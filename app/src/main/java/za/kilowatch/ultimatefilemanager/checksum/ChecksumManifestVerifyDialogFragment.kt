package za.kilowatch.ultimatefilemanager.checksum

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import java.io.File

class ChecksumManifestVerifyDialogFragment : DialogFragment() {

    companion object {
        const val TAG = "ChecksumManifestVerifyDialogFragment"
        private const val ARG_MANIFEST_SESSION_ID = "arg_manifest_session_id"
        private const val ARG_PARENT_DIR_PATH = "arg_parent_dir_path"

        fun newInstance(manifestSource: UfmFileSource, parentDir: File? = null): ChecksumManifestVerifyDialogFragment {
            val sessionId = ChecksumSessionHolder.put(listOf(manifestSource))
            val fragment = ChecksumManifestVerifyDialogFragment()
            fragment.arguments = Bundle().apply {
                putString(ARG_MANIFEST_SESSION_ID, sessionId)
                putString(ARG_PARENT_DIR_PATH, parentDir?.absolutePath)
            }
            return fragment
        }
    }

    enum class EntryStatus { PENDING, OK, FAILED, MISSING }

    data class VerifiedEntry(
        val filename: String,
        val expectedHash: String,
        val algorithm: HashAlgorithm,
        var actualHash: String? = null,
        var status: EntryStatus = EntryStatus.PENDING
    )

    private var sessionId: String? = null
    private var parentDirPath: String? = null
    private var manifestSource: UfmFileSource? = null
    private var isTv = false
    private var verifyJob: Job? = null

    private val entriesList = mutableListOf<VerifiedEntry>()
    private var adapter: ManifestEntryAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionId = arguments?.getString(ARG_MANIFEST_SESSION_ID)
        parentDirPath = arguments?.getString(ARG_PARENT_DIR_PATH)
        sessionId?.let {
            manifestSource = ChecksumSessionHolder.get(it)?.firstOrNull()
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
        val layoutRes = if (isTv) R.layout.dialog_checksum_manifest_verify_tv else R.layout.dialog_checksum_manifest_verify
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
        val source = manifestSource
        if (source == null) {
            dismissAllowingStateLoss()
            return
        }

        val txtManifestFileName = view.findViewById<TextView>(R.id.txtManifestFileName)
        val txtManifestAlgoBadge = view.findViewById<TextView>(R.id.txtManifestAlgoBadge)
        val txtManifestStatusSummary = view.findViewById<TextView>(R.id.txtManifestStatusSummary)
        val progressBarManifest = view.findViewById<ProgressBar>(R.id.progressBarManifest)
        val recyclerManifestEntries = view.findViewById<RecyclerView>(R.id.recyclerManifestEntries)
        val btnCancelManifest = view.findViewById<View>(R.id.btnCancelManifest)
        val btnCloseManifest = view.findViewById<View>(R.id.btnCloseManifest)

        txtManifestFileName.text = source.name
        val fallbackAlgo = HashAlgorithm.fromExtension(source.name.substringAfterLast('.'))
        txtManifestAlgoBadge.text = fallbackAlgo?.displayName ?: "Checksum"

        adapter = ManifestEntryAdapter(entriesList)
        recyclerManifestEntries.layoutManager = LinearLayoutManager(requireContext())
        recyclerManifestEntries.adapter = adapter

        btnCloseManifest.setOnClickListener { dismiss() }
        btnCancelManifest.setOnClickListener {
            verifyJob?.cancel(CancellationException("Cancelled"))
        }

        startManifestVerification(source, fallbackAlgo, txtManifestStatusSummary, progressBarManifest, btnCancelManifest)
    }

