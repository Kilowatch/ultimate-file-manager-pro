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
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZMethod
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.archive.ExtractLocationDialog
import za.kilowatch.ultimatefilemanager.archive.PasswordPromptDialog
import za.kilowatch.ultimatefilemanager.settings.FontSizeHelper
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import za.kilowatch.ultimatefilemanager.storage.FileBrowserActivity
import za.kilowatch.ultimatefilemanager.storage.StorageBrowserActivity
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import za.kilowatch.ultimatefilemanager.util.FileTypeIconProvider
import java.io.File
import java.util.Locale

/**
 * Built-in 7z browser using Apache Commons Compress.
 */
class SevenZipViewerActivity : AppCompatActivity() {

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
    private var archivePassword: String? = null

    // Stash single-file entry while the user picks a destination
    private var pendingExtractEntry: SevenZArchiveEntry? = null
    // true = extract all; false = single entry stored in pendingExtractEntry
    private var pendingExtractAll: Boolean = false

    /** Receives destination folder chosen via StorageBrowserActivity / FileBrowserActivity */
    private val extractDestLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val localPath = result.data
                ?.getStringExtra(FileBrowserActivity.RESULT_SELECTED_LOCAL_PATH)
                ?: return@registerForActivityResult
            val destDir = File(localPath)
            if (pendingExtractAll) {
                doExtractAll(destDir)
            } else {
                pendingExtractEntry?.let { doExtractSingleFile(it, destDir) }
            }
        }
        pendingExtractEntry = null
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

    private fun load7z(file: File) {
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // If we have a password, use it. If not, try without.
                val szf = if (archivePassword != null) {
                    SevenZFile(file, archivePassword?.toCharArray())
                } else {
                    SevenZFile(file)
                }
                
                val entries = szf.entries.toList()
                
                // If opened without password, we must check if content is encrypted
                if (archivePassword == null) {
                    var needsPassword = false
                    
                    // 1. Check metadata for AES method
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
                        showSnackbar(getString(R.string.error_opening_7z_emessage, e.message ?: "Unknown error"))
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
                    // Test password by attempting to read from the first file entry
                    SevenZFile(file, password.toCharArray()).use { testSzf ->
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
                    withContext(Dispatchers.Main) {
                        load7z(file)
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

        for (entry in allEntries) {
            val name = entry.name
            if (!name.startsWith(prefix)) continue
            val relativeName = name.removePrefix(prefix)
            if (relativeName.isEmpty()) continue

            if (relativeName.contains("/")) {
                val dirName = relativeName.substringBefore("/")
                if (dirName !in seenDirs) {
                    seenDirs.add(dirName)
                    items.add(SevenZipItem(dirName, true, null))
                }
            } else {
                if (!entry.isDirectory) {
                    items.add(SevenZipItem(relativeName, false, entry))
                }
            }
        }

        items.sortWith(compareBy<SevenZipItem> { !it.isDirectory }.thenBy { it.name.lowercase() })

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
            pendingExtractEntry = null
            val intent = Intent(this, StorageBrowserActivity::class.java).apply {
                putExtra(StorageBrowserActivity.EXTRA_EXTRACT_DEST_PICKER, true)
            }
            extractDestLauncher.launch(intent)
        }
        dialog.show(supportFragmentManager, ExtractLocationDialog.TAG)
    }

    /** Shows the extract-location dialog, then navigates to the storage/folder picker. */
    private fun extractSingleFile(targetEntry: SevenZArchiveEntry) {
        val dialog = ExtractLocationDialog()
        dialog.setOnSetLocation {
            pendingExtractAll = false
            pendingExtractEntry = targetEntry
            val intent = Intent(this, StorageBrowserActivity::class.java).apply {
                putExtra(StorageBrowserActivity.EXTRA_EXTRACT_DEST_PICKER, true)
            }
            extractDestLauncher.launch(intent)
        }
        dialog.show(supportFragmentManager, ExtractLocationDialog.TAG)
    }

    /** Performs the actual "Extract All" once the user has chosen a destination. */
    private fun doExtractAll(destDir: File) {
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                destDir.mkdirs()
                val szf = if (archivePassword != null) {
                    SevenZFile(sourceFile!!, archivePassword?.toCharArray())
                } else {
                    SevenZFile(sourceFile!!)
                }

                szf.use { z ->
                    val canonicalDest = destDir.canonicalPath
                    var entry = z.getNextEntry()
                    while (entry != null) {
                        val outFile = File(destDir, entry.name)
                        val canonicalOut = outFile.canonicalPath
                        
                        if (!canonicalOut.startsWith(canonicalDest + File.separator)) {
                            Log.w("7zViewer", "Zip Slip attempt detected! Skipping entry: ${entry.name}")
                            entry = z.getNextEntry()
                            continue
                        }

                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            outFile.outputStream().use { out ->
                                val buffer = ByteArray(8192)
                                var len: Int
                                while (z.read(buffer).also { len = it } != -1) {
                                    out.write(buffer, 0, len)
                                }
                            }
                        }
                        entry = z.getNextEntry()
                    }
                }

                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    showSnackbar(getString(R.string.archive_extract_success, destDir.absolutePath))
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
    private fun doExtractSingleFile(targetEntry: SevenZArchiveEntry, destDir: File) {
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val szf = if (archivePassword != null) {
                    SevenZFile(sourceFile!!, archivePassword?.toCharArray())
                } else {
                    SevenZFile(sourceFile!!)
                }

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
        try { sevenZipFile?.close() } catch (_: Exception) {}
    }

    private fun showSnackbar(message: String) {
        val root = findViewById<View>(R.id.main)
        Snackbar.make(root, message, Snackbar.LENGTH_SHORT).show()
    }

    data class SevenZipItem(val name: String, val isDirectory: Boolean, val entry: SevenZArchiveEntry?)

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
            } else {
                holder.icon.setImageResource(
                    FileTypeIconProvider.iconForExtension(holder.itemView.context, item.name.substringAfterLast('.', ""))
                )
                val entry = item.entry!!
                val size = Formatter.formatFileSize(this@SevenZipViewerActivity, entry.size)
                holder.txtInfo.text = size
                holder.itemView.setOnClickListener { extractSingleFile(entry) }
            }
        }
        override fun getItemCount(): Int = items.size
    }
}
