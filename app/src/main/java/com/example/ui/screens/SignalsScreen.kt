package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.repository.EnrichedSignal
import com.example.data.repository.SignalChartData
import com.example.domain.model.FilteredPnlSummary
import com.example.ui.components.ApiHealthAlertBanner
import com.example.ui.components.DirectionPill
import com.example.ui.components.FullscreenOhlcDialog
import com.example.ui.components.LevelPnlStrip
import com.example.ui.components.MicroBadge
import com.example.ui.components.MonitoringPhasePill
import com.example.ui.components.NeedsReviewQuickFixSheet
import com.example.ui.components.OhlcChartCanvas
import com.example.ui.components.SignalLadderRail
import com.example.ui.components.SignalsFilterSheet
import com.example.ui.components.StatusPill
import com.example.ui.components.VerificationStatusPill
import com.example.ui.components.formatFullTimestamp
import com.example.ui.components.formatPnl
import com.example.ui.components.formatShortTimestamp
import com.example.ui.components.isEditedSignal
import com.example.ui.theme.AmberContainer
import com.example.ui.theme.AmberWarn
import com.example.ui.theme.CoralContainer
import com.example.ui.theme.CoralRed
import com.example.ui.theme.EmeraldAccent
import com.example.ui.theme.EmeraldContainer
import com.example.ui.theme.MonoFontFamily
import com.example.ui.theme.NavyBorder
import com.example.ui.theme.NavyBorderActive
import com.example.ui.theme.NavyCanvas
import com.example.ui.theme.NavyCard
import com.example.ui.theme.NavyCardElevated
import com.example.ui.theme.NavyInput
import com.example.ui.theme.NavyWell
import com.example.ui.theme.SignalBlue
import com.example.ui.theme.SignalBlueContainer
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.viewmodel.MainViewModel
import com.example.ui.viewmodel.SignalFilterState

