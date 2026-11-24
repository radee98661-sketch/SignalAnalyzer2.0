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
import androidx.compose.material3.OutlinedButton
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
import com.example.ui.theme.NavyCardElevated
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

    val cats = listOf("Index Options", "Stock Options", "MCX", "Forex", "Crypto")

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
    val secretFields by viewModel.integrationSecretFields.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()

    val byProvider = remember(integrations) { integrations.associateBy { it.provider } }
    val twelveFields = secretFields["twelve_data"].orEmpty()
    val angelFields = secretFields["angel_one"].orEmpty()
    val hasTwelveBackup = "backup_api_key" in twelveFields
    val hasAngelBackup = listOf("backup_api_key", "backup_client_id", "backup_password", "backup_totp_secret").all { it in angelFields }

    var sheetsUrl by remember { mutableStateOf("") }
    var sheetsToken by remember { mutableStateOf("") }
    var syncInterval by remember { mutableStateOf("30") }
    var autoSync by remember { mutableStateOf(false) }

    var binanceBaseUrl by remember { mutableStateOf("https://fapi.binance.com") }
    var twelveApiKey by remember { mutableStateOf("") }
    var twelveBackupApiKey by remember { mutableStateOf("") }
    var twelveBaseUrl by remember { mutableStateOf("https://api.twelvedata.com") }

    var angelApiKey by remember { mutableStateOf("") }
    var angelClientId by remember { mutableStateOf("") }
    var angelPassword by remember { mutableStateOf("") }
    var angelTotp by remember { mutableStateOf("") }
    var angelBackupApiKey by remember { mutableStateOf("") }
    var angelBackupClientId by remember { mutableStateOf("") }
    var angelBackupPassword by remember { mutableStateOf("") }
    var angelBackupTotp by remember { mutableStateOf("") }

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
                                put("worksheets", JSONArray(listOf("Index Options", "Stock Options", "MCX", "Forex", "Crypto")))
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
            SectionCard(
                title = "Twelve Data — Forex",
                subtitle = "Forex & metals historical candles (XAU/USD, EUR/USD, WTI/USD). Automatically routes to Backup API key when Primary hits daily/minute limit.",
                action = {
                    if (hasTwelveBackup) {
                        StatusPill("BACKUP READY", EmeraldContainer, EmeraldAccent)
                    } else {
                        StatusPill("1 KEY", NavyCardElevated, TextSecondary)
                    }
                }
            ) {
                OutlinedTextField(
                    value = twelveApiKey,
                    onValueChange = { twelveApiKey = it },
                    label = { Text(if ("api_key" in twelveFields) "Primary API Key (saved — leave blank to keep)" else "Primary API Key") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().testTag("twelve_key_input"),
                    singleLine = true
                )
                OutlinedTextField(
                    value = twelveBackupApiKey,
                    onValueChange = { twelveBackupApiKey = it },
                    label = { Text(if (hasTwelveBackup) "Secondary / Backup API Key (saved — leave blank to keep)" else "Secondary / Backup API Key (Auto-Failover)") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().testTag("twelve_backup_key_input"),
                    singleLine = true
                )
                OutlinedTextField(
                    value = twelveBaseUrl,
                    onValueChange = { twelveBaseUrl = it },
                    label = { Text("Base URL") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val cfg = JSONObject().apply {
                                put("base_url", twelveBaseUrl.trim())
                                put("timezone", "UTC")
                                put("outputsize", 5000)
                            }
                            val sec = buildMap {
                                if (twelveApiKey.isNotBlank()) put("api_key", twelveApiKey.trim())
                                if (twelveBackupApiKey.isNotBlank()) put("backup_api_key", twelveBackupApiKey.trim())
                            }
                            viewModel.saveIntegration("twelve_data", cfg, sec)
                            twelveApiKey = ""
                            twelveBackupApiKey = ""
                        },
                        modifier = Modifier.weight(1f).testTag("save_twelve_button")
                    ) { Text("Save") }
                    FilledTonalButton(
                        onClick = { viewModel.testTwelveData("primary") },
                        enabled = !isBusy,
                        modifier = Modifier.weight(1f).testTag("test_twelve_primary_button")
                    ) { Text("Test Primary") }
                }
                if (hasTwelveBackup) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            onClick = { viewModel.testTwelveData("backup") },
                            enabled = !isBusy,
                            modifier = Modifier.weight(1f).testTag("test_twelve_backup_button")
                        ) { Text("Test Backup") }
                        OutlinedButton(
                            onClick = { viewModel.clearBackupCredentials("twelve_data") },
                            enabled = !isBusy,
                            modifier = Modifier.weight(1f).testTag("clear_twelve_backup_button")
                        ) { Text("Clear Backup") }
                    }
                }
            }
        }

        // Angel One
        item {
            SectionCard(
                title = "Angel One SmartAPI",
                subtitle = "Index Options, Stock Options & MCX verification. Automatically fails over to Backup account on rate limit or auth error.",
                action = {
                    if (hasAngelBackup) {
                        StatusPill("BACKUP READY", EmeraldContainer, EmeraldAccent)
                    } else {
                        StatusPill("PRIMARY ONLY", NavyCardElevated, TextSecondary)
                    }
                }
            ) {
                Text("Primary Account", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                OutlinedTextField(value = angelApiKey, onValueChange = { angelApiKey = it }, label = { Text(if ("api_key" in angelFields) "Primary API Key (saved)" else "Primary API Key") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = angelClientId, onValueChange = { angelClientId = it }, label = { Text(if ("client_id" in angelFields) "Primary Client ID (saved)" else "Primary Client ID") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = angelPassword, onValueChange = { angelPassword = it }, label = { Text(if ("password" in angelFields) "Primary Password / PIN (saved)" else "Primary Password / PIN") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = angelTotp, onValueChange = { angelTotp = it }, label = { Text(if ("totp_secret" in angelFields) "Primary TOTP Secret (saved)" else "Primary TOTP Secret (Base32)") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true)

                HorizontalDivider(color = NavyBorder)
                Text("Secondary / Backup Account (Auto-Failover)", style = MaterialTheme.typography.labelMedium, color = EmeraldAccent)
                OutlinedTextField(value = angelBackupApiKey, onValueChange = { angelBackupApiKey = it }, label = { Text(if ("backup_api_key" in angelFields) "Backup API Key (saved)" else "Backup API Key") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().testTag("angel_backup_key_input"), singleLine = true)
                OutlinedTextField(value = angelBackupClientId, onValueChange = { angelBackupClientId = it }, label = { Text(if ("backup_client_id" in angelFields) "Backup Client ID (saved)" else "Backup Client ID") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = angelBackupPassword, onValueChange = { angelBackupPassword = it }, label = { Text(if ("backup_password" in angelFields) "Backup Password / PIN (saved)" else "Backup Password / PIN") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = angelBackupTotp, onValueChange = { angelBackupTotp = it }, label = { Text(if ("backup_totp_secret" in angelFields) "Backup TOTP Secret (saved)" else "Backup TOTP Secret (Base32)") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true)

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val sec = buildMap {
                                if (angelApiKey.isNotBlank()) put("api_key", angelApiKey.trim())
                                if (angelClientId.isNotBlank()) put("client_id", angelClientId.trim())
                                if (angelPassword.isNotBlank()) put("password", angelPassword.trim())
                                if (angelTotp.isNotBlank()) put("totp_secret", angelTotp.trim())
                                if (angelBackupApiKey.isNotBlank()) put("backup_api_key", angelBackupApiKey.trim())
                                if (angelBackupClientId.isNotBlank()) put("backup_client_id", angelBackupClientId.trim())
                                if (angelBackupPassword.isNotBlank()) put("backup_password", angelBackupPassword.trim())
                                if (angelBackupTotp.isNotBlank()) put("backup_totp_secret", angelBackupTotp.trim())
                            }
                            viewModel.saveIntegration("angel_one", JSONObject(), sec)
                            angelApiKey = ""; angelClientId = ""; angelPassword = ""; angelTotp = ""
                            angelBackupApiKey = ""; angelBackupClientId = ""; angelBackupPassword = ""; angelBackupTotp = ""
                        },
                        modifier = Modifier.weight(1f).testTag("save_angel_button")
                    ) { Text("Save") }
                    FilledTonalButton(
                        onClick = { viewModel.testAngelOne("primary") },
                        enabled = !isBusy,
                        modifier = Modifier.weight(1f).testTag("test_angel_button")
                    ) { Text("Test Primary") }
                    FilledTonalButton(
                        onClick = { viewModel.refreshAngelMaster() },
                        enabled = !isBusy,
                        modifier = Modifier.weight(1f).testTag("refresh_angel_master_button")
                    ) { Text("Master") }
                }
                if (hasAngelBackup) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            onClick = { viewModel.testAngelOne("backup") },
                            enabled = !isBusy,
                            modifier = Modifier.weight(1f).testTag("test_angel_backup_button")
                        ) { Text("Test Backup") }
                        OutlinedButton(
                            onClick = { viewModel.clearBackupCredentials("angel_one") },
                            enabled = !isBusy,
                            modifier = Modifier.weight(1f).testTag("clear_angel_backup_button")
                        ) { Text("Clear Backup") }
                    }
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
