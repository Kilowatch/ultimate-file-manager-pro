package za.kilowatch.ultimatefilemanager.scanner

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textfield.TextInputEditText
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import za.kilowatch.ultimatefilemanager.network.DropboxShareClient
import za.kilowatch.ultimatefilemanager.network.FtpShareClient
import za.kilowatch.ultimatefilemanager.network.GoogleDriveShareClient
import za.kilowatch.ultimatefilemanager.network.NetworkShareRepository
import za.kilowatch.ultimatefilemanager.network.OnedriveShareClient
import za.kilowatch.ultimatefilemanager.network.OnlineStorageRepository
import za.kilowatch.ultimatefilemanager.network.S3ShareClient
import za.kilowatch.ultimatefilemanager.network.NfsShareClient
import za.kilowatch.ultimatefilemanager.network.SmbShareClient
import za.kilowatch.ultimatefilemanager.network.ShareType
import za.kilowatch.ultimatefilemanager.network.SshShareClient
import za.kilowatch.ultimatefilemanager.network.WebDavShareClient
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import za.kilowatch.ultimatefilemanager.settings.ThemeHelper
import za.kilowatch.ultimatefilemanager.storage.FileBrowserActivity
import za.kilowatch.ultimatefilemanager.storage.StorageBrowserActivity
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DocumentScannerActivity : AppCompatActivity() {

    private lateinit var txtSubtitle: TextView
    private lateinit var txtEmptyState: TextView
    private lateinit var pagesRecycler: RecyclerView
    private lateinit var btnScanPage: com.google.android.material.button.MaterialButton
    private lateinit var btnSave: com.google.android.material.button.MaterialButton

    private val scannedBitmaps = mutableListOf<Bitmap>()
    private var selectedFolderPath: String? = null
    private var selectedNetShareId: String? = null
    private var selectedNetPath: String? = null
    private var pendingFormat: String? = null
    private var pendingFileName: String? = null
    private var pagesAdapter: PagesAdapter? = null
    private var cameraPhotoUri: Uri? = null

    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            val uri = cameraPhotoUri
            if (uri != null) {
                loadBitmapFromUri(uri)
                // Clean up temp file after loading
                try {
                    val file = File(uri.path ?: "")
                    if (file.exists()) file.delete()
                } catch (_: Exception) {}
                updatePagesUi()
            }
        }
    }

    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            loadBitmapFromUri(uri)
            updatePagesUi()
        }
    }

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data
            val localPath = data?.getStringExtra(FileBrowserActivity.RESULT_SELECTED_LOCAL_PATH)
            val netShareId = data?.getStringExtra(za.kilowatch.ultimatefilemanager.network.NetworkBrowserActivity.RESULT_SELECTED_SHARE_ID)
            val netPath = data?.getStringExtra(za.kilowatch.ultimatefilemanager.network.NetworkBrowserActivity.RESULT_SELECTED_NET_PATH)
            if (localPath != null) {
                selectedFolderPath = localPath
                selectedNetShareId = null
                selectedNetPath = null
            } else if (netShareId != null) {
                selectedNetShareId = netShareId
                selectedNetPath = netPath ?: ""
                selectedFolderPath = null
            }
            if (pendingFormat != null && pendingFileName != null) {
                showSaveDialog(pendingFormat!!, pendingFileName!!)
            }
        }
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (DeviceUtils.isTvDevice(this)) {
            Toast.makeText(this, R.string.scanner_no_pages, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setContentView(R.layout.activity_document_scanner)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(sb.left, sb.top, sb.right, sb.bottom)
            insets
        }

        txtSubtitle = findViewById(R.id.txtSubtitle)
        txtEmptyState = findViewById(R.id.txtEmptyState)
        pagesRecycler = findViewById(R.id.pagesRecycler)
        btnScanPage = findViewById(R.id.btnScanPage)
        btnSave = findViewById(R.id.btnSave)

        pagesRecycler.layoutManager = LinearLayoutManager(this)
        pagesAdapter = PagesAdapter(scannedBitmaps) { index ->
            scannedBitmaps.removeAt(index)
            updatePagesUi()
        }
        pagesRecycler.adapter = pagesAdapter

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        btnScanPage.setOnClickListener { showCaptureChoice() }
        btnSave.setOnClickListener { onSavePressed() }

        selectedFolderPath = getDefaultScansDir()
        updatePagesUi()
    }

    private fun showCaptureChoice() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_scanner_source, null)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()
        dialog.window?.setBackgroundDrawableResource(R.drawable.bg_dialog_surface)
        dialogView.findViewById<View>(R.id.btnCamera).setOnClickListener {
            dialog.dismiss()
            launchCamera()
        }
        dialogView.findViewById<View>(R.id.btnGallery).setOnClickListener {
            dialog.dismiss()
            launchGallery()
        }
        dialog.show()
    }

    private fun launchCamera() {
        val photoFile = createTempPhotoFile()
        val uri = FileProvider.getUriForFile(
            this,
            "${packageName}.fileprovider",
            photoFile
        )
        cameraPhotoUri = uri
        cameraLauncher.launch(uri)
    }

    private fun launchGallery() {
        galleryLauncher.launch("image/*")
    }

    private fun createTempPhotoFile(): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val dir = File(cacheDir, "scanner_temp")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "IMG_${timeStamp}.jpg")
    }

    private fun getDefaultScansDir(): String {
        val dir = File(
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS),
            "UFM/Scans"
        )
        if (!dir.exists()) dir.mkdirs()
        return dir.absolutePath
    }

    private fun loadBitmapFromUri(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri)
            var bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()
            if (bitmap != null) {
                // Read EXIF orientation and rotate the bitmap so it displays upright
                val rotation = readExifRotation(uri)
                if (rotation != 0) {
                    val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                    bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                }
                scannedBitmaps.add(bitmap)
            }
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.scanner_save_error, e.message), Toast.LENGTH_SHORT).show()
        }
    }

    private fun readExifRotation(uri: Uri): Int {
        return try {
            val inputStream = contentResolver.openInputStream(uri) ?: return 0
            val exif = ExifInterface(inputStream)
            inputStream.close()
            when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90  -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else                                 -> 0
            }
        } catch (e: Exception) {
            0
        }
    }

    private fun updatePagesUi() {
        val count = scannedBitmaps.size
        txtSubtitle.text = getString(R.string.scanner_pages, count)
        if (count == 0) {
            txtEmptyState.visibility = View.VISIBLE
            pagesRecycler.visibility = View.GONE
            btnSave.visibility = View.GONE
        } else {
            txtEmptyState.visibility = View.GONE
            pagesRecycler.visibility = View.VISIBLE
            btnSave.visibility = View.VISIBLE
            btnSave.isEnabled = true
            btnSave.alpha = 1.0f
            pagesAdapter?.notifyDataSetChanged()
        }
    }

    private fun onSavePressed() {
        if (scannedBitmaps.isEmpty()) {
            Toast.makeText(this, R.string.scanner_no_pages, Toast.LENGTH_SHORT).show()
            return
        }
        val defaultName = getString(R.string.scanner_default_filename) + "_" +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        pendingFileName = "$defaultName.pdf"
        pendingFormat = "pdf"
        showSaveDialog("pdf", pendingFileName!!)
    }

    private fun showSaveDialog(ext: String, name: String) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_scanner_save, null)
        val edtFilename = dialogView.findViewById<TextInputEditText>(R.id.edtFilename)
        val txtSavePath = dialogView.findViewById<TextView>(R.id.txtSavePath)
        val btnSelectFolder = dialogView.findViewById<View>(R.id.btnSelectFolder)
        val btnConfirm = dialogView.findViewById<View>(R.id.btnConfirmSave)
        val btnCancel = dialogView.findViewById<View>(R.id.btnCancelSave)
        val chipFormatJpeg = dialogView.findViewById<com.google.android.material.chip.Chip>(R.id.chipFormatJpeg)
        val chipFormatPng = dialogView.findViewById<com.google.android.material.chip.Chip>(R.id.chipFormatPng)
        val chipFormatPdf = dialogView.findViewById<com.google.android.material.chip.Chip>(R.id.chipFormatPdf)
        val chipGroupFormats = dialogView.findViewById<com.google.android.material.chip.ChipGroup>(R.id.chipGroupFormats)
        val txtPageCount = dialogView.findViewById<TextView>(R.id.txtPageCount)

        val baseName = name.substringBeforeLast(".")
        edtFilename.setText(baseName)
        val displayPath = when {
            selectedFolderPath != null -> selectedFolderPath!!
            selectedNetShareId != null -> "${selectedNetShareId}/${selectedNetPath ?: ""}"
            else -> getDefaultScansDir()
        }
        txtSavePath.text = getString(R.string.scanner_save_path, displayPath)
        txtPageCount.text = getString(R.string.scanner_pages, scannedBitmaps.size)

        val isMultiPage = scannedBitmaps.size > 1
        if (isMultiPage) {
            chipFormatJpeg.isEnabled = false
            chipFormatPng.isEnabled = false
        }

        var selectedFormat = ext.lowercase()

        chipFormatJpeg.isChecked = selectedFormat == "jpeg"
        chipFormatPng.isChecked = selectedFormat == "png"
        chipFormatPdf.isChecked = selectedFormat == "pdf"

        chipGroupFormats.setOnCheckedStateChangeListener { _, checkedIds ->
            selectedFormat = when {
                checkedIds.contains(R.id.chipFormatJpeg) -> "jpeg"
                checkedIds.contains(R.id.chipFormatPng) -> "png"
                checkedIds.contains(R.id.chipFormatPdf) -> "pdf"
                else -> "pdf"
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()
        dialog.window?.setBackgroundDrawableResource(R.drawable.bg_dialog_surface)

        btnSelectFolder.setOnClickListener {
            pendingFormat = selectedFormat
            pendingFileName = "${edtFilename.text}.$selectedFormat"
            dialog.dismiss()
            val intent = Intent(this, StorageBrowserActivity::class.java).apply {
                putExtra(FileBrowserActivity.EXTRA_SCANNER_FOLDER_PICKER, true)
            }
            folderPickerLauncher.launch(intent)
        }

        btnConfirm.setOnClickListener {
            val nameInput = edtFilename.text?.toString()?.trim() ?: ""
            if (nameInput.isEmpty()) {
                Toast.makeText(this, R.string.filename_empty_error, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val finalName = "$nameInput.$selectedFormat"
            val folder = selectedFolderPath ?: getDefaultScansDir()
            dialog.dismiss()
            doSave(File(folder, finalName), selectedFormat)
        }

        btnCancel.setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    private fun doSave(targetFile: File, format: String) {
        val progressView = layoutInflater.inflate(R.layout.dialog_scanner_progress, null)
        val progressDialog = AlertDialog.Builder(this)
            .setView(progressView)
            .setCancelable(false)
            .create()
        progressDialog.show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Write content to a temp file first
                val tempFile = File(cacheDir, "scanner_export_${System.nanoTime()}.tmp")
                when (format) {
                    "jpeg" -> {
                        val bmp = scannedBitmaps[0]
                        FileOutputStream(tempFile).use { out ->
                            bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
                        }
                    }
                    "png" -> {
                        val bmp = scannedBitmaps[0]
                        FileOutputStream(tempFile).use { out ->
                            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                        }
                    }
                    "pdf" -> {
                        PDFBoxResourceLoader.init(this@DocumentScannerActivity)
                        val document = PDDocument()
                        for ((i, bmp) in scannedBitmaps.withIndex()) {
                            val pageJpeg = File(cacheDir, "pdf_page_${i}_${System.nanoTime()}.jpg")
                            FileOutputStream(pageJpeg).use { out ->
                                bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
                            }
                            val fis = java.io.FileInputStream(pageJpeg)
                            val image = JPEGFactory.createFromStream(document, fis)
                            fis.close()
                            pageJpeg.delete()
                            val page = PDPage(PDRectangle(image.width.toFloat(), image.height.toFloat()))
                            document.addPage(page)
                            val cs = PDPageContentStream(document, page)
                            cs.drawImage(image, 0f, 0f, image.width.toFloat(), image.height.toFloat())
                            cs.close()
                        }
                        document.save(tempFile)
                        document.close()
                    }
                }

                // Save to network or local destination
                try {
                    val netShareId = selectedNetShareId
                    if (netShareId != null) {
                        val netPath = selectedNetPath ?: ""
                        val remoteFilePath = if (netPath.isEmpty()) targetFile.name else "${netPath.trimEnd('/')}/${targetFile.name}"
                        uploadToNetwork(tempFile, netShareId, remoteFilePath)
                    } else {
                        // Use copy instead of renameTo — renameTo fails across
                        // filesystem boundaries (internal ↔ SD card).
                        targetFile.parentFile?.mkdirs()
                        java.io.FileInputStream(tempFile).use { inp ->
                            java.io.FileOutputStream(targetFile).use { out ->
                                inp.copyTo(out)
                            }
                        }
                    }
                } finally {
                    if (tempFile.exists()) tempFile.delete()
                }

                withContext(Dispatchers.Main) {
                    if (progressDialog.isShowing) progressDialog.dismiss()
                    Toast.makeText(
                        this@DocumentScannerActivity,
                        getString(R.string.scanner_saved, targetFile.name),
                        Toast.LENGTH_SHORT
                    ).show()
                    finish()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (progressDialog.isShowing) progressDialog.dismiss()
                    Toast.makeText(
                        this@DocumentScannerActivity,
                        getString(R.string.scanner_save_error, e.message),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private suspend fun uploadToNetwork(tempFile: File, shareId: String, remotePath: String) {
        val share = NetworkShareRepository.getInstance(this).getById(shareId)
            ?: OnlineStorageRepository.getInstance(this).getById(shareId)?.let { online ->
                za.kilowatch.ultimatefilemanager.network.NetworkShare(
                    id = online.id,
                    name = online.displayName,
                    type = when (online.provider) {
                        za.kilowatch.ultimatefilemanager.network.OnlineStorageProvider.ONEDRIVE -> ShareType.ONEDRIVE
                        za.kilowatch.ultimatefilemanager.network.OnlineStorageProvider.GOOGLE_DRIVE -> ShareType.GOOGLE_DRIVE
                        za.kilowatch.ultimatefilemanager.network.OnlineStorageProvider.DROPBOX -> ShareType.DROPBOX
                        za.kilowatch.ultimatefilemanager.network.OnlineStorageProvider.AWS_S3 -> ShareType.AWS_S3
                        za.kilowatch.ultimatefilemanager.network.OnlineStorageProvider.IDRIVE_E2 -> ShareType.IDRIVE_E2
                        za.kilowatch.ultimatefilemanager.network.OnlineStorageProvider.WEBDAV -> ShareType.WEBDAV
                    },
                    host = online.email,
                    port = 0,
                    username = online.email,
                    password = "",
                    remotePath = "/",
                    readOnly = false
                )
            } ?: throw java.io.IOException("Network share not found: $shareId")

        val input = java.io.FileInputStream(tempFile)
        when (share.type) {
            ShareType.SMB -> SmbShareClient.openOutputStream(share, remotePath)
            ShareType.FTP -> FtpShareClient.openOutputStream(share, remotePath)
            ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.openOutputStream(share, remotePath)
            ShareType.WEBDAV -> WebDavShareClient.openOutputStream(share, remotePath)
            ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.openOutputStream(share, remotePath)
            ShareType.ONEDRIVE -> OnedriveShareClient.openOutputStream(share, remotePath)
            ShareType.DROPBOX -> DropboxShareClient.openOutputStream(share, remotePath)
            ShareType.SFTP, ShareType.SCP -> {
                // SshShareClient.openOutputStream is not a suspend function,
                // use withContext to bridge onto IO dispatcher
                withContext(Dispatchers.IO) {
                    SshShareClient.openOutputStream(share, remotePath)
                }
            }
            ShareType.NFS -> NfsShareClient.openOutputStream(share, remotePath)
            ShareType.DLNA -> throw UnsupportedOperationException("DLNA is read-only")
            else -> throw java.io.IOException("Saving to ${share.type} is not supported yet")
        }.use { output ->
            input.copyTo(output)
        }
        input.close()
    }

    override fun onBackPressed() {
        if (scannedBitmaps.isNotEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.unsaved_changes_title)
                .setMessage(R.string.unsaved_changes_message)
                .setPositiveButton(R.string.scanner_save) { _, _ -> onSavePressed() }
                .setNegativeButton(R.string.btn_discard) { _, _ -> finish() }
                .setNeutralButton(android.R.string.cancel, null)
                .show()
        } else {
            super.onBackPressed()
        }
    }

    private class PagesAdapter(
        private val bitmaps: MutableList<Bitmap>,
        private val onDelete: (Int) -> Unit
    ) : RecyclerView.Adapter<PagesAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_scanned_page, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val bmp = bitmaps[position]
            holder.imageView.setImageBitmap(bmp)
            holder.pageNumber.text = "#${position + 1}"
            holder.btnDelete.setOnClickListener { onDelete(position) }
        }

        override fun getItemCount() = bitmaps.size

        class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val imageView: ImageView = itemView.findViewById(R.id.imgPage)
            val pageNumber: TextView = itemView.findViewById(R.id.txtPageNumber)
            val btnDelete: View = itemView.findViewById(R.id.btnDeletePage)
        }
    }
}
