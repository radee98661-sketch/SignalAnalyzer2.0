package com.example.server

import com.example.data.local.SecretStore
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class LanAuthManager(
    private val secretStore: SecretStore,
    private val maxFailuresPerMinute: Int = 10,
    private val sessionTtlSeconds: Long = 12 * 3600L
) {
    private val failureBuckets = ConcurrentHashMap<String, ArrayDeque<Long>>()
    private val tokenGeneration = AtomicInteger(1)

    fun currentBearerToken(): String = secretStore.getOrCreateLanBearerToken()

    fun rotateBearerToken(): String {
        tokenGeneration.incrementAndGet()
        return secretStore.rotateLanBearerToken()
    }

    fun isAllowedLanAddress(remoteHost: String?): Boolean {
        if (remoteHost.isNullOrBlank()) return false
        val clean = remoteHost.trim().removePrefix("[").removeSuffix("]").substringBefore("%")
        if (clean == "localhost" || clean == "127.0.0.1" || clean == "::1" || clean == "0:0:0:0:0:0:0:1") {
            return true
        }
        return try {
            val addr = InetAddress.getByName(clean)
            if (addr.isLoopbackAddress || addr.isSiteLocalAddress || addr.isLinkLocalAddress) {
                return true
            }
            when (addr) {
                is Inet4Address -> isPrivateIpv4(addr.address)
                is Inet6Address -> {
                    val b = addr.address
                    // IPv4-mapped IPv6 ::ffff:a.b.c.d
                    val isMappedV4 = (0..9).all { b[it] == 0.toByte() } &&
                        b[10] == 0xFF.toByte() && b[11] == 0xFF.toByte()
                    if (isMappedV4) {
                        isPrivateIpv4(byteArrayOf(b[12], b[13], b[14], b[15]))
                    } else {
                        // Unique Local Address fc00::/7 or Link-local fe80::/10
                        val first = b[0].toInt() and 0xFF
                        (first and 0xFE) == 0xFC || (first == 0xFE && ((b[1].toInt() and 0xC0) == 0x80))
                    }
                }
                else -> false
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun isPrivateIpv4(bytes: ByteArray): Boolean {
        if (bytes.size != 4) return false
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF
        return when {
            b0 == 127 -> true // 127.0.0.0/8 loopback
            b0 == 10 -> true // 10.0.0.0/8 RFC1918
            b0 == 172 && b1 in 16..31 -> true // 172.16.0.0/12 RFC1918
            b0 == 192 && b1 == 168 -> true // 192.168.0.0/16 RFC1918
            b0 == 169 && b1 == 254 -> true // 169.254.0.0/16 link-local
            else -> false
        }
    }

    fun isRateLimited(remoteHost: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val bucket = failureBuckets[remoteHost] ?: return false
        synchronized(bucket) {
            pruneOld(bucket, nowMs)
            return bucket.size >= maxFailuresPerMinute
        }
    }

    fun recordAuthFailure(remoteHost: String, nowMs: Long = System.currentTimeMillis()) {
        val bucket = failureBuckets.computeIfAbsent(remoteHost) { ArrayDeque() }
        synchronized(bucket) {
            pruneOld(bucket, nowMs)
            bucket.addLast(nowMs)
        }
    }

    fun clearAuthFailures(remoteHost: String) {
        failureBuckets.remove(remoteHost)
    }

    private fun pruneOld(bucket: ArrayDeque<Long>, nowMs: Long) {
        val cutoff = nowMs - 60_000L
        while (bucket.isNotEmpty() && bucket.first() < cutoff) {
            bucket.removeFirst()
        }
    }

    fun verifyBearerToken(candidate: String?): Boolean {
        if (candidate.isNullOrBlank()) return false
        val expected = currentBearerToken().toByteArray(Charsets.UTF_8)
        val supplied = candidate.trim().toByteArray(Charsets.UTF_8)
        return MessageDigest.isEqual(expected, supplied)
    }

    fun issueSessionToken(nowSec: Long = System.currentTimeMillis() / 1000L): Pair<String, Long> {
        val exp = nowSec + sessionTtlSeconds
        val gen = tokenGeneration.get()
        val payload = "v1:$gen:$exp"
        val sig = hmacSha256(payload.toByteArray(Charsets.UTF_8))
        val encodedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray(Charsets.UTF_8))
        val encodedSig = Base64.getUrlEncoder().withoutPadding().encodeToString(sig)
        return "sa_sess_${encodedPayload}.$encodedSig" to sessionTtlSeconds
    }

    fun verifyAnyToken(candidate: String?, nowSec: Long = System.currentTimeMillis() / 1000L): Boolean {
        if (candidate.isNullOrBlank()) return false
        val trimmed = candidate.trim()
        if (verifyBearerToken(trimmed)) return true
        if (!trimmed.startsWith("sa_sess_")) return false

        return try {
            val raw = trimmed.removePrefix("sa_sess_")
            val dot = raw.indexOf('.')
            if (dot <= 0 || dot >= raw.length - 1) return false
            val payloadBytes = Base64.getUrlDecoder().decode(raw.substring(0, dot))
            val suppliedSig = Base64.getUrlDecoder().decode(raw.substring(dot + 1))
            val expectedSig = hmacSha256(payloadBytes)
            if (!MessageDigest.isEqual(expectedSig, suppliedSig)) return false

            val payload = String(payloadBytes, Charsets.UTF_8)
            val parts = payload.split(':')
            if (parts.size != 3 || parts[0] != "v1") return false
            val gen = parts[1].toIntOrNull() ?: return false
            val exp = parts[2].toLongOrNull() ?: return false
            gen == tokenGeneration.get() && exp > nowSec
        } catch (_: Exception) {
            false
        }
    }

    fun isValidCsrfHeaders(method: String, originHeader: String?, hostHeader: String?, requestedWithHeader: String?): Boolean {
        if (method in setOf("GET", "HEAD", "OPTIONS")) return true
        if (!requestedWithHeader.isNullOrBlank()) {
            // If Origin is also sent by browser, ensure it matches Host (same-origin)
            if (!originHeader.isNullOrBlank() && !hostHeader.isNullOrBlank()) {
                val originHost = originHeader.removePrefix("http://").removePrefix("https://").substringBefore("/")
                if (!originHost.equals(hostHeader, ignoreCase = true)) return false
            }
            return true
        }
        if (!originHeader.isNullOrBlank() && !hostHeader.isNullOrBlank()) {
            val originHost = originHeader.removePrefix("http://").removePrefix("https://").substringBefore("/")
            return originHost.equals(hostHeader, ignoreCase = true)
        }
        // Allow non-browser programmatic clients that send Authorization header without Origin
        return originHeader.isNullOrBlank()
    }

    private fun hmacSha256(data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secretStore.getOrCreateSessionSigningKey(), "HmacSHA256"))
        return mac.doFinal(data)
    }
}
