package za.kilowatch.ultimatefilemanager.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.UfmApplication
import za.kilowatch.ultimatefilemanager.util.GoRoLog
import za.kilowatch.ultimatefilemanager.util.NaturalSort
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * S3-compatible REST API client supporting AWS S3, IDrive e2, Backblaze B2, Wasabi, etc.
 *
 * Uses manual AWS Signature Version 4 (SigV4) signing — no AWS SDK dependency.
 * Powered by native UfmHttpClient (backed by Go kernel sockets / HttpURLConnection).
 *
 * NetworkShare field mapping:
 *   host        → endpoint base URL (e.g. "https://s3.amazonaws.com")
 *   domain      → bucket name
 *   remotePath  → region (e.g. "us-east-1")
 *   username    → Access Key ID
 *   password    → Secret Access Key (stored encrypted at rest, decrypted by repo)
 */
object S3ShareClient {

    private const val TAG = "S3ShareClient"
    private const val OCTET_STREAM = "application/octet-stream"
    private const val MULTIPART_THRESHOLD = 100L * 1024 * 1024   // 100 MB
    private const val PART_SIZE          =   8L * 1024 * 1024    //   8 MB

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun listFiles(share: NetworkShare, remotePath: String): List<NetworkFile> =
        withContext(Dispatchers.IO) {
            val bucket   = share.domain
            val endpoint = normalizeEndpoint(share.host)
            val region   = share.remotePath.ifBlank { "us-east-1" }

            // Normalize prefix: strip leading slash, ensure trailing slash for non-root
            val prefix = remotePath.trimStart('/').let { if (it.isNotEmpty() && !it.endsWith('/')) "$it/" else it }

            val queryParams = buildString {
                append("list-type=2")
                append("&delimiter=%2F")           // '/' delimiter for directory-style listing
                if (prefix.isNotEmpty()) append("&prefix=${urlEncode(prefix)}")
            }
            val url = "$endpoint/$bucket?$queryParams"

            val response = signedGet(url, share, region, emptyMap())
            val body = response.bodyString
            if (!response.isSuccessful) {
                GoRoLog.e(TAG, "listFiles failed (${response.statusCode}): $body")
                throw IOException("S3 listFiles failed (${response.statusCode}): ${extractS3Error(body)}")
            }

            parseListBucketResult(body, prefix)
        }

    suspend fun mkdir(share: NetworkShare, remotePath: String) = withContext(Dispatchers.IO) {
        val key = normalizePath(remotePath).trimStart('/') + "/"
        val url = "${normalizeEndpoint(share.host)}/${share.domain}/${encodePath(key)}"
        val region = share.remotePath.ifBlank { "us-east-1" }

        val (targetUrl, headers) = buildSignedHeaders("PUT", url, share, region, emptyMap(), emptyPayloadHash())
        val response = UfmHttpClient.putSync(targetUrl, headers, ByteArray(0))
        if (!response.isSuccessful && response.statusCode != 409) {
            val err = response.bodyString
            throw IOException("S3 mkdir failed (${response.statusCode}): ${extractS3Error(err)}")
        }
    }

    suspend fun deleteFile(share: NetworkShare, remotePath: String) = withContext(Dispatchers.IO) {
        val key = normalizePath(remotePath).trimStart('/')
        val url = "${normalizeEndpoint(share.host)}/${share.domain}/${encodePath(key)}"
        val region = share.remotePath.ifBlank { "us-east-1" }

        val (targetUrl, headers) = buildSignedHeaders("DELETE", url, share, region, emptyMap(), emptyPayloadHash())
        val response = UfmHttpClient.deleteSync(targetUrl, headers)
        if (!response.isSuccessful && response.statusCode != 204 && response.statusCode != 404) {
            val err = response.bodyString
            throw IOException("S3 delete failed (${response.statusCode}): ${extractS3Error(err)}")
        }
    }

    suspend fun deleteDir(share: NetworkShare, remotePath: String): Unit = withContext(Dispatchers.IO) {
        // recursively walk and delete
        val items = listFiles(share, remotePath)
        for (item in items) {
            if (item.isDirectory) {
                deleteDir(share, "/" + item.path)
            } else {
                deleteFile(share, "/" + item.path)
            }
        }
        deleteFile(share, remotePath + "/") // Delete dir marker
    }

