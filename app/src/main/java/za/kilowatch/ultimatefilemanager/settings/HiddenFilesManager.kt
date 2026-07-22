package za.kilowatch.ultimatefilemanager.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.UfmApplication
import java.util.Locale

object HiddenFilesManager {
    private const val PREFS_NAME = "ufm_hidden_files_prefs"
    private const val KEY_SHOW_HIDDEN = "show_hidden_files"

    private val JUNK_NAMES = setOf(
        // Files
        "thumbs.db",
        "ehthumbs.db",
        "ehthumbs_vista.db",
        "desktop.ini",
        "network trash folder",
        "temporary items",
        "lost+found",
        // Folders
        "\$recycle.bin",
        "system volume information",
        "@eadir",
        "#recycle",
        "@recycle",
        "#snapshot"
    )

    @Volatile
    private var prefs: SharedPreferences? = null

    @Volatile
    private var dao: HiddenFileDao? = null

    fun init(context: Context) {
        val appContext = context.applicationContext
        prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        try {
            dao = HiddenFilesDatabase.getInstance(appContext).hiddenFileDao()
        } catch (e: Exception) {
            android.util.Log.e("HiddenFilesManager", "Failed to initialize hidden files DAO", e)
        }
    }

    private fun ensureInitialized(context: Context? = null) {
        if (prefs == null || dao == null) {
            val ctx = context?.applicationContext ?: try { UfmApplication.instance } catch (_: Exception) { null }
            if (ctx != null) {
                if (prefs == null) {
                    try {
                        prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    } catch (_: Exception) {}
                }
                if (dao == null) {
                    try {
                        dao = HiddenFilesDatabase.getInstance(ctx).hiddenFileDao()
                    } catch (_: Exception) {}
                }
            }
        }
    }

    var isShowHiddenFilesEnabled: Boolean
        get() {
            ensureInitialized()
            return prefs?.getBoolean(KEY_SHOW_HIDDEN, false) ?: false
        }
        set(value) {
            ensureInitialized()
            prefs?.edit()?.putBoolean(KEY_SHOW_HIDDEN, value)?.apply()
        }

    suspend fun hide(path: String) = withContext(Dispatchers.IO) {
        ensureInitialized()
        dao?.insert(HiddenFileEntity(path))
    }

    suspend fun unhide(path: String) = withContext(Dispatchers.IO) {
        ensureInitialized()
        dao?.delete(path)
    }

    suspend fun isHidden(path: String): Boolean = withContext(Dispatchers.IO) {
        ensureInitialized()
        dao?.exists(path) ?: false
    }

    fun isJunkOrHidden(name: String): Boolean {
        if (name.startsWith(".")) return true
        val lower = name.lowercase(Locale.ROOT)
        return JUNK_NAMES.contains(lower)
    }

    fun isPathJunkOrHidden(path: String): Boolean {
        val segments = path.split('/', '\\')
        for (segment in segments) {
            if (segment.isNotEmpty() && isJunkOrHidden(segment)) {
                return true
            }
        }
        return false
    }
}
