package za.kilowatch.ultimatefilemanager.storage

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.UfmApplication
import za.kilowatch.ultimatefilemanager.indexing.FileIndex
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import java.io.File

/**
 * Scoped Duplicate Finder Activity for mobile and TV devices.
 * Scans a target folder and all subfolders for duplicate files.
 */
class FolderDuplicateFinderActivity : AppCompatActivity() {

    private val viewModel: FolderDuplicateFinderViewModel by viewModels()

    private var folderPath: String = ""
    private var storageId: String = ""

    private var isTv: Boolean = false
    private var hasDeletedFiles: Boolean = false

    private lateinit var txtTitle: TextView
    private lateinit var txtSubtitle: TextView
    private lateinit var txtScopeDescription: TextView
    private lateinit var btnBack: View
    private lateinit var recyclerDuplicates: RecyclerView
    private lateinit var layoutEmptyState: View
    private lateinit var progressBarLoading: ProgressBar
    private lateinit var layoutVerifying: View

    private var btnScopeThisFolder: TextView? = null
    private var btnScopeAcrossStorage: TextView? = null
    private var btnScopeThisFolderTv: Button? = null
    private var btnScopeAcrossStorageTv: Button? = null

    private var fabDeleteDuplicates: ExtendedFloatingActionButton? = null
    private var btnDeleteDuplicatesTv: Button? = null

    private var duplicateAdapter: AnalyzerDuplicateAdapter? = null
    private var currentDuplicateGroups: List<DuplicateGroup> = emptyList()

    companion object {
        const val EXTRA_FOLDER_PATH = "extra_folder_path"
        const val EXTRA_STORAGE_ID  = "extra_storage_id"
        const val EXTRA_FILES_DELETED = "extra_files_deleted"
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        isTv = DeviceUtils.isTvDevice(this)
        setContentView(if (isTv) R.layout.activity_folder_duplicate_finder_tv else R.layout.activity_folder_duplicate_finder)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val tvPad = if (isTv) (27 * resources.displayMetrics.density).toInt() else 0
            v.setPadding(
                systemBars.left + tvPad,
                systemBars.top + tvPad,
                systemBars.right + tvPad,
                systemBars.bottom + tvPad
            )
            insets
        }

        folderPath = intent.getStringExtra(EXTRA_FOLDER_PATH) ?: ""
        storageId  = intent.getStringExtra(EXTRA_STORAGE_ID)  ?: ""

        bindViews()
        setupListeners()
        observeViewModel()

