package za.kilowatch.ultimatefilemanager.download

import android.app.Activity
import android.app.Dialog
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.swig.error_code
import org.libtorrent4j.swig.libtorrent
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.indexing.IndexingRepository
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import za.kilowatch.ultimatefilemanager.settings.ThemeHelper
import za.kilowatch.ultimatefilemanager.storage.FileBrowserActivity
import za.kilowatch.ultimatefilemanager.storage.StorageBrowserActivity
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import za.kilowatch.ultimatefilemanager.util.TransferConflictHelper
import java.io.File

/**
 * Unified Download Manager Activity for Mobile and Android TV.
 * Supports Direct HTTP/HTTPS downloads and BitTorrent / Magnet transfers.
 * Modeled after images/layout.jpeg with zero-seeding privacy protection.
 */
class DownloadManagerActivity : AppCompatActivity(), DownloadTransferManager.TransferUpdateListener {

    private var isTv = false
    private var txtSubtitle: TextView? = null
    private var layoutEmptyContainer: View? = null
    private var txtCurrentLocation: TextView? = null
    private var recyclerDownloads: RecyclerView? = null
    private var txtEmptyTransfers: TextView? = null
    private var adapter: DownloadTransferAdapter? = null
    private var activeDialogSaveLocationText: TextView? = null

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val pickedPath = result.data?.getStringExtra(FileBrowserActivity.RESULT_SELECTED_LOCAL_PATH)
                ?: result.data?.getStringExtra(FileBrowserActivity.RESULT_SELECTED_PATH)
            if (!pickedPath.isNullOrBlank()) {
                DownloadPreferencesManager.setDownloadDir(this, pickedPath)
                updateLocationDisplay()
                activeDialogSaveLocationText?.text = pickedPath
            }
        }
    }

    private val torrentFilePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val pickedPath = result.data?.getStringExtra(FileBrowserActivity.RESULT_SELECTED_LOCAL_PATH)
                ?: result.data?.getStringExtra(FileBrowserActivity.RESULT_SELECTED_PATH)
            if (!pickedPath.isNullOrBlank()) {
                val file = File(pickedPath)
                if (file.exists()) {
                    val info = runCatching { TorrentInfo(file) }.getOrNull()
                    if (info != null) {
                        showTorrentFileSelectionDialog(info, file, DownloadPreferencesManager.getDownloadDir(this))
                    } else {
                        showTorrentInputDialog(preselectedFile = file)
                    }
                }
            }
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        isTv = DeviceUtils.isTvDevice(this)

        if (!isTv) {
            enableEdgeToEdge()
        }

        val layoutRes = if (isTv) R.layout.activity_download_manager_tv else R.layout.activity_download_manager
        setContentView(layoutRes)

        // Setup Edge-to-Edge insets for mobile
        if (!isTv) {
            val root = findViewById<View>(R.id.main)
            if (root != null) {
                ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
                    val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                    v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
                    insets
                }
            }
        }

        txtSubtitle = findViewById(R.id.txtSubtitle)
        layoutEmptyContainer = findViewById(R.id.layoutEmptyContainer)
        txtCurrentLocation = findViewById(R.id.txtDownloadLocationPath)
        recyclerDownloads = findViewById(R.id.recyclerDownloads)
        txtEmptyTransfers = findViewById(R.id.txtEmptyTransfers)

        findViewById<View>(R.id.btnBack)?.setOnClickListener { finish() }

        // Setup Top Cards
        findViewById<View>(R.id.cardDirectDownloads)?.setOnClickListener {
            showDirectDownloadInputDialog()
        }
        findViewById<View>(R.id.cardTorrentDownloads)?.setOnClickListener {
            showTorrentInputDialog()
        }

        // Setup Location row
        findViewById<View>(R.id.rowDownloadLocation)?.setOnClickListener {
            launchFolderPicker()
        }
        findViewById<View>(R.id.btnChangeLocation)?.setOnClickListener {
            launchFolderPicker()
        }

        // Setup RecyclerView
        adapter = DownloadTransferAdapter(
            isTv = isTv,
            onPauseResumeClicked = { item ->
                if (item.state == DownloadTransferManager.TransferState.DOWNLOADING) {
                    DownloadTransferManager.pauseTransfer(item.id)
                } else {
                    DownloadTransferManager.resumeTransfer(this, item.id)
                }
            },
            onCancelClicked = { item ->
                showDeleteTransferConfirmationDialog(item)
            },
            onItemClicked = { item ->
                showTransferActionDialog(item)
            }
        )
        recyclerDownloads?.layoutManager = LinearLayoutManager(this)
        recyclerDownloads?.adapter = adapter

        updateLocationDisplay()
        refreshTransfersList()

        // Mandatory Legal Gate check on open
        DownloadLegalGateHelper.checkAndGate(this) {
            handleIncomingIntent(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        DownloadTransferManager.addListener(this)
        updateLocationDisplay()
        refreshTransfersList()
    }

    override fun onPause() {
        super.onPause()
        DownloadTransferManager.removeListener(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onTransfersChanged() {
        runOnUiThread {
            refreshTransfersList()
        }
    }

    override fun onTransferProgress(item: DownloadTransferManager.TransferItem) {
        runOnUiThread {
            refreshTransfersList()
        }
    }

    override fun onPreflightFailed(
        item: DownloadTransferManager.TransferItem,
        result: StoragePreflightValidator.ValidationResult
    ) {
        runOnUiThread {
            when (result.errorType) {
                StoragePreflightValidator.ErrorType.FAT32_LIMIT_EXCEEDED -> {
                    val sizeFormatted = formatBytes(result.offendingFileSize)
                    MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
                        .setTitle(R.string.download_storage_fat32_error_title)
                        .setMessage(getString(R.string.download_storage_fat32_error_msg, sizeFormatted))
                        .setPositiveButton(R.string.download_storage_choose_new_location) { _, _ ->
                            launchFolderPicker()
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
                StoragePreflightValidator.ErrorType.INSUFFICIENT_SPACE -> {
                    val reqFormatted = formatBytes(result.requiredBytes)
                    val availFormatted = formatBytes(result.availableBytes)
                    MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
                        .setTitle(R.string.download_storage_insufficient_title)
                        .setMessage(getString(R.string.download_storage_insufficient_msg, reqFormatted, availFormatted))
                        .setPositiveButton(R.string.download_storage_choose_new_location) { _, _ ->
                            launchFolderPicker()
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
                else -> {
                    Toast.makeText(this, "Target directory is not writable", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun refreshTransfersList() {
        val list = DownloadTransferManager.getAllTransfers()
        adapter?.submitList(list)
        layoutEmptyContainer?.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        recyclerDownloads?.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE

        val activeCount = list.count { it.state == DownloadTransferManager.TransferState.DOWNLOADING }
        val totalSpeed = list.sumOf { it.speedBytesPerSec }
        txtSubtitle?.text = when {
            list.isEmpty() -> getString(R.string.download_no_transfers)
            activeCount > 0 -> {
                val speedStr = formatSpeed(totalSpeed)
                if (totalSpeed > 0) "$activeCount downloading • $speedStr" else "$activeCount downloading"
            }
            else -> "${list.size} transfers"
        }
    }

    private fun updateLocationDisplay() {
        val currentDir = DownloadPreferencesManager.getDownloadDir(this)
        txtCurrentLocation?.text = currentDir.absolutePath
    }

    private fun launchFolderPicker() {
        val intent = Intent(this, StorageBrowserActivity::class.java).apply {
            putExtra(FileBrowserActivity.EXTRA_TORRENT_DOWNLOAD_LOCATION_PICKER, true)
        }
        folderPickerLauncher.launch(intent)
    }

    private fun cacheTorrentUri(uri: Uri): File? {
        return try {
            val fileName = contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1 && cursor.moveToFirst()) cursor.getString(nameIndex) else null
            } ?: "download_${System.currentTimeMillis()}.torrent"

            val torrentDir = File(cacheDir, "torrents").apply { mkdirs() }
            val target = File(torrentDir, fileName)
            contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            if (target.exists() && target.length() > 0) target else null
        } catch (e: Exception) {
            null
        }
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val extraPath = intent.getStringExtra("extra_torrent_path")
        if (!extraPath.isNullOrBlank()) {
            val f = File(extraPath)
            if (f.exists()) {
                val info = runCatching { TorrentInfo(f) }.getOrNull()
                if (info != null) {
                    showTorrentFileSelectionDialog(info, f, DownloadPreferencesManager.getDownloadDir(this))
                } else {
                    showTorrentInputDialog(preselectedFile = f)
                }
                return
            }
        }
        val uri = intent.data ?: intent.clipData?.getItemAt(0)?.uri ?: return
        val scheme = uri.scheme?.lowercase()

        when {
            scheme == "magnet" -> {
                showTorrentInputDialog(preselectedMagnet = uri.toString())
            }
            scheme == "http" || scheme == "https" -> {
                showDirectDownloadInputDialog(preselectedUrl = uri.toString())
            }
            scheme == "file" -> {
                val path = uri.path
                if (path != null && path.endsWith(".torrent", ignoreCase = true)) {
                    val f = File(path)
                    val info = runCatching { TorrentInfo(f) }.getOrNull()
                    if (info != null) {
                        showTorrentFileSelectionDialog(info, f, DownloadPreferencesManager.getDownloadDir(this))
                    } else {
                        showTorrentInputDialog(preselectedFile = f)
                    }
                }
            }
            scheme == "content" -> {
                val cached = cacheTorrentUri(uri)
                if (cached != null) {
                    val info = runCatching { TorrentInfo(cached) }.getOrNull()
                    if (info != null) {
                        showTorrentFileSelectionDialog(info, cached, DownloadPreferencesManager.getDownloadDir(this))
                    } else {
                        showTorrentInputDialog(preselectedFile = cached)
                    }
                }
            }
        }
    }

    private fun showDirectDownloadInputDialog(preselectedUrl: String? = null) {
        val layoutRes = if (isTv) R.layout.dialog_direct_download_tv else R.layout.dialog_direct_download
        val dialogView = LayoutInflater.from(this).inflate(layoutRes, null, false)

        val editUrl = dialogView.findViewById<EditText>(R.id.editDownloadUrl)
        val btnPaste = dialogView.findViewById<View>(R.id.btnPasteClipboard)
        val txtLocation = dialogView.findViewById<TextView>(R.id.txtSaveLocation)
        val btnChangeLoc = dialogView.findViewById<View>(R.id.btnChangeLocation)
        val btnStart = dialogView.findViewById<MaterialButton>(R.id.btnStartDownload)
        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnCancel)

        if (!preselectedUrl.isNullOrBlank()) {
            editUrl?.setText(preselectedUrl)
        }

        txtLocation?.text = DownloadPreferencesManager.getDownloadDir(this).absolutePath
        activeDialogSaveLocationText = txtLocation

        btnPaste?.setOnClickListener {
            val clip = (getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).text?.toString()?.trim()
                if (!text.isNullOrEmpty()) {
                    editUrl?.setText(text)
                }
            }
        }

        btnChangeLoc?.setOnClickListener {
            launchFolderPicker()
        }

        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        btnCancel?.setOnClickListener {
            dialog.dismiss()
        }

        btnStart?.setOnClickListener {
            val url = editUrl?.text?.toString()?.trim() ?: ""
            if (url.isBlank() || (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true))) {
                editUrl?.error = "Please enter a valid HTTP or HTTPS URL"
                return@setOnClickListener
            }

            val targetDir = DownloadPreferencesManager.getDownloadDir(this)
            val fileName = url.substringBefore("?").substringAfterLast("/").ifBlank { "download" }
            val destFile = File(targetDir, fileName)

            if (destFile.exists()) {
                lifecycleScope.launch {
                    val action = TransferConflictHelper.showConflictDialog(
                        activity = this@DownloadManagerActivity,
                        fileName = fileName,
                        isFolder = destFile.isDirectory,
                        destSizeBytes = destFile.length(),
                        applyToAllRef = booleanArrayOf(false)
                    )
                    when (action) {
                        TransferConflictHelper.ConflictAction.OVERWRITE -> {
                            destFile.delete()
                            DownloadTransferManager.startHttpDownload(this@DownloadManagerActivity, url, targetDir, fileName)
                            dialog.dismiss()
                        }
                        TransferConflictHelper.ConflictAction.KEEP_BOTH -> {
                            val unique = TransferConflictHelper.uniqueLocalFile(targetDir, fileName, this@DownloadManagerActivity)
                            DownloadTransferManager.startHttpDownload(this@DownloadManagerActivity, url, targetDir, unique.name)
                            dialog.dismiss()
                        }
                        TransferConflictHelper.ConflictAction.SKIP,
                        TransferConflictHelper.ConflictAction.CANCEL -> {
                            // Leave dialog open or user cancelled
                        }
                    }
                }
            } else {
                DownloadTransferManager.startHttpDownload(this, url, targetDir, fileName)
                dialog.dismiss()
            }
        }

        dialog.setOnDismissListener {
            if (activeDialogSaveLocationText == txtLocation) {
                activeDialogSaveLocationText = null
            }
        }

        dialog.show()

        if (isTv) {
            dialog.window?.setLayout(
                (resources.displayMetrics.widthPixels * 0.50).toInt(),
                android.view.WindowManager.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private fun showTorrentInputDialog(preselectedFile: File? = null, preselectedMagnet: String? = null) {
        val layoutRes = if (isTv) R.layout.dialog_torrent_download_tv else R.layout.dialog_torrent_download
        val dialogView = LayoutInflater.from(this).inflate(layoutRes, null, false)

        var currentSelectedFile: File? = preselectedFile

        val cardSelectedTorrent = dialogView.findViewById<View>(R.id.cardSelectedTorrent)
        val txtTorrentFileName = dialogView.findViewById<TextView>(R.id.txtTorrentFileName)
        val txtTorrentFileSize = dialogView.findViewById<TextView>(R.id.txtTorrentFileSize)
        val btnRemoveTorrentFile = dialogView.findViewById<View>(R.id.btnRemoveTorrentFile)

        val layoutMagnetSection = dialogView.findViewById<View>(R.id.layoutMagnetSection)
        val editMagnetUrl = dialogView.findViewById<EditText>(R.id.editMagnetUrl)
        val btnPasteMagnet = dialogView.findViewById<View>(R.id.btnPasteMagnet)
        val btnBrowseTorrentFile = dialogView.findViewById<View>(R.id.btnBrowseTorrentFile)

        val txtLocation = dialogView.findViewById<TextView>(R.id.txtSaveLocation)
        val btnChangeLoc = dialogView.findViewById<View>(R.id.btnChangeLocation)
        val btnStart = dialogView.findViewById<MaterialButton>(R.id.btnStartDownload)
        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnCancel)

        txtLocation?.text = DownloadPreferencesManager.getDownloadDir(this).absolutePath
        activeDialogSaveLocationText = txtLocation

        fun updateUI() {
            val f = currentSelectedFile
            if (f != null && f.exists()) {
                cardSelectedTorrent?.visibility = View.VISIBLE
                txtTorrentFileName?.text = f.name
                txtTorrentFileSize?.text = formatBytes(f.length())
                layoutMagnetSection?.visibility = View.GONE
            } else {
                cardSelectedTorrent?.visibility = View.GONE
                layoutMagnetSection?.visibility = View.VISIBLE
                if (!preselectedMagnet.isNullOrBlank()) {
                    editMagnetUrl?.setText(preselectedMagnet)
                }
            }
        }
        updateUI()

        btnRemoveTorrentFile?.setOnClickListener {
            currentSelectedFile = null
            updateUI()
        }

        btnPasteMagnet?.setOnClickListener {
            val clip = (getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).text?.toString()?.trim()
                if (!text.isNullOrEmpty()) {
                    editMagnetUrl?.setText(text)
                }
            }
        }

        btnChangeLoc?.setOnClickListener {
            launchFolderPicker()
        }

        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        btnBrowseTorrentFile?.setOnClickListener {
            dialog.dismiss()
            val intent = Intent(this, StorageBrowserActivity::class.java).apply {
                putExtra(FileBrowserActivity.EXTRA_PICKER_MODE, true)
                putExtra(FileBrowserActivity.EXTRA_PICKER_EXTENSIONS, "torrent")
            }
            torrentFilePickerLauncher.launch(intent)
        }

        btnCancel?.setOnClickListener {
            dialog.dismiss()
        }

        btnStart?.setOnClickListener {
            val targetDir = DownloadPreferencesManager.getDownloadDir(this)
            val file = currentSelectedFile

            if (file != null && file.exists()) {
                val torrentInfo = runCatching { TorrentInfo(file) }.getOrNull()
                if (torrentInfo != null) {
                    dialog.dismiss()
                    showTorrentFileSelectionDialog(torrentInfo, file, targetDir)
                    return@setOnClickListener
                }
                Toast.makeText(this, "Failed to inspect torrent file", Toast.LENGTH_SHORT).show()
            } else {
                val magnetUri = editMagnetUrl?.text?.toString()?.trim() ?: ""
                if (magnetUri.isBlank() || !magnetUri.startsWith("magnet:?", ignoreCase = true)) {
                    editMagnetUrl?.error = "Please enter a valid Magnet link"
                    return@setOnClickListener
                }
                startMagnetResolutionFlow(magnetUri, dialog)
            }
        }

        dialog.setOnDismissListener {
            if (activeDialogSaveLocationText == txtLocation) {
                activeDialogSaveLocationText = null
            }
        }

        dialog.show()

        if (isTv) {
            dialog.window?.setLayout(
                (resources.displayMetrics.widthPixels * 0.50).toInt(),
                android.view.WindowManager.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private fun startMagnetResolutionFlow(magnetUri: String, parentDialogToDismiss: Dialog?) {
        val layoutRes = if (isTv) R.layout.dialog_torrent_fetch_metadata_tv else R.layout.dialog_torrent_fetch_metadata
        val dialogView = LayoutInflater.from(this).inflate(layoutRes, null, false)
        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnCancelFetch)

        val progressDialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        progressDialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        btnCancel?.setOnClickListener { progressDialog.dismiss() }
        progressDialog.show()

        if (isTv) {
            progressDialog.window?.setLayout(
                (520 * resources.displayMetrics.density).toInt(),
                android.view.WindowManager.LayoutParams.WRAP_CONTENT
            )
        }

        var isResolved = false
        var isCancelled = false

        progressDialog.setOnCancelListener { isCancelled = true }
        progressDialog.setOnDismissListener { isCancelled = true }

        val timeoutHandler = Handler(Looper.getMainLooper())
        val timeoutRunnable = Runnable {
            if (isFinishing || isDestroyed) return@Runnable
            if (!isResolved && !isCancelled && progressDialog.isShowing) {
                progressDialog.dismiss()
                showMagnetTimeoutDialog(magnetUri, parentDialogToDismiss)
            }
        }
        timeoutHandler.postDelayed(timeoutRunnable, 20_000L)

        DownloadTransferManager.fetchMagnetMetadata(this, magnetUri, timeoutSeconds = 25) { cachedTorrentFile ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                timeoutHandler.removeCallbacks(timeoutRunnable)
                if (isCancelled) return@runOnUiThread
                isResolved = true
                if (progressDialog.isShowing) {
                    progressDialog.dismiss()
                }
                if (cachedTorrentFile != null && cachedTorrentFile.exists()) {
                    parentDialogToDismiss?.dismiss()
                    val info = runCatching { TorrentInfo(cachedTorrentFile) }.getOrNull()
                    if (info != null) {
                        showTorrentFileSelectionDialog(
                            torrentInfo = info,
                            torrentFile = cachedTorrentFile,
                            initialTargetDir = DownloadPreferencesManager.getDownloadDir(this)
                        )
                    } else {
                        Toast.makeText(this, "Failed to parse torrent metadata", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    showMagnetTimeoutDialog(magnetUri, parentDialogToDismiss)
                }
            }
        }
    }

    private fun showMagnetTimeoutDialog(magnetUri: String, parentDialogToDismiss: Dialog?) {
        val layoutRes = if (isTv) R.layout.dialog_torrent_metadata_timeout_tv else R.layout.dialog_torrent_metadata_timeout
        val dialogView = LayoutInflater.from(this).inflate(layoutRes, null, false)

        val btnDownloadAll = dialogView.findViewById<MaterialButton>(R.id.btnDownloadAllDirect)
        val btnKeepWaiting = dialogView.findViewById<MaterialButton>(R.id.btnKeepWaiting)
        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnCancelTimeout)

        val timeoutDialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        timeoutDialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        btnDownloadAll?.setOnClickListener {
            timeoutDialog.dismiss()
            parentDialogToDismiss?.dismiss()
            val targetDir = DownloadPreferencesManager.getDownloadDir(this)
            DownloadTransferManager.startTorrentDownload(this, magnetUri, targetDir)
        }

        btnKeepWaiting?.setOnClickListener {
            timeoutDialog.dismiss()
            startMagnetResolutionFlow(magnetUri, parentDialogToDismiss)
        }

        btnCancel?.setOnClickListener {
            timeoutDialog.dismiss()
        }

        timeoutDialog.show()

        if (isTv) {
            timeoutDialog.window?.setLayout(
                (560 * resources.displayMetrics.density).toInt(),
                android.view.WindowManager.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private fun showTorrentFileSelectionDialog(
        torrentInfo: TorrentInfo,
        torrentFile: File,
        initialTargetDir: File
    ) {
        val layoutRes = if (isTv) R.layout.dialog_torrent_file_selection_tv else R.layout.dialog_torrent_file_selection
        val dialogView = LayoutInflater.from(this).inflate(layoutRes, null, false)

        val rootNode = TorrentFileNode.fromTorrentInfo(torrentInfo)

        val txtTorrentTitle = dialogView.findViewById<TextView>(R.id.txtTorrentTitle)
        val txtSelectedSummary = dialogView.findViewById<TextView>(R.id.txtSelectedSummary)
        val txtSaveLocation = dialogView.findViewById<TextView>(R.id.txtSaveLocation)
        val btnChangeLocation = dialogView.findViewById<View>(R.id.btnChangeLocation)
        val cardLocation = dialogView.findViewById<View>(R.id.cardLocation)
        val recycler = dialogView.findViewById<RecyclerView>(R.id.recyclerTorrentFiles)
        val btnSelectAll = dialogView.findViewById<View>(R.id.btnSelectAll)
        val btnDeselectAll = dialogView.findViewById<View>(R.id.btnDeselectAll)
        val editSearch = dialogView.findViewById<EditText>(R.id.editSearchFiles)
        val btnStart = dialogView.findViewById<MaterialButton>(R.id.btnStartDownload)
        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnCancel)

        // Category filter chips
        val chipAll = dialogView.findViewById<TextView>(R.id.chipFilterAll)
        val chipVideos = dialogView.findViewById<TextView>(R.id.chipFilterVideos)
        val chipAudio = dialogView.findViewById<TextView>(R.id.chipFilterAudio)
        val chipImages = dialogView.findViewById<TextView>(R.id.chipFilterImages)
        val chipArchives = dialogView.findViewById<TextView>(R.id.chipFilterArchives)
        val chipDocs = dialogView.findViewById<TextView>(R.id.chipFilterDocs)

        txtTorrentTitle?.text = torrentInfo.name().ifBlank { torrentFile.nameWithoutExtension }
        txtSaveLocation?.text = initialTargetDir.absolutePath
        activeDialogSaveLocationText = txtSaveLocation

        val totalFiles = rootNode.getLeafFileCount()
        val totalBytes = torrentInfo.totalSize()
        var downloadStarted = false

        fun updateMetricsDisplay() {
            val selectedCount = rootNode.getSelectedLeafFileCount()
            val selectedBytes = rootNode.getSelectedBytes()
            txtSelectedSummary?.text = getString(
                R.string.torrent_selected_summary,
                selectedCount,
                totalFiles,
                formatBytes(selectedBytes),
                formatBytes(totalBytes)
            )

            btnStart?.isEnabled = selectedCount > 0
            btnStart?.alpha = if (selectedCount > 0) 1.0f else 0.5f
        }
        updateMetricsDisplay()

        val selectionAdapter = TorrentFileSelectionAdapter(
            rootNode = rootNode,
            isTv = isTv,
            onSelectionChanged = {
                runOnUiThread { updateMetricsDisplay() }
            }
        )
        recycler?.layoutManager = LinearLayoutManager(this)
        recycler?.adapter = selectionAdapter

        val chips = listOf(
            chipAll to TorrentFileSelectionAdapter.CategoryFilter.ALL,
            chipVideos to TorrentFileSelectionAdapter.CategoryFilter.VIDEOS,
            chipAudio to TorrentFileSelectionAdapter.CategoryFilter.AUDIO,
            chipImages to TorrentFileSelectionAdapter.CategoryFilter.IMAGES,
            chipArchives to TorrentFileSelectionAdapter.CategoryFilter.ARCHIVES,
            chipDocs to TorrentFileSelectionAdapter.CategoryFilter.DOCS
        )

        fun highlightChip(selectedChip: TextView?) {
            chips.forEach { (chip, _) ->
                if (chip == selectedChip) {
                    chip?.setTextColor(if (isTv) ContextCompat.getColor(this, R.color.tv_button_focused_yellow_text) else Color.WHITE)
                    chip?.setBackgroundResource(if (isTv) R.drawable.selector_tv_button_yellow else R.drawable.bg_btn_icon_frosted)
                } else {
                    chip?.setTextColor(ContextCompat.getColor(this, if (isTv) R.color.tv_text_secondary else R.color.mobile_text_secondary))
                    chip?.setBackgroundResource(if (isTv) R.drawable.selector_tv_button else R.drawable.bg_btn_icon_frosted)
                }
            }
        }

        chips.forEach { (chip, filter) ->
            chip?.setOnClickListener {
                highlightChip(chip)
                selectionAdapter.setCategoryFilter(filter)
            }
        }

        editSearch?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                selectionAdapter.setSearchQuery(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        btnSelectAll?.setOnClickListener {
            selectionAdapter.selectAll()
        }

        btnDeselectAll?.setOnClickListener {
            selectionAdapter.deselectAll()
        }

        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        btnChangeLocation?.setOnClickListener {
            launchFolderPicker()
        }
        cardLocation?.setOnClickListener {
            launchFolderPicker()
        }

        btnCancel?.setOnClickListener {
            dialog.dismiss()
        }

        btnStart?.setOnClickListener {
            val selectedIndices = rootNode.getSelectedFileIndices()
            if (selectedIndices.isEmpty()) {
                Toast.makeText(this, R.string.torrent_zero_selected_error, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val targetDir = DownloadPreferencesManager.getDownloadDir(this)
            val torrentName = torrentInfo.name().ifBlank { torrentFile.nameWithoutExtension }
            val isFolder = torrentInfo.numFiles() > 1
            val destFile = File(targetDir, torrentName)

            if (destFile.exists()) {
                lifecycleScope.launch {
                    val action = TransferConflictHelper.showConflictDialog(
                        activity = this@DownloadManagerActivity,
                        fileName = torrentName,
                        isFolder = destFile.isDirectory || isFolder,
                        destSizeBytes = if (destFile.isFile) destFile.length() else 0L,
                        applyToAllRef = booleanArrayOf(false)
                    )
                    when (action) {
                        TransferConflictHelper.ConflictAction.OVERWRITE -> {
                            downloadStarted = true
                            DownloadTransferManager.startTorrentFileDownload(
                                this@DownloadManagerActivity,
                                torrentFile,
                                targetDir,
                                selectedIndices
                            )
                            dialog.dismiss()
                        }
                        TransferConflictHelper.ConflictAction.KEEP_BOTH -> {
                            downloadStarted = true
                            val unique = TransferConflictHelper.uniqueLocalFile(targetDir, torrentName, this@DownloadManagerActivity)
                            val saveFolder = if (isFolder) unique else File(targetDir, "${unique.nameWithoutExtension}_dir")
                            saveFolder.mkdirs()
                            DownloadTransferManager.startTorrentFileDownload(
                                this@DownloadManagerActivity,
                                torrentFile,
                                saveFolder,
                                selectedIndices
                            )
                            dialog.dismiss()
                        }
                        TransferConflictHelper.ConflictAction.SKIP,
                        TransferConflictHelper.ConflictAction.CANCEL -> {
                            // Leave dialog open
                        }
                    }
                }
            } else {
                downloadStarted = true
                DownloadTransferManager.startTorrentFileDownload(
                    this,
                    torrentFile,
                    targetDir,
                    selectedIndices
                )
                dialog.dismiss()
            }
        }

        dialog.setOnDismissListener {
            if (activeDialogSaveLocationText == txtSaveLocation) {
                activeDialogSaveLocationText = null
            }
            if (!downloadStarted && torrentFile.absolutePath.startsWith(cacheDir.absolutePath)) {
                runCatching { torrentFile.delete() }
            }
        }

        dialog.show()

        if (isTv) {
            dialog.window?.setLayout(
                (800 * resources.displayMetrics.density).toInt(),
                android.view.WindowManager.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024L * 1024L * 1024L -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
            bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
            bytes >= 1024L -> String.format("%.0f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }

    private fun formatSpeed(bytesPerSec: Long): String {
        return when {
            bytesPerSec >= 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB/s", bytesPerSec / (1024.0 * 1024.0))
            bytesPerSec >= 1024 -> String.format(java.util.Locale.US, "%.0f KB/s", bytesPerSec / 1024.0)
            else -> "$bytesPerSec B/s"
        }
    }

    private fun formatEta(seconds: Long): String {
        val mins = seconds / 60
        val secs = seconds % 60
        return if (mins > 0) "${mins}m ${secs}s" else "${secs}s"
    }

    private fun showTransferActionDialog(item: DownloadTransferManager.TransferItem) {
        val layoutRes = if (isTv) R.layout.dialog_transfer_action_tv else R.layout.dialog_transfer_action
        val dialogView = LayoutInflater.from(this).inflate(layoutRes, null, false)

        val imgIcon = dialogView.findViewById<ImageView>(R.id.imgActionIcon)
        val txtFileName = dialogView.findViewById<TextView>(R.id.txtActionFileName)
        val txtStatus = dialogView.findViewById<TextView>(R.id.txtActionStatus)
        val txtSizeSpeed = dialogView.findViewById<TextView>(R.id.txtActionSizeSpeed)
        val txtEtaPeers = dialogView.findViewById<TextView>(R.id.txtActionEtaPeers)
        val txtSavePath = dialogView.findViewById<TextView>(R.id.txtActionSavePath)
        val btnPauseResume = dialogView.findViewById<MaterialButton>(R.id.btnActionPauseResume)
        val btnOpenFolder = dialogView.findViewById<MaterialButton>(R.id.btnActionOpenFolder)
        val btnDelete = dialogView.findViewById<MaterialButton>(R.id.btnActionDelete)
        val btnDismiss = dialogView.findViewById<MaterialButton>(R.id.btnActionDismiss)

        val iconRes = when (item.type) {
            DownloadTransferManager.TransferType.DIRECT_HTTP -> R.drawable.ic_link
            DownloadTransferManager.TransferType.TORRENT -> R.drawable.ic_file_torrent
            DownloadTransferManager.TransferType.MAGNET -> R.drawable.ic_magnet
        }
        imgIcon?.setImageResource(iconRes)
        txtFileName?.text = item.title

        val stateStr = when (item.state) {
            DownloadTransferManager.TransferState.DOWNLOADING -> "${getString(R.string.download_state_downloading)} (${item.progressPercent}%)"
            DownloadTransferManager.TransferState.PAUSED -> getString(R.string.download_state_paused)
            DownloadTransferManager.TransferState.QUEUED_WIFI -> getString(R.string.download_state_queued)
            DownloadTransferManager.TransferState.COMPLETED -> getString(R.string.download_state_completed)
            DownloadTransferManager.TransferState.ERROR -> "${getString(R.string.download_state_error)}: ${item.errorMessage ?: ""}"
        }
        txtStatus?.text = stateStr

        val downloadedText = formatBytes(item.downloadedBytes)
        val totalText = if (item.totalBytes > 0) formatBytes(item.totalBytes) else ""
        val speedText = formatSpeed(item.speedBytesPerSec)
        txtSizeSpeed?.text = if (totalText.isNotEmpty()) "$downloadedText / $totalText • $speedText" else "$downloadedText • $speedText"

        val etaPart = if (item.etaSeconds > 0) "ETA: ${formatEta(item.etaSeconds)}" else "ETA: Calculating..."
        val peersPart = if (item.type != DownloadTransferManager.TransferType.DIRECT_HTTP) {
            " • Peers: ${item.connectedPeers} (${item.totalPeers}) • Seeds: ${item.connectedSeeds}"
        } else ""
        txtEtaPeers?.text = "$etaPart$peersPart"
        txtSavePath?.text = item.targetFolder.absolutePath

        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        if (item.state == DownloadTransferManager.TransferState.COMPLETED) {
            btnPauseResume?.visibility = View.GONE
        } else if (item.state == DownloadTransferManager.TransferState.PAUSED ||
            item.state == DownloadTransferManager.TransferState.QUEUED_WIFI) {
            btnPauseResume?.text = getString(R.string.transfer_action_resume)
            btnPauseResume?.setIconResource(R.drawable.ic_play)
        } else {
            btnPauseResume?.text = getString(R.string.transfer_action_pause)
            btnPauseResume?.setIconResource(R.drawable.ic_pause)
        }

        btnPauseResume?.setOnClickListener {
            if (item.state == DownloadTransferManager.TransferState.DOWNLOADING) {
                DownloadTransferManager.pauseTransfer(item.id)
            } else {
                DownloadTransferManager.resumeTransfer(this, item.id)
            }
            dialog.dismiss()
        }

        btnOpenFolder?.setOnClickListener {
            dialog.dismiss()
            val downloadDest = if (item.title.isNotBlank()) File(item.targetFolder, item.title) else item.targetFolder
            val folderToOpen = if (downloadDest.exists() && downloadDest.isDirectory) {
                downloadDest
            } else {
                item.targetFolder
            }

            if (!folderToOpen.exists()) {
                folderToOpen.mkdirs()
            }

            if (folderToOpen.exists()) {
                val (resolvedSid, resolvedStype, resolvedRoot) = IndexingRepository.resolveStorageForPath(folderToOpen.absolutePath)
                val intent = Intent(this, FileBrowserActivity::class.java).apply {
                    action = Intent.ACTION_VIEW
                    putExtra(FileBrowserActivity.EXTRA_MOUNT_PATH, resolvedRoot)
                    putExtra(FileBrowserActivity.EXTRA_INITIAL_PATH, folderToOpen.absolutePath)
                    if (downloadDest.exists() && downloadDest.isFile) {
                        putExtra(FileBrowserActivity.EXTRA_FOCUS_PATH, downloadDest.absolutePath)
                    }
                    putExtra(FileBrowserActivity.EXTRA_STORAGE_ID, resolvedSid)
                    putExtra(FileBrowserActivity.EXTRA_STORAGE_TYPE, resolvedStype)
                }
                startActivity(intent)
            } else {
                Toast.makeText(this, "Folder does not exist", Toast.LENGTH_SHORT).show()
            }
        }

        btnDelete?.setOnClickListener {
            dialog.dismiss()
            showDeleteTransferConfirmationDialog(item)
        }

        btnDismiss?.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()

        if (isTv) {
            dialog.window?.setLayout(
                (650 * resources.displayMetrics.density).toInt(),
                android.view.WindowManager.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private fun showDeleteTransferConfirmationDialog(item: DownloadTransferManager.TransferItem) {
        val layoutRes = if (isTv) R.layout.dialog_transfer_delete_confirm_tv else R.layout.dialog_transfer_delete_confirm
        val dialogView = LayoutInflater.from(this).inflate(layoutRes, null, false)

        val btnKeepFiles = dialogView.findViewById<MaterialButton>(R.id.btnDeleteKeepFiles)
        val btnRemoveFiles = dialogView.findViewById<MaterialButton>(R.id.btnDeleteRemoveFiles)
        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnDeleteCancel)

        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        btnKeepFiles?.setOnClickListener {
            DownloadTransferManager.cancelTransfer(item.id, deleteFiles = false)
            dialog.dismiss()
        }

        btnRemoveFiles?.setOnClickListener {
            DownloadTransferManager.cancelTransfer(item.id, deleteFiles = true)
            dialog.dismiss()
        }

        btnCancel?.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()

        if (isTv) {
            dialog.window?.setLayout(
                (600 * resources.displayMetrics.density).toInt(),
                android.view.WindowManager.LayoutParams.WRAP_CONTENT
            )
        }
    }
}
