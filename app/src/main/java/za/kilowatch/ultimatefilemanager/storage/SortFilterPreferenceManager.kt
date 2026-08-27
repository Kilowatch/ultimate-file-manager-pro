package za.kilowatch.ultimatefilemanager.storage

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest
import za.kilowatch.ultimatefilemanager.util.NaturalSort

/**
 * Centralised persistence for sort & filter preferences.
 *
 * Global settings live in the existing "ufm_prefs" file (fully backward-compatible).
 * Per-folder settings live in "ufm_folder_sort_prefs" backed by [EncryptedSharedPreferences]
 * using AES-256-GCM (Android Keystore). The same security pattern as SecureTokenStore.
 *
 * **Write pattern:** all writes to the encrypted store call `commit()` (not `apply()`) and
 * MUST be dispatched on [kotlinx.coroutines.Dispatchers.IO] to avoid blocking the main thread
 * during fdatasync (see SecureTokenStore KDoc for full rationale).
 *
 * **Folder key format:**
 * - Local:   "local_" + SHA-256(absolutePath).take(40)
 * - Network: "net_"   + SHA-256("shareId:remotePath").take(40)
 *
 * A human-readable display label is stored alongside each entry under key "<hash>_label"
 * so the [FolderSortManagerActivity] can display paths without reversing hashes.
 */
object SortFilterPreferenceManager {

    private const val TAG = "SortFilterPrefManager"

    // ── Global prefs (existing, backward-compatible) ─────────────────────────
    private const val PREFS_GLOBAL = "ufm_prefs"
    private const val KEY_SORT_MODE  = "sort_mode"
    private const val KEY_SORT_ORDER = "sort_order"
    // filter_type, show_hidden, group_by_date, active_tags were not previously persisted globally
    // via this manager — they are now included for completeness, but their keys are new.
    private const val KEY_FILTER_TYPE   = "global_filter_type"
    private const val KEY_DATE_FILTER   = "global_date_filter"
    private const val KEY_SIZE_FILTER   = "global_size_filter"
    private const val KEY_SHOW_HIDDEN   = "global_show_hidden"
    private const val KEY_GROUP_BY_DATE = "global_group_by_date"
    private const val KEY_ACTIVE_TAGS   = "global_active_tags"

    // ── Per-folder encrypted prefs ────────────────────────────────────────────
    private const val PREFS_FOLDER = "ufm_folder_sort_prefs"
    private const val SUFFIX_LABEL       = "_label"
    private const val SUFFIX_SORT_MODE   = "_sort_mode"
    private const val SUFFIX_SORT_ORDER  = "_sort_order"
    private const val SUFFIX_FILTER_TYPE = "_filter_type"
    private const val SUFFIX_DATE_FILTER = "_date_filter"
    private const val SUFFIX_SIZE_FILTER = "_size_filter"
    private const val SUFFIX_SHOW_HIDDEN = "_show_hidden"
    private const val SUFFIX_GROUP_DATE  = "_group_by_date"
    private const val SUFFIX_TAGS        = "_tags"
    private const val SUFFIX_IS_NETWORK  = "_is_network"
    private const val SUFFIX_VIEW_MODE   = "_view_mode"
    private const val SUFFIX_IS_RECURSIVE = "_is_recursive"

    // ── Encrypted prefs singleton ─────────────────────────────────────────────
    @Volatile
    private var encryptedPrefs: SharedPreferences? = null

    private fun getEncryptedPrefs(context: Context): SharedPreferences? {
        encryptedPrefs?.let { return it }
        return synchronized(this) {
            encryptedPrefs ?: buildEncryptedPrefs(context.applicationContext).also { encryptedPrefs = it }
        }
    }

