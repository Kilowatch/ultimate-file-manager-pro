package za.kilowatch.ultimatefilemanager.storage

import android.app.Activity
import android.content.Intent
import za.kilowatch.ultimatefilemanager.notepad.NotepadActivity
import za.kilowatch.ultimatefilemanager.scanner.DocumentScannerActivity
import za.kilowatch.ultimatefilemanager.smartsort.SmartSortActivity
import za.kilowatch.ultimatefilemanager.recycle.RecycleBinActivity
import za.kilowatch.ultimatefilemanager.sync.SyncManagerActivity
import za.kilowatch.ultimatefilemanager.sync.advanced.AdvancedSyncActivity
import za.kilowatch.ultimatefilemanager.server.ServerHostActivity
import za.kilowatch.ultimatefilemanager.settings.SettingsActivity
import za.kilowatch.ultimatefilemanager.network.NetworkShareManagerActivity
import za.kilowatch.ultimatefilemanager.network.OnlineStorageManagerActivity
import za.kilowatch.ultimatefilemanager.network.NetworkBrowserActivity
import za.kilowatch.ultimatefilemanager.tabs.TabbedBrowserActivity
import za.kilowatch.ultimatefilemanager.tabs.TabSessionManager
import za.kilowatch.ultimatefilemanager.tabs.StorageType
import za.kilowatch.ultimatefilemanager.util.DeviceUtils

/**
 * Dispatches tile launch actions across any Activity in the application.
 */
object StorageTileActionHandler {

    fun launchTile(activity: Activity, item: StorageItem) {
        if (activity is StorageBrowserActivity) {
            activity.onStorageTileClicked(item)
            return
        }

        when {
            item.isTwinWindowTile -> {
                activity.startActivity(Intent(activity, TwinWindowActivity::class.java))
            }
            item.isNotepadTile -> {
                activity.startActivity(Intent(activity, NotepadActivity::class.java))
            }
            item.isScannerTile -> {
                activity.startActivity(Intent(activity, DocumentScannerActivity::class.java))
            }
            item.isAppsTile -> {
                activity.startActivity(Intent(activity, AppManagerActivity::class.java))
            }
            item.isTerminalTile -> {
                activity.startActivity(Intent(activity, za.kilowatch.ultimatefilemanager.ui.TerminalActivity::class.java))
            }
            item.isShizukuTile -> {
                activity.startActivity(Intent(activity, za.kilowatch.ultimatefilemanager.ui.elevated.ElevatedAccessActivity::class.java))
            }
            item.isSearchTile -> {
                activity.startActivity(Intent(activity, SearchActivity::class.java))
            }
            item.isAnalyzerTile -> {
                activity.startActivity(Intent(activity, StorageAnalyzerActivity::class.java))
            }
            item.isSmartSortTile -> {
                activity.startActivity(Intent(activity, SmartSortActivity::class.java))
            }
            item.isVaultTile -> {
                activity.startActivity(Intent(activity, VaultActivity::class.java))
            }
            item.isRecycleBinTile -> {
                activity.startActivity(Intent(activity, RecycleBinActivity::class.java))
            }
            item.isSyncTile -> {
                activity.startActivity(Intent(activity, SyncManagerActivity::class.java))
            }
            item.isAdvancedSyncTile -> {
                activity.startActivity(Intent(activity, AdvancedSyncActivity::class.java))
            }
            item.isFileServerTile -> {
                activity.startActivity(Intent(activity, ServerHostActivity::class.java))
            }
            item.isSettingsTile -> {
                activity.startActivity(Intent(activity, SettingsActivity::class.java))
            }
            item.isNetworkTile -> {
                activity.startActivity(Intent(activity, NetworkShareManagerActivity::class.java))
            }
            item.isOnlineStoragesTile -> {
                activity.startActivity(Intent(activity, OnlineStorageManagerActivity::class.java))
            }
            item.isCustomTile -> {
                val intent = Intent(activity, CustomTileActivity::class.java).apply {
                    putExtra(CustomTileActivity.EXTRA_CUSTOM_TILE_ID, item.id)
                }
                activity.startActivity(intent)
            }
            item.isNetworkRoot -> {
                val share = item.networkShare
                if (!DeviceUtils.isTvDevice(activity) && TabSessionManager.hasSavedTabs(activity)) {
                    val type = if (share?.type == za.kilowatch.ultimatefilemanager.network.ShareType.GOOGLE_DRIVE ||
                        share?.type == za.kilowatch.ultimatefilemanager.network.ShareType.ONEDRIVE ||
                        share?.type == za.kilowatch.ultimatefilemanager.network.ShareType.DROPBOX
                    ) {
                        StorageType.CLOUD
                    } else {
                        StorageType.NETWORK
                    }
                    val rootPath = share?.remotePath ?: ""
                    val intent = Intent(activity, TabbedBrowserActivity::class.java).apply {
                        putExtra(TabbedBrowserActivity.EXTRA_INITIAL_PATH, rootPath)
                        putExtra(TabbedBrowserActivity.EXTRA_INITIAL_ROOT_PATH, rootPath)
                        putExtra(TabbedBrowserActivity.EXTRA_INITIAL_LABEL, item.label)
                        putExtra(TabbedBrowserActivity.EXTRA_INITIAL_SHARE_ID, share?.id)
                        putExtra(TabbedBrowserActivity.EXTRA_INITIAL_STORAGE_TYPE, type.name)
                    }
                    activity.startActivity(intent)
                } else {
                    val intent = Intent(activity, NetworkBrowserActivity::class.java).apply {
                        if (share?.type == za.kilowatch.ultimatefilemanager.network.ShareType.TV) {
                            putExtra(NetworkBrowserActivity.EXTRA_PAIRED_DEVICE_ID, share.id)
                        } else {
                            putExtra(NetworkBrowserActivity.EXTRA_SHARE_ID, share?.id)
                        }
                        putExtra(NetworkBrowserActivity.EXTRA_STORAGE_LABEL, item.label)
                    }
                    activity.startActivity(intent)
                }
            }
            else -> {
                // Local storage, SAF custom locations, favorites, SD/USB
                val mountPath = item.mountPath
                val label = item.label
                if (!DeviceUtils.isTvDevice(activity) && TabSessionManager.hasSavedTabs(activity)) {
                    val storageType = if (mountPath.startsWith("content://")) StorageType.SAF.name else StorageType.LOCAL.name
                    val intent = Intent(activity, TabbedBrowserActivity::class.java).apply {
                        putExtra(TabbedBrowserActivity.EXTRA_INITIAL_PATH, mountPath)
                        putExtra(TabbedBrowserActivity.EXTRA_INITIAL_ROOT_PATH, mountPath)
                        putExtra(TabbedBrowserActivity.EXTRA_INITIAL_LABEL, label)
                        putExtra(TabbedBrowserActivity.EXTRA_INITIAL_STORAGE_TYPE, storageType)
                    }
                    activity.startActivity(intent)
                } else {
                    val intent = Intent(activity, FileBrowserActivity::class.java).apply {
                        putExtra(FileBrowserActivity.EXTRA_MOUNT_PATH, mountPath)
                        putExtra(FileBrowserActivity.EXTRA_INITIAL_PATH, mountPath)
                        putExtra(FileBrowserActivity.EXTRA_STORAGE_LABEL, label)
                        putExtra(FileBrowserActivity.EXTRA_STORAGE_ID, item.id)
                        if (item.isSafCustomLocation) {
                            putExtra(FileBrowserActivity.EXTRA_STORAGE_TYPE, "saf_custom")
                        }
                    }
                    activity.startActivity(intent)
                }
            }
        }
    }
}
