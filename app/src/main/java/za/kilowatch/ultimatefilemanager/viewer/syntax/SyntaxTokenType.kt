package za.kilowatch.ultimatefilemanager.viewer.syntax

/**
 * All token types the syntax highlighters can emit.
 * Each maps to a distinct span colour resolved by [SyntaxColorScheme].
 *
 * NOTE: OPERATOR and PUNCTUATION render with the theme's primary text colour
 * (no separate colour) so they blend naturally into the text.
 */
enum class SyntaxTokenType {

    // ── Core code tokens ──────────────────────────────────────────────────
    /** Language keywords (if, else, for, while, return, class, fun, etc.) */
    KEYWORD,

    /** Quoted string literals ("text", 'text') */
    STRING,

    /** Escape sequences inside strings (\n, \t, \\, \") */
    STRING_ESCAPE,

    /** Single-line comments (// …) */
    COMMENT_LINE,

    /** Multi-line / block comments (slash-star ... star-slash) */
    COMMENT_BLOCK,

    /** Numeric literals (42, 3.14, 0xFF, 1e5) */
    NUMBER,

    /** Type / class names (String, Int, Boolean, user-defined types) */
    TYPE,

    /** Annotations / decorators (@Override, @Deprecated) */
    ANNOTATION,

    /** Operators (+, -, *, /, =, ==, !=, <, >, &&, ||, ->, =>) */
    OPERATOR,

    /** Punctuation / delimiters ({, }, [, ], (, ), ;, ,, .) */
    PUNCTUATION,

    // ── Markup tokens ────────────────────────────────────────────────────
    /** XML/HTML tag name (<div>, <body>) */
    TAG_NAME,

    /** XML/HTML attribute name (class, id, style) */
    TAG_ATTR,

    /** Quoted attribute value (class="container") */
    ATTR_VALUE,

    // ── Config / data-file tokens ─────────────────────────────────────────
    /** Key in key-value pairs (key: value, key=value) — YAML, Properties, TOML */
    PROPERTY_KEY,

    /** Variable references ($var, ${var}) — Shell, PHP, Perl, Ruby */
    VARIABLE,

    /** Preprocessor directives (#include, #define, #import) — C, C++ */
    PREPROCESSOR,

    // ── Markdown tokens ───────────────────────────────────────────────────
    /** Markdown heading (# Heading, ## Subheading) */
    MARKDOWN_HEADER,

    /** Markdown link ([text](url)) */
    MARKDOWN_LINK,

    // ── Special identifiers ──────────────────────────────────────────────
    /** Built-in functions / methods (print, println, len, range) */
    BUILTIN,

    /** Boolean / null literals (true, false, null, nil, None) */
    BOOLEAN_LITERAL,

    /** Constants / static final / ALL_CAPS identifiers */
    CONSTANT,
}
