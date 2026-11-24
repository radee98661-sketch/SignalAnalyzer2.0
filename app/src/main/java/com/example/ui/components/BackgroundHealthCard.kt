package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.service.BackgroundHealthSnapshot
import com.example.service.BackgroundHealthTracker
import com.example.service.BackgroundIncident
import com.example.service.HealthBucketStatus
import com.example.service.HealthMinuteBucket
import com.example.ui.theme.AmberContainer
import com.example.ui.theme.AmberWarn
import com.example.ui.theme.CoralContainer
import com.example.ui.theme.CoralGlow
import com.example.ui.theme.CoralRed
import com.example.ui.theme.EmeraldAccent
import com.example.ui.theme.EmeraldContainer
import com.example.ui.theme.MonoFontFamily
import com.example.ui.theme.NavyBorder
import com.example.ui.theme.NavyBorderActive
import com.example.ui.theme.NavyCardElevated
import com.example.ui.theme.NavyWell
import com.example.ui.theme.SignalBlue
import com.example.ui.theme.SignalBlueContainer
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max

private enum class HealthWindow(val label: String, val totalMinutes: Int, val slotMinutes: Int) {
    ONE_HOUR("1H", 60, 1),
    SIX_HOURS("6H", 360, 5),
    TWENTY_FOUR_HOURS("24H", 1440, 15)
}

private data class TimelineSlot(
    val startMs: Long,
    val endMs: Long,
    val status: HealthBucketStatus,
    val maxGapSec: Long,
    val maxTickMs: Long
)

private val slotTimeFmt = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
private val incidentTimeFmt = DateTimeFormatter.ofPattern("dd MMM HH:mm:ss", Locale.US)

private fun fmtSlotTime(ms: Long): String = try {
    slotTimeFmt.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
} catch (_: Exception) {
    "—"
}

private fun fmtIncidentTime(ms: Long): String = try {
    incidentTimeFmt.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
} catch (_: Exception) {
    "—"
}

private fun buildTimelineSlots(
    nowMs: Long,
    window: HealthWindow,
    buckets: List<HealthMinuteBucket>,
    expectedRunning: Boolean,
    lastHeartbeatMs: Long?
): List<TimelineSlot> {
    val slotCount = window.totalMinutes / window.slotMinutes
    val bucketMap = buckets.associateBy { it.minuteEpoch }
    val endMin = nowMs / 60_000L
    val startMin = endMin - window.totalMinutes + 1
    val liveStallStartMin = if (expectedRunning && lastHeartbeatMs != null && (nowMs - lastHeartbeatMs) > BackgroundHealthTracker.GAP_THRESHOLD_MS) {
        lastHeartbeatMs / 60_000L
    } else {
        Long.MAX_VALUE
    }

    return List(slotCount) { idx ->
        val slotStartMin = startMin + idx.toLong() * window.slotMinutes
        val slotEndMin = slotStartMin + window.slotMinutes - 1
        var merged = HealthBucketStatus.UNKNOWN
        var maxGap = 0L
        var maxTick = 0L

        for (m in slotStartMin..slotEndMin) {
            val b = bucketMap[m]
            val st = when {
                b != null -> b.status
                m in liveStallStartMin..endMin -> HealthBucketStatus.STALLED
                else -> HealthBucketStatus.UNKNOWN
            }
            if (b != null) {
                maxGap = max(maxGap, b.gapSeconds)
                maxTick = max(maxTick, b.maxTickDurationMs)
            }
            merged = when {
                merged == HealthBucketStatus.STALLED || st == HealthBucketStatus.STALLED -> HealthBucketStatus.STALLED
                merged == HealthBucketStatus.BUSY || st == HealthBucketStatus.BUSY -> HealthBucketStatus.BUSY
                merged == HealthBucketStatus.ACTIVE || st == HealthBucketStatus.ACTIVE -> HealthBucketStatus.ACTIVE
                merged == HealthBucketStatus.STOPPED || st == HealthBucketStatus.STOPPED -> HealthBucketStatus.STOPPED
                else -> merged
            }
        }
        TimelineSlot(
            startMs = slotStartMin * 60_000L,
            endMs = (slotEndMin + 1) * 60_000L,
            status = merged,
            maxGapSec = maxGap,
            maxTickMs = maxTick
        )
    }
}

