package za.kilowatch.ultimatefilemanager.settings.renamer

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import za.kilowatch.ultimatefilemanager.settings.ThemeHelper
import za.kilowatch.ultimatefilemanager.storage.StorageBrowserActivity
import za.kilowatch.ultimatefilemanager.util.DeviceUtils

class StorageRenameActivity : AppCompatActivity() {

    private lateinit var recyclerRenames: RecyclerView
    private lateinit var layoutEmpty: View
    private lateinit var adapter: StorageRenameAdapter

    private var isTv = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        isTv = DeviceUtils.isTvDevice(this)
        if (isTv) {
            setContentView(R.layout.activity_storage_rename_tv)
        } else {
            setContentView(R.layout.activity_storage_rename)
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val tvPad = if (isTv) (27 * resources.displayMetrics.density).toInt() else 0
            v.setPadding(
                systemBars.left + tvPad, systemBars.top + tvPad,
                systemBars.right + tvPad, systemBars.bottom + tvPad
            )
            insets
        }

        findViewById<ImageView>(R.id.btnBack)?.setOnClickListener { finish() }

        recyclerRenames = findViewById(R.id.recyclerRenames)
        layoutEmpty = findViewById(R.id.layoutEmpty)

        adapter = StorageRenameAdapter(isTv) { item ->
            showRenameDialog(item)
        }
        
        recyclerRenames.layoutManager = LinearLayoutManager(this)
        recyclerRenames.adapter = adapter

        loadData()
    }

    private fun loadData() {
        lifecycleScope.launch(Dispatchers.IO) {
            val dbRenames = StorageRenameManager.getInstance(this@StorageRenameActivity).getAllRenameMapSync()
            
            // Getting all connected storages, omitting logical tiles
            val connectedStorages = StorageBrowserActivity.getConnectedStorages(this@StorageRenameActivity)
                .filter { !it.isAppsTile && !it.isRemoteTile && !it.isSettingsTile && !it.isLegalTile && !it.isRateUsTile && !it.isTipJarTile && !it.isAnalyzerTile && !it.isSearchTile && !it.isVaultTile && !it.isNetworkTile && !it.isSyncTile && !it.isExtractsTile && !it.isPairedDevicesTile && !it.isTwinWindowTile && !it.isTerminalTile && !it.isShizukuTile && !it.isFavoriteTile && !it.isOnlineStoragesTile && !it.isFileServerTile && !it.isNetworkRoot && !it.isOnlineStorage }
            
            val liveHashedIds = connectedStorages.map { StorageRenameManager.hashDeviceId(it.id) }.toSet()
            val listItems = mutableListOf<RenameListItem>()

            // 1. Add all connected drives
            for (liveDrive in connectedStorages) {
                val hashedLiveId = StorageRenameManager.hashDeviceId(liveDrive.id)
                val renamedProp = dbRenames[hashedLiveId]
                listItems.add(
                    RenameListItem(
                        deviceId = hashedLiveId,
                        displayTitle = renamedProp?.customName ?: liveDrive.label,
                        displaySubtitle = if (renamedProp != null) getString(R.string.storage_unknown_pattern, liveDrive.label) else "",
                        sizeBytes = liveDrive.totalBytes,
                        isOnline = true,
                        iconRes = liveDrive.iconRes
                    )
                )
            }

            // 2. Add offline renamed drives
            for ((id, entity) in dbRenames) {
                if (id !in liveHashedIds) {
                    val lowerOriginal = entity.originalName.lowercase()
                    val fallbackIcon = when {
                        lowerOriginal.contains("usb") -> R.drawable.ic_storage_usb
                        lowerOriginal.contains("sd") -> R.drawable.ic_storage_sdcard
                        else -> R.drawable.ic_storage_internal
                    }
                    
                    listItems.add(
                        RenameListItem(
                            deviceId = id,
                            displayTitle = entity.customName,
                            displaySubtitle = getString(R.string.storage_unknown_pattern, entity.originalName),
                            sizeBytes = entity.totalBytes,
                            isOnline = false,
                            iconRes = fallbackIcon
                        )
                    )
                }
            }

            withContext(Dispatchers.Main) {
                if (listItems.isEmpty()) {
                    recyclerRenames.visibility = View.GONE
                    layoutEmpty.visibility = View.VISIBLE
                } else {
                    recyclerRenames.visibility = View.VISIBLE
                    layoutEmpty.visibility = View.GONE
                    adapter.submitList(listItems)
                }
            }
        }
    }

    private fun showRenameDialog(item: RenameListItem) {
        val input = EditText(this).apply {
            hint = getString(R.string.custom_name_hint)
            setText(item.displayTitle)
            setSelection(item.displayTitle.length)
            isSingleLine = true
        }
        
        val container = android.widget.FrameLayout(this)
        val params = android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT, 
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
        val margin = (24 * resources.displayMetrics.density).toInt()
        params.setMargins(margin, margin/2, margin, 0)
        input.layoutParams = params
        container.addView(input)

        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rename_drive_dialog_title)
            .setView(container)
            .setPositiveButton(R.string.rename_action) { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        val originalNameStr = if (item.displaySubtitle.isNotEmpty()) {
                            item.displaySubtitle.removePrefix("Storage (").removeSuffix(")")
                        } else {
                            item.displayTitle
                        }

                        StorageRenameManager.getInstance(this@StorageRenameActivity).saveRenameByHashedId(
                            hashedId = item.deviceId,
                            customName = newName,
                            originalName = originalNameStr.ifEmpty { "Drive" },
                            totalBytes = item.sizeBytes
                        )
                        loadData()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            
        lifecycleScope.launch(Dispatchers.IO) {
            val dbRenames = StorageRenameManager.getInstance(this@StorageRenameActivity).getAllRenameMapSync()
            val existsInDb = dbRenames.containsKey(item.deviceId)
            
            withContext(Dispatchers.Main) {
                if (existsInDb) {
                    builder.setNeutralButton(R.string.reset_action) { _, _ ->
                        lifecycleScope.launch(Dispatchers.IO) {
                            StorageRenameManager.getInstance(this@StorageRenameActivity).deleteRenameByHashedId(item.deviceId)
                            loadData()
                        }
                    }
                }
                
                builder.show()
                input.requestFocus()
            }
        }
    }
}
