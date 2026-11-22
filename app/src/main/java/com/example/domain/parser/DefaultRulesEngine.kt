package com.example.domain.parser

import com.example.domain.model.DefaultSlCategoryRule
import com.example.domain.model.DefaultSlSymbolRule
import com.example.domain.model.InstrumentProfile
import com.example.domain.model.ParsedSignal
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import kotlin.math.max

object DefaultRulesEngine {

    private val MCX_KNOWN = setOf(
        "CRUDEOIL", "CRUDEOILM", "NATURALGAS", "NATGAS", "GOLD", "GOLDM",
        "SILVER", "SILVERM", "COPPER", "ZINC", "ALUMINIUM", "LEAD"
    )
    private val FOREX_KNOWN = setOf(
        "XAUUSD", "XAGUSD", "EURUSD", "GBPUSD", "USDJPY", "GBPJPY", "AUDUSD",
        "USDCAD", "USDCHF", "NZDUSD", "EURGBP", "EURJPY", "USOIL", "UKOIL", "US30", "NAS100"
    )
    private val OPTIONS_INDEX_KNOWN = setOf(
        "NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX", "BANKEX"
    )

    fun normalizeSlCategory(categoryHint: String?, symbol: String? = "", optionType: String? = null): String {
        val c = (categoryHint ?: "").trim().uppercase(Locale.US)
        val sym = (symbol ?: "").trim().uppercase(Locale.US)
        if ("STOCK OPTIONS" in c || "STOCK_OPTIONS" in c) return "STOCK_OPTIONS"
        if ("INDIAN OPTIONS" in c || c == "OPTIONS" || "INDEX OPTIONS" in c) return "OPTIONS"
        if ("MCX" in c || sym in MCX_KNOWN) return "MCX"
        if ("FOREX" in c || c == "FX" || sym in FOREX_KNOWN) return "FOREX"
        if ("CRYPTO" in c || sym.endsWith("USDT") || sym.endsWith("USDC") || sym.endsWith("BUSD")) return "CRYPTO"
        if (sym in OPTIONS_INDEX_KNOWN) return "OPTIONS"
        if (!optionType.isNullOrBlank()) return "STOCK_OPTIONS"
        return "OPTIONS"
    }

    fun forexPipSize(symbol: String?): Double {
        val sym = (symbol ?: "").trim().uppercase(Locale.US)
        if (sym == "XAUUSD" || sym == "GOLD") return 0.1
        if ("JPY" in sym || sym in setOf("XAGUSD", "SILVER", "USOIL", "UKOIL", "XTIUSD", "XBRUSD")) return 0.01
        if (sym in setOf("US30", "NAS100", "US100", "SPX500", "US500", "GER40", "UK100")) return 1.0
        return 0.0001
    }

    private fun cleanPos(v: Double?): Double? = if (v != null && v > 0.0 && !v.isNaN() && !v.isInfinite()) v else null

    fun resolveInstrumentProfile(
        rules: Map<String, DefaultSlCategoryRule>?,
        categoryHint: String?,
        symbol: String?,
        optionType: String? = null
    ): InstrumentProfile {
        val cat = normalizeSlCategory(categoryHint, symbol, optionType)
        val unitType = when (cat) {
            "FOREX" -> "pips"
            "CRYPTO" -> "pct"
            else -> "points"
        }
        val catBlock = rules?.get(cat)
        val symMap = catBlock?.symbols.orEmpty()
        val sym = (symbol ?: "").trim().uppercase(Locale.US)
        val isOpt = !optionType.isNullOrBlank()

        val candidates = mutableListOf<Pair<String, String>>()
        if (sym.isNotEmpty()) {
            if (cat == "MCX") {
                candidates += "$sym:${if (isOpt) "OPT" else "FUT"}" to "$sym (${if (isOpt) "Options" else "Futures"})"
            }
            candidates += sym to sym
        }

        var symEntry: DefaultSlSymbolRule? = null
        var sourceLabel = "$cat default"
        for ((key, label) in candidates) {
            val found = symMap[key]
            if (found != null) {
                symEntry = found
                sourceLabel = label
                break
            }
        }

        fun pick(symVal: Double?, catVal: Double?): Double? = cleanPos(symVal) ?: cleanPos(catVal)

        return InstrumentProfile(
            category = cat,
            unitType = unitType,
            sourceLabel = sourceLabel,
            sl = pick(symEntry?.sl, catBlock?.categoryDefault),
            tp1 = pick(symEntry?.tp1, catBlock?.tp1),
            tp2 = pick(symEntry?.tp2, catBlock?.tp2),
            tp3 = pick(symEntry?.tp3, catBlock?.tp3),
            lots = pick(symEntry?.lots, catBlock?.lots) ?: 1.0,
            lotSize = pick(symEntry?.lotSize, catBlock?.lotSize),
            usdAmount = pick(symEntry?.usdAmount, catBlock?.usdAmount) ?: 100.0
        )
    }

