package za.kilowatch.ultimatefilemanager.checksum

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import java.io.File
import java.util.Locale

class ChecksumDialogFragment : DialogFragment() {

    companion object {
        const val TAG = "ChecksumDialogFragment"
        private const val ARG_SESSION_ID = "arg_session_id"

        fun newInstance(sources: List<UfmFileSource>): ChecksumDialogFragment {
            val sessionId = ChecksumSessionHolder.put(sources)
            val fragment = ChecksumDialogFragment()
            fragment.arguments = Bundle().apply {
                putString(ARG_SESSION_ID, sessionId)
            }
            return fragment
        }
    }

    private var sessionId: String? = null
    private var sources: List<UfmFileSource> = emptyList()
    private var isTv = false

    private var currentJob: Job? = null
    private val computedResults = mutableMapOf<UfmFileSource, Map<HashAlgorithm, String>>()
    private val activeAlgorithms = mutableSetOf<HashAlgorithm>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionId = arguments?.getString(ARG_SESSION_ID)
        sources = sessionId?.let { ChecksumSessionHolder.get(it) } ?: emptyList()
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): android.app.Dialog {
        isTv = DeviceUtils.isTvDevice(requireContext())
        return if (isTv) {
            super.onCreateDialog(savedInstanceState)
        } else {
            BottomSheetDialog(requireContext(), theme)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val layoutRes = if (isTv) R.layout.dialog_checksum_tv else R.layout.dialog_checksum
        return inflater.inflate(layoutRes, container, false)
    }

    override fun onStart() {
        super.onStart()
        val dialog = dialog ?: return
        val window = dialog.window ?: return
        if (isTv) {
            val screenWidth = requireContext().resources.displayMetrics.widthPixels
            window.setLayout(
                (screenWidth * 0.70f).toInt().coerceAtLeast(600),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            window.setGravity(android.view.Gravity.CENTER)
            window.setBackgroundDrawableResource(android.R.color.transparent)
        } else {
            window.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            window.setGravity(android.view.Gravity.BOTTOM)
            window.setBackgroundDrawableResource(android.R.color.transparent)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (sources.isEmpty()) {
            dismissAllowingStateLoss()
            return
        }

        val txtTargetFileName = view.findViewById<TextView>(R.id.txtTargetFileName)
        val txtFileSize = view.findViewById<TextView>(R.id.txtFileSize)
        val txtStorageBadge = view.findViewById<TextView>(R.id.txtStorageBadge)
        val btnGenerate = view.findViewById<View>(R.id.btnGenerate)
        val btnCopyAll = view.findViewById<View>(R.id.btnCopyAll)
        val btnExportManifest = view.findViewById<View>(R.id.btnExportManifest)
        val layoutProgress = view.findViewById<View>(R.id.layoutProgress)
        val txtProgressStatus = view.findViewById<TextView>(R.id.txtProgressStatus)
        val txtProgressSpeed = view.findViewById<TextView>(R.id.txtProgressSpeed)
        val txtProgressEta = view.findViewById<TextView>(R.id.txtProgressEta)
        val progressBarChecksum = view.findViewById<ProgressBar>(R.id.progressBarChecksum)
        val btnCancel = view.findViewById<View>(R.id.btnCancel)
        val layoutResults = view.findViewById<LinearLayout>(R.id.layoutResults)
        val tilVerifyHash = view.findViewById<TextInputLayout>(R.id.tilVerifyHash)
        val edtVerifyHash = view.findViewById<TextInputEditText>(R.id.edtVerifyHash)
        val layoutVerifyBanner = view.findViewById<LinearLayout>(R.id.layoutVerifyBanner)
        val imgVerifyBannerIcon = view.findViewById<ImageView>(R.id.imgVerifyBannerIcon)
        val txtVerifyBannerText = view.findViewById<TextView>(R.id.txtVerifyBannerText)

        // Setup Header Meta
        if (sources.size == 1) {
            val s = sources.first()
            txtTargetFileName.text = s.name
            txtFileSize.text = "• " + Formatter.formatFileSize(requireContext(), s.size)
            txtStorageBadge.text = s.storageLabel
        } else {
            val totalBytes = sources.sumOf { it.size }
            txtTargetFileName.text = getString(R.string.selection_count, sources.size)
            txtFileSize.text = "• " + Formatter.formatFileSize(requireContext(), totalBytes)
            txtStorageBadge.text = sources.first().storageLabel
            if (btnGenerate is TextView) {
                btnGenerate.text = getString(R.string.checksum_generate_all)
            }
        }

        // Setup Chips from preferences
        val savedAlgos = ChecksumPreferenceManager.getSelectedAlgorithms(requireContext())
        activeAlgorithms.clear()
        activeAlgorithms.addAll(savedAlgos)

        val chipCrc32 = view.findViewById<Chip?>(R.id.chipCrc32)
        val chipMd5 = view.findViewById<Chip?>(R.id.chipMd5)
        val chipSha1 = view.findViewById<Chip?>(R.id.chipSha1)
        val chipSha256 = view.findViewById<Chip?>(R.id.chipSha256)
        val chipSha512 = view.findViewById<Chip?>(R.id.chipSha512)

        chipCrc32?.isChecked = activeAlgorithms.contains(HashAlgorithm.CRC32)
        chipMd5?.isChecked = activeAlgorithms.contains(HashAlgorithm.MD5)
        chipSha1?.isChecked = activeAlgorithms.contains(HashAlgorithm.SHA1)
        chipSha256?.isChecked = activeAlgorithms.contains(HashAlgorithm.SHA256)
        chipSha512?.isChecked = activeAlgorithms.contains(HashAlgorithm.SHA512)

        val chipMap = mapOf(
            chipCrc32 to HashAlgorithm.CRC32,
            chipMd5 to HashAlgorithm.MD5,
            chipSha1 to HashAlgorithm.SHA1,
            chipSha256 to HashAlgorithm.SHA256,
            chipSha512 to HashAlgorithm.SHA512
        )

        for ((chip, algo) in chipMap) {
            chip?.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    activeAlgorithms.add(algo)
                } else {
                    activeAlgorithms.remove(algo)
                    // Keep at least one algorithm active
                    if (activeAlgorithms.isEmpty()) {
                        chip.isChecked = true
                        activeAlgorithms.add(algo)
                    }
                }
                ChecksumPreferenceManager.setSelectedAlgorithms(requireContext(), activeAlgorithms)
            }
        }

        // Generate Action
        btnGenerate.setOnClickListener {
            checkLargeFilesAndCompute()
        }

        // Cancel Action
        btnCancel.setOnClickListener {
            currentJob?.cancel(CancellationException("User cancelled"))
        }

        // Copy All Action
        btnCopyAll.setOnClickListener {
            copyAllToClipboard()
        }

        // Export Manifest Action
        btnExportManifest.setOnClickListener {
            exportManifest()
        }

        // Paste-to-verify listener & End icon click
        tilVerifyHash?.setEndIconOnClickListener {
            val clip = (requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).text?.toString()?.trim() ?: ""
                edtVerifyHash.setText(text)
                edtVerifyHash.setSelection(text.length)
            }
        }

        edtVerifyHash?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateVerificationBanner(
                    s?.toString()?.trim() ?: "",
                    layoutVerifyBanner,
                    imgVerifyBannerIcon,
                    txtVerifyBannerText
                )
            }
        })
    }

    private fun checkLargeFilesAndCompute() {
        val anyLarge = sources.any { it.size >= ChecksumEngine.LARGE_FILE_THRESHOLD_BYTES }
        if (anyLarge && ChecksumPreferenceManager.shouldWarnLargeFiles(requireContext())) {
            val largest = sources.maxByOrNull { it.size }
            val formattedSize = Formatter.formatFileSize(requireContext(), largest?.size ?: 0L)

            val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_folder_confirm, null)
            val txtMsg = dialogView.findViewById<TextView?>(R.id.txtMessage)
            val cbDontAsk = CheckBox(requireContext()).apply {
                text = getString(R.string.checksum_dont_ask_again)
                setPadding(0, 16, 0, 0)
            }

            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.checksum_large_file_warning_title)
                .setMessage(getString(R.string.checksum_large_file_warning_msg, formattedSize))
                .setView(cbDontAsk)
                .setPositiveButton(R.string.ok) { _, _ ->
                    if (cbDontAsk.isChecked) {
                        ChecksumPreferenceManager.setWarnLargeFiles(requireContext(), false)
                    }
                    startComputation()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else {
            startComputation()
        }
    }

    private fun startComputation() {
        val view = view ?: return
        val layoutProgress = view.findViewById<View>(R.id.layoutProgress)
        val progressBarChecksum = view.findViewById<ProgressBar>(R.id.progressBarChecksum)
        val txtProgressStatus = view.findViewById<TextView>(R.id.txtProgressStatus)
        val txtProgressSpeed = view.findViewById<TextView>(R.id.txtProgressSpeed)
        val txtProgressEta = view.findViewById<TextView>(R.id.txtProgressEta)
        val btnGenerate = view.findViewById<View>(R.id.btnGenerate)
        val btnCopyAll = view.findViewById<View>(R.id.btnCopyAll)
        val btnExportManifest = view.findViewById<View>(R.id.btnExportManifest)
        val layoutResults = view.findViewById<LinearLayout>(R.id.layoutResults)

        layoutProgress.visibility = View.VISIBLE
        btnGenerate.isEnabled = false
        layoutResults.removeAllViews()
        computedResults.clear()

        val selectedAlgos = activeAlgorithms.toSet()

        currentJob = lifecycleScope.launch {
            try {
                for ((index, source) in sources.withIndex()) {
                    if (sources.size > 1) {
                        val batchPercent = ((index * 100) / sources.size)
                        txtProgressStatus.text = getString(
                            R.string.checksum_batch_progress_format,
                            index + 1,
                            sources.size,
                            batchPercent
                        )
                    } else {
                        txtProgressStatus.text = getString(R.string.checksum_generating)
                    }

                    val hashes = ChecksumEngine.computeHashes(
                        requireContext(),
                        source,
                        selectedAlgos
                    ) { progress ->
                        withContext(Dispatchers.Main) {
                            if (progress.percent >= 0) {
                                progressBarChecksum.isIndeterminate = false
                                progressBarChecksum.progress = progress.percent
                            } else {
                                progressBarChecksum.isIndeterminate = true
                            }
                            val speedMb = progress.speedBytesPerSec / (1024.0 * 1024.0)
                            txtProgressSpeed.text = getString(R.string.checksum_speed_format, speedMb)
                            txtProgressEta.text = getString(R.string.checksum_eta_format, progress.etaSeconds)
                        }
                    }

                    computedResults[source] = hashes
                    withContext(Dispatchers.Main) {
                        appendResultRows(source, hashes, layoutResults)
                    }
                }

                withContext(Dispatchers.Main) {
                    layoutProgress.visibility = View.GONE
                    btnGenerate.isEnabled = true
                    btnCopyAll.visibility = View.VISIBLE
                    btnExportManifest.visibility = View.VISIBLE

                    val edtVerifyHash = view.findViewById<TextInputEditText>(R.id.edtVerifyHash)
                    val layoutVerifyBanner = view.findViewById<LinearLayout>(R.id.layoutVerifyBanner)
                    val imgVerifyBannerIcon = view.findViewById<ImageView>(R.id.imgVerifyBannerIcon)
                    val txtVerifyBannerText = view.findViewById<TextView>(R.id.txtVerifyBannerText)
                    updateVerificationBanner(
                        edtVerifyHash.text?.toString()?.trim() ?: "",
                        layoutVerifyBanner,
                        imgVerifyBannerIcon,
                        txtVerifyBannerText
                    )
                }
            } catch (_: CancellationException) {
                withContext(Dispatchers.Main) {
                    layoutProgress.visibility = View.GONE
                    btnGenerate.isEnabled = true
                }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) {
                    layoutProgress.visibility = View.GONE
                    btnGenerate.isEnabled = true
                    Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun appendResultRows(
        source: UfmFileSource,
        hashes: Map<HashAlgorithm, String>,
        container: LinearLayout
    ) {
        val inflater = LayoutInflater.from(requireContext())

        if (sources.size > 1) {
            val title = TextView(requireContext()).apply {
                text = "${source.name} (${Formatter.formatFileSize(requireContext(), source.size)})"
                textSize = 13f
                setTextColor(requireContext().getColor(if (isTv) R.color.tv_text_primary else R.color.ufm_text_primary))
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, 12, 0, 4)
            }
            container.addView(title)
        }

        for ((algo, hash) in hashes) {
            val rowView = inflater.inflate(R.layout.item_checksum_row, container, false)
            val txtAlgorithmBadge = rowView.findViewById<TextView>(R.id.txtAlgorithmBadge)
            val txtHashValue = rowView.findViewById<TextView>(R.id.txtHashValue)
            val btnCopyHash = rowView.findViewById<View>(R.id.btnCopyHash)

            txtAlgorithmBadge.text = algo.displayName
            txtHashValue.text = hash

            btnCopyHash.setOnClickListener {
                copyStringToClipboard(hash, getString(R.string.checksum_copied))
            }

            container.addView(rowView)
        }
    }

    private fun updateVerificationBanner(
        inputHash: String,
        banner: LinearLayout,
        icon: ImageView,
        text: TextView
    ) {
        if (inputHash.isEmpty() || computedResults.isEmpty()) {
            banner.visibility = View.GONE
            return
        }

        val clean = inputHash.lowercase(Locale.ROOT)
        val detectedAlgo = HashAlgorithm.detectAlgorithmFromHex(clean)

        // Find match in any computed result
        var matched = false
        var matchedAlgo: HashAlgorithm? = null
        var algoComputed = false

        for ((_, map) in computedResults) {
            for ((algo, hash) in map) {
                if (detectedAlgo == null || detectedAlgo == algo) {
                    algoComputed = true
                    if (hash.equals(clean, ignoreCase = true)) {
                        matched = true
                        matchedAlgo = algo
                        break
                    }
                }
            }
            if (matched) break
        }

        banner.visibility = View.VISIBLE
        if (matched) {
            banner.setBackgroundResource(R.drawable.bg_card_glass)
            icon.setImageResource(R.drawable.ic_check)
            icon.imageTintList = android.content.res.ColorStateList.valueOf(requireContext().getColor(R.color.ufm_granted))
            text.text = getString(R.string.checksum_match) + " (${matchedAlgo?.displayName})"
            text.setTextColor(requireContext().getColor(R.color.ufm_granted))
            banner.setOnClickListener(null)
        } else if (algoComputed) {
            banner.setBackgroundResource(R.drawable.bg_card_glass)
            icon.setImageResource(R.drawable.ic_close)
            icon.imageTintList = android.content.res.ColorStateList.valueOf(requireContext().getColor(R.color.tv_error_red))
            text.text = getString(R.string.checksum_mismatch)
            text.setTextColor(requireContext().getColor(R.color.tv_error_red))
            banner.setOnClickListener(null)
        } else if (detectedAlgo != null) {
            // Algorithm not yet computed
            banner.setBackgroundResource(R.drawable.bg_card_glass)
            icon.setImageResource(R.drawable.ic_checksum)
            icon.imageTintList = android.content.res.ColorStateList.valueOf(requireContext().getColor(R.color.tv_button_focused_yellow))
            text.text = getString(R.string.checksum_prompt_compute_algo, detectedAlgo.displayName)
            text.setTextColor(requireContext().getColor(if (isTv) R.color.tv_button_focused_yellow else R.color.ufm_text_primary))
            banner.setOnClickListener {
                activeAlgorithms.add(detectedAlgo)
                ChecksumPreferenceManager.setSelectedAlgorithms(requireContext(), activeAlgorithms)
                startComputation()
            }
        } else {
            banner.visibility = View.GONE
        }
    }

    private fun copyAllToClipboard() {
        if (computedResults.isEmpty()) return
        val sb = StringBuilder()
        for ((source, map) in computedResults) {
            if (sources.size > 1) {
                sb.append("File: ").append(source.name).append("\n")
            }
            for ((algo, hash) in map) {
                sb.append(algo.displayName).append(": ").append(hash).append("\n")
            }
            sb.append("\n")
        }
        copyStringToClipboard(sb.toString().trim(), getString(R.string.checksum_all_copied))
    }

    private fun copyStringToClipboard(text: String, toastMsg: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Checksum", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(requireContext(), toastMsg, Toast.LENGTH_SHORT).show()
    }

    private fun exportManifest() {
        if (computedResults.isEmpty()) return

        // Default to SHA-256 if computed, otherwise the first available
        val selectedAlgo = activeAlgorithms.firstOrNull { it == HashAlgorithm.SHA256 }
            ?: activeAlgorithms.firstOrNull() ?: HashAlgorithm.SHA256

        val records = computedResults.mapNotNull { (source, map) ->
            val hash = map[selectedAlgo]
            if (hash != null) {
                ChecksumManifestWriter.FileHashRecord(source.name, hash)
            } else {
                null
            }
        }

        if (records.isEmpty()) return

        val manifestContent = ChecksumManifestWriter.createManifestContent(records)
        val defaultName = ChecksumManifestWriter.generateDefaultManifestFilename(selectedAlgo)

        // Try writing to parent directory of the first file if it is a local file
        val firstSource = sources.firstOrNull()
        if (firstSource is LocalFileSource && firstSource.file.parentFile != null) {
            val destFile = File(firstSource.file.parentFile, defaultName)
            try {
                destFile.writeText(manifestContent, Charsets.UTF_8)
                Toast.makeText(
                    requireContext(),
                    getString(R.string.checksum_export_success, destFile.name),
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), R.string.checksum_export_error, Toast.LENGTH_SHORT).show()
            }
        } else {
            // Write to download folder or app files dir as fallback
            val downloadDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            val target = File(downloadDir, defaultName)
            try {
                target.writeText(manifestContent, Charsets.UTF_8)
                Toast.makeText(
                    requireContext(),
                    getString(R.string.checksum_export_success, target.name),
                    Toast.LENGTH_LONG
                ).show()
            } catch (_: Exception) {
                Toast.makeText(requireContext(), R.string.checksum_export_error, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        currentJob?.cancel()
        sessionId?.let { ChecksumSessionHolder.remove(it) }
    }
}
