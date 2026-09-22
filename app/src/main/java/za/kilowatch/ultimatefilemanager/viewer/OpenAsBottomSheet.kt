package za.kilowatch.ultimatefilemanager.viewer

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import java.io.File

/**
 * Universal "Open As..." sheet & dialog for Mobile and Android TV.
 *
 * Allows power users to override default file associations and open ANY file
 * under 9 distinct viewing modalities (Text, Hex, Presentation, Spreadsheet,
 * Image, Audio, Video, Archive, or External System Chooser).
 */
class OpenAsBottomSheet : BottomSheetDialogFragment() {

    private var filePath: String? = null
    private var fileName: String? = null
    private var isNetwork: Boolean = false
    private var isTv: Boolean = false

    data class OpenAsOption(
        val id: String,
        val titleRes: Int,
        val descRes: Int,
        val iconRes: Int
    )

    companion object {
        const val TAG = "OpenAsBottomSheet"
        private const val ARG_FILE_PATH = "arg_file_path"
        private const val ARG_FILE_NAME = "arg_file_name"
        private const val ARG_IS_NETWORK = "arg_is_network"

        const val ID_TEXT = "open_as_text"
        const val ID_HEX = "open_as_hex"
        const val ID_PRESENTATION = "open_as_presentation"
        const val ID_SPREADSHEET = "open_as_spreadsheet"
        const val ID_IMAGE = "open_as_image"
        const val ID_AUDIO = "open_as_audio"
        const val ID_VIDEO = "open_as_video"
        const val ID_ARCHIVE = "open_as_archive"
        const val ID_EXTERNAL = "open_as_external"

        fun newInstance(
            filePath: String,
            fileName: String? = null,
            isNetwork: Boolean = false
        ): OpenAsBottomSheet {
            return OpenAsBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_FILE_PATH, filePath)
                    putString(ARG_FILE_NAME, fileName ?: File(filePath).name)
                    putBoolean(ARG_IS_NETWORK, isNetwork)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        filePath = arguments?.getString(ARG_FILE_PATH)
        fileName = arguments?.getString(ARG_FILE_NAME)
        isNetwork = arguments?.getBoolean(ARG_IS_NETWORK, false) ?: false
        isTv = DeviceUtils.isTvDevice(requireContext())
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        isTv = DeviceUtils.isTvDevice(context)
        if (isTv) {
            val tvDialog = Dialog(context).apply {
                window?.apply {
                    setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                    setLayout(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }
            }
            return tvDialog
        }

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
        val layoutRes = if (isTv) R.layout.dialog_open_as_tv else R.layout.dialog_open_as
        return inflater.inflate(layoutRes, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val txtFileName = view.findViewById<TextView>(R.id.txtFileName)
        txtFileName.text = fileName ?: filePath?.let { File(it).name } ?: ""

        val rvCategories = view.findViewById<RecyclerView>(R.id.rvCategories)
        rvCategories.layoutManager = LinearLayoutManager(context)

        val options = listOf(
            OpenAsOption(ID_TEXT, R.string.open_as_text, R.string.open_as_text_desc, R.drawable.ic_file_text),
            OpenAsOption(ID_HEX, R.string.open_as_hex, R.string.open_as_hex_desc, R.drawable.ic_file_code),
            OpenAsOption(ID_PRESENTATION, R.string.open_as_presentation, R.string.open_as_presentation_desc, R.drawable.ic_file_presentation),
            OpenAsOption(ID_SPREADSHEET, R.string.open_as_spreadsheet, R.string.open_as_spreadsheet_desc, R.drawable.ic_file_spreadsheet),
            OpenAsOption(ID_IMAGE, R.string.open_as_image, R.string.open_as_image_desc, R.drawable.ic_file_image),
            OpenAsOption(ID_AUDIO, R.string.open_as_audio, R.string.open_as_audio_desc, R.drawable.ic_file_audio),
            OpenAsOption(ID_VIDEO, R.string.open_as_video, R.string.open_as_video_desc, R.drawable.ic_file_video),
            OpenAsOption(ID_ARCHIVE, R.string.open_as_archive, R.string.open_as_archive_desc, R.drawable.ic_file_archive),
            OpenAsOption(ID_EXTERNAL, R.string.open_as_other, R.string.open_as_other_desc, R.drawable.ic_apps)
        )

        rvCategories.adapter = OpenAsAdapter(options) { option ->
            handleCategorySelected(option.id)
            dismiss()
        }

        view.findViewById<MaterialButton?>(R.id.btnCancel)?.setOnClickListener {
            dismiss()
        }
    }

    private fun handleCategorySelected(categoryId: String) {
        val path = filePath ?: return
        val file = File(path)
        val name = fileName ?: file.name
        val ctx = context ?: return

        when (categoryId) {
            ID_TEXT -> {
                val intent = Intent(ctx, TextViewerActivity::class.java).apply {
                    putExtra(FileViewerRouter.EXTRA_FILE_PATH, file.absolutePath)
                    putExtra(FileViewerRouter.EXTRA_FILE_NAME, name)
                }
                ctx.startActivity(intent)
            }
            ID_HEX -> {
                val intent = Intent(ctx, TextViewerActivity::class.java).apply {
                    putExtra(FileViewerRouter.EXTRA_FILE_PATH, file.absolutePath)
                    putExtra(FileViewerRouter.EXTRA_FILE_NAME, name)
                    putExtra(TextViewerActivity.EXTRA_FORCE_HEX, true)
                }
                ctx.startActivity(intent)
            }
            ID_PRESENTATION -> {
                val intent = Intent(ctx, PresentationViewerActivity::class.java).apply {
                    putExtra(FileViewerRouter.EXTRA_FILE_PATH, file.absolutePath)
                    putExtra(FileViewerRouter.EXTRA_FILE_NAME, name)
                }
                ctx.startActivity(intent)
            }
            ID_SPREADSHEET -> {
                val intent = Intent(ctx, SpreadsheetViewerActivity::class.java).apply {
                    putExtra(FileViewerRouter.EXTRA_FILE_PATH, file.absolutePath)
                    putExtra(FileViewerRouter.EXTRA_FILE_NAME, name)
                }
                ctx.startActivity(intent)
            }
            ID_IMAGE -> {
                val intent = Intent(ctx, ImageViewerActivity::class.java).apply {
                    putExtra(FileViewerRouter.EXTRA_FILE_PATH, file.absolutePath)
                    putExtra(FileViewerRouter.EXTRA_FILE_NAME, name)
                }
                ctx.startActivity(intent)
            }
            ID_AUDIO -> {
                FileViewerRouter.openInPlayer(ctx, file, forceOpen = true)
            }
            ID_VIDEO -> {
                FileViewerRouter.openInPlayer(ctx, file, forceOpen = true)
            }
            ID_ARCHIVE -> {
                val ext = file.extension.lowercase()
                val sevenZipExtensions = setOf("7z", "rar", "tar", "gz", "svgz", "bz2", "xz", "zst", "lz4", "iso")
                if (ext in sevenZipExtensions) {
                    val intent = Intent(ctx, SevenZipViewerActivity::class.java).apply {
                        putExtra(FileViewerRouter.EXTRA_FILE_PATH, file.absolutePath)
                        putExtra(FileViewerRouter.EXTRA_FILE_NAME, name)
                    }
                    ctx.startActivity(intent)
                } else {
                    val intent = Intent(ctx, ZipViewerActivity::class.java).apply {
                        putExtra(FileViewerRouter.EXTRA_FILE_PATH, file.absolutePath)
                        putExtra(FileViewerRouter.EXTRA_FILE_NAME, name)
                    }
                    ctx.startActivity(intent)
                }
            }
            ID_EXTERNAL -> {
                FileViewerRouter.showOpenWithDialog(ctx, file, isNetwork = isNetwork)
            }
        }
    }

    private class OpenAsAdapter(
        private val items: List<OpenAsOption>,
        private val onItemClick: (OpenAsOption) -> Unit
    ) : RecyclerView.Adapter<OpenAsAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val imgIcon: ImageView = view.findViewById(R.id.imgIcon)
            val txtTitle: TextView = view.findViewById(R.id.txtTitle)
            val txtDescription: TextView = view.findViewById(R.id.txtDescription)
            val itemContainer: View = view.findViewById(R.id.itemContainer)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(
                R.layout.item_open_as_category,
                parent,
                false
            )
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.imgIcon.setImageResource(item.iconRes)
            holder.txtTitle.setText(item.titleRes)
            holder.txtDescription.setText(item.descRes)

            holder.itemContainer.setOnClickListener {
                onItemClick(item)
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
