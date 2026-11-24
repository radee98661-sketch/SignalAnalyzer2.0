package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.repository.EnrichedSignal
import com.example.data.repository.SignalChartData
import com.example.ui.theme.AmberWarn
import com.example.ui.theme.CoralRed
import com.example.ui.theme.EmeraldAccent
import com.example.ui.theme.MonoFontFamily
import com.example.ui.theme.NavyBorder
import com.example.ui.theme.NavyBorderActive
import com.example.ui.theme.NavyCanvas
import com.example.ui.theme.NavyCardElevated
import com.example.ui.theme.NavyInput
import com.example.ui.theme.NavyWell
import com.example.ui.theme.SignalBlue
import com.example.ui.theme.SignalBlueContainer
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.time.Instant
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

private data class ChartMarker(
    val idx: Int,
    val label: String,
    val color: Color,
    val price: Double,
    val detail: String
)

@Composable
fun OhlcChartCanvas(
    chartData: SignalChartData,
    modifier: Modifier = Modifier,
    height: Dp = 260.dp,
    fillHeight: Boolean = false,
    onOpenFullscreen: (() -> Unit)? = null
) {
    val candles = chartData.candles
    if (candles.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(NavyWell)
                .border(1.dp, NavyBorder, RoundedCornerShape(10.dp))
                .padding(14.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "No cached 1m candles yet for ${chartData.tradingSymbol ?: chartData.resolvedSymbol} (${chartData.provider}).",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                if (!chartData.detail.isNullOrBlank()) {
                    Text(chartData.detail, style = MaterialTheme.typography.bodySmall, color = AmberWarn)
                }
            }
        }
        return
    }

    val candleTimesMs = remember(candles) {
        candles.map {
            try {
                Instant.parse(it.candleTimestamp).toEpochMilli()
            } catch (_: Exception) {
                0L
            }
        }
    }

    fun nearestIdx(iso: String?): Int {
        if (iso.isNullOrBlank()) return -1
        val target = try {
            Instant.parse(iso).toEpochMilli()
        } catch (_: Exception) {
            return -1
        }
        var best = 0
        var bestDiff = Long.MAX_VALUE
        for (i in candleTimesMs.indices) {
            val d = abs(candleTimesMs[i] - target)
            if (d < bestDiff) {
                bestDiff = d
                best = i
            }
        }
        return best
    }

    val markers = remember(chartData) {
        val list = mutableListOf<ChartMarker>()
        val sigIdx = nearestIdx(chartData.notificationTime)
        if (sigIdx in candles.indices) {
            list += ChartMarker(sigIdx, "SIG", TextSecondary, candles[sigIdx].close, "Signal Sent")
        }
        for (ev in chartData.events) {
            val idx = nearestIdx(ev.candleTime)
            if (idx !in candles.indices) continue
            val isTp = ev.type == "tp_hit"
            val isSlAfterTp = ev.type == "sl_after_tp"
            val isSl = ev.type == "sl_hit" || ev.type == "ambiguous"
            val lbl = when {
                ev.type == "entry_hit" -> ev.leg?.let { "E$it" } ?: "ENTRY"
                isTp -> ev.label.substringBefore(" ")
                isSlAfterTp -> "SL*"
                isSl -> "SL"
                else -> ev.label
            }
            list += ChartMarker(
                idx = idx,
                label = lbl,
                color = when {
                    isTp -> EmeraldAccent
                    isSlAfterTp -> AmberWarn
                    isSl -> CoralRed
                    else -> SignalBlue
                },
                price = ev.price ?: candles[idx].close,
                detail = "${ev.label} @ ${ev.price ?: ""}"
            )
        }
        list
    }

    val minVisible = min(8, candles.size).toFloat()
    val maxVisible = max(candles.size, 1).toFloat()

    var viewStart by remember(chartData.signalId, candles.size) { mutableFloatStateOf(0f) }
    var visibleCount by remember(chartData.signalId, candles.size) { mutableFloatStateOf(candles.size.toFloat()) }
    var selectedIdx by remember(chartData.signalId, candles.size) { mutableIntStateOf(-1) }
    var includeAllLevels by remember(chartData.signalId) { mutableStateOf(true) }
    var scrubMode by remember(chartData.signalId) { mutableStateOf(false) }

    fun clamp() {
        visibleCount = visibleCount.coerceIn(minVisible, maxVisible)
        val maxStart = max(0f, candles.size - visibleCount)
        viewStart = viewStart.coerceIn(0f, maxStart)
    }

    fun focusTrade() {
        if (markers.isNotEmpty()) {
            val firstEv = markers.minOf { it.idx }
            val lastEv = markers.maxOf { it.idx }
            val span = max(lastEv - firstEv + 1, 16)
            val pad = max(5, (span * 0.25f).toInt())
            viewStart = max(0, firstEv - pad).toFloat()
            visibleCount = min(candles.size, span + pad * 2).toFloat()
        } else {
            viewStart = 0f
            visibleCount = min(candles.size, 60).toFloat()
        }
        includeAllLevels = true
        clamp()
    }

    fun fitAll() {
        viewStart = 0f
        visibleCount = candles.size.toFloat()
        includeAllLevels = true
        clamp()
    }

    fun zoom(factor: Float) {
        includeAllLevels = false
        val anchor = viewStart + visibleCount * 0.5f
        visibleCount = (visibleCount * factor).coerceIn(minVisible, maxVisible)
        viewStart = anchor - visibleCount * 0.5f
        clamp()
    }

    fun selectCandleAtX(touchX: Float, canvasWidth: Float) {
        val padLeft = 16f
        val padRight = 118f
        val plotW = max(canvasWidth - padLeft - padRight, 40f)
        val rel = ((touchX - padLeft) / plotW).coerceIn(0f, 1f)
        selectedIdx = floor(viewStart + rel * visibleCount).toInt().coerceIn(0, candles.lastIndex)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(NavyInput)
            .border(1.dp, NavyBorder, RoundedCornerShape(12.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Controls toolbar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = scrubMode,
                    onClick = { scrubMode = !scrubMode },
                    label = { Text("Scrub", style = MaterialTheme.typography.labelSmall) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.MyLocation,
                            contentDescription = "Toggle crosshair scrub mode",
                            modifier = Modifier.size(14.dp)
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = SignalBlueContainer,
                        selectedLabelColor = SignalBlue,
                        selectedLeadingIconColor = SignalBlue
                    ),
                    modifier = Modifier.testTag("chart_scrub_toggle")
                )
                ChartToolBtn("+", "chart_zoom_in") { zoom(0.65f) }
                ChartToolBtn("−", "chart_zoom_out") { zoom(1.5f) }
                ChartToolBtn("Focus", "chart_focus") { focusTrade() }
                ChartToolBtn("Fit", "chart_fit") { fitAll() }
            }
            if (onOpenFullscreen != null) {
                IconButton(
                    onClick = onOpenFullscreen,
                    modifier = Modifier.testTag("chart_fullscreen_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.Fullscreen,
                        contentDescription = "Open fullscreen chart",
                        tint = SignalBlue
                    )
                }
            }
        }

        // Compact chart hint
        Text(
            text = if (scrubMode) {
                "Scrub active • Drag across chart to inspect OHLC & Entry delta"
            } else {
                "${candles.size} bars • ${chartData.provider} (${chartData.tradingSymbol ?: chartData.resolvedSymbol}) • Pinch/pan or long-press to scrub"
            },
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily, fontSize = 10.sp),
            color = TextSecondary,
            maxLines = 1
        )

        val canvasBoxModifier = if (fillHeight) {
            Modifier.fillMaxWidth().weight(1f)
        } else {
            Modifier.fillMaxWidth().height(height)
        }

        Box(
            modifier = canvasBoxModifier
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF090F1D))
                .border(1.dp, NavyBorder.copy(alpha = 0.7f), RoundedCornerShape(10.dp))
        ) {
            val gestureModifier = if (scrubMode) {
                Modifier
                    .pointerInput(candles.size, scrubMode) {
                        detectHorizontalDragGestures(
                            onDragStart = { offset -> selectCandleAtX(offset.x, size.width.toFloat()) },
                            onHorizontalDrag = { change, _ ->
                                change.consume()
                                selectCandleAtX(change.position.x, size.width.toFloat())
                            }
                        )
                    }
                    .pointerInput(candles.size, scrubMode) {
                        detectTapGestures(
                            onTap = { offset -> selectCandleAtX(offset.x, size.width.toFloat()) },
                            onDoubleTap = { selectedIdx = -1; fitAll() }
                        )
                    }
            } else {
                Modifier
                    .pointerInput(candles.size, scrubMode) {
                        detectTransformGestures { _, pan, zoomFactor, _ ->
                            val plotW = max(size.width - 134f, 40f)
                            if (zoomFactor != 1f) {
                                includeAllLevels = false
                                val anchor = viewStart + visibleCount * 0.5f
                                visibleCount = (visibleCount / zoomFactor).coerceIn(minVisible, maxVisible)
                                viewStart = anchor - visibleCount * 0.5f
                            }
                            if (pan.x != 0f) {
                                includeAllLevels = false
                                viewStart -= (pan.x / plotW) * visibleCount
                            }
                            clamp()
                        }
                    }
                    .pointerInput(candles.size, scrubMode) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { offset -> selectCandleAtX(offset.x, size.width.toFloat()) },
                            onDrag = { change, _ ->
                                change.consume()
                                selectCandleAtX(change.position.x, size.width.toFloat())
                            }
                        )
                    }
                    .pointerInput(candles.size, scrubMode) {
                        detectTapGestures(
                            onTap = { offset -> selectCandleAtX(offset.x, size.width.toFloat()) },
                            onDoubleTap = { selectedIdx = -1; fitAll() }
                        )
                    }
            }

            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .then(gestureModifier)
                    .testTag("ohlc_chart_canvas")
            ) {
                val padTop = 30f
                val padBottom = 34f
                val padLeft = 16f
                val padRight = 118f
                val plotW = max(size.width - padLeft - padRight, 40f)
                val plotH = max(size.height - padTop - padBottom, 40f)

                val startIdx = max(0, floor(viewStart).toInt())
                val endIdx = min(candles.lastIndex, ceil(viewStart + visibleCount).toInt())

                var cMin = Double.POSITIVE_INFINITY
                var cMax = Double.NEGATIVE_INFINITY
                for (i in startIdx..endIdx) {
                    if (candles[i].low < cMin) cMin = candles[i].low
                    if (candles[i].high > cMax) cMax = candles[i].high
                }
                if (!cMin.isFinite() || !cMax.isFinite()) {
                    cMin = 0.0
                    cMax = 1.0
                }

                var minP = cMin
                var maxP = cMax
                val cSpan = max(cMax - cMin, max(abs(cMax) * 0.002, 1.0))
                val allLevels = buildList {
                    chartData.entryPrice?.let { add(it) }
                    addAll(chartData.additionalEntries)
                    chartData.stopLoss?.let { add(it) }
                    addAll(chartData.targets)
                }
                for (lvl in allLevels) {
                    if (includeAllLevels || (lvl >= cMin - cSpan * 0.6 && lvl <= cMax + cSpan * 0.6)) {
                        if (lvl < minP) minP = lvl
                        if (lvl > maxP) maxP = lvl
                    }
                }
                if (minP == maxP) {
                    minP -= 1.0
                    maxP += 1.0
                }
                val margin = (maxP - minP) * 0.09
                minP -= margin
                maxP += margin

                fun yOf(price: Double): Float = padTop + (((maxP - price) / (maxP - minP)) * plotH).toFloat()
                val stepX = plotW / max(visibleCount, 1f)
                fun xOf(idx: Int): Float = padLeft + (idx - viewStart + 0.5f) * stepX
                val bodyW = (stepX * 0.68f).coerceIn(3f, 22f)

                val labelPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.parseColor("#7F91B0")
                    textSize = 23f
                    isAntiAlias = true
                }
                val tagPaint = android.graphics.Paint().apply {
                    textSize = 21f
                    isFakeBoldText = true
                    isAntiAlias = true
                }
                val pillTextPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.parseColor("#0B1020")
                    textSize = 21f
                    isFakeBoldText = true
                    isAntiAlias = true
                }

                // Grid lines + price axis
                for (g in 0..4) {
                    val p = minP + ((maxP - minP) * g) / 4.0
                    val y = yOf(p)
                    drawLine(Color(0xFF1B2742), Offset(padLeft, y), Offset(size.width - padRight, y), strokeWidth = 1.1f)
                    val fmt = if (p >= 100.0) String.format(Locale.US, "%.2f", p) else String.format(Locale.US, "%.4f", p)
                    drawContext.canvas.nativeCanvas.drawText(fmt, size.width - padRight + 8f, y + 7f, labelPaint)
                }

                // Dashed Trade Levels
                val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)
                fun drawLevel(price: Double?, label: String, color: Color, hexColor: Int) {
                    if (price == null || price < minP || price > maxP) return
                    val y = yOf(price)
                    drawLine(color.copy(alpha = 0.85f), Offset(padLeft, y), Offset(size.width - padRight, y), strokeWidth = 1.8f, pathEffect = dash)
                    tagPaint.color = hexColor
                    drawContext.canvas.nativeCanvas.drawText("$label: ${formatCompactPrice(price)}", padLeft + 6f, max(24f, y - 6f), tagPaint)
                }
                drawLevel(chartData.entryPrice, "E1", SignalBlue, android.graphics.Color.parseColor("#60A5FA"))
                chartData.additionalEntries.forEachIndexed { idx, v ->
                    drawLevel(v, "E${idx + 2}", SignalBlue, android.graphics.Color.parseColor("#60A5FA"))
                }
                drawLevel(chartData.stopLoss, "SL", CoralRed, android.graphics.Color.parseColor("#FF7285"))
                chartData.targets.forEachIndexed { idx, v ->
                    drawLevel(v, "TP${idx + 1}", EmeraldAccent, android.graphics.Color.parseColor("#62D6A5"))
                }

                // Candlesticks
                for (i in startIdx..endIdx) {
                    val c = candles[i]
                    val cx = xOf(i)
                    if (cx < padLeft || cx > size.width - padRight) continue
                    val col = if (c.close >= c.open) Color(0xFF34D399) else Color(0xFFF87171)
                    drawLine(col, Offset(cx, yOf(c.high)), Offset(cx, yOf(c.low)), strokeWidth = 2.2f)
                    val topY = yOf(max(c.open, c.close))
                    val botY = yOf(min(c.open, c.close))
                    drawRect(col, Offset(cx - bodyW / 2f, topY), Size(bodyW, max(2.5f, botY - topY)))
                }

                // Event Markers
                markers.forEachIndexed { mIdx, m ->
                    if (m.idx < startIdx || m.idx > endIdx) return@forEachIndexed
                    val cx = xOf(m.idx)
                    val cy = yOf(m.price)
                    drawCircle(m.color, radius = 8f, center = Offset(cx, cy))
                    drawCircle(Color(0xFF090F1D), radius = 3f, center = Offset(cx, cy))
                    tagPaint.color = android.graphics.Color.WHITE
                    drawContext.canvas.nativeCanvas.drawText(m.label, cx - 18f, max(24f, cy - 14f - (mIdx % 2) * 20f), tagPaint)
                }

                // Crosshair + Y-axis price pill + X-axis timestamp pill
                if (selectedIdx in startIdx..endIdx) {
                    val c = candles[selectedIdx]
                    val cx = xOf(selectedIdx)
                    val cy = yOf(c.close)
                    val crosshairDash = PathEffect.dashPathEffect(floatArrayOf(7f, 6f), 0f)
                    drawLine(Color(0xCCE7EDF9), Offset(cx, padTop), Offset(cx, size.height - padBottom), strokeWidth = 1.4f, pathEffect = crosshairDash)
                    drawLine(Color(0xCCE7EDF9), Offset(padLeft, cy), Offset(size.width - padRight, cy), strokeWidth = 1.4f, pathEffect = crosshairDash)
                    drawCircle(SignalBlue, radius = 6f, center = Offset(cx, cy))

                    // Y-axis price highlight pill
                    val priceLbl = formatCompactPrice(c.close)
                    val pillW = max(92f, pillTextPaint.measureText(priceLbl) + 18f)
                    val pillH = 30f
                    drawRoundRect(
                        color = SignalBlue,
                        topLeft = Offset(size.width - padRight + 3f, (cy - pillH / 2f).coerceIn(padTop, size.height - padBottom - pillH)),
                        size = Size(min(pillW, padRight - 6f), pillH),
                        cornerRadius = CornerRadius(6f, 6f)
                    )
                    drawContext.canvas.nativeCanvas.drawText(
                        priceLbl,
                        size.width - padRight + 10f,
                        (cy + 7f).coerceIn(padTop + 21f, size.height - padBottom - 8f),
                        pillTextPaint
                    )

                    // X-axis timestamp highlight pill
                    val tsLbl = formatShortTimestamp(c.candleTimestamp)
                    val tsW = pillTextPaint.measureText(tsLbl) + 18f
                    val tsX = (cx - tsW / 2f).coerceIn(padLeft, size.width - padRight - tsW)
                    drawRoundRect(
                        color = TextPrimary,
                        topLeft = Offset(tsX, size.height - padBottom + 3f),
                        size = Size(tsW, 27f),
                        cornerRadius = CornerRadius(6f, 6f)
                    )
                    drawContext.canvas.nativeCanvas.drawText(tsLbl, tsX + 9f, size.height - padBottom + 23f, pillTextPaint)
                }

                // Default edge time labels when no crosshair overlaps
                if (selectedIdx !in startIdx..endIdx) {
                    drawContext.canvas.nativeCanvas.drawText(formatShortTimestamp(candles[startIdx].candleTimestamp), padLeft, size.height - 8f, labelPaint)
                    val endTxt = formatShortTimestamp(candles[endIdx].candleTimestamp)
                    val endW = labelPaint.measureText(endTxt)
                    drawContext.canvas.nativeCanvas.drawText(endTxt, max(padLeft, size.width - padRight - endW), size.height - 8f, labelPaint)
                }
            }

            // Floating OHLC + Entry Distance HUD Card (auto-positioned opposite active candle)
            if (selectedIdx in candles.indices) {
                val c = candles[selectedIdx]
                val isLeftHalf = (selectedIdx - viewStart) < (visibleCount * 0.5f)
                val hudAlignment = if (isLeftHalf) Alignment.TopEnd else Alignment.TopStart
                val isBull = c.close >= c.open
                val entryRef = chartData.entryPrice
                val deltaText = if (entryRef != null && entryRef > 0.0) {
                    val isSell = chartData.direction.equals("SELL", ignoreCase = true)
                    val rawDiff = if (isSell) entryRef - c.close else c.close - entryRef
                    val pct = (rawDiff / entryRef) * 100.0
                    val sign = if (rawDiff >= 0.0) "+" else ""
                    Triple("$sign${String.format(Locale.US, "%.2f", pct)}% vs Entry", rawDiff >= 0.0, formatCompactPrice(rawDiff))
                } else {
                    null
                }
                val markerNotes = markers.filter { it.idx == selectedIdx }.joinToString(" • ") { it.detail }

                Surface(
                    modifier = Modifier
                        .align(hudAlignment)
                        .padding(8.dp)
                        .testTag("chart_crosshair_hud"),
                    color = NavyCardElevated.copy(alpha = 0.94f),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, NavyBorderActive)
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = formatShortTimestamp(c.candleTimestamp),
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily, fontWeight = FontWeight.Bold),
                                color = TextPrimary
                            )
                            Text(
                                text = if (isBull) "BULL" else "BEAR",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
                                color = if (isBull) EmeraldAccent else CoralRed
                            )
                        }
                        Text(
                            text = "O:${formatCompactPrice(c.open)}  H:${formatCompactPrice(c.high)}",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily, fontSize = 10.sp),
                            color = TextSecondary
                        )
                        Text(
                            text = "L:${formatCompactPrice(c.low)}  C:${formatCompactPrice(c.close)}",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily, fontSize = 10.sp, fontWeight = FontWeight.Bold),
                            color = if (isBull) EmeraldAccent else CoralRed
                        )
                        if (deltaText != null) {
                            Text(
                                text = deltaText.first,
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily, fontSize = 10.sp, fontWeight = FontWeight.Bold),
                                color = if (deltaText.second) EmeraldAccent else CoralRed
                            )
                        }
                        if (markerNotes.isNotEmpty()) {
                            Text(
                                text = "★ $markerNotes",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                                color = AmberWarn
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChartToolBtn(label: String, tag: String, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        modifier = Modifier.testTag(tag)
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
}

/**
 * Full-bleed edge-to-edge Fullscreen Candlestick Chart Modal with compact TP/SL ladder header.
 */
@Composable
fun FullscreenOhlcDialog(
    signal: EnrichedSignal,
    chartData: SignalChartData,
    onDismiss: () -> Unit
) {
    val r = signal.row
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(NavyCanvas)
                .systemBarsPadding()
                .padding(12.dp)
                .testTag("fullscreen_chart_dialog"),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = r.symbol ?: chartData.tradingSymbol ?: chartData.resolvedSymbol,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = TextPrimary
                    )
                    DirectionPill(r.direction)
                    VerificationStatusPill(
                        status = r.verificationStatus,
                        isManualNeeded = signal.isManualNeeded,
                        isAutoScheduled = signal.isAutoScheduled,
                        closedBySlAfterTp = signal.closedBySlAfterTp,
                        maxTpHit = signal.maxTpHit
                    )
                }
                IconButton(onClick = onDismiss, modifier = Modifier.testTag("fullscreen_chart_close")) {
                    Icon(Icons.Default.Close, contentDescription = "Close fullscreen chart", tint = TextPrimary)
                }
            }

            SignalLadderRail(sig = signal, compact = true)

            OhlcChartCanvas(
                chartData = chartData,
                fillHeight = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.height(2.dp))
        }
    }
}
