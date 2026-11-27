package com.example

import android.app.Application
import com.example.data.local.AndroidKeystoreSecretStore
import com.example.data.local.AppDatabase
import com.example.data.local.LegacySqliteImporter
import com.example.data.local.SecretStore
import com.example.data.provider.GoogleSheetsSyncClient
import com.example.data.provider.OkHttpMarketDataProviders
import com.example.data.repository.EventBus
import com.example.data.repository.SignalIngestionService
import com.example.data.repository.SignalRepository
import com.example.data.repository.VerificationService
import com.example.server.LanAuthManager
import com.example.server.LanDashboardServer
import com.example.server.ServerRuntimeInfo
import com.example.service.BackgroundHealthTracker
import com.example.service.ReconciliationWorker
import com.example.service.SignalProcessingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AppContainer(app: Application) {
    val db: AppDatabase = AppDatabase.getInstance(app)
    val secretStore: SecretStore = AndroidKeystoreSecretStore(app)
    val providers = OkHttpMarketDataProviders()
    val eventBus = EventBus()
    val ingestionService = SignalIngestionService(db, eventBus)
    val verificationService = VerificationService(db, secretStore, providers, eventBus)
    val syncClient = GoogleSheetsSyncClient(db, secretStore, ingestionService, eventBus)
    val backgroundHealthTracker = BackgroundHealthTracker(app, db)
    val repository = SignalRepository(
        context = app,
        db = db,
        secretStore = secretStore,
        providers = providers,
        ingestionService = ingestionService,
        verificationService = verificationService,
        syncClient = syncClient,
        eventBus = eventBus
    )
    val lanAuthManager = LanAuthManager(secretStore)
    val lanServer = LanDashboardServer(
        repository = repository,
        authManager = lanAuthManager,
        assetLoader = { name ->
            try {
                app.assets.open("dashboard/$name").use { it.readBytes() }
            } catch (_: Exception) {
                null
            }
        },
        runtimeStatusProvider = {
            val st = SignalProcessingService.status.value
            ServerRuntimeInfo(
                running = st.running,
                bindHost = st.bindHost,
                port = st.port,
                lanEnabled = st.lanDashboardEnabled,
                activeWsClients = st.activeWsClients,
                dedicatedProcessingEnabled = st.dedicatedProcessingEnabled
            )
        },
        backgroundHealthTracker = backgroundHealthTracker
    )
    val legacyImporter = LegacySqliteImporter(app, db)
}

class SignalAnalyzerApp : Application() {
    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        appScope.launch {
            try {
                container.db.initializeDefaults()
                container.backgroundHealthTracker.ensureLoaded()
                container.ingestionService.repairFalseSignalUpdates()
                container.verificationService.reconcileOnStartup()
            } catch (_: Exception) {
            }
        }
        try {
            ReconciliationWorker.schedulePeriodicCatchUp(this)
        } catch (_: Exception) {
            // WorkManager may be uninitialized in Robolectric unit test harnesses.
        }
    }
}
