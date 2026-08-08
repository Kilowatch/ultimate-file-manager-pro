package za.kilowatch.ultimatefilemanager.util

import android.content.Context
import androidx.annotation.DrawableRes
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.settings.IconCustomizationManager
import java.io.File

/**
 * Single source of truth for mapping file extensions / MIME types to drawable icons.
 * All adapters and viewers call this instead of hardcoding R.drawable.ic_file.
 */
object FileTypeIconProvider {

    /**
     * Returns the appropriate icon drawable for the given [File].
     * Falls back to [R.drawable.ic_file] for unknown types.
     */
    @DrawableRes
    fun iconForFile(file: File): Int = iconForExtension(file.extension)

    /**
     * Returns the appropriate icon drawable for the given file extension (case-insensitive).
     * Falls back to [R.drawable.ic_file] for unknown extensions.
     */
    @DrawableRes
    fun iconForExtension(ext: String): Int = when (ext.lowercase().trimStart('.')) {

        // ── Images ────────────────────────────────────────────────────────────
        "jpg", "jpeg", "png", "gif", "bmp", "webp",
        "heic", "heif", "svg", "tiff", "tif", "ico", "avif", "raw",
        "cr2", "nef", "arw", "dng" ->
            R.drawable.ic_file_image

        // ── Video ─────────────────────────────────────────────────────────────
        "mp4", "mkv", "avi", "mov", "wmv", "flv",
        "webm", "3gp", "m4v", "ts", "m2ts", "vob",
        "mpg", "mpeg", "rmvb", "asf", "divx", "xvid" ->
            R.drawable.ic_file_video

        // ── Audio ─────────────────────────────────────────────────────────────
        "mp3", "wav", "flac", "aac", "ogg", "m4a",
        "opus", "wma", "mid", "midi", "aiff", "aif",
        "ape", "alac", "mka", "ra", "amr", "ac3" ->
            R.drawable.ic_file_audio

        // ── PDF ───────────────────────────────────────────────────────────────
        "pdf" -> R.drawable.ic_file_pdf

        // ── Word-processor docs ───────────────────────────────────────────────
        "doc", "docx", "odt", "rtf", "pages", "wpd" ->
            R.drawable.ic_file_word

        // ── Spreadsheets ──────────────────────────────────────────────────────
        "xls", "xlsx", "ods", "csv", "numbers", "tsv" ->
            R.drawable.ic_file_spreadsheet

        // ── Presentations ─────────────────────────────────────────────────────
        "ppt", "pptx", "odp", "key" ->
            R.drawable.ic_file_presentation

        // ── APK (Android packages — distinct robot icon) ──────────────────────
        "apk", "xapk", "apks" ->
            R.drawable.ic_file_apk

        // ── Archives / Compressed ─────────────────────────────────────────────
        "zip", "rar", "7z", "tar", "gz", "bz2",
        "xz", "zst", "lz4", "tgz", "tbz2", "jar",
        "aar", "war", "ear", "cab", "lzma", "z",
        "lzip", "arj", "ace" ->
            R.drawable.ic_file_archive

        // ── Source code ───────────────────────────────────────────────────────
        "kt", "kts", "java", "py", "js", "ts",
        "c", "cpp", "cxx", "cc", "h", "hpp",
        "cs", "go", "rs", "php", "rb", "sh",
        "bash", "zsh", "swift", "dart", "lua",
        "pl", "pm", "r", "m", "vb", "asm",
        "groovy", "scala", "clj", "elm", "ex",
        "exs", "hs", "ml", "sql", "gradle" ->
            R.drawable.ic_file_code

        // ── XML / HTML / JSON / config structured text ────────────────────────
        "xml", "html", "htm", "xhtml",
        "json", "jsonc", "json5" ->
            R.drawable.ic_file_xml

        // ── Plain text / config / markup ──────────────────────────────────────
        "txt", "log", "md", "markdown", "nfo",
        "ini", "cfg", "conf", "yaml", "yml",
        "toml", "properties", "env", "editorconfig",
        "gitignore", "gitattributes", "m3u", "m3u8" ->
            R.drawable.ic_file_text

        // ── Fonts ─────────────────────────────────────────────────────────────
        "ttf", "otf", "woff", "woff2", "eot", "pfb", "pfm" ->
            R.drawable.ic_file_font

        // ── Ebooks ────────────────────────────────────────────────────────────
        "epub", "mobi", "azw", "azw3", "fb2", "djvu", "cbz", "cbr" ->
            R.drawable.ic_file_ebook

        // ── Disk images ───────────────────────────────────────────────────────
        "iso", "img", "dmg", "bin", "cue", "mdf", "nrg" ->
            R.drawable.ic_file_iso

        // ── Databases ─────────────────────────────────────────────────────────
        "db", "sqlite", "sqlite3", "mdb", "accdb", "realm" ->
            R.drawable.ic_file_database

        // ── Torrents ─────────────────────────────────────────────────────────
        "torrent", "magnet" ->
            R.drawable.ic_file_torrent

        // ── Subtitles / captions ──────────────────────────────────────────────
        "srt", "vtt", "ass", "ssa", "sub", "idx", "sup", "lrc" ->
            R.drawable.ic_file_subtitle

        // ── 3D models ─────────────────────────────────────────────────────────
        "obj", "fbx", "stl", "blend", "dae", "glb", "gltf", "3ds", "max" ->
            R.drawable.ic_file_3d

        // ── Backups / temporaries ─────────────────────────────────────────────
        "bak", "tmp", "temp", "swp", "old", "orig" ->
            R.drawable.ic_file_backup

        // ── Unknown / fallback ────────────────────────────────────────────────
        else -> R.drawable.ic_file
    }