    private fun buildEncryptedPrefs(context: Context): SharedPreferences? {
        val masterKey = try {
            MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create master key — falling back to private prefs", e)
            return context.getSharedPreferences(PREFS_FOLDER, Context.MODE_PRIVATE)
        }

        return try {
            EncryptedSharedPreferences.create(
                context,
                PREFS_FOLDER,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            ).also { Log.d(TAG, "Encrypted folder sort prefs initialised") }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to initialise encrypted folder sort prefs, attempting self-healing recovery", e)
            try {
                // Check if there were plain / unencrypted keys in the existing file (e.g. from an old plain restore)
                val plainPrefs = context.getSharedPreferences(PREFS_FOLDER, Context.MODE_PRIVATE)
                val plainAll = plainPrefs.all
                val rescuedEntries = mutableMapOf<String, Any?>()
                for ((k, v) in plainAll) {
                    if (!k.startsWith("__androidx_security_crypto_")) {
                        rescuedEntries[k] = v
                    }
                }

                // Delete the corrupted file so Tink can re-create a clean Keystore-backed file
                val dataDir = context.applicationInfo.dataDir
                val prefsFile = java.io.File(dataDir, "shared_prefs/$PREFS_FOLDER.xml")
                if (prefsFile.exists()) {
                    prefsFile.delete()
                }

                val recovered = EncryptedSharedPreferences.create(
                    context,
                    PREFS_FOLDER,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )

                if (rescuedEntries.isNotEmpty()) {
                    val editor = recovered.edit()
                    for ((k, v) in rescuedEntries) {
                        when (v) {
                            is Int -> editor.putInt(k, v)
                            is Boolean -> editor.putBoolean(k, v)
                            is Long -> editor.putLong(k, v)
                            is Float -> editor.putFloat(k, v)
                            is String -> editor.putString(k, v)
                        }
                    }
                    editor.commit()
                    Log.i(TAG, "Rescued and re-encrypted ${rescuedEntries.size} folder sort entries")
                }
                Log.i(TAG, "Encrypted folder sort prefs recovered successfully")
                recovered
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to recreate encrypted prefs, falling back to secure private SharedPreferences", e2)
                context.getSharedPreferences(PREFS_FOLDER, Context.MODE_PRIVATE)
            }
        }
    }

    // ── Backup & Restore Helpers ──────────────────────────────────────────────

    /**
     * Retrieves all decrypted folder sort entries for backup export.
     * Must be called from a background thread (Dispatchers.IO).
     */
    fun getAllEntriesForBackup(context: Context): Map<String, Any?> {
        val prefs = getEncryptedPrefs(context) ?: return emptyMap()
        return prefs.all.filterKeys { !it.startsWith("__androidx_security_crypto_") }
    }

    /**
     * Restores folder sort entries from backup into the device's encrypted store.
     * Must be called from a background thread (Dispatchers.IO).
     */
    fun restoreEntriesFromBackup(context: Context, entries: Map<String, Any?>) {
        val prefs = getEncryptedPrefs(context) ?: return
        val editor = prefs.edit()
        for ((k, v) in entries) {
            if (k.startsWith("__androidx_security_crypto_")) continue
            when (v) {
                is Int -> editor.putInt(k, v)
                is Boolean -> editor.putBoolean(k, v)
                is Long -> editor.putLong(k, v)
                is Float -> editor.putFloat(k, v)
                is Double -> editor.putFloat(k, v.toFloat())
                is String -> editor.putString(k, v)
            }
        }
        editor.commit()
    }

    // ── Folder key helpers ────────────────────────────────────────────────────

    /**
     * Key for a local folder path.
     * Normalizes trailing slashes so paths like "/storage/emulated/0/Download" and
     * "/storage/emulated/0/Download/" produce identical keys.
     */
    fun folderKey(localPath: String): String {
        val clean = if (localPath == "/" || localPath.isEmpty()) localPath else localPath.trimEnd('/')
        return "local_" + sha256(clean).take(40)
    }

    /** Legacy unnormalized key helper for backward compatibility lookup. */
    fun legacyFolderKey(localPath: String): String =
        "local_" + sha256(localPath).take(40)

    /**
     * Key for a network/online remote path within a specific share.
     * Normalizes leading and trailing slashes so paths like "docker", "/docker",
     * "docker/", and "/docker/" produce identical keys.
     */
    fun folderKey(shareId: String, remotePath: String): String {
        val clean = remotePath.trim().trim('/')
        return "net_" + sha256("$shareId:$clean").take(40)
    }

    /** Legacy unnormalized key helper for backward compatibility lookup. */
    fun legacyFolderKey(shareId: String, remotePath: String): String =
        "net_" + sha256("$shareId:$remotePath").take(40)

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // ── Global load / save ────────────────────────────────────────────────────

