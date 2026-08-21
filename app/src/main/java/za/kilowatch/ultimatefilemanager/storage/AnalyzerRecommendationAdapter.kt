package za.kilowatch.ultimatefilemanager.storage

import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import za.kilowatch.ultimatefilemanager.R

/**
 * Cleanup recommendation cards.
 * Each card shows a risk-level badge (colored chip), title, description, and estimated savings.
 */
class AnalyzerRecommendationAdapter(
    private val items : List<CleanupRecommendation>,
    private val isTv  : Boolean = false,
    private val onClick: (CleanupRecommendation) -> Unit
) : RecyclerView.Adapter<AnalyzerRecommendationAdapter.VH>() {

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val txtTitle   : TextView  = v.findViewById(R.id.txtRecTitle)
        val txtDesc    : TextView  = v.findViewById(R.id.txtRecDescription)
        val txtSavings : TextView  = v.findViewById(R.id.txtRecSavings)
        val txtRisk    : TextView  = v.findViewById(R.id.txtRecRisk)
        val imgIcon    : ImageView = v.findViewById(R.id.imgRecIcon)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val layout = if (isTv) R.layout.item_analyzer_recommendation_tv else R.layout.item_analyzer_recommendation
        return VH(LayoutInflater.from(parent.context).inflate(layout, parent, false))
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val ctx  = holder.itemView.context

        holder.txtTitle.text   = item.title
        holder.txtDesc.text    = item.description
        holder.txtSavings.text = ctx.getString(R.string.save_formatterformatfilesizectx_itemestimatedbytes, Formatter.formatFileSize(ctx, item.estimatedBytes))

        val (riskLabel, riskColor, riskBgRes, defaultIcon) = when (item.riskLevel) {
            RiskLevel.SAFE          -> Quad("SAFE TO CLEAN", ContextCompat.getColor(ctx, R.color.ufm_risk_safe), R.drawable.bg_badge_risk_safe, R.drawable.ic_junk)
            RiskLevel.MODERATE      -> Quad("MODERATE RISK", ContextCompat.getColor(ctx, R.color.ufm_risk_moderate), R.drawable.bg_badge_risk_moderate, R.drawable.ic_warning_badge)
            RiskLevel.MANUAL_REVIEW -> Quad(ctx.getString(R.string.manual_review), ContextCompat.getColor(ctx, R.color.ufm_risk_manual), R.drawable.bg_badge_risk_manual, R.drawable.ic_warning_badge)
        }

        val iconRes = when {
            item.title.contains("APK", ignoreCase = true) -> R.drawable.ic_apps
            item.title.contains("Duplicate", ignoreCase = true) -> R.drawable.ic_copy
            item.title.contains("Download", ignoreCase = true) -> R.drawable.ic_file
            item.title.contains("Old", ignoreCase = true) || item.title.contains("Large", ignoreCase = true) -> R.drawable.ic_file
            else -> defaultIcon
        }

        holder.txtRisk.text = riskLabel
        holder.txtRisk.setTextColor(riskColor)
        holder.txtRisk.setBackgroundResource(riskBgRes)
        holder.imgIcon.setImageResource(iconRes)
        if (!isTv) {
            holder.imgIcon.imageTintList = android.content.res.ColorStateList.valueOf(riskColor)
        }

        holder.itemView.setOnClickListener { onClick(item) }
    }

    private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    override fun getItemCount(): Int = items.size
}
