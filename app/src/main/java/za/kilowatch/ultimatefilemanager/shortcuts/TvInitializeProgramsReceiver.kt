package za.kilowatch.ultimatefilemanager.shortcuts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.tvprovider.media.tv.TvContractCompat
import za.kilowatch.ultimatefilemanager.util.DeviceUtils

/**
 * BroadcastReceiver listening for [TvContractCompat.ACTION_INITIALIZE_PROGRAMS]
 * sent by Android TV when the app is installed or home screen channels are customized.
 * Initializes the default "UFM Favorites" preview channel so it appears in
 * "Customize channels" on the TV home screen.
 */
class TvInitializeProgramsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "TvInitProgramsReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d(TAG, "Received broadcast action: $action")
        if (action == TvContractCompat.ACTION_INITIALIZE_PROGRAMS ||
            action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            val pendingResult = goAsync()
            Thread {
                try {
                    if (DeviceUtils.isTvDevice(context)) {
                        val manager = TvPreviewManager()
                        val channelId = manager.getOrCreateChannelId(context)
                        Log.d(TAG, "Initialized TV preview channel on broadcast: id=$channelId")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to initialize TV preview channel on broadcast", e)
                } finally {
                    pendingResult.finish()
                }
            }.apply {
                name = "tv-init-programs"
                isDaemon = true
                start()
            }
        }
    }
}
