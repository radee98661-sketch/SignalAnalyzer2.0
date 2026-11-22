package com.example.domain.parser

import com.example.domain.model.DefaultSlCategoryRule
import com.example.domain.model.DefaultSlSymbolRule

object ParserConstants {

    val SUP_MAP: Map<Char, Char> = mapOf(
        '⁰' to '0', '¹' to '1', '²' to '2', '³' to '3', '⁴' to '4',
        '⁵' to '5', '⁶' to '6', '⁷' to '7', '⁸' to '8', '⁹' to '9',
        '①' to ' ', '②' to ' ', '③' to ' ', '④' to ' ', '⑤' to ' ',
        '⑥' to ' ', '⑦' to ' ', '⑧' to ' ', '⑨' to ' ', '⑩' to ' ',
        '❶' to ' ', '❷' to ' ', '❸' to ' ', '❹' to ' ', '❺' to ' ',
        '❻' to ' ', '❼' to ' ', '❽' to ' ', '❾' to ' ', '❿' to ' ',
        '➀' to ' ', '➁' to ' ', '➂' to ' ', '➃' to ' ', '➄' to ' ',
        '➅' to ' ', '➆' to ' ', '➇' to ' ', '➈' to ' ', '➉' to ' ',
        '➊' to ' ', '➋' to ' ', '➌' to ' ', '➍' to ' ', '➎' to ' ',
        '➏' to ' ', '➐' to ' ', '➑' to ' ', '➒' to ' ', '➓' to ' '
    )

    val NOISE_WORDS = Regex(
        "^(BUY|SELL|LONG|SHORT|ENTRY|ENTRYS|ABOVE|ABV|BELOW|BLW|NOW|CMP|AT|NEAR|SL|TP|TGT|TARGET|TARGETS|STOPLOSS|STPLOSS|LEVEL|AGAIN|HERO|ZERO|COIN|PAIR|TYPE|SCALP|CROSS|MARGIN|RISK|USDT|USD|PERP|OPTION|OPTIONS|CALL|CALLS|PUT|PUTS|STOCK|STOCKS|ALERT|INTRADAY|POSITIONAL|BTST|STBT|HIGHLY|BREAKOUT|TRADE|WEEK|WAIT|FOR|PINNED|KING|LOOKING|GOOD|ONLY|MOVE|FAST|JOINING|FREE|CAPITAL|REQUIRED|PROFIT|DEMO|PREMIUM|RECOVERY|FUTURE|COMMODITY|MCX|EXPIRY|EX)$",
        RegexOption.IGNORE_CASE
    )

    val FALSE_FOREX_WORDS = Regex(
        "^(TARGET|STOPLO|STPLOS|SIGNAL|OPTION|CENTER|MASTER|TRADER|EXPERT|CRYPTO|ANDREW|FREE|TRAIL|SCALP|CROSS|MARGIN|COINS|ENTRY|ENTRYS|SHORT|LONG|ALERT|PINNED|LUPIN|PNB|ABB|SENSEX|COPPER|SILVER|GOLD)$",
        RegexOption.IGNORE_CASE
    )

    const val MONTHS_PATTERN =
        "(?:JAN(?:UARY)?|FEB(?:RUARY)?|MAR(?:CH)?|APR(?:IL)?|MAY|JUN(?:E)?|JUL(?:Y)?|AUG(?:UST)?|SEP(?:TEMBER)?|OCT(?:OBER)?|NOV(?:EMBER)?|DEC(?:EMBER)?)"

    val MCX_COMMODITY_ALIASES: Map<String, String> = linkedMapOf(
        "CRUDEOILM" to "CRUDEOILM",
        "CRUDEOIL" to "CRUDEOIL",
        "CRUDE" to "CRUDEOIL",
        "NATURALGASM" to "NATURALGASM",
        "NATURALGAS" to "NATURALGAS",
        "NATGASMINI" to "NATGASMINI",
        "NATGAS" to "NATURALGAS",
        "GOLDM" to "GOLDM",
        "GOLDPETAL" to "GOLDPETAL",
        "GOLDGUINEA" to "GOLDGUINEA",
        "GOLD" to "GOLD",
        "SILVERMIC" to "SILVERMIC",
        "SILVERM" to "SILVERM",
        "SILVER" to "SILVER",
        "COPPER" to "COPPER",
        "ZINC" to "ZINC",
        "ALUMINIUM" to "ALUMINIUM",
        "ALUMINUM" to "ALUMINIUM",
        "LEAD" to "LEAD",
        "NICKEL" to "NICKEL"
    )

    val FOREX_SYMBOL_ALIASES: Map<String, String> = linkedMapOf(
        "GOLD" to "XAUUSD",
        "XAU" to "XAUUSD",
        "XAUUSD" to "XAUUSD",
        "SILVER" to "XAGUSD",
        "XAG" to "XAGUSD",
        "XAGUSD" to "XAGUSD",
        "USOIL" to "USOIL",
        "WTI" to "USOIL",
        "WTIUSD" to "USOIL",
        "XTIUSD" to "USOIL",
        "UKOIL" to "UKOIL",
        "BRENT" to "UKOIL",
        "XBRUSD" to "UKOIL"
    )

