package za.kilowatch.ultimatefilemanager.network

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.URLEncoder
import za.kilowatch.ultimatefilemanager.UfmApplication

/**
 * Client for interacting with a connected TV's PairingServer over HTTPS.
 * All connections use [LanHttpsClient] which pins to the bundled self-signed certificate.
 */
object TvShareClient {

    private fun getBaseUrl(share: NetworkShare): String {
        return "https://${share.host}:${share.port}"
    }

    private fun context(): Context = UfmApplication.instance

    private fun getDeviceForShare(share: NetworkShare): PairedDevice? {
        val pm = PairingManager.getInstance(context())
        return pm.getAllPairedDevices().find {
            it.lastIp == share.host && it.lastPort == share.port
        }
    }

    private fun addAuthHeaders(connection: javax.net.ssl.HttpsURLConnection, share: NetworkShare) {
        val myId = PairingManager.getInstance(context()).getMyDeviceId()
        connection.setRequestProperty("X-UFM-DeviceId", myId)
        
        val device = getDeviceForShare(share)
        if (device != null && !device.authToken.isNullOrEmpty()) {
            connection.setRequestProperty("Authorization", "Bearer ${device.authToken}")
        }
    }

    private fun checkUpgradedToken(share: NetworkShare, connection: javax.net.ssl.HttpsURLConnection) {
        val upgradedToken = connection.getHeaderField("X-UFM-Upgraded-Token")
        if (!upgradedToken.isNullOrEmpty()) {
            val pm = PairingManager.getInstance(context())
            val device = getDeviceForShare(share)
            if (device != null && device.authToken != upgradedToken) {
                device.authToken = upgradedToken
                pm.addOrUpdateDevice(device)
            }
        }
    }

    /**
     * Resolves the stored certificate fingerprint for a TV share by matching
     * the share's host and port against the paired devices list.
     */
    private fun getCertFingerprintForShare(share: NetworkShare): String? {
        return getDeviceForShare(share)?.certFingerprint
    }

    // A simple POST request for commands
    private fun postCommand(share: NetworkShare, endpoint: String, jsonBody: JSONObject) {
        val urlStr = "${getBaseUrl(share)}$endpoint"
        var connection: javax.net.ssl.HttpsURLConnection? = null
        try {
            connection = LanHttpsClient.openConnection(context(), urlStr, getCertFingerprintForShare(share))
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Connection", "close") // Prevent Keep-Alive starvation
            addAuthHeaders(connection, share)
            connection.connectTimeout = 10000
            connection.readTimeout = 15000

            val bodyString = jsonBody.toString()
            connection.outputStream.use { out ->
                out.write(bodyString.toByteArray(Charsets.UTF_8))
            }

            val code = connection.responseCode
            checkUpgradedToken(share, connection)
            if (code !in 200..299) {
                val errorMsg = try { connection.errorStream?.bufferedReader()?.readText() } catch (e: Exception) { "Unknown Error" }
                throw Exception("TV Error ($code): $errorMsg")
            }
        } finally {
            connection?.disconnect()
        }
    }

    fun listFiles(share: NetworkShare, path: String): List<NetworkFile> {
        val urlStr = if (path.isEmpty()) {
            "${getBaseUrl(share)}/tv/drives"
        } else {
            "${getBaseUrl(share)}/tv/files?path=${URLEncoder.encode(path, "UTF-8")}"
        }

        var connection: javax.net.ssl.HttpsURLConnection? = null
        try {
            connection = LanHttpsClient.openConnection(context(), urlStr, getCertFingerprintForShare(share))
            connection.requestMethod = "GET"
            connection.setRequestProperty("Connection", "close") // Prevent Keep-Alive starvation
            addAuthHeaders(connection, share)
            connection.connectTimeout = 10000
            connection.readTimeout = 15000

            val code = connection.responseCode
            checkUpgradedToken(share, connection)
            if (code == 200) {
                val responseString = connection.inputStream.bufferedReader().use { it.readText() }
                val jsonArray = JSONArray(responseString)
                val files = mutableListOf<NetworkFile>()
                for (i in 0 until jsonArray.length()) {
                    val item = jsonArray.getJSONObject(i)
                    val folderName = item.getString("name")
                    // Use `label` (human-readable) if provided by the server; fall back to raw folder name
                    val displayName = item.optString("label", "").takeIf { it.isNotBlank() } ?: folderName
                    files.add(
                        NetworkFile(
                            name = displayName,  // Shown in UI (e.g. "Internal Storage")
                            path = item.getString("path"),
                            isDirectory = item.getBoolean("isDirectory"),
                            size = item.optLong("size", 0L),
                            lastModified = item.optLong("lastModified", 0L),
                            freeSpace = item.optLong("freeSpace", -1L)
                        )
                    )
                }
                return files
            } else {
                val errorMsg = try { connection.errorStream?.bufferedReader()?.readText() } catch (e: Exception) { "Unknown Error" }
                throw Exception("Failed to list TV files ($code): $errorMsg")
            }
        } finally {
            connection?.disconnect()
        }
    }