    fun resolveDefaultSl(
        rules: Map<String, DefaultSlCategoryRule>?,
        categoryHint: String?,
        symbol: String?,
        optionType: String? = null
    ): Triple<Double, String, String>? {
        val prof = resolveInstrumentProfile(rules, categoryHint, symbol, optionType)
        val sl = prof.sl ?: return null
        return Triple(sl, prof.unitType, prof.sourceLabel)
    }

    fun roundValue(value: Double, decimals: Int): Double {
        if (value.isNaN() || value.isInfinite()) return value
        return BigDecimal.valueOf(value).setScale(decimals, RoundingMode.HALF_EVEN).toDouble()
    }

    fun formatG(value: Double): String {
        if (value == value.toLong().toDouble() && kotlin.math.abs(value) < 1e12) {
            return value.toLong().toString()
        }
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
    }

    fun formatPyFloat(value: Double): String {
        return if (value == value.toLong().toDouble() && kotlin.math.abs(value) < 1e12) {
            "${value.toLong()}.0"
        } else {
            BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
        }
    }

    fun applyDefaultTargets(
        parsed: ParsedSignal,
        categoryHint: String = "",
        slRules: Map<String, DefaultSlCategoryRule>? = null
    ): ParsedSignal {
        if (slRules == null || parsed.targets.isNotEmpty()) return parsed
        val entry = parsed.entryPrice ?: return parsed
        val direction = (parsed.direction ?: "").uppercase(Locale.US)
        val symbol = parsed.symbol ?: return parsed
        if (direction !in setOf("BUY", "SELL") || symbol.isBlank()) return parsed

        val prof = resolveInstrumentProfile(
            slRules,
            categoryHint.ifBlank { parsed.category },
            symbol,
            parsed.optionType
        )
        val offsets = listOf(prof.tp1, prof.tp2, prof.tp3)
        if (offsets.none { it != null }) return parsed

        val allEntries = buildList {
            add(entry)
            addAll(parsed.additionalEntries)
        }
        val refEntry = allEntries.sum() / allEntries.size
        val unitType = prof.unitType

        val generated = mutableListOf<Double>()
        for (off in offsets) {
            if (off == null) continue
            val delta: Double
            val decimals: Int
            when (unitType) {
                "pips" -> {
                    val pip = forexPipSize(symbol)
                    delta = off * pip
                    decimals = if (pip <= 0.0001) 5 else if (pip <= 0.01) 3 else 2
                }
                "pct" -> {
                    delta = refEntry * (off / 100.0)
                    decimals = if (refEntry < 1.0) 8 else if (refEntry < 100.0) 4 else 2
                }
                else -> {
                    delta = off
                    decimals = if (refEntry < 1.0) 4 else 2
                }
            }
            var tpVal = if (direction == "BUY") {
                roundValue(refEntry + delta, decimals)
            } else {
                val rawTp = refEntry - delta
                roundValue(if (rawTp > 0.0) rawTp else max(0.05, refEntry * 0.5), decimals)
            }
            if (tpVal == tpVal.toLong().toDouble() && decimals <= 2) {
                tpVal = tpVal.toLong().toDouble()
            }
            generated += tpVal
        }

        if (generated.isEmpty()) return parsed
        val tpStr = generated.joinToString(" / ") { formatPyFloat(it) }
        val noteTag = "Default TPs: $tpStr (${prof.sourceLabel})"
        val expl = parsed.explanation
        val newExpl = if (noteTag !in expl) {
            (expl.trimEnd('.') + " | $noteTag.").trim()
        } else {
            expl
        }
        return parsed.copy(
            targets = generated,
            defaultTpApplied = true,
            explanation = newExpl
        )
    }

