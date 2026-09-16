package za.kilowatch.ultimatefilemanager.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import za.kilowatch.ultimatefilemanager.util.GoRoLog
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Hardened native HTTP/HTTPS client for UFM.
 *
 * Primary engine: routes through native Go [gohttp.Gohttp] via Gomobile,
 * utilizing Linux kernel sockets, HTTP/2, connection pooling, and strict TLS 1.2/1.3.
 *
 * Fallback engine: uses Android's native [HttpURLConnection] / [HttpsURLConnection]
 * if the Go shared library is not loaded (e.g. JVM unit tests under Robolectric).
 */
object UfmHttpClient {

    private const val TAG = "UfmHttpClient"

    data class Response(
        val statusCode: Int,
        val headers: Map<String, String>,
        val bodyBytes: ByteArray
    ) {
        val bodyString: String by lazy { String(bodyBytes, Charsets.UTF_8) }
        val body: String get() = bodyString
        val isSuccessful: Boolean get() = statusCode in 200..299

        fun header(name: String): String? {
            return headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as Response
            return statusCode == other.statusCode &&
                    headers == other.headers &&
                    bodyBytes.contentEquals(other.bodyBytes)
        }

        override fun hashCode(): Int {
            var result = statusCode
            result = 31 * result + headers.hashCode()
            result = 31 * result + bodyBytes.contentHashCode()
            return result
        }
    }

    class StreamResponse(
        val statusCode: Int,
        val headers: Map<String, String>,
        val inputStream: InputStream,
        private val onClose: () -> Unit = {}
    ) : java.io.Closeable {
        val isSuccessful: Boolean get() = statusCode in 200..299
        val contentLength: Long get() = header("Content-Length")?.toLongOrNull() ?: -1L
        fun header(name: String): String? {
            return headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
        }

        override fun close() {
            runCatching { inputStream.close() }
            runCatching { onClose() }
        }
    }

    fun openStream(
        url: String,
        headers: Map<String, String> = emptyMap(),
        method: String = "GET",
        body: ByteArray? = null,
        timeoutSec: Int = 30
    ): StreamResponse {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            try {
                requestMethod = method
            } catch (e: Exception) {
                try {
                    val field = HttpURLConnection::class.java.getDeclaredField("method")
                    field.isAccessible = true
                    field.set(this, method)
                } catch (t: Throwable) {
                    setRequestProperty("X-HTTP-Method-Override", method)
                    requestMethod = "POST"
                }
            }
            connectTimeout = timeoutSec * 1000
            readTimeout = timeoutSec * 1000
            instanceFollowRedirects = true
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            if (body != null) {
                doOutput = true
            }
        }
        if (body != null && body.isNotEmpty()) {
            conn.outputStream.use { it.write(body) }
        }
        val statusCode = conn.responseCode
        val respHeaders = mutableMapOf<String, String>()
        conn.headerFields.forEach { (k, vList) ->
            if (k != null && vList.isNotEmpty()) {
                respHeaders[k] = vList.first()
            }
        }
        val rawStream = if (statusCode in 200..299) {
            conn.inputStream
        } else {
            conn.errorStream ?: java.io.ByteArrayInputStream(ByteArray(0))
        }
        val safeStream = object : java.io.FilterInputStream(rawStream) {
            private var closed = false
            override fun close() {
                if (!closed) {
                    closed = true
                    runCatching { super.close() }
                    runCatching { conn.disconnect() }
                }
            }
        }
        return StreamResponse(statusCode, respHeaders, safeStream) {
            safeStream.close()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Primary Methods
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutSec: Int = 30
    ): Response = withContext(Dispatchers.IO) {
        execute("GET", url, headers, null, timeoutSec)
    }

    fun getSync(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutSec: Int = 30
    ): Response {
        return execute("GET", url, headers, null, timeoutSec)
    }

    suspend fun post(
        url: String,
        headers: Map<String, String> = emptyMap(),
        bodyBytes: ByteArray? = null,
        timeoutSec: Int = 30
    ): Response = withContext(Dispatchers.IO) {
        execute("POST", url, headers, bodyBytes, timeoutSec)
    }

    fun postSync(
        url: String,
        headers: Map<String, String> = emptyMap(),
        bodyBytes: ByteArray? = null,
        timeoutSec: Int = 30
    ): Response {
        return execute("POST", url, headers, bodyBytes, timeoutSec)
    }

