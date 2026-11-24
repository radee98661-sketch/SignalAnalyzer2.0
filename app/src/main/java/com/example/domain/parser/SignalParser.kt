package com.example.domain.parser

import com.example.domain.model.DefaultSlCategoryRule
import com.example.domain.model.ParsedSignal
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow

object SignalParser {

    private fun stripLeverageNoise(text: String): String {
        var cleaned = Regex(
            "(?:leverage|lev)?\\s*[:\\-]?\\s*(?:cross|isolated)?\\s*\\(?\\s*\\d+(?:\\.\\d+)?\\s*[x×X]?(?:\\s*(?:-|/|to|[ \\t]+)\\s*\\d+(?:\\.\\d+)?\\s*[x×X])+\\s*\\)?",
            RegexOption.IGNORE_CASE
        ).replace(text, " ")
        cleaned = Regex(
            "(?:leverage|lev)?\\s*[:\\-]?\\s*(?:cross|isolated)?\\s*\\(?\\s*\\d+(?:\\.\\d+)?\\s*[x×X]\\s*\\)?",
            RegexOption.IGNORE_CASE
        ).replace(cleaned, " ")
        cleaned = Regex("\\b(?:cross|isolated)\\s*\\(?\\s*\\d+(?:\\.\\d+)?\\s*[x×X]?\\s*\\)?", RegexOption.IGNORE_CASE).replace(cleaned, " ")
        cleaned = Regex("\\(?\\b\\d+(?:\\.\\d+)?\\s*[x×X]\\b\\)?", RegexOption.IGNORE_CASE).replace(cleaned, " ")
        return cleaned
    }

    private fun extractCleanNumbers(text: String?): List<Double> {
        if (text.isNullOrBlank()) return emptyList()
        val rangeM = Regex("([0-9,]+(?:\\.[0-9]+)?)\\s*(?:-|/|TO)\\s*([0-9,]+(?:\\.[0-9]+)?)", RegexOption.IGNORE_CASE).find(text)
        if (rangeM != null) {
            val pair = HybridOptionsParser.expandRangePair(rangeM.groupValues[1], rangeM.groupValues[2])
            if (pair != null) {
                val rest = HybridOptionsParser.maskSpan(text, rangeM.range.first, rangeM.range.last + 1)
                val extras = HybridOptionsParser.extractNumbers(rest)
                    .filter { it > 0.0 && it != pair.first && it != pair.second }
                return listOf(pair.first, pair.second) + extras
            }
        }
        val out = mutableListOf<Double>()
        for (n in HybridOptionsParser.extractNumbers(text)) {
            if (n > 0.0 && n !in out) out += n
        }
        return out
    }

    private fun extractTargetsFromBlock(blockText: String?): List<Double> {
        if (blockText.isNullOrBlank()) return emptyList()
        val targets = mutableListOf<Double>()
        for (line in blockText.lines()) {
            var cleanLine = line.trim()
            if (cleanLine.isEmpty()) continue
            cleanLine = Regex("\\b\\d+(?:\\.\\d+)?(?:\\s*(?:-|to|/)\\s*\\d+(?:\\.\\d+)?)?\\s*%", RegexOption.IGNORE_CASE)
                .replace(cleanLine, " ")
            cleanLine = Regex(
                "^(?:take[\\s\\-]+profit[\\s\\-]+targets?|take[\\s\\-]+profit|entry[\\s\\-]+targets?|targets?|tps?|t)\\s*(?:\\d+(?:st|nd|rd|th)?|1st|2nd|3rd|4th|5th)?\\s*[:\\-]?\\s*",
                RegexOption.IGNORE_CASE
            ).replace(cleanLine, "")
            cleanLine = Regex("(?:^|\\s)\\d{1,2}\\s*\\)\\s*").replace(cleanLine, " ")
            cleanLine = Regex("^\\s*\\d{1,2}\\s*[.:\\-]\\s+").replace(cleanLine, "")
            cleanLine = Regex(
                "\\b(?:tp|target)\\s*(?:\\d+(?:st|nd|rd|th)?|1st|2nd|3rd|4th|5th)\\s*[:\\-]?\\s*",
                RegexOption.IGNORE_CASE
            ).replace(cleanLine, " ")
            for (num in HybridOptionsParser.extractNumbers(cleanLine)) {
                if (num > 0.0 && num !in targets) targets += num
            }
        }
        return HybridOptionsParser.cleanTargets(targets)
    }

    private const val SECTION_BREAK = "__SECTION_BREAK__"

    private fun replaceSpanWithBreak(text: String, start: Int, endExclusive: Int): String {
        if (start < 0 || endExclusive > text.length || start >= endExclusive) return text
        return text.substring(0, start) + "\n$SECTION_BREAK\n" + text.substring(endExclusive)
    }

