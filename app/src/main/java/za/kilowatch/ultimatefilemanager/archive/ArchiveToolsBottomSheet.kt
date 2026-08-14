package za.kilowatch.ultimatefilemanager.archive

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.settings.IconCustomizationManager

class ArchiveToolsBottomSheet : BottomSheetDialogFragment() {

    data class ActionItem(
        val id: String,
        val label: String,
        val defaultIconRes: Int,
        val customIconId: String,
        val action: () -> Unit
    )

    private var actions: List<ActionItem> = emptyList()
    private var titleText: String? = null
    private var subtitleText: String? = null

    fun setActions(actions: List<ActionItem>) {
        this.actions = actions
    }

    fun setTitleAndSubtitle(title: String?, subtitle: String?) {
        this.titleText = title
        this.subtitleText = subtitle
    }

    companion object {
        const val TAG = "ArchiveToolsBottomSheet"

        fun newInstance(
            actions: List<ActionItem>,
            title: String? = null,
            subtitle: String? = null
        ): ArchiveToolsBottomSheet {
            return ArchiveToolsBottomSheet().apply {
                setActions(actions)
                setTitleAndSubtitle(title, subtitle)
            }
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
        return inflater.inflate(R.layout.dialog_archive_tools, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val txtTitle = view.findViewById<TextView>(R.id.txtTitle)
        val txtSubtitle = view.findViewById<TextView>(R.id.txtSubtitle)

        if (titleText != null) {
            txtTitle.text = titleText
            txtTitle.visibility = View.VISIBLE
        }

        if (subtitleText != null) {
            txtSubtitle.text = subtitleText
            txtSubtitle.visibility = View.VISIBLE
        }

        val rvTools = view.findViewById<RecyclerView>(R.id.rvTools)
        val spanCount = if (actions.size <= 3) actions.size.coerceAtLeast(1) else 4
        rvTools.layoutManager = GridLayoutManager(context, spanCount)
        rvTools.adapter = ToolsAdapter(actions) {
            dismiss()
        }
    }

    private class ToolsAdapter(
        private val items: List<ActionItem>,
        private val onItemClick: () -> Unit
    ) : RecyclerView.Adapter<ToolsAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val imgIcon: ImageView = view.findViewById(R.id.imgIcon)
            val txtLabel: TextView = view.findViewById(R.id.txtLabel)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_file_tool, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.txtLabel.text = item.label

            IconCustomizationManager.applyToView(
                holder.itemView.context,
                holder.imgIcon,
                item.customIconId,
                item.defaultIconRes
            )

            holder.itemView.setOnClickListener {
                item.action()
                onItemClick()
            }
        }

        override fun getItemCount() = items.size
    }
}
