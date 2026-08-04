package za.kilowatch.ultimatefilemanager.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import za.kilowatch.ultimatefilemanager.viewer.syntax.LanguageDef
import za.kilowatch.ultimatefilemanager.viewer.syntax.LanguageRegistry
import za.kilowatch.ultimatefilemanager.viewer.syntax.PropertiesLexer
import za.kilowatch.ultimatefilemanager.viewer.syntax.SyntaxTokenType

/**
 * Unit tests for the dot-config file feature: filename detection,
 * language detection for dotfiles, and the dotenv `KEY=VALUE` lexer.
 */
class DotConfigFeatureTest {

    // ── FileViewerRouter.isDotConfigFile ──────────────────────────────────

    @Test
    fun isDotConfigFile_acceptsDotenvFamily() {
        assertTrue(FileViewerRouter.isDotConfigFile(".env"))
        assertTrue(FileViewerRouter.isDotConfigFile(".env.local"))
        assertTrue(FileViewerRouter.isDotConfigFile(".env.production"))
        assertTrue(FileViewerRouter.isDotConfigFile(".env."))
    }

    @Test
    fun isDotConfigFile_isCaseInsensitive() {
        assertTrue(FileViewerRouter.isDotConfigFile(".ENV"))
        assertTrue(FileViewerRouter.isDotConfigFile(".Env.Local"))
    }

    @Test
    fun isDotConfigFile_acceptsExtensionlessDotfiles() {
        assertTrue(FileViewerRouter.isDotConfigFile(".htaccess"))
        assertTrue(FileViewerRouter.isDotConfigFile(".gitignore"))
        assertTrue(FileViewerRouter.isDotConfigFile(".npmrc"))
        assertTrue(FileViewerRouter.isDotConfigFile(".DS_Store"))
    }

    @Test
    fun isDotConfigFile_rejectsNonDotfilesAndDottedNames() {
        assertTrue(!FileViewerRouter.isDotConfigFile("env"))
        assertTrue(!FileViewerRouter.isDotConfigFile("notes.txt"))
        assertTrue(!FileViewerRouter.isDotConfigFile(".babelrc.json"))
        assertTrue(!FileViewerRouter.isDotConfigFile(".."))
        assertTrue(!FileViewerRouter.isDotConfigFile("."))
        assertTrue(!FileViewerRouter.isDotConfigFile(""))
    }

    // ── LanguageRegistry.detect ───────────────────────────────────────────

    @Test
    fun detect_returnsDotenvForEnvFamily() {
        assertEquals(LanguageRegistry.Env, LanguageRegistry.detect(".env"))
        assertEquals(LanguageRegistry.Env, LanguageRegistry.detect(".env.local"))
        assertEquals(LanguageRegistry.Env, LanguageRegistry.detect(".env.production"))
        assertEquals(LanguageRegistry.Env, LanguageRegistry.detect(".ENV"))
    }

    @Test
    fun detect_returnsDotenvForKeyValueDotfiles() {
        assertEquals(LanguageRegistry.Env, LanguageRegistry.detect(".npmrc"))
        assertEquals(LanguageRegistry.Env, LanguageRegistry.detect(".pylintrc"))
        assertEquals(LanguageRegistry.Env, LanguageRegistry.detect(".curlrc"))
        assertEquals(LanguageRegistry.Env, LanguageRegistry.detect(".editorconfig"))
    }

    @Test
    fun detect_returnsNullForNonKeyValueDotfiles() {
        assertNull(LanguageRegistry.detect(".htaccess"))
        assertNull(LanguageRegistry.detect(".gitignore"))
        assertNull(LanguageRegistry.detect(".bashrc"))
        assertNull(LanguageRegistry.detect("env"))
    }

