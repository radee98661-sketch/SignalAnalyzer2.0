package com.example.data.repository

import android.content.Context
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.example.data.local.*
import com.example.data.provider.GoogleSheetsSyncClient
import com.example.data.provider.MarketDataProviders
import com.example.data.provider.ProviderError
import com.example.data.provider.ProviderErrorClassifier
import com.example.domain.model.*
import com.example.domain.parser.DefaultRulesEngine
import com.example.domain.parser.SignalParser
import com.example.util.JsonUtils
import com.example.util.TimeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

data class EnrichedSignal(
    val row: SignalJoinedRow,
    val targets: List<Double>,
    val additionalEntries: List<Double>,
    val events: List<TradeEvent>,
    val legs: List<LegResult>,
    val pnlAmount: Double,
    val pnlCurrency: String,
    val tradesCount: Int,
    val ageMinutes: Double,
    val isAutoScheduled: Boolean,
    val isRecent: Boolean,
    val isManualNeeded: Boolean,
    val isHistorical: Boolean,
    val isDefaultSl: Boolean,
    val isDefaultTp: Boolean,
    val statusReason: String,
    val maxTpHit: Int = 0,
    val allTargetsHit: Boolean = false,
    val closedBySlAfterTp: Boolean = false,
    val tp1PnlAmount: Double = 0.0,
    val tp2PnlAmount: Double = 0.0,
    val tp3PnlAmount: Double = 0.0,
    val highestTpPnlAmount: Double = 0.0,
    val slPnlAmount: Double = 0.0,
    val tpLevels: List<TargetLevelPnl> = emptyList(),
    val monitoringPhase: String = "unscheduled"
) {
    fun pnlForBasis(basis: String): Double {
        val b = basis.trim().lowercase(Locale.US)
        val vStatus = row.verificationStatus
        return when (b) {
            "tp2" -> when {
                maxTpHit >= 2 -> tp2PnlAmount
                maxTpHit == 1 -> tp1PnlAmount
                vStatus == "stop_loss_hit" -> slPnlAmount
                else -> 0.0
            }
            "tp3" -> when {
                maxTpHit >= 3 -> tp3PnlAmount
                maxTpHit == 2 -> tp2PnlAmount
                maxTpHit == 1 -> tp1PnlAmount
                vStatus == "stop_loss_hit" -> slPnlAmount
                else -> 0.0
            }
            "highest_tp" -> when {
                vStatus == "target_hit" -> highestTpPnlAmount
                vStatus == "stop_loss_hit" -> slPnlAmount
                else -> 0.0
            }
            else -> when {
                vStatus == "target_hit" -> if (maxTpHit >= 1 && tp1PnlAmount != 0.0) tp1PnlAmount else pnlAmount
                vStatus == "stop_loss_hit" -> if (slPnlAmount != 0.0) slPnlAmount else pnlAmount
                else -> 0.0
            }
        }
    }
}

data class OverviewData(
    val notifications: Int,
    val parsed: Int,
    val today: Int,
    val todayParsed: Int = 0,
    val selectedCategory: String = "",
    val targetHits: Int,
    val stopLossHits: Int,
    val pending: Int,
    val entryRate: Double,
    val winRate: Double,
    val pnlInr: Double,
    val pnlUsd: Double,
    val categories: List<Pair<String, Int>>,
    val channels: List<Pair<String, Int>>,
    val recentNotifications: List<RawNotificationEntity>,
    val recentSignals: List<EnrichedSignal>,
    val latestSync: SynchronizationLogEntity?,
    val integrations: List<IntegrationEntity>
)

data class InboxChannelItem(
    val channel: String,
    val category: String,
    val totalMessages: Int,
    val todayMessages: Int,
    val parsedCount: Int,
    val needsReviewCount: Int,
    val lastMessage: String,
    val lastTime: String,
    val lastRawId: Long
)

data class InboxChannelHistory(
    val channel: String,
    val category: String,
    val totalCount: Int,
    val parsedCount: Int,
    val needsReviewCount: Int,
    val hasMore: Boolean,
    val messages: List<NotificationJoinedRow>
)

data class SignalChartData(
    val signalId: Long,
    val symbol: String?,
    val resolvedSymbol: String,
    val tradingSymbol: String?,
    val provider: String,
    val direction: String?,
    val notificationTime: String,
    val entryPrice: Double?,
    val additionalEntries: List<Double>,
    val entryMode: String,
    val stopLoss: Double?,
    val targets: List<Double>,
    val verificationStatus: String,
    val detail: String?,
    val entryHitAt: String?,
    val targetHitAt: String?,
    val stopLossHitAt: String?,
    val events: List<TradeEvent>,
    val candles: List<Candle>
)

data class SignalEditPayload(
    val symbol: String? = null,
    val hasSymbol: Boolean = false,
    val direction: String? = null,
    val hasDirection: Boolean = false,
    val entryPrice: Double? = null,
    val hasEntryPrice: Boolean = false,
    val additionalEntries: List<Double>? = null,
    val stopLoss: Double? = null,
    val hasStopLoss: Boolean = false,
    val targets: List<Double>? = null,
    val optionType: String? = null,
    val hasOptionType: Boolean = false,
    val strikePrice: Double? = null,
    val hasStrikePrice: Boolean = false,
    val expiryDate: String? = null,
    val hasExpiryDate: Boolean = false,
    val entryMode: String? = null,
    val manualReviewStatus: String? = null,
    val notificationTime: String? = null,
    val verificationTimeframe: String? = null,
    val reschedule: Boolean = false
)

