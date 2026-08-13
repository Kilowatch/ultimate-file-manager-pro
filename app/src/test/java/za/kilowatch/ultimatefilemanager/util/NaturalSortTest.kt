package za.kilowatch.ultimatefilemanager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NaturalSortTest {

    private fun sort(vararg names: String): List<String> =
        names.toList().sortedWith(NaturalSort.order)

    @Test
    fun testBasicNumericOrder() {
        assertEquals(
            listOf("file1.jpg", "file2.jpg", "file10.jpg", "file11.jpg"),
            sort("file10.jpg", "file2.jpg", "file1.jpg", "file11.jpg")
        )
    }

    @Test
    fun testFullMixedSetOrder() {
        // case-insensitive text; numeric 1 < 2; "2" < "002" (fewer leading zeros first)
        assertEquals(
            listOf("file1.jpg", "file2.jpg", "file002.jpg", "file10.jpg", "file11.jpg", "File20.jpg"),
            sort("file11.jpg", "File20.jpg", "file10.jpg", "file002.jpg", "file2.jpg", "file1.jpg")
        )
    }

    @Test
    fun testLeadingZeroTieBreak() {
        assertEquals(
            listOf("file2.jpg", "file02.jpg", "file002.jpg"),
            sort("file002.jpg", "file02.jpg", "file2.jpg")
        )
    }

    @Test
    fun testMultipleNumericGroups() {
        assertEquals(
            listOf("v2_track10.mp3", "v10_track2.mp3"),
            sort("v10_track2.mp3", "v2_track10.mp3")
        )
    }

    @Test
    fun testNoDigitNamesCaseInsensitive() {
        assertEquals(
            listOf("data.txt", "README.md"),
            sort("README.md", "data.txt")
        )
    }

    @Test
    fun testCaseEqualNamesAreTotalAndDeterministic() {
        // Equal under case-insensitive comparison → stable case-sensitive tie-break.
        assertTrue(NaturalSort.naturalCompare("File2.jpg", "file2.jpg") < 0)
        assertTrue(NaturalSort.naturalCompare("file2.jpg", "File2.jpg") > 0)
        assertEquals(
            listOf("File2.jpg", "file2.jpg"),
            sort("file2.jpg", "File2.jpg")
        )
    }

    @Test
    fun testLargeNumbersDoNotOverflow() {
        // 20-digit runs must order correctly without parsing into a fixed-width int.
        assertEquals(
            listOf("x12345678901234567890", "x12345678901234567891"),
            sort("x12345678901234567891", "x12345678901234567890")
        )
        // A 19-digit run sorts before a 20-digit run regardless of digits.
        assertTrue(NaturalSort.naturalCompare("x9999999999999999999", "x10000000000000000000") < 0)
    }

    @Test
    fun testNullAndEmpty() {
        assertEquals(0, NaturalSort.naturalCompare(null, null))
        assertTrue(NaturalSort.naturalCompare(null, "a") < 0)
        assertTrue(NaturalSort.naturalCompare("a", null) > 0)
        assertTrue(NaturalSort.naturalCompare("", "a") < 0)
    }

    @Test
    fun testDescendingMirrorsNaturalOrder() {
        val names = listOf("file1.jpg", "file10.jpg", "file2.jpg", "file11.jpg")
        assertEquals(
            listOf("file11.jpg", "file10.jpg", "file2.jpg", "file1.jpg"),
            names.sortedWith(NaturalSort.order.reversed())
        )
    }

    @Test
    fun testFewerChunksSortsFirst() {
        // "file" is a prefix of "file1"; the shorter name sorts first.
        assertTrue(NaturalSort.naturalCompare("file", "file1") < 0)
    }

    @Test
    fun testMixedChunkTypeFallsBackGracefully() {
        // Digit chunk vs text chunk at the same position → case-insensitive string fallback, no crash.
        assertTrue(NaturalSort.naturalCompare("2file", "file") < 0)
    }
}
