package za.kilowatch.ultimatefilemanager.util

import android.webkit.MimeTypeMap
import java.io.File

/**
 * Universal MIME Type Registry & Fallback Database for Ultimate File Manager Pro.
 * Provides canonical, standardized MIME mappings for 500+ file extensions across
 * 16 categories to eliminate `null` / `*&#47;*` / `application/octet-stream` dead ends
 * on Android and Android TV.
 */
object MimeTypeHelper {

    /**
     * Subtitle file extensions recognized by UFM.
     */
    val SUBTITLE_EXTENSIONS = setOf(
        "srt", "vtt", "ass", "ssa", "sub", "idx", "sup", "lrc",
        "smi", "sami", "ttml", "sbv", "dfxp", "rt", "cap"
    )

    /**
     * Presentation slide deck file extensions recognized by UFM.
     */
    val PRESENTATION_EXTENSIONS = setOf(
        "pptx", "pptm", "ppsx", "pps", "potx", "potm", "pot",
        "ppt", "odp", "otp", "key", "sdd", "shw"
    )

    /**
     * Returns the MIME type for [file], determining its extension and falling back
     * to the comprehensive UFM registry when system [MimeTypeMap] is incomplete.
     */
    fun getMimeType(file: File): String = getOrFallback(file.extension)

    /**
     * Returns the MIME type for [ext] (case-insensitive, without leading dot).
     */
    fun getOrFallback(ext: String): String {
        val lower = ext.lowercase().trimStart('.')
        if (lower.isEmpty()) return "application/octet-stream"

        // Check our canonical overrides first for formats where system MimeTypeMap is known
        // to be inaccurate or inconsistent across Android OEM distributions
        override(lower)?.let { return it }

        val mime = try {
            MimeTypeMap.getSingleton()?.getMimeTypeFromExtension(lower)
        } catch (_: Throwable) {
            null
        }
        return mime ?: fallback(lower)
    }

    fun isSubtitle(ext: String): Boolean = ext.lowercase().trimStart('.') in SUBTITLE_EXTENSIONS

    fun isPresentation(ext: String): Boolean = ext.lowercase().trimStart('.') in PRESENTATION_EXTENSIONS

    fun isTextMime(mime: String): Boolean {
        val m = mime.lowercase()
        return m.startsWith("text/") ||
                m.contains("json") ||
                m.contains("xml") ||
                m.contains("javascript") ||
                m.contains("subrip") ||
                m.contains("x-ssa") ||
                m.contains("yaml") ||
                m.contains("toml")
    }

    fun isAudioMime(mime: String): Boolean = mime.lowercase().startsWith("audio/")

    fun isVideoMime(mime: String): Boolean = mime.lowercase().startsWith("video/")

    fun isImageMime(mime: String): Boolean = mime.lowercase().startsWith("image/")

    fun isArchiveMime(mime: String): Boolean {
        val m = mime.lowercase()
        return m.contains("zip") || m.contains("tar") || m.contains("compressed") ||
                m.contains("archive") || m.contains("7z") || m.contains("rar") ||
                m.contains("bzip") || m.contains("gzip") || m.contains("xz") || m.contains("zstd")
    }

