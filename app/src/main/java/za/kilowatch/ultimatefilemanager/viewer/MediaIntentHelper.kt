package za.kilowatch.ultimatefilemanager.viewer

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import za.kilowatch.ultimatefilemanager.network.SubtitleIntentHelper
import za.kilowatch.ultimatefilemanager.util.MimeTypeHelper

/**
 * Helper for attaching media metadata and subtitle extras to external viewer
 * Intents (e.g. VLC, MX Player, Just Player, Kodi, Nova Video Player, MPV).
 *
 * Solves Issue #55: VLC and external players don't record playback history / progress
 * when opening files without metadata Intent extras.
 */
object MediaIntentHelper {

    // VLC for Android Intent Extra Keys
    const val VLC_EXTRA_ITEM_TITLE = "item_title"
    const val VLC_EXTRA_MEDIA_TITLE = "org.videolan.vlc.MediaTitle"

    /**
     * Attaches title, size, and metadata extras to [intent] so that external
     * players (VLC, MX Player, Just Player, Kodi, MPV, etc.) can record
     * playback history and resume progress accurately.
     */
    fun attachMediaExtras(
        intent: Intent,
        title: String,
        size: Long = -1L,
        mimeType: String? = null
    ) {
        if (title.isNotBlank()) {
            // VLC for Android:
            // "title" is mapped to VLC's PLAY_EXTRA_ITEM_TITLE fallback
            // "item_title" is VLC's internal VideoPlayerActivity.PLAY_EXTRA_ITEM_TITLE constant
            // "org.videolan.vlc.MediaTitle" is used by VLC media services
            intent.putExtra("title", title)
            intent.putExtra(VLC_EXTRA_ITEM_TITLE, title)
            intent.putExtra(VLC_EXTRA_MEDIA_TITLE, title)

            // Android Framework Standard
            intent.putExtra(Intent.EXTRA_TITLE, title)

            // MX Player / Kodi / Other players
            intent.putExtra("filename", title)
            intent.putExtra("displayName", title)
        }

        if (size > 0L) {
            intent.putExtra("size", size)
        }
    }

    /**
     * Normalizes [rawMime] for media playback. Ensures known video/audio extensions
     * do not fall back to generic octet-stream or wildcard types that break intent resolution.
     */
    fun normalizeMediaMimeType(extension: String, rawMime: String?): String {
        val ext = extension.lowercase().trimStart('.')
        val isVideo = FileViewerRouter.isVideo(ext)
        val isAudio = FileViewerRouter.isAudio(ext)

        if (rawMime.isNullOrBlank() || rawMime == "*/*" || rawMime == "application/octet-stream") {
            if (isVideo) return "video/*"
            if (isAudio) return "audio/*"
        }
        return rawMime ?: MimeTypeHelper.getOrFallback(ext)
    }

    /**
     * Finds local sibling subtitle files for [videoFile] matching its base name.
     */
    fun findLocalSubtitles(videoFile: File): List<File> {
        if (!FileViewerRouter.isVideo(videoFile.extension)) return emptyList()
        val parent = videoFile.parentFile ?: return emptyList()
        if (!parent.exists() || !parent.isDirectory) return emptyList()

        val videoBase = videoFile.nameWithoutExtension
        return parent.listFiles { f ->
            if (!f.isFile) return@listFiles false
            val ext = f.extension.lowercase()
            val base = f.nameWithoutExtension
            ext in SubtitleIntentHelper.SUBTITLE_EXTENSIONS && (
                base.equals(videoBase, ignoreCase = true) ||
                base.startsWith("$videoBase.", ignoreCase = true)
            )
        }?.sortedBy { it.name.lowercase() } ?: emptyList()
    }

    /**
     * Finds local sibling subtitle files for [videoFile] and attaches them to [intent].
     */
    fun attachLocalSubtitles(intent: Intent, context: Context, videoFile: File) {
        val siblings = findLocalSubtitles(videoFile)
        if (siblings.isEmpty()) return

        val uris = siblings.mapNotNull { file ->
            try {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            } catch (_: Exception) {
                null
            }
        }
        if (uris.isEmpty()) return

        val names = siblings.map { it.nameWithoutExtension }
        val fileNames = siblings.map { it.name }
        SubtitleIntentHelper.attachSubtitleExtras(intent, uris, names, fileNames)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
