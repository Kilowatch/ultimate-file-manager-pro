package za.kilowatch.ultimatefilemanager.viewer.openwith

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.util.LruCache
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.viewer.DefaultOpenManager
import za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter

/**
 * High-performance resolver that queries the Android PackageManager for all applications
 * capable of handling an ACTION_VIEW intent, deduplicating components, disambiguating
 * identical labels, prioritizing preferred and category-specialized players, and caching icons.
 */
object OpenWithResolver {

    private const val TAG = "OpenWithResolver"
    private const val ICON_CACHE_MAX_ENTRIES = 120

    // Memory-bounded LRU cache for application/activity icons
    private val iconCache = object : LruCache<String, Drawable>(ICON_CACHE_MAX_ENTRIES) {}

    // Priority package identifiers for media and document types
    private val VIDEO_AUDIO_PLAYERS = setOf(
        "com.mxtech.videoplayer.ad",
        "com.mxtech.videoplayer.pro",
        "org.videolan.vlc",
        "org.videolan.vlc.debug",
        "com.brouken.player",
        "org.xbmc.kodi",
        "is.xyz.mpv",
        "org.courville.nova",
        "com.clov4r.android.nil",
        "com.google.android.exoplayer2.demo",
        "com.google.android.youtube",
        "com.google.android.videos"
    )

    private val DOCUMENT_VIEWERS = setOf(
        "com.adobe.reader",
        "com.google.android.apps.docs",
        "com.microsoft.office.word",
        "com.microsoft.office.excel",
        "com.microsoft.office.powerpoint",
        "com.mobisystems.office",
        "cn.wps.moffice_eng"
    )

    /**
     * Queries all candidate applications capable of handling [uri] with [mimeType] off the main thread.
     */
    suspend fun resolveTargets(
        context: Context,
        uri: Uri,
        mimeType: String,
        extension: String,
        isNetwork: Boolean
    ): List<OpenWithTarget> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val selfPackage = context.packageName
        val cleanExt = extension.lowercase().trimStart('.')
        val preferredPkg = DefaultOpenManager.getPreferredPackage(context, cleanExt, isNetwork)

        // Build generic ACTION_VIEW intent to query handlers
        val queryIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val resolveList: List<ResolveInfo> = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(
                    queryIntent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(queryIntent, PackageManager.MATCH_ALL)
            }
        } catch (e: Exception) {
            emptyList()
        }

        // 1. Filter out UFM itself, disabled packages, and non-exported activities
        val validResolves = resolveList.filter { ri ->
            val ai = ri.activityInfo ?: return@filter false
            val appInfo = ai.applicationInfo ?: return@filter false

            ai.packageName != selfPackage &&
            ai.exported &&
            appInfo.enabled &&
            ai.isEnabled
        }

        // 2. Load labels and group candidates
        data class RawTarget(
            val resolveInfo: ResolveInfo,
            val label: String,
            val packageName: String,
            val activityName: String,
            val isSystemApp: Boolean
        )

        val rawList = validResolves.map { ri ->
            val ai = ri.activityInfo
            val label = runCatching { ri.loadLabel(pm).toString() }.getOrNull()?.trim()
                ?: ai.loadLabel(pm).toString().trim()
            val isSys = (ai.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0

            RawTarget(
                resolveInfo = ri,
                label = label.ifBlank { ai.packageName },
                packageName = ai.packageName,
                activityName = ai.name,
                isSystemApp = isSys
            )
        }

        // 3. Deduplicate by package + activity name; retain multiple activities from same package only if labels differ
        val deduplicated = mutableListOf<RawTarget>()
        val seenPackageLabels = mutableSetOf<String>() // "$pkg:$label"

        for (item in rawList) {
            val key = "${item.packageName}:${item.label}"
            if (seenPackageLabels.add(key)) {
                deduplicated.add(item)
            }
        }

        // 4. Disambiguation for identical labels across different packages
        val labelCounts = deduplicated.groupingBy { it.label.lowercase() }.eachCount()

        val isVideoOrAudio = FileViewerRouter.isVideo(cleanExt) || FileViewerRouter.isAudio(cleanExt)
        val isDocument = cleanExt in setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt")

        // 5. Transform to OpenWithTarget and calculate sorting priority
        val targets = deduplicated.map { raw ->
            val isPreferred = (raw.packageName == preferredPkg)
            val needsDisambiguation = (labelCounts[raw.label.lowercase()] ?: 0) > 1

            // Category ranking score (lower = higher priority)
            val rank = when {
                isPreferred -> 0
                isVideoOrAudio && raw.packageName in VIDEO_AUDIO_PLAYERS -> 1
                isDocument && raw.packageName in DOCUMENT_VIEWERS -> 1
                !raw.isSystemApp -> 2
                else -> 3
            }

            val target = OpenWithTarget(
                id = "${raw.packageName}/${raw.activityName}",
                label = raw.label,
                packageName = raw.packageName,
                activityName = raw.activityName,
                isSystemApp = raw.isSystemApp,
                isPreferred = isPreferred,
                disambiguation = if (needsDisambiguation) raw.packageName else null,
                isSystemChooserFallback = false
            )

            Pair(rank, target)
        }

        // 6. Sort: Priority rank -> Alphabetical label
        targets
            .sortedWith(compareBy({ it.first }, { it.second.label.lowercase() }))
            .map { it.second }
    }

    /**
     * Loads the target application's icon asynchronously with in-memory caching.
     */
    suspend fun loadIcon(context: Context, target: OpenWithTarget): Drawable = withContext(Dispatchers.IO) {
        if (target.isSystemChooserFallback) {
            return@withContext ContextCompat.getDrawable(context, R.drawable.ic_apps)
                ?: ContextCompat.getDrawable(context, android.R.drawable.sym_def_app_icon)
                ?: android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
        }

        val cacheKey = target.id
        iconCache.get(cacheKey)?.let { return@withContext it }

        val pm = context.packageManager
        val icon: Drawable = try {
            val intent = Intent().setClassName(target.packageName, target.activityName)
            val resolve = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.resolveActivity(intent, 0)
            }
            resolve?.loadIcon(pm)
                ?: pm.getApplicationIcon(target.packageName)
        } catch (_: Exception) {
            ContextCompat.getDrawable(context, android.R.drawable.sym_def_app_icon)
                ?: android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
        }

        iconCache.put(cacheKey, icon)
        icon
    }

    /**
     * Clears in-memory icon cache when memory is reclaimed.
     */
    fun clearCache() {
        iconCache.evictAll()
    }
}
