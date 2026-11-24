package com.example.data.repository

import com.example.data.provider.ProviderError
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class TwelveDataAccount(
    val slot: String, // "primary" or "backup"
    val apiKey: String
)

data class AngelOneAccount(
    val slot: String, // "primary" or "backup"
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
}

data class ProviderFailoverStatus(
    val backupConfigured: Boolean,
    val activeSlot: String, // "primary", "backup", "exhausted", "unconfigured"
    val configuredCount: Int,
    val failoverNote: String? = null
)

/**
 * Coordinates automatic Primary -> Backup failover, per-account rate-limit/quota cooldowns,
 * and empty-forward-range cooldowns (preventing repeated weekend/closed-market API calls).
 */
class ProviderFailoverManager(
    private val clock: () -> Instant = { Instant.now() }
) {
    private data class SlotCooldown(
        val until: Instant,
        val category: String,
        val message: String
    )

    private val slotCooldowns = ConcurrentHashMap<String, SlotCooldown>()
    private val emptyForwardCooldowns = ConcurrentHashMap<String, Instant>()

    private fun slotKey(provider: String, slot: String): String =
        "${provider.lowercase(Locale.US)}:${slot.lowercase(Locale.US)}"

    private fun forwardKey(provider: String, symbol: String): String =
        "${provider.lowercase(Locale.US)}:${symbol.trim().uppercase(Locale.US)}"

    fun twelveDataAccounts(secrets: Map<String, String>): List<TwelveDataAccount> {
        val list = mutableListOf<TwelveDataAccount>()
        secrets["api_key"]?.trim()?.takeIf { it.isNotEmpty() }?.let {
            list += TwelveDataAccount(SLOT_PRIMARY, it)
        }
        secrets["backup_api_key"]?.trim()?.takeIf { it.isNotEmpty() }?.let {
            list += TwelveDataAccount(SLOT_BACKUP, it)
        }
        return list
    }

    fun angelOneAccounts(secrets: Map<String, String>): List<AngelOneAccount> {
        val list = mutableListOf<AngelOneAccount>()
        val pKey = secrets["api_key"]?.trim().orEmpty()
        val pCid = secrets["client_id"]?.trim().orEmpty()
        val pPwd = secrets["password"]?.trim().orEmpty()
        val pTotp = secrets["totp_secret"]?.trim().orEmpty()
        if (pKey.isNotEmpty() && pCid.isNotEmpty() && pPwd.isNotEmpty() && pTotp.isNotEmpty()) {
            list += AngelOneAccount(SLOT_PRIMARY, pKey, pCid, pPwd, pTotp)
        }

        val bKey = secrets["backup_api_key"]?.trim().orEmpty()
        val bCid = secrets["backup_client_id"]?.trim().orEmpty()
        val bPwd = secrets["backup_password"]?.trim().orEmpty()
        val bTotp = secrets["backup_totp_secret"]?.trim().orEmpty()
        if (bKey.isNotEmpty() && bCid.isNotEmpty() && bPwd.isNotEmpty() && bTotp.isNotEmpty()) {
            list += AngelOneAccount(SLOT_BACKUP, bKey, bCid, bPwd, bTotp)
        }
        return list
    }

    fun hasBackupCredentials(provider: String, secrets: Map<String, String>): Boolean = when (provider) {
        "twelve_data" -> !secrets["backup_api_key"].isNullOrBlank()
        "angel_one" -> angelOneAccounts(secrets).any { it.slot == SLOT_BACKUP }
        else -> false
    }

    fun isSlotCooledDown(provider: String, slot: String): Boolean {
        val key = slotKey(provider, slot)
        val cd = slotCooldowns[key] ?: return false
        val now = clock()
        if (!now.isBefore(cd.until)) {
            slotCooldowns.remove(key)
            return false
        }
        return true
    }

    fun getSlotCooldownMessage(provider: String, slot: String): String? {
        val key = slotKey(provider, slot)
        val cd = slotCooldowns[key] ?: return null
        val now = clock()
        if (!now.isBefore(cd.until)) {
            slotCooldowns.remove(key)
            return null
        }
        return cd.message
    }

    fun shouldFailover(category: String, httpCode: Int?): Boolean {
        if (category in setOf("quota_exceeded", "rate_limited", "auth_error")) return true
        if (httpCode != null && (httpCode == 429 || httpCode == 401 || httpCode == 403 || httpCode >= 500)) return true
        return false
    }

    fun recordSlotSuccess(provider: String, slot: String) {
        slotCooldowns.remove(slotKey(provider, slot))
    }

    fun recordSlotFailure(provider: String, slot: String, category: String, httpCode: Int?, message: String?) {
        val now = clock()
        val until: Instant? = when {
            category == "quota_exceeded" -> {
                // Daily credit limits (e.g. Twelve Data 800/day) reset at UTC midnight
                val nextUtcMidnight = now.atOffset(ZoneOffset.UTC)
                    .toLocalDate()
                    .plusDays(1)
                    .atStartOfDay()
                    .toInstant(ZoneOffset.UTC)
                val minWait = now.plus(Duration.ofMinutes(15))
                if (nextUtcMidnight.isAfter(minWait)) nextUtcMidnight else minWait
            }
            category == "rate_limited" || httpCode == 429 -> {
                // Minute window / burst limit
                now.plus(Duration.ofSeconds(65))
            }
            category == "auth_error" || httpCode == 401 || httpCode == 403 -> {
                // Broken API key/login — pause this slot for 15m so backup takes traffic cleanly
                now.plus(Duration.ofMinutes(15))
            }
            else -> null
        }
        if (until != null) {
            slotCooldowns[slotKey(provider, slot)] = SlotCooldown(
                until = until,
                category = category,
                message = message?.take(220) ?: category
            )
        }
    }

    fun resetProvider(provider: String) {
        slotCooldowns.remove(slotKey(provider, SLOT_PRIMARY))
        slotCooldowns.remove(slotKey(provider, SLOT_BACKUP))
        val prefix = "${provider.lowercase(Locale.US)}:"
        emptyForwardCooldowns.keys.filter { it.startsWith(prefix) }.forEach { emptyForwardCooldowns.remove(it) }
    }

    fun <T> selectOrderedAccounts(
        provider: String,
        accounts: List<T>,
        slotOf: (T) -> String
    ): Pair<List<T>, ProviderError?> {
        if (accounts.isEmpty()) return emptyList<T>() to null
        val ready = accounts.filter { !isSlotCooledDown(provider, slotOf(it)) }
        if (ready.isNotEmpty()) return ready to null

        // Every configured account is currently in cooldown — avoid spamming 429/auth errors
        val now = clock()
        val activeCooldowns = accounts.mapNotNull { acc ->
            slotCooldowns[slotKey(provider, slotOf(acc))]?.takeIf { now.isBefore(it.until) }
        }
        val earliest = activeCooldowns.minByOrNull { it.until }
        val category = earliest?.category ?: "rate_limited"
        val minsLeft = earliest?.let { Duration.between(now, it.until).toMinutes().coerceAtLeast(1) } ?: 1L
        val slotLabel = if (accounts.size > 1) "All ${accounts.size} configured $provider accounts" else "$provider primary account"
        val reason = earliest?.message ?: category
        val httpCode = if (category in setOf("quota_exceeded", "rate_limited")) 429 else null
        return emptyList<T>() to ProviderError(
            "$slotLabel paused (~${minsLeft}m cooldown — $reason)",
            httpCode = httpCode,
            category = category
        )
    }

    fun isForwardFetchCooledDown(provider: String, symbol: String): Boolean {
        val key = forwardKey(provider, symbol)
        val until = emptyForwardCooldowns[key] ?: return false
        val now = clock()
        if (!now.isBefore(until)) {
            emptyForwardCooldowns.remove(key)
            return false
        }
        return true
    }

    fun markEmptyForwardFetch(provider: String, symbol: String) {
        emptyForwardCooldowns[forwardKey(provider, symbol)] = clock().plus(EMPTY_FORWARD_COOLDOWN)
    }

    fun clearEmptyForwardFetch(provider: String, symbol: String) {
        emptyForwardCooldowns.remove(forwardKey(provider, symbol))
    }

    fun getFailoverStatus(provider: String, secrets: Map<String, String>): ProviderFailoverStatus {
        val slots: List<String> = when (provider) {
            "twelve_data" -> twelveDataAccounts(secrets).map { it.slot }
            "angel_one" -> angelOneAccounts(secrets).map { it.slot }
            else -> return ProviderFailoverStatus(false, SLOT_PRIMARY, if (secrets.isNotEmpty()) 1 else 0, null)
        }
        val hasBackup = SLOT_BACKUP in slots
        if (slots.isEmpty()) {
            return ProviderFailoverStatus(false, "unconfigured", 0, null)
        }
        val primaryReady = SLOT_PRIMARY in slots && !isSlotCooledDown(provider, SLOT_PRIMARY)
        val backupReady = SLOT_BACKUP in slots && !isSlotCooledDown(provider, SLOT_BACKUP)
        val primaryCd = slotCooldowns[slotKey(provider, SLOT_PRIMARY)]
        val backupCd = slotCooldowns[slotKey(provider, SLOT_BACKUP)]

        return when {
            primaryReady -> ProviderFailoverStatus(
                backupConfigured = hasBackup,
                activeSlot = SLOT_PRIMARY,
                configuredCount = slots.size,
                failoverNote = if (hasBackup) "Backup ready (auto-failover enabled)" else null
            )
            backupReady -> ProviderFailoverStatus(
                backupConfigured = true,
                activeSlot = SLOT_BACKUP,
                configuredCount = slots.size,
                failoverNote = "Auto-routed to Backup API (Primary paused: ${primaryCd?.category ?: "limit"})"
            )
            else -> ProviderFailoverStatus(
                backupConfigured = hasBackup,
                activeSlot = "exhausted",
                configuredCount = slots.size,
                failoverNote = if (hasBackup) {
                    "Primary (${primaryCd?.category ?: "paused"}) & Backup (${backupCd?.category ?: "paused"}) both paused"
                } else {
                    "Primary paused (${primaryCd?.category ?: "limit"}) — add a Backup API key to auto-failover"
                }
            )
        }
    }

    companion object {
        const val SLOT_PRIMARY = "primary"
        const val SLOT_BACKUP = "backup"
        val EMPTY_FORWARD_COOLDOWN: Duration = Duration.ofMinutes(5)

        val TWELVE_DATA_BACKUP_KEYS = listOf("backup_api_key")
        val ANGEL_ONE_BACKUP_KEYS = listOf("backup_api_key", "backup_client_id", "backup_password", "backup_totp_secret")
    }
}
