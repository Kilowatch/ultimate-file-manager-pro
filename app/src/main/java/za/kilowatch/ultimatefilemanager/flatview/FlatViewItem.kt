package za.kilowatch.ultimatefilemanager.flatview

import za.kilowatch.ultimatefilemanager.indexing.FileIndex

/**
 * Model items displayed in the Flat View RecyclerView list.
 */
sealed interface FlatViewItem {
    val categoryPath: String

    /**
     * Root-level category header (e.g. "A Google Review", "Documents", "Root Files").
     * Styled like main menu categories with 24dp card, squircle icon, bold title, count pill, etc.
     */
    data class RootCategoryHeader(
        override val categoryPath: String,
        val categoryName: String,
        val totalFiles: Int,
        val totalSubfolders: Int,
        var isExpanded: Boolean = true,
        var isSelected: Boolean = false,
        val isRoot: Boolean = false
    ) : FlatViewItem

    /**
     * Subfolder header inside a category (e.g. "Hello", "Hello/World").
     * Displays only the subfolder relative path without repeating the category prefix.
     * Styled like items within categories with 20dp card, squircle icon, count, chevron, etc.
     */
    data class SubfolderHeader(
        val folderPath: String,
        override val categoryPath: String,
        val subfolderDisplayName: String,
        val fileCount: Int,
        var isExpanded: Boolean = true,
        var isSelected: Boolean = false
    ) : FlatViewItem

    /**
     * File entry row.
     * @param fileIndex The indexed file entity from Room.
     * @param folderPath The folder path containing this file.
     * @param categoryPath The root category path containing this file.
     * @param isSelected Whether this file is currently selected in edit mode.
     */
    data class FileEntry(
        val fileIndex: FileIndex,
        val folderPath: String,
        override val categoryPath: String,
        var isSelected: Boolean = false
    ) : FlatViewItem
}
