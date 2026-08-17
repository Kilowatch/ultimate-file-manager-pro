package za.kilowatch.ultimatefilemanager.util

import android.webkit.MimeTypeMap

/**
 * Extension of [MimeTypeMap] that adds manual fallbacks for modern image formats
 * (AVIF, HEIC, HEIF) that are missing from [MimeTypeMap] on older Android API levels.
 *
 * Use [getOrFallback] instead of calling [MimeTypeMap.getMimeTypeFromExtension] directly
 * when the MIME type will be used in an [android.content.Intent] or file-sharing flow
 * where `null` / `*&#47;*` would degrade the user experience.
 */
object MimeTypeHelper {

    /**
     * Returns the MIME type for [ext] (case-insensitive, without leading dot),
     * falling back to a hard-coded value for formats not covered by [MimeTypeMap]
     * on older API levels, or `"application/octet-stream"` as a last resort.
     */
    fun getOrFallback(ext: String): String {
        val lower = ext.lowercase()
        val mime = try {
            MimeTypeMap.getSingleton()?.getMimeTypeFromExtension(lower)
        } catch (_: Throwable) {
            null
        }
        return mime ?: fallback(lower)
    }

    private fun fallback(ext: String): String = when (ext) {
        // Images & Modern Still Formats
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

        // Camera RAW Formats
        "cr2", "cr3", "crw" -> "image/x-canon-cr2"
        "nef", "nrw"        -> "image/x-nikon-nef"
        "arw", "srf", "sr2" -> "image/x-sony-arw"
        "raf"               -> "image/x-fuji-raf"
        "rw2"               -> "image/x-panasonic-rw2"
        "orf"               -> "image/x-olympus-orf"
        "pef", "ptx"        -> "image/x-pentax-pef"
        "dng"               -> "image/x-adobe-dng"
        "srw"               -> "image/x-samsung-srw"
        "x3f"               -> "image/x-sigma-x3f"
        "erf"               -> "image/x-epson-erf"
        "kdc", "dcr", "k25" -> "image/x-kodak-dcr"
        "mrw"               -> "image/x-minolta-mrw"
        "mos"               -> "image/x-leaf-mos"
        "raw"               -> "image/x-raw"

        // Graphic Design, Bitmaps & Textures
        "psd", "psb"        -> "image/vnd.adobe.photoshop"
        "ai"                -> "application/illustrator"
        "xcf"               -> "image/x-xcf"
        "kra"               -> "application/x-krita"
        "clip"              -> "application/x-clip"
        "tga", "targa"      -> "image/x-tga"
        "dds"               -> "image/x-dds"
        "wbmp"              -> "image/vnd.wap.wbmp"
        "cur"               -> "image/x-win-bitmap"
        "ani"               -> "application/x-navi-animation"
        "pbm", "pgm", "ppm", "pnm" -> "image/x-portable-anymap"
        "pcx"               -> "image/x-pcx"
        "wmf"               -> "image/x-wmf"
        "emf"               -> "image/x-emf"

        // Videos
        "mjpeg", "mjpg", "mjp"      -> "video/x-motion-jpeg"
        "m4v"                       -> "video/x-m4v"
        "qt"                        -> "video/quicktime"
        "3g2", "3gp2"               -> "video/3gpp2"
        "m2ts", "mts", "m2t", "tp", "trp" -> "video/mp2t"
        "vob", "evo"                -> "video/x-ms-vob"
        "mpg", "mpeg", "mpe", "m1v", "m2v", "mpv" -> "video/mpeg"
        "f4v"                       -> "video/x-f4v"
        "ogv", "ogm"                -> "video/ogg"
        "rm", "rmvb"                -> "video/x-pn-realvideo"
        "asf", "wm"                 -> "video/x-ms-asf"
        "mxf"                       -> "application/mxf"
        "dv"                        -> "video/x-dv"
        "divx", "xvid"              -> "video/x-msvideo"
        "mk3d"                      -> "video/x-matroska"

        // Documents, Audio & Config
        "yaml", "yml"   -> "text/yaml"
        "m3u", "m3u8"   -> "audio/x-mpegurl"
        "epub"          -> "application/epub+zip"
        else            -> "application/octet-stream"
    }
}
