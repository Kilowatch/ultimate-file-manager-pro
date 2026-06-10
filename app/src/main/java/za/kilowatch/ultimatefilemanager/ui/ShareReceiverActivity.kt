package za.kilowatch.ultimatefilemanager.ui

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.webkit.MimeTypeMap
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.network.NetworkShare
import za.kilowatch.ultimatefilemanager.network.NetworkShareRepository
import za.kilowatch.ultimatefilemanager.network.ShareType
import za.kilowatch.ultimatefilemanager.storage.FileBrowserActivity
import za.kilowatch.ultimatefilemanager.storage.StorageBrowserActivity
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import za.kilowatch.ultimatefilemanager.util.FileTypeIconProvider
import java.io.File
import java.io.FileOutputStream
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper

class ShareReceiverActivity : AppCompatActivity() {

    private lateinit var imgFileIcon: ImageView
    private lateinit var txtFileName: TextView
    private lateinit var txtFileSize: TextView
    private lateinit var txtFileSource: TextView
    private lateinit var layoutFileInfo: View
    private lateinit var btnBrowse: MaterialButton
    private lateinit var txtSelectedPath: TextView
    private lateinit var layoutDestination: View
    private lateinit var btnSave: MaterialButton
    private lateinit var layoutBottomAction: View
    private lateinit var layoutEmpty: View
    private lateinit var progressBar: ProgressBar
    private lateinit var btnBack: View

    private var isTv = false
    private var sharedUris: List<Uri> = emptyList()
    private var sharedFileName: String = ""
    private var sharedMimeType: String = "*/*"
    private var sharedFileSize: Long = 0L
    private var callingAppName: String = ""

    // Picked destination
    private var selectedLocalPath: String? = null
    private var selectedShareId: String? = null
    private var selectedNetPath: String? = null

