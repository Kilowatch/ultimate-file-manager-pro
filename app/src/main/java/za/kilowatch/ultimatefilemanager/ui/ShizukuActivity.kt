package za.kilowatch.ultimatefilemanager.ui

import android.animation.ObjectAnimator
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.databinding.ActivityShizukuBinding
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper

/**
 * Elevated Access (Shizuku / Shevery) Activity for Mobile — Modern Material 3 & Glassmorphism Design.
 */
class ShizukuActivity : AppCompatActivity() {

    private lateinit var binding: ActivityShizukuBinding
    private var statusAnimator: ObjectAnimator? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    private val SHIZUKU_CODE = 1001
    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, _ ->
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

        // Download actions
        binding.btnShizukuDownload.setOnClickListener {
            val url = "https://github.com/thedjchi/Shizuku/releases/latest"
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }

        binding.btnSheveryDownload.setOnClickListener {
            val url = "https://github.com/HmnDev-Tech/shevery/releases/latest"
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }

        // Open installed manager app actions
        binding.btnOpenShizuku.setOnClickListener {
            openAppPackage(ShizukuShellWrapper.SHIZUKU_PACKAGE)
        }

        binding.btnOpenShevery.setOnClickListener {
            openAppPackage(ShizukuShellWrapper.SHEVERY_PACKAGE)
        }

        // Service Recheck action
        binding.btnRecheckStatus.setOnClickListener {
            updateShizukuStatus()
            Toast.makeText(this, R.string.shizuku_recheck_status, Toast.LENGTH_SHORT).show()
        }

        // Primary Action (Start service or Authorize)
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

