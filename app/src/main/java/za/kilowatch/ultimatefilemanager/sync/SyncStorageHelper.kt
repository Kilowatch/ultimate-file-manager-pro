package za.kilowatch.ultimatefilemanager.sync

import android.content.Context
import android.util.Log
import za.kilowatch.ultimatefilemanager.settings.RootPreferenceManager
import za.kilowatch.ultimatefilemanager.storage.RootFile
import za.kilowatch.ultimatefilemanager.storage.RootShellWrapper
import za.kilowatch.ultimatefilemanager.storage.SafFile
import za.kilowatch.ultimatefilemanager.storage.SafTreeManager
import za.kilowatch.ultimatefilemanager.storage.ShizukuFile
import za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Unified storage abstraction for [SyncWorker] and [za.kilowatch.ultimatefilemanager.sync.advanced.AdvancedSyncWorker].
 * Seamlessly bridges local file operations across all 4 storage tiers:
 * 1. SAF ([SafTreeManager])
 * 2. Root ([RootShellWrapper])
 * 3. Elevated / Shizuku / Porter ([ShizukuShellWrapper])
 * 4. Standard local filesystem ([java.io.File])
 */
object SyncStorageHelper {

    private const val TAG = "SyncStorageHelper"

    enum class StorageType {
        SAF,
        ROOT,
        SHIZUKU,
        DIRECT
    }

    /**
     * Resolves the access tier for a given local path.
     */
    fun resolveStorageType(context: Context, path: String): StorageType {
        val cleanPath = SafFile.cleanSafPath(path)
        if (cleanPath.isEmpty()) return StorageType.DIRECT

        // 1. SAF: tree URI or registered tree permission
        if (SafTreeManager.isSafPath(cleanPath) || SafTreeManager.hasTreePermissionForPath(context, cleanPath)) {
            return StorageType.SAF
        }

        // 2. Root: user enabled root, root authorized, and path is either in root partition,
        // a protected folder (Android/data, Android/obb), or not readable via direct File API.
        if (RootPreferenceManager.isRootEnabled(context) && RootShellWrapper.isAuthorized(context)) {
            if (RootShellWrapper.isRootPath(cleanPath) ||
                ShizukuShellWrapper.isProtectedPath(cleanPath) ||
                !File(cleanPath).canRead()
            ) {
                return StorageType.ROOT
            }
        }

        // 3. Shizuku / Porter: elevated manager active for protected path
        if (ShizukuShellWrapper.canUseShizukuForPath(cleanPath)) {
            return StorageType.SHIZUKU
        }

        // 4. Fallback to standard local filesystem
        return StorageType.DIRECT
    }

    /**
     * Checks whether the directory at [path] exists and is readable.
     */
    fun isValidDirectory(context: Context, path: String): Boolean {
        if (path.isEmpty()) return false
        val cleanPath = SafFile.cleanSafPath(path)
        return when (resolveStorageType(context, cleanPath)) {
            StorageType.SAF -> {
                SafTreeManager.exists(context, cleanPath) && SafTreeManager.isDirectory(context, cleanPath)
            }
            StorageType.ROOT -> {
                if (!RootShellWrapper.exists(cleanPath)) return false
                val safePath = RootShellWrapper.escapeShellPath(cleanPath)
                val (code, _) = RootShellWrapper.runCommand("test -d '$safePath'")
                code == 0
            }
            StorageType.SHIZUKU -> {
                if (!ShizukuShellWrapper.exists(cleanPath)) return false
                val working = ShizukuShellWrapper.getWorkingPath(cleanPath)
                val (code, _) = ShizukuShellWrapper.runCommand("test -d '$working'")
                code == 0
            }
            StorageType.DIRECT -> {
                val dir = File(cleanPath)
                dir.exists() && dir.isDirectory && dir.canRead()
            }
        }
    }

