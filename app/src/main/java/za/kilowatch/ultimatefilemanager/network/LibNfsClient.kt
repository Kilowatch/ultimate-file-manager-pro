package za.kilowatch.ultimatefilemanager.network

import android.util.Log
import androidx.annotation.WorkerThread
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Represents an NFS server discovered on the local network along with
 * its available exports.
 */
data class NfsDiscoveredServer(
    val ip: String,
    val exports: List<String>,
    val exportsError: String? = null  // null = exports listed OK
)

/**
 * High-level NFS client backed by libnfs (native, via JNI).
 *
 * Supports NFSv2, NFSv3, NFSv4 and custom port configuration
 * (bypassing Portmapper). This is the same NFS engine used by VLC and Kodi.
 *
 * Usage:
 * - Preferred over the legacy [NfsShareClient] (EMC nfs-client-java).
 * - Automatically used when libnfs is available (native library loads).
 */
object LibNfsClient {

    private const val TAG = "LibNfsClient"


    /** Whether the native library loaded successfully. */
    val isAvailable: Boolean by lazy {
        try {
            LibNfsBridge // triggers init block / System.loadLibrary
            val h = LibNfsBridge.nfsInit()
            if (h != 0L) {
                LibNfsBridge.nfsDestroy(h)
                true
            } else false
        } catch (e: Throwable) {
            Log.w(TAG, "libnfs not available: ${e.message}")
            false
        }
    }

    /** Sentinel error keys matching [NfsShareClient.ErrorSentinel]. */
    object ErrorSentinel {
        const val STALE_HANDLE = NfsShareClient.ErrorSentinel.STALE_HANDLE
        const val PORTMAPPER_UNREACHABLE = NfsShareClient.ErrorSentinel.PORTMAPPER_UNREACHABLE
        const val PERMISSION_DENIED = NfsShareClient.ErrorSentinel.PERMISSION_DENIED
        const val AUTH_REJECTED = NfsShareClient.ErrorSentinel.AUTH_REJECTED
        const val CONNECTION_FAILED = NfsShareClient.ErrorSentinel.CONNECTION_FAILED
        const val PATH_NOT_FOUND = NfsShareClient.ErrorSentinel.PATH_NOT_FOUND
        const val SERVICE_UNAVAILABLE = NfsShareClient.ErrorSentinel.SERVICE_UNAVAILABLE
        const val VERSION_MISMATCH = NfsShareClient.ErrorSentinel.VERSION_MISMATCH
    }

    /* ── Helpers ─────────────────────────────────────────────────────────────── */

    /**
     * Build an NFS URL from the share config.
     * e.g. `nfs://192.168.1.10/export?nfsport=2049&version=3`
     *
     * @param nfsV4MinorVersion NFSv4 minor version (0=v4.0, 1=v4.1, 2=v4.2).
     *   Only added to the URL when [NetworkShare.nfsVersion] is 4 or when
     *   explicitly overridden during the auto-detect cascade.
     * @param forceMountPort Custom mountport override (0 = default portmapper lookup).
     */
    private fun buildNfsUrl(share: NetworkShare, nfsV4MinorVersion: Int = 0, forceMountPort: Int = 0): String {
        val host = share.host
        val export = normalizePath(share.remotePath)
        val params = mutableListOf<String>()

        val port = share.effectivePort
        if (port != 2049) {
            params.add("nfsport=$port")
        }

        // NFS version from share config (0 = auto, 3 = v3, 4 = v4)
        if (share.nfsVersion > 0) {
            params.add("version=${share.nfsVersion}")
        }

        // NFSv4 minor version — tells libnfs which COMPOUND minorversion to use.
        // Only meaningful when version=4; ignored by NFSv3.
        if (share.nfsVersion == 4 && nfsV4MinorVersion > 0) {
            params.add("minor=$nfsV4MinorVersion")
        }

        // Encode the timeout in the URL so nfs_parse_url_full applies it to ALL
        // sub-RPCs (portmapper, mountd, nfsd) — rpc_set_timeout() only covers the
        // main context. timeo= is in deciseconds (1/10s units).
        // Minimum accepted by libnfs parser is 100 (= 10 seconds).
        params.add("timeo=100")

        // Only add explicit mountport parameter if explicitly requested (e.g. NAT fallback).
        // Standard NFSv3 relies on Portmapper (port 111) to discover mountd dynamic port (e.g. 1058 on HaneWin NFS).
        if (forceMountPort > 0) {
            params.add("mountport=$forceMountPort")
        }

        val query = if (params.isEmpty()) "" else "?" + params.joinToString("&")
        return "nfs://$host$export$query"
    }

