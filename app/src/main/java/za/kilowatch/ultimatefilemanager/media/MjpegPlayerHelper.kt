package za.kilowatch.ultimatefilemanager.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.widget.ImageView
import kotlinx.coroutines.*
import za.kilowatch.ultimatefilemanager.network.IRandomAccessFile
import za.kilowatch.ultimatefilemanager.storage.SafTreeManager
import za.kilowatch.ultimatefilemanager.util.GoRoLog
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.ConcurrentHashMap

interface MjpegDataSource {
    fun length(): Long
    fun read(position: Long, buffer: ByteArray, length: Int): Int
    fun close()
}

class FileMjpegDataSource(private val file: File) : MjpegDataSource {
    private var ra: RandomAccessFile? = null
    override fun length(): Long = file.length()
    override fun read(position: Long, buffer: ByteArray, length: Int): Int {
        val r = ra ?: RandomAccessFile(file, "r").also { ra = it }
        r.seek(position)
        return r.read(buffer, 0, length)
    }
    override fun close() {
        try { ra?.close() } catch (_: Exception) {}
        ra = null
    }
}

class SafMjpegDataSource(
    private val context: Context,
    private val path: String,
    private val contentUri: Uri? = null
) : MjpegDataSource {
    private var pfd: ParcelFileDescriptor? = null
    private var channel: FileChannel? = null
    private var fileLength: Long = -1L

    private fun ensureOpen(): FileChannel? {
        if (channel != null) return channel
        try {
            val uri = contentUri 
                ?: SafTreeManager.getDocumentUriForPath(context, path) 
                ?: Uri.parse(path)
            val fd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
            pfd = fd
            val fis = FileInputStream(fd.fileDescriptor)
            channel = fis.channel
            fileLength = channel?.size() ?: SafTreeManager.getFileSize(context, path)
            return channel
        } catch (e: Exception) {
            GoRoLog.e("SafMjpegDataSource", "Failed to open SAF MJPEG data source: ${e.message}", e)
            return null
        }
    }

    override fun length(): Long {
        if (fileLength >= 0) return fileLength
        ensureOpen()
        return fileLength.coerceAtLeast(0L)
    }

    override fun read(position: Long, buffer: ByteArray, length: Int): Int {
        val ch = ensureOpen() ?: return -1
        return try {
            ch.position(position)
            val byteBuffer = ByteBuffer.wrap(buffer, 0, length)
            ch.read(byteBuffer)
        } catch (e: Exception) {
            -1
        }
    }

    override fun close() {
        try { channel?.close() } catch (_: Exception) {}
        try { pfd?.close() } catch (_: Exception) {}
        channel = null
        pfd = null
    }
}

class RemoteMjpegDataSource(private val remoteFile: IRandomAccessFile) : MjpegDataSource {
    override fun length(): Long = remoteFile.size
    override fun read(position: Long, buffer: ByteArray, length: Int): Int {
        return remoteFile.read(position, buffer, length)
    }
    override fun close() {
        try { remoteFile.close() } catch (_: Exception) {}
    }
}

object MjpegFrameDecoder {
    fun isBlackBitmap(bitmap: Bitmap): Boolean {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return true
        val xSteps = listOf(width / 4, width / 3, width / 2, 2 * width / 3, 3 * width / 4)
        val ySteps = listOf(height / 4, height / 3, height / 2, 2 * height / 3, 3 * height / 4)
        var nonBlackCount = 0
        for (x in xSteps) {
            for (y in ySteps) {
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                if (r > 25 || g > 25 || b > 25) {
                    nonBlackCount++
                    if (nonBlackCount >= 2) return false
                }
            }
        }
        return true
    }