    /**
     * Load global sort & filter state. Always returns a valid state (falls back to defaults).
     * Reads from the existing "ufm_prefs" keys for full backward compatibility.
     */
    fun loadGlobal(context: Context): SortFilterState {
        val prefs = context.getSharedPreferences(PREFS_GLOBAL, Context.MODE_PRIVATE)
        val sortMode = SortFilterSheet.SortMode.entries.getOrElse(
            prefs.getInt(KEY_SORT_MODE, 0)
        ) { SortFilterSheet.SortMode.NAME }
        val sortOrder = SortFilterSheet.SortOrder.entries.getOrElse(
            prefs.getInt(KEY_SORT_ORDER, 0)
        ) { SortFilterSheet.SortOrder.ASC }
        val filterType = SortFilterSheet.FilterType.entries.getOrElse(
            prefs.getInt(KEY_FILTER_TYPE, 0)
        ) { SortFilterSheet.FilterType.ALL }
        val dateFilter = SortFilterSheet.DateFilter.entries.getOrElse(
            prefs.getInt(KEY_DATE_FILTER, 0)
        ) { SortFilterSheet.DateFilter.ANY }
        val sizeFilter = SortFilterSheet.SizeFilter.entries.getOrElse(
            prefs.getInt(KEY_SIZE_FILTER, 0)
        ) { SortFilterSheet.SizeFilter.ANY }
        val showHidden = prefs.getBoolean(KEY_SHOW_HIDDEN,
            za.kilowatch.ultimatefilemanager.settings.HiddenFilesManager.isShowHiddenFilesEnabled)
        val groupByDate = prefs.getBoolean(KEY_GROUP_BY_DATE,
            za.kilowatch.ultimatefilemanager.settings.DateGroupPreferenceManager.isEnabled(context))
        val tagsRaw = prefs.getString(KEY_ACTIVE_TAGS, "") ?: ""
        val activeTags = if (tagsRaw.isEmpty()) emptySet()
        else tagsRaw.split(",").filter { it.isNotEmpty() }.toSet()
        return SortFilterState(sortMode, sortOrder, filterType, showHidden, groupByDate, activeTags, dateFilter = dateFilter, sizeFilter = sizeFilter)
    }

    /**
     * Save global sort & filter state to "ufm_prefs".
     * Writes to the same keys previously used inline across 4 files.
     */
    fun saveGlobal(context: Context, state: SortFilterState) {
        context.getSharedPreferences(PREFS_GLOBAL, Context.MODE_PRIVATE).edit()
            .putInt(KEY_SORT_MODE, state.sortMode.ordinal)
            .putInt(KEY_SORT_ORDER, state.sortOrder.ordinal)
            .putInt(KEY_FILTER_TYPE, state.filterType.ordinal)
            .putInt(KEY_DATE_FILTER, state.dateFilter.ordinal)
            .putInt(KEY_SIZE_FILTER, state.sizeFilter.ordinal)
            .putBoolean(KEY_SHOW_HIDDEN, state.showHidden)
            .putBoolean(KEY_GROUP_BY_DATE, state.groupByDate)
            .putString(KEY_ACTIVE_TAGS, state.activeTags.joinToString(","))
            .apply()
    }

    // ── Per-folder load / save ────────────────────────────────────────────────

    /**
     * Returns `true` if the folder identified by [key] has stored custom sort settings.
     */
    fun hasFolderSpecific(context: Context, key: String): Boolean {
        val prefs = getEncryptedPrefs(context) ?: return false
        return prefs.contains(key + SUFFIX_SORT_MODE)
    }