    /**
     * Checks if a file or directory exists at [path].
     */
    fun exists(context: Context, path: String): Boolean {
        if (path.isEmpty()) return false
        val cleanPath = SafFile.cleanSafPath(path)
        return when (resolveStorageType(context, cleanPath)) {
            StorageType.SAF -> SafTreeManager.exists(context, cleanPath)
            StorageType.ROOT -> RootShellWrapper.exists(cleanPath)
            StorageType.SHIZUKU -> ShizukuShellWrapper.exists(cleanPath)
            StorageType.DIRECT -> File(cleanPath).exists()
        }
    }

    /**
     * Lists child files and directories under [path].
     */
    fun listFiles(context: Context, path: String): List<File> {
        val cleanPath = SafFile.cleanSafPath(path)
        return when (resolveStorageType(context, cleanPath)) {
            StorageType.SAF -> SafTreeManager.listFiles(context, cleanPath)
            StorageType.ROOT -> RootShellWrapper.listFiles(cleanPath)
            StorageType.SHIZUKU -> ShizukuShellWrapper.listFiles(cleanPath)
            StorageType.DIRECT -> File(cleanPath).listFiles()?.toList() ?: emptyList()
        }
    }

    /**
     * Returns the file size in bytes.
     */
    fun getFileSize(context: Context, file: File): Long {
        if (file is SafFile) return SafTreeManager.getFileSize(context, file.absolutePath)
        if (file is RootFile) return file.length()
        if (file is ShizukuFile) return file.length()

        val cleanPath = SafFile.cleanSafPath(file.absolutePath)
        return when (resolveStorageType(context, cleanPath)) {
            StorageType.SAF -> SafTreeManager.getFileSize(context, cleanPath)
            StorageType.ROOT -> RootShellWrapper.getFileSize(cleanPath)
            StorageType.SHIZUKU -> ShizukuShellWrapper.getFileSize(cleanPath)
            StorageType.DIRECT -> file.length()
        }
    }

    /**
     * Returns the last modified timestamp in milliseconds.
     */
    fun getLastModified(context: Context, file: File): Long {
        if (file is RootFile) return RootShellWrapper.getLastModified(file.absolutePath)
        if (file is ShizukuFile) return ShizukuShellWrapper.getLastModified(file.absolutePath)

        val cleanPath = SafFile.cleanSafPath(file.absolutePath)
        return when (resolveStorageType(context, cleanPath)) {
            StorageType.SAF -> file.lastModified()
            StorageType.ROOT -> RootShellWrapper.getLastModified(cleanPath)
            StorageType.SHIZUKU -> ShizukuShellWrapper.getLastModified(cleanPath)
            StorageType.DIRECT -> file.lastModified()
        }
    }

    /**
     * Opens an [InputStream] for reading [file].
     */
    fun openInputStream(context: Context, file: File): InputStream {
        val cleanPath = SafFile.cleanSafPath(file.absolutePath)
        return when (resolveStorageType(context, cleanPath)) {
            StorageType.SAF -> {
                SafTreeManager.openInputStream(context, cleanPath)
                    ?: throw FileNotFoundException("Cannot open SAF input stream for $cleanPath")
            }
            StorageType.ROOT -> {
                val stream = RootShellWrapper.openInputStream(cleanPath)
                stream
            }
            StorageType.SHIZUKU -> {
                val cacheDir = context.externalCacheDir ?: context.cacheDir
                val tmp = File.createTempFile("ufm_shizuku_sync_", ".tmp", cacheDir)
                if (!ShizukuShellWrapper.copy(cleanPath, tmp.absolutePath)) {
                    tmp.delete()
                    throw IOException("Failed to stage Shizuku file for reading: $cleanPath")
                }
                object : FileInputStream(tmp) {
                    override fun close() {
                        try {
                            super.close()
                        } finally {
                            tmp.delete()
                        }
                    }
                }
            }
            StorageType.DIRECT -> FileInputStream(file)
        }
    }

