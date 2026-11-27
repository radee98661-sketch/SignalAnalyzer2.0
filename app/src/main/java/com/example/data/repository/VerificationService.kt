package com.example.data.repository

import com.example.data.local.ApiRequestLogEntity
import com.example.data.local.AppDatabase
import com.example.data.local.ApplicationSettingEntity
import com.example.data.local.InstrumentEntity
import com.example.data.local.MarketCandleEntity
import com.example.data.local.SecretStore
import com.example.data.local.SignalAnalysisEntity
import com.example.data.local.SignalJoinedRow
import com.example.data.local.SystemLogEntity
import com.example.data.local.VerificationRunEntity
import com.example.data.provider.MarketDataProviders
import com.example.data.provider.ProviderError
import com.example.data.provider.ProviderErrorClassifier
import com.example.domain.model.AppEvent
import com.example.domain.model.Candle
import com.example.domain.model.LegResult
import com.example.domain.model.ParsedSignal
import com.example.domain.model.TargetLevelPnl
import com.example.domain.model.VerificationTimeline
import com.example.domain.parser.DefaultRulesEngine
import com.example.domain.verification.VerifiableSignal
import com.example.domain.verification.VerificationEngine
import com.example.util.JsonUtils
import com.example.util.TimeUtils
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import java.util.Locale
import kotlin.math.abs

data class ProcessSignalOutput(
    val signalId: Long,
    val status: String,
    val detail: String,
    val candleTime: String?,
    val pnlAmount: Double = 0.0,
    val pnlCurrency: String = "INR",
    val tradesCount: Int = 0,
    val timeline: VerificationTimeline? = null
)

