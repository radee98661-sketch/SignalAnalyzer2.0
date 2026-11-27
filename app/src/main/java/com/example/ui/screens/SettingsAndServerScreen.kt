package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VpnLock
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.service.NetworkUtils
import com.example.ui.components.BackgroundHealthCard
import com.example.ui.components.QrCodeCard
import com.example.ui.components.SectionCard
import com.example.ui.components.StatusPill
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
import com.example.ui.theme.SignalBlue
import com.example.ui.theme.TextSecondary
import com.example.ui.viewmodel.MainViewModel

@Composable
fun SettingsAndServerScreen(
    viewModel: MainViewModel,
    onRequestNotificationPermission: (() -> Unit) -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Server & LAN", "Settings & Rules", "Reports & Logs")

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTab) {
            tabs.forEachIndexed { idx, title ->
                Tab(
                    selected = selectedTab == idx,
                    onClick = { selectedTab = idx },
                    text = { Text(title) },
                    modifier = Modifier.testTag("settings_tab_$idx")
                )
            }
        }
        when (selectedTab) {
            0 -> ServerLanTab(viewModel, onRequestNotificationPermission)
            1 -> SettingsAndRulesTab(viewModel)
            else -> ReportsImportLogsTab(viewModel)
        }
    }
}

@Composable
private fun ServerLanTab(
    viewModel: MainViewModel,
    onRequestNotificationPermission: (() -> Unit) -> Unit
) {
    val ctx = LocalContext.current
    val status by viewModel.serviceStatus.collectAsStateWithLifecycle()
    val bgHealth by viewModel.backgroundHealth.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val lanToken by viewModel.lanToken.collectAsStateWithLifecycle()
    val lanNetwork by viewModel.lanNetwork.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.refreshBackgroundHealth()
        viewModel.refreshLanNetwork()
    }

    var portText by remember { mutableStateOf("8765") }
    LaunchedEffect(settings) {
        settings["lan_port"]?.takeIf { it.isNotBlank() }?.let { portText = it }
    }
    val portInt = (portText.toIntOrNull() ?: 8765).coerceIn(1024, 65535)
    val effectivePort = if (status.running && status.lanDashboardEnabled) status.port else portInt
    val liveLanIp = if (status.running && status.lanIp != "127.0.0.1") status.lanIp else lanNetwork.primaryIp
    val liveIface = if (status.running && status.lanInterfaceName != null) status.lanInterfaceName else lanNetwork.interfaceName
    val liveAllIps = if (status.running && status.allLanIps.isNotEmpty()) status.allLanIps else lanNetwork.allLanIps
    val liveOnWifi = if (status.running) status.onWifi else lanNetwork.onWifi
    val liveVpnActive = if (status.running) status.vpnActive else lanNetwork.vpnActive
    val liveVpnDetail = if (status.running) status.vpnDetail else lanNetwork.vpnDetail
    val liveLanUrl = "http://$liveLanIp:$effectivePort"
    val ignoringBattery = remember(status.running, bgHealth.ignoringBatteryOptimizations) {
        NetworkUtils.isIgnoringBatteryOptimizations(ctx)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            SectionCard(
                title = "Dedicated Processing & Embedded LAN Server",
                subtitle = "Runs inside a foreground dataSync service when enabled. Off by default until you turn it on.",
                action = {
                    StatusPill(
                        text = if (status.running) "ACTIVE" else "STOPPED",
                        bg = if (status.running) EmeraldContainer else AmberContainer,
                        fg = if (status.running) EmeraldAccent else AmberWarn
                    )
                }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Dedicated Processing Mode", style = MaterialTheme.typography.titleSmall)
                        Text("10-second verification scheduler + automatic Apps Script sync loop", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    }
                    Switch(
                        checked = status.dedicatedProcessingEnabled,
                        onCheckedChange = { enable ->
                            onRequestNotificationPermission {
                                viewModel.updateServiceState(enable, status.lanDashboardEnabled, portInt)
                            }
                        },
                        modifier = Modifier.testTag("toggle_dedicated_processing")
                    )
                }

                HorizontalDivider(color = NavyBorder)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("LAN Browser Dashboard (Wi-Fi)", style = MaterialTheme.typography.titleSmall)
                        Text("Binds 0.0.0.0:$portInt — allows RFC1918 private LAN browsers only", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    }
                    Switch(
                        checked = status.lanDashboardEnabled,
                        onCheckedChange = { enable ->
                            onRequestNotificationPermission {
                                viewModel.updateServiceState(status.dedicatedProcessingEnabled, enable, portInt)
                            }
                        },
                        modifier = Modifier.testTag("toggle_lan_dashboard")
                    )
                }

                OutlinedTextField(
                    value = portText,
                    onValueChange = { portText = it.filter { ch -> ch.isDigit() }.take(5) },
                    label = { Text("LAN Server Port (1024–65535)") },
                    modifier = Modifier.fillMaxWidth().testTag("lan_port_input"),
                    singleLine = true
                )

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = NavyCardElevated,
                    border = BorderStroke(1.dp, if (status.lanDashboardEnabled && liveLanIp != "127.0.0.1") EmeraldAccent.copy(alpha = 0.45f) else NavyBorder),
                    modifier = Modifier.fillMaxWidth().testTag("live_lan_url_card")
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    Icons.Default.Lan,
                                    contentDescription = "Live LAN address",
                                    tint = if (liveLanIp != "127.0.0.1") EmeraldAccent else AmberWarn,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = if (liveIface != null) "Live LAN URL ($liveIface)" else "Live LAN URL",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = TextSecondary
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = {
                                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                        cm?.setPrimaryClip(ClipData.newPlainText("LAN URL", liveLanUrl))
                                        viewModel.setStatusMessage("Copied $liveLanUrl")
                                    },
                                    modifier = Modifier.testTag("copy_live_lan_url_button")
                                ) {
                                    Icon(
                                        Icons.Default.ContentCopy,
                                        contentDescription = "Copy live LAN URL",
                                        tint = SignalBlue,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        val fresh = viewModel.refreshLanNetwork()
                                        val vpnNote = if (fresh.vpnActive) " • VPN active" else ""
                                        viewModel.setStatusMessage("LAN IP: http://${fresh.primaryIp}:$effectivePort$vpnNote")
                                    },
                                    modifier = Modifier.testTag("refresh_live_lan_ip_button")
                                ) {
                                    Icon(
                                        Icons.Default.Refresh,
                                        contentDescription = "Refresh LAN IP",
                                        tint = TextSecondary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                        Text(
                            text = liveLanUrl,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontFamily = MonoFontFamily,
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = if (liveLanIp != "127.0.0.1") EmeraldAccent else AmberWarn,
                            modifier = Modifier.testTag("live_lan_url_text")
                        )
                        val extraIps = liveAllIps.filter { it.second != liveLanIp }
                        if (extraIps.isNotEmpty()) {
                            Text(
                                text = "Other LAN IPs: " + extraIps.joinToString(" • ") { (iface, ip) -> "$iface: http://$ip:$effectivePort" },
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                                color = TextSecondary
                            )
                        }
                    }
                }

                AnimatedVisibility(visible = liveVpnActive) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = AmberContainer,
                        border = BorderStroke(1.dp, AmberWarn.copy(alpha = 0.6f)),
                        modifier = Modifier.fillMaxWidth().testTag("active_vpn_warning_banner")
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                Icons.Default.VpnLock,
                                contentDescription = "Active VPN warning",
                                tint = AmberWarn,
                                modifier = Modifier.size(20.dp)
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = "Active VPN / Local VPN Tunnel Detected",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = AmberWarn
                                )
                                Text(
                                    text = "${liveVpnDetail ?: "VPN tunnel active"}. VPNs and local VPN ad-blockers/firewalls can block Wi-Fi LAN browsers (ERR_CONNECTION_TIMED_OUT). Pause the VPN or allow Local LAN / exclude Signal Analyzer.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }

                AnimatedVisibility(visible = status.lanDashboardEnabled && (!liveOnWifi || liveLanIp == "127.0.0.1")) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = CoralContainer,
                        border = BorderStroke(1.dp, CoralRed.copy(alpha = 0.55f)),
                        modifier = Modifier.fillMaxWidth().testTag("no_wifi_lan_warning_banner")
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                Icons.Default.WifiOff,
                                contentDescription = "No Wi-Fi LAN connection",
                                tint = CoralRed,
                                modifier = Modifier.size(20.dp)
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = "No Wi-Fi LAN Address Detected",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = CoralRed
                                )
                                Text(
                                    text = "Connect this phone and your desktop to the same Wi-Fi network so the LAN dashboard gets a reachable 192.168.x.x / 10.x.x.x address.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }

                Text(
                    "Status: ${if (status.running) "Running on ${status.bindHost}:${status.port}" else "Idle (local-only mode)"} • WS Clients: ${status.activeWsClients}",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                    color = SignalBlue
                )
                if (status.lastHeartbeatAt != null) {
                    Text(
                        "Last watchdog heartbeat: ${formatShortTimestamp(status.lastHeartbeatAt)}" +
                            if (status.isSchedulerBusy) " • Verifying signals…" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = EmeraldAccent
                    )
                }
                if (status.lastSchedulerTickAt != null) {
                    val durSuffix = if (status.lastTickDurationMs > 0L) " (${status.lastTickDurationMs}ms)" else ""
                    Text(
                        "Last scheduler tick: ${formatShortTimestamp(status.lastSchedulerTickAt)}$durSuffix",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
                if (status.lastError != null) {
                    Text("Error: ${status.lastError}", style = MaterialTheme.typography.bodySmall, color = CoralRed)
                }
            }
        }

        item {
            BackgroundHealthCard(
                snapshot = bgHealth,
                isServiceRunning = status.running,
                onRefresh = { viewModel.refreshBackgroundHealth() },
                onClearHistory = { viewModel.clearBackgroundHealthHistory() }
            )
        }

        item {
            QrCodeCard(
                url = liveLanUrl,
                bearerToken = lanToken,
                onRotateToken = { viewModel.rotateLanToken() },
                onCopiedMessage = { viewModel.setStatusMessage(it) }
            )
        }

        item {
            SectionCard(
                title = "Security & Android Background Honesty",
                subtitle = "Important operational guidance for running a LAN dashboard on Android."
            ) {
                Text(
                    "• LAN clients authenticate with the per-install Bearer Token (never in URLs or localStorage). Public/non-RFC1918 IPs are blocked by default.\n" +
                        "• Plain HTTP on a trusted home/office Wi-Fi LAN only — do NOT expose this port to the public internet or untrusted public Wi-Fi.\n" +
                        "• Android Doze and OEM battery managers can throttle background loops when unplugged. For uninterrupted processing, disable battery optimization for Signal Analyzer and keep Dedicated Processing ON.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                OutlinedButton(
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            runCatching { ctx.startActivity(intent) }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("battery_opt_button")
                ) {
                    Icon(Icons.Default.BatteryAlert, contentDescription = "Battery optimization settings", modifier = Modifier.size(18.dp))
                    Text(if (ignoringBattery) " Battery Optimization: Unrestricted" else " Open Battery Optimization Settings")
                }
            }
        }
    }
}

