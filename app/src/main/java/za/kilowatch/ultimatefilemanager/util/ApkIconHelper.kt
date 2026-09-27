package za.kilowatch.ultimatefilemanager.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import org.json.JSONObject
import za.kilowatch.ultimatefilemanager.storage.SafFile
import za.kilowatch.ultimatefilemanager.storage.SafTreeManager
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * Authoritative helper for APK and multi-APK bundle icon extraction and extension classification.
 * Supports standard .apk, split bundles (.xapk, .apks), and APKMirror bundles (.apkm).
 */
object ApkIconHelper {

    val PACKAGE_EXTENSIONS: Set<String> = setOf("apk", "xapk", "apks", "apkm")

    fun isApkOrBundle(nameOrExt: String?): Boolean {
        if (nameOrExt.isNullOrEmpty()) return false
        val ext = if (nameOrExt.contains('.')) {
            nameOrExt.substringAfterLast('.', "")
        } else {
            nameOrExt
        }
        return ext.lowercase() in PACKAGE_EXTENSIONS
    }

    /**
     * Resolves the launcher icon for any APK or multi-APK bundle (.apk, .xapk, .apks, .apkm).
     * Works with regular local files, cache files, and SAF storage.
     */
    fun resolveIcon(context: Context, file: File): Drawable? {
        val ext = file.extension.lowercase()
        if (ext !in PACKAGE_EXTENSIONS) return null

        val isSaf = file is SafFile ||
            SafTreeManager.isSafPath(file.absolutePath) ||
            SafTreeManager.hasTreePermissionForPath(context, file.absolutePath)

        return if (isSaf) {
            resolveSafIcon(context, file, ext)
        } else {
            resolveLocalIcon(context, file, ext)
        }
    }

    private fun resolveLocalIcon(context: Context, file: File, ext: String): Drawable? {
        if (!file.exists() || !file.canRead()) return null
        val pm = context.packageManager

        if (ext == "apk") {
            return try {
                val pi = pm.getPackageArchiveInfo(file.absolutePath, 0)
                if (pi != null) {
                    pi.applicationInfo?.sourceDir = file.absolutePath
                    pi.applicationInfo?.publicSourceDir = file.absolutePath
                    val raw = pi.applicationInfo?.loadIcon(pm)
                    if (raw != null) toSafeDrawable(context, raw) else null
                } else null
            } catch (_: Throwable) { null }
        }

        // Multi-APK bundle formats: xapk, apks, apkm
        var tempBaseApk: File? = null
        try {
            var rootBitmap: Bitmap? = null
            var manifestJson: JSONObject? = null
            var baseEntryName: String? = null
            var fallbackApkEntryName: String? = null
            var largestApkSize: Long = -1L
            var largestApkEntryName: String? = null

            ZipFile(file).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val name = entry.name
                    val nameLower = name.lowercase()

                    // Check for manifest.json
                    if (name.equals("manifest.json", ignoreCase = true)) {
                        try {
                            val content = zip.getInputStream(entry).use { it.reader().readText() }
                            manifestJson = JSONObject(content)
                        } catch (_: Throwable) {}
                    }

                    // Check for root or embedded icon image
                    if (rootBitmap == null && (
                        nameLower == "icon.png" || nameLower.endsWith("/icon.png") ||
                        nameLower == "icon.webp" || nameLower.endsWith("/icon.webp") ||
                        nameLower == "ic_launcher.png" || nameLower.endsWith("/ic_launcher.png")
                    )) {
                        try {
                            rootBitmap = BitmapFactory.decodeStream(zip.getInputStream(entry))
                        } catch (_: Throwable) {}
                    }

                    // Scan for APK entries to locate base.apk
                    if (nameLower.endsWith(".apk")) {
                        val fileNameOnly = name.substringAfterLast('/')
                        val isSplit = fileNameOnly.startsWith("split_", ignoreCase = true) ||
                            fileNameOnly.contains("split_config", ignoreCase = true) ||
                            fileNameOnly.startsWith("config.", ignoreCase = true)

                        if (fileNameOnly.equals("base.apk", ignoreCase = true)) {
                            baseEntryName = name
                        } else if (!isSplit && baseEntryName == null) {
                            if (fileNameOnly.contains("base", ignoreCase = true)) {
                                baseEntryName = name
                            } else if (fallbackApkEntryName == null) {
                                fallbackApkEntryName = name
                            }
                        }

                        val size = entry.size
                        if (size > largestApkSize) {
                            largestApkSize = size
                            largestApkEntryName = name
                        }
                    }
                }

                // If root icon wasn't found, check manifest.json's "icon" field (common in APKM)
                if (rootBitmap == null && manifestJson != null) {
                    val iconName = manifestJson.optString("icon")
                    if (iconName.isNotEmpty()) {
                        val iconEntry = zip.getEntry(iconName)
                            ?: zip.entries().asSequence().firstOrNull { it.name.endsWith(iconName, ignoreCase = true) }
                        if (iconEntry != null) {
                            try {
                                rootBitmap = BitmapFactory.decodeStream(zip.getInputStream(iconEntry))
                            } catch (_: Throwable) {}
                        }
                    }
                }

                if (rootBitmap != null) {
                    return toSafeDrawable(context, BitmapDrawable(context.resources, rootBitmap))
                }

                // Extract the candidate base APK to a temp file
                val targetApkEntryName = baseEntryName ?: fallbackApkEntryName ?: largestApkEntryName
                if (targetApkEntryName != null) {
                    val entry = zip.getEntry(targetApkEntryName)
                    if (entry != null) {
                        tempBaseApk = File(context.cacheDir, "bundle_icon_${UUID.randomUUID()}.apk")
                        zip.getInputStream(entry).use { input ->
                            tempBaseApk!!.outputStream().use { output -> input.copyTo(output) }
                        }
                    }
                }
            }

