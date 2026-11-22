package com.example.data.local

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.room.withTransaction
import com.example.util.TimeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class LegacyImportReport(
    val success: Boolean,
    val message: String,
    val rawNotificationsImported: Int = 0,
    val signalsImported: Int = 0,
    val candlesImported: Int = 0,
    val instrumentsImported: Int = 0,
    val integrationsMigrated: Int = 0,
    val providersNeedingSecretReentry: List<String> = emptyList(),
    val foreignKeyCheckPassed: Boolean = false
)

/**
 * Opt-in, rollback-safe importer for legacy Flask `signals.sqlite3` files.
 * Copies the user-picked file into app-private staging, validates schema/version,
 * imports inside a Room transaction preserving FK relationships via ID mapping,
 * migrates public integration configs only (never importing plaintext secrets into DB),
 * verifies row counts & FK integrity, and deletes the temporary copy.
 */
class LegacySqliteImporter(
    private val context: Context,
    private val db: AppDatabase
) {

    suspend fun importFromUri(uri: Uri): LegacyImportReport = withContext(Dispatchers.IO) {
        val stagingFile = File(context.cacheDir, "legacy_import_staging_${System.currentTimeMillis()}.sqlite3")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                stagingFile.outputStream().use { output -> input.copyTo(output) }
            } ?: return@withContext LegacyImportReport(false, "Could not open selected SQLite file.")

            importFromFile(stagingFile)
        } catch (e: Exception) {
            LegacyImportReport(false, "Legacy import failed: ${e.message?.take(300) ?: "unknown error"}")
        } finally {
            if (stagingFile.exists()) stagingFile.delete()
        }
    }

    suspend fun importFromFile(sourceFile: File): LegacyImportReport = withContext(Dispatchers.IO) {
        if (!sourceFile.exists() || sourceFile.length() < 100L) {
            return@withContext LegacyImportReport(false, "Selected file is missing or not a valid SQLite database.")
        }
        val legacy = try {
            SQLiteDatabase.openDatabase(sourceFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        } catch (e: Exception) {
            return@withContext LegacyImportReport(false, "Could not open SQLite database: ${e.message?.take(200)}")
        }

        try {
            val tables = mutableSetOf<String>()
            legacy.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { c ->
                while (c.moveToNext()) tables += c.getString(0)
            }
            val requiredTables = setOf("raw_notifications", "signals", "application_settings")
            val missing = requiredTables - tables
            if (missing.isNotEmpty()) {
                return@withContext LegacyImportReport(false, "Legacy database is missing required tables: ${missing.joinToString(", ")}")
            }

            var rawCount = 0
            var signalCount = 0
            var candleCount = 0
            var instrumentCount = 0
            var integrationCount = 0
            val secretProviders = mutableListOf<String>()

            db.withTransaction {
                val core = db.coreDao()
                val market = db.marketDao()
                val ver = db.verificationDao()
                val now = TimeUtils.utcNowIso()

                // 1. Categories & Channels
                if ("categories" in tables) {
                    legacy.rawQuery("SELECT name, enabled FROM categories", null).use { c ->
                        while (c.moveToNext()) {
                            core.insertCategory(CategoryEntity(name = c.getString(0), enabled = c.getInt(1)))
                        }
                    }
                }
                if ("channels" in tables) {
                    legacy.rawQuery("SELECT name, category, enabled FROM channels", null).use { c ->
                        while (c.moveToNext()) {
                            core.insertChannel(ChannelEntity(name = c.getString(0), category = c.optStr(1), enabled = c.getInt(2)))
                        }
                    }
                }

                // 2. Settings (preserve default_sl_rules and schedule settings)
                legacy.rawQuery("SELECT key, value, updated_at FROM application_settings", null).use { c ->
                    while (c.moveToNext()) {
                        val k = c.getString(0)
                        val v = c.getString(1)
                        val u = c.optStr(2) ?: now
                        core.upsertSetting(ApplicationSettingEntity(k, v, u))
                    }
                }

                // 3. Integrations (config_json ONLY — never copy plaintext secrets_json)
                if ("integrations" in tables) {
                    legacy.rawQuery("SELECT provider, config_json, secrets_json, status, updated_at FROM integrations", null).use { c ->
                        while (c.moveToNext()) {
                            val provider = c.getString(0)
                            val configJson = c.optStr(1) ?: "{}"
                            val legacySecrets = c.optStr(2) ?: "{}"
                            val hasSecrets = legacySecrets.trim().length > 2
                            if (hasSecrets) secretProviders += provider
                            core.upsertIntegration(
                                provider = provider,
                                configJson = configJson,
                                status = if (hasSecrets) "reenter_secrets" else (c.optStr(3) ?: "configured"),
                                updatedAt = c.optStr(4) ?: now
                            )
                            integrationCount++
                        }
                    }
                }

                // 4. Raw notifications (oldId -> newId mapping)
                val rawIdMap = mutableMapOf<Long, Long>()
                val rawCols = tableColumns(legacy, "raw_notifications")
                legacy.rawQuery("SELECT * FROM raw_notifications ORDER BY id ASC", null).use { c ->
                    while (c.moveToNext()) {
                        val oldId = c.getLong(c.getColumnIndexOrThrow("id"))
                        val notifId = c.optColStr("notification_id")?.takeIf { it.isNotBlank() }
                        val existingId = if (notifId != null) core.findRawIdByNotificationId(notifId) else null
                        if (existingId != null) {
                            rawIdMap[oldId] = existingId
                            continue
                        }
                        val dupOfOld = if ("duplicate_of" in rawCols) c.optColLong("duplicate_of") else null
                        val newId = core.insertRawNotification(
                            RawNotificationEntity(
                                receivedAt = c.optColStr("received_at"),
                                notificationTime = c.optColStr("notification_time"),
                                sourceApp = c.optColStr("source_app"),
                                packageName = c.optColStr("package"),
                                channel = c.optColStr("channel"),
                                category = c.optColStr("category"),
                                title = c.optColStr("title"),
                                message = c.optColStr("message"),
                                notificationId = notifId,
                                deliveryId = c.optColStr("delivery_id"),
                                importedAt = c.optColStr("imported_at") ?: now,
                                isDuplicate = if ("is_duplicate" in rawCols) c.optColInt("is_duplicate", 0) else 0,
                                duplicateOf = dupOfOld?.let { rawIdMap[it] },
                                duplicateReason = if ("duplicate_reason" in rawCols) c.optColStr("duplicate_reason") else null,
                                duplicateGroupId = if ("duplicate_group_id" in rawCols) c.optColStr("duplicate_group_id") else null
                            )
                        )
                        rawIdMap[oldId] = newId
                        rawCount++
                    }
                }

                // 5. Signals (oldSignalId -> newSignalId mapping)
                val signalIdMap = mutableMapOf<Long, Long>()
                val sigCols = tableColumns(legacy, "signals")
                legacy.rawQuery("SELECT * FROM signals ORDER BY id ASC", null).use { c ->
                    while (c.moveToNext()) {
                        val oldId = c.getLong(c.getColumnIndexOrThrow("id"))
                        val oldRawId = c.getLong(c.getColumnIndexOrThrow("raw_notification_id"))
                        val newRawId = rawIdMap[oldRawId] ?: continue
                        val newId = core.insertSignal(
                            SignalEntity(
                                rawNotificationId = newRawId,
                                symbol = c.optColStr("symbol"),
                                direction = c.optColStr("direction"),
                                entryPrice = c.optColDouble("entry_price"),
                                additionalEntries = c.optColStr("additional_entries") ?: "[]",
                                stopLoss = c.optColDouble("stop_loss"),
                                targets = c.optColStr("targets") ?: "[]",
                                optionType = c.optColStr("option_type"),
                                strikePrice = c.optColDouble("strike_price"),
                                expiryDate = c.optColStr("expiry_date"),
                                entryMode = if ("entry_mode" in sigCols) (c.optColStr("entry_mode") ?: "limit") else "limit",
                                parsingStatus = c.optColStr("parsing_status") ?: "needs_review",
                                parsingConfidence = c.optColDouble("parsing_confidence") ?: 0.0,
                                manualReviewStatus = c.optColStr("manual_review_status") ?: "not_required",
                                verificationStatus = c.optColStr("verification_status") ?: "pending",
                                finalResult = c.optColStr("final_result"),
                                parsingExplanation = c.optColStr("parsing_explanation"),
                                updatedAt = c.optColStr("updated_at") ?: now
                            )
                        )
                        signalIdMap[oldId] = newId
                        signalCount++
                    }
                }

                // 6. Trade Results
                if ("trade_results" in tables) {
                    val trCols = tableColumns(legacy, "trade_results")
                    legacy.rawQuery("SELECT * FROM trade_results", null).use { c ->
                        while (c.moveToNext()) {
                            val newSigId = signalIdMap[c.getLong(c.getColumnIndexOrThrow("signal_id"))] ?: continue
                            ver.upsertTradeResult(
                                signalId = newSigId,
                                entryHitAt = c.optColStr("entry_hit_at"),
                                targetHitAt = c.optColStr("target_hit_at"),
                                stopLossHitAt = c.optColStr("stop_loss_hit_at"),
                                eventsJson = if ("events_json" in trCols) (c.optColStr("events_json") ?: "[]") else "[]",
                                result = c.optColStr("result") ?: "pending",
                                detail = c.optColStr("detail"),
                                pnlAmount = if ("pnl_amount" in trCols) (c.optColDouble("pnl_amount") ?: 0.0) else 0.0,
                                pnlCurrency = if ("pnl_currency" in trCols) (c.optColStr("pnl_currency") ?: "INR") else "INR",
                                tradesCount = if ("trades_count" in trCols) c.optColInt("trades_count", 0) else 0,
                                legsJson = if ("legs_json" in trCols) (c.optColStr("legs_json") ?: "[]") else "[]",
                                maxTpHit = if ("max_tp_hit" in trCols) c.optColInt("max_tp_hit", 0) else 0,
                                allTargetsHit = if ("all_targets_hit" in trCols) c.optColInt("all_targets_hit", 0) else 0,
                                closedBySlAfterTp = if ("closed_by_sl_after_tp" in trCols) c.optColInt("closed_by_sl_after_tp", 0) else 0,
                                tp1PnlAmount = if ("tp1_pnl_amount" in trCols) (c.optColDouble("tp1_pnl_amount") ?: 0.0) else 0.0,
                                tp2PnlAmount = if ("tp2_pnl_amount" in trCols) (c.optColDouble("tp2_pnl_amount") ?: 0.0) else 0.0,
                                tp3PnlAmount = if ("tp3_pnl_amount" in trCols) (c.optColDouble("tp3_pnl_amount") ?: 0.0) else 0.0,
                                highestTpPnlAmount = if ("highest_tp_pnl_amount" in trCols) (c.optColDouble("highest_tp_pnl_amount") ?: 0.0) else 0.0,
                                slPnlAmount = if ("sl_pnl_amount" in trCols) (c.optColDouble("sl_pnl_amount") ?: 0.0) else 0.0,
                                tpLevelsJson = if ("tp_levels_json" in trCols) (c.optColStr("tp_levels_json") ?: "[]") else "[]",
                                updatedAt = c.optColStr("updated_at") ?: now
                            )
                        }
                    }
                }

                // 7. Verification Schedules (never retroactively auto-queue historical rows — import as manual_required if scheduled)
                val schedIdMap = mutableMapOf<Long, Long>()
                if ("verification_schedules" in tables) {
                    legacy.rawQuery("SELECT * FROM verification_schedules", null).use { c ->
                        while (c.moveToNext()) {
                            val oldSchedId = c.getLong(c.getColumnIndexOrThrow("id"))
                            val newSigId = signalIdMap[c.getLong(c.getColumnIndexOrThrow("signal_id"))] ?: continue
                            val rawStatus = c.optColStr("status") ?: "manual_required"
                            val safeStatus = if (rawStatus == "scheduled" || rawStatus == "processing") "manual_required" else rawStatus
                            val newSchedId = ver.insertSchedule(
                                VerificationScheduleEntity(
                                    signalId = newSigId,
                                    signalStartAt = c.optColStr("signal_start_at") ?: now,
                                    initialWaitMinutes = c.optColInt("initial_wait_minutes", 15),
                                    intervalMinutes = c.optColInt("interval_minutes", 15),
                                    timeframe = c.optColStr("timeframe") ?: "1m",
                                    firstScheduledAt = c.optColStr("first_scheduled_at") ?: now,
                                    nextScheduledAt = if (safeStatus == "scheduled") c.optColStr("next_scheduled_at") else null,
                                    lastVerifiedAt = c.optColStr("last_verified_at"),
                                    expiresAt = c.optColStr("expires_at"),
                                    status = safeStatus,
                                    lastResult = c.optColStr("last_result"),
                                    lockedAt = null,
                                    createdAt = c.optColStr("created_at") ?: now,
                                    updatedAt = c.optColStr("updated_at") ?: now
                                )
                            )
                            if (newSchedId > 0) schedIdMap[oldSchedId] = newSchedId
                        }
                    }
                }

                // 8. Signal Analysis, Parsing Runs, Verification Runs
                if ("signal_analysis" in tables) {
                    legacy.rawQuery("SELECT * FROM signal_analysis", null).use { c ->
                        while (c.moveToNext()) {
                            val newSigId = signalIdMap[c.getLong(c.getColumnIndexOrThrow("signal_id"))] ?: continue
                            ver.insertSignalAnalysis(
                                SignalAnalysisEntity(
                                    signalId = newSigId,
                                    analyzedAt = c.optColStr("analyzed_at") ?: now,
                                    decision = c.optColStr("decision") ?: "pending",
                                    detail = c.optColStr("detail"),
                                    candleTime = c.optColStr("candle_time")
                                )
                            )
                        }
                    }
                }
                if ("parsing_runs" in tables) {
                    legacy.rawQuery("SELECT * FROM parsing_runs", null).use { c ->
                        while (c.moveToNext()) {
                            val newSigId = signalIdMap[c.getLong(c.getColumnIndexOrThrow("signal_id"))] ?: continue
                            core.insertParsingRun(
                                ParsingRunEntity(
                                    signalId = newSigId,
                                    runType = c.optColStr("run_type") ?: "legacy",
                                    parsedAt = c.optColStr("parsed_at") ?: now,
                                    status = c.optColStr("status") ?: "parsed",
                                    confidence = c.optColDouble("confidence") ?: 0.0,
                                    explanation = c.optColStr("explanation"),
                                    snapshotJson = c.optColStr("snapshot_json") ?: "{}"
                                )
                            )
                        }
                    }
                }
                if ("verification_runs" in tables) {
                    legacy.rawQuery("SELECT * FROM verification_runs", null).use { c ->
                        while (c.moveToNext()) {
                            val newSigId = signalIdMap[c.getLong(c.getColumnIndexOrThrow("signal_id"))] ?: continue
                            val oldSched = c.optColLong("schedule_id")
                            ver.insertVerificationRun(
                                VerificationRunEntity(
                                    signalId = newSigId,
                                    scheduleId = oldSched?.let { schedIdMap[it] },
                                    runType = c.optColStr("run_type") ?: "legacy",
                                    startedAt = c.optColStr("started_at") ?: now,
                                    finishedAt = c.optColStr("finished_at"),
                                    status = c.optColStr("status") ?: "complete",
                                    result = c.optColStr("result"),
                                    detail = c.optColStr("detail"),
                                    candleTime = c.optColStr("candle_time")
                                )
                            )
                        }
                    }
                }

                // 9. Instruments & Mappings
                if ("instruments" in tables) {
                    legacy.rawQuery("SELECT * FROM instruments", null).use { c ->
                        while (c.moveToNext()) {
                            val token = c.optColStr("instrument_token") ?: continue
                            market.upsertInstrument(
                                symbol = c.optColStr("symbol"),
                                tradingSymbol = c.optColStr("trading_symbol") ?: "",
                                token = token,
                                exchange = c.optColStr("exchange_segment") ?: "",
                                instType = c.optColStr("instrument_type"),
                                expiry = c.optColStr("expiry"),
                                strike = c.optColDouble("strike") ?: 0.0,
                                lotSize = c.optColDouble("lot_size") ?: 0.0,
                                tickSize = c.optColDouble("tick_size") ?: 0.0,
                                rawJson = c.optColStr("raw_json"),
                                updatedAt = c.optColStr("updated_at") ?: now
                            )
                            instrumentCount++
                        }
                    }
                }
                if ("instrument_mappings" in tables) {
                    legacy.rawQuery("SELECT * FROM instrument_mappings", null).use { c ->
                        while (c.moveToNext()) {
                            val key = c.optColStr("lookup_key") ?: continue
                            val tok = c.optColStr("instrument_token") ?: continue
                            val exch = c.optColStr("exchange_segment") ?: continue
                            market.upsertInstrumentMapping(key, tok, exch, c.optColStr("note"), c.optColStr("updated_at") ?: now)
                        }
                    }
                }

                // 10. Market Candles (batched)
                if ("market_candles" in tables) {
                    val mcCols = tableColumns(legacy, "market_candles")
                    val batch = mutableListOf<MarketCandleEntity>()
                    legacy.rawQuery("SELECT * FROM market_candles", null).use { c ->
                        while (c.moveToNext()) {
                            val tf = c.optColStr("timeframe") ?: "1m"
                            val ct = c.optColStr("candle_time") ?: continue
                            val interval = if ("interval" in mcCols) (c.optColStr("interval")?.ifBlank { tf } ?: tf) else tf
                            val ts = if ("candle_timestamp" in mcCols) (c.optColStr("candle_timestamp")?.ifBlank { ct } ?: ct) else ct
                            batch += MarketCandleEntity(
                                provider = c.optColStr("provider") ?: continue,
                                symbol = c.optColStr("symbol") ?: continue,
                                exchange = if ("exchange" in mcCols) (c.optColStr("exchange") ?: "") else "",
                                interval = interval,
                                candleTimestamp = ts,
                                timeframe = tf,
                                candleTime = ct,
                                open = c.optColDouble("open") ?: continue,
                                high = c.optColDouble("high") ?: continue,
                                low = c.optColDouble("low") ?: continue,
                                close = c.optColDouble("close") ?: continue,
                                volume = c.optColDouble("volume"),
                                retrievedAt = if ("retrieved_at" in mcCols) c.optColStr("retrieved_at") else now
                            )
                            if (batch.size >= 500) {
                                candleCount += market.insertCandlesIgnore(batch).count { it > 0 }
                                batch.clear()
                            }
                        }
                    }
                    if (batch.isNotEmpty()) {
                        candleCount += market.insertCandlesIgnore(batch).count { it > 0 }
                    }
                }

                // 11. Sync & System logs
                if ("synchronization_logs" in tables) {
                    legacy.rawQuery("SELECT * FROM synchronization_logs ORDER BY id DESC LIMIT 100", null).use { c ->
                        while (c.moveToNext()) {
                            core.insertSyncLog(
                                SynchronizationLogEntity(
                                    startedAt = c.optColStr("started_at") ?: now,
                                    finishedAt = c.optColStr("finished_at"),
                                    provider = c.optColStr("provider") ?: "google_sheets",
                                    status = c.optColStr("status") ?: "success",
                                    importedRows = c.optColInt("imported_rows", 0),
                                    duplicateRows = c.optColInt("duplicate_rows", 0),
                                    skippedRows = c.optColInt("skipped_rows", 0),
                                    failedRows = c.optColInt("failed_rows", 0),
                                    detail = c.optColStr("detail")
                                )
                            )
                        }
                    }
                }
                if ("system_logs" in tables) {
                    legacy.rawQuery("SELECT * FROM system_logs ORDER BY id DESC LIMIT 200", null).use { c ->
                        while (c.moveToNext()) {
                            core.insertSystemLog(
                                SystemLogEntity(
                                    createdAt = c.optColStr("created_at") ?: now,
                                    severity = c.optColStr("severity") ?: "INFO",
                                    source = c.optColStr("source") ?: "legacy",
                                    message = (c.optColStr("message") ?: "").take(1000)
                                )
                            )
                        }
                    }
                }

                core.insertSystemLog(
                    SystemLogEntity(
                        createdAt = now,
                        severity = "INFO",
                        source = "import",
                        message = "Imported legacy SQLite: $rawCount raw notifications, $signalCount signals, $candleCount candles, $instrumentCount instruments."
                    )
                )
            }

            // Verify FK integrity on Room DB
            val fkOk = db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { c -> c.count == 0 }

            LegacyImportReport(
                success = true,
                message = buildString {
                    append("Imported $rawCount notifications, $signalCount signals, $candleCount candles, $instrumentCount instruments.")
                    if (secretProviders.isNotEmpty()) {
                        append(" Please re-enter API keys/secrets in Integrations for: ${secretProviders.joinToString(", ")}.")
                    }
                },
                rawNotificationsImported = rawCount,
                signalsImported = signalCount,
                candlesImported = candleCount,
                instrumentsImported = instrumentCount,
                integrationsMigrated = integrationCount,
                providersNeedingSecretReentry = secretProviders,
                foreignKeyCheckPassed = fkOk
            )
        } finally {
            legacy.close()
        }
    }

    private fun tableColumns(db: SQLiteDatabase, table: String): Set<String> {
        val cols = mutableSetOf<String>()
        db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            while (c.moveToNext()) cols += c.getString(1)
        }
        return cols
    }

    private fun Cursor.optStr(idx: Int): String? = if (idx < 0 || isNull(idx)) null else getString(idx)
    private fun Cursor.optColStr(name: String): String? = getColumnIndex(name).let { if (it < 0 || isNull(it)) null else getString(it) }
    private fun Cursor.optColLong(name: String): Long? = getColumnIndex(name).let { if (it < 0 || isNull(it)) null else getLong(it) }
    private fun Cursor.optColInt(name: String, def: Int): Int = getColumnIndex(name).let { if (it < 0 || isNull(it)) def else getInt(it) }
    private fun Cursor.optColDouble(name: String): Double? = getColumnIndex(name).let { if (it < 0 || isNull(it)) null else getDouble(it) }
}
