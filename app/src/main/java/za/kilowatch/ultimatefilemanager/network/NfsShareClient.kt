package za.kilowatch.ultimatefilemanager.network

import android.util.Log
import java.io.InputStream
import java.io.OutputStream

/**
 * NFS client entry point.
 *
 * Now delegates entirely to [LibNfsClient] (libnfs native backend).
 * The EMC nfs-client-java fallback has been removed — it was the suspected
 * source of RPCSEC_GSS credentials causing auth failures.
 */
object NfsShareClient {

    private const val TAG = "NfsShareClient"

    /** Sentinel error keys that can be mapped to localised strings by the UI layer. */
    object ErrorSentinel {
        const val STALE_HANDLE = "NFS_STALE_HANDLE"
        const val PORTMAPPER_UNREACHABLE = "NFS_PORTMAPPER_UNREACHABLE"
        const val PERMISSION_DENIED = "NFS_PERMISSION_DENIED"
        const val AUTH_REJECTED = "NFS_AUTH_REJECTED"
        const val CONNECTION_FAILED = "NFS_CONNECTION_FAILED"
        const val PATH_NOT_FOUND = "NFS_PATH_NOT_FOUND"
        const val SERVICE_UNAVAILABLE = "NFS_SERVICE_UNAVAILABLE"
        const val VERSION_MISMATCH = "NFS_VERSION_MISMATCH"
    }

    /* ── Delegated to LibNfsClient ──────────────────────────────────────────── */

    fun testConnection(share: NetworkShare): String? {
        return LibNfsClient.testConnection(share)
    }

    fun listFiles(share: NetworkShare, remotePath: String): List<NetworkFile> {
        return LibNfsClient.listFiles(share, remotePath)
    }

    fun getFileSize(share: NetworkShare, remotePath: String): Long? {
        return LibNfsClient.getFileSize(share, remotePath)
    }

    fun openInputStream(share: NetworkShare, remotePath: String): InputStream {
        return LibNfsClient.openInputStream(share, remotePath)
    }

    fun openOutputStream(share: NetworkShare, remotePath: String): OutputStream {
        return LibNfsClient.openOutputStream(share, remotePath)
    }

    fun openRandomAccessFile(
        share: NetworkShare,
        remotePath: String,
        isWrite: Boolean = false
    ): IRandomAccessFile {
        return LibNfsClient.openRandomAccessFile(share, remotePath, isWrite)
    }

    fun mkdir(share: NetworkShare, remotePath: String) {
        LibNfsClient.mkdir(share, remotePath)
    }

    fun deleteFile(share: NetworkShare, remotePath: String) {
        LibNfsClient.deleteFile(share, remotePath)
    }

    fun deleteDir(share: NetworkShare, remotePath: String) {
        LibNfsClient.deleteDir(share, remotePath)
    }

    fun rename(share: NetworkShare, fromPath: String, toPath: String) {
        LibNfsClient.rename(share, fromPath, toPath)
    }

    /** Utility: normalise an NFS path. Delegated to [LibNfsClient]. */
    fun normalizePath(path: String): String = LibNfsClient.normalizePath(path)
}
