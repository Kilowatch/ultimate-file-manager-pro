package za.kilowatch.ultimatefilemanager.settings

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.widget.ImageView
import org.json.JSONObject
import za.kilowatch.ultimatefilemanager.storage.TileIconManager
import java.io.File
import java.io.FileOutputStream
import za.kilowatch.ultimatefilemanager.R

data class IconOverride(
    val customPath: String?,
    val builtinRes: Int
)

/** Comprehensive set of all built-in icons available for selection in the icon picker. */
val ALL_BUILTIN_ICONS = intArrayOf(
    // Folders & Storage
    R.drawable.ic_folder, R.drawable.ic_home,
    R.drawable.ic_storage_internal, R.drawable.ic_storage_usb, R.drawable.ic_storage_sdcard,
    // Network & Cloud
    R.drawable.ic_cloud, R.drawable.ic_network, R.drawable.ic_dlna, R.drawable.ic_dropbox,
    // Apps & Features
    R.drawable.ic_apps, R.drawable.ic_search, R.drawable.ic_analyzer, R.drawable.ic_lock,
    R.drawable.ic_star, R.drawable.ic_settings, R.drawable.ic_sync, R.drawable.ic_sync_advanced, R.drawable.ic_notepad,
    R.drawable.ic_scanner, R.drawable.ic_terminal, R.drawable.ic_file_server,
    R.drawable.ic_about, R.drawable.ic_support, R.drawable.ic_delete, R.drawable.ic_sort, R.drawable.ic_twin_window,
    R.drawable.ic_tv, R.drawable.ic_tv_remote, R.drawable.ic_remote_manage,
    R.drawable.ic_remote_enabled, R.drawable.ic_remote_disabled, R.drawable.ic_screenshot, R.drawable.ic_record_screen,
    R.drawable.ic_mic, R.drawable.ic_mic_off,
    // File Types
    R.drawable.ic_file, R.drawable.ic_file_image, R.drawable.ic_file_video,
    R.drawable.ic_file_audio, R.drawable.ic_file_pdf, R.drawable.ic_file_word,
    R.drawable.ic_file_spreadsheet, R.drawable.ic_file_presentation, R.drawable.ic_file_apk,
    R.drawable.ic_file_archive, R.drawable.ic_file_code, R.drawable.ic_file_xml,
    R.drawable.ic_file_text, R.drawable.ic_file_font, R.drawable.ic_file_ebook,
    R.drawable.ic_file_iso, R.drawable.ic_file_database, R.drawable.ic_file_torrent,
    R.drawable.ic_file_subtitle, R.drawable.ic_file_3d, R.drawable.ic_file_backup,
    R.drawable.ic_file_generic,
    // Navigation
    R.drawable.ic_arrow_back, R.drawable.ic_arrow_forward, R.drawable.ic_arrow_up,
    R.drawable.ic_arrow_down, R.drawable.ic_expand_more,
    // Toolbar / Actions
    R.drawable.ic_add, R.drawable.ic_create_new, R.drawable.ic_close, R.drawable.ic_edit, R.drawable.ic_refresh,
    R.drawable.ic_save, R.drawable.ic_paste, R.drawable.ic_copy, R.drawable.ic_move,
    R.drawable.ic_rename, R.drawable.ic_share, R.drawable.ic_check, R.drawable.ic_compress, R.drawable.ic_extract,
    R.drawable.ic_compress_image, R.drawable.ic_copy_encrypt, R.drawable.ic_move_encrypt,
    R.drawable.ic_eye, R.drawable.ic_eye_off, R.drawable.ic_undo, R.drawable.ic_duplicate, R.drawable.ic_duplicate_finder,
    R.drawable.ic_crop, R.drawable.ic_zoom_in, R.drawable.ic_zoom_out, R.drawable.ic_fit_screen,
    R.drawable.ic_paperclip, R.drawable.ic_paperclip_off,
    R.drawable.ic_wallpaper_home, R.drawable.ic_wallpaper_lock,

    // Media Player
    R.drawable.ic_play, R.drawable.ic_pause, R.drawable.ic_skip_next,
    R.drawable.ic_skip_previous, R.drawable.ic_shuffle, R.drawable.ic_repeat,
    R.drawable.ic_fullscreen, R.drawable.ic_fullscreen_exit,
    R.drawable.ic_volume_down, R.drawable.ic_volume_off,
    // Status / Alert
    R.drawable.ic_warning, R.drawable.ic_warning_badge, R.drawable.ic_check_circle,
    R.drawable.ic_shield_check, R.drawable.ic_shield_alert,
    // View Modes
    R.drawable.ic_view_grid_small, R.drawable.ic_view_grid_medium,
    R.drawable.ic_view_grid_large, R.drawable.ic_view_list,
    // Settings
    R.drawable.ic_font_size, R.drawable.ic_language, R.drawable.ic_theme,
    R.drawable.ic_palette, R.drawable.ic_export, R.drawable.ic_import,
    R.drawable.ic_long_press, R.drawable.ic_controls_timeout, R.drawable.ic_photo_video, R.drawable.ic_tune,
    R.drawable.ic_policy, R.drawable.ic_coffee, R.drawable.ic_home,
    // Utility
    R.drawable.ic_more_vert, R.drawable.ic_remove_circle, R.drawable.ic_history,
    R.drawable.ic_import_code, R.drawable.ic_refresh_custom, R.drawable.ic_lightning,
    R.drawable.ic_saf, R.drawable.ic_visibility_off, R.drawable.ic_shizuku_logo,
    R.drawable.ic_notifications, R.drawable.ic_install
)

