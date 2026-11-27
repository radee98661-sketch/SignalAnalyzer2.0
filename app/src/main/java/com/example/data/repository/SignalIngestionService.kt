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

            if (notificationId != null) {
                val existingRaw = core.getRawNotificationByNotificationId(notificationId)
                if (existingRaw != null) {
                    val prevMsg = existingRaw.message.orEmpty().trim()
                    val nextMsg = row.message.trim()
                    if (prevMsg == nextMsg) {
                        return@withTransaction null to "duplicate"
                    }
                    // Message was EDITED in Telegram / Google Sheets (same notification_id, updated text)
                    core.updateEditedRawNotification(
                        id = existingRaw.id,
                        message = row.message,
                        title = row.title,
                        category = row.category,
                        receivedAt = row.receivedAt,
                        deliveryId = row.deliveryId
                    )
                    val incomingTime = TimeUtils.notificationInstant(
                        existingRaw.notificationTime?.takeIf { it.isNotBlank() } ?: row.notificationTime,
                        existingRaw.receivedAt?.takeIf { it.isNotBlank() } ?: row.receivedAt
                    )
                    val effCat = rawCat.ifBlank { existingRaw.category.orEmpty() }
                    val effChannel = channel.ifBlank { existingRaw.channel.orEmpty() }

                    // Inherit active Forex symbol if applicable
                    if (effCat.lowercase(Locale.US) in setOf("forex", "fx")) {
                        val cutoffIso = incomingTime.minus(Duration.ofMinutes(30)).toString()
                        val recentFxSymbol = core.findRecentForexSymbolForReparse(
                            channel = effChannel,
                            cutoffIso = cutoffIso,
                            upperIso = incomingTime.toString(),
                            excludeRawId = existingRaw.id
                        )
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
                                    inherited = DefaultRulesEngine.applyDefaultSl(inherited, effCat, slRules)
                                    inherited = DefaultRulesEngine.applyDefaultTargets(inherited, effCat, slRules)
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

                    val existingSignals = core.getSignalsByRawId(existingRaw.id)
                    val hasManualOverride = existingSignals.any { it.parsingStatus == "manually_corrected" }
                    if (!hasManualOverride) {
                        val updatedSignalIds = mutableListOf<Pair<Long, ParsedSignal>>()
                        parsedItems.forEachIndexed { idx, item ->
                            val existingSig = existingSignals.getOrNull(idx)
                            val sigId = if (existingSig != null) {
                                core.updateSignalParsedFields(
                                    id = existingSig.id,
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
                                    parsingExplanation = (item.explanation.trimEnd('.') + " | Auto-reparsed from edited message.").trim(),
                                    updatedAt = nowIso
                                )
                                existingSig.id
                            } else {
                                core.insertSignal(
                                    SignalEntity(
                                        rawNotificationId = existingRaw.id,
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
                                        parsingExplanation = (item.explanation.trimEnd('.') + " | Auto-parsed from edited message.").trim(),
                                        updatedAt = nowIso
                                    )
                                )
                            }
                            updatedSignalIds += sigId to item
                        }

                        for ((sigId, item) in updatedSignalIds) {
                            val canVerify = item.status == "parsed" &&
                                !item.symbol.isNullOrBlank() &&
                                item.direction in setOf("BUY", "SELL") &&
                                (item.entryPrice != null || item.entryMode == "market_now")
                            if (canVerify) {
                                val isIdleWait = firstSched.isAfter(nowInstant)
                                val nextTime = if (isIdleWait) firstSched.toString() else nowInstant.toString()
                                val existingSched = verDao.getScheduleForSignal(sigId)
                                if (existingSched == null) {
                                    verDao.insertSchedule(
                                        VerificationScheduleEntity(
                                            signalId = sigId,
                                            signalStartAt = incomingTime.toString(),
                                            initialWaitMinutes = initialWait,
                                            intervalMinutes = repeatMinutes,
                                            timeframe = timeframe,
                                            firstScheduledAt = firstSched.toString(),
                                            nextScheduledAt = nextTime,
                                            expiresAt = expiresAt.toString(),
                                            status = "scheduled",
                                            lastResult = if (isIdleWait) {
                                                "Idle wait (${initialWait}m) after edited message"
                                            } else {
                                                "Queued for catch-up verification after message edit"
                                            },
                                            createdAt = nowIso,
                                            updatedAt = nowIso
                                        )
                                    )
                                } else {
                                    verDao.rescheduleSignal(
                                        signalId = sigId,
                                        startAt = incomingTime.toString(),
                                        firstAt = firstSched.toString(),
                                        nextAt = nextTime,
                                        updatedAt = nowIso
                                    )
                                }
                            }
                        }
                    }

                    core.insertSystemLog(
                        SystemLogEntity(
                            createdAt = nowIso,
                            severity = "INFO",
                            source = "ingest",
                            message = "Updated edited notification ${existingRaw.id} (${parsedItems.size} signal(s))"
                        )
                    )
                    return@withTransaction existingRaw.id to "updated"
                }
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
            val hasDetailMarkers = hasUpdateDetailMarkers(row.message)

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

        if (result.second == "imported" || result.second == "updated") {
            eventBus.emit(AppEvent("signal.changed", result.first, result.second, null, TimeUtils.utcNowIso()))
        }
        return result
    }

    suspend fun repairFalseSignalUpdates(): Int {
        val nowIso = TimeUtils.utcNowIso()
        val repaired = db.withTransaction {
            val core = db.coreDao()
            val flagged = core.getSignalUpdateRawNotifications()
            if (flagged.isEmpty()) return@withTransaction 0

            val affectedGroups = linkedSetOf<String>()
            var fixedCount = 0

            for (row in flagged) {
                val hasMarkers = hasUpdateDetailMarkers(row.message)
                val rowSubj = messageSubject(row.message)
                val groupId = row.duplicateGroupId
                val matchesPeerInGroup = if (hasMarkers && rowSubj.first != null && !groupId.isNullOrBlank()) {
                    val peers = core.getRawNotificationsByGroupId(groupId).filter { it.id != row.id }
                    peers.any { peer -> messageSubject(peer.message) == rowSubj }
                } else {
                    false
                }

                if (!matchesPeerInGroup) {
                    core.clearFalseSignalUpdate(row.id)
                    if (!groupId.isNullOrBlank()) affectedGroups += groupId
                    fixedCount++
                }
            }

            for (gid in affectedGroups) {
                val remaining = core.getRawNotificationsByGroupId(gid)
                if (remaining.size == 1) {
                    val only = remaining.first()
                    if (only.isDuplicate == 0 && only.duplicateReason.isNullOrBlank()) {
                        core.clearOrphanDuplicateGroup(only.id)
                    }
                }
            }

            if (fixedCount > 0) {
                core.insertSystemLog(
                    SystemLogEntity(
                        createdAt = nowIso,
                        severity = "INFO",
                        source = "ingest",
                        message = "Repaired $fixedCount falsely grouped signal_update notification(s)"
                    )
                )
            }
            fixedCount
        }
        if (repaired > 0) {
            eventBus.emit(AppEvent("signal.changed", null, "signal_updates_repaired", "count=$repaired", nowIso))
        }
        return repaired
    }

    suspend fun backfillDuplicateClassifications() {
        repairFalseSignalUpdates()
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
                val hasMarkers = hasUpdateDetailMarkers(row.message)
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

        private val PREMIUM_PLACEHOLDER_LINE_RE = Regex(
            "(?:\\b(?:TARGETS?|TP\\d*|SL|STOP\\s*LOSS|ENTRY|ENTRIES)\\b[^\\n]*\\b(?:ONLY\\s+FOR|PREMIUM|PAID|VIP|MEMBERS?|GROUP|CHANNEL|OPEN|COMING\\s+SOON|WILL\\s+UPDATE|SOON)\\b[^\\n]*)",
            RegexOption.IGNORE_CASE
        )

        val DETAIL_MARKERS = Regex(
            "\\b(?:ENTRY|ENTRIES|TARGETS?|TP\\d*|SL|STOP\\s*LOSS|LEVERAGE)\\b\\s*[:=\\-–—@]*\\s*(?:\\n\\s*)?(?:\\d+\\s*[).:]\\s*)?\\d+(?:\\.\\d+)?|\\b\\d+(?:\\.\\d+)?\\s*X\\b",
            RegexOption.IGNORE_CASE
        )

        private val INDEX_OPTION_SUBJECT_RE = Regex(
            "\\b(NIFTY|BANKNIFTY|FINNIFTY|MIDCPNIFTY|SENSEX|BANKEX)\\b(?:\\s+\\d{1,2}\\s*[A-Z]{3,9}(?:\\s*\\d{2,4})?)?\\s+(\\d{3,6}(?:\\.\\d+)?)\\s*(CE|PE|CALL|PUT)\\b"
        )

        private val GENERIC_OPTION_SUBJECT_RE = Regex(
            "\\b([A-Z][A-Z0-9&]{1,14})\\b(?:\\s+\\d{1,2}\\s*[A-Z]{3,9}(?:\\s*\\d{2,4})?)?\\s+(\\d{2,6}(?:\\.\\d+)?)\\s*(CE|PE|CALL|PUT)\\b"
        )

        private val OPTION_SUBJECT_STOP_WORDS = setOf(
            "BUY", "SELL", "LONG", "SHORT", "AT", "CMP", "ABOVE", "BELOW", "NEAR", "AROUND",
            "LEVEL", "LEVELS", "ENTRY", "ENTRIES", "TARGET", "TARGETS", "SL", "STOP", "LOSS",
            "ONLY", "FOR", "PREMIUM", "MEMBERS", "PAID", "VIP", "IN", "ON", "THE", "OF",
            "JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"
        )

        private val BASE_SYMBOL_SUBJECT_RE = Regex(
            "\\b[A-Z0-9]+[_/]?USDT\\b|\\b(?:NIFTY|BANKNIFTY|FINNIFTY|MIDCPNIFTY|SENSEX|BANKEX|GOLD|SILVER|CRUDEOIL|NATURALGAS|XAUUSD|XAGUSD|EURUSD|GBPUSD|USDJPY|GBPJPY|AUDUSD|USDCAD|USDCHF|NZDUSD|EURGBP|EURJPY|US30|NAS100)\\b"
        )

        fun hasUpdateDetailMarkers(value: String?): Boolean {
            if (value.isNullOrBlank()) return false
            val cleaned = PREMIUM_PLACEHOLDER_LINE_RE.replace(value, " ")
            return DETAIL_MARKERS.containsMatchIn(cleaned)
        }

        fun normalizedMessage(value: String?): String =
            Regex("\\s+").replace(Regex("[^A-Z0-9]+").replace((value ?: "").uppercase(Locale.US), " "), " ").trim()

        private fun normalizeStrikeToken(raw: String): String {
            val d = raw.toDoubleOrNull() ?: return raw
            return if (d == d.toLong().toDouble()) d.toLong().toString() else raw
        }

        fun messageSubject(value: String?): Pair<String?, String?> {
            val text = (value ?: "").uppercase(Locale.US)
            val direction = Regex("\\b(?:BUY|SELL|LONG|SHORT)\\b").find(text)?.value

            val idxOpt = INDEX_OPTION_SUBJECT_RE.find(text)
            if (idxOpt != null) {
                val base = idxOpt.groupValues[1]
                val strike = normalizeStrikeToken(idxOpt.groupValues[2])
                val optType = if (idxOpt.groupValues[3] in setOf("CE", "CALL")) "CE" else "PE"
                return "$base:$strike:$optType" to direction
            }

            for (m in GENERIC_OPTION_SUBJECT_RE.findAll(text)) {
                val candidate = m.groupValues[1]
                if (candidate in OPTION_SUBJECT_STOP_WORDS) continue
                val strike = normalizeStrikeToken(m.groupValues[2])
                val optType = if (m.groupValues[3] in setOf("CE", "CALL")) "CE" else "PE"
                return "$candidate:$strike:$optType" to direction
            }

            val symbol = BASE_SYMBOL_SUBJECT_RE.find(text)
            return symbol?.value?.replace("_", "")?.replace("/", "") to direction
        }
    }
}
