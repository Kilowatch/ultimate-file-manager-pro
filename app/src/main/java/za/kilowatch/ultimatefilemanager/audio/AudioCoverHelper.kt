package za.kilowatch.ultimatefilemanager.audio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import za.kilowatch.ultimatefilemanager.storage.SafFile
import za.kilowatch.ultimatefilemanager.storage.SafTreeManager
import za.kilowatch.ultimatefilemanager.util.GoRoLog
import java.io.File

/**
 * Centralized audio embedded artwork cache and extraction helper.
 * Supports Local filesystem, SAF (MicroSD card, USB OTG), and fallback via AudioTagManager.
 */
object AudioCoverHelper {
    private const val TAG = "AudioCoverHelper"

    // In-memory LRU cache for decoded bitmaps (128 entries)
    private val artCache = LruCache<String, Bitmap>(128)

    // Negative cache for files without artwork (256 entries) to prevent repeated disk/SAF I/O
    private val noArtCache = LruCache<String, Boolean>(256)

    fun getCachedArt(path: String): Bitmap? {
        val b = artCache.get(path)
        if (b != null && b.isRecycled) {
            artCache.remove(path)
            return null
        }
        return b
    }

    fun putCachedArt(path: String, bitmap: Bitmap) {
        if (bitmap.isRecycled) return
        artCache.put(path, bitmap)
        noArtCache.remove(path)
    }

    fun isKnownNoArt(path: String): Boolean = noArtCache.get(path) == true

    fun markNoArt(path: String) {
        noArtCache.put(path, true)
        artCache.remove(path)
    }

    fun clearCacheForPath(path: String) {
        artCache.remove(path)
        noArtCache.remove(path)
    }

    fun clearCacheForFolder(folderPath: String) {
        val prefix = if (folderPath.endsWith(File.separator)) folderPath else folderPath + File.separator
        val keys = artCache.snapshot().keys
        for (key in keys) {
            if (key == folderPath || key.startsWith(prefix)) {
                artCache.remove(key)
            }
        }
        val noKeys = noArtCache.snapshot().keys
        for (key in noKeys) {
            if (key == folderPath || key.startsWith(prefix)) {
                noArtCache.remove(key)
            }
        }
    }

    /**
     * Drop cached cover art for every file at or beneath [volumePath], ahead of an eject.
     *
     * Mirrors [clearCacheForFolder] but sweeps an entire volume and `recycle()`s the bitmaps
     * rather than only evicting them, so the native memory is released before the unmount
     * instead of waiting for GC. [getCachedArt] already treats a recycled entry as a miss, so
     * a bitmap recycled here cannot be handed out afterwards.
     *
     * Matching uses a path-segment boundary, so `/storage/7DE2-1219` does not sweep the
     * sibling `/storage/7DE2-12190`.
     */
    fun clearVolumeCaches(volumePath: String) {
        val root = volumePath.trimEnd(File.separatorChar)
        if (root.isEmpty()) return
        val prefix = root + File.separator

        val keys = artCache.snapshot().keys
        for (key in keys) {
            if (key == root || key.startsWith(prefix)) {
                artCache.remove(key)?.recycle()
            }
        }
        val noKeys = noArtCache.snapshot().keys
        for (key in noKeys) {
            if (key == root || key.startsWith(prefix)) {
                noArtCache.remove(key)
            }
        }
    }