@OptIn(ExperimentalFoundationApi::class)
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
    var showFilterSheet by remember { mutableStateOf(false) }
    var showReviewQueue by remember { mutableStateOf(false) }
    var singleEditSignalId by remember { mutableStateOf<Long?>(null) }
    var fullscreenSignalId by remember { mutableStateOf<Long?>(null) }

    val needsReviewQueue by remember(signals) {
        derivedStateOf { signals.filter { it.row.parsingStatus == "needs_review" } }
    }

    val activeFilterChips = remember(filter) {
        buildList<Pair<String, () -> Unit>> {
            if (filter.channel.isNotEmpty()) add("Ch: ${filter.channel}" to { viewModel.updateSignalFilter { it.copy(channel = "") } })
            if (filter.status.isNotEmpty()) add("Status: ${filter.status}" to { viewModel.updateSignalFilter { it.copy(status = "") } })
            if (filter.tpFilter.isNotEmpty()) add("TP: ${filter.tpFilter}" to { viewModel.updateSignalFilter { it.copy(tpFilter = "") } })
            if (filter.datePreset != "all") add("Date: ${filter.datePreset}" to { viewModel.updateSignalFilter { it.copy(datePreset = "all") } })
            if (filter.notificationFilter != "primary") add("Msg: ${filter.notificationFilter}" to { viewModel.updateSignalFilter { it.copy(notificationFilter = "primary") } })
            if (filter.pnlBasis != "tp1") add("Basis: ${filter.pnlBasis.uppercase()}" to { viewModel.updateSignalFilter { it.copy(pnlBasis = "tp1") } })
        }
    }

    fun cyclePnlBasis() {
        val order = listOf("tp1", "tp2", "tp3", "highest_tp")
        val next = order[(order.indexOf(filter.pnlBasis) + 1) % order.size]
        viewModel.updateSignalFilter { it.copy(pnlBasis = next) }
    }

    fun resetAllFilters() {
        viewModel.updateSignalFilter { SignalFilterState() }
    }

    Column(modifier = Modifier.fillMaxSize().background(NavyCanvas)) {
        if (apiHealth.activeAlerts.isNotEmpty()) {
            ApiHealthAlertBanner(
                summary = apiHealth,
                onOpenDiagnostics = onNavigateToMarkets,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
            )
        }

        // Compact Terminal Top Bar (Category Strip + Search + Filter Trigger + Review Queue Pill)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(NavyCard)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Row 1: Single Category Pill Strip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = filter.category.isEmpty(),
                    onClick = { viewModel.updateSignalFilter { it.copy(category = "", channel = "") } },
                    label = { Text("All") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = EmeraldContainer,
                        selectedLabelColor = EmeraldAccent
                    ),
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
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = EmeraldContainer,
                            selectedLabelColor = EmeraldAccent
                        ),
                        modifier = Modifier.testTag("filter_cat_${cat.lowercase().replace(" ", "_")}")
                    )
                }
            }

            // Row 2: Compact Search + Filters(N) + Review(N) + Select/Refresh
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Search Box
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(NavyWell)
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search signals",
                        tint = TextMuted,
                        modifier = Modifier.size(16.dp)
                    )
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (filter.searchQuery.isEmpty()) {
                            Text("Search symbol, channel...", style = MaterialTheme.typography.bodySmall, color = TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        BasicTextField(
                            value = filter.searchQuery,
                            onValueChange = { q -> viewModel.updateSignalFilter { it.copy(searchQuery = q) } },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall.copy(color = TextPrimary),
                            cursorBrush = SolidColor(EmeraldAccent),
                            modifier = Modifier.fillMaxWidth().testTag("signals_search_input")
                        )
                    }
                    if (filter.searchQuery.isNotEmpty()) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Clear search",
                            tint = TextSecondary,
                            modifier = Modifier
                                .size(16.dp)
                                .clickable { viewModel.updateSignalFilter { it.copy(searchQuery = "") } }
                        )
                    }
                }

                // Filters(N) trigger button
                val filterCount = activeFilterChips.size
                Surface(
                    onClick = { showFilterSheet = true },
                    color = if (filterCount > 0) SignalBlueContainer else NavyCardElevated,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, if (filterCount > 0) SignalBlue else NavyBorder),
                    modifier = Modifier.height(40.dp).testTag("open_filters_sheet_btn")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FilterList,
                            contentDescription = "Open filters",
                            tint = if (filterCount > 0) SignalBlue else TextPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = if (filterCount > 0) "Filters ($filterCount)" else "Filters",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = if (filterCount > 0) SignalBlue else TextPrimary
                        )
                    }
                }

                // Needs-Review(N) Quick-Fix Pill
                if (needsReviewQueue.isNotEmpty()) {
                    Surface(
                        onClick = { showReviewQueue = true },
                        color = AmberContainer,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, AmberWarn),
                        modifier = Modifier.height(40.dp).testTag("open_needs_review_btn")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "Open needs review queue",
                                tint = AmberWarn,
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = "${needsReviewQueue.size}",
                                style = MaterialTheme.typography.labelMedium.copy(fontFamily = MonoFontFamily, fontWeight = FontWeight.Bold),
                                color = AmberWarn
                            )
                        }
                    }
                }

                // Refresh button
                IconButton(
                    onClick = { viewModel.refreshSignals() },
                    modifier = Modifier.size(40.dp).testTag("signals_refresh")
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh signals", tint = TextSecondary)
                }
            }

            // Row 3: Dismissible Active Filter Micro-Chips (only shown if secondary filters are active)
            AnimatedVisibility(visible = activeFilterChips.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    activeFilterChips.forEach { (label, onClear) ->
                        Surface(
                            onClick = onClear,
                            color = SignalBlueContainer.copy(alpha = 0.65f),
                            shape = RoundedCornerShape(999.dp),
                            border = BorderStroke(1.dp, SignalBlue.copy(alpha = 0.5f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(label, style = MaterialTheme.typography.labelSmall, color = SignalBlue)
                                Icon(Icons.Default.Close, contentDescription = "Remove $label", tint = SignalBlue, modifier = Modifier.size(12.dp))
                            }
                        }
                    }
                    TextButton(
                        onClick = { resetAllFilters() },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.height(28.dp).testTag("signals_reset_filters_inline")
                    ) {
                        Text("Reset", style = MaterialTheme.typography.labelSmall, color = CoralRed)
                    }
                }
            }

            // Contextual Batch Action Bar (appears only when 1+ signals are selected)
            AnimatedVisibility(
                visible = selectedIds.isNotEmpty(),
                enter = fadeIn() + expandVertically(spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)),
                exit = fadeOut() + shrinkVertically()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(NavyCardElevated)
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledTonalButton(
                        onClick = { viewModel.selectAllFiltered() },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.testTag("signals_select_all")
                    ) {
                        Icon(Icons.Default.SelectAll, contentDescription = null, modifier = Modifier.size(15.dp))
                        Text(" All (${signals.size})", style = MaterialTheme.typography.labelSmall)
                    }
                    Button(
                        onClick = { viewModel.processSelectedSignals() },
                        enabled = !isBusy,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.testTag("signals_process_selected")
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Process selected", modifier = Modifier.size(15.dp))
                        Text(" Process (${selectedIds.size})", style = MaterialTheme.typography.labelSmall)
                    }
                    FilledTonalButton(
                        onClick = { viewModel.reparseSelectedSignals() },
                        enabled = !isBusy,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.testTag("signals_reparse_selected")
                    ) {
                        Text("Re-parse", style = MaterialTheme.typography.labelSmall)
                    }
                    Button(
                        onClick = { viewModel.deleteSelectedSignals() },
                        colors = ButtonDefaults.buttonColors(containerColor = CoralContainer, contentColor = CoralRed),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.testTag("signals_delete_selected")
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete selected", modifier = Modifier.size(15.dp))
                        Text(" Delete", style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(
                        onClick = { viewModel.clearSelection() },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text("Clear", style = MaterialTheme.typography.labelSmall)
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
                Text("No signals match the active filters.", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Clear filters or sync notifications from Google Sheets.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                if (activeFilterChips.isNotEmpty() || filter.category.isNotEmpty() || filter.searchQuery.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    FilledTonalButton(onClick = { resetAllFilters() }, modifier = Modifier.testTag("empty_clear_filters")) {
                        Text("Clear All Filters")
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Sticky P&L Ticker Bar pinned at top while scrolling
                stickyHeader(key = "sticky_pnl_ticker") {
                    StickyPnlTickerBar(
                        pnlSummary = pnlSummary,
                        activeBasis = filter.pnlBasis,
                        onCycleBasis = { cyclePnlBasis() }
                    )
                }

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
                        onEdit = { singleEditSignalId = sig.row.id },
                        onDelete = { viewModel.deleteSignal(sig.row.id) },
                        onOpenFullscreen = { fullscreenSignalId = sig.row.id },
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }
            }
        }
    }

    // Collapsible Secondary Filter Sheet
    if (showFilterSheet) {
        SignalsFilterSheet(
            filter = filter,
            channels = channels,
            onUpdateFilter = { viewModel.updateSignalFilter(it) },
            onResetAll = { resetAllFilters() },
            onDismiss = { showFilterSheet = false }
        )
    }

    // Needs-Review Queue Sheet OR Single-Signal Quick-Fix Sheet
    if (showReviewQueue) {
        NeedsReviewQuickFixSheet(
            queue = needsReviewQueue,
            isQueueMode = true,
            isBusy = isBusy,
            onDismiss = { showReviewQueue = false },
            onReparse = { viewModel.reparseSingleSignal(it) },
            onSave = { id, payload, verify ->
                viewModel.saveManualSignalEdit(id, payload, verifyImmediately = verify)
            }
        )
    } else if (singleEditSignalId != null) {
        val target = signals.firstOrNull { it.row.id == singleEditSignalId }
        if (target != null) {
            NeedsReviewQuickFixSheet(
                queue = listOf(target),
                isQueueMode = false,
                isBusy = isBusy,
                onDismiss = { singleEditSignalId = null },
                onReparse = { viewModel.reparseSingleSignal(it) },
                onSave = { id, payload, verify ->
                    viewModel.saveManualSignalEdit(id, payload, verifyImmediately = verify)
                }
            )
        }
    }

    // Fullscreen Interactive OHLC Chart Modal
    fullscreenSignalId?.let { fId ->
        val fSig = signals.firstOrNull { it.row.id == fId }
        val fChart = charts[fId]
        if (fSig != null && fChart != null) {
            FullscreenOhlcDialog(
                signal = fSig,
                chartData = fChart,
                onDismiss = { fullscreenSignalId = null }
            )
        }
    }
}

@Composable
private fun StickyPnlTickerBar(
    pnlSummary: FilteredPnlSummary,
    activeBasis: String,
    onCycleBasis: () -> Unit
) {
    val basisLabel = when (activeBasis) {
        "tp2" -> "TP2"
        "tp3" -> "TP3"
        "highest_tp" -> "Best TP"
        else -> "TP1"
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(NavyCanvas.copy(alpha = 0.96f))
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("signals_filtered_pnl_strip")
    ) {
        Surface(
            color = NavyCard,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, NavyBorderActive.copy(alpha = 0.7f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Brush.horizontalGradient(listOf(NavyCardElevated.copy(alpha = 0.6f), NavyWell)))
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${pnlSummary.totalSignals} sigs",
                            style = MaterialTheme.typography.labelMedium.copy(fontFamily = MonoFontFamily, fontWeight = FontWeight.Bold),
                            color = TextPrimary
                        )
                        Text(
                            text = "Win ${pnlSummary.winRate}%",
                            style = MaterialTheme.typography.labelMedium.copy(fontFamily = MonoFontFamily, fontWeight = FontWeight.Bold),
                            color = if (pnlSummary.winRate >= 50.0) EmeraldAccent else AmberWarn
                        )
                        Surface(
                            onClick = onCycleBasis,
                            color = SignalBlueContainer,
                            shape = RoundedCornerShape(6.dp),
                            border = BorderStroke(1.dp, SignalBlue.copy(alpha = 0.55f)),
                            modifier = Modifier.testTag("sticky_pnl_basis_cycle")
                        ) {
                            Text(
                                text = "Basis: $basisLabel ↻",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                                color = SignalBlue,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Text(
                        text = "TP1:${pnlSummary.tp1Hits}  TP2:${pnlSummary.tp2Hits}  TP3:${pnlSummary.tp3Hits}  TP→SL:${pnlSummary.tpThenSlHits}  SL:${pnlSummary.slOnlyHits}",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily, fontSize = 10.sp),
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = formatPnl(pnlSummary.pnlInr, "INR"),
                        style = MaterialTheme.typography.titleSmall.copy(fontFamily = MonoFontFamily, fontWeight = FontWeight.Bold),
                        color = if (pnlSummary.pnlInr >= 0.0) EmeraldAccent else CoralRed
                    )
                    if (pnlSummary.pnlUsd != 0.0) {
                        Text(
                            text = formatPnl(pnlSummary.pnlUsd, "USD"),
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily, fontWeight = FontWeight.Bold),
                            color = if (pnlSummary.pnlUsd >= 0.0) EmeraldAccent else CoralRed
                        )
                    }
                }
            }
        }
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
    onDelete: () -> Unit,
    onOpenFullscreen: () -> Unit,
    modifier: Modifier = Modifier
) {
    val r = sig.row
    val isMarketNow = r.entryMode == "market_now"
    val isEdited = isEditedSignal(r.parsingExplanation)
    val optionMeta = listOfNotNull(
        r.strikePrice?.let { if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString() },
        r.optionType
    ).joinToString(" ").let { if (it.isNotEmpty()) " $it" else "" }

    val cardBorder = when {
        isSelected -> SignalBlue
        isExpanded -> NavyBorderActive
        r.parsingStatus == "needs_review" -> AmberWarn.copy(alpha = 0.5f)
        else -> NavyBorder
    }

    Card(
        modifier = modifier.fillMaxWidth().testTag("signal_card_${r.id}"),
        colors = CardDefaults.cardColors(containerColor = NavyCard),
        border = BorderStroke(1.dp, cardBorder),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(NavyCardElevated.copy(alpha = 0.45f), NavyCard)))
        ) {
            // Card Header + Visual TP/SL Ladder
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpand)
                    .padding(horizontal = 10.dp, vertical = 11.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onToggleSelect() },
                        modifier = Modifier.size(36.dp).testTag("signal_check_${r.id}")
                    )
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("#${r.id}", style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily), color = TextMuted)
                            Text(
                                "${r.symbol ?: "UNPARSED"}$optionMeta",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            DirectionPill(r.direction)
                            if (isMarketNow) {
                                MicroBadge("MKT", SignalBlueContainer, SignalBlue)
                            }
                            if (isEdited) {
                                MicroBadge("EDITED", AmberContainer, AmberWarn)
                            }
                        }
                        Text(
                            "${r.category ?: ""} • ${r.channel ?: ""} • ${formatShortTimestamp(r.notificationTime)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    val basisPnl = sig.pnlForBasis(activePnlBasis)
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (sig.tradesCount > 0 || basisPnl != 0.0) {
                            Text(
                                text = formatPnl(basisPnl, sig.pnlCurrency),
                                style = MaterialTheme.typography.labelMedium.copy(fontFamily = MonoFontFamily, fontWeight = FontWeight.Bold),
                                color = if (basisPnl >= 0.0) EmeraldAccent else CoralRed
                            )
                        }
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (isExpanded) "Collapse signal" else "Expand signal",
                            tint = TextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // Visual TP/SL Price Journey Ladder Rail
                SignalLadderRail(sig = sig)

                // Status Pills + Quick Fix trigger for needs_review
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
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
                    if (r.parsingStatus != "parsed") {
                        Surface(
                            onClick = onEdit,
                            color = AmberContainer,
                            shape = RoundedCornerShape(999.dp),
                            border = BorderStroke(1.dp, AmberWarn.copy(alpha = 0.7f)),
                            modifier = Modifier.testTag("signal_quick_fix_${r.id}")
                        ) {
                            Text(
                                text = "Quick Fix ✎",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = AmberWarn,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                LevelPnlStrip(sig = sig, activeBasis = activePnlBasis)
            }

            // Spring-animated Expandable Detail Section
            AnimatedVisibility(
                visible = isExpanded,
                enter = fadeIn() + expandVertically(spring(dampingRatio = 0.85f, stiffness = 420f)),
                exit = fadeOut() + shrinkVertically(spring(dampingRatio = 0.9f, stiffness = 460f))
            ) {
                Column {
                    HorizontalDivider(color = NavyBorder)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(NavyWell.copy(alpha = 0.7f))
                            .padding(12.dp),
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
                                .background(if (sig.isManualNeeded) AmberContainer.copy(alpha = 0.45f) else SignalBlueContainer.copy(alpha = 0.45f))
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
                                Text(" Quick Fix")
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
                                            color = if (lg.entered) TextPrimary else TextSecondary
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

                        // Interactive OHLC Chart with Fullscreen trigger
                        if (chartData != null) {
                            OhlcChartCanvas(
                                chartData = chartData,
                                height = 250.dp,
                                onOpenFullscreen = onOpenFullscreen
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(120.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(NavyInput),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("Loading 1m OHLC chart...", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                            }
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
                                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                                            color = when (ev.type) {
                                                "tp_hit" -> EmeraldAccent
                                                "sl_after_tp" -> AmberWarn
                                                "sl_hit" -> CoralRed
                                                else -> SignalBlue
                                            }
                                        )
                                        Text(
                                            formatShortTimestamp(ev.candleTime),
                                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
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
                            Text(
                                "Original Raw Message (${formatFullTimestamp(r.notificationTime)})",
                                style = MaterialTheme.typography.labelMedium,
                                color = TextSecondary
                            )
                            Text(
                                r.message.orEmpty(),
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily),
                                color = TextPrimary
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
}
