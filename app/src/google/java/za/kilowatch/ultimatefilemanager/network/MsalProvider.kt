package za.kilowatch.ultimatefilemanager.network

import android.content.Context
import com.microsoft.identity.client.IPublicClientApplication
import com.microsoft.identity.client.IMultipleAccountPublicClientApplication
import com.microsoft.identity.client.PublicClientApplication
import com.microsoft.identity.client.exception.MsalException
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.util.GoRoLog

/**
 * Singleton provider for the MSAL Multiple Account Public Client Application.
 * This ensures that only one instance of the MSAL app is used across the entire application,
 * preventing initialization collisions and state inconsistency.
 *
 * Bug fixes applied:
 *  - `instance` is now @Volatile (Bug 5: stale null reads on multi-core CPUs)
 *  - `lastError` caches initialization failures so repeated getApp() calls after an error
 *    return the error immediately instead of silently re-entering a broken init path (Bug 1)
 *  - `reset()` allows the caller (retry button, etc.) to clear all state and re-initialize
 *
 * Google Play source set: full MSAL implementation.
 * Amazon source set: see src/amazon/ for the no-op stub.
 */
object MsalProvider {
    @Volatile private var instance: IMultipleAccountPublicClientApplication? = null
    @Volatile private var isInitializing = false
    @Volatile private var lastError: MsalException? = null
    private val initializationCallbacks = mutableListOf<(IMultipleAccountPublicClientApplication?, MsalException?) -> Unit>()

    fun getApp(context: Context, callback: (IMultipleAccountPublicClientApplication?, MsalException?) -> Unit) {
        // Fast path: already initialized
        val currentInstance = instance
        if (currentInstance != null) {
            GoRoLog.d("GoRoAuth", "MsalProvider: Returning existing instance.")
            callback(currentInstance, null)
            return
        }

        // Error path: previous init attempt failed — return cached error immediately
        // so callers are not silently swallowed. The user must call reset() before retrying.
        val cachedError = lastError
        if (cachedError != null) {
            GoRoLog.w("GoRoAuth", "MsalProvider: Returning cached init error: ${cachedError.message}")
            callback(null, cachedError)
            return
        }

        synchronized(this) {
            // Re-check inside lock in case another thread just finished
            val lockedInstance = instance
            if (lockedInstance != null) {
                GoRoLog.d("GoRoAuth", "MsalProvider: Returning existing instance (inside lock).")
                callback(lockedInstance, null)
                return
            }
            val lockedError = lastError
            if (lockedError != null) {
                GoRoLog.w("GoRoAuth", "MsalProvider: Returning cached init error (inside lock).")
                callback(null, lockedError)
                return
            }

            if (isInitializing) {
                GoRoLog.d("GoRoAuth", "MsalProvider: App is already initializing, adding callback to queue.")
                initializationCallbacks.add(callback)
                return
            }

            isInitializing = true
            initializationCallbacks.add(callback)
            GoRoLog.d("GoRoAuth", "MsalProvider: Starting MSAL app initialization.")

            PublicClientApplication.createMultipleAccountPublicClientApplication(
                context.applicationContext,
                R.raw.auth_config_onedrive,
                object : IPublicClientApplication.IMultipleAccountApplicationCreatedListener {
                    override fun onCreated(application: IMultipleAccountPublicClientApplication) {
                        GoRoLog.d("GoRoAuth", "MsalProvider: App created successfully")
                        synchronized(this@MsalProvider) {
                            instance = application
                            lastError = null
                            isInitializing = false
                            val callbacks = initializationCallbacks.toList()
                            initializationCallbacks.clear()
                            callbacks.forEach { it(application, null) }
                        }
                    }

                    override fun onError(exception: MsalException) {
                        GoRoLog.e("GoRoAuth", "MsalProvider: App creation error: ${exception.message}", exception)
                        synchronized(this@MsalProvider) {
                            lastError = exception
                            isInitializing = false
                            val callbacks = initializationCallbacks.toList()
                            initializationCallbacks.clear()
                            callbacks.forEach { it(null, exception) }
                        }
                    }
                }
            )
        }
    }

    /**
     * Clears all cached state so a subsequent call to [getApp] will attempt fresh initialization.
     * Call this when the user explicitly retries after an initialization failure.
     */
    fun reset() {
        synchronized(this) {
            GoRoLog.d("GoRoAuth", "MsalProvider: reset() called — clearing instance, error, and callbacks.")
            instance = null
            lastError = null
            isInitializing = false
            initializationCallbacks.clear()
        }
    }
}
