package com.example.domain.parser

import com.example.domain.model.DefaultSlCategoryRule
import com.example.domain.model.ParsedSignal
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

object HybridOptionsParser {

    private val KEYCAP_RE = Regex("[0-9#*]\uFE0F?\u20E3")
    private val PINNED_RE = Regex("\\bpinned\\s+\"([\\s\\S]+)\"\\s*$", RegexOption.IGNORE_CASE)
    private val EMOJI_NOISE_RE = Regex("[🥇🥈🥉🏅🎯🛑⛔🚫✅✔🚀📈📉💎🔥✨🌟⚡💢✳🟢🔴🔺🔻🔸🔹•●▪▫►▶📊📙📌💛⭕❗👆💚🐎🏮👀🔼👇❤🖼🛒🏆⚠👨🎓🟡📲☎🌁🌉☑♻❌\uFE0F\u200D]+")
    private val BOX_NOISE_RE = Regex("[╭╮╯╰━─═│┃✦★☆©®™◾]+")
    private val DASH_RE = Regex("[\u2010\u2011\u2012\u2013\u2014\u2015\u2212]")
    private val DOUBLE_DOT_RE = Regex("(\\d)\\.\\.(\\d)")
    private val PLUS_NUM_RE = Regex("(\\d)\\++")
    private val CRUDE_RE = Regex("\\bCRUDE\\s*-?\\s*OIL\\b", RegexOption.IGNORE_CASE)
    private val NATGAS_RE = Regex("\\bNATURAL\\s*-?\\s*G[AE]S\\b", RegexOption.IGNORE_CASE)
    private val ATTACHED_OPT_RE = Regex(
        "\\b([A-Z]{2,15})(${ParserConstants.MONTHS_PATTERN})(\\d{2,6}(?:\\.\\d+)?)\\s*(CE|PE|CALL|PUT)\\b",
        RegexOption.IGNORE_CASE
    )
    private val SLASH_CRYPTO_RE = Regex("\\b([A-Z0-9]{2,15})\\s*/\\s*(USDT|USDC|BUSD|USD|BTC|ETH)\\b", RegexOption.IGNORE_CASE)
    private val SLASH_FX_RE = Regex("\\b(XAU|XAG|EUR|GBP|USD|AUD|NZD|CAD|CHF)/(USD|JPY|GBP|EUR|CAD|AUD|CHF|NZD)\\b", RegexOption.IGNORE_CASE)
    private val HTML_SPACE_RE = Regex("&#x20;|&nbsp;", RegexOption.IGNORE_CASE)
    private val SPACES_RE = Regex("[ \\t]+")
    private val NUMBER_RE = Regex("[0-9,]+(?:\\.[0-9]+)?")

    fun normalizeText(text: String?): String {
        var s = text.orEmpty()
        s = KEYCAP_RE.replace(s, " ")
        s = Normalizer.normalize(s, Normalizer.Form.NFKC)
        s = buildString(s.length) {
            for (ch in s) {
                append(ParserConstants.SUP_MAP[ch] ?: ch)
            }
        }
        val pinnedMatch = PINNED_RE.find(s)
        if (pinnedMatch != null) {
            s = pinnedMatch.groupValues[1]
        }
        s = EMOJI_NOISE_RE.replace(s, " ")
        s = BOX_NOISE_RE.replace(s, " ")
        s = DASH_RE.replace(s, "-")
        s = DOUBLE_DOT_RE.replace(s, "$1.$2")
        s = PLUS_NUM_RE.replace(s, "$1")
        s = CRUDE_RE.replace(s, "CRUDEOIL")
        s = NATGAS_RE.replace(s, "NATURALGAS")
        s = ATTACHED_OPT_RE.replace(s, "$1 $2 $3 $4")
        s = SLASH_CRYPTO_RE.replace(s, "$1/$2")
        s = SLASH_FX_RE.replace(s, "$1$2")
        s = HTML_SPACE_RE.replace(s, " ")
        s = s.replace("**", "").replace("*", "").replace("₹", " ").replace("\r", "")
        s = SPACES_RE.replace(s, " ")
        return s.trim()
    }

    fun parseNumber(value: String?): Double? {
        if (value.isNullOrBlank()) return null
        val clean = value.replace(",", "").trim()
        if (clean.isEmpty()) return null
        return clean.toDoubleOrNull()
    }

    fun extractNumbers(text: String?): List<Double> {
        val out = mutableListOf<Double>()
        for (m in NUMBER_RE.findAll(text.orEmpty())) {
            val n = parseNumber(m.value)
            if (n != null) out += n
        }
        return out
    }

    fun maskSpan(text: String, start: Int, endExclusive: Int): String {
        if (start < 0 || endExclusive > text.length || start >= endExclusive) return text
        return text.substring(0, start) + " ".repeat(endExclusive - start) + text.substring(endExclusive)
    }

    private fun maskAll(text: String, regex: Regex): String =
        regex.replace(text) { " ".repeat(it.value.length) }

