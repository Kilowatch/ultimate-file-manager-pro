package za.kilowatch.ultimatefilemanager.download

import android.content.Context
import android.media.MediaScannerConnection
import android.util.Log
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Direct HTTP/HTTPS download engine using standard HttpURLConnection with chunked streaming and Range-resumption.
 */
class HttpDownloadEngine(private val context: Context) {

    private val activeTasks = ConcurrentHashMap<String, DownloadTask>()

    interface HttpListener {
        fun onHttpStarted(id: String, fileName: String, totalBytes: Long)
        fun onHttpProgress(id: String, progressPercent: Int, downloadSpeedBytesPerSec: Long, etaSeconds: Long, downloadedBytes: Long)
        fun onHttpFinished(id: String, file: File)
        fun onHttpError(id: String, error: String)
    }

    var listener: HttpListener? = null

    private class DownloadTask(
        val id: String,
        val url: String,
        val targetFile: File,
        var isPaused: AtomicBoolean = AtomicBoolean(false),
        var isCancelled: AtomicBoolean = AtomicBoolean(false),
        var thread: Thread? = null,
        @Volatile var connection: HttpURLConnection? = null
    )

    fun startDownload(id: String, url: String, destinationDir: File, customFileName: String? = null) {
        val rawName = customFileName ?: extractFileNameFromUrl(url)
        val safeName = File(rawName).name.replace("..", "").trim().ifBlank { "downloaded_file" }
        val targetFile = File(destinationDir, safeName).canonicalFile
        val destCanonical = destinationDir.canonicalFile

        if (!targetFile.toPath().startsWith(destCanonical.toPath())) {
            listener?.onHttpError(id, "Security violation: destination directory escape prevented")
            return
        }

        val task = DownloadTask(id, url, targetFile)
        activeTasks[id] = task

        val thread = Thread({
            runDownload(task)
        }, "HttpDownloader-$id")
        task.thread = thread
        thread.start()
    }

