package za.kilowatch.ultimatefilemanager

import android.app.Application
import android.content.ComponentCallbacks2
import android.util.Log
import com.google.android.material.color.DynamicColors
import za.kilowatch.ultimatefilemanager.network.AdbManager
import za.kilowatch.ultimatefilemanager.network.DlnaDiscovery
import za.kilowatch.ultimatefilemanager.network.NetworkHttpProxyServer
import za.kilowatch.ultimatefilemanager.network.PairingServer
import za.kilowatch.ultimatefilemanager.network.SmbSessionPool
import za.kilowatch.ultimatefilemanager.indexing.IndexingRepository
import za.kilowatch.ultimatefilemanager.support.CrashReportManager
import za.kilowatch.ultimatefilemanager.util.GoRoLog
import java.security.Security
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.apache.sshd.common.util.io.PathUtils
import kotlin.io.path.toPath

/**
 * Global application class for Ultimate File Manager.
 * Ensures long-running services like PairingServer and file indexing system stay alive
 * as long as the application process is running, allowing symmetrical
 * file sharing between paired phones and TVs, plus high-performance file searching.
 */
class UfmApplication : Application() {
    companion object {
        lateinit var instance: UfmApplication
            private set
        
        lateinit var indexingRepository: IndexingRepository
            private set
    }

    private val TAG = "UfmApplication"
    private var pairingServer: PairingServer? = null

