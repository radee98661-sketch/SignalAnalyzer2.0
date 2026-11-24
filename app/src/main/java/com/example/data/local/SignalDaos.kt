package com.example.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

data class NamedCount(
    val name: String?,
    val value: Int
)

data class SignalJoinedRow(
    val id: Long,
    @ColumnInfo(name = "raw_notification_id") val rawNotificationId: Long,
    val symbol: String?,
    val direction: String?,
    @ColumnInfo(name = "entry_price") val entryPrice: Double?,
    @ColumnInfo(name = "additional_entries") val additionalEntries: String,
    @ColumnInfo(name = "stop_loss") val stopLoss: Double?,
    val targets: String,
    @ColumnInfo(name = "option_type") val optionType: String?,
    @ColumnInfo(name = "strike_price") val strikePrice: Double?,
    @ColumnInfo(name = "expiry_date") val expiryDate: String?,
    @ColumnInfo(name = "entry_mode") val entryMode: String,
    @ColumnInfo(name = "parsing_status") val parsingStatus: String,
    @ColumnInfo(name = "parsing_confidence") val parsingConfidence: Double,
    @ColumnInfo(name = "manual_review_status") val manualReviewStatus: String,
    @ColumnInfo(name = "verification_status") val verificationStatus: String,
    @ColumnInfo(name = "final_result") val finalResult: String?,
    @ColumnInfo(name = "parsing_explanation") val parsingExplanation: String?,
    @ColumnInfo(name = "updated_at") val updatedAt: String,
    @ColumnInfo(name = "received_at") val receivedAt: String?,
    @ColumnInfo(name = "notification_time") val notificationTime: String?,
    @ColumnInfo(name = "source_app") val sourceApp: String?,
    val channel: String?,
    val category: String?,
    val title: String?,
    val message: String?,
    @ColumnInfo(name = "notification_id") val notificationId: String?,
    @ColumnInfo(name = "is_duplicate") val isDuplicate: Int,
    @ColumnInfo(name = "duplicate_of") val duplicateOf: Long?,
    @ColumnInfo(name = "duplicate_reason") val duplicateReason: String?,
    @ColumnInfo(name = "duplicate_group_id") val duplicateGroupId: String?,
    @ColumnInfo(name = "first_scheduled_at") val firstScheduledAt: String?,
    @ColumnInfo(name = "next_scheduled_at") val nextScheduledAt: String?,
    @ColumnInfo(name = "last_verified_at") val lastVerifiedAt: String?,
    @ColumnInfo(name = "last_result") val lastResult: String?,
    @ColumnInfo(name = "scheduler_status") val schedulerStatus: String?,
    @ColumnInfo(name = "verification_timeframe") val verificationTimeframe: String?,
    @ColumnInfo(name = "entry_hit_at") val entryHitAt: String?,
    @ColumnInfo(name = "target_hit_at") val targetHitAt: String?,
    @ColumnInfo(name = "stop_loss_hit_at") val stopLossHitAt: String?,
    @ColumnInfo(name = "events_json") val eventsJson: String?,
    @ColumnInfo(name = "trade_detail") val tradeDetail: String?,
    @ColumnInfo(name = "pnl_amount") val pnlAmount: Double?,
    @ColumnInfo(name = "pnl_currency") val pnlCurrency: String?,
    @ColumnInfo(name = "trades_count") val tradesCount: Int?,
    @ColumnInfo(name = "legs_json") val legsJson: String?,
    @ColumnInfo(name = "max_tp_hit") val maxTpHit: Int? = 0,
    @ColumnInfo(name = "all_targets_hit") val allTargetsHit: Int? = 0,
    @ColumnInfo(name = "closed_by_sl_after_tp") val closedBySlAfterTp: Int? = 0,
    @ColumnInfo(name = "tp1_pnl_amount") val tp1PnlAmount: Double? = 0.0,
    @ColumnInfo(name = "tp2_pnl_amount") val tp2PnlAmount: Double? = 0.0,
    @ColumnInfo(name = "tp3_pnl_amount") val tp3PnlAmount: Double? = 0.0,
    @ColumnInfo(name = "highest_tp_pnl_amount") val highestTpPnlAmount: Double? = 0.0,
    @ColumnInfo(name = "sl_pnl_amount") val slPnlAmount: Double? = 0.0,
    @ColumnInfo(name = "tp_levels_json") val tpLevelsJson: String? = null
)

