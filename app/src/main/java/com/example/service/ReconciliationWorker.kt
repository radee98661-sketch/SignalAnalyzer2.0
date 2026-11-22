package com.example.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.SignalAnalyzerApp
import java.util.concurrent.TimeUnit

/**
 * WorkManager catch-up worker for periodic verification, optional Google Sheets sync,
 * and startup/boot stale-lease reconciliation. Never retroactively auto-queues old signals.
 */
class ReconciliationWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? SignalAnalyzerApp ?: return Result.success()
        val container = app.container
        return try {
            container.db.initializeDefaults()
            container.verificationService.reconcileOnStartup()

            val settings = container.repository.getSettings()
            if ((settings["google_sheets_auto_sync"] ?: "false").equals("true", ignoreCase = true)) {
                container.syncClient.syncGoogleSheets()
            }
            container.verificationService.runDueSchedules()
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val PERIODIC_WORK_NAME = "signal_analyzer_periodic_catchup"
        private const val BOOT_WORK_NAME = "signal_analyzer_boot_reconcile"

        fun schedulePeriodicCatchUp(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val req = PeriodicWorkRequestBuilder<ReconciliationWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                req
            )
        }

        fun enqueueStartupReconcile(context: Context) {
            val req = OneTimeWorkRequestBuilder<ReconciliationWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                BOOT_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                req
            )
        }
    }
}
