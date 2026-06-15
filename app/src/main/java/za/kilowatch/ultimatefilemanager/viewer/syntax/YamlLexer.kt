package za.kilowatch.ultimatefilemanager.viewer.syntax

import za.kilowatch.ultimatefilemanager.viewer.syntax.SyntaxTokenType.*

/**
 * YAML-specific lexer.
 *
 * Processes line-by-line and identifies:
 * - `key:` at line start (optionally indented) → [PROPERTY_KEY]
 * - `- ` list markers → [OPERATOR] for the dash
 * - `#` line comments → [COMMENT_LINE]
 * - `---` / `...` document markers → [PUNCTUATION]
 * - `|` / `>` block scalar indicators → [OPERATOR]
 * - `&` anchors, `*` aliases → [VARIABLE]
 * - Value content (strings, numbers, booleans) using standard token matching
 */
object YamlLexer {

    fun tokenize(text: String, language: LanguageDef): List<TokenSpan> {
        if (text.isEmpty()) return emptyList()

        val tokens = mutableListOf<TokenSpan>()
        val len = text.length
        var pos = 0

        while (pos < len) {
            val lineStart = pos
            val lineEnd = text.indexOf('\n', pos).let { if (it < 0) len else it }

            // ── Process this line ─────────────────────────────────────
            scanLine(text, lineStart, lineEnd, len, language, tokens)

            // Move past the newline
            pos = lineEnd + 1
        }

        return tokens
    }

    /**
     * Scans a single YAML line for structural tokens.
     */
    private fun scanLine(
        text: String,
        lineStart: Int,
        lineEnd: Int,
        fullLen: Int,
        language: LanguageDef,
        tokens: MutableList<TokenSpan>,
    ) {
        var pos = lineStart

        // Skip leading spaces (track indentation)
        var indent = 0
        while (pos < lineEnd && text[pos] == ' ') {
            indent++
            pos++
        }

        // Empty line or whitespace-only
        if (pos >= lineEnd) return

        val ch = text[pos]

        // ── Document markers ─────────────────────────────────────────
        if (ch == '-' && lineEnd - pos >= 3 && text.substring(pos, pos + 3) == "---") {
            tokens.add(TokenSpan(PUNCTUATION, pos, pos + 3))
            // Scan rest of line if anything follows (unlikely)
            scanValueContent(text, pos + 3, lineEnd, language, tokens)
            return
        }
        if (ch == '.' && lineEnd - pos >= 3 && text.substring(pos, pos + 3) == "...") {
            tokens.add(TokenSpan(PUNCTUATION, pos, pos + 3))
            return
        }

        // ── Line comments ────────────────────────────────────────────
        if (ch == '#') {
            tokens.add(TokenSpan(COMMENT_LINE, pos, lineEnd))
            return
        }

        // ── List marker ──────────────────────────────────────────────
        if (ch == '-' && pos + 1 < lineEnd && text[pos + 1] == ' ') {
            tokens.add(TokenSpan(OPERATOR, pos, pos + 1))
            pos += 2
            // Scan value content after list marker
            scanValueContent(text, pos, lineEnd, language, tokens)
            return
        }

        // ── Block scalar indicator ───────────────────────────────────
        if ((ch == '|' || ch == '>') && (
                pos + 1 >= lineEnd || text[pos + 1] == ' ' ||
                text[pos + 1] == '\t' || text[pos + 1] == '\n')
        ) {
            // Scan the indicator (may include block chomping: |-, |+, >-, >+)
            val indicatorEnd = if (pos + 1 < lineEnd && text[pos + 1] in "-+123456789") {
                pos + 2
            } else {
                pos + 1
            }
            tokens.add(TokenSpan(OPERATOR, pos, indicatorEnd))
            return
        }

        // ── Key-value pair (key:) ────────────────────────────────────
        val keyEnd = scanKey(text, pos, lineEnd)
        if (keyEnd > pos) {
            tokens.add(TokenSpan(PROPERTY_KEY, pos, keyEnd))
            pos = keyEnd

            // Skip whitespace after key:
            while (pos < lineEnd && text[pos] == ' ') pos++

            // Check for # comment after value
            if (pos < lineEnd && text[pos] == '#') {
                tokens.add(TokenSpan(COMMENT_LINE, pos, lineEnd))
                return
            }

            // Scan value
            if (pos < lineEnd) {
                scanValueContent(text, pos, lineEnd, language, tokens)
            }
            return
        }

        // ── Anchor / alias ───────────────────────────────────────────
        if (ch == '&') {
            val end = scanGenericIdentifier(text, pos + 1, lineEnd)
            tokens.add(TokenSpan(VARIABLE, pos, end))
            return
        }
        if (ch == '*') {
            val end = scanGenericIdentifier(text, pos + 1, lineEnd)
            tokens.add(TokenSpan(VARIABLE, pos, end))
            return
        }

        // ── If none of the above, scan as value content ──────────────
        scanValueContent(text, pos, lineEnd, language, tokens)
    }

