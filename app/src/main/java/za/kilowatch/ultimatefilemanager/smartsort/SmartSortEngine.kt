package za.kilowatch.ultimatefilemanager.smartsort

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import za.kilowatch.ultimatefilemanager.network.*

data class SmartSortFileEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    val relativeSubpath: String = ""  // subfolder path from root when recursive+folding
)

interface SmartSortStorage {
    suspend fun listFiles(path: String): List<SmartSortFileEntry>
    suspend fun mkdirs(path: String): Boolean
    suspend fun rename(from: String, to: String): Boolean
    suspend fun exists(path: String): Boolean
    suspend fun writeBytes(path: String, data: ByteArray): Boolean
    suspend fun delete(path: String): Boolean
}

class LocalSmartSortStorage : SmartSortStorage {
    override suspend fun listFiles(path: String): List<SmartSortFileEntry> = withContext(Dispatchers.IO) {
        val dir = File(path)
        if (!dir.exists() || !dir.isDirectory) return@withContext emptyList()
        dir.listFiles()?.mapNotNull { file ->
            if (file.isHidden && file.name.startsWith(".UFM_")) return@mapNotNull null
            SmartSortFileEntry(
                name = file.name,
                path = file.absolutePath,
                isDirectory = file.isDirectory,
                size = if (file.isFile) file.length() else 0L,
                lastModified = file.lastModified()
            )
        }?.toList() ?: emptyList()
    }

    override suspend fun mkdirs(path: String): Boolean = withContext(Dispatchers.IO) {
        File(path).mkdirs()
    }

    override suspend fun rename(from: String, to: String): Boolean = withContext(Dispatchers.IO) {
        val src = File(from)
        val dst = File(to)
        if (dst.exists()) return@withContext false
        if (src.renameTo(dst)) return@withContext true
        try {
            src.copyTo(dst, overwrite = false)
            src.delete()
            true
        } catch (_: Exception) { false }
    }

    override suspend fun exists(path: String): Boolean = withContext(Dispatchers.IO) {
        File(path).exists()
    }

    override suspend fun writeBytes(path: String, data: ByteArray): Boolean = withContext(Dispatchers.IO) {
        try {
            File(path).writeBytes(data)
            true
        } catch (_: Exception) { false }
    }

    override suspend fun delete(path: String): Boolean = withContext(Dispatchers.IO) {
        try {
            File(path).delete()
        } catch (_: Exception) { false }
    }
}

data class SmartSortPreview(
    val categoryCounts: Map<String, Int>,
    val totalFiles: Int,
    val conflicts: List<String>
)

data class SmartSortResult(
    val movedCount: Int,
    val skippedCount: Int,
    val failedCount: Int,
    val failedFiles: List<String>,
    val manifest: SmartSortManifest?
)

class SmartSortEngine {

    suspend fun scanFiles(
        rootPath: String,
        config: SmartSortConfig
    ): List<SmartSortFileEntry> = withContext(Dispatchers.IO) {
        val result = mutableListOf<SmartSortFileEntry>()
        scanRecursive(rootPath, config, result, depth = 0)
        result
    }

    private suspend fun scanRecursive(
        path: String,
        config: SmartSortConfig,
        result: MutableList<SmartSortFileEntry>,
        depth: Int,
        relativeSubpath: String = ""
    ) {
        val s = storageFor(config)
        val entries = s.listFiles(path)
        for (entry in entries) {
            if (entry.name.startsWith(".UFM_")) continue
            if (entry.isDirectory) {
                if (config.recursive && depth < config.maxDepth) {
                    val childRelative = if (config.flattenSubfolders) "" else {
                        if (relativeSubpath.isEmpty()) entry.name else "$relativeSubpath/${entry.name}"
                    }
                    scanRecursive(entry.path, config, result, depth + 1, childRelative)
                }
            } else {
                result.add(entry.copy(relativeSubpath = relativeSubpath))
            }
        }
    }

    suspend fun preview(
        rootPath: String,
        config: SmartSortConfig
    ): SmartSortPreview = withContext(Dispatchers.IO) {
        val files = scanFiles(rootPath, config)
        val counts = mutableMapOf<String, Int>()
        val conflicts = mutableListOf<String>()

        for (file in files) {
            val resolved = resolveTargetDir(file, config, rootPath) ?: continue
            val (targetDir, destStorage) = resolved
            val targetPath = "$targetDir/${file.name}"
            val key = targetDir.substringAfterLast("/")

            counts[key] = (counts[key] ?: 0) + 1

            if (destStorage.exists(targetPath)) {
                conflicts.add(file.name)
            }
        }

        SmartSortPreview(
            categoryCounts = counts,
            totalFiles = counts.values.sum(),
            conflicts = conflicts
        )
    }

