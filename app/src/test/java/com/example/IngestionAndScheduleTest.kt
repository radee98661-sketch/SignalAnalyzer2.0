package com.example

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.InMemorySecretStore
import com.example.data.local.LegacySqliteImporter
import com.example.data.local.VerificationScheduleEntity
import com.example.data.provider.MarketDataProviders
import com.example.data.repository.EventBus
import com.example.data.repository.SignalIngestionService
import com.example.data.repository.VerificationService
import com.example.domain.model.Candle
import com.example.domain.model.SourceNotificationRow
import com.example.util.TimeUtils
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class IngestionAndScheduleTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var secretStore: InMemorySecretStore
    private lateinit var eventBus: EventBus
    private lateinit var ingestion: SignalIngestionService

    private val stubProviders = object : MarketDataProviders {
        override suspend fun fetchBinanceCandles(symbol: String, interval: String, start: Instant, end: Instant, config: Map<String, Any?>) = emptyList<Candle>()
        override suspend fun fetchTwelveDataCandles(symbol: String, interval: String, start: Instant?, end: Instant?, config: Map<String, Any?>, apiKey: String) = emptyList<Candle>() to 0.0
        override suspend fun authenticateAngelOne(secrets: Map<String, String>, forceRefresh: Boolean) = "jwt" to TimeUtils.utcNowIso()
        override suspend fun searchAngelScrip(jwtToken: String, apiKey: String, exchange: String, searchSymbol: String) = emptyList<JSONObject>()
        override suspend fun fetchAngelCandles(jwtToken: String, apiKey: String, exchange: String, symbolToken: String, interval: String, startIstFormatted: String, endIstFormatted: String) = emptyList<Candle>()
        override suspend fun downloadAngelScripMaster() = JSONArray()
    }

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        db.initializeDefaults()
        secretStore = InMemorySecretStore()
        eventBus = EventBus()
        ingestion = SignalIngestionService(db, eventBus)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `rejects duplicate notification IDs groups same-channel duplicates and flags signal updates`() = runBlocking {
        val now = Instant.now()
        val row1 = SourceNotificationRow(
            receivedAt = now.toString(),
            notificationTime = now.toString(),
            channel = "CryptoVIP",
            category = "Crypto",
            message = "#BTC/USDT LONG\nEntry: 65000\nTP: 67000\nSL: 64000",
            notificationId = "notif-100"
        )
        val (id1, st1) = ingestion.ingest(row1)
        assertNotNull(id1)
        assertEquals("imported", st1)

        // Exact same Notification ID -> rejected as duplicate
        val (idDup, stDup) = ingestion.ingest(row1)
        assertNull(idDup)
        assertEquals("duplicate", stDup)

        // Different Notification ID, same normalized message within 10m window -> imported and marked is_duplicate=1
        val row2 = row1.copy(
            notificationTime = now.plusSeconds(60).toString(),
            notificationId = "notif-101"
        )
        val (id2, st2) = ingestion.ingest(row2)
        assertEquals("imported", st2)
        val raw2 = db.coreDao().getRawNotificationById(id2!!)!!
        assertEquals(1, raw2.isDuplicate)
        assertEquals(id1, raw2.duplicateOf)
        assertEquals(SignalIngestionService.DUPLICATE_REASON_EXACT, raw2.duplicateReason)

        // Richer follow-up message on same symbol/direction/channel -> classified as signal_update (NOT marked isDuplicate=1)
        val row3 = row1.copy(
            notificationTime = now.plusSeconds(120).toString(),
            message = "BTCUSDT LONG update Entry: 65000 TP1: 67000 TP2: 68500 SL: 64000 10x",
            notificationId = "notif-102"
        )
        val (id3, st3) = ingestion.ingest(row3)
        assertEquals("imported", st3)
        val raw3 = db.coreDao().getRawNotificationById(id3!!)!!
        assertEquals(0, raw3.isDuplicate)
        assertEquals(SignalIngestionService.DUPLICATE_REASON_UPDATE, raw3.duplicateReason)
    }

    @Test
    fun `inherits recent Forex symbol from same channel within 30 minutes`() = runBlocking {
        val now = Instant.now()
        val first = SourceNotificationRow(
            receivedAt = now.minusSeconds(300).toString(),
            notificationTime = now.minusSeconds(300).toString(),
            channel = "GoldSignals",
            category = "Forex",
            message = "XAUUSD BUY 3010\nTP: 3020\nSL: 3002",
            notificationId = "fx-1"
        )
        ingestion.ingest(first)

        // Follow-up omits XAUUSD symbol
        val followUp = SourceNotificationRow(
            receivedAt = now.toString(),
            notificationTime = now.toString(),
            channel = "GoldSignals",
            category = "Forex",
            message = "BUY 3015\nTP: 3028\nSL: 3008",
            notificationId = "fx-2"
        )
        val (rawId2, _) = ingestion.ingest(followUp)
        val signals = db.coreDao().getSignalsByRawId(rawId2!!)
        assertEquals(1, signals.size)
        assertEquals("XAUUSD", signals[0].symbol)
        assertEquals("parsed", signals[0].parsingStatus)
        assertEquals(0.85, signals[0].parsingConfidence, 0.001)
        assertTrue(signals[0].parsingExplanation.orEmpty().contains("Inherited symbol XAUUSD"))
    }

    @Test
    fun `schedules all signals automatically regardless of age recovers stale leases and promotes legacy manual_required`() = runBlocking {
        val now = Instant.now()
        val freshRow = SourceNotificationRow(
            receivedAt = now.toString(),
            notificationTime = now.toString(),
            channel = "LiveDesk",
            category = "Crypto",
            message = "#ETH/USDT LONG\nEntry: 3200\nTP: 3300\nSL: 3150",
            notificationId = "live-1"
        )
        val (freshRawId, _) = ingestion.ingest(freshRow)
        val freshSigId = db.coreDao().getSignalsByRawId(freshRawId!!).first().id
        val freshSched = db.verificationDao().getScheduleForSignal(freshSigId)!!
        assertEquals("scheduled", freshSched.status)
        // Fresh signal waits 15 minutes before first candle check
        val freshFirst = TimeUtils.parseUtcInstant(freshSched.firstScheduledAt)!!
        val freshNext = TimeUtils.parseUtcInstant(freshSched.nextScheduledAt)!!
        assertEquals(freshFirst, freshNext)
        assertTrue(Duration.between(now, freshNext).toMinutes() in 14..15)

        // Older imported signal (2h old) also enters automatic scheduled monitoring with immediate catch-up
        val oldTime = now.minus(Duration.ofHours(2))
        val oldRow = freshRow.copy(
            receivedAt = oldTime.toString(),
            notificationTime = oldTime.toString(),
            notificationId = "old-1"
        )
        val (oldRawId, _) = ingestion.ingest(oldRow)
        val oldSigId = db.coreDao().getSignalsByRawId(oldRawId!!).first().id
        val oldSched = db.verificationDao().getScheduleForSignal(oldSigId)!!
        assertEquals("scheduled", oldSched.status)
        assertTrue(!TimeUtils.parseUtcInstant(oldSched.nextScheduledAt)!!.isAfter(Instant.now().plusSeconds(5)))

        // Simulate a stale crashed "processing" lease AND a legacy "manual_required" schedule
        val staleLockedAt = now.minus(Duration.ofMinutes(10)).toString()
        val verService = VerificationService(db, secretStore, stubProviders, eventBus)
        assertEquals(1, db.verificationDao().claimSchedule(freshSched.id, staleLockedAt))
        db.verificationDao().completeScheduleCycle(
            scheduleId = oldSched.id,
            status = "manual_required",
            verifiedAt = now.toString(),
            nextAt = null,
            lastResult = null,
            updatedAt = now.toString()
        )

        verService.reconcileOnStartup()
        val recovered = db.verificationDao().getScheduleForSignal(freshSigId)!!
        assertEquals("scheduled", recovered.status)
        assertNull(recovered.lockedAt)

        val promoted = db.verificationDao().getScheduleForSignal(oldSigId)!!
        assertEquals("scheduled", promoted.status)
        assertNotNull(promoted.nextScheduledAt)
    }

    @Test
    fun `adapts verification cadence from 15m in first hour to 60m after first hour and auto-stops on SL after TP1`() = runBlocking {
        val now = Instant.now()
        // Signal 1: 25m old (inside 60m fast phase) -> still active after check -> rescheduled +15m
        val t1Start = now.minus(Duration.ofMinutes(25))
        val (raw1, _) = ingestion.ingest(
            SourceNotificationRow(
                receivedAt = t1Start.toString(),
                notificationTime = t1Start.toString(),
                channel = "FastDesk",
                category = "Crypto",
                message = "#BTC/USDT LONG\nEntry: 60000\nTP1: 61000\nTP2: 62000\nSL: 59000",
                notificationId = "phase-fast-1"
            )
        )
        val sig1 = db.coreDao().getSignalsByRawId(raw1!!).first().id

        // Signal 2: 75m old (past 60m fast phase) -> still active after check -> rescheduled +60m
        val t2Start = now.minus(Duration.ofMinutes(75))
        val (raw2, _) = ingestion.ingest(
            SourceNotificationRow(
                receivedAt = t2Start.toString(),
                notificationTime = t2Start.toString(),
                channel = "HourlyDesk",
                category = "Crypto",
                message = "#ETH/USDT LONG\nEntry: 3000\nTP1: 3100\nTP2: 3200\nSL: 2900",
                notificationId = "phase-hourly-1"
            )
        )
        val sig2 = db.coreDao().getSignalsByRawId(raw2!!).first().id

        // Signal 3: hits TP1 then reverses to SL -> remains target_hit & monitoring auto-stops (completed)
        val t3Start = now.minus(Duration.ofMinutes(30))
        val (raw3, _) = ingestion.ingest(
            SourceNotificationRow(
                receivedAt = t3Start.toString(),
                notificationTime = t3Start.toString(),
                channel = "FastDesk",
                category = "Crypto",
                message = "#SOL/USDT LONG\nEntry: 150\nTP1: 160\nTP2: 170\nSL: 140",
                notificationId = "tp-then-sl-1"
            )
        )
        val sig3 = db.coreDao().getSignalsByRawId(raw3!!).first().id

        val dynamicProviders = object : MarketDataProviders by stubProviders {
            override suspend fun fetchBinanceCandles(
                symbol: String,
                interval: String,
                start: Instant,
                end: Instant,
                config: Map<String, Any?>
            ): List<Candle> {
                val base = TimeUtils.truncateToMinute(start)
                return when (symbol) {
                    "BTCUSDT" -> listOf(
                        Candle("binance", "BTCUSDT", "USD-M", "1m", base.toString(), 60000.0, 60500.0, 59800.0, 60200.0)
                    )
                    "ETHUSDT" -> listOf(
                        Candle("binance", "ETHUSDT", "USD-M", "1m", base.toString(), 3000.0, 3050.0, 2980.0, 3020.0)
                    )
                    "SOLUSDT" -> listOf(
                        Candle("binance", "SOLUSDT", "USD-M", "1m", base.toString(), 150.0, 152.0, 149.0, 151.0),
                        Candle("binance", "SOLUSDT", "USD-M", "1m", base.plusSeconds(60).toString(), 151.0, 162.0, 150.5, 161.0),
                        Candle("binance", "SOLUSDT", "USD-M", "1m", base.plusSeconds(120).toString(), 161.0, 161.5, 138.0, 139.0)
                    )
                    else -> emptyList()
                }
            }
        }

        val verService = VerificationService(db, secretStore, dynamicProviders, eventBus)
        val processed = verService.runDueSchedules()
        assertEquals(3, processed)

        val sched1 = db.verificationDao().getScheduleForSignal(sig1)!!
        assertEquals("scheduled", sched1.status)
        assertTrue(Duration.between(Instant.now(), TimeUtils.parseUtcInstant(sched1.nextScheduledAt)!!).toMinutes() in 13..15)

        val sched2 = db.verificationDao().getScheduleForSignal(sig2)!!
        assertEquals("scheduled", sched2.status)
        assertTrue(Duration.between(Instant.now(), TimeUtils.parseUtcInstant(sched2.nextScheduledAt)!!).toMinutes() in 58..60)

        val sched3 = db.verificationDao().getScheduleForSignal(sig3)!!
        assertEquals("completed", sched3.status)
        assertNull(sched3.nextScheduledAt)
        val row3 = db.coreDao().getSignalJoinedById(sig3)!!
        assertEquals("target_hit", row3.verificationStatus)
        assertEquals(1, row3.closedBySlAfterTp)
        assertEquals(1, row3.maxTpHit)
        assertEquals(0, row3.allTargetsHit)
    }

    @Test
    fun `stores per-level TP1 TP2 TP3 and SL PnL and computes filtered PnL summary and channel analytics by basis`() = runBlocking {
        val now = Instant.now().minus(Duration.ofMinutes(20))
        // Signal A (ChanAlpha): hits TP1, TP2, TP3. Entry 100, TP1 110, TP2 120, TP3 130, SL 90 (Crypto default: $100 notional -> qty=1)
        val (rawA, _) = ingestion.ingest(
            SourceNotificationRow(
                receivedAt = now.toString(),
                notificationTime = now.toString(),
                channel = "ChanAlpha",
                category = "Crypto",
                message = "#AVAX/USDT LONG\nEntry: 100\nTP1: 110\nTP2: 120\nTP3: 130\nSL: 90",
                notificationId = "pnl-a"
            )
        )
        val sigA = db.coreDao().getSignalsByRawId(rawA!!).first().id

        // Signal B (ChanAlpha): hits SL only. Entry 100, TP1 110, SL 90 -> SL loss -$10
        val (rawB, _) = ingestion.ingest(
            SourceNotificationRow(
                receivedAt = now.toString(),
                notificationTime = now.toString(),
                channel = "ChanAlpha",
                category = "Crypto",
                message = "#LINK/USDT LONG\nEntry: 100\nTP1: 110\nSL: 90",
                notificationId = "pnl-b"
            )
        )
        val sigB = db.coreDao().getSignalsByRawId(rawB!!).first().id

        val verService = VerificationService(db, secretStore, stubProviders, eventBus)
        val t0 = TimeUtils.truncateToMinute(now)
        verService.storeCandles(
            listOf(
                Candle("binance", "AVAXUSDT", "USD-M", "1m", t0.toString(), 100.0, 102.0, 99.0, 101.0),
                Candle("binance", "AVAXUSDT", "USD-M", "1m", t0.plusSeconds(60).toString(), 101.0, 132.0, 100.5, 131.0),
                Candle("binance", "LINKUSDT", "USD-M", "1m", t0.toString(), 100.0, 101.0, 99.5, 100.0),
                Candle("binance", "LINKUSDT", "USD-M", "1m", t0.plusSeconds(60).toString(), 100.0, 100.5, 88.0, 89.0)
            )
        )
        verService.processSignal(sigA, "manual")
        verService.processSignal(sigB, "manual")

        val rowA = db.coreDao().getSignalJoinedById(sigA)!!
        assertEquals(10.0, rowA.tp1PnlAmount!!, 0.01)
        assertEquals(20.0, rowA.tp2PnlAmount!!, 0.01)
        assertEquals(30.0, rowA.tp3PnlAmount!!, 0.01)
        assertEquals(-10.0, rowA.slPnlAmount!!, 0.01)
        // Default realized pnl_amount focuses on TP1
        assertEquals(10.0, rowA.pnlAmount!!, 0.01)
        assertEquals(3, rowA.maxTpHit)
        assertEquals(1, rowA.allTargetsHit)

        val syncClient = com.example.data.provider.GoogleSheetsSyncClient(db, secretStore, ingestion, eventBus)
        val repo = com.example.data.repository.SignalRepository(context, db, secretStore, stubProviders, ingestion, verService, syncClient, eventBus)

        // Filter all signals on TP1 basis: +10 (SigA) - 10 (SigB) = 0.0
        val allSignals = repo.getSignals(channel = "ChanAlpha")
        val sumTp1 = repo.computeFilteredPnlSummary(allSignals, "tp1")
        assertEquals(0.0, sumTp1.pnlUsd, 0.01)
        assertEquals(1, sumTp1.tp1Hits)
        assertEquals(1, sumTp1.tp3Hits)
        assertEquals(1, sumTp1.slOnlyHits)

        // Switch basis to TP3: +30 (SigA) - 10 (SigB) = +20.0
        val sumTp3 = repo.computeFilteredPnlSummary(allSignals, "tp3")
        assertEquals(20.0, sumTp3.pnlUsd, 0.01)

        // Filter by tpFilter = "tp3_plus": only SigA (+30 on TP3 basis)
        val tp3Only = repo.getSignals(channel = "ChanAlpha", tpFilter = "tp3_plus")
        assertEquals(1, tp3Only.size)
        assertEquals("AVAXUSDT", tp3Only.first().row.symbol)

        // Channel analytics on TP3 basis
        val analytics = repo.getFilteredChannelAnalytics(category = "Crypto", channel = "ChanAlpha", pnlBasis = "tp3")
        assertEquals(1, analytics.size)
        assertEquals(1, analytics.first().tp3Hits)
        assertEquals(1, analytics.first().allTpHits)
        assertEquals(20.0, analytics.first().pnlUsd, 0.01)
    }

    @Test
    fun `computes INR PnL using Angel master lot size when symbol lot override is absent`() = runBlocking {
        val now = Instant.now().minus(Duration.ofMinutes(20))
        // Insert Angel One instrument with lot_size = 500 for RELIANCE 1300 CE
        db.marketDao().upsertInstrument(
            symbol = "RELIANCE27MAR261300CE",
            tradingSymbol = "RELIANCE27MAR261300CE",
            token = "99901",
            exchange = "NFO",
            instType = "OPTSTK",
            expiry = "27MAR2026",
            strike = 130000.0,
            lotSize = 500.0,
            tickSize = 0.05,
            rawJson = """{"name":"RELIANCE","symbol":"RELIANCE27MAR261300CE","token":"99901","exch_seg":"NFO"}""",
            updatedAt = now.toString()
        )
        db.marketDao().upsertInstrumentMapping("RELIANCE|1300|CE", "99901", "NFO", "test", now.toString())

        val (rawId, _) = ingestion.ingest(
            SourceNotificationRow(
                receivedAt = now.toString(),
                notificationTime = now.toString(),
                channel = "OptDesk",
                category = "Stock Options",
                message = "BUY RELIANCE 1300 CE @ 40 SL 30 TGT 50",
                notificationId = "opt-pnl-1"
            )
        )
        val sigId = db.coreDao().getSignalsByRawId(rawId!!).first().id
        val verService = VerificationService(db, secretStore, stubProviders, eventBus)

        // Pre-populate cached candles for token 99901 so verification runs cleanly offline
        val t0 = TimeUtils.truncateToMinute(now)
        verService.storeCandles(
            listOf(
                Candle("angel_one", "99901", "NFO", "1m", t0.toString(), 40.0, 42.0, 39.0, 41.0),
                Candle("angel_one", "99901", "NFO", "1m", t0.plusSeconds(60).toString(), 41.0, 52.0, 40.5, 51.0)
            )
        )

        val out = verService.processSignal(sigId, "manual")
        assertEquals("target_hit", out.status)
        assertEquals("INR", out.pnlCurrency)
        // +10 points * 1 lot * 500 lot_size = +5000 INR
        assertEquals(5000.0, out.pnlAmount, 0.01)
    }

    @Test
    fun `imports legacy Flask signals sqlite3 safely with FK integrity and zero plaintext secret import`() = runBlocking {
        val legacyFile = File(context.cacheDir, "test_legacy_${System.currentTimeMillis()}.sqlite3")
        try {
            val ldb = SQLiteDatabase.openOrCreateDatabase(legacyFile, null)
            ldb.execSQL("CREATE TABLE application_settings (key TEXT PRIMARY KEY, value TEXT NOT NULL, updated_at TEXT NOT NULL)")
            ldb.execSQL("CREATE TABLE integrations (provider TEXT PRIMARY KEY, config_json TEXT NOT NULL, secrets_json TEXT NOT NULL, status TEXT NOT NULL, updated_at TEXT NOT NULL)")
            ldb.execSQL(
                """
                CREATE TABLE raw_notifications (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    received_at TEXT, notification_time TEXT, source_app TEXT, package TEXT,
                    channel TEXT, category TEXT, title TEXT, message TEXT,
                    notification_id TEXT UNIQUE, delivery_id TEXT, imported_at TEXT NOT NULL
                )
                """.trimIndent()
            )
            ldb.execSQL(
                """
                CREATE TABLE signals (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    raw_notification_id INTEGER NOT NULL,
                    symbol TEXT, direction TEXT, entry_price REAL, additional_entries TEXT DEFAULT '[]',
                    stop_loss REAL, targets TEXT DEFAULT '[]', option_type TEXT, strike_price REAL,
                    expiry_date TEXT, entry_mode TEXT DEFAULT 'limit', parsing_status TEXT,
                    parsing_confidence REAL, manual_review_status TEXT, verification_status TEXT,
                    final_result TEXT, parsing_explanation TEXT, updated_at TEXT NOT NULL
                )
                """.trimIndent()
            )
            ldb.execSQL("INSERT INTO application_settings VALUES ('entry_tolerance', '0.5', '2026-01-01T00:00:00Z')")
            ldb.execSQL("""INSERT INTO integrations VALUES ('twelve_data', '{"base_url":"https://api.twelvedata.com"}', '{"api_key":"SECRET_PLAINTEXT_DO_NOT_COPY"}', 'configured', '2026-01-01T00:00:00Z')""")
            ldb.execSQL("""INSERT INTO raw_notifications (received_at, notification_time, channel, category, message, notification_id, imported_at) VALUES ('2026-01-01T10:00:00Z', '2026-01-01T10:00:00Z', 'LegacyChan', 'Crypto', '#BTC/USDT LONG Entry: 60000 TP: 62000 SL: 59000', 'leg-1', '2026-01-01T10:00:00Z')""")
            ldb.execSQL("""INSERT INTO signals (raw_notification_id, symbol, direction, entry_price, stop_loss, targets, parsing_status, parsing_confidence, manual_review_status, verification_status, updated_at) VALUES (1, 'BTCUSDT', 'BUY', 60000, 59000, '[62000]', 'parsed', 0.94, 'not_required', 'pending', '2026-01-01T10:00:00Z')""")
            ldb.close()

            val importer = LegacySqliteImporter(context, db)
            val report = importer.importFromFile(legacyFile)
            assertTrue(report.success)
            assertTrue(report.foreignKeyCheckPassed)
            assertEquals(1, report.rawNotificationsImported)
            assertEquals(1, report.signalsImported)
            assertEquals(1, report.integrationsMigrated)
            assertEquals(listOf("twelve_data"), report.providersNeedingSecretReentry)

            // Verify integration config was preserved, status flagged for re-entry, and secret was NEVER imported
            val tdInt = db.coreDao().getIntegration("twelve_data")!!
            assertEquals("reenter_secrets", tdInt.status)
            assertTrue(secretStore.getProviderSecrets("twelve_data").isEmpty())
            assertEquals("0.5", db.coreDao().getSetting("entry_tolerance"))
        } finally {
            legacyFile.delete()
        }
    }
}
