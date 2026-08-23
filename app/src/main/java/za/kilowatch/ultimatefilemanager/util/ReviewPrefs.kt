package za.kilowatch.ultimatefilemanager.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Manages preferences for the "Rate Us" / In-App Review prompt.
 * Ensures we only ask users at appropriate times and respect their choices.
 */
object ReviewPrefs {
    private const val PREFS_NAME = "review_prefs"
    private const val KEY_INSTALL_DATE = "install_date"
    private const val KEY_RATE_US_TAPPED = "rate_us_tapped"
    private const val KEY_SNOOZE_UNTIL = "snooze_until"
    private const val KEY_NEVER_ASK = "never_ask"
    private const val KEY_SNOOZE_COUNT = "snooze_count"
    private const val TAG = "GoRoRating"

    fun init(context: Context) {
        val prefs = getPrefs(context)
        if (!prefs.contains(KEY_INSTALL_DATE)) {
            prefs.edit().putLong(KEY_INSTALL_DATE, System.currentTimeMillis()).apply()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TEST FLAG — set to true to force the popup on every resume, bypassing all
    // eligibility checks. Flip back to false (or delete this block) to restore
    // normal behaviour before shipping.
    // ─────────────────────────────────────────────────────────────────────────
    private const val FORCE_REVIEW_POPUP = false

    fun shouldShowPopup(context: Context): Boolean {
        if (FORCE_REVIEW_POPUP) {
            Log.d(TAG, "shouldShowPopup: FORCE_REVIEW_POPUP=true — bypassing all checks")
            return true
        }

        val prefs = getPrefs(context)
        val now = System.currentTimeMillis()
        val sevenDays = 7 * 24 * 60 * 60 * 1000L

        val neverAsk = prefs.getBoolean(KEY_NEVER_ASK, false)
        val rateTapped = prefs.getBoolean(KEY_RATE_US_TAPPED, false)
        val snoozeUntil = prefs.getLong(KEY_SNOOZE_UNTIL, 0L)
        val installDate = prefs.getLong(KEY_INSTALL_DATE, now)

        Log.d(TAG, "Checking shouldShowPopup: neverAsk=$neverAsk, rateTapped=$rateTapped, now=$now, installDate=$installDate, snoozeUntil=$snoozeUntil")

        if (neverAsk || rateTapped) {
            Log.d(TAG, "-> False: user already rated or declined forever")
            return false
        }
        if (now < installDate + sevenDays) {
            Log.d(TAG, "-> False: too early (wait 7 days), install was at $installDate")
            return false
        }
        if (now < snoozeUntil) {
            Log.d(TAG, "-> False: snoozed until $snoozeUntil")
            return false
        }

        Log.d(TAG, "-> True: eligible for popup")
        return true
    }


    fun onRateUsTapped(context: Context) {
        Log.d(TAG, "onRateUsTapped: marking as rated and never ask again")
        getPrefs(context).edit()
            .putBoolean(KEY_RATE_US_TAPPED, true)
            .putBoolean(KEY_NEVER_ASK, true) // Never ask again if they tapped it once
            .apply()
    }

    fun onMaybeLater(context: Context) {
        val prefs = getPrefs(context)
        val currentSnoozes = prefs.getInt(KEY_SNOOZE_COUNT, 0)
        
        if (currentSnoozes >= 1) {
            // Already snoozed once, this was the second ask. Never ask again.
            Log.d(TAG, "onMaybeLater: reached limit (2 asks), marking as never ask")
            onNoThanks(context)
        } else {
            val snoozeUntil = System.currentTimeMillis() + 14 * 24 * 60 * 60 * 1000L
            Log.d(TAG, "onMaybeLater: first snooze, snoozing until $snoozeUntil")
            prefs.edit()
                .putLong(KEY_SNOOZE_UNTIL, snoozeUntil)
                .putInt(KEY_SNOOZE_COUNT, currentSnoozes + 1)
                .apply()
        }
    }

    fun onNoThanks(context: Context) {
        Log.d(TAG, "onNoThanks: marking as never ask again")
        getPrefs(context).edit().putBoolean(KEY_NEVER_ASK, true).apply()
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
