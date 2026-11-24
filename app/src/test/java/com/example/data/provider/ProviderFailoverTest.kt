package com.example.data.provider

import com.example.data.local.InMemorySecretStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class ProviderFailoverTest {

    @Test
    fun twelveDataQuotaExceededFailsOverToBackupUntilUtcMidnight() {
        var now = Instant.parse("2026-11-24T10:00:00Z")
        val fm = ProviderFailoverManager(clock = { now })
        val secrets = mapOf(
            "api_key" to "primary-td-key",
            "backup_api_key" to "backup-td-key"
        )

        val configured = fm.getTwelveDataAccounts(secrets)
        assertEquals(2, configured.size)
        assertEquals(ApiAccountSlot.PRIMARY, fm.selectAvailableAccounts("twelve_data", configured) { it.slot }.first().slot)

        // Simulate primary hitting daily quota HTTP 429
        fm.recordFailure("twelve_data", ApiAccountSlot.PRIMARY, "quota_exceeded", 429, "You have run out of API credits for the day.")
        assertTrue(fm.shouldFailover("quota_exceeded", 429))

        // Immediately after failure, only BACKUP should be eligible
        val afterFail = fm.selectAvailableAccounts("twelve_data", configured) { it.slot }
        assertEquals(1, afterFail.size)
        assertEquals(ApiAccountSlot.BACKUP, afterFail.first().slot)

        val snap = fm.getStatusSnapshot("twelve_data", secrets)
        assertTrue(snap.failoverActive)
        assertTrue(snap.backupConfigured)
        assertEquals("backup", snap.activeSlot)

        // Still cooling down 5 hours later (15:00 UTC same day)
        now = now.plus(Duration.ofHours(5))
        assertEquals(listOf(ApiAccountSlot.BACKUP), fm.selectAvailableAccounts("twelve_data", configured) { it.slot }.map { it.slot })

        // After UTC midnight reset (next day 00:01 UTC), PRIMARY is eligible and preferred again
        now = Instant.parse("2026-11-25T00:01:00Z")
        val afterReset = fm.selectAvailableAccounts("twelve_data", configured) { it.slot }
        assertEquals(listOf(ApiAccountSlot.PRIMARY, ApiAccountSlot.BACKUP), afterReset.map { it.slot })
        assertFalse(fm.getStatusSnapshot("twelve_data", secrets).failoverActive)
    }

    @Test
    fun twelveDataMinuteRateLimitRecoversAfter65Seconds() {
        var now = Instant.parse("2026-11-24T10:00:00Z")
        val fm = ProviderFailoverManager(clock = { now })
        val secrets = mapOf("api_key" to "p-key", "backup_api_key" to "b-key")
        val configured = fm.getTwelveDataAccounts(secrets)

        fm.recordFailure("twelve_data", ApiAccountSlot.PRIMARY, "rate_limited", 429, "8 credits per minute exceeded")
        assertEquals(listOf(ApiAccountSlot.BACKUP), fm.selectAvailableAccounts("twelve_data", configured) { it.slot }.map { it.slot })

        now = now.plusSeconds(66)
        assertEquals(listOf(ApiAccountSlot.PRIMARY, ApiAccountSlot.BACKUP), fm.selectAvailableAccounts("twelve_data", configured) { it.slot }.map { it.slot })
    }

    @Test
    fun angelOneAuthErrorFailsOverToBackupAndResetsOnCredentialSave() {
        var now = Instant.parse("2026-11-24T06:00:00Z")
        val fm = ProviderFailoverManager(clock = { now })
        val secrets = mapOf(
            "api_key" to "bad-key",
            "client_id" to "C1",
            "password" to "1234",
            "totp_secret" to "JBSWY3DPEHPK3PXP",
            "backup_api_key" to "good-backup-key",
            "backup_client_id" to "C2",
            "backup_password" to "5678",
            "backup_totp_secret" to "JBSWY3DPEHPK3PXP"
        )

        val configured = fm.getAngelOneAccounts(secrets)
        assertEquals(2, configured.size)
        assertTrue(fm.hasPrimaryConfigured("angel_one", secrets))
        assertTrue(fm.hasBackupConfigured("angel_one", secrets))

        fm.recordFailure("angel_one", ApiAccountSlot.PRIMARY, "auth_error", 401, "Invalid API Key")
        val available = fm.selectAvailableAccounts("angel_one", configured) { it.slot }
        assertEquals(1, available.size)
        assertEquals(ApiAccountSlot.BACKUP, available.first().slot)
        assertEquals("good-backup-key", available.first().apiKey)
        assertEquals("C2", available.first().clientId)

        // Saving updated credentials calls resetProvider -> PRIMARY is immediately available again
        fm.resetProvider("angel_one")
        val afterSave = fm.selectAvailableAccounts("angel_one", configured) { it.slot }
        assertEquals(listOf(ApiAccountSlot.PRIMARY, ApiAccountSlot.BACKUP), afterSave.map { it.slot })
    }

    @Test
    fun emptyTailCooldownPreventsClosedMarketSpamAndClearsOnNewCandles() {
        var now = Instant.parse("2026-11-28T10:00:00Z") // Saturday
        val fm = ProviderFailoverManager(clock = { now })

        assertFalse(fm.isEmptyTailCoolingDown("twelve_data", "XAUUSD"))
        fm.recordEmptyTail("twelve_data", "XAUUSD")
        assertTrue(fm.isEmptyTailCoolingDown("twelve_data", "XAUUSD"))

        // Still cooling down at +2m, expires at +3m
        now = now.plus(Duration.ofMinutes(2))
        assertTrue(fm.isEmptyTailCoolingDown("twelve_data", "XAUUSD"))
        now = now.plus(Duration.ofMinutes(2))
        assertFalse(fm.isEmptyTailCoolingDown("twelve_data", "XAUUSD"))

        // Second consecutive empty response escalates to 10 minutes
        fm.recordEmptyTail("twelve_data", "XAUUSD")
        now = now.plus(Duration.ofMinutes(5))
        assertTrue(fm.isEmptyTailCoolingDown("twelve_data", "XAUUSD"))

        // New candle data clears cooldown immediately
        fm.clearEmptyTail("twelve_data", "XAUUSD")
        assertFalse(fm.isEmptyTailCoolingDown("twelve_data", "XAUUSD"))
    }

    @Test
    fun secretStoreRemovesOnlyBackupKeysLeavingPrimaryIntact() {
        val store = InMemorySecretStore()
        store.putProviderSecrets(
            "twelve_data",
            mapOf("api_key" to "primary-key", "backup_api_key" to "backup-key")
        )
        store.removeProviderSecretKeys("twelve_data", ProviderFailoverManager.TWELVE_DATA_BACKUP_KEYS)

        val remaining = store.getProviderSecrets("twelve_data")
        assertEquals("primary-key", remaining["api_key"])
        assertFalse(remaining.containsKey("backup_api_key"))
    }
}