class VerificationService(
    private val db: AppDatabase,
    private val secretStore: SecretStore,
    private val providers: MarketDataProviders,
    private val eventBus: EventBus,
    val failover: ProviderFailoverManager = ProviderFailoverManager()
) {
    private var lastMasterError: String? = null
    private var lastSearchScripSample: List<String> = emptyList()

    private val istZone = ZoneOffset.ofHoursMinutes(5, 30)
    private val angelDateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(istZone)
    private val expiryFmtUpper = DateTimeFormatter.ofPattern("ddMMMyyyy", Locale.US)

    suspend fun resolveSignalProvider(item: SignalJoinedRow): Pair<String, String> {
        val category = (item.category ?: "").trim().lowercase(Locale.US)
        val symbol = (item.symbol ?: "").trim().uppercase(Locale.US)
        if (category == "crypto" || symbol.endsWith("USDT") || symbol.endsWith("USDC") || symbol.endsWith("BUSD")) {
            return "binance" to symbol
        }
        if (category == "forex" || category == "fx" || symbol in FOREX_ROUTED_SYMBOLS) {
            return "twelve_data" to symbol
        }

        // 1. Check explicit manual mappings in instrument_mappings
        val marketDao = db.marketDao()
        val lookupCandidates = mutableListOf<String>()
        if (item.strikePrice != null && !item.optionType.isNullOrBlank()) {
            val sp = item.strikePrice
            val strikeStr = if (sp == sp.toLong().toDouble()) sp.toLong().toString() else sp.toString()
            if (!item.expiryDate.isNullOrBlank()) {
                lookupCandidates += "$symbol|${item.expiryDate}|$strikeStr|${item.optionType}"
            }
            lookupCandidates += "$symbol|$strikeStr|${item.optionType}"
        }
        if (symbol.isNotEmpty()) lookupCandidates += symbol

        for (key in lookupCandidates) {
            val mapped = marketDao.getInstrumentMapping(key)
            if (mapped != null) {
                return "angel_one" to "${mapped.exchangeSegment}:${mapped.instrumentToken}"
            }
        }

        // 2. Auto-resolve from local instruments or live searchScrip
        val autoResolved = autoResolveAngelInstrument(item, category, symbol)
        if (autoResolved != null) return "angel_one" to autoResolved
        return "angel_one" to symbol
    }

    private fun rowUnderlying(row: InstrumentEntity): String {
        val rawName = try {
            JSONObject(row.rawJson ?: "{}").optString("name", "").trim().uppercase(Locale.US)
        } catch (_: Exception) {
            ""
        }
        if (rawName.isNotEmpty()) return rawName
        val tsym = (row.tradingSymbol.ifBlank { row.symbol ?: "" }).trim().uppercase(Locale.US)
        val m = Regex("^([A-Z&\\-]+?)(?:\\d|$)").find(tsym)
        return m?.groupValues?.get(1) ?: tsym
    }

    private fun parseMasterExpiry(exp: String?): LocalDate? {
        if (exp.isNullOrBlank()) return null
        val clean = exp.trim().uppercase(Locale.US)
        val patterns = listOf("dMMMyyyy", "dMMMyy", "yyyy-MM-dd", "d-MMM-yyyy")
        for (p in patterns) {
            try {
                val fmt = DateTimeFormatterBuilder()
                    .parseCaseInsensitive()
                    .appendPattern(p)
                    .toFormatter(Locale.US)
                return LocalDate.parse(clean, fmt)
            } catch (_: Exception) {
            }
        }
        return null
    }

    internal fun parseLiveExpiryToken(expToken: String, refDate: LocalDate): LocalDate {
        parseMasterExpiry(expToken)?.let { return it }
        val clean = expToken.trim().uppercase(Locale.US)
        try {
            val fmt = DateTimeFormatterBuilder()
                .parseCaseInsensitive()
                .appendPattern("dMMM")
                .parseDefaulting(ChronoField.YEAR, refDate.year.toLong())
                .toFormatter(Locale.US)
            val dt = LocalDate.parse(clean, fmt)
            return if (dt.isBefore(refDate.minusDays(180))) dt.plusYears(1) else dt
        } catch (_: Exception) {
        }
        return refDate
    }

    private suspend fun maybeRefreshAngelMaster(): Boolean {
        val core = db.coreDao()
        val lastAt = core.getSetting("angel_master_auto_refreshed_at")
        if (!lastAt.isNullOrBlank()) {
            val prev = TimeUtils.parseUtcInstant(lastAt)
            if (prev != null && Duration.between(prev, Instant.now()) < Duration.ofMinutes(30)) {
                return false
            }
        }
        return try {
            val arr = providers.downloadAngelScripMaster()
            val market = db.marketDao()
            val nowIso = TimeUtils.utcNowIso()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val token = obj.optString("token", "").trim()
                if (token.isEmpty()) continue
                market.upsertInstrument(
                    symbol = obj.optString("symbol", null),
                    tradingSymbol = obj.optString("symbol", ""),
                    token = token,
                    exchange = obj.optString("exch_seg", ""),
                    instType = obj.optString("instrumenttype", null),
                    expiry = obj.optString("expiry", null),
                    strike = obj.optString("strike", "0").toDoubleOrNull() ?: 0.0,
                    lotSize = obj.optString("lotsize", "0").toDoubleOrNull() ?: 0.0,
                    tickSize = obj.optString("tick_size", "0").toDoubleOrNull() ?: 0.0,
                    rawJson = obj.toString(),
                    updatedAt = nowIso
                )
            }
            core.upsertSetting(ApplicationSettingEntity("angel_master_auto_refreshed_at", nowIso, nowIso))
            lastMasterError = null
            true
        } catch (e: Exception) {
            lastMasterError = e.message?.take(250)
            false
        }
    }

    private suspend fun extractSymbolCandidates(symbol: String, exchanges: List<String>, instTypes: List<String>): List<String> {
        val clean = SYMBOL_ALIASES[symbol] ?: symbol
        val rows = db.marketDao().getInstrumentsBySegmentsAndTypes(
            exchanges.map { it.uppercase(Locale.US) },
            instTypes.map { it.uppercase(Locale.US) }
        )
        val underlyings = rows.map { rowUnderlying(it) }.filter { it.isNotEmpty() }.toSortedSet()
        if (clean in underlyings) return listOf(clean)

        val matches = mutableListOf<String>()
        val prefix = if (clean.length >= 6) clean.take(6) else clean
        for (u in underlyings) {
            if (u.startsWith(prefix) || clean in u || u in clean) matches += u
        }
        // Simple similarity fallback (Levenshtein ratio >= 0.72)
        for (u in underlyings) {
            if (u !in matches && similarityRatio(clean, u) >= 0.72) matches += u
        }
        return matches.ifEmpty { listOf(clean) }
    }

    private fun similarityRatio(a: String, b: String): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        val maxLen = maxOf(a.length, b.length)
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val temp = dp[j]
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + cost)
                prev = temp
            }
        }
        return 1.0 - (dp[b.length].toDouble() / maxLen.toDouble())
    }

    private suspend fun queryAngelCandidates(
        targetSymbol: String,
        exchanges: List<String>,
        instTypes: List<String>,
        isOption: Boolean,
        strike: Double?,
        optType: String
    ): List<InstrumentEntity> {
        val market = db.marketDao()
        val exUpper = exchanges.map { it.uppercase(Locale.US) }
        val itUpper = instTypes.map { it.uppercase(Locale.US) }
        val rawList = if (isOption && strike != null) {
            market.findOptionInstruments(exUpper, itUpper, strike * 100.0, strike, "%$optType")
        } else {
            market.findFutureInstruments(exUpper, itUpper, "%${targetSymbol.take(5)}%")
        }
        val prefix = if (targetSymbol.length >= 6) targetSymbol.take(6) else targetSymbol
        return rawList.filter { r ->
            val u = rowUnderlying(r)
            val tsym = Regex("[\\s\\-_]+").replace((r.tradingSymbol.ifBlank { r.symbol ?: "" }).uppercase(Locale.US), "")
            u == targetSymbol || u.startsWith(prefix) || tsym.startsWith(prefix)
        }
    }

    private suspend fun liveSearchAngelContract(
        item: SignalJoinedRow,
        exchanges: List<String>,
        instTypes: List<String>,
        targetSymbol: String,
        isOption: Boolean,
        strike: Double?,
        optType: String,
        refDate: LocalDate
    ): String? {
        lastSearchScripSample = emptyList()
        val secrets = secretStore.getProviderSecrets("angel_one")
        val accounts = failover.angelOneAccounts(secrets)
        if (accounts.isEmpty()) return null
        val (candidates, pausedErr) = failover.selectOrderedAccounts("angel_one", accounts) { it.slot }
        if (pausedErr != null || candidates.isEmpty()) return null

        val strikeNum = strike ?: 0.0
        val strikeClean = if (strikeNum == strikeNum.toLong().toDouble()) strikeNum.toLong().toString() else strikeNum.toString()
        val queries = mutableListOf<String>()
        if (isOption) queries += "$targetSymbol $strikeClean $optType"
        queries += targetSymbol
        if (targetSymbol.length > 6) queries += targetSymbol.take(6)

        val rawHits = mutableListOf<JSONObject>()
        var accountIdx = 0
        while (accountIdx < candidates.size && rawHits.isEmpty()) {
            val acc = candidates[accountIdx]
            val accSecrets = acc.toSecretsMap()
            try {
                var jwt = providers.authenticateAngelOne(accSecrets).first
                for (exch in exchanges) {
                    for (q in queries) {
                        val hits = try {
                            providers.searchAngelScrip(jwt, acc.apiKey, exch, q)
                        } catch (e: ProviderError) {
                            val msg = e.message.orEmpty()
                            if ("Invalid Token" in msg || "401" in msg || "AG8001" in msg) {
                                jwt = providers.authenticateAngelOne(accSecrets, forceRefresh = true).first
                                providers.searchAngelScrip(jwt, acc.apiKey, exch, q)
                            } else if (failover.shouldFailover(e.category, e.httpCode)) {
                                throw e
                            } else {
                                emptyList()
                            }
                        }
                        if (hits.isNotEmpty()) {
                            rawHits.addAll(hits)
                            break
                        }
                    }
                }
                failover.recordSlotSuccess("angel_one", acc.slot)
                break
            } catch (e: ProviderError) {
                failover.recordSlotFailure("angel_one", acc.slot, e.category, e.httpCode, e.message)
                if (accountIdx + 1 < candidates.size && failover.shouldFailover(e.category, e.httpCode)) {
                    logFailoverEvent("angel_one", acc.slot, candidates[accountIdx + 1].slot, e.category, e.message)
                    accountIdx++
                } else {
                    break
                }
            } catch (_: Exception) {
                break
            }
        }
        if (rawHits.isEmpty()) return null

        lastSearchScripSample = rawHits.take(5).mapNotNull { h ->
            val ts = h.optString("tradingsymbol", "")
            if (ts.isNotEmpty()) "${h.optString("exchange", "")}:$ts" else null
        }

        val hintExpiry = Regex("[^A-Z0-9]").replace((item.expiryDate ?: "").uppercase(Locale.US), "")
        val prefix = if (targetSymbol.length >= 6) targetSymbol.take(6) else targetSymbol

        data class ScoredHit(
            val hintMatch: Boolean,
            val isActive: Boolean,
            val expDate: LocalDate,
            val exch: String,
            val tok: String,
            val rawTsym: String
        )

        val scored = mutableListOf<ScoredHit>()
        for (h in rawHits) {
            val exch = h.optString("exchange", exchanges.first()).trim().uppercase(Locale.US)
            val rawTsym = h.optString("tradingsymbol", "").trim().uppercase(Locale.US)
            val tsym = Regex("[\\s\\-_]+").replace(rawTsym, "")
            val tok = h.optString("symboltoken", "").trim()
            if (tsym.isEmpty() || tok.isEmpty()) continue

            val expToken: String
            if (isOption) {
                val m = Regex("^(${Regex.escape(prefix)}[A-Z&]*?)(\\d{1,2}[A-Z]{3}(?:\\d{2,4})?)(\\d+(?:\\.\\d+)?)${Regex.escape(optType)}$").find(tsym)
                    ?: continue
                expToken = m.groupValues[2]
                val foundStrike = m.groupValues[3].toDoubleOrNull() ?: continue
                if (abs(foundStrike - strikeNum) > 0.05) continue
            } else {
                val m = Regex("^(${Regex.escape(prefix)}[A-Z&]*?)(\\d{1,2}[A-Z]{3}(?:\\d{2,4})?)FUT$").find(tsym)
                    ?: continue
                expToken = m.groupValues[2]
            }

            val expDate = parseLiveExpiryToken(expToken, refDate)
            val hintMatch = hintExpiry.isNotEmpty() && hintExpiry in expToken
            scored += ScoredHit(hintMatch, !expDate.isBefore(refDate), expDate, exch, tok, rawTsym)
        }
        if (scored.isEmpty()) return null

        val active = scored.filter { it.isActive }
        val pick = if (active.isNotEmpty()) {
            val hinted = active.filter { it.hintMatch }
            (hinted.ifEmpty { active }).minBy { it.expDate }
        } else {
            scored.maxBy { it.expDate }
        }

        val rawJson = JSONObject().apply {
            put("name", targetSymbol)
            put("symbol", pick.rawTsym)
            put("token", pick.tok)
            put("exch_seg", pick.exch)
        }.toString()
        db.marketDao().upsertInstrument(
            symbol = pick.rawTsym,
            tradingSymbol = pick.rawTsym,
            token = pick.tok,
            exchange = pick.exch,
            instType = instTypes.first(),
            expiry = pick.expDate.format(expiryFmtUpper).uppercase(Locale.US),
            strike = if (isOption) strikeNum * 100.0 else 0.0,
            lotSize = 0.0,
            tickSize = 0.0,
            rawJson = rawJson,
            updatedAt = TimeUtils.utcNowIso()
        )
        return "${pick.exch}:${pick.tok}"
    }

    private suspend fun describeMissingContract(item: SignalJoinedRow, category: String, symbol: String): String {
        val strike = item.strikePrice
        val optType = (item.optionType ?: "").trim().uppercase(Locale.US)
        val isOption = strike != null && strike > 0.0 && optType in setOf("CE", "PE")

        val (exchanges, instTypes) = segmentAndTypesFor(category, symbol, isOption)
        val target = extractSymbolCandidates(symbol, exchanges, instTypes).first()
        val prefix = if (target.length >= 5) target.take(5) else target
        val rowsAll = db.marketDao().findFutureInstruments(exchanges, instTypes, "%$prefix%").filter { r ->
            rowUnderlying(r) == target || r.tradingSymbol.uppercase(Locale.US).startsWith(prefix)
        }
        if (rowsAll.isEmpty()) {
            val crossHits = db.marketDao().findCrossSegmentInstruments("%$prefix%")
                .map { "${it.exchangeSegment}:${it.tradingSymbol}" }
            val notes = mutableListOf<String>()
            if (crossHits.isNotEmpty()) notes += "Other segments have: ${crossHits.joinToString(", ")}"
            else notes += "No '$prefix*' contracts exist in Angel One's master (likely not in F&O)"
            if (lastSearchScripSample.isNotEmpty()) notes += "searchScrip returned: ${lastSearchScripSample.joinToString(", ")}"
            if (lastMasterError != null) notes += "Master refresh error: $lastMasterError"
            val sDisp = strike?.let { DefaultRulesEngine.formatG(it) } ?: ""
            return "Contract '$target $sDisp $optType' unavailable in ${exchanges.first()} (${instTypes.first()}). ${notes.joinToString("; ")}."
        }

        if (isOption && strike != null) {
            val strikes = rowsAll.mapNotNull { r ->
                val rawS = r.strike ?: 0.0
                if (rawS > 0.0) {
                    if (rawS >= 10000.0) DefaultRulesEngine.roundValue(rawS / 100.0, 2) else DefaultRulesEngine.roundValue(rawS, 2)
                } else null
            }.distinct().sorted()
            if (strikes.isNotEmpty()) {
                val nearest = strikes.sortedBy { abs(it - strike) }.take(6).sorted()
                return "Strike ${DefaultRulesEngine.formatG(strike)} $optType not listed for $target. Nearest strikes in master: ${nearest.joinToString(", ") { DefaultRulesEngine.formatG(it) }}."
            }
        }
        return "No matching $target contract found in Angel One ${exchanges.first()}."
    }

    private fun segmentAndTypesFor(category: String, symbol: String, isOption: Boolean): Pair<List<String>, List<String>> = when {
        symbol in INDEX_BFO -> listOf("BFO") to listOf(if (isOption) "OPTIDX" else "FUTIDX")
        category == "mcx" || symbol in MCX_SYMBOLS -> listOf("MCX") to listOf(if (isOption) "OPTFUT" else "FUTCOM")
        symbol in INDEX_NFO -> listOf("NFO") to listOf(if (isOption) "OPTIDX" else "FUTIDX")
        else -> listOf("NFO") to listOf(if (isOption) "OPTSTK" else "FUTSTK")
    }

    private suspend fun autoResolveAngelInstrument(item: SignalJoinedRow, category: String, symbol: String): String? {
        if (symbol.isBlank()) return null
        val strike = item.strikePrice
        val optType = (item.optionType ?: "").trim().uppercase(Locale.US)
        val isOption = strike != null && strike > 0.0 && optType in setOf("CE", "PE")
        val (exchanges, instTypes) = segmentAndTypesFor(category, symbol, isOption)

        val refDate = TimeUtils.notificationInstant(item.notificationTime, item.receivedAt)
            .atOffset(istZone).toLocalDate()

        var targetSymbols = extractSymbolCandidates(symbol, exchanges, instTypes)
        var candidates = emptyList<InstrumentEntity>()
        for (ts in targetSymbols) {
            candidates = queryAngelCandidates(ts, exchanges, instTypes, isOption, strike, optType)
            if (candidates.isNotEmpty()) break
        }

        if (candidates.isEmpty()) {
            try {
                val live = liveSearchAngelContract(item, exchanges, instTypes, targetSymbols.first(), isOption, strike, optType, refDate)
                if (live != null) return live
            } catch (_: Exception) {
            }
        }

        if (candidates.isEmpty() && maybeRefreshAngelMaster()) {
            targetSymbols = extractSymbolCandidates(symbol, exchanges, instTypes)
            for (ts in targetSymbols) {
                candidates = queryAngelCandidates(ts, exchanges, instTypes, isOption, strike, optType)
                if (candidates.isNotEmpty()) break
            }
        }

        if (candidates.isEmpty()) return null

        val hintExpiry = Regex("[^A-Z0-9]").replace((item.expiryDate ?: "").uppercase(Locale.US), "")
        data class ScoredRow(val hintMatch: Boolean, val isActive: Boolean, val expDate: LocalDate, val row: InstrumentEntity)

        val scored = candidates.mapNotNull { r ->
            val expStr = (r.expiry ?: "").trim().uppercase(Locale.US)
            val expDate = parseMasterExpiry(expStr) ?: return@mapNotNull null
            ScoredRow(hintExpiry.isNotEmpty() && hintExpiry in expStr, !expDate.isBefore(refDate), expDate, r)
        }
        if (scored.isEmpty()) {
            val pick = candidates.first()
            return "${pick.exchangeSegment}:${pick.instrumentToken}"
        }
        val active = scored.filter { it.isActive }
        val best = if (active.isNotEmpty()) {
            val hinted = active.filter { it.hintMatch }
            (hinted.ifEmpty { active }).minBy { it.expDate }.row
        } else {
            scored.maxBy { it.expDate }.row
        }
        return "${best.exchangeSegment}:${best.instrumentToken}"
    }

    suspend fun storeCandles(candles: List<Candle>): Int {
        if (candles.isEmpty()) return 0
        val now = TimeUtils.utcNowIso()
        val entities = candles.map { c ->
            MarketCandleEntity(
                provider = c.provider,
                symbol = c.symbol,
                exchange = c.exchange,
                interval = c.interval,
                candleTimestamp = c.candleTimestamp,
                timeframe = c.interval,
                candleTime = c.candleTimestamp,
                open = c.open,
                high = c.high,
                low = c.low,
                close = c.close,
                volume = c.volume,
                retrievedAt = now
            )
        }
        val inserted = db.marketDao().insertCandlesIgnore(entities).count { it > 0 }
        if (inserted > 0) {
            eventBus.emit(AppEvent("market.cache.changed", null, "stored", "${candles.first().provider}:${candles.first().symbol}", now))
        }
        return inserted
    }

    suspend fun recordApiCall(
        provider: String,
        endpoint: String,
        status: String,
        httpCode: Int? = null,
        errorCategory: String = "ok",
        creditsUsed: Double = 0.0,
        candlesReturned: Int = 0,
        error: String? = null,
        requestKey: String? = null
    ) {
        val now = TimeUtils.utcNowIso()
        val safeErr = error?.let { ProviderErrorClassifier.sanitize(it) }
        db.marketDao().insertApiRequestLog(
            ApiRequestLogEntity(
                provider = provider,
                endpoint = endpoint,
                requestedAt = now,
                completedAt = now,
                status = status,
                creditsUsed = creditsUsed,
                candlesReturned = candlesReturned,
                error = safeErr,
                requestKey = requestKey,
                httpCode = httpCode,
                errorCategory = errorCategory
            )
        )
        if (status != "success") {
            eventBus.emit(AppEvent("api.health.changed", null, errorCategory, "$provider: ${safeErr ?: errorCategory}", now))
        }
    }

    private suspend fun logFailoverEvent(
        provider: String,
        fromSlot: String,
        toSlot: String,
        category: String,
        reason: String?
    ) {
        val now = TimeUtils.utcNowIso()
        val safeReason = ProviderErrorClassifier.sanitize(reason).ifBlank { category }
        db.coreDao().insertSystemLog(
            SystemLogEntity(
                createdAt = now,
                severity = "WARNING",
                source = provider,
                message = "Auto-failover ($fromSlot -> $toSlot) triggered after $category: $safeReason"
            )
        )
        eventBus.emit(AppEvent("api.health.changed", null, "failover", "$provider switched $fromSlot -> $toSlot", now))
    }

    suspend fun fetchTwelveDataWithFailover(
        tdSym: String,
        interval: String,
        start: Instant?,
        end: Instant?,
        configMap: Map<String, Any?>
    ): Triple<List<Candle>, Double, String> {
        val secrets = secretStore.getProviderSecrets("twelve_data")
        val accounts = failover.twelveDataAccounts(secrets)
        if (accounts.isEmpty()) {
            recordApiCall("twelve_data", "/time_series", "error", null, "auth_error", 0.0, 0, "Twelve Data API key is not configured.", tdSym)
            throw ProviderError("Twelve Data API key is not configured.", null, "auth_error")
        }
        val (candidates, pausedErr) = failover.selectOrderedAccounts("twelve_data", accounts) { it.slot }
        if (pausedErr != null || candidates.isEmpty()) {
            throw pausedErr ?: ProviderError("Twelve Data accounts temporarily paused.", 429, "rate_limited")
        }

        var lastErr: ProviderError? = null
        for (i in candidates.indices) {
            val acc = candidates[i]
            val reqTag = "$tdSym [${acc.slot}]"
            try {
                val (fetched, credits) = providers.fetchTwelveDataCandles(tdSym, interval, start, end, configMap, acc.apiKey)
                failover.recordSlotSuccess("twelve_data", acc.slot)
                recordApiCall("twelve_data", "/time_series", "success", 200, "ok", credits, fetched.size, null, reqTag)
                return Triple(fetched, credits, acc.slot)
            } catch (e: ProviderError) {
                recordApiCall("twelve_data", "/time_series", "error", e.httpCode, e.category, 0.0, 0, "[${acc.slot}] ${e.message}", reqTag)
                failover.recordSlotFailure("twelve_data", acc.slot, e.category, e.httpCode, e.message)
                lastErr = e
                if (i + 1 < candidates.size && failover.shouldFailover(e.category, e.httpCode)) {
                    logFailoverEvent("twelve_data", acc.slot, candidates[i + 1].slot, e.category, e.message)
                    continue
                }
                throw e
            } catch (e: Exception) {
                val cat = ProviderErrorClassifier.classify(null, e.message, e is java.io.IOException)
                recordApiCall("twelve_data", "/time_series", "error", null, cat, 0.0, 0, "[${acc.slot}] ${e.message}", reqTag)
                failover.recordSlotFailure("twelve_data", acc.slot, cat, null, e.message)
                val pe = ProviderError(e.message ?: "Twelve Data fetch failed", null, cat)
                lastErr = pe
                if (i + 1 < candidates.size && failover.shouldFailover(cat, null)) {
                    logFailoverEvent("twelve_data", acc.slot, candidates[i + 1].slot, cat, e.message)
                    continue
                }
                throw pe
            }
        }
        throw lastErr ?: ProviderError("Twelve Data fetch failed across all accounts.", null, "provider_error")
    }

    suspend fun fetchAngelCandlesWithFailover(
        exchange: String,
        token: String,
        interval: String,
        fromStr: String,
        toStr: String,
        requestKey: String = "$exchange:$token"
    ): Pair<List<Candle>, String> {
        val secrets = secretStore.getProviderSecrets("angel_one")
        val accounts = failover.angelOneAccounts(secrets)
        if (accounts.isEmpty()) {
            recordApiCall("angel_one", "/historical/getCandleData", "error", null, "auth_error", 0.0, 0, "Angel One is not configured.", requestKey)
            throw ProviderError("Angel One is not configured.", null, "auth_error")
        }
        val (candidates, pausedErr) = failover.selectOrderedAccounts("angel_one", accounts) { it.slot }
        if (pausedErr != null || candidates.isEmpty()) {
            throw pausedErr ?: ProviderError("Angel One accounts temporarily paused.", 429, "rate_limited")
        }

        var lastErr: ProviderError? = null
        for (i in candidates.indices) {
            val acc = candidates[i]
            val accSecrets = acc.toSecretsMap()
            val reqTag = "$requestKey [${acc.slot}]"
            try {
                var jwt = providers.authenticateAngelOne(accSecrets).first
                val fetched = try {
                    providers.fetchAngelCandles(jwt, acc.apiKey, exchange, token, interval, fromStr, toStr)
                } catch (e: ProviderError) {
                    val msg = e.message.orEmpty()
                    if ("Invalid Token" in msg || "401" in msg || "AG8001" in msg) {
                        jwt = providers.authenticateAngelOne(accSecrets, forceRefresh = true).first
                        providers.fetchAngelCandles(jwt, acc.apiKey, exchange, token, interval, fromStr, toStr)
                    } else {
                        throw e
                    }
                }
                failover.recordSlotSuccess("angel_one", acc.slot)
                recordApiCall("angel_one", "/historical/getCandleData", "success", 200, "ok", 0.0, fetched.size, null, reqTag)
                return fetched to acc.slot
            } catch (e: ProviderError) {
                recordApiCall("angel_one", "/historical/getCandleData", "error", e.httpCode, e.category, 0.0, 0, "[${acc.slot}] ${e.message}", reqTag)
                failover.recordSlotFailure("angel_one", acc.slot, e.category, e.httpCode, e.message)
                lastErr = e
                if (i + 1 < candidates.size && failover.shouldFailover(e.category, e.httpCode)) {
                    logFailoverEvent("angel_one", acc.slot, candidates[i + 1].slot, e.category, e.message)
                    continue
                }
                throw e
            } catch (e: Exception) {
                val cat = ProviderErrorClassifier.classify(null, e.message, e is java.io.IOException)
                recordApiCall("angel_one", "/historical/getCandleData", "error", null, cat, 0.0, 0, "[${acc.slot}] ${e.message}", reqTag)
                failover.recordSlotFailure("angel_one", acc.slot, cat, null, e.message)
                val pe = ProviderError(e.message ?: "Angel One fetch failed", null, cat)
                lastErr = pe
                if (i + 1 < candidates.size && failover.shouldFailover(cat, null)) {
                    logFailoverEvent("angel_one", acc.slot, candidates[i + 1].slot, cat, e.message)
                    continue
                }
                throw pe
            }
        }
        throw lastErr ?: ProviderError("Angel One fetch failed across all accounts.", null, "provider_error")
    }

    private suspend fun fetchProviderRange(
        provider: String,
        resolvedSym: String,
        cacheSym: String,
        fetchStart: Instant,
        fetchEnd: Instant,
        item: SignalJoinedRow?
    ): Int {
        if (!fetchEnd.isAfter(fetchStart.plusSeconds(30))) return 0
        val integration = db.coreDao().getIntegration(provider)
        val configMap = integration?.let { jsonObjectToMap(it.configJson) } ?: emptyMap()

        val candles: List<Candle> = when (provider) {
            "binance" -> {
                try {
                    val fetched = providers.fetchBinanceCandles(cacheSym, "1m", fetchStart, fetchEnd, configMap)
                    recordApiCall("binance", "/fapi/v1/klines", "success", 200, "ok", 0.0, fetched.size, null, cacheSym)
                    fetched
                } catch (e: ProviderError) {
                    recordApiCall("binance", "/fapi/v1/klines", "error", e.httpCode, e.category, 0.0, 0, e.message, cacheSym)
                    throw e
                } catch (e: Exception) {
                    val cat = ProviderErrorClassifier.classify(null, e.message, e is java.io.IOException)
                    recordApiCall("binance", "/fapi/v1/klines", "error", null, cat, 0.0, 0, e.message, cacheSym)
                    throw ProviderError(e.message ?: "Binance fetch failed", null, cat)
                }
            }
            "twelve_data" -> {
                val tdSym = if (Regex("^[A-Z]{6}$").matches(cacheSym)) {
                    "${cacheSym.substring(0, 3)}/${cacheSym.substring(3)}"
                } else cacheSym
                val (fetched, _, _) = fetchTwelveDataWithFailover(tdSym, "1min", fetchStart, fetchEnd, configMap)
                fetched.map { it.copy(symbol = cacheSym) }
            }
            else -> {
                if (":" !in resolvedSym) {
                    val errMsg = if (item != null) {
                        val cat = (item.category ?: "").trim().lowercase(Locale.US)
                        describeMissingContract(item, cat, resolvedSym)
                    } else {
                        "No matching contract found in Angel One for $resolvedSym."
                    }
                    recordApiCall("angel_one", "/historical/getCandleData", "error", null, "provider_error", 0.0, 0, errMsg, resolvedSym)
                    throw ProviderError(errMsg)
                }
                val (exchange, token) = resolvedSym.split(":", limit = 2)
                val fromStr = angelDateFmt.format(fetchStart)
                val toStr = angelDateFmt.format(fetchEnd)
                fetchAngelCandlesWithFailover(exchange, token, "ONE_MINUTE", fromStr, toStr, resolvedSym).first
            }
        }
        storeCandles(candles)
        return candles.size
    }

    suspend fun fetchCandlesForSignal(item: SignalJoinedRow, start: Instant, end: Instant): Int {
        val (provider, resolvedSym) = resolveSignalProvider(item)
        if (resolvedSym.isBlank()) throw ProviderError("Signal has no resolved symbol.")

        val desiredStart = TimeUtils.truncateToMinute(start)
        if (!end.isAfter(desiredStart.plusSeconds(30))) return 0

        val cacheSym = if (":" in resolvedSym) resolvedSym.substringAfter(":") else resolvedSym
        val bounds = db.marketDao().getCandleBounds(provider, cacheSym)
        val minDt = TimeUtils.parseUtcInstant(bounds?.minTs)
        val maxDt = TimeUtils.parseUtcInstant(bounds?.maxTs)

        if (minDt == null || maxDt == null) {
            val fetched = fetchProviderRange(provider, resolvedSym, cacheSym, desiredStart, end, item)
            if (fetched > 0) failover.clearEmptyForwardFetch(provider, cacheSym)
            return fetched
        }

        var fetchedTotal = 0
        if (desiredStart.isBefore(minDt)) {
            fetchedTotal += fetchProviderRange(provider, resolvedSym, cacheSym, desiredStart, minDt, item)
        }
        if (end.isAfter(maxDt.plusSeconds(30)) && !failover.isForwardFetchCooledDown(provider, cacheSym)) {
            val forwardStart = if (desiredStart.isAfter(maxDt)) desiredStart else maxDt
            val forwardFetched = fetchProviderRange(provider, resolvedSym, cacheSym, forwardStart, end, item)
            fetchedTotal += forwardFetched
            if (forwardFetched == 0) {
                // Market likely closed / no new candle formed yet — avoid burning credits every tick
                failover.markEmptyForwardFetch(provider, cacheSym)
            } else {
                failover.clearEmptyForwardFetch(provider, cacheSym)
            }
        }
        return fetchedTotal
    }

    suspend fun processSignal(
        signalId: Long,
        runType: String = "manual",
        scheduleId: Long? = null
    ): ProcessSignalOutput {
        val core = db.coreDao()
        val market = db.marketDao()
        val verDao = db.verificationDao()
        val rules = DefaultRulesEngine.rulesFromJson(core.getSetting("default_sl_rules"))

        val record = core.getSignalJoinedById(signalId)
            ?: return ProcessSignalOutput(signalId, "missing", "Signal not found.", null)
        if (record.isDuplicate == 1) {
            return ProcessSignalOutput(signalId, "duplicate", "Duplicates are not verified.", null)
        }

        val runId = verDao.insertVerificationRun(
            VerificationRunEntity(
                signalId = signalId,
                scheduleId = scheduleId,
                runType = runType,
                startedAt = TimeUtils.utcNowIso(),
                status = "running"
            )
        )

        var entryPrice = record.entryPrice
        val additionalEntries = JsonUtils.jsonToDoubleList(record.additionalEntries)
        var stopLoss = record.stopLoss
        var targets = JsonUtils.jsonToDoubleList(record.targets)
        val entryMode = record.entryMode.trim().lowercase(Locale.US).ifEmpty { "limit" }

        val (provider, resolvedSym) = resolveSignalProvider(record)
        val cacheSym = if (":" in resolvedSym) resolvedSym.substringAfter(":") else resolvedSym
        val sigStart = TimeUtils.truncateToMinute(TimeUtils.notificationInstant(record.notificationTime, record.receivedAt))

        var resolvedLabel = resolvedSym
        var instLotFromMaster = 0.0
        if (provider == "angel_one" && ":" in resolvedSym) {
            val (exchPart, tokPart) = resolvedSym.split(":", limit = 2)
            val instRow = market.getInstrumentByToken(tokPart)
            if (instRow != null) {
                if (instRow.tradingSymbol.isNotBlank()) {
                    resolvedLabel = "$exchPart:${instRow.tradingSymbol} (token $tokPart)"
                }
                instLotFromMaster = instRow.lotSize ?: 0.0
            }
        }

        var fetchError: String? = null
        if (!record.symbol.isNullOrBlank() && !record.direction.isNullOrBlank() &&
            (entryPrice != null || entryMode == "market_now")
        ) {
            try {
                fetchCandlesForSignal(record, sigStart, Instant.now())
            } catch (e: Exception) {
                fetchError = e.message?.take(400) ?: "provider fetch error"
            }
        }

        val candles = market.getCandlesSince(provider, cacheSym, sigStart.toString()).map {
            Candle(it.provider, it.symbol, it.exchange, it.interval, it.candleTimestamp, it.open, it.high, it.low, it.close, it.volume ?: 0.0)
        }

        // Lock in market_now entry price from first candle open
        if (entryMode == "market_now" && entryPrice == null && candles.isNotEmpty()) {
            val firstC = candles.first()
            val marketEntry = firstC.open.takeIf { it > 0.0 } ?: firstC.close
            entryPrice = marketEntry
            var expl = record.parsingExplanation.orEmpty()
            val note = "Market entry @ ${DefaultRulesEngine.formatG(marketEntry)} (${firstC.candleTimestamp})"
            if (note !in expl) {
                expl = (expl.trimEnd('.') + " | $note.").trim()
            }
            var tempParsed = ParsedSignal(
                category = record.category.orEmpty(),
                symbol = record.symbol,
                direction = record.direction,
                entryPrice = entryPrice,
                additionalEntries = additionalEntries,
                stopLoss = stopLoss,
                targets = targets,
                optionType = record.optionType,
                entryMode = entryMode,
                explanation = expl
            )
            tempParsed = DefaultRulesEngine.applyDefaultSl(tempParsed, record.category.orEmpty(), rules)
            tempParsed = DefaultRulesEngine.applyDefaultTargets(tempParsed, record.category.orEmpty(), rules)
            stopLoss = tempParsed.stopLoss
            targets = tempParsed.targets
            core.updateSignalMarketEntryAndDefaults(
                id = signalId,
                entryPrice = entryPrice,
                stopLoss = stopLoss,
                targets = JsonUtils.doubleListToJson(targets),
                explanation = tempParsed.explanation,
                updatedAt = TimeUtils.utcNowIso()
            )
        }

        val tolerance = core.getSetting("entry_tolerance")?.toDoubleOrNull() ?: 0.0
        val vRes = VerificationEngine.verifyDetailed(
            VerifiableSignal(
                direction = record.direction,
                entryPrice = entryPrice,
                additionalEntries = additionalEntries,
                stopLoss = stopLoss,
                targets = targets,
                entryMode = entryMode
            ),
            candles,
            tolerance
        )

        var status = vRes.status
        var detail = vRes.detail
        val candleTime = vRes.decisionTime

        // Calculate per-level TP1/TP2/TP3 & SL money P&L and TP1-focused realized headline P&L
        val catKey = DefaultRulesEngine.normalizeSlCategory(record.category, record.symbol, record.optionType)
        val prof = DefaultRulesEngine.resolveInstrumentProfile(rules, record.category, record.symbol, record.optionType)
        val symUpper = (record.symbol ?: "").trim().uppercase(Locale.US)
        val symLookupKey = if (catKey == "MCX") "$symUpper:${if (!record.optionType.isNullOrBlank()) "OPT" else "FUT"}" else symUpper
        val symOverride = rules[catKey]?.symbols?.get(symLookupKey)
        val hasExplicitSymLot = symOverride?.lotSize != null && symOverride.lotSize > 0.0

        val effectiveLotSize = when {
            !hasExplicitSymLot && instLotFromMaster > 0.0 -> instLotFromMaster
            prof.lotSize != null && prof.lotSize > 0.0 -> prof.lotSize
            else -> 1.0
        }

        val pnlCurrency = if (catKey == "FOREX" || catKey == "CRYPTO") "USD" else "INR"
        val dirUpper = (record.direction ?: "").trim().uppercase(Locale.US)

        fun calcPoints(entryVal: Double, exitVal: Double): Double =
            if (dirUpper == "SELL") DefaultRulesEngine.roundValue(entryVal - exitVal, 6)
            else DefaultRulesEngine.roundValue(exitVal - entryVal, 6)

        fun calcMoney(entryVal: Double, exitVal: Double): Double {
            val pts = calcPoints(entryVal, exitVal)
            return if (catKey == "CRYPTO" && entryVal > 0.0) {
                DefaultRulesEngine.roundValue(pts * (prof.usdAmount / entryVal), 2)
            } else {
                DefaultRulesEngine.roundValue(pts * (prof.lots * effectiveLotSize), 2)
            }
        }

        fun positionLabelFor(entryVal: Double): String = if (catKey == "CRYPTO" && entryVal > 0.0) {
            val qty = prof.usdAmount / entryVal
            "$${DefaultRulesEngine.formatG(prof.usdAmount)} (${String.format(Locale.US, "%.4g", qty)} qty)"
        } else {
            "${DefaultRulesEngine.formatG(prof.lots)} lot × ${DefaultRulesEngine.formatG(effectiveLotSize)}"
        }

        var highestTpTotalPnl = 0.0
        var tp1RealizedTotal = 0.0
        val enrichedLegs = mutableListOf<LegResult>()
        var enteredCount = 0

        for (lg in vRes.timeline.legs) {
            val ep = if (lg.entryPrice > 0.0) lg.entryPrice else (entryPrice ?: 0.0)
            val legTp1 = targets.getOrNull(0)?.let { if (ep > 0.0) calcMoney(ep, it) else null }
            val legTp2 = targets.getOrNull(1)?.let { if (ep > 0.0) calcMoney(ep, it) else null }
            val legTp3 = targets.getOrNull(2)?.let { if (ep > 0.0) calcMoney(ep, it) else null }
            val legSl = stopLoss?.let { if (ep > 0.0) calcMoney(ep, it) else 0.0 } ?: 0.0

            if (!lg.entered) {
                enrichedLegs += lg.copy(
                    currency = pnlCurrency,
                    positionLabel = if (ep > 0.0) positionLabelFor(ep) else null,
                    tp1Pnl = legTp1,
                    tp2Pnl = legTp2,
                    tp3Pnl = legTp3,
                    highestTpPnl = 0.0,
                    slPnl = legSl
                )
                continue
            }
            enteredCount++
            val pts = lg.pointsPnl
            val legHighestPnl = if (catKey == "CRYPTO" && ep > 0.0) {
                DefaultRulesEngine.roundValue(pts * (prof.usdAmount / ep), 2)
            } else {
                DefaultRulesEngine.roundValue(pts * (prof.lots * effectiveLotSize), 2)
            }
            val legHeadlinePnl = when {
                lg.outcome == "target_hit" && 1 in lg.targetsHit && legTp1 != null -> legTp1
                lg.outcome == "target_hit" -> legHighestPnl
                lg.outcome == "stop_loss_hit" -> legHighestPnl
                else -> 0.0
            }
            highestTpTotalPnl = DefaultRulesEngine.roundValue(highestTpTotalPnl + legHighestPnl, 2)
            tp1RealizedTotal = DefaultRulesEngine.roundValue(tp1RealizedTotal + legHeadlinePnl, 2)
            enrichedLegs += lg.copy(
                moneyPnl = legHeadlinePnl,
                currency = pnlCurrency,
                positionLabel = positionLabelFor(ep),
                tp1Pnl = legTp1,
                tp2Pnl = legTp2,
                tp3Pnl = legTp3,
                highestTpPnl = legHighestPnl,
                slPnl = legSl
            )
        }

        // Compute per-level TP1/TP2/TP3/SL summary across entered legs (or projected from Entry 1 if not yet entered)
        val activeLegsForMatrix = enrichedLegs.filter { it.entered }.ifEmpty {
            val baseEntry = entryPrice ?: 0.0
            if (baseEntry > 0.0) listOf(
                LegResult(1, baseEntry, false, null, "pending", null, null, 0.0, emptyList())
            ) else emptyList()
        }
        val tpHitTimeByNum = mutableMapOf<Int, String>()
        for (ev in vRes.timeline.events) {
            if (ev.type == "tp_hit") {
                val m = Regex("TP(\\d+)", RegexOption.IGNORE_CASE).find(ev.label)
                val num = m?.groupValues?.get(1)?.toIntOrNull()
                if (num != null && num !in tpHitTimeByNum) tpHitTimeByNum[num] = ev.candleTime
            }
        }
        val tpLevels = targets.mapIndexed { idx, tPrice ->
            val tpNum = idx + 1
            val hit = vRes.timeline.maxTpHit >= tpNum || tpNum in tpHitTimeByNum
            val avgPts = if (activeLegsForMatrix.isNotEmpty()) {
                DefaultRulesEngine.roundValue(activeLegsForMatrix.map { calcPoints(it.entryPrice, tPrice) }.average(), 4)
            } else 0.0
            val sumMoney = DefaultRulesEngine.roundValue(
                activeLegsForMatrix.sumOf { if (it.entryPrice > 0.0) calcMoney(it.entryPrice, tPrice) else 0.0 },
                2
            )
            TargetLevelPnl(
                index = tpNum,
                targetPrice = tPrice,
                pointsPnl = avgPts,
                moneyPnl = sumMoney,
                hit = hit,
                hitAt = tpHitTimeByNum[tpNum]
            )
        }
        val tp1PnlAmount = tpLevels.getOrNull(0)?.moneyPnl ?: 0.0
        val tp2PnlAmount = tpLevels.getOrNull(1)?.moneyPnl ?: 0.0
        val tp3PnlAmount = tpLevels.getOrNull(2)?.moneyPnl ?: 0.0
        val slPnlAmount = if (stopLoss != null && activeLegsForMatrix.isNotEmpty()) {
            DefaultRulesEngine.roundValue(
                activeLegsForMatrix.sumOf { if (it.entryPrice > 0.0) calcMoney(it.entryPrice, stopLoss) else 0.0 },
                2
            )
        } else 0.0

        // Headline realized P&L focuses on TP1 (or SL loss if SL hit before TP1)
        val totalPnl = tp1RealizedTotal

        val initialWait = core.getSetting("verification_initial_wait_minutes")?.toIntOrNull() ?: 15
        if (status == "awaiting_market_data") {
            if (fetchError != null) detail = "Awaiting market data ($fetchError)"
            if (runType == "manual" && sigStart.isBefore(Instant.now().minus(Duration.ofMinutes(initialWait.toLong())))) {
                status = "historical_data_missing"
                detail = if (fetchError != null) {
                    "Historical provider fetch could not supply candles: $fetchError"
                } else {
                    "Provider returned 0 candles for $resolvedLabel."
                }
            }
        }

        val enrichedTimeline = vRes.timeline.copy(legs = enrichedLegs)
        val now = TimeUtils.utcNowIso()

        core.updateSignalVerificationStatus(signalId, status, status, now)
        verDao.insertSignalAnalysis(
            SignalAnalysisEntity(signalId = signalId, analyzedAt = now, decision = status, detail = detail, candleTime = candleTime)
        )
        verDao.upsertTradeResult(
            signalId = signalId,
            entryHitAt = enrichedTimeline.entryHitAt,
            targetHitAt = enrichedTimeline.firstTargetHitAt,
            stopLossHitAt = enrichedTimeline.stopLossHitAt,
            eventsJson = JsonUtils.eventsToJson(enrichedTimeline.events),
            result = status,
            detail = detail,
            pnlAmount = totalPnl,
            pnlCurrency = pnlCurrency,
            tradesCount = enteredCount,
            legsJson = JsonUtils.legsToJson(enrichedLegs),
            maxTpHit = enrichedTimeline.maxTpHit,
            allTargetsHit = if (enrichedTimeline.allTargetsHit) 1 else 0,
            closedBySlAfterTp = if (enrichedTimeline.closedBySlAfterTp) 1 else 0,
            tp1PnlAmount = tp1PnlAmount,
            tp2PnlAmount = tp2PnlAmount,
            tp3PnlAmount = tp3PnlAmount,
            highestTpPnlAmount = highestTpTotalPnl,
            slPnlAmount = slPnlAmount,
            tpLevelsJson = JsonUtils.tpLevelsToJson(tpLevels),
            updatedAt = now
        )
        verDao.finishVerificationRun(runId, now, status, detail, candleTime)

        eventBus.emit(AppEvent("signal.changed", signalId, status, detail, now))

        return ProcessSignalOutput(
            signalId = signalId,
            status = status,
            detail = detail,
            candleTime = candleTime,
            pnlAmount = totalPnl,
            pnlCurrency = pnlCurrency,
            tradesCount = enteredCount,
            timeline = enrichedTimeline
        )
    }

    suspend fun processSignalsBatch(signalIds: List<Long>, runType: String = "manual"): List<ProcessSignalOutput> {
        val cleanIds = signalIds.distinct()
        if (cleanIds.isEmpty()) return emptyList()
        val ordered = db.coreDao().getSignalIdsOrderedOldestFirst(cleanIds).toMutableList()
        val found = ordered.toSet()
        for (id in cleanIds) {
            if (id !in found) ordered += id
        }
        return ordered.map { sid -> processSignal(sid, runType) }
    }

    suspend fun reconcileOnStartup() {
        val verDao = db.verificationDao()
        val now = Instant.now()
        val nowIso = now.toString()
        val staleCutoff = now.minus(Duration.ofMinutes(5)).toString()
        val recovered = verDao.recoverStaleProcessingLeases(staleCutoff, nowIso)
        // Promote any legacy manual_required schedules into zero-manual automatic monitoring
        val promoted = verDao.promoteManualRequiredToScheduled(nowIso)
        if (recovered > 0 || promoted > 0) {
            eventBus.emit(AppEvent("schedule.changed", null, "reconciled", "recovered=$recovered, promoted=$promoted", nowIso))
        }
    }

    suspend fun runDueSchedules(): Int {
        val core = db.coreDao()
        val verDao = db.verificationDao()
        val settings = core.getAllSettings().associate { it.key to it.value }
        if ((settings["automatic_verification_enabled"] ?: "true").lowercase(Locale.US) != "true") {
            return 0
        }

        val now = Instant.now()
        val nowIso = now.toString()
        // Ensure zero manual work: promote any legacy manual_required schedules & recover stale leases
        verDao.promoteManualRequiredToScheduled(nowIso)
        verDao.recoverStaleProcessingLeases(now.minus(Duration.ofMinutes(5)).toString(), nowIso)

        val fastPhaseMins = settings["verification_fast_phase_minutes"]?.toLongOrNull() ?: 60L
        val extendedIntervalMins = settings["verification_extended_interval_minutes"]?.toLongOrNull() ?: 60L
        val defaultRepeatMins = settings["verification_repeat_minutes"]?.toLongOrNull() ?: 15L
        val retryMins = settings["verification_retry_minutes"]?.toLongOrNull() ?: 15L

        val due = verDao.getDueSchedules(nowIso)
        var processed = 0
        for (job in due) {
            val claimed = verDao.claimSchedule(job.id, TimeUtils.utcNowIso())
            if (claimed == 0) continue

            val result = processSignal(job.signalId, "automatic", job.id)
            val current = Instant.now()
            val expDt = TimeUtils.parseUtcInstant(job.expiresAt)
            val expired = expDt != null && !expDt.isAfter(current)
            val isDone = result.status in TERMINAL_STATUSES ||
                (result.status == "target_hit" && result.timeline?.allTargetsHit == true) ||
                (result.status == "target_hit" && result.timeline?.closedBySlAfterTp == true) ||
                expired

            val nextStatus: String
            val nextTime: String?
            if (isDone) {
                nextStatus = if (!expired) "completed" else "expired"
                nextTime = null
            } else {
                val startDt = TimeUtils.parseUtcInstant(job.signalStartAt) ?: current
                val firstCheckDt = startDt.plus(Duration.ofMinutes(job.initialWaitMinutes.toLong()))
                val ageMins = Duration.between(startDt, current).toMinutes()
                val fastInterval = if (job.intervalMinutes > 0) job.intervalMinutes.toLong() else defaultRepeatMins
                val waitMins = when {
                    result.status == "awaiting_market_data" -> if (ageMins < fastPhaseMins) retryMins else extendedIntervalMins
                    ageMins < fastPhaseMins -> fastInterval
                    else -> extendedIntervalMins
                }
                val candidateNext = current.plus(Duration.ofMinutes(waitMins))
                val safeNext = if (candidateNext.isBefore(firstCheckDt)) firstCheckDt else candidateNext
                nextStatus = "scheduled"
                nextTime = safeNext.toString()
            }
            val doneNow = TimeUtils.utcNowIso()
            val statusNote = if (result.timeline?.closedBySlAfterTp == true) "target_hit_sl_closed" else result.status
            verDao.completeScheduleCycle(job.id, nextStatus, doneNow, nextTime, statusNote, doneNow)
            eventBus.emit(AppEvent("schedule.changed", job.signalId, nextStatus, statusNote, doneNow))
            processed++
        }
        return processed
    }

    private fun jsonObjectToMap(raw: String): Map<String, Any?> {
        return try {
            val obj = JSONObject(raw)
            val out = linkedMapOf<String, Any?>()
            val k = obj.keys()
            while (k.hasNext()) {
                val key = k.next()
                out[key] = obj.opt(key)
            }
            out
        } catch (_: Exception) {
            emptyMap()
        }
    }

    companion object {
        val TERMINAL_STATUSES = setOf("stop_loss_hit", "ambiguous")
        val INDEX_BFO = setOf("SENSEX", "BANKEX")
        val INDEX_NFO = setOf("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "NIFTYNXT50")
        val MCX_SYMBOLS = setOf(
            "CRUDEOIL", "CRUDEOILM", "NATURALGAS", "NATGASMINI",
            "GOLD", "GOLDM", "GOLDPETAL", "GOLDGUINEA",
            "SILVER", "SILVERM", "SILVERMIC",
            "COPPER", "ZINC", "ALUMINIUM", "LEAD", "NICKEL"
        )
        val FOREX_ROUTED_SYMBOLS = setOf(
            "XAUUSD", "XAGUSD", "EURUSD", "GBPUSD", "USDJPY",
            "AUDUSD", "USDCAD", "USDCHF", "NZDUSD", "USOIL", "UKOIL"
        )
        val SYMBOL_ALIASES = mapOf(
            "NAVINFLOUR" to "NAVINFLUOR",
            "BAJAJFIN" to "BAJAJFINSV",
            "TATAMOTOR" to "TATAMOTORS",
            "HINDUNILV" to "HINDUNILVR",
            "HUL" to "HINDUNILVR",
            "INDUSIND" to "INDUSINDBK",
            "MM" to "M&M",
            "M_M" to "M&M",
            "RIL" to "RELIANCE",
            "AIRTEL" to "BHARTIARTL",
            "SUN" to "SUNPHARMA",
            "ADANI" to "ADANIENT",
            "L&T" to "LT"
        )
    }
}
