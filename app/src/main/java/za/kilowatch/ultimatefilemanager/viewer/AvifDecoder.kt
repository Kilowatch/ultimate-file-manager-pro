package za.kilowatch.ultimatefilemanager.viewer

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Build
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import okio.BufferedSource
import org.aomedia.avif.android.AvifDecoder as LibAvifDecoder

/**
 * Coil 3 [Decoder] for AVIF still images.
 *
 * Decode strategy by API level:
 * - **API 31+**: [android.graphics.ImageDecoder] — built-in, hardware-accelerated, zero extra native libs.
 * - **API 26–30**: [org.aomedia.avif.android.AvifDecoder] — JNI fallback from libavif.
 *
 * The [Factory] detects AVIF by sniffing the ISO-BMFF `ftyp` box (bytes 4–11 in the stream)
 * for any of the known AVIF major/compatible brands (`avif`, `avis`, `avio`).
 * This is extension-agnostic and works for in-memory streams (e.g. extracted ZIP entries).
 */
class AvifDecoder(
    private val sourceResult: SourceFetchResult,
    private val options: Options,
) : Decoder {

    override suspend fun decode(): DecodeResult? {
        val bytes = sourceResult.source.source().readByteArray()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // API 31+: use the system's native ImageDecoder — has built-in AVIF support.
            val source = ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))
            val bitmap = ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            return DecodeResult(image = bitmap.asImage(), isSampled = false)
        } else {
            // API 26–30: delegate to org.aomedia.avif:avif-android.
            // The library requires a ByteBuffer (not a raw ByteArray).
            val byteBuffer = java.nio.ByteBuffer.wrap(bytes)
            val info = LibAvifDecoder.Info()
            if (!LibAvifDecoder.getInfo(byteBuffer, bytes.size, info)) return null

            // Rewind so create() reads from the beginning.
            byteBuffer.rewind()
            val decoder = LibAvifDecoder.create(byteBuffer, bytes.size) ?: return null
            return try {
                val bmp = Bitmap.createBitmap(info.width, info.height, Bitmap.Config.ARGB_8888)
                // nextFrame returns void in this library version; a decode failure throws.
                decoder.nextFrame(bmp)
                DecodeResult(image = bmp.asImage(), isSampled = false)
            } catch (e: Exception) {
                null
            } finally {
                decoder.release()
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────

    class Factory : Decoder.Factory {
        override fun create(
            result: SourceFetchResult,
            options: Options,
            imageLoader: ImageLoader,
        ): Decoder? {
            return if (isAvif(result.source.source())) AvifDecoder(result, options) else null
        }

        /**
         * Detects AVIF by peeking at the ISO Base Media File Format (ISOBMFF) `ftyp` box.
         *
         * Structure of the first `ftyp` box:
         * ```
         * offset  size  field
         *  0       4    box size (big-endian uint32)
         *  4       4    box type ("ftyp")
         *  8       4    major brand (e.g. "avif", "avis", "avio")
         * 12       4    minor version
         * 16+      4*N  compatible brands
         * ```
         *
         * If `ftyp` is at byte 4 and any 4-byte brand starting at offset 8 equals
         * an AVIF brand, we return true.
         */
        private fun isAvif(source: BufferedSource): Boolean {
            val header = source.peek().readByteArray(64)
            if (header.size < 12) return false

            // Bytes 4–7 must be "ftyp"
            if (header[4] != 'f'.code.toByte() ||
                header[5] != 't'.code.toByte() ||
                header[6] != 'y'.code.toByte() ||
                header[7] != 'p'.code.toByte()) return false

            // Read the box size so we know how far to scan compatible brands
            val boxSize = ((header[0].toInt() and 0xFF) shl 24) or
                          ((header[1].toInt() and 0xFF) shl 16) or
                          ((header[2].toInt() and 0xFF) shl 8) or
                           (header[3].toInt() and 0xFF)
            val scanEnd = minOf(boxSize, 64).coerceAtLeast(12)

            // Scan major brand (offset 8) + compatible brands (offset 16 onwards, every 4 bytes)
            var offset = 8
            while (offset + 3 < scanEnd) {
                if (isAvifBrand(header, offset)) return true
                offset += 4
            }
            return false
        }

        /** Returns true if the 4 bytes at [off] in [buf] are "avif", "avis", or "avio". */
        private fun isAvifBrand(buf: ByteArray, off: Int): Boolean {
            if (off + 3 >= buf.size) return false
            return buf[off]     == 'a'.code.toByte() &&
                   buf[off + 1] == 'v'.code.toByte() &&
                   buf[off + 2] == 'i'.code.toByte() &&
                   (buf[off + 3] == 'f'.code.toByte() ||
                    buf[off + 3] == 's'.code.toByte() ||
                    buf[off + 3] == 'o'.code.toByte())
        }
    }
}
