package com.example.ui.screens

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.components.ApiHealthAlertBanner
import com.example.ui.components.DirectionPill
import com.example.ui.components.DistributionBarRow
import com.example.ui.components.MetricCard
import com.example.ui.components.SectionCard
import com.example.ui.components.StatusPill
import com.example.ui.components.VerificationStatusPill
import com.example.ui.components.formatPnl
import com.example.ui.theme.AmberContainer
import com.example.ui.theme.AmberWarn
import com.example.ui.theme.CoralRed
import com.example.ui.theme.EmeraldAccent
import com.example.ui.theme.EmeraldContainer
import com.example.ui.theme.MonoFontFamily
import com.example.ui.theme.NavyBorder
import com.example.ui.theme.NavyCardElevated
import com.example.ui.theme.SignalBlue
import com.example.ui.theme.TextSecondary
import com.example.ui.viewmodel.MainViewModel

@Composable
fun OverviewScreen(
    viewModel: MainViewModel,
    onNavigateToServer: () -> Unit,
    onNavigateToSignals: () -> Unit,
    onNavigateToMarkets: () -> Unit = {}
) {
    val overview by viewModel.overview.collectAsStateWithLifecycle()
    val serviceStatus by viewModel.serviceStatus.collectAsStateWithLifecycle()
    val apiHealth by viewModel.apiHealth.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (apiHealth.activeAlerts.isNotEmpty()) {
            item {
                ApiHealthAlertBanner(
                    summary = apiHealth,
                    onOpenDiagnostics = onNavigateToMarkets
                )
            }
        }
        // Header & Quick Actions
        item {
            SectionCard(
                title = "Signal Analyzer",
                subtitle = "Local-first Android trading signal parser, verifier & LAN dashboard",
                action = {
                    StatusPill(
                        text = if (serviceStatus.lanDashboardEnabled) "LAN LIVE" else if (serviceStatus.running) "PROCESSING" else "LOCAL",
                        bg = if (serviceStatus.running) EmeraldContainer else NavyCardElevated,
                        fg = if (serviceStatus.running) EmeraldAccent else TextSecondary
                    )
                }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { viewModel.syncGoogleSheetsNow() },
                        enabled = !isBusy,
                        modifier = Modifier.weight(1f).testTag("overview_sync_button")
                    ) {
                        Icon(Icons.Default.CloudSync, contentDescription = "Sync sheets", modifier = Modifier.size(18.dp))
                        Text(" Sync Sheets")
                    }
                    FilledTonalButton(
                        onClick = onNavigateToServer,
                        modifier = Modifier.weight(1f).testTag("overview_lan_button")
                    ) {
                        Icon(Icons.Default.Lan, contentDescription = "LAN Server", modifier = Modifier.size(18.dp))
                        Text(" LAN Server")
                    }
                    FilledTonalButton(
                        onClick = { viewModel.refreshAll() },
                        modifier = Modifier.testTag("overview_refresh_button")
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh overview", modifier = Modifier.size(18.dp))
                    }
                }
                if (serviceStatus.lanDashboardEnabled) {
                    Text(
                        "Dashboard: ${serviceStatus.lanUrl} • ${serviceStatus.activeWsClients} client(s)",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                        color = EmeraldAccent
                    )
                }
            }
        }

        // KPI Metrics Grid (2-column pairs)
        item {
            val ov = overview
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricCard("Notifications", "${ov?.notifications ?: 0}", Modifier.weight(1f), SignalBlue)
                    MetricCard("Parsed Signals", "${ov?.parsed ?: 0}", Modifier.weight(1f), EmeraldAccent)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricCard("Target Hits", "${ov?.targetHits ?: 0}", Modifier.weight(1f), EmeraldAccent)
                    MetricCard("Stop-Loss Hits", "${ov?.stopLossHits ?: 0}", Modifier.weight(1f), CoralRed)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricCard("Win Rate", "${ov?.winRate ?: 0.0}%", Modifier.weight(1f), AmberWarn)
                    MetricCard("Pending", "${ov?.pending ?: 0}", Modifier.weight(1f), TextSecondary)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val inr = ov?.pnlInr ?: 0.0
                    val usd = ov?.pnlUsd ?: 0.0
                    MetricCard(
                        "TP1 Realized P&L (INR)",
                        formatPnl(inr, "INR"),
                        Modifier.weight(1f),
                        if (inr >= 0.0) EmeraldAccent else CoralRed
                    )
                    MetricCard(
                        "TP1 Realized P&L (USD)",
                        formatPnl(usd, "USD"),
                        Modifier.weight(1f),
                        if (usd >= 0.0) EmeraldAccent else CoralRed
                    )
                }
            }
        }

        // Category Distribution
        item {
            val cats = overview?.categories.orEmpty()
            val maxCat = cats.maxOfOrNull { it.second } ?: 1
            SectionCard(title = "Category Distribution") {
                if (cats.isEmpty()) {
                    Text("No signals imported yet.", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                } else {
                    cats.forEach { (name, count) ->
                        DistributionBarRow(name, count, maxCat)
                    }
                }
            }
        }

        // Channel Distribution
        item {
            val chans = overview?.channels.orEmpty()
            val maxChan = chans.maxOfOrNull { it.second } ?: 1
            SectionCard(title = "Top Channels") {
                if (chans.isEmpty()) {
                    Text("No channels recorded yet.", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                } else {
                    chans.forEach { (name, count) ->
                        DistributionBarRow(name, count, maxChan)
                    }
                }
            }
        }

        // Recent Parsed Signals
        item {
            SectionCard(
                title = "Recent Parsed Signals",
                action = {
                    FilledTonalButton(
                        onClick = onNavigateToSignals,
                        modifier = Modifier.testTag("overview_view_all_signals")
                    ) {
                        Text("View All")
                    }
                }
            ) {
                val recent = overview?.recentSignals.orEmpty()
                if (recent.isEmpty()) {
                    Text(
                        "No parsed signals yet. Use Tools → Parse & Import or configure Google Sheets Sync.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                } else {
                    recent.forEachIndexed { index, s ->
                        if (index > 0) HorizontalDivider(color = NavyBorder)
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "#${s.row.id} ${s.row.symbol ?: "UNPARSED"}",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    DirectionPill(s.row.direction)
                                }
                                Text(
                                    "${s.row.channel ?: "—"} • Entry: ${s.row.entryPrice ?: s.row.entryMode}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary
                                )
                            }
                            VerificationStatusPill(
                                status = s.row.verificationStatus,
                                isManualNeeded = s.isManualNeeded,
                                isAutoScheduled = s.isAutoScheduled,
                                closedBySlAfterTp = s.closedBySlAfterTp,
                                maxTpHit = s.maxTpHit
                            )
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(12.dp)) }
    }
}
