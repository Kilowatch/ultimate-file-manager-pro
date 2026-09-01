package za.kilowatch.ultimatefilemanager.viewer

import android.content.Context
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import za.kilowatch.ultimatefilemanager.R

/**
 * Callback interface implemented by viewer activities to handle
 * content-specific searching, highlighting, and scrolling.
 */
interface SearchHost<T> {
    /** Perform a case-insensitive substring search and return matching positions. */
    fun findMatches(query: String): List<T>

    /** Apply highlights: all matches in yellow, the match at [currentIndex] in light blue. */
    fun highlightMatches(matches: List<T>, currentIndex: Int)

    /** Remove all search-related highlights. */
    fun clearHighlights()

    /** Scroll the content so the match at [index] in [matches] is visible. */
    fun scrollToMatch(matches: List<T>, index: Int)

    /** Context for string resources / system services. */
    fun getContext(): Context
}

/**
 * A generic helper that manages the in-document search lifecycle:
 * toggling the search bar, executing a search, tracking match positions,
 * navigating up/down with wrapping, updating the match count label,
 * and toggling the search icon tint.
 *
 * @param T the type used to represent a match position — each viewer
 *          chooses its own representation (e.g. IntRange, Pair<Int,Int>).
 */
class DocumentSearchHelper<T>(
    private val host: SearchHost<T>,
    private val searchInput: EditText,
    private val searchBarLayout: View,
    private val matchCountLabel: TextView,
    private val btnUp: View,
    private val btnDown: View,
    private val btnClose: View,
    private val searchIconView: ImageView,
    private val isTv: Boolean
) {
    private var matches: List<T> = emptyList()
    private var currentIndex: Int = -1
    private var _query: String = ""
    private val defaultIconTint = searchIconView.imageTintList

    /** Whether the search bar is currently open with an active query. */
    val isActive: Boolean
        get() = _query.isNotEmpty() && searchBarLayout.visibility == View.VISIBLE

    /** The current search query text. */
    val currentQuery: String
        get() = _query

    init {
        // Wire up the EditText to trigger search on Enter / IME action
        searchInput.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                executeSearch(searchInput.text.toString())
                true
            } else false
        }

        // Wire up navigation and close buttons
        btnUp.setOnClickListener { navigateUp() }
        btnDown.setOnClickListener { navigateDown() }
        btnClose.setOnClickListener { close() }
    }

    /** Toggle the search bar open/closed. */
    fun toggle() {
        if (searchBarLayout.visibility == View.VISIBLE) {
            close()
        } else {
            open()
        }
    }

    private fun open() {
        searchBarLayout.visibility = View.VISIBLE
        searchIconView.imageTintList = ContextCompat.getColorStateList(
            host.getContext(), R.color.ufm_granted
        )

        searchInput.requestFocus()
        val imm = host.getContext()
            .getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)

        // Restore previous query if one exists
        if (_query.isNotEmpty()) {
            reRunSearch()
        }
    }

    /** Close the search bar, clear state, and remove highlights. */
    fun close() {
        searchBarLayout.visibility = View.GONE
        searchIconView.imageTintList = defaultIconTint

        searchInput.setText("")
        val imm = host.getContext()
            .getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(searchInput.windowToken, 0)

        _query = ""
        matches = emptyList()
        currentIndex = -1
        matchCountLabel.text = host.getContext().getString(R.string.search_match_zero)
        host.clearHighlights()
    }

    /** Execute a new search with the given [query]. */
    fun executeSearch(query: String) {
        _query = query
        if (query.isBlank()) {
            matches = emptyList()
            currentIndex = -1
            matchCountLabel.text = host.getContext().getString(R.string.search_match_zero)
            updateArrowStates()
            host.clearHighlights()
            return
        }

        matches = host.findMatches(query)
        currentIndex = if (matches.isNotEmpty()) 0 else -1
        updateCountLabel()
        updateArrowStates()
        host.clearHighlights()
        host.highlightMatches(matches, currentIndex)
        if (currentIndex >= 0) {
            host.scrollToMatch(matches, currentIndex)
        }
    }

    /** Navigate to the previous match (wraps around). */
    fun navigateUp() {
        if (matches.isEmpty()) return
        currentIndex = if (currentIndex <= 0) matches.size - 1 else currentIndex - 1
        updateCountLabel()
        host.highlightMatches(matches, currentIndex)
        host.scrollToMatch(matches, currentIndex)
    }

    /** Navigate to the next match (wraps around). */
    fun navigateDown() {
        if (matches.isEmpty()) return
        currentIndex = if (currentIndex >= matches.size - 1) 0 else currentIndex + 1
        updateCountLabel()
        host.highlightMatches(matches, currentIndex)
        host.scrollToMatch(matches, currentIndex)
    }

    /** Clear search state without closing the bar (e.g. on page change). */
    fun reset() {
        matches = emptyList()
        currentIndex = -1
        _query = ""
        matchCountLabel.text = host.getContext().getString(R.string.search_match_zero)
        searchInput.setText("")
        host.clearHighlights()
    }

    /** Re-run the current query (e.g., after page change or sheet switch). */
    fun reRunSearch() {
        if (_query.isNotEmpty()) {
            executeSearch(_query)
        }
    }

    /** Restore state after a configuration change. */
    fun restoreState(query: String, index: Int) {
        _query = query
        if (query.isNotEmpty()) {
            matches = host.findMatches(query)
            currentIndex = if (index in matches.indices) index else 0
            updateCountLabel()
            updateArrowStates()
            host.highlightMatches(matches, currentIndex)
        }
    }

    // ── Private helpers ───────────────────────────────────────────────

    private fun updateCountLabel() {
        matchCountLabel.text = if (matches.isEmpty()) {
            host.getContext().getString(R.string.search_match_zero)
        } else {
            host.getContext().getString(
                R.string.search_match_count,
                currentIndex + 1,
                matches.size
            )
        }
    }

    private fun updateArrowStates() {
        val hasMatches = matches.isNotEmpty()
        btnUp.alpha = if (hasMatches) 1f else 0.3f
        btnDown.alpha = if (hasMatches) 1f else 0.3f
    }
}
