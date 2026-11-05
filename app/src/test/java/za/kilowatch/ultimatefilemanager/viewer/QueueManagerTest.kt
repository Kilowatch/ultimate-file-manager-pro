package za.kilowatch.ultimatefilemanager.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class QueueManagerTest {

    @Test
    fun testEmptyQueueSafety() {
        val qm = QueueManager()
        assertEquals(0, qm.size)
        assertTrue(qm.isEmpty)
        assertEquals(0, qm.currentIndex)
        assertNull(qm.currentItem)
        assertNull(qm.get(0))
        assertNull(qm.get(458))
        assertNull(qm.nextIndex(false))
        assertNull(qm.prevIndex(false))
    }

    @Test
    fun testIndexOutOfBoundsClamping() {
        val qm = QueueManager()
        val items = (0 until 250).map { QueueItem(path = "/storage/file_$it.mp4") }
        qm.setQueue(items, 100)
        assertEquals(250, qm.size)
        assertEquals(100, qm.currentIndex)
        assertNotNull(qm.currentItem)

        // Attempting to set currentIndex out of bounds should clamp to max valid index (249)
        qm.currentIndex = 458
        assertEquals(249, qm.currentIndex)
        assertNotNull(qm.currentItem)
        assertEquals("/storage/file_249.mp4", qm.currentItem?.path)

        // Negative index should clamp to 0
        qm.currentIndex = -10
        assertEquals(0, qm.currentIndex)
        assertEquals("/storage/file_0.mp4", qm.currentItem?.path)

        // setCurrentIndex with invalid index returns false and does not change index
        assertFalse(qm.setCurrentIndex(458))
        assertEquals(0, qm.currentIndex)

        // Direct get with out-of-bounds index returns null without throwing
        assertNull(qm.get(458))
        assertNull(qm.get(-1))
    }

    @Test
    fun testNullElementSafety() {
        val qm = QueueManager()
        val itemsWithNull = listOf<QueueItem?>(
            QueueItem(path = "/storage/file_0.mp4"),
            null,
            QueueItem(path = "/storage/file_1.mp4")
        )
        @Suppress("UNCHECKED_CAST")
        qm.setQueue(itemsWithNull as List<QueueItem>, 0)

        assertEquals(2, qm.size)
        assertEquals(2, qm.queue.size)
        assertEquals("/storage/file_0.mp4", qm.get(0)?.path)
        assertEquals("/storage/file_1.mp4", qm.get(1)?.path)

        @Suppress("UNCHECKED_CAST")
        qm.addToQueue(listOf(null, QueueItem(path = "/storage/file_2.mp4")) as List<QueueItem>)
        assertEquals(3, qm.size)

        @Suppress("UNCHECKED_CAST")
        qm.playNext(listOf(null, QueueItem(path = "/storage/file_next.mp4")) as List<QueueItem>)
        assertEquals(4, qm.size)
        assertTrue(qm.queue.none { it == null })
    }

    @Test
    fun testReplaceItem() {
        val qm = QueueManager()
        val items = (0 until 5).map { QueueItem(path = "/storage/track_$it.mp3") }
        qm.setQueue(items, 2)

        val updated = QueueItem(path = "/storage/track_2.mp3", title = "Track 2 New Title", duration = 12345L)
        qm.replaceItem(2, updated)

        assertEquals("Track 2 New Title", qm.currentItem?.title)
        assertEquals(12345L, qm.currentItem?.duration)

        // Replacing out of bounds should be a safe no-op
        qm.replaceItem(999, QueueItem(path = "/invalid"))
        assertEquals(5, qm.size)
    }

    @Test
    fun testConcurrentReadsAndMutationsNeverThrow() {
        val qm = QueueManager()
        val initialItems = (0 until 500).map { QueueItem(path = "/storage/media_$it.mkv") }
        qm.setQueue(initialItems, 458)

        val threadCount = 8
        val executor = Executors.newFixedThreadPool(threadCount)
        val readyLatch = CountDownLatch(threadCount)
        val startLatch = CountDownLatch(1)
        val running = AtomicBoolean(true)
        val exceptionRef = AtomicReference<Throwable?>(null)

        // 4 reader threads querying currentItem, currentIndex, and get()
        for (i in 0 until 4) {
            executor.execute {
                readyLatch.countDown()
                startLatch.await()
                try {
                    while (running.get()) {
                        val item = qm.currentItem
                        val idx = qm.currentIndex
                        val getVal = qm.get(idx)
                        val size = qm.size
                        if (item != null) {
                            assertNotNull(item.path)
                        }
                    }
                } catch (t: Throwable) {
                    exceptionRef.compareAndSet(null, t)
                }
            }
        }

        // 4 writer threads mutating queue (setQueue, replaceItem, removeAt, addToQueue)
        for (i in 0 until 4) {
            executor.execute {
                readyLatch.countDown()
                startLatch.await()
                var counter = 0
                try {
                    while (running.get()) {
                        counter++
                        when (counter % 4) {
                            0 -> {
                                val newItems = (0 until 250).map { QueueItem(path = "/storage/sub_$it.mp4") }
                                qm.setQueue(newItems, (counter % 250))
                            }
                            1 -> {
                                val currentIdx = qm.currentIndex
                                qm.replaceItem(currentIdx, QueueItem(path = "/storage/replaced_$counter.mp4", duration = counter * 1000L))
                            }
                            2 -> {
                                val nextIdx = qm.nextIndex(false)
                                if (nextIdx != null) qm.currentIndex = nextIdx
                            }
                            3 -> {
                                val fullItems = (0 until 500).map { QueueItem(path = "/storage/full_$it.mp4") }
                                qm.setQueue(fullItems, 458)
                            }
                        }
                    }
                } catch (t: Throwable) {
                    exceptionRef.compareAndSet(null, t)
                }
            }
        }

        readyLatch.await(5, TimeUnit.SECONDS)
        startLatch.countDown()

        Thread.sleep(1000)
        running.set(false)
        executor.shutdown()
        executor.awaitTermination(5, TimeUnit.SECONDS)

        val err = exceptionRef.get()
        assertNull("Concurrent reads and mutations threw an unexpected exception: ${err?.message}", err)
    }
}
