package za.kilowatch.ultimatefilemanager.audio.musicbrainz

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import za.kilowatch.ultimatefilemanager.R

/**
 * UFMStandard Glass Bottom Sheet for selectively applying fields fetched from MusicBrainz.
 */
class TagFieldDiffBottomSheet : BottomSheetDialogFragment() {

    companion object {
        const val TAG = "TagFieldDiffBottomSheet"

        fun newInstance(): TagFieldDiffBottomSheet {
            return TagFieldDiffBottomSheet()
        }
    }

    private var diffItems: List<TagDiffItem> = emptyList()
    private var onApplyListener: ((List<TagDiffItem>) -> Unit)? = null

    fun setDiffData(items: List<TagDiffItem>, onApply: (List<TagDiffItem>) -> Unit) {
        this.diffItems = items
        this.onApplyListener = onApply
    }

    private lateinit var chkSelectAll: MaterialCheckBox
    private lateinit var recyclerDiffFields: RecyclerView
    private lateinit var btnApplyDiff: MaterialButton
    private lateinit var adapter: TagDiffAdapter

    override fun getTheme(): Int = R.style.TransparentBottomSheetDialog

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.bottom_sheet_tag_diff, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<ImageView>(R.id.btnClose).setOnClickListener {
            dismiss()
        }

        chkSelectAll = view.findViewById(R.id.chkSelectAll)
        recyclerDiffFields = view.findViewById(R.id.recyclerDiffFields)
        btnApplyDiff = view.findViewById(R.id.btnApplyDiff)

        adapter = TagDiffAdapter {
            updateSelectAllState()
        }
        recyclerDiffFields.adapter = adapter
        adapter.submitList(diffItems)

        chkSelectAll.setOnCheckedChangeListener { _, isChecked ->
            if (chkSelectAll.isPressed) {
                adapter.setAllSelected(isChecked)
            }
        }

        btnApplyDiff.setOnClickListener {
            val selected = adapter.items.filter { it.isSelected }
            onApplyListener?.invoke(selected)
            dismiss()
        }

        updateSelectAllState()
    }

    private fun updateSelectAllState() {
        val total = adapter.items.size
        val selectedCount = adapter.items.count { it.isSelected }
        chkSelectAll.isChecked = (selectedCount == total && total > 0)
        btnApplyDiff.isEnabled = (selectedCount > 0)
    }
}