    private val destPickerLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data ?: return@registerForActivityResult
            val localPath = data.getStringExtra(StorageBrowserActivity.RESULT_SELECTED_LOCAL_PATH)
            if (localPath != null) {
                selectedLocalPath = localPath
                selectedShareId = null
                selectedNetPath = null
                onDestinationPicked(localPath)
                return@registerForActivityResult
            }
            val netPath = data.getStringExtra(StorageBrowserActivity.RESULT_SELECTED_NET_PATH)
            val shareId = data.getStringExtra(StorageBrowserActivity.RESULT_SELECTED_SHARE_ID)
            if (shareId != null && netPath != null) {
                selectedLocalPath = null
                selectedShareId = shareId
                selectedNetPath = netPath
                onDestinationPicked("$shareId:$netPath")
            }
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        za.kilowatch.ultimatefilemanager.settings.ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        isTv = DeviceUtils.isTvDevice(this)
        setContentView(if (isTv) R.layout.activity_share_receiver_tv else R.layout.activity_share_receiver)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        setupViews()
        extractShareIntent()
    }

    private fun setupViews() {
        imgFileIcon = findViewById(R.id.imgFileIcon)
        txtFileName = findViewById(R.id.txtFileName)
        txtFileSize = findViewById(R.id.txtFileSize)
        txtFileSource = findViewById(R.id.txtFileSource)
        layoutFileInfo = findViewById(R.id.layoutFileInfo)
        btnBrowse = findViewById(R.id.btnBrowse)
        txtSelectedPath = findViewById(R.id.txtSelectedPath)
        layoutDestination = findViewById(R.id.layoutDestination)
        btnSave = findViewById(R.id.btnSave)
        layoutBottomAction = findViewById(R.id.layoutBottomAction)
        layoutEmpty = findViewById(R.id.layoutEmpty)
        progressBar = findViewById(R.id.progressBar)
        btnBack = findViewById(R.id.btnBack)

        btnBack.setOnClickListener { finish() }

        btnBrowse.setOnClickListener { launchDestinationPicker() }

        btnSave.setOnClickListener { performSave() }
    }

    private fun extractShareIntent() {
        val intent = intent ?: run {
            layoutEmpty.visibility = View.VISIBLE
            return
        }

        when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                if (uri != null) {
                    sharedUris = listOf(uri)
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                }
                if (uris != null) {
                    sharedUris = uris
                }
            }
        }

        if (sharedUris.isEmpty()) {
            layoutEmpty.visibility = View.VISIBLE
            return
        }

        sharedMimeType = intent.type ?: "*/*"

        resolveCallingApp()
        resolveFileInfo()
        showFileInfo()
    }

    private fun resolveCallingApp() {
        val callingPkg = callingPackage
        if (callingPkg != null) {
            try {
                val pm = packageManager
                val ai = pm.getApplicationInfo(callingPkg, 0)
                callingAppName = pm.getApplicationLabel(ai).toString()
            } catch (_: Exception) {
                callingAppName = callingPkg
            }
        }
    }

    private fun resolveFileInfo() {
        val uri = sharedUris.firstOrNull() ?: return
        sharedFileName = ""
        sharedFileSize = 0L

        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0) {
                        sharedFileName = cursor.getString(nameIdx) ?: ""
                    }
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIdx >= 0) {
                        sharedFileSize = cursor.getLong(sizeIdx)
                    }
                }
            }
        } catch (_: Exception) {}

        if (sharedFileName.isEmpty()) {
            sharedFileName = uri.lastPathSegment ?: "file"
        }

        val ext = sharedFileName.substringAfterLast('.', "").lowercase()
        if (sharedMimeType == "*/*" && ext.isNotEmpty()) {
            val guessed = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            if (guessed != null) sharedMimeType = guessed
        }
    }

    private fun showFileInfo() {
        layoutFileInfo.visibility = View.VISIBLE

        txtFileName.text = if (sharedUris.size == 1) {
            sharedFileName
        } else {
            getString(R.string.share_receive_multiple_files, sharedUris.size)
        }

        txtFileSize.text = if (sharedUris.size == 1 && sharedFileSize > 0) {
            android.text.format.Formatter.formatFileSize(this, sharedFileSize)
        } else {
            ""
        }
        txtFileSize.visibility = if (txtFileSize.text.isNotEmpty()) View.VISIBLE else View.GONE

        txtFileSource.text = getString(R.string.share_receive_from, callingAppName)

        val ext = sharedFileName.substringAfterLast('.', "").lowercase()
        imgFileIcon.setImageResource(
            if (ext.isNotEmpty()) FileTypeIconProvider.iconForExtension(this, ext)
            else R.drawable.ic_file_generic
        )
    }

    private fun launchDestinationPicker() {
        val intent = Intent(this, StorageBrowserActivity::class.java).apply {
            putExtra(StorageBrowserActivity.EXTRA_SHARE_DEST_PICKER, true)
        }
        destPickerLauncher.launch(intent)
    }

    private fun onDestinationPicked(displayPath: String) {
        val label = if (selectedLocalPath != null) {
            selectedLocalPath
        } else if (selectedShareId != null) {
            val share = resolveShareById(selectedShareId!!)
            val name = share?.name ?: selectedShareId
            "$name/${selectedNetPath ?: ""}"
        } else {
            displayPath
        }
        txtSelectedPath.text = getString(R.string.share_receive_selected, label)
        txtSelectedPath.visibility = View.VISIBLE
        layoutBottomAction.visibility = View.VISIBLE
    }

    private fun performSave() {
        val uri = sharedUris.firstOrNull() ?: return
        progressBar.visibility = View.VISIBLE
        btnSave.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (selectedLocalPath != null) {
                    saveToLocal(uri, selectedLocalPath!!)
                } else if (selectedShareId != null && selectedNetPath != null) {
                    saveToNetwork(uri, selectedShareId!!, selectedNetPath!!)
                } else {
                    throw IllegalStateException("No destination selected")
                }

                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    Snackbar.make(findViewById(R.id.main), R.string.share_receive_success, Snackbar.LENGTH_LONG).show()
                    finishAfterDelay()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    btnSave.isEnabled = true
                    Snackbar.make(findViewById(R.id.main), getString(R.string.share_receive_error) + ": ${e.message}", Snackbar.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun saveToLocal(uri: Uri, destDirPath: String) {
        val dir = File(destDirPath)
        if (!dir.exists()) dir.mkdirs()
        val destFile = File(dir, sharedFileName)
        var uniqueFile = destFile
        var counter = 1
        while (uniqueFile.exists()) {
            val nameWithoutExt = sharedFileName.substringBeforeLast('.')
            val ext = sharedFileName.substringAfterLast('.', "")
            uniqueFile = File(dir, if (ext.isEmpty()) "${nameWithoutExt}_($counter)" else "${nameWithoutExt}_($counter).$ext")
            counter++
        }
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(uniqueFile).use { output ->
                input.copyTo(output)
            }
        }
    }

    private suspend fun saveToNetwork(uri: Uri, shareId: String, netPath: String) {
        val share = resolveShareById(shareId) ?: throw IllegalStateException("Share not found: $shareId")
        val remoteFilePath = if (netPath.isEmpty()) sharedFileName else "$netPath/$sharedFileName"

        withContext(Dispatchers.IO) {
            contentResolver.openInputStream(uri)?.use { input ->
                when (share.type) {
                    ShareType.SMB -> {
                        za.kilowatch.ultimatefilemanager.network.SmbShareClient.openOutputStream(share, remoteFilePath)
                            .use { output -> input.copyTo(output) }
                    }
                    ShareType.FTP -> {
                        za.kilowatch.ultimatefilemanager.network.FtpShareClient.openOutputStream(share, remoteFilePath)
                            .use { output -> input.copyTo(output) }
                    }
                    ShareType.SFTP, ShareType.SCP -> {
                        za.kilowatch.ultimatefilemanager.network.SshShareClient.openOutputStream(share, remoteFilePath)
                            .use { output -> input.copyTo(output) }
                    }
                    ShareType.TV -> {
                        za.kilowatch.ultimatefilemanager.network.TvShareClient.uploadStream(share, remoteFilePath, input, sharedFileSize)
                    }
                    ShareType.ONEDRIVE -> {
                        za.kilowatch.ultimatefilemanager.network.OnedriveShareClient.openOutputStream(share, remoteFilePath)
                            .use { output -> input.copyTo(output) }
                    }
                    ShareType.GOOGLE_DRIVE -> {
                        za.kilowatch.ultimatefilemanager.network.GoogleDriveShareClient.openOutputStream(share, remoteFilePath)
                            .use { output -> input.copyTo(output) }
                    }
                    ShareType.DROPBOX -> {
                        za.kilowatch.ultimatefilemanager.network.DropboxShareClient.openOutputStream(share, remoteFilePath)
                            .use { output -> input.copyTo(output) }
                    }
                    ShareType.AWS_S3, ShareType.IDRIVE_E2 -> {
                        za.kilowatch.ultimatefilemanager.network.S3ShareClient.openOutputStream(share, remoteFilePath)
                            .use { output -> input.copyTo(output) }
                    }
                    ShareType.WEBDAV -> {
                        za.kilowatch.ultimatefilemanager.network.WebDavShareClient.openOutputStream(share, remoteFilePath)
                            .use { output -> input.copyTo(output) }
                    }
                    ShareType.NFS -> {
                        za.kilowatch.ultimatefilemanager.network.NfsShareClient.openOutputStream(share, remoteFilePath)
                            .use { output -> input.copyTo(output) }
                    }
                    ShareType.DLNA -> {
                        throw UnsupportedOperationException("Cannot save to DLNA share — read-only")
                    }
                }
            }
        }
    }

    private fun resolveShareById(id: String): NetworkShare? {
        val fromRepo = NetworkShareRepository.getInstance(this).getById(id)
        if (fromRepo != null) return fromRepo

        val fromOnline = za.kilowatch.ultimatefilemanager.network.OnlineStorageRepository.getInstance(this).getById(id)
        if (fromOnline != null) {
            val providerType = when (fromOnline.provider) {
                za.kilowatch.ultimatefilemanager.network.OnlineStorageProvider.ONEDRIVE -> ShareType.ONEDRIVE
                za.kilowatch.ultimatefilemanager.network.OnlineStorageProvider.GOOGLE_DRIVE -> ShareType.GOOGLE_DRIVE
                za.kilowatch.ultimatefilemanager.network.OnlineStorageProvider.DROPBOX -> ShareType.DROPBOX
                za.kilowatch.ultimatefilemanager.network.OnlineStorageProvider.AWS_S3 -> ShareType.AWS_S3
                za.kilowatch.ultimatefilemanager.network.OnlineStorageProvider.IDRIVE_E2 -> ShareType.IDRIVE_E2
                za.kilowatch.ultimatefilemanager.network.OnlineStorageProvider.WEBDAV -> ShareType.WEBDAV
            }
            return NetworkShare(
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

        val dev = za.kilowatch.ultimatefilemanager.network.PairingManager.getInstance(this).getPairedDevice(id)
        if (dev != null) return NetworkShare(
            id = dev.deviceId, name = dev.name,
            type = ShareType.TV, host = dev.lastIp, port = dev.lastPort, readOnly = false
        )
        return null
    }

    private fun finishAfterDelay() {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            finish()
        }, 2000)
    }
}
