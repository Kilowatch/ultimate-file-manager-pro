package za.kilowatch.ultimatefilemanager.viewer.syntax

import android.content.Context
import android.content.res.Configuration
import androidx.core.content.ContextCompat
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.settings.ColorblindPrefs
import java.lang.ref.SoftReference

/**
 * Resolves [SyntaxTokenType] to Android colour integers at runtime.
 *
 * Colour maps are cached via a [SoftReference] so they survive configuration
 * changes without re-allocation while still allowing GC under memory pressure.
 * The cache is **keyed** (see [CacheKey]) rather than holding one map, because
 * two different palettes are now in play: the default scheme and the Colorblind
 * Mode scheme, and the latter changes with the user's type/strength.
 *
 * OPERATOR and PUNCTUATION map to the theme's primary text colour
 * (`android:textColorPrimary`) so they blend naturally into the text
 * rather than drawing attention. They are **not** remapped: they already follow
 * the theme, and giving them a hue would draw the eye to punctuation.
 *
 * COMMENT_LINE and COMMENT_BLOCK share the same colour (`syntax_comment`).
 * STRING and STRING_ESCAPE share the same colour (`syntax_string`).
 *
 * ## The Colorblind Mode remap (FR-12)
 *
 * Pass `remap = true` from the **read-only viewer only**. Edit mode must pass
 * `false`: entering edit mode calls the same `highlight()` the viewer does, so
 * the decision has to come from the caller, not from which engine method ran.
 *
 * The remapped palette has **four hues plus a strength-scaled grey**, against the
 * default scheme's eight hues plus two greys — so some collapsing is unavoidable,
 * and the question is only *which* tokens collapse together. The rule is semantic
 * proximity to the colour the default scheme already gave them:
 *
 * | Remapped family | Default hues folded in |
 * |---|---|
 * | `keyword` | purple (`keyword`, `tag_attr`, `boolean_literal`) and violet (`builtin`) |
 * | `string` | green (`string`, `attr_value`) — a 1:1 match, nothing collapses |
 * | `number` | amber (`number`, `variable`, `constant`) — a 1:1 match |
 * | `comment` | grey (`comment`) and dark grey (`annotation`, `preprocessor`) |
 * | `name` | the residue: cyan (`type`), red (`tag_name`, `markdown_header`), blue (`property_key`, `markdown_link`), teal (`property_value`) |
 *
 * The one collapse that carries the requirement is **red vs green**: `tag_name`
 * (red) and `string`/`attr_value` (green) are the pair a red-green user loses,
 * and they land in different families. The multi-hue `name` residue is the price
 * of a four-family palette and is the least harmful place to pay it — those
 * tokens are separated by position and syntax as much as by colour.
 *
 * **Strength** scales only the `comment` family. Per Q14 the strength tier raises
 * the contrast floor on the low-salience tokens a colourblind user actually
 * loses — `comment`, `annotation`, `preprocessor` — rather than building nine
 * full 18-token palettes. Code ink already contrasts with its own background, so
 * scaling every token would add ~162 hand-authored colours whose failure mode is
 * silent and isolated to one of nine settings. All three of those tokens are in
 * the `comment` family above, so this file delivers Q14 exactly, reusing the
 * generated `ufm_cb_syntax_comment_{strength}_{type}` resources.
 *
 * @see ColorblindPrefs for the stored type/strength that select a palette.
 */
object SyntaxColorScheme {

    /**
     * Identifies a cached map.
     *
     * A key rather than a single cached map, and a data class rather than a
     * packed `Int`: the earlier single-entry cache was correct only while one
     * palette existed. With the remap it would serve the Colorblind palette to
     * edit mode, the default palette to the viewer after a mode change, and
     * **day colours after a night-mode switch** — [night] is in the key precisely
     * because the `ufm_cb_syntax_*` and `syntax_*` resources both carry
     * `values-night` variants, so the same type/strength resolves to different
     * colours in the two appearances. Packing these four fields into an `Int`
     * would work until two of them collided, which is not a class of bug worth
     * re-introducing for one allocation per highlight pass.
     */
    private data class CacheKey(
        val remap: Boolean,
        val night: Int,
        val type: Int,
        val strength: Int,
    )

    private var cache: SoftReference<Pair<CacheKey, Map<SyntaxTokenType, Int>>>? = null

    /**
     * Returns the colour map for [context].
     *
     * @param remap `true` for the Colorblind Mode syntax palette, `false` for the
     *   default scheme. Defaults to `false`, so every existing call site keeps
     *   today's appearance without being touched. Note that `true` is still a
     *   request, not a guarantee: while the mode is **Off** this returns the
     *   default map, so the mode-off appearance cannot change (FR-19).
     */
    fun getColors(context: Context, remap: Boolean = false): Map<SyntaxTokenType, Int> {
        // FR-19: with the mode off there is no palette to select, and the stored
        // type would otherwise index [typeRes] out of range or fall back onto an
        // enabled palette.
        val active = remap && ColorblindPrefs.isEnabled(context)
        val key = CacheKey(
            remap = active,
            night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK,
            type = ColorblindPrefs.getType(context),
            strength = ColorblindPrefs.getStrength(context),
        )
        cache?.get()?.let { (cachedKey, colors) -> if (cachedKey == key) return colors }

        val map = if (active) remappedColors(context, key.type, key.strength) else defaultColors(context)
        cache = SoftReference(key to map)
        return map
    }

    /** Clears the cached colour map (e.g. when the theme changes at runtime). */
    fun invalidate() {
        cache = null
    }

    // ── Default scheme ──────────────────────────────────────────────────────

