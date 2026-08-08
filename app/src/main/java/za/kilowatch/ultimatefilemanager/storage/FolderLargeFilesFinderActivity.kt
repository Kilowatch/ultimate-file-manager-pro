package za.kilowatch.ultimatefilemanager.storage

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.indexing.FileIndex
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import za.kilowatch.ultimatefilemanager.util.GoRoLog
import java.io.File

/**
 * Scoped Folder Large Files Activity for Mobile and Android TV.
 * Displays files larger than 10MB in the specified folder and its subfolders.
 */
class FolderLargeFilesFinderActivity : AppCompatActivity() {

    private val viewModel: FolderLargeFilesFinderViewModel by viewModels()

    private var folderPath: String = ""
    private var storageId: String = ""
    private var isTv: Boolean = false

    private lateinit var txtTitle: TextView
    private lateinit var txtSubtitle: TextView
    private lateinit var txtScopeDescription: TextView
    private lateinit var btnBack: View
    private lateinit var recyclerLargeFiles: RecyclerView
    private lateinit var layoutEmptyState: View
    private lateinit var progressBarLoading: ProgressBar
    private lateinit var layoutVerifying: View

    private var fabDeleteLargeFiles: ExtendedFloatingActionButton? = null
    private var btnDeleteLargeFilesTv: Button? = null

    private var largeFilesAdapter: FolderLargeFilesAdapter? = null
    private var currentFiles: List<FileIndex> = emptyList()

    companion object {
        const val EXTRA_FOLDER_PATH = "extra_folder_path"
        const val EXTRA_STORAGE_ID = "extra_storage_id"
        const val EXTRA_FILES_DELETED = "extra_files_deleted"
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        isTv = DeviceUtils.isTvDevice(this)
        setContentView(if (isTv) R.layout.activity_folder_large_files_tv else R.layout.activity_folder_large_files)

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
        storageId = intent.getStringExtra(EXTRA_STORAGE_ID) ?: ""

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
        recyclerLargeFiles = findViewById(R.id.recyclerLargeFiles)
        layoutEmptyState = findViewById(R.id.layoutEmptyState)
        progressBarLoading = findViewById(R.id.progressBarLoading)
        layoutVerifying = findViewById(R.id.layoutVerifying)

        if (isTv) {
            btnDeleteLargeFilesTv = findViewById(R.id.btnDeleteLargeFiles)
        } else {
            fabDeleteLargeFiles = findViewById(R.id.fabDeleteLargeFiles)
        }

        recyclerLargeFiles.layoutManager = LinearLayoutManager(this)
    }

    private fun setupListeners() {
        btnBack.setOnClickListener {
            handleExit()
        }

        fabDeleteLargeFiles?.setOnClickListener {
            onDeleteClicked()
        }

        btnDeleteLargeFilesTv?.setOnClickListener {
            onDeleteClicked()
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
            viewModel.largeFiles.collectLatest { files ->
                if (files == null) return@collectLatest
                currentFiles = files
                updateFileList(files)
            }
        }
    }

    private fun updateFileList(files: List<FileIndex>) {
        if (files.isEmpty()) {
            layoutEmptyState.visibility = View.VISIBLE
            recyclerLargeFiles.visibility = View.GONE
            updateDeleteButtonVisibility(0)
            return
        }

        layoutEmptyState.visibility = View.GONE
        recyclerLargeFiles.visibility = View.VISIBLE

        val adapter = FolderLargeFilesAdapter(files, isTv, folderPath) { count ->
            updateDeleteButtonVisibility(count)
        }
        largeFilesAdapter = adapter
        recyclerLargeFiles.adapter = adapter
    }

    private fun updateDeleteButtonVisibility(checkedCount: Int) {
        val show = checkedCount > 0
        if (isTv) {
            btnDeleteLargeFilesTv?.visibility = if (show) View.VISIBLE else View.GONE
            if (show) {
                val text = "${getString(R.string.analyzer_delete_selected)} ($checkedCount)"
                btnDeleteLargeFilesTv?.text = text
            }
        } else {
            if (show) {
                fabDeleteLargeFiles?.text = "${getString(R.string.analyzer_delete_selected)} ($checkedCount)"
                fabDeleteLargeFiles?.show()
            } else {
                fabDeleteLargeFiles?.hide()
            }
        }
    }

    private fun onDeleteClicked() {
        val adapter = largeFilesAdapter ?: return
        val toDelete = adapter.checkedFiles.toList()
        if (toDelete.isEmpty()) return

        val totalBytes = toDelete.sumOf { it.size }
        val title = getString(R.string.analyzer_delete_confirm_title, toDelete.size)
        val msg = getString(R.string.analyzer_delete_confirm_msg)

        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton(R.string.delete) { _, _ ->
                executeDeletion(toDelete)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun executeDeletion(targets: List<FileIndex>) {
        // Run the blocking File.delete() calls (native remove syscall) on the IO
        // dispatcher: on slow or busy storage a single delete can exceed the 5 s
        // ANR watchdog threshold and freeze the main thread (reported from a
        // KTC JVC 2K TV, SDK 34, app 1.8.0-GOOGLE).
        lifecycleScope.launch(Dispatchers.IO) {
            val deletedPaths = mutableSetOf<String>()
            var failedCount = 0

            for (fi in targets) {
                try {
                    val f = File(fi.path)
                    if (f.exists() && f.delete()) {
                        deletedPaths.add(fi.path)
                    } else if (!f.exists()) {
                        deletedPaths.add(fi.path)
                    } else {
                        failedCount++
                    }
                } catch (e: Exception) {
                    GoRoLog.e("FolderLargeFiles", "Failed to delete file ${fi.path}: ${e.message}")
                    failedCount++
                }
            }

            withContext(Dispatchers.Main) {
                if (deletedPaths.isNotEmpty()) {
                    largeFilesAdapter?.removeFiles(deletedPaths)
                    val remaining = currentFiles.filter { it.path !in deletedPaths }
                    currentFiles = remaining
                    updateDeleteButtonVisibility(largeFilesAdapter?.checkedFiles?.size ?: 0)
                    if (remaining.isEmpty()) {
                        layoutEmptyState.visibility = View.VISIBLE
                        recyclerLargeFiles.visibility = View.GONE
                    }

                    val msg = getString(R.string.delete_success, deletedPaths.size)
                    Toast.makeText(this@FolderLargeFilesFinderActivity, msg, Toast.LENGTH_SHORT).show()
                }

                if (failedCount > 0) {
                    val msg = "Could not delete $failedCount files (permission or locked)."
                    Toast.makeText(this@FolderLargeFilesFinderActivity, msg, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun handleExit() {
        setResult(RESULT_OK, Intent().apply {
            putExtra(EXTRA_FILES_DELETED, true)
        })
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        handleExit()
    }
}
