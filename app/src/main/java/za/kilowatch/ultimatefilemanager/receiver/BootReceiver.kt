package za.kilowatch.ultimatefilemanager.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import za.kilowatch.ultimatefilemanager.sync.advanced.InstantSyncWatcher

/**
 * Re-registers [InstantSyncWatcher] observers whenever the device finishes booting
 * or when the application package is updated.
 *
 * The re-registration runs on a background thread: [InstantSyncWatcher.rewatchAll]
 * synchronously loads the advanced-sync profile JSON file and starts/stops native
 * `FileObserver`s per profile, and the TV-server start touches SharedPreferences and
 * the encrypted pairing store — none of which belongs on the main thread during a boot
 * broadcast (a low-end TV can exceed the 5 s main-thread window while storage is still
 * spinning up). [goAsync] keeps the process alive until the background work finishes.
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
            val pendingResult = goAsync()
            Thread {
                try {
                    try {
                        InstantSyncWatcher.rewatchAll(context.applicationContext)
                    } catch (e: Exception) {
                        Log.e("BootReceiver", "Failed to rewatch instant sync profiles on boot", e)
                    }

                    try {
                        if (za.kilowatch.ultimatefilemanager.util.DeviceUtils.isTvDevice(context.applicationContext)) {
                            za.kilowatch.ultimatefilemanager.network.TvServerForegroundService.start(context.applicationContext)
                        }
                    } catch (e: Exception) {
                        Log.e("BootReceiver", "Failed to start TV server foreground service on boot", e)
                    }
                } finally {
                    pendingResult.finish()
                }
            }.apply {
                name = "boot-receiver-init"
                isDaemon = true
                start()
            }
        }
    }
}
