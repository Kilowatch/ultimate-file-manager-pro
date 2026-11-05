package za.kilowatch.ultimatefilemanager.viewer.openwith

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.text.format.Formatter
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.launch
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import za.kilowatch.ultimatefilemanager.viewer.DefaultOpenManager
import za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter
import java.io.File

/**
 * Universal "Open With" dialog & bottom sheet adhering strictly to UFMStandard.
 * Displays all installed applications capable of opening the target file without caps or hidden players.
 */
class OpenWithBottomSheet : BottomSheetDialogFragment() {

    private var targetUri: Uri? = null
    private var mimeType: String = "*/*"
    private var fileName: String = ""
    private var filePath: String? = null
    private var fileExtension: String = ""
    private var isNetwork: Boolean = false
    private var isTv: Boolean = false

    private lateinit var rvApps: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var emptyStateContainer: View
    private lateinit var adapter: OpenWithAdapter
    private var switchAlways: MaterialSwitch? = null
    private var searchContainer: View? = null
    private var edtSearch: EditText? = null

    companion object {
        const val TAG = "OpenWithBottomSheet"

        private const val ARG_URI = "arg_uri"
        private const val ARG_MIME = "arg_mime"
        private const val ARG_FILE_NAME = "arg_file_name"
        private const val ARG_FILE_PATH = "arg_file_path"
        private const val ARG_EXTENSION = "arg_extension"
        private const val ARG_IS_NETWORK = "arg_is_network"

        fun newInstance(
            uri: Uri,
            mimeType: String,
            fileName: String,
            file: File? = null,
            isNetwork: Boolean = false,
            extension: String = ""
        ): OpenWithBottomSheet {
            return OpenWithBottomSheet().apply {
                arguments = Bundle().apply {
                    putParcelable(ARG_URI, uri)
                    putString(ARG_MIME, mimeType)
                    putString(ARG_FILE_NAME, fileName)
                    putString(ARG_FILE_PATH, file?.absolutePath)
                    putString(ARG_EXTENSION, extension.ifBlank { file?.extension ?: fileName.substringAfterLast('.', "") })
                    putBoolean(ARG_IS_NETWORK, isNetwork)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            @Suppress("DEPRECATION")
            targetUri = it.getParcelable(ARG_URI)
            mimeType = it.getString(ARG_MIME, "*/*")
            fileName = it.getString(ARG_FILE_NAME, "")
            filePath = it.getString(ARG_FILE_PATH)
            fileExtension = it.getString(ARG_EXTENSION, "")
            isNetwork = it.getBoolean(ARG_IS_NETWORK, false)
        }
        isTv = DeviceUtils.isTvDevice(requireContext())
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        isTv = DeviceUtils.isTvDevice(context)

        if (isTv) {
            // Android TV Centered Glass Dialog
            return Dialog(context, R.style.UFM_Dialog).apply {
                window?.apply {
                    setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                    setLayout(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    setGravity(Gravity.CENTER)
                }
            }
        }

        // Mobile Glass Bottom Sheet Dialog
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

    override fun onStart() {
        super.onStart()
        if (isTv) {
            dialog?.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setLayout(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setGravity(Gravity.CENTER)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val layoutRes = if (isTv) R.layout.dialog_open_with_tv else R.layout.dialog_open_with
        return inflater.inflate(layoutRes, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val uri = targetUri ?: run {
            dismiss()
            return
        }

        val targetFile = filePath?.let { File(it) }

        // 1. Bind Header Details
        val txtFileName = view.findViewById<TextView>(R.id.txtFileName)
        val txtFileDetails = view.findViewById<TextView?>(R.id.txtFileDetails)
        val imgFileIcon = view.findViewById<ImageView?>(R.id.imgFileIcon)

        txtFileName.text = fileName

        val sizeString = if (targetFile != null && targetFile.exists() && targetFile.length() > 0) {
            Formatter.formatFileSize(requireContext(), targetFile.length())
        } else null

        val detailsText = when {
            sizeString != null && mimeType.isNotBlank() -> "$sizeString • $mimeType"
            sizeString != null -> sizeString
            else -> mimeType
        }
        txtFileDetails?.text = detailsText

        // Set contextual file icon
        if (imgFileIcon != null) {
            val ext = fileExtension.lowercase()
            val iconRes = when {
                FileViewerRouter.isVideo(ext) -> R.drawable.ic_file_video
                FileViewerRouter.isAudio(ext) -> R.drawable.ic_file_audio
                ext in FileViewerRouter.IMAGE_EXTENSIONS -> R.drawable.ic_file_image
                ext == "pdf" -> R.drawable.ic_file_pdf
                ext in za.kilowatch.ultimatefilemanager.archive.ArchiveManager.SUPPORTED_ARCHIVE_EXTENSIONS -> R.drawable.ic_file_archive
                else -> R.drawable.ic_file_generic
            }
            imgFileIcon.setImageResource(iconRes)
        }

        // 2. Initialize Views
        rvApps = view.findViewById(R.id.rvApps)
        progressBar = view.findViewById(R.id.progressBar)
        emptyStateContainer = view.findViewById(R.id.emptyStateContainer)
        searchContainer = view.findViewById(R.id.searchContainer)
        edtSearch = view.findViewById(R.id.edtSearch)
        switchAlways = view.findViewById(R.id.switchAlways)
        val cardAlways = view.findViewById<View?>(R.id.cardAlways)
        val txtAlwaysSubtitle = view.findViewById<TextView?>(R.id.txtAlwaysSubtitle)

        val cleanExt = fileExtension.lowercase().trimStart('.')
        if (cleanExt.isNotBlank()) {
            txtAlwaysSubtitle?.text = getString(R.string.open_with_always_desc, cleanExt)
        }
        cardAlways?.setOnClickListener {
            switchAlways?.toggle()
        }

        // 3. Setup RecyclerView & Span count
        val spanCount = if (isTv) {
            4
        } else {
            val orientation = resources.configuration.orientation
            val isTablet = (resources.configuration.screenLayout and Configuration.SCREENLAYOUT_SIZE_MASK) >= Configuration.SCREENLAYOUT_SIZE_LARGE
            if (isTablet || orientation == Configuration.ORIENTATION_LANDSCAPE) 6 else 4
        }
        rvApps.layoutManager = GridLayoutManager(requireContext(), spanCount)

        // 4. Setup Adapter
        adapter = OpenWithAdapter(
            allTargets = emptyList(),
            isTv = isTv,
            scope = viewLifecycleOwner.lifecycleScope,
            onItemClick = { target ->
                val remember = switchAlways?.isChecked == true
                launchTarget(target, remember)
            },
            onItemLongClick = { target ->
                showTargetContextMenu(target)
                true
            }
        )
        rvApps.adapter = adapter

        // 5. Setup Search Filter
        edtSearch?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter.filter(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // 6. Setup Buttons (Mobile & TV)
        view.findViewById<View?>(R.id.btnSystemChooser)?.setOnClickListener {
            OpenWithLauncher.launchSystemChooser(requireContext(), uri, mimeType, fileName, targetFile)
            dismiss()
        }

        view.findViewById<View?>(R.id.btnSearchPlayStore)?.setOnClickListener {
            try {
                val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=$mimeType"))
                startActivity(marketIntent)
            } catch (_: Exception) {
                val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/search?q=$mimeType"))
                startActivity(webIntent)
            }
            dismiss()
        }

        // TV Embedded Buttons
        view.findViewById<MaterialButton?>(R.id.btnJustOnce)?.setOnClickListener {
            adapter.getSelectedTarget()?.let { target ->
                launchTarget(target, remember = false)
            }
        }

        view.findViewById<MaterialButton?>(R.id.btnAlways)?.setOnClickListener {
            adapter.getSelectedTarget()?.let { target ->
                launchTarget(target, remember = true)
            }
        }

        view.findViewById<MaterialButton?>(R.id.btnCancel)?.setOnClickListener {
            dismiss()
        }

        // 7. Load Targets in Background
        loadTargets(uri, mimeType, cleanExt, isNetwork)
    }

    private fun loadTargets(uri: Uri, mime: String, extension: String, isNet: Boolean) {
        progressBar.visibility = View.VISIBLE
        rvApps.visibility = View.GONE
        emptyStateContainer.visibility = View.GONE

        viewLifecycleOwner.lifecycleScope.launch {
            val targets = OpenWithResolver.resolveTargets(
                context = requireContext(),
                uri = uri,
                mimeType = mime,
                extension = extension,
                isNetwork = isNet
            )

            progressBar.visibility = View.GONE

            if (targets.isEmpty()) {
                emptyStateContainer.visibility = View.VISIBLE
                rvApps.visibility = View.GONE
                searchContainer?.visibility = View.GONE
            } else {
                emptyStateContainer.visibility = View.GONE
                rvApps.visibility = View.VISIBLE

                // Show search container only if > 12 apps
                if (targets.size > 12) {
                    searchContainer?.visibility = View.VISIBLE
                } else {
                    searchContainer?.visibility = View.GONE
                }

                adapter.updateList(targets)
                if (isTv) {
                    rvApps.post {
                        rvApps.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() ?: rvApps.requestFocus()
                    }
                }
            }
        }
    }

    private fun launchTarget(target: OpenWithTarget, remember: Boolean) {
        val uri = targetUri ?: return
        val targetFile = filePath?.let { File(it) }

        OpenWithLauncher.launch(
            context = requireContext(),
            target = target,
            uri = uri,
            mimeType = mimeType,
            fileName = fileName,
            file = targetFile,
            isNetwork = isNetwork,
            remember = remember,
            extension = fileExtension
        )
        dismiss()
    }

    private fun showTargetContextMenu(target: OpenWithTarget) {
        if (target.isSystemChooserFallback) return

        val cleanExt = fileExtension.lowercase().trimStart('.')
        val context = requireContext()
        val isTv = DeviceUtils.isTvDevice(context)

        val dialogView = LayoutInflater.from(context).inflate(
            if (isTv) R.layout.dialog_open_with_options_tv
            else R.layout.dialog_open_with_options,
            null
        )

        val imgAppIcon = dialogView.findViewById<ImageView>(R.id.imgAppIcon)
        val txtAppTitle = dialogView.findViewById<TextView>(R.id.txtAppTitle)
        val txtAppPackage = dialogView.findViewById<TextView>(R.id.txtAppPackage)
        val cardSetDefault = dialogView.findViewById<View>(R.id.cardSetDefault)
        val txtSetDefaultSubtitle = dialogView.findViewById<TextView>(R.id.txtSetDefaultSubtitle)
        val cardAppInfo = dialogView.findViewById<View>(R.id.cardAppInfo)
        val btnCancel = dialogView.findViewById<View>(R.id.btnCancel)

        txtAppTitle?.text = target.label
        txtAppPackage?.text = target.packageName

        if (cleanExt.isNotBlank()) {
            txtSetDefaultSubtitle?.text = getString(R.string.open_with_always_desc, cleanExt)
        }

        val quickIcon = target.badge ?: runCatching {
            context.packageManager.getApplicationIcon(target.packageName)
        }.getOrNull()
        if (quickIcon != null) {
            imgAppIcon?.setImageDrawable(quickIcon)
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val resolvedIcon = OpenWithResolver.loadIcon(context, target)
            imgAppIcon?.setImageDrawable(resolvedIcon)
        }

        val dialog = MaterialAlertDialogBuilder(context, R.style.UFM_Dialog)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        cardSetDefault?.setOnClickListener {
            dialog.dismiss()
            launchTarget(target, remember = true)
        }

        cardAppInfo?.setOnClickListener {
            dialog.dismiss()
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", target.packageName, null)
                }
                startActivity(intent)
            } catch (_: Exception) {
                Toast.makeText(context, R.string.error_cannot_open_file, Toast.LENGTH_SHORT).show()
            }
        }

        btnCancel?.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()

        if (isTv) {
            cardSetDefault?.requestFocus()
        }
    }
}
