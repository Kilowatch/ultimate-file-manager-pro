package za.kilowatch.ultimatefilemanager.settings

import android.content.Context
import java.util.regex.Pattern

/**
 * Manages persistence and validation for the last used ADB target IP and port.
 *
 * SEC-POLICY:
 * 1. Network parameters (host and port) are stored in private SharedPreferences (MODE_PRIVATE).
 * 2. Input values are sanitized and validated against strict address and port ranges before saving.
 * 3. CRITICAL: 6-digit Wi-Fi Pairing PIN codes MUST NEVER be persisted to disk. Pairing codes
 *    are one-time ephemeral credentials that remain solely in transient memory during the handshake.
 */
object AdbPreferenceManager {

    const val PREFS_NAME = "adb_preferences"
    private const val KEY_LAST_TARGET_HOST = "adb_last_target_host"
    private const val KEY_LAST_TARGET_PORT = "adb_last_target_port"

    const val DEFAULT_HOST = "127.0.0.1"
    const val DEFAULT_PORT = 5555

    // Pattern for valid IPv4 addresses (0.0.0.0 to 255.255.255.255)
    private val IPV4_PATTERN = Pattern.compile(
        "^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$"
    )

    // Pattern for valid hostnames (RFC 1123 compliant label structure, no spaces/newlines)
    private val HOSTNAME_PATTERN = Pattern.compile(
        "^([a-zA-Z0-9]|[a-zA-Z0-9][a-zA-Z0-9\\-]*[a-zA-Z0-9])(\\.[a-zA-Z0-9]|[a-zA-Z0-9][a-zA-Z0-9\\-]*[a-zA-Z0-9])*$"
    )

    /**
     * Validates whether the given string is a safe, valid IPv4 address or hostname.
     * Rejects control characters, newlines, semicolons, or command injection attempts.
     */
    fun isValidHost(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val trimmed = host.trim()
        if (trimmed == "localhost") return true
        return IPV4_PATTERN.matcher(trimmed).matches() || HOSTNAME_PATTERN.matcher(trimmed).matches()
    }

    /**
     * Validates whether the given port is within the valid TCP port range (1 to 65535).
     */
    fun isValidPort(port: Int): Boolean {
        return port in 1..65535
    }

    /**
     * Validates a port string.
     */
    fun isValidPort(portStr: String?): Boolean {
        val p = portStr?.trim()?.toIntOrNull() ?: return false
        return isValidPort(p)
    }

    /**
     * Returns the last used target host, or [DEFAULT_HOST] if none is saved or if corrupted.
     */
    fun getLastTargetHost(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val host = prefs.getString(KEY_LAST_TARGET_HOST, DEFAULT_HOST)?.trim() ?: DEFAULT_HOST
        return if (isValidHost(host)) host else DEFAULT_HOST
    }

    /**
     * Returns the last used target port, or [DEFAULT_PORT] if none is saved or out of range.
     */
    fun getLastTargetPort(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val port = prefs.getInt(KEY_LAST_TARGET_PORT, DEFAULT_PORT)
        return if (isValidPort(port)) port else DEFAULT_PORT
    }

    /**
     * Persists the last used target host and port after validation.
     */
    fun saveTarget(context: Context, host: String, port: Int) {
        val trimmedHost = host.trim()
        if (!isValidHost(trimmedHost) || !isValidPort(port)) return

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_TARGET_HOST, trimmedHost)
            .putInt(KEY_LAST_TARGET_PORT, port)
            .apply()
    }

    /**
     * Clears persisted ADB target host and port.
     */
    fun clearTarget(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }
}