    private fun startManifestVerification(
        source: UfmFileSource,
        fallbackAlgo: HashAlgorithm?,
        txtSummary: TextView,
        progressBar: ProgressBar,
        btnCancel: View
    ) {
        val baseFolder = if (parentDirPath != null) File(parentDirPath!!) else {
            if (source is LocalFileSource) source.file.parentFile else null
        }

        verifyJob = lifecycleScope.launch {
            try {
                txtSummary.text = getString(R.string.checksum_manifest_reading)
                val parsed = withContext(Dispatchers.IO) {
                    source.openStream(requireContext()).use { input ->
                        ChecksumManifestParser.parse(input, fallbackAlgo)
                    }
                }

                if (parsed.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        txtSummary.text = "No valid checksum records found in manifest."
                        progressBar.visibility = View.GONE
                        btnCancel.visibility = View.GONE
                    }
                    return@launch
                }

                entriesList.clear()
                entriesList.addAll(parsed.map {
                    VerifiedEntry(it.filename, it.expectedHash, it.algorithm)
                })
                withContext(Dispatchers.Main) {
                    adapter?.notifyDataSetChanged()
                    progressBar.max = entriesList.size
                    progressBar.progress = 0
                }

                var okCount = 0
                var failedCount = 0
                var missingCount = 0

                for ((idx, entry) in entriesList.withIndex()) {
                    val targetFile = if (baseFolder != null) File(baseFolder, entry.filename) else null
                    if (targetFile == null || !targetFile.exists() || !targetFile.isFile) {
                        entry.status = EntryStatus.MISSING
                        missingCount++
                    } else {
                        val fileSource = LocalFileSource(targetFile)
                        val hashes = ChecksumEngine.computeHashes(
                            requireContext(),
                            fileSource,
                            setOf(entry.algorithm)
                        )
                        val actual = hashes[entry.algorithm]
                        entry.actualHash = actual
                        if (actual != null && actual.equals(entry.expectedHash, ignoreCase = true)) {
                            entry.status = EntryStatus.OK
                            okCount++
                        } else {
                            entry.status = EntryStatus.FAILED
                            failedCount++
                        }
                    }

                    withContext(Dispatchers.Main) {
                        adapter?.notifyItemChanged(idx)
                        progressBar.progress = idx + 1
                        txtSummary.text = getString(R.string.checksum_manifest_summary, okCount, failedCount, missingCount)
                    }
                }

                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    btnCancel.visibility = View.GONE
                }
            } catch (_: CancellationException) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    btnCancel.visibility = View.GONE
                }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) {
                    txtSummary.text = "Error: ${e.message}"
                    progressBar.visibility = View.GONE
                    btnCancel.visibility = View.GONE
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        verifyJob?.cancel()
        sessionId?.let { ChecksumSessionHolder.remove(it) }
    }

    private inner class ManifestEntryAdapter(
        private val items: List<VerifiedEntry>
    ) : RecyclerView.Adapter<ManifestEntryAdapter.ViewHolder>() {

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val imgStatus: ImageView = itemView.findViewById(R.id.imgEntryStatus)
            val txtName: TextView = itemView.findViewById(R.id.txtEntryFileName)
            val txtStatusLabel: TextView = itemView.findViewById(R.id.txtEntryStatusLabel)
            val txtDetail: TextView = itemView.findViewById(R.id.txtEntryDetail)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_checksum_manifest_entry, parent, false)
            return ViewHolder(v)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.txtName.text = item.filename

            when (item.status) {
                EntryStatus.PENDING -> {
                    holder.imgStatus.setImageResource(R.drawable.ic_history)
                    holder.imgStatus.imageTintList = android.content.res.ColorStateList.valueOf(requireContext().getColor(if (isTv) R.color.tv_text_secondary else R.color.ufm_text_secondary))
                    holder.txtStatusLabel.text = "..."
                    holder.txtStatusLabel.setTextColor(requireContext().getColor(if (isTv) R.color.tv_text_secondary else R.color.ufm_text_secondary))
                    holder.txtDetail.visibility = View.GONE
                }
                EntryStatus.OK -> {
                    holder.imgStatus.setImageResource(R.drawable.ic_check)
                    holder.imgStatus.imageTintList = android.content.res.ColorStateList.valueOf(requireContext().getColor(R.color.ufm_granted))
                    holder.txtStatusLabel.text = getString(R.string.checksum_manifest_status_ok)
                    holder.txtStatusLabel.setTextColor(requireContext().getColor(R.color.ufm_granted))
                    holder.txtDetail.visibility = View.GONE
                }
                EntryStatus.FAILED -> {
                    holder.imgStatus.setImageResource(R.drawable.ic_close)
                    holder.imgStatus.imageTintList = android.content.res.ColorStateList.valueOf(requireContext().getColor(R.color.tv_error_red))
                    holder.txtStatusLabel.text = getString(R.string.checksum_manifest_status_failed)
                    holder.txtStatusLabel.setTextColor(requireContext().getColor(R.color.tv_error_red))
                    holder.txtDetail.visibility = View.VISIBLE
                    holder.txtDetail.text = "Expected: ${item.expectedHash}\nActual:   ${item.actualHash ?: "none"}"
                }
                EntryStatus.MISSING -> {
                    holder.imgStatus.setImageResource(R.drawable.ic_warning)
                    holder.imgStatus.imageTintList = android.content.res.ColorStateList.valueOf(requireContext().getColor(R.color.tv_button_focused_yellow))
                    holder.txtStatusLabel.text = getString(R.string.checksum_manifest_status_missing)
                    holder.txtStatusLabel.setTextColor(requireContext().getColor(R.color.tv_button_focused_yellow))
                    holder.txtDetail.visibility = View.GONE
                }
            }
        }
    }
}
