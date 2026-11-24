package com.example.service

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import com.example.data.local.AppDatabase
import com.example.data.local.ApplicationSettingEntity
import com.example.data.local.SystemLogEntity
import com.example.util.TimeUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

enum class HealthBucketStatus(val code: Int) {
    UNKNOWN(0),
    ACTIVE(1),
    BUSY(2),
    STALLED(3),
    STOPPED(4);

    companion object {
        fun fromCode(code: Int): HealthBucketStatus = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

data class HealthMinuteBucket(
    val minuteEpoch: Long, // epochMs / 60_000L
    val status: HealthBucketStatus,
    val maxTickDurationMs: Long = 0L,
    val gapSeconds: Long = 0L
)

data class BackgroundIncident(
    val id: Long,
    val kind: String, // os_freeze_gap | process_killed | slow_tick | user_stopped | service_started | worker_catchup | task_swiped
    val startedAtMs: Long,
    val endedAtMs: Long,
    val durationSec: Long,
    val detail: String
)

data class BackgroundHealthSnapshot(
    val loaded: Boolean = false,
    val expectedRunning: Boolean = false,
    val lastHeartbeatMs: Long? = null,
    val lastSchedulerTickMs: Long? = null,
    val lastSchedulerDurationMs: Long = 0L,
    val isSchedulerBusy: Boolean = false,
    val currentWorkLabel: String? = null,
    val liveStallSeconds: Long = 0L,
    val uptime24hPct: Double = 100.0,
    val activeMinutes24h: Long = 0L,
    val downtimeSeconds24h: Long = 0L,
    val longestGapSeconds24h: Long = 0L,
    val outageCount24h: Int = 0,
    val buckets: List<HealthMinuteBucket> = emptyList(),
    val incidents: List<BackgroundIncident> = emptyList(),
    val lastOsExitReason: String? = null,
    val ignoringBatteryOptimizations: Boolean = false
)

/**
 * Tracks foreground service heartbeats, scheduler tick latency, OS freezes / Doze pauses,
 * and unexpected process kills. Persists a compact 24-hour rolling ring buffer in Room's
 * existing `app_settings` table (no schema migration required) and logs major incidents to
 * `system_logs`.
 */
class BackgroundHealthTracker(
    private val context: Context?,
    private val db: AppDatabase?,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val exitReasonProvider: (Long, Long) -> String? = { fromMs, toMs ->
        queryAndroidExitReason(context, fromMs, toMs)
    },
    private val batteryOptProvider: () -> Boolean = {
        context?.let { NetworkUtils.isIgnoringBatteryOptimizations(it) } ?: true
    }
) {
    private val mutex = Mutex()
    private var initialized = false
    private var expectedRunning = false
    private var lastAliveMs: Long = 0L
    private var lastSchedulerTickMs: Long? = null
    private var lastSchedulerDurationMs: Long = 0L
    private var isSchedulerBusy: Boolean = false
    private var currentWorkLabel: String? = null
    private var lastPersistedMinute: Long = -1L
    private var nextIncidentId: Long = 1L
    private var lastOsExitReason: String? = null

    private val bucketsByMinute = LinkedHashMap<Long, HealthMinuteBucket>()
    private val incidents = ArrayDeque<BackgroundIncident>()

    private val _snapshot = MutableStateFlow(BackgroundHealthSnapshot())
    val snapshot: StateFlow<BackgroundHealthSnapshot> = _snapshot.asStateFlow()

    suspend fun ensureLoaded() {
        mutex.withLock {
            loadLocked()
            refreshSnapshotLocked(clock())
        }
    }

    suspend fun refreshNow() {
        mutex.withLock {
            loadLocked()
            refreshSnapshotLocked(clock())
        }
    }

    suspend fun onServiceStarted(dedicated: Boolean, lanEnabled: Boolean) {
        val now = clock()
        val modeLabel = buildList {
            if (dedicated) add("Dedicated Processing")
            if (lanEnabled) add("LAN Dashboard")
        }.joinToString(" + ").ifEmpty { "Foreground Service" }

        mutex.withLock {
            loadLocked()
            if (expectedRunning && lastAliveMs > 0L && (now - lastAliveMs) > GAP_THRESHOLD_MS) {
                val gapSec = (now - lastAliveMs) / 1000L
                val osReason = exitReasonProvider(lastAliveMs - 5_000L, now + 5_000L)
                if (!osReason.isNullOrBlank()) {
                    lastOsExitReason = osReason
                }
                fillStalledBucketsLocked(lastAliveMs, now, gapSec)
                val detail = buildString {
                    append("Service restarted after unexpected downtime of ")
                    append(formatDurationShort(gapSec))
                    if (!osReason.isNullOrBlank()) {
                        append(" — Android OS reason: ")
                        append(osReason)
                    } else {
                        append(" (OS killed process or device slept)")
                    }
                }
                addIncidentLocked(
                    kind = KIND_PROCESS_KILLED,
                    startedAtMs = lastAliveMs,
                    endedAtMs = now,
                    durationSec = gapSec,
                    detail = detail
                )
                logSystemLocked("ERROR", detail)
            } else {
                addIncidentLocked(
                    kind = KIND_SERVICE_STARTED,
                    startedAtMs = now,
                    endedAtMs = now,
                    durationSec = 0L,
                    detail = "Started: $modeLabel"
                )
            }

            expectedRunning = true
            lastAliveMs = now
            upsertBucketLocked(now / 60_000L, HealthBucketStatus.ACTIVE, tickMs = 0L, gapSec = 0L)
            pruneLocked(now)
            persistLocked(now, force = true)
            refreshSnapshotLocked(now)
        }
    }

    suspend fun recordHeartbeat(busy: Boolean = false, workLabel: String? = null) {
        val now = clock()
        mutex.withLock {
            loadLocked()
            var detectedIncident = false
            if (expectedRunning && lastAliveMs > 0L) {
                val gapMs = now - lastAliveMs
                if (gapMs > GAP_THRESHOLD_MS) {
                    val gapSec = gapMs / 1000L
                    fillStalledBucketsLocked(lastAliveMs, now, gapSec)
                    val detail = "Background loop paused for ${formatDurationShort(gapSec)} " +
                        "(${formatClock(lastAliveMs)} → ${formatClock(now)}) — likely Doze / OEM background freeze"
                    addIncidentLocked(
                        kind = KIND_OS_FREEZE_GAP,
                        startedAtMs = lastAliveMs,
                        endedAtMs = now,
                        durationSec = gapSec,
                        detail = detail
                    )
                    logSystemLocked("WARNING", detail)
                    detectedIncident = true
                }
            }

            expectedRunning = true
            lastAliveMs = now
            isSchedulerBusy = busy
            currentWorkLabel = workLabel
            val status = if (busy) HealthBucketStatus.BUSY else HealthBucketStatus.ACTIVE
            upsertBucketLocked(now / 60_000L, status, tickMs = 0L, gapSec = 0L)
            pruneLocked(now)
            persistLocked(now, force = detectedIncident)
            refreshSnapshotLocked(now)
        }
    }

    suspend fun setSchedulerBusy(busy: Boolean, workLabel: String? = null) {
        val now = clock()
        mutex.withLock {
            loadLocked()
            isSchedulerBusy = busy
            currentWorkLabel = if (busy) workLabel else null
            if (busy && expectedRunning) {
                upsertBucketLocked(now / 60_000L, HealthBucketStatus.BUSY, tickMs = 0L, gapSec = 0L)
            }
            refreshSnapshotLocked(now)
        }
    }

    suspend fun onSchedulerTickCompleted(durationMs: Long) {
        val now = clock()
        mutex.withLock {
            loadLocked()
            lastSchedulerTickMs = now
            lastSchedulerDurationMs = durationMs
            isSchedulerBusy = false
            currentWorkLabel = null
            val bucketStatus = if (durationMs >= SLOW_TICK_THRESHOLD_MS) {
                HealthBucketStatus.BUSY
            } else {
                HealthBucketStatus.ACTIVE
            }
            upsertBucketLocked(now / 60_000L, bucketStatus, tickMs = durationMs, gapSec = 0L)
            var forcePersist = false
            if (durationMs >= SLOW_TICK_THRESHOLD_MS) {
                val durSec = durationMs / 1000L
                val detail = "Verification tick took ${formatDurationShort(durSec)} (API/network latency, service remained alive)"
                addIncidentLocked(
                    kind = KIND_SLOW_TICK,
                    startedAtMs = max(0L, now - durationMs),
                    endedAtMs = now,
                    durationSec = durSec,
                    detail = detail
                )
                logSystemLocked("WARNING", detail)
                forcePersist = true
            }
            pruneLocked(now)
            persistLocked(now, force = forcePersist)
            refreshSnapshotLocked(now)
        }
    }

    suspend fun onServiceStoppedByUser() {
        val now = clock()
        mutex.withLock {
            loadLocked()
            expectedRunning = false
            isSchedulerBusy = false
            currentWorkLabel = null
            lastAliveMs = now
            upsertBucketLocked(now / 60_000L, HealthBucketStatus.STOPPED, tickMs = 0L, gapSec = 0L)
            addIncidentLocked(
                kind = KIND_USER_STOPPED,
                startedAtMs = now,
                endedAtMs = now,
                durationSec = 0L,
                detail = "Stopped by user"
            )
            pruneLocked(now)
            persistLocked(now, force = true)
            refreshSnapshotLocked(now)
        }
    }

    suspend fun onTaskRemoved() {
        val now = clock()
        mutex.withLock {
            loadLocked()
            if (expectedRunning) {
                addIncidentLocked(
                    kind = KIND_TASK_SWIPED,
                    startedAtMs = now,
                    endedAtMs = now,
                    durationSec = 0L,
                    detail = "App swiped from Recents while service was active (some OEMs freeze services after swipe)"
                )
                logSystemLocked("INFO", "App removed from Recents while Dedicated Processing was active")
                persistLocked(now, force = true)
                refreshSnapshotLocked(now)
            }
        }
    }

    suspend fun onServiceDestroyedUnexpectedly() {
        val now = clock()
        mutex.withLock {
            loadLocked()
            if (expectedRunning) {
                lastAliveMs = now
                logSystemLocked("WARNING", "Foreground service destroyed by Android while active — awaiting START_STICKY restart")
                persistLocked(now, force = true)
                refreshSnapshotLocked(now)
            }
        }
    }

    suspend fun onReconciliationWorkerRun(isForegroundRunning: Boolean) {
        val now = clock()
        mutex.withLock {
            loadLocked()
            if (!isForegroundRunning && expectedRunning && lastAliveMs > 0L) {
                val gapSec = max(0L, (now - lastAliveMs) / 1000L)
                if (gapSec >= GAP_THRESHOLD_MS / 1000L) {
                    fillStalledBucketsLocked(lastAliveMs, now, gapSec)
                    val detail = "WorkManager catch-up ran while foreground service was down (${formatDurationShort(gapSec)} since last heartbeat)"
                    addIncidentLocked(
                        kind = KIND_WORKER_CATCHUP,
                        startedAtMs = lastAliveMs,
                        endedAtMs = now,
                        durationSec = gapSec,
                        detail = detail
                    )
                    logSystemLocked("WARNING", detail)
                    persistLocked(now, force = true)
                    refreshSnapshotLocked(now)
                }
            }
        }
    }

    suspend fun clearHistory() {
        val now = clock()
        mutex.withLock {
            loadLocked()
            bucketsByMinute.clear()
            incidents.clear()
            lastOsExitReason = null
            if (expectedRunning) {
                lastAliveMs = now
                upsertBucketLocked(now / 60_000L, HealthBucketStatus.ACTIVE, tickMs = 0L, gapSec = 0L)
            }
            persistLocked(now, force = true)
            refreshSnapshotLocked(now)
        }
    }

    fun exportStateJson(): String {
        val sb = StringBuilder(2048)
        sb.append("v1|")
            .append(if (expectedRunning) 1 else 0).append('|')
            .append(lastAliveMs).append('|')
            .append(lastSchedulerTickMs ?: -1L).append('|')
            .append(lastSchedulerDurationMs).append('|')
            .append(nextIncidentId).append('|')
            .append(escapeField(lastOsExitReason.orEmpty()))
            .append('\n')

        for (b in bucketsByMinute.values) {
            sb.append("B|")
                .append(b.minuteEpoch).append('|')
                .append(b.status.code).append('|')
                .append(b.maxTickDurationMs).append('|')
                .append(b.gapSeconds)
                .append('\n')
        }
        for (inc in incidents) {
            sb.append("I|")
                .append(inc.id).append('|')
                .append(escapeField(inc.kind)).append('|')
                .append(inc.startedAtMs).append('|')
                .append(inc.endedAtMs).append('|')
                .append(inc.durationSec).append('|')
                .append(escapeField(inc.detail))
                .append('\n')
        }
        return sb.toString()
    }

    fun importStateJson(raw: String) {
        try {
            val lines = raw.lineSequence().filter { it.isNotBlank() }.toList()
            if (lines.isEmpty()) return
            val header = splitEscaped(lines.first())
            if (header.size < 7 || header[0] != "v1") return

            expectedRunning = header[1] == "1"
            lastAliveMs = header[2].toLongOrNull() ?: 0L
            lastSchedulerTickMs = header[3].toLongOrNull()?.takeIf { it >= 0L }
            lastSchedulerDurationMs = header[4].toLongOrNull() ?: 0L
            nextIncidentId = (header[5].toLongOrNull() ?: 1L).coerceAtLeast(1L)
            lastOsExitReason = unescapeField(header[6]).takeIf { it.isNotBlank() }

            bucketsByMinute.clear()
            incidents.clear()

            for (i in 1 until lines.size) {
                val parts = splitEscaped(lines[i])
                when (parts.firstOrNull()) {
                    "B" -> if (parts.size >= 5) {
                        val minEpoch = parts[1].toLongOrNull() ?: continue
                        val st = HealthBucketStatus.fromCode(parts[2].toIntOrNull() ?: 0)
                        val tickMs = parts[3].toLongOrNull() ?: 0L
                        val gapSec = parts[4].toLongOrNull() ?: 0L
                        if (minEpoch > 0L) {
                            bucketsByMinute[minEpoch] = HealthMinuteBucket(minEpoch, st, tickMs, gapSec)
                        }
                    }
                    "I" -> if (parts.size >= 7) {
                        incidents.addLast(
                            BackgroundIncident(
                                id = parts[1].toLongOrNull() ?: nextIncidentId++,
                                kind = unescapeField(parts[2]),
                                startedAtMs = parts[3].toLongOrNull() ?: 0L,
                                endedAtMs = parts[4].toLongOrNull() ?: 0L,
                                durationSec = parts[5].toLongOrNull() ?: 0L,
                                detail = unescapeField(parts[6])
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun escapeField(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '|' -> sb.append("\\p")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun unescapeField(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    '\\' -> sb.append('\\')
                    'p' -> sb.append('|')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    else -> sb.append(s[i + 1])
                }
                i += 2
            } else {
                sb.append(ch)
                i += 1
            }
        }
        return sb.toString()
    }

    private fun splitEscaped(line: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            if (ch == '|') {
                out.add(cur.toString())
                cur.setLength(0)
            } else {
                cur.append(ch)
            }
            i += 1
        }
        out.add(cur.toString())
        return out
    }

    private suspend fun loadLocked() {
        if (initialized) return
        initialized = true
        val raw = try {
            db?.coreDao()?.getSetting(SETTINGS_KEY)
        } catch (_: Exception) {
            null
        }
        if (!raw.isNullOrBlank()) {
            importStateJson(raw)
        }
        if (lastOsExitReason == null) {
            val now = clock()
            lastOsExitReason = exitReasonProvider(now - WINDOW_24H_MS, now)
        }
    }

    private fun upsertBucketLocked(
        minuteEpoch: Long,
        newStatus: HealthBucketStatus,
        tickMs: Long,
        gapSec: Long
    ) {
        val existing = bucketsByMinute[minuteEpoch]
        if (existing == null) {
            bucketsByMinute[minuteEpoch] = HealthMinuteBucket(minuteEpoch, newStatus, tickMs, gapSec)
            return
        }
        // Preserve higher-severity status within the same minute so stalls/outages are never masked by a normal tick in the same minute.
        val mergedStatus = when {
            existing.status == HealthBucketStatus.STALLED || newStatus == HealthBucketStatus.STALLED -> HealthBucketStatus.STALLED
            newStatus == HealthBucketStatus.STOPPED -> HealthBucketStatus.STOPPED
            existing.status == HealthBucketStatus.BUSY || newStatus == HealthBucketStatus.BUSY -> HealthBucketStatus.BUSY
            else -> newStatus
        }
        bucketsByMinute[minuteEpoch] = HealthMinuteBucket(
            minuteEpoch = minuteEpoch,
            status = mergedStatus,
            maxTickDurationMs = max(existing.maxTickDurationMs, tickMs),
            gapSeconds = max(existing.gapSeconds, gapSec)
        )
    }

    private fun fillStalledBucketsLocked(fromMs: Long, toMs: Long, totalGapSec: Long) {
        val startMin = (fromMs / 60_000L)
        val endMin = (toMs / 60_000L)
        val clampedStart = max(startMin, endMin - MAX_BUCKETS)
        for (m in clampedStart..endMin) {
            upsertBucketLocked(m, HealthBucketStatus.STALLED, tickMs = 0L, gapSec = totalGapSec)
        }
    }

    private fun addIncidentLocked(
        kind: String,
        startedAtMs: Long,
        endedAtMs: Long,
        durationSec: Long,
        detail: String
    ) {
        incidents.addFirst(
            BackgroundIncident(
                id = nextIncidentId++,
                kind = kind,
                startedAtMs = startedAtMs,
                endedAtMs = endedAtMs,
                durationSec = durationSec,
                detail = detail
            )
        )
        while (incidents.size > MAX_INCIDENTS) {
            incidents.removeLast()
        }
    }

    private fun pruneLocked(nowMs: Long) {
        val cutoffMin = (nowMs - WINDOW_24H_MS) / 60_000L
        val it = bucketsByMinute.entries.iterator()
        while (it.hasNext()) {
            if (it.next().key < cutoffMin) it.remove()
        }
    }

    private suspend fun persistLocked(nowMs: Long, force: Boolean) {
        val currentMin = nowMs / 60_000L
        if (!force && currentMin == lastPersistedMinute) return
        lastPersistedMinute = currentMin
        val dao = db?.coreDao() ?: return
        try {
            dao.upsertSetting(
                ApplicationSettingEntity(
                    key = SETTINGS_KEY,
                    value = exportStateJson(),
                    updatedAt = TimeUtils.utcNowIso()
                )
            )
        } catch (_: Exception) {
        }
    }

    private suspend fun logSystemLocked(severity: String, message: String) {
        val dao = db?.coreDao() ?: return
        try {
            dao.insertSystemLog(
                SystemLogEntity(
                    createdAt = TimeUtils.utcNowIso(),
                    severity = severity,
                    source = "bg_watchdog",
                    message = message
                )
            )
        } catch (_: Exception) {
        }
    }

    private fun refreshSnapshotLocked(nowMs: Long) {
        val cutoffMs = nowMs - WINDOW_24H_MS
        val cutoffMin = cutoffMs / 60_000L
        val recentBuckets = bucketsByMinute.values
            .filter { it.minuteEpoch >= cutoffMin }
            .sortedBy { it.minuteEpoch }

        val liveStallSec = if (expectedRunning && lastAliveMs > 0L && (nowMs - lastAliveMs) > GAP_THRESHOLD_MS) {
            (nowMs - lastAliveMs) / 1000L
        } else {
            0L
        }

        val recentOutages = incidents.filter { inc ->
            inc.endedAtMs >= cutoffMs && (inc.kind == KIND_OS_FREEZE_GAP || inc.kind == KIND_PROCESS_KILLED)
        }
        val outageCount = recentOutages.size + if (liveStallSec > 0L) 1 else 0
        val downtimeSec = recentOutages.sumOf { it.durationSec } + liveStallSec
        val longestGapSec = max(recentOutages.maxOfOrNull { it.durationSec } ?: 0L, liveStallSec)

        val activeMins = recentBuckets.count { it.status == HealthBucketStatus.ACTIVE || it.status == HealthBucketStatus.BUSY }.toLong()
        val stalledMins = recentBuckets.count { it.status == HealthBucketStatus.STALLED }.toLong() + (liveStallSec / 60L)
        val totalExpectedSec = activeMins * 60L + downtimeSec
        val uptimePct = if (totalExpectedSec <= 0L) {
            100.0
        } else {
            val healthySec = max(0L, totalExpectedSec - downtimeSec)
            min(100.0, max(0.0, (healthySec.toDouble() / totalExpectedSec.toDouble()) * 100.0))
        }

        _snapshot.value = BackgroundHealthSnapshot(
            loaded = true,
            expectedRunning = expectedRunning,
            lastHeartbeatMs = lastAliveMs.takeIf { it > 0L },
            lastSchedulerTickMs = lastSchedulerTickMs,
            lastSchedulerDurationMs = lastSchedulerDurationMs,
            isSchedulerBusy = isSchedulerBusy,
            currentWorkLabel = currentWorkLabel,
            liveStallSeconds = liveStallSec,
            uptime24hPct = uptimePct,
            activeMinutes24h = activeMins,
            downtimeSeconds24h = downtimeSec,
            longestGapSeconds24h = longestGapSec,
            outageCount24h = outageCount,
            buckets = recentBuckets,
            incidents = incidents.toList(),
            lastOsExitReason = lastOsExitReason,
            ignoringBatteryOptimizations = batteryOptProvider()
        )
    }

    companion object {
        const val SETTINGS_KEY = "bg_health_state_v1"
        const val HEARTBEAT_INTERVAL_MS = 15_000L
        const val GAP_THRESHOLD_MS = 45_000L
        const val SLOW_TICK_THRESHOLD_MS = 30_000L
        const val WINDOW_24H_MS = 24L * 60L * 60L * 1000L
        const val MAX_BUCKETS = 1440
        const val MAX_INCIDENTS = 100

        const val KIND_OS_FREEZE_GAP = "os_freeze_gap"
        const val KIND_PROCESS_KILLED = "process_killed"
        const val KIND_SLOW_TICK = "slow_tick"
        const val KIND_USER_STOPPED = "user_stopped"
        const val KIND_SERVICE_STARTED = "service_started"
        const val KIND_WORKER_CATCHUP = "worker_catchup"
        const val KIND_TASK_SWIPED = "task_swiped"

        private val clockFmt = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US)

        fun formatClock(epochMs: Long): String {
            return try {
                clockFmt.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))
            } catch (_: Exception) {
                epochMs.toString()
            }
        }

        fun formatDurationShort(totalSeconds: Long): String {
            val s = max(0L, totalSeconds)
            val h = s / 3600L
            val m = (s % 3600L) / 60L
            val remS = s % 60L
            return when {
                h > 0L -> "${h}h ${m}m"
                m > 0L -> "${m}m ${remS}s"
                else -> "${remS}s"
            }
        }

        fun queryAndroidExitReason(context: Context?, fromMs: Long, toMs: Long): String? {
            if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
            return try {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
                val reasons = am.getHistoricalProcessExitReasons(context.packageName, 0, 10)
                val match = reasons.firstOrNull { info ->
                    info.timestamp in fromMs..toMs && info.reason != ApplicationExitInfo.REASON_EXIT_SELF
                } ?: reasons.firstOrNull { info ->
                    info.reason != ApplicationExitInfo.REASON_EXIT_SELF &&
                        info.reason != ApplicationExitInfo.REASON_USER_REQUESTED &&
                        info.timestamp >= fromMs - WINDOW_24H_MS
                } ?: return null

                val reasonName = when (match.reason) {
                    ApplicationExitInfo.REASON_ANR -> "ANR (App Not Responding)"
                    ApplicationExitInfo.REASON_CRASH -> "App Crash"
                    ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native Crash"
                    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "Excessive Resource Usage (OEM/OS)"
                    ApplicationExitInfo.REASON_FREEZER -> "App Freezer (Cached/OEM Freeze)"
                    ApplicationExitInfo.REASON_LOW_MEMORY -> "Low Memory Killer (LMK)"
                    ApplicationExitInfo.REASON_USER_REQUESTED -> "User Closed / Swiped"
                    ApplicationExitInfo.REASON_USER_STOPPED -> "User Force-Stopped"
                    ApplicationExitInfo.REASON_OTHER -> "OS Terminated (Other)"
                    ApplicationExitInfo.REASON_SIGNALED -> "OS Signal Kill"
                    ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "Init Failure"
                    ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "Permission Changed"
                    ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "Dependency Died"
                    else -> "Code ${match.reason}"
                }
                val desc = match.description?.trim()?.take(100).orEmpty()
                val ts = formatClock(match.timestamp)
                if (desc.isNotBlank()) "$reasonName ($desc) at $ts" else "$reasonName at $ts"
            } catch (_: Exception) {
                null
            }
        }
    }
}
