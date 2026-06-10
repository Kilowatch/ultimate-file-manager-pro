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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import za.kilowatch.ultimatefilemanager.util.DeviceUtils

/**
 * Main screen for managing the hosted FTP/SFTP/DLNA servers.
 *
 * Shows:
 * - FTP toggle (port 2121) with IP:port display when active
 * - SFTP toggle (port 2222) with IP:port display when active
 * - DLNA Media Server toggle with IP:port display when active
 * - DLNA Media Renderer toggle with status when active
 * - List of configured profiles
 * - FAB to create new profiles
 *
 * Security note: Anonymous login is not supported. All access requires a named profile.
 * H-2: The FTP URL display includes an "⚠️ Unencrypted" warning to remind users
 * that FTP data is transmitted in plaintext.
 */
class ServerHostActivity : AppCompatActivity() {

    private lateinit var switchFtp: MaterialSwitch
    private lateinit var switchSftp: MaterialSwitch
    private lateinit var txtFtpUrl: TextView
    private lateinit var txtSftpUrl: TextView
    private lateinit var txtDlnaStatus: TextView
    private lateinit var recyclerProfiles: RecyclerView
    private lateinit var layoutEmptyProfiles: View
    private lateinit var adapter: ServerProfileAdapter
    private lateinit var profileRepo: FtpServerProfileRepository

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        za.kilowatch.ultimatefilemanager.settings.ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (DeviceUtils.isTvDevice(this)) {
            setContentView(R.layout.activity_server_host_tv)
        } else {
            setContentView(R.layout.activity_server_host)
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val isTv = DeviceUtils.isTvDevice(this)
            val tvPad = if (isTv) (27 * resources.displayMetrics.density).toInt() else 0
            v.setPadding(
                systemBars.left + tvPad,
                systemBars.top + tvPad,
                systemBars.right + tvPad,
                systemBars.bottom + tvPad
            )
            insets
        }

        profileRepo = FtpServerProfileRepository.getInstance(this)

        bindViews()
        setupToggles()
        setupProfilesList()
        setupFab()

        // Observe server state
        FileServerService.serverState.observe(this) { state ->
            updateServerStatus(state)
        }
    }

    override fun onResume() {
        super.onResume()
        loadProfiles()
        loadSavedState()
    }

    private fun bindViews() {
        switchFtp  = findViewById(R.id.switchFtp)
        switchSftp = findViewById(R.id.switchSftp)
        txtFtpUrl  = findViewById(R.id.txtFtpUrl)
        txtSftpUrl = findViewById(R.id.txtSftpUrl)
        txtDlnaStatus = findViewById(R.id.txtDlnaStatus)
        recyclerProfiles    = findViewById(R.id.recyclerProfiles)
        layoutEmptyProfiles = findViewById(R.id.layoutEmptyProfiles)
        findViewById<ImageView>(R.id.btnBack).setOnClickListener { navigateBack() }

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                navigateBack()
            }
        })

        findViewById<View>(R.id.layoutDlnaCard).setOnClickListener {
            startActivity(Intent(this, DlnaHostActivity::class.java))
        }

        if (DeviceUtils.isTvDevice(this)) {
            findViewById<View>(R.id.layoutFtpCard).setOnClickListener  { switchFtp.toggle() }
            findViewById<View>(R.id.layoutSftpCard).setOnClickListener { switchSftp.toggle() }
        }
    }

    private fun setupToggles() {
        switchFtp.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                // FTP does not support SSH key auth — require at least one profile with a password.
                if (profileRepo.getAll().none { it.encryptedPassword.isNotEmpty() }) {
                    switchFtp.isChecked = false
                    MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.ftp_needs_password_title)
                        .setMessage(R.string.ftp_needs_password_msg)
                        .setPositiveButton(R.string.ok, null)
                        .show()
                    return@setOnCheckedChangeListener
                }
                FileServerService.startFtp(this)
                ServerMonitorWorker.schedule(this)
            } else {
                FileServerService.stopFtp(this)
                FileServerService.setFtpEnabled(this, false)
                // M-3: Cancel the monitor worker when both servers are off.
                if (!FileServerService.isSftpEnabled(this) &&
                    !FileServerService.isDlnaServerEnabled(this) &&
                    !FileServerService.isDlnaRendererEnabled(this)) {
                    ServerMonitorWorker.cancel(this)
                }
            }
        }

        switchSftp.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                FileServerService.startSftp(this)
                ServerMonitorWorker.schedule(this)
            } else {
                FileServerService.stopSftp(this)
                FileServerService.setSftpEnabled(this, false)
                if (!FileServerService.isFtpEnabled(this) &&
                    !FileServerService.isDlnaServerEnabled(this) &&
                    !FileServerService.isDlnaRendererEnabled(this)) {
                    ServerMonitorWorker.cancel(this)
                }
            }
        }
    }

    private fun setupProfilesList() {
        adapter = ServerProfileAdapter(
            onEdit = { profile ->
                val intent = Intent(this, ServerProfileEditActivity::class.java).apply {
                    putExtra(ServerProfileEditActivity.EXTRA_PROFILE_ID, profile.id)
                }
                startActivity(intent)
            },
            onDelete = { profile ->
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.delete_profile)
                    .setMessage(getString(R.string.delete_profile_confirm, profile.username))
                    .setPositiveButton(R.string.delete) { _, _ ->
                        profileRepo.delete(profile.id)
                        loadProfiles()
                        Toast.makeText(this, R.string.profile_deleted, Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        )
        recyclerProfiles.layoutManager = LinearLayoutManager(this)
        recyclerProfiles.adapter = adapter
    }

    private fun setupFab() {
        findViewById<View>(R.id.fabAddProfile).setOnClickListener {
            startActivity(Intent(this, ServerProfileEditActivity::class.java))
        }
    }

    private fun loadProfiles() {
        val profiles = profileRepo.getAll()
        adapter.submitList(profiles)
        layoutEmptyProfiles.visibility = if (profiles.isEmpty()) View.VISIBLE else View.GONE
        recyclerProfiles.visibility    = if (profiles.isEmpty()) View.GONE   else View.VISIBLE
    }

    private fun loadSavedState() {
        switchFtp.isChecked  = FileServerService.isFtpEnabled(this)
        switchSftp.isChecked = FileServerService.isSftpEnabled(this)
    }

    private fun updateServerStatus(state: ServerState) {
        if (state.ftpRunning) {
            txtFtpUrl.text = "ftp://${state.ftpAddress}  ⚠️ Unencrypted"
            txtFtpUrl.visibility = View.VISIBLE
        } else {
            txtFtpUrl.visibility = View.GONE
        }

        if (state.sftpRunning) {
            txtSftpUrl.text = "sftp://${state.sftpAddress}  🔒 Encrypted"
            txtSftpUrl.visibility = View.VISIBLE
        } else {
            txtSftpUrl.visibility = View.GONE
        }

        val dlnaActive = state.dlnaRunning || state.rendererRunning
        txtDlnaStatus.text = if (dlnaActive) {
            getString(R.string.dlna_tile_subtitle_active)
        } else {
            getString(R.string.dlna_tile_subtitle_inactive)
        }
    }

    private fun navigateBack() {
        if (isTaskRoot) {
            val intent = Intent(this, za.kilowatch.ultimatefilemanager.storage.StorageBrowserActivity::class.java)
            startActivity(intent)
        }
        finish()
    }
}
