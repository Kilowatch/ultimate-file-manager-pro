package za.kilowatch.ultimatefilemanager.util

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.LruCache
import za.kilowatch.ultimatefilemanager.storage.SafFile
import java.io.File
import java.net.URLDecoder

/**
 * Helper to identify Android app data/obb/media folders and provide cached
 * app icon Drawables for badging folders on mobile.
 */
object AppIconBadgeHelper {

    // In-memory LRU cache for installed app icon drawables (up to 256 apps)
    private val iconCache = LruCache<String, Drawable>(256)

    // Negative cache for unknown or uninstalled packages to prevent repetitive PackageManager IPC calls
    private val notFoundCache = LruCache<String, Boolean>(256)

    /**
     * Determines whether [file] is a direct app package directory inside Android/data, Android/obb, or Android/media.
     * Nested subdirectories (e.g. Android/data/com.foo/files) return false.
     *
     * @param file The file or directory to check
     * @param isDir Whether the file is known to be a directory (defaults to file.isDirectory)
     */
    fun isAppFolder(file: File, isDir: Boolean = file.isDirectory): Boolean {
        if (!isDir) return false

        val name = file.name.trim()
        // App package names contain at least one dot, cannot be empty, and cannot be navigation dots ("..", ".nomedia")
        if (name.isEmpty() || name == ".." || name.startsWith(".") || !name.contains('.')) {
            return false
        }

        val parentPath = file.parent ?: return false
        val cleanParent = SafFile.cleanSafPath(parentPath)
        val decodedParent = try {
            URLDecoder.decode(cleanParent, "UTF-8")
        } catch (_: Exception) {
            cleanParent
        }

        val normalized = decodedParent.replace('\\', '/').trimEnd('/')
        return normalized.endsWith("/Android/data", ignoreCase = true) ||
               normalized.equals("Android/data", ignoreCase = true) ||
               normalized.endsWith("/Android/obb", ignoreCase = true) ||
               normalized.equals("Android/obb", ignoreCase = true) ||
               normalized.endsWith("/Android/media", ignoreCase = true) ||
               normalized.equals("Android/media", ignoreCase = true)
    }

    /**
     * Returns the cached icon drawable for [packageName], or null if not yet cached.
     */
    fun getCachedIcon(packageName: String): Drawable? {
        return iconCache.get(packageName)
    }

    /**
     * Returns true if [packageName] is known to not be installed or failed lookup previously.
     */
    fun isKnownNotFound(packageName: String): Boolean {
        return notFoundCache.get(packageName) == true
    }

    /**
     * Queries [PackageManager] for the app icon of [packageName].
     * Should be executed on a background thread (e.g. Dispatchers.IO) to avoid UI thread IPC overhead.
     */
    fun loadIcon(context: Context, packageName: String): Drawable? {
        iconCache.get(packageName)?.let { return it }
        if (notFoundCache.get(packageName) == true) return null

        return try {
            val pm = context.packageManager
            val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(packageName, 0)
            }
            val icon = appInfo.loadIcon(pm)
            if (icon != null) {
                iconCache.put(packageName, icon)
            } else {
                notFoundCache.put(packageName, true)
            }
            icon
        } catch (_: PackageManager.NameNotFoundException) {
            notFoundCache.put(packageName, true)
            null
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Clears all in-memory caches.
     */
    fun clearCache() {
        iconCache.evictAll()
        notFoundCache.evictAll()
    }
}
