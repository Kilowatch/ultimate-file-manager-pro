package za.kilowatch.ultimatefilemanager.storage

import android.content.Context
import android.content.SharedPreferences
import za.kilowatch.ultimatefilemanager.R
import java.io.File
import java.io.FileWriter

/**
 * Manager for simulating a removable USB OTG Flash Drive / External HDD.
 *
 * When [MOCK_USB_DRIVE_FEATURE_ENABLED] is true, developers and testers can test
 * drive mounting, file browsing, transfers, and safe removal (eject) without
 * needing a physical USB OTG cable or USB flash drive.
 *
 * When [MOCK_USB_DRIVE_FEATURE_ENABLED] is false, this entire feature is completely
 * disabled and hidden from Settings and from the main storage list.
 */
object MockUsbStorageManager {

    /**
     * Master feature toggle.
     * Set to true to enable the mock USB drive for testing.
     * Set to false to completely hide it from Settings and UI.
     */
    const val MOCK_USB_DRIVE_FEATURE_ENABLED = false

    const val MOCK_USB_ID = "mock_usb_drive"
    private const val PREFS_NAME = "mock_usb_storage_prefs"
    private const val KEY_MOCK_USB_ENABLED = "mock_usb_enabled"
    private const val KEY_MOCK_USB_MOUNTED = "mock_usb_mounted"

    const val ACTION_MOCK_USB_STATE_CHANGED = "za.kilowatch.ultimatefilemanager.action.MOCK_USB_STATE_CHANGED"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Returns whether the mock USB drive is enabled.
     * Always returns false if [MOCK_USB_DRIVE_FEATURE_ENABLED] is false.
     */
    fun isMockUsbEnabled(context: Context): Boolean {
        if (!MOCK_USB_DRIVE_FEATURE_ENABLED) return false
        return getPrefs(context).getBoolean(KEY_MOCK_USB_ENABLED, true)
    }

    /**
     * Toggles whether the mock USB drive is enabled in settings.
     */
    fun setMockUsbEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_MOCK_USB_ENABLED, enabled).apply()
        notifyStateChanged(context)
    }

    /**
     * Returns whether the mock USB drive is currently mounted (not ejected).
     */
    fun isMockUsbMounted(context: Context): Boolean {
        if (!isMockUsbEnabled(context)) return false
        return getPrefs(context).getBoolean(KEY_MOCK_USB_MOUNTED, true)
    }

    /**
     * Marks the mock drive as mounted or unmounted.
     */
    fun setMockUsbMounted(context: Context, mounted: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_MOCK_USB_MOUNTED, mounted).apply()
        notifyStateChanged(context)
    }

    private fun notifyStateChanged(context: Context) {
        try {
            val intent = android.content.Intent(ACTION_MOCK_USB_STATE_CHANGED).apply {
                setPackage(context.packageName)
            }
            context.sendBroadcast(intent)
        } catch (_: Exception) {}
    }

    /**
     * Safely unmounts the mock USB drive.
     */
    fun unmountMockUsb(context: Context) {
        setMockUsbMounted(context, false)
    }

    /**
     * Re-mounts the mock USB drive for testing.
     */
    fun remountMockUsb(context: Context) {
        setMockUsbMounted(context, true)
    }

    /**
     * Resolves the physical sandbox directory for the mock USB drive,
     * creating realistic sample test files if empty.
     */
    fun getMockUsbDirectory(context: Context): File {
        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        val mockUsbDir = File(baseDir, "mock_usb_drive")
        if (!mockUsbDir.exists()) {
            mockUsbDir.mkdirs()
        }
        populateSampleFilesIfEmpty(mockUsbDir)
        return mockUsbDir
    }

    private fun populateSampleFilesIfEmpty(dir: File) {
        try {
            val list = dir.listFiles()
            if (list == null || list.isEmpty()) {
                val docsDir = File(dir, "Documents").apply { mkdirs() }
                val photosDir = File(dir, "Photos").apply { mkdirs() }
                val backupsDir = File(dir, "Backups").apply { mkdirs() }

                File(dir, "readme_otg.txt").writeText(
                    "Ultimate File Manager Pro - Mock USB OTG Drive\n" +
                    "==============================================\n" +
                    "This is a simulated external USB flash drive / HDD.\n" +
                    "You can browse, create, rename, and transfer files here,\n" +
                    "as well as test the 'Safely Remove' (Eject) flow.\n"
                )

                File(docsDir, "Sample_Report.txt").writeText(
                    "Quarterly System Storage Report\n" +
                    "Status: OK\n" +
                    "Filesystem: exFAT (Simulated)\n"
                )

                File(photosDir, "DCIM_Placeholder.txt").writeText("Camera photo placeholder\n")
                File(backupsDir, "config_backup.bak").writeText("Mock backup file content\n")
            }
        } catch (_: Exception) {
            // Ignore file population errors
        }
    }

    /**
     * Creates a [StorageItem] representing the mock USB drive.
     */
    fun createMockStorageItem(context: Context): StorageItem {
        val dir = getMockUsbDirectory(context)
        val totalBytes = 32L * 1024 * 1024 * 1024 // 32 GB
        val usedBytes = 7L * 1024 * 1024 * 1024   // 7 GB

        val isMounted = isMockUsbMounted(context)
        val baseLabel = try {
            context.getString(R.string.mock_usb_title)
        } catch (_: Exception) {
            "USB Drive (Simulated OTG)"
        }
        val label = if (isMounted) baseLabel else {
            val unmountedSuffix = try { context.getString(R.string.storage_unmounted) } catch (_: Exception) { "Unmounted" }
            "$baseLabel ($unmountedSuffix)"
        }

        val subtitle = if (isMounted) {
            try {
                context.getString(R.string.mock_usb_subtitle)
            } catch (_: Exception) {
                "OTG Flash Drive (Mock / Test)"
            }
        } else {
            try {
                context.getString(R.string.storage_unmounted_tap_to_mount)
            } catch (_: Exception) {
                "Unmounted • Tap to mount"
            }
        }

        return StorageItem(
            id = MOCK_USB_ID,
            label = label,
            iconRes = R.drawable.ic_storage_usb,
            totalBytes = if (isMounted) totalBytes else 0L,
            usedBytes = if (isMounted) usedBytes else 0L,
            mountPath = if (isMounted) dir.absolutePath else "",
            isRemovable = true,
            isUnmounted = !isMounted,
            subtitle = subtitle
        )
    }

    /**
     * Checks if a given [StorageItem] is the mock USB drive.
     */
    fun isMockUsbItem(item: StorageItem): Boolean {
        return item.id == MOCK_USB_ID
    }

    /**
     * Checks if a given storage ID is the mock USB drive.
     */
    fun isMockUsbStorageId(id: String): Boolean {
        return id == MOCK_USB_ID
    }

    /**
     * Checks if a given file path is located inside the mock USB drive.
     */
    fun isMockUsbPath(context: Context, path: String): Boolean {
        val normMock = getMockUsbDirectory(context).absolutePath.replace('\\', '/').trimEnd('/')
        val normPath = path.replace('\\', '/').trimEnd('/')
        return normPath == normMock || normPath.startsWith("$normMock/")
    }
}
