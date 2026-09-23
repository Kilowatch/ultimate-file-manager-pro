package za.kilowatch.ultimatefilemanager.storage

import android.content.Context
import android.content.Intent
import android.os.Environment
import android.os.storage.StorageManager
import za.kilowatch.ultimatefilemanager.network.NetworkBrowserActivity
import za.kilowatch.ultimatefilemanager.network.NetworkShareRepository
import za.kilowatch.ultimatefilemanager.network.OnlineStorageRepository
import za.kilowatch.ultimatefilemanager.network.PairingManager
import za.kilowatch.ultimatefilemanager.tabs.TabSessionManager
import za.kilowatch.ultimatefilemanager.tabs.TabbedBrowserActivity
import java.io.File

/**
 * Manages the persistence and resolution of the user's last-opened browsing location on mobile.
 *
 * When the app is force closed or backgrounded, the active container and folder path are saved.
 * Upon cold start, [resolveStartIntent] verifies that the target storage location is still accessible.
 * If the storage volume is unavailable (e.g., unplugged USB drive, unmounted SD card, revoked SAF
 * tree permission, deleted network share), it automatically redirects to [StorageBrowserActivity].
 */
object LastLocationManager {

    private const val PREFS_NAME = "last_location_prefs"

    const val EXTRA_STORAGE_UNAVAILABLE_REDIRECT = "extra_storage_unavailable_redirect"

    const val TYPE_NONE = "NONE"
    const val TYPE_FILE_BROWSER = "FILE_BROWSER"
    const val TYPE_NETWORK_BROWSER = "NETWORK_BROWSER"
    const val TYPE_TABBED_BROWSER = "TABBED_BROWSER"
    const val TYPE_TWIN_WINDOW = "TWIN_WINDOW"
    const val TYPE_STORAGE_BROWSER = "STORAGE_BROWSER"

    private const val KEY_CONTAINER_TYPE = "container_type"

    // FileBrowser keys
    private const val KEY_MOUNT_PATH = "file_mount_path"
    private const val KEY_CURRENT_PATH = "file_current_path"
    private const val KEY_STORAGE_LABEL = "file_storage_label"
    private const val KEY_STORAGE_ID = "file_storage_id"
    private const val KEY_STORAGE_TYPE = "file_storage_type"
    private const val KEY_IS_REMOVABLE = "file_is_removable"
    private const val KEY_IS_ROOT = "file_is_root"

    // NetworkBrowser keys
    private const val KEY_NET_SHARE_ID = "net_share_id"
    private const val KEY_NET_CURRENT_PATH = "net_current_path"
    private const val KEY_NET_STORAGE_LABEL = "net_storage_label"
    private const val KEY_NET_IS_ONLINE = "net_is_online"
    private const val KEY_NET_PAIRED_DEVICE_ID = "net_paired_device_id"

