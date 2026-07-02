package za.kilowatch.ultimatefilemanager.sync.advanced

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

class AdvancedSyncProfileAdapter(
    private val onToggle: (AdvancedSyncProfile, Boolean) -> Unit,
    private val onEdit: (AdvancedSyncProfile) -> Unit,
    private val onDelete: (AdvancedSyncProfile) -> Unit,
    private val onSyncNow: (AdvancedSyncProfile) -> Unit,
    private val onViewConflictLog: (AdvancedSyncProfile) -> Unit
) : ListAdapter<AdvancedSyncProfile, AdvancedSyncProfileAdapter.ViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val isTv = za.kilowatch.ultimatefilemanager.util.DeviceUtils.isTvDevice(parent.context)
        val layoutRes = if (isTv) R.layout.item_advanced_sync_profile_tv else R.layout.item_advanced_sync_profile
        val view = LayoutInflater.from(parent.context).inflate(layoutRes, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val iconDirection: ImageView = view.findViewById(R.id.iconDirection)
        private val iconActiveDot: ImageView? = view.findViewById(R.id.iconActiveDot)
        private val txtName: TextView = view.findViewById(R.id.txtName)
        private val txtScheduleType: TextView = view.findViewById(R.id.txtScheduleType)
        private val iconInstantSync: ImageView? = view.findViewById(R.id.iconInstantSync)
        private val iconWifiOnly: ImageView? = view.findViewById(R.id.iconWifiOnly)
        private val txtSource: TextView = view.findViewById(R.id.txtSource)
        private val txtDest: TextView = view.findViewById(R.id.txtDest)
        private val txtLastSync: TextView = view.findViewById(R.id.txtLastSync)
        private val switchEnabled: MaterialSwitch = view.findViewById(R.id.switchEnabled)
        private val btnMenu: ImageView = view.findViewById(R.id.btnMenu)

        fun bind(profile: AdvancedSyncProfile) {
            txtName.text = profile.name

            // Direction icon
            when (profile.direction) {
                "download" -> {
                    iconDirection.setImageResource(R.drawable.ic_direction_download)
                    iconDirection.contentDescription = itemView.context.getString(R.string.sync_direction_download)
                }
                "twoway" -> {
                    iconDirection.setImageResource(R.drawable.ic_direction_twoway)
                    iconDirection.contentDescription = itemView.context.getString(R.string.sync_direction_twoway)
                }
                else -> {
                    iconDirection.setImageResource(R.drawable.ic_direction_upload)
                    iconDirection.contentDescription = itemView.context.getString(R.string.sync_direction_upload)
                }
            }

            // Green dot — active if InstantSyncWatcher is watching
            iconActiveDot?.visibility =
                if (InstantSyncWatcher.isWatching(profile.id)) View.VISIBLE else View.GONE

            // Schedule type label
            if (profile.scheduleType == "manual") {
                txtScheduleType.setText(R.string.manual)
            } else if (profile.scheduleType == "interval") {
                txtScheduleType.text = itemView.context.getString(
                    R.string.every_formatintervalprofileintervalminutes,
                    formatInterval(profile.intervalMinutes)
                )
            } else {
                val timeStr = "%02d:%02d".format(profile.scheduledHour, profile.scheduledMinute)
                txtScheduleType.text = when (profile.scheduledPeriod) {
                    "daily" -> itemView.context.getString(R.string.daily_at_timestr, timeStr)
                    "weekly" -> {
                        val days = arrayOf("", itemView.context.getString(R.string.mon),
                            itemView.context.getString(R.string.tue), itemView.context.getString(R.string.wed),
                            itemView.context.getString(R.string.thu), itemView.context.getString(R.string.fri),
                            itemView.context.getString(R.string.sat), itemView.context.getString(R.string.sun))
                        val dayStr = days.getOrNull(profile.scheduledDayOfWeek) ?: "Day"
                        itemView.context.getString(R.string.weekly_on_daystr_at_timestr, dayStr, timeStr)
                    }
                    "monthly" -> itemView.context.getString(R.string.monthly_on_profilescheduleddayofmonthdaysuffix_at_timestr,
                        profile.scheduledDayOfMonth, getDaySuffix(profile.scheduledDayOfMonth), timeStr)
                    else -> itemView.context.getString(R.string.scheduled_at_timestr, timeStr)
                }
            }

            // Trigger indicator icons
            iconInstantSync?.visibility =
                if (profile.instantSyncEnabled) View.VISIBLE else View.GONE
            iconWifiOnly?.visibility =
                if (profile.wifiOnly) View.VISIBLE else View.GONE

            // Paths
            txtSource.text = profile.localDisplayPath
            txtDest.text = if (profile.destLocalUri.isNotEmpty()) {
                profile.destLocalDisplayPath
            } else {
                profile.remotePath.ifEmpty { "/" }
            }

            // Last sync
            if (profile.lastSyncTime > 0) {
                val timeStr = android.text.format.DateUtils.getRelativeTimeSpanString(
                    profile.lastSyncTime, System.currentTimeMillis(),
                    android.text.format.DateUtils.MINUTE_IN_MILLIS
                )
                txtLastSync.text = itemView.context.getString(
                    R.string.last_sync_timestr_profilelastsyncfilecount_files, timeStr, profile.lastSyncFileCount
                )
            } else {
                txtLastSync.setText(R.string.never_synced)
            }

            // Enabled switch
            switchEnabled.setOnCheckedChangeListener(null)
            switchEnabled.isChecked = profile.enabled
            switchEnabled.setOnCheckedChangeListener { _, isChecked ->
                onToggle(profile, isChecked)
            }

            // Menu
            btnMenu.setOnClickListener { v ->
                val popup = PopupMenu(v.context, v)
                popup.menu.add(0, 1, 0, itemView.context.getString(R.string.edit))
                popup.menu.add(0, 2, 0, itemView.context.getString(R.string.sync_now))
                popup.menu.add(0, 3, 0, itemView.context.getString(R.string.conflict_log_title))
                popup.menu.add(0, 4, 0, itemView.context.getString(R.string.action_delete))
                popup.setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        1 -> { onEdit(profile); true }
                        2 -> { onSyncNow(profile); true }
                        3 -> { onViewConflictLog(profile); true }
                        4 -> { onDelete(profile); true }
                        else -> false
                    }
                }
                popup.show()
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

        private fun getDaySuffix(day: Int): String = when (day) {
            1, 21, 31 -> "st"; 2, 22 -> "nd"; 3, 23 -> "rd"; else -> "th"
        }
    }

    object DiffCallback : DiffUtil.ItemCallback<AdvancedSyncProfile>() {
        override fun areItemsTheSame(oldItem: AdvancedSyncProfile, newItem: AdvancedSyncProfile) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: AdvancedSyncProfile, newItem: AdvancedSyncProfile) = oldItem == newItem
    }
}
