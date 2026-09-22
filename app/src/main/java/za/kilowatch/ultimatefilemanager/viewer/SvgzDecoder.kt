package za.kilowatch.ultimatefilemanager.viewer

import coil3.ImageLoader
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.svg.SvgDecoder
import java.util.zip.GZIPInputStream

/**
 * Custom Coil decoder factory ensuring that compressed SVG images (.svgz)
 * are recognized and decoded seamlessly via AndroidSVG, even on Android ROMs
 * where MimeTypeMap does not map .svgz to "image/svg+xml".
 */
class SvgzDecoder(
    private val delegate: SvgDecoder
) : Decoder by delegate {

    class Factory : Decoder.Factory {
        override fun create(
            result: SourceFetchResult,
            options: Options,
            imageLoader: ImageLoader,
        ): Decoder? {
            return if (isSvgz(result.source)) {
                // SvgDecoder internally uses com.caverock.androidsvg.SVG.getFromInputStream,
                // which automatically checks for GZIP magic (0x1f, 0x8b) and unzips before parsing.
                SvgzDecoder(SvgDecoder(result.source, options))
            } else {
                null
            }
        }

        private fun isSvgz(source: ImageSource): Boolean {
            val file = source.fileOrNull()
            if (file != null && file.name.endsWith(".svgz", ignoreCase = true)) {
                return true
            }
            return try {
                val peek = source.source().peek()
                val header = ByteArray(2)
                val readCount = peek.read(header)
                if (readCount < 2) return false
                // Check for GZIP magic: 0x1F, 0x8B
                if (header[0] == 0x1F.toByte() && header[1] == 0x8B.toByte()) {
                    val testPeek = source.source().peek()
                    GZIPInputStream(testPeek.inputStream()).use { gz ->
                        val buf = ByteArray(128)
                        val len = gz.read(buf)
                        if (len > 0) {
                            val text = String(buf, 0, len, Charsets.UTF_8)
                            text.contains("<svg", ignoreCase = true) || text.contains("<?xml", ignoreCase = true)
                        } else false
                    }
                } else {
                    false
                }
            } catch (_: Throwable) {
                false
            }
        }
    }
}
