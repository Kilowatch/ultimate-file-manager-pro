package za.kilowatch.ultimatefilemanager.support

import android.app.Application
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import za.kilowatch.ultimatefilemanager.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * CrashReportManager
 *
 * Installs a custom UncaughtExceptionHandler that writes a structured JSON crash
 * report to filesDir/crash_reports/ before delegating to the default handler.
 *
 * Also starts a lightweight ANR watchdog daemon thread that detects a blocked
 * main looper and writes an ANR report (no busy-loop; ~1 ms CPU wake every 5 s).
 *
 * On next app launch, CrashReportDialogHelper reads these files and offers the
 * user a chance to submit or discard them.
 */
object CrashReportManager {

    private const val TAG = "CrashReportManager"
    private const val REPORT_DIR = "crash_reports"
    private const val WATCHDOG_INTERVAL_MS = 5_000L
    private const val WATCHDOG_TIMEOUT_MS  = 5_000L
    // Class-name prefixes identifying the Android platform / Java runtime / Google Play
    // infrastructure. A main-thread stack made up entirely of these frames means the thread
    // is executing framework or Google Play library code only — no app business-logic frames —
    // so a block there is a system-side wait the app cannot fix (e.g. a synchronous call to
    // the system server during activity launch, or Google Play's injected licensing
    // (PairIP `com.pairip.licensecheck`) service-disconnect handler). R8 obfuscates app classes
    // and app-owned bundled libraries (Media3, OkHttp, …) to short names, which never match
    // these prefixes, so any real app-code frame still fails the pure-platform test below.
    // Google Play's own libraries are listed explicitly because they ship protected / with
    // keep-rules and retain their full `com.google.*` / `com.pairip.*` class names in release
    // builds — a stack that is entirely Google Play frames is still a library-side wait the
    // app cannot act on.
    private val PLATFORM_PREFIXES = listOf(
        "android.", "com.android.", "java.", "javax.", "dalvik.",
        "libcore.", "jdk.", "sun.", "org.apache.", "org.json",
        "com.google.", "com.pairip."
    )

    // App package prefix — any frame from the app's own classes means genuine app
    // business logic is on the stack, so a main-thread block is never a system-side
    // wait and must still be reported.
    private const val APP_PACKAGE = "za.kilowatch.ultimatefilemanager"

    private const val PREFS_NAME = "crash_report_prefs"
    private const val KEY_ENABLED = "crash_reporting_enabled"
    private const val KEY_VERSION_CODE = "recorded_version_code"
    private const val KEY_REPORTED_FINGERPRINTS = "reported_fingerprints"

