package za.kilowatch.ultimatefilemanager.viewer.syntax

import za.kilowatch.ultimatefilemanager.viewer.syntax.SyntaxTokenType.*

/**
 * State-machine lexer for XML, HTML, and XHTML documents.
 *
 * States:
 * - `IN_TEXT`  — free text between tags
 * - `IN_TAG`   — inside `<…>`, scanning tag name
 * - `IN_ATTR`  — scanning attribute name (whitespace before `=` or `>`)
 * - `IN_ATTR_VALUE` — scanning quoted attribute value
 *
 * Special constructs handled:
 * - `<!-- … -->`  → COMMENT_BLOCK
 * - `<!DOCTYPE … >` → TAG_NAME
 * - `<?xml … ?>`  → TAG_NAME (processing instructions)
 * - `<![CDATA[ … ]]>` → STRING
 * - Self-closing `<tag/>`
 */
object MarkupLexer {

    private enum class State { IN_TEXT, IN_TAG, IN_ATTR, IN_ATTR_VALUE }

    /**
     * Tokenises an XML/HTML document.
     * Content between tags falls through as plain text (no spans emitted).
     */
    fun tokenize(text: String, language: LanguageDef): List<TokenSpan> {
        if (text.isEmpty()) return emptyList()

        val tokens = mutableListOf<TokenSpan>()
        var pos = 0
        val len = text.length
        var state = State.IN_TEXT

        while (pos < len) {
            when (state) {
                State.IN_TEXT -> {
                    // Look for '<' or special constructs
                    if (text[pos] == '<') {
                        // Check for HTML comment <!-- -->
                        if (text.regionMatches(pos, "<!--", 0, 4, false)) {
                            val end = findTagClose(text, pos + 4, "-->", len)
                            tokens.add(TokenSpan(SyntaxTokenType.COMMENT_BLOCK, pos, end))
                            pos = end
                            continue
                        }

                        // Check for CDATA <![CDATA[ … ]]>
                        if (text.regionMatches(pos, "<![CDATA[", 0, 9, false)) {
                            val end = findTagClose(text, pos + 9, "]]>", len)
                            tokens.add(TokenSpan(SyntaxTokenType.STRING, pos, end))
                            pos = end
                            continue
                        }

                        // Check for processing instruction <?xml … ?>
                        if (pos + 1 < len && text[pos + 1] == '?') {
                            // Scan to ?>
                            var i = pos + 2
                            while (i < len) {
                                if (text[i] == '?' && i + 1 < len && text[i + 1] == '>') break
                                i++
                            }
                            val end = if (i < len) i + 2 else len
                            tokens.add(TokenSpan(SyntaxTokenType.TAG_NAME, pos, end))
                            pos = end
                            continue
                        }

                        // Check for DOCTYPE or other <! … >
                        if (pos + 1 < len && text[pos + 1] == '!') {
                            val gt = text.indexOf('>', pos + 2)
                            val end = if (gt < 0) len else gt + 1
                            tokens.add(TokenSpan(SyntaxTokenType.TAG_NAME, pos, end))
                            pos = end
                            continue
                        }

                        // Regular tag: <tag or </tag
                        state = State.IN_TAG
                        // Emit the '<' as punctuation (or plain text — but tag starts here)
                        // The tag name follows immediately
                        val tagStart = pos
                        pos++ // skip '<'

                        // Skip '/' for closing tags — we'll just emit the name
                        if (pos < len && text[pos] == '/') {
                            // Still part of the tag — include the slash as plain
                            // The tag name starts after the slash
                            pos++
                        }

                        // Scan tag name
                        val nameEnd = scanTagName(text, pos, len)
                        if (nameEnd > pos) {
                            tokens.add(TokenSpan(SyntaxTokenType.TAG_NAME, tagStart, nameEnd))
                        }
                        pos = nameEnd
                        continue
                    }

                    // Not a tag — advance one character (plain text)
                    pos++
                }

                State.IN_TAG -> {
                    // We already emitted the tag name. Now look for attributes or '>'
                    if (pos >= len) break

                    val c = text[pos]

                    if (c == '>') {
                        tokens.add(TokenSpan(SyntaxTokenType.OPERATOR, pos, pos + 1))
                        pos++
                        state = State.IN_TEXT
                        continue
                    }

                    if (c == '/') {
                        // Self-closing <tag/>
                        if (pos + 1 < len && text[pos + 1] == '>') {
                            pos += 2
                            tokens.add(TokenSpan(SyntaxTokenType.OPERATOR, pos - 2, pos))
                            state = State.IN_TEXT
                            continue
                        }
                        pos++
                        continue
                    }

                    if (c.isWhitespace()) {
                        pos++
                        continue
                    }

                    // Must be an attribute name
                    val attrStart = pos
                    val attrEnd = scanAttrName(text, pos, len)
                    if (attrEnd > attrStart) {
                        tokens.add(TokenSpan(SyntaxTokenType.TAG_ATTR, attrStart, attrEnd))
                        pos = attrEnd
                        state = State.IN_ATTR
                        continue
                    }

                    pos++
                }

                State.IN_ATTR -> {
                    if (pos >= len) break

                    val c = text[pos]

                    if (c == '>') {
                        tokens.add(TokenSpan(SyntaxTokenType.OPERATOR, pos, pos + 1))
                        pos++
                        state = State.IN_TEXT
                        continue
                    }

                    if (c == '/') {
                        pos++
                        continue
                    }

                    if (c.isWhitespace()) {
                        pos++
                        continue
                    }

                    // Check for =
                    if (c == '=') {
                        pos++
                        continue
                    }

                    // Check for quoted attribute value
                    if (c == '"' || c == '\'') {
                        val valEnd = scanAttrValue(text, pos, c, len)
                        if (valEnd > pos) {
                            tokens.add(TokenSpan(SyntaxTokenType.ATTR_VALUE, pos, valEnd))
                        }
                        pos = valEnd
                        state = State.IN_TAG
                        continue
                    }

                    // Unquoted attribute value (rare but valid in HTML)
                    // Treat as attribute name — push back to tag scanning
                    state = State.IN_TAG
                }

                State.IN_ATTR_VALUE -> {
                    // This state is handled inline in IN_ATTR above
                    state = State.IN_TAG
                }
            }
        }

        return tokens
    }

    // ── Private helpers ─────────────────────────────────────────────────

    /**
     * Scans a tag name: letters, digits, `:`, `-`, `.`
     */
    private fun scanTagName(text: String, start: Int, len: Int): Int {
        var i = start
        while (i < len && (text[i].isLetterOrDigit() || text[i] in ":-.")) i++
        return i
    }

    /**
     * Scans an attribute name: letters, digits, `:`, `-`, `_`
     */
    private fun scanAttrName(text: String, start: Int, len: Int): Int {
        var i = start
        while (i < len && (text[i].isLetterOrDigit() || text[i] in ":_-")) i++
        return i
    }

    /**
     * Scans a quoted attribute value from the opening quote.
     * Returns the index after the closing quote.
     */
    private fun scanAttrValue(text: String, start: Int, quote: Char, len: Int): Int {
        var i = start + 1 // skip opening quote
        while (i < len) {
            if (text[i] == '\\' && i + 1 < len) {
                i += 2
                continue
            }
            if (text[i] == quote) return i + 1
            i++
        }
        return len
    }

    /**
     * Finds the closing marker for a special construct (comment, CDATA).
     * @return index right after [close], or [len] if not found.
     */
    private fun findTagClose(text: String, start: Int, close: String, len: Int): Int {
        val idx = text.indexOf(close, start)
        return if (idx < 0) len else idx + close.length
    }
}