    /**
     * Load per-folder sort settings. Returns `null` if no override exists for [key],
     * which signals the caller to fall back to [loadGlobal].
     *
     * Must be called on a background thread when accessed for the first time (prefs init).
     */
    fun loadForFolder(context: Context, key: String): SortFilterState? {
        val prefs = getEncryptedPrefs(context) ?: return null
        if (!prefs.contains(key + SUFFIX_SORT_MODE)) return null
        val sortMode = SortFilterSheet.SortMode.entries.getOrElse(
            prefs.getInt(key + SUFFIX_SORT_MODE, 0)
        ) { SortFilterSheet.SortMode.NAME }
        val sortOrder = SortFilterSheet.SortOrder.entries.getOrElse(
            prefs.getInt(key + SUFFIX_SORT_ORDER, 0)
        ) { SortFilterSheet.SortOrder.ASC }
        val filterType = SortFilterSheet.FilterType.entries.getOrElse(
            prefs.getInt(key + SUFFIX_FILTER_TYPE, 0)
        ) { SortFilterSheet.FilterType.ALL }
        val dateFilter = SortFilterSheet.DateFilter.entries.getOrElse(
            prefs.getInt(key + SUFFIX_DATE_FILTER, 0)
        ) { SortFilterSheet.DateFilter.ANY }
        val sizeFilter = SortFilterSheet.SizeFilter.entries.getOrElse(
            prefs.getInt(key + SUFFIX_SIZE_FILTER, 0)
        ) { SortFilterSheet.SizeFilter.ANY }
        val showHidden = prefs.getBoolean(key + SUFFIX_SHOW_HIDDEN, false)
        val groupByDate = prefs.getBoolean(key + SUFFIX_GROUP_DATE, false)
        val tagsRaw = prefs.getString(key + SUFFIX_TAGS, "") ?: ""
        val activeTags = if (tagsRaw.isEmpty()) emptySet()
        else tagsRaw.split(",").filter { it.isNotEmpty() }.toSet()
        val viewModeStr = prefs.getString(key + SUFFIX_VIEW_MODE, null)
        val viewMode = viewModeStr?.let {
            try { ViewModeManager.ViewMode.valueOf(it) } catch (e: Exception) { null }
        }
        val isRecursive = prefs.getBoolean(key + SUFFIX_IS_RECURSIVE, false)
        return SortFilterState(sortMode, sortOrder, filterType, showHidden, groupByDate, activeTags, viewMode, isRecursive, dateFilter, sizeFilter)
    }

    /**
     * Save per-folder sort settings. Uses `commit()` (not `apply()`) per the encrypted prefs
     * write contract — MUST be called from [kotlinx.coroutines.Dispatchers.IO].
     *
     * @param key         Hashed folder key from [folderKey].
     * @param displayPath Human-readable path shown in [FolderSortManagerActivity].
     * @param state       Settings to persist.
     * @param isNetwork   `true` for network / online folders.
     */
    fun saveFolderSpecific(
        context: Context,
        key: String,
        displayPath: String,
        state: SortFilterState,
        isNetwork: Boolean = false
    ) {
        val prefs = getEncryptedPrefs(context) ?: run {
            Log.w(TAG, "Encrypted prefs unavailable — falling back to global save")
            saveGlobal(context, state)
            return
        }
        prefs.edit()
            .putString(key + SUFFIX_LABEL, displayPath)
            .putInt(key + SUFFIX_SORT_MODE, state.sortMode.ordinal)
            .putInt(key + SUFFIX_SORT_ORDER, state.sortOrder.ordinal)
            .putInt(key + SUFFIX_FILTER_TYPE, state.filterType.ordinal)
            .putInt(key + SUFFIX_DATE_FILTER, state.dateFilter.ordinal)
            .putInt(key + SUFFIX_SIZE_FILTER, state.sizeFilter.ordinal)
            .putBoolean(key + SUFFIX_SHOW_HIDDEN, state.showHidden)
            .putBoolean(key + SUFFIX_GROUP_DATE, state.groupByDate)
            .putString(key + SUFFIX_TAGS, state.activeTags.joinToString(","))
            .putBoolean(key + SUFFIX_IS_NETWORK, isNetwork)
            .putBoolean(key + SUFFIX_IS_RECURSIVE, state.isRecursive)
            .apply {
                if (state.viewMode != null) {
                    putString(key + SUFFIX_VIEW_MODE, state.viewMode.name)
                } else {
                    remove(key + SUFFIX_VIEW_MODE)
                }
            }
            .commit() // NOT apply() — see KDoc
    }

    /**
     * Remove the per-folder override for [key].
     * MUST be called from [kotlinx.coroutines.Dispatchers.IO].
     */
    fun clearFolderSpecific(context: Context, key: String) {
        val prefs = getEncryptedPrefs(context) ?: return
        prefs.edit()
            .remove(key + SUFFIX_LABEL)
            .remove(key + SUFFIX_SORT_MODE)
            .remove(key + SUFFIX_SORT_ORDER)
            .remove(key + SUFFIX_FILTER_TYPE)
            .remove(key + SUFFIX_DATE_FILTER)
            .remove(key + SUFFIX_SIZE_FILTER)
            .remove(key + SUFFIX_SHOW_HIDDEN)
            .remove(key + SUFFIX_GROUP_DATE)
            .remove(key + SUFFIX_TAGS)
            .remove(key + SUFFIX_IS_NETWORK)
            .remove(key + SUFFIX_VIEW_MODE)
            .remove(key + SUFFIX_IS_RECURSIVE)
            .commit() // NOT apply() — see KDoc
    }

