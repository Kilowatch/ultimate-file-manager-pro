package za.kilowatch.ultimatefilemanager.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import za.kilowatch.ultimatefilemanager.network.TvServerForegroundService
import za.kilowatch.ultimatefilemanager.util.DeviceUtils

/**
 * TV-only boot receiver that starts [TvServerForegroundService] whenever the
 * Android TV / Fire TV device finishes booting or powers on.
 *
 * This ensures paired mobile devices can connect to the TV and browse files
 * in the background even when UFM is not open on the TV.
 *
 * Runs on a background daemon thread with [goAsync] to prevent blocking the
 * main thread during cold boot.
 */
class TvBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val isBootAction = Intent.ACTION_BOOT_COMPLETED == action ||
                Intent.ACTION_LOCKED_BOOT_COMPLETED == action ||
                Intent.ACTION_MY_PACKAGE_REPLACED == action ||
                "android.intent.action.QUICKBOOT_POWERON" == action ||
                "com.htc.intent.action.QUICKBOOT_POWERON" == action

        if (isBootAction) {
            Log.d("TvBootReceiver", "TV boot intent received ($action), starting TV server foreground service")
            val pendingResult = goAsync()
            Thread {
                try {
                    if (DeviceUtils.isTvDevice(context.applicationContext)) {
                        TvServerForegroundService.start(context.applicationContext)
                    }
                } catch (e: Exception) {
                    Log.e("TvBootReceiver", "Failed to start TV server foreground service on TV boot", e)
                } finally {
                    pendingResult.finish()
                }
            }.apply {
                name = "tv-boot-receiver-init"
                isDaemon = true
                start()
            }
        }
    }
}
