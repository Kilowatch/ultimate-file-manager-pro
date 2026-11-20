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

    @Test
    fun testNoDoubleEscapedUnicodeInStringResources() {
        val resDir = java.io.File("src/main/res").takeIf { it.exists() }
            ?: java.io.File("app/src/main/res").takeIf { it.exists() }
            ?: java.io.File("F:/Share/UFM/app/src/main/res")
        val xmlFiles = resDir.walkTopDown().filter { it.extension == "xml" && it.parentFile?.name?.startsWith("values") == true }
        val violations = mutableListOf<String>()
        for (file in xmlFiles) {
            val content = file.readText()
            if (content.contains("\\u") || content.contains("\\\\u")) {
                violations.add("${file.path}: contains unrendered \\u escape")
            }
        }
        org.junit.Assert.assertTrue("Double-escaped unicode sequences found in XML:\n${violations.joinToString("\n")}", violations.isEmpty())
    }

    @Test
    fun testFavoriteAndDeviceSubtitleRendersBulletAcrossLocales() {
        val resDir = java.io.File("src/main/res").takeIf { it.exists() }
            ?: java.io.File("app/src/main/res").takeIf { it.exists() }
            ?: java.io.File("F:/Share/UFM/app/src/main/res")
        val valuesDirs = resDir.listFiles()?.filter { it.isDirectory && it.name.startsWith("values") } ?: emptyList()
        org.junit.Assert.assertTrue("Values directories must be found", valuesDirs.isNotEmpty())

        val bulletPattern = java.util.regex.Pattern.compile("""<string\s+name="(fav_protocol_type_format|devicetypelabel_u2022_devicelastip)"[^>]*>(.*?)</string>""", java.util.regex.Pattern.DOTALL)

        for (dir in valuesDirs) {
            val stringsFile = java.io.File(dir, "strings.xml")
            if (!stringsFile.exists()) continue
            val text = stringsFile.readText()
            val matcher = bulletPattern.matcher(text)
            var count = 0
            while (matcher.find()) {
                val key = matcher.group(1) ?: ""
                val body = matcher.group(2) ?: ""
                count++
                org.junit.Assert.assertTrue("[${dir.name}:$key] must contain literal bullet '•': $body", body.contains("•"))
                org.junit.Assert.assertFalse("[${dir.name}:$key] must not contain '\\u2022': $body", body.contains("\\u2022"))
            }
            if (dir.name == "values" || dir.name.startsWith("values-")) {
                org.junit.Assert.assertTrue("[${dir.name}] must contain both bullet strings", count >= 2)
            }
        }

        val item = FavoritesManager.FavoriteItem(
            id = "fav_test",
            path = "/test",
            label = "test",
            isFolder = true,
            isNetwork = false
        )
        val subtitle = FavoritesManager.getFavoriteSubtitle(context, item)
        org.junit.Assert.assertTrue("getFavoriteSubtitle must contain bullet: $subtitle", subtitle.contains("•"))
        org.junit.Assert.assertFalse("getFavoriteSubtitle must not contain raw escape: $subtitle", subtitle.contains("\\u2022"))
    }
}
