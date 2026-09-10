package za.kilowatch.ultimatefilemanager.viewer.syntax

import android.content.Context
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Facade that ties the detection, lexing, and span-application pipeline together.
 *
 * Usage:
 * ```kotlin
 * val spannable = SyntaxHighlightEngine.highlight(code, language, context)
 * textView.text = spannable
 * ```
 *
 * For lightweight in-place span updates on an existing [Editable] (e.g. during
 * EditText editing) use [applyHighlight]:
 * ```kotlin
 * SyntaxHighlightEngine.applyHighlight(editable, language, context)
 * ```
 *
 * The optional `remap` parameter selects the Colorblind Mode syntax palette
 * (FR-12). It defaults to `false`, so a caller that says nothing gets today's
 * colours — and it is a **parameter on every entry point rather than a second
 * method**, because the read-only viewer and the editor's edit mode call the same
 * methods; only the caller knows which mode it is in.
 */
object SyntaxHighlightEngine {

    /**
     * Syntax-highlights [text] synchronously into a new [SpannableString].
     *
     * @param text     The raw source code to highlight.
     * @param language The language definition (from [LanguageRegistry.detect]).
     * @param context  Android context for colour resolution.
     * @param remap    `true` to use the Colorblind Mode syntax palette (FR-12).
     *   **Pass `true` from the read-only viewer and `false` from edit mode.** The
     *   caller must decide: entering edit mode calls this same method, so which
     *   method ran cannot distinguish the two. While the mode is Off the flag has
     *   no effect — see [SyntaxColorScheme.getColors].
     * @return A [SpannableString] with [ForegroundColorSpan]s applied.
     */
    fun highlight(
        text: String,
        language: LanguageDef,
        context: Context,
        remap: Boolean = false
    ): SpannableString {
        val spans = tokenize(text, language)
        return applySpans(text, spans, context, remap)
    }

    /**
     * Syntax-highlights [text] on [Dispatchers.Default].
     * Use this from the main thread to avoid jank on large files.
     */
    suspend fun highlightAsync(
        text: String,
        language: LanguageDef,
        context: Context,
        remap: Boolean = false
    ): SpannableString = withContext(Dispatchers.Default) {
        highlight(text, language, context, remap)
    }

    /**
     * Applies syntax-highlighting spans **in-place** on [editable].
     *
     * This is the preferred method for edit-mode re-highlighting because it
     * avoids calling [android.widget.EditText.setText] — the underlying
     * [Editable] stays the same, cursor position and IME state are preserved,
     * and no full layout pass is triggered.
     *
     * Call from the **main thread** only (spans must be mutated on the UI thread).
     *
     * @param editable The current text buffer (e.g. `editText.text`).
     * @param language The language definition.
     * @param context  Android context for colour resolution.
     * @param remap    `true` to use the Colorblind Mode syntax palette. Edit mode
     *   passes `false` (FR-12) — **that is the whole reason this is a parameter
     *   and not a different method**: the contract above is about *how* spans are
     *   applied, and it must hold identically for either palette. Adding a second
     *   code path for the colour would put the no-`setText` guarantee at risk on
     *   the one path where it matters most.
     */
    fun applyHighlight(
        editable: Editable,
        language: LanguageDef,
        context: Context,
        remap: Boolean = false
    ) {
        // 1. Strip all existing ForegroundColorSpans
        val existing = editable.getSpans(0, editable.length, ForegroundColorSpan::class.java)
        for (span in existing) {
            editable.removeSpan(span)
        }

        // 2. Tokenize and apply new spans
        val text = editable.toString()
        val spans = tokenize(text, language)
        val colors = SyntaxColorScheme.getColors(context, remap)

        for (span in spans) {
            val color = colors[span.type] ?: continue
            val start = span.start.coerceIn(0, text.length)
            val end = span.end.coerceIn(start, text.length)
            if (start >= end) continue

            editable.setSpan(
                ForegroundColorSpan(color),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    /**
     * Fast pre-check: returns true if [extension] is a recognised
     * code-file extension that supports syntax highlighting.
     */
    fun isHighlightable(extension: String): Boolean =
        LanguageRegistry.isCodeFile(extension)

    // ── Private helpers ─────────────────────────────────────────────────

    /**
     * Selects the correct lexer and runs tokenization.
     */
    internal fun tokenize(text: String, language: LanguageDef): List<TokenSpan> {
        return when {
            language.isYaml     -> YamlLexer.tokenize(text, language)
            language.isMarkup   -> MarkupLexer.tokenize(text, language)
            language.isDotenv   -> PropertiesLexer.tokenize(text, language)
            else                -> CodeLexer.tokenize(text, language)
        }
    }

    /**
     * Builds a [SpannableString] and applies [ForegroundColorSpan]s.
     */
    private fun applySpans(
        text: String,
        spans: List<TokenSpan>,
        context: Context,
        remap: Boolean
    ): SpannableString {
        val spannable = SpannableString(text)
        val colors = SyntaxColorScheme.getColors(context, remap)

        for (span in spans) {
            val color = colors[span.type] ?: continue
            // Bounds check: span must be within the text
            val start = span.start.coerceIn(0, text.length)
            val end = span.end.coerceIn(start, text.length)
            if (start >= end) continue

            spannable.setSpan(
                ForegroundColorSpan(color),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        return spannable
    }
}
