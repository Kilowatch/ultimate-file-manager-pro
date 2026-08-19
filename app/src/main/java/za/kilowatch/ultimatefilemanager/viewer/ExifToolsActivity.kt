package za.kilowatch.ultimatefilemanager.viewer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
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
import coil3.load
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import za.kilowatch.ultimatefilemanager.settings.ThemeHelper
import za.kilowatch.ultimatefilemanager.storage.FileBrowserActivity
import za.kilowatch.ultimatefilemanager.storage.StorageBrowserActivity
import za.kilowatch.ultimatefilemanager.util.ExifMetadataDetails
import za.kilowatch.ultimatefilemanager.util.ExifPrivacyManager
import za.kilowatch.ultimatefilemanager.util.ExifPrivacyOptions
import za.kilowatch.ultimatefilemanager.util.ExifRenamePreviewItem
import za.kilowatch.ultimatefilemanager.util.ExifRenameResolver
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Mobile-only Activity for Photo EXIF Inspection, Privacy Scrubbing, and Batch EXIF Renaming.
 */
class ExifToolsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FILE_PATHS = "extra_file_paths"
    }

    private val files = mutableListOf<File>()
    private var selectedIndex = 0
    private var customOutputDir: File? = null

    // UI References
    private lateinit var txtPhotoCount: TextView
    private lateinit var recyclerThumbs: RecyclerView
    private lateinit var thumbAdapter: ExifThumbAdapter
    private lateinit var tabLayout: TabLayout

    // Tab Containers
    private lateinit var layoutInspect: LinearLayout
    private lateinit var layoutClean: LinearLayout
    private lateinit var layoutRename: LinearLayout

    // Inspect Tab Views
    private lateinit var txtCoordinates: TextView
    private lateinit var layoutLocationActions: LinearLayout
    private lateinit var btnViewMap: MaterialButton
    private lateinit var btnRemoveGpsSingle: MaterialButton
    private lateinit var txtCameraMakeModel: TextView
    private lateinit var txtLensModel: TextView
    private lateinit var txtExposureSummary: TextView
    private lateinit var txtOpticsExtra: TextView
    private lateinit var txtDateTaken: TextView
    private lateinit var txtDateDigitized: TextView
    private lateinit var txtDimensions: TextView
    private lateinit var txtSoftware: TextView
    private lateinit var txtAuthorCopyright: TextView
    private lateinit var txtUserComment: TextView
    private lateinit var btnWipeSingle: MaterialButton

    // Clean Tab Views
    private lateinit var rgCleanPresets: RadioGroup
    private lateinit var rbCleanFull: RadioButton
    private lateinit var rbCleanGpsOnly: RadioButton
    private lateinit var rbCleanCustom: RadioButton
    private lateinit var layoutCustomCleanOptions: LinearLayout
    private lateinit var chkCleanGps: CheckBox
    private lateinit var chkCleanDevice: CheckBox
    private lateinit var chkCleanAuthor: CheckBox
    private lateinit var chkCleanDates: CheckBox
    private lateinit var chkCleanCamera: CheckBox
    private lateinit var rgDestination: RadioGroup
    private lateinit var rbDestOverwrite: RadioButton
    private lateinit var rbDestCopy: RadioButton
    private lateinit var rbDestFolder: RadioButton
    private lateinit var layoutOutputFolder: LinearLayout
    private lateinit var txtOutputFolderPath: TextView
    private lateinit var btnPickOutputFolder: MaterialButton
    private lateinit var btnStartClean: MaterialButton

    // Rename Tab Views
    private lateinit var edtPattern: TextInputEditText
    private lateinit var recyclerRenamePreview: RecyclerView
    private lateinit var renamePreviewAdapter: ExifRenamePreviewAdapter
    private lateinit var btnStartRename: MaterialButton

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val path = result.data?.getStringExtra(FileBrowserActivity.RESULT_SELECTED_LOCAL_PATH)
            if (path != null) {
                customOutputDir = File(path)
                txtOutputFolderPath.text = path
                layoutOutputFolder.visibility = View.VISIBLE
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
        setContentView(R.layout.activity_exif_tools)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        initViews()
        loadInputFiles()
    }

    private fun initViews() {
        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }
        txtPhotoCount = findViewById(R.id.txtPhotoCount)
        recyclerThumbs = findViewById(R.id.recyclerThumbs)
        tabLayout = findViewById(R.id.tabLayout)

        layoutInspect = findViewById(R.id.layoutInspect)
        layoutClean = findViewById(R.id.layoutClean)
        layoutRename = findViewById(R.id.layoutRename)

        // Inspect
        txtCoordinates = findViewById(R.id.txtCoordinates)
        layoutLocationActions = findViewById(R.id.layoutLocationActions)
        btnViewMap = findViewById(R.id.btnViewMap)
        btnRemoveGpsSingle = findViewById(R.id.btnRemoveGpsSingle)
        txtCameraMakeModel = findViewById(R.id.txtCameraMakeModel)
        txtLensModel = findViewById(R.id.txtLensModel)
        txtExposureSummary = findViewById(R.id.txtExposureSummary)
        txtOpticsExtra = findViewById(R.id.txtOpticsExtra)
        txtDateTaken = findViewById(R.id.txtDateTaken)
        txtDateDigitized = findViewById(R.id.txtDateDigitized)
        txtDimensions = findViewById(R.id.txtDimensions)
        txtSoftware = findViewById(R.id.txtSoftware)
        txtAuthorCopyright = findViewById(R.id.txtAuthorCopyright)
        txtUserComment = findViewById(R.id.txtUserComment)
        btnWipeSingle = findViewById(R.id.btnWipeSingle)

        // Clean
        rgCleanPresets = findViewById(R.id.rgCleanPresets)
        rbCleanFull = findViewById(R.id.rbCleanFull)
        rbCleanGpsOnly = findViewById(R.id.rbCleanGpsOnly)
        rbCleanCustom = findViewById(R.id.rbCleanCustom)
        layoutCustomCleanOptions = findViewById(R.id.layoutCustomCleanOptions)
        chkCleanGps = findViewById(R.id.chkCleanGps)
        chkCleanDevice = findViewById(R.id.chkCleanDevice)
        chkCleanAuthor = findViewById(R.id.chkCleanAuthor)
        chkCleanDates = findViewById(R.id.chkCleanDates)
        chkCleanCamera = findViewById(R.id.chkCleanCamera)
        rgDestination = findViewById(R.id.rgDestination)
        rbDestOverwrite = findViewById(R.id.rbDestOverwrite)
        rbDestCopy = findViewById(R.id.rbDestCopy)
        rbDestFolder = findViewById(R.id.rbDestFolder)
        layoutOutputFolder = findViewById(R.id.layoutOutputFolder)
        txtOutputFolderPath = findViewById(R.id.txtOutputFolderPath)
        btnPickOutputFolder = findViewById(R.id.btnPickOutputFolder)
        btnStartClean = findViewById(R.id.btnStartClean)

        // Rename
        edtPattern = findViewById(R.id.edtPattern)
        recyclerRenamePreview = findViewById(R.id.recyclerRenamePreview)
        btnStartRename = findViewById(R.id.btnStartRename)

        setupTabs()
        setupInspectActions()
        setupCleanActions()
        setupRenameActions()
    }

    private fun loadInputFiles() {
        val paths = intent.getStringArrayListExtra(EXTRA_FILE_PATHS) ?: emptyList()
        files.clear()
        files.addAll(paths.map { File(it) }.filter { it.exists() && it.isFile })

        if (files.isEmpty()) {
            Toast.makeText(this, R.string.compress_image_no_images, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        txtPhotoCount.text = getString(R.string.exif_photo_count, files.size)
        btnStartRename.text = getString(R.string.exif_btn_start_rename, files.size)

        thumbAdapter = ExifThumbAdapter(files, selectedIndex) { pos ->
            selectedIndex = pos
            thumbAdapter.setSelectedPosition(pos)
            displayCurrentPhotoMetadata()
        }
        recyclerThumbs.adapter = thumbAdapter

        displayCurrentPhotoMetadata()
        updateRenamePreview()
    }

    private fun setupTabs() {
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> {
                        layoutInspect.visibility = View.VISIBLE
                        layoutClean.visibility = View.GONE
                        layoutRename.visibility = View.GONE
                    }
                    1 -> {
                        layoutInspect.visibility = View.GONE
                        layoutClean.visibility = View.VISIBLE
                        layoutRename.visibility = View.GONE
                    }
                    2 -> {
                        layoutInspect.visibility = View.GONE
                        layoutClean.visibility = View.GONE
                        layoutRename.visibility = View.VISIBLE
                        updateRenamePreview()
                    }
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) = Unit
            override fun onTabReselected(tab: TabLayout.Tab?) = Unit
        })
    }

    private fun displayCurrentPhotoMetadata() {
        if (selectedIndex !in files.indices) return
        val currentFile = files[selectedIndex]

        lifecycleScope.launch(Dispatchers.IO) {
            val details = ExifPrivacyManager.readFullDetails(currentFile)
            val modifiedDateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(currentFile.lastModified()))

            withContext(Dispatchers.Main) {
                // Location
                if (details.hasGps && details.latitude != null && details.longitude != null) {
                    val altText = details.altitude?.let { " (Alt: ${String.format(Locale.US, "%.1fm", it)})" } ?: ""
                    txtCoordinates.text = "${details.formattedCoordinates}$altText"
                    layoutLocationActions.visibility = View.VISIBLE

                    btnViewMap.setOnClickListener {
                        val geoUri = Uri.parse("geo:${details.latitude},${details.longitude}?q=${details.latitude},${details.longitude}(${Uri.encode(currentFile.name)})")
                        val mapIntent = Intent(Intent.ACTION_VIEW, geoUri)
                        if (mapIntent.resolveActivity(packageManager) != null) {
                            startActivity(mapIntent)
                        } else {
                            // Fallback to browser Google Maps
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=${details.latitude},${details.longitude}")))
                        }
                    }

                    btnRemoveGpsSingle.setOnClickListener {
                        lifecycleScope.launch(Dispatchers.IO) {
                            val success = ExifPrivacyManager.removeGpsOnly(currentFile)
                            withContext(Dispatchers.Main) {
                                if (success) {
                                    Toast.makeText(this@ExifToolsActivity, getString(R.string.exif_single_photo_cleaned, currentFile.name), Toast.LENGTH_SHORT).show()
                                    thumbAdapter.notifyItemChanged(selectedIndex)
                                    displayCurrentPhotoMetadata()
                                }
                            }
                        }
                    }
                } else {
                    txtCoordinates.text = getString(R.string.exif_no_location)
                    layoutLocationActions.visibility = View.GONE
                }

                // Camera & Optics
                val makeModel = listOfNotNull(details.cameraMake, details.cameraModel).joinToString(" ").takeIf { it.isNotBlank() }
                    ?: getString(R.string.exif_no_camera)
                txtCameraMakeModel.text = makeModel

                txtLensModel.visibility = if (details.lensModel != null) View.VISIBLE else View.GONE
                txtLensModel.text = details.lensModel ?: ""

                val exposureParts = listOfNotNull(
                    details.focalLength,
                    details.aperture,
                    details.shutterSpeed,
                    details.iso?.let { "ISO $it" }
                )
                txtExposureSummary.text = if (exposureParts.isNotEmpty()) exposureParts.joinToString(" · ") else "—"

                val extraParts = listOfNotNull(
                    details.flash?.let { "Flash: $it" },
                    details.whiteBalance?.let { "WB: $it" }
                )
                txtOpticsExtra.visibility = if (extraParts.isNotEmpty()) View.VISIBLE else View.GONE
                txtOpticsExtra.text = extraParts.joinToString(" · ")

                // Date
                txtDateTaken.text = details.dateTaken?.let { "Taken: $it" } ?: getString(R.string.exif_no_date)
                txtDateDigitized.text = details.dateDigitized?.let { "Digitized: $it" } ?: "Modified: $modifiedDateStr"

                // Device & Personal
                txtDimensions.text = "Dimensions: ${details.formattedDimensions}"
                txtSoftware.visibility = if (details.software != null) View.VISIBLE else View.GONE
                txtSoftware.text = "Software: ${details.software ?: ""}"

                val authorCopy = listOfNotNull(
                    details.artist?.let { "Artist: $it" },
                    details.copyright?.let { "Copyright: $it" }
                ).joinToString(" · ")
                txtAuthorCopyright.visibility = if (authorCopy.isNotBlank()) View.VISIBLE else View.GONE
                txtAuthorCopyright.text = authorCopy

                txtUserComment.visibility = if (details.userComment != null) View.VISIBLE else View.GONE
                txtUserComment.text = "Comment: ${details.userComment ?: ""}"
            }
        }
    }

    private fun setupInspectActions() {
        btnWipeSingle.setOnClickListener {
            if (selectedIndex !in files.indices) return@setOnClickListener
            val target = files[selectedIndex]

            MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
                .setTitle(R.string.exif_confirm_overwrite_title)
                .setMessage(getString(R.string.exif_confirm_overwrite_msg, 1))
                .setPositiveButton(R.string.compress_btn_start) { _, _ ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        val options = ExifPrivacyOptions(
                            stripGps = true,
                            stripDevice = true,
                            stripAuthor = true,
                            stripDates = false,
                            stripCameraSettings = false
                        )
                        val success = ExifPrivacyManager.stripMetadata(target, target, options)
                        withContext(Dispatchers.Main) {
                            if (success) {
                                Toast.makeText(this@ExifToolsActivity, getString(R.string.exif_single_photo_cleaned, target.name), Toast.LENGTH_SHORT).show()
                                thumbAdapter.notifyItemChanged(selectedIndex)
                                displayCurrentPhotoMetadata()
                            }
                        }
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun setupCleanActions() {
        rgCleanPresets.setOnCheckedChangeListener { _, checkedId ->
            layoutCustomCleanOptions.visibility = if (checkedId == R.id.rbCleanCustom) View.VISIBLE else View.GONE
        }

        rgDestination.setOnCheckedChangeListener { _, checkedId ->
            layoutOutputFolder.visibility = if (checkedId == R.id.rbDestFolder) View.VISIBLE else View.GONE
        }

        btnPickOutputFolder.setOnClickListener {
            val intent = Intent(this, StorageBrowserActivity::class.java).apply {
                putExtra(StorageBrowserActivity.EXTRA_IMAGE_COMPRESS_DEST_PICKER, true)
            }
            folderPickerLauncher.launch(intent)
        }

        btnStartClean.setOnClickListener {
            val options = when (rgCleanPresets.checkedRadioButtonId) {
                R.id.rbCleanGpsOnly -> ExifPrivacyOptions(
                    stripGps = true,
                    stripDevice = false,
                    stripAuthor = false,
                    stripDates = false,
                    stripCameraSettings = false
                )
                R.id.rbCleanCustom -> ExifPrivacyOptions(
                    stripGps = chkCleanGps.isChecked,
                    stripDevice = chkCleanDevice.isChecked,
                    stripAuthor = chkCleanAuthor.isChecked,
                    stripDates = chkCleanDates.isChecked,
                    stripCameraSettings = chkCleanCamera.isChecked
                )
                else -> ExifPrivacyOptions( // Full Wipe
                    stripGps = true,
                    stripDevice = true,
                    stripAuthor = true,
                    stripDates = false,
                    stripCameraSettings = false
                )
            }

            val isOverwrite = rgDestination.checkedRadioButtonId == R.id.rbDestOverwrite
            if (isOverwrite) {
                MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
                    .setTitle(R.string.exif_confirm_overwrite_title)
                    .setMessage(getString(R.string.exif_confirm_overwrite_msg, files.size))
                    .setPositiveButton(R.string.compress_btn_start) { _, _ ->
                        performBatchClean(options, null)
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            } else {
                val targetDir = if (rgDestination.checkedRadioButtonId == R.id.rbDestFolder) {
                    customOutputDir
                } else {
                    null // Will generate _cleaned in same directory
                }
                performBatchClean(options, targetDir)
            }
        }
    }

    private fun performBatchClean(options: ExifPrivacyOptions, outputDir: File?) {
        val progressDialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setTitle(R.string.exif_tab_clean)
            .setMessage(getString(R.string.exif_cleaning_progress, 1, files.size))
            .setCancelable(false)
            .create()

        progressDialog.show()

        lifecycleScope.launch(Dispatchers.IO) {
            val (successCount, errors) = ExifPrivacyManager.batchStripMetadata(files, options, outputDir) { current, total ->
                lifecycleScope.launch(Dispatchers.Main) {
                    progressDialog.setMessage(getString(R.string.exif_cleaning_progress, current, total))
                }
            }

            withContext(Dispatchers.Main) {
                progressDialog.dismiss()
                if (successCount > 0) {
                    Toast.makeText(this@ExifToolsActivity, getString(R.string.exif_clean_success, successCount), Toast.LENGTH_LONG).show()
                    thumbAdapter.notifyDataSetChanged()
                    displayCurrentPhotoMetadata()
                }
                if (errors.isNotEmpty()) {
                    Toast.makeText(this@ExifToolsActivity, getString(R.string.exif_clean_error, errors.first()), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setupRenameActions() {
        renamePreviewAdapter = ExifRenamePreviewAdapter(emptyList())
        recyclerRenamePreview.adapter = renamePreviewAdapter

        edtPattern.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                updateRenamePreview()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        // Preset Chips
        findViewById<Chip>(R.id.chipPresetDateOriginal).setOnClickListener {
            edtPattern.setText(ExifRenameResolver.PRESET_DATE_ORIGINAL)
        }
        findViewById<Chip>(R.id.chipPresetDateOnly).setOnClickListener {
            edtPattern.setText(ExifRenameResolver.PRESET_DATE_ONLY)
        }
        findViewById<Chip>(R.id.chipPresetCameraDate).setOnClickListener {
            edtPattern.setText(ExifRenameResolver.PRESET_CAMERA_DATE)
        }

        // Token Chips
        fun insertToken(token: String) {
            val start = edtPattern.selectionStart.coerceAtLeast(0)
            val end = edtPattern.selectionEnd.coerceAtLeast(0)
            edtPattern.text?.replace(Math.min(start, end), Math.max(start, end), token)
        }

        findViewById<Chip>(R.id.chipTokenYear).setOnClickListener { insertToken("{YYYY}") }
        findViewById<Chip>(R.id.chipTokenMonth).setOnClickListener { insertToken("{MM}") }
        findViewById<Chip>(R.id.chipTokenDay).setOnClickListener { insertToken("{DD}") }
        findViewById<Chip>(R.id.chipTokenTime).setOnClickListener { insertToken("{hh}{mm}{ss}") }
        findViewById<Chip>(R.id.chipTokenModel).setOnClickListener { insertToken("{MODEL}") }
        findViewById<Chip>(R.id.chipTokenOriginal).setOnClickListener { insertToken("{ORIGINAL}") }
        findViewById<Chip>(R.id.chipTokenSeq).setOnClickListener { insertToken("{#}") }

        btnStartRename.setOnClickListener {
            performBatchRename()
        }
    }

    private fun updateRenamePreview() {
        val pattern = edtPattern.text?.toString()?.trim().orEmpty().ifEmpty { "{ORIGINAL}" }
        lifecycleScope.launch(Dispatchers.IO) {
            val previewItems = ExifRenameResolver.generatePreview(files, pattern)
            withContext(Dispatchers.Main) {
                renamePreviewAdapter.updateItems(previewItems)
            }
        }
    }

    private fun performBatchRename() {
        val items = renamePreviewAdapter.getItems()
        if (items.isEmpty()) return

        val progressDialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setTitle(R.string.exif_tab_rename)
            .setMessage(getString(R.string.exif_renaming_progress, 1, items.size))
            .setCancelable(false)
            .create()

        progressDialog.show()

        lifecycleScope.launch(Dispatchers.IO) {
            val (successCount, errors) = ExifRenameResolver.executeBatchRename(items) { current, total ->
                lifecycleScope.launch(Dispatchers.Main) {
                    progressDialog.setMessage(getString(R.string.exif_renaming_progress, current, total))
                }
            }

            withContext(Dispatchers.Main) {
                progressDialog.dismiss()
                if (successCount > 0) {
                    Toast.makeText(this@ExifToolsActivity, getString(R.string.exif_rename_success, successCount), Toast.LENGTH_LONG).show()
                    // Reload file references
                    files.clear()
                    files.addAll(items.map { it.targetFile })
                    thumbAdapter.notifyDataSetChanged()
                    displayCurrentPhotoMetadata()
                    updateRenamePreview()
                }
                if (errors.isNotEmpty()) {
                    Toast.makeText(this@ExifToolsActivity, getString(R.string.exif_rename_error, errors.first()), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ── Adapters ─────────────────────────────────────────────────────────

    private inner class ExifThumbAdapter(
        private val list: List<File>,
        private var selectedPos: Int,
        private val onSelect: (Int) -> Unit
    ) : RecyclerView.Adapter<ExifThumbAdapter.ThumbViewHolder>() {

        fun setSelectedPosition(pos: Int) {
            val oldPos = selectedPos
            selectedPos = pos
            notifyItemChanged(oldPos)
            notifyItemChanged(selectedPos)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ThumbViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_exif_photo_thumb, parent, false)
            return ThumbViewHolder(view)
        }

        override fun onBindViewHolder(holder: ThumbViewHolder, position: Int) {
            val file = list[position]
            holder.imgThumb.load(file)

            val isSelected = position == selectedPos
            holder.cardThumb.strokeWidth = if (isSelected) (3 * resources.displayMetrics.density).toInt() else 0

            // Check GPS asynchronously or via fast tag inspection
            lifecycleScope.launch(Dispatchers.IO) {
                val hasGps = try {
                    val exif = androidx.exifinterface.media.ExifInterface(file.absolutePath)
                    exif.latLong != null
                } catch (_: Exception) { false }

                withContext(Dispatchers.Main) {
                    holder.txtGpsBadge.visibility = if (hasGps) View.VISIBLE else View.GONE
                }
            }

            holder.itemView.setOnClickListener { onSelect(position) }
        }

        override fun getItemCount(): Int = list.size

        inner class ThumbViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val cardThumb: MaterialCardView = view.findViewById(R.id.cardThumb)
            val imgThumb: ImageView = view.findViewById(R.id.imgThumb)
            val txtGpsBadge: TextView = view.findViewById(R.id.txtGpsBadge)
        }
    }

    private class ExifRenamePreviewAdapter(
        private var items: List<ExifRenamePreviewItem>
    ) : RecyclerView.Adapter<ExifRenamePreviewAdapter.PreviewViewHolder>() {

        fun updateItems(newItems: List<ExifRenamePreviewItem>) {
            items = newItems
            notifyDataSetChanged()
        }

        fun getItems(): List<ExifRenamePreviewItem> = items

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PreviewViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_exif_preview, parent, false)
            return PreviewViewHolder(view)
        }

        override fun onBindViewHolder(holder: PreviewViewHolder, position: Int) {
            val item = items[position]
            holder.imgItemThumb.load(item.originalFile)
            holder.txtOriginalName.text = item.originalName
            holder.txtNewName.text = item.newName

            if (item.hasConflict) {
                holder.txtConflictBadge.visibility = View.VISIBLE
            } else {
                holder.txtConflictBadge.visibility = View.GONE
            }
        }

        override fun getItemCount(): Int = items.size

        class PreviewViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val imgItemThumb: ImageView = view.findViewById(R.id.imgItemThumb)
            val txtOriginalName: TextView = view.findViewById(R.id.txtOriginalName)
            val txtNewName: TextView = view.findViewById(R.id.txtNewName)
            val txtConflictBadge: TextView = view.findViewById(R.id.txtConflictBadge)
        }
    }
}