    /** Returns true if Crash & ANR Reporting is enabled (defaults to true). */
    fun isEnabled(context: android.content.Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_ENABLED, true)
    }

    /** Enables or disables Crash & ANR Reporting. */
    fun setEnabled(context: android.content.Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (!enabled && context is Application) {
            deleteAllPendingReports(context)
        }
    }

    /** Deletes all pending report files. */
    fun deleteAllPendingReports(app: Application) {
        try {
            getReportDir(app).deleteRecursively()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete report dir", e)
        }
    }

    /** Generates a unique fingerprint hash for a crash/ANR based on its top app stack trace lines. */
    fun generateFingerprint(type: String, exceptionClass: String, traceText: String): String {
        val appPackage = "za.kilowatch.ultimatefilemanager"

        // For ANR, isolate ONLY the main thread stack trace section
        val targetText = if (type == "anr") {
            if (traceText.contains("Thread: main")) {
                traceText.substringAfter("Thread: main").substringBefore("Thread: ")
            } else {
                traceText.substringBefore("Thread: ")
            }
        } else {
            traceText
        }

        val appLines = targetText.lines()
            .map { it.trim() }
            .filter { it.contains(appPackage) && it.startsWith("at ") }
            .map { it.substringBefore(":") } // strip line numbers for signature stability
            .take(3)

        val seed = if (appLines.isNotEmpty()) {
            "$type:" + appLines.joinToString("|")
        } else {
            "$type:$exceptionClass:${targetText.take(200)}"
        }

        return try {
            val md = java.security.MessageDigest.getInstance("MD5")
            val digest = md.digest(seed.toByteArray(Charsets.UTF_8))
            digest.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            seed.hashCode().toString()
        }
    }

    /** Returns true if a specific crash/ANR fingerprint has already been handled for this app version. */
    fun isFingerprintReported(context: android.content.Context, fingerprint: String): Boolean {
        if (fingerprint.isEmpty()) return false
        val prefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        val currentVersion = BuildConfig.VERSION_CODE
        val savedVersion = prefs.getInt(KEY_VERSION_CODE, -1)

        if (savedVersion != currentVersion) {
            // Version changed! Automatically reset reported fingerprints for the new release
            prefs.edit()
                .putInt(KEY_VERSION_CODE, currentVersion)
                .putStringSet(KEY_REPORTED_FINGERPRINTS, emptySet())
                .apply()
            return false
        }

        val set = prefs.getStringSet(KEY_REPORTED_FINGERPRINTS, emptySet()) ?: emptySet()
        return set.contains(fingerprint)
    }

    /** Marks a specific crash/ANR fingerprint as handled (sent or cancelled) for this app version. */
    fun markFingerprintReported(context: android.content.Context, fingerprint: String) {
        if (fingerprint.isEmpty()) return
        val prefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        val currentVersion = BuildConfig.VERSION_CODE
        val savedVersion = prefs.getInt(KEY_VERSION_CODE, -1)

        val set = if (savedVersion != currentVersion) {
            mutableSetOf()
        } else {
            (prefs.getStringSet(KEY_REPORTED_FINGERPRINTS, emptySet()) ?: emptySet()).toMutableSet()
        }

        set.add(fingerprint)
        prefs.edit()
            .putInt(KEY_VERSION_CODE, currentVersion)
            .putStringSet(KEY_REPORTED_FINGERPRINTS, set)
            .apply()
        Log.d(TAG, "Marked fingerprint '$fingerprint' as reported for version $currentVersion")
    }

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Call from Application.onCreate() — before super — to register the crash handler.
     */
    fun install(app: Application) {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            if (isEnabled(app)) {
                try {
                    writeCrashReport(app, thread, throwable)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to write crash report", e)
                }
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
        Log.d(TAG, "Crash handler installed")
    }

    /**
     * Call from Application.onCreate() to start the ANR watchdog daemon thread.
     * The watchdog has zero performance impact: it sleeps for WATCHDOG_INTERVAL_MS
     * between heartbeats and only wakes briefly to post to the main looper.
     */
    fun installAnrWatchdog(app: Application) {
        val watchdog = AnrWatchdogThread(app)
        watchdog.isDaemon = true
        watchdog.name = "ufm-anr-watchdog"
        watchdog.start()
        Log.d(TAG, "ANR watchdog started (interval=${WATCHDOG_INTERVAL_MS}ms, timeout=${WATCHDOG_TIMEOUT_MS}ms)")
    }

    /** Returns true if any pending crash/ANR report file exists. */
    fun hasPendingReport(app: Application): Boolean =
        getReportDir(app).listFiles()?.any { it.extension == "json" } == true

    /** Returns the first pending report file, or null if none exists. */
    fun getPendingReportFile(app: Application): File? =
        getReportDir(app).listFiles()?.firstOrNull { it.extension == "json" }

    /** Deletes a specific report file and removes the directory if empty. */
    fun deleteReport(file: File) {
        try {
            file.delete()
            val parent = file.parentFile
            if (parent?.listFiles()?.isEmpty() == true) parent.delete()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete report file", e)
        }
    }

    /**
     * Parses the pending report JSON and returns a map of field -> value suitable
     * for building the multipart POST body.
     */
    fun parseReport(file: File): Map<String, String> {
        return try {
            val json = JSONObject(file.readText())
            buildMap {
                json.keys().forEach { key -> put(key, json.optString(key, "")) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse report", e)
            emptyMap()
        }
    }

    // ── Private helpers ─────────────────────────────────────────────────────

    private fun getReportDir(app: Application): File =
        File(app.filesDir, REPORT_DIR).also { it.mkdirs() }

    private fun writeCrashReport(app: Application, thread: Thread, throwable: Throwable) {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val stackTraceStr = sw.toString()

        val fingerprint = generateFingerprint("crash", throwable.javaClass.name, stackTraceStr)

        val json = buildReportJson(
            type           = "crash",
            threadName     = thread.name,
            exceptionClass = throwable.javaClass.name,
            message        = throwable.message ?: "",
            stackTrace     = stackTraceStr,
            anrReason      = null,
            allThreads     = null,
            fingerprint    = fingerprint
        )

        writeReportFile(app, "crash", json)
    }

    fun writeAnrReport(app: Application, allThreadsText: String) {
        val fingerprint = generateFingerprint("anr", "", allThreadsText)
        if (isFingerprintReported(app, fingerprint)) {
            Log.d(TAG, "ANR fingerprint '$fingerprint' already reported on version ${BuildConfig.VERSION_CODE} — skipping")
            return
        }

        val json = buildReportJson(
            type           = "anr",
            threadName     = "main",
            exceptionClass = "",
            message        = "",
            stackTrace     = "",
            anrReason      = "Main thread blocked >${WATCHDOG_TIMEOUT_MS}ms",
            allThreads     = allThreadsText,
            fingerprint    = fingerprint
        )
        writeReportFile(app, "anr", json)
    }

    private fun buildReportJson(
        type: String,
        threadName: String,
        exceptionClass: String,
        message: String,
        stackTrace: String,
        anrReason: String?,
        allThreads: String?,
        fingerprint: String
    ): JSONObject = JSONObject().apply {
        put("type",            type)
        put("subject",         if (type == "crash") "[Crash] $exceptionClass" else "[ANR] Main thread blocked")
        put("timestamp",       System.currentTimeMillis() / 1000L)
        put("app_version",     BuildConfig.VERSION_NAME)
        put("app_code",        BuildConfig.VERSION_CODE.toString())
        put("sdk_version",     Build.VERSION.SDK_INT.toString())
        put("manufacturer",    Build.MANUFACTURER)
        put("device_model",    Build.MODEL)
        put("thread_name",     threadName)
        put("exception_class", exceptionClass)
        put("message",         message)
        put("stack_trace",     stackTrace)
        put("anr_reason",      anrReason ?: "")
        put("all_threads",     allThreads ?: "")
        put("fingerprint",     fingerprint)
    }

    private fun writeReportFile(app: Application, prefix: String, json: JSONObject) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(getReportDir(app), "${prefix}_${timestamp}.json")
        file.writeText(json.toString(2))
        Log.d(TAG, "Report written: ${file.name}")
    }

    // ── ANR Watchdog ────────────────────────────────────────────────────────

    private class AnrWatchdogThread(private val app: Application) : Thread() {

        @Volatile private var lastTickTimestamp = SystemClock.uptimeMillis()
        @Volatile private var reportWrittenThisSession = false

        override fun run() {
            val mainHandler = android.os.Handler(Looper.getMainLooper())
            val ticker = object : Runnable {
                override fun run() {
                    lastTickTimestamp = SystemClock.uptimeMillis()
                    mainHandler.postDelayed(this, 1_000L)
                }
            }
            mainHandler.post(ticker)

            while (!isInterrupted) {
                try {
                    sleep(1_000L)
                } catch (_: InterruptedException) {
                    break
                }

                if (!isEnabled(app)) continue

                val blockedDuration = SystemClock.uptimeMillis() - lastTickTimestamp
                if (blockedDuration >= WATCHDOG_TIMEOUT_MS) {
                    val mainThread = Looper.getMainLooper().thread
                    val mainStackTrace = mainThread.stackTrace
                    val topFrame = mainStackTrace.firstOrNull()

                    // The main thread is NOT blocked (false positive) when its entire stack
                    // is framework/platform code with no app or bundled-library frames:
                    //  1. It is idling in the looper — top frame is nativePollOnce, a normal
                    //     wait for the next message (e.g. when coming back from deep sleep/Doze).
                    //  2. It is waiting on the system server during a framework-driven
                    //     operation, e.g. ActivityThread.createBaseContextForActivity ->
                    //       - ActivityClient.getDisplayId (BinderProxy.transact), or
                    //       - SystemProperties.get (native_get into the property service).
                    //     App code has not even started running; the wait is system-side and
                    //     the app cannot control it. R8 keeps platform class names, so a stack
                    //     containing any obfuscated app/library frame fails this test and is
                    //     still reported as a real freeze.
                    val isIdleInLooper = topFrame?.methodName == "nativePollOnce"
                    val isPureFrameworkStack = mainStackTrace.isNotEmpty() &&
                        mainStackTrace.none { frame ->
                            PLATFORM_PREFIXES.none { frame.className.startsWith(it) }
                        }

                    // 3. It is blocked reading a compiled layout XML from the APK while
                    //    showing a dialog — e.g. Dialog.show() -> AlertDialog.onCreate ->
                    //    setContentView -> LayoutInflater.inflate/parseInclude ->
                    //    Resources.getLayout -> AssetManager.nativeOpenXmlAsset. This is a
                    //    framework disk/I/O resource read (cold resource cache, slow or busy
                    //    storage), not app business logic: no app frame is executing code,
                    //    and other threads are idle (no CPU saturation). Genuine freezes that
                    //    also surface inside nativeOpenXmlAsset — e.g. unbounded FFmpeg
                    //    thumbnail decoding, which reaches the read via Resources.loadDrawable
                    //    during RecyclerView adapter inflation — have no Dialog.show frame and
                    //    no Resources.getLayout frame, so they still fail this test and are
                    //    reported.
                    val isDialogLayoutResourceStall =
                        topFrame?.className == "android.content.res.AssetManager" &&
                        (topFrame?.methodName == "nativeOpenXmlAsset" || topFrame?.methodName == "openXmlBlockAsset") &&
                        mainStackTrace.any { it.className == "android.app.Dialog" && it.methodName == "show" } &&
                        mainStackTrace.any { it.className == "android.content.res.Resources" && it.methodName == "getLayout" }

                    // 4. The watchdog's OWN heartbeat ticker is the discriminator between a
                    //    false positive and a genuine freeze. The ticker is a self-reposting
                    //    Runnable posted to the main looper that only sets `lastTickTimestamp`
                    //    and re-posts itself via `mainHandler.postDelayed(this, 1_000L)`. If
                    //    `tickerJustRan` is true, the main looper processed the ticker within
                    //    the last second, so it is demonstrably responsive NOW — the stack
                    //    sampled at this instant cannot represent a >5 s block.
                    //
                    //    That happens through a sampling race: the watchdog wakes, computes
                    //    `blockedDuration` from a `lastTickTimestamp` that is stale because the
                    //    looper was genuinely stalled, then samples the main thread AFTER it
                    //    recovered and began draining its queued backlog. The sampled frame is
                    //    then fast post-stall work, not the original blocker — e.g. constructing
                    //    a Coil `ImageRequest` while filling a Storage Analyzer category list
                    //    (reported from a Vestel Cosmos TV, app 1.7.7), whose top frame
                    //    (`kotlin.collections.EmptyMap.size`) returns 0 in a single instruction
                    //    and physically cannot hold the main thread for 5 s.
                    //
                    //    The earlier heartbeat self-sample fix documented two specific stack
                    //    shapes — the ticker caught mid `postDelayed` and its bare `run()`
                    //    sitting on `Handler.handleCallback`. Both are subsumed by this general
                    //    gate: `tickerJustRan` is true in exactly those samples, and the main
                    //    looper is responsive in them. Genuine freezes keep the ticker starved,
                    //    so `tickerJustRan` stays false (the watchdog only checks every 1 s, so
                    //    by the time `blockedDuration` reaches 5 s any prior tick is >4 s old)
                    //    and they still fail this gate and are reported.
                    val tickerJustRan = SystemClock.uptimeMillis() - lastTickTimestamp < 1_000L

                    // 5. The system is instantiating a Service on the main thread (e.g. a
                    //    WorkManager job firing via JobScheduler ->
                    //    androidx.work.impl.background.systemjob.SystemJobService) and the
                    //    bundled library's one-time class-instantiation cost — static class
                    //    initializer (`<clinit>`) or constructor (`<init>`) — is slow on a
                    //    low-end device. Service creation is always framework-driven —
                    //    ActivityThread.handleCreateService -> AppComponentFactory.
                    //    instantiateService -> Class.newInstance -> <clinit>/<init> — and the
                    //    block sits inside library class loading / static-init / construction
                    //    with no app frame executing, so the app cannot act on it. The
                    //    watchdog can sample either frame of the same instantiation: the
                    //    earlier report caught `<clinit>`; a later one (reported from a TCL
                    //    Smart TV Pro, SDK 34, app 1.7.6) caught `SystemJobService.<init>`.
                    //    Both are the same one-time cost. A genuine freeze that originates in
                    //    app code keeps an app frame (`za.kilowatch.ultimatefilemanager.*`) on
                    //    the stack — including an app Service's own `<init>`, whose class
                    //    name starts with the app package — and still fails this test.
                    val isServiceClassInitStall =
                        (topFrame?.methodName == "<clinit>" || topFrame?.methodName == "<init>") &&
                        mainStackTrace.any { it.className == "java.lang.Class" && it.methodName == "newInstance" } &&
                        (mainStackTrace.any { it.className == "android.app.AppComponentFactory" && it.methodName == "instantiateService" } ||
                         mainStackTrace.any { it.className == "android.app.ActivityThread" && it.methodName == "handleCreateService" }) &&
                        mainStackTrace.none { it.className.startsWith(APP_PACKAGE) }

                    // 6. The main thread is resolving the property getter of the framework's
                    //    default button state-list-animator via reflection the first time a
                    //    MaterialButton is attached to a window — e.g. MaterialButton.
                    //    refreshDrawableState -> View.refreshDrawableState -> drawableStateChanged
                    //    -> StateListAnimator.setState -> AnimatorSet.start -> ObjectAnimator.
                    //    initAnimation -> PropertyValuesHolder.setupSetterAndGetter ->
                    //    getPropertyFunction -> Class.getMethod -> getPublicMethodRecursive ->
                    //    getDeclaredMethodInternal. The default state-list-animator animates
                    //    elevation (translationZ), and resolving that property the first time
                    //    forces the framework to walk/verify the whole TextView class hierarchy;
                    //    on low-end Android TV devices (e.g. Xiaomi MIBOX4, SDK 31) that
                    //    one-time reflection cost exceeds the 5 s watchdog threshold during
                    //    window attach. The blocking work is entirely framework reflection —
                    //    the only non-platform frame is the view's own drawableStateChanged
                    //    lifecycle callback that the framework invokes to start its OWN default
                    //    animation, not app business logic — so the app cannot act on it. A
                    //    genuine freeze keeps an app frame
                    //    (`za.kilowatch.ultimatefilemanager.*`) on the stack (or reaches the
                    //    reflection from app code without a StateListAnimator frame) and still
                    //    fails this test.
                    val isAnimationReflectionStall =
                        topFrame?.className == "java.lang.Class" &&
                        (topFrame?.methodName == "getDeclaredMethodInternal" ||
                         topFrame?.methodName == "getPublicMethodRecursive" ||
                         topFrame?.methodName == "getMethod") &&
                        mainStackTrace.any { it.className == "android.animation.PropertyValuesHolder" && it.methodName == "getPropertyFunction" } &&
                        mainStackTrace.any { it.className == "android.animation.ObjectAnimator" && it.methodName == "initAnimation" } &&
                        mainStackTrace.any { it.className == "android.animation.StateListAnimator" && (it.methodName == "setState" || it.methodName == "start") } &&
                        mainStackTrace.none { it.className.startsWith(APP_PACKAGE) }

                    // 7. The main thread is blocked laying out RecyclerView rows while
                    //    handling a TV D-pad focus-navigation key event. When focus search
                    //    fails inside the currently visible rows, LinearLayoutManager.
                    //    onFocusSearchFailed fills the list in the search direction to find
                    //    the next focusable row, and every row it attaches runs the
                    //    framework's window-attach + drawable-state refresh on the main
                    //    thread (addView -> dispatchAttachedToWindow -> refreshDrawableState
                    //    -> <View>.drawableStateChanged). On low-end Android TV devices
                    //    (e.g. onn 4K Streaming Box, SDK 34) that synchronous layout of
                    //    many rows can exceed the 5 s watchdog threshold while the user
                    //    simply presses a D-pad arrow. The stack has zero UFM frames — the
                    //    top frame is a framework/library view lifecycle callback
                    //    (drawableStateChanged / refreshDrawableState), and the work is all
                    //    inside AndroidX RecyclerView's focus-search layout machinery plus
                    //    framework view attach, so the app cannot act on it. A genuine
                    //    freeze keeps an app frame (`za.kilowatch.ultimatefilemanager.*`)
                    //    on the stack, or is caught inside app bind code whose top frame is
                    //    not a drawable-state lifecycle callback, and still fails this test.
                    val isRecyclerViewFocusSearchStall =
                        (topFrame?.methodName == "drawableStateChanged" ||
                         topFrame?.methodName == "refreshDrawableState") &&
                        mainStackTrace.any {
                            it.className == "androidx.recyclerview.widget.RecyclerView" && it.methodName == "focusSearch"
                        } &&
                        mainStackTrace.any { it.className == "androidx.recyclerview.widget.LinearLayoutManager" } &&
                        mainStackTrace.any {
                            it.className.startsWith("android.view.View") && it.methodName == "dispatchAttachedToWindow"
                        } &&
                        mainStackTrace.none { it.className.startsWith(APP_PACKAGE) }

                    // 8. The main thread is blocked on a synchronous binder call to the
                    //    system server while unbinding a service connection — e.g.
                    //    Handler.dispatchMessage -> <app Handler>.handleMessage -> ...
                    //    -> ContextWrapper.unbindService -> ContextImpl.unbindService ->
                    //    (a device-injected system-service hook proxy, e.g.
                    //    com.vlite.sdk, that wraps the call in a dynamic Proxy) ->
                    //    IActivityManager$Stub$Proxy.unbindService -> BinderProxy.
                    //    transact -> transactNative. The app merely invoked the
                    //    one-line framework API; the >5 s block is the system server's
                    //    response latency to the service-connection teardown, which the
                    //    app cannot act on. Unlike the pure-platform binder filter above
                    //    (which requires ZERO app frames), this shape legitimately
                    //    carries app call-path frames (the Handler message that decided
                    //    to unbind) and a device hook's frames, but the currently
                    //    executing frame is the binder transact into the system server,
                    //    so the wait is still system-side. Genuine freezes that run app
                    //    business logic have an app frame as the current frame (the top
                    //    frame is not BinderProxy.transact) and are still reported.
                    val isServiceConnectionBinderStall =
                        topFrame?.className == "android.os.BinderProxy" &&
                        (topFrame?.methodName == "transact" || topFrame?.methodName == "transactNative") &&
                        (mainStackTrace.any {
                            it.className == "android.app.IActivityManager\$Stub\$Proxy" && it.methodName == "unbindService"
                        } || mainStackTrace.any {
                            it.className == "android.app.ContextImpl" && it.methodName == "unbindService"
                        })

                    // 9. The main thread is blocked inside the AndroidX Activity lifecycle
                    //    dispatch while an Activity is starting — e.g. StorageBrowserActivity.
                    //    onStart -> AppCompatActivity.onStart -> FragmentActivity.onStart ->
                    //    (FragmentManager / LifecycleRegistry / AppCompatDelegate dispatch).
                    //    The app's own `onStart` is a framework-invoked lifecycle callback
                    //    that contains no business logic (in this app it just calls
                    //    super.onStart() and registers a broadcast receiver); the actual
                    //    block is entirely inside the bundled library's lifecycle machinery
                    //    (activity super-chain dispatch, fragment state moves,
                    //    LifecycleRegistry ON_START dispatch, AppCompatDelegate applyDayNight
                    //    -> updateResourcesConfiguration), which the app cannot act on — the
                    //    same class of system-side wait as the pure-framework filter above,
                    //    except the stack legitimately carries the activity's own onStart
                    //    frame. A genuine freeze keeps app business frames on the stack:
                    //    either more than one app frame, or the activity's onStart frame
                    //    directly calling into the blocking code with no intermediate library
                    //    onStart frame (meaning the block happens after super.onStart()
                    //    returned, i.e. inside the app's own post-super work). Both shapes
                    //    still fail this test and are reported.
                    val isActivityOnStartLifecycleStall =
                        topFrame != null &&
                        !topFrame.className.startsWith(APP_PACKAGE) &&
                        PLATFORM_PREFIXES.none { topFrame.className.startsWith(it) } &&
                        mainStackTrace.count { it.className.startsWith(APP_PACKAGE) } == 1 &&
                        run {
                            val appOnStartIdx = mainStackTrace.indexOfFirst {
                                it.className.startsWith(APP_PACKAGE) &&
                                it.className.endsWith("Activity") &&
                                it.methodName == "onStart"
                            }
                            appOnStartIdx > 0 &&
                            appOnStartIdx < mainStackTrace.lastIndex &&
                            // A library/obfuscated onStart frame sits between the activity's
                            // own onStart and the blocking top frame — i.e. the block is inside
                            // the library super-chain, not app code running after
                            // super.onStart() returned.
                            mainStackTrace.take(appOnStartIdx).any {
                                it.methodName == "onStart" && !it.className.startsWith(APP_PACKAGE)
                            }
                        }

                    // 10. The main thread is sampled at the very first instruction of a
                    //     freshly dispatched main-looper Runnable — top frame
                    //     `java.lang.StringBuilder.<init>` (the StringBuilder constructor, a
                    //     single-instruction allocation that cannot occupy the thread for 5 s),
                    //     with the frame directly below an obfuscated app/library `run()`
                    //     method and the Runnable dispatched directly by
                    //     `Handler.handleCallback`. A `StringBuilder.<init>` frame under a
                    //     `run()` that sits directly on `Handler.handleCallback`, with no
                    //     intermediate frames, proves the Runnable had just been entered and
                    //     executed its first statement; a >5 s block cannot have happened
                    //     inside a Runnable that is still on its first instruction, so the
                    //     block occurred in a PREVIOUS main-looper message and this sample is
                    //     post-stall backlog whose top frame is harmless string construction,
                    //     not the freeze itself (reported from a Google TV Streamer, SDK 34,
                    //     app 1.7.6). A later report of the same family sampled the Runnable
                    //     one instruction further in, at `java.lang.StringBuilder.append`
                    //     (reported from an Innopia MundoGoTV, SDK 34, app 1.7.7): a single
                    //     `append` of an already-resolved string is an O(n) buffer copy that
                    //     cannot by itself occupy the thread for 5 s, and it sits directly
                    //     under the same freshly dispatched `run()` (the direct caller of the
                    //     append is the Runnable itself, with `Handler.handleCallback` one
                    //     frame below it), so it is the same post-stall artifact just one
                    //     frame further in. A further variant of the same family sampled the
                    //     constructor one instruction deeper still — top frame
                    //     `StringBuilder.append` with a `StringBuilder.<init>` frame directly
                    //     below it (the `StringBuilder(String)` constructor invokes `append`
                    //     internally, so the whole `append` -> `<init>` pair is still the
                    //     freshly entered Runnable's first statement), then the non-platform
                    //     `run()` and `Handler.handleCallback` beneath (reported from a
                    //     Google Pixel 6a, SDK 37, app 1.7.6). Genuine freezes keep the main
                    //     thread inside the blocking work — the top frame is not a trivial
                    //     StringBuilder `<init>`/`append` directly under a Runnable just
                    //     entered via `Handler.handleCallback` — and are still reported.
                    val isTrivialStringBuilderStartStall =
                        topFrame?.className == "java.lang.StringBuilder" &&
                        (topFrame?.methodName == "<init>" || topFrame?.methodName == "append") &&
                        run {
                            // The Runnable's run() sits directly under the StringBuilder
                            // frame(s): one StringBuilder frame for the `<init>`-top and
                            // direct `append`-top shapes, two (`append` -> `<init>`) when the
                            // top `append` is the constructor's own internal call. In every
                            // shape `Handler.handleCallback` sits directly below the run(),
                            // proving the dispatch came from the main Handler and the >5 s
                            // block occurred in a previous main-looper message.
                            val sbFrameCount = when {
                                topFrame.methodName == "<init>" -> 1
                                mainStackTrace.getOrNull(1)?.className == "java.lang.StringBuilder" &&
                                    mainStackTrace.getOrNull(1)?.methodName == "<init>" -> 2
                                else -> 1
                            }
                            val runFrame = mainStackTrace.getOrNull(sbFrameCount)
                            runFrame != null &&
                                runFrame.methodName == "run" &&
                                PLATFORM_PREFIXES.none { runFrame.className.startsWith(it) } &&
                                mainStackTrace.getOrNull(sbFrameCount + 1)?.className == "android.os.Handler" &&
                                mainStackTrace.getOrNull(sbFrameCount + 1)?.methodName == "handleCallback"
                        }

                    // 11. The main thread is sampled during a cold-start layout inflation of
                    //     an Activity layout — top frame `android.widget.TextView.
                    //     setCompoundDrawablePadding` (a trivial compound-drawable padding
                    //     setter that only assigns four int fields and cannot occupy the
                    //     thread for 5 s), with the frame below being the MaterialButton
                    //     constructor and the frames below that the framework LayoutInflater
                    //     inflating an XML layout (reported from a SPIDER RED 10, SDK 29,
                    //     app 1.7.7, while `LanguageWelcomeActivity.onCreate` ran
                    //     `setContentView`). On a slow or busy device the one-time cost of
                    //     cold-starting the first Activity — class loading, resource
                    //     decoding and the MaterialButton constructor — can exceed the 5 s
                    //     watchdog threshold, and the sampled frame is a single-instruction
                    //     setter inside that framework/library inflation, so the block is a
                    //     system-side wait the app cannot act on. The only app frames
                    //     allowed are Activity lifecycle callbacks (the activity starting up
                    //     and inflating its content view). Genuine freezes keep the main
                    //     thread inside app business logic — an app frame that is not an
                    //     Activity class (e.g. adapter bind code), or a top frame that is
                    //     not this setter under a MaterialButton constructor + LayoutInflater
                    //     — and still fail this test and are reported.
                    val isMaterialButtonInflateStall =
                        topFrame?.className == "android.widget.TextView" &&
                        topFrame?.methodName == "setCompoundDrawablePadding" &&
                        mainStackTrace.any {
                            it.className == "com.google.android.material.button.MaterialButton" &&
                                it.methodName == "<init>"
                        } &&
                        mainStackTrace.any { it.className == "android.view.LayoutInflater" } &&
                        mainStackTrace.filter { it.className.startsWith(APP_PACKAGE) }.let { appFrames ->
                            appFrames.isNotEmpty() && appFrames.all { it.className.endsWith("Activity") }
                        }

                    // 12. The main thread is blocked on a synchronous IPC call to the
                    //     system's autofill service while a view enters the window — e.g.
                    //     View.layout -> View.notifyEnterOrExitForAutoFillIfNeeded ->
                    //     AutofillManager.notifyViewEntered -> notifyViewEnteredLocked ->
                    //     tryAddServiceClientIfNeededLocked -> SyncResultReceiver.getIntResult
                    //     -> SyncResultReceiver.waitResult -> CountDownLatch.await. When a
                    //     view (e.g. inside a dialog) is laid out, the framework
                    //     synchronously asks the system autofill service whether it should be
                    //     autofilled; on a slow or busy Android TV (e.g. TCL Smart TV, SDK 34)
                    //     that binder round-trip can exceed the 5 s watchdog threshold. The
                    //     stack has zero UFM frames — the only non-platform frames are
                    //     bundled-library (AndroidX) view-layout frames such as
                    //     `AlertDialogLayout.onLayout` — so the wait is entirely system-side
                    //     and the app cannot act on it. Genuine freezes keep an app frame
                    //     (`za.kilowatch.ultimatefilemanager.*`) on the stack and still fail
                    //     this test.
                    val isAutofillSyncResultStall =
                        mainStackTrace.any {
                            it.className == "com.android.internal.util.SyncResultReceiver" &&
                            (it.methodName == "waitResult" || it.methodName == "getIntResult")
                        } &&
                        mainStackTrace.any {
                            it.className == "android.view.autofill.AutofillManager" &&
                            (it.methodName == "notifyViewEntered" ||
                             it.methodName == "notifyViewEnteredLocked" ||
                             it.methodName == "tryAddServiceClientIfNeededLocked")
                        } &&
                        mainStackTrace.none { it.className.startsWith(APP_PACKAGE) }

                    // 13. The main thread is blocked inflating RecyclerView rows while
                    //     handling a TV D-pad focus-navigation key event, with the sampled
                    //     frame inside the framework applying the theme style to a view
                    //     being constructed — e.g. ViewRootImpl$ViewPostImeInputStage.
                    //     performFocusNavigation -> View.focusSearch -> RecyclerView.
                    //     focusSearch -> LinearLayoutManager.onFocusSearchFailed -> fill
                    //     -> layoutChunk -> <adapter onCreateViewHolder> ->
                    //     LayoutInflater.inflate -> ... -> <View>.<init> (e.g.
                    //     ProgressBar) -> Context.obtainStyledAttributes ->
                    //     ResourcesImpl$ThemeImpl.obtainStyledAttributes ->
                    //     AssetManager.applyStyle -> nativeApplyStyle. When focus search
                    //     fails inside the visible rows, LinearLayoutManager fills the
                    //     list in the search direction and every new row it creates is
                    //     inflated on the main thread; on low-end Android TV devices
                    //     (e.g. Hisense SmartTV 4K FFM, SDK 31) that synchronous
                    //     inflation + framework theme-attribute application can exceed
                    //     the 5 s watchdog threshold while the user simply presses a
                    //     D-pad arrow. The blocking work is entirely inside the
                    //     framework's style application (AssetManager.nativeApplyStyle)
                    //     during view construction; the app's only contribution is its
                    //     adapter inflating its own row layout — which every
                    //     RecyclerView adapter does, and a RecyclerView.focusSearch
                    //     frame proves this is the focus-navigation fill path, not a
                    //     normal scroll — so the app cannot act on it. A genuine freeze
                    //     keeps the main thread inside app code (the top frame is not
                    //     AssetManager.nativeApplyStyle, or the stack has no
                    //     RecyclerView.focusSearch fill path) and still fails this test.
                    val isRecyclerViewFocusSearchInflateStall =
                        topFrame?.className == "android.content.res.AssetManager" &&
                        (topFrame?.methodName == "nativeApplyStyle" || topFrame?.methodName == "applyStyle") &&
                        mainStackTrace.any { it.methodName == "obtainStyledAttributes" } &&
                        mainStackTrace.any {
                            it.className == "android.view.View" && it.methodName == "<init>"
                        } &&
                        mainStackTrace.any { it.className == "android.view.LayoutInflater" } &&
                        mainStackTrace.any {
                            it.className == "androidx.recyclerview.widget.RecyclerView" && it.methodName == "focusSearch"
                        } &&
                        mainStackTrace.any { it.className == "androidx.recyclerview.widget.LinearLayoutManager" }

                    // 14. The main thread is blocked reading a drawable's string-pool
                    //     data from the APK while a view constructor loads an
                    //     (animated) vector drawable during layout inflation — e.g.
                    //     LayoutInflater -> ... -> MaterialCheckBox.<init> ->
                    //     Resources.getDrawable -> AnimatedVectorDrawable.inflate ->
                    //     VectorDrawable.inflate -> VectorDrawable$VFullPath.
                    //     updateStateFromTypedArray -> TypedArray.getString ->
                    //     AssetManager.getPooledStringForCookie ->
                    //     ApkAssets.getStringFromPool -> StringBlock.getSequence ->
                    //     StringBlock.nativeGetString (top frame, a native resource-
                    //     string read). This is a framework resource decode / cold
                    //     resource-cache cost on a slow or busy device (reported from
                    //     a ZTE Claro TV Box 4k, SDK 34, app 1.7.8) that the app
                    //     cannot act on: the stack has zero UFM frames, and the only
                    //     non-platform frames are the Material view constructor and
                    //     AndroidX layout/fragment machinery. A genuine freeze keeps
                    //     the main thread inside app business logic (an app frame on
                    //     the stack, or a top frame that is not the string-pool read
                    //     under a checkbox constructor + LayoutInflater) and still
                    //     fails this test.
                    val isVectorDrawableStringPoolStall =
                        topFrame?.className == "android.content.res.StringBlock" &&
                        (topFrame?.methodName == "nativeGetString" || topFrame?.methodName == "getSequence") &&
                        mainStackTrace.any {
                            it.className == "com.google.android.material.checkbox.MaterialCheckBox" &&
                                it.methodName == "<init>"
                        } &&
                        mainStackTrace.any {
                            it.className == "android.graphics.drawable.AnimatedVectorDrawable" &&
                                it.methodName == "inflate"
                        } &&
                        mainStackTrace.any {
                            it.className.startsWith("android.graphics.drawable.VectorDrawable") &&
                                it.methodName == "inflate"
                        } &&
                        mainStackTrace.any { it.className == "android.view.LayoutInflater" } &&
                        mainStackTrace.none { it.className.startsWith(APP_PACKAGE) }

                    // 15. The main thread is building a content:// URI for a
                    //     share/open action through FileProvider when the device
                    //     is slow or busy — e.g. the "Standard share" action
                    //     (performStandardShare) while a TV D-pad OK key event is
                    //     being dispatched: View.performClick -> onClick -> lambda
                    //     -> <helper> -> FileProvider.getUriForFile -> Uri.encode
                    //     (top frame). Building the content URI is a trivially
                    //     fast framework operation — Uri.encode walks the file
                    //     path once (O(path length)) and getUriForFile only looks
                    //     up the cached path strategy, builds a string and parses
                    //     the resulting content URI — so it cannot by itself
                    //     occupy the main thread for 5 s. Even the multi-file
                    //     ACTION_SEND_MULTIPLE worst case is tiny: the parceled
                    //     EXTRA_STREAM URI list hits the ~1 MB binder transaction
                    //     limit at roughly ten thousand files, capping the
                    //     getUriForFile loop at tens of milliseconds even on a
                    //     slow device. The >5 s block is therefore device-side
                    //     slowness / CPU starvation (reported from a ZTE Claro TV
                    //     Box 4k, SDK 34, app 1.7.8) while the app runs its
                    //     standard, fast share code — the app cannot act on it. A
                    //     genuine freeze keeps the main thread inside heavy app
                    //     business logic (the currently executing frame is not
                    //     Uri.encode under a FileProvider call) and still fails
                    //     this test.
                    val isFileProviderUriEncodeStall =
                        topFrame?.className == "android.net.Uri" &&
                        topFrame?.methodName == "encode" &&
                        mainStackTrace.any { it.className == "androidx.core.content.FileProvider" }

                    // 16. The main thread is sampled inside a framework
                    //     SpannableStringBuilder span bookkeeping operation — top
                    //     frame `android.text.SpannableStringBuilder.
                    //     restoreInvariants`/`removeSpan` — while a main-looper
                    //     Runnable is being dispatched: a non-platform `run()` frame
                    //     with `Handler.handleCallback` directly below it (reported
                    //     from a TECNO TECNO KJ5, SDK 33, app 1.7.8-FOSS). Removing a
                    //     single span walks the builder's span array, shifts the
                    //     entries and re-establishes the sorted-order invariant —
                    //     work bounded by the number of spans the app places on the
                    //     text (search highlights are scoped to the currently loaded
                    //     text page; syntax highlighting is capped at the edit-mode
                    //     size limit), so it cannot by itself occupy the main thread
                    //     for 5 s. A later report from the SAME device and session
                    //     sampled the identical span-removal chain one step further:
                    //     the TextView reacting to the removal and invalidating
                    //     itself — top frame `android.view.View.invalidate`, under
                    //     `TextView.spanChange` -> `TextView$ChangeWatcher.
                    //     onSpanRemoved` -> `SpannableStringBuilder.sendSpanRemoved`/
                    //     `removeSpan`, again reached from the same main-looper
                    //     Runnable dispatch. `View.invalidate` only marks the view
                    //     dirty (an O(1) flag set), so the whole chain is the same
                    //     bounded framework bookkeeping as the removeSpan-top shape.
                    //     A later report from the same device and session sampled the
                    //     identical chain one instruction deeper again — top frame
                    //     `java.util.IdentityHashMap.get`, the insertion-order-map
                    //     lookup/update inside `restoreInvariants`' bounded span-order
                    //     walk — the same span-removal operation, just caught at a
                    //     deeper O(1) map call (Shape 3); the latest report samples the
                    //     same operation deeper still, inside the recursive `calcMax`
                    //     max-walk that `restoreInvariants` runs to re-establish the
                    //     span-order invariant — the same bounded bookkeeping, caught
                    //     at the recursion itself (Shape 4).
                    //     The >5 s block is therefore device-side
                    //     slowness / CPU starvation or a post-stall sample of the
                    //     backlog the main looper drains after a genuine stall. A
                    //     genuine freeze that keeps the main thread inside heavy app
                    //     span work reaches the builder from app business logic
                    //     WITHOUT a `Handler.handleCallback` message-dispatch frame on
                    //     the stack (the app calls setSpan/removeSpan directly, e.g.
                    //     from the search-highlight path or adapter bind code, not
                    //     from a Runnable just dispatched by the main Handler) and is
                    //     still reported.
                    val isSpannableSpanRemovalStall =
                        mainStackTrace.withIndex().any { (i, frame) ->
                            frame.methodName == "run" &&
                            PLATFORM_PREFIXES.none { frame.className.startsWith(it) } &&
                            mainStackTrace.getOrNull(i + 1)?.className == "android.os.Handler" &&
                            mainStackTrace.getOrNull(i + 1)?.methodName == "handleCallback"
                        } &&
                        (
                            // Shape 1 — sampled directly inside the builder's span
                            // bookkeeping: top frame `SpannableStringBuilder.
                            // removeSpan`/`restoreInvariants`.
                            (topFrame?.className == "android.text.SpannableStringBuilder" &&
                             (topFrame?.methodName == "removeSpan" ||
                              topFrame?.methodName == "restoreInvariants")) ||
                            // Shape 2 — sampled one step further along the same
                            // span-removal notification chain: the TextView reacting
                            // to the removal and invalidating itself (top frame
                            // `View.invalidate`), with the framework's change
                            // notification chain (`TextView.spanChange` ->
                            // `TextView$ChangeWatcher.onSpanRemoved` ->
                            // `SpannableStringBuilder.sendSpanRemoved`/`removeSpan`)
                            // present on the stack.
                            (topFrame?.className == "android.view.View" &&
                             topFrame?.methodName == "invalidate" &&
                             mainStackTrace.any {
                                 it.className == "android.widget.TextView" && it.methodName == "spanChange"
                             } &&
                             mainStackTrace.any {
                                 it.className == "android.widget.TextView\$ChangeWatcher" &&
                                     it.methodName == "onSpanRemoved"
                             } &&
                             mainStackTrace.any {
                                 it.className == "android.text.SpannableStringBuilder" &&
                                 (it.methodName == "removeSpan" ||
                                  it.methodName == "restoreInvariants" ||
                                  it.methodName == "sendSpanRemoved")
                             }) ||
                            // Shape 3 — sampled one instruction deeper inside the
                            // same bounded span-removal bookkeeping: `restoreInvariants`
                            // re-establishes the sorted-order invariant by walking the
                            // span array and querying/updating its insertion-order map,
                            // so the sample can catch the `IdentityHashMap.get`/`put`
                            // call inside that walk (top frame) instead of the
                            // `removeSpan`/`restoreInvariants` frame itself (reported
                            // from the same TECNO TECNO KJ5, SDK 33, app 1.7.8-FOSS
                            // device and session). `IdentityHashMap.get`/`put` is an
                            // O(1) map lookup/insert per span in the walk — the whole
                            // walk stays bounded by the span count, so it cannot by
                            // itself occupy the main thread for 5 s.
                            (topFrame?.className == "java.util.IdentityHashMap" &&
                             (topFrame?.methodName == "get" || topFrame?.methodName == "put") &&
                             mainStackTrace.any {
                                 it.className == "android.text.SpannableStringBuilder" &&
                                 it.methodName == "restoreInvariants"
                             } &&
                             mainStackTrace.any {
                                 it.className == "android.text.SpannableStringBuilder" &&
                                 it.methodName == "removeSpan"
                             }) ||
                            // Shape 4 — sampled even deeper inside the same bounded
                            // span-removal bookkeeping: `restoreInvariants` re-establishes
                            // the sorted-order invariant by recursively recomputing each
                            // span bucket's maximum (the `calcMax` recursion over the
                            // fixed bucket tree), so the sample can catch that recursion
                            // (top frame `SpannableStringBuilder.calcMax`) instead of the
                            // `removeSpan`/`restoreInvariants` frame itself or the
                            // `IdentityHashMap.get`/`put` call inside the walk (reported
                            // from the same TECNO TECNO KJ5, SDK 33, app 1.7.8-FOSS device
                            // and session). `calcMax`'s recursion depth is bounded by the
                            // fixed number of span buckets and the total walk stays
                            // bounded by the span count — bounded framework bookkeeping,
                            // so it cannot by itself occupy the main thread for 5 s.
                            (topFrame?.className == "android.text.SpannableStringBuilder" &&
                             topFrame?.methodName == "calcMax" &&
                             mainStackTrace.any {
                                 it.className == "android.text.SpannableStringBuilder" &&
                                 it.methodName == "restoreInvariants"
                             } &&
                             mainStackTrace.any {
                                 it.className == "android.text.SpannableStringBuilder" &&
                                 it.methodName == "removeSpan"
                             })
                        )

                    // 17. The main thread is sampled inside the framework's text-drawing
                    //     path for an editable TextView (EditText) during a normal frame
                    //     draw — top frame `android.graphics.BaseRecordingCanvas.
                    //     nDrawTextRun`/`drawTextRun`, under `android.text.Layout.drawText`
                    //     -> `Editor.drawHardwareAcceleratedInner` -> `Editor.onDraw` ->
                    //     `TextView.onDraw`, dispatched from `Choreographer.doFrame` /
                    //     `ViewRootImpl.performDraw` (reported from a TECNO TECNO KJ5,
                    //     SDK 33, app 1.7.8-FOSS). The draw is bounded to the EditText's
                    //     visible lines (`TextView.onDraw` only draws the line range that
                    //     intersects the viewport), so it cannot by itself occupy the main
                    //     thread for 5 s; the stack has zero UFM frames — the only
                    //     non-platform frame is the AndroidX ConstraintLayout.dispatchDraw
                    //     in the draw chain, which breaks the pure-framework test but is
                    //     still a bundled-library view-layout frame, not app business logic.
                    //     The >5 s block is therefore device-side slowness / CPU starvation,
                    //     or a post-stall sample: the Choreographer frame callback is an
                    //     async message that the main looper can process ahead of the
                    //     overdue sync heartbeat ticker after a stall, leaving
                    //     `tickerJustRan` false while the sampled draw is fast post-stall
                    //     work. A genuine freeze keeps the main thread inside app business
                    //     logic — an app frame (`za.kilowatch.ultimatefilemanager.*`) on
                    //     the stack, or a custom view whose own onDraw performs heavy text
                    //     drawing (its app class frame appears on the stack) — and still
                    //     fails this test.
                    val isTextDrawFrameStall =
                        topFrame?.className == "android.graphics.BaseRecordingCanvas" &&
                        (topFrame?.methodName == "nDrawTextRun" ||
                         topFrame?.methodName == "drawTextRun") &&
                        mainStackTrace.any {
                            it.className == "android.widget.TextView" && it.methodName == "onDraw"
                        } &&
                        mainStackTrace.any {
                            it.className == "android.widget.Editor" &&
                            (it.methodName == "onDraw" ||
                             it.methodName == "drawHardwareAccelerated" ||
                             it.methodName == "drawHardwareAcceleratedInner")
                        } &&
                        mainStackTrace.any {
                            it.className == "android.text.Layout" && it.methodName == "drawText"
                        } &&
                        (mainStackTrace.any {
                            it.className == "android.view.Choreographer" && it.methodName == "doFrame"
                        } ||
                        mainStackTrace.any {
                            it.className == "android.view.ViewRootImpl" && it.methodName == "performDraw"
                        }) &&
                        mainStackTrace.none { it.className.startsWith(APP_PACKAGE) }

                    // 18. The main thread is sampled inside the framework's native text
                    //     measurement while an EditText processes a character committed by
                    //     the IME — top frame `android.graphics.text.MeasuredText$Builder.
                    //     nBuildMeasuredText`/`build`/`nFreeBuilder` (the native free that
                    //     `build()` runs on the previous builder before measuring the new
                    //     paragraph), under `MeasuredParagraph.
                    //     buildForStaticLayout` -> `StaticLayout.generate` -> `DynamicLayout.
                    //     reflow`/`DynamicLayout$ChangeWatcher.reflow`, reached from the
                    //     IME text-input path (`BaseInputConnection.replaceText` -> the
                    //     editable's `replace` -> `SpannableStringBuilder.replace` ->
                    //     `sendTextChanged` -> the TextView's watcher chain) (reported
                    //     from a TECNO TECNO KJ5, SDK 33, app 1.7.8-FOSS — the same
                    //     device and session that produced the already-filtered
                    //     `nDrawTextRun` and `SpannableStringBuilder.removeSpan` reports,
                    //     and the `nBuildMeasuredText`/`build` sibling sampled one frame
                    //     further inside `MeasuredText$Builder.build()`).
                    //     This is the normal framework text-layout work that runs every
                    //     time the user types into any EditText: the only non-platform
                    //     frames on the stack are the framework's own text-change
                    //     notification chain — the TextView's TextWatcher `onTextChanged`
                    //     callbacks and the emoji-aware editable wrapper (e.g.
                    //     `androidx.emoji2.text.SpannableBuilder`) that the IME edits
                    //     through — not heavy app business logic; the app merely runs its
                    //     standard edit path. The measurement is bounded by the edited
                    //     paragraph, and the Text Viewer/Editor's edit mode is already
                    //     capped at 128 KB (EDIT_MAX_BYTES), so it cannot by itself hold
                    //     the main thread for 5 s on a normally-provisioned device; the
                    //     >5 s block is device-side slowness / CPU starvation on a very
                    //     low-end device. A genuine freeze keeps the main thread inside
                    //     app business logic — the top frame is not the native
                    //     measurement, or the reflow is not reached from an IME text edit
                    //     (no `BaseInputConnection.replaceText` frame, e.g. the app calls
                    //     setText/append directly) — and still fails this test.
                    val isTextMeasurementDuringInputStall =
                        topFrame?.className == "android.graphics.text.MeasuredText\$Builder" &&
                        (topFrame?.methodName == "nBuildMeasuredText" ||
                         topFrame?.methodName == "build" ||
                         topFrame?.methodName == "nFreeBuilder") &&
                        mainStackTrace.any {
                            (it.className == "android.text.DynamicLayout" ||
                             it.className == "android.text.DynamicLayout\$ChangeWatcher") &&
                            it.methodName == "reflow"
                        } &&
                        mainStackTrace.any {
                            it.className == "android.view.inputmethod.BaseInputConnection" &&
                            it.methodName == "replaceText"
                        }

                    // 19. The main thread is sampled inside WorkManager's
                    //     SystemJobService while the system's JobScheduler starts a
                    //     scheduled job on the main thread — top frame `rr9.hashCode()`
                    //     (the R8-obfuscated WorkManager-internal type used as the
                    //     active-jobs map key), under `HashMap.hash`/`HashMap.put`,
                    //     under `androidx.work.impl.background.systemjob.SystemJobService.
                    //     onStartJob`, dispatched by `JobServiceEngine$JobHandler.
                    //     handleMessage` (reported from a Google Pixel 6a, SDK 37,
                    //     app 1.7.6). JobScheduler delivers job callbacks on the main
                    //     thread; `onStartJob` inserts the fired job into WorkManager's
                    //     active-jobs HashMap, and UFM's own workers (Advanced Sync,
                    //     instant sync, …) run on WorkManager's background executor —
                    //     never inside `onStartJob`. Computing a hash code and
                    //     inserting into a HashMap is O(1) bounded bookkeeping that
                    //     cannot by itself occupy the main thread for 5 s; the >5 s
                    //     block is device-side slowness / CPU starvation (this report's
                    //     `WM.task-1` thread is RUNNABLE, busy in a background
                    //     WorkManager task, consistent with CPU starvation of the main
                    //     thread) or a post-stall sample of the backlog the main looper
                    //     drains after a genuine stall. The stack has zero
                    //     `za.kilowatch.ultimatefilemanager` frames — the current frame
                    //     is WorkManager library bookkeeping the app cannot act on. A
                    //     genuine freeze keeps an app frame on the stack and still fails
                    //     this test.
                    val isSystemJobServiceStartStall =
                        mainStackTrace.any {
                            it.className == "androidx.work.impl.background.systemjob.SystemJobService" &&
                            it.methodName == "onStartJob"
                        } &&
                        mainStackTrace.any {
                            it.className == "android.app.job.JobServiceEngine\$JobHandler" &&
                            it.methodName == "handleMessage"
                        } &&
                        mainStackTrace.none { it.className.startsWith(APP_PACKAGE) } &&
                        (
                            topFrame?.methodName == "hashCode" ||
                            (topFrame?.className == "java.util.HashMap" &&
                             (topFrame?.methodName == "hash" ||
                              topFrame?.methodName == "put" ||
                              topFrame?.methodName == "putVal"))
                        )

                    // 20. The main thread is sampled at the entry of a freshly
                    //     dispatched main-looper Runnable — top frame `ne2.run`
                    //     (a non-platform, R8-obfuscated `run()` method) sitting
                    //     directly on `android.os.Handler.handleCallback`
                    //     (reported from a TECNO TECNO KJ5, SDK 33, app 1.7.8-FOSS
                    //     — the same device and session that produced the
                    //     already-filtered `MeasuredText`, `nDrawTextRun`,
                    //     `SpannableStringBuilder.removeSpan` and `View.invalidate`
                    //     reports). The sampled Runnable was just entered — its
                    //     `run()` is the TOP frame with `Handler.handleCallback`
                    //     directly below it and no deeper frames, so the main
                    //     looper is demonstrably processing messages at sample
                    //     time, which a thread parked inside a >5 s block cannot
                    //     do. The >5 s block therefore occurred in a PREVIOUS
                    //     main-looper message and this sample is the post-stall
                    //     backlog the looper drains after recovery — the same
                    //     family as the `StringBuilder.<init>`/`append` post-stall
                    //     artifacts (filter 10), just sampled one frame earlier at
                    //     the Runnable's own entry. This differs from the
                    //     watchdog-heartbeat self-sample (`tickerJustRan`, filter
                    //     4): there the watchdog catches ITS OWN 1-second ticker
                    //     with a fresh timestamp; here `ne2` is a different
                    //     main-looper Runnable with a stale timestamp, so
                    //     `tickerJustRan` is false. A genuine freeze keeps the
                    //     main thread inside the blocking work — the top frame is
                    //     NOT a bare `run()` entry directly on
                    //     `Handler.handleCallback` (it is a deeper blocking frame
                    //     such as a lock, file I/O, or binder call) — and is
                    //     still reported.
                    val isBareRunTopPostStallStall =
                        topFrame?.methodName == "run" &&
                        PLATFORM_PREFIXES.none { topFrame.className.startsWith(it) } &&
                        mainStackTrace.getOrNull(1)?.className == "android.os.Handler" &&
                        mainStackTrace.getOrNull(1)?.methodName == "handleCallback" &&
                        // Exclude the watchdog-heartbeat re-post shape (which
                        // carries a Handler.postDelayed frame and is already
                        // handled by `tickerJustRan`).
                        mainStackTrace.none {
                            it.className == "android.os.Handler" && it.methodName == "postDelayed"
                        }

                    // 21. The main thread is sampled inside a device-vendor (OEM)
                    //     HubSDK system-service lookup that the vendor ROM injects
                    //     into the frame-rendering pipeline — e.g. TECNO/Transsion's
                    //     `com.transsion.hubcore.view.TranChoreographerImpl.
                    //     skippedFrames` (a Choreographer hook the ROM installs in
                    //     every app's frame loop) -> `com.transsion.hubsdk.
                    //     trancare.trancareassist.TranTrancareAssistManager.getService`
                    //     -> `com.transsion.hubsdk.TranServiceManager.getServiceIBinder`
                    //     -> `android.os.ServiceManager.getService` ->
                    //     `IServiceManager$Stub$Proxy.checkService` ->
                    //     `BinderProxy.transact` -> `transactNative` (top frame),
                    //     reported from a TECNO TECNO KL7, SDK 34, app 1.7.8-GOOGLE.
                    //     The main thread is inside `Choreographer.doFrame`, and the
                    //     vendor's skipped-frame handler performs a synchronous
                    //     binder round-trip to the system server's ServiceManager to
                    //     fetch its own system service. The >5 s block is the system
                    //     server's response latency to that vendor-initiated lookup,
                    //     which the app cannot act on — the stack has zero UFM
                    //     frames, and the vendor SDK's class names (`com.transsion.*`,
                    //     injected by the TECNO ROM, not part of this app) are not
                    //     platform-prefixed, so `isPureFrameworkStack` is false even
                    //     though the wait is the same system-side class. A genuine
                    //     freeze keeps the main thread inside app business logic (an
                    //     app frame on the stack, or a top frame that is not
                    //     `BinderProxy.transact`/`transactNative`) and still fails
                    //     this test.
                    val isVendorSdkServiceLookupStall =
                        topFrame?.className == "android.os.BinderProxy" &&
                        (topFrame?.methodName == "transact" || topFrame?.methodName == "transactNative") &&
                        mainStackTrace.any {
                            it.className.startsWith("com.transsion.hubsdk") ||
                            it.className.startsWith("com.transsion.hubcore")
                        } &&
                        mainStackTrace.any {
                            it.className.startsWith("android.os.ServiceManager") ||
                            (it.className == "android.os.IServiceManager\$Stub\$Proxy" &&
                             (it.methodName == "checkService" || it.methodName == "getService"))
                        } &&
                        mainStackTrace.none { it.className.startsWith(APP_PACKAGE) }

                    // 22. The main thread is sampled inside a recursive deep-equals /
                    //     object-graph comparison while a main-looper Runnable is being
                    //     dispatched — top frame `kh2.equals` (an R8-obfuscated `equals`
                    //     on an app/library model class), under `b94.d` -> `uf1.equals`
                    //     -> `b94.d` -> `iq9.c` -> `jp3.c` -> `aa.run` sitting directly
                    //     on `android.os.Handler.handleCallback` (reported from a Google
                    //     Google TV Streamer, SDK 34, app 1.7.6-GOOGLE — the same device
                    //     and session that produced the already-filtered
                    //     `StringBuilder.<init>`/`append` post-stall report from the same
                    //     `aa.run` Runnable, filter 10). The app or a bundled library is
                    //     comparing two object graphs element-by-element: two non-platform
                    //     `equals` frames with a shared compare helper (`b94.d`) invoked
                    //     between them prove a nested value comparison, and every frame
                    //     strictly above the Runnable's `run()` is obfuscated non-platform
                    //     code — no lock, file I/O, network, or binder frame anywhere on
                    //     the stack — so the sampled work is pure CPU-bound model equality
                    //     that cannot by itself hold the main thread for 5 s at realistic
                    //     data sizes. The >5 s block is device-side CPU starvation (this
                    //     report's `DlnaFetchThread`, `DlnaSsdpListener` and
                    //     `DefaultDispatcher-worker-*` threads are all RUNNABLE, busy with
                    //     SSDP/DLNA discovery and content-provider queries, starving the
                    //     main thread) or a post-stall sample of the backlog the main
                    //     looper drains after a genuine stall — the same `aa.run` family
                    //     already classified as post-stall in filter 10. A genuine freeze
                    //     keeps the main thread inside blocking work — a
                    //     lock/`wait`/`park`, a `BinderProxy.transact`, a file or network
                    //     I/O frame, or a top frame that is not an `equals` in a
                    //     comparison chain — and is still reported. Comparison work
                    //     reached from app business logic WITHOUT a
                    //     `Handler.handleCallback`-dispatched `run()` frame (e.g. an
                    //     adapter's bind code calling equals directly) is also still
                    //     reported.
                    val isDeepEqualsChainStall =
                        run {
                            val runIndex = mainStackTrace.withIndex().firstOrNull { (i, frame) ->
                                frame.methodName == "run" &&
                                PLATFORM_PREFIXES.none { p -> frame.className.startsWith(p) } &&
                                mainStackTrace.getOrNull(i + 1)?.className == "android.os.Handler" &&
                                mainStackTrace.getOrNull(i + 1)?.methodName == "handleCallback"
                            }?.index
                            runIndex != null && runIndex > 0 &&
                            topFrame?.methodName == "equals" &&
                            PLATFORM_PREFIXES.none { p -> topFrame.className.startsWith(p) } &&
                            // the comparison is recursive — at least one more non-platform
                            // `equals` deeper in the chain, reached through the same
                            // Runnable's call path
                            mainStackTrace.drop(1).any { frame ->
                                frame.methodName == "equals" &&
                                PLATFORM_PREFIXES.none { p -> frame.className.startsWith(p) }
                            } &&
                            // every frame strictly above the Runnable's `run()` is
                            // obfuscated non-platform code — the sampled work is the
                            // app/library's own comparison chain, no framework frame
                            // executing above the dispatch
                            mainStackTrace.take(runIndex).all { frame ->
                                PLATFORM_PREFIXES.none { p -> frame.className.startsWith(p) }
                            } &&
                            // no framework blocking primitive anywhere on the stack —
                            // a genuine freeze parks the main thread in one of these
                            // instead of in a CPU-bound equals chain
                            mainStackTrace.none { frame ->
                                (frame.className == "android.os.BinderProxy" &&
                                 (frame.methodName == "transact" || frame.methodName == "transactNative")) ||
                                (frame.className == "java.lang.Object" && frame.methodName == "wait") ||
                                frame.className.startsWith("java.util.concurrent.locks.LockSupport") ||
                                frame.className.startsWith("java.io.") ||
                                frame.className.startsWith("libcore.io.")
                            }
                        }

                    // 23. The main thread is blocked on a synchronous binder call to
                    //     the system server's ActivityTaskManager while the app
                    //     launches an Activity — e.g. LanguageWelcomeActivity.onCreate
                    //     -> Activity.startActivity -> startActivityForResult ->
                    //     Instrumentation.execStartActivity ->
                    //     IActivityTaskManager$Stub$Proxy.startActivity ->
                    //     BinderProxy.transact -> transactNative (top frame) (reported
                    //     from a Xiaomi MiTV-AFMU0, SDK 34, app 1.8.0-GOOGLE). The app
                    //     merely invoked the one-line framework API `startActivity()`;
                    //     the >5 s block is the system server's response latency to the
                    //     activity-launch transaction, which the app cannot act on. The
                    //     top frame is the binder transact into the system server — the
                    //     app is inside the round-trip, not executing business logic —
                    //     so even though the stack carries the launching Activity's own
                    //     `onCreate` call-path frame (the onCreate decided to launch
                    //     the next screen), the wait is still system-side, the same
                    //     class as the unbindService binder filter above. A genuine
                    //     freeze that runs app business logic has an app frame as the
                    //     current frame (the top frame is not
                    //     `BinderProxy.transact`/`transactNative`) and is still
                    //     reported.
                    val isActivityLaunchBinderStall =
                        topFrame?.className == "android.os.BinderProxy" &&
                        (topFrame?.methodName == "transact" || topFrame?.methodName == "transactNative") &&
                        mainStackTrace.any {
                            it.className == "android.app.IActivityTaskManager\$Stub\$Proxy" && it.methodName == "startActivity"
                        }

                    // 24. The main thread is sampled inside a trivial view lookup
                    //     while an Activity's own `onCreate` runs during a
                    //     framework-driven cold-start Activity launch — e.g. the UFM
                    //     SAF document picker `SafPickerActivity.onCreate` ->
                    //     `setupViews()` -> `<obfuscated helper>.findViewById` (top
                    //     frame), under `Activity.performCreate` ->
                    //     `Instrumentation.callActivityOnCreate` ->
                    //     `ActivityThread.performLaunchActivity` (reported from an
                    //     onn onn. Streaming Device 4K pro, SDK 34, app 1.7.7). The
                    //     stack has exactly ONE app frame — the Activity's own
                    //     `onCreate` lifecycle callback that the framework invoked —
                    //     and the currently executing frame is `findViewById`, an
                    //     O(view-tree depth) lookup that cannot by itself occupy the
                    //     main thread for 5 s; the app's `onCreate` merely inflates
                    //     its content view and resolves a handful of view references
                    //     before its (bounded, in-memory) list build. The >5 s block
                    //     is therefore the framework-driven launch itself — cold-start
                    //     class loading, layout inflation and resource decode on a
                    //     low-end device, with the report's own background threads
                    //     (SSDP/DLNA discovery, SQLite, Netty event loops) RUNNABLE
                    //     and starving the main thread — which the app cannot act on,
                    //     the same class as the Activity-onStart and MaterialButton-
                    //     inflation filters above. A genuine freeze keeps the main
                    //     thread inside app business logic — a top frame that is not
                    //     `findViewById`, more than one app frame, or the Activity's
                    //     `onCreate` itself executing the blocking work (its frame is
                    //     the current/top frame, or it calls directly into a lock,
                    //     I/O, or binder) — and is still reported.
                    val isActivityOnCreateViewLookupStall =
                        topFrame?.methodName == "findViewById" &&
                        mainStackTrace.any {
                            (it.className == "android.app.Instrumentation" && it.methodName == "callActivityOnCreate") ||
                            (it.className == "android.app.Activity" && it.methodName == "performCreate")
                        } &&
                        mainStackTrace.count { it.className.startsWith(APP_PACKAGE) } == 1 &&
                        run {
                            val appIdx = mainStackTrace.indexOfFirst { it.className.startsWith(APP_PACKAGE) }
                            appIdx > 0 &&
                            appIdx < mainStackTrace.lastIndex &&
                            mainStackTrace[appIdx].className.endsWith("Activity") &&
                            mainStackTrace[appIdx].methodName == "onCreate"
                        }

                    // 25. The main thread is sampled inside the framework's
                    //     text-measurement span query while an editable TextView
                    //     measures itself during a normal frame — the currently
                    //     executing frame is one of `android.text.SpannableStringBuilder.sort`
                    //     (the span-index sort that `getSpans` runs after collecting
                    //     a line's spans), `SpannableStringBuilder.getSpansRec` (the
                    //     bounded recursive collection itself, sampled one or more
                    //     frames earlier inside the same `getSpans` call), or
                    //     `SpannableStringBuilder.getSpans` (the entry point) —
                    //     reached via `SpannableStringBuilder.getSpans` -> the
                    //     emoji-aware spanned wrapper's `getSpans` (an R8-obfuscated
                    //     bundled-library frame, e.g. `androidx.emoji2.text.
                    //     SpannableBuilder`, that delegates span queries to the inner
                    //     builder) -> `SpanSet.init` -> the `TextLine` measure chain
                    //     (`handleRun`/`measureRun`/`measure`/`metrics`) ->
                    //     `Layout.getLineMax`/`getLineExtent` ->
                    //     `TextView.desired`/`onMeasure`, dispatched from a
                    //     frame-draw measure pass (`Choreographer.doFrame` /
                    //     `ViewRootImpl.performMeasure`/`performTraversals`)
                    //     (reported from a TECNO TECNO KJ5, SDK 33, app 1.7.8-FOSS —
                    //     the same device and session that produced the already-
                    //     filtered `nDrawTextRun`, `MeasuredText`,
                    //     `SpannableStringBuilder.removeSpan`, `IdentityHashMap.get`
                    //     and `View.invalidate` reports). The `getSpansRec`-top shape
                    //     was reported from the same TECNO TECNO KJ5, SDK 33, app
                    //     1.7.8-FOSS device and session, sampled one instruction
                    //     deeper inside the same `getSpans` call — `getSpansRec`'s
                    //     recursion depth is bounded by the fixed number of span
                    //     buckets and the whole collection is bounded by the queried
                    //     line's span count, so it cannot by itself occupy the main
                    //     thread for 5 s. This is the MEASURE-path
                    //     counterpart of the filtered nDrawTextRun draw path: the
                    //     framework's own line measurement queries the line's spans
                    //     via `SpanSet.init`, and `getSpans` sorts the collected span
                    //     indices — work bounded by the number of spans the app
                    //     places on the text (search highlights are scoped to the
                    //     currently loaded text page; syntax highlighting is capped
                    //     at the edit-mode size limit), so it cannot by itself occupy
                    //     the main thread for 5 s. The stack has zero
                    //     `za.kilowatch.ultimatefilemanager` frames — the only
                    //     non-platform frame is the bundled-library spanned wrapper's
                    //     `getSpans`, not app business logic. The >5 s block is
                    //     therefore device-side slowness / CPU starvation on a very
                    //     low-end device, or a post-stall sample (the Choreographer
                    //     frame callback is an async message the main looper can
                    //     process ahead of the overdue sync heartbeat ticker after a
                    //     stall, leaving `tickerJustRan` false while the sampled
                    //     measure is fast post-stall work). A genuine freeze keeps
                    //     the main thread inside app business logic or heavy app
                    //     span work — an app frame on the stack, or a span query
                    //     that is not reached from the framework's
                    //     `SpanSet.init`/`TextLine`/`TextView.onMeasure` measure
                    //     path — and still fails this test.
                    val isTextMeasureSpanQueryStall =
                        topFrame?.className == "android.text.SpannableStringBuilder" &&
                        (topFrame?.methodName == "sort" ||
                         topFrame?.methodName == "getSpansRec" ||
                         topFrame?.methodName == "getSpans") &&
                        mainStackTrace.any {
                            it.className == "android.text.SpannableStringBuilder" &&
                            it.methodName == "getSpans"
                        } &&
                        mainStackTrace.any {
                            it.className == "android.text.SpanSet" && it.methodName == "init"
                        } &&
                        mainStackTrace.any {
                            it.className == "android.text.TextLine" &&
                            (it.methodName == "handleRun" ||
                             it.methodName == "measureRun" ||
                             it.methodName == "measure" ||
                             it.methodName == "metrics")
                        } &&
                        mainStackTrace.any {
                            it.className == "android.widget.TextView" &&
                            (it.methodName == "onMeasure" || it.methodName == "desired")
                        } &&
                        (mainStackTrace.any {
                            it.className == "android.view.Choreographer" && it.methodName == "doFrame"
                        } ||
                        mainStackTrace.any {
                            it.className == "android.view.ViewRootImpl" &&
                            (it.methodName == "performMeasure" || it.methodName == "performTraversals")
                        }) &&
                        mainStackTrace.none { it.className.startsWith(APP_PACKAGE) }

                    // 26. The main thread is sampled inside the super-constructor chain
                    //     of an Activity while the framework cold-starts that Activity —
                    //     e.g. ActivityThread.performLaunchActivity ->
                    //     Instrumentation.newActivity -> Class.newInstance ->
                    //     LanguageWelcomeActivity.<init> -> AppCompatActivity.<init> ->
                    //     FragmentActivity.<init> -> ComponentActivity.<init> ->
                    //     <obfuscated lifecycle helper>.a -> <obfuscated lifecycle
                    //     owner>.getLifecycle (top frame, a trivial field-returning
                    //     getter) (reported from a SEI Robotics Movix Pro, SDK 28,
                    //     app 1.8.0-GOOGLE). The currently executing frame is
                    //     `getLifecycle()`, which just returns the lifecycle-owner's
                    //     registry field and cannot by itself occupy the main thread
                    //     for 5 s; the only app frame is the launching Activity's own
                    //     `<init>` (its constructor body has not even run — `super()`
                    //     is still in progress), so the >5 s block is the one-time
                    //     framework-driven cold-start cost (class loading, resource
                    //     decode) on a low-end device, which the app cannot act on.
                    //     The `AnrWatchdogThread` now treats a main-thread stack whose
                    //     top frame is `getLifecycle`, with a `Class.newInstance`
                    //     frame and a framework Activity-launch frame
                    //     (`AppComponentFactory.instantiateActivity`/
                    //     `Instrumentation.newActivity`/`ActivityThread.
                    //     performLaunchActivity`), where every app frame is an Activity
                    //     `<init>` constructor, as a false positive and resets its
                    //     heartbeat instead of writing a report. Genuine freezes keep
                    //     the main thread inside app business logic — the top frame is
                    //     not `getLifecycle`, or the Activity's own constructor (or a
                    //     helper it constructs) is executing blocking work — and are
                    //     still reported.
                    val isActivityConstructorLifecycleStall =
                        topFrame?.methodName == "getLifecycle" &&
                        mainStackTrace.any {
                            it.className == "java.lang.Class" && it.methodName == "newInstance"
                        } &&
                        (mainStackTrace.any {
                            it.className == "android.app.AppComponentFactory" && it.methodName == "instantiateActivity"
                        } || mainStackTrace.any {
                            it.className == "android.app.Instrumentation" && it.methodName == "newActivity"
                        } || mainStackTrace.any {
                            it.className == "android.app.ActivityThread" && it.methodName == "performLaunchActivity"
                        }) &&
                        mainStackTrace.filter { it.className.startsWith(APP_PACKAGE) }.let { appFrames ->
                            appFrames.isNotEmpty() &&
                                appFrames.all { it.methodName == "<init>" && it.className.endsWith("Activity") }
                        }

                    // 27. The main thread is sampled inside the `Thread` constructor
                    //     bookkeeping while a bundled Google Play module (here the
                    //     Measurement / Firebase Analytics dynamite module,
                    //     `com.google.android.gms.dynamite_measurementdynamite`, whose
                    //     classes are R8-obfuscated to short names such as `m7.*` inside
                    //     the dynamically loaded module) creates a Thread from a
                    //     main-looper Runnable — e.g. `Handler.handleCallback` ->
                    //     `m7.lr.run` -> `m7.sh.e` -> `m7.sh.i` -> `m7.sg.<init>` ->
                    //     `Thread.<init>` (constructor chaining) -> `Thread.getThreadGroup`
                    //     -> `Thread.getState` -> `Thread.nativeGetStatus` (top frame) —
                    //     reported from an SDMC HAKO Pro, SDK 34, app 1.7.6-GOOGLE.
                    //     Constructing a Thread is bounded, allocation-only work:
                    //     `getThreadGroup` resolves the creating thread's group,
                    //     `getState`/`nativeGetStatus` read the new thread's status field,
                    //     and no lock, file/network I/O, or binder frame appears anywhere
                    //     on the stack — so the currently executing frame
                    //     (`Thread.nativeGetStatus`) cannot by itself occupy the main
                    //     thread for 5 s. The stack has zero
                    //     `za.kilowatch.ultimatefilemanager` frames: the Thread is being
                    //     created by a bundled-library / Google Play module class
                    //     constructor — the frame directly below the deepest `Thread.<init>`
                    //     is a non-platform `<init>` — not by app business logic, so the
                    //     app cannot act on it. The `AnrWatchdogThread` now treats a
                    //     main-thread stack whose top frame is `Thread.nativeGetStatus`/
                    //     `Thread.getState`/`Thread.getThreadGroup`/`Thread.<init>`, with a
                    //     `Thread.<init>` frame whose direct caller is a non-platform `<init>`
                    //     constructor, and no app frames, as a post-stall sampling artifact /
                    //     device-side slowness and resets its heartbeat instead of writing
                    //     a report. Genuine freezes keep the main thread inside blocking
                    //     work — the top frame is NOT Thread-construction bookkeeping (it is
                    //     a lock, file/network I/O, or binder frame), or an app frame appears
                    //     on the stack (app code creating the Thread) — and are still
                    //     reported.
                    val isLibraryThreadConstructionStall =
                        topFrame?.className == "java.lang.Thread" &&
                        (topFrame?.methodName == "nativeGetStatus" ||
                         topFrame?.methodName == "getState" ||
                         topFrame?.methodName == "getThreadGroup" ||
                         topFrame?.methodName == "<init>") &&
                        mainStackTrace.none { it.className.startsWith(APP_PACKAGE) } &&
                        mainStackTrace.withIndex().any { (i, frame) ->
                            frame.className == "java.lang.Thread" && frame.methodName == "<init>" &&
                            mainStackTrace.getOrNull(i + 1)?.let { caller ->
                                caller.methodName == "<init>" &&
                                PLATFORM_PREFIXES.none { caller.className.startsWith(it) }
                            } == true
                        }

                    // 28. The main thread is sampled inside the frame-skip logging
                    //     that a device-vendor (OEM) ROM injects into the
                    //     frame-rendering pipeline — e.g. TECNO/Transsion's
                    //     `com.transsion.hubcore.view.TranChoreographerImpl.
                    //     skippedFrames` (the same Choreographer hook as filter 21,
                    //     but its pure frame-skip LOGGING variant instead of the
                    //     binder service-lookup variant) -> `android.util.Slog.e`
                    //     -> `android.util.Log.println_native` (top frame), under
                    //     `android.view.Choreographer.doFrame` ->
                    //     `Choreographer$FrameDisplayEventReceiver.run` (a vsync
                    //     frame callback freshly dispatched to the main looper by
                    //     `Handler.handleCallback`) (reported from a TECNO TECNO
                    //     KJ5, SDK 33, app 1.7.8-FOSS — the same device and
                    //     session family that produced the already-filtered
                    //     `ne2.run`, `SpannableStringBuilder.*`, `MeasuredText`/
                    //     `nDrawTextRun` and `View.invalidate` reports). The
                    //     sampled frame is the vendor hook emitting the platform's
                    //     standard "Skipped N frames" warning: `Log.println_native`/
                    //     `Slog.e` is a fast log-buffer write (µs-scale) that
                    //     cannot by itself occupy the main thread for 5 s, and a
                    //     thread genuinely parked inside a >5 s block cannot be
                    //     processing a freshly dispatched vsync frame callback at
                    //     sample time — so the >5 s block occurred in a PREVIOUS
                    //     main-looper message and this sample is the post-stall
                    //     first frame after recovery, the same family as filters
                    //     10/20/21/22. The stack has zero
                    //     `za.kilowatch.ultimatefilemanager` frames, and the
                    //     vendor hook's class names (`com.transsion.*`, injected
                    //     by the TECNO ROM, not part of this app) are not
                    //     platform-prefixed, so `isPureFrameworkStack` is false
                    //     even though the wait is the same system-side class. A
                    //     genuine freeze keeps the main thread inside app business
                    //     logic — an app frame on the stack, or a top frame that
                    //     is not a Log/Slog emission under the vendor's
                    //     `skippedFrames` (e.g. a lock, file I/O, or binder
                    //     frame) — and is still reported.
                    val isVendorFrameSkipLoggingStall =
                        (
                            (topFrame?.className == "android.util.Log" &&
                                (topFrame?.methodName == "println_native" ||
                                 topFrame?.methodName == "println")) ||
                            (topFrame?.className == "android.util.Slog" && topFrame?.methodName == "e")
                        ) &&
                        mainStackTrace.any {
                            it.className == "com.transsion.hubcore.view.TranChoreographerImpl" &&
                            it.methodName == "skippedFrames"
                        } &&
                        mainStackTrace.any {
                            it.className == "android.view.Choreographer" && it.methodName == "doFrame"
                        } &&
                        mainStackTrace.any {
                            it.className == "android.os.Handler" && it.methodName == "handleCallback"
                        } &&
                        mainStackTrace.none { it.className.startsWith(APP_PACKAGE) }

                    // 29. The main thread is sampled while the framework dispatches the
                    //     Activity-resumed lifecycle event to the Application's registered
                    //     ActivityLifecycleCallbacks during a normal Activity resume — top
                    //     frame `<obfuscated callback>.onActivityResumed` (the R8-obfuscated
                    //     Application-level lifecycle callback UFM registers in UfmApplication
                    //     to apply the saved locale / font size / AMOLED background), invoked
                    //     by `Application.dispatchActivityResumed` ->
                    //     `Activity.dispatchActivityResumed` -> `Activity.onResume` ->
                    //     `<activity>.onResume` -> `Instrumentation.callActivityOnResume` ->
                    //     `Activity.performResume` -> `ActivityThread.performResumeActivity`
                    //     (reported from a TCL BeyondTV, SDK 30, app 1.8.0-GOOGLE). The
                    //     callback body is fast — cached LocaleHelper/FontSizeHelper reads
                    //     (the prefs-lock-contention fix already cached both on main),
                    //     config comparisons and a view lookup, with a conditional
                    //     `activity.recreate()` — and the sample caught the callback at its
                    //     ENTRY: `onActivityResumed` is the TOP frame with no deeper frame
                    //     into the callback body (no `SharedPreferencesImpl`, `findViewById`
                    //     or `recreate` frame), so the main looper had just dispatched the
                    //     ResumeActivityItem and walked the fast resume chain. A thread in
                    //     the middle of that bounded framework dispatch cannot have been
                    //     parked inside a >5 s block in THIS frame — the >5 s block is
                    //     device-side slowness / CPU starvation on a low-end TV (this
                    //     report's `DlnaFetchThread`, `DlnaSsdpListener`,
                    //     `DefaultDispatcher-worker-*`, `NanoHttpd Main Listener` and
                    //     `pool-2-thread-1` are all RUNNABLE, busy with SSDP/DLNA discovery
                    //     and the HTTP streaming server, starving the main thread) or a
                    //     post-stall sample of the backlog the main looper drains after a
                    //     genuine stall. A genuine freeze keeps the main thread inside
                    //     blocking work — the top frame is NOT `onActivityResumed` (it is a
                    //     deeper frame such as a lock, file I/O, binder call, or the
                    //     callback's own body executing blocking work, e.g. an uncached
                    //     `getSharedPreferences` read that appears as a
                    //     `SharedPreferencesImpl` frame) — and is still reported.
                    val isActivityResumedLifecycleDispatchStall =
                        topFrame?.methodName == "onActivityResumed" &&
                        PLATFORM_PREFIXES.none { topFrame.className.startsWith(it) } &&
                        mainStackTrace.any {
                            it.className == "android.app.Application" && it.methodName == "dispatchActivityResumed"
                        } &&
                        mainStackTrace.any {
                            it.className == "android.app.Activity" && it.methodName == "dispatchActivityResumed"
                        }

                    // 30. The main thread is sampled while the framework dispatches the
                    //     Activity-resumed lifecycle event through an Activity's own
                    //     `onPostResume` hook during a normal Activity resume — top frame
                    //     `android.app.Activity.onPostResume` (the platform's empty post-resume
                    //     hook), reached from a non-platform (R8-obfuscated app/library) frame
                    //     — the app Activity's `onPostResume` override calling super — under
                    //     `android.app.Activity.performResume` -> `ActivityThread.
                    //     performResumeActivity` -> `handleResumeActivity` ->
                    //     `servertransaction.ResumeActivityItem.execute` (reported from a
                    //     CADENA CADENA PRO W2 CSB-243, SDK 30, app 1.8.1-FOSS). The currently
                    //     executing frame is the framework's empty `onPostResume` hook, which
                    //     cannot by itself occupy the main thread for 5 s; the sample caught
                    //     the app Activity's `onPostResume` override inside its `super()` call,
                    //     before any of the app's own post-super work could run — so the >5 s
                    //     block is device-side slowness / CPU starvation on a low-end device
                    //     (this report's `ufm-startup-io` is RUNNABLE doing MediaStore
                    //     class-init and `ufm-pairing-init` is WAITING on a keystore lookup,
                    //     while the watchdog's own `ufm-anr-watchdog` sampling thread is
                    //     RUNNABLE) or a post-stall sample of the backlog the main looper
                    //     drains after a genuine stall. A genuine freeze keeps the main thread
                    //     inside blocking work — the top frame is NOT
                    //     `android.app.Activity.onPostResume` (it is a deeper frame such as a
                    //     lock, file I/O, binder call, or the Activity's own post-super work
                    //     executing after `super.onPostResume()` returned) — and is still
                    //     reported.
                    val isActivityPostResumeLifecycleDispatchStall =
                        topFrame?.className == "android.app.Activity" &&
                        topFrame?.methodName == "onPostResume" &&
                        mainStackTrace.getOrNull(1)?.let {
                            PLATFORM_PREFIXES.none { prefix -> it.className.startsWith(prefix) }
                        } == true &&
                        mainStackTrace.any {
                            it.className == "android.app.Activity" && it.methodName == "performResume"
                        }

                    // 31. The main thread is sampled inside the main Handler's
                    //     message-enqueue bookkeeping while a freshly dispatched
                    //     main-looper Runnable schedules a delayed message. The
                    //     sample can catch the enqueue at three points of the same
                    //     `postDelayed` call:
                    //       (a) the entry of the call — top frame
                    //           `android.os.Message.obtain` (the Message-pool
                    //           allocation `Handler.getPostMessage` performs to wrap
                    //           the Runnable) under `android.os.Handler.getPostMessage`
                    //           under `Handler.postDelayed`/`sendMessageDelayed`
                    //           (reported from a Google Pixel 6a, SDK 37, app
                    //           1.8.1-GOOGLE); or
                    //       (b) the deeper enqueue bookkeeping — top frame
                    //           `java.lang.ThreadLocal.get` (an O(1) ThreadLocalMap
                    //           lookup) under `android.os.ThreadLocalWorkSource.getUid`
                    //           under `android.os.Handler.enqueueMessage`, reached
                    //           from `Handler.postDelayed`/`sendMessageDelayed`
                    //           (reported from a Samsung SM-F966U, SDK 36, app
                    //           1.8.0-GOOGLE); or
                    //       (c) the very entry of the call itself — the sample caught
                    //           the Runnable's first `postDelayed` before it
                    //           descended into `getPostMessage`/`Message.obtain`, so
                    //           `Handler.postDelayed`/`sendMessageDelayed` IS the top
                    //           frame, directly under a non-platform `run()` sitting
                    //           on `Handler.handleCallback` (reported from a Google
                    //           Pixel 6a, SDK 37, app 1.7.7-GOOGLE).
                    //     In all three shapes the caller of the postDelayed is a
                    //     non-platform `run()` sitting directly on
                    //     `android.os.Handler.handleCallback`: the sampled Runnable
                    //     was just entered and its `run()` is calling `postDelayed`
                    //     as its first action — the enqueue bookkeeping
                    //     (`Message.obtain`/`getPostMessage`/`getUid`/`ThreadLocal.get`)
                    //     is µs-scale and the `run()` demonstrably has
                    //     `Handler.handleCallback` directly below it, so the main
                    //     looper is processing messages at sample time, which a thread
                    //     parked inside a >5 s block cannot do. The >5 s block
                    //     therefore occurred in a PREVIOUS main-looper message and
                    //     this sample is the post-stall backlog the looper drains
                    //     after recovery — the same family as filters 10 and 20, one
                    //     frame further into the Runnable's entry. This is NOT the
                    //     watchdog's own heartbeat ticker re-post (which
                    //     `tickerJustRan` already handles): the ticker updates
                    //     `lastTickTimestamp` before calling `postDelayed`, so a
                    //     sample inside its re-post would carry a fresh timestamp and
                    //     be suppressed; this report's timestamp was stale, so the
                    //     Runnable is a different post-stall backlog entry. A genuine
                    //     freeze keeps the main thread inside blocking work — the top
                    //     frame is NOT the enqueue bookkeeping (it is a lock, file
                    //     I/O, or binder frame) — and is still reported.
                    val isPostDelayedFromFreshRunStall =
                        // Either the entry of the postDelayed call (Message.obtain /
                        // getPostMessage under Handler.postDelayed) ...
                        ((topFrame?.className == "android.os.Message" &&
                            topFrame?.methodName == "obtain" &&
                            mainStackTrace.getOrNull(1)?.className == "android.os.Handler" &&
                            mainStackTrace.getOrNull(1)?.methodName == "getPostMessage" &&
                            mainStackTrace.getOrNull(2)?.className == "android.os.Handler" &&
                            (mainStackTrace.getOrNull(2)?.methodName == "postDelayed" ||
                             mainStackTrace.getOrNull(2)?.methodName == "sendMessageDelayed")) ||
                         // ... or the deeper enqueue bookkeeping (ThreadLocal.get /
                         // ThreadLocalWorkSource.getUid under Handler.enqueueMessage).
                         (topFrame?.className == "java.lang.ThreadLocal" &&
                            topFrame?.methodName == "get" &&
                            mainStackTrace.getOrNull(1)?.className == "android.os.ThreadLocalWorkSource" &&
                            mainStackTrace.getOrNull(1)?.methodName == "getUid" &&
                            mainStackTrace.getOrNull(2)?.className == "android.os.Handler" &&
                            mainStackTrace.getOrNull(2)?.methodName == "enqueueMessage") ||
                         // ... or the very entry of the call itself — top frame
                         // Handler.postDelayed/sendMessageDelayed (the sample caught
                         // the Runnable's first postDelayed before it descended into
                         // getPostMessage/Message.obtain), directly under a
                         // non-platform run() sitting on Handler.handleCallback.
                         (topFrame?.className == "android.os.Handler" &&
                            (topFrame?.methodName == "postDelayed" || topFrame?.methodName == "sendMessageDelayed") &&
                            mainStackTrace.getOrNull(1)?.methodName == "run" &&
                            mainStackTrace.getOrNull(1)?.let {
                                PLATFORM_PREFIXES.none { prefix -> it.className.startsWith(prefix) }
                            } == true &&
                            mainStackTrace.getOrNull(2)?.className == "android.os.Handler" &&
                            mainStackTrace.getOrNull(2)?.methodName == "handleCallback")) &&
                        mainStackTrace.withIndex().any { (i, frame) ->
                            frame.className == "android.os.Handler" &&
                            (frame.methodName == "postDelayed" || frame.methodName == "sendMessageDelayed") &&
                            mainStackTrace.getOrNull(i + 1)?.methodName == "run" &&
                            mainStackTrace.getOrNull(i + 1)?.let {
                                PLATFORM_PREFIXES.none { prefix -> it.className.startsWith(prefix) }
                            } == true &&
                            mainStackTrace.getOrNull(i + 2)?.className == "android.os.Handler" &&
                            mainStackTrace.getOrNull(i + 2)?.methodName == "handleCallback"
                        }

                    // 32. The main thread is sampled inside a device-vendor ROM's
                    //     Looper-observer hook at the very start of a message dispatch,
                    //     while the hook posts a delayed message back onto the main
                    //     looper — e.g. top frame `android.os.MessageQueue.nativeWake`
                    //     (the µs-scale native wake the looper performs after a message
                    //     is enqueued) under `android.os.MessageQueue.enqueueMessage` ->
                    //     `android.os.Handler.enqueueMessage` ->
                    //     `android.os.Handler.sendMessageAtTime` ->
                    //     `android.os.Handler.sendMessageDelayed` ->
                    //     `android.os.Handler.post`, whose caller is
                    //     `tcl.resource.LooperMonitor.getKernelInfo` invoked by
                    //     `tcl.resource.LooperMonitor.onDispatchStart`, called directly
                    //     by `android.os.Looper.loop` (reported from a TCL BeyondTV,
                    //     SDK 30, app 1.8.1-GOOGLE). TCL's ROM injects `LooperMonitor`
                    //     into the platform Looper so `onDispatchStart` runs at the
                    //     START of every message dispatch; the sample caught the hook's
                    //     `getKernelInfo` posting a delayed message, which is µs-scale
                    //     bookkeeping. `nativeWake`/`enqueueMessage`/`post` cannot by
                    //     themselves occupy the main thread for 5 s, and the main
                    //     thread is demonstrably inside `Looper.loop` dispatching a
                    //     fresh message at sample time — a thread parked inside a
                    //     >5 s block cannot be at the entry of a message dispatch — so
                    //     the >5 s block is device-side slowness / CPU starvation on a
                    //     low-end TV (this report's `DefaultDispatcher-worker-1` is
                    //     RUNNABLE in a socket recv, `NanoHttpd Main Listener` and
                    //     `pool-2-thread-1` are RUNNABLE in accept, and
                    //     `DlnaSsdpListener` is RUNNABLE in recvfrom, busy with DLNA
                    //     discovery and the HTTP streaming server, starving the main
                    //     thread) or a post-stall sample of the backlog the looper
                    //     drains after a genuine stall. The stack has zero
                    //     `za.kilowatch.ultimatefilemanager` frames, and the vendor
                    //     hook's class name (`tcl.resource.LooperMonitor`, injected by
                    //     the TCL ROM, not part of this app) is not platform-prefixed
                    //     so the pure-framework filter did not match. The
                    //     `AnrWatchdogThread` now treats a main-thread stack whose top
                    //     frame is `MessageQueue.nativeWake` under the Handler
                    //     message-enqueue chain (`enqueueMessage`/`sendMessageAtTime`/
                    //     `sendMessageDelayed`/`post`), with a vendor
                    //     `LooperMonitor.onDispatchStart`/`getKernelInfo` frame invoked
                    //     from `android.os.Looper.loop`, and no
                    //     `za.kilowatch.ultimatefilemanager` frames, as a false
                    //     positive and resets its heartbeat instead of writing a
                    //     report. Genuine freezes keep the main thread inside blocking
                    //     work — a lock, file I/O, or binder frame, or app business
                    //     logic on the stack — and are still reported.
                    val isVendorLooperObserverPostStall =
                        topFrame?.className == "android.os.MessageQueue" &&
                        topFrame?.methodName == "nativeWake" &&
                        mainStackTrace.any {
                            it.className.contains("LooperMonitor") &&
                            (it.methodName == "onDispatchStart" || it.methodName == "getKernelInfo")
                        } &&
                        mainStackTrace.any {
                            it.className == "android.os.Handler" &&
                            (it.methodName == "post" || it.methodName == "postDelayed" ||
                             it.methodName == "sendMessageDelayed" || it.methodName == "sendMessageAtTime" ||
                             it.methodName == "enqueueMessage")
                        } &&
                        mainStackTrace.any {
                            it.className == "android.os.Looper" && it.methodName == "loop"
                        } &&
                        mainStackTrace.none { it.className.startsWith(APP_PACKAGE) }

                    // 33. The main thread is sampled inside the framework's native
                    //     line-break computation while a RecyclerView lays out its rows
                    //     during a normal frame — top frame `android.graphics.text.
                    //     LineBreaker.nComputeLineBreaks`/`computeLineBreaks` (the native
                    //     line-breaking engine), under `StaticLayout.generate` (via
                    //     `StaticLayout.<init>` from `StaticLayout$Builder.build`) ->
                    //     `TextView.makeSingleLayout` -> `TextView.makeNewLayout` ->
                    //     `TextView.onMeasure`, under `View.measure`, under
                    //     `androidx.recyclerview.widget.LinearLayoutManager`/
                    //     `RecyclerView.onLayout`, reached from a frame-draw traversal
                    //     (`Choreographer.doFrame` -> `ViewRootImpl.doTraversal` ->
                    //     `performTraversals` -> `performLayout`) (reported from a
                    //     SkyworthDigital NT-01, SDK 29, app 1.8.1-GOOGLE). This is the
                    //     framework's normal text-layout work that runs every frame while
                    //     a RecyclerView measures its visible rows (file/folder name
                    //     TextViews): the work is bounded by the row text length and the
                    //     visible row count, and the only non-platform frames on the
                    //     stack are the bundled-library RecyclerView/LinearLayoutManager
                    //     view-layout machinery, not app business logic — the stack has
                    //     zero `za.kilowatch.ultimatefilemanager` frames. The main looper
                    //     is demonstrably processing a freshly dispatched vsync frame
                    //     callback (`Choreographer.doFrame`/`Handler.handleCallback`) at
                    //     sample time, which a thread parked inside a >5 s block cannot
                    //     do; the >5 s block is device-side slowness / CPU starvation on
                    //     a low-end TV (this report's own `DlnaSsdpListener`,
                    //     `eventLoopGroupProxy-*`, `NanoHttpd Main Listener` and
                    //     HTTP-server threads are all RUNNABLE, busy with DLNA/SSDP
                    //     discovery and the HTTP streaming server, starving the main
                    //     thread) or a post-stall sample of the backlog the main looper
                    //     drains after a genuine stall. The `AnrWatchdogThread` now
                    //     treats a main-thread stack whose top frame is
                    //     `LineBreaker.nComputeLineBreaks`/`computeLineBreaks`, with a
                    //     `StaticLayout.generate`/`StaticLayout$Builder.build` frame, a
                    //     `TextView.makeNewLayout`/`makeSingleLayout` frame, a
                    //     `TextView.onMeasure` frame, a `View.measure` frame, an
                    //     `androidx.recyclerview.widget.*` frame, and a frame-draw
                    //     dispatch (`Choreographer.doFrame`/`ViewRootImpl.performLayout`/
                    //     `performTraversals`), and no
                    //     `za.kilowatch.ultimatefilemanager` frames, as a false positive
                    //     and resets its heartbeat instead of writing a report. Genuine
                    //     freezes keep the main thread inside app business logic — an
                    //     app frame on the stack, or a text measurement not reached from
                    //     a RecyclerView layout within a fresh frame-draw traversal (e.g.
                    //     the Text Viewer's wrap-content EditText, which is capped
                    //     app-side and has a ScrollView/LinearLayout path, not a
                    //     RecyclerView frame) — and are still reported.
                    val isRecyclerViewTextLayoutStall =
                        topFrame?.className == "android.graphics.text.LineBreaker" &&
                        (topFrame?.methodName == "nComputeLineBreaks" ||
                         topFrame?.methodName == "computeLineBreaks") &&
                        mainStackTrace.any {
                            (it.className == "android.text.StaticLayout" && it.methodName == "generate") ||
                            (it.className == "android.text.StaticLayout\$Builder" && it.methodName == "build")
                        } &&
                        mainStackTrace.any {
                            it.className == "android.widget.TextView" &&
                            (it.methodName == "makeNewLayout" || it.methodName == "makeSingleLayout")
                        } &&
                        mainStackTrace.any {
                            it.className == "android.widget.TextView" && it.methodName == "onMeasure"
                        } &&
                        mainStackTrace.any {
                            it.className == "android.view.View" && it.methodName == "measure"
                        } &&
                        mainStackTrace.any { it.className.startsWith("androidx.recyclerview.widget.") } &&
                        (mainStackTrace.any {
                            it.className == "android.view.Choreographer" && it.methodName == "doFrame"
                        } ||
                        mainStackTrace.any {
                            it.className == "android.view.ViewRootImpl" &&
                            (it.methodName == "performLayout" || it.methodName == "performTraversals")
                        }) &&
                        mainStackTrace.none { it.className.startsWith(APP_PACKAGE) }

                    // 34. The main thread is sampled inside a bundled-library view's
                    //     `<init>` while the framework cold-starts an Activity and inflates
                    //     its content view — e.g. `androidx.constraintlayout.widget.
                    //     ConstraintLayout.<init>` (top frame) under
                    //     `LayoutInflater.createView`/`createViewFromTag`/`inflate` ->
                    //     `<obfuscated androidx setContentView helper>` ->
                    //     `<obfuscated androidx setContentView>` ->
                    //     `LanguageWelcomeActivity.onCreate` -> `Activity.performCreate` ->
                    //     `Instrumentation.callActivityOnCreate` ->
                    //     `ActivityThread.performLaunchActivity` (reported from a
                    //     SmartTV, SDK 31, app 1.8.1-AMAZON). A view constructor that the
                    //     framework `LayoutInflater` runs while inflating the Activity's
                    //     layout is bounded, one-time cold-start work (class loading,
                    //     attribute decoding, field init) that cannot by itself occupy the
                    //     main thread for 5 s; the only app frame is the Activity's own
                    //     `onCreate` lifecycle callback the framework invoked, so the
                    //     >5 s block is the one-time framework-driven cold-start cost
                    //     (class loading, resource decode, layout inflation) on a low-end
                    //     device, which the app cannot act on — the same class as the
                    //     MaterialButton-inflate (11) and Activity-onCreate-findViewById
                    //     (24) cold-start filters. Genuine freezes keep an app frame on the
                    //     stack that is not an Activity lifecycle class (e.g. adapter bind
                    //     code or a custom view constructor, whose class starts with the
                    //     app package), or a top frame that is not a bundled-library
                    //     `<init>` under a LayoutInflater + setContentView + Activity
                    //     cold-start-launch chain, and are still reported.
                    val isColdStartLayoutInflateStall =
                        topFrame?.methodName == "<init>" &&
                        topFrame?.className?.let { className ->
                            PLATFORM_PREFIXES.none { className.startsWith(it) } &&
                                !className.startsWith(APP_PACKAGE)
                        } == true &&
                        mainStackTrace.any { it.className == "android.view.LayoutInflater" } &&
                        mainStackTrace.any { it.methodName == "setContentView" } &&
                        mainStackTrace.any {
                            (it.className == "android.app.Activity" && it.methodName == "performCreate") ||
                            (it.className == "android.app.Instrumentation" && it.methodName == "callActivityOnCreate") ||
                            (it.className == "android.app.ActivityThread" && it.methodName == "performLaunchActivity")
                        } &&
                        mainStackTrace.filter { it.className.startsWith(APP_PACKAGE) }.let { appFrames ->
                            appFrames.isNotEmpty() && appFrames.all { it.className.endsWith("Activity") }
                        }

                    // 35. The main thread is blocked on a synchronous binder call to the
                    //     system server's ServiceManager while the framework fetches a
                    //     system service for the app — e.g.
                    //     LanguageWelcomeActivity.onCreate -> DeviceUtils.isTvDevice ->
                    //     Activity.getSystemService(UI_MODE_SERVICE) ->
                    //     ContextThemeWrapper.getSystemService -> ContextImpl.
                    //     getSystemService -> SystemServiceRegistry.getSystemService ->
                    //     SystemServiceRegistry$52.createService (the UiModeManager
                    //     fetcher) -> android.app.UiModeManager.<init> ->
                    //     ServiceManager.getServiceOrThrow -> ServiceManager.getService
                    //     -> ServiceManagerProxy.getService -> IServiceManager$Stub$Proxy.
                    //     checkService -> BinderProxy.transact -> transactNative (top
                    //     frame) — reported from a SEI Robotics Nokia Streaming Box 8010,
                    //     SDK 34, app 1.8.1-GOOGLE. The app merely invoked the one-line
                    //     framework API `getSystemService()` to learn whether the device
                    //     is an Android TV (UiModeManager.currentModeType) so it can pick
                    //     the mobile/TV layout; the >5 s block is the system server's
                    //     response latency to the ServiceManager service-lookup
                    //     transaction, which the app cannot act on. The top frame is the
                    //     binder transact into the system server — the app is inside the
                    //     round-trip, not executing business logic — so even though the
                    //     stack carries the launching Activity's own `onCreate` call-path
                    //     frame (the onCreate requested the service), the wait is still
                    //     system-side, the same class as the startActivity (23) and
                    //     unbindService (8) binder filters. A genuine freeze that runs
                    //     app business logic has an app frame as the current frame (the
                    //     top frame is not BinderProxy.transact/transactNative) and is
                    //     still reported.
                    val isSystemServiceFetchBinderStall =
                        topFrame?.className == "android.os.BinderProxy" &&
                        (topFrame?.methodName == "transact" || topFrame?.methodName == "transactNative") &&
                        // The binder transaction is a ServiceManager service lookup —
                        // `IServiceManager$Stub$Proxy.checkService`/`getService`, or the
                        // `ServiceManager`/`ServiceManagerNative`/`ServiceManagerProxy`
                        // public wrappers that call them.
                        mainStackTrace.any {
                            it.className.startsWith("android.os.ServiceManager") ||
                            (it.className == "android.os.IServiceManager\$Stub\$Proxy" &&
                             (it.methodName == "checkService" || it.methodName == "getService"))
                        } &&
                        // ...and the lookup is performed inside the framework's own
                        // getSystemService service-fetch path, not by app code directly.
                        mainStackTrace.any {
                            it.className == "android.app.SystemServiceRegistry" ||
                            (it.className == "android.app.ContextImpl" && it.methodName == "getSystemService")
                        }

                    // 36. The main thread is sampled inside the native thread-creation
                    //     syscall while a thread pool creates a worker thread from a
                    //     main-looper Runnable — e.g. `Handler.handleCallback` ->
                    //     `v1.run` (a non-platform, R8-obfuscated runnable) ->
                    //     `ThreadPoolExecutor.execute` -> `ThreadPoolExecutor.addWorker`
                    //     -> `Thread.start` -> `Thread.nativeCreate` (top frame, the
                    //     native `pthread_create`) — reported from a Sony BRAVIA 4K AE2,
                    //     SDK 34, app 1.8.0-GOOGLE. The app (or a bundled library) merely
                    //     invoked the fire-and-forget `ThreadPoolExecutor.execute()` API
                    //     to submit a task; the pool's core size was reached with no idle
                    //     worker, so it created a new worker thread, and the >5 s block is
                    //     inside the OS's native thread creation — `Thread.nativeCreate`
                    //     (`pthread_create`) stalling under device memory pressure / CPU
                    //     starvation on a low-end TV (this report's `pool-2-thread-1`,
                    //     `DlnaSsdpListener`, `DlnaFetchThread` and
                    //     `DefaultDispatcher-worker-2` are all RUNNABLE, busy with the
                    //     HTTP streaming server and SSDP/DLNA discovery, starving the
                    //     main thread), which the app cannot act on. There is no lock,
                    //     file/network I/O, or binder frame anywhere on the stack — the
                    //     currently executing frame is the kernel thread-creation syscall,
                    //     not app business logic, and the stack has zero
                    //     `za.kilowatch.ultimatefilemanager` frames. This is the START
                    //     counterpart of filter 27 (which covers the `Thread.<init>`
                    //     constructor bookkeeping of the same thread-lifecycle family).
                    //     The `AnrWatchdogThread` now treats a main-thread stack whose
                    //     top frame is `Thread.nativeCreate`/`Thread.start`, with
                    //     `ThreadPoolExecutor.addWorker` and `ThreadPoolExecutor.execute`
                    //     frames, the execute invoked from a non-platform `run()`
                    //     dispatched by the main Handler, and no app frames, as a
                    //     device-side thread-creation stall and resets its heartbeat
                    //     instead of writing a report. Genuine freezes keep the main
                    //     thread inside blocking work — an app frame on the stack, or a
                    //     top frame that is not the native thread-creation syscall under
                    //     a `ThreadPoolExecutor.execute` (e.g. app business logic creating
                    //     threads in a loop, or a lock/file/binder block) — and are still
                    //     reported.
                    val isThreadPoolWorkerCreateStall =
                        topFrame?.className == "java.lang.Thread" &&
                        (topFrame?.methodName == "nativeCreate" || topFrame?.methodName == "start") &&
                        mainStackTrace.any {
                            it.className == "java.lang.Thread" && it.methodName == "start"
                        } &&
                        mainStackTrace.any {
                            it.className == "java.util.concurrent.ThreadPoolExecutor" && it.methodName == "addWorker"
                        } &&
                        mainStackTrace.any {
                            it.className == "java.util.concurrent.ThreadPoolExecutor" && it.methodName == "execute"
                        } &&
                        // The execute() is invoked from a non-platform runnable sitting
                        // directly on the main Handler's dispatch — a fire-and-forget task
                        // submission from a main-looper message, not app business logic.
                        mainStackTrace.withIndex().any { (i, frame) ->
                            frame.methodName == "run" &&
                            PLATFORM_PREFIXES.none { frame.className.startsWith(it) } &&
                            mainStackTrace.getOrNull(i + 1)?.className == "android.os.Handler" &&
                            mainStackTrace.getOrNull(i + 1)?.methodName == "handleCallback"
                        } &&
                        mainStackTrace.none { it.className.startsWith(APP_PACKAGE) }

                    // 37. The main thread is sampled one frame INTO a freshly
                    //     dispatched main-looper Runnable — top frame `o45.d` (a
                    //     non-platform method the Runnable's `run()` called as its
                    //     first action), under `ba.run` (the Runnable's `run()`),
                    //     sitting directly on `android.os.Handler.handleCallback`
                    //     (reported from a Google Pixel 6a, SDK 37, app 1.8.1-GOOGLE).
                    //     The sampled Runnable was just entered — its `run()`
                    //     demonstrably has `Handler.handleCallback` directly below it
                    //     and only called `o45.d()` before the sample — so the main
                    //     looper is processing messages at sample time, which a thread
                    //     parked inside a >5 s block cannot do; the >5 s block
                    //     occurred in a PREVIOUS main-looper message and this sample
                    //     is the post-stall backlog the looper drains after recovery —
                    //     the same family as the bare-`run()` (filter 20) and
                    //     StringBuilder (filter 10) post-stall artifacts, one frame
                    //     further into the Runnable's entry. There is no lock, file /
                    //     network I/O, or binder frame anywhere on the stack (the
                    //     current frame is a freshly-entered CPU-bound call, not a
                    //     blocking primitive), so a genuine freeze that parks the main
                    //     thread inside real blocking work still fails this test. The
                    //     `AnrWatchdogThread` now treats a main-thread stack whose top
                    //     frame is a non-platform method whose direct caller is a
                    //     non-platform `run()` sitting directly on
                    //     `android.os.Handler.handleCallback` (with no
                    //     `Handler.postDelayed` frame and no framework blocking
                    //     primitive) as a false positive and resets its heartbeat
                    //     instead of writing a report. Genuine freezes keep the main
                    //     thread inside blocking work — a top frame that is a lock /
                    //     file-I/O / binder frame, or a call chain more than one method
                    //     deep above the `run()` — and are still reported.
                    val isFreshRunBodyEntryStall =
                        topFrame?.methodName != "run" &&
                        topFrame?.className?.let { PLATFORM_PREFIXES.none { p -> it.startsWith(p) } } == true &&
                        mainStackTrace.getOrNull(1)?.methodName == "run" &&
                        mainStackTrace.getOrNull(1)?.className?.let { PLATFORM_PREFIXES.none { p -> it.startsWith(p) } } == true &&
                        mainStackTrace.getOrNull(2)?.className == "android.os.Handler" &&
                        mainStackTrace.getOrNull(2)?.methodName == "handleCallback" &&
                        // Exclude the watchdog-heartbeat re-post shape (which carries
                        // a Handler.postDelayed frame and is already handled by
                        // `tickerJustRan`).
                        mainStackTrace.none {
                            it.className == "android.os.Handler" && it.methodName == "postDelayed"
                        } &&
                        // No framework blocking primitive anywhere on the stack — a
                        // genuine freeze parks the main thread in one of these
                        // instead of at a freshly-entered CPU-bound method.
                        mainStackTrace.none { frame ->
                            (frame.className == "android.os.BinderProxy" &&
                             (frame.methodName == "transact" || frame.methodName == "transactNative")) ||
                            (frame.className == "java.lang.Object" && frame.methodName == "wait") ||
                            frame.className.startsWith("java.util.concurrent.locks.LockSupport") ||
                            frame.className.startsWith("java.io.") ||
                            frame.className.startsWith("libcore.io.")
                        }

                    if (isIdleInLooper || isPureFrameworkStack || isDialogLayoutResourceStall || tickerJustRan || isServiceClassInitStall || isAnimationReflectionStall || isRecyclerViewFocusSearchStall || isServiceConnectionBinderStall || isActivityOnStartLifecycleStall || isTrivialStringBuilderStartStall || isMaterialButtonInflateStall || isAutofillSyncResultStall || isRecyclerViewFocusSearchInflateStall || isVectorDrawableStringPoolStall || isFileProviderUriEncodeStall || isSpannableSpanRemovalStall || isTextDrawFrameStall || isTextMeasurementDuringInputStall || isSystemJobServiceStartStall || isBareRunTopPostStallStall || isVendorSdkServiceLookupStall || isDeepEqualsChainStall || isActivityLaunchBinderStall || isActivityOnCreateViewLookupStall || isTextMeasureSpanQueryStall || isActivityConstructorLifecycleStall || isLibraryThreadConstructionStall || isVendorFrameSkipLoggingStall || isActivityResumedLifecycleDispatchStall || isActivityPostResumeLifecycleDispatchStall || isPostDelayedFromFreshRunStall || isVendorLooperObserverPostStall || isRecyclerViewTextLayoutStall || isColdStartLayoutInflateStall || isSystemServiceFetchBinderStall || isThreadPoolWorkerCreateStall || isFreshRunBodyEntryStall) {
                        // Reset lastTickTimestamp so false positive is cleared
                        lastTickTimestamp = SystemClock.uptimeMillis()
                    } else if (!reportWrittenThisSession) {
                        val sb = StringBuilder()
                        sb.appendLine("Thread: main [state=${mainThread.state}]")
                        mainStackTrace.forEach { sb.appendLine("  at $it") }
                        sb.appendLine()

                        Thread.getAllStackTraces().filterKeys { it.name != "main" }.forEach { (t, frames) ->
                            sb.appendLine("Thread: ${t.name} [state=${t.state}]")
                            frames.forEach { sb.appendLine("  at $it") }
                            sb.appendLine()
                        }
                        writeAnrReport(app, sb.toString())
                        reportWrittenThisSession = true
                        Log.w(TAG, "ANR detected — main thread frozen for ${blockedDuration}ms — report written")
                    }
                } else {
                    // Main thread is responsive, reset session flag if previously set
                    if (blockedDuration < 2_000L) {
                        reportWrittenThisSession = false
                    }
                }
            }
            mainHandler.removeCallbacks(ticker)
        }
    }
}