    /**
     * Returns the appropriate icon drawable for the given MIME type string.
     * Useful for network adapters that have MIME but no file path.
     * Falls back to extension-based lookup using [iconForExtension] when possible.
     */
    @DrawableRes
    fun iconForMime(mime: String): Int {
        if (mime.isBlank()) return R.drawable.ic_file
        return when {
            mime.startsWith("image/")                                         -> R.drawable.ic_file_image
            mime.startsWith("video/")                                         -> R.drawable.ic_file_video
            mime.startsWith("audio/")                                         -> R.drawable.ic_file_audio
            mime == "application/pdf"                                         -> R.drawable.ic_file_pdf
            mime.contains("msword") || mime.contains("wordprocessingml")      -> R.drawable.ic_file_word
            mime.contains("spreadsheet") || mime.contains("excel")
                || mime.contains("ms-excel") || mime == "text/csv"            -> R.drawable.ic_file_spreadsheet
            mime.contains("presentation") || mime.contains("powerpoint")      -> R.drawable.ic_file_presentation
            mime == "application/vnd.android.package-archive"                 -> R.drawable.ic_file_apk
            mime.contains("zip") || mime.contains("archive")
                || mime.contains("compressed") || mime.contains("tar")
                || mime.contains("x-rar") || mime.contains("x-7z")           -> R.drawable.ic_file_archive
            mime.startsWith("text/html") || mime == "application/xml"
                || mime.startsWith("application/json")                        -> R.drawable.ic_file_xml
            mime.startsWith("text/")                                          -> R.drawable.ic_file_text
            mime.contains("font")                                             -> R.drawable.ic_file_font
            mime.contains("epub") || mime.contains("mobipocket")              -> R.drawable.ic_file_ebook
            mime.contains("iso") || mime.contains("disk-image")               -> R.drawable.ic_file_iso
            mime.contains("sqlite") || mime.contains("database")              -> R.drawable.ic_file_database
            mime == "application/x-bittorrent"                                -> R.drawable.ic_file_torrent
            else                                                               -> R.drawable.ic_file
        }
    }

    // ── Context-aware overloads (check IconCustomizationManager) ─────

    @DrawableRes
    fun iconForFile(context: Context, file: File): Int {
        val defaultRes = iconForFile(file)
        val iconId = fileTypeIdForFile(file)
        return IconCustomizationManager.getEffectiveIconRes(context, iconId, defaultRes)
    }

    @DrawableRes
    fun iconForExtension(context: Context, ext: String): Int {
        val defaultRes = iconForExtension(ext)
        val iconId = fileTypeIdForExtension(ext)
        return IconCustomizationManager.getEffectiveIconRes(context, iconId, defaultRes)
    }

