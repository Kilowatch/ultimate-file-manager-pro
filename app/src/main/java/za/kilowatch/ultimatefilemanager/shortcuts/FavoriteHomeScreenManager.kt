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
import za.kilowatch.ultimatefilemanager.settings.FavoritesManager
import za.kilowatch.ultimatefilemanager.util.DeviceUtils

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
     * Requests the launcher to pin a shortcut for the favorite item.
     * Uses optimistic tracking; reconciled on next resume.
     */
    fun pinShortcut(context: Context, item: FavoritesManager.FavoriteItem) {
        if (!isMobilePinSupported(context)) {
            Log.w(TAG, "Pin shortcuts not supported on this launcher")
            return
        }

        try {
            val deepLinkIntent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("ufm://open?favId=${Uri.encode(item.id)}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }

            val icon = FavoriteIconComposer.createShortcutIcon(context, item)
            val shortcut = ShortcutInfoCompat.Builder(context, item.id)
                .setShortLabel(item.label.take(12))
                .setLongLabel(item.label)
                .setIcon(icon)
                .setIntent(deepLinkIntent)
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
            addProgram(context, item, activity)
        } else {
            pinShortcut(context, item)
        }
    }

    /**
     * Called by [FavoritesManager.removeFavorite] to clean up home-screen presence.
     */
    fun onFavoriteRemoved(context: Context, item: FavoritesManager.FavoriteItem) {
        if (item.isPinnedMobile) {
            disableShortcut(context, item)
        }
        if (item.isPinnedTv) {
            removeProgram(context, item)
        }
    }
}
