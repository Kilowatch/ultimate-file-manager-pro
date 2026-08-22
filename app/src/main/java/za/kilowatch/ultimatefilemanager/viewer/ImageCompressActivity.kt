package za.kilowatch.ultimatefilemanager.viewer

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Typeface
import android.media.ExifInterface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.network.DropboxShareClient
import za.kilowatch.ultimatefilemanager.network.FtpShareClient
import za.kilowatch.ultimatefilemanager.network.GoogleDriveShareClient
import za.kilowatch.ultimatefilemanager.network.NetworkBrowserActivity
import za.kilowatch.ultimatefilemanager.network.NetworkShare
import za.kilowatch.ultimatefilemanager.network.NetworkShareRepository
import za.kilowatch.ultimatefilemanager.network.NfsShareClient
import za.kilowatch.ultimatefilemanager.network.OnedriveShareClient
import za.kilowatch.ultimatefilemanager.network.PairingManager
import za.kilowatch.ultimatefilemanager.network.S3ShareClient
import za.kilowatch.ultimatefilemanager.network.ShareType
import za.kilowatch.ultimatefilemanager.network.SmbShareClient
import za.kilowatch.ultimatefilemanager.network.SshShareClient
import za.kilowatch.ultimatefilemanager.network.TvShareClient
import za.kilowatch.ultimatefilemanager.network.WebDavShareClient
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import za.kilowatch.ultimatefilemanager.util.CopyHelper
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import java.io.File
import java.io.FileOutputStream

class ImageCompressActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FILE_PATHS = "extra_file_paths"
        const val EXTRA_NETWORK_SHARE_ID = "extra_network_share_id"
        const val EXTRA_NETWORK_PATH = "extra_network_path"
        const val EXTRA_SOURCE_SHARE_ID = "extra_source_share_id"
    }

    private lateinit var layoutImageList: LinearLayout
    private lateinit var seekQuality: android.widget.SeekBar
    private lateinit var txtQualityValue: TextView
    private lateinit var chkResize: android.widget.CheckBox
    private lateinit var layoutResizeFields: View
    private lateinit var editWidth: android.widget.EditText
    private lateinit var editHeight: android.widget.EditText
    private lateinit var chipFormatOriginal: com.google.android.material.chip.Chip
    private lateinit var chipFormatJpeg: com.google.android.material.chip.Chip
    private lateinit var chipFormatPng: com.google.android.material.chip.Chip
    private lateinit var chipFormatWebp: com.google.android.material.chip.Chip
    private lateinit var txtOutputPath: TextView
    private lateinit var btnCompress: com.google.android.material.button.MaterialButton

    private var isTv = false
    private val originalFiles = mutableListOf<File>()
    private var customOutputDir: File? = null
    private var networkShareId: String? = null
    private var networkPath: String? = null
    private var sourceShareId: String? = null

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data ?: return@registerForActivityResult

            // Local destination
            val path = data.getStringExtra(
                za.kilowatch.ultimatefilemanager.storage.FileBrowserActivity.RESULT_SELECTED_LOCAL_PATH
            )
            if (path != null) {
                customOutputDir = File(path)
                networkShareId = null
                networkPath = null
                txtOutputPath.text = path
                return@registerForActivityResult
            }

            // Network destination
            val shareId = data.getStringExtra(NetworkBrowserActivity.RESULT_SELECTED_COMPRESS_SHARE_ID)
            val netPath = data.getStringExtra(NetworkBrowserActivity.RESULT_SELECTED_COMPRESS_NET_PATH)
            if (shareId != null && netPath != null) {
                customOutputDir = null
                networkShareId = shareId
                networkPath = netPath
                val share = resolveShareById(shareId)
                val display = if (share != null) "${share.name}/$netPath" else "Network: $netPath"
                txtOutputPath.text = display
            }
        }
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        za.kilowatch.ultimatefilemanager.settings.ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        isTv = DeviceUtils.isTvDevice(this)
        setContentView(
            if (isTv) R.layout.activity_image_compress_tv
            else R.layout.activity_image_compress
        )

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val tvPad = if (isTv) (27 * resources.displayMetrics.density).toInt() else 0
            v.setPadding(
                sb.left + tvPad, sb.top + tvPad,
                sb.right + tvPad, sb.bottom + tvPad
            )
            insets
        }

        bindViews()
        loadFiles()
    }

    private fun bindViews() {
        val btnBack = findViewById<android.widget.ImageView>(R.id.btnBack)
        btnBack?.setOnClickListener { finish() }

        layoutImageList = findViewById(R.id.layoutImageList)
        seekQuality = findViewById(R.id.seekQuality)
        txtQualityValue = findViewById(R.id.txtQualityValue)
        chkResize = findViewById(R.id.chkResize)
        editWidth = findViewById(R.id.editWidth)
        editHeight = findViewById(R.id.editHeight)
        chipFormatOriginal = findViewById(R.id.chipFormatOriginal)
        chipFormatJpeg = findViewById(R.id.chipFormatJpeg)
        chipFormatPng = findViewById(R.id.chipFormatPng)
        chipFormatWebp = findViewById(R.id.chipFormatWebp)

        val formatChips = listOf(chipFormatOriginal, chipFormatJpeg, chipFormatPng, chipFormatWebp)
        formatChips.forEach { chip ->
            chip.setOnClickListener {
                formatChips.forEach { it.isChecked = (it == chip) }
            }
        }
        txtOutputPath = findViewById(R.id.txtOutputPath)
        findViewById<View>(R.id.btnChangeOutput)?.setOnClickListener { pickOutputFolder() }
        btnCompress = findViewById(R.id.btnCompress)
        layoutResizeFields = findViewById(R.id.layoutResizeFields)

        seekQuality.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                txtQualityValue.text = progress.toString()
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) = Unit
        })

        chkResize.setOnCheckedChangeListener { _, isChecked ->
            layoutResizeFields.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        btnCompress.setOnClickListener { startCompression() }
    }

    private fun pickOutputFolder() {
        val intent = Intent(this, za.kilowatch.ultimatefilemanager.storage.StorageBrowserActivity::class.java).apply {
            putExtra(
                za.kilowatch.ultimatefilemanager.storage.StorageBrowserActivity.EXTRA_IMAGE_COMPRESS_DEST_PICKER,
                true
            )
        }
        folderPickerLauncher.launch(intent)
    }

    private fun loadFiles() {
        val paths = intent.getStringArrayListExtra(EXTRA_FILE_PATHS) ?: return
        val imageExtensions = FileViewerRouter.IMAGE_EXTENSIONS
        originalFiles.clear()
        for (p in paths) {
            val f = File(p)
            if (f.exists() && f.extension.lowercase() in imageExtensions) {
                originalFiles.add(f)
            }
        }

        if (originalFiles.isEmpty()) {
            Toast.makeText(this, R.string.compress_image_no_images, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val txtSubtitle = findViewById<TextView>(R.id.txtSubtitle)
        txtSubtitle?.text = getString(R.string.compress_image_count, originalFiles.size)

        layoutImageList.removeAllViews()
        val primaryColor = if (isTv) getColor(R.color.tv_text_primary) else getColor(R.color.mobile_card_text_primary)
        val secondaryColor = if (isTv) getColor(R.color.tv_text_secondary) else getColor(R.color.mobile_card_text_secondary)

        for ((index, file) in originalFiles.withIndex()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 8, 0, 8)
            }

            val nameText = TextView(this).apply {
                text = file.name
                setTextColor(primaryColor)
                textSize = if (isTv) 16f else 14f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(nameText)

            val sizeLp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            sizeLp.leftMargin = 16
            val sizeText = TextView(this).apply {
                text = formatSize(file.length())
                setTextColor(secondaryColor)
                textSize = if (isTv) 14f else 12f
                layoutParams = sizeLp
            }
            row.addView(sizeText)

            layoutImageList.addView(row)

            if (index < originalFiles.size - 1) {
                val divider = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1
                    )
                    setBackgroundColor(if (isTv) getColor(R.color.tv_divider) else 0x1A000000.toInt())
                }
                layoutImageList.addView(divider)
            }
        }

        // Read network output destination extras
        networkShareId = intent.getStringExtra(EXTRA_NETWORK_SHARE_ID)
        networkPath = intent.getStringExtra(EXTRA_NETWORK_PATH)
        sourceShareId = intent.getStringExtra(EXTRA_SOURCE_SHARE_ID)

        // If no explicit network output but a source share is known, default to source
        if (networkShareId == null && sourceShareId != null) {
            networkShareId = sourceShareId
        }

        // Default output path display — network takes priority
        if (networkShareId != null) {
            val share = resolveShareById(networkShareId!!)
            val display = if (share != null) "${share.name}/${networkPath ?: ""}"
                          else getString(R.string.compress_image_output_same)
            txtOutputPath.text = display
        }
    }

    private data class CompressionResult(
        val name: String,
        val originalSize: Long,
        val compressedSize: Long,
        val savedPercent: Int,
        val error: String? = null
    )

    private fun startCompression() {
        val quality = seekQuality.progress
        if (quality < 1) {
            Toast.makeText(this, R.string.compress_image_quality, Toast.LENGTH_SHORT).show()
            return
        }

        btnCompress.isEnabled = false

        val resizeChecked = chkResize.isChecked
        val resizeW = if (resizeChecked) editWidth.text.toString().toIntOrNull() else null
        val resizeH = if (resizeChecked) editHeight.text.toString().toIntOrNull() else null

        val forceFormat = when {
            chipFormatJpeg.isChecked -> Bitmap.CompressFormat.JPEG
            chipFormatPng.isChecked -> Bitmap.CompressFormat.PNG
            chipFormatWebp.isChecked -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    @Suppress("DEPRECATION")
                    Bitmap.CompressFormat.WEBP
                }
            }
            else -> null
        }

        val progressDialogView = layoutInflater.inflate(
            if (isTv) R.layout.dialog_image_compress_progress_tv
            else R.layout.dialog_image_compress_progress,
            null
        )
        val txtProgressSubtitle = progressDialogView.findViewById<TextView>(R.id.txtProgressSubtitle)

        val progressDialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(progressDialogView)
            .setCancelable(false)
            .create()

        progressDialog.show()
        progressDialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        lifecycleScope.launch(Dispatchers.IO) {
            val results = mutableListOf<CompressionResult>()

            for ((i, file) in originalFiles.withIndex()) {
                val progressMsg = getString(R.string.compress_image_progress, i + 1, originalFiles.size)
                withContext(Dispatchers.Main) { txtProgressSubtitle.text = progressMsg }

                try {
                    val outputFormat = forceFormat ?: detectFormat(file)
                    val ext = when (outputFormat) {
                        Bitmap.CompressFormat.JPEG -> "jpg"
                        Bitmap.CompressFormat.PNG -> "png"
                        Bitmap.CompressFormat.WEBP,
                        Bitmap.CompressFormat.WEBP_LOSSY,
                        Bitmap.CompressFormat.WEBP_LOSSLESS -> "webp"
                    }

                    val rotation = readExifRotation(file)

                    // Decode bitmap with optional resize via inSampleSize
                    val opts = BitmapFactory.Options().apply {
                        inJustDecodeBounds = false
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    if (resizeW != null && resizeH != null) {
                        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(file.absolutePath, boundsOpts)
                        val targetW = if (rotation == 90 || rotation == 270) resizeH else resizeW
                        val targetH = if (rotation == 90 || rotation == 270) resizeW else resizeH
                        opts.inSampleSize = calculateInSampleSize(boundsOpts, targetW, targetH)
                    }
                    val decodedBitmap = BitmapFactory.decodeFile(file.absolutePath, opts)
                        ?: throw Exception("Failed to decode image")

                    // Rotate bitmap upright if needed
                    val rotatedBitmap = if (rotation != 0) {
                        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                        val rot = Bitmap.createBitmap(decodedBitmap, 0, 0, decodedBitmap.width, decodedBitmap.height, matrix, true)
                        if (rot !== decodedBitmap) decodedBitmap.recycle()
                        rot
                    } else {
                        decodedBitmap
                    }

                    // Scale bitmap if resize is checked
                    val finalBitmap = if (resizeW != null && resizeH != null) {
                        val ratio = minOf(
                            resizeW.toFloat() / rotatedBitmap.width,
                            resizeH.toFloat() / rotatedBitmap.height
                        )
                        val newW = (rotatedBitmap.width * ratio).toInt().coerceAtLeast(1)
                        val newH = (rotatedBitmap.height * ratio).toInt().coerceAtLeast(1)
                        val scaled = Bitmap.createScaledBitmap(rotatedBitmap, newW, newH, true)
                        if (scaled !== rotatedBitmap) rotatedBitmap.recycle()
                        scaled
                    } else {
                        rotatedBitmap
                    }

                    val outName = "${file.nameWithoutExtension}_compressed.$ext"

                    // Use temp file for network upload, otherwise write directly to output dir
                    val useNetwork = networkShareId != null
                    val outFile = if (useNetwork) {
                        File(cacheDir, "img_upload_${System.currentTimeMillis()}_$outName")
                    } else {
                        val outDir = customOutputDir ?: file.parentFile
                        File(outDir, outName)
                    }

                    FileOutputStream(outFile).use { out ->
                        finalBitmap.compress(outputFormat, quality, out)
                    }

                    val compressedSize = outFile.length()

                    if (useNetwork) {
                        uploadCompressedFile(outFile, outName)
                    }

                    finalBitmap.recycle()

                    val savedPercent = if (file.length() > 0) {
                        ((1.0 - compressedSize.toDouble() / file.length()) * 100).toInt()
                    } else 0

                    results.add(CompressionResult(file.name, file.length(), compressedSize, savedPercent))
                } catch (e: Exception) {
                    results.add(CompressionResult(file.name, 0L, 0L, 0, getString(R.string.compress_image_error, file.name, e.message ?: "")))
                }
            }

            withContext(Dispatchers.Main) {
                progressDialog.dismiss()
                btnCompress.isEnabled = true
                showResultsDialog(results)
            }
        }
    }

    private fun showResultsDialog(results: List<CompressionResult>) {
        val dialogView = layoutInflater.inflate(
            if (isTv) R.layout.dialog_image_compress_results_tv
            else R.layout.dialog_image_compress_results,
            null
        )
        val layoutResultsView = dialogView.findViewById<LinearLayout>(R.id.layoutResults)
        val btnClose = dialogView.findViewById<View>(R.id.btnClose)

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnClose.setOnClickListener { dialog.dismiss() }

        if (isTv) {
            val scrollContainer = dialogView.findViewById<androidx.core.widget.NestedScrollView>(R.id.scrollContainer)
            scrollContainer?.setOnKeyListener { _, keyCode, event ->
                if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                    val scrollAmount = (80 * resources.displayMetrics.density).toInt()
                    when (keyCode) {
                        android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                            scrollContainer.smoothScrollBy(0, scrollAmount)
                            true
                        }
                        android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                            if (scrollContainer.scrollY <= 0) false
                            else {
                                scrollContainer.smoothScrollBy(0, -scrollAmount)
                                true
                            }
                        }
                        else -> false
                    }
                } else false
            }
        }

        val primaryColor = if (isTv) getColor(R.color.tv_text_primary) else getColor(R.color.mobile_card_text_primary)
        val secondaryColor = if (isTv) getColor(R.color.tv_text_secondary) else getColor(R.color.mobile_card_text_secondary)
        val successColor = getColor(R.color.ufm_primary)
        val errorColor = getColor(R.color.ufm_denied)

        var totalOriginal = 0L
        var totalCompressed = 0L
        var hasErrors = false

        for ((index, res) in results.withIndex()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 8, 0, 8)
            }

            val nameText = TextView(this).apply {
                text = res.name
                setTextColor(primaryColor)
                textSize = if (isTv) 16f else 14f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            }
            row.addView(nameText)

            val resultText = TextView(this).apply {
                textSize = if (isTv) 14f else 12f
                typeface = Typeface.DEFAULT_BOLD
                if (res.error != null) {
                    text = res.error
                    setTextColor(errorColor)
                } else {
                    text = getString(R.string.compress_image_result, formatSize(res.originalSize), formatSize(res.compressedSize), res.savedPercent)
                    setTextColor(successColor)
                }
            }
            row.addView(resultText)

            layoutResultsView.addView(row)

            if (res.error == null) {
                totalOriginal += res.originalSize
                totalCompressed += res.compressedSize
            } else {
                hasErrors = true
            }

            if (index < results.size - 1) {
                val divider = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1
                    )
                    setBackgroundColor(if (isTv) getColor(R.color.tv_divider) else 0x1A000000.toInt())
                }
                layoutResultsView.addView(divider)
            }
        }

        if (totalOriginal > 0) {
            val totalSavedPercent = ((1.0 - totalCompressed.toDouble() / totalOriginal) * 100).toInt()

            val divider = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1
                ).also { it.setMargins(0, 12, 0, 12) }
                setBackgroundColor(if (isTv) getColor(R.color.tv_divider) else 0x1A000000.toInt())
            }
            layoutResultsView.addView(divider)

            val totalText = TextView(this).apply {
                text = getString(R.string.compress_image_total_saved, formatSize(totalOriginal), formatSize(totalCompressed), totalSavedPercent)
                setTextColor(primaryColor)
                textSize = if (isTv) 18f else 16f
                typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
                setPadding(0, 8, 0, 8)
            }
            layoutResultsView.addView(totalText)
        }

        dialog.show()

        if (!hasErrors) {
            Toast.makeText(this, R.string.compress_image_complete, Toast.LENGTH_SHORT).show()
        } else {
            val firstError = results.firstOrNull { it.error != null }?.error ?: ""
            Toast.makeText(this, firstError, Toast.LENGTH_LONG).show()
        }
    }

    private fun readExifRotation(imageFile: File): Int {
        return try {
            val exif = ExifInterface(imageFile.absolutePath)
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

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val h = options.outHeight
        val w = options.outWidth
        var inSampleSize = 1
        if (h > reqHeight || w > reqWidth) {
            val halfH = h / 2
            val halfW = w / 2
            while (halfH / inSampleSize >= reqHeight && halfW / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private fun detectFormat(file: File): Bitmap.CompressFormat {
        return when (file.extension.lowercase()) {
            "jpg", "jpeg" -> Bitmap.CompressFormat.JPEG
            "png" -> Bitmap.CompressFormat.PNG
            "webp" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    @Suppress("DEPRECATION")
                    Bitmap.CompressFormat.WEBP
                }
            }
            else -> Bitmap.CompressFormat.JPEG
        }
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> getString(R.string.bytes_b)
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> String.format(getString(R.string.q1f_mb), bytes / (1024.0 * 1024.0))
        }
    }

    /** Resolves a share ID to a NetworkShare — checks saved shares first, then paired TV/Phone devices. */
    private fun resolveShareById(id: String): NetworkShare? {
        val fromRepo = NetworkShareRepository.getInstance(this).getById(id)
        if (fromRepo != null) return fromRepo
        val dev = PairingManager.getInstance(this).getPairedDevice(id)
        if (dev != null) return NetworkShare(
            id = dev.deviceId, name = dev.name,
            type = ShareType.TV, host = dev.lastIp, port = dev.lastPort, readOnly = false
        )
        return null
    }

    /**
     * Uploads a compressed image file to the network share.
     * The local temp [file] is deleted after a successful upload.
     */
    private suspend fun uploadCompressedFile(file: File, remoteName: String) {
        var share = resolveShareById(networkShareId ?: return) ?: return
        val netPath = networkPath
        if (share.isServerMode && !netPath.isNullOrEmpty()) {
            val segments = netPath.trimStart('/').split("/", limit = 2)
            share = share.copy(remotePath = "/${segments[0]}")
        }
        val remotePath = if (netPath.isNullOrEmpty()) remoteName else "${netPath}/$remoteName"

        val inp = file.inputStream()
        try {
            when (share.type) {
                ShareType.TV -> TvShareClient.uploadStream(share, remotePath, inp, file.length())
                ShareType.SMB -> SmbShareClient.openOutputStream(share, remotePath)
                    .use { out -> CopyHelper.copy(inp, out) }
                ShareType.FTP -> FtpShareClient.openOutputStream(share, remotePath)
                    .use { out -> CopyHelper.copy(inp, out) }
                ShareType.SFTP, ShareType.SCP -> SshShareClient.openOutputStream(share, remotePath)
                    .use { out -> CopyHelper.copy(inp, out) }
                ShareType.ONEDRIVE -> OnedriveShareClient.openOutputStream(share, remotePath)
                    .use { out -> CopyHelper.copy(inp, out) }
                ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.openOutputStream(share, remotePath)
                    .use { out -> CopyHelper.copy(inp, out) }
                ShareType.DROPBOX -> DropboxShareClient.openOutputStream(share, remotePath)
                    .use { out -> CopyHelper.copy(inp, out) }
                ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.openOutputStream(share, remotePath)
                    .use { out -> CopyHelper.copy(inp, out) }
                ShareType.WEBDAV -> WebDavShareClient.openOutputStream(share, remotePath)
                    .use { out -> CopyHelper.copy(inp, out) }
                ShareType.WEBDAV -> WebDavShareClient.openOutputStream(share, remotePath)
                    .use { out -> CopyHelper.copy(inp, out) }
                ShareType.NFS -> NfsShareClient.openOutputStream(share, remotePath)
                    .use { out -> CopyHelper.copy(inp, out) }
                ShareType.DLNA -> throw UnsupportedOperationException("DLNA is read-only")
            }
        } finally {
            inp.close()
            file.delete()
        }
    }
}
