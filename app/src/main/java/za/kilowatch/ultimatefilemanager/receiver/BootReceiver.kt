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
        val isBootAction = Intent.ACTION_BOOT_COMPLETED == action ||
                Intent.ACTION_LOCKED_BOOT_COMPLETED == action ||
                Intent.ACTION_MY_PACKAGE_REPLACED == action ||
                "android.intent.action.QUICKBOOT_POWERON" == action ||
                "com.htc.intent.action.QUICKBOOT_POWERON" == action

        if (isBootAction) {
            Log.d("BootReceiver", "Boot/update intent received ($action), re-registering instant sync watchers")
            try {
                InstantSyncWatcher.rewatchAll(context.applicationContext)
            } catch (e: Exception) {
                Log.e("BootReceiver", "Failed to rewatch instant sync profiles on boot", e)
            }

            try {
                if (za.kilowatch.ultimatefilemanager.util.DeviceUtils.isTvDevice(context)) {
                    za.kilowatch.ultimatefilemanager.network.TvServerForegroundService.start(context)
                }
            } catch (e: Exception) {
                Log.e("BootReceiver", "Failed to start TV server foreground service on boot", e)
            }
        }
    }
}
