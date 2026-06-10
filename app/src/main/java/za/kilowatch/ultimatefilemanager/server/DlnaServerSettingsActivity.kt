package za.kilowatch.ultimatefilemanager.server

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import za.kilowatch.ultimatefilemanager.settings.ThemeHelper
import za.kilowatch.ultimatefilemanager.storage.StorageBrowserActivity
import za.kilowatch.ultimatefilemanager.util.DeviceUtils

/**
 * Settings screen for the DLNA Media Server.
 *
 * Allows the user to configure:
 * - Server name (advertised on the network)
 * - Renderer name (identifies this device as a playback target)
 * - Shared folder list (which folders are exposed via DLNA)
 * - View the configured port
 *
 * Follows the one-activity-two-layouts pattern (mobile / TV).
 */
class DlnaServerSettingsActivity : AppCompatActivity() {

    // ── Views ─────────────────────────────────────────────────────────────────

    private lateinit var editServerName: TextInputEditText
    private lateinit var editRendererName: TextInputEditText
    private lateinit var rvSharedFolders: RecyclerView
    private lateinit var txtNoFolders: TextView
    private lateinit var txtPortInfo: TextView
    private lateinit var btnAddFolder: MaterialButton
    private lateinit var btnSave: MaterialButton

    // ── Data ───────────────────────────────────────────────────────────────────

    private val sharedFolders = mutableListOf<DlnaServerPrefs.SharedFolder>()
    private lateinit var folderAdapter: FolderAdapter

    // ── Picker launcher ────────────────────────────────────────────────────────

    private lateinit var folderPickerLauncher: androidx.activity.result.ActivityResultLauncher<Intent>

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val isTv = DeviceUtils.isTvDevice(this)
        if (isTv) {
            setContentView(R.layout.activity_dlna_server_settings_tv)
        } else {
            setContentView(R.layout.activity_dlna_server_settings)
        }

        // Edge-to-edge insets
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

        // Register the folder picker launcher
        folderPickerLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK) {
                val data = result.data ?: return@registerForActivityResult
                val uri = data.getStringExtra(StorageBrowserActivity.RESULT_URI)
                    ?: return@registerForActivityResult
                val label = data.getStringExtra(StorageBrowserActivity.RESULT_LABEL) ?: uri

                val folder = DlnaServerPrefs.SharedFolder(uri, label)
                sharedFolders.add(folder)
                updateFolderList()
            }
        }

        setupViews()
        loadSettings()
    }

    /**
     * Save settings when the user presses back (auto-save behaviour).
     */
    override fun onBackPressed() {
        saveSettings()
        super.onBackPressed()
    }

    // ── View binding ──────────────────────────────────────────────────────────

    private fun setupViews() {
        editServerName = findViewById(R.id.editServerName)
        editRendererName = findViewById(R.id.editRendererName)
        rvSharedFolders = findViewById(R.id.rvSharedFolders)
        txtNoFolders = findViewById(R.id.txtNoFolders)
        txtPortInfo = findViewById(R.id.txtPortInfo)
        btnAddFolder = findViewById(R.id.btnAddFolder)
        btnSave = findViewById(R.id.btnSave)

        // Back button
        findViewById<View>(R.id.btnBack).setOnClickListener {
            saveSettings()
            finish()
        }

        // Add folder — open the unified storage browser in location-picker mode
        btnAddFolder.setOnClickListener {
            folderPickerLauncher.launch(
                Intent(this, StorageBrowserActivity::class.java).apply {
                    putExtra(StorageBrowserActivity.EXTRA_LOCATION_PICKER, true)
                }
            )
        }

        // Save button
        btnSave.setOnClickListener {
            saveSettings()
            finish()
        }

        // RecyclerView setup
        folderAdapter = FolderAdapter(sharedFolders) { index ->
            sharedFolders.removeAt(index)
            updateFolderList()
        }
        rvSharedFolders.layoutManager = LinearLayoutManager(this)
        rvSharedFolders.adapter = folderAdapter
    }

    // ── Data loading / saving ─────────────────────────────────────────────────

    private fun loadSettings() {
        editServerName.setText(DlnaServerPrefs.getDlnaServerName(this))
        editRendererName.setText(DlnaServerPrefs.getDlnaRendererName(this))

        sharedFolders.clear()
        sharedFolders.addAll(DlnaServerPrefs.getSharedFolders(this))
        updateFolderList()

        val port = DlnaServerPrefs.getDlnaServerPort(this)
        txtPortInfo.text = getString(R.string.dlna_port_info, port)
    }

    private fun updateFolderList() {
        val isEmpty = sharedFolders.isEmpty()
        txtNoFolders.visibility = if (isEmpty) View.VISIBLE else View.GONE
        rvSharedFolders.visibility = if (isEmpty) View.GONE else View.VISIBLE
        folderAdapter.notifyDataSetChanged()
    }

    private fun saveSettings() {
        val serverName = editServerName.text?.toString()?.trim().orEmpty()
        val rendererName = editRendererName.text?.toString()?.trim().orEmpty()

        if (serverName.isNotEmpty()) {
            DlnaServerPrefs.setDlnaServerName(this, serverName)
        }
        if (rendererName.isNotEmpty()) {
            DlnaServerPrefs.setDlnaRendererName(this, rendererName)
        }
        DlnaServerPrefs.setSharedFolders(this, sharedFolders.toList())
    }

    // ── RecyclerView adapter ──────────────────────────────────────────────────

    /**
     * Simple adapter that shows each shared folder label with a remove button.
     */
    private class FolderAdapter(
        private val folders: List<DlnaServerPrefs.SharedFolder>,
        private val onRemove: (Int) -> Unit
    ) : RecyclerView.Adapter<FolderAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_dlna_shared_folder, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val folder = folders[position]
            holder.labelText.text = folder.label
            holder.pathText.text = folder.uri
            holder.btnRemove.setOnClickListener {
                onRemove(holder.bindingAdapterPosition)
            }
        }

        override fun getItemCount(): Int = folders.size

        class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val labelText: TextView = itemView.findViewById(R.id.txtFolderLabel)
            val pathText: TextView = itemView.findViewById(R.id.txtFolderPath)
            val btnRemove: ImageView = itemView.findViewById(R.id.btnRemoveFolder)
        }
    }
}