data class NotificationJoinedRow(
    val id: Long,
    @ColumnInfo(name = "received_at") val receivedAt: String?,
    @ColumnInfo(name = "notification_time") val notificationTime: String?,
    @ColumnInfo(name = "source_app") val sourceApp: String?,
    @ColumnInfo(name = "package") val packageName: String?,
    val channel: String?,
    val category: String?,
    val title: String?,
    val message: String?,
    @ColumnInfo(name = "notification_id") val notificationId: String?,
    @ColumnInfo(name = "delivery_id") val deliveryId: String?,
    @ColumnInfo(name = "imported_at") val importedAt: String,
    @ColumnInfo(name = "is_duplicate") val isDuplicate: Int,
    @ColumnInfo(name = "duplicate_of") val duplicateOf: Long?,
    @ColumnInfo(name = "duplicate_reason") val duplicateReason: String?,
    @ColumnInfo(name = "duplicate_group_id") val duplicateGroupId: String?,
    @ColumnInfo(name = "signal_id") val signalId: Long?,
    val symbol: String?,
    val direction: String?,
    @ColumnInfo(name = "entry_price") val entryPrice: Double?,
    @ColumnInfo(name = "additional_entries") val additionalEntries: String?,
    @ColumnInfo(name = "stop_loss") val stopLoss: Double?,
    val targets: String?,
    @ColumnInfo(name = "option_type") val optionType: String?,
    @ColumnInfo(name = "strike_price") val strikePrice: Double?,
    @ColumnInfo(name = "expiry_date") val expiryDate: String?,
    @ColumnInfo(name = "entry_mode") val entryMode: String?,
    @ColumnInfo(name = "parsing_status") val parsingStatus: String?,
    @ColumnInfo(name = "manual_review_status") val manualReviewStatus: String?,
    @ColumnInfo(name = "parsing_confidence") val parsingConfidence: Double?,
    @ColumnInfo(name = "parsing_explanation") val parsingExplanation: String?,
    @ColumnInfo(name = "verification_status") val verificationStatus: String?
)

data class ChannelAnalyticsRow(
    val channel: String?,
    val total: Int,
    @ColumnInfo(name = "today_total") val todayTotal: Int,
    @ColumnInfo(name = "today_tp") val todayTp: Int,
    @ColumnInfo(name = "today_sl") val todaySl: Int,
    val parsed: Int,
    @ColumnInfo(name = "trades_triggered") val tradesTriggered: Int,
    val targets: Int,
    val stops: Int,
    val pending: Int,
    @ColumnInfo(name = "pnl_inr") val pnlInr: Double,
    @ColumnInfo(name = "pnl_usd") val pnlUsd: Double,
    @ColumnInfo(name = "today_pnl_inr") val todayPnlInr: Double,
    @ColumnInfo(name = "today_pnl_usd") val todayPnlUsd: Double
)

data class MarketCoverageRow(
    val provider: String,
    val symbol: String,
    @ColumnInfo(name = "raw_symbol") val rawSymbol: String,
    val exchange: String,
    val interval: String,
    val candles: Int,
    val earliest: String?,
    val latest: String?,
    @ColumnInfo(name = "last_retrieved") val lastRetrieved: String?
)

data class CandleBounds(
    val minTs: String?,
    val maxTs: String?
)

data class DistinctSignalSymbol(
    val category: String?,
    val symbol: String?,
    @ColumnInfo(name = "option_type") val optionType: String?
)

@Dao
interface CoreDao {

