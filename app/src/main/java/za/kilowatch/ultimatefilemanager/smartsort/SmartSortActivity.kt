package za.kilowatch.ultimatefilemanager.smartsort

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.storage.StorageManager
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.storage.FileBrowserActivity
import za.kilowatch.ultimatefilemanager.network.NetworkBrowserActivity
import za.kilowatch.ultimatefilemanager.network.NetworkShare
import za.kilowatch.ultimatefilemanager.network.NetworkShareRepository
import za.kilowatch.ultimatefilemanager.network.OnlineStorageRepository
import za.kilowatch.ultimatefilemanager.settings.FontSizeHelper
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import za.kilowatch.ultimatefilemanager.util.ThemeColors
import za.kilowatch.ultimatefilemanager.util.safeDirectoryPath
import java.io.File

data class StorageEntry(
    val id: String,
    val label: String,
    val path: String,
    val iconRes: Int,
    val type: StorageEntryType
)

enum class StorageEntryType {
    LOCAL, NETWORK, ONLINE
}

class SmartSortActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SMART_SORT_CATEGORY_PICKER = "extra_smart_sort_category_picker"
        const val EXTRA_SMART_SORT_CATEGORY_KEY = "extra_smart_sort_category_key"
        const val RESULT_SELECTED_CATEGORY_PATH = "result_selected_category_path"
        const val RESULT_SELECTED_CATEGORY_KEY = "result_selected_category_key"
        const val RESULT_SELECTED_SHARE_ID = "result_selected_share_id"
    }

    private var isCategoryPickerMode = false
    private var pendingCategoryKey: String? = null
    private var selectedShare: NetworkShare? = null
    private var pendingSmartSortPath: String? = null
    private var handledFontChange = false
    private var handledLocaleChange = false

    private lateinit var contentLayout: LinearLayout
    private lateinit var layoutEmpty: LinearLayout
    private lateinit var txtPath: TextView

    private val localPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val selectedPath = result.data?.getStringExtra(FileBrowserActivity.RESULT_SELECTED_LOCAL_PATH)
                ?: result.data?.getStringExtra(FileBrowserActivity.RESULT_SELECTED_PATH)
            if (selectedPath != null) {
                if (isCategoryPickerMode) {
                    setResult(Activity.RESULT_OK, Intent().apply {
                        putExtra(RESULT_SELECTED_CATEGORY_PATH, selectedPath)
                        putExtra(RESULT_SELECTED_CATEGORY_KEY, pendingCategoryKey)
                    })
                    finish()
                } else {
                    openSmartSortSheet(selectedPath)
                }
            }
        }
    }

    private val networkPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val selectedPath = result.data?.getStringExtra(NetworkBrowserActivity.RESULT_SELECTED_SMART_SORT_PATH)
                ?: result.data?.getStringExtra(NetworkBrowserActivity.RESULT_SELECTED_NET_PATH)
            val shareId = result.data?.getStringExtra(NetworkBrowserActivity.RESULT_SELECTED_SMART_SORT_SHARE_ID)
                ?: result.data?.getStringExtra(NetworkBrowserActivity.RESULT_SELECTED_SHARE_ID)
            if (selectedPath != null) {
                if (isCategoryPickerMode) {
                    setResult(Activity.RESULT_OK, Intent().apply {
                        putExtra(RESULT_SELECTED_CATEGORY_PATH, selectedPath)
                        putExtra(RESULT_SELECTED_CATEGORY_KEY, pendingCategoryKey)
                        if (shareId != null) {
                            putExtra(RESULT_SELECTED_SHARE_ID, shareId)
                        }
                    })
                    finish()
                } else {
                    openSmartSortSheet(selectedPath)
                }
            }
        }
    }

    private val smartSortLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            finish()
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
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
        refreshRecentButton()
        showStorageRoots()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("handledFontChange", handledFontChange)
        outState.putBoolean("handledLocaleChange", handledLocaleChange)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handledFontChange = savedInstanceState?.getBoolean("handledFontChange", false) ?: false
        handledLocaleChange = savedInstanceState?.getBoolean("handledLocaleChange", false) ?: false
        enableEdgeToEdge()
        setContentView(R.layout.activity_smart_sort)

        isCategoryPickerMode = intent.getBooleanExtra(EXTRA_SMART_SORT_CATEGORY_PICKER, false)
        pendingCategoryKey = intent.getStringExtra(EXTRA_SMART_SORT_CATEGORY_KEY)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        contentLayout = findViewById(R.id.contentLayout)
        layoutEmpty = findViewById(R.id.layoutEmpty)
        txtPath = findViewById(R.id.txtPath)

        val btnRecent = findViewById<ImageView>(R.id.btnRecentConfigs)
        btnRecent?.setOnClickListener { showRecentConfigsDialog() }
        if (!DeviceUtils.isTvDevice(this)) {
            val primaryColor = ThemeColors.primary(this)
            btnRecent?.imageTintList = android.content.res.ColorStateList.valueOf(primaryColor)
        }

        findViewById<ImageView>(R.id.btnPickerBack).setOnClickListener { finish() }

        showStorageRoots()
    }

    private fun showStorageRoots() {
        txtPath.text = getString(R.string.smart_sort_select_drive)
        val allEntries = buildStorageList()
        val localList = allEntries.filter { it.type == StorageEntryType.LOCAL }
        val netList = allEntries.filter { it.type == StorageEntryType.NETWORK }
        val onlineList = allEntries.filter { it.type == StorageEntryType.ONLINE }

        contentLayout.removeAllViews()

        if (allEntries.isEmpty()) {
            layoutEmpty.visibility = View.VISIBLE
            return
        }
        layoutEmpty.visibility = View.GONE

        val isTv = DeviceUtils.isTvDevice(this)

        if (localList.isNotEmpty()) {
            contentLayout.addView(createSectionHeader(getString(R.string.storage_section_devices)))
            contentLayout.addView(createGroupCard(localList, isTv))
        }

        if (netList.isNotEmpty()) {
            contentLayout.addView(createSectionHeader(getString(R.string.network_shares_title)))
            contentLayout.addView(createGroupCard(netList, isTv))
        }

        if (onlineList.isNotEmpty()) {
            contentLayout.addView(createSectionHeader(getString(R.string.online_storages_title)))
            contentLayout.addView(createGroupCard(onlineList, isTv))
        }
    }

    private fun createGroupCard(entries: List<StorageEntry>, isTv: Boolean): MaterialCardView {
        val card = createGlassCard(isTv)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val inflater = LayoutInflater.from(this)

        entries.forEachIndexed { index, entry ->
            val row = inflater.inflate(
                if (isTv) R.layout.item_smart_sort_folder_tv else R.layout.item_smart_sort_folder_row,
                container,
                false
            )
            bindStorageRow(row, entry, isTv)
            container.addView(row)

            if (!isTv && index < entries.size - 1) {
                container.addView(createDivider())
            }
        }

        card.addView(container)
        return card
    }

    private fun bindStorageRow(row: View, entry: StorageEntry, isTv: Boolean) {
        val txtName = row.findViewById<TextView>(R.id.txtFolderName)
        val txtFolderPath = row.findViewById<TextView>(R.id.txtFolderPath)
        val imgIcon = row.findViewById<ImageView>(R.id.imgFolderIcon)

        txtName.text = entry.label
        txtFolderPath.text = when (entry.type) {
            StorageEntryType.LOCAL -> entry.path
            StorageEntryType.NETWORK -> getString(R.string.network_tile_subtitle)
            StorageEntryType.ONLINE -> DeviceUtils.getOnlineStoragesSubtitle(this)
        }
        imgIcon.setImageResource(entry.iconRes)

        row.setOnClickListener { onStorageEntryClicked(entry) }
    }

    private fun onStorageEntryClicked(entry: StorageEntry) {
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
                val share = SmartSortShareHolder.resolve(entry.id) ?: return
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

    private fun createSectionHeader(title: String): TextView {
        return TextView(this).apply {
            text = title
            setTextColor(ThemeColors.primary(this@SmartSortActivity))
            textSize = 13f
            typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
            isAllCaps = true
            letterSpacing = 0.05f
            val density = resources.displayMetrics.density
            setPadding(
                (4 * density).toInt(),
                (14 * density).toInt(),
                (4 * density).toInt(),
                (8 * density).toInt()
            )
        }
    }

    private fun createGlassCard(isTv: Boolean): MaterialCardView {
        val density = resources.displayMetrics.density
        return MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (12 * density).toInt()
            }
            radius = 16 * density
            strokeWidth = (1 * density).toInt()
            strokeColor = getColor(if (isTv) R.color.tv_glass_border else R.color.mobile_glass_stroke)
            setCardBackgroundColor(getColor(if (isTv) R.color.tv_glass_white_10 else R.color.mobile_glass_card))
            cardElevation = 0f
        }
    }

    private fun createDivider(): View {
        val density = resources.displayMetrics.density
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (1 * density).toInt()
            ).apply {
                marginStart = (72 * density).toInt()
                marginEnd = (14 * density).toInt()
            }
            setBackgroundColor(getColor(R.color.mobile_glass_stroke))
        }
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
        val layoutRecent = findViewById<View>(R.id.layoutRecentConfigs)
        val allConfigs = SmartSortSavedConfigRepository.getAll()
        val hasConfigs = allConfigs.isNotEmpty()
        btnRecent?.visibility = if (hasConfigs) View.VISIBLE else View.GONE
        layoutRecent?.visibility = if (hasConfigs) View.VISIBLE else View.GONE
    }

    private fun showRecentConfigsDialog() {
        val configs = SmartSortSavedConfigRepository.getAll()
        val isTv = DeviceUtils.isTvDevice(this)
        val dialogView = LayoutInflater.from(this).inflate(
            if (isTv) R.layout.dialog_smart_sort_recent_configs_tv else R.layout.dialog_smart_sort_recent_configs,
            null
        )
        val layoutList = dialogView.findViewById<LinearLayout>(R.id.layoutConfigList)
        val txtEmpty = dialogView.findViewById<TextView>(R.id.txtEmptyConfigs)

        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        fun renderConfigs() {
            layoutList.removeAllViews()
            val currentConfigs = SmartSortSavedConfigRepository.getAll()
            if (currentConfigs.isEmpty()) {
                txtEmpty.visibility = View.VISIBLE
                return
            }
            txtEmpty.visibility = View.GONE

            val inflater = LayoutInflater.from(this)
            for (saved in currentConfigs) {
                val item = inflater.inflate(
                    if (isTv) R.layout.item_smart_sort_recent_config_tv else R.layout.item_smart_sort_recent_config,
                    layoutList,
                    false
                )
                item.findViewById<TextView>(R.id.txtConfigDescription).text = saved.description
                item.findViewById<TextView>(R.id.txtConfigFolder).text = saved.folderPath

                item.findViewById<View>(R.id.btnExecuteConfig).setOnClickListener {
                    dialog.dismiss()
                    executeSavedConfig(saved)
                }

                item.findViewById<View>(R.id.btnDeleteConfig).setOnClickListener {
                    confirmDeleteSavedConfig(saved) {
                        renderConfigs()
                        refreshRecentButton()
                    }
                }

                item.setOnClickListener {
                    dialog.dismiss()
                    executeSavedConfig(saved)
                }

                layoutList.addView(item)
            }
        }

        renderConfigs()

        dialogView.findViewById<View>(R.id.btnCancel).setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun executeSavedConfig(saved: SmartSortSavedConfig) {
        val intent = Intent(this, SmartSortTvActivity::class.java).apply {
            putExtra(SmartSortTvActivity.EXTRA_FOLDER_PATH, saved.folderPath)
            putExtra(SmartSortTvActivity.EXTRA_LOAD_CONFIG_ID, saved.id)
        }
        smartSortLauncher.launch(intent)
    }

    private fun confirmDeleteSavedConfig(saved: SmartSortSavedConfig, onDeleted: (() -> Unit)? = null) {
        val isTv = DeviceUtils.isTvDevice(this)
        val dialogView = LayoutInflater.from(this).inflate(
            if (isTv) R.layout.dialog_smart_sort_delete_config_confirm_tv else R.layout.dialog_smart_sort_delete_config_confirm,
            null
        )
        val txtMessage = dialogView.findViewById<TextView>(R.id.txtDeleteMessage)
        txtMessage.text = getString(R.string.smart_sort_delete_saved_confirm_msg, saved.description)

        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogView.findViewById<View>(R.id.btnDeleteConfirm).setOnClickListener {
            SmartSortSavedConfigRepository.delete(saved.id)
            refreshRecentButton()
            onDeleted?.invoke()
            dialog.dismiss()
        }
        dialogView.findViewById<View>(R.id.btnCancel).setOnClickListener {
            dialog.dismiss()
        }
        dialog.show()
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
}
