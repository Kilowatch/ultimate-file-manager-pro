package za.kilowatch.ultimatefilemanager.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RootDetectorTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        RootDetector.clearCache()
    }

    @Test
    fun testRootDetectorReturnsValidResult() {
        val result = RootDetector.detect(context, forceRefresh = true)
        assertNotNull(result)
        assertNotNull(result.rootType)
        assertNotNull(result.detectedBinaries)
        assertNotNull(result.detectedPackages)
        assertFalse(RootDetector.isRooted(context) && result.rootType == RootDetector.RootType.NONE)
    }

    @Test
    fun testRootDetectorCaching() {
        val firstResult = RootDetector.detect(context)
        val secondResult = RootDetector.detect(context)
        // Ensure same cached instance is returned when forceRefresh = false
        assertSame(firstResult, secondResult)

        // Clear cache and ensure new instance is returned
        RootDetector.clearCache()
        val refreshedResult = RootDetector.detect(context)
        assertNotSame(firstResult, refreshedResult)
    }

    @Test
    fun testRootTypeDisplayNames() {
        assertEquals("None", RootDetector.RootType.NONE.displayName)
        assertEquals("Magisk", RootDetector.RootType.MAGISK.displayName)
        assertEquals("KernelSU", RootDetector.RootType.KERNEL_SU.displayName)
        assertEquals("APatch", RootDetector.RootType.APATCH.displayName)
        assertEquals("SuperSU", RootDetector.RootType.SUPERSU.displayName)
        assertEquals("su binary", RootDetector.RootType.GENERIC_SU.displayName)
    }

    @Test
    fun testRootDetectionResultProperties() {
        val nonRootedResult = RootDetector.RootDetectionResult(
            isRooted = false,
            rootType = RootDetector.RootType.NONE,
            detectedBinaries = emptyList(),
            detectedPackages = emptyList(),
            hasTestKeys = false
        )
        assertFalse(nonRootedResult.isRooted)
        assertEquals(RootDetector.RootType.NONE, nonRootedResult.rootType)
        assertTrue(nonRootedResult.detectedBinaries.isEmpty())
        assertTrue(nonRootedResult.detectedPackages.isEmpty())
        assertFalse(nonRootedResult.hasTestKeys)

        val magiskResult = RootDetector.RootDetectionResult(
            isRooted = true,
            rootType = RootDetector.RootType.MAGISK,
            detectedBinaries = listOf("/system/bin/su", "/data/adb/magisk/magisk64"),
            detectedPackages = listOf("com.topjohnwu.magisk"),
            hasTestKeys = true
        )
        assertTrue(magiskResult.isRooted)
        assertEquals(RootDetector.RootType.MAGISK, magiskResult.rootType)
        assertEquals(2, magiskResult.detectedBinaries.size)
        assertEquals("com.topjohnwu.magisk", magiskResult.detectedPackages.first())
        assertTrue(magiskResult.hasTestKeys)
    }
}
