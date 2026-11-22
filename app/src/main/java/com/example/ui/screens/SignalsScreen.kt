package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.example.data.repository.EnrichedSignal
import com.example.data.repository.SignalChartData
import com.example.data.repository.SignalEditPayload
import com.example.ui.components.ApiHealthAlertBanner
import com.example.ui.components.DirectionPill
import com.example.ui.components.LevelPnlStrip
import com.example.ui.components.MonitoringPhasePill
import com.example.ui.components.OhlcChartCanvas
import com.example.ui.components.StatusPill
import com.example.ui.components.VerificationStatusPill
import com.example.ui.components.formatFullTimestamp
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
import com.example.ui.theme.NavyBorderActive
import com.example.ui.theme.NavyCard
import com.example.ui.theme.NavyCardElevated
import com.example.ui.theme.NavyInput
import com.example.ui.theme.SignalBlue
import com.example.ui.theme.SignalBlueContainer
import com.example.ui.theme.TextSecondary
import com.example.ui.viewmodel.MainViewModel

@Composable
fun SignalsScreen(
    viewModel: MainViewModel,
    onNavigateToMarkets: () -> Unit = {}
) {
    val signals by viewModel.signals.collectAsStateWithLifecycle()
    val filter by viewModel.signalFilter.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val channels by viewModel.channels.collectAsStateWithLifecycle()
    val pnlSummary by viewModel.filteredPnlSummary.collectAsStateWithLifecycle()
    val apiHealth by viewModel.apiHealth.collectAsStateWithLifecycle()
    val selectedIds by viewModel.selectedSignalIds.collectAsStateWithLifecycle()
    val charts by viewModel.charts.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()

    var expandedIds by remember { mutableStateOf(setOf<Long>()) }
    var editingSignal by remember { mutableStateOf<EnrichedSignal?>(null) }

    val milestoneOptions = listOf(
        "" to "All Signals",
        "tp1_plus" to "TP1+",
        "tp2_plus" to "TP2+",
        "tp3_plus" to "TP3+",
        "all_tps" to "All TPs",
        "tp1_only" to "TP1 Only",
        "tp_then_sl" to "TP → SL",
        "sl_only" to "SL Only",
        "active" to "Active"
    )
    val datePresets = listOf(
        "all" to "All Time",
        "today" to "Today",
        "7d" to "7d",
        "30d" to "30d"
    )
    val basisOptions = listOf(
        "tp1" to "P&L: TP1",
        "tp2" to "P&L: TP2",
        "tp3" to "P&L: TP3",
        "highest_tp" to "P&L: Best"
    )

    Column(modifier = Modifier.fillMaxSize()) {
        if (apiHealth.activeAlerts.isNotEmpty()) {
            ApiHealthAlertBanner(
                summary = apiHealth,
                onOpenDiagnostics = onNavigateToMarkets,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }

        // Filter & Batch Action Bar
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(NavyCard)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Category + Channel filter chips
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(
                    selected = filter.category.isEmpty(),
                    onClick = { viewModel.updateSignalFilter { it.copy(category = "", channel = "") } },
                    label = { Text("All Categories") },
                    modifier = Modifier.testTag("filter_cat_all")
                )
                categories.forEach { cat ->
                    FilterChip(
                        selected = filter.category == cat,
                        onClick = {
                            viewModel.updateSignalFilter {
                                it.copy(category = if (it.category == cat) "" else cat, channel = "")
                            }
                        },
                        label = { Text(cat) },
                        modifier = Modifier.testTag("filter_cat_${cat.lowercase().replace(" ", "_")}")
                    )
                }
            }

            if (channels.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = filter.channel.isEmpty(),
                        onClick = { viewModel.updateSignalFilter { it.copy(channel = "") } },
                        label = { Text("All Channels") },
                        modifier = Modifier.testTag("filter_chan_all")
                    )
                    channels.forEach { ch ->
                        FilterChip(
                            selected = filter.channel == ch,
                            onClick = {
                                viewModel.updateSignalFilter {
                                    it.copy(channel = if (it.channel == ch) "" else ch)
                                }
                            },
                            label = { Text(ch) }
                        )
                    }
                }
            }

            // Milestone filter chips
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                milestoneOptions.forEach { (key, label) ->
                    FilterChip(
                        selected = filter.tpFilter == key,
                        onClick = { viewModel.updateSignalFilter { it.copy(tpFilter = key) } },
                        label = { Text(label) },
                        modifier = Modifier.testTag("filter_tp_${key.ifEmpty { "all" }}")
                    )
                }
            }

            // Date Window + P&L Basis selector
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                datePresets.forEach { (key, label) ->
                    FilterChip(
                        selected = filter.datePreset == key,
                        onClick = { viewModel.updateSignalFilter { it.copy(datePreset = key) } },
                        label = { Text(label) },
                        modifier = Modifier.testTag("filter_date_$key")
                    )
                }
                basisOptions.forEach { (key, label) ->
                    FilterChip(
                        selected = filter.pnlBasis == key,
                        onClick = { viewModel.updateSignalFilter { it.copy(pnlBasis = key) } },
                        label = { Text(label) },
                        modifier = Modifier.testTag("filter_basis_$key")
                    )
                }
            }

            // Filtered P&L Summary Strip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(NavyInput)
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .testTag("signals_filtered_pnl_strip"),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "${pnlSummary.totalSignals} signals • Win ${pnlSummary.winRate}% • TP1:${pnlSummary.tp1Hits} TP2:${pnlSummary.tp2Hits} TP3:${pnlSummary.tp3Hits} SL:${pnlSummary.slOnlyHits}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                    Text(
                        "All TPs: ${pnlSummary.allTpHits} • TP→SL: ${pnlSummary.tpThenSlHits} • Active: ${pnlSummary.activeMonitoring}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        formatPnl(pnlSummary.pnlInr, "INR"),
                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = MonoFontFamily, fontWeight = FontWeight.Bold),
                        color = if (pnlSummary.pnlInr >= 0.0) EmeraldAccent else CoralRed
                    )
                    if (pnlSummary.pnlUsd != 0.0) {
                        Text(
                            formatPnl(pnlSummary.pnlUsd, "USD"),
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily, fontWeight = FontWeight.Bold),
                            color = if (pnlSummary.pnlUsd >= 0.0) EmeraldAccent else CoralRed
                        )
                    }
                }
            }

            // Batch actions
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledTonalButton(onClick = { viewModel.selectAllFiltered() }, modifier = Modifier.testTag("signals_select_all")) {
                    Text("Select All (${signals.size})")
                }
                if (selectedIds.isNotEmpty()) {
                    Button(
                        onClick = { viewModel.processSelectedSignals() },
                        enabled = !isBusy,
                        modifier = Modifier.testTag("signals_process_selected")
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Process selected", modifier = Modifier.size(16.dp))
                        Text(" Process (${selectedIds.size})")
                    }
                    FilledTonalButton(
                        onClick = { viewModel.reparseSelectedSignals() },
                        enabled = !isBusy,
                        modifier = Modifier.testTag("signals_reparse_selected")
                    ) {
                        Text("Re-parse")
                    }
                    Button(
                        onClick = { viewModel.deleteSelectedSignals() },
                        colors = ButtonDefaults.buttonColors(containerColor = CoralContainer, contentColor = CoralRed),
                        modifier = Modifier.testTag("signals_delete_selected")
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete selected", modifier = Modifier.size(16.dp))
                        Text(" Delete")
                    }
                    TextButton(onClick = { viewModel.clearSelection() }) { Text("Clear") }
                } else {
                    FilledTonalButton(onClick = { viewModel.refreshSignals() }, modifier = Modifier.testTag("signals_refresh")) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh signals", modifier = Modifier.size(16.dp))
                        Text(" Refresh")
                    }
                }
            }
        }

        HorizontalDivider(color = NavyBorder)

        if (signals.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("No signals match the active filters.", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Import notifications via Google Sheets sync or Tools → Parse & Import.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(signals, key = { it.row.id }) { sig ->
                    val isExp = sig.row.id in expandedIds
                    SignalAccordionCard(
                        sig = sig,
                        activePnlBasis = filter.pnlBasis,
                        isSelected = sig.row.id in selectedIds,
                        isExpanded = isExp,
                        chartData = charts[sig.row.id],
                        onToggleSelect = { viewModel.toggleSelectSignal(sig.row.id) },
                        onToggleExpand = {
                            if (isExp) {
                                expandedIds = expandedIds - sig.row.id
                            } else {
                                expandedIds = expandedIds + sig.row.id
                                viewModel.loadChart(sig.row.id, forceVerify = false)
                            }
                        },
                        onVerifyNow = { viewModel.loadChart(sig.row.id, forceVerify = true) },
                        onReparse = { viewModel.reparseSingleSignal(sig.row.id) },
                        onEdit = { editingSignal = sig },
                        onDelete = { viewModel.deleteSignal(sig.row.id) }
                    )
                }
            }
        }
    }

    editingSignal?.let { target ->
        SignalEditDialog(
            signal = target,
            onDismiss = { editingSignal = null },
            onSave = { payload ->
                viewModel.saveManualSignalEdit(target.row.id, payload)
                editingSignal = null
            }
        )
    }
}

