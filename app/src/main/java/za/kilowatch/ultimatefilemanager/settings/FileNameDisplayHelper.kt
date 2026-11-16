package za.kilowatch.ultimatefilemanager.settings

import android.os.Build
import android.text.Layout
import android.text.TextUtils
import android.widget.TextView
import za.kilowatch.ultimatefilemanager.R

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

    private val COMPOUND_EXTENSIONS = listOf(
        ".tar.gz", ".tar.bz2", ".tar.xz", ".tar.zst", ".tar.br", ".tar.lz4", ".user.js"
    )

    private val truncationCache = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
            return size > 256
        }
    }

    /**
     * Truncates a filename in the middle, preserving both the beginning prefix and the
     * discriminating suffix (at least [keepSuffixChars] characters of the base name) plus
     * the file extension (e.g. `prefix…suffix.ext`).
     */
    fun truncateMiddle(
        fileName: String,
        paint: android.graphics.Paint,
        availableWidth: Float,
        keepSuffixChars: Int = 4
    ): String {
        if (fileName.isEmpty() || availableWidth <= 0f) return fileName
        if (paint.measureText(fileName) <= availableWidth) return fileName

        val cacheKey = "$fileName|${availableWidth.toInt()}|$keepSuffixChars"
        synchronized(truncationCache) {
            truncationCache[cacheKey]?.let { return it }
        }

        // 1. Separate baseName and extension (respecting compound extensions like .tar.gz)
        val lowerName = fileName.lowercase()
        val compoundExt = COMPOUND_EXTENSIONS.firstOrNull { lowerName.endsWith(it) }

        val (baseName, extension) = when {
            compoundExt != null -> {
                val splitIdx = fileName.length - compoundExt.length
                Pair(fileName.substring(0, splitIdx), fileName.substring(splitIdx))
            }
            fileName.lastIndexOf('.') > 0 -> {
                val dotIdx = fileName.lastIndexOf('.')
                Pair(fileName.substring(0, dotIdx), fileName.substring(dotIdx))
            }
            else -> Pair(fileName, "")
        }

        val ellipsis = "…"

        // 2. Base name is very short (shorter than or equal to suffix reservation)
        if (baseName.length <= keepSuffixChars) {
            val suffix = baseName + extension
            return if (paint.measureText(ellipsis + suffix) <= availableWidth) {
                ellipsis + suffix
            } else {
                fileName
            }
        }

        // 3. Suffix to preserve: last N characters of base name + full extension
        val suffix = baseName.takeLast(keepSuffixChars) + extension
        val reservedWidth = paint.measureText(ellipsis + suffix)
        val remainingWidth = availableWidth - reservedWidth

        if (remainingWidth <= 0f) {
            return if (paint.measureText(ellipsis + extension) <= availableWidth) {
                ellipsis + extension
            } else {
                fileName
            }
        }

        // 4. Binary search for maximum prefix that fits into remainingWidth
        val maxPrefixLen = baseName.length - keepSuffixChars
        var low = 1
        var high = maxPrefixLen
        var best = 0

        while (low <= high) {
            val mid = (low + high) ushr 1
            val testPrefix = baseName.substring(0, mid)
            if (paint.measureText(testPrefix) <= remainingWidth) {
                best = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }

        val result = if (best > 0) {
            "${baseName.substring(0, best)}$ellipsis$suffix"
        } else {
            "$ellipsis$suffix"
        }

        synchronized(truncationCache) {
            truncationCache[cacheKey] = result
        }
        return result
    }

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
     * @param isSelected True if the item is selected in multi-select mode
     * @param isFocused True if the item currently has input focus (TV D-pad)
     */
    fun applyFileNameDisplay(
        textView: TextView,
        fileName: String,
        isTv: Boolean,
        isGrid: Boolean = false,
        isSelected: Boolean = false,
        isFocused: Boolean = false
    ) {
        val context = textView.context
        val mode = ScrollingTextPreferenceManager.getMode(context)

        if (isTv) {
            when (mode) {
                ScrollingTextPreferenceManager.MODE_MARQUEE -> {
                    val scope = ScrollingTextPreferenceManager.getScrollScope(context)
                    val scrollingEnabled = if (scope == ScrollingTextPreferenceManager.SCOPE_FOCUSED) {
                        isFocused
                    } else {
                        ScrollingTextPreferenceManager.isEnabled(context)
                    }
                    textView.maxLines = 1
                    textView.text = fileName
                    ScrollingTextHelper.applyScrollingText(textView, scrollingEnabled)
                }
                ScrollingTextPreferenceManager.MODE_TRUNCATE_MIDDLE -> {
                    ScrollingTextHelper.cancelScrolling(textView)
                    textView.setHorizontallyScrolling(false)
                    textView.scrollX = 0
                    textView.maxLines = 1
                    textView.ellipsize = TextUtils.TruncateAt.MIDDLE
                    applyMiddleTruncationText(textView, fileName)
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
                    // Multi-line on TV
                    ScrollingTextHelper.cancelScrolling(textView)
                    textView.setHorizontallyScrolling(false)
                    textView.scrollX = 0
                    textView.maxLines = when (mode) {
                        ScrollingTextPreferenceManager.MODE_MULTILINE_2 -> 2
                        ScrollingTextPreferenceManager.MODE_MULTILINE_3 -> 3
                        else -> 3
                    }
                    textView.ellipsize = TextUtils.TruncateAt.END
                    textView.text = formatForWrapping(fileName)
                }
            }
            return
        }

        if (isGrid) {
            ScrollingTextHelper.cancelScrolling(textView)
            textView.setHorizontallyScrolling(false)
            textView.scrollX = 0
            textView.setOnTouchListener(null)

            textView.maxLines = when (mode) {
                ScrollingTextPreferenceManager.MODE_TRUNCATE,
                ScrollingTextPreferenceManager.MODE_TRUNCATE_MIDDLE -> 1
                ScrollingTextPreferenceManager.MODE_MULTILINE_3,
                ScrollingTextPreferenceManager.MODE_MULTILINE_UNLIMITED -> 3
                else -> 2
            }
            textView.ellipsize = if (mode == ScrollingTextPreferenceManager.MODE_TRUNCATE_MIDDLE) {
                TextUtils.TruncateAt.MIDDLE
            } else {
                TextUtils.TruncateAt.END
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                textView.breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
            }
            textView.text = formatForWrapping(fileName)
            return
        }

        // Mobile list view (item_file, item_file_compact)
        when (mode) {
            ScrollingTextPreferenceManager.MODE_MARQUEE -> {
                val scope = ScrollingTextPreferenceManager.getScrollScope(context)
                val shouldScroll = if (scope == ScrollingTextPreferenceManager.SCOPE_FOCUSED) {
                    isSelected
                } else {
                    true
                }
                textView.maxLines = 1
                textView.text = fileName
                ScrollingTextHelper.applyScrollingText(textView, enabled = shouldScroll)
                ScrollingTextHelper.attachTouchScrubbing(textView, fileName)
            }
            ScrollingTextPreferenceManager.MODE_TRUNCATE_MIDDLE -> {
                ScrollingTextHelper.cancelScrolling(textView)
                textView.setHorizontallyScrolling(false)
                textView.scrollX = 0
                textView.maxLines = 1
                textView.ellipsize = TextUtils.TruncateAt.MIDDLE
                applyMiddleTruncationText(textView, fileName)
                ScrollingTextHelper.attachTouchScrubbing(textView, fileName)
            }
            ScrollingTextPreferenceManager.MODE_TRUNCATE -> {
                ScrollingTextHelper.cancelScrolling(textView)
                textView.setHorizontallyScrolling(false)
                textView.scrollX = 0
                textView.maxLines = 1
                textView.ellipsize = TextUtils.TruncateAt.END
                textView.text = fileName
                ScrollingTextHelper.attachTouchScrubbing(textView, fileName)
            }
            else -> {
                // Multi-line modes: 2 lines, 3 lines, or unlimited
                ScrollingTextHelper.cancelScrolling(textView)
                textView.setHorizontallyScrolling(false)
                textView.scrollX = 0
                textView.setOnTouchListener(null)

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

    private fun applyMiddleTruncationText(textView: TextView, fileName: String) {
        textView.setTag(R.id.txtFileName, fileName)
        val viewWidth = textView.width - textView.paddingLeft - textView.paddingRight
        if (viewWidth > 0) {
            textView.text = truncateMiddle(fileName, textView.paint, viewWidth.toFloat())
        } else {
            textView.text = fileName
            textView.post {
                if (textView.isAttachedToWindow && textView.getTag(R.id.txtFileName) == fileName && textView.width > 0) {
                    val w = textView.width - textView.paddingLeft - textView.paddingRight
                    if (w > 0) {
                        textView.text = truncateMiddle(fileName, textView.paint, w.toFloat())
                    }
                }
            }
        }
    }
}
