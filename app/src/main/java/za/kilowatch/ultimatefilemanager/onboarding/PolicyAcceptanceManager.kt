package za.kilowatch.ultimatefilemanager.onboarding

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Manages user acceptance of Terms of Service and Privacy Policy, as well as
 * onboarding completion status stored in "acceptance_prefs".
 *
 * Provides an in-memory cache to eliminate synchronous [android.content.SharedPreferences]
 * disk I/O and lock contention ([android.app.SharedPreferencesImpl.awaitLoadedLocked])
 * from the main-thread cold start path in [PolicyWelcomeActivity.onCreate],
 * [MainActivity.onCreate], and [WelcomeActivity.onCreate].
 */
object PolicyAcceptanceManager {

    private const val PREFS_NAME = "acceptance_prefs"
    private const val KEY_TERMS_TIME = "terms_accepted_time"
    private const val KEY_PRIVACY_TIME = "privacy_accepted_time"
    private const val KEY_ONBOARDING_COMPLETE = "onboarding_complete"

    @Volatile private var cachedTermsTime: Long? = null
    @Volatile private var cachedPrivacyTime: Long? = null
    @Volatile private var cachedOnboardingComplete: Boolean? = null

    private val observers = CopyOnWriteArrayList<() -> Unit>()

    private val bgExecutor by lazy {
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "ufm-policy-acceptance-prefs").apply { isDaemon = true }
        }
    }

    /**
     * Pre-warms the in-memory cache and underlying SharedPreferences file from disk.
     * Safe to call from [za.kilowatch.ultimatefilemanager.UfmApplication.onCreate] before
     * the ANR watchdog arms and before any Activity cold-starts.
     *
     * Synchronously calling getLong/getBoolean forces [android.app.SharedPreferencesImpl]
     * to complete its background disk load during application initialization rather than
     * stalling the main looper on the first Activity's [android.app.Activity.onCreate].
     */
    fun init(context: Context) {
        if (cachedTermsTime != null && cachedPrivacyTime != null && cachedOnboardingComplete != null) return
        val appContext = context.applicationContext
        try {
            val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val terms = prefs.getLong(KEY_TERMS_TIME, 0L)
            val privacy = prefs.getLong(KEY_PRIVACY_TIME, 0L)
            val onboarding = prefs.getBoolean(KEY_ONBOARDING_COMPLETE, false)
            cachedTermsTime = terms
            cachedPrivacyTime = privacy
            cachedOnboardingComplete = onboarding
            notifyObservers()
        } catch (_: Throwable) {
            if (cachedTermsTime == null) cachedTermsTime = 0L
            if (cachedPrivacyTime == null) cachedPrivacyTime = 0L
            if (cachedOnboardingComplete == null) cachedOnboardingComplete = false
        }
    }

    /** Clears the in-memory cache, e.g. after a settings reset or test. */
    fun invalidateCache() {
        cachedTermsTime = null
        cachedPrivacyTime = null
        cachedOnboardingComplete = null
    }

    fun addObserver(observer: () -> Unit) {
        observers.add(observer)
    }

    fun removeObserver(observer: () -> Unit) {
        observers.remove(observer)
    }

    private fun notifyObservers() {
        if (observers.isEmpty()) return
        Handler(Looper.getMainLooper()).post {
            observers.forEach { it.invoke() }
        }
    }

    // ── Policy Acceptance ───────────────────────────────────────────────────

    fun arePoliciesAccepted(context: Context): Boolean {
        val terms = getTermsAcceptedTime(context)
        val privacy = getPrivacyAcceptedTime(context)
        return (terms > 0L && privacy > 0L)
    }

    fun getTermsAcceptedTime(context: Context): Long {
        cachedTermsTime?.let { return it }
        val appContext = context.applicationContext
        if (Looper.myLooper() == Looper.getMainLooper()) {
            bgExecutor.execute {
                try {
                    val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    cachedTermsTime = prefs.getLong(KEY_TERMS_TIME, 0L)
                    notifyObservers()
                } catch (_: Throwable) {}
            }
            return 0L
        }
        return try {
            val terms = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getLong(KEY_TERMS_TIME, 0L)
            cachedTermsTime = terms
            terms
        } catch (_: Throwable) {
            0L
        }
    }

    fun getPrivacyAcceptedTime(context: Context): Long {
        cachedPrivacyTime?.let { return it }
        val appContext = context.applicationContext
        if (Looper.myLooper() == Looper.getMainLooper()) {
            bgExecutor.execute {
                try {
                    val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    cachedPrivacyTime = prefs.getLong(KEY_PRIVACY_TIME, 0L)
                    notifyObservers()
                } catch (_: Throwable) {}
            }
            return 0L
        }
        return try {
            val privacy = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getLong(KEY_PRIVACY_TIME, 0L)
            cachedPrivacyTime = privacy
            privacy
        } catch (_: Throwable) {
            0L
        }
    }

    fun recordTermsAccepted(context: Context, time: Long = System.currentTimeMillis()) {
        cachedTermsTime = time
        val appContext = context.applicationContext
        try {
            appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_TERMS_TIME, time)
                .apply()
        } catch (_: Throwable) {}
        notifyObservers()
    }

    fun recordPrivacyAccepted(context: Context, time: Long = System.currentTimeMillis()) {
        cachedPrivacyTime = time
        val appContext = context.applicationContext
        try {
            appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_PRIVACY_TIME, time)
                .apply()
        } catch (_: Throwable) {}
        notifyObservers()
    }

    fun recordBothAccepted(context: Context, time: Long = System.currentTimeMillis()) {
        cachedTermsTime = time
        cachedPrivacyTime = time
        val appContext = context.applicationContext
        try {
            appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_TERMS_TIME, time)
                .putLong(KEY_PRIVACY_TIME, time)
                .apply()
        } catch (_: Throwable) {}
        notifyObservers()
    }

    // ── Onboarding Status ───────────────────────────────────────────────────

    fun isOnboardingComplete(context: Context): Boolean {
        cachedOnboardingComplete?.let { return it }
        val appContext = context.applicationContext
        if (Looper.myLooper() == Looper.getMainLooper()) {
            bgExecutor.execute {
                try {
                    val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    cachedOnboardingComplete = prefs.getBoolean(KEY_ONBOARDING_COMPLETE, false)
                    notifyObservers()
                } catch (_: Throwable) {}
            }
            return false
        }
        return try {
            val complete = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_ONBOARDING_COMPLETE, false)
            cachedOnboardingComplete = complete
            complete
        } catch (_: Throwable) {
            false
        }
    }

    fun setOnboardingComplete(context: Context, complete: Boolean = true) {
        cachedOnboardingComplete = complete
        val appContext = context.applicationContext
        try {
            appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ONBOARDING_COMPLETE, complete)
                .apply()
        } catch (_: Throwable) {}
        notifyObservers()
    }
}
