package com.example.data.provider

import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class ApiAccountSlot(val id: String, val label: String) {
    PRIMARY("primary", "Primary"),
    BACKUP("backup", "Backup")
}

data class TwelveDataAccount(
    val slot: ApiAccountSlot,
    val apiKey: String
)

data class AngelOneAccount(
    val slot: ApiAccountSlot,
    val apiKey: String,
    val clientId: String,
    val password: String,
    val totpSecret: String
) {
    fun toSecretsMap(): Map<String, String> = mapOf(
        "api_key" to apiKey,
        "client_id" to clientId,
        "password" to password,
        "totp_secret" to totpSecret
    )

    fun isComplete(): Boolean =
        apiKey.isNotBlank() && clientId.isNotBlank() && password.isNotBlank() && totpSecret.isNotBlank()
}

data class SlotCooldown(
    val cooldownUntil: Instant,
    val category: String,
    val reason: String
)

data class ProviderFailoverStatus(
    val provider: String,
    val primaryConfigured: Boolean,
    val backupConfigured: Boolean,
    val activeSlot: String, // "primary", "backup", "exhausted", "none"
    val failoverActive: Boolean,
    val failoverNote: String?,
    val primaryCooldownUntil: String?,
    val primaryCooldownReason: String?,
    val backupCooldownUntil: String?,
    val backupCooldownReason: String?
)

/**
 * Coordinates primary + secondary (backup) API account selection, per-slot cooldowns
 * after rate-limit / quota / auth failures, and closed-market empty-tail fetch deduplication.
 *
 * Pure Kotlin & thread-safe so it can be unit-tested on JVM without Android dependencies.
 */