class SignalRepository(
    private val context: Context?,
    val db: AppDatabase,
    val secretStore: SecretStore,
    val providers: MarketDataProviders,
    val ingestionService: SignalIngestionService,
    val verificationService: VerificationService,
    val syncClient: GoogleSheetsSyncClient,
    val eventBus: EventBus
) {
    private val istZone = ZoneOffset.ofHoursMinutes(5, 30)
    private val angelDateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(istZone)

    suspend fun getSettings(): Map<String, String> =
        db.coreDao().getAllSettings().associate { it.key to it.value }

    suspend fun saveSettings(values: Map<String, String>) {
        val now = TimeUtils.utcNowIso()
        val core = db.coreDao()
        for ((k, v) in values) {
            core.upsertSetting(ApplicationSettingEntity(k, v, now))
        }
        eventBus.emit(AppEvent("server.status", null, "settings_updated", null, now))
    }

    suspend fun getDefaultSlRules(): MutableMap<String, DefaultSlCategoryRule> =
        DefaultRulesEngine.rulesFromJson(db.coreDao().getSetting("default_sl_rules"))

    suspend fun saveDefaultSlRules(rules: Map<String, DefaultSlCategoryRule>) {
        val now = TimeUtils.utcNowIso()
        db.coreDao().upsertSetting(ApplicationSettingEntity("default_sl_rules", DefaultRulesEngine.rulesToJson(rules), now))
        db.coreDao().insertSystemLog(SystemLogEntity(createdAt = now, severity = "INFO", source = "settings", message = "Updated Default SL, TP1-TP3, and Lot/Dollar sizing rules"))
        eventBus.emit(AppEvent("signal.changed", null, "rules_updated", null, now))
    }

    suspend fun applyDefaultRulesToMissing(): Pair<Int, Int> {
        val rules = getDefaultSlRules()
        val candidates = db.coreDao().getSignalsMissingDefaults()
        var updated = 0
        val now = TimeUtils.utcNowIso()
        db.withTransaction {
            val core = db.coreDao()
            for (row in candidates) {
                val origSl = row.stopLoss
                val origTargets = JsonUtils.jsonToDoubleList(row.targets)
                var item = ParsedSignal(
                    category = row.category.orEmpty(),
                    symbol = row.symbol,
                    direction = row.direction,
                    entryPrice = row.entryPrice,
                    additionalEntries = JsonUtils.jsonToDoubleList(row.additionalEntries),
                    stopLoss = origSl,
                    targets = origTargets,
                    optionType = row.optionType,
                    explanation = row.parsingExplanation.orEmpty()
                )
                item = DefaultRulesEngine.applyDefaultSl(item, row.category.orEmpty(), rules)
                item = DefaultRulesEngine.applyDefaultTargets(item, row.category.orEmpty(), rules)
                if (item.stopLoss != origSl || item.targets != origTargets) {
                    core.updateSignalDefaults(
                        id = row.id,
                        stopLoss = item.stopLoss,
                        targets = JsonUtils.doubleListToJson(item.targets),
                        explanation = item.explanation,
                        updatedAt = now
                    )
                    updated++
                }
            }
            core.insertSystemLog(SystemLogEntity(createdAt = now, severity = "INFO", source = "settings", message = "Applied Default SL/TPs to $updated existing signals"))
        }
        if (updated > 0) eventBus.emit(AppEvent("signal.changed", null, "defaults_applied", "updated=$updated", now))
        return candidates.size to updated
    }

    suspend fun getOverview(category: String = ""): OverviewData {
        val core = db.coreDao()
        val todayDate = LocalDate.now().toString()
        val selectedCat = if (category.isBlank()) "" else canonicalCategory(category)

        val catMap = linkedMapOf<String, Int>()
        for (name in CATEGORY_ALIASES.keys) catMap[name] = 0
        for (item in core.getCategoryDistribution()) {
            val canon = canonicalCategory(item.name.orEmpty())
            catMap[canon] = (catMap[canon] ?: 0) + item.value
        }

        val settings = getSettings()
        val initWait = settings["verification_initial_wait_minutes"]?.toIntOrNull() ?: 15
        val repeatMins = settings["verification_repeat_minutes"]?.toIntOrNull() ?: 15
        val fastPhaseMins = settings["verification_fast_phase_minutes"]?.toIntOrNull() ?: 60
        val extendedIntervalMins = settings["verification_extended_interval_minutes"]?.toIntOrNull() ?: 60
        val rules = DefaultRulesEngine.rulesFromJson(settings["default_sl_rules"])

        if (selectedCat.isBlank()) {
            val total = core.countPrimaryNotifications()
            val parsed = core.countParsedSignals()
            val verified = core.countVerifiedSignals()
            val targets = core.countTargetHits()
            val stops = core.countStopLossHits()
            val pending = core.countPendingSignals()
            val entered = core.countEnteredSignals()
            val today = core.countTodayNotifications(todayDate)
            val todayParsed = core.queryInt(
                SimpleSQLiteQuery(
                    "SELECT COUNT(*) FROM signals s JOIN raw_notifications r ON r.id = s.raw_notification_id " +
                        "WHERE r.is_duplicate = 0 AND s.parsing_status = 'parsed' " +
                        "AND substr(COALESCE(r.notification_time, r.received_at), 1, 10) = ?",
                    arrayOf(todayDate)
                )
            )
            val pnlInr = core.sumPnlByCurrency("INR")
            val pnlUsd = core.sumPnlByCurrency("USD")

            return OverviewData(
                notifications = total,
                parsed = parsed,
                today = today,
                todayParsed = todayParsed,
                selectedCategory = "",
                targetHits = targets,
                stopLossHits = stops,
                pending = pending,
                entryRate = DefaultRulesEngine.roundValue(100.0 * entered / maxOf(parsed, 1), 1),
                winRate = DefaultRulesEngine.roundValue(100.0 * targets / maxOf(verified, 1), 1),
                pnlInr = pnlInr,
                pnlUsd = pnlUsd,
                categories = catMap.entries.map { it.key to it.value },
                channels = core.getChannelDistribution().map { (it.name ?: "Unknown") to it.value },
                recentNotifications = core.getRecentRawNotifications(),
                recentSignals = core.getRecentSignalsJoined().map {
                    enrichSignal(it, initWait, repeatMins, fastPhaseMins, extendedIntervalMins, rules)
                },
                latestSync = core.getLatestSyncLog(),
                integrations = core.getAllIntegrations()
            )
        }

        val aliases = categoryAliasesFor(selectedCat)
        val placeholders = aliases.joinToString(",") { "?" }
        val aliasArgs = aliases.toTypedArray<Any>()

        val total = core.queryInt(
            SimpleSQLiteQuery(
                "SELECT COUNT(*) FROM raw_notifications r WHERE r.is_duplicate = 0 AND LOWER(COALESCE(r.category,'')) IN ($placeholders)",
                aliasArgs
            )
        )
        val parsed = core.queryInt(
            SimpleSQLiteQuery(
                "SELECT COUNT(*) FROM signals s JOIN raw_notifications r ON r.id = s.raw_notification_id " +
                    "WHERE r.is_duplicate = 0 AND s.parsing_status = 'parsed' AND LOWER(COALESCE(r.category,'')) IN ($placeholders)",
                aliasArgs
            )
        )
        val verified = core.queryInt(
            SimpleSQLiteQuery(
                "SELECT COUNT(*) FROM signals s JOIN raw_notifications r ON r.id = s.raw_notification_id " +
                    "WHERE r.is_duplicate = 0 AND s.verification_status IN ('target_hit','stop_loss_hit') AND LOWER(COALESCE(r.category,'')) IN ($placeholders)",
                aliasArgs
            )
        )
        val targets = core.queryInt(
            SimpleSQLiteQuery(
                "SELECT COUNT(*) FROM signals s JOIN raw_notifications r ON r.id = s.raw_notification_id " +
                    "WHERE r.is_duplicate = 0 AND s.verification_status = 'target_hit' AND LOWER(COALESCE(r.category,'')) IN ($placeholders)",
                aliasArgs
            )
        )
        val stops = core.queryInt(
            SimpleSQLiteQuery(
                "SELECT COUNT(*) FROM signals s JOIN raw_notifications r ON r.id = s.raw_notification_id " +
                    "WHERE r.is_duplicate = 0 AND s.verification_status = 'stop_loss_hit' AND LOWER(COALESCE(r.category,'')) IN ($placeholders)",
                aliasArgs
            )
        )
        val pending = core.queryInt(
            SimpleSQLiteQuery(
                "SELECT COUNT(*) FROM signals s JOIN raw_notifications r ON r.id = s.raw_notification_id " +
                    "WHERE r.is_duplicate = 0 AND s.verification_status = 'pending' AND LOWER(COALESCE(r.category,'')) IN ($placeholders)",
                aliasArgs
            )
        )
        val entered = core.queryInt(
            SimpleSQLiteQuery(
                "SELECT COUNT(*) FROM signals s JOIN raw_notifications r ON r.id = s.raw_notification_id " +
                    "WHERE r.is_duplicate = 0 AND s.verification_status IN ('entry_hit','target_hit','stop_loss_hit','ambiguous') " +
                    "AND LOWER(COALESCE(r.category,'')) IN ($placeholders)",
                aliasArgs
            )
        )
        val today = core.queryInt(
            SimpleSQLiteQuery(
                "SELECT COUNT(*) FROM raw_notifications r WHERE r.is_duplicate = 0 " +
                    "AND substr(COALESCE(r.notification_time, r.received_at), 1, 10) = ? " +
                    "AND LOWER(COALESCE(r.category,'')) IN ($placeholders)",
                arrayOf<Any>(todayDate, *aliasArgs)
            )
        )
        val todayParsed = core.queryInt(
            SimpleSQLiteQuery(
                "SELECT COUNT(*) FROM signals s JOIN raw_notifications r ON r.id = s.raw_notification_id " +
                    "WHERE r.is_duplicate = 0 AND s.parsing_status = 'parsed' " +
                    "AND substr(COALESCE(r.notification_time, r.received_at), 1, 10) = ? " +
                    "AND LOWER(COALESCE(r.category,'')) IN ($placeholders)",
                arrayOf<Any>(todayDate, *aliasArgs)
            )
        )
        val pnlInr = core.queryDouble(
            SimpleSQLiteQuery(
                "SELECT ROUND(COALESCE(SUM(tr.pnl_amount), 0), 2) FROM trade_results tr " +
                    "JOIN signals s ON s.id = tr.signal_id JOIN raw_notifications r ON r.id = s.raw_notification_id " +
                    "WHERE r.is_duplicate = 0 AND tr.pnl_currency = 'INR' AND LOWER(COALESCE(r.category,'')) IN ($placeholders)",
                aliasArgs
            )
        )
        val pnlUsd = core.queryDouble(
            SimpleSQLiteQuery(
                "SELECT ROUND(COALESCE(SUM(tr.pnl_amount), 0), 2) FROM trade_results tr " +
                    "JOIN signals s ON s.id = tr.signal_id JOIN raw_notifications r ON r.id = s.raw_notification_id " +
                    "WHERE r.is_duplicate = 0 AND tr.pnl_currency = 'USD' AND LOWER(COALESCE(r.category,'')) IN ($placeholders)",
                aliasArgs
            )
        )
        val channels = core.queryNamedCounts(
            SimpleSQLiteQuery(
                "SELECT COALESCE(NULLIF(TRIM(r.channel),''), 'Unknown') AS name, COUNT(*) AS value " +
                    "FROM raw_notifications r WHERE r.is_duplicate = 0 AND LOWER(COALESCE(r.category,'')) IN ($placeholders) " +
                    "GROUP BY COALESCE(NULLIF(TRIM(r.channel),''), 'Unknown') ORDER BY value DESC LIMIT 12",
                aliasArgs
            )
        ).map { (it.name ?: "Unknown") to it.value }

        val recentRaw = core.queryRawNotifications(
            SimpleSQLiteQuery(
                "SELECT * FROM raw_notifications r WHERE LOWER(COALESCE(r.category,'')) IN ($placeholders) ORDER BY r.id DESC LIMIT 8",
                aliasArgs
            )
        )
        val recentSignals = core.querySignalsJoined(
            SimpleSQLiteQuery(
                "SELECT s.*, r.received_at, r.notification_time, r.source_app, r.channel, r.category, r.title, r.message, " +
                    "r.notification_id, r.is_duplicate, r.duplicate_of, r.duplicate_reason, r.duplicate_group_id, " +
                    "vs.first_scheduled_at, vs.next_scheduled_at, vs.last_verified_at, vs.last_result, " +
                    "vs.status AS scheduler_status, vs.timeframe AS verification_timeframe, " +
                    "tr.entry_hit_at, tr.target_hit_at, tr.stop_loss_hit_at, tr.events_json, tr.detail AS trade_detail, " +
                    "tr.pnl_amount, tr.pnl_currency, tr.trades_count, tr.legs_json, " +
                    "tr.max_tp_hit, tr.all_targets_hit, tr.closed_by_sl_after_tp, " +
                    "tr.tp1_pnl_amount, tr.tp2_pnl_amount, tr.tp3_pnl_amount, tr.highest_tp_pnl_amount, tr.sl_pnl_amount, tr.tp_levels_json " +
                    "FROM signals s JOIN raw_notifications r ON r.id = s.raw_notification_id " +
                    "LEFT JOIN verification_schedules vs ON vs.signal_id = s.id " +
                    "LEFT JOIN trade_results tr ON tr.signal_id = s.id " +
                    "WHERE r.is_duplicate = 0 AND LOWER(COALESCE(r.category,'')) IN ($placeholders) " +
                    "ORDER BY s.id DESC LIMIT 12",
                aliasArgs
            )
        ).map { enrichSignal(it, initWait, repeatMins, fastPhaseMins, extendedIntervalMins, rules) }

        return OverviewData(
            notifications = total,
            parsed = parsed,
            today = today,
            todayParsed = todayParsed,
            selectedCategory = selectedCat,
            targetHits = targets,
            stopLossHits = stops,
            pending = pending,
            entryRate = DefaultRulesEngine.roundValue(100.0 * entered / maxOf(parsed, 1), 1),
            winRate = DefaultRulesEngine.roundValue(100.0 * targets / maxOf(verified, 1), 1),
            pnlInr = pnlInr,
            pnlUsd = pnlUsd,
            categories = catMap.entries.map { it.key to it.value },
            channels = channels,
            recentNotifications = recentRaw,
            recentSignals = recentSignals,
            latestSync = core.getLatestSyncLog(),
            integrations = core.getAllIntegrations()
        )
    }

    suspend fun getSignals(
        category: String = "",
        channel: String = "",
        symbol: String = "",
        status: String = "",
        notificationFilter: String = "all",
        dateFrom: String = "",
        dateTo: String = "",
        tpFilter: String = ""
    ): List<EnrichedSignal> {
        val settings = getSettings()
        val initialWait = settings["verification_initial_wait_minutes"]?.toIntOrNull() ?: 15
        val repeatMins = settings["verification_repeat_minutes"]?.toIntOrNull() ?: 15
        val fastPhaseMins = settings["verification_fast_phase_minutes"]?.toIntOrNull() ?: 60
        val extendedIntervalMins = settings["verification_extended_interval_minutes"]?.toIntOrNull() ?: 60
        val rules = DefaultRulesEngine.rulesFromJson(settings["default_sl_rules"])

        val where = mutableListOf<String>()
        val args = mutableListOf<Any>()

        if (category.isNotBlank()) {
            val aliases = categoryAliasesFor(category)
            where += "LOWER(COALESCE(r.category,'')) IN (${aliases.joinToString(",") { "?" }})"
            args.addAll(aliases)
        }
        if (channel.isNotBlank()) {
            where += "r.channel = ?"
            args += channel
        }
        if (symbol.isNotBlank()) {
            where += "s.symbol = ?"
            args += symbol
        }
        if (dateFrom.isNotBlank()) {
            where += "substr(COALESCE(r.notification_time, r.received_at), 1, 10) >= ?"
            args += dateFrom.trim()
        }
        if (dateTo.isNotBlank()) {
            where += "substr(COALESCE(r.notification_time, r.received_at), 1, 10) <= ?"
            args += dateTo.trim()
        }

        when (notificationFilter.lowercase(Locale.US)) {
            "primary" -> where += "r.is_duplicate = 0 AND (r.duplicate_reason IS NULL OR r.duplicate_reason NOT LIKE 'signal_update:%')"
            "duplicates" -> where += "r.is_duplicate = 1"
            "updates" -> where += "r.is_duplicate = 0 AND r.duplicate_reason LIKE 'signal_update:%'"
        }

        when (val st = status.trim().lowercase(Locale.US)) {
            "needs_review" -> where += "(s.parsing_status = 'needs_review' OR s.manual_review_status = 'required')"
            "manual_needed" -> where += "(s.parsing_status = 'needs_review' OR s.manual_review_status = 'required' OR vs.status = 'manual_required')"
            "scheduled" -> where += "(vs.status IN ('scheduled','processing') OR vs.next_scheduled_at IS NOT NULL)"
            "pending", "awaiting_market_data", "historical_data_missing", "entry_hit", "target_hit", "stop_loss_hit", "ambiguous" -> {
                where += "s.verification_status = ?"
                args += st
            }
        }

        val sql = buildString {
            append(
                "SELECT s.*, r.received_at, r.notification_time, r.source_app, r.channel, r.category, r.title, r.message, " +
                    "r.notification_id, r.is_duplicate, r.duplicate_of, r.duplicate_reason, r.duplicate_group_id, " +
                    "vs.first_scheduled_at, vs.next_scheduled_at, vs.last_verified_at, vs.last_result, " +
                    "vs.status AS scheduler_status, vs.timeframe AS verification_timeframe, " +
                    "tr.entry_hit_at, tr.target_hit_at, tr.stop_loss_hit_at, tr.events_json, tr.detail AS trade_detail, " +
                    "tr.pnl_amount, tr.pnl_currency, tr.trades_count, tr.legs_json, " +
                    "tr.max_tp_hit, tr.all_targets_hit, tr.closed_by_sl_after_tp, " +
                    "tr.tp1_pnl_amount, tr.tp2_pnl_amount, tr.tp3_pnl_amount, tr.highest_tp_pnl_amount, " +
                    "tr.sl_pnl_amount, tr.tp_levels_json " +
                    "FROM signals s JOIN raw_notifications r ON r.id = s.raw_notification_id " +
                    "LEFT JOIN verification_schedules vs ON vs.signal_id = s.id " +
                    "LEFT JOIN trade_results tr ON tr.signal_id = s.id"
            )
            if (where.isNotEmpty()) append(" WHERE ").append(where.joinToString(" AND "))
            append(" ORDER BY s.id DESC LIMIT 500")
        }

        val rawRows = db.coreDao().querySignalsJoined(SimpleSQLiteQuery(sql, args.toTypedArray()))
        val enriched = rawRows.map { enrichSignal(it, initialWait, repeatMins, fastPhaseMins, extendedIntervalMins, rules) }
        return when (tpFilter.trim().lowercase(Locale.US)) {
            "tp1_plus" -> enriched.filter { it.maxTpHit >= 1 }
            "tp2_plus" -> enriched.filter { it.maxTpHit >= 2 }
            "tp3_plus" -> enriched.filter { it.maxTpHit >= 3 }
            "all_tps" -> enriched.filter { it.allTargetsHit || (it.targets.isNotEmpty() && it.maxTpHit >= it.targets.size) }
            "tp1_only" -> enriched.filter { it.maxTpHit == 1 && !it.allTargetsHit }
            "tp_then_sl" -> enriched.filter { it.closedBySlAfterTp }
            "sl_only" -> enriched.filter { it.row.verificationStatus == "stop_loss_hit" }
            "active" -> enriched.filter { it.monitoringPhase in setOf("idle_15m", "fast_15m", "hourly_1h") }
            else -> enriched
        }
    }

    fun computeFilteredPnlSummary(signals: List<EnrichedSignal>, basis: String = "tp1"): FilteredPnlSummary {
        val normBasis = basis.trim().lowercase(Locale.US).ifBlank { "tp1" }
        var tp1Hits = 0
        var tp2Hits = 0
        var tp3Hits = 0
        var allTpHits = 0
        var tpThenSlHits = 0
        var slOnlyHits = 0
        var activeMon = 0
        var pnlInr = 0.0
        var pnlUsd = 0.0
        for (s in signals) {
            if (s.maxTpHit >= 1) tp1Hits++
            if (s.maxTpHit >= 2) tp2Hits++
            if (s.maxTpHit >= 3) tp3Hits++
            if (s.allTargetsHit || (s.targets.isNotEmpty() && s.maxTpHit >= s.targets.size)) allTpHits++
            if (s.closedBySlAfterTp) tpThenSlHits++
            if (s.row.verificationStatus == "stop_loss_hit") slOnlyHits++
            if (s.monitoringPhase in setOf("idle_15m", "fast_15m", "hourly_1h")) activeMon++
            val pnl = s.pnlForBasis(normBasis)
            if (s.pnlCurrency.equals("USD", ignoreCase = true)) {
                pnlUsd += pnl
            } else {
                pnlInr += pnl
            }
        }
        val decided = tp1Hits + slOnlyHits
        val winRate = if (decided > 0) DefaultRulesEngine.roundValue(100.0 * tp1Hits / decided, 1) else 0.0
        return FilteredPnlSummary(
            basis = normBasis,
            totalSignals = signals.size,
            tp1Hits = tp1Hits,
            tp2Hits = tp2Hits,
            tp3Hits = tp3Hits,
            allTpHits = allTpHits,
            tpThenSlHits = tpThenSlHits,
            slOnlyHits = slOnlyHits,
            activeMonitoring = activeMon,
            winRate = winRate,
            pnlInr = DefaultRulesEngine.roundValue(pnlInr, 2),
            pnlUsd = DefaultRulesEngine.roundValue(pnlUsd, 2)
        )
    }

    suspend fun getFilteredChannelAnalytics(
        category: String = "",
        channel: String = "",
        dateFrom: String = "",
        dateTo: String = "",
        pnlBasis: String = "tp1"
    ): List<FilteredChannelAnalyticsRow> {
        val normBasis = pnlBasis.trim().lowercase(Locale.US).ifBlank { "tp1" }
        val today = LocalDate.now().toString()
        val all = getSignals(
            category = category,
            channel = channel,
            notificationFilter = "primary",
            dateFrom = dateFrom,
            dateTo = dateTo
        )
        return all.groupBy { it.row.channel?.ifBlank { "Unknown" } ?: "Unknown" }
            .map { (ch, items) ->
                var todayTotal = 0
                var todayTp = 0
                var todaySl = 0
                var parsed = 0
                var tradesTriggered = 0
                var targets = 0
                var tp1Hits = 0
                var tp2Hits = 0
                var tp3Hits = 0
                var allTpHits = 0
                var tpThenSlHits = 0
                var stops = 0
                var pending = 0
                var pnlInr = 0.0
                var pnlUsd = 0.0
                var todayPnlInr = 0.0
                var todayPnlUsd = 0.0

                for (s in items) {
                    val sigDate = (s.row.notificationTime ?: s.row.receivedAt ?: "").take(10)
                    val isToday = sigDate == today
                    if (isToday) todayTotal++
                    if (s.row.parsingStatus in setOf("parsed", "manually_corrected")) parsed++
                    tradesTriggered += s.tradesCount
                    val vStatus = s.row.verificationStatus
                    if (vStatus == "target_hit") {
                        targets++
                        if (isToday) todayTp++
                    } else if (vStatus == "stop_loss_hit") {
                        stops++
                        if (isToday) todaySl++
                    } else if (vStatus in setOf("pending", "awaiting_market_data", "entry_hit")) {
                        pending++
                    }
                    if (s.maxTpHit >= 1) tp1Hits++
                    if (s.maxTpHit >= 2) tp2Hits++
                    if (s.maxTpHit >= 3) tp3Hits++
                    if (s.allTargetsHit || (s.targets.isNotEmpty() && s.maxTpHit >= s.targets.size)) allTpHits++
                    if (s.closedBySlAfterTp) tpThenSlHits++
                    val pnl = s.pnlForBasis(normBasis)
                    if (s.pnlCurrency.equals("USD", ignoreCase = true)) {
                        pnlUsd += pnl
                        if (isToday) todayPnlUsd += pnl
                    } else {
                        pnlInr += pnl
                        if (isToday) todayPnlInr += pnl
                    }
                }
                val decided = targets + stops
                val winRate = if (decided > 0) DefaultRulesEngine.roundValue(100.0 * targets / decided, 1) else 0.0
                FilteredChannelAnalyticsRow(
                    channel = ch,
                    total = items.size,
                    todayTotal = todayTotal,
                    todayTp = todayTp,
                    todaySl = todaySl,
                    parsed = parsed,
                    tradesTriggered = tradesTriggered,
                    targets = targets,
                    tp1Hits = tp1Hits,
                    tp2Hits = tp2Hits,
                    tp3Hits = tp3Hits,
                    allTpHits = allTpHits,
                    tpThenSlHits = tpThenSlHits,
                    stops = stops,
                    pending = pending,
                    winRate = winRate,
                    pnlInr = DefaultRulesEngine.roundValue(pnlInr, 2),
                    pnlUsd = DefaultRulesEngine.roundValue(pnlUsd, 2),
                    todayPnlInr = DefaultRulesEngine.roundValue(todayPnlInr, 2),
                    todayPnlUsd = DefaultRulesEngine.roundValue(todayPnlUsd, 2),
                    pnlBasis = normBasis
                )
            }
            .sortedWith(compareByDescending<FilteredChannelAnalyticsRow> { it.total }.thenBy { it.channel })
    }

    suspend fun getApiHealthSummary(): ApiHealthSummary {
        val now = Instant.now()
        val since24h = now.minus(Duration.ofHours(24)).toString()
        val alertCutoff = now.minus(Duration.ofMinutes(30))
        val logs24h = db.marketDao().getApiLogsSince(since24h, 500)
        val twelveUsage = getTwelveDataUsage()
        val integrations = db.coreDao().getAllIntegrations().associateBy { it.provider }

        val providersOrder = listOf(
            "binance" to "Binance Futures",
            "twelve_data" to "Twelve Data",
            "angel_one" to "Angel One SmartAPI",
            "google_sheets" to "Google Sheets Sync"
        )

        val providerCards = mutableListOf<ApiProviderHealth>()
        val alerts = mutableListOf<ApiHealthAlert>()

        for ((id, label) in providersOrder) {
            val pLogs = logs24h.filter { it.provider.equals(id, ignoreCase = true) }
            val calls24h = pLogs.size
            val errLogs = pLogs.filter { it.status != "success" }
            val errors24h = errLogs.size
            val rateLimitHits24h = errLogs.count { it.errorCategory in setOf("rate_limited", "quota_exceeded") }
            val lastSuccess = pLogs.firstOrNull { it.status == "success" }
            val lastError = errLogs.firstOrNull()
            val latest = pLogs.firstOrNull()

            val lastErrorDt = lastError?.requestedAt?.let { TimeUtils.parseUtcInstant(it) }
            val latestIsRecentError = latest != null && latest.status != "success" &&
                (lastErrorDt == null || !lastErrorDt.isBefore(alertCutoff))

            var status = when {
                latestIsRecentError && lastError?.errorCategory in setOf("rate_limited", "quota_exceeded") -> "rate_limited"
                latestIsRecentError && lastError?.errorCategory == "auth_error" -> "error"
                latestIsRecentError -> "degraded"
                calls24h == 0 && id != "binance" && integrations[id] == null -> "unconfigured"
                else -> "healthy"
            }

            if (id == "twelve_data") {
                val dailyRem = twelveUsage["daily_remaining"] ?: 800.0
                val dailyAllow = twelveUsage["daily_allowance"] ?: 800.0
                val minRem = twelveUsage["minute_remaining"] ?: 8.0
                if (dailyRem <= 0.0 || minRem <= 0.0) {
                    status = "rate_limited"
                    alerts += ApiHealthAlert(
                        provider = id,
                        severity = "error",
                        category = if (dailyRem <= 0.0) "quota_exceeded" else "rate_limited",
                        httpCode = lastError?.httpCode,
                        message = if (dailyRem <= 0.0) {
                            "Twelve Data daily credit limit reached (${twelveUsage["daily_used"]?.toInt() ?: 0}/${dailyAllow.toInt()}). Forex/XAU fetches paused until quota resets."
                        } else {
                            "Twelve Data per-minute credit limit reached (${twelveUsage["minute_used"]?.toInt() ?: 0}/${twelveUsage["minute_allowance"]?.toInt() ?: 8}). Retrying next minute."
                        },
                        occurredAt = lastError?.requestedAt ?: now.toString()
                    )
                } else if (dailyAllow > 0.0 && dailyRem <= dailyAllow * 0.1) {
                    if (status == "healthy") status = "degraded"
                    alerts += ApiHealthAlert(
                        provider = id,
                        severity = "warning",
                        category = "quota_exceeded",
                        httpCode = null,
                        message = "Twelve Data daily credits low (${dailyRem.toInt()} remaining of ${dailyAllow.toInt()}).",
                        occurredAt = now.toString()
                    )
                }
            }

            if (latestIsRecentError && lastError != null) {
                val alreadyHasTwelveQuota = id == "twelve_data" && alerts.any { it.provider == id }
                if (!alreadyHasTwelveQuota) {
                    val sev = if (lastError.errorCategory in setOf("rate_limited", "quota_exceeded", "auth_error")) "error" else "warning"
                    alerts += ApiHealthAlert(
                        provider = id,
                        severity = sev,
                        category = lastError.errorCategory,
                        httpCode = lastError.httpCode,
                        message = "$label (${lastError.errorCategory}${lastError.httpCode?.let { " HTTP $it" } ?: ""}): ${lastError.error ?: "Request failed"}",
                        occurredAt = lastError.requestedAt
                    )
                }
            }

            providerCards += ApiProviderHealth(
                provider = id,
                displayName = label,
                status = status,
                calls24h = calls24h,
                errors24h = errors24h,
                rateLimitHits24h = rateLimitHits24h,
                lastSuccessAt = lastSuccess?.requestedAt,
                lastErrorAt = lastError?.requestedAt,
                lastErrorCategory = lastError?.errorCategory,
                lastHttpCode = lastError?.httpCode,
                lastErrorMessage = lastError?.error
            )
        }

        val overall = when {
            providerCards.any { it.status in setOf("error", "rate_limited") } -> "error"
            providerCards.any { it.status == "degraded" } || alerts.isNotEmpty() -> "degraded"
            else -> "healthy"
        }

        val recentIncidents = logs24h.filter { it.status != "success" }.take(20)
        return ApiHealthSummary(
            overallStatus = overall,
            activeAlerts = alerts,
            providers = providerCards,
            twelveDataUsage = twelveUsage,
            recentIncidents = recentIncidents
        )
    }

    suspend fun getSignalEnriched(signalId: Long): EnrichedSignal? {
        val row = db.coreDao().getSignalJoinedById(signalId) ?: return null
        val settings = getSettings()
        val initWait = settings["verification_initial_wait_minutes"]?.toIntOrNull() ?: 15
        val repeatMins = settings["verification_repeat_minutes"]?.toIntOrNull() ?: 15
        val fastPhaseMins = settings["verification_fast_phase_minutes"]?.toIntOrNull() ?: 60
        val extendedIntervalMins = settings["verification_extended_interval_minutes"]?.toIntOrNull() ?: 60
        val rules = DefaultRulesEngine.rulesFromJson(settings["default_sl_rules"])
        return enrichSignal(row, initWait, repeatMins, fastPhaseMins, extendedIntervalMins, rules)
    }

    suspend fun getFilters(category: String = ""): Pair<List<String>, List<String>> {
        val core = db.coreDao()
        val cats = core.getEnabledCategoryNames()
        val chans = if (category.isNotBlank()) {
            core.getDistinctChannelsByCategoryAliases(categoryAliasesFor(category))
        } else {
            core.getDistinctChannels()
        }
        return cats to chans
    }

    suspend fun updateSignal(signalId: Long, edit: SignalEditPayload): Boolean {
        val existing = db.coreDao().getSignalById(signalId) ?: return false
        val now = TimeUtils.utcNowIso()
        db.withTransaction {
            val core = db.coreDao()
            val verDao = db.verificationDao()
            core.updateSignalParsedFields(
                id = signalId,
                symbol = if (edit.hasSymbol) edit.symbol else existing.symbol,
                direction = if (edit.hasDirection) edit.direction else existing.direction,
                entryPrice = if (edit.hasEntryPrice) edit.entryPrice else existing.entryPrice,
                additionalEntries = edit.additionalEntries?.let { JsonUtils.doubleListToJson(it) } ?: existing.additionalEntries,
                stopLoss = if (edit.hasStopLoss) edit.stopLoss else existing.stopLoss,
                targets = edit.targets?.let { JsonUtils.doubleListToJson(it) } ?: existing.targets,
                optionType = if (edit.hasOptionType) edit.optionType else existing.optionType,
                strikePrice = if (edit.hasStrikePrice) edit.strikePrice else existing.strikePrice,
                expiryDate = if (edit.hasExpiryDate) edit.expiryDate else existing.expiryDate,
                entryMode = edit.entryMode ?: existing.entryMode,
                parsingStatus = "manually_corrected",
                parsingConfidence = existing.parsingConfidence,
                manualReviewStatus = edit.manualReviewStatus ?: existing.manualReviewStatus,
                parsingExplanation = existing.parsingExplanation,
                updatedAt = now
            )
            if (edit.notificationTime != null) {
                core.updateNotificationTimeForSignal(signalId, edit.notificationTime)
            }
            if (edit.verificationTimeframe != null) {
                verDao.updateScheduleTimeframe(signalId, edit.verificationTimeframe, now)
            }
            if (edit.reschedule) {
                val joined = core.getSignalJoinedById(signalId)
                val start = TimeUtils.parseUtcInstant(edit.notificationTime ?: joined?.notificationTime) ?: Instant.now()
                val sched = verDao.getScheduleForSignal(signalId)
                if (sched != null) {
                    val first = start.plus(Duration.ofMinutes(sched.initialWaitMinutes.toLong())).toString()
                    verDao.rescheduleSignal(signalId, start.toString(), first, first, now)
                }
            }
            core.insertSystemLog(SystemLogEntity(createdAt = now, severity = "INFO", source = "signals", message = "Manual correction for signal $signalId"))
        }
        eventBus.emit(AppEvent("signal.changed", signalId, "updated", null, now))
        return true
    }

    suspend fun deleteSignal(signalId: Long): Int {
        val deleted = db.coreDao().deleteSignalById(signalId)
        if (deleted > 0) {
            val now = TimeUtils.utcNowIso()
            db.coreDao().insertSystemLog(SystemLogEntity(createdAt = now, severity = "WARNING", source = "signals", message = "Deleted signal $signalId"))
            eventBus.emit(AppEvent("signal.changed", signalId, "deleted", null, now))
        }
        return deleted
    }

    suspend fun deleteSignals(ids: List<Long>): Int {
        val clean = ids.distinct().take(500)
        if (clean.isEmpty()) return 0
        val deleted = db.coreDao().deleteSignalsByIds(clean)
        val now = TimeUtils.utcNowIso()
        db.coreDao().insertSystemLog(SystemLogEntity(createdAt = now, severity = "WARNING", source = "signals", message = "Deleted $deleted selected signals"))
        eventBus.emit(AppEvent("signal.changed", null, "deleted_batch", "count=$deleted", now))
        return deleted
    }

    suspend fun reparseSignal(signalId: Long, runType: String = "manual_reparse"): ParsedSignal? {
        val joined = db.coreDao().getSignalJoinedById(signalId) ?: return null
        val rules = getDefaultSlRules()
        val rawCat = joined.category.orEmpty()
        var items = SignalParser.parseSignals(joined.message.orEmpty(), rawCat, slRules = rules)
        val sigDt = TimeUtils.notificationInstant(joined.notificationTime, joined.receivedAt)
        val isDup = joined.isDuplicate == 1
        val now = TimeUtils.utcNowIso()

        val primary = db.withTransaction {
            val core = db.coreDao()
            val verDao = db.verificationDao()

            if (!isDup && rawCat.trim().lowercase(Locale.US) in setOf("forex", "fx")) {
                val cutoff = sigDt.minus(Duration.ofMinutes(30)).toString()
                val recentFx = core.findRecentForexSymbolForReparse(
                    channel = joined.channel.orEmpty(),
                    cutoffIso = cutoff,
                    upperIso = sigDt.toString(),
                    excludeRawId = joined.rawNotificationId
                )
                if (!recentFx.isNullOrBlank()) {
                    items = items.map { it ->
                        if (it.symbol.isNullOrBlank() && !it.direction.isNullOrBlank() &&
                            (it.entryPrice != null || it.entryMode == "market_now")
                        ) {
                            var upd = it.copy(
                                symbol = recentFx,
                                status = "parsed",
                                review = "not_required",
                                confidence = 0.85,
                                explanation = it.explanation.trimEnd('.') + " | Inherited symbol $recentFx from recent channel message."
                            )
                            upd = DefaultRulesEngine.applyDefaultSl(upd, rawCat, rules)
                            upd = DefaultRulesEngine.applyDefaultTargets(upd, rawCat, rules)
                            upd
                        } else it
                    }
                }
            }

            val firstItem = items.first()
            core.updateSignalParsedFields(
                id = signalId,
                symbol = firstItem.symbol,
                direction = firstItem.direction,
                entryPrice = firstItem.entryPrice,
                additionalEntries = JsonUtils.doubleListToJson(firstItem.additionalEntries),
                stopLoss = firstItem.stopLoss,
                targets = JsonUtils.doubleListToJson(firstItem.targets),
                optionType = firstItem.optionType,
                strikePrice = firstItem.strikePrice,
                expiryDate = firstItem.expiryDate,
                entryMode = firstItem.entryMode.ifBlank { "limit" },
                parsingStatus = firstItem.status,
                parsingConfidence = firstItem.confidence,
                manualReviewStatus = firstItem.review,
                parsingExplanation = firstItem.explanation,
                updatedAt = now
            )
            core.insertParsingRun(
                ParsingRunEntity(
                    signalId = signalId,
                    runType = runType,
                    parsedAt = now,
                    status = firstItem.status,
                    confidence = firstItem.confidence,
                    explanation = firstItem.explanation,
                    snapshotJson = JsonUtils.parsedSignalToJsonObject(firstItem).toString()
                )
            )
            ensureScheduleForSignal(core, verDao, signalId, sigDt, firstItem, isDup, now)

            val siblings = core.getSiblingSignals(joined.rawNotificationId, signalId)
            val extraItems = items.drop(1)
            for (idx in extraItems.indices) {
                val extra = extraItems[idx]
                val extraId: Long
                if (idx < siblings.size) {
                    extraId = siblings[idx].id
                    core.updateSignalParsedFields(
                        id = extraId,
                        symbol = extra.symbol,
                        direction = extra.direction,
                        entryPrice = extra.entryPrice,
                        additionalEntries = JsonUtils.doubleListToJson(extra.additionalEntries),
                        stopLoss = extra.stopLoss,
                        targets = JsonUtils.doubleListToJson(extra.targets),
                        optionType = extra.optionType,
                        strikePrice = extra.strikePrice,
                        expiryDate = extra.expiryDate,
                        entryMode = extra.entryMode.ifBlank { "limit" },
                        parsingStatus = extra.status,
                        parsingConfidence = extra.confidence,
                        manualReviewStatus = extra.review,
                        parsingExplanation = extra.explanation,
                        updatedAt = now
                    )
                } else {
                    extraId = core.insertSignal(
                        SignalEntity(
                            rawNotificationId = joined.rawNotificationId,
                            symbol = extra.symbol,
                            direction = extra.direction,
                            entryPrice = extra.entryPrice,
                            additionalEntries = JsonUtils.doubleListToJson(extra.additionalEntries),
                            stopLoss = extra.stopLoss,
                            targets = JsonUtils.doubleListToJson(extra.targets),
                            optionType = extra.optionType,
                            strikePrice = extra.strikePrice,
                            expiryDate = extra.expiryDate,
                            entryMode = extra.entryMode.ifBlank { "limit" },
                            parsingStatus = extra.status,
                            parsingConfidence = extra.confidence,
                            manualReviewStatus = extra.review,
                            parsingExplanation = extra.explanation,
                            updatedAt = now
                        )
                    )
                }
                core.insertParsingRun(
                    ParsingRunEntity(
                        signalId = extraId,
                        runType = runType,
                        parsedAt = now,
                        status = extra.status,
                        confidence = extra.confidence,
                        explanation = extra.explanation,
                        snapshotJson = JsonUtils.parsedSignalToJsonObject(extra).toString()
                    )
                )
                ensureScheduleForSignal(core, verDao, extraId, sigDt, extra, isDup, now)
            }
            if (siblings.size > extraItems.size) {
                val staleIds = siblings.drop(extraItems.size).map { it.id }
                core.deleteSignalsByIds(staleIds)
            }
            core.insertSystemLog(SystemLogEntity(createdAt = now, severity = "INFO", source = "parser", message = "Re-parsed signal $signalId (${items.size} trade(s))"))
            firstItem
        }

        eventBus.emit(AppEvent("signal.changed", signalId, "reparsed", null, now))
        return primary
    }

    private suspend fun ensureScheduleForSignal(
        core: CoreDao,
        verDao: VerificationDao,
        signalId: Long,
        sigDt: Instant,
        item: ParsedSignal,
        isDuplicate: Boolean,
        nowIso: String
    ) {
        val canVerify = item.status == "parsed" &&
            !isDuplicate &&
            !item.symbol.isNullOrBlank() &&
            item.direction in setOf("BUY", "SELL") &&
            (item.entryPrice != null || item.entryMode == "market_now")
        if (!canVerify || verDao.getScheduleForSignal(signalId) != null) return

        val settings = core.getAllSettings().associate { it.key to it.value }
        val initial = settings["verification_initial_wait_minutes"]?.toIntOrNull() ?: 15
        val interval = settings["verification_repeat_minutes"]?.toIntOrNull() ?: 15
        val expiry = settings["verification_expiry_hours"]?.toLongOrNull() ?: 168L
        val first = sigDt.plus(Duration.ofMinutes(initial.toLong()))
        val expires = sigDt.plus(Duration.ofHours(expiry))
        val now = Instant.now()
        // Zero-manual self-monitoring: wait 15m idle for fresh signals, immediate catch-up for older signals
        val nextAt = if (first.isAfter(now)) first else now

        verDao.insertSchedule(
            VerificationScheduleEntity(
                signalId = signalId,
                signalStartAt = sigDt.toString(),
                initialWaitMinutes = initial,
                intervalMinutes = interval,
                timeframe = settings["verification_timeframe"] ?: "1m",
                firstScheduledAt = first.toString(),
                nextScheduledAt = nextAt.toString(),
                expiresAt = expires.toString(),
                status = "scheduled",
                lastResult = null,
                createdAt = nowIso,
                updatedAt = nowIso
            )
        )
    }

    suspend fun getSignalChart(signalId: Long): SignalChartData? {
        val record = db.coreDao().getSignalJoinedById(signalId) ?: return null
        val (provider, resolvedSym) = verificationService.resolveSignalProvider(record)
        val cacheSym = if (":" in resolvedSym) resolvedSym.substringAfter(":") else resolvedSym
        val instRow = if (provider == "angel_one") db.marketDao().getInstrumentByToken(cacheSym) else null
        val sigDt = TimeUtils.notificationInstant(record.notificationTime, record.receivedAt)
        val chartStart = TimeUtils.truncateToMinute(sigDt).toString()
        val candles = db.marketDao().getCandlesSince(provider, cacheSym, chartStart, 1500).map {
            Candle(it.provider, it.symbol, it.exchange, it.interval, it.candleTimestamp, it.open, it.high, it.low, it.close, it.volume ?: 0.0)
        }
        return SignalChartData(
            signalId = signalId,
            symbol = record.symbol,
            resolvedSymbol = cacheSym,
            tradingSymbol = instRow?.tradingSymbol,
            provider = provider,
            direction = record.direction,
            notificationTime = sigDt.toString(),
            entryPrice = record.entryPrice,
            additionalEntries = JsonUtils.jsonToDoubleList(record.additionalEntries),
            entryMode = record.entryMode.ifBlank { "limit" },
            stopLoss = record.stopLoss,
            targets = JsonUtils.jsonToDoubleList(record.targets),
            verificationStatus = record.verificationStatus,
            detail = record.tradeDetail,
            entryHitAt = record.entryHitAt,
            targetHitAt = record.targetHitAt,
            stopLossHitAt = record.stopLossHitAt,
            events = JsonUtils.jsonToEvents(record.eventsJson),
            candles = candles
        )
    }

    suspend fun getNotifications(
        limit: Int = 100,
        afterId: Long? = null,
        category: String = "",
        channel: String = "",
        dateFrom: String = "",
        dateTo: String = "",
        parsingStatus: String = "",
        notificationFilter: String = "all"
    ): List<NotificationJoinedRow> {
        val clampedLimit = limit.coerceIn(1, 500)
        val where = mutableListOf<String>()
        val args = mutableListOf<Any>()

        if (afterId != null && afterId > 0) {
            where += "r.id > ?"
            args += afterId
        }
        if (category.isNotBlank()) {
            val aliases = categoryAliasesFor(category)
            where += "LOWER(COALESCE(r.category,'')) IN (${aliases.joinToString(",") { "?" }})"
            args.addAll(aliases)
        }
        if (channel.isNotBlank()) {
            where += "r.channel = ?"
            args += channel
        }
        if (dateFrom.isNotBlank()) {
            where += "substr(COALESCE(r.notification_time, r.received_at), 1, 10) >= ?"
            args += dateFrom
        }
        if (dateTo.isNotBlank()) {
            where += "substr(COALESCE(r.notification_time, r.received_at), 1, 10) <= ?"
            args += dateTo
        }
        when (val ps = parsingStatus.trim().lowercase(Locale.US)) {
            "needs_review" -> where += "(s.parsing_status = 'needs_review' OR s.manual_review_status = 'required')"
            "parsed", "manually_corrected" -> {
                where += "s.parsing_status = ?"
                args += ps
            }
            "unparsed" -> where += "s.id IS NULL"
        }
        when (notificationFilter.lowercase(Locale.US)) {
            "primary" -> where += "r.is_duplicate = 0 AND (r.duplicate_reason IS NULL OR r.duplicate_reason NOT LIKE 'signal_update:%')"
            "duplicates" -> where += "r.is_duplicate = 1"
            "updates" -> where += "r.is_duplicate = 0 AND r.duplicate_reason LIKE 'signal_update:%'"
        }

        val sql = buildString {
            append(
                "SELECT r.*, s.id AS signal_id, s.symbol, s.direction, s.entry_price, s.additional_entries, " +
                    "s.stop_loss, s.targets, s.option_type, s.strike_price, s.expiry_date, s.entry_mode, " +
                    "s.parsing_status, s.manual_review_status, s.parsing_confidence, s.parsing_explanation, s.verification_status " +
                    "FROM raw_notifications r LEFT JOIN signals s ON s.raw_notification_id = r.id"
            )
            if (where.isNotEmpty()) append(" WHERE ").append(where.joinToString(" AND "))
            append(" ORDER BY r.id DESC LIMIT ?")
        }
        args += clampedLimit
        return db.coreDao().queryNotificationsJoined(SimpleSQLiteQuery(sql, args.toTypedArray()))
    }

    suspend fun getInboxChannels(category: String = ""): List<InboxChannelItem> {
        val core = db.coreDao()
        val todayDate = LocalDate.now().toString()
        val where = mutableListOf<String>()
        val args = mutableListOf<Any>(todayDate)

        if (category.isNotBlank()) {
            val aliases = categoryAliasesFor(category)
            where += "LOWER(COALESCE(r.category,'')) IN (${aliases.joinToString(",") { "?" }})"
            args.addAll(aliases)
        }

        val sql = buildString {
            append(
                "SELECT COALESCE(NULLIF(TRIM(r.channel),''), 'Unknown') AS channel, " +
                    "COALESCE(NULLIF(TRIM(r.category),''), 'Uncategorized') AS category, " +
                    "COUNT(DISTINCT r.id) AS total_messages, " +
                    "COUNT(DISTINCT CASE WHEN substr(COALESCE(r.notification_time, r.received_at), 1, 10) = ? THEN r.id END) AS today_messages, " +
                    "COUNT(DISTINCT CASE WHEN s.parsing_status IN ('parsed','manually_corrected') THEN r.id END) AS parsed_count, " +
                    "COUNT(DISTINCT CASE WHEN s.parsing_status = 'needs_review' OR s.manual_review_status = 'required' THEN r.id END) AS needs_review_count, " +
                    "(SELECT r2.message FROM raw_notifications r2 WHERE r2.id = MAX(r.id)) AS last_message, " +
                    "MAX(COALESCE(r.notification_time, r.received_at)) AS last_time, " +
                    "MAX(r.id) AS last_raw_id " +
                    "FROM raw_notifications r LEFT JOIN signals s ON s.raw_notification_id = r.id"
            )
            if (where.isNotEmpty()) append(" WHERE ").append(where.joinToString(" AND "))
            append(" GROUP BY COALESCE(NULLIF(TRIM(r.channel),''), 'Unknown'), COALESCE(NULLIF(TRIM(r.category),''), 'Uncategorized') ")
            append(" ORDER BY last_time DESC, last_raw_id DESC")
        }

        val rawRows = core.queryInboxChannelSummaries(SimpleSQLiteQuery(sql, args.toTypedArray()))
        // Merge any legacy category alias rows for the same (channel, canonicalCategory) pair
        val merged = linkedMapOf<Pair<String, String>, InboxChannelItem>()
        for (row in rawRows) {
            val ch = row.channel?.trim()?.ifEmpty { "Unknown" } ?: "Unknown"
            val canonCat = canonicalCategory(row.category.orEmpty())
            val key = ch to canonCat
            val prev = merged[key]
            if (prev == null) {
                merged[key] = InboxChannelItem(
                    channel = ch,
                    category = canonCat,
                    totalMessages = row.totalMessages,
                    todayMessages = row.todayMessages,
                    parsedCount = row.parsedCount,
                    needsReviewCount = row.needsReviewCount,
                    lastMessage = row.lastMessage.orEmpty(),
                    lastTime = row.lastTime.orEmpty(),
                    lastRawId = row.lastRawId
                )
            } else {
                val useNew = row.lastRawId > prev.lastRawId
                merged[key] = prev.copy(
                    totalMessages = prev.totalMessages + row.totalMessages,
                    todayMessages = prev.todayMessages + row.todayMessages,
                    parsedCount = prev.parsedCount + row.parsedCount,
                    needsReviewCount = prev.needsReviewCount + row.needsReviewCount,
                    lastMessage = if (useNew) row.lastMessage.orEmpty() else prev.lastMessage,
                    lastTime = if (useNew) row.lastTime.orEmpty() else prev.lastTime,
                    lastRawId = maxOf(prev.lastRawId, row.lastRawId)
                )
            }
        }
        return merged.values.sortedWith(compareByDescending<InboxChannelItem> { it.lastTime }.thenByDescending { it.lastRawId })
    }

    suspend fun getInboxMessages(
        channel: String,
        category: String = "",
        search: String = "",
        limit: Int = 200
    ): InboxChannelHistory {
        val core = db.coreDao()
        val clampedLimit = limit.coerceIn(20, 1000)
        val cleanChannel = channel.trim().ifEmpty { "Unknown" }
        val baseWhere = mutableListOf<String>("COALESCE(NULLIF(TRIM(r.channel),''), 'Unknown') = ?")
        val baseArgs = mutableListOf<Any>(cleanChannel)

        val canonCat = if (category.isNotBlank()) canonicalCategory(category) else ""
        if (canonCat.isNotBlank()) {
            val aliases = categoryAliasesFor(canonCat)
            baseWhere += "LOWER(COALESCE(r.category,'')) IN (${aliases.joinToString(",") { "?" }})"
            baseArgs.addAll(aliases)
        }

        val whereClause = baseWhere.joinToString(" AND ")
        val totalCount = core.queryInt(
            SimpleSQLiteQuery("SELECT COUNT(*) FROM raw_notifications r WHERE $whereClause", baseArgs.toTypedArray())
        )
        val parsedCount = core.queryInt(
            SimpleSQLiteQuery(
                "SELECT COUNT(DISTINCT r.id) FROM raw_notifications r JOIN signals s ON s.raw_notification_id = r.id " +
                    "WHERE $whereClause AND s.parsing_status IN ('parsed','manually_corrected')",
                baseArgs.toTypedArray()
            )
        )
        val needsReviewCount = core.queryInt(
            SimpleSQLiteQuery(
                "SELECT COUNT(DISTINCT r.id) FROM raw_notifications r JOIN signals s ON s.raw_notification_id = r.id " +
                    "WHERE $whereClause AND (s.parsing_status = 'needs_review' OR s.manual_review_status = 'required')",
                baseArgs.toTypedArray()
            )
        )

        val msgWhere = baseWhere.toMutableList()
        val msgArgs = baseArgs.toMutableList()
        val q = search.trim()
        if (q.isNotEmpty()) {
            msgWhere += "(LOWER(COALESCE(r.message,'')) LIKE ? OR LOWER(COALESCE(s.symbol,'')) LIKE ?)"
            val pat = "%${q.lowercase(Locale.US)}%"
            msgArgs += pat
            msgArgs += pat
        }

        val msgSql = buildString {
            append(
                "SELECT r.*, s.id AS signal_id, s.symbol, s.direction, s.entry_price, s.additional_entries, " +
                    "s.stop_loss, s.targets, s.option_type, s.strike_price, s.expiry_date, s.entry_mode, " +
                    "s.parsing_status, s.manual_review_status, s.parsing_confidence, s.parsing_explanation, s.verification_status " +
                    "FROM raw_notifications r LEFT JOIN signals s ON s.raw_notification_id = r.id " +
                    "WHERE ${msgWhere.joinToString(" AND ")} " +
                    "ORDER BY COALESCE(r.notification_time, r.received_at) DESC, r.id DESC LIMIT ?"
            )
        }
        msgArgs += (clampedLimit + 1)
        val fetchedDesc = core.queryNotificationsJoined(SimpleSQLiteQuery(msgSql, msgArgs.toTypedArray()))
        val hasMore = fetchedDesc.size > clampedLimit
        val trimmedChrono = fetchedDesc.take(clampedLimit).reversed()

        return InboxChannelHistory(
            channel = cleanChannel,
            category = canonCat.ifBlank { trimmedChrono.lastOrNull()?.category?.let { canonicalCategory(it) }.orEmpty() },
            totalCount = totalCount,
            parsedCount = parsedCount,
            needsReviewCount = needsReviewCount,
            hasMore = hasMore,
            messages = trimmedChrono
        )
    }

    suspend fun saveIntegration(provider: String, configJson: String, secrets: Map<String, String>) {
        if (provider !in SUPPORTED_PROVIDERS) throw IllegalArgumentException("Unsupported provider")
        val now = TimeUtils.utcNowIso()
        if (secrets.isNotEmpty()) {
            secretStore.putProviderSecrets(provider, secrets)
        }
        db.coreDao().upsertIntegration(provider, configJson, "configured", now)
        db.coreDao().insertSystemLog(SystemLogEntity(createdAt = now, severity = "INFO", source = "integrations", message = "Updated $provider configuration"))
        eventBus.emit(AppEvent("server.status", null, "integration_updated", provider, now))
    }

    suspend fun deleteIntegration(provider: String) {
        val now = TimeUtils.utcNowIso()
        secretStore.deleteProviderSecrets(provider)
        db.coreDao().deleteIntegration(provider)
        db.coreDao().insertSystemLog(SystemLogEntity(createdAt = now, severity = "INFO", source = "integrations", message = "Deleted $provider credentials"))
        eventBus.emit(AppEvent("server.status", null, "integration_deleted", provider, now))
    }

    suspend fun testBinance(): Pair<Int, String> {
        val end = Instant.now()
        val start = TimeUtils.truncateToMinute(end)
        val integration = db.coreDao().getIntegration("binance")
        val cfg = integration?.let { jsonToMap(it.configJson) } ?: emptyMap()
        return try {
            val candles = providers.fetchBinanceCandles("BTCUSDT", "1m", start, end, cfg)
            verificationService.recordApiCall("binance", "/fapi/v1/klines", "success", 200, "ok", 0.0, candles.size)
            candles.size to "Public USD-M Futures endpoint responded."
        } catch (e: ProviderError) {
            verificationService.recordApiCall("binance", "/fapi/v1/klines", "error", e.httpCode, e.category, 0.0, 0, e.message)
            throw e
        } catch (e: Exception) {
            val cat = ProviderErrorClassifier.classify(null, e.message)
            verificationService.recordApiCall("binance", "/fapi/v1/klines", "error", null, cat, 0.0, 0, e.message)
            throw e
        }
    }

    suspend fun testAngelOne(): String {
        val secrets = secretStore.getProviderSecrets("angel_one")
        return try {
            val (_, authAt) = providers.authenticateAngelOne(secrets, forceRefresh = true)
            val now = TimeUtils.utcNowIso()
            db.coreDao().updateIntegrationStatus("angel_one", "connected", now)
            db.coreDao().insertSystemLog(SystemLogEntity(createdAt = now, severity = "INFO", source = "angel_one", message = "Authentication succeeded."))
            verificationService.recordApiCall("angel_one", "/loginByPassword", "success", 200, "ok")
            authAt
        } catch (e: ProviderError) {
            verificationService.recordApiCall("angel_one", "/loginByPassword", "error", e.httpCode, e.category, 0.0, 0, e.message)
            throw e
        } catch (e: Exception) {
            val cat = ProviderErrorClassifier.classify(null, e.message)
            verificationService.recordApiCall("angel_one", "/loginByPassword", "error", null, cat, 0.0, 0, e.message)
            throw e
        }
    }

    suspend fun refreshAngelInstrumentMaster(): Int {
        val arr = try {
            val res = providers.downloadAngelScripMaster()
            verificationService.recordApiCall("angel_one", "/OpenAPIScripMaster.json", "success", 200, "ok", 0.0, res.length())
            res
        } catch (e: ProviderError) {
            verificationService.recordApiCall("angel_one", "/OpenAPIScripMaster.json", "error", e.httpCode, e.category, 0.0, 0, e.message)
            throw e
        } catch (e: Exception) {
            val cat = ProviderErrorClassifier.classify(null, e.message)
            verificationService.recordApiCall("angel_one", "/OpenAPIScripMaster.json", "error", null, cat, 0.0, 0, e.message)
            throw e
        }
        val now = TimeUtils.utcNowIso()
        val market = db.marketDao()
        var count = 0
        db.withTransaction {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val token = obj.optString("token", "").trim()
                if (token.isEmpty()) continue
                market.upsertInstrument(
                    symbol = obj.optString("symbol", null),
                    tradingSymbol = obj.optString("symbol", ""),
                    token = token,
                    exchange = obj.optString("exch_seg", ""),
                    instType = obj.optString("instrumenttype", null),
                    expiry = obj.optString("expiry", null),
                    strike = obj.optString("strike", "0").toDoubleOrNull() ?: 0.0,
                    lotSize = obj.optString("lotsize", "0").toDoubleOrNull() ?: 0.0,
                    tickSize = obj.optString("tick_size", "0").toDoubleOrNull() ?: 0.0,
                    rawJson = obj.toString(),
                    updatedAt = now
                )
                count++
            }
            db.coreDao().upsertSetting(ApplicationSettingEntity("angel_master_auto_refreshed_at", now, now))
            db.coreDao().insertSystemLog(SystemLogEntity(createdAt = now, severity = "INFO", source = "angel_one", message = "Instrument master refreshed: $count records."))
        }
        eventBus.emit(AppEvent("market.cache.changed", null, "angel_master_refreshed", "count=$count", now))
        return count
    }

    suspend fun fetchManualBinanceCandles(symbol: String, timeframe: String, startIso: String, endIso: String): Pair<Int, Int> {
        val start = TimeUtils.parseUtcInstant(startIso) ?: throw IllegalArgumentException("Invalid start time")
        val end = TimeUtils.parseUtcInstant(endIso) ?: throw IllegalArgumentException("Invalid end time")
        if (!end.isAfter(start)) throw IllegalArgumentException("End time must be later than start time.")
        val integration = db.coreDao().getIntegration("binance")
        val cfg = integration?.let { jsonToMap(it.configJson) } ?: emptyMap()
        return try {
            val candles = providers.fetchBinanceCandles(symbol, timeframe, start, end, cfg)
            val stored = verificationService.storeCandles(candles)
            verificationService.recordApiCall("binance", "/fapi/v1/klines", "success", 200, "ok", 0.0, candles.size)
            candles.size to stored
        } catch (e: ProviderError) {
            verificationService.recordApiCall("binance", "/fapi/v1/klines", "error", e.httpCode, e.category, 0.0, 0, e.message)
            throw e
        } catch (e: Exception) {
            val cat = ProviderErrorClassifier.classify(null, e.message)
            verificationService.recordApiCall("binance", "/fapi/v1/klines", "error", null, cat, 0.0, 0, e.message)
            throw e
        }
    }

    suspend fun fetchManualTwelveDataCandles(symbol: String, interval: String, startIso: String?, endIso: String?): Triple<Int, Int, Double> {
        val integration = db.coreDao().getIntegration("twelve_data")
            ?: throw ProviderError("Configure Twelve Data first.", category = "auth_error")
        val apiKey = secretStore.getProviderSecrets("twelve_data")["api_key"].orEmpty()
        val cfg = jsonToMap(integration.configJson)
        val start = startIso?.takeIf { it.isNotBlank() }?.let { TimeUtils.parseUtcInstant(it) }
        val end = endIso?.takeIf { it.isNotBlank() }?.let { TimeUtils.parseUtcInstant(it) }
        return try {
            val (candles, credits) = providers.fetchTwelveDataCandles(symbol, interval, start, end, cfg, apiKey)
            val stored = verificationService.storeCandles(candles)
            verificationService.recordApiCall("twelve_data", "/time_series", "success", 200, "ok", credits, candles.size)
            Triple(candles.size, stored, credits)
        } catch (e: ProviderError) {
            verificationService.recordApiCall("twelve_data", "/time_series", "error", e.httpCode, e.category, 0.0, 0, e.message)
            throw e
        } catch (e: Exception) {
            val cat = ProviderErrorClassifier.classify(null, e.message)
            verificationService.recordApiCall("twelve_data", "/time_series", "error", null, cat, 0.0, 0, e.message)
            throw e
        }
    }

    suspend fun fetchManualAngelCandles(exchange: String, token: String, rawInterval: String, startIso: String, endIso: String): Pair<Int, Int> {
        val secrets = secretStore.getProviderSecrets("angel_one")
        val apiKey = secrets["api_key"].orEmpty()
        if (apiKey.isBlank()) throw ProviderError("Angel One is not configured.", category = "auth_error")
        val start = TimeUtils.parseUtcInstant(startIso) ?: throw IllegalArgumentException("Invalid start time")
        val end = TimeUtils.parseUtcInstant(endIso) ?: throw IllegalArgumentException("Invalid end time")
        if (!end.isAfter(start)) throw IllegalArgumentException("End time must be later than start time.")
        val exch = exchange.trim().uppercase(Locale.US)
        val tok = token.trim()
        if (exch.isEmpty() || tok.isEmpty()) throw IllegalArgumentException("Select a valid exchange and contract token first.")
        val interval = ANGEL_INTERVAL_MAP[rawInterval.trim()] ?: rawInterval.trim()
        return try {
            val jwt = providers.authenticateAngelOne(secrets).first
            val candles = providers.fetchAngelCandles(jwt, apiKey, exch, tok, interval, angelDateFmt.format(start), angelDateFmt.format(end))
            val stored = verificationService.storeCandles(candles)
            verificationService.recordApiCall("angel_one", "/getCandleData", "success", 200, "ok", 0.0, candles.size)
            candles.size to stored
        } catch (e: ProviderError) {
            verificationService.recordApiCall("angel_one", "/getCandleData", "error", e.httpCode, e.category, 0.0, 0, e.message)
            throw e
        } catch (e: Exception) {
            val cat = ProviderErrorClassifier.classify(null, e.message)
            verificationService.recordApiCall("angel_one", "/getCandleData", "error", null, cat, 0.0, 0, e.message)
            throw e
        }
    }

    suspend fun getTwelveDataUsage(): Map<String, Double> {
        val now = Instant.now()
        val day = now.toString().take(10)
        val minute = now.toString().take(16)
        val settings = getSettings()
        val daily = settings["twelve_daily_credits"]?.toDoubleOrNull() ?: 800.0
        val perMin = settings["twelve_minute_credits"]?.toDoubleOrNull() ?: 8.0
        val used = db.marketDao().sumCreditsForDay("twelve_data", day)
        val minUsed = db.marketDao().sumCreditsForMinute("twelve_data", minute)
        return mapOf(
            "daily_allowance" to daily,
            "daily_used" to used,
            "daily_remaining" to maxOf(0.0, daily - used),
            "minute_allowance" to perMin,
            "minute_used" to minUsed,
            "minute_remaining" to maxOf(0.0, perMin - minUsed)
        )
    }

    suspend fun searchAngelInstruments(q: String, exchange: String): List<InstrumentEntity> {
        val where = mutableListOf<String>()
        val args = mutableListOf<Any>()
        if (q.isNotBlank()) {
            where += "(trading_symbol LIKE ? OR symbol LIKE ? OR instrument_token = ?)"
            args += "%${q.trim()}%"
            args += "%${q.trim()}%"
            args += q.trim()
        }
        if (exchange.isNotBlank()) {
            where += "exchange_segment = ?"
            args += exchange.trim().uppercase(Locale.US)
        }
        val sql = "SELECT * FROM instruments" +
            (if (where.isNotEmpty()) " WHERE " + where.joinToString(" AND ") else "") +
            " ORDER BY trading_symbol LIMIT 100"
        return db.marketDao().searchInstrumentsRaw(SimpleSQLiteQuery(sql, args.toTypedArray()))
    }

    suspend fun saveAngelMapping(lookupKey: String, token: String, exchange: String, note: String = "") {
        val valid = db.marketDao().getInstrumentByTokenAndExchange(token, exchange)
            ?: throw IllegalArgumentException("Token is not present in the downloaded Angel One instrument master.")
        db.marketDao().upsertInstrumentMapping(lookupKey, valid.instrumentToken, valid.exchangeSegment, note, TimeUtils.utcNowIso())
    }

    suspend fun deleteMarketCache(provider: String, symbol: String): Int {
        val deleted = db.marketDao().deleteCacheForSymbol(provider, symbol)
        val now = TimeUtils.utcNowIso()
        db.coreDao().insertSystemLog(SystemLogEntity(createdAt = now, severity = "WARNING", source = "market_data", message = "Deleted $deleted cached candles for $provider:$symbol."))
        eventBus.emit(AppEvent("market.cache.changed", null, "deleted", "$provider:$symbol", now))
        return deleted
    }

    suspend fun getAngelCatalogJson(exchange: String, instType: String, underlying: String, expiry: String, q: String): JSONObject {
        val total = db.marketDao().countInstruments()
        val ex = exchange.ifBlank { "NFO" }.trim().uppercase(Locale.US)
        val it = instType.ifBlank { "FUTIDX" }.trim().uppercase(Locale.US)
        val und = underlying.trim().uppercase(Locale.US)
        val exp = expiry.trim().uppercase(Locale.US)
        val query = q.trim().uppercase(Locale.US)

        if (total == 0 && it == "AMXIDX") {
            val spot = BUILTIN_SPOT_INDICES.filter { it.optString("exchange_segment") == ex }.ifEmpty { BUILTIN_SPOT_INDICES }
            val uList = spot.map { it.optString("underlying") }
            val selU = if (und in uList) und else uList.firstOrNull().orEmpty()
            val contracts = spot.filter { selU.isEmpty() || it.optString("underlying") == selU }
            return JSONObject().apply {
                put("total_instruments", 0)
                put("selected_underlying", selU)
                put("selected_expiry", "")
                put("underlyings", JSONArray(uList))
                put("expiries", JSONArray())
                put("contracts", JSONArray(contracts))
            }
        }
        if (total == 0) {
            return JSONObject().apply {
                put("total_instruments", 0)
                put("underlyings", JSONArray())
                put("expiries", JSONArray())
                put("contracts", JSONArray())
            }
        }

        val fetched = db.marketDao().getInstrumentsForCatalog(ex, it)
        val items = mutableListOf<Pair<InstrumentEntity, String>>()
        val underlyingSet = linkedSetOf<String>()
        for (r in fetched) {
            val name = try {
                JSONObject(r.rawJson ?: "{}").optString("name", "").ifBlank { r.symbol.orEmpty() }.trim().uppercase(Locale.US)
            } catch (_: Exception) {
                r.symbol.orEmpty().trim().uppercase(Locale.US)
            }
            if (name.isEmpty()) continue
            underlyingSet += name
            items += r to name
        }
        val prioRank = CATALOG_PRIORITY.withIndex().associate { it.value to it.index }
        val underlyings = underlyingSet.sortedWith(compareBy({ prioRank[it] ?: 999 }, { it }))
        val activeUnderlying = if (und.isNotEmpty() && und in underlyingSet) und else underlyings.firstOrNull().orEmpty()
        val byUnderlying = items.filter { activeUnderlying.isEmpty() || it.second == activeUnderlying }
        val expirySet = byUnderlying.mapNotNull { it.first.expiry?.trim()?.uppercase(Locale.US)?.takeIf { s -> s.isNotEmpty() } }.toSet()
        val expiries = expirySet.sortedBy { parseCatalogExpiry(it) }
        val activeExpiry = if (exp.isNotEmpty() && exp in expirySet) exp else expiries.firstOrNull().orEmpty()

        val filtered = byUnderlying.filter { (r, _) ->
            (activeExpiry.isEmpty() || (r.expiry ?: "").trim().uppercase(Locale.US) == activeExpiry) &&
                (query.isEmpty() || query in r.tradingSymbol.uppercase(Locale.US) || query == r.instrumentToken)
        }.sortedWith(compareBy({ parseCatalogExpiry(it.first.expiry) }, { it.first.strike ?: 0.0 }, { it.first.tradingSymbol }))

        val contractsArr = JSONArray()
        for ((r, uName) in filtered.take(300)) {
            val rawStrike = r.strike ?: 0.0
            contractsArr.put(
                JSONObject().apply {
                    put("trading_symbol", r.tradingSymbol)
                    put("instrument_token", r.instrumentToken)
                    put("exchange_segment", r.exchangeSegment)
                    put("instrument_type", r.instrumentType.orEmpty())
                    put("expiry", r.expiry.orEmpty())
                    put("strike", if (rawStrike > 0.0) DefaultRulesEngine.roundValue(rawStrike / 100.0, 2) else 0.0)
                    put("lot_size", (r.lotSize ?: 0.0).toInt())
                    put("underlying", uName)
                }
            )
        }
        return JSONObject().apply {
            put("total_instruments", total)
            put("selected_underlying", activeUnderlying)
            put("selected_expiry", activeExpiry)
            put("underlyings", JSONArray(underlyings))
            put("expiries", JSONArray(expiries))
            put("contracts", contractsArr)
        }
    }

    suspend fun getChannelAnalytics(): List<ChannelAnalyticsRow> =
        db.coreDao().getChannelAnalytics(LocalDate.now().toString())

    suspend fun exportCsv(kind: String): Pair<String, String> {
        return when (kind) {
            "raw" -> {
                val rows = db.coreDao().getAllRawNotifications()
                val header = listOf("id", "received_at", "notification_time", "source_app", "package", "channel", "category", "title", "message", "notification_id", "delivery_id", "imported_at", "is_duplicate", "duplicate_of", "duplicate_reason", "duplicate_group_id")
                val lines = rows.map { r ->
                    listOf(r.id, r.receivedAt, r.notificationTime, r.sourceApp, r.packageName, r.channel, r.category, r.title, r.message, r.notificationId, r.deliveryId, r.importedAt, r.isDuplicate, r.duplicateOf, r.duplicateReason, r.duplicateGroupId)
                }
                "raw_notifications.csv" to buildCsv(header, lines)
            }
            "signals" -> {
                val rows = getSignals(notificationFilter = "all")
                val header = listOf("id", "raw_notification_id", "channel", "category", "notification_time", "symbol", "direction", "entry_price", "additional_entries", "stop_loss", "targets", "option_type", "strike_price", "expiry_date", "entry_mode", "parsing_status", "verification_status", "max_tp_hit", "closed_by_sl_after_tp", "pnl_amount", "tp1_pnl", "tp2_pnl", "tp3_pnl", "highest_tp_pnl", "sl_pnl", "pnl_currency", "monitoring_phase")
                val lines = rows.map { s ->
                    listOf(s.row.id, s.row.rawNotificationId, s.row.channel, s.row.category, s.row.notificationTime, s.row.symbol, s.row.direction, s.row.entryPrice, s.row.additionalEntries, s.row.stopLoss, s.row.targets, s.row.optionType, s.row.strikePrice, s.row.expiryDate, s.row.entryMode, s.row.parsingStatus, s.row.verificationStatus, s.maxTpHit, s.closedBySlAfterTp, s.pnlAmount, s.tp1PnlAmount, s.tp2PnlAmount, s.tp3PnlAmount, s.highestTpPnlAmount, s.slPnlAmount, s.pnlCurrency, s.monitoringPhase)
                }
                "signals.csv" to buildCsv(header, lines)
            }
            "channels" -> {
                val rows = getChannelAnalytics()
                val header = listOf("channel", "total", "today_total", "today_tp", "today_sl", "parsed", "trades_triggered", "targets", "stops", "pending", "pnl_inr", "pnl_usd", "today_pnl_inr", "today_pnl_usd")
                val lines = rows.map { r ->
                    listOf(r.channel, r.total, r.todayTotal, r.todayTp, r.todaySl, r.parsed, r.tradesTriggered, r.targets, r.stops, r.pending, r.pnlInr, r.pnlUsd, r.todayPnlInr, r.todayPnlUsd)
                }
                "channel_report.csv" to buildCsv(header, lines)
            }
            else -> throw IllegalArgumentException("Unknown export kind")
        }
    }

    suspend fun createLocalBackup(): Pair<String, String> = withContext(Dispatchers.IO) {
        val ctx = context ?: throw IllegalStateException("Context required for backup")
        // Checkpoint WAL so the copied file is self-contained
        db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").close()
        val src = ctx.getDatabasePath(AppDatabase.DB_NAME)
        val backupDir = File(ctx.filesDir, "backups").apply { mkdirs() }
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.now())
        val dest = File(backupDir, "signals-backup-$stamp.sqlite3")
        src.copyTo(dest, overwrite = true)
        val now = TimeUtils.utcNowIso()
        db.coreDao().insertSystemLog(SystemLogEntity(createdAt = now, severity = "INFO", source = "backup", message = "Created local SQLite backup: ${dest.name}"))
        dest.name to "Backup saved to app-private storage (${dest.name}). API keys & TOTP secrets remain encrypted in Android Keystore and are NOT included in SQLite backups."
    }

    private fun buildCsv(headers: List<String>, rows: List<List<Any?>>): String {
        val sb = StringBuilder()
        sb.append(headers.joinToString(",") { escapeCsv(it) }).append('\n')
        for (r in rows) {
            sb.append(r.joinToString(",") { escapeCsv(it?.toString().orEmpty()) }).append('\n')
        }
        return sb.toString()
    }

    private fun escapeCsv(value: String): String {
        return if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else value
    }

    private fun enrichSignal(
        row: SignalJoinedRow,
        initialWait: Int,
        repeatMins: Int,
        fastPhaseMins: Int = 60,
        extendedIntervalMins: Int = 60,
        rules: Map<String, DefaultSlCategoryRule> = emptyMap()
    ): EnrichedSignal {
        val nowUtc = Instant.now()
        val sigDt = TimeUtils.notificationInstant(row.notificationTime, row.receivedAt)
        val ageMins = maxOf(0.0, Duration.between(sigDt, nowUtc).seconds / 60.0)
        val sStatus = row.schedulerStatus
        val closedBySlAfterTp = (row.closedBySlAfterTp ?: 0) == 1
        val allTargetsHit = (row.allTargetsHit ?: 0) == 1
        val maxTpHit = row.maxTpHit ?: 0
        val isAutoScheduled = sStatus in setOf("scheduled", "processing") || !row.nextScheduledAt.isNullOrBlank()
        val isRecent = ageMins <= fastPhaseMins.toDouble()
        val isManualNeeded = row.parsingStatus == "needs_review" || row.manualReviewStatus == "required"
        val expl = row.parsingExplanation.orEmpty()
        val targets = JsonUtils.jsonToDoubleList(row.targets)
        val additionalEntries = JsonUtils.jsonToDoubleList(row.additionalEntries)
        val events = JsonUtils.jsonToEvents(row.eventsJson)
        val legs = JsonUtils.jsonToLegs(row.legsJson)

        val firstDt = row.firstScheduledAt?.let { TimeUtils.parseUtcInstant(it) }
            ?: sigDt.plus(Duration.ofMinutes(initialWait.toLong()))
        val monitoringPhase = when {
            sStatus == "completed" ||
                row.verificationStatus in setOf("stop_loss_hit", "ambiguous") ||
                allTargetsHit ||
                closedBySlAfterTp -> "completed"
            sStatus == "expired" -> "expired"
            isAutoScheduled && nowUtc.isBefore(firstDt) -> "idle_15m"
            isAutoScheduled && ageMins < fastPhaseMins.toDouble() -> "fast_15m"
            isAutoScheduled -> "hourly_1h"
            else -> "unscheduled"
        }

        val catKey = DefaultRulesEngine.normalizeSlCategory(row.category, row.symbol, row.optionType)
        val prof = DefaultRulesEngine.resolveInstrumentProfile(rules, row.category, row.symbol, row.optionType)
        val defaultCurrency = if (catKey == "FOREX" || catKey == "CRYPTO") "USD" else "INR"
        val resolvedCurrency = row.pnlCurrency?.takeIf { it.isNotBlank() } ?: defaultCurrency
        val storedLevels = JsonUtils.jsonToTpLevels(row.tpLevelsJson)
        val tpLevels: List<TargetLevelPnl>
        val tp1Pnl: Double
        val tp2Pnl: Double
        val tp3Pnl: Double
        val highestTpPnl: Double
        val slPnl: Double

        if (storedLevels.isNotEmpty() || row.tp1PnlAmount != null || row.slPnlAmount != null) {
            tpLevels = storedLevels
            tp1Pnl = DefaultRulesEngine.roundValue(row.tp1PnlAmount ?: 0.0, 2)
            tp2Pnl = DefaultRulesEngine.roundValue(row.tp2PnlAmount ?: 0.0, 2)
            tp3Pnl = DefaultRulesEngine.roundValue(row.tp3PnlAmount ?: 0.0, 2)
            highestTpPnl = DefaultRulesEngine.roundValue(row.highestTpPnlAmount ?: 0.0, 2)
            slPnl = DefaultRulesEngine.roundValue(row.slPnlAmount ?: 0.0, 2)
        } else {
            // Compute projected per-level P&L so even unverified / idle-wait signal cards display TP1/TP2/TP3 & SL money values
            val dirMult = if ((row.direction ?: "BUY").equals("BUY", ignoreCase = true)) 1.0 else -1.0
            val entryVal = row.entryPrice
            val qty = when {
                entryVal == null || entryVal <= 0.0 -> 0.0
                catKey == "CRYPTO" -> prof.usdAmount / entryVal
                else -> prof.lots * (prof.lotSize?.takeIf { it > 0.0 } ?: 1.0)
            }
            val builtLevels = mutableListOf<TargetLevelPnl>()
            if (entryVal != null && qty > 0.0) {
                targets.forEachIndexed { idx, tpPrice ->
                    val pts = DefaultRulesEngine.roundValue((tpPrice - entryVal) * dirMult, 4)
                    val money = DefaultRulesEngine.roundValue(pts * qty, 2)
                    builtLevels += TargetLevelPnl(
                        index = idx + 1,
                        targetPrice = tpPrice,
                        pointsPnl = pts,
                        moneyPnl = money,
                        hit = false,
                        hitAt = null
                    )
                }
            }
            tpLevels = builtLevels
            tp1Pnl = builtLevels.getOrNull(0)?.moneyPnl ?: 0.0
            tp2Pnl = builtLevels.getOrNull(1)?.moneyPnl ?: 0.0
            tp3Pnl = builtLevels.getOrNull(2)?.moneyPnl ?: 0.0
            highestTpPnl = 0.0
            slPnl = if (entryVal != null && row.stopLoss != null && qty > 0.0) {
                DefaultRulesEngine.roundValue((row.stopLoss - entryVal) * dirMult * qty, 2)
            } else 0.0
        }

        return EnrichedSignal(
            row = row,
            targets = targets,
            additionalEntries = additionalEntries,
            events = events,
            legs = legs,
            pnlAmount = DefaultRulesEngine.roundValue(row.pnlAmount ?: 0.0, 2),
            pnlCurrency = resolvedCurrency,
            tradesCount = row.tradesCount ?: 0,
            ageMinutes = DefaultRulesEngine.roundValue(ageMins, 1),
            isAutoScheduled = isAutoScheduled,
            isRecent = isRecent,
            isManualNeeded = isManualNeeded,
            isHistorical = !(isRecent || isAutoScheduled),
            isDefaultSl = "Default SL:" in expl,
            isDefaultTp = "Default TPs:" in expl,
            statusReason = buildStatusReason(
                row = row,
                initialWait = initialWait,
                repeatMins = repeatMins,
                fastPhaseMins = fastPhaseMins,
                extendedIntervalMins = extendedIntervalMins,
                monitoringPhase = monitoringPhase,
                maxTpHit = maxTpHit,
                allTargetsHit = allTargetsHit,
                closedBySlAfterTp = closedBySlAfterTp
            ),
            maxTpHit = maxTpHit,
            allTargetsHit = allTargetsHit,
            closedBySlAfterTp = closedBySlAfterTp,
            tp1PnlAmount = tp1Pnl,
            tp2PnlAmount = tp2Pnl,
            tp3PnlAmount = tp3Pnl,
            highestTpPnlAmount = highestTpPnl,
            slPnlAmount = slPnl,
            tpLevels = tpLevels,
            monitoringPhase = monitoringPhase
        )
    }

    private fun buildStatusReason(
        row: SignalJoinedRow,
        initialWait: Int,
        repeatMins: Int,
        fastPhaseMins: Int,
        extendedIntervalMins: Int,
        monitoringPhase: String,
        maxTpHit: Int,
        allTargetsHit: Boolean,
        closedBySlAfterTp: Boolean
    ): String {
        val pStatus = row.parsingStatus
        val vStatus = row.verificationStatus
        val nextAt = row.nextScheduledAt
        val lastRes = row.lastResult
        val tradeDetail = row.tradeDetail

        if (pStatus == "needs_review") {
            return "Needs manual review: parser could not extract all required fields (Symbol, Direction, or Entry)."
        }
        if (closedBySlAfterTp) {
            return "Closed at SL after TP${maxOf(1, maxTpHit)} (Profitable trade) — monitoring automatically stopped."
        }
        if (vStatus == "target_hit" && allTargetsHit) {
            return "${tradeDetail ?: "All targets achieved!"} Monitoring automatically stopped."
        }
        if (vStatus == "stop_loss_hit") {
            return "${tradeDetail ?: "Stop loss hit."} Monitoring automatically stopped."
        }
        if (vStatus == "historical_data_missing" && !tradeDetail.isNullOrBlank()) return tradeDetail

        val cadenceLabel = when (monitoringPhase) {
            "idle_15m" -> "Idle wait (${initialWait}m before first fetch)"
            "fast_15m" -> "Fast ${repeatMins}m interval (first ${fastPhaseMins}m)"
            "hourly_1h" -> "Hourly (${extendedIntervalMins}m) interval"
            else -> "Auto-monitoring"
        }

        return when (vStatus) {
            "awaiting_market_data" -> when {
                monitoringPhase == "idle_15m" && !nextAt.isNullOrBlank() ->
                    "Idle wait (${initialWait}m): no candles fetched yet. First automatic check at $nextAt, then every ${repeatMins}m for 1h and hourly after."
                !nextAt.isNullOrBlank() ->
                    "$cadenceLabel — awaiting market data. Next automatic fetch: $nextAt."
                !lastRes.isNullOrBlank() ->
                    "Awaiting market data ($lastRes)."
                else -> "Awaiting market data — self-monitored automatically."
            }
            "pending" -> if (!nextAt.isNullOrBlank()) {
                "$cadenceLabel — entry price not touched yet. Next automatic check: $nextAt."
            } else tradeDetail ?: "Candles checked; entry price was not touched."
            "entry_hit" -> if (!nextAt.isNullOrBlank()) {
                "$cadenceLabel — entry hit! Monitoring for TP1/TP2/TP3/SL (next check: $nextAt)."
            } else tradeDetail ?: "Entry hit; monitoring for targets or stop loss."
            "target_hit" -> if (!nextAt.isNullOrBlank()) {
                "${tradeDetail ?: "TP$maxTpHit hit!"} $cadenceLabel continuing for remaining targets (next: $nextAt)."
            } else tradeDetail ?: "Target achieved."
            "ambiguous" -> tradeDetail ?: "Target and stop loss were touched within the same 1m candle."
            else -> tradeDetail ?: lastRes ?: "Ready."
        }
    }

    private fun parseCatalogExpiry(exp: String?): LocalDate {
        if (exp.isNullOrBlank()) return LocalDate.MAX
        val clean = exp.trim().uppercase(Locale.US)
        for (pat in listOf("ddMMMyyyy", "yyyy-MM-dd", "dd-MMM-yyyy")) {
            try {
                return LocalDate.parse(clean, DateTimeFormatter.ofPattern(pat, Locale.US))
            } catch (_: Exception) {
            }
        }
        return LocalDate.MAX
    }

    private fun jsonToMap(raw: String): Map<String, Any?> = try {
        val o = JSONObject(raw)
        val out = linkedMapOf<String, Any?>()
        val k = o.keys()
        while (k.hasNext()) {
            val key = k.next()
            out[key] = o.opt(key)
        }
        out
    } catch (_: Exception) {
        emptyMap()
    }

    companion object {
        val SUPPORTED_PROVIDERS = setOf("google_sheets", "binance", "angel_one", "twelve_data")

        val CATEGORY_ALIASES = linkedMapOf(
            "Index Options" to listOf("index options", "indian options", "options"),
            "Stock Options" to listOf("stock options"),
            "MCX" to listOf("mcx"),
            "Forex" to listOf("forex", "fx"),
            "Crypto" to listOf("crypto")
        )

        val ANGEL_INTERVAL_MAP = mapOf(
            "1m" to "ONE_MINUTE", "3m" to "THREE_MINUTE", "5m" to "FIVE_MINUTE",
            "10m" to "TEN_MINUTE", "15m" to "FIFTEEN_MINUTE", "30m" to "THIRTY_MINUTE",
            "1h" to "ONE_HOUR", "1d" to "ONE_DAY"
        )

        val CATALOG_PRIORITY = listOf(
            "NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "NIFTYNXT50",
            "SENSEX", "BANKEX",
            "CRUDEOIL", "CRUDEOILM", "NATURALGAS", "NATGASMINI",
            "GOLD", "GOLDM", "GOLDPETAL", "SILVER", "SILVERM", "SILVERMIC", "COPPER", "ZINC", "ALUMINIUM", "LEAD"
        )

        val BUILTIN_SPOT_INDICES: List<JSONObject> = listOf(
            JSONObject(mapOf("trading_symbol" to "NIFTY 50 (Spot Index)", "instrument_token" to "99926000", "exchange_segment" to "NSE", "instrument_type" to "AMXIDX", "expiry" to "", "strike" to 0, "lot_size" to 1, "underlying" to "NIFTY")),
            JSONObject(mapOf("trading_symbol" to "NIFTY BANK (Spot Index)", "instrument_token" to "99926009", "exchange_segment" to "NSE", "instrument_type" to "AMXIDX", "expiry" to "", "strike" to 0, "lot_size" to 1, "underlying" to "BANKNIFTY")),
            JSONObject(mapOf("trading_symbol" to "FINNIFTY (Spot Index)", "instrument_token" to "99926037", "exchange_segment" to "NSE", "instrument_type" to "AMXIDX", "expiry" to "", "strike" to 0, "lot_size" to 1, "underlying" to "FINNIFTY")),
            JSONObject(mapOf("trading_symbol" to "MIDCPNIFTY (Spot Index)", "instrument_token" to "99926074", "exchange_segment" to "NSE", "instrument_type" to "AMXIDX", "expiry" to "", "strike" to 0, "lot_size" to 1, "underlying" to "MIDCPNIFTY")),
            JSONObject(mapOf("trading_symbol" to "INDIA VIX (Spot Index)", "instrument_token" to "99926017", "exchange_segment" to "NSE", "instrument_type" to "AMXIDX", "expiry" to "", "strike" to 0, "lot_size" to 1, "underlying" to "INDIAVIX")),
            JSONObject(mapOf("trading_symbol" to "SENSEX (Spot Index)", "instrument_token" to "99919000", "exchange_segment" to "BSE", "instrument_type" to "AMXIDX", "expiry" to "", "strike" to 0, "lot_size" to 1, "underlying" to "SENSEX"))
        )

        fun canonicalCategory(value: String): String {
            val lowered = value.trim().lowercase(Locale.US)
            return CATEGORY_ALIASES.entries.firstOrNull { lowered in it.value }?.key ?: value
        }

        fun categoryAliasesFor(category: String): List<String> =
            CATEGORY_ALIASES[category] ?: listOf(category.trim().lowercase(Locale.US))
    }
}