object IconCustomizationManager {

    private const val PREFS_NAME = "icon_customization_prefs"
    private const val KEY_OVERRIDES = "icon_overrides"
    private const val MAX_SIZE_BYTES = 1 * 1024 * 1024
    private const val ICON_DIR = "custom_icons"

    // ── Read ─────────────────────────────────────────────────────────

    fun getOverride(context: Context, iconId: String): IconOverride? {
        return loadAllOverrides(context)[iconId]
    }

    fun getCustomPath(context: Context, iconId: String): String? {
        return getOverride(context, iconId)?.customPath?.takeIf { it.isNotEmpty() }
    }

    fun getBuiltinRes(context: Context, iconId: String): Int {
        return getOverride(context, iconId)?.builtinRes ?: 0
    }

    fun getAll(context: Context): Map<String, IconOverride> {
        return loadAllOverrides(context)
    }

    fun getEffectiveIconRes(context: Context, iconId: String, defaultRes: Int): Int {
        val override = getOverride(context, iconId)
        if (override != null) {
            if (override.builtinRes != 0) return override.builtinRes
        }
        // For tile icons, check TileIconManager as fallback
        if (iconId.startsWith("tile_")) {
            val tileId = iconId.removePrefix("tile_")
            val tileRes = TileIconManager.getTileIconRes(context, tileId)
            if (tileRes != 0) return tileRes
        }
        return defaultRes
    }

    fun applyToView(context: Context, view: ImageView, iconId: String, defaultRes: Int) {
        val override = getOverride(context, iconId)
        if (override != null) {
            if (!override.customPath.isNullOrEmpty()) {
                val bitmap = loadCustomBitmap(override.customPath)
                if (bitmap != null) {
                    view.setImageBitmap(bitmap)
                    return
                }
            }
            if (override.builtinRes != 0) {
                view.setImageResource(override.builtinRes)
                return
            }
        }
        // For tile icons, check TileIconManager as fallback
        if (iconId.startsWith("tile_")) {
            val tileId = iconId.removePrefix("tile_")
            val customPath = TileIconManager.getTileIcon(context, tileId)
            if (!customPath.isNullOrEmpty()) {
                val bitmap = loadCustomBitmap(customPath)
                if (bitmap != null) {
                    view.setImageBitmap(bitmap)
                    return
                }
            }
            val tileRes = TileIconManager.getTileIconRes(context, tileId)
            if (tileRes != 0) {
                view.setImageResource(tileRes)
                return
            }
        }
        view.setImageResource(defaultRes)
    }

    fun getCustomBitmap(context: Context, iconId: String): Bitmap? {
        val path = getCustomPath(context, iconId) ?: return null
        return loadCustomBitmap(path)
    }

    // ── Write ────────────────────────────────────────────────────────

