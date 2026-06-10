package za.kilowatch.ultimatefilemanager.network

import android.content.Context
import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import za.kilowatch.ultimatefilemanager.settings.SettingsTransferManager
import za.kilowatch.ultimatefilemanager.settings.ThemeHelper

/**
 * TV-side approval screen for an incoming settings transfer request.
 *
 * Security design (C-3 / H-1):
 *  - No sensitive data is passed via Intent. Only a [EXTRA_TOKEN] UUID token is received.
 *  - The actual payload bytes are retrieved from [PendingTransferHolder] using that token.
 *  - All disk writes happen ONLY inside [onApprove], AFTER explicit user confirmation.
 *  - The entry auto-expires after 60 seconds even if the Activity is destroyed.
 */
class TransferApprovalActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TOKEN = "extra_transfer_token"
        private const val AUTO_DISMISS_MS = 60_000L
    }

    private lateinit var txtSourceDevice: TextView
    private lateinit var txtSummary: TextView
    private lateinit var txtCountdown: TextView
    private lateinit var btnApprove: MaterialButton
    private lateinit var btnReject: MaterialButton

    private var token: String = ""
    private var payloadBytes: ByteArray? = null
    private var countDownTimer: CountDownTimer? = null

    override fun attachBaseContext(base: Context) = super.attachBaseContext(
        LocaleHelper.wrap(base)  // supplies correct font scale → prevents GoRo recreate loop
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_transfer_approval)

        txtSourceDevice = findViewById(R.id.txtSourceDevice)
        txtSummary      = findViewById(R.id.txtSummary)
        txtCountdown    = findViewById(R.id.txtCountdown)
        btnApprove      = findViewById(R.id.btnApprove)
        btnReject       = findViewById(R.id.btnReject)

        token = intent.getStringExtra(EXTRA_TOKEN) ?: run {
            Toast.makeText(this, getString(R.string.transfer_settings_timeout), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // H-1: Retrieve payload from the in-memory holder — not from the Intent
        payloadBytes = PendingTransferHolder.retrieve(token)
        if (payloadBytes == null) {
            Toast.makeText(this, getString(R.string.transfer_settings_timeout), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        populateUI(payloadBytes!!)
        startCountdown()

        btnApprove.setOnClickListener { onApprove() }
        btnReject.setOnClickListener  { onReject() }
    }

    private fun populateUI(bytes: ByteArray) {
        val json = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrNull() ?: return

        // M-1: Sanitise device_name before display
        val rawDeviceName = json.optString("device_name", getString(R.string.android_device))
        val safeName = rawDeviceName.take(64).filter { it.code in 32..126 || it.isLetterOrDigit() }
        txtSourceDevice.text = getString(R.string.transfer_settings_from_device, safeName)

        val settingsCount  = json.optJSONObject("settings")?.length() ?: 0
        val sharesCount    = json.optJSONArray("network_shares")?.length() ?: 0
        val hasFileServer  = json.has("file_server_port")

        txtSummary.text = buildSummaryText(settingsCount, sharesCount, hasFileServer)
    }

    private fun buildSummaryText(settings: Int, shares: Int, fileServer: Boolean): String {
        return buildString {
            if (settings > 0) appendLine("• $settings ${getString(R.string.transfer_settings_summary_settings)}")
            if (shares > 0)   appendLine("• $shares ${getString(R.string.transfer_settings_summary_shares)}")
            if (fileServer)   appendLine("• ${getString(R.string.file_server_port_item, 0)}")
        }.trimEnd()
    }

    private fun startCountdown() {
        countDownTimer = object : CountDownTimer(AUTO_DISMISS_MS, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val secs = millisUntilFinished / 1000
                txtCountdown.text = getString(R.string.transfer_settings_timeout_countdown, secs)
            }
            override fun onFinish() {
                PendingTransferHolder.clear(token)
                finish()
            }
        }.start()
    }

    /**
     * C-3: All settings writes happen exclusively here, after explicit user approval.
     */
    private fun onApprove() {
        val bytes = payloadBytes ?: return
        btnApprove.isEnabled = false
        btnReject.isEnabled  = false
        countDownTimer?.cancel()

        CoroutineScope(Dispatchers.IO).launch {
            val result = SettingsTransferManager.applyPayload(this@TransferApprovalActivity, bytes)
            PendingTransferHolder.clear(token)

            withContext(Dispatchers.Main) {
                when (result) {
                    is SettingsTransferManager.ApplyResult.Success -> {
                        val msg = getString(
                            R.string.transfer_settings_success_count,
                            result.settingsCount + result.sharesAdded + result.sharesUpdated
                        )
                        Toast.makeText(this@TransferApprovalActivity, msg, Toast.LENGTH_LONG).show()
                    }
                    is SettingsTransferManager.ApplyResult.Failure -> {
                        Toast.makeText(this@TransferApprovalActivity,
                            getString(R.string.transfer_settings_apply_failed, result.error),
                            Toast.LENGTH_LONG).show()
                    }
                }
                finish()
            }
        }
    }

    private fun onReject() {
        countDownTimer?.cancel()
        PendingTransferHolder.clear(token)
        Toast.makeText(this, getString(R.string.transfer_settings_rejected), Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        countDownTimer?.cancel()
        // Do NOT clear token here — PendingTransferHolder's own TTL handles cleanup
    }
}
