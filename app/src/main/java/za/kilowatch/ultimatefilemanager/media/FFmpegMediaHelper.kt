package za.kilowatch.ultimatefilemanager.media

import android.content.Context
import org.json.JSONObject
import za.kilowatch.ultimatefilemanager.util.GoRoLog
import java.io.File

/**
 * FFmpeg media utility leveraging the bundled native FFmpeg libraries
 * (libavcodec, libavformat, libavutil, libswscale) for deep codec/stream inspection,
 * subtitle extraction, and lossless audio demuxing.
 */
object FFmpegMediaHelper {
    private const val TAG = "FFmpegMediaHelper"
    private var isLoaded = false

    init {
        try {
            System.loadLibrary("ffmpeg_jni")
            isLoaded = true
            GoRoLog.i(TAG, "FFmpegMediaHelper successfully loaded ffmpeg_jni")
        } catch (e: UnsatisfiedLinkError) {
            GoRoLog.e(TAG, "UnsatisfiedLinkError loading ffmpeg_jni in FFmpegMediaHelper", e)
        } catch (e: Exception) {
            GoRoLog.e(TAG, "Exception loading ffmpeg_jni in FFmpegMediaHelper", e)
        }
    }

    fun isAvailable(): Boolean = isLoaded

    data class MediaStreamInfo(
        val index: Int,
        val type: String,
        val codec: String,
        val width: Int = 0,
        val height: Int = 0,
        val fps: Double = 0.0,
        val channels: Int = 0,
        val sampleRate: Int = 0,
        val bitrate: Long = 0,
        val lang: String = "",
        val title: String = ""
    ) : java.io.Serializable

    data class MediaInfo(
        val format: String,
        val durationSec: Long,
        val bitrate: Long,
        val streams: List<MediaStreamInfo>
    ) {
        val videoStreams: List<MediaStreamInfo> get() = streams.filter { it.type == "video" }
        val audioStreams: List<MediaStreamInfo> get() = streams.filter { it.type == "audio" }
        val subtitleStreams: List<MediaStreamInfo> get() = streams.filter { it.type == "subtitle" }
    }

