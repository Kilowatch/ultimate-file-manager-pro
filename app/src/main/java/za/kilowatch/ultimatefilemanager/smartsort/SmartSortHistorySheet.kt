package za.kilowatch.ultimatefilemanager.smartsort

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SmartSortHistorySheet : BottomSheetDialogFragment() {

    private var onRefresh: (() -> Unit)? = null
    private var folderPath: String? = null

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
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.sheet_smart_sort_history, container, false)
        folderPath = arguments?.getString(ARG_FOLDER_PATH)
        initViews(view)
        return view
    }

    private fun initViews(view: View) {
        val txtEmpty = view.findViewById<TextView>(R.id.txtHistoryEmpty)
        val layoutList = view.findViewById<LinearLayout>(R.id.layoutHistoryList)
        val btnClearAll = view.findViewById<MaterialButton>(R.id.btnClearAll)

        var entries = SmartSortHistoryManager.loadAll()
        if (folderPath != null) {
            entries = entries.filter { it.folderPath == folderPath }
        }
        updateList(entries, txtEmpty, layoutList)

        btnClearAll.setOnClickListener {
            val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_smart_sort_clear_history_confirm, null)
            val dialog = MaterialAlertDialogBuilder(requireContext(), R.style.UFM_Dialog)
                .setView(dialogView)
                .create()
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

            dialogView.findViewById<View>(R.id.btnDeleteConfirm).setOnClickListener {
                SmartSortHistoryManager.clearAll()
                updateList(emptyList(), txtEmpty, layoutList)
                onRefresh?.invoke()
                dialog.dismiss()
            }
            dialogView.findViewById<View>(R.id.btnCancel).setOnClickListener {
                dialog.dismiss()
            }
            dialog.show()
        }
    }

    private fun updateList(
        entries: List<SmartSortHistoryEntry>,
        txtEmpty: TextView,
        layoutList: LinearLayout
    ) {
        layoutList.removeAllViews()
        if (entries.isEmpty()) {
            txtEmpty.visibility = View.VISIBLE
            return
        }
        txtEmpty.visibility = View.GONE

        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val engine = SmartSortEngine()

        for (entry in entries) {
            val card = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_smart_sort_history_entry, layoutList, false)

            card.findViewById<TextView>(R.id.txtHistoryFolder).text = entry.folderPath
            card.findViewById<TextView>(R.id.txtHistoryDate).text = dateFormat.format(Date(entry.sortDate))
            card.findViewById<TextView>(R.id.txtHistorySummary).text = getString(
                R.string.smart_sort_history_summary,
                entry.movedCount, entry.skippedCount, entry.failedCount
            )

            card.findViewById<MaterialButton>(R.id.btnHistoryUndo).setOnClickListener {
                val context = requireContext()
                val undoProgressView = LayoutInflater.from(context).inflate(R.layout.dialog_smart_sort_progress, null)
                val undoStatusText = undoProgressView.findViewById<TextView>(R.id.txtProgress)
                val undoProgressBar = undoProgressView.findViewById<ProgressBar>(R.id.progressBar)
                undoProgressView.findViewById<TextView>(R.id.txtTitle).setText(R.string.smart_sort_undo)
                undoProgressBar.max = 100
                undoProgressBar.progress = 0

                val undoDialog = MaterialAlertDialogBuilder(context, R.style.UFM_Dialog)
                    .setView(undoProgressView)
                    .setCancelable(false)
                    .create()
                undoDialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
                undoDialog.show()

                lifecycleScope.launch {
                    val manifest = withContext(Dispatchers.IO) {
                        SmartSortManifest.load(entry.manifestFolderPath)
                    }
                    if (manifest != null) {
                        val config = SmartSortConfig(
                            shareInfo = manifest.sourceShareId?.let { SmartSortShareHolder.resolve(it) }
                        )
                        withContext(Dispatchers.IO) {
                            engine.undo(entry.manifestFolderPath, manifest, config) { fileName, current, total ->
                                requireActivity().runOnUiThread {
                                    undoStatusText.text = getString(R.string.smart_sort_progress_moving, fileName, current, total)
                                    undoProgressBar.progress = if (total > 0) (current * 100) / total else 0
                                }
                            }
                        }
                        SmartSortHistoryManager.removeEntry(entry.id)
                    }
                    undoDialog.dismiss()
                    val updated = SmartSortHistoryManager.loadAll()
                    updateList(updated, txtEmpty, layoutList)
                    onRefresh?.invoke()
                }
            }

            card.findViewById<MaterialButton>(R.id.btnHistoryDelete).setOnClickListener {
                SmartSortHistoryManager.removeEntry(entry.id)
                SmartSortManifest.delete(entry.manifestFolderPath)
                val updated = SmartSortHistoryManager.loadAll()
                updateList(updated, txtEmpty, layoutList)
                onRefresh?.invoke()
            }

            layoutList.addView(card)
        }
    }

    companion object {
        const val TAG = "SmartSortHistorySheet"
        private const val ARG_FOLDER_PATH = "folder_path"

        fun newInstance(folderPath: String? = null, onRefresh: (() -> Unit)? = null): SmartSortHistorySheet {
            return SmartSortHistorySheet().apply {
                this.onRefresh = onRefresh
                arguments = Bundle().apply {
                    if (folderPath != null) putString(ARG_FOLDER_PATH, folderPath)
                }
            }
        }
    }
}
