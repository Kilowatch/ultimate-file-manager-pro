package za.kilowatch.ultimatefilemanager.viewer

import android.content.Intent
import android.os.Bundle
import android.text.format.Formatter
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import za.kilowatch.ultimatefilemanager.archive.ArchiveItemOptionsDialog
import za.kilowatch.ultimatefilemanager.archive.ArchiveManager
import za.kilowatch.ultimatefilemanager.archive.ArchivePreviewCache
import za.kilowatch.ultimatefilemanager.archive.ExtractLocationDialog
import za.kilowatch.ultimatefilemanager.archive.PasswordPromptDialog
import za.kilowatch.ultimatefilemanager.settings.FontSizeHelper
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import za.kilowatch.ultimatefilemanager.storage.FileBrowserActivity
import za.kilowatch.ultimatefilemanager.storage.StorageBrowserActivity
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import za.kilowatch.ultimatefilemanager.util.NaturalSort
import za.kilowatch.ultimatefilemanager.util.FileTypeIconProvider
import java.io.File
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.FileHeader

/**
 * Built-in ZIP browser. Lists entries, supports navigating into subdirectories,
 * extracting individual files, moving entries out of archive, deleting entries, or extracting the entire archive.
 */
class ZipViewerActivity : AppCompatActivity() {

    private enum class ExtractOpMode { EXTRACT_ALL, COPY_SINGLE, MOVE_OUT_SINGLE }

    private lateinit var recyclerEntries: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var txtTitle: TextView
    private lateinit var txtBreadcrumb: TextView
    private lateinit var btnExtractAll: MaterialButton
    private lateinit var layoutEmpty: View

    private var zipFile: ZipFile? = null
    private var sourceFile: File? = null
    private var currentPath = "" // Current directory within the ZIP
    private var allEntries = listOf<FileHeader>()
    private var archivePassword: String? = null
    private val extractedFiles = mutableMapOf<String, File>()
    private var focusedItem: ZipItem? = null

    private var pendingExtractHeader: FileHeader? = null
    private var pendingTargetItem: ZipItem? = null
    private var pendingExtractAll: Boolean = false
    private var pendingOpMode: ExtractOpMode = ExtractOpMode.EXTRACT_ALL