        updateShizukuStatus()
    }

    private fun openAppPackage(packageName: String) {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            startActivity(launchIntent)
        } else {
            Toast.makeText(this, R.string.shizuku_manager_open_error, Toast.LENGTH_SHORT).show()
        }
    }

    private fun startShizukuService() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_shizuku_starting, null)
        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialog.show()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))

        if (ShizukuShellWrapper.isSheveryInstalled(this)) {
            try {
                val intent = Intent("com.hamondev.shevery.START").apply {
                    component = ComponentName("com.hamondev.shevery", "com.hamondev.shevery.receiver.ManualStartReceiver")
                }
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
            val intent = Intent("moe.shizuku.privileged.api.START").apply {
                component = ComponentName("moe.shizuku.privileged.api", "moe.shizuku.manager.receiver.ManualStartReceiver")
            }
            sendBroadcast(intent)
        }

        lifecycleScope.launch {
            delay(3000)
            dialog.dismiss()
            updateShizukuStatus()
            if (!ShizukuShellWrapper.tryBindShevery(this@ShizukuActivity)) {
                Toast.makeText(this@ShizukuActivity, R.string.shizuku_start_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showAuthDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_shizuku_auth, null)
        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialogView.findViewById<View>(R.id.btnAuthorize).setOnClickListener {
            Shizuku.requestPermission(SHIZUKU_CODE)
            dialog.dismiss()
        }

        dialogView.findViewById<View>(R.id.btnCancel).setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
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
        binding.imgStatusIcon.alpha = 1f

        val isShizuku = ShizukuShellWrapper.isShizukuInstalled(this)
        val isShevery = ShizukuShellWrapper.isSheveryInstalled(this)
        val isManagerInstalled = isShizuku || isShevery

        // Manager download vs open buttons
        if (isShizuku) {
            binding.btnShizukuDownload.visibility = View.GONE
            binding.btnOpenShizuku.text = getString(R.string.shizuku_open_manager, "Shizuku")
            binding.btnOpenShizuku.visibility = View.VISIBLE
        } else {
            binding.btnShizukuDownload.visibility = View.VISIBLE
            binding.btnOpenShizuku.visibility = View.GONE
        }

        if (isShevery) {
            binding.btnSheveryDownload.visibility = View.GONE
            binding.btnOpenShevery.text = getString(R.string.shizuku_open_manager, "Shevery")
            binding.btnOpenShevery.visibility = View.VISIBLE
        } else {
            binding.btnSheveryDownload.visibility = View.VISIBLE
            binding.btnOpenShevery.visibility = View.GONE
        }

        if (isManagerInstalled) {
            val isBound = ShizukuShellWrapper.tryBindShevery(this)
            if (isBound) {
                val isGranted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                if (isGranted) {
                    // STATE 4: Active & Authorized (Connected)
                    binding.imgStatusIcon.setImageResource(R.drawable.ic_shield_check)
                    binding.imgStatusIcon.setColorFilter(Color.parseColor("#FF10B981"))
                    binding.layoutStatusIconBg.setBackgroundResource(R.drawable.bg_badge_connected)
                    binding.cardStatusHero.strokeColor = Color.parseColor("#4010B981")

                    binding.txtShizukuStatus.text = getString(R.string.shizuku_enabled_and_active)
                    binding.txtStatusBadge.text = getString(R.string.shizuku_badge_connected)
                    binding.txtStatusBadge.setBackgroundResource(R.drawable.bg_badge_connected)
                    binding.txtStatusBadge.setTextColor(Color.parseColor("#FF10B981"))

                    binding.txtShizukuDescription.text = getString(R.string.shizuku_status_active_desc)
                    binding.btnShizukuEnable.visibility = View.GONE

                    // Capabilities checkmarks
                    binding.icProtectedCheck.visibility = View.VISIBLE
                    binding.icSpeedCheck.visibility = View.VISIBLE
                    binding.icSecurityCheck.visibility = View.VISIBLE

                    // Service Info Card
                    binding.cardServiceInfo.visibility = View.VISIBLE
                    binding.txtServiceProviderVal.text = if (isShevery) "Shevery Service" else "Shizuku Service"
                    binding.txtServicePermissionVal.text = getString(R.string.shizuku_permission_granted_api)
                    binding.txtServicePermissionVal.setTextColor(Color.parseColor("#FF10B981"))

                    // Setup guide can be hidden when active
                    binding.cardSetupGuide.visibility = View.GONE

                    // Pulse animation on the shield icon
                    statusAnimator = ObjectAnimator.ofFloat(binding.imgStatusIcon, "alpha", 1f, 0.4f, 1f).apply {
                        duration = 1500
                        repeatCount = ObjectAnimator.INFINITE
                        start()
                    }
                } else {
                    // STATE 3: Service Running, Not Authorized
                    binding.imgStatusIcon.setImageResource(R.drawable.ic_shield_alert)
                    binding.imgStatusIcon.setColorFilter(Color.parseColor("#FF0284C7"))
                    binding.layoutStatusIconBg.setBackgroundResource(R.drawable.bg_status_badge_accent)
                    binding.cardStatusHero.strokeColor = ContextCompat.getColor(this, R.color.ufm_primary)

                    binding.txtShizukuStatus.text = getString(R.string.shizuku_not_authorized)
                    binding.txtStatusBadge.text = getString(R.string.shizuku_badge_auth_needed)
                    binding.txtStatusBadge.setBackgroundResource(R.drawable.bg_status_badge_accent)
                    binding.txtStatusBadge.setTextColor(Color.parseColor("#FF0284C7"))

                    binding.txtShizukuDescription.text = getString(R.string.shizuku_status_unauthorized_desc)
                    binding.btnShizukuEnable.text = getString(R.string.shizuku_btn_authorize)
                    binding.btnShizukuEnable.setIconResource(R.drawable.ic_check_circle)
                    binding.btnShizukuEnable.visibility = View.VISIBLE

                    binding.icProtectedCheck.visibility = View.GONE
                    binding.icSpeedCheck.visibility = View.GONE
                    binding.icSecurityCheck.visibility = View.GONE

                    binding.cardServiceInfo.visibility = View.GONE
                    binding.cardSetupGuide.visibility = View.VISIBLE
                }
            } else {
                // STATE 2: Installed, Service Inactive / Stopped
                binding.imgStatusIcon.setImageResource(R.drawable.ic_lightning)
                binding.imgStatusIcon.setColorFilter(Color.parseColor("#FFF59E0B"))
                binding.layoutStatusIconBg.setBackgroundResource(R.drawable.bg_badge_inactive)
                binding.cardStatusHero.strokeColor = Color.parseColor("#40F59E0B")

                binding.txtShizukuStatus.text = getString(R.string.shizuku_service_not_running)
                binding.txtStatusBadge.text = getString(R.string.shizuku_badge_stopped)
                binding.txtStatusBadge.setBackgroundResource(R.drawable.bg_badge_inactive)
                binding.txtStatusBadge.setTextColor(Color.parseColor("#FFF59E0B"))

                binding.txtShizukuDescription.text = getString(R.string.shizuku_status_stopped_desc)
                binding.btnShizukuEnable.text = getString(R.string.shizuku_btn_start_service)
                binding.btnShizukuEnable.setIconResource(R.drawable.ic_lightning)
                binding.btnShizukuEnable.visibility = View.VISIBLE

                binding.icProtectedCheck.visibility = View.GONE
                binding.icSpeedCheck.visibility = View.GONE
                binding.icSecurityCheck.visibility = View.GONE

                binding.cardServiceInfo.visibility = View.GONE
                binding.cardSetupGuide.visibility = View.VISIBLE
            }
        } else {
            // STATE 1: Manager Not Installed
            binding.imgStatusIcon.setImageResource(R.drawable.ic_shield_alert)
            binding.imgStatusIcon.setColorFilter(Color.parseColor("#FFEF5350"))
            binding.layoutStatusIconBg.setBackgroundResource(R.drawable.bg_badge_unavailable)
            binding.cardStatusHero.strokeColor = Color.parseColor("#40EF5350")

            binding.txtShizukuStatus.text = getString(R.string.shizuku_not_installed)
            binding.txtStatusBadge.text = getString(R.string.shizuku_badge_unavailable)
            binding.txtStatusBadge.setBackgroundResource(R.drawable.bg_badge_unavailable)
            binding.txtStatusBadge.setTextColor(Color.parseColor("#FFEF5350"))

            binding.txtShizukuDescription.text = getString(R.string.shizuku_status_not_installed_desc)
            binding.btnShizukuEnable.visibility = View.GONE

            binding.icProtectedCheck.visibility = View.GONE
            binding.icSpeedCheck.visibility = View.GONE
            binding.icSecurityCheck.visibility = View.GONE

            binding.cardServiceInfo.visibility = View.GONE
            binding.cardSetupGuide.visibility = View.VISIBLE
        }
    }
}