    @DrawableRes
    fun iconForMime(context: Context, mime: String): Int {
        val defaultRes = iconForMime(mime)
        val extGuess = when {
            mime.startsWith("image/") -> "jpg"
            mime.startsWith("video/") -> "mp4"
            mime.startsWith("audio/") -> "mp3"
            mime == "application/pdf" -> "pdf"
            mime.contains("spreadsheet") || mime.contains("excel") || mime == "text/csv" -> "xlsx"
            mime.contains("presentation") || mime.contains("powerpoint") -> "pptx"
            mime.contains("zip") || mime.contains("archive") -> "zip"
            mime.startsWith("text/") -> "txt"
            else -> ""
        }
        val iconId = if (extGuess.isNotEmpty()) fileTypeIdForExtension(extGuess) else "file_generic"
        return IconCustomizationManager.getEffectiveIconRes(context, iconId, defaultRes)
    }

    // ── Canonical icon ID mapping ────────────────────────────────────

    fun fileTypeIdForFile(file: File): String = fileTypeIdForExtension(file.extension)

    fun fileTypeIdForExtension(ext: String): String = when (ext.lowercase().trimStart('.')) {
        "jpg", "jpeg", "png", "gif", "bmp", "webp",
        "heic", "heif", "svg", "tiff", "tif", "ico", "avif", "raw",
        "cr2", "nef", "arw", "dng" -> "file_image"

        "mp4", "mkv", "avi", "mov", "wmv", "flv",
        "webm", "3gp", "m4v", "ts", "m2ts", "vob",
        "mpg", "mpeg", "rmvb", "asf", "divx", "xvid" -> "file_video"

        "mp3", "wav", "flac", "aac", "ogg", "m4a",
        "opus", "wma", "mid", "midi", "aiff", "aif",
        "ape", "alac", "mka", "ra", "amr", "ac3" -> "file_audio"

        "pdf" -> "file_pdf"
        "doc", "docx", "odt", "rtf", "pages", "wpd" -> "file_word"
        "xls", "xlsx", "ods", "csv", "numbers", "tsv" -> "file_spreadsheet"
        "ppt", "pptx", "odp", "key" -> "file_presentation"
        "apk", "xapk", "apks" -> "file_apk"

        "zip", "rar", "7z", "tar", "gz", "bz2",
        "xz", "zst", "lz4", "tgz", "tbz2", "jar",
        "aar", "war", "ear", "cab", "lzma", "z",
        "lzip", "arj", "ace" -> "file_archive"

        "kt", "kts", "java", "py", "js", "ts",
        "c", "cpp", "cxx", "cc", "h", "hpp",
        "cs", "go", "rs", "php", "rb", "sh",
        "bash", "zsh", "swift", "dart", "lua",
        "pl", "pm", "r", "m", "vb", "asm",
        "groovy", "scala", "clj", "elm", "ex",
        "exs", "hs", "ml", "sql", "gradle" -> "file_code"

        "xml", "html", "htm", "xhtml",
        "json", "jsonc", "json5" -> "file_xml"

        "txt", "log", "md", "markdown", "nfo",
        "ini", "cfg", "conf", "yaml", "yml",
        "toml", "properties", "env", "editorconfig",
        "gitignore", "gitattributes", "m3u", "m3u8" -> "file_text"

        "ttf", "otf", "woff", "woff2", "eot", "pfb", "pfm" -> "file_font"
        "epub", "mobi", "azw", "azw3", "fb2", "djvu", "cbz", "cbr" -> "file_ebook"
        "iso", "img", "dmg", "bin", "cue", "mdf", "nrg" -> "file_iso"
        "db", "sqlite", "sqlite3", "mdb", "accdb", "realm" -> "file_database"
        "torrent", "magnet" -> "file_torrent"
        "srt", "vtt", "ass", "ssa", "sub", "idx", "sup", "lrc" -> "file_subtitle"
        "obj", "fbx", "stl", "blend", "dae", "glb", "gltf", "3ds", "max" -> "file_3d"
        "bak", "tmp", "temp", "swp", "old", "orig" -> "file_backup"
        else -> "file_generic"
    }
}
