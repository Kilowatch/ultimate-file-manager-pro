package za.kilowatch.ultimatefilemanager.storage

import java.io.File

/**
 * Singleton clipboard for file copy/move operations.
 * Each entry tracks its own operation independently, allowing mixed COPY+CUT batches.
 */
object FileClipboard {
    enum class Operation { COPY, MOVE, EXTRACT }

    data class Entry(val file: File, val operation: Operation)

    var entries: List<Entry> = emptyList()
        private set

    /** Temporary directory for staged extraction files awaiting folder selection. */
    var activeTempExtractDir: File? = null
        private set

    // Convenience aliases used by existing code
    val files: List<File> get() = entries.map { it.file }
    val operation: Operation get() = entries.firstOrNull()?.operation ?: Operation.COPY

    /**
     * Appends [selectedFiles] with [op] to the clipboard.
     * If a file is already in the clipboard it is replaced (newest op wins).
     */
    fun add(selectedFiles: List<File>, op: Operation) {
        if (op != Operation.EXTRACT) {
            clearTempDir()
        }
        val newPaths = selectedFiles.map { it.absolutePath }.toSet()
        entries = entries.filter { it.file.absolutePath !in newPaths } +
                selectedFiles.map { Entry(it, op) }
    }

    /** Legacy alias — replaces entire clipboard (single op). */
    fun set(selectedFiles: List<File>, op: Operation) {
        if (op != Operation.EXTRACT) {
            clearTempDir()
        }
        entries = selectedFiles.map { Entry(it, op) }
    }

    /**
     * Sets the clipboard for a staged extraction operation from [tempDir].
     */
    fun setExtract(extractedFiles: List<File>, tempDir: File) {
        clearTempDir()
        activeTempExtractDir = tempDir
        entries = extractedFiles.map { Entry(it, Operation.EXTRACT) }
    }

    fun clearTempDir() {
        activeTempExtractDir?.let { dir ->
            try {
                if (dir.exists()) dir.deleteRecursively()
            } catch (_: Exception) {}
        }
        activeTempExtractDir = null
    }

    fun remove(file: File) {
        entries = entries.filter { it.file.absolutePath != file.absolutePath }
        if (entries.isEmpty()) {
            clearTempDir()
        }
    }

    fun hasItems(): Boolean = entries.isNotEmpty()

    fun clear() {
        entries = emptyList()
        clearTempDir()
    }
}