@Composable
private fun SignalAccordionCard(
    sig: EnrichedSignal,
    activePnlBasis: String,
    isSelected: Boolean,
    isExpanded: Boolean,
    chartData: SignalChartData?,
    onToggleSelect: () -> Unit,
    onToggleExpand: () -> Unit,
    onVerifyNow: () -> Unit,
    onReparse: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val r = sig.row
    val allEntries = buildList {
        r.entryPrice?.let { add(it) }
        addAll(sig.additionalEntries)
    }
    val entryTxt = when {
        allEntries.isNotEmpty() -> allEntries.joinToString(" / ")
        r.entryMode == "market_now" -> "Market Now"
        else -> "—"
    }
    val optionMeta = listOfNotNull(r.strikePrice?.let { if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString() }, r.optionType)
        .joinToString(" ")
        .let { if (it.isNotEmpty()) " $it" else "" }

    Card(
        modifier = Modifier.fillMaxWidth().testTag("signal_card_${r.id}"),
        colors = CardDefaults.cardColors(containerColor = NavyCard),
        border = BorderStroke(1.dp, if (isExpanded) NavyBorderActive else NavyBorder),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpand)
                    .padding(horizontal = 10.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleSelect() },
                    modifier = Modifier.testTag("signal_check_${r.id}")
                )
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("#${r.id}", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                        Text(
                            "${r.symbol ?: "UNPARSED"}$optionMeta",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        DirectionPill(r.direction)
                    }
                    Text(
                        "${r.category ?: ""} • ${r.channel ?: ""} • ${formatShortTimestamp(r.notificationTime)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Text(
                        "E: $entryTxt | SL: ${r.stopLoss ?: "—"} | TP: ${sig.targets.joinToString("/").ifEmpty { "—" }}",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        VerificationStatusPill(
                            status = r.verificationStatus,
                            isManualNeeded = sig.isManualNeeded,
                            isAutoScheduled = sig.isAutoScheduled,
                            closedBySlAfterTp = sig.closedBySlAfterTp,
                            maxTpHit = sig.maxTpHit
                        )
                        MonitoringPhasePill(sig.monitoringPhase)
                        val basisPnl = sig.pnlForBasis(activePnlBasis)
                        if (sig.tradesCount > 0 || basisPnl != 0.0) {
                            StatusPill(
                                text = formatPnl(basisPnl, sig.pnlCurrency),
                                bg = if (basisPnl >= 0.0) EmeraldContainer else CoralContainer,
                                fg = if (basisPnl >= 0.0) EmeraldAccent else CoralRed
                            )
                        }
                        if (r.parsingStatus != "parsed") {
                            StatusPill(r.parsingStatus, AmberContainer, AmberWarn)
                        }
                    }
                    LevelPnlStrip(
                        sig = sig,
                        activeBasis = activePnlBasis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                Icon(
                    imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (isExpanded) "Collapse signal" else "Expand signal",
                    tint = TextSecondary
                )
            }

            if (isExpanded) {
                HorizontalDivider(color = NavyBorder)
                Column(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Status banner
                    Text(
                        sig.statusReason,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (sig.isManualNeeded) AmberWarn else SignalBlue,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (sig.isManualNeeded) AmberContainer.copy(alpha = 0.5f) else SignalBlueContainer.copy(alpha = 0.5f))
                            .padding(10.dp)
                    )

                    // Action Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(onClick = onVerifyNow, modifier = Modifier.testTag("signal_verify_${r.id}")) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Fetch and verify", modifier = Modifier.size(16.dp))
                            Text(" Verify & Chart")
                        }
                        FilledTonalButton(onClick = onEdit, modifier = Modifier.testTag("signal_edit_${r.id}")) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit signal", modifier = Modifier.size(16.dp))
                            Text(" Edit")
                        }
                        OutlinedButton(onClick = onReparse, modifier = Modifier.testTag("signal_reparse_${r.id}")) {
                            Text("Re-parse")
                        }
                        OutlinedButton(
                            onClick = onDelete,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = CoralRed),
                            modifier = Modifier.testTag("signal_delete_${r.id}")
                        ) {
                            Text("Delete")
                        }
                    }

                    // Multi-Entry Trade Legs
                    if (sig.legs.isNotEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(NavyInput)
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                "Trade Legs (${sig.tradesCount} of ${sig.legs.size} triggered)",
                                style = MaterialTheme.typography.titleSmall
                            )
                            sig.legs.forEach { lg ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        "Entry ${lg.leg} @ ${lg.entryPrice} (${lg.outcome})",
                                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                                        color = if (lg.entered) MaterialTheme.colorScheme.onSurface else TextSecondary
                                    )
                                    Text(
                                        if (lg.entered) formatPnl(lg.moneyPnl, lg.currency.ifEmpty { sig.pnlCurrency }) else "—",
                                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily, fontWeight = FontWeight.Bold),
                                        color = if (!lg.entered) TextSecondary else if (lg.moneyPnl >= 0.0) EmeraldAccent else CoralRed
                                    )
                                }
                            }
                        }
                    }

                    // Interactive OHLC Chart
                    if (chartData != null) {
                        OhlcChartCanvas(chartData = chartData, height = 240.dp)
                    } else {
                        Text("Loading chart...", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    }

                    // Execution Events Timeline
                    if (sig.events.isNotEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(NavyInput)
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text("Execution Timeline", style = MaterialTheme.typography.titleSmall)
                            sig.events.forEach { ev ->
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(
                                        "${ev.label} @ ${ev.price ?: "—"}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = when (ev.type) {
                                            "tp_hit" -> EmeraldAccent
                                            "sl_after_tp" -> AmberWarn
                                            "sl_hit" -> CoralRed
                                            else -> SignalBlue
                                        }
                                    )
                                    Text(
                                        formatShortTimestamp(ev.candleTime),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextSecondary
                                    )
                                }
                            }
                        }
                    }

                    // Raw Notification & Explanation
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(NavyInput)
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("Original Raw Message (${formatFullTimestamp(r.notificationTime)})", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                        Text(
                            r.message.orEmpty(),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (!r.parsingExplanation.isNullOrBlank()) {
                            Text("Parser: ${r.parsingExplanation}", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SignalEditDialog(
    signal: EnrichedSignal,
    onDismiss: () -> Unit,
    onSave: (SignalEditPayload) -> Unit
) {
    val r = signal.row
    var symbol by remember { mutableStateOf(r.symbol.orEmpty()) }
    var direction by remember { mutableStateOf(r.direction.orEmpty()) }
    var entry by remember { mutableStateOf(r.entryPrice?.toString().orEmpty()) }
    var extraEntries by remember { mutableStateOf(signal.additionalEntries.joinToString(", ")) }
    var sl by remember { mutableStateOf(r.stopLoss?.toString().orEmpty()) }
    var targets by remember { mutableStateOf(signal.targets.joinToString(", ")) }
    var optionType by remember { mutableStateOf(r.optionType.orEmpty()) }
    var strike by remember { mutableStateOf(r.strikePrice?.toString().orEmpty()) }
    var expiry by remember { mutableStateOf(r.expiryDate.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Signal #${r.id}") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(value = symbol, onValueChange = { symbol = it }, label = { Text("Symbol") }, singleLine = true)
                OutlinedTextField(value = direction, onValueChange = { direction = it.uppercase() }, label = { Text("Direction (BUY / SELL)") }, singleLine = true)
                OutlinedTextField(value = entry, onValueChange = { entry = it }, label = { Text("Entry 1") }, singleLine = true)
                OutlinedTextField(value = extraEntries, onValueChange = { extraEntries = it }, label = { Text("Extra Entries (comma)") }, singleLine = true)
                OutlinedTextField(value = sl, onValueChange = { sl = it }, label = { Text("Stop Loss") }, singleLine = true)
                OutlinedTextField(value = targets, onValueChange = { targets = it }, label = { Text("Targets (comma)") }, singleLine = true)
                OutlinedTextField(value = optionType, onValueChange = { optionType = it.uppercase() }, label = { Text("Option Type (CE/PE)") }, singleLine = true)
                OutlinedTextField(value = strike, onValueChange = { strike = it }, label = { Text("Strike Price") }, singleLine = true)
                OutlinedTextField(value = expiry, onValueChange = { expiry = it.uppercase() }, label = { Text("Expiry Date") }, singleLine = true)
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val parsedExtras = extraEntries.split(",").mapNotNull { it.trim().toDoubleOrNull() }
                    val parsedTargets = targets.split(",").mapNotNull { it.trim().toDoubleOrNull() }
                    val entryNum = entry.trim().toDoubleOrNull()
                    onSave(
                        SignalEditPayload(
                            symbol = symbol.trim().uppercase().ifEmpty { null },
                            hasSymbol = true,
                            direction = direction.trim().uppercase().ifEmpty { null },
                            hasDirection = true,
                            entryPrice = entryNum,
                            hasEntryPrice = true,
                            additionalEntries = parsedExtras,
                            stopLoss = sl.trim().toDoubleOrNull(),
                            hasStopLoss = true,
                            targets = parsedTargets,
                            optionType = optionType.trim().uppercase().ifEmpty { null },
                            hasOptionType = true,
                            strikePrice = strike.trim().toDoubleOrNull(),
                            hasStrikePrice = true,
                            expiryDate = expiry.trim().uppercase().ifEmpty { null },
                            hasExpiryDate = true,
                            entryMode = if (entryNum != null) "limit" else r.entryMode,
                            manualReviewStatus = "resolved"
                        )
                    )
                },
                modifier = Modifier.testTag("save_signal_edit_button")
            ) {
                Text("Save Fix")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
