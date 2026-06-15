package za.kilowatch.ultimatefilemanager.viewer.syntax

/**
 * A single highlighted token produced by a lexer.
 *
 * @property type  The kind of token (determines the span colour).
 * @property start Character index where the token begins (inclusive).
 * @property end   Character index where the token ends (exclusive).
 */
data class TokenSpan(
    val type: SyntaxTokenType,
    val start: Int,
    val end: Int
)
