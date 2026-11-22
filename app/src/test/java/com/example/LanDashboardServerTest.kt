package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.InMemorySecretStore
import com.example.data.provider.GoogleSheetsSyncClient
import com.example.data.provider.MarketDataProviders
import com.example.data.repository.EventBus
import com.example.data.repository.SignalIngestionService
import com.example.data.repository.SignalRepository
import com.example.data.repository.VerificationService
import com.example.domain.model.AppEvent
import com.example.domain.model.Candle
import com.example.server.LanAuthManager
import com.example.server.LanDashboardServer
import com.example.util.TimeUtils
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LanDashboardServerTest {

    private lateinit var db: AppDatabase
    private lateinit var secretStore: InMemorySecretStore
    private lateinit var authManager: LanAuthManager
    private lateinit var repository: SignalRepository
    private lateinit var server: LanDashboardServer
    private lateinit var client: OkHttpClient
    private var port: Int = 0

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private val stubProviders = object : MarketDataProviders {
        override suspend fun fetchBinanceCandles(symbol: String, interval: String, start: Instant, end: Instant, config: Map<String, Any?>) = emptyList<Candle>()
        override suspend fun fetchTwelveDataCandles(symbol: String, interval: String, start: Instant?, end: Instant?, config: Map<String, Any?>, apiKey: String) = emptyList<Candle>() to 0.0
        override suspend fun authenticateAngelOne(secrets: Map<String, String>, forceRefresh: Boolean) = "jwt" to TimeUtils.utcNowIso()
        override suspend fun searchAngelScrip(jwtToken: String, apiKey: String, exchange: String, searchSymbol: String) = emptyList<JSONObject>()
        override suspend fun fetchAngelCandles(jwtToken: String, apiKey: String, exchange: String, symbolToken: String, interval: String, startIstFormatted: String, endIstFormatted: String) = emptyList<Candle>()
        override suspend fun downloadAngelScripMaster() = JSONArray()
    }

    @Before
    fun setUp(): Unit = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        db.initializeDefaults()
        secretStore = InMemorySecretStore()
        authManager = LanAuthManager(secretStore, maxFailuresPerMinute = 4)
        val eventBus = EventBus()
        val ingestion = SignalIngestionService(db, eventBus)
        val verification = VerificationService(db, secretStore, stubProviders, eventBus)
        val syncClient = GoogleSheetsSyncClient(db, secretStore, ingestion, eventBus)
        repository = SignalRepository(ctx, db, secretStore, stubProviders, ingestion, verification, syncClient, eventBus)

        server = LanDashboardServer(
            repository = repository,
            authManager = authManager,
            assetLoader = { name -> "<html>$name</html>".toByteArray() }
        )
        server.start("127.0.0.1", 0)
        port = server.actualPort()
        client = OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build()
    }

    @After
    fun tearDown() {
        server.stop()
        db.close()
    }

    @Test
    fun `enforces RFC1918 and loopback IP allowlist while rejecting public IPs`() {
        assertTrue(authManager.isAllowedLanAddress("127.0.0.1"))
        assertTrue(authManager.isAllowedLanAddress("::1"))
        assertTrue(authManager.isAllowedLanAddress("192.168.1.42"))
        assertTrue(authManager.isAllowedLanAddress("10.0.0.15"))
        assertTrue(authManager.isAllowedLanAddress("172.16.8.4"))

        assertFalse(authManager.isAllowedLanAddress("8.8.8.8"))
        assertFalse(authManager.isAllowedLanAddress("203.0.113.10"))
        assertFalse(authManager.isAllowedLanAddress("1.1.1.1"))
        assertFalse(authManager.isAllowedLanAddress(""))
    }

    @Test
    fun `requires auth rate limits bad tokens pairs session and serves full API and WebSocket events`(): Unit = runBlocking {
        val baseUrl = "http://127.0.0.1:$port"

        // 1. Unauthenticated request returns 401
        val unauthResp = client.newCall(Request.Builder().url("$baseUrl/api/v1/overview").get().build()).execute()
        assertEquals(401, unauthResp.code)
        unauthResp.close()

        // 2. Repeated bad tokens trigger 429 rate limit (threshold = 4)
        repeat(3) {
            client.newCall(
                Request.Builder().url("$baseUrl/api/v1/overview")
                    .header("Authorization", "Bearer wrong-token")
                    .get().build()
            ).execute().close()
        }
        val limitedResp = client.newCall(
            Request.Builder().url("$baseUrl/api/v1/overview")
                .header("Authorization", "Bearer wrong-token")
                .get().build()
        ).execute()
        assertEquals(429, limitedResp.code)
        limitedResp.close()

        // Clear rate limit bucket for localhost so we can test valid pairing flow cleanly
        authManager.clearAuthFailures("127.0.0.1")

        // 3. Pair using install bearer token -> receives session token
        val bearer = authManager.currentBearerToken()
        val pairResp = client.newCall(
            Request.Builder()
                .url("$baseUrl/api/v1/auth/pair")
                .header("X-Requested-With", "SignalAnalyzer")
                .post("""{"token":"$bearer"}""".toRequestBody(jsonMedia))
                .build()
        ).execute()
        assertEquals(200, pairResp.code)
        val pairJson = JSONObject(pairResp.body!!.string())
        pairResp.close()
        val sessionToken = pairJson.getString("session_token")
        assertTrue(sessionToken.startsWith("sa_sess_"))

        // 4. Parse preview endpoint
        val parseResp = client.newCall(
            Request.Builder()
                .url("$baseUrl/api/v1/parse")
                .header("Authorization", "Bearer $sessionToken")
                .header("X-Requested-With", "SignalAnalyzer")
                .post("""{"category":"Crypto","message":"#BTC/USDT LONG\nEntry: 65000\nTP: 67000\nSL: 64000"}""".toRequestBody(jsonMedia))
                .build()
        ).execute()
        assertEquals(200, parseResp.code)
        val parseJson = JSONObject(parseResp.body!!.string())
        parseResp.close()
        assertEquals("BTCUSDT", parseJson.getString("symbol"))
        assertEquals("BUY", parseJson.getString("direction"))

        // 5. Import manual signal via API (with all 10 required SOURCE_COLUMNS)
        val nowIso = TimeUtils.utcNowIso()
        val importBody = JSONObject().apply {
            put("Received At", nowIso)
            put("Notification Time", nowIso)
            put("Source App", "Telegram")
            put("Package", "org.telegram.messenger")
            put("Channel", "LanTestChan")
            put("Category", "Crypto")
            put("Title", "ETH Long")
            put("Message", "#ETH/USDT LONG\nEntry: 3200\nTP: 3350\nSL: 3100")
            put("Notification ID", "lan-notif-1")
            put("Delivery ID", "del-lan-1")
        }
        val importResp = client.newCall(
            Request.Builder()
                .url("$baseUrl/api/v1/import")
                .header("Authorization", "Bearer $sessionToken")
                .header("X-Requested-With", "SignalAnalyzer")
                .post(importBody.toString().toRequestBody(jsonMedia))
                .build()
        ).execute()
        assertEquals(200, importResp.code)
        val importJson = JSONObject(importResp.body!!.string())
        importResp.close()
        assertEquals(1, importJson.getInt("imported"))

        // 6. Query signals & overview
        val sigResp = client.newCall(
            Request.Builder()
                .url("$baseUrl/api/v1/signals")
                .header("Authorization", "Bearer $sessionToken")
                .get().build()
        ).execute()
        assertEquals(200, sigResp.code)
        val sigArr = JSONArray(sigResp.body!!.string())
        sigResp.close()
        assertEquals(1, sigArr.length())
        val sig0 = sigArr.getJSONObject(0)
        assertEquals("ETHUSDT", sig0.getString("symbol"))
        assertTrue(sig0.has("tp1_pnl_amount"))
        assertTrue(sig0.has("sl_pnl_amount"))
        assertTrue(sig0.has("monitoring_phase"))
        assertTrue(sig0.getJSONArray("tp_levels").length() >= 1)

        // 6b. Signals P&L summary endpoint
        val sumResp = client.newCall(
            Request.Builder()
                .url("$baseUrl/api/v1/signals/summary?pnl_basis=tp1")
                .header("Authorization", "Bearer $sessionToken")
                .get().build()
        ).execute()
        assertEquals(200, sumResp.code)
        val sumJson = JSONObject(sumResp.body!!.string())
        sumResp.close()
        assertEquals("tp1", sumJson.getString("basis"))
        assertEquals(1, sumJson.getInt("total_signals"))

        // 6c. Filtered channel analytics endpoint
        val chanResp = client.newCall(
            Request.Builder()
                .url("$baseUrl/api/v1/analytics/channels?category=Crypto&pnl_basis=tp3")
                .header("Authorization", "Bearer $sessionToken")
                .get().build()
        ).execute()
        assertEquals(200, chanResp.code)
        val chanArr = JSONArray(chanResp.body!!.string())
        chanResp.close()
        assertEquals(1, chanArr.length())
        assertEquals("LanTestChan", chanArr.getJSONObject(0).getString("channel"))
        assertTrue(chanArr.getJSONObject(0).has("tp3_hits"))
        assertTrue(chanArr.getJSONObject(0).has("tp_then_sl_hits"))

        // 6d. Provider API Health endpoint with simulated rate-limit incident
        db.marketDao().insertApiRequestLog(
            com.example.data.local.ApiRequestLogEntity(
                provider = "twelve_data",
                endpoint = "/time_series",
                requestedAt = TimeUtils.utcNowIso(),
                completedAt = TimeUtils.utcNowIso(),
                status = "error",
                creditsUsed = 0.0,
                candlesReturned = 0,
                error = "HTTP 429 Too Many Requests",
                httpCode = 429,
                errorCategory = "rate_limited"
            )
        )
        val healthResp = client.newCall(
            Request.Builder()
                .url("$baseUrl/api/v1/api-health")
                .header("Authorization", "Bearer $sessionToken")
                .get().build()
        ).execute()
        assertEquals(200, healthResp.code)
        val healthJson = JSONObject(healthResp.body!!.string())
        healthResp.close()
        assertEquals("error", healthJson.getString("overall_status"))
        assertTrue(healthJson.getJSONArray("providers").length() >= 4)
        assertTrue(healthJson.getJSONArray("active_alerts").length() >= 1)

        val ovResp = client.newCall(
            Request.Builder()
                .url("$baseUrl/api/v1/overview")
                .header("Authorization", "Bearer $sessionToken")
                .get().build()
        ).execute()
        assertEquals(200, ovResp.code)
        val ovJson = JSONObject(ovResp.body!!.string())
        ovResp.close()
        assertEquals(1, ovJson.getJSONObject("metrics").getInt("notifications"))

        // 7. Save Twelve Data integration with secret -> GET /integrations must NEVER return plaintext secret
        val saveIntResp = client.newCall(
            Request.Builder()
                .url("$baseUrl/api/v1/integrations/twelve_data")
                .header("Authorization", "Bearer $sessionToken")
                .header("X-Requested-With", "SignalAnalyzer")
                .post("""{"config":{"base_url":"https://api.twelvedata.com"},"secrets":{"api_key":"TOP_SECRET_TD_KEY_999"}}""".toRequestBody(jsonMedia))
                .build()
        ).execute()
        assertEquals(200, saveIntResp.code)
        saveIntResp.close()

        val getIntResp = client.newCall(
            Request.Builder()
                .url("$baseUrl/api/v1/integrations")
                .header("Authorization", "Bearer $sessionToken")
                .get().build()
        ).execute()
        assertEquals(200, getIntResp.code)
        val intBody = getIntResp.body!!.string()
        getIntResp.close()
        assertFalse("Secret must never appear in API response", intBody.contains("TOP_SECRET_TD_KEY_999"))
        assertTrue(intBody.contains("twelve_data"))

        // 8. Export CSV
        val csvResp = client.newCall(
            Request.Builder()
                .url("$baseUrl/api/v1/export/signals.csv")
                .header("Authorization", "Bearer $sessionToken")
                .get().build()
        ).execute()
        assertEquals(200, csvResp.code)
        val csvText = csvResp.body!!.string()
        csvResp.close()
        assertTrue(csvText.contains("ETHUSDT"))

        // 9. WebSocket /ws/events first-frame auth and live event broadcast
        val authLatch = CountDownLatch(1)
        val eventLatch = CountDownLatch(1)
        var receivedEventType: String? = null

        val wsReq = Request.Builder().url("ws://127.0.0.1:$port/ws/events").build()
        val ws = client.newWebSocket(wsReq, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(JSONObject(mapOf("type" to "auth", "token" to sessionToken)).toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val obj = JSONObject(text)
                when (obj.optString("type")) {
                    "auth_ok" -> authLatch.countDown()
                    "signal.changed" -> {
                        receivedEventType = obj.optString("type")
                        eventLatch.countDown()
                    }
                }
            }
        })
        assertTrue("WebSocket should authenticate", authLatch.await(5, TimeUnit.SECONDS))
        repository.eventBus.emit(AppEvent("signal.changed", 1L, "target_hit", "TP1 Hit", TimeUtils.utcNowIso()))
        assertTrue("WebSocket should deliver live event", eventLatch.await(5, TimeUnit.SECONDS))
        assertNotNull(receivedEventType)
        assertEquals("signal.changed", receivedEventType)
        ws.close(1000, "done")
    }
}