    /**
     * Records that the user is currently browsing in [FileBrowserActivity].
     */
    fun recordFileBrowser(
        context: Context,
        mountPath: String,
        currentPath: String,
        storageLabel: String,
        storageId: String,
        storageType: String,
        isRemovable: Boolean,
        isRoot: Boolean
    ) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_CONTAINER_TYPE, TYPE_FILE_BROWSER)
            .putString(KEY_MOUNT_PATH, mountPath)
            .putString(KEY_CURRENT_PATH, currentPath)
            .putString(KEY_STORAGE_LABEL, storageLabel)
            .putString(KEY_STORAGE_ID, storageId)
            .putString(KEY_STORAGE_TYPE, storageType)
            .putBoolean(KEY_IS_REMOVABLE, isRemovable)
            .putBoolean(KEY_IS_ROOT, isRoot)
            .apply()
    }

    /**
     * Records that the user is currently browsing in [NetworkBrowserActivity].
     */
    fun recordNetworkBrowser(
        context: Context,
        shareId: String?,
        currentPath: String?,
        storageLabel: String?,
        isOnlineStorage: Boolean,
        pairedDeviceId: String? = null
    ) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_CONTAINER_TYPE, TYPE_NETWORK_BROWSER)
            .putString(KEY_NET_SHARE_ID, shareId)
            .putString(KEY_NET_CURRENT_PATH, currentPath)
            .putString(KEY_NET_STORAGE_LABEL, storageLabel)
            .putBoolean(KEY_NET_IS_ONLINE, isOnlineStorage)
            .putString(KEY_NET_PAIRED_DEVICE_ID, pairedDeviceId)
            .apply()
    }

    /**
     * Records that the user is currently in [TabbedBrowserActivity].
     * (Tab contents and active tab are persisted directly via [TabSessionManager]).
     */
    fun recordTabbedBrowser(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_CONTAINER_TYPE, TYPE_TABBED_BROWSER)
            .apply()
    }

    /**
     * Records that the user is currently in [TwinWindowActivity].
     */
    fun recordTwinWindow(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_CONTAINER_TYPE, TYPE_TWIN_WINDOW)
            .apply()
    }

    /**
     * Records that the user is on the main storage screen ([StorageBrowserActivity]).
     */
    fun recordStorageBrowser(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_CONTAINER_TYPE, TYPE_STORAGE_BROWSER)
            .apply()
    }

    /**
     * Clears recorded last location.
     */
    fun clear(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
    }

    /**
     * Resolves an [Intent] to restore the last opened session on mobile.
     *
     * Validates that the underlying storage location is still mounted and accessible.
     * If the storage is not available anymore, clears the obsolete session and returns
     * an intent directing the user to [StorageBrowserActivity] with [EXTRA_STORAGE_UNAVAILABLE_REDIRECT].
     */
    fun resolveStartIntent(context: Context): Intent {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val containerType = prefs.getString(KEY_CONTAINER_TYPE, TYPE_STORAGE_BROWSER) ?: TYPE_STORAGE_BROWSER

        when (containerType) {
            TYPE_FILE_BROWSER -> {
                val mountPath = prefs.getString(KEY_MOUNT_PATH, null)
                val currentPath = prefs.getString(KEY_CURRENT_PATH, mountPath)
                val storageLabel = prefs.getString(KEY_STORAGE_LABEL, "") ?: ""
                val storageId = prefs.getString(KEY_STORAGE_ID, "") ?: ""
                val storageType = prefs.getString(KEY_STORAGE_TYPE, "LOCAL") ?: "LOCAL"
                val isRemovable = prefs.getBoolean(KEY_IS_REMOVABLE, false)
                val isRoot = prefs.getBoolean(KEY_IS_ROOT, false)

                if (mountPath.isNullOrEmpty() || !isFileStorageAvailable(context, mountPath, isRemovable, isRoot, storageId)) {
                    // Storage location is unavailable anymore -> redirect to main storage screen
                    recordStorageBrowser(context)
                    return Intent(context, StorageBrowserActivity::class.java).apply {
                        putExtra(EXTRA_STORAGE_UNAVAILABLE_REDIRECT, true)
                    }
                }

                // If storage mount is valid but child directory was removed, fall back to mount root
                val effectivePath = if (!currentPath.isNullOrEmpty() && File(currentPath).exists()) {
                    currentPath
                } else {
                    mountPath
                }

                return Intent(context, FileBrowserActivity::class.java).apply {
                    putExtra(FileBrowserActivity.EXTRA_MOUNT_PATH, mountPath)
                    putExtra(FileBrowserActivity.EXTRA_INITIAL_PATH, effectivePath)
                    putExtra(FileBrowserActivity.EXTRA_STORAGE_LABEL, storageLabel)
                    putExtra(FileBrowserActivity.EXTRA_STORAGE_ID, storageId)
                    putExtra(FileBrowserActivity.EXTRA_STORAGE_TYPE, storageType)
                    putExtra(FileBrowserActivity.EXTRA_IS_REMOVABLE, isRemovable)
                    if (isRoot) {
                        putExtra(FileBrowserActivity.EXTRA_IS_ROOT_STORAGE, true)
                    }
                }
            }

            TYPE_NETWORK_BROWSER -> {
                val shareId = prefs.getString(KEY_NET_SHARE_ID, null)
                val currentPath = prefs.getString(KEY_NET_CURRENT_PATH, null)
                val storageLabel = prefs.getString(KEY_NET_STORAGE_LABEL, "")
                val isOnlineStorage = prefs.getBoolean(KEY_NET_IS_ONLINE, false)
                val pairedDeviceId = prefs.getString(KEY_NET_PAIRED_DEVICE_ID, null)

                if (!isNetworkStorageAvailable(context, shareId, isOnlineStorage, pairedDeviceId)) {
                    // Network / cloud location is unavailable -> redirect to main storage screen
                    recordStorageBrowser(context)
                    return Intent(context, StorageBrowserActivity::class.java).apply {
                        putExtra(EXTRA_STORAGE_UNAVAILABLE_REDIRECT, true)
                    }
                }

                return Intent(context, NetworkBrowserActivity::class.java).apply {
                    if (!pairedDeviceId.isNullOrEmpty()) {
                        putExtra(NetworkBrowserActivity.EXTRA_PAIRED_DEVICE_ID, pairedDeviceId)
                    } else {
                        putExtra(NetworkBrowserActivity.EXTRA_SHARE_ID, shareId)
                    }
                    if (isOnlineStorage) {
                        putExtra("isOnlineStorage", true)
                    }
                    if (!currentPath.isNullOrEmpty()) {
                        putExtra(NetworkBrowserActivity.EXTRA_INITIAL_PATH, currentPath)
                    }
                    if (!storageLabel.isNullOrEmpty()) {
                        putExtra(NetworkBrowserActivity.EXTRA_STORAGE_LABEL, storageLabel)
                    }
                }
            }

            TYPE_TABBED_BROWSER -> {
                if (!TabSessionManager.hasSavedTabs(context)) {
                    return Intent(context, StorageBrowserActivity::class.java)
                }
                val (savedTabs, _) = TabSessionManager.loadSession(context)
                if (savedTabs.isEmpty()) {
                    return Intent(context, StorageBrowserActivity::class.java)
                }
                val (validTabs, closedTabs) = TabSessionManager.validateAndPrune(context, savedTabs)
                if (closedTabs.size == savedTabs.size || validTabs.isEmpty()) {
                    // All tabs were on disconnected storage -> clear tabs and redirect to main screen
                    TabSessionManager.clearSession(context)
                    recordStorageBrowser(context)
                    return Intent(context, StorageBrowserActivity::class.java).apply {
                        putExtra(EXTRA_STORAGE_UNAVAILABLE_REDIRECT, true)
                    }
                }
                return Intent(context, TabbedBrowserActivity::class.java)
            }

            TYPE_TWIN_WINDOW -> {
                return Intent(context, TwinWindowActivity::class.java)
            }

            else -> {
                return Intent(context, StorageBrowserActivity::class.java)
            }
        }
    }

    /**
     * Checks if a local, removable, SAF, or root storage location is available.
     */
    private fun isFileStorageAvailable(
        context: Context,
        mountPath: String,
        isRemovable: Boolean,
        isRoot: Boolean,
        storageId: String
    ): Boolean {
        if (isRoot) {
            val root = File("/")
            return root.exists() && root.canRead()
        }

        if (MockUsbStorageManager.isMockUsbStorageId(storageId) ||
            MockUsbStorageManager.isMockUsbPath(context, mountPath)
        ) {
            return MockUsbStorageManager.isMockUsbMounted(context)
        }

        if (SafTreeManager.isSafPath(mountPath)) {
            val locId = mountPath.removePrefix("saf://").substringBefore('/')
            val loc = SafLocationRepository.getLocationById(context, locId)
            return loc != null && SafTreeManager.hasTreePermissionForPath(context, mountPath)
        }

        if (isRemovable) {
            val file = File(mountPath)
            if (!file.exists() || !file.canRead()) return false

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                try {
                    val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
                    val mountedVolumes = storageManager?.storageVolumes ?: emptyList()
                    for (vol in mountedVolumes) {
                        try {
                            val state = vol.state
                            if (state == Environment.MEDIA_MOUNTED || state == Environment.MEDIA_MOUNTED_READ_ONLY) {
                                val getPathMethod = vol.javaClass.getMethod("getPath")
                                val path = getPathMethod.invoke(vol) as? String
                                if (!path.isNullOrEmpty() && (mountPath == path || mountPath.startsWith("$path/"))) {
                                    return true
                                }
                            }
                        } catch (_: Throwable) {}
                    }
                } catch (_: Throwable) {}
            }
            return file.canRead()
        }

        val root = File(mountPath)
        return root.exists() && root.canRead()
    }

    /**
     * Checks if a network share, cloud storage, or paired TV is configured and valid.
     */
    private fun isNetworkStorageAvailable(
        context: Context,
        shareId: String?,
        isOnlineStorage: Boolean,
        pairedDeviceId: String?
    ): Boolean {
        if (!pairedDeviceId.isNullOrEmpty()) {
            return PairingManager.getInstance(context).getPairedDevice(pairedDeviceId) != null
        }
        if (shareId.isNullOrEmpty()) return false
        return if (isOnlineStorage) {
            OnlineStorageRepository.getInstance(context).getById(shareId) != null
        } else {
            NetworkShareRepository.getInstance(context).getById(shareId) != null
        }
    }
}
