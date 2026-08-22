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
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.commons.compress.MemoryLimitException
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZMethod
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.archive.ArchiveItemOptionsDialog
import za.kilowatch.ultimatefilemanager.archive.ArchiveManager
import za.kilowatch.ultimatefilemanager.archive.ArchivePreviewCache
import za.kilowatch.ultimatefilemanager.archive.ArchiveToolsBottomSheet
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
import java.util.Locale

/**
 * Built-in 7z browser using Apache Commons Compress.
 */
class SevenZipViewerActivity : AppCompatActivity() {

    private enum class ExtractOpMode { EXTRACT_ALL, COPY_SINGLE, MOVE_OUT_SINGLE }

    private lateinit var recyclerEntries: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var txtTitle: TextView
    private lateinit var txtBreadcrumb: TextView
    private lateinit var btnExtractAll: MaterialButton
    private lateinit var layoutEmpty: View
    private var fabArchiveTools: ExtendedFloatingActionButton? = null

    private var sevenZipFile: SevenZFile? = null
    private var sourceFile: File? = null
    private var currentPath = ""
    private var allEntries = listOf<SevenZArchiveEntry>()
    private var allEntryInfos = listOf<ArchiveManager.ArchiveEntryInfo>()
    private var archivePassword: String? = null
    private val extractedFiles = mutableMapOf<String, File>()
    private var focusedItem: SevenZipItem? = null
    private val selectedSevenZipItems = mutableSetOf<SevenZipItem>()

