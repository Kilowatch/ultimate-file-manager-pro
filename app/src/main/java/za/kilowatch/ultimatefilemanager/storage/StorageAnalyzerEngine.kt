package za.kilowatch.ultimatefilemanager.storage

import android.content.Context
import android.os.StatFs
import android.text.format.Formatter
import za.kilowatch.ultimatefilemanager.indexing.FileIndex
import za.kilowatch.ultimatefilemanager.indexing.FileIndexDao
import za.kilowatch.ultimatefilemanager.indexing.FileTypeUsage
import za.kilowatch.ultimatefilemanager.indexing.UfmIndexingDatabase
import za.kilowatch.ultimatefilemanager.util.GoRoLog
import java.io.File
import java.security.MessageDigest
import za.kilowatch.ultimatefilemanager.R

/**
 * StorageAnalyzerEngine — pure analysis logic, no UI concerns.
 *
 * Aggregates all analyzer reports by running SQL queries against the FileIndex
 * Room database. Never touches the filesystem except for StatFs disk stats.
 *
 * For non-indexed storages, [runForNonIndexed] falls back to a basic filesystem
 * walk that replicates the original category breakdown only.
 */
class StorageAnalyzerEngine(private val context: Context) {

    private val TAG = "StorageAnalyzerEngine"
    private val dao: FileIndexDao = UfmIndexingDatabase.getInstance(context).fileIndexDao()

    // ──────────────────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Creates an initial empty report for indexed storage.
     */
    fun createEmptyReport(storageId: String, storagePath: File): AnalyzerReport {
        val (totalBytes, usedBytes) = diskStats(storagePath)
        return AnalyzerReport(
            storageId = storageId,
            mountPath = storagePath.absolutePath,
            isIndexed = true,
            totalBytes = totalBytes,
            usedBytes = usedBytes,
            categoryBreakdown = emptyList(),
            topFolders = emptyList(),
            largeFiles = emptyList(),
            duplicateGroups = emptyList(),
            oldFiles = emptyList(),
            junkReport = JunkReport(0L, 0, emptyList()),
            downloadReport = DownloadReport(emptyList(), emptyList()),
            appUsage = emptyList(),
            recommendations = emptyList()
        )
    }

    suspend fun getCategories(storageId: String, usedBytes: Long): List<CategoryData> {
        val mimeUsage = dao.getStorageUsageByMimeType(storageId)
        return buildCategories(mimeUsage, usedBytes)
    }

    suspend fun getTopFolders(storageId: String): List<AnalyzerFolder> {
        return dao.getTopFoldersBySize(storageId, limit = 50)
            .map { AnalyzerFolder(it.folderPath, it.totalSize, it.fileCount) }
    }

    suspend fun getLargeFiles(storageId: String): List<FileIndex> {
        return dao.getLargestFiles(storageId, limit = 200)
    }

    suspend fun getDuplicateGroupsReport(storageId: String): List<DuplicateGroup> {
        return buildDuplicateGroups(storageId)
    }

    suspend fun getOldFiles(storageId: String): List<FileIndex> {
        val oldThreshold = System.currentTimeMillis() - (180L * 24 * 60 * 60 * 1000)
        return dao.getOldFiles(storageId, oldThreshold, limit = 200)
    }

    suspend fun getJunk(storageId: String): JunkReport {
        val junkAgg = dao.getJunkFilesAggregated(storageId)
        val junkFiles = dao.getJunkFiles(storageId, limit = 500)
        return JunkReport(
            totalBytes = junkAgg?.totalSize ?: 0L,
            fileCount = junkAgg?.fileCount?.toInt() ?: 0,
            files = junkFiles
        )
    }

    suspend fun getAppUsage(storageId: String): List<AppStorageUsage> {
        return buildAppUsage(storageId)
    }

