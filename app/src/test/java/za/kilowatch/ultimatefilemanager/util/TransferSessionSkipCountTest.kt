package za.kilowatch.ultimatefilemanager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * Tests for the skip channel added by T009 (FR-15).
 *
 * Before this, a file the user was never told about was recorded as a **success** — every engine
 * did `session.noteSuccess(); return` on a skip (`storage/LocalPasteEngine.kt:139/234/303`,
 * `network/NetworkPasteEngine.kt:178/320/411/519`). An exclusion is neither a success nor a
 * failure: the file was never attempted, and the summary has to say so.
 */
@RunWith(RobolectricTestRunner::class)
class TransferSessionSkipCountTest {

    private fun session() = TransferManager.SessionImpl(1L, "Copy", isExtract = false)

    @Test
    fun `an excluded file is counted as skipped and not as successful`() {
        val session = session()

        session.noteSkipped("too large for this device")

        assertEquals(1, session.skippedCount)
        assertEquals(0, session.successCount)
        assertEquals(0, session.failCount)
    }

    @Test
    fun `a transferred file is counted as successful and not as skipped`() {
        val session = session()

        session.noteSuccess()

        assertEquals(1, session.successCount)
        assertEquals(0, session.skippedCount)
    }

    @Test
    fun `skips and successes are counted independently`() {
        val session = session()

        repeat(2) { session.noteSuccess() }
        repeat(3) { session.noteSkipped("too large") }

        assertEquals(2, session.successCount)
        assertEquals(3, session.skippedCount)
        assertEquals(0, session.failCount)
    }

    @Test
    fun `a failure is counted separately from a skip`() {
        val session = session()

        session.noteSkipped("too large")
        session.noteFailure("connection reset")

        assertEquals(0, session.successCount)
        assertEquals(1, session.skippedCount)
        assertEquals(1, session.failCount)
        assertEquals("connection reset", session.lastError)
    }

    @Test
    fun `the first skip reason is kept`() {
        val session = session()

        session.noteSkipped("first reason")
        session.noteSkipped("second reason")

        assertEquals("first reason", session.skipReason)
        assertEquals(2, session.skippedCount)
    }

    @Test
    fun `a blank reason does not displace a real one`() {
        val session = session()

        session.noteSkipped(null)
        session.noteSkipped("   ")
        session.noteSkipped("the real reason")

        assertEquals("the real reason", session.skipReason)
    }

    @Test
    fun `a blank reason cannot leave a stray value behind`() {
        val session = session()

        session.noteSkipped("")
        session.noteSkipped(null)

        assertNull(session.skipReason)
    }

    @Test
    fun `skips recorded from several threads are all counted`() {
        // The engines assess files concurrently on Dispatchers.IO.
        val session = session()
        val threads = 8
        val perThread = 250
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)

        try {
            val futures = (0 until threads).map { t ->
                pool.submit {
                    start.await()
                    repeat(perThread) { session.noteSkipped("worker-$t") }
                }
            }
            start.countDown()
            futures.forEach { it.get() }
        } finally {
            pool.shutdownNow()
        }

        assertEquals(threads * perThread, session.skippedCount)
        assertEquals(threads * perThread, session.successCount + session.failCount + session.skippedCount)
    }

    @Test
    fun `the summary carries the skip count and falls back to the skip reason`() {
        // A batch where everything else transferred still has to tell the user what it left
        // behind, so the skip reason becomes the message when nothing actually failed.
        val session = session()
        session.noteSuccess()
        repeat(3) { session.noteSkipped("3 files are too large for this device") }

        val summary = TransferManager.summaryFrom(session)

        assertEquals(1, summary.successCount)
        assertEquals(3, summary.skippedCount)
        assertEquals("3 files are too large for this device", summary.message)
    }

    @Test
    fun `a failure message outranks the skip reason in the summary`() {
        val session = session()
        session.noteSkipped("3 files are too large for this device")
        session.noteFailure("connection reset")

        val summary = TransferManager.summaryFrom(session)

        assertEquals(1, summary.skippedCount)
        assertEquals("connection reset", summary.message)
    }

    @Test
    fun `a clean transfer reports nothing`() {
        val session = session()
        session.noteSuccess()

        val summary = TransferManager.summaryFrom(session)

        assertEquals(1, summary.successCount)
        assertEquals(0, summary.skippedCount)
        assertNull(summary.message)
    }
}
