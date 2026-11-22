package com.example.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "schema_version")
data class SchemaVersionEntity(
    @PrimaryKey val version: Int
)

@Entity(
    tableName = "categories",
    indices = [Index(value = ["name"], unique = true)]
)
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val enabled: Int = 1
)

@Entity(
    tableName = "channels",
    indices = [Index(value = ["name"], unique = true)]
)
data class ChannelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val category: String? = null,
    val enabled: Int = 1
)

@Entity(
    tableName = "raw_notifications",
    indices = [
        Index(value = ["notification_id"], unique = true),
        Index(value = ["notification_time"], name = "idx_raw_time"),
        Index(value = ["channel"], name = "idx_raw_channel"),
        Index(value = ["is_duplicate", "duplicate_group_id"], name = "idx_raw_duplicate")
    ]
)
data class RawNotificationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "received_at") val receivedAt: String? = null,
    @ColumnInfo(name = "notification_time") val notificationTime: String? = null,
    @ColumnInfo(name = "source_app") val sourceApp: String? = null,
    @ColumnInfo(name = "package") val packageName: String? = null,
    val channel: String? = null,
    val category: String? = null,
    val title: String? = null,
    val message: String? = null,
    @ColumnInfo(name = "notification_id") val notificationId: String? = null,
    @ColumnInfo(name = "delivery_id") val deliveryId: String? = null,
    @ColumnInfo(name = "imported_at") val importedAt: String,
    @ColumnInfo(name = "is_duplicate") val isDuplicate: Int = 0,
    @ColumnInfo(name = "duplicate_of") val duplicateOf: Long? = null,
    @ColumnInfo(name = "duplicate_reason") val duplicateReason: String? = null,
    @ColumnInfo(name = "duplicate_group_id") val duplicateGroupId: String? = null
)

@Entity(
    tableName = "signals",
    foreignKeys = [
        ForeignKey(
            entity = RawNotificationEntity::class,
            parentColumns = ["id"],
            childColumns = ["raw_notification_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["verification_status"], name = "idx_signals_status"),
        Index(value = ["raw_notification_id"], name = "idx_signals_raw_id")
    ]
)
data class SignalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "raw_notification_id") val rawNotificationId: Long,
    val symbol: String? = null,
    val direction: String? = null,
    @ColumnInfo(name = "entry_price") val entryPrice: Double? = null,
    @ColumnInfo(name = "additional_entries") val additionalEntries: String = "[]",
    @ColumnInfo(name = "stop_loss") val stopLoss: Double? = null,
    val targets: String = "[]",
    @ColumnInfo(name = "option_type") val optionType: String? = null,
    @ColumnInfo(name = "strike_price") val strikePrice: Double? = null,
    @ColumnInfo(name = "expiry_date") val expiryDate: String? = null,
    @ColumnInfo(name = "entry_mode") val entryMode: String = "limit",
    @ColumnInfo(name = "parsing_status") val parsingStatus: String,
    @ColumnInfo(name = "parsing_confidence") val parsingConfidence: Double = 0.0,
    @ColumnInfo(name = "manual_review_status") val manualReviewStatus: String = "not_required",
    @ColumnInfo(name = "verification_status") val verificationStatus: String = "pending",
    @ColumnInfo(name = "final_result") val finalResult: String? = null,
    @ColumnInfo(name = "parsing_explanation") val parsingExplanation: String? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: String
)

/**
 * Stores non-secret integration configuration only.
 * All credentials/API keys/TOTP secrets are stored in Android Keystore-backed SecretStore.
 */
@Entity(
    tableName = "integrations",
    indices = [Index(value = ["provider"], unique = true)]
)
data class IntegrationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val provider: String,
    @ColumnInfo(name = "config_json") val configJson: String = "{}",
    val status: String = "not_configured",
    @ColumnInfo(name = "updated_at") val updatedAt: String
)

@Entity(tableName = "application_settings")
data class ApplicationSettingEntity(
    @PrimaryKey val key: String,
    val value: String,
    @ColumnInfo(name = "updated_at") val updatedAt: String
)

@Entity(tableName = "synchronization_logs")
data class SynchronizationLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "started_at") val startedAt: String,
    @ColumnInfo(name = "finished_at") val finishedAt: String? = null,
    val provider: String,
    val status: String,
    @ColumnInfo(name = "imported_rows") val importedRows: Int = 0,
    @ColumnInfo(name = "duplicate_rows") val duplicateRows: Int = 0,
    @ColumnInfo(name = "skipped_rows") val skippedRows: Int = 0,
    @ColumnInfo(name = "failed_rows") val failedRows: Int = 0,
    val detail: String? = null
)

