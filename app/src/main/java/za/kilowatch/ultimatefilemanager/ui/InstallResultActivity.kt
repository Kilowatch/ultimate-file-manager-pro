package za.kilowatch.ultimatefilemanager.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import za.kilowatch.ultimatefilemanager.util.DeviceUtils

/**
 * Translucent activity that hosts UFM's Glassmorphic installation result dialog.
 * Displays success / failure feedback (including signature mismatch notifications)
 * with TV D-Pad and mobile touch support.
 */
class InstallResultActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        val statusMessage = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val packageName = intent.getStringExtra("packageName") ?: ""
        val appName = intent.getStringExtra("appName") ?: ""
        val fileName = intent.getStringExtra("fileName") ?: ""

        val isTv = DeviceUtils.isTvDevice(this)
        val layoutRes = if (isTv) R.layout.dialog_install_result_tv else R.layout.dialog_install_result
        val view = LayoutInflater.from(this).inflate(layoutRes, null)

        val isSuccess = (status == PackageInstaller.STATUS_SUCCESS)

        val imgIcon = view.findViewById<ImageView>(R.id.imgResultIcon)
        val txtTitle = view.findViewById<TextView>(R.id.txtResultTitle)
        val txtAppName = view.findViewById<TextView>(R.id.txtResultAppName)
        val txtMessage = view.findViewById<TextView>(R.id.txtResultMessage)
        val btnDismiss = view.findViewById<MaterialButton>(R.id.btnDismiss)
        val btnOpen = view.findViewById<MaterialButton>(R.id.btnOpen)

        val displayName = when {
            appName.isNotBlank() -> appName
            fileName.isNotBlank() -> fileName
            packageName.isNotBlank() -> packageName
            else -> getString(R.string.app_name)
        }

        if (displayName.isNotBlank() && displayName != getString(R.string.app_name)) {
            txtAppName.text = displayName
            txtAppName.visibility = View.VISIBLE
        } else {
            txtAppName.visibility = View.GONE
        }

        if (isSuccess) {
            txtTitle.text = getString(R.string.installation_successful)
            txtMessage.text = getString(R.string.installation_successful_desc, displayName)
            imgIcon.setImageResource(android.R.drawable.stat_sys_download_done)
            imgIcon.imageTintList = ColorStateList.valueOf(
                if (isTv) ContextCompat.getColor(this, R.color.tv_button_focused_yellow) else Color.parseColor("#10B981")
            )

            val launchIntent = if (packageName.isNotBlank()) {
                packageManager.getLaunchIntentForPackage(packageName)
            } else null

            if (launchIntent != null) {
                btnOpen.visibility = View.VISIBLE
                btnOpen.setOnClickListener {
                    try {
                        startActivity(launchIntent)
                    } catch (e: Exception) {
                        // Ignore failure to launch
                    }
                    finish()
                }
            } else {
                btnOpen.visibility = View.GONE
            }

            btnDismiss.text = getString(R.string.btn_ok)
            btnDismiss.setOnClickListener { finish() }

        } else {
            txtTitle.text = getString(R.string.installation_failed)
            imgIcon.setImageResource(android.R.drawable.ic_dialog_alert)
            imgIcon.imageTintList = ColorStateList.valueOf(Color.parseColor("#EF4444")) // Crimson Red

            val errorDetail = when (status) {
                PackageInstaller.STATUS_FAILURE_CONFLICT ->
                    getString(R.string.install_error_signature_mismatch)
                PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                    getString(R.string.install_error_incompatible)
                PackageInstaller.STATUS_FAILURE_STORAGE ->
                    getString(R.string.install_error_storage)
                PackageInstaller.STATUS_FAILURE_INVALID ->
                    getString(R.string.install_error_invalid)
                PackageInstaller.STATUS_FAILURE_BLOCKED ->
                    getString(R.string.install_error_blocked)
                PackageInstaller.STATUS_FAILURE_ABORTED ->
                    getString(R.string.install_error_aborted)
                else -> {
                    val msg = if (!statusMessage.isNull_or_blank()) statusMessage else "Code $status"
                    getString(R.string.install_error_generic, msg)
                }
            }
            txtMessage.text = errorDetail

            btnOpen.visibility = View.GONE
            btnDismiss.text = getString(R.string.btn_ok)
            btnDismiss.setOnClickListener { finish() }
        }

        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(view)
            .setCancelable(true)
            .setOnDismissListener { finish() }
            .create()

        dialog.window?.setBackgroundDrawableResource(R.drawable.bg_dialog_glass)
        dialog.show()

        if (isTv) {
            val focusTarget = if (isSuccess && btnOpen.visibility == View.VISIBLE) btnOpen else btnDismiss
            focusTarget.requestFocus()
        }
    }

    private fun String?.isNull_or_blank(): Boolean = this == null || this.trim().isEmpty()

    companion object {
        fun createIntent(
            context: Context,
            status: Int,
            statusMessage: String?,
            packageName: String?,
            appName: String?,
            fileName: String?
        ): Intent {
            return Intent(context, InstallResultActivity::class.java).apply {
                putExtra(PackageInstaller.EXTRA_STATUS, status)
                putExtra(PackageInstaller.EXTRA_STATUS_MESSAGE, statusMessage)
                putExtra("packageName", packageName)
                putExtra("appName", appName)
                putExtra("fileName", fileName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        }
    }
}
