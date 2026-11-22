package com.example.domain.verification

import com.example.domain.model.Candle
import com.example.domain.model.LegResult
import com.example.domain.model.TradeEvent
import com.example.domain.model.VerificationResult
import com.example.domain.model.VerificationTimeline
import com.example.domain.parser.DefaultRulesEngine
import java.util.Locale

data class VerifiableSignal(
    val direction: String?,
    val entryPrice: Double?,
    val additionalEntries: List<Double> = emptyList(),
    val stopLoss: Double? = null,
    val targets: List<Double> = emptyList(),
    val entryMode: String = "limit"
)

object VerificationEngine {

    private fun touched(candle: Candle, price: Double, tolerance: Double): Boolean {
        val low = candle.low - tolerance
        val high = candle.high + tolerance
        return price in low..high
    }

    private data class SingleLegEvaluation(
        val legResult: LegResult,
        val events: List<TradeEvent>
    )

    private fun evaluateSingleLeg(
        legNum: Int,
        totalLegs: Int,
        entryVal: Double,
        direction: String,
        stopLoss: Double?,
        targets: List<Double>,
        candles: List<Candle>,
        tolerance: Double,
        forceMarketOpen: Boolean
    ): SingleLegEvaluation {
        var entryIdx: Int? = null
        var entryTs: String? = null

        if (forceMarketOpen && candles.isNotEmpty()) {
            entryIdx = 0
            entryTs = candles[0].candleTimestamp
        } else {
            for (idx in candles.indices) {
                if (touched(candles[idx], entryVal, tolerance)) {
                    entryIdx = idx
                    entryTs = candles[idx].candleTimestamp
                    break
                }
            }
        }

        if (entryIdx == null || entryTs == null) {
            return SingleLegEvaluation(
                legResult = LegResult(
                    leg = legNum,
                    entryPrice = entryVal,
                    entered = false,
                    entryHitAt = null,
                    outcome = "pending",
                    exitPrice = null,
                    exitTime = null,
                    pointsPnl = 0.0,
                    targetsHit = emptyList()
                ),
                events = emptyList()
            )
        }

        val legTag = if (totalLegs > 1) " (Entry $legNum)" else ""
        val entryLabel = if (forceMarketOpen) {
            if (totalLegs > 1) "Market Entry $legNum" else "Market Entry"
        } else {
            if (totalLegs > 1) "Entry $legNum Hit" else "Entry Hit"
        }

        val events = mutableListOf(
            TradeEvent(
                type = "entry_hit",
                label = entryLabel,
                price = entryVal,
                candleTime = entryTs,
                leg = legNum
            )
        )

        val hitTpIndices = linkedSetOf<Int>()
        var highestTpPrice: Double? = null
        var highestTpTime: String? = null
        var slHitTime: String? = null
        var slAfterTpTime: String? = null
        var ambiguousTime: String? = null

        for (idx in entryIdx until candles.size) {
            val c = candles[idx]
            val ts = c.candleTimestamp

            val slTouched = stopLoss != null && touched(c, stopLoss, tolerance)
            val newlyHitTps = targets.mapIndexedNotNull { tIdx, tVal ->
                if (tIdx !in hitTpIndices && touched(c, tVal, tolerance)) tIdx to tVal else null
            }

            if (slTouched && newlyHitTps.isNotEmpty() && hitTpIndices.isEmpty()) {
                ambiguousTime = ts
                events += TradeEvent(
                    type = "ambiguous",
                    label = "Ambiguous TP & SL$legTag",
                    price = stopLoss!!,
                    candleTime = ts,
                    leg = legNum
                )
                break
            }

            if (slTouched && hitTpIndices.isNotEmpty()) {
                // Already profitable (at least TP1 hit); original SL touched -> close monitoring while keeping profitable trade classification
                val highestHitNum = hitTpIndices.max() + 1
                slAfterTpTime = ts
                events += TradeEvent(
                    type = "sl_after_tp",
                    label = "SL Hit after TP$highestHitNum$legTag",
                    price = stopLoss!!,
                    candleTime = ts,
                    leg = legNum
                )
                break
            }

            for ((tIdx, tVal) in newlyHitTps) {
                hitTpIndices.add(tIdx)
                highestTpPrice = tVal
                highestTpTime = ts
                events += TradeEvent(
                    type = "tp_hit",
                    label = "TP${tIdx + 1} Hit$legTag",
                    price = tVal,
                    candleTime = ts,
                    leg = legNum
                )
            }

            if (slTouched && hitTpIndices.isEmpty()) {
                slHitTime = ts
                events += TradeEvent(
                    type = "sl_hit",
                    label = "SL Hit$legTag",
                    price = stopLoss!!,
                    candleTime = ts,
                    leg = legNum
                )
                break
            }

            if (targets.isNotEmpty() && hitTpIndices.size == targets.size) {
                break
            }
        }

        val outcome: String
        val exitPrice: Double?
        val exitTime: String?
        val pointsPnl: Double

        when {
            ambiguousTime != null -> {
                outcome = "ambiguous"
                exitPrice = null
                exitTime = ambiguousTime
                pointsPnl = 0.0
            }
            hitTpIndices.isNotEmpty() -> {
                outcome = "target_hit"
                exitPrice = highestTpPrice
                exitTime = highestTpTime
                val ep = exitPrice!!
                pointsPnl = if (direction == "BUY") {
                    DefaultRulesEngine.roundValue(ep - entryVal, 6)
                } else {
                    DefaultRulesEngine.roundValue(entryVal - ep, 6)
                }
            }
            slHitTime != null -> {
                outcome = "stop_loss_hit"
                exitPrice = stopLoss!!
                exitTime = slHitTime
                pointsPnl = if (direction == "BUY") {
                    DefaultRulesEngine.roundValue(exitPrice - entryVal, 6)
                } else {
                    DefaultRulesEngine.roundValue(entryVal - exitPrice, 6)
                }
            }
            else -> {
                outcome = "entry_hit"
                exitPrice = null
                exitTime = null
                pointsPnl = 0.0
            }
        }

        return SingleLegEvaluation(
            legResult = LegResult(
                leg = legNum,
                entryPrice = entryVal,
                entered = true,
                entryHitAt = entryTs,
                outcome = outcome,
                exitPrice = exitPrice,
                exitTime = exitTime,
                pointsPnl = pointsPnl,
                targetsHit = hitTpIndices.map { it + 1 }.sorted(),
                closedBySlAfterTp = slAfterTpTime != null,
                slHitAfterTpAt = slAfterTpTime
            ),
            events = events
        )
    }

