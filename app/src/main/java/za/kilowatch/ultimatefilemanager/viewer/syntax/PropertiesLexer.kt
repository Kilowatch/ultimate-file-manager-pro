package za.kilowatch.ultimatefilemanager.viewer.syntax

import za.kilowatch.ultimatefilemanager.viewer.syntax.SyntaxTokenType.*

/**
 * Key=value lexer for dotenv / properties-style config files.
 *
 * Used by languages with `isProperties = true` (e.g. `.env`, `.npmrc`,
 * `.pylintrc`, `.curlrc`, and Java `.properties` / `.ini` / `.cfg` / `.conf`).
 *
 * Processes line-by-line and identifies:
 * - `KEY=VALUE` / `KEY: VALUE` (optionally spaced around the separator) →
 *   [PROPERTY_KEY] for the key and [PROPERTY_VALUE] for the value
 * - Line comments starting with a `language.lineComments` prefix
 *   (`#`, and for Properties also `;` and `!`) → [COMMENT_LINE]
 * - Quoted values (`"..."` / `'...'`) → [STRING]
 * - Inline `#` comments after a value (preceded by whitespace) → [COMMENT_LINE]
 * - Lines with no `=` / `:` separator (e.g. `[SECTION]` headers, stray text)
 *   emit nothing and stay plain
 */
object PropertiesLexer {

    fun tokenize(text: String, language: LanguageDef): List<TokenSpan> {
        if (text.isEmpty()) return emptyList()

        val tokens = mutableListOf<TokenSpan>()
        val len = text.length
        var pos = 0

        while (pos < len) {
            val lineStart = pos
            val lineEnd = text.indexOf('\n', pos).let { if (it < 0) len else it }

            scanLine(text, lineStart, lineEnd, language, tokens)

            // Move past the newline
            pos = lineEnd + 1
        }

        return tokens
    }

    /**
     * Scans a single line for key/value and comment tokens.
     */
    private fun scanLine(
        text: String,
        lineStart: Int,
        lineEnd: Int,
        language: LanguageDef,
        tokens: MutableList<TokenSpan>,
    ) {
        var pos = lineStart

        // Skip leading whitespace
        while (pos < lineEnd && text[pos].isWhitespace()) pos++

        // Empty line or whitespace-only
        if (pos >= lineEnd) return

        // ── Line comment (handles # and any other language comment prefix) ──
        for (prefix in language.lineComments) {
            if (prefix.isNotEmpty() && text.regionMatches(pos, prefix, 0, prefix.length)) {
                tokens.add(TokenSpan(COMMENT_LINE, pos, lineEnd))
                return
            }
        }

        // ── Key-value pair ────────────────────────────────────────────────
        val sep = indexOfSeparator(text, pos, lineEnd)
        if (sep > pos) {
            // Key must start with a valid identifier character
            val first = text[pos]
            if (first.isLetter() || first.isDigit() || first == '_') {
                // Trim trailing whitespace from the key
                var keyEnd = sep
                while (keyEnd > pos && text[keyEnd - 1].isWhitespace()) keyEnd--
                if (keyEnd > pos) {
                    tokens.add(TokenSpan(PROPERTY_KEY, pos, keyEnd))
                }
                scanValue(text, sep + 1, lineEnd, tokens)
            }
        }
    }

    /**
     * Returns the index of the first `=` or `:` separator, or -1 if none.
     */
    private fun indexOfSeparator(text: String, start: Int, lineEnd: Int): Int {
        var i = start
        while (i < lineEnd) {
            val c = text[i]
            if (c == '=' || c == ':') return i
            i++
        }
        return -1
    }

    /**
     * Scans the value portion after a separator.
     */
    private fun scanValue(
        text: String,
        start: Int,
        lineEnd: Int,
        tokens: MutableList<TokenSpan>,
    ) {
        var pos = start

        // Skip whitespace after the separator
        while (pos < lineEnd && text[pos].isWhitespace()) pos++
        if (pos >= lineEnd) return

        val ch = text[pos]

        // ── Quoted value → STRING ─────────────────────────────────────────
        if (ch == '"' || ch == '\'') {
            val strEnd = scanQuotedString(text, pos + 1, ch, lineEnd)
            tokens.add(TokenSpan(STRING, pos, strEnd))
            // Anything after the closing quote: skip spaces, then an inline comment
            var after = strEnd
            while (after < lineEnd && text[after].isWhitespace()) after++
            if (after < lineEnd && text[after] == '#') {
                tokens.add(TokenSpan(COMMENT_LINE, after, lineEnd))
            }
            return
        }

        // ── Unquoted value → PROPERTY_VALUE (with optional inline comment) ──
        var commentStart = -1
        var v = pos
        while (v < lineEnd) {
            if (text[v] == '#' && (v == pos || text[v - 1].isWhitespace())) {
                commentStart = v
                break
            }
            v++
        }
        val valueEnd = if (commentStart >= 0) commentStart else lineEnd
        var trimmedEnd = valueEnd
        while (trimmedEnd > pos && text[trimmedEnd - 1].isWhitespace()) trimmedEnd--
        if (trimmedEnd > pos) {
            tokens.add(TokenSpan(PROPERTY_VALUE, pos, trimmedEnd))
        }
        if (commentStart >= 0) {
            tokens.add(TokenSpan(COMMENT_LINE, commentStart, lineEnd))
        }
    }

    /**
     * Scans a quoted string to the matching close quote (respects `\` escapes).
     */
    private fun scanQuotedString(text: String, start: Int, quote: Char, end: Int): Int {
        var i = start
        while (i < end) {
            if (text[i] == '\\' && i + 1 < end) {
                i += 2
                continue
            }
            if (text[i] == quote) return i + 1
            i++
        }
        return end
    }
}
