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
        if (RCloneShareClient.isRCloneShare(share)) return@withContext RCloneShareClient.testConnection(share)
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
            if (RCloneShareClient.isRCloneShare(share)) return@withContext RCloneShareClient.listFiles(share, remotePath)
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
        if (RCloneShareClient.isRCloneShare(share)) return@withContext RCloneShareClient.mkdir(share, remotePath)
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
        if (RCloneShareClient.isRCloneShare(share)) return@withContext RCloneShareClient.deleteFile(share, remotePath)
        val url = resolveUrl(share, remotePath)
        val request = buildRequest("DELETE", url, share).delete().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful && response.code != 404) {
                throw IOException("WebDAV DELETE failed (${response.code})")
            }
        }
    }

    suspend fun deleteDir(share: NetworkShare, remotePath: String) =
        if (RCloneShareClient.isRCloneShare(share)) RCloneShareClient.deleteDir(share, remotePath)
        else deleteFile(share, remotePath.trimEnd('/') + "/")   // WebDAV DELETE is recursive for collections

    suspend fun rename(share: NetworkShare, fromPath: String, toPath: String, isDirectory: Boolean = false) =
        withContext(Dispatchers.IO) {
            if (RCloneShareClient.isRCloneShare(share)) return@withContext RCloneShareClient.rename(share, fromPath, toPath, isDirectory)
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
            if (RCloneShareClient.isRCloneShare(share)) return@withContext RCloneShareClient.openInputStream(share, remotePath)
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
        if (RCloneShareClient.isRCloneShare(share)) throw IOException("RClone does not support range-streaming via okhttp3.Response")
        openInputStreamInternal(share, remotePath, rangeHeader)
    }

    fun openInputStreamForStreamingSync(
        share: NetworkShare,
        remotePath: String,
        rangeHeader: String?
    ): okhttp3.Response {
        if (RCloneShareClient.isRCloneShare(share)) throw IOException("RClone does not support range-streaming via okhttp3.Response")
        return openInputStreamInternal(share, remotePath, rangeHeader)
    }

    fun getFileSizeSync(share: NetworkShare, remotePath: String): Long {
        if (RCloneShareClient.isRCloneShare(share)) return RCloneShareClient.getFileSizeSync(share, remotePath)
        val url = resolveUrl(share, remotePath)

        // 1. Try HEAD request
        runCatching {
            val request = buildRequest("HEAD", url, share).head().build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val len = response.header("Content-Length")?.toLongOrNull()
                    if (len != null && len > 0) return len
                }
            }
        }

        // 2. Fallback: PROPFIND Depth:0 request targeting single file
        runCatching {
            val request = buildRequest("PROPFIND", url, share)
                .addHeader("Depth", "0")
                .addHeader("Content-Type", "application/xml")
                .method("PROPFIND", PROPFIND_BODY.toRequestBody("application/xml".toMediaType()))
                .build()
            client.newCall(request).execute().use { response ->
                if (response.code == 207) {
                    val body = response.body?.string() ?: ""
                    val shareRootPath = try {
                        java.net.URI(share.host.trimEnd('/')).path.trimEnd('/')
                    } catch (_: Exception) { "" }
                    val parsed = parsePropfindResponse(body, url, shareRootPath)
                    if (parsed.isNotEmpty() && parsed[0].size > 0) {
                        return parsed[0].size
                    }
                }
            }
        }

        // 3. Fallback: GET Range: bytes=0-0 to extract total size from Content-Range header
        runCatching {
            val request = buildRequest("GET", url, share)
                .addHeader("Range", "bytes=0-0")
                .build()
            client.newCall(request).execute().use { response ->
                val contentRange = response.header("Content-Range")
                if (contentRange != null && contentRange.contains("/")) {
                    val totalStr = contentRange.substringAfterLast('/')
                    val total = totalStr.toLongOrNull()
                    if (total != null && total > 0) return total
                }
                val len = response.header("Content-Length")?.toLongOrNull()
                if (len != null && len > 0) return len
            }
        }

        return 0L
    }

    /**
     * Opens a seekable, random-access handle to a remote WebDAV file.
     *
     * Maintains an active HTTP stream as long as sequential reads advance continuously.
     * If a seek or discontinuity occurs (currentPos != offset), the existing stream
     * is closed and a new HTTP GET Range stream is established.
     *
     * @param share      The WebDAV share configuration.
     * @param remotePath The remote file path.
     * @param knownSize  Optional known size of the file in bytes (if available).
     * @return An [IRandomAccessFile] backed by persistent HTTP Range streaming.
     */
    fun openRandomAccessFile(share: NetworkShare, remotePath: String, knownSize: Long = -1L): IRandomAccessFile {
        if (RCloneShareClient.isRCloneShare(share)) return RCloneShareClient.openRandomAccessFile(share, remotePath)
        val fileSize = if (knownSize > 0) knownSize else getFileSizeSync(share, remotePath)
        return object : IRandomAccessFile {
            override val size: Long = if (fileSize > 0) fileSize else Long.MAX_VALUE

            private var currentResponse: okhttp3.Response? = null
            private var currentInputStream: InputStream? = null
            private var currentPos: Long = -1L
            private val lock = Any()

            override fun read(offset: Long, buffer: ByteArray, length: Int): Int = synchronized(lock) {
                if (offset >= size) return -1
                if (length <= 0) return 0

                // If offset does not match current stream position, open (or seek) stream
                if (currentInputStream == null || currentPos != offset) {
                    closeCurrentStream()
                    val rangeHeader = "bytes=$offset-"
                    val response = try {
                        openInputStreamForStreamingSync(share, remotePath, rangeHeader)
                    } catch (e: Exception) {
                        GoRoLog.e(TAG, "Failed to open stream at offset $offset for $remotePath", e)
                        return -1
                    }
                    if (!response.isSuccessful && response.code != 206) {
                        response.close()
                        GoRoLog.e(TAG, "Stream failed with code ${response.code} at offset $offset")
                        return -1
                    }
                    val body = response.body
                    currentResponse = response
                    val input = body.byteStream()
                    currentInputStream = input
                    currentPos = offset

                    // If server ignored Range (200 instead of 206) and returned full body starting at 0:
                    if (response.code == 200 && offset > 0) {
                        var remaining = offset
                        val skipBuf = ByteArray(8192)
                        while (remaining > 0) {
                            val toRead = minOf(remaining, skipBuf.size.toLong()).toInt()
                            val skipped = try { input.read(skipBuf, 0, toRead) } catch (_: Exception) { -1 }
                            if (skipped <= 0) break
                            remaining -= skipped
                        }
                    }
                }

                val input = currentInputStream ?: return -1
                val bytesToRead = minOf(length.toLong(), size - offset).toInt()
                if (bytesToRead <= 0) return -1

                var totalRead = 0
                while (totalRead < bytesToRead) {
                    val read = try {
                        input.read(buffer, totalRead, bytesToRead - totalRead)
                    } catch (e: Exception) {
                        GoRoLog.e(TAG, "Stream read error at ${currentPos + totalRead}", e)
                        closeCurrentStream()
                        return if (totalRead > 0) {
                            currentPos += totalRead
                            totalRead
                        } else -1
                    }
                    if (read <= 0) break
                    totalRead += read
                }

                if (totalRead > 0) {
                    currentPos += totalRead
                    return totalRead
                } else {
                    closeCurrentStream()
                    return -1
                }
            }

            override fun write(offset: Long, buffer: ByteArray, length: Int): Int =
                throw IOException("Random writes not supported on WebDAV")

            override fun close() = synchronized(lock) {
                closeCurrentStream()
            }

            private fun closeCurrentStream() {
                runCatching { currentInputStream?.close() }
                runCatching { currentResponse?.close() }
                currentInputStream = null
                currentResponse = null
                currentPos = -1L
            }
        }
    }

    suspend fun uploadStream(
        share: NetworkShare,
        remotePath: String,
        inputStream: InputStream,
        totalSize: Long,
        onProgress: (Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        if (RCloneShareClient.isRCloneShare(share)) return@withContext RCloneShareClient.uploadStream(share, remotePath, inputStream, totalSize)
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
            if (RCloneShareClient.isRCloneShare(share)) return@withContext RCloneShareClient.openOutputStream(share, remotePath)
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
                val tagName = parser.name?.substringAfterLast(':')?.lowercase() ?: ""
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
