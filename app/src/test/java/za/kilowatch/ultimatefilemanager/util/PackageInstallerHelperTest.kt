package za.kilowatch.ultimatefilemanager.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class PackageInstallerHelperTest {

    @Test
    fun isApk_recognizesApkExtensionCaseInsensitively() {
        assertTrue(PackageInstallerHelper.isApk(File("test.apk")))
        assertTrue(PackageInstallerHelper.isApk(File("test.APK")))
        assertTrue(PackageInstallerHelper.isApk(File("/path/to/archive.ApK")))
        assertFalse(PackageInstallerHelper.isApk(File("test.xapk")))
        assertFalse(PackageInstallerHelper.isApk(File("test.zip")))
    }

    @Test
    fun isXapk_recognizesXapkAndApksExtensionsCaseInsensitively() {
        assertTrue(PackageInstallerHelper.isXapk(File("test.xapk")))
        assertTrue(PackageInstallerHelper.isXapk(File("test.XAPK")))
        assertTrue(PackageInstallerHelper.isXapk(File("test.apks")))
        assertTrue(PackageInstallerHelper.isXapk(File("test.APKS")))
        assertFalse(PackageInstallerHelper.isXapk(File("test.apk")))
        assertFalse(PackageInstallerHelper.isXapk(File("test.zip")))
    }

    @Test
    fun openInstallPermissionSettings_doesNotThrowUncaughtActivityNotFoundException() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Should safely attempt resolution across fallback settings without throwing uncaught exceptions
        val result = PackageInstallerHelper.openInstallPermissionSettings(context)
        // result is boolean
    }

    @Test
    fun canResolveInstallPermissionSettings_doesNotThrow() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Must return boolean without uncaught exception
        PackageInstallerHelper.canResolveInstallPermissionSettings(context)
    }
}
