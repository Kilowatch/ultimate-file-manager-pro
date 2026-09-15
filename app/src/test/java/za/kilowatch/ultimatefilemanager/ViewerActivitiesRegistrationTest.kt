package za.kilowatch.ultimatefilemanager

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Registration check for [UfmApplication.VIEWER_ACTIVITIES].
 *
 * The release phase closes viewers by class before it issues an unmount, because `vold` scans
 * `/proc/<pid>/fd` and `/proc/<pid>/maps` and `SIGINT`s anything still holding a reference —
 * including the process that asked for the unmount. A viewer missing from that set is therefore
 * a false negative, and one false negative is enough to kill the app with no Java stack trace.
 *
 * This is not hypothetical: `ZipViewerActivity` is reachable from `FileViewerRouter`'s `"zip"`
 * branch and was missing from the set until this test was written.
 *
 * Both directions are asserted, so a deleted viewer cannot linger in the set either.
 */
class ViewerActivitiesRegistrationTest {

    private companion object {
        const val APP_PACKAGE = "za.kilowatch.ultimatefilemanager"
        const val VIEWER_PACKAGE = "$APP_PACKAGE.viewer."
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }

    @Test
    fun everyViewerActivityInTheManifestIsRegistered() {
        val declared = declaredViewerActivityNames()

        assertTrue(
            "No viewer activities were found in the manifest, so this test is not actually " +
                "reading it — fix the manifest lookup rather than trusting a green result.",
            declared.isNotEmpty()
        )

        val registered = UfmApplication.VIEWER_ACTIVITIES.map { it.simpleName }.toSet()

        val missing = declared - registered
        assertTrue(
            "Viewer activities missing from UfmApplication.VIEWER_ACTIVITIES: $missing. " +
                "Ejecting a volume while one of these is open would leave its descriptors and " +
                "mappings for vold to find, which SIGINTs the process.",
            missing.isEmpty()
        )

        val stale = registered - declared
        assertTrue(
            "UfmApplication.VIEWER_ACTIVITIES lists activities that are not in the manifest: " +
                "$stale. Either they were deleted (drop them from the set) or they are declared " +
                "in a manifest this test does not read.",
            stale.isEmpty()
        )
    }

    /**
     * Simple names of every `<activity>` in a `.viewer` package declared by any manifest in the
     * module. Flavour manifests are included because a component may be declared in more than
     * one of them.
     */
    private fun declaredViewerActivityNames(): Set<String> {
        val result = mutableSetOf<String>()
        manifestFiles().forEach { manifest ->
            val document = DocumentBuilderFactory.newInstance()
                .apply { isNamespaceAware = true }
                .newDocumentBuilder()
                .parse(manifest)

            val activities = document.getElementsByTagName("activity")
            for (index in 0 until activities.length) {
                val element = activities.item(index)
                val name = element.attributes?.getNamedItemNS(ANDROID_NS, "name")?.nodeValue
                    ?: continue
                val qualified =
                    if (name.startsWith(".")) "$APP_PACKAGE$name" else name
                if (qualified.startsWith(VIEWER_PACKAGE)) {
                    result.add(qualified.removePrefix(VIEWER_PACKAGE))
                }
            }
        }
        return result
    }

    /**
     * Every `AndroidManifest.xml` under the module's source sets.
     *
     * The working directory of a JVM unit test is the module directory (`app/`), but that is a
     * Gradle implementation detail rather than a guarantee — so walk up a few levels looking for
     * both `src` and `app/src` before giving up with a message that says what went wrong.
     */
    private fun manifestFiles(): List<File> {
        val candidates = generateSequence(File("").absoluteFile) { it.parentFile }
            .take(4)
            .flatMap { base ->
                listOf(base.resolve("app/src"), base.resolve("src"))
            }
            .map { sourceRoot ->
                sourceRoot.listFiles { file -> file.isDirectory }
                    ?.mapNotNull { flavour -> flavour.resolve("AndroidManifest.xml").takeIf(File::isFile) }
                    .orEmpty()
            }
            .firstOrNull { it.isNotEmpty() }

        assertTrue(
            "Could not locate any AndroidManifest.xml under app/src/* from " +
                "${File("").absolutePath}. This test cannot verify the viewer registration " +
                "without it.",
            candidates != null
        )
        return candidates!!
    }
}
