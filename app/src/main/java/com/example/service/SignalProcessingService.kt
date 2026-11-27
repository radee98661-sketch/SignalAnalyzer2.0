package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.SignalAnalyzerApp
import com.example.domain.model.AppEvent
import com.example.util.TimeUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean

data class ServiceStatus(
    val running: Boolean = false,
    val dedicatedProcessingEnabled: Boolean = false,
    val lanDashboardEnabled: Boolean = false,
    val bindHost: String = "127.0.0.1",
    val port: Int = 8765,
    val lanIp: String = "127.0.0.1",
    val lanInterfaceName: String? = null,
    val allLanIps: List<Pair<String, String>> = emptyList(),
    val onWifi: Boolean = false,
    val vpnActive: Boolean = false,
    val vpnDetail: String? = null,
    val activeWsClients: Int = 0,
    val lastSchedulerTickAt: String? = null,
    val lastHeartbeatAt: String? = null,
    val lastTickDurationMs: Long = 0L,
    val isSchedulerBusy: Boolean = false,
    val lastSyncAt: String? = null,
    val lastError: String? = null
) {
    val lanUrl: String
        get() = "http://${if (lanDashboardEnabled) lanIp else "127.0.0.1"}:$port"
}

class SignalProcessingService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var schedulerJob: Job? = null
    private var syncJob: Job? = null
    private var watchdogJob: Job? = null
    private var statusPollJob: Job? = null
    private val stoppedByUser = AtomicBoolean(false)
    @Volatile
    private var currentBusyLabel: String? = null
    @Volatile
    private var startupLanError: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stoppedByUser.set(true)
            stopAllAndSelf()
            return START_NOT_STICKY
        }

        val dedicated = intent?.getBooleanExtra(EXTRA_DEDICATED, _status.value.dedicatedProcessingEnabled) ?: true
        val lanEnabled = intent?.getBooleanExtra(EXTRA_LAN_ENABLED, _status.value.lanDashboardEnabled) ?: false
        val port = (intent?.getIntExtra(EXTRA_PORT, _status.value.port) ?: 8765).coerceIn(1024, 65535)

        if (!dedicated && !lanEnabled) {
            stoppedByUser.set(true)
            stopAllAndSelf()
            return START_NOT_STICKY
        }

        stoppedByUser.set(false)
        createNotificationChannel()
        val netSnap = NetworkUtils.getLanNetworkSnapshot(applicationContext)
        val lanIp = netSnap.primaryIp
        val bindHost = if (lanEnabled) "0.0.0.0" else "127.0.0.1"

        val notif = buildNotification(dedicated, lanEnabled, lanIp, port)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notif)
        }

        val container = (application as SignalAnalyzerApp).container
        var lanErr: String? = null
        if (lanEnabled) {
            try {
                if (!container.lanServer.isRunning() || _status.value.port != port || _status.value.bindHost != bindHost) {
                    container.lanServer.start(bindHost, port)
                }
            } catch (e: Exception) {
                lanErr = "LAN server bind error on port $port: ${e.message?.take(120)}"
            }
        } else {
            container.lanServer.stop()
        }
        startupLanError = lanErr

        val wasRunning = _status.value.running
        _status.value = _status.value.copy(
            running = true,
            dedicatedProcessingEnabled = dedicated,
            lanDashboardEnabled = lanEnabled && lanErr == null,
            bindHost = bindHost,
            port = port,
            lanIp = lanIp,
            lanInterfaceName = netSnap.interfaceName,
            allLanIps = netSnap.allLanIps,
            onWifi = netSnap.onWifi,
            vpnActive = netSnap.vpnActive,
            vpnDetail = netSnap.vpnDetail,
            activeWsClients = container.lanServer.activeWsClientCount(),
            lastHeartbeatAt = TimeUtils.utcNowIso(),
            lastError = lanErr
        )
        container.eventBus.emit(AppEvent("server.status", null, "running", _status.value.lanUrl, TimeUtils.utcNowIso()))

        if (!wasRunning) {
            serviceScope.launch {
                container.backgroundHealthTracker.onServiceStarted(dedicated, lanEnabled)
            }
        }

        startLoops(dedicated)
        return START_STICKY
    }

    private fun startLoops(dedicated: Boolean) {
        val container = (application as SignalAnalyzerApp).container

        if (dedicated) {
            if (schedulerJob?.isActive != true) {
                schedulerJob = serviceScope.launch {
                    try {
                        currentBusyLabel = "Startup reconciliation"
                        container.backgroundHealthTracker.setSchedulerBusy(true, currentBusyLabel)
                        container.verificationService.reconcileOnStartup()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                    } finally {
                        currentBusyLabel = null
                    }
                    while (isActive) {
                        val startedMs = System.currentTimeMillis()
                        try {
                            currentBusyLabel = "Verifying due signals"
                            _status.value = _status.value.copy(isSchedulerBusy = true)
                            container.backgroundHealthTracker.setSchedulerBusy(true, currentBusyLabel)
                            container.verificationService.runDueSchedules()
                            val elapsedMs = (System.currentTimeMillis() - startedMs).coerceAtLeast(0L)
                            container.backgroundHealthTracker.onSchedulerTickCompleted(elapsedMs)
                            _status.value = _status.value.copy(
                                lastSchedulerTickAt = TimeUtils.utcNowIso(),
                                lastTickDurationMs = elapsedMs,
                                isSchedulerBusy = false,
                                lastError = startupLanError
                            )
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            _status.value = _status.value.copy(
                                isSchedulerBusy = false,
                                lastError = e.message?.take(140) ?: e::class.java.simpleName
                            )
                        } finally {
                            currentBusyLabel = null
                        }
                        delay(10_000L)
                    }
                }
            }

            if (syncJob?.isActive != true) {
                syncJob = serviceScope.launch {
                    while (isActive) {
                        var waitSec = 30L
                        try {
                            val settings = container.repository.getSettings()
                            waitSec = (settings["sync_interval_seconds"]?.toLongOrNull() ?: 30L).coerceAtLeast(5L)
                            val autoSync = (settings["google_sheets_auto_sync"] ?: "false").equals("true", ignoreCase = true)
                            if (autoSync) {
                                container.syncClient.syncGoogleSheets()
                                _status.value = _status.value.copy(lastSyncAt = TimeUtils.utcNowIso())
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                        }
                        delay(waitSec * 1000L)
                    }
                }
            }
        } else {
            schedulerJob?.cancel()
            schedulerJob = null
            syncJob?.cancel()
            syncJob = null
            _status.value = _status.value.copy(isSchedulerBusy = false)
        }

        // Independent lightweight watchdog heartbeat so slow API calls never masquerade as an OS kill/freeze.
        if (watchdogJob?.isActive != true) {
            watchdogJob = serviceScope.launch {
                while (isActive) {
                    try {
                        val busy = _status.value.isSchedulerBusy
                        container.backgroundHealthTracker.recordHeartbeat(busy = busy, workLabel = currentBusyLabel)
                        _status.value = _status.value.copy(lastHeartbeatAt = TimeUtils.utcNowIso())
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                    }
                    delay(BackgroundHealthTracker.HEARTBEAT_INTERVAL_MS)
                }
            }
        }

        if (statusPollJob?.isActive != true) {
            statusPollJob = serviceScope.launch {
                while (isActive) {
                    try {
                        val netSnap = NetworkUtils.getLanNetworkSnapshot(applicationContext)
                        val clients = container.lanServer.activeWsClientCount()
                        val cur = _status.value
                        if (cur.lanIp != netSnap.primaryIp ||
                            cur.lanInterfaceName != netSnap.interfaceName ||
                            cur.allLanIps != netSnap.allLanIps ||
                            cur.onWifi != netSnap.onWifi ||
                            cur.vpnActive != netSnap.vpnActive ||
                            cur.vpnDetail != netSnap.vpnDetail ||
                            cur.activeWsClients != clients
                        ) {
                            val ipChanged = cur.lanIp != netSnap.primaryIp
                            _status.value = cur.copy(
                                lanIp = netSnap.primaryIp,
                                lanInterfaceName = netSnap.interfaceName,
                                allLanIps = netSnap.allLanIps,
                                onWifi = netSnap.onWifi,
                                vpnActive = netSnap.vpnActive,
                                vpnDetail = netSnap.vpnDetail,
                                activeWsClients = clients
                            )
                            if (ipChanged && cur.lanDashboardEnabled) {
                                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                                nm?.notify(
                                    NOTIFICATION_ID,
                                    buildNotification(cur.dedicatedProcessingEnabled, true, netSnap.primaryIp, cur.port)
                                )
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                    }
                    delay(4_000L)
                }
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val container = (application as? SignalAnalyzerApp)?.container
        if (container != null && _status.value.running) {
            serviceScope.launch {
                container.backgroundHealthTracker.onTaskRemoved()
            }
        }
        super.onTaskRemoved(rootIntent)
    }

    private fun stopAllAndSelf() {
        schedulerJob?.cancel()
        syncJob?.cancel()
        watchdogJob?.cancel()
        statusPollJob?.cancel()
        val container = (application as? SignalAnalyzerApp)?.container
        container?.lanServer?.stop()
        if (container != null) {
            runBlocking(Dispatchers.IO) {
                runCatching { container.backgroundHealthTracker.onServiceStoppedByUser() }
            }
        }
        _status.value = _status.value.copy(
            running = false,
            dedicatedProcessingEnabled = false,
            lanDashboardEnabled = false,
            isSchedulerBusy = false,
            activeWsClients = 0
        )
        container?.eventBus?.emit(AppEvent("server.status", null, "stopped", null, TimeUtils.utcNowIso()))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        schedulerJob?.cancel()
        syncJob?.cancel()
        watchdogJob?.cancel()
        statusPollJob?.cancel()
        val container = (application as? SignalAnalyzerApp)?.container
        container?.lanServer?.stop()
        if (container != null && !stoppedByUser.get() && _status.value.running) {
            runBlocking(Dispatchers.IO) {
                runCatching { container.backgroundHealthTracker.onServiceDestroyedUnexpectedly() }
            }
        }
        _status.value = _status.value.copy(
            running = false,
            dedicatedProcessingEnabled = false,
            lanDashboardEnabled = false,
            isSchedulerBusy = false,
            activeWsClients = 0
        )
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.service_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.service_notification_channel_desc)
            }
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(dedicated: Boolean, lanEnabled: Boolean, lanIp: String, port: Int): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, SignalProcessingService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val parts = mutableListOf<String>()
        if (dedicated) parts += "Scheduler active"
        if (lanEnabled) parts += "LAN http://$lanIp:$port"
        val body = parts.joinToString(" • ").ifEmpty { "Active" }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Signal Analyzer")
            .setContentText(body)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "signal_processing_service"
        const val NOTIFICATION_ID = 4102
        const val ACTION_START = "com.example.action.START_PROCESSING"
        const val ACTION_STOP = "com.example.action.STOP_PROCESSING"
        const val EXTRA_DEDICATED = "extra_dedicated"
        const val EXTRA_LAN_ENABLED = "extra_lan_enabled"
        const val EXTRA_PORT = "extra_port"

        private val _status = MutableStateFlow(ServiceStatus())
        val status: StateFlow<ServiceStatus> = _status.asStateFlow()

        fun startOrUpdate(context: Context, dedicated: Boolean, lanEnabled: Boolean, port: Int) {
            if (!dedicated && !lanEnabled) {
                stop(context)
                return
            }
            val intent = Intent(context, SignalProcessingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_DEDICATED, dedicated)
                putExtra(EXTRA_LAN_ENABLED, lanEnabled)
                putExtra(EXTRA_PORT, port)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, SignalProcessingService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
