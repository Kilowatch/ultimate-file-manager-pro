package za.kilowatch.ultimatefilemanager.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

object TileIconManager {

    private const val PREFS_NAME = "tile_icons_prefs"
    private const val KEY_ICONS  = "tile_icons"
    const val MAX_SIZE_BYTES = 1 * 1024 * 1024  // 1 MB
    private const val ICON_DIR = "tile_icons"

    /** Internal entry stored in JSON: `{ "path": "...", "res": 123 }` */
    private data class IconEntry(val path: String?, val iconRes: Int)

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
            entries[tileId] = IconEntry(existing?.path, iconRes)
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

    // ── Clear ──────────────────────────────────────────────────────────

    fun clearTileIcon(context: Context, tileId: String) {
        val entries = loadAllEntries(context).toMutableMap()
        entries.remove(tileId)
        saveAllEntries(context, entries)
        deleteCustomIcon(context, tileId)
    }

    // ── Private storage management ──────────────────────────────────────

    private fun iconsDir(context: Context): File {
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

    /** JSON schema: `{ "tileId": { "path": "...", "res": 123 } }` or legacy string. */
    private fun loadAllEntries(context: Context): Map<String, IconEntry> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ICONS, null) ?: return emptyMap()
        return try {
            val json = JSONObject(raw)
            val result = mutableMapOf<String, IconEntry>()
            json.keys().forEach { id ->
                val value = json.get(id)
                result[id] = when (value) {
                    is String -> IconEntry(value, 0)               // v1 migration
                    is JSONObject -> IconEntry(
                        path    = value.optString("path", null)?.takeIf { it.isNotEmpty() },
                        iconRes = value.optInt("res", 0)
                    )
                    else -> null
                } ?: return@forEach
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
            if (entry.iconRes != 0) obj.put("res", entry.iconRes)
            json.put(id, obj)
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ICONS, json.toString())
            .apply()
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
