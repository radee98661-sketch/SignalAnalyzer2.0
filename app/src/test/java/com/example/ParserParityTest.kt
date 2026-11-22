package com.example

import com.example.domain.parser.DefaultRulesEngine
import com.example.domain.parser.ParserConstants
import com.example.domain.parser.SignalParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ParserParityTest {

    private val defaultRules = ParserConstants.defaultSlSeed()

    @Test
    fun `parses NIFTY and BANKNIFTY options with strike expiry range entry SL and targets`() {
        val msg = """
            BUY NIFTY 24500 CE 27MAR
            Entry: 120 - 125
            SL: 95
            Targets: 150 / 180 / 210
        """.trimIndent()

        val parsed = SignalParser.parseSignal(msg, "Indian Options", defaultRules)
        assertEquals("parsed", parsed.status)
        assertEquals("NIFTY", parsed.symbol)
        assertEquals("BUY", parsed.direction)
        assertEquals("CE", parsed.optionType)
        assertEquals(24500.0, parsed.strikePrice!!, 0.001)
        assertNotNull(parsed.expiryDate)
        assertEquals(120.0, parsed.entryPrice!!, 0.001)
        assertEquals(listOf(125.0), parsed.additionalEntries)
        assertEquals(95.0, parsed.stopLoss!!, 0.001)
        assertEquals(listOf(150.0, 180.0, 210.0), parsed.targets)
        assertFalse(parsed.defaultSlApplied)
        assertFalse(parsed.defaultTpApplied)
    }

    @Test
    fun `parses stock option signal and applies default SL and TPs when rules are configured`() {
        val msg = "BUY RELIANCE 1300 PE @ 42"
        // Default seed leaves STOCK_OPTIONS SL/TP null until user configures them
        val withSeed = SignalParser.parseSignal(msg, "Stock Options", defaultRules)
        assertEquals("parsed", withSeed.status)
        assertEquals("RELIANCE", withSeed.symbol)
        assertEquals("BUY", withSeed.direction)
        assertEquals("PE", withSeed.optionType)
        assertEquals(1300.0, withSeed.strikePrice!!, 0.001)
        assertEquals(42.0, withSeed.entryPrice!!, 0.001)
        assertFalse(withSeed.defaultSlApplied)
        assertNull(withSeed.stopLoss)

        // Pre-seeded NIFTY index option applies literal seed defaults (SL=20, TP=20/40/60)
        val niftyAuto = SignalParser.parseSignal("BUY NIFTY 24500 CE @ 120", "Indian Options", defaultRules)
        assertTrue(niftyAuto.defaultSlApplied)
        assertEquals(100.0, niftyAuto.stopLoss!!, 0.001)
        assertTrue(niftyAuto.defaultTpApplied)
        assertEquals(listOf(140.0, 160.0, 180.0), niftyAuto.targets)

        // Configured STOCK_OPTIONS category defaults apply automatically
        val customRules = ParserConstants.defaultSlSeed()
        customRules["STOCK_OPTIONS"] = customRules["STOCK_OPTIONS"]!!.copy(
            categoryDefault = 8.0,
            tp1 = 10.0,
            tp2 = 20.0
        )
        val parsed = SignalParser.parseSignal(msg, "Stock Options", customRules)
        assertTrue(parsed.defaultSlApplied)
        assertEquals(34.0, parsed.stopLoss!!, 0.001)
        assertTrue(parsed.defaultTpApplied)
        assertEquals(listOf(52.0, 62.0), parsed.targets)
    }

    @Test
    fun `clamps non-positive default SL safely for low-priced option BUY`() {
        // NIFTY default SL is 15 pts; if entry is 10, raw SL would be -5 -> clamped to max(0.05, entry * 0.5) = 5.0
        val msg = "BUY NIFTY 24000 CE @ 10"
        val parsed = SignalParser.parseSignal(msg, "Indian Options", defaultRules)
        assertEquals("parsed", parsed.status)
        assertTrue(parsed.defaultSlApplied)
        assertEquals(5.0, parsed.stopLoss!!, 0.001)
    }

    @Test
    fun `parses crypto slash pair strips leverage and handles market_now`() {
        val msg = """
            #BTC/USDT LONG
            Leverage: Cross 20x
            Entry: NOW
            TP1: 68500
            TP2: 69500
            SL: 66200
        """.trimIndent()

        val parsed = SignalParser.parseSignal(msg, "Crypto", defaultRules)
        assertEquals("parsed", parsed.status)
        assertEquals("BTCUSDT", parsed.symbol)
        assertEquals("BUY", parsed.direction)
        assertEquals("market_now", parsed.entryMode)
        assertNull(parsed.entryPrice)
        assertEquals(66200.0, parsed.stopLoss!!, 0.001)
        assertEquals(listOf(68500.0, 69500.0), parsed.targets)
    }

    @Test
    fun `splits multi-signal crypto message into independent signals`() {
        val msg = """
            #ETH/USDT LONG
            Entry: 3200
            TP: 3350 / 3500
            SL: 3100

            #SOLUSDT SHORT
            Entry: 185 - 188
            TP: 175 / 165
            SL: 195
        """.trimIndent()

        val signals = SignalParser.parseSignals(msg, "Crypto", defaultRules)
        assertEquals(2, signals.size)
        assertEquals("ETHUSDT", signals[0].symbol)
        assertEquals("BUY", signals[0].direction)
        assertEquals(3200.0, signals[0].entryPrice!!, 0.001)
        assertEquals(3100.0, signals[0].stopLoss!!, 0.001)

        assertEquals("SOLUSDT", signals[1].symbol)
        assertEquals("SELL", signals[1].direction)
        assertEquals(185.0, signals[1].entryPrice!!, 0.001)
        assertEquals(listOf(188.0), signals[1].additionalEntries)
        assertEquals(195.0, signals[1].stopLoss!!, 0.001)
    }

    @Test
    fun `parses forex aliases shorthand range entries numbered targets and direction filter`() {
        val goldMsg = """
            GOLD BUY 3724/20
            1) TP 3730
            2) TP 3738
            3) TP 3710
            SL: 3712
        """.trimIndent()

        val gold = SignalParser.parseSignal(goldMsg, "Forex", defaultRules)
        assertEquals("parsed", gold.status)
        assertEquals("XAUUSD", gold.symbol)
        assertEquals("BUY", gold.direction)
        assertEquals(3724.0, gold.entryPrice!!, 0.001)
        assertEquals(listOf(3720.0), gold.additionalEntries)
        assertEquals(3712.0, gold.stopLoss!!, 0.001)
        // 3710 is below refLow (3720) for BUY, so direction filter removes it
        assertEquals(listOf(3730.0, 3738.0), gold.targets)

        val wti = SignalParser.parseSignal("WTI SELL 72.50\nTP1: 71.00\nSL: 73.50", "Forex", defaultRules)
        assertEquals("USOIL", wti.symbol)
        assertEquals("SELL", wti.direction)
        assertEquals(listOf(71.0), wti.targets)

        val fxRules = ParserConstants.defaultSlSeed()
        fxRules["FOREX"]!!.symbols["XAGUSD"] = fxRules["FOREX"]!!.symbols["XAGUSD"]!!.copy(sl = 50.0)
        val silver = SignalParser.parseSignal("SILVER BUY 31.20\nTP: 32.00", "Forex", fxRules)
        assertEquals("XAGUSD", silver.symbol)
        assertTrue(silver.defaultSlApplied)
        // XAGUSD pip size is 0.01 -> 50 pips = 0.50 -> 31.20 - 0.50 = 30.70
        assertEquals(30.7, silver.stopLoss!!, 0.001)
    }

    @Test
    fun `splits multi-pair forex message into independent signals`() {
        val msg = """
            EURUSD BUY 1.0850
            TP1: 1.0900
            SL: 1.0810

            GBPUSD SELL 1.2750
            TP1: 1.2690
            SL: 1.2800
        """.trimIndent()

        val signals = SignalParser.parseSignals(msg, "Forex", defaultRules)
        assertEquals(2, signals.size)
        assertEquals("EURUSD", signals[0].symbol)
        assertEquals("BUY", signals[0].direction)
        assertEquals("GBPUSD", signals[1].symbol)
        assertEquals("SELL", signals[1].direction)
    }

    @Test
    fun `parses MCX commodity aliases multi-commodity split and MCX options delegation`() {
        val multi = """
            BUY CRUDE 6200
            TP: 6280 / 6350
            SL: 6140

            SELL NATGAS 245
            TP: 235
            SL: 252
        """.trimIndent()

        val list = SignalParser.parseSignals(multi, "MCX", defaultRules)
        assertEquals(2, list.size)
        assertEquals("CRUDEOIL", list[0].symbol)
        assertEquals("BUY", list[0].direction)
        assertEquals(6200.0, list[0].entryPrice!!, 0.001)
        assertEquals("NATURALGAS", list[1].symbol)
        assertEquals("SELL", list[1].direction)
        assertEquals(245.0, list[1].entryPrice!!, 0.001)

        val alum = SignalParser.parseSignal("BUY ALUMINUM 230\nTP: 235", "MCX", defaultRules)
        assertEquals("ALUMINIUM", alum.symbol)

        // MCX option with CE/PE delegates to hybrid options parser
        val mcxOpt = SignalParser.parseSignal("BUY CRUDEOIL 6200 CE @ 145 SL 110 TGT 190 220", "MCX", defaultRules)
        assertEquals("parsed", mcxOpt.status)
        assertEquals("CRUDEOIL", mcxOpt.symbol)
        assertEquals("CE", mcxOpt.optionType)
        assertEquals(6200.0, mcxOpt.strikePrice!!, 0.001)
    }

    @Test
    fun `marks incomplete message as needs_review and preserves JSON roundtrip of default rules`() {
        val incomplete = SignalParser.parseSignal("Good morning traders, market opens soon!", "Forex", defaultRules)
        assertEquals("needs_review", incomplete.status)
        assertEquals("required", incomplete.review)

        val json = DefaultRulesEngine.rulesToJson(defaultRules)
        val reloaded = DefaultRulesEngine.rulesFromJson(json)
        assertEquals(defaultRules.keys, reloaded.keys)
        assertEquals(defaultRules["OPTIONS"]?.symbols?.get("NIFTY")?.sl, reloaded["OPTIONS"]?.symbols?.get("NIFTY")?.sl)
        assertEquals(defaultRules["FOREX"]?.symbols?.get("XAUUSD")?.sl, reloaded["FOREX"]?.symbols?.get("XAUUSD")?.sl)
    }
}
