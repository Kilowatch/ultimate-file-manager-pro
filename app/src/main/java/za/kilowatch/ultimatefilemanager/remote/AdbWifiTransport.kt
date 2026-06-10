package za.kilowatch.ultimatefilemanager.remote

import android.bluetooth.BluetoothProfile
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import za.kilowatch.ultimatefilemanager.network.AdbManager
import za.kilowatch.ultimatefilemanager.network.PairedDevice

/**
 * [RemoteTransport] implementation that sends remote control commands
 * over a WiFi ADB connection using Android [input] shell commands.
 *
 * Uses the existing persistent shell infrastructure in [AdbManager.sendShellCommand]
 * for low-latency key injection. Maps HID keycodes and Consumer Control codes
 * to Android [android.view.KeyEvent] integer codes.
 */
class AdbWifiTransport(
    private val pairedDevice: PairedDevice
) : RemoteTransport {

    private val TAG = "AdbWifiTransport"
    private val adbManager = AdbManager.getInstance()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _connectionState = MutableStateFlow(BluetoothProfile.STATE_DISCONNECTED)
    override val connectionState: StateFlow<Int> = _connectionState

    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    override val connectedDeviceName: StateFlow<String?> = _connectedDeviceName

    companion object {
        /**
         * Creates an [AdbWifiTransport] that is already in the CONNECTED state.
         * Used when the activity is recreated after the ADB connection was
         * established — the actual socket is still alive in [AdbManager].
         */
        fun reconnect(pairedDevice: PairedDevice): AdbWifiTransport {
            return AdbWifiTransport(pairedDevice).apply {
                _connectionState.value = BluetoothProfile.STATE_CONNECTED
                _connectedDeviceName.value = pairedDevice.name
            }
        }
    }

    override suspend fun connect(): Boolean {
        _connectionState.value = BluetoothProfile.STATE_CONNECTING
        _connectedDeviceName.value = pairedDevice.name

        val port = 5555
        val success = adbManager.connect(pairedDevice.lastIp, port)
        if (success) {
            adbManager.isRemoteMode = true
            adbManager.activeRemoteDeviceId = pairedDevice.deviceId
            _connectionState.value = BluetoothProfile.STATE_CONNECTED
            AdbRemoteForegroundService.start(pairedDevice.name)
            Log.i(TAG, "Connected to ${pairedDevice.name} at ${pairedDevice.lastIp}:$port")
        } else {
            _connectionState.value = BluetoothProfile.STATE_DISCONNECTED
            _connectedDeviceName.value = null
            Log.e(TAG, "Failed to connect to ${pairedDevice.name}: ${adbManager.lastError}")
        }
        return success
    }

    override fun disconnect() {
        Log.i(TAG, "Disconnecting from ${pairedDevice.name}")
        AdbRemoteForegroundService.stop()
        adbManager.releaseRemoteMode()
        adbManager.activeRemoteDeviceId = null
        adbManager.disconnectExplicit()
        _connectionState.value = BluetoothProfile.STATE_DISCONNECTED
        _connectedDeviceName.value = null
    }

    override fun isConnected(): Boolean {
        return adbManager.isConnected() && _connectionState.value == BluetoothProfile.STATE_CONNECTED
    }

    // ── Key Injection ─────────────────────────────────────────────────────────

    override fun sendKeyboardKey(hidKeyCode: Byte) {
        val keyevent = hidToAndroidKeyEvent(hidKeyCode) ?: return
        sendCommand("input keyevent $keyevent")
    }

    override fun sendKeyboardKeyWithModifier(hidKeyCode: Byte, modifier: Byte) {
        // Modifier bits: 0x01 = Left Ctrl, 0x02 = Left Shift
        val isCtrl = (modifier.toInt() and 0x01) != 0
        val isShift = (modifier.toInt() and 0x02) != 0

        if (isCtrl) {
            // Send Ctrl + key as separate keyevents (Android input processes them sequentially)
            sendCommand("input keyevent --longpress 113")  // CTRL_LEFT
            val keyevent = hidToAndroidKeyEvent(hidKeyCode) ?: return
            sendCommand("input keyevent $keyevent")
        } else if (isShift) {
            // For shifted characters, find the uppercase Android keyevent
            val keyevent = hidShiftedToAndroidKeyEvent(hidKeyCode) ?: return
            sendCommand("input keyevent $keyevent")
        } else {
            sendKeyboardKey(hidKeyCode)
        }
    }

    override fun sendConsumerKey(hidKeyCode: Int) {
        val keyevent = consumerToAndroidKeyEvent(hidKeyCode) ?: return
        sendCommand("input keyevent $keyevent")
    }

    override fun sendText(text: String) {
        if (text.isEmpty()) return
        val escaped = escapeShellText(text)
        sendCommand("input text $escaped")
    }

    override fun sendCharacter(char: Char) {
        when (char) {
            '\n' -> sendCommand("input keyevent 66")  // ENTER
            '\t' -> sendCommand("input keyevent 61")  // TAB
            ' '  -> sendCommand("input text ' '")
            else -> {
                val escaped = escapeShellText(char.toString())
                sendCommand("input text $escaped")
            }
        }
    }

    override fun sendKeyboardKeyDown(hidKeyCode: Byte) {
        val keyevent = hidToAndroidKeyEvent(hidKeyCode) ?: return
        sendCommand("input keyevent --longpress $keyevent")
    }

    override fun sendKeyboardKeyUp() {
        // Android input keyevent --longpress on release side: send a zero-key
        // to break the longpress cycle, or simply do nothing — the longpress
        // simulates hold-and-release automatically after a timeout.
        // For explicit UP, we rely on the regular keyevent which completes the cycle.
    }

    override fun sendHidBackspace() {
        sendCommand("input keyevent 67")  // KEYCODE_DEL
    }

    override fun sendHidEnter() {
        sendCommand("input keyevent 66")  // KEYCODE_ENTER
    }

    override fun sendHidTab() {
        sendCommand("input keyevent 61")  // KEYCODE_TAB
    }

    override fun sendSelectAll() {
        sendCommand("input keyevent --longpress 113")  // CTRL_LEFT hold
        sendCommand("input keyevent 29")               // A
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun sendCommand(command: String) {
        scope.launch {
            try {
                adbManager.sendShellCommand(command)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send ADB command: $command", e)
            }
        }
    }

    /** Escape a string for use inside single-quoted `input text '...'`. */
    private fun escapeShellText(text: String): String {
        // Replace any internal single quotes with '\'' (end quote, escaped quote, start quote)
        val escaped = text.replace("'", "'\\''")
        return "'$escaped'"
    }

    // ── Keycode Mapping ───────────────────────────────────────────────────────

    /** Map USB HID keyboard keycode (Usage Page 0x07) to Android KeyEvent code. */
    private fun hidToAndroidKeyEvent(hidCode: Byte): Int? = when (hidCode) {
        0x04.toByte() -> 29   // A
        0x05.toByte() -> 30   // B
        0x06.toByte() -> 31   // C
        0x07.toByte() -> 32   // D
        0x08.toByte() -> 33   // E
        0x09.toByte() -> 34   // F
        0x0A.toByte() -> 35   // G
        0x0B.toByte() -> 36   // H
        0x0C.toByte() -> 37   // I
        0x0D.toByte() -> 38   // J
        0x0E.toByte() -> 39   // K
        0x0F.toByte() -> 40   // L
        0x10.toByte() -> 41   // M
        0x11.toByte() -> 42   // N
        0x12.toByte() -> 43   // O
        0x13.toByte() -> 44   // P
        0x14.toByte() -> 45   // Q
        0x15.toByte() -> 46   // R
        0x16.toByte() -> 47   // S
        0x17.toByte() -> 48   // T
        0x18.toByte() -> 49   // U
        0x19.toByte() -> 50   // V
        0x1A.toByte() -> 51   // W
        0x1B.toByte() -> 52   // X
        0x1C.toByte() -> 53   // Y
        0x1D.toByte() -> 54   // Z
        0x1E.toByte() -> 8    // 1
        0x1F.toByte() -> 9    // 2
        0x20.toByte() -> 10   // 3
        0x21.toByte() -> 11   // 4
        0x22.toByte() -> 12   // 5
        0x23.toByte() -> 13   // 6
        0x24.toByte() -> 14   // 7
        0x25.toByte() -> 15   // 8
        0x26.toByte() -> 16   // 9
        0x27.toByte() -> 7    // 0
        0x28.toByte() -> 23   // Enter → DPAD_CENTER (also KEYCODE_ENTER 66, used in dedicated methods)
        0x2A.toByte() -> 67   // Backspace → DEL
        0x2B.toByte() -> 61   // Tab
        0x2C.toByte() -> 62   // Space
        0x2D.toByte() -> 69   // -
        0x2E.toByte() -> 70   // =
        0x2F.toByte() -> 71   // [
        0x30.toByte() -> 72   // ]
        0x31.toByte() -> 73   // Backslash
        0x33.toByte() -> 74   // ;
        0x34.toByte() -> 75   // '
        0x35.toByte() -> 68   // `
        0x36.toByte() -> 55   // ,
        0x37.toByte() -> 56   // .
        0x38.toByte() -> 76   // /
        // D-Pad
        0x4F.toByte() -> 22   // Right arrow
        0x50.toByte() -> 21   // Left arrow
        0x51.toByte() -> 20   // Down arrow
        0x52.toByte() -> 19   // Up arrow
        else -> null
    }

    /** Map shifted USB HID keyboard keycode to Android KeyEvent code. */
    private fun hidShiftedToAndroidKeyEvent(hidCode: Byte): Int? = when (hidCode) {
        // Letters — same keyevent, Android handles shift via meta state.
        // But since we can't set meta state via `input keyevent` alone,
        // we map to the character keyevent and rely on the TV processing it.
        0x04.toByte() -> 29   // A (shift pressed = uppercase)
        0x05.toByte() -> 30   // B
        0x06.toByte() -> 31   // C
        0x07.toByte() -> 32   // D
        0x08.toByte() -> 33   // E
        0x09.toByte() -> 34   // F
        0x0A.toByte() -> 35   // G
        0x0B.toByte() -> 36   // H
        0x0C.toByte() -> 37   // I
        0x0D.toByte() -> 38   // J
        0x0E.toByte() -> 39   // K
        0x0F.toByte() -> 40   // L
        0x10.toByte() -> 41   // M
        0x11.toByte() -> 42   // N
        0x12.toByte() -> 43   // O
        0x13.toByte() -> 44   // P
        0x14.toByte() -> 45   // Q
        0x15.toByte() -> 46   // R
        0x16.toByte() -> 47   // S
        0x17.toByte() -> 48   // T
        0x18.toByte() -> 49   // U
        0x19.toByte() -> 50   // V
        0x1A.toByte() -> 51   // W
        0x1B.toByte() -> 52   // X
        0x1C.toByte() -> 53   // Y
        0x1D.toByte() -> 54   // Z
        // Shifted numbers/symbols — map to the symbol keyevent where possible,
        // otherwise fall back to the base key and rely on input text
        0x1E.toByte() -> 8    // ! (KEYCODE_1 with shift, use 1 keyevent)
        0x1F.toByte() -> 9    // @
        0x20.toByte() -> 10   // #
        0x21.toByte() -> 11   // $
        0x22.toByte() -> 12   // %
        0x23.toByte() -> 13   // ^
        0x24.toByte() -> 14   // &
        0x25.toByte() -> 15   // *
        0x26.toByte() -> 16   // (
        0x27.toByte() -> 7    // )
        0x2D.toByte() -> 69   // _ (underscore, same key as -)
        0x2E.toByte() -> 70   // +
        0x2F.toByte() -> 71   // {
        0x30.toByte() -> 72   // }
        0x31.toByte() -> 73   // |
        0x33.toByte() -> 74   // :
        0x34.toByte() -> 75   // "
        0x35.toByte() -> 68   // ~
        0x36.toByte() -> 55   // <
        0x37.toByte() -> 56   // >
        0x38.toByte() -> 76   // ?
        else -> null
    }

    /** Map USB HID Consumer Control code (Usage Page 0x0C) to Android KeyEvent code. */
    private fun consumerToAndroidKeyEvent(consumerCode: Int): Int? = when (consumerCode) {
        0x00CD -> 126  // MEDIA_PLAY (KEYCODE_MEDIA_PLAY)
        0x00B7 -> 86   // MEDIA_STOP (KEYCODE_MEDIA_STOP — closest match)
        0x00B4 -> 89   // MEDIA_REWIND
        0x00B3 -> 90   // MEDIA_FAST_FORWARD
        0x00E9 -> 24   // VOLUME_UP
        0x00EA -> 25   // VOLUME_DOWN
        0x00E2 -> 164  // VOLUME_MUTE
        0x0223 -> 3    // HOME
        0x0224 -> 4    // BACK
        else -> {
            Log.w(TAG, "Unknown consumer code: 0x${consumerCode.toString(16)}")
            null
        }
    }
}
