package za.kilowatch.ultimatefilemanager.network

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Hardware-backed encrypted storage for per-device secrets
 * (Bearer auth tokens and TLS certificate fingerprints).
 *
 * Backed by [EncryptedSharedPreferences] using an AES-256-GCM master key
 * stored in the Android Keystore (TEE / StrongBox where available).
 *
 * Keys inside the encrypted prefs file:
 *   - "token_<deviceId>"       → authToken
 *   - "fingerprint_<deviceId>" → certFingerprint
 *
 * **Important — why we use `commit()` instead of `apply()` here:**
 * [EncryptedSharedPreferences] rewrites the entire encrypted file on every
 * write. Calling `apply()` posts that work to [android.app.QueuedWork]; the
 * framework then calls `QueuedWork.waitToFinish()` **on the main thread**
 * during `Activity.onPause()` / `onStop()`, blocking it for the full
 * `fdatasync()` duration → ANR. `commit()` is synchronous but never touches
 * QueuedWork, so the main thread is never blocked. All write methods in this
 * class must only be called from a worker thread (i.e. `Dispatchers.IO`).
 *
 * On rare OEM devices where keyset initialisation fails (known ESP bug),
 * the class logs the error and silently degrades to a no-op implementation
 * so the app never crashes. Secrets simply won't be persisted across
 * restarts in that edge case.
 */
class SecureTokenStore private constructor(private val prefs: SharedPreferences?) {

    // ── Key helpers ──────────────────────────────────────────────────────────

    private fun tokenKey(deviceId: String) = "token_$deviceId"
    private fun fingerprintKey(deviceId: String) = "fingerprint_$deviceId"

    // ── Public API ───────────────────────────────────────────────────────────

    fun getToken(deviceId: String): String? =
        prefs?.getString(tokenKey(deviceId), null)

    fun getFingerprint(deviceId: String): String? =
        prefs?.getString(fingerprintKey(deviceId), null)

    fun putToken(deviceId: String, token: String?) {
        val editor = prefs?.edit() ?: return
        if (token.isNullOrEmpty()) {
            editor.remove(tokenKey(deviceId))
        } else {
            editor.putString(tokenKey(deviceId), token)
        }
        editor.commit() // NOT apply() — see class KDoc for rationale
    }

    fun putFingerprint(deviceId: String, fingerprint: String?) {
        val editor = prefs?.edit() ?: return
        if (fingerprint.isNullOrEmpty()) {
            editor.remove(fingerprintKey(deviceId))
        } else {
            editor.putString(fingerprintKey(deviceId), fingerprint)
        }
        editor.commit() // NOT apply() — see class KDoc for rationale
    }

    /**
     * Puts both token and fingerprint in a single [SharedPreferences.Editor.apply] call.
     */
    fun put(deviceId: String, token: String?, fingerprint: String?) {
        val editor = prefs?.edit() ?: return
        if (token.isNullOrEmpty()) editor.remove(tokenKey(deviceId))
        else editor.putString(tokenKey(deviceId), token)

        if (fingerprint.isNullOrEmpty()) editor.remove(fingerprintKey(deviceId))
        else editor.putString(fingerprintKey(deviceId), fingerprint)

        editor.commit() // NOT apply() — see class KDoc for rationale
    }

    /** Remove all secrets for a device (call when unpairing). */
    fun remove(deviceId: String) {
        prefs?.edit()
            ?.remove(tokenKey(deviceId))
            ?.remove(fingerprintKey(deviceId))
            ?.commit() // NOT apply() — see class KDoc for rationale
    }

    // ── Factory ──────────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "SecureTokenStore"
        private const val PREFS_FILE = "ufm_secure_tokens"

        @Volatile
        private var INSTANCE: SecureTokenStore? = null

        fun getInstance(context: Context): SecureTokenStore {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: build(context.applicationContext).also { INSTANCE = it }
            }
        }

        private fun build(context: Context): SecureTokenStore {
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

                Log.d(TAG, "Encrypted token store initialised successfully")
                SecureTokenStore(encryptedPrefs)
            } catch (e: Exception) {
                // Rare OEM keyset-corruption or provisioning failure.
                // Degrade gracefully — secrets won't survive restarts but the
                // app won't crash.
                Log.e(TAG, "Failed to initialise EncryptedSharedPreferences — secrets will not be persisted", e)
                SecureTokenStore(null)
            }
        }
    }
}