@Composable
private fun SettingsAndRulesTab(viewModel: MainViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val rules by viewModel.defaultSlRules.collectAsStateWithLifecycle()

    var initialWait by remember { mutableStateOf("15") }
    var repeatMins by remember { mutableStateOf("15") }
    var fastPhaseMins by remember { mutableStateOf("60") }
    var extendedIntervalMins by remember { mutableStateOf("60") }
    var expiryHours by remember { mutableStateOf("168") }
    var dupWindow by remember { mutableStateOf("10") }
    var tolerance by remember { mutableStateOf("0") }
    var autoVerify by remember { mutableStateOf(true) }

    LaunchedEffect(settings) {
        initialWait = settings["verification_initial_wait_minutes"] ?: "15"
        repeatMins = settings["verification_repeat_minutes"] ?: "15"
        fastPhaseMins = settings["verification_fast_phase_minutes"] ?: "60"
        extendedIntervalMins = settings["verification_extended_interval_minutes"] ?: "60"
        expiryHours = settings["verification_expiry_hours"] ?: "168"
        dupWindow = settings["duplicate_window_minutes"] ?: "10"
        tolerance = settings["entry_tolerance"] ?: "0"
        autoVerify = (settings["automatic_verification_enabled"] ?: "true").equals("true", ignoreCase = true)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            SectionCard(
                title = "Verification & Two-Phase Scheduler",
                subtitle = "Zero-manual self-monitoring: idle wait first, then fast checks during the first hour, then hourly checks until closed."
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = initialWait, onValueChange = { initialWait = it }, label = { Text("Idle Wait (m)") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(value = repeatMins, onValueChange = { repeatMins = it }, label = { Text("Fast Interval (m)") }, modifier = Modifier.weight(1f), singleLine = true)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = fastPhaseMins, onValueChange = { fastPhaseMins = it }, label = { Text("Fast Phase Window (m)") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(value = extendedIntervalMins, onValueChange = { extendedIntervalMins = it }, label = { Text("Hourly Interval (m)") }, modifier = Modifier.weight(1f), singleLine = true)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = expiryHours, onValueChange = { expiryHours = it }, label = { Text("Expiry (hrs)") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(value = dupWindow, onValueChange = { dupWindow = it }, label = { Text("Dup Window (m)") }, modifier = Modifier.weight(1f), singleLine = true)
                }
                OutlinedTextField(value = tolerance, onValueChange = { tolerance = it }, label = { Text("Entry Tolerance") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Automatic Self-Monitoring Enabled", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = autoVerify, onCheckedChange = { autoVerify = it })
                }
                Button(
                    onClick = {
                        viewModel.saveSettings(
                            mapOf(
                                "verification_initial_wait_minutes" to initialWait.trim(),
                                "verification_repeat_minutes" to repeatMins.trim(),
                                "verification_fast_phase_minutes" to fastPhaseMins.trim(),
                                "verification_extended_interval_minutes" to extendedIntervalMins.trim(),
                                "verification_expiry_hours" to expiryHours.trim(),
                                "duplicate_window_minutes" to dupWindow.trim(),
                                "entry_tolerance" to tolerance.trim(),
                                "automatic_verification_enabled" to autoVerify.toString()
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth().testTag("save_settings_button")
                ) { Text("Save Settings") }
            }
        }

        item {
            SectionCard(
                title = "Default SL, TP1–TP3 & Sizing Rules",
                subtitle = "Category fallbacks & per-symbol overrides seeded identically to legacy Flask. Full table also editable in LAN Dashboard."
            ) {
                rules.forEach { (catKey, catRule) ->
                    HorizontalDivider(color = NavyBorder)
                    val displayCat = when (catKey) {
                        "OPTIONS" -> "Index Options"
                        "STOCK_OPTIONS" -> "Stock Options"
                        "MCX" -> "MCX"
                        "FOREX" -> "Forex"
                        "CRYPTO" -> "Crypto"
                        else -> catKey
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(displayCat, style = MaterialTheme.typography.titleSmall, color = EmeraldAccent)
                        Text(
                            "Fallback SL: ${catRule.categoryDefault ?: "—"} | TP1/2/3: ${catRule.tp1 ?: "—"}/${catRule.tp2 ?: "—"}/${catRule.tp3 ?: "—"} | " +
                                if (catKey == "CRYPTO") "USD: $${catRule.usdAmount}" else "Lots: ${catRule.lots} × ${catRule.lotSize ?: "Auto"}",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily)
                        )
                        Text(
                            "Overrides (${catRule.symbols.size}): ${catRule.symbols.keys.take(8).joinToString(", ")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
                FilledTonalButton(
                    onClick = { viewModel.applyDefaultSlMissing() },
                    modifier = Modifier.fillMaxWidth().testTag("apply_default_sl_button")
                ) {
                    Text("Apply Missing Default SL & TPs to Existing Signals")
                }
            }
        }
    }
}

@Composable
private fun ReportsImportLogsTab(viewModel: MainViewModel) {
    val ctx = LocalContext.current
    val logs by viewModel.systemLogs.collectAsStateWithLifecycle()
    val importReport by viewModel.legacyImportReport.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()

    val legacyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) viewModel.importLegacySqlite(uri)
    }

    fun shareCsv(kind: String) {
        viewModel.exportCsvToCache(kind) { file ->
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_SUBJECT, file.name)
                putExtra(Intent.EXTRA_TEXT, file.readText().take(50_000))
            }
            runCatching { ctx.startActivity(Intent.createChooser(shareIntent, "Share ${file.name}")) }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            SectionCard(title = "Reports, CSV Export & Local Backup") {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { shareCsv("signals") }, modifier = Modifier.weight(1f).testTag("export_signals_csv")) {
                        Icon(Icons.Default.Share, contentDescription = "Export signals CSV", modifier = Modifier.size(16.dp))
                        Text(" Signals CSV")
                    }
                    FilledTonalButton(onClick = { shareCsv("channels") }, modifier = Modifier.weight(1f).testTag("export_channels_csv")) {
                        Text("Channels CSV")
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { shareCsv("raw") }, modifier = Modifier.weight(1f).testTag("export_raw_csv")) {
                        Text("Raw CSV")
                    }
                    Button(onClick = { viewModel.createBackup() }, modifier = Modifier.weight(1f).testTag("create_backup_button")) {
                        Icon(Icons.Default.Backup, contentDescription = "Create local backup", modifier = Modifier.size(16.dp))
                        Text(" Backup DB")
                    }
                }
            }
        }

        item {
            SectionCard(
                title = "Opt-In Legacy SQLite Importer",
                subtitle = "Import an existing Python Flask signals.sqlite3 file safely inside a transaction. Plaintext API secrets are never imported; re-enter them in Integrations."
            ) {
                Button(
                    onClick = { legacyPicker.launch(arrayOf("*/*")) },
                    enabled = !isBusy,
                    modifier = Modifier.fillMaxWidth().testTag("import_legacy_sqlite_button")
                ) {
                    Icon(Icons.Default.FileUpload, contentDescription = "Pick legacy SQLite file", modifier = Modifier.size(18.dp))
                    Text(" Select Legacy signals.sqlite3")
                }
                importReport?.let { rep ->
                    Text(
                        rep.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (rep.success) EmeraldAccent else CoralRed
                    )
                }
            }
        }

        item {
            SectionCard(
                title = "System Logs (${logs.size})",
                action = {
                    FilledTonalButton(onClick = { viewModel.refreshSystemLogs() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh logs", modifier = Modifier.size(16.dp))
                    }
                }
            ) {
                if (logs.isEmpty()) {
                    Text("No logs yet.", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                }
            }
        }

        items(logs.take(100), key = { it.id }) { log ->
            SectionCard {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        "${log.severity} • ${log.source}",
                        style = MaterialTheme.typography.labelSmall,
                        color = when (log.severity) {
                            "ERROR" -> CoralRed
                            "WARNING" -> AmberWarn
                            else -> EmeraldAccent
                        }
                    )
                    Text(formatShortTimestamp(log.createdAt), style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                }
                Text(log.message, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
