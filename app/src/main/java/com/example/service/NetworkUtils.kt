package com.example.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import java.net.Inet4Address
import java.net.NetworkInterface

data class LanNetworkSnapshot(
    val primaryIp: String = "127.0.0.1",
    val interfaceName: String? = null,
    val allLanIps: List<Pair<String, String>> = emptyList(),
    val onWifi: Boolean = false,
    val vpnActive: Boolean = false,
    val vpnDetail: String? = null
)

object NetworkUtils {

    private val VPN_PREFIXES = listOf("tun", "tap", "ppp", "wg", "utun", "ipsec")
    private val CELLULAR_OR_IGNORED_PREFIXES = listOf("rmnet", "ccmni", "pdp", "dummy", "lo", "clat", "v4-")

    private fun isVpnInterface(name: String): Boolean =
        VPN_PREFIXES.any { name.startsWith(it) }

    private fun isIgnoredForLan(name: String): Boolean =
        isVpnInterface(name) || CELLULAR_OR_IGNORED_PREFIXES.any { name.startsWith(it) }

    /**
     * Collects all active private LAN IPv4 addresses (excluding VPN & cellular interfaces),
     * ordered by preference: Wi-Fi -> Hotspot -> Ethernet -> other private LAN interfaces.
     */
    fun getAllLanIpv4Addresses(): List<Pair<String, String>> {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            val candidates = mutableListOf<Triple<Int, String, String>>()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback || iface.isVirtual) continue
                val name = iface.name.lowercase()
                if (isIgnoredForLan(name)) continue
                val priority = when {
                    name.startsWith("wlan") || name.startsWith("wifi") -> 0
                    name.startsWith("ap") || name.startsWith("swlan") -> 1
                    name.startsWith("eth") || name.startsWith("en") -> 2
                    else -> 3
                }
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress && addr.isSiteLocalAddress) {
                        val ip = addr.hostAddress ?: continue
                        candidates += Triple(priority, iface.name, ip)
                    }
                }
            }
            candidates
                .sortedWith(compareBy<Triple<Int, String, String>> { it.first }.thenBy { it.second })
                .distinctBy { it.third }
                .map { it.second to it.third }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Finds the device's best active RFC1918 private LAN IPv4 address (e.g., Wi-Fi 192.168.x.x / 10.x.x.x).
     * Explicitly skips VPN (tun/wg/ppp) and cellular (rmnet/ccmni) interfaces.
     * Falls back to 127.0.0.1 if offline or no private LAN interface is up.
     */
    fun getLocalLanIpv4(): String {
        return getAllLanIpv4Addresses().firstOrNull()?.second ?: "127.0.0.1"
    }

    /**
     * Inspects active Android networks and network interfaces to report the live LAN IPv4,
     * Wi-Fi connectivity, and whether a VPN or local VPN-based firewall/ad-blocker tunnel is active.
     */
    fun getLanNetworkSnapshot(context: Context): LanNetworkSnapshot {
        val lanIps = getAllLanIpv4Addresses()
        val primary = lanIps.firstOrNull()

        var hasWifiTransport = false
        var hasVpnTransport = false
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (cm != null) {
                val networks = buildList {
                    cm.activeNetwork?.let { add(it) }
                    @Suppress("DEPRECATION")
                    cm.allNetworks.forEach { if (it !in this) add(it) }
                }
                for (net in networks) {
                    val caps = cm.getNetworkCapabilities(net) ?: continue
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        hasWifiTransport = true
                    }
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                        hasVpnTransport = true
                    }
                }
            }
        } catch (_: Exception) {
        }

        val activeVpnIfaces = mutableListOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces != null && interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                val lower = iface.name.lowercase()
                if (isVpnInterface(lower)) {
                    activeVpnIfaces += iface.name
                }
            }
        } catch (_: Exception) {
        }

        val vpnActive = hasVpnTransport || activeVpnIfaces.isNotEmpty()
        val vpnDetail = when {
            activeVpnIfaces.isNotEmpty() && hasVpnTransport ->
                "VPN tunnel active (${activeVpnIfaces.joinToString(", ")})"
            activeVpnIfaces.isNotEmpty() ->
                "Virtual tunnel interface up (${activeVpnIfaces.joinToString(", ")})"
            hasVpnTransport ->
                "Android VPN transport active"
            else -> null
        }

        val onWifi = hasWifiTransport || lanIps.any { (iface, _) ->
            val n = iface.lowercase()
            n.startsWith("wlan") || n.startsWith("wifi") || n.startsWith("ap") || n.startsWith("swlan")
        }

        return LanNetworkSnapshot(
            primaryIp = primary?.second ?: "127.0.0.1",
            interfaceName = primary?.first,
            allLanIps = lanIps,
            onWifi = onWifi,
            vpnActive = vpnActive,
            vpnDetail = vpnDetail
        )
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
