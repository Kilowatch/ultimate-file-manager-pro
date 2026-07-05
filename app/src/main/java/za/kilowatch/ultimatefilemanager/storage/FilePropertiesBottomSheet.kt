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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FilePropertiesBottomSheet : BottomSheetDialogFragment() {

    private lateinit var txtDateTime: TextView
    private lateinit var txtFilename: TextView
    private lateinit var txtDirectory: TextView
    private lateinit var txtFolderTitle: TextView
    private lateinit var txtFileDetails: TextView
    private lateinit var btnEditTags: ImageView
    private lateinit var cgTags: ChipGroup
    private lateinit var txtNoTags: TextView

    private lateinit var filePath: String
    private var isDirectory: Boolean = false
    private var fileSize: Long = 0L
    private var lastModified: Long = 0L
    private var isNetwork: Boolean = false

    companion object {
        const val TAG = "FilePropertiesBottomSheet"
        
        private const val ARG_FILE_PATH = "arg_file_path"
        private const val ARG_IS_DIRECTORY = "arg_is_directory"
        private const val ARG_FILE_SIZE = "arg_file_size"
        private const val ARG_LAST_MODIFIED = "arg_last_modified"
        private const val ARG_IS_NETWORK = "arg_is_network"

        fun newInstance(
            filePath: String,
            isDirectory: Boolean,
            size: Long,
            lastModified: Long,
            isNetwork: Boolean
        ): FilePropertiesBottomSheet {
            return FilePropertiesBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_FILE_PATH, filePath)
                    putBoolean(ARG_IS_DIRECTORY, isDirectory)
                    putLong(ARG_FILE_SIZE, size)
                    putLong(ARG_LAST_MODIFIED, lastModified)
                    putBoolean(ARG_IS_NETWORK, isNetwork)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            filePath = it.getString(ARG_FILE_PATH) ?: ""
            isDirectory = it.getBoolean(ARG_IS_DIRECTORY, false)
            fileSize = it.getLong(ARG_FILE_SIZE, 0L)
            lastModified = it.getLong(ARG_LAST_MODIFIED, 0L)
            isNetwork = it.getBoolean(ARG_IS_NETWORK, false)
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

        txtDateTime = view.findViewById(R.id.txtDateTime)
        txtFilename = view.findViewById(R.id.txtFilename)
        txtDirectory = view.findViewById(R.id.txtDirectory)
        txtFolderTitle = view.findViewById(R.id.txtFolderTitle)
        txtFileDetails = view.findViewById(R.id.txtFileDetails)
        btnEditTags = view.findViewById(R.id.btnEditTags)
        cgTags = view.findViewById(R.id.cgTags)
        txtNoTags = view.findViewById(R.id.txtNoTags)

        setupMetadata()
        loadTags()

        val editClickListener = View.OnClickListener { showEditTagsDialog() }
        btnEditTags.setOnClickListener(editClickListener)
    }

    private fun setupMetadata() {
        val name = if (isNetwork) {
            filePath.substringAfterLast('/')
        } else {
            File(filePath).name
        }

        val parentPath = if (isNetwork) {
            filePath.substringBeforeLast('/', "")
        } else {
            File(filePath).parent ?: ""
        }

        val parentName = if (isNetwork) {
            if (parentPath.isEmpty() || parentPath == "/") "Network Root" else parentPath.substringAfterLast('/')
        } else {
            File(filePath).parentFile?.name ?: "Storage"
        }

        txtFilename.text = name
        txtDirectory.text = parentPath
        txtFolderTitle.text = parentName

        // Format Date
        if (lastModified > 0L) {
            try {
                val sdf = SimpleDateFormat("EEEE, d MMMM yyyy • h:mm a", Locale.getDefault())
                txtDateTime.text = sdf.format(Date(lastModified))
            } catch (e: Exception) {
                txtDateTime.text = ""
            }
        } else {
            txtDateTime.text = ""
        }

        // Format Size & Details
        val formattedSize = Formatter.formatFileSize(requireContext(), fileSize)
        txtFileDetails.text = formattedSize

        // Resolve Image Dimensions & Megapixels if it is local
        if (!isNetwork && !isDirectory) {
            val ext = name.substringAfterLast('.').lowercase()
            if (ext in setOf("jpg", "jpeg", "png", "webp", "heic", "heif")) {
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(filePath, options)
                        val width = options.outWidth
                        val height = options.outHeight

                        withContext(Dispatchers.Main) {
                            if (width > 0 && height > 0) {
                                val megapixels = (width * height) / 1_000_000.0
                                val mpString = if (megapixels >= 0.1) {
                                    "${Math.round(megapixels)}MP"
                                } else ""

                                txtFileDetails.text = buildString {
                                    append(formattedSize)
                                    append("  |  ")
                                    append(width)
                                    append("x")
                                    append(height)
                                    if (mpString.isNotEmpty()) {
                                        append("  |  ")
                                        append(mpString)
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) {
                        // ignore and stick to standard file size
                    }
                }
            }
        }
    }

    private fun loadTags() {
        cgTags.removeAllViews()
        val tags = FileTagsManager.getTags(requireContext(), filePath)
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

    private fun showEditTagsDialog() {
        val currentTags = FileTagsManager.getTags(requireContext(), filePath)
        val allCreatedTags = FileTagsManager.getAllCreatedTags(requireContext())

        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_edit_tags, null)
        val edtInput = dialogView.findViewById<TextInputEditText>(R.id.edtTagsInput)
        val txtHeader = dialogView.findViewById<TextView>(R.id.txtCreatedTagsHeader)
        val cgExisting = dialogView.findViewById<ChipGroup>(R.id.cgExistingTags)

        val chipsMap = mutableMapOf<String, Chip>()

        // Populate existing tags flow if there are any
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

        // Helper to update chip states reactively without triggers loops
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

        // Prefill with current tags (comma separated)
        val initialText = currentTags.joinToString(", ")
        edtInput.setText(initialText)
        edtInput.setSelection(edtInput.text?.length ?: 0)
        
        // Set initial checked states
        updateChipsFromInput(initialText)

        // Set up reactive text listener
        edtInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                updateChipsFromInput(s?.toString() ?: "")
            }
        })

        val dialogTheme = com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog

        MaterialAlertDialogBuilder(requireContext(), dialogTheme)
            .setTitle("Edit Tags")
            .setView(dialogView)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Done") { _, _ ->
                val textInput = edtInput.text?.toString() ?: ""
                val newTags = FileTagsManager.sanitizeAndSplit(textInput).toSet()
                FileTagsManager.saveTags(requireContext(), filePath, newTags)
                loadTags()
            }
            .show()
    }
}