    /** @internal Shared with [NfsShareClient] callers. */
    fun normalizePath(path: String): String {
        var p = path.trim().replace('\\', '/')
        if (!p.startsWith("/")) p = "/$p"
        if (p.length > 1 && p.endsWith("/")) p = p.removeSuffix("/")
        return p
    }

    /** @internal Shared with [NfsShareClient] callers. */
    fun buildChildPath(parentPath: String, childName: String): String {
        val p = normalizePath(parentPath)
        return if (p == "/") "/$childName" else "$p/$childName"
    }

    /**
     * Mount using an explicit NFS version (must be 3 or 4).
     *
     * @param version Major NFS version (3 or 4).
     * @param minorVersion NFSv4 minor version (0=v4.0, 1=v4.1, 2=v4.2). Ignored for NFSv3.
     * @param mountPort Custom mountport override (0 = portmapper lookup).
     * @param useExplicitAuthFlavor Whether to force explicit nfsSetAuthFlavor (true) vs native 1.6.1 uid/gid binding (false).
     * @return (contextHandle, null) on success, (0, errorMessage) on failure.
     */
    private fun mountContextForVersion(
        share: NetworkShare,
        version: Int,
        minorVersion: Int = 0,
        mountPort: Int = 0,
        useExplicitAuthFlavor: Boolean = false
    ): Pair<Long, String?> {
        val vShare = if (share.nfsVersion == version) share else share.copy(nfsVersion = version)
        val versionLabel = if (version == 4) "NFSv4.$minorVersion" else "NFSv$version"

        Log.d(TAG, "mountContext: starting $versionLabel for ${vShare.host}:${vShare.effectivePort} path=${vShare.remotePath}")

        val t0 = System.currentTimeMillis()
        val handle = LibNfsBridge.nfsInit()
        if (handle == 0L) {
            Log.e(TAG, "mountContext: nfsInit returned 0 (FAILED)")
            return 0L to "Failed to create NFS context"
        }
        Log.d(TAG, "mountContext: nfsInit OK handle=$handle (${System.currentTimeMillis() - t0}ms)")

        val uid = vShare.username.toIntOrNull() ?: 0

        if (useExplicitAuthFlavor) {
            val t1 = System.currentTimeMillis()
            LibNfsBridge.nfsSetAuthFlavor(handle, 1, uid, 0)
            Log.d(TAG, "mountContext: nfsSetAuthFlavor(AUTH_SYS, uid=$uid) (${System.currentTimeMillis() - t1}ms)")
        }

        LibNfsBridge.nfsSetTimeout(handle, 5_000)
        LibNfsBridge.nfsSetVersion(handle, version)
        if (version == 4) {
            LibNfsBridge.nfsSetV4MinorVersion(handle, minorVersion)
        }

        LibNfsBridge.nfsSetUid(handle, uid)
        LibNfsBridge.nfsSetGid(handle, 0)
        LibNfsBridge.nfsSetDebug(handle, 1)

        val url = buildNfsUrl(vShare, nfsV4MinorVersion = minorVersion, forceMountPort = mountPort)
        Log.i(TAG, "mountContext: calling nfsMountUrl(\"$url\") (uid=$uid, $versionLabel)")

        val t2 = System.currentTimeMillis()
        val err = LibNfsBridge.nfsMountUrl(handle, url)
        val mountDuration = System.currentTimeMillis() - t2
        if (err != null) {
            val rpcErr = LibNfsBridge.nfsGetLastRpcError(handle)
            Log.e(TAG, "mountContext: $versionLabel nfsMountUrl FAILED after ${mountDuration}ms: $err | rpcErr=$rpcErr")
            LibNfsBridge.nfsDestroy(handle)

            // Fallback 1: If NFSv3 fails due to portmapper/rpcbind issue and mountPort was 0, try fallback with mountport=20048 (for NAT/Docker/Portproxy)
            if (version == 3 && mountPort == 0 && (err.contains("portmap", true) || err.contains("rpcbind", true) || rpcErr.contains("portmap", true))) {
                Log.w(TAG, "mountContext: NFSv3 standard mount failed with portmapper error, attempting fallback with mountport=20048")
                return mountContextForVersion(share, 3, 0, mountPort = 20048, useExplicitAuthFlavor = useExplicitAuthFlavor)
            }

            // Fallback 2: If mount failed with ret=-13 / EACCES (Permission Denied) and we haven't tried explicit auth flavor yet, retry with explicit auth flavor
            if (!useExplicitAuthFlavor && (err.contains("-13") || err.contains("13") || err.contains("acces", true) || err.contains("denied", true) || rpcErr.contains("-13"))) {
                Log.w(TAG, "mountContext: mount failed with ret=-13 (EACCES/Permission Denied), retrying with explicit AUTH_SYS flavor")
                return mountContextForVersion(share, version, minorVersion, mountPort = mountPort, useExplicitAuthFlavor = true)
            }

            return 0L to err
        }
        Log.i(TAG, "mountContext: $versionLabel mount succeeded (${System.currentTimeMillis() - t0}ms total)")
        return handle to null
    }