    private fun openConnectionWithRedirects(urlString: String, rangeBytes: Long): Pair<HttpURLConnection, Long> {
        var currentUrl = urlString
        var redirects = 0
        val maxRedirects = 5

        while (redirects < maxRedirects) {
            val url = URL(currentUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 30000
            conn.readTimeout = 60000
            conn.setRequestProperty("User-Agent", "UltimateFileManager/2.0")

            if (rangeBytes > 0) {
                conn.setRequestProperty("Range", "bytes=$rangeBytes-")
            }

            conn.connect()
            val code = conn.responseCode

            if (code == HttpURLConnection.HTTP_MOVED_PERM ||
                code == HttpURLConnection.HTTP_MOVED_TEMP ||
                code == HttpURLConnection.HTTP_SEE_OTHER ||
                code == 307 || code == 308
            ) {
                val newUrl = conn.getHeaderField("Location")
                conn.disconnect()
                if (newUrl != null) {
                    currentUrl = if (newUrl.startsWith("http://") || newUrl.startsWith("https://")) {
                        newUrl
                    } else {
                        URL(url, newUrl).toString()
                    }
                    redirects++
                    continue
                }
            }

            return Pair(conn, rangeBytes)
        }
        throw IllegalStateException("Too many HTTP redirects")
    }

    private fun runDownload(task: DownloadTask) {
        var downloadedBytes = if (task.targetFile.exists()) task.targetFile.length() else 0L

        var conn: HttpURLConnection? = null
        var inputStream: InputStream? = null
        var randomAccessFile: RandomAccessFile? = null

        try {
            val pair = openConnectionWithRedirects(task.url, downloadedBytes)
            conn = pair.first
            var effectiveOffset = pair.second
            task.connection = conn

            var code = conn.responseCode

            // If 416 (Requested Range Not Satisfiable), or Range not supported with failure, restart from 0
            if (code == 416 || (downloadedBytes > 0 && code != HttpURLConnection.HTTP_PARTIAL && code != HttpURLConnection.HTTP_OK)) {
                conn.disconnect()
                task.targetFile.delete()
                downloadedBytes = 0L
                val retry = openConnectionWithRedirects(task.url, 0L)
                conn = retry.first
                task.connection = conn
                effectiveOffset = 0L
                code = conn.responseCode
            }

            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                listener?.onHttpError(task.id, "HTTP Error: $code")
                activeTasks.remove(task.id)
                return
            }

            val isPartial = (code == HttpURLConnection.HTTP_PARTIAL)
            val initialBytes = if (isPartial) effectiveOffset else 0L
            if (!isPartial && effectiveOffset > 0) {
                // Server returned 200 OK instead of 206 Partial Content, so it restarted from beginning
                task.targetFile.delete()
            }

            val contentLength = conn.contentLengthLong
            val totalBytes = if (contentLength > 0) initialBytes + contentLength else -1L

            listener?.onHttpStarted(task.id, task.targetFile.name, totalBytes)

            inputStream = conn.inputStream
            randomAccessFile = RandomAccessFile(task.targetFile, "rw")
            randomAccessFile.seek(initialBytes)

            val buffer = ByteArray(64 * 1024)
            var read: Int
            var totalRead = initialBytes
            var lastTime = System.currentTimeMillis()
            var bytesSinceLastSpeed = 0L

            while (inputStream.read(buffer).also { read = it } != -1) {
                if (task.isCancelled.get()) {
                    randomAccessFile.close()
                    task.targetFile.delete()
                    activeTasks.remove(task.id)
                    return
                }
                if (task.isPaused.get()) {
                    randomAccessFile.close()
                    return
                }

                randomAccessFile.write(buffer, 0, read)
                totalRead += read
                bytesSinceLastSpeed += read

                val now = System.currentTimeMillis()
                val elapsed = now - lastTime
                if (elapsed >= 500) {
                    val speed = (bytesSinceLastSpeed * 1000) / elapsed
                    val progress = if (totalBytes > 0) ((totalRead * 100) / totalBytes).toInt() else 0
                    val remainingBytes = if (totalBytes > totalRead) totalBytes - totalRead else 0L
                    val etaSeconds = if (speed > 0) remainingBytes / speed else 0L

                    listener?.onHttpProgress(task.id, progress, speed, etaSeconds, totalRead)
                    lastTime = now
                    bytesSinceLastSpeed = 0L
                }
            }

            randomAccessFile.close()
            randomAccessFile = null
            activeTasks.remove(task.id)

            // Scan downloaded file into MediaStore
            MediaScannerConnection.scanFile(
                context,
                arrayOf(task.targetFile.absolutePath),
                null
            ) { path, uri ->
                Log.d(TAG, "Scanned $path into MediaStore: $uri")
            }

            listener?.onHttpFinished(task.id, task.targetFile)
        } catch (e: Exception) {
            if (!task.isCancelled.get() && !task.isPaused.get()) {
                Log.e(TAG, "Download error for ${task.url}", e)
                listener?.onHttpError(task.id, e.message ?: "Connection failed")
            }
            activeTasks.remove(task.id)
        } finally {
            try { randomAccessFile?.close() } catch (_: Exception) {}
            try { inputStream?.close() } catch (_: Exception) {}
            try { conn?.disconnect() } catch (_: Exception) {}
            if (task.isCancelled.get()) {
                try {
                    if (task.targetFile.exists()) {
                        task.targetFile.delete()
                    }
                } catch (_: Exception) {}
            }
        }
    }

    fun pause(id: String) {
        val task = activeTasks[id] ?: return
        task.isPaused.set(true)
        task.connection?.disconnect()
    }

    fun resume(id: String) {
        val task = activeTasks[id] ?: return
        task.isPaused.set(false)
        val thread = Thread({
            runDownload(task)
        }, "HttpDownloader-$id")
        task.thread = thread
        thread.start()
    }

    fun cancel(id: String, deleteFile: Boolean = true) {
        val task = activeTasks.remove(id) ?: return
        task.isCancelled.set(true)
        task.connection?.disconnect()
        if (deleteFile) {
            try {
                if (task.targetFile.exists()) {
                    task.targetFile.delete()
                }
            } catch (_: Exception) {}
        }
    }

    private fun extractFileNameFromUrl(url: String): String {
        return try {
            val cleanUrl = url.substringBefore("?").substringBefore("#")
            val candidate = cleanUrl.substringAfterLast("/")
            if (candidate.isNotBlank() && candidate.contains(".")) candidate else "downloaded_file"
        } catch (_: Exception) {
            "downloaded_file"
        }
    }

    companion object {
        private const val TAG = "HttpDownloadEngine"
    }
}
