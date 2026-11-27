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
    fun `parses crypto inline and multiline signals with leverage stripping and abbreviated target expansion matching parser py`() {
        val multiLineMsg = """
            #BTC/USDT LONG
            Entry: 65000
            TP: 67000 / 69000
            SL: 63500
        """.trimIndent()
        val multiParsed = SignalParser.parseSignal(multiLineMsg, "Crypto", defaultRules)
        assertEquals("parsed", multiParsed.status)
        assertEquals("BTCUSDT", multiParsed.symbol)
        assertEquals("BUY", multiParsed.direction)
        assertEquals("limit", multiParsed.entryMode)
        assertEquals(65000.0, multiParsed.entryPrice!!, 0.001)
        assertEquals(63500.0, multiParsed.stopLoss!!, 0.001)
        assertEquals(listOf(67000.0, 69000.0), multiParsed.targets)

        // Abbreviated target expansion via extractCleanNumbers (e.g. 1236 - 33 -> (min, max) = 1233, 1236)
        val abbrevMsg = """
            #ETH/USDT SHORT
            Leverage: 20x
            Entry: 1250
            Targets: 1236 - 33
            Stop Loss: 1270
        """.trimIndent()
        val abbrevParsed = SignalParser.parseSignal(abbrevMsg, "Crypto", defaultRules)
        assertEquals("parsed", abbrevParsed.status)
        assertEquals("ETHUSDT", abbrevParsed.symbol)
        assertEquals("SELL", abbrevParsed.direction)
        assertEquals(1250.0, abbrevParsed.entryPrice!!, 0.001)
        assertEquals(1270.0, abbrevParsed.stopLoss!!, 0.001)
        assertEquals(listOf(1233.0, 1236.0), abbrevParsed.targets)

        // SENSEX option with VIP/premium placeholder gets default SL and TP1-TP3 from rules
        val sensexRules = ParserConstants.defaultSlSeed()
        val sensexBase = sensexRules["OPTIONS"]!!.symbols["SENSEX"]!!
        sensexRules["OPTIONS"]!!.symbols["SENSEX"] = sensexBase.copy(sl = 40.0, tp1 = 45.0, tp2 = 85.0, tp3 = 125.0)
        val vipSensex = """
            💎BUY SENSEX 84200 CE
            ▶️LEVEL- 270/280
            📊TARGET & SL ONLY FOR PREMIUM MEMBERS
        """.trimIndent()
        val sensexParsed = SignalParser.parseSignal(vipSensex, "Index Options", sensexRules)
        assertEquals("parsed", sensexParsed.status)
        assertEquals("SENSEX", sensexParsed.symbol)
        assertEquals("BUY", sensexParsed.direction)
        assertEquals("CE", sensexParsed.optionType)
        assertEquals(84200.0, sensexParsed.strikePrice!!, 0.001)
        assertEquals(270.0, sensexParsed.entryPrice!!, 0.001)
        assertEquals(listOf(280.0), sensexParsed.additionalEntries)
        assertTrue(sensexParsed.defaultSlApplied)
        assertTrue(sensexParsed.defaultTpApplied)
        assertEquals(230.0, sensexParsed.stopLoss!!, 0.001)
        // BUY default targets use range midpoint (275 + 45/85/125 = 320/360/400) matching parser.py
        assertEquals(listOf(320.0, 360.0, 400.0), sensexParsed.targets)
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
    fun `enforces strict parser py market_now rules across Options, Crypto, Forex, and MCX`() {
        // Options V3.4+ strictly uses limit entries; missing numeric entry -> needs_review
        val niftyNoPrice = SignalParser.parseSignal(
            """
            BUY NIFTY 24500 CE
            ENTRY NOW
            SL: 95
            TGT: 150 / 180
            """.trimIndent(),
            "Index Options",
            defaultRules
        )
        assertEquals("needs_review", niftyNoPrice.status)
        assertEquals("limit", niftyNoPrice.entryMode)
        assertNull(niftyNoPrice.entryPrice)

        val stockNoPrice = SignalParser.parseSignal("BUY RELIANCE 1300 CE MARKET PRICE", "Stock Options", defaultRules)
        assertEquals("needs_review", stockNoPrice.status)
        assertEquals("limit", stockNoPrice.entryMode)

        // Crypto: Entry: MARKET / NOW / CMP triggers market_now
        val cryptoMkt = SignalParser.parseSignal(
            """
            #ETH/USDT SHORT
            Entry: MARKET
            Targets: 3100 / 3000
            Stop Loss: 3300
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
        // parser.py _expand_range_pair returns (min, max)
        assertEquals(listOf(3000.0, 3100.0), cryptoMkt.targets)

        // Forex: BUY NOW without price -> market_now; BUY NOW 3733 -> limit @ 3733
        val fxMkt = SignalParser.parseSignal("XAUUSD BUY NOW\nSL: 3710\nTP: 3740", "Forex", defaultRules)
        assertEquals("parsed", fxMkt.status)
        assertEquals("XAUUSD", fxMkt.symbol)
        assertNull(fxMkt.entryPrice)
        assertEquals("market_now", fxMkt.entryMode)

        val fxBuyNowLimit = SignalParser.parseSignal("XAUUSD BUY NOW 3733\nSL: 3720\nTP: 3750", "Forex", defaultRules)
        assertEquals("parsed", fxBuyNowLimit.status)
        assertEquals("limit", fxBuyNowLimit.entryMode)
        assertEquals(3733.0, fxBuyNowLimit.entryPrice!!, 0.001)

        // MCX: strictly matches \b(?:buy|sell)\s*now\b for market_now
        val mcxBuyNow = SignalParser.parseSignal("CRUDEOIL BUY NOW\nSL: 6140\nTP: 6280", "MCX", defaultRules)
        assertEquals("parsed", mcxBuyNow.status)
        assertEquals("CRUDEOIL", mcxBuyNow.symbol)
        assertNull(mcxBuyNow.entryPrice)
        assertEquals("market_now", mcxBuyNow.entryMode)
        assertEquals(6140.0, mcxBuyNow.stopLoss!!, 0.001)
        assertEquals(listOf(6280.0), mcxBuyNow.targets)

        val mcxEntryNowNoPhrase = SignalParser.parseSignal("BUY CRUDEOIL ENTRY NOW\nSL: 6140\nTP: 6280", "MCX", defaultRules)
        assertEquals("needs_review", mcxEntryNowNoPhrase.status)
        assertEquals("limit", mcxEntryNowNoPhrase.entryMode)

        // Negative guards: missing entry or exit alert stays needs_review + limit
        val missingEntry = SignalParser.parseSignal("BUY NIFTY 24500 CE", "Index Options", defaultRules)
        assertEquals("needs_review", missingEntry.status)
        assertEquals("limit", missingEntry.entryMode)

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

        val mmtMsg = """
            🚀 LONG: #MMT/USDT
            LEVERAGE: 50x

            ENTRY PRICE: 0.2325 - 0.2250
            TAKE PROFIT

            0.2400 - 0.2550 - 0.2780

            STOP LOSS: 0.2100
        """.trimIndent()

        val mmt = SignalParser.parseSignal(mmtMsg, "Crypto", defaultRules)
        assertEquals("parsed", mmt.status)
        assertEquals("MMTUSDT", mmt.symbol)
        assertEquals("BUY", mmt.direction)
        assertEquals("limit", mmt.entryMode)
        // parser.py _expand_range_pair returns (min, max) -> entry=0.225, additional=[0.2325]
        assertEquals(0.225, mmt.entryPrice!!, 0.00001)
        assertEquals(listOf(0.2325), mmt.additionalEntries)
        assertEquals(0.21, mmt.stopLoss!!, 0.00001)
        assertEquals(listOf(0.24, 0.255, 0.278), mmt.targets)
    }
}