    fun setCustomPath(context: Context, iconId: String, path: String?) {
        val entries = loadAllOverrides(context).toMutableMap()
        val existing = entries[iconId]
        if (path.isNullOrEmpty()) {
            if (existing != null && existing.builtinRes == 0) {
                entries.remove(iconId)
            } else {
                entries[iconId] = IconOverride(null, existing?.builtinRes ?: 0)
            }
        } else {
            entries[iconId] = IconOverride(path, existing?.builtinRes ?: 0)
        }
        saveAllOverrides(context, entries)
    }

    fun setBuiltinRes(context: Context, iconId: String, res: Int) {
        val entries = loadAllOverrides(context).toMutableMap()
        val existing = entries[iconId]
        if (res == 0 && existing?.customPath.isNullOrEmpty()) {
            entries.remove(iconId)
        } else {
            entries[iconId] = IconOverride(existing?.customPath, res)
        }
        saveAllOverrides(context, entries)
    }

    fun clearOverride(context: Context, iconId: String) {
        val entries = loadAllOverrides(context).toMutableMap()
        entries.remove(iconId)
        saveAllOverrides(context, entries)
        deleteCustomIcon(context, iconId)
    }

    fun clearAll(context: Context) {
        val entries = loadAllOverrides(context)
        for (iconId in entries.keys) {
            deleteCustomIcon(context, iconId)
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_OVERRIDES)
            .apply()
    }

    // ── Private storage ──────────────────────────────────────────────

    private fun iconsDir(context: Context): File {
        return File(context.filesDir, ICON_DIR).also { it.mkdirs() }
    }

    private fun deleteCustomIcon(context: Context, iconId: String) {
        val file = File(iconsDir(context), "${sanitizeId(iconId)}.png")
        if (file.exists()) file.delete()
    }

    fun copyToPrivateStorage(context: Context, iconId: String, sourcePath: String): String? {
        val sourceFile = File(sourcePath)
        if (!sourceFile.exists()) return null
        if (sourceFile.length() > MAX_SIZE_BYTES) return null

        val bitmap = decodeIconFile(sourceFile) ?: return null

        val outFile = File(iconsDir(context), "${sanitizeId(iconId)}.png")
        FileOutputStream(outFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        return outFile.absolutePath
    }

    // ── Image decoding (mirrors TileIconManager logic) ───────────────

    private fun decodeIconFile(file: File): Bitmap? {
        val ext = file.extension.lowercase()
        return if (ext == "ico") {
            decodeIco(file) ?: decodeBitmapWithBounds(file)
        } else {
            decodeBitmapWithBounds(file)
        }
    }

    private fun loadCustomBitmap(path: String): Bitmap? {
        val file = File(path)
        if (!file.exists()) return null
        return BitmapFactory.decodeFile(path)
    }

    private fun decodeBitmapWithBounds(file: File): Bitmap? {
        val opts = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null

        val target = 256
        opts.inSampleSize = 1
        while (opts.outWidth / opts.inSampleSize > target ||
               opts.outHeight / opts.inSampleSize > target) {
            opts.inSampleSize *= 2
        }
        opts.inJustDecodeBounds = false
        return BitmapFactory.decodeFile(file.absolutePath, opts)
    }

    private fun decodeIco(file: File): Bitmap? {
        try {
            val bytes = file.readBytes()
            if (bytes.size < 6) return null

            val count = ((bytes[4].toInt() and 0xFF) shl 8) or (bytes[5].toInt() and 0xFF)
            if (count <= 0 || count > 255) return null

            data class IcoEntry(val width: Int, val height: Int, val size: Int, val offset: Int, val isPng: Boolean)

            val entries = mutableListOf<IcoEntry>()
            for (i in 0 until count) {
                val entryOffset = 6 + i * 16
                if (entryOffset + 16 > bytes.size) break

                val w = bytes[entryOffset].toInt() and 0xFF
                val h = bytes[entryOffset + 1].toInt() and 0xFF
                val size = readInt32Le(bytes, entryOffset + 8)
                val off = readInt32Le(bytes, entryOffset + 12)
                val width = if (w == 0) 256 else w
                val height = if (h == 0) 256 else h

                val isPng = off + 8 <= bytes.size &&
                    bytes[off] == 0x89.toByte() &&
                    bytes[off + 1] == 0x50.toByte() &&
                    bytes[off + 2] == 0x4E.toByte() &&
                    bytes[off + 3] == 0x47.toByte() &&
                    bytes[off + 4] == 0x0D.toByte() &&
                    bytes[off + 5] == 0x0A.toByte() &&
                    bytes[off + 6] == 0x1A.toByte() &&
                    bytes[off + 7] == 0x0A.toByte()

                entries.add(IcoEntry(width, height, size, off, isPng))
            }

            val best = entries
                .filter { it.size > 0 && it.offset + it.size <= bytes.size }
                .maxWithOrNull(compareByDescending<IcoEntry> { if (it.isPng) 1 else 0 }
                    .thenByDescending { it.width * it.height })

            if (best == null) return null

            return if (best.isPng) {
                val pngBytes = bytes.copyOfRange(best.offset, best.offset + best.size)
                decodeBitmapWithBoundsFromBytes(pngBytes)
            } else {
                null
            }
        } catch (_: Exception) {
            return null
        }
    }

    private fun decodeBitmapWithBoundsFromBytes(data: ByteArray): Bitmap? {
        val opts = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(data, 0, data.size, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null

        val target = 256
        opts.inSampleSize = 1
        while (opts.outWidth / opts.inSampleSize > target ||
               opts.outHeight / opts.inSampleSize > target) {
            opts.inSampleSize *= 2
        }
        opts.inJustDecodeBounds = false
        return BitmapFactory.decodeByteArray(data, 0, data.size, opts)
    }

    private fun readInt32Le(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xFF)) or
               ((data[offset + 1].toInt() and 0xFF) shl 8) or
               ((data[offset + 2].toInt() and 0xFF) shl 16) or
               ((data[offset + 3].toInt() and 0xFF) shl 24)
    }

