package za.kilowatch.ultimatefilemanager.bookmarks.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.UUID
import java.util.regex.Pattern

data class ScrapedMetadata(
    val title: String?,
    val description: String?,
    val thumbnailPath: String?,
    val faviconPath: String?,
    val domain: String,
    val articleText: String?
)

object BookmarkMetadataScraper {

    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Mobile; rv:128.0) Gecko/128.0 Firefox/128.0"
    private const val TIMEOUT_MS = 10_000
    private const val MAX_HTML_BYTES = 512 * 1024 // 512 KB

    suspend fun scrape(context: Context, targetUrl: String): ScrapedMetadata = withContext(Dispatchers.IO) {
        val domain = extractDomain(targetUrl)
        var title: String? = null
        var description: String? = null
        var imageUrl: String? = null
        var faviconUrl: String? = null
        var articleText: String? = null

        try {
            val html = fetchHtml(targetUrl)
            if (html.isNotEmpty()) {
                title = extractMeta(html, "og:title")
                    ?: extractMeta(html, "twitter:title")
                    ?: extractTitleTag(html)

                description = extractMeta(html, "og:description")
                    ?: extractMeta(html, "twitter:description")
                    ?: extractMetaName(html, "description")

                imageUrl = extractMeta(html, "og:image")
                    ?: extractMeta(html, "twitter:image")

                faviconUrl = extractFavicon(html, targetUrl)
                    ?: "https://$domain/favicon.ico"

                articleText = extractCleanArticleText(html)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val thumbnailPath = if (!imageUrl.isNullOrBlank()) {
            downloadImage(context, resolveUrl(targetUrl, imageUrl))
        } else null

        val faviconPath = if (!faviconUrl.isNullOrBlank()) {
            downloadImage(context, resolveUrl(targetUrl, faviconUrl))
        } else null

        return@withContext ScrapedMetadata(
            title = title?.trim()?.takeIf { it.isNotEmpty() },
            description = description?.trim()?.takeIf { it.isNotEmpty() },
            thumbnailPath = thumbnailPath,
            faviconPath = faviconPath,
            domain = domain,
            articleText = articleText?.takeIf { it.isNotBlank() }
        )
    }

    private fun extractDomain(urlStr: String): String {
        return try {
            val uri = URI(urlStr)
            uri.host?.removePrefix("www.") ?: urlStr
        } catch (_: Exception) {
            urlStr
        }
    }

    private fun fetchHtml(urlStr: String): String {
        var currentUrl = urlStr
        var redirects = 0
        while (redirects < 5) {
            val conn = URL(currentUrl).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")

            val status = conn.responseCode
            if (status in 300..399) {
                val newUrl = conn.getHeaderField("Location") ?: break
                currentUrl = resolveUrl(currentUrl, newUrl)
                redirects++
                continue
            }

            if (status != HttpURLConnection.HTTP_OK) {
                return ""
            }

            conn.inputStream.use { input ->
                val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
                val sb = StringBuilder()
                val buffer = CharArray(4096)
                var bytesRead: Int
                var total = 0
                while (reader.read(buffer).also { bytesRead = it } != -1) {
                    sb.append(buffer, 0, bytesRead)
                    total += bytesRead * 2
                    if (total >= MAX_HTML_BYTES) break
                }
                return sb.toString()
            }
        }
        return ""
    }

    private fun extractMeta(html: String, property: String): String? {
        val pattern = Pattern.compile(
            """<meta\s+[^>]*property=["']$property["'][^>]*content=["']([^"']*)["']""",
            Pattern.CASE_INSENSITIVE
        )
        val matcher = pattern.matcher(html)
        if (matcher.find()) return unescapeHtml(matcher.group(1))

        // Reverse attribute order: content first, then property
        val reversePattern = Pattern.compile(
            """<meta\s+[^>]*content=["']([^"']*)["'][^>]*property=["']$property["']""",
            Pattern.CASE_INSENSITIVE
        )
        val revMatcher = reversePattern.matcher(html)
        if (revMatcher.find()) return unescapeHtml(revMatcher.group(1))

        return null
    }

    private fun extractMetaName(html: String, name: String): String? {
        val pattern = Pattern.compile(
            """<meta\s+[^>]*name=["']$name["'][^>]*content=["']([^"']*)["']""",
            Pattern.CASE_INSENSITIVE
        )
        val matcher = pattern.matcher(html)
        if (matcher.find()) return unescapeHtml(matcher.group(1))

        val reversePattern = Pattern.compile(
            """<meta\s+[^>]*content=["']([^"']*)["'][^>]*name=["']$name["']""",
            Pattern.CASE_INSENSITIVE
        )
        val revMatcher = reversePattern.matcher(html)
        if (revMatcher.find()) return unescapeHtml(revMatcher.group(1))

        return null
    }

    private fun extractTitleTag(html: String): String? {
        val pattern = Pattern.compile("""<title[^>]*>(.*?)</title>""", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
        val matcher = pattern.matcher(html)
        return if (matcher.find()) unescapeHtml(matcher.group(1)?.trim()) else null
    }

    private fun extractFavicon(html: String, baseUrl: String): String? {
        val pattern = Pattern.compile(
            """<link\s+[^>]*rel=["'](?:shortcut\s+)?icon["'][^>]*href=["']([^"']*)["']""",
            Pattern.CASE_INSENSITIVE
        )
        val matcher = pattern.matcher(html)
        if (matcher.find()) {
            return resolveUrl(baseUrl, matcher.group(1))
        }
        return null
    }

    private fun resolveUrl(base: String, relative: String): String {
        return try {
            val baseUri = URI(base)
            baseUri.resolve(relative.trim()).toString()
        } catch (_: Exception) {
            relative
        }
    }

    private fun downloadImage(context: Context, imgUrl: String): String? {
        val thumbDir = BookmarkFileCloner.getThumbnailDir(context)
        val destFile = File(thumbDir, "thumb_${UUID.randomUUID()}.jpg")

        return try {
            val conn = URL(imgUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 7_000
            conn.readTimeout = 7_000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null

            conn.inputStream.use { input ->
                val bmp = BitmapFactory.decodeStream(input) ?: return null
                FileOutputStream(destFile).use { out ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
                }
                bmp.recycle()
            }
            destFile.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    private fun extractCleanArticleText(html: String): String? {
        // Strip out scripts, styles, navigations, footers, headers
        var cleaned = html
            .replace(Regex("""<script[^>]*>.*?</script>""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""<style[^>]*>.*?</style>""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""<nav[^>]*>.*?</nav>""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""<header[^>]*>.*?</header>""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""<footer[^>]*>.*?</footer>""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""<aside[^>]*>.*?</aside>""", RegexOption.DOT_MATCHES_ALL), "")

        // Extract paragraphs
        val pPattern = Pattern.compile("""<p[^>]*>(.*?)</p>""", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
        val matcher = pPattern.matcher(cleaned)
        val paragraphs = mutableListOf<String>()

        while (matcher.find()) {
            val rawP = matcher.group(1) ?: continue
            val textOnly = rawP.replace(Regex("""<[^>]*>"""), "").trim()
            val unescaped = unescapeHtml(textOnly)
            if (unescaped.length > 40) { // Keep meaningful sentence-length paragraphs
                paragraphs.add(unescaped)
            }
        }

        return if (paragraphs.isNotEmpty()) {
            paragraphs.joinToString("\n\n")
        } else null
    }

    private fun unescapeHtml(text: String?): String {
        if (text == null) return ""
        return text
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&nbsp;", " ")
            .trim()
    }
}