    /**
     * Extracts and decodes a representative non-black JPEG frame from an MJPEG byte stream
     * by scanning for standard JPEG SOI (0xFF 0xD8) to EOI (0xFF 0xD9) markers.
     */
    fun decodeFirstFrame(inputStream: InputStream, maxDim: Int = 512, maxFramesToScan: Int = 30): Bitmap? {
        return try {
            val stream = if (inputStream is BufferedInputStream) inputStream else BufferedInputStream(inputStream, 64 * 1024)
            var framesScanned = 0
            var fallbackBmp: Bitmap? = null

            while (framesScanned < maxFramesToScan) {
                val baos = ByteArrayOutputStream(128 * 1024)
                var foundStart = false
                var prev = -1
                var b = stream.read()
                while (b != -1) {
                    val ub = b and 0xFF
                    if (!foundStart) {
                        if (prev == 0xFF && ub == 0xD8) {
                            foundStart = true
                            baos.write(0xFF)
                            baos.write(0xD8)
                        }
                    } else {
                        baos.write(ub)
                        if (prev == 0xFF && ub == 0xD9) {
                            break
                        }
                        if (baos.size() > 5 * 1024 * 1024) break
                    }
                    prev = ub
                    b = stream.read()
                }

                if (!foundStart || baos.size() == 0) break
                framesScanned++

                val bytes = baos.toByteArray()
                val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOpts)
                if (boundsOpts.outWidth > 0 && boundsOpts.outHeight > 0) {
                    val sample = maxOf(1, maxOf(boundsOpts.outWidth, boundsOpts.outHeight) / maxDim)
                    val decodeOpts = BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inPreferredConfig = Bitmap.Config.RGB_565
                    }
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
                    if (bmp != null) {
                        if (!isBlackBitmap(bmp)) {
                            fallbackBmp?.recycle()
                            return bmp
                        } else {
                            if (fallbackBmp == null) {
                                fallbackBmp = bmp
                            } else {
                                bmp.recycle()
                            }
                        }
                    }
                }
            }
            fallbackBmp
        } catch (e: Exception) {
            GoRoLog.e("MjpegFrameDecoder", "Failed to decode frame: ${e.message}", e)
            null
        }
    }
}



/**
 * High-performance progressive frame streaming engine for Motion JPEG (.mjpeg, .mjpg, .mjp) files.
 * Supports instant start over local files and remote network streams (SMB, SFTP, FTP, WebDAV, Cloud)
 * with background progressive indexing and look-ahead frame prefetching.
 */