@Entity(
    tableName = "market_candles",
    indices = [
        Index(
            value = ["provider", "symbol", "exchange", "interval", "candle_timestamp"],
            unique = true,
            name = "idx_candles_normalized"
        ),
        Index(
            value = ["provider", "symbol", "timeframe", "candle_time"],
            name = "idx_candles_lookup"
        )
    ]
)
data class MarketCandleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val provider: String,
    val symbol: String,
    val exchange: String = "",
    val interval: String = "1m",
    @ColumnInfo(name = "candle_timestamp") val candleTimestamp: String,
    val timeframe: String = "1m",
    @ColumnInfo(name = "candle_time") val candleTime: String,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double? = null,
    @ColumnInfo(name = "retrieved_at") val retrievedAt: String? = null
)

@Entity(
    tableName = "instruments",
    indices = [
        Index(value = ["instrument_token"], unique = true),
        Index(value = ["trading_symbol", "exchange_segment"], name = "idx_instruments_search")
    ]
)
data class InstrumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val symbol: String? = null,
    @ColumnInfo(name = "trading_symbol") val tradingSymbol: String,
    @ColumnInfo(name = "instrument_token") val instrumentToken: String,
    @ColumnInfo(name = "exchange_segment") val exchangeSegment: String,
    @ColumnInfo(name = "instrument_type") val instrumentType: String? = null,
    val expiry: String? = null,
    val strike: Double? = null,
    @ColumnInfo(name = "lot_size") val lotSize: Double? = null,
    @ColumnInfo(name = "tick_size") val tickSize: Double? = null,
    @ColumnInfo(name = "raw_json") val rawJson: String? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: String
)

@Entity(
    tableName = "instrument_mappings",
    indices = [Index(value = ["lookup_key"], unique = true)]
)
data class InstrumentMappingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "lookup_key") val lookupKey: String,
    @ColumnInfo(name = "instrument_token") val instrumentToken: String,
    @ColumnInfo(name = "exchange_segment") val exchangeSegment: String,
    val note: String? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: String
)

@Entity(
    tableName = "api_request_log",
    indices = [Index(value = ["provider", "requested_at"], name = "idx_api_usage")]
)
data class ApiRequestLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val provider: String,
    val endpoint: String,
    @ColumnInfo(name = "requested_at") val requestedAt: String,
    @ColumnInfo(name = "completed_at") val completedAt: String? = null,
    val status: String,
    @ColumnInfo(name = "credits_used") val creditsUsed: Double = 0.0,
    @ColumnInfo(name = "candles_returned") val candlesReturned: Int = 0,
    val error: String? = null,
    @ColumnInfo(name = "request_key") val requestKey: String? = null,
    @ColumnInfo(name = "http_code") val httpCode: Int? = null,
    @ColumnInfo(name = "error_category", defaultValue = "'ok'") val errorCategory: String = "ok"
)

