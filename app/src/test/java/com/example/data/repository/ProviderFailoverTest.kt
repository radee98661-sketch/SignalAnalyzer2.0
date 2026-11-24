package com.example.data.repository

import com.example.data.local.InMemorySecretStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class ProviderFailoverTest {

    @Test
    fun twelveDataPrimaryQuotaExceededRoutesImmediatelyToBackupAndShortCircuitsWhenAllExhausted() {
        var now = Instant.parse("2026-11-24T10:00:00Z")
        val failover = ProviderFailoverManager(clock = { now })

        val secrets = mapOf("api_key" to "primary-key", "backup_api_key" to "backup-key")
        val accounts = failover.twelveDataAccounts(secrets)
        assertEquals(listOf("primary", "backup"), accounts.map { it.slot })

        // Initially primary is first, backup is second
        val (initialOrder, initialErr) = failover.selectOrderedAccounts("twelve_data", accounts) { it.slot }
        assertNull(initialErr)
        assertEquals(listOf("primary", "backup"), initialOrder.map { it.slot })

        // Primary hits HTTP 429 quota_exceeded -> primary cooled down until next UTC midnight
        assertTrue(failover.shouldFailover("quota_exceeded", 429))
        failover.recordSlotFailure("twelve_data", "primary", "quota_exceeded", 429, "800 daily credits used")

        val statusAfterPrimary = failover.getFailoverStatus("twelve_data", secrets)
        assertTrue(statusAfterPrimary.backupConfigured)
        assertEquals("backup", statusAfterPrimary.activeSlot)
        assertEquals(2, statusAfterPrimary.configuredCount)

        // Subsequent calls skip exhausted primary and route directly to backup
        val (afterPrimaryFail, errAfterPrimary) = failover.selectOrderedAccounts("twelve_data", accounts) { it.slot }
        assertNull(errAfterPrimary)
        assertEquals(listOf("backup"), afterPrimaryFail.map { it.slot })

        // If backup also hits quota_exceeded -> short-circuits with a single paused error (zero wasted calls!)
        failover.recordSlotFailure("twelve_data", "backup", "quota_exceeded", 429, "backup daily credits used")
        val (noneLeft, exhaustedErr) = failover.selectOrderedAccounts("twelve_data", accounts) { it.slot }
        assertTrue(noneLeft.isEmpty())
        assertNotNull(exhaustedErr)
        assertEquals("quota_exceeded", exhaustedErr!!.category)
        assertEquals(429, exhaustedErr.httpCode)
        assertEquals("exhausted", failover.getFailoverStatus("twelve_data", secrets).activeSlot)

        // After UTC midnight, both primary and backup automatically recover
        now = Instant.parse("2026-11-25T00:01:00Z")
        val (recovered, recoveredErr) = failover.selectOrderedAccounts("twelve_data", accounts) { it.slot }
        assertNull(recoveredErr)
        assertEquals(listOf("primary", "backup"), recovered.map { it.slot })
        assertEquals("primary", failover.getFailoverStatus("twelve_data", secrets).activeSlot)
    }

    @Test
    fun angelOneFailoverAndBackupCredentialClearingPreservePrimarySecrets() {
        var now = Instant.parse("2026-11-24T05:00:00Z")
        val failover = ProviderFailoverManager(clock = { now })
        val secretStore = InMemorySecretStore()

        secretStore.putProviderSecrets(
            "angel_one",
            mapOf(
                "api_key" to "primary-ao-key",
                "client_id" to "P001",
                "password" to "1111",
                "totp_secret" to "JBSWY3DPEHPK3PXP",
                "backup_api_key" to "backup-ao-key",
                "backup_client_id" to "B002",
                "backup_password" to "2222",
                "backup_totp_secret" to "JBSWY3DPEHPK3PXP"
            )
        )

        val secrets = secretStore.getProviderSecrets("angel_one")
        val accounts = failover.angelOneAccounts(secrets)
        assertEquals(2, accounts.size)
        assertTrue(failover.hasBackupCredentials("angel_one", secrets))

        // Primary fails with auth_error (e.g. Invalid API Key) -> routes directly to backup for 15 minutes
        assertTrue(failover.shouldFailover("auth_error", 401))
        failover.recordSlotFailure("angel_one", "primary", "auth_error", 401, "Invalid API Key")

        val (candidates, err) = failover.selectOrderedAccounts("angel_one", accounts) { it.slot }
        assertNull(err)
        assertEquals(listOf("backup"), candidates.map { it.slot })
        assertEquals("backup-ao-key", candidates.first().toSecretsMap()["api_key"])
        assertEquals("B002", candidates.first().toSecretsMap()["client_id"])

        // Saving updated credentials resets cooldowns immediately
        failover.resetProvider("angel_one")
        assertFalse(failover.isSlotCooledDown("angel_one", "primary"))

        // Clearing backup keys removes ONLY backup_* fields and preserves primary
        secretStore.removeProviderSecretKeys("angel_one", ProviderFailoverManager.ANGEL_ONE_BACKUP_KEYS)
        val remaining = secretStore.getProviderSecrets("angel_one")
        assertFalse(failover.hasBackupCredentials("angel_one", remaining))
        assertEquals("primary-ao-key", remaining["api_key"])
        assertEquals("P001", remaining["client_id"])
        assertNull(remaining["backup_api_key"])
    }

    @Test
    fun emptyForwardFetchCooldownPreventsRepeatedClosedMarketCalls() {
        var now = Instant.parse("2026-11-24T12:00:00Z")
        val failover = ProviderFailoverManager(clock = { now })

        assertFalse(failover.isForwardFetchCooledDown("twelve_data", "XAUUSD"))

        failover.markEmptyForwardFetch("twelve_data", "XAUUSD")
        assertTrue(failover.isForwardFetchCooledDown("twelve_data", "XAUUSD"))
        assertFalse(failover.isForwardFetchCooledDown("twelve_data", "EURUSD"))

        // Still paused after 3 minutes
        now = now.plus(Duration.ofMinutes(3))
        assertTrue(failover.isForwardFetchCooledDown("twelve_data", "XAUUSD"))

        // Automatically expires after 5 minutes
        now = now.plus(Duration.ofMinutes(3))
        assertFalse(failover.isForwardFetchCooledDown("twelve_data", "XAUUSD"))
    }

    @Test
    fun minuteRateLimitCooldownRecoversAfter65SecondsWithoutBlockingAllDay() {
        var now = Instant.parse("2026-11-24T10:00:00Z")
        val failover = ProviderFailoverManager(clock = { now })

        val secrets = mapOf("api_key" to "primary-key", "backup_api_key" to "backup-key")
        val accounts = failover.twelveDataAccounts(secrets)

        // Primary hits 8/min burst limit (rate_limited, not daily quota_exceeded)
        failover.recordSlotFailure("twelve_data", "primary", "rate_limited", 429, "8 credits per minute exceeded")
        assertTrue(failover.isSlotCooledDown("twelve_data", "primary"))
        assertEquals("backup", failover.getFailoverStatus("twelve_data", secrets).activeSlot)

        // After 30s still routed to backup
        now = now.plusSeconds(30)
        val (duringWindow, _) = failover.selectOrderedAccounts("twelve_data", accounts) { it.slot }
        assertEquals(listOf("backup"), duringWindow.map { it.slot })

        // After 70s primary recovers automatically
        now = now.plusSeconds(40)
        assertFalse(failover.isSlotCooledDown("twelve_data", "primary"))
        val (afterWindow, _) = failover.selectOrderedAccounts("twelve_data", accounts) { it.slot }
        assertEquals(listOf("primary", "backup"), afterWindow.map { it.slot })
        assertEquals("primary", failover.getFailoverStatus("twelve_data", secrets).activeSlot)
    }
}
