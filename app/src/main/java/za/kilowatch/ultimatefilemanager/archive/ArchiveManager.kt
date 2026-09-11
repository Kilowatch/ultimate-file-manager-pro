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
        val cleanName = archiveFile.name.replace(Regex("^(view_7z_|view_zip_|stage_mod_arc_)\\d+_?"), "")
        val targetName = getArchiveBaseName(cleanName)
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

    /**
     * Extracts the base name of an archive file, cleanly stripping single (.zip, .7z)
     * and compound (.tar.gz, .tar.bz2, .tar.xz, .tar.zst) extensions.
     */
    fun getArchiveBaseName(fileName: String): String {
        val lower = fileName.lowercase()
        val compoundExtensions = listOf(".tar.gz", ".tar.bz2", ".tar.xz", ".tar.zst")
        for (ext in compoundExtensions) {
            if (lower.endsWith(ext)) {
                return fileName.substring(0, fileName.length - ext.length)
            }
        }
        return if (fileName.contains('.')) fileName.substringBeforeLast('.') else fileName
    }

    fun getArchiveBaseName(file: File): String = getArchiveBaseName(file.name)

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

    /**
     * Recursively computes the total number of files and cumulative bytes for a list of files/folders.
     */
    fun calculateTotalFilesAndBytes(files: List<File>): Pair<Int, Long> {
        var count = 0
        var bytes = 0L
        for (f in files) {
            if (f.isDirectory) {
                val children = f.listFiles()?.toList() ?: emptyList()
                val (subCount, subBytes) = calculateTotalFilesAndBytes(children)
                count += subCount
                bytes += subBytes
            } else {
                count++
                bytes += f.length()
            }
        }
        return Pair(count.coerceAtLeast(1), bytes)
    }

    suspend fun compress(
        sourceFiles: List<File>,
        destFile: File,
        password: String? = null,
        format: Format = Format.ZIP,
        onProgress: (Int) -> Unit = {},
        onArchiveProgress: ArchiveProgressListener? = null
    ): Result<Unit> = compress(
        context = null,
        sourceFiles = sourceFiles,
        destFile = destFile,
        password = password,
        format = format,
        onProgress = onProgress,
        onArchiveProgress = onArchiveProgress
    )

    suspend fun compress(
        context: Context? = null,
        sourceFiles: List<File>,
        destFile: File,
        password: String? = null,
        format: Format = Format.ZIP,
        onProgress: (Int) -> Unit = {},
        onArchiveProgress: ArchiveProgressListener? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val isDestSaf = context != null && (destFile is za.kilowatch.ultimatefilemanager.storage.SafFile ||
                        za.kilowatch.ultimatefilemanager.storage.SafTreeManager.isSafPath(destFile.absolutePath) ||
                        za.kilowatch.ultimatefilemanager.storage.SafTreeManager.hasTreePermissionForPath(context, destFile.absolutePath))

        val anySourceSaf = context != null && sourceFiles.any {
            it is za.kilowatch.ultimatefilemanager.storage.SafFile ||
            za.kilowatch.ultimatefilemanager.storage.SafTreeManager.isSafPath(it.absolutePath) ||
            za.kilowatch.ultimatefilemanager.storage.SafTreeManager.hasTreePermissionForPath(context, it.absolutePath)
        }

        var tempDestFile: File? = null
        var tempSourceDir: File? = null

        try {
            val effectiveDest = if (isDestSaf && context != null) {
                tempDestFile = File(context.cacheDir, "comp_${System.currentTimeMillis()}_${destFile.name}")
                tempDestFile
            } else {
                destFile
            }

            val effectiveSources = if (anySourceSaf && context != null) {
                tempSourceDir = File(context.cacheDir, "stage_comp_src_${System.currentTimeMillis()}").apply { mkdirs() }
                sourceFiles.map { sf ->
                    val isSfSaf = sf is za.kilowatch.ultimatefilemanager.storage.SafFile ||
                                  za.kilowatch.ultimatefilemanager.storage.SafTreeManager.isSafPath(sf.absolutePath) ||
                                  za.kilowatch.ultimatefilemanager.storage.SafTreeManager.hasTreePermissionForPath(context, sf.absolutePath)
                    if (isSfSaf) {
                        val staged = File(tempSourceDir, sf.name)
                        val isDir = (sf as? za.kilowatch.ultimatefilemanager.storage.SafFile)?.isDirectory() == true ||
                                    za.kilowatch.ultimatefilemanager.storage.SafTreeManager.isDirectory(context, sf.absolutePath)
                        if (isDir) {
                            staged.mkdirs()
                            val children = za.kilowatch.ultimatefilemanager.storage.SafTreeManager.walkSafTopDown(context, sf.absolutePath)
                            for (child in children) {
                                val rel = child.absolutePath.removePrefix(sf.absolutePath).trimStart('/')
                                if (rel.isEmpty()) continue
                                val destChild = File(staged, rel)
                                if (child.isDirectory) {
                                    destChild.mkdirs()
                                } else {
                                    destChild.parentFile?.mkdirs()
                                    za.kilowatch.ultimatefilemanager.storage.SafTreeManager.openInputStream(context, child.absolutePath)?.use { inStream ->
                                        destChild.outputStream().use { outStream -> inStream.copyTo(outStream) }
                                    }
                                }
                            }
                        } else {
                            za.kilowatch.ultimatefilemanager.storage.SafTreeManager.openInputStream(context, sf.absolutePath)?.use { inStream ->
                                staged.outputStream().use { outStream -> inStream.copyTo(outStream) }
                            }
                        }
                        staged
                    } else {
                        sf
                    }
                }
            } else {
                sourceFiles
            }

            val (totalFiles, totalBytes) = calculateTotalFilesAndBytes(effectiveSources)

            when (format) {
                Format.ZIP -> compressZip(effectiveSources, effectiveDest, password, totalFiles, totalBytes, onArchiveProgress, onProgress)
                Format.SEVEN_Z -> compress7z(effectiveSources, effectiveDest, password, totalFiles, totalBytes, onArchiveProgress, onProgress)
                Format.TAR -> compressTarStream(effectiveSources, effectiveDest, CompressorStream.NONE, totalFiles, totalBytes, onArchiveProgress, onProgress)
                Format.TAR_GZ -> compressTarStream(effectiveSources, effectiveDest, CompressorStream.GZIP, totalFiles, totalBytes, onArchiveProgress, onProgress)
                Format.TAR_BZ2 -> compressTarStream(effectiveSources, effectiveDest, CompressorStream.BZIP2, totalFiles, totalBytes, onArchiveProgress, onProgress)
                Format.TAR_XZ -> compressTarStream(effectiveSources, effectiveDest, CompressorStream.XZ, totalFiles, totalBytes, onArchiveProgress, onProgress)
                Format.TAR_ZST -> compressTarStream(effectiveSources, effectiveDest, CompressorStream.ZSTD, totalFiles, totalBytes, onArchiveProgress, onProgress)
                Format.GZ -> compressTarStream(effectiveSources, effectiveDest, CompressorStream.GZIP, totalFiles, totalBytes, onArchiveProgress, onProgress)
                Format.BZ2 -> compressTarStream(effectiveSources, effectiveDest, CompressorStream.BZIP2, totalFiles, totalBytes, onArchiveProgress, onProgress)
                Format.XZ -> compressTarStream(effectiveSources, effectiveDest, CompressorStream.XZ, totalFiles, totalBytes, onArchiveProgress, onProgress)
                Format.ZST -> compressTarStream(effectiveSources, effectiveDest, CompressorStream.ZSTD, totalFiles, totalBytes, onArchiveProgress, onProgress)
            }

            if (isDestSaf && tempDestFile != null && context != null) {
                TransferConflictHelper.copyLocalToLocalAtomic(
                    tempDestFile,
                    destFile,
                    TransferConflictHelper.ConflictAction.OVERWRITE
                )
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
        } finally {
            tempDestFile?.delete()
            tempSourceDir?.deleteRecursively()
        }
    }

    private fun compressZip(
        sourceFiles: List<File>,
        destFile: File,
        password: String?,
        totalFiles: Int,
        totalBytes: Long,
        onArchiveProgress: ArchiveProgressListener?,
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

        var filesProcessed = 0
        var bytesProcessed = 0L
        val startTime = System.currentTimeMillis()

        fun addFileRecursive(file: File, parentPath: String) {
            if (Thread.currentThread().isInterrupted) throw kotlinx.coroutines.CancellationException("Compression cancelled")
            val params = ZipParameters(parameters)
            if (parentPath.isNotEmpty()) {
                if (file.isDirectory) params.rootFolderNameInZip = "$parentPath/${file.name}"
                else params.fileNameInZip = "$parentPath/${file.name}"
            }
            if (file.isDirectory) {
                zipFile.addFolder(file, params)
                file.listFiles()?.forEach { child ->
                    addFileRecursive(child, if (parentPath.isEmpty()) file.name else "$parentPath/${file.name}")
                }
            } else {
                zipFile.addFile(file, params)
                filesProcessed++
                bytesProcessed += file.length()
                val pct = if (totalBytes > 0) ((bytesProcessed * 100) / totalBytes).toInt().coerceIn(0, 100)
                          else ((filesProcessed * 100) / totalFiles.coerceAtLeast(1)).coerceIn(0, 100)
                val elapsed = System.currentTimeMillis() - startTime
                val speed = if (elapsed > 500) (bytesProcessed * 1000L) / elapsed else 0L
                val eta = if (speed > 0 && totalBytes > bytesProcessed) ((totalBytes - bytesProcessed) * 1000L) / speed else 0L
                onArchiveProgress?.onProgress(
                    ArchiveProgress(
                        operation = ArchiveOperationType.COMPRESS,
                        archiveName = destFile.name,
                        currentFileName = file.name,
                        fileIndex = filesProcessed,
                        totalFiles = totalFiles,
                        bytesProcessed = bytesProcessed,
                        totalBytes = totalBytes,
                        percentage = pct,
                        speedBytesPerSec = speed,
                        estimatedRemainingMs = eta
                    )
                )
                onProgress(pct)
            }
        }

        sourceFiles.forEach { file ->
            addFileRecursive(file, "")
        }
        onProgress(100)
    }

    private fun compress7z(
        sourceFiles: List<File>,
        destFile: File,
        password: String?,
        totalFiles: Int,
        totalBytes: Long,
        onArchiveProgress: ArchiveProgressListener?,
        onProgress: (Int) -> Unit
    ) {
        val out = if (password != null) {
            SevenZOutputFile(destFile, password.toCharArray())
        } else {
            SevenZOutputFile(destFile)
        }
        val counter = LongArray(2) // [0] = files, [1] = bytes
        val startTime = System.currentTimeMillis()

        out.use { sevenZOut ->
            sourceFiles.forEach { file ->
                addFileTo7z(sevenZOut, file, "", destFile.name, totalFiles, totalBytes, counter, startTime, onArchiveProgress, onProgress)
            }
        }
        onProgress(100)
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
        totalFiles: Int,
        totalBytes: Long,
        onArchiveProgress: ArchiveProgressListener?,
        onProgress: (Int) -> Unit
    ) {
        val counter = LongArray(2)
        val startTime = System.currentTimeMillis()
        getCompressorOutputStream(destFile, compressor).use { compOut ->
            TarArchiveOutputStream(compOut).use { tarOut ->
                tarOut.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                sourceFiles.forEach { file ->
                    addFileToTar(tarOut, file, "", destFile.name, totalFiles, totalBytes, counter, startTime, onArchiveProgress, onProgress)
                }
            }
        }
        onProgress(100)
    }

    private fun addFileToTar(
        out: TarArchiveOutputStream,
        file: File,
        parentPath: String,
        destArchiveName: String,
        totalFiles: Int,
        totalBytes: Long,
        counter: LongArray,
        startTime: Long,
        onArchiveProgress: ArchiveProgressListener?,
        onProgress: (Int) -> Unit
    ) {
        if (Thread.currentThread().isInterrupted) throw kotlinx.coroutines.CancellationException("Compression cancelled")
        val entryPath = if (parentPath.isEmpty()) file.name else "$parentPath/${file.name}"
        val entry = TarArchiveEntry(file, entryPath)
        out.putArchiveEntry(entry)
        if (file.isFile) {
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var len: Int
                while (input.read(buffer).also { len = it } > 0) {
                    if (Thread.currentThread().isInterrupted) throw kotlinx.coroutines.CancellationException("Compression cancelled")
                    out.write(buffer, 0, len)
                    counter[1] += len
                }
            }
            counter[0]++
            val pct = if (totalBytes > 0) ((counter[1] * 100) / totalBytes).toInt().coerceIn(0, 100)
                      else ((counter[0] * 100) / totalFiles.coerceAtLeast(1)).toInt().coerceIn(0, 100)
            val elapsed = System.currentTimeMillis() - startTime
            val speed = if (elapsed > 500) (counter[1] * 1000L) / elapsed else 0L
            val eta = if (speed > 0 && totalBytes > counter[1]) ((totalBytes - counter[1]) * 1000L) / speed else 0L
            onArchiveProgress?.onProgress(
                ArchiveProgress(
                    operation = ArchiveOperationType.COMPRESS,
                    archiveName = destArchiveName,
                    currentFileName = file.name,
                    fileIndex = counter[0].toInt(),
                    totalFiles = totalFiles,
                    bytesProcessed = counter[1],
                    totalBytes = totalBytes,
                    percentage = pct,
                    speedBytesPerSec = speed,
                    estimatedRemainingMs = eta
                )
            )
            onProgress(pct)
        }
        out.closeArchiveEntry()
        if (file.isDirectory) {
            file.listFiles()?.forEach { child ->
                addFileToTar(out, child, entryPath, destArchiveName, totalFiles, totalBytes, counter, startTime, onArchiveProgress, onProgress)
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
        onArchiveProgress: ArchiveProgressListener? = null,
        onProgress: (Int) -> Unit = {},
        onConflict: (suspend (file: File, isFolder: Boolean, destSizeBytes: Long, applyToAllRef: BooleanArray) -> TransferConflictHelper.ConflictAction)? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val isSourceSaf = archiveFile is za.kilowatch.ultimatefilemanager.storage.SafFile ||
                          za.kilowatch.ultimatefilemanager.storage.SafTreeManager.isSafPath(archiveFile.absolutePath) ||
                          za.kilowatch.ultimatefilemanager.storage.SafTreeManager.hasTreePermissionForPath(context, archiveFile.absolutePath)
        val isDestSaf = destDir is za.kilowatch.ultimatefilemanager.storage.SafFile ||
                        za.kilowatch.ultimatefilemanager.storage.SafTreeManager.isSafPath(destDir.absolutePath) ||
                        za.kilowatch.ultimatefilemanager.storage.SafTreeManager.hasTreePermissionForPath(context, destDir.absolutePath)

        var tempArchive: File? = null
        var tempExtractDir: File? = null

        try {
            val effectiveArchive = if (isSourceSaf) {
                tempArchive = File(context.cacheDir, "source_archive_${System.currentTimeMillis()}_${archiveFile.name}")
                za.kilowatch.ultimatefilemanager.storage.SafTreeManager.openInputStream(context, archiveFile.absolutePath)?.use { input ->
                    tempArchive.outputStream().use { output -> input.copyTo(output) }
                } ?: throw java.io.FileNotFoundException("Cannot open archive ${archiveFile.name}")
                tempArchive
            } else {
                archiveFile
            }

            val effectiveDest = if (isDestSaf) {
                tempExtractDir = File(context.cacheDir, "extracted_${System.currentTimeMillis()}").apply { mkdirs() }
                tempExtractDir
            } else {
                destDir
            }

            val name = effectiveArchive.name.lowercase()
            when {
                name.endsWith(".tar.gz") || name.endsWith(".tgz") -> extractTarStream(effectiveArchive, effectiveDest, CompressorStream.GZIP, onArchiveProgress, onProgress, onConflict)
                name.endsWith(".tar.bz2") || name.endsWith(".tbz2") || name.endsWith(".tbz") -> extractTarStream(effectiveArchive, effectiveDest, CompressorStream.BZIP2, onArchiveProgress, onProgress, onConflict)
                name.endsWith(".tar.xz") || name.endsWith(".txz") -> extractTarStream(effectiveArchive, effectiveDest, CompressorStream.XZ, onArchiveProgress, onProgress, onConflict)
                name.endsWith(".tar.zst") || name.endsWith(".tzst") -> extractTarStream(effectiveArchive, effectiveDest, CompressorStream.ZSTD, onArchiveProgress, onProgress, onConflict)
                name.endsWith(".tar") -> extractTarStream(effectiveArchive, effectiveDest, CompressorStream.NONE, onArchiveProgress, onProgress, onConflict)
                name.endsWith(".rar") -> extractRar(effectiveArchive, effectiveDest, password, onArchiveProgress, onProgress, onConflict)
                name.endsWith(".gz") -> extractSingleStreamOrTar(effectiveArchive, effectiveDest, CompressorStream.GZIP, onArchiveProgress, onProgress, onConflict)
                name.endsWith(".bz2") -> extractSingleStreamOrTar(effectiveArchive, effectiveDest, CompressorStream.BZIP2, onArchiveProgress, onProgress, onConflict)
                name.endsWith(".xz") -> extractSingleStreamOrTar(effectiveArchive, effectiveDest, CompressorStream.XZ, onArchiveProgress, onProgress, onConflict)
                name.endsWith(".zst") -> extractSingleStreamOrTar(effectiveArchive, effectiveDest, CompressorStream.ZSTD, onArchiveProgress, onProgress, onConflict)
                effectiveArchive.extension.lowercase() == "zip" -> extractZip(context, effectiveArchive, effectiveDest, password, onArchiveProgress, onProgress, onConflict)
                effectiveArchive.extension.lowercase() == "7z" -> extract7z(context, effectiveArchive, effectiveDest, password, onArchiveProgress, onProgress, onConflict)
                else -> throw IllegalArgumentException(context.getString(R.string.unsupported_archive_format_extension, effectiveArchive.extension))
            }

            if (isDestSaf && tempExtractDir != null) {
                for (subFile in tempExtractDir.walkTopDown()) {
                    val rel = subFile.relativeTo(tempExtractDir).path
                    if (rel.isEmpty()) continue
                    val targetSafPath = za.kilowatch.ultimatefilemanager.storage.SafTreeManager.getSafChildPath(destDir.absolutePath, rel)
                    if (subFile.isDirectory) {
                        za.kilowatch.ultimatefilemanager.storage.SafTreeManager.createFolder(context, targetSafPath)
                    } else {
                        val targetDestFile = za.kilowatch.ultimatefilemanager.storage.SafFile(targetSafPath)
                        TransferConflictHelper.copyLocalToLocalAtomic(
                            src = subFile,
                            dest = targetDestFile,
                            action = TransferConflictHelper.ConflictAction.OVERWRITE
                        )
                    }
                }
            }

            Result.success(Unit)
        } catch (e: OutOfMemoryError) {
            Result.failure(Exception(context.getString(R.string.error_not_enough_memory), e))
        } catch (e: org.apache.commons.compress.MemoryLimitException) {
            Result.failure(Exception(context.getString(R.string.error_not_enough_memory), e))
        } catch (e: LinkageError) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            tempArchive?.delete()
            tempExtractDir?.deleteRecursively()
        }
    }

    private suspend fun extractSingleStreamOrTar(
        archiveFile: File,
        destDir: File,
        compressor: CompressorStream,
        onArchiveProgress: ArchiveProgressListener? = null,
        onProgress: (Int) -> Unit = {},
        onConflict: (suspend (file: File, isFolder: Boolean, destSizeBytes: Long, applyToAllRef: BooleanArray) -> TransferConflictHelper.ConflictAction)? = null
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
            extractTarStream(archiveFile, destDir, compressor, onArchiveProgress, onProgress, onConflict)
        } else {
            extractSingleStream(archiveFile, destDir, compressor, onArchiveProgress, onProgress, onConflict)
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
        onArchiveProgress: ArchiveProgressListener? = null,
        onProgress: (Int) -> Unit = {},
        onConflict: (suspend (file: File, isFolder: Boolean, destSizeBytes: Long, applyToAllRef: BooleanArray) -> TransferConflictHelper.ConflictAction)? = null
    ) {
        val canonicalDest = destDir.canonicalPath
        var applyToAllAction: TransferConflictHelper.ConflictAction? = null
        val totalBytes = archiveFile.length()
        var bytesProcessed = 0L
        var entryIndex = 0
        val startTime = System.currentTimeMillis()

        getDecompressedInputStream(archiveFile, compressor).use { decIn ->
            val tarIn = TarArchiveInputStream(decIn)
            var entry = tarIn.nextEntry
            while (entry != null) {
                if (Thread.currentThread().isInterrupted) throw kotlinx.coroutines.CancellationException("Extraction cancelled")
                val currentEntry = entry
                val outFile = File(destDir, currentEntry.name)
                val canonicalOut = try {
                    outFile.canonicalPath
                } catch (e: java.io.IOException) {
                    Log.w(TAG, "Skipping entry with unresolvable path: ${currentEntry.name}")
                    entry = tarIn.nextEntry
                    continue
                }
                if (!canonicalOut.startsWith(canonicalDest + File.separator) && canonicalOut != canonicalDest) {
                    Log.w(TAG, "Zip Slip attempt detected! Skipping entry: ${currentEntry.name}")
                    entry = tarIn.nextEntry
                    continue
                }

                var targetFile = outFile
                if (currentEntry.isDirectory) {
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
                        val buffer = ByteArray(16384)
                        var len: Int
                        while (tarIn.read(buffer).also { len = it } > 0) {
                            if (Thread.currentThread().isInterrupted) throw kotlinx.coroutines.CancellationException("Extraction cancelled")
                            output.write(buffer, 0, len)
                            bytesProcessed += len
                        }
                    }
                }

                entryIndex++
                val pct = if (totalBytes > 0) ((bytesProcessed * 100) / totalBytes).toInt().coerceIn(0, 100) else 0
                val elapsed = System.currentTimeMillis() - startTime
                val speed = if (elapsed > 500) (bytesProcessed * 1000L) / elapsed else 0L
                val eta = if (speed > 0 && totalBytes > bytesProcessed) ((totalBytes - bytesProcessed) * 1000L) / speed else 0L

                onArchiveProgress?.onProgress(
                    ArchiveProgress(
                        operation = ArchiveOperationType.EXTRACT,
                        archiveName = archiveFile.name,
                        currentFileName = currentEntry.name,
                        fileIndex = entryIndex,
                        totalFiles = 0,
                        bytesProcessed = bytesProcessed,
                        totalBytes = totalBytes,
                        percentage = pct,
                        speedBytesPerSec = speed,
                        estimatedRemainingMs = eta
                    )
                )
                onProgress(pct)

                entry = tarIn.nextEntry
            }
        }
    }

    private suspend fun extractSingleStream(
        archiveFile: File,
        destDir: File,
        compressor: CompressorStream,
        onArchiveProgress: ArchiveProgressListener? = null,
        onProgress: (Int) -> Unit = {},
        onConflict: (suspend (file: File, isFolder: Boolean, destSizeBytes: Long, applyToAllRef: BooleanArray) -> TransferConflictHelper.ConflictAction)? = null
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
        val totalBytes = archiveFile.length()
        var bytesProcessed = 0L
        val startTime = System.currentTimeMillis()

        getDecompressedInputStream(archiveFile, compressor).use { input ->
            targetFile.outputStream().use { output ->
                val buffer = ByteArray(16384)
                var len: Int
                while (input.read(buffer).also { len = it } > 0) {
                    if (Thread.currentThread().isInterrupted) throw kotlinx.coroutines.CancellationException("Extraction cancelled")
                    output.write(buffer, 0, len)
                    bytesProcessed += len
                    val pct = if (totalBytes > 0) ((bytesProcessed * 100) / totalBytes).toInt().coerceIn(0, 100) else 0
                    val elapsed = System.currentTimeMillis() - startTime
                    val speed = if (elapsed > 500) (bytesProcessed * 1000L) / elapsed else 0L
                    val eta = if (speed > 0 && totalBytes > bytesProcessed) ((totalBytes - bytesProcessed) * 1000L) / speed else 0L
                    onArchiveProgress?.onProgress(
                        ArchiveProgress(
                            operation = ArchiveOperationType.EXTRACT,
                            archiveName = archiveFile.name,
                            currentFileName = targetName,
                            fileIndex = 1,
                            totalFiles = 1,
                            bytesProcessed = bytesProcessed,
                            totalBytes = totalBytes,
                            percentage = pct,
                            speedBytesPerSec = speed,
                            estimatedRemainingMs = eta
                        )
                    )
                    onProgress(pct)
                }
            }
        }
    }

    private suspend fun extractRar(
        archiveFile: File,
        destDir: File,
        password: String?,
        onArchiveProgress: ArchiveProgressListener? = null,
        onProgress: (Int) -> Unit = {},
        onConflict: (suspend (file: File, isFolder: Boolean, destSizeBytes: Long, applyToAllRef: BooleanArray) -> TransferConflictHelper.ConflictAction)? = null
    ) {
        val canonicalDest = destDir.canonicalPath
        var applyToAllAction: TransferConflictHelper.ConflictAction? = null
        val startTime = System.currentTimeMillis()

        val archive = if (password != null) Archive(archiveFile, password) else Archive(archiveFile)
        archive.use { rar ->
            val headers = rar.fileHeaders
            val totalFiles = headers.size
            val totalBytes = headers.sumOf { it.unpSize }
            var bytesProcessed = 0L

            headers.forEachIndexed { index, header ->
                if (Thread.currentThread().isInterrupted) throw kotlinx.coroutines.CancellationException("Extraction cancelled")
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
                    bytesProcessed += header.unpSize
                }

                val pct = if (totalBytes > 0) ((bytesProcessed * 100) / totalBytes).toInt().coerceIn(0, 100)
                          else (((index + 1) * 100) / headers.size.coerceAtLeast(1)).coerceIn(0, 100)
                val elapsed = System.currentTimeMillis() - startTime
                val speed = if (elapsed > 500) (bytesProcessed * 1000L) / elapsed else 0L
                val eta = if (speed > 0 && totalBytes > bytesProcessed) ((totalBytes - bytesProcessed) * 1000L) / speed else 0L

                onArchiveProgress?.onProgress(
                    ArchiveProgress(
                        operation = ArchiveOperationType.EXTRACT,
                        archiveName = archiveFile.name,
                        currentFileName = fileName,
                        fileIndex = index + 1,
                        totalFiles = totalFiles,
                        bytesProcessed = bytesProcessed,
                        totalBytes = totalBytes,
                        percentage = pct,
                        speedBytesPerSec = speed,
                        estimatedRemainingMs = eta
                    )
                )
                onProgress(pct)
            }
        }
    }

    private suspend fun extractZip(
        context: Context,
        archiveFile: File,
        destDir: File,
        password: String?,
        onArchiveProgress: ArchiveProgressListener? = null,
        onProgress: (Int) -> Unit = {},
        onConflict: (suspend (file: File, isFolder: Boolean, destSizeBytes: Long, applyToAllRef: BooleanArray) -> TransferConflictHelper.ConflictAction)? = null
    ) {
        val zipFile = ZipFile(archiveFile)
        if (zipFile.isEncrypted && password != null) {
            zipFile.setPassword(password.toCharArray())
        }

        val canonicalDest = destDir.canonicalPath
        val headers = zipFile.fileHeaders
        val totalFiles = headers.size
        val totalBytes = headers.sumOf { it.uncompressedSize }
        var bytesProcessed = 0L
        val startTime = System.currentTimeMillis()
        var applyToAllAction: TransferConflictHelper.ConflictAction? = null

        headers.forEachIndexed { index, header ->
            if (Thread.currentThread().isInterrupted) throw kotlinx.coroutines.CancellationException("Extraction cancelled")
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
                zipFile.getInputStream(header).use { input ->
                    targetFile.outputStream().use { output ->
                        val buffer = ByteArray(16384)
                        var len: Int
                        while (input.read(buffer).also { len = it } > 0) {
                            if (Thread.currentThread().isInterrupted) throw kotlinx.coroutines.CancellationException("Extraction cancelled")
                            output.write(buffer, 0, len)
                            bytesProcessed += len
                        }
                    }
                }
            }

            val pct = if (totalBytes > 0) ((bytesProcessed * 100) / totalBytes).toInt().coerceIn(0, 100)
                      else (((index + 1) * 100) / totalFiles.coerceAtLeast(1)).coerceIn(0, 100)
            val elapsed = System.currentTimeMillis() - startTime
            val speed = if (elapsed > 500) (bytesProcessed * 1000L) / elapsed else 0L
            val eta = if (speed > 0 && totalBytes > bytesProcessed) ((totalBytes - bytesProcessed) * 1000L) / speed else 0L

            onArchiveProgress?.onProgress(
                ArchiveProgress(
                    operation = ArchiveOperationType.EXTRACT,
                    archiveName = archiveFile.name,
                    currentFileName = header.fileName,
                    fileIndex = index + 1,
                    totalFiles = totalFiles,
                    bytesProcessed = bytesProcessed,
                    totalBytes = totalBytes,
                    percentage = pct,
                    speedBytesPerSec = speed,
                    estimatedRemainingMs = eta
                )
            )
            onProgress(pct)
        }
    }

    private suspend fun extract7z(
        context: Context,
        archiveFile: File,
        destDir: File,
        password: String?,
        onArchiveProgress: ArchiveProgressListener? = null,
        onProgress: (Int) -> Unit = {},
        onConflict: (suspend (file: File, isFolder: Boolean, destSizeBytes: Long, applyToAllRef: BooleanArray) -> TransferConflictHelper.ConflictAction)? = null
    ) {
        val sevenZFile = createSevenZFile(archiveFile, password)

        sevenZFile.use { archive ->
            val canonicalDest = destDir.canonicalPath
            val entries = archive.entries.toList()
            val totalFiles = entries.size
            val totalBytes = entries.sumOf { it.size }
            var bytesProcessed = 0L
            val startTime = System.currentTimeMillis()
            var applyToAllAction: TransferConflictHelper.ConflictAction? = null
            var entryIndex = 0

            var entry = archive.nextEntry
            while (entry != null) {
                if (Thread.currentThread().isInterrupted) throw kotlinx.coroutines.CancellationException("Extraction cancelled")
                val currentEntry = entry
                val outFile = File(destDir, currentEntry.name)
                val canonicalOut = try {
                    outFile.canonicalPath
                } catch (e: java.io.IOException) {
                    Log.w(TAG, "Skipping entry with unresolvable path: ${currentEntry.name}")
                    entry = archive.nextEntry
                    entryIndex++
                    continue
                }
                if (!canonicalOut.startsWith(canonicalDest + File.separator) && canonicalOut != canonicalDest) {
                    Log.w(TAG, "Zip Slip attempt detected! Skipping entry: ${currentEntry.name}")
                    entry = archive.nextEntry
                    entryIndex++
                    continue
                }

                var targetFile = outFile
                if (currentEntry.isDirectory) {
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
                                entryIndex++
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
                        val buffer = ByteArray(16384)
                        var len: Int
                        while (archive.read(buffer).also { len = it } > 0) {
                            if (Thread.currentThread().isInterrupted) throw kotlinx.coroutines.CancellationException("Extraction cancelled")
                            output.write(buffer, 0, len)
                            bytesProcessed += len
                        }
                    }
                }

                entryIndex++
                val pct = if (totalBytes > 0) ((bytesProcessed * 100) / totalBytes).toInt().coerceIn(0, 100)
                          else ((entryIndex * 100) / totalFiles.coerceAtLeast(1)).coerceIn(0, 100)
                val elapsed = System.currentTimeMillis() - startTime
                val speed = if (elapsed > 500) (bytesProcessed * 1000L) / elapsed else 0L
                val eta = if (speed > 0 && totalBytes > bytesProcessed) ((totalBytes - bytesProcessed) * 1000L) / speed else 0L

                onArchiveProgress?.onProgress(
                    ArchiveProgress(
                        operation = ArchiveOperationType.EXTRACT,
                        archiveName = archiveFile.name,
                        currentFileName = currentEntry.name,
                        fileIndex = entryIndex,
                        totalFiles = totalFiles,
                        bytesProcessed = bytesProcessed,
                        totalBytes = totalBytes,
                        percentage = pct,
                        speedBytesPerSec = speed,
                        estimatedRemainingMs = eta
                    )
                )
                onProgress(pct)

                entry = archive.nextEntry
            }
        }
    }

    private fun addFileTo7z(
        out: SevenZOutputFile,
        file: File,
        parentPath: String,
        destArchiveName: String,
        totalFiles: Int,
        totalBytes: Long,
        counter: LongArray,
        startTime: Long,
        onArchiveProgress: ArchiveProgressListener?,
        onProgress: (Int) -> Unit
    ) {
        if (Thread.currentThread().isInterrupted) throw kotlinx.coroutines.CancellationException("Compression cancelled")
        val entryPath = if (parentPath.isEmpty()) file.name else "$parentPath/${file.name}"
        val entry = out.createArchiveEntry(file, entryPath)
        out.putArchiveEntry(entry)
        
        if (file.isDirectory) {
            out.closeArchiveEntry()
            file.listFiles()?.forEach { child ->
                addFileTo7z(out, child, entryPath, destArchiveName, totalFiles, totalBytes, counter, startTime, onArchiveProgress, onProgress)
            }
        } else {
            FileInputStream(file).use { input ->
                val buffer = ByteArray(8192)
                var len: Int
                while (input.read(buffer).also { len = it } > 0) {
                    if (Thread.currentThread().isInterrupted) throw kotlinx.coroutines.CancellationException("Compression cancelled")
                    out.write(buffer, 0, len)
                    counter[1] += len
                }
            }
            out.closeArchiveEntry()
            counter[0]++
            val pct = if (totalBytes > 0) ((counter[1] * 100) / totalBytes).toInt().coerceIn(0, 100)
                      else ((counter[0] * 100) / totalFiles.coerceAtLeast(1)).toInt().coerceIn(0, 100)
            val elapsed = System.currentTimeMillis() - startTime
            val speed = if (elapsed > 500) (counter[1] * 1000L) / elapsed else 0L
            val eta = if (speed > 0 && totalBytes > counter[1]) ((totalBytes - counter[1]) * 1000L) / speed else 0L
            onArchiveProgress?.onProgress(
                ArchiveProgress(
                    operation = ArchiveOperationType.COMPRESS,
                    archiveName = destArchiveName,
                    currentFileName = file.name,
                    fileIndex = counter[0].toInt(),
                    totalFiles = totalFiles,
                    bytesProcessed = counter[1],
                    totalBytes = totalBytes,
                    percentage = pct,
                    speedBytesPerSec = speed,
                    estimatedRemainingMs = eta
                )
            )
            onProgress(pct)
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
        password: String? = null,
        context: Context? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val isDestSaf = destDir is za.kilowatch.ultimatefilemanager.storage.SafFile ||
                        za.kilowatch.ultimatefilemanager.storage.SafTreeManager.isSafPath(destDir.absolutePath) ||
                        (context != null && za.kilowatch.ultimatefilemanager.storage.SafTreeManager.hasTreePermissionForPath(context, destDir.absolutePath))
        val isArchiveSaf = archiveFile is za.kilowatch.ultimatefilemanager.storage.SafFile ||
                           za.kilowatch.ultimatefilemanager.storage.SafTreeManager.isSafPath(archiveFile.absolutePath) ||
                           (context != null && za.kilowatch.ultimatefilemanager.storage.SafTreeManager.hasTreePermissionForPath(context, archiveFile.absolutePath))

        var tempArchiveFile: File? = null
        var tempStagingDir: File? = null
        try {
            val effectiveArchive = if (isArchiveSaf && context != null) {
                tempArchiveFile = File(context.cacheDir, "stage_entry_zip_${System.currentTimeMillis()}_${archiveFile.name}")
                za.kilowatch.ultimatefilemanager.storage.SafTreeManager.openInputStream(context, archiveFile.absolutePath)?.use { inStream ->
                    tempArchiveFile.outputStream().use { outStream -> inStream.copyTo(outStream) }
                }
                tempArchiveFile
            } else {
                archiveFile
            }

            val effectiveDest = if (isDestSaf && context != null) {
                tempStagingDir = File(context.cacheDir, "stage_entry_dest_${System.currentTimeMillis()}").apply { mkdirs() }
                tempStagingDir
            } else {
                destDir
            }

            val zipFile = ZipFile(effectiveArchive)
            if (zipFile.isEncrypted && password != null) {
                zipFile.setPassword(password.toCharArray())
            }
            effectiveDest.mkdirs()
            val canonicalDest = effectiveDest.canonicalPath
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
                val outFile = File(effectiveDest, relativePath)
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

            if (isDestSaf && tempStagingDir != null && context != null) {
                val stagedFiles = tempStagingDir.walkTopDown().toList()
                for (sf in stagedFiles) {
                    if (sf == tempStagingDir) continue
                    val relPath = sf.relativeTo(tempStagingDir).path.replace('\\', '/')
                    val destChild = File(destDir, relPath)
                    if (sf.isDirectory) {
                        za.kilowatch.ultimatefilemanager.storage.SafTreeManager.createFolder(context, destChild.absolutePath)
                    } else {
                        TransferConflictHelper.copyLocalToLocalAtomic(
                            sf,
                            destChild,
                            TransferConflictHelper.ConflictAction.OVERWRITE
                        )
                    }
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            tempArchiveFile?.delete()
            tempStagingDir?.deleteRecursively()
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
        password: String? = null,
        context: Context? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val extractRes = extractZipEntry(archiveFile, entryPath, destDir, password, context)
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
        password: String? = null,
        context: Context? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val isDestSaf = destDir is za.kilowatch.ultimatefilemanager.storage.SafFile ||
                        za.kilowatch.ultimatefilemanager.storage.SafTreeManager.isSafPath(destDir.absolutePath) ||
                        (context != null && za.kilowatch.ultimatefilemanager.storage.SafTreeManager.hasTreePermissionForPath(context, destDir.absolutePath))
        val isArchiveSaf = archiveFile is za.kilowatch.ultimatefilemanager.storage.SafFile ||
                           za.kilowatch.ultimatefilemanager.storage.SafTreeManager.isSafPath(archiveFile.absolutePath) ||
                           (context != null && za.kilowatch.ultimatefilemanager.storage.SafTreeManager.hasTreePermissionForPath(context, archiveFile.absolutePath))

        var tempArchiveFile: File? = null
        var tempStagingDir: File? = null
        try {
            val effectiveArchive = if (isArchiveSaf && context != null) {
                tempArchiveFile = File(context.cacheDir, "stage_entry_arc_${System.currentTimeMillis()}_${archiveFile.name}")
                za.kilowatch.ultimatefilemanager.storage.SafTreeManager.openInputStream(context, archiveFile.absolutePath)?.use { inStream ->
                    tempArchiveFile.outputStream().use { outStream -> inStream.copyTo(outStream) }
                }
                tempArchiveFile
            } else {
                archiveFile
            }

            val effectiveDest = if (isDestSaf && context != null) {
                tempStagingDir = File(context.cacheDir, "stage_entry_dest_${System.currentTimeMillis()}").apply { mkdirs() }
                tempStagingDir
            } else {
                destDir
            }

            val ext = effectiveArchive.name.lowercase()
            val res = when {
                ext.endsWith(".tar.gz") || ext.endsWith(".tgz") -> extractTarEntry(effectiveArchive, entryPath, effectiveDest, CompressorStream.GZIP)
                ext.endsWith(".tar.bz2") || ext.endsWith(".tbz2") || ext.endsWith(".tbz") -> extractTarEntry(effectiveArchive, entryPath, effectiveDest, CompressorStream.BZIP2)
                ext.endsWith(".tar.xz") || ext.endsWith(".txz") -> extractTarEntry(effectiveArchive, entryPath, effectiveDest, CompressorStream.XZ)
                ext.endsWith(".tar.zst") || ext.endsWith(".tzst") -> extractTarEntry(effectiveArchive, entryPath, effectiveDest, CompressorStream.ZSTD)
                ext.endsWith(".tar") -> extractTarEntry(effectiveArchive, entryPath, effectiveDest, CompressorStream.NONE)
                ext.endsWith(".rar") -> extractRarEntry(effectiveArchive, entryPath, effectiveDest, password)
                ext.endsWith(".gz") -> extractTarEntry(effectiveArchive, entryPath, effectiveDest, CompressorStream.GZIP)
                ext.endsWith(".bz2") -> extractTarEntry(effectiveArchive, entryPath, effectiveDest, CompressorStream.BZIP2)
                ext.endsWith(".xz") -> extractTarEntry(effectiveArchive, entryPath, effectiveDest, CompressorStream.XZ)
                ext.endsWith(".zst") -> extractTarEntry(effectiveArchive, entryPath, effectiveDest, CompressorStream.ZSTD)
                effectiveArchive.extension.lowercase() == "zip" -> extractZipEntry(effectiveArchive, entryPath, effectiveDest, password)
                effectiveArchive.extension.lowercase() == "7z" -> extract7zEntry(effectiveArchive, entryPath, effectiveDest, password)
                else -> Result.failure<Unit>(IllegalArgumentException("Unsupported archive format"))
            }

            if (res.isSuccess && isDestSaf && tempStagingDir != null && context != null) {
                val stagedFiles = tempStagingDir.walkTopDown().toList()
                for (sf in stagedFiles) {
                    if (sf == tempStagingDir) continue
                    val relPath = sf.relativeTo(tempStagingDir).path.replace('\\', '/')
                    val destChild = File(destDir, relPath)
                    if (sf.isDirectory) {
                        za.kilowatch.ultimatefilemanager.storage.SafTreeManager.createFolder(context, destChild.absolutePath)
                    } else {
                        TransferConflictHelper.copyLocalToLocalAtomic(
                            sf,
                            destChild,
                            TransferConflictHelper.ConflictAction.OVERWRITE
                        )
                    }
                }
            }
            res
        } finally {
            tempArchiveFile?.delete()
            tempStagingDir?.deleteRecursively()
        }
    }

    /**
     * Moves a single entry (or directory) out of an archive of any format: extracts to destDir then removes from archive.
     */
    suspend fun moveArchiveEntry(
        archiveFile: File,
        entryPath: String,
        destDir: File,
        password: String? = null,
        context: Context? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val extractRes = extractArchiveEntry(archiveFile, entryPath, destDir, password, context)
        if (extractRes.isFailure) return@withContext extractRes
        deleteArchiveEntry(archiveFile, entryPath, password)
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

    /**
     * Returns true if the given file is an archive format that supports adding/modifying files.
     */
    fun isWritableArchive(file: File): Boolean {
        val name = file.name.lowercase()
        return name.endsWith(".zip") ||
               name.endsWith(".7z") ||
               name.endsWith(".tar") ||
               name.endsWith(".tar.gz") || name.endsWith(".tgz") || name.endsWith(".gz") ||
               name.endsWith(".tar.bz2") || name.endsWith(".tbz2") || name.endsWith(".tbz") || name.endsWith(".bz2") ||
               name.endsWith(".tar.xz") || name.endsWith(".txz") || name.endsWith(".xz") ||
               name.endsWith(".tar.zst") || name.endsWith(".tzst") || name.endsWith(".zst")
    }

    /**
     * Universal non-extracting insertion into an archive.
     * Supports ZIP, 7Z, TAR, and compressed TAR variants (.tar.gz, .tar.bz2, .tar.xz, .tar.zst, .gz, .bz2, .xz, .zst).
     * If [isMove] is true, deletes source files upon successful addition.
     */
    suspend fun addFilesToArchive(
        context: Context? = null,
        archiveFile: File,
        sourceFiles: List<File>,
        targetDirInArchive: String = "",
        isMove: Boolean = false,
        password: String? = null,
        onArchiveProgress: ArchiveProgressListener? = null,
        onProgress: (current: Int, total: Int, fileName: String) -> Unit = { _, _, _ -> }
    ): Result<Int> = withContext(Dispatchers.IO) {
        val isArchiveSaf = archiveFile is za.kilowatch.ultimatefilemanager.storage.SafFile ||
                           za.kilowatch.ultimatefilemanager.storage.SafTreeManager.isSafPath(archiveFile.absolutePath) ||
                           (context != null && za.kilowatch.ultimatefilemanager.storage.SafTreeManager.hasTreePermissionForPath(context, archiveFile.absolutePath))

        val anySourceSaf = context != null && sourceFiles.any {
            it is za.kilowatch.ultimatefilemanager.storage.SafFile ||
            za.kilowatch.ultimatefilemanager.storage.SafTreeManager.isSafPath(it.absolutePath) ||
            za.kilowatch.ultimatefilemanager.storage.SafTreeManager.hasTreePermissionForPath(context, it.absolutePath)
        }

        var tempArchiveFile: File? = null
        var tempSourceDir: File? = null

        try {
            val effectiveArchive = if (isArchiveSaf && context != null) {
                tempArchiveFile = File(context.cacheDir, "stage_mod_arc_${System.currentTimeMillis()}_${archiveFile.name}")
                za.kilowatch.ultimatefilemanager.storage.SafTreeManager.openInputStream(context, archiveFile.absolutePath)?.use { inStream ->
                    tempArchiveFile.outputStream().use { outStream -> inStream.copyTo(outStream) }
                } ?: return@withContext Result.failure(java.io.FileNotFoundException("Cannot access archive: ${archiveFile.name}"))
                tempArchiveFile
            } else {
                archiveFile
            }

            val effectiveSources = if (anySourceSaf && context != null) {
                tempSourceDir = File(context.cacheDir, "stage_add_src_${System.currentTimeMillis()}").apply { mkdirs() }
                sourceFiles.map { sf ->
                    val isSfSaf = sf is za.kilowatch.ultimatefilemanager.storage.SafFile ||
                                  za.kilowatch.ultimatefilemanager.storage.SafTreeManager.isSafPath(sf.absolutePath) ||
                                  za.kilowatch.ultimatefilemanager.storage.SafTreeManager.hasTreePermissionForPath(context, sf.absolutePath)
                    if (isSfSaf) {
                        val staged = File(tempSourceDir, sf.name)
                        val isDir = (sf as? za.kilowatch.ultimatefilemanager.storage.SafFile)?.isDirectory() == true ||
                                    za.kilowatch.ultimatefilemanager.storage.SafTreeManager.isDirectory(context, sf.absolutePath)
                        if (isDir) {
                            staged.mkdirs()
                            val children = za.kilowatch.ultimatefilemanager.storage.SafTreeManager.walkSafTopDown(context, sf.absolutePath)
                            for (child in children) {
                                val rel = child.absolutePath.removePrefix(sf.absolutePath).trimStart('/')
                                if (rel.isEmpty()) continue
                                val destChild = File(staged, rel)
                                if (child.isDirectory) {
                                    destChild.mkdirs()
                                } else {
                                    destChild.parentFile?.mkdirs()
                                    za.kilowatch.ultimatefilemanager.storage.SafTreeManager.openInputStream(context, child.absolutePath)?.use { inStream ->
                                        destChild.outputStream().use { outStream -> inStream.copyTo(outStream) }
                                    }
                                }
                            }
                        } else {
                            za.kilowatch.ultimatefilemanager.storage.SafTreeManager.openInputStream(context, sf.absolutePath)?.use { inStream ->
                                staged.outputStream().use { outStream -> inStream.copyTo(outStream) }
                            }
                        }
                        staged
                    } else {
                        sf
                    }
                }
            } else {
                sourceFiles
            }

            val cleanTarget = targetDirInArchive.trim('/').replace('\\', '/')
            val name = effectiveArchive.name.lowercase()
            val res = when {
                name.endsWith(".tar.gz") || name.endsWith(".tgz") || name.endsWith(".gz") -> addFilesToTarStream(effectiveArchive, effectiveSources, cleanTarget, CompressorStream.GZIP, isMove, onArchiveProgress, onProgress)
                name.endsWith(".tar.bz2") || name.endsWith(".tbz2") || name.endsWith(".tbz") || name.endsWith(".bz2") -> addFilesToTarStream(effectiveArchive, effectiveSources, cleanTarget, CompressorStream.BZIP2, isMove, onArchiveProgress, onProgress)
                name.endsWith(".tar.xz") || name.endsWith(".txz") || name.endsWith(".xz") -> addFilesToTarStream(effectiveArchive, effectiveSources, cleanTarget, CompressorStream.XZ, isMove, onArchiveProgress, onProgress)
                name.endsWith(".tar.zst") || name.endsWith(".tzst") || name.endsWith(".zst") -> addFilesToTarStream(effectiveArchive, effectiveSources, cleanTarget, CompressorStream.ZSTD, isMove, onArchiveProgress, onProgress)
                name.endsWith(".tar") -> addFilesToTarStream(effectiveArchive, effectiveSources, cleanTarget, CompressorStream.NONE, isMove, onArchiveProgress, onProgress)
                name.endsWith(".zip") -> addFilesToZip(effectiveArchive, effectiveSources, cleanTarget, password, isMove, onArchiveProgress, onProgress)
                name.endsWith(".7z") -> addFilesTo7z(effectiveArchive, effectiveSources, cleanTarget, password, isMove, onArchiveProgress, onProgress)
                else -> Result.failure(IllegalArgumentException("Unsupported archive format for adding files: ${archiveFile.name}"))
            }

            if (res.isSuccess) {
                if (isArchiveSaf && tempArchiveFile != null && context != null) {
                    TransferConflictHelper.copyLocalToLocalAtomic(
                        tempArchiveFile,
                        archiveFile,
                        TransferConflictHelper.ConflictAction.OVERWRITE
                    )
                }

                if (isMove) {
                    for (sf in sourceFiles) {
                        val isSfSaf = context != null && (sf is za.kilowatch.ultimatefilemanager.storage.SafFile ||
                                      za.kilowatch.ultimatefilemanager.storage.SafTreeManager.isSafPath(sf.absolutePath) ||
                                      za.kilowatch.ultimatefilemanager.storage.SafTreeManager.hasTreePermissionForPath(context, sf.absolutePath))
                        if (isSfSaf && context != null) {
                            za.kilowatch.ultimatefilemanager.storage.SafTreeManager.delete(context, sf.absolutePath)
                        } else {
                            if (sf.isDirectory) {
                                sf.deleteRecursively()
                            } else {
                                sf.delete()
                            }
                        }
                    }
                }
            }
            res
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            tempArchiveFile?.delete()
            tempSourceDir?.deleteRecursively()
        }
    }

    private fun addFilesToZip(
        archiveFile: File,
        sourceFiles: List<File>,
        targetDirInArchive: String,
        password: String?,
        isMove: Boolean,
        onArchiveProgress: ArchiveProgressListener?,
        onProgress: (current: Int, total: Int, fileName: String) -> Unit
    ): Result<Int> {
        return try {
            val zipFile = ZipFile(archiveFile)
            if ((zipFile.isEncrypted || !password.isNullOrEmpty()) && password != null) {
                zipFile.setPassword(password.toCharArray())
            }

            val isEncrypted = zipFile.isEncrypted || !password.isNullOrEmpty()
            val (totalFiles, totalBytes) = calculateTotalFilesAndBytes(sourceFiles)
            var bytesProcessed = 0L
            val startTime = System.currentTimeMillis()

            var count = 0
            sourceFiles.forEachIndexed { index, file ->
                if (Thread.currentThread().isInterrupted) throw kotlinx.coroutines.CancellationException("Operation cancelled")
                onProgress(index + 1, sourceFiles.size, file.name)
                val params = ZipParameters().apply {
                    if (isEncrypted) {
                        isEncryptFiles = true
                        encryptionMethod = EncryptionMethod.AES
                        aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                    }
                }
                if (file.isDirectory) {
                    if (targetDirInArchive.isNotEmpty()) {
                        params.rootFolderNameInZip = "$targetDirInArchive/${file.name}"
                    }
                    zipFile.addFolder(file, params)
                } else {
                    if (targetDirInArchive.isNotEmpty()) {
                        params.fileNameInZip = "$targetDirInArchive/${file.name}"
                    }
                    zipFile.addFile(file, params)
                }
                count++
                val fileByteSize = if (file.isFile) file.length() else 0L
                bytesProcessed += fileByteSize
                val pct = if (totalBytes > 0) ((bytesProcessed * 100) / totalBytes).toInt().coerceIn(0, 100)
                          else (((index + 1) * 100) / sourceFiles.size.coerceAtLeast(1)).coerceIn(0, 100)
                val elapsed = System.currentTimeMillis() - startTime
                val speed = if (elapsed > 500) (bytesProcessed * 1000L) / elapsed else 0L
                val eta = if (speed > 0 && totalBytes > bytesProcessed) ((totalBytes - bytesProcessed) * 1000L) / speed else 0L

                onArchiveProgress?.onProgress(
                    ArchiveProgress(
                        operation = if (isMove) ArchiveOperationType.MOVE else ArchiveOperationType.ADD,
                        archiveName = archiveFile.name,
                        currentFileName = file.name,
                        fileIndex = count,
                        totalFiles = totalFiles,
                        bytesProcessed = bytesProcessed,
                        totalBytes = totalBytes,
                        percentage = pct,
                        speedBytesPerSec = speed,
                        estimatedRemainingMs = eta
                    )
                )
            }
            Result.success(count)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun addFilesTo7z(
        archiveFile: File,
        sourceFiles: List<File>,
        targetDirInArchive: String,
        password: String?,
        isMove: Boolean,
        onArchiveProgress: ArchiveProgressListener?,
        onProgress: (current: Int, total: Int, fileName: String) -> Unit
    ): Result<Int> {
        val tempFile = File(archiveFile.parentFile, "${archiveFile.name}.tmp_${System.currentTimeMillis()}")
        if (tempFile.exists()) tempFile.delete()

        return try {
            val targetNames = sourceFiles.map {
                if (targetDirInArchive.isEmpty()) it.name else "$targetDirInArchive/${it.name}"
            }.toSet()

            val inSevenZ = createSevenZFile(archiveFile, password)
            val outSevenZ = if (password != null) SevenZOutputFile(tempFile, password.toCharArray()) else SevenZOutputFile(tempFile)
            val (totalFiles, totalBytes) = calculateTotalFilesAndBytes(sourceFiles)
            val counter = LongArray(2)
            val startTime = System.currentTimeMillis()

            inSevenZ.use { inArchive ->
                outSevenZ.use { outArchive ->
                    var entry = inArchive.nextEntry
                    while (entry != null) {
                        val entryName = entry.name
                        val isReplaced = targetNames.any { t -> entryName == t || entryName == "$t/" || entryName.startsWith("$t/") }
                        if (!isReplaced) {
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

                    sourceFiles.forEachIndexed { index, file ->
                        if (Thread.currentThread().isInterrupted) throw kotlinx.coroutines.CancellationException("Operation cancelled")
                        onProgress(index + 1, sourceFiles.size, file.name)
                        addFileTo7z(
                            out = outArchive,
                            file = file,
                            parentPath = targetDirInArchive,
                            destArchiveName = archiveFile.name,
                            totalFiles = totalFiles,
                            totalBytes = totalBytes,
                            counter = counter,
                            startTime = startTime,
                            onArchiveProgress = onArchiveProgress,
                            onProgress = { }
                        )
                    }
                }
            }

            if (!tempFile.exists()) {
                throw java.io.IOException("Failed to create updated 7z archive")
            }

            if (!archiveFile.delete()) {
                Log.w(TAG, "Could not delete original 7z file before replacement")
            }
            if (!tempFile.renameTo(archiveFile)) {
                tempFile.copyTo(archiveFile, overwrite = true)
                tempFile.delete()
            }

            Result.success(sourceFiles.size)
        } catch (e: Exception) {
            tempFile.delete()
            Result.failure(e)
        }
    }

    private fun addFilesToTarStream(
        archiveFile: File,
        sourceFiles: List<File>,
        targetDirInArchive: String,
        compressor: CompressorStream,
        isMove: Boolean,
        onArchiveProgress: ArchiveProgressListener?,
        onProgress: (current: Int, total: Int, fileName: String) -> Unit
    ): Result<Int> {
        val tempFile = File(archiveFile.parentFile, "${archiveFile.name}.tmp_${System.currentTimeMillis()}")
        if (tempFile.exists()) tempFile.delete()

        return try {
            val targetNames = sourceFiles.map {
                if (targetDirInArchive.isEmpty()) it.name else "$targetDirInArchive/${it.name}"
            }.toSet()

            var hadTarEntries = false
            val (totalFiles, totalBytes) = calculateTotalFilesAndBytes(sourceFiles)
            val counter = LongArray(2)
            val startTime = System.currentTimeMillis()

            getDecompressedInputStream(archiveFile, compressor).use { decIn ->
                TarArchiveInputStream(decIn).use { tarIn ->
                    getCompressedOutputStream(tempFile, compressor).use { compOut ->
                        TarArchiveOutputStream(compOut).use { tarOut ->
                            tarOut.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                            tarOut.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)

                            try {
                                var entry = tarIn.nextEntry
                                while (entry != null) {
                                    hadTarEntries = true
                                    val entryName = entry.name
                                    val isReplaced = targetNames.any { t -> entryName == t || entryName == "$t/" || entryName.startsWith("$t/") }
                                    if (!isReplaced) {
                                        tarOut.putArchiveEntry(entry)
                                        if (!entry.isDirectory) {
                                            tarIn.copyTo(tarOut)
                                        }
                                        tarOut.closeArchiveEntry()
                                    }
                                    entry = tarIn.nextEntry
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Archive is not a standard tar stream: ${e.message}")
                            }

                            if (!hadTarEntries && archiveFile.length() > 0L) {
                                try {
                                    getDecompressedInputStream(archiveFile, compressor).use { rawIn ->
                                        val cleanName = archiveFile.name.replace(Regex("^stage_mod_arc_\\d+_"), "")
                                        val singleName = getArchiveBaseName(cleanName)
                                        if (!targetNames.contains(singleName)) {
                                            val rawBytes = rawIn.readBytes()
                                            if (rawBytes.isNotEmpty()) {
                                                val rawEntry = org.apache.commons.compress.archivers.tar.TarArchiveEntry(singleName).apply {
                                                    size = rawBytes.size.toLong()
                                                    modTime = java.util.Date(archiveFile.lastModified())
                                                }
                                                tarOut.putArchiveEntry(rawEntry)
                                                tarOut.write(rawBytes)
                                                tarOut.closeArchiveEntry()
                                            }
                                        }
                                    }
                                } catch (ex: Exception) {
                                    Log.w(TAG, "Could not import raw single stream into tar: ${ex.message}")
                                }
                            }

                            sourceFiles.forEachIndexed { index, file ->
                                if (Thread.currentThread().isInterrupted) throw kotlinx.coroutines.CancellationException("Operation cancelled")
                                onProgress(index + 1, sourceFiles.size, file.name)
                                addFileToTar(
                                    out = tarOut,
                                    file = file,
                                    parentPath = targetDirInArchive,
                                    destArchiveName = archiveFile.name,
                                    totalFiles = totalFiles,
                                    totalBytes = totalBytes,
                                    counter = counter,
                                    startTime = startTime,
                                    onArchiveProgress = onArchiveProgress,
                                    onProgress = { }
                                )
                            }
                            tarOut.finish()
                        }
                    }
                }
            }

            if (!tempFile.exists()) {
                throw java.io.IOException("Failed to create updated tar archive")
            }

            if (!archiveFile.delete()) {
                Log.w(TAG, "Could not delete original tar file before replacement")
            }
            if (!tempFile.renameTo(archiveFile)) {
                tempFile.copyTo(archiveFile, overwrite = true)
                tempFile.delete()
            }

            Result.success(sourceFiles.size)
        } catch (e: Exception) {
            tempFile.delete()
            Result.failure(e)
        }
    }
}