    /**
     * Resolves an ISO 639-1 / 639-2 language code into a user-friendly display name.
     */
    fun getLanguageDisplayName(langCode: String): String {
        if (langCode.isBlank()) return ""
        val lower = langCode.lowercase().trim()
        val locale = java.util.Locale.forLanguageTag(lower)
        val display = locale.getDisplayLanguage(java.util.Locale.getDefault())
        if (display.isNotBlank() && !display.equals(lower, ignoreCase = true)) {
            return display.replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.getDefault()) else it.toString() }
        }
        return when (lower) {
            "eng", "en" -> "English"
            "spa", "es" -> "Spanish"
            "fre", "fra", "fr" -> "French"
            "ger", "deu", "de" -> "German"
            "ita", "it" -> "Italian"
            "por", "pt" -> "Portuguese"
            "rus", "ru" -> "Russian"
            "zho", "chi", "zh" -> "Chinese"
            "jpn", "ja" -> "Japanese"
            "kor", "ko" -> "Korean"
            "ara", "ar" -> "Arabic"
            "hin", "hi" -> "Hindi"
            "ind", "id", "in" -> "Indonesian"
            "tur", "tr" -> "Turkish"
            "ukr", "uk" -> "Ukrainian"
            "swe", "sv" -> "Swedish"
            "dut", "nld", "nl" -> "Dutch"
            "pol", "pl" -> "Polish"
            "dan", "da" -> "Danish"
            "fin", "fi" -> "Finnish"
            "nor", "no" -> "Norwegian"
            "und" -> "Undetermined"
            else -> lower.uppercase()
        }
    }

    external fun nativeGetMediaInfo(mediaPath: String): String?
    external fun nativeExtractSubtitle(mediaPath: String, streamIndex: Int, outputPath: String): Boolean
    external fun nativeExtractAudio(mediaPath: String, targetStreamIdx: Int, outputPath: String): Boolean
    external fun nativeTranscodeAudio(mediaPath: String, targetStreamIdx: Int, outputPath: String): Boolean
    external fun nativeConvertToMp4(mediaPath: String, outputPath: String): Boolean

    /**
     * Resolves the appropriate file extension for an audio codec.
     */
    fun getAudioExtensionForCodec(codec: String?): String {
        val lower = codec?.lowercase()?.trim() ?: ""
        return when {
            lower == "aac" || lower == "alac" -> "m4a"
            lower == "mp3" -> "mp3"
            lower == "opus" -> "opus"
            lower == "vorbis" -> "ogg"
            lower == "flac" -> "flac"
            lower == "ac3" -> "ac3"
            lower == "eac3" -> "eac3"
            lower == "dts" || lower == "dca" -> "dts"
            lower == "truehd" || lower == "thd" -> "thd"
            lower.startsWith("pcm") || lower == "wav" -> "wav"
            lower.startsWith("wma") -> "wma"
            lower.startsWith("amr") -> "amr"
            else -> "mka"
        }
    }

    /**
     * Checks if a codec is a cinematic or surround-sound codec (e.g. E-AC-3, AC-3, DTS, TrueHD)
     * which may lack native hardware decoding on standard Android phones.
     */
    fun isCinemaAudioCodec(codec: String?): Boolean {
        val lower = codec?.lowercase()?.trim() ?: ""
        return lower in setOf("eac3", "ac3", "ec3", "dts", "dca", "dtshd", "truehd", "thd", "mlp")
    }

    /**
     * Checks if a file extension represents a cinematic or surround-sound audio format
     * which may lack native hardware decoding on standard Android phones.
     */
    fun isCinemaAudioExtension(ext: String?): Boolean {
        val lower = ext?.lowercase()?.trim()?.removePrefix(".") ?: ""
        return lower in setOf("eac3", "ec3", "ac3", "dts", "dtshd", "truehd", "thd", "mlp")
    }

    /**
     * Checks if the Android device has an available MediaCodec decoder for a given MIME type.
     */
    fun hasDecoderForMimeType(mimeType: String): Boolean {
        return try {
            val list = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS)
            list.codecInfos.any { info ->
                !info.isEncoder && info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Checks if the device has a native decoder for the given cinema audio file extension.
     */
    fun hasDecoderForAudioExtension(ext: String): Boolean {
        val mime = when (ext.lowercase().removePrefix(".")) {
            "eac3", "ec3" -> "audio/eac3"
            "ac3" -> "audio/ac3"
            "dts", "dtshd" -> "audio/vnd.dts"
            "truehd", "thd" -> "audio/true-hd"
            else -> return true
        }
        return hasDecoderForMimeType(mime)
    }

    /**
     * Formats audio channels into a user-friendly label (e.g. "5.1 Surround", "Stereo", "7.1 Surround").
     */
    fun getChannelLayoutDisplayName(channels: Int): String {
        return when (channels) {
            1 -> "Mono (1 ch)"
            2 -> "Stereo (2 ch)"
            6 -> "5.1 Surround (6 ch)"
            8 -> "7.1 Surround (8 ch)"
            else -> if (channels > 0) "$channels ch" else ""
        }
    }

    /**
     * Inspects media file streams, codecs, bitrates, resolutions, and languages.
     */
    fun getMediaInfo(file: File): MediaInfo? {
        if (!isLoaded || !file.exists() || !file.canRead()) return null
        return try {
            val jsonStr = nativeGetMediaInfo(file.absolutePath) ?: return null
            val obj = org.json.JSONObject(jsonStr)
            val format = obj.optString("format", "unknown")
            val duration = obj.optLong("duration_sec", 0L)
            val bitrate = obj.optLong("bitrate", 0L)
            val streamsArr = obj.optJSONArray("streams") ?: return null

            val streamList = mutableListOf<MediaStreamInfo>()
            for (i in 0 until streamsArr.length()) {
                val s = streamsArr.getJSONObject(i)
                streamList.add(
                    MediaStreamInfo(
                        index = s.optInt("index", i),
                        type = s.optString("type", "other"),
                        codec = s.optString("codec", "unknown"),
                        width = s.optInt("width", 0),
                        height = s.optInt("height", 0),
                        fps = s.optDouble("fps", 0.0),
                        channels = s.optInt("channels", 0),
                        sampleRate = s.optInt("sample_rate", 0),
                        bitrate = s.optLong("bitrate", 0),
                        lang = s.optString("lang", ""),
                        title = s.optString("title", "")
                    )
                )
            }
            MediaInfo(format, duration, bitrate, streamList)
        } catch (e: Exception) {
            GoRoLog.e(TAG, "Failed to parse media info for ${file.name}", e)
            null
        }
    }

    /**
     * Extracts an embedded subtitle track to a standalone `.srt` file.
     * If [targetFile] is null, places the `.srt` next to the original video
     * with language code suffix if provided.
     */
    fun extractSubtitles(
        file: File,
        streamIndex: Int = -1,
        langCode: String? = null,
        targetFile: File? = null
    ): File? {
        if (!isLoaded || !file.exists() || !file.canRead()) return null
        return try {
            val destFile = targetFile ?: run {
                val parent = file.parentFile ?: return null
                val base = file.nameWithoutExtension
                val suffix = if (!langCode.isNullOrBlank()) "_${langCode.lowercase().trim()}" else ""
                var candidate = File(parent, "$base$suffix.srt")
                var counter = 1
                while (candidate.exists()) {
                    candidate = File(parent, "${base}${suffix}_$counter.srt")
                    counter++
                }
                candidate
            }
            val success = nativeExtractSubtitle(file.absolutePath, streamIndex, destFile.absolutePath)
            if (success && destFile.exists() && destFile.length() > 0) {
                destFile
            } else {
                destFile.delete()
                null
            }
        } catch (e: Exception) {
            GoRoLog.e(TAG, "Failed to extract subtitles from ${file.name}", e)
            null
        }
    }

    /**
     * Extracts all subtitle streams into individual standalone `.srt` files.
     */
    fun extractAllSubtitles(file: File, subtitleStreams: List<MediaStreamInfo>): List<File> {
        val results = mutableListOf<File>()
        for (stream in subtitleStreams) {
            val extracted = extractSubtitles(file, streamIndex = stream.index, langCode = stream.lang)
            if (extracted != null) {
                results.add(extracted)
            }
        }
        return results
    }

    /**
     * Extracts an audio stream into a standalone audio file.
     * If [universalAac] is true, transcodes to standard AAC (.m4a) for 100% universal playback
     * across all Android phones, TVs, music players, and car stereos.
     * Otherwise, performs a lossless direct stream copy (.m4a, .mp3, .ac3, .eac3, .dts, .flac, etc.).
     */
    fun extractAudio(
        file: File,
        streamIndex: Int = -1,
        langCode: String? = null,
        targetFile: File? = null,
        universalAac: Boolean = false
    ): File? {
        if (!isLoaded || !file.exists() || !file.canRead()) return null
        return try {
            val info = getMediaInfo(file)
            val audioStreams = info?.audioStreams ?: emptyList()
            if (audioStreams.isEmpty()) return null

            val chosenStream = if (streamIndex >= 0) {
                audioStreams.find { it.index == streamIndex } ?: audioStreams.first()
            } else {
                audioStreams.first()
            }

            val ext = if (universalAac) "m4a" else getAudioExtensionForCodec(chosenStream.codec)
            val langSuffix = if (!langCode.isNullOrBlank()) {
                "_${langCode.lowercase().trim()}"
            } else if (chosenStream.lang.isNotBlank() && audioStreams.size > 1) {
                "_${chosenStream.lang.lowercase().trim()}"
            } else ""

            val destFile = targetFile ?: run {
                val parent = file.parentFile ?: return null
                val base = file.nameWithoutExtension
                var candidate = File(parent, "$base$langSuffix.$ext")
                var counter = 1
                while (candidate.exists()) {
                    candidate = File(parent, "${base}${langSuffix}_$counter.$ext")
                    counter++
                }
                candidate
            }

            if (universalAac) {
                val success = nativeTranscodeAudio(file.absolutePath, chosenStream.index, destFile.absolutePath)
                if (success && destFile.exists() && destFile.length() > 0) {
                    return destFile
                }
                destFile.delete()
                return null
            }

            var success = nativeExtractAudio(file.absolutePath, chosenStream.index, destFile.absolutePath)
            if (success && destFile.exists() && destFile.length() > 0) {
                return destFile
            }

            // Fallback: If native container remuxing failed and ext was not .mka, try .mka (Matroska Audio)
            // Matroska container can losslessly encapsulate ANY audio codec without exception
            if (ext != "mka" && targetFile == null) {
                destFile.delete()
                val parent = file.parentFile ?: return null
                val base = file.nameWithoutExtension
                var mkaCandidate = File(parent, "$base$langSuffix.mka")
                var mkaCounter = 1
                while (mkaCandidate.exists()) {
                    mkaCandidate = File(parent, "${base}${langSuffix}_$mkaCounter.mka")
                    mkaCounter++
                }
                success = nativeExtractAudio(file.absolutePath, chosenStream.index, mkaCandidate.absolutePath)
                if (success && mkaCandidate.exists() && mkaCandidate.length() > 0) {
                    return mkaCandidate
                }
                mkaCandidate.delete()
            }

            destFile.delete()
            null
        } catch (e: Exception) {
            GoRoLog.e(TAG, "Failed to extract audio from ${file.name}", e)
            null
        }
    }

    /**
     * Extracts all audio streams into individual standalone files.
     */
    fun extractAllAudio(file: File, audioStreams: List<MediaStreamInfo>, universalAac: Boolean = false): List<File> {
        val results = mutableListOf<File>()
        for (stream in audioStreams) {
            val extracted = extractAudio(file, streamIndex = stream.index, langCode = stream.lang, universalAac = universalAac)
            if (extracted != null) {
                results.add(extracted)
            }
        }
        return results
    }

    /**
     * Transcodes an existing audio or video file's audio track to universal AAC (.m4a) format.
     * Guaranteed to be playable on all Android phones, TVs, external music players, and car stereos.
     */
    fun transcodeToM4a(file: File, streamIndex: Int = -1, targetFile: File? = null): File? {
        if (!isLoaded || !file.exists() || !file.canRead()) return null
        return try {
            val destFile = targetFile ?: run {
                val parent = file.parentFile ?: return null
                val base = file.nameWithoutExtension
                var candidate = File(parent, "$base.m4a")
                var counter = 1
                while (candidate.exists()) {
                    candidate = File(parent, "${base}_$counter.m4a")
                    counter++
                }
                candidate
            }

            val success = nativeTranscodeAudio(file.absolutePath, streamIndex, destFile.absolutePath)
            if (success && destFile.exists() && destFile.length() > 0) {
                destFile
            } else {
                destFile.delete()
                null
            }
        } catch (e: Exception) {
            GoRoLog.e(TAG, "Failed to transcode audio from ${file.name}", e)
            null
        }
    }

    /**
     * Converts or remuxes a video file to standard universal MP4 format (.mp4).
     * If [targetFile] is null, writes <filename>_converted.mp4 in the same parent directory.
     */
    fun convertToMp4(file: File, targetFile: File? = null): File? {
        if (!isLoaded || !file.exists() || !file.canRead()) return null
        return try {
            val destFile = targetFile ?: run {
                val parent = file.parentFile ?: return null
                val base = file.nameWithoutExtension
                var candidate = File(parent, "${base}_converted.mp4")
                var counter = 1
                while (candidate.exists()) {
                    candidate = File(parent, "${base}_converted_$counter.mp4")
                    counter++
                }
                candidate
            }

            val success = nativeConvertToMp4(file.absolutePath, destFile.absolutePath)
            if (success && destFile.exists() && destFile.length() > 0) {
                destFile
            } else {
                destFile.delete()
                null
            }
        } catch (e: Exception) {
            GoRoLog.e(TAG, "Failed to convert ${file.name} to MP4", e)
            null
        }
    }
}
