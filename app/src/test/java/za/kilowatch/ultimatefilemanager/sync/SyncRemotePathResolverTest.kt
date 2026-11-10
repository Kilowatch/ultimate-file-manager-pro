package za.kilowatch.ultimatefilemanager.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import za.kilowatch.ultimatefilemanager.network.NetworkShare
import za.kilowatch.ultimatefilemanager.network.ShareType

class SyncRemotePathResolverTest {

    @Test
    fun testWebDavSubfolderResolution() {
        val webDavShare = NetworkShare(
            id = "webdav_1",
            name = "Nextcloud",
            type = ShareType.WEBDAV,
            host = "https://cloud.example.com/remote.php/dav/files/user/",
            remotePath = ""
        )

        val resolved = SyncRemotePathResolver.resolve(webDavShare, "Photos/Vacation")
        assertEquals("Photos/Vacation", resolved.syncRemotePath)
        assertEquals("", resolved.effectiveShare.remotePath)
        assertFalse(resolved.effectiveShare.isServerMode)
    }

    @Test
    fun testWebDavRootResolution() {
        val webDavShare = NetworkShare(
            id = "webdav_root",
            name = "Nextcloud Root",
            type = ShareType.WEBDAV,
            host = "https://cloud.example.com/remote.php/dav/files/user/",
            remotePath = ""
        )

        val resolvedEmpty = SyncRemotePathResolver.resolve(webDavShare, "")
        assertEquals("", resolvedEmpty.syncRemotePath)

        val resolvedSlash = SyncRemotePathResolver.resolve(webDavShare, "/")
        assertEquals("", resolvedSlash.syncRemotePath)
    }

    @Test
    fun testWebDavIrregularSlashesNormalized() {
        val webDavShare = NetworkShare(
            id = "webdav_slashes",
            name = "Nextcloud",
            type = ShareType.WEBDAV,
            host = "https://cloud.example.com/remote.php/dav/files/user/",
            remotePath = ""
        )

        val resolved = SyncRemotePathResolver.resolve(webDavShare, "///Documents/Work/Reports///")
        assertEquals("Documents/Work/Reports", resolved.syncRemotePath)
    }

    @Test
    fun testSmbServerModeDynamicShare() {
        val smbServer = NetworkShare(
            id = "smb_srv",
            name = "NAS",
            type = ShareType.SMB,
            host = "192.168.1.100",
            remotePath = "",
            isServerMode = true
        )

        val resolved = SyncRemotePathResolver.resolve(smbServer, "Media/Movies/SciFi")
        assertEquals("", resolved.syncRemotePath)
        assertEquals("Media/Movies/SciFi", resolved.effectiveShare.remotePath)
        assertFalse(resolved.effectiveShare.isServerMode)
    }

    @Test
    fun testSmbStandardShareSubfolder() {
        val smbShare = NetworkShare(
            id = "smb_std",
            name = "NAS Media",
            type = ShareType.SMB,
            host = "192.168.1.100",
            remotePath = "/Media",
            isServerMode = false
        )

        // Browser returns path with share prefix
        val resolvedWithPrefix = SyncRemotePathResolver.resolve(smbShare, "Media/Movies/Comedy")
        assertEquals("Movies/Comedy", resolvedWithPrefix.syncRemotePath)
        assertEquals("/Media", resolvedWithPrefix.effectiveShare.remotePath)

        // Browser returns path with lowercase share prefix (case insensitivity)
        val resolvedCaseDiff = SyncRemotePathResolver.resolve(smbShare, "media/Movies/Comedy")
        assertEquals("Movies/Comedy", resolvedCaseDiff.syncRemotePath)

        // Stored as already relative
        val resolvedRelative = SyncRemotePathResolver.resolve(smbShare, "Movies/Comedy")
        assertEquals("Movies/Comedy", resolvedRelative.syncRemotePath)

        // Share root selected
        val resolvedRoot = SyncRemotePathResolver.resolve(smbShare, "Media")
        assertEquals("", resolvedRoot.syncRemotePath)

        val resolvedSlashRoot = SyncRemotePathResolver.resolve(smbShare, "/Media/")
        assertEquals("", resolvedSlashRoot.syncRemotePath)
    }

    @Test
    fun testNonSmbProtocolsPreserveRemotePath() {
        val protocols = listOf(
            ShareType.SFTP,
            ShareType.FTP,
            ShareType.NFS,
            ShareType.AWS_S3,
            ShareType.IDRIVE_E2
        )

        for (protocol in protocols) {
            val share = NetworkShare(
                id = "test_${protocol.name}",
                name = protocol.name,
                type = protocol,
                host = "example.com",
                remotePath = ""
            )

            val resolvedSub = SyncRemotePathResolver.resolve(share, "backups/daily")
            assertEquals("backups/daily", resolvedSub.syncRemotePath)
            assertEquals("", resolvedSub.effectiveShare.remotePath)

            val resolvedRoot = SyncRemotePathResolver.resolve(share, "/")
            assertEquals("", resolvedRoot.syncRemotePath)
        }
    }

    @Test
    fun testBuildRemoteFilePath() {
        assertEquals("file.txt", SyncRemotePathResolver.buildRemoteFilePath("", "file.txt"))
        assertEquals("file.txt", SyncRemotePathResolver.buildRemoteFilePath("/", "file.txt"))
        assertEquals("file.txt", SyncRemotePathResolver.buildRemoteFilePath("///", "file.txt"))
        assertEquals("Photos/beach.jpg", SyncRemotePathResolver.buildRemoteFilePath("Photos", "beach.jpg"))
        assertEquals("Photos/beach.jpg", SyncRemotePathResolver.buildRemoteFilePath("/Photos/", "beach.jpg"))
        assertEquals("Photos/2026/beach.jpg", SyncRemotePathResolver.buildRemoteFilePath("/Photos/2026/", "beach.jpg"))
    }
}
