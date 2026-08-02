package za.kilowatch.ultimatefilemanager.storage

import android.content.Context
import android.media.ExifInterface
import android.provider.MediaStore
import za.kilowatch.ultimatefilemanager.util.GoRoLog
import java.io.File
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import za.kilowatch.ultimatefilemanager.R
import java.util.concurrent.Executors


object FileTagsManager {
    private const val TAG = "FileTagsManager"
    private const val PREFS_NAME = "ufm_file_tags"

    // In-memory mirror of the ufm_file_tags map, updated synchronously so a read immediately after
    // a write sees the new value even though disk persistence is async. Disk persistence runs on a
    // dedicated single-thread writer using commit() (NOT apply()): apply() posts the write to
    // android.app.QueuedWork, and the framework then calls QueuedWork.waitToFinish() on the main
    // thread during Activity.onPause()/onStop(), blocking it for the full fsync() duration — an
    // unbounded per-path ufm_file_tags file (one key per tagged file) makes that flush exceed 5 s
    // on slow devices. commit() never touches QueuedWork, so the main thread is never blocked.
    // See SecureTokenStore KDoc for the same rationale.
    @Volatile
    private var cachedEntries: Map<String, String>? = null

    private val ioWriter = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ufm-file-tags-writer").apply { isDaemon = true }
    }

    /** Current tag map — lazy-loaded from prefs once, then kept in sync in memory. */
    private fun entries(context: Context): Map<String, String> {
        cachedEntries?.let { return it }
        synchronized(this) {
            cachedEntries?.let { return it }
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val map = prefs.all.mapNotNull { (k, v) -> if (v is String) k to v else null }.toMap()
            cachedEntries = map
            return map
        }
    }

    /**
     * Drop the in-memory tag map so the next read reloads from disk. Called after a settings
     * restore (which rewrites `ufm_file_tags` directly via [android.content.SharedPreferences]),
     * so the cache cannot serve stale pre-restore data.
     */
    fun invalidateCache() {
        cachedEntries = null
    }

    /** Persist the full tag map to disk on the writer thread via commit(). */
    private fun persist(context: Context, snapshot: Map<String, String>) {
        val appContext = context.applicationContext
        val payload = snapshot.toMap()
        ioWriter.execute {
            try {
                val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit().apply {
                    clear()
                    payload.forEach { (k, v) -> putString(k, v) }
                }.commit()
            } catch (e: Exception) {
                GoRoLog.e(TAG, "Failed to persist tags: ${e.message}")
            }
        }
    }

    /** Writes tag metadata into a local image's EXIF on the calling thread. */
    private fun writeExifTags(filePath: String, sanitized: Set<String>) {
        val file = File(filePath)
        if (file.exists() && file.isFile) {
            val ext = file.extension.lowercase()
            if (ext in setOf("jpg", "jpeg", "png", "webp", "heic", "heif")) {
                try {
                    val exifInterface = ExifInterface(filePath)
                    // Standard Windows/Samsung keywords (semicolon-delimited)
                    exifInterface.setAttribute("XPKeywords", sanitized.joinToString(";"))
                    // Custom prefix in UserComment
                    exifInterface.setAttribute(ExifInterface.TAG_USER_COMMENT, "tags:" + sanitized.joinToString(","))
                    exifInterface.saveAttributes()
                } catch (e: Exception) {
                    GoRoLog.e(TAG, "Error writing EXIF tags to $filePath: ${e.message}")
                }
            }
        }
    }

    /**
     * Get all unique tags ever created across all files in UFM.
     */
    fun getAllCreatedTags(context: Context): Set<String> {
        val allTags = mutableSetOf<String>()
        try {
            for (value in entries(context).values) {
                allTags.addAll(sanitizeAndSplit(value))
            }
        } catch (e: Exception) {
            GoRoLog.e(TAG, "Error getting all created tags: ${e.message}")
        }
        return allTags
    }

    /**
     * Get all tags for a file.
     * Combines SharedPreferences, EXIF keywords (local images), and MediaStore description.
     * Returns a set of sanitized alphanumeric tags.
     */
    fun getTags(context: Context, filePath: String): Set<String> {
        val tags = mutableSetOf<String>()
        val file = File(filePath)

        // 1. Read from the in-memory tag map (immediately consistent after a save)
        val savedTags = entries(context)[filePath]
        if (!savedTags.isNullOrEmpty()) {
            tags.addAll(sanitizeAndSplit(savedTags))
        }

        // 2. For local files, read EXIF if it's a supported image format
        if (file.exists() && file.isFile) {
            val ext = file.extension.lowercase()
            if (ext in setOf("jpg", "jpeg", "png", "webp", "heic", "heif")) {
                try {
                    val exifInterface = ExifInterface(filePath)
                    // Read standard Windows/Samsung EXIF keywords
                    val xpKeywords = exifInterface.getAttribute("XPKeywords")
                    if (!xpKeywords.isNullOrEmpty()) {
                        tags.addAll(sanitizeAndSplit(xpKeywords))
                    }

                    // Fallback to UserComment tag
                    val userComment = exifInterface.getAttribute(ExifInterface.TAG_USER_COMMENT)
                    if (!userComment.isNullOrEmpty() && userComment.startsWith("tags:")) {
                        val commentContent = userComment.substringAfter("tags:")
                        tags.addAll(sanitizeAndSplit(commentContent))
                    }
                } catch (e: Exception) {
                    GoRoLog.e(TAG, "Error reading EXIF tags for $filePath: ${e.message}")
                }
            }

            // 3. Read description from MediaStore (local files only)
            try {
                val uri = MediaStore.Files.getContentUri("external")
                val projection = arrayOf("description")
                val selection = "_data = ?"
                val selectionArgs = arrayOf(filePath)
                context.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val desc = cursor.getString(0)
                        if (!desc.isNullOrEmpty()) {
                            tags.addAll(sanitizeAndSplit(desc))
                        }
                    }
                }
            } catch (e: Exception) {
                // MediaStore query might fail on some devices or if permission is missing; catch and proceed
                GoRoLog.d(TAG, "MediaStore description query skipped: ${e.message}")
            }
        }

        return tags
    }

    /**
     * Save tags for a file.
     * Writes to SharedPreferences, and writes to EXIF metadata if it's a local image.
     */
    fun saveTags(context: Context, filePath: String, tags: Set<String>) {
        val sanitized = tags.map { sanitizeTag(it) }.filter { it.isNotEmpty() }.toSet()

        // 1. Update the in-memory tag map synchronously, then persist to disk asynchronously via
        //    commit() on the dedicated writer thread (see class KDoc — never QueuedWork).
        val updated = entries(context).toMutableMap()
        if (sanitized.isEmpty()) {
            updated.remove(filePath)
        } else {
            updated[filePath] = sanitized.joinToString(",")
        }
        cachedEntries = updated
        persist(context, updated)

        // 2. Save to EXIF for local images
        writeExifTags(filePath, sanitized)
    }

    /**
     * Delete a tag globally from the entire UFM tagging database.
     * Cleans it from SharedPreferences and updates EXIF of any local images that contain it.
     */
    fun deleteGlobalTag(context: Context, tagToDelete: String) {
        val tagToClean = tagToDelete.trim().trimStart('#').filter { it.isLetterOrDigit() }
        if (tagToClean.isEmpty()) return

        // Batch the whole cleanup into ONE cache update + ONE background commit() (the previous
        // per-entry saveTags loop queued one full-file apply() rewrite per matching file).
        val current = entries(context)
        val updated = current.toMutableMap()
        var changed = false
        for ((filePath, value) in current) {
            val tags = sanitizeAndSplit(value).toMutableList()
            if (tags.remove(tagToClean)) {
                if (tags.isEmpty()) {
                    updated.remove(filePath)
                } else {
                    updated[filePath] = tags.joinToString(",")
                }
                changed = true
                writeExifTags(filePath, tags.toSet())
            }
        }
        if (changed) {
            cachedEntries = updated
            persist(context, updated)
        }
    }

    /**
     * Handles updating tag paths when a file or directory is moved or renamed.
     *
     * Called once per file inside copy/move loops that already run on a background thread. The
     * update is applied to the in-memory map synchronously and persisted with a single commit() on
     * the writer thread — the previous editor.apply() queued a full-file rewrite to QueuedWork per
     * file, and a large folder move would pile dozens/hundreds of rewrites onto the queued-work
     * looper that the main thread then waits on at the next Activity.onStop().
     */
    fun onPathMoved(context: Context, oldPath: String, newPath: String) {
        val current = entries(context)
        val updated = current.toMutableMap()
        var changed = false

        for ((key, value) in current) {
            if (key == oldPath) {
                updated.remove(oldPath)
                updated[newPath] = value
                changed = true
            } else if (key.startsWith("$oldPath/")) {
                val subPath = key.substring(oldPath.length)
                val newKey = newPath + subPath
                updated.remove(key)
                updated[newKey] = value
                changed = true
            }
        }
        if (changed) {
            cachedEntries = updated
            persist(context, updated)
        }
    }

    /**
     * Handles duplicating tag paths when a file or directory is copied.
     *
     * Same background-commit() rationale as [onPathMoved].
     */
    fun onPathCopied(context: Context, oldPath: String, newPath: String) {
        val current = entries(context)
        val updated = current.toMutableMap()
        var changed = false

        for ((key, value) in current) {
            if (key == oldPath) {
                updated[newPath] = value
                changed = true
            } else if (key.startsWith("$oldPath/")) {
                val subPath = key.substring(oldPath.length)
                val newKey = newPath + subPath
                updated[newKey] = value
                changed = true
            }
        }
        if (changed) {
            cachedEntries = updated
            persist(context, updated)
        }
    }

    /**
     * Show a dialog to edit tags for multiple files.
     */
    fun showMultiFileTagDialog(
        context: Context,
        filePaths: List<String>,
        onSaved: () -> Unit
    ) {
        val allCreatedTags = getAllCreatedTags(context)
        val unionTags = mutableSetOf<String>()
        for (path in filePaths) {
            unionTags.addAll(getTags(context, path))
        }

        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_edit_tags, null)
        val edtInput = dialogView.findViewById<TextInputEditText>(R.id.edtTagsInput)
        val txtHeader = dialogView.findViewById<TextView>(R.id.txtCreatedTagsHeader)
        val cgExisting = dialogView.findViewById<ChipGroup>(R.id.cgExistingTags)

        val chipsMap = mutableMapOf<String, Chip>()

        if (allCreatedTags.isNotEmpty()) {
            txtHeader.visibility = View.VISIBLE
            for (tag in allCreatedTags) {
                val chip = LayoutInflater.from(context)
                    .inflate(R.layout.item_tag_chip, cgExisting, false) as Chip
                chip.text = "#$tag"
                cgExisting.addView(chip)
                chipsMap[tag] = chip
            }
        }

        fun updateChipsFromInput(inputStr: String) {
            val parsedTags = sanitizeAndSplit(inputStr).toSet()
            for ((cleanTag, chip) in chipsMap) {
                chip.setOnCheckedChangeListener(null)
                chip.isChecked = parsedTags.contains(cleanTag)
                chip.setOnCheckedChangeListener { _, isChecked ->
                    val currentText = edtInput.text?.toString() ?: ""
                    val tagsList = sanitizeAndSplit(currentText).toMutableList()
                    if (isChecked) {
                        if (!tagsList.contains(cleanTag)) {
                            tagsList.add(cleanTag)
                        }
                    } else {
                        tagsList.remove(cleanTag)
                    }
                    edtInput.setText(tagsList.joinToString(", "))
                    edtInput.setSelection(edtInput.text?.length ?: 0)
                }
            }
        }

        val initialText = unionTags.joinToString(", ")
        edtInput.setText(initialText)
        edtInput.setSelection(edtInput.text?.length ?: 0)
        updateChipsFromInput(initialText)

        edtInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                updateChipsFromInput(s?.toString() ?: "")
            }
        })

        val dialogTheme = com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog

        MaterialAlertDialogBuilder(context, dialogTheme)
            .setTitle("Tag Multiple Files")
            .setView(dialogView)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Done") { _, _ ->
                val textInput = edtInput.text?.toString() ?: ""
                val newTags = sanitizeAndSplit(textInput).toSet()
                // Batch all files into ONE cache update + ONE background commit() instead of one
                // apply() per file — a per-file apply() loop would queue N full-file rewrites to
                // QueuedWork, which the main thread then waits on at the next Activity.onStop().
                val updated = entries(context).toMutableMap()
                for (path in filePaths) {
                    if (newTags.isEmpty()) {
                        updated.remove(path)
                    } else {
                        updated[path] = newTags.joinToString(",")
                    }
                }
                cachedEntries = updated
                persist(context, updated)
                for (path in filePaths) {
                    writeExifTags(path, newTags)
                }
                onSaved()
            }
            .show()
    }

    /**
     * Splits a raw string by common separators (comma, semicolon, space, newline)
     * and sanitizes each token to be purely alphanumeric.
     */
    fun sanitizeAndSplit(raw: String): List<String> {
        val cleaned = raw.replace("\u0000", "").replace("\u0001", "")
        val delimiters = charArrayOf(',', ';', ' ', '\t', '\n', '\r')
        return cleaned.split(*delimiters)
            .map { sanitizeTag(it) }
            .filter { it.isNotEmpty() }
    }

    /**
     * Retain only alphanumeric characters (A-Za-z0-9) inside the tag.
     */
    private fun sanitizeTag(tag: String): String {
        // Strip out leading '#' symbol if present before sanitizing, then retain only alphanumeric
        val trimmed = tag.trim().trimStart('#')
        return trimmed.filter { it.isLetterOrDigit() }
    }
}
