package za.kilowatch.ultimatefilemanager.network

import android.content.Context
import android.util.Log
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.UfmApplication
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.keyverifier.ServerKeyVerifier
import org.apache.sshd.client.session.ClientSession
import java.security.MessageDigest
import java.security.PublicKey
import org.apache.sshd.common.keyprovider.FileKeyPairProvider
import org.apache.sshd.sftp.client.SftpClientFactory
import za.kilowatch.ultimatefilemanager.storage.VaultCrypto
import java.io.File
import java.util.concurrent.TimeUnit

object SshShareClient {
    const val TAG = "SshShareClient"
    const val TIMEOUT_SECONDS = 10L

    // ── Connection test ───────────────────────────────────────────────────────

    /**
     * Tests the connection. For SCP: tries SFTP first, falls back to exec channel.
     * Returns null on success, error message on failure.
     */
    fun testConnection(context: Context, share: NetworkShare): String? {
        var newFingerprint: String? = null
        val client = SshClient.setUpDefaultClient()
        setupClientAuth(client, share) { fp -> newFingerprint = fp }
        client.start()

        return try {
            val session = client.connect(share.username, share.host, share.effectivePort)
                .verify(TIMEOUT_SECONDS, TimeUnit.SECONDS).session

            // Persist fingerprint on TOFU (also persisted directly in verifier, this is a fallback)
            if (newFingerprint != null) {
                val updatedShare = share.copy(hostKeyFingerprint = newFingerprint)
                NetworkShareRepository.getInstance(context).save(updatedShare)
            }

            if (!authenticate(session, share)) return "Authentication failed"

            when (share.type) {
                ShareType.SFTP -> {
                    SftpClientFactory.instance().createSftpClient(session).use { sftp ->
                        val path = if (share.remotePath.isEmpty()) "/" else share.remotePath
                        sftp.readDir(path)
                    }
                }
                ShareType.SCP -> {
                    // Try SFTP first; fall back to a raw exec ping
                    val sftpOk = runCatching {
                        SftpClientFactory.instance().createSftpClient(session).use { sftp ->
                            val path = if (share.remotePath.isEmpty()) "/" else share.remotePath
                            sftp.readDir(path)
                        }
                    }.isSuccess
                    if (!sftpOk) {
                        // Bare exec: confirm shell access is available (routers etc.)
                        session.createExecChannel("echo ok").use { ch ->
                            ch.open().verify(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                            ch.waitFor(listOf(org.apache.sshd.client.channel.ClientChannelEvent.CLOSED), 5_000L)
                        }
                    }
                }
                ShareType.DLNA -> throw UnsupportedOperationException("DLNA is not an SSH protocol")
                else -> return "Unsupported share type"
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Connection test failed: ${e.message}", e)
            val msg = e.message ?: ""
            if (msg.contains("server key", ignoreCase = true)) {
                context.getString(R.string.ssh_host_key_mismatch_error)
            } else {
                e.message ?: "Unknown SSH error"
            }
        } finally {
            client.stop()
        }
    }

    // ── File operations (pooled sessions) ────────────────────────────────────

    fun listFiles(share: NetworkShare, path: String): List<NetworkFile> {
        return withPooledSession(share) { session ->
            val remotePath = effectivePath(share, path)

            when (share.type) {
                ShareType.SFTP -> listViaSftp(session, remotePath, path)
                ShareType.SCP  -> listScpWithFallback(session, remotePath, path)
                ShareType.DLNA -> throw UnsupportedOperationException("DLNA is not an SSH protocol")
                else -> emptyList()
            }
        }
    }

    /**
     * Returns the size of [path] on the remote SFTP server in bytes,
     * or `-1L` if the file does not exist or the stat fails.
     *
     * Uses a direct SFTP `lstat` call rather than a directory listing so the
     * result reflects the server's current on-disk metadata immediately after a
     * write — no stale directory-entry race.
     */
    fun getFileSize(share: NetworkShare, path: String): Long {
        return try {
            withPooledSession(share) { session ->
                SftpClientFactory.instance().createSftpClient(session).use { sftp ->
                    sftp.lstat(path).size
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "getFileSize failed for '$path': ${e.message}")
            -1L
        }
    }

    fun mkdir(share: NetworkShare, path: String) {
        withPooledSession(share) { session ->
            sftpOrExec(session,
                sftp = { it.mkdir(path) },
                exec = { sess -> runShellCommand(sess, "mkdir -p \"$path\"") }
            )
        }
    }

    fun rename(share: NetworkShare, oldPath: String, newPath: String) {
        withPooledSession(share) { session ->
            sftpOrExec(session,
                sftp = { it.rename(oldPath, newPath) },
                exec = { sess -> runShellCommand(sess, "mv \"$oldPath\" \"$newPath\"") }
            )
        }
    }

    fun delete(share: NetworkShare, path: String, isDirectory: Boolean) {
        withPooledSession(share) { session ->
            try {
                SftpClientFactory.instance().createSftpClient(session).use { sftp ->
                    // We ignore the isDirectory flag here and let sftpDeleteRecursive 
                    // handle both files and directories intelligently.
                    sftpDeleteRecursive(sftp, path)
                }
                Log.d(TAG, "Deleted via SFTP: $path")
            } catch (sftpEx: Exception) {
                Log.w(TAG, "SFTP delete failed for '$path': ${sftpEx.message}, trying shell fallback")
                try {
                    // Always use -rf in shell for maximum compatibility with folders/files
                    val cmd = "rm -rf \"$path\""
                    runShellCommand(session, cmd)
                    Log.d(TAG, "Deleted via shell: $path")
                } catch (shellEx: Exception) {
                    Log.e(TAG, "Shell delete also failed for '$path': ${shellEx.message}")
                    throw sftpEx
                }
            }
        }
    }

    /** Recursively delete a remote path via SFTP, regardless of whether it's a file or directory. */
    private fun sftpDeleteRecursive(sftp: org.apache.sshd.sftp.client.SftpClient, path: String) {
        // Try as a regular file first — fast path for the common case
        if (runCatching { sftp.remove(path) }.isSuccess) return

        // It's a directory (or symlink) — list contents and recurse
        val entries = runCatching {
            sftp.readDir(path).filter { it.filename != "." && it.filename != ".." }
        }.getOrElse { emptyList() }

        for (entry in entries) {
            val childPath = "${path.trimEnd('/')}/${entry.filename}"
            sftpDeleteRecursive(sftp, childPath) // let each child decide if it's file or dir
        }

        sftp.rmdir(path) // now directory should be empty
    }

    // ── Streaming I/O (dedicated, non-pooled sessions) ────────────────────────

    fun openInputStream(share: NetworkShare, path: String): java.io.InputStream {
        val pooled = SshSessionPool.borrow(share, dedicated = true)
        return try {
            val sftp = SftpClientFactory.instance().createSftpClient(pooled.session)
            val stream = sftp.read(path)
            object : java.io.InputStream() {
                override fun read() = stream.read()
                override fun read(b: ByteArray, off: Int, len: Int) = stream.read(b, off, len)
                override fun close() {
                    runCatching { stream.close() }
                    pooled.release()
                }
            }
        } catch (e: Exception) {
            pooled.invalidate()
            throw e
        }
    }

    fun openOutputStream(share: NetworkShare, path: String): java.io.OutputStream {
        val pooled = SshSessionPool.borrow(share, dedicated = true)
        return try {
            val sftp = SftpClientFactory.instance().createSftpClient(pooled.session)
            val stream = sftp.write(path)
            object : java.io.OutputStream() {
                override fun write(b: Int) = stream.write(b)
                override fun write(b: ByteArray, off: Int, len: Int) = stream.write(b, off, len)
                override fun flush() = stream.flush()
                override fun close() {
                    runCatching { stream.close() }
                    pooled.release()
                }
            }
        } catch (e: Exception) {
            pooled.invalidate()
            throw e
        }
    }

    fun openRandomAccessFile(share: NetworkShare, path: String, dedicated: Boolean = true): IRandomAccessFile {
        val pooled = SshSessionPool.borrow(share, dedicated = dedicated)
        return try {
            val sftp = SftpClientFactory.instance().createSftpClient(pooled.session)
            val handle = sftp.open(path, java.util.EnumSet.of(org.apache.sshd.sftp.client.SftpClient.OpenMode.Read))
            val attrs = sftp.stat(handle)
            object : IRandomAccessFile {
                override var size = attrs.size

                override fun read(offset: Long, buffer: ByteArray, length: Int): Int = synchronized(this) {
                    if (offset >= size) return -1
                    var totalRead = 0
                    var currentOffset = offset
                    try {
                        while (totalRead < length) {
                            val rd = sftp.read(handle, currentOffset, buffer, totalRead, length - totalRead)
                            if (rd <= 0) break
                            totalRead += rd
                            currentOffset += rd
                        }
                    } catch (e: Exception) {
                        pooled.invalidate()
                        throw e
                    }
                    return if (totalRead == 0) -1 else totalRead
                }

                override fun write(offset: Long, buffer: ByteArray, length: Int): Int {
                    return 0 // Unsupported in read-only handle
                }

                override fun close() {
                    runCatching { handle.close() }
                    runCatching { sftp.close() }
                    pooled.release()
                }
            }
        } catch (e: Exception) {
            pooled.invalidate()
            throw e
        }
    }

    // ── Auth setup (internal + called by SshSessionPool) ─────────────────────

    fun setupClientAuth(client: SshClient, share: NetworkShare, onNewFingerprint: ((String) -> Unit)? = null) {
        client.setServerKeyVerifier(TofuServerKeyVerifier(share, onNewFingerprint ?: {}))
    }

    // ── TOFU Host Key Verifier ────────────────────────────────────────────

    private class TofuServerKeyVerifier(
        private val share: NetworkShare,
        private val onNewFingerprint: (String) -> Unit
    ) : ServerKeyVerifier {

        override fun verifyServerKey(
            session: ClientSession?,
            remoteAddress: java.net.SocketAddress?,
            serverKey: PublicKey
        ): Boolean {
            val fingerprint = computeFingerprint(serverKey)
            return when {
                share.hostKeyFingerprint == null -> {
                    onNewFingerprint(fingerprint)
                    try {
                        val updated = share.copy(hostKeyFingerprint = fingerprint)
                        NetworkShareRepository.getInstance(UfmApplication.instance).save(updated)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to persist host key fingerprint", e)
                    }
                    true
                }
                MessageDigest.isEqual(
                    share.hostKeyFingerprint.toByteArray(Charsets.UTF_8),
                    fingerprint.toByteArray(Charsets.UTF_8)
                ) -> true
                else -> false
            }
        }

        private fun computeFingerprint(key: PublicKey): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(key.encoded)
            return hash.joinToString("") { "%02x".format(it) }
        }
    }

    fun authenticate(session: ClientSession, share: NetworkShare): Boolean {
        var hasIdentity = false

        if (!share.privateKeyPath.isNullOrEmpty()) {
            val keyFile = File(share.privateKeyPath)
            Log.d(TAG, "Key: ${keyFile.absolutePath} exists=${keyFile.exists()} size=${keyFile.length()}")
            if (keyFile.exists() && keyFile.length() > 0) {
                val passphrase: String? = if (share.password.isNotEmpty()) {
                    if (share.useKeychain) {
                        try { VaultCrypto.decryptString(share.password) } catch (_: Exception) { share.password }
                    } else share.password
                } else null

                try {
                    if (keyFile.extension.equals("ppk", ignoreCase = true)) {
                        val kp = PpkKeyParser.loadKeyPair(keyFile, passphrase)
                        session.addPublicKeyIdentity(kp)
                        hasIdentity = true
                        Log.d(TAG, "PPK key loaded: ${kp.public.algorithm}")
                    } else {
                        val provider = FileKeyPairProvider(keyFile.toPath())
                        if (passphrase != null) {
                            provider.passwordFinder =
                                org.apache.sshd.common.config.keys.FilePasswordProvider.of(passphrase)
                        }
                        val pairs = provider.loadKeys(null).toList()
                        Log.d(TAG, "OpenSSH keys loaded: ${pairs.size}")
                        pairs.forEach { kp -> session.addPublicKeyIdentity(kp); hasIdentity = true }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Key load error '${keyFile.name}': ${e.message}", e)
                }
            } else {
                Log.w(TAG, "Key file missing/empty: ${keyFile.absolutePath}")
            }
        }

        // Always offer password — covers pure-password auth and mixed servers.
        if (share.password.isNotEmpty()) {
            val pw = if (share.useKeychain) {
                try { VaultCrypto.decryptString(share.password) } catch (_: Exception) { share.password }
            } else share.password
            session.addPasswordIdentity(pw)
            hasIdentity = true
        }

        if (!hasIdentity) {
            Log.e(TAG, "No auth identity (no key and no password)")
            return false
        }

        return runCatching {
            session.auth().verify(TIMEOUT_SECONDS, TimeUnit.SECONDS).isSuccess
        }.getOrElse { e ->
            Log.e(TAG, "Auth failed: ${e.message}")
            false
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun <T> withPooledSession(share: NetworkShare, block: (ClientSession) -> T): T {
        val pooled = SshSessionPool.borrow(share)
        return try {
            val result = block(pooled.session)
            pooled.release()
            result
        } catch (e: Exception) {
            pooled.invalidate()
            throw e
        }
    }

    /**
     * Determine the effective remote path for a listing call.
     * If [path] is empty (root level), uses the share's configured [remotePath].
     */
    private fun effectivePath(share: NetworkShare, path: String): String =
        if (path.isNotEmpty()) path
        else if (share.remotePath.isNotEmpty()) share.remotePath
        else "/"

    /**
     * List files using the SFTP subsystem. Works on Linux, Windows (Bitvise/Win32-OpenSSH),
     * routers with SFTP support (OpenWRT 18+), NAS devices.
     */
    private fun listViaSftp(session: ClientSession, remotePath: String, displayPath: String): List<NetworkFile> {
        return SftpClientFactory.instance().createSftpClient(session).use { sftp ->
            sftp.readDir(remotePath).map { entry ->
                val isDir = entry.attributes.isDirectory
                NetworkFile(
                    name = entry.filename,
                    path = "${remotePath.trimEnd('/')}/${entry.filename}",
                    isDirectory = isDir,
                    size = if (isDir) 0L else entry.attributes.size,
                    lastModified = entry.attributes.modifyTime?.toMillis() ?: 0L
                )
            }.filter { it.name != "." && it.name != ".." }
        }
    }

    /**
     * For SCP shares: try SFTP subsystem first (Windows, modern Linux, most NAS).
     * If the server doesn't have SFTP enabled, fall back to `ls -la` shell parsing
     * (older routers, embedded BusyBox devices).
     */
    private fun listScpWithFallback(session: ClientSession, remotePath: String, displayPath: String): List<NetworkFile> {
        // Try SFTP subsystem first — most reliable and cross-platform
        val sftpResult = runCatching { listViaSftp(session, remotePath, displayPath) }
        if (sftpResult.isSuccess) {
            Log.d(TAG, "SCP listing via SFTP subsystem ✓")
            return sftpResult.getOrThrow()
        }

        Log.d(TAG, "SFTP unavailable, falling back to ls: ${sftpResult.exceptionOrNull()?.message}")

        // Fallback: parse Unix `ls -la` output — works on Linux, BusyBox (routers, NAS without SFTP)
        val out = java.io.ByteArrayOutputStream()
        val err = java.io.ByteArrayOutputStream()
        session.createExecChannel("ls -la \"$remotePath\"").use { channel ->
            channel.out = out
            channel.err = err
            channel.open().verify(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            channel.waitFor(listOf(org.apache.sshd.client.channel.ClientChannelEvent.CLOSED), TIMEOUT_SECONDS * 1_000)
        }

        val stderr = err.toString().trim()
        if (stderr.isNotEmpty()) Log.w(TAG, "ls stderr: $stderr")

        val stdout = out.toString()
        Log.d(TAG, "ls output (${stdout.lines().size} lines)")

        return stdout.lines()
            .filter { it.isNotBlank() && !it.startsWith("total") }
            .mapNotNull { parseLsLine(it, remotePath) }
            .filter { it.name != "." && it.name != ".." }
    }

    /**
     * Try an operation via SFTP subsystem; if that fails (server lacks SFTP),
     * run the fallback exec lambda instead.
     */
    private fun sftpOrExec(
        session: ClientSession,
        sftp: (org.apache.sshd.sftp.client.SftpClient) -> Unit,
        exec: (ClientSession) -> Unit
    ) {
        val ok = runCatching {
            SftpClientFactory.instance().createSftpClient(session).use(sftp)
        }.isSuccess
        if (!ok) exec(session)
    }

    /**
     * Parse a single line of `ls -la` output into a [NetworkFile].
     * Handles both full GNU ls and BusyBox ls output on routers.
     *
     * Format: `<perms> <links> <owner> <group> <size> <month> <day> <time/year> <name>`
     */
    private fun parseLsLine(line: String, parentPath: String): NetworkFile? {
        val parts = line.split(Regex("\\s+"))
        if (parts.size < 9) return null

        val permissions = parts[0]
        if (!permissions.matches(Regex("[dlbcps-][rwxts-]{9}.*"))) return null // not a valid perm string

        val isDir = permissions.startsWith("d")
        val isLink = permissions.startsWith("l")
        val size = parts[4].toLongOrNull() ?: 0L
        val rawName = parts.subList(8, parts.size).joinToString(" ")
        // Strip symlink target: "link -> target"
        val name = if (isLink) rawName.substringBefore(" -> ").trim() else rawName.trim()
        if (name.isEmpty() || name == "." || name == "..") return null

        return NetworkFile(
            name = name,
            path = "${parentPath.trimEnd('/')}/$name",
            isDirectory = isDir || isLink, // treat symlinks as dirs for navigability
            size = size,
            lastModified = 0L
        )
    }

    /**
     * Execute a shell command on the remote server WITHOUT using SSHD's built-in
     * ClientSession.executeRemoteCommand() which depends on java.rmi.RemoteException
     * (not available on Android). We create the exec channel directly instead.
     *
     * Throws [java.io.IOException] if the remote command exits with a non-zero status.
     */
    private fun runShellCommand(session: ClientSession, command: String): String {
        val out = java.io.ByteArrayOutputStream()
        val err = java.io.ByteArrayOutputStream()
        session.createExecChannel(command).use { channel ->
            channel.out = out
            channel.err = err
            channel.open().verify(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            channel.waitFor(listOf(org.apache.sshd.client.channel.ClientChannelEvent.CLOSED), TIMEOUT_SECONDS * 1_000)

            val exitCode = channel.exitStatus ?: 0
            if (exitCode != 0) {
                val errMsg = err.toString().trim().ifEmpty { out.toString().trim() }
                throw java.io.IOException("Shell command failed (exit $exitCode): ${errMsg.take(200)}")
            }
        }
        return out.toString()
    }
}