    /**
     * Create and mount an NFS context for the given share.
     * Returns a pair of (contextHandle, null) on success,
     * or (0, errorMessage) on failure.
     *
     * When [NetworkShare.nfsVersion] is 0 (auto):
     *   1. NFSv3 attempt (fastest & most compatible with servers like HaneWin, Synology, Linux, macOS).
     *   2. If NFSv3 fails due to version/service mismatch, fall back to NFSv4.2 → 4.1 → 4.0 cascade.
     *
     * When [NetworkShare.nfsVersion] is 4 (explicit NFSv4), only the NFSv4
     * minor version cascade is attempted (4.2 → 4.1 → 4.0).
     */
    @WorkerThread
    private fun mountContext(share: NetworkShare): Pair<Long, String?> {
        val t0 = System.currentTimeMillis()

        if (share.nfsVersion == 3) {
            // Explicit NFSv3
            return mountContextForVersion(share, 3)
        }

        if (share.nfsVersion == 4) {
            // Explicit NFSv4
            return mountV4WithMinorVersionCascade(share)
        }

        // Auto-detect (nfsVersion == 0):
        // Try NFSv3 FIRST because 99%+ of home/NAS servers (HaneWin NFS, Synology, Linux, macOS, unRAID) run NFSv3.
        Log.i(TAG, "mountContext: nfsVersion=auto (0) — attempting NFSv3 first")
        val (v3Handle, v3Err) = mountContextForVersion(share, 3)
        if (v3Handle != 0L) {
            Log.i(TAG, "mountContext: auto-detected NFSv3 SUCCESS in ${System.currentTimeMillis() - t0}ms ✓")
            return v3Handle to null
        }

        // If NFSv3 failed with auth/permission/stale error, don't try NFSv4 (the server rejected us, not version issue)
        if (v3Err != null) {
            val classified = classifyError(v3Err)
            if (classified == ErrorSentinel.PERMISSION_DENIED ||
                classified == ErrorSentinel.AUTH_REJECTED ||
                classified == ErrorSentinel.STALE_HANDLE ||
                classified == ErrorSentinel.PATH_NOT_FOUND) {
                Log.w(TAG, "mountContext: NFSv3 failed with definitive non-version error ($classified): $v3Err — skipping NFSv4 cascade")
                return 0L to v3Err
            }
        }

        Log.w(TAG, "mountContext: NFSv3 auto-detect attempt failed ($v3Err) — falling back to NFSv4 cascade")
        val (v4Handle, v4Err) = mountV4WithMinorVersionCascade(share)
        if (v4Handle != 0L) {
            Log.i(TAG, "mountContext: auto-detected NFSv4 SUCCESS in ${System.currentTimeMillis() - t0}ms ✓")
            return v4Handle to null
        }

        Log.e(TAG, "mountContext: auto-detect exhausted in ${System.currentTimeMillis() - t0}ms — NFSv3: $v3Err | NFSv4: $v4Err")
        val combined = "NFSv3: $v3Err | NFSv4: ${v4Err ?: "failed"}"
        return 0L to combined
    }