    suspend fun rename(share: NetworkShare, fromPath: String, toPath: String) = withContext(Dispatchers.IO) {
        // Since S3 doesn't distinguish file/dir at the bridge level rename call, check if it's a dir
        val isDir = try {
            val (headUrl, headHeaders) = buildSignedHeaders("HEAD", "${normalizeEndpoint(share.host)}/${share.domain}/${encodePath(normalizePath(fromPath).trimStart('/') + "/")}", share, share.remotePath.ifBlank { "us-east-1" }, emptyMap(), emptyPayloadHash())
            UfmHttpClient.headSync(headUrl, headHeaders).isSuccessful
        } catch (e: Exception) { false }

        if (isDir) {
            renameDir(share, fromPath, toPath)
            return@withContext
        }

        val endpoint = normalizeEndpoint(share.host)
        val bucket   = share.domain
        val region   = share.remotePath.ifBlank { "us-east-1" }
        val srcKey   = normalizePath(fromPath).trimStart('/')
        val dstKey   = normalizePath(toPath).trimStart('/')

        // S3 rename = copy + delete
        val copyUrl  = "$endpoint/$bucket/${encodePath(dstKey)}"
        val extraHeaders = mapOf("x-amz-copy-source" to "/$bucket/${encodePath(srcKey)}")
        val (targetUrl, copyHeaders) = buildSignedHeaders("PUT", copyUrl, share, region, extraHeaders, emptyPayloadHash())

        val response = UfmHttpClient.putSync(targetUrl, copyHeaders, ByteArray(0))
        if (!response.isSuccessful) {
            val err = response.bodyString
            throw IOException("S3 copy failed (${response.statusCode}): ${extractS3Error(err)}")
        }

        // Delete source
        deleteFile(share, srcKey)
    }

    suspend fun renameDir(share: NetworkShare, fromPath: String, toPath: String): Unit = withContext(Dispatchers.IO) {
        mkdir(share, toPath) // create dest dir marker
        val items = listFiles(share, fromPath)
        for (item in items) {
            val newDest = toPath + "/" + item.name
            if (item.isDirectory) {
                renameDir(share, "/" + item.path, newDest)
            } else {
                rename(share, "/" + item.path, newDest)
            }
        }
        deleteFile(share, fromPath + "/") // clean up source dir marker
    }

    suspend fun openInputStream(share: NetworkShare, remotePath: String): Pair<InputStream, Long> =
        withContext(Dispatchers.IO) {
            val response = openInputStreamInternal(share, remotePath, null)
            val length = response.header("Content-Length")?.toLongOrNull() ?: -1L
            Pair(response.inputStream, length)
        }

    suspend fun openInputStreamForStreaming(share: NetworkShare, remotePath: String, rangeHeader: String?): UfmHttpClient.StreamResponse =
        withContext(Dispatchers.IO) { openInputStreamInternalSync(share, remotePath, rangeHeader) }

    fun openInputStreamForStreamingSync(share: NetworkShare, remotePath: String, rangeHeader: String?): UfmHttpClient.StreamResponse =
        openInputStreamInternalSync(share, remotePath, rangeHeader)

    fun openRandomAccessFile(share: NetworkShare, remotePath: String): IRandomAccessFile {
        val fileSize = getFileSizeSync(share, remotePath)
        return object : IRandomAccessFile {
            override val size: Long = fileSize
            override fun read(offset: Long, buffer: ByteArray, length: Int): Int {
                val rangeHeader = "bytes=$offset-${offset + length - 1}"
                val response = openInputStreamInternalSync(share, remotePath, rangeHeader)
                return response.use { resp ->
                    if (!resp.isSuccessful) {
                        throw IOException("S3 RAF read failed: ${resp.statusCode}")
                    }
                    resp.inputStream.use { input ->
                        var totalRead = 0
                        while (totalRead < length) {
                            val read = input.read(buffer, totalRead, length - totalRead)
                            if (read == -1) break
                            totalRead += read
                        }
                        if (totalRead == 0) -1 else totalRead
                    }
                }
            }
            override fun write(offset: Long, buffer: ByteArray, length: Int): Int =
                throw IOException("Random writes not supported on S3")
            override fun close() {}
        }
    }

