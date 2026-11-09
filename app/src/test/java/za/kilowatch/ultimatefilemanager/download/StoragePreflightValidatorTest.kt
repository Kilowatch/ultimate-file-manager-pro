package za.kilowatch.ultimatefilemanager.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StoragePreflightValidatorTest {

    @Test
    fun fat32MaxFileLimitConstantIsExact() {
        // 4 GiB minus 1 byte = 4,294,967,295 bytes
        assertEquals(4_294_967_295L, StoragePreflightValidator.FAT32_MAX_FILE_BYTES)
    }

    @Test
    fun pathTraversalPreventionRejectsEscapes() {
        val baseDir = File("/storage/emulated/0/Download")

        // Safe relative child files
        assertTrue(StoragePreflightValidator.isPathTraversalSafe(baseDir, "ubuntu-24.04.iso"))
        assertTrue(StoragePreflightValidator.isPathTraversalSafe(baseDir, "subfolder/document.pdf"))

        // Malicious traversal attempts (Zip-Slip)
        assertFalse(StoragePreflightValidator.isPathTraversalSafe(baseDir, "../secret.txt"))
        assertFalse(StoragePreflightValidator.isPathTraversalSafe(baseDir, "../../vault/keys.json"))
        assertFalse(StoragePreflightValidator.isPathTraversalSafe(baseDir, "../../../data/data/app/file"))
    }

    @Test
    fun validationResultReportsErrorCorrectly() {
        val result = StoragePreflightValidator.ValidationResult(
            isValid = false,
            errorType = StoragePreflightValidator.ErrorType.FAT32_LIMIT_EXCEEDED,
            requiredBytes = 11_000_000_000L,
            offendingFileName = "large_image.iso",
            offendingFileSize = 11_000_000_000L
        )

        assertFalse(result.isSuccessful)
        assertEquals(StoragePreflightValidator.ErrorType.FAT32_LIMIT_EXCEEDED, result.errorType)
        assertEquals(11_000_000_000L, result.offendingFileSize)
        assertEquals("large_image.iso", result.offendingFileName)
    }
}
