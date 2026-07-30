package za.kilowatch.ultimatefilemanager.ui

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.view.View
import rikka.shizuku.Shizuku
import android.content.pm.PackageManager
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.databinding.ActivityShizukuBinding
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

import za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper

/**
 * Elevated Access (Shizuku / Shevery) Activity for Mobile.
 */
class ShizukuActivity : AppCompatActivity() {

    private lateinit var binding: ActivityShizukuBinding
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
        enableEdgeToEdge()
        binding = ActivityShizukuBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        updateShizukuStatus()

        binding.btnShizukuDownload.setOnClickListener {
            val url = "https://github.com/thedjchi/Shizuku/releases/latest"
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
            startActivity(intent)
        }

        binding.btnSheveryDownload.setOnClickListener {
            val url = "https://github.com/HmnDev-Tech/shevery/releases/latest"
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
            startActivity(intent)
        }

        binding.btnShizukuEnable.setOnClickListener {
            if (ShizukuShellWrapper.tryBindShevery(this)) {
                if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    showAuthDialog()
                }
            } else {
                startShizukuService()
            }
        }

        Shizuku.addRequestPermissionResultListener(permissionListener)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    private fun startShizukuService() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_shizuku_starting, null)
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialog.show()

        if (ShizukuShellWrapper.isSheveryInstalled(this)) {
            try {
                val intent = android.content.Intent("com.hamondev.shevery.START")
                intent.component = android.content.ComponentName("com.hamondev.shevery", "com.hamondev.shevery.receiver.ManualStartReceiver")
                sendBroadcast(intent)
            } catch (e: Exception) {
                try {
                    val launchIntent = packageManager.getLaunchIntentForPackage(ShizukuShellWrapper.SHEVERY_PACKAGE)
                    if (launchIntent != null) startActivity(launchIntent)
                } catch (ex: Exception) {
                    ex.printStackTrace()
                }
            }
        } else {
            val intent = android.content.Intent("moe.shizuku.privileged.api.START")
            intent.component = android.content.ComponentName("moe.shizuku.privileged.api", "moe.shizuku.manager.receiver.ManualStartReceiver")
            sendBroadcast(intent)
        }
        
        lifecycleScope.launch {
            delay(3000)
            dialog.dismiss()
            updateShizukuStatus()
            if (!ShizukuShellWrapper.tryBindShevery(this@ShizukuActivity)) {
                android.widget.Toast.makeText(this@ShizukuActivity, R.string.shizuku_start_failed, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showAuthDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_shizuku_auth, null)
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialogView.findViewById<android.widget.Button>(R.id.btnAuthorize).setOnClickListener {
            Shizuku.requestPermission(SHIZUKU_CODE)
            dialog.dismiss()
        }

        dialogView.findViewById<android.widget.Button>(R.id.btnCancel).setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
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

        if (ShizukuShellWrapper.isElevatedManagerInstalled(this)) {
            binding.txtShizukuStatus.text = getString(R.string.shizuku_installed)
            binding.txtShizukuStatus.setTextColor(getColor(R.color.shizuku_status_ok))
            binding.txtShizukuDescription.text = getString(R.string.shizuku_installed_msg)
            binding.txtShizukuDescription.visibility = View.VISIBLE
            
            binding.txtShizukuDownloadHint.visibility = View.GONE
            binding.btnShizukuDownload.visibility = View.GONE
            binding.btnSheveryDownload.visibility = View.GONE

            // Service Status
            binding.txtShizukuServiceStatusLabel.visibility = View.VISIBLE
            binding.txtShizukuServiceStatus.visibility = View.VISIBLE

            if (ShizukuShellWrapper.tryBindShevery(this)) {
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
            binding.btnSheveryDownload.visibility = View.VISIBLE

            binding.txtShizukuServiceStatusLabel.visibility = View.GONE
            binding.txtShizukuServiceStatus.visibility = View.GONE
            binding.btnShizukuEnable.visibility = View.GONE
        }
    }
}
