package za.kilowatch.ultimatefilemanager.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatchRenameDiffTest {

    @Test
    fun identicalNamesReturnNull() {
        assertNull(BatchRenameDiff.compute("abc.txt", "abc.txt"))
    }

    @Test
    fun appendedSuffixHighlightsOnlyTheSuffix() {
        val h = BatchRenameDiff.compute("abc", "abcX")
        assertEquals(BatchRenameDiff.Highlight(3, 4), h)
    }

    @Test
    fun prependedPrefixHighlightsOnlyThePrefix() {
        val h = BatchRenameDiff.compute("abc", "Xabc")
        assertEquals(BatchRenameDiff.Highlight(0, 1), h)
    }

    @Test
    fun middleChangeHighlightsTheChangedSegment() {
        val h = BatchRenameDiff.compute("draft.pdf", "final.pdf")
        assertEquals(BatchRenameDiff.Highlight(0, 5), h)
    }

    @Test
    fun commonPrefixAndSuffixNarrowTheHighlight() {
        val h = BatchRenameDiff.compute("report_draft.pdf", "report_final.pdf")
        assertEquals(BatchRenameDiff.Highlight(7, 13), h)
    }

    @Test
    fun fullyRegeneratedNameHighlightsEverything() {
        val h = BatchRenameDiff.compute("abc", "xyz")
        assertEquals(BatchRenameDiff.Highlight(0, 3), h)
    }

    @Test
    fun caseOnlyChangeHighlightsWholeName() {
        val h = BatchRenameDiff.compute("PHOTO.JPG", "photo.jpg")
        assertEquals(BatchRenameDiff.Highlight(0, 10), h)
    }
}
