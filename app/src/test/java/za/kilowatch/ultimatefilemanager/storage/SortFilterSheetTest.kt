package za.kilowatch.ultimatefilemanager.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import za.kilowatch.ultimatefilemanager.network.NetworkFile
import java.io.File
import java.util.Calendar

class SortFilterSheetTest {

    @Test
    fun matchesDateFiltersCorrectly() {
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance()

        // Today
        assertTrue(SortFilterSheet.matchesDate(now, SortFilterSheet.DateFilter.TODAY))
        assertTrue(SortFilterSheet.matchesDate(now, SortFilterSheet.DateFilter.ANY))

        // Yesterday
        cal.timeInMillis = now
        cal.add(Calendar.DAY_OF_YEAR, -1)
        val yesterday = cal.timeInMillis
        assertTrue(SortFilterSheet.matchesDate(yesterday, SortFilterSheet.DateFilter.YESTERDAY))
        assertFalse(SortFilterSheet.matchesDate(yesterday, SortFilterSheet.DateFilter.TODAY))

        // Previous week
        cal.timeInMillis = now
        cal.add(Calendar.WEEK_OF_YEAR, -1)
        val prevWeek = cal.timeInMillis
        assertTrue(SortFilterSheet.matchesDate(prevWeek, SortFilterSheet.DateFilter.PREVIOUS_WEEK))

        // Previous year
        cal.timeInMillis = now
        cal.add(Calendar.YEAR, -1)
        val prevYear = cal.timeInMillis
        assertTrue(SortFilterSheet.matchesDate(prevYear, SortFilterSheet.DateFilter.PREVIOUS_YEAR))
        assertFalse(SortFilterSheet.matchesDate(prevYear, SortFilterSheet.DateFilter.THIS_YEAR))
    }

    @Test
    fun matchesSizeFiltersCorrectly() {
        // Empty: 0 B
        assertTrue(SortFilterSheet.matchesSize(0L, false, SortFilterSheet.SizeFilter.EMPTY))
        assertFalse(SortFilterSheet.matchesSize(100L, false, SortFilterSheet.SizeFilter.EMPTY))

        // Tiny: 0 - 10 KB
        assertTrue(SortFilterSheet.matchesSize(5 * 1024L, false, SortFilterSheet.SizeFilter.TINY))
        assertFalse(SortFilterSheet.matchesSize(15 * 1024L, false, SortFilterSheet.SizeFilter.TINY))

        // Small: 10 KB - 1 MB
        assertTrue(SortFilterSheet.matchesSize(500 * 1024L, false, SortFilterSheet.SizeFilter.SMALL))
        assertFalse(SortFilterSheet.matchesSize(5 * 1024L, false, SortFilterSheet.SizeFilter.SMALL))

        // Medium: 1 MB - 10 MB
        assertTrue(SortFilterSheet.matchesSize(5 * 1024 * 1024L, false, SortFilterSheet.SizeFilter.MEDIUM))

        // Big: 10 MB - 100 MB
        assertTrue(SortFilterSheet.matchesSize(50 * 1024 * 1024L, false, SortFilterSheet.SizeFilter.BIG))

        // Large: 100 MB - 1 GB
        assertTrue(SortFilterSheet.matchesSize(500 * 1024 * 1024L, false, SortFilterSheet.SizeFilter.LARGE))

        // Huge: > 1 GB
        assertTrue(SortFilterSheet.matchesSize(2L * 1024 * 1024 * 1024L, false, SortFilterSheet.SizeFilter.HUGE))
        assertFalse(SortFilterSheet.matchesSize(500 * 1024 * 1024L, false, SortFilterSheet.SizeFilter.HUGE))

        // Directories always pass size filters except EMPTY when size > 0
        assertTrue(SortFilterSheet.matchesSize(0L, true, SortFilterSheet.SizeFilter.TINY))
    }

    @Test
    fun matchesArchiveFilter() {
        assertTrue(SortFilterSheet.matchesExtension("zip", SortFilterSheet.FilterType.ARCHIVES))
        assertTrue(SortFilterSheet.matchesExtension("rar", SortFilterSheet.FilterType.ARCHIVES))
        assertTrue(SortFilterSheet.matchesExtension("tgz", SortFilterSheet.FilterType.ARCHIVES))
        assertTrue(SortFilterSheet.matchesExtension("tar", SortFilterSheet.FilterType.ARCHIVES))
        assertTrue(SortFilterSheet.matchesExtension("gz", SortFilterSheet.FilterType.ARCHIVES))
        assertFalse(SortFilterSheet.matchesExtension("mp4", SortFilterSheet.FilterType.ARCHIVES))
        assertFalse(SortFilterSheet.matchesExtension("jpg", SortFilterSheet.FilterType.ARCHIVES))
    }

