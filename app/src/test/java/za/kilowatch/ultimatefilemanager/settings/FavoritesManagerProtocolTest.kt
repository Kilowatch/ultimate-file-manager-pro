package za.kilowatch.ultimatefilemanager.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import za.kilowatch.ultimatefilemanager.network.NetworkShare
import za.kilowatch.ultimatefilemanager.network.OnlineStorage
import za.kilowatch.ultimatefilemanager.network.OnlineStorageProvider
import za.kilowatch.ultimatefilemanager.network.OnlineStorageRepository
import za.kilowatch.ultimatefilemanager.network.ShareType
import za.kilowatch.ultimatefilemanager.shortcuts.FavoriteIconComposer

@RunWith(RobolectricTestRunner::class)
class FavoritesManagerProtocolTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testLocalFavoriteSubtitle() {
        val item = FavoritesManager.FavoriteItem(
            id = "fav_local_folder",
            path = "/storage/emulated/0/Download",
            label = "Downloads",
            isFolder = true,
            isNetwork = false
        )
        val proto = FavoritesManager.getFavoriteProtocolLabel(context, item)
        assertEquals("Local", proto)

        val subtitle = FavoritesManager.getFavoriteSubtitle(context, item)
        assertEquals("Local \u2022 Folder", subtitle)
    }

    @Test
    fun testLocalFileFavoriteSubtitle() {
        val item = FavoritesManager.FavoriteItem(
            id = "fav_local_file",
            path = "/storage/emulated/0/Download/test.mkv",
            label = "test.mkv",
            isFolder = false,
            isNetwork = false
        )
        val subtitle = FavoritesManager.getFavoriteSubtitle(context, item)
        assertEquals("Local \u2022 File", subtitle)
    }

    @Test
    fun testWebDavOnlineStorageFavoriteSubtitle() {
        // Register a WebDAV online storage in OnlineStorageRepository
        val onlineRepo = OnlineStorageRepository.getInstance(context)
        val webDavStorage = OnlineStorage(
            id = "webdav_share_1",
            displayName = "My WebDAV",
            provider = OnlineStorageProvider.WEBDAV,
            webDavUrl = "https://192.168.1.100:8080"
        )
        onlineRepo.save(webDavStorage)

        val folderItem = FavoritesManager.FavoriteItem(
            id = "fav_webdav_folder",
            path = "/A1",
            label = "A1",
            isFolder = true,
            isNetwork = true,
            shareId = "webdav_share_1"
        )
        val proto = FavoritesManager.getFavoriteProtocolLabel(context, folderItem)
        assertEquals("WebDAV", proto)

        val folderSubtitle = FavoritesManager.getFavoriteSubtitle(context, folderItem)
        assertEquals("WebDAV \u2022 Folder", folderSubtitle)

        val fileItem = FavoritesManager.FavoriteItem(
            id = "fav_webdav_file",
            path = "/A1/A1.mkv",
            label = "A1.mkv",
            isFolder = false,
            isNetwork = true,
            shareId = "webdav_share_1"
        )
        val fileSubtitle = FavoritesManager.getFavoriteSubtitle(context, fileItem)
        assertEquals("WebDAV \u2022 File", fileSubtitle)
    }

    @Test
    fun testSmbNetworkShareFavoriteSubtitle() {
        val smbShare = NetworkShare(
            id = "smb_1",
            name = "NAS",
            type = ShareType.SMB,
            host = "192.168.1.50"
        )
        val folderItem = FavoritesManager.FavoriteItem(
            id = "fav_smb_folder",
            path = "/Movies",
            label = "Movies",
            isFolder = true,
            isNetwork = true,
            shareId = "smb_1"
        )
        val subtitle = FavoritesManager.getFavoriteSubtitle(
            context,
            isNetwork = true,
            isFolder = true,
            shareId = "smb_1",
            netShare = smbShare
        )
        assertEquals("SMB \u2022 Folder", subtitle)
    }

    @Test
    fun testFavoriteIconComposerWithNetworkProtocolPill() {
        val onlineRepo = OnlineStorageRepository.getInstance(context)
        val webDavStorage = OnlineStorage(
            id = "webdav_share_2",
            displayName = "My WebDAV",
            provider = OnlineStorageProvider.WEBDAV,
            webDavUrl = "https://192.168.1.100:8080"
        )
        onlineRepo.save(webDavStorage)

        val item = FavoritesManager.FavoriteItem(
            id = "fav_wdav_compose",
            path = "/A1",
            label = "A1",
            isFolder = true,
            isNetwork = true,
            shareId = "webdav_share_2"
        )

        val bitmap = FavoriteIconComposer.composeIconBitmap(context, item)
        assertNotNull(bitmap)
        assertEquals(192, bitmap.width)
        assertEquals(192, bitmap.height)
    }
}
