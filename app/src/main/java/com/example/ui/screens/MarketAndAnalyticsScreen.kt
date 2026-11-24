package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.components.SectionCard
import com.example.ui.components.StatusPill
import com.example.ui.components.formatPnl
import com.example.ui.components.formatShortTimestamp
import com.example.ui.theme.AmberContainer
import com.example.ui.theme.AmberWarn
import com.example.ui.theme.CoralContainer
import com.example.ui.theme.CoralRed
import com.example.ui.theme.EmeraldAccent
import com.example.ui.theme.EmeraldContainer
import com.example.ui.theme.MonoFontFamily
import com.example.ui.theme.NavyBorder
import com.example.ui.theme.NavyCardElevated
import com.example.ui.theme.NavyInput
import com.example.ui.theme.SignalBlue
import com.example.ui.theme.TextSecondary
import com.example.ui.viewmodel.MainViewModel
import java.time.Instant
import java.time.temporal.ChronoUnit

@Composable
fun MarketAndAnalyticsScreen(viewModel: MainViewModel) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Channel Analytics", "Market Data & API Health")

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTab) {
            tabs.forEachIndexed { idx, title ->
                Tab(
                    selected = selectedTab == idx,
                    onClick = { selectedTab = idx },
                    text = { Text(title) },
                    modifier = Modifier.testTag("market_tab_$idx")
                )
            }
        }
        if (selectedTab == 0) {
            AnalyticsTab(viewModel)
        } else {
            MarketDataTab(viewModel)
        }
    }
}

