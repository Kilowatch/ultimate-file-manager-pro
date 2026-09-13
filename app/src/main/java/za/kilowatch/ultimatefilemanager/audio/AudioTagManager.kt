package za.kilowatch.ultimatefilemanager.audio

import android.content.Context
import android.media.MediaScannerConnection
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.images.Artwork
import org.jaudiotagger.tag.images.ArtworkFactory
import org.jaudiotagger.tag.flac.FlacTag
import org.jaudiotagger.tag.reference.PictureTypes
import org.jaudiotagger.tag.vorbiscomment.VorbisCommentTag
import za.kilowatch.ultimatefilemanager.storage.SafTreeManager
import za.kilowatch.ultimatefilemanager.util.GoRoLog
import java.io.File
import java.io.InputStream
import java.util.Locale

/**
 * High-performance audio metadata read/write manager supporting MP3, FLAC, M4A, OGG, OPUS, WAV, and WMA.
 * Handles both direct filesystem modifications and SAF streaming fallback for removable SD cards.
 */
object AudioTagManager {

    /**
     * Reads all metadata tags and technical audio properties from an audio file.
     */
    fun readTags(context: Context, path: String): AudioTagData? = readTags(context, File(path))

    fun readTags(context: Context, file: File): AudioTagData? {
        val isSaf = SafTreeManager.isSafPath(file.absolutePath) ||
                    SafTreeManager.hasTreePermissionForPath(context, file.absolutePath)
        val canDirectRead = file.exists() && file.canRead() && !isSaf

        val fileToRead = if (canDirectRead) {
            file
        } else {
            // Stream SAF file to temporary cache file for jaudiotagger random-access reading
            val tempFile = File(context.cacheDir, "read_${System.currentTimeMillis()}_${file.name}")
            val inStream = if (isSaf) {
                SafTreeManager.openInputStream(context, file.absolutePath)
            } else {
                file.inputStream()
            } ?: return null

            try {
                inStream.use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                tempFile
            } catch (e: Exception) {
                tempFile.delete()
                return null
            }
        }

        return try {
            val audioFile = AudioFileIO.read(fileToRead)
            val tag: Tag? = audioFile.tag
            val header = audioFile.audioHeader

            val artworkBytes: ByteArray?
            val artworkMime: String?
            val artwork: Artwork? = tag?.firstArtwork
            if (artwork != null && artwork.binaryData != null) {
                artworkBytes = artwork.binaryData
                artworkMime = artwork.mimeType
            } else {
                artworkBytes = null
                artworkMime = null
            }

            AudioTagData(
                title = tag?.getFirst(FieldKey.TITLE) ?: "",
                artist = tag?.getFirst(FieldKey.ARTIST) ?: "",
                album = tag?.getFirst(FieldKey.ALBUM) ?: "",
                albumArtist = tag?.getFirst(FieldKey.ALBUM_ARTIST) ?: "",
                year = tag?.getFirst(FieldKey.YEAR) ?: "",
                genre = tag?.getFirst(FieldKey.GENRE) ?: "",
                trackNumber = tag?.getFirst(FieldKey.TRACK) ?: "",
                trackTotal = tag?.getFirst(FieldKey.TRACK_TOTAL) ?: "",
                discNumber = tag?.getFirst(FieldKey.DISC_NO) ?: "",
                discTotal = tag?.getFirst(FieldKey.DISC_TOTAL) ?: "",
                composer = tag?.getFirst(FieldKey.COMPOSER) ?: "",
                comment = tag?.getFirst(FieldKey.COMMENT) ?: "",
                lyrics = tag?.getFirst(FieldKey.LYRICS) ?: "",
                artworkBytes = artworkBytes,
                artworkMime = artworkMime,
                format = header?.format ?: file.extension.uppercase(Locale.ROOT),
                bitrate = if (header != null && header.bitRate != null) "${header.bitRate} kbps" else "",
                sampleRate = if (header != null && header.sampleRate != null) "${header.sampleRate} Hz" else "",
                channels = header?.channels ?: "",
                durationSeconds = header?.trackLength?.toLong() ?: 0L,
                fileSizeBytes = if (canDirectRead) file.length() else SafTreeManager.getFileSize(context, file.absolutePath)
            )
        } catch (e: Exception) {
            GoRoLog.w("AudioTagManager", "Failed to read tags from ${fileToRead.name}: ${e.message}")
            null
        } finally {
            if (!canDirectRead) {
                fileToRead.delete()
            }
        }
    }

