package za.kilowatch.ultimatefilemanager.storage

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import za.kilowatch.ultimatefilemanager.settings.SafFileActionPreferenceManager
import za.kilowatch.ultimatefilemanager.settings.ThemeHelper
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import za.kilowatch.ultimatefilemanager.util.GoRoLog
import za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter
import java.io.File

/**
 * Activity displaying installed Storage Access Framework (SAF) Document Providers,
 * matching Google Files' "Other storage" screen.
 *
 * Tapping a provider promotes it to a persistent storage tile on the Main Storage Menu.
 */
class OtherStorageActivity : AppCompatActivity() {

    private var isTv = false
    private lateinit var rvProviders: RecyclerView
    private lateinit var layoutEmpty: View
    private lateinit var txtEmptyMessage: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var txtSubtitle: TextView
    private lateinit var adapter: DocumentProviderAdapter

    private var selectedProvider: DocumentProviderInfo? = null
    private var currentActionDialog: AlertDialog? = null

    // Launcher for file-only or generic document selection
    private val openDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val data = result.data!!
                val uriList = mutableListOf<Uri>()

                val clipData = data.clipData
                if (clipData != null) {
                    for (i in 0 until clipData.itemCount) {
                        uriList.add(clipData.getItemAt(i).uri)
                    }
                } else if (data.data != null) {
                    uriList.add(data.data!!)
                }

