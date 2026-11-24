package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.repository.EnrichedSignal
import com.example.domain.model.ApiHealthSummary
import com.example.ui.theme.AmberContainer
import com.example.ui.theme.AmberWarn
import com.example.ui.theme.CoralContainer
import com.example.ui.theme.CoralRed
import com.example.ui.theme.EmeraldAccent
import com.example.ui.theme.EmeraldContainer
import com.example.ui.theme.MonoFontFamily
import com.example.ui.theme.NavyBorder
import com.example.ui.theme.NavyCard
import com.example.ui.theme.NavyCardElevated
import com.example.ui.theme.NavyInput
import com.example.ui.theme.NavyWell
import com.example.ui.theme.SignalBlue
import com.example.ui.theme.SignalBlueContainer
import com.example.ui.theme.TextSecondary
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

private val shortFmt = DateTimeFormatter.ofPattern("dd MMM HH:mm", Locale.US)
private val fullFmt = DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm:ss a", Locale.US)

fun formatPnl(amount: Double, currency: String = "INR"): String {
    val sym = if (currency == "USD") "$" else "₹"
    val sign = when {
        amount > 0.0 -> "+"
        amount < 0.0 -> "-"
        else -> ""
    }
    val absVal = abs(amount)
    val num = if (absVal == absVal.toLong().toDouble()) {
        String.format(Locale.US, "%,d", absVal.toLong())
    } else {
        String.format(Locale.US, "%,.2f", absVal)
    }
    return "$sign$sym$num"
}

fun formatCompactPrice(price: Double?): String {
    if (price == null) return "—"
    val absVal = abs(price)
    return when {
        absVal >= 1000.0 -> if (price == price.toLong().toDouble()) String.format(Locale.US, "%.0f", price) else String.format(Locale.US, "%.2f", price)
        absVal >= 10.0 -> String.format(Locale.US, "%.2f", price).trimEnd('0').trimEnd('.')
        absVal >= 1.0 -> String.format(Locale.US, "%.4f", price).trimEnd('0').trimEnd('.')
        else -> String.format(Locale.US, "%.5f", price).trimEnd('0').trimEnd('.')
    }
}

fun isEditedSignal(explanation: String?): Boolean {
    if (explanation.isNullOrBlank()) return false
    return explanation.contains("edited", ignoreCase = true)
}

fun formatShortTimestamp(iso: String?): String {
    if (iso.isNullOrBlank()) return "—"
    return try {
        val inst = Instant.parse(iso)
        shortFmt.format(inst.atZone(ZoneId.systemDefault()))
    } catch (_: Exception) {
        iso.take(16)
    }
}

fun formatFullTimestamp(iso: String?): String {
    if (iso.isNullOrBlank()) return "—"
    return try {
        val inst = Instant.parse(iso)
        fullFmt.format(inst.atZone(ZoneId.systemDefault()))
    } catch (_: Exception) {
        iso
    }
}

@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = NavyCard),
        border = BorderStroke(1.dp, NavyBorder),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(NavyCardElevated.copy(alpha = 0.55f), NavyCard)))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (title != null || action != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        if (title != null) {
                            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                        }
                        if (!subtitle.isNullOrBlank()) {
                            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                        }
                    }
                    if (action != null) action()
                }
            }
            content()
        }
    }
}

@Composable
fun MetricCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    accent: Color = EmeraldAccent
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = NavyCard),
        border = BorderStroke(1.dp, NavyBorder),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(NavyCardElevated.copy(alpha = 0.5f), NavyWell)))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                value,
                style = MaterialTheme.typography.titleLarge.copy(fontFamily = MonoFontFamily, fontWeight = FontWeight.Bold),
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun StatusPill(text: String, bg: Color, fg: Color, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = bg,
        shape = RoundedCornerShape(999.dp)
    ) {
        Text(
            text = text,
            color = fg,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun MicroBadge(
    text: String,
    bg: Color,
    fg: Color,
    border: Color = fg.copy(alpha = 0.45f),
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = bg,
        shape = RoundedCornerShape(5.dp),
        border = BorderStroke(1.dp, border)
    ) {
        Text(
            text = text,
            color = fg,
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = MonoFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                letterSpacing = 0.4.sp
            ),
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
            maxLines = 1
        )
    }
}