    @Query("SELECT version FROM schema_version LIMIT 1")
    suspend fun getSchemaVersion(): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSchemaVersion(entity: SchemaVersionEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCategory(category: CategoryEntity): Long

    @Query("DELETE FROM categories WHERE name = :name")
    suspend fun deleteCategoryByName(name: String)

    @Query("UPDATE raw_notifications SET category = :newName WHERE LOWER(TRIM(COALESCE(category, ''))) = LOWER(:oldName)")
    suspend fun migrateRawNotificationCategory(oldName: String, newName: String)

    @Query("UPDATE channels SET category = :newName WHERE LOWER(TRIM(COALESCE(category, ''))) = LOWER(:oldName)")
    suspend fun migrateChannelCategory(oldName: String, newName: String)

    @Query("SELECT name FROM categories WHERE enabled=1 ORDER BY id")
    suspend fun getEnabledCategoryNames(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertChannel(channel: ChannelEntity): Long

    @Query("SELECT * FROM application_settings")
    suspend fun getAllSettings(): List<ApplicationSettingEntity>

    @Query("SELECT value FROM application_settings WHERE key = :key LIMIT 1")
    suspend fun getSetting(key: String): String?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSettingIfMissing(setting: ApplicationSettingEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSetting(setting: ApplicationSettingEntity)

    @Insert
    suspend fun insertSystemLog(log: SystemLogEntity): Long

    @Query("SELECT * FROM system_logs ORDER BY id DESC LIMIT 300")
    suspend fun getRecentSystemLogs(): List<SystemLogEntity>

    @Query("SELECT * FROM system_logs ORDER BY id DESC LIMIT 300")
    fun observeRecentSystemLogs(): Flow<List<SystemLogEntity>>

    // Raw Notifications
    @Query("SELECT id FROM raw_notifications WHERE notification_id = :notificationId LIMIT 1")
    suspend fun findRawIdByNotificationId(notificationId: String): Long?

    @Query("SELECT * FROM raw_notifications WHERE notification_id = :notificationId LIMIT 1")
    suspend fun getRawNotificationByNotificationId(notificationId: String): RawNotificationEntity?

    @Query(
        "UPDATE raw_notifications SET message=:message, title=COALESCE(NULLIF(:title, ''), title), " +
            "category=COALESCE(NULLIF(:category, ''), category), received_at=COALESCE(NULLIF(:receivedAt, ''), received_at), " +
            "delivery_id=COALESCE(NULLIF(:deliveryId, ''), delivery_id), is_duplicate=0, duplicate_of=NULL, duplicate_reason=NULL " +
            "WHERE id=:id"
    )
    suspend fun updateEditedRawNotification(
        id: Long,
        message: String,
        title: String?,
        category: String?,
        receivedAt: String?,
        deliveryId: String?
    )

    @Insert
    suspend fun insertRawNotification(raw: RawNotificationEntity): Long

    @Query("SELECT * FROM raw_notifications WHERE id = :id")
    suspend fun getRawNotificationById(id: Long): RawNotificationEntity?

    @Query("SELECT * FROM raw_notifications WHERE id <> :excludeId AND COALESCE(channel, '') = :channel ORDER BY id")
    suspend fun getRawCandidatesForDuplicateCheck(excludeId: Long, channel: String): List<RawNotificationEntity>

    @Query("SELECT * FROM raw_notifications WHERE duplicate_group_id IS NULL ORDER BY id")
    suspend fun getLegacyUnclassifiedRawNotifications(): List<RawNotificationEntity>

    @Query("UPDATE raw_notifications SET duplicate_group_id=:groupId, is_duplicate=0, duplicate_of=NULL, duplicate_reason=NULL WHERE id=:id")
    suspend fun markRawPrimary(id: Long, groupId: String)

    @Query("UPDATE raw_notifications SET is_duplicate=1, duplicate_of=:duplicateOf, duplicate_group_id=:groupId, duplicate_reason=:reason WHERE id=:id")
    suspend fun markRawDuplicate(id: Long, duplicateOf: Long, groupId: String, reason: String)

    @Query("UPDATE raw_notifications SET duplicate_group_id=:groupId WHERE id IN (:id1, :id2)")
    suspend fun setDuplicateGroupPair(id1: Long, id2: Long, groupId: String)

    @Query("UPDATE raw_notifications SET duplicate_reason=:reason WHERE id=:id")
    suspend fun setDuplicateReason(id: Long, reason: String)

    @Query("UPDATE raw_notifications SET notification_time=:notificationTime WHERE id=(SELECT raw_notification_id FROM signals WHERE id=:signalId)")
    suspend fun updateNotificationTimeForSignal(signalId: Long, notificationTime: String)

    @Query("SELECT * FROM raw_notifications ORDER BY id DESC LIMIT 8")
    suspend fun getRecentRawNotifications(): List<RawNotificationEntity>

    @Query("SELECT * FROM raw_notifications ORDER BY id ASC")
    suspend fun getAllRawNotifications(): List<RawNotificationEntity>

    @Query("SELECT DISTINCT channel FROM raw_notifications WHERE channel IS NOT NULL AND channel <> '' ORDER BY channel")
    suspend fun getDistinctChannels(): List<String>

    @Query("SELECT DISTINCT channel FROM raw_notifications WHERE channel IS NOT NULL AND channel <> '' AND LOWER(category) IN (:aliases) ORDER BY channel")
    suspend fun getDistinctChannelsByCategoryAliases(aliases: List<String>): List<String>

    // Signals
    @Insert
    suspend fun insertSignal(signal: SignalEntity): Long

    @Query("SELECT * FROM signals WHERE id = :id")
    suspend fun getSignalById(id: Long): SignalEntity?

    @Query("SELECT * FROM signals WHERE raw_notification_id = :rawId ORDER BY id ASC")
    suspend fun getSignalsByRawId(rawId: Long): List<SignalEntity>

    @Query("SELECT * FROM signals WHERE raw_notification_id = :rawId AND id <> :excludeId ORDER BY id ASC")
    suspend fun getSiblingSignals(rawId: Long, excludeId: Long): List<SignalEntity>

    @Query(
        "SELECT s.symbol FROM signals s JOIN raw_notifications r ON r.id = s.raw_notification_id " +
            "WHERE COALESCE(r.channel,'') = :channel AND LOWER(COALESCE(r.category,'')) IN ('forex','fx') " +
            "AND r.is_duplicate = 0 AND s.symbol IS NOT NULL AND s.symbol <> '' " +
            "AND COALESCE(r.notification_time, r.received_at) >= :cutoffIso " +
            "ORDER BY s.id DESC LIMIT 1"
    )
    suspend fun findRecentForexSymbol(channel: String, cutoffIso: String): String?

    @Query(
        "SELECT s.symbol FROM signals s JOIN raw_notifications r ON r.id = s.raw_notification_id " +
            "WHERE COALESCE(r.channel,'') = :channel AND LOWER(COALESCE(r.category,'')) IN ('forex','fx') " +
            "AND r.is_duplicate = 0 AND s.symbol IS NOT NULL AND s.symbol <> '' " +
            "AND COALESCE(r.notification_time, r.received_at) >= :cutoffIso " +
            "AND COALESCE(r.notification_time, r.received_at) <= :upperIso AND r.id <> :excludeRawId " +
            "ORDER BY s.id DESC LIMIT 1"
    )
    suspend fun findRecentForexSymbolForReparse(channel: String, cutoffIso: String, upperIso: String, excludeRawId: Long): String?

    @Query(
        "UPDATE signals SET symbol=:symbol, direction=:direction, entry_price=:entryPrice, additional_entries=:additionalEntries, " +
            "stop_loss=:stopLoss, targets=:targets, option_type=:optionType, strike_price=:strikePrice, expiry_date=:expiryDate, " +
            "entry_mode=:entryMode, parsing_status=:parsingStatus, parsing_confidence=:parsingConfidence, " +
            "manual_review_status=:manualReviewStatus, parsing_explanation=:parsingExplanation, updated_at=:updatedAt WHERE id=:id"
    )
    suspend fun updateSignalParsedFields(
        id: Long,
        symbol: String?,
        direction: String?,
        entryPrice: Double?,
        additionalEntries: String,
        stopLoss: Double?,
        targets: String,
        optionType: String?,
        strikePrice: Double?,
        expiryDate: String?,
        entryMode: String,
        parsingStatus: String,
        parsingConfidence: Double,
        manualReviewStatus: String,
        parsingExplanation: String?,
        updatedAt: String
    )

    @Query(
        "UPDATE signals SET entry_price=:entryPrice, stop_loss=:stopLoss, targets=:targets, " +
            "parsing_explanation=:explanation, updated_at=:updatedAt WHERE id=:id"
    )
    suspend fun updateSignalMarketEntryAndDefaults(
        id: Long,
        entryPrice: Double?,
        stopLoss: Double?,
        targets: String,
        explanation: String?,
        updatedAt: String
    )

    @Query("UPDATE signals SET stop_loss=:stopLoss, targets=:targets, parsing_explanation=:explanation, updated_at=:updatedAt WHERE id=:id")
    suspend fun updateSignalDefaults(id: Long, stopLoss: Double?, targets: String, explanation: String?, updatedAt: String)

    @Query("UPDATE signals SET verification_status=:status, final_result=:finalResult, updated_at=:updatedAt WHERE id=:id")
    suspend fun updateSignalVerificationStatus(id: Long, status: String, finalResult: String?, updatedAt: String)

    @Query("DELETE FROM signals WHERE id = :id")
    suspend fun deleteSignalById(id: Long): Int

    @Query("DELETE FROM signals WHERE id IN (:ids)")
    suspend fun deleteSignalsByIds(ids: List<Long>): Int

    @RawQuery
    suspend fun querySignalsJoined(query: SupportSQLiteQuery): List<SignalJoinedRow>

    @RawQuery
    suspend fun queryNotificationsJoined(query: SupportSQLiteQuery): List<NotificationJoinedRow>

    @Query(
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
            "WHERE s.id = :signalId LIMIT 1"
    )
    suspend fun getSignalJoinedById(signalId: Long): SignalJoinedRow?

    @Query(
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
            "WHERE r.is_duplicate = 0 ORDER BY s.id DESC LIMIT 8"
    )
    suspend fun getRecentSignalsJoined(): List<SignalJoinedRow>

    @Query(
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
            "WHERE (s.stop_loss IS NULL OR s.targets IS NULL OR s.targets = '' OR s.targets = '[]') " +
            "AND s.entry_price IS NOT NULL AND s.direction IN ('BUY','SELL') AND s.symbol IS NOT NULL"
    )
    suspend fun getSignalsMissingDefaults(): List<SignalJoinedRow>

    @Query(
        "SELECT DISTINCT r.category, s.symbol, s.option_type FROM signals s " +
            "JOIN raw_notifications r ON r.id = s.raw_notification_id " +
            "WHERE s.symbol IS NOT NULL AND s.symbol <> ''"
    )
    suspend fun getDistinctSignalSymbols(): List<DistinctSignalSymbol>

    @Query(
        "SELECT s.id FROM signals s JOIN raw_notifications r ON r.id = s.raw_notification_id " +
            "WHERE s.id IN (:ids) ORDER BY COALESCE(r.notification_time, r.received_at) ASC, s.id ASC"
    )
    suspend fun getSignalIdsOrderedOldestFirst(ids: List<Long>): List<Long>

    @Query("SELECT * FROM signals ORDER BY id ASC")
    suspend fun getAllSignals(): List<SignalEntity>

    // Overview & Analytics
    @Query("SELECT COUNT(*) FROM raw_notifications WHERE is_duplicate = 0")
    suspend fun countPrimaryNotifications(): Int

    @Query("SELECT COUNT(*) FROM signals s JOIN raw_notifications r ON r.id = s.raw_notification_id WHERE r.is_duplicate = 0 AND s.parsing_status = 'parsed'")
    suspend fun countParsedSignals(): Int

    @Query("SELECT COUNT(*) FROM signals s JOIN raw_notifications r ON r.id = s.raw_notification_id WHERE r.is_duplicate = 0 AND s.verification_status IN ('target_hit','stop_loss_hit')")
    suspend fun countVerifiedSignals(): Int

    @Query("SELECT COUNT(*) FROM signals s JOIN raw_notifications r ON r.id = s.raw_notification_id WHERE r.is_duplicate = 0 AND s.verification_status = 'target_hit'")
    suspend fun countTargetHits(): Int

    @Query("SELECT COUNT(*) FROM signals s JOIN raw_notifications r ON r.id = s.raw_notification_id WHERE r.is_duplicate = 0 AND s.verification_status = 'stop_loss_hit'")
    suspend fun countStopLossHits(): Int

    @Query("SELECT COUNT(*) FROM signals s JOIN raw_notifications r ON r.id = s.raw_notification_id WHERE r.is_duplicate = 0 AND s.verification_status = 'pending'")
    suspend fun countPendingSignals(): Int

    @Query("SELECT COUNT(*) FROM signals s JOIN raw_notifications r ON r.id = s.raw_notification_id WHERE r.is_duplicate = 0 AND s.verification_status IN ('entry_hit','target_hit','stop_loss_hit','ambiguous')")
    suspend fun countEnteredSignals(): Int

    @Query("SELECT COUNT(*) FROM raw_notifications WHERE is_duplicate = 0 AND substr(COALESCE(notification_time, received_at), 1, 10) = :todayDate")
    suspend fun countTodayNotifications(todayDate: String): Int

    @Query(
        "SELECT ROUND(COALESCE(SUM(tr.pnl_amount), 0), 2) FROM trade_results tr " +
            "JOIN signals s ON s.id = tr.signal_id JOIN raw_notifications r ON r.id = s.raw_notification_id " +
            "WHERE r.is_duplicate = 0 AND tr.pnl_currency = :currency"
    )
    suspend fun sumPnlByCurrency(currency: String): Double

    @Query("SELECT category AS name, COUNT(*) AS value FROM raw_notifications WHERE is_duplicate = 0 GROUP BY category")
    suspend fun getCategoryDistribution(): List<NamedCount>

    @Query("SELECT channel AS name, COUNT(*) AS value FROM raw_notifications WHERE is_duplicate = 0 GROUP BY channel ORDER BY value DESC LIMIT 8")
    suspend fun getChannelDistribution(): List<NamedCount>

    @Query(
        "SELECT r.channel, COUNT(*) AS total, " +
            "SUM(CASE WHEN substr(COALESCE(r.notification_time, r.received_at), 1, 10) = :today THEN 1 ELSE 0 END) AS today_total, " +
            "SUM(CASE WHEN substr(COALESCE(r.notification_time, r.received_at), 1, 10) = :today AND s.verification_status = 'target_hit' THEN 1 ELSE 0 END) AS today_tp, " +
            "SUM(CASE WHEN substr(COALESCE(r.notification_time, r.received_at), 1, 10) = :today AND s.verification_status = 'stop_loss_hit' THEN 1 ELSE 0 END) AS today_sl, " +
            "SUM(CASE WHEN s.parsing_status IN ('parsed','manually_corrected') THEN 1 ELSE 0 END) AS parsed, " +
            "COALESCE(SUM(tr.trades_count), 0) AS trades_triggered, " +
            "SUM(CASE WHEN s.verification_status = 'target_hit' THEN 1 ELSE 0 END) AS targets, " +
            "SUM(CASE WHEN s.verification_status = 'stop_loss_hit' THEN 1 ELSE 0 END) AS stops, " +
            "SUM(CASE WHEN s.verification_status = 'pending' THEN 1 ELSE 0 END) AS pending, " +
            "ROUND(COALESCE(SUM(CASE WHEN tr.pnl_currency = 'INR' THEN tr.pnl_amount ELSE 0 END), 0), 2) AS pnl_inr, " +
            "ROUND(COALESCE(SUM(CASE WHEN tr.pnl_currency = 'USD' THEN tr.pnl_amount ELSE 0 END), 0), 2) AS pnl_usd, " +
            "ROUND(COALESCE(SUM(CASE WHEN substr(COALESCE(r.notification_time, r.received_at), 1, 10) = :today AND tr.pnl_currency = 'INR' THEN tr.pnl_amount ELSE 0 END), 0), 2) AS today_pnl_inr, " +
            "ROUND(COALESCE(SUM(CASE WHEN substr(COALESCE(r.notification_time, r.received_at), 1, 10) = :today AND tr.pnl_currency = 'USD' THEN tr.pnl_amount ELSE 0 END), 0), 2) AS today_pnl_usd " +
            "FROM signals s JOIN raw_notifications r ON r.id = s.raw_notification_id " +
            "LEFT JOIN trade_results tr ON tr.signal_id = s.id " +
            "WHERE r.is_duplicate = 0 GROUP BY r.channel ORDER BY total DESC"
    )
    suspend fun getChannelAnalytics(today: String): List<ChannelAnalyticsRow>

    // Integrations & Sync logs
    @Query("SELECT * FROM integrations ORDER BY provider")
    suspend fun getAllIntegrations(): List<IntegrationEntity>

    @Query("SELECT * FROM integrations WHERE provider = :provider LIMIT 1")
    suspend fun getIntegration(provider: String): IntegrationEntity?

    @Query(
        "INSERT INTO integrations(provider, config_json, status, updated_at) VALUES(:provider, :configJson, :status, :updatedAt) " +
            "ON CONFLICT(provider) DO UPDATE SET config_json = excluded.config_json, status = excluded.status, updated_at = excluded.updated_at"
    )
    suspend fun upsertIntegration(provider: String, configJson: String, status: String, updatedAt: String)

    @Query("UPDATE integrations SET status = :status, updated_at = :updatedAt WHERE provider = :provider")
    suspend fun updateIntegrationStatus(provider: String, status: String, updatedAt: String)

    @Query("DELETE FROM integrations WHERE provider = :provider")
    suspend fun deleteIntegration(provider: String)

    @Insert
    suspend fun insertSyncLog(log: SynchronizationLogEntity): Long

    @Query(
        "UPDATE synchronization_logs SET finished_at=:finishedAt, status=:status, imported_rows=:imported, " +
            "duplicate_rows=:duplicates, skipped_rows=:skipped, failed_rows=:failed, detail=:detail WHERE id=:id"
    )
    suspend fun finishSyncLog(
        id: Long,
        finishedAt: String,
        status: String,
        imported: Int,
        duplicates: Int,
        skipped: Int,
        failed: Int,
        detail: String?
    )

    @Query("SELECT * FROM synchronization_logs ORDER BY id DESC LIMIT 100")
    suspend fun getSyncLogs(): List<SynchronizationLogEntity>

    @Query("SELECT * FROM synchronization_logs ORDER BY id DESC LIMIT 1")
    suspend fun getLatestSyncLog(): SynchronizationLogEntity?

    // Parsing runs
    @Insert
    suspend fun insertParsingRun(run: ParsingRunEntity): Long

    @Query("SELECT * FROM parsing_runs WHERE signal_id = :signalId ORDER BY id DESC LIMIT 50")
    suspend fun getParsingRuns(signalId: Long): List<ParsingRunEntity>
}

@Dao
interface MarketDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCandlesIgnore(candles: List<MarketCandleEntity>): List<Long>

    @Query(
        "SELECT * FROM market_candles WHERE provider = :provider AND symbol = :symbol " +
            "AND candle_timestamp >= :startIso ORDER BY candle_timestamp ASC LIMIT :limit"
    )
    suspend fun getCandlesSince(provider: String, symbol: String, startIso: String, limit: Int = 5000): List<MarketCandleEntity>

    @Query("SELECT MIN(candle_timestamp) AS minTs, MAX(candle_timestamp) AS maxTs FROM market_candles WHERE provider = :provider AND symbol = :symbol")
    suspend fun getCandleBounds(provider: String, symbol: String): CandleBounds?

    @Query(
        "SELECT mc.provider, COALESCE(i.trading_symbol || ' (' || mc.symbol || ')', mc.symbol) AS symbol, " +
            "mc.symbol AS raw_symbol, mc.exchange, mc.interval, COUNT(*) AS candles, " +
            "MIN(mc.candle_timestamp) AS earliest, MAX(mc.candle_timestamp) AS latest, MAX(mc.retrieved_at) AS last_retrieved " +
            "FROM market_candles mc LEFT JOIN instruments i ON mc.provider = 'angel_one' AND i.instrument_token = mc.symbol " +
            "GROUP BY mc.provider, mc.symbol, mc.exchange, mc.interval ORDER BY mc.provider, symbol"
    )
    suspend fun getMarketCoverage(): List<MarketCoverageRow>

    @Query("DELETE FROM market_candles WHERE provider = :provider AND symbol = :symbol")
    suspend fun deleteCacheForSymbol(provider: String, symbol: String): Int

    // Instruments
    @Query("SELECT COUNT(*) FROM instruments")
    suspend fun countInstruments(): Int

    @Query("SELECT * FROM instruments WHERE instrument_token = :token LIMIT 1")
    suspend fun getInstrumentByToken(token: String): InstrumentEntity?

    @Query("SELECT * FROM instruments WHERE instrument_token = :token AND exchange_segment = :exchange LIMIT 1")
    suspend fun getInstrumentByTokenAndExchange(token: String, exchange: String): InstrumentEntity?

    @Query(
        "INSERT INTO instruments(symbol, trading_symbol, instrument_token, exchange_segment, instrument_type, expiry, strike, lot_size, tick_size, raw_json, updated_at) " +
            "VALUES(:symbol, :tradingSymbol, :token, :exchange, :instType, :expiry, :strike, :lotSize, :tickSize, :rawJson, :updatedAt) " +
            "ON CONFLICT(instrument_token) DO UPDATE SET symbol=excluded.symbol, trading_symbol=excluded.trading_symbol, " +
            "exchange_segment=excluded.exchange_segment, instrument_type=excluded.instrument_type, expiry=excluded.expiry, " +
            "strike=excluded.strike, lot_size=excluded.lot_size, tick_size=excluded.tick_size, raw_json=excluded.raw_json, updated_at=excluded.updated_at"
    )
    suspend fun upsertInstrument(
        symbol: String?,
        tradingSymbol: String,
        token: String,
        exchange: String,
        instType: String?,
        expiry: String?,
        strike: Double,
        lotSize: Double,
        tickSize: Double,
        rawJson: String?,
        updatedAt: String
    )

    @Query("SELECT * FROM instruments WHERE UPPER(exchange_segment) = :exchange AND (:instType = 'ALL' OR UPPER(instrument_type) = :instType)")
    suspend fun getInstrumentsForCatalog(exchange: String, instType: String): List<InstrumentEntity>

    @Query(
        "SELECT * FROM instruments WHERE UPPER(exchange_segment) IN (:exchanges) AND UPPER(instrument_type) IN (:instTypes)"
    )
    suspend fun getInstrumentsBySegmentsAndTypes(exchanges: List<String>, instTypes: List<String>): List<InstrumentEntity>

    @Query(
        "SELECT * FROM instruments WHERE UPPER(exchange_segment) IN (:exchanges) AND UPPER(instrument_type) IN (:instTypes) " +
            "AND (ABS(strike - :scaledStrike) < 1.0 OR ABS(strike - :rawStrike) < 0.05) AND UPPER(trading_symbol) LIKE :optLike"
    )
    suspend fun findOptionInstruments(
        exchanges: List<String>,
        instTypes: List<String>,
        scaledStrike: Double,
        rawStrike: Double,
        optLike: String
    ): List<InstrumentEntity>

    @Query(
        "SELECT * FROM instruments WHERE UPPER(exchange_segment) IN (:exchanges) AND UPPER(instrument_type) IN (:instTypes) " +
            "AND UPPER(trading_symbol) LIKE :prefixLike"
    )
    suspend fun findFutureInstruments(
        exchanges: List<String>,
        instTypes: List<String>,
        prefixLike: String
    ): List<InstrumentEntity>

    @Query("SELECT * FROM instruments WHERE UPPER(trading_symbol) LIKE :prefixLike LIMIT 5")
    suspend fun findCrossSegmentInstruments(prefixLike: String): List<InstrumentEntity>

    @RawQuery
    suspend fun searchInstrumentsRaw(query: SupportSQLiteQuery): List<InstrumentEntity>

    // Mappings
    @Query("SELECT * FROM instrument_mappings ORDER BY updated_at DESC")
    suspend fun getAllInstrumentMappings(): List<InstrumentMappingEntity>

    @Query("SELECT * FROM instrument_mappings WHERE lookup_key = :lookupKey LIMIT 1")
    suspend fun getInstrumentMapping(lookupKey: String): InstrumentMappingEntity?

    @Query(
        "INSERT INTO instrument_mappings(lookup_key, instrument_token, exchange_segment, note, updated_at) " +
            "VALUES(:lookupKey, :token, :exchange, :note, :updatedAt) " +
            "ON CONFLICT(lookup_key) DO UPDATE SET instrument_token=excluded.instrument_token, " +
            "exchange_segment=excluded.exchange_segment, note=excluded.note, updated_at=excluded.updated_at"
    )
    suspend fun upsertInstrumentMapping(lookupKey: String, token: String, exchange: String, note: String?, updatedAt: String)

    // API usage & health log
    @Insert
    suspend fun insertApiRequestLog(log: ApiRequestLogEntity): Long

    @Query("SELECT COALESCE(SUM(credits_used), 0) FROM api_request_log WHERE provider = :provider AND substr(requested_at, 1, 10) = :dayPrefix")
    suspend fun sumCreditsForDay(provider: String, dayPrefix: String): Double

    @Query("SELECT COALESCE(SUM(credits_used), 0) FROM api_request_log WHERE provider = :provider AND substr(requested_at, 1, 16) = :minutePrefix")
    suspend fun sumCreditsForMinute(provider: String, minutePrefix: String): Double

    @Query("SELECT * FROM api_request_log WHERE requested_at >= :sinceIso ORDER BY id DESC LIMIT :limit")
    suspend fun getApiLogsSince(sinceIso: String, limit: Int = 500): List<ApiRequestLogEntity>

    @Query("SELECT * FROM api_request_log WHERE status <> 'success' AND requested_at >= :sinceIso ORDER BY id DESC LIMIT :limit")
    suspend fun getApiErrorsSince(sinceIso: String, limit: Int = 50): List<ApiRequestLogEntity>
}

@Dao
interface VerificationDao {

    @Query("SELECT * FROM verification_schedules WHERE signal_id = :signalId LIMIT 1")
    suspend fun getScheduleForSignal(signalId: Long): VerificationScheduleEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSchedule(schedule: VerificationScheduleEntity): Long

    @Query("UPDATE verification_schedules SET timeframe = :timeframe, updated_at = :updatedAt WHERE signal_id = :signalId")
    suspend fun updateScheduleTimeframe(signalId: Long, timeframe: String, updatedAt: String)

    @Query(
        "UPDATE verification_schedules SET signal_start_at = :startAt, first_scheduled_at = :firstAt, " +
            "next_scheduled_at = :nextAt, status = 'scheduled', updated_at = :updatedAt WHERE signal_id = :signalId"
    )
    suspend fun rescheduleSignal(signalId: Long, startAt: String, firstAt: String, nextAt: String, updatedAt: String)

    @Query(
        "UPDATE verification_schedules SET status = 'scheduled', next_scheduled_at = :nowIso, " +
            "last_result = 'Promoted to automatic self-monitoring', updated_at = :nowIso " +
            "WHERE status = 'manual_required' AND (expires_at IS NULL OR expires_at > :nowIso)"
    )
    suspend fun promoteManualRequiredToScheduled(nowIso: String): Int

    @Query(
        "UPDATE verification_schedules SET status = 'scheduled', locked_at = NULL, updated_at = :updatedAt " +
            "WHERE status = 'processing' AND (locked_at IS NULL OR locked_at < :staleBeforeIso)"
    )
    suspend fun recoverStaleProcessingLeases(staleBeforeIso: String, updatedAt: String): Int

    @Query(
        "SELECT * FROM verification_schedules WHERE status = 'scheduled' AND next_scheduled_at <= :nowIso " +
            "AND (expires_at IS NULL OR expires_at > :nowIso) ORDER BY next_scheduled_at ASC LIMIT 25"
    )
    suspend fun getDueSchedules(nowIso: String): List<VerificationScheduleEntity>

    @Query(
        "UPDATE verification_schedules SET status = 'processing', locked_at = :nowIso, updated_at = :nowIso " +
            "WHERE id = :scheduleId AND status = 'scheduled'"
    )
    suspend fun claimSchedule(scheduleId: Long, nowIso: String): Int

    @Query(
        "UPDATE verification_schedules SET status = :status, last_verified_at = :verifiedAt, " +
            "next_scheduled_at = :nextAt, last_result = :lastResult, locked_at = NULL, updated_at = :updatedAt WHERE id = :scheduleId"
    )
    suspend fun completeScheduleCycle(
        scheduleId: Long,
        status: String,
        verifiedAt: String,
        nextAt: String?,
        lastResult: String?,
        updatedAt: String
    )

    @Insert
    suspend fun insertVerificationRun(run: VerificationRunEntity): Long

    @Query(
        "UPDATE verification_runs SET finished_at = :finishedAt, status = 'complete', result = :result, " +
            "detail = :detail, candle_time = :candleTime WHERE id = :runId"
    )
    suspend fun finishVerificationRun(runId: Long, finishedAt: String, result: String?, detail: String?, candleTime: String?)

    @Query("SELECT * FROM verification_runs WHERE signal_id = :signalId ORDER BY id DESC LIMIT 100")
    suspend fun getVerificationRuns(signalId: Long): List<VerificationRunEntity>

    @Insert
    suspend fun insertSignalAnalysis(analysis: SignalAnalysisEntity): Long

    @Query(
        "INSERT INTO trade_results(signal_id, entry_hit_at, target_hit_at, stop_loss_hit_at, events_json, result, detail, " +
            "pnl_amount, pnl_currency, trades_count, legs_json, max_tp_hit, all_targets_hit, closed_by_sl_after_tp, " +
            "tp1_pnl_amount, tp2_pnl_amount, tp3_pnl_amount, highest_tp_pnl_amount, sl_pnl_amount, tp_levels_json, updated_at) " +
            "VALUES(:signalId, :entryHitAt, :targetHitAt, :stopLossHitAt, :eventsJson, :result, :detail, " +
            ":pnlAmount, :pnlCurrency, :tradesCount, :legsJson, :maxTpHit, :allTargetsHit, :closedBySlAfterTp, " +
            ":tp1PnlAmount, :tp2PnlAmount, :tp3PnlAmount, :highestTpPnlAmount, :slPnlAmount, :tpLevelsJson, :updatedAt) " +
            "ON CONFLICT(signal_id) DO UPDATE SET entry_hit_at=excluded.entry_hit_at, target_hit_at=excluded.target_hit_at, " +
            "stop_loss_hit_at=excluded.stop_loss_hit_at, events_json=excluded.events_json, result=excluded.result, detail=excluded.detail, " +
            "pnl_amount=excluded.pnl_amount, pnl_currency=excluded.pnl_currency, trades_count=excluded.trades_count, " +
            "legs_json=excluded.legs_json, max_tp_hit=excluded.max_tp_hit, all_targets_hit=excluded.all_targets_hit, " +
            "closed_by_sl_after_tp=excluded.closed_by_sl_after_tp, tp1_pnl_amount=excluded.tp1_pnl_amount, " +
            "tp2_pnl_amount=excluded.tp2_pnl_amount, tp3_pnl_amount=excluded.tp3_pnl_amount, " +
            "highest_tp_pnl_amount=excluded.highest_tp_pnl_amount, sl_pnl_amount=excluded.sl_pnl_amount, " +
            "tp_levels_json=excluded.tp_levels_json, updated_at=excluded.updated_at"
    )
    suspend fun upsertTradeResult(
        signalId: Long,
        entryHitAt: String?,
        targetHitAt: String?,
        stopLossHitAt: String?,
        eventsJson: String,
        result: String,
        detail: String?,
        pnlAmount: Double,
        pnlCurrency: String,
        tradesCount: Int,
        legsJson: String,
        maxTpHit: Int,
        allTargetsHit: Int,
        closedBySlAfterTp: Int,
        tp1PnlAmount: Double,
        tp2PnlAmount: Double,
        tp3PnlAmount: Double,
        highestTpPnlAmount: Double,
        slPnlAmount: Double,
        tpLevelsJson: String,
        updatedAt: String
    )
}