    /**
     * Extracts raw embedded picture bytes. Works across Local and SAF storages.
     * Uses FLAC fast-path for .flac files, MediaMetadataRetriever native fast-path first,
     * and falls back to AudioTagManager streaming.
     */
    fun extractArtworkBytes(context: Context, path: String): ByteArray? {
        // Fast-path for FLAC files
        if (path.endsWith(".flac", ignoreCase = true)) {
            try {
                val stream = if (SafTreeManager.isSafPath(path) || path.startsWith("content://")) {
                    SafTreeManager.openInputStream(context, path)
                } else {
                    val file = File(path)
                    if (file.exists() && file.canRead()) file.inputStream() else null
                }
                stream?.use { s ->
                    val art = extractFlacArtwork(s)
                    if (art != null && art.isNotEmpty()) return art
                }
            } catch (e: Exception) {
                GoRoLog.d(TAG, "extractArtworkBytes flac fast-path failed: ${e.message}")
            }
        }

        var retriever: MediaMetadataRetriever? = null
        try {
            val isSaf = SafTreeManager.isSafPath(path) ||
                        SafTreeManager.hasTreePermissionForPath(context, path) ||
                        path.startsWith("content://")

            retriever = MediaMetadataRetriever()
            if (isSaf) {
                val docUri = if (path.startsWith("content://")) {
                    Uri.parse(path)
                } else {
                    SafTreeManager.getDocumentUriForPath(context, path)
                }
                if (docUri != null) {
                    retriever.setDataSource(context, docUri)
                } else {
                    retriever.setDataSource(path)
                }
            } else {
                val file = File(path)
                if (!file.exists()) return null
                retriever.setDataSource(path)
            }

            val picture = retriever.embeddedPicture
            if (picture != null && picture.isNotEmpty()) {
                return picture
            }
        } catch (e: Exception) {
            GoRoLog.d(TAG, "extractArtworkBytes native retriever failed: ${e.message}")
        } finally {
            try { retriever?.release() } catch (_: Exception) {}
        }

        // Fallback via AudioTagManager (supports SAF streaming & specialized codecs like FLAC/OGG/OPUS)
        try {
            val tags = AudioTagManager.readTags(context, path)
            val artBytes = tags?.artworkBytes
            if (artBytes != null && artBytes.isNotEmpty()) {
                return artBytes
            }
        } catch (e: Exception) {
            GoRoLog.d(TAG, "extractArtworkBytes tagManager fallback failed: ${e.message}")
        }

        return null
    }

    /**
     * Efficiently extracts embedded front cover artwork from a FLAC audio stream
     * by parsing metadata blocks (specifically block type 6 PICTURE).
     * Stops immediately after reading metadata blocks without reading audio frames.
     */
    fun extractFlacArtwork(inputStream: java.io.InputStream): ByteArray? {
        try {
            val dis = java.io.DataInputStream(inputStream)
            val header = ByteArray(4)
            dis.readFully(header)

            // Optional ID3v2 header before FLAC signature
            if (header[0] == 'I'.code.toByte() && header[1] == 'D'.code.toByte() && header[2] == '3'.code.toByte()) {
                val id3Remaining = ByteArray(6)
                dis.readFully(id3Remaining)
                val id3Size = ((id3Remaining[2].toInt() and 0x7F) shl 21) or
                              ((id3Remaining[3].toInt() and 0x7F) shl 14) or
                              ((id3Remaining[4].toInt() and 0x7F) shl 7) or
                              (id3Remaining[5].toInt() and 0x7F)
                skipFully(dis, id3Size.toLong())
                dis.readFully(header)
            }

            // Verify "fLaC" signature
            if (header[0] != 0x66.toByte() || header[1] != 0x4C.toByte() ||
                header[2] != 0x61.toByte() || header[3] != 0x43.toByte()) {
                return null
            }

            var isLast = false
            while (!isLast) {
                val b0 = dis.readUnsignedByte()
                isLast = (b0 and 0x80) != 0
                val blockType = b0 and 0x7F
                val b1 = dis.readUnsignedByte()
                val b2 = dis.readUnsignedByte()
                val b3 = dis.readUnsignedByte()
                val length = (b1 shl 16) or (b2 shl 8) or b3

                if (blockType == 6) { // PICTURE block
                    if (length <= 0 || length > 30 * 1024 * 1024) return null

                    val picType = dis.readInt() // 3 = Front Cover
                    val mimeLen = dis.readInt()
                    if (mimeLen < 0 || mimeLen > length) return null
                    skipFully(dis, mimeLen.toLong())

                    val descLen = dis.readInt()
                    if (descLen < 0 || descLen > length) return null
                    skipFully(dis, descLen.toLong())

                    dis.readInt() // width
                    dis.readInt() // height
                    dis.readInt() // depth
                    dis.readInt() // colors

                    val dataLen = dis.readInt()
                    if (dataLen <= 0 || dataLen > 30 * 1024 * 1024) return null
                    val picData = ByteArray(dataLen)
                    dis.readFully(picData)
                    return picData
                } else {
                    skipFully(dis, length.toLong())
                }
            }
        } catch (_: Exception) {
            return null
        }
        return null
    }

    private fun skipFully(input: java.io.InputStream, bytesToSkip: Long) {
        var remaining = bytesToSkip
        val buf = ByteArray(minOf(remaining, 8192L).toInt())
        while (remaining > 0L) {
            val toRead = minOf(remaining, buf.size.toLong()).toInt()
            val n = input.read(buf, 0, toRead)
            if (n <= 0) break
            remaining -= n
        }
    }

