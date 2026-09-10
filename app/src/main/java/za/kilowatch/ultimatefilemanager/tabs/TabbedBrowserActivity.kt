package za.kilowatch.ultimatefilemanager.tabs

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.network.NetworkBrowserActivity
import za.kilowatch.ultimatefilemanager.network.NetworkBrowserFragment
import za.kilowatch.ultimatefilemanager.network.NetworkClipboard
import za.kilowatch.ultimatefilemanager.network.DlnaShareClient
import za.kilowatch.ultimatefilemanager.network.DropboxShareClient
import za.kilowatch.ultimatefilemanager.network.FtpShareClient
import za.kilowatch.ultimatefilemanager.network.GoogleDriveShareClient
import za.kilowatch.ultimatefilemanager.network.NetworkFile
import za.kilowatch.ultimatefilemanager.network.NetworkShare
import za.kilowatch.ultimatefilemanager.network.NetworkShareRepository
import za.kilowatch.ultimatefilemanager.network.NfsShareClient
import za.kilowatch.ultimatefilemanager.network.OnedriveShareClient
import za.kilowatch.ultimatefilemanager.network.OnlineStorageProvider
import za.kilowatch.ultimatefilemanager.network.OnlineStorageRepository
import za.kilowatch.ultimatefilemanager.network.PairingManager
import za.kilowatch.ultimatefilemanager.network.RCloneShareClient
import za.kilowatch.ultimatefilemanager.network.S3ShareClient
import za.kilowatch.ultimatefilemanager.network.ShareType
import za.kilowatch.ultimatefilemanager.network.SmbShareClient
import za.kilowatch.ultimatefilemanager.network.SshShareClient
import za.kilowatch.ultimatefilemanager.network.TvShareClient
import za.kilowatch.ultimatefilemanager.network.WebDavShareClient
import za.kilowatch.ultimatefilemanager.settings.ColorblindPalette
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import za.kilowatch.ultimatefilemanager.settings.SettingsActivity
import za.kilowatch.ultimatefilemanager.storage.FileBrowserActivity
import za.kilowatch.ultimatefilemanager.storage.FileBrowserFragment
import za.kilowatch.ultimatefilemanager.storage.FileClipboard
import za.kilowatch.ultimatefilemanager.storage.SafFile
import za.kilowatch.ultimatefilemanager.storage.SafTreeManager
import za.kilowatch.ultimatefilemanager.storage.StorageBrowserActivity
import za.kilowatch.ultimatefilemanager.storage.TwinWindowActivity
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import za.kilowatch.ultimatefilemanager.util.TransferConflictHelper
import za.kilowatch.ultimatefilemanager.settings.ThemeHelper
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

