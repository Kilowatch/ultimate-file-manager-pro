package za.kilowatch.ultimatefilemanager.shortcuts

import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.annotation.Keep
import androidx.core.content.ContextCompat
import androidx.tvprovider.media.tv.ChannelLogoUtils
import androidx.tvprovider.media.tv.PreviewChannel
import androidx.tvprovider.media.tv.PreviewChannelHelper
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.settings.FavoritesManager

/**
 * Manages Android TV Preview Channels & Programs for UFM Favorites.
 * Interacts with Android TV Provider (androidx.tvprovider) to publish channels,
 * prompt user approval (ACTION_REQUEST_CHANNEL_BROWSABLE), and add/remove program cards.
 */
@Keep
class TvPreviewManager : TvHomeDelegate {

    companion object {
        private const val TAG = "TvPreviewManager"
        private const val PREFS_NAME = "ufm_favorites_prefs"
        private const val KEY_CHANNEL_ID = "tv_favorites_channel_id"
        private const val INTERNAL_CHANNEL_ID = "ufm_favorites_channel"
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Resolves an existing preview channel or creates and publishes a new one.
     * Generates a 160x160 logo bitmap and attaches it via [PreviewChannel.Builder.setLogo].
     * PreviewChannelHelper requires the logo to be attached on the builder, otherwise it rolls
     * back channel insertion and returns -1.
     */
    fun getOrCreateChannelId(context: Context): Long? {
        val prefs = getPrefs(context)
        val existingId = prefs.getLong(KEY_CHANNEL_ID, -1L)
        val helper = PreviewChannelHelper(context)

        if (existingId > 0L) {
            try {
                val existingChannel = helper.getPreviewChannel(existingId)
                if (existingChannel != null && existingChannel.type == TvContractCompat.Channels.TYPE_PREVIEW) {
                    Log.d(TAG, "Found existing preview channel: id=$existingId isBrowsable=${existingChannel.isBrowsable}")
                    return existingId
                } else if (existingChannel != null) {
                    Log.w(TAG, "Existing channel id=$existingId had wrong type (${existingChannel.type}), recreating")
                    try { helper.deletePreviewChannel(existingId) } catch (_: Exception) {}
                } else {
                    Log.w(TAG, "Stored channel id=$existingId no longer exists, clearing")
                    prefs.edit().remove(KEY_CHANNEL_ID).apply()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to query existing channel: ${e.message}")
            }
        }

        // Also check if channel already exists by internalProviderId
        try {
            val allChannels = helper.allChannels
            val existing = allChannels.firstOrNull {
                it.internalProviderId == INTERNAL_CHANNEL_ID && it.type == TvContractCompat.Channels.TYPE_PREVIEW
            }
            if (existing != null && existing.id > 0L) {
                prefs.edit().putLong(KEY_CHANNEL_ID, existing.id).apply()
                Log.d(TAG, "Found channel via allChannels scan: id=${existing.id} isBrowsable=${existing.isBrowsable}")
                return existing.id
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check allChannels: ${e.message}")
        }

        // Build logo bitmap first — REQUIRED by PreviewChannelHelper.publishChannel / publishDefaultChannel
        val logoSize = 160
        val logoBitmap = Bitmap.createBitmap(logoSize, logoSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(logoBitmap)
        val d = ContextCompat.getDrawable(context, R.drawable.ic_star)?.mutate()
        d?.setTint(ContextCompat.getColor(context, R.color.tv_accent))
        d?.setBounds(0, 0, logoSize, logoSize)
        d?.draw(canvas)

        return try {
            val channel = PreviewChannel.Builder()
                .setDisplayName(context.getString(R.string.fav_tv_channel_name))
                .setDescription(context.getString(R.string.fav_tv_channel_name))
                .setAppLinkIntentUri(Uri.parse("ufm://open?type=manageFavorites"))
                .setInternalProviderId(INTERNAL_CHANNEL_ID)
                .setLogo(logoBitmap)
                .build()

            val channelId = helper.publishDefaultChannel(channel)
            if (channelId <= 0L) {
                Log.e(TAG, "publishDefaultChannel returned invalid channelId: $channelId")
                null
            } else {
                prefs.edit().putLong(KEY_CHANNEL_ID, channelId).apply()
                Log.d(TAG, "Successfully published default TV preview channel: id=$channelId")
                channelId
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create preview channel", e)
            null
        }
    }

    override fun addProgram(context: Context, item: FavoritesManager.FavoriteItem, activity: Activity?) {
        // Run channel creation + program publish on a background thread to avoid StrictMode violations.
        // The browsable prompt (startActivity) is dispatched back on the main thread with the Activity.
        Thread {
            val channelId = getOrCreateChannelId(context)
            if (channelId == null) {
                Log.e(TAG, "addProgram: could not get/create channel — aborting")
                return@Thread
            }
            Log.d(TAG, "addProgram: channelId=$channelId activity=${activity?.javaClass?.simpleName}")

            val helper = PreviewChannelHelper(context)

            // Check browsable state BEFORE publishing the program
            val isBrowsable = try {
                val channel = helper.getPreviewChannel(channelId)
                (channel?.isBrowsable == true).also {
                    Log.d(TAG, "Channel isBrowsable=$it")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to check isBrowsable: ${e.message}")
                false
            }

            // Build and publish the preview program
            try {
                val posterUri = FavoritePosterRenderer.getPosterUri(context, item)
                val launchIntent = FavoriteHomeScreenManager.createLaunchIntent(context, item)
                val programIntentUri = Uri.parse(launchIntent.toUri(Intent.URI_INTENT_SCHEME))

                val builder = PreviewProgram.Builder()
                    .setChannelId(channelId)
                    .setType(TvContractCompat.PreviewPrograms.TYPE_CLIP)
                    .setTitle(item.label)
                    .setDescription(item.path)
                    .setIntentUri(programIntentUri)
                    .setInternalProviderId(item.id)

                if (posterUri != null) {
                    builder.setPosterArtUri(posterUri)
                        .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)

                    // Grant read permission to TV launchers so home cards render cleanly
                    try {
                        val launcherPackages = listOf(
                            "com.google.android.tvlauncher",
                            "com.android.tv",
                            "com.google.android.apps.tv.launcherx",
                            "com.android.providers.tv"
                        )
                        for (pkg in launcherPackages) {
                            context.grantUriPermission(pkg, posterUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to grant URI permission: ${e.message}")
                    }
                }

                val program = builder.build()
                val programId = helper.publishPreviewProgram(program)
                val uri = TvContractCompat.buildPreviewProgramUri(programId)
                Log.d(TAG, "Published TV preview program: $uri for ${item.label}")

                val updated = item.copy(isPinnedTv = true, tvProgramUri = uri.toString())
                FavoritesManager.updateItem(context, updated)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to publish preview program for ${item.label}", e)
                return@Thread
            }

            // Show the user-facing channel approval dialog from the foreground Activity.
            // This MUST be called after the channel exists in the TV Provider DB.
            // If the channel is already browsable we still show it once so the user knows
            // the channel was added (the system handles the already-approved case gracefully).
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try {
                    if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
                        val promptIntent = Intent(TvContractCompat.ACTION_REQUEST_CHANNEL_BROWSABLE).apply {
                            putExtra(TvContractCompat.EXTRA_CHANNEL_ID, channelId)
                        }
                        Log.d(TAG, "Firing ACTION_REQUEST_CHANNEL_BROWSABLE for channelId=$channelId from ${activity.javaClass.simpleName}")
                        activity.startActivity(promptIntent)
                    } else if (!isBrowsable) {
                        // No live Activity; fall back to silent API (no dialog, but channel is registered)
                        Log.w(TAG, "No foreground Activity available, using silent requestChannelBrowsable fallback")
                        TvContractCompat.requestChannelBrowsable(context, channelId)
                    }
                    Toast.makeText(context, R.string.fav_shortcut_requested_toast, Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to request channel browsable: ${e.message}", e)
                }
            }
        }.apply {
            name = "tv-add-program"
            isDaemon = true
            start()
        }
    }

    override fun removeProgram(context: Context, item: FavoritesManager.FavoriteItem) {
        try {
            val helper = PreviewChannelHelper(context)
            if (item.tvProgramUri != null) {
                try {
                    val programId = ContentUris.parseId(Uri.parse(item.tvProgramUri))
                    helper.deletePreviewProgram(programId)
                    Log.d(TAG, "Deleted TV program via helper: programId=$programId")
                } catch (e: Exception) {
                    context.contentResolver.delete(Uri.parse(item.tvProgramUri), null, null)
                }
            } else {
                val where = "${TvContractCompat.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID} = ?"
                context.contentResolver.delete(
                    TvContractCompat.PreviewPrograms.CONTENT_URI,
                    where,
                    arrayOf(item.id)
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete TV program for ${item.label}", e)
        } finally {
            FavoritePosterRenderer.removePoster(context, item)
            val updated = item.copy(isPinnedTv = false, tvProgramUri = null)
            FavoritesManager.updateItem(context, updated)
        }
    }

    override fun reconcileTv(
        context: Context,
        favorites: List<FavoritesManager.FavoriteItem>
    ): List<FavoritesManager.FavoriteItem> {
        val existingProgramIds = mutableSetOf<String>()
        try {
            val projection = arrayOf(
                TvContractCompat.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID
            )
            context.contentResolver.query(
                TvContractCompat.PreviewPrograms.CONTENT_URI,
                projection,
                null,
                null,
                null
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(TvContractCompat.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID)
                while (cursor.moveToNext()) {
                    if (idIdx != -1) {
                        cursor.getString(idIdx)?.let { existingProgramIds.add(it) }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query TV programs for reconciliation: ${e.message}")
            return favorites
        }

        // If no TV programs were found in the provider (e.g. provider not ready or timing issue),
        // do not blindly wipe user favorite pinned states.
        if (existingProgramIds.isEmpty()) {
            return favorites
        }

        var changed = false
        val reconciled = favorites.map { item ->
            val actuallyPinned = existingProgramIds.contains(item.id)
            if (item.isPinnedTv != actuallyPinned) {
                changed = true
                item.copy(
                    isPinnedTv = actuallyPinned,
                    tvProgramUri = if (actuallyPinned) item.tvProgramUri else null
                )
            } else {
                item
            }
        }

        if (changed) {
            reconciled.forEach { FavoritesManager.updateItem(context, it) }
        }
        return reconciled
    }
}
