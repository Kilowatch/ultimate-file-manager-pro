package za.kilowatch.ultimatefilemanager.network

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/**
 * Unit tests for the HTTP proxy's per-request handle + bounded-retry logic
 * ([NetworkHttpProxyServer.readChunkWithRetry]) and range normalization.
 * Uses the [NetworkHttpProxyServer.handleFactory] seam so no real sockets or
 * network clients are needed.
 */
@RunWith(RobolectricTestRunner::class)
class NetworkHttpProxyServerTest {

    private lateinit var session: NetworkHttpProxyServer.Session

    /** A fake handle whose read can be programmed to fail a given number of times. */
    private class FakeRandomAccessFile(
        override val size: Long,
        val readResult: Int = -1,
        var failReadsRemaining: Int = 0
    ) : IRandomAccessFile {
        var readCalls: Int = 0
        var closeCalls: Int = 0

        override fun read(offset: Long, buffer: ByteArray, length: Int): Int {
            readCalls++
            if (failReadsRemaining > 0) {
                failReadsRemaining--
                throw IOException("simulated read failure")
            }
            if (readResult >= 0) {
                val n = minOf(readResult, length)
                buffer.fill(0x5A, 0, n)
                return n
            }
            return -1 // EOF
        }

        override fun write(offset: Long, buffer: ByteArray, length: Int): Int = length

        override fun close() {
            closeCalls++
        }
    }

    @Before
    fun setUp() {
        session = NetworkHttpProxyServer.Session(
            share = NetworkShare(),
            path = "/movies/test.mp4",
            mimeType = "video/mp4",
            fileSize = 1000L
        )
    }

    @After
    fun tearDown() {
        // Restore the real handle factory so state doesn't leak between tests.
        NetworkHttpProxyServer.handleFactory = NetworkHttpProxyServer::openHandleForSession
    }

    // ── readChunkWithRetry ─────────────────────────────────────────────────────

    @Test
    fun readSucceedsOnFirstAttempt() {
        val fake = FakeRandomAccessFile(size = 1000L, readResult = 256)
        val buffer = ByteArray(256)

        val result = NetworkHttpProxyServer.readChunkWithRetry(session, fake, 0L, buffer, 256)

        assertEquals(256, result.bytesRead)
        assertEquals(1, fake.readCalls)
        // No handle replacement needed on success
        assertNull(result.newHandle)
    }

    @Test
    fun transientReadFailureReopensFreshHandleAndRecovers() {
        val failing = FakeRandomAccessFile(size = 1000L, readResult = 256, failReadsRemaining = 1)
        val good = FakeRandomAccessFile(size = 1000L, readResult = 256)
        var factoryCalls = 0
        NetworkHttpProxyServer.handleFactory = {
            factoryCalls++
            if (factoryCalls == 1) failing else good
        }
        val buffer = ByteArray(256)

        val result = NetworkHttpProxyServer.readChunkWithRetry(session, failing, 100L, buffer, 256)

        assertEquals(256, result.bytesRead)
        assertTrue("a fresh handle must be opened after the failed read", factoryCalls >= 2)
        assertTrue("the failed handle must be closed", failing.closeCalls >= 1)
        // A new handle was opened — it should be returned as the replacement
        assertNotNull("new handle must be returned after replacement", result.newHandle)
        assertNotSame(failing, result.newHandle)
    }

    @Test
    fun readBudgetExhaustionReturnsMinusOne() {
        val alwaysFails = FakeRandomAccessFile(size = 1000L, failReadsRemaining = Int.MAX_VALUE)
        NetworkHttpProxyServer.handleFactory = { alwaysFails }
        val buffer = ByteArray(256)

        val result = NetworkHttpProxyServer.readChunkWithRetry(session, alwaysFails, 0L, buffer, 256)

        assertEquals(-1, result.bytesRead)
        // The failed handle should have been closed during retries
        assertTrue(alwaysFails.closeCalls >= 1)
    }

    @Test
    fun noHandleReplacementWhenReadSucceedsOnRetryWithSameHandle() {
        // First read succeeds — same handle, no replacement needed
        val fake = FakeRandomAccessFile(size = 1000L, readResult = 256)
        val buffer = ByteArray(256)

        val result = NetworkHttpProxyServer.readChunkWithRetry(session, fake, 0L, buffer, 256)

        // newHandle should be null because the original handle was reused successfully
        assertNull(result.newHandle)
        assertEquals(256, result.bytesRead)
    }

    @Test
    fun factoryReturnsNullOnRetryReturnsMinusOne() {
        // First read fails, then the factory returns null (cannot open new handle)
        val failing = FakeRandomAccessFile(size = 1000L, readResult = 256, failReadsRemaining = 1)
        NetworkHttpProxyServer.handleFactory = { null } // retry factory returns null
        val buffer = ByteArray(256)

        val result = NetworkHttpProxyServer.readChunkWithRetry(session, failing, 0L, buffer, 256)

        assertEquals(-1, result.bytesRead)
    }

    // ── parseRange normalization ──────────────────────────────────────────────

    @Test
    fun parseRangeFullFileWhenNoHeader() {
        assertEquals(0L to 999L, NetworkHttpProxyServer.parseRange(null, 1000L))
    }

    @Test
    fun parseRangeOpenEndedFromOffset() {
        assertEquals(200L to 999L, NetworkHttpProxyServer.parseRange("bytes=200-", 1000L))
    }

    @Test
    fun parseRangeSuffixIsSupported() {
        assertEquals(500L to 999L, NetworkHttpProxyServer.parseRange("bytes=-500", 1000L))
    }

    @Test
    fun parseRangeExactRange() {
        assertEquals(100L to 200L, NetworkHttpProxyServer.parseRange("bytes=100-200", 1000L))
    }

    @Test
    fun parseRangeReversedRangeIsNormalizedToSingleByte() {
        assertEquals(100L to 100L, NetworkHttpProxyServer.parseRange("bytes=100-50", 1000L))
    }

    @Test
    fun parseRangeStartPastEofClampsToLastByte() {
        assertEquals(999L to 999L, NetworkHttpProxyServer.parseRange("bytes=100000-", 1000L))
    }

    @Test
    fun parseRangeBeyondEofExactClamps() {
        assertEquals(990L to 999L, NetworkHttpProxyServer.parseRange("bytes=990-5000", 1000L))
    }
}