    /**
     * Try NFSv4.2 → 4.1 → 4.0 with early exit on non-version errors.
     * Returns (handle, null) on success, (0, lastError) on failure.
     */
    private fun mountV4WithMinorVersionCascade(
        share: NetworkShare
    ): Pair<Long, String?> {
        var lastErr: String? = null
        for (minor in intArrayOf(2, 1, 0)) {
            Log.i(TAG, "mountV4Cascade: trying NFSv4.$minor")
            val (handle, err) = mountContextForVersion(share, 4, minor)
            if (handle != 0L) {
                Log.i(TAG, "mountV4Cascade: NFSv4.$minor succeeded ✓")
                return handle to null
            }
            lastErr = err
            // Early exit: if the error is NOT a version mismatch, don't try
            // lower minor versions — the problem is something else entirely
            if (!isMinorVersionMismatchError(err)) {
                Log.w(TAG, "mountV4Cascade: NFSv4.$minor failed with " +
                      "non-version error, stopping cascade: $err")
                break
            }
            Log.w(TAG, "mountV4Cascade: NFSv4.$minor version mismatch, " +
                  "trying next minor version")
        }
        return 0L to lastErr
    }

    /**
     * Returns true if the mount error looks like an NFS4 minor version
     * mismatch (error 10021 / NFS4ERR_MINOR_VERS_MISMATCH).
     *
     * libnfs surfaces this in several ways depending on the server:
     * - "nfs_mount failed: ret=-5 errno=11" (EAGAIN)
     * - Error strings containing "10021" or "minor"
     * - Generic mount failures with no specific error text
     *
     * We err on the side of continuing the cascade (returning true)
     * for ambiguous errors, since the cost is just one more mount attempt.
     */
    private fun isMinorVersionMismatchError(error: String?): Boolean {
        if (error == null) return false
        val msg = error.lowercase()
        // Definite non-version errors → stop cascade immediately
        if (msg.contains("permission denied") ||
            msg.contains("access denied") ||
            msg.contains("auth") ||
            msg.contains("no such file") ||
            msg.contains("no such export") ||
            msg.contains("not found") ||
            msg.contains("connection refused") ||
            msg.contains("econnrefused") ||
            msg.contains("ehostunreach") ||
            msg.contains("enetunreach") ||
            msg.contains("stale")) {
            return false
        }
        // Probable version mismatch or ambiguous error → continue cascade
        return true
    }

