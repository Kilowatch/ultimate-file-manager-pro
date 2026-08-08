package za.kilowatch.ultimatefilemanager.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import za.kilowatch.ultimatefilemanager.sync.advanced.InstantSyncWatcher

/**
 * Re-registers [InstantSyncWatcher] observers whenever the device finishes booting
 * or when the application package is updated.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (Intent.ACTION_BOOT_COMPLETED == action || Intent.ACTION_MY_PACKAGE_REPLACED == action) {
            Log.d("BootReceiver", "Boot/update intent received ($action), re-registering instant sync watchers")
            try {
                InstantSyncWatcher.rewatchAll(context.applicationContext)
            } catch (e: Exception) {
                Log.e("BootReceiver", "Failed to rewatch instant sync profiles on boot", e)
            }
        }
    }
}
