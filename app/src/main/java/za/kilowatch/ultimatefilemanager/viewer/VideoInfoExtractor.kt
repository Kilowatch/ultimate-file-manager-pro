package za.kilowatch.ultimatefilemanager.viewer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.text.format.Formatter
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import java.io.File
import java.util.Locale

/**
 * Utility to extract and structure technical video, audio, and subtitle metadata
 * from active playback state and media formats.
 */
object VideoInfoExtractor {

    fun extract(
        context: Context,
        player: ExoPlayer?,
        currentTrackInfo: QueueItem?,
        tracks: Tracks?,
        externalSubtitles: List<SubtitleTrackInfo> = emptyList(),
        fallbackPath: String? = null,
        fallbackFileSize: Long = 0L
    ): VideoTechnicalDetails {
        var path = currentTrackInfo?.path?.takeIf { it.isNotEmpty() }
            ?: fallbackPath?.takeIf { it.isNotEmpty() }
            ?: player?.currentMediaItem?.localConfiguration?.uri?.let { uri ->
                when (uri.scheme) {
                    "ufm" -> uri.toString().removePrefix("ufm://").replace("%20", " ")
                    "file" -> uri.path
                    else -> uri.toString()
                }
            } ?: ""

        if (path.startsWith("file://")) {
            path = Uri.parse(path).path ?: path.removePrefix("file://")
        }

        var resolvedFileName = currentTrackInfo?.title?.takeIf { it.isNotBlank() && it != "Media Title" }
        var resolvedSize = currentTrackInfo?.fileSize?.takeIf { it > 0L }
            ?: fallbackFileSize.takeIf { it > 0L }

        // Query content resolver if content:// URI
        if (path.startsWith("content://")) {
            try {
                val uri = Uri.parse(path)
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        if (resolvedFileName == null) {
                            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (nameIdx >= 0 && !cursor.isNull(nameIdx)) {
                                resolvedFileName = cursor.getString(nameIdx)
                            }
                        }
                        if (resolvedSize == null || resolvedSize <= 0L) {
                            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                            if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                                val sz = cursor.getLong(sizeIdx)
                                if (sz > 0L) resolvedSize = sz
                            }
                        }
                        val dataIdx = cursor.getColumnIndex("_data")
                        if (dataIdx >= 0 && !cursor.isNull(dataIdx)) {
                            val realPath = cursor.getString(dataIdx)
                            if (!realPath.isNullOrEmpty()) {
                                path = realPath
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // If local file, resolve size if not yet known
        if (resolvedSize == null || resolvedSize <= 0L) {
            if (path.isNotEmpty() && !path.contains("://")) {
                try {
                    val f = File(path)
                    if (f.exists() && f.isFile) {
                        resolvedSize = f.length()
                    }
                } catch (_: Exception) {}
            }
        }

        // If SAF path, resolve size
        if (resolvedSize == null || resolvedSize <= 0L) {
            try {
                if (za.kilowatch.ultimatefilemanager.storage.SafTreeManager.isSafPath(path) ||
                    za.kilowatch.ultimatefilemanager.storage.SafTreeManager.hasTreePermissionForPath(context, path)) {
                    val safSize = za.kilowatch.ultimatefilemanager.storage.SafTreeManager.getFileSize(context, path)
                    if (safSize > 0L) resolvedSize = safSize
                }
            } catch (_: Exception) {}
        }

        val fileName = resolvedFileName
            ?: (if (path.isNotEmpty()) {
                val lastSeg = path.substringAfterLast(File.separatorChar).substringAfterLast('/')
                if (lastSeg.contains("?")) lastSeg.substringBefore('?') else lastSeg
            } else "Media File").ifEmpty { "Media File" }

        val ext = path.substringAfterLast('.', "").lowercase()
        val durationMs = if (player != null && player.duration > 0) player.duration else (currentTrackInfo?.duration ?: 0L)
        val fileSize = resolvedSize ?: 0L

        // 1. Container / File
        val containerFormat = getContainerName(ext, player?.videoFormat?.containerMimeType)
        val fileSizeFormatted = if (fileSize > 0) {
            val formatted = Formatter.formatFileSize(context, fileSize)
            "$formatted (${String.format(Locale.US, "%,d", fileSize)} bytes)"
        } else {
            "N/A"
        }
        val durationFormatted = formatDuration(durationMs)
        val overallBitrateFormatted = if (fileSize > 0 && durationMs >= 1000) {
            val bps = (fileSize * 8.0) / (durationMs / 1000.0)
            if (bps >= 1_000_000) {
                String.format(Locale.US, "%.1f Mbps (Estimated)", bps / 1_000_000.0)
            } else {
                String.format(Locale.US, "%.0f kbps (Estimated)", bps / 1000.0)
            }
        } else null

        // 2. Video Stream
        var activeVideoFormat: Format? = player?.videoFormat
        if (activeVideoFormat == null && tracks != null) {
            for (group in tracks.groups) {
                if (group.type == C.TRACK_TYPE_VIDEO && group.length > 0) {
                    activeVideoFormat = group.getTrackFormat(0)
                    break
                }
            }
        }

        val hasVideo = activeVideoFormat != null && activeVideoFormat.width > 0 && activeVideoFormat.height > 0
        var videoCodec: String? = null
        var resolution: String? = null
        var resolutionLabel: String? = null
        var frameRate: String? = null
        var videoBitrate: String? = null
        var hdrFormat: String? = null
        var colorSpace: String? = null
        var colorRange: String? = null
        var bitDepth: String? = null
        var pixelFormat: String? = null

        if (hasVideo) {
            val vf = activeVideoFormat!!
            videoCodec = getVideoCodecName(vf.sampleMimeType, vf.codecs)
            resolution = "${vf.width} × ${vf.height}"
            resolutionLabel = getResolutionLabel(vf.width, vf.height)

            if (vf.frameRate > 0) {
                frameRate = if (vf.frameRate % 1.0f == 0.0f) {
                    String.format(Locale.US, "%.0f fps", vf.frameRate)
                } else {
                    String.format(Locale.US, "%.3f fps", vf.frameRate)
                }
            }

            if (vf.bitrate > 0) {
                videoBitrate = if (vf.bitrate >= 1_000_000) {
                    String.format(Locale.US, "%.1f Mbps", vf.bitrate / 1_000_000f)
                } else {
                    "${vf.bitrate / 1000} kbps"
                }
            }

            // HDR & Color metrology
            val colorInfo = vf.colorInfo
            if (colorInfo != null) {
                hdrFormat = when (colorInfo.colorTransfer) {
                    C.COLOR_TRANSFER_ST2084 -> "HDR10 (PQ / SMPTE ST 2084)"
                    C.COLOR_TRANSFER_HLG -> "HDR (HLG / ARIB STD-B67)"
                    C.COLOR_TRANSFER_SDR -> "SDR (Standard Dynamic Range)"
                    else -> if (isHdrCodec(vf.codecs)) "HDR" else "SDR"
                }

                colorSpace = when (colorInfo.colorSpace) {
                    C.COLOR_SPACE_BT2020 -> "BT.2020 (Wide Color Gamut)"
                    C.COLOR_SPACE_BT709 -> "BT.709 (Rec. 709)"
                    C.COLOR_SPACE_BT601 -> "BT.601"
                    else -> null
                }

                colorRange = when (colorInfo.colorRange) {
                    C.COLOR_RANGE_FULL -> "Full Range (0-255)"
                    C.COLOR_RANGE_LIMITED -> "Limited Range (16-235)"
                    else -> null
                }

                bitDepth = if (colorInfo.colorTransfer == C.COLOR_TRANSFER_ST2084 ||
                    colorInfo.colorTransfer == C.COLOR_TRANSFER_HLG ||
                    is10BitCodec(vf.codecs)) {
                    "10-bit"
                } else {
                    "8-bit"
                }

                pixelFormat = "YUV 4:2:0"
            } else {
                if (isHdrCodec(vf.codecs)) {
                    hdrFormat = "HDR"
                    bitDepth = "10-bit"
                }
            }
        }

        // 3. Audio Tracks
        val audioTracks = mutableListOf<AudioTrackTechnicalDetails>()
        tracks?.let { t ->
            var audioIndex = 1
            for (group in t.groups) {
                if (group.type != C.TRACK_TYPE_AUDIO) continue
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    val isSelected = group.isTrackSelected(i)
                    val langCode = format.language ?: "und"
                    val langDisplay = languageCodeToDisplay(langCode)
                    val trackTitle = format.label?.ifEmpty { null } ?: "$langDisplay (#$audioIndex)"
                    val codecName = getAudioCodecName(format.sampleMimeType, format.codecs)
                    val channelsName = getChannelLayout(format.channelCount)
                    val sampleRateName = if (format.sampleRate > 0) {
                        String.format(Locale.US, "%.1f kHz", format.sampleRate / 1000.0)
                    } else null
                    val bitrateName = if (format.bitrate > 0) {
                        "${format.bitrate / 1000} kbps"
                    } else null

                    audioTracks.add(
                        AudioTrackTechnicalDetails(
                            index = audioIndex,
                            title = trackTitle,
                            codec = codecName,
                            channels = channelsName,
                            sampleRate = sampleRateName,
                            bitrate = bitrateName,
                            language = langDisplay,
                            isSelected = isSelected
                        )
                    )
                    audioIndex++
                }
            }
        }

        // 4. Subtitle Tracks
        val subtitleTracks = mutableListOf<SubtitleTrackTechnicalDetails>()
        var subIndex = 1

        // Embedded
        tracks?.let { t ->
            for (group in t.groups) {
                if (group.type != C.TRACK_TYPE_TEXT) continue
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    val isSelected = group.isTrackSelected(i)
                    val langCode = format.language ?: "und"
                    val langDisplay = languageCodeToDisplay(langCode)
                    val title = format.label?.ifEmpty { null } ?: "$langDisplay (Embedded #$subIndex)"
                    val formatName = getSubtitleFormatName(format.sampleMimeType)

                    subtitleTracks.add(
                        SubtitleTrackTechnicalDetails(
                            index = subIndex,
                            title = title,
                            format = formatName,
                            language = langDisplay,
                            source = "Embedded",
                            isSelected = isSelected
                        )
                    )
                    subIndex++
                }
            }
        }

        // External companion subtitles
        for (extSub in externalSubtitles) {
            subtitleTracks.add(
                SubtitleTrackTechnicalDetails(
                    index = subIndex,
                    title = extSub.label,
                    format = extSub.sourceType ?: "SRT",
                    language = extSub.language,
                    source = "External",
                    isSelected = extSub.isSelected
                )
            )
            subIndex++
        }

        return VideoTechnicalDetails(
            fileName = fileName,
            filePath = path,
            containerFormat = containerFormat,
            fileSizeFormatted = fileSizeFormatted,
            durationFormatted = durationFormatted,
            overallBitrateFormatted = overallBitrateFormatted,
            hasVideo = hasVideo,
            videoCodec = videoCodec,
            resolution = resolution,
            resolutionLabel = resolutionLabel,
            frameRate = frameRate,
            videoBitrate = videoBitrate,
            hdrFormat = hdrFormat,
            colorSpace = colorSpace,
            colorRange = colorRange,
            bitDepth = bitDepth,
            pixelFormat = pixelFormat,
            audioTracks = audioTracks,
            subtitleTracks = subtitleTracks
        )
    }

    private fun getContainerName(ext: String, containerMime: String?): String {
        return when (ext) {
            "mkv" -> "MKV (Matroska)"
            "mp4", "m4v" -> "MP4 (MPEG-4 Part 14)"
            "webm" -> "WebM"
            "avi" -> "AVI (Audio Video Interleaved)"
            "mov" -> "MOV (QuickTime)"
            "ts", "m2ts" -> "TS (MPEG Transport Stream)"
            "flv" -> "FLV (Flash Video)"
            "wmv", "asf" -> "WMV (Windows Media Video)"
            "3gp", "3g2" -> "3GP (3GPP Media)"
            "ogv", "ogg" -> "OGV (Ogg Video)"
            "mp3" -> "MP3 (MPEG Audio Layer III)"
            "flac" -> "FLAC (Free Lossless Audio Codec)"
            "m4a", "aac" -> "M4A (MPEG-4 Audio)"
            "wav" -> "WAV (Waveform Audio)"
            "opus" -> "Opus Audio"
            else -> {
                if (!containerMime.isNullOrEmpty()) {
                    containerMime.substringAfterLast('/')
                        .replace("x-matroska", "Matroska (MKV)")
                        .replace("quicktime", "QuickTime (MOV)")
                        .uppercase()
                } else if (ext.isNotEmpty()) {
                    ext.uppercase()
                } else {
                    "N/A"
                }
            }
        }
    }

    fun getVideoCodecName(sampleMime: String?, codecs: String?): String {
        val baseName = when (sampleMime) {
            "video/hevc" -> "HEVC / H.265"
            "video/avc" -> "AVC / H.264"
            "video/av01" -> "AV1"
            "video/x-vnd.on2.vp9" -> "VP9"
            "video/x-vnd.on2.vp8" -> "VP8"
            "video/mp4v-es" -> "MPEG-4 Visual"
            "video/mpeg2" -> "MPEG-2 Video"
            "video/dolby-vision" -> "Dolby Vision"
            "video/mjpeg" -> "Motion JPEG (MJPEG)"
            "video/wvc1", "video/x-ms-wmv" -> "VC-1 / WMV"
            "video/3gpp" -> "H.263"
            else -> sampleMime?.substringAfterLast('/')?.uppercase() ?: "Unknown Codec"
        }

        return if (!codecs.isNullOrEmpty() && !codecs.equals(baseName, ignoreCase = true)) {
            "$baseName ($codecs)"
        } else {
            baseName
        }
    }

    fun getResolutionLabel(width: Int, height: Int): String {
        val w = width.coerceAtLeast(height)
        val h = width.coerceAtMost(height)

        val aspectDesc = getAspectRatioDescription(w, h)

        val qualityTag = when {
            w >= 3840 || h >= 2160 -> "4K UHD"
            w >= 2560 || h >= 1440 -> "2K QHD"
            w >= 1920 || h >= 1080 -> "1080p FHD"
            w >= 1280 || h >= 720 -> "720p HD"
            w >= 854 || h >= 480 -> "480p SD"
            else -> "${h}p"
        }

        return if (aspectDesc.isNotEmpty()) "$qualityTag · $aspectDesc" else qualityTag
    }

    private fun getAspectRatioDescription(w: Int, h: Int): String {
        if (w <= 0 || h <= 0) return ""
        val ratio = w.toDouble() / h.toDouble()
        return when {
            kotlin.math.abs(ratio - (16.0 / 9.0)) < 0.05 -> "16:9"
            kotlin.math.abs(ratio - (4.0 / 3.0)) < 0.05 -> "4:3"
            kotlin.math.abs(ratio - (16.0 / 10.0)) < 0.05 -> "16:10"
            kotlin.math.abs(ratio - 2.39) < 0.08 || kotlin.math.abs(ratio - 2.35) < 0.08 -> "2.39:1 Cinemascope"
            kotlin.math.abs(ratio - 1.0) < 0.05 -> "1:1"
            else -> String.format(Locale.US, "%.2f:1", ratio)
        }
    }

    fun getAudioCodecName(sampleMime: String?, codecs: String?): String {
        val name = when (sampleMime) {
            "audio/mp4a-latm" -> "AAC"
            "audio/ac3" -> "Dolby Digital (AC-3)"
            "audio/eac3" -> "Dolby Digital Plus (E-AC-3)"
            "audio/eac3-joc" -> "Dolby Digital Plus with Atmos"
            "audio/true-hd" -> "Dolby TrueHD"
            "audio/vnd.dts" -> "DTS Digital Surround"
            "audio/vnd.dts.hd" -> "DTS-HD Master Audio"
            "audio/vnd.dts.uhd" -> "DTS:X"
            "audio/flac" -> "FLAC Lossless"
            "audio/opus" -> "Opus"
            "audio/vorbis" -> "Ogg Vorbis"
            "audio/mpeg", "audio/mp3" -> "MP3"
            "audio/raw" -> "PCM Uncompressed"
            else -> sampleMime?.substringAfterLast('/')?.uppercase() ?: "Audio"
        }

        return if (!codecs.isNullOrEmpty() && !codecs.equals(name, ignoreCase = true)) {
            "$name ($codecs)"
        } else {
            name
        }
    }

    fun getChannelLayout(channelCount: Int): String {
        return when (channelCount) {
            1 -> "1.0 (Mono)"
            2 -> "2.0 (Stereo)"
            6 -> "5.1 Surround (6 ch)"
            8 -> "7.1 Surround (8 ch)"
            else -> if (channelCount > 0) "$channelCount channels" else "N/A"
        }
    }

    fun getSubtitleFormatName(sampleMime: String?): String {
        return when (sampleMime) {
            "text/vtt" -> "WebVTT"
            "application/x-subrip", "text/x-subrip" -> "SRT (SubRip)"
            "text/x-ssa", "text/x-ass" -> "ASS / SSA"
            "application/pgs" -> "PGS (Blu-ray)"
            "application/vobsub" -> "VobSub (DVD)"
            "application/ttml+xml" -> "TTML"
            else -> sampleMime?.substringAfterLast('/')?.uppercase() ?: "Text Subtitle"
        }
    }

    private fun isHdrCodec(codecs: String?): Boolean {
        if (codecs == null) return false
        val lower = codecs.lowercase()
        return lower.contains("hdr") || lower.contains("dovi") || lower.contains("dvhe") || lower.contains("dvh1")
    }

    private fun is10BitCodec(codecs: String?): Boolean {
        if (codecs == null) return false
        val lower = codecs.lowercase()
        return lower.contains("main10") || lower.contains("high10") || lower.contains(".10.") || lower.contains("10bit")
    }

    private fun formatDuration(ms: Long): String {
        if (ms <= 0) return "00:00"
        val totalSec = ms / 1000
        val s = totalSec % 60
        val m = (totalSec / 60) % 60
        val h = totalSec / 3600
        return if (h > 0) {
            String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%02d:%02d", m, s)
        }
    }
}
