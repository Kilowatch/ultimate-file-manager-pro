package za.kilowatch.ultimatefilemanager.storage

import za.kilowatch.ultimatefilemanager.util.safeDirectoryPath

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.storage.StorageManager
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.*
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import java.io.File
import za.kilowatch.ultimatefilemanager.settings.FontSizeHelper
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper

/**
 * Displays all files matching a specific category (Images, Videos, etc.)
 * from the Storage Analyzer. Supports opening files on click.
 */
class CategoryFilesActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CATEGORY_NAME = "category_name"
        const val EXTRA_FILTER_TYPE = "filter_type"
        const val EXTRA_DRIVE_PATH = "drive_path"
    }

    private lateinit var progressBar: ProgressBar
    private lateinit var recyclerFiles: RecyclerView
    private lateinit var layoutEmpty: LinearLayout
    private lateinit var fileAdapter: FileAdapter

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val isTv = DeviceUtils.isTvDevice(this)
        if (isTv) {
            setContentView(R.layout.activity_category_files_tv)
        } else {
            setContentView(R.layout.activity_category_files)
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val tvPad = if (DeviceUtils.isTvDevice(this)) (27 * resources.displayMetrics.density).toInt() else 0
            v.setPadding(
                systemBars.left + tvPad, systemBars.top + tvPad,
                systemBars.right + tvPad, systemBars.bottom + tvPad
            )
            insets
        }

        progressBar = findViewById(R.id.progressScanning)
        recyclerFiles = findViewById(R.id.recyclerFiles)
        layoutEmpty = findViewById(R.id.layoutEmpty)

        val categoryName = intent.getStringExtra(EXTRA_CATEGORY_NAME) ?: "Files"
        val filterOrdinal = intent.getIntExtra(EXTRA_FILTER_TYPE, 0)
        val filterType = SortFilterSheet.FilterType.entries.getOrElse(filterOrdinal) { SortFilterSheet.FilterType.ALL }
        val drivePath = intent.getStringExtra(EXTRA_DRIVE_PATH)

        val categoryTitle = getString(R.string.category_files_title, categoryName)
        if (isTv) {
            // Wire up TV custom header
            findViewById<TextView?>(R.id.tvHeaderTitle)?.text = categoryTitle
            val btnBack = findViewById<ImageView?>(R.id.btnBack)
            val whiteCsl = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_text_primary))
            val blackCsl = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_button_focused_yellow_text))
            btnBack?.imageTintList = whiteCsl
            btnBack?.setOnClickListener { finish() }
            btnBack?.setOnFocusChangeListener { _, hasFocus ->
                btnBack.imageTintList = if (hasFocus) blackCsl else whiteCsl
            }
        } else {
            // Wire up mobile custom glass header - tint is set via app:tint in XML
            findViewById<TextView?>(R.id.txtTitle)?.text = categoryTitle
            findViewById<ImageView?>(R.id.btnBack)?.setOnClickListener { finish() }
        }

        fileAdapter = FileAdapter(
            isTv = isTv,
            onItemClick = { file, _ ->
                if (file.isDirectory) {
                    val intent = Intent(this, FileBrowserActivity::class.java).apply {
                        putExtra(FileBrowserActivity.EXTRA_MOUNT_PATH, file.absolutePath)
                        putExtra(FileBrowserActivity.EXTRA_STORAGE_LABEL, file.name)
                    }
                    startActivity(intent)
                } else {
                    openFile(file)
                }
            },
            onSelectionChanged = { /* no selection here */ }
        )

        recyclerFiles.layoutManager = LinearLayoutManager(this)
        recyclerFiles.adapter = fileAdapter

        loadFiles(filterType, drivePath)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun loadFiles(filterType: SortFilterSheet.FilterType, drivePath: String?) {
        progressBar.visibility = View.VISIBLE

        scope.launch {
            val files = withContext(Dispatchers.IO) {
                val roots = if (drivePath != null) listOf(File(drivePath)) else getStorageRoots()
                val found = mutableListOf<File>()

                for (root in roots) {
                    try {
                        root.walkTopDown()
                            .filter { it.isFile && SortFilterSheet.matchesFilter(it, filterType) }
                            .forEach { found.add(it) }
                    } catch (_: Exception) { }
                }

                found.sortedByDescending { it.lastModified() }
            }

            progressBar.visibility = View.GONE

            if (files.isEmpty()) {
                layoutEmpty.visibility = View.VISIBLE
                recyclerFiles.visibility = View.GONE
            } else {
                layoutEmpty.visibility = View.GONE
                recyclerFiles.visibility = View.VISIBLE
                val subtitle = "${files.size} files"
                if (DeviceUtils.isTvDevice(this@CategoryFilesActivity)) {
                    val subView = findViewById<TextView?>(R.id.tvHeaderSubtitle)
                    subView?.text = subtitle
                    subView?.visibility = View.VISIBLE
                }
                // Mobile: no explicit subtitle widget (title row is enough)
                fileAdapter.submitList(files)
            }
        }
    }

    private fun getStorageRoots(): List<File> {
        val roots = mutableListOf<File>()
        val sm = getSystemService(Context.STORAGE_SERVICE) as StorageManager
        for (vol in sm.storageVolumes) {
            try {
                val dirPath = vol.safeDirectoryPath
                val dir = dirPath?.let { File(it) }
                if (dir != null && dir.exists() && dir.canRead()) {
                    roots.add(dir)
                }
            } catch (_: Exception) { }
        }
        if (roots.isEmpty()) {
            roots.add(File("/storage/emulated/0"))
        }
        return roots
    }

    private fun openFile(file: File) {
        // Try built-in viewer first
        if (za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.openFile(this, file)) return

        // Fall back to external app
        try {
            val extension = file.extension.lowercase()
            val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"
            val uri: Uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
            } else {
                showSnackbar(getString(R.string.no_app_found_to_open_this_file_type))
            }
        } catch (e: Exception) {
            showSnackbar(getString(R.string.unable_to_open_file_emessage))
        }
    }

    private fun showSnackbar(message: String) {
        Snackbar.make(findViewById(R.id.main), message, Snackbar.LENGTH_SHORT)
            .setBackgroundTint(getColor(R.color.ufm_surface_variant))
            .setTextColor(getColor(R.color.ufm_text_primary))
            .show()
    }
}
