package za.kilowatch.ultimatefilemanager.recycle

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import java.io.File

class RecycleBinActivity : AppCompatActivity() {

    private lateinit var recyclerItems: RecyclerView
    private lateinit var layoutEmpty: View
    private lateinit var layoutSelectionBar: View
    private lateinit var progressBar: ProgressBar
    private lateinit var txtTitle: TextView
    private lateinit var txtTrashSize: TextView
    private lateinit var btnBack: View
    private lateinit var btnClearAll: View
    private lateinit var btnSettings: View
    private lateinit var btnRestore: View
    private lateinit var btnDeletePerm: View

    private var isTv = false
    private lateinit var adapter: RecycleBinAdapter
    private var allEntries: List<RecycleBinEntity> = emptyList()

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        za.kilowatch.ultimatefilemanager.settings.ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        isTv = DeviceUtils.isTvDevice(this)
        setContentView(if (isTv) R.layout.activity_recycle_bin_tv else R.layout.activity_recycle_bin)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        setupViews()
        observeData()
    }

    override fun onResume() {
        super.onResume()
        updateTrashSize()
    }

    private fun setupViews() {
        recyclerItems = findViewById(R.id.recyclerItems)
        layoutEmpty = findViewById(R.id.layoutEmpty)
        layoutSelectionBar = findViewById(R.id.layoutSelectionBar)
        progressBar = findViewById(R.id.progressBar)
        txtTitle = findViewById(R.id.txtTitle)
        txtTrashSize = findViewById(R.id.txtTrashSize)
        btnBack = findViewById(R.id.btnBack)
        btnClearAll = findViewById(R.id.btnClearAll)
        btnSettings = findViewById(R.id.btnSettings)
        btnRestore = findViewById(R.id.btnRestore)
        btnDeletePerm = findViewById(R.id.btnDeletePerm)

        btnBack.setOnClickListener { finish() }

        btnClearAll.setOnClickListener { showClearAllDialog() }

        btnSettings.setOnClickListener {
            startActivity(Intent(this, RecycleBinAutoDeleteActivity::class.java))
        }

        btnRestore.setOnClickListener { restoreSelected() }

        btnDeletePerm.setOnClickListener { deleteSelectedPermanently() }

        adapter = RecycleBinAdapter(
            isTv = isTv,
            onItemClick = { entity -> onItemClicked(entity) },
            onItemLongClick = { entity -> adapter.enterSelectionMode(entity.id) },
            onSelectionChanged = { updateSelectionBar() }
        )

        recyclerItems.layoutManager = LinearLayoutManager(this)
        recyclerItems.adapter = adapter
    }

    private fun observeData() {
        lifecycleScope.launch {
            RecycleBinManager.validateEntries()
            RecycleBinManager.getAllFlow().collectLatest { entries ->
                allEntries = entries
                adapter.pruneSelection(entries)
                adapter.submitList(entries)
                updateEmptyState(entries.isEmpty())
                updateTrashSize()
            }
        }
    }

    private fun updateEmptyState(isEmpty: Boolean) {
        layoutEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
        recyclerItems.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    private fun updateSelectionBar() {
        val count = adapter.getSelectedCount()
        if (count > 0) {
            layoutSelectionBar.visibility = View.VISIBLE
            btnRestore.isEnabled = true
            btnDeletePerm.isEnabled = true
        } else {
            layoutSelectionBar.visibility = View.GONE
        }
    }

    private fun updateTrashSize() {
        lifecycleScope.launch(Dispatchers.IO) {
            val entries = RecycleBinManager.getAllEntries()
            var totalSize = entries.sumOf { it.fileSize }
            var fileCount = entries.count { !it.isDirectory }
            var folderCount = entries.count { it.isDirectory }

            for (entry in entries.filter { it.isDirectory }) {
                if (entry.trashPath.startsWith("/")) {
                    val dir = File(entry.trashPath)
                    if (dir.isDirectory) {
                        val files = dir.listFiles() ?: emptyArray()
                        totalSize += files.sumOf { if (it.isFile) it.length() else 0L }
                        fileCount += files.count { it.isFile }
                        folderCount += files.count { it.isDirectory }
                    }
                } else {
                    // Network directory — list remote files
                    try {
                        val share = resolveShareForPreview(entry) ?: continue
                        val netFiles = when (share.type) {
                            za.kilowatch.ultimatefilemanager.network.ShareType.SMB -> za.kilowatch.ultimatefilemanager.network.SmbShareClient.listFiles(share, entry.trashPath)
                            za.kilowatch.ultimatefilemanager.network.ShareType.FTP -> za.kilowatch.ultimatefilemanager.network.FtpShareClient.listFiles(share, entry.trashPath)
                            za.kilowatch.ultimatefilemanager.network.ShareType.SFTP, za.kilowatch.ultimatefilemanager.network.ShareType.SCP -> za.kilowatch.ultimatefilemanager.network.SshShareClient.listFiles(share, entry.trashPath)
                            za.kilowatch.ultimatefilemanager.network.ShareType.NFS -> za.kilowatch.ultimatefilemanager.network.NfsShareClient.listFiles(share, entry.trashPath)
                            za.kilowatch.ultimatefilemanager.network.ShareType.TV -> za.kilowatch.ultimatefilemanager.network.TvShareClient.listFiles(share, entry.trashPath)
                            za.kilowatch.ultimatefilemanager.network.ShareType.ONEDRIVE -> za.kilowatch.ultimatefilemanager.network.OnedriveShareClient.listFiles(share, entry.trashPath)
                            za.kilowatch.ultimatefilemanager.network.ShareType.GOOGLE_DRIVE -> za.kilowatch.ultimatefilemanager.network.GoogleDriveShareClient.listFiles(share, entry.trashPath)
                            za.kilowatch.ultimatefilemanager.network.ShareType.DROPBOX -> za.kilowatch.ultimatefilemanager.network.DropboxShareClient.listFiles(share, entry.trashPath)
                            za.kilowatch.ultimatefilemanager.network.ShareType.AWS_S3, za.kilowatch.ultimatefilemanager.network.ShareType.IDRIVE_E2 -> za.kilowatch.ultimatefilemanager.network.S3ShareClient.listFiles(share, entry.trashPath)
                            za.kilowatch.ultimatefilemanager.network.ShareType.WEBDAV -> za.kilowatch.ultimatefilemanager.network.WebDavShareClient.listFiles(share, entry.trashPath)
                            za.kilowatch.ultimatefilemanager.network.ShareType.DLNA -> za.kilowatch.ultimatefilemanager.network.DlnaShareClient.listFiles(share, entry.trashPath)
                        }
                        fileCount += netFiles.count { !it.isDirectory }
                        folderCount += netFiles.count { it.isDirectory }
                        totalSize += netFiles.sumOf { it.size }
                    } catch (_: Exception) { /* offline or unreachable */ }
                }
            }

            withContext(Dispatchers.Main) {
                val autoDays = za.kilowatch.ultimatefilemanager.recycle.RecycleBinSettingsManager.getAutoDeleteDays(this@RecycleBinActivity)
                txtTrashSize.text = if (entries.isNotEmpty()) {
                    val sb = StringBuilder()
                    if (totalSize > 0) sb.append(android.text.format.Formatter.formatFileSize(this@RecycleBinActivity, totalSize)).append(" · ")
                    sb.append("$fileCount files")
                    if (folderCount > 0) sb.append(" · $folderCount folders")
                    if (autoDays > 0) {
                        val expiredCount = entries.count { it.dateDeleted + (autoDays * 86400000L) < System.currentTimeMillis() }
                        if (expiredCount > 0) sb.append(" · $expiredCount expiring")
                        sb.append(" · ").append(getString(R.string.recycle_bin_auto_delete_days, autoDays))
                    } else {
                        sb.append(" · ").append(getString(R.string.recycle_bin_auto_delete_disabled))
                    }
                    sb.toString()
                } else ""
                txtTrashSize.visibility = if (entries.isNotEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun onItemClicked(entity: RecycleBinEntity) {
        val isLocal = entity.trashPath.startsWith("/")
        if (isLocal) {
            val file = File(entity.trashPath)
            if (!file.exists()) {
                Snackbar.make(recyclerItems, R.string.recycle_bin_file_missing, Snackbar.LENGTH_SHORT).show()
                lifecycleScope.launch { RecycleBinManager.permanentDelete(entity) }
                return
            }
            FileViewerRouter.openFile(this, file)
        } else {
            // Network entries: download to temp and open
            lifecycleScope.launch {
                progressBar.visibility = View.VISIBLE
                try {
                    val share = resolveShareForPreview(entity)
                    if (share == null) {
                        Snackbar.make(recyclerItems, R.string.recycle_bin_share_offline, Snackbar.LENGTH_SHORT).show()
                        progressBar.visibility = View.GONE
                        return@launch
                    }
                    val tempFile = withContext(Dispatchers.IO) {
                        val dir = File(cacheDir, "ufm_preview")
                        dir.mkdirs()
                        val out = File(dir, entity.fileName)
                        val input = openRemoteStream(share, entity.trashPath)
                        input?.use { stream -> out.outputStream().use { o -> stream.copyTo(o) } }
                        if (out.exists()) out else null
                    }
                    progressBar.visibility = View.GONE
                    if (tempFile != null) {
                        FileViewerRouter.openFile(this@RecycleBinActivity, tempFile)
                    } else {
                        Snackbar.make(recyclerItems, R.string.share_receive_error, Snackbar.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    progressBar.visibility = View.GONE
                    Snackbar.make(recyclerItems, getString(R.string.share_receive_error) + ": ${e.message}", Snackbar.LENGTH_SHORT).show()
                }
            }
        }
    }

    private suspend fun resolveShareForPreview(entity: RecycleBinEntity): za.kilowatch.ultimatefilemanager.network.NetworkShare? {
        val fromRepo = za.kilowatch.ultimatefilemanager.network.NetworkShareRepository.getInstance(this).getById(entity.storageId)
        if (fromRepo != null) return fromRepo
        val fromOnline = za.kilowatch.ultimatefilemanager.network.OnlineStorageRepository.getInstance(this).getById(entity.storageId)
        if (fromOnline != null) {
            val providerType = when (fromOnline.provider) {
                za.kilowatch.ultimatefilemanager.network.OnlineStorageProvider.ONEDRIVE -> za.kilowatch.ultimatefilemanager.network.ShareType.ONEDRIVE
                za.kilowatch.ultimatefilemanager.network.OnlineStorageProvider.GOOGLE_DRIVE -> za.kilowatch.ultimatefilemanager.network.ShareType.GOOGLE_DRIVE
                za.kilowatch.ultimatefilemanager.network.OnlineStorageProvider.DROPBOX -> za.kilowatch.ultimatefilemanager.network.ShareType.DROPBOX
                za.kilowatch.ultimatefilemanager.network.OnlineStorageProvider.AWS_S3 -> za.kilowatch.ultimatefilemanager.network.ShareType.AWS_S3
                za.kilowatch.ultimatefilemanager.network.OnlineStorageProvider.IDRIVE_E2 -> za.kilowatch.ultimatefilemanager.network.ShareType.IDRIVE_E2
                za.kilowatch.ultimatefilemanager.network.OnlineStorageProvider.WEBDAV -> za.kilowatch.ultimatefilemanager.network.ShareType.WEBDAV
                za.kilowatch.ultimatefilemanager.network.OnlineStorageProvider.RCLONE -> za.kilowatch.ultimatefilemanager.network.ShareType.WEBDAV
            }
            return za.kilowatch.ultimatefilemanager.network.NetworkShare(
                id = fromOnline.id,
                name = fromOnline.displayName,
                type = providerType,
                host = if (fromOnline.isWebDavProvider) (fromOnline.webDavUrl ?: fromOnline.email) else (fromOnline.s3Endpoint ?: fromOnline.email),
                port = 0,
                username = if (fromOnline.isWebDavProvider) (fromOnline.webDavUsername ?: fromOnline.email) else (fromOnline.s3AccessKey ?: fromOnline.email),
                password = if (fromOnline.isWebDavProvider) (fromOnline.webDavPassword ?: "") else (fromOnline.s3SecretKey ?: ""),
                domain = fromOnline.s3Bucket ?: "",
                remotePath = fromOnline.s3Region ?: "/",
                readOnly = false
            )
        }
        val device = za.kilowatch.ultimatefilemanager.network.PairingManager.getInstance(this).getPairedDevice(entity.storageId)
        if (device != null) return za.kilowatch.ultimatefilemanager.network.NetworkShare(id = device.deviceId, name = device.name, type = za.kilowatch.ultimatefilemanager.network.ShareType.TV, host = device.lastIp, port = device.lastPort, readOnly = false)
        return null
    }

    private suspend fun openRemoteStream(share: za.kilowatch.ultimatefilemanager.network.NetworkShare, path: String): java.io.InputStream? {
        return try {
            when (share.type) {
                za.kilowatch.ultimatefilemanager.network.ShareType.SMB -> za.kilowatch.ultimatefilemanager.network.SmbShareClient.openInputStream(share, path)
                za.kilowatch.ultimatefilemanager.network.ShareType.FTP -> za.kilowatch.ultimatefilemanager.network.FtpShareClient.openInputStream(share, path)
                za.kilowatch.ultimatefilemanager.network.ShareType.SFTP, za.kilowatch.ultimatefilemanager.network.ShareType.SCP -> za.kilowatch.ultimatefilemanager.network.SshShareClient.openInputStream(share, path)
                za.kilowatch.ultimatefilemanager.network.ShareType.NFS -> za.kilowatch.ultimatefilemanager.network.NfsShareClient.openInputStream(share, path)
                za.kilowatch.ultimatefilemanager.network.ShareType.TV -> za.kilowatch.ultimatefilemanager.network.TvShareClient.openInputStream(share, path)
                za.kilowatch.ultimatefilemanager.network.ShareType.ONEDRIVE -> za.kilowatch.ultimatefilemanager.network.OnedriveShareClient.openInputStream(share, path).first
                za.kilowatch.ultimatefilemanager.network.ShareType.GOOGLE_DRIVE -> za.kilowatch.ultimatefilemanager.network.GoogleDriveShareClient.openInputStream(share, path).first
                za.kilowatch.ultimatefilemanager.network.ShareType.DROPBOX -> za.kilowatch.ultimatefilemanager.network.DropboxShareClient.openInputStream(share, path).first
                za.kilowatch.ultimatefilemanager.network.ShareType.AWS_S3, za.kilowatch.ultimatefilemanager.network.ShareType.IDRIVE_E2 -> za.kilowatch.ultimatefilemanager.network.S3ShareClient.openInputStream(share, path).first
                za.kilowatch.ultimatefilemanager.network.ShareType.WEBDAV -> za.kilowatch.ultimatefilemanager.network.WebDavShareClient.openInputStream(share, path).first
                za.kilowatch.ultimatefilemanager.network.ShareType.DLNA -> za.kilowatch.ultimatefilemanager.network.DlnaShareClient.openInputStream(share, path)
            }
        } catch (e: Exception) { null }
    }

    private fun showClearAllDialog() {
        val count = allEntries.size
        if (count == 0) return

        val layoutRes = if (isTv) R.layout.dialog_file_delete_confirm_tv else R.layout.dialog_file_delete_confirm
        val dialogView = android.view.LayoutInflater.from(this).inflate(layoutRes, null)

        val txtTitle = dialogView.findViewById<TextView>(R.id.txtTitle)
        val txtMessage = dialogView.findViewById<TextView>(R.id.txtDeleteMessage)
        val btnConfirm = dialogView.findViewById<View>(R.id.btnDeleteConfirm)
        val btnCancel = dialogView.findViewById<View>(R.id.btnCancel)

        txtTitle?.setText(R.string.recycle_bin_clear_title)
        txtMessage?.text = getString(R.string.recycle_bin_clear_confirm, count)

        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        btnConfirm?.setOnClickListener {
            dialog.dismiss()
            lifecycleScope.launch {
                progressBar.visibility = View.VISIBLE
                val deleted = withContext(Dispatchers.IO) { RecycleBinManager.emptyTrash() }
                progressBar.visibility = View.GONE
                Snackbar.make(recyclerItems, getString(R.string.recycle_bin_cleared, deleted), Snackbar.LENGTH_SHORT).show()
            }
        }

        btnCancel?.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
    }

    private fun restoreSelected() {
        val ids = adapter.getSelectedIds().toList()
        if (ids.isEmpty()) return
        lifecycleScope.launch {
            progressBar.visibility = View.VISIBLE
            // Check if any network shares are offline
            for (id in ids) {
                val entry = RecycleBinManager.getById(id) ?: continue
                val isOnline = RecycleBinManager.isShareOnline(entry)
                if (!isOnline) {
                    progressBar.visibility = View.GONE
                    Snackbar.make(recyclerItems, getString(R.string.recycle_bin_share_offline), Snackbar.LENGTH_LONG).show()
                    return@launch
                }
            }
            val restored = withContext(Dispatchers.IO) { RecycleBinManager.restoreByIds(ids) }
            progressBar.visibility = View.GONE
            adapter.exitSelectionMode()
            Snackbar.make(recyclerItems, getString(R.string.recycle_bin_restored_count, restored), Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun deleteSelectedPermanently() {
        val ids = adapter.getSelectedIds().toList()
        if (ids.isEmpty()) return
        lifecycleScope.launch {
            // Check if any network shares are offline
            for (id in ids) {
                val entry = RecycleBinManager.getById(id) ?: continue
                val isOnline = RecycleBinManager.isShareOnline(entry)
                if (!isOnline) {
                    Snackbar.make(recyclerItems, getString(R.string.recycle_bin_share_offline), Snackbar.LENGTH_LONG).show()
                    return@launch
                }
            }
        }

        val layoutRes = if (isTv) R.layout.dialog_file_delete_confirm_tv else R.layout.dialog_file_delete_confirm
        val dialogView = android.view.LayoutInflater.from(this).inflate(layoutRes, null)

        val txtTitle = dialogView.findViewById<TextView>(R.id.txtTitle)
        val txtMessage = dialogView.findViewById<TextView>(R.id.txtDeleteMessage)
        val btnConfirm = dialogView.findViewById<View>(R.id.btnDeleteConfirm)
        val btnCancel = dialogView.findViewById<View>(R.id.btnCancel)

        txtTitle?.setText(R.string.recycle_bin_delete_perm_title)
        txtMessage?.text = getString(R.string.recycle_bin_delete_perm_confirm, ids.size)

        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        btnConfirm?.setOnClickListener {
            dialog.dismiss()
            lifecycleScope.launch {
                progressBar.visibility = View.VISIBLE
                val deleted = withContext(Dispatchers.IO) { RecycleBinManager.permanentDeleteByIds(ids) }
                progressBar.visibility = View.GONE
                adapter.exitSelectionMode()
                Snackbar.make(recyclerItems, getString(R.string.recycle_bin_deleted_count, deleted), Snackbar.LENGTH_SHORT).show()
            }
        }

        btnCancel?.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
    }
}
