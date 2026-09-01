package za.kilowatch.ultimatefilemanager.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import za.kilowatch.ultimatefilemanager.util.TransferConflictHelper

@RunWith(RobolectricTestRunner::class)
class RootShellWrapperTest {

    @Test
    fun testIsRootPath() {
        // System and root partitions should be identified as root paths
        assertTrue(RootShellWrapper.isRootPath("/"))
        assertTrue(RootShellWrapper.isRootPath("/system"))
        assertTrue(RootShellWrapper.isRootPath("/system/bin/sh"))
        assertTrue(RootShellWrapper.isRootPath("/data"))
        assertTrue(RootShellWrapper.isRootPath("/data/app"))
        assertTrue(RootShellWrapper.isRootPath("/vendor"))
        assertTrue(RootShellWrapper.isRootPath("/vendor/etc"))
        assertTrue(RootShellWrapper.isRootPath("/apex"))
        assertTrue(RootShellWrapper.isRootPath("/etc"))
        assertTrue(RootShellWrapper.isRootPath("/sbin"))
        assertTrue(RootShellWrapper.isRootPath("/init.rc"))

        // Regular storage, SAF, and cloud paths should NOT be root paths
        assertFalse(RootShellWrapper.isRootPath("/storage/emulated/0"))
        assertFalse(RootShellWrapper.isRootPath("/storage/emulated/0/Download"))
        assertFalse(RootShellWrapper.isRootPath("/sdcard/DCIM"))
        assertFalse(RootShellWrapper.isRootPath("saf://tree/primary:Download"))
        assertFalse(RootShellWrapper.isRootPath("smb://192.168.1.100/share"))
        assertFalse(RootShellWrapper.isRootPath(""))
    }

    @Test
    fun testRootFileRootDirectory() {
        val root = RootFile("", "")
        assertEquals("/", root.absolutePath)
        assertEquals("", root.name)
        assertTrue(root.isDirectory)
        assertFalse(root.isFile)
        assertNull(root.parent)
        assertNull(root.parentFile)
    }

    @Test
    fun testRootFileFirstLevelDirectory() {
        val systemDir = RootFile("", "system", true)
        assertEquals("/system", systemDir.absolutePath)
        assertEquals("system", systemDir.name)
        assertTrue(systemDir.isDirectory)
        assertFalse(systemDir.isFile)
        assertEquals("/", systemDir.parent)
        assertEquals("/", systemDir.parentFile?.absolutePath)
    }

    @Test
    fun testRootFileNestedFile() {
        val buildProp = RootFile(
            parentPath = "/system",
            docName = "build.prop",
            isDir = false,
            docLength = 4096L,
            docLastModified = 1700000000000L,
            posixPermissions = "rw-r--r--",
            ownerGroup = "root:root",
            selinuxContext = "u:object_r:system_file:s0"
        )

        assertEquals("/system/build.prop", buildProp.absolutePath)
        assertEquals("build.prop", buildProp.name)
        assertFalse(buildProp.isDirectory)
        assertTrue(buildProp.isFile)
        assertEquals(4096L, buildProp.length())
        assertEquals(1700000000000L, buildProp.lastModified())
        assertEquals("/system", buildProp.parent)
        assertEquals("/system", buildProp.parentFile?.absolutePath)
        assertEquals("rw-r--r--", buildProp.posixPermissions)
        assertEquals("root:root", buildProp.ownerGroup)
        assertEquals("u:object_r:system_file:s0", buildProp.selinuxContext)
        assertFalse(buildProp.isSymlink)
    }

    @Test
    fun testRootFileSymlink() {
        val symlink = RootFile(
            parentPath = "/system/bin",
            docName = "app_process",
            isDir = false,
            isSymlink = true,
            symlinkTarget = "app_process64"
        )

        assertEquals("/system/bin/app_process", symlink.absolutePath)
        assertEquals("app_process", symlink.name)
        assertTrue(symlink.isSymlink)
        assertEquals("app_process64", symlink.symlinkTarget)
    }

    @Test
    fun testTransferConflictHelperRootFileNameGeneration() {
        val destFolder = RootFile("/system", "app", true)

        // When testing uniqueLocalFile with base name
        val unique1 = TransferConflictHelper.uniqueLocalFile(destFolder, "TestApp.apk")
        assertEquals("/system/app/TestApp.apk", unique1.absolutePath)

        val uniqueFolder = TransferConflictHelper.uniqueLocalFolder(destFolder, "NewApp")
        assertEquals("/system/app/NewApp", uniqueFolder.absolutePath)
    }
}
