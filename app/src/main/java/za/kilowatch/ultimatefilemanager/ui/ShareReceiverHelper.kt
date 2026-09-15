package za.kilowatch.ultimatefilemanager.ui

import android.content.ClipData
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap

/**
 * Encapsulates metadata for an individual item received via Android share sheets (ACTION_SEND or ACTION_SEND_MULTIPLE).
 */
data class SharedItem(
    val uri: Uri,
    val fileName: String,
    val fileSize: Long,
    val mimeType: String
)

/**
 * Helper utility for extracting, sanitizing, and resolving file metadata for incoming share intents.
 */
object ShareReceiverHelper {

    /**
     * Extracts all incoming URIs from an [Intent], supporting both ACTION_SEND and ACTION_SEND_MULTIPLE,
     * with graceful fallbacks to [ClipData] and `intent.data`.
     */
    fun extractUris(intent: Intent?): List<Uri> {
        if (intent == null) return emptyList()

        val uris = mutableListOf<Uri>()

        when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                if (uri != null) {
                    uris.add(uri)
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                }
                if (list != null) {
                    uris.addAll(list.filterNotNull())
                }
            }
        }

        // Fallback to ClipData if EXTRA_STREAM did not yield any URIs (or if app shared via clipData)
        if (uris.isEmpty()) {
            intent.clipData?.let { clipData ->
                for (i in 0 until clipData.itemCount) {
                    clipData.getItemAt(i)?.uri?.let { uris.add(it) }
                }
            }
        }

        // Fallback to intent.data if still empty
        if (uris.isEmpty()) {
            intent.data?.let { uris.add(it) }
        }

        return uris.distinct()
    }

    /**
     * Sanitizes a proposed file name by removing characters illegal in filesystems (/ \ : * ? " < > |)
     * and trimming whitespace.
     */
    fun sanitizeFileName(name: String): String {
        val sanitized = name.replace(Regex("[/\\\\:*?\"<>|]"), "_").trim()
        return sanitized.ifBlank { "shared_file" }
    }

    /**
     * Ensures that multiple items in an incoming batch have unique filenames
     * (e.g. "photo.jpg", "photo_(1).jpg", "photo_(2).jpg").
     */
    fun deduplicateItemNames(items: List<SharedItem>): List<SharedItem> {
        val usedNames = mutableSetOf<String>()
        return items.map { item ->
            var candidateName = item.fileName
            val hasExtension = candidateName.contains('.') && !candidateName.startsWith('.')
            val baseName = if (hasExtension) candidateName.substringBeforeLast('.') else candidateName
            val ext = if (hasExtension) ".${candidateName.substringAfterLast('.')}" else ""
            var count = 1
            while (usedNames.contains(candidateName.lowercase())) {
                candidateName = "${baseName}_($count)$ext"
                count++
            }
            usedNames.add(candidateName.lowercase())
            if (candidateName != item.fileName) {
                item.copy(fileName = candidateName)
            } else {
                item
            }
        }
    }

    /**
     * Resolves display name, size, and MIME type for a single [Uri].
     */
    fun resolveItem(
        contentResolver: ContentResolver,
        uri: Uri,
        defaultMimeType: String = "*/*",
        index: Int = 0
    ): SharedItem {
        var name = ""
        var size = 0L
        var mimeType = defaultMimeType

        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0) {
                        name = cursor.getString(nameIdx) ?: ""
                    }
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIdx >= 0) {
                        size = cursor.getLong(sizeIdx)
                    }
                }
            }
        } catch (_: Exception) {}

        if (name.isBlank()) {
            val segment = uri.lastPathSegment
            if (!segment.isNullOrBlank()) {
                name = segment.substringAfterLast('/')
            }
        }

        try {
            val resolvedMime = contentResolver.getType(uri)
            if (!resolvedMime.isNullOrBlank()) {
                mimeType = resolvedMime
            }
        } catch (_: Exception) {}

        name = sanitizeFileName(name)

        val hasExt = name.contains('.') && !name.startsWith('.')
        if (!hasExt) {
            val ext = if (mimeType != "*/*") {
                MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
            } else null
            name = if (!ext.isNullOrBlank()) {
                if (name.isBlank() || name == "shared_file") "shared_file_${index + 1}.$ext" else "$name.$ext"
            } else {
                if (name.isBlank() || name == "shared_file") "shared_file_${index + 1}" else name
            }
        }

        return SharedItem(
            uri = uri,
            fileName = name,
            fileSize = maxOf(0L, size),
            mimeType = mimeType
        )
    }

    /**
     * Resolves metadata for all provided URIs and deduplicates any identical filenames in the batch.
     */
    fun resolveAllItems(
        contentResolver: ContentResolver,
        uris: List<Uri>,
        defaultMimeType: String = "*/*"
    ): List<SharedItem> {
        val resolved = uris.mapIndexed { index, uri ->
            resolveItem(contentResolver, uri, defaultMimeType, index)
        }
        return deduplicateItemNames(resolved)
    }
}
