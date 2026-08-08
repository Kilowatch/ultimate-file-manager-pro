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
import com.radzivon.bartoshyk.avif.coder.HeifCoder
import com.radzivon.bartoshyk.avif.coder.PreferredColorConfig
import okio.BufferedSource
import za.kilowatch.ultimatefilemanager.util.GoRoLog

/**
 * Coil 3 [Decoder] for AVIF still images.
 *
 * Decode strategy by API level:
 * - **API 31+**: [android.graphics.ImageDecoder] — built-in, hardware-accelerated, zero extra native libs.
 * - **API 24–30**: [com.radzivon.bartoshyk.avif.coder.HeifCoder] (avif-coder by awxkee) — covers
 *   devices that the old `avif-android` (API 26+) could not handle (API 24–25, e.g. MiBox 4).
 * - **Below API 24**: Not supported — [Factory.create] returns `null` so Coil falls through to
 *   the placeholder/error drawable gracefully.
 *
 * Safe software decoding with [PreferredColorConfig.RGBA_8888] avoids hardware buffer failures
 * on Android TV devices (e.g. MiBox 4), and large images (>2048px) are downsampled with
 * [HeifCoder.decodeSampled] to prevent OOM errors on RAM-constrained hardware.
 */
class AvifDecoder(
    private val sourceResult: SourceFetchResult,
    private val options: Options,
) : Decoder {

    override suspend fun decode(): DecodeResult? {
        val bytes = try {
            sourceResult.source.source().readByteArray()
        } catch (t: Throwable) {
            GoRoLog.e("AvifDecoder", "Error reading bytes from source", t)
            return null
        }

        if (bytes.isEmpty()) return null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // API 31+: try system ImageDecoder first
            try {
                val source = ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))
                val bitmap = ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
                return DecodeResult(image = bitmap.asImage(), isSampled = false)
            } catch (t: Throwable) {
                GoRoLog.e("AvifDecoder", "ImageDecoder decode failed on API ${Build.VERSION.SDK_INT}, falling back to HeifCoder", t)
            }
        }

        // Fallback to avif-coder (awxkee) with RGBA_8888 software configuration (covers API 24+ and API 31+ ImageDecoder failures)
        return try {
            val coder = HeifCoder()
            val preferredConfig = PreferredColorConfig.RGBA_8888
            val size = try { coder.getSize(bytes) } catch (t: Throwable) { null }

            val bmp: Bitmap = if (size != null && (size.width > 2048 || size.height > 2048)) {
                // Downsample images exceeding 2048px to prevent OOM on TV boxes (e.g. MiBox 4)
                val scale = maxOf(size.width / 2048f, size.height / 2048f)
                val targetW = (size.width / scale).toInt().coerceAtLeast(1)
                val targetH = (size.height / scale).toInt().coerceAtLeast(1)
                coder.decodeSampled(bytes, targetW, targetH, preferredConfig)
            } else {
                coder.decode(bytes, preferredConfig)
            }
            DecodeResult(image = bmp.asImage(), isSampled = false)
        } catch (t: Throwable) {
            GoRoLog.e("AvifDecoder", "HeifCoder decode failed on API ${Build.VERSION.SDK_INT}", t)
            null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────

    class Factory : Decoder.Factory {
        override fun create(
            result: SourceFetchResult,
            options: Options,
            imageLoader: ImageLoader,
        ): Decoder? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return null
            return if (isAvif(result.source.source())) AvifDecoder(result, options) else null
        }

        /**
         * Safely detects AVIF by peeking up to 256 bytes of the stream without throwing EOFException.
         */
        private fun isAvif(source: BufferedSource): Boolean {
            return try {
                val peek = source.peek()
                val header = ByteArray(256)
                val readCount = peek.read(header)
                if (readCount < 12) return false
                val bytes = if (readCount == 256) header else header.copyOf(readCount)

                // Bytes 4–7 must be "ftyp"
                if (bytes[4] != 'f'.code.toByte() ||
                    bytes[5] != 't'.code.toByte() ||
                    bytes[6] != 'y'.code.toByte() ||
                    bytes[7] != 'p'.code.toByte()) return false

                val boxSize = ((bytes[0].toInt() and 0xFF) shl 24) or
                              ((bytes[1].toInt() and 0xFF) shl 16) or
                              ((bytes[2].toInt() and 0xFF) shl 8) or
                               (bytes[3].toInt() and 0xFF)
                val scanEnd = if (boxSize in 12..bytes.size) boxSize else bytes.size

                var offset = 8
                while (offset + 3 < scanEnd) {
                    if (isAvifBrand(bytes, offset)) return true
                    offset += 4
                }
                false
            } catch (t: Throwable) {
                false
            }
        }

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
