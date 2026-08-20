package za.kilowatch.ultimatefilemanager.server

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import za.kilowatch.ultimatefilemanager.settings.FontSizeHelper
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import za.kilowatch.ultimatefilemanager.settings.ThemeHelper
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import za.kilowatch.ultimatefilemanager.util.ThemeColors

/**
 * Dedicated screen for the two DLNA services:
 *  - DLNA Media Server (toggle + status URL + settings navigation)
 *  - DLNA Media Renderer (toggle + status)
 *
 * Follows the Language & Grouped Glass Card design standard across Mobile & TV.
 * Observes [FileServerService.serverState] to update status text in real-time.
 */
class DlnaHostActivity : AppCompatActivity() {

    private lateinit var switchDlna: MaterialSwitch
    private lateinit var switchDlnaRenderer: MaterialSwitch
    private lateinit var txtDlnaUrl: TextView
    private lateinit var txtDlnaRendererUrl: TextView

    private var handledFontChange = false
    private var handledLocaleChange = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (savedInstanceState != null) {
            handledFontChange = savedInstanceState.getBoolean("font_handled", false)
            handledLocaleChange = savedInstanceState.getBoolean("locale_handled", false)
        }

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
        if (LocaleHelper.restartPending && !handledLocaleChange) {
            handledLocaleChange = true
            recreate()
            return
        }
        if (FontSizeHelper.restartPending && !handledFontChange) {
            handledFontChange = true
            recreate()
            return
        }

        loadSavedState()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("font_handled", handledFontChange)
        outState.putBoolean("locale_handled", handledLocaleChange)
    }

    // ── View binding ──────────────────────────────────────────────────────────

    private fun bindViews(isTv: Boolean) {
        switchDlna         = findViewById(R.id.switchDlna)
        switchDlnaRenderer = findViewById(R.id.switchDlnaRenderer)
        txtDlnaUrl         = findViewById(R.id.txtDlnaUrl)
        txtDlnaRendererUrl = findViewById(R.id.txtDlnaRendererUrl)

        // Section headers primary color tinting (Mobile)
        if (!isTv) {
            val primaryColor = ThemeColors.primary(this)
            findViewById<TextView>(R.id.labelSectionServices)?.setTextColor(primaryColor)
            findViewById<TextView>(R.id.labelSectionConfig)?.setTextColor(primaryColor)
        }

        // Back button
        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }

        onBackPressedDispatcher.addCallback(this,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() { finish() }
            })

        // Tap entire card row to toggle switch
        findViewById<View>(R.id.layoutDlnaCard)?.setOnClickListener { switchDlna.toggle() }
        findViewById<View>(R.id.layoutDlnaRendererCard)?.setOnClickListener { switchDlnaRenderer.toggle() }

        // Settings navigation
        val settingsClickListener = View.OnClickListener {
            startActivity(Intent(this, DlnaServerSettingsActivity::class.java))
        }
        findViewById<View>(R.id.btnDlnaSettings)?.setOnClickListener(settingsClickListener)
        findViewById<View>(R.id.layoutDlnaSettingsCard)?.setOnClickListener(settingsClickListener)

        // Tap-to-copy URL box
        txtDlnaUrl.setOnClickListener {
            val text = txtDlnaUrl.text.toString()
            if (text.isNotEmpty()) {
                val cleanUrl = if (text.startsWith("DLNA: ")) text.removePrefix("DLNA: ") else text
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("DLNA URL", cleanUrl)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, getString(R.string.tile_color_export_copied), Toast.LENGTH_SHORT).show()
            }
        }

        txtDlnaRendererUrl.setOnClickListener {
            val text = txtDlnaRendererUrl.text.toString()
            if (text.isNotEmpty()) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("DLNA Renderer", text)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, getString(R.string.tile_color_export_copied), Toast.LENGTH_SHORT).show()
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
