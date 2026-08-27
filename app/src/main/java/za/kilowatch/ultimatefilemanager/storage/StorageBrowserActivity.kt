package za.kilowatch.ultimatefilemanager.storage

import za.kilowatch.ultimatefilemanager.util.safeDirectoryPath

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.os.Looper
import android.os.StatFs

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.core.view.doOnLayout
import kotlin.math.roundToInt
import com.google.android.material.snackbar.Snackbar
import za.kilowatch.ultimatefilemanager.BuildConfig
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.network.DlnaDiscovery
import za.kilowatch.ultimatefilemanager.network.NetworkBrowserActivity
import za.kilowatch.ultimatefilemanager.remote.PinDialogHelper
import za.kilowatch.ultimatefilemanager.remote.RemoteManageActivity
import za.kilowatch.ultimatefilemanager.remote.VpnWarningHelper
import za.kilowatch.ultimatefilemanager.settings.ThemeActivity
import za.kilowatch.ultimatefilemanager.settings.ThemeHelper
import za.kilowatch.ultimatefilemanager.settings.DefaultIconColorManager
import za.kilowatch.ultimatefilemanager.ui.policy.PolicySelectionActivity
import za.kilowatch.ultimatefilemanager.billing.SupporterLoyaltyActivity
import za.kilowatch.ultimatefilemanager.billing.AutoBackupPrefs
import za.kilowatch.ultimatefilemanager.billing.AutoBackupScheduler
import za.kilowatch.ultimatefilemanager.billing.LoyaltyPrefs
import za.kilowatch.ultimatefilemanager.settings.SettingsBackupManager
import za.kilowatch.ultimatefilemanager.settings.ThemePackManager
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import java.io.File
import za.kilowatch.ultimatefilemanager.settings.FontSizeHelper
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import za.kilowatch.ultimatefilemanager.indexing.IndexingManager
import za.kilowatch.ultimatefilemanager.indexing.IndexingRepository
import za.kilowatch.ultimatefilemanager.indexing.IndexingUiHelper
import za.kilowatch.ultimatefilemanager.smartsort.SmartSortActivity
import za.kilowatch.ultimatefilemanager.support.CrashReportDialogHelper
import androidx.lifecycle.lifecycleScope
import android.widget.RadioButton
import com.google.android.material.card.MaterialCardView

/**
 * Displays all available storage volumes (internal, SD card, USB) as cards.
 * Dynamically updates when new storage is mounted or removed.
 *
 * Uses three detection mechanisms:
 * 1. StorageVolume callback (API 30+) â€” immediate, most reliable
 * 2. BroadcastReceiver for media/USB events â€” classic approach
 * 3. onResume auto-refresh â€” catches anything missed
 */
class StorageBrowserActivity : AppCompatActivity() {

    private lateinit var recyclerStorage: RecyclerView
    private lateinit var layoutEmptyStorage: android.view.ViewGroup
    private var btnToggleGrid: ImageView? = null
    private var btnToggleList: ImageView? = null
    
    private var isPickerMode = false
    private var isKeyfilePickerMode = false
    private var isCertPickerMode = false
    private var pickerExtensions: String? = null
    private var isSyncFolderPickerMode = false
    private var isAdvancedSyncFolderPickerMode = false
    private var isAdvancedSyncDestPickerMode = false
    private var isCompressDestPickerMode = false
    private var isExtractDestPickerMode = false
    private var isImageCompressDestPickerMode = false
    private var isGifCreatorDestPickerMode = false
    private var isDrivePicker = false
    private var isLocationPickerMode = false
    private var isSearchFolderPicker = false
    private var isNetworkCachePickerMode = false
    private var isQuickTransferPickerMode = false
    private var isShareDestPickerMode = false
    private var isNotepadFolderPicker = false
    private var isScannerFolderPicker = false
    private var isAutoBackupFolderPicker = false
    private var isSupportAttachmentPicker = false
    private var isTileIconPickerMode = false
    private var activeTileIdForIcon: String? = null
    