    fun maskNoiseTokens(text: String): String {
        var s = maskAll(
            text,
            Regex("\\b(\\d{1,2})\\s*${ParserConstants.MONTHS_PATTERN}(?:\\s*(?:20\\d{2}|EX(?:PIRY)?))?\\b", RegexOption.IGNORE_CASE)
        )
        s = maskAll(s, Regex("\\(?\\b${ParserConstants.MONTHS_PATTERN}(?:\\s*20\\d{2})?\\b\\)?", RegexOption.IGNORE_CASE))
        s = maskAll(s, Regex("\\b\\d{1,2}(?:[:.]\\d{2})?\\s*(?:AM|PM)\\b", RegexOption.IGNORE_CASE))
        s = maskAll(s, Regex("\\b\\d+(?:\\.\\d+)?(?:\\s*-\\s*\\d+(?:\\.\\d+)?)?\\s*K\\b", RegexOption.IGNORE_CASE))
        s = maskAll(s, Regex("\\b\\d+(?:\\.\\d+)?\\s*/-"))
        s = maskAll(s, Regex("\\b\\d+\\s*(?:WEEKS?|LOTS?)\\b", RegexOption.IGNORE_CASE))
        s = maskAll(s, Regex("\\bLOTS?\\s*SIZE\\s*[:=\\-]?\\s*\\d+\\b", RegexOption.IGNORE_CASE))
        s = maskAll(s, Regex("\\b\\d{10,12}\\b"))
        s = maskAll(s, Regex("@[A-Za-z0-9_]+"))
        s = maskAll(
            s,
            Regex(
                "\\b(?:LEV(?:ERAGE)?\\s*[:=\\-/]?\\s*)?(?:CROSS\\s*|ISOLATED\\s*)?\\d{1,3}(?:\\.\\d+)?\\s*[X×](?:\\s*(?:/|-|TO)\\s*\\d{1,3}(?:\\.\\d+)?\\s*[X×])?\\b",
                RegexOption.IGNORE_CASE
            )
        )
        s = maskAll(s, Regex("\\b\\d+(?:\\.\\d+)?(?:\\s*-\\s*\\d+(?:\\.\\d+)?)?\\s*%"))
        s = maskAll(s, Regex("\\b\\d{1,4}\\s*(?:DMA|EMA|SMA|MA|RSI)\\b", RegexOption.IGNORE_CASE))
        s = maskAll(s, Regex("\\bCMP\\s*[:=\\-\\s(]*[0-9,]+(?:\\.[0-9]+)?\\)?", RegexOption.IGNORE_CASE))
        return s
    }

    fun expandRangePair(rawA: String, rawB: String): Pair<Double, Double>? {
        val a = parseNumber(rawA) ?: return null
        var b = parseNumber(rawB) ?: return null
        val cleanA = rawA.replace(",", "").trim()
        val cleanB = rawB.replace(",", "").trim()

        if (a >= 100.0 && '.' !in cleanA && '.' !in cleanB && cleanA.length > cleanB.length) {
            val scale = 10.0.pow(cleanB.length).toLong()
            val base = (a.toLong() / scale) * scale
            val candidates = listOf(base + b, base + scale + b, base - scale + b)
            val best = candidates.minByOrNull { abs(it - a) }!!
            if (abs(best - a) <= a * 0.1) {
                b = best
            }
        }
        return min(a, b) to max(a, b)
    }

    fun cleanTargets(arr: List<Double>): List<Double> {
        val seen = linkedSetOf<Double>()
        for (v in arr) {
            seen.add(v)
        }
        return seen.take(10)
    }

