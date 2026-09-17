package za.kilowatch.ultimatefilemanager.ui.elevated

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.Log
import eu.darken.porter.client.PorterClient
import rikka.shizuku.Shizuku

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

    /** The channel the SDK will use. See the note on [PorterClient.getActiveBackend]. */
    fun activeChannel(): ActiveChannel
}

/**
 * Production [ElevatedAccessProbe], reading straight from the Porter SDK.
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
        Shizuku.pingBinder()
    } catch (e: Throwable) {
        Log.w(tag, "pingBinder failed: ${e.message}")
        false
    }

    override fun isAuthorized(): Boolean = try {
        Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) {
        // IllegalStateException("Not an attached client") lands here. Reporting "not authorized"
        // is correct for a status read: we cannot prove authorization, so we must not claim it.
        Log.w(tag, "checkSelfPermission failed: ${e.message}")
        false
    }

    /**
     * The SDK's own backend selection, with its failure mode contained.
     *
     * `PorterClient.getActiveBackend()` reads this app's `<meta-data>` via
     * `isPorterOnly()` → `getApplicationInfo(pkg, GET_META_DATA)`, and throws
     * `IllegalStateException("Cannot read this app's Porter configuration")` if that read fails
     * for any reason. It is `static synchronized` and caches into a static field, so a throw
     * leaves the cache cold and every later call retries — a status screen must not inherit that
     * crash, and must not take the whole screen down over a metadata read.
     *
     * The fallback is [ActiveChannel.SHIZUKU] rather than [ActiveChannel.PORTER] deliberately:
     * claiming Porter without having read the SDK's answer would attribute the connection to an
     * app that may not even be installed.
     */
    override fun activeChannel(): ActiveChannel = try {
        when (PorterClient.getActiveBackend(context)) {
            PorterClient.Backend.PORTER -> ActiveChannel.PORTER
            else -> ActiveChannel.SHIZUKU
        }
    } catch (e: Throwable) {
        Log.w(tag, "getActiveBackend failed: ${e.message}")
        ActiveChannel.SHIZUKU
    }
}