    /**
     * Classify a raw libnfs error message into a sentinel key for the UI.
     * Maps RPC/NFS protocol errors to user-facing categories.
     */
    private fun classifyError(rawMessage: String): String {
        if (rawMessage.isBlank()) return "NFS_UNKNOWN_ERROR"
        val msg = rawMessage.lowercase()
        return when {
            // Auth / MSG_DENIED / AUTH_ERROR (root cause #1)
            msg.contains("msg_denied") || msg.contains("auth_error") ||
            msg.contains("auth_bogus_creds") || msg.contains("seal broken") ||
            msg.contains("authentication error") || msg.contains("auth rejected") ->
                ErrorSentinel.AUTH_REJECTED

            // Stale file handle
            msg.contains("stale") || msg.contains("nfs3err_stale") ->
                ErrorSentinel.STALE_HANDLE

            // Permission denied / Read-only filesystem
            msg.contains("permission denied") || msg.contains("access denied") ||
            msg.contains("nfs3err_acces") || msg.contains("nfs3err_perm") ||
            msg.contains("nfs3err_rofs") || msg.contains("read-only") ->
                ErrorSentinel.PERMISSION_DENIED

            // Path / export not found
            msg.contains("no such file") || msg.contains("no such export") ||
            msg.contains("nfs3err_noent") || msg.contains("not found") ->
                ErrorSentinel.PATH_NOT_FOUND

            // Portmapper / rpcbind unreachable
            msg.contains("portmap") || msg.contains("rpcbind") ->
                ErrorSentinel.PORTMAPPER_UNREACHABLE

            // Connection refused / timeout (TCP-level)
            msg.contains("connection refused") || msg.contains("connection timed out") ||
            msg.contains("econnrefused") || msg.contains("ehostunreach") ||
            msg.contains("enetunreach") ->
                ErrorSentinel.CONNECTION_FAILED

            // NFS version mismatch (including NFSv4 minor version mismatch / error 10021)
            msg.contains("version") && (msg.contains("mismatch") || msg.contains("not supported")) ->
                ErrorSentinel.VERSION_MISMATCH
            msg.contains("10021") || msg.contains("minor_vers_mismatch") ->
                ErrorSentinel.VERSION_MISMATCH

            // Service not available (RPC layer)
            msg.contains("program not registered") || msg.contains("prog_unavail") ||
            msg.contains("proc_unavail") ->
                ErrorSentinel.SERVICE_UNAVAILABLE

            else -> rawMessage
        }
    }

    /* ── Public API (mirrors NfsShareClient) ────────────────────────────────── */

    fun testConnection(share: NetworkShare): String? {
        val startTime = System.currentTimeMillis()
        val stages = mutableListOf<RpcStage>()
        var finalError: String? = null
        val versionLabel = when (share.nfsVersion) { 0 -> "auto" 3 -> "NFSv3" 4 -> "NFSv4" else -> "v${share.nfsVersion}" }
        Log.i(TAG, "testConnection: START host=${share.host} port=${share.effectivePort} version=$versionLabel")

        // Stage 1: NFS context init + mount.
        val initStart = System.currentTimeMillis()
        val (handle, mountErr) = mountContext(share)
        val mountDuration = System.currentTimeMillis() - initStart
        stages.add(RpcStage("NFS init + mount ($versionLabel)", mountErr == null, mountErr, mountDuration))

        if (mountErr != null) {
            Log.e(TAG, "testConnection: mount failed after ${mountDuration}ms — error=$mountErr")
            finalError = classifyError(mountErr)
            val totalTime = System.currentTimeMillis() - startTime
            Log.e(TAG, "testConnection: classified as \"$finalError\" (total=${totalTime}ms)")
            recordDebugEntry(share, stages, finalError, totalTime)
            return finalError
        }
        Log.i(TAG, "testConnection: mount OK (${mountDuration}ms)")

        // Stage 2: List root directory to verify mount
        val listStart = System.currentTimeMillis()
        return try {
            Log.d(TAG, "testConnection: calling nfsListDir(/, ...)")
            val entries = LibNfsBridge.nfsListDir(handle, "/")
            val listDuration = System.currentTimeMillis() - listStart
            stages.add(RpcStage("List root directory", true, "${entries?.size ?: 0} entries", listDuration))
            Log.i(TAG, "testConnection: nfsListDir OK — ${entries?.size ?: 0} entries in root (${listDuration}ms)")
            finalError = null
            null // success
        } catch (e: Exception) {
            val listDuration = System.currentTimeMillis() - listStart
            Log.e(TAG, "testConnection: nfsListDir FAILED after ${listDuration}ms", e)
            val msg = e.message ?: e.javaClass.simpleName
            stages.add(RpcStage("List root directory", false, msg, listDuration))
            finalError = classifyError(msg)
            Log.e(TAG, "testConnection: listDir error classified as \"$finalError\"")
            finalError
        } finally {
            LibNfsBridge.nfsDestroy(handle)
            val totalTime = System.currentTimeMillis() - startTime
            recordDebugEntry(share, stages, finalError, totalTime)
            Log.i(TAG, "testConnection: END result=${finalError ?: "SUCCESS"} (${totalTime}ms)")
        }
    }

