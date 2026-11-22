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

data class ServiceStatus(
    val running: Boolean = false,
    val dedicatedProcessingEnabled: Boolean = false,
    val lanDashboardEnabled: Boolean = false,
    val bindHost: String = "127.0.0.1",
    val port: Int = 8765,
    val lanIp: String = "127.0.0.1",
    val activeWsClients: Int = 0,
    val lastSchedulerTickAt: String? = null,
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
    private var statusPollJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopAllAndSelf()
            return START_NOT_STICKY
        }

        val dedicated = intent?.getBooleanExtra(EXTRA_DEDICATED, _status.value.dedicatedProcessingEnabled) ?: true
        val lanEnabled = intent?.getBooleanExtra(EXTRA_LAN_ENABLED, _status.value.lanDashboardEnabled) ?: false
        val port = (intent?.getIntExtra(EXTRA_PORT, _status.value.port) ?: 8765).coerceIn(1024, 65535)

        if (!dedicated && !lanEnabled) {
            stopAllAndSelf()
            return START_NOT_STICKY
        }

        createNotificationChannel()
        val lanIp = NetworkUtils.getLocalLanIpv4()
        val bindHost = if (lanEnabled) "0.0.0.0" else "127.0.0.1"

        val notif = buildNotification(dedicated, lanEnabled, lanIp, port)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notif)
        }

        val container = (application as SignalAnalyzerApp).container
        var startupError: String? = null
        if (lanEnabled) {
            try {
                container.lanServer.start(bindHost, port)
            } catch (e: Exception) {
                startupError = "LAN server bind error on port $port: ${e.message?.take(120)}"
            }
        } else {
            container.lanServer.stop()
        }

        _status.value = _status.value.copy(
            running = true,
            dedicatedProcessingEnabled = dedicated,
            lanDashboardEnabled = lanEnabled && startupError == null,
            bindHost = bindHost,
            port = port,
            lanIp = lanIp,
            activeWsClients = container.lanServer.activeWsClientCount(),
            lastError = startupError
        )
        container.eventBus.emit(AppEvent("server.status", null, "running", _status.value.lanUrl, TimeUtils.utcNowIso()))

        startLoops(dedicated)
        return START_STICKY
    }

    private fun startLoops(dedicated: Boolean) {
        schedulerJob?.cancel()
        syncJob?.cancel()
        statusPollJob?.cancel()

        val container = (application as SignalAnalyzerApp).container

        if (dedicated) {
            schedulerJob = serviceScope.launch {
                try {
                    container.verificationService.reconcileOnStartup()
                } catch (_: Exception) {
                }
                while (isActive) {
                    try {
                        container.verificationService.runDueSchedules()
                        _status.value = _status.value.copy(lastSchedulerTickAt = TimeUtils.utcNowIso())
                    } catch (e: Exception) {
                        _status.value = _status.value.copy(lastError = e.message?.take(140))
                    }
                    delay(10_000L)
                }
            }

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
                    } catch (_: Exception) {
                    }
                    delay(waitSec * 1000L)
                }
            }
        }

        statusPollJob = serviceScope.launch {
            while (isActive) {
                val lanIp = NetworkUtils.getLocalLanIpv4()
                val clients = container.lanServer.activeWsClientCount()
                if (_status.value.lanIp != lanIp || _status.value.activeWsClients != clients) {
                    _status.value = _status.value.copy(lanIp = lanIp, activeWsClients = clients)
                }
                delay(4_000L)
            }
        }
    }

    private fun stopAllAndSelf() {
        schedulerJob?.cancel()
        syncJob?.cancel()
        statusPollJob?.cancel()
        val container = (application as? SignalAnalyzerApp)?.container
        container?.lanServer?.stop()
        _status.value = _status.value.copy(
            running = false,
            dedicatedProcessingEnabled = false,
            lanDashboardEnabled = false,
            activeWsClients = 0
        )
        container?.eventBus?.emit(AppEvent("server.status", null, "stopped", null, TimeUtils.utcNowIso()))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        schedulerJob?.cancel()
        syncJob?.cancel()
        statusPollJob?.cancel()
        (application as? SignalAnalyzerApp)?.container?.lanServer?.stop()
        _status.value = _status.value.copy(running = false, dedicatedProcessingEnabled = false, lanDashboardEnabled = false, activeWsClients = 0)
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
