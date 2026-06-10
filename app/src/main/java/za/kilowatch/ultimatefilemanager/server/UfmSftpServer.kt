package za.kilowatch.ultimatefilemanager.server

import android.content.Context
import android.util.Base64
import android.util.Log
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import net.i2p.crypto.eddsa.EdDSAPublicKey
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.PublicKey
import java.security.interfaces.DSAPublicKey
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.DSAPublicKeySpec
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.RSAPublicKeySpec
import org.apache.sshd.server.session.ServerSession
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.apache.sshd.sftp.server.SftpFileSystemAccessor
import org.apache.sshd.sftp.server.SftpSubsystemProxy
import java.io.File
import java.nio.channels.SeekableByteChannel
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.attribute.FileAttribute
import java.util.concurrent.ConcurrentHashMap

/**
 * Embedded SFTP server running on port 2222.
 *
 * Uses Apache SSHD to host an SFTP server.
 * Each user's home directory is resolved from their [FtpServerProfile.defaultLocationUri]
 * and chrooted so users can only access their configured directory.
 *
 * Security notes:
 * - Anonymous access is NOT supported.
 * - H-1: Brute-force protection: after [MAX_FAILED_ATTEMPTS] consecutive failures per username
 *   (tracked by session remote address) the authenticator rejects for [LOCKOUT_DURATION_MS] ms.
 * - M-4: Binds only to the Wi-Fi IP address supplied by [FileServerService].
 * - L-3: Host key is regenerated as a proper RSA key via [SimpleGeneratorHostKeyProvider];
 *   the .ser serialization format is a library detail outside our control.
 */
class UfmSftpServer(private val context: Context) {

