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
import org.apache.commons.compress.MemoryLimitException
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZMethod
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.archive.ArchiveItemOptionsDialog
import za.kilowatch.ultimatefilemanager.archive.ArchiveManager
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

    private var sevenZipFile: SevenZFile? = null
    private var sourceFile: File? = null
    private var currentPath = ""
    private var allEntries = listOf<SevenZArchiveEntry>()
    private var allEntryInfos = listOf<ArchiveManager.ArchiveEntryInfo>()
    private var archivePassword: String? = null

    private var pendingExtractEntry: SevenZArchiveEntry? = null
    private var pendingTargetItem: SevenZipItem? = null
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
                pendingExtractEntry?.let { doExtractSingleFile(it, destDir) }
                    ?: pendingTargetItem?.let { doExtractSingleItem(it, destDir) }
            }
        }
        pendingExtractEntry = null
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
    }

    private fun navigateBack() {
        if (currentPath.isNotEmpty()) {
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
        txtBreadcrumb.text = if (currentPath.isEmpty()) "/" else "/$currentPath/"
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
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.confirm_delete_archive_entry_title)
            .setMessage(getString(R.string.confirm_delete_archive_entry_msg, item.name))
            .setPositiveButton(R.string.action_delete) { _, _ ->
                doDeleteSevenZipItem(item)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
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
            holder.txtName.text = item.name
            if (item.isDirectory) {
                holder.icon.setImageResource(R.drawable.ic_folder)
                holder.txtInfo.setText(R.string.folder)
                holder.itemView.setOnClickListener {
                    currentPath = if (currentPath.isEmpty()) item.name else "${currentPath}/${item.name}"
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
                val rawSize = item.entryInfo?.uncompressedSize ?: item.entry?.size ?: 0L
                val size = Formatter.formatFileSize(this@SevenZipViewerActivity, rawSize)
                holder.txtInfo.text = size
                holder.itemView.setOnClickListener { showItemOptions(item) }
                holder.itemView.setOnLongClickListener {
                    showItemOptions(item)
                    true
                }
            }
        }
        override fun getItemCount(): Int = items.size
    }
}
