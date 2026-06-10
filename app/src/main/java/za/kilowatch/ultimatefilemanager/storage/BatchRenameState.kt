package za.kilowatch.ultimatefilemanager.storage

/**
 * Immutable state for the batch rename dialog, held in [BatchRenameViewModel].
 */
data class BatchRenameState(
    /** Raw EditText content including token markers ("{Original}", "{Padding}"). */
    val patternText: String = "",
    /** Whether the "Keep Original Text" toggle is active. */
    val useOriginal: Boolean = false,
    /** Whether the "Add Number Padding" toggle is active. */
    val usePadding: Boolean = false,
    /** Zero-pad width (1–9). */
    val paddingLength: Int = 3,
    /** Counter start value (≥ 0). */
    val paddingStart: Int = 1,
    /** The items being renamed (ordered: folders first, then files). */
    val items: List<BatchRenameItem> = emptyList(),
    /** Resolved preview for each item — regenerated on every pattern/option change. */
    val previewItems: List<PreviewItem> = emptyList(),
    /** True when the Rename button should be enabled. */
    val isRenameEnabled: Boolean = false,
    /** Inline error message resource key (null when pattern is valid). */
    val patternError: String? = null
)

/**
 * A single row in the batch rename preview list.
 */
data class PreviewItem(
    /** Original display name (e.g. "photo.jpg" or "MyFolder"). */
    val originalName: String,
    /** Resolved resulting name after pattern substitution. */
    val resultingName: String,
    /** True for folders, false for files. */
    val isFolder: Boolean,
    /** Drawable resource ID for the file/folder icon. */
    val iconRes: Int
)
