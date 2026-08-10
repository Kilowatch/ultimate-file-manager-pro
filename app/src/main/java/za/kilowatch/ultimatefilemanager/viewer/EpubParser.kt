package za.kilowatch.ultimatefilemanager.viewer

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Lightweight zero-dependency EPUB parser.
 *
 * An EPUB file is a ZIP archive. This parser:
 *   1. Extracts all ZIP entries to [destDir].
 *   2. Reads META-INF/container.xml to find the OPF rootfile path.
 *   3. Parses the OPF to build the spine (ordered list of chapter hrefs).
 *   4. Reads the NCX (EPUB 2) or Nav document (EPUB 3) for chapter titles.
 *
 * No external library is required — only [java.util.zip] and [android.util.Xml].
 */
object EpubParser {

    /** Thrown when an EPUB file is missing required structure or is unreadable. */
    class EpubParseException(message: String, cause: Throwable? = null) :
        Exception(message, cause)

    // ── Public data model ────────────────────────────────────────────────────

    data class EpubBook(
        /** Book title from OPF metadata (falls back to filename). */
        val title: String,
        /**
         * Ordered list of spine items (chapters / sections).
         * Each item's [EpubChapter.absolutePath] points to the extracted HTML file.
         */
        val chapters: List<EpubChapter>
    )

    data class EpubChapter(
        /** OPF spine idref — unique within the book. */
        val id: String,
        /** Human-readable title from NCX/Nav, or the filename as fallback. */
        val title: String,
        /** Absolute path to the extracted HTML/XHTML file in the cache dir. */
        val absolutePath: String
    )

    // ── Entry point ──────────────────────────────────────────────────────────

    /**
     * Parses [epubFile] into an [EpubBook].
     *
     * @param epubFile  The `.epub` file on disk.
     * @param destDir   Directory to extract EPUB contents into (caller manages lifecycle).
     * @return          Parsed [EpubBook] ready for display.
     * @throws EpubParseException if the file is not a valid EPUB.
     */
    fun parse(epubFile: File, destDir: File): EpubBook {
        // 1. Extract all entries from the ZIP
        extractZip(epubFile, destDir)

        // 2. Find OPF rootfile via META-INF/container.xml
        val containerFile = File(destDir, "META-INF/container.xml")
        if (!containerFile.exists()) throw EpubParseException("Missing META-INF/container.xml")
        val opfRelPath = parseContainerXml(containerFile.inputStream())
        val opfFile = File(destDir, opfRelPath)
        if (!opfFile.exists()) throw EpubParseException("OPF file not found: $opfRelPath")

        // OPF is relative to its own directory (content/ or root etc.)
        val opfDir = opfFile.parentFile ?: destDir

        // 3. Parse OPF
        val opfData = parseOpf(opfFile.inputStream(), opfDir, destDir)

        return EpubBook(
            title = opfData.title,
            chapters = opfData.chapters
        )
    }

    // ── ZIP extraction ───────────────────────────────────────────────────────