    private fun defaultColors(context: Context): Map<SyntaxTokenType, Int> {
        val primaryText = ContextCompat.getColor(context, android.R.color.primary_text_dark)
        // Actually use the theme's text colour via a resolved attribute
        val ta = context.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
        val textColor = ta.getColor(0, primaryText)
        ta.recycle()

        return buildMap {
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
    }

    // ── Colorblind Mode scheme ──────────────────────────────────────────────

    private fun remappedColors(context: Context, type: Int, strength: Int): Map<SyntaxTokenType, Int> {
        // The mode is on, so the type is 1..3 and the index is 0..2. Both are
        // coerced anyway: ColorblindPrefs coerces on read, but a future type
        // added there without a matching array here would otherwise throw out of
        // a highlight pass, taking the viewer down over a colour.
        val ti = (type - 1).coerceIn(0, TYPE_SLOTS - 1)
        val si = strength.coerceIn(0, STRENGTH_SLOTS - 1)

        val keyword = ContextCompat.getColor(context, KEYWORD_RES[ti])
        val string = ContextCompat.getColor(context, STRING_RES[ti])
        val number = ContextCompat.getColor(context, NUMBER_RES[ti])
        val name = ContextCompat.getColor(context, NAME_RES[ti])
        val comment = ContextCompat.getColor(context, COMMENT_RES[si][ti])

        // OPERATOR/PUNCTUATION keep the theme's own text colour. They are the two
        // tokens whose job is to be invisible; a remap could only make them worse.
        val primaryText = ContextCompat.getColor(context, android.R.color.primary_text_dark)
        val ta = context.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
        val textColor = ta.getColor(0, primaryText)
        ta.recycle()

        return buildMap {
            // keyword family — purple and violet in the default scheme.
            put(SyntaxTokenType.KEYWORD,         keyword)
            put(SyntaxTokenType.BUILTIN,         keyword)
            put(SyntaxTokenType.BOOLEAN_LITERAL, keyword)
            put(SyntaxTokenType.TAG_ATTR,        keyword)

            // string family — green in the default scheme, 1:1.
            put(SyntaxTokenType.STRING,        string)
            put(SyntaxTokenType.STRING_ESCAPE, string)
            put(SyntaxTokenType.ATTR_VALUE,    string)

            // number family — amber in the default scheme, 1:1.
            put(SyntaxTokenType.NUMBER,   number)
            put(SyntaxTokenType.VARIABLE, number)
            put(SyntaxTokenType.CONSTANT, number)

            // comment family — the strength-scaled tokens (Q14).
            put(SyntaxTokenType.COMMENT_LINE, comment)
            put(SyntaxTokenType.COMMENT_BLOCK, comment)
            put(SyntaxTokenType.ANNOTATION,   comment)
            put(SyntaxTokenType.PREPROCESSOR, comment)

            // name family — the multi-hue residue (cyan, red, blue, teal).
            put(SyntaxTokenType.TYPE,            name)
            put(SyntaxTokenType.TAG_NAME,        name)
            put(SyntaxTokenType.PROPERTY_KEY,    name)
            put(SyntaxTokenType.PROPERTY_VALUE,  name)
            put(SyntaxTokenType.MARKDOWN_HEADER, name)
            put(SyntaxTokenType.MARKDOWN_LINK,   name)

            // Unchanged by the remap.
            put(SyntaxTokenType.OPERATOR,    textColor)
            put(SyntaxTokenType.PUNCTUATION, textColor)
        }
    }

    private const val TYPE_SLOTS = 3
    private const val STRENGTH_SLOTS = 3

    // Indexed by (type - 1): red-green, blue-yellow, high-contrast. Explicit
    // tables rather than getIdentifier("ufm_cb_syntax_keyword_" + name): the
    // generated names would be silently unverifiable at compile time, and a
    // template change upstream would fail at runtime inside a highlight pass
    // rather than at build time here.

    private val KEYWORD_RES = intArrayOf(
        R.color.ufm_cb_syntax_keyword_redgreen,
        R.color.ufm_cb_syntax_keyword_blueyellow,
        R.color.ufm_cb_syntax_keyword_highcontrast,
    )

    private val STRING_RES = intArrayOf(
        R.color.ufm_cb_syntax_string_redgreen,
        R.color.ufm_cb_syntax_string_blueyellow,
        R.color.ufm_cb_syntax_string_highcontrast,
    )

    private val NUMBER_RES = intArrayOf(
        R.color.ufm_cb_syntax_number_redgreen,
        R.color.ufm_cb_syntax_number_blueyellow,
        R.color.ufm_cb_syntax_number_highcontrast,
    )

    private val NAME_RES = intArrayOf(
        R.color.ufm_cb_syntax_name_redgreen,
        R.color.ufm_cb_syntax_name_blueyellow,
        R.color.ufm_cb_syntax_name_highcontrast,
    )

    /**
     * Comment-family colours, `[strength][type]` — the generated resources are
     * named `..._{strength}_{type}`, so the arrays are strength-major to match.
     */
    private val COMMENT_RES = arrayOf(
        intArrayOf(
            R.color.ufm_cb_syntax_comment_low_redgreen,
            R.color.ufm_cb_syntax_comment_low_blueyellow,
            R.color.ufm_cb_syntax_comment_low_highcontrast,
        ),
        intArrayOf(
            R.color.ufm_cb_syntax_comment_medium_redgreen,
            R.color.ufm_cb_syntax_comment_medium_blueyellow,
            R.color.ufm_cb_syntax_comment_medium_highcontrast,
        ),
        intArrayOf(
            R.color.ufm_cb_syntax_comment_high_redgreen,
            R.color.ufm_cb_syntax_comment_high_blueyellow,
            R.color.ufm_cb_syntax_comment_high_highcontrast,
        ),
    )
}