    companion object {
        const val TAG = "UfmSftpServer"
        const val PORT = 2222

        // H-1: Shared brute-force guard (keyed by "username@remoteIp").
        private const val MAX_FAILED_ATTEMPTS = 5
        private const val LOCKOUT_DURATION_MS = 30_000L
        private val failedAttempts = ConcurrentHashMap<String, Pair<Int, Long>>()

        private fun lockoutKey(username: String, session: ServerSession): String {
            val ip = try { session.remoteAddress?.toString() ?: "unknown" } catch (_: Exception) { "unknown" }
            return "$username@$ip"
        }

        private fun isLockedOut(key: String): Boolean {
            val (count, since) = failedAttempts[key] ?: return false
            if (count < MAX_FAILED_ATTEMPTS) return false
            if (System.currentTimeMillis() - since > LOCKOUT_DURATION_MS) {
                failedAttempts.remove(key)
                return false
            }
            return true
        }

        private fun recordFailure(key: String) {
            val now = System.currentTimeMillis()
            val (count, _) = failedAttempts[key] ?: (0 to now)
            failedAttempts[key] = (count + 1) to now
        }

        private fun resetFailures(key: String) {
            failedAttempts.remove(key)
        }

        /** Decodes an SSH public key from OpenSSH authorized_keys format (e.g. "ssh-rsa AAAA..."). */
        private fun decodeSshPublicKey(line: String): PublicKey? = runCatching {
            val parts = line.split(" ")
            if (parts.size < 2) { Log.w(TAG, "decode: line has <2 parts: ${line.take(40)}"); return@runCatching null }
            DataInputStream(ByteArrayInputStream(Base64.decode(parts[1], Base64.DEFAULT))).use { dis ->
                fun readStr(): String {
                    val b = ByteArray(dis.readInt()); dis.readFully(b); return String(b, Charsets.UTF_8)
                }
                fun readMpInt(): BigInteger {
                    val b = ByteArray(dis.readInt()); dis.readFully(b); return BigInteger(b)
                }
                val algorithm = readStr()
                Log.d(TAG, "decode: detected algorithm '$algorithm' from line: ${line.take(40)}...")
                when (algorithm) {
                    "ssh-rsa" -> {
                        // SSH wire: string "ssh-rsa", mpint e, mpint n
                        val e = readMpInt(); val n = readMpInt()
                        Log.d(TAG, "decode: RSA e bits=${e.bitLength()} n bits=${n.bitLength()}")
                        KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(n, e))
                    }
                    "ssh-dss" -> {
                        // SSH wire: string "ssh-dss", mpint p, mpint q, mpint g, mpint y
                        val p = readMpInt(); val q = readMpInt(); val g = readMpInt(); val y = readMpInt()
                        KeyFactory.getInstance("DSA").generatePublic(DSAPublicKeySpec(y, p, q, g))
                    }
                    "ssh-ed25519", "sk-ssh-ed25519@openssh.com" -> {
                        val keyLen = dis.readInt()
                        val keyBytes = ByteArray(keyLen)
                        dis.readFully(keyBytes)
                        Log.d(TAG, "decode: Ed25519 keyLen=$keyLen")
                        EdDSAPublicKey(EdDSAPublicKeySpec(keyBytes, EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519)))
                    }
                    "ecdsa-sha2-nistp256", "ecdsa-sha2-nistp384", "ecdsa-sha2-nistp521",
                    "sk-ecdsa-sha2-nistp256@openssh.com" -> {
                        /* SSH wire: string "ecdsa-sha2-*", string curve_name, string key_bytes.
                           key_bytes is an uncompressed EC point (0x04 || x || y). */
                        val curveName = readStr()
                        val keyLen = dis.readInt()
                        val keyData = ByteArray(keyLen)
                        dis.readFully(keyData)
                        // Strip leading 0x04 uncompressed marker
                        val pointLen = (keyData.size - 1) / 2
                        val x = BigInteger(1, keyData.copyOfRange(1, 1 + pointLen))
                        val y = BigInteger(1, keyData.copyOfRange(1 + pointLen, keyData.size))
                        val javaCurve = mapOf(
                            "nistp256" to "secp256r1",
                            "nistp384" to "secp384r1",
                            "nistp521" to "secp521r1"
                        )[curveName] ?: return@use null
                        val params = AlgorithmParameters.getInstance("EC").apply {
                            init(ECGenParameterSpec(javaCurve))
                        }.getParameterSpec(ECParameterSpec::class.java)
                        KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), params))
                    }
                    else -> { Log.w(TAG, "Unsupported SSH key algorithm"); null }
                }
            }
        }.getOrElse {
            Log.w(TAG, "Failed to decode SSH public key", it)
            null
        }

        /** Compares two PublicKey objects by their actual key material, not by implementation class. */
        private fun keysMatch(a: PublicKey?, b: PublicKey): Boolean {
            if (a == null) return false
            if (a is RSAPublicKey && b is RSAPublicKey) {
                return a.modulus == b.modulus && a.publicExponent == b.publicExponent
            }
            if (a is DSAPublicKey && b is DSAPublicKey) {
                return a.y == b.y && a.params.p == b.params.p && a.params.q == b.params.q && a.params.g == b.params.g
            }
            if (a is EdDSAPublicKey && b is EdDSAPublicKey) {
                return a.a.equals(b.a) || (a.encoded?.contentEquals(b.encoded ?: ByteArray(0)) == true)
            }
            if (a is ECPublicKey && b is ECPublicKey) {
                return a.w.affineX == b.w.affineX && a.w.affineY == b.w.affineY &&
                       a.params.curve.field.fieldSize == b.params.curve.field.fieldSize
            }
            // Fallback: compare encoded form
            val aEnc = a.encoded ?: return false
            val bEnc = b.encoded ?: return false
            return aEnc.contentEquals(bEnc)
        }
    }

    private var sshServer: SshServer? = null
    private val profileRepo = FtpServerProfileRepository.getInstance(context)

    val isRunning: Boolean get() = sshServer?.isStarted == true

    /**
     * Starts the SFTP server on port [PORT].
     *
     * @param bindAddress The local IP to bind to. Defaults to all interfaces if blank.
     */
    fun start(bindAddress: String = "") {
        if (isRunning) {
            Log.w(TAG, "SFTP server already running")
            return
        }

        try {
            val server = SshServer.setUpDefaultServer()
            server.port = PORT

            // M-4: Bind only to the Wi-Fi address.
            if (bindAddress.isNotEmpty() && bindAddress != "0.0.0.0") {
                server.host = bindAddress
            }

            // L-3: Host key regenerated on first start and persisted between restarts.
            // SimpleGeneratorHostKeyProvider serialises the key using the library's own
            // format — not a gadget-chain risk because we only load what we write.
            val hostKeyFile = File(context.filesDir, "sftp_host_key.ser").toPath()
            server.keyPairProvider = SimpleGeneratorHostKeyProvider(hostKeyFile)

            // H-1: Password authenticator with per-user brute-force protection.
            server.passwordAuthenticator = PasswordAuthenticator { username, password, session ->
                if (username == null || password == null) return@PasswordAuthenticator false

                val key = lockoutKey(username, session as ServerSession)
                if (isLockedOut(key)) {
                    Log.w(TAG, "Auth denied — locked out: $username")
                    return@PasswordAuthenticator false
                }

                // Anonymous login is explicitly rejected.
                if (username.equals("anonymous", ignoreCase = true)) {
                    Log.w(TAG, "Auth denied — anonymous login not permitted")
                    return@PasswordAuthenticator false
                }

                val ok = profileRepo.validatePassword(username, password)
                if (ok) {
                    resetFailures(key)
                    // Store username so VirtualFileSystemFactory can resolve it
                    session.properties["auth.username"] = username
                } else {
                    recordFailure(key)
                    Log.w(TAG, "Auth failed for: $username")
                }
                ok
            }

            // Public key authenticator — accepts keys listed in the profile's authorizedKeys field.
            server.publickeyAuthenticator = PublickeyAuthenticator { username, key, session ->
                try {
                    Log.e(TAG, "!!! PUBKEY AUTH: username='$username' algo=${key?.algorithm}")
                    if (username == null) { Log.e(TAG, "Auth denied — null username"); return@PublickeyAuthenticator false }

                    val lockKey = lockoutKey(username, session as ServerSession)
                    if (isLockedOut(lockKey)) {
                        Log.w(TAG, "Pubkey auth denied — locked out: $username")
                        return@PublickeyAuthenticator false
                    }

                    val profile = profileRepo.getByUsername(username)
                    if (profile == null) { Log.e(TAG, "Auth denied — no profile for: $username"); recordFailure(lockKey); return@PublickeyAuthenticator false }
                    if (profile.authorizedKeys.isBlank()) { Log.e(TAG, "Auth denied — no authorized keys for: $username"); recordFailure(lockKey); return@PublickeyAuthenticator false }
                    val ok = profile.authorizedKeys.lineSequence()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() && !it.startsWith("#") }
                        .any { line ->
                            val decoded = decodeSshPublicKey(line)
                            if (decoded == null) { Log.e(TAG, "decode FAILED for line: ${line.take(60)}..."); false }
                            else {
                                val byEquals = decoded == key
                                val byMaterial = keysMatch(decoded, key)
                                Log.e(TAG, "  equals=$byEquals keysMatch=$byMaterial decoded=${decoded::class.simpleName} session=${key::class.simpleName}")
                                byEquals || byMaterial
                            }
                        }
                    Log.e(TAG, "Result for '$username': $ok")
                    if (ok) {
                        resetFailures(lockKey)
                        (session as ServerSession).properties["auth.username"] = username
                    } else {
                        recordFailure(lockKey)
                    }
                    ok
                } catch (e: Throwable) {
                    Log.e(TAG, "!!! CRASH in pubkey auth: ${e.message}", e)
                    false
                }
            }

            // ── Throughput tuning ────────────────────────────────────────────────────
            // SFTP-level max packet size (read and write data payloads)
            val maxPacket = 256 * 1024   // 256 KB per SFTP data packet
            server.properties["sftp-max-read-data-packet-length"]  = maxPacket
            server.properties["sftp-max-write-data-packet-length"] = maxPacket

            // SSH transport-level channel window
            val windowSize   = 16 * 1024 * 1024  // 16 MB
            val maxSshPacket = 256 * 1024         // 256 KB per SSH transport packet
            server.properties["window-size"]    = windowSize
            server.properties["max-packet-size"] = maxSshPacket

            val sftpFactory = SftpSubsystemFactory()
            sftpFactory.fileSystemAccessor = object : SftpFileSystemAccessor {
                override fun openFile(
                    subsystem: SftpSubsystemProxy,
                    handle: org.apache.sshd.sftp.server.FileHandle?,
                    path: Path,
                    fileId: String,
                    options: MutableSet<out OpenOption>,
                    vararg attrs: FileAttribute<*>
                ): SeekableByteChannel {
                    return path.fileSystem.provider().newByteChannel(path, options)
                }
            }
            server.subsystemFactories = listOf(sftpFactory)

            // Bridged File System Factory for local and network storage
            server.fileSystemFactory = UfmSftpFileSystemFactory(context)

            server.start()
            sshServer = server
            Log.i(TAG, "SFTP server started on $bindAddress:$PORT")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start SFTP server", e)
            throw e
        }
    }

    fun stop() {
        try {
            sshServer?.stop(true)
            sshServer = null
            Log.i(TAG, "SFTP server stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping SFTP server", e)
        }
    }
}
