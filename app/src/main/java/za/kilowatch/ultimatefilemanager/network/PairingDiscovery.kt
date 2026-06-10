package za.kilowatch.ultimatefilemanager.network

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

object PairingDiscovery {

    private const val TAG = "PairingDiscovery"
    const val DISCOVERY_PORT = 8086
    private const val DISCOVERY_PREFIX = "UFM_DISCOVER:"
    private const val RESOLVE_PREFIX = "UFM_RESOLVE:"
    
    // We will broadcast our Device Info so the seeker knows who we are immediately
    // Format: UFM_RESPONSE:DeviceId:DeviceName:HTTPPort:IsTV
    const val RESPONSE_PREFIX = "UFM_RESPONSE:"

    data class DiscoveredDevice(
        val ipAddress: String,
        val deviceId: String,
        val deviceName: String,
        val httpPort: Int,
        val isTv: Boolean
    )

    /**
     * Sends a UDP broadcast to discover TVs on the local network.
     * Listens for responses for the specified duration.
     * Returns a list of DiscoveredDevice objects.
     */
    suspend fun discoverDevices(
        context: Context, 
        listenDurationMs: Long = 3000
    ): List<DiscoveredDevice> = withContext(Dispatchers.IO) {
        val discoveredList = mutableListOf<DiscoveredDevice>()
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            socket.broadcast = true

            val broadcastAddress = getBroadcastAddress(context)
            if (broadcastAddress == null) {
                Log.e(TAG, "Could not determine broadcast address")
                return@withContext emptyList<DiscoveredDevice>()
            }

            // Send DISCOVER packet
            val message = DISCOVERY_PREFIX.toByteArray()
            val packet = DatagramPacket(message, message.size, broadcastAddress, DISCOVERY_PORT)
            socket.send(packet)
            Log.d(TAG, "Sent DISCOVER broadcast to $broadcastAddress:$DISCOVERY_PORT")

            // Listen for responses
            socket.soTimeout = listenDurationMs.toInt()
            val receiveBuffer = ByteArray(1024)
            val startTime = System.currentTimeMillis()

            while (System.currentTimeMillis() - startTime < listenDurationMs) {
                try {
                    val receivePacket = DatagramPacket(receiveBuffer, receiveBuffer.size)
                    socket.receive(receivePacket)

                    val responseString = String(receivePacket.data, 0, receivePacket.length)
                    val senderIp = receivePacket.address.hostAddress ?: continue
                    
                    Log.d(TAG, "Received broadcast response: $responseString from $senderIp")

                    if (responseString.startsWith(RESPONSE_PREFIX)) {
                        try {
                            // Trim prefix and split by colon
                            val dataStr = responseString.removePrefix(RESPONSE_PREFIX)
                            val parts = dataStr.split(":", limit = 4)
                            if (parts.size >= 4) {
                                val devId = parts[0]
                                val port = parts[1].toInt()
                                val isTv = parts[2].toBoolean()
                                val devName = parts[3]
                                
                                // Only add if it identifies as a TV, and prevent duplicates
                                if (isTv) {
                                    if (discoveredList.none { it.deviceId == devId }) {
                                        discoveredList.add(DiscoveredDevice(senderIp, devId, devName, port, isTv))
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to parse response payload: $responseString", e)
                        }
                    }
                } catch (e: SocketTimeoutException) {
                    // Timeout is expected, just exit the loop
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Error receiving broadcast packet", e)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Exception during discovery", e)
        } finally {
            socket?.close()
        }

        return@withContext discoveredList
    }

    /**
     * Broadcasts a targeted RESOLVE packet looking for a specific Device ID.
     * Useful for finding a paired device whose IP has changed.
     * Returns the new IP if found within the timeout, else null.
     */
    suspend fun resolveDeviceIp(
        context: Context,
        targetDeviceId: String,
        timeoutMs: Long = 2000
    ): String? = withContext<String?>(Dispatchers.IO) {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            socket.broadcast = true

            val broadcastAddress = getBroadcastAddress(context)
            if (broadcastAddress == null) {
                return@withContext null as String?
            }

            // Send RESOLVE:DeviceId packet
            val payload = "$RESOLVE_PREFIX$targetDeviceId"
            val message = payload.toByteArray()
            val packet = DatagramPacket(message, message.size, broadcastAddress, DISCOVERY_PORT)
            socket.send(packet)

            // Listen for specific RESPONSE
            socket.soTimeout = timeoutMs.toInt()
            val receiveBuffer = ByteArray(1024)
            
            var resultIp: String? = null
            while (true) { // Will exit on timeout exception or match
                val receivePacket = DatagramPacket(receiveBuffer, receiveBuffer.size)
                socket.receive(receivePacket)

                val responseString = String(receivePacket.data, 0, receivePacket.length)
                val senderIp = receivePacket.address.hostAddress ?: continue
                
                if (responseString.startsWith(RESPONSE_PREFIX)) {
                    val dataStr = responseString.removePrefix(RESPONSE_PREFIX)
                    val parts = dataStr.split(":", limit = 4)
                    if (parts.isNotEmpty() && parts[0] == targetDeviceId) {
                        resultIp = senderIp
                        break
                    }
                }
            }
            resultIp
        } catch (e: SocketTimeoutException) {
            null
        } catch (e: Exception) {
            null
        } finally {
            socket?.close()
        }
    }

    /**
     * Helper to get the broadcast IP
     */
    private fun getBroadcastAddress(context: Context): InetAddress? {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val dhcp = wifiManager.dhcpInfo
            if (dhcp != null && dhcp.ipAddress != 0) {
                val broadcast = (dhcp.ipAddress and dhcp.netmask) or dhcp.netmask.inv()
                val quads = ByteArray(4)
                for (k in 0..3) {
                    quads[k] = (broadcast shr k * 8 and 0xFF).toByte()
                }
                return InetAddress.getByAddress(quads)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to calculate broadcast address from WiFi, falling back to 255.255.255.255", e)
        }
        
        // Universal fallback broadcast IP
        return InetAddress.getByName("255.255.255.255")
    }
}
