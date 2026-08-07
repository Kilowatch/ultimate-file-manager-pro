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
import za.kilowatch.ultimatefilemanager.util.TransferConflictHelper
import com.github.junrar.Archive
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorOutputStream

/**
 * Handles compression and extraction for ZIP, 7Z, TAR, RAR, and compressed stream formats.
 */
object ArchiveManager {
    private const val TAG = "ArchiveManager"

    enum class Format(val ext: String, val displayName: String, val supportsPassword: Boolean = false) {
        ZIP("zip", ".zip", true),
        SEVEN_Z("7z", ".7z", true),
        TAR("tar", ".tar", false),
        TAR_GZ("tar.gz", ".tar.gz", false),
        TAR_BZ2("tar.bz2", ".tar.bz2", false),
        TAR_XZ("tar.xz", ".tar.xz", false),
        TAR_ZST("tar.zst", ".tar.zst", false),
        GZ("gz", ".gz", false),
        BZ2("bz2", ".bz2", false),
        XZ("xz", ".xz", false),
        ZST("zst", ".zst", false)
    }

    data class ArchiveEntryInfo(
        val name: String,
        val isDirectory: Boolean,
        val uncompressedSize: Long,
        val lastModified: Long
    )

    suspend fun getArchiveEntries(
        archiveFile: File,
        password: String? = null
    ): List<ArchiveEntryInfo> = withContext(Dispatchers.IO) {
        try {
            val name = archiveFile.name.lowercase()
            when {
                name.endsWith(".tar.gz") || name.endsWith(".tgz") -> getTarEntries(archiveFile, CompressorStream.GZIP)
                name.endsWith(".tar.bz2") || name.endsWith(".tbz2") || name.endsWith(".tbz") -> getTarEntries(archiveFile, CompressorStream.BZIP2)
                name.endsWith(".tar.xz") || name.endsWith(".txz") -> getTarEntries(archiveFile, CompressorStream.XZ)
                name.endsWith(".tar.zst") || name.endsWith(".tzst") -> getTarEntries(archiveFile, CompressorStream.ZSTD)
                name.endsWith(".tar") -> getTarEntries(archiveFile, CompressorStream.NONE)
                name.endsWith(".rar") -> getRarEntries(archiveFile, password)
                name.endsWith(".gz") -> getSingleStreamOrTarEntries(archiveFile, CompressorStream.GZIP)
                name.endsWith(".bz2") -> getSingleStreamOrTarEntries(archiveFile, CompressorStream.BZIP2)
                name.endsWith(".xz") -> getSingleStreamOrTarEntries(archiveFile, CompressorStream.XZ)
                name.endsWith(".zst") -> getSingleStreamOrTarEntries(archiveFile, CompressorStream.ZSTD)
                archiveFile.extension.lowercase() == "zip" -> getZipEntries(archiveFile, password)
                archiveFile.extension.lowercase() == "7z" -> get7zEntries(archiveFile, password)
                else -> emptyList()
            }
        } catch (e: LinkageError) {
            // Native codec (e.g. zstd-jni for .zst) missing on this device — treat the
            // archive as unreadable instead of crashing the app.
            Log.w(TAG, "Archive codec unavailable: ${e.message}")
            emptyList()
        }
    }

    private fun getSingleStreamOrTarEntries(archiveFile: File, compressor: CompressorStream): List<ArchiveEntryInfo> {
        try {
            val list = mutableListOf<ArchiveEntryInfo>()
            getDecompressedInputStream(archiveFile, compressor).use { decIn ->
                TarArchiveInputStream(decIn).use { tarIn ->
                    var entry = tarIn.nextEntry
                    while (entry != null) {
                        list.add(ArchiveEntryInfo(
                            name = entry.name,
                            isDirectory = entry.isDirectory,
                            uncompressedSize = entry.size,
                            lastModified = entry.modTime?.time ?: 0L
                        ))
                        entry = tarIn.nextEntry
                    }
                }
            }
            if (list.isNotEmpty()) {
                return list
            }
        } catch (e: Exception) {
            // Fallback to single stream entry
        }
        val targetName = archiveFile.name.substringBeforeLast('.')
        return listOf(ArchiveEntryInfo(targetName, false, archiveFile.length(), archiveFile.lastModified()))
    }

