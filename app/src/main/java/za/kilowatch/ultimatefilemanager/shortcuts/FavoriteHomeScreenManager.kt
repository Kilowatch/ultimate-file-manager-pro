package za.kilowatch.ultimatefilemanager.shortcuts

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.indexing.IndexingRepository
import za.kilowatch.ultimatefilemanager.network.NetworkBrowserActivity
import za.kilowatch.ultimatefilemanager.settings.FavoritesManager
import za.kilowatch.ultimatefilemanager.storage.FileBrowserActivity
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import java.io.File

/**
 * Central orchestrator for favorites home-screen presence across Mobile (pinned shortcuts)
 * and Android TV (preview channels and programs).
 */
object FavoriteHomeScreenManager {

    private const val TAG = "FavoriteHomeManager"

    private val tvDelegate: TvHomeDelegate = TvPreviewManager()

    fun ensureTvChannel(context: Context) {
        if (DeviceUtils.isTvDevice(context)) {
            (tvDelegate as? TvPreviewManager)?.getOrCreateChannelId(context)
        }
    }
    // ── Mobile Shortcut Operations ──────────────────────────────────────────

    /**
     * Checks if the default launcher supports pinning shortcuts.
     */
    fun isMobilePinSupported(context: Context): Boolean {
        return ShortcutManagerCompat.isRequestPinShortcutSupported(context)
    }

