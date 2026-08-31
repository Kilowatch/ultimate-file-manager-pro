package za.kilowatch.ultimatefilemanager.network

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.util.UUID
import java.util.zip.ZipFile

object AdbPushManager {
    private const val TAG = "AdbPushManager"

    /**
     * Pushes a local file to the remote device's specified directory.
     * Uses exec:cat > 'remoteDir/filename'
     */
    suspend fun pushFile(
        localFile: File,
        remoteDir: String = "/storage/emulated/0/Download",
        onProgress: (bytesSent: Long, totalBytes: Long) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val adbManager = AdbManager.getInstance()
        if (!adbManager.isConnected()) {
            Log.e(TAG, "ADB is not connected")
            return@withContext false
        }

        val escapedName = localFile.name.replace("'", "'\\''")
        val remotePath = "$remoteDir/$escapedName"

        // Open exec channel to write the file on the remote device
        val cmd = "mkdir -p '$remoteDir' && cat > '$remotePath'"
        Log.d(TAG, "Executing: $cmd")
        
        val stream = adbManager.openExec(cmd) ?: return@withContext false
        var success = false

        try {
            val outputStream = stream.openOutputStream()
            val ctx = za.kilowatch.ultimatefilemanager.UfmApplication.instance
            val isSaf = za.kilowatch.ultimatefilemanager.storage.SafTreeManager.isSafPath(localFile.absolutePath) ||
                        za.kilowatch.ultimatefilemanager.storage.SafTreeManager.hasTreePermissionForPath(ctx, localFile.absolutePath)
            val inputStream = if (isSaf) {
                za.kilowatch.ultimatefilemanager.storage.SafTreeManager.openInputStream(ctx, localFile.absolutePath)
            } else {
                FileInputStream(localFile)
            } ?: run {
                Log.e(TAG, "Cannot open input stream for ${localFile.absolutePath}")
                return@withContext false
            }
            val rawSize = if (isSaf) za.kilowatch.ultimatefilemanager.storage.SafTreeManager.getFileSize(ctx, localFile.absolutePath) else localFile.length()
            val totalBytes = if (rawSize >= 0L) rawSize else localFile.length()
            val buffer = ByteArray(64 * 1024) // 64KB buffer for fast transfer
            var bytesSent = 0L

            inputStream.use { input ->
                outputStream.use { output ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        bytesSent += read
                        onProgress(bytesSent, totalBytes)
                    }
                    output.flush()
                }
            }
            success = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to push file via ADB: ${e.message}", e)
        } finally {
            try {
                stream.close()
            } catch (_: Exception) {}
        }