@Entity(
    tableName = "signal_analysis",
    foreignKeys = [
        ForeignKey(
            entity = SignalEntity::class,
            parentColumns = ["id"],
            childColumns = ["signal_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["signal_id"])]
)
data class SignalAnalysisEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "signal_id") val signalId: Long,
    @ColumnInfo(name = "analyzed_at") val analyzedAt: String,
    val decision: String,
    val detail: String? = null,
    @ColumnInfo(name = "candle_time") val candleTime: String? = null
)

@Entity(
    tableName = "trade_results",
    foreignKeys = [
        ForeignKey(
            entity = SignalEntity::class,
            parentColumns = ["id"],
            childColumns = ["signal_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["signal_id"], unique = true)]
)
data class TradeResultEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "signal_id") val signalId: Long,
    @ColumnInfo(name = "entry_hit_at") val entryHitAt: String? = null,
    @ColumnInfo(name = "target_hit_at") val targetHitAt: String? = null,
    @ColumnInfo(name = "stop_loss_hit_at") val stopLossHitAt: String? = null,
    @ColumnInfo(name = "events_json") val eventsJson: String = "[]",
    val result: String,
    val detail: String? = null,
    @ColumnInfo(name = "pnl_amount") val pnlAmount: Double = 0.0,
    @ColumnInfo(name = "pnl_currency") val pnlCurrency: String = "INR",
    @ColumnInfo(name = "trades_count") val tradesCount: Int = 0,
    @ColumnInfo(name = "legs_json") val legsJson: String = "[]",
    @ColumnInfo(name = "max_tp_hit", defaultValue = "0") val maxTpHit: Int = 0,
    @ColumnInfo(name = "all_targets_hit", defaultValue = "0") val allTargetsHit: Int = 0,
    @ColumnInfo(name = "closed_by_sl_after_tp", defaultValue = "0") val closedBySlAfterTp: Int = 0,
    @ColumnInfo(name = "tp1_pnl_amount", defaultValue = "0.0") val tp1PnlAmount: Double = 0.0,
    @ColumnInfo(name = "tp2_pnl_amount", defaultValue = "0.0") val tp2PnlAmount: Double = 0.0,
    @ColumnInfo(name = "tp3_pnl_amount", defaultValue = "0.0") val tp3PnlAmount: Double = 0.0,
    @ColumnInfo(name = "highest_tp_pnl_amount", defaultValue = "0.0") val highestTpPnlAmount: Double = 0.0,
    @ColumnInfo(name = "sl_pnl_amount", defaultValue = "0.0") val slPnlAmount: Double = 0.0,
    @ColumnInfo(name = "tp_levels_json", defaultValue = "'[]'") val tpLevelsJson: String = "[]",
    @ColumnInfo(name = "updated_at") val updatedAt: String
)

@Entity(
    tableName = "verification_schedules",
    foreignKeys = [
        ForeignKey(
            entity = SignalEntity::class,
            parentColumns = ["id"],
            childColumns = ["signal_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["signal_id"], unique = true),
        Index(value = ["status", "next_scheduled_at"], name = "idx_verification_due")
    ]
)
data class VerificationScheduleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "signal_id") val signalId: Long,
    @ColumnInfo(name = "signal_start_at") val signalStartAt: String,
    @ColumnInfo(name = "initial_wait_minutes") val initialWaitMinutes: Int,
    @ColumnInfo(name = "interval_minutes") val intervalMinutes: Int,
    val timeframe: String,
    @ColumnInfo(name = "first_scheduled_at") val firstScheduledAt: String,
    @ColumnInfo(name = "next_scheduled_at") val nextScheduledAt: String? = null,
    @ColumnInfo(name = "last_verified_at") val lastVerifiedAt: String? = null,
    @ColumnInfo(name = "expires_at") val expiresAt: String? = null,
    val status: String = "scheduled",
    @ColumnInfo(name = "last_result") val lastResult: String? = null,
    @ColumnInfo(name = "locked_at") val lockedAt: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "updated_at") val updatedAt: String
)

@Entity(
    tableName = "verification_runs",
    foreignKeys = [
        ForeignKey(
            entity = SignalEntity::class,
            parentColumns = ["id"],
            childColumns = ["signal_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = VerificationScheduleEntity::class,
            parentColumns = ["id"],
            childColumns = ["schedule_id"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["signal_id", "started_at"], name = "idx_verification_runs"),
        Index(value = ["schedule_id"])
    ]
)
data class VerificationRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "signal_id") val signalId: Long,
    @ColumnInfo(name = "schedule_id") val scheduleId: Long? = null,
    @ColumnInfo(name = "run_type") val runType: String,
    @ColumnInfo(name = "started_at") val startedAt: String,
    @ColumnInfo(name = "finished_at") val finishedAt: String? = null,
    val status: String,
    val result: String? = null,
    val detail: String? = null,
    @ColumnInfo(name = "candle_time") val candleTime: String? = null
)

@Entity(
    tableName = "parsing_runs",
    foreignKeys = [
        ForeignKey(
            entity = SignalEntity::class,
            parentColumns = ["id"],
            childColumns = ["signal_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["signal_id", "parsed_at"], name = "idx_parsing_runs")]
)
data class ParsingRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "signal_id") val signalId: Long,
    @ColumnInfo(name = "run_type") val runType: String,
    @ColumnInfo(name = "parsed_at") val parsedAt: String,
    val status: String,
    val confidence: Double,
    val explanation: String? = null,
    @ColumnInfo(name = "snapshot_json") val snapshotJson: String
)

@Entity(tableName = "system_logs")
data class SystemLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "created_at") val createdAt: String,
    val severity: String,
    val source: String,
    val message: String
)
