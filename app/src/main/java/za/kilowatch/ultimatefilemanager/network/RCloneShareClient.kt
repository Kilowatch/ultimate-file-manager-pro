package za.kilowatch.ultimatefilemanager.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.UfmApplication
import za.kilowatch.ultimatefilemanager.util.GoRoLog
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile as JavaRandomAccessFile
import java.util.concurrent.ConcurrentHashMap

/**
 * RClone cloud storage client — mirrors [WebDavShareClient]'s public API but
 * drives rclone RC methods through [gomobile.Gomobile.rcloneRPC] instead of HTTP.
 *
 * NetworkShare field mapping:
 *   host     = "rclone" (sentinel — [WebDavShareClient] uses this to detect RClone shares)
 *   username = remote name (the storage UUID / [OnlineStorage.id]) used as the "remote:"
 *              prefix in RC calls — must match the section key in the encrypted config file
 *              and the name passed to `config/create` in launchRCloneBrowse.
 *   password = unused (obscuring happens inside rclone)
 */
object RCloneShareClient {

    private const val TAG = "RCloneShareClient"
    private const val OCTET_STREAM = "application/octet-stream"

    /** Sentinel value placed in [NetworkShare.host] to mark RClone shares. */
    const val RCLONE_HOST_MARKER = "rclone"

    /** The fixed remote name used in rclone RC calls and as the config section header. */
    const val REMOTE_NAME = "ufm_rclone"


    // ── Process-scoped initialization ───────────────────────────────────

    /**
     * Whether rclone has been initialized for this process.
     * Guarded by [initLock] — set to true only after [gomobile.Gomobile.rcloneInitialize]
     * has succeeded.
     */
    @Volatile private var initialized = false
    private val initLock = Any()

    /**
     * Ensures rclone is initialized exactly once per process.
     *
     * Steps:
     *  1. Decrypt the encrypted config to a temp rclone.conf in cacheDir.
     *  2. Call [gomobile.Gomobile.rcloneInitialize].
     *  3. Point rclone at the temp config via `config/setpath`.
     *
     * The temp file is intentionally kept alive — rclone reads it lazily on
     * every remote access, so deleting it after setpath would break subsequent
     * RC calls. It lives at a fixed path in app-private cacheDir (overwritten
     * on each init, cleared by the OS when needed, never accessible to other apps).
     *
     * Must be called on a background thread (performs I/O and RPC).
     *
     * @throws IOException if initialization fails or no config exists yet.
     */
    fun ensureInitialized() {
        if (initialized) return
        synchronized(initLock) {
            if (initialized) return

            // Check if rclone is already initialized by another component
            // (e.g. OnlineStorageManagerActivity.launchRCloneBrowse). If so,
            // just mark as initialized and use the existing instance — calling
            // rcloneInitialize() again would reset the in-memory state and lose
            // the remote that was already created via config/create.
            try {
                val versionResult = gomobile.Gomobile.rcloneRPC("core/version", "{}")
                if (versionResult.status == 200L) {
                    // rclone is already initialized elsewhere — but we still need to set
                    // the config path so config/create writes to the correct location.
                    val ctx = UfmApplication.instance
                    val tempFile = RCloneConfig.decryptToTempFile(ctx)
                    if (tempFile != null) {
                        gomobile.Gomobile.rcloneRPC(
                            "config/setpath",
                            """{"path": "${tempFile.absolutePath}"}"""
                        )
                        GoRoLog.d(TAG, "Config path set to ${tempFile.absolutePath} (reusing existing rclone)")
                    }
                    initialized = true
                    GoRoLog.i(TAG, "rclone already initialized, reusing existing instance")
                    return
                }
            } catch (_: Exception) {
                // core/version failed — rclone needs full initialization
            }

            val ctx = UfmApplication.instance
            val tempFile = RCloneConfig.decryptToTempFile(ctx)
                ?: throw IOException("No RClone configuration found — please add a provider first")
            gomobile.Gomobile.rcloneInitialize()
            val pathJson = JSONObject().apply { put("path", tempFile.absolutePath) }
            GoRoLog.d(TAG, "ensureInitialized: setting config path to ${tempFile.absolutePath}")
            val setResult = gomobile.Gomobile.rcloneRPC("config/setpath", pathJson.toString())
            if (setResult.status != 200L) {
                tempFile.delete()
                throw IOException("rclone config/setpath failed (status ${setResult.status}): ${setResult.output}")
            }
            initialized = true
            GoRoLog.i(TAG, "rclone initialized, config at: ${tempFile.absolutePath}")
        }
    }

    /**
     * Set of remote names that have been explicitly created in the current rclone session.
     * Prevents repeated config/create calls for the same remote.
     */
    private val createdRemotes = mutableSetOf<String>()
    private val createdRemotesLock = Any()

