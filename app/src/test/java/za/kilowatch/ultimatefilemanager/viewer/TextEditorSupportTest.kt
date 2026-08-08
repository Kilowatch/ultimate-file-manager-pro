package za.kilowatch.ultimatefilemanager.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import za.kilowatch.ultimatefilemanager.util.MimeTypeHelper
import za.kilowatch.ultimatefilemanager.viewer.syntax.LanguageRegistry

class TextEditorSupportTest {

    @Test
    fun router_supportsM3uAndYmlExtensions() {
        assertTrue(FileViewerRouter.canOpenInternally("m3u"))
        assertTrue(FileViewerRouter.canOpenInternally("m3u8"))
        assertTrue(FileViewerRouter.canOpenInternally("yml"))
        assertTrue(FileViewerRouter.canOpenInternally("yaml"))
        assertTrue("m3u" in FileViewerRouter.TEXT_EXTENSIONS)
        assertTrue("m3u8" in FileViewerRouter.TEXT_EXTENSIONS)
        assertTrue("yml" in FileViewerRouter.TEXT_EXTENSIONS)
        assertTrue("yaml" in FileViewerRouter.TEXT_EXTENSIONS)
    }

    @Test
    fun languageRegistry_detectsM3uAndYmlLanguages() {
        assertEquals(LanguageRegistry.M3U, LanguageRegistry.detect("playlist.m3u"))
        assertEquals(LanguageRegistry.M3U, LanguageRegistry.detect("stream.m3u8"))
        assertEquals(LanguageRegistry.YAML, LanguageRegistry.detect("config.yml"))
        assertEquals(LanguageRegistry.YAML, LanguageRegistry.detect("config.yaml"))
    }

    @Test
    fun mimeTypeHelper_providesNonOctetStreamFallbacks() {
        val ymlMime = MimeTypeHelper.getOrFallback("yml")
        val m3uMime = MimeTypeHelper.getOrFallback("m3u")
        assertTrue(ymlMime != "application/octet-stream")
        assertTrue(m3uMime != "application/octet-stream")
    }
}
