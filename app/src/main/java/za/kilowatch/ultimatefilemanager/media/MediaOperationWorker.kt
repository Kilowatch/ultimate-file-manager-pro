package za.kilowatch.ultimatefilemanager.media

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.media.MediaScannerConnection
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.network.*
import za.kilowatch.ultimatefilemanager.util.GoRoLog
import za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter
import java.io.File
import java.util.UUID

/**
 * Background WorkManager worker for long-running media operations:
 * - Subtitle extraction (single or all tracks)
 * - Audio extraction (lossless direct copy or universal AAC transcoding)
 * - Video conversion / remuxing to MP4
 *
 * Runs as a foreground service with a persistent system notification so that long-running
 * FFmpeg conversions survive screen lock, app switching, and activity destruction.
 * Works seamlessly across both Local storage and Network / Online cloud shares.
 */
class MediaOperationWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        const val TAG = "MediaOperationWorker"
        const val CHANNEL_ID = "media_operations_channel"

        const val KEY_OP_TYPE = "key_op_type"
        const val OP_EXTRACT_AUDIO = "EXTRACT_AUDIO"
        const val OP_EXTRACT_ALL_AUDIO = "EXTRACT_ALL_AUDIO"
        const val OP_EXTRACT_SUBTITLES = "EXTRACT_SUBTITLES"
        const val OP_EXTRACT_ALL_SUBTITLES = "EXTRACT_ALL_SUBTITLES"
        const val OP_CONVERT_TO_MP4 = "CONVERT_TO_MP4"

        const val KEY_IS_NETWORK = "key_is_network"
        const val KEY_LOCAL_FILE_PATH = "key_local_file_path"

        const val KEY_SHARE_ID = "key_share_id"
        const val KEY_REMOTE_FILE_PATH = "key_remote_file_path"
        const val KEY_REMOTE_DIR = "key_remote_dir"
        const val KEY_FILE_NAME = "key_file_name"
        const val KEY_FILE_SIZE = "key_file_size"
        const val KEY_COPY_TO_LOCAL = "key_copy_to_local"

        const val KEY_STREAM_INDEX = "key_stream_index"
        const val KEY_LANG_TAG = "key_lang_tag"
        const val KEY_UNIVERSAL_AAC = "key_universal_aac"
        /** For isServerMode SMB shares with empty remotePath: the SMB share name (first segment of currentPath). */
        const val KEY_EFFECTIVE_SHARE_NAME = "key_effective_share_name"

        // Output Keys
        const val KEY_OUTPUT_NAME = "key_output_name"
        const val KEY_OUTPUT_COUNT = "key_output_count"
        const val KEY_ERROR_MESSAGE = "key_error_message"

        /**
         * Enqueues a local media operation.
         */
        fun enqueueLocal(
            context: Context,
            opType: String,
            localFile: File,
            streamIndex: Int = -1,
            langTag: String? = null,
            universalAac: Boolean = false
        ): UUID {
            val data = workDataOf(
                KEY_OP_TYPE to opType,
                KEY_IS_NETWORK to false,
                KEY_LOCAL_FILE_PATH to localFile.absolutePath,
                KEY_FILE_NAME to localFile.name,
                KEY_STREAM_INDEX to streamIndex,
                KEY_LANG_TAG to langTag,
                KEY_UNIVERSAL_AAC to universalAac
            )
            val request = OneTimeWorkRequestBuilder<MediaOperationWorker>()
                .setInputData(data)
                .addTag("media_op_local")
                .build()
            WorkManager.getInstance(context.applicationContext).enqueue(request)
            return request.id
        }

        /**
         * Enqueues a remote network media operation.
         */
        fun enqueueNetwork(
            context: Context,
            opType: String,
            shareId: String,
            remoteFilePath: String,
            remoteDir: String,
            fileName: String,
            fileSize: Long,
            copyToLocal: Boolean,
            streamIndex: Int = -1,
            langTag: String? = null,
            universalAac: Boolean = false,
            effectiveShareName: String = ""
        ): UUID {
            val data = workDataOf(
                KEY_OP_TYPE to opType,
                KEY_IS_NETWORK to true,
                KEY_SHARE_ID to shareId,
                KEY_REMOTE_FILE_PATH to remoteFilePath,
                KEY_REMOTE_DIR to remoteDir,
                KEY_FILE_NAME to fileName,
                KEY_FILE_SIZE to fileSize,
                KEY_COPY_TO_LOCAL to copyToLocal,
                KEY_STREAM_INDEX to streamIndex,
                KEY_LANG_TAG to langTag,
                KEY_UNIVERSAL_AAC to universalAac,
                KEY_EFFECTIVE_SHARE_NAME to effectiveShareName
            )
            val request = OneTimeWorkRequestBuilder<MediaOperationWorker>()
                .setInputData(data)
                .addTag("media_op_network")
                .build()
            WorkManager.getInstance(context.applicationContext).enqueue(request)
            return request.id
        }
    }

    override suspend fun doWork(): Result {
        val opType = inputData.getString(KEY_OP_TYPE) ?: return Result.failure()
        val isNetwork = inputData.getBoolean(KEY_IS_NETWORK, false)
        val fileName = inputData.getString(KEY_FILE_NAME) ?: "media"

        val titleRes = when (opType) {
            OP_CONVERT_TO_MP4 -> R.string.media_op_converting_video
            OP_EXTRACT_AUDIO, OP_EXTRACT_ALL_AUDIO -> R.string.media_op_extracting_audio
            OP_EXTRACT_SUBTITLES, OP_EXTRACT_ALL_SUBTITLES -> R.string.media_op_extracting_subtitles
            else -> R.string.media_operation_channel_name
        }
        val iconRes = when (opType) {
            OP_CONVERT_TO_MP4 -> R.drawable.ic_convert_video
            OP_EXTRACT_AUDIO, OP_EXTRACT_ALL_AUDIO -> R.drawable.ic_audio
            else -> R.drawable.ic_subtitles
        }

        try {
            setForeground(createForegroundInfo(applicationContext.getString(titleRes), fileName, iconRes))
        } catch (e: Exception) {
            GoRoLog.w(TAG, "Failed to set foreground info: ${e.message}")
        }

        return if (isNetwork) {
            doNetworkWork(opType, fileName, iconRes)
        } else {
            doLocalWork(opType, fileName, iconRes)
        }
    }

    private fun doLocalWork(opType: String, fileName: String, iconRes: Int): Result {
        val localPath = inputData.getString(KEY_LOCAL_FILE_PATH) ?: return Result.failure()
        val localFile = File(localPath)
        if (!localFile.exists() || !localFile.canRead()) {
            return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "Local file does not exist or cannot be read"))
        }

        val streamIndex = inputData.getInt(KEY_STREAM_INDEX, -1)
        val langTag = inputData.getString(KEY_LANG_TAG)
        val universalAac = inputData.getBoolean(KEY_UNIVERSAL_AAC, false)

        return try {
            when (opType) {
                OP_EXTRACT_AUDIO -> {
                    val result = FFmpegMediaHelper.extractAudio(localFile, streamIndex, langTag, universalAac = universalAac)
                    if (result != null) {
                        MediaScannerConnection.scanFile(applicationContext, arrayOf(result.absolutePath), null, null)
                        showCompletionNotification(
                            applicationContext.getString(R.string.media_op_completed_title),
                            applicationContext.getString(R.string.audio_extracted_success) + "\n" + result.name,
                            iconRes
                        )
                        Result.success(workDataOf(KEY_OUTPUT_NAME to result.name, KEY_OUTPUT_COUNT to 1))
                    } else {
                        Result.failure(workDataOf(KEY_ERROR_MESSAGE to "Extraction failed"))
                    }
                }
                OP_EXTRACT_ALL_AUDIO -> {
                    val info = FFmpegMediaHelper.getMediaInfo(localFile)
                    val audioStreams = info?.audioStreams ?: emptyList()
                    val results = FFmpegMediaHelper.extractAllAudio(localFile, audioStreams, universalAac = universalAac)
                    if (results.isNotEmpty()) {
                        MediaScannerConnection.scanFile(applicationContext, results.map { it.absolutePath }.toTypedArray(), null, null)
                        showCompletionNotification(
                            applicationContext.getString(R.string.media_op_completed_title),
                            applicationContext.getString(R.string.audio_extracted_multiple_success, results.size),
                            iconRes
                        )
                        Result.success(workDataOf(KEY_OUTPUT_COUNT to results.size))
                    } else {
                        Result.failure(workDataOf(KEY_ERROR_MESSAGE to "Extraction failed"))
                    }
                }
                OP_EXTRACT_SUBTITLES -> {
                    val result = FFmpegMediaHelper.extractSubtitles(localFile, streamIndex, langTag)
                    if (result != null) {
                        MediaScannerConnection.scanFile(applicationContext, arrayOf(result.absolutePath), null, null)
                        showCompletionNotification(
                            applicationContext.getString(R.string.media_op_completed_title),
                            applicationContext.getString(R.string.subtitles_extracted_success) + "\n" + result.name,
                            iconRes
                        )
                        Result.success(workDataOf(KEY_OUTPUT_NAME to result.name, KEY_OUTPUT_COUNT to 1))
                    } else {
                        Result.failure(workDataOf(KEY_ERROR_MESSAGE to "Extraction failed"))
                    }
                }
                OP_EXTRACT_ALL_SUBTITLES -> {
                    val info = FFmpegMediaHelper.getMediaInfo(localFile)
                    val subStreams = info?.subtitleStreams ?: emptyList()
                    val results = FFmpegMediaHelper.extractAllSubtitles(localFile, subStreams)
                    if (results.isNotEmpty()) {
                        MediaScannerConnection.scanFile(applicationContext, results.map { it.absolutePath }.toTypedArray(), null, null)
                        showCompletionNotification(
                            applicationContext.getString(R.string.media_op_completed_title),
                            applicationContext.getString(R.string.subtitles_extracted_multiple_success, results.size),
                            iconRes
                        )
                        Result.success(workDataOf(KEY_OUTPUT_COUNT to results.size))
                    } else {
                        Result.failure(workDataOf(KEY_ERROR_MESSAGE to "Extraction failed"))
                    }
                }
                OP_CONVERT_TO_MP4 -> {
                    val result = FFmpegMediaHelper.convertToMp4(localFile)
                    if (result != null) {
                        MediaScannerConnection.scanFile(applicationContext, arrayOf(result.absolutePath), null, null)
                        showCompletionNotification(
                            applicationContext.getString(R.string.media_op_completed_title),
                            applicationContext.getString(R.string.convert_to_mp4_success, result.name),
                            iconRes
                        )
                        Result.success(workDataOf(KEY_OUTPUT_NAME to result.name, KEY_OUTPUT_COUNT to 1))
                    } else {
                        Result.failure(workDataOf(KEY_ERROR_MESSAGE to "Conversion failed"))
                    }
                }
                else -> Result.failure(workDataOf(KEY_ERROR_MESSAGE to "Unknown operation: $opType"))
            }
        } catch (e: Exception) {
            GoRoLog.e(TAG, "Local media operation failed", e)
            Result.failure(workDataOf(KEY_ERROR_MESSAGE to (e.message ?: "Unknown error")))
        }
    }

    private suspend fun doNetworkWork(opType: String, fileName: String, iconRes: Int): Result {
        val shareId = inputData.getString(KEY_SHARE_ID) ?: return Result.failure()
        val remoteFilePath = inputData.getString(KEY_REMOTE_FILE_PATH) ?: return Result.failure()
        val remoteDir = inputData.getString(KEY_REMOTE_DIR) ?: ""
        val fileSize = inputData.getLong(KEY_FILE_SIZE, 0L)
        val copyToLocal = inputData.getBoolean(KEY_COPY_TO_LOCAL, false)
        val streamIndex = inputData.getInt(KEY_STREAM_INDEX, -1)
        val langTag = inputData.getString(KEY_LANG_TAG)
        val universalAac = inputData.getBoolean(KEY_UNIVERSAL_AAC, false)
        val effectiveShareName = inputData.getString(KEY_EFFECTIVE_SHARE_NAME) ?: ""

        var share = NetworkShareRepository.getInstance(applicationContext).getById(shareId)
            ?: return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "Network share not found"))

        // For isServerMode SMB shares with empty remotePath: if the caller passed an effective
        // share name (first path segment of currentPath), apply it so SmbShareClient can correctly
        // resolve upload paths when the repository-stored remotePath is empty.
        if (share.type == ShareType.SMB && share.isServerMode && share.remotePath.isEmpty()
            && effectiveShareName.isNotEmpty()) {
            share = share.copy(remotePath = "/$effectiveShareName")
        }

        val ext = fileName.substringAfterLast('.', "")
        val mimeType = za.kilowatch.ultimatefilemanager.util.MimeTypeHelper.getOrFallback(ext)
        val safeName = fileName.replace(Regex("[/\\\\:*?\"<>|]"), "_")
        val baseName = fileName.substringBeforeLast('.')

        return if (copyToLocal) {
            // Mode B: Copy source video to local cacheDir first
            val cacheFile = File(applicationContext.cacheDir, "ufm_copy_${System.currentTimeMillis()}_$safeName")
            try {
                val inStream = openNetworkInputStream(share, remoteFilePath)
                inStream.use { inp -> cacheFile.outputStream().use { out -> inp.copyTo(out) } }

                val outputs = mutableListOf<File>()
                when (opType) {
                    OP_EXTRACT_AUDIO -> {
                        val res = FFmpegMediaHelper.extractAudio(cacheFile, streamIndex, langTag, universalAac = universalAac)
                        if (res != null) outputs.add(res)
                    }
                    OP_EXTRACT_ALL_AUDIO -> {
                        val info = FFmpegMediaHelper.getMediaInfo(cacheFile)
                        val audioStreams = info?.audioStreams ?: emptyList()
                        outputs.addAll(FFmpegMediaHelper.extractAllAudio(cacheFile, audioStreams, universalAac = universalAac))
                    }
                    OP_EXTRACT_SUBTITLES -> {
                        val res = FFmpegMediaHelper.extractSubtitles(cacheFile, streamIndex, langTag)
                        if (res != null) outputs.add(res)
                    }
                    OP_EXTRACT_ALL_SUBTITLES -> {
                        val info = FFmpegMediaHelper.getMediaInfo(cacheFile)
                        val subStreams = info?.subtitleStreams ?: emptyList()
                        outputs.addAll(FFmpegMediaHelper.extractAllSubtitles(cacheFile, subStreams))
                    }
                    OP_CONVERT_TO_MP4 -> {
                        val res = FFmpegMediaHelper.convertToMp4(cacheFile)
                        if (res != null) outputs.add(res)
                    }
                }

                if (outputs.isEmpty()) {
                    return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "Operation failed"))
                }

                if (!share.readOnly) {
                    for (out in outputs) {
                        val destRemotePath = if (remoteDir.isEmpty()) out.name else "$remoteDir/${out.name}"
                        uploadFileToNetwork(share, out, destRemotePath)
                        out.delete()
                    }
                } else {
                    val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                    for (out in outputs) {
                        val target = File(downloadsDir, out.name)
                        out.copyTo(target, overwrite = true)
                        out.delete()
                        MediaScannerConnection.scanFile(applicationContext, arrayOf(target.absolutePath), null, null)
                    }
                }

                showCompletionNotification(
                    applicationContext.getString(R.string.media_op_completed_title),
                    applicationContext.getString(R.string.audio_extracted_success) + "\n" + outputs.firstOrNull()?.name.orEmpty(),
                    iconRes
                )
                Result.success(workDataOf(KEY_OUTPUT_COUNT to outputs.size))
            } catch (e: Exception) {
                GoRoLog.e(TAG, "Network media operation (copy-first) failed", e)
                Result.failure(workDataOf(KEY_ERROR_MESSAGE to (e.message ?: "Unknown error")))
            } finally {
                cacheFile.delete()
            }
        } else {
            // Mode A: Direct Remote Zero-Copy via NetworkHttpProxyServer
            val streamUrl = NetworkHttpProxyServer.register(share, remoteFilePath, mimeType, fileSize)
            val sessionUuid = streamUrl.substringAfter("127.0.0.1:").substringAfter('/').substringBefore('/')

            try {
                val outputs = mutableListOf<File>()
                when (opType) {
                    OP_EXTRACT_AUDIO -> {
                        val targetExt = if (universalAac) "m4a" else {
                            val info = FFmpegMediaHelper.getMediaInfoFromPathOrUrl(streamUrl)
                            val codec = info?.audioStreams?.find { it.index == streamIndex }?.codec ?: "m4a"
                            FFmpegMediaHelper.getAudioExtensionForCodec(codec)
                        }
                        val langSuffix = if (!langTag.isNullOrBlank()) "_${langTag.lowercase().trim()}" else ""
                        val targetFile = File(applicationContext.cacheDir, "ufm_tmp_${System.currentTimeMillis()}_${baseName}${langSuffix}.$targetExt")
                        val res = FFmpegMediaHelper.extractAudioFromUrl(streamUrl, streamIndex, targetFile, universalAac = universalAac)
                        if (res != null) outputs.add(res)
                    }
                    OP_EXTRACT_ALL_AUDIO -> {
                        val info = FFmpegMediaHelper.getMediaInfoFromPathOrUrl(streamUrl)
                        val audioStreams = info?.audioStreams ?: emptyList()
                        outputs.addAll(FFmpegMediaHelper.extractAllAudioFromUrl(streamUrl, audioStreams, baseName, applicationContext.cacheDir, universalAac = universalAac))
                    }
                    OP_EXTRACT_SUBTITLES -> {
                        val langSuffix = if (!langTag.isNullOrBlank()) "_${langTag.lowercase().trim()}" else ""
                        val targetFile = File(applicationContext.cacheDir, "ufm_tmp_${System.currentTimeMillis()}_${baseName}${langSuffix}.srt")
                        val res = FFmpegMediaHelper.extractSubtitlesFromUrl(streamUrl, streamIndex, targetFile)
                        if (res != null) outputs.add(res)
                    }
                    OP_EXTRACT_ALL_SUBTITLES -> {
                        val info = FFmpegMediaHelper.getMediaInfoFromPathOrUrl(streamUrl)
                        val subStreams = info?.subtitleStreams ?: emptyList()
                        outputs.addAll(FFmpegMediaHelper.extractAllSubtitlesFromUrl(streamUrl, subStreams, baseName, applicationContext.cacheDir))
                    }
                    OP_CONVERT_TO_MP4 -> {
                        val targetFile = File(applicationContext.cacheDir, "ufm_tmp_${System.currentTimeMillis()}_${baseName}_converted.mp4")
                        val res = FFmpegMediaHelper.convertToMp4FromUrl(streamUrl, targetFile)
                        if (res != null) outputs.add(res)
                    }
                }

                if (outputs.isEmpty()) {
                    return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "Direct streaming operation failed"))
                }

                if (!share.readOnly) {
                    for (out in outputs) {
                        val destRemotePath = if (remoteDir.isEmpty()) out.name else "$remoteDir/${out.name}"
                        uploadFileToNetwork(share, out, destRemotePath)
                        out.delete()
                    }
                } else {
                    val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                    for (out in outputs) {
                        val target = File(downloadsDir, out.name)
                        out.copyTo(target, overwrite = true)
                        out.delete()
                        MediaScannerConnection.scanFile(applicationContext, arrayOf(target.absolutePath), null, null)
                    }
                }

                showCompletionNotification(
                    applicationContext.getString(R.string.media_op_completed_title),
                    applicationContext.getString(R.string.audio_extracted_success) + "\n" + outputs.firstOrNull()?.name.orEmpty(),
                    iconRes
                )
                Result.success(workDataOf(KEY_OUTPUT_COUNT to outputs.size))
            } catch (e: Exception) {
                GoRoLog.e(TAG, "Direct network media operation failed", e)
                Result.failure(workDataOf(KEY_ERROR_MESSAGE to (e.message ?: "Unknown error")))
            } finally {
                NetworkHttpProxyServer.unregister(sessionUuid)
            }
        }
    }

    private suspend fun openNetworkInputStream(share: NetworkShare, path: String): java.io.InputStream {
        return when (share.type) {
            ShareType.SMB -> SmbShareClient.openInputStream(share, path)
            ShareType.FTP -> FtpShareClient.openInputStream(share, path)
            ShareType.TV  -> TvShareClient.openInputStream(share, path)
            ShareType.SFTP, ShareType.SCP -> SshShareClient.openInputStream(share, path)
            ShareType.ONEDRIVE -> OnedriveShareClient.openInputStream(share, path).first
            ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.openInputStream(share, path).first
            ShareType.DROPBOX -> DropboxShareClient.openInputStream(share, path).first
            ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.openInputStream(share, path).first
            ShareType.WEBDAV -> WebDavShareClient.openInputStream(share, path).first
            ShareType.NFS -> NfsShareClient.openInputStream(share, path)
            ShareType.DLNA -> DlnaShareClient.openInputStream(share, path)
        }
    }

    private fun stripSharePrefix(share: NetworkShare, path: String): String {
        val clean = path.trimStart('/')
        if (!share.isServerMode || share.remotePath.isEmpty()) return clean
        val prefix = share.remotePath.trimStart('/')
        return when {
            clean.startsWith("$prefix/", ignoreCase = true) -> clean.substring(prefix.length + 1)
            clean.equals(prefix, ignoreCase = true)         -> ""
            else                                           -> clean
        }
    }

    private suspend fun uploadFileToNetwork(share: NetworkShare, localFile: File, rawRemotePath: String) {
        val cleanRemotePath = stripSharePrefix(share, rawRemotePath).replace('\\', '/')
        val parentPath = cleanRemotePath.substringBeforeLast('/', "")
        if (parentPath.isNotEmpty()) {
            val segments = parentPath.split("/").filter { it.isNotEmpty() }
            var currentSegment = ""
            for (segment in segments) {
                currentSegment = if (currentSegment.isEmpty()) segment else "$currentSegment/$segment"
                try {
                    when (share.type) {
                        ShareType.SMB -> SmbShareClient.mkdir(share, currentSegment)
                        ShareType.FTP -> FtpShareClient.mkdir(share, currentSegment)
                        ShareType.TV  -> TvShareClient.mkdir(share, currentSegment)
                        ShareType.SFTP, ShareType.SCP -> SshShareClient.mkdir(share, currentSegment)
                        ShareType.ONEDRIVE -> OnedriveShareClient.mkdir(share, currentSegment)
                        ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.mkdir(share, currentSegment)
                        ShareType.DROPBOX -> DropboxShareClient.mkdir(share, currentSegment)
                        ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.mkdir(share, currentSegment)
                        ShareType.WEBDAV -> WebDavShareClient.mkdir(share, currentSegment)
                        ShareType.NFS -> NfsShareClient.mkdir(share, currentSegment)
                        ShareType.DLNA -> throw UnsupportedOperationException("DLNA is read-only")
                    }
                } catch (_: Exception) {}
            }
        }
        val inStream = localFile.inputStream()
        try {
            when (share.type) {
                ShareType.SMB -> SmbShareClient.openOutputStream(share, cleanRemotePath).use { out -> inStream.copyTo(out) }
                ShareType.FTP -> FtpShareClient.openOutputStream(share, cleanRemotePath).use { out -> inStream.copyTo(out) }
                ShareType.TV -> TvShareClient.uploadStream(share, cleanRemotePath, inStream, localFile.length())
                ShareType.SFTP, ShareType.SCP -> SshShareClient.openOutputStream(share, cleanRemotePath).use { out -> inStream.copyTo(out) }
                ShareType.ONEDRIVE -> OnedriveShareClient.openOutputStream(share, cleanRemotePath).use { out -> inStream.copyTo(out) }
                ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.openOutputStream(share, cleanRemotePath).use { out -> inStream.copyTo(out) }
                ShareType.DROPBOX -> DropboxShareClient.openOutputStream(share, cleanRemotePath).use { out -> inStream.copyTo(out) }
                ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.openOutputStream(share, cleanRemotePath).use { out -> inStream.copyTo(out) }
                ShareType.WEBDAV -> WebDavShareClient.openOutputStream(share, cleanRemotePath).use { out -> inStream.copyTo(out) }
                ShareType.NFS -> NfsShareClient.openOutputStream(share, cleanRemotePath).use { out -> inStream.copyTo(out) }
                ShareType.DLNA -> throw UnsupportedOperationException("DLNA is read-only")
            }
        } finally {
            inStream.close()
        }
    }

    private fun createForegroundInfo(title: String, subtitle: String, iconRes: Int): ForegroundInfo {
        createNotificationChannel()
        val notificationId = 15000 + (id.hashCode() % 1000).let { if (it < 0) -it else it }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setSmallIcon(iconRes)
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                applicationContext.getString(R.string.media_operation_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = applicationContext.getString(R.string.media_operation_channel_desc)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun showCompletionNotification(title: String, message: String, iconRes: Int) {
        createNotificationChannel()
        val notificationId = 16000 + (id.hashCode() % 1000).let { if (it < 0) -it else it }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(iconRes)
            .setAutoCancel(true)
            .build()
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(notificationId, notification)
    }
}