    // Result launcher to forward picker selection back to caller
    private val pickerLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            setResult(RESULT_OK, result.data)
            finish()
        }
    }

    // Result launcher for tile icon file picker
    private var activeColorSheet: TileColorBottomSheet? = null
    private val iconPickerLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val selectedPath = result.data?.getStringExtra(FileBrowserActivity.RESULT_SELECTED_PATH)
            if (selectedPath != null) {
                val tileId = activeTileIdForIcon ?: return@registerForActivityResult
                val sourceFile = java.io.File(selectedPath)
                if (sourceFile.exists() && sourceFile.length() > TileIconManager.MAX_SIZE_BYTES) {
                    androidx.core.content.ContextCompat.getString(this, R.string.tile_icon_file_too_large)
                        .let { msg -> android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show() }
                } else {
                    val privatePath = TileIconManager.copyToPrivateStorage(this, tileId, selectedPath)
                    if (privatePath != null) {
                        activeColorSheet?.onIconPicked(privatePath)
                    } else {
                        androidx.core.content.ContextCompat.getString(this, R.string.tile_icon_invalid_file)
                            .let { msg -> android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show() }
                    }
                }
            }
        }
        activeTileIdForIcon = null
    }
    private lateinit var storageAdapter: StorageAdapter
    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            loadStorageVolumes()
        }
    }

    private var isTv = false
    private var isAmazon = false
    private val storageReceiver = StorageEventReceiver()
    private val knownMountPaths = mutableSetOf<String>()
    private val handler = Handler(Looper.getMainLooper())
    private var lastShizukuLaunchTime = 0L


    private var tvSnapHelper: androidx.recyclerview.widget.SnapHelper? = null


    companion object {
        private const val TAG = "StorageBrowser"
        /** Max time (ms) to spend on a single StatFs call during the TV USB fallback scan. */
        private const val STAT_TIMEOUT_MS = 300L

        /**
         * Set to true when USB storage is detected at /mnt/media_rw/ but SELinux
         * (or another platform restriction) blocks access.  The display code reads this
         * flag and shows a Shizuku-guidance card in place of the invisible USB volume.
         */
        var usbSelinuxBlocked = false
        var hasShownTipJarThisSession = false
        /** When true, the user is picking a source folder for Folder Sync */
        const val EXTRA_SYNC_FOLDER_PICKER = "extra_sync_folder_picker"
        /** When true, the user is picking a source folder for Advanced Sync */
        const val EXTRA_ADVANCED_SYNC_FOLDER_PICKER = "extra_advanced_sync_folder_picker"
        /** When true, the user is picking a remote destination folder for Advanced Sync (shows network + online storages only) */
        const val EXTRA_ADVANCED_SYNC_DEST_PICKER = "extra_advanced_sync_dest_picker"
        /** When true, the user is picking a destination folder for Compress */
        const val EXTRA_COMPRESS_DEST_PICKER = "extra_compress_dest_picker"
        /** When true, the user is picking a destination folder for Extract */
        const val EXTRA_EXTRACT_DEST_PICKER = "extra_extract_dest_picker"
        /** When true, the user is picking a destination folder for Image Compress */
        const val EXTRA_IMAGE_COMPRESS_DEST_PICKER = "extra_image_compress_dest_picker"
        /** When true, the user is picking a destination folder for GIF Creator */
        const val EXTRA_GIF_CREATOR_DEST_PICKER = "extra_gif_creator_dest_picker"
        /** When true, the user is picking a drive (e.g. for Twin Window) */
        const val EXTRA_DRIVE_PICKER = "extra_drive_picker"
        /** Returned by child activity when the user confirms a sync folder */
        const val RESULT_SELECTED_SYNC_PATH = "result_selected_sync_path"
        /** Returned by child activity â€” the absolute path of the selected local folder */
        const val RESULT_SELECTED_LOCAL_PATH = "result_selected_local_path"
        
        /** When true, the user is picking a tile icon file from any storage */
        const val EXTRA_TILE_ICON_PICKER = "extra_tile_icon_picker"
        /** When true, the user is picking a full location (URI, label, type, meta) */
        const val EXTRA_LOCATION_PICKER = "extra_location_picker"
        /** When true, the user is picking a folder specifically for Search scope (local storage only) */
        const val EXTRA_SEARCH_FOLDER_PICKER = "extra_search_folder_picker"
        /** When true, the user is picking a local folder for network thumbnail caching */
        const val EXTRA_NETWORK_CACHE_PICKER = "extra_network_cache_picker"
        /** When true, the user is picking a public key file for SSH authentication */
        const val EXTRA_KEYFILE_PICKER = "extra_keyfile_picker"
        /** When true, the user is picking a certificate file for Remote Manage HTTPS */
        const val EXTRA_CERT_PICKER = "extra_cert_picker"
        
        /** Result keys for location picker */
        const val RESULT_URI = "result_uri"
        const val RESULT_LABEL = "result_label"
        const val RESULT_TYPE = "result_type"
        const val RESULT_META_ID = "result_meta_id"

        /** When true, the user is picking a destination folder for Share Receive */
        const val EXTRA_SHARE_DEST_PICKER = "extra_share_dest_picker"
        /** Returned when the user picks a local folder as share destination */
        const val RESULT_SELECTED_SHARE_ID = "result_selected_share_id"
        /** Returned when the user picks a network folder as share destination */
        const val RESULT_SELECTED_NET_PATH = "result_selected_net_path"

        /**
         * Static utility to get only physical connected storages (Internal, SD, USB).
         */
        fun getConnectedStorages(context: Context, localOnly: Boolean = false): List<StorageItem> {
            val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
            val volumes = storageManager.storageVolumes
            val storageItems = mutableListOf<StorageItem>()
            
            for (volume in volumes) {
                val item = volumeToItem(context, volume) ?: continue
                storageItems.add(item)
            }

            // USB scan for TV
            if (DeviceUtils.isTvDevice(context)) {
                val discovered = storageItems.map { it.mountPath }.toSet()
                scanExtraPaths(context, storageItems, discovered)
            }

            // On Amazon FireOS, if USB drives at /mnt/media_rw/ exist but SELinux blocks
            // access, add a guidance card pointing users to Shizuku as a workaround.
            if (usbSelinuxBlocked) {
                storageItems.add(StorageItem(
                    id = "shizuku_usb_access",
                    label = context.getString(R.string.shizuku_usb_access_title),
                    iconRes = R.drawable.ic_shizuku_logo,
                    totalBytes = 0,
                    usedBytes = 0,
                    mountPath = "",
                    isShizukuTile = true,
                    subtitle = context.getString(R.string.shizuku_usb_access_subtitle)
                ))
            }

            if (localOnly) return storageItems

            // Add Network Shares (SMB/FTP)
            val repo = za.kilowatch.ultimatefilemanager.network.NetworkShareRepository.getInstance(context)
            repo.getAll().forEach { share ->
                storageItems.add(StorageItem(
                    id = share.id,
                    label = share.name,
                    iconRes = R.drawable.ic_network,
                    totalBytes = 0,
                    usedBytes = 0,
                    mountPath = share.docIdPrefix,
                    isNetworkRoot = true,
                    networkShare = share
                ))
            }

            // Add Paired Devices (TV/Phone)
            val pairingManager = za.kilowatch.ultimatefilemanager.network.PairingManager.getInstance(context)
            pairingManager.getAllPairedDevices().forEach { device ->
                if (device.isConnected) {
                    val iconRes = if (device.isTv) R.drawable.ic_remote_manage else R.drawable.ic_phone
                    storageItems.add(StorageItem(
                        id = "tv_${device.deviceId}",
                        label = device.name.ifEmpty { if (device.isTv) context.getString(R.string.connected_tv) else context.getString(R.string.connected_phone) },
                        iconRes = iconRes,
                        totalBytes = 0,
                        usedBytes = 0,
                        mountPath = "tv://${device.deviceId}",
                        isNetworkRoot = true,
                        networkShare = za.kilowatch.ultimatefilemanager.network.NetworkShare(
                            id = device.deviceId,
                            name = device.name,
                            type = za.kilowatch.ultimatefilemanager.network.ShareType.TV,
                            host = device.lastIp,
                            port = device.lastPort,
                            readOnly = false
                        )
                    ))
                }
            }

            // Add Custom SAF Storage Locations (Termux, Document Providers, USB/Custom Folders)
            val safLocations = SafLocationRepository.getLocations(context)
            for (loc in safLocations) {
                val iconRes = if (loc.iconType == "terminal" || loc.authority.contains("termux")) R.drawable.ic_terminal else R.drawable.ic_folder

                storageItems.add(StorageItem(
                    id = "saf_location_${loc.id}",
                    label = loc.displayName,
                    iconRes = iconRes,
                    totalBytes = 0,
                    usedBytes = 0,
                    mountPath = "saf://${loc.id}",
                    isSafCustomLocation = true,
                    safLocation = loc,
                    colorConfig = TileColorConfig(),
                    subtitle = context.getString(R.string.saf_storage)
                ))
            }

            return storageItems
        }

        private fun volumeToItem(context: Context, volume: StorageVolume): StorageItem? {
            val path = volume.safeDirectoryPath ?: return null
            if (volume.state != android.os.Environment.MEDIA_MOUNTED && 
                volume.state != android.os.Environment.MEDIA_MOUNTED_READ_ONLY) return null
            
            val total: Long
            val used: Long
            try {
                val stats = StatFs(path)
                total = stats.totalBytes
                used = total - stats.availableBytes
            } catch (e: SecurityException) {
                if (path.contains("media_rw") || path.contains("/mnt/media_rw")) {
                    Log.w(TAG, "SELinux blocked StatFs for $path â€” USB drive inaccessible on this platform.")
                    // On Amazon FireOS, flag for Shizuku guidance card
                    if (za.kilowatch.ultimatefilemanager.util.DeviceUtils.isAmazonDevice(context)) {
                        usbSelinuxBlocked = true
                    }
                }
                return null
            } catch (e: Exception) {
                return null
            }

            // Same Shield USB heuristic as volumeToStorageItem(): some TV firmware marks
            // a USB dongle as isPrimary=true, isRemovable=false. Detect it by path + UUID.
            val looksLikeUsb = path.contains("/mnt/media_rw/", ignoreCase = true)
                            || path.contains("/mnt/usb",        ignoreCase = true)
                            || path.contains("/storage/usb",    ignoreCase = true)
            val hasUuid = volume.uuid != null
            val treatAsRemovable = volume.isRemovable || (looksLikeUsb && hasUuid)

            val label = volume.getDescription(context)
            val icon = StorageItem.iconForType(treatAsRemovable, label)
            
            return StorageItem(
                id = volume.uuid ?: "internal",
                label = label,
                iconRes = icon,
                totalBytes = total,
                usedBytes = used,
                mountPath = path,
                isRemovable = treatAsRemovable
            )
        }

        private fun scanExtraPaths(context: Context, items: MutableList<StorageItem>, discovered: Set<String>) {
            val commonPaths = arrayOf("/mnt/media_rw", "/storage", "/mnt/usb", "/mnt/sda", "/mnt/sdb")
            for (root in commonPaths) {
                val rootFile = File(root)
                if (!rootFile.exists() || !rootFile.isDirectory) continue
                rootFile.listFiles()?.forEach { file ->
                    if (file.isDirectory && !discovered.contains(file.absolutePath)) {
                        // Skip system/hidden folders
                        if (file.name.startsWith(".") || file.name == "self" || file.name == "emulated") return@forEach
                        
                        try {
                            val stats = StatFs(file.absolutePath)
                            if (stats.totalBytes > 0) {
                                items.add(StorageItem(
                                    id = file.absolutePath,
                                    label = context.getString(R.string.usb_drive_filename),
                                    iconRes = R.drawable.ic_storage_usb,
                                    totalBytes = stats.totalBytes,
                                    usedBytes = stats.totalBytes - stats.availableBytes,
                                    mountPath = file.absolutePath,
                                    isRemovable = true
                                ))
                            }
                        } catch (e: SecurityException) {
                            if (root.contains("media_rw") && za.kilowatch.ultimatefilemanager.util.DeviceUtils.isAmazonDevice(context)) {
                                Log.w(TAG, "SELinux blocked access to " + file.absolutePath + " on FireOS. USB drives at /mnt/media_rw/ restricted by platform policy.")
                                usbSelinuxBlocked = true
                            }
                        } catch (_: Exception) { }
                    }
                }
            }
        }

        /**
         * Static utility that builds a complete snapshot of ALL tile types that
         * can exist on the main screen â€” physical storage, network shares, online
         * storages, paired devices, and all feature shortcut tiles.  Used by
         * [CustomTileActivity] to resolve child tile IDs to full [StorageItem]s.
         */
        // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
        //  IMPORTANT: Every new tile added to loadStorageVolumes() MUST also
        //  be added here, otherwise it will NOT render inside custom tiles.
        //  See CLAUDE.md â†’ "Main Menu Tile Registration Checklist" for details.
        // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
        /**
         * Static utility that builds a complete snapshot of ALL tile types that
         * can exist on the main screen â€” physical storage, network shares, online
         * storages, paired devices, favorites, APK extracts, recycle bin, and all
         * feature shortcut tiles.  Used by [CustomTileActivity] to resolve child
         * tile IDs to full [StorageItem]s.
         */
        fun buildAllKnownTiles(context: Context): List<StorageItem> {
            val items = getConnectedStorages(context, localOnly = false).toMutableList()
            val isTv = za.kilowatch.ultimatefilemanager.util.DeviceUtils.isTvDevice(context)

            // â”€â”€ Online Storages (individual accounts) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            val onlineRepo = za.kilowatch.ultimatefilemanager.network.OnlineStorageRepository.getInstance(context)
            for (storage in onlineRepo.getAll()) {
                if (storage.isCredentialsStripped) continue
                items.add(StorageItem(id = storage.id, label = storage.displayName, iconRes = R.drawable.ic_cloud, totalBytes = 0, usedBytes = 0, mountPath = storage.email, isOnlineStorage = true, onlineStorage = storage, subtitle = storage.email))
            }

            // â”€â”€ Network Shares (individual SMB/FTP connections) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            val shareRepo = za.kilowatch.ultimatefilemanager.network.NetworkShareRepository.getInstance(context)
            for (share in shareRepo.getAll()) {
                if (share.isCredentialsStripped) continue
                items.add(StorageItem(id = share.id, label = share.name, iconRes = R.drawable.ic_network, totalBytes = 0, usedBytes = 0, mountPath = share.docIdPrefix, isNetworkRoot = true, networkShare = share))
            }

            // ── Paired Devices (individual entries) ────────────────────────────
            val pairingManager = za.kilowatch.ultimatefilemanager.network.PairingManager.getInstance(context)
            for (device in pairingManager.getAllPairedDevices()) {
                if (device.isConnected) {
                    val iconRes = if (device.isTv) R.drawable.ic_remote_manage else R.drawable.ic_phone
                    items.add(StorageItem(id = "tv_${device.deviceId}", label = device.name.ifEmpty { if (device.isTv) context.getString(R.string.connected_tv) else context.getString(R.string.connected_phone) }, iconRes = iconRes, totalBytes = 0, usedBytes = 0, mountPath = "tv://${device.deviceId}", isNetworkRoot = true))
                }
            }

            // ── Favorites ──────────────────────────────────────────────────
            val favorites = za.kilowatch.ultimatefilemanager.settings.FavoritesManager.getFavorites(context)
            for (fav in favorites) {
                val netShare: za.kilowatch.ultimatefilemanager.network.NetworkShare? = if (fav.isNetwork && fav.shareId != null) {
                    if (fav.shareId.startsWith("tv_")) {
                        val deviceId = fav.shareId.removePrefix("tv_")
                        val device = pairingManager.getAllPairedDevices().find { it.deviceId == deviceId }
                        if (device != null) {
                            za.kilowatch.ultimatefilemanager.network.NetworkShare(
                                id = device.deviceId,
                                name = device.name,
                                type = za.kilowatch.ultimatefilemanager.network.ShareType.TV,
                                host = device.lastIp,
                                port = device.lastPort,
                                readOnly = false
                            )
                        } else null
                    } else {
                        val net = shareRepo.getById(fav.shareId)
                        if (net != null) {
                            net
                        } else {
                            val online = onlineRepo.getById(fav.shareId)
                            if (online != null) {
                                za.kilowatch.ultimatefilemanager.network.NetworkShare(
                                    id = online.id,
                                    name = online.displayName,
                                    type = when (online.provider) {
                                        za.kilowatch.ultimatefilemanager.network.OnlineStorageProvider.ONEDRIVE -> za.kilowatch.ultimatefilemanager.network.ShareType.ONEDRIVE
                                        za.kilowatch.ultimatefilemanager.network.OnlineStorageProvider.GOOGLE_DRIVE -> za.kilowatch.ultimatefilemanager.network.ShareType.GOOGLE_DRIVE
                                        za.kilowatch.ultimatefilemanager.network.OnlineStorageProvider.DROPBOX -> za.kilowatch.ultimatefilemanager.network.ShareType.DROPBOX
                                        za.kilowatch.ultimatefilemanager.network.OnlineStorageProvider.AWS_S3 -> za.kilowatch.ultimatefilemanager.network.ShareType.AWS_S3
                                        za.kilowatch.ultimatefilemanager.network.OnlineStorageProvider.IDRIVE_E2 -> za.kilowatch.ultimatefilemanager.network.ShareType.IDRIVE_E2
                                        za.kilowatch.ultimatefilemanager.network.OnlineStorageProvider.WEBDAV -> za.kilowatch.ultimatefilemanager.network.ShareType.WEBDAV
                                        za.kilowatch.ultimatefilemanager.network.OnlineStorageProvider.RCLONE -> za.kilowatch.ultimatefilemanager.network.ShareType.WEBDAV
                                    },
                                    host = when (online.provider) {
                                        za.kilowatch.ultimatefilemanager.network.OnlineStorageProvider.RCLONE -> za.kilowatch.ultimatefilemanager.network.RCloneShareClient.RCLONE_HOST_MARKER
                                        else -> if (online.isWebDavProvider) online.webDavUrl ?: online.email else online.s3Endpoint ?: online.email
                                    },
                                    port = 0,
                                    username = when (online.provider) {
                                        za.kilowatch.ultimatefilemanager.network.OnlineStorageProvider.RCLONE -> online.id
                                        else -> if (online.isWebDavProvider) online.webDavUsername ?: "" else online.s3AccessKey ?: ""
                                    },
                                    password = if (online.isWebDavProvider) online.webDavPassword ?: "" else online.s3SecretKey ?: "",
                                    remotePath = "/",
                                    readOnly = false
                                )
                            } else null
                        }
                    }
                } else null

                items.add(
                    StorageItem(
                        id = fav.id,
                        label = fav.label,
                        iconRes = R.drawable.ic_star,
                        totalBytes = 0,
                        usedBytes = 0,
                        mountPath = "",
                        isFavoriteTile = true,
                        favoritePath = fav.path,
                        favoriteIsFolder = fav.isFolder,
                        favoriteIsNetwork = fav.isNetwork,
                        networkShare = netShare
                    )
                )
            }

            // â”€â”€ Feature shortcut tiles â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            items.add(StorageItem(id = "twin_window_tile", label = context.getString(R.string.twin_window_title), iconRes = R.drawable.ic_twin_window, totalBytes = 0, usedBytes = 0, mountPath = "", isTwinWindowTile = true))
            items.add(StorageItem(id = "notepad_tile", label = context.getString(R.string.notepad), iconRes = R.drawable.ic_notepad, totalBytes = 0, usedBytes = 0, mountPath = "", isNotepadTile = true, subtitle = context.getString(R.string.notepad_tile_subtitle)))
            if (!isTv) {
                items.add(StorageItem(id = "scanner_tile", label = context.getString(R.string.scanner_title), iconRes = R.drawable.ic_scanner, totalBytes = 0, usedBytes = 0, mountPath = "", isScannerTile = true, subtitle = context.getString(R.string.scanner_tile_subtitle)))
            }
            items.add(StorageItem(id = "apps_tile", label = context.getString(R.string.perm_query_apps_title), iconRes = R.drawable.ic_apps, totalBytes = 0, usedBytes = 0, mountPath = "", isAppsTile = true))
            items.add(StorageItem(id = "remote_tile", label = context.getString(R.string.remote_manage_btn), iconRes = R.drawable.ic_remote_manage, totalBytes = 0, usedBytes = 0, mountPath = "", isRemoteTile = true))
            items.add(StorageItem(id = "search_tile", label = context.getString(R.string.search_title), iconRes = R.drawable.ic_search, totalBytes = 0, usedBytes = 0, mountPath = "", isSearchTile = true))
            items.add(StorageItem(id = "analyzer_tile", label = context.getString(R.string.analyzer_title), iconRes = R.drawable.ic_analyzer, totalBytes = 0, usedBytes = 0, mountPath = "", isAnalyzerTile = true))
            items.add(StorageItem(id = "smart_sort_tile", label = context.getString(R.string.smart_sort_title), iconRes = R.drawable.ic_sort, totalBytes = 0, usedBytes = 0, mountPath = "", isSmartSortTile = true))
            items.add(StorageItem(id = "vault_tile", label = context.getString(R.string.vault_title), iconRes = R.drawable.ic_lock, totalBytes = 0, usedBytes = 0, mountPath = "", isVaultTile = true))

            // APK Extracts (only if folder exists and non-empty)
            val extractsDir = java.io.File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), "UFM-Extracted")
            if (extractsDir.exists() && extractsDir.listFiles()?.isNotEmpty() == true) {
                items.add(StorageItem(id = "extracts_tile", label = context.getString(R.string.apk_xapk_extracts), iconRes = R.drawable.ic_apps, totalBytes = 0, usedBytes = 0, mountPath = extractsDir.absolutePath, isExtractsTile = true))
            }

            // Recycle Bin (only if enabled)
            if (za.kilowatch.ultimatefilemanager.recycle.RecycleBinSettingsManager.isEnabled(context)) {
                items.add(StorageItem(id = "recycle_bin_tile", label = context.getString(R.string.recycle_bin_title), iconRes = R.drawable.ic_delete, totalBytes = 0, usedBytes = 0, mountPath = "", isRecycleBinTile = true))
            }

            items.add(StorageItem(id = "paired_devices_tile", label = if (isTv) context.getString(R.string.paired_phones_1) else context.getString(R.string.paired_tvs_1), iconRes = R.drawable.ic_tv, totalBytes = 0, usedBytes = 0, mountPath = "", isPairedDevicesTile = true))
            if (!isTv) {
                items.add(StorageItem(id = "tv_remote_tile", label = context.getString(R.string.tv_remote), iconRes = R.drawable.ic_tv_remote, totalBytes = 0, usedBytes = 0, mountPath = "", isTvRemoteTile = true, subtitle = context.getString(R.string.tv_remote_subtitle)))
            }
            items.add(StorageItem(id = "terminal_tile", label = context.getString(R.string.adb_terminal_title), iconRes = R.drawable.ic_terminal, totalBytes = 0, usedBytes = 0, mountPath = "", isTerminalTile = true))
            items.add(StorageItem(id = "shizuku_tile", label = context.getString(R.string.shizuku_title), iconRes = R.drawable.ic_shizuku_logo, totalBytes = 0, usedBytes = 0, mountPath = "", isShizukuTile = true, subtitle = context.getString(R.string.shizuku_subtitle)))
            items.add(StorageItem(id = "network_tile", label = context.getString(R.string.network_tile_title), iconRes = R.drawable.ic_network, totalBytes = 0, usedBytes = 0, mountPath = "", isNetworkTile = true))
            items.add(StorageItem(id = "online_storages_tile", label = context.getString(R.string.online_storages_title), iconRes = R.drawable.ic_cloud, totalBytes = 0, usedBytes = 0, mountPath = "", isOnlineStoragesTile = true))
            if (!isTv) {
                items.add(StorageItem(id = "sync_tile", label = context.getString(R.string.sync_title), iconRes = R.drawable.ic_sync, totalBytes = 0, usedBytes = 0, mountPath = "", isSyncTile = true))
            }
            if (!isTv) {
                items.add(StorageItem(id = "advanced_sync_tile", label = context.getString(R.string.advanced_sync_title), iconRes = R.drawable.ic_sync_advanced, totalBytes = 0, usedBytes = 0, mountPath = "", isAdvancedSyncTile = true))
            }
            items.add(StorageItem(id = "file_server_tile", label = context.getString(R.string.file_server_title), iconRes = R.drawable.ic_file_server, totalBytes = 0, usedBytes = 0, mountPath = "", isFileServerTile = true))
            items.add(StorageItem(id = "add_storage_location_tile", label = context.getString(R.string.add_storage_location_title), iconRes = R.drawable.ic_folder, totalBytes = 0, usedBytes = 0, mountPath = "", isAddStorageLocationTile = true, subtitle = context.getString(R.string.add_storage_location_subtitle)))
            items.add(StorageItem(id = "settings_tile", label = context.getString(R.string.font_size_title), iconRes = R.drawable.ic_font_size, totalBytes = 0, usedBytes = 0, mountPath = "", isSettingsTile = true))
            items.add(StorageItem(id = "legal_tile", label = context.getString(R.string.policy_selection_title), iconRes = R.drawable.ic_policy, totalBytes = 0, usedBytes = 0, mountPath = "", isLegalTile = true))
            items.add(StorageItem(id = "rate_us_tile", label = context.getString(R.string.rate_us_title), iconRes = R.drawable.ic_star, totalBytes = 0, usedBytes = 0, mountPath = "", isRateUsTile = true))
            items.add(StorageItem(id = "tip_jar_tile", label = context.getString(R.string.tip_jar_title), iconRes = R.drawable.ic_coffee, totalBytes = 0, usedBytes = 0, mountPath = "", isTipJarTile = true))
            items.add(StorageItem(id = "support_tile", label = context.getString(R.string.support_title), iconRes = R.drawable.ic_support, totalBytes = 0, usedBytes = 0, mountPath = "", isSupportTile = true))
            items.add(StorageItem(id = "about_tile", label = context.getString(R.string.about_title), iconRes = R.drawable.ic_about, totalBytes = 0, usedBytes = 0, mountPath = "", isAboutTile = true))

            val safLocations = SafLocationRepository.getLocations(context)
            for (loc in safLocations) {
                val iconRes = if (loc.iconType == "terminal" || loc.authority.contains("termux")) R.drawable.ic_terminal else R.drawable.ic_folder

                items.add(StorageItem(
                    id = "saf_location_${loc.id}",
                    label = loc.displayName,
                    iconRes = iconRes,
                    totalBytes = 0,
                    usedBytes = 0,
                    mountPath = "saf://${loc.id}",
                    isSafCustomLocation = true,
                    safLocation = loc,
                    colorConfig = TileColorConfig(),
                    subtitle = context.getString(R.string.saf_storage)
                ))
            }

            return items
        }
    }

    // StorageVolume callback for API 30+
    private var storageVolumeCallback: Any? = null

    // Mobile drag-and-drop
    private lateinit var itemTouchHelper: ItemTouchHelper

    private lateinit var btnManageTiles: android.widget.ImageView
    private var btnColorTile: android.widget.ImageView? = null
    private var btnImportColorCode: android.widget.ImageView? = null
    private var toolbar: MaterialToolbar? = null
    private var btnDoneTv: com.google.android.material.button.MaterialButton? = null
    private var draggedItem: StorageItem? = null

    private var btnAddCustomTile: android.widget.ImageView? = null
    private var btnSettingsGear: android.widget.ImageView? = null

    private var isEditMode = false

    // TV D-Pad reorder mode
    private var reorderModeItemId: String? = null
    private var reorderModeOriginalList: List<StorageItem>? = null

    // Snapshot of all tiles (even hidden ones) for the Manage Tiles sheet
    private var lastFullTileList: List<StorageItem> = emptyList()

    private val tileColorTvLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val data = result.data ?: return@registerForActivityResult
                val config = TileColorConfig(
                    ringColor   = data.getIntExtra(TileColorTvActivity.RESULT_RING_COLOR,   android.graphics.Color.TRANSPARENT),
                    iconColor   = data.getIntExtra(TileColorTvActivity.RESULT_ICON_COLOR,   android.graphics.Color.TRANSPARENT),
                    iconBgColor = data.getIntExtra(TileColorTvActivity.RESULT_ICON_BG,      android.graphics.Color.TRANSPARENT),
                    tileBgColor = data.getIntExtra(TileColorTvActivity.RESULT_TILE_BG,      android.graphics.Color.TRANSPARENT),
                    labelColor  = data.getIntExtra(TileColorTvActivity.RESULT_LABEL_COLOR,  android.graphics.Color.TRANSPARENT)
                )
                val tileId = TvTileDataHolder.sourceTileId
                TileColorManager.saveTileColor(this, tileId, config)
                storageAdapter.setTileColors(TileColorManager.loadTileColors(this))
                storageAdapter.setTileIcons(TileIconManager.getAllTileIcons(this))
        storageAdapter.setTileIconRes(TileIconManager.getAllTileIconRes(this))
                loadStorageVolumes()  // re-read full list to include any TV icon changes
            }
        }

    private val tvTileCopyLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                storageAdapter.setTileColors(TileColorManager.loadTileColors(this))
                storageAdapter.setTileIcons(TileIconManager.getAllTileIcons(this))
                storageAdapter.setTileIconRes(TileIconManager.getAllTileIconRes(this))
            }
        }

    private val addStorageLocationLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri != null) {
                handleSafLocationPickerResult(uri)
            }
        }

    private fun handleSafLocationPickerResult(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            contentResolver.takePersistableUriPermission(uri, flags)
        } catch (e: Exception) {
            za.kilowatch.ultimatefilemanager.util.GoRoLog.w("StorageBrowser", "Failed to take persistable permission: ${e.message}")
        }

        val authority = uri.authority ?: ""
        val isTermux = authority.contains("termux")
        val defaultName = when {
            isTermux -> getString(R.string.storage_location_termux_default_name)
            else -> {
                val doc = androidx.documentfile.provider.DocumentFile.fromTreeUri(this, uri)
                doc?.name ?: getString(R.string.storage_location_saf_default_name)
            }
        }
        val defaultIcon = if (isTermux) "terminal" else "folder"

        showAddStorageLocationNameDialog(uri, authority, defaultName, defaultIcon)
    }

    private fun showAddStorageLocationNameDialog(uri: Uri, authority: String, defaultName: String, defaultIcon: String) {
        val layoutRes = if (isTv) R.layout.dialog_add_storage_location_tv else R.layout.dialog_add_storage_location
        val dialogView = layoutInflater.inflate(layoutRes, null)
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        val imgIcon = dialogView.findViewById<ImageView>(R.id.imgStorageLocationIcon)
        val edtName = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.edtStorageLocationName)
        val btnCancel = dialogView.findViewById<View>(R.id.btnCancelStorageLocation)
        val btnSave = dialogView.findViewById<View>(R.id.btnSaveStorageLocation)

        val iconRes = if (defaultIcon == "terminal" || authority.contains("termux")) R.drawable.ic_terminal else R.drawable.ic_folder
        imgIcon?.setImageResource(iconRes)
        edtName?.setText(defaultName)
        edtName?.selectAll()

        btnCancel?.setOnClickListener { dialog.dismiss() }
        btnSave?.setOnClickListener {
            val name = edtName?.text?.toString()?.trim().orEmpty().ifEmpty { defaultName }
            val docId = try {
                android.provider.DocumentsContract.getTreeDocumentId(uri)
            } catch (_: Exception) { "" }

            val newLocation = SafLocation(
                displayName = name,
                treeUriString = uri.toString(),
                authority = authority,
                rootDocId = docId,
                iconType = defaultIcon
            )

            val success = SafLocationRepository.addLocation(this, newLocation)
            dialog.dismiss()
            if (success) {
                showPremiumSnackbar(getString(R.string.add_storage_success, name))
                loadStorageVolumes()
            } else {
                showPremiumSnackbar(getString(R.string.add_storage_failed, name))
            }
        }

        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        edtName?.requestFocus()
    }

    private fun showRenameStorageLocationDialog(item: StorageItem) {
        val location = item.safLocation ?: return
        val layoutRes = if (isTv) R.layout.dialog_add_storage_location_tv else R.layout.dialog_add_storage_location
        val dialogView = layoutInflater.inflate(layoutRes, null)
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        val txtTitle = dialogView.findViewById<TextView>(R.id.txtStorageLocationDialogTitle)
        val txtSubtitle = dialogView.findViewById<TextView>(R.id.txtStorageLocationDialogSubtitle)
        val imgIcon = dialogView.findViewById<ImageView>(R.id.imgStorageLocationIcon)
        val edtName = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.edtStorageLocationName)
        val btnCancel = dialogView.findViewById<View>(R.id.btnCancelStorageLocation)
        val btnSave = dialogView.findViewById<android.widget.TextView>(R.id.btnSaveStorageLocation)

        txtTitle?.setText(R.string.rename_title)
        txtSubtitle?.visibility = View.GONE
        val iconRes = if (location.iconType == "terminal" || location.authority.contains("termux")) R.drawable.ic_terminal else R.drawable.ic_folder
        imgIcon?.setImageResource(iconRes)
        edtName?.setText(location.displayName)
        edtName?.selectAll()
        btnSave?.setText(R.string.save)

        btnCancel?.setOnClickListener { dialog.dismiss() }
        btnSave?.setOnClickListener {
            val name = edtName?.text?.toString()?.trim().orEmpty()
            if (name.isNotEmpty()) {
                location.displayName = name
                SafLocationRepository.updateLocation(this, location)
                loadStorageVolumes()
            }
            dialog.dismiss()
        }

        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        edtName?.requestFocus()
    }

    private fun showRemoveStorageLocationDialog(item: StorageItem) {
        val location = item.safLocation ?: return
        val layoutRes = if (isTv) R.layout.dialog_remove_storage_location_confirm_tv else R.layout.dialog_remove_storage_location_confirm
        val dialogView = layoutInflater.inflate(layoutRes, null)
        val txtDeleteMessage = dialogView.findViewById<TextView>(R.id.txtDeleteMessage)
        val btnDeleteConfirm = dialogView.findViewById<View>(R.id.btnDeleteConfirm)
        val btnCancel = dialogView.findViewById<View>(R.id.btnCancel)

        txtDeleteMessage?.text = getString(R.string.remove_storage_location_msg, location.displayName)

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnDeleteConfirm?.setOnClickListener {
            dialog.dismiss()
            SafLocationRepository.removeLocation(this, location.id)
            try {
                contentResolver.releasePersistableUriPermission(
                    android.net.Uri.parse(location.treeUriString),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {}

            val hidden = TileOrderManager.loadHidden(this).toMutableSet()
            hidden.remove(item.id)
            val parentMap = TileOrderManager.loadHiddenParents(this).toMutableMap()
            parentMap.remove(item.id)
            TileOrderManager.saveHidden(this, hidden, parentMap)
            val order = TileOrderManager.load(this).toMutableList()
            order.remove(item.id)
            TileOrderManager.save(this, order)

            showPremiumSnackbar(getString(R.string.storage_location_removed))
            loadStorageVolumes()
        }

        btnCancel?.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }
    
    private var lottieEmptyStorage: com.airbnb.lottie.LottieAnimationView? = null

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        isTv = DeviceUtils.isTvDevice(this)
        isAmazon = DeviceUtils.isAmazonDevice(this)
        if (isTv) {
            setContentView(R.layout.activity_storage_browser_tv)
        } else {
            setContentView(R.layout.activity_storage_browser)
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Picker mode configuration
        isPickerMode = intent.getBooleanExtra(za.kilowatch.ultimatefilemanager.storage.FileBrowserActivity.EXTRA_PICKER_MODE, false)
        isKeyfilePickerMode = intent.getBooleanExtra(EXTRA_KEYFILE_PICKER, false)
        isCertPickerMode = intent.getBooleanExtra(EXTRA_CERT_PICKER, false)
        pickerExtensions = intent.getStringExtra(za.kilowatch.ultimatefilemanager.storage.FileBrowserActivity.EXTRA_PICKER_EXTENSIONS)
        isSyncFolderPickerMode = intent.getBooleanExtra(EXTRA_SYNC_FOLDER_PICKER, false)
        isAdvancedSyncFolderPickerMode = intent.getBooleanExtra(EXTRA_ADVANCED_SYNC_FOLDER_PICKER, false)
        isAdvancedSyncDestPickerMode = intent.getBooleanExtra(EXTRA_ADVANCED_SYNC_DEST_PICKER, false)
        isCompressDestPickerMode = intent.getBooleanExtra(EXTRA_COMPRESS_DEST_PICKER, false)
        isExtractDestPickerMode = intent.getBooleanExtra(FileBrowserActivity.EXTRA_EXTRACT_DEST_PICKER, false)
        isImageCompressDestPickerMode = intent.getBooleanExtra(EXTRA_IMAGE_COMPRESS_DEST_PICKER, false)
        isGifCreatorDestPickerMode = intent.getBooleanExtra(EXTRA_GIF_CREATOR_DEST_PICKER, false)
        isDrivePicker = intent.getBooleanExtra(EXTRA_DRIVE_PICKER, false)
        isLocationPickerMode = intent.getBooleanExtra(EXTRA_LOCATION_PICKER, false)
        isSearchFolderPicker = intent.getBooleanExtra(EXTRA_SEARCH_FOLDER_PICKER, false)
        isNetworkCachePickerMode = intent.getBooleanExtra(EXTRA_NETWORK_CACHE_PICKER, false)
        isQuickTransferPickerMode = intent.getBooleanExtra(FileBrowserActivity.EXTRA_QUICK_TRANSFER_PICKER, false)
        isShareDestPickerMode = intent.getBooleanExtra(EXTRA_SHARE_DEST_PICKER, false)
        isNotepadFolderPicker = intent.getBooleanExtra(FileBrowserActivity.EXTRA_NOTEPAD_FOLDER_PICKER, false)
        isScannerFolderPicker = intent.getBooleanExtra(FileBrowserActivity.EXTRA_SCANNER_FOLDER_PICKER, false)
        isAutoBackupFolderPicker = intent.getBooleanExtra(FileBrowserActivity.EXTRA_AUTO_BACKUP_FOLDER_PICKER, false)
        isSupportAttachmentPicker = intent.getBooleanExtra(FileBrowserActivity.EXTRA_SUPPORT_ATTACHMENT_PICKER, false)
        isTileIconPickerMode = intent.getBooleanExtra(EXTRA_TILE_ICON_PICKER, false)

        if (isTileIconPickerMode) {
            isPickerMode = true  // reuse picker routing
            pickerExtensions = "ico,png"
        }

        setupViews()
        loadStorageVolumes()
        registerStorageReceiver()
        registerStorageVolumeCallback()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter("za.kilowatch.ufm.PAIRING_UPDATED")
        Log.d(TAG, "Registering pairing update receiver")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(updateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(updateReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(updateReceiver)
    }

    private var hasShownReviewPopupThisSession = false
    private var hasCheckedAutoBackupRestoreThisSession = false
    private var hasCheckedCrashReportThisSession = false
    /** Timestamp of the last background device-ping pass. Prevents hammering the OEM
     *  Kumiho telemetry hook (and the network) every time onResume fires. */
    private var lastDevicePingMs = 0L
    private val DEVICE_PING_INTERVAL_MS = 30_000L

    override fun onResume() {
        super.onResume()
        // Reload in onResume to pick up new network shares or USB mounts instantly
        loadStorageVolumes()
        Log.d(TAG, "onResume: Refreshing storage volumes")
        applyViewMode()
        applyDynamicThemeColors()

        
        // Background check: ping TV devices and refresh UI if online status changes.
        // Debounced to at most once per DEVICE_PING_INTERVAL_MS to prevent the OEM
        // Kumiho telemetry hook from firing on every resume (e.g. dialog dismiss, focus change).
        val now = System.currentTimeMillis()
        if (now - lastDevicePingMs >= DEVICE_PING_INTERVAL_MS) {
            lastDevicePingMs = now
            lifecycleScope.launch(Dispatchers.IO) {
                val pairingManager = za.kilowatch.ultimatefilemanager.network.PairingManager.getInstance(this@StorageBrowserActivity)
                val devices = pairingManager.getAllPairedDevices()
                var changed = false
                for (device in devices) {
                    val wasConnected = device.isConnected

                    // 1. Explicit Manual Override: User-triggered disconnects MUST be honored first.
                    // This prevents background pings from auto-reconnecting a device the user chose to disconnect.
                    if (device.manuallyDisconnected) {
                        if (wasConnected) {
                            device.isConnected = false
                            pairingManager.addOrUpdateDevice(device)
                            changed = true
                        }
                        continue
                    }

                    // 2. Network Check: Only ping if not manually disconnected.
                    val isOnline = pairingManager.pingDevice(device)
                    if (wasConnected != isOnline) {
                        device.isConnected = isOnline
                        pairingManager.addOrUpdateDevice(device)
                        changed = true
                    }
                }
                if (changed) {
                    withContext(Dispatchers.Main) {
                        loadStorageVolumes()
                    }
                }
            }
        }

        // Show "Rate Us" popup if eligible
        if (!hasShownReviewPopupThisSession) {
            val shouldShow = za.kilowatch.ultimatefilemanager.util.ReviewPrefs.shouldShowPopup(this)
            android.util.Log.d("GoRoRating", "StorageBrowserActivity onResume: Checking eligibility. result=$shouldShow")
            if (shouldShow) {
                hasShownReviewPopupThisSession = true
                za.kilowatch.ultimatefilemanager.util.ReviewUiHelper.showReviewPopup(this)
            }
        }

        // Check for auto-backup restore (first boot detection)
        if (!hasCheckedAutoBackupRestoreThisSession) {
            hasCheckedAutoBackupRestoreThisSession = true
            checkAutoBackupRestore()
        }

        // Check for pending crash/ANR report and offer to submit it
        if (!hasCheckedCrashReportThisSession) {
            hasCheckedCrashReportThisSession = true
            CrashReportDialogHelper.maybeShowCrashReportDialog(this, lifecycleScope)
        }
    }

    // â”€â”€ Auto-Backup Restore Detection â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private fun checkAutoBackupRestore() {
        val ctx = this

        // If the flag doesn't exist yet, this is the first boot after install/upgrade.
        // Set the flag now, then fall through to check if we need to show the dialog.
        val prefs = ctx.getSharedPreferences("auto_backup_prefs", Context.MODE_PRIVATE)
        if (!prefs.contains("backup_files_present_on_first_boot")) {
            val configExists = AutoBackupPrefs.getConfigFile(ctx).exists()
            val themeExists = AutoBackupPrefs.getThemeFile(ctx).exists()
            AutoBackupPrefs.setBackupFilesPresentOnFirstBoot(ctx, configExists || themeExists)
        }

        // If files were not present on first boot, nothing to restore
        if (!AutoBackupPrefs.isBackupFilesPresentOnFirstBoot(ctx)) return

        // If prompt was already shown, don't show again
        if (AutoBackupPrefs.isRestorePromptShown(ctx)) return

        // Show the restore dialog
        showBackupRestoreDialog()
    }

    private fun showBackupRestoreDialog() {
        val ctx = this
        val configExists = AutoBackupPrefs.getConfigFile(this).exists()
        val themeExists = AutoBackupPrefs.getThemeFile(this).exists()

        val detectedItems = mutableListOf<String>()
        if (configExists) detectedItems.add("• " + getString(R.string.auto_restore_detected_config))
        if (themeExists) detectedItems.add("• " + getString(R.string.auto_restore_detected_theme))

        val dialogView = layoutInflater.inflate(R.layout.dialog_auto_restore_offer, null)
        val txtDetected = dialogView.findViewById<TextView>(R.id.txtAutoRestoreDetected)
        txtDetected.text = detectedItems.joinToString("\n")

        val btnRestore = dialogView.findViewById<View>(R.id.btnAutoRestore)
        val btnSkip = dialogView.findViewById<View>(R.id.btnAutoRestoreSkip)

        val dialog = MaterialAlertDialogBuilder(ctx, R.style.UFM_Dialog)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        btnRestore.setOnClickListener {
            dialog.dismiss()
            performAutoBackupRestore(configExists, themeExists)
        }

        btnSkip.setOnClickListener {
            dialog.dismiss()
            AutoBackupPrefs.setRestorePromptShown(ctx)
        }

        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    private fun performAutoBackupRestore(configExists: Boolean, themeExists: Boolean) {
        val ctx = this@StorageBrowserActivity

        // Check if any file needs a password â€” read formats on IO thread
        lifecycleScope.launch(Dispatchers.IO) {
            var needsPassword = false
            if (configExists) {
                val configFile = AutoBackupPrefs.getConfigFile(this@StorageBrowserActivity)
                val cb = configFile.readBytes()
                val fmt = SettingsBackupManager.detectFormat(cb)
                if (fmt == SettingsBackupManager.BackupFormat.V3_ENCRYPTED) needsPassword = true
            }
            if (!needsPassword && themeExists) {
                val themeFile = AutoBackupPrefs.getThemeFile(this@StorageBrowserActivity)
                val tb = themeFile.readBytes()
                val fmt = ThemePackManager.detectFormat(tb)
                if (fmt == ThemePackManager.ThemePackFormat.V2_ENCRYPTED) needsPassword = true
            }

            if (needsPassword) {
                withContext(Dispatchers.Main) {
                    showSingleRestorePasswordDialog { password ->
                        doAutoBackupRestore(password, configExists, themeExists)
                    }
                }
            } else {
                doAutoBackupRestore(null, configExists, themeExists)
            }
        }
    }

    private fun showSingleRestorePasswordDialog(onPassword: (String) -> Unit) {
        val isTv = DeviceUtils.isTvDevice(this)
        val dialogView = LayoutInflater.from(this).inflate(
            if (isTv) R.layout.dialog_backup_import_password_tv
            else R.layout.dialog_backup_import_password,
            null
        )

        val edtPassword = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.edtPassword)
        val btnDecrypt = dialogView.findViewById<Button>(R.id.btnDecrypt)

        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))

        btnDecrypt.setOnClickListener {
            val pw = edtPassword.text?.toString() ?: ""
            if (pw.isEmpty()) return@setOnClickListener
            dialog.dismiss()
            onPassword(pw)
        }

        dialog.show()

        if (isTv) {
            val yellow = getColor(R.color.tv_button_focused_yellow)
            val black = getColor(R.color.tv_button_focused_yellow_text)
            btnDecrypt.backgroundTintList = android.content.res.ColorStateList.valueOf(yellow)
            btnDecrypt.setTextColor(black)
            btnDecrypt.setOnFocusChangeListener { _, hasFocus ->
                btnDecrypt.backgroundTintList =
                    if (hasFocus) android.content.res.ColorStateList.valueOf(getColor(R.color.tv_button_focused_yellow))
                    else android.content.res.ColorStateList.valueOf(yellow)
            }
            btnDecrypt.requestFocus()
        }
    }

    private fun doAutoBackupRestore(password: String?, configExists: Boolean, themeExists: Boolean) {
        val ctx = this@StorageBrowserActivity

        lifecycleScope.launch(Dispatchers.IO) {
            // Theme first (no restart needed)
            if (themeExists) {
                try {
                    val themeFile = AutoBackupPrefs.getThemeFile(this@StorageBrowserActivity)
                    val (success, overrides) = ThemePackManager.performImport(ctx, themeFile, password)
                    if (success && overrides.isNotEmpty()) {
                        ThemePackManager.applyOverrides(ctx, overrides)
                        withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(ctx, R.string.auto_restore_success_theme, android.widget.Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(ctx, R.string.auto_restore_error_theme, android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: javax.crypto.AEADBadTagException) {
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(ctx, ctx.getString(R.string.backup_import_wrong_password, 0), android.widget.Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(ctx, R.string.auto_restore_error_theme, android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }

            // Then config (triggers restart)
            if (configExists) {
                try {
                    val configFile = AutoBackupPrefs.getConfigFile(this@StorageBrowserActivity)
                    val bytes = configFile.readBytes()
                    val plainText = SettingsBackupManager.decryptBackup(bytes, password)
                    val details = SettingsBackupManager.parseBackupContent(ctx, plainText)
                    val success = SettingsBackupManager.performRestore(ctx, details)
                    withContext(Dispatchers.Main) {
                        if (success) {
                            AutoBackupPrefs.setRestorePromptShown(ctx)
                            android.widget.Toast.makeText(ctx, R.string.auto_restore_success_config, android.widget.Toast.LENGTH_SHORT).show()
                            val pm = packageManager
                            val launchIntent = pm.getLaunchIntentForPackage(packageName)
                            val mainIntent = android.content.Intent.makeRestartActivityTask(launchIntent?.component)
                            startActivity(mainIntent)
                            java.lang.Runtime.getRuntime().exit(0)
                        } else {
                            android.widget.Toast.makeText(ctx, R.string.auto_restore_error_config, android.widget.Toast.LENGTH_SHORT).show()
                            AutoBackupPrefs.setRestorePromptShown(ctx)
                        }
                    }
                } catch (e: javax.crypto.AEADBadTagException) {
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(ctx, ctx.getString(R.string.backup_import_wrong_password, 0), android.widget.Toast.LENGTH_SHORT).show()
                        AutoBackupPrefs.setRestorePromptShown(ctx)
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(ctx, R.string.auto_restore_error_config, android.widget.Toast.LENGTH_SHORT).show()
                        AutoBackupPrefs.setRestorePromptShown(ctx)
                    }
                }
            } else {
                withContext(Dispatchers.Main) {
                    AutoBackupPrefs.setRestorePromptShown(ctx)
                }
            }
        }
    }


    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // TV: when window regains focus (returning from child screen), focus first item
        if (hasFocus && isTv) {
            recyclerStorage.postDelayed({
                // Try the first child view directly
                val firstChild = recyclerStorage.getChildAt(0)
                if (firstChild != null) {
                    firstChild.requestFocus()
                } else {
                    // Fallback: focus the RecyclerView itself â€” descendantFocusability
                    // will pass it down to the first focusable child
                    recyclerStorage.requestFocus()
                }
            }, 300)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        try {
            unregisterReceiver(storageReceiver)
        } catch (_: Exception) { }
        unregisterStorageVolumeCallback()
    }

    private fun setupViews() {
        recyclerStorage = findViewById(R.id.recyclerStorage)
        layoutEmptyStorage = findViewById(R.id.layoutEmptyStorage)
        lottieEmptyStorage = findViewById(R.id.lottieEmptyStorage)

        btnToggleGrid = findViewById(R.id.btnToggleGrid)
        btnToggleList = findViewById(R.id.btnToggleList)

        btnToggleGrid?.setOnClickListener {
            if (isTv) {
                val currentMode = MainMenuViewModeManager.loadViewMode(this)
                if (currentMode != MainMenuViewModeManager.ViewMode.GRID) {
                    MainMenuViewModeManager.saveViewMode(this, MainMenuViewModeManager.ViewMode.GRID)
                    applyViewMode(animate = true)
                }
                showViewModeOptions(isListView = false)
            } else {
                showViewModeOptions(isListView = false)
            }
        }

        btnToggleList?.setOnClickListener {
            if (isTv) {
                val currentMode = MainMenuViewModeManager.loadViewMode(this)
                if (currentMode != MainMenuViewModeManager.ViewMode.LIST) {
                    MainMenuViewModeManager.saveViewMode(this, MainMenuViewModeManager.ViewMode.LIST)
                    applyViewMode(animate = true)
                }
                showViewModeOptions(isListView = true)
            } else {
                showViewModeOptions(isListView = true)
            }
        }

        if (isTv) {
            btnToggleGrid?.let { setupTvToggleFocus(it) }
            btnToggleList?.let { setupTvToggleFocus(it) }
        }

        // Mobile: wire up the MaterialToolbar
        if (!isTv) {
            toolbar = findViewById<MaterialToolbar>(R.id.toolbar)?.also { tb ->
                setSupportActionBar(tb)
                supportActionBar?.setDisplayShowTitleEnabled(false) // Prevent default title hijacking
            }
            val cardTipJar = findViewById<View>(R.id.cardTipJarProgress)
            if (cardTipJar != null) {
                if (hasShownTipJarThisSession) {
                    cardTipJar.visibility = View.GONE
                } else if (!LoyaltyPrefs.getHasEverBeenOnline(this)) {
                    // Device has never been online — never show the progress card in any session
                    cardTipJar.visibility = View.GONE
                } else if (!LoyaltyPrefs.isTipJarPopupEnabled(this)) {
                    cardTipJar.visibility = View.GONE
                } else {
                    cardTipJar.visibility = View.VISIBLE
                    hasShownTipJarThisSession = true

                    // Show cached progress immediately, then fetch live data from server
                    applyCachedTipJarProgress()
                    fetchTipJarProgress()

                    // Named Runnable so both the dismiss button and the auto-timer
                    // share the same slide-up animation and can cancel each other.
                    val dismissCard = Runnable {
                        val parent = findViewById<android.view.ViewGroup>(R.id.main)
                        if (parent != null) {
                            val transition = android.transition.TransitionSet().apply {
                                addTransition(android.transition.Fade(android.transition.Fade.OUT).apply {
                                    addTarget(cardTipJar)
                                })
                                addTransition(android.transition.Slide(android.view.Gravity.TOP).apply {
                                    addTarget(cardTipJar)
                                })
                                addTransition(android.transition.ChangeBounds())
                                duration = 600
                            }
                            android.transition.TransitionManager.beginDelayedTransition(parent, transition)
                            cardTipJar.visibility = View.GONE
                        }
                    }

                    // ✕ Dismiss button — immediately slides the card away
                    findViewById<View>(R.id.btnDismissTipJar)?.setOnClickListener {
                        handler.removeCallbacks(dismissCard) // cancel the auto-dismiss timer
                        dismissCard.run()
                    }

                    // "Fuel it!" chip — navigates to the Supporter Loyalty screen
                    val navigateToTipJar = {
                        if (isAmazon && !BuildConfig.AMAZON_IAP_ENABLED) {
                            android.widget.Toast.makeText(
                                this,
                                getString(R.string.billing_unavailable_amazon_coming_soon),
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        } else {
                            startActivity(Intent(this, SupporterLoyaltyActivity::class.java))
                        }
                    }
                    findViewById<View>(R.id.btnFuelIt)?.setOnClickListener { navigateToTipJar() }

                    // Card tap (background area) also navigates
                    cardTipJar.setOnClickListener { navigateToTipJar() }

                    // Auto-dismiss after 10 seconds if the user doesn't interact
                    handler.postDelayed(dismissCard, 10000)
                }
            }
        }

        // TV: thin tip jar notification bar
        if (isTv) {
            val tvTipJar = findViewById<View>(R.id.tipJarTvNotification)
            if (tvTipJar != null) {
                if (hasShownTipJarThisSession) {
                    tvTipJar.visibility = View.GONE
                } else if (!LoyaltyPrefs.getHasEverBeenOnline(this)) {
                    tvTipJar.visibility = View.GONE
                } else if (!LoyaltyPrefs.isTipJarPopupEnabled(this)) {
                    tvTipJar.visibility = View.GONE
                } else {
                    tvTipJar.visibility = View.VISIBLE
                    hasShownTipJarThisSession = true

                    // Show cached progress immediately, then fetch live data
                    applyTvTipJarCachedValues()
                    fetchTipJarProgress()

                    // Auto-hide after 10 seconds with fade transition
                    handler.postDelayed({
                        val parent = findViewById<android.view.ViewGroup>(R.id.main)
                        if (parent != null) {
                            val transition = android.transition.TransitionSet().apply {
                                addTransition(android.transition.Fade(android.transition.Fade.OUT).apply {
                                    addTarget(tvTipJar)
                                })
                                addTransition(android.transition.ChangeBounds())
                                duration = 600
                            }
                            android.transition.TransitionManager.beginDelayedTransition(parent, transition)
                            tvTipJar.visibility = View.GONE
                        }
                    }, 10000)
                }
            }
        }

        // Mobile/TV: Manage Tiles / Edit Mode buttons
        btnManageTiles = findViewById(R.id.btnManageTiles)
        btnColorTile = findViewById(R.id.btnColorTile)
        btnImportColorCode = findViewById(R.id.btnImportColorCode)
        if (isTv) {
            btnDoneTv = findViewById(R.id.btnDone)
            btnDoneTv?.setOnClickListener { exitEditMode() }
        }

        // "Add Custom Tile" button
        val density = resources.displayMetrics.density
        if (!isTv) {
            val pad = (8 * density).toInt()
            val margin = (8 * density).toInt()
            val initialTint = DefaultIconColorManager.getMobileIconTint(this)
            btnAddCustomTile = ImageView(this).apply {
                setImageResource(R.drawable.ic_add)
                contentDescription = getString(R.string.custom_tile_add_button)
                setPadding(pad, pad, pad, pad)
                setBackgroundResource(R.drawable.bg_btn_icon_frosted)
                imageTintList = android.content.res.ColorStateList.valueOf(initialTint)
                layoutParams = androidx.appcompat.widget.Toolbar.LayoutParams(
                    androidx.appcompat.widget.Toolbar.LayoutParams.WRAP_CONTENT,
                    androidx.appcompat.widget.Toolbar.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.END
                ).apply { this.marginEnd = margin }
                setOnClickListener { showAddOptionsDialog() }
            }
            btnSettingsGear = ImageView(this).apply {
                setImageResource(R.drawable.ic_gear)
                contentDescription = getString(R.string.settings)
                setPadding(pad, pad, pad, pad)
                setBackgroundResource(R.drawable.bg_btn_icon_frosted)
                setColorFilter(getColor(R.color.mobile_icon_tint))
                layoutParams = androidx.appcompat.widget.Toolbar.LayoutParams(
                    androidx.appcompat.widget.Toolbar.LayoutParams.WRAP_CONTENT,
                    androidx.appcompat.widget.Toolbar.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.END
                ).apply { this.marginEnd = margin }
                setOnClickListener {
                    startActivity(
                        Intent(
                            this@StorageBrowserActivity,
                            za.kilowatch.ultimatefilemanager.settings.SettingsActivity::class.java
                        )
                    )
                }
            }
            toolbar?.addView(btnSettingsGear)
            toolbar?.addView(btnAddCustomTile)
        } else {
            val pad = (12 * density).toInt()
            val size = (48 * density).toInt()
            val margin = (12 * density).toInt()
            btnAddCustomTile = ImageView(this).apply {
                setImageResource(R.drawable.ic_add)
                contentDescription = getString(R.string.custom_tile_add_button)
                setPadding(pad, pad, pad, pad)
                setBackgroundResource(R.drawable.selector_tv_button_yellow)
                setColorFilter(getColor(R.color.selector_tv_button_text))
                layoutParams = android.view.ViewGroup.MarginLayoutParams(size, size).apply {
                    this.marginEnd = margin
                }
                setOnClickListener { showCreateCustomTileDialog() }
            }
            val headerLayout = findViewById<android.view.ViewGroup>(R.id.headerLayout)
            val buttonRow = headerLayout?.getChildAt(0) as? android.view.ViewGroup
            val rightButtons = buttonRow?.getChildAt(1) as? android.view.ViewGroup
            rightButtons?.addView(btnAddCustomTile, rightButtons.childCount - 1) // before btnDone
        }

        btnManageTiles.setOnClickListener {
            if (isEditMode) {
                exitEditMode()
            } else {
                ManageTilesBottomSheet
                    .newInstance()
                    .withTiles(buildAllTilesForSheet())
                    .withTileIcons(TileIconManager.getAllTileIcons(this))
                    .withTileIconRes(TileIconManager.getAllTileIconRes(this))
                    .apply {
                        onRestored  = { loadStorageVolumes() }
                        onTileClick = { item -> onStorageTileClicked(item) }
                    }
                    .show(supportFragmentManager, ManageTilesBottomSheet.TAG)
            }
        }

        updateHiddenBadge()

        val initMode = MainMenuViewModeManager.loadViewMode(this)
        val initCols = MainMenuViewModeManager.loadColumnCount(this)
        val initSize = MainMenuViewModeManager.loadItemSize(this)

        storageAdapter = StorageAdapter(
            isTv = isTv,
            onStorageClick = { item -> onStorageTileClicked(item) },
            onLongPress = { item, viewHolder ->
                if (isTv) {
                    // TV: long press enters Edit Mode first.
                    // If already in Edit Mode, show menu with move/reorder options.
                    if (isEditMode) {
                        showTvEditOptionsMenu(item)
                    } else {
                        enterEditMode()
                    }
                } else {
                    // Mobile: enter Edit Mode (pulse/jiggle) and start drag
                    if (!isEditMode) {
                        enterEditMode()
                    }
                    itemTouchHelper.startDrag(viewHolder)
                }
            }
        ).apply {
            viewMode = initMode
            gridColumnCount = initCols
            itemSize = initSize
            onHideClick = { item ->
                if (item.isSafCustomLocation) {
                    showRemoveStorageLocationDialog(item)
                } else {
                    hideTile(item)
                }
            }
            onEditModeClick = { item ->
                if (isSelectingTileForColor) {
                    // Color-pick mode takes priority — even for custom tiles
                    isSelectingTileForColor = false
                    storageAdapter.isColorPickMode = false
                    selectedTileId = item.id
                    showColorPickerForTile(item)
                } else if (item.isCategoryHeader) {
                    val catId = item.categoryId
                    if (catId != null && MainMenuViewModeManager.loadCustomCategories(this@StorageBrowserActivity).containsKey(catId)) {
                        confirmDeleteCustomHeader(item)
                    }
                } else if (item.isCustomTile) {
                    // Gear icon on custom tile → open Edit/Delete dialog
                    showCustomTileOptionsMenu(item)
                } else {
                    showPremiumSnackbar(getString(R.string.tile_color_title_select))
                }
            }
        }

        applyViewMode(animate = false)
        recyclerStorage.adapter = storageAdapter

        // Load custom tile colors and icons
        val colors = TileColorManager.loadTileColors(this)
        storageAdapter.setTileColors(colors)
        storageAdapter.setTileIcons(TileIconManager.getAllTileIcons(this))
        storageAdapter.setTileIconRes(TileIconManager.getAllTileIconRes(this))

        // Palette button â€” TV sets colour-selection mode directly (no dialog); mobile uses popup
        btnColorTile?.setOnClickListener {
            if (isEditMode) {
                if (isTv) {
                    isSelectingTileForColor = true
                    selectedTileId = null
                    storageAdapter.isColorPickMode = true
                    showPremiumSnackbar(getString(R.string.tile_color_select_tile))
                    // Pulse the button to signal active mode
                    btnColorTile?.animate()?.scaleX(1.2f)?.scaleY(1.2f)?.setDuration(150)
                        ?.withEndAction {
                            btnColorTile?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(150)?.start()
                        }?.start()
                } else {
                    showColorSelectionPopup()
                }
            }
        }

        btnImportColorCode?.setOnClickListener {
            if (isEditMode) {
                if (isTv) {
                    // Start the new Activity instead of showing a dialog
                    val copyIsListView = MainMenuViewModeManager.loadViewMode(this@StorageBrowserActivity) == MainMenuViewModeManager.ViewMode.LIST
                    TvTileDataHolder.tiles = lastFullTileList
                    TvTileDataHolder.isListView = copyIsListView
                    
                    val importIntent = Intent(this, TileColorImportTvActivity::class.java)
                    tvTileCopyLauncher.launch(importIntent)
                } else {
                    TileColorImportBottomSheet()
                        .setOnApplyListener { config ->
                            val copyIsListView = MainMenuViewModeManager.loadViewMode(this) == MainMenuViewModeManager.ViewMode.LIST
                            TileCopyBottomSheet.newInstance(
                                sourceConfig = config,
                                sourceTileId = "imported",
                                tiles        = lastFullTileList,
                                isListView   = copyIsListView
                            ).apply {
                                onApply = { targetIds ->
                                    targetIds.forEach { id ->
                                        TileColorManager.saveTileColor(this@StorageBrowserActivity, id, config)
                                    }
                                    storageAdapter.setTileColors(TileColorManager.loadTileColors(this@StorageBrowserActivity))
                                }
                            }.show(supportFragmentManager, TileCopyBottomSheet.TAG)
                        }
                        .show(supportFragmentManager, TileColorImportBottomSheet.TAG)
                }
            }
        }

        // Attach ItemTouchHelper for mobile drag-and-drop (no-op on TV)
        if (!isTv) setupItemTouchHelper()

        applyDynamicThemeColors()
    }

    private fun applyDynamicThemeColors() {
        val iconTint = if (isTv) DefaultIconColorManager.getTvIconTint(this) else DefaultIconColorManager.getMobileIconTint(this)
        findViewById<View?>(R.id.viewSectionUnderline)?.backgroundTintList = android.content.res.ColorStateList.valueOf(iconTint)
        btnManageTiles.imageTintList = android.content.res.ColorStateList.valueOf(iconTint)
        btnColorTile?.imageTintList = android.content.res.ColorStateList.valueOf(iconTint)
        btnImportColorCode?.imageTintList = android.content.res.ColorStateList.valueOf(iconTint)
        btnAddCustomTile?.imageTintList = android.content.res.ColorStateList.valueOf(iconTint)
        btnSettingsGear?.imageTintList = android.content.res.ColorStateList.valueOf(iconTint)
        updateToggleVisuals()
    }

    private var selectedTileId: String? = null
    private var isSelectingTileForColor = false



    private fun showColorSelectionPopup() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_tile_color_select, null)
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialogView.findViewById<View>(R.id.btnSelectTile).setOnClickListener {
            dialog.dismiss()
            isSelectingTileForColor = true
            selectedTileId = null
            showPremiumSnackbar(getString(R.string.tile_color_select_tile))
        }

        dialogView.findViewById<View>(R.id.btnCancel).setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
    }

    private fun showColorPickerForTile(item: StorageItem) {
        val config = storageAdapter.getTileColor(item.id)
        // Resolve the subtitle the same way StorageAdapter.bind() does, so the preview
        // tile shows the correct capacity or description text.
        val resolvedSubtitle: String = when {
            item.subtitle != null                -> item.subtitle
            item.isTwinWindowTile              -> getString(R.string.twin_window_subtitle)
             item.isNotepadTile                 -> getString(R.string.notepad_tile_subtitle)
             item.isScannerTile                 -> getString(R.string.scanner_tile_subtitle)
            item.isSmartSortTile               -> getString(R.string.smart_sort_tile_subtitle)
            item.isTerminalTile                 -> getString(R.string.adb_terminal_subtitle)
            item.isPairedDevicesTile            -> getString(R.string.manage_links_with_other_devices)
            item.isSettingsTile                 -> getString(R.string.font_size_tile_subtitle)
            item.isAppsTile                     -> getString(R.string.apps_tile_subtitle)
            item.isRemoteTile                   -> getString(R.string.remote_tile_subtitle)
            item.isSearchTile                   -> getString(R.string.search_tile_subtitle)
            item.isAnalyzerTile                 -> getString(R.string.analyzer_tile_subtitle)
            item.isVaultTile                    -> getString(R.string.vault_tile_subtitle)
            item.isLegalTile                    -> getString(R.string.policy_selection_subtitle)
            item.isRateUsTile                   -> getString(R.string.rate_us_subtitle)
            item.isAboutTile                    -> getString(R.string.about_tile_subtitle)
            item.isSupportTile                  -> getString(R.string.support_tile_subtitle)
            item.isSafTile                      -> getString(R.string.saf_tile_subtitle)
            item.isNetworkTile                  -> getString(R.string.network_tile_subtitle)
            item.isOnlineStoragesTile           -> DeviceUtils.getOnlineStoragesSubtitle(this)
            item.isTipJarTile                   -> getString(R.string.tip_jar_subtitle)
            item.isSyncTile                     -> getString(R.string.sync_subtitle)
            item.isAdvancedSyncTile              -> getString(R.string.advanced_sync_subtitle)
            item.isExtractsTile                 -> getString(R.string.browse_extracted_apps)
            item.isFavoriteTile                 -> if (item.favoriteIsFolder) getString(R.string.favorite_folder) else getString(R.string.favorite_file)
            item.isFileServerTile               -> getString(R.string.file_server_tile_subtitle)
            item.isNetworkRoot                  -> {
                val share = item.networkShare
                if (share != null) {
                    val typeLabel = when (share.type) {
                        za.kilowatch.ultimatefilemanager.network.ShareType.AWS_S3    -> getString(R.string.add_online_storage_aws_s3)
                        za.kilowatch.ultimatefilemanager.network.ShareType.IDRIVE_E2 -> getString(R.string.add_online_storage_idrive_e2)
                        za.kilowatch.ultimatefilemanager.network.ShareType.NFS       -> share.type.name
                        else -> share.type.name
                    }
                    "$typeLabel \u2022 ${share.host}"
                } else ""
            }
            item.totalBytes > 0                 -> {
                // Real storage volume: show "X free of Y"
                val free  = android.text.format.Formatter.formatFileSize(this, item.freeBytes)
                val total = android.text.format.Formatter.formatFileSize(this, item.totalBytes)
                getString(R.string.storage_free_format, free, total)
            }
            else -> ""
        }
        val isList = MainMenuViewModeManager.loadViewMode(this) == MainMenuViewModeManager.ViewMode.LIST

        if (isTv) {
            // TV: launch full-screen activity via TvTileDataHolder singleton
            TvTileDataHolder.tiles        = lastFullTileList
            TvTileDataHolder.sourceConfig = config
            TvTileDataHolder.sourceTileId = item.id
            TvTileDataHolder.isListView   = isList
            // Patch resolved subtitle back onto item so TileColorTvActivity can read it
            val tvItem = item.copy(subtitle = resolvedSubtitle)
            TvTileDataHolder.tiles = lastFullTileList.map {
                if (it.id == item.id) tvItem else it
            }
            tileColorTvLauncher.launch(
                TileColorTvActivity.createIntent(this, isListView = isList)
            )
        } else {
            // Mobile: bottom sheet flow
            val existingIconPath = TileIconManager.getTileIcon(this, item.id)
            val sheet = TileColorBottomSheet.newInstance(
                tileId = item.id,
                tileName = item.label,
                tileIconRes = item.iconRes,
                tileSubtitle = resolvedSubtitle,
                config = config,
                isListView = isList,
                customIconPath = existingIconPath
            )
            activeColorSheet = sheet
            sheet
                .setOnColorChangedListener { newConfig ->
                    TileColorManager.saveTileColor(this, item.id, newConfig)
                    val updatedColors = TileColorManager.loadTileColors(this)
                    storageAdapter.setTileColors(updatedColors)
                }
                .setOnIconChangedListener { iconConfig ->
                    TileIconManager.saveTileIconRes(this, item.id, iconConfig.selectedIconRes)
                    if (iconConfig.hasCustomIcon && iconConfig.customIconPath != null) {
                        TileIconManager.saveTileIcon(this, item.id, iconConfig.customIconPath)
                    } else if (!iconConfig.isBuiltinSelection) {
                        TileIconManager.clearTileIcon(this, item.id)
                    }
                    storageAdapter.setTileIcons(TileIconManager.getAllTileIcons(this))
                    storageAdapter.setTileIconRes(TileIconManager.getAllTileIconRes(this))
                }
                .setOnBrowseIconClickedListener {
                    launchTileIconPicker(item.id)
                }
                .setOnCopyToListener { sourceConfig ->
                    val copyIsListView = MainMenuViewModeManager.loadViewMode(this) ==
                        MainMenuViewModeManager.ViewMode.LIST
                    TileCopyBottomSheet.newInstance(
                        sourceConfig = sourceConfig,
                        sourceTileId = item.id,
                        tiles        = lastFullTileList,
                        isListView   = copyIsListView
                    ).apply {
                        onApply = { targetIds ->
                            targetIds.forEach { id ->
                                TileColorManager.saveTileColor(
                                    this@StorageBrowserActivity, id, sourceConfig
                                )
                            }
                            storageAdapter.setTileColors(
                                TileColorManager.loadTileColors(this@StorageBrowserActivity)
                            )
                        }
                    }.show(supportFragmentManager, TileCopyBottomSheet.TAG)
                }
                .show(supportFragmentManager, TileColorBottomSheet.TAG)
        }
    }

    /**
     * Launches the activity in icon-picker mode so the user can browse
     * local, network, or online storage for an .ico/.png file.
     */
    private fun launchTileIconPicker(tileId: String) {
        activeTileIdForIcon = tileId
        val intent = Intent(this, StorageBrowserActivity::class.java).apply {
            putExtra(EXTRA_TILE_ICON_PICKER, true)
        }
        iconPickerLauncher.launch(intent)
    }

    /**
     * In tile icon picker mode, filters out feature tiles so only
     * storage-selector tiles (drives, network roots, online storages,
     * favorites, paired devices) are shown.
     */
    private fun List<StorageItem>.filterForTileIconPicker(): List<StorageItem> {
        if (!isTileIconPickerMode) return this
        return this.filter { item ->
            item.isNetworkRoot || item.isOnlineStorage ||
            item.isFavoriteTile || item.isPairedDevicesTile ||
            (!item.isAppsTile && !item.isSearchTile && !item.isAnalyzerTile &&
             !item.isVaultTile && !item.isSafTile && !item.isNetworkTile &&
             !item.isExtractsTile && !item.isSyncTile && !item.isAdvancedSyncTile && !item.isSettingsTile &&
             !item.isTwinWindowTile && !item.isTerminalTile && !item.isShizukuTile &&
             !item.isFileServerTile && !item.isAboutTile && !item.isSupportTile && !item.isNotepadTile &&
             !item.isScannerTile && !item.isSmartSortTile && !item.isRecycleBinTile &&
             !item.isTvRemoteTile && !item.isRemoteTile &&
             !item.isOnlineStoragesTile && !item.isLegalTile &&
             !item.isRateUsTile && !item.isTipJarTile)
        }
    }

    /**
     * Central tile-click router used by both the main screen adapter
     * and the [ManageTilesBottomSheet] (for clicking on hidden tiles).
     */
    fun onStorageTileClicked(item: StorageItem) {
        when {
            item.isCustomTile -> {
                val intent = Intent(this, CustomTileActivity::class.java).apply {
                    putExtra(CustomTileActivity.EXTRA_CUSTOM_TILE_ID, item.id)
                    // Propagate active picker extras so CustomTileActivity can pass them through
                    if (isPickerMode) {
                        putExtra(FileBrowserActivity.EXTRA_PICKER_MODE, true)
                        putExtra(FileBrowserActivity.EXTRA_PICKER_EXTENSIONS, pickerExtensions)
                    }
                    if (isSyncFolderPickerMode) putExtra(FileBrowserActivity.EXTRA_SYNC_FOLDER_PICKER, true)
                    if (isAdvancedSyncFolderPickerMode) putExtra(FileBrowserActivity.EXTRA_ADVANCED_SYNC_FOLDER_PICKER, true)
                    if (isAdvancedSyncDestPickerMode) putExtra(FileBrowserActivity.EXTRA_ADVANCED_SYNC_DEST_PICKER, true)
                    if (isCompressDestPickerMode) putExtra(FileBrowserActivity.EXTRA_COMPRESS_DEST_PICKER, true)
                    if (isImageCompressDestPickerMode) putExtra(FileBrowserActivity.EXTRA_IMAGE_COMPRESS_DEST_PICKER, true)
                    if (isGifCreatorDestPickerMode) putExtra(FileBrowserActivity.EXTRA_GIF_CREATOR_DEST_PICKER, true)
                    if (isExtractDestPickerMode) putExtra(FileBrowserActivity.EXTRA_EXTRACT_DEST_PICKER, true)
                    if (isNetworkCachePickerMode) putExtra(FileBrowserActivity.EXTRA_NETWORK_CACHE_PICKER, true)
                    if (isQuickTransferPickerMode) {
                        putExtra(FileBrowserActivity.EXTRA_QUICK_TRANSFER_PICKER, true)
                        putExtra(FileBrowserActivity.EXTRA_QUICK_TRANSFER_OP,
                            intent.getStringExtra(FileBrowserActivity.EXTRA_QUICK_TRANSFER_OP))
                    }
                    if (isShareDestPickerMode) putExtra(FileBrowserActivity.EXTRA_SHARE_DEST_PICKER, true)
                    if (isNotepadFolderPicker) putExtra(FileBrowserActivity.EXTRA_NOTEPAD_FOLDER_PICKER, true)
                    if (isScannerFolderPicker) putExtra(FileBrowserActivity.EXTRA_SCANNER_FOLDER_PICKER, true)
                    if (isAutoBackupFolderPicker) putExtra(FileBrowserActivity.EXTRA_AUTO_BACKUP_FOLDER_PICKER, true)
                    if (isSupportAttachmentPicker) putExtra(FileBrowserActivity.EXTRA_SUPPORT_ATTACHMENT_PICKER, true)
                    if (isKeyfilePickerMode) putExtra(StorageBrowserActivity.EXTRA_KEYFILE_PICKER, true)
                    if (isCertPickerMode) putExtra(StorageBrowserActivity.EXTRA_CERT_PICKER, true)
                    if (isLocationPickerMode) putExtra(StorageBrowserActivity.EXTRA_LOCATION_PICKER, true)
                    if (isDrivePicker) putExtra(StorageBrowserActivity.EXTRA_DRIVE_PICKER, true)
                }
                val isAnyPickerActive = isPickerMode || isSyncFolderPickerMode || isAdvancedSyncFolderPickerMode || isAdvancedSyncDestPickerMode || isCompressDestPickerMode || isImageCompressDestPickerMode || isGifCreatorDestPickerMode || isExtractDestPickerMode || isNetworkCachePickerMode || isQuickTransferPickerMode || isShareDestPickerMode || isNotepadFolderPicker || isScannerFolderPicker || isAutoBackupFolderPicker || isSupportAttachmentPicker || isKeyfilePickerMode || isCertPickerMode || isLocationPickerMode || isDrivePicker
                if (isAnyPickerActive) {
                    pickerLauncher.launch(intent)
                } else {
                    startActivity(intent)
                }
            }
            item.isAddStorageLocationTile -> {
                addStorageLocationLauncher.launch(null)
            }
            item.isSafCustomLocation -> {
                za.kilowatch.ultimatefilemanager.util.GoRoLog.d("SafStorage", "Storage tile clicked: label=${item.label}, mountPath=${item.mountPath}, safLocation=${item.safLocation}")
                if (isDrivePicker) {
                    val data = Intent().apply {
                        putExtra("is_network", false)
                        putExtra("is_saf_custom", true)
                        putExtra("saf_location_id", item.safLocation?.id)
                        putExtra(FileBrowserActivity.EXTRA_MOUNT_PATH, item.mountPath)
                        putExtra(FileBrowserActivity.EXTRA_STORAGE_LABEL, item.label)
                    }
                    setResult(RESULT_OK, data)
                    finish()
                } else {
                    val isAnyPickerActive = isPickerMode || isLocationPickerMode || isSyncFolderPickerMode ||
                        isAdvancedSyncFolderPickerMode || isAdvancedSyncDestPickerMode || isCompressDestPickerMode ||
                        isImageCompressDestPickerMode || isGifCreatorDestPickerMode || isExtractDestPickerMode ||
                        isNetworkCachePickerMode || isQuickTransferPickerMode || isShareDestPickerMode ||
                        isNotepadFolderPicker || isScannerFolderPicker || isAutoBackupFolderPicker ||
                        isSupportAttachmentPicker || isKeyfilePickerMode || isCertPickerMode

                    val intent = Intent(this, FileBrowserActivity::class.java).apply {
                        putExtra(FileBrowserActivity.EXTRA_MOUNT_PATH, item.mountPath)
                        putExtra(FileBrowserActivity.EXTRA_INITIAL_PATH, item.mountPath)
                        putExtra(FileBrowserActivity.EXTRA_STORAGE_LABEL, item.label)
                        putExtra(FileBrowserActivity.EXTRA_STORAGE_ID, item.id)
                        putExtra(FileBrowserActivity.EXTRA_STORAGE_TYPE, "saf_custom")
                        if (isPickerMode) {
                            putExtra(FileBrowserActivity.EXTRA_PICKER_MODE, true)
                            putExtra(FileBrowserActivity.EXTRA_PICKER_EXTENSIONS, pickerExtensions)
                        }
                        if (isLocationPickerMode) putExtra(FileBrowserActivity.EXTRA_LOCATION_PICKER, true)
                        if (isSyncFolderPickerMode) putExtra(FileBrowserActivity.EXTRA_SYNC_FOLDER_PICKER, true)
                        if (isAdvancedSyncFolderPickerMode) putExtra(FileBrowserActivity.EXTRA_ADVANCED_SYNC_FOLDER_PICKER, true)
                        if (isAdvancedSyncDestPickerMode) putExtra(FileBrowserActivity.EXTRA_ADVANCED_SYNC_DEST_PICKER, true)
                        if (isCompressDestPickerMode) putExtra(FileBrowserActivity.EXTRA_COMPRESS_DEST_PICKER, true)
                        if (isImageCompressDestPickerMode) putExtra(FileBrowserActivity.EXTRA_IMAGE_COMPRESS_DEST_PICKER, true)
                        if (isGifCreatorDestPickerMode) putExtra(FileBrowserActivity.EXTRA_GIF_CREATOR_DEST_PICKER, true)
                        if (isExtractDestPickerMode) putExtra(FileBrowserActivity.EXTRA_EXTRACT_DEST_PICKER, true)
                        if (isNetworkCachePickerMode) putExtra(FileBrowserActivity.EXTRA_NETWORK_CACHE_PICKER, true)
                        if (isQuickTransferPickerMode) {
                            putExtra(FileBrowserActivity.EXTRA_QUICK_TRANSFER_PICKER, true)
                            putExtra(FileBrowserActivity.EXTRA_QUICK_TRANSFER_OP, this@StorageBrowserActivity.intent.getStringExtra(FileBrowserActivity.EXTRA_QUICK_TRANSFER_OP))
                        }
                        if (isShareDestPickerMode) putExtra(FileBrowserActivity.EXTRA_SHARE_DEST_PICKER, true)
                        if (isNotepadFolderPicker) putExtra(FileBrowserActivity.EXTRA_NOTEPAD_FOLDER_PICKER, true)
                        if (isScannerFolderPicker) putExtra(FileBrowserActivity.EXTRA_SCANNER_FOLDER_PICKER, true)
                        if (isAutoBackupFolderPicker) putExtra(FileBrowserActivity.EXTRA_AUTO_BACKUP_FOLDER_PICKER, true)
                        if (isSupportAttachmentPicker) putExtra(FileBrowserActivity.EXTRA_SUPPORT_ATTACHMENT_PICKER, true)
                        if (isKeyfilePickerMode) putExtra(FileBrowserActivity.EXTRA_KEYFILE_PICKER, true)
                        if (isCertPickerMode) putExtra(FileBrowserActivity.EXTRA_CERT_PICKER, true)
                    }

                    if (isAnyPickerActive) {
                        pickerLauncher.launch(intent)
                    } else {
                        za.kilowatch.ultimatefilemanager.util.AnimationHelper.startActivityWithTransition(this, intent)
                        showPremiumSnackbar(getString(R.string.opening_itemlabel, item.label))
                    }
                }
            }
            item.isTwinWindowTile -> {
                startActivity(Intent(this, TwinWindowActivity::class.java))
                showPremiumSnackbar(getString(R.string.opening_twin_window))
            }
            item.isNotepadTile -> {
                startActivity(Intent(this, za.kilowatch.ultimatefilemanager.notepad.NotepadActivity::class.java))
                showPremiumSnackbar(getString(R.string.opening_notepad))
            }
            item.isScannerTile -> {
                startActivity(Intent(this, za.kilowatch.ultimatefilemanager.scanner.DocumentScannerActivity::class.java))
                showPremiumSnackbar(getString(R.string.opening_scanner))
            }
            item.isExtractsTile -> checkAndNavigateToFileBrowser(item)
            item.isPairedDevicesTile -> {
                startActivity(Intent(this, za.kilowatch.ultimatefilemanager.ui.DevicePairingActivity::class.java))
            }
            item.isTerminalTile -> {
                startActivity(Intent(this, za.kilowatch.ultimatefilemanager.ui.TerminalActivity::class.java))
                showPremiumSnackbar(getString(R.string.opening_adb_terminal))
            }
            item.isShizukuTile -> {
                // Prevent double-launching / flickering
                val now = System.currentTimeMillis()
                if (now - lastShizukuLaunchTime < 1000) return
                lastShizukuLaunchTime = now

                val intent = if (isTv) {
                    Intent(this, za.kilowatch.ultimatefilemanager.ui.ShizukuTvActivity::class.java)
                } else {
                    Intent(this, za.kilowatch.ultimatefilemanager.ui.ShizukuActivity::class.java)
                }
                startActivity(intent)
                showPremiumSnackbar(getString(R.string.opening_shizuku))
            }
            item.isAppsTile -> {
                if (isDrivePicker) {
                    val data = Intent().apply {
                        putExtra("is_apps", true)
                        putExtra(za.kilowatch.ultimatefilemanager.storage.FileBrowserActivity.EXTRA_STORAGE_LABEL, item.label)
                    }
                    setResult(RESULT_OK, data)
                    finish()
                } else {
                    startActivity(Intent(this, AppManagerActivity::class.java))
                    showPremiumSnackbar(getString(R.string.opening_app_manager))
                }
            }
            item.isRemoteTile -> {
                if (VpnWarningHelper.isVpnActive(this)) {
                    VpnWarningHelper.showVpnWarningDialog(this) {
                        PinDialogHelper.showPinDialog(this, onCancel = {}) { pin ->
                            val intent = Intent(this, RemoteManageActivity::class.java).apply {
                                putExtra(RemoteManageActivity.EXTRA_PIN, pin)
                            }
                            startActivity(intent)
                        }
                    }
                } else {
                    PinDialogHelper.showPinDialog(this, onCancel = {}) { pin ->
                        val intent = Intent(this, RemoteManageActivity::class.java).apply {
                            putExtra(RemoteManageActivity.EXTRA_PIN, pin)
                        }
                        startActivity(intent)
                    }
                }
            }
            item.isSearchTile -> {
                startActivity(Intent(this, SearchActivity::class.java))
                showPremiumSnackbar(getString(R.string.opening_search))
            }
            item.isAnalyzerTile -> {
                startActivity(Intent(this, StorageAnalyzerActivity::class.java))
                showPremiumSnackbar(getString(R.string.opening_storage_analyzer))
            }
            item.isSmartSortTile -> {
                startActivity(Intent(this, SmartSortActivity::class.java))
                showPremiumSnackbar(getString(R.string.opening_smart_sort))
            }
            item.isTvRemoteTile -> {
                if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P) {
                    val dialogView = layoutInflater.inflate(R.layout.dialog_bt_remote_unsupported, null)
                    val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
                        .setView(dialogView)
                        .setCancelable(true)
                        .create()
                    dialogView.findViewById<View>(R.id.btnGotIt)?.setOnClickListener { dialog.dismiss() }
                    dialog.show()
                    dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
                } else {
                    startActivity(Intent(this, za.kilowatch.ultimatefilemanager.network.TvRemoteActivity::class.java))
                    showPremiumSnackbar(getString(R.string.opening_itemlabel, getString(R.string.tv_remote)))
                }
            }

            item.isSettingsTile -> startActivity(Intent(this, za.kilowatch.ultimatefilemanager.settings.SettingsActivity::class.java))
            item.isLegalTile    -> PolicySelectionActivity.start(this)
            item.isNetworkTile -> {
                startActivity(Intent(this, za.kilowatch.ultimatefilemanager.network.NetworkShareManagerActivity::class.java))
            }
            item.isOnlineStoragesTile -> {
                startActivity(Intent(this, za.kilowatch.ultimatefilemanager.network.OnlineStorageManagerActivity::class.java))
                showPremiumSnackbar(getString(R.string.opening_online_storages))
            }
            item.isVaultTile -> {
                startActivity(Intent(this, VaultActivity::class.java))
                showPremiumSnackbar(getString(R.string.opening_vault))
            }
            item.isRecycleBinTile -> {
                startActivity(Intent(this, za.kilowatch.ultimatefilemanager.recycle.RecycleBinActivity::class.java))
                showPremiumSnackbar(getString(R.string.opening_recycle_bin))
            }
            item.isSyncTile -> {
                startActivity(Intent(this, za.kilowatch.ultimatefilemanager.sync.SyncManagerActivity::class.java))
                showPremiumSnackbar(getString(R.string.opening_folder_sync))
            }
            item.isAdvancedSyncTile -> {
                startActivity(Intent(this, za.kilowatch.ultimatefilemanager.sync.advanced.AdvancedSyncActivity::class.java))
                showPremiumSnackbar(getString(R.string.opening_advanced_sync))
            }
            item.isFileServerTile -> {
                startActivity(Intent(this, za.kilowatch.ultimatefilemanager.server.ServerHostActivity::class.java))
                showPremiumSnackbar(getString(R.string.opening_file_server))
            }
            item.isRateUsTile -> {
                startActivity(Intent(this, za.kilowatch.ultimatefilemanager.settings.RateUsActivity::class.java))
            }
            item.isSupportTile -> {
                startActivity(Intent(this, za.kilowatch.ultimatefilemanager.support.SupportActivity::class.java))
            }
            item.isAboutTile -> {
                startActivity(Intent(this, za.kilowatch.ultimatefilemanager.settings.AboutActivity::class.java))
            }
            item.isTipJarTile -> {
                if (isAmazon && !BuildConfig.AMAZON_IAP_ENABLED) {
                    // Tip Jar not yet available on Amazon â€” show notice and don't open the activity
                    android.widget.Toast.makeText(
                        this,
                        getString(R.string.billing_unavailable_amazon_coming_soon),
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                } else {
                    val intent = Intent(this, SupporterLoyaltyActivity::class.java)
                    startActivity(intent)
                }
            }
            item.isNetworkRoot -> {
                if (isKeyfilePickerMode || isCertPickerMode) {
                    pickerLauncher.launch(
                        Intent(this, NetworkBrowserActivity::class.java).apply {
                            if (item.networkShare?.type == za.kilowatch.ultimatefilemanager.network.ShareType.TV) {
                                putExtra(NetworkBrowserActivity.EXTRA_PAIRED_DEVICE_ID, item.networkShare?.id)
                            } else {
                                putExtra(NetworkBrowserActivity.EXTRA_SHARE_ID, item.networkShare?.id)
                            }
                            putExtra(NetworkBrowserActivity.EXTRA_STORAGE_LABEL, item.label)
                            putExtra(FileBrowserActivity.EXTRA_PICKER_MODE, true)
                        }
                    )
                } else if (isPickerMode) {
                    if (isDrivePicker) {
                        val data = Intent().apply {
                            putExtra("is_network", true)
                            putExtra("share_id", item.networkShare?.id)
                            putExtra(za.kilowatch.ultimatefilemanager.storage.FileBrowserActivity.EXTRA_STORAGE_LABEL, item.label)
                        }
                        setResult(RESULT_OK, data)
                        finish()
                    } else {
                        val intent = Intent(this, NetworkBrowserActivity::class.java).apply {
                            if (item.networkShare?.type == za.kilowatch.ultimatefilemanager.network.ShareType.TV) {
                                putExtra(NetworkBrowserActivity.EXTRA_PAIRED_DEVICE_ID, item.networkShare?.id)
                            } else {
                                putExtra(NetworkBrowserActivity.EXTRA_SHARE_ID, item.networkShare?.id)
                            }
                            putExtra(NetworkBrowserActivity.EXTRA_STORAGE_LABEL, item.label)
                            putExtra(FileBrowserActivity.EXTRA_PICKER_MODE, true)
                            putExtra(FileBrowserActivity.EXTRA_PICKER_EXTENSIONS, pickerExtensions)
                        }
                        pickerLauncher.launch(intent)
                    }
                } else if (isQuickTransferPickerMode) {
                    val intent = Intent(this, NetworkBrowserActivity::class.java).apply {
                        if (item.networkShare?.type == za.kilowatch.ultimatefilemanager.network.ShareType.TV) {
                            putExtra(NetworkBrowserActivity.EXTRA_PAIRED_DEVICE_ID, item.networkShare?.id)
                        } else {
                            putExtra(NetworkBrowserActivity.EXTRA_SHARE_ID, item.networkShare?.id)
                        }
                        putExtra(NetworkBrowserActivity.EXTRA_STORAGE_LABEL, item.label)
                        putExtra(NetworkBrowserActivity.EXTRA_QUICK_TRANSFER_PICKER, true)
                        putExtra(NetworkBrowserActivity.EXTRA_QUICK_TRANSFER_OP,
                            this@StorageBrowserActivity.intent.getStringExtra(FileBrowserActivity.EXTRA_QUICK_TRANSFER_OP))
                    }
                    pickerLauncher.launch(intent)
                } else if (isLocationPickerMode) {
                    val intent = Intent(this, NetworkBrowserActivity::class.java).apply {
                        // TV/paired-device items must use EXTRA_PAIRED_DEVICE_ID so
                        // NetworkBrowserActivity opens the peer connection instead of
                        // treating it as a regular SMB/FTP share (which would just refresh).
                        if (item.networkShare?.type == za.kilowatch.ultimatefilemanager.network.ShareType.TV) {
                            putExtra(NetworkBrowserActivity.EXTRA_PAIRED_DEVICE_ID, item.networkShare?.id)
                        } else {
                            putExtra(NetworkBrowserActivity.EXTRA_SHARE_ID, item.networkShare?.id)
                        }
                        putExtra(NetworkBrowserActivity.EXTRA_STORAGE_LABEL, item.label)
                        putExtra(NetworkBrowserActivity.EXTRA_LOCATION_PICKER, true)
                    }
                    pickerLauncher.launch(intent)
                } else if (isShareDestPickerMode) {
                    val intent = Intent(this, NetworkBrowserActivity::class.java).apply {
                        if (item.networkShare?.type == za.kilowatch.ultimatefilemanager.network.ShareType.TV) {
                            putExtra(NetworkBrowserActivity.EXTRA_PAIRED_DEVICE_ID, item.networkShare?.id)
                        } else {
                            putExtra(NetworkBrowserActivity.EXTRA_SHARE_ID, item.networkShare?.id)
                        }
                        putExtra(NetworkBrowserActivity.EXTRA_STORAGE_LABEL, item.label)
                        putExtra(NetworkBrowserActivity.EXTRA_SHARE_DEST_PICKER, true)
                    }
                    pickerLauncher.launch(intent)
                } else if (isAdvancedSyncDestPickerMode) {
                    android.util.Log.d("AdvSyncDest", "Launching NetworkBrowser for dest picker, share=${item.networkShare?.id}, label=${item.label}")
                    val intent = Intent(this, NetworkBrowserActivity::class.java).apply {
                        if (item.networkShare?.type == za.kilowatch.ultimatefilemanager.network.ShareType.TV) {
                            putExtra(NetworkBrowserActivity.EXTRA_PAIRED_DEVICE_ID, item.networkShare?.id)
                        } else {
                            putExtra(NetworkBrowserActivity.EXTRA_SHARE_ID, item.networkShare?.id)
                        }
                        putExtra(NetworkBrowserActivity.EXTRA_STORAGE_LABEL, item.label)
                        putExtra(NetworkBrowserActivity.EXTRA_ADVANCED_SYNC_FOLDER_PICKER, true)
                    }
                    pickerLauncher.launch(intent)
                } else if (isScannerFolderPicker) {
                    val intent = Intent(this, NetworkBrowserActivity::class.java).apply {
                        if (item.networkShare?.type == za.kilowatch.ultimatefilemanager.network.ShareType.TV) {
                            putExtra(NetworkBrowserActivity.EXTRA_PAIRED_DEVICE_ID, item.networkShare?.id)
                        } else {
                            putExtra(NetworkBrowserActivity.EXTRA_SHARE_ID, item.networkShare?.id)
                        }
                        putExtra(NetworkBrowserActivity.EXTRA_STORAGE_LABEL, item.label)
                        putExtra(FileBrowserActivity.EXTRA_SCANNER_FOLDER_PICKER, true)
                    }
                    pickerLauncher.launch(intent)
                } else if (isAutoBackupFolderPicker) {
                    val intent = Intent(this, NetworkBrowserActivity::class.java).apply {
                        if (item.networkShare?.type == za.kilowatch.ultimatefilemanager.network.ShareType.TV) {
                            putExtra(NetworkBrowserActivity.EXTRA_PAIRED_DEVICE_ID, item.networkShare?.id)
                        } else {
                            putExtra(NetworkBrowserActivity.EXTRA_SHARE_ID, item.networkShare?.id)
                        }
                        putExtra(NetworkBrowserActivity.EXTRA_STORAGE_LABEL, item.label)
                        putExtra(FileBrowserActivity.EXTRA_AUTO_BACKUP_FOLDER_PICKER, true)
                    }
                    pickerLauncher.launch(intent)
                } else if (isImageCompressDestPickerMode) {
                    val intent = Intent(this, NetworkBrowserActivity::class.java).apply {
                        if (item.networkShare?.type == za.kilowatch.ultimatefilemanager.network.ShareType.TV) {
                            putExtra(NetworkBrowserActivity.EXTRA_PAIRED_DEVICE_ID, item.networkShare?.id)
                        } else {
                            putExtra(NetworkBrowserActivity.EXTRA_SHARE_ID, item.networkShare?.id)
                        }
                        putExtra(NetworkBrowserActivity.EXTRA_STORAGE_LABEL, item.label)
                        putExtra(FileBrowserActivity.EXTRA_IMAGE_COMPRESS_DEST_PICKER, true)
                    }
                    pickerLauncher.launch(intent)
                } else if (isGifCreatorDestPickerMode) {
                    val intent = Intent(this, NetworkBrowserActivity::class.java).apply {
                        if (item.networkShare?.type == za.kilowatch.ultimatefilemanager.network.ShareType.TV) {
                            putExtra(NetworkBrowserActivity.EXTRA_PAIRED_DEVICE_ID, item.networkShare?.id)
                        } else {
                            putExtra(NetworkBrowserActivity.EXTRA_SHARE_ID, item.networkShare?.id)
                        }
                        putExtra(NetworkBrowserActivity.EXTRA_STORAGE_LABEL, item.label)
                        putExtra(FileBrowserActivity.EXTRA_GIF_CREATOR_DEST_PICKER, true)
                    }
                    pickerLauncher.launch(intent)
                } else {
                    val isDefaultTwinWindow = za.kilowatch.ultimatefilemanager.settings.TwinWindowPreferenceManager.isDefaultStartup(this)
                    if (isDefaultTwinWindow && !isCompressDestPickerMode && !isQuickTransferPickerMode) {
                        val intent = Intent(this, TwinWindowActivity::class.java).apply {
                            if (item.networkShare?.type == za.kilowatch.ultimatefilemanager.network.ShareType.TV) {
                                // For TV we pass it via EXTRA_TOP_SHARE_ID, assuming NetworkBrowserFragment can resolve it.
                                putExtra(TwinWindowActivity.EXTRA_TOP_SHARE_ID, item.networkShare?.id)
                            } else {
                                putExtra(TwinWindowActivity.EXTRA_TOP_SHARE_ID, item.networkShare?.id)
                            }
                        }
                        startActivity(intent)
                        showPremiumSnackbar(getString(R.string.opening_itemlabel, item.label))
                    } else {
                        val intent = Intent(this, NetworkBrowserActivity::class.java).apply {
                            if (item.networkShare?.type == za.kilowatch.ultimatefilemanager.network.ShareType.TV) {
                                putExtra(NetworkBrowserActivity.EXTRA_PAIRED_DEVICE_ID, item.networkShare?.id)
                            } else {
                                putExtra(NetworkBrowserActivity.EXTRA_SHARE_ID, item.networkShare?.id)
                            }
                            putExtra(NetworkBrowserActivity.EXTRA_STORAGE_LABEL, item.label)
                        }
                        startActivity(intent)
                    }
                }
            }
            item.isOnlineStorage -> {
                val storage = item.onlineStorage
                val isRClone = storage?.provider == za.kilowatch.ultimatefilemanager.network.OnlineStorageProvider.RCLONE

                /**
                 * Runs [block] immediately for non-RClone providers.
                 * For RClone providers, calls [RCloneShareClient.prepareForBrowse] on a
                 * background thread first, then runs [block] on the main thread.
                 * Shows a toast if initialization fails.
                 */
                fun launchWithRCloneInit(block: () -> Unit) {
                    if (storage != null && storage.provider == za.kilowatch.ultimatefilemanager.network.OnlineStorageProvider.RCLONE) {
                        lifecycleScope.launch {
                            try {
                                za.kilowatch.ultimatefilemanager.network.RCloneShareClient.prepareForBrowse(storage)
                                block()
                            } catch (e: Exception) {
                                android.widget.Toast.makeText(
                                    this@StorageBrowserActivity,
                                    "RClone error: ${e.message}",
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    } else {
                        block()
                    }
                }

                if (isKeyfilePickerMode || isCertPickerMode) {
                    launchWithRCloneInit {
                        pickerLauncher.launch(
                            Intent(this, NetworkBrowserActivity::class.java).apply {
                                putExtra("isOnlineStorage", true)
                                putExtra(NetworkBrowserActivity.EXTRA_SHARE_ID, storage?.id)
                                putExtra(NetworkBrowserActivity.EXTRA_STORAGE_LABEL, item.label)
                                putExtra(FileBrowserActivity.EXTRA_PICKER_MODE, true)
                            }
                        )
                    }
                } else if (isPickerMode) {
                    if (isDrivePicker) {
                        // Drive picker only returns a result — no network I/O here.
                        val data = Intent().apply {
                            putExtra("is_network", true)
                            putExtra("isOnlineStorage", true)
                            putExtra("share_id", storage?.id)
                            putExtra(za.kilowatch.ultimatefilemanager.storage.FileBrowserActivity.EXTRA_STORAGE_LABEL, item.label)
                        }
                        setResult(RESULT_OK, data)
                        finish()
                    } else {
                        launchWithRCloneInit {
                            val intent = Intent(this, NetworkBrowserActivity::class.java).apply {
                                putExtra("isOnlineStorage", true)
                                putExtra(NetworkBrowserActivity.EXTRA_SHARE_ID, storage?.id)
                                putExtra(NetworkBrowserActivity.EXTRA_STORAGE_LABEL, "${item.label} - ${storage?.email}")
                                putExtra(FileBrowserActivity.EXTRA_PICKER_MODE, true)
                                putExtra(FileBrowserActivity.EXTRA_PICKER_EXTENSIONS, pickerExtensions)
                            }
                            pickerLauncher.launch(intent)
                        }
                    }
                } else if (isQuickTransferPickerMode) {
                    launchWithRCloneInit {
                        val intent = Intent(this, NetworkBrowserActivity::class.java).apply {
                            putExtra("isOnlineStorage", true)
                            putExtra(NetworkBrowserActivity.EXTRA_SHARE_ID, storage?.id)
                            putExtra(NetworkBrowserActivity.EXTRA_STORAGE_LABEL, "${item.label} - ${storage?.email}")
                            putExtra(NetworkBrowserActivity.EXTRA_QUICK_TRANSFER_PICKER, true)
                            putExtra(NetworkBrowserActivity.EXTRA_QUICK_TRANSFER_OP,
                                this@StorageBrowserActivity.intent.getStringExtra(FileBrowserActivity.EXTRA_QUICK_TRANSFER_OP))
                        }
                        pickerLauncher.launch(intent)
                    }
                } else if (isLocationPickerMode) {
                    launchWithRCloneInit {
                        val intent = Intent(this, NetworkBrowserActivity::class.java).apply {
                            putExtra("isOnlineStorage", true)
                            putExtra(NetworkBrowserActivity.EXTRA_SHARE_ID, storage?.id)
                            putExtra(NetworkBrowserActivity.EXTRA_STORAGE_LABEL, "${item.label} - ${storage?.email}")
                            putExtra(NetworkBrowserActivity.EXTRA_LOCATION_PICKER, true)
                        }
                        pickerLauncher.launch(intent)
                    }
                } else if (isShareDestPickerMode) {
                    launchWithRCloneInit {
                        val intent = Intent(this, NetworkBrowserActivity::class.java).apply {
                            putExtra("isOnlineStorage", true)
                            putExtra(NetworkBrowserActivity.EXTRA_SHARE_ID, storage?.id)
                            putExtra(NetworkBrowserActivity.EXTRA_STORAGE_LABEL, "${item.label} - ${storage?.email}")
                            putExtra(NetworkBrowserActivity.EXTRA_SHARE_DEST_PICKER, true)
                        }
                        pickerLauncher.launch(intent)
                    }
                } else if (isScannerFolderPicker) {
                    launchWithRCloneInit {
                        val intent = Intent(this, NetworkBrowserActivity::class.java).apply {
                            putExtra("isOnlineStorage", true)
                            putExtra(NetworkBrowserActivity.EXTRA_SHARE_ID, storage?.id)
                            putExtra(NetworkBrowserActivity.EXTRA_STORAGE_LABEL, "${item.label} - ${storage?.email}")
                            putExtra(FileBrowserActivity.EXTRA_SCANNER_FOLDER_PICKER, true)
                        }
                        pickerLauncher.launch(intent)
                    }
                } else if (isAutoBackupFolderPicker) {
                    launchWithRCloneInit {
                        val intent = Intent(this, NetworkBrowserActivity::class.java).apply {
                            putExtra("isOnlineStorage", true)
                            putExtra(NetworkBrowserActivity.EXTRA_SHARE_ID, storage?.id)
                            putExtra(NetworkBrowserActivity.EXTRA_STORAGE_LABEL, "${item.label} - ${storage?.email}")
                            putExtra(FileBrowserActivity.EXTRA_AUTO_BACKUP_FOLDER_PICKER, true)
                        }
                        pickerLauncher.launch(intent)
                    }
                } else if (isImageCompressDestPickerMode) {
                    launchWithRCloneInit {
                        val intent = Intent(this, NetworkBrowserActivity::class.java).apply {
                            putExtra("isOnlineStorage", true)
                            putExtra(NetworkBrowserActivity.EXTRA_SHARE_ID, storage?.id)
                            putExtra(NetworkBrowserActivity.EXTRA_STORAGE_LABEL, "${item.label} - ${storage?.email}")
                            putExtra(FileBrowserActivity.EXTRA_IMAGE_COMPRESS_DEST_PICKER, true)
                        }
                        pickerLauncher.launch(intent)
                    }
                } else if (isGifCreatorDestPickerMode) {
                    launchWithRCloneInit {
                        val intent = Intent(this, NetworkBrowserActivity::class.java).apply {
                            putExtra("isOnlineStorage", true)
                            putExtra(NetworkBrowserActivity.EXTRA_SHARE_ID, storage?.id)
                            putExtra(NetworkBrowserActivity.EXTRA_STORAGE_LABEL, "${item.label} - ${storage?.email}")
                            putExtra(FileBrowserActivity.EXTRA_GIF_CREATOR_DEST_PICKER, true)
                        }
                        pickerLauncher.launch(intent)
                    }
                } else {
                    val isDefaultTwinWindow = za.kilowatch.ultimatefilemanager.settings.TwinWindowPreferenceManager.isDefaultStartup(this)
                    if (isDefaultTwinWindow && !isCompressDestPickerMode && !isQuickTransferPickerMode) {
                        // Twin Window: launch directly — TwinWindowActivity handles its own init.
                        val intent = Intent(this, TwinWindowActivity::class.java).apply {
                            putExtra(TwinWindowActivity.EXTRA_TOP_SHARE_ID, storage?.id)
                        }
                        startActivity(intent)
                        showPremiumSnackbar(getString(R.string.opening_itemlabel, item.label))
                    } else {
                        launchWithRCloneInit {
                            val intent = Intent(this, NetworkBrowserActivity::class.java).apply {
                                putExtra("isOnlineStorage", true)
                                putExtra(NetworkBrowserActivity.EXTRA_SHARE_ID, storage?.id)
                                putExtra(NetworkBrowserActivity.EXTRA_STORAGE_LABEL, "${item.label} - ${storage?.email}")
                                if (isCompressDestPickerMode) {
                                    putExtra(NetworkBrowserActivity.EXTRA_COMPRESS_DEST_PICKER, true)
                                }
                            }
                            startActivity(intent)
                        }
                    }
                }
            }

            item.isFavoriteTile -> {
                if (item.favoriteIsFolder) {
                    val intent = if (item.favoriteIsNetwork) {
                        Intent(this, NetworkBrowserActivity::class.java).apply {
                            if (item.networkShare?.type == za.kilowatch.ultimatefilemanager.network.ShareType.TV) {
                                putExtra(NetworkBrowserActivity.EXTRA_PAIRED_DEVICE_ID, item.networkShare.id)
                            } else {
                                putExtra(NetworkBrowserActivity.EXTRA_SHARE_ID, item.networkShare?.id)
                                if (item.networkShare?.type == za.kilowatch.ultimatefilemanager.network.ShareType.ONEDRIVE || item.networkShare?.type == za.kilowatch.ultimatefilemanager.network.ShareType.GOOGLE_DRIVE) {
                                    putExtra("isOnlineStorage", true)
                                }
                            }
                            putExtra(NetworkBrowserActivity.EXTRA_STORAGE_LABEL, item.label)
                            putExtra(NetworkBrowserActivity.EXTRA_INITIAL_PATH, item.favoritePath)
                            if (isPickerMode) {
                                putExtra(FileBrowserActivity.EXTRA_PICKER_MODE, true)
                                putExtra(FileBrowserActivity.EXTRA_PICKER_EXTENSIONS, pickerExtensions)
                            }
                            if (isCompressDestPickerMode) {
                                putExtra(NetworkBrowserActivity.EXTRA_COMPRESS_DEST_PICKER, true)
                            }
                        }
                    } else {
                        val favPath = item.favoritePath ?: ""
                        val (sid, stype, volumeRoot) = IndexingRepository.resolveStorageForPath(favPath)
                        Intent(this, FileBrowserActivity::class.java).apply {
                            putExtra(FileBrowserActivity.EXTRA_MOUNT_PATH, volumeRoot)
                            putExtra(FileBrowserActivity.EXTRA_INITIAL_PATH, favPath)
                            putExtra(FileBrowserActivity.EXTRA_STORAGE_LABEL, item.label)
                            putExtra(FileBrowserActivity.EXTRA_STORAGE_ID, sid)
                            putExtra(FileBrowserActivity.EXTRA_STORAGE_TYPE, stype)
                        }
                    }
                    
                    za.kilowatch.ultimatefilemanager.util.AnimationHelper.startActivityWithTransition(this, intent)
                    showPremiumSnackbar(getString(R.string.opening_itemlabel, item.label))
                } else {
                    // It's a file
                    if (item.favoriteIsNetwork) {
                        val intent = Intent(this, NetworkBrowserActivity::class.java).apply {
                            if (item.networkShare?.type == za.kilowatch.ultimatefilemanager.network.ShareType.TV) {
                                putExtra(NetworkBrowserActivity.EXTRA_PAIRED_DEVICE_ID, item.networkShare.id)
                            } else {
                                putExtra(NetworkBrowserActivity.EXTRA_SHARE_ID, item.networkShare?.id)
                                if (item.networkShare?.type == za.kilowatch.ultimatefilemanager.network.ShareType.ONEDRIVE || item.networkShare?.type == za.kilowatch.ultimatefilemanager.network.ShareType.GOOGLE_DRIVE) {
                                    putExtra("isOnlineStorage", true)
                                }
                            }
                            putExtra(NetworkBrowserActivity.EXTRA_STORAGE_LABEL, item.label)
                            
                            val parentPath = item.favoritePath?.substringBeforeLast("/", "") ?: ""
                            val fileName = item.favoritePath?.substringAfterLast("/") ?: ""
                            
                            putExtra(NetworkBrowserActivity.EXTRA_INITIAL_PATH, parentPath)
                            putExtra(NetworkBrowserActivity.EXTRA_OPEN_FILE_PATH, item.favoritePath)
                            putExtra(NetworkBrowserActivity.EXTRA_OPEN_FILE_NAME, fileName)
                        }
                        startActivity(intent)
                        showPremiumSnackbar(getString(R.string.opening_itemlabel_1, item.label))
                    } else {
                        val file = File(item.favoritePath ?: "")
                        if (file.exists()) {
                            if (za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.openFile(this, file)) return
                            
                            val uri = androidx.core.content.FileProvider.getUriForFile(
                                this,
                                "${packageName}.fileprovider",
                                file
                            )
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                val ext = file.extension.lowercase()
                                val mimeType = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
                                setDataAndType(uri, mimeType)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            try {
                                if (intent.resolveActivity(packageManager) != null) {
                                    startActivity(intent)
                                } else {
                                    showPremiumSnackbar(getString(R.string.no_app_found_to_open_this_file_pattern))
                                }
                            } catch (e: Exception) {
                                showPremiumSnackbar(getString(R.string.unable_to_open_file_emessage))
                            }
                        } else {
                            showPremiumSnackbar(getString(R.string.file_not_found))
                        }
                    }
                }
            }
            else -> {
                if (isDrivePicker) {
                    val data = Intent().apply {
                        putExtra("is_network", false)
                        putExtra(za.kilowatch.ultimatefilemanager.storage.FileBrowserActivity.EXTRA_MOUNT_PATH, item.mountPath)
                        putExtra(za.kilowatch.ultimatefilemanager.storage.FileBrowserActivity.EXTRA_STORAGE_LABEL, item.label)
                    }
                    setResult(RESULT_OK, data)
                    finish()
                } else if (isKeyfilePickerMode) {
                    val intent = Intent(this, FileBrowserActivity::class.java).apply {
                        putExtra(FileBrowserActivity.EXTRA_MOUNT_PATH, item.mountPath)
                        putExtra(FileBrowserActivity.EXTRA_STORAGE_LABEL, item.label)
                        putExtra(FileBrowserActivity.EXTRA_KEYFILE_PICKER, true)
                    }
                    pickerLauncher.launch(intent)
                } else if (isCertPickerMode) {
                    val intent = Intent(this, FileBrowserActivity::class.java).apply {
                        putExtra(FileBrowserActivity.EXTRA_MOUNT_PATH, item.mountPath)
                        putExtra(FileBrowserActivity.EXTRA_STORAGE_LABEL, item.label)
                        putExtra(FileBrowserActivity.EXTRA_CERT_PICKER, true)
                    }
                    pickerLauncher.launch(intent)
                } else if (isLocationPickerMode) {
                    val intent = Intent(this, FileBrowserActivity::class.java).apply {
                        putExtra(FileBrowserActivity.EXTRA_MOUNT_PATH, item.mountPath)
                        putExtra(FileBrowserActivity.EXTRA_STORAGE_LABEL, item.label)
                        putExtra(FileBrowserActivity.EXTRA_LOCATION_PICKER, true)
                        // Local storage ID/Type
                        val (sid, stype) = IndexingRepository.resolveStorageForPath(item.mountPath)
                        putExtra(FileBrowserActivity.EXTRA_STORAGE_ID, sid)
                        putExtra(FileBrowserActivity.EXTRA_STORAGE_TYPE, stype)
                    }
                    pickerLauncher.launch(intent)
                } else if (isImageCompressDestPickerMode) {
                    val intent = Intent(this, FileBrowserActivity::class.java).apply {
                        putExtra(FileBrowserActivity.EXTRA_MOUNT_PATH, item.mountPath)
                        putExtra(FileBrowserActivity.EXTRA_STORAGE_LABEL, item.label)
                        putExtra(FileBrowserActivity.EXTRA_IMAGE_COMPRESS_DEST_PICKER, true)
                    }
                    pickerLauncher.launch(intent)
                } else if (isGifCreatorDestPickerMode) {
                    val intent = Intent(this, FileBrowserActivity::class.java).apply {
                        putExtra(FileBrowserActivity.EXTRA_MOUNT_PATH, item.mountPath)
                        putExtra(FileBrowserActivity.EXTRA_STORAGE_LABEL, item.label)
                        putExtra(FileBrowserActivity.EXTRA_GIF_CREATOR_DEST_PICKER, true)
                    }
                    pickerLauncher.launch(intent)
                } else if (isSupportAttachmentPicker) {
                    checkAndNavigateToFileBrowser(item)
                } else {
                    checkAndNavigateToFileBrowser(item)
                }
            }
        }
    }

    /**
     * Checks if the storage needs to be indexed before navigating.
     *
     *  - Already indexed       â†’ navigate directly (fast path, no dialog)
     *  - User declined before  â†’ navigate directly (respect the choice)
     *  - Never indexed         â†’ show the indexing offer dialog first
     */
    private fun checkAndNavigateToFileBrowser(item: StorageItem) {
        val storageId = IndexingRepository.resolveStorageForPath(item.mountPath).first
        val storageType = IndexingRepository.resolveStorageForPath(item.mountPath).second
        val repo = IndexingRepository.getInstance(this)

        if (repo.isStorageFullyIndexed(storageId) || repo.hasUserDeclinedIndexing(storageId)) {
            navigateToFileBrowser(item, storageId, storageType)
        } else {
            showIndexingOfferDialog(item, storageId, storageType)
        }
    }

    /**
     * Asks the user whether they want to index this storage before browsing.
     * "Index Now" â†’ starts the full index via the progress dialog.
     * "Not Now"   â†’ saves a declined preference so we don't ask again, then navigates.
     */
    private fun showIndexingOfferDialog(item: StorageItem, storageId: String, storageType: String) {
        IndexingUiHelper.showIndexingOfferDialog(
            activity = this,
            storageLabel = item.label,
            storageId = storageId,
            onIndexNow = { showIndexingProgressDialog(item, storageId, storageType) },
            onNotNow = { navigateToFileBrowser(item, storageId, storageType) }
        )
    }

    private fun showIndexingProgressDialog(item: StorageItem, storageId: String, storageType: String) {
        IndexingUiHelper.showIndexingProgressDialog(
            activity = this,
            storageLabel = item.label,
            storageId = storageId,
            storagePath = item.mountPath,
            storageType = storageType,
            onComplete = { navigateToFileBrowser(item, storageId, storageType) },
            onCancel = { /* Just dismissed */ }
        )
    }

    /**
     * Navigates to the file browser for the selected storage volume.
     */
    private fun navigateToFileBrowser(item: StorageItem, storageId: String, storageType: String) {
        val isDefaultTwinWindow = za.kilowatch.ultimatefilemanager.settings.TwinWindowPreferenceManager.isDefaultStartup(this)
        
        if (isDefaultTwinWindow && !isPickerMode && !isSyncFolderPickerMode && !isAdvancedSyncFolderPickerMode && !isAdvancedSyncDestPickerMode && !isCompressDestPickerMode && !isImageCompressDestPickerMode && !isGifCreatorDestPickerMode && !isExtractDestPickerMode && !isLocationPickerMode && !isNetworkCachePickerMode && !isQuickTransferPickerMode && !isShareDestPickerMode && !isScannerFolderPicker && !isAutoBackupFolderPicker && !isSupportAttachmentPicker) {
            val intent = Intent(this, TwinWindowActivity::class.java).apply {
                putExtra(TwinWindowActivity.EXTRA_TOP_LOCAL_PATH, item.mountPath)
                putExtra(TwinWindowActivity.EXTRA_TOP_LOCAL_LABEL, item.label)
                putExtra(FileBrowserActivity.EXTRA_STORAGE_ID, storageId)
                putExtra(FileBrowserActivity.EXTRA_STORAGE_TYPE, storageType)
            }
            startActivity(intent)
            showPremiumSnackbar(getString(R.string.opening_itemlabel, item.label))
            return
        }

        val intent = Intent(this, FileBrowserActivity::class.java).apply {
            putExtra(FileBrowserActivity.EXTRA_MOUNT_PATH, item.mountPath)
            putExtra(FileBrowserActivity.EXTRA_STORAGE_LABEL, item.label)
            putExtra(FileBrowserActivity.EXTRA_STORAGE_ID, storageId)
            putExtra(FileBrowserActivity.EXTRA_STORAGE_TYPE, storageType)
            if (isPickerMode) {
                putExtra(FileBrowserActivity.EXTRA_PICKER_MODE, true)
                putExtra(FileBrowserActivity.EXTRA_PICKER_EXTENSIONS, pickerExtensions)
            }
            if (isLocationPickerMode) {
                putExtra(FileBrowserActivity.EXTRA_LOCATION_PICKER, true)
            }
            if (isSyncFolderPickerMode) {
                putExtra(FileBrowserActivity.EXTRA_SYNC_FOLDER_PICKER, true)
            }
            if (isAdvancedSyncFolderPickerMode) {
                putExtra(FileBrowserActivity.EXTRA_ADVANCED_SYNC_FOLDER_PICKER, true)
            }
            if (isAdvancedSyncDestPickerMode) {
                putExtra(FileBrowserActivity.EXTRA_ADVANCED_SYNC_DEST_PICKER, true)
            }
            if (isCompressDestPickerMode) {
                putExtra(FileBrowserActivity.EXTRA_COMPRESS_DEST_PICKER, true)
            }
            if (isImageCompressDestPickerMode) {
                putExtra(FileBrowserActivity.EXTRA_IMAGE_COMPRESS_DEST_PICKER, true)
            }
            if (isGifCreatorDestPickerMode) {
                putExtra(FileBrowserActivity.EXTRA_GIF_CREATOR_DEST_PICKER, true)
            }
            if (isExtractDestPickerMode) {
                putExtra(FileBrowserActivity.EXTRA_EXTRACT_DEST_PICKER, true)
            }
            if (isNetworkCachePickerMode) {
                putExtra(FileBrowserActivity.EXTRA_NETWORK_CACHE_PICKER, true)
            }
            if (isQuickTransferPickerMode) {
                putExtra(FileBrowserActivity.EXTRA_QUICK_TRANSFER_PICKER, true)
                putExtra(FileBrowserActivity.EXTRA_QUICK_TRANSFER_OP,
                    intent.getStringExtra(FileBrowserActivity.EXTRA_QUICK_TRANSFER_OP))
            }
            if (isShareDestPickerMode) {
                putExtra(FileBrowserActivity.EXTRA_SHARE_DEST_PICKER, true)
            }
            if (isNotepadFolderPicker) {
                putExtra(FileBrowserActivity.EXTRA_NOTEPAD_FOLDER_PICKER, true)
            }
            if (isScannerFolderPicker) {
                putExtra(FileBrowserActivity.EXTRA_SCANNER_FOLDER_PICKER, true)
            }
            if (isAutoBackupFolderPicker) {
                putExtra(FileBrowserActivity.EXTRA_AUTO_BACKUP_FOLDER_PICKER, true)
            }
            if (isSupportAttachmentPicker) {
                putExtra(FileBrowserActivity.EXTRA_SUPPORT_ATTACHMENT_PICKER, true)
            }
        }
        if (isPickerMode || isLocationPickerMode || isSyncFolderPickerMode || isAdvancedSyncFolderPickerMode || isAdvancedSyncDestPickerMode || isCompressDestPickerMode || isImageCompressDestPickerMode || isGifCreatorDestPickerMode || isExtractDestPickerMode || isNetworkCachePickerMode || isQuickTransferPickerMode || isShareDestPickerMode || isNotepadFolderPicker || isScannerFolderPicker || isAutoBackupFolderPicker || isSupportAttachmentPicker) {
            pickerLauncher.launch(intent)
        } else {
            za.kilowatch.ultimatefilemanager.util.AnimationHelper.startActivityWithTransition(this, intent)
            showPremiumSnackbar(getString(R.string.opening_itemlabel, item.label))
        }
    }


    /**
     * Sets up the [ItemTouchHelper] for mobile drag-and-drop tile reordering.
     * - Long-press (2 s) on any tile calls [ItemTouchHelper.startDrag].
     * - The dragged tile scales to 1.1Ã— with elevated shadow.
     * - Dropping is blocked on/after locked tiles.
     * - Order is persisted to [TileOrderManager] when the finger lifts.
     * - Dragging over the [fabHideTile] drop-zone hides the tile.
     */
    private var dragTargetCustomTileId: String? = null

    private fun setupItemTouchHelper() {
        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0
        ) {
            override fun getMovementFlags(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ): Int {
                return makeMovementFlags(
                    ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0
                )
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to   = target.bindingAdapterPosition
                // Allow all moves — custom tile drop is handled in clearView
                storageAdapter.moveItem(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) { /* disabled */ }

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
                    viewHolder.itemView.animate().scaleX(1.1f).scaleY(1.1f).setDuration(150).start()
                    viewHolder.itemView.elevation = 24f
                    val item = storageAdapter.getItems().getOrNull(viewHolder.bindingAdapterPosition)
                    draggedItem = item
                } else if (actionState == ItemTouchHelper.ACTION_STATE_IDLE) {
                    // Don't clear draggedItem or dragTargetCustomTileId here â€”
                    // onSelectedChanged(IDLE) fires BEFORE clearView(), so we
                    // must keep them for clearView to do the move logic.
                }
            }

            override fun onChildDraw(
                c: android.graphics.Canvas,
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                dX: Float, dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean
            ) {
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && isCurrentlyActive) {
                    val draggedItemData = storageAdapter.getItems().getOrNull(viewHolder.bindingAdapterPosition)
                    val density = resources.displayMetrics.density

                    // Compute the dragged view's rect once
                    val draggedRect = android.graphics.Rect()
                    viewHolder.itemView.getHitRect(draggedRect)
                    draggedRect.offset(dX.toInt(), dY.toInt())

                    // First pass: find the custom tile with the largest overlap
                    var bestId: String? = null
                    var bestArea: Long = 0

                    for (i in 0 until recyclerView.childCount) {
                        val child = recyclerView.getChildAt(i) ?: continue
                        val vh = recyclerView.getChildViewHolder(child)
                        val pos = vh.bindingAdapterPosition
                        if (pos == RecyclerView.NO_POSITION) continue
                        val item = storageAdapter.getItems().getOrNull(pos) ?: continue
                        if (!item.isCustomTile || draggedItemData?.isCustomTile == true) continue

                        val targetRect = android.graphics.Rect()
                        child.getHitRect(targetRect)

                        if (android.graphics.Rect.intersects(draggedRect, targetRect)) {
                            val overlapW = minOf(draggedRect.right, targetRect.right) - maxOf(draggedRect.left, targetRect.left)
                            val overlapH = minOf(draggedRect.bottom, targetRect.bottom) - maxOf(draggedRect.top, targetRect.top)
                            val area = (overlapW * overlapH).toLong()
                            if (area > bestArea) {
                                bestArea = area
                                bestId = item.id
                            }
                        }
                    }
                    dragTargetCustomTileId = bestId

                    // Second pass: highlight only the best match, clear the rest
                    for (i in 0 until recyclerView.childCount) {
                        val child = recyclerView.getChildAt(i) ?: continue
                        val vh = recyclerView.getChildViewHolder(child)
                        val pos = vh.bindingAdapterPosition
                        if (pos == RecyclerView.NO_POSITION) continue
                        val item = storageAdapter.getItems().getOrNull(pos) ?: continue
                        if (!item.isCustomTile) continue

                        val card = child.findViewById<com.google.android.material.card.MaterialCardView>(R.id.cardStorage)
                        if (item.id == bestId) {
                            card?.strokeWidth = (4 * density).toInt()
                            card?.strokeColor = za.kilowatch.ultimatefilemanager.util.ThemeColors.primary(this@StorageBrowserActivity)
                        } else {
                            card?.strokeWidth = 0
                            card?.strokeColor = android.graphics.Color.TRANSPARENT
                        }
                    }
                }
                super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                viewHolder.itemView.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
                viewHolder.itemView.elevation = 0f

                val droppedItem = draggedItem
                val targetId = dragTargetCustomTileId
                draggedItem = null
                dragTargetCustomTileId = null

                // Reset all card strokes
                for (i in 0 until recyclerView.childCount) {
                    val c = recyclerView.getChildAt(i)
                        ?.findViewById<com.google.android.material.card.MaterialCardView>(R.id.cardStorage)
                    if (c != null) {
                        c.strokeWidth = 0
                        c.strokeColor = android.graphics.Color.TRANSPARENT
                    }
                }

                if (droppedItem != null && targetId != null && !droppedItem.isCustomTile) {
                    // Dropped onto a custom tile â€” move it inside
                    CustomTileManager.setTileParent(this@StorageBrowserActivity, droppedItem.id, targetId)
                    // Add to custom tile's internal order
                    val order = CustomTileManager.loadTileOrder(this@StorageBrowserActivity, targetId).toMutableList()
                    if (droppedItem.id !in order) {
                        order.add(droppedItem.id)
                        CustomTileManager.saveTileOrder(this@StorageBrowserActivity, targetId, order)
                    }
                    val ctData = CustomTileManager.loadCustomTiles(this@StorageBrowserActivity).find { it.id == targetId }
                    showPremiumSnackbar(getString(R.string.custom_tile_moved_to, ctData?.title ?: targetId))
                    loadStorageVolumes()
                } else if (droppedItem?.isCustomTile == true && targetId != null) {
                    // Cannot nest custom tiles
                    showPremiumSnackbar(getString(R.string.custom_tile_cannot_nest))
                } else {
                    // Normal reorder drop — persist the new order
                    val orderedIds = storageAdapter.getRawItems().map { it.id }
                    TileOrderManager.save(this@StorageBrowserActivity, orderedIds)
                    storageAdapter.onDragFinished(this@StorageBrowserActivity, droppedItem)
                    showPremiumSnackbar(getString(R.string.tile_order_saved))
                }
            }

            /** We manage long-press ourselves — disable the system default. */
            override fun isLongPressDragEnabled() = false

            override fun canDropOver(
                recyclerView: RecyclerView,
                current: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                return true
            }
        }
        itemTouchHelper = ItemTouchHelper(callback)
        itemTouchHelper.attachToRecyclerView(recyclerStorage)
    }

    // â”€â”€ Tile hide / unhide helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Marks [item] as hidden: persists its ID to [TileOrderManager], removes it
     * from the adapter immediately, and updates the badge dot.
     *
     * On TV this also:
     *  - Shows a Snackbar naming the tile that was removed.
     *  - Refocuses the next available tile (or the previous one if the hidden tile was last),
     *    preventing focus from jumping to the Done button.
     */
    private fun hideTile(item: StorageItem) {
        if (item.isSafCustomLocation) {
            showRemoveStorageLocationDialog(item)
            return
        }
        // Remember the current focused position so we can restore focus after the list refreshes.
        val currentPos = storageAdapter.getItems().indexOfFirst { it.id == item.id }

        val hidden = TileOrderManager.loadHidden(this).toMutableSet()
        hidden.add(item.id)
        val parentMap = TileOrderManager.loadHiddenParents(this).toMutableMap()
        parentMap[item.id] = null // null = hidden from main screen
        TileOrderManager.saveHidden(this, hidden, parentMap)

        // Show a toast naming the tile that was hidden
        showPremiumSnackbar("\"${item.label}\" tile hidden")

        // Refresh local items immediately so they disappear (and 'lastFullTileList' stays fresh)
        loadStorageVolumes()

        // On TV: restore focus to the next tile (or previous if we just hid the last one)
        if (isTv && currentPos >= 0) {
            recyclerStorage.postDelayed({
                val newCount = storageAdapter.itemCount
                if (newCount == 0) return@postDelayed
                // Target the same position, clamped to the new list size
                val targetPos = currentPos.coerceAtMost(newCount - 1)
                recyclerStorage.scrollToPosition(targetPos)
                recyclerStorage.post {
                    recyclerStorage.findViewHolderForAdapterPosition(targetPos)
                        ?.itemView?.requestFocus()
                }
            }, 120)
        }
    }

    /** Shows or hides the Manage Tiles button (only visible when there are hidden tiles). */
    private fun updateHiddenBadge() {
        val hiddenCount = TileOrderManager.loadHidden(this).size

        // Hide "Create Custom Tile" button in edit mode
        btnAddCustomTile?.visibility = if (isEditMode) View.GONE else View.VISIBLE
        btnSettingsGear?.visibility = if (isEditMode) View.GONE else View.VISIBLE

        if (isEditMode) {
            // In Edit Mode
            if (isTv) {
                btnDoneTv?.visibility = View.VISIBLE
                // Show palette button in TV edit mode
                btnColorTile?.visibility = View.VISIBLE
                btnImportColorCode?.visibility = View.VISIBLE
                // On TV, hide the Gear icon when in Edit Mode for a cleaner look
                btnManageTiles.visibility = View.GONE
            } else {
                btnManageTiles.setImageResource(R.drawable.ic_check)
                btnManageTiles.visibility = View.VISIBLE
                btnManageTiles.clearColorFilter()
                btnManageTiles.setBackgroundResource(R.drawable.bg_btn_icon_frosted)
                // Show color button in edit mode
                btnColorTile?.visibility = View.VISIBLE
                btnImportColorCode?.visibility = View.VISIBLE
            }
        } else {
            // Normal Mode
            if (isTv) {
                btnDoneTv?.visibility = View.GONE
                btnColorTile?.visibility = View.GONE
                btnImportColorCode?.visibility = View.GONE
                isSelectingTileForColor = false
                if (::storageAdapter.isInitialized) storageAdapter.isColorPickMode = false
                btnManageTiles.setImageResource(R.drawable.ic_tune)
                btnManageTiles.visibility = if (hiddenCount > 0) View.VISIBLE else View.GONE
            } else {
                btnManageTiles.setImageResource(R.drawable.ic_tune)
                btnManageTiles.visibility = if (hiddenCount > 0) View.VISIBLE else View.GONE
                btnManageTiles.clearColorFilter()
                btnManageTiles.setBackgroundResource(R.drawable.bg_btn_icon_frosted)
                // Hide color button in normal mode
                btnColorTile?.visibility = View.GONE
                btnImportColorCode?.visibility = View.GONE
            }
        }
    }

    private fun enterEditMode() {
        if (isEditMode) return
        isEditMode = true
        storageAdapter.isEditMode = true
        updateHiddenBadge()
        
        if (isTv) {
            showTvEditInstructions()
        } else {
            showPremiumSnackbar(getString(R.string.edit_mode_tap_x_to_hide_drag_to_reorder))
            // Vibrate for feedback
            recyclerStorage.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        }
    }

    private fun showTvEditInstructions() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_tv_edit_instructions, null)
        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        
        val btnGotIt = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnGotIt)
        btnGotIt.setOnClickListener {
            dialog.dismiss()
        }
        
        // Ensure "Got It" button is focused for D-pad accessibility
        dialog.setOnShowListener {
            btnGotIt.requestFocus()
        }

        dialog.show()
    }

    private fun exitEditMode() {
        if (!isEditMode) return
        isEditMode = false
        storageAdapter.isEditMode = false
        updateHiddenBadge()
        showPremiumSnackbar(getString(R.string.tile_configuration_saved))
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (isEditMode) {
            exitEditMode()
        } else {
            super.onBackPressed()
        }
    }

    /**
     * Builds the complete natural tile list (ignoring hidden filter) so
     * [ManageTilesBottomSheet] can show both hidden and visible tiles.
     * This is a lightweight snapshot â€” no storage I/O, just the current adapter list
     * combined with hidden tiles reconstructed from saved IDs.
     */
    private fun buildAllTilesForSheet(): List<StorageItem> {
        return lastFullTileList
    }



    // â”€â”€ TV D-Pad reorder mode â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Enters TV reorder mode for [item]: snapshot the list, mark the tile visually,
     * and show a hint Snackbar. D-Pad UP/DOWN then moves the tile; OK saves; Back cancels.
     */
    private fun enterTvReorderMode(item: StorageItem) {
        reorderModeOriginalList = storageAdapter.getItems().toList()
        reorderModeItemId       = item.id
        storageAdapter.reorderModeId = item.id
        storageAdapter.notifyDataSetChanged()
        showPremiumSnackbar(getString(R.string.dpad_moves_tile_ok_saves_back_cancels))
    }

    /**
     * Exits TV reorder mode.
     * @param save If true, persists the current order; if false, restores the snapshot.
     */
    private fun exitTvReorderMode(save: Boolean) {
        if (!save) {
            reorderModeOriginalList?.let { storageAdapter.submitList(it) }
            showPremiumSnackbar(getString(R.string.tile_order_cancelled))
        } else {
            val orderedIds = storageAdapter.getItems().map { it.id }
            TileOrderManager.save(this, orderedIds)
            showPremiumSnackbar(getString(R.string.tile_order_saved))
        }
        reorderModeItemId       = null
        reorderModeOriginalList = null
        storageAdapter.reorderModeId = null
        storageAdapter.notifyDataSetChanged()
    }

    /**
     * Moves the tile currently in reorder mode by [direction] (-1 up, +1 down).
     * All tiles are freely movable â€” no locked-tile boundary.
     * Refocuses the tile after the layout settles.
     */
    private fun moveTileInReorderMode(direction: Int) {
        val id = reorderModeItemId ?: return
        val list = storageAdapter.getItems().toMutableList()
        val fromIndex = list.indexOfFirst { it.id == id }
        if (fromIndex < 0) return

        val toIndex = (fromIndex + direction).coerceIn(0, list.lastIndex)
        if (toIndex == fromIndex) return

        list.add(toIndex, list.removeAt(fromIndex))
        storageAdapter.submitList(list)

        // Restore visual reorder state (submitList calls notifyDataSetChanged)
        storageAdapter.reorderModeId = id

        // Refocus the moved tile after RecyclerView settles
        recyclerStorage.postDelayed({
            val newPos = storageAdapter.getItems().indexOfFirst { it.id == id }
            if (newPos >= 0) {
                recyclerStorage.scrollToPosition(newPos)
                recyclerStorage.findViewHolderForAdapterPosition(newPos)?.itemView?.requestFocus()
            }
        }, 80)
    }

    /**
     * Intercepts D-Pad keys while the TV reorder mode is active:
     * UP/DOWN move the tile, OK saves and exits, Back cancels.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isTv && reorderModeItemId != null) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                val cols = if (storageAdapter.viewMode == MainMenuViewModeManager.ViewMode.GRID) {
                    MainMenuViewModeManager.loadColumnCount(this)
                } else 1

                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP    -> { moveTileInReorderMode(-cols); return true }
                    KeyEvent.KEYCODE_DPAD_DOWN  -> { moveTileInReorderMode(cols);  return true }
                    KeyEvent.KEYCODE_DPAD_LEFT  -> { moveTileInReorderMode(-1);    return true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { moveTileInReorderMode(1);     return true }
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER     -> { 
                        // Only save if it's a NEW press (not a repeat of the entering long-press)
                        if (event.repeatCount == 0 && !event.isLongPress) {
                            exitTvReorderMode(save = true)
                            return true 
                        }
                    }
                    KeyEvent.KEYCODE_BACK      -> { exitTvReorderMode(save = false); return true }
                }
            } else if (event.action == KeyEvent.ACTION_UP) {
                // Consume the UP action for our handled keys so nothing else fires
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP,
                    KeyEvent.KEYCODE_DPAD_DOWN,
                    KeyEvent.KEYCODE_DPAD_LEFT,
                    KeyEvent.KEYCODE_DPAD_RIGHT,
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_BACK -> return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * Shows a modern Material Snackbar with premium styling.
     */
    // Track the custom tile being edited (null = creating new)
    private var editingCustomTileId: String? = null
    private var selectedCustomTileIconRes: Int = R.drawable.ic_folder

    private fun showCreateCustomTileDialog(editTileId: String? = null) {
        editingCustomTileId = editTileId
        val isEdit = editTileId != null

        // Load existing data if editing
        val existingData = if (isEdit) {
            CustomTileManager.loadCustomTiles(this).find { it.id == editTileId }
        } else null
        // A custom tile with no (or an unresolvable) saved icon falls back to the
        // default folder icon instead of passing a stale resource ID to setImageResource.
        selectedCustomTileIconRes = existingData?.iconRes?.takeIf { it != 0 } ?: R.drawable.ic_folder

        if (isTv) {
            showCreateCustomTileDialogTv(isEdit, existingData)
        } else {
            showCreateCustomTileDialogMobile(isEdit, existingData)
        }
    }

    private fun showAddOptionsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_menu_options, null)
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogView.findViewById<View>(R.id.btnOptionCreateHeader)?.setOnClickListener {
            dialog.dismiss()
            showCreateHeaderDialog()
        }

        dialogView.findViewById<View>(R.id.btnOptionCreateTile)?.setOnClickListener {
            dialog.dismiss()
            showCreateCustomTileDialog()
        }

        dialogView.findViewById<View>(R.id.btnCancelOptions)?.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showCreateHeaderDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_create_custom_header, null)
        val edtTitle = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.edtHeaderTitle)
        val tilTitle = dialogView.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.tilHeaderTitle)

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogView.findViewById<View>(R.id.btnCancelHeader)?.setOnClickListener {
            dialog.dismiss()
        }

        dialogView.findViewById<View>(R.id.btnCreateHeader)?.setOnClickListener {
            val title = edtTitle?.text?.toString()?.trim() ?: ""
            if (title.isEmpty()) {
                tilTitle?.error = getString(R.string.custom_header_name_required)
                return@setOnClickListener
            }
            tilTitle?.error = null

            val catId = "custom_cat_${System.currentTimeMillis()}"
            MainMenuViewModeManager.saveCustomCategory(this, catId, title)

            val currentOrder = MainMenuViewModeManager.loadCategoryOrder(this).toMutableList()
            if (catId !in currentOrder) {
                currentOrder.add(catId)
                MainMenuViewModeManager.saveCategoryOrder(this, currentOrder)
            }

            // Ensure Modern Categorized mode so the new header is immediately visible
            if (MainMenuViewModeManager.loadViewMode(this) != MainMenuViewModeManager.ViewMode.MODERN_CATEGORIZED) {
                MainMenuViewModeManager.saveViewMode(this, MainMenuViewModeManager.ViewMode.MODERN_CATEGORIZED)
                applyViewMode()
            } else {
                storageAdapter.refreshDisplayedList(this)
                storageAdapter.notifyDataSetChanged()
            }

            showPremiumSnackbar(getString(R.string.custom_header_created))
            dialog.dismiss()
        }

        dialog.show()
        za.kilowatch.ultimatefilemanager.util.DialogInputHelper.setupDialogInput(dialog, edtTitle) {
            dialogView.findViewById<View>(R.id.btnCreateHeader)?.performClick()
        }
    }

    private fun showCreateCustomTileDialogMobile(isEdit: Boolean, existingData: CustomTileManager.CustomTileData?) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_create_custom_tile, null)
        val imgIcon = dialogView.findViewById<ImageView>(R.id.imgCustomTileIcon)
        val txtDialogTitle = dialogView.findViewById<TextView>(R.id.txtCustomTileDialogTitle)
        val edtTitle = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.edtCustomTileTitle)
        val tilTitle = dialogView.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.tilCustomTileTitle)
        val edtSubtitle = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.edtCustomTileSubtitle)
        val switchShowInPicker = dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchShowInPicker)
        val btnCancel = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCancelCustomTile)
        val btnSave = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSaveCustomTile)

        txtDialogTitle?.text = if (isEdit) getString(R.string.custom_tile_edit_title) else getString(R.string.custom_tile_create_title)
        btnSave?.text = if (isEdit) getString(R.string.save) else getString(R.string.custom_tile_add_button)

        if (isEdit && existingData != null) {
            edtTitle.setText(existingData.title)
            edtSubtitle.setText(existingData.subtitle)
            switchShowInPicker.isChecked = existingData.showInFolderPicker
        }
        imgIcon.setImageResource(selectedCustomTileIconRes)
        imgIcon.setOnClickListener {
            showSimpleIconPickerDialog(imgIcon)
        }

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnSave.setOnClickListener {
            val title = edtTitle.text?.toString()?.trim() ?: ""
            if (title.isEmpty()) {
                tilTitle?.error = getString(R.string.custom_tile_title_required)
                return@setOnClickListener
            }
            tilTitle?.error = null
            val subtitle = edtSubtitle.text?.toString()?.trim() ?: ""
            saveCustomTile(title, subtitle, switchShowInPicker.isChecked)
            dialog.dismiss()
        }

        dialog.show()
        za.kilowatch.ultimatefilemanager.util.DialogInputHelper.setupDialogInput(dialog, edtTitle) {
            btnSave.performClick()
        }
        za.kilowatch.ultimatefilemanager.util.DialogInputHelper.setupDoneAction(edtSubtitle) {
            btnSave.performClick()
        }
    }

    /** Shows the full built-in icon picker dialog (same icon set as Tile Color -> Icons). */
    private fun showSimpleIconPickerDialog(targetImageView: ImageView) {
        val allIcons = za.kilowatch.ultimatefilemanager.settings.ALL_BUILTIN_ICONS
        val density = resources.displayMetrics.density

        val dialogView = layoutInflater.inflate(R.layout.dialog_custom_tile_icon_picker, null)
        val rv = dialogView.findViewById<RecyclerView>(R.id.rvIconPicker)
        val btnBrowse = dialogView.findViewById<View>(R.id.btnBrowseIcon)
        val btnDone = dialogView.findViewById<View>(R.id.btnDoneIconPicker)

        rv.layoutManager = GridLayoutManager(this, 5)

        btnBrowse.setOnClickListener {
            activeTileIdForIcon = "custom_tile_icon_picker"
            launchTileIconPicker("custom_tile_icon_picker")
        }

        // Built-in icon adapter (same pattern as TileColorBottomSheet.BuiltinIconAdapter)
        rv.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            private var selectedIcon = selectedCustomTileIconRes

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val size = (48 * density).toInt()
                val pad = (4 * density).toInt()
                val container = FrameLayout(parent.context)
                container.layoutParams = ViewGroup.LayoutParams(size, size)
                container.setPadding(pad, pad, pad, pad)
                val ivId = View.generateViewId()
                val iv = ImageView(parent.context)
                iv.id = ivId
                iv.layoutParams = ViewGroup.LayoutParams(size - pad * 2, size - pad * 2)
                iv.scaleType = ImageView.ScaleType.FIT_CENTER
                container.addView(iv)
                container.tag = ivId
                container.isFocusable = true
                return object : RecyclerView.ViewHolder(container) {
                    val icon: ImageView = iv
                }
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, pos: Int) {
                val iconRes = allIcons[pos]
                val isSelected = iconRes == selectedIcon
                val iv = holder.itemView.findViewById<ImageView>(holder.itemView.tag as Int)
                iv.setImageResource(iconRes)
                iv.setPadding(12, 12, 12, 12)
                val bg = android.graphics.drawable.GradientDrawable()
                bg.shape = android.graphics.drawable.GradientDrawable.OVAL
                if (isSelected) {
                    bg.setColor(0x3300897B.toInt())
                    bg.setStroke(3, 0xFF00897B.toInt())
                } else {
                    bg.setColor(0x0FFFFFFF.toInt())
                    bg.setStroke(1, 0x44000000.toInt())
                }
                iv.background = bg
                holder.itemView.setOnClickListener {
                    selectedIcon = iconRes
                    selectedCustomTileIconRes = iconRes
                    targetImageView.setImageResource(iconRes)
                    notifyDataSetChanged()
                }
            }

            override fun getItemCount() = allIcons.size
        }

        val iconPickerDialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .create()

        btnDone.setOnClickListener {
            iconPickerDialog.dismiss()
        }

        // Restore D-pad focus to the icon preview when the picker dialog is dismissed
        iconPickerDialog.setOnDismissListener {
            targetImageView.requestFocus()
        }

        iconPickerDialog.show()
        iconPickerDialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    private fun showCreateCustomTileDialogTv(isEdit: Boolean, existingData: CustomTileManager.CustomTileData?) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_create_custom_tile_tv, null)
        val imgIcon = dialogView.findViewById<ImageView>(R.id.imgCustomTileIcon)
        val edtTitle = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.edtCustomTileTitle)
        val edtSubtitle = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.edtCustomTileSubtitle)
        val switchShowInPicker = dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchShowInPicker)
        val txtDialogTitle = dialogView.findViewById<TextView>(R.id.txtDialogTitle)
        val btnSave = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSave)
        val btnCancel = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCancel)

        txtDialogTitle.text = if (isEdit) getString(R.string.custom_tile_edit_title) else getString(R.string.custom_tile_create_title)
        btnSave.text = if (isEdit) getString(R.string.save) else getString(R.string.custom_tile_add_button)

        if (isEdit && existingData != null) {
            edtTitle.setText(existingData.title)
            edtSubtitle.setText(existingData.subtitle)
            switchShowInPicker.isChecked = existingData.showInFolderPicker
        }
        imgIcon.setImageResource(selectedCustomTileIconRes)

        // TV D-pad: clicking the icon preview opens the built-in icon picker
        imgIcon.setOnClickListener {
            showSimpleIconPickerDialog(imgIcon)
        }

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnSave.setOnClickListener {
            val title = edtTitle.text?.toString()?.trim() ?: ""
            if (title.isEmpty()) {
                showPremiumSnackbar(getString(R.string.custom_tile_title_required))
                return@setOnClickListener
            }
            val subtitle = edtSubtitle.text?.toString()?.trim() ?: ""
            saveCustomTile(title, subtitle, switchShowInPicker.isChecked)
            dialog.dismiss()
        }
        btnCancel.setOnClickListener { dialog.dismiss() }
        dialog.show()
        za.kilowatch.ultimatefilemanager.util.DialogInputHelper.setupDialogInput(dialog, edtTitle) {
            btnSave.performClick()
        }
        za.kilowatch.ultimatefilemanager.util.DialogInputHelper.setupDoneAction(edtSubtitle) {
            btnSave.performClick()
        }
    }

    private fun showCustomTileOptionsMenu(item: StorageItem) {
        if (isTv) {
            val options = arrayOf(
                getString(R.string.custom_tile_edit_title),
                getString(R.string.custom_tile_delete_title)
            )
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
                .setTitle(item.label)
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> showCreateCustomTileDialog(item.id) // Edit
                        1 -> confirmDeleteCustomTile(item) // Delete
                    }
                }
                .show()
        } else {
            val dialogView = layoutInflater.inflate(R.layout.dialog_custom_tile_options, null)
            val imgHeaderIcon = dialogView.findViewById<ImageView>(R.id.imgCustomTileHeaderIcon)
            val txtHeaderTitle = dialogView.findViewById<TextView>(R.id.txtCustomTileHeaderTitle)
            val txtHeaderSubtitle = dialogView.findViewById<TextView>(R.id.txtCustomTileHeaderSubtitle)
            val btnEdit = dialogView.findViewById<View>(R.id.btnOptionEditCustomTile)
            val btnDelete = dialogView.findViewById<View>(R.id.btnOptionDeleteCustomTile)
            val btnCancel = dialogView.findViewById<View>(R.id.btnCancelCustomTileOptions)

            txtHeaderTitle.text = item.label
            if (!item.subtitle.isNullOrBlank()) {
                txtHeaderSubtitle.text = item.subtitle
                txtHeaderSubtitle.visibility = View.VISIBLE
            } else {
                txtHeaderSubtitle.visibility = View.GONE
            }

            if (item.iconRes != 0) {
                imgHeaderIcon.setImageResource(item.iconRes)
            }

            val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
                .setView(dialogView)
                .create()

            btnEdit.setOnClickListener {
                dialog.dismiss()
                showCreateCustomTileDialog(item.id)
            }

            btnDelete.setOnClickListener {
                dialog.dismiss()
                confirmDeleteCustomTile(item)
            }

            btnCancel.setOnClickListener {
                dialog.dismiss()
            }

            dialog.show()
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        }
    }

    private fun showTvEditOptionsMenu(item: StorageItem) {
        val customTiles = CustomTileManager.loadCustomTiles(this)
        val options = mutableListOf<String>()
        options.add(getString(R.string.dpad_moves_tile_ok_saves_back_cancels)) // Reorder
        if (customTiles.isNotEmpty() && !item.isCustomTile) {
            options.add(getString(R.string.move_to_custom_tile))
        }
        if (item.isSafCustomLocation) {
            options.add(getString(R.string.remove_storage_location_confirm))
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setTitle(item.label)
            .setItems(options.toTypedArray()) { _, which ->
                val selected = options[which]
                when {
                    which == 0 -> enterTvReorderMode(item)
                    selected == getString(R.string.move_to_custom_tile) -> showMoveToCustomTileDialogTv(item)
                    selected == getString(R.string.remove_storage_location_confirm) -> showRemoveStorageLocationDialog(item)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showMoveToCustomTileDialogTv(item: StorageItem) {
        val customTiles = CustomTileManager.loadCustomTiles(this)
        if (customTiles.isEmpty()) {
            showPremiumSnackbar(getString(R.string.move_to_custom_tile_no_tiles))
            return
        }
        val tileNames = customTiles.map { it.title }.toTypedArray()
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setTitle(getString(R.string.move_to_custom_tile))
            .setItems(tileNames) { _, which ->
                val target = customTiles[which]
                CustomTileManager.setTileParent(this, item.id, target.id)
                val order = CustomTileManager.loadTileOrder(this, target.id).toMutableList()
                if (item.id !in order) {
                    order.add(item.id)
                    CustomTileManager.saveTileOrder(this, target.id, order)
                }
                showPremiumSnackbar(getString(R.string.custom_tile_moved_to, target.title))
                loadStorageVolumes()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteCustomTile(item: StorageItem) {
        if (isTv) {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
                .setTitle(getString(R.string.custom_tile_delete_confirm))
                .setMessage(getString(R.string.custom_tile_delete_warning))
                .setPositiveButton(getString(R.string.custom_tile_delete_title)) { _, _ ->
                    deleteCustomTileInternal(item)
                }
                .setNegativeButton(R.string.cancel) { d, _ -> d.dismiss() }
                .show()
        } else {
            val dialogView = layoutInflater.inflate(R.layout.dialog_delete_custom_tile_confirm, null)
            val txtTileName = dialogView.findViewById<TextView>(R.id.txtTileName)
            txtTileName.text = item.label
            txtTileName.visibility = View.VISIBLE

            val btnDeleteConfirm = dialogView.findViewById<View>(R.id.btnDeleteConfirm)
            val btnCancel = dialogView.findViewById<View>(R.id.btnCancel)

            val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
                .setView(dialogView)
                .create()

            btnDeleteConfirm.setOnClickListener {
                dialog.dismiss()
                deleteCustomTileInternal(item)
            }

            btnCancel.setOnClickListener {
                dialog.dismiss()
            }

            dialog.show()
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        }
    }

    private fun deleteCustomTileInternal(item: StorageItem) {
        // Move all child tiles back to main screen
        val childIds = CustomTileManager.getChildTiles(this, item.id)
        for (childId in childIds) {
            CustomTileManager.setTileParent(this, childId, null)
        }
        // Update hidden tile parents for tiles hidden from this custom tile
        val hiddenParents = TileOrderManager.loadHiddenParents(this).toMutableMap()
        for ((tileId, parentId) in hiddenParents) {
            if (parentId == item.id) {
                hiddenParents[tileId] = null
            }
        }
        val hidden = TileOrderManager.loadHidden(this)
        TileOrderManager.saveHidden(this, hidden, hiddenParents)

        CustomTileManager.deleteCustomTile(this, item.id)
        showPremiumSnackbar(getString(R.string.custom_tile_deleted))
        loadStorageVolumes()
    }

    private fun confirmDeleteCustomHeader(item: StorageItem) {
        val catId = item.categoryId ?: return
        val headerTitle = item.label

        if (isTv) {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
                .setTitle(getString(R.string.custom_header_delete_title))
                .setMessage(getString(R.string.custom_header_delete_warning))
                .setPositiveButton(getString(R.string.delete)) { _, _ ->
                    deleteCustomHeaderInternal(catId, headerTitle)
                }
                .setNegativeButton(R.string.cancel) { d, _ -> d.dismiss() }
                .show()
        } else {
            val dialogView = layoutInflater.inflate(R.layout.dialog_delete_category_header_confirm, null)
            val txtHeaderName = dialogView.findViewById<TextView>(R.id.txtHeaderName)
            txtHeaderName.text = headerTitle
            txtHeaderName.visibility = View.VISIBLE

            val txtMessage = dialogView.findViewById<TextView>(R.id.txtDeleteHeaderMessage)
            txtMessage.text = getString(R.string.custom_header_delete_confirm, headerTitle) + "\n" + getString(R.string.custom_header_delete_warning)

            val btnDeleteConfirm = dialogView.findViewById<View>(R.id.btnDeleteConfirm)
            val btnCancel = dialogView.findViewById<View>(R.id.btnCancel)

            val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
                .setView(dialogView)
                .create()

            btnDeleteConfirm.setOnClickListener {
                dialog.dismiss()
                deleteCustomHeaderInternal(catId, headerTitle)
            }

            btnCancel.setOnClickListener {
                dialog.dismiss()
            }

            dialog.show()
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        }
    }

    private fun deleteCustomHeaderInternal(catId: String, headerTitle: String) {
        MainMenuViewModeManager.deleteCustomCategory(this, catId)
        storageAdapter.refreshDisplayedList(this)
        storageAdapter.notifyDataSetChanged()
        showPremiumSnackbar(getString(R.string.custom_header_deleted, headerTitle))
    }

    private fun saveCustomTile(title: String, subtitle: String, showInFolderPicker: Boolean = false) {
        val id = editingCustomTileId ?: CustomTileManager.generateId()
        val data = CustomTileManager.CustomTileData(
            id = id,
            title = title,
            subtitle = subtitle,
            iconRes = selectedCustomTileIconRes,
            showInFolderPicker = showInFolderPicker
        )
        CustomTileManager.saveCustomTile(this, data)
        // Sync icon to TileIconManager so "Tile Color â†’ Icons" and "Edit â†’ Select Icon"
        // always agree â€” whichever was set last wins, and both paths read the same value.
        TileIconManager.saveTileIconRes(this, id, selectedCustomTileIconRes)
        val wasEdit = editingCustomTileId != null
        editingCustomTileId = null
        showPremiumSnackbar(
            if (wasEdit) getString(R.string.custom_tile_updated)
            else getString(R.string.custom_tile_created)
        )
        loadStorageVolumes()
    }

    private fun showPremiumSnackbar(message: String) {
        val rootView = findViewById<View>(R.id.main)
        Snackbar.make(rootView, message, Snackbar.LENGTH_SHORT)
            .setBackgroundTint(getColor(R.color.ufm_surface_variant))
            .setTextColor(getColor(R.color.ufm_text_primary))
            .setActionTextColor(getColor(R.color.ufm_primary))
            .show()
    }

    /**
     * Re-orders [allItems] according to the user's saved tile order.
     *
     * Algorithm:
     * 1. Splits list into [nonLocked] + [locked] (locked always stays at bottom).
     * 2. Loads the saved order from [TileOrderManager].
     * 3. Calls [TileOrderManager.mergeWithNatural] which:
     *    - Drops saved IDs that no longer exist.
     *    - Inserts new IDs after the last tile of the same category.
     * 4. Returns reordered non-locked tiles followed by locked tiles.
     */
    private fun applyTileOrder(allItems: List<StorageItem>): List<StorageItem> {
        val saved  = TileOrderManager.load(this)
        if (saved.isEmpty()) return allItems  // No saved order yet â€” use natural list

        val mergedIds   = TileOrderManager.mergeWithNatural(saved, allItems)
        val byId        = allItems.associateBy { it.id }
        val reordered   = mergedIds.mapNotNull { byId[it] }

        return reordered
    }

    /**
     * Enumerates all mounted storage volumes using [StorageManager].
     *
     * All blocking I/O (StatFs, filesystem scans) is dispatched to [Dispatchers.IO]
     * so the main thread is never stalled. Results are applied on the main thread
     * once the background work completes.
     */
    /**
     * Tags items that belong inside custom tile groups with [StorageItem.parentCustomTileId],
     * removes them from the main list, and adds [StorageItem] entries for the custom tile
     * containers themselves (filtered by [showFeatureTiles] and [CustomTileData.showInFolderPicker]).
     *
     * Must be called AFTER all storage volumes, network shares, online storages, and feature
     * tiles have been added to [storageItems], so the parent map correctly identifies children.
     */
    private fun removeCustomTileChildrenAndAddContainers(storageItems: MutableList<StorageItem>, showFeatureTiles: Boolean) {
        val customTiles = CustomTileManager.loadCustomTiles(this)
        val parentMap = CustomTileManager.getTileParentMap(this)

        // Tag items with their parent custom tile ID
        for (i in storageItems.indices) {
            val parentId = parentMap[storageItems[i].id]
            if (parentId != null) {
                storageItems[i] = storageItems[i].copy(parentCustomTileId = parentId)
            }
        }

        // Add custom tile container entries (only those opted in for picker mode)
        for (ct in customTiles) {
            if (!showFeatureTiles && !ct.showInFolderPicker) continue
            storageItems.add(StorageItem(
                id = ct.id,
                label = ct.title,
                iconRes = if (ct.iconRes != 0) ct.iconRes else R.drawable.ic_folder,
                totalBytes = 0,
                usedBytes = 0,
                mountPath = "",
                isCustomTile = true,
                subtitle = ct.subtitle.ifEmpty { null }
            ))
        }

        // Remove child tiles from the main list (they belong inside custom tile containers)
        storageItems.removeAll { it.parentCustomTileId != null }
    }

    private fun loadStorageVolumes() {
        // Capture values needed on the background thread before leaving the main thread.
        val previousPaths = knownMountPaths.toSet()
        val capturedIsTv = isTv
        val capturedIsAmazon = isAmazon
        val capturedIsDrivePicker = isDrivePicker
        val capturedIsSyncFolderPickerMode = isSyncFolderPickerMode
        val capturedIsAdvancedSyncFolderPickerMode = isAdvancedSyncFolderPickerMode
        val capturedIsAdvancedSyncDestPickerMode = isAdvancedSyncDestPickerMode
        val capturedIsPickerMode = isPickerMode
        val capturedIsCompressDestPickerMode = isCompressDestPickerMode
        val capturedIsLocationPickerMode = isLocationPickerMode
        val capturedIsSearchFolderPicker = isSearchFolderPicker
        val capturedIsNetworkCachePickerMode = isNetworkCachePickerMode
        val capturedIsQuickTransferPickerMode = isQuickTransferPickerMode
        val capturedIsShareDestPickerMode = isShareDestPickerMode
        val capturedIsNotepadFolderPicker = isNotepadFolderPicker
        val capturedIsKeyfilePickerMode = isKeyfilePickerMode
        val capturedIsCertPickerMode = isCertPickerMode
        val capturedIsScannerFolderPicker = isScannerFolderPicker
        val capturedIsAutoBackupFolderPicker = isAutoBackupFolderPicker
        val capturedIsSupportAttachmentPicker = isSupportAttachmentPicker
        val capturedIsExtractDestPickerMode = isExtractDestPickerMode
        val capturedIsImageCompressDestPickerMode = isImageCompressDestPickerMode
        val capturedIsGifCreatorDestPickerMode = isGifCreatorDestPickerMode

        lifecycleScope.launch(Dispatchers.IO) {
            val storageManager = getSystemService(Context.STORAGE_SERVICE) as StorageManager
            val volumes = storageManager.storageVolumes
            val storageItems = mutableListOf<StorageItem>()
            val discoveredPaths = mutableSetOf<String>()
            val newKnownPaths = mutableSetOf<String>()

            // Reset SELinux-blocked detection for this scan cycle
            usbSelinuxBlocked = false

            Log.d(TAG, "StorageManager reports ${volumes.size} volume(s)")
            val seenVolumeIds   = mutableSetOf<String>()
            val seenMountPaths  = mutableSetOf<String>()
            for (volume in volumes) {
                val item = volumeToStorageItem(volume) ?: continue
                // Guard against firmware bugs that return the same volume twice â€”
                // deduplicate by both id (UUID/"internal") and mountPath.
                if (!seenVolumeIds.add(item.id) || !seenMountPaths.add(item.mountPath)) {
                    Log.w(TAG, "Skipping duplicate storage volume: id=${item.id} path=${item.mountPath}")
                    continue
                }
                storageItems.add(item)
                newKnownPaths.add(item.mountPath)
                discoveredPaths.add(item.mountPath)
            }

            // Composite flag: feature tiles are suppressed in any picker mode
            val showFeatureTiles = !capturedIsDrivePicker && !capturedIsQuickTransferPickerMode && !capturedIsShareDestPickerMode && !capturedIsNotepadFolderPicker && !capturedIsKeyfilePickerMode && !capturedIsCertPickerMode && !capturedIsScannerFolderPicker && !capturedIsAutoBackupFolderPicker && !capturedIsSupportAttachmentPicker && !capturedIsImageCompressDestPickerMode && !capturedIsGifCreatorDestPickerMode && !capturedIsPickerMode && !capturedIsCompressDestPickerMode && !capturedIsExtractDestPickerMode && !capturedIsLocationPickerMode && !capturedIsSyncFolderPickerMode && !capturedIsAdvancedSyncFolderPickerMode && !capturedIsAdvancedSyncDestPickerMode && !capturedIsNetworkCachePickerMode && !capturedIsSearchFolderPicker

            // Add Twin Window tile at the very top (first in list)
            if (showFeatureTiles) {
                storageItems.add(0, StorageItem(
                    id = "twin_window_tile",
                    label = getString(R.string.twin_window_title),
                    iconRes = R.drawable.ic_twin_window,
                    totalBytes = 0,
                    usedBytes = 0,
                    mountPath = "",
                    isTwinWindowTile = true
                ))
            }

            // Add Notepad tile (after twin window)
            if (showFeatureTiles) {
                storageItems.add(StorageItem(
                    id = "notepad_tile",
                    label = getString(R.string.notepad),
                    iconRes = R.drawable.ic_notepad,
                    totalBytes = 0,
                    usedBytes = 0,
                    mountPath = "",
                    isNotepadTile = true,
                    subtitle = getString(R.string.notepad_tile_subtitle)
                ))
            }

            // Add Document Scanner tile (mobile only, after notepad)
            if (!capturedIsTv && showFeatureTiles) {
                storageItems.add(StorageItem(
                    id = "scanner_tile",
                    label = getString(R.string.scanner_title),
                    iconRes = R.drawable.ic_scanner,
                    totalBytes = 0,
                    usedBytes = 0,
                    mountPath = "",
                    isScannerTile = true,
                    subtitle = getString(R.string.scanner_tile_subtitle)
                ))
            }

            // Fallback: scan common USB mount paths that StorageManager may miss on TV.
            // Each StatFs call is capped at STAT_TIMEOUT_MS to avoid slow USB controllers
            // blocking the whole scan.
            if (capturedIsTv) {
                scanExtraMountPaths(storageItems, discoveredPaths)
            }

            // On Amazon FireOS, if USB drives at /mnt/media_rw/ are blocked by SELinux,
            // add a Shizuku guidance card so the user knows how to access their USB storage.
            if (usbSelinuxBlocked && !capturedIsImageCompressDestPickerMode) {
                storageItems.add(StorageItem(
                    id = "shizuku_usb_access",
                    label = getString(R.string.shizuku_usb_access_title),
                    iconRes = R.drawable.ic_shizuku_logo,
                    totalBytes = 0,
                    usedBytes = 0,
                    mountPath = "",
                    isShizukuTile = true,
                    subtitle = getString(R.string.shizuku_usb_access_subtitle)
                ))
            }

            // Add Custom SAF Storage Locations (Termux, Document Providers, USB/Custom Folders)
            if (!capturedIsSearchFolderPicker) {
                val safLocations = SafLocationRepository.getLocations(this@StorageBrowserActivity)
                for (loc in safLocations) {
                    val iconRes = if (loc.iconType == "terminal" || loc.authority.contains("termux")) R.drawable.ic_terminal else R.drawable.ic_folder

                    storageItems.add(StorageItem(
                        id = "saf_location_${loc.id}",
                        label = loc.displayName,
                        iconRes = iconRes,
                        totalBytes = 0,
                        usedBytes = 0,
                        mountPath = "saf://${loc.id}",
                        isSafCustomLocation = true,
                        safLocation = loc,
                        subtitle = getString(R.string.saf_storage)
                    ))
                }
            }

            // In sync/notepad/network-cache/search folder picker mode only show local device storage
            if (capturedIsSyncFolderPickerMode || capturedIsAdvancedSyncFolderPickerMode || capturedIsNotepadFolderPicker || capturedIsNetworkCachePickerMode || capturedIsSearchFolderPicker) {
                removeCustomTileChildrenAndAddContainers(storageItems, showFeatureTiles)
                withContext(Dispatchers.Main) {
                    knownMountPaths.clear()
                    knownMountPaths.addAll(newKnownPaths)
                    storageAdapter.submitList(storageItems.filterForTileIconPicker())
                    updateEmptyState(storageItems.isEmpty())
                }
                return@launch
            }

            // Add configured online storages
            val onlineRepo = za.kilowatch.ultimatefilemanager.network.OnlineStorageRepository.getInstance(this@StorageBrowserActivity)
            val onlineStorages = onlineRepo.getAll()
            for (storage in onlineStorages) {
                if (storage.isCredentialsStripped) continue
                storageItems.add(StorageItem(
                    id = storage.id,
                    label = storage.displayName,
                    iconRes = R.drawable.ic_cloud,
                    totalBytes = 0,
                    usedBytes = 0,
                    mountPath = storage.email,
                    isOnlineStorage = true,
                    onlineStorage = storage,
                    subtitle = storage.email
                ))
            }

            // Add configured network shares (SMB/FTP) right below local storage
            val repo = za.kilowatch.ultimatefilemanager.network.NetworkShareRepository.getInstance(this@StorageBrowserActivity)
            val shares = repo.getAll()
            for (share in shares) {
                if (share.isCredentialsStripped) continue
                val icon = if (share.type == za.kilowatch.ultimatefilemanager.network.ShareType.SMB) {
                    R.drawable.ic_network
                } else {
                    R.drawable.ic_network
                }
                storageItems.add(StorageItem(
                    id = share.id,
                    label = share.name,
                    iconRes = icon,
                    totalBytes = 0,
                    usedBytes = 0,
                    mountPath = share.docIdPrefix, // use docIdPrefix as mountPath for network roots
                    isNetworkRoot = true,
                    networkShare = share
                ))
            }

            val pairedDevices = za.kilowatch.ultimatefilemanager.network.PairingManager.getInstance(this@StorageBrowserActivity).getAllPairedDevices()
            for (device in pairedDevices) {
                if (device.isConnected) {
                    val defaultLabel = if (device.isTv) getString(R.string.connected_tv) else getString(R.string.connected_phone)
                    val iconRes = if (device.isTv) R.drawable.ic_remote_manage else R.drawable.ic_phone
                    val deviceTypeLabel = if (device.isTv) getString(R.string.android_tv) else getString(R.string.phone)

                    storageItems.add(StorageItem(
                        id = "tv_${device.deviceId}", // Keep ID prefix identical for backward compatibility
                        label = device.name.ifEmpty { defaultLabel },
                        iconRes = iconRes,
                        totalBytes = 0,
                        usedBytes = 0,
                        mountPath = "tv://${device.deviceId}",
                        isNetworkRoot = true,
                        subtitle = getString(R.string.devicetypelabel_u2022_devicelastip, deviceTypeLabel, device.lastIp),
                        networkShare = za.kilowatch.ultimatefilemanager.network.NetworkShare(
                            id = device.deviceId,
                            name = device.name,
                            type = za.kilowatch.ultimatefilemanager.network.ShareType.TV,
                            host = device.lastIp,
                            port = device.lastPort,
                            readOnly = false
                        )
                    ))
                }
            }

            // Apply custom drive names from Room DB
            val renameMap = za.kilowatch.ultimatefilemanager.settings.renamer.StorageRenameManager.getInstance(this@StorageBrowserActivity).getRenameMap()
            for (i in storageItems.indices) {
                val item = storageItems[i]
                if (item.isRemovable || item.id == "internal") {
                    val hashedId = za.kilowatch.ultimatefilemanager.settings.renamer.StorageRenameManager.hashDeviceId(item.id)
                    renameMap[hashedId]?.let { customName ->
                        storageItems[i] = item.copy(label = customName)
                    }
                }
            }

            // Detect newly mounted devices
            if (previousPaths.isNotEmpty()) {
                storageItems.forEach { item ->
                    if (item.mountPath !in previousPaths) {
                        item.isNewlyMounted = true
                    }
                }
            }

            // Dest / Folder pickers: show local + network + online storages — no feature/favorites tiles
            if (capturedIsScannerFolderPicker || capturedIsAutoBackupFolderPicker ||
                capturedIsAdvancedSyncDestPickerMode || capturedIsGifCreatorDestPickerMode ||
                capturedIsCompressDestPickerMode || capturedIsImageCompressDestPickerMode ||
                capturedIsExtractDestPickerMode || capturedIsSyncFolderPickerMode ||
                capturedIsAdvancedSyncFolderPickerMode || capturedIsShareDestPickerMode ||
                capturedIsNotepadFolderPicker || capturedIsSupportAttachmentPicker) {
                removeCustomTileChildrenAndAddContainers(storageItems, showFeatureTiles)
                withContext(Dispatchers.Main) {
                    knownMountPaths.clear()
                    knownMountPaths.addAll(newKnownPaths)
                    storageAdapter.setTileColors(TileColorManager.loadTileColors(this@StorageBrowserActivity))
                    storageAdapter.setTileIcons(TileIconManager.getAllTileIcons(this@StorageBrowserActivity))
                    storageAdapter.setTileIconRes(TileIconManager.getAllTileIconRes(this@StorageBrowserActivity))
                    storageAdapter.submitList(storageItems.filterForTileIconPicker())
                    updateEmptyState(storageItems.isEmpty())
                }
                return@launch
            }

            if (capturedIsPickerMode || capturedIsKeyfilePickerMode) {
                removeCustomTileChildrenAndAddContainers(storageItems, showFeatureTiles)
                withContext(Dispatchers.Main) {
                    knownMountPaths.clear()
                    knownMountPaths.addAll(newKnownPaths)
                    storageAdapter.setTileColors(TileColorManager.loadTileColors(this@StorageBrowserActivity))
                    storageAdapter.setTileIcons(TileIconManager.getAllTileIcons(this@StorageBrowserActivity))
                    storageAdapter.setTileIconRes(TileIconManager.getAllTileIconRes(this@StorageBrowserActivity))
                    storageAdapter.submitList(storageItems.filterForTileIconPicker())
                    updateEmptyState(storageItems.isEmpty())
                }
                return@launch
            }

            // Cert picker: show all real storage + network shares — no feature tiles
            if (capturedIsCertPickerMode) {
                removeCustomTileChildrenAndAddContainers(storageItems, showFeatureTiles)
                withContext(Dispatchers.Main) {
                    knownMountPaths.clear()
                    knownMountPaths.addAll(newKnownPaths)
                    storageAdapter.setTileColors(TileColorManager.loadTileColors(this@StorageBrowserActivity))
                    storageAdapter.setTileIcons(TileIconManager.getAllTileIcons(this@StorageBrowserActivity))
                    storageAdapter.setTileIconRes(TileIconManager.getAllTileIconRes(this@StorageBrowserActivity))
                    storageAdapter.submitList(storageItems.filterForTileIconPicker())
                    updateEmptyState(storageItems.isEmpty())
                }
                return@launch
            }
            val favorites = za.kilowatch.ultimatefilemanager.settings.FavoritesManager.getFavorites(this@StorageBrowserActivity)
            val networkShareRepo = za.kilowatch.ultimatefilemanager.network.NetworkShareRepository.getInstance(this@StorageBrowserActivity)
            val pairedDeviceRepo = za.kilowatch.ultimatefilemanager.network.PairingManager.getInstance(this@StorageBrowserActivity)
            for (fav in favorites) {
                if (fav.isNetwork && fav.shareId != null) {
                    if (!fav.shareId.startsWith("tv_")) {
                        val netShare = networkShareRepo.getById(fav.shareId)
                        if (netShare != null && netShare.isCredentialsStripped) {
                            continue
                        }
                        val onlineShare = onlineRepo.getById(fav.shareId)
                        if (onlineShare != null && onlineShare.isCredentialsStripped) {
                            continue
                        }
                    }
                }
                storageItems.add(StorageItem(
                    id = fav.id,
                    label = fav.label,
                    iconRes = R.drawable.ic_star,
                    totalBytes = 0,
                    usedBytes = 0,
                    mountPath = "",
                    isFavoriteTile = true,
                    favoritePath = fav.path,
                    favoriteIsFolder = fav.isFolder,
                    favoriteIsNetwork = fav.isNetwork,
                    networkShare = if (fav.isNetwork && fav.shareId != null) {
                        if (fav.shareId.startsWith("tv_")) {
                            val deviceId = fav.shareId.removePrefix("tv_")
                            val device = pairedDeviceRepo.getAllPairedDevices().find { it.deviceId == deviceId }
                            if (device != null) {
                                za.kilowatch.ultimatefilemanager.network.NetworkShare(
                                    id = device.deviceId,
                                    name = device.name,
                                    type = za.kilowatch.ultimatefilemanager.network.ShareType.TV,
                                    host = device.lastIp,
                                    port = device.lastPort,
                                    readOnly = false
                                )
                            } else null
                        } else {
                            val netShare = networkShareRepo.getById(fav.shareId)
                            if (netShare != null) {
                                netShare
                            } else {
                                val onlineShare = onlineRepo.getById(fav.shareId)
                                if (onlineShare != null) {
                                    za.kilowatch.ultimatefilemanager.network.NetworkShare(
                                        id = onlineShare.id,
                                        name = onlineShare.displayName,
                                        type = when (onlineShare.provider) {
                                            za.kilowatch.ultimatefilemanager.network.OnlineStorageProvider.ONEDRIVE -> za.kilowatch.ultimatefilemanager.network.ShareType.ONEDRIVE
                                            za.kilowatch.ultimatefilemanager.network.OnlineStorageProvider.GOOGLE_DRIVE -> za.kilowatch.ultimatefilemanager.network.ShareType.GOOGLE_DRIVE
                                            za.kilowatch.ultimatefilemanager.network.OnlineStorageProvider.DROPBOX -> za.kilowatch.ultimatefilemanager.network.ShareType.DROPBOX
                                            za.kilowatch.ultimatefilemanager.network.OnlineStorageProvider.AWS_S3 -> za.kilowatch.ultimatefilemanager.network.ShareType.AWS_S3
                                            za.kilowatch.ultimatefilemanager.network.OnlineStorageProvider.IDRIVE_E2 -> za.kilowatch.ultimatefilemanager.network.ShareType.IDRIVE_E2
                                            za.kilowatch.ultimatefilemanager.network.OnlineStorageProvider.WEBDAV -> za.kilowatch.ultimatefilemanager.network.ShareType.WEBDAV
                                            za.kilowatch.ultimatefilemanager.network.OnlineStorageProvider.RCLONE -> za.kilowatch.ultimatefilemanager.network.ShareType.WEBDAV
                                        },
                                        host = when (onlineShare.provider) {
                                            za.kilowatch.ultimatefilemanager.network.OnlineStorageProvider.RCLONE -> za.kilowatch.ultimatefilemanager.network.RCloneShareClient.RCLONE_HOST_MARKER
                                            else -> if (onlineShare.isWebDavProvider) onlineShare.webDavUrl ?: onlineShare.email else onlineShare.s3Endpoint ?: onlineShare.email
                                        },
                                        port = 0,
                                        username = when (onlineShare.provider) {
                                            za.kilowatch.ultimatefilemanager.network.OnlineStorageProvider.RCLONE -> onlineShare.id
                                            else -> if (onlineShare.isWebDavProvider) onlineShare.webDavUsername ?: "" else onlineShare.s3AccessKey ?: ""
                                        },
                                        password = if (onlineShare.isWebDavProvider) onlineShare.webDavPassword ?: "" else onlineShare.s3SecretKey ?: "",
                                        remotePath = "/",
                                        readOnly = false
                                    )
                                } else null
                            }
                        }
                    } else null
                ))
            }

            if (showFeatureTiles) {
                // Add APK / XAPK Extracts tile â€” only if the folder is non-empty
                val extractsDir = File(
                    getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS),
                    "UFM-Extracted"
                )
                if (extractsDir.exists() && extractsDir.listFiles()?.isNotEmpty() == true) {
                    val fileCount = extractsDir.listFiles()?.size ?: 0
                    storageItems.add(StorageItem(
                        id = "extracts_tile",
                        label = getString(R.string.apk_xapk_extracts),
                        iconRes = R.drawable.ic_apps,
                        totalBytes = 0,
                        usedBytes = 0,
                        mountPath = extractsDir.absolutePath,
                        isExtractsTile = true,
                        subtitle = "$fileCount ${getString(R.string.extracts_subtitle_files)}"
                    ))
                }

                // Add Recycle Bin tile (only when enabled)
                if (za.kilowatch.ultimatefilemanager.recycle.RecycleBinSettingsManager.isEnabled(this@StorageBrowserActivity)) {
                    storageItems.add(StorageItem(
                        id = "recycle_bin_tile",
                        label = getString(R.string.recycle_bin_title),
                        iconRes = R.drawable.ic_delete,
                        totalBytes = 0,
                        usedBytes = 0,
                        mountPath = "",
                        isRecycleBinTile = true
                    ))
                }

                // Add the Paired Devices tile (above App Access)
                storageItems.add(StorageItem(
                    id = "paired_devices_tile",
                    label = if (capturedIsTv) getString(R.string.paired_phones_1) else getString(R.string.paired_tvs_1),
                    iconRes = R.drawable.ic_tv,
                    totalBytes = 0,
                    usedBytes = 0,
                    mountPath = "",
                    isPairedDevicesTile = true
                ))

                // Add the TV Remote tile (Mobile only)
                if (!capturedIsTv) {
                    storageItems.add(StorageItem(
                        id = "tv_remote_tile",
                        label = getString(R.string.tv_remote),
                        iconRes = R.drawable.ic_tv_remote,
                        totalBytes = 0,
                        usedBytes = 0,
                        mountPath = "",
                        isTvRemoteTile = true,
                        subtitle = getString(R.string.tv_remote_subtitle)
                    ))
                }

                // Add the ADB Terminal tile
                storageItems.add(StorageItem(
                    id = "terminal_tile",
                    label = getString(R.string.adb_terminal_title),
                    iconRes = R.drawable.ic_terminal,
                    totalBytes = 0,
                    usedBytes = 0,
                    mountPath = "",
                    isTerminalTile = true
                ))

                // Add the Shizuku tile
                storageItems.add(StorageItem(
                    id = "shizuku_tile",
                    label = getString(R.string.shizuku_title),
                    iconRes = R.drawable.ic_shizuku_logo,
                    totalBytes = 0,
                    usedBytes = 0,
                    mountPath = "",
                    isShizukuTile = true,
                    subtitle = getString(R.string.shizuku_subtitle)
                ))

                // Add the Apps tile
                storageItems.add(StorageItem(
                    id = "apps_tile",
                    label = getString(R.string.perm_query_apps_title),
                    iconRes = R.drawable.ic_apps,
                    totalBytes = 0,
                    usedBytes = 0,
                    mountPath = "",
                    isAppsTile = true
                ))

                // Add the Remote Manage tile
                storageItems.add(StorageItem(
                    id = "remote_tile",
                    label = getString(R.string.remote_manage_btn),
                    iconRes = R.drawable.ic_remote_manage,
                    totalBytes = 0,
                    usedBytes = 0,
                    mountPath = "",
                    isRemoteTile = true
                ))

                // Add the File Server tile (FTP/SFTP hosting)
                storageItems.add(StorageItem(
                    id = "file_server_tile",
                    label = getString(R.string.file_server_title),
                    iconRes = R.drawable.ic_file_server,
                    totalBytes = 0,
                    usedBytes = 0,
                    mountPath = "",
                    isFileServerTile = true
                ))

                // Add the Encrypted Vault tile
                storageItems.add(StorageItem(
                    id = "vault_tile",
                    label = getString(R.string.vault_title),
                    iconRes = R.drawable.ic_lock,
                    totalBytes = 0,
                    usedBytes = 0,
                    mountPath = "",
                    isVaultTile = true
                ))

                // Add the Search tile
                storageItems.add(StorageItem(
                    id = "search_tile",
                    label = getString(R.string.search_title),
                    iconRes = R.drawable.ic_search,
                    totalBytes = 0,
                    usedBytes = 0,
                    mountPath = "",
                    isSearchTile = true
                ))

                // Add the Storage Analyzer tile
                storageItems.add(StorageItem(
                    id = "analyzer_tile",
                    label = getString(R.string.analyzer_title),
                    iconRes = R.drawable.ic_analyzer,
                    totalBytes = 0,
                    usedBytes = 0,
                    mountPath = "",
                    isAnalyzerTile = true
                ))

                // Add the Smart Sort tile
                storageItems.add(StorageItem(
                    id = "smart_sort_tile",
                    label = getString(R.string.smart_sort_title),
                    iconRes = R.drawable.ic_sort,
                    totalBytes = 0,
                    usedBytes = 0,
                    mountPath = "",
                    isSmartSortTile = true
                ))

                // Add the Network Shares tile â€” above SAF tile
                storageItems.add(StorageItem(
                    id = "network_tile",
                    label = getString(R.string.network_tile_title),
                    iconRes = R.drawable.ic_network,
                    totalBytes = 0,
                    usedBytes = 0,
                    mountPath = "",
                    isNetworkTile = true
                ))

                // Add the Online Storages tile
                storageItems.add(StorageItem(
                    id = "online_storages_tile",
                    label = getString(R.string.online_storages_title),
                    iconRes = R.drawable.ic_cloud,
                    totalBytes = 0,
                    usedBytes = 0,
                    mountPath = "",
                    isOnlineStoragesTile = true
                ))

                // Add the Folder Sync tile â€” mobile only, directly below the Network Shares manager tile
                if (!capturedIsTv) {
                    storageItems.add(StorageItem(
                        id = "sync_tile",
                        label = getString(R.string.sync_title),
                        iconRes = R.drawable.ic_sync,
                        totalBytes = 0,
                        usedBytes = 0,
                        mountPath = "",
                        isSyncTile = true
                    ))
                }

                // Add the Advanced Sync tile â€” mobile only, alongside Folder Sync tile
                if (!capturedIsTv) {
                    storageItems.add(StorageItem(
                        id = "advanced_sync_tile",
                        label = getString(R.string.advanced_sync_title),
                        iconRes = R.drawable.ic_sync_advanced,
                        totalBytes = 0,
                        usedBytes = 0,
                        mountPath = "",
                        isAdvancedSyncTile = true
                    ))
                }

                // Add the Add Storage Location action tile
                storageItems.add(StorageItem(
                    id = "add_storage_location_tile",
                    label = getString(R.string.add_storage_location_title),
                    iconRes = R.drawable.ic_folder,
                    totalBytes = 0,
                    usedBytes = 0,
                    mountPath = "",
                    isAddStorageLocationTile = true,
                    subtitle = getString(R.string.add_storage_location_subtitle)
                ))

                // Add the Settings tile (Font Size etc.) â€” just above Legal
                storageItems.add(StorageItem(
                    id = "settings_tile",
                    label = getString(R.string.font_size_title),
                    iconRes = R.drawable.ic_font_size,
                    totalBytes = 0,
                    usedBytes = 0,
                    mountPath = "",
                    isSettingsTile = true
                ))

                // Add the Legal tile
                storageItems.add(StorageItem(
                    id = "legal_tile",
                    label = getString(R.string.policy_selection_title),
                    iconRes = R.drawable.ic_policy,
                    totalBytes = 0,
                    usedBytes = 0,
                    mountPath = "",
                    isLegalTile = true
                ))

                // Add the Rate Us tile â€” shown on all devices
                storageItems.add(StorageItem(
                    id = "rate_us_tile",
                    label = getString(R.string.rate_us_title),
                    iconRes = R.drawable.ic_star,
                    totalBytes = 0,
                    usedBytes = 0,
                    mountPath = "",
                    isRateUsTile = true
                ))

                // Add the Tip Jar tile â€” shown on ALL devices including Amazon.
                if (isInternetAvailable()) {
                    storageItems.add(StorageItem(
                        id = "tip_jar_tile",
                        label = getString(R.string.tip_jar_title),
                        iconRes = R.drawable.ic_coffee,
                        totalBytes = 0,
                        usedBytes = 0,
                        mountPath = "",
                        isTipJarTile = true
                    ))
                }

                // Add the Help & Support tile — adjacent to About
                storageItems.add(StorageItem(
                    id = "support_tile",
                    label = getString(R.string.support_title),
                    iconRes = R.drawable.ic_support,
                    totalBytes = 0,
                    usedBytes = 0,
                    mountPath = "",
                    isSupportTile = true
                ))

                // Add the About tile
                storageItems.add(StorageItem(
                    id = "about_tile",
                    label = getString(R.string.about_title),
                    iconRes = R.drawable.ic_about,
                    totalBytes = 0,
                    usedBytes = 0,
                    mountPath = "",
                    isAboutTile = true
                ))
            }

            // Handle custom tile parent-child relationships and container entries.
            // Runs here for normal (non-picker) mode where all items are in storageItems.
            removeCustomTileChildrenAndAddContainers(storageItems, showFeatureTiles)

            // Filter out any tiles the user has chosen to hide, then publish on main thread.
            val fullList = storageItems.toList()
            val hidden = TileOrderManager.loadHidden(this@StorageBrowserActivity)
            storageItems.removeAll { it.id in hidden }
            val orderedItems = applyTileOrder(storageItems)

            // In tile icon picker mode, show only storage-selector tiles
            val displayItems = orderedItems.filterForTileIconPicker()

            withContext(Dispatchers.Main) {
                knownMountPaths.clear()
                knownMountPaths.addAll(newKnownPaths)
                lastFullTileList = fullList
                updateHiddenBadge()
                // Reload colors and icons every time so changes made inside
                // custom tiles are picked up when tiles return to main menu.
                storageAdapter.setTileColors(TileColorManager.loadTileColors(this@StorageBrowserActivity))
                storageAdapter.setTileIcons(TileIconManager.getAllTileIcons(this@StorageBrowserActivity))
                storageAdapter.setTileIconRes(TileIconManager.getAllTileIconRes(this@StorageBrowserActivity))
                storageAdapter.submitList(displayItems, this@StorageBrowserActivity)
                updateEmptyState(storageItems.isEmpty())
            }
        }
    }


    /**
     * Scans common mount directories for USB/removable storage that
     * StorageManager doesn't report. Common on Android TV devices.
     *
     * Each [StatFs] call is run on a separate thread with a [STAT_TIMEOUT_MS] deadline
     * so a slow or unresponsive USB controller can never stall the whole scan.
     */
    private fun scanExtraMountPaths(
        items: MutableList<StorageItem>,
        discoveredPaths: MutableSet<String>
    ) {
        val mountDirs = listOf(
            File("/storage"),
            File("/mnt/media_rw"),
            File("/mnt/usb")
        )

        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()

        for (mountDir in mountDirs) {
            if (!mountDir.exists() || !mountDir.isDirectory) continue
            val children = mountDir.listFiles() ?: continue

            for (child in children) {
                // Skip emulated (internal) and self
                if (child.name == "emulated" || child.name == "self") continue
                if (!child.isDirectory) continue

                val path = child.absolutePath

                // On Amazon FireOS, /mnt/media_rw/ exists but SELinux blocks child.canRead().
                // Detect this case so we can offer Shizuku guidance later.
                if (!child.canRead()) {
                    if (path.contains("media_rw") && za.kilowatch.ultimatefilemanager.util.DeviceUtils.isAmazonDevice(this)) {
                        Log.w(TAG, "SELinux blocked read access to $path on FireOS â€” USB not accessible without Shizuku.")
                        usbSelinuxBlocked = true
                    }
                    continue
                }

                if (path in discoveredPaths) continue

                Log.d(TAG, "  Fallback scan found: $path")

                // Run StatFs on a worker thread with a hard timeout so a hung USB
                // controller can't block the coroutine for seconds at a time.
                val future = executor.submit<StatFs?> {
                    try { StatFs(path) } catch (_: Exception) { null }
                }
                val stat = try {
                    future.get(STAT_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
                } catch (_: Exception) {
                    future.cancel(true)
                    Log.w(TAG, "  StatFs timed out or failed for $path â€” skipping")
                    null
                } ?: continue

                val totalBytes = stat.totalBytes
                if (totalBytes <= 0) continue // Not a real mount

                val freeBytes = stat.freeBytes
                val usedBytes = totalBytes - freeBytes

                val label = when {
                    path.contains("usb", ignoreCase = true) -> getString(R.string.storage_usb)
                    else -> getString(R.string.storage_usb) + " (${child.name})"
                }

                val item = StorageItem(
                    id = child.name,
                    label = label,
                    iconRes = R.drawable.ic_storage_usb,
                    totalBytes = totalBytes,
                    usedBytes = usedBytes,
                    mountPath = path,
                    isRemovable = true,
                    isNewlyMounted = false
                )

                items.add(item)
                discoveredPaths.add(path)
                knownMountPaths.add(path)
            }
        }

        executor.shutdown()
    }

    /**
     * Converts a [StorageVolume] to a [StorageItem], calculating size info.
     */
    private fun volumeToStorageItem(volume: StorageVolume): StorageItem? {
        val path = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            volume.safeDirectoryPath
        } else {
            try {
                val method = volume.javaClass.getMethod("getPath")
                method.invoke(volume) as? String
            } catch (e: Exception) {
                null
            }
        } ?: return null

        val statFs = try {
            StatFs(path)
        } catch (e: SecurityException) {
            if (path.contains("media_rw")) {
                Log.w(TAG, "SELinux blocked StatFs for $path â€” USB inaccessible on this platform.")
                if (za.kilowatch.ultimatefilemanager.util.DeviceUtils.isAmazonDevice(this)) {
                    usbSelinuxBlocked = true
                }
            }
            return null
        } catch (e: Exception) {
            return null
        }

        val totalBytes = statFs.totalBytes
        val freeBytes = statFs.freeBytes
        val usedBytes = totalBytes - freeBytes

        val description = volume.getDescription(this) ?: ""

        // On some TV/set-top firmware (e.g. NVIDIA Shield), a large USB dongle can be
        // reported as isPrimary=true and isRemovable=false. Detect this via a path heuristic:
        //   â€¢ Mount path contains a known USB directory segment, AND
        //   â€¢ A UUID is present â€” real internal eMMC is always UUID-less on Android.
        // This keeps normal phones/tablets unaffected (their eMMC is at /storage/emulated/0
        // and has no UUID).
        val looksLikeUsb = path.contains("/mnt/media_rw/", ignoreCase = true)
                        || path.contains("/mnt/usb",        ignoreCase = true)
                        || path.contains("/storage/usb",    ignoreCase = true)
        val hasUuid = volume.uuid != null
        val treatAsRemovable = volume.isRemovable || (looksLikeUsb && hasUuid)

        val label = when {
            treatAsRemovable && description.lowercase().contains("usb") -> getString(R.string.storage_usb)
            treatAsRemovable -> getString(R.string.storage_sd_card)
            volume.isPrimary -> getString(R.string.storage_internal)
            else             -> description.ifEmpty { getString(R.string.storage_unknown) }
        }

        val iconRes = StorageItem.iconForType(treatAsRemovable, description)
        val id = volume.uuid ?: "internal"

        return StorageItem(
            id = id,
            label = label,
            iconRes = iconRes,
            totalBytes = totalBytes,
            usedBytes = usedBytes,
            mountPath = path,
            isRemovable = treatAsRemovable,
            isNewlyMounted = false
        )
    }

    /**
     * Registers the [StorageEventReceiver] for dynamic storage events.
     */
    private fun registerStorageReceiver() {
        storageReceiver.onStorageChanged = {
            // Debounce: delay 500ms to let mount settle before reading
            handler.removeCallbacksAndMessages(null)
            handler.postDelayed({
                runOnUiThread { loadStorageVolumes() }
            }, 500)
        }

        val filter = IntentFilter().apply {
            StorageEventReceiver.STORAGE_ACTIONS.forEach { action ->
                addAction(action)
            }
            addDataScheme("file")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(storageReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(storageReceiver, filter)
        }
    }

    /**
     * Registers a StorageVolume callback (API 30+) for reliable mount/unmount events.
     * This is the most reliable method on modern Android, especially TV.
     */
    private fun registerStorageVolumeCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val storageManager = getSystemService(Context.STORAGE_SERVICE) as StorageManager
            val callback = object : StorageManager.StorageVolumeCallback() {
                override fun onStateChanged(volume: StorageVolume) {
                    handler.removeCallbacksAndMessages(null)
                    handler.postDelayed({
                        runOnUiThread { loadStorageVolumes() }
                    }, 500)
                }
            }
            storageManager.registerStorageVolumeCallback(mainExecutor, callback)
            storageVolumeCallback = callback
        }
    }

    private fun unregisterStorageVolumeCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && storageVolumeCallback != null) {
            val storageManager = getSystemService(Context.STORAGE_SERVICE) as StorageManager
            storageManager.unregisterStorageVolumeCallback(
                storageVolumeCallback as StorageManager.StorageVolumeCallback
            )
            storageVolumeCallback = null
        }
    }

    private fun updateEmptyState(isEmpty: Boolean) {
        if (isEmpty) {
            layoutEmptyStorage.visibility = View.VISIBLE
            recyclerStorage.visibility = View.GONE
            lottieEmptyStorage?.playAnimation()
        } else {
            layoutEmptyStorage.visibility = View.GONE
            recyclerStorage.visibility = View.VISIBLE
            lottieEmptyStorage?.cancelAnimation()
        }
    }

    /**
     * Returns true if the device has an active internet connection.
     * Used to conditionally show the Tip Jar tile (Google Play Billing requires internet).
     */
    private fun isInternetAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        val available = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        if (available) {
            LoyaltyPrefs.setHasEverBeenOnline(this, true)
        }
        return available
    }

    // ─── Tip Jar Server Fetch ────────────────────────────────

    /** Shared OkHttpClient for tip jar API calls. */
    private val tipJarClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * Abbreviates a full month name at the start of a string to its 3-letter form.
     * e.g. "August 2026" → "Aug 2026", "January 2025" → "Jan 2025".
     * Leaves the string unchanged if no full month name is found.
     */
    private fun abbreviateMonth(month: String): String {
        val months = mapOf(
            "January" to "Jan", "February" to "Feb", "March" to "Mar",
            "April" to "Apr", "May" to "May", "June" to "Jun",
            "July" to "Jul", "August" to "Aug", "September" to "Sep",
            "October" to "Oct", "November" to "Nov", "December" to "Dec"
        )
        var result = month
        for ((full, abbr) in months) {
            if (result.startsWith(full)) {
                result = abbr + result.removePrefix(full)
                break
            }
        }
        return result
    }

    /**
     * Build a spannable title string with the month highlighted in the accent colour.
     */
    private fun buildTipJarTitleSpan(month: String): CharSequence {
        val shortMonth = abbreviateMonth(month).substringBefore(" ") // "August 2026" → "Aug"
        val raw = getString(R.string.tip_jar_progress_title_format, shortMonth)
        val spannable = android.text.SpannableString(raw)
        val color = androidx.core.content.ContextCompat.getColor(this, R.color.tile_tip_jar_accent)
        val start = raw.indexOf(shortMonth)
        if (start >= 0) {
            spannable.setSpan(
                android.text.style.ForegroundColorSpan(color),
                start,
                start + shortMonth.length,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return spannable
    }


    // ─── Tip Jar Server Fetch ────────────────────────────────

    /**
     * Fetches the current month's tip jar progress from the server.
     *
     * The server returns a simple JSON object:
     *   {"percent": 47, "month": "July 2025"}
     *
     * On success: updates the progress bar, percentage text, and month label,
     * and caches the values in LoyaltyPrefs.
     * On failure: uses cached values from LoyaltyPrefs. If no cache exists,
     * shows 0% with an offline subtitle.
     */
    private fun fetchTipJarProgress() {
        // Skip the network call if we're offline — go straight to cached values
        if (!isInternetAvailable()) {
            applyCachedTipJarProgress()
            applyTvTipJarCachedValues()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("https://www.kilowatch.co.za/UFM/api/progress.php")
                    .get()
                    .build()

                val response = tipJarClient.newCall(request).execute()
                val body = response.body?.string()

                if (!response.isSuccessful || body == null) {
                    // Server error — use cached values
                    withContext(Dispatchers.Main) {
                        applyCachedTipJarProgress()
                        applyTvTipJarCachedValues()
                    }
                    return@launch
                }

                val json = JSONObject(body)
                val percent = json.optInt("percent", 0).coerceIn(0, 100)
                val month = json.optString("month", "")

                if (month.isNotEmpty()) {
                    // Cache the server response
                    LoyaltyPrefs.saveCachedProgress(this@StorageBrowserActivity, percent, month)

                    // Update UI on the main thread
                    withContext(Dispatchers.Main) {
                        updateTipJarCard(percent, month)
                        updateTvTipJarCard(percent, month)
                    }
                } else {
                    // Malformed response — fall back to cache
                    withContext(Dispatchers.Main) {
                        applyCachedTipJarProgress()
                        applyTvTipJarCachedValues()
                    }
                }

            } catch (e: Exception) {
                // Network error, timeout, parse error — use cached values
                withContext(Dispatchers.Main) {
                    applyCachedTipJarProgress()
                    applyTvTipJarCachedValues()
                }
            }
        }
    }

    /**
     * Update the tip jar card with the given server values.
     * Called on the main thread after a successful fetch.
     */
    private fun updateTipJarCard(percent: Int, month: String) {
        val progressBar = findViewById<android.widget.ProgressBar>(R.id.progressTipJar) ?: return
        val percentTv = findViewById<TextView>(R.id.txtTipJarPercent) ?: return
        val titleTv = findViewById<TextView>(R.id.txtTipJarTitle)

        // Update progress bar
        progressBar.progress = percent

        // Update percentage text
        percentTv.text = getString(R.string.loyalty_value_format, percent) + "%"

        // Update title with server month
        if (titleTv != null && month.isNotEmpty()) {
            titleTv.text = buildTipJarTitleSpan(month)
        }

        // Restore the normal subtitle (in case it was changed to the offline message)
        val descView = findViewById<TextView>(R.id.tip_jar_desc_label)
        if (descView != null) {
            descView.text = getString(R.string.tip_jar_desc_label)
            descView.visibility = View.VISIBLE
        }
    }

    /**
     * Apply cached (or default) tip jar progress values.
     * Called when the server fetch fails or returns invalid data.
     */
    private fun applyCachedTipJarProgress() {
        val cachedPercent = LoyaltyPrefs.getCachedPercent(this)
        val cachedMonth = LoyaltyPrefs.getCachedMonth(this)

        val progressBar = findViewById<android.widget.ProgressBar>(R.id.progressTipJar) ?: return
        val percentTv = findViewById<TextView>(R.id.txtTipJarPercent) ?: return

        progressBar.progress = cachedPercent

        if (cachedMonth.isNotEmpty()) {
            percentTv.text = getString(R.string.loyalty_value_format, cachedPercent) + "%"
            val titleTv = findViewById<TextView>(R.id.txtTipJarTitle)
            if (titleTv != null) {
                titleTv.text = buildTipJarTitleSpan(cachedMonth)
            }

            // Restore normal subtitle text + visibility
            val descView = findViewById<TextView>(R.id.tip_jar_desc_label)
            if (descView != null) {
                descView.text = getString(R.string.tip_jar_desc_label)
                descView.visibility = View.VISIBLE
            }
        } else {
            // No cache — show 0% with offline subtitle
            percentTv.text = "0%"
            val descView = findViewById<TextView>(R.id.tip_jar_desc_label)
            if (descView != null) {
                descView.text = getString(R.string.tip_jar_offline_subtitle)
                descView.visibility = View.VISIBLE
            }
        }
    }

    // ─── TV Tip Jar Notification ─────────────────────────────────

    /**
     * Update the TV notification bar with the given server values.
     * Called on the main thread after a successful fetch.
     * Uses findViewById which safely returns null on mobile.
     */
    private fun updateTvTipJarCard(percent: Int, month: String) {
        val progressBar = findViewById<android.widget.ProgressBar>(R.id.progressTvTipJar) ?: return
        val percentTv = findViewById<TextView>(R.id.txtTvTipJarPercent) ?: return
        val titleTv = findViewById<TextView>(R.id.txtTvTipJarTitle)

        progressBar.progress = percent
        percentTv.text = getString(R.string.loyalty_value_format, percent) + "%"

        if (titleTv != null && month.isNotEmpty()) {
            titleTv.text = buildTipJarTitleSpan(month)
        }
    }

    /**
     * Apply cached (or default) tip jar progress values to the TV notification bar.
     * Called when the server fetch fails or returns invalid data.
     */
    private fun applyTvTipJarCachedValues() {
        val cachedPercent = LoyaltyPrefs.getCachedPercent(this)
        val cachedMonth = LoyaltyPrefs.getCachedMonth(this)

        val progressBar = findViewById<android.widget.ProgressBar>(R.id.progressTvTipJar) ?: return
        val percentTv = findViewById<TextView>(R.id.txtTvTipJarPercent) ?: return

        progressBar.progress = cachedPercent

        if (cachedMonth.isNotEmpty()) {
            percentTv.text = getString(R.string.loyalty_value_format, cachedPercent) + "%"
            val titleTv = findViewById<TextView>(R.id.txtTvTipJarTitle)
            if (titleTv != null) {
                titleTv.text = buildTipJarTitleSpan(cachedMonth)
            }
        } else {
            percentTv.text = "0%"
        }
    }

    private fun applyViewMode(animate: Boolean = false) {
        val updateLayout = {
            val mode = MainMenuViewModeManager.loadViewMode(this)
            val cols = MainMenuViewModeManager.loadColumnCount(this)
            val size = MainMenuViewModeManager.loadItemSize(this)

            if (::storageAdapter.isInitialized) {
                storageAdapter.viewMode = mode
                storageAdapter.itemSize = size
                storageAdapter.gridColumnCount = cols
            }

            // Remove any previously attached TV grid listeners before switching mode
            tvSnapHelper?.attachToRecyclerView(null)
            tvSnapHelper = null

            if (mode == MainMenuViewModeManager.ViewMode.LIST) {
                recyclerStorage.layoutManager = LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false)
                if (::storageAdapter.isInitialized) storageAdapter.gridItemHeightPx = -1
            } else if (mode == MainMenuViewModeManager.ViewMode.MODERN_CATEGORIZED) {
                val gridLayoutManager = GridLayoutManager(this, 1)
                recyclerStorage.layoutManager = gridLayoutManager
                if (::storageAdapter.isInitialized) storageAdapter.gridItemHeightPx = -1
            } else {
                val gridLayoutManager = GridLayoutManager(this, cols)
                gridLayoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int): Int {
                        val item = storageAdapter.getItems().getOrNull(position)
                        return if (item?.isCategoryHeader == true) cols else 1
                    }
                }
                recyclerStorage.layoutManager = gridLayoutManager

                if (isTv) {
                    recyclerStorage.doOnLayout {
                        val density = resources.displayMetrics.density
                        val rvH = recyclerStorage.height
                        val pTop = recyclerStorage.paddingTop
                        val pBot = recyclerStorage.paddingBottom
                        val marginPx = (16f * density).toInt() * 4

                        val availableHeight = rvH - pTop - pBot - marginPx
                        val itemInnerHeightPx = availableHeight / 2
                        
                        if (::storageAdapter.isInitialized && itemInnerHeightPx > 0) {
                            storageAdapter.gridItemHeightPx = itemInnerHeightPx
                        }
                    }

                    if (mode == MainMenuViewModeManager.ViewMode.GRID) {
                        val snapHelper = TopSnapHelper()
                        snapHelper.attachToRecyclerView(recyclerStorage)
                        tvSnapHelper = snapHelper
                    }
                }
            }

            updateToggleVisuals()
            if (::storageAdapter.isInitialized) {
                storageAdapter.notifyDataSetChanged()
            }
        }

        if (animate && ::recyclerStorage.isInitialized) {
            za.kilowatch.ultimatefilemanager.util.AnimationHelper.animateViewModeSwitch(recyclerStorage) {
                updateLayout()
            }
        } else {
            updateLayout()
        }
    }

    private fun updateToggleVisuals() {
        val currentMode = MainMenuViewModeManager.loadViewMode(this)
        val isGrid = currentMode == MainMenuViewModeManager.ViewMode.GRID
        val activeColor = if (isTv) getColor(R.color.tv_accent) else DefaultIconColorManager.getMobileIconTint(this)

        fun createActiveDrawable(): android.graphics.drawable.Drawable {
            val density = resources.displayMetrics.density
            return android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 9 * density
                setColor(activeColor)
            }
        }

        btnToggleGrid?.let { gridBtn ->
            if (isGrid) {
                gridBtn.background = createActiveDrawable()
                gridBtn.imageTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.white))
            } else {
                gridBtn.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                val inactiveTint = if (isTv) getColor(R.color.tv_text_secondary) else za.kilowatch.ultimatefilemanager.util.ThemeColors.onSurfaceVariant(this@StorageBrowserActivity)
                gridBtn.imageTintList = android.content.res.ColorStateList.valueOf(inactiveTint)
            }
        }

        btnToggleList?.let { listBtn ->
            if (!isGrid) {
                listBtn.background = createActiveDrawable()
                listBtn.imageTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.white))
            } else {
                listBtn.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                val inactiveTint = if (isTv) getColor(R.color.tv_text_secondary) else za.kilowatch.ultimatefilemanager.util.ThemeColors.onSurfaceVariant(this@StorageBrowserActivity)
                listBtn.imageTintList = android.content.res.ColorStateList.valueOf(inactiveTint)
            }
        }
    }

    private fun setupTvToggleFocus(btn: ImageView) {
        btn.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                btn.setBackgroundResource(R.drawable.selector_tv_icon_btn)
                btn.imageTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_button_focused_yellow_text))
            } else {
                updateToggleVisuals()
            }
        }
    }

    private fun showViewModeOptions(isListView: Boolean) {
        if (isTv) {
            showTvViewModeOptions(isListView)
        } else {
            val sheet = ViewModeBottomSheet.newInstance(isListView)
            sheet.onSettingsChanged = {
                loadStorageVolumes()
                applyViewMode()
            }
            sheet.show(supportFragmentManager, ViewModeBottomSheet.TAG)
        }
    }

    private fun showTvViewModeOptions(isListView: Boolean) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_view_mode_options_tv, null)
        val imgDialogIcon = dialogView.findViewById<ImageView>(R.id.imgDialogIcon)
        val txtDialogTitle = dialogView.findViewById<TextView>(R.id.txtDialogTitle)
        val layoutColumns = dialogView.findViewById<View>(R.id.layoutColumns)
        val layoutListSize = dialogView.findViewById<View>(R.id.layoutListSize)
        val btnTvClose = dialogView.findViewById<View>(R.id.btnTvClose)

        val dialog = android.app.AlertDialog.Builder(this, android.R.style.Theme_Translucent_NoTitleBar)
            .setView(dialogView)
            .create()

        btnTvClose.setOnClickListener { dialog.dismiss() }

        if (isListView) {
            imgDialogIcon.setImageResource(R.drawable.ic_list_view_custom)
            txtDialogTitle.text = getString(R.string.layout_list)
            layoutListSize.visibility = View.VISIBLE
            layoutColumns.visibility = View.GONE

            val cardLarge = dialogView.findViewById<MaterialCardView>(R.id.cardSizeLarge)
            val cardMedium = dialogView.findViewById<MaterialCardView>(R.id.cardSizeMedium)
            val cardSmall = dialogView.findViewById<MaterialCardView>(R.id.cardSizeSmall)

            val rbLarge = dialogView.findViewById<RadioButton>(R.id.rbSizeLarge)
            val rbMedium = dialogView.findViewById<RadioButton>(R.id.rbSizeMedium)
            val rbSmall = dialogView.findViewById<RadioButton>(R.id.rbSizeSmall)

            val currentSize = MainMenuViewModeManager.loadItemSize(this)
            rbLarge.isChecked = currentSize == MainMenuViewModeManager.ItemSize.LARGE
            rbMedium.isChecked = currentSize == MainMenuViewModeManager.ItemSize.MEDIUM
            rbSmall.isChecked = currentSize == MainMenuViewModeManager.ItemSize.SMALL

            setupTvCardFocusForDialog(cardLarge)
            setupTvCardFocusForDialog(cardMedium)
            setupTvCardFocusForDialog(cardSmall)

            val activeColor = getColor(R.color.tv_accent)
            val inactiveColor = getColor(R.color.tv_glass_border)

            cardLarge.strokeColor = if (currentSize == MainMenuViewModeManager.ItemSize.LARGE) activeColor else inactiveColor
            cardMedium.strokeColor = if (currentSize == MainMenuViewModeManager.ItemSize.MEDIUM) activeColor else inactiveColor
            cardSmall.strokeColor = if (currentSize == MainMenuViewModeManager.ItemSize.SMALL) activeColor else inactiveColor

            cardLarge.setOnClickListener {
                MainMenuViewModeManager.saveItemSize(this, MainMenuViewModeManager.ItemSize.LARGE)
                applyViewMode(animate = true)
                dialog.dismiss()
            }
            cardMedium.setOnClickListener {
                MainMenuViewModeManager.saveItemSize(this, MainMenuViewModeManager.ItemSize.MEDIUM)
                applyViewMode(animate = true)
                dialog.dismiss()
            }
            cardSmall.setOnClickListener {
                MainMenuViewModeManager.saveItemSize(this, MainMenuViewModeManager.ItemSize.SMALL)
                applyViewMode(animate = true)
                dialog.dismiss()
            }
        } else {
            imgDialogIcon.setImageResource(R.drawable.ic_grid_view_custom)
            txtDialogTitle.text = getString(R.string.layout_grid)
            layoutColumns.visibility = View.VISIBLE
            layoutListSize.visibility = View.GONE

            val cardColumns4 = dialogView.findViewById<MaterialCardView>(R.id.cardColumns4)
            val cardColumns3 = dialogView.findViewById<MaterialCardView>(R.id.cardColumns3)

            val rbColumns4 = dialogView.findViewById<RadioButton>(R.id.rbColumns4)
            val rbColumns3 = dialogView.findViewById<RadioButton>(R.id.rbColumns3)

            val currentColCount = MainMenuViewModeManager.loadColumnCount(this)
            rbColumns4.isChecked = currentColCount == 4
            rbColumns3.isChecked = currentColCount == 3

            setupTvCardFocusForDialog(cardColumns4)
            setupTvCardFocusForDialog(cardColumns3)

            val activeColor = getColor(R.color.tv_accent)
            val inactiveColor = getColor(R.color.tv_glass_border)

            cardColumns4.strokeColor = if (currentColCount == 4) activeColor else inactiveColor
            cardColumns3.strokeColor = if (currentColCount == 3) activeColor else inactiveColor

            cardColumns4.setOnClickListener {
                MainMenuViewModeManager.saveColumnCount(this, 4)
                applyViewMode(animate = true)
                dialog.dismiss()
            }
            cardColumns3.setOnClickListener {
                MainMenuViewModeManager.saveColumnCount(this, 3)
                applyViewMode(animate = true)
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    private fun setupTvCardFocusForDialog(card: MaterialCardView) {
        val yellowFill = getColor(R.color.tv_button_focused_yellow)
        val blackText = getColor(R.color.tv_button_focused_yellow_text)
        val glassColor = getColor(R.color.tv_glass_white_10)
        val primaryText = getColor(R.color.tv_text_primary)
        val secondaryText = getColor(R.color.tv_text_secondary)

        card.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                card.setCardBackgroundColor(yellowFill)
                setTvDialogCardTextColors(card, blackText, blackText)
                setTvDialogCardRadioTint(card, blackText)
            } else {
                card.setCardBackgroundColor(glassColor)
                setTvDialogCardTextColors(card, primaryText, secondaryText)
                setTvDialogCardRadioTint(card, getColor(R.color.tv_accent))
            }
        }
    }

    private fun setTvDialogCardTextColors(view: View, primaryColor: Int, secondaryColor: Int) {
        if (view is TextView) {
            val isSubtitle = view.textSize < resources.displayMetrics.density * 16
            view.setTextColor(if (isSubtitle) secondaryColor else primaryColor)
            return
        }
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount)
                setTvDialogCardTextColors(view.getChildAt(i), primaryColor, secondaryColor)
        }
    }

    private fun setTvDialogCardRadioTint(view: View, color: Int) {
        if (view is RadioButton) {
            view.buttonTintList = android.content.res.ColorStateList.valueOf(color)
            return
        }
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount)
                setTvDialogCardRadioTint(view.getChildAt(i), color)
        }
    }





    /**
     * Custom SnapHelper to snap tiles to the top boundary automatically.
     */
    class TopSnapHelper : androidx.recyclerview.widget.LinearSnapHelper() {
        private var verticalHelper: androidx.recyclerview.widget.OrientationHelper? = null

        private fun getHelper(manager: RecyclerView.LayoutManager): androidx.recyclerview.widget.OrientationHelper {
            if (verticalHelper == null) {
                verticalHelper = androidx.recyclerview.widget.OrientationHelper.createVerticalHelper(manager)
            }
            return verticalHelper!!
        }

        override fun calculateDistanceToFinalSnap(
            layoutManager: RecyclerView.LayoutManager,
            targetView: android.view.View
        ): IntArray {
            val out = IntArray(2)
            val helper = getHelper(layoutManager)
            val viewTop = helper.getDecoratedStart(targetView)
            val containerTop = helper.startAfterPadding
            out[1] = viewTop - containerTop
            return out
        }

        override fun findSnapView(layoutManager: RecyclerView.LayoutManager): android.view.View? {
            val helper = getHelper(layoutManager)
            val childCount = layoutManager.childCount
            if (childCount == 0) return null

            var closestChild: android.view.View? = null
            var closestDistance = Int.MAX_VALUE

            val containerTop = helper.startAfterPadding

            for (i in 0 until childCount) {
                val child = layoutManager.getChildAt(i) ?: continue
                val viewTop = helper.getDecoratedStart(child)
                val distance = Math.abs(viewTop - containerTop)
                if (distance < closestDistance) {
                    closestDistance = distance
                    closestChild = child
                }
            }
            return closestChild
        }
    }
}