            if (tempBaseApk != null && tempBaseApk!!.exists() && tempBaseApk!!.length() > 0L) {
                val pi = pm.getPackageArchiveInfo(tempBaseApk!!.absolutePath, 0)
                if (pi != null) {
                    pi.applicationInfo?.sourceDir = tempBaseApk!!.absolutePath
                    pi.applicationInfo?.publicSourceDir = tempBaseApk!!.absolutePath
                    val raw = pi.applicationInfo?.loadIcon(pm)
                    if (raw != null) {
                        return toSafeDrawable(context, raw)
                    }
                }
                // Fallback to installed app icon if package name is identified
                val pkgName = pi?.packageName ?: manifestJson?.optString("package_name")
                if (!pkgName.isNullOrEmpty()) {
                    try {
                        val installed = pm.getApplicationIcon(pkgName)
                        return toSafeDrawable(context, installed)
                    } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) {
            null
        } finally {
            tempBaseApk?.delete()
        }
        return null
    }

    private fun resolveSafIcon(context: Context, file: File, ext: String): Drawable? {
        val inStream = SafTreeManager.openInputStream(context, file.absolutePath) ?: return null

        fun densityRank(name: String): Int = when {
            "xxxhdpi" in name -> 6
            "xxhdpi"  in name -> 5
            "xhdpi"   in name -> 4
            "hdpi"    in name -> 3
            "mdpi"    in name -> 2
            "ldpi"    in name -> 1
            else              -> 0
        }

        fun isIconEntry(n: String) =
            n == "icon.png" ||
            n == "icon.webp" ||
            n == "ic_launcher.png" ||
            n.endsWith("/icon.png") ||
            n.endsWith("/icon.webp") ||
            (n.startsWith("res/mipmap")  && n.endsWith(".png") && "ic_launcher" in n) ||
            (n.startsWith("res/drawable") && n.endsWith(".png") && "ic_launcher" in n)

        var bestRank = -1
        var bestBytes: ByteArray? = null

        try {
            ZipInputStream(inStream).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val n = entry.name.lowercase()
                    if (isIconEntry(n)) {
                        val rank = densityRank(n)
                        if (rank > bestRank) {
                            val bytes = zip.readBytes()
                            if (bytes.isNotEmpty()) {
                                bestRank = rank
                                bestBytes = bytes
                                if (rank == 6) break
                            }
                        }
                    }
                    entry = zip.nextEntry
                }
            }
        } catch (_: Throwable) {}

        if (bestBytes != null && bestBytes!!.isNotEmpty()) {
            val bmp = BitmapFactory.decodeByteArray(bestBytes, 0, bestBytes!!.size)
            if (bmp != null) return toSafeDrawable(context, BitmapDrawable(context.resources, bmp))
        }

        // If streaming fast-path failed, copy to temp cache file and run resolveLocalIcon
        var tempFile: File? = null
        try {
            tempFile = File(context.cacheDir, "saf_thumb_${UUID.randomUUID()}.$ext")
            SafTreeManager.openInputStream(context, file.absolutePath)?.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            }
            if (tempFile.exists() && tempFile.length() > 0L) {
                return resolveLocalIcon(context, tempFile, ext)
            }
        } catch (_: Throwable) {
            null
        } finally {
            tempFile?.delete()
        }
        return null
    }

    fun toSafeDrawable(context: Context, drawable: Drawable): Drawable {
        return try {
            val w = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 144
            val h = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 144
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, w, h)
            drawable.draw(canvas)
            BitmapDrawable(context.resources, bitmap)
        } catch (_: Throwable) {
            drawable
        }
    }
}