    /**
     * Remove ALL per-folder overrides.
     * MUST be called from [kotlinx.coroutines.Dispatchers.IO].
     */
    fun clearAllFolderSpecific(context: Context) {
        val prefs = getEncryptedPrefs(context) ?: return
        prefs.edit().clear().commit() // NOT apply() — see KDoc
    }

    /**
     * Load sort/filter state for a local path, resolving recursive overrides from parent folders.
     * Backward-compatible with legacy keys that had trailing slashes.
     */
    fun loadForLocalPath(context: Context, localPath: String): SortFilterState? {
        val exactKey = folderKey(localPath)
        val exactState = loadForFolder(context, exactKey)
        if (exactState != null) {
            return exactState
        }

        // Backward compatibility fallback for legacy unnormalized key
        val legacyKey = legacyFolderKey(localPath)
        if (legacyKey != exactKey) {
            val legacyState = loadForFolder(context, legacyKey)
            if (legacyState != null) return legacyState
        }

        // Walk up parents
        var file = java.io.File(localPath).parentFile
        while (file != null) {
            val parentPath = file.absolutePath
            val parentKey = folderKey(parentPath)
            val parentState = loadForFolder(context, parentKey)
            if (parentState != null && parentState.isRecursive) {
                return parentState
            }
            val legParentKey = legacyFolderKey(parentPath)
            if (legParentKey != parentKey) {
                val legParentState = loadForFolder(context, legParentKey)
                if (legParentState != null && legParentState.isRecursive) {
                    return legParentState
                }
            }
            file = file.parentFile
        }
        return null
    }

    /**
     * Load sort/filter state for a network/remote path, resolving recursive overrides from parent folders.
     * Handles canonical normalized keys, legacy unnormalized keys, and subpaths for server-mode SMB shares.
     */
    fun loadForNetworkPath(context: Context, shareId: String, remotePath: String): SortFilterState? {
        val cleanPath = remotePath.trim().trim('/')
        val exactKey = folderKey(shareId, cleanPath)
        val exactState = loadForFolder(context, exactKey)
        if (exactState != null) {
            return exactState
        }

        // Backward compatibility check for legacy unnormalized key (e.g. if saved with leading slash "/docker")
        val legacyKey = legacyFolderKey(shareId, remotePath)
        if (legacyKey != exactKey) {
            val legacyState = loadForFolder(context, legacyKey)
            if (legacyState != null) return legacyState
        }

        // Backward compatibility for server-mode SMB subpaths (e.g. if saved as "subfolder" instead of "share/subfolder")
        if (cleanPath.contains('/')) {
            val subPath = cleanPath.substringAfter('/')
            val subKey = folderKey(shareId, subPath)
            if (subKey != exactKey) {
                val subState = loadForFolder(context, subKey)
                if (subState != null) return subState
            }
        }

        // Walk up parents of cleanPath (e.g. "docker/projects/src" -> "docker/projects" -> "docker")
        var path = cleanPath
        while (path.isNotEmpty() && path.contains('/')) {
            val idx = path.lastIndexOf('/')
            if (idx == -1) break
            path = path.substring(0, idx)
            val parentKey = folderKey(shareId, path)
            val parentState = loadForFolder(context, parentKey)
            if (parentState != null && parentState.isRecursive) {
                return parentState
            }
            val legParentKey = legacyFolderKey(shareId, path)
            if (legParentKey != parentKey) {
                val legParentState = loadForFolder(context, legParentKey)
                if (legParentState != null && legParentState.isRecursive) {
                    return legParentState
                }
            }
        }

        // Check share root / server root ("") if cleanPath wasn't already root
        if (cleanPath.isNotEmpty()) {
            val rootKey = folderKey(shareId, "")
            val rootState = loadForFolder(context, rootKey)
            if (rootState != null && rootState.isRecursive) {
                return rootState
            }
        }
        return null
    }

