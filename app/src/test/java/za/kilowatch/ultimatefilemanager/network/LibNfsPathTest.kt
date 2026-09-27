package za.kilowatch.ultimatefilemanager.network

import org.junit.Assert.assertEquals
import org.junit.Test

class LibNfsPathTest {

    @Test
    fun testStripExportPrefix_fullPathMatchesExport() {
        val share = NetworkShare(
            id = "nfs1",
            type = ShareType.NFS,
            name = "MediaShare",
            host = "192.168.1.50",
            remotePath = "/volume1/media"
        )
        val result = LibNfsClient.stripExportPrefix(share, "/volume1/media/movies/action.mkv")
        assertEquals("/movies/action.mkv", result)
    }

    @Test
    fun testStripExportPrefix_alreadyRelativePath() {
        val share = NetworkShare(
            id = "nfs1",
            type = ShareType.NFS,
            name = "MediaShare",
            host = "192.168.1.50",
            remotePath = "/volume1/media"
        )
        val result = LibNfsClient.stripExportPrefix(share, "/movies/action.mkv")
        assertEquals("/movies/action.mkv", result)
    }

    @Test
    fun testStripExportPrefix_exactExportRoot() {
        val share = NetworkShare(
            id = "nfs1",
            type = ShareType.NFS,
            name = "MediaShare",
            host = "192.168.1.50",
            remotePath = "/volume1/media"
        )
        val result = LibNfsClient.stripExportPrefix(share, "/volume1/media")
        assertEquals("/", result)
    }

    @Test
    fun testStripExportPrefix_rootExport() {
        val share = NetworkShare(
            id = "nfs1",
            type = ShareType.NFS,
            name = "MediaShare",
            host = "192.168.1.50",
            remotePath = "/"
        )
        val result = LibNfsClient.stripExportPrefix(share, "/video.mp4")
        assertEquals("/video.mp4", result)
    }

    @Test
    fun testStripExportPrefix_emptyExport() {
        val share = NetworkShare(
            id = "nfs1",
            type = ShareType.NFS,
            name = "MediaShare",
            host = "192.168.1.50",
            remotePath = ""
        )
        val result = LibNfsClient.stripExportPrefix(share, "/video.mp4")
        assertEquals("/video.mp4", result)
    }

    @Test
    fun testStripExportPrefix_windowsStyleBackslashes() {
        val share = NetworkShare(
            id = "nfs1",
            type = ShareType.NFS,
            name = "MediaShare",
            host = "192.168.1.50",
            remotePath = "/volume1/media"
        )
        val result = LibNfsClient.stripExportPrefix(share, "\\volume1\\media\\sample.mp3")
        assertEquals("/sample.mp3", result)
    }

    @Test
    fun testChunkSizesAreSafe() {
        // Must be exactly 64 KB to balance RPC throughput with socket/poll timeouts
        assertEquals(65536, LibNfsClient.NFS_READ_CHUNK_SIZE)
        assertEquals(65536, LibNfsClient.NFS_WRITE_CHUNK_SIZE)
    }
}
