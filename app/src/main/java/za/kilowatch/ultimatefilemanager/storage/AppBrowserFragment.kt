package za.kilowatch.ultimatefilemanager.storage

import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.Environment
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import java.io.File
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * App Browser Fragment.
 *
 * In full-screen mode: shows User / System / Debloater tabs + search bar.
 * In twin-window mode: shows ONLY user apps in a compact, clean list
 *   (no tabs, no search bar) using [fragment_app_browser_twin] layout.
 */
class AppBrowserFragment : Fragment() {

    private lateinit var recyclerApps: RecyclerView
    private lateinit var appAdapter: AppAdapter
    private lateinit var txtAppCount: TextView
    private lateinit var txtEmpty: TextView
    private lateinit var progressLoading: ProgressBar
    private lateinit var debloatRepository: DebloatRepository

    // Full-screen-only views (null in twin window mode)
    private var editSearch: EditText? = null

    // Selection Bar UI
    private lateinit var layoutSelectionBar: View
    private lateinit var txtSelectionCount: TextView
    private var layoutActionPillsScroll: View? = null

    private var allApps = mutableListOf<AppItem>()
    private var debloatMap = mapOf<String, DebloatApp>()
    private var showingSystem = false
    private var showingDebloater = false

    private var currentSortMode = SortFilterAppSheet.AppSortMode.NAME
    private var currentSortOrder = SortFilterAppSheet.AppSortOrder.ASC

    private var isTwinWindow: Boolean = false

    // Callbacks wired by TwinWindowActivity
    var onActionRequested: ((String) -> Unit)? = null
    var onStoragePickerRequested: (() -> Unit)? = null
    /** Called when the user taps the back button inside the twin-window app pane. */
    var onNavigateBack: (() -> Unit)? = null

