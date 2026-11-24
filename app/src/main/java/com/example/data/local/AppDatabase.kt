package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.domain.model.SourceNotificationRow
import com.example.domain.parser.DefaultRulesEngine
import com.example.domain.parser.ParserConstants
import com.example.util.TimeUtils

@Database(
    entities = [
        SchemaVersionEntity::class,
        CategoryEntity::class,
        ChannelEntity::class,
        RawNotificationEntity::class,
        SignalEntity::class,
        IntegrationEntity::class,
        ApplicationSettingEntity::class,
        SynchronizationLogEntity::class,
        MarketCandleEntity::class,
        InstrumentEntity::class,
        InstrumentMappingEntity::class,
        ApiRequestLogEntity::class,
        SignalAnalysisEntity::class,
        TradeResultEntity::class,
        VerificationScheduleEntity::class,
        VerificationRunEntity::class,
        ParsingRunEntity::class,
        SystemLogEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun coreDao(): CoreDao
    abstract fun marketDao(): MarketDao
    abstract fun verificationDao(): VerificationDao

    suspend fun initializeDefaults() {
        val dao = coreDao()
        val now = TimeUtils.utcNowIso()
        val currentVersion = dao.getSchemaVersion()
        if (currentVersion == null) {
            dao.insertSchemaVersion(SchemaVersionEntity(3))
        } else if (currentVersion < 3) {
            if (dao.getSetting("verification_initial_wait_minutes") == "60") {
                dao.upsertSetting(ApplicationSettingEntity("verification_initial_wait_minutes", "15", now))
            }
            if (dao.getSetting("verification_repeat_minutes") == "30") {
                dao.upsertSetting(ApplicationSettingEntity("verification_repeat_minutes", "15", now))
            }
            dao.insertSchemaVersion(SchemaVersionEntity(3))
        }

        dao.deleteCategoryByName("Indian Options")
        dao.migrateRawNotificationCategory("Indian Options", "Index Options")
        dao.migrateChannelCategory("Indian Options", "Index Options")
        for (cat in SourceNotificationRow.DEFAULT_CATEGORIES) {
            dao.insertCategory(CategoryEntity(name = cat, enabled = 1))
        }

        val defaults = mapOf(
            "timezone" to "Asia/Kolkata",
            "sync_interval_seconds" to "30",
            "default_timeframe" to "5m",
            "entry_tolerance" to "0",
            "duplicate_window_minutes" to "10",
            "verification_initial_wait_minutes" to "15",
            "verification_repeat_minutes" to "15",
            "verification_fast_phase_minutes" to "60",
            "verification_extended_interval_minutes" to "60",
            "verification_timeframe" to "1m",
            "verification_expiry_hours" to "168",
            "automatic_verification_enabled" to "true",
            "verification_retry_minutes" to "15",
            "market_provider" to "provider_routing",
            "demo_mode" to "false",
            "google_sheets_auto_sync" to "true",
            "dedicated_processing_enabled" to "false",
            "lan_dashboard_enabled" to "false",
            "lan_dashboard_port" to "8080",
            "default_sl_rules" to DefaultRulesEngine.rulesToJson(ParserConstants.defaultSlSeed())
        )
        for ((k, v) in defaults) {
            dao.insertSettingIfMissing(ApplicationSettingEntity(k, v, now))
        }
        dao.upsertSetting(ApplicationSettingEntity("verification_scheduler_started_at", now, now))
        dao.insertSystemLog(SystemLogEntity(createdAt = now, severity = "INFO", source = "system", message = "Application database initialized"))
    }

    companion object {
        const val DB_NAME = "signals.sqlite3"

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE trade_results ADD COLUMN max_tp_hit INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE trade_results ADD COLUMN all_targets_hit INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE trade_results ADD COLUMN closed_by_sl_after_tp INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE trade_results ADD COLUMN tp1_pnl_amount REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE trade_results ADD COLUMN tp2_pnl_amount REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE trade_results ADD COLUMN tp3_pnl_amount REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE trade_results ADD COLUMN highest_tp_pnl_amount REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE trade_results ADD COLUMN sl_pnl_amount REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE trade_results ADD COLUMN tp_levels_json TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE api_request_log ADD COLUMN http_code INTEGER")
                db.execSQL("ALTER TABLE api_request_log ADD COLUMN error_category TEXT NOT NULL DEFAULT 'ok'")
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME
                )
                    .addMigrations(MIGRATION_2_3)
                    .fallbackToDestructiveMigration(false)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
