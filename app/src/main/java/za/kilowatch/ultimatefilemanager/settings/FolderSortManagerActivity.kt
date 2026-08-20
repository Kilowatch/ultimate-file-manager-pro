package za.kilowatch.ultimatefilemanager.settings

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
 * Supports both Mobile (frosted glass) and Android TV (yellow focus) themes.
 */
class FolderSortManagerActivity : AppCompatActivity() {

    private var isTv = false

    private lateinit var recyclerEntries: RecyclerView
    private lateinit var layoutEmpty: View
    private lateinit var cardInfo: View
    private lateinit var btnClearAll: View
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
        btnClearAll = findViewById(R.id.btnClearAll)
        btnClearAll.setOnClickListener { showClearAllDialog() }
        if (isTv) {
            val whiteCsl = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_text_primary))
            val blackCsl = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_button_focused_yellow_text))
            val btn = btnClearAll as? ImageView
            btn?.imageTintList = whiteCsl
            btn?.setOnFocusChangeListener { _, hasFocus ->
                btn.imageTintList = if (hasFocus) blackCsl else whiteCsl
            }
        }
    }

    private fun setupRecycler() {
        cardInfo = findViewById(R.id.cardInfo)
        recyclerEntries = findViewById(R.id.recyclerFolderSort)
        layoutEmpty = findViewById(R.id.layoutEmpty)

        recyclerEntries.layoutManager = LinearLayoutManager(this)
        adapter = FolderSortManagerAdapter(isTv) { entry ->
            showDeleteEntryDialog(entry)
        }
        recyclerEntries.adapter = adapter
    }

    // ── Data ──────────────────────────────────────────────────────────────────

    private fun loadEntries() {
        lifecycleScope.launch(Dispatchers.IO) {
            val entries = SortFilterPreferenceManager.getAllFolderEntries(this@FolderSortManagerActivity)
            withContext(Dispatchers.Main) {
                val isEmpty = entries.isEmpty()
                if (isEmpty) {
                    recyclerEntries.visibility = View.GONE
                    cardInfo.visibility = View.GONE
                    btnClearAll.visibility = View.GONE
                    layoutEmpty.visibility = View.VISIBLE
                } else {
                    recyclerEntries.visibility = View.VISIBLE
                    cardInfo.visibility = View.VISIBLE
                    btnClearAll.visibility = View.VISIBLE
                    layoutEmpty.visibility = View.GONE
                    adapter.submitList(entries)
                }
            }
        }
    }

    private fun deleteEntry(entry: SortFilterPreferenceManager.FolderSortEntry) {
        lifecycleScope.launch(Dispatchers.IO) {
            SortFilterPreferenceManager.clearFolderSpecific(this@FolderSortManagerActivity, entry.key)
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@FolderSortManagerActivity,
                    getString(R.string.folder_sort_manager_deleted, entry.displayPath),
                    Toast.LENGTH_SHORT
                ).show()
                loadEntries()
            }
        }
    }

    private fun clearAll() {
        lifecycleScope.launch(Dispatchers.IO) {
            SortFilterPreferenceManager.clearAllFolderSpecific(this@FolderSortManagerActivity)
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@FolderSortManagerActivity,
                    R.string.folder_sort_manager_cleared_all_toast,
                    Toast.LENGTH_SHORT
                ).show()
                loadEntries()
            }
        }
    }

    // ── Dialogs ───────────────────────────────────────────────────────────────

    private fun showDeleteEntryDialog(entry: SortFilterPreferenceManager.FolderSortEntry) {
        val dialogView = LayoutInflater.from(this).inflate(
            if (isTv) R.layout.dialog_folder_sort_remove_confirm_tv
            else R.layout.dialog_folder_sort_remove_confirm,
            null
        )

        val txtMessage = dialogView.findViewById<TextView>(R.id.txtMessage)
        val btnResetConfirm = dialogView.findViewById<View>(R.id.btnResetConfirm)
        val btnCancel = dialogView.findViewById<View>(R.id.btnCancel)

        txtMessage.text = getString(R.string.folder_sort_manager_remove_confirm_message, entry.displayPath)

        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        btnResetConfirm.setOnClickListener {
            dialog.dismiss()
            deleteEntry(entry)
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()

        if (isTv) {
            btnCancel.requestFocus()
        }
    }

    private fun showClearAllDialog() {
        val dialogView = LayoutInflater.from(this).inflate(
            if (isTv) R.layout.dialog_folder_sort_clear_all_confirm_tv
            else R.layout.dialog_folder_sort_clear_all_confirm,
            null
        )

        val btnClearConfirm = dialogView.findViewById<View>(R.id.btnClearConfirm)
        val btnCancel = dialogView.findViewById<View>(R.id.btnCancel)

        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        btnClearConfirm.setOnClickListener {
            dialog.dismiss()
            clearAll()
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()

        if (isTv) {
            btnCancel.requestFocus()
        }
    }
}
