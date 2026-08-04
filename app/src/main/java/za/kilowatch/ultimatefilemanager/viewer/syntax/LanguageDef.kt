package za.kilowatch.ultimatefilemanager.viewer.syntax

/**
 * Describes how to tokenise a single programming / markup / config language.
 *
 * Every set is populated with **lowercase** entries because lexers normalise
 * identifiers before matching (case-insensitive comparison is done at the
 * language-registry level for languages that need it — case-sensitive
 * languages such as YAML keys are handled by their dedicated lexer).
 *
 * @property name             Human-readable display name (e.g. "JSON", "Kotlin").
 * @property keywords         Reserved words / control-flow keywords.
 * @property types            Built-in or commonly-used type names.
 * @property builtins         Built-in functions, methods, or standard-library names.
 * @property booleanLiterals  Literal boolean / null / undefined values.
 * @property constants        Well-known constant names (e.g. "PI", "E", "INFINITY").
 * @property lineComments     Prefixes for single-line comments.
 * @property blockComments    Pairs of (open, close) for multi-line comments.
 * @property stringDelimiters Characters that start/end string literals.
 * @property hasAnnotations   Whether the language uses @-prefixed annotations.
 * @property hasPreprocessor  Whether # at line-start is a preprocessor directive.
 * @property hasMarkdownHeaders Whether # at line-start is a heading marker.
 * @property hasMarkdownLinks Whether [text](url) patterns should be highlighted.
 * @property variablePrefix   Character that introduces variable references.
 * @property numericSuffixes  Suffixes that can follow numeric literals (e.g. "L", "f").
 * @property multiStringDelimiters Strings that open/close multi-line strings (e.g. """).
 * @property isMarkup         If true, use [MarkupLexer] instead of the general [CodeLexer].
 * @property isYaml           If true, use [YamlLexer] instead of the general [CodeLexer].
 * @property isProperties     If true, treat as key=value config (extra PROPERTY_KEY rules).
 */
data class LanguageDef(
    val name: String,
    val keywords: Set<String> = emptySet(),
    val types: Set<String> = emptySet(),
    val builtins: Set<String> = emptySet(),
    val booleanLiterals: Set<String> = emptySet(),
    val constants: Set<String> = emptySet(),
    val lineComments: List<String> = emptyList(),
    val blockComments: List<Pair<String, String>> = emptyList(),
    val stringDelimiters: List<String> = listOf("\"", "'"),
    val hasAnnotations: Boolean = false,
    val hasPreprocessor: Boolean = false,
    val hasMarkdownHeaders: Boolean = false,
    val hasMarkdownLinks: Boolean = false,
    val variablePrefix: Char? = null,
    val numericSuffixes: List<String> = emptyList(),
    val multiStringDelimiters: List<String> = emptyList(),
    val isMarkup: Boolean = false,
    val isYaml: Boolean = false,
    val isProperties: Boolean = false,
    /** If true, use [PropertiesLexer] instead of the general [CodeLexer] (dotenv-style KEY=VALUE files). */
    val isDotenv: Boolean = false
)
