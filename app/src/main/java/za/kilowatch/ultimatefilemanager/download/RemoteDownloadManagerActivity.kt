package za.kilowatch.ultimatefilemanager.download

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
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.libtorrent4j.TorrentInfo
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.network.NetworkFile
import za.kilowatch.ultimatefilemanager.network.NetworkShare
import za.kilowatch.ultimatefilemanager.network.ShareType
import za.kilowatch.ultimatefilemanager.network.TvShareClient
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import za.kilowatch.ultimatefilemanager.storage.FileBrowserActivity
import za.kilowatch.ultimatefilemanager.storage.StorageBrowserActivity
import java.io.File

/**
 * Mobile-side activity to manage Download Manager on a paired Android TV over LAN HTTPS.
 * Allows monitoring live transfer progress, pausing/resuming/canceling downloads,
 * changing the remote TV download directory, and dispatching direct URLs, magnet links,
 * and .torrent files (with selective sub-file inclusion).
 */
class RemoteDownloadManagerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PAIRED_DEVICE_ID = "extra_paired_device_id"
        const val EXTRA_SHARE_HOST = "extra_share_host"
        const val EXTRA_SHARE_PORT = "extra_share_port"
        const val EXTRA_DEVICE_NAME = "extra_device_name"
    }

    private lateinit var share: NetworkShare
    private var pairedDeviceId: String = ""
    private var deviceName: String = "TV"

    private lateinit var txtSubtitle: TextView
    private lateinit var txtCurrentLocation: TextView
    private lateinit var storageSpaceProgress: ProgressBar
    private lateinit var txtStorageSpaceInfo: TextView
    private lateinit var layoutReconnecting: View
    private lateinit var txtReconnectingMessage: TextView
    private lateinit var recyclerDownloads: RecyclerView
    private lateinit var layoutEmptyState: View

    private lateinit var adapter: RemoteTransferAdapter
    private var pollingJob: Job? = null
    private var currentTvDownloadDir: String = ""
    private var currentTransfers: List<TvShareClient.RemoteTransferItem> = emptyList()
    private var activeRemoteDialogSaveDir: String = ""

    private val torrentPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val pickedPath = result.data?.getStringExtra(FileBrowserActivity.RESULT_SELECTED_LOCAL_PATH)
                ?: result.data?.getStringExtra(FileBrowserActivity.RESULT_SELECTED_PATH)
            if (!pickedPath.isNullOrBlank()) {
                val file = java.io.File(pickedPath)
                if (file.exists()) {
                    handlePickedTorrentFile(file)
                }
            }
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_remote_download_manager)

        val root = findViewById<View>(R.id.main)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(sysBars.left, sysBars.top, sysBars.right, sysBars.bottom)
            insets
        }

        pairedDeviceId = intent.getStringExtra(EXTRA_PAIRED_DEVICE_ID) ?: ""
        val host = intent.getStringExtra(EXTRA_SHARE_HOST) ?: ""
        val port = intent.getIntExtra(EXTRA_SHARE_PORT, 8443)
        deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME) ?: getString(R.string.paired_tvs)

        share = NetworkShare(
            id = "tv_$host",
            name = deviceName,
            type = ShareType.TV,
            host = host,
            port = port,
            readOnly = false
        )

        initViews()
        setupListeners()
        startPollingLoop()
    }

    private fun initViews() {
        findViewById<TextView>(R.id.txtTitle).text = getString(R.string.download_manager_title)
        txtSubtitle = findViewById(R.id.txtSubtitle)
        txtSubtitle.text = "$deviceName • ${getString(R.string.reconnecting_to_tv)}"

        txtCurrentLocation = findViewById(R.id.txtCurrentLocation)
        storageSpaceProgress = findViewById(R.id.storageSpaceProgress)
        txtStorageSpaceInfo = findViewById(R.id.txtStorageSpaceInfo)
        layoutReconnecting = findViewById(R.id.layoutReconnecting)
        txtReconnectingMessage = findViewById(R.id.txtReconnectingMessage)
        layoutEmptyState = findViewById(R.id.layoutEmptyState)

        recyclerDownloads = findViewById(R.id.recyclerDownloads)
        recyclerDownloads.layoutManager = LinearLayoutManager(this)
        adapter = RemoteTransferAdapter(
            onPauseResume = { item -> handlePauseResume(item) },
            onCancelDelete = { item -> showDeleteTransferConfirmationDialog(item) }
        )
        recyclerDownloads.adapter = adapter
    }

    private fun setupListeners() {
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        findViewById<View>(R.id.btnRefresh).setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) { fetchStatus() }
        }

        findViewById<View>(R.id.btnClearCompleted).setOnClickListener {
            clearCompletedTransfers()
        }

        findViewById<View>(R.id.btnRetryConnection).setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) { fetchStatus() }
        }

        findViewById<View>(R.id.btnChangeLocation).setOnClickListener {
            showTvFolderPickerDialog(currentTvDownloadDir)
        }

        findViewById<View>(R.id.cardAddDirect).setOnClickListener {
            showDirectDownloadDialog()
        }

        findViewById<View>(R.id.cardAddMagnet).setOnClickListener {
            showMagnetDownloadDialog()
        }

        findViewById<View>(R.id.cardAddTorrent).setOnClickListener {
            val intent = Intent(this, StorageBrowserActivity::class.java).apply {
                putExtra(FileBrowserActivity.EXTRA_PICKER_MODE, true)
                putExtra(FileBrowserActivity.EXTRA_PICKER_EXTENSIONS, "torrent")
            }
            torrentPickerLauncher.launch(intent)
        }
    }

    private fun startPollingLoop() {
        pollingJob?.cancel()
        pollingJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (isActive) {
                    fetchStatus()
                    delay(1500)
                }
            }
        }
    }

    private suspend fun fetchStatus() {
        val status = withContext(Dispatchers.IO) {
            TvShareClient.getDownloadManagerStatus(share)
        }

        withContext(Dispatchers.Main) {
            if (status != null) {
                layoutReconnecting.visibility = View.GONE
                txtSubtitle.text = "$deviceName • ${getString(R.string.remote_status_running)}"

                currentTvDownloadDir = status.settings.downloadDir
                txtCurrentLocation.text = currentTvDownloadDir

                val free = status.settings.freeBytes
                val total = status.settings.totalBytes
                if (total > 0L) {
                    val used = (total - free).coerceAtLeast(0L)
                    val progress = ((used.toDouble() / total.toDouble()) * 100).toInt()
                    storageSpaceProgress.progress = progress
                    txtStorageSpaceInfo.text = getString(
                        R.string.tv_download_free_space,
                        formatBytes(free),
                        formatBytes(total)
                    )
                } else {
                    txtStorageSpaceInfo.text = currentTvDownloadDir
                }

                currentTransfers = status.transfers
                adapter.submitList(status.transfers)

                if (status.transfers.isEmpty()) {
                    layoutEmptyState.visibility = View.VISIBLE
                    recyclerDownloads.visibility = View.GONE
                } else {
                    layoutEmptyState.visibility = View.GONE
                    recyclerDownloads.visibility = View.VISIBLE
                }
            } else {
                layoutReconnecting.visibility = View.VISIBLE
                txtSubtitle.text = "$deviceName • ${getString(R.string.reconnecting_to_tv)}"
            }
        }
    }

    private fun handlePauseResume(item: TvShareClient.RemoteTransferItem) {
        val nextAction = if (item.state == "PAUSED") "resume" else "pause"
        lifecycleScope.launch(Dispatchers.IO) {
            val success = TvShareClient.executeTransferAction(share, item.id, nextAction)
            withContext(Dispatchers.Main) {
                if (!success) {
                    Toast.makeText(this@RemoteDownloadManagerActivity, getString(R.string.remote_action_failed, nextAction), Toast.LENGTH_SHORT).show()
                }
                fetchStatus()
            }
        }
    }

    private fun showDeleteTransferConfirmationDialog(item: TvShareClient.RemoteTransferItem) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_transfer_delete_confirm, null, false)
        val btnKeepFiles = dialogView.findViewById<MaterialButton>(R.id.btnDeleteKeepFiles)
        val btnRemoveFiles = dialogView.findViewById<MaterialButton>(R.id.btnDeleteRemoveFiles)
        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnDeleteCancel)

        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        btnKeepFiles?.setOnClickListener {
            dialog.dismiss()
            executeDelete(item.id, deleteFiles = false)
        }

        btnRemoveFiles?.setOnClickListener {
            dialog.dismiss()
            executeDelete(item.id, deleteFiles = true)
        }

        btnCancel?.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun executeDelete(id: String, deleteFiles: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            val success = TvShareClient.executeTransferAction(share, id, "delete", deleteFiles = deleteFiles)
            withContext(Dispatchers.Main) {
                if (!success) {
                    Toast.makeText(this@RemoteDownloadManagerActivity, getString(R.string.remote_action_failed, "delete"), Toast.LENGTH_SHORT).show()
                }
                fetchStatus()
            }
        }
    }

    private fun clearCompletedTransfers() {
        val completed = currentTransfers.filter { it.state == "COMPLETED" }
        if (completed.isEmpty()) {
            Toast.makeText(this, getString(R.string.download_no_transfers), Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            for (item in completed) {
                TvShareClient.executeTransferAction(share, item.id, "delete", deleteFiles = false)
            }
            withContext(Dispatchers.Main) {
                fetchStatus()
            }
        }
    }

    private fun showDirectDownloadDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_direct_download, null, false)
        val editUrl = dialogView.findViewById<EditText>(R.id.editDownloadUrl)
        val btnPaste = dialogView.findViewById<View>(R.id.btnPasteClipboard)
        val txtLocation = dialogView.findViewById<TextView>(R.id.txtSaveLocation)
        val btnChangeLoc = dialogView.findViewById<View>(R.id.btnChangeLocation)
        val cardLocation = dialogView.findViewById<View>(R.id.cardLocation)
        val btnDownload = dialogView.findViewById<MaterialButton>(R.id.btnStartDownload)
        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnCancel)

        var saveDir = currentTvDownloadDir.ifBlank { "/storage/emulated/0/Download" }
        txtLocation?.text = saveDir

        val onLocationClick = View.OnClickListener {
            showTvFolderPickerDialog(saveDir) { pickedDir ->
                saveDir = pickedDir
                txtLocation?.text = pickedDir
            }
        }
        btnChangeLoc?.setOnClickListener(onLocationClick)
        cardLocation?.setOnClickListener(onLocationClick)

        btnPaste?.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clipText = clipboard?.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
            if (!clipText.isNullOrBlank()) {
                editUrl?.setText(clipText)
                editUrl?.setSelection(clipText.length)
            }
        }

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clipText = clipboard?.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
        if (!clipText.isNullOrBlank() && (clipText.startsWith("http://") || clipText.startsWith("https://"))) {
            editUrl?.setText(clipText)
            editUrl?.setSelection(clipText.length)
        }

        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        btnDownload?.setOnClickListener {
            val url = editUrl?.text?.toString()?.trim() ?: ""
            if (url.isBlank() || (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true))) {
                Toast.makeText(this, getString(R.string.download_enter_url_hint), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            dialog.dismiss()
            dispatchDirectDownload(url, saveDir)
        }

        btnCancel?.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun dispatchDirectDownload(url: String, saveDir: String = currentTvDownloadDir) {
        lifecycleScope.launch(Dispatchers.IO) {
            val success = TvShareClient.addDirectDownload(share, url, filename = null, saveDir = saveDir.ifBlank { currentTvDownloadDir })
            withContext(Dispatchers.Main) {
                if (success) {
                    showSnackbar(getString(R.string.remote_download_dispatched))
                    fetchStatus()
                } else {
                    Toast.makeText(this@RemoteDownloadManagerActivity, getString(R.string.remote_download_failed, "Network error"), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showMagnetDownloadDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_torrent_download, null, false)
        dialogView.findViewById<View>(R.id.cardSelectedTorrent)?.visibility = View.GONE
        dialogView.findViewById<View>(R.id.btnBrowseTorrentFile)?.visibility = View.GONE

        val editMagnet = dialogView.findViewById<EditText>(R.id.editMagnetUrl)
        val btnPaste = dialogView.findViewById<View>(R.id.btnPasteMagnet)
        val txtLocation = dialogView.findViewById<TextView>(R.id.txtSaveLocation)
        val btnChangeLoc = dialogView.findViewById<View>(R.id.btnChangeLocation)
        val cardLocation = dialogView.findViewById<View>(R.id.cardLocation)
        val btnDownload = dialogView.findViewById<MaterialButton>(R.id.btnStartDownload)
        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnCancel)

        var saveDir = currentTvDownloadDir.ifBlank { "/storage/emulated/0/Download" }
        txtLocation?.text = saveDir

        val onLocationClick = View.OnClickListener {
            showTvFolderPickerDialog(saveDir) { pickedDir ->
                saveDir = pickedDir
                txtLocation?.text = pickedDir
            }
        }
        btnChangeLoc?.setOnClickListener(onLocationClick)
        cardLocation?.setOnClickListener(onLocationClick)

        btnPaste?.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clipText = clipboard?.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
            if (!clipText.isNullOrBlank()) {
                editMagnet?.setText(clipText)
                editMagnet?.setSelection(clipText.length)
            }
        }

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clipText = clipboard?.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
        if (!clipText.isNullOrBlank() && clipText.startsWith("magnet:", ignoreCase = true)) {
            editMagnet?.setText(clipText)
            editMagnet?.setSelection(clipText.length)
        }

        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        btnDownload?.setOnClickListener {
            val magnet = editMagnet?.text?.toString()?.trim() ?: ""
            if (magnet.isBlank() || !magnet.startsWith("magnet:", ignoreCase = true)) {
                Toast.makeText(this, getString(R.string.download_enter_magnet_hint), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startMagnetResolutionFlow(magnet, dialog, saveDir)
        }

        btnCancel?.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun startMagnetResolutionFlow(magnetUri: String, parentDialogToDismiss: Dialog?, saveDir: String) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_torrent_fetch_metadata, null, false)
        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnCancelFetch)

        val progressDialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        progressDialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        btnCancel?.setOnClickListener { progressDialog.dismiss() }
        progressDialog.show()

        var isResolved = false
        var isCancelled = false

        progressDialog.setOnCancelListener { isCancelled = true }
        progressDialog.setOnDismissListener { isCancelled = true }

        val timeoutHandler = Handler(Looper.getMainLooper())
        val timeoutRunnable = Runnable {
            if (isFinishing || isDestroyed) return@Runnable
            if (!isResolved && !isCancelled && progressDialog.isShowing) {
                progressDialog.dismiss()
                showMagnetTimeoutDialog(magnetUri, parentDialogToDismiss, saveDir)
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
                        val bytes = try { cachedTorrentFile.readBytes() } catch (_: Exception) { ByteArray(0) }
                        showTorrentFileSelectionDialog(
                            torrentInfo = info,
                            torrentFile = cachedTorrentFile,
                            torrentBytes = bytes,
                            initialSaveDir = saveDir
                        )
                    } else {
                        Toast.makeText(this, "Failed to parse torrent metadata", Toast.LENGTH_SHORT).show()
                        dispatchMagnetDownload(magnetUri, saveDir)
                    }
                } else {
                    showMagnetTimeoutDialog(magnetUri, parentDialogToDismiss, saveDir)
                }
            }
        }
    }

    private fun showMagnetTimeoutDialog(magnetUri: String, parentDialogToDismiss: Dialog?, saveDir: String) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_torrent_metadata_timeout, null, false)
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
            dispatchMagnetDownload(magnetUri, saveDir)
        }

        btnKeepWaiting?.setOnClickListener {
            timeoutDialog.dismiss()
            startMagnetResolutionFlow(magnetUri, parentDialogToDismiss, saveDir)
        }

        btnCancel?.setOnClickListener {
            timeoutDialog.dismiss()
        }

        timeoutDialog.show()
    }

    private fun dispatchMagnetDownload(magnetUri: String, saveDir: String = currentTvDownloadDir) {
        lifecycleScope.launch(Dispatchers.IO) {
            val success = TvShareClient.addMagnetDownload(share, magnetUri, saveDir = saveDir.ifBlank { currentTvDownloadDir })
            withContext(Dispatchers.Main) {
                if (success) {
                    showSnackbar(getString(R.string.remote_download_dispatched))
                    fetchStatus()
                } else {
                    Toast.makeText(this@RemoteDownloadManagerActivity, getString(R.string.remote_download_failed, "Network error"), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun handlePickedTorrentFile(file: File) {
        lifecycleScope.launch(Dispatchers.IO) {
            val bytes = try {
                file.readBytes()
            } catch (e: Exception) {
                null
            }

            if (bytes == null || bytes.isEmpty()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@RemoteDownloadManagerActivity, getString(R.string.remote_download_failed, "Cannot read file"), Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            val torrentInfo = runCatching { TorrentInfo(file) }.getOrNull()

            withContext(Dispatchers.Main) {
                if (torrentInfo != null) {
                    showTorrentFileSelectionDialog(torrentInfo, file, bytes, initialSaveDir = currentTvDownloadDir)
                } else {
                    dispatchTorrentDownload(bytes, selectedIndices = null)
                }
            }
        }
    }

    private fun handlePickedTorrentUri(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            val bytes = try {
                contentResolver.openInputStream(uri)?.use { it.readBytes() }
            } catch (e: Exception) {
                null
            }

            if (bytes == null || bytes.isEmpty()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@RemoteDownloadManagerActivity, getString(R.string.remote_download_failed, "Cannot read file"), Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            val tempFile = File(cacheDir, "picked_${System.currentTimeMillis()}.torrent")
            tempFile.writeBytes(bytes)
            handlePickedTorrentFile(tempFile)
        }
    }

    private fun showTorrentFileSelectionDialog(
        torrentInfo: TorrentInfo,
        torrentFile: File,
        torrentBytes: ByteArray,
        initialSaveDir: String = currentTvDownloadDir
    ) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_torrent_file_selection, null, false)
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

        var activeSaveDir = initialSaveDir.ifBlank { currentTvDownloadDir }
        txtTorrentTitle?.text = torrentInfo.name().ifBlank { torrentFile.nameWithoutExtension }
        txtSaveLocation?.text = activeSaveDir

        val onLocationClick = View.OnClickListener {
            showTvFolderPickerDialog(activeSaveDir) { pickedDir ->
                activeSaveDir = pickedDir
                txtSaveLocation?.text = pickedDir
            }
        }
        btnChangeLocation?.setOnClickListener(onLocationClick)
        cardLocation?.setOnClickListener(onLocationClick)

        val totalFiles = rootNode.getLeafFileCount()
        val totalBytes = torrentInfo.totalSize()

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
            isTv = false,
            onSelectionChanged = { updateMetricsDisplay() }
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
                    chip?.setTextColor(Color.WHITE)
                    chip?.setBackgroundResource(R.drawable.bg_btn_icon_frosted)
                } else {
                    chip?.setTextColor(getColor(R.color.mobile_text_secondary))
                    chip?.setBackgroundResource(R.drawable.bg_btn_icon_frosted)
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

        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        btnSelectAll?.setOnClickListener {
            selectionAdapter.selectAll()
            updateMetricsDisplay()
        }

        btnDeselectAll?.setOnClickListener {
            selectionAdapter.deselectAll()
            updateMetricsDisplay()
        }

        btnStart?.setOnClickListener {
            val selectedIndices = rootNode.getSelectedFileIndices()
            dialog.dismiss()
            dispatchTorrentDownload(torrentBytes, selectedIndices, saveDirOverride = activeSaveDir)
        }

        btnCancel?.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun dispatchTorrentDownload(torrentBytes: ByteArray, selectedIndices: Set<Int>?, saveDirOverride: String? = null) {
        lifecycleScope.launch(Dispatchers.IO) {
            val targetDir = (saveDirOverride ?: currentTvDownloadDir).ifBlank { currentTvDownloadDir }
            val success = TvShareClient.addTorrentDownload(
                share = share,
                torrentBytes = torrentBytes,
                saveDir = targetDir,
                selectedIndices = selectedIndices
            )
            withContext(Dispatchers.Main) {
                if (success) {
                    showSnackbar(getString(R.string.remote_download_dispatched))
                    fetchStatus()
                } else {
                    Toast.makeText(this@RemoteDownloadManagerActivity, getString(R.string.remote_download_failed, "Upload failed"), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showTvFolderPickerDialog(initialPath: String, onSelected: ((String) -> Unit)? = null) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_remote_tv_folder_picker, null, false)
        val btnUp = dialogView.findViewById<ImageView>(R.id.btnFolderUp)
        val txtCurrentPickerPath = dialogView.findViewById<TextView>(R.id.txtCurrentPickerPath)
        val pickerProgress = dialogView.findViewById<ProgressBar>(R.id.pickerProgress)
        val txtPickerEmpty = dialogView.findViewById<TextView>(R.id.txtPickerEmpty)
        val recyclerFolders = dialogView.findViewById<RecyclerView>(R.id.recyclerFolders)
        val btnSelect = dialogView.findViewById<MaterialButton>(R.id.btnPickerSelect)
        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnPickerCancel)
        val btnCreateNewFolder = dialogView.findViewById<ImageView>(R.id.btnCreateNewFolder)

        var activePath = initialPath.ifBlank { "/storage/emulated/0" }

        val folderAdapter = RemoteFolderAdapter { pickedFolder ->
            activePath = pickedFolder.path
            loadPickerDirectory(activePath, dialogView, btnUp, txtCurrentPickerPath, pickerProgress, txtPickerEmpty, recyclerFolders)
        }

        recyclerFolders.layoutManager = LinearLayoutManager(this)
        recyclerFolders.adapter = folderAdapter

        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        btnUp.setOnClickListener {
            val parent = File(activePath).parent
            activePath = when {
                parent.isNullOrBlank() || parent == "/" || parent == "/storage" || parent == "/storage/emulated" -> ""
                else -> parent
            }
            loadPickerDirectory(activePath, dialogView, btnUp, txtCurrentPickerPath, pickerProgress, txtPickerEmpty, recyclerFolders)
        }

        btnCreateNewFolder.setOnClickListener {
            showCreateDirectoryDialog(activePath) { newDirName ->
                val targetNewDirPath = if (activePath.isEmpty()) "/storage/emulated/0/$newDirName" else "$activePath/$newDirName"
                lifecycleScope.launch(Dispatchers.IO) {
                    TvShareClient.mkdir(share, targetNewDirPath)
                    withContext(Dispatchers.Main) {
                        loadPickerDirectory(activePath, dialogView, btnUp, txtCurrentPickerPath, pickerProgress, txtPickerEmpty, recyclerFolders)
                    }
                }
            }
        }

        btnSelect.setOnClickListener {
            if (activePath.isNotBlank()) {
                dialog.dismiss()
                if (onSelected != null) {
                    onSelected(activePath)
                } else {
                    updateTvDownloadLocation(activePath)
                }
            } else {
                Toast.makeText(this, getString(R.string.tv_folder_picker_title), Toast.LENGTH_SHORT).show()
            }
        }

        btnCancel.setOnClickListener { dialog.dismiss() }

        loadPickerDirectory(activePath, dialogView, btnUp, txtCurrentPickerPath, pickerProgress, txtPickerEmpty, recyclerFolders)
        dialog.show()
    }

    private fun loadPickerDirectory(
        path: String,
        dialogView: View,
        btnUp: ImageView,
        txtCurrentPickerPath: TextView,
        pickerProgress: ProgressBar,
        txtPickerEmpty: TextView,
        recyclerFolders: RecyclerView
    ) {
        txtCurrentPickerPath.text = path.ifBlank { "/" }
        btnUp.visibility = if (path.isNotBlank() && path != "/") View.VISIBLE else View.GONE
        pickerProgress.visibility = View.VISIBLE
        txtPickerEmpty.visibility = View.GONE
        recyclerFolders.visibility = View.GONE

        lifecycleScope.launch(Dispatchers.IO) {
            val items = try {
                TvShareClient.listFiles(share, path).filter { it.isDirectory }
            } catch (e: Exception) {
                emptyList()
            }

            withContext(Dispatchers.Main) {
                pickerProgress.visibility = View.GONE
                val adapter = recyclerFolders.adapter as? RemoteFolderAdapter
                adapter?.submitList(items)

                if (items.isEmpty()) {
                    txtPickerEmpty.visibility = View.VISIBLE
                    recyclerFolders.visibility = View.GONE
                } else {
                    txtPickerEmpty.visibility = View.GONE
                    recyclerFolders.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun showCreateDirectoryDialog(parentPath: String, onCreated: (String) -> Unit) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_create_folder, null, false)
        val edtFolderName = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.edtFolderName)
        val btnCreate = dialogView.findViewById<MaterialButton>(R.id.btnCreate)
        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnCancel)

        edtFolderName?.setText(getString(R.string.new_menu_new_folder))
        edtFolderName?.selectAll()

        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        btnCancel?.setOnClickListener { dialog.dismiss() }

        btnCreate?.setOnClickListener {
            val name = edtFolderName?.text?.toString()?.trim().orEmpty()
            if (name.isBlank()) {
                edtFolderName?.error = getString(R.string.new_folder_empty)
                return@setOnClickListener
            }
            dialog.dismiss()
            onCreated(name)
        }

        dialog.show()
        edtFolderName?.requestFocus()
    }

    private fun updateTvDownloadLocation(newPath: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val success = TvShareClient.updateDownloadSettings(share, downloadDir = newPath)
            withContext(Dispatchers.Main) {
                if (success) {
                    showSnackbar(getString(R.string.remote_location_updated))
                    fetchStatus()
                } else {
                    Toast.makeText(this@RemoteDownloadManagerActivity, getString(R.string.remote_location_update_failed, "Permission denied or disk unwritable"), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showSnackbar(message: String) {
        Snackbar.make(findViewById(R.id.main), message, Snackbar.LENGTH_SHORT).show()
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        val unit = units.getOrNull(digitGroups) ?: "GB"
        val value = bytes / Math.pow(1024.0, digitGroups.toDouble())
        return String.format(java.util.Locale.US, "%.1f %s", value, unit)
    }

    // ── Adapters ─────────────────────────────────────────────────────────────

    private class RemoteTransferAdapter(
        private val onPauseResume: (TvShareClient.RemoteTransferItem) -> Unit,
        private val onCancelDelete: (TvShareClient.RemoteTransferItem) -> Unit
    ) : ListAdapter<TvShareClient.RemoteTransferItem, RemoteTransferViewHolder>(DiffCallback) {

        object DiffCallback : DiffUtil.ItemCallback<TvShareClient.RemoteTransferItem>() {
            override fun areItemsTheSame(oldItem: TvShareClient.RemoteTransferItem, newItem: TvShareClient.RemoteTransferItem): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: TvShareClient.RemoteTransferItem, newItem: TvShareClient.RemoteTransferItem): Boolean =
                oldItem == newItem
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RemoteTransferViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_remote_download_transfer, parent, false)
            return RemoteTransferViewHolder(view, onPauseResume, onCancelDelete)
        }

        override fun onBindViewHolder(holder: RemoteTransferViewHolder, position: Int) {
            holder.bind(getItem(position))
        }
    }

    private class RemoteTransferViewHolder(
        itemView: View,
        private val onPauseResume: (TvShareClient.RemoteTransferItem) -> Unit,
        private val onCancelDelete: (TvShareClient.RemoteTransferItem) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        private val txtTitle: TextView = itemView.findViewById(R.id.txtTransferTitle)
        private val txtState: TextView = itemView.findViewById(R.id.txtTransferState)
        private val imgIcon: ImageView = itemView.findViewById(R.id.imgTransferIcon)
        private val btnPauseResume: ImageView = itemView.findViewById(R.id.btnTransferPauseResume)
        private val btnCancel: ImageView = itemView.findViewById(R.id.btnTransferCancel)
        private val progressTransfer: ProgressBar = itemView.findViewById(R.id.progressTransfer)
        private val txtPercent: TextView = itemView.findViewById(R.id.txtTransferPercent)
        private val txtSizeSpeed: TextView = itemView.findViewById(R.id.txtTransferSizeSpeed)
        private val txtEtaPeers: TextView = itemView.findViewById(R.id.txtTransferEtaPeers)

        fun bind(item: TvShareClient.RemoteTransferItem) {
            txtTitle.text = item.title
            txtPercent.text = "${item.progressPercent}%"
            progressTransfer.progress = item.progressPercent

            val isPaused = item.state == "PAUSED"
            val isCompleted = item.state == "COMPLETED"
            val isError = item.state == "ERROR"

            txtState.text = item.state
            txtState.setTextColor(
                when {
                    isCompleted -> Color.parseColor("#00E676")
                    isPaused -> Color.parseColor("#FFA726")
                    isError -> Color.parseColor("#EF5350")
                    else -> Color.parseColor("#42A5F5")
                }
            )

            btnPauseResume.visibility = if (isCompleted) View.GONE else View.VISIBLE
            btnPauseResume.setImageResource(if (isPaused) R.drawable.ic_play else R.drawable.ic_pause)

            btnPauseResume.setOnClickListener { onPauseResume(item) }
            btnCancel.setOnClickListener { onCancelDelete(item) }

            val speedFormatted = if (item.speedBytesPerSec > 0) "${formatSpeed(item.speedBytesPerSec)}" else ""
            val sizeFormatted = "${formatBytes(item.downloadedBytes)} / ${formatBytes(item.totalBytes)}"
            txtSizeSpeed.text = if (speedFormatted.isNotBlank()) "$sizeFormatted • $speedFormatted" else sizeFormatted

            val etaText = if (item.etaSeconds > 0) "ETA: ${formatEta(item.etaSeconds)}" else ""
            val peerText = if (item.type != "DIRECT_HTTP" && (item.connectedPeers > 0 || item.totalPeers > 0)) {
                "Peers: ${item.connectedPeers} (${item.totalPeers}) • Seeds: ${item.connectedSeeds}"
            } else ""

            val line2 = listOf(etaText, peerText, item.errorMessage).filter { !it.isNullOrBlank() }.joinToString(" • ")
            txtEtaPeers.text = line2
            txtEtaPeers.visibility = if (line2.isNotBlank()) View.VISIBLE else View.GONE
        }

        private fun formatBytes(bytes: Long): String {
            if (bytes <= 0L) return "0 B"
            val units = arrayOf("B", "KB", "MB", "GB", "TB")
            val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
            val unit = units.getOrNull(digitGroups) ?: "GB"
            val value = bytes / Math.pow(1024.0, digitGroups.toDouble())
            return String.format(java.util.Locale.US, "%.1f %s", value, unit)
        }

        private fun formatSpeed(bytesPerSec: Long): String = "${formatBytes(bytesPerSec)}/s"

        private fun formatEta(seconds: Long): String {
            val m = (seconds / 60) % 60
            val h = seconds / 3600
            val s = seconds % 60
            return if (h > 0) String.format("%dh %02dm", h, m) else String.format("%dm %02ds", m, s)
        }
    }

    private class RemoteFolderAdapter(
        private val onClick: (NetworkFile) -> Unit
    ) : ListAdapter<NetworkFile, RemoteFolderViewHolder>(FolderDiff) {

        object FolderDiff : DiffUtil.ItemCallback<NetworkFile>() {
            override fun areItemsTheSame(oldItem: NetworkFile, newItem: NetworkFile): Boolean = oldItem.path == newItem.path
            override fun areContentsTheSame(oldItem: NetworkFile, newItem: NetworkFile): Boolean = oldItem == newItem
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RemoteFolderViewHolder {
            val density = parent.context.resources.displayMetrics.density
            val dp24 = (24 * density).toInt()
            val dp16 = (16 * density).toInt()
            val dp12 = (12 * density).toInt()
            val dp8 = (8 * density).toInt()

            val row = LinearLayout(parent.context).apply {
                layoutParams = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = (4 * density).toInt()
                }
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp16, dp12, dp16, dp12)
                setBackgroundResource(R.drawable.bg_btn_icon_frosted)
            }

            val icon = ImageView(parent.context).apply {
                layoutParams = LinearLayout.LayoutParams(dp24, dp24)
                setImageResource(R.drawable.ic_folder)
                imageTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#42A5F5"))
            }

            val tv = TextView(parent.context).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f).apply {
                    marginStart = dp12
                    marginEnd = dp8
                }
                textSize = 15f
                setTextColor(context.getColor(R.color.mobile_text_primary))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }

            val chevron = ImageView(parent.context).apply {
                layoutParams = LinearLayout.LayoutParams((18 * density).toInt(), (18 * density).toInt())
                setImageResource(R.drawable.ic_arrow_back)
                rotation = 180f
                imageTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#80FFFFFF"))
            }

            row.addView(icon)
            row.addView(tv)
            row.addView(chevron)

            return RemoteFolderViewHolder(row, tv, onClick)
        }

        override fun onBindViewHolder(holder: RemoteFolderViewHolder, position: Int) {
            holder.bind(getItem(position))
        }
    }

    private class RemoteFolderViewHolder(
        itemView: View,
        private val txtName: TextView,
        private val onClick: (NetworkFile) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        fun bind(file: NetworkFile) {
            txtName.text = file.name
            itemView.setOnClickListener { onClick(file) }
        }
    }
}