class MjpegPlayerHelper(
    private val dataSource: MjpegDataSource,
    private val frameRateFps: Int = 30,
    private val onFrameRendered: (currentMs: Long, totalDurationMs: Long) -> Unit,
    private val onPlaybackStateChanged: (isPlaying: Boolean) -> Unit
) {
    constructor(
        file: File,
        frameRateFps: Int = 30,
        onFrameRendered: (currentMs: Long, totalDurationMs: Long) -> Unit,
        onPlaybackStateChanged: (isPlaying: Boolean) -> Unit
    ) : this(FileMjpegDataSource(file), frameRateFps, onFrameRendered, onPlaybackStateChanged)

    constructor(
        remoteFile: IRandomAccessFile,
        frameRateFps: Int = 30,
        onFrameRendered: (currentMs: Long, totalDurationMs: Long) -> Unit,
        onPlaybackStateChanged: (isPlaying: Boolean) -> Unit
    ) : this(RemoteMjpegDataSource(remoteFile), frameRateFps, onFrameRendered, onPlaybackStateChanged)

    private val frameDurationMs = 1000L / frameRateFps
    private val frameOffsets = java.util.Collections.synchronizedList(ArrayList<Pair<Long, Int>>())
    @Volatile private var isFullyIndexed = false

    var isPlaying = false
        private set
    var currentFrame = 0
        private set

    val totalDurationMs: Long
        get() {
            val count = frameOffsets.size
            if (count == 0) return 0L
            if (!isFullyIndexed) {
                val lastOffset = frameOffsets.lastOrNull()?.first ?: 1L
                val totalLen = dataSource.length()
                if (lastOffset > 0 && totalLen > lastOffset) {
                    val estimatedTotalFrames = ((count.toDouble() / lastOffset) * totalLen).toLong()
                    return estimatedTotalFrames * frameDurationMs
                }
            }
            return count * frameDurationMs
        }

    val currentPositionMs: Long
        get() = currentFrame * frameDurationMs

    private var playbackJob: Job? = null
    private var indexJob: Job? = null
    private var prefetchScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val frameCache = ConcurrentHashMap<Int, ByteArray>()

    fun start(imageView: ImageView, scope: CoroutineScope) {
        startIndexing(scope)
        scope.launch {
            var waited = 0
            while (frameOffsets.isEmpty() && waited < 50 && indexJob?.isActive == true) {
                delay(10)
                waited++
            }
            if (frameOffsets.isNotEmpty()) {
                startPlayback(imageView, scope)
            }
        }
    }

    private fun startIndexing(scope: CoroutineScope) {
        if (indexJob != null) return
        indexJob = scope.launch(Dispatchers.IO) {
            try {
                val len = dataSource.length()
                val buffer = ByteArray(512 * 1024)
                var filePos = 0L
                var frameStart = -1L
                var prevByte = -1

                while (isActive && filePos < len) {
                    val toRead = minOf(buffer.size.toLong(), len - filePos).toInt()
                    val read = dataSource.read(filePos, buffer, toRead)
                    if (read <= 0) break

                    for (i in 0 until read) {
                        val b = buffer[i].toInt() and 0xFF
                        if (frameStart == -1L) {
                            if (prevByte == 0xFF && b == 0xD8) {
                                frameStart = filePos + i - 1
                            }
                        } else {
                            if (prevByte == 0xFF && b == 0xD9) {
                                val frameEnd = filePos + i + 1
                                val frameSize = (frameEnd - frameStart).toInt()
                                if (frameSize > 4) {
                                    val idx = frameOffsets.size
                                    frameOffsets.add(Pair(frameStart, frameSize))
                                    if (idx < 30) {
                                        val frameData = ByteArray(frameSize)
                                        val bufOffset = (frameStart - filePos).toInt()
                                        if (bufOffset >= 0 && bufOffset + frameSize <= read) {
                                            System.arraycopy(buffer, bufOffset, frameData, 0, frameSize)
                                            frameCache[idx] = frameData
                                        }
                                    }
                                }
                                frameStart = -1L
                                prevByte = -1
                                continue
                            }
                        }
                        prevByte = b
                    }
                    filePos += read
                }
                isFullyIndexed = true
                GoRoLog.i("MjpegPlayerHelper", "Finished indexing ${frameOffsets.size} frames")
            } catch (e: Exception) {
                GoRoLog.e("MjpegPlayerHelper", "Indexing error", e)
            }
        }
    }

    fun startPlayback(imageView: ImageView, scope: CoroutineScope) {
        isPlaying = true
        onPlaybackStateChanged(true)

        playbackJob?.cancel()
        playbackJob = scope.launch(Dispatchers.Default) {
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
            }

            while (isActive && isPlaying) {
                val size = frameOffsets.size
                if (size == 0) {
                    delay(30)
                    continue
                }
                if (currentFrame >= size) {
                    if (isFullyIndexed) {
                        currentFrame = 0
                    } else {
                        delay(frameDurationMs)
                        continue
                    }
                }

                prefetchFrames(currentFrame, 10)

                val frameBytes = frameCache.remove(currentFrame) ?: withContext(Dispatchers.IO) {
                    loadFrameBytes(currentFrame)
                }

                if (frameBytes != null) {
                    val bmp = BitmapFactory.decodeByteArray(frameBytes, 0, frameBytes.size, opts)
                    if (bmp != null) {
                        withContext(Dispatchers.Main) {
                            imageView.setImageBitmap(bmp)
                            onFrameRendered(currentPositionMs, totalDurationMs)
                        }
                    }
                }

                currentFrame++
                delay(frameDurationMs)
            }
        }
    }

    private fun prefetchFrames(fromIdx: Int, count: Int) {
        prefetchScope.launch {
            for (i in fromIdx until minOf(fromIdx + count, frameOffsets.size)) {
                if (!frameCache.containsKey(i)) {
                    val bytes = loadFrameBytes(i)
                    if (bytes != null) frameCache[i] = bytes
                }
            }
            val oldKeys = frameCache.keys().toList().filter { it < fromIdx - 5 }
            oldKeys.forEach { frameCache.remove(it) }
        }
    }

    private fun loadFrameBytes(frameIdx: Int): ByteArray? {
        val entry = frameOffsets.getOrNull(frameIdx) ?: return null
        val (offset, size) = entry
        return try {
            val b = ByteArray(size)
            var readTotal = 0
            while (readTotal < size) {
                val n = dataSource.read(offset + readTotal, b, size - readTotal)
                if (n <= 0) break
                readTotal += n
            }
            b
        } catch (_: Exception) {
            null
        }
    }

    fun pause() {
        isPlaying = false
        playbackJob?.cancel()
        playbackJob = null
        onPlaybackStateChanged(false)
    }

    fun toggle(imageView: ImageView, scope: CoroutineScope) {
        if (isPlaying) pause() else startPlayback(imageView, scope)
    }

    fun seekTo(positionMs: Long, imageView: ImageView) {
        if (frameOffsets.isEmpty()) return
        val targetFrame = (positionMs / frameDurationMs).toInt().coerceIn(0, frameOffsets.size - 1)
        currentFrame = targetFrame
        frameCache.clear()
        renderSingleFrame(currentFrame, imageView)
    }

    fun renderSingleFrame(frameIdx: Int, imageView: ImageView) {
        if (frameIdx !in frameOffsets.indices) return
        prefetchScope.launch {
            val bytes = loadFrameBytes(frameIdx)
            if (bytes != null) {
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bmp != null) {
                    withContext(Dispatchers.Main) {
                        imageView.setImageBitmap(bmp)
                        onFrameRendered(currentPositionMs, totalDurationMs)
                    }
                }
            }
        }
    }

    fun release() {
        pause()
        indexJob?.cancel()
        prefetchScope.cancel()
        dataSource.close()
        frameCache.clear()
        frameOffsets.clear()
    }
}
