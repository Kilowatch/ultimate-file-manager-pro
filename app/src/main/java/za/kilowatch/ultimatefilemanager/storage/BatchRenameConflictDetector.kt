package za.kilowatch.ultimatefilemanager.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.network.DropboxShareClient
import za.kilowatch.ultimatefilemanager.network.FtpShareClient
import za.kilowatch.ultimatefilemanager.network.GoogleDriveShareClient
import za.kilowatch.ultimatefilemanager.network.NetworkFile
import za.kilowatch.ultimatefilemanager.network.NetworkShare
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
 * Detects naming conflicts for a batch rename preview.
 *
 * Two independent passes:
 *  - [nameConflicts] is a pure, synchronous check over the resolved names
 *    (duplicate / invalid characters).
 *  - [detectCollisions] lists each shared parent directory once to flag result
 *    names that collide with an existing sibling that is not part of the
 *    selection. It is best-effort: any provider error degrades to "no collision"
 *    rather than failing the preview.
 */
object BatchRenameConflictDetector {

    private val INVALID_CHARS = charArrayOf('\\', '/', ':', '*', '?', '"', '<', '>', '|')

    private val RESERVED_NAMES: Set<String> =
        setOf("CON", "PRN", "AUX", "NUL") + (1..9).flatMap { listOf("COM$it", "LPT$it") }

    /**
     * Detect name-based conflicts synchronously.
     * Path-aware: checks duplicates within the same parent folder instead of globally across disparate folders.
     *
     * @return Map of item index → conflict, containing only conflicting rows.
     */
    fun nameConflicts(
        items: List<BatchRenameItem>,
        resolvedNames: List<String>
    ): Map<Int, PreviewConflict> {
        val result = mutableMapOf<Int, PreviewConflict>()

        fun getParentKey(index: Int): String {
            val item = items.getOrNull(index) ?: return ""
            return if (item.isLocal) {
                item.localFile?.parentFile?.absolutePath ?: ""
            } else {
                "${item.networkShare?.id ?: ""}:${parentRemotePath(item.networkFile?.path ?: "")}"
            }
        }

        // Group non-empty result names (case-insensitive) by parent directory to find duplicates.
        val byParentAndName = resolvedNames.withIndex()
            .filter { it.value.isNotEmpty() }
            .groupBy { (index, name) ->
                getParentKey(index) to name.lowercase()
            }

        resolvedNames.forEachIndexed { index, name ->
            val parentKey = getParentKey(index)
            when {
                isInvalidName(name) -> result[index] = PreviewConflict.INVALID_CHARS
                (byParentAndName[parentKey to name.lowercase()]?.size ?: 0) > 1 -> result[index] = PreviewConflict.DUPLICATE
            }
        }

        return result
    }

    /**
     * Backward-compatible overload for flat lists with no parent folder context.
     */
    fun nameConflicts(
        resolvedNames: List<String>
    ): Map<Int, PreviewConflict> = nameConflicts(emptyList(), resolvedNames)

    /**
     * Detect collisions with existing sibling files (best-effort, async).
     *
     * A result name collides when it matches an existing sibling in the same
     * directory that is not one of the selected items' own original names.
     * Selected items are assumed to be renamed away, freeing their originals.
     *
     * @return Map of item index → [PreviewConflict.COLLISION], containing only collisions.
     */
    suspend fun detectCollisions(
        items: List<BatchRenameItem>,
        resolvedNames: List<String>
    ): Map<Int, PreviewConflict> = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext emptyMap()

        val collisions = mutableMapOf<Int, PreviewConflict>()

        // ── Local items (group by parent directory) ────────────────────────
        val localGroups = items.withIndex()
            .filter { it.value.isLocal }
            .groupBy { it.value.localFile?.parentFile?.absolutePath ?: "" }

        for ((_, group) in localGroups) {
            val parent = group.firstOrNull()?.value?.localFile?.parentFile
            val existing = parent?.list()?.map { it.lowercase() }?.toSet() ?: emptySet()
            val selectedOriginals = group.map { it.value.fullName.lowercase() }.toSet()

            for ((index, item) in group) {
                val result = resolvedNames[index].lowercase()
                if (result.isNotEmpty() && result in existing && result !in selectedOriginals) {
                    collisions[index] = PreviewConflict.COLLISION
                }
            }
        }

        // ── Network / cloud items (group by share + parent path) ──────────
        val networkGroups = items.withIndex()
            .filter { !it.value.isLocal }
            .groupBy { it.value.networkShare?.id to parentRemotePath(it.value.networkFile?.path ?: "") }

        for ((key, group) in networkGroups) {
            val (_, parentPath) = key
            val share = group.firstOrNull()?.value?.networkShare ?: continue

            val existing = try {
                listSiblingNames(share, parentPath)
            } catch (_: Exception) {
                // Graceful degradation: provider unavailable or FOSS stub → no collision flag.
                emptySet()
            }
            if (existing.isEmpty()) continue

            val selectedOriginals = group.map { it.value.fullName.lowercase() }.toSet()

            for ((index, item) in group) {
                val result = resolvedNames[index].lowercase()
                if (result.isNotEmpty() && result in existing && result !in selectedOriginals) {
                    collisions[index] = PreviewConflict.COLLISION
                }
            }
        }

        collisions
    }

    /**
     * List the sibling names (lowercased) of a network directory.
     */
    private suspend fun listSiblingNames(share: NetworkShare, parentPath: String): Set<String> {
        val files: List<NetworkFile> = when (share.type) {
            ShareType.SMB -> SmbShareClient.listFiles(share, parentPath)
            ShareType.FTP -> FtpShareClient.listFiles(share, parentPath)
            ShareType.TV -> TvShareClient.listFiles(share, parentPath)
            ShareType.SFTP, ShareType.SCP -> SshShareClient.listFiles(share, parentPath)
            ShareType.ONEDRIVE -> OnedriveShareClient.listFiles(share, parentPath)
            ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.listFiles(share, parentPath)
            ShareType.DROPBOX -> DropboxShareClient.listFiles(share, parentPath)
            ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.listFiles(share, parentPath)
            ShareType.WEBDAV -> WebDavShareClient.listFiles(share, parentPath)
            ShareType.NFS -> NfsShareClient.listFiles(share, parentPath)
            ShareType.DLNA -> emptyList() // read-only; no rename support
        }
        return files.map { it.name.lowercase() }.toSet()
    }

    /**
     * Derive the parent remote directory from a network file path.
     * "/a/b/file.txt" → "a/b", "/file.txt" → "".
     */
    private fun parentRemotePath(path: String): String =
        path.substringBeforeLast('/').trimStart('/')

    /**
     * Conservative cross-platform invalid-name check.
     */
    private fun isInvalidName(name: String): Boolean {
        if (name.any { it in INVALID_CHARS }) return true
        if (name.endsWith('.') || name.endsWith(' ')) return true
        val base = name.substringBefore('.').uppercase()
        return base in RESERVED_NAMES
    }
}
