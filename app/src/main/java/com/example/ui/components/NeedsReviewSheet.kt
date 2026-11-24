package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import com.example.data.repository.EnrichedSignal
import com.example.data.repository.SignalEditPayload
import com.example.ui.theme.AmberContainer
import com.example.ui.theme.AmberWarn
import com.example.ui.theme.CoralContainer
import com.example.ui.theme.CoralRed
import com.example.ui.theme.EmeraldAccent
import com.example.ui.theme.EmeraldContainer
import com.example.ui.theme.MonoFontFamily
import com.example.ui.theme.NavyBorder
import com.example.ui.theme.NavyCardElevated
import com.example.ui.theme.NavyWell
import com.example.ui.theme.OnEmerald
import com.example.ui.theme.SignalBlue
import com.example.ui.theme.SignalBlueContainer
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

/**
 * Streamlined step-through Needs-Review & Quick-Fix Bottom Sheet.
 * Supports stepping through a queue of needs_review signals or editing any single signal,
 * showing the verbatim raw Telegram message right above smart category-aware inputs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NeedsReviewQuickFixSheet(
    queue: List<EnrichedSignal>,
    isQueueMode: Boolean,
    isBusy: Boolean,
    onDismiss: () -> Unit,
    onReparse: (Long) -> Unit,
    onSave: (signalId: Long, payload: SignalEditPayload, verifyImmediately: Boolean) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var currentIndex by remember(queue.size) { mutableIntStateOf(0) }
    val safeIndex = if (queue.isEmpty()) 0 else currentIndex.coerceIn(0, queue.lastIndex)
    val activeSignal = queue.getOrNull(safeIndex)

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
                .testTag("needs_review_quick_fix_sheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (activeSignal == null) {
                // Queue cleared completion state
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "All caught up",
                        tint = EmeraldAccent,
                        modifier = Modifier.size(48.dp)
                    )
                    Text("All Caught Up", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                    Text(
                        "0 signals need manual review.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                    Spacer(Modifier.height(6.dp))
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldAccent, contentColor = OnEmerald),
                        modifier = Modifier.testTag("review_done_close")
                    ) {
                        Text("Done", fontWeight = FontWeight.Bold)
                    }
                }
                return@Column
            }

            val r = activeSignal.row
            val isOptionsCategory = r.category.equals("Index Options", ignoreCase = true) ||
                r.category.equals("Stock Options", ignoreCase = true) ||
                !r.optionType.isNullOrBlank() ||
                r.strikePrice != null

            // Header bar with queue stepper
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    StatusPill(
                        text = if (isQueueMode) "Review ${safeIndex + 1} / ${queue.size}" else "Quick Fix #${r.id}",
                        bg = if (r.parsingStatus == "needs_review") AmberContainer else SignalBlueContainer,
                        fg = if (r.parsingStatus == "needs_review") AmberWarn else SignalBlue
                    )
                    Text(
                        text = "#${r.id} • ${r.category.orEmpty()}",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isQueueMode && queue.size > 1) {
                        IconButton(
                            onClick = { currentIndex = (safeIndex - 1).coerceAtLeast(0) },
                            enabled = safeIndex > 0,
                            modifier = Modifier.testTag("review_prev_btn")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Previous signal")
                        }
                        IconButton(
                            onClick = { currentIndex = (safeIndex + 1).coerceAtMost(queue.lastIndex) },
                            enabled = safeIndex < queue.lastIndex,
                            modifier = Modifier.testTag("review_next_btn")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Next signal")
                        }
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.testTag("review_close_btn")) {
                        Icon(Icons.Default.Close, contentDescription = "Close quick fix drawer")
                    }
                }
            }

            // Form state keyed on activeSignal (re-initializes cleanly when stepping or re-parsing!)
            var symbol by remember(r.id, r.symbol) { mutableStateOf(r.symbol.orEmpty()) }
            var direction by remember(r.id, r.direction) { mutableStateOf(r.direction.orEmpty().uppercase()) }
            var useMarketNow by remember(r.id, r.entryMode, r.entryPrice) {
                mutableStateOf(r.entryMode == "market_now" && r.entryPrice == null)
            }
            var entry by remember(r.id, r.entryPrice) { mutableStateOf(r.entryPrice?.toString().orEmpty()) }
            var extraEntries by remember(r.id, activeSignal.additionalEntries) {
                mutableStateOf(activeSignal.additionalEntries.joinToString(", "))
            }
            var sl by remember(r.id, r.stopLoss) { mutableStateOf(r.stopLoss?.toString().orEmpty()) }
            var targets by remember(r.id, activeSignal.targets) {
                mutableStateOf(activeSignal.targets.joinToString(", "))
            }
            var optionType by remember(r.id, r.optionType) { mutableStateOf(r.optionType.orEmpty().uppercase()) }
            var strike by remember(r.id, r.strikePrice) { mutableStateOf(r.strikePrice?.toString().orEmpty()) }
            var expiry by remember(r.id, r.expiryDate) { mutableStateOf(r.expiryDate.orEmpty()) }

            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Recessed Raw Telegram Message preview
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(NavyWell)
                        .border(1.dp, NavyBorder, RoundedCornerShape(10.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${r.channel.orEmpty()} • ${formatShortTimestamp(r.notificationTime)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                        if (isEditedSignal(r.parsingExplanation)) {
                            MicroBadge("EDITED", AmberContainer, AmberWarn)
                        }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 120.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = r.message.orEmpty().ifBlank { "(Empty message)" },
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily),
                            color = TextPrimary
                        )
                    }
                    if (!r.parsingExplanation.isNullOrBlank()) {
                        Text(
                            text = "Parser: ${r.parsingExplanation}",
                            style = MaterialTheme.typography.labelSmall,
                            color = AmberWarn
                        )
                    }
                }

                // Symbol + Direction Segmented Selector
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = symbol,
                        onValueChange = { symbol = it.uppercase() },
                        label = { Text("Symbol") },
                        singleLine = true,
                        modifier = Modifier.weight(1f).testTag("review_symbol_input")
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Direction", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            DirectionTogglePill(
                                label = "BUY",
                                selected = direction == "BUY",
                                activeBg = EmeraldContainer,
                                activeFg = EmeraldAccent,
                                onClick = { direction = "BUY" },
                                tag = "review_dir_buy"
                            )
                            DirectionTogglePill(
                                label = "SELL",
                                selected = direction == "SELL",
                                activeBg = CoralContainer,
                                activeFg = CoralRed,
                                onClick = { direction = "SELL" },
                                tag = "review_dir_sell"
                            )
                        }
                    }
                }

                // Entry Price + One-tap "Use Market Now" toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = if (useMarketNow) "" else entry,
                        onValueChange = {
                            useMarketNow = false
                            entry = it
                        },
                        enabled = !useMarketNow,
                        label = { Text(if (useMarketNow) "Entry (Market Now)" else "Entry Price") },
                        placeholder = { Text(if (useMarketNow) "Candle open at signal time" else "e.g. 0.2325") },
                        singleLine = true,
                        modifier = Modifier.weight(1f).testTag("review_entry_input")
                    )
                    FilterChip(
                        selected = useMarketNow,
                        onClick = {
                            useMarketNow = !useMarketNow
                            if (useMarketNow) entry = ""
                        },
                        label = { Text("Market Now") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Bolt,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = SignalBlueContainer,
                            selectedLabelColor = SignalBlue,
                            selectedLeadingIconColor = SignalBlue
                        ),
                        modifier = Modifier.testTag("review_market_now_toggle")
                    )
                }

                // SL + Targets
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = sl,
                        onValueChange = { sl = it },
                        label = { Text("Stop Loss") },
                        singleLine = true,
                        modifier = Modifier.weight(0.9f).testTag("review_sl_input")
                    )
                    OutlinedTextField(
                        value = targets,
                        onValueChange = { targets = it },
                        label = { Text("Targets (comma)") },
                        placeholder = { Text("TP1, TP2, TP3") },
                        singleLine = true,
                        modifier = Modifier.weight(1.3f).testTag("review_targets_input")
                    )
                }

                OutlinedTextField(
                    value = extraEntries,
                    onValueChange = { extraEntries = it },
                    label = { Text("Additional Entries (optional, comma)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("review_extra_entries_input")
                )

                // Category-aware Option fields
                if (isOptionsCategory) {
                    HorizontalDivider(color = NavyBorder)
                    Text("Option Contract Fields", style = MaterialTheme.typography.labelMedium, color = SignalBlue)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            DirectionTogglePill(
                                label = "CE",
                                selected = optionType == "CE",
                                activeBg = EmeraldContainer,
                                activeFg = EmeraldAccent,
                                onClick = { optionType = if (optionType == "CE") "" else "CE" },
                                tag = "review_opt_ce"
                            )
                            DirectionTogglePill(
                                label = "PE",
                                selected = optionType == "PE",
                                activeBg = CoralContainer,
                                activeFg = CoralRed,
                                onClick = { optionType = if (optionType == "PE") "" else "PE" },
                                tag = "review_opt_pe"
                            )
                        }
                        OutlinedTextField(
                            value = strike,
                            onValueChange = { strike = it },
                            label = { Text("Strike") },
                            singleLine = true,
                            modifier = Modifier.weight(1f).testTag("review_strike_input")
                        )
                        OutlinedTextField(
                            value = expiry,
                            onValueChange = { expiry = it.uppercase() },
                            label = { Text("Expiry") },
                            singleLine = true,
                            modifier = Modifier.weight(1f).testTag("review_expiry_input")
                        )
                    }
                }
            }

            HorizontalDivider(color = NavyBorder)

            // Action footer: Auto Re-parse + Save & Verify
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = { onReparse(r.id) },
                    enabled = !isBusy,
                    modifier = Modifier.testTag("review_reparse_btn")
                ) {
                    Icon(Icons.Default.AutoFixHigh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(" Re-parse")
                }

                Spacer(Modifier.weight(1f))

                Button(
                    onClick = {
                        val parsedExtras = extraEntries.split(",", "/", " ").mapNotNull { it.trim().toDoubleOrNull() }
                        val parsedTargets = targets.split(",", "/").mapNotNull { it.trim().toDoubleOrNull() }
                        val entryNum = if (useMarketNow) null else entry.trim().toDoubleOrNull()
                        val resolvedMode = when {
                            useMarketNow -> "market_now"
                            entryNum != null -> "limit"
                            else -> r.entryMode
                        }
                        val payload = SignalEditPayload(
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
                            hasOptionType = isOptionsCategory || !r.optionType.isNullOrBlank(),
                            strikePrice = strike.trim().toDoubleOrNull(),
                            hasStrikePrice = isOptionsCategory || r.strikePrice != null,
                            expiryDate = expiry.trim().uppercase().ifEmpty { null },
                            hasExpiryDate = isOptionsCategory || !r.expiryDate.isNullOrBlank(),
                            entryMode = resolvedMode,
                            manualReviewStatus = "resolved"
                        )
                        onSave(r.id, payload, true)
                        if (isQueueMode && safeIndex < queue.lastIndex) {
                            currentIndex = safeIndex + 1
                        } else if (!isQueueMode) {
                            onDismiss()
                        }
                    },
                    enabled = !isBusy,
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldAccent, contentColor = OnEmerald),
                    modifier = Modifier.testTag("save_signal_edit_button")
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(
                        if (isQueueMode && queue.size > 1) " Save & Next" else " Save & Verify",
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun DirectionTogglePill(
    label: String,
    selected: Boolean,
    activeBg: androidx.compose.ui.graphics.Color,
    activeFg: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    tag: String
) {
    Surface(
        onClick = onClick,
        color = if (selected) activeBg else NavyWell,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, if (selected) activeFg else NavyBorder),
        modifier = Modifier.testTag(tag)
    ) {
        Text(
            text = label,
            color = if (selected) activeFg else TextSecondary,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
        )
    }
}
