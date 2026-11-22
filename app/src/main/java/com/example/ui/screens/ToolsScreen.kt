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
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.components.SectionCard
import com.example.ui.components.StatusPill
import com.example.ui.components.formatShortTimestamp
import com.example.ui.theme.AmberContainer
import com.example.ui.theme.AmberWarn
import com.example.ui.theme.EmeraldAccent
import com.example.ui.theme.EmeraldContainer
import com.example.ui.theme.MonoFontFamily
import com.example.ui.theme.NavyBorder
import com.example.ui.theme.NavyInput
import com.example.ui.theme.TextSecondary
import com.example.ui.viewmodel.MainViewModel
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun ToolsScreen(viewModel: MainViewModel) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Parse & Import", "Integrations", "Notifications")

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTab) {
            tabs.forEachIndexed { idx, title ->
                Tab(
                    selected = selectedTab == idx,
                    onClick = { selectedTab = idx },
                    text = { Text(title) },
                    modifier = Modifier.testTag("tools_tab_$idx")
                )
            }
        }
        when (selectedTab) {
            0 -> ParseAndImportTab(viewModel)
            1 -> IntegrationsTab(viewModel)
            else -> NotificationsLogTab(viewModel)
        }
    }
}

@Composable
private fun ParseAndImportTab(viewModel: MainViewModel) {
    val preview by viewModel.parsePreview.collectAsStateWithLifecycle()
    var message by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("Crypto") }
    var channel by remember { mutableStateOf("Manual") }

    val cats = listOf("Crypto", "Forex", "Indian Options", "Stock Options", "MCX")

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            SectionCard(
                title = "Signal Parser & Manual Import",
                subtitle = "Test parsing safely without saving, or import directly into your local Room database."
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    cats.forEach { c ->
                        FilterChip(
                            selected = category == c,
                            onClick = { category = c },
                            label = { Text(c) }
                        )
                    }
                }
                OutlinedTextField(
                    value = channel,
                    onValueChange = { channel = it },
                    label = { Text("Channel Name (for import)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it },
                    label = { Text("Raw Signal Message") },
                    placeholder = { Text("XAUUSD SELL NOW 4073\nSL 4082\nTP 4065 4058") },
                    modifier = Modifier.fillMaxWidth().testTag("parse_message_input"),
                    minLines = 4
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.previewParse(message, category) },
                        enabled = message.isNotBlank(),
                        modifier = Modifier.weight(1f).testTag("parse_preview_button")
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Preview parse", modifier = Modifier.size(18.dp))
                        Text(" Preview Parse")
                    }
                    FilledTonalButton(
                        onClick = { viewModel.importManualSignal(channel, category, message) },
                        enabled = message.isNotBlank(),
                        modifier = Modifier.weight(1f).testTag("manual_import_button")
                    ) {
                        Icon(Icons.Default.Save, contentDescription = "Import signal", modifier = Modifier.size(18.dp))
                        Text(" Import Signal")
                    }
                }
            }
        }

        if (preview.isNotEmpty()) {
            item {
                SectionCard(title = "Parsed Preview (${preview.size} trade(s))") {
                    preview.forEachIndexed { idx, p ->
                        if (idx > 0) HorizontalDivider(color = NavyBorder)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(NavyInput)
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                "${p.symbol ?: "UNPARSED"} ${p.direction ?: ""} • ${p.status} (${p.confidence})",
                                style = MaterialTheme.typography.titleSmall,
                                color = if (p.status == "parsed") EmeraldAccent else AmberWarn
                            )
                            Text(
                                "Entry: ${p.entryPrice ?: p.entryMode} | Extra: ${p.additionalEntries} | SL: ${p.stopLoss ?: "—"} | TP: ${p.targets}",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily)
                            )
                            if (p.optionType != null || p.strikePrice != null) {
                                Text(
                                    "Option: ${p.strikePrice ?: ""} ${p.optionType ?: ""} ${p.expiryDate ?: ""}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary
                                )
                            }
                            Text(p.explanation, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IntegrationsTab(viewModel: MainViewModel) {
    val integrations by viewModel.integrations.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()

    val byProvider = remember(integrations) { integrations.associateBy { it.provider } }

    var sheetsUrl by remember { mutableStateOf("") }
    var sheetsToken by remember { mutableStateOf("") }
    var syncInterval by remember { mutableStateOf("30") }
    var autoSync by remember { mutableStateOf(false) }

    var binanceBaseUrl by remember { mutableStateOf("https://fapi.binance.com") }
    var twelveApiKey by remember { mutableStateOf("") }
    var twelveBaseUrl by remember { mutableStateOf("https://api.twelvedata.com") }

    var angelApiKey by remember { mutableStateOf("") }
    var angelClientId by remember { mutableStateOf("") }
    var angelPassword by remember { mutableStateOf("") }
    var angelTotp by remember { mutableStateOf("") }

    LaunchedEffect(integrations, settings) {
        byProvider["google_sheets"]?.configJson?.let { raw ->
            try {
                val o = JSONObject(raw)
                if (sheetsUrl.isEmpty()) sheetsUrl = o.optString("apps_script_url", "")
            } catch (_: Exception) {}
        }
        byProvider["binance"]?.configJson?.let { raw ->
            try {
                val o = JSONObject(raw)
                o.optString("base_url", "").takeIf { it.isNotBlank() }?.let { binanceBaseUrl = it }
            } catch (_: Exception) {}
        }
        byProvider["twelve_data"]?.configJson?.let { raw ->
            try {
                val o = JSONObject(raw)
                o.optString("base_url", "").takeIf { it.isNotBlank() }?.let { twelveBaseUrl = it }
            } catch (_: Exception) {}
        }
        syncInterval = settings["sync_interval_seconds"] ?: "30"
        autoSync = (settings["google_sheets_auto_sync"] ?: "false").equals("true", ignoreCase = true)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Google Sheets
        item {
            SectionCard(
                title = "Google Sheets — Apps Script Sync",
                subtitle = "Secrets are encrypted in Android Keystore and never stored in plaintext SQLite."
            ) {
                OutlinedTextField(
                    value = sheetsUrl,
                    onValueChange = { sheetsUrl = it },
                    label = { Text("Apps Script Web App URL (HTTPS)") },
                    modifier = Modifier.fillMaxWidth().testTag("sheets_url_input"),
                    singleLine = true
                )
                OutlinedTextField(
                    value = sheetsToken,
                    onValueChange = { sheetsToken = it },
                    label = { Text("Optional Access Token (leave blank to keep saved)") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = syncInterval,
                        onValueChange = { syncInterval = it },
                        label = { Text("Sync Interval (s)") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    FilterChip(
                        selected = autoSync,
                        onClick = { autoSync = !autoSync },
                        label = { Text(if (autoSync) "Auto-Sync ON" else "Auto-Sync OFF") },
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val cfg = JSONObject().apply {
                                put("apps_script_url", sheetsUrl.trim())
                                put("worksheets", JSONArray(listOf("Crypto", "Indian Options", "Stock Options", "MCX", "Forex")))
                            }
                            val sec = if (sheetsToken.isNotBlank()) mapOf("access_token" to sheetsToken.trim()) else emptyMap()
                            viewModel.saveIntegration("google_sheets", cfg, sec)
                            viewModel.saveSettings(mapOf("sync_interval_seconds" to syncInterval.trim(), "google_sheets_auto_sync" to autoSync.toString()))
                            sheetsToken = ""
                        },
                        modifier = Modifier.weight(1f).testTag("save_sheets_button")
                    ) { Text("Save") }
                    FilledTonalButton(
                        onClick = { viewModel.syncGoogleSheetsNow() },
                        enabled = !isBusy,
                        modifier = Modifier.weight(1f).testTag("sync_sheets_now_button")
                    ) {
                        Icon(Icons.Default.CloudSync, contentDescription = "Sync now", modifier = Modifier.size(16.dp))
                        Text(" Sync Now")
                    }
                }
            }
        }

        // Binance
        item {
            SectionCard(title = "Binance USD-M Futures", subtitle = "Public market data — no API key required.") {
                OutlinedTextField(
                    value = binanceBaseUrl,
                    onValueChange = { binanceBaseUrl = it },
                    label = { Text("Base URL") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.saveIntegration("binance", JSONObject().put("base_url", binanceBaseUrl.trim()), emptyMap()) },
                        modifier = Modifier.weight(1f).testTag("save_binance_button")
                    ) { Text("Save") }
                    FilledTonalButton(
                        onClick = { viewModel.testBinance() },
                        enabled = !isBusy,
                        modifier = Modifier.weight(1f).testTag("test_binance_button")
                    ) { Text("Test Public API") }
                }
            }
        }

        // Twelve Data
        item {
            SectionCard(title = "Twelve Data — Forex", subtitle = "Forex & metals historical candles (XAU/USD, EUR/USD, WTI/USD).") {
                OutlinedTextField(
                    value = twelveApiKey,
                    onValueChange = { twelveApiKey = it },
                    label = { Text("API Key (leave blank to keep saved)") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().testTag("twelve_key_input"),
                    singleLine = true
                )
                OutlinedTextField(
                    value = twelveBaseUrl,
                    onValueChange = { twelveBaseUrl = it },
                    label = { Text("Base URL") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Button(
                    onClick = {
                        val cfg = JSONObject().apply {
                            put("base_url", twelveBaseUrl.trim())
                            put("timezone", "UTC")
                            put("outputsize", 5000)
                        }
                        val sec = if (twelveApiKey.isNotBlank()) mapOf("api_key" to twelveApiKey.trim()) else emptyMap()
                        viewModel.saveIntegration("twelve_data", cfg, sec)
                        twelveApiKey = ""
                    },
                    modifier = Modifier.fillMaxWidth().testTag("save_twelve_button")
                ) { Text("Save Twelve Data") }
            }
        }

        // Angel One
        item {
            SectionCard(title = "Angel One SmartAPI", subtitle = "Indian Options & MCX verification using RFC 6238 TOTP & Scrip Master.") {
                OutlinedTextField(value = angelApiKey, onValueChange = { angelApiKey = it }, label = { Text("API Key") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = angelClientId, onValueChange = { angelClientId = it }, label = { Text("Client ID") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = angelPassword, onValueChange = { angelPassword = it }, label = { Text("Password / PIN") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = angelTotp, onValueChange = { angelTotp = it }, label = { Text("TOTP Secret (Base32)") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val sec = buildMap {
                                if (angelApiKey.isNotBlank()) put("api_key", angelApiKey.trim())
                                if (angelClientId.isNotBlank()) put("client_id", angelClientId.trim())
                                if (angelPassword.isNotBlank()) put("password", angelPassword.trim())
                                if (angelTotp.isNotBlank()) put("totp_secret", angelTotp.trim())
                            }
                            viewModel.saveIntegration("angel_one", JSONObject(), sec)
                            angelApiKey = ""; angelClientId = ""; angelPassword = ""; angelTotp = ""
                        },
                        modifier = Modifier.weight(1f).testTag("save_angel_button")
                    ) { Text("Save") }
                    FilledTonalButton(
                        onClick = { viewModel.testAngelOne() },
                        enabled = !isBusy,
                        modifier = Modifier.weight(1f).testTag("test_angel_button")
                    ) { Text("Auth Test") }
                    FilledTonalButton(
                        onClick = { viewModel.refreshAngelMaster() },
                        enabled = !isBusy,
                        modifier = Modifier.weight(1f).testTag("refresh_angel_master_button")
                    ) { Text("Master") }
                }
            }
        }
    }
}

@Composable
private fun NotificationsLogTab(viewModel: MainViewModel) {
    val rows by viewModel.notifications.collectAsStateWithLifecycle()
    val syncs by viewModel.syncLogs.collectAsStateWithLifecycle()
    val filter by viewModel.notifFilter.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SectionCard(
                title = "Notification Log (${rows.size})",
                action = {
                    FilledTonalButton(onClick = { viewModel.refreshNotifications() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh notifications", modifier = Modifier.size(16.dp))
                    }
                }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("" to "All", "needs_review" to "Needs Review", "parsed" to "Parsed", "unparsed" to "Unparsed").forEach { (k, lbl) ->
                        FilterChip(
                            selected = filter.parsingStatus == k,
                            onClick = { viewModel.updateNotificationFilter { it.copy(parsingStatus = k) } },
                            label = { Text(lbl) }
                        )
                    }
                }
            }
        }

        items(rows, key = { "${it.id}_${it.signalId ?: 0}" }) { n ->
            SectionCard {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("#${n.id} • ${n.channel ?: "—"} (${n.category ?: "—"})", style = MaterialTheme.typography.titleSmall)
                    StatusPill(
                        text = n.parsingStatus ?: "unparsed",
                        bg = if (n.parsingStatus == "parsed") EmeraldContainer else AmberContainer,
                        fg = if (n.parsingStatus == "parsed") EmeraldAccent else AmberWarn
                    )
                }
                Text(formatShortTimestamp(n.notificationTime ?: n.receivedAt), style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                Text(n.message.orEmpty(), style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily))
                if (!n.parsingExplanation.isNullOrBlank()) {
                    Text(n.parsingExplanation, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                }
            }
        }

        if (syncs.isNotEmpty()) {
            item {
                SectionCard(title = "Recent Sync History") {
                    syncs.take(10).forEachIndexed { idx, s ->
                        if (idx > 0) HorizontalDivider(color = NavyBorder)
                        Text(
                            "${formatShortTimestamp(s.startedAt)} • ${s.status.uppercase()} • +${s.importedRows} imp / ${s.duplicateRows} dup / ${s.failedRows} fail",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (s.status == "success") EmeraldAccent else AmberWarn
                        )
                        if (!s.detail.isNullOrBlank()) {
                            Text(s.detail, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                        }
                    }
                }
            }
        }
    }
}
