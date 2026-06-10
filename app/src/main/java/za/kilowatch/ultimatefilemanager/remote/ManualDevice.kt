package za.kilowatch.ultimatefilemanager.remote

import za.kilowatch.ultimatefilemanager.network.PairedDevice
import java.util.UUID

/**
 * A manually-added ADB remote device, identified by IP address and user-provided name.
 * Stored as CSV in SharedPreferences alongside other remote transport preferences.
 */
data class ManualDevice(
    val deviceId: String = UUID.randomUUID().toString(),
    val name: String,
    val ip: String,
    val port: Int = 5555
) {
    /**
     * Serialises to CSV for SharedPreferences storage.
     * Commas in [name] are escaped to prevent field corruption.
     */
    fun toCsv(): String {
        val safeName = name.replace(",", "\\,")
        return "$deviceId,$safeName,$ip,$port"
    }

    companion object {
        /**
         * Deserialises from a CSV string. Returns null if the format is invalid.
         */
        fun fromCsv(csv: String): ManualDevice? {
            val parts = csv.split(",")
            if (parts.size < 4) return null
            return try {
                ManualDevice(
                    deviceId = parts[0],
                    name = parts[1].replace("\\,", ","),
                    ip = parts[2],
                    port = parts[3].toIntOrNull() ?: 5555
                )
            } catch (e: Exception) {
                null
            }
        }

        /**
         * Creates a minimal [PairedDevice] from a [ManualDevice] so it can be
         * passed to [AdbWifiTransport] which expects a [PairedDevice].
         */
        fun toPairedDevice(manual: ManualDevice): PairedDevice {
            return PairedDevice(
                deviceId = manual.deviceId,
                name = manual.name,
                lastIp = manual.ip,
                lastPort = manual.port,
                isConnected = false,
                isTv = true
            )
        }
    }
}
