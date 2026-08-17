package za.kilowatch.ultimatefilemanager.storage

import android.graphics.BitmapFactory
import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.network.NetworkFile
import za.kilowatch.ultimatefilemanager.settings.HiddenFilesManager
import za.kilowatch.ultimatefilemanager.settings.PinnedFilesManager
import za.kilowatch.ultimatefilemanager.settings.ProtectedFilesManager
import za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FilePropertiesBottomSheet : BottomSheetDialogFragment() {

    // Views
    private lateinit var imgPropertiesIcon: ImageView
    private lateinit var txtFilename: TextView
    private lateinit var txtTypeSubtitle: TextView

    private lateinit var rowType: View
    private lateinit var lblType: TextView
    private lateinit var txtTypeValue: TextView

    private lateinit var rowOpensWith: View
    private lateinit var txtOpensWithValue: TextView

    private lateinit var rowLocation: View
    private lateinit var txtLocationValue: TextView

    private lateinit var rowSize: View
    private lateinit var txtSizeValue: TextView

    private lateinit var rowContains: View
    private lateinit var txtContainsValue: TextView

    private lateinit var rowDimensions: View
    private lateinit var txtDimensionsValue: TextView

    private lateinit var dividerDates: View
    private lateinit var tableTimestamps: View
    private lateinit var rowModified: View
    private lateinit var txtModifiedValue: TextView

    private lateinit var dividerAttributes: View
    private lateinit var layoutAttributes: View
    private lateinit var cgAttributes: ChipGroup

    private lateinit var layoutTagsSection: View
    private lateinit var btnEditTags: ImageView
    private lateinit var cgTags: ChipGroup
    private lateinit var txtNoTags: TextView

    private var filePaths: ArrayList<String> = arrayListOf()
    private var isDirList: BooleanArray = booleanArrayOf()
    private var fileSizes: LongArray = longArrayOf()
    private var lastModifiedList: LongArray = longArrayOf()
    private var isNetwork: Boolean = false
    private var parentPathArg: String = ""

    private var calcJob: Job? = null

    companion object {
        const val TAG = "FilePropertiesBottomSheet"

        private const val ARG_FILE_PATHS = "arg_file_paths"
        private const val ARG_IS_DIR_LIST = "arg_is_dir_list"
        private const val ARG_FILE_SIZES = "arg_file_sizes"
        private const val ARG_LAST_MODIFIED_LIST = "arg_last_modified_list"
        private const val ARG_IS_NETWORK = "arg_is_network"
        private const val ARG_PARENT_PATH = "arg_parent_path"

        private const val ARG_FILE_PATH = "arg_file_path"
        private const val ARG_IS_DIRECTORY = "arg_is_directory"
        private const val ARG_FILE_SIZE = "arg_file_size"
        private const val ARG_LAST_MODIFIED = "arg_last_modified"

        fun newInstanceForLocalFiles(files: List<File>): FilePropertiesBottomSheet {
            return FilePropertiesBottomSheet().apply {
                arguments = Bundle().apply {
                    putStringArrayList(ARG_FILE_PATHS, ArrayList(files.map { it.absolutePath }))
                    putBooleanArray(ARG_IS_DIR_LIST, files.map { it.isDirectory }.toBooleanArray())
                    putLongArray(ARG_FILE_SIZES, files.map { if (it.isDirectory) 0L else it.length() }.toLongArray())
                    putLongArray(ARG_LAST_MODIFIED_LIST, files.map { it.lastModified() }.toLongArray())
                    putBoolean(ARG_IS_NETWORK, false)
                    putString(ARG_PARENT_PATH, files.firstOrNull()?.parent ?: "")
                }
            }
        }

        fun newInstanceForNetworkFiles(files: List<NetworkFile>, parentPath: String = ""): FilePropertiesBottomSheet {
            return FilePropertiesBottomSheet().apply {
                arguments = Bundle().apply {
                    putStringArrayList(ARG_FILE_PATHS, ArrayList(files.map { it.path }))
                    putBooleanArray(ARG_IS_DIR_LIST, files.map { it.isDirectory }.toBooleanArray())
                    putLongArray(ARG_FILE_SIZES, files.map { it.size }.toLongArray())
                    putLongArray(ARG_LAST_MODIFIED_LIST, files.map { it.lastModified }.toLongArray())
                    putBoolean(ARG_IS_NETWORK, true)
                    val derivedParent = if (parentPath.isNotEmpty()) parentPath else files.firstOrNull()?.path?.substringBeforeLast('/', "") ?: ""
                    putString(ARG_PARENT_PATH, derivedParent)
                }
            }
        }

        fun newInstance(
            filePath: String,
            isDirectory: Boolean,
            size: Long,
            lastModified: Long,
            isNetwork: Boolean
        ): FilePropertiesBottomSheet {
            return FilePropertiesBottomSheet().apply {
                arguments = Bundle().apply {
                    putStringArrayList(ARG_FILE_PATHS, arrayListOf(filePath))
                    putBooleanArray(ARG_IS_DIR_LIST, booleanArrayOf(isDirectory))
                    putLongArray(ARG_FILE_SIZES, longArrayOf(size))
                    putLongArray(ARG_LAST_MODIFIED_LIST, longArrayOf(lastModified))
                    putBoolean(ARG_IS_NETWORK, isNetwork)
                    putString(ARG_FILE_PATH, filePath)
                    putBoolean(ARG_IS_DIRECTORY, isDirectory)
                    putLong(ARG_FILE_SIZE, size)
                    putLong(ARG_LAST_MODIFIED, lastModified)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            filePaths = it.getStringArrayList(ARG_FILE_PATHS) ?: arrayListOf()
            if (filePaths.isEmpty()) {
                val legacyPath = it.getString(ARG_FILE_PATH)
                if (!legacyPath.isNullOrEmpty()) {
                    filePaths = arrayListOf(legacyPath)
                    isDirList = booleanArrayOf(it.getBoolean(ARG_IS_DIRECTORY, false))
                    fileSizes = longArrayOf(it.getLong(ARG_FILE_SIZE, 0L))
                    lastModifiedList = longArrayOf(it.getLong(ARG_LAST_MODIFIED, 0L))
                }
            } else {
                isDirList = it.getBooleanArray(ARG_IS_DIR_LIST) ?: booleanArrayOf()
                fileSizes = it.getLongArray(ARG_FILE_SIZES) ?: longArrayOf()
                lastModifiedList = it.getLongArray(ARG_LAST_MODIFIED_LIST) ?: longArrayOf()
            }
            isNetwork = it.getBoolean(ARG_IS_NETWORK, false)
            parentPathArg = it.getString(ARG_PARENT_PATH) ?: ""
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): android.app.Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            if (bottomSheet != null) {
                val behavior = BottomSheetBehavior.from(bottomSheet)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
            }
        }
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_file_properties, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        imgPropertiesIcon = view.findViewById(R.id.imgPropertiesIcon)
        txtFilename = view.findViewById(R.id.txtFilename)
        txtTypeSubtitle = view.findViewById(R.id.txtTypeSubtitle)

        rowType = view.findViewById(R.id.rowType)
        lblType = view.findViewById(R.id.lblType)
        txtTypeValue = view.findViewById(R.id.txtTypeValue)

        rowOpensWith = view.findViewById(R.id.rowOpensWith)
        txtOpensWithValue = view.findViewById(R.id.txtOpensWithValue)

        rowLocation = view.findViewById(R.id.rowLocation)
        txtLocationValue = view.findViewById(R.id.txtLocationValue)

        rowSize = view.findViewById(R.id.rowSize)
        txtSizeValue = view.findViewById(R.id.txtSizeValue)

        rowContains = view.findViewById(R.id.rowContains)
        txtContainsValue = view.findViewById(R.id.txtContainsValue)

        rowDimensions = view.findViewById(R.id.rowDimensions)
        txtDimensionsValue = view.findViewById(R.id.txtDimensionsValue)

        dividerDates = view.findViewById(R.id.dividerDates)
        tableTimestamps = view.findViewById(R.id.tableTimestamps)
        rowModified = view.findViewById(R.id.rowModified)
        txtModifiedValue = view.findViewById(R.id.txtModifiedValue)

        dividerAttributes = view.findViewById(R.id.dividerAttributes)
        layoutAttributes = view.findViewById(R.id.layoutAttributes)
        cgAttributes = view.findViewById(R.id.cgAttributes)

        layoutTagsSection = view.findViewById(R.id.layoutTagsSection)
        btnEditTags = view.findViewById(R.id.btnEditTags)
        cgTags = view.findViewById(R.id.cgTags)
        txtNoTags = view.findViewById(R.id.txtNoTags)

        setupWindowsProperties()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        calcJob?.cancel()
    }

    private fun setupWindowsProperties() {
        val count = filePaths.size
        if (count == 0) return

        if (count == 1) {
            val path = filePaths.first()
            val isDirectory = isDirList.firstOrNull() ?: false
            val fileSize = fileSizes.firstOrNull() ?: 0L
            val lastModified = lastModifiedList.firstOrNull() ?: 0L

            val name = if (isNetwork) {
                path.substringAfterLast('/')
            } else {
                File(path).name
            }

            val parentPath = if (parentPathArg.isNotEmpty()) {
                parentPathArg
            } else if (isNetwork) {
                path.substringBeforeLast('/', "")
            } else {
                File(path).parent ?: ""
            }

            // 1. Header
            txtFilename.text = name
            imgPropertiesIcon.setImageResource(resolveIconRes(name, isDirectory, count))

            val (subtitle, fullType) = resolveFileType(name, isDirectory)
            txtTypeSubtitle.text = subtitle

            // 2. General Table
            lblType.text = if (isDirectory) getString(R.string.properties_label_type_general) else getString(R.string.properties_label_type)
            txtTypeValue.text = fullType

            if (!isDirectory) {
                val opensWith = resolveOpensWith(name)
                if (opensWith.isNotEmpty()) {
                    rowOpensWith.visibility = View.VISIBLE
                    txtOpensWithValue.text = opensWith
                } else {
                    rowOpensWith.visibility = View.GONE
                }
            } else {
                rowOpensWith.visibility = View.GONE
            }

            txtLocationValue.text = if (parentPath.isEmpty()) "/" else parentPath

            // 3. Size & Contains & Dimensions
            if (!isDirectory) {
                rowContains.visibility = View.GONE
                txtSizeValue.text = formatWindowsSize(fileSize)

                // Dimensions for images
                val ext = name.substringAfterLast('.').lowercase()
                if (!isNetwork && ext in setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "avif", "jxl", "bmp", "gif")) {
                    rowDimensions.visibility = View.VISIBLE
                    txtDimensionsValue.text = "…"
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            val width: Int
                            val height: Int
                            if (ext == "jxl") {
                                // BitmapFactory cannot read JXL headers; JxlCoder has no getSize().
                                // Decode at thumbnail size — decodeSampled returns proportional dims.
                                val (w, h) = try {
                                    val bytes = java.io.File(path).readBytes()
                                    val thumb = com.awxkee.jxlcoder.JxlCoder.decodeSampled(bytes, 256, 256)
                                    val dims = thumb.width to thumb.height
                                    thumb.recycle()
                                    dims
                                } catch (_: Exception) { -1 to -1 }
                                width = w; height = h
                            } else {
                                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                BitmapFactory.decodeFile(path, options)
                                width = options.outWidth
                                height = options.outHeight
                            }
                            if (width > 0 && height > 0) {
                                val megapixels = (width * height) / 1_000_000.0
                                val mpString = if (megapixels >= 0.1) {
                                    String.format(Locale.getDefault(), " (%.1f MP)", megapixels)
                                } else ""
                                withContext(Dispatchers.Main) {
                                    if (isAdded) {
                                        txtDimensionsValue.text = "${width} x ${height}$mpString"
                                    }
                                }
                            } else {
                                withContext(Dispatchers.Main) {
                                    if (isAdded) rowDimensions.visibility = View.GONE
                                }
                            }
                        } catch (_: Exception) {
                            withContext(Dispatchers.Main) {
                                if (isAdded) rowDimensions.visibility = View.GONE
                            }
                        }
                    }
                } else {
                    rowDimensions.visibility = View.GONE
                }
            } else {
                // Single Folder
                rowDimensions.visibility = View.GONE
                rowContains.visibility = View.VISIBLE

                if (isNetwork) {
                    txtSizeValue.text = getString(R.string.properties_type_folder)
                    txtContainsValue.text = getString(R.string.properties_type_folder)
                } else {
                    txtSizeValue.text = getString(R.string.properties_calculating_size)
                    txtContainsValue.text = getString(R.string.properties_calculating_size)
                    calcJob?.cancel()
                    calcJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                        var totalBytes = 0L
                        var totalFiles = 0
                        var totalFolders = 0
                        val folder = File(path)
                        if (folder.exists() && folder.isDirectory) {
                            folder.walkTopDown().onEnter { true }.forEach { sub ->
                                if (sub != folder) {
                                    if (sub.isDirectory) {
                                        totalFolders++
                                    } else if (sub.isFile) {
                                        totalFiles++
                                        totalBytes += sub.length()
                                    }
                                }
                            }
                        }
                        withContext(Dispatchers.Main) {
                            if (!isAdded) return@withContext
                            txtSizeValue.text = formatWindowsSize(totalBytes)
                            txtContainsValue.text = String.format(Locale.getDefault(), "%d Files, %d Folders", totalFiles, totalFolders)
                        }
                    }
                }
            }

            // 4. Timestamps
            if (lastModified > 0L) {
                dividerDates.visibility = View.VISIBLE
                tableTimestamps.visibility = View.VISIBLE
                try {
                    val sdf = SimpleDateFormat("EEEE, d MMMM yyyy, h:mm:ss a", Locale.getDefault())
                    txtModifiedValue.text = sdf.format(Date(lastModified))
                } catch (e: Exception) {
                    txtModifiedValue.text = ""
                }
            } else {
                dividerDates.visibility = View.GONE
                tableTimestamps.visibility = View.GONE
            }

            // 5. Attributes
            setupAttributes(listOf(path), booleanArrayOf(isDirectory))

            // 6. Tags (Single file only)
            if (!isDirectory) {
                layoutTagsSection.visibility = View.VISIBLE
                loadTags(path)
                btnEditTags.setOnClickListener { showEditTagsDialog(path) }
            } else {
                layoutTagsSection.visibility = View.GONE
            }

        } else {
            // Multiple Items Selected
            val directFiles = isDirList.count { !it }
            val directFolders = isDirList.count { it }

            // 1. Header
            txtFilename.text = getString(R.string.properties_multiple_items_title, count)
            imgPropertiesIcon.setImageResource(R.drawable.ic_folder)
            val directBreakdown = String.format(Locale.getDefault(), "%d files, %d folders", directFiles, directFolders)
            txtTypeSubtitle.text = "${getString(R.string.properties_type_multiple)} ($directBreakdown)"

            // 2. General Table
            lblType.text = getString(R.string.properties_label_type_general)
            txtTypeValue.text = getString(R.string.properties_type_multiple)
            rowOpensWith.visibility = View.GONE
            rowDimensions.visibility = View.GONE

            val parentPath = if (parentPathArg.isNotEmpty()) {
                parentPathArg
            } else if (isNetwork) {
                filePaths.first().substringBeforeLast('/', "")
            } else {
                File(filePaths.first()).parent ?: ""
            }
            txtLocationValue.text = if (parentPath.isEmpty()) "/" else parentPath

            rowContains.visibility = View.VISIBLE
            txtContainsValue.text = directBreakdown

            // 3. Size & Scanning
            if (isNetwork) {
                val totalKnownSize = fileSizes.sum()
                txtSizeValue.text = formatWindowsSize(totalKnownSize)
            } else {
                txtSizeValue.text = getString(R.string.properties_calculating_size)
                calcJob?.cancel()
                calcJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    var totalBytes = 0L
                    var totalFiles = 0
                    var totalFolders = 0

                    for (i in filePaths.indices) {
                        val p = filePaths[i]
                        val isD = isDirList.getOrNull(i) ?: false
                        val f = File(p)
                        if (!f.exists()) continue

                        if (isD || f.isDirectory) {
                            totalFolders++
                            f.walkTopDown().onEnter { true }.forEach { sub ->
                                if (sub != f) {
                                    if (sub.isDirectory) {
                                        totalFolders++
                                    } else if (sub.isFile) {
                                        totalFiles++
                                        totalBytes += sub.length()
                                    }
                                }
                            }
                        } else {
                            totalFiles++
                            totalBytes += f.length()
                        }
                    }

                    withContext(Dispatchers.Main) {
                        if (!isAdded) return@withContext
                        txtSizeValue.text = formatWindowsSize(totalBytes)
                        txtContainsValue.text = String.format(Locale.getDefault(), "%d Files, %d Folders", totalFiles, totalFolders)
                    }
                }
            }

            // Timestamps: for multiple items, hide modified date or show common date if identical
            val allSameDate = lastModifiedList.isNotEmpty() && lastModifiedList.all { it == lastModifiedList[0] && it > 0L }
            if (allSameDate) {
                dividerDates.visibility = View.VISIBLE
                tableTimestamps.visibility = View.VISIBLE
                val sdf = SimpleDateFormat("EEEE, d MMMM yyyy, h:mm:ss a", Locale.getDefault())
                txtModifiedValue.text = sdf.format(Date(lastModifiedList[0]))
            } else {
                dividerDates.visibility = View.GONE
                tableTimestamps.visibility = View.GONE
            }

            // 4. Attributes
            setupAttributes(filePaths, isDirList)

            // Tags hidden for multi items
            layoutTagsSection.visibility = View.GONE
        }
    }

    private fun setupAttributes(paths: List<String>, isDirArray: BooleanArray) {
        cgAttributes.removeAllViews()
        val context = context ?: return

        // Check read-only / writable
        if (!isNetwork) {
            val allFiles = paths.map { File(it) }
            val hasReadOnly = allFiles.any { it.exists() && !it.canWrite() }
            val allReadOnly = allFiles.all { it.exists() && !it.canWrite() }

            if (allReadOnly) {
                addAttributeChip(getString(R.string.properties_attr_read_only))
            } else if (!hasReadOnly) {
                addAttributeChip(getString(R.string.properties_attr_read_write))
            }
        }

        // Check hidden
        val hasHidden = paths.any { p ->
            val name = p.substringAfterLast(if (isNetwork) '/' else File.separatorChar)
            name.startsWith(".") || HiddenFilesManager.isPathJunkOrHidden(p)
        }
        if (hasHidden) {
            addAttributeChip(getString(R.string.properties_attr_hidden))
        }

        // Check protected
        val hasProtected = paths.any { p ->
            ProtectedFilesManager.isProtected(context, p)
        }
        if (hasProtected) {
            addAttributeChip(getString(R.string.properties_attr_protected))
        }

        // Check pinned
        val hasPinned = paths.any { p ->
            PinnedFilesManager.isPinned(context, p)
        }
        if (hasPinned) {
            addAttributeChip(getString(R.string.properties_attr_pinned))
        }

        // If no attributes, show read/write
        if (cgAttributes.childCount == 0) {
            addAttributeChip(getString(R.string.properties_attr_read_write))
        }
    }

    private fun addAttributeChip(label: String) {
        val context = context ?: return
        val chip = Chip(context).apply {
            text = label
            isClickable = false
            isFocusable = false
            setEnsureMinTouchTargetSize(false)
            chipMinHeight = resources.displayMetrics.density * 28f
            textSize = 12f
            setChipBackgroundColorResource(android.R.color.transparent)
            setChipStrokeColorResource(com.google.android.material.R.color.material_on_surface_stroke)
            chipStrokeWidth = resources.displayMetrics.density * 1f
        }
        cgAttributes.addView(chip)
    }

    private fun formatWindowsSize(bytes: Long): String {
        val context = context ?: return "$bytes bytes"
        val human = Formatter.formatFileSize(context, bytes)
        val exact = String.format(Locale.getDefault(), "%,d bytes", bytes)
        return "$human ($exact)"
    }

    private fun resolveFileType(name: String, isDirectory: Boolean): Pair<String, String> {
        if (isDirectory) {
            val folderStr = getString(R.string.properties_type_folder)
            return Pair(folderStr, folderStr)
        }

        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) {
            return Pair("File", "File")
        }

        return when (ext) {
            "jpg", "jpeg" -> Pair("JPEG image (.${ext})", "JPEG Image (.${ext})")
            "png" -> Pair("PNG image (.png)", "PNG Image (.png)")
            "gif" -> Pair("GIF image (.gif)", "GIF Image (.gif)")
            "webp" -> Pair("WEBP image (.webp)", "WEBP Image (.webp)")
            "svg" -> Pair("SVG image (.svg)", "Scalable Vector Graphics (.svg)")
            "bmp" -> Pair("BMP image (.bmp)", "Bitmap Image (.bmp)")
            "heic", "heif", "avif" -> Pair("${ext.uppercase()} image (.${ext})", "${ext.uppercase()} Image (.${ext})")
            "jxl" -> Pair("JPEG XL image (.jxl)", "JPEG XL Image (.jxl)")
            "pdf" -> Pair("PDF Document (.pdf)", "Adobe Acrobat Document (.pdf)")
            "doc", "docx" -> Pair("Word Document (.${ext})", "Microsoft Word Document (.${ext})")
            "xls", "xlsx", "csv" -> Pair("Excel Worksheet (.${ext})", "Microsoft Excel Worksheet (.${ext})")
            "ppt", "pptx" -> Pair("PowerPoint Presentation (.${ext})", "Microsoft PowerPoint Presentation (.${ext})")
            "txt" -> Pair("Text Document (.txt)", "Plain Text Document (.txt)")
            "json" -> Pair("JSON File (.json)", "JSON Source File (.json)")
            "xml" -> Pair("XML Document (.xml)", "XML Document (.xml)")
            "html", "htm" -> Pair("HTML Document (.${ext})", "HTML Document (.${ext})")
            "md", "markdown" -> Pair("Markdown Document (.${ext})", "Markdown Document (.${ext})")
            "zip" -> Pair("ZIP archive (.zip)", "Compressed (zipped) Folder (.zip)")
            "7z" -> Pair("7Z archive (.7z)", "7-Zip Archive (.7z)")
            "rar" -> Pair("RAR archive (.rar)", "WinRAR Archive (.rar)")
            "tar", "gz", "bz2", "xz" -> Pair("${ext.uppercase()} archive (.${ext})", "${ext.uppercase()} Archive (.${ext})")
            "mp4" -> Pair("MP4 video (.mp4)", "MP4 Video (.mp4)")
            "mkv" -> Pair("MKV video (.mkv)", "Matroska Video (.mkv)")
            "avi" -> Pair("AVI video (.avi)", "Audio Video Interleave (.avi)")
            "mov" -> Pair("MOV video (.mov)", "QuickTime Movie (.mov)")
            "mp3" -> Pair("MP3 audio (.mp3)", "MP3 Audio (.mp3)")
            "flac" -> Pair("FLAC audio (.flac)", "Free Lossless Audio (.flac)")
            "wav" -> Pair("WAV audio (.wav)", "Waveform Audio (.wav)")
            "m4a", "aac", "ogg" -> Pair("${ext.uppercase()} audio (.${ext})", "${ext.uppercase()} Audio (.${ext})")
            "apk" -> Pair("Android Package (.apk)", "Android Application Package (.apk)")
            "aab" -> Pair("Android App Bundle (.aab)", "Android App Bundle (.aab)")
            else -> Pair("${ext.uppercase()} File (.${ext})", "${ext.uppercase()} File (.${ext})")
        }
    }

    private fun resolveOpensWith(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            ext in FileViewerRouter.IMAGE_EXTENSIONS -> getString(R.string.properties_viewer_photo)
            FileViewerRouter.isVideo(ext) || FileViewerRouter.isAudio(ext) -> getString(R.string.properties_viewer_player)
            ext in FileViewerRouter.TEXT_EXTENSIONS || FileViewerRouter.isDotConfigFile(name) -> getString(R.string.properties_viewer_text)
            ext in setOf("zip", "7z", "rar", "tar", "gz", "bz2", "xz", "iso", "jar") -> getString(R.string.properties_viewer_archive)
            ext in setOf("xls", "xlsx", "csv", "xlsm", "xltx", "xltm", "xlt", "xlsb") -> getString(R.string.properties_viewer_spreadsheet)
            ext == "pdf" -> getString(R.string.properties_viewer_pdf)
            else -> getString(R.string.properties_viewer_default)
        }
    }

    private fun resolveIconRes(name: String, isDirectory: Boolean, count: Int): Int {
        if (count > 1) return R.drawable.ic_folder
        if (isDirectory) return R.drawable.ic_folder

        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            ext in FileViewerRouter.IMAGE_EXTENSIONS -> R.drawable.ic_file_image
            FileViewerRouter.isVideo(ext) -> R.drawable.ic_file_video
            FileViewerRouter.isAudio(ext) -> R.drawable.ic_file_audio
            ext in setOf("zip", "7z", "rar", "tar", "gz", "bz2", "xz", "iso", "jar") -> R.drawable.ic_file_archive
            ext == "pdf" -> R.drawable.ic_file_pdf
            ext in setOf("doc", "docx") -> R.drawable.ic_file_word
            ext in setOf("xls", "xlsx", "csv", "xlsm", "xltx", "xltm", "xlt", "xlsb") -> R.drawable.ic_file_spreadsheet
            ext in setOf("ppt", "pptx") -> R.drawable.ic_file_presentation
            ext in FileViewerRouter.TEXT_EXTENSIONS || FileViewerRouter.isDotConfigFile(name) -> R.drawable.ic_file_code
            ext in setOf("apk", "aab", "xapk", "apks") -> R.drawable.ic_file_apk
            else -> R.drawable.ic_file_generic
        }
    }

    private fun loadTags(singlePath: String) {
        cgTags.removeAllViews()
        val tags = FileTagsManager.getTags(requireContext(), singlePath)
        if (tags.isEmpty()) {
            txtNoTags.visibility = View.VISIBLE
            cgTags.visibility = View.GONE
        } else {
            txtNoTags.visibility = View.GONE
            cgTags.visibility = View.VISIBLE

            for (tag in tags) {
                val chip = LayoutInflater.from(requireContext())
                    .inflate(R.layout.item_tag_chip, cgTags, false) as Chip
                chip.text = "#$tag"
                cgTags.addView(chip)
            }
        }
    }

    private fun showEditTagsDialog(singlePath: String) {
        val currentTags = FileTagsManager.getTags(requireContext(), singlePath)
        val allCreatedTags = FileTagsManager.getAllCreatedTags(requireContext())

        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_edit_tags, null)
        val edtInput = dialogView.findViewById<TextInputEditText>(R.id.edtTagsInput)
        val txtHeader = dialogView.findViewById<TextView>(R.id.txtCreatedTagsHeader)
        val cgExisting = dialogView.findViewById<ChipGroup>(R.id.cgExistingTags)

        val chipsMap = mutableMapOf<String, Chip>()

        if (allCreatedTags.isNotEmpty()) {
            txtHeader.visibility = View.VISIBLE
            for (tag in allCreatedTags) {
                val chip = LayoutInflater.from(requireContext())
                    .inflate(R.layout.item_tag_chip, cgExisting, false) as Chip
                chip.text = "#$tag"
                cgExisting.addView(chip)
                chipsMap[tag] = chip
            }
        }

        fun updateChipsFromInput(inputStr: String) {
            val parsedTags = FileTagsManager.sanitizeAndSplit(inputStr).toSet()
            for ((cleanTag, chip) in chipsMap) {
                chip.setOnCheckedChangeListener(null)
                chip.isChecked = parsedTags.contains(cleanTag)
                chip.setOnCheckedChangeListener { _, isChecked ->
                    val currentText = edtInput.text?.toString() ?: ""
                    val tagsList = FileTagsManager.sanitizeAndSplit(currentText).toMutableList()
                    if (isChecked) {
                        if (!tagsList.contains(cleanTag)) {
                            tagsList.add(cleanTag)
                        }
                    } else {
                        tagsList.remove(cleanTag)
                    }
                    edtInput.setText(tagsList.joinToString(", "))
                    edtInput.setSelection(edtInput.text?.length ?: 0)
                }
            }
        }

        val initialText = currentTags.joinToString(", ")
        edtInput.setText(initialText)
        edtInput.setSelection(edtInput.text?.length ?: 0)
        updateChipsFromInput(initialText)

        edtInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                updateChipsFromInput(s?.toString() ?: "")
            }
        })

        val dialogTheme = com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog

        MaterialAlertDialogBuilder(requireContext(), dialogTheme)
            .setTitle(getString(R.string.edit_tags_title))
            .setView(dialogView)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Done") { _, _ ->
                val textInput = edtInput.text?.toString() ?: ""
                val newTags = FileTagsManager.sanitizeAndSplit(textInput).toSet()
                FileTagsManager.saveTags(requireContext(), singlePath, newTags)
                loadTags(singlePath)
            }
            .show()
    }
}