    /**
     * Record a debug entry for the last mount attempt.
     */
    private fun recordDebugEntry(
        share: NetworkShare,
        stages: List<RpcStage>,
        finalError: String?,
        durationMs: Long,
        nfsV4MinorVersion: Int = 0
    ) {
        NfsDebugLogger.record(
            NfsDebugEntry(
                timestamp = System.currentTimeMillis(),
                host = share.host,
                path = share.remotePath,
                port = share.effectivePort,
                versionAttempted = share.nfsVersion.takeIf { it > 0 } ?: 3,
                nfsV4MinorVersion = nfsV4MinorVersion,
                authFlavor = share.nfsAuthFlavor,
                stages = stages,
                finalError = finalError,
                durationMs = durationMs
            )
        )
    }

    fun listFiles(share: NetworkShare, remotePath: String): List<NetworkFile> {
        val (handle, mountErr) = mountContext(share)
        if (mountErr != null) {
            Log.e(TAG, "listFiles mount failed for $remotePath: $mountErr")
            return emptyList()
        }

        return try {
            val path = normalizePath(remotePath)
            val entries = LibNfsBridge.nfsListDir(handle, path) ?: return emptyList()
            entries.map { raw ->
                // Format: "name\ttype\tsize\tmtime"
                val parts = raw.split("\t")
                val name = parts.getOrElse(0) { "" }
                val type = parts.getOrElse(1) { "f" }
                val size = parts.getOrElse(2) { "0" }.toLongOrNull() ?: 0L
                val mtime = (parts.getOrElse(3) { "0" }.toLongOrNull() ?: 0L) * 1000L
                NetworkFile(
                    name = name,
                    path = "/" + buildChildPath(remotePath, name).trimStart('/'),
                    isDirectory = type == "d",
                    size = if (type == "d") 0L else size,
                    lastModified = mtime
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "listFiles failed for path=$remotePath", e)
            emptyList()
        } finally {
            LibNfsBridge.nfsDestroy(handle)
        }
    }

    fun getFileSize(share: NetworkShare, remotePath: String): Long? {
        val (handle, mountErr) = mountContext(share)
        if (mountErr != null) return null

        return try {
            val size = LibNfsBridge.nfsFileSize(handle, normalizePath(remotePath))
            if (size >= 0) size else null
        } catch (e: Exception) {
            Log.e(TAG, "getFileSize failed for $remotePath", e)
            null
        } finally {
            LibNfsBridge.nfsDestroy(handle)
        }
    }

    fun openInputStream(share: NetworkShare, remotePath: String): InputStream {
        Log.d(TAG, "openInputStream: host=${share.host} path=$remotePath")
        val (handle, mountErr) = mountContext(share)
        if (mountErr != null) throw IOException("NFS mount failed: $mountErr")

        val path = normalizePath(remotePath)
        val fh = LibNfsBridge.nfsOpen(handle, path, 0)
        if (fh == 0L) {
            LibNfsBridge.nfsDestroy(handle)
            val rpcErr = LibNfsBridge.nfsGetLastRpcError(handle)
            Log.e(TAG, "openInputStream FAILED for $path: $rpcErr")
            throw IOException("Failed to open file for reading ($path): ${rpcErr.ifEmpty { "Permission denied or missing file" }}")
        }
        return NfsNativeInputStream(handle, fh)
    }

    fun openOutputStream(share: NetworkShare, remotePath: String): OutputStream {
        Log.d(TAG, "openOutputStream: host=${share.host} path=$remotePath")
        val (handle, mountErr) = mountContext(share)
        if (mountErr != null) throw IOException("NFS mount failed: $mountErr")

        val path = normalizePath(remotePath)
        val fh = LibNfsBridge.nfsOpen(handle, path, 1)
        if (fh == 0L) {
            val rpcErr = LibNfsBridge.nfsGetLastRpcError(handle)
            LibNfsBridge.nfsDestroy(handle)
            Log.e(TAG, "openOutputStream FAILED for $path: $rpcErr")
            throw IOException("Failed to open file for writing ($path): ${rpcErr.ifEmpty { "Permission denied (NFS3ERR_ACCES) or read-only export" }}")
        }
        return NfsNativeOutputStream(handle, fh)
    }

    fun openRandomAccessFile(
        share: NetworkShare,
        remotePath: String,
        isWrite: Boolean = false
    ): IRandomAccessFile {
        return LibNfsRandomAccess(share, normalizePath(remotePath), isWrite)
    }

    fun mkdir(share: NetworkShare, remotePath: String) {
        val path = normalizePath(remotePath)
        Log.d(TAG, "mkdir START: host=${share.host} path=$path")
        val (handle, mountErr) = mountContext(share)
        if (mountErr != null) throw IOException("NFS mount failed: $mountErr")
        try {
            val err = LibNfsBridge.nfsMkdir(handle, path)
            if (err != null) {
                Log.e(TAG, "mkdir FAILED for $path: $err")
                throw IOException(err)
            }
            Log.i(TAG, "mkdir SUCCESS: $path")
        } finally {
            LibNfsBridge.nfsDestroy(handle)
        }
    }

    fun deleteFile(share: NetworkShare, remotePath: String) {
        val path = normalizePath(remotePath)
        Log.d(TAG, "deleteFile START: host=${share.host} path=$path")
        val (handle, mountErr) = mountContext(share)
        if (mountErr != null) throw IOException("NFS mount failed: $mountErr")
        try {
            val err = LibNfsBridge.nfsUnlink(handle, path)
            if (err != null) {
                Log.e(TAG, "deleteFile FAILED for $path: $err")
                throw IOException("delete failed: $err")
            }
            Log.i(TAG, "deleteFile SUCCESS: $path")
        } finally {
            LibNfsBridge.nfsDestroy(handle)
        }
    }

    fun deleteDir(share: NetworkShare, remotePath: String) {
        val path = normalizePath(remotePath)
        Log.d(TAG, "deleteDir START: host=${share.host} path=$path")
        val (handle, mountErr) = mountContext(share)
        if (mountErr != null) throw IOException("NFS mount failed: $mountErr")
        try {
            val err = LibNfsBridge.nfsRmdir(handle, path)
            if (err != null) {
                Log.e(TAG, "deleteDir FAILED for $path: $err")
                throw IOException("rmdir failed: $err")
            }
            Log.i(TAG, "deleteDir SUCCESS: $path")
        } finally {
            LibNfsBridge.nfsDestroy(handle)
        }
    }

    fun rename(share: NetworkShare, fromPath: String, toPath: String) {
        val src = normalizePath(fromPath)
        val dst = normalizePath(toPath)
        Log.d(TAG, "rename START: host=${share.host} from=$src to=$dst")
        val (handle, mountErr) = mountContext(share)
        if (mountErr != null) throw IOException("NFS mount failed: $mountErr")
        try {
            val err = LibNfsBridge.nfsRename(handle, src, dst)
            if (err != null) {
                Log.e(TAG, "rename FAILED from $src to $dst: $err")
                throw IOException("rename failed: $err")
            }
            Log.i(TAG, "rename SUCCESS from $src to $dst")
        } finally {
            LibNfsBridge.nfsDestroy(handle)
        }
    }

    /** List available exports on a server. */
    fun listExports(server: String): List<String> {
        return try {
            LibNfsBridge.nfsListExports(server)?.toList() ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "listExports failed for $server", e)
            emptyList()
        }
    }