    /**
     * Cache of remote names confirmed as Box OAuth providers.
     *
     * Avoids decrypting the encrypted config on every [rcloneCall] just to check
     * the provider type. Populated lazily by [isBoxRemote]. Cleared when
     * providers are added or removed via [resetInitialized].
     */
    private val boxRemoteCache = ConcurrentHashMap<String, Boolean>()

    /**
     * Ensures a specific remote (by [remoteName]) exists in rclone's in-memory config.
     * If it hasn't been created yet this session, reads the encrypted provider config
     * and calls `config/create`. This is robust against both fresh starts (where
     * config/setpath alone is insufficient) and session reuse (launchRCloneBrowse
     * may have created a different remote).
     */
    private fun ensureRemoteCreated(remoteName: String) {
        synchronized(createdRemotesLock) {
            if (createdRemotes.contains(remoteName)) return
        }

        val ctx = UfmApplication.instance
        val providers = try {
            RCloneConfig.readEncrypted(ctx)
        } catch (e: Exception) {
            GoRoLog.w(TAG, "Could not read encrypted config to create remote $remoteName: ${e.message}")
            return
        }

        val providerConfig = providers[remoteName] ?: run {
            GoRoLog.w(TAG, "No provider config found for remote $remoteName")
            return
        }

        val type = providerConfig["type"] ?: run {
            GoRoLog.w(TAG, "Provider config for $remoteName has no type field")
            return
        }

        val params = JSONObject(providerConfig.filterKeys { it != "type" } as Map<*, *>)
        val createParams = JSONObject().apply {
            put("name", remoteName)
            put("type", type)
            put("parameters", params)
        }

        // premiumizeme's Config callback always returns OAuth state, which
        // can deadlock on Android.  Bypass it by writing directly to the config
        // file — rclone reads remotes lazily from the file on each access.
        if (type == "premiumizeme") {
            val ctx2 = UfmApplication.instance
            val configFile = RCloneConfig.decryptToTempFile(ctx2)
            if (configFile != null) {
                GoRoLog.i(TAG, "Remote '$remoteName' already in config file, skipping config/create")
                gomobile.Gomobile.rcloneRPC(
                    "config/setpath",
                    """{"path": "${configFile.absolutePath}"}"""
                )
                synchronized(createdRemotesLock) { createdRemotes.add(remoteName) }
            } else {
                GoRoLog.w(TAG, "No config file for premiumizeme remote $remoteName")
            }
            return
        }

        val result = gomobile.Gomobile.rcloneRPC("config/create", createParams.toString())
        if (result.status == 200L) {
            synchronized(createdRemotesLock) { createdRemotes.add(remoteName) }
            GoRoLog.i(TAG, "Remote '$remoteName' created in rclone session")
        } else {
            GoRoLog.w(TAG, "config/create for '$remoteName' failed: ${result.output}")
        }
    }

    /**
     * Resets the initialization flag and cleans up the temp config file so the
     * next [rcloneCall] re-initializes rclone with fresh credentials.
     * Call this after saving new credentials.
     */
    fun resetInitialized() {
        synchronized(initLock) {
            initialized = false
            RCloneConfig.cleanTempConfig(UfmApplication.instance)
        }
        synchronized(createdRemotesLock) { createdRemotes.clear() }
        boxRemoteCache.clear()
    }

