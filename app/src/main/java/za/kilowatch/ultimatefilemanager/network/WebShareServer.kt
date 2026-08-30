package za.kilowatch.ultimatefilemanager.network

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.text.format.Formatter
import android.util.Log
import android.webkit.MimeTypeMap
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.engine.*
import io.ktor.server.netty.Netty
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.header
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.BuildConfig
import za.kilowatch.ultimatefilemanager.R
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object WebShareServer {
    private const val TAG = "WebShareServer"

    // ── Rate limiting constants ──
    private const val RATE_LIMIT_DELAY_THRESHOLD = 5
    private const val RATE_LIMIT_BLOCK_THRESHOLD = 10
    private const val RATE_LIMIT_WINDOW_MS = 15 * 60 * 1000L  // 15 minutes
    private const val RATE_LIMIT_DELAY_MS = 5_000L

    // ── Rate limit state ──
    private data class RateLimitEntry(
        val failures: Int,
        val firstFailureTime: Long
    )

    private sealed class RateLimitResult {
        object Allowed : RateLimitResult()
        object Delayed : RateLimitResult()
        object Blocked : RateLimitResult()
    }

    private val rateLimitMap = java.util.concurrent.ConcurrentHashMap<String, RateLimitEntry>()

    private var server: io.ktor.server.engine.EmbeddedServer<*, *>? = null
    
    var pin: String = ""
        private set
    var port: Int = 0
        private set
    var sslPort: Int = 0
        private set
    var certFingerprint: String = ""
        private set

    private var filesToShare: List<File> = emptyList()
    private var cleanUpOnStop = false
    private var appContext: Context? = null
    private var sslServerSocket: SSLServerSocket? = null
    private var ktorPort: Int = 0

    private val localizedContextCache = mutableMapOf<String, Context>()

    private fun getLocalizedContext(context: Context, langCode: String): Context {
        val key = langCode.lowercase().ifBlank { "en" }
        return localizedContextCache.getOrPut(key) {
            val locale = if (key == "en" || key.isBlank()) java.util.Locale.ENGLISH
                         else java.util.Locale.forLanguageTag(key)
            val appCtx = context.applicationContext
            val config = android.content.res.Configuration(appCtx.resources.configuration)
            config.setLocale(locale)
            appCtx.createConfigurationContext(config)
        }
    }

    /**
     * Finds an available local port.
     */
    private fun findFreePort(): Int {
        return try {
            ServerSocket(0).use { it.localPort }
        } catch (e: Exception) {
            8080 // fallback
        }
    }

    /**
     * Resolves the primary local network IPv4 address.
     */
    fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isLoopback || !networkInterface.isUp) continue
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address is Inet4Address) {
                        val ip = address.hostAddress
                        if (ip != "127.0.0.1") {
                            return ip
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting local IP", e)
        }
        return "127.0.0.1"
    }

    /**
     * Converts a drawable resource to a Bitmap.
     */
    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable) {
            return drawable.bitmap
        }
        val bitmap = Bitmap.createBitmap(
            drawable.intrinsicWidth.coerceAtLeast(1),
            drawable.intrinsicHeight.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    /** Record a failed PIN attempt for a client IP.
     *  Atomically increments the failure counter or starts a new window if the previous one expired. */
    private fun recordFailure(clientIp: String) {
        val now = System.currentTimeMillis()
        rateLimitMap.compute(clientIp) { _, existing ->
            if (existing == null) {
                RateLimitEntry(1, now)
            } else if (now - existing.firstFailureTime > RATE_LIMIT_WINDOW_MS) {
                RateLimitEntry(1, now)
            } else {
                existing.copy(failures = existing.failures + 1)
            }
        }
    }

    /** Reset the rate-limit counter for a client IP (called on successful PIN entry). */
    private fun resetRateLimit(clientIp: String) {
        rateLimitMap.remove(clientIp)
    }

    /** Returns true if the client IP has exceeded the block threshold within the current window. */
    private fun isIpBlocked(clientIp: String): Boolean {
        val entry = rateLimitMap[clientIp] ?: return false
        val now = System.currentTimeMillis()
        if (now - entry.firstFailureTime > RATE_LIMIT_WINDOW_MS) {
            rateLimitMap.remove(clientIp)
            return false
        }
        return entry.failures >= RATE_LIMIT_BLOCK_THRESHOLD
    }

    /** Check the rate-limit status for a client IP. Returns Allowed, Delayed, or Blocked. */
    private fun checkRateLimit(clientIp: String): RateLimitResult {
        val entry = rateLimitMap[clientIp] ?: return RateLimitResult.Allowed
        val now = System.currentTimeMillis()
        if (now - entry.firstFailureTime > RATE_LIMIT_WINDOW_MS) {
            rateLimitMap.remove(clientIp)
            return RateLimitResult.Allowed
        }
        return when {
            entry.failures >= RATE_LIMIT_BLOCK_THRESHOLD -> RateLimitResult.Blocked
            entry.failures >= RATE_LIMIT_DELAY_THRESHOLD -> RateLimitResult.Delayed
            else -> RateLimitResult.Allowed
        }
    }

    /**
     * Starts the Ktor web server.
     * Returns the access URL (e.g. http://192.168.1.100:8080)
     */
    private fun generateSelfSignedCert(context: Context): SSLContext? {
        return try {
            val dir = File(context.filesDir, "webshare")
            dir.mkdirs()
            val keystoreFile = File(dir, "server.p12")

            val keyPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
            val now = Date()
            val notAfter = Date(now.time + 365L * 24 * 3600 * 1000)
            val issuer = X500Name("CN=UFM WebShare")
            val serial = BigInteger.valueOf(now.time)

            val certBuilder = JcaX509v3CertificateBuilder(issuer, serial, now, notAfter, issuer, keyPair.public)
            val signer = JcaContentSignerBuilder("SHA256withECDSA").build(keyPair.private)
            val certHolder = certBuilder.build(signer)
            val certFactory = java.security.cert.CertificateFactory.getInstance("X.509")
            val cert = certFactory.generateCertificate(certHolder.encoded.inputStream()) as X509Certificate

            val digest = MessageDigest.getInstance("SHA-256")
            certFingerprint = digest.digest(cert.encoded).joinToString("") { "%02x".format(it) }

            val ks = KeyStore.getInstance("PKCS12")
            ks.load(null, null)
            ks.setKeyEntry("webshare", keyPair.private, "webshare".toCharArray(), arrayOf<X509Certificate>(cert))
            keystoreFile.outputStream().use { ks.store(it, "webshare".toCharArray()) }

            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(ks, "webshare".toCharArray())
            SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, SecureRandom()) }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to generate TLS certificate, HTTP only", e)
            null
        }
    }

    fun start(context: Context, files: List<File>, cleanUp: Boolean = false): String {
        appContext = context.applicationContext
        filesToShare = files
        cleanUpOnStop = cleanUp
        pin = String.format("%04d", (1000..9999).random())

        val localIp = getLocalIpAddress()
        ktorPort = findFreePort()
        val sslContext = generateSelfSignedCert(context)
        if (sslContext != null) sslPort = findFreePort()

        val serverInstance = embeddedServer(Netty, port = ktorPort, host = "127.0.0.1") {
            routing {
                // Serve localized flag SVGs
                get("/api/flags/{code}") {
                    withContext(Dispatchers.IO) {
                        val rawCode = call.parameters["code"]?.lowercase() ?: ""
                        val code = rawCode.removeSuffix(".svg")
                        if (!code.matches(Regex("[a-z]{2,5}"))) {
                            call.respondText("Not Found", status = HttpStatusCode.NotFound)
                            return@withContext
                        }
                        try {
                            val inputStream = context.assets.open("remote/flags/$code.svg")
                            val bytes = inputStream.readBytes()
                            call.respondOutputStream(ContentType.parse("image/svg+xml")) {
                                write(bytes)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed serving flag for $code", e)
                            call.respondText("Not Found", status = HttpStatusCode.NotFound)
                        }
                    }
                }

                // Dynamically serve app launcher logo as PNG
                get("/logo.png") {
                    withContext(Dispatchers.IO) {
                        try {
                            val pm = context.packageManager
                            val appIcon = pm.getApplicationIcon(context.packageName)
                            val bitmap = drawableToBitmap(appIcon)
                            val bos = ByteArrayOutputStream()
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, bos)
                            call.respondOutputStream(ContentType.Image.PNG) {
                                write(bos.toByteArray())
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed serving logo.png", e)
                            call.respondRedirect("/")
                        }
                    }
                }

                // Handle single file downloads
                get("/download/{index}") {
                    withContext(Dispatchers.IO) {
                        val sessionPin = call.request.cookies["session_pin"]
                        if (sessionPin != pin) {
                            call.respondRedirect("/")
                            return@withContext
                        }

                        val index = call.parameters["index"]?.toIntOrNull()
                        if (index == null || index < 0 || index >= filesToShare.size) {
                            call.respondRedirect("/")
                            return@withContext
                        }

                        val file = filesToShare[index]
                        if (!file.exists() || !file.isFile) {
                            call.respondRedirect("/")
                            return@withContext
                        }

                        val mimeType = MimeTypeMap.getSingleton()
                            .getMimeTypeFromExtension(file.extension.lowercase())
                            ?: "application/octet-stream"

                        call.response.header(
                            "Content-Disposition",
                            "attachment; filename=\"${file.name}\""
                        )

                        call.respondOutputStream(ContentType.parse(mimeType)) {
                            FileInputStream(file).use { input ->
                                input.copyTo(this)
                            }
                        }
                    }
                }

                // Download all files as a ZIP bundle
                get("/download/all") {
                    withContext(Dispatchers.IO) {
                        val sessionPin = call.request.cookies["session_pin"]
                        if (sessionPin != pin) {
                            call.respondRedirect("/")
                            return@withContext
                        }

                        call.response.header(
                            "Content-Disposition",
                            "attachment; filename=\"ufm_shared_files.zip\""
                        )

                        call.respondOutputStream(ContentType.Application.Zip) {
                            ZipOutputStream(this).use { zipOut ->
                                for (file in filesToShare) {
                                    if (file.exists() && file.isFile) {
                                        zipOut.putNextEntry(ZipEntry(file.name))
                                        FileInputStream(file).use { it.copyTo(zipOut) }
                                        zipOut.closeEntry()
                                    }
                                }
                            }
                        }
                    }
                }

                // PIN verification POST action
                post("/verify") {
                    val clientIp = call.request.local.remoteHost
                    val params = call.receiveParameters()
                    val enteredPin = params["pin"]

                    // Rate-limit check
                    when (checkRateLimit(clientIp)) {
                        RateLimitResult.Blocked -> {
                            if (BuildConfig.DEBUG) {
                                val entry = rateLimitMap[clientIp]
                                Log.i(TAG, "CRIT-2: Rate limit block applied to $clientIp (failure #${entry?.failures ?: "?"})")
                            }
                            call.respondText(
                                renderPinHtml(getLocalizedContext(context, "en"), false, true, "en"),
                                ContentType.Text.Html,
                                HttpStatusCode.TooManyRequests
                            )
                            return@post
                        }
                        RateLimitResult.Delayed -> {
                            if (BuildConfig.DEBUG) {
                                val entry = rateLimitMap[clientIp]
                                Log.i(TAG, "CRIT-2: Rate limit delay applied to $clientIp (failure #${entry?.failures ?: "?"})")
                            }
                            delay(RATE_LIMIT_DELAY_MS)
                        }
                        RateLimitResult.Allowed -> { /* proceed */ }
                    }

                    if (enteredPin == pin) {
                        resetRateLimit(clientIp)
                        if (BuildConfig.DEBUG) Log.i(TAG, "CRIT-2: Rate limit reset for $clientIp (successful PIN)")
                        call.response.cookies.append(
                            name = "session_pin",
                            value = pin,
                            path = "/",
                            maxAge = 7200L, // 2 hours validity
                            httpOnly = true
                        )
                        call.respondRedirect("/")
                    } else {
                        recordFailure(clientIp)
                        call.respondRedirect("/?error=1")
                    }
                }

                // Root URL endpoint (PIN verification or download list)
                get("/") {
                    val lang = call.request.cookies["ufm_lang"] ?: "en"
                    val localizedCtx = getLocalizedContext(context, lang)
                    val sessionPin = call.request.cookies["session_pin"]
                    if (sessionPin == pin) {
                        call.respondText(renderFilesHtml(localizedCtx, lang), ContentType.Text.Html)
                    } else {
                        val clientIp = call.request.local.remoteHost
                        if (isIpBlocked(clientIp)) {
                            call.respondText(
                                renderPinHtml(localizedCtx, false, true, lang),
                                ContentType.Text.Html,
                                HttpStatusCode.TooManyRequests
                            )
                        } else {
                            val isError = call.request.queryParameters["error"] == "1"
                            call.respondText(renderPinHtml(localizedCtx, isError, false, lang), ContentType.Text.Html)
                        }
                    }
                }
            }
        }
        
        try {
            server = serverInstance
            serverInstance.start(wait = false)
        } catch (e: LinkageError) {
            // VerifyError / ExceptionInInitializerError / NoClassDefFoundError from the embedded
            // Ktor/Netty engine boot (Netty's PlatformDependent can be rejected by the stricter ART
            // verifier on some devices, e.g. Android 16 / API 36). Called from the UI thread, so a
            // failure here must degrade gracefully instead of crashing the activity.
            server = null
            Log.e(TAG, "WebShare Ktor/Netty server failed to start (device verifier rejected Netty bytecode)", e)
            return ""
        }

        // Start SSL proxy if certificate was generated
        if (sslContext != null) {
            val factory = sslContext.serverSocketFactory
            sslServerSocket = factory.createServerSocket() as SSLServerSocket
            sslServerSocket!!.reuseAddress = true
            sslServerSocket!!.bind(java.net.InetSocketAddress(localIp, sslPort))
            Thread {
                try {
                    while (true) {
                        val sock = sslServerSocket?.accept() ?: break
                        Thread {
                            try {
                                val ktorSocket = java.net.Socket("127.0.0.1", ktorPort)
                                val fromClient = sock.getInputStream()
                                val toKtor = ktorSocket.getOutputStream()
                                val fromKtor = ktorSocket.getInputStream()
                                val toClient = sock.getOutputStream()
                                val t1 = Thread { try { fromClient.copyTo(toKtor) } catch (_: Exception) {} }
                                val t2 = Thread { try { fromKtor.copyTo(toClient) } catch (_: Exception) {} }
                                t1.start(); t2.start()
                                t1.join(); t2.join()
                                ktorSocket.close()
                            } catch (_: Exception) {}
                            try { sock.close() } catch (_: Exception) {}
                        }.start()
                    }
                } catch (_: Exception) {}
            }.apply { isDaemon = true; name = "WebShare-SSL-Proxy" }.start()
        }

        port = if (sslPort > 0) sslPort else ktorPort
        return if (sslPort > 0) "https://$localIp:$sslPort" else "http://$localIp:$ktorPort"
    }

    /**
     * Stops the Ktor web server and cleans up temporary files if indicated.
     */
    fun stop() {
        try {
            sslServerSocket?.close()
            sslServerSocket = null
            server?.stop(1000, 2000)
            server = null
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping web server: ${e.message}")
        }
        
        if (cleanUpOnStop) {
            for (file in filesToShare) {
                try {
                    if (file.exists()) {
                        file.delete()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error deleting temporary file: ${file.name}")
                }
            }
        }
        filesToShare = emptyList()
        appContext = null
    }

    /**
     * Renders the PIN prompt screen.
     */
    private fun renderPinHtml(ctx: Context, showError: Boolean, showRateLimit: Boolean, lang: String): String {
        val title = ctx.getString(R.string.web_portal_html_title)
        val header = ctx.getString(R.string.web_portal_html_header)
        val deviceName = Build.MODEL
        val subtitle = ctx.getString(R.string.web_portal_html_subtitle, deviceName)
        val pinPrompt = ctx.getString(R.string.web_portal_html_enter_pin)
        val btnText = ctx.getString(R.string.web_portal_html_verify)
        val errorMsg = if (showError) ctx.getString(R.string.web_portal_html_invalid_pin) else ""
        val rateLimitMsg = if (showRateLimit) ctx.getString(R.string.web_portal_html_rate_limited) else ""

        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>${escape(title)}</title>
                <link href="https://fonts.googleapis.com/css2?family=Outfit:wght@300;400;600;800&display=swap" rel="stylesheet">
                <style>
                    * { box-sizing: border-box; margin: 0; padding: 0; }
                    body {
                        font-family: 'Outfit', sans-serif;
                        background: linear-gradient(135deg, #0b0f19 0%, #111827 50%, #1e1b4b 100%);
                        background-attachment: fixed;
                        color: #f3f4f6;
                        min-height: 100vh;
                        display: flex;
                        align-items: center;
                        justify-content: center;
                        padding: 20px;
                    }
                    /* Language Selector */
                    .lang-selector {
                        position: absolute;
                        top: 24px;
                        right: 24px;
                        z-index: 1000;
                    }
                    .lang-btn {
                        background: rgba(255, 255, 255, 0.05);
                        backdrop-filter: blur(10px);
                        -webkit-backdrop-filter: blur(10px);
                        border: 1px solid rgba(255, 255, 255, 0.1);
                        border-radius: 30px;
                        padding: 8px 16px;
                        color: white;
                        font-weight: 600;
                        font-size: 13px;
                        cursor: pointer;
                        display: flex;
                        align-items: center;
                        gap: 8px;
                        transition: all 0.2s;
                    }
                    .lang-btn:hover {
                        background: rgba(255, 255, 255, 0.1);
                    }
                    .lang-dropdown {
                        display: none;
                        position: absolute;
                        top: calc(100% + 8px);
                        right: 0;
                        background: #111827;
                        border: 1px solid rgba(255, 255, 255, 0.08);
                        border-radius: 12px;
                        box-shadow: 0 10px 25px rgba(0, 0, 0, 0.5);
                        width: 180px;
                        overflow-y: auto;
                        max-height: 400px;
                        z-index: 1001;
                    }
                    .lang-dropdown.show {
                        display: block;
                    }
                    .lang-item {
                        padding: 12px 16px;
                        display: flex;
                        align-items: center;
                        gap: 12px;
                        cursor: pointer;
                        font-size: 14px;
                        color: #9ca3af;
                        transition: all 0.2s;
                        text-align: left;
                    }
                    .lang-item:hover {
                        background: rgba(255, 255, 255, 0.05);
                        color: white;
                    }
                    .lang-item.active {
                        color: #ffcc00;
                        background: rgba(255, 204, 0, 0.1);
                    }
                    .lang-flag {
                        width: 20px;
                        height: 15px;
                        object-fit: cover;
                        border-radius: 2px;
                        box-shadow: 0 1px 2px rgba(0,0,0,0.2);
                    }
                    @media (max-width: 768px) {
                        .lang-selector {
                            top: 12px;
                            right: 12px;
                        }
                        .lang-btn {
                            padding: 6px 12px;
                            font-size: 11px;
                        }
                    }
                    .container {
                        width: 100%;
                        max-width: 440px;
                        background: rgba(255, 255, 255, 0.03);
                        backdrop-filter: blur(20px);
                        -webkit-backdrop-filter: blur(20px);
                        border: 1px solid rgba(255, 255, 255, 0.08);
                        border-radius: 24px;
                        padding: 40px 30px;
                        box-shadow: 0 20px 50px rgba(0, 0, 0, 0.4);
                        text-align: center;
                    }
                    .logo-badge {
                        width: 84px;
                        height: 84px;
                        background: rgba(255, 255, 255, 0.04);
                        border: 1px solid rgba(255, 255, 255, 0.08);
                        border-radius: 50%;
                        display: flex;
                        align-items: center;
                        justify-content: center;
                        margin: 0 auto 24px;
                        box-shadow: 0 10px 25px rgba(0, 0, 0, 0.25);
                    }
                    .logo-badge img {
                        width: 48px;
                        height: 48px;
                        object-fit: contain;
                    }
                    h1 {
                        font-size: 24px;
                        font-weight: 800;
                        background: linear-gradient(to right, #ffcc00, #ff9900);
                        -webkit-background-clip: text;
                        -webkit-text-fill-color: transparent;
                        margin-bottom: 6px;
                    }
                    .subtitle {
                        font-size: 14px;
                        color: #9ca3af;
                        margin-bottom: 30px;
                        font-weight: 400;
                    }
                    .input-group {
                        position: relative;
                        margin-bottom: 24px;
                    }
                    .pin-input {
                        width: 100%;
                        padding: 16px;
                        background: rgba(0, 0, 0, 0.25);
                        border: 1.5px solid rgba(255, 255, 255, 0.1);
                        border-radius: 14px;
                        color: #fff;
                        font-size: 24px;
                        text-align: center;
                        letter-spacing: 12px;
                        text-indent: 12px;
                        font-weight: 700;
                        outline: none;
                        transition: all 0.3s ease;
                    }
                    .pin-input:focus {
                        border-color: #ffcc00;
                        background: rgba(0, 0, 0, 0.4);
                        box-shadow: 0 0 15px rgba(255, 204, 0, 0.2);
                    }
                    .btn {
                        width: 100%;
                        padding: 16px;
                        background: linear-gradient(135deg, #ffcc00 0%, #ff9900 100%);
                        border: none;
                        border-radius: 14px;
                        color: #0b0f19;
                        font-size: 16px;
                        font-weight: 700;
                        cursor: pointer;
                        transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
                        box-shadow: 0 8px 20px rgba(255, 204, 0, 0.25);
                    }
                    .btn:hover {
                        transform: translateY(-2px);
                        box-shadow: 0 12px 25px rgba(255, 204, 0, 0.4);
                    }
                    .btn:active {
                        transform: translateY(1px);
                    }
                    .error-msg {
                        color: #f87171;
                        font-size: 13px;
                        margin-top: 14px;
                        font-weight: 500;
                    }
                </style>
            </head>
            <body>
                ${renderLangSelectorHtml(lang)}
                <div class="container">
                    <div class="logo-badge">
                        <img src="/logo.png" alt="Logo">
                    </div>
                    <h1>${escape(header)}</h1>
                    <div class="subtitle">${escape(subtitle)}</div>
                    
                    ${if (showRateLimit) """
                    <div class="error-msg" style="text-align:center; padding: 20px; font-size: 16px;">${escape(rateLimitMsg)}</div>
                    """ else """
                    <form action="/verify" method="post">
                        <div class="input-group">
                            <input class="pin-input" type="password" name="pin" maxlength="4" placeholder="••••" required autocomplete="off" autofocus>
                        </div>
                        <button type="submit" class="btn">${escape(btnText)}</button>
                    </form>
                    ${if (showError) "<div class=\"error-msg\">" + escape(errorMsg) + "</div>" else ""}
                    ${if (certFingerprint.isNotEmpty()) """
                    <div style="text-align:center; padding-top: 12px; opacity: 0.4; font-size: 11px;">
                        TLS SHA256: ${certFingerprint}
                    </div>
                    """.trimIndent() else ""}
                    """}
                </div>
            </body>
            </html>
        """.trimIndent()
    }

    /**
     * Renders the premium download list screen.
     */
    private fun renderFilesHtml(ctx: Context, lang: String): String {
        val title = ctx.getString(R.string.web_portal_html_title)
        val header = ctx.getString(R.string.web_portal_html_header)
        val deviceName = Build.MODEL
        val subtitle = ctx.getString(R.string.web_portal_html_subtitle, deviceName)
        val listHeader = ctx.getString(R.string.web_portal_html_download_list)
        val dlText = ctx.getString(R.string.web_portal_html_download_button)
        val dlAllText = ctx.getString(R.string.web_portal_html_download_all)
        val emptyText = ctx.getString(R.string.web_portal_html_empty_list)
        val noticeText = ctx.getString(R.string.web_portal_html_destination_notice)

        val fileListHtml = StringBuilder()
        if (filesToShare.isEmpty()) {
            fileListHtml.append("<div style=\"text-align:center; padding: 20px; color:#9ca3af;\">${escape(emptyText)}</div>")
        } else {
            for (i in filesToShare.indices) {
                val file = filesToShare[i]
                val sizeStr = Formatter.formatFileSize(ctx, file.length())
                fileListHtml.append("""
                    <div class="file-item">
                        <div class="file-info">
                            <div class="file-name" title="${escape(file.name)}">${escape(file.name)}</div>
                            <div class="file-size">$sizeStr</div>
                        </div>
                        <a href="/download/$i" class="btn-download">${escape(dlText)}</a>
                    </div>
                """.trimIndent())
            }
        }

        val showDownloadAll = filesToShare.size > 1

        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>${escape(title)}</title>
                <link href="https://fonts.googleapis.com/css2?family=Outfit:wght@300;400;600;800&display=swap" rel="stylesheet">
                <style>
                    * { box-sizing: border-box; margin: 0; padding: 0; }
                    body {
                        font-family: 'Outfit', sans-serif;
                        background: linear-gradient(135deg, #0b0f19 0%, #111827 50%, #1e1b4b 100%);
                        background-attachment: fixed;
                        color: #f3f4f6;
                        min-height: 100vh;
                        display: flex;
                        align-items: center;
                        justify-content: center;
                        padding: 20px;
                    }
                    /* Language Selector */
                    .lang-selector {
                        position: absolute;
                        top: 24px;
                        right: 24px;
                        z-index: 1000;
                    }
                    .lang-btn {
                        background: rgba(255, 255, 255, 0.05);
                        backdrop-filter: blur(10px);
                        -webkit-backdrop-filter: blur(10px);
                        border: 1px solid rgba(255, 255, 255, 0.1);
                        border-radius: 30px;
                        padding: 8px 16px;
                        color: white;
                        font-weight: 600;
                        font-size: 13px;
                        cursor: pointer;
                        display: flex;
                        align-items: center;
                        gap: 8px;
                        transition: all 0.2s;
                    }
                    .lang-btn:hover {
                        background: rgba(255, 255, 255, 0.1);
                    }
                    .lang-dropdown {
                        display: none;
                        position: absolute;
                        top: calc(100% + 8px);
                        right: 0;
                        background: #111827;
                        border: 1px solid rgba(255, 255, 255, 0.08);
                        border-radius: 12px;
                        box-shadow: 0 10px 25px rgba(0, 0, 0, 0.5);
                        width: 180px;
                        overflow-y: auto;
                        max-height: 400px;
                        z-index: 1001;
                    }
                    .lang-dropdown.show {
                        display: block;
                    }
                    .lang-item {
                        padding: 12px 16px;
                        display: flex;
                        align-items: center;
                        gap: 12px;
                        cursor: pointer;
                        font-size: 14px;
                        color: #9ca3af;
                        transition: all 0.2s;
                        text-align: left;
                    }
                    .lang-item:hover {
                        background: rgba(255, 255, 255, 0.05);
                        color: white;
                    }
                    .lang-item.active {
                        color: #ffcc00;
                        background: rgba(255, 204, 0, 0.1);
                    }
                    .lang-flag {
                        width: 20px;
                        height: 15px;
                        object-fit: cover;
                        border-radius: 2px;
                        box-shadow: 0 1px 2px rgba(0,0,0,0.2);
                    }
                    @media (max-width: 768px) {
                        .lang-selector {
                            top: 12px;
                            right: 12px;
                        }
                        .lang-btn {
                            padding: 6px 12px;
                            font-size: 11px;
                        }
                    }
                    .container {
                        width: 100%;
                        max-width: 500px;
                        background: rgba(255, 255, 255, 0.03);
                        backdrop-filter: blur(20px);
                        -webkit-backdrop-filter: blur(20px);
                        border: 1px solid rgba(255, 255, 255, 0.08);
                        border-radius: 24px;
                        padding: 35px 25px;
                        box-shadow: 0 20px 50px rgba(0, 0, 0, 0.4);
                        text-align: center;
                    }
                    .logo-badge {
                        width: 72px;
                        height: 72px;
                        background: rgba(255, 255, 255, 0.04);
                        border: 1px solid rgba(255, 255, 255, 0.08);
                        border-radius: 50%;
                        display: flex;
                        align-items: center;
                        justify-content: center;
                        margin: 0 auto 16px;
                        box-shadow: 0 10px 25px rgba(0, 0, 0, 0.25);
                    }
                    .logo-badge img {
                        width: 40px;
                        height: 40px;
                        object-fit: contain;
                    }
                    h1 {
                        font-size: 22px;
                        font-weight: 800;
                        background: linear-gradient(to right, #ffcc00, #ff9900);
                        -webkit-background-clip: text;
                        -webkit-text-fill-color: transparent;
                        margin-bottom: 4px;
                    }
                    .subtitle {
                        font-size: 13px;
                        color: #9ca3af;
                        margin-bottom: 24px;
                        font-weight: 400;
                    }
                    h2 {
                        font-size: 16px;
                        font-weight: 600;
                        text-align: left;
                        margin-bottom: 12px;
                        color: #f3f4f6;
                        border-left: 3px solid #ffcc00;
                        padding-left: 8px;
                    }
                    .file-list {
                        text-align: left;
                        max-height: 320px;
                        overflow-y: auto;
                        padding-right: 5px;
                        margin-bottom: 20px;
                    }
                    .file-list::-webkit-scrollbar {
                        width: 6px;
                    }
                    .file-list::-webkit-scrollbar-track {
                        background: rgba(255, 255, 255, 0.01);
                        border-radius: 10px;
                    }
                    .file-list::-webkit-scrollbar-thumb {
                        background: rgba(255, 255, 255, 0.1);
                        border-radius: 10px;
                    }
                    .file-item {
                        display: flex;
                        align-items: center;
                        justify-content: space-between;
                        padding: 12px 14px;
                        background: rgba(255, 255, 255, 0.02);
                        border: 1px solid rgba(255, 255, 255, 0.04);
                        border-radius: 14px;
                        margin-bottom: 10px;
                        transition: all 0.2s ease;
                    }
                    .file-item:hover {
                        background: rgba(255, 255, 255, 0.04);
                        border-color: rgba(255, 255, 255, 0.08);
                    }
                    .file-info {
                        display: flex;
                        flex-direction: column;
                        max-width: 70%;
                    }
                    .file-name {
                        font-size: 13.5px;
                        font-weight: 600;
                        white-space: nowrap;
                        overflow: hidden;
                        text-overflow: ellipsis;
                        color: #f3f4f6;
                    }
                    .file-size {
                        font-size: 11.5px;
                        color: #9ca3af;
                        margin-top: 3px;
                    }
                    .btn-download {
                        background: rgba(255, 255, 255, 0.06);
                        border: 1px solid rgba(255, 255, 255, 0.1);
                        color: #fff;
                        padding: 8px 12px;
                        border-radius: 10px;
                        font-size: 11.5px;
                        font-weight: 600;
                        text-decoration: none;
                        transition: all 0.2s ease;
                    }
                    .btn-download:hover {
                        background: #ffcc00;
                        border-color: #ffcc00;
                        color: #0b0f19;
                        box-shadow: 0 4px 10px rgba(255, 204, 0, 0.2);
                    }
                    .btn-download-all {
                        display: inline-flex;
                        align-items: center;
                        justify-content: center;
                        margin-bottom: 20px;
                        background: linear-gradient(135deg, rgba(255, 204, 0, 0.15) 0%, rgba(255, 153, 0, 0.15) 100%);
                        border: 1px solid rgba(255, 204, 0, 0.25);
                        color: #ffcc00;
                        padding: 12px 20px;
                        border-radius: 12px;
                        font-size: 14px;
                        font-weight: 600;
                        text-decoration: none;
                        width: 100%;
                        transition: all 0.3s ease;
                    }
                    .btn-download-all:hover {
                        background: linear-gradient(135deg, #ffcc00 0%, #ff9900 100%);
                        color: #0b0f19;
                        box-shadow: 0 6px 15px rgba(255, 204, 0, 0.25);
                    }
                    .notice {
                        font-size: 11.5px;
                        color: #6b7280;
                        margin-top: 24px;
                        line-height: 1.5;
                        padding: 10px;
                        background: rgba(255, 255, 255, 0.01);
                        border-radius: 10px;
                        border: 1px dashed rgba(255, 255, 255, 0.05);
                    }
                </style>
            </head>
            <body>
                ${renderLangSelectorHtml(lang)}
                <div class="container">
                    <div class="logo-badge">
                        <img src="/logo.png" alt="Logo">
                    </div>
                    <h1>${escape(header)}</h1>
                    <div class="subtitle">${escape(subtitle)}</div>
                    
                    ${if (showDownloadAll) "<a href=\"/download/all\" class=\"btn-download-all\">" + escape(dlAllText) + "</a>" else ""}
                    
                    <h2>${escape(listHeader)}</h2>
                    <div class="file-list">
                        $fileListHtml
                    </div>
                    
                    <div class="notice">
                        ${escape(noticeText)}
                    </div>
                </div>
            </body>
            </html>
        """.trimIndent()
    }

    private fun renderLangSelectorHtml(currentLang: String): String {
        val options = listOf(
            Triple("en", "English", "gb"),
            Triple("pt", "Português", "br"),
            Triple("de", "Deutsch", "de"),
            Triple("es", "Español", "es"),
            Triple("hi", "हिन्दी", "in"),
            Triple("id", "Indonesia", "id"),
            Triple("ar", "العربية", "sa"),
            Triple("ru", "Русский", "ru"),
            Triple("fr", "Français", "fr"),
            Triple("it", "Italiano", "it"),
            Triple("nl", "Nederlands", "nl"),
            Triple("tr", "Türkçe", "tr"),
            Triple("ja", "日本語", "jp"),
            Triple("ko", "한국어", "kr"),
            Triple("zh", "中文", "cn"),
            Triple("sv", "Svenska", "se"),
            Triple("uk", "Українська", "ua")
        )
        val currentOption = options.find { it.first == currentLang } ?: options.first()
        val currentName = currentOption.second
        val currentFlag = currentOption.third

        val sb = java.lang.StringBuilder()
        sb.append("""
            <div class="lang-selector">
                <button class="lang-btn" onclick="toggleLangDropdown()">
                    <img src="/api/flags/$currentFlag.svg" id="currentLangFlag" class="lang-flag">
                    <span id="currentLangName">$currentName</span>
                </button>
                <div class="lang-dropdown" id="langDropdown">
        """.trimIndent())
        
        for ((code, name, flag) in options) {
            val activeClass = if (code == currentLang) " active" else ""
            sb.append("<div class=\"lang-item$activeClass\" onclick=\"setLanguage('$code')\"><img src=\"/api/flags/$flag.svg\" class=\"lang-flag\"> $name</div>")
        }
        
        sb.append("""
                </div>
            </div>
            <script>
                function toggleLangDropdown() {
                    document.getElementById('langDropdown').classList.toggle('show');
                }
                window.onclick = function(event) {
                    if (!event.target.closest('.lang-selector')) {
                        var dropdown = document.getElementById('langDropdown');
                        if (dropdown && dropdown.classList.contains('show')) {
                            dropdown.classList.remove('show');
                        }
                    }
                }
                function setLanguage(lang) {
                    document.cookie = "ufm_lang=" + lang + "; path=/; max-age=31536000";
                    window.location.reload();
                }
            </script>
        """.trimIndent())
        return sb.toString()
    }

    /**
     * Escape HTML characters.
     */
    private fun escape(s: String): String {
        val out = StringBuilder()
        for (i in s.indices) {
            val c = s[i]
            if (c.code > 127 || c == '"' || c == '<' || c == '>' || c == '&' || c == '\'') {
                out.append("&#")
                out.append(c.code)
                out.append(';')
            } else {
                out.append(c)
            }
        }
        return out.toString()
    }
}
