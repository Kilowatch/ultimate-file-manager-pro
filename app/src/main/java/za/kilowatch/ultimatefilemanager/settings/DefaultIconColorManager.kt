package za.kilowatch.ultimatefilemanager.settings

import android.content.Context
import za.kilowatch.ultimatefilemanager.util.ThemeColors

/**
 * Resolves the correct default icon tint color for the current theme.
 *
 * Supports user-customized colors per theme via SharedPreferences.
 * If no custom color is set, it defaults to the Material You dynamic color
 * (ThemeColors.primary(context)).
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

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Returns the correct mobile icon tint for the currently active theme,
     * respecting any user-customized color, or falling back to Material You color.
     */
    fun getMobileIconTint(context: Context): Int = resolve(context)

    /**
     * Returns the correct TV icon tint for the currently active theme,
     * respecting any user-customized color, or falling back to Material You color.
     */
    fun getTvIconTint(context: Context): Int = resolve(context)

    /**
     * Returns the custom color stored for the given theme, or `null` if the
     * user has not customized it (i.e. the Material You default should be used).
     */
    fun getCustomColor(context: Context, theme: Int): Int? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return when (theme) {
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
     * Remove the custom color for the given theme, reverting to the Material You
     * default. Updates both SharedPreferences and in-memory cache.
     */
    fun resetCustomColor(context: Context, theme: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(prefKey(theme))
            .apply()
        updateCache(theme, null)
    }

    /**
     * Remove ALL custom icon colors, reverting every theme to its Material You
     * default.
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
     * Returns the active Material You / theme primary default color.
     */
    fun getDefaultColor(context: Context): Int = ThemeColors.primary(context)

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
    }

    // ── Internal helpers ────────────────────────────────────────────────────

    /**
     * Resolve the correct color for the current theme.
     * Order: user custom > Material You color (colorPrimary).
     */
    private fun resolve(context: Context): Int {
        val theme = ThemeHelper.getSavedTheme(context)

        // 1. Check user custom color (loads + caches from prefs)
        getCustomColor(context, theme)?.let { return it }

        // 2. Default: Material You color (ThemeColors.primary)
        if (cachedDynamic == null || cachedDynamicTheme != theme) {
            cachedDynamic = ThemeColors.primary(context)
            cachedDynamicTheme = theme
        }
        return cachedDynamic!!
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