    /**
     * Performs a full, clean rclone initialization for a specific [storage] entry,
     * then registers the remote so it is immediately available for RC calls.
     *
     * This mirrors the logic in [OnlineStorageManagerActivity.launchRCloneBrowse] and
     * must be called on a **background thread** before launching [NetworkBrowserActivity]
     * for any RClone provider — regardless of which screen initiates the browse.
     *
     * Steps:
     *  1. If rclone is already running, finalize it to clear any stale Fs cache.
     *  2. Decrypt credentials to a temp rclone.conf.
     *  3. Call [gomobile.Gomobile.rcloneInitialize] + `config/setpath`.
     *  4. Call `config/create` for this specific remote (skipped for premiumizeme
     *     and box which rely on the file-based config).
     *  5. Mark [initialized] and add the remote to [createdRemotes].
     *
     * @throws IOException if no config is found or initialization fails.
     */
    suspend fun prepareForBrowse(storage: OnlineStorage) = withContext(Dispatchers.IO) {
        synchronized(initLock) {
            // Finalize any stale rclone state so the Fs cache is cleared.
            // The gomobile library caches filesystem instances by remote name, so
            // a previous session pointing at a different remote/config must be
            // reset before we can create a new one reliably.
            if (initialized) {
                try { gomobile.Gomobile.rcloneFinalize() } catch (_: Exception) {}
                initialized = false
            }
            synchronized(createdRemotesLock) { createdRemotes.clear() }
        }

        val ctx = UfmApplication.instance

        // Layer 1 (belt-and-suspenders): Proactive Box token refresh before
        // rclone even initialises. The main proactive check in [rcloneCall]
        // covers all entry points, but this catches the case where we're about
        // to spend time decrypting and initialising with a known-stale token.
        if (storage.refreshToken == RCloneProviderActivity.BOX_REFRESH_TOKEN_MARKER) {
            proactiveBoxTokenRefreshSync(NetworkShare(
                host = RCLONE_HOST_MARKER,
                username = storage.id,
                name = storage.displayName
            ))
        }

        val configFile = RCloneConfig.decryptToTempFile(ctx)
            ?: throw IOException("RClone credentials not found for ${storage.displayName}. Please re-add the storage.")

        gomobile.Gomobile.rcloneInitialize()
        gomobile.Gomobile.rcloneRPC("config/setpath", """{"path": "${configFile.absolutePath}"}""")

        // Layer 2: Sync any token changes that may have been written to the
        // temp config by rclone's internal PutToken() during initialization
        // back to the encrypted store.
        syncBoxTokenToEncrypted(storage.id)

        // Read the encrypted config to get provider parameters for this remote.
        val providers = RCloneConfig.readEncrypted(ctx)
        // Look up by storage.id; fall back to the first entry for backward compat.
        val remoteName = storage.id
        val providerConfig = providers[remoteName]
            ?: providers.keys.firstOrNull()?.let { providers[it] }
        val providerType = providerConfig?.get("type") ?: ""

        // premiumizeme and box rely on the file written by decryptToTempFile above —
        // calling config/create for them triggers a blocking OAuth callback on Android.
        if (providerType != "premiumizeme" && providerType != "box" && providerConfig != null) {
            val params = JSONObject(providerConfig.filterKeys { it != "type" } as Map<*, *>)
            val createParams = JSONObject().apply {
                put("name", remoteName)
                put("type", providerType)
                put("parameters", params)
            }
            val result = gomobile.Gomobile.rcloneRPC("config/create", createParams.toString())
            if (result.status != 200L) {
                GoRoLog.e(TAG, "prepareForBrowse: config/create failed for $remoteName: ${result.output}")
                throw IOException("Failed to initialize RClone remote: ${result.output}")
            }
        } else {
            GoRoLog.i(TAG, "prepareForBrowse: skipping config/create for $providerType — using file-based config")
        }

        synchronized(initLock) { initialized = true }
        synchronized(createdRemotesLock) { createdRemotes.add(remoteName) }
        GoRoLog.i(TAG, "prepareForBrowse: rclone ready for remote '$remoteName' (type=$providerType)")
    }

    // ── Sentinel detection ──────────────────────────────────────────────

    /** Returns true if [share] is an RClone share (not a real WebDAV share). */
    fun isRCloneShare(share: NetworkShare): Boolean = share.host == RCLONE_HOST_MARKER

    /** Returns the rclone remote name (the "fs" prefix for RC calls). */
    fun getRemoteName(share: NetworkShare): String = share.username.ifBlank { REMOTE_NAME }

    /**
     * Returns true if [share] is an RClone share backed by a Box OAuth provider.
     *
     * Results are cached in [boxRemoteCache] after the first lookup to avoid
     * repeated decryption of the encrypted config on every RC call. The cache
     * is cleared when providers are added or removed via [resetInitialized].
     *
     * Returns false if the config cannot be read (safe to call at any time).
     */
    fun isBoxRemote(share: NetworkShare): Boolean {
        if (!isRCloneShare(share)) return false
        val remoteName = getRemoteName(share)
        // Check cache first — avoids decrypting the config on every RC call
        boxRemoteCache[remoteName]?.let { return it }
        // Cache miss — read the encrypted config
        val result = try {
            val providers = RCloneConfig.readEncrypted(UfmApplication.instance)
            providers[remoteName]?.get("type") == "box"
        } catch (_: Exception) {
            false
        }
        boxRemoteCache[remoteName] = result
        return result
    }

    // ── RC call helpers ─────────────────────────────────────────────────