    /**
     * Extracts and scales down audio cover art for a file path.
     */
    fun extractCover(context: Context, path: String, maxDimension: Int = 256): Bitmap? {
        artCache.get(path)?.let {
            if (!it.isRecycled) return it
            artCache.remove(path)
        }
        if (noArtCache.get(path) == true) return null

        val bytes = extractArtworkBytes(context, path)
        if (bytes == null || bytes.isEmpty()) {
            markNoArt(path)
            return null
        }

        val bitmap = decodeSampledBitmap(bytes, maxDimension)
        if (bitmap != null) {
            putCachedArt(path, bitmap)
            return bitmap
        } else {
            markNoArt(path)
            return null
        }
    }

    /**
     * Extracts and scales down audio cover art for a File object (including SafFile).
     */
    fun extractCover(context: Context, file: File, maxDimension: Int = 256): Bitmap? {
        val path = file.absolutePath
        artCache.get(path)?.let {
            if (!it.isRecycled) return it
            artCache.remove(path)
        }
        if (noArtCache.get(path) == true) return null

        // Fast-path for FLAC files
        if (file.name.endsWith(".flac", ignoreCase = true)) {
            try {
                val stream = if (file is SafFile) {
                    file.documentUri?.let { context.contentResolver.openInputStream(it) }
                } else if (SafTreeManager.isSafPath(path) || path.startsWith("content://")) {
                    SafTreeManager.openInputStream(context, path)
                } else {
                    if (file.exists() && file.canRead()) file.inputStream() else null
                }
                stream?.use { s ->
                    val art = extractFlacArtwork(s)
                    if (art != null && art.isNotEmpty()) {
                        val bmp = decodeSampledBitmap(art, maxDimension)
                        if (bmp != null) {
                            putCachedArt(path, bmp)
                            return bmp
                        }
                    }
                }
            } catch (e: Exception) {
                GoRoLog.d(TAG, "extractCover flac fast-path failed: ${e.message}")
            }
        }

        var retriever: MediaMetadataRetriever? = null
        try {
            val safDocUri = (file as? SafFile)?.documentUri
                ?: if (SafTreeManager.isSafPath(path) || SafTreeManager.hasTreePermissionForPath(context, path) || path.startsWith("content://")) {
                    if (path.startsWith("content://")) Uri.parse(path) else SafTreeManager.getDocumentUriForPath(context, path)
                } else null

            retriever = MediaMetadataRetriever()
            if (safDocUri != null) {
                retriever.setDataSource(context, safDocUri)
            } else {
                retriever.setDataSource(path)
            }
            val picture = retriever.embeddedPicture
            if (picture != null && picture.isNotEmpty()) {
                val bmp = decodeSampledBitmap(picture, maxDimension)
                if (bmp != null) {
                    putCachedArt(path, bmp)
                    return bmp
                }
            }
        } catch (e: Exception) {
            GoRoLog.d(TAG, "extractCover native retriever failed: ${e.message}")
        } finally {
            try { retriever?.release() } catch (_: Exception) {}
        }

        // Fallback to AudioTagManager (handles SAF and non-standard audio headers)
        try {
            val tags = AudioTagManager.readTags(context, file)
            val artBytes = tags?.artworkBytes
            if (artBytes != null && artBytes.isNotEmpty()) {
                val bmp = decodeSampledBitmap(artBytes, maxDimension)
                if (bmp != null) {
                    putCachedArt(path, bmp)
                    return bmp
                }
            }
        } catch (e: Exception) {
            GoRoLog.d(TAG, "extractCover AudioTagManager fallback failed: ${e.message}")
        }

        markNoArt(path)
        return null
    }

    /**
     * Decodes a byte array into a downsampled Bitmap to avoid memory exhaustion.
     */
    fun decodeSampledBitmap(bytes: ByteArray, maxDimension: Int): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)

            val srcW = options.outWidth
            val srcH = options.outHeight
            if (srcW <= 0 || srcH <= 0) return null

            var inSampleSize = 1
            if (srcW > maxDimension || srcH > maxDimension) {
                val halfW = srcW / 2
                val halfH = srcH / 2
                while ((halfW / inSampleSize) >= maxDimension && (halfH / inSampleSize) >= maxDimension) {
                    inSampleSize *= 2
                }
            }

            val decodeOptions = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
        } catch (e: Exception) {
            GoRoLog.w(TAG, "decodeSampledBitmap failed", e)
            null
        }
    }
}
