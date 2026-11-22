package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.data.repository.SignalChartData
import com.example.ui.theme.AmberWarn
import com.example.ui.theme.CoralRed
import com.example.ui.theme.EmeraldAccent
import com.example.ui.theme.MonoFontFamily
import com.example.ui.theme.NavyBorder
import com.example.ui.theme.NavyInput
import com.example.ui.theme.SignalBlue
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
    height: Dp = 260.dp
) {
    val candles = chartData.candles
    if (candles.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(NavyInput)
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

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(NavyInput)
            .border(1.dp, NavyBorder, RoundedCornerShape(10.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Controls toolbar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilledTonalButton(onClick = { zoom(0.65f) }, modifier = Modifier.testTag("chart_zoom_in")) { Text("+") }
            FilledTonalButton(onClick = { zoom(1.5f) }, modifier = Modifier.testTag("chart_zoom_out")) { Text("−") }
            FilledTonalButton(onClick = { focusTrade() }, modifier = Modifier.testTag("chart_focus")) { Text("Focus") }
            FilledTonalButton(onClick = { fitAll() }, modifier = Modifier.testTag("chart_fit")) { Text("Fit All") }
        }

        // Crosshair / status readout
        val readout = if (selectedIdx in candles.indices) {
            val c = candles[selectedIdx]
            val mNotes = markers.filter { it.idx == selectedIdx }.joinToString(" • ") { it.detail }
            val suffix = if (mNotes.isNotEmpty()) " | ★ $mNotes" else ""
            "${formatShortTimestamp(c.candleTimestamp)} | O:${c.open} H:${c.high} L:${c.low} C:${c.close}$suffix"
        } else {
            "${candles.size} bars • ${chartData.provider} (${chartData.tradingSymbol ?: chartData.resolvedSymbol}) • Drag/pinch or tap bar"
        }
        Text(readout, style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily), color = TextSecondary)

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF090F1D))
                .pointerInput(candles.size) {
                    detectTransformGestures { _, pan, zoomFactor, _ ->
                        val plotW = max(size.width - 140f, 40f)
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
                .pointerInput(candles.size) {
                    detectTapGestures { offset ->
                        val padLeft = 18f
                        val padRight = 116f
                        val plotW = max(size.width - padLeft - padRight, 40f)
                        val rel = ((offset.x - padLeft) / plotW).coerceIn(0f, 1f)
                        selectedIdx = floor(viewStart + rel * visibleCount).toInt().coerceIn(0, candles.lastIndex)
                    }
                }
                .testTag("ohlc_chart_canvas")
        ) {
            val padTop = 30f
            val padBottom = 34f
            val padLeft = 16f
            val padRight = 116f
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
                textSize = 24f
                isAntiAlias = true
            }
            val tagPaint = android.graphics.Paint().apply {
                textSize = 22f
                isFakeBoldText = true
                isAntiAlias = true
            }

            // Grid lines + price axis
            for (g in 0..4) {
                val p = minP + ((maxP - minP) * g) / 4.0
                val y = yOf(p)
                drawLine(Color(0xFF1B2742), Offset(padLeft, y), Offset(size.width - padRight, y), strokeWidth = 1.2f)
                val fmt = if (p >= 100.0) String.format(Locale.US, "%.2f", p) else String.format(Locale.US, "%.4f", p)
                drawContext.canvas.nativeCanvas.drawText(fmt, size.width - padRight + 8f, y + 7f, labelPaint)
            }

            // Dashed Trade Levels
            val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)
            fun drawLevel(price: Double?, label: String, color: Color, hexColor: Int) {
                if (price == null || price < minP || price > maxP) return
                val y = yOf(price)
                drawLine(color, Offset(padLeft, y), Offset(size.width - padRight, y), strokeWidth = 2f, pathEffect = dash)
                tagPaint.color = hexColor
                drawContext.canvas.nativeCanvas.drawText("$label: $price", padLeft + 6f, max(26f, y - 6f), tagPaint)
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
                drawContext.canvas.nativeCanvas.drawText(m.label, cx - 18f, max(26f, cy - 14f - (mIdx % 2) * 20f), tagPaint)
            }

            // Crosshair
            if (selectedIdx in startIdx..endIdx) {
                val c = candles[selectedIdx]
                val cx = xOf(selectedIdx)
                val cy = yOf(c.close)
                drawLine(Color(0xFFE7EDF9), Offset(cx, padTop), Offset(cx, size.height - padBottom), strokeWidth = 1.5f, pathEffect = dash)
                drawLine(Color(0xFFE7EDF9), Offset(padLeft, cy), Offset(size.width - padRight, cy), strokeWidth = 1.5f, pathEffect = dash)
            }

            // Time axis labels
            drawContext.canvas.nativeCanvas.drawText(formatShortTimestamp(candles[startIdx].candleTimestamp), padLeft, size.height - 8f, labelPaint)
            val endTxt = formatShortTimestamp(candles[endIdx].candleTimestamp)
            val endW = labelPaint.measureText(endTxt)
            drawContext.canvas.nativeCanvas.drawText(endTxt, max(padLeft, size.width - padRight - endW), size.height - 8f, labelPaint)
        }
    }
}
