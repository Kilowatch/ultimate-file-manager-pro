package za.kilowatch.ultimatefilemanager.bookmarks.ui

import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.bookmarks.db.BookmarkContentType
import za.kilowatch.ultimatefilemanager.bookmarks.db.BookmarkEntity
import za.kilowatch.ultimatefilemanager.bookmarks.db.BookmarkGroupEntity
import za.kilowatch.ultimatefilemanager.bookmarks.db.BookmarkRepository
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import za.kilowatch.ultimatefilemanager.settings.ThemeHelper
import za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class BookmarkHubActivity : AppCompatActivity() {

    private lateinit var btnBack: ImageView
    private lateinit var txtToolbarSubtitle: TextView
    private lateinit var btnSearch: ImageView
    private lateinit var btnOptionsToggle: ImageView

    private lateinit var layoutSearchBar: FrameLayout
    private lateinit var editSearchQuery: EditText
    private lateinit var tabAll: LinearLayout
    private lateinit var txtTabAll: TextView
    private lateinit var indicatorTabAll: View
    private lateinit var tabStarred: LinearLayout
    private lateinit var txtTabStarred: TextView
    private lateinit var indicatorTabStarred: View
    private lateinit var btnViewAllGroups: TextView
    private lateinit var chipGroupGroups: ChipGroup
    private lateinit var recyclerBookmarks: RecyclerView
    private lateinit var layoutEmpty: LinearLayout
    private lateinit var txtEmptyTitle: TextView
    private lateinit var txtEmptyDesc: TextView
    private lateinit var btnDeleteEmptyGroup: MaterialButton

    private lateinit var layoutSelectionBar: LinearLayout
    private lateinit var txtSelectedCount: TextView
    private lateinit var btnBatchMove: View
    private lateinit var btnBatchExport: View
    private lateinit var btnBatchDelete: View
    private lateinit var btnCloseSelection: ImageView

    private lateinit var adapter: BookmarkAdapter
    private var allBookmarks: List<BookmarkEntity> = emptyList()
    private var allGroups: List<BookmarkGroupEntity> = emptyList()

    private var selectedGroupId: String? = null
    private var selectedContentType: String? = null
    private var isFilterStarred: Boolean = false
    private var searchQuery: String = ""
    private var currentSortMode = SORT_NEWEST
    private var isGrid = false

    private var recentlyDeletedItem: BookmarkEntity? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContentView(R.layout.activity_bookmark_hub)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        isGrid = prefs.getBoolean(KEY_IS_GRID, false)
        currentSortMode = prefs.getInt(KEY_SORT_MODE, SORT_NEWEST)

        setupViews()
        setupRecyclerView()
        observeData()
    }

    private fun setupViews() {
        btnBack = findViewById(R.id.btnBack)
        txtToolbarSubtitle = findViewById(R.id.txtToolbarSubtitle)
        btnSearch = findViewById(R.id.btnSearch)
        btnOptionsToggle = findViewById(R.id.btnOptionsToggle)

        layoutSearchBar = findViewById(R.id.layoutSearchBar)
        editSearchQuery = findViewById(R.id.editSearchQuery)
        tabAll = findViewById(R.id.tabAll)
        txtTabAll = findViewById(R.id.txtTabAll)
        indicatorTabAll = findViewById(R.id.indicatorTabAll)
        tabStarred = findViewById(R.id.tabStarred)
        txtTabStarred = findViewById(R.id.txtTabStarred)
        indicatorTabStarred = findViewById(R.id.indicatorTabStarred)
        btnViewAllGroups = findViewById(R.id.btnViewAllGroups)
        chipGroupGroups = findViewById(R.id.chipGroupGroups)

        tabAll.setOnClickListener { selectTab(starred = false) }
        tabStarred.setOnClickListener { selectTab(starred = true) }
        btnViewAllGroups.setOnClickListener { showCollectionHubDialog() }
        recyclerBookmarks = findViewById(R.id.recyclerBookmarks)
        layoutEmpty = findViewById(R.id.layoutEmpty)
        txtEmptyTitle = findViewById(R.id.txtEmptyTitle)
        txtEmptyDesc = findViewById(R.id.txtEmptyDesc)
        btnDeleteEmptyGroup = findViewById(R.id.btnDeleteEmptyGroup)

        layoutSelectionBar = findViewById(R.id.layoutSelectionBar)
        txtSelectedCount = findViewById(R.id.txtSelectedCount)
        btnBatchMove = findViewById(R.id.btnBatchMove)
        btnBatchExport = findViewById(R.id.btnBatchExport)
        btnBatchDelete = findViewById(R.id.btnBatchDelete)
        btnCloseSelection = findViewById(R.id.btnCloseSelection)

        btnBack.setOnClickListener { finish() }

        btnSearch.setOnClickListener { toggleSearchBar() }

        btnOptionsToggle.setOnClickListener { anchor ->
            showBookmarkHubOptionsMenu(anchor)
        }

        editSearchQuery.doAfterTextChanged { text ->
            searchQuery = text?.toString()?.trim().orEmpty()
            applyFilterAndSort()
        }

        btnDeleteEmptyGroup.setOnClickListener {
            val group = allGroups.find { it.id == selectedGroupId }
            if (group != null) {
                confirmDeleteEmptyGroup(group)
            }
        }

        btnCloseSelection.setOnClickListener { exitSelectionMode() }
        btnBatchDelete.setOnClickListener { performBatchDelete() }
        btnBatchMove.setOnClickListener { performBatchMove() }
        btnBatchExport.setOnClickListener {
            val selectedItems = allBookmarks.filter { adapter.selectedIds.contains(it.id) }
            showExportDialog(selectedItems)
        }
    }

    private fun toggleSearchBar() {
        val isVisible = layoutSearchBar.visibility == View.VISIBLE
        layoutSearchBar.visibility = if (isVisible) View.GONE else View.VISIBLE
        if (isVisible) {
            editSearchQuery.setText("")
            searchQuery = ""
            applyFilterAndSort()
        } else {
            editSearchQuery.requestFocus()
        }
    }

    private fun setupRecyclerView() {
        adapter = BookmarkAdapter(
            context = this,
            isGrid = isGrid,
            onItemClick = { item -> handleItemClick(item) },
            onItemLongClick = { _ -> updateSelectionBar() },
            onStarToggle = { item -> toggleStar(item) },
            onActionReadArticle = { item -> openReaderMode(item) },
            onActionOpenBrowser = { item -> openInBrowser(item) },
            onActionCopy = { item -> copyBookmarkContent(item) },
            onActionSaveStorage = { item -> saveFileToDownloads(item) },
            onActionMoveGroup = { item -> showMoveGroupDialog(listOf(item)) },
            onActionViewNote = { item -> showNoteDialog(item) },
            onActionDelete = { item -> deleteSingleItemWithUndo(item) }
        )
        applyLayoutManager()
        recyclerBookmarks.adapter = adapter
    }

    private fun applyLayoutManager() {
        if (isGrid) {
            val gridLayout = GridLayoutManager(this, 2)
            gridLayout.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    return if (adapter.getItemViewType(position) == BookmarkAdapter.VIEW_TYPE_HEADER) 2 else 1
                }
            }
            recyclerBookmarks.layoutManager = gridLayout
        } else {
            recyclerBookmarks.layoutManager = LinearLayoutManager(this)
        }
    }

    private fun toggleViewMode() {
        isGrid = !isGrid
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_IS_GRID, isGrid)
            .apply()
        adapter.isGrid = isGrid
        applyLayoutManager()
        recyclerBookmarks.adapter = adapter
    }

    private fun showBookmarkHubOptionsMenu(anchor: View) {
        val popupView = layoutInflater.inflate(R.layout.popup_bookmark_hub_options, null)
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

        val imgViewMode = popupView.findViewById<ImageView>(R.id.imgViewModeIcon)
        val txtViewMode = popupView.findViewById<TextView>(R.id.txtViewModeLabel)
        if (isGrid) {
            imgViewMode.setImageResource(R.drawable.ic_view_list)
            txtViewMode.setText(R.string.layout_list)
        } else {
            imgViewMode.setImageResource(R.drawable.ic_view_grid_medium)
            txtViewMode.setText(R.string.layout_grid)
        }

        popupView.findViewById<View>(R.id.menuItemSort)?.setOnClickListener {
            popupWindow.dismiss()
            showSortDialog()
        }

        popupView.findViewById<View>(R.id.menuItemViewMode)?.setOnClickListener {
            popupWindow.dismiss()
            toggleViewMode()
        }

        popupView.findViewById<View>(R.id.menuItemExport)?.setOnClickListener {
            popupWindow.dismiss()
            showExportDialog(allBookmarks)
        }

        popupView.findViewById<View>(R.id.menuItemSearch)?.setOnClickListener {
            popupWindow.dismiss()
            toggleSearchBar()
        }

        val xOffset = -(popupWidth - anchor.width)
        popupWindow.showAsDropDown(anchor, xOffset, (4 * resources.displayMetrics.density).toInt())
    }

    private fun selectTab(starred: Boolean) {
        if (isFilterStarred == starred) return
        isFilterStarred = starred
        val typedValue = android.util.TypedValue()
        theme.resolveAttribute(androidx.appcompat.R.attr.colorPrimary, typedValue, true)
        val primaryColor = if (typedValue.data != 0) typedValue.data else Color.parseColor("#3B82F6")
        val textPrimary = ContextCompat.getColor(this, R.color.mobile_text_primary)
        val textSecondary = ContextCompat.getColor(this, R.color.mobile_text_secondary)

        if (starred) {
            txtTabStarred.setTextColor(textPrimary)
            txtTabStarred.typeface = Typeface.DEFAULT_BOLD
            indicatorTabStarred.setBackgroundColor(primaryColor)
            indicatorTabStarred.visibility = View.VISIBLE

            txtTabAll.setTextColor(textSecondary)
            txtTabAll.typeface = Typeface.DEFAULT
            indicatorTabAll.visibility = View.INVISIBLE
        } else {
            txtTabAll.setTextColor(textPrimary)
            txtTabAll.typeface = Typeface.DEFAULT_BOLD
            indicatorTabAll.setBackgroundColor(primaryColor)
            indicatorTabAll.visibility = View.VISIBLE

            txtTabStarred.setTextColor(textSecondary)
            txtTabStarred.typeface = Typeface.DEFAULT
            indicatorTabStarred.visibility = View.INVISIBLE
        }
        applyFilterAndSort()
    }

    private fun observeData() {
        val repo = BookmarkRepository.getInstance(this)

        lifecycleScope.launch {
            repo.getAllGroups().collectLatest { groups ->
                allGroups = groups
                if (selectedGroupId != null && groups.none { it.id == selectedGroupId }) {
                    selectedGroupId = null
                }
                populateGroupChips(groups)
                updateToolbarSubtitle()
            }
        }

        lifecycleScope.launch {
            repo.getAllBookmarks().collectLatest { bookmarks ->
                allBookmarks = bookmarks
                applyFilterAndSort()
                updateToolbarSubtitle()
            }
        }
    }

    private fun isDeviceSecurityAvailable(): Boolean {
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val isDeviceSecure = keyguardManager?.isDeviceSecure == true
        val biometricManager = androidx.biometric.BiometricManager.from(this)
        val authenticators = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG or
            androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            @Suppress("DEPRECATION")
            androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
        }
        val canAuth = biometricManager.canAuthenticate(authenticators)
        val isBiometricAvailable = (canAuth == androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS)

        return isDeviceSecure || isBiometricAvailable
    }

    private fun populateGroupChips(groups: List<BookmarkGroupEntity>) {
        chipGroupGroups.removeAllViews()

        // 1. "+ New Group" Pill (Distinct emerald styling)
        val addPill = TextView(this).apply {
            text = getString(R.string.bookmark_new_group)
            background = ContextCompat.getDrawable(this@BookmarkHubActivity, R.drawable.bg_bookmark_pill_add)
            setTextColor(Color.parseColor("#34D399"))
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = android.view.Gravity.CENTER
            val pxPad = (14 * resources.displayMetrics.density).toInt()
            setPadding(pxPad, 0, pxPad, 0)
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                (38 * resources.displayMetrics.density).toInt()
            ).apply {
                marginEnd = (8 * resources.displayMetrics.density).toInt()
                bottomMargin = (8 * resources.displayMetrics.density).toInt()
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                showCreateNewGroupDialog()
            }
        }
        chipGroupGroups.addView(addPill)

        // 2. Select up to 4 prominent groups (ensuring selectedGroupId is visible if chosen)
        val displayedGroups = mutableListOf<BookmarkGroupEntity>()
        val activeGroup = groups.find { it.id == selectedGroupId }
        if (activeGroup != null) {
            displayedGroups.add(activeGroup)
        }
        for (g in groups) {
            if (displayedGroups.size >= 4) break
            if (!displayedGroups.any { it.id == g.id }) {
                displayedGroups.add(g)
            }
        }

        for (group in displayedGroups) {
            val isManual = !group.isAutoGroup
            val isSelected = (selectedGroupId == group.id)

            val pill = TextView(this).apply {
                text = when {
                    group.isLocked -> "🔒 ${group.name}"
                    isManual -> "📁 ${group.name}"
                    else -> group.name
                }

                val bgRes = when {
                    isManual && isSelected -> R.drawable.bg_bookmark_pill_manual_active
                    isManual -> R.drawable.bg_bookmark_pill_manual_inactive
                    isSelected -> R.drawable.bg_bookmark_pill_auto_active
                    else -> R.drawable.bg_bookmark_pill_auto_inactive
                }
                background = ContextCompat.getDrawable(this@BookmarkHubActivity, bgRes)

                val textColor = when {
                    isSelected -> Color.WHITE
                    isManual -> Color.parseColor("#C7D2FE")
                    else -> Color.parseColor("#94A3B8")
                }
                setTextColor(textColor)
                textSize = 13.5f
                typeface = if (isSelected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                gravity = android.view.Gravity.CENTER
                val pxPad = (14 * resources.displayMetrics.density).toInt()
                setPadding(pxPad, 0, pxPad, 0)
                layoutParams = ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    (38 * resources.displayMetrics.density).toInt()
                ).apply {
                    marginEnd = (8 * resources.displayMetrics.density).toInt()
                    bottomMargin = (8 * resources.displayMetrics.density).toInt()
                }

                isClickable = true
                isFocusable = true

                setOnClickListener {
                    if (isSelected) {
                        selectedGroupId = null
                        applyFilterAndSort()
                        populateGroupChips(allGroups)
                    } else if (group.isLocked) {
                        promptVaultUnlock {
                            selectedGroupId = group.id
                            applyFilterAndSort()
                            populateGroupChips(allGroups)
                        }
                    } else {
                        selectedGroupId = group.id
                        applyFilterAndSort()
                        populateGroupChips(allGroups)
                    }
                }

                setOnLongClickListener {
                    if (isManual) {
                        showGroupOptionsDialog(group)
                        true
                    } else {
                        false
                    }
                }
            }
            chipGroupGroups.addView(pill)
        }

        // 3. "+ X" Pill when more groups exist (matching a1.jpeg where + 56 is shown)
        if (groups.size > displayedGroups.size) {
            val remainingCount = groups.size - displayedGroups.size
            val morePill = TextView(this).apply {
                text = getString(R.string.bookmark_more_groups_format, remainingCount)
                background = ContextCompat.getDrawable(this@BookmarkHubActivity, R.drawable.bg_bookmark_pill_auto_inactive)
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 13.5f
                typeface = Typeface.DEFAULT_BOLD
                gravity = android.view.Gravity.CENTER
                val pxPad = (14 * resources.displayMetrics.density).toInt()
                setPadding(pxPad, 0, pxPad, 0)
                layoutParams = ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    (38 * resources.displayMetrics.density).toInt()
                ).apply {
                    marginEnd = (8 * resources.displayMetrics.density).toInt()
                    bottomMargin = (8 * resources.displayMetrics.density).toInt()
                }
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    showCollectionHubDialog()
                }
            }
            chipGroupGroups.addView(morePill)
        }
    }

    private fun showCollectionHubDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_bookmark_collection_hub, null)
        val editSearchCollections = dialogView.findViewById<EditText>(R.id.editSearchCollections)
        val txtHubSubtitle = dialogView.findViewById<TextView>(R.id.txtHubSubtitle)
        val cardAllBookmarks = dialogView.findViewById<View>(R.id.cardAllBookmarks)
        val txtAllBookmarksCount = dialogView.findViewById<TextView>(R.id.txtAllBookmarksCount)
        val layoutManualCollections = dialogView.findViewById<LinearLayout>(R.id.layoutManualCollections)
        val layoutAutoCollections = dialogView.findViewById<LinearLayout>(R.id.layoutAutoCollections)
        val headerManualSection = dialogView.findViewById<View>(R.id.headerManualSection)
        val headerAutoSection = dialogView.findViewById<View>(R.id.headerAutoSection)
        val btnCreateNewGroupInHub = dialogView.findViewById<MaterialButton>(R.id.btnCreateNewGroupInHub)
        val btnCloseHub = dialogView.findViewById<MaterialButton>(R.id.btnCloseHub)

        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val lockedGroupIds = allGroups.filter { it.isLocked }.map { it.id }.toSet()
        val unlockedCount = allBookmarks.count { it.groupId !in lockedGroupIds }
        txtHubSubtitle.text = getString(R.string.bookmark_hub_subtitle_count, unlockedCount, allGroups.size)
        txtAllBookmarksCount.text = "$unlockedCount"

        cardAllBookmarks.setOnClickListener {
            selectedGroupId = null
            dialog.dismiss()
            applyFilterAndSort()
            populateGroupChips(allGroups)
        }

        btnCreateNewGroupInHub.setOnClickListener {
            dialog.dismiss()
            showCreateNewGroupDialog()
        }

        btnCloseHub.setOnClickListener { dialog.dismiss() }

        fun populateHubList(query: String) {
            layoutManualCollections.removeAllViews()
            layoutAutoCollections.removeAllViews()

            val manualGroups = allGroups.filter { !it.isAutoGroup && (query.isEmpty() || it.name.contains(query, ignoreCase = true)) }
            val autoGroups = allGroups.filter { it.isAutoGroup && (query.isEmpty() || it.name.contains(query, ignoreCase = true)) }

            headerManualSection.visibility = if (manualGroups.isNotEmpty()) View.VISIBLE else View.GONE
            headerAutoSection.visibility = if (autoGroups.isNotEmpty()) View.VISIBLE else View.GONE

            for (group in manualGroups) {
                val row = LayoutInflater.from(this).inflate(R.layout.item_bookmark_hub_collection_row, layoutManualCollections, false)
                val txtName = row.findViewById<TextView>(R.id.txtGroupName)
                val txtStatus = row.findViewById<TextView>(R.id.txtGroupStatus)
                val txtCount = row.findViewById<TextView>(R.id.txtGroupCount)
                val btnOptions = row.findViewById<ImageView>(R.id.btnRowOptions)
                val imgIcon = row.findViewById<ImageView>(R.id.imgGroupIcon)

                txtName.text = group.name
                if (group.isLocked) {
                    txtStatus.text = "🔒 " + getString(R.string.bookmark_locked_badge)
                    txtStatus.setTextColor(Color.parseColor("#FFA726"))
                    imgIcon.setImageResource(R.drawable.ic_lock)
                    imgIcon.setColorFilter(Color.parseColor("#FFA726"))
                } else {
                    txtStatus.text = getString(R.string.bookmark_section_manual_collections)
                    txtStatus.setTextColor(ContextCompat.getColor(this, R.color.mobile_text_secondary))
                    imgIcon.setImageResource(R.drawable.ic_folder)
                }

                val count = allBookmarks.count { it.groupId == group.id }
                txtCount.text = "$count"

                btnOptions.visibility = View.VISIBLE
                btnOptions.setOnClickListener {
                    dialog.dismiss()
                    showGroupOptionsDialog(group)
                }

                row.setOnClickListener {
                    if (group.isLocked) {
                        promptVaultUnlock {
                            selectedGroupId = group.id
                            dialog.dismiss()
                            applyFilterAndSort()
                            populateGroupChips(allGroups)
                        }
                    } else {
                        selectedGroupId = group.id
                        dialog.dismiss()
                        applyFilterAndSort()
                        populateGroupChips(allGroups)
                    }
                }

                row.setOnLongClickListener {
                    dialog.dismiss()
                    showGroupOptionsDialog(group)
                    true
                }

                layoutManualCollections.addView(row)
            }

            for (group in autoGroups) {
                val row = LayoutInflater.from(this).inflate(R.layout.item_bookmark_hub_collection_row, layoutAutoCollections, false)
                val txtName = row.findViewById<TextView>(R.id.txtGroupName)
                val txtStatus = row.findViewById<TextView>(R.id.txtGroupStatus)
                val txtCount = row.findViewById<TextView>(R.id.txtGroupCount)
                val btnOptions = row.findViewById<ImageView>(R.id.btnRowOptions)

                txtName.text = group.name
                txtStatus.text = getString(R.string.bookmark_section_auto_collections)
                val count = allBookmarks.count { it.groupId == group.id }
                txtCount.text = "$count"
                btnOptions.visibility = View.GONE

                row.setOnClickListener {
                    selectedGroupId = group.id
                    dialog.dismiss()
                    applyFilterAndSort()
                    populateGroupChips(allGroups)
                }

                layoutAutoCollections.addView(row)
            }
        }

        populateHubList("")

        editSearchCollections.doAfterTextChanged { text ->
            populateHubList(text?.toString()?.trim().orEmpty())
        }

        dialog.show()
    }

    private fun showGroupOptionsDialog(group: BookmarkGroupEntity) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_bookmark_collection_options, null)
        val txtCollectionTitle = dialogView.findViewById<TextView>(R.id.txtCollectionTitle)
        val txtCollectionBadge = dialogView.findViewById<TextView>(R.id.txtCollectionBadge)
        val optToggleLock = dialogView.findViewById<LinearLayout>(R.id.optToggleLock)
        val imgLockIcon = dialogView.findViewById<ImageView>(R.id.imgLockIcon)
        val txtLockTitle = dialogView.findViewById<TextView>(R.id.txtLockTitle)
        val txtLockDesc = dialogView.findViewById<TextView>(R.id.txtLockDesc)
        val optDeleteCollection = dialogView.findViewById<LinearLayout>(R.id.optDeleteCollection)
        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnCancel)

        txtCollectionTitle.text = group.name
        txtCollectionBadge.text = if (!group.isAutoGroup) {
            getString(R.string.bookmark_group_manual_badge)
        } else {
            getString(R.string.bookmark_group_auto_badge)
        }

        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val isSecure = isDeviceSecurityAvailable()
        if (!isSecure) {
            optToggleLock.isEnabled = false
            optToggleLock.alpha = 0.45f
            txtLockTitle.text = getString(R.string.bookmark_lock_collection)
            txtLockDesc.text = getString(R.string.bookmark_vault_no_credentials)
        } else {
            optToggleLock.isEnabled = true
            optToggleLock.alpha = 1.0f
            if (group.isLocked) {
                txtLockTitle.text = getString(R.string.bookmark_unlock_collection)
                txtLockDesc.text = getString(R.string.bookmark_unlock_group_desc)
                imgLockIcon.setImageResource(R.drawable.ic_lock)
            } else {
                txtLockTitle.text = getString(R.string.bookmark_lock_collection)
                txtLockDesc.text = getString(R.string.bookmark_lock_group_desc)
                imgLockIcon.setImageResource(R.drawable.ic_lock)
            }

            optToggleLock.setOnClickListener {
                dialog.dismiss()
                promptVaultUnlock {
                    lifecycleScope.launch {
                        val updated = group.copy(isLocked = !group.isLocked)
                        BookmarkRepository.getInstance(this@BookmarkHubActivity).updateGroup(updated)
                        Toast.makeText(
                            this@BookmarkHubActivity,
                            if (updated.isLocked) R.string.bookmark_locked_toast else R.string.bookmark_unlocked_toast,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }

        optDeleteCollection.setOnClickListener {
            dialog.dismiss()
            confirmDeleteEmptyGroup(group)
        }

        btnCancel.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun showCreateNewGroupDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_bookmark_create_group, null)
        val edtGroupName = dialogView.findViewById<TextInputEditText>(R.id.edtGroupName)
        val layoutLockToggle = dialogView.findViewById<LinearLayout>(R.id.layoutLockToggle)
        val switchLockGroup = dialogView.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switchLockGroup)
        val txtLockGroupDesc = dialogView.findViewById<TextView>(R.id.txtLockGroupDesc)
        val btnCreate = dialogView.findViewById<MaterialButton>(R.id.btnCreate)
        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnCancel)

        val isSecure = isDeviceSecurityAvailable()
        if (!isSecure) {
            switchLockGroup.isChecked = false
            switchLockGroup.isEnabled = false
            layoutLockToggle?.isEnabled = false
            layoutLockToggle?.alpha = 0.45f
            txtLockGroupDesc?.text = getString(R.string.bookmark_vault_no_credentials)
        } else {
            layoutLockToggle?.setOnClickListener {
                switchLockGroup.isChecked = !switchLockGroup.isChecked
            }
        }

        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        btnCreate.setOnClickListener {
            val name = edtGroupName.text?.toString()?.trim().orEmpty()
            if (name.isNotEmpty()) {
                val isLocked = switchLockGroup.isChecked
                fun saveNewGroup() {
                    lifecycleScope.launch {
                        val newGroup = BookmarkGroupEntity(
                            id = "group_${UUID.randomUUID()}",
                            name = name,
                            iconResName = "ic_folder",
                            colorHex = "#3B82F6",
                            isAutoGroup = false,
                            isLocked = isLocked,
                            sortOrder = 50
                        )
                        BookmarkRepository.getInstance(this@BookmarkHubActivity).insertGroup(newGroup)
                        selectedGroupId = newGroup.id
                        applyFilterAndSort()
                        dialog.dismiss()
                    }
                }

                if (isLocked) {
                    promptVaultUnlock {
                        saveNewGroup()
                    }
                } else {
                    saveNewGroup()
                }
            }
        }

        btnCancel.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun promptVaultUnlock(onSuccess: () -> Unit) {
        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                onSuccess()
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                if (errorCode != BiometricPrompt.ERROR_USER_CANCELED && errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                    Toast.makeText(this@BookmarkHubActivity, errString, Toast.LENGTH_SHORT).show()
                }
            }
        })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.bookmark_vault_prompt))
            .apply {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    setAllowedAuthenticators(
                        androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
                    )
                } else {
                    @Suppress("DEPRECATION")
                    setDeviceCredentialAllowed(true)
                }
            }
            .build()

        prompt.authenticate(promptInfo)
    }

    private fun applyFilterAndSort() {
        var filtered = allBookmarks

        // Filter by Group & Lock Vault Isolation
        val lockedGroupIds = allGroups.filter { it.isLocked }.map { it.id }.toSet()
        if (selectedGroupId != null) {
            filtered = filtered.filter { it.groupId == selectedGroupId }
        } else if (lockedGroupIds.isNotEmpty()) {
            // "All" view: Strictly hide bookmarks belonging to locked collections!
            filtered = filtered.filter { it.groupId !in lockedGroupIds }
        }

        // Filter by Starred Tab
        if (isFilterStarred) {
            filtered = filtered.filter { it.isStarred }
        }

        // Search Query
        if (searchQuery.isNotEmpty()) {
            filtered = filtered.filter {
                it.title.contains(searchQuery, ignoreCase = true) ||
                (it.notes?.contains(searchQuery, ignoreCase = true) == true) ||
                (it.url?.contains(searchQuery, ignoreCase = true) == true) ||
                it.sourceAppName.contains(searchQuery, ignoreCase = true)
            }
        }

        val rowItems = mutableListOf<BookmarkRowItem>()

        if (currentSortMode == SORT_GROUP_AZ || currentSortMode == SORT_GROUP_ZA) {
            val grouped = filtered.groupBy { it.groupName }
            val sortedGroupKeys = if (currentSortMode == SORT_GROUP_ZA) {
                grouped.keys.sortedByDescending { it.lowercase() }
            } else {
                grouped.keys.sortedBy { it.lowercase() }
            }

            for (groupKey in sortedGroupKeys) {
                val itemsInGroup = grouped[groupKey].orEmpty()
                rowItems.add(BookmarkRowItem.Header(groupName = groupKey, count = itemsInGroup.size))
                itemsInGroup.forEach { rowItems.add(BookmarkRowItem.Bookmark(it)) }
            }
        } else {
            val sorted = when (currentSortMode) {
                SORT_NEWEST -> filtered.sortedByDescending { it.updatedAt }
                SORT_OLDEST -> filtered.sortedBy { it.createdAt }
                SORT_TITLE_AZ -> filtered.sortedBy { it.title.lowercase() }
                SORT_TITLE_ZA -> filtered.sortedByDescending { it.title.lowercase() }
                SORT_SIZE -> filtered.sortedByDescending { it.fileSizeBytes }
                else -> filtered.sortedByDescending { it.updatedAt }
            }
            sorted.forEach { rowItems.add(BookmarkRowItem.Bookmark(it)) }
        }

        adapter.submitList(rowItems)

        if (filtered.isEmpty()) {
            layoutEmpty.visibility = View.VISIBLE
            when {
                searchQuery.isNotEmpty() -> {
                    txtEmptyTitle.text = getString(R.string.bookmark_empty_search_title)
                    txtEmptyDesc.text = getString(R.string.bookmark_empty_search_desc, searchQuery)
                }
                isFilterStarred -> {
                    txtEmptyTitle.text = getString(R.string.bookmark_empty_filter_title)
                    txtEmptyDesc.text = getString(R.string.bookmark_empty_filter_desc)
                }
                selectedGroupId != null -> {
                    val group = allGroups.find { it.id == selectedGroupId }
                    txtEmptyTitle.text = group?.name ?: getString(R.string.bookmark_empty_filter_title)
                    txtEmptyDesc.text = getString(R.string.bookmark_empty_filter_desc)
                }
                else -> {
                    txtEmptyTitle.text = getString(R.string.bookmark_empty_title)
                    txtEmptyDesc.text = getString(R.string.bookmark_empty_desc)
                }
            }

            // Show Delete Collection button if an empty custom group is selected
            if (selectedGroupId != null) {
                val group = allGroups.find { it.id == selectedGroupId }
                if (group != null && !group.isAutoGroup) {
                    btnDeleteEmptyGroup.visibility = View.VISIBLE
                } else {
                    btnDeleteEmptyGroup.visibility = View.GONE
                }
            } else {
                btnDeleteEmptyGroup.visibility = View.GONE
            }
        } else {
            layoutEmpty.visibility = View.GONE
            btnDeleteEmptyGroup.visibility = View.GONE
        }
    }

    private fun confirmDeleteEmptyGroup(group: BookmarkGroupEntity) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_bookmark_delete_confirm, null)
        val txtTitle = dialogView.findViewById<TextView>(R.id.txtDeleteTitle)
        val txtMsg = dialogView.findViewById<TextView>(R.id.txtDeleteMessage)
        val btnDelete = dialogView.findViewById<MaterialButton>(R.id.btnConfirmDelete)
        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnCancel)

        txtTitle.text = getString(R.string.bookmark_delete_collection)
        txtMsg.text = getString(R.string.bookmark_delete_collection_msg, group.name)

        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        btnDelete.setOnClickListener {
            lifecycleScope.launch {
                BookmarkRepository.getInstance(this@BookmarkHubActivity).deleteGroup(group)
                selectedGroupId = null
                applyFilterAndSort()
                dialog.dismiss()
            }
        }

        btnCancel.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun updateToolbarSubtitle() {
        val lockedGroupIds = allGroups.filter { it.isLocked }.map { it.id }.toSet()
        val visibleBookmarks = if (selectedGroupId != null) {
            allBookmarks.filter { it.groupId == selectedGroupId }
        } else {
            allBookmarks.filter { it.groupId !in lockedGroupIds }
        }
        val count = visibleBookmarks.size
        val groupCount = if (selectedGroupId != null) 1 else allGroups.count { !it.isLocked }
        txtToolbarSubtitle.text = if (count > 0) {
            getString(R.string.bookmark_hub_subtitle_count, count, groupCount)
        } else {
            getString(R.string.bookmark_hub_subtitle_empty)
        }
    }

    private fun handleItemClick(item: BookmarkEntity) {
        // 1. Article Link -> Open Reader Mode if readable text cached, else open browser
        if (item.contentType == BookmarkContentType.LINK) {
            if (!item.cachedArticleText.isNullOrBlank()) {
                openReaderMode(item)
            } else if (!item.url.isNullOrBlank()) {
                openInBrowser(item)
            }
            return
        }

        // 2. Physical cloned file
        val path = item.filePath
        if (!path.isNullOrBlank() && File(path).exists()) {
            FileViewerRouter.openFile(this, File(path))
            return
        }

        // 3. Plain Text / Note
        if (item.contentType == BookmarkContentType.TEXT_NOTE) {
            showNoteDialog(item)
        }
    }

    private fun openReaderMode(item: BookmarkEntity) {
        val intent = Intent(this, BookmarkReaderActivity::class.java).apply {
            putExtra(BookmarkReaderActivity.EXTRA_BOOKMARK_ID, item.id)
            putExtra(BookmarkReaderActivity.EXTRA_URL, item.url)
            putExtra(BookmarkReaderActivity.EXTRA_TITLE, item.title)
            putExtra(BookmarkReaderActivity.EXTRA_ARTICLE_TEXT, item.cachedArticleText)
        }
        startActivity(intent)
    }

    private fun openInBrowser(item: BookmarkEntity) {
        val url = item.url ?: return
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    private fun copyBookmarkContent(item: BookmarkEntity) {
        val textToCopy = item.url ?: item.notes ?: item.title
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("bookmark", textToCopy))
        val msgRes = if (!item.url.isNullOrBlank()) R.string.bookmark_action_copy_link else R.string.bookmark_action_copy_text
        Toast.makeText(this, msgRes, Toast.LENGTH_SHORT).show()
    }

    private fun showNoteDialog(item: BookmarkEntity) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_bookmark_note_view, null)
        val txtNoteTitle = dialogView.findViewById<TextView>(R.id.txtNoteTitle)
        val txtNoteBody = dialogView.findViewById<TextView>(R.id.txtNoteBody)
        val btnCopyText = dialogView.findViewById<MaterialButton>(R.id.btnCopyText)
        val btnClose = dialogView.findViewById<MaterialButton>(R.id.btnClose)

        txtNoteTitle.text = item.title
        txtNoteBody.text = item.notes.orEmpty()

        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        btnCopyText.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("note", item.notes ?: ""))
            Toast.makeText(this, R.string.bookmark_action_copy_text, Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        btnClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun saveFileToDownloads(item: BookmarkEntity) {
        val sourcePath = item.filePath
        if (sourcePath.isNullOrBlank() || !File(sourcePath).exists()) {
            Toast.makeText(this, "No local file to save", Toast.LENGTH_SHORT).show()
            return
        }
        val sourceFile = File(sourcePath)

        lifecycleScope.launch(Dispatchers.IO) {
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val destFile = File(downloadDir, sourceFile.name.substringAfter('_'))
            sourceFile.copyTo(destFile, overwrite = true)
            withContext(Dispatchers.Main) {
                Toast.makeText(this@BookmarkHubActivity, getString(R.string.bookmark_export_success, 1, destFile.absolutePath), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun deleteSingleItemWithUndo(item: BookmarkEntity) {
        recentlyDeletedItem = item
        val repo = BookmarkRepository.getInstance(this)

        lifecycleScope.launch {
            repo.deleteBookmark(item, purgeFiles = false) // Soft-delete from DB first

            Snackbar.make(findViewById(R.id.main), R.string.bookmark_deleted_undo, 5000)
                .setAction(R.string.bookmark_undo) {
                    lifecycleScope.launch {
                        repo.saveBookmark(item)
                    }
                }
                .addCallback(object : Snackbar.Callback() {
                    override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                        if (event != DISMISS_EVENT_ACTION) {
                            // Purge private physical files upon snackbar timeout
                            lifecycleScope.launch(Dispatchers.IO) {
                                item.filePath?.let { runCatching { File(it).delete() } }
                                item.thumbnailPath?.let { runCatching { File(it).delete() } }
                                item.faviconPath?.let { runCatching { File(it).delete() } }
                                repo.cleanupEmptyAutoGroups()
                            }
                        }
                    }
                })
                .show()
        }
    }

    private fun showMoveGroupDialog(items: List<BookmarkEntity>) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_bookmark_move_group, null)
        val layoutGroupItems = dialogView.findViewById<LinearLayout>(R.id.layoutGroupItems)
        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnCancel)

        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        for (group in allGroups) {
            val itemView = LayoutInflater.from(this).inflate(R.layout.item_dropdown_popup, layoutGroupItems, false)
            (itemView as? TextView)?.apply {
                text = group.name
                setPadding(40, 32, 40, 32)
                textSize = 15f
                setOnClickListener {
                    lifecycleScope.launch(Dispatchers.IO) {
                        val repo = BookmarkRepository.getInstance(this@BookmarkHubActivity)
                        for (item in items) {
                            repo.updateBookmark(item.copy(groupId = group.id, groupName = group.name))
                        }
                        repo.cleanupEmptyAutoGroups()
                    }
                    exitSelectionMode()
                    dialog.dismiss()
                }
            }
            layoutGroupItems.addView(itemView)
        }

        btnCancel.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun toggleStar(item: BookmarkEntity) {
        lifecycleScope.launch(Dispatchers.IO) {
            val updated = item.copy(isStarred = !item.isStarred)
            BookmarkRepository.getInstance(this@BookmarkHubActivity).updateBookmark(updated)
        }
    }

    private fun showSortDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_bookmark_sort, null)
        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val imgCheckNewest = dialogView.findViewById<ImageView>(R.id.imgCheckNewest)
        val imgCheckOldest = dialogView.findViewById<ImageView>(R.id.imgCheckOldest)
        val imgCheckTitleAz = dialogView.findViewById<ImageView>(R.id.imgCheckTitleAz)
        val imgCheckTitleZa = dialogView.findViewById<ImageView>(R.id.imgCheckTitleZa)
        val imgCheckSize = dialogView.findViewById<ImageView>(R.id.imgCheckSize)
        val imgCheckGroup = dialogView.findViewById<ImageView>(R.id.imgCheckGroup)
        val layoutGroupOrder = dialogView.findViewById<View>(R.id.layoutGroupOrder)
        val imgCheckGroupAz = dialogView.findViewById<ImageView>(R.id.imgCheckGroupAz)
        val imgCheckGroupZa = dialogView.findViewById<ImageView>(R.id.imgCheckGroupZa)

        val isGroupSort = currentSortMode == SORT_GROUP_AZ || currentSortMode == SORT_GROUP_ZA
        layoutGroupOrder.visibility = if (isGroupSort) View.VISIBLE else View.GONE
        imgCheckGroup.visibility = if (isGroupSort) View.VISIBLE else View.GONE
        imgCheckGroupAz.visibility = if (currentSortMode == SORT_GROUP_AZ) View.VISIBLE else View.GONE
        imgCheckGroupZa.visibility = if (currentSortMode == SORT_GROUP_ZA) View.VISIBLE else View.GONE

        imgCheckNewest.visibility = if (currentSortMode == SORT_NEWEST) View.VISIBLE else View.GONE
        imgCheckOldest.visibility = if (currentSortMode == SORT_OLDEST) View.VISIBLE else View.GONE
        imgCheckTitleAz.visibility = if (currentSortMode == SORT_TITLE_AZ) View.VISIBLE else View.GONE
        imgCheckTitleZa.visibility = if (currentSortMode == SORT_TITLE_ZA) View.VISIBLE else View.GONE
        imgCheckSize.visibility = if (currentSortMode == SORT_SIZE) View.VISIBLE else View.GONE

        fun saveSortMode(mode: Int) {
            currentSortMode = mode
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_SORT_MODE, currentSortMode)
                .apply()
            applyFilterAndSort()
        }

        dialogView.findViewById<View>(R.id.optSortNewest).setOnClickListener {
            saveSortMode(SORT_NEWEST)
            dialog.dismiss()
        }
        dialogView.findViewById<View>(R.id.optSortOldest).setOnClickListener {
            saveSortMode(SORT_OLDEST)
            dialog.dismiss()
        }
        dialogView.findViewById<View>(R.id.optSortTitleAz).setOnClickListener {
            saveSortMode(SORT_TITLE_AZ)
            dialog.dismiss()
        }
        dialogView.findViewById<View>(R.id.optSortTitleZa).setOnClickListener {
            saveSortMode(SORT_TITLE_ZA)
            dialog.dismiss()
        }
        dialogView.findViewById<View>(R.id.optSortSize).setOnClickListener {
            saveSortMode(SORT_SIZE)
            dialog.dismiss()
        }

        dialogView.findViewById<View>(R.id.optSortGroup).setOnClickListener {
            if (layoutGroupOrder.visibility == View.VISIBLE) {
                layoutGroupOrder.visibility = View.GONE
                imgCheckGroup.visibility = View.GONE
            } else {
                layoutGroupOrder.visibility = View.VISIBLE
                imgCheckGroup.visibility = View.VISIBLE
                if (currentSortMode != SORT_GROUP_AZ && currentSortMode != SORT_GROUP_ZA) {
                    saveSortMode(SORT_GROUP_AZ)
                    imgCheckGroupAz.visibility = View.VISIBLE
                    imgCheckGroupZa.visibility = View.GONE
                    imgCheckNewest.visibility = View.GONE
                    imgCheckOldest.visibility = View.GONE
                    imgCheckTitleAz.visibility = View.GONE
                    imgCheckTitleZa.visibility = View.GONE
                    imgCheckSize.visibility = View.GONE
                }
            }
        }

        dialogView.findViewById<View>(R.id.optGroupAz).setOnClickListener {
            saveSortMode(SORT_GROUP_AZ)
            dialog.dismiss()
        }

        dialogView.findViewById<View>(R.id.optGroupZa).setOnClickListener {
            saveSortMode(SORT_GROUP_ZA)
            dialog.dismiss()
        }

        dialogView.findViewById<View>(R.id.btnCancel).setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun updateSelectionBar() {
        val count = adapter.selectedIds.size
        if (count > 0) {
            layoutSelectionBar.visibility = View.VISIBLE
            txtSelectedCount.text = getString(R.string.bookmark_batch_actions, count)
        } else {
            layoutSelectionBar.visibility = View.GONE
            adapter.isSelectionMode = false
        }
    }

    private fun exitSelectionMode() {
        adapter.selectedIds.clear()
        adapter.isSelectionMode = false
        adapter.notifyDataSetChanged()
        layoutSelectionBar.visibility = View.GONE
    }

    private fun performBatchDelete() {
        val itemsToDelete = allBookmarks.filter { adapter.selectedIds.contains(it.id) }
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_bookmark_delete_confirm, null)
        val txtDeleteMessage = dialogView.findViewById<TextView>(R.id.txtDeleteMessage)
        val btnConfirmDelete = dialogView.findViewById<MaterialButton>(R.id.btnConfirmDelete)
        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnCancel)

        txtDeleteMessage.text = getString(R.string.bookmark_batch_actions, itemsToDelete.size)

        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        btnConfirmDelete.setOnClickListener {
            lifecycleScope.launch {
                val repo = BookmarkRepository.getInstance(this@BookmarkHubActivity)
                repo.deleteBookmarks(itemsToDelete, purgeFiles = true)
                repo.cleanupEmptyAutoGroups()
                exitSelectionMode()
                dialog.dismiss()
            }
        }

        btnCancel.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun performBatchMove() {
        val itemsToMove = allBookmarks.filter { adapter.selectedIds.contains(it.id) }
        showMoveGroupDialog(itemsToMove)
    }

    private fun showExportDialog(items: List<BookmarkEntity>) {
        val lockedGroupIds = allGroups.filter { it.isLocked }.map { it.id }.toSet()
        val exportableItems = if (selectedGroupId != null) {
            items
        } else {
            items.filter { it.groupId !in lockedGroupIds }
        }

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_bookmark_export, null)
        val optExportHtml = dialogView.findViewById<View>(R.id.optExportHtml)
        val optExportMarkdown = dialogView.findViewById<View>(R.id.optExportMarkdown)
        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnCancel)

        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        optExportHtml.setOnClickListener {
            exportToHtml(exportableItems)
            exitSelectionMode()
            dialog.dismiss()
        }

        optExportMarkdown.setOnClickListener {
            exportToMarkdown(exportableItems)
            exitSelectionMode()
            dialog.dismiss()
        }

        btnCancel.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun exportToHtml(items: List<BookmarkEntity>) {
        lifecycleScope.launch(Dispatchers.IO) {
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val file = File(downloadDir, "ufm_bookmarks_${System.currentTimeMillis()}.html")
            val sb = StringBuilder()
            sb.append("<!DOCTYPE NETSCAPE-Bookmark-file-1>\n")
            sb.append("<META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-8\">\n")
            sb.append("<TITLE>Bookmarks</TITLE>\n<H1>Bookmarks</H1>\n<DL><p>\n")
            for (item in items) {
                val url = item.url ?: item.filePath ?: ""
                sb.append("    <DT><A HREF=\"$url\" ADD_DATE=\"${item.createdAt / 1000}\">${item.title}</A>\n")
                if (!item.notes.isNullOrBlank()) {
                    sb.append("    <DD>${item.notes}</DD>\n")
                }
            }
            sb.append("</DL><p>\n")
            FileOutputStream(file).use { it.write(sb.toString().toByteArray()) }

            withContext(Dispatchers.Main) {
                Toast.makeText(this@BookmarkHubActivity, getString(R.string.bookmark_export_success, items.size, file.name), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun exportToMarkdown(items: List<BookmarkEntity>) {
        lifecycleScope.launch(Dispatchers.IO) {
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val file = File(downloadDir, "ufm_bookmarks_${System.currentTimeMillis()}.md")
            val sb = StringBuilder()
            sb.append("# Ultimate File Manager Bookmarks\n\n")
            for (item in items) {
                sb.append("### [${item.title}](${item.url ?: item.filePath ?: ""})\n")
                sb.append("- **Collection:** ${item.groupName}\n")
                if (!item.notes.isNullOrBlank()) sb.append("- **Notes:** ${item.notes}\n")
                sb.append("\n")
            }
            FileOutputStream(file).use { it.write(sb.toString().toByteArray()) }

            withContext(Dispatchers.Main) {
                Toast.makeText(this@BookmarkHubActivity, getString(R.string.bookmark_export_success, items.size, file.name), Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        private const val PREFS_NAME = "ufm_bookmarks_prefs"
        private const val KEY_IS_GRID = "is_grid"
        private const val KEY_SORT_MODE = "sort_mode"

        const val SORT_NEWEST = 0
        const val SORT_OLDEST = 1
        const val SORT_TITLE_AZ = 2
        const val SORT_TITLE_ZA = 3
        const val SORT_SIZE = 4
        const val SORT_GROUP_AZ = 5
        const val SORT_GROUP_ZA = 6
    }
}
