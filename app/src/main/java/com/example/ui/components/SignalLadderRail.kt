package com.example.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.repository.EnrichedSignal
import com.example.ui.theme.AmberContainer
import com.example.ui.theme.AmberGlow
import com.example.ui.theme.AmberWarn
import com.example.ui.theme.CoralGlow
import com.example.ui.theme.CoralRed
import com.example.ui.theme.EmeraldAccent
import com.example.ui.theme.EmeraldGlow
import com.example.ui.theme.MonoFontFamily
import com.example.ui.theme.NavyBorder
import com.example.ui.theme.NavyWell
import com.example.ui.theme.SignalBlue
import com.example.ui.theme.SignalBlueGlow
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

private enum class LadderNodeKind { SL, ENTRY, TP }

private data class LadderNode(
    val kind: LadderNodeKind,
    val label: String,
    val priceText: String,
    val hit: Boolean,
    val active: Boolean,
    val warningRing: Boolean = false
)

/**
 * Horizontal TP/SL price journey ladder rendered inside signal cards and fullscreen chart headers.
 * Visualizes: [SL] ─── [ENTRY / MKT] ═══● [TP1] ═══● [TP2] ───○ [TP3] (+N)
 */
@Composable
fun SignalLadderRail(
    sig: EnrichedSignal,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    val r = sig.row
    val status = r.verificationStatus
    val isSlHit = status == "stop_loss_hit"
    val isSlAfterTp = sig.closedBySlAfterTp
    val isEntryActive = status == "entry_hit"
    val isEntryReached = isEntryActive || status == "target_hit" || isSlHit || sig.tradesCount > 0
    val isMarketNow = r.entryMode == "market_now"

    val displayedTargets = sig.targets.take(3)
    val extraTargetsCount = (sig.targets.size - 3).coerceAtLeast(0)

    val entryPriceLabel = when {
        r.entryPrice != null -> formatCompactPrice(r.entryPrice)
        isMarketNow -> "MKT NOW"
        else -> "—"
    }

    val nodes = buildList {
        add(
            LadderNode(
                kind = LadderNodeKind.SL,
                label = if (isSlAfterTp) "SL*" else "SL",
                priceText = formatCompactPrice(r.stopLoss),
                hit = isSlHit,
                active = false,
                warningRing = isSlAfterTp
            )
        )
        add(
            LadderNode(
                kind = LadderNodeKind.ENTRY,
                label = if (isMarketNow) "MKT" else "ENTRY",
                priceText = entryPriceLabel,
                hit = isEntryReached,
                active = isEntryActive
            )
        )
        if (displayedTargets.isEmpty()) {
            add(
                LadderNode(
                    kind = LadderNodeKind.TP,
                    label = "TP1",
                    priceText = "—",
                    hit = false,
                    active = false
                )
            )
        } else {
            displayedTargets.forEachIndexed { idx, price ->
                val tpNum = idx + 1
                add(
                    LadderNode(
                        kind = LadderNodeKind.TP,
                        label = if (idx == 2 && extraTargetsCount > 0) "TP3+$extraTargetsCount" else "TP$tpNum",
                        priceText = formatCompactPrice(price),
                        hit = sig.maxTpHit >= tpNum || (status == "target_hit" && tpNum == 1 && sig.maxTpHit == 0),
                        active = false
                    )
                )
            }
        }
    }

    val pulseAlpha = if (isEntryActive) {
        val transition = rememberInfiniteTransition(label = "entry_pulse")
        val anim by transition.animateFloat(
            initialValue = 0.25f,
            targetValue = 0.85f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1200),
                repeatMode = RepeatMode.Reverse
            ),
            label = "entry_pulse_alpha"
        )
        anim
    } else {
        0f
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(NavyWell)
            .border(1.dp, NavyBorder.copy(alpha = 0.85f), RoundedCornerShape(10.dp))
            .padding(horizontal = if (compact) 8.dp else 10.dp, vertical = if (compact) 6.dp else 8.dp)
            .testTag("signal_ladder_${r.id}"),
        verticalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 5.dp)
    ) {
        // Track & Node Canvas
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (compact) 18.dp else 22.dp)
        ) {
            val count = nodes.size
            if (count < 2) return@Canvas
            val y = size.height / 2f
            val slotW = size.width / count.toFloat()
            val xs = List(count) { i -> slotW * (i + 0.5f) }
            val trackStroke = if (compact) 3.2f else 4.2f
            val radius = if (compact) 5.5f else 6.8f

            // Draw segments between adjacent nodes
            for (i in 0 until count - 1) {
                val left = nodes[i]
                val right = nodes[i + 1]
                val x1 = xs[i]
                val x2 = xs[i + 1]
                val segColor = when {
                    left.kind == LadderNodeKind.SL && left.hit -> CoralRed
                    left.kind == LadderNodeKind.SL && left.warningRing -> AmberWarn.copy(alpha = 0.85f)
                    right.kind == LadderNodeKind.TP && right.hit -> EmeraldAccent
                    left.kind == LadderNodeKind.ENTRY && left.active && right.kind == LadderNodeKind.TP ->
                        SignalBlue.copy(alpha = 0.55f)
                    else -> NavyBorder
                }
                val isLit = segColor != NavyBorder
                drawLine(
                    color = segColor,
                    start = Offset(x1, y),
                    end = Offset(x2, y),
                    strokeWidth = if (isLit) trackStroke * 1.25f else trackStroke,
                    cap = StrokeCap.Round
                )
            }

            // Draw nodes
            nodes.forEachIndexed { idx, node ->
                val cx = xs[idx]
                when (node.kind) {
                    LadderNodeKind.SL -> {
                        when {
                            node.hit -> {
                                drawCircle(CoralGlow, radius = radius * 2.0f, center = Offset(cx, y))
                                drawCircle(CoralRed, radius = radius, center = Offset(cx, y))
                            }
                            node.warningRing -> {
                                drawCircle(AmberGlow, radius = radius * 1.9f, center = Offset(cx, y))
                                drawCircle(NavyWell, radius = radius, center = Offset(cx, y))
                                drawCircle(AmberWarn, radius = radius, center = Offset(cx, y), style = Stroke(width = 3f))
                            }
                            else -> {
                                drawCircle(NavyWell, radius = radius, center = Offset(cx, y))
                                drawCircle(
                                    CoralRed.copy(alpha = if (node.priceText == "—") 0.25f else 0.65f),
                                    radius = radius,
                                    center = Offset(cx, y),
                                    style = Stroke(width = 2.4f)
                                )
                            }
                        }
                    }

                    LadderNodeKind.ENTRY -> {
                        if (node.active) {
                            drawCircle(
                                SignalBlue.copy(alpha = pulseAlpha * 0.45f),
                                radius = radius * 2.2f,
                                center = Offset(cx, y)
                            )
                            drawCircle(SignalBlue, radius = radius * 1.08f, center = Offset(cx, y))
                            drawCircle(Color.White, radius = radius * 0.38f, center = Offset(cx, y))
                        } else if (node.hit) {
                            drawCircle(SignalBlueGlow, radius = radius * 1.7f, center = Offset(cx, y))
                            drawCircle(SignalBlue, radius = radius, center = Offset(cx, y))
                        } else {
                            drawCircle(NavyWell, radius = radius, center = Offset(cx, y))
                            drawCircle(SignalBlue.copy(alpha = 0.7f), radius = radius, center = Offset(cx, y), style = Stroke(width = 2.5f))
                        }
                    }

                    LadderNodeKind.TP -> {
                        if (node.hit) {
                            drawCircle(EmeraldGlow, radius = radius * 1.95f, center = Offset(cx, y))
                            drawCircle(EmeraldAccent, radius = radius, center = Offset(cx, y))
                            drawCircle(Color(0xFF062019), radius = radius * 0.35f, center = Offset(cx, y))
                        } else {
                            drawCircle(NavyWell, radius = radius, center = Offset(cx, y))
                            drawCircle(
                                EmeraldAccent.copy(alpha = if (node.priceText == "—") 0.2f else 0.55f),
                                radius = radius,
                                center = Offset(cx, y),
                                style = Stroke(width = 2.4f)
                            )
                        }
                    }
                }
            }
        }

        // Aligned labels + tabular monospace prices
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            nodes.forEach { node ->
                val labelColor = when {
                    node.kind == LadderNodeKind.SL && node.hit -> CoralRed
                    node.kind == LadderNodeKind.SL && node.warningRing -> AmberWarn
                    node.kind == LadderNodeKind.SL -> CoralRed.copy(alpha = 0.8f)
                    node.kind == LadderNodeKind.ENTRY && (node.hit || node.active) -> SignalBlue
                    node.kind == LadderNodeKind.ENTRY -> TextSecondary
                    node.kind == LadderNodeKind.TP && node.hit -> EmeraldAccent
                    else -> TextMuted
                }
                val priceColor = when {
                    node.hit && node.kind == LadderNodeKind.TP -> EmeraldAccent
                    node.hit && node.kind == LadderNodeKind.SL -> CoralRed
                    node.warningRing -> AmberWarn
                    node.active -> TextPrimary
                    node.priceText == "—" -> TextMuted
                    else -> TextPrimary.copy(alpha = 0.88f)
                }
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = if (node.hit && node.kind == LadderNodeKind.TP) "${node.label} ✓" else node.label,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = if (compact) 9.sp else 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.3.sp
                        ),
                        color = labelColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = node.priceText,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = MonoFontFamily,
                            fontSize = if (compact) 10.sp else 11.sp,
                            fontWeight = if (node.hit || node.active) FontWeight.Bold else FontWeight.Medium
                        ),
                        color = priceColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        if (isSlAfterTp && !compact) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                MicroBadge(
                    text = "TP${maxOf(1, sig.maxTpHit)} ✓ → SL CLOSED",
                    bg = AmberContainer,
                    fg = AmberWarn
                )
            }
        }
    }
}