    fun applyDefaultSl(
        parsed: ParsedSignal,
        categoryHint: String = "",
        slRules: Map<String, DefaultSlCategoryRule>? = null
    ): ParsedSignal {
        if (slRules == null || parsed.stopLoss != null) return parsed
        val entry = parsed.entryPrice ?: return parsed
        val direction = (parsed.direction ?: "").uppercase(Locale.US)
        val symbol = parsed.symbol ?: return parsed
        if (direction !in setOf("BUY", "SELL") || symbol.isBlank()) return parsed

        val cat = categoryHint.ifBlank { parsed.category }
        val resolved = resolveDefaultSl(slRules, cat, symbol, parsed.optionType) ?: return parsed
        val (offsetVal, unitType, sourceLabel) = resolved

        val allEntries = buildList {
            add(entry)
            addAll(parsed.additionalEntries)
        }
        val refLow = allEntries.min()
        val refHigh = allEntries.max()

        val delta: Double
        val decimals: Int
        val unitText: String
        when (unitType) {
            "pips" -> {
                val pip = forexPipSize(symbol)
                delta = offsetVal * pip
                decimals = if (pip <= 0.0001) 5 else if (pip <= 0.01) 3 else 2
                unitText = "${formatG(offsetVal)} pips"
            }
            "pct" -> {
                val baseRef = if (direction == "BUY") refLow else refHigh
                delta = baseRef * (offsetVal / 100.0)
                decimals = if (baseRef < 1.0) 8 else if (baseRef < 100.0) 4 else 2
                unitText = "${formatG(offsetVal)}%"
            }
            else -> {
                delta = offsetVal
                decimals = if (refLow < 1.0) 4 else 2
                unitText = "${formatG(offsetVal)} pts"
            }
        }

        var slPrice = if (direction == "BUY") {
            val rawSl = refLow - delta
            val clamped = if (rawSl <= 0.0) max(0.05, refLow * 0.5) else rawSl
            roundValue(clamped, decimals)
        } else {
            roundValue(refHigh + delta, decimals)
        }
        if (slPrice == slPrice.toLong().toDouble() && decimals <= 2) {
            slPrice = slPrice.toLong().toDouble()
        }

        val slDisplay = formatPyFloat(slPrice)
        val noteTag = "Default SL: $slDisplay ($unitText - $sourceLabel)"
        val expl = parsed.explanation
        val newExpl = if (noteTag !in expl) {
            (expl.trimEnd('.') + " | $noteTag.").trim()
        } else {
            expl
        }
        return parsed.copy(
            stopLoss = slPrice,
            defaultSlApplied = true,
            defaultSlNote = "Default SL $slDisplay ($unitText via $sourceLabel)",
            explanation = newExpl
        )
    }

