package za.kilowatch.ultimatefilemanager.checksum

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import kotlin.coroutines.coroutineContext

/**
 * High-performance, single-pass cryptographic hashing engine.
 * Computes arbitrary combinations of CRC32, MD5, SHA-1, SHA-256, and SHA-512 concurrently
 * over a single stream read pass with strict ANR guardrails.
 */
object ChecksumEngine {

    const val BUFFER_SIZE = 128 * 1024 // 128 KB
    const val LARGE_FILE_THRESHOLD_BYTES = 2L * 1024 * 1024 * 1024 // 2 GB
    const val PROGRESS_INTERVAL_BYTES = 2L * 1024 * 1024 // 2 MB
    const val PROGRESS_INTERVAL_MS = 150L // 150 ms

    data class ProgressInfo(
        val bytesRead: Long,
        val totalBytes: Long,
        val percent: Int,
        val speedBytesPerSec: Double,
        val etaSeconds: Long
    )

    /**
     * Computes the requested hashes for the given [source] in a single read pass.
     * Executes strictly on [Dispatchers.IO].
     */
    suspend fun computeHashes(
        context: Context,
        source: UfmFileSource,
        algorithms: Set<HashAlgorithm>,
        onProgress: (suspend (ProgressInfo) -> Unit)? = null
    ): Map<HashAlgorithm, String> = withContext(Dispatchers.IO) {
        if (algorithms.isEmpty()) return@withContext emptyMap()

        val includesCrc = algorithms.contains(HashAlgorithm.CRC32)
        val crc = if (includesCrc) CRC32() else null

        val digests = mutableMapOf<HashAlgorithm, MessageDigest>()
        for (algo in algorithms) {
            if (algo != HashAlgorithm.CRC32) {
                algo.createMessageDigest()?.let { digests[algo] = it }
            }
        }

        var stream: InputStream? = null
        try {
            stream = source.openStream(context)
            val buffer = ByteArray(BUFFER_SIZE)
            var totalRead = 0L
            val totalSize = source.size
            val startTime = System.currentTimeMillis()
            var lastProgressTime = startTime
            var lastProgressBytes = 0L

            while (true) {
                coroutineContext.ensureActive()
                val bytesRead = stream.read(buffer)
                if (bytesRead <= 0) break

                totalRead += bytesRead

                if (crc != null) {
                    crc.update(buffer, 0, bytesRead)
                }
                for (digest in digests.values) {
                    digest.update(buffer, 0, bytesRead)
                }

                val now = System.currentTimeMillis()
                val bytesSinceLast = totalRead - lastProgressBytes
                val timeSinceLast = now - lastProgressTime

                if (bytesSinceLast >= PROGRESS_INTERVAL_BYTES || timeSinceLast >= PROGRESS_INTERVAL_MS) {
                    val totalElapsedSec = (now - startTime).coerceAtLeast(1) / 1000.0
                    val speed = totalRead / totalElapsedSec
                    val percent = if (totalSize > 0) {
                        ((totalRead * 100) / totalSize).toInt().coerceIn(0, 100)
                    } else {
                        -1
                    }
                    val eta = if (totalSize > totalRead && speed > 0) {
                        ((totalSize - totalRead) / speed).toLong()
                    } else {
                        0L
                    }

                    onProgress?.invoke(
                        ProgressInfo(
                            bytesRead = totalRead,
                            totalBytes = totalSize,
                            percent = percent,
                            speedBytesPerSec = speed,
                            etaSeconds = eta
                        )
                    )

                    lastProgressBytes = totalRead
                    lastProgressTime = now
                }
            }

            // Final progress emission at 100%
            if (totalSize > 0) {
                val totalElapsedSec = (System.currentTimeMillis() - startTime).coerceAtLeast(1) / 1000.0
                val speed = totalRead / totalElapsedSec
                onProgress?.invoke(
                    ProgressInfo(
                        bytesRead = totalRead,
                        totalBytes = totalSize,
                        percent = 100,
                        speedBytesPerSec = speed,
                        etaSeconds = 0
                    )
                )
            }

            val results = mutableMapOf<HashAlgorithm, String>()
            if (crc != null) {
                results[HashAlgorithm.CRC32] = HashAlgorithm.formatCrc32(crc.value)
            }
            for ((algo, digest) in digests) {
                results[algo] = HashAlgorithm.formatDigest(digest.digest())
            }
            results
        } finally {
            try {
                stream?.close()
            } catch (_: Throwable) {
            }
        }
    }
}
