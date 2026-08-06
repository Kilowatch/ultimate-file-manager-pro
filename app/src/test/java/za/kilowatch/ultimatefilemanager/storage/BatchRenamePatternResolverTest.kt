package za.kilowatch.ultimatefilemanager.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BatchRenamePatternResolverTest {

    private val sampleFile = BatchRenameItem(
        name = "vacation_photo",
        extension = ".jpg",
        isDirectory = false,
        isLocal = true,
        localFile = File("/tmp/vacation_photo.jpg"),
        networkFile = null,
        networkShare = null
    )

    private val sampleFolder = BatchRenameItem(
        name = "Documents",
        extension = "",
        isDirectory = true,
        isLocal = true,
        localFile = File("/tmp/Documents"),
        networkFile = null,
        networkShare = null
    )

    @Test
    fun testSequenceNumberTokens() {
        val res1 = BatchRenamePatternResolver.resolve("img_#", sampleFile, 1)
        assertEquals("img_1", res1)

        val res2 = BatchRenamePatternResolver.resolve("img_###", sampleFile, 5)
        assertEquals("img_005", res2)

        val full1 = BatchRenamePatternResolver.appendExtension(res1, sampleFile, "img_#")
        assertEquals("img_1.jpg", full1) // Preserves original extension at the very end
    }

    @Test
    fun testLegacyPaddingTokenNoParentheses() {
        val res = BatchRenamePatternResolver.resolve("{Padding}_doc", sampleFile, 3, paddingLength = 4)
        assertEquals("0003_doc", res)
    }

    @Test
    fun testNameAndExtensionTokens() {
        val resN = BatchRenamePatternResolver.resolve("\$N", sampleFile, 1)
        val fullN = BatchRenamePatternResolver.appendExtension(resN, sampleFile, "\$N")
        assertEquals("vacation_photo", fullN) // Bare name rule ($N outputs photo without extension)

        val resF = BatchRenamePatternResolver.resolve("backup_\$F_#", sampleFile, 1)
        val fullF = BatchRenamePatternResolver.appendExtension(resF, sampleFile, "backup_\$F_#")
        assertEquals("backup_vacation_photo_1.jpg", fullF) // Extension placed at the VERY END!

        val resE = BatchRenamePatternResolver.resolve("\$N_custom.\$E", sampleFile, 1)
        val fullE = BatchRenamePatternResolver.appendExtension(resE, sampleFile, "\$N_custom.\$E")
        assertEquals("vacation_photo_custom.jpg", fullE)

        assertTrue(BatchRenamePatternResolver.hasBareNameToken("\$N"))
        assertFalse(BatchRenamePatternResolver.hasBareNameToken("\$F"))
    }

    @Test
    fun testCustomExtensionToggle() {
        val resN = BatchRenamePatternResolver.resolve("\$N", sampleFile, 1)
        val fullCustom = BatchRenamePatternResolver.appendExtension(
            resolvedName = resN,
            item = sampleFile,
            pattern = "\$N",
            useCustomExtension = true,
            customExtension = "csv"
        )
        assertEquals("vacation_photo.csv", fullCustom)
    }

    @Test
    fun testSeparateDateTokensAndCustomOverrides() {
        val resDate = BatchRenamePatternResolver.resolve("file_\$Y_\$M_\$D", sampleFile, 1)
        val fullDate = BatchRenamePatternResolver.appendExtension(resDate, sampleFile, "file_\$Y_\$M_\$D")
        assertTrue(fullDate.matches(Regex("""file_\d{4}_\d{2}_\d{2}\.jpg"""))) // Extension at the end!

        val resCustomDate = BatchRenamePatternResolver.resolve(
            pattern = "",
            item = sampleFile,
            counter = 1,
            useYear = true,
            useMonth = true,
            useDay = true,
            customYear = "2025",
            customMonth = "12",
            customDay = "25"
        )
        val fullCustomDate = BatchRenamePatternResolver.appendExtension(resCustomDate, sampleFile, "")
        assertEquals("20251225.jpg", fullCustomDate)

        val resMonthOption = BatchRenamePatternResolver.resolve(
            pattern = "\$F",
            item = sampleFile,
            counter = 1,
            useMonth = true,
            customMonth = "08"
        )
        val fullMonthOption = BatchRenamePatternResolver.appendExtension(resMonthOption, sampleFile, "\$F")
        assertEquals("vacation_photo08.jpg", fullMonthOption) // Direct append without underscore!
    }

    @Test
    fun testCaseTransformation() {
        val resUpper = BatchRenamePatternResolver.resolve("\$F", sampleFile, 1, hasUpper = true)
        val fullUpper = BatchRenamePatternResolver.appendExtension(resUpper, sampleFile, "\$F", hasUpper = true)
        assertEquals("VACATION_PHOTO.JPG", fullUpper) // Entire filename (including extension) uppercased without $U in pattern text

        val resLower = BatchRenamePatternResolver.resolve("\$F", sampleFile, 1, hasLower = true)
        val fullLower = BatchRenamePatternResolver.appendExtension(resLower, sampleFile, "\$F", hasLower = true)
        assertEquals("vacation_photo.jpg", fullLower)
    }

    @Test
    fun testReplaceTextAndWith() {
        val res = BatchRenamePatternResolver.resolve(
            pattern = "\$N",
            item = sampleFile,
            counter = 1,
            replaceText = "photo",
            replaceWith = "pic"
        )
        assertEquals("vacation_pic", res)

        val resCaseInsensitive = BatchRenamePatternResolver.resolve(
            pattern = "\$N",
            item = sampleFile,
            counter = 1,
            replaceText = "PHOTO",
            replaceWith = "pic"
        )
        assertEquals("vacation_pic", resCaseInsensitive) // Matching "PHOTO" against "photo" case-insensitively!
    }
}
