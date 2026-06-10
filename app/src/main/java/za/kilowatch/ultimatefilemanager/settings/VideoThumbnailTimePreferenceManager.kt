package za.kilowatch.ultimatefilemanager.settings

import android.content.Context

object VideoThumbnailTimePreferenceManager {

    private const val PREFS_NAME = "video_thumbnail_time_prefs"
    private const val KEY_PERCENT = "thumbnail_time_percent"

    private const val DEFAULT_PERCENT = 0

    fun getPercent(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_PERCENT, DEFAULT_PERCENT)
            .coerceIn(0, 100)
    }

    fun setPercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_PERCENT, percent.coerceIn(0, 100))
            .apply()
    }

    fun formatPercent(context: Context, percent: Int): String {
        val res = context.resources
        return when (percent) {
            0   -> res.getString(
                za.kilowatch.ultimatefilemanager.R.string.vtt_percent_0
            )
            10  -> res.getString(
                za.kilowatch.ultimatefilemanager.R.string.vtt_percent_10
            )
            25  -> res.getString(
                za.kilowatch.ultimatefilemanager.R.string.vtt_percent_25
            )
            50  -> res.getString(
                za.kilowatch.ultimatefilemanager.R.string.vtt_percent_50
            )
            75  -> res.getString(
                za.kilowatch.ultimatefilemanager.R.string.vtt_percent_75
            )
            else -> String.format("%d%%", percent)
        }
    }
}