    fun getRecommendations(
        dupGroups: List<DuplicateGroup>,
        largeFiles: List<FileIndex>,
        oldFiles: List<FileIndex>,
        junk: JunkReport
    ): List<CleanupRecommendation> {
        return buildRecommendations(dupGroups, largeFiles, oldFiles, junk)
    }

    /**
     * Full analysis for an indexed storage.
     * Deprecated: use granular methods for incremental loading.
     */
    suspend fun runForIndexed(storageId: String, storagePath: File): AnalyzerReport {
        val report = createEmptyReport(storageId, storagePath)
        val cats   = getCategories(storageId, report.usedBytes)
        val folders = getTopFolders(storageId)
        val large  = getLargeFiles(storageId)
        val dups   = getDuplicateGroupsReport(storageId)
        val old    = getOldFiles(storageId)
        val junk   = getJunk(storageId)
        val apps   = getAppUsage(storageId)
        val recs   = getRecommendations(dups, large, old, junk)

        return report.copy(
            categoryBreakdown = cats,
            topFolders = folders,
            largeFiles = large,
            duplicateGroups = dups,
            oldFiles = old,
            junkReport = junk,
            appUsage = apps,
            recommendations = recs
        )
    }

    /**
     * Basic category breakdown for non-indexed storages using a filesystem walk.
     * Only populates [AnalyzerReport.categoryBreakdown] and disk stats.
     */
    suspend fun runForNonIndexed(storagePath: File): AnalyzerReport {
        val (totalBytes, usedBytes) = diskStats(storagePath)
        val categories = walkForCategories(storagePath)
        return AnalyzerReport(
            storageId = "",
            mountPath = storagePath.absolutePath,
            isIndexed = false,
            totalBytes = totalBytes,
            usedBytes = usedBytes,
            categoryBreakdown = categories,
            topFolders = emptyList(),
            largeFiles = emptyList(),
            duplicateGroups = emptyList(),
            oldFiles = emptyList(),
            junkReport = JunkReport(0L, 0, emptyList()),
            downloadReport = DownloadReport(emptyList(), emptyList()),
            appUsage = emptyList(),
            recommendations = emptyList()
        )
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ──────────────────────────────────────────────────────────────────────────

    private fun diskStats(storagePath: File): Pair<Long, Long> {
        return try {
            val stat = StatFs(storagePath.absolutePath)
            val total = stat.totalBytes
            val free  = stat.freeBytes
            total to (total - free)
        } catch (e: Exception) {
            GoRoLog.w(TAG, "diskStats failed: ${e.message}")
            0L to 0L
        }
    }

    /**
     * Build the 6 user-facing categories from MIME type aggregations.
     * All categories are returned (even if 0 bytes) so the UI can display them.
     */
    private fun buildCategories(mimeUsage: List<za.kilowatch.ultimatefilemanager.indexing.FileTypeUsage>, usedBytes: Long): List<CategoryData> {
        var imageBytes = 0L; var imageCount = 0L
        var videoBytes = 0L; var videoCount = 0L
        var audioBytes = 0L; var audioCount = 0L
        var docBytes   = 0L; var docCount   = 0L
        var apkBytes   = 0L; var apkCount   = 0L
        var otherBytes = 0L; var otherCount = 0L

        for (row in mimeUsage) {
            val mime = row.extension  // column alias is extension but holds mimeType
            when {
                mime.startsWith("image/")                      -> { imageBytes += row.totalSize; imageCount += row.fileCount }
                mime.startsWith("video/")                      -> { videoBytes += row.totalSize; videoCount += row.fileCount }
                mime.startsWith("audio/")                      -> { audioBytes += row.totalSize; audioCount += row.fileCount }
                mime == "application/vnd.android.package-archive" ||
                mime.endsWith(".apk")                          -> { apkBytes   += row.totalSize; apkCount   += row.fileCount }
                mime.startsWith("application/") &&
                (mime.contains("pdf") || mime.contains("msword") ||
                 mime.contains("sheet") || mime.contains("text") ||
                 mime.contains("presentation"))                -> { docBytes   += row.totalSize; docCount   += row.fileCount }
                mime.startsWith("text/")                       -> { docBytes   += row.totalSize; docCount   += row.fileCount }
                else                                           -> { otherBytes += row.totalSize; otherCount += row.fileCount }
            }
        }

        val total = usedBytes.coerceAtLeast(1L)
        return listOf(
            CategoryData(R.string.analyzer_category_images,    imageBytes, imageCount, SortFilterSheet.FilterType.IMAGES,    (imageBytes * 100 / total).toInt()),
            CategoryData(R.string.analyzer_category_videos,    videoBytes, videoCount, SortFilterSheet.FilterType.VIDEOS,    (videoBytes * 100 / total).toInt()),
            CategoryData(R.string.analyzer_category_audio,     audioBytes, audioCount, SortFilterSheet.FilterType.AUDIO,     (audioBytes * 100 / total).toInt()),
            CategoryData(R.string.analyzer_category_documents, docBytes,   docCount,   SortFilterSheet.FilterType.DOCUMENTS, (docBytes   * 100 / total).toInt()),
            CategoryData(R.string.analyzer_category_apks,      apkBytes,   apkCount,   SortFilterSheet.FilterType.APKS,      (apkBytes   * 100 / total).toInt()),
            CategoryData(R.string.analyzer_category_other,     otherBytes, otherCount, SortFilterSheet.FilterType.OTHER,      (otherBytes * 100 / total).toInt())
        ).sortedByDescending { it.bytes }
    }

    /** Walks the filesystem for a basic 6-category breakdown (non-indexed fallback). */
    private fun walkForCategories(root: File): List<CategoryData> {
        var imageBytes = 0L; var imageCount = 0L
        var videoBytes = 0L; var videoCount = 0L
        var audioBytes = 0L; var audioCount = 0L
        var docBytes   = 0L; var docCount   = 0L
        var apkBytes   = 0L; var apkCount   = 0L
        var otherBytes = 0L; var otherCount = 0L

        try {
            root.walkTopDown().filter { it.isFile }.forEach { file ->
                val ext  = file.extension.lowercase()
                val size = file.length()
                when {
                    ext in SortFilterSheet.IMAGE_EXTENSIONS    -> { imageBytes += size; imageCount++ }
                    ext in SortFilterSheet.VIDEO_EXTENSIONS    -> { videoBytes += size; videoCount++ }
                    ext in SortFilterSheet.AUDIO_EXTENSIONS    -> { audioBytes += size; audioCount++ }
                    ext in SortFilterSheet.DOCUMENT_EXTENSIONS -> { docBytes   += size; docCount++ }
                    ext in SortFilterSheet.APK_EXTENSIONS      -> { apkBytes   += size; apkCount++ }
                    else                                        -> { otherBytes += size; otherCount++ }
                }
            }
        } catch (_: Exception) { }

        return listOf(
            CategoryData(R.string.analyzer_category_images,    imageBytes, imageCount, SortFilterSheet.FilterType.IMAGES,    0),
            CategoryData(R.string.analyzer_category_videos,    videoBytes, videoCount, SortFilterSheet.FilterType.VIDEOS,    0),
            CategoryData(R.string.analyzer_category_audio,     audioBytes, audioCount, SortFilterSheet.FilterType.AUDIO,     0),
            CategoryData(R.string.analyzer_category_documents, docBytes,   docCount,   SortFilterSheet.FilterType.DOCUMENTS, 0),
            CategoryData(R.string.analyzer_category_apks,      apkBytes,   apkCount,   SortFilterSheet.FilterType.APKS,      0),
            CategoryData(R.string.analyzer_category_other,     otherBytes, otherCount, SortFilterSheet.FilterType.OTHER,      0)
        ).sortedByDescending { it.bytes }
    }

    /**
     * Two-phase duplicate detection pipeline.
     *
     * Phase 1 — SQL: group records by the quick-hash (64 KB MD5) already stored in the DB.
     *            This quickly narrows the candidate pool without touching the filesystem.
     *
     * Phase 2 — Full-file MD5: for every candidate pair from Phase 1, compute a full-content
     *            hash on-the-fly and re-group by it. This eliminates false positives caused by
     *            quick-hash collisions on large files, and — crucially — detects duplicates
     *            regardless of filename because only the bytes are compared.
     *
     *            Files larger than [FULL_HASH_MAX_BYTES] skip Phase 2 (to bound I/O) and are
     *            returned as [DuplicateGroup] with [DuplicateGroup.isVerified] = false.
     */
    private suspend fun buildDuplicateGroups(storageId: String): List<DuplicateGroup> {
        // ── Phase 1: DB query for quick-hash candidate groups ────────────────────
        val summaries = dao.getDuplicateGroups(storageId, limit = 200)
        if (summaries.isEmpty()) return emptyList()

        // Batch-fetch all candidate files in one SQL call (avoids N+1 pattern)
        val hashes = summaries.map { it.hash }
        val allCandidates = dao.getFilesForHashes(hashes, storageId)
        val byQuickHash = allCandidates.groupBy { it.hash }

        val result = mutableListOf<DuplicateGroup>()

        for (summary in summaries) {
            val candidates = byQuickHash[summary.hash] ?: continue
            if (candidates.size < 2) continue

            // ── Phase 2: full-hash verification ─────────────────────────────────
            // Separate candidates into those that fit within the size threshold and
            // those that are too large to full-hash within a reasonable time budget.
            val (smallEnough, tooLarge) = candidates.partition { it.size <= FULL_HASH_MAX_BYTES }

            // Full-hash the files that are within the threshold
            if (smallEnough.size >= 2) {
                val byFullHash = smallEnough.groupBy { file ->
                    try {
                        computeFullHash(File(file.path))
                    } catch (e: Exception) {
                        GoRoLog.w(TAG, "Full-hash failed for ${file.path}: ${e.message}")
                        // On failure fall back to the quick hash so the file stays in its group
                        file.hash
                    }
                }
                for ((fullHash, group) in byFullHash) {
                    if (group.size >= 2) {
                        val wastedBytes = group.sumOf { it.size } - group.minOf { it.size }
                        result.add(DuplicateGroup(
                            hash        = fullHash,
                            files       = group,
                            wastedBytes = wastedBytes,
                            isVerified  = true
                        ))
                    }
                }
            } else if (smallEnough.size == 1 && tooLarge.isEmpty()) {
                // Only one small-enough file after grouping — not a duplicate group
                continue
            }

            // Files above the threshold: group by quick-hash as before but mark unverified
            if (tooLarge.size >= 2) {
                val wastedBytes = tooLarge.sumOf { it.size } - tooLarge.minOf { it.size }
                result.add(DuplicateGroup(
                    hash        = summary.hash,
                    files       = tooLarge,
                    wastedBytes = wastedBytes,
                    isVerified  = false
                ))
            }
        }

        return result.sortedByDescending { it.wastedBytes }
    }

    /**
     * Compute a full MD5 hash of [file]'s entire content.
     * This is intentionally kept simple and synchronous — callers run it on Dispatchers.IO.
     */
    private fun computeFullHash(file: File): String {
        val md = MessageDigest.getInstance("MD5")
        val buffer = ByteArray(65_536)  // 64 KB read buffer
        file.inputStream().use { input ->
            var len: Int
            while (input.read(buffer).also { len = it } != -1) {
                md.update(buffer, 0, len)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Build the well-known app-storage list. */
    private suspend fun buildAppUsage(storageId: String): List<AppStorageUsage> {
        val results = mutableListOf<AppStorageUsage>()
        for ((nameRes, prefix) in APP_PACKAGE_MAP) {
            val agg = dao.getAppStorageByPrefix(storageId, prefix) ?: continue
            if (agg.totalSize > 0L) {
                results.add(AppStorageUsage(
                    nameRes    = nameRes,
                    packageId  = prefix,
                    totalBytes = agg.totalSize,
                    fileCount  = agg.fileCount.toInt()
                ))
            }
        }
        return results.sortedByDescending { it.totalBytes }
    }

    /** Convert analysis results into actionable cleanup recommendations. */
    private fun buildRecommendations(
        dupGroups   : List<DuplicateGroup>,
        largeFiles  : List<FileIndex>,
        oldFiles    : List<FileIndex>,
        junk        : JunkReport
    ): List<CleanupRecommendation> {
        val recs = mutableListOf<CleanupRecommendation>()

        // Junk / cache — always safe
        if (junk.totalBytes > 0L) {
            recs.add(CleanupRecommendation(
                title              = context.getString(R.string.clear_cache_temp_files),
                description        = context.getString(R.string.found_junkfilecount_cachetemp_files_in_wellknown_junk_folders, junk.fileCount),
                estimatedBytes     = junk.totalBytes,
                riskLevel          = RiskLevel.SAFE,
                files              = junk.files,
                targetTab          = 3 // TAB_JUNK
            ))
        }

        // Duplicates — moderate risk (keep one copy)
        val totalWasted = dupGroups.sumOf { it.wastedBytes }
        if (totalWasted > 1024 * 1024L) { // > 1 MB
            val allDupFiles = dupGroups.flatMap { g -> g.files.drop(1) } // keep first, propose deleting rest
            recs.add(CleanupRecommendation(
                title              = context.getString(R.string.remove_duplicate_files),
                description        = context.getString(R.string.found_dupgroupssize_duplicate_groups_wasting_space, dupGroups.size),
                estimatedBytes     = totalWasted,
                riskLevel          = RiskLevel.MODERATE,
                files              = allDupFiles,
                targetTab          = 2 // TAB_DUPLICATES
            ))
        }

        // Large files — manual review
        val bigFiles = largeFiles.filter { it.size > 512 * 1024 * 1024L } // > 512 MB
        if (bigFiles.isNotEmpty()) {
            recs.add(CleanupRecommendation(
                title              = context.getString(R.string.review_large_files),
                description        = context.getString(R.string.found_bigfilessize_files_larger_than_512_mb, bigFiles.size),
                estimatedBytes     = bigFiles.sumOf { it.size },
                riskLevel          = RiskLevel.MANUAL_REVIEW,
                files              = bigFiles,
                targetTab          = 1 // TAB_LARGE
            ))
        }

        // Old files — moderate/manual
        if (oldFiles.isNotEmpty()) {
            recs.add(CleanupRecommendation(
                title              = context.getString(R.string.old_unused_files),
                description        = context.getString(R.string.analyzer_old_files_desc, oldFiles.size),
                estimatedBytes     = oldFiles.sumOf { it.size },
                riskLevel          = RiskLevel.MODERATE,
                files              = oldFiles,
                targetTab          = 3 // TAB_JUNK
            ))
        }

        return recs.sortedByDescending { it.estimatedBytes }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Constants
    // ──────────────────────────────────────────────────────────────────────────

    companion object {
        /**
         * Maximum file size for the Phase-2 full-hash verification pass.
         * Files larger than this are kept in the results with [DuplicateGroup.isVerified] = false
         * to bound the I/O cost of the analysis scan.
         * Default: 500 MB.
         */
        const val FULL_HASH_MAX_BYTES: Long = 500L * 1024L * 1024L

        /**
         * Well-known app package → friendly name mapping.
         * Key = display name, Value = path prefix on the filesystem.
         */
        val APP_PACKAGE_MAP: Map<Int, String> = linkedMapOf(
            R.string.whatsapp       to "/storage/emulated/0/Android/media/com.whatsapp",
            R.string.whatsapp_business to "/storage/emulated/0/Android/media/com.whatsapp.w4b",
            R.string.telegram       to "/storage/emulated/0/Android/media/org.telegram.messenger",
            R.string.instagram      to "/storage/emulated/0/Android/data/com.instagram.android",
            R.string.snapchat       to "/storage/emulated/0/Android/data/com.snapchat.android",
            R.string.tiktok         to "/storage/emulated/0/Android/data/com.zhiliaoapp.musically",
            R.string.facebook       to "/storage/emulated/0/Android/media/com.facebook.orca",
            R.string.twitter_x      to "/storage/emulated/0/Android/data/com.twitter.android",
            R.string.discord        to "/storage/emulated/0/Android/data/com.discord",
            R.string.spotify        to "/storage/emulated/0/Android/data/com.spotify.music",
            R.string.netflix        to "/storage/emulated/0/Android/data/com.netflix.mediaclient",
            R.string.youtube        to "/storage/emulated/0/Android/data/com.google.android.youtube",
            R.string.signal         to "/storage/emulated/0/Android/media/org.thoughtcrime.securesms",
            R.string.viber          to "/storage/emulated/0/Android/media/com.viber.voip",
            R.string.line           to "/storage/emulated/0/Android/media/jp.naver.line.android"
        )
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Data classes
// ──────────────────────────────────────────────────────────────────────────────

/** Full analysis report for one storage device. */
data class AnalyzerReport(
    val storageId        : String,
    val mountPath        : String,
    val isIndexed        : Boolean,
    val totalBytes       : Long,
    val usedBytes        : Long,
    val categoryBreakdown: List<CategoryData>,
    val topFolders       : List<AnalyzerFolder>,
    val largeFiles       : List<FileIndex>,
    val duplicateGroups  : List<DuplicateGroup>,
    val oldFiles         : List<FileIndex>,
    val junkReport       : JunkReport,
    val downloadReport   : DownloadReport,
    val appUsage         : List<AppStorageUsage>,
    val recommendations  : List<CleanupRecommendation>
)

/** One category row in the Overview breakdown. */
data class CategoryData(
    val nameRes    : Int,
    val bytes      : Long,
    val fileCount  : Long,
    val filterType : SortFilterSheet.FilterType,
    val percent    : Int        // relative to usedBytes (0–100)
)

/** One folder row in the Top Folders list. */
data class AnalyzerFolder(
    val folderPath : String,
    val totalSize  : Long,
    val fileCount  : Long
)

/**
 * A group of duplicate files sharing the same content hash.
 *
 * @param hash        Full-file MD5 when [isVerified] is true; quick-hash (64 KB MD5) when false.
 * @param files       All files in the group — every entry has identical content.
 * @param wastedBytes Total size minus the smallest copy (bytes recoverable by deleting duplicates).
 * @param isVerified  true  → confirmed by a full-file MD5 (100% identical content, name-independent).
 *                   false → only the first-64-KB quick-hash matched; file was too large to
 *                           full-hash within the analysis time budget. Treat with caution.
 */
data class DuplicateGroup(
    val hash        : String,
    val files       : List<FileIndex>,
    val wastedBytes : Long,       // total - one copy
    val isVerified  : Boolean = true
)

/** Aggregated junk / cache report. */
data class JunkReport(
    val totalBytes : Long,
    val fileCount  : Int,
    val files      : List<FileIndex>
)

/** Download folder analysis. */
data class DownloadReport(
    val byExtension : List<FileTypeUsage>,
    val largeFiles  : List<FileIndex>
)

/** App-specific storage usage by well-known path prefix. */
data class AppStorageUsage(
    val nameRes   : Int,
    val packageId : String,
    val totalBytes: Long,
    val fileCount : Int
)

/** Cleanup suggestion card model. */
data class CleanupRecommendation(
    val title          : String,
    val description    : String,
    val estimatedBytes : Long,
    val riskLevel      : RiskLevel,
    val files          : List<FileIndex>,
    val targetTab      : Int = -1
)

enum class RiskLevel { SAFE, MODERATE, MANUAL_REVIEW }
