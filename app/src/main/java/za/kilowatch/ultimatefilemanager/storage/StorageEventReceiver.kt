package za.kilowatch.ultimatefilemanager.storage

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.util.Log

/**
 * BroadcastReceiver that listens for storage mount/unmount events
 * and USB device attach/detach events.
 *
 * Sends a local broadcast or calls a callback to notify the
 * StorageBrowserActivity to refresh the storage list.
 */
class StorageEventReceiver : BroadcastReceiver() {

    var onStorageChanged: (() -> Unit)? = null

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.d(TAG, "Storage event received: $action")

        when (action) {
            Intent.ACTION_MEDIA_MOUNTED,
            Intent.ACTION_MEDIA_UNMOUNTED,
            Intent.ACTION_MEDIA_EJECT,
            Intent.ACTION_MEDIA_REMOVED,
            UsbManager.ACTION_USB_DEVICE_ATTACHED,
            UsbManager.ACTION_USB_DEVICE_DETACHED,
            MockUsbStorageManager.ACTION_MOCK_USB_STATE_CHANGED -> {
                onStorageChanged?.invoke()
            }
        }
    }

    companion object {
        private const val TAG = "StorageEventReceiver"

        /** Actions to register in the IntentFilter with file data scheme */
        val MEDIA_ACTIONS = listOf(
            Intent.ACTION_MEDIA_MOUNTED,
            Intent.ACTION_MEDIA_UNMOUNTED,
            Intent.ACTION_MEDIA_EJECT,
            Intent.ACTION_MEDIA_REMOVED
        )

        /** Actions to register without file scheme (hardware USB and app events) */
        val GENERIC_ACTIONS = listOf(
            UsbManager.ACTION_USB_DEVICE_ATTACHED,
            UsbManager.ACTION_USB_DEVICE_DETACHED,
            MockUsbStorageManager.ACTION_MOCK_USB_STATE_CHANGED
        )

        /** Legacy list for backward compatibility */
        val STORAGE_ACTIONS = MEDIA_ACTIONS + GENERIC_ACTIONS

        /**
         * Registers the receiver for both media scheme events and USB/mock events.
         */
        fun register(context: Context, receiver: StorageEventReceiver) {
            val mediaFilter = android.content.IntentFilter().apply {
                MEDIA_ACTIONS.forEach { addAction(it) }
                addDataScheme("file")
            }
            val genericFilter = android.content.IntentFilter().apply {
                GENERIC_ACTIONS.forEach { addAction(it) }
            }

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, mediaFilter, Context.RECEIVER_EXPORTED)
                context.registerReceiver(receiver, genericFilter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(receiver, mediaFilter)
                context.registerReceiver(receiver, genericFilter)
            }
        }
    }
}
