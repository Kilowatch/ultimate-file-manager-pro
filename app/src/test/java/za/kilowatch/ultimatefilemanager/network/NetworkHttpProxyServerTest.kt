package za.kilowatch.ultimatefilemanager.network

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class NetworkHttpProxyServerTest {

    private lateinit var testShare: NetworkShare

    private class FakeRandomAccessFile(
        override val size: Long,
        private val fillByte: Byte = 0x41 // 'A'
    ) : IRandomAccessFile {
        var readCalls = 0
        var closeCalls = 0
        @Volatile var isClosed = false

        override fun read(offset: Long, buffer: ByteArray, length: Int): Int {
            if (isClosed) throw java.io.IOException("File closed")
            readCalls++
            if (offset >= size) return -1
            val available = (size - offset).coerceAtMost(length.toLong()).toInt()
            buffer.fill(fillByte, 0, available)
            return available
        }

        override fun write(offset: Long, buffer: ByteArray, length: Int): Int = length

        override fun close() {
            isClosed = true
            closeCalls++
        }
    }

    @Before
    fun setUp() {
        testShare = NetworkShare(
            id = "1",
            type = ShareType.SMB,
            name = "TestShare",
            host = "192.168.1.100",
            remotePath = "share"
        )
        NetworkHttpProxyServer.start()
    }

    @After
    fun tearDown() {
        NetworkHttpProxyServer.handleFactory = NetworkHttpProxyServer::openHandleForSession
        NetworkHttpProxyServer.stop()
    }

    // ── parseRange normalization tests ────────────────────────────────────────

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

    // ── HTTP streaming tests ──────────────────────────────────────────────────

    @Test
    fun streamingRangeRequestReturns206AndExpectedContent() {
        val fakeHandle = FakeRandomAccessFile(size = 10_000L, fillByte = 'Z'.code.toByte())
        NetworkHttpProxyServer.handleFactory = { fakeHandle }

        val urlString = NetworkHttpProxyServer.register(testShare, "movie.mp4", "video/mp4", 10_000L)
        val url = URL(urlString)

        val conn = url.openConnection() as HttpURLConnection
        conn.setRequestProperty("Range", "bytes=1000-1999")
        conn.connect()

        assertEquals(206, conn.responseCode)
        assertEquals("video/mp4", conn.contentType)
        assertEquals("1000", conn.getHeaderField("Content-Length"))
        assertEquals("bytes 1000-1999/10000", conn.getHeaderField("Content-Range"))
        assertEquals("bytes", conn.getHeaderField("Accept-Ranges"))

        val data = conn.inputStream.readBytes()
        assertEquals(1000, data.size)
        assertTrue(data.all { it == 'Z'.code.toByte() })
        conn.disconnect()
    }

    @Test
    fun newRangeRequestAbortsSupersededInFlightStream() {
        val fakeHandle = FakeRandomAccessFile(size = 10_000_000L, fillByte = 'X'.code.toByte())
        NetworkHttpProxyServer.handleFactory = { fakeHandle }

        val urlString = NetworkHttpProxyServer.register(testShare, "bigmovie.mp4", "video/mp4", 10_000_000L)
        val url = URL(urlString)
        val port = url.port
        val path = url.file

        // Stream 1: connect via raw socket and request bytes=0-
        val socket1 = Socket("127.0.0.1", port)
        val out1 = socket1.getOutputStream()
        val in1 = socket1.getInputStream()

        out1.write("GET $path HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nRange: bytes=0-\r\n\r\n".toByteArray(Charsets.US_ASCII))
        out1.flush()

        // Read the headers and initial chunk from socket 1
        val reader1 = BufferedReader(InputStreamReader(in1))
        val statusLine1 = reader1.readLine()
        assertEquals("HTTP/1.1 206 Partial Content", statusLine1)
        while (true) {
            val line = reader1.readLine()
            if (line.isNullOrBlank()) break
        }

        // Read a few bytes from socket 1 so it is actively streaming
        val firstChunk = ByteArray(1024)
        val n1 = in1.read(firstChunk)
        assertTrue(n1 > 0)

        // Stream 2: user seeks! Connect via raw socket 2 and request bytes=5000000-
        val socket2 = Socket("127.0.0.1", port)
        val out2 = socket2.getOutputStream()
        val in2 = socket2.getInputStream()

        out2.write("GET $path HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nRange: bytes=5000000-\r\n\r\n".toByteArray(Charsets.US_ASCII))
        out2.flush()

        val reader2 = BufferedReader(InputStreamReader(in2))
        val statusLine2 = reader2.readLine()
        assertEquals("HTTP/1.1 206 Partial Content", statusLine2)

        // Verify that socket 1 was closed / aborted by the new seek request
        // Reading from socket 1 should return EOF (-1) or throw SocketException
        var socket1Terminated = false
        try {
            val buf = ByteArray(1024)
            var totalRead = 0
            while (totalRead < 5 * 1024 * 1024) {
                val r = in1.read(buf)
                if (r < 0) {
                    socket1Terminated = true
                    break
                }
                totalRead += r
            }
        } catch (_: Exception) {
            socket1Terminated = true
        }

        assertTrue("Socket 1 must be aborted/closed when a superseding seek arrives", socket1Terminated)

        socket1.close()
        socket2.close()
    }

    @Test
    fun idleHandleClosesAndReopensTransparently() {
        var openCount = 0
        var lastFake: FakeRandomAccessFile? = null
        NetworkHttpProxyServer.handleFactory = {
            openCount++
            FakeRandomAccessFile(size = 5000L).also { lastFake = it }
        }

        val urlString = NetworkHttpProxyServer.register(testShare, "movie.mp4", "video/mp4", 5000L)
        val url = URL(urlString)

        // Request 1: fetch bytes
        val conn1 = url.openConnection() as HttpURLConnection
        conn1.setRequestProperty("Range", "bytes=0-99")
        assertEquals(206, conn1.responseCode)
        conn1.inputStream.readBytes()
        conn1.disconnect()

        assertEquals("Handle should be opened on first request", 1, openCount)
        val firstHandle = lastFake
        assertNotNull(firstHandle)

        // Find session
        val uuid = url.path.trimStart('/').substringBefore('/')
        val sessionField = NetworkHttpProxyServer::class.java.getDeclaredField("sessions")
        sessionField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val sessions = sessionField.get(NetworkHttpProxyServer) as Map<String, NetworkHttpProxyServer.Session>
        val session = sessions[uuid]
        assertNotNull(session)

        // Simulate idle timeout expiring
        session!!.scheduleIdleHandleCloseLocked(10L)
        Thread.sleep(100L)

        // Handle should now be closed to reclaim server resources
        assertTrue("Handle should be closed when idle", firstHandle!!.isClosed)
        assertNull("Session handle reference should be null", session.handle)

        // Request 2: new request arrives (unpause/seek) -> handle reopens transparently!
        val conn2 = url.openConnection() as HttpURLConnection
        conn2.setRequestProperty("Range", "bytes=100-199")
        assertEquals(206, conn2.responseCode)
        val data2 = conn2.inputStream.readBytes()
        assertEquals(100, data2.size)
        conn2.disconnect()

        assertEquals("Handle should reopen transparently on subsequent request", 2, openCount)
        assertNotNull("New handle should be present in session", session.handle)
    }
}
