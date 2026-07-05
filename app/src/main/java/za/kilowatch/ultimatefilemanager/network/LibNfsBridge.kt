package za.kilowatch.ultimatefilemanager.network

import android.util.Log

/**
 * Low-level JNI bridge to libnfs.
 *
 * All native methods operate on an opaque context handle (long) obtained from [nfsInit].
 * The context **must** be destroyed with [nfsDestroy] when no longer needed.
 */
object LibNfsBridge {

    private const val TAG = "LibNfsBridge"

    init {
        try {
            System.loadLibrary("nfs_jni")
            Log.i(TAG, "libnfs JNI loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load libnfs JNI: ${e.message}", e)
        }
    }

    /* ── Lifecycle ──────────────────────────────────────────────────────────── */

    /** Create a new NFS context. Returns handle, or 0 on failure. */
    @JvmStatic external fun nfsInit(): Long

    /** Destroy an NFS context and free all resources. */
    @JvmStatic external fun nfsDestroy(handle: Long)

    /* ── Configuration ─────────────────────────────────────────────────────── */

    @JvmStatic external fun nfsSetUid(handle: Long, uid: Int)
    @JvmStatic external fun nfsSetGid(handle: Long, gid: Int)

    /** Set NFS protocol version: 3 (NFSv3) or 4 (NFSv4). Default is v3. */
    @JvmStatic external fun nfsSetVersion(handle: Long, version: Int)

    /** Set NFSv4 minor version: 0 = NFSv4.0, 1 = NFSv4.1, 2 = NFSv4.2. */
    @JvmStatic external fun nfsSetV4MinorVersion(handle: Long, minorVersion: Int)

    /** Force the RPC authentication flavor. 1 = AUTH_SYS, 0 = AUTH_NONE. uid/gid for AUTH_UNIX. */
    @JvmStatic external fun nfsSetAuthFlavor(handle: Long, authFlavor: Int, uid: Int, gid: Int)

    /** Override the default RPC reply timeout (ms). Default in libnfs is 60000. */
    @JvmStatic external fun nfsSetTimeout(handle: Long, timeoutMs: Int)

    /** Get the last RPC-level error string. */
    @JvmStatic external fun nfsGetLastRpcError(handle: Long): String

    /** Enable debug logging. 0 = off, 1+ = verbose. */
    @JvmStatic external fun nfsSetDebug(handle: Long, level: Int)

    /* ── Mount ─────────────────────────────────────────────────────────────── */

    /**
     * Mount an NFS export. Returns null on success, error string on failure.
     */
    @JvmStatic external fun nfsMount(handle: Long, server: String, exportPath: String): String?

    /**
     * Mount using a full NFS URL with optional parameters.
     *
     * Example URLs:
     * - `nfs://192.168.1.10/share`
     * - `nfs://192.168.1.10/share?nfsport=2049&version=3`
     * - `nfs://server/export?nfsport=2049&mountport=20048`
     *
     * Returns null on success, error string on failure.
     */
    @JvmStatic external fun nfsMountUrl(handle: Long, url: String): String?

    /* ── Directory ─────────────────────────────────────────────────────────── */

    /**
     * List directory contents. Returns array of tab-separated strings:
     * `"name\ttype\tsize\tmtime"` where type is 'f', 'd', or 'l'.
     */
    @JvmStatic external fun nfsListDir(handle: Long, path: String): Array<String>?

    /** Create a directory. Returns null on success. */
    @JvmStatic external fun nfsMkdir(handle: Long, path: String): String?

    /** Remove a directory. Returns null on success. */
    @JvmStatic external fun nfsRmdir(handle: Long, path: String): String?

    /* ── File Operations ───────────────────────────────────────────────────── */

    /** Get file size. Returns -1 on error. */
    @JvmStatic external fun nfsFileSize(handle: Long, path: String): Long

    /**
     * Open a file. Returns file handle, or 0 on failure.
     * @param flags 0 = read-only, 1 = write (create), 2 = read-write
     */
    @JvmStatic external fun nfsOpen(handle: Long, path: String, flags: Int): Long

    /** Sequential read from open file handle. Returns bytes read, or -1. */
    @JvmStatic external fun nfsRead(handle: Long, fhHandle: Long, buffer: ByteArray, offset: Int, length: Int): Int

    /** Positional read. Returns bytes read, or -1. */
    @JvmStatic external fun nfsPread(handle: Long, fhHandle: Long, fileOffset: Long, buffer: ByteArray, bufOffset: Int, length: Int): Int

    /** Sequential write. Returns bytes written, or -1. */
    @JvmStatic external fun nfsWrite(handle: Long, fhHandle: Long, buffer: ByteArray, offset: Int, length: Int): Int

    /** Positional write. Returns bytes written, or -1. */
    @JvmStatic external fun nfsPwrite(handle: Long, fhHandle: Long, fileOffset: Long, buffer: ByteArray, bufOffset: Int, length: Int): Int

    /** Seek within a file. whence: 0=SET, 1=CUR, 2=END */
    @JvmStatic external fun nfsLseek(handle: Long, fhHandle: Long, offset: Long, whence: Int)

    /** Close an open file handle. */
    @JvmStatic external fun nfsClose(handle: Long, fhHandle: Long)

    /** Delete a file. Returns null on success. */
    @JvmStatic external fun nfsUnlink(handle: Long, path: String): String?

    /** Rename a file or directory. Returns null on success. */
    @JvmStatic external fun nfsRename(handle: Long, oldPath: String, newPath: String): String?

    /* ── Export Discovery ──────────────────────────────────────────────────── */

    /** List available NFS exports on a server (no context needed). */
    @JvmStatic external fun nfsListExports(server: String): Array<String>?

    /**
     * Discover NFS servers on the local network via RPC broadcast.
     * Returns server IP strings, or null if discovery failed.
     * Wraps libnfs's nfs_find_local_servers().
     */
    @JvmStatic external fun nfsFindLocalServers(): Array<String>?
}