    /**
     * Writes metadata tags to an audio file.
     * If direct writing fails or if the file is on a SAF tree, writes to a temp file and streams back.
     */
    fun writeTags(
        context: Context,
        targetFile: File,
        data: AudioTagData,
        updateArtwork: Boolean = false,
        removeArtwork: Boolean = false
    ): Boolean {
        val isSaf = SafTreeManager.isSafPath(targetFile.absolutePath) ||
                    SafTreeManager.hasTreePermissionForPath(context, targetFile.absolutePath)
        val canDirectWrite = targetFile.exists() && targetFile.canWrite() && !isSaf

        val fileToModify = if (canDirectWrite) {
            targetFile
        } else {
            val tempFile = File(context.cacheDir, "write_${System.currentTimeMillis()}_${targetFile.name}")
            val inStream = if (isSaf) {
                SafTreeManager.openInputStream(context, targetFile.absolutePath)
            } else {
                targetFile.inputStream()
            } ?: return false

            try {
                inStream.use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                tempFile
            } catch (e: Exception) {
                tempFile.delete()
                return false
            }
        }

        return try {
            val audioFile = AudioFileIO.read(fileToModify)
            val tag = audioFile.tagOrCreateAndSetDefault

            fun setOrDelete(key: FieldKey, value: String) {
                if (value.isNotBlank()) {
                    tag.setField(key, value.trim())
                } else {
                    tag.deleteField(key)
                }
            }

            setOrDelete(FieldKey.TITLE, data.title)
            setOrDelete(FieldKey.ARTIST, data.artist)
            setOrDelete(FieldKey.ALBUM, data.album)
            setOrDelete(FieldKey.ALBUM_ARTIST, data.albumArtist)
            setOrDelete(FieldKey.YEAR, data.year)
            setOrDelete(FieldKey.GENRE, data.genre)
            setOrDelete(FieldKey.TRACK, data.trackNumber)
            setOrDelete(FieldKey.TRACK_TOTAL, data.trackTotal)
            setOrDelete(FieldKey.DISC_NO, data.discNumber)
            setOrDelete(FieldKey.DISC_TOTAL, data.discTotal)
            setOrDelete(FieldKey.COMPOSER, data.composer)
            setOrDelete(FieldKey.COMMENT, data.comment)
            setOrDelete(FieldKey.LYRICS, data.lyrics)

            if (removeArtwork) {
                runCatching { tag.deleteArtworkField() }
            } else if (updateArtwork) {
                val artBytes = data.artworkBytes
                if (artBytes != null) {
                    runCatching { tag.deleteArtworkField() }
                    val (width, height, mime) = try {
                        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        android.graphics.BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size, opts)
                        Triple(
                            if (opts.outWidth > 0) opts.outWidth else 0,
                            if (opts.outHeight > 0) opts.outHeight else 0,
                            opts.outMimeType ?: (data.artworkMime ?: "image/jpeg")
                        )
                    } catch (_: Throwable) {
                        Triple(0, 0, data.artworkMime ?: "image/jpeg")
                    }

                    when (tag) {
                        is FlacTag -> {
                            val picField = tag.createArtworkField(
                                artBytes,
                                PictureTypes.DEFAULT_ID,
                                mime,
                                "",
                                width,
                                height,
                                24,
                                0
                            )
                            tag.setField(picField)
                        }
                        is VorbisCommentTag -> {
                            tag.setArtworkField(artBytes, mime)
                        }
                        else -> {
                            val artwork = ArtworkFactory.getNew()
                            artwork.binaryData = artBytes
                            artwork.mimeType = mime
                            if (width > 0) artwork.width = width
                            if (height > 0) artwork.height = height
                            tag.setField(artwork)
                        }
                    }
                }
            }

            audioFile.commit()

            if (!canDirectWrite) {
                val outStream = if (isSaf) {
                    SafTreeManager.openOutputStream(context, targetFile.absolutePath)
                } else {
                    targetFile.outputStream()
                } ?: run {
                    fileToModify.delete()
                    return false
                }

                outStream.use { output ->
                    fileToModify.inputStream().use { input ->
                        input.copyTo(output)
                    }
                }
                fileToModify.delete()
            }

            MediaScannerConnection.scanFile(context, arrayOf(targetFile.absolutePath), null, null)
            true
        } catch (e: Exception) {
            GoRoLog.e("AudioTagManager", "Failed to write tags for ${targetFile.name}: ${e.message}", e)
            if (!canDirectWrite) {
                fileToModify.delete()
            }
            false
        }
    }

    /**
     * Batch writes common tags across multiple audio files.
     */
    fun batchWriteCommonTags(
        context: Context,
        files: List<File>,
        commonData: AudioTagData,
        applyAlbum: Boolean,
        applyArtist: Boolean,
        applyAlbumArtist: Boolean,
        applyYear: Boolean,
        applyGenre: Boolean,
        autoNumber: Boolean,
        updateArtwork: Boolean,
        removeArtwork: Boolean,
        onProgress: (current: Int, total: Int) -> Unit
    ): Pair<Int, List<String>> {
        var successCount = 0
        val errors = mutableListOf<String>()

        for ((index, file) in files.withIndex()) {
            try {
                val currentTags = readTags(context, file) ?: AudioTagData()

                if (applyAlbum && commonData.album.isNotBlank()) {
                    currentTags.album = commonData.album
                }
                if (applyArtist && commonData.artist.isNotBlank()) {
                    currentTags.artist = commonData.artist
                }
                if (applyAlbumArtist && commonData.albumArtist.isNotBlank()) {
                    currentTags.albumArtist = commonData.albumArtist
                }
                if (applyYear && commonData.year.isNotBlank()) {
                    currentTags.year = commonData.year
                }
                if (applyGenre && commonData.genre.isNotBlank()) {
                    currentTags.genre = commonData.genre
                }
                if (autoNumber) {
                    currentTags.trackNumber = (index + 1).toString()
                    currentTags.trackTotal = files.size.toString()
                }
                if (updateArtwork && commonData.artworkBytes != null) {
                    currentTags.artworkBytes = commonData.artworkBytes
                    currentTags.artworkMime = commonData.artworkMime
                }

                val ok = writeTags(
                    context = context,
                    targetFile = file,
                    data = currentTags,
                    updateArtwork = updateArtwork,
                    removeArtwork = removeArtwork
                )

                if (ok) {
                    successCount++
                } else {
                    errors.add("${file.name}: Write failed")
                }
            } catch (e: Exception) {
                errors.add("${file.name}: ${e.message}")
            }
            onProgress(index + 1, files.size)
        }

        return successCount to errors
    }

    /**
     * Extracts embedded album art and saves it as an image file on disk.
     */
    fun extractArtwork(context: Context, sourceAudioFile: File, targetImageFile: File): Boolean {
        val tags = readTags(context, sourceAudioFile) ?: return false
        val bytes = tags.artworkBytes ?: return false
        return try {
            targetImageFile.outputStream().use { it.write(bytes) }
            MediaScannerConnection.scanFile(context, arrayOf(targetImageFile.absolutePath), null, null)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Renames an audio file according to a pattern based on its current tags.
     */
    fun renameFileFromTags(
        context: Context,
        file: File,
        pattern: String,
        tags: AudioTagData
    ): File? {
        val newFilename = FilenameTagParser.formatFilename(tags, pattern, file.extension)
        if (newFilename.equals(file.name, ignoreCase = true)) {
            return file
        }

        val isSaf = SafTreeManager.isSafPath(file.absolutePath) ||
                    SafTreeManager.hasTreePermissionForPath(context, file.absolutePath)

        return try {
            val destination = File(file.parentFile, newFilename)
            if (isSaf) {
                val ok = SafTreeManager.rename(context, file.absolutePath, newFilename)
                if (ok) {
                    MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath, destination.absolutePath), null, null)
                    destination
                } else null
            } else {
                val ok = file.renameTo(destination)
                if (ok) {
                    MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath, destination.absolutePath), null, null)
                    destination
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }
}