    override fun onCreate() {
        // ── Security provider setup ───────────────────────────────────────────────────
        // Must happen BEFORE super.onCreate() so that:
        //  (a) The Android system BC shim is gone before any SDK initialises.
        //  (b) Our full BouncyCastle provider is registered before Firebase's networking
        //      threads start — eliminating the race that causes the NPE in
        //      OpenSSLSocketFactoryImpl.createSocket() (seen when the provider list is
        //      in a transient state during an active TLS handshake on a Firebase
        //      ThreadPoolExecutor worker).
        //
        // FirebaseInitProvider is removed from the manifest (tools:node="remove") so
        // Firebase does NOT auto-start inside super.onCreate(). We call
        // FirebaseApp.initializeApp() manually below, after the provider list is stable.
        try { Security.removeProvider("BC") } catch (_: Exception) {}
        try { Security.addProvider(BouncyCastleProvider()) } catch (_: Exception) {}

        // Install crash handler BEFORE super.onCreate() so any exception thrown
        // during Activity/ContentProvider init is captured.
        CrashReportManager.install(this)

        super.onCreate()

        // Manually initialise Firebase now that the security provider list is fully
        // stable. FirebaseInitProvider was removed from the manifest to prevent it
        // from firing during super.onCreate() before the lines above ran.
        za.kilowatch.ultimatefilemanager.util.Analytics.init(this)
        instance = this
        Log.d(TAG, "Starting global UfmApplication...")

        // Initialize managers that are required immediately on startup (avoid race conditions)
        try {
            za.kilowatch.ultimatefilemanager.settings.HiddenFilesManager.init(this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize hidden files manager", e)
        }
        try {
            za.kilowatch.ultimatefilemanager.recycle.RecycleBinManager.init(this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize recycle bin manager", e)
        }

        // Apply Material You Dynamic Colors — adapts the app's color scheme to the
        // user's wallpaper on Android 12+ (API 31+). Safe no-op on older versions.
        DynamicColors.applyToActivitiesIfAvailable(this)

        // Fix SSHD home folder on Android
        PathUtils.setUserHomeFolderResolver { filesDir.toPath() }

        registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
            // onActivityCreated fires from within super.onCreate() — BEFORE setContentView().
            // Posting to the main looper queues the runnable to execute after the full
            // Activity.onCreate() stack (including setContentView) has unwound, so
            // the view hierarchy is guaranteed to exist when it runs.
            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {
                val themeHelper = za.kilowatch.ultimatefilemanager.settings.ThemeHelper
                if (themeHelper.isAmoled(activity)) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        // 1st: R.id.main — used by ~95% of activities.
                        // 2nd: first child of android.R.id.content — the inflated layout
                        //      root ConstraintLayout, which covers TwinWindowActivity (no
                        //      root id), TvPairingActivity (root id != main), and any
                        //      other screen whose root carries a non-standard id.
                        val root: android.view.View? =
                            activity.findViewById(R.id.main)
                                ?: (activity.findViewById<android.view.ViewGroup>(android.R.id.content))
                                    ?.getChildAt(0)
                        root?.setBackgroundColor(android.graphics.Color.BLACK)
                    }
                }
            }
            override fun onActivityStarted(activity: android.app.Activity) {}
            override fun onActivityResumed(activity: android.app.Activity) {
                val localeHelper = za.kilowatch.ultimatefilemanager.settings.LocaleHelper
                val fontHelper  = za.kilowatch.ultimatefilemanager.settings.FontSizeHelper

                // --- Locale check ---
                val savedLocale = localeHelper.getSavedLocale(activity)
                val expectedLang = if (savedLocale == localeHelper.LOCALE_DEFAULT) {
                    java.util.Locale.getDefault().language
                } else {
                    savedLocale
                }
                val actualLang = activity.resources.configuration.locale.language
                val localeMismatch = (savedLocale != localeHelper.LOCALE_DEFAULT) && (actualLang != expectedLang)

                // --- Font scale check ---
                val expectedScale = when (fontHelper.getSavedSize(activity)) {
                    fontHelper.FONT_SMALL -> 0.85f
                    fontHelper.FONT_LARGE -> 1.15f
                    else                  -> 1.00f
                }
                val actualScale   = activity.resources.configuration.fontScale
                val fontMismatch  = Math.abs(actualScale - expectedScale) > 0.01f

                if (localeMismatch || fontMismatch) {
                    GoRoLog.w("GoRo", "Global Refresh: Recreating ${activity.javaClass.simpleName} " +
                          "(lang=$actualLang→$expectedLang, scale=$actualScale→$expectedScale, localeMismatch=$localeMismatch, fontMismatch=$fontMismatch)")
                    activity.recreate()
                } else {
                    // --- AMOLED background enforcement ---
                    // Dark ↔ AMOLED both use MODE_NIGHT_YES so setDefaultNightMode() sees no
                    // change and never triggers recreation. We therefore enforce black on every
                    // resume when AMOLED is active, and recreate when it was just deactivated
                    // (background is solid black but AMOLED is off).
                    val themeHelper = za.kilowatch.ultimatefilemanager.settings.ThemeHelper
                    val amoledRoot: android.view.View? =
                        activity.findViewById(R.id.main)
                            ?: (activity.findViewById<android.view.ViewGroup>(android.R.id.content))
                                ?.getChildAt(0)

                    if (themeHelper.isAmoled(activity)) {
                        // Enforce pure black — idempotent, safe to call on every resume.
                        amoledRoot?.setBackgroundColor(android.graphics.Color.BLACK)
                    } else {
                        // If AMOLED was just turned off, the root background is still a
                        // solid black ColorDrawable. Recreate to let the layout XML restore
                        // the correct gradient drawable.
                        val bg = amoledRoot?.background
                        if (bg is android.graphics.drawable.ColorDrawable &&
                            bg.color == android.graphics.Color.BLACK) {
                            activity.recreate()
                        }
                    }
                }
            }
            override fun onActivityPaused(activity: android.app.Activity) {}
            override fun onActivityStopped(activity: android.app.Activity) {}
            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: android.app.Activity) {}
        })
        
        // Offload heavy startup tasks (Indexing, Cache Purge, Hidden Files) to a background thread.
        // These tasks perform significant disk IP and can block the UI thread on low-end TVs.
        Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)

            // 1. Initialize file indexing system.
            try {
                indexingRepository = IndexingRepository.getInstance(this)
                indexingRepository.initialize()
                Log.d(TAG, "File indexing system initialized")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize file indexing system", e)
            }

            // 1b. Initialize DLNA client discovery (SSDP engine + listener).
            // Must be called before any DLNA scan; the SSDP multicast socket and
            // listener thread are idle until a scan is requested, so this is cheap.
            try {
                DlnaDiscovery.initialize(this@UfmApplication)
                Log.d(TAG, "DLNA client discovery initialized")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize DLNA discovery", e)
            }
            
            // HiddenFilesManager and RecycleBinManager are initialized synchronously in onCreate to prevent race conditions

            // 4. Purge old temporary files from cache
            purgeOldCacheFiles()

            // 4. Pre-warm AdbManager — runs EncryptedSharedPreferences + Keystore IPC here,
            //    on a background thread, so TerminalActivity.getInstance() returns instantly
            //    and never blocks the main thread.
            try {
                AdbManager.getInstance()
                Log.d(TAG, "AdbManager pre-warmed successfully")
            } catch (e: Exception) {
                Log.e(TAG, "AdbManager pre-warm failed", e)
            }
            
        }.apply { name = "ufm-startup-io"; start() }

        // BouncyCastle is already registered synchronously above (before super.onCreate).
        // This thread only starts PairingServer, which depends on BC being present.
        Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            // Initialize PairingServer (depends on BouncyCastle, already registered on main thread)
            try {
                pairingServer = PairingServer(this)
                pairingServer?.startSecure()
                Log.d(TAG, "Global PairingServer started successfully (HTTPS)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start global PairingServer", e)
            }
        }.apply { name = "ufm-pairing-init"; isDaemon = true; start() }

        // Start the local HTTP proxy server for seekable network file streaming to external players.
        // Uses port 0 (OS-assigned random port) to avoid conflicts.
        Thread {
            try {
                NetworkHttpProxyServer.start()
                Log.d(TAG, "NetworkHttpProxyServer started")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start NetworkHttpProxyServer", e)
            }
        }.apply { name = "ufm-http-proxy-init"; isDaemon = true; start() }

        // Start ANR watchdog after super.onCreate() — main looper must exist first
        CrashReportManager.installAnrWatchdog(this)

        // Initialize Review Prefs
        za.kilowatch.ultimatefilemanager.util.ReviewPrefs.init(this)

        // Schedule daily Recycle Bin cleanup
        try {
            val cleanupRequest = androidx.work.PeriodicWorkRequestBuilder<za.kilowatch.ultimatefilemanager.recycle.RecycleBinCleanupWorker>(
                1, java.util.concurrent.TimeUnit.DAYS
            ).build()
            androidx.work.WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                za.kilowatch.ultimatefilemanager.recycle.RecycleBinCleanupWorker.WORK_NAME,
                androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                cleanupRequest
            )
            Log.d(TAG, "Recycle Bin cleanup worker scheduled")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule Recycle Bin cleanup", e)
        }

        // Register network connectivity listener to purge SMB pool on Wi-Fi network transitions
        registerNetworkCallback()
    }

    private fun registerNetworkCallback() {
        try {
            val cm = getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                cm.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                    private var currentNetwork: android.net.Network? = null

                    override fun onAvailable(network: android.net.Network) {
                        if (currentNetwork != null && currentNetwork != network) {
                            Log.d(TAG, "Network changed -> purging SMB session pool")
                            SmbSessionPool.closeAll()
                        }
                        currentNetwork = network
                    }

                    override fun onLost(network: android.net.Network) {
                        Log.d(TAG, "Network lost -> purging SMB session pool")
                        SmbSessionPool.closeAll()
                        if (currentNetwork == network) currentNetwork = null
                    }
                })
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback", e)
        }
    }

    /**
     * Deletes all files and folders in cacheDir that are older than 1 hour.
     * This covers SMB open cache, upload temps, compression temps, extract temps,
     * and any other temp files the app creates — no pattern list to maintain.
     */
    private fun purgeOldCacheFiles() {
        Log.d(TAG, "Auto-cleanup: checking for old cache files...")

        val threshold = System.currentTimeMillis() - (1 * 60 * 60 * 1000) // 1 hour
        val cache = cacheDir
        if (!cache.exists() || !cache.isDirectory) return

        val files = cache.listFiles() ?: return
        var count = 0

        files.forEach { file ->
            if (file.lastModified() < threshold) {
                if (file.isDirectory) file.deleteRecursively() else file.delete()
                count++
            }
        }
        Log.d(TAG, "Auto-cleanup: Purged $count files/folders")
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            Log.d(TAG, "Low memory — no cleanup needed (hard deletes, no orphan rows)")
        }
        if (level == ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            // Process is about to be killed — cleanly close pooled SMB connections
            // so Windows doesn't hold orphaned sessions until its own idle timeout.
            Log.d(TAG, "TRIM_MEMORY_COMPLETE: closing SMB session pool")
            SmbSessionPool.closeAll()
            NetworkHttpProxyServer.stop()
        }
    }

    /**
     * Call this when a device initiates a PIN-based pairing session.
     */
    fun startPairingMode(pinCode: String) {
        pairingServer?.startPairingMode(pinCode)
    }

    /**
     * Call this to cancel or end a PIN-based pairing session.
     */
    fun stopPairingMode() {
        pairingServer?.stopPairingMode()
    }
}