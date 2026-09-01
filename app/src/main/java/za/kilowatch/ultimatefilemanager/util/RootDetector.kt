package za.kilowatch.ultimatefilemanager.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import za.kilowatch.ultimatefilemanager.R
import java.io.File

/**
 * Silent, passive root detection utility.
 *
 * Scans standard filesystem binary locations, PATH environment directories,
 * package managers, build tags, and filesystem markers without invoking 'su'
 * or triggering superuser permission dialogs.
 */
object RootDetector {

    enum class RootType(val displayName: String) {
        NONE("None"),
        MAGISK("Magisk"),
        KERNEL_SU("KernelSU"),
        APATCH("APatch"),
        SUPERSU("SuperSU"),
        GENERIC_SU("su binary");

        fun getLocalizedName(context: Context): String {
            return when (this) {
                NONE -> context.getString(R.string.root_status_not_rooted)
                MAGISK -> context.getString(R.string.root_type_magisk)
                KERNEL_SU -> context.getString(R.string.root_type_kernelsu)
                APATCH -> context.getString(R.string.root_type_apatch)
                SUPERSU -> context.getString(R.string.root_type_supersu)
                GENERIC_SU -> context.getString(R.string.root_type_generic)
            }
        }
    }

    data class RootDetectionResult(
        val isRooted: Boolean,
        val rootType: RootType,
        val detectedBinaries: List<String>,
        val detectedPackages: List<String>,
        val hasTestKeys: Boolean
    ) {
        fun getSummary(context: Context): String {
            return if (isRooted) {
                context.getString(R.string.root_status_detected, rootType.getLocalizedName(context))
            } else {
                context.getString(R.string.root_status_not_rooted)
            }
        }

        fun getDetailedReport(context: Context): String {
            if (!isRooted) {
                return context.getString(R.string.root_details_none)
            }
            val sb = StringBuilder()
            if (detectedBinaries.isNotEmpty()) {
                sb.append(context.getString(R.string.root_details_binaries, detectedBinaries.joinToString(", ")))
            }
            if (detectedPackages.isNotEmpty()) {
                if (sb.isNotEmpty()) sb.append("\n\n")
                sb.append(context.getString(R.string.root_details_packages, detectedPackages.joinToString(", ")))
            }
            if (hasTestKeys) {
                if (sb.isNotEmpty()) sb.append("\n\n")
                sb.append(context.getString(R.string.root_details_test_keys))
            }
            return sb.toString()
        }
    }