    private fun extractZip(epubFile: File, destDir: File) {
        destDir.mkdirs()
        ZipInputStream(FileInputStream(epubFile).buffered()).use { zin ->
            var entry = zin.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val outFile = sanitizeZipEntry(destDir, entry.name)
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos -> zin.copyTo(fos) }
                }
                zin.closeEntry()
                entry = zin.nextEntry
            }
        }
    }

    /**
     * Guards against Zip Slip path traversal attacks.
     * Throws if the resolved path escapes [destDir].
     */
    private fun sanitizeZipEntry(destDir: File, entryName: String): File {
        val dest = File(destDir, entryName).canonicalFile
        val canonicalDest = destDir.canonicalFile
        if (!dest.path.startsWith(canonicalDest.path + File.separator) &&
            dest.path != canonicalDest.path) {
            throw EpubParseException("Zip Slip attempt blocked: $entryName")
        }
        return dest
    }

    // ── container.xml ────────────────────────────────────────────────────────

    /**
     * Parses META-INF/container.xml and returns the path of the OPF rootfile.
     *
     * Expected structure:
     * <container>
     *   <rootfiles>
     *     <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
     *   </rootfiles>
     * </container>
     */
    private fun parseContainerXml(stream: InputStream): String {
        val parser = Xml.newPullParser()
        parser.setInput(stream, null)
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG && parser.name == "rootfile") {
                val fullPath = parser.getAttributeValue(null, "full-path")
                if (!fullPath.isNullOrBlank()) return fullPath
            }
            eventType = parser.next()
        }
        throw EpubParseException("No rootfile found in container.xml")
    }

    // ── OPF parsing ──────────────────────────────────────────────────────────

    private data class OpfData(val title: String, val chapters: List<EpubChapter>)

    /**
     * Parses the OPF package document and returns an [OpfData] with title and spine chapters.
     *
     * @param stream  Input stream of the OPF file.
     * @param opfDir  Directory containing the OPF file (used to resolve relative hrefs).
     * @param destDir Root extraction directory (used as fallback).
     */
    private fun parseOpf(stream: InputStream, opfDir: File, destDir: File): OpfData {
        val manifestItems = mutableMapOf<String, ManifestItem>()
        val spineIdrefs   = mutableListOf<String>()
        var title = ""
        var ncxId = ""
        var navId = ""

        val parser = Xml.newPullParser()
        parser.setInput(stream, null)
        var eventType = parser.eventType

        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                when (parser.name.substringAfterLast(':')) {
                    "title" -> {
                        if (title.isEmpty()) {
                            eventType = parser.next()
                            if (eventType == XmlPullParser.TEXT) title = parser.text.trim()
                            continue
                        }
                    }
                    "item" -> {
                        val id         = parser.getAttributeValue(null, "id")           ?: ""
                        val href       = parser.getAttributeValue(null, "href")         ?: ""
                        val mediaType  = parser.getAttributeValue(null, "media-type")   ?: ""
                        val properties = parser.getAttributeValue(null, "properties")   ?: ""
                        if (id.isNotEmpty() && href.isNotEmpty()) {
                            manifestItems[id] = ManifestItem(id, href, mediaType, properties)
                            if (mediaType.contains("ncx")) ncxId = id
                            if (properties.contains("nav")) navId = id
                        }
                    }
                    "itemref" -> {
                        val idref  = parser.getAttributeValue(null, "idref")   ?: ""
                        val linear = parser.getAttributeValue(null, "linear")  ?: "yes"
                        if (idref.isNotEmpty() && linear != "no") spineIdrefs += idref
                    }
                    "spine" -> {
                        val tocAttr = parser.getAttributeValue(null, "toc") ?: ""
                        if (tocAttr.isNotEmpty() && ncxId.isEmpty()) ncxId = tocAttr
                    }
                }
            }
            eventType = parser.next()
        }

        if (title.isEmpty()) title = opfDir.name

        // Resolve chapter titles from NCX (EPUB2) or Nav doc (EPUB3)
        val chapterTitles = mutableMapOf<String, String>()
        val navItemId = navId.takeIf { it.isNotEmpty() } ?: ncxId
        val navItem   = manifestItems[navItemId]
        if (navItem != null) {
            val navFile = resolveHref(opfDir, navItem.href)
            if (navFile.exists()) {
                try {
                    if (navItem.mediaType.contains("ncx") || navItem.href.endsWith(".ncx")) {
                        parseNcx(navFile.inputStream(), chapterTitles)
                    } else {
                        parseNavDoc(navFile.inputStream(), chapterTitles)
                    }
                } catch (_: Exception) { /* titles remain empty; filename fallback used */ }
            }
        }

        // Assemble ordered spine
        val chapters = spineIdrefs.mapNotNull { idref ->
            val item = manifestItems[idref] ?: return@mapNotNull null
            if (!item.mediaType.contains("html") &&
                !item.href.endsWith(".xhtml") && !item.href.endsWith(".html")) {
                return@mapNotNull null
            }
            val absoluteFile = resolveHref(opfDir, item.href)
            val chapterTitle = chapterTitles[item.href]
                ?: chapterTitles[item.href.substringAfterLast('/')]
                ?: item.href.substringAfterLast('/').substringBeforeLast('.')
            EpubChapter(
                id           = item.id,
                title        = chapterTitle,
                absolutePath = absoluteFile.absolutePath
            )
        }

        if (chapters.isEmpty()) throw EpubParseException("EPUB spine has no readable HTML chapters")

        return OpfData(title = title, chapters = chapters)
    }

    // ── NCX parser (EPUB 2) ──────────────────────────────────────────────────

    private fun parseNcx(stream: InputStream, titles: MutableMap<String, String>) {
        val parser = Xml.newPullParser()
        parser.setInput(stream, null)
        var currentLabel = ""
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                when (parser.name.substringAfterLast(':')) {
                    "text" -> {
                        eventType = parser.next()
                        if (eventType == XmlPullParser.TEXT) currentLabel = parser.text.trim()
                        continue
                    }
                    "content" -> {
                        val src = parser.getAttributeValue(null, "src") ?: ""
                        if (src.isNotBlank() && currentLabel.isNotBlank()) {
                            val key = src.substringBefore('#')
                            titles[key] = currentLabel
                            titles[key.substringAfterLast('/')] = currentLabel
                            currentLabel = ""
                        }
                    }
                }
            }
            eventType = parser.next()
        }
    }

    // ── Nav document parser (EPUB 3) ─────────────────────────────────────────

    private fun parseNavDoc(stream: InputStream, titles: MutableMap<String, String>) {
        val parser = Xml.newPullParser()
        parser.setInput(stream, null)
        var inTocNav = false
        var currentHref = ""
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                val tagName = parser.name.substringAfterLast(':')
                if (tagName == "nav") {
                    val epubType = parser.getAttributeValue(null, "epub:type") ?: ""
                    inTocNav = epubType.contains("toc")
                }
                if (inTocNav && tagName == "a") {
                    currentHref = (parser.getAttributeValue(null, "href") ?: "").substringBefore('#')
                }
            } else if (eventType == XmlPullParser.TEXT && inTocNav && currentHref.isNotBlank()) {
                val label = parser.text.trim()
                if (label.isNotBlank()) {
                    titles[currentHref] = label
                    titles[currentHref.substringAfterLast('/')] = label
                    currentHref = ""
                }
            } else if (eventType == XmlPullParser.END_TAG) {
                if (parser.name.substringAfterLast(':') == "nav") inTocNav = false
            }
            eventType = parser.next()
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private data class ManifestItem(
        val id: String,
        val href: String,
        val mediaType: String,
        val properties: String
    )

    private fun resolveHref(opfDir: File, href: String): File {
        val decoded = java.net.URLDecoder.decode(href, "UTF-8")
        return File(opfDir, decoded)
    }
}