    /**
     * Opens an [OutputStream] for writing to [path].
     */
    fun openOutputStream(context: Context, path: String): OutputStream {
        val cleanPath = SafFile.cleanSafPath(path)
        return when (resolveStorageType(context, cleanPath)) {
            StorageType.SAF -> {
                SafTreeManager.openOutputStream(context, cleanPath)
                    ?: throw IOException("Cannot open SAF output stream for $cleanPath")
            }
            StorageType.ROOT -> {
                RootShellWrapper.openOutputStream(cleanPath)
            }
            StorageType.SHIZUKU -> {
                val cacheDir = context.externalCacheDir ?: context.cacheDir
                val tmp = File.createTempFile("ufm_shizuku_down_", ".tmp", cacheDir)
                val fos = FileOutputStream(tmp)
                object : OutputStream() {
                    override fun write(b: Int) = fos.write(b)
                    override fun write(b: ByteArray) = fos.write(b)
                    override fun write(b: ByteArray, off: Int, len: Int) = fos.write(b, off, len)
                    override fun flush() = fos.flush()
                    override fun close() {
                        try {
                            fos.close()
                            ShizukuShellWrapper.move(tmp.absolutePath, cleanPath)
                        } finally {
                            tmp.delete()
                        }
                    }
                }
            }
            StorageType.DIRECT -> {
                val targetFile = File(cleanPath)
                targetFile.parentFile?.mkdirs()
                FileOutputStream(targetFile)
            }
        }
    }

    /**
     * Deletes a file or directory.
     */
    fun delete(context: Context, file: File): Boolean {
        val cleanPath = SafFile.cleanSafPath(file.absolutePath)
        return when (resolveStorageType(context, cleanPath)) {
            StorageType.SAF -> SafTreeManager.delete(context, cleanPath)
            StorageType.ROOT -> RootShellWrapper.delete(cleanPath)
            StorageType.SHIZUKU -> ShizukuShellWrapper.delete(cleanPath)
            StorageType.DIRECT -> file.delete()
        }
    }

    /**
     * Creates a directory at [path].
     */
    fun createFolder(context: Context, path: String): Boolean {
        val cleanPath = SafFile.cleanSafPath(path)
        return when (resolveStorageType(context, cleanPath)) {
            StorageType.SAF -> SafTreeManager.createFolder(context, cleanPath)
            StorageType.ROOT -> RootShellWrapper.mkdir(cleanPath)
            StorageType.SHIZUKU -> ShizukuShellWrapper.mkdir(cleanPath)
            StorageType.DIRECT -> {
                val dir = File(cleanPath)
                if (!dir.exists()) dir.mkdirs()
                dir.exists() && dir.isDirectory
            }
        }
    }

    fun getFileSize(context: Context, path: String): Long = getFileSize(context, File(path))
    fun getLastModified(context: Context, path: String): Long = getLastModified(context, File(path))
    fun openInputStream(context: Context, path: String): InputStream = openInputStream(context, File(path))
    fun delete(context: Context, path: String): Boolean = delete(context, File(path))

    /**
     * Resolves a child [File] abstraction for a parent directory path and child name.
     */
    fun resolveChildFile(context: Context, parentPath: String, name: String): File {
        val cleanParent = SafFile.cleanSafPath(parentPath)
        if (name.isEmpty()) {
            return when (resolveStorageType(context, cleanParent)) {
                StorageType.SAF -> SafFile(cleanParent, isDir = true)
                StorageType.ROOT -> RootFile(cleanParent, isDir = true)
                StorageType.SHIZUKU -> ShizukuFile(cleanParent, isDir = true)
                StorageType.DIRECT -> File(cleanParent)
            }
        }
        val fullChild = if (cleanParent == "/" || cleanParent.isEmpty()) "/$name" else "$cleanParent/$name"
        return when (resolveStorageType(context, cleanParent)) {
            StorageType.SAF -> SafFile(SafTreeManager.getSafChildPath(cleanParent, name))
            StorageType.ROOT -> RootFile(fullChild, isDir = false)
            StorageType.SHIZUKU -> ShizukuFile(fullChild, isDir = false)
            StorageType.DIRECT -> File(File(cleanParent), name)
        }
    }

    /**
     * Resolves a child [File] abstraction for a parent directory and child name.
     */
    fun resolveChildFile(context: Context, parentDir: File, name: String): File {
        return resolveChildFile(context, parentDir.absolutePath, name)
    }
}
