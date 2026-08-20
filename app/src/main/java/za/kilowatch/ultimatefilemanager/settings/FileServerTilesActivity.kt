package za.kilowatch.ultimatefilemanager.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.switchmaterial.SwitchMaterial
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.server.DlnaServerPrefs
import za.kilowatch.ultimatefilemanager.server.FtpServerProfileRepository
import za.kilowatch.ultimatefilemanager.server.ServerHostActivity
import za.kilowatch.ultimatefilemanager.util.ThemeColors
import za.kilowatch.ultimatefilemanager.widget.DlnaTileService
import za.kilowatch.ultimatefilemanager.widget.FtpTileService
import za.kilowatch.ultimatefilemanager.widget.SftpTileService
import java.util.concurrent.Executors

/**
 * File Server System Tiles activity.
 * Configures Android Quick Settings pull-down tiles for FTP, SFTP, and DLNA media servers.
 * Follows the Language and Grouped Glass Card design standard.
 */
class FileServerTilesActivity : AppCompatActivity() {

    private lateinit var switchFtpTile: SwitchMaterial
    private lateinit var txtFtpTileSubtitle: TextView

    private lateinit var switchSftpTile: SwitchMaterial
    private lateinit var txtSftpTileSubtitle: TextView

    private lateinit var switchDlnaTile: SwitchMaterial
    private lateinit var txtDlnaTileSubtitle: TextView

    // Persisted through recreate() to prevent looping when restartPending is still true.
    private var handledFontChange = false
    private var handledLocaleChange = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        handledFontChange = savedInstanceState?.getBoolean("font_handled", false) ?: false
        handledLocaleChange = savedInstanceState?.getBoolean("locale_handled", false) ?: false

        enableEdgeToEdge()
        setContentView(R.layout.activity_file_server_tiles)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val btnBack = findViewById<View>(R.id.btnBack)
        btnBack.setOnClickListener { finish() }

        val primaryColor = ThemeColors.primary(this)
        findViewById<TextView>(R.id.labelSectionServer)?.setTextColor(primaryColor)
        findViewById<TextView>(R.id.labelSectionTiles)?.setTextColor(primaryColor)

        // Open File Server Activity
        val cardOpenFileServer = findViewById<View>(R.id.cardOpenFileServer)
        cardOpenFileServer.setOnClickListener {
            startActivity(Intent(this, ServerHostActivity::class.java))
        }

        // FTP Tile
        val cardFtpTile = findViewById<View>(R.id.cardFtpTile)
        switchFtpTile = findViewById(R.id.switchFtpTile)
        txtFtpTileSubtitle = findViewById(R.id.txtFtpTileSubtitle)

        val ftpTileEnabled = isTileEnabled(FtpTileService::class.java)
        switchFtpTile.isChecked = ftpTileEnabled
        updateFtpTileSubtitle(ftpTileEnabled)

        cardFtpTile.setOnClickListener { toggleFtpTile() }
        switchFtpTile.setOnCheckedChangeListener(null)

        // SFTP Tile
        val cardSftpTile = findViewById<View>(R.id.cardSftpTile)
        switchSftpTile = findViewById(R.id.switchSftpTile)
        txtSftpTileSubtitle = findViewById(R.id.txtSftpTileSubtitle)

        val sftpTileEnabled = isTileEnabled(SftpTileService::class.java)
        switchSftpTile.isChecked = sftpTileEnabled
        updateSftpTileSubtitle(sftpTileEnabled)

        cardSftpTile.setOnClickListener { toggleSftpTile() }
        switchSftpTile.setOnCheckedChangeListener(null)

        // DLNA Tile
        val cardDlnaTile = findViewById<View>(R.id.cardDlnaTile)
        switchDlnaTile = findViewById(R.id.switchDlnaTile)
        txtDlnaTileSubtitle = findViewById(R.id.txtDlnaTileSubtitle)

        val dlnaTileEnabled = isTileEnabled(DlnaTileService::class.java)
        switchDlnaTile.isChecked = dlnaTileEnabled
        updateDlnaTileSubtitle(dlnaTileEnabled)

        cardDlnaTile.setOnClickListener { toggleDlnaTile() }
        switchDlnaTile.setOnCheckedChangeListener(null)
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

        // Refresh tile states in case system tile status changed externally
        val ftpTileEnabled = isTileEnabled(FtpTileService::class.java)
        switchFtpTile.isChecked = ftpTileEnabled
        updateFtpTileSubtitle(ftpTileEnabled)

        val sftpTileEnabled = isTileEnabled(SftpTileService::class.java)
        switchSftpTile.isChecked = sftpTileEnabled
        updateSftpTileSubtitle(sftpTileEnabled)