    private fun getTarEntries(archiveFile: File, compressor: CompressorStream): List<ArchiveEntryInfo> {
        val list = mutableListOf<ArchiveEntryInfo>()
        getDecompressedInputStream(archiveFile, compressor).use { decIn ->
            TarArchiveInputStream(decIn).use { tarIn ->
                var entry = tarIn.nextEntry
                while (entry != null) {
                    list.add(ArchiveEntryInfo(
                        name = entry.name,
                        isDirectory = entry.isDirectory,
                        uncompressedSize = entry.size,
                        lastModified = entry.modTime?.time ?: 0L
                    ))
                    entry = tarIn.nextEntry
                }
            }
        }
        return list
    }

    private fun getRarEntries(archiveFile: File, password: String?): List<ArchiveEntryInfo> {
        val list = mutableListOf<ArchiveEntryInfo>()
        val archive = if (password != null) Archive(archiveFile, password) else Archive(archiveFile)
        archive.use { rar ->
            for (header in rar.fileHeaders) {
                val fileName = header.fileName.replace('\\', '/')
                list.add(ArchiveEntryInfo(
                    name = fileName,
                    isDirectory = header.isDirectory,
                    uncompressedSize = header.fullUnpackSize,
                    lastModified = header.mTime?.time ?: 0L
                ))
            }
        }
        return list
    }

    private fun getZipEntries(archiveFile: File, password: String?): List<ArchiveEntryInfo> {
        val zipFile = ZipFile(archiveFile)
        if (zipFile.isEncrypted && password != null) {
            zipFile.setPassword(password.toCharArray())
        }
        return zipFile.fileHeaders.map { header ->
            ArchiveEntryInfo(
                name = header.fileName,
                isDirectory = header.isDirectory,
                uncompressedSize = header.uncompressedSize,
                lastModified = header.lastModifiedTime
            )
        }
    }

    private fun get7zEntries(archiveFile: File, password: String?): List<ArchiveEntryInfo> {
        val szf = createSevenZFile(archiveFile, password)
        return szf.use { file ->
            file.entries.map { entry ->
                ArchiveEntryInfo(
                    name = entry.name,
                    isDirectory = entry.isDirectory,
                    uncompressedSize = entry.size,
                    lastModified = entry.lastModifiedDate?.time ?: 0L
                )
            }
        }
    }

    val SUPPORTED_ARCHIVE_EXTENSIONS = setOf(
        "zip", "7z", "rar", "tar", "gz", "bz2", "xz", "zst", "tgz", "txz", "tzst", "tbz", "tbz2"
    )

    fun isSupportedArchiveExtension(ext: String): Boolean {
        return ext.lowercase() in SUPPORTED_ARCHIVE_EXTENSIONS
    }

