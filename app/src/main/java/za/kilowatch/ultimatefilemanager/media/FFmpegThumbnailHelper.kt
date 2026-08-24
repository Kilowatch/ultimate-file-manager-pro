package za.kilowatch.ultimatefilemanager.media

import android.graphics.Bitmap

object FFmpegThumbnailHelper {
    private const val TAG = "FFmpegThumbnailHelper"
    private var isLoaded = false

    /**
     * Global cap on concurrent native FFmpeg frame extractions.
     *
     * Every video thumbnail bind on local storage (FileAdapter, SafPickerActivity,
     * UfmDocumentsProvider) used to start its own unbounded FFmpeg decode on
     * Dispatchers.IO. On low-end devices (e.g. Google Chromecast) a fast fling
     * through a video-heavy folder spawned several simultaneous native decodes,
     * saturating all CPU cores and starving the main thread (its XML-drawable
     * asset load blocked >5000ms -> ANR). The semaphore bounds native decode
     * concurrency so the UI thread always keeps CPU headroom. The network
     * thumbnail path is additionally capped by its own semaphore in
     * NetworkThumbnailCacheManager, so this is the tighter global limit.
     */
    private val extractionSemaphore = java.util.concurrent.Semaphore(2)

    init {
        try {
            System.loadLibrary("ffmpeg_jni")
            isLoaded = true
            za.kilowatch.ultimatefilemanager.util.GoRoLog.i(TAG, "Successfully loaded ffmpeg_jni library")
        } catch (e: UnsatisfiedLinkError) {
            za.kilowatch.ultimatefilemanager.util.GoRoLog.e(TAG, "UnsatisfiedLinkError loading ffmpeg_jni", e)
        } catch (e: Exception) {
            za.kilowatch.ultimatefilemanager.util.GoRoLog.e(TAG, "Exception loading ffmpeg_jni", e)
        }
    }

    /**
     * Extracts a video frame from the video at [videoPath] at the given percentage [timePercent] (0-100)
     * and renders it into a Bitmap of the specified [width] and [height].
     *
     * Returns the Bitmap if successful, or null on failure.
     */
    fun extractVideoFrame(videoPath: String, timePercent: Int, width: Int = 256, height: Int = 256): Bitmap? {
        za.kilowatch.ultimatefilemanager.util.GoRoLog.i(TAG, "extractVideoFrame: path='$videoPath', pct=$timePercent, width=$width, height=$height")

        if (!isLoaded) {
            za.kilowatch.ultimatefilemanager.util.GoRoLog.e(TAG, "extractVideoFrame failed: ffmpeg_jni library not loaded")
            return null
        }

        // Acquire a global extraction permit before running native FFmpeg decode so that at
        // most [extractionSemaphore] decodes run at once. On low-end devices this keeps the
        // main thread responsive while video thumbnails are being generated.
        try {
            extractionSemaphore.acquire()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        }

        try {
            // Try the requested percentage
            var bitmap = tryExtractAtPercent(videoPath, timePercent, width, height)

            // If the frame is black, retry at different percentage options to avoid empty/black thumbnails
            if (bitmap != null && checkIsBlackBitmap(bitmap)) {
                za.kilowatch.ultimatefilemanager.util.GoRoLog.w(TAG, "extractVideoFrame: extracted frame at $timePercent% was completely black. Retrying at alternative times...")
                bitmap.recycle()
                bitmap = null

                // Try alternatives: 15%, 20%, 5%, 30%
                val alternatives = listOf(15, 20, 5, 30)
                for (altPercent in alternatives) {
                    if (altPercent == timePercent) continue
                    za.kilowatch.ultimatefilemanager.util.GoRoLog.i(TAG, "extractVideoFrame: Retrying frame extraction at $altPercent%")
                    val altBitmap = tryExtractAtPercent(videoPath, altPercent, width, height)
                    if (altBitmap != null) {
                        if (!checkIsBlackBitmap(altBitmap)) {
                            za.kilowatch.ultimatefilemanager.util.GoRoLog.i(TAG, "extractVideoFrame: Successfully extracted non-black frame at $altPercent%")
                            bitmap = altBitmap
                            break
                        } else {
                            za.kilowatch.ultimatefilemanager.util.GoRoLog.w(TAG, "extractVideoFrame: frame at $altPercent% was also black. Continuing retry...")
                            altBitmap.recycle()
                        }
                    }
                }

                // If all retries failed or returned black, fall back to the first successful extract (e.g. 10%)
                if (bitmap == null) {
                    za.kilowatch.ultimatefilemanager.util.GoRoLog.w(TAG, "extractVideoFrame: All alternative percent attempts returned black. Falling back to default time percent $timePercent%")
                    bitmap = tryExtractAtPercent(videoPath, timePercent, width, height)
                }
            }

            return bitmap
        } finally {
            extractionSemaphore.release()
        }
    }

    private fun tryExtractAtPercent(videoPath: String, timePercent: Int, width: Int, height: Int): Bitmap? {
        return try {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val success = extractFrame(videoPath, timePercent, bitmap)
            if (success) {
                bitmap
            } else {
                bitmap.recycle()
                null
            }
        } catch (e: Throwable) {
            za.kilowatch.ultimatefilemanager.util.GoRoLog.e(TAG, "tryExtractAtPercent error at $timePercent%", e)
            null
        }
    }

    private fun checkIsBlackBitmap(bitmap: Bitmap): Boolean {
        val width = bitmap.width
        val height = bitmap.height
        // Check center and four corners plus a few intermediate points
        val samplePoints = listOf(
            Pair(width / 2, height / 2),
            Pair(width / 4, height / 4),
            Pair(3 * width / 4, 3 * width / 4),
            Pair(width / 4, 3 * height / 4),
            Pair(3 * width / 4, height / 4)
        )
        for (p in samplePoints) {
            if (p.first in 0 until width && p.second in 0 until height) {
                val pixel = bitmap.getPixel(p.first, p.second)
                val r = android.graphics.Color.red(pixel)
                val g = android.graphics.Color.green(pixel)
                val b = android.graphics.Color.blue(pixel)
                if (r > 40 || g > 40 || b > 40) { // non-black pixel
                    return false
                }
            }
        }
        return true
    }

    private external fun extractFrame(videoPath: String, timePercent: Int, destBitmap: Bitmap): Boolean
}
