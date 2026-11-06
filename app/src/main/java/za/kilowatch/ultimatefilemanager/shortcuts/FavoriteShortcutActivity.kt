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
        applyZeroAnimationTransition()
        super.onCreate(savedInstanceState)

        val uri = intent?.data
        val favId = uri?.getQueryParameter("favId")
            ?: intent?.getStringExtra("favId")
            ?: intent?.getStringExtra("id")
        val pathParam = uri?.getQueryParameter("path")
            ?: intent?.getStringExtra("path")

        val isManageFavorites = uri?.getQueryParameter("type") == "manageFavorites" ||
                uri?.getQueryParameter("manageFavorites") == "true"

        if (isManageFavorites) {
            startActivity(Intent(this, ManageFavoritesActivity::class.java))
            finish()
            return
        }

        val allFavorites = FavoritesManager.getFavorites(this)
        val favorite = (if (!favId.isNullOrEmpty()) allFavorites.firstOrNull { it.id == favId } else null)
            ?: (if (!pathParam.isNullOrEmpty()) allFavorites.firstOrNull { it.path == pathParam } else null)
            ?: run {
                val effectivePath = pathParam ?: uri?.getQueryParameter("path") ?: intent?.getStringExtra("path")
                val shareId = uri?.getQueryParameter("shareId") ?: intent?.getStringExtra("shareId")
                if (!effectivePath.isNullOrEmpty() || !shareId.isNullOrEmpty()) {
                    val label = uri?.getQueryParameter("label") ?: intent?.getStringExtra("label") ?: ""
                    val isFolder = uri?.getBooleanQueryParameter("isFolder", true)
                        ?: intent?.getBooleanExtra("isFolder", true)
                        ?: true
                    val isNetwork = uri?.getBooleanQueryParameter("isNetwork", false)
                        ?: intent?.getBooleanExtra("isNetwork", false)
                        ?: (!shareId.isNullOrEmpty())
                    FavoritesManager.FavoriteItem(
                        id = favId ?: java.util.UUID.randomUUID().toString(),
                        path = effectivePath ?: "/",
                        label = label,
                        isFolder = isFolder,
                        isNetwork = isNetwork,
                        shareId = shareId
                    )
                } else null
            }

        if (favorite == null) {
            Log.w(TAG, "Favorite not found for id=$favId, path=$pathParam")
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

        val isTvPaired = shareId.startsWith("tv_")
        val pairedDevice = if (isTvPaired) {
            val deviceId = shareId.removePrefix("tv_")
            za.kilowatch.ultimatefilemanager.network.PairingManager.getInstance(this).getPairedDevice(deviceId)
        } else null

        val netRepo = NetworkShareRepository.getInstance(this)
        val netShare = if (!isTvPaired) netRepo.getById(shareId) else null
        val onlineRepo = OnlineStorageRepository.getInstance(this)
        val onlineShare = if (!isTvPaired && netShare == null) onlineRepo.getById(shareId) else null

        if (!isTvPaired && netShare == null && onlineShare == null) {
            showErrorAndFinish("Network share is no longer configured or available.")
            return
        }

        if (item.isFolder) {
            val intent = Intent(this, NetworkBrowserActivity::class.java).apply {
                if (isTvPaired) {
                    putExtra(NetworkBrowserActivity.EXTRA_PAIRED_DEVICE_ID, pairedDevice?.deviceId ?: shareId.removePrefix("tv_"))
                } else {
                    putExtra(NetworkBrowserActivity.EXTRA_SHARE_ID, shareId)
                }
                putExtra(NetworkBrowserActivity.EXTRA_INITIAL_PATH, item.path)
                putExtra(NetworkBrowserActivity.EXTRA_STORAGE_LABEL, item.label)
                if (onlineShare != null) {
                    putExtra("isOnlineStorage", true)
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            finish()
        } else {
            // CL-07: Navigate to parent folder with target file focused
            val parentPath = item.path.substringBeforeLast('/', "")
            val intent = Intent(this, NetworkBrowserActivity::class.java).apply {
                if (isTvPaired) {
                    putExtra(NetworkBrowserActivity.EXTRA_PAIRED_DEVICE_ID, pairedDevice?.deviceId ?: shareId.removePrefix("tv_"))
                } else {
                    putExtra(NetworkBrowserActivity.EXTRA_SHARE_ID, shareId)
                }
                putExtra(NetworkBrowserActivity.EXTRA_INITIAL_PATH, parentPath)
                putExtra(NetworkBrowserActivity.EXTRA_OPEN_FILE_PATH, item.path)
                putExtra(NetworkBrowserActivity.EXTRA_OPEN_FILE_NAME, item.path.substringAfterLast('/'))
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            finish()
        }
    }

    private fun routeLocalFavorite(item: FavoritesManager.FavoriteItem) {
        if (item.path.startsWith("content://") || item.path.startsWith("saf://")) {
            val intent = Intent(this, FileBrowserActivity::class.java).apply {
                putExtra(FileBrowserActivity.EXTRA_MOUNT_PATH, item.path)
                putExtra(FileBrowserActivity.EXTRA_INITIAL_PATH, item.path)
                putExtra(FileBrowserActivity.EXTRA_STORAGE_TYPE, "saf_custom")
                putExtra(FileBrowserActivity.EXTRA_STORAGE_LABEL, item.label)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            finish()
            return
        }

        val targetFile = File(item.path)
        if (item.isFolder || item.isStorageTileFavorite || targetFile.isDirectory) {
            val (resolvedSid, resolvedStype, resolvedRoot) = IndexingRepository.resolveStorageForPath(targetFile.absolutePath)
            val intent = Intent(this, FileBrowserActivity::class.java).apply {
                putExtra(FileBrowserActivity.EXTRA_MOUNT_PATH, resolvedRoot)
                putExtra(FileBrowserActivity.EXTRA_INITIAL_PATH, targetFile.absolutePath)
                putExtra(FileBrowserActivity.EXTRA_STORAGE_ID, resolvedSid)
                putExtra(FileBrowserActivity.EXTRA_STORAGE_TYPE, resolvedStype)
                putExtra(FileBrowserActivity.EXTRA_STORAGE_LABEL, item.label)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            finish()
            return
        }

        if (!targetFile.exists()) {
            showErrorAndFinish()
            return
        }

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

    private fun showErrorAndFinish(customMsg: String? = null) {
        FavShortcutErrorSheet.show(this, customMsg) {
            finish()
        }
    }

    private fun applyZeroAnimationTransition() {
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(android.app.Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(android.app.Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    override fun finish() {
        super.finish()
        applyZeroAnimationTransition()
    }

    override fun onStop() {
        super.onStop()
        if (!isFinishing) {
            finish()
        }
    }
}
