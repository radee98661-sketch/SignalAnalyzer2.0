package com.example.util

import com.example.domain.model.LegResult
import com.example.domain.model.ParsedSignal
import com.example.domain.model.TradeEvent
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

object TimeUtils {
    private val FALLBACK_FORMATS = listOf(
        DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
    )

    fun utcNowIso(): String = Instant.now().toString()

    fun parseUtcInstant(value: String?): Instant? {
        if (value.isNullOrBlank()) return null
        val trimmed = value.trim()
        try {
            return Instant.parse(trimmed)
        } catch (_: Exception) {
        }
        try {
            return OffsetDateTime.parse(trimmed).toInstant()
        } catch (_: Exception) {
        }
        try {
            return OffsetDateTime.parse(trimmed.replace(" ", "T")).toInstant()
        } catch (_: Exception) {
        }
        for (fmt in FALLBACK_FORMATS) {
            try {
                return LocalDateTime.parse(trimmed, fmt).toInstant(ZoneOffset.UTC)
            } catch (_: Exception) {
            }
        }
        return null
    }

    fun notificationInstant(notificationTime: String?, receivedAt: String?): Instant {
        return parseUtcInstant(notificationTime)
            ?: parseUtcInstant(receivedAt)
            ?: Instant.now()
    }

    fun truncateToMinute(instant: Instant): Instant = instant.truncatedTo(ChronoUnit.MINUTES)
}

object JsonUtils {

    fun doubleListToJson(list: List<Double>): String {
        val arr = JSONArray()
        for (v in list) arr.put(v)
        return arr.toString()
    }

    fun jsonToDoubleList(raw: String?): List<Double> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val v = arr.optDouble(i, Double.NaN)
                    if (!v.isNaN()) add(v)
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun eventsToJson(events: List<TradeEvent>): String {
        val arr = JSONArray()
        for (e in events) {
            arr.put(
                JSONObject().apply {
                    put("type", e.type)
                    put("label", e.label)
                    put("price", e.price)
                    put("candle_time", e.candleTime)
                    put("leg", e.leg)
                }
            )
        }
        return arr.toString()
    }

