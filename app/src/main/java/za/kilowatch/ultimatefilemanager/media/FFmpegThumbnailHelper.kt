package za.kilowatch.ultimatefilemanager.media

import android.graphics.Bitmap

object FFmpegThumbnailHelper {
    private const val TAG = "FFmpegThumbnailHelper"
    private var isLoaded = false

    init {
        try {
            System.loadLibrary("ffmpeg_jni")
            isLoaded = true
        } catch (_: UnsatisfiedLinkError) {
        } catch (_: Exception) {
        }
    }

    /**
     * Extracts a video frame from the video at [videoPath] at the given percentage [timePercent] (0-100)
     * and renders it into a Bitmap of the specified [width] and [height].
     *
     * Returns the Bitmap if successful, or null on failure.
     */
    fun extractVideoFrame(videoPath: String, timePercent: Int, width: Int = 256, height: Int = 256): Bitmap? {
        if (!isLoaded) {
            return null
        }
        return try {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val success = extractFrame(videoPath, timePercent, bitmap)
            if (success) {
                bitmap
            } else {
                bitmap.recycle()
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private external fun extractFrame(videoPath: String, timePercent: Int, destBitmap: Bitmap): Boolean
}
