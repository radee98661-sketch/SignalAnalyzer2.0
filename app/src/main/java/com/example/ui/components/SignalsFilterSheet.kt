package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ui.theme.EmeraldAccent
import com.example.ui.theme.NavyBorder
import com.example.ui.theme.NavyCardElevated
import com.example.ui.theme.OnEmerald
import com.example.ui.theme.SignalBlue
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.viewmodel.SignalFilterState

/**
 * Collapsible Material 3 ModalBottomSheet holding secondary signal filters
 * (Channel, Verification Status, Milestone/TP, Date Window, P&L Basis, Notification Type)
 * so the main Signals list stays clean and spacious.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SignalsFilterSheet(
    filter: SignalFilterState,
    channels: List<String>,
    onUpdateFilter: ((SignalFilterState) -> SignalFilterState) -> Unit,
    onResetAll: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val statusOptions = listOf(
        "" to "All Statuses",
        "needs_review" to "Needs Review",
        "entry_hit" to "Entry Hit",
        "target_hit" to "TP Hit",
        "stop_loss_hit" to "SL Hit",
        "pending" to "Pending",
        "scheduled" to "Monitoring",
        "manual_needed" to "Manual Needed",
        "ambiguous" to "Ambiguous"
    )
    val milestoneOptions = listOf(
        "" to "All Milestones",
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
        "7d" to "7 Days",
        "30d" to "30 Days"
    )
    val basisOptions = listOf(
        "tp1" to "TP1 (Default)",
        "tp2" to "TP2",
        "tp3" to "TP3",
        "highest_tp" to "Highest TP"
    )
    val notifTypeOptions = listOf(
        "primary" to "Primary Only",
        "all" to "All Messages",
        "duplicates" to "Duplicates",
        "updates" to "Signal Updates"
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = NavyCardElevated,
        contentColor = TextPrimary,
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .testTag("signals_filter_sheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Filter Signals", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                    Text("Narrow down channels, milestones, date range & P&L basis", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                }
                IconButton(onClick = onDismiss, modifier = Modifier.testTag("filter_sheet_close")) {
                    Icon(Icons.Default.Close, contentDescription = "Close filter sheet")
                }
            }

            HorizontalDivider(color = NavyBorder)

            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (channels.isNotEmpty()) {
                    FilterSectionTitle("Channel")
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        FilterChip(
                            selected = filter.channel.isEmpty(),
                            onClick = { onUpdateFilter { it.copy(channel = "") } },
                            label = { Text("All Channels") },
                            modifier = Modifier.testTag("filter_chan_all")
                        )
                        channels.forEach { ch ->
                            FilterChip(
                                selected = filter.channel == ch,
                                onClick = {
                                    onUpdateFilter { it.copy(channel = if (it.channel == ch) "" else ch) }
                                },
                                label = { Text(ch) }
                            )
                        }
                    }
                }

                FilterSectionTitle("Verification / Parse Status")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    statusOptions.forEach { (key, label) ->
                        FilterChip(
                            selected = filter.status == key,
                            onClick = { onUpdateFilter { it.copy(status = key) } },
                            label = { Text(label) },
                            modifier = Modifier.testTag("filter_status_${key.ifEmpty { "all" }}")
                        )
                    }
                }

                FilterSectionTitle("Target / Milestone")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    milestoneOptions.forEach { (key, label) ->
                        FilterChip(
                            selected = filter.tpFilter == key,
                            onClick = { onUpdateFilter { it.copy(tpFilter = key) } },
                            label = { Text(label) },
                            modifier = Modifier.testTag("filter_tp_${key.ifEmpty { "all" }}")
                        )
                    }
                }

                FilterSectionTitle("Date Window")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    datePresets.forEach { (key, label) ->
                        FilterChip(
                            selected = filter.datePreset == key,
                            onClick = { onUpdateFilter { it.copy(datePreset = key) } },
                            label = { Text(label) },
                            modifier = Modifier.testTag("filter_date_$key")
                        )
                    }
                }

                FilterSectionTitle("P&L Evaluation Basis")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    basisOptions.forEach { (key, label) ->
                        FilterChip(
                            selected = filter.pnlBasis == key,
                            onClick = { onUpdateFilter { it.copy(pnlBasis = key) } },
                            label = { Text(label) },
                            modifier = Modifier.testTag("filter_basis_$key")
                        )
                    }
                }

                FilterSectionTitle("Message Type")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    notifTypeOptions.forEach { (key, label) ->
                        FilterChip(
                            selected = filter.notificationFilter == key,
                            onClick = { onUpdateFilter { it.copy(notificationFilter = key) } },
                            label = { Text(label) },
                            modifier = Modifier.testTag("filter_notif_$key")
                        )
                    }
                }
            }

            HorizontalDivider(color = NavyBorder)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onResetAll,
                    modifier = Modifier.testTag("filter_sheet_reset")
                ) {
                    Text("Reset All")
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldAccent, contentColor = OnEmerald),
                    modifier = Modifier.testTag("filter_sheet_done")
                ) {
                    Text("Apply Filters", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun FilterSectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
        color = SignalBlue
    )
}
