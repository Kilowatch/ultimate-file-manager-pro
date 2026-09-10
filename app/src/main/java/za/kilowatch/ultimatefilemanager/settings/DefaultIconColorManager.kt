package za.kilowatch.ultimatefilemanager.settings

import android.content.Context
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import za.kilowatch.ultimatefilemanager.util.ThemeColors

/**
 * Resolves the correct default icon tint color for the current theme.
 *
 * Supports user-customized colors per theme via SharedPreferences.
 * If no custom color is set:
 * - When Colorblind Mode is on, the Colorblind palette wins outright (FR-22),
 *   ahead of both of the branches below.
 * - When Material You is enabled (mobile Android 12+), defaults to the dynamic
 *   palette (ThemeColors.primary(context)).
 * - When Material You is disabled, defaults to built-in constants:
 *     Light  → 0xFF1C2B3A (dark slate)
 *     Dark   → 0xFF7DAECC (muted blue-gray)
 *     AMOLED → 0xFFE8C98A (warm gold/tan)
 *
 * > The Dark and AMOLED rows above were transposed until this pass: they listed
 * > the two colours the wrong way round relative to [DEFAULT_DARK] and
 * > [DEFAULT_AMOLED]. Corrected here rather than left to contradict the
 * > constants it documents.
 *
 * Colors are cached in memory after first load so per-item calls during
 * RecyclerView scrolling incur zero I/O.
 */
object DefaultIconColorManager {

    // ── Built-in fallback constants ──────────────────────────────────────────

    const val DEFAULT_LIGHT: Int  = 0xFF1C2B3A.toInt()
    const val DEFAULT_DARK: Int   = 0xFF7DAECC.toInt()
    const val DEFAULT_AMOLED: Int = 0xFFE8C98A.toInt()

    // ── Preference keys ─────────────────────────────────────────────────────

    private const val PREFS = "ufm_prefs"
    private const val KEY_LIGHT  = "default_icon_color_light"
    private const val KEY_DARK   = "default_icon_color_dark"
    private const val KEY_AMOLED = "default_icon_color_amoled"
    private const val NOT_SET = 0

    // ── In-memory cache (null = not yet loaded / needs refresh) ─────────────

    private var cachedLight: Int? = null
    private var cachedDark: Int? = null
    private var cachedAmoled: Int? = null
    private var cachedDynamic: Int? = null          // Material You default (colorPrimary)
    private var cachedDynamicTheme: Int = Int.MIN_VALUE   // theme mode it was resolved for
    private var cachedColorblind: Int? = null       // Colorblind Mode default (overlay colorPrimary)
    private var cachedColorblindTheme: Int = Int.MIN_VALUE
    private var cachedColorblindNight: Boolean = false

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Returns the correct mobile icon tint for the currently active theme,
     * respecting any user-customized color, or falling back to Material You color / built-in default.
     */
    fun getMobileIconTint(context: Context): Int = resolve(context)

    /**
     * Returns the correct TV icon tint for the currently active theme,
     * respecting any user-customized color, or falling back to Material You color / built-in default.
     */
    fun getTvIconTint(context: Context): Int = resolve(context)

    /**
     * Returns the custom color stored for the given theme, or `null` if the
     * user has not customized it (i.e. the default should be used).
     */
    fun getCustomColor(context: Context, theme: Int): Int? {
        val effectiveTheme = if (theme == ThemeHelper.THEME_SYSTEM) {
            val isNight = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
            if (isNight) ThemeHelper.THEME_DARK else ThemeHelper.THEME_LIGHT
        } else {
            theme
        }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return when (effectiveTheme) {
            ThemeHelper.THEME_LIGHT  -> loadOrCached(KEY_LIGHT,  prefs, cachedLight)  { cachedLight  = it }
            ThemeHelper.THEME_DARK   -> loadOrCached(KEY_DARK,   prefs, cachedDark)   { cachedDark   = it }
            ThemeHelper.THEME_AMOLED -> loadOrCached(KEY_AMOLED, prefs, cachedAmoled) { cachedAmoled = it }
            else -> null
        }
    }

    /**
     * Store a custom icon color for the given theme. Persists to
     * SharedPreferences and updates the in-memory cache.
     */
    fun setCustomColor(context: Context, theme: Int, color: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(prefKey(theme), color)
            .apply()
        updateCache(theme, color)
    }

    /**
     * Remove the custom color for the given theme, reverting to the default.
     * Updates both SharedPreferences and in-memory cache.
     */
    fun resetCustomColor(context: Context, theme: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(prefKey(theme))
            .apply()
        updateCache(theme, null)
    }