    /**
     * Unified resolver that loads settings for a path (exact match or recursive parent match).
     */
    fun loadForPath(context: Context, path: String, shareId: String? = null): SortFilterState? {
        return if (shareId != null) {
            loadForNetworkPath(context, shareId, path)
        } else {
            loadForLocalPath(context, path)
        }
    }

    /**
     * Returns true if there is an exact or recursive override active for the path.
     */
    fun hasFolderOverride(context: Context, path: String, shareId: String? = null): Boolean {
        return loadForPath(context, path, shareId) != null
    }

    /**
     * Returns all stored folder entries for display in [FolderSortManagerActivity].
     * Iterates the encrypted prefs, collects all entries that have a sort_mode key,
     * then builds [FolderSortEntry] objects.
     *
     * Must be called on a background thread.
     */
    fun getAllFolderEntries(context: Context): List<FolderSortEntry> {
        val prefs = getEncryptedPrefs(context) ?: return emptyList()
        val allKeys = prefs.all.keys
        // Collect unique entry keys (strip suffixes)
        val entryKeys = allKeys
            .filter { it.endsWith(SUFFIX_SORT_MODE) }
            .map { it.removeSuffix(SUFFIX_SORT_MODE) }
        return entryKeys.mapNotNull { key ->
            val state = loadForFolder(context, key) ?: return@mapNotNull null
            val label = prefs.getString(key + SUFFIX_LABEL, key) ?: key
            val isNetwork = prefs.getBoolean(key + SUFFIX_IS_NETWORK, false)
            FolderSortEntry(key = key, displayPath = label, isNetwork = isNetwork, state = state)
        }.sortedBy { it.displayPath }
    }

    // ── Data classes ──────────────────────────────────────────────────────────

    /**
     * Immutable snapshot of all sort & filter settings for one folder (or global).
     */
    data class SortFilterState(
        val sortMode: SortFilterSheet.SortMode = SortFilterSheet.SortMode.NAME,
        val sortOrder: SortFilterSheet.SortOrder = SortFilterSheet.SortOrder.ASC,
        val filterType: SortFilterSheet.FilterType = SortFilterSheet.FilterType.ALL,
        val showHidden: Boolean = false,
        val groupByDate: Boolean = false,
        val activeTags: Set<String> = emptySet(),
        val viewMode: ViewModeManager.ViewMode? = null,
        val isRecursive: Boolean = false,
        val dateFilter: SortFilterSheet.DateFilter = SortFilterSheet.DateFilter.ANY,
        val sizeFilter: SortFilterSheet.SizeFilter = SortFilterSheet.SizeFilter.ANY
    )

    /**
     * One entry in the [FolderSortManagerActivity] list.
     */
    data class FolderSortEntry(
        /** Hashed prefs key used to retrieve / delete the entry. */
        val key: String,
        /** Human-readable path or share label shown in the list. */
        val displayPath: String,
        /** True when this entry belongs to a network or online share. */
        val isNetwork: Boolean,
        /** The stored sort & filter settings. */
        val state: SortFilterState
    )

    // ── Comparators ────────────────────────────────────────────────────────────

    private fun getFileTypeCategoryRank(ext: String): Int {
        val lower = ext.lowercase().trimStart('.')
        return when {
            lower in SortFilterSheet.IMAGE_EXTENSIONS -> 1
            lower in SortFilterSheet.VIDEO_EXTENSIONS -> 2
            lower in SortFilterSheet.AUDIO_EXTENSIONS -> 3
            lower in SortFilterSheet.DOCUMENT_EXTENSIONS -> 4
            lower in SortFilterSheet.ARCHIVE_EXTENSIONS -> 5
            lower in SortFilterSheet.APK_EXTENSIONS -> 6
            else -> 7
        }
    }

    /**
     * Creates a comparator for java.io.File objects using the specified sort mode & order,
     * optional context for pinned files, and optionally grouping directories first.
     */
    fun getFileComparator(
        state: SortFilterState,
        context: Context? = null,
        directoriesFirst: Boolean = false
    ): Comparator<java.io.File> {
        val secondaryComparator: Comparator<java.io.File> = when (state.sortMode) {
            SortFilterSheet.SortMode.NAME -> compareBy(NaturalSort.order) { f: java.io.File -> f.name }
            SortFilterSheet.SortMode.SIZE -> compareBy { f: java.io.File -> if (f.isDirectory) 0L else f.length() }
            SortFilterSheet.SortMode.DATE -> compareBy { f: java.io.File -> f.lastModified() }
            SortFilterSheet.SortMode.TYPE -> compareBy<java.io.File> { getFileTypeCategoryRank(it.extension) }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.extension }
                .thenBy(NaturalSort.order) { it.name }
        }
        val orderedComparator = if (state.sortOrder == SortFilterSheet.SortOrder.DESC) secondaryComparator.reversed() else secondaryComparator