    fun parseSingleCryptoBlock(
        rawBlock: String,
        category: String = "Crypto",
        slRules: Map<String, DefaultSlCategoryRule>? = null
    ): ParsedSignal {
        var raw = HybridOptionsParser.normalizeText(rawBlock)
        raw = Regex("[→➔➜➤➡⇒]+|->").replace(raw, " ")
        var cleaned = stripLeverageNoise(raw)
        // Strip Trailing Configuration section (e.g. "Trailing Configuration:\nStop: Breakeven -\nTrigger: Target (1)")
        cleaned = Regex(
            "\\btrailing\\s+configuration\\b[\\s\\S]*?(?=\\n\\s*\\n|\\n[ \\t]*(?:entry|take[\\s\\-]*profit|stop[\\s\\-]*target|stop[\\s\\-]*loss|sl\\b)|$)",
            RegexOption.IGNORE_CASE
        ).replace(cleaned, "\n$SECTION_BREAK\n")
        // Strip risk/usage/amount percentage noise & Telegram handles so digits inside them never leak as prices
        cleaned = Regex(
            "\\b(?:account[\\s\\-]*usage|amount|margin|wallet|capital|risk[\\s\\-]*size|position[\\s\\-]*size)\\b[ \\t:=\\-]*\\d+(?:\\.\\d+)?(?:\\s*(?:-|to|/)\\s*\\d+(?:\\.\\d+)?)?\\s*%?",
            RegexOption.IGNORE_CASE
        ).replace(cleaned, "\n$SECTION_BREAK\n")
        cleaned = Regex("\\b\\d+(?:\\.\\d+)?(?:\\s*(?:-|to|/)\\s*\\d+(?:\\.\\d+)?)?\\s*%", RegexOption.IGNORE_CASE)
            .replace(cleaned, " ")
        cleaned = Regex("@[A-Za-z_][A-Za-z0-9_]*").replace(cleaned, " ")

        var symbol: String? = null
        val mPair = Regex("[#$]?([A-Z0-9]{2,15})\\s*/\\s*(USDT|USDC|BUSD)\\b", RegexOption.IGNORE_CASE).find(cleaned)
        if (mPair != null) {
            symbol = "${mPair.groupValues[1].uppercase(Locale.US)}${mPair.groupValues[2].uppercase(Locale.US)}"
        }
        if (symbol == null) {
            val mHash = Regex("[#$]([A-Z0-9]*[A-Z][A-Z0-9]{1,14})(?=[.\\s,:;\\-]|$)", RegexOption.IGNORE_CASE).find(cleaned)
            if (mHash != null) {
                val base = mHash.groupValues[1].uppercase(Locale.US)
                symbol = if (base.endsWith("USDT") || base.endsWith("USDC") || base.endsWith("BUSD")) base else "${base}USDT"
            }
        }
        if (symbol == null) {
            val mUsdt = Regex("\\b([A-Z0-9]{2,15}(?:USDT|USDC|BUSD))\\b", RegexOption.IGNORE_CASE).find(cleaned)
            if (mUsdt != null) symbol = mUsdt.groupValues[1].uppercase(Locale.US)
        }

        val direction: String? = when {
            Regex("\\b(?:SELL|SHORT)\\b", RegexOption.IGNORE_CASE).containsMatchIn(cleaned) -> "SELL"
            Regex("\\b(?:BUY|LONG)\\b", RegexOption.IGNORE_CASE).containsMatchIn(cleaned) -> "BUY"
            else -> null
        }

        var working = cleaned

        // 1. Extract & mask Stop-Loss / Stop Target FIRST so SL prices & headers never leak into Entry or Targets
        var stopLoss: Double? = null
        val mSl = Regex(
            "\\b(?:stop[\\s\\-]*loss(?:es)?|stoploss|stop[\\s\\-]*targets?|s/l|sl)\\b[ \\t:=\\-]*(?:\\n[ \\t]*){0,3}(?:\\d{1,2}\\s*\\)[ \\t]*|\\d{1,2}[.:\\-][ \\t]+)?([0-9,]+(?:\\.[0-9]+)?)",
            RegexOption.IGNORE_CASE
        ).find(working)
        if (mSl != null) {
            stopLoss = HybridOptionsParser.parseNumber(mSl.groupValues[1])
            working = replaceSpanWithBreak(working, mSl.range.first, mSl.range.last + 1)
        }

        // 2. Extract & mask Entry section (supports single-line, range, market_now, and multi-line Entry Targets blocks)
        var entryPrice: Double? = null
        var additionalEntries: List<Double> = emptyList()
        var entryMode = "limit"

        val mEntryLine = Regex(
            "\\b(?:take\\s+entry\\s+now|take\\s+entry|entry[\\s\\-]*targets?|entries|entrys|entry[\\s\\-]*zone|entry[\\s\\-]*price|1st[\\s\\-]*entry|entry[\\s\\-]*1|entry|enter|buy\\s+at|sell\\s+at|long\\s+at|short\\s+at|cmp)\\b[ \\t:=\\-]*([^\\n]*?)(?=\\b(?:take[\\s\\-]*profit|targets?|tps?|tp\\d+|stop[\\s\\-]*loss|stoploss|stop[\\s\\-]*targets?|sl|s/l|$SECTION_BREAK)\\b|\\n|$)",
            RegexOption.IGNORE_CASE
        ).find(working)
        if (mEntryLine != null) {
            val entrySeg = mEntryLine.groupValues[1].trim()
            val nums = extractCleanNumbers(entrySeg)
            if (nums.isNotEmpty()) {
                entryPrice = nums[0]
                additionalEntries = nums.drop(1)
                working = replaceSpanWithBreak(working, mEntryLine.range.first, mEntryLine.range.last + 1)
            } else if (HybridOptionsParser.MARKET_NOW_SEGMENT_RE.containsMatchIn(entrySeg) ||
                Regex("\\b(?:take\\s+entry\\s+now|cmp)\\b", RegexOption.IGNORE_CASE).containsMatchIn(mEntryLine.value)
            ) {
                entryMode = "market_now"
                working = replaceSpanWithBreak(working, mEntryLine.range.first, mEntryLine.range.last + 1)
            } else {
                // Multi-line entry block (e.g., "Entry Targets:\n\n1) 3.44\n\n2) 3.35" or "Entry Targets:\n1) Market")
                val afterIdx = mEntryLine.range.last + 1
                val afterEntry = working.substring(afterIdx)
                val mEntryBody = Regex(
                    "^([\\s\\S]*?)(?=\\n[ \\t]*(?:take[\\s\\-]*profit|targets?\\b|tps?\\b|tp\\s*\\d|stop[\\s\\-]*loss|stoploss|stop[\\s\\-]*targets?|sl\\b|s/l\\b|leverage\\b|lev\\b|trailing\\b|account\\b|amount\\b|margin\\b|published\\b|manage\\b|no\\s*reacts\\b|note\\b|$SECTION_BREAK)|$)",
                    RegexOption.IGNORE_CASE
                ).find(afterEntry)
                val bodyText = mEntryBody?.groupValues?.get(1).orEmpty()
                val bodyNums = extractTargetsFromBlock(bodyText)
                if (bodyNums.isNotEmpty()) {
                    entryPrice = bodyNums[0]
                    additionalEntries = bodyNums.drop(1)
                    val endIdx = afterIdx + (mEntryBody?.range?.last?.plus(1) ?: 0)
                    working = replaceSpanWithBreak(working, mEntryLine.range.first, endIdx)
                } else if (HybridOptionsParser.MARKET_NOW_SEGMENT_RE.containsMatchIn(bodyText)) {
                    entryMode = "market_now"
                    val endIdx = afterIdx + (mEntryBody?.range?.last?.plus(1) ?: 0)
                    working = replaceSpanWithBreak(working, mEntryLine.range.first, endIdx)
                } else {
                    // Still mask the Entry header so "Entry Targets" cannot match the Targets section below
                    working = replaceSpanWithBreak(working, mEntryLine.range.first, mEntryLine.range.last + 1)
                }
            }
        }

        // Capture secondary entry lines (e.g., "2nd ENTRY: 0.2250", "3rd Entry: 0.21", "Entry 2: 0.225", "DCA: 0.22")
        val secondaryEntryRe = Regex(
            "\\b(?:(?:2nd|3rd|4th|second|third|add(?:itional)?|extra)[ \\t]+(?:entry[\\s\\-]*(?:price|zone|level)?|entries|entrys|buy|sell)|(?:dca|re[\\s\\-]?entry)(?:[ \\t]+(?:entry|price|zone|level))?|entry[\\s\\-]*(?:2|3|4|two|three))\\b[ \\t:=\\-]*([^\\n]*?)(?=\\b(?:take[\\s\\-]*profit|targets?|tps?|tp\\d+|stop[\\s\\-]*loss|stoploss|stop[\\s\\-]*targets?|sl|s/l|$SECTION_BREAK)\\b|\\n|$)",
            RegexOption.IGNORE_CASE
        )
        val extraEntriesMutable = additionalEntries.toMutableList()
        while (true) {
            val mExtra = secondaryEntryRe.find(working) ?: break
            val extraNums = extractCleanNumbers(mExtra.groupValues[1])
            for (n in extraNums) {
                if (entryPrice == null) {
                    entryPrice = n
                } else if (abs(n - entryPrice) > 1e-9 && extraEntriesMutable.none { abs(it - n) <= 1e-9 }) {
                    extraEntriesMutable += n
                }
            }
            working = replaceSpanWithBreak(working, mExtra.range.first, mExtra.range.last + 1)
        }
        additionalEntries = extraEntriesMutable

        if (entryPrice == null && entryMode != "market_now") {
            val mDirPrice = Regex(
                "\\b(?:buy|sell|long|short)[ \\t]*[:\\-][ \\t]*([^\\n]*?)(?=\\b(?:take[\\s\\-]*profit|targets?|tps?|tp\\d+|stop[\\s\\-]*loss|stoploss|stop[\\s\\-]*targets?|sl|s/l|$SECTION_BREAK)\\b|\\n|$)",
                RegexOption.IGNORE_CASE
            ).find(working)
            if (mDirPrice != null) {
                val nums = extractCleanNumbers(mDirPrice.groupValues[1])
                if (nums.isNotEmpty()) {
                    entryPrice = nums[0]
                    additionalEntries = nums.drop(1)
                    working = replaceSpanWithBreak(working, mDirPrice.range.first, mDirPrice.range.last + 1)
                }
            }
        }

        if (entryPrice == null && entryMode != "market_now") {
            if (HybridOptionsParser.hasMarketNowKeyword(cleaned)) {
                entryMode = "market_now"
            } else if (symbol != null && direction != null &&
                Regex("\\b(?:tp|target|sl|stoploss|stop[\\s\\-]*loss|stop[\\s\\-]*target)\\b", RegexOption.IGNORE_CASE).containsMatchIn(cleaned)
            ) {
                entryMode = "market_now"
            }
        }

        // 3. Extract Targets from remaining text (SL & Entry already masked with SECTION_BREAK)
        var targets: List<Double> = emptyList()
        val mTpSec = Regex(
            "(?<!entry[\\s\\-])(?<!stop[\\s\\-])\\b(?:take[\\s\\-]+profit(?:[\\s\\-]+targets?)?|targets?|tps?)\\b(.*?)(?=\\b(?:stoploss|stop[\\s\\-]*loss(?:es)?|stop[\\s\\-]*targets?|s/l|sl|leverage|lev|published|manage\\s+your\\s+risk|account[\\s\\-]*usage|amount|trailing|margin|no\\s*reacts|$SECTION_BREAK)\\b|$)",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).find(working)
        if (mTpSec != null) {
            targets = extractTargetsFromBlock(mTpSec.groupValues[1])
        }
        if (targets.isEmpty()) {
            val tpInline = mutableListOf<Double>()
            val reInline = Regex(
                "^\\s*(?:take[\\s\\-]+profit\\s*\\d*|tp\\s*\\d*|target\\s*\\d*)\\s*[:\\-]?\\s*([^\\n]+)$",
                setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)
            )
            for (mTp in reInline.findAll(working)) {
                tpInline.addAll(extractTargetsFromBlock(mTp.groupValues[1]))
            }
            targets = HybridOptionsParser.cleanTargets(tpInline)
        }

