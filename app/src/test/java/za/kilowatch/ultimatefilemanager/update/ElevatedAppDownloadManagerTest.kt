package za.kilowatch.ultimatefilemanager.update

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import za.kilowatch.ultimatefilemanager.update.ElevatedAppDownloadManager.ElevatedApp

/**
 * Covers [ElevatedAppDownloadManager.parseRelease] against recorded GitHub payload shapes.
 *
 * These are the branches that decide what a user is actually offered, so the ones that matter most
 * are the ones where the obvious implementation is wrong: a newest-first list whose head is a
 * pre-release (picking index 0 gets the wrong release), and Porter's asset list, which is ordered
 * compat-first (picking the first `.apk` downloads a different application).
 *
 * Fixtures are built with `org.json` rather than hand-written JSON, so that a malformed fixture
 * cannot masquerade as a production failure. Robolectric, not plain JUnit, because `org.json` is a
 * throwing stub in the `android.jar` on a unit-test classpath.
 */
@RunWith(RobolectricTestRunner::class)
class ElevatedAppDownloadManagerTest {

    private val porter = ElevatedApp.PORTER
    private val shizuku = ElevatedApp.SHIZUKU

    // Kotlin cannot import a member off an `object`, so the seam is re-exposed here rather than
    // qualifying every call site.
    private fun parseRelease(app: ElevatedApp, body: String) =
        ElevatedAppDownloadManager.parseRelease(app, body)

    /** One entry of a GitHub `/releases` response. */
    private fun release(
        tag: String,
        prerelease: Boolean = false,
        draft: Boolean = false,
        assets: List<JSONObject> = emptyList(),
        htmlUrl: String? = "https://example.test/$tag",
        body: String = ""
    ): JSONObject = JSONObject().apply {
        put("tag_name", tag)
        put("prerelease", prerelease)
        put("draft", draft)
        if (htmlUrl != null) put("html_url", htmlUrl)
        put("body", body)
        put("assets", JSONArray(assets))
    }

    private fun asset(
        name: String,
        url: String = "https://example.test/$name",
        size: Long = 1024L
    ): JSONObject = JSONObject().apply {
        put("name", name)
        put("browser_download_url", url)
        put("size", size)
    }

    private fun releasesJson(vararg releases: JSONObject) = JSONArray(releases.toList()).toString()

    // ── Stable present ───────────────────────────────────────────────────────

    @Test
    fun `prefers the newest stable even when a newer pre-release heads the list`() {
        // GitHub returns newest-first, so index 0 is the pre-release here. "Prefer stable" has to
        // mean "scan for stable", not "take the first entry".
        val body = releasesJson(
            release("v2.0.0-beta1", prerelease = true, assets = listOf(asset("app-v2.0.0-beta1.apk"))),
            release("v1.9.0", assets = listOf(asset("app-v1.9.0.apk")))
        )

        val info = parseRelease(shizuku, body)

        assertNotNull(info)
        assertEquals("v1.9.0", info!!.tagName)
        assertEquals("1.9.0", info.version)
        assertFalse(info.isPrerelease)
        assertEquals("app-v1.9.0.apk", info.apkName)
    }

    @Test
    fun `version strips a leading v or V from the tag`() {
        val lower = releasesJson(release("v1.2.3", assets = listOf(asset("a.apk"))))
        val upper = releasesJson(release("V1.2.3", assets = listOf(asset("a.apk"))))

        assertEquals("1.2.3", parseRelease(shizuku, lower)!!.version)
        assertEquals("1.2.3", parseRelease(shizuku, upper)!!.version)
    }

    @Test
    fun `html_url falls back to the app's releases page when GitHub omits it`() {
        val body = releasesJson(release("v1.0.0", assets = listOf(asset("a.apk")), htmlUrl = null))

        assertEquals(shizuku.fallbackWebUrl, parseRelease(shizuku, body)!!.htmlUrl)
    }

    // ── Pre-release only (Porter's permanent situation, CR-03) ───────────────

    @Test
    fun `falls back to the newest pre-release and labels it as one`() {
        val body = releasesJson(
            release("v0.3.0-beta", prerelease = true, assets = listOf(asset("porter-v0.3.0-beta.apk"))),
            release("v0.2.0-beta", prerelease = true, assets = listOf(asset("porter-v0.2.0-beta.apk")))
        )

        val info = parseRelease(porter, body)

        assertNotNull(info)
        assertEquals("v0.3.0-beta", info!!.tagName)
        // The caller needs this to say "pre-release" rather than presenting it as a stable build.
        assertTrue(info.isPrerelease)
    }

