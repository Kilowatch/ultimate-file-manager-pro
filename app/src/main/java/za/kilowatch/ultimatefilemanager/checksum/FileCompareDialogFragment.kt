package za.kilowatch.ultimatefilemanager.checksum

import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.util.DeviceUtils

/**
 * Dialog controller for comparing two files across Twin Window panes or folders.
 * Features early-exit short-circuiting on file size difference and side-by-side hash comparison.
 */
class FileCompareDialogFragment : DialogFragment() {

    companion object {
        const val TAG = "FileCompareDialogFragment"
        private const val ARG_COMPARE_SESSION_ID = "arg_compare_session_id"

        fun newInstance(left: UfmFileSource, right: UfmFileSource): FileCompareDialogFragment {
            val sessionId = ChecksumSessionHolder.putCompare(left, right)
            val fragment = FileCompareDialogFragment()
            fragment.arguments = Bundle().apply {
                putString(ARG_COMPARE_SESSION_ID, sessionId)
            }
            return fragment
        }
    }

    private var sessionId: String? = null
    private var leftSource: UfmFileSource? = null
    private var rightSource: UfmFileSource? = null
    private var isTv = false
    private var compareJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionId = arguments?.getString(ARG_COMPARE_SESSION_ID)
        sessionId?.let {
            val pair = ChecksumSessionHolder.getCompare(it)
            leftSource = pair?.first
            rightSource = pair?.second
        }
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
        val layoutRes = if (isTv) R.layout.dialog_compare_files_tv else R.layout.dialog_compare_files
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
        val left = leftSource
        val right = rightSource
        if (left == null || right == null) {
            dismissAllowingStateLoss()
            return
        }

        val txtLeftStorage = view.findViewById<TextView>(R.id.txtLeftStorage)
        val txtLeftFileName = view.findViewById<TextView>(R.id.txtLeftFileName)
        val txtLeftFileSize = view.findViewById<TextView>(R.id.txtLeftFileSize)
        val txtRightStorage = view.findViewById<TextView>(R.id.txtRightStorage)
        val txtRightFileName = view.findViewById<TextView>(R.id.txtRightFileName)
        val txtRightFileSize = view.findViewById<TextView>(R.id.txtRightFileSize)
        val layoutSizeCheckBanner = view.findViewById<LinearLayout>(R.id.layoutSizeCheckBanner)
        val imgSizeCheckIcon = view.findViewById<ImageView>(R.id.imgSizeCheckIcon)
        val txtSizeCheckMessage = view.findViewById<TextView>(R.id.txtSizeCheckMessage)
        val layoutCompareAlgoSection = view.findViewById<View>(R.id.layoutCompareAlgoSection)
        val chipGroupCompareAlgo = view.findViewById<ChipGroup?>(R.id.chipGroupCompareAlgo)
        val chipCompareSha1 = view.findViewById<Chip?>(R.id.chipCompareSha1)
        val chipCompareSha256 = view.findViewById<Chip?>(R.id.chipCompareSha256)
        val chipCompareSha512 = view.findViewById<Chip?>(R.id.chipCompareSha512)
        val chipCompareCrc32 = view.findViewById<Chip?>(R.id.chipCompareCrc32)
        val chipCompareMd5 = view.findViewById<Chip?>(R.id.chipCompareMd5)

        val compareChips = listOfNotNull(
            chipCompareSha1?.let { it to HashAlgorithm.SHA1 },
            chipCompareSha256?.let { it to HashAlgorithm.SHA256 },
            chipCompareSha512?.let { it to HashAlgorithm.SHA512 },
            chipCompareCrc32?.let { it to HashAlgorithm.CRC32 },
            chipCompareMd5?.let { it to HashAlgorithm.MD5 }
        )

        var selectedAlgo = HashAlgorithm.SHA256
        chipCompareSha256?.isChecked = true

        for ((chip, algo) in compareChips) {
            chip.setOnClickListener {
                selectedAlgo = algo
                for ((otherChip, _) in compareChips) {
                    otherChip.isChecked = (otherChip == chip)
                }
            }
        }

        val btnStartCompare = view.findViewById<View>(R.id.btnStartCompare)
        val btnDismissCompare = view.findViewById<View>(R.id.btnDismissCompare)
        val layoutCompareProgress = view.findViewById<View>(R.id.layoutCompareProgress)
        val txtCompareProgressFile = view.findViewById<TextView>(R.id.txtCompareProgressFile)
        val progressBarCompare = view.findViewById<ProgressBar>(R.id.progressBarCompare)
        val btnCancelCompare = view.findViewById<View>(R.id.btnCancelCompare)
        val layoutCompareResult = view.findViewById<View>(R.id.layoutCompareResult)
        val imgCompareResultIcon = view.findViewById<ImageView>(R.id.imgCompareResultIcon)
        val txtCompareResultTitle = view.findViewById<TextView>(R.id.txtCompareResultTitle)
        val txtCompareResultDetail = view.findViewById<TextView>(R.id.txtCompareResultDetail)

        // Bind Left & Right
        txtLeftStorage.text = left.storageLabel
        txtLeftFileName.text = left.name
        val leftSizeFormatted = Formatter.formatFileSize(requireContext(), left.size)
        txtLeftFileSize.text = leftSizeFormatted

