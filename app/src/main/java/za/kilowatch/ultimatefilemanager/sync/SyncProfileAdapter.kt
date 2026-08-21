package za.kilowatch.ultimatefilemanager.sync

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.materialswitch.MaterialSwitch
import za.kilowatch.ultimatefilemanager.R

class SyncProfileAdapter(
    private val onToggle: (SyncProfile, Boolean) -> Unit,
    private val onEdit: (SyncProfile) -> Unit,
    private val onDelete: (SyncProfile) -> Unit,
    private val onSyncNow: (SyncProfile) -> Unit
) : ListAdapter<SyncProfile, SyncProfileAdapter.ViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_sync_profile, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val txtName: TextView = view.findViewById(R.id.txtName)
        private val txtInterval: TextView = view.findViewById(R.id.txtInterval)
        private val txtSource: TextView = view.findViewById(R.id.txtSource)
        private val txtDest: TextView = view.findViewById(R.id.txtDest)
        private val txtLastSync: TextView = view.findViewById(R.id.txtLastSync)
        private val switchEnabled: MaterialSwitch = view.findViewById(R.id.switchEnabled)
        private val btnMenu: View = view.findViewById(R.id.btnMenu)

        fun bind(profile: SyncProfile) {
            txtName.text = profile.name
            
            if (profile.scheduleType == "interval") {
                txtInterval.text = itemView.context.getString(R.string.every_formatintervalprofileintervalminutes, formatInterval(profile.intervalMinutes))
            } else {
                val timeStr = "%02d:%02d".format(profile.scheduledHour, profile.scheduledMinute)
                txtInterval.text = when (profile.scheduledPeriod) {
                    "daily" -> itemView.context.getString(R.string.daily_at_timestr, timeStr)
                    "weekly" -> {
                        val days = arrayOf("", itemView.context.getString(R.string.mon), itemView.context.getString(R.string.tue), itemView.context.getString(R.string.wed), itemView.context.getString(R.string.thu), itemView.context.getString(R.string.fri), itemView.context.getString(R.string.sat), itemView.context.getString(R.string.sun))
                        val dayStr = days.getOrNull(profile.scheduledDayOfWeek) ?: "Day"
                        itemView.context.getString(R.string.weekly_on_daystr_at_timestr, dayStr, timeStr)
                    }
                    "monthly" -> {
                        val daySuffix = when (profile.scheduledDayOfMonth) {
                            1, 21, 31 -> "st"
                            2, 22 -> "nd"
                            3, 23 -> "rd"
                            else -> "th"
                        }
                        itemView.context.getString(R.string.monthly_on_profilescheduleddayofmonthdaysuffix_at_timestr, profile.scheduledDayOfMonth, daySuffix, timeStr)
                    }
                    else -> itemView.context.getString(R.string.scheduled_at_timestr, timeStr)
                }
            }

            txtSource.text = profile.localDisplayPath
            txtDest.text = profile.remotePath.ifEmpty { "/" }

            if (profile.lastSyncTime > 0) {
                val timeStr = android.text.format.DateUtils.getRelativeTimeSpanString(
                    profile.lastSyncTime, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS
                )
                txtLastSync.text = itemView.context.getString(R.string.last_sync_timestr_profilelastsyncfilecount_files, timeStr, profile.lastSyncFileCount)
            } else {
                txtLastSync.setText(R.string.never_synced)
            }

            switchEnabled.setOnCheckedChangeListener(null)
            switchEnabled.isChecked = profile.enabled
            switchEnabled.setOnCheckedChangeListener { _, isChecked ->
                onToggle(profile, isChecked)
            }

            btnMenu.setOnClickListener { v ->
                val inflater = LayoutInflater.from(v.context)
                val popupView = inflater.inflate(R.layout.popup_sync_profile_menu, null)
                val density = v.resources.displayMetrics.density
                val popupWidth = (200 * density).toInt()
                val popupWindow = android.widget.PopupWindow(
                    popupView,
                    popupWidth,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    true
                ).apply {
                    elevation = 16f * density
                    isOutsideTouchable = true
                    isFocusable = true
                    setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
                    animationStyle = android.R.style.Animation_Dialog
                }

                popupView.findViewById<View>(R.id.menuSyncNow).setOnClickListener {
                    popupWindow.dismiss()
                    onSyncNow(profile)
                }

                popupView.findViewById<View>(R.id.menuEdit).setOnClickListener {
                    popupWindow.dismiss()
                    onEdit(profile)
                }

                popupView.findViewById<View>(R.id.menuDelete).setOnClickListener {
                    popupWindow.dismiss()
                    onDelete(profile)
                }

                val xOffset = -(popupWidth - v.width)
                popupWindow.showAsDropDown(v, xOffset, (4 * density).toInt())
            }

            itemView.setOnClickListener {
                onEdit(profile)
            }
        }
        
        private fun formatInterval(minutes: Int): String {
            if (minutes < 60) return "${minutes}m"
            val hours = minutes / 60
            if (hours < 24) return "${hours}h"
            val days = hours / 24
            return "${days}d"
        }
    }

    object DiffCallback : DiffUtil.ItemCallback<SyncProfile>() {
        override fun areItemsTheSame(oldItem: SyncProfile, newItem: SyncProfile) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: SyncProfile, newItem: SyncProfile) = oldItem == newItem
    }
}