class TabbedBrowserActivity : AppCompatActivity(),
    FileBrowserFragment.FileOperationsListener,
    NetworkBrowserFragment.NetworkOperationsListener {

    companion object {
        const val EXTRA_INITIAL_PATH = "extra_initial_path"
        const val EXTRA_INITIAL_ROOT_PATH = "extra_initial_root_path"
        const val EXTRA_INITIAL_LABEL = "extra_initial_label"
        const val EXTRA_INITIAL_SHARE_ID = "extra_initial_share_id"
        const val EXTRA_INITIAL_STORAGE_TYPE = "extra_initial_storage_type"
        const val EXTRA_OPEN_NEW_TAB_DIALOG = "extra_open_new_tab_dialog"
    }

    private val tabs = mutableListOf<TabModel>()
    private var activeTabId: String? = null
    private lateinit var tabAdapter: TabAdapter
    private lateinit var tabPagerAdapter: TabPagerAdapter
    private lateinit var tabTouchHelper: ItemTouchHelper

    // Views
    private lateinit var recyclerTabs: RecyclerView
    private lateinit var viewPagerTabs: ViewPager2
    private lateinit var badgeStorageType: TextView
    private lateinit var btnBack: ImageView
    private lateinit var btnSearchToggle: ImageView
    private lateinit var btnViewToggle: ImageView
    private lateinit var btnSort: ImageView
    private lateinit var btnTwinWindow: ImageView
    private lateinit var btnOptionsToggle: ImageView
    private lateinit var layoutSearchRow: LinearLayout
    private lateinit var edtSearch: EditText
    private lateinit var btnSearchClear: ImageView
    private lateinit var layoutBreadcrumbsScroll: HorizontalScrollView
    private lateinit var layoutBreadcrumbs: LinearLayout

    private var storageReceiver: BroadcastReceiver? = null

    private val storagePickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data ?: return@registerForActivityResult
            val isNetwork = data.getBooleanExtra("is_network", false)
            val isSafCustom = data.getBooleanExtra("is_saf_custom", false)

            if (isNetwork) {
                val shareId = data.getStringExtra("share_id") ?: return@registerForActivityResult
                val label = data.getStringExtra(FileBrowserActivity.EXTRA_STORAGE_LABEL) ?: getString(R.string.network_shares_title)
                val share = NetworkShareRepository.getInstance(this).getById(shareId)
                val protocol = share?.type?.name ?: "SMB"
                val type = if (share?.type == ShareType.GOOGLE_DRIVE || share?.type == ShareType.ONEDRIVE || share?.type == ShareType.DROPBOX) {
                    StorageType.CLOUD
                } else {
                    StorageType.NETWORK
                }
                addNewTab(
                    type = type,
                    rootPath = share?.remotePath ?: "",
                    currentPath = share?.remotePath ?: "",
                    label = label,
                    shareId = shareId,
                    protocol = protocol,
                    isEditing = false
                )
            } else {
                val path = data.getStringExtra("result_selected_local_path")
                    ?: data.getStringExtra(FileBrowserActivity.EXTRA_MOUNT_PATH)
                    ?: return@registerForActivityResult
                val label = data.getStringExtra(FileBrowserActivity.EXTRA_STORAGE_LABEL) ?: getString(R.string.storage_internal)
                val isSaf = isSafCustom || SafTreeManager.isSafPath(path)
                val isUsb = path.contains("usb", ignoreCase = true) || label.contains("usb", ignoreCase = true)
                val protocol = if (isUsb) "USB" else if (isSaf) "SD" else "LOCAL"
                addNewTab(
                    type = if (isSaf) StorageType.SAF else StorageType.LOCAL,
                    rootPath = path,
                    currentPath = path,
                    label = label,
                    protocol = protocol,
                    isEditing = false
                )
            }
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)

        // Mobile only — tabs are not enabled on TV
        if (DeviceUtils.isTvDevice(this)) {
            finish()
            return
        }

        setContentView(R.layout.activity_tabbed_browser)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(
                top = systemBars.top,
                bottom = systemBars.bottom,
                left = systemBars.left,
                right = systemBars.right
            )
            insets
        }

        initViews()
        setupTabRecycler()
        setupViewPager()
        setupBackNavigation()
        initSessionFromIntentOrSaved()
        registerMediaReceiver()

        if (intent.getBooleanExtra(EXTRA_OPEN_NEW_TAB_DIALOG, false)) {
            showNewTabDialog()
        }
    }

    override fun onResume() {
        super.onResume()
        updatePasteFab()
        validateAndPruneStorageTabs()
    }

    override fun onDestroy() {
        super.onDestroy()
        storageReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: Exception) {}
        }
    }

    private fun initViews() {
        recyclerTabs = findViewById(R.id.recyclerTabs)
        viewPagerTabs = findViewById(R.id.viewPagerTabs)
        badgeStorageType = findViewById(R.id.badgeStorageType)
        btnBack = findViewById(R.id.btnBack)
        btnSearchToggle = findViewById(R.id.btnSearchToggle)
        btnViewToggle = findViewById(R.id.btnViewToggle)
        btnSort = findViewById(R.id.btnSort)
        btnTwinWindow = findViewById(R.id.btnTwinWindow)
        btnOptionsToggle = findViewById(R.id.btnOptionsToggle)
        layoutSearchRow = findViewById(R.id.layoutSearchRow)
        edtSearch = findViewById(R.id.edtSearch)
        btnSearchClear = findViewById(R.id.btnSearchClear)
        layoutBreadcrumbsScroll = findViewById(R.id.layoutBreadcrumbsScroll)
        layoutBreadcrumbs = findViewById(R.id.layoutBreadcrumbs)

        // Top Back button: goes up a level; stays at root if already at root
        btnBack.setOnClickListener {
            val activeFrag = getActiveFragment()
            if (activeFrag is FileBrowserFragment) {
                activeFrag.handleBackPress()
            } else if (activeFrag is NetworkBrowserFragment) {
                activeFrag.handleBackPress()
            }
        }

        btnSearchToggle.setOnClickListener {
            if (layoutSearchRow.visibility == View.VISIBLE) {
                layoutSearchRow.visibility = View.GONE
                edtSearch.setText("")
                getActiveFragment()?.let { frag ->
                    if (frag is FileBrowserFragment) frag.search("")
                    else if (frag is NetworkBrowserFragment) frag.search("")
                }
            } else {
                layoutSearchRow.visibility = View.VISIBLE
                edtSearch.requestFocus()
            }
        }

        btnSearchClear.setOnClickListener {
            edtSearch.setText("")
        }

        edtSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim() ?: ""
                btnSearchClear.visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE
                getActiveFragment()?.let { frag ->
                    if (frag is FileBrowserFragment) frag.search(query)
                    else if (frag is NetworkBrowserFragment) frag.search(query)
                }
            }
        })

        edtSearch.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                val query = edtSearch.text.toString().trim()
                getActiveFragment()?.let { frag ->
                    if (frag is FileBrowserFragment) frag.search(query)
                    else if (frag is NetworkBrowserFragment) frag.search(query)
                }
                true
            } else false
        }

        btnViewToggle.setOnClickListener {
            getActiveFragment()?.let { frag ->
                if (frag is FileBrowserFragment) frag.openViewModeDialog()
                else if (frag is NetworkBrowserFragment) frag.openViewModeDialog()
            }
        }

        btnSort.setOnClickListener {
            getActiveFragment()?.let { frag ->
                if (frag is FileBrowserFragment) frag.openSortFilterSheet()
                else if (frag is NetworkBrowserFragment) frag.openSortFilterSheet()
            }
        }

        btnTwinWindow.setOnClickListener {
            val activeTab = getActiveTab() ?: return@setOnClickListener
            val intent = Intent(this, TwinWindowActivity::class.java).apply {
                if (activeTab.storageType == StorageType.NETWORK || activeTab.storageType == StorageType.CLOUD) {
                    putExtra(TwinWindowActivity.EXTRA_TOP_SHARE_ID, activeTab.shareId)
                    putExtra(TwinWindowActivity.EXTRA_TOP_SHARE_PATH, activeTab.currentPath)
                } else {
                    putExtra(TwinWindowActivity.EXTRA_TOP_LOCAL_PATH, activeTab.rootPath)
                    putExtra(TwinWindowActivity.EXTRA_TOP_LOCAL_LABEL, activeTab.storageLabel)
                    putExtra(TwinWindowActivity.EXTRA_TOP_LOCAL_INITIAL_PATH, activeTab.currentPath)
                }
            }
            startActivity(intent)
        }

        btnOptionsToggle.setOnClickListener { showOptionsMenu(it) }
    }

    private fun setupBackNavigation() {
        // System back gesture / hardware back: returns directly to Main Menu, preserving open tabs
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                TabSessionManager.saveSession(this@TabbedBrowserActivity, tabs, activeTabId ?: "")
                val intent = Intent(this@TabbedBrowserActivity, StorageBrowserActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                startActivity(intent)
                finish()
            }
        })
    }

    private fun setupTabRecycler() {
        val touchCallback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
            0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val fromPos = viewHolder.bindingAdapterPosition
                val toPos = target.bindingAdapterPosition

                if (viewHolder is TabAdapter.AddTabViewHolder || target is TabAdapter.AddTabViewHolder) {
                    return false
                }
                if (fromPos !in tabs.indices || toPos !in tabs.indices) {
                    return false
                }

                java.util.Collections.swap(tabs, fromPos, toPos)
                tabAdapter.notifyItemMoved(fromPos, toPos)
                return true
            }

            override fun canDropOver(
                recyclerView: RecyclerView,
                current: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val targetPos = target.bindingAdapterPosition
                return targetPos in tabs.indices
            }

            override fun isLongPressDragEnabled(): Boolean = false

            override fun isItemViewSwipeEnabled(): Boolean = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
                    viewHolder.itemView.animate()
                        .scaleX(1.06f)
                        .scaleY(1.06f)
                        .alpha(0.95f)
                        .setDuration(120)
                        .start()
                    viewHolder.itemView.elevation = 12f
                }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                viewHolder.itemView.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .alpha(1f)
                    .setDuration(120)
                    .start()
                viewHolder.itemView.elevation = 0f

                tabPagerAdapter.notifyDataSetChanged()
                val activePos = tabs.indexOfFirst { it.id == activeTabId }
                if (activePos >= 0) {
                    viewPagerTabs.setCurrentItem(activePos, false)
                }
                TabSessionManager.saveSession(this@TabbedBrowserActivity, tabs, activeTabId ?: "")
            }
        }

        tabTouchHelper = ItemTouchHelper(touchCallback)
        tabTouchHelper.attachToRecyclerView(recyclerTabs)

        tabAdapter = TabAdapter(
            tabs = tabs,
            activeTabId = activeTabId,
            onTabSelected = { tab, _ ->
                selectTab(tab)
            },
            onTabClose = { tab, pos ->
                closeTab(tab, pos)
            },
            onTabDuplicate = { tab, pos ->
                duplicateTab(tab, pos)
            },
            onTabRenamed = { tab, newName, _ ->
                tab.title = newName
                tab.isCustomName = true
                TabSessionManager.saveSession(this, tabs, activeTabId ?: "")
            },
            onAddTabClicked = {
                showNewTabDialog()
            },
            onStartDrag = { holder ->
                tabTouchHelper.startDrag(holder)
            }
        )

        recyclerTabs.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        recyclerTabs.adapter = tabAdapter
    }

    private fun setupViewPager() {
        tabPagerAdapter = TabPagerAdapter(this)
        viewPagerTabs.adapter = tabPagerAdapter
        viewPagerTabs.offscreenPageLimit = 2

        viewPagerTabs.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                tabAdapter.commitAllEdits()
                if (position in tabs.indices) {
                    val tab = tabs[position]
                    if (activeTabId != tab.id) {
                        activeTabId = tab.id
                        tabAdapter.setActiveTabId(tab.id, recyclerTabs)
                        recyclerTabs.smoothScrollToPosition(position)
                        updateBadgeAndBreadcrumbs(tab)
                        updatePasteFab()
                        TabSessionManager.saveSession(this@TabbedBrowserActivity, tabs, tab.id)
                    }
                }
            }
        })
    }

    private fun initSessionFromIntentOrSaved() {
        val (savedTabs, savedActiveId) = TabSessionManager.loadSession(this)
        val (validSavedTabs, _) = TabSessionManager.validateAndPrune(this, savedTabs)

        val initialPath = intent.getStringExtra(EXTRA_INITIAL_PATH)
        val initialRoot = intent.getStringExtra(EXTRA_INITIAL_ROOT_PATH) ?: initialPath
        val initialLabel = intent.getStringExtra(EXTRA_INITIAL_LABEL)
        val initialShareId = intent.getStringExtra(EXTRA_INITIAL_SHARE_ID)
        val initialTypeStr = intent.getStringExtra(EXTRA_INITIAL_STORAGE_TYPE)

        tabs.clear()

        if (validSavedTabs.isNotEmpty()) {
            tabs.addAll(validSavedTabs)

            if (initialPath != null && initialLabel != null) {
                val existing = if (!initialShareId.isNullOrEmpty()) {
                    tabs.firstOrNull { it.shareId == initialShareId }
                } else {
                    tabs.firstOrNull { it.rootPath == initialRoot || it.currentPath == initialPath }
                }
                if (existing != null) {
                    activeTabId = existing.id
                } else {
                    val type = try {
                        StorageType.valueOf(initialTypeStr ?: StorageType.LOCAL.name)
                    } catch (_: Exception) {
                        StorageType.LOCAL
                    }
                    val isUsb = initialPath.contains("usb", ignoreCase = true) || initialLabel.contains("usb", ignoreCase = true)
                    val protocol = if (isUsb) "USB" else if (type == StorageType.SAF) "SD" else if (type == StorageType.NETWORK) "SMB" else null
                    val newTab = TabModel(
                        title = initialLabel,
                        isCustomName = false,
                        storageType = type,
                        rootPath = initialRoot ?: initialPath,
                        currentPath = initialPath,
                        shareId = initialShareId,
                        protocol = protocol,
                        storageLabel = initialLabel
                    )
                    tabs.add(newTab)
                    activeTabId = newTab.id
                }
            } else {
                activeTabId = validSavedTabs.firstOrNull { it.id == savedActiveId }?.id ?: validSavedTabs.first().id
            }
        } else if (initialPath != null && initialLabel != null) {
            val type = try {
                StorageType.valueOf(initialTypeStr ?: StorageType.LOCAL.name)
            } catch (_: Exception) {
                StorageType.LOCAL
            }
            val isUsb = initialPath.contains("usb", ignoreCase = true) || initialLabel.contains("usb", ignoreCase = true)
            val protocol = if (isUsb) "USB" else if (type == StorageType.SAF) "SD" else if (type == StorageType.NETWORK) "SMB" else null
            val defaultTab = TabModel(
                title = initialLabel,
                isCustomName = false,
                storageType = type,
                rootPath = initialRoot ?: initialPath,
                currentPath = initialPath,
                shareId = initialShareId,
                protocol = protocol,
                storageLabel = initialLabel
            )
            tabs.add(defaultTab)
            activeTabId = defaultTab.id
        } else {
            val defaultTab = TabSessionManager.createDefaultTab(this)
            tabs.add(defaultTab)
            activeTabId = defaultTab.id
        }

        tabAdapter.setActiveTabId(activeTabId ?: tabs.first().id)
        tabAdapter.notifyDataSetChanged()
        tabPagerAdapter.notifyDataSetChanged()

        val activeIndex = tabs.indexOfFirst { it.id == activeTabId }.coerceAtLeast(0)
        viewPagerTabs.setCurrentItem(activeIndex, false)
        recyclerTabs.scrollToPosition(activeIndex)
        updateBadgeAndBreadcrumbs(tabs[activeIndex])
        updatePasteFab()
        TabSessionManager.saveSession(this, tabs, activeTabId ?: "")
    }

    fun selectTab(tab: TabModel) {
        tabAdapter.commitAllEdits()
        activeTabId = tab.id
        val pos = tabs.indexOfFirst { it.id == tab.id }
        if (pos >= 0) {
            tabAdapter.setActiveTabId(tab.id, recyclerTabs)
            if (viewPagerTabs.currentItem != pos) {
                viewPagerTabs.setCurrentItem(pos, true)
            }
            recyclerTabs.smoothScrollToPosition(pos)
        }
        updateBadgeAndBreadcrumbs(tab)
        updatePasteFab()
        TabSessionManager.saveSession(this, tabs, tab.id)
    }

    private fun updateBadgeAndBreadcrumbs(tab: TabModel) {
        val accentColor = tab.getAccentColor()
        badgeStorageType.text = when (tab.storageType) {
            StorageType.LOCAL -> getString(R.string.storage_internal).uppercase()
            StorageType.SAF -> getString(R.string.storage_sd_card).uppercase()
            StorageType.NETWORK -> (tab.protocol ?: getString(R.string.storage_network)).uppercase()
            StorageType.CLOUD -> (tab.protocol ?: getString(R.string.storage_cloud)).uppercase()
        }
        badgeStorageType.setTextColor(accentColor)
        updateBreadcrumbs(tab)
    }

    private fun onTabDirectoryChanged(tab: TabModel, newPath: String) {
        tab.currentPath = newPath
        if (!tab.isCustomName && !tab.isEditing) {
            val folderName = newPath.trimEnd('/').substringAfterLast('/')
            val newTitle = if (folderName.isNotEmpty()) folderName else tab.storageLabel
            tab.title = newTitle
            val pos = tabs.indexOf(tab)
            if (pos >= 0) {
                if (recyclerTabs.isComputingLayout) {
                    recyclerTabs.post {
                        if (pos < tabs.size) tabAdapter.notifyItemChanged(pos)
                    }
                } else {
                    tabAdapter.notifyItemChanged(pos)
                }
            }
        }
        if (tab.id == activeTabId) {
            updateBreadcrumbs(tab)
            updatePasteFab()
        }
        TabSessionManager.saveSession(this, tabs, activeTabId ?: "")
    }

    fun addNewTab(
        type: StorageType,
        rootPath: String,
        currentPath: String,
        label: String,
        shareId: String? = null,
        protocol: String? = null,
        isEditing: Boolean = false
    ) {
        val folderName = currentPath.trimEnd('/').substringAfterLast('/')
        val initialTitle = if (folderName.isNotEmpty()) folderName else label
        val resolvedProtocol = protocol ?: when (type) {
            StorageType.LOCAL -> if (rootPath.contains("usb", ignoreCase = true) || label.contains("usb", ignoreCase = true)) "USB" else "LOCAL"
            StorageType.SAF -> if (rootPath.contains("usb", ignoreCase = true) || label.contains("usb", ignoreCase = true)) "USB" else "SD"
            StorageType.NETWORK -> "SMB"
            StorageType.CLOUD -> "CLOUD"
        }

        val newTab = TabModel(
            title = initialTitle,
            isCustomName = false,
            storageType = type,
            rootPath = rootPath,
            currentPath = currentPath,
            shareId = shareId,
            protocol = resolvedProtocol,
            storageLabel = label,
            isEditing = isEditing
        )

        tabs.add(newTab)
        val newIndex = tabs.size - 1
        tabAdapter.notifyDataSetChanged()
        tabPagerAdapter.notifyDataSetChanged()

        selectTab(newTab)
    }

    fun showNewTabDialog() {
        val activeTab = getActiveTab()
        val currentPathDisplay = activeTab?.currentPath ?: getString(R.string.storage_internal)

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_new_tab_location, null)
        val txtPath = dialogView.findViewById<TextView>(R.id.txtThisLocationPath)
        val cardThisLocation = dialogView.findViewById<View>(R.id.cardThisLocation)
        val cardOtherStorage = dialogView.findViewById<View>(R.id.cardOtherStorage)
        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnCancel)

        txtPath.text = currentPathDisplay

        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .create()

        cardThisLocation.setOnClickListener {
            dialog.dismiss()
            if (activeTab != null) {
                addNewTab(
                    type = activeTab.storageType,
                    rootPath = activeTab.rootPath,
                    currentPath = activeTab.currentPath,
                    label = activeTab.storageLabel,
                    shareId = activeTab.shareId,
                    protocol = activeTab.protocol,
                    isEditing = false
                )
            } else {
                val internalPath = android.os.Environment.getExternalStorageDirectory().absolutePath
                addNewTab(
                    type = StorageType.LOCAL,
                    rootPath = internalPath,
                    currentPath = internalPath,
                    label = getString(R.string.storage_internal),
                    protocol = "LOCAL",
                    isEditing = false
                )
            }
        }

        cardOtherStorage.setOnClickListener {
            dialog.dismiss()
            val intent = Intent(this, StorageBrowserActivity::class.java).apply {
                putExtra(FileBrowserActivity.EXTRA_PICKER_MODE, true)
                putExtra(StorageBrowserActivity.EXTRA_DRIVE_PICKER, true)
            }
            storagePickerLauncher.launch(intent)
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    }

    private fun closeTab(tab: TabModel, position: Int) {
        if (tabs.size > 1) {
            val wasActive = tab.id == activeTabId
            tabs.removeAt(position)
            tabAdapter.notifyDataSetChanged()
            tabPagerAdapter.notifyDataSetChanged()

            if (wasActive) {
                val nextPos = (position.coerceAtMost(tabs.size - 1)).coerceAtLeast(0)
                val nextTab = tabs[nextPos]
                activeTabId = nextTab.id
                tabAdapter.setActiveTabId(nextTab.id, recyclerTabs)
                viewPagerTabs.setCurrentItem(nextPos, true)
                updateBadgeAndBreadcrumbs(nextTab)
            }
            TabSessionManager.saveSession(this, tabs, activeTabId ?: "")
        } else {
            // Closing last remaining tab: exit tabbed session and keep open at that location in single browser mode
            closeLastTabAndKeepOpenAtLocation(tab)
        }
    }

    private fun duplicateTab(sourceTab: TabModel, position: Int) {
        // Source tab must strictly keep its exact title and remain custom
        sourceTab.isCustomName = true

        val allTitles = tabs.map { it.title }
        val newTitle = generateDuplicateTabName(sourceTab.title, allTitles)

        val duplicatedTab = TabModel(
            title = newTitle,
            isCustomName = true,
            storageType = sourceTab.storageType,
            rootPath = sourceTab.rootPath,
            currentPath = sourceTab.currentPath,
            shareId = sourceTab.shareId,
            protocol = sourceTab.protocol,
            storageLabel = sourceTab.storageLabel,
            isEditing = false
        )

        val sourceIndex = tabs.indexOfFirst { it.id == sourceTab.id }.let { if (it >= 0) it else position }
        val insertPos = (sourceIndex + 1).coerceAtMost(tabs.size)
        tabs.add(insertPos, duplicatedTab)

        tabAdapter.notifyDataSetChanged()
        tabPagerAdapter.notifyDataSetChanged()

        selectTab(duplicatedTab)
        recyclerTabs.post {
            recyclerTabs.smoothScrollToPosition(insertPos)
        }

        val msg = getString(R.string.tab_duplicated, newTitle)
        Snackbar.make(findViewById(R.id.main), msg, Snackbar.LENGTH_SHORT).show()
    }

    private fun generateDuplicateTabName(sourceTitle: String, allTabTitles: List<String>): String {
        val regex = Regex("""^(.*?)\s*\((\d+)\)$""")
        val match = regex.find(sourceTitle)
        val baseName = if (match != null) {
            match.groupValues[1].trim()
        } else {
            sourceTitle.trim()
        }

        var nextIndex = 1
        var candidate = "$baseName ($nextIndex)"
        while (allTabTitles.contains(candidate)) {
            nextIndex++
            candidate = "$baseName ($nextIndex)"
        }
        return candidate
    }

    private fun closeLastTabAndKeepOpenAtLocation(lastTab: TabModel) {
        TabSessionManager.clearSession(this)
        if (lastTab.storageType == StorageType.NETWORK || lastTab.storageType == StorageType.CLOUD) {
            val intent = Intent(this, NetworkBrowserActivity::class.java).apply {
                putExtra(NetworkBrowserActivity.EXTRA_SHARE_ID, lastTab.shareId)
                putExtra(NetworkBrowserActivity.EXTRA_INITIAL_PATH, lastTab.currentPath)
                putExtra(NetworkBrowserActivity.EXTRA_STORAGE_LABEL, lastTab.storageLabel)
            }
            startActivity(intent)
        } else {
            val intent = Intent(this, FileBrowserActivity::class.java).apply {
                putExtra(FileBrowserActivity.EXTRA_MOUNT_PATH, lastTab.rootPath)
                putExtra(FileBrowserActivity.EXTRA_INITIAL_PATH, lastTab.currentPath)
                putExtra(FileBrowserActivity.EXTRA_STORAGE_LABEL, lastTab.storageLabel)
                val isSaf = SafTreeManager.isSafPath(lastTab.rootPath) || lastTab.storageType == StorageType.SAF
                putExtra(FileBrowserActivity.EXTRA_STORAGE_TYPE, if (isSaf) "SAF" else "LOCAL")
            }
            startActivity(intent)
        }
        finish()
    }

    private fun updateBreadcrumbs(tab: TabModel) {
        layoutBreadcrumbs.removeAllViews()
        val inflater = LayoutInflater.from(this)

        val homeLabel = getString(R.string.home)
        val list = mutableListOf<Pair<String, String>>()
        list.add(Pair(homeLabel, ""))
        list.add(Pair(tab.storageLabel, tab.rootPath))

        if (tab.currentPath != tab.rootPath) {
            val relative = if (tab.rootPath.isEmpty()) {
                tab.currentPath.trimStart('/')
            } else if (tab.currentPath.startsWith(tab.rootPath)) {
                tab.currentPath.removePrefix(tab.rootPath).trimStart('/')
            } else {
                tab.currentPath.trimStart('/')
            }
            if (relative.isNotEmpty()) {
                val parts = relative.split('/')
                var accum = tab.rootPath.trimEnd('/')
                for (part in parts) {
                    if (part.isNotEmpty()) {
                        accum = if (accum.isEmpty()) part else "$accum/$part"
                        list.add(Pair(part, accum))
                    }
                }
            }
        }

        for (i in list.indices) {
            val item = list[i]
            val view = if (i == 0) {
                inflater.inflate(R.layout.item_breadcrumb_home, layoutBreadcrumbs, false).apply {
                    setOnClickListener {
                        val intent = Intent(this@TabbedBrowserActivity, StorageBrowserActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        }
                        startActivity(intent)
                    }
                }
            } else {
                inflater.inflate(R.layout.item_breadcrumb_text, layoutBreadcrumbs, false).apply {
                    findViewById<TextView>(R.id.txtBreadcrumbName).apply {
                        text = item.first
                    }
                    if (i == list.lastIndex) {
                        findViewById<TextView>(R.id.txtBreadcrumbName).setTextColor(tab.getAccentColor())
                        isClickable = false
                        isFocusable = false
                    } else {
                        setOnClickListener {
                            val frag = getActiveFragment()
                            if (frag is FileBrowserFragment) {
                                frag.navigateTo(File(item.second))
                            } else if (frag is NetworkBrowserFragment) {
                                frag.navigateTo(item.second)
                            }
                        }
                    }
                }
            }
            layoutBreadcrumbs.addView(view)

            if (i < list.lastIndex) {
                val sep = inflater.inflate(R.layout.item_breadcrumb_separator, layoutBreadcrumbs, false)
                layoutBreadcrumbs.addView(sep)
            }
        }

        layoutBreadcrumbsScroll.post {
            layoutBreadcrumbsScroll.fullScroll(HorizontalScrollView.FOCUS_RIGHT)
        }
    }

    private fun updatePasteFab() {
        val frag = getActiveFragment()
        if (frag is FileBrowserFragment) {
            frag.updatePasteFab()
        } else if (frag is NetworkBrowserFragment) {
            frag.updatePasteFab()
        }
    }

    private fun handleCopyOrCut(selected: List<File>, isMove: Boolean) {
        if (selected.isEmpty()) return
        val op = if (isMove) FileClipboard.Operation.MOVE else FileClipboard.Operation.COPY
        val activeTab = getActiveTab()
        val currentPath = activeTab?.currentPath ?: ""
        val recentSlot = FileClipboard.getRecentSlot()

        if (recentSlot == null) {
            FileClipboard.pushLocalSlot(selected, op, currentPath)
            Snackbar.make(findViewById(R.id.main), getString(if (isMove) R.string.clipboard_cut else R.string.clipboard_copied, selected.size), Snackbar.LENGTH_SHORT).show()
            updatePasteFab()
        } else {
            val dialogView = layoutInflater.inflate(R.layout.dialog_clipboard_add_or_new, null)
            val txtSubtitle = dialogView.findViewById<TextView>(R.id.txtAddOrNewSubtitle)
            val recyclerSlots = dialogView.findViewById<RecyclerView>(R.id.recyclerExistingSlots)
            val btnNewSlot = dialogView.findViewById<MaterialButton>(R.id.btnNewSlot)
            val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnCancelAddOrNew)

            txtSubtitle.text = getString(R.string.clipboard_slots_title, FileClipboard.slots.size, FileClipboard.totalItemCount())

            val dialog = BottomSheetDialog(this).apply {
                setContentView(dialogView)
            }

            recyclerSlots.layoutManager = LinearLayoutManager(this)
            class SlotChoiceViewHolder(val v: View) : RecyclerView.ViewHolder(v) {
                val txtLabel: TextView = v.findViewById(R.id.txtSlotLabel)
                val txtSummary: TextView = v.findViewById(R.id.txtSlotSummary)
                val card: View = v.findViewById(R.id.cardSlotChoice)
            }

            val slotsList = FileClipboard.slots
            recyclerSlots.adapter = object : RecyclerView.Adapter<SlotChoiceViewHolder>() {
                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SlotChoiceViewHolder {
                    val view = layoutInflater.inflate(R.layout.item_clipboard_slot_choice, parent, false)
                    return SlotChoiceViewHolder(view)
                }

                override fun getItemCount(): Int = slotsList.size

                override fun onBindViewHolder(holder: SlotChoiceViewHolder, position: Int) {
                    val slot = slotsList.getOrNull(position) ?: return
                    holder.txtLabel.text = slot.label
                    val fileSummary = slot.items.take(3).joinToString(", ") { it.name }
                    holder.txtSummary.text = "${slot.totalCount} item(s) • $fileSummary"

                    holder.card.setOnClickListener {
                        dialog.dismiss()
                        FileClipboard.addLocalToSlot(slot.id, selected, op)
                        Snackbar.make(findViewById(R.id.main), getString(if (isMove) R.string.clipboard_cut else R.string.clipboard_copied, selected.size), Snackbar.LENGTH_SHORT).show()
                        updatePasteFab()
                    }
                }
            }

            btnNewSlot.setOnClickListener {
                dialog.dismiss()
                if (FileClipboard.isFull) {
                    Toast.makeText(this, R.string.clipboard_full_paste_first, Toast.LENGTH_SHORT).show()
                } else {
                    FileClipboard.pushLocalSlot(selected, op, currentPath)
                    if (FileClipboard.slots.size == 9) {
                        Toast.makeText(this, R.string.clipboard_warning_one_slot_left, Toast.LENGTH_SHORT).show()
                    }
                    Snackbar.make(findViewById(R.id.main), getString(R.string.clipboard_slot_created, selected.size, FileClipboard.slots.size), Snackbar.LENGTH_SHORT).show()
                    updatePasteFab()
                }
            }

            btnCancel.setOnClickListener {
                dialog.dismiss()
            }

            dialog.show()
        }
    }

    private fun handleNetworkCopyOrCut(selected: List<NetworkFile>, isMove: Boolean, share: NetworkShare) {
        if (selected.isEmpty()) return
        val op = if (isMove) FileClipboard.Operation.MOVE else FileClipboard.Operation.COPY
        val activeTab = getActiveTab()
        val currentPath = activeTab?.currentPath ?: ""
        val effectiveRemotePath = when {
            share.remotePath.isNotBlank() -> share.remotePath
            currentPath.isNotBlank() -> currentPath
            else -> ""
        }
        val recentSlot = FileClipboard.getRecentSlot()

        if (recentSlot == null) {
            FileClipboard.pushRemoteSlot(selected, op, share.id, effectiveRemotePath, customLabel = share.name)
            Snackbar.make(findViewById(R.id.main), getString(if (isMove) R.string.clipboard_cut else R.string.clipboard_copied, selected.size), Snackbar.LENGTH_SHORT).show()
            updatePasteFab()
        } else {
            val dialogView = layoutInflater.inflate(R.layout.dialog_clipboard_add_or_new, null)
            val txtSubtitle = dialogView.findViewById<TextView>(R.id.txtAddOrNewSubtitle)
            val recyclerSlots = dialogView.findViewById<RecyclerView>(R.id.recyclerExistingSlots)
            val btnNewSlot = dialogView.findViewById<MaterialButton>(R.id.btnNewSlot)
            val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnCancelAddOrNew)

            txtSubtitle.text = getString(R.string.clipboard_slots_title, FileClipboard.slots.size, FileClipboard.totalItemCount())

            val dialog = BottomSheetDialog(this).apply {
                setContentView(dialogView)
            }

            recyclerSlots.layoutManager = LinearLayoutManager(this)
            class SlotChoiceViewHolder(val v: View) : RecyclerView.ViewHolder(v) {
                val txtLabel: TextView = v.findViewById(R.id.txtSlotLabel)
                val txtSummary: TextView = v.findViewById(R.id.txtSlotSummary)
                val card: View = v.findViewById(R.id.cardSlotChoice)
            }

            val slotsList = FileClipboard.slots
            recyclerSlots.adapter = object : RecyclerView.Adapter<SlotChoiceViewHolder>() {
                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SlotChoiceViewHolder {
                    val view = layoutInflater.inflate(R.layout.item_clipboard_slot_choice, parent, false)
                    return SlotChoiceViewHolder(view)
                }

                override fun getItemCount(): Int = slotsList.size

                override fun onBindViewHolder(holder: SlotChoiceViewHolder, position: Int) {
                    val slot = slotsList.getOrNull(position) ?: return
                    holder.txtLabel.text = slot.label
                    val fileSummary = slot.items.take(3).joinToString(", ") { it.name }
                    holder.txtSummary.text = "${slot.totalCount} item(s) • $fileSummary"

                    holder.card.setOnClickListener {
                        dialog.dismiss()
                        FileClipboard.addRemoteToSlot(slot.id, selected, op, share.id, effectiveRemotePath)
                        Snackbar.make(findViewById(R.id.main), getString(if (isMove) R.string.clipboard_cut else R.string.clipboard_copied, selected.size), Snackbar.LENGTH_SHORT).show()
                        updatePasteFab()
                    }
                }
            }

            btnNewSlot.setOnClickListener {
                dialog.dismiss()
                if (FileClipboard.isFull) {
                    Toast.makeText(this, R.string.clipboard_full_paste_first, Toast.LENGTH_SHORT).show()
                } else {
                    FileClipboard.pushRemoteSlot(selected, op, share.id, effectiveRemotePath, customLabel = share.name)
                    if (FileClipboard.slots.size == 9) {
                        Toast.makeText(this, R.string.clipboard_warning_one_slot_left, Toast.LENGTH_SHORT).show()
                    }
                    Snackbar.make(findViewById(R.id.main), getString(R.string.clipboard_slot_created, selected.size, FileClipboard.slots.size), Snackbar.LENGTH_SHORT).show()
                    updatePasteFab()
                }
            }

            btnCancel.setOnClickListener {
                dialog.dismiss()
            }

            dialog.show()
        }
    }

    private fun resolveShare(shareId: String?, remotePath: String = "", filePath: String = ""): NetworkShare? {
        if (shareId.isNullOrEmpty()) return null
        var fromRepo = NetworkShareRepository.getInstance(this).getById(shareId)
        if (fromRepo != null) {
            if (fromRepo.type == ShareType.SMB && fromRepo.isServerMode) {
                val shareName = when {
                    fromRepo.remotePath.isNotBlank() -> fromRepo.remotePath.trimStart('/').substringBefore('/')
                    remotePath.isNotBlank() -> remotePath.trimStart('/').substringBefore('/')
                    filePath.isNotBlank() && filePath.trimStart('/').contains('/') -> filePath.trimStart('/').substringBefore('/')
                    else -> ""
                }
                if (shareName.isNotEmpty()) {
                    fromRepo = fromRepo.copy(remotePath = "/$shareName")
                }
            }
            return fromRepo
        }
        val online = OnlineStorageRepository.getInstance(this).getById(shareId)
        if (online != null) {
            return NetworkShare(
                id = online.id,
                name = online.displayName,
                type = when (online.provider) {
                    OnlineStorageProvider.ONEDRIVE     -> ShareType.ONEDRIVE
                    OnlineStorageProvider.GOOGLE_DRIVE -> ShareType.GOOGLE_DRIVE
                    OnlineStorageProvider.DROPBOX      -> ShareType.DROPBOX
                    OnlineStorageProvider.AWS_S3       -> ShareType.AWS_S3
                    OnlineStorageProvider.IDRIVE_E2    -> ShareType.IDRIVE_E2
                    OnlineStorageProvider.WEBDAV       -> ShareType.WEBDAV
                    OnlineStorageProvider.RCLONE       -> ShareType.WEBDAV
                },
                host = when (online.provider) {
                    OnlineStorageProvider.RCLONE -> RCloneShareClient.RCLONE_HOST_MARKER
                    else -> if (online.isWebDavProvider) online.webDavUrl ?: "" else online.s3Endpoint ?: online.email
                },
                domain = online.s3Bucket ?: "",
                remotePath = online.s3Region ?: "",
                username = when (online.provider) {
                    OnlineStorageProvider.RCLONE -> online.id
                    else -> if (online.isWebDavProvider) online.webDavUsername ?: "" else online.s3AccessKey ?: online.email
                },
                password = when {
                    online.isWebDavProvider -> online.webDavPassword ?: ""
                    else                    -> online.s3SecretKey ?: ""
                },
                readOnly = false
            )
        }
        return PairingManager.getInstance(this).getPairedDevice(shareId)?.let { dev ->
            NetworkShare(id = dev.deviceId, name = dev.name, type = ShareType.TV, host = dev.lastIp, port = dev.lastPort, readOnly = false)
        }
    }

    private suspend fun listRemoteFiles(share: NetworkShare, path: String): List<NetworkFile> {
        return when (share.type) {
            ShareType.SMB -> SmbShareClient.listFiles(share, path)
            ShareType.FTP -> FtpShareClient.listFiles(share, path)
            ShareType.TV -> TvShareClient.listFiles(share, path)
            ShareType.SFTP, ShareType.SCP -> SshShareClient.listFiles(share, path)
            ShareType.ONEDRIVE -> OnedriveShareClient.listFiles(share, path)
            ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.listFiles(share, path)
            ShareType.DROPBOX -> DropboxShareClient.listFiles(share, path)
            ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.listFiles(share, path)
            ShareType.WEBDAV -> WebDavShareClient.listFiles(share, path)
            ShareType.NFS -> NfsShareClient.listFiles(share, path)
            ShareType.DLNA -> DlnaShareClient.listFiles(share, path)
        }
    }

    private suspend fun makeRemoteDir(share: NetworkShare, path: String) {
        when (share.type) {
            ShareType.SMB -> SmbShareClient.mkdir(share, path)
            ShareType.FTP -> FtpShareClient.mkdir(share, path)
            ShareType.TV -> TvShareClient.mkdir(share, path)
            ShareType.SFTP, ShareType.SCP -> SshShareClient.mkdir(share, path)
            ShareType.ONEDRIVE -> OnedriveShareClient.mkdir(share, path)
            ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.mkdir(share, path)
            ShareType.DROPBOX -> DropboxShareClient.mkdir(share, path)
            ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.mkdir(share, path)
            ShareType.WEBDAV -> WebDavShareClient.mkdir(share, path)
            ShareType.NFS -> NfsShareClient.mkdir(share, path)
            ShareType.DLNA -> throw UnsupportedOperationException("DLNA is read-only")
        }
    }

    private suspend fun deleteRemoteFile(share: NetworkShare, path: String, isDir: Boolean = false) {
        if (isDir) {
            TransferConflictHelper.deleteNetworkDirRecursively(share, path)
        } else {
            when (share.type) {
                ShareType.SMB -> SmbShareClient.deleteFile(share, path)
                ShareType.FTP -> FtpShareClient.deleteFile(share, path)
                ShareType.TV -> TvShareClient.deleteFile(share, path)
                ShareType.SFTP, ShareType.SCP -> SshShareClient.delete(share, path, false)
                ShareType.ONEDRIVE -> OnedriveShareClient.deleteFile(share, path)
                ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.deleteFile(share, path)
                ShareType.DROPBOX -> DropboxShareClient.deleteFile(share, path)
                ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.deleteFile(share, path)
                ShareType.WEBDAV -> WebDavShareClient.deleteFile(share, path)
                ShareType.NFS -> NfsShareClient.deleteFile(share, path)
                ShareType.DLNA -> throw UnsupportedOperationException("DLNA is read-only")
            }
        }
    }

    private suspend fun renameRemoteFile(share: NetworkShare, oldPath: String, newPath: String, isDir: Boolean = false) {
        when (share.type) {
            ShareType.SMB -> SmbShareClient.rename(share, oldPath, newPath)
            ShareType.FTP -> FtpShareClient.rename(share, oldPath, newPath)
            ShareType.TV -> TvShareClient.rename(share, oldPath, newPath)
            ShareType.SFTP, ShareType.SCP -> SshShareClient.rename(share, oldPath, newPath)
            ShareType.ONEDRIVE -> OnedriveShareClient.rename(share, oldPath, newPath)
            ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.rename(share, oldPath, newPath)
            ShareType.DROPBOX -> DropboxShareClient.rename(share, oldPath, newPath)
            ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.rename(share, oldPath, newPath)
            ShareType.WEBDAV -> WebDavShareClient.rename(share, oldPath, newPath, isDir)
            ShareType.NFS -> NfsShareClient.rename(share, oldPath, newPath)
            ShareType.DLNA -> throw UnsupportedOperationException("DLNA is read-only")
        }
    }

    private fun showClipboardSheet() {
        if (!FileClipboard.hasItems()) return
        val colorCopy = getColor(R.color.ufm_primary)
        val colorCut = ColorblindPalette.denied(this)

        val contentView = layoutInflater.inflate(R.layout.bottom_sheet_clipboard, null)
        val tabLayout = contentView.findViewById<TabLayout>(R.id.tabClipboardSlots)
        val viewPager = contentView.findViewById<ViewPager2>(R.id.vpClipboardSlots)
        val btnPasteHere = contentView.findViewById<MaterialButton>(R.id.btnPasteHere)
        val btnPasteSlot = contentView.findViewById<MaterialButton?>(R.id.btnPasteSlot)
        val btnRemoveSlot = contentView.findViewById<MaterialButton?>(R.id.btnRemoveSlot)
        val btnClearAll = contentView.findViewById<MaterialButton>(R.id.btnClearClipboard)
        val txtTitle = contentView.findViewById<TextView>(R.id.txtClipboardTitle)
        val layoutSlotActions = contentView.findViewById<View?>(R.id.layoutSlotActions)

        val dialog = BottomSheetDialog(this).apply {
            setContentView(contentView)
        }

        fun updateUI() {
            val slots = FileClipboard.slots
            val total = FileClipboard.totalItemCount()
            if (slots.isEmpty() || total == 0) {
                dialog.dismiss()
                updatePasteFab()
                return
            }

            if (slots.size <= 1) {
                txtTitle.text = if (total == 1) getString(R.string.clipboard_1_file) else getString(R.string.clipboard_total_files, total)
                tabLayout.visibility = View.GONE
                btnPasteHere.text = getString(R.string.paste_here)
                layoutSlotActions?.visibility = View.GONE
            } else {
                txtTitle.text = getString(R.string.clipboard_slots_title, slots.size, total)
                tabLayout.visibility = View.VISIBLE
                btnPasteHere.text = getString(R.string.paste_all_slots)
                layoutSlotActions?.visibility = View.VISIBLE
            }
            updatePasteFab()
        }

        class SlotItemViewHolder(val v: View) : RecyclerView.ViewHolder(v)
        class SlotPageViewHolder(val view: View) : RecyclerView.ViewHolder(view) {
            val recycler: RecyclerView = view.findViewById(R.id.recyclerSlotItems)
        }

        val pagerAdapter = object : RecyclerView.Adapter<SlotPageViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SlotPageViewHolder {
                val v = layoutInflater.inflate(R.layout.item_clipboard_slot_page, parent, false)
                return SlotPageViewHolder(v)
            }

            override fun getItemCount(): Int = FileClipboard.slots.size

            override fun onBindViewHolder(holder: SlotPageViewHolder, position: Int) {
                val slot = FileClipboard.slots.getOrNull(position) ?: return
                val itemsRecycler = holder.recycler
                itemsRecycler.layoutManager = LinearLayoutManager(this@TabbedBrowserActivity)
                itemsRecycler.adapter = object : RecyclerView.Adapter<SlotItemViewHolder>() {
                    override fun onCreateViewHolder(p: ViewGroup, vt: Int) =
                        SlotItemViewHolder(layoutInflater.inflate(R.layout.item_clipboard_entry, p, false))

                    override fun getItemCount() = slot.items.size

                    override fun onBindViewHolder(h: SlotItemViewHolder, itemPos: Int) {
                        val item = slot.items.getOrNull(itemPos) ?: return
                        val v = h.itemView
                        val txtOp = v.findViewById<TextView>(R.id.txtOperation)
                        val txtName = v.findViewById<TextView>(R.id.txtFileName)
                        val btnRemove = v.findViewById<ImageView>(R.id.btnRemoveClipboard)

                        val isMove = item.operation == FileClipboard.Operation.MOVE
                        txtOp.text = when (item.operation) {
                            FileClipboard.Operation.MOVE -> "CUT"
                            FileClipboard.Operation.COPY -> "COPY"
                            FileClipboard.Operation.EXTRACT -> "EXTRACT"
                        }
                        (txtOp.background as? GradientDrawable)?.setColor(
                            if (isMove) colorCut else colorCopy
                        )

                        val prefix = if (item is FileClipboard.ClipItem.Remote) "[Remote] " else ""
                        txtName.text = "$prefix${item.name}"

                        btnRemove.setOnClickListener {
                            FileClipboard.removeItem(slot.id, item)
                            updateUI()
                            notifyDataSetChanged()
                            viewPager.adapter?.notifyDataSetChanged()
                        }
                    }
                }
            }
        }

        viewPager.adapter = pagerAdapter

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            val slot = FileClipboard.slots.getOrNull(position)
            tab.text = slot?.let { "${it.label} (${it.totalCount})" } ?: "Slot ${position + 1}"
        }.attach()

        updateUI()

        btnPasteHere.setOnClickListener {
            dialog.dismiss()
            performPasteSlots()
        }

        btnPasteSlot?.setOnClickListener {
            val currentPos = viewPager.currentItem
            val slot = FileClipboard.slots.getOrNull(currentPos)
            if (slot != null) {
                dialog.dismiss()
                performPasteSlots(targetSlotId = slot.id)
            }
        }

        btnRemoveSlot?.setOnClickListener {
            val currentPos = viewPager.currentItem
            val slot = FileClipboard.slots.getOrNull(currentPos)
            if (slot != null) {
                FileClipboard.removeSlot(slot.id)
                updateUI()
                pagerAdapter.notifyDataSetChanged()
            }
        }

        btnClearAll.setOnClickListener {
            FileClipboard.clear()
            updatePasteFab()
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun performPasteSlots(targetSlotId: Long? = null) {
        val activeTab = getActiveTab() ?: return
        val activeFrag = getActiveFragment() ?: return
        val targetSlots = if (targetSlotId != null) FileClipboard.slots.filter { it.id == targetSlotId } else FileClipboard.slots.toList()
        if (targetSlots.isEmpty()) return

        val totalItems = targetSlots.sumOf { it.items.size }
        if (totalItems == 0) return

        var fileCounter = 0
        var isCancelled = false
        var currentTransferConnection: AutoCloseable? = null
        val applyToAllRef = booleanArrayOf(false)
        var globalAction: TransferConflictHelper.ConflictAction? = null

        val anyMove = targetSlots.any { slot -> slot.items.any { it.operation == FileClipboard.Operation.MOVE } }

        val dialogView = layoutInflater.inflate(R.layout.dialog_transfer_progress, null)
        val txtTitle = dialogView.findViewById<TextView>(R.id.txtProgressTitle)
        val txtFiles = dialogView.findViewById<TextView>(R.id.txtProgressFiles)
        val txtCurrentFile = dialogView.findViewById<TextView>(R.id.txtProgressCurrentFile)
        val txtSize = dialogView.findViewById<TextView>(R.id.txtProgressSize)
        val progressFile = dialogView.findViewById<com.google.android.material.progressindicator.LinearProgressIndicator>(R.id.progressFile)

        txtTitle.text = if (anyMove) getString(R.string.moving_files) else getString(R.string.copying_files_1)
        txtFiles.text = getString(R.string.item_0_totalfiles, totalItems)

        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .setCancelable(false)
            .setNegativeButton(R.string.cancel) { _, _ ->
                isCancelled = true
                runCatching { currentTransferConnection?.close() }
                currentTransferConnection = null
            }
            .create()

        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val onProgress: (String, Long, Long, Int, Int) -> Unit = { fileName, copied, total, index, totalCount ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                runCatching {
                    txtFiles.text = getString(R.string.item_index_totalcount, fileCounter, totalItems)
                    txtCurrentFile.text = fileName
                    if (total > 0) {
                        val percent = ((copied * 100L) / total).toInt().coerceIn(0, 100)
                        progressFile.isIndeterminate = false
                        progressFile.progress = percent
                        val copiedMb = copied / (1024 * 1024)
                        val totalMb = total / (1024 * 1024)
                        txtSize.text = getString(R.string.copiedmb_mb_totalmb_mb_percent, copiedMb.toString(), totalMb.toString(), percent)
                    } else {
                        progressFile.isIndeterminate = true
                        txtSize.setText(R.string.processing)
                    }
                }
            }
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val targetIsLocal = activeTab.storageType == StorageType.LOCAL || activeTab.storageType == StorageType.SAF
                if (targetIsLocal) {
                    val destPath = activeTab.currentPath
                    val isDestSaf = activeTab.storageType == StorageType.SAF ||
                        SafTreeManager.isSafPath(destPath) ||
                        SafTreeManager.hasTreePermissionForPath(this@TabbedBrowserActivity, destPath)

                    suspend fun processLocalTarget(item: FileClipboard.ClipItem, currentDestPath: String) {
                        if (isCancelled) throw kotlinx.coroutines.CancellationException()
                        val itemName = item.name
                        val isMove = item.operation == FileClipboard.Operation.MOVE

                        if (item is FileClipboard.ClipItem.Local) {
                            val actualItem = item.file
                            val isSrcSaf = actualItem is SafFile ||
                                SafTreeManager.isSafPath(actualItem.absolutePath) ||
                                SafTreeManager.hasTreePermissionForPath(this@TabbedBrowserActivity, actualItem.absolutePath)

                            val destBase = if (isDestSaf) {
                                SafFile(currentDestPath, itemName, actualItem.isDirectory)
                            } else {
                                File(currentDestPath, itemName)
                            }

                            if (actualItem.isDirectory) {
                                val hasConflict = TransferConflictHelper.localFileExists(File(currentDestPath), itemName, this@TabbedBrowserActivity)
                                var effectiveDest = destBase
                                if (hasConflict) {
                                    val resolvedAction = globalAction ?: withContext(Dispatchers.Main) {
                                        TransferConflictHelper.showConflictDialog(this@TabbedBrowserActivity, itemName, true, -1L, applyToAllRef).also {
                                            if (applyToAllRef[0]) globalAction = it
                                        }
                                    }
                                    when (resolvedAction) {
                                        TransferConflictHelper.ConflictAction.CANCEL -> {
                                            isCancelled = true
                                            throw kotlinx.coroutines.CancellationException()
                                        }
                                        TransferConflictHelper.ConflictAction.SKIP -> return
                                        TransferConflictHelper.ConflictAction.KEEP_BOTH -> {
                                            effectiveDest = TransferConflictHelper.uniqueLocalFolder(File(currentDestPath), itemName, this@TabbedBrowserActivity)
                                        }
                                        TransferConflictHelper.ConflictAction.OVERWRITE -> {
                                            effectiveDest = destBase
                                        }
                                    }
                                }

                                if (isDestSaf) {
                                    if (!SafTreeManager.exists(this@TabbedBrowserActivity, effectiveDest.absolutePath)) {
                                        SafTreeManager.mkdir(this@TabbedBrowserActivity, currentDestPath, effectiveDest.name)
                                    }
                                } else {
                                    effectiveDest.mkdirs()
                                }

                                val children = if (isSrcSaf) {
                                    SafTreeManager.listFiles(this@TabbedBrowserActivity, actualItem.absolutePath)
                                } else {
                                    actualItem.listFiles()?.toList()
                                }
                                if (children != null) {
                                    for (child in children) {
                                        if (isCancelled) break
                                        coroutineContext.ensureActive()
                                        val childClip = FileClipboard.ClipItem.Local(child, item.operation)
                                        processLocalTarget(childClip, effectiveDest.absolutePath)
                                    }
                                }
                                if (isMove && !isCancelled) {
                                    if (isSrcSaf) {
                                        SafTreeManager.deleteRecursively(this@TabbedBrowserActivity, actualItem.absolutePath)
                                    } else {
                                        actualItem.deleteRecursively()
                                    }
                                }
                            } else {
                                val hasConflict = TransferConflictHelper.localFileExists(File(currentDestPath), itemName, this@TabbedBrowserActivity)
                                val resolvedAction = if (hasConflict) {
                                    val destSize = TransferConflictHelper.localFileSize(File(currentDestPath), itemName, this@TabbedBrowserActivity)
                                    globalAction ?: withContext(Dispatchers.Main) {
                                        TransferConflictHelper.showConflictDialog(this@TabbedBrowserActivity, itemName, false, destSize, applyToAllRef).also {
                                            if (applyToAllRef[0]) globalAction = it
                                        }
                                    }
                                } else TransferConflictHelper.ConflictAction.KEEP_BOTH

                                if (resolvedAction == TransferConflictHelper.ConflictAction.CANCEL) {
                                    isCancelled = true
                                    throw kotlinx.coroutines.CancellationException()
                                }
                                if (resolvedAction == TransferConflictHelper.ConflictAction.SKIP) return

                                val finalDest = if (resolvedAction == TransferConflictHelper.ConflictAction.KEEP_BOTH)
                                    TransferConflictHelper.uniqueLocalFile(File(currentDestPath), itemName, this@TabbedBrowserActivity)
                                else destBase

                                fileCounter++
                                TransferConflictHelper.copyLocalToLocalAtomic(actualItem, finalDest, resolvedAction) { c, t ->
                                    onProgress(itemName, c, t, fileCounter, totalItems)
                                }
                                if (isMove) {
                                    if (isSrcSaf) {
                                        SafTreeManager.delete(this@TabbedBrowserActivity, actualItem.absolutePath)
                                    } else {
                                        actualItem.delete()
                                    }
                                }
                            }
                        } else if (item is FileClipboard.ClipItem.Remote) {
                            val actualItem = item.file
                            val srcShare = resolveShare(item.sourceShareId, item.sourceRemotePath, actualItem.path) ?: return

                            val destBase = if (isDestSaf) {
                                SafFile(currentDestPath, itemName, actualItem.isDirectory)
                            } else {
                                File(currentDestPath, itemName)
                            }

                            if (actualItem.isDirectory) {
                                val hasConflict = TransferConflictHelper.localFileExists(File(currentDestPath), itemName, this@TabbedBrowserActivity)
                                var effectiveDest = destBase
                                if (hasConflict) {
                                    val resolvedAction = globalAction ?: withContext(Dispatchers.Main) {
                                        TransferConflictHelper.showConflictDialog(this@TabbedBrowserActivity, itemName, true, -1L, applyToAllRef).also {
                                            if (applyToAllRef[0]) globalAction = it
                                        }
                                    }
                                    when (resolvedAction) {
                                        TransferConflictHelper.ConflictAction.CANCEL -> {
                                            isCancelled = true
                                            throw kotlinx.coroutines.CancellationException()
                                        }
                                        TransferConflictHelper.ConflictAction.SKIP -> return
                                        TransferConflictHelper.ConflictAction.KEEP_BOTH -> {
                                            effectiveDest = TransferConflictHelper.uniqueLocalFolder(File(currentDestPath), itemName, this@TabbedBrowserActivity)
                                        }
                                        TransferConflictHelper.ConflictAction.OVERWRITE -> {
                                            effectiveDest = destBase
                                        }
                                    }
                                }

                                if (isDestSaf) {
                                    if (!SafTreeManager.exists(this@TabbedBrowserActivity, effectiveDest.absolutePath)) {
                                        SafTreeManager.mkdir(this@TabbedBrowserActivity, currentDestPath, effectiveDest.name)
                                    }
                                } else {
                                    effectiveDest.mkdirs()
                                }

                                val children = listRemoteFiles(srcShare, actualItem.path)
                                for (child in children) {
                                    if (isCancelled) break
                                    coroutineContext.ensureActive()
                                    val childClip = FileClipboard.ClipItem.Remote(child, item.operation, item.sourceShareId, item.sourceRemotePath)
                                    processLocalTarget(childClip, effectiveDest.absolutePath)
                                }
                                if (isMove && !isCancelled) {
                                    deleteRemoteFile(srcShare, actualItem.path, isDir = true)
                                }
                            } else {
                                val hasConflict = TransferConflictHelper.localFileExists(File(currentDestPath), itemName, this@TabbedBrowserActivity)
                                val resolvedAction = if (hasConflict) {
                                    val destSize = TransferConflictHelper.localFileSize(File(currentDestPath), itemName, this@TabbedBrowserActivity)
                                    globalAction ?: withContext(Dispatchers.Main) {
                                        TransferConflictHelper.showConflictDialog(this@TabbedBrowserActivity, itemName, false, destSize, applyToAllRef).also {
                                            if (applyToAllRef[0]) globalAction = it
                                        }
                                    }
                                } else TransferConflictHelper.ConflictAction.OVERWRITE

                                if (resolvedAction == TransferConflictHelper.ConflictAction.CANCEL) {
                                    isCancelled = true
                                    throw kotlinx.coroutines.CancellationException()
                                }
                                if (resolvedAction == TransferConflictHelper.ConflictAction.SKIP) return

                                val finalDest = if (resolvedAction == TransferConflictHelper.ConflictAction.KEEP_BOTH)
                                    TransferConflictHelper.uniqueLocalFile(File(currentDestPath), itemName, this@TabbedBrowserActivity)
                                else destBase

                                fileCounter++
                                TransferConflictHelper.downloadNetworkToLocalAtomic(
                                    srcShare, actualItem, finalDest, resolvedAction,
                                    onProgress = { c, t -> onProgress(itemName, c, t, fileCounter, totalItems) },
                                    onConnectionReady = { conn -> currentTransferConnection = conn }
                                )
                                currentTransferConnection = null

                                if (isMove) {
                                    deleteRemoteFile(srcShare, actualItem.path, isDir = false)
                                }
                            }
                        }
                    }

                    for (slot in targetSlots) {
                        for (item in slot.items) {
                            coroutineContext.ensureActive()
                            processLocalTarget(item, destPath)
                        }
                        FileClipboard.removeSlot(slot.id)
                    }
                } else {
                    // Target is Network / Cloud
                    val dstShare = resolveShare(activeTab.shareId, activeTab.currentPath) ?: return@launch
                    val rawDst = activeTab.currentPath
                    val dstPath = if (dstShare.type == ShareType.TV) rawDst else rawDst.removePrefix(dstShare.docIdPrefix).removePrefix("/")

                    suspend fun processNetTarget(item: FileClipboard.ClipItem, currentDestPath: String) {
                        if (isCancelled) throw kotlinx.coroutines.CancellationException()
                        val itemName = item.name
                        val isMove = item.operation == FileClipboard.Operation.MOVE
                        val targetPath = if (currentDestPath.isEmpty() || currentDestPath == "/") itemName else "${currentDestPath.trimEnd('/')}/$itemName"

                        if (item is FileClipboard.ClipItem.Local) {
                            val actualItem = item.file
                            val isSrcSaf = actualItem is SafFile ||
                                SafTreeManager.isSafPath(actualItem.absolutePath) ||
                                SafTreeManager.hasTreePermissionForPath(this@TabbedBrowserActivity, actualItem.absolutePath)

                            if (actualItem.isDirectory) {
                                val conflictData = try {
                                    val list = listRemoteFiles(dstShare, currentDestPath)
                                    Pair(TransferConflictHelper.networkFileExists(itemName, list), list)
                                } catch (_: Exception) { Pair(false, emptyList<NetworkFile>()) }

                                val hasConflict = conflictData.first
                                val destChildren = conflictData.second

                                var effectiveDest = targetPath
                                if (hasConflict) {
                                    val resolvedAction = globalAction ?: withContext(Dispatchers.Main) {
                                        TransferConflictHelper.showConflictDialog(this@TabbedBrowserActivity, itemName, true, -1L, applyToAllRef).also {
                                            if (applyToAllRef[0]) globalAction = it
                                        }
                                    }
                                    when (resolvedAction) {
                                        TransferConflictHelper.ConflictAction.CANCEL -> {
                                            isCancelled = true
                                            throw kotlinx.coroutines.CancellationException()
                                        }
                                        TransferConflictHelper.ConflictAction.SKIP -> return
                                        TransferConflictHelper.ConflictAction.KEEP_BOTH -> {
                                            effectiveDest = TransferConflictHelper.uniqueNetworkPath(currentDestPath, itemName, destChildren, isFolder = true)
                                        }
                                        TransferConflictHelper.ConflictAction.OVERWRITE -> {
                                            effectiveDest = targetPath
                                        }
                                    }
                                }

                                try {
                                    makeRemoteDir(dstShare, effectiveDest)
                                } catch (e: Exception) {
                                    za.kilowatch.ultimatefilemanager.util.GoRoLog.e("TabbedBrowser", "mkdir failed for $effectiveDest: ${e.message}", e)
                                }

                                val children = if (isSrcSaf) {
                                    SafTreeManager.listFiles(this@TabbedBrowserActivity, actualItem.absolutePath)
                                } else {
                                    actualItem.listFiles()?.toList()
                                }
                                if (children != null) {
                                    for (child in children) {
                                        if (isCancelled) break
                                        coroutineContext.ensureActive()
                                        val childClip = FileClipboard.ClipItem.Local(child, item.operation)
                                        processNetTarget(childClip, effectiveDest)
                                    }
                                }
                                if (isMove && !isCancelled) {
                                    if (isSrcSaf) {
                                        SafTreeManager.deleteRecursively(this@TabbedBrowserActivity, actualItem.absolutePath)
                                    } else {
                                        actualItem.deleteRecursively()
                                    }
                                }
                            } else {
                                val conflictData = try {
                                    val list = listRemoteFiles(dstShare, currentDestPath)
                                    Pair(TransferConflictHelper.networkFileExists(itemName, list), list)
                                } catch (_: Exception) { Pair(false, emptyList<NetworkFile>()) }

                                val hasConflict = conflictData.first
                                val destChildren = conflictData.second

                                val resolvedAction = if (hasConflict) {
                                    globalAction ?: withContext(Dispatchers.Main) {
                                        TransferConflictHelper.showConflictDialog(this@TabbedBrowserActivity, itemName, false, -1L, applyToAllRef).also {
                                            if (applyToAllRef[0]) globalAction = it
                                        }
                                    }
                                } else TransferConflictHelper.ConflictAction.KEEP_BOTH

                                if (resolvedAction == TransferConflictHelper.ConflictAction.CANCEL) {
                                    isCancelled = true
                                    throw kotlinx.coroutines.CancellationException()
                                }
                                if (resolvedAction == TransferConflictHelper.ConflictAction.SKIP) return

                                val finalPath = if (resolvedAction == TransferConflictHelper.ConflictAction.KEEP_BOTH)
                                    TransferConflictHelper.uniqueNetworkPath(currentDestPath, itemName, destChildren)
                                else targetPath

                                fileCounter++
                                TransferConflictHelper.uploadLocalToNetworkAtomic(
                                    actualItem, dstShare, finalPath,
                                    onProgress = { c, t -> onProgress(itemName, c, t, fileCounter, totalItems) },
                                    onConnectionReady = { conn -> currentTransferConnection = conn }
                                )
                                currentTransferConnection = null

                                if (isMove) {
                                    if (isSrcSaf) {
                                        SafTreeManager.delete(this@TabbedBrowserActivity, actualItem.absolutePath)
                                    } else {
                                        actualItem.delete()
                                    }
                                }
                            }
                        } else if (item is FileClipboard.ClipItem.Remote) {
                            val actualItem = item.file
                            val srcShare = resolveShare(item.sourceShareId, item.sourceRemotePath, actualItem.path) ?: return

                            if (actualItem.isDirectory) {
                                val conflictData = try {
                                    val list = listRemoteFiles(dstShare, currentDestPath)
                                    Pair(TransferConflictHelper.networkFileExists(itemName, list), list)
                                } catch (_: Exception) { Pair(false, emptyList<NetworkFile>()) }

                                val hasConflict = conflictData.first
                                val destChildren = conflictData.second

                                var effectiveDest = targetPath
                                if (hasConflict) {
                                    val resolvedAction = globalAction ?: withContext(Dispatchers.Main) {
                                        TransferConflictHelper.showConflictDialog(this@TabbedBrowserActivity, itemName, true, -1L, applyToAllRef).also {
                                            if (applyToAllRef[0]) globalAction = it
                                        }
                                    }
                                    when (resolvedAction) {
                                        TransferConflictHelper.ConflictAction.CANCEL -> {
                                            isCancelled = true
                                            throw kotlinx.coroutines.CancellationException()
                                        }
                                        TransferConflictHelper.ConflictAction.SKIP -> return
                                        TransferConflictHelper.ConflictAction.KEEP_BOTH -> {
                                            effectiveDest = TransferConflictHelper.uniqueNetworkPath(currentDestPath, itemName, destChildren, isFolder = true)
                                        }
                                        TransferConflictHelper.ConflictAction.OVERWRITE -> {
                                            effectiveDest = targetPath
                                        }
                                    }
                                }

                                try {
                                    makeRemoteDir(dstShare, effectiveDest)
                                } catch (e: Exception) {
                                    za.kilowatch.ultimatefilemanager.util.GoRoLog.e("TabbedBrowser", "mkdir failed for $effectiveDest: ${e.message}", e)
                                }

                                val children = listRemoteFiles(srcShare, actualItem.path)
                                for (child in children) {
                                    if (isCancelled) break
                                    coroutineContext.ensureActive()
                                    val childClip = FileClipboard.ClipItem.Remote(child, item.operation, item.sourceShareId, item.sourceRemotePath)
                                    processNetTarget(childClip, effectiveDest)
                                }
                                if (isMove && !isCancelled) {
                                    deleteRemoteFile(srcShare, actualItem.path, isDir = true)
                                }
                            } else {
                                val conflictData = try {
                                    val list = listRemoteFiles(dstShare, currentDestPath)
                                    Pair(TransferConflictHelper.networkFileExists(itemName, list), list)
                                } catch (_: Exception) { Pair(false, emptyList<NetworkFile>()) }

                                val hasConflict = conflictData.first
                                val destChildren = conflictData.second

                                val resolvedAction = if (hasConflict) {
                                    globalAction ?: withContext(Dispatchers.Main) {
                                        TransferConflictHelper.showConflictDialog(this@TabbedBrowserActivity, itemName, false, -1L, applyToAllRef).also {
                                            if (applyToAllRef[0]) globalAction = it
                                        }
                                    }
                                } else TransferConflictHelper.ConflictAction.KEEP_BOTH

                                if (resolvedAction == TransferConflictHelper.ConflictAction.CANCEL) {
                                    isCancelled = true
                                    throw kotlinx.coroutines.CancellationException()
                                }
                                if (resolvedAction == TransferConflictHelper.ConflictAction.SKIP) return

                                val finalPath = if (resolvedAction == TransferConflictHelper.ConflictAction.KEEP_BOTH)
                                    TransferConflictHelper.uniqueNetworkPath(currentDestPath, itemName, destChildren)
                                else targetPath

                                fileCounter++
                                val useTmp = dstShare.type != ShareType.AWS_S3 && dstShare.type != ShareType.IDRIVE_E2 && dstShare.type != ShareType.WEBDAV && dstShare.type != ShareType.NFS
                                val tmpPath = if (useTmp) "$finalPath.ufm_tmp" else finalPath

                                onProgress(itemName, 0, actualItem.size, fileCounter, totalItems)
                                TransferConflictHelper.copyNetworkFileToNetwork(
                                    srcShare, actualItem, dstShare, tmpPath, onProgress, fileCounter, totalItems,
                                    onConnectionReady = { conn -> currentTransferConnection = conn }
                                )
                                currentTransferConnection = null

                                if (resolvedAction == TransferConflictHelper.ConflictAction.OVERWRITE && useTmp) {
                                    try { deleteRemoteFile(dstShare, finalPath, false) } catch (_: Exception) {}
                                }

                                if (useTmp) {
                                    renameRemoteFile(dstShare, tmpPath, finalPath, false)
                                }

                                if (isMove) {
                                    deleteRemoteFile(srcShare, actualItem.path, false)
                                }
                            }
                        }
                    }

                    for (slot in targetSlots) {
                        for (item in slot.items) {
                            coroutineContext.ensureActive()
                            processNetTarget(item, dstPath)
                        }
                        FileClipboard.removeSlot(slot.id)
                    }
                }
            } catch (e: Exception) {
                if (isCancelled || e is kotlinx.coroutines.CancellationException) {
                    // user cancelled
                } else {
                    za.kilowatch.ultimatefilemanager.util.GoRoLog.e("TabbedBrowser", "Paste failed: ${e.message}", e)
                }
            } finally {
                withContext(Dispatchers.Main) {
                    runCatching { dialog.dismiss() }
                    updatePasteFab()
                    if (activeFrag is FileBrowserFragment) {
                        activeFrag.refresh()
                    } else if (activeFrag is NetworkBrowserFragment) {
                        activeFrag.loadDirectory()
                    }
                    if (!isCancelled) {
                        Toast.makeText(this@TabbedBrowserActivity, getString(R.string.transfer_complete), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    // ── FileOperationsListener Implementation ───────────────────────────
    override fun onCopyRequested(fragment: FileBrowserFragment, files: List<File>) {
        if (files.isEmpty()) return
        handleCopyOrCut(files, isMove = false)
    }

    override fun onMoveRequested(fragment: FileBrowserFragment, files: List<File>) {
        if (files.isEmpty()) return
        handleCopyOrCut(files, isMove = true)
    }

    override fun onDeleteRequested(fragment: FileBrowserFragment, files: List<File>) {
        if (files.isEmpty()) return
        val msg = if (files.size == 1) {
            getString(R.string.delete_confirm_single, files.first().name)
        } else {
            getString(R.string.delete_confirm_multiple, files.size)
        }
        MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setTitle(R.string.delete_title)
            .setMessage(msg)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    for (f in files) {
                        if (f.isDirectory) f.deleteRecursively() else f.delete()
                    }
                    withContext(Dispatchers.Main) {
                        fragment.refresh()
                        Snackbar.make(findViewById(R.id.main), getString(R.string.delete_success, files.size), Snackbar.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onRenameRequested(fragment: FileBrowserFragment, file: File?) {
        // Handled internally in FileBrowserFragment
    }

    override fun onPasteRequested(fragment: FileBrowserFragment, destination: File) {
        showClipboardSheet()
    }

    // ── NetworkOperationsListener Implementation ────────────────────────
    override fun onNetworkCopyRequested(fragment: NetworkBrowserFragment, files: List<NetworkFile>) {
        if (files.isEmpty()) return
        handleNetworkCopyOrCut(files, isMove = false, fragment.getShare())
    }

    override fun onNetworkMoveRequested(fragment: NetworkBrowserFragment, files: List<NetworkFile>) {
        if (files.isEmpty()) return
        handleNetworkCopyOrCut(files, isMove = true, fragment.getShare())
    }

    override fun onNetworkDeleteRequested(fragment: NetworkBrowserFragment, files: List<NetworkFile>) {
        if (files.isEmpty()) return
        val share = fragment.getShare()
        val msg = if (files.size == 1) {
            getString(R.string.delete_confirm_single, files.first().name)
        } else {
            getString(R.string.delete_confirm_multiple, files.size)
        }
        MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setTitle(R.string.delete_title)
            .setMessage(msg)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    for (f in files) {
                        deleteRemoteFile(share, f.path, f.isDirectory)
                    }
                    withContext(Dispatchers.Main) {
                        fragment.loadDirectory()
                        Snackbar.make(findViewById(R.id.main), getString(R.string.delete_success, files.size), Snackbar.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onNetworkPasteRequested(fragment: NetworkBrowserFragment, destinationPath: String) {
        showClipboardSheet()
    }

    private fun showOptionsMenu(anchor: View) {
        val popupView = layoutInflater.inflate(R.layout.popup_header_options_menu, null)
        val popupWidth = (215 * resources.displayMetrics.density).toInt()
        val popupWindow = PopupWindow(
            popupView,
            popupWidth,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            elevation = 16f * resources.displayMetrics.density
            isOutsideTouchable = true
            isFocusable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            animationStyle = android.R.style.Animation_Dialog
        }

        popupView.findViewById<View>(R.id.menuItemNewTab)?.setOnClickListener {
            popupWindow.dismiss()
            showNewTabDialog()
        }

        val itemRenameTab = popupView.findViewById<View>(R.id.menuItemRenameTab)
        itemRenameTab?.visibility = if (getActiveTab() != null) View.VISIBLE else View.GONE
        itemRenameTab?.setOnClickListener {
            popupWindow.dismiss()
            val activeTab = getActiveTab() ?: return@setOnClickListener
            val pos = tabs.indexOf(activeTab)
            if (pos >= 0) {
                tabAdapter.startEditingTab(pos)
            }
        }

        val itemCloseAllTabs = popupView.findViewById<View>(R.id.menuItemCloseAllTabs)
        itemCloseAllTabs?.visibility = if (tabs.isNotEmpty()) View.VISIBLE else View.GONE
        itemCloseAllTabs?.setOnClickListener {
            popupWindow.dismiss()
            closeAllTabs()
        }

        popupView.findViewById<View>(R.id.menuItemTwinWindow)?.setOnClickListener {
            popupWindow.dismiss()
            btnTwinWindow.performClick()
        }

        popupView.findViewById<View>(R.id.menuItemCopyFolderPath)?.setOnClickListener {
            popupWindow.dismiss()
            copyActiveFolderPath()
        }

        popupView.findViewById<View>(R.id.menuItemSettings)?.setOnClickListener {
            popupWindow.dismiss()
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        val xOffset = -(popupWidth - anchor.width)
        popupWindow.showAsDropDown(anchor, xOffset, (4 * resources.displayMetrics.density).toInt())
    }

    private fun closeAllTabs() {
        TabSessionManager.clearSession(this)
        tabs.clear()
        tabAdapter.notifyDataSetChanged()
        tabPagerAdapter.notifyDataSetChanged()
        val intent = Intent(this, StorageBrowserActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)
        finish()
    }

    private fun copyActiveFolderPath() {
        val activeTab = getActiveTab() ?: return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard != null) {
            val clip = ClipData.newPlainText("Folder Path", activeTab.currentPath)
            clipboard.setPrimaryClip(clip)
            Snackbar.make(findViewById(R.id.main), getString(R.string.folder_path_copied), Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun validateAndPruneStorageTabs() {
        val (valid, closed) = TabSessionManager.validateAndPrune(this, tabs)
        if (closed.isNotEmpty()) {
            tabs.clear()
            tabs.addAll(valid)
            tabAdapter.notifyDataSetChanged()
            tabPagerAdapter.notifyDataSetChanged()

            if (!tabs.any { it.id == activeTabId }) {
                selectTab(tabs.first())
            }

            val msg = getString(R.string.tab_storage_unavailable_closed, closed.joinToString(", "))
            Snackbar.make(findViewById(R.id.main), msg, Snackbar.LENGTH_LONG).show()
            TabSessionManager.saveSession(this, tabs, activeTabId ?: "")
        }
    }

    private fun registerMediaReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addDataScheme("file")
        }
        storageReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                validateAndPruneStorageTabs()
            }
        }
        registerReceiver(storageReceiver, filter)
    }

    private fun getActiveTab(): TabModel? = tabs.firstOrNull { it.id == activeTabId }

    private fun getActiveFragment(): Fragment? {
        val pos = tabs.indexOfFirst { it.id == activeTabId }
        if (pos < 0) return null
        val itemId = tabPagerAdapter.getItemId(pos)
        return supportFragmentManager.findFragmentByTag("f$itemId")
    }

    // ── ViewPager2 FragmentStateAdapter ─────────────────────────────────
    private inner class TabPagerAdapter(activity: AppCompatActivity) : FragmentStateAdapter(activity) {

        override fun getItemCount(): Int = tabs.size

        override fun getItemId(position: Int): Long = tabs[position].id.hashCode().toLong()

        override fun containsItem(itemId: Long): Boolean = tabs.any { it.id.hashCode().toLong() == itemId }

        override fun createFragment(position: Int): Fragment {
            val tab = tabs[position]
            return when (tab.storageType) {
                StorageType.LOCAL, StorageType.SAF -> {
                    FileBrowserFragment.newInstance(
                        mountPath = tab.rootPath,
                        label = tab.storageLabel,
                        isTwinWindow = false,
                        initialPath = tab.currentPath
                    ).apply {
                        onDirectoryChanged = { file ->
                            onTabDirectoryChanged(tab, file.absolutePath)
                        }
                    }
                }
                StorageType.NETWORK, StorageType.CLOUD -> {
                    NetworkBrowserFragment.newInstance(
                        shareId = tab.shareId ?: "",
                        initialPath = tab.currentPath,
                        isTwinWindow = false
                    ).apply {
                        onDirectoryChanged = { path ->
                            onTabDirectoryChanged(tab, path)
                        }
                    }
                }
            }
        }
    }
}