    /** Receives destination folder chosen via StorageBrowserActivity / FileBrowserActivity */
    private val extractDestLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val localPath = result.data
                ?.getStringExtra(FileBrowserActivity.RESULT_SELECTED_LOCAL_PATH)
                ?: return@registerForActivityResult
            val destDir = File(localPath)
            if (pendingExtractAll || pendingOpMode == ExtractOpMode.EXTRACT_ALL) {
                doExtractAll(destDir)
            } else if (pendingOpMode == ExtractOpMode.MOVE_OUT_SINGLE) {
                pendingTargetItem?.let { doMoveOutSingleItem(it, destDir) }
            } else {
                pendingExtractHeader?.let { doExtractSingleFile(it, destDir) }
                    ?: pendingTargetItem?.let { doExtractSingleItem(it, destDir) }
            }
        }
        pendingExtractHeader = null
        pendingTargetItem = null
        pendingExtractAll = false
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val isTv = DeviceUtils.isTvDevice(this)
        setContentView(
            if (isTv) R.layout.activity_zip_viewer_tv
            else R.layout.activity_zip_viewer
        )

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(sb.left, sb.top, sb.right, sb.bottom)
            insets
        }

        recyclerEntries = findViewById(R.id.recyclerEntries)
        progressBar = findViewById(R.id.progressBar)
        txtTitle = findViewById(R.id.txtTitle)
        txtBreadcrumb = findViewById(R.id.txtBreadcrumb)
        btnExtractAll = findViewById(R.id.btnExtractAll)
        layoutEmpty = findViewById(R.id.layoutEmpty)

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { navigateBack() }

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                navigateBack()
            }
        })

        val filePath = intent.getStringExtra(FileViewerRouter.EXTRA_FILE_PATH) ?: run {
            finish(); return
        }
        val fileName = intent.getStringExtra(FileViewerRouter.EXTRA_FILE_NAME) ?: getString(R.string.archive_1)
        txtTitle.text = fileName
        sourceFile = File(filePath)

        btnExtractAll.setOnClickListener { extractAll() }

        recyclerEntries.layoutManager = LinearLayoutManager(this)
        loadZip(File(filePath))

        // TV D-pad scroll support
        if (isTv) {
            // Yellow/black focus for Extract All button
            val yellowColor = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_button_focused_yellow))
            val defaultBtnBg = btnExtractAll.backgroundTintList
            val defaultBtnText = btnExtractAll.textColors
            btnExtractAll.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    btnExtractAll.backgroundTintList = yellowColor
                    btnExtractAll.setTextColor(getColor(R.color.tv_button_focused_yellow_text))
                } else {
                    btnExtractAll.backgroundTintList = defaultBtnBg
                    btnExtractAll.setTextColor(defaultBtnText)
                }
            }

            // "Options" action for the focused entry (TV has no long-press).
            val btnOptions = findViewById<MaterialButton>(R.id.btnOptions)
            btnOptions.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    btnOptions.backgroundTintList = yellowColor
                    btnOptions.setTextColor(getColor(R.color.tv_button_focused_yellow_text))
                } else {
                    btnOptions.backgroundTintList = defaultBtnBg
                    btnOptions.setTextColor(defaultBtnText)
                }
            }
            btnOptions.setOnClickListener { focusedItem?.let { showItemOptions(it) } }

            val scrollContainer = findViewById<View>(R.id.scrollContainer)
            scrollContainer?.setOnKeyListener { _, keyCode, event ->
                if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                    val scrollAmount = (80 * resources.displayMetrics.density).toInt()
                    when (keyCode) {
                        android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                            recyclerEntries.smoothScrollBy(0, scrollAmount); true
                        }
                        android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                            if (!recyclerEntries.canScrollVertically(-1)) false // Let focus escape
                            else { recyclerEntries.smoothScrollBy(0, -scrollAmount); true }
                        }
                        else -> false
                    }
                } else false
            }
            scrollContainer?.requestFocus()
        }
    }

    private fun navigateBack() {
        if (currentPath.isNotEmpty()) {
            // Go up one directory — Zip4j uses forward slashes
            val parent = if (currentPath.contains("/")) {
                currentPath.substringBeforeLast("/")
            } else {
                ""
            }
            currentPath = parent
            displayEntries()
        } else {
            finish()
        }
    }

    private fun loadZip(file: File) {
        progressBar.visibility = View.VISIBLE

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val zf = ZipFile(file)
                val isEncrypted = zf.isEncrypted || zf.fileHeaders.any { it.isEncrypted }
                
                if (isEncrypted && archivePassword == null) {
                    withContext(Dispatchers.Main) {
                        progressBar.visibility = View.GONE
                        showPasswordPrompt(zf)
                    }
                    return@launch
                }

                zipFile = zf
                allEntries = zf.fileHeaders

                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    displayEntries()
                }
            } catch (e: OutOfMemoryError) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    showSnackbar(getString(R.string.error_opening_archive_emessage, getString(R.string.error_not_enough_memory)))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    showSnackbar(getString(R.string.error_opening_archive_emessage, e.message ?: "Unknown error"))
                }
            }
        }
    }

    private fun showPasswordPrompt(zf: ZipFile) {
        val dialog = PasswordPromptDialog()
        dialog.setOnConfirm { password ->
            archivePassword = password
            zf.setPassword(password.toCharArray())
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    // 1. Fetch headers
                    val headers = zf.fileHeaders
                    
                    // 2. Perform test read if any entry is encrypted
                    val firstEncrypted = headers.firstOrNull { it.isEncrypted && !it.isDirectory }
                    if (firstEncrypted != null) {
                        zf.getInputStream(firstEncrypted).use { it.read() }
                    }

                    zipFile = zf
                    allEntries = headers
                    withContext(Dispatchers.Main) {
                        displayEntries()
                    }
                } catch (e: OutOfMemoryError) {
                    withContext(Dispatchers.Main) {
                        showSnackbar(getString(R.string.error_opening_archive_emessage, getString(R.string.error_not_enough_memory)))
                        archivePassword = null
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        showSnackbar(getString(R.string.invalid_password))
                        archivePassword = null
                        loadZip(sourceFile!!)
                    }
                }
            }
        }
        dialog.setOnCancel {
            finish()
        }
        dialog.show(supportFragmentManager, PasswordPromptDialog.TAG)
    }

    private fun displayEntries() {
        // Update breadcrumb
        txtBreadcrumb.text = if (currentPath.isEmpty()) "/" else "/$currentPath/"
        txtBreadcrumb.visibility = View.VISIBLE

        // Get entries at current directory level
        val prefix = if (currentPath.isEmpty()) "" else "$currentPath/"
        val items = mutableListOf<ZipItem>()
        val seenDirs = mutableSetOf<String>()

        for (entry in allEntries) {
            val name = entry.fileName
            if (!name.startsWith(prefix)) continue
            val relativeName = name.removePrefix(prefix)
            if (relativeName.isEmpty()) continue

            if (relativeName.contains("/")) {
                // This is a subdirectory entry
                val dirName = relativeName.substringBefore("/")
                if (dirName.isNotEmpty() && dirName !in seenDirs) {
                    seenDirs.add(dirName)
                    items.add(ZipItem(dirName, isDirectory = true, entry = null))
                }
            } else {
                // This is a file at the current level
                if (!entry.isDirectory) {
                    items.add(ZipItem(relativeName, isDirectory = false, entry = entry))
                }
            }
        }

        // Sort: directories first, then alphabetically
        items.sortWith(compareBy<ZipItem> { !it.isDirectory }.thenBy(NaturalSort.order) { it.name })

        if (items.isEmpty()) {
            layoutEmpty.visibility = View.VISIBLE
            recyclerEntries.visibility = View.GONE
        } else {
            layoutEmpty.visibility = View.GONE
            recyclerEntries.visibility = View.VISIBLE
            recyclerEntries.adapter = ZipAdapter(items)
        }
    }

    /** Shows the extract-location dialog, then navigates to the storage/folder picker. */
    private fun extractAll() {
        if (zipFile == null) return
        val dialog = ExtractLocationDialog()
        dialog.setOnSetLocation {
            pendingExtractAll = true
            pendingOpMode = ExtractOpMode.EXTRACT_ALL
            pendingExtractHeader = null
            pendingTargetItem = null
            val intent = Intent(this, StorageBrowserActivity::class.java).apply {
                putExtra(StorageBrowserActivity.EXTRA_EXTRACT_DEST_PICKER, true)
            }
            extractDestLauncher.launch(intent)
        }
        dialog.show(supportFragmentManager, ExtractLocationDialog.TAG)
    }

    private fun showItemOptions(item: ZipItem) {
        val dialog = ArchiveItemOptionsDialog()
        dialog.setItemName(item.name)
        dialog.setOnCopyOut {
            pendingExtractAll = false
            pendingExtractHeader = item.entry
            pendingTargetItem = item
            pendingOpMode = ExtractOpMode.COPY_SINGLE
            launchDestPicker()
        }
        dialog.setOnMoveOut {
            pendingExtractAll = false
            pendingExtractHeader = item.entry
            pendingTargetItem = item
            pendingOpMode = ExtractOpMode.MOVE_OUT_SINGLE
            launchDestPicker()
        }
        dialog.setOnDelete {
            confirmDeleteZipItem(item)
        }
        dialog.show(supportFragmentManager, ArchiveItemOptionsDialog.TAG)
    }

    /** Extracts a single entry to the session cache and opens it in the built-in viewer. */
    private fun previewItem(item: ZipItem) {
        val source = sourceFile ?: return
        val entryPath = item.entry?.fileName ?: return
        val entryName = entryPath.substringAfterLast("/")

        // Reuse an already-extracted copy from this session when available.
        extractedFiles[entryPath]?.let { cached ->
            if (cached.exists() && cached.isFile) {
                FileViewerRouter.openFile(this, cached)
                return
            }
        }

        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val session = ArchivePreviewCache.sessionDir(this@ZipViewerActivity)
                val outDir = File(session, "entry_${extractedFiles.size}")
                val res = ArchiveManager.extractArchiveEntry(source, entryPath, outDir, archivePassword)
                if (res.isFailure) {
                    outDir.deleteRecursively()
                    withContext(Dispatchers.Main) {
                        progressBar.visibility = View.GONE
                        showSnackbar(getString(R.string.archive_operation_failed, res.exceptionOrNull()?.message ?: "Unknown error"))
                    }
                    return@launch
                }
                val tempFile = outDir.listFiles()?.firstOrNull { it.isFile } ?: File(outDir, entryName)
                if (!tempFile.exists()) {
                    withContext(Dispatchers.Main) {
                        progressBar.visibility = View.GONE
                        showSnackbar(getString(R.string.archive_operation_failed, "Unknown error"))
                    }
                    return@launch
                }
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    extractedFiles[entryPath] = tempFile
                    FileViewerRouter.openFile(this@ZipViewerActivity, tempFile)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    showSnackbar(getString(R.string.archive_operation_failed, e.message ?: "Unknown error"))
                }
            }
        }
    }

    private fun launchDestPicker() {
        val dialog = ExtractLocationDialog()
        dialog.setOnSetLocation {
            val intent = Intent(this, StorageBrowserActivity::class.java).apply {
                putExtra(StorageBrowserActivity.EXTRA_EXTRACT_DEST_PICKER, true)
            }
            extractDestLauncher.launch(intent)
        }
        dialog.show(supportFragmentManager, ExtractLocationDialog.TAG)
    }

    /** Performs single-item extraction once the user has chosen a destination. */
    private fun doExtractSingleItem(item: ZipItem, destDir: File) {
        val file = sourceFile ?: return
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val entryPath = item.entry?.fileName ?: (if (currentPath.isEmpty()) item.name else "$currentPath/${item.name}")
                val res = ArchiveManager.extractZipEntry(file, entryPath, destDir, archivePassword)
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    if (res.isSuccess) {
                        showSnackbar(getString(R.string.archive_extract_success, destDir.absolutePath))
                    } else {
                        showSnackbar(getString(R.string.archive_operation_failed, res.exceptionOrNull()?.message ?: "Unknown error"))
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    showSnackbar(getString(R.string.archive_operation_failed, e.message ?: "Unknown error"))
                }
            }
        }
    }

    /** Performs moving a single item out of the archive once the user has chosen a destination. */
    private fun doMoveOutSingleItem(item: ZipItem, destDir: File) {
        val file = sourceFile ?: return
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val entryPath = item.entry?.fileName ?: (if (currentPath.isEmpty()) item.name else "$currentPath/${item.name}")
                val res = ArchiveManager.moveZipEntry(file, entryPath, destDir, archivePassword)
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    if (res.isSuccess) {
                        showSnackbar(getString(R.string.archive_move_success, item.name, destDir.absolutePath))
                        loadZip(file)
                    } else {
                        showSnackbar(getString(R.string.archive_operation_failed, res.exceptionOrNull()?.message ?: "Unknown error"))
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    showSnackbar(getString(R.string.archive_operation_failed, e.message ?: "Unknown error"))
                }
            }
        }
    }

    private fun confirmDeleteZipItem(item: ZipItem) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.confirm_delete_archive_entry_title)
            .setMessage(getString(R.string.confirm_delete_archive_entry_msg, item.name))
            .setPositiveButton(R.string.action_delete) { _, _ ->
                doDeleteZipItem(item)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun doDeleteZipItem(item: ZipItem) {
        val file = sourceFile ?: return
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val entryPath = item.entry?.fileName ?: (if (currentPath.isEmpty()) item.name else "$currentPath/${item.name}")
                val res = ArchiveManager.deleteZipEntry(file, entryPath, archivePassword)
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    if (res.isSuccess) {
                        showSnackbar(getString(R.string.archive_delete_success, item.name))
                        loadZip(file)
                    } else {
                        showSnackbar(getString(R.string.archive_operation_failed, res.exceptionOrNull()?.message ?: "Unknown error"))
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    showSnackbar(getString(R.string.archive_operation_failed, e.message ?: "Unknown error"))
                }
            }
        }
    }

    /** Performs the actual "Extract All" once the user has chosen a destination. */
    private fun doExtractAll(destDir: File) {
        val zf = zipFile ?: return
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                destDir.mkdirs()
                val canonicalDest = destDir.canonicalPath
                for (header in zf.fileHeaders) {
                    val outFile = File(destDir, header.fileName)
                    val canonicalOut = outFile.canonicalPath
                    
                    if (!canonicalOut.startsWith(canonicalDest + File.separator)) {
                        Log.w("ZipViewer", "Zip Slip attempt detected! Skipping entry: ${header.fileName}")
                        continue
                    }
                    
                    zf.extractFile(header, destDir.absolutePath)
                }
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    showSnackbar(getString(R.string.archive_extract_success, destDir.absolutePath))
                }
            } catch (e: OutOfMemoryError) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    showSnackbar("${getString(R.string.archive_extract_error)}: ${getString(R.string.error_not_enough_memory)}")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    showSnackbar("${getString(R.string.archive_extract_error)}: ${e.message}")
                }
            }
        }
    }

    /** Performs single-file extraction once the user has chosen a destination. */
    private fun doExtractSingleFile(header: FileHeader, destDir: File) {
        val zf = zipFile ?: return
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (zf.isEncrypted && archivePassword != null) {
                    zf.setPassword(archivePassword?.toCharArray())
                }
                destDir.mkdirs()
                val outFile = File(destDir, header.fileName.substringAfterLast("/"))
                zf.getInputStream(header).use { input ->
                    outFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    showSnackbar(getString(R.string.archive_extract_success, outFile.absolutePath))
                }
            } catch (e: OutOfMemoryError) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    showSnackbar("${getString(R.string.archive_extract_error)}: ${getString(R.string.error_not_enough_memory)}")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    showSnackbar("${getString(R.string.archive_extract_error)}: ${e.message}")
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { zipFile?.close() } catch (_: Exception) { }
        ArchivePreviewCache.purgeSession()
    }

    private fun showSnackbar(message: String) {
        val root = findViewById<View>(R.id.main)
        Snackbar.make(root, message, Snackbar.LENGTH_SHORT)
            .setBackgroundTint(getColor(R.color.ufm_surface_variant))
            .setTextColor(getColor(R.color.ufm_text_primary))
            .show()
    }

    // ── Data models ──────────────────────────────────────────────────────────

    data class ZipItem(
        val name: String,
        val isDirectory: Boolean,
        val entry: FileHeader?
    )

    // ── Adapter ──────────────────────────────────────────────────────────────

    inner class ZipAdapter(private val items: List<ZipItem>) :
        RecyclerView.Adapter<ZipAdapter.VH>() {

        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val icon: ImageView = itemView.findViewById(R.id.imgIcon)
            val txtName: TextView = itemView.findViewById(R.id.txtName)
            val txtInfo: TextView = itemView.findViewById(R.id.txtInfo)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_zip_entry, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            val context = holder.itemView.context
            
            holder.txtName.text = item.name

            if (item.isDirectory) {
                holder.icon.setImageResource(R.drawable.ic_folder)
                holder.txtInfo.setText(R.string.folder)
                holder.itemView.setOnClickListener {
                    currentPath = if (currentPath.isEmpty()) item.name else "$currentPath/${item.name}"
                    displayEntries()
                }
                holder.itemView.setOnLongClickListener {
                    showItemOptions(item)
                    true
                }
            } else {
                holder.icon.setImageResource(
                    FileTypeIconProvider.iconForExtension(holder.itemView.context, item.name.substringAfterLast('.', ""))
                )
                val entry = item.entry!!
                val size = Formatter.formatFileSize(context, entry.uncompressedSize)
                val compressed = Formatter.formatFileSize(context, entry.compressedSize)
                val ratio = if (entry.uncompressedSize > 0) {
                    ((1.0 - entry.compressedSize.toDouble() / entry.uncompressedSize) * 100).toInt()
                } else 0
                holder.txtInfo.text = getString(R.string.size_compressed_ratio_saved, size, compressed, ratio)
                holder.itemView.setOnClickListener {
                    previewItem(item)
                }
                holder.itemView.setOnLongClickListener {
                    showItemOptions(item)
                    true
                }
            }

            // TV focus handling: Text/icon turns black on yellow focus bg
            if (DeviceUtils.isTvDevice(context)) {
                val black = context.getColor(R.color.tv_button_focused_yellow_text)
                val primaryColor = context.getColor(R.color.mobile_text_primary)
                val secondaryColor = context.getColor(R.color.mobile_text_secondary)
                val iconColor = context.getColor(R.color.mobile_icon_tint)
                
                val blackCsl = android.content.res.ColorStateList.valueOf(black)
                val iconCsl = android.content.res.ColorStateList.valueOf(iconColor)

                holder.itemView.setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus) {
                        focusedItem = item
                        holder.txtName.setTextColor(black)
                        holder.txtInfo.setTextColor(black)
                        holder.icon.imageTintList = blackCsl
                    } else {
                        holder.txtName.setTextColor(primaryColor)
                        holder.txtInfo.setTextColor(secondaryColor)
                        holder.icon.imageTintList = iconCsl
                    }
                }
            }
        }

        override fun getItemCount() = items.size
    }
}
