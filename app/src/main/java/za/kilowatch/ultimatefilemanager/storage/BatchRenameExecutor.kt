package za.kilowatch.ultimatefilemanager.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.network.DlnaShareClient
import za.kilowatch.ultimatefilemanager.network.DropboxShareClient
import za.kilowatch.ultimatefilemanager.network.FtpShareClient
import za.kilowatch.ultimatefilemanager.network.GoogleDriveShareClient
import za.kilowatch.ultimatefilemanager.network.NetworkFile
import za.kilowatch.ultimatefilemanager.network.NfsShareClient
import za.kilowatch.ultimatefilemanager.network.OnedriveShareClient
import za.kilowatch.ultimatefilemanager.network.S3ShareClient
import za.kilowatch.ultimatefilemanager.network.ShareType
import za.kilowatch.ultimatefilemanager.network.SmbShareClient
import za.kilowatch.ultimatefilemanager.network.SshShareClient
import za.kilowatch.ultimatefilemanager.network.TvShareClient
import za.kilowatch.ultimatefilemanager.network.WebDavShareClient
import java.io.File

/**
 * Executes batch renames through the existing storage provider layer.
 *
 * Groups items by provider type, calls the same single-item rename methods
 * already used by the file browsers. Per-item failures are caught individually
 * so one failure does not abort the entire batch.
 */
object BatchRenameExecutor {

    data class RenameResult(
        val successCount: Int,
        val failureCount: Int,
        val failures: List<Pair<BatchRenameItem, String>>
    )

    /**
     * Execute renames for all items.
     *
     * @param items Ordered list of items to rename (must match order used for preview)
     * @param resolvedNames Ordered list of resulting full names (name + extension)
     * @param connectivityChecker Lambda that returns true if network is available
     * @return [RenameResult] with per-item outcome counts
     */
    suspend fun execute(
        items: List<BatchRenameItem>,
        resolvedNames: List<String>,
        connectivityChecker: () -> Boolean = { true }
    ): RenameResult = withContext(Dispatchers.IO) {
        var successCount = 0
        val failures = mutableListOf<Pair<BatchRenameItem, String>>()

        // Separate local and network items
        val localItems = mutableListOf<Pair<Int, BatchRenameItem>>()   // (index, item)
        val networkItems = mutableListOf<Pair<Int, BatchRenameItem>>()

        items.forEachIndexed { index, item ->
            if (item.isLocal) {
                localItems.add(index to item)
            } else {
                networkItems.add(index to item)
            }
        }

        // ── Local items ──────────────────────────────────────────────────
        for ((index, item) in localItems) {
            val newFullName = resolvedNames[index]
            try {
                val localFile = item.localFile ?: continue
                if (newFullName.isEmpty() || newFullName == item.fullName) {
                    // No change — skip
                    successCount++
                    continue
                }
                val newFile = File(localFile.parent, newFullName)
                val ok = localFile.renameTo(newFile)
                if (ok) {
                    successCount++
                } else {
                    failures.add(item to "renameTo returned false")
                }
            } catch (e: Exception) {
                failures.add(item to (e.message ?: "Unknown error"))
            }
        }

        // ── Network items (group by ShareType) ────────────────────────────
        val byType: Map<ShareType, List<Pair<Int, BatchRenameItem>>> =
            networkItems.groupBy { (_, item) ->
                item.networkShare?.type ?: ShareType.SMB
            }

        for ((type, group) in byType) {
            // All items in a group share the same share object (or should)
            var share = group.firstOrNull()?.second?.networkShare ?: continue

            // Server-mode SMB: extract share name from first segment of item paths.
            // Assumes all items in the group are from the same share, so the first
            // item's path is representative (e.g. "ShareName/sub/file.txt" → "ShareName").
            if (share.isServerMode && share.remotePath.isEmpty()) {
                val firstItem = group.firstOrNull()?.second
                val samplePath = firstItem?.networkFile?.path?.trimStart('/')
                if (!samplePath.isNullOrEmpty()) {
                    val segments = samplePath.split("/", limit = 2)
                    share = share.copy(remotePath = "/${segments[0]}")
                }
            }

            if (!connectivityChecker()) {
                group.forEach { (_, item) ->
                    failures.add(item to "Provider unavailable")
                }
                continue
            }

            for ((index, item) in group) {
                val newFullName = resolvedNames[index]
                val nf = item.networkFile ?: continue
                if (newFullName.isEmpty() || newFullName == item.fullName) {
                    successCount++
                    continue
                }

                try {
                    val targetPath = buildTargetPath(nf.path, newFullName)

                    when (type) {
                        ShareType.SMB -> SmbShareClient.rename(share, nf.path, targetPath)
                        ShareType.FTP -> FtpShareClient.rename(share, nf.path, targetPath)
                        ShareType.TV -> TvShareClient.rename(share, nf.path, targetPath)
                        ShareType.SFTP, ShareType.SCP -> SshShareClient.rename(share, nf.path, targetPath)
                        ShareType.ONEDRIVE -> OnedriveShareClient.rename(share, nf.path, targetPath)
                        ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.rename(share, nf.path, targetPath)
                        ShareType.DROPBOX -> DropboxShareClient.rename(share, nf.path, targetPath)
                        ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.rename(share, nf.path, targetPath)
                        ShareType.WEBDAV -> WebDavShareClient.rename(share, nf.path, targetPath)
                        ShareType.WEBDAV -> WebDavShareClient.rename(share, nf.path, targetPath)
                        ShareType.NFS -> NfsShareClient.rename(share, nf.path, targetPath)
                        ShareType.DLNA -> throw UnsupportedOperationException("DLNA is read-only")
                    }
                    successCount++
                } catch (e: Exception) {
                    failures.add(item to (e.message ?: "Unknown error"))
                }
            }
        }

        RenameResult(
            successCount = successCount,
            failureCount = failures.size,
            failures = failures
        )
    }

    /**
     * Build the target path for a network file rename.
     * The target path is: parent directory of the original + new name.
     *
     * Example:
     * - fromPath = "/share/folder/photo.jpg"
     * - newName  = "Test 001.jpg"
     * - result   = "/share/folder/Test 001.jpg"
     */
    private fun buildTargetPath(fromPath: String, newName: String): String {
        val lastSlash = fromPath.lastIndexOf('/')
        return if (lastSlash >= 0) {
            fromPath.substring(0, lastSlash + 1) + newName
        } else {
            newName
        }
    }
}
