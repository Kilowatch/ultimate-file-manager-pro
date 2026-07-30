package za.kilowatch.ultimatefilemanager.network

import za.kilowatch.ultimatefilemanager.util.safeDirectoryPath

import za.kilowatch.ultimatefilemanager.R

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import za.kilowatch.ultimatefilemanager.remote.InstallReceiver
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.URLDecoder
import java.security.KeyStore
import java.util.UUID
import java.util.zip.ZipFile
import android.os.storage.StorageManager


class PairingServer(
    private val context: Context,
    private val port: Int = 8085
) : NanoHTTPD(port) {

    private val pairingManager = PairingManager.getInstance(context)
    private var isListeningUdp = false

    // If non-null, we are actively showing a PIN and accepting pairs
    private var activePin: String? = null

    // XAPK install job tracking (mirrors FileServer.XapkJob)
    private data class XapkJob(
        var status: String = "extracting",
        var current: Int = 0,
        var total: Int = 0,
        var currentFile: String = "",
        var packageLabel: String = "",
        var error: String? = null
    )
    private val xapkJobs = java.util.concurrent.ConcurrentHashMap<String, XapkJob>()

    // Transfer-settings replay protection & rate-limiting (C-2 / H-3)
    private val seenNonces = java.util.LinkedList<String>()
    private val transferRateLimit = java.util.concurrent.ConcurrentHashMap<String, Long>() // deviceId -> lastCallMs
    private val MAX_TRANSFER_PAYLOAD_BYTES = 512 * 1024  // 512 KB

    // H-4: Brute-force protection for pairing PIN
    private val pairingFailedAttempts = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, Long>>()
    private val PAIRING_MAX_ATTEMPTS = 5
    private val PAIRING_LOCKOUT_MS = 60_000L
    private val PAIRING_LOCKOUT_ESCALATED_MS = 600_000L
    private val PAIRING_LOCKOUT_DEACTIVATE = 15

    init {
        startUdpListener()
    }

    /**
     * Starts NanoHTTPD with TLS.
     *
     * Uses an AndroidKeyStore-backed ECDSA key pair (secp256r1).  EC was chosen over RSA
     * because ECDSA only ever requires signing operations — no PURPOSE_DECRYPT needed —
     * so the same key works cleanly with both TLS 1.2 (ECDHE-ECDSA cipher suites) and
     * TLS 1.3 (ECDSA_SECP256R1_SHA256 signatures).
     *
     * The SSLContext is built manually to avoid NanoHTTPD's helper calling
     * TrustManagerFactory.init(androidKeyStore) — which silently produces zero trust
     * anchors because AndroidKeyStore only has PrivateKeyEntry, not TrustedCertificateEntry.
     *
     * Regeneration: destroying app data clears the key; a new one is generated on next start.
     */
    fun startSecure() {
        try {
            val aks = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
            val alias = "ufm_pairing_ecdsa_v3"

            // Delete all known legacy aliases — stale keys without DIGEST_NONE
            // can be accidentally picked by the KeyManagerFactory.
            listOf("ufm_pairing_server", "ufm_pairing_ecdsa_v2").forEach { legacy ->
                if (aks.containsAlias(legacy)) {
                    aks.deleteEntry(legacy)
                    Log.d("PairingServer", "Removed legacy pairing key: $legacy")
                }
            }

            // Generate on first launch (or after migration)
            if (!aks.containsAlias(alias)) {
                val kpg = java.security.KeyPairGenerator.getInstance(
                    android.security.keystore.KeyProperties.KEY_ALGORITHM_EC,
                    "AndroidKeyStore"
                )
                kpg.initialize(
                    android.security.keystore.KeyGenParameterSpec.Builder(
                        alias,
                        android.security.keystore.KeyProperties.PURPOSE_SIGN
                    )
                        .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
                        .setDigests(
                            android.security.keystore.KeyProperties.DIGEST_NONE,   // Conscrypt signs pre-hashed bytes via NONEwithECDSA
                            android.security.keystore.KeyProperties.DIGEST_SHA256,
                            android.security.keystore.KeyProperties.DIGEST_SHA512
                        )
                        .setCertificateSubject(
                            javax.security.auth.x500.X500Principal("CN=UFM Pairing")
                        )
                        .build()
                )
                kpg.generateKeyPair()
                Log.d("PairingServer", "Generated ECDSA+DIGEST_NONE key ($alias)")
            }

            val kmf = javax.net.ssl.KeyManagerFactory.getInstance(
                javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm()
            )
            kmf.init(aks, null)

            // Wrap with a pinned KeyManager so only our alias is ever presented.
            // Guards against residual AndroidKeyStore keys being selected instead.
            val delegate = kmf.keyManagers[0] as javax.net.ssl.X509KeyManager
            val pinned = object : javax.net.ssl.X509KeyManager {
                override fun getClientAliases(t: String?, i: Array<out java.security.Principal>?) = null
                override fun chooseClientAlias(t: Array<out String>?, i: Array<out java.security.Principal>?, s: java.net.Socket?) = null
                override fun getServerAliases(t: String?, i: Array<out java.security.Principal>?) = arrayOf(alias)
                override fun chooseServerAlias(t: String?, i: Array<out java.security.Principal>?, s: java.net.Socket?) = alias
                override fun getCertificateChain(a: String?) = delegate.getCertificateChain(a)
                override fun getPrivateKey(a: String?) = delegate.getPrivateKey(a)
            }

            val sslCtx = javax.net.ssl.SSLContext.getInstance("TLS")
            sslCtx.init(arrayOf(pinned), null, java.security.SecureRandom())
            makeSecure(sslCtx.serverSocketFactory, null)
            Log.d("PairingServer", "HTTPS ready (ECDSA/$alias)")
        } catch (e: Exception) {
            Log.e("PairingServer", "Failed to configure HTTPS, falling back to HTTP", e)
        }
        start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)

        // On startup, restore all non-manually-disconnected devices as connected
        // so the TV accepts requests immediately without requiring the mobile to re-connect
        restoreConnectedDevices()
    }

    /**
     * Marks all paired devices that weren't manually disconnected as connected.
     * Called at startup so the TV always accepts requests from paired devices
     * without requiring them to re-send a /status POST.
     */
    private fun restoreConnectedDevices() {
        val devices = pairingManager.getAllPairedDevices()
        var restored = 0
        for (device in devices) {
            if (!device.manuallyDisconnected && !device.isConnected) {
                device.isConnected = true
                pairingManager.addOrUpdateDevice(device)
                restored++
            }
        }
        if (restored > 0) {
            Log.d("PairingServer", "Restored $restored paired device(s) as connected")
        }
    }

    /**
     * Start accepting pairing requests with a specific PIN 
     * (used by the TV side)
     */
    fun startPairingMode(pin: String) {
        activePin = pin
    }

    fun stopPairingMode() {
        activePin = null
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method
        Log.d("GoRoScreen", "serve() called: method=$method uri=$uri")

        return try {
            when {
                method == Method.POST && uri == "/pair" -> handlePairRequest(session)

                // Management endpoints — require Bearer token auth
                method == Method.POST && uri == "/status" -> {
                    val authResult = isManagementAuthorized(session)
                    if (!authResult.first) {
                        newFixedLengthResponse(Response.Status.FORBIDDEN, "application/json", "{\"error\":\"Forbidden: Invalid or missing auth token\"}")
                    } else {
                        val response = handleStatusRequest(session)
                        authResult.second?.let { response.addHeader("X-UFM-Upgraded-Token", it) }
                        response
                    }
                }
                method == Method.POST && uri == "/rename" -> {
                    val authResult = isManagementAuthorized(session)
                    if (!authResult.first) {
                        newFixedLengthResponse(Response.Status.FORBIDDEN, "application/json", "{\"error\":\"Forbidden: Invalid or missing auth token\"}")
                    } else {
                        val response = handleRenameRequest(session)
                        authResult.second?.let { response.addHeader("X-UFM-Upgraded-Token", it) }
                        response
                    }
                }
                method == Method.POST && uri == "/unpair" -> {
                    val authResult = isManagementAuthorized(session)
                    if (!authResult.first) {
                        newFixedLengthResponse(Response.Status.FORBIDDEN, "application/json", "{\"error\":\"Forbidden: Invalid or missing auth token\"}")
                    } else {
                        val response = handleUnpairRequest(session)
                        authResult.second?.let { response.addHeader("X-UFM-Upgraded-Token", it) }
                        response
                    }
                }
                
                // TV Storage Endpoints
                uri.startsWith("/tv/") -> {
                    val authResult = isDeviceAuthorized(session)
                    val authorized = authResult.first
                    val upgradedToken = authResult.second
                    Log.d("GoRoScreen", "/tv/ route hit: authorized=$authorized, method=$method, uri=$uri")
                    if (!authorized) {
                        newFixedLengthResponse(Response.Status.FORBIDDEN, "application/json", "{\"error\":\"Forbidden: Device not connected or missing valid token\"}")
                    } else {
                        val response = when {
                            method == Method.GET && uri == "/tv/drives" -> handleTvDrives()
                            method == Method.GET && uri == "/tv/files" -> handleTvFiles(session)
                            method == Method.GET && uri == "/tv/read" -> handleTvRead(session)
                            method == Method.POST && uri == "/tv/write" -> handleTvWrite(session)
                            method == Method.POST && uri == "/tv/mkdir" -> handleTvMkdir(session)
                            method == Method.POST && uri == "/tv/delete" -> handleTvDelete(session)
                            method == Method.POST && uri == "/tv/rename" -> handleTvRename(session)
                            method == Method.POST && uri == "/tv/install-apk" -> handleTvInstallApk(session)
                            method == Method.POST && uri == "/tv/install-xapk" -> handleTvInstallXapk(session)
                            method == Method.GET && uri == "/tv/install-xapk-status" -> handleTvInstallXapkStatus(session)
                            method == Method.GET && uri == "/tv/adb-status" -> handleTvAdbStatus()
                            method == Method.POST && uri == "/tv/transfer-settings" -> handleTvTransferSettings(session)
                            else -> {
                                Log.w("GoRoScreen", "No route matched for: method=$method uri=$uri")
                                newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
                            }
                        }
                        if (upgradedToken != null) {
                            response.addHeader("X-UFM-Upgraded-Token", upgradedToken)
                        }
                        response
                    }
                }
                
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
            }
        } catch (e: Exception) {
            Log.e("PairingServer", "Error serving response", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Internal Server Error")
        }
    }

    private fun handlePairRequest(session: IHTTPSession): Response {
        // Only accept if we have an active PIN
        if (activePin == null) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Not in pairing mode")
        }

        val requesterIp = session.headers["remote-addr"] ?: "0.0.0.0"
        val now = System.currentTimeMillis()

        // H-4: Check per-IP lockout before processing
        pairingFailedAttempts[requesterIp]?.let { (count, expiry) ->
            if (count >= PAIRING_MAX_ATTEMPTS && now < expiry) {
                val remaining = (expiry - now) / 1000
                Log.w("PairingServer", "Pairing locked out for $requesterIp ($remaining s remaining)")
                return newFixedLengthResponse(
                    Response.Status.TOO_MANY_REQUESTS,
                    "application/json",
                    "{\"error\":\"Too many attempts. Try again in ${remaining}s.\"}"
                )
            }
        }

        val postData = HashMap<String, String>()
        session.parseBody(postData)

        // Read body JSON
        val bodyStr = postData["postData"] ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing body")
        val json = JSONObject(bodyStr)

        val providedPin = json.optString("pin")
        if (providedPin != activePin) {
            // H-4: Track failed attempt
            val current = pairingFailedAttempts[requesterIp] ?: Pair(0, 0L)
            val nextCount = current.first + 1
            val lockoutMs = if (nextCount >= PAIRING_LOCKOUT_DEACTIVATE) {
                Log.w("PairingServer", "Pairing mode auto-deactivated — $nextCount failed attempts from $requesterIp")
                activePin = null
                Long.MAX_VALUE // permanent for this session
            } else if (nextCount >= PAIRING_MAX_ATTEMPTS * 2) {
                PAIRING_LOCKOUT_ESCALATED_MS
            } else {
                PAIRING_LOCKOUT_MS
            }
            val nextExpiry = if (lockoutMs == Long.MAX_VALUE) Long.MAX_VALUE else now + lockoutMs
            pairingFailedAttempts[requesterIp] = Pair(nextCount, nextExpiry)
            Log.w("PairingServer", "Pairing PIN failed ($nextCount) from $requesterIp")
            return newFixedLengthResponse(Response.Status.UNAUTHORIZED, MIME_PLAINTEXT, "Invalid PIN")
        }

        // H-4: Successful pairing — reset lockout for this IP
        pairingFailedAttempts.remove(requesterIp)

        // Add to our Paired Devices
        val deviceId = json.optString("deviceId")
        val deviceName = json.optString("deviceName")
        val httpPort = json.optInt("httpPort", 8085)
        val isTv = json.optBoolean("isTv", false)
        val manuallyDisconnected = json.optBoolean("manuallyDisconnected", false)
        val newToken = java.util.UUID.randomUUID().toString()

        val pairedDevice = PairedDevice(deviceId, deviceName, requesterIp, httpPort, isConnected = !manuallyDisconnected, isTv = isTv, manuallyDisconnected = manuallyDisconnected, authToken = newToken)
        pairingManager.addOrUpdateDevice(pairedDevice)

        // Respond with our info — include our cert fingerprint for client-side pinning
        val responseJson = JSONObject().apply {
            put("deviceId", pairingManager.getMyDeviceId())
            put("deviceName", getDeviceName())
            put("httpPort", port)
            put("isTv", isTvDevice())
            // Include our cert fingerprint so the client can verify/store it
            try {
                val aks = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
                val cert = aks.getCertificate("ufm_pairing_ecdsa_v3")
                if (cert != null) {
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                    val hash = digest.digest(cert.encoded)
                    put("certFingerprint", hash.joinToString("") { "%02x".format(it) })
                }
            } catch (e: Exception) {
                Log.w("PairingServer", "Could not include cert fingerprint in pairing response", e)
            }
            put("authToken", newToken)
        }

        // Once paired successfully, we can turn off pairing mode (optional, depending on UX)
        activePin = null
        
        // Broadcast an event so UI can update - Make it explicit for reliability
        val intent = android.content.Intent("za.kilowatch.ufm.PAIRING_UPDATED")
        intent.setPackage(context.packageName)
        intent.putExtra("newly_paired_device_name", deviceName)
        context.sendBroadcast(intent)

        return newFixedLengthResponse(Response.Status.OK, "application/json", responseJson.toString())
    }

    private fun handleStatusRequest(session: IHTTPSession): Response {
        val postData = HashMap<String, String>()
        session.parseBody(postData)
        val bodyStr = postData["postData"] ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing body")
        
        val json = JSONObject(bodyStr)
        val deviceId = json.optString("deviceId")
        val isConnected = json.optBoolean("isConnected", false)
        val manuallyDisconnected = json.optBoolean("manuallyDisconnected", false)

        Log.d("PairingServer", "Status request from $deviceId: connected=$isConnected, manual=$manuallyDisconnected")

        val device = pairingManager.getPairedDevice(deviceId)
        if (device != null) {
            // Update IP in case it changed during connection
            val requesterIp = session.headers["remote-addr"] ?: device.lastIp
            device.lastIp = requesterIp
            device.isConnected = isConnected
            device.manuallyDisconnected = manuallyDisconnected
            pairingManager.addOrUpdateDevice(device)
            val intent = android.content.Intent("za.kilowatch.ufm.PAIRING_UPDATED")
            intent.setPackage(context.packageName)
            context.sendBroadcast(intent)
            return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "Success")
        }

        return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Device not paired")
    }

    private fun handleRenameRequest(session: IHTTPSession): Response {
        val postData = HashMap<String, String>()
        session.parseBody(postData)
        val bodyStr = postData["postData"] ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing body")
        
        val json = JSONObject(bodyStr)
        val deviceId = json.optString("deviceId")
        val newName = json.optString("newName")

        val device = pairingManager.getPairedDevice(deviceId)
        if (device != null && newName.isNotEmpty()) {
            device.name = newName
            pairingManager.addOrUpdateDevice(device)
            val intent = android.content.Intent("za.kilowatch.ufm.PAIRING_UPDATED")
            intent.setPackage(context.packageName)
            context.sendBroadcast(intent)
            return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "Success")
        }
        return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Invalid request")
    }

    private fun handleUnpairRequest(session: IHTTPSession): Response {
        val postData = HashMap<String, String>()
        session.parseBody(postData)
        val bodyStr = postData["postData"] ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing body")
        
        val json = JSONObject(bodyStr)
        val deviceId = json.optString("deviceId")

        val device = pairingManager.getPairedDevice(deviceId)
        if (device != null) {
            pairingManager.removeDevice(deviceId)
            val intent = android.content.Intent("za.kilowatch.ufm.PAIRING_UPDATED")
            intent.setPackage(context.packageName)
            context.sendBroadcast(intent)
            return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "Success")
        }
        return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "Not found, but considered success")
    }

    // --- UDP Listener ---
    // Listens for DISCOVER and RESOLVE packets
    private fun startUdpListener() {
        if (isListeningUdp) return
        isListeningUdp = true

        GlobalScope.launch(Dispatchers.IO) {
            var socket: DatagramSocket? = null
            var multicastLock: WifiManager.MulticastLock? = null
            try {
                // Acquire MulticastLock to ensure we receive UDP broadcasts reliably in background
                val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                if (wm != null) {
                    multicastLock = wm.createMulticastLock("UFMPairingLock")
                    multicastLock?.setReferenceCounted(true)
                    multicastLock?.acquire()
                    Log.d("PairingServer", "MulticastLock acquired")
                } else {
                    Log.w("PairingServer", "WifiManager unavailable — MulticastLock not acquired (TV/Ethernet-only device)")
                }
                val ds = DatagramSocket(null)
                ds.reuseAddress = true
                ds.bind(java.net.InetSocketAddress(PairingDiscovery.DISCOVERY_PORT))
                socket = ds
                socket.broadcast = true
                val buffer = ByteArray(1024)

                while (isListeningUdp) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)

                    val data = String(packet.data, 0, packet.length)
                    val senderIp = packet.address
                    
                    Log.d("PairingServer", "UDP Packet received from ${senderIp.hostAddress}: $data")

                    // Only process requests, ignore our own broadcasts/responses
                    if (data.startsWith("UFM_DISCOVER:")) {
                        // Someone is looking for TVs/Devices. Respond with our details.
                        val myId = pairingManager.getMyDeviceId()
                        val myName = getDeviceName()
                        val myTvStatus = isTvDevice()
                        val activePort = za.kilowatch.ultimatefilemanager.remote.FileServer.getActivePort() ?: port
                        val responsePayload = "${PairingDiscovery.RESPONSE_PREFIX}$myId:$activePort:$myTvStatus:$myName"
                        val responseBytes = responsePayload.toByteArray()
                        val responsePacket = DatagramPacket(responseBytes, responseBytes.size, senderIp, packet.port)
                        socket.send(responsePacket)
                    } else if (data.startsWith("UFM_RESOLVE:")) {
                        // Someone is looking for a specific Device ID to get its IP
                        val targetId = data.removePrefix("UFM_RESOLVE:")
                        if (targetId == pairingManager.getMyDeviceId()) {
                            // That's me! Respond so they get my new IP
                            val myId = pairingManager.getMyDeviceId()
                            val myName = getDeviceName()
                            val myTvStatus = isTvDevice()
                            val activePort = za.kilowatch.ultimatefilemanager.remote.FileServer.getActivePort() ?: port
                            val responsePayload = "${PairingDiscovery.RESPONSE_PREFIX}$myId:$activePort:$myTvStatus:$myName"
                            val responseBytes = responsePayload.toByteArray()
                            val responsePacket = DatagramPacket(responseBytes, responseBytes.size, senderIp, packet.port)
                            socket.send(responsePacket)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("PairingServer", "UDP Listener error", e)
            } finally {
                socket?.close()
                try {
                    if (multicastLock?.isHeld == true) {
                        multicastLock.release()
                        Log.d("PairingServer", "MulticastLock released")
                    }
                } catch (e: Exception) {
                    Log.e("PairingServer", "Error releasing MulticastLock", e)
                }
                isListeningUdp = false
            }
        }
    }

    override fun stop() {
        isListeningUdp = false
        super.stop()
    }

    private fun getDeviceName(): String {
        // Try getting user defined device name, fallback to model
        val prefsName = context.getSharedPreferences("UFM_Pairing_Prefs", Context.MODE_PRIVATE).getString("my_tv_name", null)
        if (prefsName != null) return prefsName
        
        val defaultName = Build.MODEL ?: context.getString(R.string.android_device)
        return Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) ?: defaultName
    }

    private fun isTvDevice(): Boolean {
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as android.app.UiModeManager
        return uiModeManager.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
    }

    // --- TV Storage Endpoints ---

    /**
     * Verifies that the requester is both paired AND currently marked as connected.
     * Generates and validates Bearer tokens.
     */
    /**
     * Authorizes management endpoints (/status, /rename, /unpair).
     *
     * Looks up the device by the X-UFM-DeviceId header (or by requester IP as
     * a fallback for legacy clients), then validates the Authorization Bearer
     * token against the stored [PairedDevice.authToken].
     *
     * Legacy migration: if the device has no stored token (paired before tokens
     * were introduced), it is auto-upgraded with a new token. The new token is
     * returned via the X-UFM-Upgraded-Token response header so the client can
     * persist it for future requests.
     *
     * @return Pair(authorized, upgradedTokenOrNull)
     */
    private fun isManagementAuthorized(session: IHTTPSession): Pair<Boolean, String?> {
        val deviceIdHeader = session.headers["x-ufm-deviceid"]
        val requesterIp = session.headers["remote-addr"] ?: "0.0.0.0"

        val devices = pairingManager.getAllPairedDevices()

        val device = if (deviceIdHeader != null) {
            devices.find { it.deviceId.equals(deviceIdHeader, ignoreCase = true) }
        } else {
            devices.find { it.lastIp == requesterIp }
        }

        if (device == null) {
            Log.w("PairingServer", "Management auth denied: device not found (id=$deviceIdHeader, ip=$requesterIp)")
            return Pair(false, null)
        }

        val authHeader = session.headers["authorization"]
        val providedToken = authHeader?.removePrefix("Bearer ")?.trim()

        var upgradedToken: String? = null
        if (device.authToken.isNullOrEmpty()) {
            // Legacy device without a token — auto-upgrade
            val newToken = UUID.randomUUID().toString()
            device.authToken = newToken
            pairingManager.addOrUpdateDevice(device)
            upgradedToken = newToken
            Log.d("PairingServer", "Management auth: legacy device ${device.deviceId} upgraded with new token")
        } else {
            // Strict validation
            if (providedToken != device.authToken) {
                Log.w("PairingServer", "Management auth denied: token mismatch for device ${device.deviceId}")
                return Pair(false, null)
            }
        }
        return Pair(true, upgradedToken)
    }

    private fun isDeviceAuthorized(session: IHTTPSession): Pair<Boolean, String?> {
        val deviceIdHeader = session.headers["x-ufm-deviceid"] 
        val requesterIp = session.headers["remote-addr"] ?: "0.0.0.0"
        
        val devices = pairingManager.getAllPairedDevices()
        
        val device = if (deviceIdHeader != null) {
            devices.find { it.deviceId.equals(deviceIdHeader, ignoreCase = true) }
        } else {
            devices.find { it.lastIp == requesterIp }
        }
        
        if (device == null || device.manuallyDisconnected) {
            return Pair(false, null)
        }
        
        val authHeader = session.headers["authorization"]
        val providedToken = authHeader?.removePrefix("Bearer ")?.trim()

        var upgradedToken: String? = null
        if (device.authToken.isNullOrEmpty()) {
            // Option B Migration: legacy authorized connection without token
            val newToken = UUID.randomUUID().toString()
            device.authToken = newToken
            pairingManager.addOrUpdateDevice(device)
            upgradedToken = newToken
        } else {
            // Validate strictly
            if (providedToken != device.authToken) {
                return Pair(false, null)
            }
        }
        return Pair(true, upgradedToken)
    }

    private fun handleTvDrives(): Response {
        val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        val volumes = storageManager.storageVolumes
        val arr = JSONArray()

        for (volume in volumes) {
            val path = volume.safeDirectoryPath ?: continue

            // Human-readable label: "Internal shared storage", "SD card", "USB drive", etc.
            val label = try {
                volume.getDescription(context) ?: deriveLabelFromPath(path, volume.isPrimary)
            } catch (e: Exception) {
                deriveLabelFromPath(path, try { volume.isPrimary } catch (e2: Exception) { false })
            }

            val dir = File(path)
            val freeBytes = try { dir.freeSpace } catch (e: Exception) { 0L }
            val totalBytes = try { dir.totalSpace } catch (e: Exception) { 0L }

            val obj = JSONObject().apply {
                // `name` is the real folder name used by the client to construct paths (e.g. "0", "ABC-1234")
                put("name", dir.name)
                // `label` is the human-readable display name shown in the UI
                put("label", label)
                put("path", path)
                put("isDirectory", true)
                put("size", totalBytes)
                put("lastModified", 0L)
                put("freeSpace", freeBytes)
                put("isRemovable", try { volume.isRemovable } catch (e: Exception) { false })
            }
            arr.put(obj)
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", arr.toString())
    }

    /** Derives a friendly name from the path when StorageVolume.getDescription() is unavailable. */
    private fun deriveLabelFromPath(path: String, isPrimary: Boolean): String {
        if (isPrimary || path.contains("emulated")) return context.getString(R.string.storage_internal)
        val segment = path.substringAfterLast('/')
        return when {
            segment.matches(Regex("[A-Z0-9]{4}-[A-Z0-9]{4}")) -> context.getString(R.string.storage_sd_card) // e.g. ABC1-DEF2
            path.contains("usb", ignoreCase = true) -> context.getString(R.string.storage_usb)
            path.contains("sdcard", ignoreCase = true) -> context.getString(R.string.storage_sd_card)
            else -> context.getString(R.string.storage_unknown_pattern, segment)
        }
    }

    // ─── Path Validation ────────────────────────────────────────────────
    private fun isPathAllowed(path: String): Boolean {
        if (path.startsWith("net:") || path.startsWith("content:")) return true
        
        val file = File(path)
        val canonicalReq = try { file.canonicalPath } catch (e: Exception) { return false }
        
        // 1. Direct Volume check: If the path belongs to a user-manageable storage volume, allow it.
        val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
        if (storageManager != null) {
            try {
                val volume = storageManager.getStorageVolume(file)
                if (volume != null) {
                    val dataDir = try { File(context.applicationInfo.dataDir).canonicalPath } catch (e: Exception) { null }
                    if (dataDir != null && canonicalReq.startsWith(dataDir)) {
                        return false
                    }
                    return true
                }
            } catch (_: Exception) { }
        }

        // 2. Fallback Sandbox check for non-volume paths
        val dataDir = try { File(context.applicationInfo.dataDir).canonicalPath } catch (e: Exception) { null }
        if (dataDir != null && canonicalReq.startsWith(dataDir)) {
            val cacheDir = try { context.cacheDir.canonicalPath } catch (e: Exception) { null }
            if (cacheDir != null && canonicalReq.startsWith(cacheDir)) return true
            return false
        }
        return true
    }

    private fun handleTvFiles(session: IHTTPSession): Response {
        val path = session.parms["path"] ?: ""
        val dir = File(path)
        
        Log.e("PairingServer", "handleTvFiles requesting path: [$path]")
        Log.e("PairingServer", "handleTvFiles exists: ${dir.exists()}, isDirectory: ${dir.isDirectory}, canRead: ${dir.canRead()}")
        
        if (!isPathAllowed(dir.absolutePath)) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, "application/json", "{\"error\":\"Permission denied: restricted path\"}")
        }
        if (!dir.exists() || !dir.isDirectory) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", "{\"error\":\"Not a directory\"}")
        }

        val files = dir.listFiles() ?: emptyArray()
        val arr = JSONArray()
        for (file in files) {
            val obj = JSONObject().apply {
                put("name", file.name)
                put("path", file.absolutePath)
                put("isDirectory", file.isDirectory)
                put("size", if (file.isDirectory) 0L else file.length())
                put("lastModified", file.lastModified())
            }
            arr.put(obj)
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", arr.toString())
    }

    private fun handleTvRead(session: IHTTPSession): Response {
        val path = session.parms["path"] ?: ""
        val file = File(path)
        
        if (!isPathAllowed(file.absolutePath)) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Permission denied")
        }
        if (!file.exists() || !file.isFile) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "File not found")
        }
        
        return try {
            val fis = FileInputStream(file)
            newChunkedResponse(Response.Status.OK, "application/octet-stream", fis)
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Cannot read file: ${e.message}")
        }
    }

    private fun handleTvWrite(session: IHTTPSession): Response {
        val pathParam = session.parms["path"] ?: ""
        val path = pathParam.replace("%CACHE%", context.cacheDir.absolutePath)
        val file = File(path)
        
        if (!isPathAllowed(file.absolutePath)) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, "application/json", "{\"error\":\"Permission denied\"}")
        }
        
        return try {
            // Write input stream directly to file
            FileOutputStream(file).use { out ->
                // NanoHTTPD gives us a temporary file for the body or parses it via parseBody
                // However, for direct streaming to avoid memory issues, we can try to copy session.inputStream
                // But NanoHTTPD requires calling parseBody first.
                // For a big file upload, we should use session.inputStream carefully, but NanoHTTPD's parseBody handles multipart or standard form data.
                // Since our client sends a pure raw POST stream:
                
                val buffer = ByteArray(8192)
                var bytesRead = 0
                val lenHeader = session.headers["content-length"]?.toLongOrNull()
                val inp = session.inputStream
                
                if (lenHeader != null) {
                    var totalRead = 0L
                    while (totalRead < lenHeader && inp.read(buffer, 0, minOf(buffer.size.toLong(), lenHeader - totalRead).toInt()).also { bytesRead = it } != -1) {
                        out.write(buffer, 0, bytesRead)
                        totalRead += bytesRead
                    }
                } else {
                    // Chunked streaming, read until EOF (-1)
                    while (inp.read(buffer).also { bytesRead = it } != -1) {
                        out.write(buffer, 0, bytesRead)
                    }
                }
            }
            newFixedLengthResponse(Response.Status.OK, "application/json", "{\"success\":true}")
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", "{\"error\":\"Cannot write file: ${e.message}\"}")
        }
    }

    private fun handleTvMkdir(session: IHTTPSession): Response {
        val postData = HashMap<String, String>()
        session.parseBody(postData)
        val bodyStr = postData["postData"] ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\":\"Missing body\"}")
        val rawPath = JSONObject(bodyStr).optString("path", "")
        val path = rawPath.replace("%CACHE%", context.cacheDir.absolutePath)
        
        val dir = File(path)
        if (!isPathAllowed(dir.absolutePath)) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, "application/json", "{\"error\":\"Permission denied\"}")
        }
        return if (dir.mkdirs()) {
            newFixedLengthResponse(Response.Status.OK, "application/json", "{\"success\":true}")
        } else {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", "{\"error\":\"Failed to create directory\"}")
        }
    }

    private fun handleTvDelete(session: IHTTPSession): Response {
        val postData = HashMap<String, String>()
        session.parseBody(postData)
        val bodyStr = postData["postData"] ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\":\"Missing body\"}")
        val rawPath = JSONObject(bodyStr).optString("path", "")
        val path = rawPath.replace("%CACHE%", context.cacheDir.absolutePath)
        
        val file = File(path)
        if (!isPathAllowed(file.absolutePath)) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, "application/json", "{\"error\":\"Permission denied\"}")
        }
        val success = if (file.isDirectory) file.deleteRecursively() else file.delete()
        
        return if (success) {
            newFixedLengthResponse(Response.Status.OK, "application/json", "{\"success\":true}")
        } else {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", "{\"error\":\"Failed to delete\"}")
        }
    }

    private fun handleTvRename(session: IHTTPSession): Response {
        val postData = HashMap<String, String>()
        session.parseBody(postData)
        val bodyStr = postData["postData"] ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\":\"Missing body\"}")
        val json = JSONObject(bodyStr)
        val rawPath = json.optString("path", "")
        val rawNewPath = json.optString("newPath", "")
        val path = rawPath.replace("%CACHE%", context.cacheDir.absolutePath)
        val newPath = rawNewPath.replace("%CACHE%", context.cacheDir.absolutePath)
        
        val file = File(path)
        val newFile = File(newPath)
        
        if (!isPathAllowed(file.absolutePath) || !isPathAllowed(newFile.absolutePath)) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, "application/json", "{\"error\":\"Permission denied\"}")
        }
        
        return if (file.renameTo(newFile)) {
            newFixedLengthResponse(Response.Status.OK, "application/json", "{\"success\":true}")
        } else {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", "{\"error\":\"Failed to rename\"}")
        }
    }

    // ─── APK / XAPK Sideload Install ────────────────────────────────────

    /**
     * Installs a single APK file that has already been uploaded to this device.
     * Uses the same ACTION_VIEW + FileProvider pattern as FileServer.triggerInstall.
     */
    private fun handleTvInstallApk(session: IHTTPSession): Response {
        val postData = HashMap<String, String>()
        session.parseBody(postData)
        val bodyStr = postData["postData"] ?: return newFixedLengthResponse(
            Response.Status.BAD_REQUEST, "application/json", "{\"error\":\"Missing body\"}")
        val rawPath = JSONObject(bodyStr).optString("path", "")
        val path = rawPath.replace("%CACHE%", context.cacheDir.absolutePath)
        val file = File(path)

        if (!isPathAllowed(file.absolutePath)) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, "application/json",
                "{\"error\":\"Permission denied\"}")
        }
        if (!file.exists() || !file.isFile) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json",
                "{\"error\":\"File not found\"}")
        }
        if (!file.name.lowercase().endsWith(".apk")) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json",
                "{\"error\":\"Not an APK file\"}")
        }

        return try {
            triggerInstall(file)
            newFixedLengthResponse(Response.Status.OK, "application/json", "{\"success\":true}")
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json",
                "{\"error\":\"${e.message?.replace("\"", "'")}\"}") 
        }
    }

    /**
     * Starts an XAPK/APKS install job. Returns a jobId for status polling.
     */
    private fun handleTvInstallXapk(session: IHTTPSession): Response {
        val postData = HashMap<String, String>()
        session.parseBody(postData)
        val bodyStr = postData["postData"] ?: return newFixedLengthResponse(
            Response.Status.BAD_REQUEST, "application/json", "{\"error\":\"Missing body\"}")
        val json = JSONObject(bodyStr)
        val rawPath = json.optString("path", "")
        val path = rawPath.replace("%CACHE%", context.cacheDir.absolutePath)
        val file = File(path)

        if (!isPathAllowed(file.absolutePath)) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, "application/json",
                "{\"error\":\"Permission denied\"}")
        }
        if (!file.exists() || !file.isFile) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json",
                "{\"error\":\"File not found\"}")
        }
        val ext = file.extension.lowercase()
        if (ext != "xapk" && ext != "apks") {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json",
                "{\"error\":\"Not an XAPK/APKS file\"}")
        }

        val jobId = UUID.randomUUID().toString()
        val job = XapkJob()
        xapkJobs[jobId] = job
        Thread { processXapk(file, jobId, job) }.start()

        val resp = JSONObject().apply {
            put("jobId", jobId)
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", resp.toString())
    }

    /** Returns status of an XAPK install job for polling. */
    private fun handleTvInstallXapkStatus(session: IHTTPSession): Response {
        val jobId = session.parms["jobId"]
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json",
                "{\"error\":\"Missing jobId\"}")
        val job = xapkJobs[jobId]
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json",
                "{\"error\":\"Job not found\"}")

        val resp = JSONObject().apply {
            put("status", job.status)
            put("current", job.current)
            put("total", job.total)
            put("currentFile", job.currentFile)
            put("packageLabel", job.packageLabel)
            put("error", job.error ?: "")
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", resp.toString())
    }

    /** Triggers a standard APK install via ACTION_VIEW + FileProvider. */
    private fun triggerInstall(file: File) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * Core XAPK processing — mirrors FileServer.processXapk.
     * Extracts APK splits from the archive, creates a PackageInstaller session,
     * and commits for installation.
     */
    private fun processXapk(archiveFile: File, jobId: String, job: XapkJob) {
        val extractDir = File(context.cacheDir, "xapk_temp/$jobId")
        extractDir.mkdirs()

        try {
            val apkFiles = mutableListOf<File>()

            ZipFile(archiveFile).use { zip ->
                val entries = zip.entries().asSequence().toList()
                val apkEntries = entries.filter { it.name.endsWith(".apk", ignoreCase = true) }
                job.total = apkEntries.size

                if (apkEntries.isEmpty()) {
                    job.status = "error"
                    job.error = "No APK files found inside archive"
                    extractDir.deleteRecursively()
                    return
                }

                // Extract APK splits
                val canonicalExtractDir = extractDir.canonicalPath
                apkEntries.forEachIndexed { i, entry ->
                    val name = entry.name.substringAfterLast('/')
                    job.current = i + 1
                    job.currentFile = name

                    val outFile = File(extractDir, name)
                    val canonicalOut = try {
                        outFile.canonicalPath
                    } catch (e: java.io.IOException) {
                        Log.w("PairingServer", "XAPK: skipping entry with unresolvable path: $name")
                        return@forEachIndexed
                    }
                    if (!canonicalOut.startsWith(canonicalExtractDir + File.separator) && canonicalOut != canonicalExtractDir) {
                        Log.w("PairingServer", "XAPK: Zip Slip attempt detected! Skipping entry: $name")
                        return@forEachIndexed
                    }
                    zip.getInputStream(entry).use { input ->
                        outFile.outputStream().use { input.copyTo(it) }
                    }
                    apkFiles.add(outFile)
                }

                // Optional manifest.json for label
                zip.getEntry("manifest.json")?.let { manifestEntry ->
                    runCatching {
                        val text = zip.getInputStream(manifestEntry).bufferedReader().readText()
                        job.packageLabel = JSONObject(text).optString("name", "")
                    }
                }
            }

            if (apkFiles.isEmpty()) {
                job.status = "error"
                job.error = "No APK splits matched after filtering"
                extractDir.deleteRecursively()
                return
            }

            // PackageInstaller session
            job.status = "installing"
            za.kilowatch.ultimatefilemanager.util.PackageInstallerHelper.abandonMySessions(context)

            val packageInstaller = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            )
            val sessionId = packageInstaller.createSession(params)
            val installSession = packageInstaller.openSession(sessionId)

            apkFiles.forEach { apkFile ->
                installSession.openWrite(apkFile.name, 0, apkFile.length()).use { out ->
                    apkFile.inputStream().use { it.copyTo(out) }
                }
            }

            // Commit — InstallReceiver handles the OS prompt
            val broadcastIntent = Intent(context, InstallReceiver::class.java).apply {
                action = "za.kilowatch.ultimatefilemanager.INSTALL_COMPLETE"
                putExtra("jobId", jobId)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context, sessionId, broadcastIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            installSession.commit(pendingIntent.intentSender)
            installSession.close()

            job.status = "awaiting_os"

        } catch (e: Exception) {
            Log.e("PairingServer", "XAPK install failed for job $jobId", e)
            job.status = "error"
            job.error = e.message ?: "Unknown error"
            extractDir.deleteRecursively()
            xapkJobs.remove(jobId)
        }
    }

    // ─── ADB Status ──────────────────────────────────────────────────────

    /**
     * Returns whether ADB (USB debugging) is enabled on this device.
     */
    private fun handleTvAdbStatus(): Response {
        val adbEnabled = try {
            Settings.Global.getInt(
                context.contentResolver,
                Settings.Global.ADB_ENABLED, 0
            ) == 1
        } catch (e: Exception) {
            false
        }
        Log.d("GoRoScreen", "handleTvAdbStatus() adbEnabled=$adbEnabled")
        val json = JSONObject().apply {
            put("adbEnabled", adbEnabled)
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
    }

    // ─── Settings Transfer ───────────────────────────────────────────────────

    /**
     * POST /tv/transfer-settings
     *
     * Security controls (from audit):
     *  H-3: Reject payloads exceeding 512 KB before reading the body.
     *  C-2: Rate-limit to 1 call per 30 s per deviceId; reject replayed nonces.
     *  H-1: Never write settings here. Store payload in PendingTransferHolder;
     *       put only the holder token UUID in the Intent.
     *  C-3: All actual disk writes happen exclusively in TransferApprovalActivity.
     */
    private fun handleTvTransferSettings(session: IHTTPSession): Response {
        // H-3: Payload size cap
        val contentLength = session.headers["content-length"]?.toLongOrNull() ?: 0L
        if (contentLength > MAX_TRANSFER_PAYLOAD_BYTES) {
            Log.w("PairingServer", "Transfer payload too large ($contentLength bytes), rejecting")
            return newFixedLengthResponse(
                Response.Status.PAYLOAD_TOO_LARGE,
                "application/json",
                "{\"error\":\"Payload too large (max 512 KB)\"}"
            )
        }

        // C-2: Rate-limit per device ID
        val deviceId = session.headers["x-ufm-deviceid"] ?: session.headers["remote-addr"] ?: "unknown"
        val lastCall = transferRateLimit[deviceId] ?: 0L
        val now = System.currentTimeMillis()
        if (now - lastCall < 30_000L) {
            Log.w("PairingServer", "Transfer rate-limited for device $deviceId")
            return newFixedLengthResponse(
                Response.Status.TOO_MANY_REQUESTS,
                "application/json",
                "{\"error\":\"Rate limited. Please wait 30 seconds before retrying.\"}"
            )
        }
        transferRateLimit[deviceId] = now

        // Parse body (manual read — readNBytes() requires API 33, minSdk is 26)
        val bodyBytes: ByteArray = try {
            val limit = contentLength.coerceAtMost(MAX_TRANSFER_PAYLOAD_BYTES.toLong()).toInt()
            val buffer = java.io.ByteArrayOutputStream(limit)
            val chunk = ByteArray(8192)
            var remaining = limit
            val inputStream = session.inputStream
            while (remaining > 0) {
                val read = inputStream.read(chunk, 0, minOf(chunk.size, remaining))
                if (read == -1) break
                buffer.write(chunk, 0, read)
                remaining -= read
            }
            buffer.toByteArray()
        } catch (e: Exception) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\":\"Cannot read body\"}")
        }

        val json = try {
            JSONObject(String(bodyBytes, Charsets.UTF_8))
        } catch (e: Exception) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\":\"Invalid JSON\"}")
        }

        // C-2: Nonce validation (replay protection)
        val nonce = json.optString("nonce", "")
        if (nonce.isBlank()) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\":\"Missing nonce\"}")
        }
        synchronized(seenNonces) {
            if (seenNonces.contains(nonce)) {
                Log.w("PairingServer", "Replayed nonce rejected: ${nonce.take(8)}")
                return newFixedLengthResponse(
                    Response.Status.CONFLICT,
                    "application/json",
                    "{\"error\":\"Duplicate nonce — request already processed\"}"
                )
            }
            seenNonces.add(nonce)
            if (seenNonces.size > 20) seenNonces.removeFirst()
        }

        // H-1: Store payload in holder, put only token in Intent
        val token = PendingTransferHolder.store(bodyBytes)
        Log.d("PairingServer", "Transfer payload stored (token=${token.take(8)}…), launching approval")

        // Launch approval Activity on the UI thread
        // FLAG_ACTIVITY_REORDER_TO_FRONT: prevents stacking duplicate approval screens on TV
        GlobalScope.launch(kotlinx.coroutines.Dispatchers.Main) {
            val approvalIntent = Intent(context, za.kilowatch.ultimatefilemanager.network.TransferApprovalActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                putExtra(za.kilowatch.ultimatefilemanager.network.TransferApprovalActivity.EXTRA_TOKEN, token)
            }
            context.startActivity(approvalIntent)
        }

        // C-3: Return 202 Accepted — no data written here
        return newFixedLengthResponse(Response.Status.ACCEPTED, "application/json", "{\"status\":\"awaiting_approval\"}")
    }
}
