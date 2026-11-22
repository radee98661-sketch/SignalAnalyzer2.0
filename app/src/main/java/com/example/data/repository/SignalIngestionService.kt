package com.example.data.repository

import androidx.room.withTransaction
import com.example.data.local.AppDatabase
import com.example.data.local.ChannelEntity
import com.example.data.local.RawNotificationEntity
import com.example.data.local.SignalEntity
import com.example.data.local.SystemLogEntity
import com.example.data.local.VerificationScheduleEntity
import com.example.domain.model.AppEvent
import com.example.domain.model.DefaultSlCategoryRule
import com.example.domain.model.ParsedSignal
import com.example.domain.model.SourceNotificationRow
import com.example.domain.parser.DefaultRulesEngine
import com.example.domain.parser.SignalParser
import com.example.util.JsonUtils
import com.example.util.TimeUtils
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

class SignalIngestionService(
    private val db: AppDatabase,
    private val eventBus: EventBus
) {

    suspend fun getDefaultSlRules(): MutableMap<String, DefaultSlCategoryRule> {
        val raw = db.coreDao().getSetting("default_sl_rules")
        return DefaultRulesEngine.rulesFromJson(raw)
    }

    suspend fun ingest(
        row: SourceNotificationRow,
        preParsed: List<ParsedSignal>? = null
    ): Pair<Long?, String> {
        val notificationId = row.notificationId.trim().takeIf { it.isNotEmpty() }
        val rawCat = row.category.trim()
        val channel = row.channel.trim()
        val slRules = getDefaultSlRules()

        var parsedItems = if (!preParsed.isNullOrEmpty()) {
            preParsed.map { it.copy(entryMode = it.entryMode.ifBlank { "limit" }) }
        } else {
            SignalParser.parseSignals(row.message, rawCat, slRules = slRules)
        }

        parsedItems = parsedItems.map { item ->
            var updated = item.copy(entryMode = item.entryMode.ifBlank { "limit" })
            updated = DefaultRulesEngine.applyDefaultSl(updated, rawCat, slRules)
            updated = DefaultRulesEngine.applyDefaultTargets(updated, rawCat, slRules)
            updated
        }

        val result = db.withTransaction {
            val core = db.coreDao()
            val verDao = db.verificationDao()
            val nowIso = TimeUtils.utcNowIso()

            if (notificationId != null && core.findRawIdByNotificationId(notificationId) != null) {
                return@withTransaction null to "duplicate"
            }

            val rawId = core.insertRawNotification(
                RawNotificationEntity(
                    receivedAt = row.receivedAt,
                    notificationTime = row.notificationTime,
                    sourceApp = row.sourceApp,
                    packageName = row.packageName,
                    channel = row.channel,
                    category = row.category,
                    title = row.title,
                    message = row.message,
                    notificationId = notificationId,
                    deliveryId = row.deliveryId,
                    importedAt = nowIso
                )
            )

            val incomingTime = TimeUtils.notificationInstant(row.notificationTime, row.receivedAt)
            val windowMinutes = core.getSetting("duplicate_window_minutes")?.toDoubleOrNull() ?: 10.0
            val candidates = core.getRawCandidatesForDuplicateCheck(rawId, channel)

            val exact = mutableListOf<RawNotificationEntity>()
            val related = mutableListOf<RawNotificationEntity>()
            val incomingNorm = normalizedMessage(row.message)
            val incomingSubject = messageSubject(row.message)
            val hasDetailMarkers = DETAIL_MARKERS.containsMatchIn(row.message)

            for (cand in candidates) {
                val candTime = TimeUtils.notificationInstant(cand.notificationTime, cand.receivedAt)
                val diffSeconds = abs(Duration.between(incomingTime, candTime).seconds)
                if (diffSeconds > windowMinutes * 60.0) continue

                if (normalizedMessage(cand.message) == incomingNorm) {
                    exact += cand
                } else if (hasDetailMarkers && incomingSubject.first != null && incomingSubject == messageSubject(cand.message)) {
                    related += cand
                }
            }

            val incomingEntity = RawNotificationEntity(
                id = rawId,
                receivedAt = row.receivedAt,
                notificationTime = row.notificationTime,
                channel = channel,
                message = row.message,
                importedAt = nowIso
            )

            if (exact.isNotEmpty()) {
                val allExact = exact + incomingEntity
                val primary = allExact.minBy { TimeUtils.notificationInstant(it.notificationTime, it.receivedAt) }
                val group = primary.duplicateGroupId ?: "dup-${primary.id}-${UUID.randomUUID().toString().replace("-", "").take(8)}"
                core.markRawPrimary(primary.id, group)
                for (item in allExact) {
                    if (item.id != primary.id) {
                        core.markRawDuplicate(
                            id = item.id,
                            duplicateOf = primary.id,
                            groupId = group,
                            reason = DUPLICATE_REASON_EXACT
                        )
                    }
                }
            } else if (related.isNotEmpty()) {
                val anchor = related.minBy { TimeUtils.notificationInstant(it.notificationTime, it.receivedAt) }
                val group = anchor.duplicateGroupId ?: "update-${anchor.id}-${UUID.randomUUID().toString().replace("-", "").take(8)}"
                core.setDuplicateGroupPair(anchor.id, rawId, group)
                core.setDuplicateReason(rawId, DUPLICATE_REASON_UPDATE)
            }

            core.insertChannel(
                ChannelEntity(
                    name = channel.ifEmpty { "Unknown" },
                    category = rawCat.ifEmpty { "Uncategorized" }
                )
            )

            val savedRaw = core.getRawNotificationById(rawId)
            val isDup = (savedRaw?.isDuplicate ?: 0) == 1

            // Inherit active Forex symbol from same channel within 30 minutes for short follow-ups
            if (!isDup && rawCat.lowercase(Locale.US) in setOf("forex", "fx")) {
                val cutoffIso = incomingTime.minus(Duration.ofMinutes(30)).toString()
                val recentFxSymbol = core.findRecentForexSymbol(channel, cutoffIso)
                if (!recentFxSymbol.isNullOrBlank()) {
                    parsedItems = parsedItems.map { item ->
                        if (item.symbol.isNullOrBlank() && !item.direction.isNullOrBlank() &&
                            (item.entryPrice != null || item.entryMode == "market_now")
                        ) {
                            var inherited = item.copy(
                                symbol = recentFxSymbol,
                                status = "parsed",
                                review = "not_required",
                                confidence = 0.85,
                                explanation = item.explanation.trimEnd('.') +
                                    " | Inherited symbol $recentFxSymbol from recent channel message."
                            )
                            inherited = DefaultRulesEngine.applyDefaultSl(inherited, rawCat, slRules)
                            inherited = DefaultRulesEngine.applyDefaultTargets(inherited, rawCat, slRules)
                            inherited
                        } else {
                            item
                        }
                    }
                }
            }

            val settings = core.getAllSettings().associate { it.key to it.value }
            val initialWait = settings["verification_initial_wait_minutes"]?.toIntOrNull() ?: 15
            val repeatMinutes = settings["verification_repeat_minutes"]?.toIntOrNull() ?: 15
            val expiryHours = settings["verification_expiry_hours"]?.toLongOrNull() ?: 168L
            val timeframe = settings["verification_timeframe"] ?: "1m"

            val firstSched = incomingTime.plus(Duration.ofMinutes(initialWait.toLong()))
            val expiresAt = incomingTime.plus(Duration.ofHours(expiryHours))
            val nowInstant = Instant.now()

            for (item in parsedItems) {
                val signalId = core.insertSignal(
                    SignalEntity(
                        rawNotificationId = rawId,
                        symbol = item.symbol,
                        direction = item.direction,
                        entryPrice = item.entryPrice,
                        additionalEntries = JsonUtils.doubleListToJson(item.additionalEntries),
                        stopLoss = item.stopLoss,
                        targets = JsonUtils.doubleListToJson(item.targets),
                        optionType = item.optionType,
                        strikePrice = item.strikePrice,
                        expiryDate = item.expiryDate,
                        entryMode = item.entryMode.ifBlank { "limit" },
                        parsingStatus = item.status,
                        parsingConfidence = item.confidence,
                        manualReviewStatus = item.review,
                        verificationStatus = "pending",
                        parsingExplanation = item.explanation,
                        updatedAt = nowIso
                    )
                )

                val canVerify = item.status == "parsed" &&
                    !isDup &&
                    !item.symbol.isNullOrBlank() &&
                    item.direction in setOf("BUY", "SELL") &&
                    (item.entryPrice != null || item.entryMode == "market_now")

                if (canVerify) {
                    val isIdleWait = firstSched.isAfter(nowInstant)
                    val nextTime = if (isIdleWait) firstSched.toString() else nowInstant.toString()
                    val lastRes = if (isIdleWait) {
                        "Idle wait (${initialWait}m) before first candle check"
                    } else {
                        "Queued for immediate catch-up verification"
                    }
                    verDao.insertSchedule(
                        VerificationScheduleEntity(
                            signalId = signalId,
                            signalStartAt = incomingTime.toString(),
                            initialWaitMinutes = initialWait,
                            intervalMinutes = repeatMinutes,
                            timeframe = timeframe,
                            firstScheduledAt = firstSched.toString(),
                            nextScheduledAt = nextTime,
                            expiresAt = expiresAt.toString(),
                            status = "scheduled",
                            lastResult = lastRes,
                            createdAt = nowIso,
                            updatedAt = nowIso
                        )
                    )
                }
            }

            core.insertSystemLog(
                SystemLogEntity(
                    createdAt = nowIso,
                    severity = "INFO",
                    source = "ingest",
                    message = "Imported notification $rawId (${parsedItems.size} signal(s))"
                )
            )
            rawId to "imported"
        }

        if (result.second == "imported") {
            eventBus.emit(AppEvent("signal.changed", result.first, "imported", null, TimeUtils.utcNowIso()))
        }
        return result
    }

    suspend fun backfillDuplicateClassifications() {
        db.withTransaction {
            val core = db.coreDao()
            val window = core.getSetting("duplicate_window_minutes")?.toDoubleOrNull() ?: 10.0
            val legacy = core.getLegacyUnclassifiedRawNotifications()
            for (row in legacy) {
                val currentTime = TimeUtils.notificationInstant(row.notificationTime, row.receivedAt)
                val candidates = core.getRawCandidatesForDuplicateCheck(row.id, row.channel.orEmpty())
                val exact = mutableListOf<RawNotificationEntity>()
                val related = mutableListOf<RawNotificationEntity>()
                val subject = messageSubject(row.message)
                val norm = normalizedMessage(row.message)
                val hasMarkers = DETAIL_MARKERS.containsMatchIn(row.message.orEmpty())
                for (cand in candidates) {
                    val candTime = TimeUtils.notificationInstant(cand.notificationTime, cand.receivedAt)
                    if (abs(Duration.between(currentTime, candTime).seconds) > window * 60.0) continue
                    if (normalizedMessage(cand.message) == norm) {
                        exact += cand
                    } else if (hasMarkers && subject.first != null && subject == messageSubject(cand.message)) {
                        related += cand
                    }
                }
                if (exact.isNotEmpty()) {
                    val all = exact + row
                    val primary = all.minBy { TimeUtils.notificationInstant(it.notificationTime, it.receivedAt) }
                    val group = primary.duplicateGroupId ?: "dup-${primary.id}-${UUID.randomUUID().toString().replace("-", "").take(8)}"
                    core.markRawPrimary(primary.id, group)
                    for (item in all) {
                        if (item.id != primary.id) {
                            core.markRawDuplicate(item.id, primary.id, group, DUPLICATE_REASON_EXACT)
                        }
                    }
                } else if (related.isNotEmpty()) {
                    val anchor = related.minBy { TimeUtils.notificationInstant(it.notificationTime, it.receivedAt) }
                    val group = anchor.duplicateGroupId ?: "update-${anchor.id}-${UUID.randomUUID().toString().replace("-", "").take(8)}"
                    core.setDuplicateGroupPair(anchor.id, row.id, group)
                    core.setDuplicateReason(row.id, DUPLICATE_REASON_UPDATE)
                }
            }
        }
    }

    companion object {
        const val DUPLICATE_REASON_EXACT = "same normalized message from same channel within configured window"
        const val DUPLICATE_REASON_UPDATE = "signal_update: richer same-channel message with entries, targets, stop loss, or leverage"

        val DETAIL_MARKERS = Regex(
            "\\b(?:ENTRY|ENTRIES|TARGETS?|TP\\d*|SL|STOP\\s*LOSS|LEVERAGE)\\b|\\b\\d+(?:\\.\\d+)?X\\b",
            RegexOption.IGNORE_CASE
        )

        fun normalizedMessage(value: String?): String =
            Regex("\\s+").replace(Regex("[^A-Z0-9]+").replace((value ?: "").uppercase(Locale.US), " "), " ").trim()

        fun messageSubject(value: String?): Pair<String?, String?> {
            val text = (value ?: "").uppercase(Locale.US)
            val symbol = Regex("\\b[A-Z0-9]+[_/]?USDT\\b|\\b(?:NIFTY|BANKNIFTY|SENSEX|GOLD|SILVER|CRUDEOIL|XAUUSD)\\b").find(text)
            val direction = Regex("\\b(?:BUY|SELL|LONG|SHORT)\\b").find(text)
            return symbol?.value?.replace("_", "")?.replace("/", "") to direction?.value
        }
    }
}
