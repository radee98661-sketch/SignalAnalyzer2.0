package com.example.data.provider

import com.example.domain.model.Candle
import com.example.util.TimeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.nio.ByteBuffer
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class ProviderError(
    message: String,
    val httpCode: Int? = null,
    val category: String = "provider_error"
) : Exception(message)

object ProviderErrorClassifier {
    fun sanitize(text: String?): String {
        if (text.isNullOrBlank()) return ""
        return text
            .replace(Regex("apikey=[^&\\s]+", RegexOption.IGNORE_CASE), "apikey=REDACTED")
            .replace(Regex("token=[^&\\s]+", RegexOption.IGNORE_CASE), "token=REDACTED")
            .replace(Regex("Bearer\\s+[A-Za-z0-9._\\-]+", RegexOption.IGNORE_CASE), "Bearer REDACTED")
            .take(350)
    }

    fun classify(httpCode: Int?, bodyOrMessage: String?, isNetwork: Boolean = false): String {
        val lower = (bodyOrMessage ?: "").lowercase(Locale.US)
        if ("daily" in lower && ("credit" in lower || "limit" in lower || "quota" in lower) ||
            "quota" in lower || "out of api credits" in lower || ("api credits" in lower && "exceed" in lower) ||
            ("plan" in lower && "upgrade" in lower)
        ) {
            return "quota_exceeded"
        }
        if (httpCode == 429 || httpCode == 418 ||
            "-1003" in lower || "too many requests" in lower || "rate limit" in lower ||
            "per minute" in lower || "current minute" in lower || ("exceeded the limit" in lower && "minute" in lower)
        ) {
            return "rate_limited"
        }
        if (httpCode == 401 || httpCode == 403 ||
            "invalid token" in lower || "ag8001" in lower || "unauthorized" in lower ||
            "invalid api" in lower || "api key" in lower && ("invalid" in lower || "not configured" in lower || "missing" in lower) ||
            "not configured" in lower || "rejected authentication" in lower || "totp" in lower
        ) {
            return "auth_error"
        }
        if (isNetwork || "timeout" in lower || "unable to resolve host" in lower ||
            "failed to connect" in lower || "connection reset" in lower || "network" in lower
        ) {
            return "network_error"
        }
        return "provider_error"
    }
}

object TotpGenerator {
    private const val BASE32_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun generate(secret: String, epochSeconds: Long = System.currentTimeMillis() / 1000L): String {
        val clean = secret.uppercase(Locale.US).replace(Regex("[\\s\\-=]+"), "")
        if (clean.isEmpty()) throw ProviderError("TOTP secret is required.")
        val key = decodeBase32(clean)
        val counter = epochSeconds / 30L
        val msg = ByteBuffer.allocate(8).putLong(counter).array()
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key, "HmacSHA1"))
        val digest = mac.doFinal(msg)
        val offset = digest[digest.size - 1].toInt() and 0x0F
        val binary = ((digest[offset].toInt() and 0x7F) shl 24) or
            ((digest[offset + 1].toInt() and 0xFF) shl 16) or
            ((digest[offset + 2].toInt() and 0xFF) shl 8) or
            (digest[offset + 3].toInt() and 0xFF)
        val otp = binary % 1_000_000
        return otp.toString().padStart(6, '0')
    }

    private fun decodeBase32(input: String): ByteArray {
        var buffer = 0
        var bitsLeft = 0
        val out = ArrayList<Byte>(input.length * 5 / 8)
        for (ch in input) {
            val v = BASE32_CHARS.indexOf(ch)
            if (v < 0) throw ProviderError("TOTP secret is not valid base32.")
            buffer = (buffer shl 5) or v
            bitsLeft += 5
            if (bitsLeft >= 8) {
                bitsLeft -= 8
                out.add(((buffer shr bitsLeft) and 0xFF).toByte())
            }
        }
        return out.toByteArray()
    }
}

interface MarketDataProviders {
    suspend fun fetchBinanceCandles(
        symbol: String,
        interval: String,
        start: Instant,
        end: Instant,
        config: Map<String, Any?>
    ): List<Candle>

