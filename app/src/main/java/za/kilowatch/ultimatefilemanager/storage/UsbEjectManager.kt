package za.kilowatch.ultimatefilemanager.storage

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import za.kilowatch.ultimatefilemanager.util.TransferManager
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * Manages the safe removal (eject) lifecycle for USB flash drives, external HDDs,
 * SD cards, and mock OTG drives.
 *
 * Workflow:
 * 1. Active Task Pre-flight: detects active file transfers and warns user.
 * 2. Linux Kernel Buffer Flush: invokes [android.system.Os.sync] to flush dirty page cache to physical media.
 * 3. Elevated Unmount: If Shizuku or Root is authorized, resolves volume ID and issues `sm unmount <volId>`.
 * 4. Standard Non-Root Flow: Directs user to Android OS Storage Settings to tap the native Eject button.
 * 5. Mock Drive Flow: Safely unmounts the simulated OTG volume.
 */
object UsbEjectManager {

    private const val TAG = "UsbEjectManager"

    /**
     * Volume uuids with a removal currently running — the FR-10 re-entrancy guard.
     *
     * A concurrent `keySet`, so [MutableSet.add] is atomic: two rapid taps on Safely Remove
     * cannot both pass the check. Keyed per volume, not globally, because FR-10 forbids only a
     * *second request for the same volume* — two different volumes ejecting at once each sweep
     * their own descriptors and do not interfere.
     *
     * Held for the whole user-facing flow, including while the FR-09 dialog or the Storage
     * Settings fallback is on screen. Releasing it earlier would let a second request start
     * while the first was still waiting on the user, which is exactly the re-entrancy FR-10
     * rules out.
     */
    private val inFlightVolumes: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Checks if this [StorageItem] represents a removable drive eligible for safe removal.
     */
    fun isRemovableStorage(item: StorageItem): Boolean {
        if (MockUsbStorageManager.isMockUsbItem(item)) return true
        if (item.isRootTile || item.id == "internal") return false
        if (item.mountPath == "/storage/emulated/0" || item.mountPath.startsWith("/storage/emulated/0")) return false
        return item.isRemovable
    }

