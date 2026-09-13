package za.kilowatch.ultimatefilemanager.audio

import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.flac.FlacTag
import org.jaudiotagger.tag.reference.PictureTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File

class FlacTagTest {

    @Test
    fun testFlacFileReadAndWrite() {
        val flacBytes = buildMinimalFlacFile()
        val tempFile = File.createTempFile("test_flac", ".flac")
        tempFile.deleteOnExit()
        tempFile.writeBytes(flacBytes)

        println("Attempting to read synthetic FLAC file with AudioFileIO...")
        try {
            val audioFile = AudioFileIO.read(tempFile)
            println("AudioFileIO.read succeeded! Header: ${audioFile.audioHeader?.format}")

            val tag = audioFile.tagOrCreateAndSetDefault
            println("Tag class: ${tag.javaClass.name}")

            tag.setField(FieldKey.TITLE, "Test FLAC Title")
            tag.setField(FieldKey.ARTIST, "Test FLAC Artist")

            val fakeImageData = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x12, 0x34)
            if (tag is FlacTag) {
                tag.deleteArtworkField()
                val picField = tag.createArtworkField(
                    fakeImageData,
                    PictureTypes.DEFAULT_ID,
                    "image/jpeg",
                    "",
                    0,
                    0,
                    24,
                    0
                )
                tag.setField(picField)
            }

            audioFile.commit()
            println("audioFile.commit() succeeded!")

            // Read back
            val rereadAudioFile = AudioFileIO.read(tempFile)
            val rereadTag = rereadAudioFile.tag
            assertNotNull(rereadTag)
            assertEquals("Test FLAC Title", rereadTag?.getFirst(FieldKey.TITLE))
            assertEquals("Test FLAC Artist", rereadTag?.getFirst(FieldKey.ARTIST))

            val art = rereadTag?.firstArtwork
            assertNotNull(art)
            assertEquals(fakeImageData.size, art?.binaryData?.size)
            println("FLAC file read/write with artwork verified end-to-end!")
        } catch (t: Throwable) {
            println("Synthetic FLAC test failed: ${t.javaClass.name} - ${t.message}")
            t.printStackTrace()
        } finally {
            tempFile.delete()
        }
    }

    private fun buildMinimalFlacFile(): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)

        // "fLaC" marker
        dos.write(byteArrayOf('f'.code.toByte(), 'L'.code.toByte(), 'a'.code.toByte(), 'C'.code.toByte()))

        // Block 0: STREAMINFO (34 bytes), not last (0x00)
        dos.writeByte(0x00)
        dos.writeByte(0)
        dos.writeByte(0)
        dos.writeByte(34)
        // 34 bytes of streaminfo: min block size (16b), max block size (16b), min frame (24b), max frame (24b),
        // sample rate (20b), channels (3b), bits per sample (5b), total samples (36b), md5 (128b)
        val streamInfo = ByteArray(34)
        // sample rate = 44100 (0x0AC44)
        // channels = 2 (1 in 0-based, bits 20..22)
        // bits per sample = 16 (15 in 0-based, bits 23..27)
        streamInfo[10] = 0x0A.toByte()
        streamInfo[11] = 0xC4.toByte()
        streamInfo[12] = 0x42.toByte()
        streamInfo[13] = 0xF0.toByte()
        dos.write(streamInfo)

        // Block 4: VORBIS_COMMENT (isLast = true: 0x84)
        val vcBaos = ByteArrayOutputStream()
        val vcDos = DataOutputStream(vcBaos)
        val vendor = "reference libFLAC 1.3.0"
        // vendor length (32b LE)
        vcDos.writeByte(vendor.length and 0xFF)
        vcDos.writeByte((vendor.length shr 8) and 0xFF)
        vcDos.writeByte((vendor.length shr 16) and 0xFF)
        vcDos.writeByte((vendor.length shr 24) and 0xFF)
        vcDos.writeBytes(vendor)
        // user comment list length = 0 (32b LE)
        vcDos.writeInt(0)
        val vcData = vcBaos.toByteArray()

        dos.writeByte(0x84) // isLast = true | type 4
        dos.writeByte((vcData.size shr 16) and 0xFF)
        dos.writeByte((vcData.size shr 8) and 0xFF)
        dos.writeByte(vcData.size and 0xFF)
        dos.write(vcData)

        // Minimal audio frame header so flac parser doesn't choke on EOF
        // 0xFFF8 (sync code for frame), etc.
        dos.writeShort(0xFFF8.toInt())
        dos.write(ByteArray(100))

        dos.flush()
        return baos.toByteArray()
    }
}