    // ── Persistence ──────────────────────────────────────────────────

    private fun loadAllOverrides(context: Context): Map<String, IconOverride> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_OVERRIDES, null) ?: return emptyMap()
        return try {
            val json = JSONObject(raw)
            val result = mutableMapOf<String, IconOverride>()
            var needsMigration = false

            json.keys().forEach { id ->
                val value = json.get(id)
                if (value is JSONObject) {
                    val path = value.optString("path", null)?.takeIf { it.isNotEmpty() }

                    // Prefer stable name (new format)
                    val resName = value.optString("res_name", null)?.takeIf { it.isNotEmpty() }
                    val resolvedRes: Int
                    if (resName != null) {
                        resolvedRes = TileIconManager.resolveResId(context, resName)
                    } else {
                        // Legacy: raw numeric ID — resolve to name and flag for migration
                        val legacyRes = value.optInt("res", 0)
                        resolvedRes = if (legacyRes != 0) {
                            val resolved = TileIconManager.resolveResName(context, legacyRes)
                            if (resolved != null) {
                                needsMigration = true
                                TileIconManager.resolveResId(context, resolved)
                            } else {
                                Log.w("IconCustomizationManager",
                                    "Dropping stale builtinRes 0x${legacyRes.toString(16)} " +
                                    "for icon '$id' — drawable no longer exists")
                                needsMigration = true
                                0
                            }
                        } else 0
                    }

                    if (path != null || resolvedRes != 0) {
                        result[id] = IconOverride(customPath = path, builtinRes = resolvedRes)
                    }
                }
            }

            if (needsMigration) {
                saveAllOverrides(context, result)
            }

            result
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun saveAllOverrides(context: Context, entries: Map<String, IconOverride>) {
        val json = JSONObject()
        entries.forEach { (id, entry) ->
            val obj = JSONObject()
            if (!entry.customPath.isNullOrEmpty()) obj.put("path", entry.customPath)
            // Persist by name (build-stable) instead of raw resource ID
            val nameToStore = TileIconManager.resolveResName(context, entry.builtinRes)
            if (!nameToStore.isNullOrEmpty()) obj.put("res_name", nameToStore)
            json.put(id, obj)
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_OVERRIDES, json.toString())
            .apply()
    }

    private fun sanitizeId(id: String): String {
        return id.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
    }
}
