package za.kilowatch.ultimatefilemanager.network

import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Central helper for external-subtitle support when opening network video files
 * in an external player (Vimu, MX Player, VLC, Kodi, etc.).
 *
 * Responsibilities:
 *  1. [findNetworkSubtitles] — scan the in-memory file list for files that match the
 *     video's base name and have a subtitle extension.
 *  2. [attachSubtitleExtras] — attach the subtitle URIs to an Intent using the extras
 *     that are understood by the most popular Android video players:
 *       • MX Player API  : `subs`, `subs.name`, `subs.filename`, `subs.autoload`
 *       • VLC            : `subtitles_location`
 *       • Kodi / others  : same MX Player keys
 */
object SubtitleIntentHelper {

    val SUBTITLE_EXTENSIONS = setOf("srt", "vtt", "ass", "ssa", "sub")

    // ── Subtitle File Discovery ───────────────────────────────────────────────

    /**
     * Return all [NetworkFile]s in [siblingFiles] that look like subtitles for [videoName].
     *
     * A file is considered a match when its extension is in [SUBTITLE_EXTENSIONS] AND its
     * base name either equals the video base name or starts with `videoBase.` (e.g.
     * `Movie.en.srt` for `Movie.mkv`).
     *
     * Results are sorted alphabetically so the ordering is deterministic.
     */
    fun findNetworkSubtitles(videoName: String, siblingFiles: List<NetworkFile>): List<NetworkFile> {
        val videoBase = videoName.substringBeforeLast('.')
        return siblingFiles
            .filter { f ->
                val ext  = f.name.substringAfterLast('.', "").lowercase()
                val base = f.name.substringBeforeLast('.')
                ext in SUBTITLE_EXTENSIONS && (
                    base.equals(videoBase, ignoreCase = true) ||
                    base.startsWith("$videoBase.", ignoreCase = true)
                )
            }
            .sortedBy { it.name.lowercase() }
    }

    // ── Intent Extras ────────────────────────────────────────────────────────

    /**
     * Attach subtitle extras to [intent] so that an external player can pick them up.
     *
     * Supports:
     *  - MX Player API  (`subs`, `subs.name`, `subs.filename`, `subs.autoload`)
     *  - VLC            (`subtitles_location`)
     *
     * @param subtitleUris  Ordered list of subtitle URIs (http:// or content://).
     * @param subtitleNames Display names shown in the player's subtitle picker (without extension).
     * @param subtitleFileNames File names including extension (helps players detect the format).
     */
    fun attachSubtitleExtras(
        intent: Intent,
        subtitleUris: List<Uri>,
        subtitleNames: List<String>,
        subtitleFileNames: List<String>
    ) {
        if (subtitleUris.isEmpty()) return

        // MX Player / Vimu / most Android players
        intent.putExtra("subs", subtitleUris.toTypedArray())
        intent.putExtra("subs.name", subtitleNames.toTypedArray())
        intent.putExtra("subs.filename", subtitleFileNames.toTypedArray())
        intent.putExtra("subs.autoload", true)

        // VLC for Android (uses the first subtitle only)
        intent.putExtra("subtitles_location", subtitleUris.first().toString())
    }

    /**
     * Convenience overload for a single subtitle.
     */
    fun attachSubtitleExtras(
        intent: Intent,
        subtitleUri: Uri,
        subtitleName: String,
        subtitleFileName: String
    ) = attachSubtitleExtras(
        intent,
        listOf(subtitleUri),
        listOf(subtitleName),
        listOf(subtitleFileName)
    )

    // ── Cached-file subtitle download helper ────────────────────────────────

    /**
     * Download all [subtitleFiles] from the share into [cacheDir] using the given [openInputStream]
     * function, then return a list of downloaded local Files for further processing.
     *
     * This is called from the "download-to-cache" path in NetworkBrowserActivity.
     * Errors on individual files are caught and skipped so one bad subtitle does not
     * block playback.
     *
     * @param openInputStream   Suspend function that opens an InputStream for a given remote path.
     */
    suspend fun downloadSubtitlesToCache(
        cacheDir: File,
        subtitleFiles: List<NetworkFile>,
        openInputStream: suspend (String) -> java.io.InputStream
    ): List<File> = withContext(Dispatchers.IO) {
        subtitleFiles.mapNotNull { netFile ->
            try {
                val safeName = netFile.name.replace(Regex("[/\\\\:*?\"<>|]"), "_")
                val cacheFile = File(cacheDir, "ufm_sub_$safeName")
                openInputStream(netFile.path).use { inp ->
                    cacheFile.outputStream().use { out -> inp.copyTo(out) }
                }
                cacheFile
            } catch (e: Exception) {
                android.util.Log.e("SubtitleHelper", "Failed to download subtitle ${netFile.name}: ${e.message}", e)
                null
            }
        }
    }

    /**
     * Build [FileProvider] content:// URIs for a list of local cache files, then call
     * [attachSubtitleExtras] on the given intent.
     *
     * Also grants FLAG_GRANT_READ_URI_PERMISSION on the intent so the receiving player
     * can access the content:// URIs.
     */
    fun attachCachedSubtitleExtras(
        intent: Intent,
        packageName: String,
        context: android.content.Context,
        cacheFiles: List<File>
    ) {
        if (cacheFiles.isEmpty()) return
        val uris = cacheFiles.mapNotNull { file ->
            try {
                FileProvider.getUriForFile(context, "$packageName.fileprovider", file)
            } catch (e: Exception) {
                android.util.Log.e("SubtitleHelper", "FileProvider failed for ${file.name}: ${e.message}")
                null
            }
        }
        if (uris.isEmpty()) return
        val names  = cacheFiles.map { it.nameWithoutExtension }
        val fnames = cacheFiles.map { it.name }
        attachSubtitleExtras(intent, uris, names, fnames)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
