package za.kilowatch.ultimatefilemanager.util

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Environment
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ApkMetadataExtractor {

    data class AppInfo(
        val label: String,
        val packageName: String,
        val versionName: String?,
        val versionCode: Long,
        val firstInstallTime: Long,
        val lastUpdateTime: Long,
        val targetSdk: Int,
        val minSdk: Int,
        val sourceDir: String,
        val splitSourceDirs: List<String>,
        val hasObb: Boolean,
        val appSize: Long,
        val permissions: List<String>?,
        val icon: Drawable?
    )

    fun extractAppInfo(context: Context, packageName: String): AppInfo? {
        return try {
            val pm = context.packageManager
            val pkgInfo = pm.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
            val appInfo = pkgInfo.applicationInfo ?: return null

            val label = pm.getApplicationLabel(appInfo).toString()
            val versionName = pkgInfo.versionName
            @Suppress("DEPRECATION")
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pkgInfo.longVersionCode
            } else {
                pkgInfo.versionCode.toLong()
            }
            val targetSdk = appInfo.targetSdkVersion
            val minSdk = appInfo.minSdkVersion
            val sourceDir = appInfo.sourceDir ?: ""
            val splitSourceDirs = appInfo.splitSourceDirs?.toList() ?: emptyList()

            val obbDir = try {
                File(Environment.getExternalStorageDirectory(), "Android/obb/$packageName")
            } catch (_: Exception) { null }
            val hasObb = obbDir?.exists() == true &&
                    obbDir.listFiles()?.any { it.extension.equals("obb", ignoreCase = true) } == true

            val apkFile = File(sourceDir)
            val appSize = if (apkFile.exists()) apkFile.length() else 0L

            val permissions = pkgInfo.requestedPermissions?.toList()

            val icon = try { pm.getApplicationIcon(appInfo) } catch (_: Exception) { null }

            AppInfo(
                label = label,
                packageName = packageName,
                versionName = versionName,
                versionCode = versionCode,
                firstInstallTime = pkgInfo.firstInstallTime,
                lastUpdateTime = pkgInfo.lastUpdateTime,
                targetSdk = targetSdk,
                minSdk = minSdk,
                sourceDir = sourceDir,
                splitSourceDirs = splitSourceDirs,
                hasObb = hasObb,
                appSize = appSize,
                permissions = permissions,
                icon = icon
            )
        } catch (_: Exception) {
            null
        }
    }

    fun saveMetadataJson(
        appInfo: AppInfo,
        destDir: File,
        baseName: String,
        selectedFields: Set<String>
    ): File? {
        return try {
            val json = JSONObject()
            val extractTime = System.currentTimeMillis()

            if ("extracted_date" in selectedFields) {
                val sdf = SimpleDateFormat("MM-dd-yyyy HH:mm:ss", Locale.US)
                json.put("extracted_date", sdf.format(Date(extractTime)))
            }
            if ("package_name" in selectedFields) json.put("package_name", appInfo.packageName)
            if ("version_name" in selectedFields) json.put("version_name", appInfo.versionName ?: JSONObject.NULL)
            if ("version_code" in selectedFields) json.put("version_code", appInfo.versionCode)
            if ("label" in selectedFields) json.put("label", appInfo.label)
            if ("install_time" in selectedFields) json.put("install_time", appInfo.firstInstallTime)
            if ("last_update_time" in selectedFields) json.put("last_update_time", appInfo.lastUpdateTime)
            if ("target_sdk" in selectedFields) json.put("target_sdk", appInfo.targetSdk)
            if ("min_sdk" in selectedFields) json.put("min_sdk", appInfo.minSdk)
            if ("source_dir" in selectedFields) json.put("source_dir", appInfo.sourceDir)
            if ("split_apks" in selectedFields) json.put("split_apks", JSONObject.wrap(appInfo.splitSourceDirs))
            if ("has_obb" in selectedFields) json.put("has_obb", appInfo.hasObb)
            if ("app_size" in selectedFields) json.put("app_size", appInfo.appSize)

            if ("permissions" in selectedFields && appInfo.permissions != null) {
                json.put("permissions", JSONObject.wrap(appInfo.permissions))
            }

            val jsonFile = File(destDir, "${baseName}.info.json")
            FileOutputStream(jsonFile).use { it.write(json.toString(2).toByteArray()) }
            jsonFile
        } catch (_: Exception) {
            null
        }
    }

    fun saveIcon(appInfo: AppInfo, destDir: File, baseName: String): File? {
        val drawable = appInfo.icon ?: return null
        return try {
            val bitmap = if (drawable is BitmapDrawable) {
                drawable.bitmap
            } else {
                val width = drawable.intrinsicWidth.coerceAtLeast(1)
                val height = drawable.intrinsicHeight.coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                bmp
            }
            val iconFile = File(destDir, "${baseName}-icon.png")
            FileOutputStream(iconFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            iconFile
        } catch (_: Exception) {
            null
        }
    }

    fun generateUniqueFilename(
        destDir: File,
        label: String,
        packageName: String,
        extension: String
    ): String {
        val sanitized = label.replace(Regex("""[\\/:*?"<>|]"""), "_").trim()
        if (sanitized.isEmpty()) return "${packageName}_$extension"

        val baseApk = File(destDir, "$sanitized.$extension")
        val baseJson = File(destDir, "$sanitized.info.json")

        if (!baseApk.exists() && !baseJson.exists()) return sanitized

        val existingJson = File(destDir, "$sanitized.info.json")
        if (existingJson.exists()) {
            try {
                val content = existingJson.readText()
                val existingPkg = JSONObject(content).optString("package_name", "")
                if (existingPkg == packageName) return sanitized
            } catch (_: Exception) { }
        }

        return "${sanitized}_$packageName"
    }
}