    fun jsonToEvents(raw: String?): List<TradeEvent> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    add(
                        TradeEvent(
                            type = o.optString("type"),
                            label = o.optString("label"),
                            price = o.optDouble("price", 0.0),
                            candleTime = o.optString("candle_time"),
                            leg = o.optInt("leg", 1)
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun tpLevelsToJson(levels: List<com.example.domain.model.TargetLevelPnl>): String {
        val arr = JSONArray()
        for (lv in levels) {
            arr.put(
                JSONObject().apply {
                    put("index", lv.index)
                    put("target_price", lv.targetPrice)
                    put("points_pnl", lv.pointsPnl)
                    put("money_pnl", lv.moneyPnl)
                    put("hit", lv.hit)
                    put("hit_at", lv.hitAt ?: JSONObject.NULL)
                }
            )
        }
        return arr.toString()
    }

    fun jsonToTpLevels(raw: String?): List<com.example.domain.model.TargetLevelPnl> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    add(
                        com.example.domain.model.TargetLevelPnl(
                            index = o.optInt("index", i + 1),
                            targetPrice = o.optDouble("target_price", 0.0),
                            pointsPnl = o.optDouble("points_pnl", 0.0),
                            moneyPnl = o.optDouble("money_pnl", 0.0),
                            hit = o.optBoolean("hit", false),
                            hitAt = if (o.isNull("hit_at")) null else o.optString("hit_at", null)
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun legsToJson(legs: List<LegResult>): String {
        val arr = JSONArray()
        for (lg in legs) {
            arr.put(
                JSONObject().apply {
                    put("leg", lg.leg)
                    put("entry_price", lg.entryPrice)
                    put("entered", lg.entered)
                    put("entry_hit_at", lg.entryHitAt ?: JSONObject.NULL)
                    put("outcome", lg.outcome)
                    put("exit_price", lg.exitPrice ?: JSONObject.NULL)
                    put("exit_time", lg.exitTime ?: JSONObject.NULL)
                    put("points_pnl", lg.pointsPnl)
                    put("targets_hit", JSONArray(lg.targetsHit))
                    put("money_pnl", lg.moneyPnl)
                    put("currency", lg.currency)
                    if (lg.positionLabel != null) put("position_label", lg.positionLabel)
                    put("closed_by_sl_after_tp", lg.closedBySlAfterTp)
                    put("sl_hit_after_tp_at", lg.slHitAfterTpAt ?: JSONObject.NULL)
                    put("tp1_pnl", lg.tp1Pnl ?: JSONObject.NULL)
                    put("tp2_pnl", lg.tp2Pnl ?: JSONObject.NULL)
                    put("tp3_pnl", lg.tp3Pnl ?: JSONObject.NULL)
                    put("highest_tp_pnl", lg.highestTpPnl)
                    put("sl_pnl", lg.slPnl)
                }
            )
        }
        return arr.toString()
    }

    fun jsonToLegs(raw: String?): List<LegResult> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val th = o.optJSONArray("targets_hit")
                    val targetsHit = buildList {
                        if (th != null) {
                            for (j in 0 until th.length()) add(th.optInt(j))
                        }
                    }
                    add(
                        LegResult(
                            leg = o.optInt("leg", i + 1),
                            entryPrice = o.optDouble("entry_price", 0.0),
                            entered = o.optBoolean("entered", false),
                            entryHitAt = if (o.isNull("entry_hit_at")) null else o.optString("entry_hit_at", null),
                            outcome = o.optString("outcome", "pending"),
                            exitPrice = if (o.isNull("exit_price")) null else o.optDouble("exit_price"),
                            exitTime = if (o.isNull("exit_time")) null else o.optString("exit_time", null),
                            pointsPnl = o.optDouble("points_pnl", 0.0),
                            targetsHit = targetsHit,
                            moneyPnl = o.optDouble("money_pnl", 0.0),
                            currency = o.optString("currency", "INR"),
                            positionLabel = if (o.has("position_label") && !o.isNull("position_label")) o.optString("position_label") else null,
                            closedBySlAfterTp = o.optBoolean("closed_by_sl_after_tp", false),
                            slHitAfterTpAt = if (!o.has("sl_hit_after_tp_at") || o.isNull("sl_hit_after_tp_at")) null else o.optString("sl_hit_after_tp_at", null),
                            tp1Pnl = if (!o.has("tp1_pnl") || o.isNull("tp1_pnl")) null else o.optDouble("tp1_pnl"),
                            tp2Pnl = if (!o.has("tp2_pnl") || o.isNull("tp2_pnl")) null else o.optDouble("tp2_pnl"),
                            tp3Pnl = if (!o.has("tp3_pnl") || o.isNull("tp3_pnl")) null else o.optDouble("tp3_pnl"),
                            highestTpPnl = o.optDouble("highest_tp_pnl", o.optDouble("money_pnl", 0.0)),
                            slPnl = o.optDouble("sl_pnl", 0.0)
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun parsedSignalToJsonObject(p: ParsedSignal): JSONObject = JSONObject().apply {
        put("category", p.category)
        put("symbol", p.symbol ?: JSONObject.NULL)
        put("direction", p.direction ?: JSONObject.NULL)
        put("entry_price", p.entryPrice ?: JSONObject.NULL)
        put("additional_entries", JSONArray(p.additionalEntries))
        put("stop_loss", p.stopLoss ?: JSONObject.NULL)
        put("targets", JSONArray(p.targets))
        put("option_type", p.optionType ?: JSONObject.NULL)
        put("strike_price", p.strikePrice ?: JSONObject.NULL)
        put("expiry_date", p.expiryDate ?: JSONObject.NULL)
        put("entry_mode", p.entryMode)
        put("status", p.status)
        put("confidence", p.confidence)
        put("review", p.review)
        put("explanation", p.explanation)
        if (p.defaultSlApplied) put("default_sl_applied", true)
        if (p.defaultSlNote != null) put("default_sl_note", p.defaultSlNote)
        if (p.defaultTpApplied) put("default_tp_applied", true)
    }

    fun payloadShape(value: Any?, depth: Int = 0): Any? {
        if (value == null || value === JSONObject.NULL) return "null"
        if (depth >= 2) return value::class.simpleName ?: "Object"
        return when (value) {
            is JSONObject -> {
                val obj = JSONObject()
                val keys = value.keys()
                var count = 0
                while (keys.hasNext() && count < 20) {
                    val k = keys.next()
                    obj.put(k, payloadShape(value.opt(k), depth + 1))
                    count++
                }
                obj
            }
            is JSONArray -> JSONObject().apply {
                put("array_length", value.length())
                put("first_item", if (value.length() > 0) payloadShape(value.opt(0), depth + 1) else JSONObject.NULL)
            }
            is String -> "str"
            is Int, is Long -> "int"
            is Double, is Float -> "float"
            is Boolean -> "bool"
            else -> value::class.simpleName ?: "Object"
        }
    }
}
