package za.kilowatch.ultimatefilemanager.media

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.*
import java.io.ByteArrayOutputStream

/**
 * An [Extractor] for raw Motion JPEG (.mjpeg, .mjpg, .mjp) elementary streams.
 * Parses consecutive JPEG SOI (0xFF 0xD8) to EOI (0xFF 0xD9) image frames
 * and outputs them to ExoPlayer's video track with [MimeTypes.VIDEO_MJPEG].
 */
class MjpegExtractor(
    private val frameRateFps: Int = 30
) : Extractor {

    private val frameDurationUs = 1_000_000L / frameRateFps
    private var extractorOutput: ExtractorOutput? = null
    private var trackOutput: TrackOutput? = null
    private var tracksEnded = false
    private var currentTimeUs = 0L

    private var videoWidth = 0
    private var videoHeight = 0

    private val frameBuffer = ByteArrayOutputStream(128 * 1024)
    private val chunk = ByteArray(8192)
    private var inFrame = false
    private var prevByte: Int = -1

    override fun sniff(input: ExtractorInput): Boolean {
        val header = ByteArray(4)
        if (!input.peekFully(header, 0, 4, true)) return false
        // Must start with JPEG SOI marker 0xFF 0xD8 followed by another marker 0xFF
        val isJpeg = (header[0].toInt() and 0xFF == 0xFF) &&
                     (header[1].toInt() and 0xFF == 0xD8) &&
                     (header[2].toInt() and 0xFF == 0xFF)
        return isJpeg
    }

    override fun init(output: ExtractorOutput) {
        this.extractorOutput = output
        this.trackOutput = output.track(0, C.TRACK_TYPE_VIDEO)
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        val out = trackOutput ?: return Extractor.RESULT_END_OF_INPUT

        while (true) {
            val bytesToRead = minOf(chunk.size, 8192)
            val read = input.read(chunk, 0, bytesToRead)
            if (read == C.RESULT_END_OF_INPUT) {
                if (frameBuffer.size() > 0) {
                    emitFrame(out, frameBuffer.toByteArray())
                    frameBuffer.reset()
                }
                return Extractor.RESULT_END_OF_INPUT
            }

            for (i in 0 until read) {
                val b = chunk[i].toInt() and 0xFF

                if (!inFrame) {
                    if (prevByte == 0xFF && b == 0xD8) {
                        // Found Start of Image (SOI)
                        inFrame = true
                        frameBuffer.reset()
                        frameBuffer.write(0xFF)
                        frameBuffer.write(0xD8)
                    }
                } else {
                    frameBuffer.write(b)
                    if (prevByte == 0xFF && b == 0xD9) {
                        // Found End of Image (EOI)
                        val frameBytes = frameBuffer.toByteArray()
                        frameBuffer.reset()
                        inFrame = false
                        prevByte = -1

                        emitFrame(out, frameBytes)
                        return Extractor.RESULT_CONTINUE
                    }
                }
                prevByte = b
            }
        }
    }

    private fun emitFrame(out: TrackOutput, frameBytes: ByteArray) {
        if (!tracksEnded) {
            parseDimensions(frameBytes)
            val format = Format.Builder()
                .setId("video/mjpeg")
                .setSampleMimeType(MimeTypes.VIDEO_MJPEG)
                .setWidth(if (videoWidth > 0) videoWidth else 1280)
                .setHeight(if (videoHeight > 0) videoHeight else 720)
                .build()
            out.format(format)
            extractorOutput?.seekMap(SeekMap.Unseekable(C.TIME_UNSET))
            extractorOutput?.endTracks()
            tracksEnded = true
        }

        val parsable = ParsableByteArray(frameBytes)
        out.sampleData(parsable, frameBytes.size)
        out.sampleMetadata(
            currentTimeUs,
            C.BUFFER_FLAG_KEY_FRAME,
            frameBytes.size,
            0,
            null
        )
        currentTimeUs += frameDurationUs
    }

    private fun parseDimensions(data: ByteArray) {
        var i = 2
        while (i < data.size - 8) {
            if ((data[i].toInt() and 0xFF) == 0xFF) {
                val marker = data[i + 1].toInt() and 0xFF
                // SOF0 (0xC0), SOF1 (0xC1), SOF2 (0xC2)
                if (marker == 0xC0 || marker == 0xC1 || marker == 0xC2) {
                    videoHeight = ((data[i + 5].toInt() and 0xFF) shl 8) or (data[i + 6].toInt() and 0xFF)
                    videoWidth = ((data[i + 7].toInt() and 0xFF) shl 8) or (data[i + 8].toInt() and 0xFF)
                    return
                }
                val length = ((data[i + 2].toInt() and 0xFF) shl 8) or (data[i + 3].toInt() and 0xFF)
                i += 2 + length
            } else {
                i++
            }
        }
    }

    override fun seek(position: Long, timeUs: Long) {
        currentTimeUs = if (timeUs > 0) timeUs else 0L
        inFrame = false
        prevByte = -1
        frameBuffer.reset()
    }

    override fun release() {
        frameBuffer.reset()
    }
}

/**
 * Shared [ExtractorsFactory] that extends [DefaultExtractorsFactory] with custom
 * extractors such as [MjpegExtractor] for raw Motion JPEG streams.
 */
class UfmExtractorsFactory : ExtractorsFactory {
    private val defaultFactory = DefaultExtractorsFactory()

    override fun createExtractors(): Array<Extractor> {
        val defaults = defaultFactory.createExtractors()
        val list = ArrayList<Extractor>(defaults.size + 1)
        list.add(MjpegExtractor())
        list.addAll(defaults)
        return list.toTypedArray()
    }

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> {
        val defaults = defaultFactory.createExtractors(uri, responseHeaders)
        val list = ArrayList<Extractor>(defaults.size + 1)
        list.add(MjpegExtractor())
        list.addAll(defaults)
        return list.toTypedArray()
    }
}
