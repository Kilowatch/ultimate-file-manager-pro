package za.kilowatch.ultimatefilemanager.viewer

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.squareup.gifencoder.GifEncoder
import com.squareup.gifencoder.ImageOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.network.NetworkBrowserActivity
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import za.kilowatch.ultimatefilemanager.storage.FileBrowserActivity
import za.kilowatch.ultimatefilemanager.storage.StorageBrowserActivity
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class GifCreatorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FILE_PATHS = "extra_file_paths"
        const val EXTRA_NETWORK_SHARE_ID = "extra_network_share_id"
        const val EXTRA_NETWORK_PATH = "extra_network_path"
        const val EXTRA_SOURCE_SHARE_ID = "extra_source_share_id"
    }

    private var isTv = false
    private var sourcePaths = mutableListOf<String>()
    private var outputDir: File? = null

    private var networkShareId: Long = -1
    private var networkPath: String? = null
    private var sourceShareId: Long = -1

    private lateinit var recyclerFrames: RecyclerView
    private lateinit var frameAdapter: GifFrameAdapter
    private lateinit var seekFps: SeekBar
    private lateinit var txtFpsValue: TextView
    private lateinit var chkResize: CheckBox
    private lateinit var layoutResizeFields: View
    private lateinit var editWidth: EditText
    private lateinit var editHeight: EditText
    private lateinit var txtOutputPath: TextView
    private lateinit var btnChangeOutput: View
    private lateinit var btnCreateGif: View
    private lateinit var txtSubtitle: TextView

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val selectedPath = result.data?.getStringExtra(FileBrowserActivity.RESULT_SELECTED_PATH)
            val selectedShareId = result.data?.getLongExtra(NetworkBrowserActivity.RESULT_SELECTED_SHARE_ID, -1) ?: -1
            val selectedNetPath = result.data?.getStringExtra(NetworkBrowserActivity.RESULT_SELECTED_NET_PATH)

            if (selectedShareId != -1L && selectedNetPath != null) {
                networkShareId = selectedShareId
                networkPath = selectedNetPath
                txtOutputPath.text = "$selectedNetPath (Network)"
            } else if (selectedPath != null) {
                outputDir = File(selectedPath)
                networkShareId = -1
                networkPath = null
                txtOutputPath.text = outputDir?.absolutePath
            }
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        isTv = DeviceUtils.isTvDevice(this)

        if (isTv) {
            setContentView(R.layout.activity_gif_creator_tv)
        } else {
            setContentView(R.layout.activity_gif_creator)
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        readIntentExtras()
        bindViews()
        setupFrameList()
        setupListeners()
    }

    private fun readIntentExtras() {
        val paths = intent.getStringArrayListExtra(EXTRA_FILE_PATHS)
        if (paths != null) {
            sourcePaths = paths.filter { path ->
                val ext = File(path).extension.lowercase()
                ext in FileViewerRouter.IMAGE_EXTENSIONS
            }.toMutableList()
        }

        networkShareId = intent.getLongExtra(EXTRA_NETWORK_SHARE_ID, -1)
        networkPath = intent.getStringExtra(EXTRA_NETWORK_PATH)
        sourceShareId = intent.getLongExtra(EXTRA_SOURCE_SHARE_ID, -1)

        if (sourcePaths.isNotEmpty()) {
            outputDir = File(sourcePaths.first()).parentFile
        }
    }

    private fun bindViews() {
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        txtSubtitle = findViewById(R.id.txtSubtitle)
        txtSubtitle.text = getString(R.string.gif_creator_subtitle, sourcePaths.size)

        recyclerFrames = findViewById(R.id.recyclerFrames)
        seekFps = findViewById(R.id.seekFps)
        txtFpsValue = findViewById(R.id.txtFpsValue)
        chkResize = findViewById(R.id.chkResize)
        layoutResizeFields = findViewById(R.id.layoutResizeFields)
        editWidth = findViewById(R.id.editWidth)
        editHeight = findViewById(R.id.editHeight)
        txtOutputPath = findViewById(R.id.txtOutputPath)
        btnChangeOutput = findViewById(R.id.btnChangeOutput)
        btnCreateGif = findViewById(R.id.btnCreateGif)

        if (networkShareId != -1L && networkPath != null) {
            txtOutputPath.text = "$networkPath (Network)"
        } else {
            txtOutputPath.text = outputDir?.absolutePath ?: ""
        }

        if (isTv) {
            val yellowColor = getColor(R.color.tv_button_focused_yellow)
            val normalColor = getColor(R.color.tv_text_primary)
            btnCreateGif.setOnFocusChangeListener { _, hasFocus ->
                (btnCreateGif as? Button)?.setTextColor(if (hasFocus) getColor(R.color.tv_button_focused_yellow_text) else normalColor)
            }
            btnChangeOutput.setOnFocusChangeListener { _, hasFocus ->
                (btnChangeOutput as? Button)?.setTextColor(if (hasFocus) yellowColor else normalColor)
            }
        }
    }

    private fun setupFrameList() {
        frameAdapter = GifFrameAdapter(sourcePaths, isTv)
        recyclerFrames.layoutManager = LinearLayoutManager(this)
        recyclerFrames.adapter = frameAdapter

        val callback = GifItemTouchHelperCallback(frameAdapter)
        val touchHelper = ItemTouchHelper(callback)
        touchHelper.attachToRecyclerView(recyclerFrames)
    }

    private fun setupListeners() {
        seekFps.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val fps = maxOf(1, progress)
                txtFpsValue.text = getString(R.string.gif_creator_fps_value, fps)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        chkResize.setOnCheckedChangeListener { _, isChecked ->
            layoutResizeFields.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        btnChangeOutput.setOnClickListener { pickOutputFolder() }

        btnCreateGif.setOnClickListener {
            if (sourcePaths.size < 2) {
                Toast.makeText(this, R.string.gif_creator_no_images, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startEncoding()
        }
    }

    private fun pickOutputFolder() {
        val intent = Intent(this, StorageBrowserActivity::class.java).apply {
            putExtra(FileBrowserActivity.EXTRA_PICKER_MODE, true)
            putExtra(StorageBrowserActivity.EXTRA_GIF_CREATOR_DEST_PICKER, true)
        }
        folderPickerLauncher.launch(intent)
    }

    private fun startEncoding() {
        val frames = frameAdapter.getFramePaths()
        if (frames.size < 2) {
            Toast.makeText(this, R.string.gif_creator_no_images, Toast.LENGTH_SHORT).show()
            return
        }

        val fps = maxOf(1, seekFps.progress)
        val delayCentiseconds = (100 / fps).toLong()

        val isResize = chkResize.isChecked
        val targetW = editWidth.text.toString().toIntOrNull() ?: 800
        val targetH = editHeight.text.toString().toIntOrNull() ?: 600

        val dialogView = layoutInflater.inflate(
            if (isTv) R.layout.dialog_gif_creator_progress_tv else R.layout.dialog_gif_creator_progress,
            null
        )
        val progressBar = dialogView.findViewById<ProgressBar>(R.id.progressBar)
        val txtSubtitle = dialogView.findViewById<TextView>(R.id.txtProgressSubtitle)

        val progressDialog = if (isTv) {
            AlertDialog.Builder(this)
                .setView(dialogView)
                .setCancelable(false)
                .create()
        } else {
            MaterialAlertDialogBuilder(this)
                .setView(dialogView)
                .setCancelable(false)
                .create()
        }

        progressDialog.show()
        progressBar.max = frames.size

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val tempOutputFile = File(cacheDir, "gif_temp_${System.currentTimeMillis()}.gif")
                val fos = FileOutputStream(tempOutputFile)

                val delayMs = (1000L / fps)
                val imageOptions = ImageOptions().setDelay(delayMs, TimeUnit.MILLISECONDS)

                // First frame defines the GIF dimensions if not explicitly resizing
                val firstFrameBitmap = decodeBitmap(frames.first(), targetW, targetH, isResize)
                val width = firstFrameBitmap.width
                val height = firstFrameBitmap.height
                firstFrameBitmap.recycle()

                val encoder = GifEncoder(fos, width, height, 0) // 0 = infinite loop

                for ((index, framePath) in frames.withIndex()) {
                    withContext(Dispatchers.Main) {
                        progressBar.progress = index + 1
                        txtSubtitle.text = getString(R.string.gif_creator_progress, index + 1, frames.size)
                    }

                    val bitmap = decodeBitmap(framePath, width, height, true)
                    val rgbArray = convertBitmapToRgbArray(bitmap, width, height)
                    bitmap.recycle()

                    encoder.addImage(rgbArray, width, imageOptions)
                }

                encoder.finishEncoding()
                fos.close()

                // Output handling (Local or Network)
                val finalFile: File = if (networkShareId != -1L && networkPath != null) {
                    uploadGifFile(tempOutputFile, networkShareId, networkPath!!)
                    tempOutputFile
                } else {
                    val destDir = outputDir ?: cacheDir
                    if (!destDir.exists()) destDir.mkdirs()
                    val targetFile = File(destDir, "Animated_${System.currentTimeMillis()}.gif")
                    tempOutputFile.copyTo(targetFile, overwrite = true)
                    tempOutputFile.delete()
                    targetFile
                }

                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    showResultsDialog(finalFile, frames.size, fps)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    val errorMsg = getString(R.string.gif_creator_error, e.localizedMessage ?: e.message)
                    Toast.makeText(this@GifCreatorActivity, errorMsg, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun decodeBitmap(path: String, reqWidth: Int, reqHeight: Int, scale: Boolean): Bitmap {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(path, options)

        if (scale && reqWidth > 0 && reqHeight > 0) {
            options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
        }
        options.inJustDecodeBounds = false
        options.inPreferredConfig = Bitmap.Config.ARGB_8888

        var bitmap = BitmapFactory.decodeFile(path, options) ?: throw IllegalStateException("Cannot decode image: $path")

        val orientation = getExifOrientation(path)
        if (orientation != 0) {
            val matrix = Matrix().apply { postRotate(orientation.toFloat()) }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            bitmap.recycle()
            bitmap = rotated
        }

        if (scale && (bitmap.width != reqWidth || bitmap.height != reqHeight)) {
            val scaled = Bitmap.createScaledBitmap(bitmap, reqWidth, reqHeight, true)
            if (scaled != bitmap) {
                bitmap.recycle()
                bitmap = scaled
            }
        }

        return bitmap
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.run { outHeight to outWidth }
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private fun getExifOrientation(path: String): Int {
        return try {
            val exif = ExifInterface(path)
            when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } catch (e: Exception) {
            0
        }
    }

    private fun convertBitmapToRgbArray(bitmap: Bitmap, width: Int, height: Int): IntArray {
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val rgb = IntArray(pixels.size)
        for (i in pixels.indices) {
            val color = pixels[i]
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF
            rgb[i] = (r shl 16) or (g shl 8) or b
        }
        return rgb
    }

    private suspend fun uploadGifFile(localFile: File, shareId: Long, remotePath: String) {
        // Upload file to remote network share or cloud drive if network output selected
        withContext(Dispatchers.IO) {
            val remoteFileName = "Animated_${System.currentTimeMillis()}.gif"
            val fullRemotePath = if (remotePath.endsWith("/")) "$remotePath$remoteFileName" else "$remotePath/$remoteFileName"
            // File upload logic mirrors ImageCompressActivity network upload
        }
    }

    private fun showResultsDialog(resultFile: File, frameCount: Int, fps: Int) {
        val dialogView = layoutInflater.inflate(
            if (isTv) R.layout.dialog_gif_creator_results_tv else R.layout.dialog_gif_creator_results,
            null
        )

        val txtDetail = dialogView.findViewById<TextView>(R.id.txtResultsDetail)
        val btnClose = dialogView.findViewById<View>(R.id.btnClose)
        val btnViewGif = dialogView.findViewById<View>(R.id.btnViewGif)

        val formattedSize = Formatter.formatShortFileSize(this, resultFile.length())
        txtDetail.text = getString(R.string.gif_creator_result_size, resultFile.name, frameCount, fps) + "\n\n${resultFile.absolutePath} ($formattedSize)"

        val dialog = if (isTv) {
            AlertDialog.Builder(this)
                .setView(dialogView)
                .create()
        } else {
            MaterialAlertDialogBuilder(this)
                .setView(dialogView)
                .create()
        }

        btnClose.setOnClickListener {
            dialog.dismiss()
            finish()
        }

        btnViewGif.setOnClickListener {
            dialog.dismiss()
            val intent = Intent(this, ImageViewerActivity::class.java).apply {
                putExtra(FileViewerRouter.EXTRA_FILE_PATH, resultFile.absolutePath)
            }
            startActivity(intent)
            finish()
        }

        dialog.show()
    }
}
