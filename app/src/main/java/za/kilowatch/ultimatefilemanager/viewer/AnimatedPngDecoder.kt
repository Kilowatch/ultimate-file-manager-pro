package za.kilowatch.ultimatefilemanager.viewer

import coil3.ImageLoader
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import com.github.penfeizhou.animation.apng.APNGDrawable
import com.github.penfeizhou.animation.loader.FileLoader
import com.github.penfeizhou.animation.loader.StreamLoader
import okio.BufferedSource
import coil3.asImage
import java.io.File

/**
 * A bit of a workaround decoder for APNG support in Coil 3.
 * APNG4Android (com.github.penfeizhou.android.animation:apng) is used for the actual rendering.
 */
class AnimatedPngDecoder(
    private val sourceResult: SourceFetchResult,
    private val options: Options
) : Decoder {

    override suspend fun decode(): DecodeResult? {
        // APNG4Android works best with Files or Assets for caching/looping.
        // Since UFM Viewer already caches remote files to local temp files,
        // we can try to get the file path from the source.
        
        val file = sourceResult.source.fileOrNull()
        if (file != null) {
            val drawable = APNGDrawable(FileLoader(file.toString()))
            return DecodeResult(
                image = drawable.asImage(),
                isSampled = false
            )
        }

        // Fallback: Use StreamLoader
        val loader = object : StreamLoader() {
            override fun getInputStream(): java.io.InputStream {
                return sourceResult.source.source().peek().inputStream()
            }
        }
        val drawable = APNGDrawable(loader)
        return DecodeResult(
            image = drawable.asImage(),
            isSampled = false
        )
    }

    class Factory : Decoder.Factory {
        override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? {
            if (isApng(result.source.source())) {
                return AnimatedPngDecoder(result, options)
            }
            return null
        }

        private fun isApng(source: BufferedSource): Boolean {
            // APNG Signature: 0x89 0x50 0x4E 0x47 0x0D 0x0A 0x1A 0x0A (PNG)
            // Plus an "acTL" chunk.
            val signature = source.peek().readByteString(8)
            if (signature.hex() != "89504e470d0a1a0a") return false
            
            // Search for "acTL" within the first few KB
            val buffer = source.peek().readByteArray(2048)
            val search = "acTL".toByteArray()
            for (i in 0 until (buffer.size - 4)) {
                if (buffer[i] == search[0] && buffer[i+1] == search[1] && 
                    buffer[i+2] == search[2] && buffer[i+3] == search[3]) {
                    return true
                }
            }
            return false
        }
    }
}