    /**
     * Builds the direct or trampoline intent used by launcher shortcuts and TV preview programs.
     * Folders and storage volumes launch directly into FileBrowserActivity or NetworkBrowserActivity,
     * while individual files route through FavoriteShortcutActivity to evaluate default open actions.
     */
    fun createLaunchIntent(context: Context, item: FavoritesManager.FavoriteItem): Intent {
        return if (item.isNetwork) {
            val shareId = item.shareId
            if (item.isFolder && !shareId.isNullOrEmpty()) {
                val isTvPaired = shareId.startsWith("tv_")
                val isOnline = !isTvPaired && za.kilowatch.ultimatefilemanager.network.OnlineStorageRepository.getInstance(context).getById(shareId) != null
                Intent(context, NetworkBrowserActivity::class.java).apply {
                    action = Intent.ACTION_VIEW
                    if (isTvPaired) {
                        putExtra(NetworkBrowserActivity.EXTRA_PAIRED_DEVICE_ID, shareId.removePrefix("tv_"))
                    } else {
                        putExtra(NetworkBrowserActivity.EXTRA_SHARE_ID, shareId)
                    }
                    if (isOnline) {
                        putExtra("isOnlineStorage", true)
                    }
                    putExtra(NetworkBrowserActivity.EXTRA_INITIAL_PATH, item.path)
                    putExtra(NetworkBrowserActivity.EXTRA_STORAGE_LABEL, item.label)
                    putExtra("favId", item.id)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            } else {
                createTrampolineIntent(context, item)
            }
        } else {
            if (item.path.startsWith("content://") || item.path.startsWith("saf://")) {
                Intent(context, FileBrowserActivity::class.java).apply {
                    action = Intent.ACTION_VIEW
                    putExtra(FileBrowserActivity.EXTRA_MOUNT_PATH, item.path)
                    putExtra(FileBrowserActivity.EXTRA_INITIAL_PATH, item.path)
                    putExtra(FileBrowserActivity.EXTRA_STORAGE_TYPE, "saf_custom")
                    putExtra(FileBrowserActivity.EXTRA_STORAGE_LABEL, item.label)
                    putExtra("favId", item.id)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            } else {
                val targetFile = File(item.path)
                if (item.isFolder || item.isStorageTileFavorite || targetFile.isDirectory) {
                    val (resolvedSid, resolvedStype, resolvedRoot) = IndexingRepository.resolveStorageForPath(targetFile.absolutePath)
                    Intent(context, FileBrowserActivity::class.java).apply {
                        action = Intent.ACTION_VIEW
                        putExtra(FileBrowserActivity.EXTRA_MOUNT_PATH, resolvedRoot)
                        putExtra(FileBrowserActivity.EXTRA_INITIAL_PATH, targetFile.absolutePath)
                        putExtra(FileBrowserActivity.EXTRA_STORAGE_ID, resolvedSid)
                        putExtra(FileBrowserActivity.EXTRA_STORAGE_TYPE, resolvedStype)
                        putExtra(FileBrowserActivity.EXTRA_STORAGE_LABEL, item.label)
                        putExtra("favId", item.id)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                } else {
                    createTrampolineIntent(context, item)
                }
            }
        }
    }

    /**
     * Fallback intent routing through FavoriteShortcutActivity with full metadata.
     */
    fun createTrampolineIntent(context: Context, item: FavoritesManager.FavoriteItem): Intent {
        return Intent(context, FavoriteShortcutActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("ufm://open?favId=${Uri.encode(item.id)}&path=${Uri.encode(item.path)}&label=${Uri.encode(item.label)}&isFolder=${item.isFolder}&isNetwork=${item.isNetwork}&shareId=${Uri.encode(item.shareId ?: "")}")
            putExtra("favId", item.id)
            putExtra("path", item.path)
            putExtra("label", item.label)
            putExtra("isFolder", item.isFolder)
            putExtra("isNetwork", item.isNetwork)
            putExtra("shareId", item.shareId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
    }

    /**
     * Requests the launcher to pin a shortcut for the favorite item.
     * Uses optimistic tracking; reconciled on next resume.
     */
    fun pinShortcut(context: Context, item: FavoritesManager.FavoriteItem) {
        if (!isMobilePinSupported(context)) {
            Log.w(TAG, "Pin shortcuts not supported on this launcher")
            return
        }

        try {
            val launchIntent = createLaunchIntent(context, item)
            val icon = FavoriteIconComposer.createShortcutIcon(context, item)
            val shortcut = ShortcutInfoCompat.Builder(context, item.id)
                .setShortLabel(item.label.take(12))
                .setLongLabel(item.label)
                .setIcon(icon)
                .setIntent(launchIntent)
                .build()

            val pinned = ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
            if (pinned) {
                val updated = item.copy(isPinnedMobile = true)
                FavoritesManager.updateItem(context, updated)
                Toast.makeText(context, R.string.fav_shortcut_requested_toast, Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request pin shortcut for ${item.label}", e)
        }
    }

    /**
     * Disables the pinned launcher shortcut when removed from home screen.
     */
    fun disableShortcut(context: Context, item: FavoritesManager.FavoriteItem) {
        try {
            ShortcutManagerCompat.disableShortcuts(
                context,
                listOf(item.id),
                context.getString(R.string.fav_shortcut_disabled_toast)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to disable shortcut ${item.id}: ${e.message}")
        } finally {
            val updated = item.copy(isPinnedMobile = false)
            FavoritesManager.updateItem(context, updated)
            Toast.makeText(context, R.string.fav_shortcut_disabled_toast, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Reconciles `isPinnedMobile` flags against the launcher's pinned shortcut registry.
     */
    fun reconcileMobile(
        context: Context,
        favorites: List<FavoritesManager.FavoriteItem>
    ): List<FavoritesManager.FavoriteItem> {
        return try {
            val shortcuts = ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED)
            val pinnedIds = shortcuts.map { it.id }.toSet()
            var changed = false

            val reconciled = favorites.map { item ->
                val actuallyPinned = pinnedIds.contains(item.id)
                if (item.isPinnedMobile != actuallyPinned) {
                    changed = true
                    item.copy(isPinnedMobile = actuallyPinned)
                } else {
                    item
                }
            }

            if (changed) {
                reconciled.forEach { FavoritesManager.updateItem(context, it) }
            }
            reconciled
        } catch (e: Exception) {
            Log.w(TAG, "Failed to reconcile mobile shortcuts: ${e.message}")
            favorites
        }
    }

    // ── TV Preview Channel Operations ───────────────────────────────────────

    fun addProgram(context: Context, item: FavoritesManager.FavoriteItem, activity: Activity? = null) {
        tvDelegate?.addProgram(context, item, activity)
    }

    fun removeProgram(context: Context, item: FavoritesManager.FavoriteItem) {
        tvDelegate?.removeProgram(context, item)
    }

    fun reconcileTv(
        context: Context,
        favorites: List<FavoritesManager.FavoriteItem>
    ): List<FavoritesManager.FavoriteItem> {
        return tvDelegate?.reconcileTv(context, favorites) ?: favorites
    }

    // ── Combined Operations & Save Handlers ──────────────────────────────────

    /**
     * Pins shortcut or publishes preview card depending on active device platform.
     * Called when the "Add to home screen" toggle is checked in the Save Favorite dialog.
     */
    fun pinOrSchedule(context: Context, item: FavoritesManager.FavoriteItem, activity: Activity? = null) {
        if (DeviceUtils.isTvDevice(context)) {
            pinOrScheduleTv(context, item, activity)
        } else {
            pinShortcut(context, item)
        }
    }

    /**
     * TV-specific routing that ensures universal compatibility across all TV launchers:
     *
     * 1. ALWAYS publishes to TV Preview Channels via [addProgram].
     *    This is the canonical Android TV / Google TV mechanism and works out of the box
     *    on Google TV, Android TV stock launchers, and custom launchers like Projectivy.
     *
     * 2. In addition, if the active TV launcher supports pinned shortcuts (such as
     *    ATV Launcher Pro, Monet, or other home screens supporting [ShortcutManagerCompat]),
     *    it ALSO requests the pinned shortcut directly onto the launcher's home screen.
     */
    private fun pinOrScheduleTv(context: Context, item: FavoritesManager.FavoriteItem, activity: Activity? = null) {
        // Always publish to TV Preview Channels (Google TV / Android TV / Projectivy)
        addProgram(context, item, activity)

        // Also request pinned shortcut if supported by the active TV launcher (ATV Launcher Pro / custom launchers)
        if (isMobilePinSupported(context)) {
            Log.d(TAG, "Pinned shortcuts supported on TV launcher — also pinning shortcut for ${item.label}")
            pinShortcutOnTv(context, item)
        }
    }

    /**
     * Pins a shortcut for TV custom launchers using the same [ShortcutManagerCompat] path
     * as mobile, but marks the item as [FavoritesManager.FavoriteItem.isPinnedTv] so the
     * manage-favorites screen shows the correct state on TV.
     * [FavoritePosterRenderer] supplies a 16:9 bitmap used by [FavoriteIconComposer] so the
     * tile looks appropriate on TV grid/banner home screens.
     */
    private fun pinShortcutOnTv(context: Context, item: FavoritesManager.FavoriteItem) {
        try {
            val launchIntent = createLaunchIntent(context, item)
            val icon = FavoriteIconComposer.createShortcutIcon(context, item)
            val shortcut = androidx.core.content.pm.ShortcutInfoCompat.Builder(context, item.id)
                .setShortLabel(item.label.take(12))
                .setLongLabel(item.label)
                .setIcon(icon)
                .setIntent(launchIntent)
                .build()

            val pinned = ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
            if (pinned) {
                // Ensure isPinnedTv=true while preserving tvProgramUri if already published
                val current = FavoritesManager.getFavorites(context).firstOrNull { it.id == item.id } ?: item
                val updated = current.copy(isPinnedTv = true)
                FavoritesManager.updateItem(context, updated)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pin TV shortcut for ${item.label}", e)
        }
    }

    /**
     * Called by [FavoritesManager.removeFavorite] to clean up home-screen presence.
     * Handles all three pin states:
     * - Mobile pinned shortcut → disable via ShortcutManagerCompat
     * - TV Preview Channel program → remove via TvPreviewManager
     * - TV custom-launcher pinned shortcut (isPinnedTv=true, tvProgramUri=null) → disable via ShortcutManagerCompat
     */
    fun onFavoriteRemoved(context: Context, item: FavoritesManager.FavoriteItem) {
        if (item.isPinnedMobile) {
            disableShortcut(context, item)
        }
        if (item.isPinnedTv) {
            if (item.tvProgramUri != null) {
                removeProgram(context, item)
            }
            disableShortcut(context, item)
        }
    }
}