    fun mkdir(share: NetworkShare, path: String) {
        postCommand(share, "/tv/mkdir", JSONObject().put("path", path))
    }

    fun deleteFile(share: NetworkShare, path: String) {
        postCommand(share, "/tv/delete", JSONObject().put("path", path))
    }

    fun deleteDir(share: NetworkShare, path: String) {
        postCommand(share, "/tv/delete", JSONObject().put("path", path))
    }

    fun rename(share: NetworkShare, path: String, newPath: String) {
        postCommand(share, "/tv/rename", JSONObject().apply {
            put("path", path)
            put("newPath", newPath)
        })
    }

    suspend fun openInputStream(device: NetworkShare, remotePath: String): InputStream = withContext(Dispatchers.IO) {
        val urlStr = "${getBaseUrl(device)}/tv/read?path=${URLEncoder.encode(remotePath, "UTF-8")}"
        val connection = LanHttpsClient.openConnection(context(), urlStr, getCertFingerprintForShare(device))
        connection.requestMethod = "GET"
        connection.setRequestProperty("Connection", "close")
        addAuthHeaders(connection, device)
        connection.connectTimeout = 10000
        connection.readTimeout = 0 // Infinite read timeout while streaming

        val code = connection.responseCode
        checkUpgradedToken(device, connection)
        if (code in 200..299) {
            return@withContext connection.inputStream
        } else {
            val errorMsg = try { connection.errorStream?.bufferedReader()?.readText() } catch (e: Exception) { "Unknown Error" }
            connection.disconnect()
            throw Exception("Failed to open TV input stream (${connection.responseCode}): $errorMsg")
        }
    }

    /**
     * Uploads a file to the TV by streaming with explicit Content-Length.
     * NanoHTTPD cannot de-chunk Transfer-Encoding:chunked bodies on the server side,
     * so we must set the file size upfront.
     */
    suspend fun uploadStream(device: NetworkShare, remotePath: String, inputStream: InputStream, totalSize: Long) = withContext(Dispatchers.IO) {
        val urlStr = "${getBaseUrl(device)}/tv/write?path=${URLEncoder.encode(remotePath, "UTF-8")}"
        val connection = LanHttpsClient.openConnection(context(), urlStr, getCertFingerprintForShare(device))
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Connection", "close")
        connection.setRequestProperty("Content-Type", "application/octet-stream")
        addAuthHeaders(connection, device)
        connection.setFixedLengthStreamingMode(totalSize) // Explicit length — no chunked framing
        connection.connectTimeout = 10000
        connection.readTimeout = 0 // Streaming mode, no timeout on reads

        connection.outputStream.use { out ->
            za.kilowatch.ultimatefilemanager.util.CopyHelper.copy(inputStream, out, totalSize)
        }

        val code = connection.responseCode
        checkUpgradedToken(device, connection)
        if (code !in 200..299) {
            val errorMsg = try { connection.errorStream?.bufferedReader()?.readText() } catch (e: Exception) { "Unknown" }
            connection.disconnect()
            throw Exception("TV upload failed ($code): $errorMsg")
        }
        connection.disconnect()
    }

    /**
     * Triggers APK installation on the TV for a file already uploaded there.
     */
    fun installApk(share: NetworkShare, remotePath: String) {
        postCommand(share, "/tv/install-apk", JSONObject().put("path", remotePath))
    }