        val dlnaTileEnabled = isTileEnabled(DlnaTileService::class.java)
        switchDlnaTile.isChecked = dlnaTileEnabled
        updateDlnaTileSubtitle(dlnaTileEnabled)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("font_handled", handledFontChange)
        outState.putBoolean("locale_handled", handledLocaleChange)
    }

    private fun isTileEnabled(cls: Class<*>): Boolean {
        val componentName = ComponentName(this, cls)
        val status = packageManager.getComponentEnabledSetting(componentName)
        return status == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    }

    private fun toggleFtpTile() {
        val newValue = !switchFtpTile.isChecked

        if (newValue) {
            val profiles = FtpServerProfileRepository.getInstance(this).getAll()
            if (profiles.isEmpty()) {
                Toast.makeText(this, R.string.toast_profile_required_ftp, Toast.LENGTH_LONG).show()
                return
            }
        }

        switchFtpTile.isChecked = newValue
        setTileEnabled(
            FtpTileService::class.java,
            newValue,
            getString(R.string.settings_ftp_tile_title),
            R.drawable.ic_ufm_ftp
        ) { success ->
            if (!success && newValue) {
                runOnUiThread {
                    switchFtpTile.isChecked = false
                    updateFtpTileSubtitle(false)
                }
            }
        }
        updateFtpTileSubtitle(newValue)
    }

    private fun updateFtpTileSubtitle(enabled: Boolean) {
        txtFtpTileSubtitle.text = if (enabled) {
            getString(R.string.settings_ftp_tile_subtitle_on)
        } else {
            getString(R.string.settings_ftp_tile_subtitle_off)
        }
    }

    private fun toggleSftpTile() {
        val newValue = !switchSftpTile.isChecked

        if (newValue) {
            val profiles = FtpServerProfileRepository.getInstance(this).getAll()
            if (profiles.isEmpty()) {
                Toast.makeText(this, R.string.toast_profile_required_sftp, Toast.LENGTH_LONG).show()
                return
            }
        }

        switchSftpTile.isChecked = newValue
        setTileEnabled(
            SftpTileService::class.java,
            newValue,
            getString(R.string.settings_sftp_tile_title),
            R.drawable.ic_ufm_sftp
        ) { success ->
            if (!success && newValue) {
                runOnUiThread {
                    switchSftpTile.isChecked = false
                    updateSftpTileSubtitle(false)
                }
            }
        }
        updateSftpTileSubtitle(newValue)
    }

    private fun updateSftpTileSubtitle(enabled: Boolean) {
        txtSftpTileSubtitle.text = if (enabled) {
            getString(R.string.settings_sftp_tile_subtitle_on)
        } else {
            getString(R.string.settings_sftp_tile_subtitle_off)
        }
    }

    private fun toggleDlnaTile() {
        val newValue = !switchDlnaTile.isChecked

        if (newValue) {
            val folders = DlnaServerPrefs.getSharedFolders(this)
            if (folders.isEmpty()) {
                Toast.makeText(this, R.string.dlna_no_shared_folders, Toast.LENGTH_LONG).show()
                return
            }
        }

        switchDlnaTile.isChecked = newValue
        setTileEnabled(
            DlnaTileService::class.java,
            newValue,
            getString(R.string.settings_dlna_tile_title),
            R.drawable.ic_dlna
        ) { success ->
            if (!success && newValue) {
                runOnUiThread {
                    switchDlnaTile.isChecked = false
                    updateDlnaTileSubtitle(false)
                }
            }
        }
        updateDlnaTileSubtitle(newValue)
    }

    private fun updateDlnaTileSubtitle(enabled: Boolean) {
        txtDlnaTileSubtitle.text = if (enabled) {
            getString(R.string.settings_dlna_tile_summary)
        } else {
            getString(R.string.settings_sftp_tile_subtitle_off)
        }
    }

    private fun setTileEnabled(
        cls: Class<*>,
        enabled: Boolean,
        label: String,
        iconResId: Int,
        onResult: ((Boolean) -> Unit)? = null
    ) {
        val componentName = ComponentName(this, cls)
        val state = if (enabled) {
            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }

        packageManager.setComponentEnabledSetting(
            componentName,
            state,
            android.content.pm.PackageManager.DONT_KILL_APP
        )

        if (enabled) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val statusBarManager = getSystemService(android.app.StatusBarManager::class.java)
                statusBarManager.requestAddTileService(
                    componentName,
                    label,
                    Icon.createWithResource(this, iconResId),
                    Executors.newSingleThreadExecutor()
                ) { result ->
                    val success = result == android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ||
                                  result == android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED
                    if (!success) {
                        packageManager.setComponentEnabledSetting(
                            componentName,
                            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                            android.content.pm.PackageManager.DONT_KILL_APP
                        )
                    }
                    onResult?.invoke(success)
                }
            } else {
                Toast.makeText(this, R.string.toast_tile_enabled_old_api, Toast.LENGTH_LONG).show()
                onResult?.invoke(true)
            }
        } else {
            onResult?.invoke(true)
        }
    }
}