    private var pendingExtractEntry: SevenZArchiveEntry? = null
    private var pendingTargetItem: SevenZipItem? = null
    private var pendingSelectedSevenZipItems: List<SevenZipItem> = emptyList()
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
                if (pendingSelectedSevenZipItems.isNotEmpty()) {
                    doMoveOutMultipleItems(pendingSelectedSevenZipItems, destDir)
                } else pendingTargetItem?.let { doMoveOutSingleItem(it, destDir) }
            } else {
                if (pendingSelectedSevenZipItems.isNotEmpty()) {
                    doExtractMultipleItems(pendingSelectedSevenZipItems, destDir)
                } else pendingExtractEntry?.let { doExtractSingleFile(it, destDir) }
                    ?: pendingTargetItem?.let { doExtractSingleItem(it, destDir) }
            }
        }
        pendingExtractEntry = null
        pendingTargetItem = null
        pendingSelectedSevenZipItems = emptyList()
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
        fabArchiveTools = findViewById(R.id.fabArchiveTools)

        fabArchiveTools?.setOnClickListener { showArchiveToolsBottomSheet() }

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { navigateBack() }

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                navigateBack()
            }
        })

        val filePath = intent.getStringExtra(FileViewerRouter.EXTRA_FILE_PATH) ?: run {
            finish(); return
        }
        sourceFile = File(filePath)
        txtTitle.text = intent.getStringExtra(FileViewerRouter.EXTRA_FILE_NAME) ?: getString(R.string.archive_1)

        btnExtractAll.setOnClickListener { extractAll() }
        recyclerEntries.layoutManager = LinearLayoutManager(this)
        
        load7z(sourceFile!!)
        
        if (isTv) {
            setupTvFocus()
        }
    }

    private fun setupTvFocus() {
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
        btnOptions.setOnClickListener {
            val target = selectedSevenZipItems.firstOrNull() ?: focusedItem
            if (target != null) {
                if (!selectedSevenZipItems.contains(target)) {
                    selectedSevenZipItems.clear()
                    selectedSevenZipItems.add(target)
                    updateFabVisibility()
                }
                showArchiveToolsBottomSheet()
            }
        }
    }

    private fun navigateBack() {
        if (selectedSevenZipItems.isNotEmpty()) {
            clearSelection()
        } else if (currentPath.isNotEmpty()) {
            currentPath = if (currentPath.contains("/")) currentPath.substringBeforeLast("/") else ""
            displayEntries()
        } else {
            finish()
        }
    }

    @Suppress("DEPRECATION")
    private fun createSevenZFile(file: File, password: String? = null): SevenZFile {
        val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        val options = org.apache.commons.compress.archivers.sevenz.SevenZFileOptions.builder()
            .withMaxMemoryLimitInKb(maxMemoryKb)
            .build()
        return if (password != null) {
            SevenZFile(file, password.toCharArray(), options)
        } else {
            SevenZFile(file, options)
        }
    }

    private fun load7z(file: File) {
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (file.extension.lowercase(Locale.ROOT) == "7z") {
                    val szf = createSevenZFile(file, archivePassword)
                    val entries = szf.entries.toList()
                    
                    if (archivePassword == null) {
                        var needsPassword = false
                        for (entry in entries) {
                            if (!entry.isDirectory && entry.hasStream()) {
                                entry.contentMethods?.forEach { m ->
                                    if (m.method == SevenZMethod.AES256SHA256) {
                                        needsPassword = true
                                    }
                                }
                            }
                            if (needsPassword) break
                        }
                        
                        if (needsPassword) {
                            szf.close()
                            withContext(Dispatchers.Main) {
                                progressBar.visibility = View.GONE
                                showPasswordPrompt(file)
                            }
                            return@launch
                        }
                    }

                    sevenZipFile = szf
                    allEntries = entries
                    allEntryInfos = entries.map { entry ->
                        ArchiveManager.ArchiveEntryInfo(
                            name = entry.name,
                            isDirectory = entry.isDirectory,
                            uncompressedSize = entry.size,
                            lastModified = entry.lastModifiedDate?.time ?: 0L
                        )
                    }
                } else {
                    val entries = ArchiveManager.getArchiveEntries(file, archivePassword)
                    allEntryInfos = entries
                }
                
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    displayEntries()
                }
            } catch (e: OutOfMemoryError) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    showSnackbar(getString(R.string.error_opening_7z_emessage, getString(R.string.error_not_enough_memory)))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    if (archivePassword == null && isPasswordError(e)) {
                        showPasswordPrompt(file)
                    } else {
                        val msg = if (e is MemoryLimitException) {
                            getString(R.string.error_not_enough_memory)
                        } else {
                            e.message ?: "Unknown error"
                        }
                        showSnackbar(getString(R.string.error_opening_7z_emessage, msg))
                    }
                }
            }
        }
    }

    private fun isPasswordError(e: Exception): Boolean {
        val msg = e.message?.lowercase(Locale.ROOT) ?: ""
        return msg.contains("password") || msg.contains("encrypt") || msg.contains("decrypt") || 
               e.javaClass.simpleName.contains("Password", ignoreCase = true)
    }

    private fun showPasswordPrompt(file: File) {
        val dialog = PasswordPromptDialog()
        dialog.setOnConfirm { password ->
            archivePassword = password
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    if (file.extension.lowercase(Locale.ROOT) == "7z") {
                        createSevenZFile(file, password).use { testSzf ->
                            val entries = testSzf.entries.toList()
                            val firstFile = entries.firstOrNull { it.hasStream() && it.size > 0 }
                            if (firstFile != null) {
                                var te = testSzf.getNextEntry()
                                while (te != null && te.name != firstFile.name) {
                                    te = testSzf.getNextEntry()
                                }
                                if (te != null) {
                                    testSzf.read()
                                }
                            }
                        }
                    }
                    withContext(Dispatchers.Main) {
                        load7z(file)
                    }
                } catch (e: OutOfMemoryError) {
                    withContext(Dispatchers.Main) {
                        showSnackbar(getString(R.string.error_opening_7z_emessage, getString(R.string.error_not_enough_memory)))
                        archivePassword = null
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        showSnackbar(getString(R.string.invalid_password))
                        archivePassword = null
                        showPasswordPrompt(file)
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
        clearSelection()
        txtBreadcrumb.text = if (currentPath.isEmpty()) "/" else "/$currentPath/"
        txtBreadcrumb.visibility = View.VISIBLE
        val prefix = if (currentPath.isEmpty()) "" else "$currentPath/"
        val items = mutableListOf<SevenZipItem>()
        val seenDirs = mutableSetOf<String>()

        for (entry in allEntryInfos) {
            val name = entry.name
            if (!name.startsWith(prefix)) continue
            val relativeName = name.removePrefix(prefix)
            if (relativeName.isEmpty()) continue

            if (relativeName.contains("/")) {
                val dirName = relativeName.substringBefore("/")
                if (dirName !in seenDirs) {
                    seenDirs.add(dirName)
                    items.add(SevenZipItem(dirName, true, entryInfo = null, entry = null))
                }
            } else {
                if (!entry.isDirectory) {
                    items.add(SevenZipItem(relativeName, false, entryInfo = entry, entry = null))
                }
            }
        }

        items.sortWith(compareBy<SevenZipItem> { !it.isDirectory }.thenBy(NaturalSort.order) { it.name })

        if (items.isEmpty()) {
            layoutEmpty.visibility = View.VISIBLE
            recyclerEntries.visibility = View.GONE
        } else {
            layoutEmpty.visibility = View.GONE
            recyclerEntries.visibility = View.VISIBLE
            recyclerEntries.adapter = SevenZipAdapter(items)
        }
    }

    private fun toggleSelection(item: SevenZipItem) {
        if (selectedSevenZipItems.contains(item)) {
            selectedSevenZipItems.remove(item)
        } else {
            selectedSevenZipItems.add(item)
        }
        updateFabVisibility()
        recyclerEntries.adapter?.notifyDataSetChanged()
    }

    private fun clearSelection() {
        selectedSevenZipItems.clear()
        updateFabVisibility()
        recyclerEntries.adapter?.notifyDataSetChanged()
    }

    private fun updateFabVisibility() {
        fabArchiveTools?.visibility = if (selectedSevenZipItems.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun showArchiveToolsBottomSheet() {
        val items = selectedSevenZipItems.toList()
        if (items.isEmpty()) return

        val ext = sourceFile?.extension?.lowercase(Locale.ROOT) ?: ""
        val isModifiable = ext != "rar"

        val actions = mutableListOf<ArchiveToolsBottomSheet.ActionItem>()

        // 1. Extract to... (Copy Out)
        actions.add(
            ArchiveToolsBottomSheet.ActionItem(
                id = "extract_to",
                label = getString(R.string.action_copy_out),
                defaultIconRes = R.drawable.ic_export,
                customIconId = "toolbar_copy_out"
            ) {
                pendingSelectedSevenZipItems = items
                pendingExtractAll = false
                pendingOpMode = ExtractOpMode.COPY_SINGLE
                launchDestPicker()
            }
        )

        // 2. Move out of archive (if modifiable)
        if (isModifiable) {
            actions.add(
                ArchiveToolsBottomSheet.ActionItem(
                    id = "move_out",
                    label = getString(R.string.action_move_out),
                    defaultIconRes = R.drawable.ic_move,
                    customIconId = "toolbar_move_out"
                ) {
                    pendingSelectedSevenZipItems = items
                    pendingExtractAll = false
                    pendingOpMode = ExtractOpMode.MOVE_OUT_SINGLE
                    launchDestPicker()
                }
            )

            // 3. Delete from archive
            actions.add(
                ArchiveToolsBottomSheet.ActionItem(
                    id = "delete_from_archive",
                    label = getString(R.string.action_delete_from_archive),
                    defaultIconRes = R.drawable.ic_delete,
                    customIconId = "toolbar_delete"
                ) {
                    confirmDeleteSevenZipItems(items)
                }
            )
        }

        val title = if (items.size == 1) items.first().name else getString(R.string.selection_count, items.size)
        val sheet = ArchiveToolsBottomSheet.newInstance(actions, title = title)
        sheet.show(supportFragmentManager, ArchiveToolsBottomSheet.TAG)
    }

    private fun doExtractMultipleItems(items: List<SevenZipItem>, destDir: File) {
        val file = sourceFile ?: return
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            var successCount = 0
            try {
                for (item in items) {
                    val entryPath = item.entryInfo?.name ?: item.entry?.name ?: (if (currentPath.isEmpty()) item.name else "$currentPath/${item.name}")
                    val res = ArchiveManager.extractArchiveEntry(file, entryPath, destDir, archivePassword)
                    if (res.isSuccess) successCount++
                }
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    if (successCount > 0) {
                        showSnackbar(getString(R.string.archive_extract_success, destDir.absolutePath))
                    } else {
                        showSnackbar(getString(R.string.archive_extract_error))
                    }
                    clearSelection()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    showSnackbar(getString(R.string.archive_operation_failed, e.message ?: "Unknown error"))
                    clearSelection()
                }
            }
        }
    }

    private fun doMoveOutMultipleItems(items: List<SevenZipItem>, destDir: File) {
        val file = sourceFile ?: return
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            var successCount = 0
            try {
                for (item in items) {
                    val entryPath = item.entryInfo?.name ?: item.entry?.name ?: (if (currentPath.isEmpty()) item.name else "$currentPath/${item.name}")
                    val res = ArchiveManager.moveArchiveEntry(file, entryPath, destDir, archivePassword)
                    if (res.isSuccess) successCount++
                }
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    if (successCount > 0) {
                        showSnackbar(getString(R.string.archive_extract_success, destDir.absolutePath))
                        load7z(file)
                    } else {
                        showSnackbar(getString(R.string.archive_operation_failed, "Failed to move items"))
                    }
                    clearSelection()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    showSnackbar(getString(R.string.archive_operation_failed, e.message ?: "Unknown error"))
                    clearSelection()
                }
            }
        }
    }

    private fun confirmDeleteSevenZipItems(items: List<SevenZipItem>) {
        val msg = if (items.size == 1) {
            getString(R.string.confirm_delete_archive_entry_msg, items.first().name)
        } else {
            getString(R.string.selection_count, items.size)
        }
        val dialogView = layoutInflater.inflate(R.layout.dialog_support_message, null)
        val imgIcon = dialogView.findViewById<ImageView>(R.id.imgDialogIcon)
        val txtTitle = dialogView.findViewById<TextView>(R.id.txtDialogTitle)
        val txtMessage = dialogView.findViewById<TextView>(R.id.txtDialogMessage)
        val btnPositive = dialogView.findViewById<MaterialButton>(R.id.btnDialogPositive)
        val btnNegative = dialogView.findViewById<MaterialButton>(R.id.btnDialogNegative)

        imgIcon?.setImageResource(R.drawable.ic_delete)
        txtTitle?.setText(R.string.confirm_delete_archive_entry_title)
        txtMessage?.text = msg
        btnPositive?.setText(R.string.action_delete)
        btnNegative?.setText(R.string.cancel)
        btnNegative?.visibility = View.VISIBLE

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        btnPositive?.setOnClickListener {
            dialog.dismiss()
            doDeleteSevenZipItems(items)
        }
        btnNegative?.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
    }

    private fun doDeleteSevenZipItems(items: List<SevenZipItem>) {
        val file = sourceFile ?: return
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                var deletedAny = false
                for (item in items) {
                    val entryPath = item.entryInfo?.name ?: item.entry?.name ?: (if (currentPath.isEmpty()) item.name else "$currentPath/${item.name}")
                    val res = ArchiveManager.deleteArchiveEntry(file, entryPath, archivePassword)
                    if (res.isSuccess) deletedAny = true
                }
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    if (deletedAny) {
                        showSnackbar(getString(R.string.archive_delete_success, if (items.size == 1) items.first().name else "${items.size} items"))
                        load7z(file)
                    }
                    clearSelection()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    showSnackbar(getString(R.string.archive_operation_failed, e.message ?: "Unknown error"))
                    clearSelection()
                }
            }
        }
    }

    /** Shows the extract-location dialog, then navigates to the storage/folder picker. */
    private fun extractAll() {
        val dialog = ExtractLocationDialog()
        dialog.setOnSetLocation {
            pendingExtractAll = true
            pendingOpMode = ExtractOpMode.EXTRACT_ALL
            pendingExtractEntry = null
            pendingTargetItem = null
            val intent = Intent(this, StorageBrowserActivity::class.java).apply {
                putExtra(StorageBrowserActivity.EXTRA_EXTRACT_DEST_PICKER, true)
            }
            extractDestLauncher.launch(intent)
        }
        dialog.show(supportFragmentManager, ExtractLocationDialog.TAG)
    }

    private fun showItemOptions(item: SevenZipItem) {
        val dialog = ArchiveItemOptionsDialog()
        dialog.setItemName(item.name)
        val ext = sourceFile?.extension?.lowercase(Locale.ROOT) ?: ""
        val isModifiable = ext != "rar"
        dialog.setAllowModification(isModifiable)
        dialog.setOnCopyOut {
            pendingExtractAll = false
            pendingExtractEntry = item.entry
            pendingTargetItem = item
            pendingOpMode = ExtractOpMode.COPY_SINGLE
            launchDestPicker()
        }
        dialog.setOnMoveOut {
            pendingExtractAll = false
            pendingExtractEntry = item.entry
            pendingTargetItem = item
            pendingOpMode = ExtractOpMode.MOVE_OUT_SINGLE
            launchDestPicker()
        }
        dialog.setOnDelete {
            confirmDeleteSevenZipItem(item)
        }
        dialog.show(supportFragmentManager, ArchiveItemOptionsDialog.TAG)
    }

    /** Extracts a single entry to the session cache and opens it in the built-in viewer. */
    private fun previewItem(item: SevenZipItem) {
        val source = sourceFile ?: return
        val entryPath = item.entryInfo?.name ?: item.entry?.name ?: return
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
                val session = ArchivePreviewCache.sessionDir(this@SevenZipViewerActivity)
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
                    FileViewerRouter.openFile(this@SevenZipViewerActivity, tempFile)
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
    private fun doExtractSingleItem(item: SevenZipItem, destDir: File) {
        val file = sourceFile ?: return
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val entryPath = item.entryInfo?.name ?: item.entry?.name ?: (if (currentPath.isEmpty()) item.name else "$currentPath/${item.name}")
                val res = ArchiveManager.extractArchiveEntry(file, entryPath, destDir, archivePassword)
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
    private fun doMoveOutSingleItem(item: SevenZipItem, destDir: File) {
        val file = sourceFile ?: return
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val entryPath = item.entryInfo?.name ?: item.entry?.name ?: (if (currentPath.isEmpty()) item.name else "$currentPath/${item.name}")
                val res = ArchiveManager.moveArchiveEntry(file, entryPath, destDir, archivePassword)
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    if (res.isSuccess) {
                        showSnackbar(getString(R.string.archive_move_success, item.name, destDir.absolutePath))
                        load7z(file)
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

    private fun confirmDeleteSevenZipItem(item: SevenZipItem) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_support_message, null)
        val imgIcon = dialogView.findViewById<ImageView>(R.id.imgDialogIcon)
        val txtTitle = dialogView.findViewById<TextView>(R.id.txtDialogTitle)
        val txtMessage = dialogView.findViewById<TextView>(R.id.txtDialogMessage)
        val btnPositive = dialogView.findViewById<MaterialButton>(R.id.btnDialogPositive)
        val btnNegative = dialogView.findViewById<MaterialButton>(R.id.btnDialogNegative)

        imgIcon?.setImageResource(R.drawable.ic_delete)
        txtTitle?.setText(R.string.confirm_delete_archive_entry_title)
        txtMessage?.text = getString(R.string.confirm_delete_archive_entry_msg, item.name)
        btnPositive?.setText(R.string.action_delete)
        btnNegative?.setText(R.string.cancel)
        btnNegative?.visibility = View.VISIBLE

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        btnPositive?.setOnClickListener {
            dialog.dismiss()
            doDeleteSevenZipItem(item)
        }
        btnNegative?.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
    }

    private fun doDeleteSevenZipItem(item: SevenZipItem) {
        val file = sourceFile ?: return
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val entryPath = item.entryInfo?.name ?: item.entry?.name ?: (if (currentPath.isEmpty()) item.name else "$currentPath/${item.name}")
                val res = ArchiveManager.deleteArchiveEntry(file, entryPath, archivePassword)
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    if (res.isSuccess) {
                        showSnackbar(getString(R.string.archive_delete_success, item.name))
                        load7z(file)
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

    private fun doExtractAll(destDir: File) {
        val file = sourceFile ?: return
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            val res = ArchiveManager.extract(this@SevenZipViewerActivity, file, destDir, archivePassword)
            withContext(Dispatchers.Main) {
                progressBar.visibility = View.GONE
                if (res.isSuccess) {
                    showSnackbar(getString(R.string.archive_extract_success, destDir.absolutePath))
                } else {
                    val msg = res.exceptionOrNull()?.message ?: ""
                    showSnackbar("${getString(R.string.archive_extract_error)}: $msg")
                }
            }
        }
    }

    /** Performs single-file extraction once the user has chosen a destination. */
    private fun doExtractSingleFile(targetEntry: SevenZArchiveEntry, destDir: File) {
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val szf = createSevenZFile(sourceFile!!, archivePassword)

                szf.use { z ->
                    var entry = z.getNextEntry()
                    while (entry != null) {
                        if (entry.name == targetEntry.name) {
                            destDir.mkdirs()
                            val outFile = File(destDir, entry.name.substringAfterLast("/"))
                            outFile.outputStream().use { out ->
                                val buffer = ByteArray(8192)
                                var len: Int
                                while (z.read(buffer).also { len = it } != -1) {
                                    out.write(buffer, 0, len)
                                }
                            }
                            break
                        }
                        entry = z.getNextEntry()
                    }
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
                    val msg = if (e is MemoryLimitException) {
                        getString(R.string.error_not_enough_memory)
                    } else {
                        e.message
                    }
                    showSnackbar("${getString(R.string.archive_extract_error)}: $msg")
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { sevenZipFile?.close() } catch (_: Exception) {}
        ArchivePreviewCache.purgeSession()
    }

    private fun showSnackbar(message: String) {
        val root = findViewById<View>(R.id.main)
        Snackbar.make(root, message, Snackbar.LENGTH_SHORT).show()
    }

    data class SevenZipItem(
        val name: String,
        val isDirectory: Boolean,
        val entryInfo: ArchiveManager.ArchiveEntryInfo? = null,
        val entry: SevenZArchiveEntry? = null
    )

    inner class SevenZipAdapter(private val items: List<SevenZipItem>) : RecyclerView.Adapter<SevenZipAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val icon: ImageView = v.findViewById(R.id.imgIcon)
            val txtName: TextView = v.findViewById(R.id.txtName)
            val txtInfo: TextView = v.findViewById(R.id.txtInfo)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_zip_entry, parent, false))
        
        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            val context = holder.itemView.context
            val isSelected = selectedSevenZipItems.contains(item)

            holder.txtName.text = item.name

            val cardView = holder.itemView as? com.google.android.material.card.MaterialCardView
            if (isSelected) {
                if (cardView != null) {
                    cardView.setCardBackgroundColor(androidx.core.content.ContextCompat.getColor(context, R.color.ufm_selection_highlight))
                    cardView.strokeColor = androidx.core.content.ContextCompat.getColor(context, R.color.ufm_accent)
                } else {
                    holder.itemView.setBackgroundColor(androidx.core.content.ContextCompat.getColor(context, R.color.ufm_selection_highlight))
                }
            } else {
                if (cardView != null) {
                    cardView.setCardBackgroundColor(androidx.core.content.ContextCompat.getColor(context, R.color.mobile_glass_card))
                    cardView.strokeColor = androidx.core.content.ContextCompat.getColor(context, R.color.mobile_glass_stroke)
                } else {
                    val typedValue = android.util.TypedValue()
                    context.theme.resolveAttribute(android.R.attr.selectableItemBackground, typedValue, true)
                    holder.itemView.setBackgroundResource(typedValue.resourceId)
                }
            }

            holder.icon.setOnClickListener {
                toggleSelection(item)
            }

            if (item.isDirectory) {
                holder.icon.setImageResource(R.drawable.ic_folder)
                holder.txtInfo.setText(R.string.folder)
                holder.itemView.setOnClickListener {
                    if (selectedSevenZipItems.isNotEmpty()) {
                        toggleSelection(item)
                    } else {
                        currentPath = if (currentPath.isEmpty()) item.name else "${currentPath}/${item.name}"
                        displayEntries()
                    }
                }
                holder.itemView.setOnLongClickListener {
                    toggleSelection(item)
                    true
                }
            } else {
                holder.icon.setImageResource(
                    FileTypeIconProvider.iconForExtension(holder.itemView.context, item.name.substringAfterLast('.', ""))
                )
                val rawSize = item.entryInfo?.uncompressedSize ?: item.entry?.size ?: 0L
                val size = Formatter.formatFileSize(this@SevenZipViewerActivity, rawSize)
                holder.txtInfo.text = size
                holder.itemView.setOnClickListener {
                    if (selectedSevenZipItems.isNotEmpty()) {
                        toggleSelection(item)
                    } else {
                        previewItem(item)
                    }
                }
                holder.itemView.setOnLongClickListener {
                    toggleSelection(item)
                    true
                }
            }

            // TV focus: yellow highlight + track focused entry for the Options button.
            if (DeviceUtils.isTvDevice(holder.itemView.context)) {
                val black = holder.itemView.context.getColor(R.color.tv_button_focused_yellow_text)
                val primaryColor = holder.itemView.context.getColor(R.color.mobile_text_primary)
                val secondaryColor = holder.itemView.context.getColor(R.color.mobile_text_secondary)
                val iconColor = holder.itemView.context.getColor(R.color.mobile_icon_tint)
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
        override fun getItemCount(): Int = items.size
    }
}
