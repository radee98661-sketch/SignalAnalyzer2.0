package com.example

import com.example.domain.model.Candle
import com.example.domain.verification.VerifiableSignal
import com.example.domain.verification.VerificationEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VerificationEngineTest {

    private fun candle(ts: String, o: Double, h: Double, l: Double, c: Double) = Candle(
        provider = "binance",
        symbol = "BTCUSDT",
        exchange = "USD-M",
        interval = "1m",
        candleTimestamp = ts,
        open = o,
        high = h,
        low = l,
        close = c,
        volume = 100.0
    )

    @Test
    fun `returns awaiting_market_data when candles are empty and pending when direction or entry missing`() {
        val empty = VerificationEngine.verifyDetailed(
            VerifiableSignal(direction = "BUY", entryPrice = 100.0, stopLoss = 90.0, targets = listOf(120.0)),
            emptyList()
        )
        assertEquals("awaiting_market_data", empty.status)
        assertNull(empty.decisionTime)

        val noDir = VerificationEngine.verifyDetailed(
            VerifiableSignal(direction = null, entryPrice = 100.0),
            listOf(candle("2026-01-01T00:00:00Z", 100.0, 105.0, 95.0, 102.0))
        )
        assertEquals("pending", noDir.status)

        val noEntry = VerificationEngine.verifyDetailed(
            VerifiableSignal(direction = "BUY", entryPrice = null, entryMode = "limit"),
            listOf(candle("2026-01-01T00:00:00Z", 100.0, 105.0, 95.0, 102.0))
        )
        assertEquals("pending", noEntry.status)
    }

    @Test
    fun `returns pending when limit entry is not touched and respects tolerance boundary`() {
        val candles = listOf(
            candle("2026-01-01T00:00:00Z", 105.0, 110.0, 101.0, 108.0),
            candle("2026-01-01T00:01:00Z", 108.0, 115.0, 102.0, 114.0)
        )
        val sig = VerifiableSignal(direction = "BUY", entryPrice = 100.0, stopLoss = 95.0, targets = listOf(112.0))

        val strict = VerificationEngine.verifyDetailed(sig, candles, tolerance = 0.0)
        assertEquals("pending", strict.status)
        assertFalse(strict.timeline.legs.first().entered)

        val withTol = VerificationEngine.verifyDetailed(sig, candles, tolerance = 1.0)
        assertEquals("target_hit", withTol.status)
        assertTrue(withTol.timeline.legs.first().entered)
        assertEquals("2026-01-01T00:00:00Z", withTol.timeline.entryHitAt)
        assertEquals("2026-01-01T00:01:00Z", withTol.timeline.firstTargetHitAt)
        assertTrue(withTol.timeline.allTargetsHit)
        assertEquals(12.0, withTol.timeline.legs.first().pointsPnl, 0.0001)
    }

    @Test
    fun `closes trade as profitable target_hit when SL hits after TP1 and ignores later TP2`() {
        val candles = listOf(
            candle("2026-01-01T00:00:00Z", 100.0, 103.0, 99.0, 102.0),   // Entry 100 hit
            candle("2026-01-01T00:01:00Z", 102.0, 112.0, 101.0, 111.0),  // TP1 110 hit
            candle("2026-01-01T00:02:00Z", 111.0, 112.0, 88.0, 95.0),    // Pulls back through SL 90 -> closes trade
            candle("2026-01-01T00:03:00Z", 95.0, 125.0, 94.0, 122.0)     // Later TP2 120 must be ignored
        )
        val sig = VerifiableSignal(
            direction = "BUY",
            entryPrice = 100.0,
            stopLoss = 90.0,
            targets = listOf(110.0, 120.0)
        )
        val res = VerificationEngine.verifyDetailed(sig, candles)
        assertEquals("target_hit", res.status)
        assertFalse(res.timeline.allTargetsHit)
        assertTrue(res.timeline.closedBySlAfterTp)
        assertEquals(1, res.timeline.maxTpHit)
        assertEquals("2026-01-01T00:02:00Z", res.timeline.stopLossHitAt)
        assertEquals(listOf(1), res.timeline.legs.first().targetsHit)
        assertTrue(res.timeline.legs.first().closedBySlAfterTp)
        assertEquals(10.0, res.timeline.legs.first().pointsPnl, 0.0001)
        assertTrue(res.timeline.events.any { it.type == "sl_after_tp" })
    }

    @Test
    fun `tracks TP1 TP2 TP3 all hit without SL closure`() {
        val candles = listOf(
            candle("2026-01-01T00:00:00Z", 100.0, 102.0, 99.0, 101.0),
            candle("2026-01-01T00:01:00Z", 101.0, 112.0, 100.5, 111.0),
            candle("2026-01-01T00:02:00Z", 111.0, 122.0, 110.0, 121.0),
            candle("2026-01-01T00:03:00Z", 121.0, 133.0, 120.0, 132.0)
        )
        val sig = VerifiableSignal(
            direction = "BUY",
            entryPrice = 100.0,
            stopLoss = 90.0,
            targets = listOf(110.0, 120.0, 130.0)
        )
        val res = VerificationEngine.verifyDetailed(sig, candles)
        assertEquals("target_hit", res.status)
        assertTrue(res.timeline.allTargetsHit)
        assertFalse(res.timeline.closedBySlAfterTp)
        assertEquals(3, res.timeline.maxTpHit)
        assertNull(res.timeline.stopLossHitAt)
        assertEquals(listOf(1, 2, 3), res.timeline.legs.first().targetsHit)
        assertEquals(30.0, res.timeline.legs.first().pointsPnl, 0.0001)
    }

    @Test
    fun `closes SELL trade on SL after TP1 preserving target_hit outcome`() {
        val candles = listOf(
            candle("2026-01-01T00:00:00Z", 200.0, 201.0, 199.0, 199.5),
            candle("2026-01-01T00:01:00Z", 199.5, 200.0, 188.0, 189.0),  // TP1 190 hit
            candle("2026-01-01T00:02:00Z", 189.0, 212.0, 188.5, 211.0),  // SL 210 hit -> closes trade
            candle("2026-01-01T00:03:00Z", 211.0, 211.0, 175.0, 178.0)   // Later TP2 180 ignored
        )
        val sig = VerifiableSignal(
            direction = "SELL",
            entryPrice = 200.0,
            stopLoss = 210.0,
            targets = listOf(190.0, 180.0)
        )
        val res = VerificationEngine.verifyDetailed(sig, candles)
        assertEquals("target_hit", res.status)
        assertTrue(res.timeline.closedBySlAfterTp)
        assertFalse(res.timeline.allTargetsHit)
        assertEquals(1, res.timeline.maxTpHit)
        assertEquals(10.0, res.timeline.legs.first().pointsPnl, 0.0001)
        assertTrue(res.timeline.events.any { it.type == "sl_after_tp" })
    }

    @Test
    fun `detects same-candle SL and TP ambiguity before any prior TP`() {
        val candles = listOf(
            candle("2026-01-01T00:00:00Z", 100.0, 102.0, 99.0, 101.0),   // Entry 100 hit
            candle("2026-01-01T00:01:00Z", 101.0, 115.0, 85.0, 100.0)    // Both SL 90 and TP1 110 touched
        )
        val sig = VerifiableSignal(
            direction = "BUY",
            entryPrice = 100.0,
            stopLoss = 90.0,
            targets = listOf(110.0)
        )
        val res = VerificationEngine.verifyDetailed(sig, candles)
        assertEquals("ambiguous", res.status)
        assertEquals("2026-01-01T00:01:00Z", res.decisionTime)
        assertEquals(0.0, res.timeline.legs.first().pointsPnl, 0.0001)
    }

    @Test
    fun `computes SELL stop loss hit and target hit points PnL accurately`() {
        val slCandles = listOf(
            candle("2026-01-01T00:00:00Z", 200.0, 202.0, 198.0, 201.0),
            candle("2026-01-01T00:01:00Z", 201.0, 212.0, 200.0, 211.0)
        )
        val sellSig = VerifiableSignal(direction = "SELL", entryPrice = 200.0, stopLoss = 210.0, targets = listOf(185.0))
        val slRes = VerificationEngine.verifyDetailed(sellSig, slCandles)
        assertEquals("stop_loss_hit", slRes.status)
        assertEquals(-10.0, slRes.timeline.legs.first().pointsPnl, 0.0001)

        val tpCandles = listOf(
            candle("2026-01-01T00:00:00Z", 200.0, 202.0, 198.0, 199.0),
            candle("2026-01-01T00:01:00Z", 199.0, 200.0, 180.0, 182.0)
        )
        val tpRes = VerificationEngine.verifyDetailed(sellSig, tpCandles)
        assertEquals("target_hit", tpRes.status)
        assertEquals(15.0, tpRes.timeline.legs.first().pointsPnl, 0.0001)
    }

    @Test
    fun `evaluates multi-leg range entries independently and supports market_now open entry`() {
        val candles = listOf(
            candle("2026-01-01T00:00:00Z", 102.0, 104.0, 99.5, 101.0),   // Leg 1 (100) touched, Leg 2 (95) not yet
            candle("2026-01-01T00:01:00Z", 101.0, 102.0, 94.5, 96.0),    // Leg 2 (95) touched
            candle("2026-01-01T00:02:00Z", 96.0, 112.0, 95.5, 111.0)     // TP1 (110) hit for both legs
        )
        val multiSig = VerifiableSignal(
            direction = "BUY",
            entryPrice = 100.0,
            additionalEntries = listOf(95.0),
            stopLoss = 90.0,
            targets = listOf(110.0)
        )
        val multiRes = VerificationEngine.verifyDetailed(multiSig, candles)
        assertEquals("target_hit", multiRes.status)
        assertEquals(2, multiRes.timeline.legs.size)
        assertEquals(10.0, multiRes.timeline.legs[0].pointsPnl, 0.0001)
        assertEquals(15.0, multiRes.timeline.legs[1].pointsPnl, 0.0001)

        // market_now without explicit entry price locks candle 0 open (102.0)
        val marketSig = VerifiableSignal(
            direction = "BUY",
            entryPrice = null,
            stopLoss = 90.0,
            targets = listOf(110.0),
            entryMode = "market_now"
        )
        val marketRes = VerificationEngine.verifyDetailed(marketSig, candles)
        assertEquals("target_hit", marketRes.status)
        assertNotNull(marketRes.timeline.entryHitAt)
        assertEquals(102.0, marketRes.timeline.legs.first().entryPrice, 0.0001)
        assertEquals(8.0, marketRes.timeline.legs.first().pointsPnl, 0.0001)
    }
}