    suspend fun execute(
        rootPath: String,
        config: SmartSortConfig,
        onProgress: ((String, Int, Int) -> Unit)? = null
    ): SmartSortResult = withContext(Dispatchers.IO) {
        val sourceStorage = storageFor(config)
        val allFiles = scanFiles(rootPath, config)
        val matchedFiles = allFiles.filter { resolveTargetDir(it, config, rootPath) != null }
        var movedCount = 0
        var skippedCount = 0
        var failedCount = 0
        val failedFiles = mutableListOf<String>()
        val manifestEntries = mutableListOf<SortManifestEntry>()
        val total = matchedFiles.size
        val processed = AtomicInteger(0)
        val createdDirs = mutableSetOf<String>()
        val resolvedTargetDirs = mutableMapOf<String, String>()

        for (file in matchedFiles) {
            kotlinx.coroutines.yield()
            val count = processed.incrementAndGet()
            onProgress?.invoke(file.name, count, total)

            val categoryKey = resolveCategoryKey(file, config)
            val (baseTargetDir, destStorage) = resolveTargetDir(file, config, rootPath) ?: run {
                skippedCount++
                continue
            }

            var targetDir: String
            val isFirstForCategory = baseTargetDir !in resolvedTargetDirs

            if (isFirstForCategory) {
                if (destStorage.exists(baseTargetDir)) {
                    when (config.existingFolderStrategy) {
                        SmartSortConfig.ExistingFolderStrategy.SKIP -> {
                            skippedCount++
                            continue
                        }
                        SmartSortConfig.ExistingFolderStrategy.RENAME -> {
                            var counter = 1
                            var newDir = "$baseTargetDir ($counter)"
                            while (destStorage.exists(newDir)) {
                                counter++
                                newDir = "$baseTargetDir ($counter)"
                            }
                            resolvedTargetDirs[baseTargetDir] = newDir
                            targetDir = newDir
                        }
                        else -> {
                            resolvedTargetDirs[baseTargetDir] = baseTargetDir
                            targetDir = baseTargetDir
                        }
                    }
                } else {
                    resolvedTargetDirs[baseTargetDir] = baseTargetDir
                    targetDir = baseTargetDir
                }
            } else {
                targetDir = resolvedTargetDirs[baseTargetDir]!!
            }

            val targetPath = "$targetDir/${file.name}"

            if (targetDir !in createdDirs) {
                createdDirs.add(targetDir)
                if (!destStorage.exists(targetDir)) {
                    destStorage.mkdirs(targetDir)
                }
            }

            val effectiveTarget = resolveDuplicate(file, targetPath, config, destStorage)

            if (effectiveTarget == null) {
                skippedCount++
                continue
            }

            val success = moveFile(sourceStorage, file.path, destStorage, effectiveTarget)

            if (success) {
                movedCount++
                manifestEntries.add(
                    SortManifestEntry(
                        originalPath = file.path,
                        newPath = effectiveTarget,
                        fileSize = file.size,
                        timestamp = System.currentTimeMillis(),
                        categoryKey = categoryKey
                    )
                )
            } else {
                failedCount++
                failedFiles.add(file.name)
            }
        }

        val manifest = if (movedCount > 0) {
            SmartSortManifest.save(rootPath, config, manifestEntries)
            SmartSortManifest(
                rootPath, manifestEntries, System.currentTimeMillis(),
                customCategoryPaths = config.customCategoryPaths,
                customCategoryShareIds = config.customCategoryShareIds
            )
        } else null

        SmartSortResult(movedCount, skippedCount, failedCount, failedFiles, manifest)
    }

    suspend fun undo(
        rootPath: String,
        manifest: SmartSortManifest,
        config: SmartSortConfig,
        onProgress: ((String, Int, Int) -> Unit)? = null
    ): SmartSortResult = withContext(Dispatchers.IO) {
        val sourceStorage = storageFor(config)
        var movedCount = 0
        var failedCount = 0
        val failedFiles = mutableListOf<String>()
        val total = manifest.entries.size
        val processed = AtomicInteger(0)

        for (entry in manifest.entries.reversed()) {
            val count = processed.incrementAndGet()
            onProgress?.invoke(File(entry.newPath).name, count, total)
            val destParent = File(entry.originalPath).parentFile?.absolutePath ?: continue
            sourceStorage.mkdirs(destParent)

            val customShareId = entry.categoryKey?.let { manifest.customCategoryShareIds[it] }
            val customPath = entry.categoryKey?.let { manifest.customCategoryPaths[it] }

            val currentStorage: SmartSortStorage = when {
                customShareId != null -> storageForCustomShare(customShareId) ?: sourceStorage
                customPath != null -> LocalSmartSortStorage()
                else -> sourceStorage
            }

            val success = moveFile(currentStorage, entry.newPath, sourceStorage, entry.originalPath)

            if (success) {
                movedCount++
            } else {
                failedCount++
                failedFiles.add(File(entry.newPath).name)
            }
        }

        SmartSortManifest.delete(rootPath)
        SmartSortResult(movedCount, 0, failedCount, failedFiles, null)
    }

