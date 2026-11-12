package za.kilowatch.ultimatefilemanager.util

import android.system.Os
import android.system.StructStat
import java.io.File
import java.io.FileInputStream
import java.util.Arrays

/**
 * Encapsulates POSIX-level physical block allocation inspection and sparse file analysis.
 */
data class FileAllocation(
    val nominalSize: Long,
    val allocatedBytes: Long,
    val isSparse: Boolean,
    val allocationPercentage: Float
)

sealed class PartialCompareResult {
    /** Files have different nominal sizes */
    object SizeMismatch : PartialCompareResult()
    /** Files have different headers (not the same target file) */
    object HeaderMismatch : PartialCompareResult()
    /** Both files are complete and content/hashes match */
    object FullMatch : PartialCompareResult()
    /** One or both files are sparse/incomplete, but initial headers and sampled non-zero blocks match */
    data class PartialMatch(
        val leftAllocation: FileAllocation,
        val rightAllocation: FileAllocation
    ) : PartialCompareResult()
    /** Files differ in content */
    object ContentMismatch : PartialCompareResult()
}

object SparseFileUtils {
    private const val TAG = "SparseFileUtils"
    private const val HEADER_CHECK_BYTES = 64 * 1024 // 64 KB

    /**
     * Inspects physical disk allocation for [file] using POSIX lstat (`stat.st_blocks * 512L`).
     * Handles non-sparse files, zero-byte files, and graceful fallback for remote or unsupported filesystems.
     */
    fun getFileAllocation(file: File): FileAllocation {
        if (!file.exists() || !file.isFile) {
            val len = try { file.length() } catch (_: Throwable) { 0L }
            return FileAllocation(len, len, isSparse = false, allocationPercentage = 100f)
        }
        return try {
            val stat: StructStat = Os.lstat(file.absolutePath)
            val nominal = stat.st_size
            val allocated = stat.st_blocks * 512L
            calculateAllocation(nominal, allocated)
        } catch (e: Throwable) {
            // Graceful fallback for non-POSIX or permission-restricted files
            val len = file.length()
            FileAllocation(len, len, isSparse = false, allocationPercentage = 100f)
        }
    }

    /**
     * Pure calculation helper for allocation metrics, easily testable in unit tests.
     */
    fun calculateAllocation(nominalSize: Long, allocatedBytes: Long): FileAllocation {
        if (nominalSize <= 0L) {
            return FileAllocation(nominalSize, 0L, isSparse = false, allocationPercentage = 100f)
        }
        // If allocated bytes are less than nominal size minus 4KB (1 filesystem block),
        // or for files > 64KB allocated is < 98% of nominal, it's sparse/partially allocated.
        val isSparse = if (nominalSize > 64 * 1024L) {
            allocatedBytes < (nominalSize * 0.98).toLong()
        } else {
            allocatedBytes < nominalSize - 4096L
        }

        val pct = if (isSparse) {
            ((allocatedBytes.toDouble() / nominalSize.toDouble()) * 100.0).toFloat().coerceIn(0f, 100f)
        } else {
            100f
        }

        return FileAllocation(
            nominalSize = nominalSize,
            allocatedBytes = if (isSparse) allocatedBytes else nominalSize,
            isSparse = isSparse,
            allocationPercentage = pct
        )
    }

    /**
     * Returns true if [file] is a sparse or pre-allocated incomplete file.
     */
    fun isSparseFile(file: File): Boolean {
        return getFileAllocation(file).isSparse
    }

    /**
     * Compares two files to check if one is an incomplete/partial copy of the other.
     * 1. Checks nominal sizes match.
     * 2. Reads and verifies initial 64 KB header of both files.
     * 3. Samples non-zero block offsets across the partial file to verify content matches the reference file.
     */
    fun comparePartialFiles(fileA: File, fileB: File): PartialCompareResult {
        if (!fileA.exists() || !fileB.exists()) return PartialCompareResult.ContentMismatch
        val allocA = getFileAllocation(fileA)
        val allocB = getFileAllocation(fileB)

        if (allocA.nominalSize != allocB.nominalSize) {
            return PartialCompareResult.SizeMismatch
        }

        // Check header match
        val headerMatch = checkHeaderMatch(fileA, fileB)
        if (!headerMatch) {
            return PartialCompareResult.HeaderMismatch
        }

        // If neither is sparse and headers match, check full or sampled content
        if (!allocA.isSparse && !allocB.isSparse) {
            return if (checkSampledBlocksMatch(fileA, fileB, allocA.nominalSize)) {
                PartialCompareResult.FullMatch
            } else {
                PartialCompareResult.ContentMismatch
            }
        }

        // At least one is sparse: sample non-zero blocks to verify partial match
        val partialSampleMatch = checkSampledBlocksMatch(fileA, fileB, allocA.nominalSize)
        return if (partialSampleMatch) {
            PartialCompareResult.PartialMatch(allocA, allocB)
        } else {
            PartialCompareResult.ContentMismatch
        }
    }

    private fun checkHeaderMatch(fileA: File, fileB: File): Boolean {
        return try {
            val bufA = ByteArray(HEADER_CHECK_BYTES)
            val bufB = ByteArray(HEADER_CHECK_BYTES)
            val readA = FileInputStream(fileA).use { it.read(bufA) }
            val readB = FileInputStream(fileB).use { it.read(bufB) }
            if (readA != readB) return false
            if (readA <= 0) return true
            Arrays.equals(bufA.copyOf(readA), bufB.copyOf(readB))
        } catch (e: Exception) {
            GoRoLog.w(TAG, "checkHeaderMatch failed: ${e.message}")
            false
        }
    }

    private fun checkSampledBlocksMatch(fileA: File, fileB: File, totalSize: Long): Boolean {
        if (totalSize <= HEADER_CHECK_BYTES) return true
        return try {
            val blockSize = 16 * 1024 // 16 KB
            val bufA = ByteArray(blockSize)
            val bufB = ByteArray(blockSize)

            val sampleCount = 8
            val step = (totalSize - HEADER_CHECK_BYTES) / (sampleCount + 1)

            FileInputStream(fileA).channel.use { chA ->
                FileInputStream(fileB).channel.use { chB ->
                    val bbA = java.nio.ByteBuffer.wrap(bufA)
                    val bbB = java.nio.ByteBuffer.wrap(bufB)

                    for (i in 1..sampleCount) {
                        val pos = HEADER_CHECK_BYTES + (i * step).coerceAtMost(totalSize - blockSize)
                        bbA.clear()
                        bbB.clear()
                        chA.position(pos)
                        chB.position(pos)
                        val rA = chA.read(bbA)
                        val rB = chB.read(bbB)
                        if (rA > 0 && rB > 0) {
                            val hasNonZeroA = hasNonZeroBytes(bufA, rA)
                            val hasNonZeroB = hasNonZeroBytes(bufB, rB)
                            if (hasNonZeroA && hasNonZeroB) {
                                val minLen = minOf(rA, rB)
                                for (k in 0 until minLen) {
                                    if (bufA[k] != bufB[k] && bufA[k] != 0.toByte() && bufB[k] != 0.toByte()) {
                                        return false
                                    }
                                }
                            }
                        }
                    }
                }
            }
            true
        } catch (e: Exception) {
            GoRoLog.w(TAG, "checkSampledBlocksMatch failed: ${e.message}")
            false
        }
    }

    private fun hasNonZeroBytes(buffer: ByteArray, length: Int): Boolean {
        for (i in 0 until length) {
            if (buffer[i] != 0.toByte()) return true
        }
        return false
    }
}
