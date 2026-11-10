package za.kilowatch.ultimatefilemanager.sync

import za.kilowatch.ultimatefilemanager.network.NetworkShare
import za.kilowatch.ultimatefilemanager.network.ShareType

/**
 * Resolves the effective [NetworkShare] configuration and the target relative remote path
 * for background file synchronization (both [za.kilowatch.ultimatefilemanager.sync.SyncWorker]
 * and [za.kilowatch.ultimatefilemanager.sync.advanced.AdvancedSyncWorker]).
 *
 * Specifically handles the architectural differences between SMB server-mode shares and standard
 * shares/non-SMB network protocols (WebDAV, SFTP, FTP, NFS, S3, RClone).
 */
object SyncRemotePathResolver {

    data class ResolvedSharePath(
        val effectiveShare: NetworkShare,
        val syncRemotePath: String
    )

    /**
     * Resolves the target remote path and effective share configuration for sync operations.
     *
     * @param share The configured [NetworkShare] from database.
     * @param profileRemotePath The subfolder path chosen in the sync profile (e.g. "Photos/Vacation", "/Test", or "").
     * @return [ResolvedSharePath] containing the [NetworkShare] to use and the relative remote path for operations.
     */
    fun resolve(share: NetworkShare, profileRemotePath: String): ResolvedSharePath {
        // SMB Server-mode: the share represents the host without a fixed share name (or empty remotePath).
        // The first segment of profileRemotePath contains the dynamic share name, which must be encoded
        // into effectiveShare.remotePath so SmbShareClient can parse the share.
        val isSmbServerMode = share.type == ShareType.SMB && (share.isServerMode || share.remotePath.isEmpty())
        val effectiveShare = if (isSmbServerMode) {
            share.copy(remotePath = profileRemotePath, isServerMode = false)
        } else {
            share
        }

        val syncRemotePath = when {
            // For SMB server-mode, the entire path is already encoded into effectiveShare.remotePath,
            // so SmbShareClient splits it from there and syncRemotePath must be "" to avoid double-counting.
            isSmbServerMode -> ""
            share.type == ShareType.SMB -> {
                val shareName = share.remotePath.trimStart('/').split("/").first()
                val raw = profileRemotePath.trim('/')
                when {
                    raw.equals(shareName, ignoreCase = true) -> ""
                    raw.startsWith("$shareName/", ignoreCase = true) -> raw.substring(shareName.length + 1).trim('/')
                    else -> raw
                }
            }
            // For all other protocols (WebDAV, Nextcloud, SFTP, FTP, NFS, S3, RClone):
            // The user-selected profileRemotePath is the exact path relative to the share root.
            else -> profileRemotePath.trim('/')
        }

        return ResolvedSharePath(effectiveShare, syncRemotePath)
    }

    /**
     * Constructs a clean remote file path given a directory [basePath] and a [fileName],
     * avoiding redundant or illegal leading slashes when [basePath] is empty.
     */
    fun buildRemoteFilePath(basePath: String, fileName: String): String {
        val cleanBase = basePath.trim('/')
        return if (cleanBase.isEmpty()) fileName else "$cleanBase/$fileName"
    }
}
