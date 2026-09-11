package za.kilowatch.ultimatefilemanager.network

import org.junit.Assert.*
import org.junit.Test

class SegmentRangeCalculationTest {

    data class Segment(val index: Int, val startOffset: Long, val endOffset: Long, val expectedBytes: Long)

    private fun calculateSegments(totalSize: Long, threads: Int): List<Segment> {
        val actualThreads = threads.coerceIn(2, 8)
        val segmentSize = (totalSize + actualThreads - 1) / actualThreads
        val segments = mutableListOf<Segment>()

        for (index in 0 until actualThreads) {
            val startOffset = index * segmentSize
            if (startOffset >= totalSize) continue
            val endOffset = minOf((index + 1) * segmentSize - 1, totalSize - 1)
            val expectedBytes = endOffset - startOffset + 1
            segments.add(Segment(index, startOffset, endOffset, expectedBytes))
        }
        return segments
    }

    @Test
    fun testEvenlyDivisibleSize() {
        val totalSize = 100_000_000L // 100MB
        val threads = 4
        val segments = calculateSegments(totalSize, threads)

        assertEquals(4, segments.size)
        var currentExpectedOffset = 0L
        var totalBytesCalculated = 0L

        for (seg in segments) {
            assertEquals(currentExpectedOffset, seg.startOffset)
            assertTrue(seg.endOffset >= seg.startOffset)
            assertEquals(seg.expectedBytes, seg.endOffset - seg.startOffset + 1)
            currentExpectedOffset = seg.endOffset + 1
            totalBytesCalculated += seg.expectedBytes
        }

        assertEquals(totalSize, currentExpectedOffset)
        assertEquals(totalSize, totalBytesCalculated)
    }

    @Test
    fun testOddPrimeSizeAcrossMultipleThreads() {
        val testSizes = listOf(
            5_242_880L,   // exactly 5MB
            10_000_001L,  // 10MB + 1 byte
            77_777_777L,  // prime-like size
            123_456_789L, // large odd size
            1_073_741_824L // 1GB
        )

        val threadCounts = listOf(2, 4, 6, 8)

        for (size in testSizes) {
            for (threads in threadCounts) {
                val segments = calculateSegments(size, threads)
                assertTrue("Segments should not be empty for size $size with threads $threads", segments.isNotEmpty())
                assertTrue("Segments count ${segments.size} should be <= $threads", segments.size <= threads)

                var expectedStart = 0L
                var sumBytes = 0L

                for (i in segments.indices) {
                    val seg = segments[i]
                    assertEquals("Start offset mismatch for segment $i with size $size, threads $threads", expectedStart, seg.startOffset)
                    assertTrue("End offset ${seg.endOffset} must be >= start offset ${seg.startOffset}", seg.endOffset >= seg.startOffset)
                    assertTrue("End offset ${seg.endOffset} must be < size $size", seg.endOffset < size)
                    assertEquals(seg.expectedBytes, seg.endOffset - seg.startOffset + 1)

                    expectedStart = seg.endOffset + 1
                    sumBytes += seg.expectedBytes
                }

                assertEquals("Total covered range must equal totalSize for size $size, threads $threads", size, expectedStart)
                assertEquals("Sum of segment bytes must equal totalSize for size $size, threads $threads", size, sumBytes)
            }
        }
    }
}