    fun isSupportedArchive(file: File): Boolean {
        if (!file.isFile) return false
        val name = file.name.lowercase()
        if (name.endsWith(".tar.gz") || name.endsWith(".tar.bz2") || name.endsWith(".tar.xz") || name.endsWith(".tar.zst")) {
            return true
        }
        return isSupportedArchiveExtension(file.extension)
    }

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
        format: Format = Format.ZIP,
        onProgress: (Int) -> Unit = {}
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            when (format) {
                Format.ZIP -> compressZip(sourceFiles, destFile, password, onProgress)
                Format.SEVEN_Z -> compress7z(sourceFiles, destFile, password, onProgress)
                Format.TAR -> compressTarStream(sourceFiles, destFile, CompressorStream.NONE, onProgress)
                Format.TAR_GZ -> compressTarStream(sourceFiles, destFile, CompressorStream.GZIP, onProgress)
                Format.TAR_BZ2 -> compressTarStream(sourceFiles, destFile, CompressorStream.BZIP2, onProgress)
                Format.TAR_XZ -> compressTarStream(sourceFiles, destFile, CompressorStream.XZ, onProgress)
                Format.TAR_ZST -> compressTarStream(sourceFiles, destFile, CompressorStream.ZSTD, onProgress)
                Format.GZ -> compressTarStream(sourceFiles, destFile, CompressorStream.GZIP, onProgress)
                Format.BZ2 -> compressTarStream(sourceFiles, destFile, CompressorStream.BZIP2, onProgress)
                Format.XZ -> compressTarStream(sourceFiles, destFile, CompressorStream.XZ, onProgress)
                Format.ZST -> compressTarStream(sourceFiles, destFile, CompressorStream.ZSTD, onProgress)
            }
            Result.success(Unit)
        } catch (e: OutOfMemoryError) {
            Result.failure(Exception("Not enough memory for compression", e))
        } catch (e: LinkageError) {
            // Native codec (e.g. zstd-jni for .zst) missing on this device — fail the
            // operation instead of crashing the app with an UnsatisfiedLinkError.
            Result.failure(e)
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

    private fun getCompressorOutputStream(file: File, compressor: CompressorStream): OutputStream {
        val rawOut = BufferedOutputStream(file.outputStream())
        return when (compressor) {
            CompressorStream.NONE -> rawOut
            CompressorStream.GZIP -> GzipCompressorOutputStream(rawOut)
            CompressorStream.BZIP2 -> BZip2CompressorOutputStream(rawOut)
            CompressorStream.XZ -> XZCompressorOutputStream(rawOut)
            CompressorStream.ZSTD -> ZstdCompressorOutputStream(rawOut)
        }
    }

    private fun compressTarStream(
        sourceFiles: List<File>,
        destFile: File,
        compressor: CompressorStream,
        onProgress: (Int) -> Unit
    ) {
        getCompressorOutputStream(destFile, compressor).use { compOut ->
            TarArchiveOutputStream(compOut).use { tarOut ->
                tarOut.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                sourceFiles.forEachIndexed { index, file ->
                    addFileToTar(tarOut, file, "")
                    onProgress(((index + 1).toFloat() / sourceFiles.size * 100).toInt())
                }
            }
        }
    }

    private fun addFileToTar(out: TarArchiveOutputStream, file: File, parentPath: String) {
        val entryPath = if (parentPath.isEmpty()) file.name else "$parentPath/${file.name}"
        val entry = TarArchiveEntry(file, entryPath)
        out.putArchiveEntry(entry)
        if (file.isFile) {
            file.inputStream().use { input ->
                input.copyTo(out)
            }
        }
        out.closeArchiveEntry()
        if (file.isDirectory) {
            file.listFiles()?.forEach { child ->
                addFileToTar(out, child, entryPath)
            }
        }
    }

    private fun compressSingleStream(
        sourceFiles: List<File>,
        destFile: File,
        compressor: CompressorStream,
        onProgress: (Int) -> Unit
    ) {
        val fileToCompress = sourceFiles.firstOrNull() ?: return
        getCompressorOutputStream(destFile, compressor).use { compOut ->
            fileToCompress.inputStream().use { input ->
                input.copyTo(compOut)
            }
        }
        onProgress(100)
    }

    suspend fun extract(
        context: Context,
        archiveFile: File,
        destDir: File,
        password: String? = null,
        onProgress: (Int) -> Unit = {},
        onConflict: (suspend (file: File, isFolder: Boolean, destSizeBytes: Long, applyToAllRef: BooleanArray) -> TransferConflictHelper.ConflictAction)? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val name = archiveFile.name.lowercase()
            when {
                name.endsWith(".tar.gz") || name.endsWith(".tgz") -> extractTarStream(archiveFile, destDir, CompressorStream.GZIP, onProgress, onConflict)
                name.endsWith(".tar.bz2") || name.endsWith(".tbz2") || name.endsWith(".tbz") -> extractTarStream(archiveFile, destDir, CompressorStream.BZIP2, onProgress, onConflict)
                name.endsWith(".tar.xz") || name.endsWith(".txz") -> extractTarStream(archiveFile, destDir, CompressorStream.XZ, onProgress, onConflict)
                name.endsWith(".tar.zst") || name.endsWith(".tzst") -> extractTarStream(archiveFile, destDir, CompressorStream.ZSTD, onProgress, onConflict)
                name.endsWith(".tar") -> extractTarStream(archiveFile, destDir, CompressorStream.NONE, onProgress, onConflict)
                name.endsWith(".rar") -> extractRar(archiveFile, destDir, password, onProgress, onConflict)
                name.endsWith(".gz") -> extractSingleStreamOrTar(archiveFile, destDir, CompressorStream.GZIP, onProgress, onConflict)
                name.endsWith(".bz2") -> extractSingleStreamOrTar(archiveFile, destDir, CompressorStream.BZIP2, onProgress, onConflict)
                name.endsWith(".xz") -> extractSingleStreamOrTar(archiveFile, destDir, CompressorStream.XZ, onProgress, onConflict)
                name.endsWith(".zst") -> extractSingleStreamOrTar(archiveFile, destDir, CompressorStream.ZSTD, onProgress, onConflict)
                archiveFile.extension.lowercase() == "zip" -> extractZip(context, archiveFile, destDir, password, onProgress, onConflict)
                archiveFile.extension.lowercase() == "7z" -> extract7z(context, archiveFile, destDir, password, onProgress, onConflict)
                else -> throw IllegalArgumentException(context.getString(R.string.unsupported_archive_format_extension, archiveFile.extension))
            }
            Result.success(Unit)
        } catch (e: OutOfMemoryError) {
            Result.failure(Exception(context.getString(R.string.error_not_enough_memory), e))
        } catch (e: org.apache.commons.compress.MemoryLimitException) {
            Result.failure(Exception(context.getString(R.string.error_not_enough_memory), e))
        } catch (e: LinkageError) {
            // Native codec (e.g. zstd-jni for .zst) missing on this device — fail the
            // operation instead of crashing the app with an UnsatisfiedLinkError.
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun extractSingleStreamOrTar(
        archiveFile: File,
        destDir: File,
        compressor: CompressorStream,
        onProgress: (Int) -> Unit,
        onConflict: (suspend (file: File, isFolder: Boolean, destSizeBytes: Long, applyToAllRef: BooleanArray) -> TransferConflictHelper.ConflictAction)?
    ) {
        var isTar = false
        try {
            getDecompressedInputStream(archiveFile, compressor).use { decIn ->
                TarArchiveInputStream(decIn).use { tarIn ->
                    if (tarIn.nextEntry != null) {
                        isTar = true
                    }
                }
            }
        } catch (e: Exception) {
            isTar = false
        }

        if (isTar) {
            extractTarStream(archiveFile, destDir, compressor, onProgress, onConflict)
        } else {
            extractSingleStream(archiveFile, destDir, compressor, onProgress, onConflict)
        }
    }

    private enum class CompressorStream { NONE, GZIP, BZIP2, XZ, ZSTD }

    private fun getDecompressedInputStream(file: File, compressor: CompressorStream): InputStream {
        val rawIn = BufferedInputStream(file.inputStream())
        return when (compressor) {
            CompressorStream.NONE -> rawIn
            CompressorStream.GZIP -> GzipCompressorInputStream(rawIn)
            CompressorStream.BZIP2 -> BZip2CompressorInputStream(rawIn)
            CompressorStream.XZ -> XZCompressorInputStream(rawIn)
            CompressorStream.ZSTD -> ZstdCompressorInputStream(rawIn)
        }
    }

    private suspend fun extractTarStream(
        archiveFile: File,
        destDir: File,
        compressor: CompressorStream,
        onProgress: (Int) -> Unit,
        onConflict: (suspend (file: File, isFolder: Boolean, destSizeBytes: Long, applyToAllRef: BooleanArray) -> TransferConflictHelper.ConflictAction)?
    ) {
        val canonicalDest = destDir.canonicalPath
        var applyToAllAction: TransferConflictHelper.ConflictAction? = null

        getDecompressedInputStream(archiveFile, compressor).use { decIn ->
            val tarIn = TarArchiveInputStream(decIn)
            var entry = tarIn.nextEntry
            while (entry != null) {
                val outFile = File(destDir, entry.name)
                val canonicalOut = try {
                    outFile.canonicalPath
                } catch (e: java.io.IOException) {
                    Log.w(TAG, "Skipping entry with unresolvable path: ${entry.name}")
                    entry = tarIn.nextEntry
                    continue
                }
                if (!canonicalOut.startsWith(canonicalDest + File.separator) && canonicalOut != canonicalDest) {
                    Log.w(TAG, "Zip Slip attempt detected! Skipping entry: ${entry.name}")
                    entry = tarIn.nextEntry
                    continue
                }

                var targetFile = outFile
                if (entry.isDirectory) {
                    targetFile.mkdirs()
                } else {
                    if (targetFile.exists()) {
                        val action = if (applyToAllAction != null) {
                            applyToAllAction!!
                        } else {
                            val applyToAllRef = booleanArrayOf(false)
                            val act = if (onConflict != null) {
                                onConflict(targetFile, false, targetFile.length(), applyToAllRef)
                            } else {
                                TransferConflictHelper.ConflictAction.OVERWRITE
                            }
                            if (applyToAllRef[0]) {
                                applyToAllAction = act
                            }
                            act
                        }

                        when (action) {
                            TransferConflictHelper.ConflictAction.SKIP -> {
                                entry = tarIn.nextEntry
                                continue
                            }
                            TransferConflictHelper.ConflictAction.CANCEL -> throw kotlinx.coroutines.CancellationException("Extraction cancelled by user")
                            TransferConflictHelper.ConflictAction.KEEP_BOTH -> {
                                targetFile = TransferConflictHelper.uniqueLocalFile(targetFile.parentFile!!, targetFile.name)
                            }
                            TransferConflictHelper.ConflictAction.OVERWRITE -> { /* proceed */ }
                        }
                    }

                    targetFile.parentFile?.mkdirs()
                    targetFile.outputStream().use { output ->
                        tarIn.copyTo(output)
                    }
                }
                entry = tarIn.nextEntry
            }
        }
    }

    private suspend fun extractSingleStream(
        archiveFile: File,
        destDir: File,
        compressor: CompressorStream,
        onProgress: (Int) -> Unit,
        onConflict: (suspend (file: File, isFolder: Boolean, destSizeBytes: Long, applyToAllRef: BooleanArray) -> TransferConflictHelper.ConflictAction)?
    ) {
        val targetName = archiveFile.name.substringBeforeLast('.')
        var targetFile = File(destDir, targetName)

        if (targetFile.exists()) {
            val applyToAllRef = booleanArrayOf(false)
            val action = if (onConflict != null) {
                onConflict(targetFile, false, targetFile.length(), applyToAllRef)
            } else {
                TransferConflictHelper.ConflictAction.OVERWRITE
            }

            when (action) {
                TransferConflictHelper.ConflictAction.SKIP -> return
                TransferConflictHelper.ConflictAction.CANCEL -> throw kotlinx.coroutines.CancellationException("Extraction cancelled by user")
                TransferConflictHelper.ConflictAction.KEEP_BOTH -> {
                    targetFile = TransferConflictHelper.uniqueLocalFile(targetFile.parentFile!!, targetFile.name)
                }
                TransferConflictHelper.ConflictAction.OVERWRITE -> { /* proceed */ }
            }
        }

        targetFile.parentFile?.mkdirs()
        getDecompressedInputStream(archiveFile, compressor).use { input ->
            targetFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }

    private suspend fun extractRar(
        archiveFile: File,
        destDir: File,
        password: String?,
        onProgress: (Int) -> Unit,
        onConflict: (suspend (file: File, isFolder: Boolean, destSizeBytes: Long, applyToAllRef: BooleanArray) -> TransferConflictHelper.ConflictAction)?
    ) {
        val canonicalDest = destDir.canonicalPath
        var applyToAllAction: TransferConflictHelper.ConflictAction? = null

        val archive = if (password != null) Archive(archiveFile, password) else Archive(archiveFile)
        archive.use { rar ->
            val headers = rar.fileHeaders
            headers.forEachIndexed { index, header ->
                val fileName = header.fileName.replace('\\', '/')
                val outFile = File(destDir, fileName)
                val canonicalOut = try {
                    outFile.canonicalPath
                } catch (e: java.io.IOException) {
                    Log.w(TAG, "Skipping entry with unresolvable path: $fileName")
                    return@forEachIndexed
                }
                if (!canonicalOut.startsWith(canonicalDest + File.separator) && canonicalOut != canonicalDest) {
                    Log.w(TAG, "Zip Slip attempt detected! Skipping entry: $fileName")
                    return@forEachIndexed
                }

                var targetFile = outFile
                if (header.isDirectory) {
                    targetFile.mkdirs()
                } else {
                    if (targetFile.exists()) {
                        val action = if (applyToAllAction != null) {
                            applyToAllAction!!
                        } else {
                            val applyToAllRef = booleanArrayOf(false)
                            val act = if (onConflict != null) {
                                onConflict(targetFile, false, targetFile.length(), applyToAllRef)
                            } else {
                                TransferConflictHelper.ConflictAction.OVERWRITE
                            }
                            if (applyToAllRef[0]) {
                                applyToAllAction = act
                            }
                            act
                        }

                        when (action) {
                            TransferConflictHelper.ConflictAction.SKIP -> return@forEachIndexed
                            TransferConflictHelper.ConflictAction.CANCEL -> throw kotlinx.coroutines.CancellationException("Extraction cancelled by user")
                            TransferConflictHelper.ConflictAction.KEEP_BOTH -> {
                                targetFile = TransferConflictHelper.uniqueLocalFile(targetFile.parentFile!!, targetFile.name)
                            }
                            TransferConflictHelper.ConflictAction.OVERWRITE -> { /* proceed */ }
                        }
                    }

                    targetFile.parentFile?.mkdirs()
                    targetFile.outputStream().use { output ->
                        rar.extractFile(header, output)
                    }
                }
                onProgress(((index + 1).toFloat() / headers.size * 100).toInt())
            }
        }
    }

    private suspend fun extractZip(
        context: Context,
        archiveFile: File,
        destDir: File,
        password: String?,
        onProgress: (Int) -> Unit,
        onConflict: (suspend (file: File, isFolder: Boolean, destSizeBytes: Long, applyToAllRef: BooleanArray) -> TransferConflictHelper.ConflictAction)?
    ) {
        val zipFile = ZipFile(archiveFile)
        if (zipFile.isEncrypted && password != null) {
            zipFile.setPassword(password.toCharArray())
        }

        if (onConflict == null) {
            zipFile.extractAll(destDir.absolutePath)
            return
        }

        val canonicalDest = destDir.canonicalPath
        val headers = zipFile.fileHeaders
        var applyToAllAction: TransferConflictHelper.ConflictAction? = null

        headers.forEachIndexed { index, header ->
            val outFile = File(destDir, header.fileName)
            val canonicalOut = try {
                outFile.canonicalPath
            } catch (e: java.io.IOException) {
                return@forEachIndexed
            }
            if (!canonicalOut.startsWith(canonicalDest + File.separator) && canonicalOut != canonicalDest) {
                Log.w(TAG, "Zip Slip attempt detected! Skipping entry: ${header.fileName}")
                return@forEachIndexed
            }

            var targetFile = outFile
            if (header.isDirectory) {
                targetFile.mkdirs()
            } else {
                if (targetFile.exists()) {
                    val action = if (applyToAllAction != null) {
                        applyToAllAction!!
                    } else {
                        val applyToAllRef = booleanArrayOf(false)
                        val act = onConflict(targetFile, false, targetFile.length(), applyToAllRef)
                        if (applyToAllRef[0]) {
                            applyToAllAction = act
                        }
                        act
                    }

                    when (action) {
                        TransferConflictHelper.ConflictAction.SKIP -> return@forEachIndexed
                        TransferConflictHelper.ConflictAction.CANCEL -> throw kotlinx.coroutines.CancellationException("Extraction cancelled by user")
                        TransferConflictHelper.ConflictAction.KEEP_BOTH -> {
                            targetFile = TransferConflictHelper.uniqueLocalFile(targetFile.parentFile!!, targetFile.name)
                        }
                        TransferConflictHelper.ConflictAction.OVERWRITE -> { /* proceed */ }
                    }
                }

                targetFile.parentFile?.mkdirs()
                zipFile.getInputStream(header).use { input ->
                    targetFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
            onProgress(((index + 1).toFloat() / headers.size * 100).toInt())
        }
    }

    private suspend fun extract7z(
        context: Context,
        archiveFile: File,
        destDir: File,
        password: String?,
        onProgress: (Int) -> Unit,
        onConflict: (suspend (file: File, isFolder: Boolean, destSizeBytes: Long, applyToAllRef: BooleanArray) -> TransferConflictHelper.ConflictAction)?
    ) {
        val sevenZFile = createSevenZFile(archiveFile, password)

        sevenZFile.use { archive ->
            val canonicalDest = destDir.canonicalPath
            var entry = archive.nextEntry
            var applyToAllAction: TransferConflictHelper.ConflictAction? = null

            while (entry != null) {
                val outFile = File(destDir, entry.name)
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

                var targetFile = outFile
                if (entry.isDirectory) {
                    targetFile.mkdirs()
                } else {
                    if (targetFile.exists()) {
                        val action = if (applyToAllAction != null) {
                            applyToAllAction!!
                        } else {
                            val applyToAllRef = booleanArrayOf(false)
                            val act = if (onConflict != null) {
                                onConflict(targetFile, false, targetFile.length(), applyToAllRef)
                            } else {
                                TransferConflictHelper.ConflictAction.OVERWRITE
                            }
                            if (applyToAllRef[0]) {
                                applyToAllAction = act
                            }
                            act
                        }

                        when (action) {
                            TransferConflictHelper.ConflictAction.SKIP -> {
                                entry = archive.nextEntry
                                continue
                            }
                            TransferConflictHelper.ConflictAction.CANCEL -> throw kotlinx.coroutines.CancellationException("Extraction cancelled by user")
                            TransferConflictHelper.ConflictAction.KEEP_BOTH -> {
                                targetFile = TransferConflictHelper.uniqueLocalFile(targetFile.parentFile!!, targetFile.name)
                            }
                            TransferConflictHelper.ConflictAction.OVERWRITE -> { /* proceed */ }
                        }
                    }

                    targetFile.parentFile?.mkdirs()
                    targetFile.outputStream().use { output ->
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

    /**
     * Format-agnostic single entry extraction for ZIP, 7Z, TAR, RAR, and compressed streams.
     */
    suspend fun extractArchiveEntry(
        archiveFile: File,
        entryPath: String,
        destDir: File,
        password: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val ext = archiveFile.name.lowercase()
        when {
            ext.endsWith(".tar.gz") || ext.endsWith(".tgz") -> extractTarEntry(archiveFile, entryPath, destDir, CompressorStream.GZIP)
            ext.endsWith(".tar.bz2") || ext.endsWith(".tbz2") || ext.endsWith(".tbz") -> extractTarEntry(archiveFile, entryPath, destDir, CompressorStream.BZIP2)
            ext.endsWith(".tar.xz") || ext.endsWith(".txz") -> extractTarEntry(archiveFile, entryPath, destDir, CompressorStream.XZ)
            ext.endsWith(".tar.zst") || ext.endsWith(".tzst") -> extractTarEntry(archiveFile, entryPath, destDir, CompressorStream.ZSTD)
            ext.endsWith(".tar") -> extractTarEntry(archiveFile, entryPath, destDir, CompressorStream.NONE)
            ext.endsWith(".rar") -> extractRarEntry(archiveFile, entryPath, destDir, password)
            ext.endsWith(".gz") -> extractTarEntry(archiveFile, entryPath, destDir, CompressorStream.GZIP)
            ext.endsWith(".bz2") -> extractTarEntry(archiveFile, entryPath, destDir, CompressorStream.BZIP2)
            ext.endsWith(".xz") -> extractTarEntry(archiveFile, entryPath, destDir, CompressorStream.XZ)
            ext.endsWith(".zst") -> extractTarEntry(archiveFile, entryPath, destDir, CompressorStream.ZSTD)
            archiveFile.extension.lowercase() == "zip" -> extractZipEntry(archiveFile, entryPath, destDir, password)
            archiveFile.extension.lowercase() == "7z" -> extract7zEntry(archiveFile, entryPath, destDir, password)
            else -> Result.failure<Unit>(IllegalArgumentException("Unsupported archive format"))
        }
    }

    private fun getCompressedOutputStream(file: File, compressor: CompressorStream): OutputStream {
        val rawOut = BufferedOutputStream(file.outputStream())
        return when (compressor) {
            CompressorStream.NONE -> rawOut
            CompressorStream.GZIP -> GzipCompressorOutputStream(rawOut)
            CompressorStream.BZIP2 -> BZip2CompressorOutputStream(rawOut)
            CompressorStream.XZ -> XZCompressorOutputStream(rawOut)
            CompressorStream.ZSTD -> ZstdCompressorOutputStream(rawOut)
        }
    }

    private suspend fun deleteTarEntry(
        archiveFile: File,
        entryPath: String,
        compressor: CompressorStream
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val tempFile = File(archiveFile.parentFile, "${archiveFile.name}.tmp")
            if (tempFile.exists()) tempFile.delete()

            getDecompressedInputStream(archiveFile, compressor).use { decIn ->
                TarArchiveInputStream(decIn).use { tarIn ->
                    getCompressedOutputStream(tempFile, compressor).use { compOut ->
                        TarArchiveOutputStream(compOut).use { tarOut ->
                            tarOut.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                            tarOut.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)

                            var entry = tarIn.nextEntry
                            while (entry != null) {
                                val isTarget = entry.name == entryPath || entry.name == "$entryPath/" || entry.name.startsWith("$entryPath/")
                                if (!isTarget) {
                                    tarOut.putArchiveEntry(entry)
                                    if (!entry.isDirectory) {
                                        tarIn.copyTo(tarOut)
                                    }
                                    tarOut.closeArchiveEntry()
                                }
                                entry = tarIn.nextEntry
                            }
                            tarOut.finish()
                        }
                    }
                }
            }

            if (!tempFile.exists()) {
                throw java.io.IOException("Failed to create modified tar archive")
            }

            if (!archiveFile.delete()) {
                Log.w(TAG, "Could not delete original tar file before replacement")
            }
            if (!tempFile.renameTo(archiveFile)) {
                tempFile.copyTo(archiveFile, overwrite = true)
                tempFile.delete()
            }

            Result.success(Unit)
        } catch (e: LinkageError) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Format-agnostic single entry deletion. Supported for ZIP, 7Z, TAR, GZ, BZ2, XZ, and ZST archives.
     */
    suspend fun deleteArchiveEntry(
        archiveFile: File,
        entryPath: String,
        password: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val ext = archiveFile.name.lowercase()
        when {
            ext.endsWith(".tar.gz") || ext.endsWith(".tgz") || ext.endsWith(".gz") -> deleteTarEntry(archiveFile, entryPath, CompressorStream.GZIP)
            ext.endsWith(".tar.bz2") || ext.endsWith(".tbz2") || ext.endsWith(".tbz") || ext.endsWith(".bz2") -> deleteTarEntry(archiveFile, entryPath, CompressorStream.BZIP2)
            ext.endsWith(".tar.xz") || ext.endsWith(".txz") || ext.endsWith(".xz") -> deleteTarEntry(archiveFile, entryPath, CompressorStream.XZ)
            ext.endsWith(".tar.zst") || ext.endsWith(".tzst") || ext.endsWith(".zst") -> deleteTarEntry(archiveFile, entryPath, CompressorStream.ZSTD)
            ext.endsWith(".tar") -> deleteTarEntry(archiveFile, entryPath, CompressorStream.NONE)
            archiveFile.extension.lowercase() == "zip" -> deleteZipEntry(archiveFile, entryPath, password)
            archiveFile.extension.lowercase() == "7z" -> delete7zEntry(archiveFile, entryPath, password)
            else -> Result.failure(UnsupportedOperationException("Deleting entries from .${archiveFile.extension} archives is not supported"))
        }
    }

    /**
     * Format-agnostic single entry move out. Supported for ZIP and 7Z.
     */
    suspend fun moveArchiveEntry(
        archiveFile: File,
        entryPath: String,
        destDir: File,
        password: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val extractRes = extractArchiveEntry(archiveFile, entryPath, destDir, password)
        if (extractRes.isFailure) return@withContext extractRes
        deleteArchiveEntry(archiveFile, entryPath, password)
    }

    private fun extractTarEntry(
        archiveFile: File,
        entryPath: String,
        destDir: File,
        compressor: CompressorStream
    ): Result<Unit> {
        return try {
            getDecompressedInputStream(archiveFile, compressor).use { decIn ->
                TarArchiveInputStream(decIn).use { tarIn ->
                    destDir.mkdirs()
                    val canonicalDest = destDir.canonicalPath
                    val prefix = if (entryPath.endsWith("/")) entryPath else if (entryPath.contains("/")) entryPath.substringBeforeLast("/") + "/" else ""
                    var entry = tarIn.nextEntry
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
                                        tarIn.copyTo(output)
                                    }
                                }
                            }
                        }
                        entry = tarIn.nextEntry
                    }
                }
            }
            Result.success(Unit)
        } catch (e: LinkageError) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun extractRarEntry(
        archiveFile: File,
        entryPath: String,
        destDir: File,
        password: String?
    ): Result<Unit> {
        return try {
            val archive = if (password != null) Archive(archiveFile, password) else Archive(archiveFile)
            archive.use { rar ->
                destDir.mkdirs()
                val canonicalDest = destDir.canonicalPath
                val prefix = if (entryPath.endsWith("/")) entryPath else if (entryPath.contains("/")) entryPath.substringBeforeLast("/") + "/" else ""
                for (header in rar.fileHeaders) {
                    val fileName = header.fileName.replace('\\', '/')
                    val isMatch = fileName == entryPath || fileName == "$entryPath/" || fileName.startsWith("$entryPath/")
                    if (isMatch) {
                        val relativePath = if (prefix.isNotEmpty() && fileName.startsWith(prefix)) {
                            fileName.removePrefix(prefix)
                        } else {
                            fileName.substringAfterLast("/")
                        }
                        val outFile = File(destDir, relativePath)
                        val canonicalOut = outFile.canonicalPath
                        if (canonicalOut.startsWith(canonicalDest + File.separator) || canonicalOut == canonicalDest) {
                            if (header.isDirectory) {
                                outFile.mkdirs()
                            } else {
                                outFile.parentFile?.mkdirs()
                                outFile.outputStream().use { out ->
                                    rar.extractFile(header, out)
                                }
                            }
                        }
                    }
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