        // 4. Exclude SL & apply direction-aware sanity filter on targets
        if (stopLoss != null) {
            targets = targets.filter { abs(it - stopLoss) > 1e-9 }
        }
        if (entryPrice != null && direction in setOf("BUY", "SELL")) {
            val allEnt = listOf(entryPrice) + additionalEntries
            val refLow = allEnt.min()
            val refHigh = allEnt.max()
            targets = if (direction == "BUY") {
                targets.filter { it > refLow }
            } else {
                targets.filter { it < refHigh }
            }
        }

        val issues = mutableListOf<String>()
        if (symbol == null) issues += "missing crypto symbol"
        if (direction == null) issues += "missing direction"
        if (entryPrice == null && entryMode != "market_now") issues += "missing entry"

        val status = if (issues.isEmpty()) "parsed" else "needs_review"
        val review = if (status == "parsed") "not_required" else "required"
        val confidence = if (status == "parsed" && entryPrice != null) 0.94 else if (status == "parsed") 0.86 else 0.4

        val entryDisp = if (entryPrice != null) {
            (listOf(entryPrice) + additionalEntries).joinToString("/") { DefaultRulesEngine.formatG(it) }
        } else {
            entryMode
        }
        val explParts = mutableListOf(
            "Crypto parser: symbol=${symbol ?: "none"}, dir=${direction ?: "none"}, entry=$entryDisp"
        )
        if (stopLoss != null) explParts += "SL=${DefaultRulesEngine.formatG(stopLoss)}"
        if (targets.isNotEmpty()) explParts += "TPs=${targets.joinToString("/") { DefaultRulesEngine.formatG(it) }}"
        if (issues.isNotEmpty()) explParts += "Issues: ${issues.joinToString(", ")}"

