package za.kilowatch.ultimatefilemanager.network

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.github.muntashirakon.adb.AdbConnection
import io.github.muntashirakon.adb.AdbStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.Security
import java.security.cert.Certificate
import za.kilowatch.ultimatefilemanager.UfmApplication
import java.util.Date

/**
 * ADB connection manager with automatic lifecycle management and inactivity timeout.
 * - Keeps connection alive across activities when app is in foreground
 * - Auto-disconnects after 5 minutes of inactivity
 * - Disconnects when app enters background or closes
 */
class AdbManager private constructor() {

    var lastError: String? = null
    private var connection: AdbConnection? = null
    private val keyPair: java.security.KeyPair
    private val cert: java.security.cert.Certificate
    private val mutex = Mutex()
    
    // Inactivity timeout: auto-disconnect after 5 minutes (300 seconds) of no activity
    private val INACTIVITY_TIMEOUT_MS = 5 * 60 * 1000L
    private var inactivityJob: Job? = null
    private var lastActivityTime = 0L
    private var lifecycleInitialized = false

    /**
     * When true, the inactivity timeout and lifecycle-driven disconnect are
     * suppressed. Set by [AdbWifiTransport] while the connection is being
     * used as a persistent remote control session.
     */
    @Volatile
    var isRemoteMode: Boolean = false

    /**
     * The paired device ID currently connected for remote control.
     * Survives activity recreation so [TvRemoteActivity] can re-wrap the
     * transport on re-entry. Set on connect, cleared on disconnect.
     */
    @Volatile
    var activeRemoteDeviceId: String? = null

    companion object {
        @Volatile
        private var instance: AdbManager? = null

        fun getInstance(context: Any? = null): AdbManager {
            return instance ?: synchronized(this) {
                instance ?: AdbManager().also { instance = it }
            }
        }

        private const val TAG = "AdbManager"
    }
    
