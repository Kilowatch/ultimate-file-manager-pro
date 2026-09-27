package za.kilowatch.ultimatefilemanager.ui.elevated

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.Log
import eu.darken.porter.sdk.PermissionState
import eu.darken.porter.sdk.Porter
import eu.darken.porter.sdk.PorterBackend

/**
 * Which elevated-access channel the SDK has bound to.
 *
 * Two channels, not three: Porter publishes its binder to a channel of its own, while Shizuku and
 * Shevery both speak the original Shizuku protocol over one shared channel. Nothing at the client
 * end can tell those two apart — see [ElevatedAccessResolver].
 */
enum class ActiveChannel { PORTER, SHIZUKU }

/**
 * Every system fact [ElevatedAccessResolver] needs, behind one seam so the resolver's decision logic
 * is testable without a device.
 *
 * **Implementations must be read-only.** A status screen that binds, pushes or requests anything as
 * a side effect of being displayed would be both surprising and racy. In particular this is why
 * [isAuthorized] must not be routed through `ShizukuShellWrapper.isAuthorized()`, which calls
 * `checkPermissionSafely()` and that falls back to `tryBindShevery()` — a bind. Display must not
 * mutate.
 */
interface ElevatedAccessProbe {
    fun isInstalled(packageName: String): Boolean

    /** The installed app's own launcher icon, or null if unavailable. */
    fun launcherIcon(packageName: String): Drawable?

    /** Whether a manager's service is currently attached to this process. */
    fun isServiceBound(): Boolean

    /** Whether UFM holds permission on the attached service. Only meaningful if [isServiceBound]. */
    fun isAuthorized(): Boolean

    /** The channel the SDK will use. */
    fun activeChannel(): ActiveChannel
}

/**
 * Production [ElevatedAccessProbe], reading straight from the Porter SDK 0.7.0.
 */
class SystemElevatedAccessProbe(private val context: Context) : ElevatedAccessProbe {

    private val tag = "ElevatedAccessProbe"

    override fun isInstalled(packageName: String): Boolean = try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (_: Exception) {
        false
    }

    override fun launcherIcon(packageName: String): Drawable? = try {
        context.packageManager.getApplicationIcon(packageName)
    } catch (_: Exception) {
        null
    }

    override fun isServiceBound(): Boolean = try {
        Porter.connection.value != null
    } catch (e: Throwable) {
        Log.w(tag, "isServiceBound failed: ${e.message}")
        false
    }

    override fun isAuthorized(): Boolean = try {
        Porter.connection.value?.let { it.permission.value is PermissionState.Granted } ?: false
    } catch (e: Throwable) {
        Log.w(tag, "isAuthorized failed: ${e.message}")
        false
    }

    /**
     * The active backend channel.
     * When a service is bound, reflects the live connection's backend.
     * When unbound, reflects Porter if installed (since an installed Porter holds the selection),
     * otherwise falls back to Shizuku.
     */
    override fun activeChannel(): ActiveChannel = try {
        val liveConnection = Porter.connection.value
        if (liveConnection != null) {
            when (liveConnection.backend) {
                PorterBackend.PORTER -> ActiveChannel.PORTER
                PorterBackend.SHIZUKU -> ActiveChannel.SHIZUKU
            }
        } else {
            if (isInstalled(ElevatedManager.PORTER.packageName)) {
                ActiveChannel.PORTER
            } else {
                ActiveChannel.SHIZUKU
            }
        }
    } catch (e: Throwable) {
        Log.w(tag, "activeChannel failed: ${e.message}")
        ActiveChannel.SHIZUKU
    }
}

