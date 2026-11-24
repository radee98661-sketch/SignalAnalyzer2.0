package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.InMemorySecretStore
import com.example.data.provider.GoogleSheetsSyncClient
import com.example.data.provider.OkHttpMarketDataProviders
import com.example.data.provider.ProviderError
import com.example.data.provider.TotpGenerator
import com.example.data.repository.EventBus
import com.example.data.repository.SignalIngestionService
import com.example.util.TimeUtils
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ProviderContractTest {

    private lateinit var server: MockWebServer
    private lateinit var db: AppDatabase
    private lateinit var secretStore: InMemorySecretStore

    @Before
    fun setUp() = runBlocking {
        server = MockWebServer()
        server.start()
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        db.initializeDefaults()
        secretStore = InMemorySecretStore()
    }

    @After
    fun tearDown() {
        db.close()
        server.shutdown()
    }

    @Test
    fun `generates RFC 6238 compliant 6-digit TOTP codes`() {
        // RFC 6238 SHA-1 test vector secret "12345678901234567890" in Base32 = GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ
        val base32Secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
        val otp = TotpGenerator.generate(base32Secret, epochSeconds = 59L)
        assertEquals("287082", otp)
    }

    @Test
    fun `fetches Binance candles with fallback host retry on HTTP 500`() = runBlocking {
        // First host returns 500 twice, second host returns valid klines
        server.enqueue(MockResponse().setResponseCode(500).setBody("err"))
        server.enqueue(MockResponse().setResponseCode(500).setBody("err"))
        val t0 = Instant.parse("2026-01-01T10:00:00Z").toEpochMilli()
        val t1 = Instant.parse("2026-01-01T10:01:00Z").toEpochMilli()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """[[$t0,"65000.0","65200.0","64900.0","65100.0","12.5"],[$t1,"65100.0","65500.0","65050.0","65400.0","18.2"]]"""
            )
        )

        val base = server.url("").toString().trimEnd('/')
        val providers = OkHttpMarketDataProviders(
            binanceDefaultEndpoints = listOf(
                Triple(base, "/fapi/v1/klines", "USD-M"),
                Triple(base, "/api/v3/klines", "SPOT_MIRROR")
            )
        )

        val candles = providers.fetchBinanceCandles(
            symbol = "BTCUSDT",
            interval = "1m",
            start = Instant.ofEpochMilli(t0),
            end = Instant.ofEpochMilli(t1),
            config = emptyMap()
        )
        assertEquals(2, candles.size)
        assertEquals("SPOT_MIRROR", candles[0].exchange)
        assertEquals(65000.0, candles[0].open, 0.001)
        assertEquals(65400.0, candles[1].close, 0.001)
    }

    @Test
    fun `fetches Twelve Data candles parses credit header and rejects blank API key`() = runBlocking {
        val base = server.url("").toString().trimEnd('/')
        val providers = OkHttpMarketDataProviders()

        try {
            providers.fetchTwelveDataCandles("XAU/USD", "1min", null, null, mapOf("base_url" to base), "")
            fail("Expected ProviderError on blank API key")
        } catch (e: ProviderError) {
            assertTrue(e.message.orEmpty().contains("not configured"))
        }

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("api-credits-used", "3")
                .setBody(
                    """
                    {
                      "status": "ok",
                      "values": [
                        {"datetime": "2026-01-01 10:00:00", "open": "2700.5", "high": "2705.0", "low": "2699.0", "close": "2703.2", "volume": "50"},
                        {"datetime": "2026-01-01 10:01:00", "open": "2703.2", "high": "2710.0", "low": "2702.0", "close": "2708.8", "volume": "80"}
                      ]
                    }
                    """.trimIndent()
                )
        )

        val (candles, credits) = providers.fetchTwelveDataCandles(
            symbol = "XAU/USD",
            interval = "1min",
            start = Instant.parse("2026-01-01T10:00:00Z"),
            end = Instant.parse("2026-01-01T10:02:00Z"),
            config = mapOf("base_url" to base),
            apiKey = "td_test_key"
        )
        assertEquals(2, candles.size)
        assertEquals(3.0, credits, 0.001)
        assertEquals(2700.5, candles[0].open, 0.001)
    }

    @Test
    fun `authenticates Angel One with JWT cache searches scrip fetches candles and downloads master`() = runBlocking {
        val base = server.url("").toString().trimEnd('/')
        val providers = OkHttpMarketDataProviders(
            angelBaseUrl = base,
            angelMasterUrl = "$base/OpenAPIScripMaster.json"
        )
        val secrets = mapOf(
            "api_key" to "angel-key",
            "client_id" to "A12345",
            "password" to "1234",
            "totp_secret" to "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
        )

        // 1. Login response
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"status":true,"data":{"jwtToken":"jwt-abc-123"}}""")
        )
        // 2. searchScrip response
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"status":true,"data":[{"exchange":"NFO","tradingsymbol":"NIFTY27MAR2624500CE","symboltoken":"55001"}]}"""
            )
        )
        // 3. getCandleData response
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"status":true,"data":[["2026-03-25T09:15:00+05:30",120.5,126.0,119.0,124.5,1500]]}"""
            )
        )
        // 4. Master JSON download
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """[{"token":"55001","symbol":"NIFTY27MAR2624500CE","name":"NIFTY","exch_seg":"NFO","instrumenttype":"OPTIDX","strike":"2450000","lotsize":"75"}]"""
            )
        )

        val (jwt1, _) = providers.authenticateAngelOne(secrets)
        val (jwt2, _) = providers.authenticateAngelOne(secrets) // cached — must not hit server again
        assertEquals("jwt-abc-123", jwt1)
        assertEquals("jwt-abc-123", jwt2)

        val hits = providers.searchAngelScrip(jwt1, "angel-key", "NFO", "NIFTY 24500 CE")
        assertEquals(1, hits.size)
        assertEquals("55001", hits[0].getString("symboltoken"))

        val candles = providers.fetchAngelCandles(jwt1, "angel-key", "NFO", "55001", "ONE_MINUTE", "2026-03-25 09:15", "2026-03-25 09:30")
        assertEquals(1, candles.size)
        assertEquals(120.5, candles[0].open, 0.001)

        val master = providers.downloadAngelScripMaster()
        assertEquals(1, master.length())
        assertEquals(4, server.requestCount) // Confirms JWT was cached
    }

    @Test
    fun `syncs Google Apps Script data and worksheets envelopes persists cursor and reports safe shape on error`() = runBlocking {
        val url = server.url("/macros/s/test/exec").toString()
        db.coreDao().upsertIntegration("google_sheets", """{"apps_script_url":"$url"}""", "configured", TimeUtils.utcNowIso())
        secretStore.putProviderSecrets("google_sheets", mapOf("access_token" to "super-secret-bearer"))

        val eventBus = EventBus()
        val ingestion = SignalIngestionService(db, eventBus)
        val syncClient = GoogleSheetsSyncClient(db, secretStore, ingestion, eventBus)

        // Envelope 1: Apps Script `data` (snake_case) + next_since_ms cursor
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "ok": true,
                  "next_since_ms": 1770001234567,
                  "data": {
                    "Index Options": [
                      {
                        "received_at": "2026-03-20T10:01:00Z",
                        "notification_time": "2026-03-20T10:01:00Z",
                        "source_app": "Telegram",
                        "package": "org.telegram.messenger",
                        "channel": "NiftyDesk",
                        "category": "Index Options",
                        "title": "NiftyDesk",
                        "message": "BUY NIFTY 24500 CE @ 120 SL 95 TGT 150",
                        "notification_id": "gs-idx-1",
                        "delivery_id": "gs-idx-1"
                      }
                    ],
                    "Crypto": [
                      {
                        "received_at": "2026-03-20T10:00:00Z",
                        "notification_time": "2026-03-20T10:00:00Z",
                        "source_app": "Telegram",
                        "package": "org.telegram",
                        "channel": "CryptoDesk",
                        "category": "Crypto",
                        "title": "Signal",
                        "message": "#BTC/USDT LONG\nEntry: 65000\nTP: 67000\nSL: 64000",
                        "notification_id": "gs-1",
                        "delivery_id": "del-1"
                      }
                    ]
                  }
                }
                """.trimIndent()
            )
        )
        val res1 = syncClient.syncGoogleSheets()
        assertEquals("success", res1.status)
        assertEquals(2, res1.imported)
        assertEquals("1770001234567", db.coreDao().getSetting("google_sheets_next_since_ms"))
        server.takeRequest() // consume first request

        // Envelope 2: `worksheets` format + confirms since_ms query param is sent on subsequent poll
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "worksheets": {
                    "Forex": [
                      {
                        "Received At": "2026-03-20T10:05:00Z",
                        "Notification Time": "2026-03-20T10:05:00Z",
                        "Source App": "Telegram",
                        "Package": "org.telegram",
                        "Channel": "FxDesk",
                        "Category": "Forex",
                        "Title": "Gold",
                        "Message": "XAUUSD BUY 3010\nTP: 3025\nSL: 3000",
                        "Notification ID": "gs-2",
                        "Delivery ID": "del-2"
                      }
                    ]
                  }
                }
                """.trimIndent()
            )
        )
        val res2 = syncClient.syncGoogleSheets()
        assertEquals("success", res2.status)
        assertEquals(1, res2.imported)
        val req2 = server.takeRequest()
        val expectedSince = (1770001234567L - GoogleSheetsSyncClient.EDIT_LOOKBACK_MS).toString()
        assertTrue(req2.path.orEmpty().contains("since_ms=$expectedSince"))

        // Envelope 3: Unexpected payload -> safe shape diagnostic without leaking values
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"unexpected":"SENSITIVE_VALUE_XYZ","count":5}""")
        )
        val res3 = syncClient.syncGoogleSheets()
        assertEquals("failed", res3.status)
        assertTrue(res3.detail.contains("Safe response shape"))
        assertFalse(res3.detail.contains("SENSITIVE_VALUE_XYZ"))
    }

    @Test
    fun `classifies provider API errors accurately and sanitizes secrets from diagnostics`() {
        assertEquals("rate_limited", com.example.data.provider.ProviderErrorClassifier.classify(429, "Too many requests"))
        assertEquals("quota_exceeded", com.example.data.provider.ProviderErrorClassifier.classify(429, "You have run out of API credits for today"))
        assertEquals("quota_exceeded", com.example.data.provider.ProviderErrorClassifier.classify(200, "Daily credit limit exceeded"))
        assertEquals("auth_error", com.example.data.provider.ProviderErrorClassifier.classify(401, "Invalid API key"))
        assertEquals("auth_error", com.example.data.provider.ProviderErrorClassifier.classify(403, "Forbidden"))
        assertEquals("network_error", com.example.data.provider.ProviderErrorClassifier.classify(null, "java.net.SocketTimeoutException: timeout"))

        val dirty = "Request failed for https://api.twelvedata.com/time_series?symbol=XAU/USD&apikey=SECRET_KEY_12345 Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.payload.sig token=abc999"
        val clean = com.example.data.provider.ProviderErrorClassifier.sanitize(dirty)
        assertFalse(clean.contains("SECRET_KEY_12345"))
        assertFalse(clean.contains("eyJhbGciOiJIUzI1NiJ9"))
        assertFalse(clean.contains("abc999"))
        assertTrue(clean.contains("REDACTED"))
    }
}
