package za.kilowatch.ultimatefilemanager.network

data class PairedDevice(
    val deviceId: String,
    var name: String,
    var lastIp: String,
    var lastPort: Int,
    var isConnected: Boolean = false,
    val isTv: Boolean = false,
    /** Set to true when the user explicitly presses Disconnect. Prevents onResume auto-reconnect. */
    var manuallyDisconnected: Boolean = false,
    /**
     * SHA-256 fingerprint of the device's TLS certificate, captured at pairing time.
     * NOT serialised in SharedPreferences CSV — stored encrypted via [SecureTokenStore].
     */
    var certFingerprint: String? = null,
    /**
     * Cryptographic Bearer token for securing endpoints.
     * NOT serialised in SharedPreferences CSV — stored encrypted via [SecureTokenStore].
     */
    var authToken: String? = null
) {
    /**
     * Serialises non-secret device metadata to a CSV string for plain SharedPreferences.
     * Secrets (authToken, certFingerprint) are stored separately in [SecureTokenStore].
     */
    fun toSharedPrefsString(): String {
        return "$deviceId,$name,$lastIp,$lastPort,$isConnected,$isTv,$manuallyDisconnected"
    }

    companion object {
        /**
         * Deserialises non-secret device metadata from a CSV string.
         * Secrets (authToken, certFingerprint) are populated separately by
         * [PairingManager] from [SecureTokenStore] after calling this method.
         *
         * Backward-compatible: old 9-field strings (with plaintext token/fingerprint
         * in columns 8 and 9) are still parsed correctly — the extra columns are
         * ignored here and handled by [PairingManager.migrateSecretsIfNeeded].
         */
        fun fromSharedPrefsString(data: String): PairedDevice? {
            val parts = data.split(",")
            if (parts.size >= 6) {
                return try {
                    PairedDevice(
                        deviceId = parts[0],
                        name = parts[1],
                        lastIp = parts[2],
                        lastPort = parts[3].toInt(),
                        isConnected = parts[4].toBoolean(),
                        isTv = parts[5].toBoolean(),
                        manuallyDisconnected = if (parts.size >= 7) parts[6].toBoolean() else false
                        // authToken and certFingerprint are intentionally omitted here.
                        // PairingManager hydrates them from SecureTokenStore.
                    )
                } catch (e: Exception) {
                    null
                }
            }
            return null
        }
    }
}
