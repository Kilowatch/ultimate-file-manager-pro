package za.kilowatch.ultimatefilemanager.ui

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import rikka.shizuku.Shizuku
import android.content.pm.PackageManager
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.databinding.ActivityShizukuTvBinding
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Shizuku Activity for TV.
 */
class ShizukuTvActivity : AppCompatActivity() {

    private lateinit var binding: ActivityShizukuTvBinding
    private var statusAnimator: android.animation.ObjectAnimator? = null

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(za.kilowatch.ultimatefilemanager.settings.LocaleHelper.wrap(newBase))
    }

    private val SHIZUKU_CODE = 1001
    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == SHIZUKU_CODE) {
            updateShizukuStatus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityShizukuTvBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnBack.requestFocus()

        updateShizukuStatus()

        binding.btnShizukuDownload.setOnClickListener {
            showDownloadDialog()
        }

        binding.btnShizukuEnable.setOnClickListener {
            if (Shizuku.pingBinder()) {
                if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    showAuthDialog()
                }
            } else {
                startShizukuService()
            }
        }

        Shizuku.addRequestPermissionResultListener(permissionListener)
    }

    private fun startShizukuService() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_shizuku_starting, null)
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialog.show()

        val intent = android.content.Intent("moe.shizuku.privileged.api.START")
        intent.component = android.content.ComponentName("moe.shizuku.privileged.api", "moe.shizuku.manager.receiver.ManualStartReceiver")
        sendBroadcast(intent)
        
        lifecycleScope.launch {
            delay(3000)
            dialog.dismiss()
            updateShizukuStatus()
            if (!Shizuku.pingBinder()) {
                android.widget.Toast.makeText(this@ShizukuTvActivity, R.string.shizuku_start_failed, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showAuthDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_shizuku_auth, null)
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        val btnAuth = dialogView.findViewById<android.widget.Button>(R.id.btnAuthorize)
        val btnCancel = dialogView.findViewById<android.widget.Button>(R.id.btnCancel)

        btnAuth.setOnClickListener {
            Shizuku.requestPermission(SHIZUKU_CODE)
            dialog.dismiss()
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
        btnAuth.requestFocus()
    }

    private fun showDownloadDialog() {
        val url = "https://github.com/thedjchi/Shizuku/releases/latest"
        
        val dialogView = layoutInflater.inflate(R.layout.dialog_shizuku_download_tv, null)
        val txtMsg = dialogView.findViewById<android.widget.TextView>(R.id.txtDialogMsg)
        val btnCopy = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCopyLink)
        val btnClose = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnClose)

        txtMsg.text = getString(R.string.shizuku_tv_download_msg, url)

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .create()

        btnCopy.setOnClickListener {
            val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("Shizuku Link", url)
            clipboard.setPrimaryClip(clip)
            android.widget.Toast.makeText(this, R.string.shizuku_link_copied, android.widget.Toast.LENGTH_SHORT).show()
        }

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
        btnCopy.requestFocus()
    }

    override fun onDestroy() {
        super.onDestroy()
        statusAnimator?.cancel()
        Shizuku.removeRequestPermissionResultListener(permissionListener)
    }

    override fun onResume() {
        super.onResume()
        updateShizukuStatus()
    }

    private fun updateShizukuStatus() {
        statusAnimator?.cancel()
        statusAnimator = null
        binding.txtShizukuStatus.alpha = 1f

        if (isShizukuInstalled()) {
            binding.txtShizukuStatus.text = getString(R.string.shizuku_installed)
            binding.txtShizukuStatus.setTextColor(getColor(R.color.shizuku_status_ok))
            binding.txtShizukuDescription.text = getString(R.string.shizuku_installed_msg)
            binding.txtShizukuDescription.visibility = View.VISIBLE
            
            binding.txtShizukuDownloadHint.visibility = View.GONE
            binding.btnShizukuDownload.visibility = View.GONE

            // Service Status
            binding.txtShizukuServiceStatusLabel.visibility = View.VISIBLE
            binding.txtShizukuServiceStatus.visibility = View.VISIBLE

            if (Shizuku.pingBinder()) {
                if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                    binding.txtShizukuServiceStatus.text = getString(R.string.shizuku_authorized)
                    binding.txtShizukuServiceStatus.setTextColor(getColor(R.color.shizuku_status_ok))
                    binding.btnShizukuEnable.visibility = View.GONE
                    
                    binding.txtShizukuStatus.text = getString(R.string.shizuku_enabled_and_active)
                    binding.txtShizukuStatus.setTextColor(getColor(R.color.shizuku_status_ok))
                    statusAnimator = android.animation.ObjectAnimator.ofFloat(binding.txtShizukuStatus, "alpha", 1f, 0.3f, 1f).apply {
                        duration = 1000
                        repeatCount = android.animation.ObjectAnimator.INFINITE
                        start()
                    }
                } else {
                    binding.txtShizukuServiceStatus.text = getString(R.string.shizuku_not_authorized)
                    binding.txtShizukuServiceStatus.setTextColor(getColor(R.color.shizuku_status_error))
                    binding.btnShizukuEnable.text = getString(R.string.shizuku_btn_authorize)
                    binding.btnShizukuEnable.visibility = View.VISIBLE
                }
            } else {
                binding.txtShizukuServiceStatus.text = getString(R.string.shizuku_service_not_running)
                binding.txtShizukuServiceStatus.setTextColor(getColor(R.color.shizuku_status_error))
                binding.btnShizukuEnable.text = getString(R.string.shizuku_btn_start_service)
                binding.btnShizukuEnable.visibility = View.VISIBLE
            }
        } else {
            binding.txtShizukuStatus.text = getString(R.string.shizuku_not_installed)
            binding.txtShizukuStatus.setTextColor(getColor(R.color.shizuku_status_error))
            binding.txtShizukuDescription.text = getString(R.string.shizuku_description)
            binding.txtShizukuDescription.visibility = View.VISIBLE
            
            binding.txtShizukuDownloadHint.visibility = View.VISIBLE
            binding.btnShizukuDownload.visibility = View.VISIBLE

            binding.txtShizukuServiceStatusLabel.visibility = View.GONE
            binding.txtShizukuServiceStatus.visibility = View.GONE
            binding.btnShizukuEnable.visibility = View.GONE
        }
    }

    private fun isShizukuInstalled(): Boolean {
        return try {
            packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
            true
        } catch (e: Exception) {
            false
        }
    }
}