    companion object {
        fun newInstance(isTwinWindow: Boolean = false): AppBrowserFragment {
            return AppBrowserFragment().apply {
                arguments = Bundle().apply {
                    putBoolean("isTwinWindow", isTwinWindow)
                }
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        isTwinWindow = arguments?.getBoolean("isTwinWindow", false) == true
        val isTv = DeviceUtils.isTvDevice(requireContext())
        val layoutRes = when {
            isTwinWindow && isTv -> R.layout.fragment_app_browser_twin_tv
            isTwinWindow         -> R.layout.fragment_app_browser_twin
            else                 -> R.layout.fragment_app_browser
        }
        return inflater.inflate(layoutRes, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        debloatRepository = DebloatRepository(requireContext())
        setupViews(view)
        // Skip debloat network call in twin window — user only sees user apps
        if (!isTwinWindow) loadDebloatData()
        loadApps()
    }

    private fun setupViews(view: View) {
        recyclerApps    = view.findViewById(R.id.recyclerApps)
        txtAppCount     = view.findViewById(R.id.txtAppCount)
        txtEmpty        = view.findViewById(R.id.txtEmpty)
        progressLoading = view.findViewById(R.id.progressLoading)
        layoutSelectionBar  = view.findViewById(R.id.layoutSelectionBar)
        txtSelectionCount   = view.findViewById(R.id.txtSelectionCount)

        // In twin mode the "pill" is a single MaterialButton (btnPillCopy).
        // In full-screen mode it sits inside layoutActionPillsScroll.
        layoutActionPillsScroll = view.findViewById(R.id.layoutActionPillsScroll)

        // ── Back button ──────────────────────────────────────────────────────
        view.findViewById<ImageView?>(R.id.btnTvBack)?.setOnClickListener {
            onNavigateBack?.invoke() ?: activity?.onBackPressedDispatcher?.onBackPressed()
        }

        // ── Drive picker  (full-screen only) ─────────────────────────────────
        if (!isTwinWindow) {
            view.findViewById<ImageView?>(R.id.btnDrivePicker)?.setOnClickListener {
                onStoragePickerRequested?.invoke()
            }
            view.findViewById<ImageView?>(R.id.btnRefreshDebloat)?.setOnClickListener { /* refresh */ }
        }

        // ── Sort sheet ───────────────────────────────────────────────────────
        view.findViewById<ImageView?>(R.id.btnSortApp)?.setOnClickListener {
            val sheet = SortFilterAppSheet()
            sheet.currentSortMode  = currentSortMode
            sheet.currentSortOrder = currentSortOrder
            sheet.onApply = { mode, order ->
                currentSortMode  = mode
                currentSortOrder = order
                filterAndDisplay()
            }
            sheet.show(childFragmentManager, "SortFilterAppSheet")
        }

        // ── Selection bar actions ────────────────────────────────────────────
        view.findViewById<View?>(R.id.btnCloseSelection)?.setOnClickListener {
            appAdapter.clearSelection()
            updateSelectionUi()
        }
        view.findViewById<View?>(R.id.btnSelectAll)?.setOnClickListener {
            if (appAdapter.selectedItems.size == appAdapter.items.size) {
                appAdapter.clearSelection()
            } else {
                appAdapter.items.forEach { appAdapter.selectedItems.add(it) }
                appAdapter.notifyDataSetChanged()
            }
            updateSelectionUi()
        }

        // ── Extract / Copy pill ──────────────────────────────────────────────
        // In twin layout this is a direct MaterialButton with id btnPillCopy
        view.findViewById<View?>(R.id.btnPillCopy)?.setOnClickListener {
            onActionRequested?.invoke("copy")
        }
        // In full-screen layout btnPillCopy lives inside layoutActionPillsScroll
        layoutActionPillsScroll?.findViewById<View?>(R.id.btnPillCopy)?.setOnClickListener {
            onActionRequested?.invoke("copy")
        }

        // ── Adapter ──────────────────────────────────────────────────────────
        val isTv = DeviceUtils.isTvDevice(requireContext())
        appAdapter = AppAdapter(
            onAppClick = { app ->
                if (appAdapter.getSelectedItems().isNotEmpty()) {
                    appAdapter.toggleSelection(app)
                    updateSelectionUi()
                } else if (isTwinWindow) {
                    // In twin mode single-tap selects (no detail dialog)
                    appAdapter.toggleSelection(app)
                    updateSelectionUi()
                } else {
                    showAppDetail(app)
                }
            },
            onAppLongClick = { app ->
                appAdapter.toggleSelection(app)
                updateSelectionUi()
            },
            compactLayout = isTwinWindow
        )
        appAdapter.setTvMode(isTv)
        recyclerApps.layoutManager = LinearLayoutManager(requireContext())
        recyclerApps.adapter = appAdapter

        // ── Tabs + Search  (full-screen only) ────────────────────────────────
        if (!isTwinWindow) {
            editSearch = view.findViewById(R.id.editSearch)

            val tabLayout = view.findViewById<com.google.android.material.tabs.TabLayout?>(R.id.tabLayout)
            tabLayout?.let { tl ->
                tl.addTab(tl.newTab().setText(getString(R.string.user_apps)))
                tl.addTab(tl.newTab().setText(getString(R.string.system_apps)))
                tl.addTab(tl.newTab().setText(getString(R.string.debloater)))
                tl.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
                    override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab?) {
                        showingSystem   = tab?.position == 1
                        showingDebloater = tab?.position == 2
                        filterAndDisplay()
                    }
                    override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
                    override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
                })
            }

            editSearch?.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { filterAndDisplay() }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
    }

    private fun updateSelectionUi() {
        val selected = appAdapter.getSelectedItems()
        val actionBar = view?.findViewById<View?>(R.id.layoutActionPillsScroll)
        if (selected.isEmpty()) {
            layoutSelectionBar.visibility = View.GONE
            actionBar?.visibility = View.GONE
        } else {
            layoutSelectionBar.visibility = View.VISIBLE
            actionBar?.visibility = View.VISIBLE
            txtSelectionCount.text = "${selected.size} selected"
        }
    }

    fun getSelectedFiles(): List<AppItem> = appAdapter.getSelectedItems()

    /**
     * Public helper to clear app selection and hide the selection bar.
     * Called by TwinWindowActivity after successful extraction.
     */
    fun exitSelectionMode() {
        if (::appAdapter.isInitialized) {
            appAdapter.clearSelection()
            updateSelectionUi()
        }
    }

    private fun loadDebloatData() {
        debloatRepository.getDebloatList(false, object : DebloatRepository.DebloatCallback {
            override fun onSuccess(data: Map<String, DebloatApp>) {
                activity?.runOnUiThread {
                    debloatMap = data
                    updateAppsWithDebloatInfo()
                    filterAndDisplay()
                }
            }
            override fun onError(e: Exception) {}
        })
    }

    private fun updateAppsWithDebloatInfo() {
        for (app in allApps) {
            app.debloatInfo = debloatMap[app.packageName]
        }
    }

    private fun loadApps() {
        progressLoading.visibility = View.VISIBLE
        recyclerApps.visibility = View.GONE

        Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            val pm = requireContext().packageManager
            val packages = pm.getInstalledPackages(0)
            val apps = mutableListOf<AppItem>()

            for (pkg in packages) {
                val appInfo = pkg.applicationInfo ?: continue
                val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                // In twin window mode, skip system apps entirely — user only wants user-installed apps
                if (isTwinWindow && isSystem) continue

                val name = pm.getApplicationLabel(appInfo).toString()
                val apkFile = File(appInfo.sourceDir)
                val apkSize = apkFile.length()

                // Removing recursive data size walking to prevent ANR.
                // Replaced with a zero-cost 0L — accurate info is not worth the CPU spike at this scale.
                val dataSize = 0L

                val icon = try { pm.getApplicationIcon(appInfo) } catch (_: Exception) { null }

                val obbDir = try {
                    File(Environment.getExternalStorageDirectory(), "Android/obb/${pkg.packageName}")
                } catch (_: Exception) { null }
                val hasObb = obbDir?.exists() == true && obbDir.listFiles()?.any { it.extension.equals("obb", true) } == true

                @Suppress("DEPRECATION")
                val pkgVerCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    pkg.longVersionCode
                } else {
                    pkg.versionCode.toLong()
                }

                apps.add(AppItem(
                    name = name, packageName = pkg.packageName, isSystem = isSystem,
                    appSizeBytes = apkSize, dataSizeBytes = dataSize,
                    installedDate = pkg.firstInstallTime, icon = icon,
                    sourceDir = appInfo.sourceDir ?: "",
                    splitSourceDirs = appInfo.splitSourceDirs?.toList() ?: emptyList(),
                    hasObb = hasObb,
                    versionName = pkg.versionName ?: "",
                    versionCode = pkgVerCode
                ))
            }
            apps.sortBy { it.name.lowercase() }

            activity?.runOnUiThread {
                allApps.clear()
                allApps.addAll(apps)
                updateAppsWithDebloatInfo()
                progressLoading.visibility = View.GONE
                recyclerApps.visibility = View.VISIBLE
                filterAndDisplay()
            }
        }.start()
    }

    private fun filterAndDisplay() {
        if (!isAdded) return
        val search = editSearch?.text?.toString()?.lowercase() ?: ""
        val filtered = allApps.filter { app ->
            val matchType = when {
                isTwinWindow    -> !app.isSystem  // twin window: user apps only
                showingDebloater -> app.debloatInfo != null
                showingSystem    -> app.isSystem
                else             -> !app.isSystem
            }
            val matchSearch = search.isEmpty() ||
                app.name.lowercase().contains(search) ||
                app.packageName.lowercase().contains(search)
            matchType && matchSearch
        }

        val sorted = when (currentSortMode) {
            SortFilterAppSheet.AppSortMode.NAME -> filtered.sortedBy { it.name.lowercase() }
            SortFilterAppSheet.AppSortMode.SIZE -> filtered.sortedBy { it.appSizeBytes + it.dataSizeBytes }
            SortFilterAppSheet.AppSortMode.DATE -> filtered.sortedBy { it.installedDate }
        }
        val finalOrdered = if (currentSortOrder == SortFilterAppSheet.AppSortOrder.DESC) sorted.reversed() else sorted

        txtAppCount.text = "${finalOrdered.size} apps"
        appAdapter.submitList(finalOrdered, showingDebloater)
        txtEmpty.visibility = if (finalOrdered.isEmpty()) View.VISIBLE else View.GONE
        recyclerApps.visibility = if (finalOrdered.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun showAppDetail(app: AppItem) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(app.name)
            .setIcon(app.icon)
            .setMessage("Package: ${app.packageName}")
            .setPositiveButton("Select") { _, _ ->
                appAdapter.toggleSelection(app)
                updateSelectionUi()
            }
            .setNegativeButton(R.string.remote_close, null)
            .show()
    }

    fun handleBackPress(): Boolean {
        if (appAdapter.getSelectedItems().isNotEmpty()) {
            exitSelectionMode()
            return true
        }
        if (isTwinWindow && onNavigateBack != null) {
            onNavigateBack?.invoke()
            return true
        }
        return false
    }
}