    @Test
    fun sortModeTypeRanksCategoriesCorrectly() {
        val files = listOf(
            File("/tmp/other.xyz"),
            File("/tmp/app.apk"),
            File("/tmp/archive.zip"),
            File("/tmp/doc.pdf"),
            File("/tmp/audio.mp3"),
            File("/tmp/video.mp4"),
            File("/tmp/image.png")
        )

        val state = SortFilterPreferenceManager.SortFilterState(
            sortMode = SortFilterSheet.SortMode.TYPE,
            sortOrder = SortFilterSheet.SortOrder.ASC,
            filterType = SortFilterSheet.FilterType.ALL
        )

        val comparator = SortFilterPreferenceManager.getFileComparator(state, context = null, directoriesFirst = true)
        val sorted = files.sortedWith(comparator)

        val names = sorted.map { it.name }
        assertEquals(
            listOf("image.png", "video.mp4", "audio.mp3", "doc.pdf", "archive.zip", "app.apk", "other.xyz"),
            names
        )
    }

    @Test
    fun networkFileSortModeTypeRanksCategoriesCorrectly() {
        val files = listOf(
            NetworkFile(name = "other.xyz", path = "/tmp/other.xyz", isDirectory = false),
            NetworkFile(name = "app.apk", path = "/tmp/app.apk", isDirectory = false),
            NetworkFile(name = "archive.zip", path = "/tmp/archive.zip", isDirectory = false),
            NetworkFile(name = "doc.pdf", path = "/tmp/doc.pdf", isDirectory = false),
            NetworkFile(name = "audio.mp3", path = "/tmp/audio.mp3", isDirectory = false),
            NetworkFile(name = "video.mp4", path = "/tmp/video.mp4", isDirectory = false),
            NetworkFile(name = "image.png", path = "/tmp/image.png", isDirectory = false)
        )

        val state = SortFilterPreferenceManager.SortFilterState(
            sortMode = SortFilterSheet.SortMode.TYPE,
            sortOrder = SortFilterSheet.SortOrder.ASC,
            filterType = SortFilterSheet.FilterType.ALL
        )

        val comparator = SortFilterPreferenceManager.getNetworkFileComparator(state, context = null, shareId = "test", directoriesFirst = true)
        val sorted = files.sortedWith(comparator)

        val names = sorted.map { it.name }
        assertEquals(
            listOf("image.png", "video.mp4", "audio.mp3", "doc.pdf", "archive.zip", "app.apk", "other.xyz"),
            names
        )
    }

    @Test
    fun sortModeSizeSmallToLargeAndLargeToSmall() {
        val files = listOf(
            NetworkFile(name = "medium.txt", path = "/tmp/medium.txt", isDirectory = false, size = 5000L),
            NetworkFile(name = "huge.txt", path = "/tmp/huge.txt", isDirectory = false, size = 1000000L),
            NetworkFile(name = "tiny.txt", path = "/tmp/tiny.txt", isDirectory = false, size = 50L)
        )

        // Small to Large (ASC)
        val stateAsc = SortFilterPreferenceManager.SortFilterState(
            sortMode = SortFilterSheet.SortMode.SIZE,
            sortOrder = SortFilterSheet.SortOrder.ASC
        )
        val compAsc = SortFilterPreferenceManager.getNetworkFileComparator(stateAsc, context = null, shareId = "test")
        val sortedAsc = files.sortedWith(compAsc).map { it.name }
        assertEquals(listOf("tiny.txt", "medium.txt", "huge.txt"), sortedAsc)

        // Large to Small (DESC)
        val stateDesc = SortFilterPreferenceManager.SortFilterState(
            sortMode = SortFilterSheet.SortMode.SIZE,
            sortOrder = SortFilterSheet.SortOrder.DESC
        )
        val compDesc = SortFilterPreferenceManager.getNetworkFileComparator(stateDesc, context = null, shareId = "test")
        val sortedDesc = files.sortedWith(compDesc).map { it.name }
        assertEquals(listOf("huge.txt", "medium.txt", "tiny.txt"), sortedDesc)
    }
}
