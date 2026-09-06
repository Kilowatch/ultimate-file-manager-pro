package za.kilowatch.ultimatefilemanager.security

import android.app.KeyguardManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.BuildConfig
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Enterprise-grade Security & App Lock manager for Mobile devices.
 *
 * Backed by [EncryptedSharedPreferences] using an AES-256-GCM master key
 * stored in the Android Keystore (TEE / StrongBox where available).
 *
 * Credential hashes and recovery keys are salted and hashed using
 * PBKDF2WithHmacSHA256 with 260,000 rounds and verified in constant-time.
 */
class AppSecurityManager private constructor(private val prefs: SharedPreferences?) {

    enum class BiometricStatus {
        AVAILABLE,
        NONE_ENROLLED,
        UNAVAILABLE
    }

    // In-memory session state (resets on process termination)
    @Volatile
    var isSessionUnlocked: Boolean = false
        private set

    @Volatile
    private var lastBackgroundTimestamp: Long = 0L

    @Volatile
    private var isAppInBackground: Boolean = false

    companion object {
        private const val TAG = "AppSecurityManager"
        private const val PREFS_FILE = "ufm_security_prefs"

        private const val KEY_ENABLED = "sec_enabled"
        private const val KEY_MODE = "sec_mode"
        private const val KEY_CREDENTIAL_HASH = "sec_credential_hash"
        private const val KEY_RECOVERY_HASH = "sec_recovery_hash"
        private const val KEY_LOCK_TIMEOUT = "sec_lock_timeout"
        private const val KEY_FAILED_ATTEMPTS = "sec_failed_attempts"
        private const val KEY_LOCKOUT_UNTIL = "sec_lockout_until"

        // Cryptographic parameters matching VaultActivity
        private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
        private const val PBKDF2_ITERATIONS = 260_000
        private const val PBKDF2_KEY_LENGTH = 256
        private const val SALT_SIZE = 16

        // 16-character alphanumeric character pool for recovery keys
        private const val RECOVERY_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

        // Rate limiting constants
        const val MAX_FAILED_ATTEMPTS = 5
        const val LOCKOUT_DURATION_MS = 30_000L // 30 seconds

        // Lock timeout constants
        const val TIMEOUT_FRESH_OPEN_ONLY = -1L
        const val TIMEOUT_IMMEDIATELY = 0L
        const val TIMEOUT_ONE_MINUTE = 60_000L
        const val TIMEOUT_FIVE_MINUTES = 300_000L

        @Volatile
        private var INSTANCE: AppSecurityManager? = null

        fun getInstance(context: Context): AppSecurityManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: build(context.applicationContext).also { INSTANCE = it }
            }
        }

        private fun build(context: Context): AppSecurityManager {
            return try {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()

                val encryptedPrefs = EncryptedSharedPreferences.create(
                    context,
                    PREFS_FILE,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
                if (BuildConfig.DEBUG) Log.d(TAG, "Encrypted security prefs initialized successfully")
                AppSecurityManager(encryptedPrefs)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize EncryptedSharedPreferences for App Security", e)
                AppSecurityManager(null)
            }
        }
    }

    // ── Preference Getters ───────────────────────────────────────────────────

    fun isSecurityEnabled(): Boolean {
        return prefs?.getBoolean(KEY_ENABLED, false) ?: false
    }

    fun getSecurityMode(): SecurityMode {
        val raw = prefs?.getString(KEY_MODE, SecurityMode.NONE.key)
        return SecurityMode.fromKey(raw)
    }

    fun getLockTimeout(): Long {
        return prefs?.getLong(KEY_LOCK_TIMEOUT, TIMEOUT_FRESH_OPEN_ONLY) ?: TIMEOUT_FRESH_OPEN_ONLY
    }

    fun hasStoredCredential(): Boolean {
        return !prefs?.getString(KEY_CREDENTIAL_HASH, null).isNullOrEmpty()
    }

    fun hasRecoveryKey(): Boolean {
        return !prefs?.getString(KEY_RECOVERY_HASH, null).isNullOrEmpty()
    }

    // ── Session & Lifecycle Lock Gating ─────────────────────────────────────

    fun setSessionUnlocked(unlocked: Boolean) {
        isSessionUnlocked = unlocked
        isAppInBackground = false
        lastBackgroundTimestamp = 0L
        if (unlocked) {
            resetFailedAttempts()
        }
    }

    fun onAppEnteredBackground() {
        isAppInBackground = true
        lastBackgroundTimestamp = SystemClock.elapsedRealtime()
    }

    fun shouldPromptLock(context: Context): Boolean {
        if (DeviceUtils.isTvDevice(context)) return false
        if (!isSecurityEnabled()) return false
        if (getSecurityMode() == SecurityMode.NONE) return false

        // If session has not been unlocked yet (e.g. cold start / fresh open), always prompt lock
        if (!isSessionUnlocked) return true

        val timeout = getLockTimeout()
        if (timeout == TIMEOUT_FRESH_OPEN_ONLY) {
            // Unlocked for the duration of this app process session
            return false
        }

        // If the app has not entered the background since being unlocked, do not prompt
        if (!isAppInBackground || lastBackgroundTimestamp == 0L) {
            return false
        }

        val elapsed = SystemClock.elapsedRealtime() - lastBackgroundTimestamp
        val needsLock = if (timeout == TIMEOUT_IMMEDIATELY) {
            true
        } else {
            elapsed > timeout
        }

        if (!needsLock) {
            // User returned before timeout expired: clear background state so internal
            // in-app navigation does not trigger a lock later
            isAppInBackground = false
            lastBackgroundTimestamp = 0L
        }

        return needsLock
    }

    // ── Rate Limiting & Cooldown ─────────────────────────────────────────────

    fun getFailedAttempts(): Int {
        return prefs?.getInt(KEY_FAILED_ATTEMPTS, 0) ?: 0
    }

    fun getRemainingLockoutSeconds(): Long {
        val lockoutUntil = prefs?.getLong(KEY_LOCKOUT_UNTIL, 0L) ?: 0L
        val now = System.currentTimeMillis()
        return if (lockoutUntil > now) (lockoutUntil - now + 999L) / 1000L else 0L
    }

    fun isLockedOut(): Boolean {
        return getRemainingLockoutSeconds() > 0L
    }

    fun registerFailedAttempt(): Int {
        val current = getFailedAttempts() + 1
        val editor = prefs?.edit() ?: return current
        editor.putInt(KEY_FAILED_ATTEMPTS, current)
        if (current >= MAX_FAILED_ATTEMPTS) {
            val lockoutUntil = System.currentTimeMillis() + LOCKOUT_DURATION_MS
            editor.putLong(KEY_LOCKOUT_UNTIL, lockoutUntil)
        }
        editor.commit() // Commit on background thread or caller
        return current
    }

    fun resetFailedAttempts() {
        prefs?.edit()
            ?.putInt(KEY_FAILED_ATTEMPTS, 0)
            ?.putLong(KEY_LOCKOUT_UNTIL, 0L)
            ?.commit()
    }

    // ── Biometric Capability Check ──────────────────────────────────────────

    fun checkBiometricStatus(context: Context): BiometricStatus {
        val biometricManager = BiometricManager.from(context)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            when (biometricManager.canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)) {
                BiometricManager.BIOMETRIC_SUCCESS -> BiometricStatus.AVAILABLE
                BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricStatus.NONE_ENROLLED
                else -> BiometricStatus.UNAVAILABLE
            }
        } else {
            val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            val isDeviceSecure = keyguardManager?.isDeviceSecure == true
            val status = biometricManager.canAuthenticate(BIOMETRIC_STRONG)
            if (isDeviceSecure || status == BiometricManager.BIOMETRIC_SUCCESS) {
                BiometricStatus.AVAILABLE
            } else if (status == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED) {
                BiometricStatus.NONE_ENROLLED
            } else {
                BiometricStatus.UNAVAILABLE
            }
        }
    }

    // ── Cryptographic PBKDF2 Operations ──────────────────────────────────────

    suspend fun hashCredential(credential: String): String = withContext(Dispatchers.Default) {
        val salt = ByteArray(SALT_SIZE).apply { SecureRandom().nextBytes(this) }
        val spec = PBEKeySpec(credential.toCharArray(), salt, PBKDF2_ITERATIONS, PBKDF2_KEY_LENGTH)
        val key = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM).generateSecret(spec).encoded
        val saltHex = salt.joinToString("") { "%02x".format(it) }
        val hashHex = key.joinToString("") { "%02x".format(it) }
        "$PBKDF2_ITERATIONS:$saltHex:$hashHex"
    }

    suspend fun verifyCredential(credential: String, storedHash: String): Boolean = withContext(Dispatchers.Default) {
        val parts = storedHash.split(":")
        if (parts.size != 3) return@withContext false
        val iterations = parts[0].toIntOrNull() ?: return@withContext false
        val salt = parts[1].hexToByteArray() ?: return@withContext false
        val expectedHash = parts[2]
        val spec = PBEKeySpec(credential.toCharArray(), salt, iterations, PBKDF2_KEY_LENGTH)
        val key = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM).generateSecret(spec).encoded
        val computedHash = key.joinToString("") { "%02x".format(it) }
        MessageDigest.isEqual(
            computedHash.toByteArray(Charsets.UTF_8),
            expectedHash.toByteArray(Charsets.UTF_8)
        )
    }

    fun generateRecoveryKey(): String {
        return (1..16).map { RECOVERY_CHARS.random() }.joinToString("")
    }

    suspend fun verifyStoredCredential(credential: String): Boolean {
        val stored = prefs?.getString(KEY_CREDENTIAL_HASH, null) ?: return false
        return verifyCredential(credential, stored)
    }

    suspend fun verifyRecoveryKey(enteredKey: String): Boolean {
        val stored = prefs?.getString(KEY_RECOVERY_HASH, null) ?: return false
        val cleanKey = enteredKey.replace("-", "").trim()
        return verifyCredential(cleanKey, stored)
    }

    // ── Save & Clear Operations ──────────────────────────────────────────────

    suspend fun savePin(pin: String, recoveryKey: String) = withContext(Dispatchers.IO) {
        val credHash = hashCredential(pin)
        val cleanRecovery = recoveryKey.replace("-", "").trim()
        val recHash = hashCredential(cleanRecovery)

        prefs?.edit()
            ?.putBoolean(KEY_ENABLED, true)
            ?.putString(KEY_MODE, SecurityMode.PIN.key)
            ?.putString(KEY_CREDENTIAL_HASH, credHash)
            ?.putString(KEY_RECOVERY_HASH, recHash)
            ?.putInt(KEY_FAILED_ATTEMPTS, 0)
            ?.putLong(KEY_LOCKOUT_UNTIL, 0L)
            ?.commit()

        setSessionUnlocked(true)
    }

    suspend fun savePassword(password: String, recoveryKey: String) = withContext(Dispatchers.IO) {
        val credHash = hashCredential(password)
        val cleanRecovery = recoveryKey.replace("-", "").trim()
        val recHash = hashCredential(cleanRecovery)

        prefs?.edit()
            ?.putBoolean(KEY_ENABLED, true)
            ?.putString(KEY_MODE, SecurityMode.PASSWORD.key)
            ?.putString(KEY_CREDENTIAL_HASH, credHash)
            ?.putString(KEY_RECOVERY_HASH, recHash)
            ?.putInt(KEY_FAILED_ATTEMPTS, 0)
            ?.putLong(KEY_LOCKOUT_UNTIL, 0L)
            ?.commit()

        setSessionUnlocked(true)
    }

    suspend fun saveBiometricMode() = withContext(Dispatchers.IO) {
        prefs?.edit()
            ?.putBoolean(KEY_ENABLED, true)
            ?.putString(KEY_MODE, SecurityMode.BIOMETRIC.key)
            ?.remove(KEY_CREDENTIAL_HASH)
            ?.remove(KEY_RECOVERY_HASH)
            ?.putInt(KEY_FAILED_ATTEMPTS, 0)
            ?.putLong(KEY_LOCKOUT_UNTIL, 0L)
            ?.commit()

        setSessionUnlocked(true)
    }

    suspend fun setLockTimeout(timeoutMs: Long) = withContext(Dispatchers.IO) {
        prefs?.edit()?.putLong(KEY_LOCK_TIMEOUT, timeoutMs)?.commit()
    }

    suspend fun clearAllCredentials() = withContext(Dispatchers.IO) {
        prefs?.edit()
            ?.putBoolean(KEY_ENABLED, false)
            ?.putString(KEY_MODE, SecurityMode.NONE.key)
            ?.remove(KEY_CREDENTIAL_HASH)
            ?.remove(KEY_RECOVERY_HASH)
            ?.putInt(KEY_FAILED_ATTEMPTS, 0)
            ?.putLong(KEY_LOCKOUT_UNTIL, 0L)
            ?.commit()

        setSessionUnlocked(false)
    }

    // ── Helper extensions ────────────────────────────────────────────────────

    private fun String.hexToByteArray(): ByteArray? {
        if (length % 2 != 0) return null
        return try {
            chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        } catch (_: Exception) {
            null
        }
    }
}
