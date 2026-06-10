package za.kilowatch.ultimatefilemanager.server

import android.content.Context
import android.util.Log
import org.apache.ftpserver.FtpServer
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.ftplet.Authentication
import org.apache.ftpserver.ftplet.Authority
import org.apache.ftpserver.ftplet.AuthorizationRequest
import org.apache.ftpserver.ftplet.FtpException
import org.apache.ftpserver.ftplet.User
import org.apache.ftpserver.ftplet.UserManager
import org.apache.ftpserver.listener.ListenerFactory
import org.apache.ftpserver.usermanager.UsernamePasswordAuthentication
import org.apache.ftpserver.usermanager.impl.ConcurrentLoginPermission
import org.apache.ftpserver.usermanager.impl.TransferRatePermission
import org.apache.ftpserver.usermanager.impl.WritePermission
import java.util.concurrent.ConcurrentHashMap

/**
 * Embedded FTP server running on port 2121.
 *
 * Uses Apache FtpServer to host an FTP server with:
 * - Profile-based authentication via [FtpServerProfileRepository]
 * - Per-profile read-only enforcement
 * - Configurable default location per user
 *
 * Security notes:
 * - Anonymous access is NOT supported.
 * - H-1: Brute-force protection: after [MAX_FAILED_ATTEMPTS] consecutive failures
 *   the account is locked for [LOCKOUT_DURATION_MS] ms.
 * - H-2: FTP transmits data unencrypted. Use SFTP for sensitive data.
 * - M-4: Binds only to the Wi-Fi IP address supplied by [FileServerService].
 * - L-5: Max 3 concurrent sessions per user.
 */
class UfmFtpServer(private val context: Context) {

    companion object {
        const val TAG = "UfmFtpServer"
        const val PORT = 2121

        // H-1: Shared brute-force guard (keyed by username).
        private const val MAX_FAILED_ATTEMPTS = 5
        private const val LOCKOUT_DURATION_MS = 30_000L
        private val failedAttempts = ConcurrentHashMap<String, Pair<Int, Long>>()

        internal fun isLockedOut(username: String): Boolean {
            val (count, since) = failedAttempts[username] ?: return false
            if (count < MAX_FAILED_ATTEMPTS) return false
            if (System.currentTimeMillis() - since > LOCKOUT_DURATION_MS) {
                failedAttempts.remove(username)
                return false
            }
            return true
        }

        internal fun recordFailure(username: String) {
            val now = System.currentTimeMillis()
            val (count, _) = failedAttempts[username] ?: (0 to now)
            failedAttempts[username] = (count + 1) to now
        }

        internal fun resetFailures(username: String) {
            failedAttempts.remove(username)
        }
    }

    private var ftpServer: FtpServer? = null
    private val profileRepo = FtpServerProfileRepository.getInstance(context)

    val isRunning: Boolean get() = ftpServer?.isStopped == false

    /**
     * Starts the FTP server on port [PORT].
     *
     * @param bindAddress The local IP to bind to. Defaults to all interfaces if blank.
     */
    fun start(bindAddress: String = "") {
        if (isRunning) {
            Log.w(TAG, "FTP server already running")
            return
        }

        try {
            val factory = FtpServerFactory()

            // M-4: Bind only to the Wi-Fi address to avoid exposing the server on all
            // network interfaces (VPN tun, mobile hotspot, etc.).
            val listenerFactory = ListenerFactory()
            listenerFactory.port = PORT
            if (bindAddress.isNotEmpty() && bindAddress != "0.0.0.0") {
                listenerFactory.serverAddress = bindAddress
            }
            factory.addListener("default", listenerFactory.createListener())

            // Custom user manager backed by our profile repository
            factory.userManager = UfmUserManager(context, profileRepo)

            // Unified File System Factory for local and network storage
            factory.fileSystem = UfmFileSystemFactory(context)

            ftpServer = factory.createServer()
            ftpServer?.start()
            Log.i(TAG, "FTP server started on $bindAddress:$PORT")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start FTP server", e)
            throw e
        }
    }

    fun stop() {
        try {
            ftpServer?.stop()
            ftpServer = null
            Log.i(TAG, "FTP server stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping FTP server", e)
        }
    }
}

/**
 * Custom FTP UserManager that resolves users from [FtpServerProfileRepository].
 * Anonymous login is not permitted.
 */
private class UfmUserManager(
    private val context: Context,
    private val profileRepo: FtpServerProfileRepository,
) : UserManager {

    companion object {
        private const val TAG = "UfmUserManager"
    }

    override fun getUserByName(username: String?): User? {
        if (username == null) return null
        val profile = profileRepo.getByUsername(username) ?: return null
        return ProfileUser(context, profile)
    }

    override fun getAllUserNames(): Array<String> =
        profileRepo.getAll().map { it.username }.toTypedArray()

    override fun delete(username: String?) {
        // Not supported via FTP — manage profiles through the app UI
    }

    override fun save(user: User?) {
        // Not supported via FTP — manage profiles through the app UI
    }

    override fun doesExist(username: String?): Boolean {
        if (username == null) return false
        return profileRepo.getByUsername(username) != null
    }

    override fun authenticate(authentication: Authentication?): User? {
        when (authentication) {
            is UsernamePasswordAuthentication -> {
                val username = authentication.username ?: return null
                val password = authentication.password ?: return null

                // H-1: Reject locked-out accounts immediately.
                if (UfmFtpServer.isLockedOut(username)) {
                    Log.w(TAG, "Auth denied — account locked out: $username")
                    return null
                }

                if (profileRepo.validatePassword(username, password)) {
                    UfmFtpServer.resetFailures(username)
                    return getUserByName(username)
                } else {
                    UfmFtpServer.recordFailure(username)
                    Log.w(TAG, "Auth failed for: $username")
                    return null
                }
            }
            else -> return null   // Anonymous and all other types are rejected
        }
    }

    override fun getAdminName(): String = "admin"

    override fun isAdmin(username: String?): Boolean = false
}

/**
 * Wraps a [FtpServerProfile] as an Apache FtpServer [User].
 */
private class ProfileUser(
    private val context: Context,
    private val profile: FtpServerProfile
) : User {

    override fun getName(): String = profile.username

    override fun getPassword(): String = "" // Password is validated via our custom auth, not stored here

    override fun getAuthorities(): MutableList<out Authority> {
        val auths = mutableListOf<Authority>()
        // L-5: Reduced from 10 to 3 concurrent sessions to limit abuse.
        auths.add(ConcurrentLoginPermission(3, 3))
        auths.add(TransferRatePermission(0, 0))
        if (!profile.readOnly) {
            auths.add(WritePermission())
        }
        return auths
    }

    override fun getAuthorities(clazz: Class<out Authority>?): MutableList<out Authority> {
        return getAuthorities().filter { clazz?.isInstance(it) == true }.toMutableList()
    }

    override fun authorize(request: AuthorizationRequest?): AuthorizationRequest? {
        val auths = getAuthorities()
        for (auth in auths) {
            val result = auth.authorize(request)
            if (result != null) return result
        }
        return null
    }

    override fun getMaxIdleTime(): Int = 300

    override fun getEnabled(): Boolean = true

    override fun getHomeDirectory(): String = profile.defaultLocationUri
}