    /**
     * Starts an XAPK install job on the TV. Returns the jobId for status polling.
     */
    fun installXapk(share: NetworkShare, remotePath: String): String {
        val urlStr = "${getBaseUrl(share)}/tv/install-xapk"
        var connection: javax.net.ssl.HttpsURLConnection? = null
        try {
            connection = LanHttpsClient.openConnection(context(), urlStr, getCertFingerprintForShare(share))
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Connection", "close")
            addAuthHeaders(connection, share)
            connection.connectTimeout = 10000
            connection.readTimeout = 30000

            val body = JSONObject().put("path", remotePath).toString()
            connection.outputStream.use { out ->
                out.write(body.toByteArray(Charsets.UTF_8))
            }

            val code = connection.responseCode
            checkUpgradedToken(share, connection)
            if (code in 200..299) {
                val responseStr = connection.inputStream.bufferedReader().use { it.readText() }
                return JSONObject(responseStr).optString("jobId", "")
            } else {
                val errorMsg = try { connection.errorStream?.bufferedReader()?.readText() } catch (e: Exception) { "Unknown Error" }
                throw Exception("TV XAPK install error ($code): $errorMsg")
            }
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Polls the XAPK install job status on the TV.
     * Returns a JSONObject with: status, current, total, currentFile, packageLabel, error
     */
    fun getXapkInstallStatus(share: NetworkShare, jobId: String): JSONObject {
        val urlStr = "${getBaseUrl(share)}/tv/install-xapk-status?jobId=${URLEncoder.encode(jobId, "UTF-8")}"
        var connection: javax.net.ssl.HttpsURLConnection? = null
        try {
            connection = LanHttpsClient.openConnection(context(), urlStr, getCertFingerprintForShare(share))
            connection.requestMethod = "GET"
            connection.setRequestProperty("Connection", "close")
            addAuthHeaders(connection, share)
            connection.connectTimeout = 10000
            connection.readTimeout = 15000

            val code = connection.responseCode
            checkUpgradedToken(share, connection)
            if (code in 200..299) {
                val responseStr = connection.inputStream.bufferedReader().use { it.readText() }
                return JSONObject(responseStr)
            } else {
                throw Exception("TV XAPK status check failed ($code)")
            }
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Checks whether ADB (USB debugging) is enabled on the TV.
     */
    fun isAdbEnabled(share: NetworkShare): Boolean {
        val urlStr = "${getBaseUrl(share)}/tv/adb-status"
        var connection: javax.net.ssl.HttpsURLConnection? = null
        return try {
            connection = LanHttpsClient.openConnection(context(), urlStr, getCertFingerprintForShare(share))
            connection.requestMethod = "GET"
            connection.setRequestProperty("Connection", "close")
            addAuthHeaders(connection, share)
            connection.connectTimeout = 5000
            connection.readTimeout = 5000

            val code = connection.responseCode
            checkUpgradedToken(share, connection)
            if (code in 200..299) {
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                JSONObject(body).optBoolean("adbEnabled", false)
            } else {
                false
            }
        } catch (e: Exception) {
            false
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Sends a settings transfer payload to the TV over the existing pinned HTTPS channel.
     *
     * Security:
     *  - Payload is a raw [ByteArray] — never held in a String.
     *  - Sent over the pinned TLS tunnel (LanHttpsClient) with a valid Bearer token.
     *  - Contains a one-time nonce for replay protection (server enforces uniqueness).
     *
     * @return the HTTP response code (202 = accepted by TV, awaiting user approval)
     * @throws Exception on connection failure or non-2xx HTTP response
     */
    fun transferSettings(share: NetworkShare, payloadBytes: ByteArray): Int {
        val urlStr = "${getBaseUrl(share)}/tv/transfer-settings"
        var connection: javax.net.ssl.HttpsURLConnection? = null
        return try {
            connection = LanHttpsClient.openConnection(context(), urlStr, getCertFingerprintForShare(share))
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Content-Length", payloadBytes.size.toString())
            connection.setRequestProperty("Connection", "close")
            addAuthHeaders(connection, share)
            connection.connectTimeout = 10000
            connection.readTimeout = 15000

            connection.outputStream.use { out -> out.write(payloadBytes) }

            val code = connection.responseCode
            checkUpgradedToken(share, connection)
            if (code !in 200..299) {
                val errorMsg = try {
                    connection.errorStream?.bufferedReader()?.readText()
                } catch (e: Exception) { "Unknown Error" }
                throw Exception("Transfer settings failed ($code): $errorMsg")
            }
            code
        } finally {
            connection?.disconnect()
        }
    }
}