    /**
     * Scans value content (strings, numbers, booleans, inline structures).
     * Delegates most work to [SyntaxTokenType] matching.
     */
    private fun scanValueContent(
        text: String,
        start: Int,
        end: Int,
        language: LanguageDef,
        tokens: MutableList<TokenSpan>,
    ) {
        var pos = start
        val booleans = language.booleanLiterals

        while (pos < end) {
            val ch = text[pos]

            // Skip whitespace
            if (ch.isWhitespace()) { pos++; continue }

            // Inline comment
            if (ch == '#') {
                tokens.add(TokenSpan(COMMENT_LINE, pos, end))
                return
            }

            // String with quotes
            if (ch == '"' || ch == '\'') {
                val strEnd = scanQuotedString(text, pos + 1, ch, end)
                tokens.add(TokenSpan(STRING, pos, strEnd))
                pos = strEnd
                continue
            }

            // Number
            if (ch.isDigit() || (ch == '-' && pos + 1 < end && text[pos + 1].isDigit()) ||
                (ch == '.' && pos + 1 < end && text[pos + 1].isDigit())
            ) {
                val numEnd = scanNumberLiteral(text, pos, end)
                tokens.add(TokenSpan(NUMBER, pos, numEnd))
                pos = numEnd
                continue
            }

            // Boolean/null
            if (ch.isLetter() || ch == '_') {
                val identEnd = scanGenericIdentifier(text, pos, end)
                val word = text.substring(pos, identEnd)
                if (word.lowercase() in booleans) {
                    tokens.add(TokenSpan(BOOLEAN_LITERAL, pos, identEnd))
                } else {
                    // If starts with uppercase, it's likely a string (YAML strings are unquoted)
                    // We don't emit a span for unquoted plain string values
                }
                pos = identEnd
                continue
            }

            // Inline list marker
            if (ch == '-' && pos + 1 < end && text[pos + 1] == ' ') {
                tokens.add(TokenSpan(OPERATOR, pos, pos + 1))
                pos += 2
                continue
            }

            // Anchor/alias inline
            if (ch == '&' || ch == '*') {
                val refEnd = scanGenericIdentifier(text, pos + 1, end)
                tokens.add(TokenSpan(VARIABLE, pos, refEnd))
                pos = refEnd
                continue
            }

            // Punctuation
            if (ch in "{}[],:") {
                tokens.add(TokenSpan(PUNCTUATION, pos, pos + 1))
                pos++
                continue
            }

            // Skip any other character (plain text)
            pos++
        }
    }

    // ── Private helpers ─────────────────────────────────────────────────

    /**
     * Scans a YAML key ending with `:`.
     * Valid keys match `[a-zA-Z_][a-zA-Z0-9_-]*` followed by `:`.
     * Returns the position after the `:`, or [start] if no key found.
     */
    private fun scanKey(text: String, start: Int, lineEnd: Int): Int {
        var i = start
        if (i >= lineEnd) return start
        if (!text[i].isLetter() && text[i] != '_') return start

        i++
        while (i < lineEnd && (text[i].isLetterOrDigit() || text[i] in "_-")) i++

        // Must be followed by ':'
        if (i < lineEnd && text[i] == ':') {
            // Check it's not ::// :: or similar operator
            i++ // include the colon
            return i
        }

        return start
    }

    /**
     * Scans a generic identifier (word).
     */
    private fun scanGenericIdentifier(text: String, start: Int, end: Int): Int {
        var i = start
        while (i < end && (text[i].isLetterOrDigit() || text[i] == '_')) i++
        return i
    }

    /**
     * Scans a quoted string to the matching close quote.
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

    /**
     * Scans a numeric literal (for YAML values).
     */
    private fun scanNumberLiteral(text: String, start: Int, end: Int): Int {
        var i = start
        if (i < end && text[i] == '-') i++

        // Hex, octal, binary with 0x/0o/0b
        if (i + 2 < end && text[i] == '0') {
            val prefix = text[i + 1].lowercaseChar()
            if (prefix in setOf('x', 'o', 'b')) {
                i += 2
                while (i < end && (text[i].isDigit() ||
                    (prefix == 'x' && text[i] in 'a'..'f') ||
                    (prefix == 'x' && text[i] in 'A'..'F'))) i++
                return i
            }
        }

        // Decimal digits
        while (i < end && text[i].isDigit()) i++
        // Fractional
        if (i < end && text[i] == '.' && i + 1 < end && text[i + 1].isDigit()) {
            i++
            while (i < end && text[i].isDigit()) i++
        }
        // Exponent
        if (i < end && (text[i] == 'e' || text[i] == 'E')) {
            i++
            if (i < end && (text[i] == '+' || text[i] == '-')) i++
            while (i < end && text[i].isDigit()) i++
        }

        return i
    }
}