        if (folderPath.isNotEmpty()) {
            txtSubtitle.text = folderPath
            viewModel.scanFolder(storageId, folderPath)
        } else {
            finish()
        }
    }

    private fun bindViews() {
        btnBack = findViewById(R.id.btnBack)
        txtTitle = findViewById(R.id.txtTitle)
        txtSubtitle = findViewById(R.id.txtSubtitle)
        txtScopeDescription = findViewById(R.id.txtScopeDescription)
        recyclerDuplicates = findViewById(R.id.recyclerDuplicates)
        layoutEmptyState = findViewById(R.id.layoutEmptyState)
        progressBarLoading = findViewById(R.id.progressBarLoading)
        layoutVerifying = findViewById(R.id.layoutVerifying)

        if (isTv) {
            btnDeleteDuplicatesTv = findViewById(R.id.btnDeleteDuplicates)
            btnScopeThisFolderTv = findViewById(R.id.btnScopeThisFolderTv)
            btnScopeAcrossStorageTv = findViewById(R.id.btnScopeAcrossStorageTv)
        } else {
            fabDeleteDuplicates = findViewById(R.id.fabDeleteDuplicates)
            btnScopeThisFolder = findViewById(R.id.btnScopeThisFolder)
            btnScopeAcrossStorage = findViewById(R.id.btnScopeAcrossStorage)
        }

        recyclerDuplicates.layoutManager = LinearLayoutManager(this)
    }


    private fun setupListeners() {
        btnBack.setOnClickListener {
            handleExit()
        }

        fabDeleteDuplicates?.setOnClickListener {
            onDeleteClicked()
        }

        btnDeleteDuplicatesTv?.setOnClickListener {
            onDeleteClicked()
        }

        btnScopeThisFolder?.setOnClickListener {
            viewModel.setScope(StorageAnalyzerEngine.DuplicateScope.THIS_FOLDER_ONLY)
        }

        btnScopeAcrossStorage?.setOnClickListener {
            viewModel.setScope(StorageAnalyzerEngine.DuplicateScope.ACROSS_STORAGE)
        }

        btnScopeThisFolderTv?.setOnClickListener {
            viewModel.setScope(StorageAnalyzerEngine.DuplicateScope.THIS_FOLDER_ONLY)
        }

        btnScopeAcrossStorageTv?.setOnClickListener {
            viewModel.setScope(StorageAnalyzerEngine.DuplicateScope.ACROSS_STORAGE)
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.isLoading.collectLatest { loading ->
                progressBarLoading.visibility = if (loading) View.VISIBLE else View.GONE
            }
        }

        lifecycleScope.launch {
            viewModel.isScanRunning.collectLatest { running ->
                layoutVerifying.visibility = if (running) View.VISIBLE else View.GONE
            }
        }

        lifecycleScope.launch {
            viewModel.scope.collectLatest { scope ->
                updateScopeUI(scope)
            }
        }

        lifecycleScope.launch {
            viewModel.duplicateGroups.collectLatest { groups ->
                if (groups == null) return@collectLatest
                currentDuplicateGroups = groups
                updateDuplicateList(groups)
            }
        }
    }

    private fun updateScopeUI(scope: StorageAnalyzerEngine.DuplicateScope) {
        val isThisFolder = scope == StorageAnalyzerEngine.DuplicateScope.THIS_FOLDER_ONLY
        txtScopeDescription.setText(if (isThisFolder) R.string.duplicate_scope_desc_this_folder else R.string.duplicate_scope_desc_across_storage)

        if (isTv) {
            btnScopeThisFolderTv?.setBackgroundResource(if (isThisFolder) R.drawable.selector_tv_button_yellow else R.drawable.selector_tv_button)
            btnScopeAcrossStorageTv?.setBackgroundResource(if (!isThisFolder) R.drawable.selector_tv_button_yellow else R.drawable.selector_tv_button)
        } else {
            if (isThisFolder) {
                btnScopeThisFolder?.setBackgroundResource(R.drawable.bg_view_toggle_item_active)
                btnScopeThisFolder?.setTextColor(ContextCompat.getColor(this, R.color.white))
                btnScopeThisFolder?.setTypeface(null, Typeface.BOLD)

                btnScopeAcrossStorage?.setBackgroundResource(android.R.color.transparent)
                btnScopeAcrossStorage?.setTextColor(ContextCompat.getColor(this, R.color.mobile_text_secondary))
                btnScopeAcrossStorage?.setTypeface(null, Typeface.NORMAL)
            } else {
                btnScopeAcrossStorage?.setBackgroundResource(R.drawable.bg_view_toggle_item_active)
                btnScopeAcrossStorage?.setTextColor(ContextCompat.getColor(this, R.color.white))
                btnScopeAcrossStorage?.setTypeface(null, Typeface.BOLD)

                btnScopeThisFolder?.setBackgroundResource(android.R.color.transparent)
                btnScopeThisFolder?.setTextColor(ContextCompat.getColor(this, R.color.mobile_text_secondary))
                btnScopeThisFolder?.setTypeface(null, Typeface.NORMAL)
            }
        }
    }

    private fun updateDuplicateList(groups: List<DuplicateGroup>) {
        if (groups.isEmpty()) {
            layoutEmptyState.visibility = View.VISIBLE
            recyclerDuplicates.visibility = View.GONE
            updateDeleteButtonVisibility(0)
            return
        }

        layoutEmptyState.visibility = View.GONE
        recyclerDuplicates.visibility = View.VISIBLE

        val adapter = AnalyzerDuplicateAdapter(groups, isTv, folderPath) { count ->
            updateDeleteButtonVisibility(count)
        }
        duplicateAdapter = adapter
        recyclerDuplicates.adapter = adapter
    }

    private fun updateDeleteButtonVisibility(checkedCount: Int) {
        val show = checkedCount > 0
        fabDeleteDuplicates?.visibility = if (show) View.VISIBLE else View.GONE
        btnDeleteDuplicatesTv?.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun onDeleteClicked() {
        val adapter = duplicateAdapter ?: return
        val toDelete = adapter.checkedFiles.toList()
        if (toDelete.isEmpty()) return

        // Safety check: check if any group is being entirely deleted
        val dangerousFiles = mutableListOf<String>()
        currentDuplicateGroups.forEach { group ->
            val groupFiles = group.files
            val selectedInGroup = groupFiles.filter { gf -> adapter.checkedFiles.any { it.path == gf.path } }
            if (selectedInGroup.size == groupFiles.size && groupFiles.isNotEmpty()) {
                dangerousFiles.add(groupFiles.first().filename)
            }
        }

        val onConfirm = {
            deleteDuplicates(toDelete.map { it.path }.toSet(), adapter)
        }

        if (dangerousFiles.isNotEmpty()) {
            DuplicateSafetySheet.newInstance(dangerousFiles, onConfirm)
                .show(supportFragmentManager, DuplicateSafetySheet.TAG)
        } else {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.analyzer_delete_confirm_title, toDelete.size))
                .setMessage(R.string.analyzer_delete_confirm_msg)
                .setPositiveButton(R.string.delete) { _, _ -> onConfirm() }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun deleteDuplicates(paths: Set<String>, adapter: AnalyzerDuplicateAdapter) {
        lifecycleScope.launch {
            val deleted = mutableSetOf<String>()
            var failedCount = 0
            var hasProtectedFailed = false

            val repo = UfmApplication.indexingRepository

            for (path in paths) {
                val f = File(path)
                val success = if (ShizukuShellWrapper.canUseShizukuForPath(path)) {
                    ShizukuShellWrapper.delete(path)
                } else {
                    f.exists() && f.delete()
                }

                if (success) {
                    repo.deleteFromIndex(path)
                    deleted.add(path)
                } else {
                    failedCount++
                    if (ShizukuShellWrapper.isProtectedPath(path)) {
                        hasProtectedFailed = true
                    }
                }
            }

            if (deleted.isNotEmpty()) {
                hasDeletedFiles = true
                adapter.removeFiles(deleted)
                // Re-evaluate remaining duplicate groups
                val remainingGroups = currentDuplicateGroups.map { g ->
                    g.copy(files = g.files.filter { it.path !in deleted })
                }.filter { it.files.size > 1 }

                currentDuplicateGroups = remainingGroups
                if (remainingGroups.isEmpty()) {
                    layoutEmptyState.visibility = View.VISIBLE
                    recyclerDuplicates.visibility = View.GONE
                }
            }

            updateDeleteButtonVisibility(adapter.checkedFiles.size)

            if (failedCount > 0) {
                val shizukuAuthorized = ShizukuShellWrapper.isAuthorized()
                val msg = if (hasProtectedFailed && !shizukuAuthorized) {
                    getString(R.string.delete_error_shizuku_required)
                } else {
                    getString(R.string.delete_error)
                }
                Toast.makeText(this@FolderDuplicateFinderActivity, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun handleExit() {
        if (hasDeletedFiles) {
            val resultIntent = Intent().apply {
                putExtra(EXTRA_FILES_DELETED, true)
                putExtra(EXTRA_FOLDER_PATH, folderPath)
            }
            setResult(RESULT_OK, resultIntent)
        }
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        handleExit()
        super.onBackPressed()
    }
}
