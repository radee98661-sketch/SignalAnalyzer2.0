package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * On device boot, schedules safe WorkManager reconciliation (stale lease recovery + catch-up)
 * without violating Android 15+ background foreground-service start restrictions.
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            ReconciliationWorker.enqueueStartupReconcile(context)
            ReconciliationWorker.schedulePeriodicCatchUp(context)
        }
    }
}
