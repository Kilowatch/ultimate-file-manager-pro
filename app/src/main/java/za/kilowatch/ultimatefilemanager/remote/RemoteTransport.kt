package za.kilowatch.ultimatefilemanager.remote

import kotlinx.coroutines.flow.StateFlow

/**
 * Common abstraction for remote control transports (Bluetooth HID and WiFi ADB).
 *
 * Implementations handle connection lifecycle and key-injection, so
 * [TvRemoteActivity] never branches on transport type for button handling.
 *
 * All send methods are non-suspending (fire-and-forget). ADB implementations
 * launch internal coroutines; callers on the UI thread are not blocked.
 */
interface RemoteTransport {

    /** Connection state — values match [android.bluetooth.BluetoothProfile.STATE_*]. */
    val connectionState: StateFlow<Int>

    /** Human-readable name of the connected device, or null if not connected. */
    val connectedDeviceName: StateFlow<String?>

    /** Attempt to connect. Returns true on success. */
    suspend fun connect(): Boolean

    /** Disconnect and release resources. */
    fun disconnect()

    /** True if the transport currently has an active connection. */
    fun isConnected(): Boolean

    // ── Key Injection ─────────────────────────────────────────────────────────

    /** Send a keyboard key (Usage Page 0x07) with no modifier. */
    fun sendKeyboardKey(hidKeyCode: Byte)

    /** Send a keyboard key with an optional modifier byte. */
    fun sendKeyboardKeyWithModifier(hidKeyCode: Byte, modifier: Byte)

    /** Send a Consumer Control key (Usage Page 0x0C). */
    fun sendConsumerKey(hidKeyCode: Int)

    /** Send every character in [text]. */
    fun sendText(text: String)

    /** Map and send a single printable character. */
    fun sendCharacter(char: Char)

    /** Send only the key-DOWN report (no UP). Used for hold gestures. */
    fun sendKeyboardKeyDown(hidKeyCode: Byte)

    /** Send only the key-UP report. Pair with [sendKeyboardKeyDown]. */
    fun sendKeyboardKeyUp()

    /** Send Backspace (HID 0x2A). */
    fun sendHidBackspace()

    /** Send Enter / Return (HID 0x28). */
    fun sendHidEnter()

    /** Send Tab (HID 0x2B). */
    fun sendHidTab()

    /** Send Ctrl+A — Select All. */
    fun sendSelectAll()
}
