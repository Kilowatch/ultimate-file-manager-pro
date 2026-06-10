package za.kilowatch.ultimatefilemanager.util

import java.io.InputStream
import java.io.OutputStream

/**
 * Streaming copy helper with progress reporting and a large buffer
 * optimised for LAN transfers (256 KB vs Kotlin's default 8 KB).
 */
object CopyHelper {

    private const val BUFFER_SIZE = 256 * 1024   // 256 KB

    /**
     * Copies [input] → [output] using a 256 KB buffer.
     *
     * @param input        Source stream (will NOT be closed by this method).
     * @param output       Destination stream (will NOT be closed by this method).
     * @param totalSize    Expected total bytes. Pass ≤ 0 if unknown.
     * @param onProgress   Called periodically with (bytesCopied, totalBytes).
     *                     [totalBytes] is [totalSize] when known, or -1 when unknown.
     * @return The total number of bytes actually copied.
     */
    suspend fun copy(
        input: InputStream,
        output: OutputStream,
        totalSize: Long = -1L,
        onProgress: ((bytesCopied: Long, totalBytes: Long) -> Unit)? = null
    ): Long {
        val buffer = ByteArray(BUFFER_SIZE)
        var bytesCopied = 0L
        val total = if (totalSize > 0) totalSize else -1L

        while (true) {
            kotlinx.coroutines.yield() // Check for cancellation
            val bytesRead = input.read(buffer)
            if (bytesRead < 0) break
            output.write(buffer, 0, bytesRead)
            bytesCopied += bytesRead
            onProgress?.invoke(bytesCopied, total)
        }
        output.flush()
        return bytesCopied
    }
}
