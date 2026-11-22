package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CandlestickChart
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.screens.MarketAndAnalyticsScreen
import com.example.ui.screens.OverviewScreen
import com.example.ui.screens.SettingsAndServerScreen
import com.example.ui.screens.SignalsScreen
import com.example.ui.screens.ToolsScreen
import com.example.ui.theme.NavySidebar
import com.example.ui.theme.SignalAnalyzerTheme
import com.example.ui.viewmodel.MainViewModel

private data class NavDestination(
    val route: String,
    val label: String,
    val icon: ImageVector
)

private val TopDestinations = listOf(
    NavDestination("overview", "Overview", Icons.Default.Dashboard),
    NavDestination("signals", "Signals", Icons.Default.CandlestickChart),
    NavDestination("markets", "Markets", Icons.Default.Analytics),
    NavDestination("tools", "Tools", Icons.Default.Build),
    NavDestination("server", "Server", Icons.Default.Settings)
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SignalAnalyzerTheme {
                SignalAnalyzerRoot()
            }
        }
    }
}

@Composable
fun SignalAnalyzerRoot(viewModel: MainViewModel = viewModel()) {
    val ctx = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val statusMsg by viewModel.statusMessage.collectAsStateWithLifecycle()
    var currentIndex by rememberSaveable { mutableIntStateOf(0) }
    var pendingAfterNotifPerm by remember { mutableStateOf<(() -> Unit)?>(null) }

    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Even if denied, service can run (notification visibility is OS-managed)
        pendingAfterNotifPerm?.invoke()
        pendingAfterNotifPerm = null
    }

    fun requestNotifPermissionThen(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingAfterNotifPerm = action
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            action()
        }
    }

    LaunchedEffect(statusMsg) {
        val msg = statusMsg ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.clearStatusMessage()
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 600.dp

        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                if (!useRail) {
                    NavigationBar(containerColor = NavySidebar) {
                        TopDestinations.forEachIndexed { idx, dest ->
                            NavigationBarItem(
                                selected = currentIndex == idx,
                                onClick = { currentIndex = idx },
                                icon = { Icon(dest.icon, contentDescription = dest.label) },
                                label = { Text(dest.label) },
                                modifier = Modifier.testTag("nav_${dest.route}")
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                if (useRail) {
                    NavigationRail(containerColor = NavySidebar) {
                        TopDestinations.forEachIndexed { idx, dest ->
                            NavigationRailItem(
                                selected = currentIndex == idx,
                                onClick = { currentIndex = idx },
                                icon = { Icon(dest.icon, contentDescription = dest.label) },
                                label = { Text(dest.label) },
                                modifier = Modifier.testTag("rail_${dest.route}")
                            )
                        }
                    }
                }
                Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                    when (currentIndex) {
                        0 -> OverviewScreen(
                            viewModel = viewModel,
                            onNavigateToServer = { currentIndex = 4 },
                            onNavigateToSignals = { currentIndex = 1 },
                            onNavigateToMarkets = { currentIndex = 2 }
                        )
                        1 -> SignalsScreen(
                            viewModel = viewModel,
                            onNavigateToMarkets = { currentIndex = 2 }
                        )
                        2 -> MarketAndAnalyticsScreen(viewModel = viewModel)
                        3 -> ToolsScreen(viewModel = viewModel)
                        else -> SettingsAndServerScreen(
                            viewModel = viewModel,
                            onRequestNotificationPermission = ::requestNotifPermissionThen
                        )
                    }
                }
            }
        }
    }
}