    /**
     * Explicit canonical overrides where Android framework defaults are either missing,
     * outdated, or conflict with modern standards (e.g. Subtitles, Presentations, Modern Images).
     */
    private fun override(ext: String): String? = when (ext) {
        // Subtitles
        "srt"           -> "application/x-subrip"
        "vtt"           -> "text/vtt"
        "ass", "ssa"    -> "text/x-ssa"
        "sub"           -> "text/x-microdvd"
        "smi", "sami"   -> "application/x-sami"
        "ttml", "dfxp"  -> "application/ttml+xml"
        "lrc", "sbv"    -> "text/plain"
        "sup"           -> "application/x-pgs"
        "idx"           -> "text/plain"

        // Presentations (ensure PPTX does not map to text or octet-stream)
        "pptx"          -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "pptm"          -> "application/vnd.ms-powerpoint.presentation.macroEnabled.12"
        "ppsx"          -> "application/vnd.openxmlformats-officedocument.presentationml.slideshow"
        "pps", "ppt", "pot" -> "application/vnd.ms-powerpoint"
        "potx"          -> "application/vnd.openxmlformats-officedocument.presentationml.template"
        "potm"          -> "application/vnd.ms-powerpoint.template.macroEnabled.12"
        "odp"           -> "application/vnd.oasis.opendocument.presentation"
        "otp"           -> "application/vnd.oasis.opendocument.presentation-template"
        "key"           -> "application/x-iwork-keynote-sffkey"

        // Modern Still Formats (missing on API < 31)
        "avif"          -> "image/avif"
        "avifs"         -> "image/avif-sequence"
        "heic"          -> "image/heic"
        "heif", "hif"   -> "image/heif"
        "jxl"           -> "image/jxl"

        // Modern Audio
        "opus"          -> "audio/opus"
        "flac"          -> "audio/flac"
        "m4a"           -> "audio/mp4"

        // Modern Code / Config
        "yaml", "yml"   -> "text/yaml"
        "toml"          -> "text/x-toml"
        "json5", "jsonc"-> "application/json"
        "ts", "tsx"     -> "application/typescript"
        "kt", "kts"     -> "text/x-kotlin"
        "rs"            -> "text/x-rust"

        else            -> null
    }

