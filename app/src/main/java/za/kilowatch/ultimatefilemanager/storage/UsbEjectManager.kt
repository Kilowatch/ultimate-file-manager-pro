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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import za.kilowatch.ultimatefilemanager.util.TransferManager

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
        onFinished: (Boolean) -> Unit
    ) {
        MaterialAlertDialogBuilder(activity, R.style.UFM_Dialog)
            .setTitle(activity.getString(R.string.safely_remove_title, item.label))
            .setMessage(activity.getString(R.string.safely_remove_confirm_msg, item.label))
            .setPositiveButton(R.string.safely_remove_action) { _, _ ->
                checkTransfersAndProceed(activity, item, onFinished)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun checkTransfersAndProceed(
        activity: Activity,
        item: StorageItem,
        onFinished: (Boolean) -> Unit
    ) {
        if (TransferManager.isActiveTransfers()) {
            MaterialAlertDialogBuilder(activity, R.style.UFM_Dialog)
                .setTitle(R.string.safely_remove_transfer_active_title)
                .setMessage(R.string.safely_remove_transfer_active_msg)
                .setPositiveButton(R.string.safely_remove_action) { _, _ ->
                    performSafeRemoval(activity, item, onFinished)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } else {
            performSafeRemoval(activity, item, onFinished)
        }
    }

    private fun performSafeRemoval(
        activity: Activity,
        item: StorageItem,
        onFinished: (Boolean) -> Unit
    ) {
        Toast.makeText(activity, R.string.safely_remove_flushing, Toast.LENGTH_SHORT).show()

        CoroutineScope(Dispatchers.Main).launch {
            // 1. Cancel background indexing / watchers on this volume
            try {
                val targetUuid = item.id.removePrefix("unmounted_").trim()
                if (targetUuid.isNotEmpty() && targetUuid != "removable") {
                    za.kilowatch.ultimatefilemanager.indexing.FileIndexingService.getInstance(activity).cancelIndexing(targetUuid)
                    za.kilowatch.ultimatefilemanager.indexing.FileIndexingService.getInstance(activity).cancelIndexing("sdcard_$targetUuid")
                }
            } catch (_: Exception) {}

            // 2. Flush Linux kernel file buffers to physical media
            flushBuffers()

            // 3. Short pause to let kernel / FUSE release closed file descriptors
            kotlinx.coroutines.delay(150)

            // 2. Handle Mock Drive case
            if (MockUsbStorageManager.isMockUsbItem(item)) {
                MockUsbStorageManager.unmountMockUsb(activity)
                Toast.makeText(activity, R.string.safely_remove_mock_success, Toast.LENGTH_LONG).show()
                onFinished(true)
                return@launch
            }

            // 3. Handle Elevated (Shizuku / Root) case
            if (isElevatedAvailable(activity)) {
                val success = unmountElevated(activity, item)
                if (success) {
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.safely_remove_success, item.label),
                        Toast.LENGTH_LONG
                    ).show()
                    onFinished(true)
                    return@launch
                }
            }

            // 4. Standard Non-Root case: Data is flushed, guide user to system storage settings
            showStandardSettingsDialog(activity, item, onFinished)
        }
    }

    private fun showStandardSettingsDialog(
        activity: Activity,
        item: StorageItem,
        onFinished: (Boolean) -> Unit
    ) {
        MaterialAlertDialogBuilder(activity, R.style.UFM_Dialog)
            .setTitle(R.string.safely_remove_open_settings_title)
            .setMessage(R.string.safely_remove_open_settings_desc)
            .setPositiveButton(R.string.safely_remove_open_settings_btn) { _, _ ->
                openSystemStorageSettings(activity)
                onFinished(true)
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                onFinished(false)
            }
            .show()
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
