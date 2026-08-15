package za.kilowatch.ultimatefilemanager.network

import android.content.Context

/**
 * Amazon Appstore stub for MsalProvider.
 *
 * OneDrive is not available on the Amazon build: MSAL (and its Google Play Services
 * transitive dependencies) is excluded from Amazon variants to comply with Amazon's
 * content policy. This stub ensures the main source-set references to MsalProvider
 * still compile, but any call will immediately return an error.
 *
 * Users should never reach this code path because the OneDrive option is hidden
 * in AddOnlineStorageActivity for Amazon variants.
 */
object MsalProvider {

    /**
     * Stub: always returns an error. OneDrive is unavailable on Amazon builds.
     */
    fun getApp(
        context: Context,
        callback: (Any?, Exception?) -> Unit
    ) {
        callback(null, UnsupportedOperationException(
            "OneDrive is not available on the Amazon Appstore build."
        ))
    }

    /** No-op reset — nothing to clear in the stub. */
    fun reset() { /* no-op */ }
}
