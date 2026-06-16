package za.kilowatch.ultimatefilemanager.remote

import za.kilowatch.ultimatefilemanager.BuildConfig
import za.kilowatch.ultimatefilemanager.util.safeDirectoryPath

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.text.format.Formatter
import android.util.Base64
import android.util.Log
import android.webkit.MimeTypeMap
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveMultipart
import io.ktor.server.request.receiveStream
import io.ktor.server.response.header
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.indexing.IndexingRepository
import za.kilowatch.ultimatefilemanager.storage.VaultCrypto
import za.kilowatch.ultimatefilemanager.storage.VaultEntry
import za.kilowatch.ultimatefilemanager.util.PackageInstallerHelper
import kotlin.concurrent.thread
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.asn1.pkcs.RSAPrivateKey
import org.bouncycastle.asn1.sec.ECPrivateKey
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.openssl.PEMKeyPair
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.bouncycastle.util.io.pem.PemReader
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.NetworkInterface
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.X509KeyManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.security.auth.x500.X500Principal

/**
 * Embedded HTTPS file server using Ktor + Netty (migrated from NanoHTTPD 2.3.1).
 * Activity-scoped: starts/stops with [RemoteManageActivity].
 *
 * Security features applied:
 *  - SEC-CRIT-1: LAN-only enforcement (RFC1918 + loopback)
 *  - SEC-CRIT-2: No CSRF token required (Authorization header only)
 *  - SEC-CRIT-3: Vault PIN brute-force lockout
 *  - SEC-HIGH-7: No query-param token fallback
 *  - SEC-LOW-4: Security response headers on all responses
 *  - TLS 1.2/1.3 with AndroidKeyStore-backed ECDSA certificate
 *  - Certificate SHA-256 fingerprint exposed for client verification
 */
