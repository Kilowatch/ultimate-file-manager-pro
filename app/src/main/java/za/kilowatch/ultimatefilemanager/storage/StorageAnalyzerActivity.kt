package za.kilowatch.ultimatefilemanager.storage

import za.kilowatch.ultimatefilemanager.util.safeDirectoryPath

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.storage.StorageManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.indexing.IndexingRepository
import za.kilowatch.ultimatefilemanager.settings.FontSizeHelper
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import za.kilowatch.ultimatefilemanager.ui.StorageBarView
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import za.kilowatch.ultimatefilemanager.util.GoRoLog
import java.io.File
import android.os.StatFs
import android.text.format.Formatter
import android.widget.ProgressBar
import android.widget.Spinner

/**
 * StorageAnalyzerActivity — reworked.
 *
 * Architecture:
 *  - Single storage selection (no "All Drives")
 *  - ⚡ spinner badge per indexed drive
 *  - 5-tab ViewPager2 (Overview, Large Files, Duplicates, Junk & Old, Suggestions)
 *  - Indexed storage → all tabs via StorageAnalyzerEngine
 *  - Non-indexed → Overview tab only (filesystem walk), others show "not indexed" banner
 *  - Duplicate delete → calls IndexingRepository.deleteFromIndex() per file
 */
class StorageAnalyzerActivity : AppCompatActivity() {
    private val TAG = "StorageAnalyzerActivity"

    // ── View references ─────────────────────────────────────────────────────
    private lateinit var spinnerDrive          : Spinner
    private lateinit var progressScanning      : ProgressBar
    private lateinit var txtNotIndexedBanner   : TextView
    private lateinit var tabLayout             : TabLayout
    private lateinit var viewPager             : ViewPager2
    private lateinit var fabDeleteDuplicates   : ExtendedFloatingActionButton

    private val isTv by lazy { DeviceUtils.isTvDevice(this) }
    internal val viewModel: StorageAnalyzerViewModel by viewModels()
    private val indexingRepo by lazy { IndexingRepository.getInstance(this) }

    // ── Drive entries ────────────────────────────────────────────────────────
    data class DriveEntry(val label: String, val path: File, val storageId: String, val isIndexed: Boolean) {
        override fun toString() = label
    }

    private val driveEntries = mutableListOf<DriveEntry>()
    private var selectedDrive: DriveEntry? = null

    fun getSelectedDriveLabel(): String {
        return selectedDrive?.label?.replace(" ⚡", "") ?: ""
    }

    // ── Tab page adapter ─────────────────────────────────────────────────────
    private lateinit var pagerAdapter: AnalyzerPagerAdapter

