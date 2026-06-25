package za.kilowatch.ultimatefilemanager.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import org.json.JSONObject
import za.kilowatch.ultimatefilemanager.R
import java.io.File
import java.io.FileOutputStream

object TileIconManager {

    private const val PREFS_NAME = "tile_icons_prefs"
    private const val KEY_ICONS  = "tile_icons"
    const val MAX_SIZE_BYTES = 1 * 1024 * 1024  // 1 MB
    private const val ICON_DIR = "tile_icons"

    /**
     * Internal entry stored in JSON.
     * New format:  `{ "path": "...", "res_name": "ic_folder" }`
     * Legacy compat: `{ "path": "...", "res": 123456789 }` (auto-migrated on first read)
     */
    private data class IconEntry(val path: String?, val iconRes: Int, val iconResName: String? = null)

    // ── Persistence — file path ─────────────────────────────────────────

    fun saveTileIcon(context: Context, tileId: String, path: String?) {
        val entries = loadAllEntries(context).toMutableMap()
        if (path.isNullOrEmpty()) {
            entries.remove(tileId)
        } else {
            val existing = entries[tileId]
            entries[tileId] = IconEntry(path, existing?.iconRes ?: 0)
        }
        saveAllEntries(context, entries)
    }

    fun getTileIcon(context: Context, tileId: String): String? {
        return loadAllEntries(context)[tileId]?.path?.takeIf { it.isNotEmpty() }
    }

    fun getAllTileIcons(context: Context): Map<String, String> {
        return loadAllEntries(context).mapValues { it.value.path ?: "" }
            .filter { it.value.isNotEmpty() }
    }

    // ── Persistence — built-in icon resource ID ─────────────────────────

    fun saveTileIconRes(context: Context, tileId: String, iconRes: Int) {
        val entries = loadAllEntries(context).toMutableMap()
        val existing = entries[tileId]
        if (iconRes == 0 && (existing?.path.isNullOrEmpty())) {
            entries.remove(tileId)
        } else {
            val resName = resolveResName(context, iconRes)
            entries[tileId] = IconEntry(existing?.path, iconRes, resName)
        }
        saveAllEntries(context, entries)
    }

    fun getTileIconRes(context: Context, tileId: String): Int {
        return loadAllEntries(context)[tileId]?.iconRes ?: 0
    }

    fun getAllTileIconRes(context: Context): Map<String, Int> {
        return loadAllEntries(context).mapValues { it.value.iconRes }
            .filter { it.value != 0 }
    }

