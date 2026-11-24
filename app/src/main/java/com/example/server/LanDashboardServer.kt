package com.example.server

import com.example.data.local.IntegrationEntity
import com.example.data.local.NotificationJoinedRow
import com.example.data.local.RawNotificationEntity
import com.example.data.provider.ProviderError
import com.example.data.repository.EnrichedSignal
import com.example.data.repository.SignalEditPayload
import com.example.data.repository.SignalRepository
import com.example.domain.model.AppEvent
import com.example.domain.model.DefaultSlCategoryRule
import com.example.domain.model.SourceNotificationRow
import com.example.domain.parser.DefaultRulesEngine
import com.example.domain.parser.SignalParser
import com.example.util.JsonUtils
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.origin
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

data class ServerRuntimeInfo(
    val running: Boolean,
    val bindHost: String,
    val port: Int,
    val lanEnabled: Boolean,
    val activeWsClients: Int,
    val dedicatedProcessingEnabled: Boolean = false
)

class LanDashboardServer(
    private val repository: SignalRepository,
    private val authManager: LanAuthManager,
    private val assetLoader: (String) -> ByteArray?,
    private val runtimeStatusProvider: () -> ServerRuntimeInfo = {
        ServerRuntimeInfo(running = true, bindHost = "127.0.0.1", port = 0, lanEnabled = false, activeWsClients = 0)
    }
) {
    private var engine: ApplicationEngine? = null
    private var currentHost: String = "127.0.0.1"
    private var currentPort: Int = 8765
    private val wsClients = AtomicInteger(0)

    fun activeWsClientCount(): Int = wsClients.get()

    fun isRunning(): Boolean = engine != null

    suspend fun actualPort(): Int {
        val eng = engine ?: return currentPort
        return try {
            eng.resolvedConnectors().firstOrNull()?.port ?: currentPort
        } catch (_: Exception) {
            currentPort
        }
    }

    @Synchronized
    fun start(bindHost: String, port: Int) {
        if (engine != null) stop()
        currentHost = bindHost
        currentPort = port
        val server = embeddedServer(CIO, port = port, host = bindHost) {
            install(WebSockets) {
                pingPeriod = Duration.ofSeconds(20)
                timeout = Duration.ofSeconds(30)
                maxFrameSize = 1024 * 64
                masking = false
            }

            routing {
                get("/") { serveAsset(call, "index.html", ContentType.Text.Html) }
                get("/style.css") { serveAsset(call, "style.css", ContentType.Text.CSS) }
                get("/app.js") { serveAsset(call, "app.js", ContentType.Application.JavaScript) }
                get("/static/style.css") { serveAsset(call, "style.css", ContentType.Text.CSS) }
                get("/static/app.js") { serveAsset(call, "app.js", ContentType.Application.JavaScript) }

                webSocket("/ws/events") {
                    val remoteHost = call.request.origin.remoteAddress
                    if (!authManager.isAllowedLanAddress(remoteHost)) {
                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "LAN access only"))
                        return@webSocket
                    }
                    if (authManager.isRateLimited(remoteHost)) {
                        close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "Rate limited"))
                        return@webSocket
                    }

                    val firstFrame = withTimeoutOrNull(10_000L) { incoming.receiveCatching().getOrNull() }
                    val authText = (firstFrame as? Frame.Text)?.readText().orEmpty()
                    val token = try {
                        val obj = JSONObject(authText)
                        if (obj.optString("type") == "auth") obj.optString("token", "") else ""
                    } catch (_: Exception) {
                        ""
                    }
                    if (!authManager.verifyAnyToken(token)) {
                        authManager.recordAuthFailure(remoteHost)
                        send(Frame.Text(JSONObject(mapOf("type" to "auth_error", "error" to "Unauthorized")).toString()))
                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Unauthorized"))
                        return@webSocket
                    }
                    authManager.clearAuthFailures(remoteHost)
                    wsClients.incrementAndGet()
                    try {
                        val collectJob = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                            repository.eventBus.events.collect { ev ->
                                send(Frame.Text(eventToJson(ev).toString()))
                            }
                        }
                        send(Frame.Text(JSONObject(mapOf("type" to "auth_ok")).toString()))
                        try {
                            for (frame in incoming) {
                                if (frame is Frame.Text && frame.readText().trim() == "ping") {
                                    send(Frame.Text("""{"type":"pong"}"""))
                                }
                            }
                        } finally {
                            collectJob.cancel()
                        }
                    } finally {
                        wsClients.decrementAndGet()
                    }
                }

                route("/api/v1") { registerApiRoutes() }
                route("/api") { registerApiRoutes() }
            }
        }
        server.start(wait = false)
        engine = server
    }

    @Synchronized
    fun stop() {
        try {
            engine?.stop(500, 1500)
        } catch (_: Exception) {
        } finally {
            engine = null
        }
    }

    private suspend fun serveAsset(call: ApplicationCall, name: String, contentType: ContentType) {
        val remote = call.request.origin.remoteAddress
        if (!authManager.isAllowedLanAddress(remote)) {
            call.respondJsonError(HttpStatusCode.Forbidden, "Access restricted to private LAN addresses.")
            return
        }
        call.addSecurityHeaders(isApi = false)
        val bytes = assetLoader(name)
        if (bytes == null) {
            call.respondText("Asset not found: $name", status = HttpStatusCode.NotFound)
        } else {
            call.respondBytes(bytes, contentType)
        }
    }

    private fun Route.registerApiRoutes() {
        post("/auth/pair") {
            val remote = call.request.origin.remoteAddress
            if (!authManager.isAllowedLanAddress(remote)) {
                return@post call.respondJsonError(HttpStatusCode.Forbidden, "Access restricted to private LAN addresses.")
            }
            if (authManager.isRateLimited(remote)) {
                return@post call.respondJsonError(HttpStatusCode.TooManyRequests, "Too many failed authentication attempts. Wait 60 seconds.")
            }
            val body = call.readJsonBody() ?: return@post call.respondJsonError(HttpStatusCode.BadRequest, "Invalid JSON body.")
            val supplied = body.optString("token", "").trim()
            if (!authManager.verifyBearerToken(supplied)) {
                authManager.recordAuthFailure(remote)
                return@post call.respondJsonError(HttpStatusCode.Unauthorized, "Invalid access token.")
            }
            authManager.clearAuthFailures(remote)
            val (sessionToken, ttl) = authManager.issueSessionToken()
            call.respondJson(
                JSONObject().apply {
                    put("ok", true)
                    put("session_token", sessionToken)
                    put("expires_in", ttl)
                }
            )
        }

        get("/server/status") {
            if (!call.requireAuth()) return@get
            val info = runtimeStatusProvider()
            call.respondJson(
                JSONObject().apply {
                    put("running", info.running)
                    put("bind_host", info.bindHost)
                    put("port", info.port)
                    put("lan_enabled", info.lanEnabled)
                    put("active_ws_clients", wsClients.get())
                    put("dedicated_processing_enabled", info.dedicatedProcessingEnabled)
                }
            )
        }

        get("/overview") {
            if (!call.requireAuth()) return@get
            val ov = repository.getOverview()
            val metricsObj = JSONObject().apply {
                put("notifications", ov.notifications)
                put("parsed", ov.parsed)
                put("today", ov.today)
                put("target_hits", ov.targetHits)
                put("stop_loss_hits", ov.stopLossHits)
                put("pending", ov.pending)
                put("entry_rate", ov.entryRate)
                put("win_rate", ov.winRate)
                put("pnl_inr", ov.pnlInr)
                put("pnl_usd", ov.pnlUsd)
            }
            call.respondJson(
                JSONObject().apply {
                    put("metrics", metricsObj)
                    put("notifications", ov.notifications)
                    put("parsed", ov.parsed)
                    put("today", ov.today)
                    put("target_hits", ov.targetHits)
                    put("stop_loss_hits", ov.stopLossHits)
                    put("pending", ov.pending)
                    put("entry_rate", ov.entryRate)
                    put("win_rate", ov.winRate)
                    put("pnl_inr", ov.pnlInr)
                    put("pnl_usd", ov.pnlUsd)
                    put("categories", JSONArray(ov.categories.map { JSONObject(mapOf("name" to it.first, "value" to it.second)) }))
                    put("channels", JSONArray(ov.channels.map { JSONObject(mapOf("name" to it.first, "value" to it.second)) }))
                    put("recent_notifications", JSONArray(ov.recentNotifications.map { rawNotifToJson(it) }))
                    put("recent_signals", JSONArray(ov.recentSignals.map { enrichedSignalToJson(it) }))
                    put("sync", ov.latestSync?.let { syncLogToJson(it) } ?: JSONObject.NULL)
                    put("latest_sync", ov.latestSync?.let { syncLogToJson(it) } ?: JSONObject.NULL)
                    put("integrations", JSONArray(ov.integrations.map { integrationToJson(it) }))
                }
            )
        }

        get("/signals") {
            if (!call.requireAuth()) return@get
            val qp = call.request.queryParameters
            val rows = repository.getSignals(
                category = qp["category"].orEmpty(),
                channel = qp["channel"].orEmpty(),
                symbol = qp["symbol"].orEmpty(),
                status = qp["status"].orEmpty(),
                notificationFilter = qp["notification_filter"] ?: "all",
                dateFrom = qp["date_from"].orEmpty(),
                dateTo = qp["date_to"].orEmpty(),
                tpFilter = qp["tp_filter"].orEmpty()
            )
            val basis = qp["pnl_basis"].orEmpty().ifBlank { "tp1" }
            call.respondJson(JSONArray(rows.map { enrichedSignalToJson(it, basis) }))
        }

        get("/signals/summary") {
            if (!call.requireAuth()) return@get
            val qp = call.request.queryParameters
            val rows = repository.getSignals(
                category = qp["category"].orEmpty(),
                channel = qp["channel"].orEmpty(),
                symbol = qp["symbol"].orEmpty(),
                status = qp["status"].orEmpty(),
                notificationFilter = qp["notification_filter"] ?: "primary",
                dateFrom = qp["date_from"].orEmpty(),
                dateTo = qp["date_to"].orEmpty(),
                tpFilter = qp["tp_filter"].orEmpty()
            )
            val basis = qp["pnl_basis"].orEmpty().ifBlank { "tp1" }
            val sum = repository.computeFilteredPnlSummary(rows, basis)
            call.respondJson(
                JSONObject().apply {
                    put("basis", sum.basis)
                    put("total_signals", sum.totalSignals)
                    put("tp1_hits", sum.tp1Hits)
                    put("tp2_hits", sum.tp2Hits)
                    put("tp3_hits", sum.tp3Hits)
                    put("all_tp_hits", sum.allTpHits)
                    put("tp_then_sl_hits", sum.tpThenSlHits)
                    put("sl_only_hits", sum.slOnlyHits)
                    put("active_monitoring", sum.activeMonitoring)
                    put("win_rate", sum.winRate)
                    put("pnl_inr", sum.pnlInr)
                    put("pnl_usd", sum.pnlUsd)
                }
            )
        }

        suspend fun handleFilters(call: ApplicationCall) {
            if (!call.requireAuth()) return
            val (cats, chans) = repository.getFilters(call.request.queryParameters["category"].orEmpty())
            call.respondJson(JSONObject().apply {
                put("categories", JSONArray(cats))
                put("channels", JSONArray(chans))
            })
        }
        get("/filters") { handleFilters(call) }
        get("/signals/filters") { handleFilters(call) }
        get("/notification-filters") { handleFilters(call) }
        get("/notifications/filters") { handleFilters(call) }

        post("/signals/process") {
            if (!call.requireAuth()) return@post
            val body = call.readJsonBody() ?: return@post call.respondJsonError(HttpStatusCode.BadRequest, "Invalid JSON body.")
            val arr = body.optJSONArray("signal_ids") ?: return@post call.respondJsonError(HttpStatusCode.BadRequest, "Select at least one signal to process.")
            val ids = (0 until arr.length()).mapNotNull { arr.optLong(it, -1L).takeIf { v -> v > 0 } }
            if (ids.isEmpty()) return@post call.respondJsonError(HttpStatusCode.BadRequest, "Select at least one signal to process.")
            val results = repository.verificationService.processSignalsBatch(ids, "manual")
            call.respondJson(
                JSONObject().apply {
                    put("processed", results.size)
                    put("results", JSONArray(results.map { r ->
                        JSONObject().apply {
                            put("signal_id", r.signalId)
                            put("status", r.status)
                            put("detail", r.detail)
                            put("candle_time", r.candleTime ?: JSONObject.NULL)
                            put("pnl_amount", r.pnlAmount)
                            put("pnl_currency", r.pnlCurrency)
                            put("trades_count", r.tradesCount)
                        }
                    }))
                }
            )
        }

        post("/signals/reparse") {
            if (!call.requireAuth()) return@post
            val body = call.readJsonBody() ?: return@post call.respondJsonError(HttpStatusCode.BadRequest, "Invalid JSON body.")
            val arr = body.optJSONArray("signal_ids") ?: return@post call.respondJsonError(HttpStatusCode.BadRequest, "Select at least one signal to re-parse.")
            val ids = (0 until arr.length()).mapNotNull { arr.optLong(it, -1L).takeIf { v -> v > 0 } }
            if (ids.isEmpty()) return@post call.respondJsonError(HttpStatusCode.BadRequest, "Select at least one signal to re-parse.")
            val results = ids.map { sid ->
                val p = repository.reparseSignal(sid, "batch_reparse")
                JSONObject().apply {
                    put("signal_id", sid)
                    put("status", p?.status ?: "missing")
                }
            }
            call.respondJson(JSONObject().put("results", JSONArray(results)))
        }

        post("/signals/delete") {
            if (!call.requireAuth()) return@post
            val body = call.readJsonBody() ?: return@post call.respondJsonError(HttpStatusCode.BadRequest, "Invalid JSON body.")
            val arr = body.optJSONArray("signal_ids") ?: return@post call.respondJsonError(HttpStatusCode.BadRequest, "Select at least one signal to delete.")
            val ids = (0 until arr.length()).mapNotNull { arr.optLong(it, -1L).takeIf { v -> v > 0 } }
            if (ids.isEmpty()) return@post call.respondJsonError(HttpStatusCode.BadRequest, "Select at least one signal to delete.")
            val deleted = repository.deleteSignals(ids)
            call.respondJson(JSONObject().put("deleted", deleted))
        }

        get("/signals/{id}") {
            if (!call.requireAuth()) return@get
            val id = call.parameters["id"]?.toLongOrNull()
                ?: return@get call.respondJsonError(HttpStatusCode.BadRequest, "Invalid signal id.")
            val enriched = repository.getSignalEnriched(id)
                ?: return@get call.respondJsonError(HttpStatusCode.NotFound, "Signal not found.")
            call.respondJson(enrichedSignalToJson(enriched))
        }

        suspend fun handleUpdateSignal(call: ApplicationCall) {
            if (!call.requireAuth()) return
            val id = call.parameters["id"]?.toLongOrNull()
                ?: return call.respondJsonError(HttpStatusCode.BadRequest, "Invalid signal id.")
            val body = call.readJsonBody() ?: return call.respondJsonError(HttpStatusCode.BadRequest, "Invalid JSON body.")

            val edit = SignalEditPayload(
                symbol = body.optNullableString("symbol"),
                hasSymbol = body.has("symbol"),
                direction = body.optNullableString("direction"),
                hasDirection = body.has("direction"),
                entryPrice = body.optNullableDouble("entry_price"),
                hasEntryPrice = body.has("entry_price"),
                additionalEntries = if (body.has("additional_entries")) parseDoubleListFlexible(body.opt("additional_entries")) else null,
                stopLoss = body.optNullableDouble("stop_loss"),
                hasStopLoss = body.has("stop_loss"),
                targets = if (body.has("targets")) parseDoubleListFlexible(body.opt("targets")) else null,
                optionType = body.optNullableString("option_type"),
                hasOptionType = body.has("option_type"),
                strikePrice = body.optNullableDouble("strike_price"),
                hasStrikePrice = body.has("strike_price"),
                expiryDate = body.optNullableString("expiry_date"),
                hasExpiryDate = body.has("expiry_date"),
                entryMode = body.optNullableString("entry_mode"),
                manualReviewStatus = body.optNullableString("manual_review_status"),
                notificationTime = body.optNullableString("notification_time"),
                verificationTimeframe = body.optNullableString("verification_timeframe"),
                reschedule = body.optBoolean("reschedule", false)
            )
            if (!repository.updateSignal(id, edit)) {
                return call.respondJsonError(HttpStatusCode.NotFound, "Signal not found.")
            }
            call.respondJson(JSONObject().put("status", "updated"))
        }
        put("/signals/{id}") { handleUpdateSignal(call) }
        post("/signals/{id}") { handleUpdateSignal(call) }

        delete("/signals/{id}") {
            if (!call.requireAuth()) return@delete
            val id = call.parameters["id"]?.toLongOrNull()
                ?: return@delete call.respondJsonError(HttpStatusCode.BadRequest, "Invalid signal id.")
            val deleted = repository.deleteSignal(id)
            if (deleted == 0) return@delete call.respondJsonError(HttpStatusCode.NotFound, "Signal not found.")
            call.respondJson(JSONObject().put("deleted", deleted))
        }

        post("/signals/{id}/reparse") {
            if (!call.requireAuth()) return@post
            val id = call.parameters["id"]?.toLongOrNull()
                ?: return@post call.respondJsonError(HttpStatusCode.BadRequest, "Invalid signal id.")
            val parsed = repository.reparseSignal(id, "manual_reparse")
                ?: return@post call.respondJsonError(HttpStatusCode.NotFound, "Signal not found.")
            call.respondJson(JsonUtils.parsedSignalToJsonObject(parsed))
        }

        post("/signals/{id}/verify") {
            if (!call.requireAuth()) return@post
            val id = call.parameters["id"]?.toLongOrNull()
                ?: return@post call.respondJsonError(HttpStatusCode.BadRequest, "Invalid signal id.")
            val res = repository.verificationService.processSignal(id, "manual")
            if (res.status == "missing") return@post call.respondJsonError(HttpStatusCode.NotFound, "Signal not found.")
            call.respondJson(
                JSONObject().apply {
                    put("signal_id", res.signalId)
                    put("status", res.status)
                    put("detail", res.detail)
                    put("candle_time", res.candleTime ?: JSONObject.NULL)
                    put("pnl_amount", res.pnlAmount)
                    put("pnl_currency", res.pnlCurrency)
                    put("trades_count", res.tradesCount)
                }
            )
        }

        get("/signals/{id}/parse-detail") {
            if (!call.requireAuth()) return@get
            val id = call.parameters["id"]?.toLongOrNull()
                ?: return@get call.respondJsonError(HttpStatusCode.BadRequest, "Invalid signal id.")
            val enriched = repository.getSignalEnriched(id)
                ?: return@get call.respondJsonError(HttpStatusCode.NotFound, "Signal not found.")
            val runsArr = JSONArray(repository.db.coreDao().getParsingRuns(id).map { r ->
                JSONObject().apply {
                    put("id", r.id)
                    put("signal_id", r.signalId)
                    put("run_type", r.runType)
                    put("parsed_at", r.parsedAt)
                    put("status", r.status)
                    put("confidence", r.confidence)
                    put("explanation", r.explanation ?: "")
                    put("snapshot_json", r.snapshotJson)
                }
            })
            call.respondJson(
                JSONObject().apply {
                    put("signal", enrichedSignalToJson(enriched))
                    put("history", runsArr)
                    put("parsing_runs", runsArr)
                }
            )
        }

        get("/signals/{id}/schedule") {
            if (!call.requireAuth()) return@get
            val id = call.parameters["id"]?.toLongOrNull()
                ?: return@get call.respondJsonError(HttpStatusCode.BadRequest, "Invalid signal id.")
            val sched = repository.db.verificationDao().getScheduleForSignal(id)
            val runs = repository.db.verificationDao().getVerificationRuns(id)
            call.respondJson(
                JSONObject().apply {
                    put("schedule", sched?.let { s ->
                        JSONObject().apply {
                            put("id", s.id)
                            put("signal_id", s.signalId)
                            put("signal_start_at", s.signalStartAt)
                            put("initial_wait_minutes", s.initialWaitMinutes)
                            put("interval_minutes", s.intervalMinutes)
                            put("timeframe", s.timeframe)
                            put("first_scheduled_at", s.firstScheduledAt)
                            put("next_scheduled_at", s.nextScheduledAt ?: JSONObject.NULL)
                            put("last_verified_at", s.lastVerifiedAt ?: JSONObject.NULL)
                            put("expires_at", s.expiresAt ?: JSONObject.NULL)
                            put("status", s.status)
                            put("last_result", s.lastResult ?: JSONObject.NULL)
                        }
                    } ?: JSONObject.NULL)
                    put("runs", JSONArray(runs.map { r ->
                        JSONObject().apply {
                            put("id", r.id)
                            put("run_type", r.runType)
                            put("started_at", r.startedAt)
                            put("finished_at", r.finishedAt ?: JSONObject.NULL)
                            put("status", r.status)
                            put("result", r.result ?: JSONObject.NULL)
                            put("detail", r.detail ?: JSONObject.NULL)
                            put("candle_time", r.candleTime ?: JSONObject.NULL)
                        }
                    }))
                }
            )
        }

        get("/signals/{id}/chart") {
            if (!call.requireAuth()) return@get
            val id = call.parameters["id"]?.toLongOrNull()
                ?: return@get call.respondJsonError(HttpStatusCode.BadRequest, "Invalid signal id.")
            val chart = repository.getSignalChart(id)
                ?: return@get call.respondJsonError(HttpStatusCode.NotFound, "Signal not found.")
            call.respondJson(
                JSONObject().apply {
                    put("signal_id", chart.signalId)
                    put("symbol", chart.symbol ?: JSONObject.NULL)
                    put("resolved_symbol", chart.resolvedSymbol)
                    put("trading_symbol", chart.tradingSymbol ?: JSONObject.NULL)
                    put("provider", chart.provider)
                    put("direction", chart.direction ?: JSONObject.NULL)
                    put("notification_time", chart.notificationTime)
                    put("entry_price", chart.entryPrice ?: JSONObject.NULL)
                    put("additional_entries", JSONArray(chart.additionalEntries))
                    put("entry_mode", chart.entryMode)
                    put("stop_loss", chart.stopLoss ?: JSONObject.NULL)
                    put("targets", JSONArray(chart.targets))
                    put("verification_status", chart.verificationStatus)
                    put("detail", chart.detail ?: "")
                    put("entry_hit_at", chart.entryHitAt ?: JSONObject.NULL)
                    put("target_hit_at", chart.targetHitAt ?: JSONObject.NULL)
                    put("stop_loss_hit_at", chart.stopLossHitAt ?: JSONObject.NULL)
                    put("events", JSONArray(JsonUtils.eventsToJson(chart.events)))
                    put("candles", JSONArray(chart.candles.map { c ->
                        JSONObject().apply {
                            put("candle_timestamp", c.candleTimestamp)
                            put("open", c.open)
                            put("high", c.high)
                            put("low", c.low)
                            put("close", c.close)
                            put("volume", c.volume)
                        }
                    }))
                }
            )
        }

        get("/notifications") {
            if (!call.requireAuth()) return@get
            val qp = call.request.queryParameters
            val limit = qp["limit"]?.toIntOrNull() ?: 100
            val afterId = qp["after_id"]?.toLongOrNull()
            val rows = repository.getNotifications(
                limit = limit,
                afterId = afterId,
                category = qp["category"].orEmpty(),
                channel = qp["channel"].orEmpty(),
                dateFrom = qp["date_from"].orEmpty(),
                dateTo = qp["date_to"].orEmpty(),
                parsingStatus = qp["parsing_status"].orEmpty(),
                notificationFilter = qp["notification_filter"] ?: "all"
            )
            call.respondJson(JSONArray(rows.map { notifJoinedToJson(it) }))
        }

        post("/import") {
            if (!call.requireAuth()) return@post
            val body = call.readJsonBody() ?: return@post call.respondJsonError(HttpStatusCode.BadRequest, "Invalid JSON body.")
            val rowsArr = body.optJSONArray("rows") ?: JSONArray().put(body)
            var imported = 0
            var duplicates = 0
            val rejected = mutableListOf<JSONObject>()
            for (i in 0 until rowsArr.length()) {
                val obj = rowsArr.optJSONObject(i)
                if (obj == null) {
                    rejected += JSONObject(mapOf("row" to i, "error" to "Row must be an object"))
                    continue
                }
                val map = linkedMapOf<String, String>()
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    map[k] = if (obj.isNull(k)) "" else obj.opt(k)?.toString().orEmpty()
                }
                val missing = SourceNotificationRow.SOURCE_COLUMNS.filter { it !in map }
                if (missing.isNotEmpty()) {
                    rejected += JSONObject(mapOf("row" to i, "error" to "Missing columns: ${missing.joinToString(", ")}"))
                    continue
                }
                val (_, st) = repository.ingestionService.ingest(SourceNotificationRow.fromMap(map))
                if (st == "imported") imported++ else duplicates++
            }
            call.respondJson(
                JSONObject().apply {
                    put("imported", imported)
                    put("duplicates", duplicates)
                    put("rejected", JSONArray(rejected))
                }
            )
        }

        post("/parse") {
            if (!call.requireAuth()) return@post
            val body = call.readJsonBody() ?: return@post call.respondJsonError(HttpStatusCode.BadRequest, "Invalid JSON body.")
            val message = body.optString("message", "")
            val category = body.optString("category", "")
            val rules = repository.getDefaultSlRules()
            val items = SignalParser.parseSignals(message, category, slRules = rules)
            val primaryObj = JsonUtils.parsedSignalToJsonObject(items.first())
            primaryObj.put("signals", JSONArray(items.map { JsonUtils.parsedSignalToJsonObject(it) }))
            primaryObj.put("signal_count", items.size)
            call.respondJson(primaryObj)
        }

        get("/settings") {
            if (!call.requireAuth()) return@get
            val obj = JSONObject()
            for ((k, v) in repository.getSettings()) obj.put(k, v)
            call.respondJson(obj)
        }

        suspend fun handleSaveSettings(call: ApplicationCall) {
            if (!call.requireAuth()) return
            val body = call.readJsonBody() ?: return call.respondJsonError(HttpStatusCode.BadRequest, "Invalid JSON body.")
            val map = linkedMapOf<String, String>()
            val keys = body.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                map[k] = body.opt(k)?.toString().orEmpty()
            }
            repository.saveSettings(map)
            call.respondJson(JSONObject().put("status", "saved"))
        }
        post("/settings") { handleSaveSettings(call) }
        put("/settings") { handleSaveSettings(call) }

        suspend fun handleGetDefaultSl(call: ApplicationCall) {
            if (!call.requireAuth()) return
            val rules = repository.getDefaultSlRules()
            call.respondJson(defaultSlRulesToDashboardJson(rules))
        }
        get("/default-sl") { handleGetDefaultSl(call) }
        get("/settings/default-sl") { handleGetDefaultSl(call) }

        suspend fun handleSaveDefaultSl(call: ApplicationCall) {
            if (!call.requireAuth()) return
            val body = call.readJsonBody() ?: return call.respondJsonError(HttpStatusCode.BadRequest, "Invalid JSON body.")
            val rawRules = body.optJSONObject("rules") ?: body
            val parsed = DefaultRulesEngine.rulesFromJson(rawRules.toString())
            repository.saveDefaultSlRules(parsed)
            call.respondJson(JSONObject().put("ok", true).put("status", "saved"))
        }
        put("/default-sl") { handleSaveDefaultSl(call) }
        post("/default-sl") { handleSaveDefaultSl(call) }
        put("/settings/default-sl") { handleSaveDefaultSl(call) }
        post("/settings/default-sl") { handleSaveDefaultSl(call) }

        suspend fun handleApplyDefaultSlMissing(call: ApplicationCall) {
            if (!call.requireAuth()) return
            val (checked, updated) = repository.applyDefaultRulesToMissing()
            call.respondJson(JSONObject().apply {
                put("ok", true)
                put("updated", updated)
                put("checked", checked)
            })
        }
        post("/default-sl/apply-missing") { handleApplyDefaultSlMissing(call) }
        post("/settings/default-sl/apply-missing") { handleApplyDefaultSlMissing(call) }

        get("/integrations") {
            if (!call.requireAuth()) return@get
            val rows = repository.db.coreDao().getAllIntegrations()
            call.respondJson(JSONArray(rows.map { integrationToJson(it) }))
        }

        suspend fun handleSaveIntegration(call: ApplicationCall) {
            if (!call.requireAuth()) return
            val provider = call.parameters["provider"].orEmpty().trim()
            if (provider !in SignalRepository.SUPPORTED_PROVIDERS) {
                return call.respondJsonError(HttpStatusCode.BadRequest, "Unsupported provider.")
            }
            val body = call.readJsonBody() ?: return call.respondJsonError(HttpStatusCode.BadRequest, "Invalid JSON body.")
            val configObj = body.optJSONObject("config") ?: JSONObject()
            val secretsObj = body.optJSONObject("secrets") ?: JSONObject()
            val secretsMap = linkedMapOf<String, String>()
            val sk = secretsObj.keys()
            while (sk.hasNext()) {
                val k = sk.next()
                val v = secretsObj.optString(k, "")
                if (v.isNotEmpty()) secretsMap[k] = v
            }
            repository.saveIntegration(provider, configObj.toString(), secretsMap)
            call.respondJson(JSONObject().put("ok", true).put("status", "saved").put("provider", provider))
        }
        post("/integrations/{provider}") { handleSaveIntegration(call) }
        put("/integrations/{provider}") { handleSaveIntegration(call) }

        delete("/integrations/{provider}") {
            if (!call.requireAuth()) return@delete
            val provider = call.parameters["provider"].orEmpty().trim()
            if (provider !in SignalRepository.SUPPORTED_PROVIDERS) {
                return@delete call.respondJsonError(HttpStatusCode.BadRequest, "Unsupported provider.")
            }
            repository.deleteIntegration(provider)
            call.respondJson(JSONObject().put("ok", true).put("status", "deleted").put("provider", provider))
        }

        post("/sync") {
            if (!call.requireAuth()) return@post
            val res = repository.syncClient.syncGoogleSheets()
            call.respondJson(
                JSONObject().apply {
                    put("status", res.status)
                    put("imported", res.imported)
                    put("duplicates", res.duplicates)
                    put("skipped", res.skipped)
                    put("failed", res.failed)
                    put("detail", res.detail)
                }
            )
        }

        get("/sync/logs") {
            if (!call.requireAuth()) return@get
            val logs = repository.db.coreDao().getSyncLogs()
            call.respondJson(JSONArray(logs.map { syncLogToJson(it) }))
        }

        post("/binance/test") {
            if (!call.requireAuth()) return@post
            try {
                val (count, detail) = repository.testBinance()
                call.respondJson(JSONObject().apply {
                    put("ok", true)
                    put("status", "connected")
                    put("candles", count)
                    put("sample_candles", count)
                    put("message", detail)
                    put("detail", detail)
                })
            } catch (e: ProviderError) {
                call.respondJsonError(HttpStatusCode.BadGateway, e.message ?: "Binance error")
            }
        }

        post("/candles/fetch") {
            if (!call.requireAuth()) return@post
            val body = call.readJsonBody() ?: return@post call.respondJsonError(HttpStatusCode.BadRequest, "Invalid JSON body.")
            val provider = body.optString("provider", "binance").trim()
            if (provider != "binance") {
                return@post call.respondJsonError(HttpStatusCode.BadRequest, "Only Binance public candles are fetched through this endpoint.")
            }
            val symbol = body.optString("symbol", "BTCUSDT").trim().uppercase(Locale.US)
            val timeframe = body.optString("timeframe", "1m").trim()
            val start = body.optString("start", "")
            val end = body.optString("end", "")
            try {
                val (fetched, stored) = repository.fetchManualBinanceCandles(symbol, timeframe, start, end)
                call.respondJson(JSONObject().put("fetched", fetched).put("downloaded", fetched).put("stored", stored))
            } catch (e: IllegalArgumentException) {
                call.respondJsonError(HttpStatusCode.BadRequest, e.message ?: "Invalid request")
            } catch (e: ProviderError) {
                call.respondJsonError(HttpStatusCode.BadGateway, e.message ?: "Provider error")
            }
        }

        post("/twelve-data/candles") {
            if (!call.requireAuth()) return@post
            val body = call.readJsonBody() ?: return@post call.respondJsonError(HttpStatusCode.BadRequest, "Invalid JSON body.")
            val symbol = body.optString("symbol", "EUR/USD").trim()
            val interval = body.optString("interval", "1min").trim()
            val start = body.optNullableString("start")
            val end = body.optNullableString("end")
            try {
                val (fetched, stored, credits) = repository.fetchManualTwelveDataCandles(symbol, interval, start, end)
                call.respondJson(JSONObject().apply {
                    put("fetched", fetched)
                    put("downloaded", fetched)
                    put("stored", stored)
                    put("credits_used", credits)
                })
            } catch (e: ProviderError) {
                call.respondJsonError(HttpStatusCode.BadGateway, e.message ?: "Twelve Data error")
            }
        }

        get("/twelve-data/usage") {
            if (!call.requireAuth()) return@get
            val usage = repository.getTwelveDataUsage()
            val obj = JSONObject()
            for ((k, v) in usage) obj.put(k, v)
            call.respondJson(obj)
        }

        post("/angel-one/test") {
            if (!call.requireAuth()) return@post
            try {
                val authAt = repository.testAngelOne()
                call.respondJson(JSONObject().put("ok", true).put("status", "connected").put("authenticated_at", authAt))
            } catch (e: ProviderError) {
                call.respondJsonError(HttpStatusCode.BadGateway, e.message ?: "Angel One auth error")
            }
        }

        post("/angel-one/instruments/refresh") {
            if (!call.requireAuth()) return@post
            try {
                val count = repository.refreshAngelInstrumentMaster()
                call.respondJson(JSONObject().put("ok", true).put("status", "refreshed").put("count", count).put("instruments", count))
            } catch (e: ProviderError) {
                call.respondJsonError(HttpStatusCode.BadGateway, e.message ?: "Angel master download failed")
            }
        }

        get("/angel-one/catalog") {
            if (!call.requireAuth()) return@get
            val qp = call.request.queryParameters
            val obj = repository.getAngelCatalogJson(
                exchange = qp["exchange"] ?: "NFO",
                instType = qp["instrument_type"] ?: "FUTIDX",
                underlying = qp["underlying"].orEmpty(),
                expiry = qp["expiry"].orEmpty(),
                q = qp["q"].orEmpty()
            )
            call.respondJson(obj)
        }

        get("/angel-one/instruments") {
            if (!call.requireAuth()) return@get
            val qp = call.request.queryParameters
            val rows = repository.searchAngelInstruments(qp["q"].orEmpty(), qp["exchange"].orEmpty())
            call.respondJson(JSONArray(rows.map { r ->
                JSONObject().apply {
                    put("id", r.id)
                    put("symbol", r.symbol ?: "")
                    put("trading_symbol", r.tradingSymbol)
                    put("instrument_token", r.instrumentToken)
                    put("exchange_segment", r.exchangeSegment)
                    put("instrument_type", r.instrumentType ?: "")
                    put("expiry", r.expiry ?: "")
                    put("strike", r.strike ?: 0.0)
                    put("lot_size", r.lotSize ?: 0.0)
                }
            }))
        }

        get("/angel-one/mappings") {
            if (!call.requireAuth()) return@get
            val rows = repository.db.marketDao().getAllInstrumentMappings()
            call.respondJson(JSONArray(rows.map { r ->
                JSONObject().apply {
                    put("id", r.id)
                    put("lookup_key", r.lookupKey)
                    put("instrument_token", r.instrumentToken)
                    put("exchange_segment", r.exchangeSegment)
                    put("note", r.note ?: "")
                    put("updated_at", r.updatedAt)
                }
            }))
        }

        post("/angel-one/mappings") {
            if (!call.requireAuth()) return@post
            val body = call.readJsonBody() ?: return@post call.respondJsonError(HttpStatusCode.BadRequest, "Invalid JSON body.")
            val lookupKey = body.optString("lookup_key", "").trim()
            val token = body.optString("instrument_token", "").trim()
            val exchange = body.optString("exchange_segment", "NFO").trim().uppercase(Locale.US)
            val note = body.optString("note", "").trim()
            if (lookupKey.isEmpty() || token.isEmpty()) {
                return@post call.respondJsonError(HttpStatusCode.BadRequest, "lookup_key and instrument_token are required.")
            }
            try {
                repository.saveAngelMapping(lookupKey, token, exchange, note)
                call.respondJson(JSONObject().put("status", "mapped"))
            } catch (e: IllegalArgumentException) {
                call.respondJsonError(HttpStatusCode.BadRequest, e.message ?: "Invalid mapping")
            }
        }

        post("/angel-one/candles") {
            if (!call.requireAuth()) return@post
            val body = call.readJsonBody() ?: return@post call.respondJsonError(HttpStatusCode.BadRequest, "Invalid JSON body.")
            val tok = body.optString("instrument_token", "").ifBlank { body.optString("symboltoken", "") }
            try {
                val (fetched, stored) = repository.fetchManualAngelCandles(
                    exchange = body.optString("exchange", "NFO"),
                    token = tok,
                    rawInterval = body.optString("interval", "ONE_MINUTE"),
                    startIso = body.optString("start", ""),
                    endIso = body.optString("end", "")
                )
                call.respondJson(JSONObject().put("fetched", fetched).put("downloaded", fetched).put("stored", stored))
            } catch (e: IllegalArgumentException) {
                call.respondJsonError(HttpStatusCode.BadRequest, e.message ?: "Invalid request")
            } catch (e: ProviderError) {
                call.respondJsonError(HttpStatusCode.BadGateway, e.message ?: "Angel One error")
            }
        }

        get("/market/coverage") {
            if (!call.requireAuth()) return@get
            val rows = repository.db.marketDao().getMarketCoverage()
            call.respondJson(JSONArray(rows.map { r ->
                JSONObject().apply {
                    put("provider", r.provider)
                    put("symbol", r.symbol)
                    put("exchange", r.exchange)
                    put("interval", r.interval)
                    put("candles", r.candles)
                    put("earliest", r.earliest ?: "")
                    put("latest", r.latest ?: "")
                    put("first_candle", r.earliest ?: "")
                    put("last_candle", r.latest ?: "")
                    put("last_retrieved", r.lastRetrieved ?: r.latest ?: "")
                }
            }))
        }

        delete("/market/cache") {
            if (!call.requireAuth()) return@delete
            val provider = call.request.queryParameters["provider"].orEmpty().trim()
            val symbol = call.request.queryParameters["symbol"].orEmpty().trim()
            if (provider.isEmpty() || symbol.isEmpty()) {
                return@delete call.respondJsonError(HttpStatusCode.BadRequest, "provider and symbol are required.")
            }
            val deleted = repository.deleteMarketCache(provider, symbol)
            call.respondJson(JSONObject().put("deleted", deleted))
        }

        get("/analytics/channels") {
            if (!call.requireAuth()) return@get
            val qp = call.request.queryParameters
            val rows = repository.getFilteredChannelAnalytics(
                category = qp["category"].orEmpty(),
                channel = qp["channel"].orEmpty(),
                dateFrom = qp["date_from"].orEmpty(),
                dateTo = qp["date_to"].orEmpty(),
                pnlBasis = qp["pnl_basis"].orEmpty().ifBlank { "tp1" }
            )
            call.respondJson(JSONArray(rows.map { r ->
                JSONObject().apply {
                    put("channel", r.channel)
                    put("total", r.total)
                    put("today_total", r.todayTotal)
                    put("today_tp", r.todayTp)
                    put("today_sl", r.todaySl)
                    put("parsed", r.parsed)
                    put("trades_triggered", r.tradesTriggered)
                    put("targets", r.targets)
                    put("tp1_hits", r.tp1Hits)
                    put("tp2_hits", r.tp2Hits)
                    put("tp3_hits", r.tp3Hits)
                    put("all_tp_hits", r.allTpHits)
                    put("tp_then_sl_hits", r.tpThenSlHits)
                    put("stops", r.stops)
                    put("pending", r.pending)
                    put("win_rate", r.winRate)
                    put("pnl_inr", r.pnlInr)
                    put("pnl_usd", r.pnlUsd)
                    put("today_pnl_inr", r.todayPnlInr)
                    put("today_pnl_usd", r.todayPnlUsd)
                    put("pnl_basis", r.pnlBasis)
                }
            }))
        }

        get("/api-health") {
            if (!call.requireAuth()) return@get
            val health = repository.getApiHealthSummary()
            val usageObj = JSONObject()
            for ((k, v) in health.twelveDataUsage) usageObj.put(k, v)
            call.respondJson(
                JSONObject().apply {
                    put("overall_status", health.overallStatus)
                    put("active_alerts", JSONArray(health.activeAlerts.map { a ->
                        JSONObject().apply {
                            put("provider", a.provider)
                            put("severity", a.severity)
                            put("category", a.category)
                            put("http_code", a.httpCode ?: JSONObject.NULL)
                            put("message", a.message)
                            put("occurred_at", a.occurredAt)
                        }
                    }))
                    put("providers", JSONArray(health.providers.map { p ->
                        JSONObject().apply {
                            put("provider", p.provider)
                            put("display_name", p.displayName)
                            put("status", p.status)
                            put("calls_24h", p.calls24h)
                            put("errors_24h", p.errors24h)
                            put("rate_limit_hits_24h", p.rateLimitHits24h)
                            put("last_success_at", p.lastSuccessAt ?: JSONObject.NULL)
                            put("last_error_at", p.lastErrorAt ?: JSONObject.NULL)
                            put("last_error_category", p.lastErrorCategory ?: JSONObject.NULL)
                            put("last_http_code", p.lastHttpCode ?: JSONObject.NULL)
                            put("last_error_message", p.lastErrorMessage ?: JSONObject.NULL)
                        }
                    }))
                    put("twelve_data_usage", usageObj)
                    put("recent_incidents", JSONArray(health.recentIncidents.map { inc ->
                        JSONObject().apply {
                            put("id", inc.id)
                            put("provider", inc.provider)
                            put("endpoint", inc.endpoint)
                            put("requested_at", inc.requestedAt)
                            put("status", inc.status)
                            put("http_code", inc.httpCode ?: JSONObject.NULL)
                            put("error_category", inc.errorCategory)
                            put("error", inc.error ?: "")
                        }
                    }))
                }
            )
        }

        get("/logs") {
            if (!call.requireAuth()) return@get
            val logs = repository.db.coreDao().getRecentSystemLogs()
            call.respondJson(JSONArray(logs.map { l ->
                JSONObject().apply {
                    put("id", l.id)
                    put("created_at", l.createdAt)
                    put("severity", l.severity)
                    put("source", l.source)
                    put("message", l.message)
                }
            }))
        }

        get("/export/{kind}") {
            if (!call.requireAuth()) return@get
            val rawKind = call.parameters["kind"].orEmpty().removeSuffix(".csv")
            try {
                val (filename, csv) = repository.exportCsv(rawKind)
                call.addSecurityHeaders(isApi = true)
                call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"$filename\"")
                call.respondText(csv, ContentType.Text.CSV)
            } catch (e: IllegalArgumentException) {
                call.respondJsonError(HttpStatusCode.NotFound, e.message ?: "Unknown export kind")
            }
        }

        post("/backup") {
            if (!call.requireAuth()) return@post
            try {
                val (name, detail) = repository.createLocalBackup()
                call.respondJson(JSONObject().apply {
                    put("status", "created")
                    put("backup", name)
                    put("detail", detail)
                })
            } catch (e: Exception) {
                call.respondJsonError(HttpStatusCode.InternalServerError, e.message ?: "Backup failed")
            }
        }
    }

    private fun defaultSlRulesToDashboardJson(rules: Map<String, DefaultSlCategoryRule>): JSONObject {
        data class CatMeta(val key: String, val title: String, val unit: String, val unitLabel: String, val sizingMode: String)
        val metas = listOf(
            CatMeta("OPTIONS", "Index Options", "points", "Option Premium Points (₹)", "lots"),
            CatMeta("STOCK_OPTIONS", "Stock Options", "points", "Option Premium Points (₹)", "lots"),
            CatMeta("MCX", "MCX Commodities (Futures & Options)", "points", "MCX Points (₹)", "lots"),
            CatMeta("FOREX", "Forex & Global Commodities", "pips", "Pips", "lots"),
            CatMeta("CRYPTO", "Crypto (USD-M Futures)", "pct", "Percentage (%)", "usd")
        )
        val catArr = JSONArray()
        for (m in metas) {
            val block = rules[m.key] ?: DefaultSlCategoryRule()
            val symArr = JSONArray()
            for ((sk, sv) in block.symbols) {
                val label = when {
                    sk.endsWith(":OPT") -> "${sk.removeSuffix(":OPT")} (Options CE/PE)"
                    sk.endsWith(":FUT") -> "${sk.removeSuffix(":FUT")} (Futures)"
                    else -> sk
                }
                val hint = if (m.unit == "pips") "pip=${DefaultRulesEngine.forexPipSize(sk)}" else m.unit
                symArr.put(
                    JSONObject().apply {
                        put("key", sk)
                        put("label", label)
                        put("hint", hint)
                        put("sl", sv.sl ?: "")
                        put("tp1", sv.tp1 ?: "")
                        put("tp2", sv.tp2 ?: "")
                        put("tp3", sv.tp3 ?: "")
                        put("lots", sv.lots ?: "")
                        put("lot_size", sv.lotSize ?: "")
                        put("usd_amount", sv.usdAmount ?: "")
                    }
                )
            }
            catArr.put(
                JSONObject().apply {
                    put("key", m.key)
                    put("title", m.title)
                    put("unit", m.unit)
                    put("unit_label", m.unitLabel)
                    put("sizing_mode", m.sizingMode)
                    put("category_default", block.categoryDefault ?: "")
                    put("tp1", block.tp1 ?: "")
                    put("tp2", block.tp2 ?: "")
                    put("tp3", block.tp3 ?: "")
                    put("lots", block.lots)
                    put("lot_size", block.lotSize ?: "")
                    put("usd_amount", block.usdAmount)
                    put("symbols", symArr)
                }
            )
        }
        return JSONObject().apply {
            put("categories", catArr)
            put("rules", JSONObject(DefaultRulesEngine.rulesToJson(rules)))
        }
    }

    private suspend fun ApplicationCall.requireAuth(): Boolean {
        val remote = request.origin.remoteAddress
        if (!authManager.isAllowedLanAddress(remote)) {
            respondJsonError(HttpStatusCode.Forbidden, "Access restricted to private LAN addresses.")
            return false
        }
        if (authManager.isRateLimited(remote)) {
            respondJsonError(HttpStatusCode.TooManyRequests, "Too many failed authentication attempts. Wait 60 seconds.")
            return false
        }
        if (!authManager.isValidCsrfHeaders(
                request.httpMethod.value,
                request.header(HttpHeaders.Origin),
                request.header(HttpHeaders.Host),
                request.header("X-Requested-With")
            )
        ) {
            respondJsonError(HttpStatusCode.Forbidden, "CSRF validation failed.")
            return false
        }

        val authHeader = request.header(HttpHeaders.Authorization).orEmpty()
        val token = if (authHeader.startsWith("Bearer ", ignoreCase = true)) {
            authHeader.substring(7).trim()
        } else {
            ""
        }
        if (!authManager.verifyAnyToken(token)) {
            authManager.recordAuthFailure(remote)
            respondJsonError(HttpStatusCode.Unauthorized, "Authentication required.")
            return false
        }
        authManager.clearAuthFailures(remote)
        return true
    }

    private fun ApplicationCall.addSecurityHeaders(isApi: Boolean) {
        response.header("X-Content-Type-Options", "nosniff")
        response.header("X-Frame-Options", "DENY")
        response.header("Referrer-Policy", "no-referrer")
        if (isApi) {
            response.header(HttpHeaders.CacheControl, "no-store")
        }
    }

    private suspend fun ApplicationCall.readJsonBody(): JSONObject? = try {
        val raw = receiveText()
        if (raw.isBlank()) JSONObject() else JSONObject(raw)
    } catch (_: Exception) {
        null
    }

    private suspend fun ApplicationCall.respondJson(obj: JSONObject, status: HttpStatusCode = HttpStatusCode.OK) {
        addSecurityHeaders(isApi = true)
        respondText(obj.toString(), ContentType.Application.Json, status)
    }

    private suspend fun ApplicationCall.respondJson(arr: JSONArray, status: HttpStatusCode = HttpStatusCode.OK) {
        addSecurityHeaders(isApi = true)
        respondText(arr.toString(), ContentType.Application.Json, status)
    }

    private suspend fun ApplicationCall.respondJsonError(status: HttpStatusCode, message: String) {
        addSecurityHeaders(isApi = true)
        respondText(JSONObject().put("error", message).toString(), ContentType.Application.Json, status)
    }

    private fun integrationToJson(entity: IntegrationEntity): JSONObject {
        val secretFields = repository.secretStore.getProviderSecretFieldNames(entity.provider)
        val cfgObj = try {
            JSONObject(entity.configJson)
        } catch (_: Exception) {
            JSONObject()
        }
        return JSONObject().apply {
            put("provider", entity.provider)
            put("config", cfgObj)
            put("status", entity.status)
            put("updated_at", entity.updatedAt)
            put("secret_fields", JSONArray(secretFields))
            put("has_secrets", secretFields.isNotEmpty())
        }
    }

    private fun rawNotifToJson(r: RawNotificationEntity): JSONObject = JSONObject().apply {
        put("id", r.id)
        put("received_at", r.receivedAt ?: "")
        put("notification_time", r.notificationTime ?: "")
        put("source_app", r.sourceApp ?: "")
        put("channel", r.channel ?: "")
        put("category", r.category ?: "")
        put("title", r.title ?: "")
        put("message", r.message ?: "")
        put("notification_id", r.notificationId ?: "")
        put("is_duplicate", r.isDuplicate)
        put("duplicate_of", r.duplicateOf ?: JSONObject.NULL)
        put("duplicate_reason", r.duplicateReason ?: JSONObject.NULL)
    }

    private fun syncLogToJson(s: com.example.data.local.SynchronizationLogEntity): JSONObject = JSONObject().apply {
        put("id", s.id)
        put("started_at", s.startedAt)
        put("finished_at", s.finishedAt ?: JSONObject.NULL)
        put("provider", s.provider)
        put("status", s.status)
        put("imported_rows", s.importedRows)
        put("duplicate_rows", s.duplicateRows)
        put("skipped_rows", s.skippedRows)
        put("failed_rows", s.failedRows)
        put("detail", s.detail ?: "")
    }

    private fun enrichedSignalToJson(s: EnrichedSignal, pnlBasis: String = "tp1"): JSONObject {
        val r = s.row
        return JSONObject().apply {
            put("id", r.id)
            put("raw_notification_id", r.rawNotificationId)
            put("symbol", r.symbol ?: JSONObject.NULL)
            put("direction", r.direction ?: JSONObject.NULL)
            put("entry_price", r.entryPrice ?: JSONObject.NULL)
            put("additional_entries", JSONArray(s.additionalEntries))
            put("stop_loss", r.stopLoss ?: JSONObject.NULL)
            put("targets", JSONArray(s.targets))
            put("option_type", r.optionType ?: JSONObject.NULL)
            put("strike_price", r.strikePrice ?: JSONObject.NULL)
            put("expiry_date", r.expiryDate ?: JSONObject.NULL)
            put("entry_mode", r.entryMode)
            put("parsing_status", r.parsingStatus)
            put("parsing_confidence", r.parsingConfidence)
            put("manual_review_status", r.manualReviewStatus)
            put("verification_status", r.verificationStatus)
            put("final_result", r.finalResult ?: JSONObject.NULL)
            put("parsing_explanation", r.parsingExplanation ?: "")
            put("updated_at", r.updatedAt)
            put("received_at", r.receivedAt ?: "")
            put("notification_time", r.notificationTime ?: "")
            put("source_app", r.sourceApp ?: "")
            put("channel", r.channel ?: "")
            put("category", r.category ?: "")
            put("title", r.title ?: "")
            put("message", r.message ?: "")
            put("notification_id", r.notificationId ?: "")
            put("is_duplicate", r.isDuplicate)
            put("duplicate_of", r.duplicateOf ?: JSONObject.NULL)
            put("duplicate_reason", r.duplicateReason ?: JSONObject.NULL)
            put("duplicate_group_id", r.duplicateGroupId ?: JSONObject.NULL)
            put("first_scheduled_at", r.firstScheduledAt ?: JSONObject.NULL)
            put("next_scheduled_at", r.nextScheduledAt ?: JSONObject.NULL)
            put("last_verified_at", r.lastVerifiedAt ?: JSONObject.NULL)
            put("last_result", r.lastResult ?: JSONObject.NULL)
            put("scheduler_status", r.schedulerStatus ?: JSONObject.NULL)
            put("verification_timeframe", r.verificationTimeframe ?: "1m")
            put("entry_hit_at", r.entryHitAt ?: JSONObject.NULL)
            put("target_hit_at", r.targetHitAt ?: JSONObject.NULL)
            put("stop_loss_hit_at", r.stopLossHitAt ?: JSONObject.NULL)
            put("events", JSONArray(JsonUtils.eventsToJson(s.events)))
            put("legs", JSONArray(JsonUtils.legsToJson(s.legs)))
            put("trade_detail", r.tradeDetail ?: JSONObject.NULL)
            put("pnl_amount", s.pnlAmount)
            put("basis_pnl_amount", s.pnlForBasis(pnlBasis))
            put("pnl_currency", s.pnlCurrency)
            put("trades_count", s.tradesCount)
            put("max_tp_hit", s.maxTpHit)
            put("all_targets_hit", s.allTargetsHit)
            put("closed_by_sl_after_tp", s.closedBySlAfterTp)
            put("tp1_pnl_amount", s.tp1PnlAmount)
            put("tp2_pnl_amount", s.tp2PnlAmount)
            put("tp3_pnl_amount", s.tp3PnlAmount)
            put("highest_tp_pnl_amount", s.highestTpPnlAmount)
            put("sl_pnl_amount", s.slPnlAmount)
            put("tp_levels", JSONArray(JsonUtils.tpLevelsToJson(s.tpLevels)))
            put("monitoring_phase", s.monitoringPhase)
            put("age_minutes", s.ageMinutes)
            put("is_auto_scheduled", s.isAutoScheduled)
            put("is_recent", s.isRecent)
            put("is_manual_needed", s.isManualNeeded)
            put("is_historical", s.isHistorical)
            put("is_default_sl", s.isDefaultSl)
            put("is_default_tp", s.isDefaultTp)
            put("status_reason", s.statusReason)
        }
    }

    private fun notifJoinedToJson(n: NotificationJoinedRow): JSONObject = JSONObject().apply {
        put("id", n.id)
        put("received_at", n.receivedAt ?: "")
        put("notification_time", n.notificationTime ?: "")
        put("source_app", n.sourceApp ?: "")
        put("package", n.packageName ?: "")
        put("channel", n.channel ?: "")
        put("category", n.category ?: "")
        put("title", n.title ?: "")
        put("message", n.message ?: "")
        put("notification_id", n.notificationId ?: "")
        put("delivery_id", n.deliveryId ?: "")
        put("imported_at", n.importedAt)
        put("is_duplicate", n.isDuplicate)
        put("duplicate_of", n.duplicateOf ?: JSONObject.NULL)
        put("duplicate_reason", n.duplicateReason ?: JSONObject.NULL)
        put("duplicate_group_id", n.duplicateGroupId ?: JSONObject.NULL)
        put("signal_id", n.signalId ?: JSONObject.NULL)
        put("symbol", n.symbol ?: JSONObject.NULL)
        put("direction", n.direction ?: JSONObject.NULL)
        put("entry_price", n.entryPrice ?: JSONObject.NULL)
        put("additional_entries", JSONArray(JsonUtils.jsonToDoubleList(n.additionalEntries)))
        put("stop_loss", n.stopLoss ?: JSONObject.NULL)
        put("targets", JSONArray(JsonUtils.jsonToDoubleList(n.targets)))
        put("option_type", n.optionType ?: JSONObject.NULL)
        put("strike_price", n.strikePrice ?: JSONObject.NULL)
        put("expiry_date", n.expiryDate ?: JSONObject.NULL)
        put("entry_mode", n.entryMode ?: "limit")
        put("parsing_status", n.parsingStatus ?: "unparsed")
        put("manual_review_status", n.manualReviewStatus ?: "not_required")
        put("parsing_confidence", n.parsingConfidence ?: 0.0)
        put("parsing_explanation", n.parsingExplanation ?: "")
        put("verification_status", n.verificationStatus ?: "pending")
    }

    private fun eventToJson(ev: AppEvent): JSONObject = JSONObject().apply {
        put("type", ev.type)
        put("entity_id", ev.entityId ?: JSONObject.NULL)
        put("status", ev.status)
        put("detail", ev.detail ?: JSONObject.NULL)
        put("timestamp", ev.timestamp)
    }

    private fun parseDoubleListFlexible(raw: Any?): List<Double> = when (raw) {
        is JSONArray -> (0 until raw.length()).mapNotNull { raw.opt(it)?.toString()?.trim()?.toDoubleOrNull() }
        is String -> raw.split(",").mapNotNull { it.trim().toDoubleOrNull() }
        else -> emptyList()
    }

    private fun JSONObject.optNullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key, "").takeIf { it.isNotBlank() }

    private fun JSONObject.optNullableDouble(key: String): Double? =
        if (!has(key) || isNull(key)) null else opt(key)?.toString()?.trim()?.toDoubleOrNull()
}
