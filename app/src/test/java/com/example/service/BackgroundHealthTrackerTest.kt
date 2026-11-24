package com.example.service

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundHealthTrackerTest {

    @Test
    fun healthyHeartbeatsMaintainFullUptime() = runBlocking {
        var nowMs = 1_764_000_000_000L
        val tracker = BackgroundHealthTracker(
            context = null,
            db = null,
            clock = { nowMs },
            exitReasonProvider = { _, _ -> null },
            batteryOptProvider = { true }
        )

        tracker.onServiceStarted(dedicated = true, lanEnabled = true)
        repeat(8) {
            nowMs += 15_000L
            tracker.recordHeartbeat(busy = false)
        }

        val snap = tracker.snapshot.value
        assertTrue(snap.expectedRunning)
        assertEquals(0, snap.outageCount24h)
        assertEquals(0L, snap.downtimeSeconds24h)
        assertEquals(100.0, snap.uptime24hPct, 0.01)
        assertTrue(snap.buckets.all { it.status == HealthBucketStatus.ACTIVE })
    }

    @Test
    fun detectsFreezeGapWithExactOutageTimestampsAndStalledBuckets() = runBlocking {
        var nowMs = 1_764_000_000_000L
        val tracker = BackgroundHealthTracker(
            context = null,
            db = null,
            clock = { nowMs },
            exitReasonProvider = { _, _ -> null },
            batteryOptProvider = { true }
        )

        tracker.onServiceStarted(dedicated = true, lanEnabled = false)
        repeat(4) {
            nowMs += 15_000L
            tracker.recordHeartbeat()
        }
        val lastHealthyMs = nowMs

        // Simulate a 19-minute OS Doze / OEM background freeze
        nowMs += 19 * 60_000L
        val resumedMs = nowMs
        tracker.recordHeartbeat()

        val snap = tracker.snapshot.value
        assertEquals(1, snap.outageCount24h)
        assertEquals(19 * 60L, snap.downtimeSeconds24h)
        assertEquals(19 * 60L, snap.longestGapSeconds24h)
        assertTrue(snap.uptime24hPct < 50.0)

        val freezeIncident = snap.incidents.first { it.kind == BackgroundHealthTracker.KIND_OS_FREEZE_GAP }
        assertEquals(lastHealthyMs, freezeIncident.startedAtMs)
        assertEquals(resumedMs, freezeIncident.endedAtMs)
        assertEquals(19 * 60L, freezeIncident.durationSec)
        assertTrue(snap.buckets.count { it.status == HealthBucketStatus.STALLED } >= 19)
    }

    @Test
    fun detectsProcessKillOnRestartAndAttachesOsExitReason() = runBlocking {
        var nowMs = 1_764_000_000_000L
        val tracker1 = BackgroundHealthTracker(
            context = null,
            db = null,
            clock = { nowMs },
            exitReasonProvider = { _, _ -> null },
            batteryOptProvider = { true }
        )
        tracker1.onServiceStarted(dedicated = true, lanEnabled = true)
        nowMs += 15_000L
        tracker1.recordHeartbeat()
        val diedAfterMs = nowMs
        val persistedJson = tracker1.exportStateJson()

        // Simulate app process killed by Android and restarted 12 minutes later
        nowMs += 12 * 60_000L
        val tracker2 = BackgroundHealthTracker(
            context = null,
            db = null,
            clock = { nowMs },
            exitReasonProvider = { _, _ -> "Low Memory Killer (LMK) at 11:45:00" },
            batteryOptProvider = { true }
        )
        tracker2.importStateJson(persistedJson)
        tracker2.onServiceStarted(dedicated = true, lanEnabled = true)

        val snap = tracker2.snapshot.value
        assertEquals(1, snap.outageCount24h)
        assertEquals(12 * 60L, snap.downtimeSeconds24h)
        assertEquals("Low Memory Killer (LMK) at 11:45:00", snap.lastOsExitReason)

        val killInc = snap.incidents.first { it.kind == BackgroundHealthTracker.KIND_PROCESS_KILLED }
        assertEquals(diedAfterMs, killInc.startedAtMs)
        assertEquals(nowMs, killInc.endedAtMs)
        assertTrue(killInc.detail.contains("Low Memory Killer"))
    }

    @Test
    fun explicitUserStopDoesNotTriggerFalseKillOutageOnNextStart() = runBlocking {
        var nowMs = 1_764_000_000_000L
        val tracker = BackgroundHealthTracker(
            context = null,
            db = null,
            clock = { nowMs },
            exitReasonProvider = { _, _ -> null },
            batteryOptProvider = { true }
        )

        tracker.onServiceStarted(dedicated = true, lanEnabled = false)
        nowMs += 15_000L
        tracker.recordHeartbeat()
        nowMs += 15_000L
        tracker.onServiceStoppedByUser()
        assertFalse(tracker.snapshot.value.expectedRunning)

        // Restart 2 hours later
        nowMs += 2 * 60 * 60_000L
        tracker.onServiceStarted(dedicated = true, lanEnabled = false)

        val snap = tracker.snapshot.value
        assertEquals(0, snap.outageCount24h)
        assertEquals(0L, snap.downtimeSeconds24h)
        assertTrue(snap.incidents.none { it.kind == BackgroundHealthTracker.KIND_PROCESS_KILLED })
    }
}
