package za.kilowatch.ultimatefilemanager.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Unified network interface helper supporting both Wi-Fi and wired Ethernet
 * across mobile and Android TV devices without relying on deprecated WifiManager APIs.
 */
object NetworkInterfaceUtils {

    private const val TAG = "NetworkInterfaceUtils"

    /**
     * Finds the primary active local IPv4 address across active non-loopback network interfaces.
     * Prioritizes Ethernet and Wi-Fi interfaces over cellular or virtual interfaces.
     */
    fun getActiveLocalIpv4(context: Context): Inet4Address? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: return null

            // Prioritize standard local network interfaces: eth* (Ethernet), wlan* (Wi-Fi), en*
            val sorted = interfaces.sortedWith(compareBy { iface ->
                val name = iface.name.lowercase()
                when {
                    name.startsWith("eth") -> 0
                    name.startsWith("wlan") -> 1
                    name.startsWith("en") -> 2
                    name.startsWith("rndis") -> 3
                    else -> 4
                }
            })

            for (iface in sorted) {
                if (!iface.isUp || iface.isLoopback || iface.isVirtual) continue
                val name = iface.name.lowercase()
                if (name.startsWith("p2p") || name.startsWith("tun") || name.startsWith("dummy")) continue

                for (addr in iface.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                        return addr
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving active local IPv4 address", e)
            null
        }
    }

    /**
     * Derives the 24-bit subnet prefix (e.g. "192.168.1") for the active LAN interface.
     * Supports both Wi-Fi and wired Ethernet (especially on Android TV).
     */
    fun getSubnetPrefix(context: Context): String? {
        val ipv4 = getActiveLocalIpv4(context) ?: return null
        val bytes = ipv4.address
        if (bytes.size < 4) return null
        val a = bytes[0].toInt() and 0xFF
        val b = bytes[1].toInt() and 0xFF
        val c = bytes[2].toInt() and 0xFF
        return "$a.$b.$c"
    }

    /**
     * Returns true if there is an active local network connection (Wi-Fi or Ethernet).
     */
    fun isLocalNetworkConnected(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return getActiveLocalIpv4(context) != null

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val activeNetwork = cm.activeNetwork ?: return false
                val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            } else {
                @Suppress("DEPRECATION")
                val activeInfo = cm.activeNetworkInfo ?: return false
                @Suppress("DEPRECATION")
                activeInfo.isConnected && (
                        activeInfo.type == ConnectivityManager.TYPE_WIFI ||
                                activeInfo.type == ConnectivityManager.TYPE_ETHERNET
                        )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed checking connectivity manager, falling back to interface check", e)
            getActiveLocalIpv4(context) != null
        }
    }
}
