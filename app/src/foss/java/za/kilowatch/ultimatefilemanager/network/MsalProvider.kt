package za.kilowatch.ultimatefilemanager.network

import android.content.Context

/**
 * FOSS build stub for MsalProvider.
 *
 * OneDrive is not available in the FOSS build. MSAL (Microsoft Authentication Library)
 * transitively pulls in Google Play Services (com.google.android.gms:*) which is
 * incompatible with F-Droid's no-GMS policy. This stub ensures the codebase compiles
 * cleanly without the MSAL dependency.
 */
object MsalProvider {

    /** No-op — OneDrive / MSAL is not available in the FOSS build. */
    fun getApp(
        context: Context,
        callback: (Any?, Exception?) -> Unit
    ) {
        callback(null, UnsupportedOperationException(
            "OneDrive is not available in the FOSS build."
        ))
    }

    /** No-op — nothing to reset in the FOSS stub. */
    fun reset() { /* no-op */ }
}
