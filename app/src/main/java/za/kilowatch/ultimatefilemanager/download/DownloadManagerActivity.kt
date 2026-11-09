package za.kilowatch.ultimatefilemanager.download

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
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
                    showTorrentInputDialog(preselectedFile = file)
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
                DownloadTransferManager.cancelTransfer(item.id, deleteFiles = true)
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
                showTorrentInputDialog(preselectedFile = f)
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
                    showTorrentInputDialog(preselectedFile = File(path))
                }
            }
            scheme == "content" -> {
                val cached = cacheTorrentUri(uri)
                if (cached != null) {
                    showTorrentInputDialog(preselectedFile = cached)
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
                val torrentName = torrentInfo?.name() ?: file.nameWithoutExtension
                val isFolder = (torrentInfo?.numFiles() ?: 1) > 1
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
                                DownloadTransferManager.startTorrentFileDownload(this@DownloadManagerActivity, file, targetDir)
                                dialog.dismiss()
                            }
                            TransferConflictHelper.ConflictAction.KEEP_BOTH -> {
                                val unique = TransferConflictHelper.uniqueLocalFile(targetDir, torrentName, this@DownloadManagerActivity)
                                val saveFolder = if (isFolder) unique else File(targetDir, "${unique.nameWithoutExtension}_dir")
                                saveFolder.mkdirs()
                                DownloadTransferManager.startTorrentFileDownload(this@DownloadManagerActivity, file, saveFolder)
                                dialog.dismiss()
                            }
                            TransferConflictHelper.ConflictAction.SKIP,
                            TransferConflictHelper.ConflictAction.CANCEL -> {
                                // Keep dialog open
                            }
                        }
                    }
                } else {
                    DownloadTransferManager.startTorrentFileDownload(this, file, targetDir)
                    dialog.dismiss()
                }
            } else {
                val magnetUri = editMagnetUrl?.text?.toString()?.trim() ?: ""
                if (magnetUri.isBlank() || !magnetUri.startsWith("magnet:?", ignoreCase = true)) {
                    editMagnetUrl?.error = "Please enter a valid Magnet link"
                    return@setOnClickListener
                }

                val ec = error_code()
                val p = libtorrent.parse_magnet_uri(magnetUri, ec)
                val dn = p.name

                if (!dn.isNullOrBlank() && File(targetDir, dn).exists()) {
                    val destFile = File(targetDir, dn)
                    lifecycleScope.launch {
                        val action = TransferConflictHelper.showConflictDialog(
                            activity = this@DownloadManagerActivity,
                            fileName = dn,
                            isFolder = destFile.isDirectory,
                            destSizeBytes = if (destFile.isFile) destFile.length() else 0L,
                            applyToAllRef = booleanArrayOf(false)
                        )
                        when (action) {
                            TransferConflictHelper.ConflictAction.OVERWRITE -> {
                                DownloadTransferManager.startTorrentDownload(this@DownloadManagerActivity, magnetUri, targetDir)
                                dialog.dismiss()
                            }
                            TransferConflictHelper.ConflictAction.KEEP_BOTH -> {
                                val unique = TransferConflictHelper.uniqueLocalFile(targetDir, dn, this@DownloadManagerActivity)
                                unique.mkdirs()
                                DownloadTransferManager.startTorrentDownload(this@DownloadManagerActivity, magnetUri, unique)
                                dialog.dismiss()
                            }
                            TransferConflictHelper.ConflictAction.SKIP,
                            TransferConflictHelper.ConflictAction.CANCEL -> {
                                // User cancelled
                            }
                        }
                    }
                } else {
                    DownloadTransferManager.startTorrentDownload(this, magnetUri, targetDir)
                    dialog.dismiss()
                }
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
}