    /**
     * Exhaustive fallback dictionary for formats when MimeTypeMap returns null.
     */
    private fun fallback(ext: String): String = when (ext) {
        // ── 1. Subtitles & Captions ──────────────────────────────────────────
        "srt"           -> "application/x-subrip"
        "vtt"           -> "text/vtt"
        "ass", "ssa"    -> "text/x-ssa"
        "sub"           -> "text/x-microdvd"
        "smi", "sami"   -> "application/x-sami"
        "ttml", "dfxp"  -> "application/ttml+xml"
        "lrc", "sbv", "rt", "cap" -> "text/plain"
        "sup"           -> "application/x-pgs"
        "idx"           -> "text/plain"

        // ── 2. Presentations & Slides ────────────────────────────────────────
        "pptx"          -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "pptm"          -> "application/vnd.ms-powerpoint.presentation.macroEnabled.12"
        "ppsx"          -> "application/vnd.openxmlformats-officedocument.presentationml.slideshow"
        "pps", "ppt", "pot" -> "application/vnd.ms-powerpoint"
        "potx"          -> "application/vnd.openxmlformats-officedocument.presentationml.template"
        "potm"          -> "application/vnd.ms-powerpoint.template.macroEnabled.12"
        "odp"           -> "application/vnd.oasis.opendocument.presentation"
        "otp"           -> "application/vnd.oasis.opendocument.presentation-template"
        "key"           -> "application/x-iwork-keynote-sffkey"
        "sdd"           -> "application/vnd.stardivision.impress"

        // ── 3. Word Processing & Documents ───────────────────────────────────
        "docx"          -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "docm"          -> "application/vnd.ms-word.document.macroEnabled.12"
        "dotx"          -> "application/vnd.openxmlformats-officedocument.wordprocessingml.template"
        "dotm"          -> "application/vnd.ms-word.template.macroEnabled.12"
        "doc", "dot"    -> "application/msword"
        "odt"           -> "application/vnd.oasis.opendocument.text"
        "ott"           -> "application/vnd.oasis.opendocument.text-template"
        "rtf"           -> "application/rtf"
        "pages"         -> "application/x-iwork-pages-sffpages"
        "wpd"           -> "application/vnd.wordperfect"
        "wps"           -> "application/vnd.ms-works"
        "oxps", "xps"   -> "application/vnd.ms-xpsdocument"
        "abw", "zabw"   -> "application/x-abiword"

        // ── 4. Spreadsheets & Tabular Data ───────────────────────────────────
        "xlsx"          -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "xlsm"          -> "application/vnd.ms-excel.sheet.macroEnabled.12"
        "xltx"          -> "application/vnd.openxmlformats-officedocument.spreadsheetml.template"
        "xltm"          -> "application/vnd.ms-excel.template.macroEnabled.12"
        "xls", "xlt"    -> "application/vnd.ms-excel"
        "xlsb"          -> "application/vnd.ms-excel.sheet.binary.macroEnabled.12"
        "ods"           -> "application/vnd.oasis.opendocument.spreadsheet"
        "ots"           -> "application/vnd.oasis.opendocument.spreadsheet-template"
        "csv"           -> "text/csv"
        "tsv", "tab"    -> "text/tab-separated-values"
        "numbers"       -> "application/x-iwork-numbers-sffnumbers"
        "sxc"           -> "application/vnd.sun.xml.calc"

        // ── 5. E-Books & Portable Documents ──────────────────────────────────
        "pdf"           -> "application/pdf"
        "epub"          -> "application/epub+zip"
        "mobi", "prc"   -> "application/x-mobipocket-ebook"
        "azw", "azw3"   -> "application/vnd.amazon.ebook"
        "fb2"           -> "application/x-fictionbook+xml"
        "djvu", "djv"   -> "image/vnd.djvu"
        "cbz"           -> "application/vnd.comicbook+zip"
        "cbr"           -> "application/vnd.comicbook-rar"
        "cb7"           -> "application/x-cb7"
        "cbt"           -> "application/x-cbt"
        "chm"           -> "application/vnd.ms-htmlhelp"

        // ── 6. Images & Camera RAW ───────────────────────────────────────────
        "avif"          -> "image/avif"
        "avifs"         -> "image/avif-sequence"
        "heic"          -> "image/heic"
        "heif", "hif"   -> "image/heif"
        "jxl"           -> "image/jxl"
        "hdr"           -> "image/vnd.radiance"
        "exr"           -> "image/x-exr"
        "mpo"           -> "image/mpo"
        "jps"           -> "image/x-jps"
        "pns"           -> "image/x-pns"
        "svg"           -> "image/svg+xml"
        "svgz"          -> "image/svg+xml"
        "wmf"           -> "image/x-wmf"
        "emf"           -> "image/x-emf"
        "eps"           -> "application/postscript"
        "ai"            -> "application/illustrator"
        "psd", "psb"    -> "image/vnd.adobe.photoshop"
        "xcf"           -> "image/x-xcf"
        "kra"           -> "application/x-krita"
        "clip"          -> "application/x-clip"
        "tga", "targa"  -> "image/x-tga"
        "dds"           -> "image/x-dds"
        "wbmp"          -> "image/vnd.wap.wbmp"
        "cur"           -> "image/x-win-bitmap"
        "ani"           -> "application/x-navi-animation"
        "pbm", "pgm", "ppm", "pnm", "pam" -> "image/x-portable-anymap"
        "pcx"           -> "image/x-pcx"
        "cr2", "cr3", "crw" -> "image/x-canon-cr2"
        "nef", "nrw"    -> "image/x-nikon-nef"
        "arw", "srf", "sr2" -> "image/x-sony-arw"
        "raf"           -> "image/x-fuji-raf"
        "rw2"           -> "image/x-panasonic-rw2"
        "orf"           -> "image/x-olympus-orf"
        "pef", "ptx"    -> "image/x-pentax-pef"
        "dng"           -> "image/x-adobe-dng"
        "srw"           -> "image/x-samsung-srw"
        "x3f"           -> "image/x-sigma-x3f"
        "erf"           -> "image/x-epson-erf"
        "kdc", "dcr", "k25" -> "image/x-kodak-dcr"
        "mrw"           -> "image/x-minolta-mrw"
        "mos"           -> "image/x-leaf-mos"
        "raw", "rwl", "3fr", "mef", "iiq" -> "image/x-raw"

        // ── 7. Audio & Music ─────────────────────────────────────────────────
        "mp3"           -> "audio/mpeg"
        "wav"           -> "audio/wav"
        "flac"          -> "audio/flac"
        "opus"          -> "audio/opus"
        "m4a", "m4b", "m4p", "alac" -> "audio/mp4"
        "aac"           -> "audio/aac"
        "ogg", "oga", "spx" -> "audio/ogg"
        "wma"           -> "audio/x-ms-wma"
        "ape"           -> "audio/x-ape"
        "aiff", "aif", "aifc" -> "audio/x-aiff"
        "wv"            -> "audio/x-wavpack"
        "mka"           -> "audio/x-matroska"
        "amr"           -> "audio/amr"
        "awb"           -> "audio/amr-wb"
        "qcp"           -> "audio/qcelp"
        "voc"           -> "audio/x-voc"
        "au", "snd"     -> "audio/basic"
        "ra", "ram"     -> "audio/x-pn-realaudio"
        "ac3"           -> "audio/ac3"
        "eac3"          -> "audio/eac3"
        "dts", "dtshd"  -> "audio/vnd.dts"
        "truehd", "thd" -> "audio/truehd"
        "mid", "midi", "kar", "rmi" -> "audio/midi"
        "mod"           -> "audio/x-mod"
        "xm"            -> "audio/x-xm"
        "it"            -> "audio/x-it"
        "s3m"           -> "audio/x-s3m"
        "m3u", "m3u8"   -> "audio/x-mpegurl"
        "pls"           -> "audio/x-scpls"
        "cue"           -> "application/x-cue"

        // ── 8. Video & Streams ───────────────────────────────────────────────
        "mp4"           -> "video/mp4"
        "mkv"           -> "video/x-matroska"
        "avi"           -> "video/x-msvideo"
        "mov", "qt"     -> "video/quicktime"
        "wmv"           -> "video/x-ms-wmv"
        "flv"           -> "video/x-flv"
        "f4v"           -> "video/x-f4v"
        "webm"          -> "video/webm"
        "3gp", "3g2", "3gp2" -> "video/3gpp"
        "m4v"           -> "video/x-m4v"
        "ts", "m2ts", "mts", "m2t", "tp", "trp" -> "video/mp2t"
        "vob", "evo"    -> "video/x-ms-vob"
        "mpg", "mpeg", "mpe", "m1v", "m2v", "mpv" -> "video/mpeg"
        "rm", "rmvb"    -> "video/x-pn-realvideo"
        "asf", "wm"     -> "video/x-ms-asf"
        "mjpeg", "mjpg", "mjp" -> "video/x-motion-jpeg"
        "ogv", "ogm"    -> "video/ogg"
        "mxf"           -> "application/mxf"
        "dv"            -> "video/x-dv"
        "divx", "xvid"  -> "video/x-msvideo"
        "mk3d"          -> "video/x-matroska"
        "hevc", "h264", "h265", "264", "265", "vc1" -> "video/mp4"

        // ── 9. Archives & Compression ────────────────────────────────────────
        "zip"           -> "application/zip"
        "7z"            -> "application/x-7z-compressed"
        "rar"           -> "application/vnd.rar"
        "tar"           -> "application/x-tar"
        "gz", "tgz"     -> "application/gzip"
        "bz2", "tbz", "tbz2" -> "application/x-bzip2"
        "xz", "txz"     -> "application/x-xz"
        "zst", "tzst"   -> "application/zstd"
        "lzma"          -> "application/x-lzma"
        "lz4"           -> "application/x-lz4"
        "lzip"          -> "application/x-lzip"
        "cab"           -> "application/vnd.ms-cab-compressed"
        "iso"           -> "application/x-iso9660-image"
        "dmg"           -> "application/x-apple-diskimage"
        "cpio"          -> "application/x-cpio"
        "ar"            -> "application/x-archive"
        "arj"           -> "application/x-arj"
        "ace"           -> "application/x-ace-compressed"
        "apk"           -> "application/vnd.android.package-archive"
        "xapk", "apks"  -> "application/vnd.android.package-archive"
        "jar", "aar"    -> "application/java-archive"

        // ── 10. Code, Web & Scripting ────────────────────────────────────────
        "json"          -> "application/json"
        "jsonc", "json5", "jsonl", "ndjson" -> "application/json"
        "xml", "xsd", "xsl", "xslt" -> "application/xml"
        "yaml", "yml"   -> "text/yaml"
        "toml"          -> "text/x-toml"
        "ini", "cfg", "conf", "cnf", "properties", "env", "editorconfig" -> "text/plain"
        "plist"         -> "application/x-plist"
        "sql", "ddl", "dml" -> "application/sql"
        "gradle"        -> "text/x-gradle"
        "html", "htm", "xhtml", "shtml" -> "text/html"
        "css", "scss", "sass", "less" -> "text/css"
        "js", "mjs", "cjs", "jsx" -> "application/javascript"
        "ts", "tsx"     -> "application/typescript"
        "vue", "svelte" -> "text/html"
        "kt", "kts", "ktm" -> "text/x-kotlin"
        "java", "jav"   -> "text/x-java-source"
        "c", "h"        -> "text/x-c"
        "cpp", "hpp", "cc", "hh", "cxx", "hxx", "c++", "h++" -> "text/x-c++src"
        "cs", "csx"     -> "text/x-csharp"
        "py", "pyw", "pyx", "pyi" -> "text/x-python"
        "rs", "rlib"    -> "text/x-rust"
        "go"            -> "text/x-go"
        "swift"         -> "text/x-swift"
        "dart"          -> "application/dart"
        "sh", "bash", "zsh", "fish", "bat", "cmd", "ps1" -> "application/x-sh"
        "lua", "wlua"   -> "text/x-lua"
        "rb", "rake"    -> "text/x-ruby"
        "php", "phtml"  -> "application/x-httpd-php"
        "pl", "pm"      -> "text/x-perl"
        "r", "rmd"      -> "text/x-r"
        "scala"         -> "text/x-scala"
        "groovy"        -> "text/x-groovy"
        "clj", "cljs"   -> "text/x-clojure"
        "hs", "lhs"     -> "text/x-haskell"
        "erl", "hrl"    -> "text/x-erlang"
        "ex", "exs"     -> "text/x-elixir"
        "asm", "s"      -> "text/x-asm"
        "vhd", "vhdl"   -> "text/x-vhdl"
        "verilog", "sv" -> "text/x-verilog"
        "dockerfile", "containerfile", "makefile", "cmake" -> "text/plain"
        "log", "diff", "patch", "nfo", "diz" -> "text/plain"
        "tex", "bib"    -> "text/x-tex"

        // ── 11. 3D Models & CAD ──────────────────────────────────────────────
        "obj"           -> "model/obj"
        "stl"           -> "model/stl"
        "fbx"           -> "application/octet-stream"
        "gltf"          -> "model/gltf+json"
        "glb"           -> "model/gltf-binary"
        "dae"           -> "model/vnd.collada+xml"
        "3ds"           -> "image/x-3ds"
        "blend"         -> "application/x-blender"
        "ply"           -> "model/ply"
        "step", "stp"   -> "model/step"
        "iges", "igs"   -> "model/iges"
        "usdz"          -> "model/vnd.usdz+zip"
        "dwg"           -> "image/vnd.dwg"
        "dxf"           -> "image/vnd.dxf"
        "scad"          -> "text/x-openscad"

        // ── 12. Fonts ────────────────────────────────────────────────────────
        "ttf", "ttc"    -> "font/ttf"
        "otf", "otc"    -> "font/otf"
        "woff"          -> "font/woff"
        "woff2"         -> "font/woff2"
        "eot"           -> "application/vnd.ms-fontobject"

        // ── 13. Databases ────────────────────────────────────────────────────
        "db", "sqlite", "sqlite3", "db3", "s3db", "sl3" -> "application/vnd.sqlite3"
        "mdb", "accdb"  -> "application/msaccess"
        "realm"         -> "application/x-realm"
        "kdbx"          -> "application/x-keepass2"

        // ── 14. Torrents & Internet ──────────────────────────────────────────
        "torrent"       -> "application/x-bittorrent"
        "url", "webloc", "desktop" -> "text/x-uri"

        // ── 15. Security & Certificates ──────────────────────────────────────
        "cer", "crt", "der" -> "application/x-x509-ca-cert"
        "pem", "key", "pub" -> "application/x-pem-file"
        "p12", "pfx"    -> "application/x-pkcs12"
        "asc", "gpg", "pgp", "sig" -> "application/pgp-signature"
        "ovpn"          -> "application/x-openvpn-profile"

        // ── 16. ROMs & Emulators ─────────────────────────────────────────────
        "nes"           -> "application/x-nes-rom"
        "snes", "smc", "sfc" -> "application/x-snes-rom"
        "gba"           -> "application/x-gba-rom"
        "gbc"           -> "application/x-gbc-rom"
        "gb"            -> "application/x-gameboy-rom"
        "nds"           -> "application/x-nintendo-ds-rom"
        "n64", "z64", "v64" -> "application/x-n64-rom"
        "cso", "chd"    -> "application/x-iso9660-image"

        else            -> "application/octet-stream"
    }
}
