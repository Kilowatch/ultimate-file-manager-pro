package za.kilowatch.ultimatefilemanager.billing

import android.content.Context
import android.content.SharedPreferences

object LoyaltyPrefs {
    private const val PREFS_NAME = "loyalty_prefs"
    private const val KEY_TOTAL_TIPPED = "total_tipped"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getTotalTipped(context: Context): Int {
        return getPrefs(context).getInt(KEY_TOTAL_TIPPED, 0)
    }

    fun addTip(context: Context, amount: Int) {
        val current = getTotalTipped(context)
        getPrefs(context).edit().putInt(KEY_TOTAL_TIPPED, current + amount).apply()
    }
}
