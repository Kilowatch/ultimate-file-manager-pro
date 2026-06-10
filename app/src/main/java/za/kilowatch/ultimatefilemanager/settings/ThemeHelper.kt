package za.kilowatch.ultimatefilemanager.settings

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.view.View
import androidx.appcompat.app.AppCompatDelegate

/**
 * Helper to read/write and apply the theme preference.
 * Supports Light, Dark, AMOLED Black, and System Default modes.
 *
 * AMOLED mode forces MODE_NIGHT_YES so all dark colour resources load, then
 * applies a [ThemeOverlay.UFM.Amoled] style overlay (theme-attribute colours)
 * and swaps the root-view background to pure black at runtime so that OLED
 * pixels turn completely off — maximising battery savings and contrast.
 */
object ThemeHelper {
    private const val PREFS = "ufm_prefs"
    private const val KEY_THEME = "theme_mode"

    const val THEME_LIGHT = 0
    const val THEME_DARK = 1
    const val THEME_SYSTEM = 2
    const val THEME_AMOLED = 3

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Apply night-mode flag. Call this before [Activity.super.onCreate].
     * If AMOLED is saved and [context] is an [Activity], the AMOLED
     * ThemeOverlay is also applied to the activity's theme object so that
     * Material3 surface/background attributes resolve to pure black.
     */
    fun applyTheme(context: Context) {
        val mode = getSavedTheme(context)
        applyMode(mode)
        if (mode == THEME_AMOLED && context is Activity) {
            context.theme.applyStyle(
                za.kilowatch.ultimatefilemanager.R.style.ThemeOverlay_UFM_Amoled,
                /* force = */ true
            )
        }
    }

    /**
     * Programmatically paint [rootView]'s background pure black.
     * Call this **after** [Activity.setContentView] whenever AMOLED is active,
     * so the root layout background (gradient drawable) is replaced with black.
     */
    fun applyAmoledBackground(context: Context, rootView: View) {
        if (getSavedTheme(context) == THEME_AMOLED) {
            rootView.setBackgroundColor(Color.BLACK)
        }
    }

    fun saveAndApply(context: Context, theme: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_THEME, theme)
            .apply()
        applyMode(theme)
    }

    fun getSavedTheme(context: Context): Int {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_THEME, THEME_DARK)
    }

    fun isAmoled(context: Context): Boolean = getSavedTheme(context) == THEME_AMOLED

    // ── Internal ────────────────────────────────────────────────────────────

    private fun applyMode(mode: Int) {
        val nightMode = when (mode) {
            THEME_LIGHT  -> AppCompatDelegate.MODE_NIGHT_NO
            THEME_DARK   -> AppCompatDelegate.MODE_NIGHT_YES
            THEME_AMOLED -> AppCompatDelegate.MODE_NIGHT_YES   // dark resources + overlay
            else         -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(nightMode)
    }
}
