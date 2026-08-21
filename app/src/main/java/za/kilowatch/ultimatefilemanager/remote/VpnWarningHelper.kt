package za.kilowatch.ultimatefilemanager.remote

import android.content.Context
import android.content.res.ColorStateList
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.view.LayoutInflater
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.util.DeviceUtils

/**
 * Checks for active VPN connections and shows a premium warning dialog
 * when the user attempts to start Remote Manage with a VPN active.
 *
 * The VPN masks the device's LAN IP, making the remote file server
 * unreachable from other devices on the local network.
 */
object VpnWarningHelper {

    /**
     * Returns true if the device currently has a VPN transport active.
     * Uses ConnectivityManager (API 23+), which covers all supported devices.
     */
    fun isVpnActive(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    /**
     * Shows a premium warning dialog informing the user that Remote Manage
     * will not work while a VPN is active. Uses separate layouts for
     * mobile and TV to match each platform's design language.
     *
     * The dialog has a single "Close" button that dismisses it.
     */
    fun showVpnWarningDialog(context: Context, onContinue: (() -> Unit)? = null) {
        val isTv = DeviceUtils.isTvDevice(context)
        val layoutRes = if (isTv) {
            R.layout.dialog_vpn_warning_tv
        } else {
            R.layout.dialog_vpn_warning
        }

        val dialogView = LayoutInflater.from(context).inflate(layoutRes, null)
        val btnClose = dialogView.findViewById<MaterialButton>(R.id.btnVpnClose)
        val btnContinue = dialogView.findViewById<MaterialButton>(R.id.btnVpnContinue)

        val dialog = MaterialAlertDialogBuilder(context)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        // Wire Close button
        btnClose.setOnClickListener { dialog.dismiss() }

        if (onContinue != null) {
            btnContinue.visibility = android.view.View.VISIBLE
            btnContinue.setOnClickListener {
                dialog.dismiss()
                onContinue.invoke()
            }
        } else {
            btnContinue.visibility = android.view.View.GONE
        }

        // TV: yellow highlight focus for the buttons (matches existing TV patterns)
        if (isTv) {
            val white = context.getColor(R.color.tv_text_primary)
            val black = context.getColor(R.color.tv_button_focused_yellow_text)
            val yellow = context.getColor(R.color.tv_button_focused_yellow)
            val yellowCsl = ColorStateList.valueOf(yellow)
            val amberCsl = ColorStateList.valueOf(0xFFFFA726.toInt())

            btnClose.backgroundTintList = amberCsl
            btnClose.setTextColor(context.getColor(R.color.white))

            btnClose.setOnFocusChangeListener { _, hasFocus ->
                btnClose.backgroundTintList = if (hasFocus) yellowCsl else amberCsl
                btnClose.setTextColor(if (hasFocus) black else context.getColor(R.color.white))
            }
            
            btnContinue.setOnFocusChangeListener { _, hasFocus ->
                btnContinue.strokeColor = if (hasFocus) yellowCsl else amberCsl
                btnContinue.setTextColor(if (hasFocus) white else 0xFFFFA726.toInt())
            }

            // Auto-focus the Continue button for D-pad navigation
            dialog.setOnShowListener {
                if (onContinue != null) {
                    btnContinue.requestFocus()
                } else {
                    btnClose.requestFocus()
                }
            }
        }

        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }
}
