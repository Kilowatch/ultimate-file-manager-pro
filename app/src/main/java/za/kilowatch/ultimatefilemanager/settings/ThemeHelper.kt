package za.kilowatch.ultimatefilemanager.settings

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.view.View
import androidx.appcompat.app.AppCompatDelegate
import za.kilowatch.ultimatefilemanager.util.DeviceUtils

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
        // TV keeps the frozen pre-redesign Material 3 theme so the Expressive
        // migration does not change TV visuals. The TV flavor manifest already
        // applies Theme.UltimateFileManager.Tv; this guard covers the case where
        // the mobile flavor is sideloaded onto a TV device.
        if (context is Activity && DeviceUtils.isTvDevice(context)) {
            context.setTheme(za.kilowatch.ultimatefilemanager.R.style.Theme_UltimateFileManager_Tv)
        }
        if (mode == THEME_AMOLED && context is Activity) {
            context.theme.applyStyle(
                za.kilowatch.ultimatefilemanager.R.style.ThemeOverlay_UFM_Amoled,
                /* force = */ true
            )
            val isMaterialYouActive = MaterialYouPrefs.isEnabled(context) &&
                !DeviceUtils.isTvDevice(context) &&
                android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
            if (!isMaterialYouActive) {
                context.theme.applyStyle(
                    za.kilowatch.ultimatefilemanager.R.style.ThemeOverlay_UFM_Amoled_Colors,
                    /* force = */ true
                )
            }
        }

        // When Material You is off and the user has chosen a custom accent color,
        // seed the Material3 dynamic palette from that color so all views using
        // ?attr/colorPrimary (toggle switches, buttons, text highlights, etc.)
        // automatically reflect the user's color across EVERY activity.
        // The application-level DynamicColors.applyToActivitiesIfAvailable only runs
        // when MaterialYouPrefs.isEnabled, so this block is safe (no double-apply).
        //
        // SUPPRESSED while Colorblind Mode is on (FR-22), and this is the
        // non-obvious half of the suppression. The Application-level precondition
        // above only covers the wallpaper path; this block is an INDEPENDENT way
        // for a non-palette colour to become colorPrimary, sourced from the user's
        // custom Default Icon Color rather than from the wallpaper. Guarding only
        // the Application path would leave this one live, and it is specifically
        // this path that carries the user's "Default Icon Colors" report through
        // to BUTTON colours rather than only to icon tints.
        if (context is Activity &&
            !DeviceUtils.isTvDevice(context) &&
            !MaterialYouPrefs.isEnabled(context) &&
            !ColorblindPrefs.isEnabled(context) &&
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
        ) {
            val customColor = DefaultIconColorManager.getCustomColor(context, mode)
            if (customColor != null) {
                try {
                    val bitmap = android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
                    bitmap.setPixel(0, 0, customColor)
                    val options = com.google.android.material.color.DynamicColorsOptions.Builder()
                        .setContentBasedSource(bitmap)
                        .build()
                    com.google.android.material.color.DynamicColors.applyToActivityIfAvailable(
                        context as Activity, options
                    )
                } catch (_: Exception) { }
            }
        }

        // Colorblind Accessibility Mode (FR-01, FR-02).
        //
        // Applied last, on purpose, and with force = true. Three things above it
        // would otherwise win attributes this feature owns:
        //   - the TV setTheme() reset at the top of this method replaces the whole
        //     theme object, so anything applied before it is discarded;
        //   - the AMOLED overlay, which is also an overlay and would win by
        //     ordering alone;
        //   - DynamicColors, which rewrites colorPrimary from the wallpaper.
        // Ordering last means the accessibility choice beats all three, which is
        // the correct precedence: a user who has told the app they cannot
        // distinguish red from green has stated a need, not a preference.
        //
        // Why an overlay at all: `@color/` references are resolved during
        // inflation and cannot be swapped afterwards. Only `?attr/` reads go
        // through the theme, so a runtime switch is only expressible as a theme
        // overlay over attributes the layouts already read. This is also why the
        // base themes keep every one of these attributes at its original value —
        // with the mode off, no overlay is applied and nothing changes (FR-19).
        //
        // Dialogs inherit this for free: MaterialAlertDialogBuilder wraps the
        // Activity context in a ContextThemeWrapper, which copies the Activity's
        // theme (applied styles included) before layering UFM.Dialog on top.
        if (context is Activity && ColorblindPrefs.isEnabled(context)) {
            context.theme.applyStyle(colorblindOverlay(context), /* force = */ true)
        }
    }

    /**
     * Resolve the `ThemeOverlay.UFM.Colorblind.<Type>.<Strength>` style for the
     * saved settings, or `0` when the mode is Off.
     *
     * Type and strength are read independently. They are independent axes —
     * strength scales the *focus fill*, type remaps the *semantic* palette — so
     * there is no combined flag to read and no valid single "is colorblind on"
     * answer beyond [ColorblindPrefs.isEnabled].
     *
     * Resolved by explicit `when` rather than `getIdentifier()`. Reflection by
     * name is the usual way to address a generated style, and it is the wrong
     * one here: it cannot be verified at compile time, it survives a renamed or
     * deleted style as a silent no-op at runtime, and R8 cannot see through it.
     * Nine branches is a small enough price for a lookup that fails to build
     * when a style goes missing.
     */
    private fun colorblindOverlay(context: Context): Int {
        val strength = when (ColorblindPrefs.getStrength(context)) {
            ColorblindPrefs.STRENGTH_LOW  -> 0
            ColorblindPrefs.STRENGTH_HIGH -> 2
            else                          -> 1   // STRENGTH_MEDIUM, the default
        }
        return when (ColorblindPrefs.getType(context)) {
            ColorblindPrefs.TYPE_RED_GREEN     -> RED_GREEN_OVERLAYS[strength]
            ColorblindPrefs.TYPE_BLUE_YELLOW   -> BLUE_YELLOW_OVERLAYS[strength]
            ColorblindPrefs.TYPE_HIGH_CONTRAST -> HIGH_CONTRAST_OVERLAYS[strength]
            else                               -> 0
        }
    }

    // Indexed by strength tier: Low, Medium, High. Generated by
    // .plans/tools/gen_colorblind_overlays.py; the three arrays are the only
    // place the type-to-style mapping is written down.
    private val RED_GREEN_OVERLAYS = intArrayOf(
        za.kilowatch.ultimatefilemanager.R.style.ThemeOverlay_UFM_Colorblind_RedGreen_Low,
        za.kilowatch.ultimatefilemanager.R.style.ThemeOverlay_UFM_Colorblind_RedGreen_Medium,
        za.kilowatch.ultimatefilemanager.R.style.ThemeOverlay_UFM_Colorblind_RedGreen_High,
    )

    private val BLUE_YELLOW_OVERLAYS = intArrayOf(
        za.kilowatch.ultimatefilemanager.R.style.ThemeOverlay_UFM_Colorblind_BlueYellow_Low,
        za.kilowatch.ultimatefilemanager.R.style.ThemeOverlay_UFM_Colorblind_BlueYellow_Medium,
        za.kilowatch.ultimatefilemanager.R.style.ThemeOverlay_UFM_Colorblind_BlueYellow_High,
    )

    private val HIGH_CONTRAST_OVERLAYS = intArrayOf(
        za.kilowatch.ultimatefilemanager.R.style.ThemeOverlay_UFM_Colorblind_HighContrast_Low,
        za.kilowatch.ultimatefilemanager.R.style.ThemeOverlay_UFM_Colorblind_HighContrast_Medium,
        za.kilowatch.ultimatefilemanager.R.style.ThemeOverlay_UFM_Colorblind_HighContrast_High,
    )

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
