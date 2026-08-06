package za.kilowatch.ultimatefilemanager.storage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * ViewModel for [BatchRenameDialogFragment] and [BatchRenameTvActivity].
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
        syncButtonStates(text)
        rebuildPreviews()
    }

    fun updateReplaceText(text: String) {
        _state.update { it.copy(replaceText = text) }
        rebuildPreviews()
    }

    fun updateReplaceWith(text: String) {
        _state.update { it.copy(replaceWith = text) }
        rebuildPreviews()
    }

    // ── Extension controls ───────────────────────────────────────────────────

    fun toggleCustomExtension() {
        _state.update { it.copy(useCustomExtension = !it.useCustomExtension) }
        rebuildPreviews()
    }

    fun setCustomExtension(ext: String) {
        _state.update { it.copy(customExtension = ext) }
        rebuildPreviews()
    }

    // ── Date controls ────────────────────────────────────────────────────────

    fun toggleYearOption() {
        _state.update { it.copy(hasYearToken = !it.hasYearToken) }
        rebuildPreviews()
    }

    fun toggleMonthOption() {
        _state.update { it.copy(hasMonthToken = !it.hasMonthToken) }
        rebuildPreviews()
    }

    fun toggleDayOption() {
        _state.update { it.copy(hasDayToken = !it.hasDayToken) }
        rebuildPreviews()
    }

    fun setCustomYear(year: String) {
        _state.update { it.copy(customYear = year) }
        rebuildPreviews()
    }

    fun setCustomMonth(month: String) {
        _state.update { it.copy(customMonth = month) }
        rebuildPreviews()
    }

    fun setCustomDay(day: String) {
        _state.update { it.copy(customDay = day) }
        rebuildPreviews()
    }

    // ── Case option toggles (hidden from EditText) ───────────────────────────

    fun toggleUpperOption() {
        _state.update {
            val nextUpper = !it.hasUpperToken
            it.copy(
                hasUpperToken = nextUpper,
                hasLowerToken = if (nextUpper) false else it.hasLowerToken
            )
        }
        rebuildPreviews()
    }

    fun toggleLowerOption() {
        _state.update {
            val nextLower = !it.hasLowerToken
            it.copy(
                hasLowerToken = nextLower,
                hasUpperToken = if (nextLower) false else it.hasUpperToken
            )
        }
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
        val originalPresent = currentText.contains("\$F") || currentText.contains("\$fullname") || currentText.contains("{fullname}") || currentText.contains("%F")
        val paddingPresent = currentText.contains(TOKEN_PADDING) || currentText.contains("###")

        val hasNumber = currentText.contains("#") && !currentText.contains("###")
        val hasPaddedNumber = currentText.contains("###") || currentText.contains(TOKEN_PADDING)
        val hasName = currentText.contains("\$N") || currentText.contains("\$name") || currentText.contains("{name}") || currentText.contains("%N") || currentText.contains(TOKEN_ORIGINAL)
        val hasFullName = currentText.contains("\$F") || currentText.contains("\$fullname") || currentText.contains("{fullname}") || currentText.contains("%F")
        val hasYear = currentText.contains("\$Y") || _state.value.hasYearToken
        val hasMonth = currentText.contains("\$M") || _state.value.hasMonthToken
        val hasDay = currentText.contains("\$D") || _state.value.hasDayToken

        _state.update {
            it.copy(
                useOriginal = originalPresent,
                usePadding = paddingPresent,
                hasNumberToken = hasNumber,
                hasPaddedNumberToken = hasPaddedNumber,
                hasNameToken = hasName,
                hasFullNameToken = hasFullName,
                hasYearToken = hasYear,
                hasMonthToken = hasMonth,
                hasDayToken = hasDay
            )
        }
    }

    // ── Token snap-in ─────────────────────────────────────────────────────────

    fun setPatternText(text: String) {
        val trimmed = collapseSpaces(text)
        _state.update { it.copy(patternText = trimmed) }
        syncButtonStates(trimmed)
        rebuildPreviews()
    }

    // ── Preview rebuild ───────────────────────────────────────────────────────

    private fun rebuildPreviews() {
        val s = _state.value
        val pattern = s.patternText

        val baseText = pattern
            .replace(TOKEN_ORIGINAL, "")
            .replace(TOKEN_PADDING, "")
            .trim()

        val hasTokens = s.useOriginal || s.usePadding || s.hasYearToken || s.hasMonthToken || s.hasDayToken ||
                s.hasUpperToken || s.hasLowerToken ||
                pattern.contains("#") || pattern.contains("$") ||
                pattern.contains("%") || pattern.contains("{")
        val hasReplaceText = s.replaceText.isNotEmpty()
        val hasExtOverride = s.useCustomExtension && s.customExtension.isNotBlank()
        val isRenameEnabled = baseText.isNotEmpty() || hasTokens || hasReplaceText || hasExtOverride

        val patternError = if (!isRenameEnabled && !hasTokens && !hasReplaceText && !hasExtOverride && baseText.isEmpty()) {
            "batch_rename_error_empty_pattern"
        } else {
            null
        }

        val previews = s.items.mapIndexed { index, item ->
            val counter = index + 1

            val resolvedName = BatchRenamePatternResolver.resolve(
                pattern = pattern,
                item = item,
                counter = counter,
                useOriginal = s.useOriginal,
                usePadding = s.usePadding,
                paddingLength = s.paddingLength,
                paddingStart = s.paddingStart,
                replaceText = s.replaceText,
                replaceWith = s.replaceWith,
                useCustomExtension = s.useCustomExtension,
                customExtension = s.customExtension,
                useYear = s.hasYearToken,
                useMonth = s.hasMonthToken,
                useDay = s.hasDayToken,
                customYear = s.customYear,
                customMonth = s.customMonth,
                customDay = s.customDay,
                hasUpper = s.hasUpperToken,
                hasLower = s.hasLowerToken
            )

            val fullResult = BatchRenamePatternResolver.appendExtension(
                resolvedName = resolvedName,
                item = item,
                pattern = pattern,
                useCustomExtension = s.useCustomExtension,
                customExtension = s.customExtension,
                hasUpper = s.hasUpperToken,
                hasLower = s.hasLowerToken
            )

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
                val sorted = items.sortedWith(compareByDescending<BatchRenameItem> { it.isDirectory }
                    .thenBy { items.indexOf(it) })
                return BatchRenameViewModel(sorted) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
