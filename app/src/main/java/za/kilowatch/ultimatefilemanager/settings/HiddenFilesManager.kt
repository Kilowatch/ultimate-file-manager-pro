package za.kilowatch.ultimatefilemanager.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.UfmApplication

object HiddenFilesManager {
    private const val PREFS_NAME = "ufm_hidden_files_prefs"
    private const val KEY_SHOW_HIDDEN = "show_hidden_files"

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
}
