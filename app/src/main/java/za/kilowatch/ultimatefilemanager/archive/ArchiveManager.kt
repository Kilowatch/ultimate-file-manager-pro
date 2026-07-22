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
}