    /**
     * Returns the drawable resource name for [resId], e.g. "ic_folder".
     * Returns null if the ID is 0 or the name cannot be resolved.
     */
    internal fun resolveResName(context: Context, resId: Int): String? {
        if (resId == 0) return null
        return try {
            context.resources.getResourceEntryName(resId)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Resolves a drawable entry name (e.g. "ic_folder") back to the current
     * build's resource ID.  Returns 0 if the drawable no longer exists.
     */
    internal fun resolveResId(context: Context, name: String): Int {
        return try {
            val id = context.resources.getIdentifier(name, "drawable", context.packageName)
            id
        } catch (_: Exception) {
            0
        }
    }

    // ── Clear ──────────────────────────────────────────────────────────

    fun clearTileIcon(context: Context, tileId: String) {
        val entries = loadAllEntries(context).toMutableMap()
        entries.remove(tileId)
        saveAllEntries(context, entries)
        deleteCustomIcon(context, tileId)
    }

    // ── Private storage management ──────────────────────────────────────

    internal fun iconsDir(context: Context): File {
        return File(context.filesDir, ICON_DIR).also { it.mkdirs() }
    }

    fun deleteCustomIcon(context: Context, tileId: String) {
        val file = File(iconsDir(context), "${sanitizeId(tileId)}.png")
        if (file.exists()) file.delete()
    }

    // ── File copy ──────────────────────────────────────────────────────

    fun copyToPrivateStorage(context: Context, tileId: String, sourcePath: String): String? {
        val sourceFile = File(sourcePath)
        if (!sourceFile.exists()) return null
        if (sourceFile.length() > MAX_SIZE_BYTES) return null

        val bitmap = decodeIconFile(sourceFile) ?: return null

        val outFile = File(iconsDir(context), "${sanitizeId(tileId)}.png")
        FileOutputStream(outFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        return outFile.absolutePath
    }

    // ── Image decoding (including .ico) ─────────────────────────────────

    private fun decodeIconFile(file: File): Bitmap? {
        val ext = file.extension.lowercase()
        return if (ext == "ico") {
            decodeIco(file) ?: decodeIcoBmpFallback(file)
        } else {
            decodeBitmapWithBounds(file)
        }
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

                val w     = bytes[entryOffset].toInt() and 0xFF
                val h     = bytes[entryOffset + 1].toInt() and 0xFF
                val size  = readInt32Le(bytes, entryOffset + 8)
                val off   = readInt32Le(bytes, entryOffset + 12)
                val width  = if (w == 0) 256 else w
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

    private fun decodeIcoBmpFallback(file: File): Bitmap? {
        return decodeBitmapWithBounds(file)
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

    // ── Internal persistence ───────────────────────────────────────────

    /**
     * JSON schema (new): `{ "tileId": { "path": "...", "res_name": "ic_folder" } }`
     * Legacy compat:     `{ "tileId": { "path": "...", "res": 123456789 } }`
     *                    `{ "tileId": "path/to/icon.png" }` (v1 string migration)
     *
     * When a legacy numeric-only "res" entry is found, we resolve it to a name
     * via [resolveResId]/[resolveResName] and rewrite the prefs so future reads
     * use the stable name. If the numeric ID can no longer be resolved the entry
     * is silently dropped (icon falls back to the tile's default icon).
     */
    private fun loadAllEntries(context: Context): Map<String, IconEntry> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ICONS, null) ?: return emptyMap()
        return try {
            val json = JSONObject(raw)
            val result = mutableMapOf<String, IconEntry>()
            var needsMigration = false

            json.keys().forEach { id ->
                val value = json.get(id)
                val entry: IconEntry? = when (value) {
                    // v1 legacy: bare string path
                    is String -> IconEntry(path = value, iconRes = 0, iconResName = null)

                    is JSONObject -> {
                        val path = value.optString("path", null)?.takeIf { it.isNotEmpty() }

                        // Prefer stable name (new format)
                        val resName = value.optString("res_name", null)?.takeIf { it.isNotEmpty() }
                        if (resName != null) {
                            val resId = resolveResId(context, resName)
                            IconEntry(path = path, iconRes = resId, iconResName = resName)
                        } else {
                            // Legacy: raw numeric ID — resolve to name and flag for migration
                            val legacyRes = value.optInt("res", 0)
                            if (legacyRes != 0) {
                                val resolved = resolveResName(context, legacyRes)
                                if (resolved != null) {
                                    // Successfully resolved — re-read current ID for this session
                                    needsMigration = true
                                    IconEntry(
                                        path = path,
                                        iconRes = resolveResId(context, resolved),
                                        iconResName = resolved
                                    )
                                } else {
                                    // Stale / unknown resource ID — drop the icon override safely
                                    Log.w("TileIconManager",
                                        "Dropping stale iconRes 0x${legacyRes.toString(16)} " +
                                        "for tile '$id' — drawable no longer exists")
                                    needsMigration = true
                                    if (path != null) IconEntry(path = path, iconRes = 0, iconResName = null)
                                    else null
                                }
                            } else {
                                IconEntry(path = path, iconRes = 0, iconResName = null)
                            }
                        }
                    }

                    else -> null
                }
                if (entry != null) result[id] = entry
            }

            // Auto-migrate legacy prefs so the next cold-start is already on the new format
            if (needsMigration) {
                saveAllEntries(context, result)
            }

            result
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun saveAllEntries(context: Context, entries: Map<String, IconEntry>) {
        val json = JSONObject()
        entries.forEach { (id, entry) ->
            val obj = JSONObject()
            if (!entry.path.isNullOrEmpty()) obj.put("path", entry.path)
            // Always persist by name (build-stable). Fall back to a fresh name lookup
            // if the entry was created before the iconResName field existed.
            val nameToStore = entry.iconResName
                ?: entry.iconRes.takeIf { it != 0 }?.let { resolveResName(context, it) }
            if (!nameToStore.isNullOrEmpty()) obj.put("res_name", nameToStore)
            json.put(id, obj)
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ICONS, json.toString())
            .commit()
    }

    private fun sanitizeId(id: String): String {
        return id.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
    }

    private fun readInt32Le(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xFF)) or
               ((data[offset + 1].toInt() and 0xFF) shl 8) or
               ((data[offset + 2].toInt() and 0xFF) shl 16) or
               ((data[offset + 3].toInt() and 0xFF) shl 24)
    }
}