        return Comparator { f1, f2 ->
            if (context != null) {
                val p1 = za.kilowatch.ultimatefilemanager.settings.PinnedFilesManager.isPinned(context.applicationContext, f1.absolutePath)
                val p2 = za.kilowatch.ultimatefilemanager.settings.PinnedFilesManager.isPinned(context.applicationContext, f2.absolutePath)
                if (p1 && p2) {
                    NaturalSort.naturalCompare(f1.name, f2.name)
                } else if (p1) {
                    -1
                } else if (p2) {
                    1
                } else if (directoriesFirst) {
                    val dir1 = f1.isDirectory
                    val dir2 = f2.isDirectory
                    if (dir1 != dir2) {
                        if (dir1) -1 else 1
                    } else {
                        orderedComparator.compare(f1, f2)
                    }
                } else {
                    orderedComparator.compare(f1, f2)
                }
            } else if (directoriesFirst) {
                val dir1 = f1.isDirectory
                val dir2 = f2.isDirectory
                if (dir1 != dir2) {
                    if (dir1) -1 else 1
                } else {
                    orderedComparator.compare(f1, f2)
                }
            } else {
                orderedComparator.compare(f1, f2)
            }
        }
    }

    /**
     * Creates a comparator for NetworkFile objects using the specified sort mode & order.
     */
    fun getNetworkFileComparator(
        state: SortFilterState,
        context: Context? = null,
        shareId: String? = null,
        directoriesFirst: Boolean = false
    ): Comparator<za.kilowatch.ultimatefilemanager.network.NetworkFile> {
        val secondaryComparator: Comparator<za.kilowatch.ultimatefilemanager.network.NetworkFile> = when (state.sortMode) {
            SortFilterSheet.SortMode.NAME -> compareBy(NaturalSort.order) { it.name }
            SortFilterSheet.SortMode.SIZE -> compareBy { if (it.isDirectory) 0L else it.size }
            SortFilterSheet.SortMode.DATE -> compareBy { it.lastModified }
            SortFilterSheet.SortMode.TYPE -> compareBy<za.kilowatch.ultimatefilemanager.network.NetworkFile> {
                val ext = if (it.name.contains('.')) it.name.substringAfterLast('.') else ""
                getFileTypeCategoryRank(ext)
            }.thenBy(String.CASE_INSENSITIVE_ORDER) {
                if (it.name.contains('.')) it.name.substringAfterLast('.') else ""
            }.thenBy(NaturalSort.order) { it.name }
        }
        val orderedComparator = if (state.sortOrder == SortFilterSheet.SortOrder.DESC) secondaryComparator.reversed() else secondaryComparator

        return Comparator { f1, f2 ->
            if (context != null && shareId != null) {
                val p1 = za.kilowatch.ultimatefilemanager.settings.PinnedFilesManager.isPinned(context.applicationContext, f1.path, shareId)
                val p2 = za.kilowatch.ultimatefilemanager.settings.PinnedFilesManager.isPinned(context.applicationContext, f2.path, shareId)
                if (p1 && p2) {
                    NaturalSort.naturalCompare(f1.name, f2.name)
                } else if (p1) {
                    -1
                } else if (p2) {
                    1
                } else if (directoriesFirst) {
                    val dir1 = f1.isDirectory
                    val dir2 = f2.isDirectory
                    if (dir1 != dir2) {
                        if (dir1) -1 else 1
                    } else {
                        orderedComparator.compare(f1, f2)
                    }
                } else {
                    orderedComparator.compare(f1, f2)
                }
            } else if (directoriesFirst) {
                val dir1 = f1.isDirectory
                val dir2 = f2.isDirectory
                if (dir1 != dir2) {
                    if (dir1) -1 else 1
                } else {
                    orderedComparator.compare(f1, f2)
                }
            } else {
                orderedComparator.compare(f1, f2)
            }
        }
    }
}