class ProviderFailoverManager(
    private val clock: () -> Instant = { Instant.now() }
) {
    private data class SlotKey(val provider: String, val slot: ApiAccountSlot)
    private data class EmptyTailState(val cooldownUntil: Instant, val consecutiveEmpty: Int)

    private val cooldowns = mutableMapOf<SlotKey, SlotCooldown>()
    private val lastActiveSlot = mutableMapOf<String, ApiAccountSlot>()
    private val emptyTailCooldowns = mutableMapOf<String, EmptyTailState>()

    private val shortTimeFmt = DateTimeFormatter.ofPattern("HH:mm 'UTC'", Locale.US).withZone(ZoneOffset.UTC)

    @Synchronized
    fun getTwelveDataAccounts(secrets: Map<String, String>): List<TwelveDataAccount> {
        val list = mutableListOf<TwelveDataAccount>()
        val primaryKey = secrets["api_key"].orEmpty().trim()
        val backupKey = secrets["backup_api_key"].orEmpty().trim()
        if (primaryKey.isNotEmpty()) list += TwelveDataAccount(ApiAccountSlot.PRIMARY, primaryKey)
        if (backupKey.isNotEmpty()) list += TwelveDataAccount(ApiAccountSlot.BACKUP, backupKey)
        return list
    }

    @Synchronized
    fun getAngelOneAccounts(secrets: Map<String, String>): List<AngelOneAccount> {
        val list = mutableListOf<AngelOneAccount>()
        val pKey = secrets["api_key"].orEmpty().trim()
        val pClient = secrets["client_id"].orEmpty().trim()
        val pPass = secrets["password"].orEmpty().trim()
        val pTotp = secrets["totp_secret"].orEmpty().trim()
        if (pKey.isNotEmpty() || pClient.isNotEmpty()) {
            list += AngelOneAccount(ApiAccountSlot.PRIMARY, pKey, pClient, pPass, pTotp)
        }
        val bKey = secrets["backup_api_key"].orEmpty().trim()
        val bClient = secrets["backup_client_id"].orEmpty().trim()
        val bPass = secrets["backup_password"].orEmpty().trim()
        val bTotp = secrets["backup_totp_secret"].orEmpty().trim()
        if (bKey.isNotEmpty() || bClient.isNotEmpty()) {
            list += AngelOneAccount(ApiAccountSlot.BACKUP, bKey, bClient, bPass, bTotp)
        }
        return list
    }

    @Synchronized
    fun getAngelOneAccountForSlot(secrets: Map<String, String>, slotId: String): AngelOneAccount? {
        val target = if (slotId.equals("backup", ignoreCase = true)) ApiAccountSlot.BACKUP else ApiAccountSlot.PRIMARY
        return getAngelOneAccounts(secrets).firstOrNull { it.slot == target }
    }

    fun hasPrimaryConfigured(provider: String, secrets: Map<String, String>): Boolean = when (provider) {
        "twelve_data" -> secrets["api_key"].orEmpty().isNotBlank()
        "angel_one" -> secrets["api_key"].orEmpty().isNotBlank() &&
            secrets["client_id"].orEmpty().isNotBlank() &&
            secrets["password"].orEmpty().isNotBlank() &&
            secrets["totp_secret"].orEmpty().isNotBlank()
        else -> secrets.isNotEmpty()
    }

    fun hasBackupConfigured(provider: String, secrets: Map<String, String>): Boolean = when (provider) {
        "twelve_data" -> secrets["backup_api_key"].orEmpty().isNotBlank()
        "angel_one" -> secrets["backup_api_key"].orEmpty().isNotBlank() &&
            secrets["backup_client_id"].orEmpty().isNotBlank() &&
            secrets["backup_password"].orEmpty().isNotBlank() &&
            secrets["backup_totp_secret"].orEmpty().isNotBlank()
        else -> false
    }

    /**
     * Returns configured accounts that are currently eligible (not in cooldown),
     * preferring PRIMARY first, or BACKUP first while PRIMARY is cooling down.
     */
    @Synchronized
    fun <T> selectAvailableAccounts(
        provider: String,
        configured: List<T>,
        slotOf: (T) -> ApiAccountSlot
    ): List<T> {
        val now = clock()
        pruneExpired(now)
        return configured.filter { acct ->
            val cd = cooldowns[SlotKey(provider, slotOf(acct))]
            cd == null || !now.isBefore(cd.cooldownUntil)
        }.sortedBy { acct ->
            // Prefer PRIMARY whenever available so traffic automatically returns once cooldown expires.
            if (slotOf(acct) == ApiAccountSlot.PRIMARY) 0 else 1
        }
    }

    /**
     * Builds a concise diagnostic error when all configured accounts for a provider are cooling down.
     */
    @Synchronized
    fun describeAllCoolingDown(provider: String): Pair<String, String> {
        val now = clock()
        pruneExpired(now)
        val pCd = cooldowns[SlotKey(provider, ApiAccountSlot.PRIMARY)]
        val bCd = cooldowns[SlotKey(provider, ApiAccountSlot.BACKUP)]
        val active = listOfNotNull(
            pCd?.let { "Primary (${it.category} until ${shortTimeFmt.format(it.cooldownUntil)})" },
            bCd?.let { "Backup (${it.category} until ${shortTimeFmt.format(it.cooldownUntil)})" }
        )
        val category = when {
            pCd?.category == "quota_exceeded" || bCd?.category == "quota_exceeded" -> "quota_exceeded"
            pCd?.category == "rate_limited" || bCd?.category == "rate_limited" -> "rate_limited"
            pCd?.category == "auth_error" || bCd?.category == "auth_error" -> "auth_error"
            else -> "rate_limited"
        }
        val label = when (provider) {
            "twelve_data" -> "Twelve Data"
            "angel_one" -> "Angel One"
            else -> provider
        }
        val msg = "$label paused — all configured API accounts are cooling down: ${active.joinToString("; ")}."
        return category to msg
    }

    fun shouldFailover(category: String, httpCode: Int?): Boolean {
        if (category in setOf("quota_exceeded", "rate_limited", "auth_error")) return true
        if (httpCode != null && (httpCode == 429 || httpCode == 401 || httpCode == 403 || httpCode >= 500)) return true
        return false
    }

    @Synchronized
    fun recordSuccess(provider: String, slot: ApiAccountSlot) {
        cooldowns.remove(SlotKey(provider, slot))
        lastActiveSlot[provider] = slot
    }

    @Synchronized
    fun recordFailure(
        provider: String,
        slot: ApiAccountSlot,
        category: String,
        httpCode: Int?,
        message: String?
    ) {
        val now = clock()
        val cooldownDuration: Duration? = when {
            category == "quota_exceeded" -> {
                // Twelve Data daily credit quota resets at UTC midnight.
                val nextUtcMidnight = now.atZone(ZoneOffset.UTC)
                    .toLocalDate()
                    .plusDays(1)
                    .atStartOfDay(ZoneOffset.UTC)
                    .toInstant()
                val untilReset = Duration.between(now, nextUtcMidnight)
                if (untilReset.seconds < 60L) Duration.ofMinutes(5) else untilReset
            }
            category == "rate_limited" || httpCode == 429 -> {
                Duration.ofSeconds(65)
            }
            category == "auth_error" || httpCode == 401 || httpCode == 403 -> {
                Duration.ofMinutes(15)
            }
            else -> null
        }

        if (cooldownDuration != null) {
            cooldowns[SlotKey(provider, slot)] = SlotCooldown(
                cooldownUntil = now.plus(cooldownDuration),
                category = category,
                reason = ProviderErrorClassifier.sanitize(message).ifBlank { category }
            )
        }
    }

    @Synchronized
    fun resetProvider(provider: String) {
        cooldowns.keys.removeAll { it.provider == provider }
        lastActiveSlot.remove(provider)
    }

    @Synchronized
    fun isEmptyTailCoolingDown(provider: String, cacheSymbol: String): Boolean {
        val now = clock()
        val key = "$provider:$cacheSymbol"
        val state = emptyTailCooldowns[key] ?: return false
        if (!now.isBefore(state.cooldownUntil)) {
            return false
        }
        return true
    }

    @Synchronized
    fun recordEmptyTail(provider: String, cacheSymbol: String) {
        val now = clock()
        val key = "$provider:$cacheSymbol"
        val prevCount = emptyTailCooldowns[key]?.consecutiveEmpty ?: 0
        val nextCount = prevCount + 1
        // Escalate empty-tail pause (3m -> 10m -> 20m) so closed markets / weekends don't burn API credits
        // while every active signal on the same symbol reuses cached SQLite candles.
        val waitMinutes = when {
            nextCount == 1 -> 3L
            nextCount == 2 -> 10L
            else -> 20L
        }
        emptyTailCooldowns[key] = EmptyTailState(
            cooldownUntil = now.plus(Duration.ofMinutes(waitMinutes)),
            consecutiveEmpty = nextCount
        )
    }

    @Synchronized
    fun clearEmptyTail(provider: String, cacheSymbol: String) {
        emptyTailCooldowns.remove("$provider:$cacheSymbol")
    }

    @Synchronized
    fun getStatusSnapshot(provider: String, secrets: Map<String, String>): ProviderFailoverStatus {
        val now = clock()
        pruneExpired(now)
        val hasPrimary = hasPrimaryConfigured(provider, secrets)
        val hasBackup = hasBackupConfigured(provider, secrets)
        val pCd = cooldowns[SlotKey(provider, ApiAccountSlot.PRIMARY)]
        val bCd = cooldowns[SlotKey(provider, ApiAccountSlot.BACKUP)]
        val primaryCooling = pCd != null && now.isBefore(pCd.cooldownUntil)
        val backupCooling = bCd != null && now.isBefore(bCd.cooldownUntil)

        val activeSlot = when {
            !hasPrimary && !hasBackup -> "none"
            hasPrimary && !primaryCooling -> "primary"
            hasBackup && !backupCooling -> "backup"
            else -> "exhausted"
        }
        val failoverActive = hasPrimary && primaryCooling && hasBackup && !backupCooling
        val note = when {
            failoverActive -> "Failover active: using Backup API (Primary ${pCd?.category} until ${shortTimeFmt.format(pCd!!.cooldownUntil)})"
            activeSlot == "exhausted" && hasBackup -> "Primary & Backup both cooling down until ${shortTimeFmt.format(minOf(pCd!!.cooldownUntil, bCd!!.cooldownUntil))}"
            activeSlot == "exhausted" && pCd != null -> "Primary cooling down (${pCd.category} until ${shortTimeFmt.format(pCd.cooldownUntil)}) — add a Backup API key for automatic failover"
            hasPrimary && hasBackup -> "Primary active • Backup ready for failover"
            hasBackup && !hasPrimary -> "Backup key active (Primary not configured)"
            else -> null
        }

        return ProviderFailoverStatus(
            provider = provider,
            primaryConfigured = hasPrimary,
            backupConfigured = hasBackup,
            activeSlot = activeSlot,
            failoverActive = failoverActive,
            failoverNote = note,
            primaryCooldownUntil = pCd?.cooldownUntil?.toString(),
            primaryCooldownReason = pCd?.category,
            backupCooldownUntil = bCd?.cooldownUntil?.toString(),
            backupCooldownReason = bCd?.category
        )
    }

    private fun pruneExpired(now: Instant) {
        val iter = cooldowns.entries.iterator()
        while (iter.hasNext()) {
            if (!now.isBefore(iter.next().value.cooldownUntil)) iter.remove()
        }
    }

    companion object {
        val TWELVE_DATA_BACKUP_KEYS = listOf("backup_api_key")
        val ANGEL_ONE_BACKUP_KEYS = listOf(
            "backup_api_key",
            "backup_client_id",
            "backup_password",
            "backup_totp_secret"
        )
    }
}
