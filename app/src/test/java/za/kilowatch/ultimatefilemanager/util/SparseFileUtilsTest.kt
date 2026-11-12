package za.kilowatch.ultimatefilemanager.util

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

class SparseFileUtilsTest {

    @Test
    fun testZeroByteFileAllocation() {
        val alloc = SparseFileUtils.calculateAllocation(0L, 0L)
        assertEquals(0L, alloc.nominalSize)
        assertEquals(0L, alloc.allocatedBytes)
        assertFalse(alloc.isSparse)
        assertEquals(100f, alloc.allocationPercentage, 0.01f)
    }

    @Test
    fun testFullyAllocatedFile() {
        val nominal = 10_000_000L // ~10 MB
        val allocated = 10_000_000L
        val alloc = SparseFileUtils.calculateAllocation(nominal, allocated)
        assertEquals(nominal, alloc.nominalSize)
        assertEquals(nominal, alloc.allocatedBytes)
        assertFalse(alloc.isSparse)
        assertEquals(100f, alloc.allocationPercentage, 0.01f)
    }

    @Test
    fun testSlightBlockRoundingNotSparse() {
        // ext4 may allocate slightly more or slightly less than nominal within 4KB
        val nominal = 1_000_000L
        val allocated = 998_000L // 99.8% (> 98%)
        val alloc = SparseFileUtils.calculateAllocation(nominal, allocated)
        assertFalse(alloc.isSparse)
        assertEquals(100f, alloc.allocationPercentage, 0.01f)
    }

    @Test
    fun testSparseIncompleteFile() {
        val nominal = 4_000_000_000L // 4 GB preallocated torrent
        val allocated = 1_000_000_000L // 1 GB downloaded (25%)
        val alloc = SparseFileUtils.calculateAllocation(nominal, allocated)
        assertTrue(alloc.isSparse)
        assertEquals(nominal, alloc.nominalSize)
        assertEquals(allocated, alloc.allocatedBytes)
        assertEquals(25f, alloc.allocationPercentage, 0.01f)
    }

    @Test
    fun testSmallFileSparse() {
        val nominal = 10_000L
        val allocated = 2_048L // Less than nominal - 4096L
        val alloc = SparseFileUtils.calculateAllocation(nominal, allocated)
        assertTrue(alloc.isSparse)
        assertEquals(20.48f, alloc.allocationPercentage, 0.05f)
    }

    @Test
    fun testComparePartialFiles_SizeMismatch() {
        val tempA = File.createTempFile("testA", ".tmp")
        val tempB = File.createTempFile("testB", ".tmp")
        try {
            FileOutputStream(tempA).use { it.write(ByteArray(100)) }
            FileOutputStream(tempB).use { it.write(ByteArray(200)) }
            val res = SparseFileUtils.comparePartialFiles(tempA, tempB)
            assertTrue(res is PartialCompareResult.SizeMismatch)
        } finally {
            tempA.delete()
            tempB.delete()
        }
    }

    @Test
    fun testComparePartialFiles_HeaderMismatch() {
        val tempA = File.createTempFile("testA", ".tmp")
        val tempB = File.createTempFile("testB", ".tmp")
        try {
            val bytesA = ByteArray(100) { 1 }
            val bytesB = ByteArray(100) { 2 }
            FileOutputStream(tempA).use { it.write(bytesA) }
            FileOutputStream(tempB).use { it.write(bytesB) }
            val res = SparseFileUtils.comparePartialFiles(tempA, tempB)
            assertTrue(res is PartialCompareResult.HeaderMismatch)
        } finally {
            tempA.delete()
            tempB.delete()
        }
    }

    @Test
    fun testComparePartialFiles_FullMatch() {
        val tempA = File.createTempFile("testA", ".tmp")
        val tempB = File.createTempFile("testB", ".tmp")
        try {
            val bytes = ByteArray(500) { (it % 256).toByte() }
            FileOutputStream(tempA).use { it.write(bytes) }
            FileOutputStream(tempB).use { it.write(bytes) }
            val res = SparseFileUtils.comparePartialFiles(tempA, tempB)
            assertTrue(res is PartialCompareResult.FullMatch)
        } finally {
            tempA.delete()
            tempB.delete()
        }
    }
}