        var res = ParsedSignal(
            category = "CRYPTO",
            symbol = symbol,
            direction = direction,
            entryPrice = entryPrice,
            additionalEntries = additionalEntries,
            stopLoss = stopLoss,
            targets = targets,
            entryMode = entryMode,
            status = status,
            confidence = confidence,
            review = review,
            explanation = explParts.joinToString(" | ") + "."
        )
        if (slRules != null && status == "parsed") {
            res = DefaultRulesEngine.applyDefaultSl(res, category.ifBlank { "Crypto" }, slRules)
            res = DefaultRulesEngine.applyDefaultTargets(res, category.ifBlank { "Crypto" }, slRules)
        }
        return res
    }

    fun forexExpandEntrySlice(sliceText: String?): List<Double> {
        if (sliceText.isNullOrBlank()) return emptyList()
        val mRng = Regex("([0-9,]+(?:\\.[0-9]+)?)\\s*(?:-|/|TO|OR)\\s*([0-9,]+(?:\\.[0-9]+)?)", RegexOption.IGNORE_CASE).find(sliceText)
        if (mRng != null) {
            val rawA = mRng.groupValues[1]
            val rawB = mRng.groupValues[2]
            val a = HybridOptionsParser.parseNumber(rawA)
            var b = HybridOptionsParser.parseNumber(rawB)
            if (a != null && b != null) {
                val cleanA = rawA.replace(",", "").trim()
                val cleanB = rawB.replace(",", "").trim()
                if (a >= 100.0 && '.' !in cleanA && '.' !in cleanB && cleanA.length > cleanB.length) {
                    val scale = 10.0.pow(cleanB.length).toLong()
                    val base = (a.toLong() / scale) * scale
                    val candidates = listOf(base + b, base + scale + b, base - scale + b)
                    val best = candidates.minByOrNull { abs(it - a) }!!
                    if (abs(best - a) <= a * 0.1) b = best
                }
                return if (abs(a - b) < 1e-9) listOf(a) else listOf(a, b)
            }
        }
        return HybridOptionsParser.extractNumbers(sliceText).filter { it > 0.0 }.distinct().take(2)
    }

    fun forexParseTargetLine(line: String): List<Double> {
        var s = line.trim()
        if (s.isEmpty()) return emptyList()
        s = Regex("^\\d{1,2}\\s*[).:\\-]?(?=\\s*(?:take\\s+profit|targets?|tps?)\\b)", RegexOption.IGNORE_CASE).replace(s, "")
        s = s.trim()
        s = Regex(
            "^(?:take\\s+profit\\s+targets?|take\\s+profit|targets?|tps?)(?:\\d{1,2}|\\s+(?:1st|2nd|3rd|\\d{1,2}(?:st|nd|rd|th)|\\d{1,2}(?=\\s*[):\\-])))?\\s*[):\\-]?\\s*",
            RegexOption.IGNORE_CASE
        ).replace(s, "")
        s = Regex("^\\d{1,2}\\s*(?:\\)|[.:\\-]\\s+)").replace(s, "")
        return HybridOptionsParser.extractNumbers(s).filter { it > 0.0 }
    }

    fun parseSingleForexBlock(
        rawBlock: String,
        category: String = "Forex",
        slRules: Map<String, DefaultSlCategoryRule>? = null
    ): ParsedSignal {
        var raw = HybridOptionsParser.normalizeText(rawBlock)
        raw = Regex("[→➔➜➤➡⇒]+|->").replace(raw, ":")
        raw = Regex("\\b(sl|stoploss|stop\\s*loss|tp|target)\\s*\\.(?=\\s*\\d)", RegexOption.IGNORE_CASE).replace(raw, "$1:")
        val cleaned = stripLeverageNoise(raw)

        // 1. Symbol extraction
        var symbol: String? = null
        val mSlash = Regex("\\b([A-Z]{3})\\s*/\\s*([A-Z]{3})\\b", RegexOption.IGNORE_CASE).find(cleaned)
        if (mSlash != null) {
            val pair = "${mSlash.groupValues[1].uppercase(Locale.US)}${mSlash.groupValues[2].uppercase(Locale.US)}"
            symbol = ParserConstants.FOREX_SYMBOL_ALIASES[pair] ?: pair
        }
        if (symbol == null) {
            for (tokMatch in Regex("\\b[A-Z0-9]{3,6}\\b").findAll(cleaned.uppercase(Locale.US))) {
                val tok = tokMatch.value
                val alias = ParserConstants.FOREX_SYMBOL_ALIASES[tok]
                if (alias != null) {
                    symbol = alias
                    break
                }
                if (tok in ParserConstants.FOREX_PAIRS && !ParserConstants.FALSE_FOREX_WORDS.matches(tok)) {
                    symbol = tok
                    break
                }
            }
        }

        // 2. Direction
        val direction: String? = when {
            Regex("\\b(?:SELL|SHORT)\\b", RegexOption.IGNORE_CASE).containsMatchIn(cleaned) -> "SELL"
            Regex("\\b(?:BUY|LONG)\\b", RegexOption.IGNORE_CASE).containsMatchIn(cleaned) -> "BUY"
            else -> null
        }

        // 3. Extract & mask Stop Loss FIRST
        var stopLoss: Double? = null
        var working = cleaned
        val mSl = Regex(
            "\\b(?:stop\\s*sl|stoploss|stop\\s*loss|sl)[ \\t]*[:\\-]?[ \\t]*(?:\\r?\\n[ \\t]*)?([0-9,]+(?:\\.[0-9]+)?)",
            RegexOption.IGNORE_CASE
        ).find(working)
        if (mSl != null) {
            stopLoss = HybridOptionsParser.parseNumber(mSl.groupValues[1])
            working = HybridOptionsParser.maskSpan(working, mSl.range.first, mSl.range.last + 1)
        }

        // 4. Entry & entry_mode
        var entryPrice: Double? = null
        var additionalEntries: List<Double> = emptyList()
        var entryMode = "limit"

        val mEntryLine = Regex(
            "\\b(?:entry\\s*zone|entry\\s*price|entries|entrys|entry|enter|buy\\s+at|sell\\s+at|at|@)[ \\t]*[:\\-]?[ \\t]*([^\\n]+)",
            RegexOption.IGNORE_CASE
        ).find(working)
        if (mEntryLine != null) {
            val seg = mEntryLine.groupValues[1].trim()
            val nums = forexExpandEntrySlice(seg)
            if (nums.isNotEmpty()) {
                entryPrice = nums[0]
                additionalEntries = nums.drop(1)
                working = HybridOptionsParser.maskSpan(working, mEntryLine.range.first, mEntryLine.range.last + 1)
            } else if (HybridOptionsParser.MARKET_NOW_SEGMENT_RE.containsMatchIn(seg)) {
                entryMode = "market_now"
                working = HybridOptionsParser.maskSpan(working, mEntryLine.range.first, mEntryLine.range.last + 1)
            }
        }

        // Pattern B: 'BUY NOW 3733', 'Sell 3742 - 3745', 'buy 3724/20', 'BUY 3724 OR 3720', 'SELL 3754 3756'
        if (entryPrice == null) {
            val mDirNum = Regex(
                "\\b(?:buy|sell|long|short)(?:\\s+(?:now|limit|stop))?[ \\t]*[:\\-]?[ \\t]*([0-9,]+(?:\\.[0-9]+)?(?:[ \\t]*(?:-|/|to|or|[ \\t])[ \\t]*[0-9,]+(?:\\.[0-9]+)?)?)",
                RegexOption.IGNORE_CASE
            ).find(working)
            if (mDirNum != null) {
                val nums = forexExpandEntrySlice(mDirNum.groupValues[1])
                if (nums.isNotEmpty()) {
                    entryPrice = nums[0]
                    additionalEntries = nums.drop(1)
                    working = HybridOptionsParser.maskSpan(working, mDirNum.range.first, mDirNum.range.last + 1)
                }
            }
        }

        // Pattern C: Price BEFORE direction ('3705 Sell Now')
        if (entryPrice == null) {
            val mNumDir = Regex(
                "\\b([0-9,]+(?:\\.[0-9]+)?(?:[ \\t]*(?:-|/|to|or)[ \\t]*[0-9,]+(?:\\.[0-9]+)?)?)[ \\t]+(?:buy|sell|long|short)(?:\\s+(?:now|limit|stop))?\\b",
                RegexOption.IGNORE_CASE
            ).find(working)
            if (mNumDir != null) {
                val nums = forexExpandEntrySlice(mNumDir.groupValues[1])
                if (nums.isNotEmpty()) {
                    entryPrice = nums[0]
                    additionalEntries = nums.drop(1)
                    working = HybridOptionsParser.maskSpan(working, mNumDir.range.first, mNumDir.range.last + 1)
                }
            }
        }

        if (entryPrice == null) {
            if (HybridOptionsParser.hasMarketNowKeyword(working) ||
                (symbol != null && direction != null && Regex("\\b(?:tp|target)\\b", RegexOption.IGNORE_CASE).containsMatchIn(working))
            ) {
                entryMode = "market_now"
            }
        }

        // 5. Targets
        val targetsMutable = mutableListOf<Double>()
        val mMultiTp = Regex(
            "^\\s*(?:take\\s+profit\\s+targets?|take\\s+profit|targets?|tps?)\\s*[:\\-]?\\s*$\\n([\\s\\S]*?)(?=\\n\\s*(?:stop|sl\\b|follow|manage|note|published|--)|$)",
            setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)
        ).find(working)
        if (mMultiTp != null) {
            for (ln in mMultiTp.groupValues[1].lines()) {
                for (num in forexParseTargetLine(ln)) {
                    if (num !in targetsMutable) targetsMutable += num
                }
            }
        }

        if (targetsMutable.isEmpty()) {
            val lineTpRe = Regex(
                "(?:^\\s*\\d{0,2}\\s*[).:\\-]?\\s*(?:take\\s+profit|targets?|tps?)\\b|\\b(?:tp|target)\\s*(?:\\d+(?:st|nd|rd|th)?|1st|2nd|3rd|4th|5th)\\b)",
                RegexOption.IGNORE_CASE
            )
            for (ln in working.lines()) {
                if (lineTpRe.containsMatchIn(ln)) {
                    for (num in forexParseTargetLine(ln)) {
                        if (num !in targetsMutable) targetsMutable += num
                    }
                }
            }
        }
        var targets = HybridOptionsParser.cleanTargets(targetsMutable)

        // 6. Direction sanity filter
        if (entryPrice != null && direction in setOf("BUY", "SELL")) {
            val allEnt = listOf(entryPrice) + additionalEntries
            val refLow = allEnt.min()
            val refHigh = allEnt.max()
            targets = if (direction == "BUY") {
                targets.filter { it > refLow }
            } else {
                targets.filter { it < refHigh }
            }
        }

        val issues = mutableListOf<String>()
        if (symbol == null) issues += "missing forex symbol"
        if (direction == null) issues += "missing direction"
        if (entryPrice == null && entryMode != "market_now") issues += "missing entry"

        val status = if (issues.isEmpty()) "parsed" else "needs_review"
        val review = if (status == "parsed") "not_required" else "required"
        val confidence = if (status == "parsed" && entryPrice != null) 0.94 else if (status == "parsed") 0.86 else 0.4

        val entryDisp = if (entryPrice != null) {
            (listOf(entryPrice) + additionalEntries).joinToString("/") { DefaultRulesEngine.formatG(it) }
        } else {
            entryMode
        }
        val explParts = mutableListOf("Forex parser: symbol=${symbol ?: "none"}, dir=${direction ?: "none"}, entry=$entryDisp")
        if (stopLoss != null) explParts += "SL=${DefaultRulesEngine.formatG(stopLoss)}"
        if (targets.isNotEmpty()) explParts += "TPs=${targets.joinToString("/") { DefaultRulesEngine.formatG(it) }}"
        if (issues.isNotEmpty()) explParts += "Issues: ${issues.joinToString(", ")}"

        var res = ParsedSignal(
            category = "FOREX",
            symbol = symbol,
            direction = direction,
            entryPrice = entryPrice,
            additionalEntries = additionalEntries,
            stopLoss = stopLoss,
            targets = targets,
            entryMode = entryMode,
            status = status,
            confidence = confidence,
            review = review,
            explanation = explParts.joinToString(" | ") + "."
        )
        if (slRules != null && status == "parsed") {
            res = DefaultRulesEngine.applyDefaultSl(res, category.ifBlank { "Forex" }, slRules)
            res = DefaultRulesEngine.applyDefaultTargets(res, category.ifBlank { "Forex" }, slRules)
        }
        return res
    }

    fun parseSingleMcxBlock(
        block: String,
        category: String = "MCX",
        slRules: Map<String, DefaultSlCategoryRule>? = null
    ): ParsedSignal {
        val commPattern = ParserConstants.MCX_COMMODITY_ALIASES.keys.sortedByDescending { it.length }.joinToString("|")
        val mComm = Regex("\\b($commPattern)\\b", RegexOption.IGNORE_CASE).find(block)
        val symbol = mComm?.let { ParserConstants.MCX_COMMODITY_ALIASES[it.groupValues[1].uppercase(Locale.US)] }

        val direction: String? = when {
            Regex("\\b(?:SELL|SHORT)\\b", RegexOption.IGNORE_CASE).containsMatchIn(block) -> "SELL"
            Regex("\\b(?:BUY|LONG)\\b", RegexOption.IGNORE_CASE).containsMatchIn(block) -> "BUY"
            else -> null
        }

        var entryPrice: Double? = null
        var additionalEntries: List<Double> = emptyList()
        var entryMode = "limit"

        val mHdr = Regex("\\b(?:buy|sell|long|short)\\s+(?:mcx\\s+)?(?:$commPattern)[ \\t]*[:\\-]?[ \\t]*([^\\n]+)", RegexOption.IGNORE_CASE).find(block)
        if (mHdr != null) {
            val nums = extractCleanNumbers(mHdr.groupValues[1])
            if (nums.isNotEmpty()) {
                entryPrice = nums[0]
                additionalEntries = nums.drop(1)
            }
        }
        if (entryPrice == null) {
            val mEnt = Regex(
                "\\b(?:entry\\s*zone|entry\\s*price|entries|entrys|entry|enter|buy\\s+at|sell\\s+at|at|cmp|near)[ \\t]*[:\\-]?[ \\t]*([^\\n]+)",
                RegexOption.IGNORE_CASE
            ).find(block)
            if (mEnt != null) {
                val seg = mEnt.groupValues[1].trim()
                val nums = extractCleanNumbers(seg)
                if (nums.isNotEmpty()) {
                    entryPrice = nums[0]
                    additionalEntries = nums.drop(1)
                } else if (HybridOptionsParser.MARKET_NOW_SEGMENT_RE.containsMatchIn(seg)) {
                    entryMode = "market_now"
                }
            }
        }

        val mSl = Regex(
            "\\b(?:stoploss|stop[\\s\\-]*loss|sl)[ \\t]*[:\\-]?[ \\t]*(?:\\r?\\n[ \\t]*)?([0-9,]+(?:\\.[0-9]+)?)",
            RegexOption.IGNORE_CASE
        ).find(block)
        val stopLoss = mSl?.let { HybridOptionsParser.parseNumber(it.groupValues[1]) }

        val targetsMutable = mutableListOf<Double>()
        val tpRe = Regex(
            "^\\s*(?:targets?|tps?)\\s*(?:\\d+(?:st|nd|rd|th)?)?\\s*[:\\-]?\\s*([^\\n]+)$",
            setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)
        )
        for (mTp in tpRe.findAll(block)) {
            for (num in extractTargetsFromBlock(mTp.groupValues[1])) {
                if (num !in targetsMutable) targetsMutable += num
            }
        }
        val targets = HybridOptionsParser.cleanTargets(targetsMutable)

        if (entryPrice == null && entryMode != "market_now") {
            if (HybridOptionsParser.hasMarketNowKeyword(block) ||
                (symbol != null && direction != null && (stopLoss != null || targets.isNotEmpty()))
            ) {
                entryMode = "market_now"
            }
        }

        val issues = mutableListOf<String>()
        if (symbol == null) issues += "missing MCX commodity"
        if (direction == null) issues += "missing direction"
        if (entryPrice == null && entryMode != "market_now") issues += "missing entry"

        val status = if (issues.isEmpty()) "parsed" else "needs_review"
        val review = if (status == "parsed") "not_required" else "required"
        val confidence = if (status == "parsed") 0.93 else 0.4

        val explParts = mutableListOf(
            "MCX parser: symbol=${symbol ?: "none"}, dir=${direction ?: "none"}, entry=${entryPrice?.let { DefaultRulesEngine.formatPyFloat(it) } ?: entryMode}"
        )
        if (stopLoss != null) explParts += "SL=${DefaultRulesEngine.formatG(stopLoss)}"
        if (targets.isNotEmpty()) explParts += "TPs=${targets.joinToString("/") { DefaultRulesEngine.formatG(it) }}"
        if (issues.isNotEmpty()) explParts += "Issues: ${issues.joinToString(", ")}"

        var res = ParsedSignal(
            category = "MCX",
            symbol = symbol,
            direction = direction,
            entryPrice = entryPrice,
            additionalEntries = additionalEntries,
            stopLoss = stopLoss,
            targets = targets,
            entryMode = entryMode,
            status = status,
            confidence = confidence,
            review = review,
            explanation = explParts.joinToString(" | ") + "."
        )
        if (slRules != null && status == "parsed") {
            res = DefaultRulesEngine.applyDefaultSl(res, category.ifBlank { "MCX" }, slRules)
            res = DefaultRulesEngine.applyDefaultTargets(res, category.ifBlank { "MCX" }, slRules)
        }
        return res
    }

    private fun splitMultiTradeBlocks(rawText: String, headerRe: Regex): List<String> {
        val matches = headerRe.findAll(rawText).toList()
        if (matches.size !in 2..10) return emptyList()
        val blocks = mutableListOf<String>()
        for (idx in matches.indices) {
            val start = matches[idx].range.first
            val end = if (idx + 1 < matches.size) matches[idx + 1].range.first else rawText.length
            val chunk = rawText.substring(start, end).trim()
            if (chunk.isNotEmpty()) blocks += chunk
        }
        return blocks
    }

    private fun parseMcxSignals(
        message: String?,
        category: String = "MCX",
        slRules: Map<String, DefaultSlCategoryRule>? = null
    ): List<ParsedSignal> {
        val raw = HybridOptionsParser.normalizeText(message)
        if (Regex("\\b(?:CE|PE|CALL|PUT)\\b", RegexOption.IGNORE_CASE).containsMatchIn(raw)) {
            return listOf(HybridOptionsParser.parseV34Hybrid(message, category = category, slRules = slRules))
        }
        val commPattern = ParserConstants.MCX_COMMODITY_ALIASES.keys.sortedByDescending { it.length }.joinToString("|")
        val headerRe = Regex(
            "^[ \\t]*(?:buy|sell|long|short)\\s+(?:mcx\\s+)?(?:$commPattern)\\b",
            setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)
        )
        val blocks = splitMultiTradeBlocks(raw, headerRe)
        if (blocks.isNotEmpty()) {
            val parsedBlocks = blocks.map { parseSingleMcxBlock(it, category = category, slRules = slRules) }
            if (parsedBlocks.count { !it.symbol.isNullOrBlank() && !it.direction.isNullOrBlank() } >= 2) {
                return parsedBlocks
            }
        }
        return listOf(parseSingleMcxBlock(raw, category = category, slRules = slRules))
    }

    private fun parseCryptoSignals(
        message: String?,
        category: String = "Crypto",
        slRules: Map<String, DefaultSlCategoryRule>? = null
    ): List<ParsedSignal> {
        val raw = HybridOptionsParser.normalizeText(message)
        val headerRe = Regex(
            "^[ \\t]*(?:(?:#[A-Z0-9]{2,15}(?:\\s*/\\s*(?:USDT|USDC|BUSD))?|[A-Z0-9]{2,15}\\s*/\\s*(?:USDT|USDC|BUSD)|[A-Z0-9]{2,15}(?:USDT|USDC|BUSD))\\b|(?:buy|sell|long|short)\\s+#?[A-Z0-9]{2,15}(?:\\s*/\\s*(?:USDT|USDC|BUSD)|USDT|USDC|BUSD)?\\b)",
            setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)
        )
        val blocks = splitMultiTradeBlocks(raw, headerRe)
        if (blocks.isNotEmpty()) {
            val parsedBlocks = blocks.map { parseSingleCryptoBlock(it, category = category, slRules = slRules) }
            if (parsedBlocks.count { !it.symbol.isNullOrBlank() && !it.direction.isNullOrBlank() } >= 2) {
                return parsedBlocks
            }
        }
        return listOf(parseSingleCryptoBlock(message.orEmpty(), category = category, slRules = slRules))
    }

    private fun parseForexSignals(
        message: String?,
        category: String = "Forex",
        slRules: Map<String, DefaultSlCategoryRule>? = null
    ): List<ParsedSignal> {
        val raw = HybridOptionsParser.normalizeText(message)
        val fxTokens = (ParserConstants.FOREX_SYMBOL_ALIASES.keys + ParserConstants.FOREX_PAIRS)
            .sortedByDescending { it.length }
        val fxPattern = fxTokens.joinToString("|") { Regex.escape(it) }
        val headerRe = Regex(
            "^[ \\t]*#?(?:(?:buy|sell|long|short)\\s+(?:now\\s+|limit\\s+|stop\\s+)?(?:$fxPattern)\\b|(?:$fxPattern)(?:\\s*/\\s*(?:usd|gold|xauusd))?\\s+(?:buy|sell|long|short)\\b)",
            setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)
        )
        val blocks = splitMultiTradeBlocks(raw, headerRe)
        if (blocks.isNotEmpty()) {
            val parsedBlocks = blocks.map { parseSingleForexBlock(it, category = category, slRules = slRules) }
            if (parsedBlocks.count { !it.symbol.isNullOrBlank() && !it.direction.isNullOrBlank() } >= 2) {
                return parsedBlocks
            }
        }
        return listOf(parseSingleForexBlock(message.orEmpty(), category = category, slRules = slRules))
    }

    fun parseSignals(
        message: String?,
        category: String = "",
        slRules: Map<String, DefaultSlCategoryRule>? = null
    ): List<ParsedSignal> {
        var catNorm = category.trim().lowercase(Locale.US)
        if (catNorm.isEmpty()) {
            catNorm = HybridOptionsParser.detectCategory("", HybridOptionsParser.normalizeText(message)).lowercase(Locale.US)
        }
        return when (catNorm) {
            "crypto" -> parseCryptoSignals(message, category = category.ifBlank { "Crypto" }, slRules = slRules)
            "forex", "fx" -> parseForexSignals(message, category = category.ifBlank { "Forex" }, slRules = slRules)
            "mcx" -> parseMcxSignals(message, category = category.ifBlank { "MCX" }, slRules = slRules)
            else -> listOf(HybridOptionsParser.parseV34Hybrid(message, category = category, slRules = slRules))
        }
    }

    fun parseSignal(
        message: String?,
        category: String = "",
        slRules: Map<String, DefaultSlCategoryRule>? = null
    ): ParsedSignal = parseSignals(message, category, slRules).first()
}
