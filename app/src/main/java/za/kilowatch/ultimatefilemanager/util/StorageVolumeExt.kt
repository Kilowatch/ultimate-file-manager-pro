package za.kilowatch.ultimatefilemanager.util

import android.os.Build
import android.os.storage.StorageVolume
import java.io.File

/**
 * Safely fetches the absolute path of a StorageVolume across all Android API levels.
 * Resolves NoSuchMethodError on devices < API 30 where `getDirectory()` doesn't exist.
 */
val StorageVolume.safeDirectoryPath: String?
    get() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return this.directory?.absolutePath
        }
        try {
            val getPathMethod = this.javaClass.getMethod("getPath")
            val path = getPathMethod.invoke(this)
            if (path is String) return path

            val getPathFileMethod = this.javaClass.getMethod("getPathFile")
            val file = getPathFileMethod.invoke(this)
            if (file is File) return file.absolutePath
        } catch (e: Exception) {
            // Ignore reflection exceptions
        }
        return null
    }
