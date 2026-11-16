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
import java.net.InetAddress
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

    private val YOUTUBE_PATTERN = Pattern.compile(
        """(?:https?://)?(?:www\.|m\.)?(?:youtube\.com/(?:watch\?.*v=|embed/|shorts/)|youtu\.be/)([a-zA-Z0-9_-]{11})""",
        Pattern.CASE_INSENSITIVE
    )

    fun isYouTube(urlStr: String): Boolean {
        return urlStr.contains("youtube.com", ignoreCase = true) || urlStr.contains("youtu.be", ignoreCase = true)
    }

    fun isReddit(urlStr: String): Boolean {
        val lower = urlStr.lowercase()
        return lower.contains("reddit.com") || lower.contains("redd.it")
    }

    private fun resolveRedirects(initialUrl: String): String {
        var current = initialUrl
        var redirects = 0
        while (redirects < 5) {
            try {
                val conn = URL(current).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.setRequestProperty("User-Agent", USER_AGENT)
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: break
                    current = resolveUrl(current, loc)
                    redirects++
                } else {
                    break
                }
            } catch (_: Exception) {
                break
            }
        }
        return current
    }

    fun fetchRedditData(targetUrl: String): ScrapedMetadata? {
        return try {
            val resolvedUrl = resolveRedirects(targetUrl)
            val cleanUrl = resolvedUrl.substringBefore("?").removeSuffix("/")
            val jsonUrl = "$cleanUrl.json?limit=5"
            val conn = URL(jsonUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 7000
            conn.readTimeout = 7000
            // Custom User-Agent compliant with Reddit API guidelines to prevent 429 rate limit
            conn.setRequestProperty("User-Agent", "android:za.kilowatch.ultimatefilemanager:v2.0 (by /u/ufm_app)")
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null

            val jsonStr = conn.inputStream.bufferedReader().readText()
            val jsonArray = org.json.JSONArray(jsonStr)
            if (jsonArray.length() == 0) return null

            val postListing = jsonArray.getJSONObject(0).getJSONObject("data").getJSONArray("children")
            if (postListing.length() == 0) return null
            val postData = postListing.getJSONObject(0).getJSONObject("data")

            val rawTitle = postData.optString("title")
            val selftext = postData.optString("selftext")
            val author = postData.optString("author")
            val sub = postData.optString("subreddit_name_prefixed")
            val rawThumb = postData.optString("thumbnail")
            val previewUrl = postData.optJSONObject("preview")
                ?.optJSONArray("images")
                ?.optJSONObject(0)
                ?.optJSONObject("source")
                ?.optString("url")
                ?.replace("&amp;", "&")

            val title = if (sub.isNotBlank()) "[$sub] $rawTitle" else rawTitle
            val desc = selftext.trim().take(250).ifBlank { null }
            val thumbUrl = if (!previewUrl.isNullOrBlank()) previewUrl else if (rawThumb.startsWith("http")) rawThumb else null

            val sb = StringBuilder()
            if (sub.isNotBlank()) sb.append("**Community:** $sub\n")
            if (author.isNotBlank()) sb.append("**Author:** u/$author\n\n")
            if (selftext.isNotBlank()) {
                sb.append(selftext.trim())
            } else {
                val postLink = postData.optString("url")
                if (postLink.isNotBlank() && !postLink.contains("reddit.com")) {
                    sb.append("Linked URL: $postLink\n")
                }
            }

            if (jsonArray.length() > 1) {
                val commentsListing = jsonArray.optJSONObject(1)?.optJSONObject("data")?.optJSONArray("children")
                if (commentsListing != null && commentsListing.length() > 0) {
                    val comments = mutableListOf<String>()
                    for (i in 0 until minOf(commentsListing.length(), 6)) {
                        val cData = commentsListing.optJSONObject(i)?.optJSONObject("data") ?: continue
                        val cAuthor = cData.optString("author")
                        val cBody = cData.optString("body")
                        if (cBody.isNotBlank() && cAuthor != "AutoModerator") {
                            comments.add("💬 **u/$cAuthor:**\n${cBody.trim()}")
                        }
                    }
                    if (comments.isNotEmpty()) {
                        sb.append("\n\n---\n### Top Discussion\n\n")
                        sb.append(comments.joinToString("\n\n"))
                    }
                }
            }

            ScrapedMetadata(
                title = title,
                description = desc,
                thumbnailPath = thumbUrl,
                faviconPath = "https://www.redditstatic.com/shreddit/assets/favicon/192x192.png",
                domain = "reddit.com",
                articleText = sb.toString().takeIf { it.isNotBlank() }
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun extractYouTubeVideoId(urlStr: String): String? {
        val matcher = YOUTUBE_PATTERN.matcher(urlStr)
        return if (matcher.find()) matcher.group(1) else null
    }

    fun fetchYouTubeOEmbed(urlStr: String): Pair<String, String>? {
        return try {
            val encoded = java.net.URLEncoder.encode(urlStr, "UTF-8")
            val oembedUrl = "https://www.youtube.com/oembed?url=$encoded&format=json"
            val conn = URL(oembedUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                conn.inputStream.use { input ->
                    val jsonStr = input.bufferedReader().readText()
                    val json = org.json.JSONObject(jsonStr)
                    val rawTitle = json.optString("title")
                    val author = json.optString("author_name")
                    val thumb = json.optString("thumbnail_url")
                    val fullTitle = if (author.isNotEmpty() && !rawTitle.contains(author, ignoreCase = true)) {
                        "$author - $rawTitle"
                    } else {
                        rawTitle
                    }
                    if (fullTitle.isNotBlank()) Pair(fullTitle, thumb) else null
                }
            } else null
        } catch (_: Exception) {
            null
        }
    }

    suspend fun scrape(context: Context, targetUrl: String): ScrapedMetadata = withContext(Dispatchers.IO) {
        val domain = extractDomain(targetUrl)
        var title: String? = null
        var description: String? = null
        var imageUrl: String? = null
        var faviconUrl: String? = null
        var articleText: String? = null

        // Check for Reddit first (handles post selftext, top comments, high-res preview)
        if (isReddit(targetUrl)) {
            val redditData = fetchRedditData(targetUrl)
            if (redditData != null) {
                val thumbPath = if (!redditData.thumbnailPath.isNullOrBlank()) {
                    downloadImage(context, redditData.thumbnailPath)
                } else null
                val favPath = if (!redditData.faviconPath.isNullOrBlank()) {
                    downloadImage(context, redditData.faviconPath)
                } else null
                return@withContext redditData.copy(
                    thumbnailPath = thumbPath,
                    faviconPath = favPath
                )
            }
        }

        // Check for YouTube: Use official oEmbed first for exact song name, band & thumbnail
        if (isYouTube(targetUrl)) {
            val ytData = fetchYouTubeOEmbed(targetUrl)
            if (ytData != null) {
                title = ytData.first
                imageUrl = ytData.second.ifBlank {
                    val videoId = extractYouTubeVideoId(targetUrl)
                    if (videoId != null) "https://img.youtube.com/vi/$videoId/hqdefault.jpg" else null
                }
                faviconUrl = "https://www.youtube.com/favicon.ico"
            } else {
                val videoId = extractYouTubeVideoId(targetUrl)
                if (videoId != null) {
                    imageUrl = "https://img.youtube.com/vi/$videoId/hqdefault.jpg"
                }
                faviconUrl = "https://www.youtube.com/favicon.ico"
            }
        }

        try {
            val html = if (isYouTube(targetUrl)) "" else fetchHtml(targetUrl)
            if (html.isNotEmpty()) {
                title = extractMeta(html, "og:title")
                    ?: extractMeta(html, "twitter:title")
                    ?: extractTitleTag(html)

                description = extractMeta(html, "og:description")
                    ?: extractMeta(html, "twitter:description")
                    ?: extractMetaName(html, "description")

                if (imageUrl.isNullOrBlank()) {
                    imageUrl = extractMeta(html, "og:image")
                        ?: extractMeta(html, "twitter:image")
                }

                faviconUrl = extractFavicon(html, targetUrl)
                    ?: "https://$domain/favicon.ico"

                articleText = extractCleanArticleText(html) ?: description?.takeIf { it.isNotBlank() }
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

    private fun isPublicUrl(urlStr: String): Boolean {
        return try {
            val uri = URI(urlStr)
            val scheme = uri.scheme?.lowercase() ?: return false
            if (scheme != "http" && scheme != "https") return false
            val host = uri.host ?: return false

            val inetAddress = InetAddress.getByName(host)
            if (inetAddress.isLoopbackAddress ||
                inetAddress.isSiteLocalAddress ||
                inetAddress.isLinkLocalAddress ||
                inetAddress.isAnyLocalAddress ||
                inetAddress.isMulticastAddress
            ) {
                return false
            }

            val ip = inetAddress.hostAddress
            if (ip != null) {
                if (ip.startsWith("127.") || ip.startsWith("10.") || ip.startsWith("0.") ||
                    ip.startsWith("169.254.") || ip.startsWith("192.168.") ||
                    ip == "::1" || ip == "0:0:0:0:0:0:0:1"
                ) {
                    return false
                }
                if (ip.startsWith("172.")) {
                    val secondOctet = ip.substringAfter('.').substringBefore('.').toIntOrNull()
                    if (secondOctet != null && secondOctet in 16..31) return false
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun fetchHtml(urlStr: String): String {
        var currentUrl = urlStr
        var redirects = 0
        while (redirects < 5) {
            if (!isPublicUrl(currentUrl)) return ""

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
            val href = matcher.group(1) ?: return null
            return resolveUrl(baseUrl, href)
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
        if (!isPublicUrl(imgUrl)) return null

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
        // Strip out scripts, styles, navigations, footers, headers, forms, etc.
        val cleaned = html
            .replace(Regex("""<script[^>]*>.*?</script>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")
            .replace(Regex("""<style[^>]*>.*?</style>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")
            .replace(Regex("""<nav[^>]*>.*?</nav>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")
            .replace(Regex("""<header[^>]*>.*?</header>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")
            .replace(Regex("""<footer[^>]*>.*?</footer>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")
            .replace(Regex("""<aside[^>]*>.*?</aside>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")
            .replace(Regex("""<form[^>]*>.*?</form>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")
            .replace(Regex("""<noscript[^>]*>.*?</noscript>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")
            .replace(Regex("""<svg[^>]*>.*?</svg>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")
            .replace(Regex("""<button[^>]*>.*?</button>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")

        // Check if <article> or <main> container exists
        val containerMatcher = Pattern.compile("""<(?:article|main)[^>]*>(.*?)</(?:article|main)>""", Pattern.CASE_INSENSITIVE or Pattern.DOTALL).matcher(cleaned)
        var targetHtml = if (containerMatcher.find()) containerMatcher.group(1) ?: cleaned else cleaned

        // Extract paragraphs and headings
        val pPattern = Pattern.compile("""<(?:p|h[1-6]|blockquote)[^>]*>(.*?)</(?:p|h[1-6]|blockquote)>""", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
        var matcher = pPattern.matcher(targetHtml)
        val paragraphs = mutableListOf<String>()

        while (matcher.find()) {
            val rawP = matcher.group(1) ?: continue
            val textOnly = rawP.replace(Regex("""<[^>]*>"""), " ").trim()
            val unescaped = unescapeHtml(textOnly).replace(Regex("""\s+"""), " ").trim()
            if (unescaped.length >= 25 && !paragraphs.contains(unescaped)) {
                paragraphs.add(unescaped)
            }
        }

        // If very little found inside container, fall back to searching full cleaned document
        if (paragraphs.size < 2 && targetHtml != cleaned) {
            matcher = pPattern.matcher(cleaned)
            while (matcher.find()) {
                val rawP = matcher.group(1) ?: continue
                val textOnly = rawP.replace(Regex("""<[^>]*>"""), " ").trim()
                val unescaped = unescapeHtml(textOnly).replace(Regex("""\s+"""), " ").trim()
                if (unescaped.length >= 25 && !paragraphs.contains(unescaped)) {
                    paragraphs.add(unescaped)
                }
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