@Composable
fun BackgroundHealthCard(
    snapshot: BackgroundHealthSnapshot,
    isServiceRunning: Boolean,
    onRefresh: () -> Unit,
    onClearHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedWindow by remember { mutableStateOf(HealthWindow.SIX_HOURS) }
    var selectedSlotIndex by remember { mutableIntStateOf(-1) }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    // Keep heartbeat age & live stall indicator fresh every 5 seconds while screen is visible.
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(5_000L)
        }
    }

    val hbAgeSec = snapshot.lastHeartbeatMs?.let { max(0L, (nowMs - it) / 1000L) }
    val isLiveStalled = snapshot.expectedRunning && hbAgeSec != null && hbAgeSec * 1000L > BackgroundHealthTracker.GAP_THRESHOLD_MS

    val (liveBadgeText, liveBg, liveFg) = when {
        isLiveStalled -> Triple("STALLED • ${BackgroundHealthTracker.formatDurationShort(hbAgeSec ?: 0L)}", CoralContainer, CoralRed)
        !isServiceRunning && !snapshot.expectedRunning -> Triple("STOPPED", AmberContainer, AmberWarn)
        !isServiceRunning && snapshot.expectedRunning -> Triple("INTERRUPTED", CoralContainer, CoralRed)
        snapshot.isSchedulerBusy -> Triple("BUSY • VERIFYING", SignalBlueContainer, SignalBlue)
        hbAgeSec != null -> Triple("HEALTHY • ${hbAgeSec}s ago", EmeraldContainer, EmeraldAccent)
        else -> Triple("ACTIVE", EmeraldContainer, EmeraldAccent)
    }

    val slots = remember(nowMs / 15_000L, selectedWindow, snapshot.buckets, snapshot.expectedRunning, snapshot.lastHeartbeatMs) {
        buildTimelineSlots(nowMs, selectedWindow, snapshot.buckets, snapshot.expectedRunning, snapshot.lastHeartbeatMs)
    }
    val inspectedSlot = slots.getOrNull(selectedSlotIndex)

    SectionCard(
        modifier = modifier.testTag("background_health_card"),
        title = "Background Activity & Uptime Plot",
        subtitle = "Tracks 15s heartbeats, detects Doze/OEM freezes (>45s gap) and OS kills with exact timestamps.",
        action = {
            StatusPill(text = liveBadgeText, bg = liveBg, fg = liveFg)
        }
    ) {
        // 4 KPI Mini-Tiles
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val uptimeColor = when {
                snapshot.uptime24hPct >= 98.0 -> EmeraldAccent
                snapshot.uptime24hPct >= 90.0 -> AmberWarn
                else -> CoralRed
            }
            HealthKpiTile(
                label = "24h Uptime",
                value = String.format(Locale.US, "%.1f%%", snapshot.uptime24hPct),
                accent = uptimeColor,
                modifier = Modifier.weight(1f)
            )
            HealthKpiTile(
                label = "Stalls / Kills",
                value = snapshot.outageCount24h.toString(),
                accent = if (snapshot.outageCount24h == 0) EmeraldAccent else CoralRed,
                modifier = Modifier.weight(1f)
            )
            HealthKpiTile(
                label = "Downtime",
                value = BackgroundHealthTracker.formatDurationShort(snapshot.downtimeSeconds24h),
                accent = if (snapshot.downtimeSeconds24h == 0L) TextPrimary else CoralRed,
                modifier = Modifier.weight(1f)
            )
            HealthKpiTile(
                label = "OS Battery",
                value = if (snapshot.ignoringBatteryOptimizations) "Unrestr." else "Optimiz.",
                accent = if (snapshot.ignoringBatteryOptimizations) EmeraldAccent else AmberWarn,
                modifier = Modifier.weight(1f)
            )
        }

        // Window Selector + Refresh / Clear
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                HealthWindow.entries.forEach { w ->
                    val selected = w == selectedWindow
                    Surface(
                        color = if (selected) SignalBlueContainer else NavyWell,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, if (selected) SignalBlue else NavyBorder),
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                selectedWindow = w
                                selectedSlotIndex = -1
                            }
                            .testTag("health_window_${w.label.lowercase()}")
                    ) {
                        Text(
                            text = w.label,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontFamily = MonoFontFamily,
                                fontWeight = FontWeight.Bold
                            ),
                            color = if (selected) SignalBlue else TextSecondary,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = {
                        nowMs = System.currentTimeMillis()
                        onRefresh()
                    },
                    modifier = Modifier.minimumInteractiveComponentSize().testTag("refresh_bg_health_button")
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh background health", tint = TextSecondary, modifier = Modifier.size(18.dp))
                }
                IconButton(
                    onClick = {
                        selectedSlotIndex = -1
                        onClearHistory()
                    },
                    modifier = Modifier.minimumInteractiveComponentSize().testTag("clear_bg_health_button")
                ) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = "Clear uptime history", tint = TextSecondary, modifier = Modifier.size(18.dp))
                }
            }
        }

        // Interactive HUD bar (either hovered slot or live heartbeat info)
        Surface(
            color = NavyWell,
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, if (inspectedSlot?.status == HealthBucketStatus.STALLED) CoralRed.copy(alpha = 0.6f) else NavyBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (inspectedSlot != null) {
                    val (stLabel, stColor) = when (inspectedSlot.status) {
                        HealthBucketStatus.ACTIVE -> "ACTIVE" to EmeraldAccent
                        HealthBucketStatus.BUSY -> "BUSY / SLOW TICK" to SignalBlue
                        HealthBucketStatus.STALLED -> "STALLED / GAP" to CoralRed
                        HealthBucketStatus.STOPPED -> "STOPPED" to AmberWarn
                        HealthBucketStatus.UNKNOWN -> "NO DATA" to TextMuted
                    }
                    Text(
                        text = "${fmtSlotTime(inspectedSlot.startMs)}–${fmtSlotTime(inspectedSlot.endMs)} • $stLabel",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily, fontWeight = FontWeight.Bold),
                        color = stColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    val detailMetric = when {
                        inspectedSlot.maxGapSec > 0L -> "Gap ${BackgroundHealthTracker.formatDurationShort(inspectedSlot.maxGapSec)}"
                        inspectedSlot.maxTickMs > 0L -> "Tick ${inspectedSlot.maxTickMs}ms"
                        else -> "Tap chart"
                    }
                    Text(
                        text = detailMetric,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                        color = TextSecondary
                    )
                } else {
                    val hbStr = snapshot.lastHeartbeatMs?.let { fmtIncidentTime(it) } ?: "Not started yet"
                    val tickStr = if (snapshot.lastSchedulerDurationMs > 0L) " • Last verify ${snapshot.lastSchedulerDurationMs}ms" else ""
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.MonitorHeart, contentDescription = null, tint = EmeraldAccent, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "Last pulse: $hbStr$tickStr",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        // Timeline Canvas Plot (tap or drag across bars to inspect exact times)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(NavyWell)
                .padding(horizontal = 8.dp, vertical = 10.dp)
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(78.dp)
                    .testTag("background_uptime_canvas")
                    .pointerInput(slots.size) {
                        detectTapGestures { offset ->
                            if (slots.isNotEmpty() && size.width > 0) {
                                val idx = ((offset.x / size.width) * slots.size).toInt().coerceIn(0, slots.lastIndex)
                                selectedSlotIndex = if (selectedSlotIndex == idx) -1 else idx
                            }
                        }
                    }
                    .pointerInput(slots.size) {
                        detectHorizontalDragGestures { change, _ ->
                            change.consume()
                            if (slots.isNotEmpty() && size.width > 0) {
                                val idx = ((change.position.x / size.width) * slots.size).toInt().coerceIn(0, slots.lastIndex)
                                selectedSlotIndex = idx
                            }
                        }
                    }
            ) {
                val count = slots.size
                if (count == 0) return@Canvas
                val w = size.width
                val h = size.height
                val slotW = w / count.toFloat()
                val gapPx = if (slotW > 4f) 1.5f else 0.75f
                val barW = max(1.5f, slotW - gapPx)

                // Subtle horizontal baseline & mid guide
                drawLine(
                    color = NavyBorder.copy(alpha = 0.45f),
                    start = Offset(0f, h * 0.5f),
                    end = Offset(w, h * 0.5f),
                    strokeWidth = 1f
                )
                drawLine(
                    color = NavyBorder.copy(alpha = 0.7f),
                    start = Offset(0f, h - 1f),
                    end = Offset(w, h - 1f),
                    strokeWidth = 1f
                )

                slots.forEachIndexed { idx, slot ->
                    val x = idx * slotW + gapPx * 0.5f
                    val (barColor, heightFraction) = when (slot.status) {
                        HealthBucketStatus.ACTIVE -> EmeraldAccent to 0.70f
                        HealthBucketStatus.BUSY -> SignalBlue to 0.85f
                        HealthBucketStatus.STALLED -> CoralRed to 1.0f
                        HealthBucketStatus.STOPPED -> AmberWarn.copy(alpha = 0.50f) to 0.34f
                        HealthBucketStatus.UNKNOWN -> NavyBorder.copy(alpha = 0.35f) to 0.14f
                    }
                    val barH = h * heightFraction
                    val topY = h - barH

                    if (slot.status == HealthBucketStatus.STALLED) {
                        drawRect(
                            color = CoralGlow,
                            topLeft = Offset(x - 1f, 0f),
                            size = Size(barW + 2f, h)
                        )
                    }

                    drawRoundRect(
                        color = if (idx == selectedSlotIndex) Color.White else barColor,
                        topLeft = Offset(x, topY),
                        size = Size(barW, barH),
                        cornerRadius = CornerRadius(2.5f, 2.5f)
                    )

                    if (idx == selectedSlotIndex) {
                        drawLine(
                            color = Color.White.copy(alpha = 0.85f),
                            start = Offset(x + barW * 0.5f, 0f),
                            end = Offset(x + barW * 0.5f, h),
                            strokeWidth = 1.5f
                        )
                    }
                }
            }
        }

        // Time Axis Labels
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val midLabel = when (selectedWindow) {
                HealthWindow.ONE_HOUR -> "-30m"
                HealthWindow.SIX_HOURS -> "-3h"
                HealthWindow.TWENTY_FOUR_HOURS -> "-12h"
            }
            Text("-${selectedWindow.label.lowercase()}", style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily, fontSize = 10.sp), color = TextMuted)
            Text(midLabel, style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily, fontSize = 10.sp), color = TextMuted)
            Text("Now (${fmtSlotTime(nowMs)})", style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily, fontSize = 10.sp), color = TextMuted)
        }

        // Legend Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LegendDot(color = EmeraldAccent, label = "Active")
            LegendDot(color = SignalBlue, label = "Busy")
            LegendDot(color = CoralRed, label = "Stalled / Killed")
            LegendDot(color = AmberWarn.copy(alpha = 0.65f), label = "Stopped")
        }

        // Android 11+ OS Exit Reason Diagnostic Banner (if available)
        if (!snapshot.lastOsExitReason.isNullOrBlank()) {
            Surface(
                color = CoralContainer.copy(alpha = 0.55f),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, CoralRed.copy(alpha = 0.45f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = CoralRed, modifier = Modifier.size(16.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "Last Android OS Process Termination",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = CoralRed
                        )
                        Text(
                            snapshot.lastOsExitReason,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextPrimary
                        )
                    }
                }
            }
        }

        HorizontalDivider(color = NavyBorder)

        // Recorded Outages & Lifecycle Timeline
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Recorded Stalls, Kills & Activity (${snapshot.incidents.size})",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = TextPrimary
            )
            if (snapshot.longestGapSeconds24h > 0L) {
                Text(
                    "Max gap: ${BackgroundHealthTracker.formatDurationShort(snapshot.longestGapSeconds24h)}",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                    color = CoralRed
                )
            }
        }

        if (snapshot.incidents.isEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = EmeraldAccent, modifier = Modifier.size(16.dp))
                Text(
                    "No stalls or unexpected kills recorded yet. Keep Dedicated Processing ON to track continuous uptime.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        } else {
            var showAll by remember { mutableStateOf(false) }
            val visibleIncidents = if (showAll) snapshot.incidents.take(25) else snapshot.incidents.take(6)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                visibleIncidents.forEach { inc ->
                    IncidentRowItem(inc)
                }
            }
            if (snapshot.incidents.size > 6) {
                TextButton(
                    onClick = { showAll = !showAll },
                    modifier = Modifier.align(Alignment.End).testTag("toggle_all_incidents_button")
                ) {
                    Text(if (showAll) "Show less" else "Show more (${snapshot.incidents.size - 6} older)")
                }
            }
        }
    }
}

