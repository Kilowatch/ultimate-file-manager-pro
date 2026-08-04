package za.kilowatch.ultimatefilemanager.viewer.syntax

import android.content.Context
import androidx.core.content.ContextCompat
import za.kilowatch.ultimatefilemanager.R
import java.lang.ref.SoftReference

/**
 * Resolves [SyntaxTokenType] to Android colour integers at runtime.
 *
 * Colours are resolved once per [Context] and cached via a [SoftReference]
 * so they survive configuration changes without re-allocation while still
 * allowing GC under memory pressure.
 *
 * OPERATOR and PUNCTUATION map to the theme's primary text colour
 * (`android:textColorPrimary`) so they blend naturally into the text
 * rather than drawing attention.
 *
 * COMMENT_LINE and COMMENT_BLOCK share the same colour (`syntax_comment`).
 * STRING and STRING_ESCAPE share the same colour (`syntax_string`).
 */
object SyntaxColorScheme {

    private var cache: SoftReference<Map<SyntaxTokenType, Int>>? = null

    /**
     * Returns the colour map for [context].  The result is cached and
     * returned on subsequent calls for the same context instance.
     */
    fun getColors(context: Context): Map<SyntaxTokenType, Int> {
        cache?.get()?.let { return it }

        val primaryText = ContextCompat.getColor(context, android.R.color.primary_text_dark)
        // Actually use the theme's text colour via a resolved attribute
        val ta = context.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
        val textColor = ta.getColor(0, primaryText)
        ta.recycle()

        val map = buildMap<SyntaxTokenType, Int> {
            put(SyntaxTokenType.KEYWORD,          ContextCompat.getColor(context, R.color.syntax_keyword))
            put(SyntaxTokenType.STRING,           ContextCompat.getColor(context, R.color.syntax_string))
            put(SyntaxTokenType.STRING_ESCAPE,    ContextCompat.getColor(context, R.color.syntax_string))
            put(SyntaxTokenType.COMMENT_LINE,     ContextCompat.getColor(context, R.color.syntax_comment))
            put(SyntaxTokenType.COMMENT_BLOCK,    ContextCompat.getColor(context, R.color.syntax_comment))
            put(SyntaxTokenType.NUMBER,           ContextCompat.getColor(context, R.color.syntax_number))
            put(SyntaxTokenType.TYPE,             ContextCompat.getColor(context, R.color.syntax_type))
            put(SyntaxTokenType.ANNOTATION,       ContextCompat.getColor(context, R.color.syntax_annotation))
            put(SyntaxTokenType.OPERATOR,         textColor)
            put(SyntaxTokenType.PUNCTUATION,      textColor)
            put(SyntaxTokenType.TAG_NAME,         ContextCompat.getColor(context, R.color.syntax_tag_name))
            put(SyntaxTokenType.TAG_ATTR,         ContextCompat.getColor(context, R.color.syntax_tag_attr))
            put(SyntaxTokenType.ATTR_VALUE,       ContextCompat.getColor(context, R.color.syntax_attr_value))
            put(SyntaxTokenType.PROPERTY_KEY,     ContextCompat.getColor(context, R.color.syntax_property_key))
            put(SyntaxTokenType.PROPERTY_VALUE,   ContextCompat.getColor(context, R.color.syntax_property_value))
            put(SyntaxTokenType.VARIABLE,         ContextCompat.getColor(context, R.color.syntax_variable))
            put(SyntaxTokenType.PREPROCESSOR,     ContextCompat.getColor(context, R.color.syntax_preprocessor))
            put(SyntaxTokenType.MARKDOWN_HEADER,  ContextCompat.getColor(context, R.color.syntax_markdown_header))
            put(SyntaxTokenType.MARKDOWN_LINK,    ContextCompat.getColor(context, R.color.syntax_markdown_link))
            put(SyntaxTokenType.BUILTIN,          ContextCompat.getColor(context, R.color.syntax_builtin))
            put(SyntaxTokenType.BOOLEAN_LITERAL,  ContextCompat.getColor(context, R.color.syntax_boolean_literal))
            put(SyntaxTokenType.CONSTANT,         ContextCompat.getColor(context, R.color.syntax_constant))
        }

        cache = SoftReference(map)
        return map
    }

    /** Clears the cached colour map (e.g. when the theme changes at runtime). */
    fun invalidate() {
        cache = null
    }
}
