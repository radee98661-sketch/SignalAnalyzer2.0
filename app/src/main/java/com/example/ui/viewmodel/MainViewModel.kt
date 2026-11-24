package com.example.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.SignalAnalyzerApp
import com.example.data.local.*
import com.example.data.repository.EnrichedSignal
import com.example.data.repository.OverviewData
import com.example.data.repository.SignalChartData
import com.example.data.repository.SignalEditPayload
import com.example.domain.model.ApiHealthSummary
import com.example.domain.model.DefaultSlCategoryRule
import com.example.domain.model.FilteredChannelAnalyticsRow
import com.example.domain.model.FilteredPnlSummary
import com.example.domain.model.ParsedSignal
import com.example.domain.model.SourceNotificationRow
import com.example.domain.parser.DefaultRulesEngine
import com.example.domain.parser.SignalParser
import com.example.service.NetworkUtils
import com.example.service.ServiceStatus
import com.example.service.SignalProcessingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

data class SignalFilterState(
    val category: String = "",
    val channel: String = "",
    val status: String = "",
    val notificationFilter: String = "primary",
    val datePreset: String = "all",
    val dateFrom: String = "",
    val dateTo: String = "",
    val tpFilter: String = "",
    val pnlBasis: String = "tp1",
    val searchQuery: String = ""
)

data class AnalyticsFilterState(
    val category: String = "",
    val channel: String = "",
    val datePreset: String = "all",
    val dateFrom: String = "",
    val dateTo: String = "",
    val pnlBasis: String = "tp1"
)

