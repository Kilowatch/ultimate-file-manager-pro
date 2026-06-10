package za.kilowatch.ultimatefilemanager.storage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * ViewModel for [BatchRenameDialogFragment].
 *
 * Holds all dialog state and survives configuration changes.
 * Pattern resolution is delegated to [BatchRenamePatternResolver].
 */
class BatchRenameViewModel(private val initialItems: List<BatchRenameItem>) : ViewModel() {

    companion object {
        const val TOKEN_ORIGINAL = "{Original}"
        const val TOKEN_PADDING = "{Padding}"
    }

    // Guard against re-entrant TextWatcher callbacks during programmatic EditText changes.
    var suppressTextWatcher = false

    private val _state = MutableStateFlow(BatchRenameState(items = initialItems))
    val state: StateFlow<BatchRenameState> = _state

    init {
        rebuildPreviews()
    }

    // ── Pattern text ──────────────────────────────────────────────────────────

    fun updatePattern(text: String) {
        _state.update { it.copy(patternText = text) }
        rebuildPreviews()
    }

    // ── Option toggles ────────────────────────────────────────────────────────

    fun toggleOriginal() {
        val current = _state.value
        val newUseOriginal = !current.useOriginal
        _state.update { it.copy(useOriginal = newUseOriginal) }
        rebuildPreviews()
    }

    fun togglePadding() {
        val current = _state.value
        val newUsePadding = !current.usePadding
        _state.update { it.copy(usePadding = newUsePadding) }
        rebuildPreviews()
    }

    // ── Padding controls ──────────────────────────────────────────────────────

    fun setPaddingLength(value: Int) {
        _state.update { it.copy(paddingLength = value.coerceIn(1, 9)) }
        rebuildPreviews()
    }

    fun setPaddingStart(value: Int) {
        _state.update { it.copy(paddingStart = value.coerceAtLeast(0)) }
        rebuildPreviews()
    }

    // ── Two-way sync: EditText → button states ────────────────────────────────

    fun syncButtonStates(currentText: String) {
        val originalPresent = currentText.contains(TOKEN_ORIGINAL)
        val paddingPresent = currentText.contains(TOKEN_PADDING)

        var changed = false
        var newState = _state.value

        if (newState.useOriginal != originalPresent) {
            newState = newState.copy(useOriginal = originalPresent)
            changed = true
        }
        if (newState.usePadding != paddingPresent) {
            newState = newState.copy(usePadding = paddingPresent)
            changed = true
        }

        if (changed) {
            _state.value = newState
            rebuildPreviews()
        }
    }

    // ── Token snap-in (called by DialogFragment after programmatic edits) ─────

    /**
     * Snap the pattern text to the ViewModel without triggering re-entrancy.
     * Used after programmatic token insertion/removal so the ViewModel is in sync
     * with what's actually in the EditText.
     */
    fun setPatternText(text: String) {
        val trimmed = collapseSpaces(text)
        _state.update { it.copy(patternText = trimmed) }
        rebuildPreviews()
    }

    // ── Preview rebuild ───────────────────────────────────────────────────────

    private fun rebuildPreviews() {
        val s = _state.value
        val pattern = s.patternText

        // Determine if base text (after stripping tokens) is non-empty
        val baseText = pattern
            .replace(TOKEN_ORIGINAL, "")
            .replace(TOKEN_PADDING, "")
            .trim()

        val hasTokens = s.useOriginal || s.usePadding
        val isRenameEnabled = baseText.isNotEmpty() || hasTokens

        val patternError = if (!isRenameEnabled && !hasTokens && baseText.isEmpty()) {
            "batch_rename_error_empty_pattern"
        } else {
            null
        }

        val previews = s.items.mapIndexed { index, item ->
            val counter = index + 1  // 1-based

            val resolvedName = BatchRenamePatternResolver.resolve(
                pattern = pattern,
                item = item,
                counter = counter,
                useOriginal = s.useOriginal,
                usePadding = s.usePadding,
                paddingLength = s.paddingLength,
                paddingStart = s.paddingStart
            )

            val fullResult = BatchRenamePatternResolver.appendExtension(resolvedName, item)

            PreviewItem(
                originalName = item.fullName,
                resultingName = fullResult.ifEmpty { "" },
                isFolder = item.isDirectory,
                iconRes = if (item.isDirectory) {
                    za.kilowatch.ultimatefilemanager.R.drawable.ic_folder
                } else {
                    za.kilowatch.ultimatefilemanager.R.drawable.ic_file
                }
            )
        }

        _state.update {
            it.copy(
                previewItems = previews,
                isRenameEnabled = isRenameEnabled,
                patternError = patternError
            )
        }
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private fun collapseSpaces(text: String): String {
        return text.replace(Regex(" +"), " ").trim()
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    class Factory(private val items: List<BatchRenameItem>) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(BatchRenameViewModel::class.java)) {
                // Sort: folders first, then files, preserving original order within each group
                val sorted = items.sortedWith(compareByDescending<BatchRenameItem> { it.isDirectory }
                    .thenBy { items.indexOf(it) })
                return BatchRenameViewModel(sorted) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