    /**
     * Flushes kernel dirty page buffers to physical media via POSIX sync().
     * Safe across all Android API levels.
     */
    suspend fun flushBuffers(): Boolean = withContext(Dispatchers.IO) {
        var success = false
        // 1. Try standard system sync binary
        try {
            val process = Runtime.getRuntime().exec("sync")
            val exitCode = process.waitFor()
            if (exitCode == 0) {
                Log.d(TAG, "Runtime 'sync' executed successfully")
                success = true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Runtime exec 'sync' failed", e)
        }

        // 2. Fallback: try reflection on android.system.Os or libcore if available
        if (!success) {
            try {
                val osClass = Class.forName("android.system.Os")
                val syncMethod = osClass.getMethod("sync")
                syncMethod.invoke(null)
                Log.d(TAG, "android.system.Os.sync() invoked successfully via reflection")
                success = true
            } catch (_: Exception) {
                try {
                    val libcoreClass = Class.forName("libcore.io.Libcore")
                    val osField = libcoreClass.getField("os")
                    val osObj = osField.get(null)
                    val syncMethod = osObj?.javaClass?.getMethod("sync")
                    syncMethod?.invoke(osObj)
                    Log.d(TAG, "libcore sync invoked successfully")
                    success = true
                } catch (_: Exception) {}
            }
        }
        success
    }

    /**
     * FR-13: whether a *local path* sits on a volume that can be ejected.
     *
     * Tab mode has to decide this from the active tab's `rootPath` alone. A tab is created from
     * an intent extra, from session restore, or from the storage picker, and none of those carry
     * the `EXTRA_IS_REMOVABLE` flag the standalone browser is launched with — so deriving it
     * from the path is what makes the eject entry point work on every route in, including a
     * restored session.
     *
     * The rule is the filesystem's own: Android addresses removable media as `/storage/<volume
     * id>` (`/storage/7DE2-1219`), while the one non-removable volume any tab can reach is
     * primary storage, reachable as `/storage/emulated/0` and `/storage/self/primary`. SAF paths
     * (`saf://…`) and network shares do not start with `/storage/` at all, so they are excluded
     * by the same branch rather than by a per-storage-type rule that could fall out of date.
     */
    fun isRemovablePath(context: Context, path: String): Boolean {
        val root = path.trimEnd('/')
        if (root.isEmpty()) return false
        if (MockUsbStorageManager.isMockUsbPath(context, path)) return true
        if (!root.startsWith("/storage/")) return false
        val volumeId = root.removePrefix("/storage/").substringBefore('/')
        return volumeId.isNotEmpty() && volumeId != "emulated" && volumeId != "self"
    }

    /**
     * Checks if Shizuku or Root elevation is available for direct shell unmount.
     */
    fun isElevatedAvailable(context: Context): Boolean {
        return try {
            ShizukuShellWrapper.isAuthorized() || RootShellWrapper.isAuthorized(context)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Executes elevated shell command using Shizuku or Root.
     */
    fun runElevatedCommand(context: Context, cmd: String): Pair<Int, List<String>> {
        return when {
            ShizukuShellWrapper.isAuthorized() -> ShizukuShellWrapper.runCommand(cmd)
            RootShellWrapper.isAuthorized(context) -> RootShellWrapper.runCommand(cmd)
            else -> Pair(-1, emptyList())
        }
    }

    /**
     * Resolves the volume ID string (e.g. "public:8,1") from `sm list-volumes`.
     */
    fun resolveVolumeId(context: Context, item: StorageItem): String? {
        try {
            val (code, output) = runElevatedCommand(context, "sm list-volumes all")
            val effectiveOutput = if (code == 0 && output.isNotEmpty()) output else {
                val (pubCode, pubOutput) = runElevatedCommand(context, "sm list-volumes public")
                if (pubCode == 0) pubOutput else emptyList()
            }
            if (effectiveOutput.isNotEmpty()) {
                val targetUuid = item.id.removePrefix("unmounted_").trim()
                val pathUuid = item.mountPath.removePrefix("/storage/").substringBefore('/').trim()
                val publicVolumes = mutableListOf<String>()

                for (line in effectiveOutput) {
                    val parts = line.trim().split(Regex("\\s+"))
                    if (parts.isNotEmpty()) {
                        val volId = parts[0]
                        if (volId.startsWith("public:")) {
                            publicVolumes.add(volId)
                        }
                        // If line contains matching UUID
                        if (targetUuid.isNotBlank() && targetUuid != "removable" && line.contains(targetUuid, ignoreCase = true)) {
                            return volId
                        }
                        // If line contains UUID from mountPath (e.g. /storage/7DE2-1219)
                        if (pathUuid.isNotBlank() && pathUuid != "emulated" && line.contains(pathUuid, ignoreCase = true)) {
                            return volId
                        }
                    }
                }
                // Fallback: if only one public volume exists, it is the removable storage
                if (publicVolumes.size == 1) {
                    return publicVolumes[0]
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve volume ID via sm", e)
        }
        return null
    }

    /**
     * Attempts direct OS unmount using `sm unmount <volId>` via Shizuku or Root.
     */
    suspend fun unmountElevated(context: Context, item: StorageItem): Boolean = withContext(Dispatchers.IO) {
        try {
            val volId = resolveVolumeId(context, item) ?: item.id.removePrefix("unmounted_")
            val (code, _) = runElevatedCommand(context, "sm unmount $volId")
            code == 0
        } catch (e: Exception) {
            Log.w(TAG, "sm unmount failed", e)
            false
        }
    }

    /**
     * Attempts direct OS mount using `sm mount <volId>` via Shizuku or Root.
     */
    suspend fun mountElevated(context: Context, item: StorageItem): Boolean = withContext(Dispatchers.IO) {
        try {
            val volId = resolveVolumeId(context, item) ?: item.id.removePrefix("unmounted_")
            val (code, _) = runElevatedCommand(context, "sm mount $volId")
            code == 0
        } catch (e: Exception) {
            Log.w(TAG, "sm mount failed", e)
            false
        }
    }

    /**
     * Opens system storage settings screen directly.
     */
    fun openSystemStorageSettings(context: Context) {
        val intents = listOf(
            Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS),
            Intent(Settings.ACTION_MEMORY_CARD_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        )
        for (intent in intents) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                    return
                }
            } catch (_: Exception) {}
        }
    }

    /**
     * Initiates the full Safe Removal flow with user confirmation dialog,
     * transfer inspection, buffer flush, and unmount.
     */
    fun safelyRemove(
        activity: Activity,
        item: StorageItem,
        host: VolumeEjectHost?,
        onFinished: (Boolean) -> Unit
    ) {
        MaterialAlertDialogBuilder(activity, R.style.UFM_Dialog)
            .setTitle(activity.getString(R.string.safely_remove_title, item.label))
            .setMessage(activity.getString(R.string.safely_remove_confirm_msg, item.label))
            .setPositiveButton(R.string.safely_remove_action) { _, _ ->
                checkTransfersAndProceed(activity, item, host, onFinished)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun checkTransfersAndProceed(
        activity: Activity,
        item: StorageItem,
        host: VolumeEjectHost?,
        onFinished: (Boolean) -> Unit
    ) {
        if (TransferManager.isActiveTransfers()) {
            MaterialAlertDialogBuilder(activity, R.style.UFM_Dialog)
                .setTitle(R.string.safely_remove_transfer_active_title)
                .setMessage(R.string.safely_remove_transfer_active_msg)
                .setPositiveButton(R.string.safely_remove_action) { _, _ ->
                    performSafeRemoval(activity, item, host, onFinished)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } else {
            performSafeRemoval(activity, item, host, onFinished)
        }
    }

    private fun performSafeRemoval(
        activity: Activity,
        item: StorageItem,
        host: VolumeEjectHost?,
        onFinished: (Boolean) -> Unit
    ) {
        val volume = VolumeIdentity.from(item)

        // FR-10. Checked before the "flushing" toast so a rejected request produces no
        // misleading progress message.
        if (!inFlightVolumes.add(volume.uuid)) {
            Log.d(TAG, "Removal of ${volume.uuid} already in progress; ignoring request")
            Toast.makeText(activity, R.string.safely_remove_in_progress, Toast.LENGTH_SHORT).show()
            onFinished(false)
            return
        }

        Toast.makeText(activity, R.string.safely_remove_flushing, Toast.LENGTH_SHORT).show()

        CoroutineScope(Dispatchers.Main).launch {
            try {
                // Release every claim this process holds before asking vold to unmount. vold
                // scans /proc/<pid>/fd and /proc/<pid>/maps of every process — including the
                // one that issued the command — and SIGINTs anything still holding a
                // reference, so skipping this kills the app with no Java stack trace.
                // This replaces the old cancelIndexing block and the delay(150): the wait is
                // now a measurement of what actually got released, not a fixed guess.
                val report = VolumeClaimReleaser.releaseAll(activity, volume)

                // Mock drive: nothing real to unmount, so it never reaches the FR-09 gate.
                if (MockUsbStorageManager.isMockUsbItem(item)) {
                    MockUsbStorageManager.unmountMockUsb(activity)
                    Toast.makeText(activity, R.string.safely_remove_mock_success, Toast.LENGTH_LONG).show()
                    onFinished(true)
                    return@launch
                }

                // FR-09: the release could not free everything, so do not unmount out from
                // under a live reference without asking. Cancel keeps the volume mounted.
                if (report.hasSurvivingClaims) {
                    Log.w(TAG, "Volume ${volume.uuid} still in use before unmount; asking the user")
                    val proceed = VolumeStillInUseDialog.show(activity, volume, report)
                    if (!proceed) {
                        Log.i(TAG, "User chose to keep ${volume.uuid} mounted")
                        onFinished(false)
                        return@launch
                    }
                }

                onFinished(unmountOrGuide(activity, item, host))
            } catch (e: CancellationException) {
                // A cancelled eject must not report an outcome, but must still free the guard.
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Safe removal of ${volume.uuid} failed", e)
                onFinished(false)
            } finally {
                inFlightVolumes.remove(volume.uuid)
            }
        }
    }

    /**
     * Unmounts through Shizuku/Root when available, and otherwise guides the user to Android
     * Storage Settings. Returns true when the volume is actually unmounted — including the
     * non-root case, where ["Open Storage Settings"] counts as handing the eject off to the OS.
     *
     * Suspends while the fallback dialog is on screen so the FR-10 guard stays held until the
     * user has answered, rather than being released the moment the dialog appears.
     *
     * This is also where FR-05 happens, because "before the unmount is issued" has a different
     * moment on each path: for the elevated path it is immediately before the `sm unmount`
     * command, and for the non-root path it is the instant the user is handed to Android. Both
     * leave the volume *after* the FR-09 and fallback dialogs, which need a live Activity —
     * which is why this cannot simply run at the top of the release.
     */
    private suspend fun unmountOrGuide(
        activity: Activity,
        item: StorageItem,
        host: VolumeEjectHost?
    ): Boolean {
        // Guarantees the host is asked to leave at most once across the two paths below, since
        // a failed elevated unmount falls through to the dialog that would ask again.
        var left = false
        suspend fun leaveVolume() {
            if (left) return
            left = true
            if (!VolumeClaimReleaser.navigateOut(host)) {
                Log.w(TAG, "Host did not confirm it left ${item.label}; continuing anyway")
            }
        }

        if (isElevatedAvailable(activity)) {
            // FR-05 / FR-02: the command below is the unmount, so this is the last moment at
            // which the app may still be sitting on the volume.
            leaveVolume()
            if (unmountElevated(activity, item)) {
                Toast.makeText(
                    activity,
                    activity.getString(R.string.safely_remove_success, item.label),
                    Toast.LENGTH_LONG
                ).show()
                return true
            }
            // The elevated unmount failed, so the user is about to be offered the Storage
            // Settings fallback — which needs a live Activity that a departed host cannot give.
            if (activity.isFinishing || activity.isDestroyed) {
                Log.w(
                    TAG,
                    "Elevated unmount of ${item.label} failed after the host had already left; " +
                        "no fallback UI available"
                )
                return false
            }
        }

        val handedOff = showStandardSettingsDialog(activity, item)
        if (handedOff) leaveVolume()
        return handedOff
    }

    /**
     * Non-root fallback. Kept on `.setPositiveButton()` / `.setNegativeButton()` rather than
     * the UFMStandard embedded-button glass dialog: FR-11 leaves the neighbouring
     * `safely_remove_*` dialogs untouched, and restyling this one alone would make it
     * inconsistent with the confirm dialog that opens the very same flow.
     */
    private suspend fun showStandardSettingsDialog(
        activity: Activity,
        item: StorageItem
    ): Boolean = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            val dialog = MaterialAlertDialogBuilder(activity, R.style.UFM_Dialog)
                .setTitle(R.string.safely_remove_open_settings_title)
                .setMessage(R.string.safely_remove_open_settings_desc)
                .setPositiveButton(R.string.safely_remove_open_settings_btn) { _, _ ->
                    openSystemStorageSettings(activity)
                    if (continuation.isActive) continuation.resume(true)
                }
                .setNegativeButton(R.string.cancel) { _, _ ->
                    if (continuation.isActive) continuation.resume(false)
                }
                .setOnCancelListener {
                    if (continuation.isActive) continuation.resume(false)
                }
                .create()

            dialog.show()
            continuation.invokeOnCancellation {
                activity.runOnUiThread { dialog.dismiss() }
            }
        }
    }

    /**
     * Remounts an unmounted storage volume (USB drive, SD card, or Mock USB drive).
     */
    fun remount(
        activity: Activity,
        item: StorageItem,
        onFinished: (Boolean) -> Unit
    ) {
        // 1. Mock USB Drive
        if (MockUsbStorageManager.isMockUsbItem(item)) {
            MockUsbStorageManager.remountMockUsb(activity)
            Toast.makeText(activity, R.string.mock_usb_remounted_toast, Toast.LENGTH_SHORT).show()
            onFinished(true)
            return
        }

        // 2. Elevated (Shizuku / Root)
        if (isElevatedAvailable(activity)) {
            CoroutineScope(Dispatchers.Main).launch {
                Toast.makeText(activity, R.string.storage_remounting, Toast.LENGTH_SHORT).show()
                val success = mountElevated(activity, item)
                if (success) {
                    val cleanLabel = item.label.substringBefore(" (")
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.storage_remount_success, cleanLabel),
                        Toast.LENGTH_LONG
                    ).show()
                    onFinished(true)
                } else {
                    showMountSettingsDialog(activity, item, onFinished)
                }
            }
            return
        }

        // 3. Standard Non-Root Flow: Guide to OS storage settings
        showMountSettingsDialog(activity, item, onFinished)
    }

    private fun showMountSettingsDialog(
        activity: Activity,
        item: StorageItem,
        onFinished: (Boolean) -> Unit
    ) {
        val cleanLabel = item.label.substringBefore(" (")
        MaterialAlertDialogBuilder(activity, R.style.UFM_Dialog)
            .setTitle(activity.getString(R.string.storage_mount_title, cleanLabel))
            .setMessage(activity.getString(R.string.storage_mount_desc, cleanLabel))
            .setPositiveButton(R.string.storage_mount_btn) { _, _ ->
                openSystemStorageSettings(activity)
                onFinished(true)
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                onFinished(false)
            }
            .show()
    }
}
