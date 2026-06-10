package za.kilowatch.ultimatefilemanager.network

import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

class DlnaRandomAccessFile(
    private val url: String,
    override val size: Long
) : IRandomAccessFile {

    private val client: OkHttpClient
        get() = sharedClient

    private var currentConnection: okhttp3.Response? = null
    private var currentStream: InputStream? = null
    private var cachedContentLength: Long = if (size > 0) size else 0L

    companion object {
        private val sharedClient: OkHttpClient by lazy {
            BypassCleartextOkHttpClient.applyBypass(
                OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .writeTimeout(10, TimeUnit.SECONDS)
                    .connectionPool(ConnectionPool(5, 30, TimeUnit.SECONDS))
                    .followRedirects(true)
                    .followSslRedirects(true)
            ).build()
        }
    }

    override fun read(offset: Long, buffer: ByteArray, length: Int): Int {
        closeCurrentConnection()

        val endOffset = offset + length - 1
        val rangeHeader = "bytes=$offset-$endOffset"

        val request = Request.Builder()
            .url(url)
            .header("Range", rangeHeader)
            .build()

        var response: okhttp3.Response? = null
        var redirectCount = 0
        var currentRequest = request
        var redirectUrl = url

        while (redirectCount <= 3) {
            response = try {
                client.newCall(currentRequest).execute()
            } catch (e: IOException) {
                closeCurrentConnection()
                throw IOException("HTTP request failed for Range [$rangeHeader] on $redirectUrl", e)
            }

            when (response.code) {
                206 -> {
                    // Partial content — ideal case
                    val body = response.body
                        ?: run {
                            response.close()
                            throw IOException("Response body is null for 206 on $redirectUrl")
                        }
                    val contentLength = body.contentLength()
                    if (contentLength > 0) {
                        cachedContentLength = contentLength
                    }

                    val stream = body.byteStream()
                    currentConnection = response
                    currentStream = stream

                    var totalBytesRead = 0
                    while (totalBytesRead < length) {
                        val bytesRead = try {
                            stream.read(buffer, totalBytesRead, length - totalBytesRead)
                        } catch (e: IOException) {
                            closeCurrentConnection()
                            throw IOException(
                                "Error reading stream at offset $offset for $redirectUrl",
                                e
                            )
                        }
                        if (bytesRead == -1) {
                            return if (totalBytesRead == 0) -1 else totalBytesRead
                        }
                        totalBytesRead += bytesRead
                    }
                    return totalBytesRead
                }

                200 -> {
                    // Server does not support Range requests
                    val body = response.body
                        ?: run {
                            response.close()
                            throw IOException("Response body is null for 200 on $redirectUrl")
                        }
                    val contentLength = body.contentLength()
                    if (contentLength > 0) {
                        cachedContentLength = contentLength
                    }
                    if (contentLength > 0 && offset >= contentLength) {
                        response.close()
                        return -1
                    }

                    val stream = body.byteStream()
                    currentConnection = response
                    currentStream = stream

                    // Seek to the requested offset
                    var bytesToSkip = offset
                    while (bytesToSkip > 0) {
                        val skipped = try {
                            stream.skip(bytesToSkip)
                        } catch (e: IOException) {
                            closeCurrentConnection()
                            throw IOException(
                                "Error seeking to offset $offset on $redirectUrl",
                                e
                            )
                        }
                        if (skipped <= 0) {
                            // Cannot skip further — likely EOF
                            closeCurrentConnection()
                            return -1
                        }
                        bytesToSkip -= skipped
                    }

                    var totalBytesRead = 0
                    while (totalBytesRead < length) {
                        val bytesRead = try {
                            stream.read(buffer, totalBytesRead, length - totalBytesRead)
                        } catch (e: IOException) {
                            closeCurrentConnection()
                            throw IOException(
                                "Error reading stream at offset $offset for $redirectUrl",
                                e
                            )
                        }
                        if (bytesRead == -1) {
                            return if (totalBytesRead == 0) -1 else totalBytesRead
                        }
                        totalBytesRead += bytesRead
                    }
                    return totalBytesRead
                }

                301, 302, 307, 308 -> {
                    val location = response.header("Location")
                    if (location.isNullOrBlank()) {
                        response.close()
                        throw IOException(
                            "Redirect ${response.code} without Location header for $redirectUrl"
                        )
                    }
                    redirectUrl = location
                    response.close()
                    redirectCount++
                    currentRequest = Request.Builder()
                        .url(location)
                        .header("Range", rangeHeader)
                        .build()
                    continue
                }

                else -> {
                    val message = "HTTP ${response.code}: ${response.message}"
                    response.close()
                    closeCurrentConnection()
                    throw IOException("Unexpected response code $message for $redirectUrl")
                }
            }
        }

        throw IOException("Too many redirects (max 3) for URL starting at $url")
    }

    override fun write(offset: Long, buffer: ByteArray, length: Int): Int {
        throw UnsupportedOperationException("DLNA random access file is read-only")
    }

    override fun close() {
        closeCurrentConnection()
    }

    private fun closeCurrentConnection() {
        try {
            currentStream?.close()
        } catch (_: IOException) {
            // Ignore close errors
        }
        try {
            currentConnection?.close()
        } catch (_: IOException) {
            // Ignore close errors
        }
        currentStream = null
        currentConnection = null
    }
}
