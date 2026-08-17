package za.kilowatch.ultimatefilemanager.util

import android.util.Log
import za.kilowatch.ultimatefilemanager.BuildConfig

/**
 * Custom logging utility to track execution flow and debug crashes.
 * High-visibility logs to distinguish from system chatter.
 */
object GoRoLog {
    private const val DEFAULT_TAG = "GoRoLog"
    private const val PREFIX = "🚀 [GoRo] "

    private val isDebug: Boolean by lazy {
        try {
            BuildConfig.DEBUG
        } catch (_: Throwable) {
            false
        }
    }

    fun d(tag: String, message: String, throwable: Throwable? = null) {
        if (isDebug) Log.d(tag, "$PREFIX$message", throwable)
    }
    fun d(message: String, throwable: Throwable? = null) = d(DEFAULT_TAG, message, throwable)

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, "$PREFIX$message", throwable)
    }
    fun e(message: String, throwable: Throwable? = null) = e(DEFAULT_TAG, message, throwable)

    fun i(tag: String, message: String, throwable: Throwable? = null) {
        if (isDebug) Log.i(tag, "$PREFIX$message", throwable)
    }
    fun i(message: String, throwable: Throwable? = null) = i(DEFAULT_TAG, message, throwable)

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        Log.w(tag, "$PREFIX$message", throwable)
    }
    fun w(message: String, throwable: Throwable? = null) = w(DEFAULT_TAG, message, throwable)
}

