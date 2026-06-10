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
            UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                onStorageChanged?.invoke()
            }
        }
    }

    companion object {
        private const val TAG = "StorageEventReceiver"

        /** Actions to register in the IntentFilter */
        val STORAGE_ACTIONS = listOf(
            Intent.ACTION_MEDIA_MOUNTED,
            Intent.ACTION_MEDIA_UNMOUNTED,
            Intent.ACTION_MEDIA_EJECT,
            Intent.ACTION_MEDIA_REMOVED,
            UsbManager.ACTION_USB_DEVICE_ATTACHED,
            UsbManager.ACTION_USB_DEVICE_DETACHED
        )
    }
}
