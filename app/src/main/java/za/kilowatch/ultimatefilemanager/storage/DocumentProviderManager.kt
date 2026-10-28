package za.kilowatch.ultimatefilemanager.storage

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.util.GoRoLog

object DocumentProviderManager {

    private const val TAG = "DocProviderMgr"

    /**
     * Synchronously queries all installed Storage Access Framework (SAF)
     * Document Providers, filtering out UFM Pro's internal provider to avoid self-referencing.
     */
    fun getInstalledProviders(context: Context): List<DocumentProviderInfo> {
        val pm = context.packageManager
        val intent = Intent("android.content.action.DOCUMENTS_PROVIDER")

        val resolveList = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentContentProviders(intent, PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentContentProviders(intent, 0)
            }
        } catch (e: Exception) {
            GoRoLog.e(TAG, "Error querying DOCUMENTS_PROVIDER", e)
            emptyList()
        }

        val providers = mutableListOf<DocumentProviderInfo>()
        val selfPackage = context.packageName

        for (resolveInfo in resolveList) {
            val provider = resolveInfo.providerInfo ?: continue

            // Exclude UFM's own provider
            if (provider.packageName == selfPackage) continue

            val label = try {
                val l = provider.loadLabel(pm).toString().trim()
                if (l.isNotEmpty()) l else provider.applicationInfo?.loadLabel(pm)?.toString()?.trim() ?: provider.authority
            } catch (_: Exception) {
                provider.authority
            }

            val icon = try {
                provider.loadIcon(pm) ?: provider.applicationInfo?.loadIcon(pm)
            } catch (_: Exception) {
                null
            }

            val nativeType = detectNativeSupport(provider.packageName, provider.authority)
            val isNative = nativeType != null
            val isTree = isTreeCapable(provider.packageName, provider.authority)

            providers.add(
                DocumentProviderInfo(
                    authority = provider.authority,
                    packageName = provider.packageName,
                    label = label,
                    icon = icon,
                    isTreeCapable = isTree,
                    isNativeSupported = isNative,
                    nativeType = nativeType
                )
            )
        }

        // Sort by label case-insensitively
        return providers.distinctBy { it.authority }.sortedBy { it.label.lowercase() }
    }

    /**
     * Asynchronously queries installed Document Providers on IO dispatcher.
     */
    suspend fun getInstalledProvidersAsync(context: Context): List<DocumentProviderInfo> =
        withContext(Dispatchers.IO) {
            getInstalledProviders(context)
        }

    /**
     * Fast count of available external document providers.
     */
    fun getProviderCount(context: Context): Int {
        return getInstalledProviders(context).size
    }

    private fun detectNativeSupport(packageName: String, authority: String): NativeCloudType? {
        val pkg = packageName.lowercase()
        val auth = authority.lowercase()
        return when {
            pkg.contains("proton") || auth.contains("proton") -> NativeCloudType.PROTON_DRIVE
            pkg.contains("com.google.android.apps.docs") || auth.contains("docs.storage") -> NativeCloudType.GOOGLE_DRIVE
            pkg.contains("synology") || auth.contains("synology") -> NativeCloudType.SYNOLOGY
            pkg.contains("davx5") || auth.contains("webdav") -> NativeCloudType.WEBDAV
            pkg.contains("termux") || auth.contains("termux") -> NativeCloudType.TERMUX
            else -> null
        }
    }

    /**
     * Determines whether this provider reliably supports ACTION_OPEN_DOCUMENT_TREE.
     * Termux and external storage providers support trees; most cloud providers
     * (Proton Drive, Google Drive, DS file) only support file-level operations.
     */
    fun isTreeCapable(packageName: String, authority: String): Boolean {
        val pkg = packageName.lowercase()
        val auth = authority.lowercase()
        return when {
            pkg.contains("termux") || auth.contains("termux") -> true
            auth.contains("externalstorage") -> true
            else -> false
        }
    }

    /**
     * Creates an ACTION_OPEN_DOCUMENT intent configured for selecting files from this provider.
     */
    fun createOpenFileIntent(authority: String): Intent {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
        return intent
    }
}