        success
    }

    /**
     * Runs pm install -r on the remote TV/device for the pushed APK.
     * Cleans up the temporary APK file after installation.
     */
    suspend fun installApk(
        remoteFileName: String,
        remoteDir: String = "/storage/emulated/0/Download"
    ): Boolean = withContext(Dispatchers.IO) {
        val adbManager = AdbManager.getInstance()
        if (!adbManager.isConnected()) {
            Log.e(TAG, "ADB is not connected")
            return@withContext false
        }

        val escapedName = remoteFileName.replace("'", "'\\''")
        val remotePath = "$remoteDir/$escapedName"

        // Execute pm install
        val installCmd = "pm install -r '$remotePath'"
        Log.d(TAG, "Executing install: $installCmd")
        val stream = adbManager.openExec(installCmd) ?: return@withContext false
        
        var output = ""
        try {
            stream.openInputStream().use { input ->
                output = String(input.readBytes(), Charsets.UTF_8)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading pm install output: ${e.message}", e)
        } finally {
            try { stream.close() } catch (_: Exception) {}
        }

        Log.d(TAG, "pm install output: $output")

        // Clean up target file
        val cleanCmd = "rm '$remotePath'"
        adbManager.sendShellCommandSync(cleanCmd)

        // Typically, success output contains "Success"
        output.contains("Success", ignoreCase = true)
    }

    /**
     * Extracts splits from the XAPK/APKS file, pushes them to a remote temp directory,
     * and runs a native pm install-create/write/commit session via ADB to install them.
     */
    suspend fun installXapk(
        context: Context,
        localXapkFile: File,
        remoteTempDir: String = "/data/local/tmp/xapk_temp",
        onProgress: (bytesSent: Long, totalBytes: Long) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val adbManager = AdbManager.getInstance()
        if (!adbManager.isConnected()) {
            Log.e(TAG, "ADB is not connected")
            return@withContext false
        }

        val jobId = UUID.randomUUID().toString()
        val localExtractDir = File(context.cacheDir, "xapk_temp_adb/$jobId")
        localExtractDir.mkdirs()

        try {
            val apkFiles = mutableListOf<File>()
            val isSaf = za.kilowatch.ultimatefilemanager.storage.SafTreeManager.isSafPath(localXapkFile.absolutePath) ||
                        za.kilowatch.ultimatefilemanager.storage.SafTreeManager.hasTreePermissionForPath(context, localXapkFile.absolutePath)

            if (isSaf) {
                val inStream = za.kilowatch.ultimatefilemanager.storage.SafTreeManager.openInputStream(context, localXapkFile.absolutePath)
                if (inStream == null) {
                    Log.e(TAG, "Cannot open stream for SAF XAPK: ${localXapkFile.absolutePath}")
                    return@withContext false
                }
                java.util.zip.ZipInputStream(inStream).use { zipIn ->
                    var entry = zipIn.nextEntry
                    while (entry != null) {
                        if (entry.name.endsWith(".apk", ignoreCase = true)) {
                            val name = entry.name.substringAfterLast('/')
                            val outFile = File(localExtractDir, name)
                            if (outFile.canonicalPath.startsWith(localExtractDir.canonicalPath + File.separator)) {
                                outFile.outputStream().use { zipIn.copyTo(it) }
                                apkFiles.add(outFile)
                            }
                        }
                        zipIn.closeEntry()
                        entry = zipIn.nextEntry
                    }
                }
            } else {
                ZipFile(localXapkFile).use { zip ->
                    val apkEntries = zip.entries().asSequence()
                        .filter { it.name.endsWith(".apk", ignoreCase = true) }
                        .toList()
                    if (apkEntries.isEmpty()) {
                        Log.e(TAG, "No APKs found inside XAPK archive")
                        return@withContext false
                    }

                    apkEntries.forEach { entry ->
                        val name = entry.name.substringAfterLast('/')
                        val outFile = File(localExtractDir, name)
                        
                        // Zip Slip guard
                        if (!outFile.canonicalPath.startsWith(localExtractDir.canonicalPath + File.separator)) {
                            return@forEach
                        }

                        zip.getInputStream(entry).use { input ->
                            outFile.outputStream().use { input.copyTo(it) }
                        }
                        apkFiles.add(outFile)
                    }
                }
            }

            if (apkFiles.isEmpty()) {
                Log.e(TAG, "No splits were successfully extracted")
                return@withContext false
            }

            val totalBytes = apkFiles.sumOf { it.length() }
            var bytesSent = 0L
            val remoteJobDir = "$remoteTempDir/$jobId"

            // Push each split to the remote device
            for (apkFile in apkFiles) {
                val success = pushFile(apkFile, remoteJobDir) { sent, _ ->
                    onProgress(bytesSent + sent, totalBytes)
                }
                if (!success) {
                    Log.e(TAG, "Failed to push split: ${apkFile.name}")
                    cleanRemoteDir(adbManager, remoteJobDir)
                    return@withContext false
                }
                bytesSent += apkFile.length()
            }

            // Create PM Install Session
            val createCmd = "pm install-create -r"
            Log.d(TAG, "Executing: $createCmd")
            val createOutput = runCommandSync(adbManager, createCmd)
            Log.d(TAG, "pm install-create output: $createOutput")

            val sessionId = parseSessionId(createOutput)
            if (sessionId == null) {
                Log.e(TAG, "Failed to parse session ID from: $createOutput")
                cleanRemoteDir(adbManager, remoteJobDir)
                return@withContext false
            }

            // Write splits to session
            var writeSuccess = true
            for ((index, apkFile) in apkFiles.withIndex()) {
                val escapedName = apkFile.name.replace("'", "'\\''")
                val remoteApkPath = "$remoteJobDir/$escapedName"
                val writeCmd = "pm install-write -S ${apkFile.length()} $sessionId split_$index '$remoteApkPath'"
                Log.d(TAG, "Executing: $writeCmd")
                val writeOutput = runCommandSync(adbManager, writeCmd)
                Log.d(TAG, "pm install-write output: $writeOutput")
                if (!writeOutput.contains("Success", ignoreCase = true)) {
                    Log.e(TAG, "Failed pm install-write split $index: $writeOutput")
                    writeSuccess = false
                    break
                }
            }

            val installSuccess = if (writeSuccess) {
                val commitCmd = "pm install-commit $sessionId"
                Log.d(TAG, "Executing: $commitCmd")
                val commitOutput = runCommandSync(adbManager, commitCmd)
                Log.d(TAG, "pm install-commit output: $commitOutput")
                commitOutput.contains("Success", ignoreCase = true)
            } else {
                val abandonCmd = "pm install-abandon $sessionId"
                runCommandSync(adbManager, abandonCmd)
                false
            }

            cleanRemoteDir(adbManager, remoteJobDir)
            installSuccess
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sideload XAPK via ADB: ${e.message}", e)
            false
        } finally {
            localExtractDir.deleteRecursively()
        }
    }

    private suspend fun runCommandSync(adbManager: AdbManager, cmd: String): String {
        val stream = adbManager.openExec(cmd) ?: return ""
        var output = ""
        try {
            stream.openInputStream().use { input ->
                output = String(input.readBytes(), Charsets.UTF_8)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing command '$cmd': ${e.message}", e)
        } finally {
            try { stream.close() } catch (_: Exception) {}
        }
        return output
    }

    private suspend fun cleanRemoteDir(adbManager: AdbManager, remoteDir: String) {
        val cleanCmd = "rm -rf '$remoteDir'"
        adbManager.sendShellCommandSync(cleanCmd)
    }

    private fun parseSessionId(output: String): String? {
        val matchResult = Regex("""\[(?:Session:?\s*)?(\d+)\]""", RegexOption.IGNORE_CASE).find(output)
        return matchResult?.groupValues?.get(1)
    }
}
