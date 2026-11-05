package za.kilowatch.ultimatefilemanager.shortcuts

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import za.kilowatch.ultimatefilemanager.indexing.IndexingRepository
import za.kilowatch.ultimatefilemanager.network.NetworkBrowserActivity
import za.kilowatch.ultimatefilemanager.network.NetworkShareRepository
import za.kilowatch.ultimatefilemanager.network.OnlineStorageRepository
import za.kilowatch.ultimatefilemanager.settings.FavoritesManager
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import za.kilowatch.ultimatefilemanager.settings.ManageFavoritesActivity
import za.kilowatch.ultimatefilemanager.storage.FileBrowserActivity
import za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter
import java.io.File

/**
 * Trampoline activity for handling home-screen pinned shortcut taps and Android TV preview program clicks.
 * Resolves the favorite item by ID and routes to the appropriate browser or file viewer.
 */
class FavoriteShortcutActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "FavoriteShortcutAct"
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uri = intent?.data
        val favId = uri?.getQueryParameter("favId")
            ?: intent?.getStringExtra("favId")
            ?: intent?.getStringExtra("id")

        val isManageFavorites = uri?.getQueryParameter("type") == "manageFavorites" ||
                uri?.getQueryParameter("manageFavorites") == "true"

        if (isManageFavorites) {
            startActivity(Intent(this, ManageFavoritesActivity::class.java))
            finish()
            return
        }

        if (favId.isNullOrEmpty()) {
            Log.w(TAG, "No favId found in deep link: $uri")
            showErrorAndFinish()
            return
        }

        val favorite = FavoritesManager.getFavorites(this).firstOrNull { it.id == favId }
        if (favorite == null) {
            Log.w(TAG, "Favorite not found for id=$favId")
            showErrorAndFinish()
            return
        }

        routeFavorite(favorite)
    }

    private fun routeFavorite(item: FavoritesManager.FavoriteItem) {
        if (item.isNetwork) {
            routeNetworkFavorite(item)
        } else {
            routeLocalFavorite(item)
        }
    }

    private fun routeNetworkFavorite(item: FavoritesManager.FavoriteItem) {
        val shareId = item.shareId
        if (shareId == null) {
            showErrorAndFinish()
            return
        }

        val netRepo = NetworkShareRepository.getInstance(this)
        val netShare = netRepo.getById(shareId)
        val onlineRepo = OnlineStorageRepository.getInstance(this)
        val onlineShare = if (netShare == null) onlineRepo.getById(shareId) else null

        if (netShare == null && onlineShare == null) {
            showErrorAndFinish("Network share is no longer configured or available.")
            return
        }

        if (item.isFolder) {
            val intent = Intent(this, NetworkBrowserActivity::class.java).apply {
                putExtra(NetworkBrowserActivity.EXTRA_SHARE_ID, shareId)
                putExtra(NetworkBrowserActivity.EXTRA_INITIAL_PATH, item.path)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(intent)
            finish()
        } else {
            // CL-07: Navigate to parent folder with target file focused
            val parentPath = item.path.substringBeforeLast('/', "")
            val intent = Intent(this, NetworkBrowserActivity::class.java).apply {
                putExtra(NetworkBrowserActivity.EXTRA_SHARE_ID, shareId)
                putExtra(NetworkBrowserActivity.EXTRA_INITIAL_PATH, parentPath)
                putExtra(NetworkBrowserActivity.EXTRA_OPEN_FILE_PATH, item.path)
                putExtra(NetworkBrowserActivity.EXTRA_OPEN_FILE_NAME, item.path.substringAfterLast('/'))
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(intent)
            finish()
        }
    }

    private fun routeLocalFavorite(item: FavoritesManager.FavoriteItem) {
        val targetFile = File(item.path)
        if (!targetFile.exists()) {
            showErrorAndFinish()
            return
        }

        if (item.isFolder || targetFile.isDirectory) {
            val (resolvedSid, resolvedStype, resolvedRoot) = IndexingRepository.resolveStorageForPath(targetFile.absolutePath)
            val intent = Intent(this, FileBrowserActivity::class.java).apply {
                putExtra(FileBrowserActivity.EXTRA_MOUNT_PATH, resolvedRoot)
                putExtra(FileBrowserActivity.EXTRA_INITIAL_PATH, targetFile.absolutePath)
                putExtra(FileBrowserActivity.EXTRA_STORAGE_ID, resolvedSid)
                putExtra(FileBrowserActivity.EXTRA_STORAGE_TYPE, resolvedStype)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(intent)
            finish()
        } else {
            // Local file: dispatch via FileViewerRouter
            val ext = targetFile.extension.lowercase()
            val defaultAction = za.kilowatch.ultimatefilemanager.viewer.DefaultOpenManager.getDefaultAction(this, ext, isNetwork = false)
            if (defaultAction != za.kilowatch.ultimatefilemanager.viewer.DefaultOpenManager.Action.ASK) {
                FileViewerRouter.openFile(this, targetFile)
                finish()
            } else {
                // When ASK is required, let the user choose.
                // onCancel finishes this activity if the user dismisses the chooser without picking an option.
                // onStop finishes this activity once a viewer activity is launched.
                val handled = FileViewerRouter.openFile(this, targetFile, onCancel = { finish() })
                if (!handled) {
                    FileViewerRouter.showOpenWithDialog(this, targetFile, onCancel = { finish() })
                }
            }
        }
    }

    private fun showErrorAndFinish(customMsg: String? = null) {
        FavShortcutErrorSheet.show(this, customMsg) {
            finish()
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isFinishing) {
            finish()
        }
    }
}