    /**
     * Remove ALL custom icon colors, reverting every theme to its default.
     */
    fun resetAllCustomColors(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_LIGHT)
            .remove(KEY_DARK)
            .remove(KEY_AMOLED)
            .apply()
        invalidateCache()
    }

    /**
     * Returns the built-in default color for the given [theme].
     */
    fun getDefaultColor(theme: Int): Int {
        return when (theme) {
            ThemeHelper.THEME_LIGHT  -> DEFAULT_LIGHT
            ThemeHelper.THEME_DARK   -> DEFAULT_DARK
            ThemeHelper.THEME_AMOLED -> DEFAULT_AMOLED
            ThemeHelper.THEME_SYSTEM -> {
                val mode = AppCompatDelegate.getDefaultNightMode()
                if (mode == AppCompatDelegate.MODE_NIGHT_YES) DEFAULT_DARK else DEFAULT_LIGHT
            }
            else -> DEFAULT_DARK
        }
    }

    /**
     * Overload for context callers.
     */
    fun getDefaultColor(context: Context, theme: Int = ThemeHelper.getSavedTheme(context)): Int {
        return getDefaultColor(theme)
    }

    /**
     * Forces the in-memory cache to reload from SharedPreferences on the next
     * access. Call after changing any custom color preference.
     */
    fun invalidateCache() {
        cachedLight = null
        cachedDark = null
        cachedAmoled = null
        cachedDynamic = null
        cachedDynamicTheme = Int.MIN_VALUE
        cachedColorblind = null
        cachedColorblindTheme = Int.MIN_VALUE
        // The null above already forces the next resolve() to recompute; this is
        // reset alongside it so "invalidate everything" leaves no stale key
        // behind for a future caller that checks the key before the value.
        cachedColorblindNight = false
    }

    // ── Internal helpers ────────────────────────────────────────────────────

    /**
     * Resolve the correct color for the current theme.
     * Order: Colorblind Mode > Material You dynamic (if enabled) > user custom
     * (when Material You off) > built-in default.
     */
    private fun resolve(context: Context): Int {
        val theme = ThemeHelper.getSavedTheme(context)

        // 0. Colorblind Mode wins over BOTH of the branches below (FR-22).
        //
        // This branch exists because icon tints are applied in the adapters,
        // AFTER inflation, so no theme overlay can reach them: the overlay fixes
        // every `?attr/` read in XML and every ColorblindPalette accessor, but an
        // explicit `setColorFilter(DefaultIconColorManager.getMobileIconTint(...))`
        // bypasses the theme entirely. Without this, the user's custom Default
        // Icon Color (branch 2) would keep painting icons while the mode is on --
        // which is the "Default Icon Colors are still overwriting" half of the
        // FR-22 report, seen on icons rather than on buttons.
        //
        // The value is read from `colorPrimary` rather than from a new palette
        // accessor, so icons and buttons resolve to the SAME colour by
        // construction. That is only meaningful because the Colorblind overlay
        // now emits colorPrimary = that type-and-strength's focus fill (T080) and
        // because Material You is suppressed while the mode is on (T077/T078) --
        // otherwise this read would return the wallpaper's colour. Ordering here
        // therefore depends on those two suppressions; they are not independent
        // fixes for the same symptom, they are the precondition for this one.
        //
        // Cached per theme mode like the Material You branch below. `theme` alone
        // is not a sufficient key for a type/strength change, which is why
        // ColorblindActivity calls invalidateCache() when either changes -- the
        // same call the Default Icon Color pickers already make.
        //
        // The night bit is part of the key, and it has to be. `theme` is the
        // SAVED mode, so under THEME_SYSTEM it reads 2 both before and after the
        // OS switches to night -- the type/strength key would not move, and the
        // day-plate fill would keep being served on a night surface. That is an
        // NFR-01 contrast failure that survives indefinitely rather than a
        // cosmetic mismatch, because the value cached here is an already-resolved
        // colour: it does depend on the configuration, the old key just could not
        // see that.
        if (ColorblindPrefs.isEnabled(context)) {
            val isNight = (context.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            if (cachedColorblind == null ||
                cachedColorblindTheme != theme ||
                cachedColorblindNight != isNight
            ) {
                cachedColorblind = ThemeColors.primary(context)
                cachedColorblindTheme = theme
                cachedColorblindNight = isNight
            }
            return cachedColorblind!!
        }

        // 1. Material You: default icon tint follows the active dynamic palette
        //    (colorPrimary). Mobile/Android 12+ only — TV always keeps the fixed
        //    per-mode defaults, and the value is cached per theme mode so it
        //    survives light/dark/AMOLED switches without per-item resolution.
        if (MaterialYouPrefs.isEnabled(context) &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !DeviceUtils.isTvDevice(context)
        ) {
            if (cachedDynamic == null || cachedDynamicTheme != theme) {
                cachedDynamic = ThemeColors.primary(context)
                cachedDynamicTheme = theme
            }
            return cachedDynamic!!
        }

        // 2. Check user custom color (loads + caches from prefs)
        getCustomColor(context, theme)?.let { return it }

        // 3. Fall back to built-in default
        return getDefaultColor(theme)
    }

    private fun prefKey(theme: Int): String = when (theme) {
        ThemeHelper.THEME_LIGHT  -> KEY_LIGHT
        ThemeHelper.THEME_DARK   -> KEY_DARK
        ThemeHelper.THEME_AMOLED -> KEY_AMOLED
        else -> KEY_DARK
    }

    private fun updateCache(theme: Int, color: Int?) {
        when (theme) {
            ThemeHelper.THEME_LIGHT  -> cachedLight  = color
            ThemeHelper.THEME_DARK   -> cachedDark   = color
            ThemeHelper.THEME_AMOLED -> cachedAmoled = color
        }
    }

    /**
     * Load a cached value from SharedPreferences on first access.
     * Returns `null` when no custom color is stored (meaning "use default").
     */
    private fun loadOrCached(
        key: String,
        prefs: android.content.SharedPreferences,
        cached: Int?,
        onCache: (Int?) -> Unit
    ): Int? {
        if (cached != null) return cached
        val stored = prefs.getInt(key, NOT_SET)
        val value: Int? = if (stored == NOT_SET || stored == 0) null else stored
        onCache(value)
        return value
    }
}
