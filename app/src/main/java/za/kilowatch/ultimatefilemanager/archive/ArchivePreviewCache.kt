package za.kilowatch.ultimatefilemanager.archive

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import java.io.File

/**
 * Owns the per-archive-session preview cache and its lifecycle.
 *
 * When a file inside an archive is previewed, it is extracted to a session-scoped
 * subdirectory under [Context.getCacheDir] rather than the whole archive being
 * extracted. Only one archive browser can be foreground at a time, so a single
 * process-wide [activeDir] reference is sufficient to track the "live" session.
 *
 * Cleanup happens on three paths:
 *  1. [purgeSession] — called when the archive browser Activity is finished.
 *  2. App-to-background — a [ProcessLifecycleOwner] observer (registered once via
 *     [registerBackgroundCleanup]) purges the session when the whole app goes to the
 *     background, so preview files never outlive the archive view even if the user
 *     never navigates back out.
 *  3. [sweepOrphans] — best-effort sweep of any leftover `archive_preview_*` dirs at
 *     app start, covering crashes / force-kills where no graceful close ran.
 */
object ArchivePreviewCache {

    private const val PREFIX = "archive_preview_"

    /** The single live session directory, if one exists. */
    @Volatile
    private var activeDir: File? = null

    @Volatile
    private var backgroundCleanupRegistered = false

    /**
     * Returns the current session directory, creating it on first use.
     * Subsequent calls within the same session return the same directory.
     */
    fun sessionDir(context: Context): File {
        activeDir?.let { return it }
        val dir = File(context.cacheDir, PREFIX + System.currentTimeMillis())
        dir.mkdirs()
        activeDir = dir
        return dir
    }

    /** Deletes the active session directory (best-effort, off the main thread). */
    fun purgeSession() {
        val dir = activeDir
        activeDir = null
        if (dir == null) return
        Thread {
            try { dir.deleteRecursively() } catch (_: Exception) {}
        }.apply { name = "archive-preview-cleanup"; isDaemon = true; start() }
    }

    /**
     * Deletes every orphaned archive-preview directory under cacheDir.
     * Called from a background thread at app start — no live session can exist yet,
     * so every matching directory is safe to remove regardless of age.
     */
    fun sweepOrphans(context: Context) {
        val cache = context.cacheDir ?: return
        val entries = cache.listFiles() ?: return
        for (entry in entries) {
            if (entry.isDirectory && entry.name.startsWith(PREFIX)) {
                try { entry.deleteRecursively() } catch (_: Exception) {}
            }
        }
    }

    /**
     * Registers a one-time app-lifecycle observer that purges the active session when
     * the app goes to the background. Safe to call repeatedly — it only registers once.
     */
    fun registerBackgroundCleanup() {
        if (backgroundCleanupRegistered) return
        backgroundCleanupRegistered = true
        try {
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStop(owner: LifecycleOwner) {
                    purgeSession()
                }
            })
        } catch (_: Exception) {
            backgroundCleanupRegistered = false
        }
    }
}
