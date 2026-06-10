package za.kilowatch.ultimatefilemanager.server

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.materialswitch.MaterialSwitch
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import za.kilowatch.ultimatefilemanager.settings.ThemeHelper
import za.kilowatch.ultimatefilemanager.util.DeviceUtils

/**
 * Dedicated screen for the two DLNA services:
 *  - DLNA Media Server (toggle + status URL + settings button)
 *  - DLNA Media Renderer (toggle + status)
 *
 * Follows the one-activity-two-layouts pattern:
 *  - Mobile → activity_dlna_host.xml (gear button inside server card)
 *  - TV     → activity_dlna_host_tv.xml (DLNA Settings is its own focusable row)
 *
 * Observes [FileServerService.serverState] to update status text in real-time.
 */
class DlnaHostActivity : AppCompatActivity() {

    private lateinit var switchDlna: MaterialSwitch
    private lateinit var switchDlnaRenderer: MaterialSwitch
    private lateinit var txtDlnaUrl: TextView
    private lateinit var txtDlnaRendererUrl: TextView

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val isTv = DeviceUtils.isTvDevice(this)
        if (isTv) {
            setContentView(R.layout.activity_dlna_host_tv)
        } else {
            setContentView(R.layout.activity_dlna_host)
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val tvPad = if (isTv) (27 * resources.displayMetrics.density).toInt() else 0
            v.setPadding(
                systemBars.left + tvPad,
                systemBars.top + tvPad,
                systemBars.right + tvPad,
                systemBars.bottom + tvPad
            )
            insets
        }

        bindViews(isTv)
        setupToggles()
        loadSavedState()

        FileServerService.serverState.observe(this) { state ->
            updateServerStatus(state)
        }
    }

    override fun onResume() {
        super.onResume()
        loadSavedState()
    }

    // ── View binding ──────────────────────────────────────────────────────────

    private fun bindViews(isTv: Boolean) {
        switchDlna         = findViewById(R.id.switchDlna)
        switchDlnaRenderer = findViewById(R.id.switchDlnaRenderer)
        txtDlnaUrl         = findViewById(R.id.txtDlnaUrl)
        txtDlnaRendererUrl = findViewById(R.id.txtDlnaRendererUrl)

        // Back button
        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }

        onBackPressedDispatcher.addCallback(this,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() { finish() }
            })

        if (isTv) {
            // TV: each card row is a focusable container that drives the switch
            findViewById<View>(R.id.layoutDlnaCard).setOnClickListener { switchDlna.toggle() }
            findViewById<View>(R.id.layoutDlnaRendererCard).setOnClickListener { switchDlnaRenderer.toggle() }

            // TV: "DLNA Settings" is its own dedicated focusable row
            findViewById<View>(R.id.layoutDlnaSettingsCard).setOnClickListener {
                startActivity(Intent(this, DlnaServerSettingsActivity::class.java))
            }
        } else {
            // Mobile: gear button inside the server card header
            findViewById<ImageView>(R.id.btnDlnaSettings).setOnClickListener {
                startActivity(Intent(this, DlnaServerSettingsActivity::class.java))
            }
        }
    }

    // ── Toggle listeners ──────────────────────────────────────────────────────

    private fun setupToggles() {
        switchDlna.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                val folders = DlnaServerPrefs.getSharedFolders(this)
                if (folders.isEmpty()) {
                    switchDlna.isChecked = false
                    Toast.makeText(this, R.string.dlna_no_shared_folders, Toast.LENGTH_LONG).show()
                    return@setOnCheckedChangeListener
                }
                FileServerService.startDlna(this)
                ServerMonitorWorker.schedule(this)
            } else {
                FileServerService.stopDlna(this)
                FileServerService.setDlnaServerEnabled(this, false)
                if (!FileServerService.isFtpEnabled(this) && !FileServerService.isSftpEnabled(this)) {
                    ServerMonitorWorker.cancel(this)
                }
            }
        }

        switchDlnaRenderer.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                FileServerService.startRenderer(this)
                ServerMonitorWorker.schedule(this)
            } else {
                FileServerService.stopRenderer(this)
                FileServerService.setDlnaRendererEnabled(this, false)
                if (!FileServerService.isFtpEnabled(this) && !FileServerService.isSftpEnabled(this)
                    && !FileServerService.isDlnaServerEnabled(this)) {
                    ServerMonitorWorker.cancel(this)
                }
            }
        }
    }

    // ── State helpers ─────────────────────────────────────────────────────────

    private fun loadSavedState() {
        switchDlna.isChecked         = FileServerService.isDlnaServerEnabled(this)
        switchDlnaRenderer.isChecked = FileServerService.isDlnaRendererEnabled(this)
    }

    private fun updateServerStatus(state: ServerState) {
        if (state.dlnaRunning && state.dlnaPort > 0) {
            txtDlnaUrl.text = getString(R.string.dlna_server_running, state.ipAddress, state.dlnaPort)
            txtDlnaUrl.visibility = View.VISIBLE
        } else {
            txtDlnaUrl.visibility = View.GONE
        }

        if (state.rendererRunning) {
            txtDlnaRendererUrl.text = getString(R.string.dlna_renderer_running, state.ipAddress)
            txtDlnaRendererUrl.visibility = View.VISIBLE
        } else {
            txtDlnaRendererUrl.visibility = View.GONE
        }
    }
}