    private val KNOWN_BINARY_PATHS = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/system/sd/xbin/su",
        "/system/bin/failsafe/su",
        "/data/local/xbin/su",
        "/data/local/bin/su",
        "/data/local/su",
        "/su/bin/su",
        "/apex/com.android.runtime/bin/su",
        "/system/bin/magisk",
        "/system/xbin/magisk",
        "/data/adb/ksud",
        "/data/adb/ap/bin/su",
        "/data/adb/magisk/magisk64",
        "/data/adb/magisk/magisk32",
        "/system/xbin/daemonsu"
    )

    private val KNOWN_ROOT_PACKAGES = mapOf(
        "com.topjohnwu.magisk" to RootType.MAGISK,
        "io.github.vvb2060.magisk" to RootType.MAGISK,
        "me.weishu.kernelsu" to RootType.KERNEL_SU,
        "com.rifsxd.ksunext" to RootType.KERNEL_SU,
        "me.bmax.apatch" to RootType.APATCH,
        "eu.chainfire.supersu" to RootType.SUPERSU,
        "com.noshufou.android.su" to RootType.GENERIC_SU,
        "com.koushikdutta.superuser" to RootType.GENERIC_SU,
        "com.kingroot.kinguser" to RootType.GENERIC_SU,
        "com.kingo.root" to RootType.GENERIC_SU
    )

    private val KNOWN_SU_APKS = listOf(
        "/system/app/Superuser.apk",
        "/system/app/SuperSU.apk",
        "/system/priv-app/Superuser.apk",
        "/system/priv-app/SuperSU.apk",
        "/system/app/Magisk.apk"
    )

    @Volatile
    private var cachedResult: RootDetectionResult? = null

    /**
     * Performs passive root detection or returns the cached result.
     */
    fun detect(context: Context, forceRefresh: Boolean = false): RootDetectionResult {
        if (!forceRefresh) {
            cachedResult?.let { return it }
        }

        val detectedBinaries = mutableListOf<String>()
        val detectedPackages = mutableListOf<String>()
        var foundRootType = RootType.NONE

        // 1. Check known binary paths
        for (path in KNOWN_BINARY_PATHS) {
            try {
                val file = File(path)
                if (file.exists()) {
                    detectedBinaries.add(path)
                    when {
                        path.contains("ksud") -> if (foundRootType == RootType.NONE) foundRootType = RootType.KERNEL_SU
                        path.contains("/ap/") -> if (foundRootType == RootType.NONE) foundRootType = RootType.APATCH
                        path.contains("magisk") -> if (foundRootType == RootType.NONE) foundRootType = RootType.MAGISK
                        path.contains("daemonsu") -> if (foundRootType == RootType.NONE) foundRootType = RootType.SUPERSU
                    }
                }
            } catch (_: Exception) {
                // Silently ignore filesystem access restrictions
            }
        }

        // 2. Check PATH environment variable directories
        val pathEnv = System.getenv("PATH")
        if (!pathEnv.isNullOrEmpty()) {
            val dirs = pathEnv.split(":")
            val targets = listOf("su", "magisk", "ksud", "busybox")
            for (dir in dirs) {
                for (target in targets) {
                    try {
                        val file = File(dir, target)
                        if (file.exists() && !detectedBinaries.contains(file.absolutePath)) {
                            detectedBinaries.add(file.absolutePath)
                            when (target) {
                                "ksud" -> if (foundRootType == RootType.NONE) foundRootType = RootType.KERNEL_SU
                                "magisk" -> if (foundRootType == RootType.NONE) foundRootType = RootType.MAGISK
                                "su" -> if (foundRootType == RootType.NONE) foundRootType = RootType.GENERIC_SU
                            }
                        }
                    } catch (_: Exception) {
                        // Silently ignore
                    }
                }
            }
        }

        // 3. Check known root manager packages
        val pm = context.packageManager
        for ((pkg, type) in KNOWN_ROOT_PACKAGES) {
            try {
                pm.getPackageInfo(pkg, 0)
                detectedPackages.add(pkg)
                if (foundRootType == RootType.NONE || foundRootType == RootType.GENERIC_SU) {
                    foundRootType = type
                }
            } catch (_: PackageManager.NameNotFoundException) {
                // Package not installed
            } catch (_: Exception) {
                // Ignore unexpected errors
            }
        }

        // 4. Check known Superuser APK filesystem presence
        for (apkPath in KNOWN_SU_APKS) {
            try {
                val file = File(apkPath)
                if (file.exists() && !detectedBinaries.contains(apkPath)) {
                    detectedBinaries.add(apkPath)
                }
            } catch (_: Exception) {
                // Ignore
            }
        }

        // 5. Check build tags (e.g. test-keys)
        val buildTags = Build.TAGS
        val hasTestKeys = buildTags != null && buildTags.contains("test-keys")

        val isRooted = detectedBinaries.isNotEmpty() || detectedPackages.isNotEmpty()

        if (isRooted && foundRootType == RootType.NONE) {
            foundRootType = RootType.GENERIC_SU
        }

        val result = RootDetectionResult(
            isRooted = isRooted,
            rootType = if (isRooted) foundRootType else RootType.NONE,
            detectedBinaries = detectedBinaries,
            detectedPackages = detectedPackages,
            hasTestKeys = hasTestKeys
        )

        cachedResult = result
        return result
    }

    /**
     * Fast helper checking if the device is rooted.
     */
    fun isRooted(context: Context): Boolean {
        return detect(context).isRooted
    }

    /**
     * Helper returning the detected root type.
     */
    fun getRootType(context: Context): RootType {
        return detect(context).rootType
    }

    /**
     * Invalidate cache for testing or manual refresh.
     */
    fun clearCache() {
        cachedResult = null
    }
}
