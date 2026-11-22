package com.example.data.provider

import com.example.data.local.ApiRequestLogEntity
import com.example.data.local.AppDatabase
import com.example.data.local.ApplicationSettingEntity
import com.example.data.local.SecretStore
import com.example.data.local.SynchronizationLogEntity
import com.example.data.local.SystemLogEntity
import com.example.data.repository.EventBus
import com.example.data.repository.SignalIngestionService
import com.example.domain.model.AppEvent
import com.example.domain.model.SourceNotificationRow
import com.example.util.JsonUtils
import com.example.util.TimeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.TimeUnit

data class SyncResult(
    val status: String,
    val imported: Int,
    val duplicates: Int,
    val skipped: Int,
    val failed: Int,
    val detail: String
)

class GoogleSheetsSyncClient(
    private val db: AppDatabase,
    private val secretStore: SecretStore,
    private val ingestionService: SignalIngestionService,
    private val eventBus: EventBus,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()
) {

    suspend fun syncGoogleSheets(): SyncResult = withContext(Dispatchers.IO) {
        val core = db.coreDao()
        val integration = core.getIntegration("google_sheets")
        val logId = core.insertSyncLog(
            SynchronizationLogEntity(
                startedAt = TimeUtils.utcNowIso(),
                provider = "google_sheets",
                status = "running"
            )
        )

        var imported = 0
        var duplicates = 0
        var skipped = 0
        var failed = 0
        var status = "success"
        var detail: String

        var lastHttpCode: Int? = null
        var errorCategory = "ok"
        val reqStartIso = TimeUtils.utcNowIso()

        try {
            if (integration == null) {
                errorCategory = "auth_error"
                throw IllegalArgumentException("Google Sheets endpoint is not configured; demo mode does not fabricate data.")
            }
            val config = try {
                JSONObject(integration.configJson)
            } catch (_: Exception) {
                JSONObject()
            }
            val urlStr = config.optString("apps_script_url", "").trim()
            if (urlStr.isEmpty()) {
                errorCategory = "auth_error"
                throw IllegalArgumentException("Apps Script URL is not configured.")
            }
            val parsedUrl = urlStr.toHttpUrlOrNull() ?: throw IllegalArgumentException("Apps Script URL is invalid.")
            if (parsedUrl.scheme != "https" && parsedUrl.host !in setOf("localhost", "127.0.0.1")) {
                throw IllegalArgumentException("Apps Script URL must use HTTPS.")
            }

            val token = secretStore.getProviderSecrets("google_sheets")["access_token"].orEmpty()
            val reqBuilder = Request.Builder()
                .url(parsedUrl)
                .header("User-Agent", "SignalAnalyzer/1.0")
                .get()
            if (token.isNotBlank()) {
                reqBuilder.header("Authorization", "Bearer $token")
            }

            val bodyStr = client.newCall(reqBuilder.build()).execute().use { resp ->
                lastHttpCode = resp.code
                val txt = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    errorCategory = ProviderErrorClassifier.classify(resp.code, txt)
                    throw IllegalStateException("Apps Script HTTP ${resp.code}")
                }
                txt
            }

            val parsedAny = JSONTokener(bodyStr).nextValue()
            val payload = parsedAny as? JSONObject
                ?: throw IllegalArgumentException(
                    "Unsupported endpoint response. Safe response shape: ${JsonUtils.payloadShape(parsedAny)}"
                )

            val worksheets: JSONObject
            val endpointCursor: String?
            val sourceFormat: String

            if (payload.optJSONObject("data") != null) {
                if (payload.has("ok") && !payload.optBoolean("ok", true)) {
                    val reason = payload.optString("error", "no error detail supplied").take(500)
                    throw IllegalArgumentException("Apps Script reported an unsuccessful response: $reason")
                }
                worksheets = payload.getJSONObject("data")
                endpointCursor = if (payload.has("next_since_ms") && !payload.isNull("next_since_ms")) {
                    payload.opt("next_since_ms")?.toString()
                } else null
                sourceFormat = "apps_script_data"
            } else if (payload.optJSONObject("worksheets") != null) {
                worksheets = payload.getJSONObject("worksheets")
                endpointCursor = null
                sourceFormat = "worksheets"
            } else {
                throw IllegalArgumentException(
                    "Unsupported endpoint response. Safe response shape: ${JsonUtils.payloadShape(payload)}"
                )
            }

            val colMapping = config.optJSONObject("column_mapping")
            val sheetNames = worksheets.keys()
            while (sheetNames.hasNext()) {
                val sheetName = sheetNames.next()
                val wsRows = worksheets.opt(sheetName)
                if (wsRows !is JSONArray) {
                    skipped++
                    continue
                }
                for (i in 0 until wsRows.length()) {
                    val rawObj = wsRows.optJSONObject(i)
                    if (rawObj == null) {
                        skipped++
                        continue
                    }
                    val translated: Map<String, String> = when {
                        sourceFormat == "apps_script_data" -> translateAppsScriptRow(rawObj)
                        colMapping != null && colMapping.length() > 0 -> {
                            val m = linkedMapOf<String, String>()
                            val mk = colMapping.keys()
                            while (mk.hasNext()) {
                                val target = mk.next()
                                val src = colMapping.optString(target)
                                if (rawObj.has(src)) m[target] = optStringSafe(rawObj, src)
                            }
                            m
                        }
                        else -> {
                            val m = linkedMapOf<String, String>()
                            val rk = rawObj.keys()
                            while (rk.hasNext()) {
                                val k = rk.next()
                                m[k] = optStringSafe(rawObj, k)
                            }
                            m
                        }
                    }

                    if (!SourceNotificationRow.SOURCE_COLUMNS.all { it in translated }) {
                        skipped++
                        continue
                    }

                    try {
                        val row = SourceNotificationRow.fromMap(translated)
                        val (_, resStatus) = ingestionService.ingest(row)
                        if (resStatus == "imported") imported++
                        else if (resStatus == "duplicate") duplicates++
                    } catch (_: Exception) {
                        failed++
                    }
                }
            }

            if (endpointCursor != null) {
                core.upsertSetting(
                    ApplicationSettingEntity("google_sheets_next_since_ms", endpointCursor, TimeUtils.utcNowIso())
                )
            }
            status = "success"
            detail = "Completed $sourceFormat import." +
                if (endpointCursor != null) " Endpoint cursor saved: $endpointCursor." else ""
        } catch (e: Exception) {
            status = "failed"
            detail = ProviderErrorClassifier.sanitize(e.message ?: "Sync failed").take(500)
            if (errorCategory == "ok") {
                errorCategory = ProviderErrorClassifier.classify(lastHttpCode, detail, e is java.io.IOException)
            }
        }

        val now = TimeUtils.utcNowIso()
        core.finishSyncLog(logId, now, status, imported, duplicates, skipped, failed, detail)
        db.marketDao().insertApiRequestLog(
            ApiRequestLogEntity(
                provider = "google_sheets",
                endpoint = "/exec",
                requestedAt = reqStartIso,
                completedAt = now,
                status = if (status == "success") "success" else "error",
                creditsUsed = 0.0,
                candlesReturned = imported,
                error = if (status == "success") null else detail,
                httpCode = lastHttpCode ?: if (status == "success") 200 else null,
                errorCategory = if (status == "success") "ok" else errorCategory
            )
        )
        core.insertSystemLog(
            SystemLogEntity(
                createdAt = now,
                severity = if (status == "success") "INFO" else "ERROR",
                source = "sync",
                message = detail.take(1000)
            )
        )
        eventBus.emit(AppEvent("sync.completed", logId, status, detail, now))
        if (status != "success") {
            eventBus.emit(AppEvent("api.health.changed", logId, errorCategory, "google_sheets: $detail", now))
        }
        SyncResult(status, imported, duplicates, skipped, failed, detail)
    }

    private fun optStringSafe(obj: JSONObject, key: String): String {
        if (!obj.has(key) || obj.isNull(key)) return ""
        return obj.opt(key)?.toString().orEmpty()
    }

    companion object {
        val APPS_SCRIPT_COLUMNS: Map<String, String> = linkedMapOf(
            "Received At" to "received_at",
            "Notification Time" to "notification_time",
            "Source App" to "source_app",
            "Package" to "package",
            "Channel" to "channel",
            "Category" to "category",
            "Title" to "title",
            "Message" to "message",
            "Notification ID" to "notification_id",
            "Delivery ID" to "delivery_id"
        )

        fun translateAppsScriptRow(row: JSONObject): Map<String, String> {
            val out = linkedMapOf<String, String>()
            for ((col, field) in APPS_SCRIPT_COLUMNS) {
                val value = if (row.has(field) && !row.isNull(field)) row.opt(field)?.toString().orEmpty() else ""
                out[col] = value
            }
            return out
        }
    }
}