    suspend fun postString(
        url: String,
        headers: Map<String, String> = emptyMap(),
        bodyString: String,
        timeoutSec: Int = 30
    ): Response = withContext(Dispatchers.IO) {
        execute("POST", url, headers, bodyString.toByteArray(Charsets.UTF_8), timeoutSec)
    }

    fun postStringSync(
        url: String,
        headers: Map<String, String> = emptyMap(),
        bodyString: String,
        timeoutSec: Int = 30
    ): Response {
        return execute("POST", url, headers, bodyString.toByteArray(Charsets.UTF_8), timeoutSec)
    }

    suspend fun postForm(
        url: String,
        formFields: Map<String, String>,
        headers: Map<String, String> = emptyMap(),
        timeoutSec: Int = 30
    ): Response = withContext(Dispatchers.IO) {
        postFormSync(url, formFields, headers, timeoutSec)
    }

    fun postFormSync(
        url: String,
        formFields: Map<String, String>,
        headers: Map<String, String> = emptyMap(),
        timeoutSec: Int = 30
    ): Response {
        val encodedForm = formFields.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
        val effHeaders = headers.toMutableMap().apply {
            put("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
        }
        return execute("POST", url, effHeaders, encodedForm.toByteArray(Charsets.UTF_8), timeoutSec)
    }

    suspend fun put(
        url: String,
        headers: Map<String, String> = emptyMap(),
        bodyBytes: ByteArray? = null,
        timeoutSec: Int = 30
    ): Response = withContext(Dispatchers.IO) {
        execute("PUT", url, headers, bodyBytes, timeoutSec)
    }

    fun putSync(
        url: String,
        headers: Map<String, String> = emptyMap(),
        bodyBytes: ByteArray? = null,
        timeoutSec: Int = 30
    ): Response {
        return execute("PUT", url, headers, bodyBytes, timeoutSec)
    }

    suspend fun putBytes(
        url: String,
        headers: Map<String, String> = emptyMap(),
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size,
        timeoutSec: Int = 30
    ): Response = withContext(Dispatchers.IO) {
        putBytesSync(url, headers, bytes, offset, length, timeoutSec)
    }

    fun putBytesSync(
        url: String,
        headers: Map<String, String> = emptyMap(),
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size,
        timeoutSec: Int = 30
    ): Response {
        val payload = if (offset == 0 && length == bytes.size) bytes else bytes.copyOfRange(offset, offset + length)
        return execute("PUT", url, headers, payload, timeoutSec)
    }

    suspend fun patch(
        url: String,
        headers: Map<String, String> = emptyMap(),
        bodyBytes: ByteArray? = null,
        timeoutSec: Int = 30
    ): Response = withContext(Dispatchers.IO) {
        execute("PATCH", url, headers, bodyBytes, timeoutSec)
    }

    fun patchSync(
        url: String,
        headers: Map<String, String> = emptyMap(),
        bodyBytes: ByteArray? = null,
        timeoutSec: Int = 30
    ): Response {
        return execute("PATCH", url, headers, bodyBytes, timeoutSec)
    }

    suspend fun patchString(
        url: String,
        headers: Map<String, String> = emptyMap(),
        bodyString: String,
        timeoutSec: Int = 30
    ): Response = withContext(Dispatchers.IO) {
        execute("PATCH", url, headers, bodyString.toByteArray(Charsets.UTF_8), timeoutSec)
    }

    fun patchStringSync(
        url: String,
        headers: Map<String, String> = emptyMap(),
        bodyString: String,
        timeoutSec: Int = 30
    ): Response {
        return execute("PATCH", url, headers, bodyString.toByteArray(Charsets.UTF_8), timeoutSec)
    }

    suspend fun patchBytes(
        url: String,
        headers: Map<String, String> = emptyMap(),
        bytes: ByteArray,
        timeoutSec: Int = 30
    ): Response = withContext(Dispatchers.IO) {
        execute("PATCH", url, headers, bytes, timeoutSec)
    }

    fun patchBytesSync(
        url: String,
        headers: Map<String, String> = emptyMap(),
        bytes: ByteArray,
        timeoutSec: Int = 30
    ): Response {
        return execute("PATCH", url, headers, bytes, timeoutSec)
    }

    suspend fun delete(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutSec: Int = 30
    ): Response = withContext(Dispatchers.IO) {
        execute("DELETE", url, headers, null, timeoutSec)
    }

    fun deleteSync(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutSec: Int = 30
    ): Response {
        return execute("DELETE", url, headers, null, timeoutSec)
    }

    suspend fun head(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutSec: Int = 30
    ): Response = withContext(Dispatchers.IO) {
        execute("HEAD", url, headers, null, timeoutSec)
    }

    fun headSync(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutSec: Int = 30
    ): Response {
        return execute("HEAD", url, headers, null, timeoutSec)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // File Transfers & Streaming
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun downloadToFile(
        url: String,
        headers: Map<String, String> = emptyMap(),
        destinationFile: File,
        timeoutSec: Int = 120,
        onProgress: ((bytesDownloaded: Long, totalBytes: Long) -> Unit)? = null
    ): Int = withContext(Dispatchers.IO) {
        downloadToFileSync(url, headers, destinationFile, timeoutSec, onProgress)
    }

    fun downloadToFileSync(
        url: String,
        headers: Map<String, String> = emptyMap(),
        destinationFile: File,
        timeoutSec: Int = 120,
        onProgress: ((bytesDownloaded: Long, totalBytes: Long) -> Unit)? = null
    ): Int {
        // Try native Go downloader first (zero JVM heap memory buffer)
        try {
            val headersJson = JSONObject(headers).toString()
            return gohttp.Gohttp.goHttpDownload(url, headersJson, destinationFile.absolutePath, timeoutSec.toLong()).toInt()
        } catch (e: Throwable) {
            GoRoLog.d(TAG, "Native GoHttpDownload unavailable, falling back to Java: ${e.message}")
        }

        // Fallback: standard HttpURLConnection stream copy
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutSec * 1000
            readTimeout = timeoutSec * 1000
            instanceFollowRedirects = true
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }

        return try {
            val code = connection.responseCode
            if (code in 200..299) {
                val contentLength = connection.contentLengthLong
                var downloaded = 0L
                destinationFile.parentFile?.mkdirs()
                connection.inputStream.use { input ->
                    FileOutputStream(destinationFile).use { output ->
                        val buf = ByteArray(64 * 1024)
                        var n: Int
                        while (input.read(buf).also { n = it } != -1) {
                            output.write(buf, 0, n)
                            downloaded += n
                            onProgress?.invoke(downloaded, contentLength)
                        }
                    }
                }
            }
            code
        } finally {
            connection.disconnect()
        }
    }

    suspend fun uploadFromFile(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        localFile: File,
        timeoutSec: Int = 120
    ): Response = withContext(Dispatchers.IO) {
        uploadFromFileSync(method, url, headers, localFile, timeoutSec)
    }

    fun uploadFromFileSync(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        localFile: File,
        timeoutSec: Int = 120
    ): Response {
        try {
            val headersJson = JSONObject(headers).toString()
            val resp = gohttp.Gohttp.goHttpUpload(method, url, headersJson, localFile.absolutePath, timeoutSec.toLong())
            val respHeaders = parseHeadersJson(resp.headers)
            return Response(resp.statusCode.toInt(), respHeaders, resp.body ?: ByteArray(0))
        } catch (e: Throwable) {
            GoRoLog.d(TAG, "Native GoHttpUpload unavailable, falling back to Java: ${e.message}")
        }

        val bytes = localFile.readBytes()
        return execute(method, url, headers, bytes, timeoutSec)
    }

    data class FilePart(
        val partName: String,
        val fileName: String,
        val fileBytes: ByteArray,
        val mimeType: String = "application/octet-stream"
    )

    suspend fun postMultipartFiles(
        url: String,
        headers: Map<String, String> = emptyMap(),
        formFields: Map<String, String>,
        files: List<FilePart> = emptyList(),
        timeoutSec: Int = 60
    ): Response = withContext(Dispatchers.IO) {
        postMultipartFilesSync(url, headers, formFields, files, timeoutSec)
    }

    fun postMultipartFilesSync(
        url: String,
        headers: Map<String, String> = emptyMap(),
        formFields: Map<String, String>,
        files: List<FilePart> = emptyList(),
        timeoutSec: Int = 60
    ): Response {
        val boundary = "==UFM_${System.currentTimeMillis()}_${SecureRandom().nextLong()}=="
        val lineEnd = "\r\n"
        val twoHyphens = "--"

        val byteStream = ByteArrayOutputStream()

        // Write form fields
        for ((key, value) in formFields) {
            byteStream.write("$twoHyphens$boundary$lineEnd".toByteArray(Charsets.UTF_8))
            byteStream.write("Content-Disposition: form-data; name=\"$key\"$lineEnd$lineEnd".toByteArray(Charsets.UTF_8))
            byteStream.write(value.toByteArray(Charsets.UTF_8))
            byteStream.write(lineEnd.toByteArray(Charsets.UTF_8))
        }

        // Write file parts
        for (file in files) {
            byteStream.write("$twoHyphens$boundary$lineEnd".toByteArray(Charsets.UTF_8))
            byteStream.write("Content-Disposition: form-data; name=\"${file.partName}\"; filename=\"${file.fileName}\"$lineEnd".toByteArray(Charsets.UTF_8))
            byteStream.write("Content-Type: ${file.mimeType}$lineEnd$lineEnd".toByteArray(Charsets.UTF_8))
            byteStream.write(file.fileBytes)
            byteStream.write(lineEnd.toByteArray(Charsets.UTF_8))
        }

        // Closing boundary
        byteStream.write("$twoHyphens$boundary$twoHyphens$lineEnd".toByteArray(Charsets.UTF_8))

        val effHeaders = headers.toMutableMap().apply {
            put("Content-Type", "multipart/form-data; boundary=$boundary")
        }

        return execute("POST", url, effHeaders, byteStream.toByteArray(), timeoutSec)
    }

    suspend fun postMultipart(
        url: String,
        headers: Map<String, String> = emptyMap(),
        formFields: Map<String, String>,
        filePartName: String,
        fileName: String,
        fileBytes: ByteArray,
        mimeType: String = "application/octet-stream",
        timeoutSec: Int = 60
    ): Response = postMultipartFiles(
        url, headers, formFields,
        listOf(FilePart(filePartName, fileName, fileBytes, mimeType)),
        timeoutSec
    )

    fun postMultipartSync(
        url: String,
        headers: Map<String, String> = emptyMap(),
        formFields: Map<String, String>,
        filePartName: String,
        fileName: String,
        fileBytes: ByteArray,
        mimeType: String = "application/octet-stream",
        timeoutSec: Int = 60
    ): Response = postMultipartFilesSync(
        url, headers, formFields,
        listOf(FilePart(filePartName, fileName, fileBytes, mimeType)),
        timeoutSec
    )

    // ─────────────────────────────────────────────────────────────────────────
    // Execution Core
    // ─────────────────────────────────────────────────────────────────────────

    private fun execute(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray?,
        timeoutSec: Int
    ): Response {
        // Attempt execution via native Go HTTP engine first
        try {
            val headersJson = JSONObject(headers).toString()
            val effBody = body ?: ByteArray(0)
            val goResp = gohttp.Gohttp.goHttpRequest(method, url, headersJson, effBody, timeoutSec.toLong())
            val respHeaders = parseHeadersJson(goResp.headers)
            return Response(goResp.statusCode.toInt(), respHeaders, goResp.body ?: ByteArray(0))
        } catch (e: Throwable) {
            // If Go is not available (e.g. JVM Robolectric test environment), fall back to Java HttpURLConnection
            GoRoLog.d(TAG, "Native GoHttp unavailable for $method $url, using Java fallback: ${e.message}")
        }

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            try {
                requestMethod = method
            } catch (e: Exception) {
                try {
                    val field = HttpURLConnection::class.java.getDeclaredField("method")
                    field.isAccessible = true
                    field.set(this, method)
                } catch (t: Throwable) {
                    setRequestProperty("X-HTTP-Method-Override", method)
                    requestMethod = "POST"
                }
            }
            connectTimeout = timeoutSec * 1000
            readTimeout = timeoutSec * 1000
            instanceFollowRedirects = true
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            if (body != null && body.isNotEmpty()) {
                doOutput = true
            }
        }

        return try {
            if (body != null && body.isNotEmpty()) {
                conn.outputStream.use { it.write(body) }
            }

            val statusCode = conn.responseCode
            val respHeaders = mutableMapOf<String, String>()
            conn.headerFields.forEach { (k, vList) ->
                if (k != null && vList.isNotEmpty()) {
                    respHeaders[k] = vList.first()
                }
            }

            val inStream = if (statusCode in 200..299) conn.inputStream else conn.errorStream
            val respBody = inStream?.use { it.readBytes() } ?: ByteArray(0)

            Response(statusCode, respHeaders, respBody)
        } finally {
            conn.disconnect()
        }
    }

    private fun parseHeadersJson(jsonStr: String?): Map<String, String> {
        if (jsonStr.isNullOrBlank()) return emptyMap()
        val result = mutableMapOf<String, String>()
        try {
            val json = JSONObject(jsonStr)
            val keys = json.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                result[k] = json.optString(k, "")
            }
        } catch (_: Exception) {}
        return result
    }
}
