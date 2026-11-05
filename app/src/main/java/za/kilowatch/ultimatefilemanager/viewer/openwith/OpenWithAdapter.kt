package za.kilowatch.ultimatefilemanager.viewer.openwith

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.R

/**
 * RecyclerView adapter for candidate apps in the Open With chooser (Mobile & TV).
 */
class OpenWithAdapter(
    private var allTargets: List<OpenWithTarget>,
    private val isTv: Boolean,
    private val scope: CoroutineScope,
    private val onItemClick: (OpenWithTarget) -> Unit,
    private val onItemLongClick: ((OpenWithTarget) -> Boolean)? = null,
    private val onItemSelected: ((OpenWithTarget) -> Unit)? = null
) : RecyclerView.Adapter<OpenWithAdapter.TargetViewHolder>() {

    private var displayedTargets: List<OpenWithTarget> = allTargets
    var selectedPosition: Int = if (allTargets.isNotEmpty()) 0 else RecyclerView.NO_POSITION
        private set

    fun updateList(newTargets: List<OpenWithTarget>) {
        allTargets = newTargets
        displayedTargets = newTargets
        selectedPosition = if (newTargets.isNotEmpty()) 0 else RecyclerView.NO_POSITION
        notifyDataSetChanged()
        if (selectedPosition != RecyclerView.NO_POSITION) {
            onItemSelected?.invoke(displayedTargets[selectedPosition])
        }
    }

    fun filter(query: String) {
        val trimmed = query.trim().lowercase()
        displayedTargets = if (trimmed.isBlank()) {
            allTargets
        } else {
            allTargets.filter { target ->
                target.label.lowercase().contains(trimmed) ||
                (target.disambiguation != null && target.disambiguation.lowercase().contains(trimmed))
            }
        }
        selectedPosition = if (displayedTargets.isNotEmpty()) 0 else RecyclerView.NO_POSITION
        notifyDataSetChanged()
        if (selectedPosition != RecyclerView.NO_POSITION) {
            onItemSelected?.invoke(displayedTargets[selectedPosition])
        }
    }

    fun getSelectedTarget(): OpenWithTarget? {
        return if (selectedPosition in displayedTargets.indices) {
            displayedTargets[selectedPosition]
        } else null
    }

    override fun getItemCount(): Int = displayedTargets.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TargetViewHolder {
        val layoutRes = if (isTv) R.layout.item_open_with_target_tv else R.layout.item_open_with_target
        val view = LayoutInflater.from(parent.context).inflate(layoutRes, parent, false)
        return TargetViewHolder(view)
    }

    override fun onBindViewHolder(holder: TargetViewHolder, position: Int) {
        val target = displayedTargets[position]
        holder.bind(target, position == selectedPosition)
    }

    inner class TargetViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val imgAppIcon: ImageView = itemView.findViewById(R.id.imgAppIcon)
        private val imgBadge: ImageView? = itemView.findViewById(R.id.imgBadge)
        private val txtAppLabel: TextView = itemView.findViewById(R.id.txtAppLabel)
        private val txtDisambiguation: TextView? = itemView.findViewById(R.id.txtDisambiguation)
        private var iconLoadJob: Job? = null

        fun bind(target: OpenWithTarget, isSelected: Boolean) {
            txtAppLabel.text = target.label

            if (target.disambiguation != null) {
                txtDisambiguation?.text = target.disambiguation
                txtDisambiguation?.visibility = View.VISIBLE
            } else {
                txtDisambiguation?.visibility = View.GONE
            }

            // Cancel any ongoing icon load for recycled views
            iconLoadJob?.cancel()

            // Default placeholder while resolving
            imgAppIcon.setImageResource(android.R.drawable.sym_def_app_icon)

            val context = itemView.context
            iconLoadJob = scope.launch(Dispatchers.IO) {
                val icon = OpenWithResolver.loadIcon(context, target)
                withContext(Dispatchers.Main) {
                    imgAppIcon.setImageDrawable(icon)
                }
            }

            if (target.badge != null) {
                imgBadge?.setImageDrawable(target.badge)
                imgBadge?.visibility = View.VISIBLE
            } else {
                imgBadge?.visibility = View.GONE
            }

            itemView.setOnClickListener {
                selectedPosition = bindingAdapterPosition
                onItemSelected?.invoke(target)
                onItemClick(target)
            }

            itemView.setOnLongClickListener {
                selectedPosition = bindingAdapterPosition
                onItemSelected?.invoke(target)
                onItemLongClick?.invoke(target) ?: false
            }

            if (isTv) {
                itemView.setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus) {
                        val prev = selectedPosition
                        selectedPosition = bindingAdapterPosition
                        onItemSelected?.invoke(target)
                        if (prev != RecyclerView.NO_POSITION && prev != selectedPosition) {
                            notifyItemChanged(prev)
                        }
                    }
                }
            }
        }
    }
}