        txtRightStorage.text = right.storageLabel
        txtRightFileName.text = right.name
        val rightSizeFormatted = Formatter.formatFileSize(requireContext(), right.size)
        txtRightFileSize.text = rightSizeFormatted

        btnDismissCompare.setOnClickListener { dismiss() }

        // Size check short-circuit
        if (left.size != right.size) {
            layoutSizeCheckBanner.visibility = View.VISIBLE
            imgSizeCheckIcon.setImageResource(R.drawable.ic_close)
            imgSizeCheckIcon.imageTintList = android.content.res.ColorStateList.valueOf(requireContext().getColor(R.color.tv_error_red))
            txtSizeCheckMessage.text = getString(R.string.checksum_compare_diff_sizes, leftSizeFormatted, rightSizeFormatted)
            txtSizeCheckMessage.setTextColor(requireContext().getColor(R.color.tv_error_red))

            layoutCompareAlgoSection.visibility = View.GONE
            btnStartCompare.visibility = View.GONE
            return
        }

        // Identical size -> ready for hash comparison
        layoutSizeCheckBanner.visibility = View.VISIBLE
        imgSizeCheckIcon.setImageResource(R.drawable.ic_check)
        imgSizeCheckIcon.imageTintList = android.content.res.ColorStateList.valueOf(requireContext().getColor(R.color.ufm_granted))
        txtSizeCheckMessage.text = getString(R.string.checksum_compare_same_size, leftSizeFormatted)
        txtSizeCheckMessage.setTextColor(requireContext().getColor(R.color.ufm_granted))

        btnCancelCompare.setOnClickListener {
            compareJob?.cancel(CancellationException("Cancelled"))
        }

        btnStartCompare.setOnClickListener {
            val algo = if (chipGroupCompareAlgo != null && chipGroupCompareAlgo.checkedChipId != View.NO_ID) {
                when (chipGroupCompareAlgo.checkedChipId) {
                    R.id.chipCompareCrc32 -> HashAlgorithm.CRC32
                    R.id.chipCompareMd5 -> HashAlgorithm.MD5
                    R.id.chipCompareSha1 -> HashAlgorithm.SHA1
                    R.id.chipCompareSha512 -> HashAlgorithm.SHA512
                    else -> HashAlgorithm.SHA256
                }
            } else {
                selectedAlgo
            }

            btnStartCompare.isEnabled = false
            layoutCompareProgress.visibility = View.VISIBLE
            layoutCompareResult.visibility = View.GONE

            compareJob = lifecycleScope.launch {
                try {
                    // Hash Left File
                    txtCompareProgressFile.text = "Hashing: ${left.name}"
                    progressBarCompare.progress = 0
                    val leftHashes = ChecksumEngine.computeHashes(
                        requireContext(),
                        left,
                        setOf(algo)
                    ) { p ->
                        withContext(Dispatchers.Main) {
                            progressBarCompare.progress = (p.percent / 2).coerceIn(0, 50)
                        }
                    }

                    // Hash Right File
                    txtCompareProgressFile.text = "Hashing: ${right.name}"
                    val rightHashes = ChecksumEngine.computeHashes(
                        requireContext(),
                        right,
                        setOf(algo)
                    ) { p ->
                        withContext(Dispatchers.Main) {
                            progressBarCompare.progress = (50 + p.percent / 2).coerceIn(50, 100)
                        }
                    }

                    val leftHash = leftHashes[algo] ?: ""
                    val rightHash = rightHashes[algo] ?: ""
                    val isMatch = leftHash.isNotEmpty() && leftHash.equals(rightHash, ignoreCase = true)

                    withContext(Dispatchers.Main) {
                        layoutCompareProgress.visibility = View.GONE
                        layoutCompareResult.visibility = View.VISIBLE
                        btnStartCompare.isEnabled = true

                        if (isMatch) {
                            imgCompareResultIcon.setImageResource(R.drawable.ic_check)
                            imgCompareResultIcon.imageTintList = android.content.res.ColorStateList.valueOf(requireContext().getColor(R.color.ufm_granted))
                            txtCompareResultTitle.text = getString(R.string.checksum_compare_identical)
                            txtCompareResultTitle.setTextColor(requireContext().getColor(R.color.ufm_granted))
                            txtCompareResultDetail.text = "${algo.displayName}:\n$leftHash"
                        } else {
                            imgCompareResultIcon.setImageResource(R.drawable.ic_close)
                            imgCompareResultIcon.imageTintList = android.content.res.ColorStateList.valueOf(requireContext().getColor(R.color.tv_error_red))
                            txtCompareResultTitle.text = getString(R.string.checksum_compare_different)
                            txtCompareResultTitle.setTextColor(requireContext().getColor(R.color.tv_error_red))
                            txtCompareResultDetail.text = "Left (${algo.displayName}):  $leftHash\nRight (${algo.displayName}): $rightHash"
                        }
                    }
                } catch (_: CancellationException) {
                    withContext(Dispatchers.Main) {
                        layoutCompareProgress.visibility = View.GONE
                        btnStartCompare.isEnabled = true
                    }
                } catch (e: Throwable) {
                    withContext(Dispatchers.Main) {
                        layoutCompareProgress.visibility = View.GONE
                        btnStartCompare.isEnabled = true
                        txtCompareResultTitle.text = "Error: ${e.message}"
                        layoutCompareResult.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        compareJob?.cancel()
        sessionId?.let { ChecksumSessionHolder.removeCompare(it) }
    }
}
