package za.kilowatch.ultimatefilemanager.smartsort

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.storage.StorageManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.network.NetworkBrowserActivity
import za.kilowatch.ultimatefilemanager.storage.FileBrowserActivity
import za.kilowatch.ultimatefilemanager.network.NetworkShareRepository
import za.kilowatch.ultimatefilemanager.network.OnlineStorageRepository
import za.kilowatch.ultimatefilemanager.network.NetworkShare
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import za.kilowatch.ultimatefilemanager.util.safeDirectoryPath
import java.io.File

data class StorageEntry(
    val id: String,
    val label: String,
    val path: String,
    val iconRes: Int,
    val type: StorageEntryType
)

enum class StorageEntryType { LOCAL, NETWORK, ONLINE }

class SmartSortActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SMART_SORT_CATEGORY_PICKER = "extra_smart_sort_category_picker"
        const val EXTRA_SMART_SORT_CATEGORY_KEY = "extra_smart_sort_category_key"
        const val EXTRA_SMART_SORT_CATEGORY_LABEL = "extra_smart_sort_category_label"
        const val RESULT_SELECTED_CATEGORY_PATH = "result_selected_category_path"
        const val RESULT_SELECTED_CATEGORY_KEY = "result_selected_category_key"
        const val RESULT_SELECTED_SHARE_ID = "result_selected_share_id"
    }

    private lateinit var toolbar: MaterialToolbar
    private lateinit var recyclerFolders: RecyclerView
    private lateinit var layoutEmpty: LinearLayout
    private lateinit var txtPath: TextView
    private lateinit var adapter: FolderAdapter

    private var pendingSmartSortPath: String? = null
    private var selectedShare: NetworkShare? = null
    private var isCategoryPickerMode = false
    private var pendingCategoryKey: String? = null

    private val localPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val path = result.data?.getStringExtra(FileBrowserActivity.RESULT_SELECTED_LOCAL_PATH)
                ?: return@registerForActivityResult
            if (isCategoryPickerMode) {
                Intent().apply {
                    putExtra(RESULT_SELECTED_CATEGORY_KEY, pendingCategoryKey)
                    putExtra(RESULT_SELECTED_CATEGORY_PATH, path)
                }.let { setResult(RESULT_OK, it); finish() }
            } else {
                openSmartSortSheet(path)
            }
        }
    }

    private val smartSortLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val sorted = result.data?.getBooleanExtra(SmartSortTvActivity.RESULT_SORTED, false) ?: false
            if (sorted) {
                showStorageRoots()
            }
        }
        pendingSmartSortPath = null
    }

    private val networkPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data ?: return@registerForActivityResult
            val path = data.getStringExtra(NetworkBrowserActivity.RESULT_SELECTED_SMART_SORT_PATH) ?: return@registerForActivityResult
            val shareId = data.getStringExtra(NetworkBrowserActivity.RESULT_SELECTED_SMART_SORT_SHARE_ID) ?: return@registerForActivityResult
            selectedShare = SmartSortShareHolder.resolve(shareId)
            if (isCategoryPickerMode) {
                val resultIntent = Intent().apply {
                    putExtra(RESULT_SELECTED_CATEGORY_KEY, pendingCategoryKey)
                    putExtra(RESULT_SELECTED_CATEGORY_PATH, path)
                    putExtra(RESULT_SELECTED_SHARE_ID, shareId)
                }
                setResult(RESULT_OK, resultIntent)
                finish()
            } else {
                openSmartSortSheet(path)
            }
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onResume() {
        super.onResume()
        refreshRecentButton()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_smart_sort)

        isCategoryPickerMode = intent.getBooleanExtra(EXTRA_SMART_SORT_CATEGORY_PICKER, false)
        pendingCategoryKey = intent.getStringExtra(EXTRA_SMART_SORT_CATEGORY_KEY)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        toolbar = findViewById(R.id.toolbar)
        recyclerFolders = findViewById(R.id.recyclerFolders)
        layoutEmpty = findViewById(R.id.layoutEmpty)
        txtPath = findViewById(R.id.txtPath)

        findViewById<ImageView>(R.id.btnRecentConfigs)?.setOnClickListener { showRecentConfigsDialog() }

        findViewById<ImageView>(R.id.btnPickerBack).setOnClickListener { finish() }

        adapter = FolderAdapter { entry ->
            when (entry.type) {
                StorageEntryType.LOCAL -> {
                    val intent = Intent(this, FileBrowserActivity::class.java).apply {
                        putExtra(FileBrowserActivity.EXTRA_MOUNT_PATH, entry.path)
                        putExtra(FileBrowserActivity.EXTRA_STORAGE_LABEL, entry.label)
                        putExtra(FileBrowserActivity.EXTRA_SMART_SORT_PICKER, true)
                    }
                    localPickerLauncher.launch(intent)
                }
                StorageEntryType.NETWORK, StorageEntryType.ONLINE -> {
                    val share = SmartSortShareHolder.resolve(entry.id) ?: return@FolderAdapter
                    selectedShare = share
                    SmartSortShareHolder.set(share)
                    val intent = Intent(this, NetworkBrowserActivity::class.java).apply {
                        putExtra(NetworkBrowserActivity.EXTRA_SHARE_ID, entry.id)
                        putExtra(NetworkBrowserActivity.EXTRA_STORAGE_LABEL, entry.label)
                        putExtra(NetworkBrowserActivity.EXTRA_SMART_SORT_PICKER, true)
                        if (isCategoryPickerMode) {
                            putExtra(NetworkBrowserActivity.EXTRA_SMART_SORT_CATEGORY_PICKER, true)
                        }
                        if (entry.type == StorageEntryType.ONLINE) {
                            putExtra("isOnlineStorage", true)
                        }
                    }
                    networkPickerLauncher.launch(intent)
                }
            }
        }

        recyclerFolders.layoutManager = LinearLayoutManager(this)
        recyclerFolders.adapter = adapter

        showStorageRoots()
    }

    private fun showStorageRoots() {
        txtPath.text = getString(R.string.smart_sort_select_drive)
        val list = buildStorageList()
        adapter.submitList(list)
        updateEmptyState(list.isEmpty())
    }



    private fun openSmartSortSheet(path: String) {
        pendingSmartSortPath = path
        val intent = Intent(this, SmartSortTvActivity::class.java).apply {
            putExtra(SmartSortTvActivity.EXTRA_FOLDER_PATH, path)
            if (selectedShare != null) {
                SmartSortShareHolder.set(selectedShare!!)
                putExtra(SmartSortTvActivity.EXTRA_SHARE_ID, selectedShare!!.id)
            }
        }
        smartSortLauncher.launch(intent)
    }

    private fun refreshRecentButton() {
        val btnRecent = findViewById<ImageView>(R.id.btnRecentConfigs)
        if (btnRecent == null) return
        val allConfigs = SmartSortSavedConfigRepository.getAll()
        btnRecent.visibility = if (allConfigs.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun showRecentConfigsDialog() {
        val configs = SmartSortSavedConfigRepository.getAll()
        if (configs.isEmpty()) return

        val names = configs.map { it.description }.toTypedArray()
        MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setTitle(R.string.smart_sort_recent)
            .setItems(names) { _, which ->
                val saved = configs[which]
                MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
                    .setTitle(saved.description)
                    .setMessage(saved.folderPath)
                    .setPositiveButton(R.string.smart_sort_execute) { _, _ ->
                        executeSavedConfig(saved)
                    }
                    .setNeutralButton(R.string.smart_sort_delete_saved) { _, _ ->
                        confirmDeleteSavedConfig(saved)
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun executeSavedConfig(saved: SmartSortSavedConfig) {
        val intent = Intent(this, SmartSortTvActivity::class.java).apply {
            putExtra(SmartSortTvActivity.EXTRA_FOLDER_PATH, saved.folderPath)
            putExtra(SmartSortTvActivity.EXTRA_LOAD_CONFIG_ID, saved.id)
        }
        smartSortLauncher.launch(intent)
    }

    private fun confirmDeleteSavedConfig(saved: SmartSortSavedConfig) {
        MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setTitle(getString(R.string.smart_sort_delete_saved_confirm_title, saved.description))
            .setMessage(getString(R.string.smart_sort_delete_saved_confirm_msg, saved.description))
            .setPositiveButton(R.string.smart_sort_delete_saved) { _, _ ->
                SmartSortSavedConfigRepository.delete(saved.id)
                refreshRecentButton()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun buildStorageList(): List<StorageEntry> {
        val list = mutableListOf<StorageEntry>()

        // Local storage volumes
        val sm = getSystemService(Context.STORAGE_SERVICE) as StorageManager
        for (vol in sm.storageVolumes) {
            try {
                val dirPath = vol.safeDirectoryPath
                val dir = if (dirPath != null) File(dirPath) else null
                if (dir != null && dir.exists() && dir.canRead()) {
                    val label = if (vol.isPrimary) {
                        getString(R.string.internal_storage)
                    } else {
                        val uuid = vol.getUuid()
                        if (uuid != null) "SD Card ($uuid)" else "SD Card"
                    }
                    list.add(StorageEntry(
                        id = dir.absolutePath,
                        label = label,
                        path = dir.absolutePath,
                        iconRes = R.drawable.ic_storage_internal,
                        type = StorageEntryType.LOCAL
                    ))
                }
            } catch (_: Exception) { }
        }
        if (list.isEmpty()) {
            list.add(StorageEntry(
                id = "/storage/emulated/0",
                label = "Internal Storage",
                path = "/storage/emulated/0",
                iconRes = R.drawable.ic_storage_internal,
                type = StorageEntryType.LOCAL
            ))
        }

        // Network shares
        try {
            val netShares = NetworkShareRepository.getInstance(this).getAll()
            for (share in netShares) {
                list.add(StorageEntry(
                    id = share.id,
                    label = share.name,
                    path = share.host,
                    iconRes = R.drawable.ic_network,
                    type = StorageEntryType.NETWORK
                ))
            }
        } catch (_: Exception) { }

        // Online storages
        try {
            val onlineStorages = OnlineStorageRepository.getInstance(this).getAll()
            for (online in onlineStorages) {
                list.add(StorageEntry(
                    id = online.id,
                    label = online.displayName.ifBlank { online.email },
                    path = online.email,
                    iconRes = R.drawable.ic_cloud,
                    type = StorageEntryType.ONLINE
                ))
            }
        } catch (_: Exception) { }

        return list
    }

    private fun updateEmptyState(isEmpty: Boolean) {
        layoutEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
        recyclerFolders.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    private inner class FolderAdapter(
        private val onClick: (StorageEntry) -> Unit
    ) : RecyclerView.Adapter<FolderAdapter.VH>() {

        private val items = mutableListOf<StorageEntry>()

        fun submitList(newItems: List<StorageEntry>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val txtName: TextView = itemView.findViewById(R.id.txtFolderName)
            val txtPath: TextView = itemView.findViewById(R.id.txtFolderPath)
            val imgIcon: ImageView = itemView.findViewById(R.id.imgFolderIcon)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_smart_sort_folder, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val entry = items[position]
            holder.txtName.text = entry.label
            holder.txtPath.text = when (entry.type) {
                StorageEntryType.LOCAL -> entry.path
                StorageEntryType.NETWORK -> getString(R.string.network_tile_subtitle)
                StorageEntryType.ONLINE -> za.kilowatch.ultimatefilemanager.util.DeviceUtils.getOnlineStoragesSubtitle(this@SmartSortActivity)
            }
            holder.imgIcon.setImageResource(entry.iconRes)

            holder.itemView.alpha = 1.0f
            holder.itemView.isClickable = true
            holder.itemView.isFocusable = true
            holder.itemView.setOnClickListener { onClick(entry) }
        }

        override fun getItemCount(): Int = items.size
    }
}