    /* ── Stream Wrappers ────────────────────────────────────────────────────── */

    private class NfsNativeInputStream(
        private val ctxHandle: Long,
        private val fileHandle: Long
    ) : InputStream() {
        private var closed = false
        private var position: Long = 0L

        override fun read(): Int {
            val buf = ByteArray(1)
            val n = read(buf, 0, 1)
            return if (n <= 0) -1 else buf[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (closed) throw IOException("Stream closed")
            if (len == 0) return 0
            val res = LibNfsBridge.nfsPread(ctxHandle, fileHandle, position, b, off, len)
            if (res < 0) throw IOException("NFS read failed")
            if (res == 0) return -1 // EOF
            position += res
            return res
        }

        override fun close() {
            if (!closed) {
                closed = true
                if (ctxHandle != 0L && fileHandle != 0L) {
                    LibNfsBridge.nfsClose(ctxHandle, fileHandle)
                }
                if (ctxHandle != 0L) {
                    LibNfsBridge.nfsDestroy(ctxHandle)
                }
            }
        }
    }

    private class NfsNativeOutputStream(
        private val ctxHandle: Long,
        private val fileHandle: Long
    ) : OutputStream() {
        private var closed = false

        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (closed) throw IOException("Stream closed")
            var bytesWritten = 0
            while (bytesWritten < len) {
                val toWrite = len - bytesWritten
                val res = LibNfsBridge.nfsWrite(ctxHandle, fileHandle, b, off + bytesWritten, toWrite)
                if (res < 0) throw IOException("NFS write failed")
                if (res == 0) throw IOException("NFS write returned 0 bytes")
                bytesWritten += res
            }
        }

        override fun close() {
            if (!closed) {
                closed = true
                if (ctxHandle != 0L && fileHandle != 0L) {
                    LibNfsBridge.nfsClose(ctxHandle, fileHandle)
                }
                if (ctxHandle != 0L) {
                    LibNfsBridge.nfsDestroy(ctxHandle)
                }
            }
        }
    }

