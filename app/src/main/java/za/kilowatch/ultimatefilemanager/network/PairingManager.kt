package za.kilowatch.ultimatefilemanager.network

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.util.UUID

class PairingManager(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val secureStore: SecureTokenStore = SecureTokenStore.getInstance(context)

    companion object {
        private const val PREFS_NAME = "UFM_Pairing_Prefs"
        private const val KEY_MY_DEVICE_ID = "my_device_id"
        private const val KEY_PAIRED_DEVICES = "paired_devices_set"

        @Volatile
        private var INSTANCE: PairingManager? = null

        fun getInstance(context: Context): PairingManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PairingManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    // Get or generate my unique device ID
    fun getMyDeviceId(): String {
        var id = prefs.getString(KEY_MY_DEVICE_ID, null)
        if (id == null) {
            id = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_MY_DEVICE_ID, id).apply()
        }
        return id
    }

    fun getAllPairedDevices(): List<PairedDevice> {
        val deviceStrings = prefs.getStringSet(KEY_PAIRED_DEVICES, emptySet()) ?: emptySet()
        // Migrate any plaintext secrets still embedded in old 9-field CSV entries
        migrateSecretsIfNeeded(deviceStrings)
        return deviceStrings.mapNotNull { PairedDevice.fromSharedPrefsString(it) }
            .map { device ->
                // Hydrate secrets from encrypted store
                device.authToken = secureStore.getToken(device.deviceId)
                device.certFingerprint = secureStore.getFingerprint(device.deviceId)
                device
            }
    }

    fun getPairedDevice(deviceId: String): PairedDevice? {
        return getAllPairedDevices().find { it.deviceId == deviceId }
    }

    fun addOrUpdateDevice(device: PairedDevice) {
        val currentDevices = getAllPairedDevices().toMutableList()
        val index = currentDevices.indexOfFirst { it.deviceId == device.deviceId }
        
        if (index != -1) {
            currentDevices[index] = device
        } else {
            currentDevices.add(device)
        }
        
        saveDevices(currentDevices)
    }

    fun removeDevice(deviceId: String) {
        val currentDevices = getAllPairedDevices().toMutableList()
        currentDevices.removeAll { it.deviceId == deviceId }
        saveDevices(currentDevices)
        // Remove encrypted secrets for this device
        secureStore.remove(deviceId)
    }
    
    fun updateConnectionStatus(deviceId: String, isConnected: Boolean) {
        val device = getPairedDevice(deviceId)
        if (device != null) {
            device.isConnected = isConnected
            addOrUpdateDevice(device)
        }
    }

    // Must only be called from a worker thread (Dispatchers.IO).
    // Uses commit() internally (via SecureTokenStore) rather than apply() to
    // avoid QueuedWork blocking the main thread during Activity.onPause().
    private fun saveDevices(devices: List<PairedDevice>) {
        // Persist secrets to encrypted store before saving the plaintext CSV
        for (device in devices) {
            secureStore.put(device.deviceId, device.authToken, device.certFingerprint)
        }
        val stringSet = devices.map { it.toSharedPrefsString() }.toSet()
        prefs.edit().putStringSet(KEY_PAIRED_DEVICES, stringSet).apply()
    }

    /**
     * One-time migration: if the prefs StringSet still contains old 9-field CSV entries
     * (from before token encryption was introduced), extract the token/fingerprint columns,
     * write them to [SecureTokenStore], and re-save the device list using the new 7-field format.
     *
     * This method is idempotent — once all entries are 7 fields it becomes a no-op.
     */
    private fun migrateSecretsIfNeeded(deviceStrings: Set<String>) {
        val needsMigration = deviceStrings.any { it.split(",").size >= 8 }
        if (!needsMigration) return

        Log.i("PairingManager", "Migrating ${deviceStrings.count { it.split(",").size >= 8 }} device(s) from plaintext to encrypted secret storage")

        val migratedDevices = deviceStrings.mapNotNull { raw ->
            val parts = raw.split(",")
            val device = PairedDevice.fromSharedPrefsString(raw) ?: return@mapNotNull null

            // Extract secrets from the old CSV columns if present
            val legacyFingerprint = if (parts.size >= 8 && parts[7].isNotEmpty()) parts[7] else null
            val legacyToken = if (parts.size >= 9 && parts[8].isNotEmpty()) parts[8] else null

            // Write to encrypted store (only if the encrypted store doesn't already have a value)
            val existingToken = secureStore.getToken(device.deviceId)
            val existingFingerprint = secureStore.getFingerprint(device.deviceId)
            secureStore.put(
                device.deviceId,
                token = existingToken ?: legacyToken,
                fingerprint = existingFingerprint ?: legacyFingerprint
            )

            device.apply {
                authToken = existingToken ?: legacyToken
                certFingerprint = existingFingerprint ?: legacyFingerprint
            }
        }

        // Re-save using the new 7-field format (secrets no longer in CSV)
        val newStringSet = migratedDevices.map { it.toSharedPrefsString() }.toSet()
        prefs.edit().putStringSet(KEY_PAIRED_DEVICES, newStringSet).apply()
        Log.i("PairingManager", "Migration complete — ${migratedDevices.size} device(s) updated")
    }

    // --- Networking Methods for Outgoing Requests ---

    /**
     * Internal generic POST request helper.
     * @param certFingerprint SHA-256 fingerprint for certificate pinning, or null for TOFU.
     */
    private suspend fun postJson(urlStr: String, json: JSONObject, timeout: Int = 3000, certFingerprint: String? = null, authToken: String? = null): String? = withContext(Dispatchers.IO) {
        var connection: javax.net.ssl.HttpsURLConnection? = null
        try {
            connection = LanHttpsClient.openConnection(context, urlStr, certFingerprint)
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("X-UFM-DeviceId", getMyDeviceId())
            if (!authToken.isNullOrEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer $authToken")
            }
            connection.connectTimeout = timeout
            connection.readTimeout = timeout
            connection.doOutput = true

            val out = OutputStreamWriter(connection.outputStream)
            out.write(json.toString())
            out.flush()
            out.close()

            if (connection.responseCode == java.net.HttpURLConnection.HTTP_OK) {
                // Check for token upgrade (legacy device migration)
                val upgradedToken = connection.getHeaderField("X-UFM-Upgraded-Token")
                if (!upgradedToken.isNullOrBlank()) {
                    // Parse deviceId from the POST body to find the right device
                    val targetDeviceId = connection.getHeaderField("X-UFM-DeviceId")
                    Log.d("PairingManager", "Received upgraded token from server")
                }
                return@withContext connection.inputStream.bufferedReader().use { it.readText() }
            } else if (connection.responseCode == 401 || connection.responseCode == 403) {
                 return@withContext "UNAUTHORIZED"
            }
        } catch (e: Exception) {
            // Check if it's an HTTP exception with 401
            if (e.message?.contains("401") == true || e.message?.contains("403") == true) {
                 return@withContext "UNAUTHORIZED"
            }
            Log.e("PairingManager", "POST request failed to $urlStr", e)
        } finally {
            connection?.disconnect()
        }
        return@withContext null
    }

    /**
     * Completes pairing using PIN. Posts to the TV's Server.
     *
     * Uses TOFU (trust-on-first-use) for the initial TLS connection because we
     * don't yet know the server's certificate. The PIN authenticates the peer.
     * After a successful handshake the server cert's SHA-256 fingerprint is
     * captured and stored in [PairedDevice.certFingerprint] for future pinning.
     */
    suspend fun sendPairingRequest(targetIp: String, targetPort: Int, pin: String): Boolean {
        val json = JSONObject().apply {
            put("pin", pin)
            put("deviceId", getMyDeviceId())
            put("deviceName", android.os.Build.MODEL)
            // Just saying we aren't TV in this discovery since typically Phone -> TV
            put("isTv", false)
            put("httpPort", 8085)
            put("manuallyDisconnected", false)
        }

        // Use TOFU (null fingerprint) — first time connecting to this device
        val url = "https://$targetIp:$targetPort/pair"

        // We need the raw connection to capture the cert fingerprint
        return withContext(Dispatchers.IO) {
            var connection: javax.net.ssl.HttpsURLConnection? = null
            try {
                connection = LanHttpsClient.openConnection(context, url, null)
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("X-UFM-DeviceId", getMyDeviceId())
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.doOutput = true

                val out = OutputStreamWriter(connection.outputStream)
                out.write(json.toString())
                out.flush()
                out.close()

                if (connection.responseCode == 401 || connection.responseCode == 403) {
                    return@withContext false  // Wrong PIN
                }

                if (connection.responseCode == java.net.HttpURLConnection.HTTP_OK) {
                    val responseBody = connection.inputStream.bufferedReader().use { it.readText() }

                    // Capture the server certificate fingerprint for future pinning
                    val fingerprint = try {
                        val serverCerts = connection.serverCertificates
                        if (serverCerts.isNotEmpty()) {
                            LanHttpsClient.getCertFingerprint(serverCerts[0])
                        } else null
                    } catch (e: Exception) {
                        Log.w("PairingManager", "Could not capture cert fingerprint", e)
                        null
                    }

                    val resJson = JSONObject(responseBody)
                    val tvId = resJson.getString("deviceId")
                    val tvName = resJson.getString("deviceName")
                    val tvPort = resJson.getInt("httpPort")
                    val isTv = resJson.getBoolean("isTv")
                    val token = resJson.optString("authToken", null)

                    val device = PairedDevice(
                        tvId, tvName, targetIp, tvPort,
                        isConnected = true, isTv = isTv,
                        certFingerprint = fingerprint,
                        authToken = token?.takeIf { it.isNotBlank() }
                    )
                    addOrUpdateDevice(device)
                    return@withContext true
                }
            } catch (e: Exception) {
                Log.e("PairingManager", "Pairing request failed to $url", e)
            } finally {
                connection?.disconnect()
            }
            return@withContext false
        }
    }

    /**
     * Connect to a specific device. Implements UDP Fail-safe if Last IP fails.
     * When connect=false (disconnect), marks the device as manuallyDisconnected so
     * the background ping in onResume does NOT silently restore the connection.
     */
    suspend fun connectToDevice(context: Context, targetDeviceId: String, connect: Boolean): Boolean {
        var device = getPairedDevice(targetDeviceId) ?: return false

        // DISCONNECT: persist intent eagerly so a crash mid-call still honours the
        // user's choice and onResume does not silently reconnect.
        // CONNECT: defer saving until after the network call succeeds — avoids a
        // wasted encrypted write if the device is unreachable.
        if (!connect) {
            device.manuallyDisconnected = true
            device.isConnected = false
            addOrUpdateDevice(device) // single write for disconnect
        }

        val json = JSONObject().apply {
            put("deviceId", getMyDeviceId())
            put("isConnected", connect)
            put("manuallyDisconnected", !connect)
        }

        // Try last known IP
        var url = "https://${device.lastIp}:${device.lastPort}/status"
        var response = postJson(url, json, timeout = 1500, certFingerprint = device.certFingerprint, authToken = device.authToken)

        if (response == null) {
            // FAILED! IP might have changed. UDP Resolve.
            Log.d("PairingManager", "Failed to connect to last IP. Triggering UDP Resolve...")
            val newIp = PairingDiscovery.resolveDeviceIp(context, targetDeviceId, timeoutMs = 2500)

            if (newIp != null) {
                Log.d("PairingManager", "Resolved new IP: $newIp. Updating Config.")
                // Re-fetch in case another coroutine modified the device while we awaited DNS.
                // Carry the resolved IP in memory; it will be flushed in the single save below.
                device = getPairedDevice(targetDeviceId) ?: return false
                device.lastIp = newIp

                // Try again via new IP
                url = "https://${device.lastIp}:${device.lastPort}/status"
                response = postJson(url, json, timeout = 2000, certFingerprint = device.certFingerprint, authToken = device.authToken)
            }
        }

        if (response != null) {
            // For CONNECT: write state once here, after network success (deferred save).
            // For DISCONNECT with a resolved IP: write once more to persist the updated lastIp.
            // Either way this is at most one write per outcome (disconnect without IP
            // resolution already saved above and doesn't need another write here).
            if (connect) {
                device.manuallyDisconnected = false
                device.isConnected = true
            }
            addOrUpdateDevice(device)
            return true
        }

        // Network call failed.
        // Disconnect path: state was persisted eagerly above — no extra write needed.
        // Connect path: nothing to persist since we didn't succeed.
        return connect // false for failed connect; true for disconnect (always succeeds locally)
    }

    /**
     * Re-name device on the remote end
     */
    suspend fun renameRemoteDevice(targetDeviceId: String, newName: String): Boolean {
        val device = getPairedDevice(targetDeviceId) ?: return false
        val json = JSONObject().apply {
            put("deviceId", getMyDeviceId())
            put("newName", newName)
        }
        val url = "https://${device.lastIp}:${device.lastPort}/rename"
        return postJson(url, json, certFingerprint = device.certFingerprint, authToken = device.authToken) != null
    }

    /**
     * Ask the remote device to remove us from its list
     */
    suspend fun unpairRemoteDevice(device: PairedDevice): Boolean {
        val json = JSONObject().apply {
            put("deviceId", getMyDeviceId())
        }
        val url = "https://${device.lastIp}:${device.lastPort}/unpair"
        return postJson(url, json, timeout = 2500, certFingerprint = device.certFingerprint, authToken = device.authToken) != null
    }

    /**
     * Lightweight ping to see if device HTTP server is reachable
     */
    suspend fun pingDevice(device: PairedDevice): Boolean = withContext(Dispatchers.IO) {
        var connection: javax.net.ssl.HttpsURLConnection? = null
        try {
            connection = LanHttpsClient.openConnection(
                context,
                "https://${device.lastIp}:${device.lastPort}/",
                device.certFingerprint
            )
            connection.requestMethod = "GET"
            connection.connectTimeout = 1500
            connection.readTimeout = 1500
            connection.responseCode
            return@withContext true
        } catch (e: Exception) {
            return@withContext false
        } finally {
            connection?.disconnect()
        }
    }
}
