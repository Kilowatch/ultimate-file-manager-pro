package za.kilowatch.ultimatefilemanager.storage

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.text.format.Formatter
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.settings.ApkExtractPreferenceManager
import za.kilowatch.ultimatefilemanager.util.ApkMetadataExtractor
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import za.kilowatch.ultimatefilemanager.settings.FontSizeHelper
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import za.kilowatch.ultimatefilemanager.ui.policy.ProminentDisclosureHelper

/**
 * Displays installed applications with the ability to view details
 * and open the system app info screen.
 * Supports both phone and TV layouts with D-pad navigation.
 */
class AppManagerActivity : AppCompatActivity() {

    private lateinit var recyclerApps: RecyclerView
    private lateinit var appAdapter: AppAdapter
    private lateinit var txtAppCount: TextView
    private lateinit var txtEmpty: TextView
    private lateinit var progressLoading: ProgressBar
    private lateinit var editSearch: EditText
    private lateinit var debloatRepository: DebloatRepository

    private var allApps = mutableListOf<AppItem>()
    private var debloatMap = mapOf<String, DebloatApp>()
    private var showingSystem = false
    private var showingDebloater = false
    private var hasLoaded = false

    private var currentSortMode = SortFilterAppSheet.AppSortMode.NAME
    private var currentSortOrder = SortFilterAppSheet.AppSortOrder.ASC

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (DeviceUtils.isTvDevice(this)) {
            setContentView(R.layout.activity_app_manager_tv)
        } else {
            setContentView(R.layout.activity_app_manager)
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        setupViews()
        debloatRepository = DebloatRepository(this)
        ProminentDisclosureHelper.showIfNeeded(
            activity = this,
            onContinue = {
                loadDebloatData()
                loadApps()
            },
            onCancel = {
                finish()
            }
        )
    }

    private fun loadDebloatData() {
        debloatRepository.getDebloatList(false, object : DebloatRepository.DebloatCallback {
            override fun onSuccess(data: Map<String, DebloatApp>) {
                runOnUiThread {
                    debloatMap = data
                    updateAppsWithDebloatInfo()
                    filterAndDisplay()
                }
            }
            override fun onError(e: Exception) {
                // Fail silently or handle error
            }
        })
    }

    private fun updateAppsWithDebloatInfo() {
        for (app in allApps) {
            app.debloatInfo = debloatMap[app.packageName]
        }
    }

    override fun onResume() {
        super.onResume()
        // Reload apps when returning from system app info (uninstall/clear data)
        if (hasLoaded) {
            loadApps()
        }
    }

