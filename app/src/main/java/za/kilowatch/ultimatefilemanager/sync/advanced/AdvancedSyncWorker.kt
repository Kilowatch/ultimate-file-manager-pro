package za.kilowatch.ultimatefilemanager.sync.advanced

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.network.FtpShareClient
import za.kilowatch.ultimatefilemanager.network.NetworkFile
import za.kilowatch.ultimatefilemanager.network.NetworkShare
import za.kilowatch.ultimatefilemanager.network.NetworkShareRepository
import za.kilowatch.ultimatefilemanager.network.NfsShareClient
import za.kilowatch.ultimatefilemanager.network.OnlineStorageProvider
import za.kilowatch.ultimatefilemanager.network.OnlineStorageRepository
import za.kilowatch.ultimatefilemanager.network.S3ShareClient
import za.kilowatch.ultimatefilemanager.network.ShareType
import za.kilowatch.ultimatefilemanager.network.SmbShareClient
import za.kilowatch.ultimatefilemanager.network.SshShareClient
import za.kilowatch.ultimatefilemanager.network.WebDavShareClient
import za.kilowatch.ultimatefilemanager.util.CopyHelper
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import org.json.JSONArray
import org.json.JSONObject

class AdvancedSyncWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    companion object {
        const val TAG = "AdvancedSyncWorker"
        const val CHANNEL_ID = "advanced_sync_channel"
        const val NOTIFICATION_ID_BASE = 2000
    }

    override suspend fun doWork(): Result {
        val profileId = inputData.getString("PROFILE_ID") ?: return Result.failure()
        val repo = AdvancedSyncProfileRepository.getInstance(applicationContext)
        val profile = repo.getById(profileId) ?: return Result.failure()

        if (!profile.enabled) return Result.success()

        val notificationId = NOTIFICATION_ID_BASE + profile.id.hashCode().rem(1000).let { if (it < 0) -it else it }

        if (profile.notificationsEnabled) {
            setupNotificationChannel()
        }

        Log.d(TAG, "Starting sync for profile '${profile.name}' (id=${profile.id}, dir=${profile.direction}, dest=${profile.networkShareId})")

        try {
            // Resolve share — try network shares first, then online storages
            var share = NetworkShareRepository.getInstance(applicationContext).getById(profile.networkShareId)
            Log.d(TAG, "Share lookup: networkShareRepo returned ${share?.let { "${it.name} (${it.type})" } ?: "null"}")

            if (share == null) {
                // Check if this is an online storage
                val foundOnline = OnlineStorageRepository.getInstance(applicationContext).getById(profile.networkShareId)
                Log.d(TAG, "Online storage lookup: ${foundOnline?.let { "${it.displayName} (${it.provider})" } ?: "null"}")
                if (foundOnline != null) {
                    share = NetworkShare(
                        id = foundOnline.id,
                        name = foundOnline.displayName,
                        type = when (foundOnline.provider) {
                            OnlineStorageProvider.ONEDRIVE -> ShareType.ONEDRIVE
                            OnlineStorageProvider.GOOGLE_DRIVE -> ShareType.GOOGLE_DRIVE
                            OnlineStorageProvider.DROPBOX -> ShareType.DROPBOX
                            OnlineStorageProvider.AWS_S3 -> ShareType.AWS_S3
                            OnlineStorageProvider.IDRIVE_E2 -> ShareType.IDRIVE_E2
                            OnlineStorageProvider.WEBDAV -> ShareType.WEBDAV
                        },
                        host = when {
                            foundOnline.isWebDavProvider -> foundOnline.webDavUrl ?: ""
                            else -> foundOnline.s3Endpoint ?: foundOnline.email
                        },
                        port = 0,
                        username = when {
                            foundOnline.isWebDavProvider -> foundOnline.webDavUsername ?: ""
                            else -> foundOnline.s3AccessKey ?: foundOnline.email
                        },
                        password = when {
                            foundOnline.isWebDavProvider -> foundOnline.webDavPassword ?: ""
                            else -> foundOnline.s3SecretKey ?: ""
                        },
                        domain = foundOnline.s3Bucket ?: "",
                        remotePath = foundOnline.s3Region ?: "/",
                        readOnly = false
                    )
                }
            }

            if (share == null) {
                Log.w(TAG, "Share not found for profile '${profile.name}'")
                if (profile.notificationsEnabled) {
                    showErrorNotification(
                        applicationContext.getString(R.string.network_share_not_found_for_profilename),
                        profile.id.hashCode() + 10
                    )
                }
                return Result.failure()
            }
            Log.d(TAG, "Resolved share: '${share.name}' type=${share.type} host=${share.host}")

            // Test connection — skip test for online storage types (they handle auth differently)
            val connError = when (share.type) {
                ShareType.SMB -> SmbShareClient.testConnection(share)
                ShareType.NFS -> NfsShareClient.testConnection(share)
                ShareType.SFTP, ShareType.SCP -> SshShareClient.testConnection(applicationContext, share)
                ShareType.WEBDAV -> if (WebDavShareClient.testConnection(share)) null else "WebDAV connection failed"
                ShareType.DLNA -> "DLNA does not support sync"
                ShareType.FTP -> FtpShareClient.testConnection(share)
                else -> null // Online storages (OneDrive, Google Drive, Dropbox, S3) skip test
            }

            if (connError != null) {
                if (profile.notificationsEnabled) {
                    showErrorNotification(
                        applicationContext.getString(R.string.connection_lost_sync_paused_for_profilename),
                        profile.id.hashCode() + 10
                    )
                }
                return Result.success()
            }

            // Resolve local folder
            val localDir = File(profile.localUri)
            if (!localDir.exists() || !localDir.isDirectory || !localDir.canRead()) {
                if (profile.notificationsEnabled) {
                    showErrorNotification(
                        applicationContext.getString(R.string.cannot_read_local_folder_for_profilename),
                        profile.id.hashCode() + 10
                    )
                }
                return Result.failure()
            }

            Log.d(TAG, "Connection test passed. Starting ${profile.direction} sync for '${profile.name}'")
            when (profile.direction) {
                "upload" -> doUpload(share, profile, localDir, notificationId)
                "download" -> doDownload(share, profile, localDir, notificationId)
                "twoway" -> doTwoway(share, profile, localDir, notificationId)
                else -> Log.w(TAG, "Unknown direction: ${profile.direction}")
            }

            if (profile.notificationsEnabled) {
                val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(notificationId)
            }

            return Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "Sync failed", e)
            if (profile.notificationsEnabled) {
                val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(notificationId)
            }
            return Result.failure()
        }
    }

    // ── Upload: local → remote ─────────────────────────────────────────────────

    private suspend fun doUpload(
        share: NetworkShare, profile: AdvancedSyncProfile, localDir: File, notificationId: Int
    ) {
        val remoteFiles = listRemoteFiles(share, profile.remotePath)
        Log.d(TAG, "doUpload: ${remoteFiles.size} remote files, remotePath='${profile.remotePath}'")
        val remoteSizes = remoteFiles.associate { it.name to it.size }
        val remoteTimestamps = remoteFiles.associate { it.name to it.lastModified }

        val localFiles = (localDir.list()?.map { File(localDir, it) } ?: emptyList())
            .filter { passesFilters(it.name, profile) }
            .filter { passesSizeAgeFilters(it, profile) }
        Log.d(TAG, "doUpload: ${localFiles.size} files after filter in '${localDir.path}'")
        val filesToUpload = mutableListOf<File>()

        for (localFile in localFiles) {
            val name = localFile.name
            if (localFile.isDirectory) {
                Log.d(TAG, "doUpload: skipping directory '$name'")
                continue
            }
            val remoteSize = resolveRemoteSize(share, remoteSizes[name], name, profile.remotePath)
            val remoteTimestamp = remoteTimestamps[name] ?: 0L

            val needsUpload = remoteSize == null
                || remoteSize != localFile.length()
                || (remoteTimestamp < localFile.lastModified() && localFile.lastModified() > 0L)

            if (needsUpload) {
                filesToUpload.add(localFile)
            }
        }

        if (filesToUpload.isEmpty()) {
            Log.d(TAG, "doUpload: No files to upload (local dir contents: ${localDir.list()?.size ?: 0} files)")
        } else {
            uploadFiles(share, profile, filesToUpload, notificationId)
        }

        // Sync deletions (upload direction): only delete previously-synced files
        if (profile.syncDeletions && profile.syncedFileHashes.isNotBlank()) {
            val previousHashes = try {
                org.json.JSONArray(profile.syncedFileHashes).let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }.toSet()
                }
            } catch (e: Exception) { emptySet() }
            if (previousHashes.isNotEmpty()) {
                val currentLocalNames = localDir.list()?.toSet() ?: emptySet()
                for (remoteFile in remoteFiles) {
                    if (remoteFile.isDirectory) continue
                    val hash = sha256(remoteFile.name)
                    if (hash in previousHashes && remoteFile.name !in currentLocalNames) {
                        try {
                            deleteRemoteFileByType(share, "${profile.remotePath.trimEnd('/')}/${remoteFile.name}")
                            Log.d(TAG, "Upload deletion: removed '${remoteFile.name}' from remote")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to delete remote '${remoteFile.name}'", e)
                        }
                    }
                }
            }
        }

        // Persist hash tracking: merge new upload hashes with existing tracked hashes
        val oldHashes = try {
            org.json.JSONArray(profile.syncedFileHashes).let { arr ->
                (0 until arr.length()).map { arr.getString(it) }.toSet()
            }
        } catch (e: Exception) { emptySet() }
        val newHashes = oldHashes + filesToUpload.map { sha256(it.name) }
        profile.syncedFileHashes = org.json.JSONArray(newHashes.toList()).toString()
        val repo = AdvancedSyncProfileRepository.getInstance(applicationContext)
        repo.save(profile)
    }

    // ── Download: remote → local ───────────────────────────────────────────────

    private suspend fun doDownload(
        share: NetworkShare, profile: AdvancedSyncProfile, localDir: File, notificationId: Int
    ) {
        val allRemoteFiles = if (profile.downloadSubfolders) {
            listRemoteFilesRecursive(share, profile.remotePath)
        } else {
            listRemoteFiles(share, profile.remotePath)
        }
        val remoteFiles = allRemoteFiles.filter { passesFilters(it.name, profile) }
            .filter { passesSizeAgeFilters(it, profile) }
        val localFileNames = localDir.list()?.toSet() ?: emptySet()

        val filesToDownload = mutableListOf<NetworkFile>()

        for (remoteFile in remoteFiles) {
            if (remoteFile.isDirectory) continue
            val localFile = File(localDir, remoteFile.name)
            val localExists = localFile.exists()

            val needsDownload = !localExists
                || localFile.length() != remoteFile.size
                || (remoteFile.lastModified > localFile.lastModified() && remoteFile.lastModified > 0L)

            if (needsDownload) {
                filesToDownload.add(remoteFile)
            }
        }

        if (filesToDownload.isEmpty()) {
            Log.d(TAG, "doDownload: No files to download")
        } else {
            downloadFiles(share, profile, localDir, filesToDownload, notificationId)
        }

        // Sync deletions (download direction): only delete files that were synced
        // in a PRIOR sync (identified by SHA-256 hash).
        if (profile.syncDeletions && profile.syncedFileHashes.isNotBlank()) {
            val previousHashes = try {
                org.json.JSONArray(profile.syncedFileHashes).let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }.toSet()
                }
            } catch (e: Exception) { emptySet() }
            if (previousHashes.isNotEmpty()) {
                val remoteNames = remoteFiles.map { it.name }.toSet()
                for (localFile in localDir.list()?.map { File(localDir, it) } ?: emptyList()) {
                    if (localFile.isDirectory) continue
                    val hash = sha256(localFile.name)
                    if (hash in previousHashes && localFile.name !in remoteNames && localFile.delete()) {
                        Log.d(TAG, "Download deletion: removed '${localFile.name}' from local")
                    }
                }
            }
        }

        // Persist hash tracking: merge new download hashes with existing tracked hashes
        val oldHashes = try {
            org.json.JSONArray(profile.syncedFileHashes).let { arr ->
                (0 until arr.length()).map { arr.getString(it) }.toSet()
            }
        } catch (e: Exception) { emptySet() }
        val newHashes = oldHashes + filesToDownload.map { sha256(it.name) }
        profile.syncedFileHashes = org.json.JSONArray(newHashes.toList()).toString()
        val repo = AdvancedSyncProfileRepository.getInstance(applicationContext)
        repo.save(profile)
    }

    // ── Two-way: upload + download with conflict resolution ────────────────────

    private suspend fun doTwoway(
        share: NetworkShare, profile: AdvancedSyncProfile, localDir: File, notificationId: Int
    ) {
        val remoteFiles = listRemoteFiles(share, profile.remotePath)
        val remoteSizes = remoteFiles.associate { it.name to it.size }
        val remoteTimestamps = remoteFiles.associate { it.name to it.lastModified }

        val localFiles = (localDir.list()?.map { File(localDir, it) } ?: emptyList())
            .filter { passesFilters(it.name, profile) }
            .filter { passesSizeAgeFilters(it, profile) }
        val localMap = localFiles.associateBy { it.name }

        val toUpload = mutableListOf<File>()
        val toDownload = mutableListOf<NetworkFile>()
        val conflicts = mutableListOf<Pair<File, NetworkFile>>()
        val conflictLog = JSONArray()

        // Two-way: upload local-only files, download remote-only files.
        // "Sync deletions" is not applied here — two-way cannot distinguish
        // between "file was deleted from source" and "file was added to destination"
        // without change tracking. Deletion propagation is handled by one-way modes.

        for (localFile in localFiles) {
            if (localFile.isDirectory) continue
            val name = localFile.name
            val remoteSize = resolveRemoteSize(share, remoteSizes[name], name, profile.remotePath)
            val remoteTimestamp = remoteTimestamps[name] ?: 0L

            if (remoteSize == null) {
                toUpload.add(localFile)
            } else if (remoteSize != localFile.length()) {
                val localMod = localFile.lastModified()
                if (localMod > remoteTimestamp && remoteTimestamp > 0L) {
                    toUpload.add(localFile)
                } else if (remoteTimestamp > localMod && localMod > 0L) {
                    remoteFiles.find { it.name == name }?.let { toDownload.add(it) }
                } else {
                    remoteFiles.find { it.name == name }?.let { conflicts.add(Pair(localFile, it)) }
                        ?: toUpload.add(localFile)
                }
            } else if (remoteTimestamp > localFile.lastModified() && remoteTimestamp > 0L) {
                remoteFiles.find { it.name == name }?.let { toDownload.add(it) }
            }
        }

        // Files only on remote → download to local
        for (remoteFile in remoteFiles) {
            if (remoteFile.isDirectory) continue
            if (remoteFile.name !in localMap.keys) toDownload.add(remoteFile)
        }

        // Resolve conflicts
        for ((localFile, remoteFile) in conflicts) {
            when (profile.conflictStrategy) {
                "newest" -> {
                    if (localFile.lastModified() >= remoteFile.lastModified) {
                        toUpload.add(localFile)
                        logConflict(conflictLog, localFile.name, "upload (newer locally)")
                    } else {
                        toDownload.add(remoteFile)
                        logConflict(conflictLog, remoteFile.name, "download (newer remotely)")
                    }
                }
                "keep_local" -> { toUpload.add(localFile); logConflict(conflictLog, localFile.name, "keep_local") }
                "keep_remote" -> { toDownload.add(remoteFile); logConflict(conflictLog, remoteFile.name, "keep_remote") }
                else -> logConflict(conflictLog, localFile.name, "skipped")
            }
        }

        // Execute transfers
        if (toUpload.isNotEmpty()) uploadFiles(share, profile, toUpload, notificationId)
        if (toDownload.isNotEmpty()) downloadFiles(share, profile, localDir, toDownload, notificationId + 1)
        if (conflictLog.length() > 0) profile.conflictLogJson = conflictLog.toString(2)

        profile.lastSyncTime = System.currentTimeMillis()
        profile.lastSyncFileCount = toUpload.size + toDownload.size
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun logConflict(log: JSONArray, fileName: String, resolution: String) {
        log.put(JSONObject().apply {
            put("file", fileName)
            put("resolution", resolution)
            put("timestamp", System.currentTimeMillis())
        })
    }

    private suspend fun listRemoteFiles(share: NetworkShare, remotePath: String): List<NetworkFile> {
        var files: List<NetworkFile>? = null
        try {
            files = listFilesByType(share, remotePath)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to list remote files, attempting to create directory", e)
        }

        if (files == null) {
            try {
                mkdirByType(share, remotePath)
                files = listFilesByType(share, remotePath)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create or access remote path", e)
                throw e
            }
        }

        return files ?: emptyList()
    }

    private suspend fun listFilesByType(share: NetworkShare, remotePath: String): List<NetworkFile> {
        // Try direct client first, fall back to reflection-based online storage clients
        val directResult = try {
            when (share.type) {
                ShareType.SMB -> SmbShareClient.listFiles(share, remotePath)
                ShareType.NFS -> NfsShareClient.listFiles(share, remotePath)
                ShareType.SFTP, ShareType.SCP -> SshShareClient.listFiles(share, remotePath)
                ShareType.WEBDAV -> WebDavShareClient.listFiles(share, remotePath)
                ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.listFiles(share, remotePath)
                ShareType.DLNA -> throw UnsupportedOperationException("DLNA does not support sync")
                else -> null // Try reflection
            }
        } catch (e: Exception) {
            Log.w(TAG, "Direct listFiles failed for ${share.type}, trying reflection", e)
            null
        }
        if (directResult != null) return directResult
        // Fallback: use reflection for online storage clients
        val refResult = OnlineSyncHelper.tryListFiles(share, remotePath)
        if (refResult != null) return refResult
        // For non-FTP protocols, don't fall back to FTP
        return when (share.type) {
            ShareType.SFTP, ShareType.SCP, ShareType.WEBDAV, ShareType.AWS_S3, ShareType.IDRIVE_E2 ->
                throw java.io.IOException("Failed to list remote files (${share.type})")
            else -> FtpShareClient.listFiles(share, remotePath)
        }
    }

    private suspend fun mkdirByType(share: NetworkShare, remotePath: String) {
        val directSuccess = try {
            when (share.type) {
                ShareType.SMB -> { SmbShareClient.mkdir(share, remotePath); true }
                ShareType.NFS -> { NfsShareClient.mkdir(share, remotePath); true }
                ShareType.SFTP, ShareType.SCP -> { SshShareClient.mkdir(share, remotePath); true }
                ShareType.WEBDAV -> { WebDavShareClient.mkdir(share, remotePath); true }
                ShareType.AWS_S3, ShareType.IDRIVE_E2 -> { S3ShareClient.mkdir(share, remotePath); true }
                ShareType.DLNA -> throw UnsupportedOperationException("DLNA does not support sync")
                else -> false // Try reflection
            }
        } catch (e: Exception) {
            Log.w(TAG, "Direct mkdir failed for ${share.type}, trying reflection", e)
            false
        }
        if (!directSuccess) {
            OnlineSyncHelper.tryMkdir(share, remotePath)
            // mkdir failures for SFTP/SCP are not critical — silently continue
        }
    }

    private suspend fun resolveRemoteSize(
        share: NetworkShare, cachedSize: Long?, fileName: String, remotePath: String
    ): Long? {
        var size = cachedSize
        val fullPath = "${remotePath.trimEnd('/')}/$fileName"
        if (size == SmbShareClient.SIZE_UNKNOWN_SENTINEL && share.type == ShareType.SMB) {
            try {
                val candidate = SmbShareClient.getFileSize(share, fullPath)
                if (candidate != null) size = candidate
            } catch (e: Exception) {
                Log.w(TAG, "Lazy size query failed for $fileName", e)
            }
        }
        // For S3, WebDAV sizes are returned correctly by listFiles, no special handling needed.
        // SFTP/SCP sizes are also returned correctly by listFiles.
        return size
    }

    private suspend fun uploadFiles(
        share: NetworkShare, profile: AdvancedSyncProfile, files: List<File>, notificationId: Int
    ) {
        var syncedCount = 0
        for ((index, file) in files.withIndex()) {
            val name = file.name
            if (profile.notificationsEnabled) {
                val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                    .setContentTitle(applicationContext.getString(R.string.advanced_sync_title))
                    .setContentText(
                        applicationContext.getString(
                            R.string.syncing_profilename_index_1filestouploadsize,
                            profile.name, index + 1, files.size
                        )
                    )
                    .setSmallIcon(R.drawable.ic_sync_advanced)
                    .setOngoing(true)
                    .build()
                val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(notificationId, notification)
            }

            try {
                val remoteFilePath = "${profile.remotePath.trimEnd('/')}/$name"
                val inStream = FileInputStream(file)
                val outStream = openOutputStreamForType(share, remoteFilePath)
                inStream.use { input ->
                    outStream.use { output ->
                        CopyHelper.copy(input, output, file.length())
                    }
                }
                syncedCount++
            } catch (e: Exception) {
                Log.e(TAG, "Failed to upload file $name", e)
            }
        }

        Log.d(TAG, "Upload complete: $syncedCount/${files.size} files synced for '${profile.name}'")
        // Move files (cut): delete source files after successful upload
        if (profile.moveFiles && syncedCount > 0) {
            for (file in files) {
                if (file.delete()) {
                    Log.d(TAG, "Moved (deleted source): ${file.name}")
                }
            }
        }
        profile.lastSyncTime = System.currentTimeMillis()
        profile.lastSyncFileCount = syncedCount
    }

    private suspend fun openOutputStreamForType(share: NetworkShare, remotePath: String): OutputStream {
        val directResult = try {
            when (share.type) {
                ShareType.SMB -> SmbShareClient.openOutputStream(share, remotePath)
                ShareType.NFS -> NfsShareClient.openOutputStream(share, remotePath)
                ShareType.SFTP, ShareType.SCP -> SshShareClient.openOutputStream(share, remotePath)
                ShareType.WEBDAV -> WebDavShareClient.openOutputStream(share, remotePath)
                ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.openOutputStream(share, remotePath)
                ShareType.DLNA -> throw UnsupportedOperationException("DLNA does not support sync")
                else -> null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Direct openOutputStream failed for ${share.type}", e)
            null
        }
        if (directResult != null) return directResult
        val refResult = OnlineSyncHelper.tryOpenOutputStream(share, remotePath)
        if (refResult != null) return refResult
        return when (share.type) {
            ShareType.SFTP, ShareType.SCP, ShareType.WEBDAV, ShareType.AWS_S3, ShareType.IDRIVE_E2 ->
                throw java.io.IOException("Failed to open remote file for writing (${share.type})")
            else -> FtpShareClient.openOutputStream(share, remotePath)
        }
    }

    private suspend fun downloadFiles(
        share: NetworkShare, profile: AdvancedSyncProfile, localDir: File,
        files: List<NetworkFile>, notificationId: Int
    ) {
        var syncedCount = 0
        for ((index, remoteFile) in files.withIndex()) {
            val name = remoteFile.name
            if (profile.notificationsEnabled) {
                val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                    .setContentTitle(applicationContext.getString(R.string.advanced_sync_title))
                    .setContentText(
                        applicationContext.getString(
                            R.string.syncing_profilename_index_1filestouploadsize,
                            profile.name, index + 1, files.size
                        )
                    )
                    .setSmallIcon(R.drawable.ic_sync_advanced)
                    .setOngoing(true)
                    .build()
                val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(notificationId, notification)
            }

            try {
                // Determine the remote file path and local destination
                val remoteFilePath = if (remoteFile.path.isNotEmpty() && remoteFile.path != remoteFile.name)
                    remoteFile.path else "${profile.remotePath.trimEnd('/')}/$name"
                // Preserve subdirectory structure when downloadSubfolders is enabled
                val localFile = if (profile.downloadSubfolders && remoteFile.path.length > name.length) {
                    // Extract relative subdirectory from the path
                    val relativePath = remoteFile.path.removePrefix(profile.remotePath.trimEnd('/')).trimStart('/')
                    val targetFile = File(localDir, relativePath)
                    targetFile.parentFile?.mkdirs()
                    targetFile
                } else {
                    File(localDir, name)
                }
                val inStream = openInputStreamForType(share, remoteFilePath)
                inStream.use { input ->
                    localFile.outputStream().use { output ->
                        CopyHelper.copy(input, output, remoteFile.size)
                    }
                }
                // Preserve remote modification timestamp
                if (remoteFile.lastModified > 0L) {
                    localFile.setLastModified(remoteFile.lastModified)
                }
                syncedCount++
            } catch (e: Exception) {
                Log.e(TAG, "Failed to download file $name", e)
            }
        }

        // Move files (cut): delete remote source files after successful download
        if (profile.moveFiles && syncedCount > 0) {
            for (remoteFile in files) {
                val rfPath = if (remoteFile.path.isNotEmpty() && remoteFile.path != remoteFile.name)
                    remoteFile.path else "${profile.remotePath.trimEnd('/')}/${remoteFile.name}"
                try {
                    deleteRemoteFileByType(share, rfPath)
                    Log.d(TAG, "Moved (deleted remote): ${remoteFile.name}")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to delete remote '${remoteFile.name}' after download", e)
                }
            }
        }

        profile.lastSyncTime = System.currentTimeMillis()
        profile.lastSyncFileCount = syncedCount
    }

    private suspend fun deleteRemoteFileByType(share: NetworkShare, remotePath: String) {
        val directSuccess = try {
            when (share.type) {
                ShareType.SMB -> { SmbShareClient.deleteFile(share, remotePath); true }
                ShareType.NFS -> { NfsShareClient.deleteFile(share, remotePath); true }
                ShareType.SFTP, ShareType.SCP -> { SshShareClient.delete(share, remotePath, false); true }
                ShareType.WEBDAV -> { WebDavShareClient.deleteFile(share, remotePath); true }
                ShareType.AWS_S3, ShareType.IDRIVE_E2 -> { S3ShareClient.deleteFile(share, remotePath); true }
                ShareType.DLNA -> throw UnsupportedOperationException("DLNA does not support sync")
                else -> false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Direct deleteFile failed for ${share.type}, trying reflection", e)
            false
        }
        if (!directSuccess) {
            OnlineSyncHelper.tryDeleteFile(share, remotePath)
            // Don't fall through to FTP — delete semantics vary by protocol
        }
    }

    private suspend fun openInputStreamForType(share: NetworkShare, remotePath: String): InputStream {
        val directResult = try {
            when (share.type) {
                ShareType.SMB -> SmbShareClient.openInputStream(share, remotePath)
                ShareType.NFS -> NfsShareClient.openInputStream(share, remotePath)
                ShareType.SFTP, ShareType.SCP -> SshShareClient.openInputStream(share, remotePath)
                ShareType.WEBDAV -> WebDavShareClient.openInputStream(share, remotePath).first
                ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.openInputStream(share, remotePath).first
                ShareType.DLNA -> throw UnsupportedOperationException("DLNA does not support sync")
                else -> null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Direct openInputStream failed for ${share.type}", e)
            null
        }
        if (directResult != null) return directResult
        // Only fall back to reflection for online storages, not for SFTP/SCP/etc.
        val reflectionResult = OnlineSyncHelper.tryOpenInputStream(share, remotePath)
        if (reflectionResult != null) return reflectionResult
        // Final fallback for traditional network protocols
        return when (share.type) {
            ShareType.SFTP, ShareType.SCP, ShareType.WEBDAV, ShareType.AWS_S3, ShareType.IDRIVE_E2 ->
                throw java.io.IOException("Failed to open remote file (${share.type})")
            else -> FtpShareClient.openInputStream(share, remotePath)
        }
    }

    // ── Notifications ──────────────────────────────────────────────────────────

    private fun setupNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Advanced Sync"
            val descriptionText = applicationContext.getString(R.string.background_file_synchronization)
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val repo = AdvancedSyncProfileRepository.getInstance(applicationContext)
        val profileId = inputData.getString("PROFILE_ID") ?: ""
        val profile = repo.getById(profileId)
        val title = profile?.name ?: "Unknown"
        val notificationId = NOTIFICATION_ID_BASE + title.hashCode().rem(1000).let { if (it < 0) -it else it }
        return createForegroundInfo(
            applicationContext.getString(R.string.syncing_title), notificationId
        )
    }

    private fun createForegroundInfo(content: String, notificationId: Int): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(applicationContext.getString(R.string.advanced_sync_title))
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_sync_advanced)
            .setOngoing(true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    private fun showErrorNotification(message: String, notificationId: Int) {
        setupNotificationChannel()
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(applicationContext.getString(R.string.advanced_sync_title))
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_sync_advanced)
            .setAutoCancel(true)
            .build()

        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(notificationId, notification)
    }

    /** Recursively list all files in subfolders for the download direction. */
    private suspend fun listRemoteFilesRecursive(share: NetworkShare, remotePath: String): List<NetworkFile> {
        val all = mutableListOf<NetworkFile>()
        val queue = ArrayDeque<String>()
        queue.add(remotePath)
        while (queue.isNotEmpty()) {
            val dir = queue.removeFirst()
            val files = try { listRemoteFiles(share, dir) } catch (e: Exception) { emptyList() }
            for (f in files) {
                if (f.isDirectory) {
                    val subPath = if (dir.isEmpty()) f.name else "$dir/${f.name}"
                    queue.add(subPath)
                } else {
                    all.add(f.copy(path = if (dir.isEmpty()) f.name else "$dir/${f.name}"))
                }
            }
        }
        return all
    }

    /** Check if a file passes the extension and name filters for the given profile. */
    private fun passesFilters(fileName: String, profile: AdvancedSyncProfile): Boolean {
        val lower = fileName.lowercase()
        // Extension filter
        if (profile.extensionMode != "all" && profile.extensionFilters.isNotBlank()) {
            val exts = profile.extensionFilters.split(",").map { it.trim().lowercase().removePrefix(".") }.filter { it.isNotEmpty() }
            val fileExt = lower.substringAfterLast('.', "")
            val matchesExt = fileExt.isNotEmpty() && exts.any { fileExt == it || fileExt.contains(it) }
            if (profile.extensionMode == "only" && !matchesExt) return false
            if (profile.extensionMode == "skip" && matchesExt) return false
        }
        // Name exclude pattern filter
        if (profile.excludePatterns.isNotBlank()) {
            val patterns = profile.excludePatterns.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            if (patterns.any { lower.contains(it) }) return false
        }
        return true
    }

    /** Check if a file passes the size and age filters for the given profile. File must be [file] for size checks. */
    private fun passesSizeAgeFilters(file: File, profile: AdvancedSyncProfile): Boolean {
        if (profile.minSizeBytes > 0L && file.length() < profile.minSizeBytes) return false
        if (profile.maxSizeBytes > 0L && file.length() > profile.maxSizeBytes) return false
        if (profile.minAgeMinutes > 0L) {
            val minAgeMs = profile.minAgeMinutes * 60 * 1000L
            val age = System.currentTimeMillis() - file.lastModified()
            if (age < minAgeMs) return false // file is newer than min age
        }
        if (profile.maxAgeMinutes > 0L) {
            val maxAgeMs = profile.maxAgeMinutes * 60 * 1000L
            val age = System.currentTimeMillis() - file.lastModified()
            if (age > maxAgeMs) return false // file is older than max age
        }
        return true
    }

    /** Check size/age filters for a remote file using its size and lastModified. */
    private fun passesSizeAgeFilters(remoteFile: NetworkFile, profile: AdvancedSyncProfile): Boolean {
        if (profile.minSizeBytes > 0L && remoteFile.size < profile.minSizeBytes) return false
        if (profile.maxSizeBytes > 0L && remoteFile.size > profile.maxSizeBytes) return false
        if (profile.minAgeMinutes > 0L && remoteFile.lastModified > 0L) {
            val age = System.currentTimeMillis() - remoteFile.lastModified
            if (age < profile.minAgeMinutes * 60 * 1000L) return false
        }
        if (profile.maxAgeMinutes > 0L && remoteFile.lastModified > 0L) {
            val age = System.currentTimeMillis() - remoteFile.lastModified
            if (age > profile.maxAgeMinutes * 60 * 1000L) return false
        }
        return true
    }

    /** SHA-256 hash of a string, for tracking synced files without storing plain-text names. */
    private fun sha256(input: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
