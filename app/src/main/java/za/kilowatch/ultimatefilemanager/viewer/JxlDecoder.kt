package za.kilowatch.ultimatefilemanager.viewer

import android.graphics.Bitmap
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import com.awxkee.jxlcoder.JxlCoder
import okio.BufferedSource
import za.kilowatch.ultimatefilemanager.util.GoRoLog

/**
 * Coil 3 [Decoder] for JPEG XL images (.jxl).
 *
 * JPEG XL has two container formats:
 * - **Bare codestream**: starts with `0xFF 0x0A`
 * - **ISO BMFF container**: starts with `0x00 0x00 0x00 0x0C 'J' 'X' 'L' ' ' 0x0D 0x0A 0x87 0x0A`
 *
 * Both are detected by [Factory.isJxl] via a peek on the stream.
 *
 * Decode strategy:
 * - [JxlCoder.decodeSampled] is called with a 2048×2048 cap to prevent OOM on RAM-constrained
 *   Android TV devices (e.g. MiBox 4, Fire TV Stick 4K). The library scales proportionally so
 *   neither dimension exceeds the cap; images smaller than 2048px are returned at native size.
 * - [JxlCoder] supports API 21+ so no minimum SDK guard is needed.
 */
class JxlDecoder(
    private val sourceResult: SourceFetchResult,
    private val options: Options,
) : Decoder {

    override suspend fun decode(): DecodeResult? {
        val bytes = try {
            sourceResult.source.source().readByteArray()
        } catch (t: Throwable) {
            GoRoLog.e("JxlDecoder", "Error reading bytes from source", t)
            return null
        }

        if (bytes.isEmpty()) return null

        return try {
            // Use decodeSampled with a 2048-cap to prevent OOM on TV boxes (e.g. MiBox 4).
            // If the image is smaller than 2048, JxlCoder returns it at native size.
            val bmp: Bitmap = JxlCoder.decodeSampled(bytes, 2048, 2048)
            DecodeResult(image = bmp.asImage(), isSampled = false)
        } catch (t: Throwable) {
            GoRoLog.e("JxlDecoder", "JxlCoder decode failed", t)
            null
        }
    }

    // ──────────────────────────────────────────────────────────────────────────

    class Factory : Decoder.Factory {
        override fun create(
            result: SourceFetchResult,
            options: Options,
            imageLoader: ImageLoader,
        ): Decoder? {
            return if (isJxl(result.source.source())) JxlDecoder(result, options) else null
        }

        /**
         * Detects JPEG XL by peeking the first 12 bytes of the stream.
         *
         * Bare codestream signature:  `0xFF 0x0A`
         * ISO BMFF box signature:     `0x00 0x00 0x00 0x0C 'J' 'X' 'L' ' ' 0x0D 0x0A 0x87 0x0A`
         */
        private fun isJxl(source: BufferedSource): Boolean {
            return try {
                val peek = source.peek()
                val header = ByteArray(12)
                val readCount = peek.read(header)
                if (readCount < 2) return false

                // Bare codestream: 0xFF 0x0A
                if (header[0] == 0xFF.toByte() && header[1] == 0x0A.toByte()) return true

                // ISO BMFF JXL container: needs at least 12 bytes
                if (readCount < 12) return false
                header[0]  == 0x00.toByte() &&
                header[1]  == 0x00.toByte() &&
                header[2]  == 0x00.toByte() &&
                header[3]  == 0x0C.toByte() &&
                header[4]  == 'J'.code.toByte() &&
                header[5]  == 'X'.code.toByte() &&
                header[6]  == 'L'.code.toByte() &&
                header[7]  == ' '.code.toByte() &&
                header[8]  == 0x0D.toByte() &&
                header[9]  == 0x0A.toByte() &&
                header[10] == 0x87.toByte() &&
                header[11] == 0x0A.toByte()
            } catch (t: Throwable) {
                false
            }
        }
    }
}
