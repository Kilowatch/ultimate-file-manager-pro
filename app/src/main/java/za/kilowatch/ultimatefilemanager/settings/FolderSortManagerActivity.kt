package za.kilowatch.ultimatefilemanager.settings

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.storage.SortFilterPreferenceManager
import za.kilowatch.ultimatefilemanager.util.DeviceUtils

/**
 * Settings activity that shows all folders which have a per-folder sort override,
 * and allows the user to delete individual entries or clear all overrides.
 *
 * Follows the Activity pattern from CLAUDE.md:
 *  - attachBaseContext → LocaleHelper.wrap
 *  - ThemeHelper.applyTheme before super.onCreate
 *  - enableEdgeToEdge
 *  - dual layouts: activity_folder_sort_manager.xml (mobile) / activity_folder_sort_manager_tv.xml (TV)
 *
 * Data is read from [SortFilterPreferenceManager.getAllFolderEntries] which uses
 * [EncryptedSharedPreferences] — all reads/writes are dispatched on [Dispatchers.IO].
 */
class FolderSortManagerActivity : AppCompatActivity() {

    private var isTv = false

    private lateinit var recyclerEntries: RecyclerView
    private lateinit var layoutEmpty: LinearLayout
    private lateinit var adapter: FolderSortManagerAdapter

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        isTv = DeviceUtils.isTvDevice(this)
        setContentView(
            if (isTv) R.layout.activity_folder_sort_manager_tv
            else R.layout.activity_folder_sort_manager
        )

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val tvPad = if (isTv) (27 * resources.displayMetrics.density).toInt() else 0
            v.setPadding(
                systemBars.left + tvPad, systemBars.top + tvPad,
                systemBars.right + tvPad, systemBars.bottom + tvPad
            )
            insets
        }

        setupBack()
        setupClearAll()
        setupRecycler()
    }

    override fun onResume() {
        super.onResume()
        loadEntries()
    }

    // ── Setup ─────────────────────────────────────────────────────────────────

    private fun setupBack() {
        val btnBack = findViewById<ImageView?>(R.id.btnBack)
        if (isTv) {
            val whiteCsl = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_text_primary))
            val blackCsl = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_button_focused_yellow_text))
            btnBack?.imageTintList = whiteCsl
            btnBack?.setOnFocusChangeListener { _, hasFocus ->
                btnBack.imageTintList = if (hasFocus) blackCsl else whiteCsl
            }
        }
        btnBack?.setOnClickListener { finish() }
    }

    private fun setupClearAll() {
        val btnClearAll = findViewById<MaterialButton?>(R.id.btnClearAll)
        btnClearAll?.setOnClickListener { showClearAllDialog() }
        if (isTv) {
            val accentCsl = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_button_focused_yellow))
            val defaultCsl = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_text_primary))
            btnClearAll?.setOnFocusChangeListener { _, hasFocus ->
                btnClearAll.setTextColor(if (hasFocus) android.content.res.ColorStateList.valueOf(getColor(R.color.tv_button_focused_yellow_text)) else defaultCsl)
                btnClearAll.backgroundTintList = if (hasFocus) accentCsl else android.content.res.ColorStateList.valueOf(getColor(R.color.tv_glass_white_10))
            }
        }
    }

    private fun setupRecycler() {
        recyclerEntries = findViewById(R.id.recyclerFolderSort)
        layoutEmpty = findViewById(R.id.layoutEmpty)

        recyclerEntries.layoutManager = LinearLayoutManager(this)
        adapter = FolderSortManagerAdapter(isTv) { entry ->
            if (isTv) showDeleteEntryDialogTv(entry) else deleteEntry(entry)
        }
        recyclerEntries.adapter = adapter
    }

    // ── Data ──────────────────────────────────────────────────────────────────

    private fun loadEntries() {
        lifecycleScope.launch(Dispatchers.IO) {
            val entries = SortFilterPreferenceManager.getAllFolderEntries(this@FolderSortManagerActivity)
            withContext(Dispatchers.Main) {
                if (entries.isEmpty()) {
                    recyclerEntries.visibility = View.GONE
                    layoutEmpty.visibility = View.VISIBLE
                    // Hide Clear All when list is empty
                    findViewById<MaterialButton?>(R.id.btnClearAll)?.visibility = View.GONE
                } else {
                    recyclerEntries.visibility = View.VISIBLE
                    layoutEmpty.visibility = View.GONE
                    findViewById<MaterialButton?>(R.id.btnClearAll)?.visibility = View.VISIBLE
                    adapter.submitList(entries)
                }
            }
        }
    }

    private fun deleteEntry(entry: SortFilterPreferenceManager.FolderSortEntry) {
        lifecycleScope.launch(Dispatchers.IO) {
            SortFilterPreferenceManager.clearFolderSpecific(this@FolderSortManagerActivity, entry.key)
            withContext(Dispatchers.Main) {
                showSnackbar(getString(R.string.folder_sort_manager_deleted))
                loadEntries()
            }
        }
    }

    private fun clearAll() {
        lifecycleScope.launch(Dispatchers.IO) {
            SortFilterPreferenceManager.clearAllFolderSpecific(this@FolderSortManagerActivity)
            withContext(Dispatchers.Main) {
                showSnackbar(getString(R.string.folder_sort_manager_deleted))
                loadEntries()
            }
        }
    }

    // ── Dialogs ───────────────────────────────────────────────────────────────

    private fun showDeleteEntryDialogTv(entry: SortFilterPreferenceManager.FolderSortEntry) {
        AlertDialog.Builder(this, R.style.UFM_Dialog)
            .setTitle(R.string.network_delete_confirm_title)
            .setMessage(entry.displayPath)
            .setPositiveButton(R.string.network_delete_confirm_yes) { _, _ -> deleteEntry(entry) }
            .setNegativeButton(R.string.delete_cancel, null)
            .show()
    }

    private fun showClearAllDialog() {
        AlertDialog.Builder(this, R.style.UFM_Dialog)
            .setTitle(R.string.folder_sort_manager_clear_all)
            .setMessage(R.string.folder_sort_manager_clear_confirm)
            .setPositiveButton(R.string.network_delete_confirm_yes) { _, _ -> clearAll() }
            .setNegativeButton(R.string.delete_cancel, null)
            .show()
    }

    // ── Snackbar ──────────────────────────────────────────────────────────────

    private fun showSnackbar(message: String) {
        val rootView = findViewById<View>(R.id.main)
        if (isTv) {
            Snackbar.make(rootView, message, Snackbar.LENGTH_SHORT)
                .setBackgroundTint(getColor(R.color.tv_glass_white_10))
                .setTextColor(getColor(R.color.tv_text_primary))
                .show()
        } else {
            Snackbar.make(rootView, message, Snackbar.LENGTH_SHORT)
                .setBackgroundTint(getColor(R.color.ufm_surface_variant))
                .setTextColor(getColor(R.color.ufm_text_primary))
                .setActionTextColor(getColor(R.color.ufm_primary))
                .show()
        }
    }
}