@Composable
fun VerificationStatusPill(
    status: String,
    isManualNeeded: Boolean,
    isAutoScheduled: Boolean,
    closedBySlAfterTp: Boolean = false,
    maxTpHit: Int = 0
) {
    val (label, bg, fg) = when {
        status == "target_hit" && closedBySlAfterTp ->
            Triple("TP${maxOf(1, maxTpHit)} ✓ → SL Closed", EmeraldContainer, EmeraldAccent)
        status == "target_hit" && maxTpHit >= 1 ->
            Triple("TP$maxTpHit Hit", EmeraldContainer, EmeraldAccent)
        status == "target_hit" -> Triple("TP Hit", EmeraldContainer, EmeraldAccent)
        status == "stop_loss_hit" -> Triple("SL Hit", CoralContainer, CoralRed)
        status == "entry_hit" -> Triple("Entry Hit", SignalBlueContainer, SignalBlue)
        status == "ambiguous" -> Triple("Ambiguous", AmberContainer, AmberWarn)
        status == "awaiting_market_data" -> Triple("Awaiting Data", AmberContainer, AmberWarn)
        status == "historical_data_missing" -> Triple("No Historical Data", NavyCardElevated, TextSecondary)
        status == "pending" -> when {
            isManualNeeded -> Triple("Review Needed", AmberContainer, AmberWarn)
            isAutoScheduled -> Triple("Monitoring", SignalBlueContainer, SignalBlue)
            else -> Triple("Pending", NavyCardElevated, TextSecondary)
        }
        else -> Triple(status, NavyCardElevated, TextSecondary)
    }
    StatusPill(label, bg, fg)
}

@Composable
fun MonitoringPhasePill(phase: String) {
    val (label, bg, fg) = when (phase) {
        "idle_15m" -> Triple("Idle 15m", NavyCardElevated, SignalBlue)
        "fast_15m" -> Triple("15m Fast", SignalBlueContainer, SignalBlue)
        "hourly_1h" -> Triple("1h Monitor", AmberContainer, AmberWarn)
        "completed" -> Triple("Auto-Stopped", NavyCardElevated, TextSecondary)
        "expired" -> Triple("Expired", NavyCardElevated, TextSecondary)
        else -> return
    }
    StatusPill(label, bg, fg)
}