    /**
     * Calls an rclone RC method with the given [method] and JSON [params].
     * Ensures rclone is initialized before every call, and that the specific
     * remote for this [share] has been explicitly registered in the session.
     * The "fs" key is automatically set to the remote name if not already present.
     *
     * Layer 1 (Proactive Refresh): Before any RC call, checks if this is a Box
     * remote with an expired token and refreshes it pre-emptively. This covers
     * ALL entry points — both the Main Menu path (via [ensureInitialized]) and
     * the Online Storages path (via [prepareForBrowse]).
     *
     * @return the JSON output on success (status 200)
     * @throws IOException if the RC call fails or returns a non-200 status
     */
    private fun rcloneCall(share: NetworkShare, method: String, params: JSONObject = JSONObject()): String {
        // Layer 1: Proactive Box token refresh before any RC call
        if (isBoxRemote(share)) {
            proactiveBoxTokenRefreshSync(share)
        }

        ensureInitialized()
        val remoteName = getRemoteName(share)
        // Always ensure the remote exists in-memory — covers both fresh app starts
        // (where config/setpath alone doesn't register remotes) and session reuse
        // (where launchRCloneBrowse may have created a different remote).
        ensureRemoteCreated(remoteName)
        // Copy the params before mutating so callers can reuse their own JSONObject
        val args = JSONObject(params.toString())
        if (!args.has("fs")) {
            args.put("fs", "$remoteName:")
        }
        val result = gomobile.Gomobile.rcloneRPC(method, args.toString())
        if (result.status != 200L) {
            val errorMsg = try {
                JSONObject(result.output).optString("error", result.output)
            } catch (_: Exception) {
                result.output
            }
            throw IOException("rclone $method failed: $errorMsg")
        }
        return result.output
    }

    // ── Connection test ─────────────────────────────────────────────────

    suspend fun testConnection(share: NetworkShare): Boolean = withContext(Dispatchers.IO) {
        try {
            rcloneCall(share, "operations/list", JSONObject().apply { put("remote", "") })
            true
        } catch (e: Exception) {
            GoRoLog.e(TAG, "testConnection failed", e)
            false
        }
    }

    // ── File listing ────────────────────────────────────────────────────

    suspend fun listFiles(share: NetworkShare, remotePath: String): List<NetworkFile> =
        withContext(Dispatchers.IO) {
            val json = try {
                rcloneCall(share, "operations/list", JSONObject().apply {
                    put("remote", normalizePath(remotePath))
                })
            } catch (e: IOException) {
                // Layer 3: 401 recovery for Box remotes — attempt one
                // automatic refresh-and-retry cycle before giving up.
                if (isBoxRemote(share) && isAuthError(e.message)) {
                    if (attemptTokenRefreshAndRetry(share)) {
                        // Retry the call once with the freshly-refreshed token
                        rcloneCall(share, "operations/list", JSONObject().apply {
                            put("remote", normalizePath(remotePath))
                        })
                    } else {
                        throw e  // refresh failed — propagate the original error
                    }
                } else {
                    throw e  // not a Box auth error — propagate as-is
                }
            }
            parseFileList(json)
        }

    /** Parses the JSON response from an rclone operations/list call. */
    private fun parseFileList(json: String): List<NetworkFile> {
        val result = mutableListOf<NetworkFile>()
        val response = JSONObject(json)
        val list = response.optJSONArray("list") ?: return result
        for (i in 0 until list.length()) {
            val item = list.getJSONObject(i)
            result.add(NetworkFile(
                name         = item.optString("Name", ""),
                path         = item.optString("Path", ""),
                isDirectory  = item.optBoolean("IsDir", false),
                size         = item.optLong("Size", 0L),
                lastModified = parseRcloneTime(item.optString("ModTime", ""))
            ))
        }
        return result
    }

    // ── Directory operations ────────────────────────────────────────────

    suspend fun mkdir(share: NetworkShare, remotePath: String) = withContext(Dispatchers.IO) {
        rcloneCall(share, "operations/mkdir", JSONObject().apply {
            put("remote", normalizePath(remotePath))
        })
    }

    suspend fun deleteFile(share: NetworkShare, remotePath: String) = withContext(Dispatchers.IO) {
        rcloneCall(share, "operations/deletefile", JSONObject().apply {
            put("remote", normalizePath(remotePath))
        })
    }

    suspend fun deleteDir(share: NetworkShare, remotePath: String) = withContext(Dispatchers.IO) {
        // purge removes a directory and all its contents (unlike rmdir which requires empty)
        rcloneCall(share, "operations/purge", JSONObject().apply {
            put("remote", normalizePath(remotePath))
        })
    }

    suspend fun rename(share: NetworkShare, fromPath: String, toPath: String, isDirectory: Boolean = false) = withContext(Dispatchers.IO) {
        val remote = getRemoteName(share)
        if (isDirectory) {
            // rclone RC has no movedir — use sync/move with full path embedded in srcFs/dstFs
            rcloneCall(share, "sync/move", JSONObject().apply {
                put("srcFs", "$remote:${normalizePath(fromPath)}")
                put("dstFs", "$remote:${normalizePath(toPath)}")
                put("deleteEmptySrcDirs", true)
            })
        } else {
            rcloneCall(share, "operations/movefile", JSONObject().apply {
                put("srcFs", "$remote:")
                put("srcRemote", normalizePath(fromPath))
                put("dstFs", "$remote:")
                put("dstRemote", normalizePath(toPath))
            })
        }
    }