    @Test
    fun detect_regression_extensionAndFilenameStillWork() {
        assertEquals(LanguageRegistry.Kotlin, LanguageRegistry.detect("Main.kt"))
        assertEquals(LanguageRegistry.YAML, LanguageRegistry.detect("config.yaml"))
        assertEquals(LanguageRegistry.Dockerfile, LanguageRegistry.detect("Dockerfile"))
        // A dotted filename with a real extension wins via the extension lookup
        assertEquals(LanguageRegistry.JSON, LanguageRegistry.detect(".babelrc.json"))
    }

    // ── PropertiesLexer.tokenize ──────────────────────────────────────────

    private fun tokensFor(text: String, lang: LanguageDef): List<Pair<SyntaxTokenType, String>> =
        PropertiesLexer.tokenize(text, lang).map { it.type to text.substring(it.start, it.end) }

    @Test
    fun lexer_highlightsCommentLines() {
        assertEquals(
            listOf(SyntaxTokenType.COMMENT_LINE to "# comment"),
            tokensFor("# comment", LanguageRegistry.Env)
        )
    }

    @Test
    fun lexer_highlightsKeyValue() {
        assertEquals(
            listOf(
                SyntaxTokenType.PROPERTY_KEY to "DB_HOST",
                SyntaxTokenType.PROPERTY_VALUE to "localhost"
            ),
            tokensFor("DB_HOST=localhost", LanguageRegistry.Env)
        )
    }

    @Test
    fun lexer_handlesSpacingAndColonSeparator() {
        assertEquals(
            listOf(
                SyntaxTokenType.PROPERTY_KEY to "KEY",
                SyntaxTokenType.PROPERTY_VALUE to "value"
            ),
            tokensFor("KEY = value", LanguageRegistry.Env)
        )
        assertEquals(
            listOf(
                SyntaxTokenType.PROPERTY_KEY to "key",
                SyntaxTokenType.PROPERTY_VALUE to "value"
            ),
            tokensFor("key: value", LanguageRegistry.Env)
        )
    }

    @Test
    fun lexer_highlightsQuotedValues() {
        assertEquals(
            listOf(
                SyntaxTokenType.PROPERTY_KEY to "APP_NAME",
                SyntaxTokenType.STRING to "\"My App\""
            ),
            tokensFor("APP_NAME=\"My App\"", LanguageRegistry.Env)
        )
    }

    @Test
    fun lexer_splitsInlineComment() {
        assertEquals(
            listOf(
                SyntaxTokenType.PROPERTY_KEY to "KEY",
                SyntaxTokenType.PROPERTY_VALUE to "value",
                SyntaxTokenType.COMMENT_LINE to "# comment"
            ),
            tokensFor("KEY=value # comment", LanguageRegistry.Env)
        )
    }

    @Test
    fun lexer_ignoresSectionHeadersAndBlankLines() {
        assertEquals(emptyList<Pair<SyntaxTokenType, String>>(), tokensFor("[SECTION]", LanguageRegistry.Env))
        assertEquals(emptyList<Pair<SyntaxTokenType, String>>(), tokensFor("   \n  ", LanguageRegistry.Env))
    }

    @Test
    fun lexer_respectsLanguageCommentPrefixes() {
        // Properties language treats ';' and '!' as line comments too
        assertEquals(
            listOf(SyntaxTokenType.COMMENT_LINE to "; a comment"),
            tokensFor("; a comment", LanguageRegistry.Properties)
        )
        assertEquals(
            listOf(SyntaxTokenType.COMMENT_LINE to "! a note"),
            tokensFor("! a note", LanguageRegistry.Properties)
        )
    }

    @Test
    fun lexer_handlesMultiLineFile() {
        val text = "# db\nDB_HOST=localhost\nAPP_NAME=\"x\""
        assertEquals(
            listOf(
                SyntaxTokenType.COMMENT_LINE to "# db",
                SyntaxTokenType.PROPERTY_KEY to "DB_HOST",
                SyntaxTokenType.PROPERTY_VALUE to "localhost",
                SyntaxTokenType.PROPERTY_KEY to "APP_NAME",
                SyntaxTokenType.STRING to "\"x\""
            ),
            tokensFor(text, LanguageRegistry.Env)
        )
    }
}