    fun rulesToJson(rules: Map<String, DefaultSlCategoryRule>): String {
        val root = JSONObject()
        for ((cat, block) in rules) {
            val catObj = JSONObject()
            catObj.put("category_default", block.categoryDefault ?: JSONObject.NULL)
            catObj.put("tp1", block.tp1 ?: JSONObject.NULL)
            catObj.put("tp2", block.tp2 ?: JSONObject.NULL)
            catObj.put("tp3", block.tp3 ?: JSONObject.NULL)
            if (block.lots != null) catObj.put("lots", block.lots)
            if (block.lotSize != null) catObj.put("lot_size", block.lotSize)
            if (block.usdAmount != null) catObj.put("usd_amount", block.usdAmount)
            val symsObj = JSONObject()
            for ((sym, sRule) in block.symbols) {
                val sObj = JSONObject()
                sObj.put("sl", sRule.sl ?: JSONObject.NULL)
                sObj.put("tp1", sRule.tp1 ?: JSONObject.NULL)
                sObj.put("tp2", sRule.tp2 ?: JSONObject.NULL)
                sObj.put("tp3", sRule.tp3 ?: JSONObject.NULL)
                sObj.put("lots", sRule.lots ?: JSONObject.NULL)
                sObj.put("lot_size", sRule.lotSize ?: JSONObject.NULL)
                sObj.put("usd_amount", sRule.usdAmount ?: JSONObject.NULL)
                symsObj.put(sym, sObj)
            }
            catObj.put("symbols", symsObj)
            root.put(cat, catObj)
        }
        return root.toString()
    }

    fun rulesFromJson(rawJson: String?): MutableMap<String, DefaultSlCategoryRule> {
        val merged = ParserConstants.defaultSlSeed()
        if (rawJson.isNullOrBlank()) return merged
        val saved = try {
            JSONObject(rawJson)
        } catch (_: Exception) {
            return merged
        }
        val keys = saved.keys()
        while (keys.hasNext()) {
            val cat = keys.next()
            val block = saved.optJSONObject(cat) ?: continue
            val existing = merged[cat] ?: DefaultSlCategoryRule(lots = 1.0, lotSize = 1.0, usdAmount = 100.0)
            val updatedSymbols = existing.symbols.toMutableMap()
            val symsJson = block.optJSONObject("symbols")
            if (symsJson != null) {
                val symKeys = symsJson.keys()
                while (symKeys.hasNext()) {
                    val sym = symKeys.next()
                    val v = symsJson.opt(sym)
                    if (v is JSONObject) {
                        val prev = updatedSymbols[sym] ?: DefaultSlSymbolRule()
                        updatedSymbols[sym] = DefaultSlSymbolRule(
                            sl = optDouble(v, "sl", prev.sl),
                            tp1 = optDouble(v, "tp1", prev.tp1),
                            tp2 = optDouble(v, "tp2", prev.tp2),
                            tp3 = optDouble(v, "tp3", prev.tp3),
                            lots = optDouble(v, "lots", prev.lots),
                            lotSize = optDouble(v, "lot_size", prev.lotSize),
                            usdAmount = optDouble(v, "usd_amount", prev.usdAmount)
                        )
                    } else if (v is Number) {
                        val prev = updatedSymbols[sym] ?: DefaultSlSymbolRule()
                        updatedSymbols[sym] = prev.copy(sl = v.toDouble())
                    }
                }
            }
            merged[cat] = DefaultSlCategoryRule(
                categoryDefault = optDouble(block, "category_default", existing.categoryDefault),
                tp1 = optDouble(block, "tp1", existing.tp1),
                tp2 = optDouble(block, "tp2", existing.tp2),
                tp3 = optDouble(block, "tp3", existing.tp3),
                lots = optDouble(block, "lots", existing.lots),
                lotSize = optDouble(block, "lot_size", existing.lotSize),
                usdAmount = optDouble(block, "usd_amount", existing.usdAmount),
                symbols = updatedSymbols
            )
        }
        return merged
    }

    private fun optDouble(obj: JSONObject, key: String, fallback: Double?): Double? {
        if (!obj.has(key)) return fallback
        if (obj.isNull(key)) return null
        val raw = obj.opt(key)
        return when (raw) {
            is Number -> raw.toDouble().takeIf { it > 0.0 }
            is String -> raw.trim().toDoubleOrNull()?.takeIf { it > 0.0 }
            else -> null
        }
    }
}