@Composable
private fun HealthKpiTile(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = NavyWell,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, NavyBorder)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                value,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontFamily = MonoFontFamily,
                    fontWeight = FontWeight.Bold
                ),
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(label, style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp), color = TextSecondary)
    }
}

@Composable
private fun IncidentRowItem(inc: BackgroundIncident) {
    val (tagText, bg, fg) = when (inc.kind) {
        BackgroundHealthTracker.KIND_OS_FREEZE_GAP -> Triple("OS FREEZE GAP", CoralContainer, CoralRed)
        BackgroundHealthTracker.KIND_PROCESS_KILLED -> Triple("OS KILLED", CoralContainer, CoralRed)
        BackgroundHealthTracker.KIND_SLOW_TICK -> Triple("SLOW VERIFY", AmberContainer, AmberWarn)
        BackgroundHealthTracker.KIND_WORKER_CATCHUP -> Triple("CATCH-UP", AmberContainer, AmberWarn)
        BackgroundHealthTracker.KIND_TASK_SWIPED -> Triple("SWIPED", AmberContainer, AmberWarn)
        BackgroundHealthTracker.KIND_USER_STOPPED -> Triple("STOPPED", NavyCardElevated, TextSecondary)
        else -> Triple("STARTED", EmeraldContainer, EmeraldAccent)
    }
    val hasRange = inc.endedAtMs > inc.startedAtMs && inc.durationSec > 0L

    Surface(
        color = NavyWell,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, if (fg == CoralRed) CoralRed.copy(alpha = 0.4f) else NavyBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    MicroBadge(text = tagText, bg = bg, fg = fg)
                    if (inc.durationSec > 0L) {
                        MicroBadge(
                            text = BackgroundHealthTracker.formatDurationShort(inc.durationSec),
                            bg = NavyCardElevated,
                            fg = if (fg == CoralRed) CoralRed else TextPrimary,
                            border = NavyBorderActive
                        )
                    }
                }
                Text(
                    text = if (hasRange) {
                        "${fmtSlotTime(inc.startedAtMs)} → ${fmtSlotTime(inc.endedAtMs)}"
                    } else {
                        fmtIncidentTime(inc.startedAtMs)
                    },
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily, fontSize = 10.sp),
                    color = TextSecondary
                )
            }
            if (hasRange) {
                Text(
                    text = "Out: ${fmtIncidentTime(inc.startedAtMs)}  •  Back: ${fmtIncidentTime(inc.endedAtMs)}",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily, fontSize = 10.sp),
                    color = if (fg == CoralRed) CoralRed else TextMuted
                )
            }
            Text(
                text = inc.detail,
                style = MaterialTheme.typography.bodySmall,
                color = TextPrimary
            )
        }
    }
}
