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

    private lateinit var prefs: SharedPreferences
    private lateinit var dao: HiddenFileDao

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        dao = HiddenFilesDatabase.getInstance(context).hiddenFileDao()
    }

    var isShowHiddenFilesEnabled: Boolean
        get() = prefs.getBoolean(KEY_SHOW_HIDDEN, false)
        set(value) {
            prefs.edit().putBoolean(KEY_SHOW_HIDDEN, value).apply()
        }

    suspend fun hide(path: String) = withContext(Dispatchers.IO) {
        dao.insert(HiddenFileEntity(path))
    }

    suspend fun unhide(path: String) = withContext(Dispatchers.IO) {
        dao.delete(path)
    }

    suspend fun isHidden(path: String): Boolean = withContext(Dispatchers.IO) {
        dao.exists(path)
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