@Composable
fun LevelPnlStrip(
    sig: EnrichedSignal,
    activeBasis: String = "tp1",
    modifier: Modifier = Modifier
) {
    val hasLevels = sig.tpLevels.isNotEmpty() || sig.tp1PnlAmount != 0.0 || sig.slPnlAmount != 0.0
    if (!hasLevels) return
    val cur = sig.pnlCurrency
    val basis = activeBasis.lowercase(Locale.US)
    val levelsToShow = if (sig.tpLevels.isNotEmpty()) {
        sig.tpLevels.take(3)
    } else {
        buildList {
            if (sig.tp1PnlAmount != 0.0 || sig.targets.isNotEmpty()) {
                add(com.example.domain.model.TargetLevelPnl(index = 1, targetPrice = sig.targets.getOrNull(0) ?: 0.0, moneyPnl = sig.tp1PnlAmount, hit = sig.maxTpHit >= 1))
            }
            if (sig.tp2PnlAmount != 0.0 || sig.targets.size >= 2) {
                add(com.example.domain.model.TargetLevelPnl(index = 2, targetPrice = sig.targets.getOrNull(1) ?: 0.0, moneyPnl = sig.tp2PnlAmount, hit = sig.maxTpHit >= 2))
            }
            if (sig.tp3PnlAmount != 0.0 || sig.targets.size >= 3) {
                add(com.example.domain.model.TargetLevelPnl(index = 3, targetPrice = sig.targets.getOrNull(2) ?: 0.0, moneyPnl = sig.tp3PnlAmount, hit = sig.maxTpHit >= 3))
            }
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        levelsToShow.forEach { lv ->
            val isBasis = (basis == "tp${lv.level}") || (basis == "highest_tp" && lv.level == sig.maxTpHit && lv.hit)
            val bg = if (lv.hit) EmeraldContainer else NavyInput
            val fg = if (lv.hit) EmeraldAccent else TextSecondary
            val borderCol = when {
                isBasis -> EmeraldAccent
                lv.hit -> EmeraldAccent.copy(alpha = 0.6f)
                else -> NavyBorder
            }
            Surface(
                color = bg,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, borderCol)
            ) {
                Text(
                    text = "${lv.label}${if (lv.hit) " ✓" else ""}: ${formatPnl(lv.moneyPnl, cur)}",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = MonoFontFamily,
                        fontWeight = if (lv.hit || isBasis) FontWeight.Bold else FontWeight.Medium
                    ),
                    color = fg,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        if (sig.row.stopLoss != null || sig.slPnlAmount != 0.0) {
            val slHit = sig.row.verificationStatus == "stop_loss_hit"
            val slAfterTp = sig.closedBySlAfterTp
            val bg = when {
                slHit -> CoralContainer
                slAfterTp -> AmberContainer
                else -> NavyInput
            }
            val fg = when {
                slHit -> CoralRed
                slAfterTp -> AmberWarn
                else -> CoralRed.copy(alpha = 0.85f)
            }
            val label = when {
                slHit -> "SL ✗"
                slAfterTp -> "SL* Closed"
                else -> "SL Risk"
            }
            Surface(
                color = bg,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, if (slHit) CoralRed else if (slAfterTp) AmberWarn else NavyBorder)
            ) {
                Text(
                    text = "$label: ${formatPnl(sig.slPnlAmount, cur)}",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = MonoFontFamily,
                        fontWeight = if (slHit || slAfterTp) FontWeight.Bold else FontWeight.Medium
                    ),
                    color = fg,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
fun ApiHealthAlertBanner(
    summary: ApiHealthSummary,
    onOpenDiagnostics: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (summary.activeAlerts.isEmpty()) return
    val isError = summary.overallStatus == "error" || summary.activeAlerts.any { it.severity == "error" }
    val bg = if (isError) CoralContainer else AmberContainer
    val fg = if (isError) CoralRed else AmberWarn
    val primaryAlert = summary.activeAlerts.first()
    val extraCount = summary.activeAlerts.size - 1

    Card(
        modifier = modifier.fillMaxWidth().testTag("api_health_alert_banner"),
        colors = CardDefaults.cardColors(containerColor = bg),
        border = BorderStroke(1.dp, fg.copy(alpha = 0.7f)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = "API health alert",
                tint = fg,
                modifier = Modifier.size(20.dp)
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "API Alert (${summary.activeAlerts.size})${if (extraCount > 0) " • +$extraCount more" else ""}",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = fg
                )
                Text(
                    text = primaryAlert.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            TextButton(
                onClick = onOpenDiagnostics,
                modifier = Modifier.testTag("api_health_banner_diagnostics")
            ) {
                Text("Details", color = fg, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun DirectionPill(direction: String?) {
    if (direction.isNullOrBlank()) return
    val isBuy = direction.equals("BUY", ignoreCase = true)
    StatusPill(
        text = direction.uppercase(Locale.US),
        bg = if (isBuy) EmeraldContainer else CoralContainer,
        fg = if (isBuy) EmeraldAccent else CoralRed
    )
}

@Composable
fun DistributionBarRow(label: String, count: Int, maxCount: Int) {
    val fraction = (count.toFloat() / maxOf(maxCount, 1).toFloat()).coerceIn(0f, 1f)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("$count", style = MaterialTheme.typography.labelSmall, color = EmeraldAccent)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(7.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(NavyCardElevated)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(7.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(EmeraldAccent)
            )
        }
    }
}
