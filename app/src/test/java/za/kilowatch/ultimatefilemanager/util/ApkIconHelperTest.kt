package za.kilowatch.ultimatefilemanager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkIconHelperTest {

    @Test
    fun testPackageExtensionsSet() {
        val expected = setOf("apk", "xapk", "apks", "apkm")
        assertEquals(expected, ApkIconHelper.PACKAGE_EXTENSIONS)
    }

    @Test
    fun testIsApkOrBundleExtension() {
        assertTrue(ApkIconHelper.isApkOrBundle("apk"))
        assertTrue(ApkIconHelper.isApkOrBundle("xapk"))
        assertTrue(ApkIconHelper.isApkOrBundle("apks"))
        assertTrue(ApkIconHelper.isApkOrBundle("apkm"))
        assertTrue(ApkIconHelper.isApkOrBundle("APKM"))
        assertTrue(ApkIconHelper.isApkOrBundle("Xapk"))
        assertFalse(ApkIconHelper.isApkOrBundle("zip"))
        assertFalse(ApkIconHelper.isApkOrBundle("rar"))
        assertFalse(ApkIconHelper.isApkOrBundle(null))
        assertFalse(ApkIconHelper.isApkOrBundle(""))
    }
}
