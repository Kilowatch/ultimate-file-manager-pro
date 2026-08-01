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
                    //    bundled library's static class initializer (`<clinit>`) is slow on a
                    //    low-end device. Service creation is always framework-driven —
                    //    ActivityThread.handleCreateService -> AppComponentFactory.
                    //    instantiateService -> Class.newInstance -> <clinit> — and the block
                    //    sits inside library class loading / static-init with no app frame
                    //    executing, so the app cannot act on it. A genuine freeze that
                    //    originates in app code keeps an app frame
                    //    (`za.kilowatch.ultimatefilemanager.*`) on the stack and still fails
                    //    this test.
                    val isServiceClassInitStall =
                        topFrame?.methodName == "<clinit>" &&
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

                    if (isIdleInLooper || isPureFrameworkStack || isDialogLayoutResourceStall || tickerJustRan || isServiceClassInitStall || isAnimationReflectionStall) {
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