    @Test
    fun `a draft stable is never offered, even as the only stable entry`() {
        // Draft asset URLs 404 for anyone but the maintainer, so a draft must not be selected —
        // the published pre-release below it is the correct answer.
        val body = releasesJson(
            release("v9.9.9", draft = true, assets = listOf(asset("porter-v9.9.9.apk"))),
            release("v0.1.0-beta", prerelease = true, assets = listOf(asset("porter-v0.1.0-beta.apk")))
        )

        val info = parseRelease(porter, body)

        assertEquals("v0.1.0-beta", info!!.tagName)
        assertTrue(info.isPrerelease)
    }

    @Test
    fun `a draft pre-release is skipped too`() {
        val body = releasesJson(
            release("v0.9.0-beta", draft = true, prerelease = true, assets = listOf(asset("x.apk"))),
            release("v0.8.0-beta", prerelease = true, assets = listOf(asset("porter-v0.8.0-beta.apk")))
        )

        assertEquals("v0.8.0-beta", parseRelease(porter, body)!!.tagName)
    }

    // ── Asset selection ──────────────────────────────────────────────────────

    @Test
    fun `porter picks its own apk, not the compat companion that heads the asset list`() {
        // Porter ships porter-compat-*.apk in the SAME release, listed first. A plain "first .apk"
        // match would download the Compatibility companion instead of the manager app.
        val body = releasesJson(
            release(
                "v0.1.0",
                prerelease = true,
                assets = listOf(
                    asset("porter-compat-v0.1.0.apk", url = "https://example.test/compat.apk", size = 10),
                    asset("porter-v0.1.0.apk", url = "https://example.test/porter.apk", size = 20)
                )
            )
        )

        val info = parseRelease(porter, body)

        assertEquals("porter-v0.1.0.apk", info!!.apkName)
        assertEquals("https://example.test/porter.apk", info.apkUrl)
        assertEquals(20L, info.apkSize)
    }

    @Test
    fun `porter with only a compat apk yields nothing rather than the wrong app`() {
        // Offering nothing sends the user to the releases page (T027's fallback). Offering the
        // compat build would install something UFM does not support, which is worse than a dead end.
        val body = releasesJson(
            release("v0.1.0", prerelease = true, assets = listOf(asset("porter-compat-v0.1.0.apk")))
        )

        assertNull(parseRelease(porter, body))
    }

    @Test
    fun `an app with no asset pattern keeps the legacy first-apk behaviour`() {
        val body = releasesJson(
            release(
                "v13.5.4",
                assets = listOf(
                    asset("shizuku-v13.5.4-release.apk"),
                    asset("shizuku-v13.5.4-debug.apk")
                )
            )
        )

        assertEquals("shizuku-v13.5.4-release.apk", parseRelease(shizuku, body)!!.apkName)
    }

    @Test
    fun `apk matching is case-insensitive`() {
        val body = releasesJson(
            release("v0.1.0", prerelease = true, assets = listOf(asset("PORTER-V0.1.0.APK")))
        )

        assertEquals("PORTER-V0.1.0.APK", parseRelease(porter, body)!!.apkName)
    }

    // ── API failure and malformed responses ──────────────────────────────────

    @Test
    fun `a github error object is not a release list`() {
        // GitHub answers a 404 or a rate-limit with a JSON object, not an array.
        assertNull(parseRelease(porter, """{"message":"Not Found","documentation_url":"https://docs.github.com"}"""))
        assertNull(parseRelease(porter, """{"message":"API rate limit exceeded"}"""))
    }

    @Test
    fun `an empty or truncated body yields nothing`() {
        assertNull(parseRelease(porter, ""))
        assertNull(parseRelease(porter, "["))
        assertNull(parseRelease(porter, "not json at all"))
    }

    @Test
    fun `an empty release list yields nothing`() {
        assertNull(parseRelease(porter, releasesJson()))
    }

    @Test
    fun `a release with no assets yields nothing`() {
        // The APK URL is what the download needs; a release without one cannot be offered.
        assertNull(parseRelease(porter, releasesJson(release("v1.0.0"))))
        assertNull(parseRelease(porter, releasesJson(release("v1.0.0", assets = listOf(asset("notes.txt"))))))
    }

    @Test
    fun `a release with a blank tag is rejected`() {
        assertNull(parseRelease(porter, releasesJson(release("", assets = listOf(asset("porter-v1.apk"))))))
    }

    @Test
    fun `a release whose matching asset has no download url yields nothing`() {
        val body = releasesJson(
            release("v0.1.0", prerelease = true, assets = listOf(asset("porter-v0.1.0.apk", url = "")))
        )

        assertNull(parseRelease(porter, body))
    }
}
