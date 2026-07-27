package za.kilowatch.ultimatefilemanager.archive

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZMethod
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile

import za.kilowatch.ultimatefilemanager.R
import java.io.File
import java.io.FileInputStream

/**
 * Handles compression and extraction for ZIP and 7Z formats.
 */
object ArchiveManager {
    private const val TAG = "ArchiveManager"

    enum class Format { ZIP, SEVEN_Z }

    @Suppress("DEPRECATION")
    private fun createSevenZFile(archiveFile: File, password: String?): org.apache.commons.compress.archivers.sevenz.SevenZFile {
        val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        val options = org.apache.commons.compress.archivers.sevenz.SevenZFileOptions.builder()
            .withMaxMemoryLimitInKb(maxMemoryKb)
            .build()
        return if (password != null) {
            org.apache.commons.compress.archivers.sevenz.SevenZFile(archiveFile, password.toCharArray(), options)
        } else {
            org.apache.commons.compress.archivers.sevenz.SevenZFile(archiveFile, options)
        }
    }

    suspend fun compress(
        sourceFiles: List<File>,
        destFile: File,
        password: String? = null,
        onProgress: (Int) -> Unit = {}
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val format = if (destFile.extension.lowercase() == "7z") Format.SEVEN_Z else Format.ZIP
            when (format) {
                Format.ZIP -> compressZip(sourceFiles, destFile, password, onProgress)
                Format.SEVEN_Z -> compress7z(sourceFiles, destFile, password, onProgress)
            }
            Result.success(Unit)
        } catch (e: OutOfMemoryError) {
            Result.failure(Exception("Not enough memory for compression", e))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun compressZip(
        sourceFiles: List<File>,
        destFile: File,
        password: String?,
        onProgress: (Int) -> Unit
    ) {
        val zipFile = ZipFile(destFile)
        val parameters = ZipParameters().apply {
            if (password != null) {
                isEncryptFiles = true
                encryptionMethod = EncryptionMethod.AES
                aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                zipFile.setPassword(password.toCharArray())
            }
        }

        sourceFiles.forEachIndexed { index, file ->
            if (file.isDirectory) {
                zipFile.addFolder(file, parameters)
            } else {
                zipFile.addFile(file, parameters)
            }
            onProgress(((index + 1).toFloat() / sourceFiles.size * 100).toInt())
        }
    }

    private fun compress7z(
        sourceFiles: List<File>,
        destFile: File,
        password: String?,
        onProgress: (Int) -> Unit
    ) {
        val out = if (password != null) {
            SevenZOutputFile(destFile, password.toCharArray())
        } else {
            SevenZOutputFile(destFile)
        }
        
        out.use { sevenZOut ->
            sourceFiles.forEachIndexed { index, file ->
                addFileTo7z(sevenZOut, file, "")
                onProgress(((index + 1).toFloat() / sourceFiles.size * 100).toInt())
            }
        }
    }

    suspend fun extract(
        context: Context,
        archiveFile: File,
        destDir: File,
        password: String? = null,
        onProgress: (Int) -> Unit = {}
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val extension = archiveFile.extension.lowercase()
            when (extension) {
                "zip" -> extractZip(archiveFile, destDir, password, onProgress)
                "7z" -> extract7z(archiveFile, destDir, password, onProgress)
                else -> throw IllegalArgumentException(context.getString(R.string.unsupported_archive_format_extension, extension))
            }
            Result.success(Unit)
        } catch (e: OutOfMemoryError) {
            Result.failure(Exception(context.getString(R.string.error_not_enough_memory), e))
        } catch (e: org.apache.commons.compress.MemoryLimitException) {
            Result.failure(Exception(context.getString(R.string.error_not_enough_memory), e))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun extractZip(
        archiveFile: File,
        destDir: File,
        password: String?,
        onProgress: (Int) -> Unit
    ) {
        val zipFile = ZipFile(archiveFile)
        if (zipFile.isEncrypted && password != null) {
            zipFile.setPassword(password.toCharArray())
        }
        zipFile.extractAll(destDir.absolutePath)
    }

    private fun extract7z(
        archiveFile: File,
        destDir: File,
        password: String?,
        onProgress: (Int) -> Unit
    ) {
        val sevenZFile = createSevenZFile(archiveFile, password)
        
        sevenZFile.use { archive ->
            val canonicalDest = destDir.canonicalPath
            var entry = archive.nextEntry
            while (entry != null) {
                val outFile = File(destDir, entry.name)
                // Zip Slip protection — canonical path must stay inside destDir
                val canonicalOut = try {
                    outFile.canonicalPath
                } catch (e: java.io.IOException) {
                    Log.w(TAG, "Skipping entry with unresolvable path: ${entry.name}")
                    entry = archive.nextEntry
                    continue
                }
                if (!canonicalOut.startsWith(canonicalDest + File.separator) && canonicalOut != canonicalDest) {
                    Log.w(TAG, "Zip Slip attempt detected! Skipping entry: ${entry.name}")
                    entry = archive.nextEntry
                    continue
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var len: Int
                        while (archive.read(buffer).also { len = it } > 0) {
                            output.write(buffer, 0, len)
                        }
                    }
                }
                entry = archive.nextEntry
            }
        }
    }

    private fun addFileTo7z(out: SevenZOutputFile, file: File, parentPath: String) {
        val entryPath = if (parentPath.isEmpty()) file.name else "$parentPath/${file.name}"
        val entry = out.createArchiveEntry(file, entryPath)
        out.putArchiveEntry(entry)
        
        if (file.isDirectory) {
            out.closeArchiveEntry()
            file.listFiles()?.forEach { child ->
                addFileTo7z(out, child, entryPath)
            }
        } else {
            FileInputStream(file).use { input ->
                val buffer = ByteArray(8192)
                var len: Int
                while (input.read(buffer).also { len = it } > 0) {
                    out.write(buffer, 0, len)
                }
            }
            out.closeArchiveEntry()
        }
    }

    /**
     * Extracts a single entry (or directory) from a ZIP archive.
     */
    suspend fun extractZipEntry(
        archiveFile: File,
        entryPath: String,
        destDir: File,
        password: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val zipFile = ZipFile(archiveFile)
            if (zipFile.isEncrypted && password != null) {
                zipFile.setPassword(password.toCharArray())
            }
            destDir.mkdirs()
            val canonicalDest = destDir.canonicalPath
            val headers = zipFile.fileHeaders.filter { 
                it.fileName == entryPath || it.fileName == "$entryPath/" || it.fileName.startsWith("$entryPath/")
            }
            if (headers.isEmpty()) {
                throw IllegalArgumentException("Entry not found in archive: $entryPath")
            }

            val prefix = if (entryPath.endsWith("/")) entryPath else if (entryPath.contains("/")) entryPath.substringBeforeLast("/") + "/" else ""
            for (header in headers) {
                val relativePath = if (prefix.isNotEmpty() && header.fileName.startsWith(prefix)) {
                    header.fileName.removePrefix(prefix)
                } else {
                    header.fileName.substringAfterLast("/")
                }
                val outFile = File(destDir, relativePath)
                val canonicalOut = outFile.canonicalPath
                if (!canonicalOut.startsWith(canonicalDest + File.separator) && canonicalOut != canonicalDest) {
                    Log.w(TAG, "Zip Slip attempt detected! Skipping entry: ${header.fileName}")
                    continue
                }
                if (header.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    zipFile.getInputStream(header).use { input ->
                        outFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Deletes a single entry (or directory hierarchy) from a ZIP archive without extracting.
     */
    suspend fun deleteZipEntry(
        archiveFile: File,
        entryPath: String,
        password: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val zipFile = ZipFile(archiveFile)
            if (zipFile.isEncrypted && password != null) {
                zipFile.setPassword(password.toCharArray())
            }
            val targetHeaders = zipFile.fileHeaders.filter {
                it.fileName == entryPath || it.fileName == "$entryPath/" || it.fileName.startsWith("$entryPath/")
            }
            for (header in targetHeaders) {
                zipFile.removeFile(header)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Moves a single entry (or directory) out of a ZIP archive: extracts to destDir then removes from archive.
     */
    suspend fun moveZipEntry(
        archiveFile: File,
        entryPath: String,
        destDir: File,
        password: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val extractRes = extractZipEntry(archiveFile, entryPath, destDir, password)
        if (extractRes.isFailure) return@withContext extractRes
        deleteZipEntry(archiveFile, entryPath, password)
    }

    /**
     * Extracts a single entry (or directory) from a 7z archive.
     */
    suspend fun extract7zEntry(
        archiveFile: File,
        entryPath: String,
        destDir: File,
        password: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val sevenZFile = createSevenZFile(archiveFile, password)
            sevenZFile.use { archive ->
                destDir.mkdirs()
                val canonicalDest = destDir.canonicalPath
                val prefix = if (entryPath.endsWith("/")) entryPath else if (entryPath.contains("/")) entryPath.substringBeforeLast("/") + "/" else ""
                var entry = archive.nextEntry
                while (entry != null) {
                    val isMatch = entry.name == entryPath || entry.name == "$entryPath/" || entry.name.startsWith("$entryPath/")
                    if (isMatch) {
                        val relativePath = if (prefix.isNotEmpty() && entry.name.startsWith(prefix)) {
                            entry.name.removePrefix(prefix)
                        } else {
                            entry.name.substringAfterLast("/")
                        }
                        val outFile = File(destDir, relativePath)
                        val canonicalOut = outFile.canonicalPath
                        if (canonicalOut.startsWith(canonicalDest + File.separator) || canonicalOut == canonicalDest) {
                            if (entry.isDirectory) {
                                outFile.mkdirs()
                            } else {
                                outFile.parentFile?.mkdirs()
                                outFile.outputStream().use { output ->
                                    val buffer = ByteArray(8192)
                                    var len: Int
                                    while (archive.read(buffer).also { len = it } > 0) {
                                        output.write(buffer, 0, len)
                                    }
                                }
                            }
                        }
                    }
                    entry = archive.nextEntry
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Deletes a single entry (or directory hierarchy) from a 7z archive by rebuilding the archive.
     */
    suspend fun delete7zEntry(
        archiveFile: File,
        entryPath: String,
        password: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val tempFile = File(archiveFile.parentFile, "${archiveFile.name}.tmp")
            if (tempFile.exists()) tempFile.delete()

            val inSevenZ = createSevenZFile(archiveFile, password)
            val outSevenZ = if (password != null) SevenZOutputFile(tempFile, password.toCharArray()) else SevenZOutputFile(tempFile)

            inSevenZ.use { inArchive ->
                outSevenZ.use { outArchive ->
                    var entry = inArchive.nextEntry
                    while (entry != null) {
                        val isTarget = entry.name == entryPath || entry.name == "$entryPath/" || entry.name.startsWith("$entryPath/")
                        if (!isTarget) {
                            val newEntry = outArchive.createArchiveEntry(File(entry.name), entry.name)
                            newEntry.isDirectory = entry.isDirectory
                            outArchive.putArchiveEntry(newEntry)
                            if (!entry.isDirectory && entry.hasStream()) {
                                val buffer = ByteArray(8192)
                                var len: Int
                                while (inArchive.read(buffer).also { len = it } > 0) {
                                    outArchive.write(buffer, 0, len)
                                }
                            }
                            outArchive.closeArchiveEntry()
                        }
                        entry = inArchive.nextEntry
                    }
                }
            }

            if (!tempFile.exists()) {
                throw java.io.IOException("Failed to create modified 7z archive file")
            }

            if (!archiveFile.delete()) {
                Log.w(TAG, "Could not delete original 7z file before replacement")
            }
            if (!tempFile.renameTo(archiveFile)) {
                tempFile.copyTo(archiveFile, overwrite = true)
                tempFile.delete()
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Moves a single entry (or directory) out of a 7z archive: extracts to destDir then removes from archive.
     */
    suspend fun move7zEntry(
        archiveFile: File,
        entryPath: String,
        destDir: File,
        password: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val extractRes = extract7zEntry(archiveFile, entryPath, destDir, password)
        if (extractRes.isFailure) return@withContext extractRes
        delete7zEntry(archiveFile, entryPath, password)
    }
}
