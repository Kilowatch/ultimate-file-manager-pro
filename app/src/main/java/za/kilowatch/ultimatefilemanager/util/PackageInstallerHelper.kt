package za.kilowatch.ultimatefilemanager.util

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.remote.InstallReceiver
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

/**
 * Single source of truth for APK and XAPK installation.
 * Handles single APKs, Split APKs (XAPK), and permission checks.
 */
object PackageInstallerHelper {
    private const val TAG = "PackageInstallerHelper"

    /**
     * Installs a single APK file.
     */
    fun installApk(context: Context, file: File) {
        checkInstallPermission(context)
        abandonMySessions(context)

        val packageInstaller = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = packageInstaller.createSession(params)
        val session = packageInstaller.openSession(sessionId)

        try {
            session.openWrite(file.name, 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
            }

            val pkgInfo = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
            val packageName = pkgInfo?.packageName ?: ""
            val appName = pkgInfo?.applicationInfo?.let { appInfo ->
                appInfo.sourceDir = file.absolutePath
                appInfo.publicSourceDir = file.absolutePath
                context.packageManager.getApplicationLabel(appInfo).toString()
            } ?: ""

            val broadcastIntent = Intent(context, InstallReceiver::class.java).apply {
                action = InstallReceiver.ACTION_INSTALL_COMPLETE
                putExtra("jobId", "")
                putExtra("fileName", file.name)
                putExtra("appName", appName)
                putExtra("packageName", packageName)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context, sessionId, broadcastIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            session.commit(pendingIntent.intentSender)
        } catch (e: Exception) {
            session.abandon()
            throw e
        } finally {
            session.close()
        }
    }

    /**
     * Installs an XAPK (Split APK) file by extracting and committing as a single session.
     */
    fun installXapk(
        context: Context,
        archiveFile: File,
        forceDpi: String? = null,
        forceAbi: String? = null
    ) {
        checkInstallPermission(context)
        abandonMySessions(context)

        val jobId = UUID.randomUUID().toString()
        val extractDir = File(context.cacheDir, "xapk_temp/$jobId")
        extractDir.mkdirs()

        val densityRegex = Regex("""split_config\.(ldpi|mdpi|tvdpi|hdpi|xhdpi|xxhdpi|xxxhdpi|anydpi|nodpi)\.apk""", RegexOption.IGNORE_CASE)
        val abiRegex     = Regex("""split_config\.(arm64.v8a|armeabi.v7a|x86_64|x86)\.apk""",              RegexOption.IGNORE_CASE)

        try {
            val apkFiles = mutableListOf<File>()

            ZipFile(archiveFile).use { zip ->
                val apkEntries = zip.entries().asSequence().filter { it.name.endsWith(".apk", ignoreCase = true) }.toList()
                if (apkEntries.isEmpty()) {
                    throw IllegalArgumentException(context.getString(R.string.error_no_apk_in_xapk))
                }

                apkEntries.forEach { entry ->
                    val name = entry.name.substringAfterLast('/')
                    
                    // Determine if this split should be included
                    val isDensitySplit = densityRegex.containsMatchIn(name)
                    val isAbiSplit     = abiRegex.containsMatchIn(name)
                    val include = when {
                        isDensitySplit && forceDpi != null ->
                            name.contains(forceDpi, ignoreCase = true)
                        isAbiSplit && forceAbi != null ->
                            name.contains(forceAbi, ignoreCase = true)
                        else -> true  // base.apk, language splits, anydpi, unrecognised — always include
                    }
                    if (!include) return@forEach

                    val outFile = File(extractDir, name)
                    
                    // Zip Slip guard
                    if (!outFile.canonicalPath.startsWith(extractDir.canonicalPath + File.separator)) {
                        return@forEach
                    }

                    zip.getInputStream(entry).use { input ->
                        outFile.outputStream().use { input.copyTo(it) }
                    }
                    apkFiles.add(outFile)
                }
            }

            if (apkFiles.isEmpty()) {
                throw IllegalArgumentException(context.getString(R.string.error_no_splits_extracted))
            }

            val packageInstaller = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val sessionId = packageInstaller.createSession(params)
            val session = packageInstaller.openSession(sessionId)

            try {
                apkFiles.forEach { apkFile ->
                    session.openWrite(apkFile.name, 0, apkFile.length()).use { out ->
                        apkFile.inputStream().use { it.copyTo(out) }
                    }
                }

                val baseApkFile = apkFiles.firstOrNull { it.name.equals("base.apk", ignoreCase = true) } ?: apkFiles.firstOrNull()
                val pkgInfo = baseApkFile?.let { context.packageManager.getPackageArchiveInfo(it.absolutePath, 0) }
                val packageName = pkgInfo?.packageName ?: ""
                val appName = baseApkFile?.let { file ->
                    pkgInfo?.applicationInfo?.let { appInfo ->
                        appInfo.sourceDir = file.absolutePath
                        appInfo.publicSourceDir = file.absolutePath
                        context.packageManager.getApplicationLabel(appInfo).toString()
                    }
                } ?: ""

                val broadcastIntent = Intent(context, InstallReceiver::class.java).apply {
                    action = InstallReceiver.ACTION_INSTALL_COMPLETE
                    putExtra("jobId", jobId)
                    putExtra("fileName", archiveFile.name)
                    putExtra("appName", appName)
                    putExtra("packageName", packageName)
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    context, sessionId, broadcastIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )
                session.commit(pendingIntent.intentSender)
            } catch (e: Exception) {
                session.abandon()
                throw e
            } finally {
                session.close()
            }

        } catch (e: Exception) {
            extractDir.deleteRecursively()
            throw e
        }
    }

    /**
     * Attempts to open the system settings screen for granting unknown app install permissions.
     * Safely falls back across multiple settings intents (per-app unknown sources,
     * global unknown sources list, application details, security settings, general settings)
     * to prevent [ActivityNotFoundException] on Android TV and customized OEM ROMs lacking
     * the dedicated unknown sources settings activity.
     *
     * @return true if a settings activity was successfully launched, false otherwise.
     */
    fun openInstallPermissionSettings(context: Context): Boolean {
        val packageUri = Uri.parse("package:${context.packageName}")
        val intents = mutableListOf<Intent>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // 1. Per-app unknown sources settings
            intents.add(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, packageUri))
            // 2. Global unknown sources list without package URI
            intents.add(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES))
        }

        // 3. App Details settings (contains "Install unknown apps" or special permissions on most TVs/OEMs)
        intents.add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri))

        // 4. Security settings (where unknown sources toggle resides on TV boxes and older Android)
        intents.add(Intent(Settings.ACTION_SECURITY_SETTINGS))

        // 5. Generic system settings
        intents.add(Intent(Settings.ACTION_SETTINGS))

        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return true
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "Settings activity not found for ${intent.action}: ${e.message}")
            } catch (e: SecurityException) {
                Log.w(TAG, "Security exception launching ${intent.action}: ${e.message}")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to launch settings ${intent.action}: ${e.message}")
            }
        }

        Log.e(TAG, "Unable to launch any settings activity for install permissions")
        return false
    }

    /**
     * Checks if the app has permission to install unknown apps.
     * Throws [SecurityException] and opens settings if not allowed.
     */
    private fun checkInstallPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()) {
            
            openInstallPermissionSettings(context)
            
            throw SecurityException(context.getString(R.string.error_install_unknown_apps_instruction))
        }
    }

    /**
     * Clears any stale or orphaned PackageInstaller sessions for this app.
     * This prevents "Too many active sessions" IllegalStateException.
     */
    fun abandonMySessions(context: Context) = synchronized(this) {
        try {
            val packageInstaller = context.packageManager.packageInstaller
            packageInstaller.mySessions.forEach { sessionInfo ->
                try {
                    Log.d(TAG, "Abandoning stale session: ${sessionInfo.sessionId}")
                    packageInstaller.abandonSession(sessionInfo.sessionId)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to abandon session ${sessionInfo.sessionId}: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to list or abandon sessions: ${e.message}")
        }
    }

    fun isApk(file: File): Boolean = file.extension.lowercase() == "apk"
    fun isXapk(file: File): Boolean {
        val ext = file.extension.lowercase()
        return ext == "xapk" || ext == "apks"
    }
}
