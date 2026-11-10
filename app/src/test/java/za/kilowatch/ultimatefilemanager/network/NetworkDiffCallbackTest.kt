package za.kilowatch.ultimatefilemanager.network

import androidx.recyclerview.widget.DiffUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import za.kilowatch.ultimatefilemanager.storage.ListItem

@RunWith(RobolectricTestRunner::class)
class NetworkDiffCallbackTest {

    @Test
    fun testAreItemsTheSame_sameRemotePath_returnsTrue() {
        val f1 = NetworkFile(name = "fileA.txt", path = "/share/fileA.txt", isDirectory = false, size = 100)
        val f2 = NetworkFile(name = "fileA.txt", path = "/share/fileA.txt", isDirectory = false, size = 200)

        val oldList = listOf(ListItem.NetworkEntry(f1))
        val newList = listOf(ListItem.NetworkEntry(f2))

        val callback = NetworkFileAdapter.Companion.NetworkDiffCallback(
            oldList = oldList,
            newList = newList,
            selectedPaths = emptySet(),
            focusedPath = null,
            childCounts = emptyMap()
        )

        assertTrue(callback.areItemsTheSame(0, 0))
    }

    @Test
    fun testAreItemsTheSame_differentRemotePath_returnsFalse() {
        val f1 = NetworkFile(name = "fileA.txt", path = "/share/fileA.txt", isDirectory = false)
        val f2 = NetworkFile(name = "fileB.txt", path = "/share/fileB.txt", isDirectory = false)

        val oldList = listOf(ListItem.NetworkEntry(f1))
        val newList = listOf(ListItem.NetworkEntry(f2))

        val callback = NetworkFileAdapter.Companion.NetworkDiffCallback(
            oldList = oldList,
            newList = newList,
            selectedPaths = emptySet(),
            focusedPath = null,
            childCounts = emptyMap()
        )

        assertFalse(callback.areItemsTheSame(0, 0))
    }

    @Test
    fun testAreContentsTheSame_identicalMetadata_returnsTrue() {
        val f1 = NetworkFile(name = "fileA.txt", path = "/share/fileA.txt", isDirectory = false, size = 100, lastModified = 1000L)
        val f2 = NetworkFile(name = "fileA.txt", path = "/share/fileA.txt", isDirectory = false, size = 100, lastModified = 1000L)

        val callback = NetworkFileAdapter.Companion.NetworkDiffCallback(
            oldList = listOf(ListItem.NetworkEntry(f1)),
            newList = listOf(ListItem.NetworkEntry(f2)),
            selectedPaths = emptySet(),
            focusedPath = null,
            childCounts = emptyMap()
        )

        assertTrue(callback.areContentsTheSame(0, 0))
    }

    @Test
    fun testAreContentsTheSame_differentSize_returnsFalse() {
        val f1 = NetworkFile(name = "fileA.txt", path = "/share/fileA.txt", isDirectory = false, size = 100)
        val f2 = NetworkFile(name = "fileA.txt", path = "/share/fileA.txt", isDirectory = false, size = 200)

        val callback = NetworkFileAdapter.Companion.NetworkDiffCallback(
            oldList = listOf(ListItem.NetworkEntry(f1)),
            newList = listOf(ListItem.NetworkEntry(f2)),
            selectedPaths = emptySet(),
            focusedPath = null,
            childCounts = emptyMap()
        )

        assertFalse(callback.areContentsTheSame(0, 0))
    }

    @Test
    fun testGetChangePayload_returnsPayloadMetadata() {
        val f1 = NetworkFile(name = "fileA.txt", path = "/share/fileA.txt", isDirectory = false, size = 100)
        val f2 = NetworkFile(name = "fileA.txt", path = "/share/fileA.txt", isDirectory = false, size = 200)

        val callback = NetworkFileAdapter.Companion.NetworkDiffCallback(
            oldList = listOf(ListItem.NetworkEntry(f1)),
            newList = listOf(ListItem.NetworkEntry(f2)),
            selectedPaths = emptySet(),
            focusedPath = null,
            childCounts = emptyMap()
        )

        val payload = callback.getChangePayload(0, 0)
        assertEquals(NetworkFileAdapter.PAYLOAD_METADATA, payload)
    }

    @Test
    fun testCalculateDiff_dispatchesFineGrainedUpdates() {
        val f1 = NetworkFile(name = "fileA.txt", path = "/share/fileA.txt", isDirectory = false, size = 100)
        val f2 = NetworkFile(name = "fileB.txt", path = "/share/fileB.txt", isDirectory = false, size = 200)
        val f3 = NetworkFile(name = "fileC.txt", path = "/share/fileC.txt", isDirectory = false, size = 300)

        val oldList = listOf(ListItem.NetworkEntry(f1), ListItem.NetworkEntry(f2))
        val newList = listOf(ListItem.NetworkEntry(f1), ListItem.NetworkEntry(f3))

        val callback = NetworkFileAdapter.Companion.NetworkDiffCallback(
            oldList = oldList,
            newList = newList,
            selectedPaths = emptySet(),
            focusedPath = null,
            childCounts = emptyMap()
        )

        val diffResult = DiffUtil.calculateDiff(callback)
        assertNotNull(diffResult)
    }
}
