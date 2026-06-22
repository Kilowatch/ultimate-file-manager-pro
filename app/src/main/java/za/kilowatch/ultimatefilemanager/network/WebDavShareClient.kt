package za.kilowatch.ultimatefilemanager.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import za.kilowatch.ultimatefilemanager.UfmApplication
import za.kilowatch.ultimatefilemanager.util.GoRoLog
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.StringReader
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * WebDAV client built on OkHttp — no third-party WebDAV library required.
 *
 * Supports: PROPFIND (list), MKCOL (mkdir), GET (download), PUT (upload),
 *           DELETE (delete), MOVE (rename).
 *
 * HTTP routing: identical to S3ShareClient — plain http:// URLs are routed
 * through localhost DNS override to satisfy Android cleartext restrictions.
 *
 * NetworkShare field mapping:
 *   host        → full WebDAV base URL (e.g. "https://cloud.example.com/remote.php/dav/files/user/")
 *   username    → WebDAV username (empty = anonymous)
 *   password    → WebDAV password (decrypted by repo before being placed in share)
 */
object WebDavShareClient {

    private const val TAG = "WebDavShareClient"
    private const val OCTET_STREAM = "application/octet-stream"
    private const val DAV_NS = "DAV:"

    // Thread-local used by the HTTP-over-localhost DNS trick (same pattern as S3ShareClient)
    private val targetIpThreadLocal = ThreadLocal<String>()

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0,  TimeUnit.SECONDS)
            .writeTimeout(0, TimeUnit.SECONDS)
            .dns(object : okhttp3.Dns {
                override fun lookup(hostname: String): List<java.net.InetAddress> {
                    if (hostname == "localhost") {
                        val overrideIp = targetIpThreadLocal.get()
                        if (overrideIp != null) {
                            try { return listOf(java.net.InetAddress.getByName(overrideIp)) }
                            catch (_: Exception) {}
                        }
                    }
                    return okhttp3.Dns.SYSTEM.lookup(hostname)
                }
            })
            .addInterceptor { chain ->
                val req = chain.request()
                if (!req.url.isHttps) {
                    targetIpThreadLocal.set(req.url.host)
                    try {
                        val originalHost = req.url.host +
                            if (req.url.port != 80 && req.url.port != 443) ":${req.url.port}" else ""
                        val newUrl = req.url.newBuilder().host("localhost").build()
                        val newReq = req.newBuilder()
                            .url(newUrl)
                            .header("Host", req.header("host") ?: req.header("Host") ?: originalHost)
                            .build()
                        return@addInterceptor chain.proceed(newReq)
                    } finally {
                        targetIpThreadLocal.remove()
                    }
                }
                chain.proceed(req)
            }
            .build()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Tests the connection by sending a PROPFIND Depth:0 to the base URL.
     * Returns true if the server responds with 207 Multi-Status.
     */
    suspend fun testConnection(share: NetworkShare): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = buildRequest("PROPFIND", resolveUrl(share, ""), share)
                .addHeader("Depth", "0")
                .addHeader("Content-Type", "application/xml")
                .method("PROPFIND", PROPFIND_BODY.toRequestBody("application/xml".toMediaType()))
                .build()
            client.newCall(request).execute().use { it.code == 207 }
        } catch (e: Exception) {
            GoRoLog.e(TAG, "testConnection failed", e)
            false
        }
    }

    suspend fun listFiles(share: NetworkShare, remotePath: String): List<NetworkFile> =
        withContext(Dispatchers.IO) {
            val url = resolveUrl(share, remotePath)
            // shareRootPath is the server-path prefix that belongs to the share base URL.
            // e.g. share.host = "http://host:8081/seafdav/"  → shareRootPath = "/seafdav"
            // This must be stripped from every PROPFIND href to get a path relative to the share.
            val shareRootPath = try {
                java.net.URI(share.host.trimEnd('/')).path.trimEnd('/')
            } catch (_: Exception) { "" }
            val request = buildRequest("PROPFIND", url, share)
                .addHeader("Depth", "1")
                .addHeader("Content-Type", "application/xml")
                .method("PROPFIND", PROPFIND_BODY.toRequestBody("application/xml".toMediaType()))
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (response.code != 207) {
                    GoRoLog.e(TAG, "listFiles failed (${response.code}): $body")
                    throw IOException("WebDAV PROPFIND failed (${response.code})")
                }
                parsePropfindResponse(body, url, shareRootPath)
            }
        }

    suspend fun mkdir(share: NetworkShare, remotePath: String) = withContext(Dispatchers.IO) {
        val url = resolveUrl(share, remotePath).trimEnd('/') + "/"
        GoRoLog.i(TAG, "mkdir: creating collection at $url")
        val request = buildRequest("MKCOL", url, share)
            .method("MKCOL", null)
            .build()
        client.newCall(request).execute().use { response ->
            GoRoLog.i(TAG, "mkdir response: ${response.code} for $url")
            if (!response.isSuccessful && response.code != 405) {   // 405 = already exists
                throw IOException("WebDAV MKCOL failed (${response.code})")
            }
        }
    }

    suspend fun deleteFile(share: NetworkShare, remotePath: String) = withContext(Dispatchers.IO) {
        val url = resolveUrl(share, remotePath)
        val request = buildRequest("DELETE", url, share).delete().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful && response.code != 404) {
                throw IOException("WebDAV DELETE failed (${response.code})")
            }
        }
    }

    suspend fun deleteDir(share: NetworkShare, remotePath: String) =
        deleteFile(share, remotePath.trimEnd('/') + "/")   // WebDAV DELETE is recursive for collections

    suspend fun rename(share: NetworkShare, fromPath: String, toPath: String) =
        withContext(Dispatchers.IO) {
            val fromUrl = resolveUrl(share, fromPath)
            val toUrl   = resolveUrl(share, toPath)
            val request = buildRequest("MOVE", fromUrl, share)
                .addHeader("Destination", toUrl)
                .addHeader("Overwrite", "T")
                .method("MOVE", null)
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("WebDAV MOVE failed (${response.code})")
                }
            }
        }

    suspend fun openInputStream(share: NetworkShare, remotePath: String): Pair<InputStream, Long> =
        withContext(Dispatchers.IO) {
            openInputStreamInternal(share, remotePath, null).let { response ->
                val length = response.header("Content-Length")?.toLongOrNull() ?: -1L
                Pair(response.body!!.byteStream(), length)
            }
        }

    suspend fun openInputStreamForStreaming(
        share: NetworkShare,
        remotePath: String,
        rangeHeader: String?
    ): okhttp3.Response = withContext(Dispatchers.IO) {
        openInputStreamInternal(share, remotePath, rangeHeader)
    }

    fun openInputStreamForStreamingSync(
        share: NetworkShare,
        remotePath: String,
        rangeHeader: String?
    ): okhttp3.Response = openInputStreamInternal(share, remotePath, rangeHeader)

    fun getFileSizeSync(share: NetworkShare, remotePath: String): Long {
        val url     = resolveUrl(share, remotePath)
        val request = buildRequest("HEAD", url, share).head().build()
        client.newCall(request).execute().use { response ->
            return response.header("Content-Length")?.toLongOrNull() ?: 0L
        }
    }

    /**
     * Opens a seekable, random-access handle to a remote WebDAV file.
     *
     * Uses HTTP Range headers to read arbitrary byte ranges — identical pattern
     * to [S3ShareClient.openRandomAccessFile]. Each read() call performs a fresh
     * HTTP GET with a Range header; no persistent connection is held open.
     *
     * @param share      The WebDAV share configuration.
     * @param remotePath The remote file path.
     * @return An [IRandomAccessFile] backed by stateless HTTP Range requests.
     */
    fun openRandomAccessFile(share: NetworkShare, remotePath: String): IRandomAccessFile {
        val fileSize = getFileSizeSync(share, remotePath)
        return object : IRandomAccessFile {
            override val size: Long = fileSize

            override fun read(offset: Long, buffer: ByteArray, length: Int): Int {
                if (offset >= size) return -1
                val rangeHeader = "bytes=$offset-${offset + length - 1}"
                val response = openInputStreamForStreamingSync(share, remotePath, rangeHeader)
                if (!response.isSuccessful) {
                    response.close()
                    throw IOException("WebDAV RAF read failed: ${response.code}")
                }
                val body = response.body ?: throw IOException("WebDAV RAF read failed: empty body (${response.code})")
                return body.byteStream().use { input ->
                    // If server ignored Range (200 instead of 206), skip to the requested offset
                    if (response.code == 200 && offset > 0) {
                        var remaining = offset
                        while (remaining > 0) {
                            val skipped = input.skip(remaining)
                            if (skipped <= 0) break
                            remaining -= skipped
                        }
                    }
                    var totalRead = 0
                    while (totalRead < length) {
                        val read = input.read(buffer, totalRead, length - totalRead)
                        if (read == -1) break
                        totalRead += read
                    }
                    if (totalRead == 0) -1 else totalRead
                }
            }

            override fun write(offset: Long, buffer: ByteArray, length: Int): Int =
                throw IOException("Random writes not supported on WebDAV")

            override fun close() { /* stateless HTTP — nothing to close */ }
        }
    }

    suspend fun uploadStream(
        share: NetworkShare,
        remotePath: String,
        inputStream: InputStream,
        totalSize: Long,
        onProgress: (Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        val url = resolveUrl(share, remotePath)
        GoRoLog.i(TAG, "uploadStream: PUT to $url, size=$totalSize")

        // Streaming RequestBody — reads directly from the InputStream without loading
        // everything into memory. Sends Content-Length so servers like Seafile WebDAV
        // don't reject chunked-transfer-encoding or unknown-length bodies.
        val body = object : RequestBody() {
            override fun contentType() = OCTET_STREAM.toMediaType()
            override fun contentLength() = totalSize
            override fun writeTo(sink: okio.BufferedSink) {
                val buf = ByteArray(8 * 1024)
                var written = 0L
                var n: Int
                while (inputStream.read(buf).also { n = it } != -1) {
                    sink.write(buf, 0, n)
                    written += n
                    onProgress(written)
                }
            }
        }

        val request = buildRequest("PUT", url, share).put(body).build()
        client.newCall(request).execute().use { response ->
            GoRoLog.i(TAG, "uploadStream response: ${response.code} for $url")
            if (!response.isSuccessful) {
                val errBody = try { response.body?.string() } catch (_: Exception) { "" }
                GoRoLog.e(TAG, "uploadStream failed (${response.code}): $errBody")
                throw IOException("WebDAV PUT failed (${response.code}): $errBody")
            }
        }
    }

    suspend fun openOutputStream(share: NetworkShare, remotePath: String): OutputStream =
        withContext(Dispatchers.IO) {
            val tempFile = File(UfmApplication.instance.cacheDir, "webdav_upload_${System.currentTimeMillis()}.tmp")
            object : OutputStream() {
                private val fileOut = FileOutputStream(tempFile)
                override fun write(b: Int)                     = fileOut.write(b)
                override fun write(b: ByteArray, off: Int, len: Int) = fileOut.write(b, off, len)
                override fun flush()                           = fileOut.flush()
                override fun close() {
                    fileOut.close()
                    runBlocking(Dispatchers.IO) {
                        try {
                            FileInputStream(tempFile).use { fis ->
                                uploadStream(share, remotePath, fis, tempFile.length()) {}
                            }
                        } finally {
                            tempFile.delete()
                        }
                    }
                }
            }
        }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun openInputStreamInternal(
        share: NetworkShare,
        remotePath: String,
        rangeHeader: String?
    ): okhttp3.Response {
        val url = resolveUrl(share, remotePath)
        val builder = buildRequest("GET", url, share).get()
        if (rangeHeader != null) builder.addHeader("Range", rangeHeader)
        val response = client.newCall(builder.build()).execute()
        if (!response.isSuccessful && response.code != 206) {
            val err = response.body?.string() ?: ""
            response.close()
            throw IOException("WebDAV GET failed (${response.code}): $err")
        }
        return response
    }

    /**
     * Builds a Request.Builder with Auth header (if credentials are set) and
     * any extra headers — does NOT call .build() so callers can chain more headers.
     */
    private fun buildRequest(method: String, url: String, share: NetworkShare): Request.Builder {
        val builder = Request.Builder().url(url)
        // Basic Auth — only if username is set (anonymous if empty)
        if (share.username.isNotBlank()) {
            val credentials = okhttp3.Credentials.basic(share.username, share.password)
            builder.addHeader("Authorization", credentials)
        }
        return builder
    }

    /**
     * Resolves a relative path against the share's base URL.
     *
     * share.host = "http://host:8081/" (the full base URL stored in OnlineStorage.webDavUrl)
     * remotePath = ""             → "http://host:8081/"
     * remotePath = "Documents"   → "http://host:8081/Documents/"
     * remotePath = "Documents/file.txt" → "http://host:8081/Documents/file.txt"
     */
    private fun resolveUrl(share: NetworkShare, remotePath: String): String {
        val base = share.host.trimEnd('/')
        if (remotePath.isBlank()) return "$base/"
        val encoded = remotePath.trimStart('/').split("/").joinToString("/") {
            URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        }
        return "$base/$encoded"
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PROPFIND XML parsing (using Android's built-in XmlPullParser)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Parses a WebDAV PROPFIND Depth:1 response into a list of [NetworkFile].
     *
     * @param xml          Raw XML body from the 207 response.
     * @param requestUrl   The URL that was PROPFIND-ed (used to skip the self-entry).
     * @param shareRootPath The path portion of the share's base URL (e.g. "/seafdav").
     *                     Every href returned by the server starts with this prefix;
     *                     stripping it yields a path relative to the share root.
     */
    private fun parsePropfindResponse(
        xml: String,
        requestUrl: String,
        shareRootPath: String = ""
    ): List<NetworkFile> {
        val files = mutableListOf<NetworkFile>()
        // Used only to detect and skip the Depth:0 self-entry.
        val selfPath = java.net.URI(requestUrl).path.trimEnd('/').lowercase()

        try {
            val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
            val parser  = factory.newPullParser()
            parser.setInput(StringReader(xml))

            // State per <D:response> block
            var inResponse   = false
            var href         = ""
            var isCollection = false
            var contentLength = 0L
            var lastModified  = 0L
            var inPropstat   = false
            var currentTag   = ""

            // Normalise shareRootPath: ensure no trailing slash, lowercase for comparison.
            val rootPrefix = shareRootPath.trimEnd('/').lowercase()

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                val tagName = parser.name?.substringAfterLast(':') ?: ""
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        currentTag = tagName
                        when (tagName) {
                            "response"      -> { inResponse = true; href = ""; isCollection = false; contentLength = 0L; lastModified = 0L }
                            "propstat"      -> inPropstat = true
                            "collection"    -> if (inResponse) isCollection = true
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (inResponse) {
                            when (currentTag) {
                                "href"             -> href = parser.text.trim()
                                "getcontentlength" -> contentLength = parser.text.trim().toLongOrNull() ?: 0L
                                "getlastmodified"  -> lastModified  = parseRfc1123(parser.text.trim())
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (tagName == "response" && inResponse) {
                            inResponse = false
                            inPropstat = false
                            val decodedHref = URLDecoder.decode(href, "UTF-8")
                            val hrefPath    = decodedHref.trimEnd('/')
                            val hrefLower   = hrefPath.lowercase()

                            // Skip the self-entry (the listed directory itself)
                            if (hrefLower != selfPath) {
                                val name = hrefPath.substringAfterLast('/')

                                // Strip the share's root prefix to get a path that is
                                // relative to the share root — NOT relative to the
                                // current listing folder.
                                //
                                // Example:
                                //   share root  = /seafdav
                                //   href        = /seafdav/My Library/Capture4.PNG
                                //   → relPath   = My Library/Capture4.PNG   ✓
                                //
                                // When browsing /seafdav/My Library:
                                //   href        = /seafdav/My Library/Capture4.PNG
                                //   → relPath   = My Library/Capture4.PNG   ✓ (same!)
                                val relPath = when {
                                    rootPrefix.isNotEmpty() &&
                                    hrefLower.startsWith("$rootPrefix/") ->
                                        hrefPath.substring(rootPrefix.length + 1)
                                    rootPrefix.isNotEmpty() &&
                                    hrefLower == rootPrefix -> "" // dir itself — skip
                                    else ->
                                        hrefPath.trimStart('/') // no prefix: use as-is
                                }

                                if (name.isNotEmpty() && relPath.isNotEmpty()) {
                                    files.add(
                                        NetworkFile(
                                            name         = name,
                                            path         = relPath,
                                            isDirectory  = isCollection,
                                            size         = contentLength,
                                            lastModified = lastModified
                                        )
                                    )
                                }
                            }
                        }
                        if (tagName == "propstat") inPropstat = false
                        currentTag = ""
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            GoRoLog.e(TAG, "parsePropfindResponse failed", e)
        }

        return files.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    /** Parses RFC 1123 date strings like "Mon, 27 Apr 2026 12:00:00 GMT" → epoch ms */
    private fun parseRfc1123(date: String): Long {
        return try {
            val fmt = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", java.util.Locale.US)
            fmt.parse(date)?.time ?: 0L
        } catch (_: Exception) { 0L }
    }

    // Minimal PROPFIND body — requests only the properties we need
    private val PROPFIND_BODY = """
        <?xml version="1.0" encoding="utf-8"?>
        <D:propfind xmlns:D="DAV:">
          <D:prop>
            <D:resourcetype/>
            <D:getcontentlength/>
            <D:getlastmodified/>
          </D:prop>
        </D:propfind>
    """.trimIndent()
}
