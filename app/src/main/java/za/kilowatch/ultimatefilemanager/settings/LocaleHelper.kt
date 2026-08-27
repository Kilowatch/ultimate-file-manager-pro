package za.kilowatch.ultimatefilemanager.settings

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Helper to manage and apply the app-wide locale (language) preference.
 */
object LocaleHelper {

    private const val PREFS = "ufm_prefs"
    private const val KEY_LOCALE = "app_locale"

    const val LOCALE_EN = "en"
    const val LOCALE_DE = "de"
    const val LOCALE_JA = "ja"
    const val LOCALE_AR = "ar"
    const val LOCALE_ES = "es"
    const val LOCALE_FR = "fr"
    const val LOCALE_HI = "hi"
    const val LOCALE_ID = "id"
    const val LOCALE_KO = "ko"
    const val LOCALE_PT = "pt"
    const val LOCALE_RU = "ru"
    const val LOCALE_TR = "tr"
    const val LOCALE_UK = "uk"
    const val LOCALE_IT = "it"
    const val LOCALE_NL = "nl"
    const val LOCALE_DEFAULT = "system"

    /**
     * Set to true when the language changes.
     * Activities check this in onResume and call recreate() if needed.
     */
    var restartPending: Boolean = false

    /**
     * In-memory cache for the saved locale code.
     * Populated on the first [getSavedLocale] call; invalidated on every [save].
     * This prevents [getSharedPreferences] from being called on the main thread
     * in the [android.app.Application.ActivityLifecycleCallbacks] hot path,
     * which caused lock contention with Firebase's background SharedPreferences
     * access and resulted in main-thread ANRs.
     */
    @Volatile private var cachedLocale: String? = null

    fun getSavedLocale(context: Context): String {
        cachedLocale?.let { return it }
        val value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LOCALE, LOCALE_DEFAULT) ?: LOCALE_DEFAULT
        cachedLocale = value
        return value
    }

    fun save(context: Context, localeCode: String) {
        cachedLocale = localeCode   // update cache synchronously before the write
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LOCALE, localeCode)
            .apply()
    }

    /**
     * Returns a new [Context] with the chosen [Locale] applied.
     * Pass the result to [super.attachBaseContext].
     */
    fun applyTo(context: Context): Context {
        val lang = getSavedLocale(context)
        if (lang == LOCALE_DEFAULT) return context

        // forLanguageTag treats the code as BCP-47, so "id" stays "id" and correctly
        // resolves to the values-id resource folder. Locale(lang) maps "id" → "in"
        // internally (old Java ISO legacy), causing Indonesian to silently fall back to English.
        val locale = Locale.forLanguageTag(lang)

        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)

        return context.createConfigurationContext(config)
    }

    /**
     * Convenience to wrap the context for both Font Size and Locale.
     */
    fun wrap(context: Context): Context {
        return applyTo(FontSizeHelper.applyTo(context))
    }
}
