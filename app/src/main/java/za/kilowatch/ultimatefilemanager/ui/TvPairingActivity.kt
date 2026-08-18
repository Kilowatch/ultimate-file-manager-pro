package za.kilowatch.ultimatefilemanager.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.UfmApplication
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import za.kilowatch.ultimatefilemanager.settings.ThemeHelper
import kotlin.random.Random

class TvPairingActivity : AppCompatActivity() {

    private var pinCode: String = ""
    
    private val pairingUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val deviceName = intent?.getStringExtra("newly_paired_device_name")
            if (deviceName != null) {
                // Pairing successful
                za.kilowatch.ultimatefilemanager.network.TvServerForegroundService.start(this@TvPairingActivity)
                val msg = getString(R.string.tv_pairing_linked, deviceName)
                Toast.makeText(this@TvPairingActivity, msg, Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_tv_pairing)

        // Generate and display PIN
        pinCode = String.format("%04d", Random.nextInt(10000))
        findViewById<TextView>(R.id.txtPinCode).text = pinCode

        // Display device name in instructions
        val deviceName = getDeviceName()
        findViewById<TextView>(R.id.txtStep2).text = androidx.core.text.HtmlCompat.fromHtml(
            getString(R.string.tv_pairing_step2, deviceName),
            androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY
        )
        findViewById<TextView>(R.id.txtStep3).text = androidx.core.text.HtmlCompat.fromHtml(
            getString(R.string.tv_pairing_step3, deviceName),
            androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY
        )

        // Start pairing mode in application context
        (application as UfmApplication).startPairingMode(pinCode)

        // Setup back button
        findViewById<ImageView>(R.id.btnBack).setOnClickListener {
            finish()
        }

        // Register for pairing updates
        val filter = IntentFilter("za.kilowatch.ufm.PAIRING_UPDATED")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(pairingUpdateReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(pairingUpdateReceiver, filter)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Stop pairing mode when leaving the screen
        (application as UfmApplication).stopPairingMode()
        unregisterReceiver(pairingUpdateReceiver)
    }

    private fun getDeviceName(): String {
        // Try getting user defined device name, fallback to model
        val prefsName = getSharedPreferences("UFM_Pairing_Prefs", Context.MODE_PRIVATE).getString("my_tv_name", null)
        if (prefsName != null) return prefsName
        
        val defaultName = android.os.Build.MODEL ?: getString(R.string.android_device)
        return android.provider.Settings.Global.getString(contentResolver, android.provider.Settings.Global.DEVICE_NAME) ?: defaultName
    }
}
