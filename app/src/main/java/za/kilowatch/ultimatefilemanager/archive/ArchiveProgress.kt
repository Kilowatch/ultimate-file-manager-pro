package za.kilowatch.ultimatefilemanager.archive

/**
 * Types of archive operations supported by the progress monitoring system.
 */
enum class ArchiveOperationType {
    EXTRACT,
    COMPRESS,
    ADD,
    DELETE,
    MOVE
}

/**
 * Telemetry snapshot emitted during archive operations for real-time UI visualization.
 */
data class ArchiveProgress(
    val operation: ArchiveOperationType,
    val archiveName: String,
    val currentFileName: String = "",
    val fileIndex: Int = 0,
    val totalFiles: Int = 0,
    val bytesProcessed: Long = 0L,
    val totalBytes: Long = 0L,
    val percentage: Int = 0, // 0..100
    val speedBytesPerSec: Long = 0L,
    val estimatedRemainingMs: Long = 0L
)

/**
 * Callback interface for receiving archive telemetry updates.
 */
fun interface ArchiveProgressListener {
    fun onProgress(progress: ArchiveProgress)
}
