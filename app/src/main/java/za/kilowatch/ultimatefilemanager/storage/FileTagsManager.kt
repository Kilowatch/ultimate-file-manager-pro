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


object FileTagsManager {
    private const val TAG = "FileTagsManager"
    private const val PREFS_NAME = "ufm_file_tags"

    /**
     * Get all unique tags ever created across all files in UFM.
     */
    fun getAllCreatedTags(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val allTags = mutableSetOf<String>()
        try {
            for (value in prefs.all.values) {
                if (value is String) {
                    allTags.addAll(sanitizeAndSplit(value))
                }
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

        // 1. Read from local SharedPreferences mapping
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedTags = prefs.getString(filePath, null)
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
        val file = File(filePath)

        // 1. Save to SharedPreferences mapping
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (sanitized.isEmpty()) {
            prefs.edit().remove(filePath).apply()
        } else {
            prefs.edit().putString(filePath, sanitized.joinToString(",")).apply()
        }

        // 2. Save to EXIF for local images
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
     * Delete a tag globally from the entire UFM tagging database.
     * Cleans it from SharedPreferences and updates EXIF of any local images that contain it.
     */
    fun deleteGlobalTag(context: Context, tagToDelete: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val allEntries = prefs.all
        val tagToClean = tagToDelete.trim().trimStart('#').filter { it.isLetterOrDigit() }
        if (tagToClean.isEmpty()) return

        for ((filePath, value) in allEntries) {
            if (value is String) {
                val tags = sanitizeAndSplit(value).toMutableList()
                if (tags.remove(tagToClean)) {
                    saveTags(context, filePath, tags.toSet())
                }
            }
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
                for (path in filePaths) {
                    saveTags(context, path, newTags)
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