@Composable
private fun AnalyticsTab(viewModel: MainViewModel) {
    val rows by viewModel.filteredAnalytics.collectAsStateWithLifecycle()
    val filter by viewModel.analyticsFilter.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val channels by viewModel.analyticsChannels.collectAsStateWithLifecycle()

    val basisLabel = when (filter.pnlBasis) {
        "tp2" -> "TP2"
        "tp3" -> "TP3"
        "highest_tp" -> "Highest TP"
        else -> "TP1"
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SectionCard(
                title = "Channel Analytics & Filter-Driven P&L",
                subtitle = "Filter by category, channel, date window, and P&L basis (TP1 default, TP2, TP3, Highest TP).",
                action = {
                    FilledTonalButton(onClick = { viewModel.refreshAnalytics() }, modifier = Modifier.testTag("analytics_refresh")) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh analytics", modifier = Modifier.size(16.dp))
                    }
                }
            ) {
                // Category chips
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = filter.category.isEmpty(),
                        onClick = { viewModel.updateAnalyticsFilter { it.copy(category = "", channel = "") } },
                        label = { Text("All Categories") }
                    )
                    categories.forEach { cat ->
                        FilterChip(
                            selected = filter.category == cat,
                            onClick = {
                                viewModel.updateAnalyticsFilter {
                                    it.copy(category = if (it.category == cat) "" else cat, channel = "")
                                }
                            },
                            label = { Text(cat) }
                        )
                    }
                }

                // Channel chips
                if (channels.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChip(
                            selected = filter.channel.isEmpty(),
                            onClick = { viewModel.updateAnalyticsFilter { it.copy(channel = "") } },
                            label = { Text("All Channels") }
                        )
                        channels.forEach { ch ->
                            FilterChip(
                                selected = filter.channel == ch,
                                onClick = {
                                    viewModel.updateAnalyticsFilter {
                                        it.copy(channel = if (it.channel == ch) "" else ch)
                                    }
                                },
                                label = { Text(ch) }
                            )
                        }
                    }
                }

                // Date window + P&L Basis chips
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("all" to "All Time", "today" to "Today", "7d" to "7d", "30d" to "30d").forEach { (k, lbl) ->
                        FilterChip(
                            selected = filter.datePreset == k,
                            onClick = { viewModel.updateAnalyticsFilter { it.copy(datePreset = k) } },
                            label = { Text(lbl) },
                            modifier = Modifier.testTag("analytics_date_$k")
                        )
                    }
                    listOf("tp1" to "Basis: TP1", "tp2" to "Basis: TP2", "tp3" to "Basis: TP3", "highest_tp" to "Basis: Best").forEach { (k, lbl) ->
                        FilterChip(
                            selected = filter.pnlBasis == k,
                            onClick = { viewModel.updateAnalyticsFilter { it.copy(pnlBasis = k) } },
                            label = { Text(lbl) },
                            modifier = Modifier.testTag("analytics_basis_$k")
                        )
                    }
                }

                if (rows.isEmpty()) {
                    Text("No channel analytics match the selected filters.", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                }
            }
        }

        items(rows, key = { it.channel }) { ch ->
            SectionCard(
                title = ch.channel,
                action = {
                    StatusPill(
                        text = "Win ${ch.winRate}%",
                        bg = if (ch.winRate >= 50.0) EmeraldContainer else AmberContainer,
                        fg = if (ch.winRate >= 50.0) EmeraldAccent else AmberWarn
                    )
                }
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Signals: ${ch.total} (Today: ${ch.todayTotal})", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    Text("Triggered: ${ch.tradesTriggered}", style = MaterialTheme.typography.labelSmall, color = SignalBlue)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        "TP1: ${ch.tp1Hits}  •  TP2: ${ch.tp2Hits}  •  TP3: ${ch.tp3Hits}  •  All TPs: ${ch.allTpHits}",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                        color = EmeraldAccent
                    )
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("TP→SL: ${ch.tpThenSlHits} • SL Only: ${ch.stops} • Pending: ${ch.pending}", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    Text("Today TP:${ch.todayTp} SL:${ch.todaySl}", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                }
                HorizontalDivider(color = NavyBorder)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("$basisLabel P&L (INR)", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                        Text(
                            formatPnl(ch.pnlInr, "INR"),
                            style = MaterialTheme.typography.titleMedium.copy(fontFamily = MonoFontFamily, fontWeight = FontWeight.Bold),
                            color = if (ch.pnlInr >= 0.0) EmeraldAccent else CoralRed
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("$basisLabel P&L (USD)", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                        Text(
                            formatPnl(ch.pnlUsd, "USD"),
                            style = MaterialTheme.typography.titleMedium.copy(fontFamily = MonoFontFamily, fontWeight = FontWeight.Bold),
                            color = if (ch.pnlUsd >= 0.0) EmeraldAccent else CoralRed
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MarketDataTab(viewModel: MainViewModel) {
    val coverage by viewModel.marketCoverage.collectAsStateWithLifecycle()
    val usage by viewModel.twelveUsage.collectAsStateWithLifecycle()
    val apiHealth by viewModel.apiHealth.collectAsStateWithLifecycle()
    val instruments by viewModel.instrumentResults.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()

    var provider by remember { mutableStateOf("binance") }
    var symbol by remember { mutableStateOf("BTCUSDT") }
    var exchange by remember { mutableStateOf("NFO") }
    var interval by remember { mutableStateOf("1m") }
    var hoursBack by remember { mutableStateOf("2") }

    var searchQ by remember { mutableStateOf("") }
    var searchExch by remember { mutableStateOf("NFO") }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Provider API Health & Rate-Limit Diagnostics
        item {
            SectionCard(
                title = "Provider API Health & Rate Limits",
                subtitle = "Live 24h status, rate-limit alerts, quota meters, and recent API errors.",
                action = {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        val (ovBg, ovFg) = when (apiHealth.overallStatus) {
                            "error" -> CoralContainer to CoralRed
                            "degraded" -> AmberContainer to AmberWarn
                            else -> EmeraldContainer to EmeraldAccent
                        }
                        StatusPill(apiHealth.overallStatus.uppercase(), ovBg, ovFg)
                        FilledTonalButton(
                            onClick = { viewModel.refreshApiHealth() },
                            modifier = Modifier.testTag("api_health_refresh")
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh API health", modifier = Modifier.size(16.dp))
                        }
                    }
                }
            ) {
                apiHealth.providers.forEachIndexed { idx, p ->
                    if (idx > 0) HorizontalDivider(color = NavyBorder)
                    val (pBg, pFg, pLbl) = when (p.status) {
                        "rate_limited" -> Triple(CoralContainer, CoralRed, "RATE LIMITED")
                        "error" -> Triple(CoralContainer, CoralRed, "ERROR")
                        "degraded" -> Triple(AmberContainer, AmberWarn, "DEGRADED")
                        "unconfigured" -> Triple(NavyCardElevated, TextSecondary, "UNCONFIGURED")
                        else -> Triple(EmeraldContainer, EmeraldAccent, "HEALTHY")
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(p.displayName, style = MaterialTheme.typography.titleSmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (p.activeSlot == "backup") {
                                    StatusPill("BACKUP ACTIVE", AmberContainer, AmberWarn)
                                } else if (p.backupConfigured) {
                                    StatusPill("BACKUP READY", EmeraldContainer, EmeraldAccent)
                                }
                                StatusPill(pLbl, pBg, pFg)
                            }
                        }
                        Text(
                            "24h Calls: ${p.calls24h} • Errors: ${p.errors24h} • Rate/Quota Hits: ${p.rateLimitHits24h}",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                            color = TextSecondary
                        )
                        if (!p.failoverNote.isNullOrBlank()) {
                            Text(
                                p.failoverNote,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (p.activeSlot == "backup") AmberWarn else EmeraldAccent
                            )
                        }
                        if (!p.lastErrorMessage.isNullOrBlank()) {
                            Text(
                                "Last issue (${p.lastErrorCategory ?: "error"}${p.lastHttpCode?.let { " HTTP $it" } ?: ""} • ${formatShortTimestamp(p.lastErrorAt)}): ${p.lastErrorMessage}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (p.status in setOf("error", "rate_limited")) CoralRed else AmberWarn
                            )
                        }
                    }
                }

                // Twelve Data quota bars
                if (usage.isNotEmpty()) {
                    HorizontalDivider(color = NavyBorder)
                    val minUsed = usage["minute_used"] ?: 0.0
                    val minAllow = (usage["minute_allowance"] ?: 8.0).coerceAtLeast(1.0)
                    val dayUsed = usage["daily_used"] ?: 0.0
                    val dayAllow = (usage["daily_allowance"] ?: 800.0).coerceAtLeast(1.0)
                    val minPct = (minUsed / minAllow).toFloat().coerceIn(0f, 1f)
                    val dayPct = (dayUsed / dayAllow).toFloat().coerceIn(0f, 1f)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "Twelve Data Minute Quota: ${minUsed.toInt()} / ${minAllow.toInt()} credits",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (minPct >= 0.9f) CoralRed else TextSecondary
                        )
                        LinearProgressIndicator(
                            progress = { minPct },
                            modifier = Modifier.fillMaxWidth(),
                            color = if (minPct >= 0.9f) CoralRed else if (minPct >= 0.7f) AmberWarn else EmeraldAccent
                        )
                        Text(
                            "Twelve Data Daily Quota: ${dayUsed.toInt()} / ${dayAllow.toInt()} credits",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (dayPct >= 0.9f) CoralRed else TextSecondary
                        )
                        LinearProgressIndicator(
                            progress = { dayPct },
                            modifier = Modifier.fillMaxWidth(),
                            color = if (dayPct >= 0.9f) CoralRed else if (dayPct >= 0.75f) AmberWarn else SignalBlue
                        )
                    }
                }

                // Recent API Incidents
                if (apiHealth.recentIncidents.isNotEmpty()) {
                    HorizontalDivider(color = NavyBorder)
                    Text("Recent API Issues (${apiHealth.recentIncidents.size})", style = MaterialTheme.typography.titleSmall, color = AmberWarn)
                    apiHealth.recentIncidents.take(8).forEach { inc ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(NavyInput)
                                .padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(
                                    "${inc.provider.uppercase()} • ${inc.errorCategory}${inc.httpCode?.let { " ($it)" } ?: ""}",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = if (inc.errorCategory in setOf("rate_limited", "quota_exceeded", "auth_error")) CoralRed else AmberWarn
                                )
                                Text(formatShortTimestamp(inc.requestedAt), style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                            }
                            Text(
                                inc.error ?: inc.endpoint,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }

        item {
            SectionCard(
                title = "Download Historical Candles",
                subtitle = "Fetch OHLC bars directly into the local Room cache."
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("binance" to "Binance", "twelve_data" to "Twelve Data", "angel_one" to "Angel One").forEach { (id, lbl) ->
                        FilterChip(
                            selected = provider == id,
                            onClick = {
                                provider = id
                                when (id) {
                                    "binance" -> { symbol = "BTCUSDT"; interval = "1m" }
                                    "twelve_data" -> { symbol = "XAU/USD"; interval = "1min" }
                                    "angel_one" -> { symbol = ""; interval = "ONE_MINUTE"; exchange = "NFO" }
                                }
                            },
                            label = { Text(lbl) },
                            modifier = Modifier.testTag("market_provider_$id")
                        )
                    }
                }

                OutlinedTextField(
                    value = symbol,
                    onValueChange = { symbol = it },
                    label = { Text(if (provider == "angel_one") "Contract Token (from search below)" else "Symbol (e.g. BTCUSDT or XAU/USD)") },
                    modifier = Modifier.fillMaxWidth().testTag("market_symbol_input"),
                    singleLine = true
                )
                if (provider == "angel_one") {
                    OutlinedTextField(
                        value = exchange,
                        onValueChange = { exchange = it.uppercase() },
                        label = { Text("Exchange Segment (NFO, MCX, BFO, NSE)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = interval,
                        onValueChange = { interval = it },
                        label = { Text("Interval") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = hoursBack,
                        onValueChange = { hoursBack = it },
                        label = { Text("Hours Back") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }
                Button(
                    onClick = {
                        val end = Instant.now()
                        val hrs = (hoursBack.toLongOrNull() ?: 2L).coerceIn(1L, 168L)
                        val start = end.minus(hrs, ChronoUnit.HOURS)
                        viewModel.downloadCandles(provider, symbol.trim(), exchange.trim(), interval.trim(), start.toString(), end.toString())
                    },
                    enabled = !isBusy && symbol.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().testTag("market_download_button")
                ) {
                    Icon(Icons.Default.Download, contentDescription = "Download candles", modifier = Modifier.size(18.dp))
                    Text(" Download Range")
                }

                if (usage.isNotEmpty()) {
                    Text(
                        "Twelve Data Credits — Today: ${usage["daily_used"]?.toInt() ?: 0}/${usage["daily_allowance"]?.toInt() ?: 800} • Minute: ${usage["minute_used"]?.toInt() ?: 0}/${usage["minute_allowance"]?.toInt() ?: 8}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
            }
        }

        item {
            SectionCard(
                title = "Local Candle Cache Coverage (${coverage.size})",
                action = {
                    FilledTonalButton(onClick = { viewModel.refreshMarketData() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh coverage", modifier = Modifier.size(16.dp))
                    }
                }
            ) {
                if (coverage.isEmpty()) {
                    Text("No cached candles yet.", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                } else {
                    coverage.forEachIndexed { idx, row ->
                        if (idx > 0) HorizontalDivider(color = NavyBorder)
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("${row.provider} • ${row.symbol} (${row.interval})", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "${row.candles} bars • ${formatShortTimestamp(row.earliest)} → ${formatShortTimestamp(row.latest)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary
                                )
                            }
                            IconButton(
                                onClick = { viewModel.deleteCache(row.provider, row.symbol) },
                                modifier = Modifier.testTag("delete_cache_${row.provider}_${row.symbol}")
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete cached candles", tint = CoralRed)
                            }
                        }
                    }
                }
            }
        }

        item {
            SectionCard(
                title = "Angel One Instrument Search",
                subtitle = "Search downloaded OpenAPIScripMaster contracts by symbol or token."
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = searchQ,
                        onValueChange = { searchQ = it },
                        label = { Text("Query (e.g. NIFTY)") },
                        modifier = Modifier.weight(1.4f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = searchExch,
                        onValueChange = { searchExch = it.uppercase() },
                        label = { Text("Exch") },
                        modifier = Modifier.weight(0.7f),
                        singleLine = true
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.searchInstruments(searchQ, searchExch) },
                        modifier = Modifier.weight(1f).testTag("angel_search_button")
                    ) {
                        Icon(Icons.Default.Search, contentDescription = "Search instruments", modifier = Modifier.size(16.dp))
                        Text(" Search")
                    }
                    FilledTonalButton(
                        onClick = { viewModel.refreshAngelMaster() },
                        enabled = !isBusy,
                        modifier = Modifier.weight(1f).testTag("angel_refresh_master_market")
                    ) {
                        Text("Refresh Master")
                    }
                }
                instruments.take(25).forEach { inst ->
                    HorizontalDivider(color = NavyBorder)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(inst.tradingSymbol, style = MaterialTheme.typography.titleSmall)
                            Text(
                                "${inst.exchangeSegment} • Token: ${inst.instrumentToken} • Lot: ${inst.lotSize?.toInt() ?: 0}",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary
                            )
                        }
                        FilledTonalButton(onClick = {
                            provider = "angel_one"
                            symbol = inst.instrumentToken
                            exchange = inst.exchangeSegment
                        }) {
                            Text("Use")
                        }
                    }
                }
            }
        }
    }
}