    // ── Duplicate adapter reference (for delete FAB) ─────────────────────────
    private var duplicateAdapter: AnalyzerDuplicateAdapter? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(if (isTv) R.layout.activity_storage_analyzer_tv else R.layout.activity_storage_analyzer)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val bars  = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val tvPad = if (isTv) (27 * resources.displayMetrics.density).toInt() else 0
            v.setPadding(bars.left + tvPad, bars.top + tvPad, bars.right + tvPad, bars.bottom + tvPad)
            insets
        }

        // Bind views
        spinnerDrive        = findViewById(R.id.spinnerDrive)
        progressScanning    = findViewById(R.id.progressScanning)
        txtNotIndexedBanner = findViewById(R.id.txtNotIndexedBanner)
        tabLayout           = findViewById(R.id.tabLayout)
        viewPager           = findViewById(R.id.viewPager)
        fabDeleteDuplicates = findViewById(R.id.fabDeleteDuplicates)

        findViewById<View?>(R.id.btnBack)?.setOnClickListener { navigateBack() }

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                navigateBack()
            }
        })

        // Setup tabs
        pagerAdapter = AnalyzerPagerAdapter(this)
        viewPager.adapter = pagerAdapter
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                TAB_OVERVIEW   -> getString(R.string.analyzer_tab_overview)
                TAB_LARGE      -> getString(R.string.analyzer_tab_large)
                TAB_DUPLICATES -> getString(R.string.analyzer_tab_duplicates)
                TAB_JUNK       -> getString(R.string.analyzer_tab_junk)
                TAB_SUGGESTIONS -> getString(R.string.analyzer_tab_suggestions)
                else           -> ""
            }
        }.attach()

        viewPager.registerOnPageChangeCallback(object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                if (position != TAB_DUPLICATES) {
                    fabDeleteDuplicates.visibility = android.view.View.GONE
                } else {
                    val count = duplicateAdapter?.checkedFiles?.size ?: 0
                    fabDeleteDuplicates.visibility = if (count > 0) android.view.View.VISIBLE else android.view.View.GONE
                }
            }
        })

        setupDrivePicker()
        observeViewModel()
        setupDeleteFab()
        setupTvFocus()
    }

    private fun setupTvFocus() {
        if (!isTv) return
        
        val normalColor = ContextCompat.getColor(this, R.color.tv_text_primary)
        val focusedColor = ContextCompat.getColor(this, R.color.tv_button_focused_yellow_text)
        
        // Inject margins and focus listeners into tabs
        tabLayout.post {
            val tabContainer = tabLayout.getChildAt(0) as? ViewGroup ?: return@post
            val marginPx = (12 * resources.displayMetrics.density).toInt()
            
            for (i in 0 until tabContainer.childCount) {
                val tabView = tabContainer.getChildAt(i)
                
                // 1. Margins
                val lp = tabView.layoutParams as? android.widget.LinearLayout.LayoutParams
                if (lp != null) {
                    lp.marginStart = marginPx
                    lp.marginEnd = marginPx
                    tabView.layoutParams = lp
                }
                
                // 2. Focus-based text color
                tabView.setOnFocusChangeListener { v, hasFocus ->
                    val textView = findTextViewInViewGroup(v as ViewGroup)
                    textView?.setTextColor(if (hasFocus) focusedColor else normalColor)
                }
            }
            tabLayout.requestLayout()
        }
    }

    private fun findTextViewInViewGroup(vg: ViewGroup): TextView? {
        for (i in 0 until vg.childCount) {
            val child = vg.getChildAt(i)
            if (child is TextView) return child
            if (child is ViewGroup) {
                val res = findTextViewInViewGroup(child)
                if (res != null) return res
            }
        }
        return null
    }

    /** Safely scroll to a view if ScrollView exists (mobile only), noop on TV with NestedScrollView */
    private fun safeScrollTo(fragmentView: View?, targetView: View?) {
        if (fragmentView == null || targetView == null) return
        val scroll = fragmentView.findViewById<View>(R.id.root_scroll)
        // Only scroll if it's a regular ScrollView (mobile)
        if (scroll is android.widget.ScrollView) {
            scroll.post { scroll.smoothScrollTo(0, targetView.top) }
        }
        // NestedScrollView auto-scrolls focused views, so no manual scroll needed
    }

    private fun navigateBack() {
        if (isTaskRoot) {
            val intent = Intent(this, za.kilowatch.ultimatefilemanager.storage.StorageBrowserActivity::class.java)
            startActivity(intent)
        }
        finish()
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (!isTv) return super.dispatchKeyEvent(event)

        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
            val focused = currentFocus ?: return super.dispatchKeyEvent(event)

            // Handle DPAD_LEFT/RIGHT from duplicate items to FAB
            if (viewPager.currentItem == TAB_DUPLICATES && fabDeleteDuplicates.visibility == View.VISIBLE) {
                if (event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) {
                    var cur: android.view.View? = focused
                    var inList = false
                    while (cur != null) {
                        if (cur.id == R.id.recyclerAnalyzerList) { inList = true; break }
                        cur = cur.parent as? android.view.View
                    }
                    if (inList) {
                        fabDeleteDuplicates.requestFocus()
                        return true
                    }
                }
            }

            if (event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN) {

                // If focused is inside the Overview categories, move to next category or next section
                var node: android.view.View? = focused
                var container: android.widget.LinearLayout? = null
                var itemView: android.view.View? = null
                while (node != null) {
                    val parent = node.parent as? android.view.View
                    if (parent != null && parent.id == R.id.layoutOverviewCategories) {
                        container = parent as android.widget.LinearLayout
                        itemView = node
                        break
                    }
                    if (node.id == R.id.layoutOverviewCategories) { container = node as? android.widget.LinearLayout; break }
                    node = parent
                }

                if (container != null && viewPager.currentItem == TAB_OVERVIEW) {
                    var idx = -1
                    if (itemView != null) {
                        for (i in 0 until container.childCount) if (container.getChildAt(i) === itemView) { idx = i; break }
                    } else {
                        for (i in 0 until container.childCount) {
                            val child = container.getChildAt(i)
                            if (child.hasFocus()) { idx = i; itemView = child; break }
                        }
                    }

                    if (idx >= 0) {
                        for (j in idx + 1 until container.childCount) {
                            val next = container.getChildAt(j)
                            if (next.isFocusable) {
                                next.requestFocus()
                                safeScrollTo(container.rootView, next)
                                return true
                            }
                        }

                        // Move to next major card if no more categories
                        val frag = if (::pagerAdapter.isInitialized) pagerAdapter.getFragment(TAB_OVERVIEW) as? OverviewTabFragment else null
                        val fv = frag?.view
                        if (fv != null) {
                            val topCard = fv.findViewById<View>(R.id.cardTopFolders)
                            val appCard = fv.findViewById<View>(R.id.cardAppUsage)
                            if (topCard != null && topCard.visibility == View.VISIBLE) {
                                val folderContainer = fv.findViewById<android.widget.LinearLayout>(R.id.layoutTopFolders)
                                val firstChild = folderContainer?.let { if (it.childCount > 0) it.getChildAt(0) else null }
                                if (firstChild != null) {
                                    if (!firstChild.isFocusable) {
                                        firstChild.isFocusable = true
                                        firstChild.isFocusableInTouchMode = true
                                    }
                                    firstChild.requestFocus()
                                    safeScrollTo(fv, firstChild)
                                    return true
                                } else {
                                    if (!topCard.isFocusable) {
                                        topCard.isFocusable = true
                                        topCard.isFocusableInTouchMode = true
                                    }
                                    topCard.requestFocus()
                                    safeScrollTo(fv, topCard)
                                    return true
                                }
                            }
                            if (appCard != null && appCard.visibility == View.VISIBLE) {
                                val appContainer = fv.findViewById<android.widget.LinearLayout>(R.id.layoutAppUsage)
                                val firstApp = appContainer?.let { if (it.childCount > 0) it.getChildAt(0) else null }
                                if (firstApp != null) {
                                    if (!firstApp.isFocusable) {
                                        firstApp.isFocusable = true
                                        firstApp.isFocusableInTouchMode = true
                                    }
                                    firstApp.requestFocus()
                                    safeScrollTo(fv, firstApp)
                                    return true
                                } else {
                                    if (!appCard.isFocusable) {
                                        appCard.isFocusable = true
                                        appCard.isFocusableInTouchMode = true
                                    }
                                    appCard.requestFocus()
                                    safeScrollTo(fv, appCard)
                                    return true
                                }
                            }
                        }
                    }
                }

                // If focused is inside Top Folders or App Usage lists, handle DPAD_DOWN at boundaries
                if (viewPager.currentItem == TAB_OVERVIEW) {
                    val frag = if (::pagerAdapter.isInitialized) pagerAdapter.getFragment(TAB_OVERVIEW) as? OverviewTabFragment else null
                    val fv = frag?.view
                    if (fv != null) {
                        val folderContainer = fv.findViewById<android.widget.LinearLayout>(R.id.layoutTopFolders)
                        val appContainer = fv.findViewById<android.widget.LinearLayout>(R.id.layoutAppUsage)
                        
                        // Check if focused is inside folders
                        var inFolders = false
                        var folderIdx = -1
                        if (folderContainer != null) {
                            for (i in 0 until folderContainer.childCount) {
                                if (folderContainer.getChildAt(i) === focused || folderContainer.getChildAt(i).hasFocus()) {
                                    inFolders = true
                                    folderIdx = i
                                    break
                                }
                            }
                        }
                        
                        // If in folders, move to next folder or to apps if at last
                        if (inFolders && folderContainer != null && folderIdx >= 0) {
                            if (folderIdx < folderContainer.childCount - 1) {
                                // Move to next folder
                                val nextFolder = folderContainer.getChildAt(folderIdx + 1)
                                if (nextFolder != null && nextFolder.isFocusable) {
                                    nextFolder.requestFocus()
                                    return true
                                }
                            } else if (folderIdx == folderContainer.childCount - 1) {
                                // At last folder, try to move to first app
                                if (appContainer != null && appContainer.childCount > 0) {
                                    val firstApp = appContainer.getChildAt(0)
                                    if (!firstApp.isFocusable) {
                                        firstApp.isFocusable = true
                                        firstApp.isFocusableInTouchMode = true
                                    }
                                    firstApp.requestFocus()
                                    safeScrollTo(fv, firstApp)
                                    return true
                                }
                            }
                        }
                        
                        // Check if focused is inside apps
                        var inApps = false
                        var appIdx = -1
                        if (appContainer != null) {
                            for (i in 0 until appContainer.childCount) {
                                if (appContainer.getChildAt(i) === focused || appContainer.getChildAt(i).hasFocus()) {
                                    inApps = true
                                    appIdx = i
                                    break
                                }
                            }
                        }
                        
                        // If in apps, move to next app if not at last
                        if (inApps && appContainer != null && appIdx >= 0) {
                            if (appIdx < appContainer.childCount - 1) {
                                // Move to next app
                                val nextApp = appContainer.getChildAt(appIdx + 1)
                                if (nextApp != null && nextApp.isFocusable) {
                                    nextApp.requestFocus()
                                    return true
                                }
                            }
                            // If at last app, let system default handle it
                        }
                    }
                }

                // If toolbar area focused -> go into viewPager (back button -> Overview tab)
                var cur: android.view.View? = focused
                var inToolbar = false
                while (cur != null) { if (cur.id == R.id.toolbar) { inToolbar = true; break }; cur = cur.parent as? android.view.View }
                if (inToolbar) {
                    if (focused.id == R.id.btnBack) {
                        viewPager.currentItem = TAB_OVERVIEW
                        tabLayout.getTabAt(TAB_OVERVIEW)?.view?.requestFocus()
                        return true
                    }
                    viewPager.requestFocus()
                    return true
                }


                // Handle DPAD_DOWN from toolbar/tab area to the first item in the list (for all tabs)
                var inBar = false
                var pBar: android.view.View? = focused
                while (pBar != null) {
                    if (pBar.id == R.id.toolbar || pBar.id == R.id.tabLayout || pBar.id == R.id.storageBar || pBar.id == R.id.layoutUsageSummary) {
                        inBar = true
                        break
                    }
                    pBar = pBar.parent as? android.view.View
                }

                if (inBar) {
                    val currentTab = viewPager.currentItem
                    if (currentTab == TAB_OVERVIEW) {
                        // Original Overview logic
                        val frag = if (::pagerAdapter.isInitialized) pagerAdapter.getFragment(TAB_OVERVIEW) as? OverviewTabFragment else null
                        val fv = frag?.view
                        if (fv != null) {
                            val container2 = fv.findViewById<android.widget.LinearLayout>(R.id.layoutOverviewCategories)
                            if (container2 != null) {
                                val otherText = getString(R.string.analyzer_category_other)
                                val fallbackText = getString(R.string.analyzer_category_videos)
                                var target: android.view.View? = null
                                for (i in 0 until container2.childCount) {
                                    val child = container2.getChildAt(i)
                                    val name = child.findViewById<TextView>(R.id.txtCategoryName)?.text?.toString()
                                    if (name == otherText && child.isFocusable) { target = child; break }
                                }
                                if (target == null) {
                                    for (i in 0 until container2.childCount) {
                                        val child = container2.getChildAt(i)
                                        val name = child.findViewById<TextView>(R.id.txtCategoryName)?.text?.toString()
                                        if (name == fallbackText && child.isFocusable) { target = child; break }
                                    }
                                }
                                if (target != null) {
                                    target.requestFocus()
                                    safeScrollTo(fv, target)
                                    return true
                                }
                            }
                        }
                    } else if (currentTab == TAB_LARGE || currentTab == TAB_DUPLICATES || currentTab == TAB_JUNK || currentTab == TAB_SUGGESTIONS) {
                        // Generic list logic: focus the first item in the recycler
                        val frag = if (::pagerAdapter.isInitialized) pagerAdapter.getFragment(currentTab) else null
                        val fv = frag?.view
                        if (fv != null) {
                            val recycler = fv.findViewById<RecyclerView>(R.id.recyclerAnalyzerList)
                            if (recycler != null && recycler.childCount > 0) {
                                // Find first focusable in recycler
                                for (i in 0 until recycler.childCount) {
                                    val child = recycler.getChildAt(i)
                                    if (child != null && child.isFocusable) {
                                        child.requestFocus()
                                        return true
                                    }
                                }
                                // Fallback to recycler itself if it's focusable, or first child
                                recycler.getChildAt(0)?.requestFocus()
                                return true
                            }
                        }
                    }
                }

                // Handle DPAD_DOWN from last item in Duplicates to FAB
                if (viewPager.currentItem == TAB_DUPLICATES && fabDeleteDuplicates.visibility == View.VISIBLE) {
                    var cur: android.view.View? = focused
                    var recycler: RecyclerView? = null
                    while (cur != null) {
                        if (cur is RecyclerView && cur.id == R.id.recyclerAnalyzerList) { recycler = cur; break }
                        cur = cur.parent as? android.view.View
                    }
                    if (recycler != null) {
                        val layoutManager = recycler.layoutManager as? LinearLayoutManager
                        val lastPos = layoutManager?.findLastVisibleItemPosition() ?: -1
                        val itemCount = recycler.adapter?.itemCount ?: 0
                        if (lastPos >= itemCount - 1 && !recycler.canScrollVertically(1)) {
                            fabDeleteDuplicates.requestFocus()
                            return true
                        }
                    }
                }
            }

            if (event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP) {
                val focused = currentFocus
                if (focused != null) {
                    // Handle DPAD_UP from FAB back to list
                    if (focused.id == R.id.fabDeleteDuplicates) {
                        val frag = if (::pagerAdapter.isInitialized) pagerAdapter.getFragment(TAB_DUPLICATES) else null
                        val fv = frag?.view
                        if (fv != null) {
                            val recycler = fv.findViewById<RecyclerView>(R.id.recyclerAnalyzerList)
                            if (recycler != null && recycler.childCount > 0) {
                                recycler.getChildAt(recycler.childCount - 1)?.requestFocus()
                                return true
                            }
                        }
                    }

                    var cur: android.view.View? = focused
                    while (cur != null) {
                        if (cur.id == R.id.viewPager) {
                            val next = android.view.FocusFinder.getInstance().findNextFocus(viewPager, focused, android.view.View.FOCUS_UP)
                            if (next == null || next == focused) {
                                tabLayout.getTabAt(tabLayout.selectedTabPosition)?.view?.requestFocus()
                                return true
                            }
                            break
                        }
                        cur = cur.parent as? android.view.View
                    }
                }
            }
        }

        return super.dispatchKeyEvent(event)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Drive picker
    // ──────────────────────────────────────────────────────────────────────────

    private fun setupDrivePicker() {
        driveEntries.clear()
        val sm = getSystemService(Context.STORAGE_SERVICE) as StorageManager
        for (vol in sm.storageVolumes) {
            try {
                val dirPath = vol.safeDirectoryPath ?: continue
                val dir = File(dirPath)
                if (!dir.exists() || !dir.canRead()) continue

                // Derive storage label
                val rawLabel = when {
                    dir.absolutePath.contains("emulated/0") -> getString(R.string.internal_storage)
                    vol.getDescription(this) != null -> vol.getDescription(this) ?: dir.name
                    else -> dir.name
                }

                // Resolve storageId for indexed check
                val (storageId, _, _) = IndexingRepository.resolveStorageForPath(dir.absolutePath)
                val indexed = indexingRepo.isStorageFullyIndexed(storageId)
                val badge   = if (indexed) " ⚡" else ""
                driveEntries.add(DriveEntry("$rawLabel$badge", dir, storageId, indexed))
            } catch (e: Exception) {
                GoRoLog.w(TAG, getString(R.string.failed_to_enumerate_drive_emessage))
            }
        }
        if (driveEntries.isEmpty()) {
            val def      = File("/storage/emulated/0")
            val (storageId, _, _) = IndexingRepository.resolveStorageForPath(def.absolutePath)
            driveEntries.add(DriveEntry(
                getString(R.string.internal_storage), def, storageId,
                indexingRepo.isStorageFullyIndexed(storageId)
            ))
        }

        val driveAdapter = object : ArrayAdapter<DriveEntry>(this, R.layout.item_drive_spinner_selected, driveEntries) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = convertView ?: LayoutInflater.from(context)
                    .inflate(R.layout.item_drive_spinner_selected, parent, false)
                bind(v, driveEntries[position])
                return v
            }
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = convertView ?: LayoutInflater.from(context)
                    .inflate(R.layout.item_drive_spinner, parent, false)
                bind(v, driveEntries[position])
                return v
            }
            private fun bind(v: View, e: DriveEntry) {
                val txtName = v.findViewById<TextView>(R.id.txtDriveName)
                txtName?.text = e.label

                val iconRes = if (e.path.absolutePath.contains("emulated/0"))
                    R.drawable.ic_storage_internal else R.drawable.ic_storage_sdcard
                v.findViewById<ImageView>(R.id.imgDriveIcon)?.setImageResource(iconRes)
            }
        }
        spinnerDrive.adapter = driveAdapter

        spinnerDrive.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                selectedDrive = driveEntries[pos]
                viewModel.clearReport()
                startAnalysis()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Auto-trigger for first drive
        if (driveEntries.isNotEmpty()) {
            selectedDrive = driveEntries[0]
            startAnalysis()
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Analysis trigger
    // ──────────────────────────────────────────────────────────────────────────

    private fun startAnalysis() {
        val drive = selectedDrive ?: return
        viewModel.analyze(drive.storageId, drive.path, drive.isIndexed)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // ViewModel observers
    // ──────────────────────────────────────────────────────────────────────────

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.isLoading.collectLatest { loading ->
                progressScanning.visibility = if (loading) View.VISIBLE else View.GONE
            }
        }
        lifecycleScope.launch {
            viewModel.report.collectLatest { report ->
                report ?: return@collectLatest
                val indexed = report.isIndexed
                txtNotIndexedBanner.visibility = if (!indexed) View.VISIBLE else View.GONE

                // Disable advanced tabs for non-indexed storage
                for (i in TAB_LARGE..TAB_SUGGESTIONS) {
                    tabLayout.getTabAt(i)?.view?.isEnabled = indexed
                    tabLayout.getTabAt(i)?.view?.alpha    = if (indexed) 1f else 0.4f
                }

                // Notify each tab fragment
                pagerAdapter.onReportReady(report)
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // FAB for duplicate deletion
    // ──────────────────────────────────────────────────────────────────────────

    private fun setupDeleteFab() {
        fabDeleteDuplicates.setOnClickListener {
            val adapter = duplicateAdapter ?: return@setOnClickListener
            val toDelete = adapter.checkedFiles.toList()
            if (toDelete.isEmpty()) return@setOnClickListener

            // Safety check: is any group being entirely deleted?
            val dangerousFiles = mutableListOf<String>()
            val report = viewModel.report.value
            report?.duplicateGroups?.forEach { group ->
                val groupFiles = group.files
                val selectedInGroup = groupFiles.filter { gf -> adapter.checkedFiles.any { it.path == gf.path } }
                if (selectedInGroup.size == groupFiles.size && groupFiles.isNotEmpty()) {
                    // All files in this group are selected!
                    dangerousFiles.add(groupFiles.first().filename)
                }
            }

            val onConfirm = {
                deleteDuplicates(toDelete.map { it.path }.toSet(), adapter)
            }

            if (dangerousFiles.isNotEmpty()) {
                DuplicateSafetySheet.newInstance(dangerousFiles, onConfirm)
                    .show(supportFragmentManager, DuplicateSafetySheet.TAG)
            } else {
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.analyzer_delete_confirm_title, toDelete.size))
                    .setMessage(R.string.analyzer_delete_confirm_msg)
                    .setPositiveButton(R.string.delete) { _, _ -> onConfirm() }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }
    }

    private fun deleteDuplicates(paths: Set<String>, adapter: AnalyzerDuplicateAdapter) {
        lifecycleScope.launch {
            // Run the blocking File.delete() calls (native remove syscall) on the IO
            // dispatcher: on slow or busy storage a single delete can exceed the 5 s
            // ANR watchdog threshold and freeze the main thread (reported from a
            // KTC JVC 2K TV, SDK 34, app 1.8.0-GOOGLE).
            val (deleted, failedCount, hasProtectedFailed) = withContext(Dispatchers.IO) {
                val deleted = mutableSetOf<String>()
                var failedCount = 0
                var hasProtectedFailed = false
                for (path in paths) {
                    val f = File(path)
                    val success = if (za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.canUseShizukuForPath(path)) {
                        za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.delete(path)
                    } else {
                        f.exists() && f.delete()
                    }
                    if (success) {
                        // Keep the Room index in sync
                        indexingRepo.deleteFromIndex(path)
                        deleted.add(path)
                    } else {
                        failedCount++
                        if (za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.isProtectedPath(path)) {
                            hasProtectedFailed = true
                        }
                    }
                }
                Triple(deleted, failedCount, hasProtectedFailed)
            }
            adapter.removeFiles(deleted)
            fabDeleteDuplicates.visibility = View.GONE
            if (failedCount > 0) {
                val shizukuAuthorized = za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.isAuthorized()
                val msg = if (hasProtectedFailed && !shizukuAuthorized) {
                    getString(R.string.delete_error_shizuku_required)
                } else {
                    getString(R.string.delete_error)
                }
                android.widget.Toast.makeText(this@StorageAnalyzerActivity, msg, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Called by the Duplicates fragment when its adapter is created. */
    fun registerDuplicateAdapter(a: AnalyzerDuplicateAdapter) {
        duplicateAdapter = a
        a.onSelectionChanged = { count ->
            fabDeleteDuplicates.visibility = if (count > 0) View.VISIBLE else View.GONE
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // ViewPager tab adapter
    // ──────────────────────────────────────────────────────────────────────────

    private class AnalyzerPagerAdapter(fa: FragmentActivity) : FragmentStateAdapter(fa) {
        private val fragments = listOf(
            OverviewTabFragment.newInstance(),
            LargeFilesTabFragment.newInstance(),
            DuplicatesTabFragment.newInstance(),
            JunkOldTabFragment.newInstance(),
            SuggestionsTabFragment.newInstance()
        )

        override fun getItemCount() = fragments.size
        override fun createFragment(position: Int) = fragments[position]

        fun getFragment(position: Int): AnalyzerTabFragment? = fragments.getOrNull(position)

        fun onReportReady(report: AnalyzerReport) {
            fragments.forEach { it.onReportReady(report) }
        }
    }

    companion object {
        const val TAB_OVERVIEW   = 0
        const val TAB_LARGE      = 1
        const val TAB_DUPLICATES = 2
        const val TAB_JUNK       = 3
        const val TAB_SUGGESTIONS = 4
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Tab Fragments — one per tab
// ──────────────────────────────────────────────────────────────────────────────

/** Base class with common report-ready callback. */
abstract class AnalyzerTabFragment : Fragment() {
    protected var pendingReport: AnalyzerReport? = null
    protected var isTv: Boolean = false

    override fun onAttach(context: android.content.Context) {
        super.onAttach(context)
        isTv = DeviceUtils.isTvDevice(context)
    }

    open fun onReportReady(report: AnalyzerReport) {
        pendingReport = report
        if (isAdded) bindReport(report)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        pendingReport?.let { bindReport(it) }
    }

    abstract fun bindReport(report: AnalyzerReport)

    protected fun makeRecycler(view: View, id: Int): RecyclerView =
        view.findViewById<RecyclerView>(id).also {
            it.layoutManager = LinearLayoutManager(requireContext())
            it.setHasFixedSize(false)
        }
}

/* ── Overview Tab ─────────────────────────────────────────────────────────── */
class OverviewTabFragment : AnalyzerTabFragment() {

    companion object { fun newInstance() = OverviewTabFragment() }

    override fun onCreateView(inf: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        inf.inflate(if (isTv) R.layout.fragment_analyzer_overview_tv else R.layout.fragment_analyzer_overview, container, false)

    override fun bindReport(report: AnalyzerReport) {
        val v = view ?: return
        val totalFmt = Formatter.formatFileSize(requireContext(), report.totalBytes)
        val usedFmt  = Formatter.formatFileSize(requireContext(), report.usedBytes)

        v.findViewById<TextView>(R.id.txtAnalyzerTotalUsage)?.text =
            getString(R.string.analyzer_total_used, usedFmt, totalFmt)

        // Storage bar
        val colors = listOf(
            R.color.ufm_analyzer_images, R.color.ufm_analyzer_videos,
            R.color.ufm_analyzer_audio, R.color.ufm_analyzer_documents,
            R.color.ufm_analyzer_apks, R.color.ufm_analyzer_other
        )
        val segments = report.categoryBreakdown.mapIndexed { i, cat ->
            StorageBarView.Segment(getString(cat.nameRes), cat.bytes,
                ContextCompat.getColor(requireContext(), colors.getOrElse(i) { R.color.ufm_analyzer_other }))
        }
        v.findViewById<StorageBarView>(R.id.storageBar)?.setSegments(segments)

        val catContainer = v.findViewById<android.widget.LinearLayout>(R.id.layoutOverviewCategories)
        catContainer?.removeAllViews()
        val inflater = LayoutInflater.from(v.context)
        report.categoryBreakdown.forEachIndexed { pos, cat ->
            val l = if (isTv) R.layout.item_category_tv else R.layout.item_category
            val itemView = inflater.inflate(l, catContainer, false)
            if (isTv) itemView.id = View.generateViewId()

            val iconRes = when (cat.filterType) {
                SortFilterSheet.FilterType.IMAGES -> R.drawable.ic_photo_video
                SortFilterSheet.FilterType.VIDEOS -> R.drawable.ic_photo_video
                SortFilterSheet.FilterType.AUDIO -> R.drawable.ic_audio
                SortFilterSheet.FilterType.DOCUMENTS -> R.drawable.ic_file
                SortFilterSheet.FilterType.APKS -> R.drawable.ic_apps
                else -> R.drawable.ic_file
            }
            itemView.findViewById<ImageView>(R.id.imgCategoryIcon)?.apply {
                setImageResource(iconRes)
                imageTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(context, R.color.tv_accent))
            }
            itemView.findViewById<TextView>(R.id.txtCategoryName)?.text = getString(cat.nameRes)
            itemView.findViewById<TextView>(R.id.txtFileCount)?.text = if (cat.fileCount == 1L) getString(R.string.analyzer_file_count_singular, 1) else getString(R.string.analyzer_file_count_plural, cat.fileCount)
            itemView.findViewById<TextView>(R.id.txtCategorySize)?.text = Formatter.formatFileSize(v.context, cat.bytes)
            
            if (cat.fileCount > 0) {
                itemView.isFocusable = true
                itemView.isClickable = true
                itemView.alpha = 1.0f
                itemView.setOnClickListener {
                    val intent = Intent(requireContext(), FileBrowserActivity::class.java).apply {
                        putExtra(FileBrowserActivity.EXTRA_MOUNT_PATH, report.mountPath)
                        putExtra(FileBrowserActivity.EXTRA_STORAGE_ID, report.storageId)
                        putExtra(FileBrowserActivity.EXTRA_STORAGE_LABEL, (activity as? StorageAnalyzerActivity)?.getSelectedDriveLabel() ?: "")
                        putExtra(FileBrowserActivity.EXTRA_IS_CATEGORY_MODE, true)
                        putExtra(FileBrowserActivity.EXTRA_CATEGORY_NAME, getString(cat.nameRes))
                        putExtra(FileBrowserActivity.EXTRA_FILTER_TYPE, cat.filterType.ordinal)
                    }
                    startActivity(intent)
                }
            } else {
                itemView.isFocusable = false
                itemView.isClickable = false
                itemView.alpha = 0.5f
            }
            catContainer?.addView(itemView)
        }

        // Bridge focus explicitly through the items, skipping empty categories
        if (isTv && catContainer != null) {
            val focusableCats = mutableListOf<View>()
            for (i in 0 until catContainer.childCount) {
                val vCat = catContainer.getChildAt(i)
                if (vCat.isFocusable) focusableCats.add(vCat)
            }
            
            for (i in 0 until focusableCats.size) {
                val current = focusableCats[i]
                if (i > 0) current.nextFocusUpId = focusableCats[i - 1].id
                if (i < focusableCats.size - 1) current.nextFocusDownId = focusableCats[i + 1].id
            }

            val usageSummary = v.findViewById<View>(R.id.layoutUsageSummary)
            if (usageSummary != null && focusableCats.isNotEmpty()) {
                val firstCat = focusableCats.first()
                usageSummary.nextFocusDownId = firstCat.id
                firstCat.nextFocusUpId = usageSummary.id
            }
        }

        // Top folders (if indexed)
        v.findViewById<View>(R.id.cardTopFolders)?.visibility =
            if (report.isIndexed && report.topFolders.isNotEmpty()) View.VISIBLE else View.GONE
        val folderContainer = v.findViewById<android.widget.LinearLayout>(R.id.layoutTopFolders)
        folderContainer?.removeAllViews()
        val maxSizeFolder = report.topFolders.maxOfOrNull { it.totalSize } ?: 1L
        report.topFolders.take(10).forEach { folder ->
            val l = if (isTv) R.layout.item_analyzer_folder_tv else R.layout.item_analyzer_folder
            val itemView = inflater.inflate(l, folderContainer, false)
            if (isTv) itemView.id = View.generateViewId()

            val segments = folder.folderPath.trimEnd('/').split("/")
            itemView.findViewById<TextView>(R.id.txtFolderName)?.text = when {
                segments.size >= 2 -> "…/${segments.takeLast(2).joinToString("/")}"
                else               -> folder.folderPath
            }
            itemView.findViewById<TextView>(R.id.txtFolderCount)?.text = if (folder.fileCount == 1L) getString(R.string.analyzer_file_count_singular, 1) else getString(R.string.analyzer_file_count_plural, folder.fileCount)
            itemView.findViewById<TextView>(R.id.txtFolderSize)?.text = Formatter.formatFileSize(v.context, folder.totalSize)
            itemView.findViewById<android.widget.ProgressBar>(R.id.progressFolder)?.apply {
                max = 1000
                progress = ((folder.totalSize.toFloat() / maxSizeFolder) * 1000).toInt()
            }
            itemView.setOnClickListener {
                startActivity(Intent(requireContext(), FileBrowserActivity::class.java).apply {
                    putExtra(FileBrowserActivity.EXTRA_MOUNT_PATH, folder.folderPath)
                })
            }
            folderContainer?.addView(itemView)
        }

        // Bridge focus between folder items (TV only)
        if (isTv && folderContainer != null && folderContainer.childCount > 0) {
            for (i in 0 until folderContainer.childCount) {
                val item = folderContainer.getChildAt(i)
                if (!item.isFocusable) {
                    item.isFocusable = true
                    item.isFocusableInTouchMode = true
                }
                if (i > 0) item.nextFocusUpId = folderContainer.getChildAt(i - 1).id
                if (i < folderContainer.childCount - 1) item.nextFocusDownId = folderContainer.getChildAt(i + 1).id
            }
        }

        // App usage (if indexed)
        v.findViewById<View>(R.id.cardAppUsage)?.visibility =
            if (report.isIndexed && report.appUsage.isNotEmpty()) View.VISIBLE else View.GONE
        val appContainer = v.findViewById<android.widget.LinearLayout>(R.id.layoutAppUsage)
        appContainer?.removeAllViews()
        report.appUsage.forEach { app ->
            val l = if (isTv) R.layout.item_analyzer_app_usage_tv else R.layout.item_analyzer_app_usage
            val itemView = inflater.inflate(l, appContainer, false)
            if (isTv) itemView.id = View.generateViewId()

            itemView.findViewById<TextView>(R.id.txtAppName)?.text = getString(app.nameRes)
            itemView.findViewById<TextView>(R.id.txtAppSize)?.text = Formatter.formatFileSize(v.context, app.totalBytes)
            itemView.findViewById<TextView>(R.id.txtAppFileCount)?.text = if (app.fileCount == 1) getString(R.string.analyzer_file_count_singular, 1) else getString(R.string.analyzer_file_count_plural, app.fileCount)
            
            appContainer?.addView(itemView)
        }

        // Bridge focus between app items (TV only)
        if (isTv && appContainer != null && appContainer.childCount > 0) {
            for (i in 0 until appContainer.childCount) {
                val item = appContainer.getChildAt(i)
                if (!item.isFocusable) {
                    item.isFocusable = true
                    item.isFocusableInTouchMode = true
                }
                if (i > 0) item.nextFocusUpId = appContainer.getChildAt(i - 1).id
                if (i < appContainer.childCount - 1) item.nextFocusDownId = appContainer.getChildAt(i + 1).id
            }
        }

        // Connect folders to apps (TV only)
        if (isTv && folderContainer != null && appContainer != null && 
            folderContainer.childCount > 0 && appContainer.childCount > 0) {
            val lastFolder = folderContainer.getChildAt(folderContainer.childCount - 1)
            val firstApp = appContainer.getChildAt(0)
            lastFolder.nextFocusDownId = firstApp.id
            firstApp.nextFocusUpId = lastFolder.id
        }
    }


}

/* ── Large Files Tab ─────────────────────────────────────────────────────── */
class LargeFilesTabFragment : AnalyzerTabFragment() {

    companion object { fun newInstance() = LargeFilesTabFragment() }

    override fun onCreateView(inf: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        inf.inflate(R.layout.fragment_analyzer_list, container, false)

    override fun bindReport(report: AnalyzerReport) {
        val v = view ?: return
        showNotIndexedIfNeeded(v, report)
        if (!report.isIndexed) return
        makeRecycler(v, R.id.recyclerAnalyzerList).adapter =
            AnalyzerLargeFileAdapter(report.largeFiles, isTv) { file ->
                // Open file via FileBrowserActivity at its folder
                startActivity(Intent(requireContext(), FileBrowserActivity::class.java).apply {
                    putExtra(FileBrowserActivity.EXTRA_MOUNT_PATH, file.folderPath)
                    putExtra(FileBrowserActivity.EXTRA_FOCUS_PATH, file.path)
                    putExtra(FileBrowserActivity.EXTRA_STORAGE_ID, report.storageId)
                    putExtra(FileBrowserActivity.EXTRA_STORAGE_LABEL, (activity as? StorageAnalyzerActivity)?.getSelectedDriveLabel() ?: "")
                })
            }
    }
}

/* ── Duplicates Tab ──────────────────────────────────────────────────────── */
class DuplicatesTabFragment : AnalyzerTabFragment() {

    companion object { fun newInstance() = DuplicatesTabFragment() }

    override fun onCreateView(inf: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        inf.inflate(R.layout.fragment_analyzer_list, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Observe the Phase-2 verification flag from the shared ViewModel to drive the
        // "Verifying content…" progress indicator pinned at the bottom of the list.
        val vm = (activity as? StorageAnalyzerActivity)?.viewModel ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            vm.isDuplicateScanRunning.collectLatest { running: Boolean ->
                view.findViewById<View>(R.id.layoutVerifying)?.visibility =
                    if (running) View.VISIBLE else View.GONE
            }
        }
    }

    override fun bindReport(report: AnalyzerReport) {
        val v = view ?: return
        showNotIndexedIfNeeded(v, report)
        if (!report.isIndexed) return

        val noHash = report.duplicateGroups.isEmpty() && report.isIndexed
        v.findViewById<TextView>(R.id.txtListEmptyState)?.visibility =
            if (noHash) View.VISIBLE else View.GONE
        if (noHash) return

        val adapter = AnalyzerDuplicateAdapter(report.duplicateGroups, isTv)
        (activity as? StorageAnalyzerActivity)?.registerDuplicateAdapter(adapter)
        makeRecycler(v, R.id.recyclerAnalyzerList).adapter = adapter
    }
}

/* ── Junk & Old Tab ──────────────────────────────────────────────────────── */
class JunkOldTabFragment : AnalyzerTabFragment() {

    companion object { fun newInstance() = JunkOldTabFragment() }

    override fun onCreateView(inf: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        inf.inflate(R.layout.fragment_analyzer_list, container, false)

    override fun bindReport(report: AnalyzerReport) {
        val v = view ?: return
        showNotIndexedIfNeeded(v, report)
        if (!report.isIndexed) return
        makeRecycler(v, R.id.recyclerAnalyzerList).adapter =
            AnalyzerJunkOldAdapter(requireContext(), report.junkReport.files, report.oldFiles, isTv) { file ->
                startActivity(Intent(requireContext(), FileBrowserActivity::class.java).apply {
                    putExtra(FileBrowserActivity.EXTRA_MOUNT_PATH, file.folderPath)
                    putExtra(FileBrowserActivity.EXTRA_FOCUS_PATH, file.path)
                    putExtra(FileBrowserActivity.EXTRA_STORAGE_ID, report.storageId)
                    putExtra(FileBrowserActivity.EXTRA_STORAGE_LABEL, (activity as? StorageAnalyzerActivity)?.getSelectedDriveLabel() ?: "")
                })
            }
    }
}

/* ── Suggestions Tab ─────────────────────────────────────────────────────── */
class SuggestionsTabFragment : AnalyzerTabFragment() {

    companion object { fun newInstance() = SuggestionsTabFragment() }

    override fun onCreateView(inf: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        inf.inflate(R.layout.fragment_analyzer_list, container, false)

    override fun bindReport(report: AnalyzerReport) {
        val v = view ?: return
        showNotIndexedIfNeeded(v, report)
        if (!report.isIndexed) return
        makeRecycler(v, R.id.recyclerAnalyzerList).adapter =
            AnalyzerRecommendationAdapter(report.recommendations, isTv) { rec ->
                // Navigate to relevant tab
                val activity = activity as? StorageAnalyzerActivity ?: return@AnalyzerRecommendationAdapter
                val tab = if (rec.targetTab != -1) rec.targetTab else StorageAnalyzerActivity.TAB_JUNK
                activity.findViewById<ViewPager2>(R.id.viewPager)?.currentItem = tab
            }
    }
}

/** Helper extension for tab fragments. */
fun AnalyzerTabFragment.showNotIndexedIfNeeded(v: View, report: AnalyzerReport) {
    val banner = v.findViewById<TextView>(R.id.txtListNotIndexed)
    if (!report.isIndexed) {
        banner?.visibility = View.VISIBLE
        banner?.text = v.context.getString(R.string.analyzer_not_indexed_tab)
        v.findViewById<RecyclerView>(R.id.recyclerAnalyzerList)?.visibility = View.GONE
    } else {
        banner?.visibility = View.GONE
        v.findViewById<RecyclerView>(R.id.recyclerAnalyzerList)?.visibility = View.VISIBLE
    }
}