    private suspend fun downloadFromNetwork(storage: NetworkSmartSortStorage, path: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val baos = java.io.ByteArrayOutputStream()
            val share = storage.getShare()
            when (storage.sharesType()) {
                ShareType.SMB -> {
                    SmbShareClient.openInputStream(share, path).use { inp -> inp.copyTo(baos) }
                }
                ShareType.FTP -> {
                    FtpShareClient.openInputStream(share, path)?.use { inp -> inp.copyTo(baos) }
                }
                ShareType.SFTP, ShareType.SCP -> {
                    SshShareClient.openInputStream(share, path).use { inp -> inp.copyTo(baos) }
                }
                ShareType.WEBDAV -> {
                    WebDavShareClient.openInputStream(share, path).first.use { inp -> inp.copyTo(baos) }
                }
                ShareType.WEBDAV -> {
                    WebDavShareClient.openInputStream(share, path).first.use { inp -> inp.copyTo(baos) }
                }
                ShareType.NFS -> {
                    NfsShareClient.openInputStream(share, path).use { inp -> inp.copyTo(baos) }
                }
                ShareType.GOOGLE_DRIVE -> {
                    GoogleDriveShareClient.openInputStream(share, path).first.use { inp -> inp.copyTo(baos) }
                }
                ShareType.ONEDRIVE -> {
                    OnedriveShareClient.openInputStream(share, path).first.use { inp -> inp.copyTo(baos) }
                }
                ShareType.DROPBOX -> {
                    DropboxShareClient.openInputStream(share, path).first.use { inp -> inp.copyTo(baos) }
                }
                ShareType.AWS_S3, ShareType.IDRIVE_E2 -> {
                    S3ShareClient.openInputStream(share, path).first.use { inp -> inp.copyTo(baos) }
                }
                ShareType.TV -> {
                    TvShareClient.openInputStream(share, path).use { inp -> inp.copyTo(baos) }
                }
                ShareType.DLNA -> {
                    DlnaShareClient.openInputStream(share, path).use { inp -> inp.copyTo(baos) }
                }
            }
            baos.toByteArray()
        } catch (_: Exception) { null }
    }

    private fun storageFor(config: SmartSortConfig): SmartSortStorage {
        return if (config.isNetwork) {
            NetworkSmartSortStorage(config.shareInfo!!)
        } else {
            LocalSmartSortStorage()
        }
    }

    private fun storageForCustomShare(shareId: String): SmartSortStorage? {
        val share = SmartSortShareHolder.resolve(shareId) ?: return null
        return NetworkSmartSortStorage(share)
    }

    private fun resolveCategoryKey(file: SmartSortFileEntry, config: SmartSortConfig): String? {
        return when (config.mode) {
            SmartSortMode.TYPE -> {
                val ext = file.name.substringAfterLast('.', "")
                SmartSortCategory.categorize(ext)?.name
            }
            SmartSortMode.SIZE -> SizeTier.forSize(file.size)?.name
            SmartSortMode.DATE -> DatePeriod.forMillis(file.lastModified)?.name
            SmartSortMode.CUSTOM -> {
                val ext = file.name.substringAfterLast('.', "").lowercase()
                config.customRules.firstOrNull { ext in it.extensions }?.id
            }
        }
    }

    private fun resolveTargetDir(
        file: SmartSortFileEntry,
        config: SmartSortConfig,
        rootPath: String
    ): Pair<String, SmartSortStorage>? {
        val ext = file.name.substringAfterLast('.', "")
        val categoryKey = resolveCategoryKey(file, config)
        val customPath = categoryKey?.let { config.customCategoryPaths[it] }
        val customShareId = categoryKey?.let { config.customCategoryShareIds[it] }

        val baseName: String? = when {
            customPath != null -> customPath
            config.mode == SmartSortMode.TYPE -> {
                val cat = SmartSortCategory.categorize(ext)
                if (cat != null && cat in config.enabledCategories)
                    config.resolveFolderName(cat.folderName.removePrefix("UFM").trim())
                else if (config.includeOther) config.resolveFolderName("Other")
                else null
            }
            config.mode == SmartSortMode.SIZE -> {
                val tier = SizeTier.forSize(file.size)
                if (tier != null && tier in config.enabledSizeTiers)
                    config.resolveFolderName(tier.folderName.removePrefix("UFM").trim())
                else if (config.includeOther) config.resolveFolderName("Other")
                else null
            }
            config.mode == SmartSortMode.DATE -> {
                val period = DatePeriod.forMillis(file.lastModified)
                if (period in config.enabledDatePeriods)
                    config.resolveFolderName(period.folderName.removePrefix("UFM").trim())
                else if (config.includeOther) config.resolveFolderName("Other")
                else null
            }
            config.mode == SmartSortMode.CUSTOM -> {
                val extLower = file.name.substringAfterLast('.', "").lowercase()
                val rule = config.customRules.firstOrNull { extLower in it.extensions }
                if (rule != null) {
                    val ruleCustomPath = config.customCategoryPaths[rule.id]
                    if (ruleCustomPath != null) ruleCustomPath
                    else config.resolveFolderName(rule.description)
                } else null
            }
            else -> null
        }

        if (baseName == null) return null

        val sub = if (!config.flattenSubfolders && file.relativeSubpath.isNotEmpty())
            "/${file.relativeSubpath}" else ""

        val destStorage = when {
            customShareId != null -> storageForCustomShare(customShareId) ?: storageFor(config)
            customPath != null -> LocalSmartSortStorage()
            else -> storageFor(config)
        }

        val targetPath = if (customPath != null) "$customPath$sub" else "$rootPath/$baseName$sub"
        return Pair(targetPath, destStorage)
    }

    private suspend fun resolveDuplicate(
        file: SmartSortFileEntry,
        targetPath: String,
        config: SmartSortConfig,
        storage: SmartSortStorage = storageFor(config)
    ): String? {
        val s = storage
        if (!s.exists(targetPath)) return targetPath

        return when (config.duplicateStrategy) {
            SmartSortConfig.DuplicateStrategy.SKIP -> null
            SmartSortConfig.DuplicateStrategy.OVERWRITE -> {
                File(targetPath).delete()
                targetPath
            }
            SmartSortConfig.DuplicateStrategy.RENAME -> {
                var counter = 1
                val base = targetPath.substringBeforeLast('.')
                val ext = targetPath.substringAfterLast('.', "")
                while (true) {
                    val candidate = if (ext.isNotEmpty()) "${base}_$counter.$ext" else "${base}_$counter"
                    if (!s.exists(candidate)) return@resolveDuplicate candidate
                    counter++
                }
            }
            SmartSortConfig.DuplicateStrategy.ASK -> targetPath
        } as String?
    }

    private suspend fun moveFile(
        srcStorage: SmartSortStorage,
        srcPath: String,
        dstStorage: SmartSortStorage,
        dstPath: String
    ): Boolean {
        if (srcStorage::class == dstStorage::class) {
            if (srcStorage.rename(srcPath, dstPath)) return true
        }
        val data = when (srcStorage) {
            is LocalSmartSortStorage -> try { File(srcPath).readBytes() } catch (_: Exception) { null }
            is NetworkSmartSortStorage -> downloadFromNetwork(srcStorage, srcPath)
            else -> null
        }
        if (data != null && dstStorage.writeBytes(dstPath, data)) {
            // Zero-byte guard: verify destination has data before deleting source
            val destSize = getSmartSortDestSize(dstStorage, dstPath)
            if (za.kilowatch.ultimatefilemanager.util.FileTransferGuard.requireSourceSafeToDelete(
                    destSize, data.size.toLong(), File(srcPath).name)) {
                srcStorage.delete(srcPath)
                return true
            }
        }
        return false
    }

    /**
     * Returns the size of a file at [path] on the given [storage].
     * Used for zero-byte verification before source deletion in move operations.
     */
    private suspend fun getSmartSortDestSize(storage: SmartSortStorage, path: String): Long {
        return when (storage) {
            is LocalSmartSortStorage -> try { File(path).length() } catch (_: Exception) { -1L }
            is NetworkSmartSortStorage -> {
                val share = storage.getShare()
                try {
                    za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.getRemoteFileSize(share, path)
                } catch (_: Exception) { -1L }
            }
            else -> -1L
        }
    }
}
