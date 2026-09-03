package za.kilowatch.ultimatefilemanager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkPackageDetailsHelperTest {

    @Test
    fun testFormatSdkVersionMatchesKnownVersions() {
        assertEquals("5.0 (SDK 21 L)", ApkPackageDetailsHelper.formatSdkVersion(21))
        assertEquals("6.0 (SDK 23 M)", ApkPackageDetailsHelper.formatSdkVersion(23))
        assertEquals("7.0 (SDK 24 N)", ApkPackageDetailsHelper.formatSdkVersion(24))
        assertEquals("8.0 (SDK 26 O)", ApkPackageDetailsHelper.formatSdkVersion(26))
        assertEquals("9.0 (SDK 28 P)", ApkPackageDetailsHelper.formatSdkVersion(28))
        assertEquals("10.0 (SDK 29 Q)", ApkPackageDetailsHelper.formatSdkVersion(29))
        assertEquals("11.0 (SDK 30 R)", ApkPackageDetailsHelper.formatSdkVersion(30))
        assertEquals("12.0 (SDK 31 S)", ApkPackageDetailsHelper.formatSdkVersion(31))
        assertEquals("13.0 (SDK 33 T)", ApkPackageDetailsHelper.formatSdkVersion(33))
        assertEquals("14.0 (SDK 34 U)", ApkPackageDetailsHelper.formatSdkVersion(34))
        assertEquals("15.0 (SDK 35 V)", ApkPackageDetailsHelper.formatSdkVersion(35))
        assertEquals("16.0 (SDK 36 W)", ApkPackageDetailsHelper.formatSdkVersion(36))
    }

    @Test
    fun testFormatSdkVersionEdgeCases() {
        assertEquals("Unknown", ApkPackageDetailsHelper.formatSdkVersion(0))
        assertEquals("Unknown", ApkPackageDetailsHelper.formatSdkVersion(-1))
        assertEquals("17.0 (SDK 37)", ApkPackageDetailsHelper.formatSdkVersion(37))
    }

    @Test
    fun testIsApkOrBundle() {
        assertTrue(ApkPackageDetailsHelper.isApkOrBundle("my_app.apk"))
        assertTrue(ApkPackageDetailsHelper.isApkOrBundle("my_app.xapk"))
        assertTrue(ApkPackageDetailsHelper.isApkOrBundle("my_app.apks"))
        assertTrue(ApkPackageDetailsHelper.isApkOrBundle("my_app.apkm"))
        assertTrue(ApkPackageDetailsHelper.isApkOrBundle("/storage/emulated/0/Download/bundle.XAPK"))
        assertFalse(ApkPackageDetailsHelper.isApkOrBundle("file.zip"))
        assertFalse(ApkPackageDetailsHelper.isApkOrBundle("photo.jpg"))
        assertFalse(ApkPackageDetailsHelper.isApkOrBundle("document.pdf"))
    }

    @Test
    fun testAxmlDecoderPlainXmlFallback() {
        val xml = "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"test\"/>"
        val decoded = AxmlDecoder.decode(xml.toByteArray(Charsets.UTF_8))
        assertTrue(decoded.contains("package=\"test\""))
    }

    @Test
    fun testAxmlDecoderInvalidBytesDoesNotCrash() {
        val invalidBytes = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
        val decoded = AxmlDecoder.decode(invalidBytes)
        assertTrue(decoded.contains("Error"))
    }
}
