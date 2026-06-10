package za.kilowatch.ultimatefilemanager.remote

import android.content.Context
import android.content.SharedPreferences

/**
 * Thin wrapper around SharedPreferences for per-TV transport preference
 * and "Use Remote" toggle state.
 *
 * For Bluetooth TVs, [deviceKey] is the Bluetooth MAC address.
 * For ADB/paired TVs, [deviceKey] is [za.kilowatch.ultimatefilemanager.network.PairedDevice.deviceId].
 */
class RemoteTransportPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Per-TV Last-Used Transport ────────────────────────────────────────────

    /**
     * Returns the last-used transport for a given TV, or null if never connected.
     * @return `"bluetooth"`, `"adb_wifi"`, or `null`
     */
    fun getLastTransport(deviceKey: String): String? {
        return prefs.getString(lastTransportKey(deviceKey), null)
    }

    /**
     * Persists the transport type used for a successful connection.
     * @param transport `"bluetooth"` or `"adb_wifi"`
     */
    fun setLastTransport(deviceKey: String, transport: String) {
        prefs.edit().putString(lastTransportKey(deviceKey), transport).apply()
    }

    /** Clears the last-used transport preference for a TV (called when TV is removed). */
    fun clearLastTransport(deviceKey: String) {
        prefs.edit().remove(lastTransportKey(deviceKey)).apply()
    }

    // ── "Use Remote" Toggle ───────────────────────────────────────────────────

    /** True if the paired TV has "Use Remote" enabled. */
    fun isRemoteEnabled(pairedDeviceId: String): Boolean {
        return getRemoteEnabledDeviceIds().contains(pairedDeviceId)
    }

    /** Enable or disable "Use Remote" for a given paired TV. */
    fun setRemoteEnabled(pairedDeviceId: String, enabled: Boolean) {
        val ids = getRemoteEnabledDeviceIds().toMutableSet()
        if (enabled) ids.add(pairedDeviceId) else ids.remove(pairedDeviceId)
        prefs.edit().putStringSet(KEY_REMOTE_ENABLED_IDS, ids).apply()
    }

    /** Returns the set of paired device IDs that have "Use Remote" enabled. */
    fun getRemoteEnabledDeviceIds(): Set<String> {
        return prefs.getStringSet(KEY_REMOTE_ENABLED_IDS, emptySet()) ?: emptySet()
    }

    // ── Last Connected Remote Device (for disconnected card) ───────────────────

    /** Save the device that was last selected and connected from the remote screen. */
    fun setLastConnectedRemoteDevice(deviceId: String, name: String, transport: String) {
        prefs.edit()
            .putString(KEY_LAST_REMOTE_DEVICE_ID, deviceId)
            .putString(KEY_LAST_REMOTE_DEVICE_NAME, name)
            .putString(KEY_LAST_REMOTE_TRANSPORT, transport)
            .apply()
    }

    /** The last connected remote device ID, or null. */
    fun getLastConnectedRemoteDeviceId(): String? {
        return prefs.getString(KEY_LAST_REMOTE_DEVICE_ID, null)
    }

    /** The last connected remote device name, or null. */
    fun getLastConnectedRemoteDeviceName(): String? {
        return prefs.getString(KEY_LAST_REMOTE_DEVICE_NAME, null)
    }

    /** The transport used for the last connected remote device. */
    fun getLastConnectedRemoteTransport(): String? {
        return prefs.getString(KEY_LAST_REMOTE_TRANSPORT, null)
    }

    /** Clear the last connected remote device (called on explicit disconnect if desired). */
    fun clearLastConnectedRemoteDevice() {
        prefs.edit()
            .remove(KEY_LAST_REMOTE_DEVICE_ID)
            .remove(KEY_LAST_REMOTE_DEVICE_NAME)
            .remove(KEY_LAST_REMOTE_TRANSPORT)
            .apply()
    }

    // ── Manual Devices (IP-based, user-added) ──────────────────────────────────

    /** Add a manually-entered device. Returns false if the IP already exists. */
    fun addManualDevice(device: ManualDevice): Boolean {
        if (isIpInManualList(device.ip)) return false
        val devices = getManualDevicesCsv().toMutableSet()
        devices.add(device.toCsv())
        prefs.edit().putStringSet(KEY_MANUAL_DEVICES, devices).apply()
        return true
    }

    /** Remove a manually-entered device by ID. */
    fun removeManualDevice(deviceId: String) {
        val devices = getManualDevicesCsv().toMutableSet()
        devices.removeAll { it.startsWith("$deviceId,") }
        prefs.edit().putStringSet(KEY_MANUAL_DEVICES, devices).apply()
    }

    /** Returns all manually-added devices. */
    fun getManualDevices(): List<ManualDevice> {
        return getManualDevicesCsv().mapNotNull { ManualDevice.fromCsv(it) }
    }

    /** True if an IP is already in the manual device list. */
    fun isIpInManualList(ip: String): Boolean {
        return getManualDevices().any { it.ip == ip }
    }

    /** Returns the manual device with the given IP, or null. */
    fun getManualDeviceByIp(ip: String): ManualDevice? {
        return getManualDevices().find { it.ip == ip }
    }

    /** Returns the set of IPs from all manual devices (for dedup). */
    fun getManualDeviceIps(): Set<String> {
        return getManualDevices().map { it.ip }.toSet()
    }

    private fun getManualDevicesCsv(): Set<String> {
        return prefs.getStringSet(KEY_MANUAL_DEVICES, emptySet()) ?: emptySet()
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private fun lastTransportKey(deviceKey: String) = "$PREFIX_LAST_TRANSPORT$deviceKey"

    companion object {
        private const val PREFS_NAME = "ufm_remote_prefs"
        private const val PREFIX_LAST_TRANSPORT = "last_transport_"
        private const val KEY_REMOTE_ENABLED_IDS = "remote_enabled_ids"
        private const val KEY_LAST_REMOTE_DEVICE_ID = "last_remote_device_id"
        private const val KEY_LAST_REMOTE_DEVICE_NAME = "last_remote_device_name"
        private const val KEY_LAST_REMOTE_TRANSPORT = "last_remote_transport"
        private const val KEY_MANUAL_DEVICES = "manual_devices"
    }
}