    // ── Streaming / I/O ─────────────────────────────────────────────────

    suspend fun openInputStream(share: NetworkShare, remotePath: String): Pair<InputStream, Long> =
        withContext(Dispatchers.IO) {
            // operations/cat is not registered in the Gomobile build — use
            // operations/copyfile to a self-deleting temp file for all file sizes.
            val tempFile = File.createTempFile("rclone_dl_", ".tmp")
            try {
                rcloneCall(share, "operations/copyfile", JSONObject().apply {
                    put("srcFs", "${getRemoteName(share)}:")
                    put("srcRemote", normalizePath(remotePath))
                    put("dstFs", "/")
                    put("dstRemote", tempFile.absolutePath)
                })
                val size = tempFile.length()
                val fis = FileInputStream(tempFile)
                // Wrap to auto-delete the temp file on close
                Pair(object : InputStream() {
                    override fun read(): Int = fis.read()
                    override fun read(b: ByteArray, off: Int, len: Int): Int = fis.read(b, off, len)
                    override fun available(): Int = fis.available()
                    override fun close() {
                        try { fis.close() } finally { tempFile.delete() }
                    }
                }, size)
            } catch (e: Exception) {
                tempFile.delete()
                throw e
            }
        }

    suspend fun openInputStreamForStreaming(
        share: NetworkShare, remotePath: String
    ): Pair<InputStream, Long> = openInputStream(share, remotePath)

    fun openInputStreamForStreamingSync(share: NetworkShare, remotePath: String): Pair<InputStream, Long> {
        return kotlinx.coroutines.runBlocking {
            openInputStream(share, remotePath)
        }
    }


