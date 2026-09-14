package za.kilowatch.ultimatefilemanager.settings

import android.os.Build
import android.text.Layout
import android.text.TextUtils
import android.widget.TextView

/**
 * Manages file name presentation across list and grid layouts.
 *
 * Supports:
 * - One-shot marquee scroll animation (via [ScrollingTextHelper])
 * - Multi-line file name wrapping (MT Manager style) with zero-width break insertion
 * - Single-line truncation with ellipsis
 */
object FileNameDisplayHelper {

    private val DELIMITERS = setOf(
        '_', '-', '.', '+', '~', '=', '@', '#', '%', '&', '[', ']', '(', ')', '{', '}'
    )

    /**
     * Inserts zero-width spaces (\u200B) after common delimiter characters and within
     * long uninterrupted alphanumeric strings. This allows Android's [Layout] engine
     * to wrap long file names naturally across multiple lines at delimiter boundaries
     * without introducing visual spaces or altering the underlying string.
     */
    fun formatForWrapping(name: String): String {
        if (name.isEmpty() || name.length <= 16) return name
        val sb = StringBuilder(name.length + 16)
        var charsSinceBreak = 0
        for (i in name.indices) {
            val c = name[i]
            sb.append(c)
            charsSinceBreak++

            if (c in DELIMITERS) {
                // Avoid inserting right before a space or another delimiter, or at the very end
                if (i + 1 < name.length && name[i + 1] != ' ' && name[i + 1] !in DELIMITERS) {
                    sb.append('\u200B')
                    charsSinceBreak = 0
                }
            } else if (c == ' ') {
                charsSinceBreak = 0
            } else if (charsSinceBreak >= 16) {
                // Break long continuous token (e.g. hash checksums)
                if (i + 1 < name.length && name[i + 1] != ' ' && name[i + 1] !in DELIMITERS) {
                    sb.append('\u200B')
                    charsSinceBreak = 0
                }
            }
        }
        return sb.toString()
    }

    /**
     * Applies the configured file name display settings to [textView].
     *
     * @param textView Target TextView (e.g. txtFileName in FileViewHolder)
     * @param fileName Original file name
     * @param isTv True if running on an Android TV device
     * @param isGrid True if rendering inside a grid layout item
     */
    fun applyFileNameDisplay(
        textView: TextView,
        fileName: String,
        isTv: Boolean,
        isGrid: Boolean = false
    ) {
        val context = textView.context

        if (isTv) {
            val scrollingEnabled = ScrollingTextPreferenceManager.isEnabled(context)
            textView.maxLines = 1
            textView.text = fileName
            ScrollingTextHelper.applyScrollingText(textView, scrollingEnabled)
            return
        }

        if (isGrid) {
            ScrollingTextHelper.cancelScrolling(textView)
            textView.setHorizontallyScrolling(false)
            textView.scrollX = 0

            val mode = ScrollingTextPreferenceManager.getMode(context)
            textView.maxLines = when (mode) {
                ScrollingTextPreferenceManager.MODE_TRUNCATE -> 1
                ScrollingTextPreferenceManager.MODE_MULTILINE_3,
                ScrollingTextPreferenceManager.MODE_MULTILINE_UNLIMITED -> 3
                else -> 2
            }
            textView.ellipsize = TextUtils.TruncateAt.END

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                textView.breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
            }
            textView.text = formatForWrapping(fileName)
            return
        }

        // Mobile list view (item_file, item_file_compact)
        val mode = ScrollingTextPreferenceManager.getMode(context)
        when (mode) {
            ScrollingTextPreferenceManager.MODE_MARQUEE -> {
                textView.maxLines = 1
                textView.text = fileName
                ScrollingTextHelper.applyScrollingText(textView, enabled = true)
            }
            ScrollingTextPreferenceManager.MODE_TRUNCATE -> {
                ScrollingTextHelper.cancelScrolling(textView)
                textView.setHorizontallyScrolling(false)
                textView.scrollX = 0
                textView.maxLines = 1
                textView.ellipsize = TextUtils.TruncateAt.END
                textView.text = fileName
            }
            else -> {
                // Multi-line modes: 2 lines, 3 lines, or unlimited
                ScrollingTextHelper.cancelScrolling(textView)
                textView.setHorizontallyScrolling(false)
                textView.scrollX = 0

                textView.maxLines = when (mode) {
                    ScrollingTextPreferenceManager.MODE_MULTILINE_2 -> 2
                    ScrollingTextPreferenceManager.MODE_MULTILINE_3 -> 3
                    else -> Int.MAX_VALUE
                }
                textView.ellipsize = if (mode == ScrollingTextPreferenceManager.MODE_MULTILINE_UNLIMITED) {
                    null
                } else {
                    TextUtils.TruncateAt.END
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    textView.breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
                }
                textView.text = formatForWrapping(fileName)
            }
        }
    }
}