    val FOREX_PAIRS: Set<String> = linkedSetOf(
        "EURUSD", "GBPUSD", "USDJPY", "GBPJPY", "AUDUSD", "USDCAD", "USDCHF", "NZDUSD",
        "EURJPY", "EURGBP", "EURAUD", "EURCAD", "EURCHF", "GBPAUD", "GBPCAD", "GBPCHF",
        "AUDJPY", "AUDCAD", "AUDNZD", "CADJPY", "CHFJPY", "NZDJPY",
        "XAUUSD", "XAGUSD", "USOIL", "UKOIL", "US30", "NAS100", "US100", "SPX500", "US500", "GER40", "UK100"
    )

    fun defaultSlSeed(): MutableMap<String, DefaultSlCategoryRule> = linkedMapOf(
        "OPTIONS" to DefaultSlCategoryRule(
            categoryDefault = 20.0, tp1 = 20.0, tp2 = 40.0, tp3 = 60.0, lots = 1.0, lotSize = 65.0,
            symbols = linkedMapOf(
                "NIFTY" to DefaultSlSymbolRule(sl = 20.0, tp1 = 20.0, tp2 = 40.0, tp3 = 60.0, lots = 1.0, lotSize = 65.0),
                "BANKNIFTY" to DefaultSlSymbolRule(sl = 40.0, tp1 = 40.0, tp2 = 80.0, tp3 = 120.0, lots = 1.0, lotSize = 30.0),
                "FINNIFTY" to DefaultSlSymbolRule(sl = 20.0, tp1 = 20.0, tp2 = 40.0, tp3 = 60.0, lots = 1.0, lotSize = 60.0),
                "MIDCPNIFTY" to DefaultSlSymbolRule(sl = 20.0, tp1 = 20.0, tp2 = 40.0, tp3 = 60.0, lots = 1.0, lotSize = 120.0),
                "SENSEX" to DefaultSlSymbolRule(sl = 40.0, tp1 = 40.0, tp2 = 80.0, tp3 = 120.0, lots = 1.0, lotSize = 20.0)
            )
        ),
        "STOCK_OPTIONS" to DefaultSlCategoryRule(
            categoryDefault = null, tp1 = null, tp2 = null, tp3 = null, lots = 1.0, lotSize = 500.0,
            symbols = linkedMapOf()
        ),
        "MCX" to DefaultSlCategoryRule(
            categoryDefault = null, tp1 = null, tp2 = null, tp3 = null, lots = 1.0, lotSize = 100.0,
            symbols = linkedMapOf(
                "CRUDEOIL:OPT" to DefaultSlSymbolRule(sl = 20.0, tp1 = 20.0, tp2 = 40.0, tp3 = 60.0, lots = 1.0, lotSize = 100.0),
                "CRUDEOIL:FUT" to DefaultSlSymbolRule(sl = 50.0, tp1 = 50.0, tp2 = 100.0, tp3 = 150.0, lots = 1.0, lotSize = 100.0),
                "NATURALGAS:FUT" to DefaultSlSymbolRule(lots = 1.0, lotSize = 1250.0),
                "NATURALGAS:OPT" to DefaultSlSymbolRule(lots = 1.0, lotSize = 1250.0),
                "GOLD:FUT" to DefaultSlSymbolRule(lots = 1.0, lotSize = 100.0),
                "GOLDM:OPT" to DefaultSlSymbolRule(lots = 1.0, lotSize = 10.0),
                "SILVER:FUT" to DefaultSlSymbolRule(lots = 1.0, lotSize = 30.0),
                "SILVERM:OPT" to DefaultSlSymbolRule(lots = 1.0, lotSize = 5.0),
                "COPPER:FUT" to DefaultSlSymbolRule(lots = 1.0, lotSize = 2500.0)
            )
        ),
        "FOREX" to DefaultSlCategoryRule(
            categoryDefault = null, tp1 = null, tp2 = null, tp3 = null, lots = 1.0, lotSize = 100000.0,
            symbols = linkedMapOf(
                "XAUUSD" to DefaultSlSymbolRule(lots = 1.0, lotSize = 100.0),
                "XAGUSD" to DefaultSlSymbolRule(lots = 1.0, lotSize = 5000.0),
                "EURUSD" to DefaultSlSymbolRule(lots = 1.0, lotSize = 100000.0),
                "GBPUSD" to DefaultSlSymbolRule(lots = 1.0, lotSize = 100000.0),
                "USDJPY" to DefaultSlSymbolRule(lots = 1.0, lotSize = 100000.0),
                "GBPJPY" to DefaultSlSymbolRule(lots = 1.0, lotSize = 100000.0),
                "AUDUSD" to DefaultSlSymbolRule(lots = 1.0, lotSize = 100000.0),
                "USDCAD" to DefaultSlSymbolRule(lots = 1.0, lotSize = 100000.0),
                "USDCHF" to DefaultSlSymbolRule(lots = 1.0, lotSize = 100000.0),
                "NZDUSD" to DefaultSlSymbolRule(lots = 1.0, lotSize = 100000.0),
                "USOIL" to DefaultSlSymbolRule(lots = 1.0, lotSize = 100.0)
            )
        ),
        "CRYPTO" to DefaultSlCategoryRule(
            categoryDefault = null, tp1 = null, tp2 = null, tp3 = null, lots = null, lotSize = null, usdAmount = 100.0,
            symbols = linkedMapOf(
                "BTCUSDT" to DefaultSlSymbolRule(usdAmount = 100.0),
                "ETHUSDT" to DefaultSlSymbolRule(usdAmount = 100.0),
                "SOLUSDT" to DefaultSlSymbolRule(usdAmount = 100.0)
            )
        )
    )
}
