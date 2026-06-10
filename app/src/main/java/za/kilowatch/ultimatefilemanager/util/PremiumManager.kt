package za.kilowatch.ultimatefilemanager.util

import android.content.Context
import android.content.SharedPreferences

/**
 * Manages premium status. 
 */
object PremiumManager {
    private const val PREFS_NAME = "ufm_premium_prefs"
    private const val KEY_IS_PREMIUM = "is_premium"

    fun isPremium(context: Context): Boolean {
        // For development/prototype, we can easily toggle this.
        // In production, this would integrate with BillingManager purchase tracking.
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_IS_PREMIUM, false)
    }

    fun setPremium(context: Context, isPremium: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_IS_PREMIUM, isPremium).apply()
    }
}
