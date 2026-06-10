package za.kilowatch.ultimatefilemanager.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileInputStream
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.network.FtpShareClient
import za.kilowatch.ultimatefilemanager.network.NetworkFile
import za.kilowatch.ultimatefilemanager.network.NetworkShare
import za.kilowatch.ultimatefilemanager.network.NetworkShareRepository
import za.kilowatch.ultimatefilemanager.network.ShareType
import za.kilowatch.ultimatefilemanager.network.NfsShareClient
import za.kilowatch.ultimatefilemanager.network.SmbShareClient
import java.io.InputStream
import java.io.OutputStream

class SyncWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    companion object {
        const val TAG = "SyncWorker"
        const val CHANNEL_ID = "sync_channel"
        const val NOTIFICATION_ID_BASE = 1000
    }

    override suspend fun doWork(): Result {
        val profileId = inputData.getString("PROFILE_ID") ?: return Result.failure()
        val repo = SyncProfileRepository.getInstance(applicationContext)
        val profile = repo.getById(profileId) ?: return Result.failure()

        if (!profile.enabled) return Result.success()

        val notificationId = NOTIFICATION_ID_BASE + profile.id.hashCode().rem(1000).let { if (it < 0) -it else it }

        if (profile.notificationsEnabled) {
            setupNotificationChannel()
        }

        try {
            val netRepo = NetworkShareRepository.getInstance(applicationContext)
            val share = netRepo.getById(profile.networkShareId)
            
            if (share == null) {
                if (profile.notificationsEnabled) {
                    showErrorNotification(applicationContext.getString(R.string.network_share_not_found_for_profilename), profile.id.hashCode() + 10)
                }
                return Result.failure()
            }

            // Test connection — null = success, non-null = error message
            val connError = when (share.type) {
                ShareType.SMB -> SmbShareClient.testConnection(share)
                ShareType.NFS -> NfsShareClient.testConnection(share)
                ShareType.DLNA -> "DLNA does not support sync"
                else -> FtpShareClient.testConnection(share)
            }

            if (connError != null) {
                if (profile.notificationsEnabled) {
                    showErrorNotification(applicationContext.getString(R.string.connection_lost_sync_paused_for_profilename), profile.id.hashCode() + 10)
                }
                // Return success so it tries again later via periodic trigger.
                return Result.success()
            }

            // Resolve local folder — stored as a raw absolute path
            val localDir = File(profile.localUri)
            
            if (!localDir.exists() || !localDir.isDirectory || !localDir.canRead()) {
                if (profile.notificationsEnabled) {
                    showErrorNotification(applicationContext.getString(R.string.cannot_read_local_folder_for_profilename), profile.id.hashCode() + 10)
                }
                return Result.failure()
            }

            // 1. Get remote files
            var remoteFiles: List<NetworkFile>? = null
            try {
                remoteFiles = when (share.type) {
                    ShareType.SMB -> SmbShareClient.listFiles(share, profile.remotePath)
                    ShareType.NFS -> NfsShareClient.listFiles(share, profile.remotePath)
                    ShareType.DLNA -> throw UnsupportedOperationException("DLNA does not support sync")
                    else -> FtpShareClient.listFiles(share, profile.remotePath)
                }
            } catch (e: Exception) {
                // Ignore initial failure, we'll try to create the directory
                Log.w(TAG, "Failed to list remote files initially, attempting to create directory", e)
            }
            
            // Try creating the directory if the list call threw an exception (e.g., path not found)
            if (remoteFiles == null) {
                try {
                    when (share.type) {
                        ShareType.SMB -> {
                            SmbShareClient.mkdir(share, profile.remotePath)
                            remoteFiles = SmbShareClient.listFiles(share, profile.remotePath)
                        }
                        ShareType.NFS -> {
                            NfsShareClient.mkdir(share, profile.remotePath)
                            remoteFiles = NfsShareClient.listFiles(share, profile.remotePath)
                        }
                        ShareType.DLNA -> throw UnsupportedOperationException("DLNA does not support sync")
                        else -> {
                            FtpShareClient.mkdir(share, profile.remotePath)
                            remoteFiles = FtpShareClient.listFiles(share, profile.remotePath)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to create or access remote path after creation attempt", e)
                    if (profile.notificationsEnabled) {
                        showErrorNotification(applicationContext.getString(R.string.failed_to_access_remote_path_for_profilename), profile.id.hashCode() + 10)
                    }
                    return Result.failure()
                }
            }
            
            val finalRemoteFiles = remoteFiles ?: emptyList<NetworkFile>()
            val remoteSizes = finalRemoteFiles.associate { it.name to it.size }

            // 2. Get local files
            val localFiles = localDir.listFiles()?.filter { it.isFile } ?: emptyList()
            val filesToUpload = mutableListOf<File>()

            for (localFile in localFiles) {
                val name = localFile.name
                val remoteSize = remoteSizes[name]

                var resolvedRemoteSize: Long? = remoteSize

                // If the remote size is the unknown sentinel, query the server lazily for this file.
                if (remoteSize == za.kilowatch.ultimatefilemanager.network.SmbShareClient.SIZE_UNKNOWN_SENTINEL && share.type == za.kilowatch.ultimatefilemanager.network.ShareType.SMB) {
                    try {
                        val candidate = za.kilowatch.ultimatefilemanager.network.SmbShareClient.getFileSize(share, "${profile.remotePath.trimEnd('/')}/$name")
                        if (candidate != null) resolvedRemoteSize = candidate
                    } catch (e: Exception) {
                        Log.w(TAG, "Lazy size query failed for $name", e)
                    }
                }

                if (resolvedRemoteSize == null || resolvedRemoteSize != localFile.length()) {
                    filesToUpload.add(localFile)
                }
            }

            if (filesToUpload.isEmpty()) {
                profile.lastSyncTime = System.currentTimeMillis()
                profile.lastSyncFileCount = 0
                repo.save(profile)
                return Result.success()
            }

            // 3. Upload files
            var syncedCount = 0
            for ((index, fileToUpload) in filesToUpload.withIndex()) {
                val name = fileToUpload.name
                if (profile.notificationsEnabled) {
                    val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                        .setContentTitle(applicationContext.getString(R.string.ufm_sync))
                        .setContentText(applicationContext.getString(R.string.syncing_profilename_index_1filestouploadsize, profile.name, index + 1, filesToUpload.size))
                        .setSmallIcon(R.drawable.ic_network)
                        .setOngoing(true)
                        .build()
                        
                    val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.notify(notificationId, notification)
                }

                try {
                    val inStream = FileInputStream(fileToUpload)
                        
                    val outStream: OutputStream = when (share.type) {
                        ShareType.SMB -> SmbShareClient.openOutputStream(share, "${profile.remotePath.trimEnd('/')}/$name")
                        ShareType.NFS -> NfsShareClient.openOutputStream(share, "${profile.remotePath.trimEnd('/')}/$name")
                        ShareType.DLNA -> throw UnsupportedOperationException("DLNA does not support sync")
                        else -> FtpShareClient.openOutputStream(share, "${profile.remotePath.trimEnd('/')}/$name")
                    }
                    
                    inStream.use { input ->
                        outStream.use { output ->
                            za.kilowatch.ultimatefilemanager.util.CopyHelper.copy(input, output, fileToUpload.length())
                        }
                    }
                    syncedCount++
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to upload file $name", e)
                }
            }

            profile.lastSyncTime = System.currentTimeMillis()
            profile.lastSyncFileCount = syncedCount
            repo.save(profile)
            
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

    private fun setupNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Folder Sync"
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
        val repo = SyncProfileRepository.getInstance(applicationContext)
        val profileId = inputData.getString("PROFILE_ID") ?: ""
        val profile = repo.getById(profileId)
        val title = profile?.name ?: "Unknown"
        val notificationId = NOTIFICATION_ID_BASE + title.hashCode().rem(1000).let { if (it < 0) -it else it }
        return createForegroundInfo(applicationContext.getString(R.string.syncing_title), notificationId)
    }

    private fun createForegroundInfo(content: String, notificationId: Int): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(applicationContext.getString(R.string.ufm_sync))
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_network) // Will be updated to ic_sync later
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
            .setContentTitle(applicationContext.getString(R.string.ufm_sync_error))
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_network)
            .setAutoCancel(true)
            .build()
            
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(notificationId, notification)
    }
}
