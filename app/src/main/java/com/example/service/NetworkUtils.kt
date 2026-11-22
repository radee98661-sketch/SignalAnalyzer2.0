package com.example.service

import android.content.Context
import android.os.Build
import android.os.PowerManager
import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {
    /**
     * Finds the device's best active RFC1918 private LAN IPv4 address (e.g., Wi-Fi 192.168.x.x / 10.x.x.x).
     * Falls back to 127.0.0.1 if offline or no private interface is up.
     */
    fun getLocalLanIpv4(): String {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "127.0.0.1"
            val candidates = mutableListOf<Pair<Int, String>>()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback || iface.isVirtual) continue
                val name = iface.name.lowercase()
                val priority = when {
                    name.startsWith("wlan") || name.startsWith("wifi") -> 0
                    name.startsWith("eth") || name.startsWith("en") -> 1
                    else -> 2
                }
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress && addr.isSiteLocalAddress) {
                        val ip = addr.hostAddress ?: continue
                        candidates += priority to ip
                    }
                }
            }
            candidates.minByOrNull { it.first }?.second ?: "127.0.0.1"
        } catch (_: Exception) {
            "127.0.0.1"
        }
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.isIgnoringBatteryOptimizations(context.packageName) ?: false
        } else {
            true
        }
    }
}