    suspend fun fetchTwelveDataCandles(
        symbol: String,
        interval: String,
        start: Instant?,
        end: Instant?,
        config: Map<String, Any?>,
        apiKey: String
    ): Pair<List<Candle>, Double>

    suspend fun authenticateAngelOne(secrets: Map<String, String>, forceRefresh: Boolean = false): Pair<String, String>

    suspend fun searchAngelScrip(
        jwtToken: String,
        apiKey: String,
        exchange: String,
        searchSymbol: String
    ): List<JSONObject>

    suspend fun fetchAngelCandles(
        jwtToken: String,
        apiKey: String,
        exchange: String,
        symbolToken: String,
        interval: String,
        startIstFormatted: String,
        endIstFormatted: String
    ): List<Candle>

    suspend fun downloadAngelScripMaster(): JSONArray
}

class OkHttpMarketDataProviders(
    private val client: OkHttpClient = defaultHttpClient(),
    private val binanceDefaultEndpoints: List<Triple<String, String, String>> = BINANCE_DEFAULT_ENDPOINTS,
    private val angelBaseUrl: String = "https://apiconnect.angelone.in",
    private val angelMasterUrl: String = "https://margincalculator.angelone.in/OpenAPI_File/files/OpenAPIScripMaster.json"
) : MarketDataProviders {

    private var cachedAngelJwt: String? = null
    private var cachedAngelClientId: String? = null
    private var cachedAngelExpiresAtMs: Long = 0L

    private val tdDateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC)

    private fun candidateBinanceEndpoints(config: Map<String, Any?>): List<Triple<String, String, String>> {
        val custom = (config["base_url"] as? String)?.trim()?.trimEnd('/').orEmpty()
        if (custom.isNotEmpty()) {
            val path = if ("binance.vision" in custom) "/api/v3/klines" else "/fapi/v1/klines"
            val exch = if ("binance.vision" in custom) "SPOT_MIRROR" else "USD-M"
            return listOf(Triple(custom, path, exch)) + binanceDefaultEndpoints.filter { it.first != custom }
        }
        return binanceDefaultEndpoints
    }

    override suspend fun fetchBinanceCandles(
        symbol: String,
        interval: String,
        start: Instant,
        end: Instant,
        config: Map<String, Any?>
    ): List<Candle> = withContext(Dispatchers.IO) {
        if (interval !in BINANCE_INTERVALS) throw ProviderError("Unsupported Binance USD-M interval.")
        val endpoints = candidateBinanceEndpoints(config)
        var cursor = start.toEpochMilli()
        val finish = end.toEpochMilli()
        val out = mutableListOf<Candle>()
        var activeEp: Triple<String, String, String>? = null

        while (cursor <= finish) {
            val limit = if (activeEp?.third == "SPOT_MIRROR") 1000 else 1500
            var rows: JSONArray? = null
            var lastError: String? = null
            var lastHttpCode: Int? = null
            var lastCategory = "provider_error"
            val triedHosts = mutableListOf<String>()

            val candidates = activeEp?.let { listOf(it) } ?: endpoints
            for (ep in candidates) {
                val (base, path, exch) = ep
                triedHosts += base
                val baseUrl = (base + path).toHttpUrlOrNull() ?: continue
                val url = baseUrl.newBuilder()
                    .addQueryParameter("symbol", symbol.uppercase(Locale.US))
                    .addQueryParameter("interval", interval)
                    .addQueryParameter("startTime", cursor.toString())
                    .addQueryParameter("endTime", finish.toString())
                    .addQueryParameter("limit", limit.toString())
                    .build()

                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) SignalAnalyzer/1.0")
                    .header("Accept", "application/json")
                    .get()
                    .build()

                for (attempt in 0 until 2) {
                    try {
                        client.newCall(req).execute().use { resp ->
                            val bodyStr = resp.body?.string().orEmpty()
                            if (!resp.isSuccessful) {
                                val cat = ProviderErrorClassifier.classify(resp.code, bodyStr)
                                val safeBody = ProviderErrorClassifier.sanitize(bodyStr)
                                if (resp.code < 500 && resp.code != 429) {
                                    throw ProviderError("Binance HTTP ${resp.code}: $safeBody", resp.code, cat)
                                }
                                lastHttpCode = resp.code
                                lastCategory = cat
                                lastError = "HTTP ${resp.code}: $safeBody"
                            } else {
                                rows = JSONArray(bodyStr)
                                activeEp = Triple(base, path, exch)
                            }
                        }
                        if (rows != null) break
                    } catch (e: ProviderError) {
                        throw e
                    } catch (e: IOException) {
                        lastError = ProviderErrorClassifier.sanitize(e.message) .ifBlank { "network error" }
                        lastCategory = "network_error"
                    } catch (e: Exception) {
                        lastError = ProviderErrorClassifier.sanitize(e.message).ifBlank { "parse error" }
                        lastCategory = ProviderErrorClassifier.classify(null, lastError)
                    }
                    if (attempt == 0) delay(500)
                }
                if (rows != null) break
            }

            val fetched = rows ?: throw ProviderError(
                "Binance endpoints did not respond ($lastError). Tried: ${triedHosts.joinToString(", ")}.",
                httpCode = lastHttpCode,
                category = lastCategory
            )
            if (fetched.length() == 0) break

            val exchLabel = activeEp?.third ?: "USD-M"
            var lastOpenTime = cursor
            for (i in 0 until fetched.length()) {
                val item = fetched.optJSONArray(i) ?: continue
                val openMs = item.optLong(0, -1L)
                if (openMs < 0) continue
                lastOpenTime = openMs
                val o = item.optString(1).toDoubleOrNull() ?: continue
                val h = item.optString(2).toDoubleOrNull() ?: continue
                val l = item.optString(3).toDoubleOrNull() ?: continue
                val c = item.optString(4).toDoubleOrNull() ?: continue
                val v = item.optString(5).toDoubleOrNull() ?: 0.0
                out += Candle(
                    provider = "binance",
                    symbol = symbol.uppercase(Locale.US),
                    exchange = exchLabel,
                    interval = interval,
                    candleTimestamp = Instant.ofEpochMilli(openMs).toString(),
                    open = o,
                    high = h,
                    low = l,
                    close = c,
                    volume = v
                )
            }

            val nextCursor = lastOpenTime + 1L
            if (nextCursor <= cursor || fetched.length() < limit) break
            cursor = nextCursor
            delay(80)
        }
        out
    }

    override suspend fun fetchTwelveDataCandles(
        symbol: String,
        interval: String,
        start: Instant?,
        end: Instant?,
        config: Map<String, Any?>,
        apiKey: String
    ): Pair<List<Candle>, Double> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw ProviderError("Twelve Data API key is not configured.", null, "auth_error")
        if (interval !in TWELVE_DATA_INTERVALS) throw ProviderError("Unsupported Twelve Data interval.")

        val base = ((config["base_url"] as? String)?.ifBlank { null } ?: "https://api.twelvedata.com").trimEnd('/')
        val baseUrl = "$base/time_series".toHttpUrlOrNull()
            ?: throw ProviderError("Invalid Twelve Data base URL.")
        val builder = baseUrl.newBuilder()
            .addQueryParameter("symbol", symbol)
            .addQueryParameter("interval", interval)
            .addQueryParameter("apikey", apiKey)
            .addQueryParameter("format", "JSON")
            .addQueryParameter("timezone", (config["timezone"] as? String)?.ifBlank { null } ?: "UTC")
            .addQueryParameter("order", "ASC")
        if (start != null) builder.addQueryParameter("start_date", tdDateFmt.format(start))
        if (end != null) builder.addQueryParameter("end_date", tdDateFmt.format(end))
        if (start == null || end == null) {
            val outSize = ((config["outputsize"] as? Number)?.toInt() ?: 5000).coerceIn(1, 5000)
            builder.addQueryParameter("outputsize", outSize.toString())
        }

        val req = Request.Builder()
            .url(builder.build())
            .header("User-Agent", "SignalAnalyzer/1.0")
            .get()
            .build()

        val payload: JSONObject
        val creditsUsed: Double
        var responseCode = 200
        try {
            client.newCall(req).execute().use { resp ->
                responseCode = resp.code
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val cat = ProviderErrorClassifier.classify(resp.code, body)
                    throw ProviderError("Twelve Data HTTP ${resp.code}: ${ProviderErrorClassifier.sanitize(body)}", resp.code, cat)
                }
                payload = JSONObject(body)
                creditsUsed = resp.header("api-credits-used")?.toDoubleOrNull() ?: 1.0
            }
        } catch (e: ProviderError) {
            throw e
        } catch (e: IOException) {
            throw ProviderError("Twelve Data network error: ${ProviderErrorClassifier.sanitize(e.message)}", null, "network_error")
        } catch (e: Exception) {
            throw ProviderError("Twelve Data request failed: ${ProviderErrorClassifier.sanitize(e.message)}", null, ProviderErrorClassifier.classify(null, e.message))
        }

        if (payload.optString("status") == "error") {
            val errCode = payload.optInt("code", responseCode).takeIf { it > 0 }
            val errMsg = ProviderErrorClassifier.sanitize(payload.optString("message").ifBlank { "Twelve Data returned an error." })
            val cat = ProviderErrorClassifier.classify(errCode, errMsg)
            throw ProviderError(errMsg, errCode, cat)
        }

        val values = payload.optJSONArray("values") ?: JSONArray()
        val out = mutableListOf<Candle>()
        for (i in 0 until values.length()) {
            val x = values.optJSONObject(i) ?: continue
            val ts = TimeUtils.parseUtcInstant(x.optString("datetime")) ?: continue
            val o = x.optString("open").toDoubleOrNull() ?: continue
            val h = x.optString("high").toDoubleOrNull() ?: continue
            val l = x.optString("low").toDoubleOrNull() ?: continue
            val c = x.optString("close").toDoubleOrNull() ?: continue
            val v = x.optString("volume").toDoubleOrNull() ?: 0.0
            out += Candle(
                provider = "twelve_data",
                symbol = symbol,
                exchange = "FOREX",
                interval = interval,
                candleTimestamp = ts.toString(),
                open = o,
                high = h,
                low = l,
                close = c,
                volume = v
            )
        }
        out to creditsUsed
    }

    private fun angelHeaders(apiKey: String, jwtToken: String? = null): Request.Builder {
        val builder = Request.Builder()
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("X-UserType", "USER")
            .header("X-SourceID", "WEB")
            .header("X-PrivateKey", apiKey)
            .header("X-ClientLocalIP", "127.0.0.1")
            .header("X-ClientPublicIP", "127.0.0.1")
            .header("X-MACAddress", "00:00:00:00:00:00")
            .header("User-Agent", "SignalAnalyzer/1.0")
        if (!jwtToken.isNullOrBlank()) {
            builder.header("Authorization", "Bearer $jwtToken")
        }
        return builder
    }

    override suspend fun authenticateAngelOne(
        secrets: Map<String, String>,
        forceRefresh: Boolean
    ): Pair<String, String> = withContext(Dispatchers.IO) {
        val apiKey = secrets["api_key"].orEmpty()
        val clientId = secrets["client_id"].orEmpty()
        val password = secrets["password"].orEmpty()
        val totpSecret = secrets["totp_secret"].orEmpty()
        if (apiKey.isBlank() || clientId.isBlank() || password.isBlank() || totpSecret.isBlank()) {
            throw ProviderError("Angel One API key, Client ID, password/PIN, and TOTP secret are required.", null, "auth_error")
        }

        val nowMs = System.currentTimeMillis()
        synchronized(this@OkHttpMarketDataProviders) {
            if (!forceRefresh && cachedAngelJwt != null && cachedAngelClientId == clientId && nowMs < cachedAngelExpiresAtMs) {
                return@withContext cachedAngelJwt!! to TimeUtils.utcNowIso()
            }
        }

        val bodyJson = JSONObject().apply {
            put("clientcode", clientId)
            put("password", password)
            put("totp", TotpGenerator.generate(totpSecret))
        }
        val req = angelHeaders(apiKey)
            .url("${angelBaseUrl.trimEnd('/')}/rest/auth/angelbroking/user/v1/loginByPassword")
            .post(bodyJson.toString().toRequestBody(JSON_MEDIA))
            .build()

        val payload: JSONObject
        try {
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val cat = ProviderErrorClassifier.classify(resp.code, body)
                    throw ProviderError("Angel One authentication HTTP ${resp.code}: ${ProviderErrorClassifier.sanitize(body)}", resp.code, cat)
                }
                payload = JSONObject(body)
            }
        } catch (e: ProviderError) {
            throw e
        } catch (e: IOException) {
            throw ProviderError("Angel One auth network error: ${ProviderErrorClassifier.sanitize(e.message)}", null, "network_error")
        } catch (e: Exception) {
            throw ProviderError("Angel One authentication failed: ${ProviderErrorClassifier.sanitize(e.message)}", null, "auth_error")
        }

        val data = payload.optJSONObject("data")
        val jwt = data?.optString("jwtToken").orEmpty()
        if (!payload.optBoolean("status", false) || jwt.isBlank()) {
            val msg = ProviderErrorClassifier.sanitize(payload.optString("message").ifBlank { "Angel One rejected authentication." })
            throw ProviderError(msg, 401, ProviderErrorClassifier.classify(401, msg))
        }

        synchronized(this@OkHttpMarketDataProviders) {
            cachedAngelJwt = jwt
            cachedAngelClientId = clientId
            cachedAngelExpiresAtMs = System.currentTimeMillis() + (4L * 3600L * 1000L)
        }
        jwt to TimeUtils.utcNowIso()
    }

    override suspend fun searchAngelScrip(
        jwtToken: String,
        apiKey: String,
        exchange: String,
        searchSymbol: String
    ): List<JSONObject> = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("exchange", exchange)
            put("searchsymbol", searchSymbol)
        }
        val req = angelHeaders(apiKey, jwtToken)
            .url("${angelBaseUrl.trimEnd('/')}/rest/secure/angelbroking/order/v1/searchScrip")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()

        val response: JSONObject
        try {
            client.newCall(req).execute().use { resp ->
                val txt = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val cat = ProviderErrorClassifier.classify(resp.code, txt)
                    throw ProviderError("Angel One searchScrip HTTP ${resp.code}: ${ProviderErrorClassifier.sanitize(txt)}", resp.code, cat)
                }
                response = JSONObject(txt)
            }
        } catch (e: ProviderError) {
            throw e
        } catch (e: IOException) {
            throw ProviderError("Angel One searchScrip network error: ${ProviderErrorClassifier.sanitize(e.message)}", null, "network_error")
        } catch (e: Exception) {
            throw ProviderError("Angel One searchScrip failed: ${ProviderErrorClassifier.sanitize(e.message)}", null, ProviderErrorClassifier.classify(null, e.message))
        }
        if (!response.optBoolean("status", false)) {
            val msg = ProviderErrorClassifier.sanitize(response.optString("message").ifBlank { "Angel One searchScrip returned no data." })
            throw ProviderError(msg, null, ProviderErrorClassifier.classify(null, msg))
        }
        val arr = response.optJSONArray("data") ?: return@withContext emptyList()
        buildList {
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { add(it) }
            }
        }
    }

    override suspend fun fetchAngelCandles(
        jwtToken: String,
        apiKey: String,
        exchange: String,
        symbolToken: String,
        interval: String,
        startIstFormatted: String,
        endIstFormatted: String
    ): List<Candle> = withContext(Dispatchers.IO) {
        if (interval !in ANGEL_INTERVALS) throw ProviderError("Unsupported Angel One interval.")
        val body = JSONObject().apply {
            put("exchange", exchange)
            put("symboltoken", symbolToken)
            put("interval", interval)
            put("fromdate", startIstFormatted)
            put("todate", endIstFormatted)
        }
        val req = angelHeaders(apiKey, jwtToken)
            .url("${angelBaseUrl.trimEnd('/')}/rest/secure/angelbroking/historical/v1/getCandleData")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()

        val response: JSONObject
        try {
            client.newCall(req).execute().use { resp ->
                val txt = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val cat = ProviderErrorClassifier.classify(resp.code, txt)
                    throw ProviderError("Angel One candle HTTP ${resp.code}: ${ProviderErrorClassifier.sanitize(txt)}", resp.code, cat)
                }
                response = JSONObject(txt)
            }
        } catch (e: ProviderError) {
            throw e
        } catch (e: IOException) {
            throw ProviderError("Angel One candle network error: ${ProviderErrorClassifier.sanitize(e.message)}", null, "network_error")
        } catch (e: Exception) {
            throw ProviderError("Angel One candle request failed: ${ProviderErrorClassifier.sanitize(e.message)}", null, ProviderErrorClassifier.classify(null, e.message))
        }

        if (!response.optBoolean("status", false)) {
            val msg = ProviderErrorClassifier.sanitize(response.optString("message").ifBlank { "Angel One returned no candle data." })
            throw ProviderError(msg, null, ProviderErrorClassifier.classify(null, msg))
        }

        val arr = response.optJSONArray("data") ?: JSONArray()
        val out = mutableListOf<Candle>()
        for (i in 0 until arr.length()) {
            val row = arr.optJSONArray(i) ?: continue
            if (row.length() < 5) continue
            val ts = TimeUtils.parseUtcInstant(row.optString(0)) ?: continue
            val o = row.optDouble(1, Double.NaN)
            val h = row.optDouble(2, Double.NaN)
            val l = row.optDouble(3, Double.NaN)
            val c = row.optDouble(4, Double.NaN)
            if (o.isNaN() || h.isNaN() || l.isNaN() || c.isNaN()) continue
            val v = if (row.length() > 5) row.optDouble(5, 0.0).let { if (it.isNaN()) 0.0 else it } else 0.0
            out += Candle(
                provider = "angel_one",
                symbol = symbolToken,
                exchange = exchange,
                interval = interval,
                candleTimestamp = ts.toString(),
                open = o,
                high = h,
                low = l,
                close = c,
                volume = v
            )
        }
        out
    }

    override suspend fun downloadAngelScripMaster(): JSONArray = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(angelMasterUrl)
            .header("User-Agent", "SignalAnalyzer/1.0")
            .get()
            .build()
        val longClient = client.newBuilder()
            .readTimeout(90, TimeUnit.SECONDS)
            .connectTimeout(30, TimeUnit.SECONDS)
            .build()
        longClient.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw ProviderError("Instrument master HTTP ${resp.code}")
            }
            val trimmed = body.trim()
            if (!trimmed.startsWith("[")) {
                throw ProviderError("Instrument master was not a JSON array.")
            }
            JSONArray(trimmed)
        }
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        val BINANCE_INTERVALS = setOf("1m", "3m", "5m", "15m", "30m", "1h", "2h", "4h", "1d")
        val TWELVE_DATA_INTERVALS = setOf("1min", "5min", "15min", "1h", "4h", "1day")
        val ANGEL_INTERVALS = setOf(
            "ONE_MINUTE", "THREE_MINUTE", "FIVE_MINUTE", "TEN_MINUTE",
            "FIFTEEN_MINUTE", "THIRTY_MINUTE", "ONE_HOUR", "ONE_DAY"
        )

        val BINANCE_DEFAULT_ENDPOINTS = listOf(
            Triple("https://fapi.binance.com", "/fapi/v1/klines", "USD-M"),
            Triple("https://fapi1.binance.com", "/fapi/v1/klines", "USD-M"),
            Triple("https://fapi2.binance.com", "/fapi/v1/klines", "USD-M"),
            Triple("https://fapi3.binance.com", "/fapi/v1/klines", "USD-M"),
            Triple("https://fapi4.binance.com", "/fapi/v1/klines", "USD-M"),
            Triple("https://data-api.binance.vision", "/api/v3/klines", "SPOT_MIRROR")
        )

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}
