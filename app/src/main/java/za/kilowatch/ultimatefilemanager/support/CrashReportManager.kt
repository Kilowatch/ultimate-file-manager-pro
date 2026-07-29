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

                    // If top frame is nativePollOnce, main thread is idling in looper, NOT blocked!
                    val isIdleInLooper = mainStackTrace.firstOrNull()?.methodName == "nativePollOnce"

                    if (isIdleInLooper) {
                        // Reset lastTickTimestamp so false positive is cleared when coming back from sleep/doze
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
