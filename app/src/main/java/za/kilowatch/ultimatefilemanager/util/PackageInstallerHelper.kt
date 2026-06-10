package za.kilowatch.ultimatefilemanager.util

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
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

            val broadcastIntent = Intent(context, InstallReceiver::class.java).apply {
                action = InstallReceiver.ACTION_INSTALL_COMPLETE
                putExtra("jobId", "")
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

                val broadcastIntent = Intent(context, InstallReceiver::class.java).apply {
                    action = InstallReceiver.ACTION_INSTALL_COMPLETE
                    putExtra("jobId", jobId)
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
     * Checks if the app has permission to install unknown apps.
     * Throws [SecurityException] and opens settings if not allowed.
     */
    private fun checkInstallPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()) {
            
            val intent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            
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