    fun detectCategory(categoryHint: String?, text: String): String {
        val c = (categoryHint ?: "").uppercase(Locale.US)
        if ("STOCK OPTIONS" in c || "STOCK_OPTIONS" in c) return "STOCK_OPTIONS"
        if ("OPTIONS" in c) return "OPTIONS"
        if ("MCX" in c) return "MCX"
        if ("FOREX" in c || "FX" in c) return "FOREX"
        if ("CRYPTO" in c) return "CRYPTO"

        if (Regex("\\b(MCX|CRUDEOIL|NATURALGAS|NATGAS|COPPER|ZINC|GOLDM|SILVERM)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            return "MCX"
        }
        if (Regex("(?:#|\\$|\\b)[A-Z0-9]{2,15}\\s*/\\s*(?:USDT|USDC|BUSD|USD)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            return "CRYPTO"
        }
        if (Regex("\\b[A-Z0-9]{2,15}USDT\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            return "CRYPTO"
        }
        if (Regex("\\b[A-Z0-9]{2,15}\\s*/\\s*(?:LONG|SHORT|BUY|SELL)\\s*-\\s*USDT\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            return "CRYPTO"
        }
        if (Regex("(?:\\$|\\b)(?:BTC|ETH|SOL|BNB|XRP|DOGE|ADA|AVAX|LINK|DOT|1000LUNC|1000PEPE|1000SHIB|1000FLOKI|1000BONK)(?:USDT|USD)?\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            return "CRYPTO"
        }
        if (Regex("\\b(BANKNIFTY|NIFTY|FINNIFTY|MIDCPNIFTY|SENSEX)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            return "OPTIONS"
        }
        if (Regex("\\b(XAUUSD|XAGUSD|US30|NAS100|US100|SPX500|US500|GER40|UK100|USOIL|UKOIL|XTIUSD|XBRUSD|COMEX)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            return "FOREX"
        }
        if (Regex("\\b(EURUSD|GBPUSD|USDJPY|USDCHF|USDCAD|AUDUSD|NZDUSD|EURGBP|EURJPY|GBPJPY|NZDCAD|AUDJPY|CADJPY|CHFJPY|EURAUD|GBPAUD)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            return "FOREX"
        }
        if (Regex("\\b(SILVER|GOLD)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            val nums = extractNumbers(text)
            if (nums.isNotEmpty() && nums[0] < 15000.0) return "FOREX"
            return "MCX"
        }
        if (Regex("(?:^|[\\s_/\\-])\\d{2,6}\\s*(?:CE|PE|CALL|PUT)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            return "STOCK_OPTIONS"
        }
        return ""
    }

    private fun detectSymbol(category: String, text: String): String? {
        val mPair = Regex("(?:#|\\$|\\b)([A-Z0-9]{2,15})\\s*/\\s*(USDT|USDC|BUSD|USD)\\b", RegexOption.IGNORE_CASE).find(text)
        if (mPair != null && !ParserConstants.NOISE_WORDS.matches(mPair.groupValues[1])) {
            val base = mPair.groupValues[1].uppercase(Locale.US)
            val quote = mPair.groupValues[2].uppercase(Locale.US)
            return base + if (quote == "USDT" || quote == "USD") "USDT" else quote
        }

        val mAttached = Regex("(?:#|\\$|\\b)([A-Z0-9]{2,15}USDT)\\b", RegexOption.IGNORE_CASE).find(text)
        if (mAttached != null) return mAttached.groupValues[1].uppercase(Locale.US)

        val mWeird = Regex("\\b([A-Z0-9]{2,15})\\s*/\\s*(?:LONG|SHORT|BUY|SELL)\\s*-\\s*USDT\\b", RegexOption.IGNORE_CASE).find(text)
        if (mWeird != null && !ParserConstants.NOISE_WORDS.matches(mWeird.groupValues[1])) {
            return mWeird.groupValues[1].uppercase(Locale.US) + "USDT"
        }

        if (category == "MCX") {
            val mcxPatterns = listOf(
                Regex("\\bCRUDEOIL\\b", RegexOption.IGNORE_CASE) to "CRUDEOIL",
                Regex("\\bNATURALGAS\\b|\\bNATGAS\\b", RegexOption.IGNORE_CASE) to "NATURALGAS",
                Regex("\\bSILVERM\\b", RegexOption.IGNORE_CASE) to "SILVERM",
                Regex("\\bSILVER\\b", RegexOption.IGNORE_CASE) to "SILVER",
                Regex("\\bGOLDM\\b", RegexOption.IGNORE_CASE) to "GOLDM",
                Regex("\\bGOLD\\b", RegexOption.IGNORE_CASE) to "GOLD",
                Regex("\\bCOPPER\\b", RegexOption.IGNORE_CASE) to "COPPER",
                Regex("\\bZINC\\b", RegexOption.IGNORE_CASE) to "ZINC",
                Regex("\\bALUMINI?UM\\b", RegexOption.IGNORE_CASE) to "ALUMINIUM",
                Regex("\\bLEAD\\b", RegexOption.IGNORE_CASE) to "LEAD"
            )
            for ((re, sym) in mcxPatterns) {
                if (re.containsMatchIn(text)) return sym
            }
            return null
        }

        if (category == "FOREX") {
            if (Regex("\\b(XAUUSD|GOLD)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)) return "XAUUSD"
            if (Regex("\\b(XAGUSD|SILVER)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)) return "XAGUSD"
            val m = Regex("\\b(US30|NAS100|US100|SPX500|US500|GER40|UK100|USOIL|UKOIL|XTIUSD|XBRUSD|BTCUSD|ETHUSD|[A-Z]{6})\\b", RegexOption.IGNORE_CASE).find(text)
            if (m != null && !ParserConstants.FALSE_FOREX_WORDS.matches(m.groupValues[1])) {
                return m.groupValues[1].uppercase(Locale.US)
            }
            return null
        }

        if (category == "OPTIONS" || category == "STOCK_OPTIONS") {
            val m = Regex("\\b(BANKNIFTY|NIFTY|FINNIFTY|MIDCPNIFTY|SENSEX)\\b", RegexOption.IGNORE_CASE).find(text)
            if (m != null) return m.groupValues[1].uppercase(Locale.US)
            return null
        }

        if (category == "CRYPTO") {
            val mCoin = Regex(
                "(?:(?:#|\\$|\\b)(1000[A-Z]{2,10}|[A-Z]{2,12})(?:USDT|USD)?\\s+(?:LONG|SHORT|BUY|SELL)\\b|\\b(?:LONG|SHORT|BUY|SELL)\\s+(?:#|\\$)?(1000[A-Z]{2,10}|[A-Z]{2,12})(?:USDT|USD)?\\b)",
                RegexOption.IGNORE_CASE
            ).find(text)
            if (mCoin != null) {
                val rawCoin = (mCoin.groupValues[1].ifEmpty { mCoin.groupValues[2] }).uppercase(Locale.US)
                if (!ParserConstants.NOISE_WORDS.matches(rawCoin)) {
                    return if (rawCoin.endsWith("USDT")) rawCoin else "${rawCoin}USDT"
                }
            }
            return null
        }

        return null
    }

    private fun maskSymbolTokens(workingText: String, symbol: String?): String {
        if (symbol.isNullOrBlank()) return workingText
        val base = Regex("(USDT|USDC|BUSD|USD)$", RegexOption.IGNORE_CASE).replace(symbol, "")
        if (base.length < 2) return workingText
        val re = Regex(
            "(?:#|\\$|\\b)" + Regex.escape(base) + "(?:\\s*/\\s*(?:USDT|USDC|BUSD|USD)|USDT|USD)?\\b",
            RegexOption.IGNORE_CASE
        )
        return maskAll(workingText, re)
    }

    private data class OptionContractResult(
        val symbol: String?,
        val strike: Double?,
        val optionType: String?,
        val masked: String,
        val anchorEnd: Int
    )

    private fun detectOptionContract(symbolInit: String?, workingText: String): OptionContractResult {
        var symbol = symbolInit
        var masked = workingText

        val mMulti = Regex(
            "\\b(MAX\\s+HEALTH|TATA\\s+MOTORS|TATA\\s+STEEL|TATA\\s+POWER|BAJAJ\\s+FINANCE|BAJAJ\\s+AUTO|TECH\\s+MAHINDRA|SUN\\s+PHARMA|INDUSIND\\s+BANK|AXIS\\s+BANK|ICICI\\s+BANK|HDFC\\s+BANK|KOTAK\\s+BANK)\\s+(\\d{2,6}(?:\\.\\d+)?)\\s*(CE|PE|CALL|PUT)\\b",
            RegexOption.IGNORE_CASE
        ).find(masked)
        if (mMulti != null) {
            if (symbol.isNullOrBlank()) {
                symbol = Regex("\\s+").replace(mMulti.groupValues[1].uppercase(Locale.US), "")
            }
            val strike = parseNumber(mMulti.groupValues[2])
            val optType = if (mMulti.groupValues[3].uppercase(Locale.US).startsWith("C")) "CE" else "PE"
            val anchorEnd = mMulti.range.last + 1
            masked = maskSpan(masked, mMulti.range.first, anchorEnd)
            return OptionContractResult(symbol, strike, optType, masked, anchorEnd)
        }

        val m1 = Regex(
            "(?:^|[^A-Z0-9])([A-Z][A-Z0-9&\\-]{1,15})\\s*(?:(?:\\d{1,2}\\s*)?${ParserConstants.MONTHS_PATTERN}\\s*)?[/\\-_]*\\s*(\\d{2,6}(?:\\.\\d+)?)\\s*(CE|PE|CALL|PUT)\\b",
            RegexOption.IGNORE_CASE
        ).find(masked)
        if (m1 != null && !ParserConstants.NOISE_WORDS.matches(m1.groupValues[1])) {
            val rawU = m1.groupValues[1].uppercase(Locale.US)
            if (symbol.isNullOrBlank()) symbol = rawU
            val strike = parseNumber(m1.groupValues[2])
            val optType = if (m1.groupValues[3].uppercase(Locale.US).startsWith("C")) "CE" else "PE"
            val anchorEnd = m1.range.last + 1
            masked = maskSpan(masked, m1.range.first, anchorEnd)
            return OptionContractResult(symbol, strike, optType, masked, anchorEnd)
        }

        val m2 = Regex("(?:^|[\\s_/\\-])(\\d{2,6}(?:\\.\\d+)?)\\s*[/\\-]?\\s*(CE|PE|CALL|PUT)\\b", RegexOption.IGNORE_CASE).find(masked)
        if (m2 != null) {
            val strike = parseNumber(m2.groupValues[1])
            val optType = if (m2.groupValues[2].uppercase(Locale.US).startsWith("C")) "CE" else "PE"
            val anchorEnd = m2.range.last + 1
            masked = maskSpan(masked, m2.range.first, anchorEnd)
            if (symbol.isNullOrBlank()) {
                val prefix = workingText.substring(0, m2.range.first)
                val tokens = Regex("\\b[A-Z][A-Z0-9&]{1,15}\\b", RegexOption.IGNORE_CASE).findAll(prefix).map { it.value }.toList()
                val monthExact = Regex("^${ParserConstants.MONTHS_PATTERN}$", RegexOption.IGNORE_CASE)
                for (t in tokens.asReversed()) {
                    if (!ParserConstants.NOISE_WORDS.matches(t) && !monthExact.matches(t)) {
                        symbol = t.uppercase(Locale.US)
                        break
                    }
                }
            }
            return OptionContractResult(symbol, strike, optType, masked, anchorEnd)
        }

        val m3 = Regex("\\b(CE|PE)\\b", RegexOption.IGNORE_CASE).find(masked)
        if (m3 != null) {
            val optType = m3.groupValues[1].uppercase(Locale.US)
            val anchorEnd = m3.range.last + 1
            masked = maskSpan(masked, m3.range.first, anchorEnd)
            return OptionContractResult(symbol, null, optType, masked, anchorEnd)
        }

        return OptionContractResult(symbol, null, null, masked, -1)
    }

    private data class SlExtractionResult(
        val masked: String,
        val stopLoss: Double?,
        val slStatus: String?,
        val targetStatus: String?
    )

    private fun extractExplicitSl(workingText: String): SlExtractionResult {
        var masked = workingText
        var stopLoss: Double? = null
        var slStatus: String? = null

        val prem = Regex(
            "\\b(?:TARGETS?|TGT|TP)\\s*(?:&|AND)\\s*(?:SL|STOPLOSS|STPLOSS|STOP\\s*LOSS)\\s*(?:ONLY\\s*FOR\\s*)?(PREMIUM(?:\\s*MEMBERS)?|VIP|PAID)\\b",
            RegexOption.IGNORE_CASE
        ).find(masked)
        if (prem != null) {
            return SlExtractionResult(maskSpan(masked, prem.range.first, prem.range.last + 1), null, "VIP", "VIP")
        }

        val slHash = Regex("\\b(?:STOP\\s*LOSS|STOP-LOSS|STOPLOSS|STPLOSS|S/L|SL)\\s*[:=\\-\\s]{0,4}#{2,}", RegexOption.IGNORE_CASE).find(masked)
        if (slHash != null) {
            slStatus = "HIDDEN"
            masked = maskSpan(masked, slHash.range.first, slHash.range.last + 1)
        }

        val slStatusM = Regex(
            "\\b(?:STOP\\s*LOSS|STOP-LOSS|STOPLOSS|STPLOSS|S/L|SL)\\s*[:=\\-\\s]{0,6}(PAID(?:\\s*CLIENTS)?|VIP(?:\\s*GROUP)?|PREMIUM|FOLLOW|TRAIL(?:ING)?|COST|CTC|KEEP\\s*SM(?:A)?LL|SMALL)\\b",
            RegexOption.IGNORE_CASE
        ).find(masked)
        if (slStatusM != null) {
            val v = slStatusM.groupValues[1].uppercase(Locale.US)
            slStatus = when {
                "SM" in v -> "SMALL"
                "VIP" in v || "PREMIUM" in v -> "VIP"
                "PAID" in v -> "PAID"
                else -> v
            }
            masked = maskSpan(masked, slStatusM.range.first, slStatusM.range.last + 1)
        }

        val slM = Regex(
            "\\b(?:STOP\\s*LOSS|STOP-LOSS|STOPLOSS|STPLOSS|S/L|SL)(?:[ \\t]*(?:POINT|LEVEL|PRICE|ZONE|AT|NEAR|BELOW|ABOVE|IS))?[^\\w\\d\\n]{0,6}\\$?[ \\t]*([0-9,]+(?:\\.[0-9]+)?)\\b",
            RegexOption.IGNORE_CASE
        ).find(masked)
        if (slM != null) {
            stopLoss = parseNumber(slM.groupValues[1])
            masked = maskSpan(masked, slM.range.first, slM.range.last + 1)
        }

        return SlExtractionResult(masked, stopLoss, slStatus, null)
    }

    private data class TargetsExtractionResult(
        val masked: String,
        val targets: List<Double>,
        val targetStatus: String?
    )

    private fun extractExplicitTargets(
        workingText: String,
        existingTargetStatus: String?,
        pipTargetsOut: MutableList<Double>
    ): TargetsExtractionResult {
        var masked = workingText
        val targetsMap = sortedMapOf<Int, Double>()
        val targetsList = mutableListOf<Double>()
        var targetStatus = existingTargetStatus

        val pipRe = Regex("\\b(?:TARGETS?|TGT|TG|TP)\\s*[0-9]{0,2}\\s*[:=.\\-_]{0,4}\\s*(\\d+(?:\\.\\d+)?)\\s*PIPS?\\b", RegexOption.IGNORE_CASE)
        for (m in pipRe.findAll(masked).toList()) {
            val pipVal = parseNumber(m.groupValues[1])
            if (pipVal != null) pipTargetsOut += pipVal
            masked = maskSpan(masked, m.range.first, m.range.last + 1)
        }

        val statusRe = Regex(
            "\\b(?:TARGETS?|TGT|TG|TP)\\s*[0-9]{0,2}\\s*[:=.\\-_]{0,4}\\s*(?:ONLY\\s*FOR\\s*)?(VIP(?:\\s*GROUP)?|PAID(?:\\s*CLIENTS)?|OPEN|YOUR|PREMIUM(?:\\s*MEMBERS)?)\\b",
            RegexOption.IGNORE_CASE
        )
        for (m in statusRe.findAll(masked).toList()) {
            if (targetStatus == null) {
                val st = m.groupValues[1].uppercase(Locale.US)
                targetStatus = when {
                    "VIP" in st || "PREMIUM" in st -> "VIP"
                    "PAID" in st -> "PAID"
                    else -> "OPEN"
                }
            }
            masked = maskSpan(masked, m.range.first, m.range.last + 1)
        }

        val revIdxRe = Regex("\\b([1-9]|10)\\s*[_\\-.]?\\s*(?:TP|TGT|TARGET)\\s*[:=.\\-_]{0,4}\\s*[$@\\s]*([0-9,]+(?:\\.[0-9]+)?)", RegexOption.IGNORE_CASE)
        for (m in revIdxRe.findAll(masked).toList()) {
            val idx = m.groupValues[1].toInt() - 1
            val num = parseNumber(m.groupValues[2])
            if (num != null) targetsMap[idx] = num
            masked = maskSpan(masked, m.range.first, m.range.last + 1)
        }

        val mainTpRe = Regex(
            "\\b(?:PREMIUM\\s+)?(?:TARGETS?|TGT|TG|TP)(?:([1-9]|10)(?=\\s*[:=.\\-_@\$]|\\s+\\d))?\\s*[:=.\\-_]{0,4}\\s*[\$@\\s]*((?:(?:[1-9]|10)\\s*[:=.\\-]\\s*)?[\$]?\\s*[0-9,]+(?:\\.[0-9]+)?(?:\\s*(?:[-/,\\n]|AND|TO)\\s*(?:(?:[1-9]|10)\\s*[:=.\\-]\\s*)?[\$]?\\s*[0-9,]+(?:\\.[0-9]+)?)*)\\+*",
            RegexOption.IGNORE_CASE
        )
        for (m in mainTpRe.findAll(masked).toList()) {
            val idx = m.groupValues[1].toIntOrNull()?.minus(1) ?: -1
            val rawBlock = m.groupValues[2]
            val cleanedBlock = Regex("(?:^|\\n)\\s*(?:[1-9]|10)\\s*[:=.\\-]\\s*").replace(rawBlock, " ")
            val nums = extractNumbers(cleanedBlock)
            if (idx >= 0 && nums.size == 1) {
                targetsMap[idx] = nums[0]
            } else {
                targetsList.addAll(nums)
            }
            masked = maskSpan(masked, m.range.first, m.range.last + 1)
        }

        val ordered = targetsMap.values.toList() + targetsList
        return TargetsExtractionResult(masked, cleanTargets(ordered), targetStatus)
    }

    private data class EntryExtractionResult(
        val masked: String,
        val entries: List<Double>,
        val conf: String
    )

    private fun extractEntry(workingText: String, optionAnchorEnd: Int): EntryExtractionResult {
        val searchOffset = if (optionAnchorEnd > 0) optionAnchorEnd else 0
        val searchSlice = workingText.substring(searchOffset)

        var rangeCtx = Regex(
            "\\b(?:ENTRYS?|ENTRY(?:\\s*(?:BUY|SELL|PRICE|RANGE|ZONE|LEVEL))?|BUY\\s*RANGE|SELL\\s*RANGE|BUY\\s*LEVEL|SELL\\s*LEVEL|LEVEL|ZONE|RANGE|BUY|SELL|LONG|SHORT|ABOVE|ABV|BELOW|AT|NEAR(?:\\s*LEVEL)?|CMP|NOW|@)(?:\\s+(?:NOW|CMP|STOP|LIMIT|AT|RANGE|ZONE|LEVEL|TO|ONLY))?(?:\\s+[A-Z][A-Z0-9]{1,11})?\\s*[:=.\\-_@\\$\\s]{0,6}([0-9,]+(?:\\.[0-9]+)?)\\s*(?:[-/_]|TO)\\s*\\$?\\s*([0-9,]+(?:\\.[0-9]+)?)",
            RegexOption.IGNORE_CASE
        ).find(searchSlice)

        if (rangeCtx == null && optionAnchorEnd > 0) {
            rangeCtx = Regex(
                "^\\s*(?:BUY|SELL|ABOVE|ABV|BELOW|AT|NEAR|CMP|NOW|LEVEL|RANGE|TO|ONLY|@|:|-|=)*\\s*([0-9,]+(?:\\.[0-9]+)?)\\s*(?:[-/_]|TO)\\s*([0-9,]+(?:\\.[0-9]+)?)",
                RegexOption.IGNORE_CASE
            ).find(searchSlice)
        }

        if (rangeCtx != null) {
            val pair = expandRangePair(rangeCtx.groupValues[1], rangeCtx.groupValues[2])
            if (pair != null) {
                val absStart = searchOffset + rangeCtx.range.first
                return EntryExtractionResult(
                    maskSpan(workingText, absStart, absStart + rangeCtx.value.length),
                    listOf(pair.first, pair.second),
                    "HIGH"
                )
            }
        }

        val singleCtx = Regex(
            "\\b(?:ENTRYS?|ENTRY(?:\\s*(?:ABOVE|BELOW|PRICE|LEVEL|ZONE))?|BUY\\s*LEVEL|SELL\\s*LEVEL|LEVEL|ZONE|BUY(?:\\s*ABOVE|\\s*BELOW)?|SELL(?:\\s*ABOVE|\\s*BELOW)?|LONG|SHORT|ABOVE|ABV|BELOW|NEAR(?:\\s*LEVEL)?|CMP|NOW|@)(?:\\s+(?:NOW|CMP|STOP|LIMIT|AT|ONLY|TO|LEVEL))?(?:\\s+[A-Z][A-Z0-9]{1,11})?\\s*[:=.\\-_@\\$\\s]{0,6}([0-9,]+(?:\\.[0-9]+)?)\\b",
            RegexOption.IGNORE_CASE
        ).find(searchSlice)
        if (singleCtx != null) {
            val value = parseNumber(singleCtx.groupValues[1])
            if (value != null) {
                val absStart = searchOffset + singleCtx.range.first
                val entries = mutableListOf(value)
                var maskedAfter = maskSpan(workingText, absStart, absStart + singleCtx.value.length)
                val addM = Regex("\\bADD\\s*MORE\\s*[:=\\-]?\\s*([0-9,]+(?:\\.[0-9]+)?)\\b", RegexOption.IGNORE_CASE).find(maskedAfter)
                if (addM != null) {
                    val addVal = parseNumber(addM.groupValues[1])
                    if (addVal != null) {
                        entries += addVal
                        maskedAfter = maskSpan(maskedAfter, addM.range.first, addM.range.last + 1)
                    }
                }
                return EntryExtractionResult(maskedAfter, entries, "HIGH")
            }
        }

        val fbRange = Regex("([0-9,]+(?:\\.[0-9]+)?)\\s*[-/_]\\s*([0-9,]+(?:\\.[0-9]+)?)").find(searchSlice)
        if (fbRange != null) {
            val pair = expandRangePair(fbRange.groupValues[1], fbRange.groupValues[2])
            if (pair != null) {
                val absStart = searchOffset + fbRange.range.first
                return EntryExtractionResult(
                    maskSpan(workingText, absStart, absStart + fbRange.value.length),
                    listOf(pair.first, pair.second),
                    "INFERRED"
                )
            }
        }

        val fbNum = NUMBER_RE.find(searchSlice)
        if (fbNum != null) {
            val value = parseNumber(fbNum.value)
            if (value != null) {
                val absStart = searchOffset + fbNum.range.first
                return EntryExtractionResult(
                    maskSpan(workingText, absStart, absStart + fbNum.value.length),
                    listOf(value),
                    "INFERRED"
                )
            }
        }

        return EntryExtractionResult(workingText, emptyList(), "NONE")
    }

    fun parseV34Hybrid(
        message: String?,
        category: String = "",
        slRules: Map<String, DefaultSlCategoryRule>? = null
    ): ParsedSignal {
        val text = normalizeText(message)
        val detectedCat = detectCategory(category, text)

        var direction: String? = when {
            Regex("\\b(LONG|BUY)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "BUY"
            Regex("\\b(SHORT|SELL)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "SELL"
            else -> null
        }

        var symbol = detectSymbol(detectedCat, text)
        var workingText = maskSymbolTokens(text, symbol)
        var strikePrice: Double? = null
        var optionType: String? = null
        var optionAnchorEnd = -1

        if (detectedCat == "OPTIONS" || detectedCat == "STOCK_OPTIONS" ||
            Regex("(?:^|[\\s_/\\-])\\d{2,6}\\s*(?:CE|PE|CALL|PUT)\\b", RegexOption.IGNORE_CASE).containsMatchIn(workingText)
        ) {
            val optRes = detectOptionContract(symbol, workingText)
            symbol = optRes.symbol
            strikePrice = optRes.strike
            optionType = optRes.optionType
            workingText = optRes.masked
            optionAnchorEnd = optRes.anchorEnd
            if (direction == null && optionType != null) {
                direction = "BUY"
            }
        }

        val expiryMatch = Regex("\\b(\\d{1,2}\\s*${ParserConstants.MONTHS_PATTERN}[A-Z]*(?:\\s*20\\d{2})?)\\b", RegexOption.IGNORE_CASE).find(workingText)
        val expiryDate = expiryMatch?.groupValues?.get(1)?.uppercase(Locale.US)
        workingText = maskNoiseTokens(workingText)

        // Stage 2: Explicit SL & Targets
        val pipTargets = mutableListOf<Double>()
        val slRes = extractExplicitSl(workingText)
        workingText = slRes.masked
        var stopLoss = slRes.stopLoss
        val slStatus = slRes.slStatus
        val tgtRes = extractExplicitTargets(workingText, slRes.targetStatus, pipTargets)
        workingText = tgtRes.masked
        var targets = tgtRes.targets
        val targetStatus = tgtRes.targetStatus

        // Stage 3: Entry Extraction
        val entryRes = extractEntry(workingText, optionAnchorEnd)
        workingText = entryRes.masked
        val entries = entryRes.entries

        if (pipTargets.isNotEmpty() && entries.isNotEmpty() && direction != null) {
            val baseEntry = entries.sum() / entries.size
            val pipSize = DefaultRulesEngine.forexPipSize(symbol)
            val converted = pipTargets.map { p ->
                val delta = p * pipSize
                if (direction == "BUY") DefaultRulesEngine.roundValue(baseEntry + delta, 5)
                else DefaultRulesEngine.roundValue(baseEntry - delta, 5)
            }
            targets = cleanTargets(targets + converted)
        }

        // Stage 4: Direction-aware inference for remaining unlabeled numbers
        val remaining = extractNumbers(workingText)
        if (remaining.isNotEmpty() && entries.isNotEmpty() && direction != null) {
            val refLow = entries.min()
            val refHigh = entries.max()
            val refEntry = entries.sum() / entries.size
            val needTargets = targets.isEmpty() && targetStatus == null
            val needSl = stopLoss == null && slStatus == null

            if (needTargets || needSl) {
                val infTargets = mutableListOf<Double>()
                val infSl = mutableListOf<Double>()
                for (n in remaining) {
                    if (refEntry > 20.0 && n <= 10.0) continue
                    if (direction == "BUY") {
                        if (n > refHigh) infTargets += n
                        else if (n < refLow) infSl += n
                    } else if (direction == "SELL") {
                        if (n < refLow) infTargets += n
                        else if (n > refHigh) infSl += n
                    }
                }
                if (needTargets && infTargets.isNotEmpty()) {
                    if (direction == "SELL") infTargets.sortDescending() else infTargets.sort()
                    targets = cleanTargets(infTargets)
                }
                if (needSl && infSl.isNotEmpty()) {
                    stopLoss = infSl.last()
                }
            }
        }

        // Stage 5: Validation & Confidence
        val errors = mutableListOf<String>()
        if (symbol.isNullOrBlank()) errors += "missing instrument"
        if (direction == null) errors += "missing direction"
        if (entries.isEmpty()) errors += "missing entry"

        var ambiguous = false
        if (entries.isNotEmpty() && direction != null) {
            val refLow = entries.min()
            val refHigh = entries.max()
            if (direction == "BUY") {
                if (stopLoss != null && stopLoss >= refHigh) {
                    ambiguous = true
                    errors += "BUY SL >= entry"
                }
                if (targets.any { it <= refLow }) {
                    ambiguous = true
                    errors += "BUY target <= entry"
                }
            } else if (direction == "SELL") {
                if (stopLoss != null && stopLoss <= refLow) {
                    ambiguous = true
                    errors += "SELL SL <= entry"
                }
                if (targets.any { it >= refHigh }) {
                    ambiguous = true
                    errors += "SELL target >= entry"
                }
            }
        }

        val hasRiskOrTarget = stopLoss != null || slStatus != null || targets.isNotEmpty() || targetStatus != null
        var confidence = 0.15 +
            (if (direction != null) 0.25 else 0.0) +
            (if (!symbol.isNullOrBlank()) 0.25 else 0.0) +
            (if (entries.isNotEmpty()) 0.20 else 0.0) +
            (if (hasRiskOrTarget) 0.15 else 0.0)
        if (ambiguous) confidence = min(confidence, 0.55)

        val status = if (direction != null && !symbol.isNullOrBlank() && entries.isNotEmpty() && !ambiguous) {
            "parsed"
        } else {
            "needs_review"
        }

        val notes = mutableListOf<String>()
        if (slStatus != null) notes += "SL=$slStatus"
        if (targetStatus != null) notes += "Targets=$targetStatus"
        if (errors.isNotEmpty()) notes += "Issues: ${errors.joinToString(", ")}"

        val explanation = "V3.4+ hybrid parser (${detectedCat.ifEmpty { "AUTO" }}): extracted direction, symbol/contract, entry" +
            if (notes.isNotEmpty()) " (${notes.joinToString("; ")})" else "."

        var result = ParsedSignal(
            category = detectedCat,
            symbol = symbol,
            direction = direction,
            entryPrice = entries.firstOrNull(),
            additionalEntries = if (entries.size > 1) entries.drop(1) else emptyList(),
            stopLoss = stopLoss,
            targets = targets,
            optionType = optionType,
            strikePrice = strikePrice,
            expiryDate = expiryDate,
            entryMode = "limit",
            status = status,
            confidence = DefaultRulesEngine.roundValue(confidence, 2),
            review = if (status != "parsed") "required" else "not_required",
            explanation = explanation
        )

        if (slRules != null && result.stopLoss == null && !ambiguous) {
            result = DefaultRulesEngine.applyDefaultSl(result, category.ifBlank { detectedCat }, slRules)
        }
        if (slRules != null && result.targets.isEmpty() && !ambiguous) {
            result = DefaultRulesEngine.applyDefaultTargets(result, category.ifBlank { detectedCat }, slRules)
        }
        return result
    }
}