class FileServer(
    private val context: Context,
    private val pin: String,
    private val indexingRepository: IndexingRepository,
    private val port: Int = 8444
) {

    companion object {
        private const val TAG = "FileServer"
        private const val SESSION_TTL_MS = 2 * 60 * 60 * 1000L // 2 hours
        private const val LOCKOUT_MS = 15 * 60 * 1000L // 15 minutes
        private const val MAX_ATTEMPTS = 5
        // SEC-CRIT-3: Vault PIN brute-force lockout constants
        private const val VAULT_LOCKOUT_MS = 30 * 60 * 1000L // 30 minutes
        private const val VAULT_MAX_ATTEMPTS = 3

        private const val SSL_KEY_ALIAS = "ufm_remote_ecdsa_v1"
        private const val CUSTOM_CERT_ALIAS = "ufm_remote_custom_v1"
        private val SSL_KEYSTORE_PASSWORD = charArrayOf('u', 'f', 'm', 's', 's', 'l')

        /**
         * Process-global reference to the currently running FileServer.
         * Guarantees that only one server instance owns the SSL port at any time,
         * even if Android recreates RemoteManageActivity before onDestroy has run.
         */
        @Volatile private var globalInstance: FileServer? = null

        /**
         * Stops and clears the global instance synchronously.
         * Always call this before [start] to avoid EADDRINUSE.
         */
        fun stopGlobal() {
            globalInstance?.stop()
            globalInstance = null
        }
    }

    var serverCertFingerprint: String? = null

    /** The actual port the SSL socket bound to (may differ from [port] if 8443 was busy). */
    var boundPort: Int = port
        private set

    private val certPrefs = context.getSharedPreferences("ufm_remote_cert_prefs", Context.MODE_PRIVATE)
    private val encryptedPrefs: android.content.SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context, "ufm_remote_cert_secure",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init encrypted prefs, falling back", e)
            context.getSharedPreferences("ufm_remote_cert_fallback", Context.MODE_PRIVATE)
        }
    }

    private fun pemEncodeCert(cert: java.security.cert.X509Certificate): String {
        val base64 = android.util.Base64.encodeToString(cert.encoded, android.util.Base64.NO_WRAP)
        return "-----BEGIN CERTIFICATE-----\n" +
            base64.chunked(64).joinToString("\n") +
            "\n-----END CERTIFICATE-----\n"
    }

    private fun pemEncodeKey(key: java.security.PrivateKey): String {
        val base64 = android.util.Base64.encodeToString(key.encoded, android.util.Base64.NO_WRAP)
        return "-----BEGIN PRIVATE KEY-----\n" +
            base64.chunked(64).joinToString("\n") +
            "\n-----END PRIVATE KEY-----\n"
    }

    fun importPemCert(certPath: String, keyPath: String): Boolean {
        try {
            val certPem = File(certPath).readText()
            val keyPem = File(keyPath).readText()

            val cert = parseCertificateFromPem(certPem) ?: return false
            val privateKey = parsePrivateKeyFromPem(keyPem) ?: return false

            encryptedPrefs.edit()
                .putString("cert_pem", certPem)
                .putString("key_pem", keyPem)
                .commit()

            val digest = MessageDigest.getInstance("SHA-256")
            serverCertFingerprint = digest.digest(cert.encoded).joinToString("") { "%02x".format(it) }
            certPrefs.edit().putBoolean("custom_cert_imported", true).commit()
            Log.i(TAG, "Custom PEM certificate imported successfully")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import PEM certificate", e)
            return false
        }
    }

    fun importPkcs12Cert(p12Path: String, password: CharArray): Boolean {
        try {
            val p12 = java.security.KeyStore.getInstance("PKCS12").also {
                it.load(java.io.FileInputStream(File(p12Path)), password)
            }
            val alias = p12.aliases().asSequence().firstOrNull { p12.isKeyEntry(it) } ?: return false
            val privateKey = p12.getKey(alias, password) as? java.security.PrivateKey ?: return false
            val chain = p12.getCertificateChain(alias) ?: return false
            if (chain.isEmpty()) return false

            val certPem = pemEncodeCert(chain[0] as java.security.cert.X509Certificate)
            val keyPem = pemEncodeKey(privateKey)

            encryptedPrefs.edit()
                .putString("cert_pem", certPem)
                .putString("key_pem", keyPem)
                .commit()

            val digest = MessageDigest.getInstance("SHA-256")
            serverCertFingerprint = digest.digest(chain[0].encoded).joinToString("") { "%02x".format(it) }
            certPrefs.edit().putBoolean("custom_cert_imported", true).commit()
            Log.i(TAG, "Custom PKCS12 certificate imported successfully")
            java.util.Arrays.fill(password, '0')
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import PKCS12 certificate", e)
            return false
        }
    }

    private fun parsePrivateKeyFromPem(pemContent: String): java.security.PrivateKey? {
        return try {
            val pemReader = PemReader(StringReader(pemContent))
            val pemObject = pemReader.readPemObject() ?: return null
            pemReader.close()
            val bytes = pemObject.content
            val header = pemObject.type
            val converter = JcaPEMKeyConverter().setProvider("BC")
            val info = when (header) {
                "PRIVATE KEY" -> PrivateKeyInfo.getInstance(bytes)
                "RSA PRIVATE KEY" -> PrivateKeyInfo.getInstance(RSAPrivateKey.getInstance(bytes).encoded)
                "EC PRIVATE KEY" -> PrivateKeyInfo.getInstance(ECPrivateKey.getInstance(bytes).encoded)
                else -> PrivateKeyInfo.getInstance(bytes)
            }
            converter.getPrivateKey(info)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse private key", e)
            null
        }
    }

    private fun parseCertificateFromPem(pemContent: String): java.security.cert.X509Certificate? {
        return try {
            val reader = PEMParser(StringReader(pemContent))
            val holder = reader.readObject() as? X509CertificateHolder
            reader.close()
            holder?.let { JcaX509CertificateConverter().setProvider("BC").getCertificate(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse certificate", e)
            null
        }
    }

    private fun loadCustomKeyManager(): KeyManagerFactory? {
        val certPem = encryptedPrefs.getString("cert_pem", null) ?: return null
        val keyPem = encryptedPrefs.getString("key_pem", null) ?: return null
        return try {
            val cert = parseCertificateFromPem(certPem) ?: return null
            val privateKey = parsePrivateKeyFromPem(keyPem) ?: return null

            val tempKs = java.security.KeyStore.getInstance("PKCS12").also { it.load(null, null) }
            tempKs.setKeyEntry(CUSTOM_CERT_ALIAS, privateKey, SSL_KEYSTORE_PASSWORD, arrayOf(cert))
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(tempKs, SSL_KEYSTORE_PASSWORD)
            kmf
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load custom certificate", e)
            null
        }
    }

    fun removeCustomCert() {
        try {
            encryptedPrefs.edit().clear().commit()
            serverCertFingerprint = null
            certPrefs.edit().clear().commit()
            val aks = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
            if (aks.containsAlias(SSL_KEY_ALIAS)) aks.deleteEntry(SSL_KEY_ALIAS)
            Log.i(TAG, "Custom certificate removed, auto key will be regenerated")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove custom certificate", e)
        }
    }

    fun hasCustomCert(): Boolean = certPrefs.getBoolean("custom_cert_imported", false) &&
        encryptedPrefs.getString("cert_pem", null) != null

    // Thread-safe session store: Token -> ExpiryTimestamp
    private val validSessions = ConcurrentHashMap<String, Long>()
    private val vaultSessions = ConcurrentHashMap<String, Long>()

    // Brute-force protection: IP -> Pair(AttemptCount, LockoutExpiry)
    private val failedAttempts = ConcurrentHashMap<String, Pair<Int, Long>>()
    // SEC-CRIT-3: Vault PIN brute-force protection: IP -> Pair(AttemptCount, LockoutExpiry)
    private val vaultFailedAttempts = ConcurrentHashMap<String, Pair<Int, Long>>()

    // Background ZIP job tracking
    private data class ZipJob(
        @Volatile var status: String = "compressing",
        @Volatile var current: Int = 0,
        @Volatile var total: Int = 0,
        @Volatile var currentFile: String = "",
        @Volatile var zipFile: File? = null,
        @Volatile var zipFileName: String = "",
        @Volatile var error: String? = null,
        @Volatile var cancelled: Boolean = false
    )
    private val zipJobs = ConcurrentHashMap<String, ZipJob>()

    // Background XAPK install job tracking
    private data class XapkJob(
        @Volatile var status: String = "extracting", // extracting | installing | awaiting_os | error
        @Volatile var current: Int = 0,
        @Volatile var total: Int = 0,
        @Volatile var currentFile: String = "",
        @Volatile var packageLabel: String = "",
        @Volatile var error: String? = null
    )
    private val xapkJobs = ConcurrentHashMap<String, XapkJob>()

    // ── Download ticket storage (HIGH-1) ──
    private data class DownloadTicket(
        val path: String,
        val isFolder: Boolean,
        val expiresAt: Long,
        val used: Boolean
    )
    private val downloadTickets = ConcurrentHashMap<String, DownloadTicket>()

    private val serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private fun notifyIndexing(path: String, isDelete: Boolean = false, isFolder: Boolean = false) {
        if (path.startsWith("net:")) return
        serverScope.launch {
            try {
                val (storageId, storageType, _) = IndexingRepository.resolveStorageForPath(path)
                if (isDelete) {
                    if (isFolder) {
                        indexingRepository.deleteTreeFromIndex(path)
                    } else {
                        indexingRepository.deleteFromIndex(path)
                    }
                } else {
                    if (isFolder) {
                        indexingRepository.indexFolder(path, storageId, storageType)
                    } else {
                        indexingRepository.indexFile(File(path), storageId, storageType)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to notify indexing for $path", e)
            }
        }
    }

    /**
     * Ktor session adapter ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â bridges Ktor's [ApplicationCall] to the NanoHTTPD-style
     * session interface that all handler functions were written against.
     * Allows handlers to use [parms], [headers], [remoteIpAddress], etc. unchanged.
     */
    inner class KtorSession(val call: ApplicationCall) {
        /** Path segment of the URI (no query string). */
        val uri: String get() = call.request.path()

        /** HTTP method as a string ("GET", "POST", ...). */
        val method: String get() = call.request.httpMethod.value

        /** Query parameters as a flat map (first value wins). */
        val parms: Map<String, String> get() =
            call.request.queryParameters.entries().associate { it.key to (it.value.firstOrNull() ?: "") }

        /** Request headers as a flat lowercase map. */
        val headers: Map<String, String> get() =
            call.request.headers.entries().associate { it.key.lowercase() to (it.value.firstOrNull() ?: "") }

        /** Remote IP address of the caller. */
        val remoteIpAddress: String get() = call.request.local.remoteHost

        /** Lang cookie ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â read ufm_lang cookie value or fallback to "en". */
        val ufmLang: String get() = call.request.cookies["ufm_lang"] ?: "en"

        /**
         * Reads and parses the request body as application/x-www-form-urlencoded or
         * raw JSON posted as "postData".  For multipart/form-data the returned map
         * contains paths of NanoHTTPD-style temp files written to cacheDir.
         *
         * This is synchronous (called via runBlocking from non-suspend handlers).
         */
        fun parseBody(result: MutableMap<String, String>) {
            val contentType = call.request.headers["content-type"] ?: ""

            if (contentType.contains("multipart/form-data", ignoreCase = true)) {
                // Use Ktor's native multipart parser to properly extract file
                // content without boundary headers (fixes FAILURE_INVALID on
                // APK install — the old code wrote the raw multipart body).
                runBlocking {
                    // No size limit — APKs can be hundreds of MB
                    val multipart = call.receiveMultipart(formFieldLimit = Long.MAX_VALUE)
                    multipart.forEachPart { part ->
                        when (part) {
                            is PartData.FileItem -> {
                                val tmpFile = File(context.cacheDir, "upload_${UUID.randomUUID()}.tmp")
                                part.provider().toInputStream().use { input ->
                                    tmpFile.outputStream().use { output -> input.copyTo(output) }
                                }
                                result["file"] = tmpFile.absolutePath
                            }
                            is PartData.FormItem -> {
                                result[part.name ?: ""] = part.value
                            }
                            else -> {}
                        }
                        part.dispose()
                    }
                }
            } else {
                // Treat as raw body (JSON or URL-encoded)
                val bytes = runBlocking { call.receiveStream().readBytes() }
                result["postData"] = bytes.toString(Charsets.UTF_8)
            }
        }

        /** Returns the raw body InputStream (for streaming upload handlers). */
        val inputStream: java.io.InputStream get() = runBlocking { call.receiveStream() }
    }

    // ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ Server Lifecycle ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬

    private var ktorServer: io.ktor.server.engine.EmbeddedServer<*, *>? = null
    private var sslServerSocket: java.net.ServerSocket? = null

    private fun findFreePort(): Int {
        val ss = java.net.ServerSocket(0)
        ss.reuseAddress = true
        val p = ss.localPort
        ss.close()
        return p
    }

    fun start() {
        try {
            var keyAlias = SSL_KEY_ALIAS
            var aks: KeyStore? = null
            var kmf: KeyManagerFactory

            val customKmf = loadCustomKeyManager()
            if (customKmf != null) {
                keyAlias = CUSTOM_CERT_ALIAS
                kmf = customKmf

                try {
                    val tempKs = java.security.KeyStore.getInstance("PKCS12").also { it.load(null, null) }
                    val certPem = encryptedPrefs.getString("cert_pem", null) ?: ""
                    val reader = PEMParser(StringReader(certPem))
                    val holder = reader.readObject() as? X509CertificateHolder
                    reader.close()
                    if (holder != null) {
                        val cert = JcaX509CertificateConverter().setProvider("BC").getCertificate(holder)
                        val digest = MessageDigest.getInstance("SHA-256")
                        serverCertFingerprint = digest.digest(cert.encoded).joinToString("") { "%02x".format(it) }
                    }
                } catch (_: Exception) {}
                Log.i(TAG, "Using custom certificate")
            } else {
                aks = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
                if (!aks.containsAlias(SSL_KEY_ALIAS)) {
                    val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
                    kpg.initialize(
                        KeyGenParameterSpec.Builder(SSL_KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                            .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                            .setCertificateSubject(X500Principal("CN=UFM Remote Manage"))
                            .build()
                    )
                    kpg.generateKeyPair()
                    Log.i(TAG, "Generated ECDSA key ($SSL_KEY_ALIAS)")
                }
                aks.getCertificate(SSL_KEY_ALIAS)?.let { cert ->
                    val digest = MessageDigest.getInstance("SHA-256")
                    serverCertFingerprint = digest.digest(cert.encoded).joinToString("") { "%02x".format(it) }
                }
                kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                kmf.init(aks, null)
            }

            val delegate = kmf.keyManagers[0] as X509KeyManager
            val pinned = object : X509KeyManager {
                override fun getClientAliases(t: String?, i: Array<out Principal>?) = null
                override fun chooseClientAlias(t: Array<out String>?, i: Array<out Principal>?, s: java.net.Socket?) = null
                override fun getServerAliases(t: String?, i: Array<out Principal>?) = arrayOf(keyAlias)
                override fun chooseServerAlias(t: String?, i: Array<out Principal>?, s: java.net.Socket?) = keyAlias
                override fun getCertificateChain(a: String?) = delegate.getCertificateChain(a)
                override fun getPrivateKey(a: String?) = delegate.getPrivateKey(a)
            }

            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(arrayOf(pinned), null, SecureRandom())

            val localPort = findFreePort()
            ktorServer = embeddedServer(Netty, port = localPort, host = "127.0.0.1") {
                routing {
                    post("/api/auth") { withContext(Dispatchers.IO) { handleAuth(KtorSession(call)).send(call) } }
                    get("/api/logo") { withContext(Dispatchers.IO) { handleLogo().send(call) } }
                    get("/api/flags/{code}") { withContext(Dispatchers.IO) { handleFlag(KtorSession(call)).send(call) } }
                    get("/api/install-status") { withContext(Dispatchers.IO) { handleInstallStatus().send(call) } }
                    route("/api/{...}") {
                        handle {
                            withContext(Dispatchers.IO) {
                                val session = KtorSession(call)
                                Log.d(TAG, "[API] ${session.method} ${session.uri}")
                                if (!isLanOrLocalhost(session.remoteIpAddress)) { KtorResult.Json(HttpStatusCode.Forbidden, JSONObject().put("error", "Access restricted to local network").toString()).send(call); return@withContext }
                                val uri = session.uri; val isPost = session.method == "POST"
                                if (!isAuthorized(session) && !consumeDownloadTicket(session, uri)) { KtorResult.Json(HttpStatusCode.Unauthorized, JSONObject().put("error", "Unauthorized").toString()).send(call); return@withContext }
                                val result: KtorResult = when {
                                    uri == "/api/volumes" -> handleVolumes(session)
                                    uri == "/api/install-status" -> handleInstallStatus()
                                    uri == "/api/vault/status" -> handleVaultStatus()
                                    uri == "/api/vault/set-pin" && isPost -> handleVaultSetPin(session)
                                    uri == "/api/vault/auth" && isPost -> handleVaultAuth(session)
                                    uri == "/api/vault/list" -> handleVaultList(session)
                                    uri == "/api/vault/add" && isPost -> handleVaultAdd(session)
                                    uri == "/api/vault/open" && isPost -> handleVaultOpen(session)
                                    uri == "/api/vault/close" && isPost -> handleVaultClose(session)
                                    uri == "/api/vault/decrypt-original" && isPost -> handleVaultDecryptOriginal(session)
                                    uri == "/api/vault/entries" -> handleVaultEntries(session)
                                    uri == "/api/vault/encrypt-files" && isPost -> handleVaultEncryptFiles(session)
                                    uri == "/api/browse" -> handleBrowse(session)
                                    uri == "/api/mkdir" && isPost -> handleMkdir(session)
                                    uri == "/api/delete" && isPost -> handleDelete(session)
                                    uri == "/api/rename" && isPost -> handleRename(session)
                                    uri == "/api/copy" && isPost -> handleCopy(session)
                                    uri == "/api/move" && isPost -> handleMove(session)
                                    uri == "/api/upload" && isPost -> handleUpload(session)
                                    uri == "/api/upload-stream" && isPost -> handleStreamUpload(session)
                                    uri == "/api/download-ticket" && isPost -> handleDownloadTicket(session)
                                    uri == "/api/download" -> handleDownload(session)
                                    uri == "/api/download-folder-start" -> handleDownloadFolderStart(session)
                                    uri == "/api/download-folder-status" -> handleDownloadFolderStatus(session)
                                    uri == "/api/download-folder-file" -> handleDownloadFolderFile(session)
                                    uri == "/api/download-folder-cancel" -> handleDownloadFolderCancel(session)
                                    uri == "/api/folder-info" -> handleFolderInfo(session)
                                    uri == "/api/install-apk" -> handleInstallApk(session)
                                    uri == "/api/install-remote" && isPost -> handleInstallRemote(session)
                                    uri == "/api/install-xapk" -> handleInstallXapk(session)
                                    uri == "/api/install-xapk-remote" && isPost -> handleInstallXapkRemote(session)
                                    uri == "/api/xapk-status" -> handleXapkStatus(session)
                                    uri == "/api/xapk-info" -> handleXapkInfo(session)
                                    uri == "/api/xapk-splits" -> handleXapkSplits(session)
                                    uri == "/api/apps" -> handleApps()
                                    uri == "/api/apps/open-info" && isPost -> handleAppOpenInfo(session)
                                    uri == "/api/apps/extract" -> handleAppExtract(session)
                                    else -> KtorResult.Json(HttpStatusCode.NotFound, JSONObject().put("error", "Not found").toString())
                                }
                                result.send(call)
                            }
                        }
                    }
                    get("/") {
                        withContext(Dispatchers.IO) {
                            val remoteIp = call.request.local.remoteHost
                            if (!isLanOrLocalhost(remoteIp)) { call.respondText("Access restricted to local network", status = HttpStatusCode.Forbidden); return@withContext }
                            serveWebUI(KtorSession(call)).send(call)
                        }
                    }
                    get("/{...}") {
                        withContext(Dispatchers.IO) {
                            val remoteIp = call.request.local.remoteHost; val uri = call.request.path()
                            if (!isLanOrLocalhost(remoteIp)) { call.respondText("Access restricted to local network", status = HttpStatusCode.Forbidden); return@withContext }
                            val isStaticAsset = uri.startsWith("/css/") || uri.startsWith("/js/") || uri.startsWith("/img/") || uri.startsWith("/fonts/") || uri.startsWith("/sounds/") || uri.endsWith(".ico")
                            if (isStaticAsset) {
                                val ext = uri.substringAfterLast('.', ""); val assetPath = "remote$uri"
                                val mime = when (ext.lowercase()) { "css" -> "text/css"; "js" -> "application/javascript"; "html" -> "text/html"; "png" -> "image/png"; "jpg","jpeg" -> "image/jpeg"; "svg" -> "image/svg+xml"; "ico" -> "image/x-icon"; "woff" -> "font/woff"; "woff2" -> "font/woff2"; "ttf" -> "font/ttf"; "mp3" -> "audio/mpeg"; "ogg" -> "audio/ogg"; else -> "application/octet-stream" }
                                try { val inputStream = context.assets.open(assetPath); KtorResult.Stream(status = HttpStatusCode.OK, contentType = mime, headers = mapOf("Cache-Control" to "public, max-age=86400"), body = { inputStream }).send(call) } catch (e: Exception) { call.respondText("Not found", status = HttpStatusCode.NotFound) }
                            } else { serveWebUI(KtorSession(call)).send(call) }
                        }
                    }
                }
            }
            ktorServer?.start(wait = false)

            // Try the preferred port first, then fall back to 8444..8453 if busy.
            val factory = sslContext.serverSocketFactory as javax.net.ssl.SSLServerSocketFactory
            val candidatePorts = listOf(port) + (port + 1..port + 10).toList()
            var bound = false
            for (candidatePort in candidatePorts) {
                try {
                    val sock = factory.createServerSocket() as javax.net.ssl.SSLServerSocket
                    sock.reuseAddress = true
                    sock.bind(java.net.InetSocketAddress(candidatePort))
                    sslServerSocket = sock
                    boundPort = candidatePort
                    if (candidatePort != port) {
                        Log.w(TAG, "Port $port was busy, using fallback port $candidatePort")
                    }
                    bound = true
                    break
                } catch (be: java.net.BindException) {
                    Log.w(TAG, "Port $candidatePort busy, trying next...")
                }
            }
            if (!bound) throw java.net.BindException("No free port found in range $port..${port + 10}")

            serverScope.launch {
                while (true) {
                    val ss = sslServerSocket ?: break
                    try {
                        val clientSocket = ss.accept()
                        serverScope.launch {
                            proxyToKtor(clientSocket, "127.0.0.1", localPort)
                        }
                    } catch (e: java.io.IOException) {
                        val sock = sslServerSocket
                        if (sock == null || sock.isClosed) break
                        Log.e(TAG, "SSL accept error", e)
                    }
                }
            }

            Log.i(TAG, "FileServer started on https://0.0.0.0:$port")
            serverCertFingerprint?.let { if (BuildConfig.DEBUG) Log.i(TAG, "TLS fingerprint: $it") }
            globalInstance = this
        } catch (e: Exception) { Log.e(TAG, "Failed to start FileServer", e) }
    }

    private fun proxyToKtor(clientSocket: java.net.Socket, targetHost: String, targetPort: Int) {
        clientSocket.soTimeout = 30000
        var targetSocket: java.net.Socket? = null
        try {
            targetSocket = java.net.Socket(targetHost, targetPort)
            targetSocket.soTimeout = 30000
            val clientIn = clientSocket.getInputStream()
            val clientOut = clientSocket.getOutputStream()
            val targetIn = targetSocket.getInputStream()
            val targetOut = targetSocket.getOutputStream()

            val c2t = thread(name = "proxy-c2t", start = false) {
                try { clientIn.copyTo(targetOut) } catch (_: Exception) {}
                runCatching { targetSocket?.close() }
                runCatching { clientSocket.close() }
            }
            val t2c = thread(name = "proxy-t2c", start = false) {
                try { targetIn.copyTo(clientOut) } catch (_: Exception) {}
                runCatching { clientSocket.close() }
                runCatching { targetSocket?.close() }
            }
            c2t.start(); t2c.start()
            c2t.join(); t2c.join()
        } catch (e: Exception) {
            Log.e(TAG, "SSL proxy error", e)
        } finally {
            runCatching { clientSocket.close() }
            runCatching { targetSocket?.close() }
        }
    }

    fun stop() {
        try {
            if (globalInstance === this) globalInstance = null
            serverScope.cancel()
            runCatching { sslServerSocket?.close() }; sslServerSocket = null
            val ktor = ktorServer
            ktorServer = null
            if (ktor != null) {
                Thread({ kotlin.runCatching { ktor.stop(gracePeriodMillis = 500, timeoutMillis = 1000) } }, "ktor-stop").start()
            }
            Log.i(TAG, "FileServer stopped")
        } catch (e: Exception) { Log.e(TAG, "Error stopping FileServer", e) }
    }

    /**
     * Sealed result type replacing NanoHTTPD.Response.
     * Each handler returns a [KtorResult] that [send] dispatches to the [ApplicationCall].
     */
    sealed class KtorResult {
        /** JSON response (most API endpoints). */
        data class Json(
            val status: HttpStatusCode,
            val body: String
        ) : KtorResult()

        /** Binary stream response (file download). */
        data class Stream(
            val status: HttpStatusCode,
            val contentType: String,
            val headers: Map<String, String> = emptyMap(),
            val body: () -> java.io.InputStream,
            val length: Long = -1L
        ) : KtorResult()

        /** Pre-rendered HTML response (web UI). */
        data class Html(
            val status: HttpStatusCode,
            val body: String,
            val extraHeaders: Map<String, String> = emptyMap()
        ) : KtorResult()

        /** Sends this result to the Ktor [ApplicationCall]. Must be called from a suspend context. */
        suspend fun send(call: ApplicationCall) {
            // SEC-LOW-4: Security headers on all responses
            call.response.header("X-Content-Type-Options", "nosniff")
            call.response.header("X-Frame-Options", "DENY")
            call.response.header("Referrer-Policy", "no-referrer")
            call.response.header("Content-Security-Policy",
                "default-src 'self' 'unsafe-inline' 'unsafe-eval' blob: data:; connect-src 'self'")
            when (this) {
                is Json -> call.respondText(body, ContentType.Application.Json, status)
                is Html -> {
                    extraHeaders.forEach { (k, v) -> call.response.header(k, v) }
                    call.respondText(body, ContentType.Text.Html, status)
                }
                is Stream -> {
                    headers.forEach { (k, v) -> call.response.header(k, v) }
                    val ct = ContentType.parse(contentType)
                    val stream = body()
                    if (length > 0L) {
                        call.respondOutputStream(ct, status, length) { stream.use { it.copyTo(this) } }
                    } else {
                        call.respondOutputStream(ct, status) { stream.use { it.copyTo(this) } }
                    }
                }
            }
        }
    }

    // Note: KtorResult is used directly as the response type in all handlers.

    private object Status {
        val OK                = HttpStatusCode.OK
        val BAD_REQUEST       = HttpStatusCode.BadRequest
        val UNAUTHORIZED      = HttpStatusCode.Unauthorized
        val FORBIDDEN         = HttpStatusCode.Forbidden
        val NOT_FOUND         = HttpStatusCode.NotFound
        val CONFLICT          = HttpStatusCode.Conflict
        val INTERNAL_ERROR    = HttpStatusCode.InternalServerError
        val TOO_MANY_REQUESTS = HttpStatusCode.TooManyRequests
    }

    private fun newFixedLengthResponse(
        status: HttpStatusCode,
        mimeType: String,
        data: ByteArray
    ): KtorResult = KtorResult.Stream(status, mimeType, body = { data.inputStream() }, length = data.size.toLong())

    private fun newFixedLengthResponse(
        status: HttpStatusCode,
        mimeType: String,
        inputStream: java.io.InputStream,
        length: Long
    ): KtorResult = KtorResult.Stream(status, mimeType, body = { inputStream }, length = length)

    private fun newChunkedResponse(
        status: HttpStatusCode,
        mimeType: String,
        inputStream: java.io.InputStream
    ): KtorResult = KtorResult.Stream(status, mimeType, body = { inputStream }, length = -1L)

    /** Convenience overload for text/JSON string bodies. */
    private fun newFixedLengthResponse(
        status: HttpStatusCode,
        mimeType: String,
        body: String
    ): KtorResult {
        val bytes = body.toByteArray(Charsets.UTF_8)
        return KtorResult.Stream(status, mimeType, body = { bytes.inputStream() }, length = bytes.size.toLong())
    }

    private fun jsonResponse(
        status: HttpStatusCode,
        vararg pairs: Pair<String, Any>
    ): KtorResult {
        val json = JSONObject()
        pairs.forEach { (key, value) -> json.put(key, value) }
        return KtorResult.Json(status, json.toString())
    }

    /**
     * SEC-CRIT-1: Returns true if [ip] is a loopback, local network, or RFC1918 LAN address.
     * Rejects all other origins to prevent exposure when the device is on a hostile network.
     */
    private fun isLanOrLocalhost(ip: String): Boolean {
        Log.d(TAG, "Checking if IP is LAN/Localhost: '$ip'")
        if (ip.isBlank()) return false
        if (ip == "127.0.0.1" || ip == "::1" || ip.equals("localhost", ignoreCase = true)) return true

        // Resolve hostname to IP address before any LAN checks.
        // This handles mDNS (.local), .lan, and any other hostnames by resolving
        // them to actual IPs and then applying the standard private-range checks.
        val remoteAddr = try { InetAddress.getByName(ip) } catch (e: Exception) { return false }
        if (remoteAddr.isLoopbackAddress) return true

        // 1. Dynamic Subnet Check
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList()
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                for (ifaceAddr in intf.interfaceAddresses) {
                    val localAddr = ifaceAddr.address
                    val prefix = ifaceAddr.networkPrefixLength

                    if (localAddr.address.size == remoteAddr.address.size) {
                        if (isInSubnet(remoteAddr.address, localAddr.address, prefix)) {
                            Log.d(TAG, "Allowed local address (Subnet Match): $ip")
                            return true
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read network interfaces", e)
        }

        // 2. Static Fallback Check for Private ranges (including VPNs and Link Local)
        val bytes = remoteAddr.address
        if (bytes.size == 4) {
            val a = bytes[0].toInt() and 0xFF
            val b = bytes[1].toInt() and 0xFF
            val isPrivate = a == 10 ||
                (a == 172 && (b in 16..31)) ||
                (a == 192 && b == 168) ||
                (a == 169 && b == 254) || // Link-local
                (a == 100 && (b in 64..127)) // Carrier-Grade NAT (e.g. Tailscale / ZeroTier)
            
            if (isPrivate) {
                Log.d(TAG, "Allowed private IP address: $ip")
                return true
            }
        } else if (bytes.size == 16) {
            // IPv6 IPv4-mapped addresses
            if (ip.startsWith("::ffff:")) {
                val ipv4 = ip.substringAfterLast(":")
                return isLanOrLocalhost(ipv4)
            }
            // IPv6 Unique Local (fc00::/7) and Link-Local (fe80::/10)
            val byte0 = bytes[0].toInt() and 0xFF
            if ((byte0 and 0xFE) == 0xFC || (byte0 and 0xC0) == 0x80) {
                Log.d(TAG, "Allowed Private/Local IPv6 address: $ip")
                return true
            }
        }

        Log.w(TAG, "Blocked external IP address: $ip")
        return false
    }

    private fun isInSubnet(remoteBytes: ByteArray, localBytes: ByteArray, prefixLength: Short): Boolean {
        var remainingPrefix = prefixLength.toInt()
        for (i in remoteBytes.indices) {
            if (remainingPrefix >= 8) {
                if (remoteBytes[i] != localBytes[i]) return false
                remainingPrefix -= 8
            } else if (remainingPrefix > 0) {
                val mask = (0xFF shl (8 - remainingPrefix)).toByte()
                if ((remoteBytes[i].toInt() and mask.toInt()) != (localBytes[i].toInt() and mask.toInt())) return false
                break
            } else {
                break
            }
        }
        return true
    }


    // ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ Auth ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬

    private fun handleAuth(session: KtorSession): KtorResult {
        val clientIp = session.remoteIpAddress
        val now = System.currentTimeMillis()

        // 1. Check for active lockout
        failedAttempts[clientIp]?.let { (count, expiry) ->
            if (count >= MAX_ATTEMPTS && now < expiry) {
                val remaining = (expiry - now) / 1000 / 60
                return jsonResponse(Status.TOO_MANY_REQUESTS, 
                    "error" to "Too many attempts. Account locked for $remaining minutes.")
            }
        }

        val body = mutableMapOf<String, String>()
        session.parseBody(body)
        val postData = body["postData"] ?: ""

        val json = try {
            JSONObject(postData)
        } catch (_: Exception) {
            return jsonResponse(Status.BAD_REQUEST, "error" to "Invalid JSON")
        }

        val submittedPin = json.optString("pin", "")
        if (submittedPin == pin) {
            // Success: Reset failed attempts for this IP
            failedAttempts.remove(clientIp)
            
            val token = UUID.randomUUID().toString()
            validSessions[token] = now + SESSION_TTL_MS
            return jsonResponse(Status.OK, "token" to token)
        }

        // Failure: Track attempts
        val current = failedAttempts[clientIp] ?: Pair(0, 0L)
        val nextCount = current.first + 1
        val nextExpiry = if (nextCount >= MAX_ATTEMPTS) now + LOCKOUT_MS else 0L
        failedAttempts[clientIp] = Pair(nextCount, nextExpiry)

        Log.w(TAG, "Failed auth attempt $nextCount from $clientIp")
        return jsonResponse(Status.FORBIDDEN, "error" to "Invalid PIN")
    }

    /** Consume a single-use download ticket from the ?ticket= query parameter.
     *  Only valid for download endpoints. Returns true if a valid ticket was consumed. */
    private fun consumeDownloadTicket(session: KtorSession, uri: String): Boolean {
        val downloadEndpoints = setOf("/api/download", "/api/download-folder-start",
            "/api/download-folder-status", "/api/download-folder-file",
            "/api/download-folder-cancel", "/api/apps/extract")
        if (uri !in downloadEndpoints) return false
        val ticketParam = session.parms["ticket"]
        if (ticketParam.isNullOrBlank()) return false
        val ticket = downloadTickets[ticketParam] ?: return false
        downloadTickets.remove(ticketParam)
        val now = System.currentTimeMillis()
        return !ticket.used && now <= ticket.expiresAt
    }

    private fun isAuthorized(session: KtorSession): Boolean {
        val now = System.currentTimeMillis()
        fun checkToken(token: String?): Boolean {
            if (token.isNullOrBlank()) return false
            val expiry = validSessions[token] ?: return false
            if (now > expiry) {
                validSessions.remove(token)
                Log.d(TAG, "Session expired: ${token.take(8)}...")
                return false
            }
            return true
        }
        // Primary: Authorization header (used by fetch() API calls)
        val authHeader = session.headers["authorization"]
        if (!authHeader.isNullOrBlank()) {
            return checkToken(authHeader.removePrefix("Bearer ").trim())
        }
        return false
    }

    // ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ Volumes ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬

    private fun handleVolumes(session: KtorSession): KtorResult {
        val lang = session.ufmLang
        val localizedCtx = getLocalizedContext(lang)
        val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        val volumes = storageManager.storageVolumes
        val arr = JSONArray()

        for (volume in volumes) {
            val path = volume.safeDirectoryPath ?: continue

            val stat = try { StatFs(path) } catch (_: Exception) { continue }

            val obj = JSONObject().apply {
                put("label", getLocalizedLabel(lang, volume))
                put("path", path)
                put("primary", volume.isPrimary)
                put("removable", volume.isRemovable)
                put("totalBytes", stat.totalBytes)
                put("freeBytes", stat.freeBytes)
                put("totalFormatted", Formatter.formatFileSize(context, stat.totalBytes))
                put("freeFormatted", Formatter.formatFileSize(context, stat.freeBytes))
            }
            arr.put(obj)
        }
        
        // Append Network Shares to the volumes list
        val networkShares = za.kilowatch.ultimatefilemanager.network.NetworkShareRepository.getInstance(context).getAll()
        for (share in networkShares) {
            val obj = JSONObject().apply {
                put("label", share.name) // Keep share name as is, but maybe add "Network Share" as type
                put("path", "net:${share.id}") 
                put("primary", false)
                put("removable", false)
                put("totalBytes", 0L)
                put("freeBytes", 0L)
                put("totalFormatted", getLocalizedLabel(lang, null))
                put("freeFormatted", if (share.readOnly) localizedCtx.getString(R.string.remote_status_readonly) else localizedCtx.getString(R.string.remote_status_shared))
                put("isNetwork", true)
            }
            arr.put(obj)
        }

        // Append Online Storages to the volumes list
        val onlineStorages = za.kilowatch.ultimatefilemanager.network.OnlineStorageRepository.getInstance(context).getAll()
        for (storage in onlineStorages) {
            val obj = JSONObject().apply {
                put("label", if (storage.displayName.isNotEmpty()) storage.displayName else storage.email)
                put("path", "net:${storage.id}") // using net: prefix for consistency
                put("primary", false)
                put("removable", false)
                put("totalBytes", 0L)
                put("freeBytes", 0L)
                put("totalFormatted", getLocalizedLabel(lang, null))
                put("freeFormatted", localizedCtx.getString(R.string.remote_status_shared))
                put("isNetwork", true)
                put("provider", storage.provider.name.lowercase())
            }
            arr.put(obj)
        }

        return KtorResult.Json(Status.OK, arr.toString())
    }

    private fun resolveShare(shareId: String): za.kilowatch.ultimatefilemanager.network.NetworkShare? {
        Log.d(TAG, "resolveShare: resolving shareId=$shareId")
        val netRepo = za.kilowatch.ultimatefilemanager.network.NetworkShareRepository.getInstance(context)
        val share = netRepo.getById(shareId)
        if (share != null) {
            Log.d(TAG, "resolveShare: found network share '${share.name}'")
            return share
        }

        val onlineRepo = za.kilowatch.ultimatefilemanager.network.OnlineStorageRepository.getInstance(context)
        val storage = onlineRepo.getById(shareId) 
        if (storage == null) {
            Log.w(TAG, "resolveShare: shareId=$shareId not found in either repository")
            return null
        }

        Log.d(TAG, "resolveShare: found online storage '${storage.email}' (${storage.provider})")
        return za.kilowatch.ultimatefilemanager.network.NetworkShare(
            id = storage.id,
            name = if (storage.displayName.isNotEmpty()) storage.displayName else storage.email,
            type = when (storage.provider) {
                za.kilowatch.ultimatefilemanager.network.OnlineStorageProvider.ONEDRIVE     -> za.kilowatch.ultimatefilemanager.network.ShareType.ONEDRIVE
                za.kilowatch.ultimatefilemanager.network.OnlineStorageProvider.GOOGLE_DRIVE -> za.kilowatch.ultimatefilemanager.network.ShareType.GOOGLE_DRIVE
                za.kilowatch.ultimatefilemanager.network.OnlineStorageProvider.DROPBOX      -> za.kilowatch.ultimatefilemanager.network.ShareType.DROPBOX
                za.kilowatch.ultimatefilemanager.network.OnlineStorageProvider.AWS_S3       -> za.kilowatch.ultimatefilemanager.network.ShareType.AWS_S3
                za.kilowatch.ultimatefilemanager.network.OnlineStorageProvider.IDRIVE_E2   -> za.kilowatch.ultimatefilemanager.network.ShareType.IDRIVE_E2
                za.kilowatch.ultimatefilemanager.network.OnlineStorageProvider.WEBDAV      -> za.kilowatch.ultimatefilemanager.network.ShareType.WEBDAV
            },
            host = storage.s3Endpoint ?: storage.email,
            domain = storage.s3Bucket ?: "",
            remotePath = storage.s3Region ?: "",
            username = storage.s3AccessKey ?: storage.email,
            password = storage.s3SecretKey ?: "",
            readOnly = false
        )
    }

    // ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ Vault ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬

    private fun handleVaultStatus(): KtorResult {
        val hasPin = getVaultPinHash() != null
        val entries = readVaultEntries()
        return jsonResponse(
            Status.OK,
            "hasPin" to hasPin,
            "hasVault" to entries.isNotEmpty(),
            "count" to entries.size
        )
    }

    private fun handleVaultSetPin(session: KtorSession): KtorResult {
        if (getVaultPinHash() != null) {
            return jsonResponse(Status.CONFLICT, "error" to "Vault PIN already set")
        }
        val body = mutableMapOf<String, String>()
        session.parseBody(body)
        val json = JSONObject(body["postData"] ?: "{}")
        val pin = json.optString("pin", "")
        if (pin.length != 4 || !pin.all { it.isDigit() }) {
            return jsonResponse(Status.BAD_REQUEST, "error" to "Invalid PIN")
        }
        setVaultPinHash(sha256(pin))
        return jsonResponse(Status.OK, "success" to true)
    }

    private fun handleVaultAuth(session: KtorSession): KtorResult {
        val clientIp = session.remoteIpAddress ?: "unknown"
        val now = System.currentTimeMillis()

        // SEC-CRIT-3: Brute-force protection for vault PIN.
        vaultFailedAttempts[clientIp]?.let { (count, expiry) ->
            if (count >= VAULT_MAX_ATTEMPTS && now < expiry) {
                val remaining = (expiry - now) / 1000 / 60
                return jsonResponse(Status.TOO_MANY_REQUESTS,
                    "error" to "Too many attempts. Vault locked for $remaining minutes.")
            }
        }

        val body = mutableMapOf<String, String>()
        session.parseBody(body)
        val json = JSONObject(body["postData"] ?: "{}")
        val submittedPin = json.optString("pin", "")
        val storedValue = getVaultPinHash()

        if (storedValue != null) {
            // Migration: if stored value is a legacy plaintext 4-digit PIN (not a 64-char hex
            // hash), hash it and re-save transparently so the user is never locked out.
            val resolvedHash = if (storedValue.length == 4 && storedValue.all { it.isDigit() }) {
                val h = sha256(storedValue)
                setVaultPinHash(h)
                h
            } else {
                storedValue // already a SHA-256 hex string
            }

            if (sha256(submittedPin) == resolvedHash) {
                // Success: clear vault lockout for this IP
                vaultFailedAttempts.remove(clientIp)
                val token = UUID.randomUUID().toString()
                vaultSessions[token] = now + SESSION_TTL_MS
                return jsonResponse(Status.OK, "vaultToken" to token)
            }
        }

        // Failure: track vault attempts per IP
        val current = vaultFailedAttempts[clientIp] ?: Pair(0, 0L)
        val nextCount = current.first + 1
        val nextExpiry = if (nextCount >= VAULT_MAX_ATTEMPTS) now + VAULT_LOCKOUT_MS else 0L
        vaultFailedAttempts[clientIp] = Pair(nextCount, nextExpiry)
        Log.w(TAG, "Failed vault auth attempt $nextCount from $clientIp")
        return jsonResponse(Status.FORBIDDEN, "error" to "Invalid PIN")
    }

    private fun handleVaultList(session: KtorSession): KtorResult {
        if (!isVaultAuthorized(session)) {
            return jsonResponse(Status.UNAUTHORIZED, "error" to "Vault unauthorized")
        }
        val entries = readVaultEntries()
        val arr = JSONArray()
        entries.forEach { entry ->
            val obj = JSONObject().apply {
                put("id", entry.id)
                put("displayName", entry.displayName)
                put("originalRoot", entry.originalRoot)
                put("fileCount", entry.files.size)
            }
            arr.put(obj)
        }
        return KtorResult.Json(Status.OK, arr.toString())
    }

    private fun handleVaultAdd(session: KtorSession): KtorResult {
        if (!isVaultAuthorized(session)) {
            return jsonResponse(Status.UNAUTHORIZED, "error" to "Vault unauthorized")
        }
        val body = mutableMapOf<String, String>()
        session.parseBody(body)
        val json = JSONObject(body["postData"] ?: "{}")
        val path = json.optString("path", "")
        if (path.isEmpty()) {
            return jsonResponse(Status.BAD_REQUEST, "error" to "Missing path")
        }
        val root = File(path)
        if (!root.exists() || !root.isDirectory) {
            return jsonResponse(Status.NOT_FOUND, "error" to "Folder not found")
        }
        return try {
            val entry = encryptFolder(root)
            jsonResponse(Status.OK, "success" to true, "id" to entry.id)
        } catch (e: Exception) {
            jsonResponse(Status.INTERNAL_ERROR, "error" to (e.message ?: "Encrypt failed"))
        }
    }

    private fun handleVaultOpen(session: KtorSession): KtorResult {
        if (!isVaultAuthorized(session)) {
            return jsonResponse(Status.UNAUTHORIZED, "error" to "Vault unauthorized")
        }
        val body = mutableMapOf<String, String>()
        session.parseBody(body)
        val json = JSONObject(body["postData"] ?: "{}")
        val entryId = json.optString("entryId", "")
        if (entryId.isEmpty()) {
            return jsonResponse(Status.BAD_REQUEST, "error" to "Missing entryId")
        }
        val entry = readVaultEntry(entryId) ?: return jsonResponse(Status.NOT_FOUND, "error" to "Entry not found")
        return try {
            val mirrorPath = decryptToMirror(entry)
            jsonResponse(Status.OK, "mirrorPath" to mirrorPath)
        } catch (e: Exception) {
            jsonResponse(Status.INTERNAL_ERROR, "error" to (e.message ?: "Open failed"))
        }
    }

    private fun handleVaultClose(session: KtorSession): KtorResult {
        if (!isVaultAuthorized(session)) {
            return jsonResponse(Status.UNAUTHORIZED, "error" to "Vault unauthorized")
        }
        val body = mutableMapOf<String, String>()
        session.parseBody(body)
        val json = JSONObject(body["postData"] ?: "{}")
        val entryId = json.optString("entryId", "")
        if (entryId.isEmpty()) {
            return jsonResponse(Status.BAD_REQUEST, "error" to "Missing entryId")
        }
        val entry = readVaultEntry(entryId) ?: return jsonResponse(Status.NOT_FOUND, "error" to "Entry not found")
        return try {
            reEncryptFromMirror(entry)
            jsonResponse(Status.OK, "success" to true)
        } catch (e: Exception) {
            jsonResponse(Status.INTERNAL_ERROR, "error" to (e.message ?: "Close failed"))
        }
    }

    private fun handleVaultDecryptOriginal(session: KtorSession): KtorResult {
        if (!isVaultAuthorized(session)) {
            return jsonResponse(Status.UNAUTHORIZED, "error" to "Vault unauthorized")
        }
        val body = mutableMapOf<String, String>()
        session.parseBody(body)
        val json = JSONObject(body["postData"] ?: "{}")
        val entryId = json.optString("entryId", "")
        if (entryId.isEmpty()) {
            return jsonResponse(Status.BAD_REQUEST, "error" to "Missing entryId")
        }
        val entry = readVaultEntry(entryId) ?: return jsonResponse(Status.NOT_FOUND, "error" to "Entry not found")
        return try {
            decryptToOriginal(entry)
            jsonResponse(Status.OK, "success" to true)
        } catch (e: Exception) {
            jsonResponse(Status.INTERNAL_ERROR, "error" to (e.message ?: "Decrypt failed"))
        }
    }

    private fun handleVaultEntries(session: KtorSession): KtorResult {
        if (!isVaultAuthorized(session)) {
            return jsonResponse(Status.UNAUTHORIZED, "error" to "Vault unauthorized")
        }
        val entries = readVaultEntries()
        val entriesJson = JSONArray().apply {
            entries.forEach { entry ->
                put(JSONObject().apply {
                    put("id", entry.id)
                    put("displayName", entry.displayName)
                    put("fileCount", entry.files.size)
                })
            }
        }
        return jsonResponse(Status.OK, "entries" to entriesJson)
    }

    private fun handleVaultEncryptFiles(session: KtorSession): KtorResult {
        if (!isVaultAuthorized(session)) {
            return jsonResponse(Status.UNAUTHORIZED, "error" to "Vault unauthorized")
        }
        val body = mutableMapOf<String, String>()
        session.parseBody(body)
        val json = JSONObject(body["postData"] ?: "{}")
        val entryId = json.optString("entryId", "")
        val deleteOriginals = json.optBoolean("deleteOriginals", false)
        val filesArray = json.optJSONArray("files")
        
        if (entryId.isEmpty()) {
            return jsonResponse(Status.BAD_REQUEST, "error" to "Missing entryId")
        }
        if (filesArray == null || filesArray.length() == 0) {
            return jsonResponse(Status.BAD_REQUEST, "error" to "No files specified")
        }
        
        val entry = readVaultEntry(entryId) 
            ?: return jsonResponse(Status.NOT_FOUND, "error" to "Entry not found")
        
        return try {
            val entryDir = File(vaultBaseDir(), entryId)
            val addedFiles = mutableListOf<String>()
            var successCount = 0
            var failCount = 0
            
            for (i in 0 until filesArray.length()) {
                val filePath = filesArray.getString(i)
                val file = File(filePath)
                
                if (!file.exists()) {
                    failCount++
                    continue
                }
                
                // Skip system files
                if (isSystemFile(file) || isHiddenFile(file)) {
                    failCount++
                    continue
                }
                
                try {
                    val relative = file.name
                    val encryptedFile = File(entryDir, "$relative.enc")
                    VaultCrypto.encryptFile(file, encryptedFile)
                    addedFiles.add(relative)
                    successCount++
                    
                    if (deleteOriginals) {
                        val path = file.absolutePath
                        if (file.delete()) {
                            notifyIndexing(path, isDelete = true, isFolder = false)
                        }
                    }
                } catch (e: Exception) {
                    failCount++
                }
            }
            
            // Update metadata with new files
            if (addedFiles.isNotEmpty()) {
                val allFiles = entry.files.toMutableList()
                allFiles.addAll(addedFiles)
                val metadata = JSONObject().apply {
                    put("id", entry.id)
                    put("displayName", entry.displayName)
                    put("originalRoot", entry.originalRoot)
                    put("files", JSONArray(allFiles))
                }
                File(entryDir, "metadata.json").writeText(metadata.toString())
            }
            
            jsonResponse(Status.OK, 
                "success" to true, 
                "addedCount" to successCount,
                "failedCount" to failCount
            )
        } catch (e: Exception) {
            jsonResponse(Status.INTERNAL_ERROR, "error" to (e.message ?: "Encrypt failed"))
        }
    }

    private fun isVaultAuthorized(session: KtorSession): Boolean {
        val now = System.currentTimeMillis()
        fun checkToken(token: String?): Boolean {
            if (token == null) return false
            val expiry = vaultSessions[token] ?: return false
            if (now > expiry) {
                vaultSessions.remove(token)
                // SEC-MED-3: Redact token value in log.
                Log.d(TAG, "Vault session expired: ${token.take(8)}...")
                return false
            }
            return true
        }
        // SEC-HIGH-7: Vault token in X-Vault-Token header only.
        return checkToken(session.headers["x-vault-token"])
    }

    /** Returns the raw stored value (may be a plaintext 4-digit legacy PIN or a SHA-256 hex hash). */
    private fun getVaultPinHash(): String? {
        val prefs = context.getSharedPreferences("vault_prefs", Context.MODE_PRIVATE)
        return prefs.getString("vault_pin", null)
    }

    /** Stores a SHA-256 hex hash of the vault PIN. */
    private fun setVaultPinHash(hash: String) {
        val prefs = context.getSharedPreferences("vault_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("vault_pin", hash).apply()
    }

    /** SHA-256 hash of a string, returned as lowercase hex (64 chars). */
    private fun sha256(input: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun vaultBaseDir(): File = File(context.filesDir, "vault")

    private fun mirrorBaseDir(): File = File(context.filesDir, "vault_mirror")

    private fun readVaultEntries(): List<VaultEntry> {
        val base = vaultBaseDir()
        if (!base.exists()) return emptyList()
        return base.listFiles()?.mapNotNull { dir ->
            readVaultEntry(dir.name)
        } ?: emptyList()
    }

    private fun readVaultEntry(entryId: String): VaultEntry? {
        return try {
            val entryDir = File(vaultBaseDir(), entryId)
            val metadataFile = File(entryDir, "metadata.json")
            if (!metadataFile.exists()) return null
            val json = JSONObject(metadataFile.readText())
            val filesJson = json.getJSONArray("files")
            val files = mutableListOf<String>()
            for (i in 0 until filesJson.length()) {
                files.add(filesJson.getString(i))
            }
            VaultEntry(
                id = json.getString("id"),
                displayName = json.getString("displayName"),
                originalRoot = json.getString("originalRoot"),
                files = files
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun encryptFolder(root: File): VaultEntry {
        val entryId = UUID.randomUUID().toString()
        val entryDir = File(vaultBaseDir(), entryId)
        entryDir.mkdirs()

        // Filter out system files and hidden files
        val files = root.walkTopDown()
            .filter { it.isFile }
            .filter { !isSystemFile(it) }
            .filter { !isHiddenFile(it) }
            .toList()
        val relativeList = mutableListOf<String>()

        files.forEach { file ->
            val relative = file.relativeTo(root).path
            val encryptedFile = File(entryDir, "$relative.enc")
            VaultCrypto.encryptFile(file, encryptedFile)
            relativeList.add(relative)
            val path = file.absolutePath
            if (file.delete()) {
                notifyIndexing(path, isDelete = true, isFolder = false)
            }
        }

        root.walkBottomUp().forEach { dir ->
            if (dir.isDirectory && dir.listFiles()?.isEmpty() == true) {
                dir.delete()
            }
        }

        val metadata = JSONObject().apply {
            put("id", entryId)
            put("displayName", root.name)
            put("originalRoot", root.absolutePath)
            put("files", JSONArray(relativeList))
        }
        File(entryDir, "metadata.json").writeText(metadata.toString())

        return VaultEntry(entryId, root.name, root.absolutePath, relativeList)
    }

    /**
     * Checks if a file is a system file that should not be encrypted.
     * System files include: Android system directories, cache, temp files, etc.
     */
    private fun isSystemFile(file: File): Boolean {
        val path = file.absolutePath.lowercase()
        
        // Get app's private data directory - files in our own app directory are allowed
        val appPrivateDir = context.filesDir.absolutePath.lowercase()
        val appExternalDir = context.getExternalFilesDir(null)?.absolutePath?.lowercase() ?: ""
        
        // Allow files from our app's private directories
        if (path.startsWith(appPrivateDir) || (appExternalDir.isNotEmpty() && path.startsWith(appExternalDir))) {
            return false
        }
        
        // Android system paths (excluding our app's data directory)
        val systemPaths = listOf(
            "/system/", "/proc/", "/sys/", "/dev/", "/data/system/",
            "/data/dalvik-cache/", "/data/app/"
        )
        
        // Check if file is in a system path
        if (systemPaths.any { path.startsWith(it) }) return true
        
        // Check for common system file patterns
        val systemFilePatterns = listOf(
            ".nomedia", "thumbs.db", "desktop.ini", ".ds_store",
            ".trash-", ".spotlight-", ".fseventsd", ".temporaryitems",
            ".localized", ".com.apple.timemachine.donotpresent"
        )
        
        return systemFilePatterns.any { file.name.lowercase() == it.lowercase() }
    }

    /**
     * Checks if a file is hidden (starts with dot) or in a hidden directory.
     */
    private fun isHiddenFile(file: File): Boolean {
        // Check if file name starts with dot
        if (file.name.startsWith(".")) return true
        
        // Check if any parent directory is hidden
        var parent = file.parentFile
        while (parent != null) {
            if (parent.name.startsWith(".")) return true
            parent = parent.parentFile
        }
        
        return false
    }

    private fun decryptToMirror(entry: VaultEntry): String {
        val entryDir = File(vaultBaseDir(), entry.id)
        val mirrorDir = File(mirrorBaseDir(), entry.id)
        mirrorDir.deleteRecursively()
        mirrorDir.mkdirs()

        entry.files.forEach { relative ->
            val encryptedFile = File(entryDir, "$relative.enc")
            val outputFile = File(mirrorDir, relative)
            VaultCrypto.decryptFile(encryptedFile, outputFile)
        }

        return mirrorDir.absolutePath
    }

    private fun reEncryptFromMirror(entry: VaultEntry) {
        val entryDir = File(vaultBaseDir(), entry.id)
        val mirrorDir = File(mirrorBaseDir(), entry.id)
        entryDir.deleteRecursively()
        entryDir.mkdirs()

        // Filter out system files and hidden files
        val files = mirrorDir.walkTopDown()
            .filter { it.isFile }
            .filter { !isSystemFile(it) }
            .filter { !isHiddenFile(it) }
            .toList()
        val relativeList = mutableListOf<String>()
        files.forEach { file ->
            val relative = file.relativeTo(mirrorDir).path
            val encryptedFile = File(entryDir, "$relative.enc")
            VaultCrypto.encryptFile(file, encryptedFile)
            relativeList.add(relative)
        }

        val metadata = JSONObject().apply {
            put("id", entry.id)
            put("displayName", entry.displayName)
            put("originalRoot", entry.originalRoot)
            put("files", JSONArray(relativeList))
        }
        File(entryDir, "metadata.json").writeText(metadata.toString())

        mirrorDir.deleteRecursively()
    }

    private fun decryptToOriginal(entry: VaultEntry) {
        val entryDir = File(vaultBaseDir(), entry.id)
        val originalRoot = File(entry.originalRoot)
        entry.files.forEach { relative ->
            val encryptedFile = File(entryDir, "$relative.enc")
            val outputFile = File(originalRoot, relative)
            VaultCrypto.decryptFile(encryptedFile, outputFile)
        }
        // Delete the vault entry after successful decryption
        entryDir.deleteRecursively()
    }
    // ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ Path Validation ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬
    private fun isPathAllowed(path: String): Boolean {
        if (path.startsWith("net:") || path.startsWith("content:")) return true
        
        val file = File(path)
        val canonicalReq = try { file.canonicalPath } catch (e: Exception) { return false }
        
        // 1. Direct Volume check: If the path belongs to a user-manageable storage volume, allow it.
        // This is the most reliable way to permit shared storage (Internal/SD) regardless of canonical paths.
        val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
        if (storageManager != null) {
            try {
                val volume = storageManager.getStorageVolume(file)
                if (volume != null) {
                    // It's a recognized storage volume. 
                    // Now just ensure they aren't trying to browse our OWN app's private sandbox *inside* Android/data on storage.
                    val dataDir = try { File(context.applicationInfo.dataDir).canonicalPath } catch (e: Exception) { null }
                    if (dataDir != null && canonicalReq.startsWith(dataDir)) {
                        // Still block our own private data dir.
                        return false
                    }
                    return true
                }
            } catch (_: Exception) { }
        }

        // 2. Fallback Sandbox check for non-volume paths (like /data/system or /proc)
        val dataDir = try { File(context.applicationInfo.dataDir).canonicalPath } catch (e: Exception) { null }
        if (dataDir != null && canonicalReq.startsWith(dataDir)) {
            val cacheDir = try { context.cacheDir.canonicalPath } catch (e: Exception) { null }
            if (cacheDir != null && canonicalReq.startsWith(cacheDir)) return true
            return false
        }
        return true
    }

    // ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ Browse ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬
    private fun handleBrowse(session: KtorSession): KtorResult {
        val path = session.parms["path"] ?: return jsonResponse(
            Status.BAD_REQUEST, "error" to "Missing 'path' parameter"
        )

        if (path.startsWith("net:")) {
            return handleNetworkBrowse(path)
        }

        val dir = File(path)
        if (!isPathAllowed(dir.absolutePath)) {
            return jsonResponse(Status.FORBIDDEN, "error" to "Permission denied: restricted path")
        }
        if (!dir.exists() || !dir.isDirectory) {
            return jsonResponse(Status.NOT_FOUND, "error" to "Directory not found")
        }
        if (!dir.canRead()) {
            return jsonResponse(Status.FORBIDDEN, "error" to "Permission denied: cannot read this directory")
        }

        val files = dir.listFiles() ?: emptyArray()
        val arr = JSONArray()

        for (file in files.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })) {
            val obj = JSONObject().apply {
                put("name", file.name)
                put("path", file.absolutePath)
                put("isDirectory", file.isDirectory)
                
                if (file.isDirectory) {
                    val childItems = file.listFiles() ?: emptyArray()
                    val folderSize = calculateFolderSize(file)
                    put("size", folderSize)
                    put("sizeFormatted", if (folderSize > 0) Formatter.formatFileSize(context, folderSize) else "0 B")
                    put("childCount", childItems.size)
                    put("childCountFormatted", "${childItems.size} item${if (childItems.size != 1) "s" else ""}")
                } else {
                    put("size", file.length())
                    put("sizeFormatted", Formatter.formatFileSize(context, file.length()))
                }
                
                put("lastModified", file.lastModified())
                put("canRead", file.canRead())
                put("canWrite", file.canWrite())
            }
            arr.put(obj)
        }

        val result = JSONObject().apply {
            put("path", dir.absolutePath)
            put("parent", dir.parent)
            put("items", arr)
            put("itemCount", files.size)
        }

        return newFixedLengthResponse(Status.OK, "application/json", result.toString())
    }

    private fun handleNetworkBrowse(netPath: String): KtorResult {
        // net:<uuid>/<path>
        val prefix = "net:"
        val idEnd = netPath.indexOf('/', prefix.length).takeIf { it != -1 } ?: netPath.length
        val shareId = netPath.substring(prefix.length, idEnd)
        val share = resolveShare(shareId) ?: return jsonResponse(Status.NOT_FOUND, "error" to "Share not found")
        
        val remotePath = if (idEnd < netPath.length) netPath.substring(idEnd + 1) else ""
        Log.d(TAG, "handleNetworkBrowse: shareId=$shareId, remotePath='$remotePath'")
        
        val files = try {
            when (share.type) {
                za.kilowatch.ultimatefilemanager.network.ShareType.SMB -> za.kilowatch.ultimatefilemanager.network.SmbShareClient.listFiles(share, remotePath)
                za.kilowatch.ultimatefilemanager.network.ShareType.FTP -> za.kilowatch.ultimatefilemanager.network.FtpShareClient.listFiles(share, remotePath)
                za.kilowatch.ultimatefilemanager.network.ShareType.TV -> za.kilowatch.ultimatefilemanager.network.TvShareClient.listFiles(share, remotePath)
                za.kilowatch.ultimatefilemanager.network.ShareType.SFTP, za.kilowatch.ultimatefilemanager.network.ShareType.SCP -> za.kilowatch.ultimatefilemanager.network.SshShareClient.listFiles(share, remotePath)
                za.kilowatch.ultimatefilemanager.network.ShareType.ONEDRIVE -> runBlocking { za.kilowatch.ultimatefilemanager.network.OnedriveShareClient.listFiles(share, remotePath) }
                za.kilowatch.ultimatefilemanager.network.ShareType.GOOGLE_DRIVE -> runBlocking { za.kilowatch.ultimatefilemanager.network.GoogleDriveShareClient.listFiles(share, remotePath) }
                za.kilowatch.ultimatefilemanager.network.ShareType.DROPBOX -> runBlocking { za.kilowatch.ultimatefilemanager.network.DropboxShareClient.listFiles(share, remotePath) }
                za.kilowatch.ultimatefilemanager.network.ShareType.AWS_S3, za.kilowatch.ultimatefilemanager.network.ShareType.IDRIVE_E2 -> runBlocking { za.kilowatch.ultimatefilemanager.network.S3ShareClient.listFiles(share, remotePath) }
                za.kilowatch.ultimatefilemanager.network.ShareType.WEBDAV -> runBlocking { za.kilowatch.ultimatefilemanager.network.WebDavShareClient.listFiles(share, remotePath) }
                za.kilowatch.ultimatefilemanager.network.ShareType.NFS -> za.kilowatch.ultimatefilemanager.network.NfsShareClient.listFiles(share, remotePath)
                za.kilowatch.ultimatefilemanager.network.ShareType.DLNA -> za.kilowatch.ultimatefilemanager.network.DlnaShareClient.listFiles(share, remotePath)
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleNetworkBrowse: failed to list share $shareId path '$remotePath'", e)
            return jsonResponse(Status.INTERNAL_ERROR, "error" to "Failed to list network share: ${e.message}")
        }
        
        Log.d(TAG, "handleNetworkBrowse: found ${files.size} items for '$remotePath'")
        
        val arr = JSONArray()
        for (file in files.sortedWith(compareBy<za.kilowatch.ultimatefilemanager.network.NetworkFile> { !it.isDirectory }.thenBy { it.name.lowercase() })) {
            val obj = JSONObject().apply {
                put("name", file.name)
                val fullPath = if (remotePath.isEmpty()) file.name else "$remotePath/${file.name}"
                put("path", "net:${share.id}/$fullPath")
                put("isDirectory", file.isDirectory)
                put("size", file.size)
                put("sizeFormatted", if (file.isDirectory) "" else Formatter.formatFileSize(context, file.size))
                put("lastModified", file.lastModified)
                put("canRead", true)
                put("canWrite", !share.readOnly)
            }
            arr.put(obj)
        }

        val result = JSONObject().apply {
            put("path", netPath)
            // compute parent
            val parentPath = if (remotePath.isEmpty()) {
                null
            } else {
                val lastSlash = remotePath.lastIndexOf('/')
                if (lastSlash > 0) "net:${share.id}/${remotePath.substring(0, lastSlash)}" else "net:${share.id}/"
            }
            if (parentPath != null) {
                put("parent", parentPath)
            }
            put("items", arr)
            put("itemCount", files.size)
        }
        return newFixedLengthResponse(Status.OK, "application/json", result.toString())
    }

    /**
     * Calculates size of folder recursively.
     */
    private fun calculateFolderSize(dir: File): Long {
        return try {
            dir.walkTopDown()
                .filter { it.isFile }
                .sumOf { it.length() }
        } catch (_: Exception) {
            0L
        }
    }

    // ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ Mkdir ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬

    private fun handleMkdir(session: KtorSession): KtorResult {
        val body = mutableMapOf<String, String>()
        session.parseBody(body)
        val json = JSONObject(body["postData"] ?: "{}")
        val path = json.optString("path", "")
        val name = json.optString("name", "")

        if (path.isEmpty() || name.isEmpty()) {
            return jsonResponse(Status.BAD_REQUEST, "error" to "Missing path or name")
        }

        if (path.startsWith("net:")) {
            return handleNetworkMkdir(path, name)
        }

        val newDir = File(path, name)
        if (!isPathAllowed(newDir.absolutePath)) {
            return jsonResponse(Status.FORBIDDEN, "error" to "Permission denied: restricted path")
        }
        if (newDir.exists()) {
            return jsonResponse(Status.CONFLICT, "error" to "Folder already exists")
        }

        return if (newDir.mkdirs()) {
            notifyIndexing(newDir.absolutePath, isFolder = true)
            jsonResponse(Status.OK, "success" to true, "path" to newDir.absolutePath)
        } else {
            jsonResponse(Status.FORBIDDEN, "error" to "Permission denied: cannot create folder here")
        }
    }

    private fun handleNetworkMkdir(netPath: String, name: String): KtorResult {
        val prefix = "net:"
        val idEnd = netPath.indexOf('/', prefix.length).takeIf { it != -1 } ?: netPath.length
        val shareId = netPath.substring(prefix.length, idEnd)
        val share = resolveShare(shareId) ?: return jsonResponse(Status.NOT_FOUND, "error" to "Share not found")
        
        if (share.readOnly) return jsonResponse(Status.FORBIDDEN, "error" to "Share is read-only")
        
        val remotePath = if (idEnd < netPath.length) netPath.substring(idEnd + 1) else ""
        val targetPath = if (remotePath.isEmpty()) name else "$remotePath/$name"
        
        return try {
            val ok = when (share.type) {
                za.kilowatch.ultimatefilemanager.network.ShareType.SMB -> {
                    za.kilowatch.ultimatefilemanager.network.SmbShareClient.mkdir(share, targetPath)
                    true
                }
                za.kilowatch.ultimatefilemanager.network.ShareType.FTP -> {
                    za.kilowatch.ultimatefilemanager.network.FtpShareClient.mkdir(share, targetPath)
                    true
                }
                za.kilowatch.ultimatefilemanager.network.ShareType.TV -> {
                    za.kilowatch.ultimatefilemanager.network.TvShareClient.mkdir(share, targetPath)
                    true
                }
                za.kilowatch.ultimatefilemanager.network.ShareType.SFTP, za.kilowatch.ultimatefilemanager.network.ShareType.SCP -> {
                    za.kilowatch.ultimatefilemanager.network.SshShareClient.mkdir(share, targetPath)
                    true
                }
                za.kilowatch.ultimatefilemanager.network.ShareType.ONEDRIVE -> {
                    runBlocking { za.kilowatch.ultimatefilemanager.network.OnedriveShareClient.mkdir(share, targetPath) }
                    true
                }
                za.kilowatch.ultimatefilemanager.network.ShareType.GOOGLE_DRIVE -> {
                    runBlocking { za.kilowatch.ultimatefilemanager.network.GoogleDriveShareClient.mkdir(share, targetPath) }
                    true
                }
                za.kilowatch.ultimatefilemanager.network.ShareType.DROPBOX -> {
                    runBlocking { za.kilowatch.ultimatefilemanager.network.DropboxShareClient.mkdir(share, targetPath) }
                    true
                }
                za.kilowatch.ultimatefilemanager.network.ShareType.AWS_S3, za.kilowatch.ultimatefilemanager.network.ShareType.IDRIVE_E2 -> {
                    runBlocking { za.kilowatch.ultimatefilemanager.network.S3ShareClient.mkdir(share, targetPath) }
                    true
                }
                za.kilowatch.ultimatefilemanager.network.ShareType.WEBDAV -> {
                    runBlocking { za.kilowatch.ultimatefilemanager.network.WebDavShareClient.mkdir(share, targetPath) }
                    true
                }
                za.kilowatch.ultimatefilemanager.network.ShareType.NFS -> {
                    za.kilowatch.ultimatefilemanager.network.NfsShareClient.mkdir(share, targetPath)
                    true
                }
                za.kilowatch.ultimatefilemanager.network.ShareType.DLNA -> {
                    throw UnsupportedOperationException("DLNA is read-only")
                }
            }
            if (ok) {
                jsonResponse(Status.OK, "success" to true, "path" to "net:${share.id}/$targetPath")
            } else {
                jsonResponse(Status.INTERNAL_ERROR, "error" to "Failed to create directory")
            }
        } catch (e: Exception) {
            jsonResponse(Status.INTERNAL_ERROR, "error" to "Exception: ${e.message}")
        }
    }

    // ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ Delete ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬

    private fun handleDelete(session: KtorSession): KtorResult {
        val body = mutableMapOf<String, String>()
        session.parseBody(body)
        val json = JSONObject(body["postData"] ?: "{}")
        val path = json.optString("path", "")

        if (path.isEmpty()) {
            return jsonResponse(Status.BAD_REQUEST, "error" to "Missing path")
        }

        if (path.startsWith("net:")) {
            return handleNetworkDelete(path)
        }

        val file = File(path)
        if (!isPathAllowed(file.absolutePath)) {
            return jsonResponse(Status.FORBIDDEN, "error" to "Permission denied: restricted path")
        }
        if (!file.exists()) {
            return jsonResponse(Status.NOT_FOUND, "error" to "File not found")
        }

        val isFolder = file.isDirectory
        val deleted = if (isFolder) file.deleteRecursively() else file.delete()

        return if (deleted) {
            notifyIndexing(path, isDelete = true, isFolder = isFolder)
            jsonResponse(Status.OK, "success" to true)
        } else {
            jsonResponse(Status.FORBIDDEN, "error" to "Permission denied: cannot delete this item")
        }
    }

    private fun handleNetworkDelete(netPath: String): KtorResult {
        val prefix = "net:"
        val idEnd = netPath.indexOf('/', prefix.length).takeIf { it != -1 } ?: netPath.length
        val shareId = netPath.substring(prefix.length, idEnd)
        val share = resolveShare(shareId) ?: return jsonResponse(Status.NOT_FOUND, "error" to "Share not found")
        
        if (share.readOnly) return jsonResponse(Status.FORBIDDEN, "error" to "Share is read-only")
        
        val remotePath = if (idEnd < netPath.length) netPath.substring(idEnd + 1) else ""
        if (remotePath.isEmpty()) return jsonResponse(Status.FORBIDDEN, "error" to "Cannot delete share root")
        
        return try {
            runBlocking(Dispatchers.IO) {
                // Note: Our clients currently require knowing if it's a directory or file. 
                // In a real VFS, we'd stat it first, but for simplicity, we try deleting as file, then as dir.
                val ok = when (share.type) {
                    za.kilowatch.ultimatefilemanager.network.ShareType.SMB -> {
                        try { za.kilowatch.ultimatefilemanager.network.SmbShareClient.deleteFile(share, remotePath); true }
                        catch (e: Exception) { za.kilowatch.ultimatefilemanager.network.SmbShareClient.deleteDir(share, remotePath); true }
                    }
                    za.kilowatch.ultimatefilemanager.network.ShareType.FTP -> {
                        try { za.kilowatch.ultimatefilemanager.network.FtpShareClient.deleteFile(share, remotePath); true }
                        catch (e: Exception) { za.kilowatch.ultimatefilemanager.network.FtpShareClient.deleteDir(share, remotePath); true }
                    }
                    za.kilowatch.ultimatefilemanager.network.ShareType.TV -> {
                        try { za.kilowatch.ultimatefilemanager.network.TvShareClient.deleteFile(share, remotePath); true }
                        catch (e: Exception) { za.kilowatch.ultimatefilemanager.network.TvShareClient.deleteDir(share, remotePath); true }
                    }
                    za.kilowatch.ultimatefilemanager.network.ShareType.SFTP, za.kilowatch.ultimatefilemanager.network.ShareType.SCP -> {
                        // SshShareClient.delete handles both file and dir (non-recursive here matching existing logic)
                        za.kilowatch.ultimatefilemanager.network.SshShareClient.delete(share, remotePath, false)
                        true
                    }
                    za.kilowatch.ultimatefilemanager.network.ShareType.ONEDRIVE -> {
                        za.kilowatch.ultimatefilemanager.network.OnedriveShareClient.deleteFile(share, remotePath)
                        true
                    }
                    za.kilowatch.ultimatefilemanager.network.ShareType.GOOGLE_DRIVE -> {
                        za.kilowatch.ultimatefilemanager.network.GoogleDriveShareClient.deleteFile(share, remotePath)
                        true
                    }
                    za.kilowatch.ultimatefilemanager.network.ShareType.DROPBOX -> {
                        za.kilowatch.ultimatefilemanager.network.DropboxShareClient.deleteFile(share, remotePath)
                        true
                    }
                    za.kilowatch.ultimatefilemanager.network.ShareType.AWS_S3, za.kilowatch.ultimatefilemanager.network.ShareType.IDRIVE_E2 -> {
                        za.kilowatch.ultimatefilemanager.network.S3ShareClient.deleteFile(share, remotePath)
                        true
                    }
                    za.kilowatch.ultimatefilemanager.network.ShareType.WEBDAV -> {
                        za.kilowatch.ultimatefilemanager.network.WebDavShareClient.deleteFile(share, remotePath)
                        true
                    }
                    za.kilowatch.ultimatefilemanager.network.ShareType.NFS -> {
                        try { za.kilowatch.ultimatefilemanager.network.NfsShareClient.deleteFile(share, remotePath); true }
                        catch (e: Exception) { za.kilowatch.ultimatefilemanager.network.NfsShareClient.deleteDir(share, remotePath); true }
                    }
                    za.kilowatch.ultimatefilemanager.network.ShareType.DLNA -> {
                        throw UnsupportedOperationException("DLNA is read-only")
                    }
                }
                if (ok) {
                    jsonResponse(Status.OK, "success" to true)
                } else {
                    jsonResponse(Status.INTERNAL_ERROR, "error" to "Failed to delete item")
                }
            }
        } catch (e: Exception) {
            jsonResponse(Status.INTERNAL_ERROR, "error" to "Exception: ${e.message}")
        }
    }

    // ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ Rename ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬

    private fun handleRename(session: KtorSession): KtorResult {
        val body = mutableMapOf<String, String>()
        session.parseBody(body)
        val json = JSONObject(body["postData"] ?: "{}")
        val path = json.optString("path", "")
        val newName = json.optString("newName", "")

        if (path.isEmpty() || newName.isEmpty()) {
            return jsonResponse(Status.BAD_REQUEST, "error" to "Missing path or newName")
        }

        if (path.startsWith("net:")) {
            return handleNetworkRename(path, newName)
        }

        val file = File(path)
        if (!isPathAllowed(file.absolutePath)) {
            return jsonResponse(Status.FORBIDDEN, "error" to "Permission denied: restricted path")
        }
        if (!file.exists()) {
            return jsonResponse(Status.NOT_FOUND, "error" to "Target not found")
        }

        val newFile = File(file.parentFile, newName)
        if (!isPathAllowed(newFile.absolutePath)) {
            return jsonResponse(Status.FORBIDDEN, "error" to "Permission denied: restricted path")
        }
        if (newFile.exists()) {
            return jsonResponse(Status.CONFLICT, "error" to "A file with that name already exists")
        }

        val isFolder = file.isDirectory
        return if (file.renameTo(newFile)) {
            notifyIndexing(path, isDelete = true, isFolder = isFolder)
            notifyIndexing(newFile.absolutePath, isFolder = isFolder)
            jsonResponse(Status.OK, "success" to true)
        } else {
            jsonResponse(Status.FORBIDDEN, "error" to "Permission denied: cannot rename this item")
        }
    }

    private fun handleNetworkRename(netPath: String, newName: String): KtorResult {
        val prefix = "net:"
        val idEnd = netPath.indexOf('/', prefix.length).takeIf { it != -1 } ?: netPath.length
        val shareId = netPath.substring(prefix.length, idEnd)
        val share = resolveShare(shareId) ?: return jsonResponse(Status.NOT_FOUND, "error" to "Share not found")
        
        if (share.readOnly) return jsonResponse(Status.FORBIDDEN, "error" to "Share is read-only")
        
        val remotePath = if (idEnd < netPath.length) netPath.substring(idEnd + 1) else ""
        if (remotePath.isEmpty()) return jsonResponse(Status.FORBIDDEN, "error" to "Cannot delete share root")
        
        val lastSlash = remotePath.lastIndexOf('/')
        val parentPath = if (lastSlash > 0) remotePath.substring(0, lastSlash) else ""
        val newRemotePath = if (parentPath.isEmpty()) newName else "$parentPath/$newName"

        return try {
            val ok = when (share.type) {
                za.kilowatch.ultimatefilemanager.network.ShareType.SMB -> {
                    za.kilowatch.ultimatefilemanager.network.SmbShareClient.rename(share, remotePath, newRemotePath)
                    true
                }
                za.kilowatch.ultimatefilemanager.network.ShareType.FTP -> {
                    za.kilowatch.ultimatefilemanager.network.FtpShareClient.rename(share, remotePath, newRemotePath)
                    true
                }
                za.kilowatch.ultimatefilemanager.network.ShareType.TV -> {
                    za.kilowatch.ultimatefilemanager.network.TvShareClient.rename(share, remotePath, newRemotePath)
                    true
                }
                za.kilowatch.ultimatefilemanager.network.ShareType.SFTP, za.kilowatch.ultimatefilemanager.network.ShareType.SCP -> {
                    za.kilowatch.ultimatefilemanager.network.SshShareClient.rename(share, remotePath, newRemotePath)
                    true
                }
                za.kilowatch.ultimatefilemanager.network.ShareType.ONEDRIVE -> {
                    runBlocking { za.kilowatch.ultimatefilemanager.network.OnedriveShareClient.rename(share, remotePath, newRemotePath) }
                    true
                }
                za.kilowatch.ultimatefilemanager.network.ShareType.GOOGLE_DRIVE -> {
                    runBlocking { za.kilowatch.ultimatefilemanager.network.GoogleDriveShareClient.rename(share, remotePath, newRemotePath) }
                    true
                }
                za.kilowatch.ultimatefilemanager.network.ShareType.DROPBOX -> {
                    runBlocking { za.kilowatch.ultimatefilemanager.network.DropboxShareClient.rename(share, remotePath, newRemotePath) }
                    true
                }
                za.kilowatch.ultimatefilemanager.network.ShareType.AWS_S3, za.kilowatch.ultimatefilemanager.network.ShareType.IDRIVE_E2 -> {
                    runBlocking { za.kilowatch.ultimatefilemanager.network.S3ShareClient.rename(share, remotePath, newRemotePath) }
                    true
                }
                za.kilowatch.ultimatefilemanager.network.ShareType.WEBDAV -> {
                    runBlocking { za.kilowatch.ultimatefilemanager.network.WebDavShareClient.rename(share, remotePath, newRemotePath) }
                    true
                }
                za.kilowatch.ultimatefilemanager.network.ShareType.NFS -> {
                    za.kilowatch.ultimatefilemanager.network.NfsShareClient.rename(share, remotePath, newRemotePath)
                    true
                }
                za.kilowatch.ultimatefilemanager.network.ShareType.DLNA -> {
                    throw UnsupportedOperationException("DLNA is read-only")
                }
            }
            if (ok) {
                jsonResponse(Status.OK, "success" to true)
            } else {
                jsonResponse(Status.INTERNAL_ERROR, "error" to "Failed to rename item")
            }
        } catch (e: Exception) {
            jsonResponse(Status.INTERNAL_ERROR, "error" to "Exception: ${e.message}")
        }
    }

    // ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ Copy & Move ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬

    private fun handleCopy(session: KtorSession): KtorResult {
        return handleFileTransfer(session, isMove = false)
    }

    private fun handleMove(session: KtorSession): KtorResult {
        return handleFileTransfer(session, isMove = true)
    }

    private fun handleFileTransfer(session: KtorSession, isMove: Boolean): KtorResult = runBlocking(Dispatchers.IO) {
        val body = mutableMapOf<String, String>()
        session.parseBody(body)
        val json = JSONObject(body["postData"] ?: "{}")
        
        val destinationPath = json.optString("destination", "")
        if (destinationPath.isEmpty()) {
            return@runBlocking jsonResponse(Status.BAD_REQUEST, "error" to "Missing destination path")
        }
        
        val isNetDest = destinationPath.startsWith("net:")
        var destShare: za.kilowatch.ultimatefilemanager.network.NetworkShare? = null
        var destRemotePath = ""
        if (isNetDest) {
            val prefix = "net:"
            val idEnd = destinationPath.indexOf('/', prefix.length).takeIf { it != -1 } ?: destinationPath.length
            val shareId = destinationPath.substring(prefix.length, idEnd)
            destShare = resolveShare(shareId) ?: return@runBlocking jsonResponse(Status.NOT_FOUND, "error" to "Target share not found")
            if (destShare.readOnly) return@runBlocking jsonResponse(Status.FORBIDDEN, "error" to "Target share is read-only")
            destRemotePath = if (idEnd < destinationPath.length) destinationPath.substring(idEnd + 1) else ""
        } else {
            val destDir = File(destinationPath)
            if (!isPathAllowed(destDir.absolutePath)) {
                return@runBlocking jsonResponse(Status.FORBIDDEN, "error" to "Permission denied: restricted destination path")
            }
            if (!destDir.exists() || !destDir.isDirectory) {
                return@runBlocking jsonResponse(Status.NOT_FOUND, "error" to "Destination directory not found")
            }
            if (!destDir.canWrite()) {
                return@runBlocking jsonResponse(Status.FORBIDDEN, "error" to "Permission denied: cannot write to destination")
            }
        }

        val sourcesArray = json.optJSONArray("sources")
        if (sourcesArray == null || sourcesArray.length() == 0) {
            return@runBlocking jsonResponse(Status.BAD_REQUEST, "error" to "No source files specified")
        }

        var successCount = 0
        var failCount = 0

        for (i in 0 until sourcesArray.length()) {
            val srcPath = sourcesArray.getString(i)
            val isNetSrc = srcPath.startsWith("net:")
            
            try {
                if (!isNetSrc && !isNetDest) {
                    // Local -> Local
                    val srcFile = File(srcPath)
                    if (!isPathAllowed(srcFile.absolutePath) || !srcFile.exists()) { failCount++; continue }
                    val targetFile = File(destinationPath, srcFile.name)
                    if (!isPathAllowed(targetFile.absolutePath) || targetFile.exists()) { failCount++; continue }
                    
                    if (isMove) {
                        val isFolder = srcFile.isDirectory
                        if (srcFile.renameTo(targetFile)) {
                            notifyIndexing(srcPath, isDelete = true, isFolder = isFolder)
                            notifyIndexing(targetFile.absolutePath, isFolder = isFolder)
                            successCount++
                        } else {
                            if (isFolder) {
                                srcFile.copyRecursively(targetFile, true)
                                srcFile.deleteRecursively()
                                notifyIndexing(srcPath, isDelete = true, isFolder = true)
                                notifyIndexing(targetFile.absolutePath, isFolder = true)
                            } else {
                                srcFile.copyTo(targetFile, true)
                                srcFile.delete()
                                notifyIndexing(srcPath, isDelete = true, isFolder = false)
                                notifyIndexing(targetFile.absolutePath, isFolder = false)
                            }
                            successCount++
                        }
                    } else {
                        val isFolder = srcFile.isDirectory
                        if (isFolder) srcFile.copyRecursively(targetFile, true) else srcFile.copyTo(targetFile, true)
                        notifyIndexing(targetFile.absolutePath, isFolder = isFolder)
                        successCount++
                    }
                } else if (isNetSrc && isNetDest) {
                    // Net -> Net
                    val prefix = "net:"
                    val idEnd = srcPath.indexOf('/', prefix.length).takeIf { it != -1 } ?: srcPath.length
                    val srcShareId = srcPath.substring(prefix.length, idEnd)
                    val srcShare = resolveShare(srcShareId) ?: throw Exception("Src share missing")
                    val srcRemote = if (idEnd < srcPath.length) srcPath.substring(idEnd + 1) else ""
                    val fileName = srcRemote.substringAfterLast('/')
                    val destRemote = if (destRemotePath.isEmpty()) fileName else "$destRemotePath/$fileName"
                    
                    if (isMove && srcShareId == destShare!!.id) {
                        when (destShare!!.type) {
                            za.kilowatch.ultimatefilemanager.network.ShareType.SMB -> za.kilowatch.ultimatefilemanager.network.SmbShareClient.rename(destShare, srcRemote, destRemote)
                            za.kilowatch.ultimatefilemanager.network.ShareType.FTP -> za.kilowatch.ultimatefilemanager.network.FtpShareClient.rename(destShare, srcRemote, destRemote)
                            za.kilowatch.ultimatefilemanager.network.ShareType.TV -> za.kilowatch.ultimatefilemanager.network.TvShareClient.rename(destShare, srcRemote, destRemote)
                            za.kilowatch.ultimatefilemanager.network.ShareType.SFTP, za.kilowatch.ultimatefilemanager.network.ShareType.SCP -> za.kilowatch.ultimatefilemanager.network.SshShareClient.rename(destShare, srcRemote, destRemote)
                            za.kilowatch.ultimatefilemanager.network.ShareType.ONEDRIVE -> runBlocking { za.kilowatch.ultimatefilemanager.network.OnedriveShareClient.rename(destShare, srcRemote, destRemote) }
                            za.kilowatch.ultimatefilemanager.network.ShareType.GOOGLE_DRIVE -> runBlocking { za.kilowatch.ultimatefilemanager.network.GoogleDriveShareClient.rename(destShare, srcRemote, destRemote) }
                            za.kilowatch.ultimatefilemanager.network.ShareType.DROPBOX -> runBlocking { za.kilowatch.ultimatefilemanager.network.DropboxShareClient.rename(destShare, srcRemote, destRemote) }
                            za.kilowatch.ultimatefilemanager.network.ShareType.AWS_S3, za.kilowatch.ultimatefilemanager.network.ShareType.IDRIVE_E2 -> runBlocking { za.kilowatch.ultimatefilemanager.network.S3ShareClient.rename(destShare, srcRemote, destRemote) }
                             za.kilowatch.ultimatefilemanager.network.ShareType.WEBDAV -> runBlocking { za.kilowatch.ultimatefilemanager.network.WebDavShareClient.rename(destShare, srcRemote, destRemote) }
                             za.kilowatch.ultimatefilemanager.network.ShareType.NFS -> za.kilowatch.ultimatefilemanager.network.NfsShareClient.rename(destShare, srcRemote, destRemote)
                             za.kilowatch.ultimatefilemanager.network.ShareType.DLNA -> throw UnsupportedOperationException("DLNA is read-only")
                        }
                    } else {
                         val inStream = when(srcShare.type) {
                             za.kilowatch.ultimatefilemanager.network.ShareType.SMB -> za.kilowatch.ultimatefilemanager.network.SmbShareClient.openInputStream(srcShare, srcRemote)
                             za.kilowatch.ultimatefilemanager.network.ShareType.FTP -> za.kilowatch.ultimatefilemanager.network.FtpShareClient.openInputStream(srcShare, srcRemote)
                             za.kilowatch.ultimatefilemanager.network.ShareType.TV -> za.kilowatch.ultimatefilemanager.network.TvShareClient.openInputStream(srcShare, srcRemote)
                             za.kilowatch.ultimatefilemanager.network.ShareType.SFTP, za.kilowatch.ultimatefilemanager.network.ShareType.SCP -> za.kilowatch.ultimatefilemanager.network.SshShareClient.openInputStream(srcShare, srcRemote)
                             za.kilowatch.ultimatefilemanager.network.ShareType.ONEDRIVE -> runBlocking { za.kilowatch.ultimatefilemanager.network.OnedriveShareClient.openInputStream(srcShare, srcRemote).first }
                             za.kilowatch.ultimatefilemanager.network.ShareType.GOOGLE_DRIVE -> runBlocking { za.kilowatch.ultimatefilemanager.network.GoogleDriveShareClient.openInputStream(srcShare, srcRemote).first }
                             za.kilowatch.ultimatefilemanager.network.ShareType.DROPBOX -> runBlocking { za.kilowatch.ultimatefilemanager.network.DropboxShareClient.openInputStream(srcShare, srcRemote).first }
                             za.kilowatch.ultimatefilemanager.network.ShareType.AWS_S3, za.kilowatch.ultimatefilemanager.network.ShareType.IDRIVE_E2 -> runBlocking { za.kilowatch.ultimatefilemanager.network.S3ShareClient.openInputStream(srcShare, srcRemote).first }
                               za.kilowatch.ultimatefilemanager.network.ShareType.WEBDAV -> runBlocking { za.kilowatch.ultimatefilemanager.network.WebDavShareClient.openInputStream(srcShare, srcRemote).first }
                              za.kilowatch.ultimatefilemanager.network.ShareType.NFS -> za.kilowatch.ultimatefilemanager.network.NfsShareClient.openInputStream(srcShare, srcRemote)
                              za.kilowatch.ultimatefilemanager.network.ShareType.DLNA -> za.kilowatch.ultimatefilemanager.network.DlnaShareClient.openInputStream(srcShare, srcRemote)
                         }
                         if (destShare!!.type == za.kilowatch.ultimatefilemanager.network.ShareType.TV) {
                             // TV requires explicit Content-Length; download to temp file first, then upload
                             val tempFile = java.io.File.createTempFile("ufm_tvbuf_", ".tmp")
                             try {
                                 inStream.use { inp -> tempFile.outputStream().use { out -> za.kilowatch.ultimatefilemanager.util.CopyHelper.copy(inp, out) } }
                                 tempFile.inputStream().use { inp ->
                                     za.kilowatch.ultimatefilemanager.network.TvShareClient.uploadStream(destShare, destRemote, inp, tempFile.length())
                                 }
                             } finally { tempFile.delete() }
                         } else {
                             val outStream = when(destShare!!.type) {
                                 za.kilowatch.ultimatefilemanager.network.ShareType.SMB -> za.kilowatch.ultimatefilemanager.network.SmbShareClient.openOutputStream(destShare, destRemote)
                                 za.kilowatch.ultimatefilemanager.network.ShareType.FTP -> za.kilowatch.ultimatefilemanager.network.FtpShareClient.openOutputStream(destShare, destRemote)
                                 za.kilowatch.ultimatefilemanager.network.ShareType.SFTP, za.kilowatch.ultimatefilemanager.network.ShareType.SCP -> za.kilowatch.ultimatefilemanager.network.SshShareClient.openOutputStream(destShare, destRemote)
                                 za.kilowatch.ultimatefilemanager.network.ShareType.ONEDRIVE -> runBlocking { za.kilowatch.ultimatefilemanager.network.OnedriveShareClient.openOutputStream(destShare, destRemote) }
                                 za.kilowatch.ultimatefilemanager.network.ShareType.GOOGLE_DRIVE -> runBlocking { za.kilowatch.ultimatefilemanager.network.GoogleDriveShareClient.openOutputStream(destShare, destRemote) }
                                 za.kilowatch.ultimatefilemanager.network.ShareType.DROPBOX -> runBlocking { za.kilowatch.ultimatefilemanager.network.DropboxShareClient.openOutputStream(destShare, destRemote) }
                                 za.kilowatch.ultimatefilemanager.network.ShareType.AWS_S3, za.kilowatch.ultimatefilemanager.network.ShareType.IDRIVE_E2 -> runBlocking { za.kilowatch.ultimatefilemanager.network.S3ShareClient.openOutputStream(destShare, destRemote) }
                                 za.kilowatch.ultimatefilemanager.network.ShareType.WEBDAV -> runBlocking { za.kilowatch.ultimatefilemanager.network.WebDavShareClient.openOutputStream(destShare, destRemote) }
                                  za.kilowatch.ultimatefilemanager.network.ShareType.NFS -> za.kilowatch.ultimatefilemanager.network.NfsShareClient.openOutputStream(destShare, destRemote)
                                  else -> throw Exception("Unhandled share type")
                             }
                             inStream.use { inp -> outStream.use { out -> za.kilowatch.ultimatefilemanager.util.CopyHelper.copy(inp, out) } }
                         }
                         if (isMove) {
                              try {
                                  when(srcShare.type) {
                                      za.kilowatch.ultimatefilemanager.network.ShareType.SMB -> za.kilowatch.ultimatefilemanager.network.SmbShareClient.deleteFile(srcShare, srcRemote)
                                      za.kilowatch.ultimatefilemanager.network.ShareType.FTP -> za.kilowatch.ultimatefilemanager.network.FtpShareClient.deleteFile(srcShare, srcRemote)
                                      za.kilowatch.ultimatefilemanager.network.ShareType.TV -> za.kilowatch.ultimatefilemanager.network.TvShareClient.deleteFile(srcShare, srcRemote)
                                      za.kilowatch.ultimatefilemanager.network.ShareType.SFTP, za.kilowatch.ultimatefilemanager.network.ShareType.SCP -> za.kilowatch.ultimatefilemanager.network.SshShareClient.delete(srcShare, srcRemote, false)
                                      za.kilowatch.ultimatefilemanager.network.ShareType.ONEDRIVE -> runBlocking { za.kilowatch.ultimatefilemanager.network.OnedriveShareClient.deleteFile(srcShare, srcRemote) }
                                      za.kilowatch.ultimatefilemanager.network.ShareType.GOOGLE_DRIVE -> runBlocking { za.kilowatch.ultimatefilemanager.network.GoogleDriveShareClient.deleteFile(srcShare, srcRemote) }
                                      za.kilowatch.ultimatefilemanager.network.ShareType.DROPBOX -> runBlocking { za.kilowatch.ultimatefilemanager.network.DropboxShareClient.deleteFile(srcShare, srcRemote) }
                                      za.kilowatch.ultimatefilemanager.network.ShareType.AWS_S3, za.kilowatch.ultimatefilemanager.network.ShareType.IDRIVE_E2 -> runBlocking { za.kilowatch.ultimatefilemanager.network.S3ShareClient.deleteFile(srcShare, srcRemote) }
                                      za.kilowatch.ultimatefilemanager.network.ShareType.WEBDAV -> runBlocking { za.kilowatch.ultimatefilemanager.network.WebDavShareClient.deleteFile(srcShare, srcRemote) }
                                      za.kilowatch.ultimatefilemanager.network.ShareType.NFS -> za.kilowatch.ultimatefilemanager.network.NfsShareClient.deleteFile(srcShare, srcRemote)
                                      za.kilowatch.ultimatefilemanager.network.ShareType.DLNA -> throw UnsupportedOperationException("DLNA is read-only")
                                  }
                              } catch(e: Exception) {
                                  when(srcShare.type) {
                                      za.kilowatch.ultimatefilemanager.network.ShareType.SMB -> za.kilowatch.ultimatefilemanager.network.SmbShareClient.deleteDir(srcShare, srcRemote)
                                      za.kilowatch.ultimatefilemanager.network.ShareType.FTP -> za.kilowatch.ultimatefilemanager.network.FtpShareClient.deleteDir(srcShare, srcRemote)
                                      za.kilowatch.ultimatefilemanager.network.ShareType.TV -> za.kilowatch.ultimatefilemanager.network.TvShareClient.deleteDir(srcShare, srcRemote)
                                      za.kilowatch.ultimatefilemanager.network.ShareType.SFTP, za.kilowatch.ultimatefilemanager.network.ShareType.SCP -> za.kilowatch.ultimatefilemanager.network.SshShareClient.delete(srcShare, srcRemote, true)
                                      za.kilowatch.ultimatefilemanager.network.ShareType.ONEDRIVE -> runBlocking { za.kilowatch.ultimatefilemanager.network.OnedriveShareClient.deleteFile(srcShare, srcRemote) }
                                      za.kilowatch.ultimatefilemanager.network.ShareType.GOOGLE_DRIVE -> runBlocking { za.kilowatch.ultimatefilemanager.network.GoogleDriveShareClient.deleteFile(srcShare, srcRemote) }
                                      za.kilowatch.ultimatefilemanager.network.ShareType.DROPBOX -> runBlocking { za.kilowatch.ultimatefilemanager.network.DropboxShareClient.deleteFile(srcShare, srcRemote) }
                                      za.kilowatch.ultimatefilemanager.network.ShareType.AWS_S3, za.kilowatch.ultimatefilemanager.network.ShareType.IDRIVE_E2 -> runBlocking { za.kilowatch.ultimatefilemanager.network.S3ShareClient.deleteFile(srcShare, srcRemote) }
                                      za.kilowatch.ultimatefilemanager.network.ShareType.WEBDAV -> runBlocking { za.kilowatch.ultimatefilemanager.network.WebDavShareClient.deleteFile(srcShare, srcRemote) }
                                      za.kilowatch.ultimatefilemanager.network.ShareType.NFS -> za.kilowatch.ultimatefilemanager.network.NfsShareClient.deleteDir(srcShare, srcRemote)
                                      za.kilowatch.ultimatefilemanager.network.ShareType.DLNA -> throw UnsupportedOperationException("DLNA is read-only")
                                  }
                              }
                          }
                     }
                     successCount++
                } else if (!isNetSrc && isNetDest) {
                    // Local -> Net
                    val srcFile = File(srcPath)
                    if (!isPathAllowed(srcFile.absolutePath) || !srcFile.exists()) { failCount++; continue }
                    val destRemote = if (destRemotePath.isEmpty()) srcFile.name else "$destRemotePath/${srcFile.name}"
                    
                    if (srcFile.isDirectory) throw Exception("Recursive local -> net not supported natively yet")
                    
                    if (destShare!!.type == za.kilowatch.ultimatefilemanager.network.ShareType.TV) {
                        java.io.FileInputStream(srcFile).use { inp ->
                            za.kilowatch.ultimatefilemanager.network.TvShareClient.uploadStream(destShare, destRemote, inp, srcFile.length())
                        }
                    } else {
                        val outStream = when(destShare!!.type) {
                             za.kilowatch.ultimatefilemanager.network.ShareType.SMB -> za.kilowatch.ultimatefilemanager.network.SmbShareClient.openOutputStream(destShare, destRemote)
                             za.kilowatch.ultimatefilemanager.network.ShareType.FTP -> za.kilowatch.ultimatefilemanager.network.FtpShareClient.openOutputStream(destShare, destRemote)
                             za.kilowatch.ultimatefilemanager.network.ShareType.SFTP, za.kilowatch.ultimatefilemanager.network.ShareType.SCP -> za.kilowatch.ultimatefilemanager.network.SshShareClient.openOutputStream(destShare, destRemote)
                             za.kilowatch.ultimatefilemanager.network.ShareType.ONEDRIVE -> runBlocking { za.kilowatch.ultimatefilemanager.network.OnedriveShareClient.openOutputStream(destShare, destRemote) }
                             za.kilowatch.ultimatefilemanager.network.ShareType.GOOGLE_DRIVE -> runBlocking { za.kilowatch.ultimatefilemanager.network.GoogleDriveShareClient.openOutputStream(destShare, destRemote) }
                             za.kilowatch.ultimatefilemanager.network.ShareType.DROPBOX -> runBlocking { za.kilowatch.ultimatefilemanager.network.DropboxShareClient.openOutputStream(destShare, destRemote) }
                                 za.kilowatch.ultimatefilemanager.network.ShareType.AWS_S3, za.kilowatch.ultimatefilemanager.network.ShareType.IDRIVE_E2 -> runBlocking { za.kilowatch.ultimatefilemanager.network.S3ShareClient.openOutputStream(destShare, destRemote) }
                               za.kilowatch.ultimatefilemanager.network.ShareType.WEBDAV -> runBlocking { za.kilowatch.ultimatefilemanager.network.WebDavShareClient.openOutputStream(destShare, destRemote) }
                               za.kilowatch.ultimatefilemanager.network.ShareType.NFS -> za.kilowatch.ultimatefilemanager.network.NfsShareClient.openOutputStream(destShare, destRemote)
                              else -> throw Exception("Unhandled share type")
                         }
                        java.io.FileInputStream(srcFile).use { inp -> outStream.use { out -> za.kilowatch.ultimatefilemanager.util.CopyHelper.copy(inp, out, srcFile.length()) } }
                    }
                    if (isMove) {
                        if (srcFile.delete()) {
                            notifyIndexing(srcPath, isDelete = true, isFolder = false)
                        }
                    }
                    successCount++
                } else if (isNetSrc && !isNetDest) {
                    // Net -> Local
                    val prefix = "net:"
                    val idEnd = srcPath.indexOf('/', prefix.length).takeIf { it != -1 } ?: srcPath.length
                    val srcShareId = srcPath.substring(prefix.length, idEnd)
                    val srcShare = resolveShare(srcShareId) ?: throw Exception("Src share missing")
                    val srcRemote = if (idEnd < srcPath.length) srcPath.substring(idEnd + 1) else ""
                    val fileName = srcRemote.substringAfterLast('/')
                    
                    val destFile = File(destinationPath, fileName)
                    if (!isPathAllowed(destFile.absolutePath)) { failCount++; continue }
                    val inStream = when(srcShare.type) {
                         za.kilowatch.ultimatefilemanager.network.ShareType.SMB -> za.kilowatch.ultimatefilemanager.network.SmbShareClient.openInputStream(srcShare, srcRemote)
                         za.kilowatch.ultimatefilemanager.network.ShareType.FTP -> za.kilowatch.ultimatefilemanager.network.FtpShareClient.openInputStream(srcShare, srcRemote)
                         za.kilowatch.ultimatefilemanager.network.ShareType.TV -> za.kilowatch.ultimatefilemanager.network.TvShareClient.openInputStream(srcShare, srcRemote)
                         za.kilowatch.ultimatefilemanager.network.ShareType.SFTP, za.kilowatch.ultimatefilemanager.network.ShareType.SCP -> za.kilowatch.ultimatefilemanager.network.SshShareClient.openInputStream(srcShare, srcRemote)
                         za.kilowatch.ultimatefilemanager.network.ShareType.ONEDRIVE -> runBlocking { za.kilowatch.ultimatefilemanager.network.OnedriveShareClient.openInputStream(srcShare, srcRemote).first }
                         za.kilowatch.ultimatefilemanager.network.ShareType.GOOGLE_DRIVE -> runBlocking { za.kilowatch.ultimatefilemanager.network.GoogleDriveShareClient.openInputStream(srcShare, srcRemote).first }
                         za.kilowatch.ultimatefilemanager.network.ShareType.DROPBOX -> runBlocking { za.kilowatch.ultimatefilemanager.network.DropboxShareClient.openInputStream(srcShare, srcRemote).first }
                             za.kilowatch.ultimatefilemanager.network.ShareType.AWS_S3, za.kilowatch.ultimatefilemanager.network.ShareType.IDRIVE_E2 -> runBlocking { za.kilowatch.ultimatefilemanager.network.S3ShareClient.openInputStream(srcShare, srcRemote).first }
                           za.kilowatch.ultimatefilemanager.network.ShareType.WEBDAV -> runBlocking { za.kilowatch.ultimatefilemanager.network.WebDavShareClient.openInputStream(srcShare, srcRemote).first }
                           za.kilowatch.ultimatefilemanager.network.ShareType.NFS -> za.kilowatch.ultimatefilemanager.network.NfsShareClient.openInputStream(srcShare, srcRemote)
                           za.kilowatch.ultimatefilemanager.network.ShareType.DLNA -> za.kilowatch.ultimatefilemanager.network.DlnaShareClient.openInputStream(srcShare, srcRemote)
                     }
                    java.io.FileOutputStream(destFile).use { out -> inStream.use { inp -> za.kilowatch.ultimatefilemanager.util.CopyHelper.copy(inp, out) } }
                    notifyIndexing(destFile.absolutePath, isFolder = false)
                     if (isMove) {
                          try {
                              when(srcShare.type) {
                                  za.kilowatch.ultimatefilemanager.network.ShareType.SMB -> za.kilowatch.ultimatefilemanager.network.SmbShareClient.deleteFile(srcShare, srcRemote)
                                  za.kilowatch.ultimatefilemanager.network.ShareType.FTP -> za.kilowatch.ultimatefilemanager.network.FtpShareClient.deleteFile(srcShare, srcRemote)
                                  za.kilowatch.ultimatefilemanager.network.ShareType.TV -> za.kilowatch.ultimatefilemanager.network.TvShareClient.deleteFile(srcShare, srcRemote)
                                  za.kilowatch.ultimatefilemanager.network.ShareType.SFTP, za.kilowatch.ultimatefilemanager.network.ShareType.SCP -> za.kilowatch.ultimatefilemanager.network.SshShareClient.delete(srcShare, srcRemote, false)
                                  za.kilowatch.ultimatefilemanager.network.ShareType.ONEDRIVE -> runBlocking { za.kilowatch.ultimatefilemanager.network.OnedriveShareClient.deleteFile(srcShare, srcRemote) }
                                  za.kilowatch.ultimatefilemanager.network.ShareType.GOOGLE_DRIVE -> runBlocking { za.kilowatch.ultimatefilemanager.network.GoogleDriveShareClient.deleteFile(srcShare, srcRemote) }
                                  za.kilowatch.ultimatefilemanager.network.ShareType.DROPBOX -> runBlocking { za.kilowatch.ultimatefilemanager.network.DropboxShareClient.deleteFile(srcShare, srcRemote) }
                                      za.kilowatch.ultimatefilemanager.network.ShareType.AWS_S3, za.kilowatch.ultimatefilemanager.network.ShareType.IDRIVE_E2 -> runBlocking { za.kilowatch.ultimatefilemanager.network.S3ShareClient.deleteFile(srcShare, srcRemote) }
                                   za.kilowatch.ultimatefilemanager.network.ShareType.WEBDAV -> runBlocking { za.kilowatch.ultimatefilemanager.network.WebDavShareClient.deleteFile(srcShare, srcRemote) }
                                   za.kilowatch.ultimatefilemanager.network.ShareType.NFS -> za.kilowatch.ultimatefilemanager.network.NfsShareClient.deleteFile(srcShare, srcRemote)
                                   za.kilowatch.ultimatefilemanager.network.ShareType.DLNA -> throw UnsupportedOperationException("DLNA is read-only")
                              }
                          } catch (e: Exception) {
                              when(srcShare.type) {
                                  za.kilowatch.ultimatefilemanager.network.ShareType.SMB -> za.kilowatch.ultimatefilemanager.network.SmbShareClient.deleteDir(srcShare, srcRemote)
                                  za.kilowatch.ultimatefilemanager.network.ShareType.FTP -> za.kilowatch.ultimatefilemanager.network.FtpShareClient.deleteDir(srcShare, srcRemote)
                                  za.kilowatch.ultimatefilemanager.network.ShareType.TV -> za.kilowatch.ultimatefilemanager.network.TvShareClient.deleteDir(srcShare, srcRemote)
                                  za.kilowatch.ultimatefilemanager.network.ShareType.SFTP, za.kilowatch.ultimatefilemanager.network.ShareType.SCP -> za.kilowatch.ultimatefilemanager.network.SshShareClient.delete(srcShare, srcRemote, true)
                                  za.kilowatch.ultimatefilemanager.network.ShareType.ONEDRIVE -> runBlocking { za.kilowatch.ultimatefilemanager.network.OnedriveShareClient.deleteFile(srcShare, srcRemote) }
                                  za.kilowatch.ultimatefilemanager.network.ShareType.GOOGLE_DRIVE -> runBlocking { za.kilowatch.ultimatefilemanager.network.GoogleDriveShareClient.deleteFile(srcShare, srcRemote) }
                                  za.kilowatch.ultimatefilemanager.network.ShareType.DROPBOX -> runBlocking { za.kilowatch.ultimatefilemanager.network.DropboxShareClient.deleteFile(srcShare, srcRemote) }
                                      za.kilowatch.ultimatefilemanager.network.ShareType.AWS_S3, za.kilowatch.ultimatefilemanager.network.ShareType.IDRIVE_E2 -> runBlocking { za.kilowatch.ultimatefilemanager.network.S3ShareClient.deleteFile(srcShare, srcRemote) }
                                   za.kilowatch.ultimatefilemanager.network.ShareType.WEBDAV -> runBlocking { za.kilowatch.ultimatefilemanager.network.WebDavShareClient.deleteFile(srcShare, srcRemote) }
                                   za.kilowatch.ultimatefilemanager.network.ShareType.NFS -> za.kilowatch.ultimatefilemanager.network.NfsShareClient.deleteDir(srcShare, srcRemote)
                                   za.kilowatch.ultimatefilemanager.network.ShareType.DLNA -> throw UnsupportedOperationException("DLNA is read-only")
                              }
                          }
                     }
                    successCount++
                }
            } catch (e: Exception) {
                failCount++
            }
        }

        jsonResponse(Status.OK, 
            "success" to true, 
            "successCount" to successCount,
            "failCount" to failCount
        )
    }

    // ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ Upload ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬

    /**
     * Legacy multipart upload ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â NanoHTTPD's parseBody() buffers the entire
     * request body to a temp file before we get control.  Keep for backward
     * compatibility but prefer [handleStreamUpload] for large files.
     */
    private fun handleUpload(session: KtorSession): KtorResult = runBlocking(Dispatchers.IO) {
        val tmpDir = File(context.cacheDir, "uploads")
        tmpDir.mkdirs()

        val files = mutableMapOf<String, String>()
        session.parseBody(files)

        val targetPath = session.parms["path"] ?: return@runBlocking jsonResponse(
            Status.BAD_REQUEST, "error" to "Missing 'path' parameter"
        )
        // SEC-HIGH-3: Strip path-traversal characters from the client-supplied filename.
        val rawFileName = session.parms["filename"] ?: "uploaded_file"
        val fileName = sanitizeFileName(rawFileName)
        val tmpFile = files["file"] ?: return@runBlocking jsonResponse(
            Status.BAD_REQUEST, "error" to "No file uploaded"
        )

        if (targetPath.startsWith("net:")) {
            return@runBlocking handleNetworkUpload(targetPath, fileName, tmpFile)
        }

        val targetDir = File(targetPath)
        if (!isPathAllowed(targetDir.absolutePath)) {
            return@runBlocking jsonResponse(Status.FORBIDDEN, "error" to "Permission denied: restricted target path")
        }
        if (!targetDir.exists() || !targetDir.isDirectory) {
            return@runBlocking jsonResponse(Status.NOT_FOUND, "error" to "Target directory not found")
        }
        if (!targetDir.canWrite()) {
            return@runBlocking jsonResponse(Status.FORBIDDEN, "error" to "Permission denied: cannot write to this directory")
        }

        val destFile = File(targetDir, fileName)
        // SEC-HIGH-3: Verify the resolved destination is actually inside targetDir.
        if (!destFile.canonicalPath.startsWith(targetDir.canonicalPath + File.separator)) {
            return@runBlocking jsonResponse(Status.FORBIDDEN, "error" to "Permission denied: invalid file path")
        }
        if (!isPathAllowed(destFile.absolutePath)) {
            return@runBlocking jsonResponse(Status.FORBIDDEN, "error" to "Permission denied: restricted file path")
        }

        try {
            File(tmpFile).inputStream().use { input ->
                FileOutputStream(destFile).use { output ->
                    za.kilowatch.ultimatefilemanager.util.CopyHelper.copy(input, output)
                }
            }
        } catch (e: IOException) {
            return@runBlocking jsonResponse(Status.INTERNAL_ERROR, "error" to "Upload failed: ${e.message}")
        }

        notifyIndexing(destFile.absolutePath)
        jsonResponse(
            Status.OK,
            "success" to true,
            "path" to destFile.absolutePath,
            "size" to destFile.length()
        )
    }

    /**
     * Streaming upload ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â the browser sends the raw file bytes as the POST body
     * (Content-Type: application/octet-stream).  We stream directly from
     * [IHTTPSession.inputStream] to the destination file, bypassing NanoHTTPD's
     * multipart temp-file buffering entirely.  This is critical for files >1 GB
     * that would otherwise exceed cache-dir capacity or hit temp-file limits.
     *
     * Query params:
     *   path     ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÂ¢Ã¢â€šÂ¬Ã…â€œ target directory (or net:shareId/ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬Ãƒâ€šÃ‚Â¦)
     *   filename ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÂ¢Ã¢â€šÂ¬Ã…â€œ destination file name
     *   size     ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÂ¢Ã¢â€šÂ¬Ã…â€œ expected file size in bytes (for verification)
     */
    private fun handleStreamUpload(session: KtorSession): KtorResult = runBlocking(Dispatchers.IO) {
        val targetPath = session.parms["path"] ?: return@runBlocking jsonResponse(
            Status.BAD_REQUEST, "error" to "Missing 'path' parameter"
        )
        // SEC-HIGH-3: Strip path-traversal characters from the client-supplied filename.
        val rawFileName = session.parms["filename"] ?: return@runBlocking jsonResponse(
            Status.BAD_REQUEST, "error" to "Missing 'filename' parameter"
        )
        val fileName = sanitizeFileName(rawFileName)
        val totalSize = session.parms["size"]?.toLongOrNull() ?: -1L
        val offset    = session.parms["offset"]?.toLongOrNull()   // null = non-chunked
        val chunkSize = session.parms["chunkSize"]?.toLongOrNull()

        // Content-Length header tells us exactly how many bytes to read
        val contentLength = try {
            session.headers["content-length"]?.toLong() ?: chunkSize ?: totalSize
        } catch (_: NumberFormatException) { chunkSize ?: totalSize }

        if (contentLength <= 0L) {
            return@runBlocking jsonResponse(Status.BAD_REQUEST, "error" to "Missing Content-Length")
        }

        // Wrap session.inputStream in a length-limited stream so we read
        // exactly contentLength bytes and don't hang waiting for more data.
        val rawInput = BoundedInputStream(session.inputStream, contentLength)

        try {
            if (targetPath.startsWith("net:")) {
                // Network uploads: chunked mode not supported for network, use single-shot
                handleStreamNetworkUpload(targetPath, fileName, rawInput, contentLength)
            } else {
                val targetDir = File(targetPath)
                if (!isPathAllowed(targetDir.absolutePath)) {
                    return@runBlocking jsonResponse(Status.FORBIDDEN, "error" to "Permission denied: restricted path")
                }
                if (!targetDir.exists() || !targetDir.isDirectory) {
                    return@runBlocking jsonResponse(Status.NOT_FOUND, "error" to "Target directory not found")
                }
                if (!targetDir.canWrite()) {
                    return@runBlocking jsonResponse(Status.FORBIDDEN, "error" to "Permission denied")
                }

                val destFile = File(targetDir, fileName)
                // SEC-HIGH-3: Verify the resolved destination is actually inside targetDir.
                if (!destFile.canonicalPath.startsWith(targetDir.canonicalPath + File.separator)) {
                    return@runBlocking jsonResponse(Status.FORBIDDEN, "error" to "Permission denied: invalid file path")
                }
                if (!isPathAllowed(destFile.absolutePath)) {
                    return@runBlocking jsonResponse(Status.FORBIDDEN, "error" to "Permission denied: restricted path")
                }

                if (offset != null) {
                    // ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ Chunked write: seek to offset, write this chunk ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬
                    java.io.RandomAccessFile(destFile, "rw").use { raf ->
                        raf.seek(offset)
                        val buf = ByteArray(256 * 1024)
                        var remaining = contentLength
                        while (remaining > 0) {
                            val toRead = minOf(buf.size.toLong(), remaining).toInt()
                            val n = rawInput.read(buf, 0, toRead)
                            if (n == -1) break
                            raf.write(buf, 0, n)
                            remaining -= n
                        }
                    }
                    // After last chunk, verify total file size
                    val isLastChunk = (totalSize > 0 && offset + contentLength >= totalSize)
                    if (isLastChunk && destFile.length() != totalSize) {
                        return@runBlocking jsonResponse(Status.INTERNAL_ERROR,
                            "error" to "Size mismatch after assembly: got ${destFile.length()}, expected $totalSize")
                    }
                    notifyIndexing(destFile.absolutePath)
                    jsonResponse(
                        Status.OK,
                        "success" to true,
                        "path" to destFile.absolutePath,
                        "size" to destFile.length()
                    )
                } else {
                    // ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ Single-shot write (non-chunked, files ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â°Ãƒâ€šÃ‚Â¤ 1 GB) ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬
                    val bytesCopied = FileOutputStream(destFile).use { out ->
                        za.kilowatch.ultimatefilemanager.util.CopyHelper.copy(rawInput, out, contentLength)
                    }
                    if (totalSize > 0 && bytesCopied != totalSize) {
                        destFile.delete()
                        return@runBlocking jsonResponse(Status.INTERNAL_ERROR,
                            "error" to "Size mismatch: received $bytesCopied bytes, expected $totalSize")
                    }
                    notifyIndexing(destFile.absolutePath)
                    jsonResponse(
                        Status.OK,
                        "success" to true,
                        "path" to destFile.absolutePath,
                        "size" to destFile.length()
                    )
                }
            }
        } catch (e: Exception) {
            jsonResponse(Status.INTERNAL_ERROR, "error" to "Upload failed: ${e.message}")
        }
    }

    /** Streams a raw upload body directly to a network share (SMB/FTP/TV). */
    private fun handleStreamNetworkUpload(
        netPath: String,
        fileName: String,
        inputStream: java.io.InputStream,
        contentLength: Long
    ): KtorResult = runBlocking(Dispatchers.IO) {
        val prefix = "net:"
        val idEnd = netPath.indexOf('/', prefix.length).takeIf { it != -1 } ?: netPath.length
        val shareId = netPath.substring(prefix.length, idEnd)
        val share = resolveShare(shareId)
            ?: return@runBlocking jsonResponse(Status.NOT_FOUND, "error" to "Share not found")

        if (share.readOnly) return@runBlocking jsonResponse(Status.FORBIDDEN, "error" to "Share is read-only")

        val remotePath = if (idEnd < netPath.length) netPath.substring(idEnd + 1) else ""
        val destPath = if (remotePath.isEmpty()) fileName else "$remotePath/$fileName"

        try {
            if (share.type == za.kilowatch.ultimatefilemanager.network.ShareType.TV) {
                // TV requires Content-Length up front; spool to temp then upload
                val tempFile = File.createTempFile("ufm_tvbuf_", ".tmp", context.cacheDir)
                try {
                    val spooled = tempFile.outputStream().use { out ->
                        za.kilowatch.ultimatefilemanager.util.CopyHelper.copy(inputStream, out, contentLength)
                    }
                    tempFile.inputStream().use { inp ->
                        za.kilowatch.ultimatefilemanager.network.TvShareClient.uploadStream(share, destPath, inp, spooled)
                    }
                } finally { tempFile.delete() }
            } else {
                val outStream = when (share.type) {
                    za.kilowatch.ultimatefilemanager.network.ShareType.SMB ->
                        za.kilowatch.ultimatefilemanager.network.SmbShareClient.openOutputStream(share, destPath)
                    za.kilowatch.ultimatefilemanager.network.ShareType.FTP ->
                        za.kilowatch.ultimatefilemanager.network.FtpShareClient.openOutputStream(share, destPath)
                    za.kilowatch.ultimatefilemanager.network.ShareType.SFTP, za.kilowatch.ultimatefilemanager.network.ShareType.SCP ->
                        za.kilowatch.ultimatefilemanager.network.SshShareClient.openOutputStream(share, destPath)
                    za.kilowatch.ultimatefilemanager.network.ShareType.ONEDRIVE ->
                        runBlocking { za.kilowatch.ultimatefilemanager.network.OnedriveShareClient.openOutputStream(share, destPath) }
                    za.kilowatch.ultimatefilemanager.network.ShareType.GOOGLE_DRIVE ->
                        runBlocking { za.kilowatch.ultimatefilemanager.network.GoogleDriveShareClient.openOutputStream(share, destPath) }
                    za.kilowatch.ultimatefilemanager.network.ShareType.DROPBOX ->
                        runBlocking { za.kilowatch.ultimatefilemanager.network.DropboxShareClient.openOutputStream(share, destPath) }
                    za.kilowatch.ultimatefilemanager.network.ShareType.AWS_S3, za.kilowatch.ultimatefilemanager.network.ShareType.IDRIVE_E2 ->
                        runBlocking { za.kilowatch.ultimatefilemanager.network.S3ShareClient.openOutputStream(share, destPath) }
                    za.kilowatch.ultimatefilemanager.network.ShareType.WEBDAV ->
                        runBlocking { za.kilowatch.ultimatefilemanager.network.WebDavShareClient.openOutputStream(share, destPath) }
                    za.kilowatch.ultimatefilemanager.network.ShareType.NFS ->
                        za.kilowatch.ultimatefilemanager.network.NfsShareClient.openOutputStream(share, destPath)
                    za.kilowatch.ultimatefilemanager.network.ShareType.DLNA ->
                        throw UnsupportedOperationException("DLNA is read-only")
                    else -> throw Exception("Unhandled share type")
                }
                outStream.use { out ->
                    za.kilowatch.ultimatefilemanager.util.CopyHelper.copy(inputStream, out, contentLength)
                }
            }
            jsonResponse(
                Status.OK,
                "success" to true,
                "path" to "net:${share.id}/$destPath",
                "size" to contentLength
            )
        } catch (e: Exception) {
            jsonResponse(Status.INTERNAL_ERROR, "error" to "Upload to network failed: ${e.message}")
        }
    }

    /**
     * Wraps an [InputStream] to read at most [limit] bytes, then signal EOF.
     * Prevents NanoHTTPD's keep-alive from causing us to block forever.
     */
    private class BoundedInputStream(
        private val source: java.io.InputStream,
        private val limit: Long
    ) : java.io.InputStream() {
        private var bytesRead = 0L

        override fun read(): Int {
            if (bytesRead >= limit) return -1
            val b = source.read()
            if (b >= 0) bytesRead++
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (bytesRead >= limit) return -1
            val remaining = (limit - bytesRead).coerceAtMost(len.toLong()).toInt()
            val n = source.read(b, off, remaining)
            if (n > 0) bytesRead += n
            return n
        }
    }

    private fun handleNetworkUpload(netPath: String, fileName: String, tmpFilePath: String): KtorResult = runBlocking(Dispatchers.IO) {
        val prefix = "net:"
        val idEnd = netPath.indexOf('/', prefix.length).takeIf { it != -1 } ?: netPath.length
        val shareId = netPath.substring(prefix.length, idEnd)
        val share = resolveShare(shareId) ?: return@runBlocking jsonResponse(Status.NOT_FOUND, "error" to "Share not found")
        
        if (share.readOnly) return@runBlocking jsonResponse(Status.FORBIDDEN, "error" to "Share is read-only")
        
        val remotePath = if (idEnd < netPath.length) netPath.substring(idEnd + 1) else ""
        val destPath = if (remotePath.isEmpty()) fileName else "$remotePath/$fileName"
        
        val tmpFile = File(tmpFilePath)
        try {
            if (share.type == za.kilowatch.ultimatefilemanager.network.ShareType.TV) {
                java.io.FileInputStream(tmpFile).use { inp ->
                    za.kilowatch.ultimatefilemanager.network.TvShareClient.uploadStream(share, destPath, inp, tmpFile.length())
                }
            } else {
                val outStream = when (share.type) {
                    za.kilowatch.ultimatefilemanager.network.ShareType.SMB -> za.kilowatch.ultimatefilemanager.network.SmbShareClient.openOutputStream(share, destPath)
                    za.kilowatch.ultimatefilemanager.network.ShareType.FTP -> za.kilowatch.ultimatefilemanager.network.FtpShareClient.openOutputStream(share, destPath)
                    za.kilowatch.ultimatefilemanager.network.ShareType.SFTP, za.kilowatch.ultimatefilemanager.network.ShareType.SCP -> za.kilowatch.ultimatefilemanager.network.SshShareClient.openOutputStream(share, destPath)
                    za.kilowatch.ultimatefilemanager.network.ShareType.ONEDRIVE -> runBlocking { za.kilowatch.ultimatefilemanager.network.OnedriveShareClient.openOutputStream(share, destPath) }
                    za.kilowatch.ultimatefilemanager.network.ShareType.GOOGLE_DRIVE -> runBlocking { za.kilowatch.ultimatefilemanager.network.GoogleDriveShareClient.openOutputStream(share, destPath) }
                    za.kilowatch.ultimatefilemanager.network.ShareType.DROPBOX -> runBlocking { za.kilowatch.ultimatefilemanager.network.DropboxShareClient.openOutputStream(share, destPath) }
                    za.kilowatch.ultimatefilemanager.network.ShareType.AWS_S3, za.kilowatch.ultimatefilemanager.network.ShareType.IDRIVE_E2 -> runBlocking { za.kilowatch.ultimatefilemanager.network.S3ShareClient.openOutputStream(share, destPath) }
                    za.kilowatch.ultimatefilemanager.network.ShareType.WEBDAV -> runBlocking { za.kilowatch.ultimatefilemanager.network.WebDavShareClient.openOutputStream(share, destPath) }
                    za.kilowatch.ultimatefilemanager.network.ShareType.NFS -> za.kilowatch.ultimatefilemanager.network.NfsShareClient.openOutputStream(share, destPath)
                    za.kilowatch.ultimatefilemanager.network.ShareType.DLNA -> throw UnsupportedOperationException("DLNA is read-only")
                    else -> throw Exception("Unhandled share type")
                }
                java.io.FileInputStream(tmpFile).use { input -> outStream.use { output -> za.kilowatch.ultimatefilemanager.util.CopyHelper.copy(input, output, tmpFile.length()) } }
            }
            jsonResponse(
                Status.OK,
                "success" to true,
                "path" to "net:${share.id}/$destPath",
                "size" to tmpFile.length()
            )
        } catch (e: Exception) {
            jsonResponse(Status.INTERNAL_ERROR, "error" to "Upload to network failed: ${e.message}")
        }
    }

    // ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ Download ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬

    /** Generate a single-use 60-second download ticket for browser-initiated downloads.
     *  Requires valid Authorization header (no ticket auth for ticket generation). */
    private fun handleDownloadTicket(session: KtorSession): KtorResult {
        if (!isAuthorized(session)) {
            return KtorResult.Json(HttpStatusCode.Unauthorized,
                JSONObject().put("error", "Unauthorized").toString())
        }
        val body = mutableMapOf<String, String>()
        session.parseBody(body)
        val json = JSONObject(body["postData"] ?: "{}")
        val path = json.optString("path", "")
        val isFolder = json.optBoolean("isFolder", false)
        if (path.isBlank()) {
            return jsonResponse(Status.BAD_REQUEST, "error" to "Missing path")
        }
        val ticket = UUID.randomUUID().toString()
        val expiresAt = System.currentTimeMillis() + 60_000L
        downloadTickets[ticket] = DownloadTicket(path, isFolder, expiresAt, used = false)
        return jsonResponse(Status.OK, "ticket" to ticket, "expiresIn" to 60)
    }

    private fun handleDownload(session: KtorSession): KtorResult {
        val path = session.parms["path"] ?: return jsonResponse(
            Status.BAD_REQUEST, "error" to "Missing 'path' parameter"
        )

        if (path.startsWith("net:")) {
            return handleNetworkDownload(path)
        }

        val file = File(path)
        if (!isPathAllowed(file.absolutePath)) {
            return jsonResponse(Status.FORBIDDEN, "error" to "Permission denied: restricted path")
        }
        if (!file.exists() || !file.isFile) {
            return jsonResponse(Status.NOT_FOUND, "error" to "File not found")
        }
        if (!file.canRead()) {
            return jsonResponse(Status.FORBIDDEN, "error" to "Permission denied")
        }

        val mimeType = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(file.extension.lowercase())
            ?: "application/octet-stream"

        return KtorResult.Stream(
            status = Status.OK,
            contentType = mimeType,
            headers = mapOf("Content-Disposition" to "attachment; filename=\"${file.name}\""),
            body = { FileInputStream(file) },
            length = file.length()
        )
    }

    private fun handleNetworkDownload(netPath: String): KtorResult = runBlocking(Dispatchers.IO) {
        val prefix = "net:"
        val idEnd = netPath.indexOf('/', prefix.length).takeIf { it != -1 } ?: netPath.length
        val shareId = netPath.substring(prefix.length, idEnd)
        val share = resolveShare(shareId) ?: return@runBlocking jsonResponse(Status.NOT_FOUND, "error" to "Share not found")
        
        val remotePath = if (idEnd < netPath.length) netPath.substring(idEnd + 1) else ""
        if (remotePath.isEmpty()) return@runBlocking jsonResponse(Status.FORBIDDEN, "error" to "Cannot download share root")

        val fileName = remotePath.substringAfterLast('/')
        val ext = fileName.substringAfterLast('.', "")
        val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase()) ?: "application/octet-stream"

        Log.d(TAG, "handleNetworkDownload: remotePath='$remotePath', shareId='$shareId'")
        try {
            val (inputStream, size) = when (share.type) {
                za.kilowatch.ultimatefilemanager.network.ShareType.SMB -> {
                    Log.d(TAG, "Downloading from SMB: $remotePath")
                    val fileSize = za.kilowatch.ultimatefilemanager.network.SmbShareClient.getFileSize(share, remotePath) ?: 0L
                    val stream = za.kilowatch.ultimatefilemanager.network.SmbShareClient.openInputStream(share, remotePath, dedicated = true)
                    Pair(java.io.BufferedInputStream(stream), fileSize)
                }
                za.kilowatch.ultimatefilemanager.network.ShareType.FTP -> {
                    Log.d(TAG, "Downloading from FTP: $remotePath")
                    val fileSize = za.kilowatch.ultimatefilemanager.network.FtpShareClient.getFileSize(share, remotePath) ?: 0L
                    val stream = za.kilowatch.ultimatefilemanager.network.FtpShareClient.openInputStream(share, remotePath)
                    Pair(java.io.BufferedInputStream(stream), fileSize)
                }
                za.kilowatch.ultimatefilemanager.network.ShareType.SFTP, za.kilowatch.ultimatefilemanager.network.ShareType.SCP -> {
                    Log.d(TAG, "Downloading from SSH: $remotePath")
                    val stream = za.kilowatch.ultimatefilemanager.network.SshShareClient.openInputStream(share, remotePath)
                    Pair(java.io.BufferedInputStream(stream), 0L)
                }
                za.kilowatch.ultimatefilemanager.network.ShareType.ONEDRIVE -> {
                    Log.d(TAG, "Downloading from OneDrive: $remotePath")
                    val (stream, fileSize) = za.kilowatch.ultimatefilemanager.network.OnedriveShareClient.openInputStream(share, remotePath)
                    Pair(java.io.BufferedInputStream(stream), fileSize)
                }
                za.kilowatch.ultimatefilemanager.network.ShareType.GOOGLE_DRIVE -> {
                    Log.d(TAG, "Downloading from Google Drive: $remotePath")
                    val (stream, fileSize) = za.kilowatch.ultimatefilemanager.network.GoogleDriveShareClient.openInputStream(share, remotePath)
                    Pair(java.io.BufferedInputStream(stream), fileSize)
                }
                za.kilowatch.ultimatefilemanager.network.ShareType.DROPBOX -> {
                    Log.d(TAG, "Downloading from Dropbox: $remotePath")
                    val (stream, fileSize) = za.kilowatch.ultimatefilemanager.network.DropboxShareClient.openInputStream(share, remotePath)
                    Pair(java.io.BufferedInputStream(stream), fileSize)
                }
                za.kilowatch.ultimatefilemanager.network.ShareType.AWS_S3, za.kilowatch.ultimatefilemanager.network.ShareType.IDRIVE_E2 -> {
                    Log.d(TAG, "Downloading from S3: $remotePath")
                    val (stream, fileSize) = za.kilowatch.ultimatefilemanager.network.S3ShareClient.openInputStream(share, remotePath)
                    Pair(java.io.BufferedInputStream(stream), fileSize)
                }
                za.kilowatch.ultimatefilemanager.network.ShareType.WEBDAV -> {
                    val (stream, fileSize) = za.kilowatch.ultimatefilemanager.network.WebDavShareClient.openInputStream(share, remotePath)
                    Pair(java.io.BufferedInputStream(stream), fileSize)
                }
                za.kilowatch.ultimatefilemanager.network.ShareType.NFS -> {
                    Log.d(TAG, "Downloading from NFS: $remotePath")
                    val stream = za.kilowatch.ultimatefilemanager.network.NfsShareClient.openInputStream(share, remotePath)
                    Pair(java.io.BufferedInputStream(stream), 0L)
                }
                za.kilowatch.ultimatefilemanager.network.ShareType.TV -> {
                    Log.d(TAG, "Downloading from TV: $remotePath")
                    val stream = za.kilowatch.ultimatefilemanager.network.TvShareClient.openInputStream(share, remotePath)
                    Pair(java.io.BufferedInputStream(stream), 0L)
                }
                za.kilowatch.ultimatefilemanager.network.ShareType.DLNA -> {
                    Log.d(TAG, "Downloading from DLNA: $remotePath")
                    val fileSize = za.kilowatch.ultimatefilemanager.network.DlnaShareClient.getFileSize(share, remotePath) ?: 0L
                    val stream = za.kilowatch.ultimatefilemanager.network.DlnaShareClient.openInputStream(share, remotePath)
                    Pair(java.io.BufferedInputStream(stream), fileSize)
                }
            }
            if (inputStream == null) {
                Log.e(TAG, "Failed to open stream for $remotePath")
                return@runBlocking jsonResponse(Status.NOT_FOUND, "error" to "Failed to open stream")
            }

            // Fix for SMB sentinel size (2GB) causing hangs
            val finalSize = if (size == 2147483647L) 0L else size
            Log.d(TAG, "Download stream opened, size=$finalSize")

            val dispHeader = mapOf("Content-Disposition" to "attachment; filename=\"$fileName\"")
            if (finalSize > 0L) {
                KtorResult.Stream(Status.OK, mimeType, headers = dispHeader,
                    body = { inputStream }, length = finalSize)
            } else {
                KtorResult.Stream(Status.OK, mimeType, headers = dispHeader, body = { inputStream })
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download error for $remotePath", e)
            jsonResponse(Status.INTERNAL_ERROR, "error" to "Failed to download: ${e.message}")
        }
    }

    // ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ Folder Info ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬

    private fun handleFolderInfo(session: KtorSession): KtorResult {
        val path = session.parms["path"] ?: return jsonResponse(
            Status.BAD_REQUEST, "error" to "Missing 'path' parameter"
        )
        
        if (path.startsWith("net:")) {
            return jsonResponse(
                Status.OK,
                "fileCount" to 0, "totalSize" to 0, "totalSizeFormatted" to "Unknown (Network)", "folderName" to path.substringAfterLast('/')
            )
        }
        
        val folder = File(path)
        if (!isPathAllowed(folder.absolutePath)) {
            return jsonResponse(Status.FORBIDDEN, "error" to "Permission denied: restricted path")
        }
        if (!folder.exists() || !folder.isDirectory) {
            return jsonResponse(Status.NOT_FOUND, "error" to "Folder not found")
        }

        var fileCount = 0L
        var totalSize = 0L
        try {
            folder.walkTopDown().forEach { f ->
                if (f.isFile) {
                    fileCount++
                    totalSize += f.length()
                }
            }
        } catch (_: Exception) { }

        return jsonResponse(
            Status.OK,
            "fileCount" to fileCount,
            "totalSize" to totalSize,
            "totalSizeFormatted" to Formatter.formatFileSize(context, totalSize),
            "folderName" to folder.name
        )
    }

    private suspend fun walkNetworkRecursive(share: za.kilowatch.ultimatefilemanager.network.NetworkShare, remotePath: String): List<za.kilowatch.ultimatefilemanager.network.NetworkFile> {
        val result = mutableListOf<za.kilowatch.ultimatefilemanager.network.NetworkFile>()
        val queue = java.util.ArrayDeque<String>()
        queue.add(remotePath)
        
        while (queue.isNotEmpty()) {
            val currentPath = queue.poll() ?: break
            try {
                val files = when (share.type) {
                    za.kilowatch.ultimatefilemanager.network.ShareType.SMB -> za.kilowatch.ultimatefilemanager.network.SmbShareClient.listFiles(share, currentPath)
                    za.kilowatch.ultimatefilemanager.network.ShareType.FTP -> za.kilowatch.ultimatefilemanager.network.FtpShareClient.listFiles(share, currentPath)
                    za.kilowatch.ultimatefilemanager.network.ShareType.ONEDRIVE -> za.kilowatch.ultimatefilemanager.network.OnedriveShareClient.listFiles(share, currentPath)
                    za.kilowatch.ultimatefilemanager.network.ShareType.GOOGLE_DRIVE -> za.kilowatch.ultimatefilemanager.network.GoogleDriveShareClient.listFiles(share, currentPath)
                    za.kilowatch.ultimatefilemanager.network.ShareType.DROPBOX -> za.kilowatch.ultimatefilemanager.network.DropboxShareClient.listFiles(share, currentPath)
                    za.kilowatch.ultimatefilemanager.network.ShareType.AWS_S3, za.kilowatch.ultimatefilemanager.network.ShareType.IDRIVE_E2 -> za.kilowatch.ultimatefilemanager.network.S3ShareClient.listFiles(share, currentPath)
                    za.kilowatch.ultimatefilemanager.network.ShareType.WEBDAV -> za.kilowatch.ultimatefilemanager.network.WebDavShareClient.listFiles(share, currentPath)
                    za.kilowatch.ultimatefilemanager.network.ShareType.SFTP, za.kilowatch.ultimatefilemanager.network.ShareType.SCP -> za.kilowatch.ultimatefilemanager.network.SshShareClient.listFiles(share, currentPath)
                    za.kilowatch.ultimatefilemanager.network.ShareType.TV -> za.kilowatch.ultimatefilemanager.network.TvShareClient.listFiles(share, currentPath)
                    za.kilowatch.ultimatefilemanager.network.ShareType.NFS -> za.kilowatch.ultimatefilemanager.network.NfsShareClient.listFiles(share, currentPath)
                    za.kilowatch.ultimatefilemanager.network.ShareType.DLNA -> za.kilowatch.ultimatefilemanager.network.DlnaShareClient.listFiles(share, currentPath)
                    else -> emptyList()
                }
                for (f in files) {
                    if (f.isDirectory) {
                        queue.add(f.path)
                    } else {
                        result.add(f)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "walkNetworkRecursive error at $currentPath", e)
            }
        }
        return result
    }

    // ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ Download Folder as ZIP (Background Job System) ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬

    private fun handleDownloadFolderStart(session: KtorSession): KtorResult = runBlocking(Dispatchers.IO) {
        val path = session.parms["path"] ?: return@runBlocking jsonResponse(
            Status.BAD_REQUEST, "error" to "Missing 'path' parameter"
        )

        val isNetwork = path.startsWith("net:")
        val folderName: String
        val volumeLabel: String
        val jobId = UUID.randomUUID().toString()
        val allFiles = mutableListOf<String>() // For local: absolute paths; for network: relative-to-share paths
        val share: za.kilowatch.ultimatefilemanager.network.NetworkShare?
        val networkPath: String

        if (isNetwork) {
            val prefix = "net:"
            val idEnd = path.indexOf('/', prefix.length).takeIf { it != -1 } ?: path.length
            val shareId = path.substring(prefix.length, idEnd)
            share = resolveShare(shareId) ?: return@runBlocking jsonResponse(Status.NOT_FOUND, "error" to "Share not found")
            networkPath = if (idEnd < path.length) path.substring(idEnd + 1) else ""
            folderName = networkPath.substringAfterLast('/', "NetworkFolder").ifEmpty { "NetworkFolder" }
            volumeLabel = share.name.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            
            // Collect network files
            val netFiles = walkNetworkRecursive(share, networkPath)
            allFiles.addAll(netFiles.map { it.path })
        } else {
            share = null
            networkPath = ""
            val folder = File(path)
            if (!isPathAllowed(folder.absolutePath)) {
                return@runBlocking jsonResponse(Status.FORBIDDEN, "error" to "Permission denied: restricted path")
            }
            if (!folder.exists() || !folder.isDirectory) {
                return@runBlocking jsonResponse(Status.NOT_FOUND, "error" to "Folder not found")
            }
            if (!folder.canRead()) {
                return@runBlocking jsonResponse(Status.FORBIDDEN, "error" to "Permission denied")
            }
            folderName = folder.name
            volumeLabel = getVolumeLabel(path)
            allFiles.addAll(folder.walkTopDown().filter { it.isFile && it.canRead() }.map { it.absolutePath })
        }

        val job = ZipJob(total = allFiles.size)
        val dateStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val safeFolderName = folderName.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val zipFileName = "ufm_zip_${volumeLabel}_${safeFolderName}_${dateStr}.zip"
        job.zipFileName = zipFileName
        zipJobs[jobId] = job

        // Start background thread
        Thread {
            val tmpZip = File(context.cacheDir, zipFileName)
            try {
                ZipOutputStream(FileOutputStream(tmpZip)).use { zos ->
                    for ((index, filePath) in allFiles.withIndex()) {
                        if (job.cancelled) {
                            tmpZip.delete()
                            job.status = "cancelled"
                            return@Thread
                        }
                        job.current = index + 1
                        val fileName = filePath.substringAfterLast('/')
                        job.currentFile = fileName

                        val entryPath = if (isNetwork) {
                            // Relative to the starting networkPath
                            if (filePath.startsWith(networkPath)) {
                                filePath.substring(networkPath.length).trimStart('/')
                            } else fileName
                        } else {
                            File(filePath).relativeTo(File(path)).path
                        }
                        
                        zos.putNextEntry(ZipEntry(entryPath))
                        
                        if (isNetwork && share != null) {
                            runBlocking {
                                val stream = when (share.type) {
                                    za.kilowatch.ultimatefilemanager.network.ShareType.SMB -> za.kilowatch.ultimatefilemanager.network.SmbShareClient.openInputStream(share, filePath)
                                    za.kilowatch.ultimatefilemanager.network.ShareType.FTP -> za.kilowatch.ultimatefilemanager.network.FtpShareClient.openInputStream(share, filePath)
                                    za.kilowatch.ultimatefilemanager.network.ShareType.ONEDRIVE -> za.kilowatch.ultimatefilemanager.network.OnedriveShareClient.openInputStream(share, filePath).first
                                    za.kilowatch.ultimatefilemanager.network.ShareType.GOOGLE_DRIVE -> za.kilowatch.ultimatefilemanager.network.GoogleDriveShareClient.openInputStream(share, filePath).first
                                    za.kilowatch.ultimatefilemanager.network.ShareType.DROPBOX -> za.kilowatch.ultimatefilemanager.network.DropboxShareClient.openInputStream(share, filePath).first
                                    za.kilowatch.ultimatefilemanager.network.ShareType.AWS_S3, za.kilowatch.ultimatefilemanager.network.ShareType.IDRIVE_E2 -> za.kilowatch.ultimatefilemanager.network.S3ShareClient.openInputStream(share, filePath).first
                                    za.kilowatch.ultimatefilemanager.network.ShareType.WEBDAV -> za.kilowatch.ultimatefilemanager.network.WebDavShareClient.openInputStream(share, filePath).first
                                    za.kilowatch.ultimatefilemanager.network.ShareType.SFTP, za.kilowatch.ultimatefilemanager.network.ShareType.SCP -> za.kilowatch.ultimatefilemanager.network.SshShareClient.openInputStream(share, filePath)
                                    za.kilowatch.ultimatefilemanager.network.ShareType.TV -> za.kilowatch.ultimatefilemanager.network.TvShareClient.openInputStream(share, filePath)
                                    za.kilowatch.ultimatefilemanager.network.ShareType.NFS -> za.kilowatch.ultimatefilemanager.network.NfsShareClient.openInputStream(share, filePath)
                                    za.kilowatch.ultimatefilemanager.network.ShareType.DLNA -> za.kilowatch.ultimatefilemanager.network.DlnaShareClient.openInputStream(share, filePath)
                                    else -> null
                                }
                                stream?.use { it.copyTo(zos) }
                            }
                        } else {
                            FileInputStream(File(filePath)).use { it.copyTo(zos) }
                        }
                        zos.closeEntry()
                    }
                }
                job.zipFile = tmpZip
                job.status = "done"
            } catch (e: Exception) {
                Log.e(TAG, "ZIP Error", e)
                tmpZip.delete()
                job.status = "error"
                job.error = e.message
            }
        }.start()

        return@runBlocking jsonResponse(
            Status.OK,
            "jobId" to jobId,
            "total" to allFiles.size,
            "folderName" to folderName
        )
    }

    private fun handleDownloadFolderStatus(session: KtorSession): KtorResult {
        val jobId = session.parms["jobId"] ?: return jsonResponse(
            Status.BAD_REQUEST, "error" to "Missing 'jobId' parameter"
        )
        val job = zipJobs[jobId] ?: return jsonResponse(
            Status.NOT_FOUND, "error" to "Job not found"
        )

        return jsonResponse(
            Status.OK,
            "status" to job.status,
            "current" to job.current,
            "total" to job.total,
            "currentFile" to job.currentFile,
            "zipFileName" to job.zipFileName
        )
    }

    private fun handleDownloadFolderFile(session: KtorSession): KtorResult {
        val jobId = session.parms["jobId"] ?: return jsonResponse(
            Status.BAD_REQUEST, "error" to "Missing 'jobId' parameter"
        )
        val job = zipJobs[jobId] ?: return jsonResponse(
            Status.NOT_FOUND, "error" to "Job not found"
        )
        if (job.status != "done" || job.zipFile == null) {
            return jsonResponse(Status.BAD_REQUEST, "error" to "ZIP not ready")
        }

        val zipFile = job.zipFile!!
        zipJobs.remove(jobId) // Clear job once download starts
        return KtorResult.Stream(
            status = Status.OK,
            contentType = "application/zip",
            headers = mapOf("Content-Disposition" to "attachment; filename=\"${job.zipFileName}\""),
            body = { DeletingFileInputStream(zipFile) },
            length = zipFile.length()
        )
    }

    private fun handleDownloadFolderCancel(session: KtorSession): KtorResult {
        val jobId = session.parms["jobId"] ?: return jsonResponse(
            Status.BAD_REQUEST, "error" to "Missing 'jobId' parameter"
        )
        val job = zipJobs[jobId] ?: return jsonResponse(
            Status.NOT_FOUND, "error" to "Job not found"
        )
        job.cancelled = true
        zipJobs.remove(jobId)
        return jsonResponse(Status.OK, "success" to true)
    }

    /**
     * Determines the volume label for a given path.
     * Returns "internal" for primary storage, "external" for removable, otherwise "storage".
     */
    private fun getVolumeLabel(path: String): String {
        val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        for (volume in storageManager.storageVolumes) {
            val volPath = volume.safeDirectoryPath ?: continue

            if (path.startsWith(volPath)) {
                return if (volume.isRemovable) "external" else "internal"
            }
        }
        return "storage"
    }

    // ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ Web UI ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬

    private fun handleLogo(): KtorResult {
        return try {
            val inputStream = context.assets.open("remote/logo.png")
            val bytes = inputStream.readBytes()
            newFixedLengthResponse(Status.OK, "image/png", bytes.inputStream(), bytes.size.toLong())
        } catch (e: Exception) {
            jsonResponse(Status.NOT_FOUND, "error" to "Logo not found")
        }
    }

    private fun handleFlag(session: KtorSession): KtorResult {
        val uri = session.uri
        val rawCode = uri.substringAfter("/api/flags/").substringBefore(".svg")
        // SEC-HIGH-4: Validate flag code ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â only allow ISO country codes (2-5 lowercase letters).
        if (!rawCode.matches(Regex("[a-z]{2,5}"))) {
            return jsonResponse(Status.NOT_FOUND, "error" to "Flag not found")
        }
        return try {
            val inputStream = context.assets.open("remote/flags/$rawCode.svg")
            val bytes = inputStream.readBytes()
            newFixedLengthResponse(Status.OK, "image/svg+xml", bytes.inputStream(), bytes.size.toLong())
        } catch (e: Exception) {
            jsonResponse(Status.NOT_FOUND, "error" to "Flag not found")
        }
    }

    // ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ XAPK / Split-APK Installation ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬

    /**
     * Install an XAPK/APKS file that already exists on the device.
     * Starts a background job and returns a jobId for status polling.
     */
    private fun handleInstallXapk(session: KtorSession): KtorResult {
        val lang = session.ufmLang
        val localizedCtx = getLocalizedContext(lang)
        val path = session.parms["path"]
            ?: return jsonResponse(Status.BAD_REQUEST, "error" to localizedCtx.getString(R.string.error_missing_path))
        val file = File(path)
        if (!file.exists() || !file.isFile)
            return jsonResponse(Status.NOT_FOUND, "error" to localizedCtx.getString(R.string.error_file_not_found))
        val ext = file.extension.lowercase()
        if (ext != "xapk" && ext != "apks")
            return jsonResponse(Status.BAD_REQUEST, "error" to localizedCtx.getString(R.string.error_not_apk_xapk))

        val forceDpi = session.parms["forceDpi"]?.takeIf { it.isNotBlank() }
        val forceAbi = session.parms["forceAbi"]?.takeIf { it.isNotBlank() }

        val jobId = UUID.randomUUID().toString()
        val job = XapkJob()
        xapkJobs[jobId] = job
        
        serverScope.launch {
            try {
                processXapk(file, jobId, job, localizedCtx, forceDpi, forceAbi)
                job.status = "awaiting_os"
            } catch (e: Exception) {
                Log.e(TAG, "Local XAPK install failed for job $jobId", e)
                job.status = "error"
                job.error = e.message ?: localizedCtx.getString(R.string.unknown_error)
            }
        }
        
        return jsonResponse(Status.OK, "jobId" to jobId)
    }

    /**
     * Upload an XAPK/APKS from the browser and install it.
     * Saves to cacheDir (never public storage) and delegates to processXapk().
     */
    private fun handleInstallXapkRemote(session: KtorSession): KtorResult {
        val lang = session.ufmLang
        val localizedCtx = getLocalizedContext(lang)
        val files = mutableMapOf<String, String>()
        try { session.parseBody(files) } catch (e: Exception) {
            return jsonResponse(Status.INTERNAL_ERROR, "error" to localizedCtx.getString(R.string.error_parse_failed))
        }
        val tmpPath  = files["file"] ?: return jsonResponse(Status.BAD_REQUEST, "error" to localizedCtx.getString(R.string.error_no_file_uploaded))
        val fileName = session.parms["file"] ?: return jsonResponse(Status.BAD_REQUEST, "error" to localizedCtx.getString(R.string.error_missing_filename))
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext != "xapk" && ext != "apks")
            return jsonResponse(Status.BAD_REQUEST, "error" to localizedCtx.getString(R.string.error_not_apk_xapk))

        val forceDpi = session.parms["forceDpi"]?.takeIf { it.isNotBlank() }
        val forceAbi = session.parms["forceAbi"]?.takeIf { it.isNotBlank() }

        val jobId = UUID.randomUUID().toString()
        val job   = XapkJob()
        xapkJobs[jobId] = job

        // Copy from Ktor's volatile tmp dir to our own stable cache location
        val staging = File(context.cacheDir, "xapk_temp/$jobId/archive.$ext")
        staging.parentFile?.mkdirs()
        try {
            File(tmpPath).copyTo(staging, overwrite = true)
        } catch (e: Exception) {
            xapkJobs.remove(jobId)
            return jsonResponse(Status.INTERNAL_ERROR, "error" to localizedCtx.getString(R.string.error_staging_failed, e.message ?: ""))
        }

        serverScope.launch {
            try {
                processXapk(staging, jobId, job, localizedCtx, forceDpi, forceAbi)
                job.status = "awaiting_os"
                // Cleanup of 'staging' and parent dir happens in InstallReceiver on completion
            } catch (e: Exception) {
                Log.e(TAG, "Remote XAPK install failed for job $jobId", e)
                job.status = "error"
                job.error = e.message ?: localizedCtx.getString(R.string.unknown_error)
                staging.delete()
            }
        }
        return jsonResponse(Status.OK, "jobId" to jobId)
    }

    /** Returns current status of an XAPK install job for polling. */
    private fun handleXapkStatus(session: KtorSession): KtorResult {
        val lang = session.ufmLang
        val localizedCtx = getLocalizedContext(lang)
        val jobId = session.parms["jobId"]
            ?: return jsonResponse(Status.BAD_REQUEST, "error" to localizedCtx.getString(R.string.error_missing_job_id))
        val job = xapkJobs[jobId]
            ?: return jsonResponse(Status.NOT_FOUND, "error" to localizedCtx.getString(R.string.error_job_not_found))
        return jsonResponse(
            Status.OK,
            "status"       to job.status,
            "current"      to job.current,
            "total"        to job.total,
            "currentFile"  to job.currentFile,
            "packageLabel" to job.packageLabel,
            "error"        to (job.error ?: "")
        )
    }

    /**
     * Reads metadata from an XAPK's manifest.json without full extraction.
     * Returns app name, package name, version, and icon (Base64).
     * Only works for XAPK format ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â APKS format has no manifest.json.
     */
    private fun handleXapkInfo(session: KtorSession): KtorResult {
        val lang = session.ufmLang
        val localizedCtx = getLocalizedContext(lang)
        val path = session.parms["path"]
            ?: return jsonResponse(Status.BAD_REQUEST, "error" to localizedCtx.getString(R.string.error_missing_path))
        val file = File(path)
        if (!file.exists() || !file.isFile)
            return jsonResponse(Status.NOT_FOUND, "error" to localizedCtx.getString(R.string.error_file_not_found))
        return try {
            ZipFile(file).use { zip ->
                val entry = zip.getEntry("manifest.json")
                    ?: return jsonResponse(Status.NOT_FOUND, "error" to localizedCtx.getString(R.string.error_manifest_not_found))
                val json = JSONObject(zip.getInputStream(entry).bufferedReader().readText())
                jsonResponse(
                    Status.OK,
                    "name"        to json.optString("name", file.nameWithoutExtension),
                    "packageName" to json.optString("pname", ""),
                    "versionName" to json.optString("version_name", ""),
                    "versionCode" to json.optInt("version_code", 0),
                    "icon"        to json.optString("icon", "")
                )
            }
        } catch (e: Exception) {
            jsonResponse(Status.INTERNAL_ERROR, "error" to (e.message ?: localizedCtx.getString(R.string.error_parse_failed)))
        }
    }

    /**
     * Lightweight pre-install analysis: reads only ZIP entry names (no extraction).
     * Returns available density/ABI splits and whether the device has an exact DPI match.
     * The frontend uses this to decide whether to show the force-DPI picker.
     */
    private fun handleXapkSplits(session: KtorSession): KtorResult {
        val lang = session.ufmLang
        val localizedCtx = getLocalizedContext(lang)
        val path = session.parms["path"]
            ?: return jsonResponse(Status.BAD_REQUEST, "error" to localizedCtx.getString(R.string.error_missing_path))
        val file = File(path)
        if (!file.exists() || !file.isFile)
            return jsonResponse(Status.NOT_FOUND, "error" to localizedCtx.getString(R.string.error_file_not_found))

        val densityRegex = Regex("""split_config\.(ldpi|mdpi|tvdpi|hdpi|xhdpi|xxhdpi|xxxhdpi|anydpi|nodpi)\.apk""", RegexOption.IGNORE_CASE)
        val abiRegex     = Regex("""split_config\.(arm64.v8a|armeabi.v7a|x86_64|x86)\.apk""",              RegexOption.IGNORE_CASE)

        val deviceDpi = context.resources.displayMetrics.densityDpi
        val deviceAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
        val deviceBucket = dpiToBucket(deviceDpi)

        val availableDpi = mutableListOf<String>()
        val availableAbi = mutableListOf<String>()

        return try {
            ZipFile(file).use { zip ->
                zip.entries().asSequence().forEach { entry ->
                    densityRegex.find(entry.name)?.let { availableDpi.add(it.groupValues[1].lowercase()) }
                    abiRegex.find(entry.name)?.let     { availableAbi.add(it.groupValues[1].lowercase()) }
                }
            }
            val recommended = pickBestDpi(deviceBucket, availableDpi)
            val exactMatch  = recommended == deviceBucket
            jsonResponse(
                Status.OK,
                "deviceDpi"      to deviceDpi,
                "deviceBucket"   to deviceBucket,
                "deviceAbi"      to deviceAbi,
                "availableDpi"   to availableDpi.joinToString(","),
                "availableAbi"   to availableAbi.joinToString(","),
                "recommendedDpi" to (recommended ?: ""),
                "exactMatch"     to exactMatch
            )
        } catch (e: Exception) {
            jsonResponse(Status.INTERNAL_ERROR, "error" to (e.message ?: localizedCtx.getString(R.string.error_read_failed)))
        }
    }

    /** Maps raw densityDpi integer to the density bucket name used in split APK file names. */
    private fun dpiToBucket(dpi: Int): String = when {
        dpi <= 140  -> "ldpi"
        dpi <= 180  -> "mdpi"
        dpi <= 230  -> "tvdpi"
        dpi <= 280  -> "hdpi"
        dpi <= 400  -> "xhdpi"
        dpi <= 560  -> "xxhdpi"
        else        -> "xxxhdpi"
    }

    /**
     * Picks the closest available DPI bucket to the device's bucket.
     * Prefers exact match, then nearest higher, then nearest lower.
     */
    private fun pickBestDpi(deviceBucket: String, available: List<String>): String? {
        if (available.isEmpty()) return null
        if (deviceBucket in available) return deviceBucket
        val order = listOf("ldpi", "mdpi", "tvdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi")
        val deviceIdx = order.indexOf(deviceBucket)
        if (deviceIdx < 0) return available.first()
        // Search outward: prefer closer match, prefer higher over lower
        for (delta in 1..order.size) {
            val higher = order.getOrNull(deviceIdx + delta)
            val lower  = order.getOrNull(deviceIdx - delta)
            if (higher != null && higher in available) return higher
            if (lower  != null && lower  in available) return lower
        }
        return available.first()
    }

    /**
     * Core XAPK processing function. Runs on a background thread.
     *
     * Phases:
     *  1. Count APK entries in the ZIP ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ set job.total
     *  2. Extract only .apk files to cacheDir/xapk_temp/<jobId>/
     *  3. Read manifest.json for package label (XAPK only, optional)
     *  4. Create PackageInstaller session and stream all APKs into it
     *  5. Commit session ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ triggers InstallReceiver on completion
     */
    private fun processXapk(
        archiveFile: File,
        jobId: String,
        job: XapkJob,
        localizedCtx: Context,
        forceDpi: String? = null,
        forceAbi: String? = null
    ) {
        val extractDir = File(context.cacheDir, "xapk_temp/$jobId")
        extractDir.mkdirs()

        val densityRegex = Regex("""split_config\.(ldpi|mdpi|tvdpi|hdpi|xhdpi|xxhdpi|xxxhdpi|anydpi|nodpi)\.apk""", RegexOption.IGNORE_CASE)
        val abiRegex     = Regex("""split_config\.(arm64.v8a|armeabi.v7a|x86_64|x86)\.apk""",              RegexOption.IGNORE_CASE)

        try {
            val apkFiles = mutableListOf<File>()

            ZipFile(archiveFile).use { zip ->
                val entries = zip.entries().asSequence().toList()
                val apkEntries = entries.filter { it.name.endsWith(".apk", ignoreCase = true) }
                job.total = apkEntries.size

                if (apkEntries.isEmpty()) {
                    job.status = "error"
                    job.error  = localizedCtx.getString(R.string.error_no_apk_in_archive)
                    extractDir.deleteRecursively()
                    return
                }

                // Phase 2: Extract APK splits (apply force-DPI / force-ABI filter)
                apkEntries.forEachIndexed { i, entry ->
                    val name = entry.name.substringAfterLast('/')
                    job.current     = i + 1
                    job.currentFile = name

                    // Determine if this split should be included
                    val isDensitySplit = densityRegex.containsMatchIn(name)
                    val isAbiSplit     = abiRegex.containsMatchIn(name)
                    val include = when {
                        isDensitySplit && forceDpi != null ->
                            name.contains(forceDpi, ignoreCase = true)
                        isAbiSplit && forceAbi != null ->
                            name.contains(forceAbi, ignoreCase = true)
                        else -> true  // base.apk, language splits, anydpi, unrecognised ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ always include
                    }
                    if (!include) return@forEachIndexed

                    val outFile = File(extractDir, name)
                    // SEC-HIGH-5: Zip Slip guard ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â ensure extracted entry stays within extractDir.
                    if (!outFile.canonicalPath.startsWith(extractDir.canonicalPath + File.separator)) {
                        Log.w(TAG, "Zip Slip attempt rejected: ${entry.name}")
                        return@forEachIndexed
                    }
                    zip.getInputStream(entry).use { input ->
                        outFile.outputStream().use { input.copyTo(it) }
                    }
                    apkFiles.add(outFile)
                }

                // Phase 3: Optional manifest.json for label (XAPK format only)
                zip.getEntry("manifest.json")?.let { manifestEntry ->
                    runCatching {
                        val text = zip.getInputStream(manifestEntry).bufferedReader().readText()
                        job.packageLabel = JSONObject(text).optString("name", "")
                    }
                }
            }

            if (apkFiles.isEmpty()) {
                job.status = "error"
                job.error  = localizedCtx.getString(R.string.error_no_splits_matched)
                extractDir.deleteRecursively()
                return
            }

            // Phase 4: PackageInstaller session
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

            // Phase 5: Commit ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â InstallReceiver handles UI prompt + cleanup
            val broadcastIntent = Intent(context, InstallReceiver::class.java).apply {
                action = InstallReceiver.ACTION_INSTALL_COMPLETE
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
            Log.e(TAG, "XAPK install failed for job $jobId", e)
            job.status = "error"
            job.error  = e.message ?: localizedCtx.getString(R.string.unknown_error)
            // Clean up immediately on error (InstallReceiver won't fire)
            extractDir.deleteRecursively()
            xapkJobs.remove(jobId)
        }
    }

    // ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ APK Installation ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬

    private fun handleInstallStatus(): KtorResult {
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true // Below Oreo, this permission didn't exist in this form
        }
        return jsonResponse(Status.OK, "granted" to granted)
    }

    private fun handleInstallApk(session: KtorSession): KtorResult {
        val lang = session.ufmLang
        val localizedCtx = getLocalizedContext(lang)
        val path = session.parms["path"] ?: return jsonResponse(Status.BAD_REQUEST, "error" to localizedCtx.getString(R.string.error_missing_path))
        val file = File(path)
        if (!file.exists() || !file.isFile) {
            return jsonResponse(Status.NOT_FOUND, "error" to localizedCtx.getString(R.string.error_file_not_found))
        }
        if (!file.name.lowercase().endsWith(".apk")) {
            return jsonResponse(Status.BAD_REQUEST, "error" to localizedCtx.getString(R.string.error_not_apk))
        }

        return try {
            PackageInstallerHelper.installApk(context, file)
            jsonResponse(Status.OK, "success" to true)
        } catch (e: SecurityException) {
            // Permission not granted — Settings page has been opened on the device.
            jsonResponse(Status.FORBIDDEN, "error" to (e.message ?: localizedCtx.getString(R.string.error_install_unknown_apps_remote_instruction)))
        } catch (e: Exception) {
            jsonResponse(Status.INTERNAL_ERROR, "error" to (e.message ?: localizedCtx.getString(R.string.error_trigger_install_failed)))
        }
    }

    private fun handleInstallRemote(session: KtorSession): KtorResult {
        val files = mutableMapOf<String, String>()
        try {
            session.parseBody(files)
        } catch (e: Exception) {
            return jsonResponse(Status.INTERNAL_ERROR, "error" to "Parse error")
        }

        val tmpPath = files["file"] ?: return jsonResponse(Status.BAD_REQUEST, "error" to "No file uploaded")
        // SEC-HIGH-3: Sanitize filename to prevent path traversal.
        val rawFileName = session.parms["file"] ?: "app.apk"
        val fileName = sanitizeFileName(rawFileName).ifBlank { "app.apk" }
        val tmpFile = File(tmpPath)

        // SEC-HIGH-6: Write APK to app-private cacheDir instead of world-readable external storage.
        val tempDir = File(context.cacheDir, "apk_install")
        tempDir.mkdirs()
        // Purge any stale temp APKs so no prior PackageInstaller session can interfere.
        tempDir.listFiles()?.forEach { it.delete() }

        // UUID prefix ensures each upload attempt gets a unique, isolated file.
        val destFile = File(tempDir, "${UUID.randomUUID()}_$fileName")
        return try {
            FileInputStream(tmpFile).use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            PackageInstallerHelper.installApk(context, destFile)
            jsonResponse(Status.OK, "success" to true)
        } catch (e: SecurityException) {
            destFile.delete()
            // Permission not granted — Settings page has been opened on the device.
            jsonResponse(Status.FORBIDDEN, "error" to (e.message ?: "Install permission not granted"))
        } catch (e: Exception) {
            destFile.delete()
            val msg = if (za.kilowatch.ultimatefilemanager.BuildConfig.DEBUG) e.message ?: "Failed to save/install APK" else "Install failed"
            jsonResponse(Status.INTERNAL_ERROR, "error" to msg)
        }
    }

    /**
     * Installs a single APK using the PackageInstaller API.
     *
     * Unlike startActivity(ACTION_VIEW), PackageInstaller works from a background
     * context, so it succeeds even when RemoteManageActivity is backgrounded by a
     * remote-desktop app. The OS install-confirmation dialog is surfaced via
     * [InstallReceiver] when PackageInstaller fires STATUS_PENDING_USER_ACTION.
     *
     * @throws SecurityException if the user has not yet granted "Install Unknown Apps".
     *   In that case, this function also opens the relevant Settings screen automatically.
     */
    private fun installApkViaPackageInstaller(file: File) {
        // Guard: if the user hasn't granted "Install Unknown Apps" for UFM,
        // open the Settings page automatically and surface a clear error.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                openInstallPermissionSettings()
                throw SecurityException(context.getString(R.string.error_install_unknown_apps_remote_instruction))
            }
        }

        za.kilowatch.ultimatefilemanager.util.PackageInstallerHelper.abandonMySessions(context)

        val packageInstaller = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = packageInstaller.createSession(params)
        val session  = packageInstaller.openSession(sessionId)

        try {
            session.openWrite(file.name, 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
            }

            // Empty jobId — InstallReceiver skips xapk_temp cleanup and only
            // wipes the apk_install/ dir on completion.
            val broadcastIntent = Intent(context, InstallReceiver::class.java).apply {
                action = InstallReceiver.ACTION_INSTALL_COMPLETE
                putExtra("jobId", "")
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context, sessionId, broadcastIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            session.commit(pendingIntent.intentSender)
        } catch (e: Exception) {
            session.abandon() // Don't leave an orphaned session in PackageInstaller
            throw e
        } finally {
            session.close()
        }
    }

    /**
     * Opens the per-app "Install Unknown Apps" Settings page for UFM.
     * Uses FLAG_ACTIVITY_NEW_TASK so it works from a background context.
     */
    private fun openInstallPermissionSettings() {
        runCatching {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            } else {
                Intent(Settings.ACTION_SECURITY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            context.startActivity(intent)
        }.onFailure { Log.w(TAG, "Could not open install-permission settings", it) }
    }

    // ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ Apps Management ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬

    private fun handleApps(): KtorResult {
        val pm = context.packageManager
        val packages = pm.getInstalledPackages(0)
        val arr = JSONArray()

        for (pkg in packages) {
            val appInfo = pkg.applicationInfo ?: continue
            val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0

            // App name
            val appName = pm.getApplicationLabel(appInfo).toString()

            // Sizes
            val apkFile = File(appInfo.sourceDir)
            val apkSize = apkFile.length()

            // Data/cache size from the app's data directory
            var dataSize = 0L
            try {
                val dataDir = appInfo.dataDir?.let { File(it) }
                if (dataDir != null && dataDir.exists()) {
                    dataSize = dataDir.walkTopDown()
                        .filter { it.isFile }
                        .sumOf { it.length() }
                }
            } catch (_: Exception) { }

            // Install date
            val installDate = pkg.firstInstallTime

            // App icon as base64 PNG
            val iconBase64 = try {
                val drawable = pm.getApplicationIcon(appInfo)
                val bitmap = if (drawable is BitmapDrawable) {
                    drawable.bitmap
                } else {
                    val bmp = Bitmap.createBitmap(
                        drawable.intrinsicWidth.coerceAtLeast(1),
                        drawable.intrinsicHeight.coerceAtLeast(1),
                        Bitmap.Config.ARGB_8888
                    )
                    val canvas = Canvas(bmp)
                    drawable.setBounds(0, 0, canvas.width, canvas.height)
                    drawable.draw(canvas)
                    bmp
                }
                // Scale to 48x48 for smaller payload
                val scaled = Bitmap.createScaledBitmap(bitmap, 48, 48, true)
                val stream = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.PNG, 80, stream)
                Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
            } catch (_: Exception) { "" }

            val obj = JSONObject().apply {
                put("name", appName)
                put("packageName", pkg.packageName)
                put("isSystem", isSystem)
                put("installedDate", installDate)
                put("appSize", apkSize)
                put("appSizeFormatted", Formatter.formatFileSize(context, apkSize))
                put("dataSize", dataSize)
                put("dataSizeFormatted", Formatter.formatFileSize(context, dataSize))
                put("icon", iconBase64)
                // Extraction metadata
                val splits = appInfo.splitSourceDirs?.toList() ?: emptyList()
                put("hasSplits", splits.isNotEmpty())
                val obbDir = try {
                    File(
                        Environment.getExternalStorageDirectory(),
                        "Android/obb/${pkg.packageName}"
                    )
                } catch (_: Exception) { null }
                val hasObb = obbDir?.exists() == true &&
                    obbDir.listFiles()?.any { it.extension.equals("obb", ignoreCase = true) } == true
                put("hasObb", hasObb)
            }
            arr.put(obj)
        }

        return newFixedLengthResponse(
            Status.OK, "application/json", arr.toString()
        )
    }

    private fun handleAppOpenInfo(session: KtorSession): KtorResult {
        val body = mutableMapOf<String, String>()
        session.parseBody(body)
        val json = JSONObject(body["postData"] ?: "{}")
        val packageName = json.optString("packageName", "")

        if (packageName.isEmpty()) {
            return jsonResponse(Status.BAD_REQUEST, "error" to "Missing packageName")
        }

        return try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            jsonResponse(Status.OK, "success" to true)
        } catch (e: Exception) {
            jsonResponse(Status.INTERNAL_ERROR, "error" to (e.message ?: "Failed to open app info"))
        }
    }

    /**
     * Auto-detects APK vs XAPK and streams the result directly to the browser.
     * Selection rule: has splits OR has OBBs ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ XAPK, otherwise ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ APK.
     */
    private fun handleAppExtract(session: KtorSession): KtorResult {
        val packageName = session.parms["packageName"]
            ?: return jsonResponse(Status.BAD_REQUEST, "error" to "Missing packageName")

        val pm = context.packageManager
        val appInfo = try {
            pm.getApplicationInfo(packageName, 0)
        } catch (e: Exception) {
            return jsonResponse(Status.NOT_FOUND, "error" to "Package not found")
        }
        val appName = pm.getApplicationLabel(appInfo).toString()
            .replace(Regex("[/\\\\:*?\"<>|]"), "_") // sanitize filename
        val splits = appInfo.splitSourceDirs?.toList() ?: emptyList()

        // OBB check
        val obbDir = runCatching {
            File(Environment.getExternalStorageDirectory(), "Android/obb/$packageName")
        }.getOrNull()
        val obbFiles = obbDir?.takeIf { it.exists() }
            ?.listFiles()
            ?.filter { it.isFile && it.extension.equals("obb", ignoreCase = true) }
            ?: emptyList()

        val useXapk = splits.isNotEmpty() || obbFiles.isNotEmpty()

        return if (!useXapk) {
            // ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ Stream APK ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬
            val apkFile = File(appInfo.sourceDir)
            if (!apkFile.exists()) {
                return jsonResponse(Status.NOT_FOUND, "error" to "APK file not found")
            }
            KtorResult.Stream(
                status = Status.OK,
                contentType = "application/vnd.android.package-archive",
                headers = mapOf("Content-Disposition" to "attachment; filename=\"$appName.apk\""),
                body = { FileInputStream(apkFile) },
                length = apkFile.length()
            )
        } else {
            // ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ Build and stream XAPK ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬
            val pkgInfo = runCatching { pm.getPackageInfo(packageName, 0) }.getOrNull()
            val versionName = pkgInfo?.versionName ?: "1.0"

            // Build ZIP in memory via temp file
            val tmpFile = File(context.cacheDir, "ufm_extract_${packageName}.xapk")
            try {
                ZipOutputStream(FileOutputStream(tmpFile)).use { zip ->
                    // base.apk
                    addFileToZip(zip, File(appInfo.sourceDir), "base.apk")

                    // split APKs
                    splits.forEachIndexed { i, path ->
                        val f = File(path)
                        if (f.exists()) addFileToZip(zip, f, f.name.ifEmpty { "split_$i.apk" })
                    }

                    // OBB files
                    obbFiles.forEach { obb -> addFileToZip(zip, obb, "obb/${obb.name}") }

                    // manifest.json
                    val manifest = "{\"xapk_version\":2,\"package_name\":\"$packageName\"," +
                        "\"name\":\"$appName\",\"version_name\":\"$versionName\"}"
                    zip.putNextEntry(ZipEntry("manifest.json"))
                    zip.write(manifest.toByteArray())
                    zip.closeEntry()
                }

                KtorResult.Stream(
                    status = Status.OK,
                    contentType = "application/octet-stream",
                    headers = mapOf("Content-Disposition" to "attachment; filename=\"$appName.xapk\""),
                    body = { DeletingFileInputStream(tmpFile) },
                    length = tmpFile.length()
                )
            } catch (e: Exception) {
                tmpFile.delete()
                jsonResponse(Status.INTERNAL_ERROR, "error" to (e.message ?: "Extraction failed"))
            }
        }
    }

    /** Adds a single file into an open ZipOutputStream. */
    private fun addFileToZip(zip: ZipOutputStream, file: File, entryName: String) {
        zip.putNextEntry(ZipEntry(entryName))
        FileInputStream(file).use { it.copyTo(zip) }
        zip.closeEntry()
    }

    /** Cache of lang code -> localized Context to avoid recreating on every request. */
    private val localizedContextCache = mutableMapOf<String, Context>()

    private fun getLocalizedContext(langCode: String): Context {
        val key = langCode.lowercase().ifBlank { "en" }
        return localizedContextCache.getOrPut(key) {
            val locale = if (key == "en" || key.isBlank()) Locale.ENGLISH
                         else Locale.forLanguageTag(key)
            val appCtx = context.applicationContext
            val config = Configuration(appCtx.resources.configuration)
            config.setLocale(locale)
            appCtx.createConfigurationContext(config)
        }
    }

    private fun getLocalizedLabel(lang: String, volume: StorageVolume?): String {
        val localizedCtx = getLocalizedContext(lang)
        return when {
            volume == null -> localizedCtx.getString(R.string.remote_volume_network)
            volume.isPrimary -> localizedCtx.getString(R.string.remote_volume_internal)
            volume.isRemovable -> localizedCtx.getString(R.string.remote_volume_sdcard)
            else -> volume.getDescription(context)
        }
    }

    private fun serveWebUI(session: KtorSession): KtorResult {
        return try {
            val inputStream = context.assets.open("remote/index.html")
            var html = inputStream.bufferedReader().readText()

            // Read language from cookie via the KtorSession adapter
            val lang = session.ufmLang
            val localizedCtx = getLocalizedContext(lang)

            // Replace {{remote_...}} i18n placeholders
            val regex = Regex("\\{\\{(remote_[a-z0-9_]+)\\}\\}")
            html = regex.replace(html) { match ->
                val resName = match.groupValues[1]
                var resId = localizedCtx.resources.getIdentifier(resName, "string", localizedCtx.packageName)
                if (resId == 0) resId = context.resources.getIdentifier(resName, "string", context.packageName)
                if (resId != 0) {
                    localizedCtx.getString(resId).replace("'", "&#39;")
                } else {
                    match.value
                }
            }

            // Security + cache headers are applied by KtorResult.Html.send()
            KtorResult.Html(
                status = HttpStatusCode.OK,
                body = html,
                extraHeaders = mapOf(
                    "Cache-Control" to "no-cache, no-store, must-revalidate",
                    "Pragma" to "no-cache",
                    "Expires" to "0"
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to serve localized Web UI", e)
            val msg = if (za.kilowatch.ultimatefilemanager.BuildConfig.DEBUG) e.message ?: "Web UI error" else "Web UI error"
            KtorResult.Html(HttpStatusCode.InternalServerError, msg)
        }
    }

    // ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ Helpers ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬

    /**
     * SEC-HIGH-3: Removes path-traversal characters from an untrusted filename.
     * Strips directory separators, null bytes, and reduces consecutive dots.
     */
    private fun sanitizeFileName(name: String): String {
        return name
            .replace("..", "")
            .replace("/", "")
            .replace("\\", "")
            .replace("\u0000", "") // null byte
            .trim()
            .ifBlank { "uploaded_file" }
    }

    // (addSecurityHeaders and the old NanoHTTPD-era jsonResponse removed;
    //  security headers are now applied in KtorResult.send() for all response types,
    //  and jsonResponse() is defined above using HttpStatusCode directly.)

    /**
     * Custom FileInputStream that deletes the underlying file when closed.
     * Used for streaming temporary files (ZIPs, XAPKs) to the browser.
     */
    private class DeletingFileInputStream(private val file: File) : FileInputStream(file) {
        override fun close() {
            try {
                super.close()
            } finally {
                if (file.exists()) {
                    val deleted = file.delete()
                    Log.d("DeletingFIS", "Temp file ${file.name} deleted: $deleted")
                }
            }
        }
    }
}