                if (uriList.isNotEmpty()) {
                    handlePickedDocuments(uriList)
                }
            }
        }

    // Launcher for tree-capable providers (Termux, etc.)
    private val openDocumentTreeLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri != null) {
                handleTreeUriResult(uri)
            }
        }

    // Folder picker launcher for changing copy destination
    private val destinationPickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val selectedPath = result.data?.getStringExtra(FileBrowserActivity.RESULT_SELECTED_LOCAL_PATH)
                    ?: result.data?.getStringExtra(za.kilowatch.ultimatefilemanager.network.NetworkBrowserActivity.RESULT_URI)
                if (!selectedPath.isNullOrEmpty()) {
                    SafFileActionPreferenceManager.setCopyDestinationPath(this, selectedPath)
                    currentActionDialog?.findViewById<TextView>(R.id.txtDestinationPath)?.text =
                        getString(R.string.saf_copy_destination_title, SafFileActionPreferenceManager.getCopyDestinationDisplayName(this))
                }
            }
        }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        isTv = DeviceUtils.isTvDevice(this)
        setContentView(
            if (isTv) R.layout.activity_other_storage_tv
            else       R.layout.activity_other_storage
        )

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(sb.left, sb.top, sb.right, sb.bottom)
            insets
        }

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }

        rvProviders = findViewById(R.id.rvProviders)
        layoutEmpty = findViewById(R.id.layoutEmpty)
        txtEmptyMessage = findViewById(R.id.txtEmptyMessage)
        progressBar = findViewById(R.id.progressBar)
        txtSubtitle = findViewById(R.id.txtSubtitle)

        rvProviders.layoutManager = LinearLayoutManager(this)
        adapter = DocumentProviderAdapter(isTv) { provider ->
            onProviderClicked(provider)
        }
        rvProviders.adapter = adapter

        loadProviders()
    }

    override fun onResume() {
        super.onResume()
        loadProviders()
    }

    private fun loadProviders() {
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val allProviders = DocumentProviderManager.getInstalledProvidersAsync(this@OtherStorageActivity)
            val list = allProviders.filterNot { DocumentProviderRepository.isProviderMounted(this@OtherStorageActivity, it.authority) }
            progressBar.visibility = View.GONE

            if (list.isEmpty()) {
                layoutEmpty.visibility = View.VISIBLE
                rvProviders.visibility = View.GONE
                if (allProviders.isNotEmpty()) {
                    txtSubtitle.text = getString(R.string.other_storage_all_added)
                    txtEmptyMessage.text = getString(R.string.other_storage_empty_all_added)
                } else {
                    txtSubtitle.text = getString(R.string.other_storage_empty)
                    txtEmptyMessage.text = getString(R.string.other_storage_empty)
                }
            } else {
                layoutEmpty.visibility = View.GONE
                rvProviders.visibility = View.VISIBLE
                txtSubtitle.text = if (list.size == 1) {
                    getString(R.string.other_storage_provider_count_single)
                } else {
                    getString(R.string.other_storage_provider_count, list.size)
                }
                adapter.submitList(list)
            }
        }
    }

    private fun onProviderClicked(provider: DocumentProviderInfo) {
        selectedProvider = provider
        // Mount provider as a persistent tile on Main Storage Menu
        val mounted = MountedDocumentProvider(
            authority = provider.authority,
            packageName = provider.packageName,
            displayName = provider.label,
            isTreeCapable = provider.isTreeCapable
        )
        DocumentProviderRepository.addProvider(this, mounted)
        Toast.makeText(this, getString(R.string.doc_provider_added_toast, provider.label), Toast.LENGTH_SHORT).show()
        loadProviders()
    }

    private fun handleTreeUriResult(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            contentResolver.takePersistableUriPermission(uri, flags)
        } catch (e: Exception) {
            GoRoLog.w("OtherStorage", "Failed to persist tree permission: ${e.message}")
        }

        val authority = uri.authority ?: ""
        val isTermux = authority.contains("termux")
        val doc = DocumentFile.fromTreeUri(this, uri)
        val defaultName = doc?.name ?: if (isTermux) getString(R.string.storage_location_termux_default_name) else selectedProvider?.label ?: getString(R.string.storage_location_saf_default_name)
        val defaultIcon = if (isTermux) "terminal" else "folder"

        val docId = try {
            android.provider.DocumentsContract.getTreeDocumentId(uri)
        } catch (_: Exception) { "" }

        val newLocation = SafLocation(
            displayName = defaultName,
            treeUriString = uri.toString(),
            authority = authority,
            rootDocId = docId,
            iconType = defaultIcon,
            isStandalone = true
        )
        SafLocationRepository.addLocation(this, newLocation)
        Toast.makeText(this, getString(R.string.add_storage_success, defaultName), Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun handlePickedDocuments(uris: List<Uri>) {
        val actionPref = SafFileActionPreferenceManager.getAction(this)
        when (actionPref) {
            SafFileActionPreferenceManager.ACTION_OPEN_VIEWER -> {
                for (uri in uris) openUriInViewer(uri)
            }
            SafFileActionPreferenceManager.ACTION_COPY_LOCAL -> {
                val destPath = SafFileActionPreferenceManager.getCopyDestinationPath(this)
                SafFileTransferManager.copyUrisToDestination(this, uris, destPath)
            }
            else -> {
                showActionChoiceDialog(uris)
            }
        }
    }

    private fun showActionChoiceDialog(uris: List<Uri>) {
        val firstUri = uris.first()
        val fileName = getFileName(firstUri)
        val title = if (uris.size == 1) fileName else "${uris.size} files"

        val layoutRes = if (isTv) R.layout.dialog_saf_file_action_tv else R.layout.dialog_saf_file_action
        val dialogView = LayoutInflater.from(this).inflate(layoutRes, null)
        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        currentActionDialog = dialog

        val chkRemember = dialogView.findViewById<CheckBox>(R.id.chkRemember)
        val txtMessage = dialogView.findViewById<TextView>(R.id.txtMessage)
        val txtDestinationPath = dialogView.findViewById<TextView>(R.id.txtDestinationPath)
        val btnChangeDestination = dialogView.findViewById<View>(R.id.btnChangeDestination)
        val btnOpenViewer = dialogView.findViewById<View>(R.id.btnOpenViewer)
        val btnCopyLocal = dialogView.findViewById<View>(R.id.btnCopyLocal)
        val btnCancel = dialogView.findViewById<View>(R.id.btnCancel)

        txtMessage.text = getString(R.string.saf_file_action_dialog_msg, title)
        txtDestinationPath.text = getString(R.string.saf_copy_destination_title, SafFileActionPreferenceManager.getCopyDestinationDisplayName(this))

        btnChangeDestination?.setOnClickListener {
            val intent = Intent(this, StorageBrowserActivity::class.java).apply {
                putExtra(FileBrowserActivity.EXTRA_SAF_COPY_DEST_PICKER, true)
            }
            destinationPickerLauncher.launch(intent)
        }

        btnOpenViewer.setOnClickListener {
            if (chkRemember.isChecked) {
                SafFileActionPreferenceManager.setAction(this, SafFileActionPreferenceManager.ACTION_OPEN_VIEWER)
            }
            dialog.dismiss()
            for (u in uris) openUriInViewer(u)
        }

        btnCopyLocal.setOnClickListener {
            if (chkRemember.isChecked) {
                SafFileActionPreferenceManager.setAction(this, SafFileActionPreferenceManager.ACTION_COPY_LOCAL)
            }
            dialog.dismiss()
            val destDir = File(SafFileActionPreferenceManager.getCopyDestinationPath(this))
            SafFileTransferManager.copyUrisToDirectory(this, uris, destDir)
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialog.setOnDismissListener {
            if (currentActionDialog == dialog) {
                currentActionDialog = null
            }
        }

        dialog.show()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        if (isTv) {
            btnOpenViewer.requestFocus()
        }
    }

    private fun openUriInViewer(uri: Uri) {
        takePersistableReadPermission(uri)
        val fileName = getFileName(uri)
        val cacheDir = File(cacheDir, "saf_temp").apply { mkdirs() }
        val tempFile = File(cacheDir, fileName)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                contentResolver.openInputStream(uri)?.use { inStream ->
                    tempFile.outputStream().use { outStream ->
                        inStream.copyTo(outStream)
                    }
                }
                withContext(Dispatchers.Main) {
                    val opened = FileViewerRouter.openFile(this@OtherStorageActivity, tempFile)
                    if (!opened) {
                        openWithExternalIntent(uri)
                    }
                }
            } catch (e: Exception) {
                GoRoLog.e("OtherStorage", "Error opening URI in viewer: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    openWithExternalIntent(uri)
                }
            }
        }
    }

    private fun openWithExternalIntent(uri: Uri) {
        val mime = contentResolver.getType(uri) ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, getString(R.string.no_app_to_open_file), Toast.LENGTH_SHORT).show()
        }
    }

    private fun takePersistableReadPermission(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {}
    }

    private fun getFileName(uri: Uri): String {
        var name = "Document"
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx != -1 && cursor.moveToFirst()) {
                    name = cursor.getString(idx) ?: name
                }
            }
        } catch (_: Exception) {}
        return name
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                File(cacheDir, "saf_temp").deleteRecursively()
            } catch (_: Exception) {}
        }
    }
}