    fun getFileSizeSync(share: NetworkShare, remotePath: String): Long {
        return try {
            val json = rcloneCall(share, "operations/stat", JSONObject().apply {
                put("remote", normalizePath(remotePath))
            })
            val item = JSONObject(json).optJSONObject("item")
            item?.optLong("Size", 0L) ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    fun openRandomAccessFile(share: NetworkShare, remotePath: String): IRandomAccessFile {
        val fsName = "${getRemoteName(share)}:"
        val normalizedPath = normalizePath(remotePath)
        val fileSize = getFileSizeSync(share, remotePath)

        // Probe: try a tiny range read to confirm the backend supports range reads.
        // Filen and most modern cloud backends do; some may not (e.g. encrypted archives).
        val supportsRange = try {
            val probe = gomobile.Gomobile.rcloneReadRange(fsName, normalizedPath, 0L, 1L)
            probe != null
        } catch (_: Exception) {
            false
        }

        if (supportsRange) {
            // ── True streaming: each read fetches only the requested byte range ──────
            GoRoLog.d(TAG, "openRandomAccessFile: using range-read streaming for $normalizedPath")
            return object : IRandomAccessFile {
                override val size: Long = fileSize

                override fun read(offset: Long, buffer: ByteArray, length: Int): Int {
                    if (fileSize > 0 && offset >= fileSize) return -1
                    val safeCount = if (fileSize > 0) {
                        minOf(length.toLong(), fileSize - offset).toInt()
                    } else {
                        length
                    }
                    if (safeCount <= 0) return -1
                    return try {
                        val data = gomobile.Gomobile.rcloneReadRange(
                            fsName, normalizedPath, offset, safeCount.toLong()
                        )
                        if (data == null || data.isEmpty()) return -1
                        val toCopy = minOf(data.size, safeCount)
                        System.arraycopy(data, 0, buffer, 0, toCopy)
                        toCopy
                    } catch (e: Exception) {
                        GoRoLog.w(TAG, "range read failed at offset $offset: ${e.message}")
                        -1
                    }
                }

                override fun write(offset: Long, buffer: ByteArray, length: Int): Int =
                    throw IOException("RClone random-access write not supported")

                override fun close() {
                    // Stateless — no connection to close
                }
            }
        }

        // ── Fallback: download the whole file to a temp file ─────────────────────
        // Only reached if the backend does not support byte-range reads.
        GoRoLog.w(TAG, "openRandomAccessFile: range reads not supported; falling back to full download for $normalizedPath")
        val tempFile = File.createTempFile("rclone_ra_", ".tmp")
        try {
            kotlinx.coroutines.runBlocking {
                rcloneCall(share, "operations/copyfile", JSONObject().apply {
                    put("srcFs", fsName)
                    put("srcRemote", normalizedPath)
                    put("dstFs", "/")
                    put("dstRemote", tempFile.absolutePath)
                })
            }
        } catch (e: Exception) {
            tempFile.delete()
            throw e
        }
        val raf = JavaRandomAccessFile(tempFile, "r")
        return object : IRandomAccessFile {
            override val size: Long get() = tempFile.length()
            override fun read(offset: Long, buffer: ByteArray, length: Int): Int {
                raf.seek(offset)
                return raf.read(buffer, 0, length)
            }
            override fun write(offset: Long, buffer: ByteArray, length: Int): Int =
                throw IOException("RClone random-access write not supported")
            override fun close() {
                try { raf.close() } finally { tempFile.delete() }
            }
        }
    }


    suspend fun uploadStream(
        share: NetworkShare, remotePath: String, inputStream: InputStream, totalSize: Long
    ) = withContext(Dispatchers.IO) {
        // Write to a temp file then copy to the remote
        val tempFile = File.createTempFile("rclone_ul_", ".tmp")
        try {
            tempFile.outputStream().use { out -> inputStream.copyTo(out) }
            rcloneCall(share, "operations/copyfile", JSONObject().apply {
                put("srcFs", "/")
                put("srcRemote", tempFile.absolutePath)
                put("dstFs", "${getRemoteName(share)}:")
                put("dstRemote", normalizePath(remotePath))
            })
        } finally {
            tempFile.delete()
        }
    }

    suspend fun openOutputStream(share: NetworkShare, remotePath: String): OutputStream =
        withContext(Dispatchers.IO) {
            val tempFile = File.createTempFile("rclone_os_", ".tmp")
            val fileOut = FileOutputStream(tempFile)
            object : OutputStream() {
                override fun write(b: Int) = fileOut.write(b)
                override fun write(b: ByteArray, off: Int, len: Int) = fileOut.write(b, off, len)
                override fun flush() = fileOut.flush()
                override fun close() {
                    try {
                        fileOut.close()
                        rcloneCall(share, "operations/copyfile", JSONObject().apply {
                            put("srcFs", "/")
                            put("srcRemote", tempFile.absolutePath)
                            put("dstFs", "${getRemoteName(share)}:")
                            put("dstRemote", normalizePath(remotePath))
                        })
                    } finally {
                        tempFile.delete()
                    }
                }
            }
        }

    // ── Progress-aware transfers ─────────────────────────────────────────

    /**
     * Downloads [remotePath] from this rclone remote to [destFile], reporting
     * real-time byte progress by polling [core/stats] every 300 ms alongside
     * the [operations/copyfile] call.
     *
     * Unlike the [openInputStream] path (which downloads everything to a temp
     * file first and then fires progress during the fast local copy), this
     * gives accurate cloud-transfer progress to the UI.
     */
    suspend fun downloadWithProgress(
        share: NetworkShare,
        remotePath: String,
        destFile: File,
        fileSize: Long,
        onProgress: ((bytesCopied: Long, totalBytes: Long) -> Unit)?
    ) = transferWithProgress(
        share,
        srcFs    = "${getRemoteName(share)}:",
        srcRemote = normalizePath(remotePath),
        dstFs    = "/",
        dstRemote = destFile.absolutePath,
        fileSize  = fileSize,
        onProgress = onProgress
    )

    /**
     * Uploads [srcFile] to [remotePath] on this rclone remote, reporting
     * real-time progress via [core/stats] polling.
     */
    suspend fun uploadWithProgress(
        share: NetworkShare,
        srcFile: File,
        remotePath: String,
        fileSize: Long,
        onProgress: ((bytesCopied: Long, totalBytes: Long) -> Unit)?
    ) = transferWithProgress(
        share,
        srcFs    = "/",
        srcRemote = srcFile.absolutePath,
        dstFs    = "${getRemoteName(share)}:",
        dstRemote = normalizePath(remotePath),
        fileSize  = fileSize,
        onProgress = onProgress
    )

    /**
     * Core implementation: launches [operations/copyfile] as an async rclone job
     * (`_async: true`) and polls [core/stats] + [job/status] every 300 ms so the
     * caller gets live byte-count updates.
     *
     * Using `_async: true` is critical because the gomobile JNI bridge serialises
     * all Go method calls onto a single OS thread.  A synchronous
     * [operations/copyfile] blocks that thread for the entire transfer, starving
     * every [core/stats] poll — resulting in 0 % → 100 % progress with nothing
     * in between.  With `_async: true`, rclone runs the transfer in its own Go
     * goroutine and the Kotlin side can poll freely.
     */
    private suspend fun transferWithProgress(
        share: NetworkShare,
        srcFs: String,
        srcRemote: String,
        dstFs: String,
        dstRemote: String,
        fileSize: Long,
        onProgress: ((bytesCopied: Long, totalBytes: Long) -> Unit)?
    ) = withContext(Dispatchers.IO) {
        // Reset accumulated stats so we start counting from zero for this file.
        try { rcloneCall(share, "core/stats-reset", JSONObject()) } catch (_: Exception) {}

        if (onProgress == null) {
            // No UI to update — just run the copy directly
            rcloneCall(share, "operations/copyfile", JSONObject().apply {
                put("srcFs", srcFs); put("srcRemote", srcRemote)
                put("dstFs", dstFs); put("dstRemote", dstRemote)
            })
            return@withContext
        }

        // ── Async job mode ────────────────────────────────────────────────────
        // The gomobile JNI bridge serialises all Go method calls onto a single
        // OS thread.  Calling operations/copyfile synchronously blocks that
        // thread for the entire transfer, so every subsequent core/stats poll
        // is queued behind it — producing 0 % → 100 % progress with nothing
        // in between.
        //
        // Fix: pass _async:true so rclone starts the transfer in its own Go
        // goroutine and returns a jobId immediately.  We can then poll
        // core/stats (byte progress) and job/status (completion flag) freely
        // from Kotlin without any JNI contention.
        // ─────────────────────────────────────────────────────────────────────

        // Launch the copy as an async rclone job
        val asyncParams = JSONObject().apply {
            put("srcFs", srcFs); put("srcRemote", srcRemote)
            put("dstFs", dstFs); put("dstRemote", dstRemote)
            put("_async", true)
        }
        val startResult = JSONObject(rcloneCall(share, "operations/copyfile", asyncParams))
        val jobId = startResult.optLong("jobid", -1L)
        if (jobId < 0) {
            // Unexpected: async response had no jobid — transfer may have already
            // completed synchronously (e.g. zero-byte file). Emit 100% and return.
            onProgress(fileSize, fileSize)
            return@withContext
        }

        // Poll until the job finishes
        var jobFinished = false
        var jobError: String? = null
        while (!jobFinished) {
            kotlinx.coroutines.delay(300)

            // ① Update byte-progress from global stats
            try {
                val stats = JSONObject(rcloneCall(share, "core/stats", JSONObject()))
                val transferred = stats.optLong("bytes", 0L)
                if (transferred > 0L) {
                    val total = if (fileSize > 0L) fileSize
                                else stats.optLong("totalBytes", -1L)
                    onProgress(
                        transferred.coerceAtMost(if (total > 0) total else Long.MAX_VALUE),
                        total
                    )
                }
            } catch (_: Exception) { /* stats not yet available — skip */ }

            // ② Check job completion
            try {
                val jobStatus = JSONObject(
                    rcloneCall(share, "job/status", JSONObject().apply { put("jobid", jobId) })
                )
                if (jobStatus.optBoolean("finished", false)) {
                    jobFinished = true
                    if (!jobStatus.optBoolean("success", true)) {
                        jobError = jobStatus.optString("error", "unknown rclone error")
                    }
                }
            } catch (_: Exception) { /* job not yet visible — retry next tick */ }
        }

        if (jobError != null) {
            throw java.io.IOException("rclone operations/copyfile async job failed: $jobError")
        }

        // Final 100 %
        onProgress(fileSize, fileSize)
    }

    // ── Internal helpers ────────────────────────────────────────────────

    /** Sentinel value in the Box OAuth token JSON used to mark the refresh token field. */
    private const val BOX_REFRESH_TOKEN_FIELD = "refresh_token"

    /**
     * Layer 3 (Emergency Recovery): Attempts a full Box token refresh-and-retry
     * cycle when a 401 is detected.
     *
     * Steps:
     * 1. Reads the stored refresh_token from encrypted config
     * 2. Calls Box's OAuth endpoint via [BoxTokenRefresher]
     * 3. Persists the new token to encrypted storage
     * 4. Finalizes and re-initializes rclone with the updated config
     *
     * @return true if the cycle completed and the caller should retry,
     *         false if recovery failed (error already logged).
     */
    private fun attemptTokenRefreshAndRetry(share: NetworkShare): Boolean {
        return try {
            GoRoLog.i(TAG, "Layer 3: Attempting Box token refresh and retry for ${getRemoteName(share)}")
            val ctx = UfmApplication.instance
            val remoteName = getRemoteName(share)
            val providers = RCloneConfig.readEncrypted(ctx)
            val config = providers[remoteName] ?: return false
            val tokenJson = config["token"] ?: return false
            val parsed = JSONObject(tokenJson)
            val refreshToken = parsed.optString(BOX_REFRESH_TOKEN_FIELD, "")
            if (refreshToken.isEmpty()) return false

            val newTokenJson = kotlinx.coroutines.runBlocking {
                BoxTokenRefresher.refreshToken(refreshToken)
            }
            RCloneConfig.updateBoxToken(ctx, remoteName, newTokenJson)

            // Reset rclone so it picks up the new token from the updated config
            val configFile = RCloneConfig.decryptToTempFile(ctx) ?: return false
            gomobile.Gomobile.rcloneFinalize()
            gomobile.Gomobile.rcloneInitialize()
            gomobile.Gomobile.rcloneRPC(
                "config/setpath",
                """{"path": "${configFile.absolutePath}"}"""
            )
            synchronized(createdRemotesLock) { createdRemotes.clear() }

            GoRoLog.i(TAG, "Layer 3: Box token refreshed and rclone re-initialised — will retry")
            true
        } catch (e: Exception) {
            GoRoLog.e(TAG, "Layer 3: Box token refresh + retry failed", e)
            false
        }
    }

    /**
     * Returns true if [message] suggests a 401 authentication/authorisation error
     * from the rclone Box backend.
     */
    private fun isAuthError(message: String?): Boolean {
        if (message == null) return false
        return message.contains("401") && (
            message.contains("unauthorized", ignoreCase = true) ||
            message.contains("invalid_token", ignoreCase = true) ||
            message.contains("expired_token", ignoreCase = true)
        )
    }

    /**
     * Synchronous proactive Box token refresh for use from [rcloneCall].
     *
     * Silently returns if:
     * - The share is not a Box remote
     * - The stored token is not expired
     * - The refresh_token is missing
     * - The HTTP call fails (the error is logged, and the caller will handle
     *   the resulting 401 via Layer 3)
     */
    private fun proactiveBoxTokenRefreshSync(share: NetworkShare) {
        try {
            val remoteName = getRemoteName(share)
            val providers = RCloneConfig.readEncrypted(UfmApplication.instance)
            val providerConfig = providers[remoteName] ?: return
            if (providerConfig["type"] != "box") return
            val tokenJson = providerConfig["token"] ?: return
            if (!RCloneConfig.isTokenExpired(tokenJson)) return

            val parsed = JSONObject(tokenJson)
            val refreshToken = parsed.optString(BOX_REFRESH_TOKEN_FIELD, "")
            if (refreshToken.isEmpty()) return

            val newTokenJson = BoxTokenRefresher.refreshTokenSync(refreshToken)
            RCloneConfig.updateBoxToken(UfmApplication.instance, remoteName, newTokenJson)
            GoRoLog.i(TAG, "Proactively refreshed Box token for $remoteName")
        } catch (e: Exception) {
            GoRoLog.w(TAG, "Proactive Box token refresh failed (will retry on error): ${e.message}")
        }
    }

    /**
     * Extracts the Box OAuth token JSON from an rclone.conf-style INI section.
     *
     * Looks for a section header `[storageId]` and reads the `token = {...}`
     * value from it. Returns null if the section or token is not found.
     */
    private fun extractBoxTokenFromConfig(configText: String, storageId: String): String? {
        val sectionRegex = Regex(
            """\[$storageId]\n(.*?)(?=\n\[|\z)""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )
        val section = sectionRegex.find(configText)?.groupValues?.getOrNull(1) ?: return null
        val tokenRegex = Regex("""^token\s*=\s*(\{.+\})$""", setOf(RegexOption.MULTILINE))
        return tokenRegex.find(section)?.groupValues?.getOrNull(1)
    }

    /**
     * Layer 2 (Post-Sync): Reads the temp rclone.conf, extracts the Box token,
     * and saves it to encrypted storage if it differs from what's currently stored.
     *
     * This catches token refreshes that rclone's Go library performed internally
     * (via PutToken) during initialization or active operations.
     */
    private fun syncBoxTokenToEncrypted(storageId: String) {
        try {
            val ctx = UfmApplication.instance
            val tempFile = File(ctx.cacheDir, "rclone/rclone_temp.conf")
            if (!tempFile.exists()) return

            val tempConfig = tempFile.readText()
            val tokenInTemp = extractBoxTokenFromConfig(tempConfig, storageId) ?: return

            val encryptedProviders = RCloneConfig.readEncrypted(ctx)
            val encryptedToken = encryptedProviders[storageId]?.get("token")
            if (tokenInTemp == encryptedToken) return  // unchanged

            RCloneConfig.updateBoxToken(ctx, storageId, tokenInTemp)
            GoRoLog.i(TAG, "Synced refreshed Box token back to encrypted store for $storageId")
        } catch (e: Exception) {
            GoRoLog.w(TAG, "Box token sync failed (non-fatal): ${e.message}")
        }
    }

    /** Normalises a remote path — strips leading slash if present. */
    private fun normalizePath(path: String): String {
        return path.trimStart('/')
    }

    /** Parses an rclone ModTime string (RFC 3339) to a Unix timestamp in millis. */
    private fun parseRcloneTime(modTime: String): Long {
        if (modTime.isBlank()) return 0L
        return try {
            java.time.Instant.parse(modTime).toEpochMilli()
        } catch (_: Exception) {
            try {
                java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply {
                    timeZone = java.util.TimeZone.getTimeZone("UTC")
                }.parse(modTime)?.time ?: 0L
            } catch (_: Exception) {
                0L
            }
        }
    }
}
