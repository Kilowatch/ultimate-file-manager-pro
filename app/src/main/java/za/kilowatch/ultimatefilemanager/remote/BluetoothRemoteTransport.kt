package za.kilowatch.ultimatefilemanager.remote

import android.bluetooth.BluetoothProfile
import kotlinx.coroutines.flow.StateFlow
import za.kilowatch.ultimatefilemanager.network.BluetoothRemoteManager

/**
 * [RemoteTransport] implementation that delegates to the existing
 * [BluetoothRemoteManager]. Zero behavioral change — every call
 * is a direct pass-through to the underlying HID transport.
 */
class BluetoothRemoteTransport(
    private val manager: BluetoothRemoteManager
) : RemoteTransport {

    override val connectionState: StateFlow<Int> = manager.connectionState
    override val connectedDeviceName: StateFlow<String?> = manager.connectedDeviceName

    override suspend fun connect(): Boolean {
        return manager.autoConnectToSavedTv(isUserInitiated = true)
    }

    override fun disconnect() {
        manager.disconnectCurrentDevice()
    }

    override fun isConnected(): Boolean {
        return connectionState.value == BluetoothProfile.STATE_CONNECTED
    }

    // ── Key Injection — all delegate to BluetoothRemoteManager ─────────────────

    override fun sendKeyboardKey(hidKeyCode: Byte) {
        manager.sendKeyboardKey(hidKeyCode)
    }

    override fun sendKeyboardKeyWithModifier(hidKeyCode: Byte, modifier: Byte) {
        manager.sendKeyboardKeyWithModifier(hidKeyCode, modifier)
    }

    override fun sendConsumerKey(hidKeyCode: Int) {
        manager.sendConsumerKey(hidKeyCode)
    }

    override fun sendText(text: String) {
        manager.sendText(text)
    }

    override fun sendCharacter(char: Char) {
        manager.sendCharacter(char)
    }

    override fun sendKeyboardKeyDown(hidKeyCode: Byte) {
        manager.sendKeyboardKeyDown(hidKeyCode)
    }

    override fun sendKeyboardKeyUp() {
        manager.sendKeyboardKeyUp()
    }

    override fun sendHidBackspace() {
        manager.sendHidBackspace()
    }

    override fun sendHidEnter() {
        manager.sendHidEnter()
    }

    override fun sendHidTab() {
        manager.sendHidTab()
    }

    override fun sendSelectAll() {
        manager.sendSelectAll()
    }
}