    /** Random-access wrapper using libnfs pread/pwrite. */
    class LibNfsRandomAccess(
        private val share: NetworkShare,
        private val remotePath: String,
        isWrite: Boolean
    ) : IRandomAccessFile {
        private var ctxHandle: Long = 0L
        private var fileHandle: Long = 0L
        private var cachedSize: Long = -1L

        init {
            val (h, err) = mountContext(share)
            if (err != null) throw IOException("NFS mount failed: $err")
            ctxHandle = h
            fileHandle = LibNfsBridge.nfsOpen(h, remotePath, if (isWrite) 2 else 0)
            if (fileHandle == 0L) {
                LibNfsBridge.nfsDestroy(ctxHandle)
                throw IOException("Failed to open file: $remotePath")
            }
        }

        override val size: Long
            get() = synchronized(this) {
                if (cachedSize < 0) {
                    cachedSize = LibNfsBridge.nfsFileSize(ctxHandle, remotePath)
                    if (cachedSize < 0) throw IOException("Failed to get file size")
                }
                return cachedSize
            }

        override fun read(offset: Long, buffer: ByteArray, length: Int): Int = synchronized(this) {
            if (offset >= size) return -1
            var bytesRead = 0
            val toReadTotal = minOf(length.toLong(), size - offset).toInt()
            if (toReadTotal <= 0) return -1
            while (bytesRead < toReadTotal) {
                val toRead = toReadTotal - bytesRead
                val res = LibNfsBridge.nfsPread(
                    ctxHandle, fileHandle, offset + bytesRead, buffer, bytesRead, toRead
                )
                if (res < 0) throw IOException("NFS random-access read failed")
                if (res == 0) break // EOF reached earlier than expected
                bytesRead += res
            }
            return if (bytesRead == 0) -1 else bytesRead
        }

        override fun write(offset: Long, buffer: ByteArray, length: Int): Int = synchronized(this) {
            var bytesWritten = 0
            while (bytesWritten < length) {
                val toWrite = length - bytesWritten
                val res = LibNfsBridge.nfsPwrite(
                    ctxHandle, fileHandle, offset + bytesWritten, buffer, bytesWritten, toWrite
                )
                if (res < 0) throw IOException("NFS random-access write failed")
                if (res == 0) throw IOException("NFS random-access write returned 0 bytes")
                bytesWritten += res
            }
            return bytesWritten
        }

        override fun close() = synchronized(this) {
            if (fileHandle != 0L) {
                LibNfsBridge.nfsClose(ctxHandle, fileHandle)
                fileHandle = 0L
            }
            if (ctxHandle != 0L) {
                LibNfsBridge.nfsDestroy(ctxHandle)
                ctxHandle = 0L
            }
        }
    }
}
