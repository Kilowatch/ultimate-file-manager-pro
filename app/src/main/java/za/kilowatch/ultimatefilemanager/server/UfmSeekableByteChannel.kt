package za.kilowatch.ultimatefilemanager.server

import android.content.Context
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.*
import java.nio.file.*
import android.util.Log

/**
 * Minimal [SeekableByteChannel] implementation that bridges NIO calls to [UfmFileSystemBridge].
 *
 * ## Read (download) performance
 * SFTP clients like WinSCP issue multiple concurrent READ requests with arbitrary offsets.
 * Forward seeks within [FORWARD_SKIP_THRESHOLD] are served by skipping on the existing open
 * stream. Larger gaps and backward seeks reopen at the exact byte via REST.
 *
 * ## Security note (L-2)
 * Log messages do not include full URIs or file paths to avoid leaking sensitive information
 * to the Android logcat (readable by apps with READ_LOGS permission).
 */
class UfmSeekableByteChannel(
    private val context: Context,
    private val uri: String,
    private val options: Set<OpenOption>
) : SeekableByteChannel {
    private val TAG = "UfmSftpChannel"

    private val FORWARD_SKIP_THRESHOLD = 4L * 1024 * 1024
    private val READ_BUFFER_SIZE = 256 * 1024

    // L-2: Only the scheme is used in log output, never the full path.
    private val uriScheme = uri.substringBefore("://")

    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var position: Long = 0
    private var size: Long = -1

    init {
        val readable = options.any { it.toString() == "READ" || it.toString() == "StandardOpenOption.READ" }
        val writable = options.any {
            it.toString().let { s ->
                s == "WRITE" || s == "StandardOpenOption.WRITE" ||
                s == "CREATE" || s == "StandardOpenOption.CREATE" ||
                s == "CREATE_NEW" || s == "StandardOpenOption.CREATE_NEW" ||
                s == "APPEND" || s == "StandardOpenOption.APPEND"
            }
        }

        if (readable) {
            try {
                val meta = UfmFileSystemBridge.getFileMetadata(context, uri)
                size = meta?.size ?: 0
                inputStream = openReadStream(0)
                Log.d(TAG, "Read channel opened [$uriScheme] size=$size")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open read channel [$uriScheme]", e)
                throw e
            }
        }

        if (writable) {
            try {
                val raw = UfmFileSystemBridge.openOutputStream(context, uri)
                outputStream = java.io.BufferedOutputStream(raw, 64 * 1024)
                Log.d(TAG, "Write channel opened [$uriScheme]")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open write channel [$uriScheme]", e)
                throw e
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun openReadStream(offset: Long): InputStream {
        val raw = UfmFileSystemBridge.openInputStream(context, uri, offset)
        return java.io.BufferedInputStream(raw, READ_BUFFER_SIZE)
    }

    private fun skipFully(stream: InputStream, n: Long) {
        var remaining = n
        while (remaining > 0) {
            val skipped = stream.skip(remaining)
            if (skipped <= 0) break
            remaining -= skipped
        }
    }

    // ── SeekableByteChannel ───────────────────────────────────────────────────

    override fun read(dst: ByteBuffer): Int {
        val stream = inputStream ?: throw NonReadableChannelException()
        if (!dst.hasRemaining()) return 0
        try {
            val buffer = ByteArray(dst.remaining())
            var totalRead = 0
            while (totalRead < buffer.size) {
                val n = stream.read(buffer, totalRead, buffer.size - totalRead)
                if (n == -1) break
                totalRead += n
            }
            if (totalRead > 0) {
                dst.put(buffer, 0, totalRead)
                position += totalRead
            } else {
                return -1
            }
            return totalRead
        } catch (e: Exception) {
            Log.e(TAG, "Read error [$uriScheme] pos=$position", e)
            throw e
        }
    }

    override fun write(src: ByteBuffer): Int {
        val stream = outputStream ?: throw NonWritableChannelException()
        if (!src.hasRemaining()) return 0
        try {
            val buffer = ByteArray(src.remaining())
            src.get(buffer)
            stream.write(buffer)
            val written = buffer.size
            position += written
            if (position > size) size = position
            return written
        } catch (e: Exception) {
            Log.e(TAG, "Write error [$uriScheme] pos=$position", e)
            throw e
        }
    }

    override fun position(): Long = position

    override fun position(newPosition: Long): SeekableByteChannel {
        if (newPosition == position) return this

        val gap = newPosition - position
        Log.d(TAG, "seek [$uriScheme]: gap=$gap")

        if (inputStream != null) {
            when {
                gap > 0 && gap < FORWARD_SKIP_THRESHOLD -> {
                    skipFully(inputStream!!, gap)
                    position = newPosition
                }
                gap >= FORWARD_SKIP_THRESHOLD -> {
                    Log.i(TAG, "Large forward seek [$uriScheme] — reopening at offset")
                    runCatching { inputStream?.close() }
                    inputStream = openReadStream(newPosition)
                    position = newPosition
                }
                else -> {
                    Log.w(TAG, "Backward seek [$uriScheme] — reopening at offset")
                    runCatching { inputStream?.close() }
                    inputStream = openReadStream(newPosition)
                    position = newPosition
                }
            }
            return this
        }

        if (outputStream != null) {
            if (gap > 0) {
                Log.i(TAG, "Write gap [$uriScheme] — filling with zeros")
                val zeroBuffer = ByteArray(minOf(gap, 8192L).toInt())
                var remaining = gap
                while (remaining > 0) {
                    val toWrite = minOf(remaining, zeroBuffer.size.toLong()).toInt()
                    outputStream?.write(zeroBuffer, 0, toWrite)
                    remaining -= toWrite
                }
                position = newPosition
                return this
            }
        }

        Log.e(TAG, "Unsupported seek [$uriScheme]: backward on write stream")
        throw UnsupportedOperationException("Backward seeking on write streams not supported for network backends")
    }

    override fun size(): Long = size

    override fun truncate(newSize: Long): SeekableByteChannel {
        // No-op: prevents SFTP clients from getting General failure (code 4) at upload end.
        return this
    }

    override fun isOpen(): Boolean = (inputStream != null || outputStream != null)

    override fun close() {
        Log.d(TAG, "Closing channel [$uriScheme]")
        runCatching { inputStream?.close() }
        runCatching { outputStream?.close() }
        inputStream = null
        outputStream = null
    }
}
