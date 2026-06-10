package za.kilowatch.ultimatefilemanager.network

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

data class AdbDevice(
    val host: String,
    val port: Int
) {
    override fun toString(): String = "$host:$port"
}

class AdbDeviceDiscovery(private val context: Context) {

    private val TAG = "AdbDeviceDiscovery"

    suspend fun scanNetwork(
        timeout: Int = 500,
        onProgress: ((scanned: Int, total: Int, devicesFound: Int) -> Unit)? = null
    ): List<AdbDevice> = withContext(Dispatchers.IO) {
        val devices = mutableListOf<AdbDevice>()
        
        try {
            val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val connectionInfo = wifiManager?.connectionInfo ?: return@withContext devices

            val ipAddress = connectionInfo.ipAddress
            if (ipAddress == 0) return@withContext devices

            // Convert IP address to string
            val subnet = buildSubnet(ipAddress)
            
            Log.d(TAG, "Scanning subnet: $subnet")

            // Scan common ADB ports
            val ports = listOf(5555, 5037, 5038)
            
            // Create all scan tasks
            val scanTasks = mutableListOf<Pair<String, Int>>()
            for (i in 1..254) {
                for (port in ports) {
                    val host = "$subnet.$i"
                    scanTasks.add(Pair(host, port))
                }
            }

            val totalHosts = scanTasks.size

            // Run all scans in parallel with batch processing
            val batchSize = 50
            var scannedCount = 0
            
            for (batch in scanTasks.chunked(batchSize)) {
                val results = batch.map { (host, port) ->
                    async {
                        if (isAdbServiceAvailable(host, port, timeout)) {
                            Log.d(TAG, "Found ADB device at $host:$port")
                            AdbDevice(host, port)
                        } else {
                            null
                        }
                    }
                }.awaitAll()
                
                results.filterNotNullTo(devices)
                
                scannedCount += batch.size
                // Report progress based on IPs scanned (batch contains 3 connection attempts per IP)
                val ipsScanned = scannedCount / 3
                onProgress?.invoke(ipsScanned, 254, devices.size)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Scan error: ${e.message}", e)
        }

        devices
    }

    private fun buildSubnet(ipAddress: Int): String {
        val part1 = ipAddress and 0xff
        val part2 = (ipAddress shr 8) and 0xff
        val part3 = (ipAddress shr 16) and 0xff
        return "$part1.$part2.$part3"
    }

    private fun isAdbServiceAvailable(host: String, port: Int, timeout: Int): Boolean {
        return try {
            val socket = Socket()
            socket.soTimeout = timeout
            socket.connect(InetSocketAddress(host, port), timeout)
            socket.close()
            true
        } catch (e: Exception) {
            false
        }
    }
}
