package za.kilowatch.ultimatefilemanager.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.network.PairedDevice

class PairedDeviceAdapter(
    private val isTvLayout: Boolean,
    private val onConnectClick: (PairedDevice, Boolean) -> Unit,
    private val onEditNameClick: (PairedDevice) -> Unit,
    private val onDeleteClick: (PairedDevice) -> Unit
) : RecyclerView.Adapter<PairedDeviceAdapter.ViewHolder>() {

    private val devices = mutableListOf<PairedDevice>()

    fun setDevices(newDevices: List<PairedDevice>) {
        devices.clear()
        devices.addAll(newDevices)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val layoutId = if (isTvLayout) R.layout.item_paired_device_tv else R.layout.item_paired_device
        val view = LayoutInflater.from(parent.context).inflate(layoutId, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(devices[position])
    }

    override fun getItemCount(): Int = devices.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val txtDeviceName: TextView = itemView.findViewById(R.id.txtDeviceName)
        private val txtStatus: TextView = itemView.findViewById(R.id.txtStatus)
        private val btnConnect: Button = itemView.findViewById(R.id.btnConnect)
        private val btnDelete: View = itemView.findViewById(R.id.btnDelete)
        private val btnEditName: View = itemView.findViewById(R.id.btnEditName)
        private val imgIcon: ImageView = itemView.findViewById(R.id.imgDeviceIcon)

        fun bind(device: PairedDevice) {
            txtDeviceName.text = device.name
            
            val deviceTypeLabel = if (device.isTv) "TV" else "Phone"

            if (device.isTv) {
                imgIcon.setImageResource(R.drawable.ic_tv)
            } else {
                imgIcon.setImageResource(R.drawable.ic_phone)
            }

            if (device.isConnected) {
                txtStatus.text = itemView.context.getString(R.string.devicetypelabel_connected, device.lastIp)
                txtStatus.setTextColor(itemView.context.getColor(
                    if (isTvLayout) android.R.color.holo_green_light else android.R.color.holo_green_dark
                ))
                btnConnect.setText(R.string.disconnect)
            } else {
                txtStatus.text = itemView.context.getString(R.string.devicetypelabel_disconnected, device.lastIp)
                txtStatus.setTextColor(itemView.context.getColor(
                    if (isTvLayout) R.color.tv_text_secondary else R.color.mobile_text_secondary
                ))
                btnConnect.setText(R.string.connect)
            }

            btnConnect.setOnClickListener {
                onConnectClick(device, !device.isConnected)
            }
            btnEditName.setOnClickListener {
                onEditNameClick(device)
            }
            btnDelete.setOnClickListener {
                onDeleteClick(device)
            }

            if (isTvLayout) {
                // Initial state: buttons are not focusable so we can scroll rows
                btnEditName.isFocusable = false
                btnConnect.isFocusable = false
                btnDelete.isFocusable = false

                itemView.setOnKeyListener { _, keyCode, event ->
                    if (event.action == android.view.KeyEvent.ACTION_DOWN && 
                        (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER || keyCode == android.view.KeyEvent.KEYCODE_ENTER)) {
                        // Mode: Enter selection - enable and focus buttons
                        btnEditName.isFocusable = true
                        btnConnect.isFocusable = true
                        btnDelete.isFocusable = true
                        btnConnect.requestFocus()
                        true
                    } else {
                        false
                    }
                }

                val backKeyListener = android.view.View.OnKeyListener { _, keyCode, event ->
                    if (event.action == android.view.KeyEvent.ACTION_DOWN && keyCode == android.view.KeyEvent.KEYCODE_BACK) {
                        // Mode: Exit selection - disable buttons and focus row
                        btnEditName.isFocusable = false
                        btnConnect.isFocusable = false
                        btnDelete.isFocusable = false
                        itemView.requestFocus()
                        true
                    } else {
                        false
                    }
                }
                btnEditName.setOnKeyListener(backKeyListener)
                btnConnect.setOnKeyListener(backKeyListener)
                btnDelete.setOnKeyListener(backKeyListener)
            }
        }
    }
}