    private fun setupViews() {
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { navigateBack() }

        // Custom back button
        val btnBack = findViewById<ImageView>(R.id.btnAppsBack)
        val whiteCsl = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_text_primary))
        val blackCsl = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_button_focused_yellow_text))
        btnBack?.imageTintList = whiteCsl
        btnBack?.setOnClickListener { navigateBack() }
        btnBack?.setOnFocusChangeListener { _, hasFocus ->
            btnBack.imageTintList = if (hasFocus) blackCsl else whiteCsl
        }

        recyclerApps = findViewById(R.id.recyclerApps)
        txtAppCount = findViewById(R.id.txtAppCount)
        txtEmpty = findViewById(R.id.txtEmpty)
        progressLoading = findViewById(R.id.progressLoading)
        editSearch = findViewById(R.id.editSearch)

        val btnSortApp = findViewById<ImageView>(R.id.btnSortApp)
        btnSortApp?.setOnClickListener {
            val sheet = SortFilterAppSheet()
            sheet.currentSortMode = currentSortMode
            sheet.currentSortOrder = currentSortOrder
            sheet.onApply = { mode, order ->
                currentSortMode = mode
                currentSortOrder = order
                filterAndDisplay()
            }
            sheet.show(supportFragmentManager, "SortFilterAppSheet")
        }
        btnSortApp?.setOnFocusChangeListener { _, hasFocus ->
            btnSortApp.imageTintList = if (hasFocus) blackCsl else whiteCsl
        }

        val btnRefresh = findViewById<ImageView>(R.id.btnRefreshDebloat)
        btnRefresh?.setOnClickListener {
            showPremiumSnackbar(getString(R.string.updating_debloat_list))
            debloatRepository.getDebloatList(true, object : DebloatRepository.DebloatCallback {
                override fun onSuccess(data: Map<String, DebloatApp>) {
                    runOnUiThread {
                        debloatMap = data
                        updateAppsWithDebloatInfo()
                        filterAndDisplay()
                        showPremiumSnackbar(getString(R.string.debloat_list_updated))
                    }
                }
                override fun onError(e: Exception) {
                    runOnUiThread {
                        showPremiumSnackbar(getString(R.string.failed_to_update_list))
                    }
                }
            })
        }

        appAdapter = AppAdapter(onAppClick = { app ->
            showAppDetail(app)
        })
        appAdapter.setTvMode(DeviceUtils.isTvDevice(this))
        recyclerApps.layoutManager = LinearLayoutManager(this)
        recyclerApps.adapter = appAdapter

        // Tab layout
        val tabLayout = findViewById<TabLayout>(R.id.tabLayout)
        tabLayout.addTab(tabLayout.newTab().setText(getString(R.string.user_apps)))
        tabLayout.addTab(tabLayout.newTab().setText(getString(R.string.system_apps)))
        tabLayout.addTab(tabLayout.newTab().setText(getString(R.string.debloater)))
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                showingSystem = tab?.position == 1
                showingDebloater = tab?.position == 2
                btnRefresh?.visibility = if (showingDebloater) View.VISIBLE else View.GONE
                filterAndDisplay()
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        // Search
        editSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterAndDisplay()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                navigateBack()
            }
        })
    }

    private fun navigateBack() {
        if (isTaskRoot) {
            val intent = Intent(this, za.kilowatch.ultimatefilemanager.storage.StorageBrowserActivity::class.java)
            startActivity(intent)
        }
        finish()
    }

    private fun loadApps() {
        progressLoading.visibility = View.VISIBLE
        recyclerApps.visibility = View.GONE

        Thread {
            val pm = packageManager
            val packages = pm.getInstalledPackages(0)
            val apps = mutableListOf<AppItem>()

            for (pkg in packages) {
                val appInfo = pkg.applicationInfo ?: continue
                val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val name = pm.getApplicationLabel(appInfo).toString()

                // APK size
                val apkFile = File(appInfo.sourceDir)
                val apkSize = apkFile.length()

                // Data size (best effort)
                var dataSize = 0L
                try {
                    val dataDir = appInfo.dataDir?.let { File(it) }
                    if (dataDir != null && dataDir.exists()) {
                        dataSize = dataDir.walkTopDown()
                            .filter { it.isFile }
                            .sumOf { it.length() }
                    }
                } catch (_: Exception) { }

                val icon = try {
                    pm.getApplicationIcon(appInfo)
                } catch (_: Exception) { null }

                // OBB detection (null-safe)
                val obbDir = try {
                    File(
                        Environment.getExternalStorageDirectory(),
                        "Android/obb/${pkg.packageName}"
                    )
                } catch (_: Exception) { null }
                val hasObb = obbDir?.exists() == true &&
                        obbDir.listFiles()?.any { it.extension.equals("obb", ignoreCase = true) } == true

                val pkgVerName = pkg.versionName ?: ""
                @Suppress("DEPRECATION")
                val pkgVerCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    pkg.longVersionCode
                } else {
                    pkg.versionCode.toLong()
                }

                apps.add(
                    AppItem(
                        name = name,
                        packageName = pkg.packageName,
                        isSystem = isSystem,
                        appSizeBytes = apkSize,
                        dataSizeBytes = dataSize,
                        installedDate = pkg.firstInstallTime,
                        icon = icon,
                        sourceDir = appInfo.sourceDir ?: "",
                        splitSourceDirs = appInfo.splitSourceDirs?.toList() ?: emptyList(),
                        hasObb = hasObb,
                        versionName = pkgVerName,
                        versionCode = pkgVerCode
                    )
                )
            }

            apps.sortBy { it.name.lowercase() }

            runOnUiThread {
                allApps.clear()
                allApps.addAll(apps)
                updateAppsWithDebloatInfo()
                hasLoaded = true
                progressLoading.visibility = View.GONE
                recyclerApps.visibility = View.VISIBLE
                filterAndDisplay()
            }
        }.start()
    }

    private fun filterAndDisplay() {
        val search = editSearch.text?.toString()?.lowercase() ?: ""
        val filtered = allApps.filter { app ->
            val matchType = when {
                showingDebloater -> app.debloatInfo != null
                showingSystem -> app.isSystem
                else -> !app.isSystem
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

        val finalOrdered = if (currentSortOrder == SortFilterAppSheet.AppSortOrder.DESC) {
            sorted.reversed()
        } else {
            sorted
        }

        val countRes = if (finalOrdered.size == 1) R.string.app_count_singular else R.string.app_count_plural
        txtAppCount.text = getString(countRes, finalOrdered.size)
        appAdapter.submitList(finalOrdered, showingDebloater)
        txtEmpty.visibility = if (finalOrdered.isEmpty()) View.VISIBLE else View.GONE
        recyclerApps.visibility = if (finalOrdered.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun showAppDetail(app: AppItem) {
        val bgColor = getColor(R.color.tv_bg_gradient_end)
        val white = getColor(R.color.tv_text_primary)
        val black = getColor(R.color.tv_button_focused_yellow_text)
        val yellow = getColor(R.color.tv_button_focused_yellow)
        val yellowCsl = android.content.res.ColorStateList.valueOf(yellow)
        val glassCsl = android.content.res.ColorStateList.valueOf(0x26FFFFFF.toInt())

        val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
        val installedStr = dateFormat.format(Date(app.installedDate))
        val appSizeStr = Formatter.formatFileSize(this, app.appSizeBytes)
        val dataSizeStr = Formatter.formatFileSize(this, app.dataSizeBytes)

        val message = buildString {
            append(getString(R.string.package_apppackagename, app.packageName)).append("\n\n")
            append(getString(R.string.installed_installedstr, installedStr)).append("\n")
            append(getString(R.string.app_size_appsizestr, appSizeStr)).append("\n")
            append(getString(R.string.data_size_datasizestr, dataSizeStr)).append("\n")
            val typeStr = if (app.isSystem) getString(R.string.app_type_system) else getString(R.string.app_type_user)
            append(getString(R.string.app_type_label, typeStr))
            
            app.debloatInfo?.let {
                append("\n\n").append(getString(R.string.debloater_guide)).append("\n")
                append(getString(R.string.safety_itrecommendation, it.recommendation)).append("\n")
                append(getString(R.string.description_itdescription, it.description))
            }
        }

        val dialogBuilder = MaterialAlertDialogBuilder(this,
            com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
            .setTitle(app.name)
            .setIcon(app.icon)
            .setMessage(message)
            .setPositiveButton(getString(R.string.extract)) { _, _ ->
                extractApp(app)
            }
            .setNeutralButton(getString(R.string.app_info)) { _, _ ->
                openAppInfo(app.packageName)
            }
            .setNegativeButton(R.string.remote_close, null)

        val dialog = dialogBuilder.create()
        dialog.show()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(bgColor))
        val titleView = dialog.findViewById<android.widget.TextView>(
            com.google.android.material.R.id.alertTitle
        ) ?: dialog.findViewById(resources.getIdentifier("alertTitle", "id", "android"))
        titleView?.setTextColor(white)
        dialog.findViewById<android.widget.TextView>(android.R.id.message)?.setTextColor(white)

        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.apply {
            backgroundTintList = glassCsl; setTextColor(white)
        }
        dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL)?.apply {
            backgroundTintList = glassCsl; setTextColor(white)
        }
        dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)?.apply {
            backgroundTintList = glassCsl; setTextColor(white)
        }
    }

    /**
     * Auto-detects whether to produce an APK or XAPK and copies/zips
     * the result to the public Downloads/UFM-Extracted/ folder (user-accessible).
     */
    private fun extractApp(app: AppItem) {
        val useXapk = app.splitSourceDirs.isNotEmpty() || app.hasObb
        showPremiumSnackbar(getString(R.string.extracting_appname, app.name))

        Thread {
            try {
                val outDir = File(
                    getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                    "UFM-Extracted"
                )
                outDir.mkdirs()

                val ext = if (useXapk) "xapk" else "apk"
                val enhancedEnabled = ApkExtractPreferenceManager.isEnabled(this)
                val baseName = if (enhancedEnabled) {
                    ApkMetadataExtractor.generateUniqueFilename(outDir, app.name, app.packageName, ext)
                } else {
                    app.name
                }

                val outputFile = File(outDir, "$baseName.$ext")
                if (!useXapk) {
                    val src = File(app.sourceDir)
                    FileInputStream(src).use { i -> FileOutputStream(outputFile).use { o -> i.copyTo(o) } }
                } else {
                    ZipOutputStream(FileOutputStream(outputFile)).use { zip ->
                        addToZip(zip, File(app.sourceDir), "base.apk")

                        app.splitSourceDirs.forEachIndexed { i, path ->
                            val splitFile = File(path)
                            if (splitFile.exists()) {
                                addToZip(zip, splitFile, splitFile.name.ifEmpty { "split_$i.apk" })
                            }
                        }

                        try {
                            val obbDir = File(
                                Environment.getExternalStorageDirectory(),
                                "Android/obb/${app.packageName}"
                            )
                            if (obbDir.exists()) {
                                obbDir.listFiles()?.filter {
                                    it.isFile && it.extension.equals("obb", ignoreCase = true)
                                }?.forEach { obb ->
                                    addToZip(zip, obb, "obb/${obb.name}")
                                }
                            }
                        } catch (_: Exception) { }

                        val pm = packageManager
                        val pkgInfo = runCatching {
                            pm.getPackageInfo(app.packageName, 0)
                        }.getOrNull()
                        val versionName = pkgInfo?.versionName ?: "1.0"
                        val manifest = """
                            {"xapk_version":2,"package_name":"${app.packageName}",
                            "name":"${app.name}","version_name":"$versionName"}
                        """.trimIndent()
                        zip.putNextEntry(ZipEntry("manifest.json"))
                        zip.write(manifest.toByteArray())
                        zip.closeEntry()
                    }
                }

                if (enhancedEnabled) {
                    val appInfo = ApkMetadataExtractor.extractAppInfo(this, app.packageName)
                    if (appInfo != null) {
                        if (ApkExtractPreferenceManager.isExtractIcon(this)) {
                            ApkMetadataExtractor.saveIcon(appInfo, outDir, baseName)
                        }
                        val fields = ApkExtractPreferenceManager.getSelectedFields(this)
                        if (fields.isNotEmpty()) {
                            ApkMetadataExtractor.saveMetadataJson(appInfo, outDir, baseName, fields)
                        }
                    }
                }

                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        showExtractionSuccessDialog(outputFile.name, useXapk)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        showPremiumSnackbar(getString(R.string.extract_failed_emessage, e.message ?: "Unknown error"))
                    }
                }
            }
        }.start()
    }

    /**
     * Shows a premium success dialog explaining where the file was saved
     * and that an 'APK / XAPK Extracts' tile will appear on the home screen.
     */
    private fun showExtractionSuccessDialog(fileName: String, wasXapk: Boolean) {
        val bgColor = getColor(R.color.tv_bg_gradient_end)
        val white = getColor(R.color.tv_text_primary)
        val glassCsl = android.content.res.ColorStateList.valueOf(0x26FFFFFF.toInt())
        val greenCsl = android.content.res.ColorStateList.valueOf(0xFF4CAF50.toInt())

        val formatLabel = if (wasXapk) "XAPK" else "APK"
        MaterialAlertDialogBuilder(this,
            com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
            .setTitle(getString(R.string.extracted_as_formatlabel, formatLabel))
            .setMessage(
                getString(R.string.filename_1, fileName) + "\n" +
                getString(R.string.accessible_via_ufm_app_files_downloads_ufmextracted) + "\n\n" +
                getString(R.string.a_new_tile_called_apk_xapk_extracts_will_appear_on_the) + " " +
                getString(R.string.home_screen_tap_it_to_browse_share_or_install_your_extracted_files) + " " +
                getString(R.string.the_tile_disappears_automatically_when_the_folder_is_empty)
            )
            .setPositiveButton(getString(R.string.got_it_1), null)
            .create()
            .also { dialog ->
                dialog.show()
                dialog.window?.setBackgroundDrawable(
                    android.graphics.drawable.ColorDrawable(bgColor)
                )
                val titleView = dialog.findViewById<android.widget.TextView>(
                    com.google.android.material.R.id.alertTitle
                ) ?: dialog.findViewById(
                    resources.getIdentifier("alertTitle", "id", "android")
                )
                titleView?.setTextColor(white)
                dialog.findViewById<android.widget.TextView>(android.R.id.message)
                    ?.setTextColor(white)
                dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.apply {
                    backgroundTintList = greenCsl
                    setTextColor(android.graphics.Color.WHITE)
                }
            }
    }

    /** Adds a file to a ZipOutputStream under the given entry name. */
    private fun addToZip(zip: ZipOutputStream, file: File, entryName: String) {
        zip.putNextEntry(ZipEntry(entryName))
        FileInputStream(file).use { it.copyTo(zip) }
        zip.closeEntry()
    }

    private fun openAppInfo(packageName: String) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            showPremiumSnackbar(getString(R.string.ufe0f_opened_app_info_for_inspection))
        } catch (e: Exception) {
            showPremiumSnackbar(getString(R.string.could_not_open_app_info))
        }
    }

    private fun showPremiumSnackbar(message: String) {
        val rootView = findViewById<View>(R.id.main)
        Snackbar.make(rootView, message, Snackbar.LENGTH_SHORT)
            .setBackgroundTint(getColor(R.color.tv_bg_gradient_end))
            .setTextColor(getColor(R.color.tv_text_primary))
            .show()
    }
}
