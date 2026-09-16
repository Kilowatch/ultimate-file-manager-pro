package za.kilowatch.ultimatefilemanager.network

import java.io.IOException
import java.io.InputStream

class DlnaRandomAccessFile(
    private val url: String,
    override val size: Long
) : IRandomAccessFile {

    private val lock = Any()
    private var currentStreamResponse: UfmHttpClient.StreamResponse? = null
    private var cachedContentLength: Long = if (size > 0) size else 0L

    override fun read(offset: Long, buffer: ByteArray, length: Int): Int = synchronized(lock) {
        closeCurrentConnection()

        val endOffset = offset + length - 1
        val rangeHeader = "bytes=$offset-$endOffset"

        val response = try {
            UfmHttpClient.openStream(url, mapOf("Range" to rangeHeader), timeoutSec = 30)
        } catch (e: Exception) {
            closeCurrentConnection()
            throw IOException("HTTP request failed for Range [$rangeHeader] on $url", e)
        }

        currentStreamResponse = response

        when (response.statusCode) {
            206 -> {
                // Partial content — ideal case
                val contentLength = response.header("Content-Length")?.toLongOrNull() ?: -1L
                if (contentLength > 0) {
                    cachedContentLength = contentLength
                }

                val stream = response.inputStream
                var totalBytesRead = 0
                while (totalBytesRead < length) {
                    val bytesRead = try {
                        stream.read(buffer, totalBytesRead, length - totalBytesRead)
                    } catch (e: IOException) {
                        closeCurrentConnection()
                        throw IOException("Error reading stream at offset $offset for $url", e)
                    }
                    if (bytesRead == -1) {
                        return if (totalBytesRead == 0) -1 else totalBytesRead
                    }
                    totalBytesRead += bytesRead
                }
                return totalBytesRead
            }

            200 -> {
                // Server does not support Range requests — seek manually
                val contentLength = response.header("Content-Length")?.toLongOrNull() ?: -1L
                if (contentLength > 0) {
                    cachedContentLength = contentLength
                }
                if (contentLength > 0 && offset >= contentLength) {
                    closeCurrentConnection()
                    return -1
                }

                val stream = response.inputStream
                var bytesToSkip = offset
                while (bytesToSkip > 0) {
                    val skipped = try {
                        stream.skip(bytesToSkip)
                    } catch (e: IOException) {
                        closeCurrentConnection()
                        throw IOException("Error seeking to offset $offset on $url", e)
                    }
                    if (skipped <= 0) {
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
                        throw IOException("Error reading stream at offset $offset for $url", e)
                    }
                    if (bytesRead == -1) {
                        return if (totalBytesRead == 0) -1 else totalBytesRead
                    }
                    totalBytesRead += bytesRead
                }
                return totalBytesRead
            }

            else -> {
                val statusCode = response.statusCode
                closeCurrentConnection()
                throw IOException("Unexpected HTTP response code $statusCode for $url")
            }
        }
    }

    override fun write(offset: Long, buffer: ByteArray, length: Int): Int {
        throw UnsupportedOperationException("DLNA random access file is read-only")
    }

    override fun close() {
        closeCurrentConnection()
    }

    private fun closeCurrentConnection() = synchronized(lock) {
        try {
            currentStreamResponse?.close()
        } catch (_: Exception) {}
        currentStreamResponse = null
    }
}
