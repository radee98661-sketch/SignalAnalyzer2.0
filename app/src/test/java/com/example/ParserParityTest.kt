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
    fun `parses NAORISUSDT scalp crypto signal with hyphenated Stop-Loss and Account Usage percentage`() {
        val msg = """
            ✨ Project Type :- SCALP 🌟

            🌈PAIR:- #NAORIS/USDT
            ⚡️ Direction : LONG
            ⚙️ Leverage: Cross 25X TO 50X

            🎯 Entrys : 0.03660

            🏆 Targets:

            🥇 0.03750
            🥈 0.03870
            🥉 0.04100

            🚫 Stop-Loss: 0.03440

            🛡Account Usage:- 1 - 2%
            (According To your Risk Setting) 


            Published By - @cryptohunter7717
        """.trimIndent()

        val parsed = SignalParser.parseSignal(msg, "Crypto", defaultRules)
        assertEquals("parsed", parsed.status)
        assertEquals("NAORISUSDT", parsed.symbol)
        assertEquals("BUY", parsed.direction)
        assertEquals("limit", parsed.entryMode)
        assertEquals(0.0366, parsed.entryPrice!!, 0.00001)
        assertTrue(parsed.additionalEntries.isEmpty())
        assertEquals(0.0344, parsed.stopLoss!!, 0.00001)
        assertEquals(listOf(0.0375, 0.0387, 0.041), parsed.targets)
    }

    @Test
    fun `parses EVAAUSDT Cornix multi-line Entry Targets Take-Profit Targets Stop Target and Trailing Config`() {
        val msg = """
            ⚡️⚡️ #EVAA/USDT️️️️⚡️️⚡️
            SIGNAL Type: Regular (LONG) 🔼📈
            Leverage: Cross (20X) 
            Amount: 1% ⚠️

            Entry Targets:

            1) ➡️  3.44

            2) ➡️  3.35

            Take-Profit Targets:

            1) 🔼 3.58

            2) 🔼 3.80

            3) 🔼 4.10

            Stop Target:

            1) ⛔️ 3.25

            Trailing Configuration:
            Stop: Breakeven -
              Trigger: Target (1)

            NO REACTS 🔥
        """.trimIndent()

        val parsed = SignalParser.parseSignal(msg, "Crypto", defaultRules)
        assertEquals("parsed", parsed.status)
        assertEquals("EVAAUSDT", parsed.symbol)
        assertEquals("BUY", parsed.direction)
        assertEquals("limit", parsed.entryMode)
        assertEquals(3.44, parsed.entryPrice!!, 0.0001)
        assertEquals(listOf(3.35), parsed.additionalEntries)
        assertEquals(3.25, parsed.stopLoss!!, 0.0001)
        assertEquals(listOf(3.58, 3.8, 4.1), parsed.targets)
    }

    @Test
    fun `parses SHORT Cornix crypto signal and single-line crypto signal without section bleed`() {
        val shortMsg = """
            #SUI/USDT
            Signal Type: Regular (SHORT)
            Leverage: Isolated (10X)
            Entry Targets:
            1) 2.50
            2) 2.60
            Take-Profit Targets:
            1) 2.35
            2) 2.20
            3) 2.00
            Stop Target:
            1) 2.75
            Trailing Configuration:
            Stop: Breakeven -
              Trigger: Target (1)
        """.trimIndent()

        val shortParsed = SignalParser.parseSignal(shortMsg, "Crypto", defaultRules)
        assertEquals("parsed", shortParsed.status)
        assertEquals("SUIUSDT", shortParsed.symbol)
        assertEquals("SELL", shortParsed.direction)
        assertEquals(2.50, shortParsed.entryPrice!!, 0.0001)
        assertEquals(listOf(2.60), shortParsed.additionalEntries)
        assertEquals(2.75, shortParsed.stopLoss!!, 0.0001)
        assertEquals(listOf(2.35, 2.20, 2.00), shortParsed.targets)

        val inlineMsg = "BTCUSDT LONG Entry: 65000 TP: 67000 / 69000 SL: 63500"
        val inlineParsed = SignalParser.parseSignal(inlineMsg, "Crypto", defaultRules)
        assertEquals("parsed", inlineParsed.status)
        assertEquals("BTCUSDT", inlineParsed.symbol)
        assertEquals("BUY", inlineParsed.direction)
        assertEquals(65000.0, inlineParsed.entryPrice!!, 0.001)
        assertEquals(63500.0, inlineParsed.stopLoss!!, 0.001)
        assertEquals(listOf(67000.0, 69000.0), inlineParsed.targets)
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

    @Test
    fun `detects market_now keywords across Index Options, Stock Options, Crypto, Forex, and MCX`() {
        // Index Options: Entry Now, At CMP, trailing NOW
        val niftyEntryNow = SignalParser.parseSignal(
            """
            BUY NIFTY 24500 CE
            ENTRY NOW
            SL: 95
            TGT: 150 / 180
            """.trimIndent(),
            "Index Options",
            defaultRules
        )
        assertEquals("parsed", niftyEntryNow.status)
        assertEquals("NIFTY", niftyEntryNow.symbol)
        assertEquals("BUY", niftyEntryNow.direction)
        assertEquals("CE", niftyEntryNow.optionType)
        assertEquals(24500.0, niftyEntryNow.strikePrice!!, 0.001)
        assertNull(niftyEntryNow.entryPrice)
        assertEquals("market_now", niftyEntryNow.entryMode)
        assertEquals(95.0, niftyEntryNow.stopLoss!!, 0.001)
        assertEquals(listOf(150.0, 180.0), niftyEntryNow.targets)

        val bnCmp = SignalParser.parseSignal("BUY BANKNIFTY 52000 PE AT CMP\nSL 250\nTARGET 350 400", "Index Options", defaultRules)
        assertEquals("parsed", bnCmp.status)
        assertNull(bnCmp.entryPrice)
        assertEquals("market_now", bnCmp.entryMode)
        assertEquals(250.0, bnCmp.stopLoss!!, 0.001)
        assertEquals(listOf(350.0, 400.0), bnCmp.targets)

        // Stock Options: Market Price
        val stockMkt = SignalParser.parseSignal("BUY RELIANCE 1300 CE MARKET PRICE", "Stock Options", defaultRules)
        assertEquals("parsed", stockMkt.status)
        assertEquals("RELIANCE", stockMkt.symbol)
        assertNull(stockMkt.entryPrice)
        assertEquals("market_now", stockMkt.entryMode)

        // Crypto: Enter at Market Price & Cornix multi-line Entry Targets -> Market
        val cryptoMkt = SignalParser.parseSignal(
            """
            #ETH/USDT SHORT
            Enter at Market Price
            Targets: 3100 / 3000
            Stop-Loss: 3300
            """.trimIndent(),
            "Crypto",
            defaultRules
        )
        assertEquals("parsed", cryptoMkt.status)
        assertEquals("ETHUSDT", cryptoMkt.symbol)
        assertEquals("SELL", cryptoMkt.direction)
        assertNull(cryptoMkt.entryPrice)
        assertEquals("market_now", cryptoMkt.entryMode)
        assertEquals(3300.0, cryptoMkt.stopLoss!!, 0.001)
        assertEquals(listOf(3100.0, 3000.0), cryptoMkt.targets)

        val cornixMkt = SignalParser.parseSignal(
            """
            #DOGE/USDT
            Signal Type: Regular (LONG)
            Entry Targets:
            1) Market
            Take-Profit Targets:
            1) 0.165
            2) 0.175
            Stop Target:
            1) 0.145
            """.trimIndent(),
            "Crypto",
            defaultRules
        )
        assertEquals("parsed", cornixMkt.status)
        assertNull(cornixMkt.entryPrice)
        assertEquals("market_now", cornixMkt.entryMode)
        assertEquals(0.145, cornixMkt.stopLoss!!, 0.0001)
        assertEquals(listOf(0.165, 0.175), cornixMkt.targets)

        // Forex: Market Execution / Enter Now
        val fxMkt = SignalParser.parseSignal("XAUUSD BUY MARKET EXECUTION\nSL: 3710\nTP: 3740", "Forex", defaultRules)
        assertEquals("parsed", fxMkt.status)
        assertEquals("XAUUSD", fxMkt.symbol)
        assertNull(fxMkt.entryPrice)
        assertEquals("market_now", fxMkt.entryMode)

        // MCX: Entry Now / At CMP
        val mcxMkt = SignalParser.parseSignal("BUY CRUDEOIL ENTRY NOW\nSL: 6140\nTP: 6280", "MCX", defaultRules)
        assertEquals("parsed", mcxMkt.status)
        assertEquals("CRUDEOIL", mcxMkt.symbol)
        assertNull(mcxMkt.entryPrice)
        assertEquals("market_now", mcxMkt.entryMode)
        assertEquals(6140.0, mcxMkt.stopLoss!!, 0.001)
        assertEquals(listOf(6280.0), mcxMkt.targets)

        // Negative guard: missing entry without market-now keyword stays needs_review
        val missingEntry = SignalParser.parseSignal("BUY NIFTY 24500 CE", "Index Options", defaultRules)
        assertEquals("needs_review", missingEntry.status)
        assertEquals("limit", missingEntry.entryMode)

        // Negative guard: exit alert ("BOOK NOW") does NOT trigger market_now entry
        val bookAlert = SignalParser.parseSignal("NIFTY 24500 CE BOOK NOW", "Index Options", defaultRules)
        assertEquals("needs_review", bookAlert.status)
        assertEquals("limit", bookAlert.entryMode)
    }

    @Test
    fun `parses user ALGENSYN implicit market_now and PHAROS Entry NOW crypto signals`() {
        val algensynMsg = """
            #ALGENSYN.   LONG.   SL 0.01530

            TP 0.01650,  0.01750, 0.019, 0.022, 0.027 +

            Leverage 20x 50x
        """.trimIndent()

        val algensyn = SignalParser.parseSignal(algensynMsg, "Crypto", defaultRules)
        assertEquals("parsed", algensyn.status)
        assertEquals("ALGENSYNUSDT", algensyn.symbol)
        assertEquals("BUY", algensyn.direction)
        assertNull(algensyn.entryPrice)
        assertEquals("market_now", algensyn.entryMode)
        assertEquals(0.0153, algensyn.stopLoss!!, 0.00001)
        assertEquals(listOf(0.0165, 0.0175, 0.019, 0.022, 0.027), algensyn.targets)

        val pharosMsg = """
            BUY PHAROS/USDT   

            Free Pump 🔥

            Entry: NOW 

            TARGETS 

            0.595 
            0.615 
            0.660 

            SL: 0.535

            (You must  manage your risk)
            🍁 RED LOTUS
        """.trimIndent()

        val pharos = SignalParser.parseSignal(pharosMsg, "Crypto", defaultRules)
        assertEquals("parsed", pharos.status)
        assertEquals("PHAROSUSDT", pharos.symbol)
        assertEquals("BUY", pharos.direction)
        assertNull(pharos.entryPrice)
        assertEquals("market_now", pharos.entryMode)
        assertEquals(0.535, pharos.stopLoss!!, 0.0001)
        assertEquals(listOf(0.595, 0.615, 0.66), pharos.targets)

        val wolfMmtMsg = """
            👍 THE WOLF SCALPER 👍

            🚀 LONG: ${'$'}MMT/USDT
            LEVERAGE: 50x

            ✔️ENTRY PRICE:0.2325
            ✔️ 2nd ENTRY: 0.2250
            __________
            ☄️ TAKE- PROFIT

            0.2400 - 0.2550 - 0.2780

            STOP LOSS: 0.2100

            CLICK TO JOIN BITUNIX

            #WE_STAND_WITH_PALESTINE

            REACTIONS?
        """.trimIndent()

        val wolfMmt = SignalParser.parseSignal(wolfMmtMsg, "Crypto", defaultRules)
        assertEquals("parsed", wolfMmt.status)
        assertEquals("MMTUSDT", wolfMmt.symbol)
        assertEquals("BUY", wolfMmt.direction)
        assertEquals("limit", wolfMmt.entryMode)
        assertEquals(0.2325, wolfMmt.entryPrice!!, 0.00001)
        assertEquals(listOf(0.225), wolfMmt.additionalEntries)
        assertEquals(0.21, wolfMmt.stopLoss!!, 0.00001)
        assertEquals(listOf(0.24, 0.255, 0.278), wolfMmt.targets)
    }
}