    fun verifyDetailed(
        signal: VerifiableSignal,
        candles: List<Candle>,
        tolerance: Double = 0.0
    ): VerificationResult {
        val direction = (signal.direction ?: "").trim().uppercase(Locale.US)
        val entryMode = signal.entryMode.trim().lowercase(Locale.US).ifEmpty { "limit" }
        val stopLoss = signal.stopLoss
        val targets = signal.targets
        val tol = tolerance

        val entries = mutableListOf<Double>()
        signal.entryPrice?.let { entries += it }
        for (extra in signal.additionalEntries) {
            if (extra !in entries) entries += extra
        }

        val emptyTimeline = VerificationTimeline(
            entryHitAt = null,
            firstTargetHitAt = null,
            stopLossHitAt = null,
            allTargetsHit = false,
            events = emptyList(),
            legs = emptyList(),
            closedBySlAfterTp = false,
            maxTpHit = 0
        )

        if (direction !in setOf("BUY", "SELL")) {
            return VerificationResult("pending", "Missing valid direction.", null, emptyTimeline)
        }
        if (entries.isEmpty() && entryMode != "market_now") {
            return VerificationResult("pending", "Missing valid entry price or direction.", null, emptyTimeline)
        }
        if (candles.isEmpty()) {
            return VerificationResult("awaiting_market_data", "Awaiting market data.", null, emptyTimeline)
        }
        if (entries.isEmpty() && entryMode == "market_now") {
            entries += candles[0].open
        }

        val legs = mutableListOf<LegResult>()
        val allEvents = mutableListOf<TradeEvent>()
        entries.forEachIndexed { idx, entryVal ->
            val eval = evaluateSingleLeg(
                legNum = idx + 1,
                totalLegs = entries.size,
                entryVal = entryVal,
                direction = direction,
                stopLoss = stopLoss,
                targets = targets,
                candles = candles,
                tolerance = tol,
                forceMarketOpen = (entryMode == "market_now" && idx == 0)
            )
            allEvents.addAll(eval.events)
            legs.add(eval.legResult)
        }

        allEvents.sortBy { it.candleTime }

        val enteredLegs = legs.filter { it.entered }
        if (enteredLegs.isEmpty()) {
            return VerificationResult(
                status = "pending",
                detail = "Candles checked; entry price not touched yet.",
                decisionTime = null,
                timeline = emptyTimeline.copy(legs = legs)
            )
        }

        val entryHitAt = enteredLegs.mapNotNull { it.entryHitAt }.minOrNull()
        val tpTimes = allEvents.filter { it.type == "tp_hit" }.map { it.candleTime }
        val slTimes = allEvents.filter { it.type == "sl_hit" || it.type == "sl_after_tp" }.map { it.candleTime }
        val ambTimes = allEvents.filter { it.type == "ambiguous" }.map { it.candleTime }

        val firstTargetHitAt = tpTimes.minOrNull()
        val stopLossHitAt = slTimes.minOrNull()
        val maxTpHit = enteredLegs.flatMap { it.targetsHit }.maxOrNull() ?: 0
        val allTargetsHit = targets.isNotEmpty() && enteredLegs.all { it.targetsHit.size == targets.size }
        val closedBySlAfterTp = enteredLegs.any { it.closedBySlAfterTp } &&
            enteredLegs.all { it.closedBySlAfterTp || it.outcome == "stop_loss_hit" || it.outcome == "ambiguous" || (targets.isNotEmpty() && it.targetsHit.size == targets.size) }

        val status: String
        val decisionTime: String?
        when {
            firstTargetHitAt != null -> {
                status = "target_hit"
                decisionTime = firstTargetHitAt
            }
            enteredLegs.any { it.outcome == "ambiguous" } -> {
                status = "ambiguous"
                decisionTime = ambTimes.firstOrNull() ?: entryHitAt
            }
            enteredLegs.all { it.outcome == "stop_loss_hit" } -> {
                status = "stop_loss_hit"
                decisionTime = stopLossHitAt
            }
            else -> {
                status = "entry_hit"
                decisionTime = entryHitAt
            }
        }

        val legSummaries = enteredLegs.map { lg ->
            val epStr = DefaultRulesEngine.formatG(lg.entryPrice)
            val pnlStr = formatSignedG(lg.pointsPnl)
            when (lg.outcome) {
                "target_hit" -> if (lg.closedBySlAfterTp) {
                    "Entry ${lg.leg} ($epStr) -> TP${lg.targetsHit.max()} then SL Closed ($pnlStr pts)"
                } else {
                    "Entry ${lg.leg} ($epStr) -> TP${lg.targetsHit.max()} ($pnlStr pts)"
                }
                "stop_loss_hit" -> "Entry ${lg.leg} ($epStr) -> SL ($pnlStr pts)"
                "ambiguous" -> "Entry ${lg.leg} ($epStr) -> Ambiguous"
                else -> "Entry ${lg.leg} ($epStr) -> Open"
            }
        }

        val detail = "${enteredLegs.size}/${entries.size} entries triggered: ${legSummaries.joinToString("; ")}."

        return VerificationResult(
            status = status,
            detail = detail,
            decisionTime = decisionTime,
            timeline = VerificationTimeline(
                entryHitAt = entryHitAt,
                firstTargetHitAt = firstTargetHitAt,
                stopLossHitAt = stopLossHitAt,
                allTargetsHit = allTargetsHit,
                events = allEvents,
                legs = legs,
                closedBySlAfterTp = closedBySlAfterTp,
                maxTpHit = maxTpHit
            )
        )
    }

    /** Compatibility wrapper matching legacy test_app.py imports (`verify`). */
    fun verify(signal: VerifiableSignal, candles: List<Candle>, tolerance: Double = 0.0): Triple<String, String, String?> {
        val res = verifyDetailed(signal, candles, tolerance)
        return Triple(res.status, res.detail, res.decisionTime)
    }

    private fun formatSignedG(value: Double): String {
        val g = DefaultRulesEngine.formatG(value)
        return if (value >= 0.0 && !g.startsWith("+") && !g.startsWith("-")) "+$g" else g
    }
}