    init {
        val appCtx = UfmApplication.instance.applicationContext

        var loadedKp: java.security.KeyPair? = null
        var loadedCert: java.security.cert.Certificate? = null
        var encryptedPrefs: android.content.SharedPreferences? = null

        try {
            val masterKey = MasterKey.Builder(appCtx)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            encryptedPrefs = EncryptedSharedPreferences.create(
                appCtx,
                "adb_keys_secure",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )

            // Security Migration: Move exposed plaintext keys into encrypted storage
            val legacyPrefs = appCtx.getSharedPreferences("adb_keys", android.content.Context.MODE_PRIVATE)
            if (legacyPrefs.contains("private_key")) {
                Log.i(TAG, "Migrating legacy plaintext ADB keys to EncryptedSharedPreferences")
                encryptedPrefs.edit()
                    .putString("private_key", legacyPrefs.getString("private_key", null))
                    .putString("public_key", legacyPrefs.getString("public_key", null))
                    .putString("cert", legacyPrefs.getString("cert", null))
                    .apply()
                legacyPrefs.edit().clear().apply()
            }

            val privKeyBase64 = encryptedPrefs.getString("private_key", null)
            val pubKeyBase64 = encryptedPrefs.getString("public_key", null)
            val certBase64 = encryptedPrefs.getString("cert", null)

            if (privKeyBase64 != null && pubKeyBase64 != null && certBase64 != null) {
                try {
                    val kf = java.security.KeyFactory.getInstance("RSA")
                    val privKeySpec = java.security.spec.PKCS8EncodedKeySpec(android.util.Base64.decode(privKeyBase64!!, android.util.Base64.DEFAULT))
                    val pubKeySpec = java.security.spec.X509EncodedKeySpec(android.util.Base64.decode(pubKeyBase64!!, android.util.Base64.DEFAULT))
                    loadedKp = java.security.KeyPair(kf.generatePublic(pubKeySpec), kf.generatePrivate(privKeySpec))

                    val certFactory = java.security.cert.CertificateFactory.getInstance("X.509")
                    loadedCert = certFactory.generateCertificate(java.io.ByteArrayInputStream(android.util.Base64.decode(certBase64!!, android.util.Base64.DEFAULT)))
                } catch (e: java.security.KeyStoreException) {
                    Log.e(TAG, "Android KeyStore unavailable (FireOS platform restriction?): ${e.message}")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load ADB keys from storage: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Encrypted storage unavailable (FireOS KeyStore issue?): ${e.message}. Keys will be ephemeral.")
        }

        if (loadedKp != null && loadedCert != null) {
            keyPair = loadedKp
            cert = loadedCert
        } else {
            keyPair = generateKeyPair()
            cert = generateSelfSignedCert(keyPair)
            // Persist in encrypted storage if it was initialized successfully.
            // On FireOS with KeyStore restrictions, encryptedPrefs may be null —
            // in that case the keys are ephemeral (regenerated on every process start).
            try {
                encryptedPrefs?.edit()
                    ?.putString("private_key", android.util.Base64.encodeToString(keyPair.private.encoded, android.util.Base64.DEFAULT))
                    ?.putString("public_key", android.util.Base64.encodeToString(keyPair.public.encoded, android.util.Base64.DEFAULT))
                    ?.putString("cert", android.util.Base64.encodeToString(cert.encoded, android.util.Base64.DEFAULT))
                    ?.apply()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to persist ADB keys: ${e.message}. Keys will be ephemeral.")
            }
        }

        // Initialize lifecycle observer on first instance creation
        initializeLifecycleObserver()
    }

    private fun generateKeyPair() = KeyPairGenerator.getInstance("RSA").apply {
        initialize(2048)
    }.generateKeyPair()

    private fun generateSelfSignedCert(kp: java.security.KeyPair): Certificate {
        return try {
            // Ensure BouncyCastle provider is available
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                Security.insertProviderAt(BouncyCastleProvider(), 1)
            }

            val now = Date()
            val until = Date(now.time + 365L * 24 * 60 * 60 * 1000)
            val cn = X500Name("CN=AdbManager")
            val serial = BigInteger.valueOf(now.time)

            val builder = JcaX509v3CertificateBuilder(
                cn, serial, now, until, cn, kp.public
            )
            val signer = JcaContentSignerBuilder("SHA256withRSA")
                .build(kp.private)

            val cert = builder.build(signer)
            JcaX509CertificateConverter()
                .getCertificate(cert)
        } catch (e: Exception) {
            Log.e(TAG, "Cert generation failed: \${e.message}", e)
            throw RuntimeException("Certificate generation failed", e)
        }
    }

    /**
     * Initialize lifecycle observer to handle app foreground/background state
     */
    private fun initializeLifecycleObserver() {
        if (lifecycleInitialized) return
        
        Handler(Looper.getMainLooper()).post {
            try {
                val lifecycle = ProcessLifecycleOwner.get().lifecycle
                lifecycle.addObserver(object : DefaultLifecycleObserver {
                    override fun onPause(owner: LifecycleOwner) {
                        if (!isRemoteMode) startInactivityTimer()
                    }

                    override fun onResume(owner: LifecycleOwner) {
                        if (!isRemoteMode) resetInactivityTimer()
                    }

                    override fun onDestroy(owner: LifecycleOwner) {
                        if (!isRemoteMode) disconnectExplicit()
                    }
                })
                lifecycleInitialized = true
                Log.d(TAG, "Lifecycle observer initialized")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to initialize lifecycle observer: ${e.message}")
            }
        }
    }

    /**
     * Reset inactivity timer - call this when shell activity occurs
     */
    fun resetActivityTimer() {
        inactivityJob?.cancel()
        inactivityJob = null
        lastActivityTime = System.currentTimeMillis()
    }

    /**
     * Start inactivity timeout - auto-disconnect if no activity for 5 minutes
     */
    private fun startInactivityTimer() {
        if (!isConnected()) return
        if (isRemoteMode) return

        inactivityJob?.cancel()
        inactivityJob = GlobalScope.launch(Dispatchers.IO) {
            try {
                delay(INACTIVITY_TIMEOUT_MS)
                // Check if still no activity and still connected
                if (isConnected()) {
                    Log.i(TAG, "Inactivity timeout reached - auto-disconnecting")
                    disconnect()
                }
            } catch (e: Exception) {
                Log.d(TAG, "Inactivity timer cancelled: ${e.message}")
            }
        }
    }

    /**
     * Reset inactivity timer - stops auto-disconnect countdown
     */
    fun resetInactivityTimer() {
        inactivityJob?.cancel()
        inactivityJob = null
        resetActivityTimer()
    }

    /**
     * Release remote mode and restore normal inactivity timeout behavior.
     * Called by [AdbWifiTransport.disconnect()] when the user explicitly
     * disconnects the ADB remote session.
     */
    fun releaseRemoteMode() {
        isRemoteMode = false
        activeRemoteDeviceId = null
        resetInactivityTimer()
    }

    suspend fun connect(host: String, port: Int): Boolean {
        // Build the AdbConnection object inside the mutex (fast, non-blocking)
        val newConnection = try {
            withContext(Dispatchers.IO) {
                mutex.withLock {
                    try { connection?.close() } catch (_: Exception) {}
                    val c = AdbConnection.Builder(host, port)
                        .setPrivateKey(keyPair.private)
                        .setCertificate(cert)
                        .setDeviceName("UltimateFM")
                        .build()
                    connection = c
                    c
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Connection setup failed: ${e.stackTraceToString()}")
            lastError = "Setup failed: ${e.message} ${e.stackTraceToString().lines().take(5).joinToString(" ")}"
            return false
        }

        // The blocking connect() call must run on a separate Thread inside
        // suspendCancellableCoroutine. The lambda must return quickly so the
        // coroutine actually suspends — only then can invokeOnCancellation fire
        // and close the socket to interrupt the blocked I/O thread.
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation {
                Log.d(TAG, "connect() cancelled — closing socket")
                try { newConnection.close() } catch (_: Exception) {}
                if (connection === newConnection) connection = null
            }

            Thread {
                try {
                    newConnection.connect()
                    resetActivityTimer()
                    Log.i(TAG, "Connected to $host:$port")
                    lastError = null
                    if (cont.isActive) cont.resume(true)
                } catch (e: Exception) {
                    Log.e(TAG, "Connection failed: ${e.toString()}")
                    lastError = e.toString()
                    if (connection === newConnection) connection = null
                    if (cont.isActive) cont.resume(false)
                }
            }.start()
        }
    }

    suspend fun pair(host: String, port: Int, pairingCode: String): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                Log.i(TAG, "Pairing with code: $pairingCode")
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    /**
     * Internal disconnect - for inactivity timeout
     */
    private fun disconnect() {
        try {
            inactivityJob?.cancel()
            inactivityJob = null
            
            try { persistentShellWriter?.close() } catch (_: Exception) {}
            try { persistentShellStream?.close() } catch (_: Exception) {}
            persistentShellWriter = null
            persistentShellStream = null
            
            connection?.close()
            connection = null
            Log.d(TAG, "Disconnected")
        } catch (e: Exception) {
            Log.w(TAG, "Disconnect: ${e.message}")
        }
    }

    /**
     * Explicit disconnect - for user-initiated disconnect
     */
    fun disconnectExplicit() {
        disconnect()
    }

    fun isConnected(): Boolean = try {
        connection?.isConnected() == true
    } catch (e: Exception) {
        false
    }

    fun openShell(): AdbStream? = try {
        if (!isConnected()) null else connection?.open("shell:")
    } catch (e: Exception) {
        Log.e(TAG, "openShell failed: ${e.message}")
        null
    }

    /**
     * Opens a non-interactive ADB exec stream for a single command.
     * Unlike openShell(), this avoids PTY allocation — essential for binary output
     * (e.g. screencap -p which outputs raw PNG bytes).
     */
    fun openExec(command: String): AdbStream? = try {
        if (!isConnected()) null else connection?.open("exec:$command")
    } catch (e: Exception) {
        Log.e(TAG, "openExec failed: ${e.message}")
        null
    }

    private var persistentShellStream: AdbStream? = null
    private var persistentShellWriter: java.io.OutputStream? = null

    /**
     * Sends a fire-and-forget shell command for the TV Remote.
     * Uses a persistent `shell:` session instead of creating a new `exec:`
     * channel per keypress, dramatically reducing ADB lag from ~500ms to near-instant.
     */
    suspend fun sendShellCommand(command: String) = withContext(Dispatchers.IO) {
        try {
            if (!isConnected()) return@withContext

            if (persistentShellStream == null || persistentShellStream!!.isClosed) {
                persistentShellStream = connection?.open("shell:")
                persistentShellWriter = persistentShellStream?.openOutputStream()

                // Read output in background so the stream buffer doesn't fill up and block
                GlobalScope.launch(Dispatchers.IO) {
                    try {
                        persistentShellStream?.openInputStream()?.use {
                            val buf = ByteArray(1024)
                            while (it.read(buf) != -1) { /* discard */ }
                        }
                    } catch (e: Exception) {}
                }
            }

            persistentShellWriter?.write((command + "\n").toByteArray(Charsets.UTF_8))
            persistentShellWriter?.flush()
        } catch (e: Exception) {
            Log.w(TAG, "sendShellCommand persistent shell failed: ${e.message}")
            persistentShellStream = null
            persistentShellWriter = null
        }
    }

    /**
     * Sends a shell command synchronously and waits for it to complete.
     * Useful for repeating actions (like holding D-PAD) where you want natural
     * backpressure so commands don't queue up faster than the TV can process them.
     */
    suspend fun sendShellCommandSync(command: String) = withContext(Dispatchers.IO) {
        try {
            if (!isConnected()) return@withContext
            val stream = connection?.open("exec:$command")
            stream?.openInputStream()?.use { it.readBytes() } // Blocks until command finishes
        } catch (e: Exception) {
            Log.w(TAG, "sendShellCommandSync failed: ${e.message}")
        }
    }
}

