package za.kilowatch.ultimatefilemanager.viewer.syntax

/**
 * General-purpose character-by-character lexer for all C-family,
 * scripting, Python, SQL, config, and most other languages.
 *
 * ## Processing order (first match wins)
 * 1. Skip whitespace
 * 2. Line comment (`//`, `#`, `--`, `;`, `%`)
 * 3. Block comment (`/* */`, etc.)
 * 4. Multi-line string opener (""", ''', ```)
 * 5. String literal (handles `\` escapes and regex delimiters in JS)
 * 6. Numeric literal (hex `0x`, octal `0o`, binary `0b`, decimal)
 * 7. `@` annotation (when language has annotations)
 * 8. `$` variable reference (when language has variable prefix)
 * 9. `#` preprocessor at line start (C/C++)
 * 10. Identifier — matched against keyword/type/builtin/boolean/constant sets
 * 11. Operator (greedy 2-char first: `==`, `!=`, `<=`, `>=`, `&&`, `||`, `->`, `=>`)
 * 12. Punctuation
 * 13. Any other character → plain text (no span emitted)
 *
 * Every character access is bounds-checked so malformed / truncated input
 * never causes an exception — remaining text is returned as plain tokens.
 */
object CodeLexer {

    private val OPERATORS = setOf(
        "==", "!=", "<=", ">=", "&&", "||", "->", "=>",
        "++", "--", "<<", ">>", "::", "..", "**", "//",
        "+", "-", "*", "/", "%", "=", "<", ">", "!", "&",
        "|", "^", "~", "?", ":"
    )

    private val PUNCTUATION = setOf(
        '{', '}', '[', ']', '(', ')', ';', ',', '.'
    )

    /** Single-character operators.  Used for greedy 2-char lookahead. */
    private val SINGLE_OPS = setOf(
        '+', '-', '*', '/', '%', '=', '<', '>', '!', '&',
        '|', '^', '~', '?', ':'
    )

    /**
     * Tokenises [text] according to [language] configuration.
     * @return list of [TokenSpan] in positional order.
     */
    fun tokenize(text: String, language: LanguageDef): List<TokenSpan> {
        if (text.isEmpty()) return emptyList()

        val tokens = mutableListOf<TokenSpan>()
        var pos = 0
        val len = text.length
        val lineComments = language.lineComments
        val blockComments = language.blockComments
        val stringDelimiters = language.stringDelimiters
        val multiStringDels = language.multiStringDelimiters
        val keywords = language.keywords
        val types = language.types
        val builtins = language.builtins
        val booleans = language.booleanLiterals
        val constants = language.constants

        while (pos < len) {
            val ch = text[pos]

            // ── 1. Whitespace ──────────────────────────────────────────
            if (ch.isWhitespace()) {
                pos++
                continue
            }

            // ── 2. Line comments ───────────────────────────────────────
            var matched = false
            for (lc in lineComments) {
                if (text.regionMatches(pos, lc, 0, lc.length, false)) {
                    val end = text.indexOf('\n', pos + lc.length).let {
                        if (it < 0) len else it
                    }
                    tokens.add(TokenSpan(SyntaxTokenType.COMMENT_LINE, pos, end))
                    pos = end
                    matched = true
                    break
                }
            }
            if (matched) continue

            // ── 3. Block comments ──────────────────────────────────────
            for ((open, close) in blockComments) {
                if (text.regionMatches(pos, open, 0, open.length, false)) {
                    val closeIdx = text.indexOf(close, pos + open.length)
                    val end = if (closeIdx < 0) len else closeIdx + close.length
                    tokens.add(TokenSpan(SyntaxTokenType.COMMENT_BLOCK, pos, end))
                    pos = end
                    matched = true
                    break
                }
            }
            if (matched) continue

            // ── 4. Multi-line strings (""" ''') ────────────────────────
            for (ms in multiStringDels) {
                if (text.regionMatches(pos, ms, 0, ms.length, false)) {
                    val closeIdx = text.indexOf(ms, pos + ms.length)
                    val end = if (closeIdx < 0) len else closeIdx + ms.length
                    tokens.add(TokenSpan(SyntaxTokenType.STRING, pos, end))
                    pos = end
                    matched = true
                    break
                }
            }
            if (matched) continue

            // ── 5. String literals ─────────────────────────────────────
            for (delim in stringDelimiters) {
                if (delim.length == 1 && ch == delim[0]) {
                    val end = scanString(text, pos + 1, delim[0], len)
                    tokens.add(TokenSpan(SyntaxTokenType.STRING, pos, end))
                    pos = end
                    matched = true
                    break
                }
            }
            if (matched) continue

            // ── 6. Numeric literals ────────────────────────────────────
            if (ch.isDigit() || (ch == '.' && pos + 1 < len && text[pos + 1].isDigit())) {
                val end = scanNumber(text, pos, len)
                tokens.add(TokenSpan(SyntaxTokenType.NUMBER, pos, end))
                pos = end
                continue
            }

            // ── 7. @ annotations ───────────────────────────────────────
            if (ch == '@' && language.hasAnnotations) {
                val end = scanIdentifier(text, pos + 1, len)
                tokens.add(TokenSpan(SyntaxTokenType.ANNOTATION, pos, end))
                pos = end
                continue
            }

            // ── 8. $ variable ──────────────────────────────────────────
            if (ch == '$' && language.variablePrefix != null) {
                val end = scanVariable(text, pos, len)
                tokens.add(TokenSpan(SyntaxTokenType.VARIABLE, pos, end))
                pos = end
                continue
            }

            // ── 9. # preprocessor (C/C++, only at line start) ──────────
            if (ch == '#' && language.hasPreprocessor && isLineStart(text, pos)) {
                val end = text.indexOf('\n', pos + 1).let { if (it < 0) len else it }
                tokens.add(TokenSpan(SyntaxTokenType.PREPROCESSOR, pos, end))
                pos = end
                continue
            }

            // ── 10. Identifiers & keywords ─────────────────────────────
            if (ch.isLetter() || ch == '_') {
                val end = scanIdentifier(text, pos, len)
                val word = text.substring(pos, end)
                val lower = word.lowercase()

                val tokenType = when {
                    lower in booleans  -> SyntaxTokenType.BOOLEAN_LITERAL
                    lower in keywords  -> SyntaxTokenType.KEYWORD
                    lower in types     -> SyntaxTokenType.TYPE
                    lower in builtins  -> SyntaxTokenType.BUILTIN
                    lower in constants -> SyntaxTokenType.CONSTANT
                    // ALL_CAPS identifiers => constant
                    word.all { it.isUpperCase() || it == '_' || it.isDigit() }
                        && word.any { it.isUpperCase() }
                        && word.length > 1 -> SyntaxTokenType.CONSTANT
                    else -> null
                }
                if (tokenType != null) {
                    tokens.add(TokenSpan(tokenType, pos, end))
                }
                pos = end
                continue
            }

            // ── 11. Operators (greedy 2-char first) ────────────────────
            if (ch in SINGLE_OPS) {
                val two = if (pos + 1 < len) text.substring(pos, pos + 2) else ""
                val op = if (two in OPERATORS) two else ch.toString()
                if (op in OPERATORS) {
                    tokens.add(TokenSpan(SyntaxTokenType.OPERATOR, pos, pos + op.length))
                    pos += op.length
                    continue
                }
            }

            // ── 12. Punctuation ────────────────────────────────────────
            if (ch in PUNCTUATION) {
                tokens.add(TokenSpan(SyntaxTokenType.PUNCTUATION, pos, pos + 1))
                pos++
                continue
            }

            // ── 13. Plain text (advance 1) ────────────────────────────
            pos++
        }

        return tokens
    }

    // ── Private helpers ─────────────────────────────────────────────────

    /**
     * Scans a string literal, handling backslash escapes.
     * @return the index of the closing delimiter (exclusive), or [len] if unterminated.
     */
    private fun scanString(text: String, start: Int, delim: Char, len: Int): Int {
        var i = start
        while (i < len) {
            val c = text[i]
            if (c == '\\' && i + 1 < len) {
                i += 2  // skip escape sequence
                continue
            }
            if (c == delim) return i + 1
            // Handle newlines in strings (some languages allow them)
            if (c == '\n') return i
            i++
        }
        return len
    }

    /**
     * Scans a numeric literal: hex (0x…), octal (0o…), binary (0b…),
     * decimal with optional fractional part and exponent.
     */
    private fun scanNumber(text: String, start: Int, len: Int): Int {
        var i = start

        // Check for 0x, 0o, 0b prefixes
        if (i + 2 < len && text[i] == '0') {
            val prefix = text[i + 1].lowercaseChar()
            if (prefix in setOf('x', 'o', 'b')) {
                i += 2
                while (i < len && (text[i].isDigit() ||
                    (prefix == 'x' && text[i] in 'a'..'f') ||
                    (prefix == 'x' && text[i] in 'A'..'F'))) {
                    i++
                }
                return i
            }
        }

        // Decimal: integer part
        while (i < len && text[i].isDigit()) i++

        // Fractional part
        if (i < len && text[i] == '.' && i + 1 < len && text[i + 1].isDigit()) {
            i++
            while (i < len && text[i].isDigit()) i++
        }

        // Exponent
        if (i < len && text[i] in setOf('e', 'E')) {
            i++
            if (i < len && text[i] in setOf('+', '-')) i++
            while (i < len && text[i].isDigit()) i++
        }

        return i
    }

    /**
     * Scans an identifier (letter, digit, underscore).
     */
    private fun scanIdentifier(text: String, start: Int, len: Int): Int {
        var i = start
        while (i < len && (text[i].isLetterOrDigit() || text[i] == '_')) i++
        return i
    }

    /**
     * Scans a variable reference starting with `$`:
     * - `$var` (simple)
     * - `${var}` (braced)
     * - `$123` (numeric)
     */
    private fun scanVariable(text: String, start: Int, len: Int): Int {
        var i = start + 1  // skip $
        if (i >= len) return start + 1

        if (text[i] == '{') {
            // ${...} — scan to matching }
            i++
            while (i < len && text[i] != '}') i++
            return if (i < len) i + 1 else len
        }

        // $var or $123
        while (i < len && (text[i].isLetterOrDigit() || text[i] == '_')) i++
        return i
    }

    /**
     * Returns true if [pos] is at the start of a line
     * (beginning of string or preceded by `\n`).
     */
    private fun isLineStart(text: String, pos: Int): Boolean =
        pos == 0 || text[pos - 1] == '\n'
}
