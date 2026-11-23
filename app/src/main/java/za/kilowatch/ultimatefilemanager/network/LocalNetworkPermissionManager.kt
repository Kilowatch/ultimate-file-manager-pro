package za.kilowatch.ultimatefilemanager.network

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.LayoutInflater
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.util.DeviceUtils

/**
 * Central manager responsible for Android 17 (API 37) Local Network Protection (LNP)
 * runtime permission checks, contextual rationales, and recovery flows.
 */
object LocalNetworkPermissionManager {

    const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
    private const val PREFS_NAME = "ufm_local_network_prefs"
    private const val KEY_HAS_REQUESTED_BEFORE = "has_requested_local_network"

    /**
     * Local Network Protection is officially enforced for Android 17 (API 37)+.
     */
    fun isEnforced(): Boolean = Build.VERSION.SDK_INT >= 37

    /**
     * Checks if local network access is granted. Always returns true on pre-Android 17 devices.
     */
    fun hasPermission(context: Context): Boolean {
        if (!isEnforced()) return true
        return ContextCompat.checkSelfPermission(
            context,
            PERMISSION
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Loopback / localhost connections (e.g., local proxy, on-device debugging)
     * are exempted from Local Network Protection.
     */
    fun isLoopbackAddress(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val trimmed = host.trim().lowercase()
        return trimmed == "localhost" ||
                trimmed == "127.0.0.1" ||
                trimmed == "::1" ||
                trimmed == "0:0:0:0:0:0:0:1"
    }

    /**
     * Checks if permission was permanently denied by the user ("Don't ask again").
     */
    fun isPermanentlyDenied(activity: Activity): Boolean {
        if (!isEnforced()) return false
        if (hasPermission(activity)) return false
        val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val hasRequested = prefs.getBoolean(KEY_HAS_REQUESTED_BEFORE, false)
        return hasRequested && !ActivityCompat.shouldShowRequestPermissionRationale(activity, PERMISSION)
    }

    private fun markRequested(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_HAS_REQUESTED_BEFORE, true)
            .apply()
    }

    /**
     * Navigates directly to the system App Details / Permissions Settings page for UFM.
     */
    fun openAppPermissionSettings(activity: Activity) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", activity.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
        } catch (_: Exception) {
            val fallback = Intent(Settings.ACTION_SETTINGS)
            activity.startActivity(fallback)
        }
    }

    /**
     * Contextual entry point: Checks permission and triggers UFMStandard rationale dialog
     * before delegating to the OS runtime request launcher.
     */
    fun checkOrRequest(
        activity: ComponentActivity,
        launcher: ActivityResultLauncher<String>?,
        onGranted: () -> Unit,
        onDenied: () -> Unit
    ) {
        if (hasPermission(activity)) {
            onGranted()
            return
        }

        showRationaleDialog(
            activity = activity,
            launcher = launcher,
            onGranted = onGranted,
            onDenied = onDenied
        )
    }

    /**
     * Displays the UFMStandard Frosted Glass Rationale Dialog (mobile or TV).
     */
    fun showRationaleDialog(
        activity: Activity,
        launcher: ActivityResultLauncher<String>?,
        onGranted: (() -> Unit)? = null,
        onDenied: (() -> Unit)? = null
    ) {
        val isTv = DeviceUtils.isTvDevice(activity)
        val layoutRes = if (isTv) {
            R.layout.dialog_local_network_rationale_tv
        } else {
            R.layout.dialog_local_network_rationale
        }

        val dialogView = LayoutInflater.from(activity).inflate(layoutRes, null)
        val isPermDenied = isPermanentlyDenied(activity)

        val dialog = MaterialAlertDialogBuilder(activity, R.style.UFM_Dialog)
            .setView(dialogView)
            .setCancelable(true)
            .setOnCancelListener { onDenied?.invoke() }
            .create()

        val btnPrimary = dialogView.findViewById<MaterialButton>(R.id.btnLocalNetPrimary)
        val btnSecondary = dialogView.findViewById<MaterialButton>(R.id.btnLocalNetSecondary)
        val txtMessage = dialogView.findViewById<TextView>(R.id.txtLocalNetMessage)

        if (isPermDenied) {
            btnPrimary?.setText(R.string.local_network_btn_settings)
            txtMessage?.setText(R.string.local_network_banner_text)
        } else {
            btnPrimary?.setText(R.string.local_network_btn_continue)
            txtMessage?.setText(R.string.local_network_rationale_message)
        }

        btnPrimary?.setOnClickListener {
            dialog.dismiss()
            if (isPermDenied) {
                openAppPermissionSettings(activity)
            } else {
                markRequested(activity)
                if (launcher != null) {
                    launcher.launch(PERMISSION)
                } else if (activity is ComponentActivity) {
                    ActivityCompat.requestPermissions(activity, arrayOf(PERMISSION), 7101)
                }
            }
        }

        btnSecondary?.setOnClickListener {
            dialog.dismiss()
            onDenied?.invoke()
        }

        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    }
}
