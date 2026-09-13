package za.kilowatch.ultimatefilemanager.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class AudioCoverHelperTest {

    @Test
    fun testExtractFlacArtwork_validPictureBlock() {
        val fakeImageData = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x12, 0x34)
        val flacBytes = buildSyntheticFlac(includePicture = true, pictureBytes = fakeImageData)

        val extracted = AudioCoverHelper.extractFlacArtwork(ByteArrayInputStream(flacBytes))
        assertArrayEquals(fakeImageData, extracted)
    }

    @Test
    fun testExtractFlacArtwork_withPrecedingId3v2Header() {
        val fakeImageData = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val flacBytes = buildSyntheticFlac(includePicture = true, pictureBytes = fakeImageData, prependId3 = true)

        val extracted = AudioCoverHelper.extractFlacArtwork(ByteArrayInputStream(flacBytes))
        assertArrayEquals(fakeImageData, extracted)
    }

    @Test
    fun testExtractFlacArtwork_noPictureBlock() {
        val flacBytes = buildSyntheticFlac(includePicture = false)

        val extracted = AudioCoverHelper.extractFlacArtwork(ByteArrayInputStream(flacBytes))
        assertNull(extracted)
    }

    @Test
    fun testExtractFlacArtwork_invalidSignature() {
        val invalidBytes = byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04)

        val extracted = AudioCoverHelper.extractFlacArtwork(ByteArrayInputStream(invalidBytes))
        assertNull(extracted)
    }

    private fun buildSyntheticFlac(
        includePicture: Boolean,
        pictureBytes: ByteArray = ByteArray(0),
        prependId3: Boolean = false
    ): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)

        if (prependId3) {
            // Write 10-byte ID3v2 header: "ID3", ver 3.0, flags 0, 10 bytes size
            dos.write(byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte()))
            dos.writeByte(3)
            dos.writeByte(0)
            dos.writeByte(0)
            // Synchsafe size (10 bytes)
            dos.writeByte(0)
            dos.writeByte(0)
            dos.writeByte(0)
            dos.writeByte(10)
            // 10 padding bytes for the ID3 tag
            dos.write(ByteArray(10))
        }

        // "fLaC" marker
        dos.write(byteArrayOf('f'.code.toByte(), 'L'.code.toByte(), 'a'.code.toByte(), 'C'.code.toByte()))

        // Block 0: STREAMINFO (34 bytes), not last if includePicture is true
        val streamInfoIsLast = !includePicture
        val b0 = if (streamInfoIsLast) 0x80 else 0x00
        dos.writeByte(b0) // blockType 0
        dos.writeByte(0)
        dos.writeByte(0)
        dos.writeByte(34) // length = 34
        dos.write(ByteArray(34)) // 34 zero bytes

        if (includePicture) {
            // Block 6: PICTURE (isLast = true)
            val picBlockBaos = ByteArrayOutputStream()
            val picDos = DataOutputStream(picBlockBaos)

            picDos.writeInt(3) // PictureType: Front Cover
            val mime = "image/jpeg"
            picDos.writeInt(mime.length)
            picDos.writeBytes(mime)
            picDos.writeInt(0) // Description length
            picDos.writeInt(100) // width
            picDos.writeInt(100) // height
            picDos.writeInt(24) // color depth
            picDos.writeInt(0) // colors
            picDos.writeInt(pictureBytes.size) // picture data length
            picDos.write(pictureBytes) // picture data

            val picData = picBlockBaos.toByteArray()
            val picHeader0 = 0x86 // isLast = true (0x80) | blockType 6 (0x06)
            dos.writeByte(picHeader0)
            dos.writeByte((picData.size shr 16) and 0xFF)
            dos.writeByte((picData.size shr 8) and 0xFF)
            dos.writeByte(picData.size and 0xFF)
            dos.write(picData)
        }

        dos.flush()
        return baos.toByteArray()
    }
}