data class NotificationFilterState(
    val limit: Int = 50,
    val parsingStatus: String = "",
    val notificationFilter: String = "all",
    val category: String = "",
    val channel: String = ""
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as SignalAnalyzerApp
    val container = app.container
    private val repo = container.repository

    val serviceStatus: StateFlow<ServiceStatus> = SignalProcessingService.status

    private val _overview = MutableStateFlow<OverviewData?>(null)
    val overview: StateFlow<OverviewData?> = _overview.asStateFlow()

    private val _signals = MutableStateFlow<List<EnrichedSignal>>(emptyList())
    val signals: StateFlow<List<EnrichedSignal>> = _signals.asStateFlow()

    private val _signalFilter = MutableStateFlow(SignalFilterState())
    val signalFilter: StateFlow<SignalFilterState> = _signalFilter.asStateFlow()

    private val _categories = MutableStateFlow<List<String>>(emptyList())
    val categories: StateFlow<List<String>> = _categories.asStateFlow()

    private val _channels = MutableStateFlow<List<String>>(emptyList())
    val channels: StateFlow<List<String>> = _channels.asStateFlow()

    private val _selectedSignalIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedSignalIds: StateFlow<Set<Long>> = _selectedSignalIds.asStateFlow()

    private val _charts = MutableStateFlow<Map<Long, SignalChartData>>(emptyMap())
    val charts: StateFlow<Map<Long, SignalChartData>> = _charts.asStateFlow()

    private val _notifications = MutableStateFlow<List<NotificationJoinedRow>>(emptyList())
    val notifications: StateFlow<List<NotificationJoinedRow>> = _notifications.asStateFlow()

    private val _notifFilter = MutableStateFlow(NotificationFilterState())
    val notifFilter: StateFlow<NotificationFilterState> = _notifFilter.asStateFlow()

    private val _syncLogs = MutableStateFlow<List<SynchronizationLogEntity>>(emptyList())
    val syncLogs: StateFlow<List<SynchronizationLogEntity>> = _syncLogs.asStateFlow()

    private val _integrations = MutableStateFlow<List<IntegrationEntity>>(emptyList())
    val integrations: StateFlow<List<IntegrationEntity>> = _integrations.asStateFlow()

    private val _analytics = MutableStateFlow<List<ChannelAnalyticsRow>>(emptyList())
    val analytics: StateFlow<List<ChannelAnalyticsRow>> = _analytics.asStateFlow()

    private val _analyticsFilter = MutableStateFlow(AnalyticsFilterState())
    val analyticsFilter: StateFlow<AnalyticsFilterState> = _analyticsFilter.asStateFlow()

    private val _filteredAnalytics = MutableStateFlow<List<FilteredChannelAnalyticsRow>>(emptyList())
    val filteredAnalytics: StateFlow<List<FilteredChannelAnalyticsRow>> = _filteredAnalytics.asStateFlow()

    private val _analyticsChannels = MutableStateFlow<List<String>>(emptyList())
    val analyticsChannels: StateFlow<List<String>> = _analyticsChannels.asStateFlow()

    private val _filteredPnlSummary = MutableStateFlow(FilteredPnlSummary())
    val filteredPnlSummary: StateFlow<FilteredPnlSummary> = _filteredPnlSummary.asStateFlow()

    private val _apiHealth = MutableStateFlow(ApiHealthSummary())
    val apiHealth: StateFlow<ApiHealthSummary> = _apiHealth.asStateFlow()

    private val _marketCoverage = MutableStateFlow<List<MarketCoverageRow>>(emptyList())
    val marketCoverage: StateFlow<List<MarketCoverageRow>> = _marketCoverage.asStateFlow()

    private val _twelveUsage = MutableStateFlow<Map<String, Double>>(emptyMap())
    val twelveUsage: StateFlow<Map<String, Double>> = _twelveUsage.asStateFlow()

    private val _instrumentResults = MutableStateFlow<List<InstrumentEntity>>(emptyList())
    val instrumentResults: StateFlow<List<InstrumentEntity>> = _instrumentResults.asStateFlow()

    private val _settings = MutableStateFlow<Map<String, String>>(emptyMap())
    val settings: StateFlow<Map<String, String>> = _settings.asStateFlow()

    private val _defaultSlRules = MutableStateFlow<Map<String, DefaultSlCategoryRule>>(emptyMap())
    val defaultSlRules: StateFlow<Map<String, DefaultSlCategoryRule>> = _defaultSlRules.asStateFlow()

    private val _systemLogs = MutableStateFlow<List<SystemLogEntity>>(emptyList())
    val systemLogs: StateFlow<List<SystemLogEntity>> = _systemLogs.asStateFlow()

    private val _parsePreview = MutableStateFlow<List<ParsedSignal>>(emptyList())
    val parsePreview: StateFlow<List<ParsedSignal>> = _parsePreview.asStateFlow()

    private val _lanToken = MutableStateFlow(container.lanAuthManager.currentBearerToken())
    val lanToken: StateFlow<String> = _lanToken.asStateFlow()

    private val _lanIp = MutableStateFlow(NetworkUtils.getLocalLanIpv4())
    val lanIp: StateFlow<String> = _lanIp.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _legacyImportReport = MutableStateFlow<LegacyImportReport?>(null)
    val legacyImportReport: StateFlow<LegacyImportReport?> = _legacyImportReport.asStateFlow()

    init {
        refreshAll()
        observeEvents()
    }

    private fun observeEvents() {
        viewModelScope.launch {
            container.eventBus.events.collect { ev ->
                delay(200)
                when (ev.type) {
                    "signal.changed", "schedule.changed" -> {
                        refreshOverview()
                        refreshSignals()
                        refreshAnalytics()
                    }
                    "sync.completed" -> {
                        refreshOverview()
                        refreshNotifications()
                        refreshSignals()
                        refreshApiHealth()
                    }
                    "market.cache.changed" -> {
                        refreshMarketData()
                        refreshApiHealth()
                    }
                    "api.health.changed" -> refreshApiHealth()
                    "server.status" -> {
                        refreshSettings()
                        refreshIntegrations()
                    }
                }
            }
        }
    }

    fun clearStatusMessage() { _statusMessage.value = null }
    fun setStatusMessage(msg: String) { _statusMessage.value = msg }

    fun refreshAll() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { container.db.initializeDefaults() }
            refreshOverview()
            refreshSignals()
            refreshNotifications()
            refreshIntegrations()
            refreshAnalytics()
            refreshMarketData()
            refreshApiHealth()
            refreshSettings()
            refreshSystemLogs()
            _lanIp.value = NetworkUtils.getLocalLanIpv4()
        }
    }

    fun refreshOverview() = viewModelScope.launch(Dispatchers.IO) {
        _overview.value = repo.getOverview()
    }

    private fun resolveDateWindow(preset: String, customFrom: String, customTo: String): Pair<String, String> {
        val today = LocalDate.now()
        return when (preset) {
            "today" -> today.toString() to today.toString()
            "7d" -> today.minusDays(6).toString() to today.toString()
            "30d" -> today.minusDays(29).toString() to today.toString()
            "custom" -> customFrom to customTo
            else -> "" to ""
        }
    }

    fun updateSignalFilter(transform: (SignalFilterState) -> SignalFilterState) {
        _signalFilter.value = transform(_signalFilter.value)
        refreshSignals()
    }

    fun refreshSignals() = viewModelScope.launch(Dispatchers.IO) {
        val f = _signalFilter.value
        val (cats, chans) = repo.getFilters(f.category)
        _categories.value = cats
        _channels.value = chans
        val (dFrom, dTo) = resolveDateWindow(f.datePreset, f.dateFrom, f.dateTo)
        val rawList = repo.getSignals(
            category = f.category,
            channel = f.channel,
            symbol = "",
            status = f.status,
            notificationFilter = f.notificationFilter,
            dateFrom = dFrom,
            dateTo = dTo,
            tpFilter = f.tpFilter
        )
        val q = f.searchQuery.trim().lowercase()
        val list = if (q.isEmpty()) {
            rawList
        } else {
            rawList.filter { sig ->
                val r = sig.row
                (r.symbol?.lowercase()?.contains(q) == true) ||
                    (r.channel?.lowercase()?.contains(q) == true) ||
                    (r.category?.lowercase()?.contains(q) == true) ||
                    (r.message?.lowercase()?.contains(q) == true) ||
                    ("#${r.id}".contains(q))
            }
        }
        _signals.value = list
        _filteredPnlSummary.value = repo.computeFilteredPnlSummary(list, f.pnlBasis)
    }

    fun toggleSelectSignal(id: Long) {
        val s = _selectedSignalIds.value.toMutableSet()
        if (!s.add(id)) s.remove(id)
        _selectedSignalIds.value = s
    }

    fun selectAllFiltered() {
        _selectedSignalIds.value = _signals.value.map { it.row.id }.toSet()
    }

    fun clearSelection() {
        _selectedSignalIds.value = emptySet()
    }

    fun loadChart(signalId: Long, forceVerify: Boolean = false) = viewModelScope.launch(Dispatchers.IO) {
        _isBusy.value = true
        try {
            if (forceVerify) {
                val res = repo.verificationService.processSignal(signalId, "manual")
                _statusMessage.value = "Signal #$signalId → ${res.status}: ${res.detail}"
            }
            val data = repo.getSignalChart(signalId)
            if (data != null) {
                _charts.value = _charts.value + (signalId to data)
            }
            if (forceVerify) {
                refreshSignals()
                refreshOverview()
            }
        } catch (e: Exception) {
            _statusMessage.value = "Chart error: ${e.message}"
        } finally {
            _isBusy.value = false
        }
    }

    fun processSelectedSignals() = viewModelScope.launch(Dispatchers.IO) {
        val ids = _selectedSignalIds.value.toList()
        if (ids.isEmpty()) {
            _statusMessage.value = "Select at least one signal to process."
            return@launch
        }
        _isBusy.value = true
        try {
            val res = repo.verificationService.processSignalsBatch(ids, "manual")
            _selectedSignalIds.value = emptySet()
            _statusMessage.value = "Processed ${res.size} signal(s)."
            refreshSignals()
            refreshOverview()
        } catch (e: Exception) {
            _statusMessage.value = "Process error: ${e.message}"
        } finally {
            _isBusy.value = false
        }
    }

    fun reparseSelectedSignals() = viewModelScope.launch(Dispatchers.IO) {
        val ids = _selectedSignalIds.value.toList()
        if (ids.isEmpty()) return@launch
        _isBusy.value = true
        try {
            ids.forEach { repo.reparseSignal(it, "batch_reparse") }
            _statusMessage.value = "Re-parsed ${ids.size} signal(s)."
            refreshSignals()
        } finally {
            _isBusy.value = false
        }
    }

    fun deleteSelectedSignals() = viewModelScope.launch(Dispatchers.IO) {
        val ids = _selectedSignalIds.value.toList()
        if (ids.isEmpty()) return@launch
        val deleted = repo.deleteSignals(ids)
        _selectedSignalIds.value = emptySet()
        _statusMessage.value = "Deleted $deleted signal(s)."
        refreshSignals()
        refreshOverview()
    }

    fun deleteSignal(id: Long) = viewModelScope.launch(Dispatchers.IO) {
        repo.deleteSignal(id)
        _selectedSignalIds.value = _selectedSignalIds.value - id
        _statusMessage.value = "Deleted signal #$id."
        refreshSignals()
        refreshOverview()
    }

    fun reparseSingleSignal(id: Long) = viewModelScope.launch(Dispatchers.IO) {
        repo.reparseSignal(id, "manual_reparse")
        _statusMessage.value = "Re-parsed signal #$id."
        refreshSignals()
    }

    fun saveManualSignalEdit(id: Long, edit: SignalEditPayload, verifyImmediately: Boolean = false) = viewModelScope.launch(Dispatchers.IO) {
        _isBusy.value = true
        try {
            repo.updateSignal(id, edit)
            if (verifyImmediately) {
                val res = repo.verificationService.processSignal(id, "manual")
                val chart = repo.getSignalChart(id)
                if (chart != null) {
                    _charts.value = _charts.value + (id to chart)
                }
                _statusMessage.value = "Saved & verified #$id → ${res.status}"
            } else {
                _statusMessage.value = "Saved manual correction for signal #$id."
            }
            refreshSignals()
            refreshOverview()
            refreshNotifications()
        } catch (e: Exception) {
            _statusMessage.value = "Save/verify error for #$id: ${e.message}"
        } finally {
            _isBusy.value = false
        }
    }

    fun previewParse(message: String, category: String) = viewModelScope.launch(Dispatchers.IO) {
        val rules = repo.getDefaultSlRules()
        _parsePreview.value = SignalParser.parseSignals(message, category, slRules = rules)
    }

    fun importManualSignal(channel: String, category: String, message: String) = viewModelScope.launch(Dispatchers.IO) {
        val now = com.example.util.TimeUtils.utcNowIso()
        val row = SourceNotificationRow(
            receivedAt = now,
            notificationTime = now,
            sourceApp = "ManualEntry",
            packageName = app.packageName,
            channel = channel.ifBlank { "Manual" },
            category = category.ifBlank { "Crypto" },
            title = channel.ifBlank { "Manual" },
            message = message,
            notificationId = "manual-${System.currentTimeMillis()}",
            deliveryId = ""
        )
        val (_, st) = repo.ingestionService.ingest(row)
        _statusMessage.value = if (st == "imported") "Signal imported and parsed." else "Duplicate notification skipped."
        refreshOverview()
        refreshSignals()
        refreshNotifications()
    }

    fun updateNotificationFilter(transform: (NotificationFilterState) -> NotificationFilterState) {
        _notifFilter.value = transform(_notifFilter.value)
        refreshNotifications()
    }

    fun refreshNotifications() = viewModelScope.launch(Dispatchers.IO) {
        val f = _notifFilter.value
        _notifications.value = repo.getNotifications(
            limit = f.limit,
            category = f.category,
            channel = f.channel,
            parsingStatus = f.parsingStatus,
            notificationFilter = f.notificationFilter
        )
        _syncLogs.value = repo.db.coreDao().getSyncLogs()
    }

    fun refreshIntegrations() = viewModelScope.launch(Dispatchers.IO) {
        _integrations.value = repo.db.coreDao().getAllIntegrations()
    }

    fun saveIntegration(provider: String, config: JSONObject, secrets: Map<String, String>) = viewModelScope.launch(Dispatchers.IO) {
        try {
            repo.saveIntegration(provider, config.toString(), secrets)
            _statusMessage.value = "Saved $provider configuration (secrets encrypted in Keystore)."
            refreshIntegrations()
        } catch (e: Exception) {
            _statusMessage.value = "Error: ${e.message}"
        }
    }

    fun syncGoogleSheetsNow() = viewModelScope.launch(Dispatchers.IO) {
        _isBusy.value = true
        try {
            val res = repo.syncClient.syncGoogleSheets()
            _statusMessage.value = "Sync ${res.status}: ${res.imported} imported, ${res.duplicates} dup, ${res.failed} failed — ${res.detail}"
            refreshOverview()
            refreshSignals()
            refreshNotifications()
        } finally {
            _isBusy.value = false
        }
    }

    fun testBinance() = viewModelScope.launch(Dispatchers.IO) {
        _isBusy.value = true
        try {
            val (cnt, msg) = repo.testBinance()
            _statusMessage.value = "Binance OK ($cnt candles): $msg"
        } catch (e: Exception) {
            _statusMessage.value = "Binance error: ${e.message}"
        } finally {
            _isBusy.value = false
        }
    }

    fun testAngelOne() = viewModelScope.launch(Dispatchers.IO) {
        _isBusy.value = true
        try {
            val at = repo.testAngelOne()
            _statusMessage.value = "Angel One authenticated at $at"
            refreshIntegrations()
        } catch (e: Exception) {
            _statusMessage.value = "Angel One error: ${e.message}"
        } finally {
            _isBusy.value = false
        }
    }

    fun refreshAngelMaster() = viewModelScope.launch(Dispatchers.IO) {
        _isBusy.value = true
        try {
            val count = repo.refreshAngelInstrumentMaster()
            _statusMessage.value = "Angel One master refreshed: $count contracts."
        } catch (e: Exception) {
            _statusMessage.value = "Master refresh error: ${e.message}"
        } finally {
            _isBusy.value = false
        }
    }

    fun updateAnalyticsFilter(transform: (AnalyticsFilterState) -> AnalyticsFilterState) {
        _analyticsFilter.value = transform(_analyticsFilter.value)
        refreshAnalytics()
    }

    fun refreshAnalytics() = viewModelScope.launch(Dispatchers.IO) {
        val f = _analyticsFilter.value
        val (_, chans) = repo.getFilters(f.category)
        _analyticsChannels.value = chans
        val (dFrom, dTo) = resolveDateWindow(f.datePreset, f.dateFrom, f.dateTo)
        _analytics.value = repo.getChannelAnalytics()
        _filteredAnalytics.value = repo.getFilteredChannelAnalytics(
            category = f.category,
            channel = f.channel,
            dateFrom = dFrom,
            dateTo = dTo,
            pnlBasis = f.pnlBasis
        )
    }

    fun refreshApiHealth() = viewModelScope.launch(Dispatchers.IO) {
        _apiHealth.value = repo.getApiHealthSummary()
    }

    fun refreshMarketData() = viewModelScope.launch(Dispatchers.IO) {
        _marketCoverage.value = repo.db.marketDao().getMarketCoverage()
        _twelveUsage.value = repo.getTwelveDataUsage()
        _apiHealth.value = repo.getApiHealthSummary()
    }

    fun downloadCandles(provider: String, symbol: String, exchange: String, interval: String, startIso: String, endIso: String) =
        viewModelScope.launch(Dispatchers.IO) {
            _isBusy.value = true
            try {
                when (provider) {
                    "binance" -> {
                        val (f, s) = repo.fetchManualBinanceCandles(symbol, interval, startIso, endIso)
                        _statusMessage.value = "Binance: fetched $f candles, stored $s new."
                    }
                    "twelve_data" -> {
                        val (f, s, cr) = repo.fetchManualTwelveDataCandles(symbol, interval, startIso, endIso)
                        _statusMessage.value = "Twelve Data: fetched $f, stored $s ($cr credits)."
                    }
                    "angel_one" -> {
                        val (f, s) = repo.fetchManualAngelCandles(exchange, symbol, interval, startIso, endIso)
                        _statusMessage.value = "Angel One: fetched $f candles, stored $s new."
                    }
                }
                refreshMarketData()
            } catch (e: Exception) {
                _statusMessage.value = "Download failed: ${e.message}"
            } finally {
                _isBusy.value = false
            }
        }

    fun deleteCache(provider: String, symbol: String) = viewModelScope.launch(Dispatchers.IO) {
        val del = repo.deleteMarketCache(provider, symbol)
        _statusMessage.value = "Deleted $del cached candles for $provider:$symbol."
        refreshMarketData()
    }

    fun searchInstruments(q: String, exchange: String) = viewModelScope.launch(Dispatchers.IO) {
        _instrumentResults.value = repo.searchAngelInstruments(q, exchange)
    }

    fun refreshSettings() = viewModelScope.launch(Dispatchers.IO) {
        _settings.value = repo.getSettings()
        _defaultSlRules.value = repo.getDefaultSlRules()
    }

    fun saveSettings(values: Map<String, String>) = viewModelScope.launch(Dispatchers.IO) {
        repo.saveSettings(values)
        _settings.value = repo.getSettings()
        _statusMessage.value = "Settings saved."
    }

    fun saveDefaultSlRules(rules: Map<String, DefaultSlCategoryRule>) = viewModelScope.launch(Dispatchers.IO) {
        repo.saveDefaultSlRules(rules)
        _defaultSlRules.value = repo.getDefaultSlRules()
        _statusMessage.value = "Default SL, TP1–TP3, and position sizing rules saved."
    }

    fun applyDefaultSlMissing() = viewModelScope.launch(Dispatchers.IO) {
        val (checked, updated) = repo.applyDefaultRulesToMissing()
        _statusMessage.value = "Checked $checked signals — updated $updated with defaults."
        refreshSignals()
    }

    fun refreshSystemLogs() = viewModelScope.launch(Dispatchers.IO) {
        _systemLogs.value = repo.db.coreDao().getRecentSystemLogs()
    }

    fun exportCsvToCache(kind: String, onReady: (File) -> Unit) = viewModelScope.launch(Dispatchers.IO) {
        try {
            val (filename, csv) = repo.exportCsv(kind)
            val dir = File(app.cacheDir, "exports").apply { mkdirs() }
            val file = File(dir, filename)
            file.writeText(csv)
            withContext(Dispatchers.Main) { onReady(file) }
            _statusMessage.value = "Exported $filename"
        } catch (e: Exception) {
            _statusMessage.value = "Export error: ${e.message}"
        }
    }

    fun createBackup() = viewModelScope.launch(Dispatchers.IO) {
        try {
            val (_, detail) = repo.createLocalBackup()
            _statusMessage.value = detail
            refreshSystemLogs()
        } catch (e: Exception) {
            _statusMessage.value = "Backup failed: ${e.message}"
        }
    }

    fun importLegacySqlite(uri: Uri) = viewModelScope.launch(Dispatchers.IO) {
        _isBusy.value = true
        try {
            val report = container.legacyImporter.importFromUri(uri)
            _legacyImportReport.value = report
            _statusMessage.value = report.message
            if (report.success) refreshAll()
        } finally {
            _isBusy.value = false
        }
    }

    fun updateServiceState(dedicated: Boolean, lanEnabled: Boolean, port: Int) {
        val safePort = port.coerceIn(1024, 65535)
        viewModelScope.launch(Dispatchers.IO) {
            repo.saveSettings(mapOf("lan_port" to safePort.toString()))
        }
        _lanIp.value = NetworkUtils.getLocalLanIpv4()
        SignalProcessingService.startOrUpdate(app, dedicated, lanEnabled, safePort)
    }

    fun rotateLanToken() {
        _lanToken.value = container.lanAuthManager.rotateBearerToken()
        _statusMessage.value = "Access token rotated. Active LAN browser sessions must re-pair."
    }
}