    fun getFileSizeSync(share: NetworkShare, remotePath: String): Long {
        val key    = normalizePath(remotePath).trimStart('/')
        val url    = "${normalizeEndpoint(share.host)}/${share.domain}/${encodePath(key)}"
        val region = share.remotePath.ifBlank { "us-east-1" }
        val (targetUrl, headers) = buildSignedHeaders("HEAD", url, share, region, emptyMap(), emptyPayloadHash())
        val response = UfmHttpClient.headSync(targetUrl, headers)
        return response.header("Content-Length")?.toLongOrNull() ?: 0L
    }

    fun getStreamingUrlAndTokenSync(share: NetworkShare, remotePath: String): Pair<String, String> {
        // S3 presigned URL generation
        val key      = normalizePath(remotePath).trimStart('/')
        val endpoint = normalizeEndpoint(share.host)
        val bucket   = share.domain
        val region   = share.remotePath.ifBlank { "us-east-1" }
        val url      = "$endpoint/$bucket/${encodePath(key)}"
        val presigned = generatePresignedUrl("GET", url, share, region, 3600)
        return Pair(presigned, "")   // Token is embedded in the URL
    }

    suspend fun uploadStream(
        share: NetworkShare,
        remotePath: String,
        inputStream: InputStream,
        totalSize: Long,
        onProgress: (Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        if (totalSize <= MULTIPART_THRESHOLD) {
            uploadSingle(share, remotePath, inputStream, totalSize, onProgress)
        } else {
            uploadMultipart(share, remotePath, inputStream, totalSize, onProgress)
        }
    }

    suspend fun openOutputStream(share: NetworkShare, remotePath: String): OutputStream =
        withContext(Dispatchers.IO) {
            val tempFile = File(UfmApplication.instance.cacheDir, "s3_upload_${System.currentTimeMillis()}.tmp")
            object : OutputStream() {
                private val fileOut = FileOutputStream(tempFile)
                override fun write(b: Int) = fileOut.write(b)
                override fun write(b: ByteArray, off: Int, len: Int) = fileOut.write(b, off, len)
                override fun flush() = fileOut.flush()
                override fun close() {
                    fileOut.close()
                    runBlocking(Dispatchers.IO) {
                        try {
                            val size = tempFile.length()
                            FileInputStream(tempFile).use { fis ->
                                uploadStream(share, remotePath, fis, size) {}
                            }
                        } finally {
                            tempFile.delete()
                        }
                    }
                }
            }
        }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal helpers — HTTP
    // ─────────────────────────────────────────────────────────────────────────

    private fun openInputStreamInternal(share: NetworkShare, remotePath: String, rangeHeader: String?): UfmHttpClient.StreamResponse {
        val key    = normalizePath(remotePath).trimStart('/')
        val url    = "${normalizeEndpoint(share.host)}/${share.domain}/${encodePath(key)}"
        val region = share.remotePath.ifBlank { "us-east-1" }
        val extra  = if (rangeHeader != null) mapOf("range" to rangeHeader) else emptyMap()
        val (targetUrl, headers) = buildSignedHeaders("GET", url, share, region, extra, emptyPayloadHash())
        val response = UfmHttpClient.openStream(targetUrl, headers)
        if (!response.isSuccessful && response.statusCode != 206) {
            val err = response.inputStream.bufferedReader().use { it.readText() }
            response.close()
            throw IOException("S3 download failed (${response.statusCode}): ${extractS3Error(err)}")
        }
        return response
    }

    private fun openInputStreamInternalSync(share: NetworkShare, remotePath: String, rangeHeader: String?): UfmHttpClient.StreamResponse =
        openInputStreamInternal(share, remotePath, rangeHeader)

    private fun signedGet(url: String, share: NetworkShare, region: String, extraHeaders: Map<String, String>): UfmHttpClient.Response {
        val (targetUrl, headers) = buildSignedHeaders("GET", url, share, region, extraHeaders, emptyPayloadHash())
        return UfmHttpClient.getSync(targetUrl, headers)
    }

    private fun uploadSingle(
        share: NetworkShare,
        remotePath: String,
        inputStream: InputStream,
        totalSize: Long,
        onProgress: (Long) -> Unit
    ) {
        val key      = normalizePath(remotePath).trimStart('/')
        val endpoint = normalizeEndpoint(share.host)
        val bucket   = share.domain
        val region   = share.remotePath.ifBlank { "us-east-1" }
        val url      = "$endpoint/$bucket/${encodePath(key)}"

        val bytes = inputStream.readBytes()
        val payloadHash = sha256Hex(bytes)
        val (targetUrl, headers) = buildSignedHeaders("PUT", url, share, region, mapOf("Content-Type" to OCTET_STREAM), payloadHash)
        val response = UfmHttpClient.putSync(targetUrl, headers, bytes)
        if (!response.isSuccessful) {
            val err = response.bodyString
            throw IOException("S3 upload failed (${response.statusCode}): ${extractS3Error(err)}")
        }
        onProgress(totalSize)
    }

    private fun uploadMultipart(
        share: NetworkShare,
        remotePath: String,
        inputStream: InputStream,
        totalSize: Long,
        onProgress: (Long) -> Unit
    ) {
        val key      = normalizePath(remotePath).trimStart('/')
        val endpoint = normalizeEndpoint(share.host)
        val bucket   = share.domain
        val region   = share.remotePath.ifBlank { "us-east-1" }

        // 1. Initiate multipart upload
        val initiateUrl = "$endpoint/$bucket/${encodePath(key)}?uploads"
        val (initUrl, initHeaders) = buildSignedHeaders(
            "POST", initiateUrl, share, region, mapOf("Content-Type" to OCTET_STREAM), emptyPayloadHash()
        )
        val response = UfmHttpClient.postSync(initUrl, initHeaders, ByteArray(0))
        val body = response.bodyString
        if (!response.isSuccessful) throw IOException("S3 initiate multipart failed: ${extractS3Error(body)}")
        val uploadId = extractXmlTag(body, "UploadId") ?: throw IOException("S3: no UploadId in response")

        val eTags = mutableListOf<String>()
        var uploadedBytes = 0L
        var partNumber    = 1
        val buffer        = ByteArray(PART_SIZE.toInt())

        try {
            // 2. Upload parts
            inputStream.use { input ->
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    val partBytes = buffer.copyOf(bytesRead)
                    val partHash  = sha256Hex(partBytes)
                    val partUrl   = "$endpoint/$bucket/${encodePath(key)}?partNumber=$partNumber&uploadId=${urlEncode(uploadId)}"
                    val (pUrl, pHeaders) = buildSignedHeaders("PUT", partUrl, share, region, mapOf("Content-Type" to OCTET_STREAM), partHash)
                    val partResp = UfmHttpClient.putSync(pUrl, pHeaders, partBytes)
                    val err = partResp.bodyString
                    if (!partResp.isSuccessful) throw IOException("S3 upload part $partNumber failed: ${extractS3Error(err)}")
                    val eTag = partResp.header("ETag") ?: extractXmlTag(err, "ETag") ?: ""
                    eTags.add(eTag.trim('"'))
                    uploadedBytes += bytesRead
                    onProgress(uploadedBytes)
                    partNumber++
                }
            }

            // 3. Complete multipart upload
            val completeXml = buildString {
                append("<CompleteMultipartUpload>")
                eTags.forEachIndexed { idx, eTag ->
                    append("<Part><PartNumber>${idx + 1}</PartNumber><ETag>\"$eTag\"</ETag></Part>")
                }
                append("</CompleteMultipartUpload>")
            }
            val completeUrl   = "$endpoint/$bucket/${encodePath(key)}?uploadId=${urlEncode(uploadId)}"
            val completeBytes = completeXml.toByteArray(Charsets.UTF_8)
            val (cUrl, cHeaders) = buildSignedHeaders("POST", completeUrl, share, region, mapOf("Content-Type" to "application/xml"), sha256Hex(completeBytes))
            val compResp = UfmHttpClient.postSync(cUrl, cHeaders, completeBytes)
            val compBody = compResp.bodyString
            if (!compResp.isSuccessful) throw IOException("S3 complete multipart failed: ${extractS3Error(compBody)}")
        } catch (e: Exception) {
            // Abort multipart on failure
            try {
                val abortUrl = "$endpoint/$bucket/${encodePath(key)}?uploadId=${urlEncode(uploadId)}"
                val (aUrl, aHeaders) = buildSignedHeaders("DELETE", abortUrl, share, region, emptyMap(), emptyPayloadHash())
                UfmHttpClient.deleteSync(aUrl, aHeaders)
            } catch (_: Exception) {}
            throw e
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SigV4 Signing
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Builds SigV4-signed URL and headers map.
     *
     * @param method        HTTP method (GET, PUT, DELETE, HEAD, POST)
     * @param urlStr        Full URL (may include query string)
     * @param share         Contains endpoint (host), bucket (domain), region (remotePath),
     *                      access key (username), secret key (password)
     * @param region        AWS region or equivalent
     * @param extraHeaders  Additional headers to include and sign
     * @param payloadHash   SHA-256 hex of the request body (use [emptyPayloadHash] for empty body)
     * @return Pair of encoded URL and finalized headers map (including Authorization)
     */
    private fun buildSignedHeaders(
        method: String,
        urlStr: String,
        share: NetworkShare,
        region: String,
        extraHeaders: Map<String, String>,
        payloadHash: String
    ): Pair<String, Map<String, String>> {
        val accessKey = share.username
        val secretKey = share.password

        val now       = Date()
        val dateTime  = dateTimeFormat.format(now)
        val dateOnly  = dateOnly(dateTime)
        val service   = "s3"

        val parsedUrl  = java.net.URL(urlStr)
        val host       = parsedUrl.host
        val pathPart   = if (parsedUrl.path.isNullOrEmpty()) "/" else parsedUrl.path
        val queryPart  = parsedUrl.query ?: ""

        // Canonical query string: sort param names
        val sortedQuery = if (queryPart.isEmpty()) ""
        else queryPart.split("&")
            .map { it.split("=", limit = 2) }
            .sortedWith(compareBy({ it[0] }, { it.getOrElse(1) { "" } }))
            .joinToString("&") { parts ->
                if (parts.size == 2) "${parts[0]}=${parts[1]}" else parts[0]
            }

        // Build canonical headers
        val allHeaders = mutableMapOf<String, String>()
        allHeaders["host"]                 = host
        allHeaders["x-amz-date"]           = dateTime
        allHeaders["x-amz-content-sha256"] = payloadHash
        extraHeaders.forEach { (k, v) -> allHeaders[k.lowercase()] = v }

        val sortedHeaderNames = allHeaders.keys.sorted()
        val canonicalHeaders  = sortedHeaderNames.joinToString("\n") { "${it}:${allHeaders[it]}" } + "\n"
        val signedHeaders     = sortedHeaderNames.joinToString(";")

        val canonicalRequest = "$method\n$pathPart\n$sortedQuery\n$canonicalHeaders\n$signedHeaders\n$payloadHash"

        val credentialScope  = "$dateOnly/$region/$service/aws4_request"
        val stringToSign     = "AWS4-HMAC-SHA256\n$dateTime\n$credentialScope\n${sha256Hex(canonicalRequest.toByteArray())}"

        val signingKey   = deriveSigningKey(secretKey, dateOnly, region, service)
        val signature    = hmacSha256Hex(signingKey, stringToSign)
        val authorization = "AWS4-HMAC-SHA256 Credential=$accessKey/$credentialScope, SignedHeaders=$signedHeaders, Signature=$signature"

        val encodedUrl = "${parsedUrl.protocol}://${parsedUrl.authority}$pathPart" + if (queryPart.isNotEmpty()) "?$queryPart" else ""
        val finalHeaders = allHeaders.toMutableMap()
        finalHeaders["Authorization"] = authorization

        return Pair(encodedUrl, finalHeaders)
    }

    /** Generates a SigV4 presigned URL for GET (streaming). */
    private fun generatePresignedUrl(
        method: String,
        urlStr: String,
        share: NetworkShare,
        region: String,
        expiresSeconds: Int
    ): String {
        val accessKey = share.username
        val secretKey = share.password
        val now       = Date()
        val dateTime  = dateTimeFormat.format(now)
        val dateOnly  = dateOnly(dateTime)
        val service   = "s3"

        val parsedUrl = java.net.URL(urlStr)
        val host      = parsedUrl.host
        val pathPart  = if (parsedUrl.path.isNullOrEmpty()) "/" else parsedUrl.path

        val credentialScope = "$dateOnly/$region/$service/aws4_request"
        val signedHeaders   = "host"

        val queryString = listOf(
            "X-Amz-Algorithm"     to "AWS4-HMAC-SHA256",
            "X-Amz-Credential"    to urlEncode("$accessKey/$credentialScope"),
            "X-Amz-Date"          to dateTime,
            "X-Amz-Expires"       to "$expiresSeconds",
            "X-Amz-SignedHeaders"  to signedHeaders
        ).joinToString("&") { (k, v) -> "$k=$v" }

        val canonicalRequest = "$method\n$pathPart\n$queryString\nhost:$host\n\n$signedHeaders\nUNSIGNED-PAYLOAD"
        val stringToSign     = "AWS4-HMAC-SHA256\n$dateTime\n$credentialScope\n${sha256Hex(canonicalRequest.toByteArray())}"
        val signingKey       = deriveSigningKey(secretKey, dateOnly, region, service)
        val signature        = hmacSha256Hex(signingKey, stringToSign)

        return "$urlStr?$queryString&X-Amz-Signature=$signature"
    }

    private fun deriveSigningKey(secret: String, date: String, region: String, service: String): ByteArray {
        val k1 = hmacSha256("AWS4$secret".toByteArray(), date)
        val k2 = hmacSha256(k1, region)
        val k3 = hmacSha256(k2, service)
        return hmacSha256(k3, "aws4_request")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // XML Parsing
    // ─────────────────────────────────────────────────────────────────────────

    private fun parseListBucketResult(xml: String, prefix: String): List<NetworkFile> {
        val files = mutableListOf<NetworkFile>()

        // Common prefixes = "directories"
        val prefixRegex = Regex("<CommonPrefixes>.*?<Prefix>(.*?)</Prefix>.*?</CommonPrefixes>", RegexOption.DOT_MATCHES_ALL)
        prefixRegex.findAll(xml).forEach { m ->
            val fullKey = m.groupValues[1]
            val name    = fullKey.removePrefix(prefix).trimEnd('/')
            if (name.isNotEmpty()) {
                files.add(NetworkFile(name = name, path = fullKey.trimEnd('/'), isDirectory = true))
            }
        }

        // Contents = files
        val contentRegex = Regex("<Contents>.*?</Contents>", RegexOption.DOT_MATCHES_ALL)
        contentRegex.findAll(xml).forEach { m ->
            val block   = m.value
            val key     = extractXmlTag(block, "Key") ?: return@forEach
            if (key.endsWith('/')) return@forEach  // Skip directory markers
            if (key == prefix)    return@forEach  // Skip the prefix itself
            val name  = key.removePrefix(prefix)
            if (name.isEmpty() || name.contains('/')) return@forEach  // Skip nested items
            val size  = extractXmlTag(block, "Size")?.toLongOrNull() ?: 0L
            val lastMod = extractXmlTag(block, "LastModified")?.let {
                try { s3DateFormat.parse(it)?.time ?: 0L } catch (_: Exception) { 0L }
            } ?: 0L
            files.add(NetworkFile(name = name, path = key, isDirectory = false, size = size, lastModified = lastMod))
        }

        return files.sortedWith(compareBy<NetworkFile> { !it.isDirectory }.thenBy(NaturalSort.order) { it.name })
    }

    private fun extractXmlTag(xml: String, tag: String): String? =
        Regex("<$tag>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)

    private fun extractS3Error(xml: String): String =
        extractXmlTag(xml, "Message") ?: extractXmlTag(xml, "Code") ?: xml.take(200)

    // ─────────────────────────────────────────────────────────────────────────
    // Crypto helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun sha256Hex(text: String) = sha256Hex(text.toByteArray(Charsets.UTF_8))

    private fun emptyPayloadHash() = sha256Hex(ByteArray(0))

    private fun hmacSha256(key: ByteArray, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
    }

    private fun hmacSha256Hex(key: ByteArray, data: String): String =
        hmacSha256(key, data).joinToString("") { "%02x".format(it) }

    // ─────────────────────────────────────────────────────────────────────────
    // Utility
    // ─────────────────────────────────────────────────────────────────────────

    private fun normalizeEndpoint(host: String): String =
        host.trimEnd('/')

    private fun normalizePath(path: String): String =
        if (path.startsWith("/")) path else "/$path"

    private fun urlEncode(s: String): String =
        URLEncoder.encode(s, "UTF-8")
            .replace("+", "%20")
            .replace("*", "%2A")
            .replace("%7E", "~")

    private fun encodePath(path: String): String =
        path.split("/").joinToString("/") { urlEncode(it) }

    private val dateTimeFormat: SimpleDateFormat
        get() = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).also {
            it.timeZone = TimeZone.getTimeZone("UTC")
        }

    private val s3DateFormat: SimpleDateFormat
        get() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).also {
            it.timeZone = TimeZone.getTimeZone("UTC")
        }

    private fun dateOnly(dateTime: String) = dateTime.substring(0, 8)
}
