package za.kilowatch.ultimatefilemanager.server

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.util.DeviceUtils

/**
 * RecyclerView adapter for displaying [FtpServerProfile] items.
 */
class ServerProfileAdapter(
    private val onEdit: (FtpServerProfile) -> Unit,
    private val onDelete: (FtpServerProfile) -> Unit
) : RecyclerView.Adapter<ServerProfileAdapter.ProfileViewHolder>() {

    private val profiles = mutableListOf<FtpServerProfile>()

    fun submitList(newProfiles: List<FtpServerProfile>) {
        profiles.clear()
        profiles.addAll(newProfiles)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProfileViewHolder {
        val layoutRes = if (DeviceUtils.isTvDevice(parent.context)) {
            R.layout.item_server_profile_tv
        } else {
            R.layout.item_server_profile
        }
        val view = LayoutInflater.from(parent.context).inflate(layoutRes, parent, false)
        return ProfileViewHolder(view)
    }

    override fun onBindViewHolder(holder: ProfileViewHolder, position: Int) {
        holder.bind(profiles[position])
    }

    override fun getItemCount(): Int = profiles.size

    inner class ProfileViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val txtUsername: TextView = itemView.findViewById(R.id.txtUsername)
        private val txtLocationLabel: TextView = itemView.findViewById(R.id.txtLocationLabel)
        private val txtReadOnlyBadge: TextView = itemView.findViewById(R.id.txtReadOnlyBadge)
        private val txtAuthType: TextView = itemView.findViewById(R.id.txtAuthType)
        private val layoutWarning: View? = itemView.findViewById(R.id.layoutWarning)
        private val btnEdit: ImageView = itemView.findViewById(R.id.btnEdit)
        private val btnDelete: ImageView = itemView.findViewById(R.id.btnDelete)
        private val ctx = itemView.context

        fun bind(profile: FtpServerProfile) {
            txtUsername.text = profile.username
            txtLocationLabel.text = profile.defaultLocationLabel.ifEmpty {
                profile.defaultLocationUri
            }
            txtReadOnlyBadge.visibility = if (profile.readOnly) View.VISIBLE else View.GONE

            if (profile.isCredentialsStripped) {
                layoutWarning?.visibility = View.VISIBLE
            } else {
                layoutWarning?.visibility = View.GONE
            }

            val hasPassword = profile.encryptedPassword.isNotEmpty()
            val hasKeys = profile.authorizedKeys.isNotEmpty()
            txtAuthType.text = when {
                hasPassword && hasKeys -> ctx.getString(R.string.auth_type_both)
                hasPassword -> ctx.getString(R.string.auth_type_password)
                hasKeys -> ctx.getString(R.string.auth_type_ssh_key)
                else -> ""
            }
            txtAuthType.visibility = if (hasPassword || hasKeys) View.VISIBLE else View.GONE
            val authColor = when {
                hasPassword && hasKeys -> 0xFF4ADE80.toInt()  // green
                hasPassword -> 0xFF42A5F5.toInt()              // blue
                else -> 0xFFAB47BC.toInt()                     // purple for SSH key only
            }
            txtAuthType.setTextColor(authColor)

            btnEdit.setOnClickListener { onEdit(profile) }
            btnDelete.setOnClickListener { onDelete(profile) }

            if (DeviceUtils.isTvDevice(itemView.context)) {
                // TV entry: OK on row -> focus Edit button
                itemView.setOnClickListener {
                    btnEdit.requestFocus()
                }

                // TV exit: Back on buttons -> focus row
                val backToRowListener = View.OnKeyListener { _, keyCode, event ->
                    if (keyCode == android.view.KeyEvent.KEYCODE_BACK && event.action == android.view.KeyEvent.ACTION_UP) {
                        itemView.requestFocus()
                        true
                    } else {
                        false
                    }
                }
                btnEdit.setOnKeyListener(backToRowListener)
                btnDelete.setOnKeyListener(backToRowListener)
            }
        }
    }
}
