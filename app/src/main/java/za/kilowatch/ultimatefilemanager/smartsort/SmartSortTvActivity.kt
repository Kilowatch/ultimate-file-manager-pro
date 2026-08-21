package za.kilowatch.ultimatefilemanager.smartsort

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import za.kilowatch.ultimatefilemanager.settings.FontSizeHelper
import za.kilowatch.ultimatefilemanager.util.ThemeColors
import android.view.LayoutInflater
import za.kilowatch.ultimatefilemanager.util.DeviceUtils

class SmartSortTvActivity : AppCompatActivity() {

    private var folderPath: String = ""
    private var shareId: String? = null
    private var currentMode = SmartSortMode.TYPE
    private val engine = SmartSortEngine()
    private val customCategoryPaths = mutableMapOf<String, String>()
    private val customCategoryShareIds = mutableMapOf<String, String>()
    private var isCustomMode = false
    private val customRules = mutableListOf<SmartSortCustomRule>()
    private var pendingRuleIndex = -1
    private var savedConfigId: String? = null
    private var autoExecuteOnLoad = false
    private var handledFontChange = false
    private var handledLocaleChange = false

    
    private fun fixCategoryFocus() {
        val typeCats = listOf(
            R.id.chkCatPhotos to R.id.btnCatPhotosFolder,
            R.id.chkCatVideos to R.id.btnCatVideosFolder,
            R.id.chkCatAudio to R.id.btnCatAudioFolder,
            R.id.chkCatDocuments to R.id.btnCatDocumentsFolder,
            R.id.chkCatArchives to R.id.btnCatArchivesFolder,
            R.id.chkCatApps to R.id.btnCatAppsFolder,
            R.id.chkCatEbooks to R.id.btnCatEbooksFolder,
            R.id.chipIncludeOther to R.id.btnOtherFolder
        )
        val sizeCats = listOf(
            R.id.chkSizeTiny to R.id.btnSizeTinyFolder,
            R.id.chkSizeSmall to R.id.btnSizeSmallFolder,
            R.id.chkSizeMedium to R.id.btnSizeMediumFolder,
            R.id.chkSizeLarge to R.id.btnSizeLargeFolder,
            R.id.chkSizeHuge to R.id.btnSizeHugeFolder
        )
        val dateCats = listOf(
            R.id.chkDateToday to R.id.btnDateTodayFolder,
            R.id.chkDateWeek to R.id.btnDateWeekFolder,
            R.id.chkDateMonth to R.id.btnDateMonthFolder,
            R.id.chkDateYear to R.id.btnDateYearFolder,
            R.id.chkDateOlder to R.id.btnDateOlderFolder
        )
        
        for (list in listOf(typeCats, sizeCats, dateCats)) {
            for (i in list.indices) {
                val (chkId, btnId) = list[i]
                val chk = findViewById<android.view.View>(chkId) ?: continue
                val btn = findViewById<android.view.View>(btnId) ?: continue
                chk.nextFocusDownId = btnId
                btn.nextFocusUpId = chkId
                if (i + 1 < list.size) {
                    val nextChkId = list[i + 1].first
                    btn.nextFocusDownId = nextChkId
                }
            }
        }
    }

    companion object {
        const val EXTRA_FOLDER_PATH = "extra_folder_path"
        const val EXTRA_SHARE_ID = "extra_share_id"
        const val EXTRA_LOAD_CONFIG_ID = "extra_load_config_id"
        const val RESULT_SORTED = "result_sorted"
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
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("handledFontChange", handledFontChange)
        outState.putBoolean("handledLocaleChange", handledLocaleChange)
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handledFontChange = savedInstanceState?.getBoolean("handledFontChange", false) ?: false
        handledLocaleChange = savedInstanceState?.getBoolean("handledLocaleChange", false) ?: false
        enableEdgeToEdge()
        val isTv = DeviceUtils.isTvDevice(this)
        setContentView(if (isTv) R.layout.activity_smart_sort_tv else R.layout.activity_smart_sort_config)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        folderPath = intent.getStringExtra(EXTRA_FOLDER_PATH) ?: "/storage/emulated/0"
        shareId = intent.getStringExtra(EXTRA_SHARE_ID)

        findViewById<TextView>(R.id.txtTvPath).text = folderPath
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        initViews()
        setupTvFocus()
fixCategoryFocus(); fixCategoryFocus()

        val loadConfigId = intent.getStringExtra(EXTRA_LOAD_CONFIG_ID)
        if (loadConfigId != null) {
            val saved = SmartSortSavedConfigRepository.getById(loadConfigId)
            if (saved != null) {
                loadSavedConfig(saved)
                autoExecuteOnLoad = true
            }
        } else {
            val saved = SmartSortSavedConfigRepository.getForFolder(folderPath)
            if (saved != null) {
                loadSavedConfig(saved)
            }
        }
        updateSaveButtonState()
        updateSaveIcon()

        findViewById<ImageView>(R.id.btnSaveConfig)?.setOnClickListener { onSaveButtonClicked() }

        if (autoExecuteOnLoad) {
            findViewById<MaterialButton>(R.id.btnStartSort)?.post {
                findViewById<MaterialButton>(R.id.btnStartSort)?.performClick()
            }
        }
    }

    private fun initViews() {
        val txtHintScope = findViewById<TextView>(R.id.txtHintScope)
        val txtHintSubfolder = findViewById<TextView>(R.id.txtHintSubfolder)
        val txtHintMode = findViewById<TextView>(R.id.txtHintMode)
        val txtHintDuplicate = findViewById<TextView>(R.id.txtHintDuplicate)
        val txtHintExisting = findViewById<TextView>(R.id.txtHintExisting)

        val cgScope = findViewById<ChipGroup>(R.id.cgScope)
        val chipRecursive = findViewById<Chip>(R.id.chipRecursive)
        val layoutRecursive = findViewById<LinearLayout>(R.id.layoutRecursiveOptions)
        val cgSubfolder = findViewById<ChipGroup>(R.id.cgSubfolderMode)

        cgScope.setOnCheckedStateChangeListener { _, _ ->
            updateHint(cgScope, txtHintScope, mapOf(
                R.id.chipRootOnly to R.string.smart_sort_hint_scope_root,
                R.id.chipRecursive to R.string.smart_sort_hint_scope_recursive
            ))
        }
        chipRecursive.setOnCheckedChangeListener { _, isChecked ->
            layoutRecursive.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (isChecked) {
                updateHint(cgSubfolder, txtHintSubfolder, mapOf(
                    R.id.chipFlatten to R.string.smart_sort_hint_subfolder_flatten,
                    R.id.chipPreserve to R.string.smart_sort_hint_subfolder_preserve
                ))
            }
        }
        cgSubfolder.setOnCheckedStateChangeListener { _, _ ->
            updateHint(cgSubfolder, txtHintSubfolder, mapOf(
                R.id.chipFlatten to R.string.smart_sort_hint_subfolder_flatten,
                R.id.chipPreserve to R.string.smart_sort_hint_subfolder_preserve
            ))
        }

        val cgSortMode = findViewById<ChipGroup>(R.id.cgSortMode)
        val layoutType = findViewById<LinearLayout>(R.id.layoutTypeCategories)
        val layoutSize = findViewById<LinearLayout>(R.id.layoutSizeCategories)
        val layoutDate = findViewById<LinearLayout>(R.id.layoutDateCategories)

        cgSortMode.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isNotEmpty()) {
                val id = checkedIds[0]
                layoutType.visibility = View.GONE
                layoutSize.visibility = View.GONE
                layoutDate.visibility = View.GONE
                when (id) {
                    R.id.chipModeType -> { currentMode = SmartSortMode.TYPE; layoutType.visibility = View.VISIBLE }
                    R.id.chipModeSize -> { currentMode = SmartSortMode.SIZE; layoutSize.visibility = View.VISIBLE }
                    R.id.chipModeDate -> { currentMode = SmartSortMode.DATE; layoutDate.visibility = View.VISIBLE }
                }
                updateHint(cgSortMode, txtHintMode, mapOf(
                    R.id.chipModeType to R.string.smart_sort_hint_mode_type,
                    R.id.chipModeSize to R.string.smart_sort_hint_mode_size,
                    R.id.chipModeDate to R.string.smart_sort_hint_mode_date
                ))
            }
        }

        val cgSortType = findViewById<ChipGroup>(R.id.cgSortType)
        val layoutStandardOptions = findViewById<LinearLayout>(R.id.layoutStandardOptions) ?: findViewById<LinearLayout>(R.id.layoutTvStandardOptions)
        val layoutTvStandardRightCol = findViewById<android.view.View>(R.id.layoutTvStandardRightCol)
        val layoutTvStandardBottom = findViewById<android.view.View>(R.id.layoutTvStandardBottom)
        val layoutCustomOptions = findViewById<LinearLayout>(R.id.layoutCustomOptions) ?: findViewById<LinearLayout>(R.id.layoutTvCustomOptions)
        val btnAddRule = findViewById<MaterialButton>(R.id.btnAddRule)
        val txtCustomRulesEmpty = findViewById<TextView>(R.id.txtCustomRulesEmpty)
        val layoutCustomRules = findViewById<LinearLayout>(R.id.layoutCustomRules)

        cgSortType?.setOnCheckedStateChangeListener { _, checkedIds ->
            isCustomMode = checkedIds.isNotEmpty() && checkedIds[0] == R.id.chipSortCustom
            if (findViewById<android.view.View>(R.id.layoutTvStandardRightCol) != null) {
                // TV Layout
                val layoutTvSortMode = findViewById<android.view.View>(R.id.layoutTvSortMode)
                layoutTvSortMode?.visibility = if (isCustomMode) View.GONE else View.VISIBLE
                layoutTvStandardBottom?.visibility = if (isCustomMode) View.GONE else View.VISIBLE
            } else {
                // Mobile layout
                layoutStandardOptions?.visibility = if (isCustomMode) View.GONE else View.VISIBLE
            }
            layoutCustomOptions?.visibility = if (isCustomMode) View.VISIBLE else View.GONE
            if (isCustomMode) renderCustomRules(layoutCustomRules, txtCustomRulesEmpty)
            updateSaveButtonState()
        }
        btnAddRule?.setOnClickListener { showAddRuleDialog(layoutCustomRules, txtCustomRulesEmpty) }

        val cgDuplicate = findViewById<ChipGroup>(R.id.cgDuplicateStrategy)
        cgDuplicate.setOnCheckedStateChangeListener { _, _ ->
            updateHint(cgDuplicate, txtHintDuplicate, mapOf(
                R.id.chipDupSkip to R.string.smart_sort_hint_dup_skip,
                R.id.chipDupRename to R.string.smart_sort_hint_dup_rename,
                R.id.chipDupOverwrite to R.string.smart_sort_hint_dup_overwrite
            ))
        }
        val cgExisting = findViewById<ChipGroup>(R.id.cgExistingFolderStrategy)
        cgExisting.setOnCheckedStateChangeListener { _, _ ->
            updateHint(cgExisting, txtHintExisting, mapOf(
                R.id.chipExistingMerge to R.string.smart_sort_hint_existing_merge,
                R.id.chipExistingSkip to R.string.smart_sort_hint_existing_skip,
                R.id.chipExistingRename to R.string.smart_sort_hint_existing_rename
            ))
        }

        updateHint(cgScope, txtHintScope, mapOf(
            R.id.chipRootOnly to R.string.smart_sort_hint_scope_root,
            R.id.chipRecursive to R.string.smart_sort_hint_scope_recursive
        ))
        updateHint(cgSortMode, txtHintMode, mapOf(
            R.id.chipModeType to R.string.smart_sort_hint_mode_type,
            R.id.chipModeSize to R.string.smart_sort_hint_mode_size,
            R.id.chipModeDate to R.string.smart_sort_hint_mode_date
        ))
        updateHint(cgDuplicate, txtHintDuplicate, mapOf(
            R.id.chipDupSkip to R.string.smart_sort_hint_dup_skip,
            R.id.chipDupRename to R.string.smart_sort_hint_dup_rename,
            R.id.chipDupOverwrite to R.string.smart_sort_hint_dup_overwrite
        ))
        updateHint(cgExisting, txtHintExisting, mapOf(
            R.id.chipExistingMerge to R.string.smart_sort_hint_existing_merge,
            R.id.chipExistingSkip to R.string.smart_sort_hint_existing_skip,
            R.id.chipExistingRename to R.string.smart_sort_hint_existing_rename
        ))

        val btnPreview = findViewById<MaterialButton>(R.id.btnPreview)
        val btnStart = findViewById<MaterialButton>(R.id.btnStartSort)
        val btnHistory = findViewById<MaterialButton>(R.id.btnTvHistory)
        val hasHistory = SmartSortHistoryManager.loadAll().any { it.folderPath == folderPath }
        if (hasHistory) {
            val historyRed = getColor(R.color.ufm_error)
            btnHistory.setTextColor(historyRed)
            btnHistory.iconTint = android.content.res.ColorStateList.valueOf(historyRed)
        }
        btnHistory.setOnClickListener {
            SmartSortHistorySheet.newInstance(folderPath = folderPath).show(supportFragmentManager, SmartSortHistorySheet.TAG)
        }

        val txtPrefixEdit = findViewById<TextInputEditText>(R.id.txtPrefix)
        fun updateLabels(prefix: String) {
            val folderText = if (prefix.isBlank()) getString(R.string.smart_sort_folder_label, "")
            else getString(R.string.smart_sort_folder_label, "$prefix ")
            allFolderLabelIds.forEach { (key, labelId) ->
                if (customCategoryPaths.containsKey(key)) return@forEach
                val nameRes = categoryDisplayNames[key] ?: return@forEach
                findViewById<TextView>(labelId)?.text = "$folderText${getString(nameRes)}"
            }
        }
        val defaultPrefix = txtPrefixEdit.text?.toString()?.trim() ?: "UFM"
        txtPrefixEdit.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val p = s?.toString()?.trim() ?: defaultPrefix
                updateLabels(p)
                if (isCustomMode) {
                    val lcr = findViewById<LinearLayout>(R.id.layoutCustomRules) ?: findViewById<LinearLayout>(R.id.layoutCustomRules)
                    val tce = findViewById<TextView>(R.id.txtCustomRulesEmpty)
                    renderCustomRules(lcr, tce)
                }
            }
        })
        updateLabels(defaultPrefix)
        wireCategoryFolderButtons()

                btnPreview.setOnClickListener {
            val err = validateConfig()
            if (err != null) {
                showValidationErrorDialog(err)
                return@setOnClickListener
            }
            lifecycleScope.launch {
                val preview = withContext(Dispatchers.IO) { engine.preview(folderPath, buildConfig()) }
                val dialogView = LayoutInflater.from(this@SmartSortTvActivity).inflate(
                    if (isTvDevice) R.layout.dialog_smart_sort_preview_tv else R.layout.dialog_smart_sort_preview,
                    null
                )
                val sb = StringBuilder()
                preview.categoryCounts.forEach { (c, n) -> sb.appendLine("$c: $n") }
                if (sb.isEmpty()) sb.appendLine(getString(R.string.smart_sort_empty))
                dialogView.findViewById<TextView>(R.id.txtPreviewStats).text = sb.toString().trimEnd()
                dialogView.findViewById<TextView>(R.id.txtTotalFiles).text = getString(R.string.smart_sort_preview_total, preview.totalFiles)

                val conflictsAlert = dialogView.findViewById<View>(R.id.layoutConflictsAlert)
                val txtConflicts = dialogView.findViewById<TextView>(R.id.txtConflictsCount)
                if (preview.conflicts.isNotEmpty()) {
                    txtConflicts.text = getString(R.string.smart_sort_preview_conflicts, preview.conflicts.size)
                    conflictsAlert.visibility = View.VISIBLE
                } else {
                    conflictsAlert.visibility = View.GONE
                }

                val dialog = MaterialAlertDialogBuilder(this@SmartSortTvActivity, R.style.UFM_Dialog)
                    .setView(dialogView)
                    .create()
                dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
                dialogView.findViewById<View>(R.id.btnDone).setOnClickListener { dialog.dismiss() }
                dialog.show()
            }
        }

        btnStart.setOnClickListener {
            val err = validateConfig()
            if (err != null) {
                showValidationErrorDialog(err)
                return@setOnClickListener
            }
            btnPreview.isEnabled = false
            btnStart.isEnabled = false

            val progressView = LayoutInflater.from(this).inflate(
                if (isTvDevice) R.layout.dialog_smart_sort_progress_tv else R.layout.dialog_smart_sort_progress,
                null
            )
            val txtProgress = progressView.findViewById<TextView>(R.id.txtProgress)
            val progressBar = progressView.findViewById<ProgressBar>(R.id.progressBar)
            progressBar.max = 100
            progressBar.progress = 0

            val pd = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
                .setView(progressView)
                .setCancelable(false)
                .create()
            pd.window?.setBackgroundDrawableResource(android.R.color.transparent)
            pd.show()

            val cfg = buildConfig()
            lifecycleScope.launch {
                val result = engine.execute(folderPath, cfg) { fn, cur, tot ->
                    runOnUiThread {
                        txtProgress.text = getString(R.string.smart_sort_progress_moving, fn, cur, tot)
                        progressBar.progress = if (tot > 0) (cur * 100) / tot else 0
                    }
                }
                pd.dismiss()
                btnPreview.isEnabled = true
                btnStart.isEnabled = true
                if (result.movedCount > 0) {
                    SmartSortHistoryManager.addEntry(folderPath, result.movedCount, result.skippedCount, result.failedCount)
                }

                val resultsView = LayoutInflater.from(this@SmartSortTvActivity).inflate(
                    if (isTvDevice) R.layout.dialog_smart_sort_results_tv else R.layout.dialog_smart_sort_results,
                    null
                )
                resultsView.findViewById<TextView>(R.id.txtMovedCount).text = result.movedCount.toString()
                resultsView.findViewById<TextView>(R.id.txtSkippedCount).text = result.skippedCount.toString()
                resultsView.findViewById<TextView>(R.id.txtFailedCount).text = result.failedCount.toString()
                resultsView.findViewById<TextView>(R.id.txtResultMessage).text = if (result.failedCount > 0) {
                    getString(R.string.smart_sort_result_failed, result.failedCount)
                } else {
                    getString(R.string.smart_sort_result_moved, result.movedCount)
                }

                val resDialog = MaterialAlertDialogBuilder(this@SmartSortTvActivity, R.style.UFM_Dialog)
                    .setView(resultsView)
                    .setCancelable(false)
                    .create()
                resDialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
                resultsView.findViewById<View>(R.id.btnDone).setOnClickListener {
                    resDialog.dismiss()
                    setResult(RESULT_OK, Intent().apply { putExtra(RESULT_SORTED, true) })
                    finish()
                }
                resDialog.show()
            }
        }
    }
    private val categoryButtonKeys = mapOf(
    "PHOTOS" to R.id.btnCatPhotosFolder, "VIDEOS" to R.id.btnCatVideosFolder,
    "AUDIO" to R.id.btnCatAudioFolder, "DOCUMENTS" to R.id.btnCatDocumentsFolder,
    "ARCHIVES" to R.id.btnCatArchivesFolder, "APPS" to R.id.btnCatAppsFolder,
    "EBOOKS" to R.id.btnCatEbooksFolder,
    "TINY" to R.id.btnSizeTinyFolder, "SMALL" to R.id.btnSizeSmallFolder,
    "MEDIUM" to R.id.btnSizeMediumFolder, "LARGE" to R.id.btnSizeLargeFolder,
    "HUGE" to R.id.btnSizeHugeFolder,
    "TODAY" to R.id.btnDateTodayFolder, "THIS_WEEK" to R.id.btnDateWeekFolder,
    "THIS_MONTH" to R.id.btnDateMonthFolder, "THIS_YEAR" to R.id.btnDateYearFolder,
    "OLDER" to R.id.btnDateOlderFolder,
    "OTHER" to R.id.btnOtherFolder
)
private val resetButtonIds = mapOf(
    "PHOTOS" to R.id.btnCatPhotosReset, "VIDEOS" to R.id.btnCatVideosReset,
    "AUDIO" to R.id.btnCatAudioReset, "DOCUMENTS" to R.id.btnCatDocumentsReset,
    "ARCHIVES" to R.id.btnCatArchivesReset, "APPS" to R.id.btnCatAppsReset,
    "EBOOKS" to R.id.btnCatEbooksReset,
    "TINY" to R.id.btnSizeTinyReset, "SMALL" to R.id.btnSizeSmallReset,
    "MEDIUM" to R.id.btnSizeMediumReset, "LARGE" to R.id.btnSizeLargeReset,
    "HUGE" to R.id.btnSizeHugeReset,
    "TODAY" to R.id.btnDateTodayReset, "THIS_WEEK" to R.id.btnDateWeekReset,
    "THIS_MONTH" to R.id.btnDateMonthReset, "THIS_YEAR" to R.id.btnDateYearReset,
    "OLDER" to R.id.btnDateOlderReset,
    "OTHER" to R.id.btnOtherReset
)
private val allFolderLabelIds = mapOf(
    "PHOTOS" to R.id.lblCatPhotos, "VIDEOS" to R.id.lblCatVideos,
    "AUDIO" to R.id.lblCatAudio, "DOCUMENTS" to R.id.lblCatDocuments,
    "ARCHIVES" to R.id.lblCatArchives, "APPS" to R.id.lblCatApps,
    "EBOOKS" to R.id.lblCatEbooks,
    "TINY" to R.id.lblSizeTiny, "SMALL" to R.id.lblSizeSmall,
    "MEDIUM" to R.id.lblSizeMedium, "LARGE" to R.id.lblSizeLarge,
    "HUGE" to R.id.lblSizeHuge,
    "TODAY" to R.id.lblDateToday, "THIS_WEEK" to R.id.lblDateWeek,
    "THIS_MONTH" to R.id.lblDateMonth, "THIS_YEAR" to R.id.lblDateYear,
    "OLDER" to R.id.lblDateOlder,
    "OTHER" to R.id.lblCatOther
)
private val categoryDisplayNames = mapOf(
    "PHOTOS" to R.string.smart_sort_category_photos, "VIDEOS" to R.string.smart_sort_category_videos,
    "AUDIO" to R.string.smart_sort_category_audio, "DOCUMENTS" to R.string.smart_sort_category_documents,
    "ARCHIVES" to R.string.smart_sort_category_archives, "APPS" to R.string.smart_sort_category_apps,
    "EBOOKS" to R.string.smart_sort_category_ebooks,
    "TINY" to R.string.smart_sort_size_tiny, "SMALL" to R.string.smart_sort_size_small,
    "MEDIUM" to R.string.smart_sort_size_medium, "LARGE" to R.string.smart_sort_size_large,
    "HUGE" to R.string.smart_sort_size_huge,
    "TODAY" to R.string.smart_sort_date_today, "THIS_WEEK" to R.string.smart_sort_date_this_week,
    "THIS_MONTH" to R.string.smart_sort_date_this_month, "THIS_YEAR" to R.string.smart_sort_date_this_year,
    "OLDER" to R.string.smart_sort_date_older, "OTHER" to R.string.smart_sort_category_other
)
private fun wireCategoryFolderButtons() {
    val a = this
    categoryButtonKeys.forEach { (key, btnId) -> a.findViewById<View>(btnId)?.setOnClickListener { a.categoryFolderPickerLauncher.launch(Intent(a, SmartSortActivity::class.java).apply { putExtra(SmartSortActivity.EXTRA_SMART_SORT_CATEGORY_PICKER, true); putExtra(SmartSortActivity.EXTRA_SMART_SORT_CATEGORY_KEY, key) }) } }
    resetButtonIds.forEach { (key, btnId) -> a.findViewById<View>(btnId)?.setOnClickListener { customCategoryPaths.remove(key); customCategoryShareIds.remove(key); revertCategoryLabel(key) } }
}
private fun updateCategoryLabel(key: String, path: String) {
    val l = allFolderLabelIds[key]?.let { findViewById<TextView>(it) } ?: return
    l.text = getString(R.string.smart_sort_folder_label, path)
    resetButtonIds[key]?.let { findViewById<View>(it)?.visibility = View.VISIBLE }
}
private fun revertCategoryLabel(key: String) {
    val p = findViewById<TextInputEditText>(R.id.txtPrefix)?.text?.toString()?.trim() ?: "UFM"
    val d = categoryDisplayNames[key] ?: return; val l = allFolderLabelIds[key]?.let { findViewById<TextView>(it) } ?: return
    l.text = getString(R.string.smart_sort_folder_label, if (p.isBlank()) "" else "$p ${getString(d)}")
    resetButtonIds[key]?.let { findViewById<View>(it)?.visibility = View.GONE }
}
private fun renderCustomRules(container: LinearLayout?, emptyText: TextView?) {
    container?.removeAllViews(); emptyText?.visibility = if (customRules.isEmpty()) View.VISIBLE else View.GONE
    if (customRules.isEmpty()) return
    val prefix = findViewById<TextInputEditText>(R.id.txtPrefix)?.text?.toString()?.trim() ?: "UFM"
    for ((idx, rule) in customRules.withIndex()) {
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 16) }; setPadding(16, 12, 16, 12) }
        val chk = com.google.android.material.checkbox.MaterialCheckBox(this).apply {
            text = getString(R.string.smart_sort_rule_description_label, rule.description); isChecked = rule.enabled
            setOnCheckedChangeListener { _, ic -> customRules[idx] = rule.copy(enabled = ic) }
        }; card.addView(chk)
        val extT = if (rule.extensions.isEmpty()) getString(R.string.smart_sort_no_extensions_added) else getString(R.string.smart_sort_extensions, rule.extensions.joinToString(", "))
        card.addView(TextView(this).apply { text = extT; textSize = 12f; setPadding(48, 0, 0, 0) })
        val fn = customCategoryPaths[rule.id] ?: "$prefix ${rule.description}"
        card.addView(TextView(this).apply { text = getString(R.string.smart_sort_folder_label, fn); textSize = 12f; setPadding(48, 4, 0, 0) })
        val extRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(48, 4, 0, 0) }
        extRow.addView(com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(8, 0, 8, 0) }; text = getString(R.string.smart_sort_add_extension); textSize = 12f; minimumHeight = 0; setPadding(8, 2, 8, 2); setOnClickListener { showAddExtensionDialog(idx) } })
        if (rule.extensions.isNotEmpty()) {
            extRow.addView(com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(8, 0, 8, 0) }; text = getString(R.string.smart_sort_remove_extension); textSize = 12f; minimumHeight = 0; setPadding(8, 2, 8, 2); setOnClickListener { showRemoveExtensionsDialog(idx) } })
        }
        card.addView(extRow)
        val fRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(48, 4, 0, 0) }
        fRow.addView(com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(8, 0, 8, 0) }; text = getString(R.string.smart_sort_select_folder); textSize = 12f; minimumHeight = 0; setPadding(8, 2, 8, 2); setOnClickListener { pickCustomRuleFolder(idx) } })
        val hasCustomPath = customCategoryPaths.containsKey(rule.id) || rule.customFolderPath != null
        if (hasCustomPath) {
            fRow.addView(com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(8, 0, 8, 0) }; text = getString(R.string.smart_sort_set_default); textSize = 12f; minimumHeight = 0; setPadding(8, 2, 8, 2); setOnClickListener { customCategoryPaths.remove(rule.id); customCategoryShareIds.remove(rule.id); renderCustomRules(container, emptyText) } })
        }
        card.addView(fRow)
        val aRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(48, 4, 0, 0) }
        aRow.addView(com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(8, 0, 8, 0) }; text = getString(R.string.smart_sort_edit_rule); textSize = 12f; minimumHeight = 0; setPadding(8, 2, 8, 2); setOnClickListener { showEditRuleDialog(idx, container, emptyText) } })
        aRow.addView(com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(8, 0, 8, 0) }; text = getString(R.string.smart_sort_delete_rule); textSize = 12f; minimumHeight = 0; setPadding(8, 2, 8, 2); setOnClickListener { MaterialAlertDialogBuilder(this@SmartSortTvActivity, R.style.UFM_Dialog).setTitle(R.string.smart_sort_delete_rule_confirm).setIcon(R.drawable.ic_delete).setPositiveButton(R.string.smart_sort_delete_rule) { _, _ -> customRules.removeAt(idx); customCategoryPaths.remove(rule.id); customCategoryShareIds.remove(rule.id); renderCustomRules(container, emptyText) }.setNegativeButton(R.string.cancel, null).show() } })
        card.addView(aRow)
        container?.addView(card)
    }
    updateSaveButtonState()
}
private val isTvDevice: Boolean get() = DeviceUtils.isTvDevice(this)
private fun showAddRuleDialog(ruleContainer: LinearLayout?, emptyText: TextView?) {
    val dialogView = LayoutInflater.from(this).inflate(
        if (isTvDevice) R.layout.dialog_smart_sort_add_rule_tv else R.layout.dialog_smart_sort_add_rule,
        null
    )
    val txtInput = dialogView.findViewById<TextInputEditText>(R.id.txtRuleDescription)
    val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
        .setView(dialogView)
        .create()
    dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

    dialogView.findViewById<View>(R.id.btnConfirm).setOnClickListener {
        val desc = txtInput.text?.toString()?.trim() ?: ""
        if (desc.isEmpty()) {
            dialogView.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.txtRuleDescriptionLayout)?.error = getString(R.string.smart_sort_description_empty)
            return@setOnClickListener
        }
        customRules.add(SmartSortCustomRule(description = desc))
        renderCustomRules(ruleContainer, emptyText)
        dialog.dismiss()
    }
    dialogView.findViewById<View>(R.id.btnCancel).setOnClickListener {
        dialog.dismiss()
    }
    dialog.show()
}

private fun showEditRuleDialog(index: Int, container: LinearLayout?, emptyText: TextView?) {
    val rule = customRules[index]
    val dialogView = LayoutInflater.from(this).inflate(
        if (isTvDevice) R.layout.dialog_smart_sort_add_rule_tv else R.layout.dialog_smart_sort_add_rule,
        null
    )
    val txtTitle = dialogView.findViewById<TextView>(R.id.txtTitle)
    txtTitle.setText(R.string.smart_sort_edit_rule)
    val txtInput = dialogView.findViewById<TextInputEditText>(R.id.txtRuleDescription)
    txtInput.setText(rule.description)

    val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
        .setView(dialogView)
        .create()
    dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

    dialogView.findViewById<View>(R.id.btnConfirm).setOnClickListener {
        val desc = txtInput.text?.toString()?.trim() ?: ""
        if (desc.isEmpty()) {
            dialogView.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.txtRuleDescriptionLayout)?.error = getString(R.string.smart_sort_description_empty)
            return@setOnClickListener
        }
        customRules[index] = rule.copy(description = desc)
        renderCustomRules(container, emptyText)
        dialog.dismiss()
    }
    dialogView.findViewById<View>(R.id.btnCancel).setOnClickListener {
        dialog.dismiss()
    }
    dialog.show()
}

private fun showAddExtensionDialog(ruleIndex: Int) {
    val dialogView = LayoutInflater.from(this).inflate(
        if (isTvDevice) R.layout.dialog_smart_sort_add_extension_tv else R.layout.dialog_smart_sort_add_extension,
        null
    )
    val txtInput = dialogView.findViewById<TextInputEditText>(R.id.txtExtension)
    val txtErr = dialogView.findViewById<TextView>(R.id.txtExtensionError)

    val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
        .setView(dialogView)
        .create()
    dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

    dialogView.findViewById<View>(R.id.btnConfirm).setOnClickListener {
        val ext = txtInput.text?.toString()?.trim()?.lowercase()?.removePrefix(".") ?: ""
        if (ext.isEmpty()) {
            txtErr.text = getString(R.string.smart_sort_extension_empty)
            txtErr.visibility = View.VISIBLE
            return@setOnClickListener
        }
        for ((oi, or) in customRules.withIndex()) {
            if (oi != ruleIndex && ext in or.extensions) {
                txtErr.text = getString(R.string.smart_sort_extension_already_used, ext, or.description)
                txtErr.visibility = View.VISIBLE
                return@setOnClickListener
            }
        }
        customRules[ruleIndex].extensions.add(ext)
        dialog.dismiss()
        renderCustomRules(findViewById(R.id.layoutCustomRules), findViewById(R.id.txtCustomRulesEmpty))
    }
    dialogView.findViewById<View>(R.id.btnCancel).setOnClickListener {
        dialog.dismiss()
    }
    dialog.show()
}
private fun showRemoveExtensionsDialog(ruleIndex: Int) {
    val rule = customRules[ruleIndex]
    val dialogView = LayoutInflater.from(this).inflate(
        if (isTvDevice) R.layout.dialog_smart_sort_remove_extensions_tv else R.layout.dialog_smart_sort_remove_extensions,
        null
    )
    val layoutList = dialogView.findViewById<LinearLayout>(R.id.layoutExtensionList)
    val txtEmpty = dialogView.findViewById<TextView>(R.id.txtEmptyExtensions)

    val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
        .setView(dialogView)
        .create()
    dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

    val inflater = LayoutInflater.from(this)
    fun renderExtensionList() {
        layoutList.removeAllViews()
        if (rule.extensions.isEmpty()) {
            txtEmpty.visibility = View.VISIBLE
            return
        }
        txtEmpty.visibility = View.GONE

        for (ext in rule.extensions.toList()) {
            val row = inflater.inflate(
                if (isTvDevice) R.layout.item_smart_sort_remove_extension_tv else R.layout.item_smart_sort_remove_extension,
                layoutList,
                false
            )
            row.findViewById<TextView>(R.id.txtExtensionName).text = if (ext.startsWith(".")) ext else ".$ext"
            row.findViewById<View>(R.id.btnDeleteExtension).setOnClickListener {
                rule.extensions.remove(ext)
                renderExtensionList()
            }
            layoutList.addView(row)
        }
    }

    renderExtensionList()

    dialogView.findViewById<View>(R.id.btnDone).setOnClickListener {
        dialog.dismiss()
        renderCustomRules(findViewById(R.id.layoutCustomRules), findViewById(R.id.txtCustomRulesEmpty))
    }
    dialog.show()
}
private fun pickCustomRuleFolder(ruleIndex: Int) {
    categoryFolderPickerLauncher.launch(Intent(this, SmartSortActivity::class.java).apply { putExtra(SmartSortActivity.EXTRA_SMART_SORT_CATEGORY_PICKER, true); putExtra(SmartSortActivity.EXTRA_SMART_SORT_CATEGORY_KEY, customRules[ruleIndex].id) })
}
private val categoryFolderPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
    if (result.resultCode == RESULT_OK) {
        val key = result.data?.getStringExtra(SmartSortActivity.RESULT_SELECTED_CATEGORY_KEY) ?: return@registerForActivityResult
        val path = result.data?.getStringExtra(SmartSortActivity.RESULT_SELECTED_CATEGORY_PATH) ?: return@registerForActivityResult
        val sid = result.data?.getStringExtra(SmartSortActivity.RESULT_SELECTED_SHARE_ID)
        customCategoryPaths[key] = path; if (sid != null) customCategoryShareIds[key] = sid
        updateCategoryLabel(key, path)
        renderCustomRules(findViewById(R.id.layoutCustomRules) ?: findViewById(R.id.layoutCustomRules), findViewById(R.id.txtCustomRulesEmpty))
    }
}
private fun updateHint(group: ChipGroup, hintView: TextView, chipHintMap: Map<Int, Int>) {
    val id = group.checkedChipId
    if (id != -1 && id in chipHintMap) { hintView.text = getString(chipHintMap[id]!!); hintView.visibility = View.VISIBLE }
    else hintView.visibility = View.GONE
}
private fun validateConfig(): String? {
    val cs = findViewById<ChipGroup>(R.id.cgScope)
    if (cs.checkedChipId == -1) return getString(R.string.smart_sort_err_scope)
    if (cs.checkedChipId == R.id.chipRecursive) { val cg = findViewById<ChipGroup>(R.id.cgSubfolderMode); if (cg.checkedChipId == -1) return getString(R.string.smart_sort_err_subfolder) }
    if (isCustomMode) {
        if (customRules.isEmpty() || customRules.none { it.enabled }) return getString(R.string.smart_sort_no_rules)
        for (r in customRules) { if (!r.enabled) continue; if (r.description.isBlank()) return getString(R.string.smart_sort_description_empty); if (r.extensions.isEmpty()) return getString(R.string.smart_sort_no_extensions) }
    } else {
        val cm = findViewById<ChipGroup>(R.id.cgSortMode); if (cm.checkedChipId == -1) return getString(R.string.smart_sort_err_mode)
        val has = when (cm.checkedChipId) {
            R.id.chipModeType -> SmartSortCategory.entries.any { cat -> val id = when(cat){SmartSortCategory.PHOTOS->R.id.chkCatPhotos;SmartSortCategory.VIDEOS->R.id.chkCatVideos;SmartSortCategory.AUDIO->R.id.chkCatAudio;SmartSortCategory.DOCUMENTS->R.id.chkCatDocuments;SmartSortCategory.ARCHIVES->R.id.chkCatArchives;SmartSortCategory.APPS->R.id.chkCatApps;SmartSortCategory.EBOOKS->R.id.chkCatEbooks}; findViewById<com.google.android.material.checkbox.MaterialCheckBox>(id)?.isChecked == true }
            R.id.chipModeSize -> SizeTier.entries.any { t -> val id = when(t){SizeTier.TINY->R.id.chkSizeTiny;SizeTier.SMALL->R.id.chkSizeSmall;SizeTier.MEDIUM->R.id.chkSizeMedium;SizeTier.LARGE->R.id.chkSizeLarge;SizeTier.HUGE->R.id.chkSizeHuge}; findViewById<com.google.android.material.checkbox.MaterialCheckBox>(id)?.isChecked == true }
            R.id.chipModeDate -> DatePeriod.entries.any { p -> val id = when(p){DatePeriod.TODAY->R.id.chkDateToday;DatePeriod.THIS_WEEK->R.id.chkDateWeek;DatePeriod.THIS_MONTH->R.id.chkDateMonth;DatePeriod.THIS_YEAR->R.id.chkDateYear;DatePeriod.OLDER->R.id.chkDateOlder}; findViewById<com.google.android.material.checkbox.MaterialCheckBox>(id)?.isChecked == true }
            else -> true
        }
        if (!has) { val io = findViewById<Chip>(R.id.chipIncludeOther)?.isChecked == true; if (!io) return getString(R.string.smart_sort_err_no_category) }
    }
    if (findViewById<ChipGroup>(R.id.cgDuplicateStrategy).checkedChipId == -1) return getString(R.string.smart_sort_err_duplicate)
    if (findViewById<ChipGroup>(R.id.cgExistingFolderStrategy).checkedChipId == -1) return getString(R.string.smart_sort_err_existing)
    return null
}
private fun buildConfig(): SmartSortConfig {
    val isRecursive = findViewById<ChipGroup>(R.id.cgScope).checkedChipId == R.id.chipRecursive
    val flatten = findViewById<ChipGroup>(R.id.cgSubfolderMode).checkedChipId == R.id.chipFlatten
    val dup = when (findViewById<ChipGroup>(R.id.cgDuplicateStrategy).checkedChipId) { R.id.chipDupSkip -> SmartSortConfig.DuplicateStrategy.SKIP; R.id.chipDupRename -> SmartSortConfig.DuplicateStrategy.RENAME; R.id.chipDupOverwrite -> SmartSortConfig.DuplicateStrategy.OVERWRITE; else -> SmartSortConfig.DuplicateStrategy.RENAME }
    val existing = when (findViewById<ChipGroup>(R.id.cgExistingFolderStrategy).checkedChipId) { R.id.chipExistingMerge -> SmartSortConfig.ExistingFolderStrategy.MERGE; R.id.chipExistingSkip -> SmartSortConfig.ExistingFolderStrategy.SKIP; R.id.chipExistingRename -> SmartSortConfig.ExistingFolderStrategy.RENAME; else -> SmartSortConfig.ExistingFolderStrategy.MERGE }
    val share = shareId?.let { SmartSortShareHolder.resolve(it) }
    val parsedPrefix = findViewById<TextInputEditText>(R.id.txtPrefix)?.text?.toString()?.trim() ?: "UFM"
    if (isCustomMode) return SmartSortConfig(sourcePath = folderPath, sortConfigType = SortConfigType.CUSTOM, mode = SmartSortMode.CUSTOM, recursive = isRecursive, flattenSubfolders = flatten, prefix = parsedPrefix, duplicateStrategy = dup, existingFolderStrategy = existing, shareInfo = share, customRules = customRules.filter { it.enabled }.map { it.copy(extensions = it.extensions.toMutableSet()) }, customCategoryPaths = customCategoryPaths.toMap(), customCategoryShareIds = customCategoryShareIds.toMap())
    return SmartSortConfig(sourcePath = folderPath, sortConfigType = SortConfigType.STANDARD, mode = when (findViewById<ChipGroup>(R.id.cgSortMode).checkedChipId) { R.id.chipModeSize -> SmartSortMode.SIZE; R.id.chipModeDate -> SmartSortMode.DATE; else -> SmartSortMode.TYPE }, recursive = isRecursive, flattenSubfolders = flatten, prefix = parsedPrefix, enabledCategories = SmartSortCategory.entries.filter { cat -> val id = when(cat){SmartSortCategory.PHOTOS->R.id.chkCatPhotos;SmartSortCategory.VIDEOS->R.id.chkCatVideos;SmartSortCategory.AUDIO->R.id.chkCatAudio;SmartSortCategory.DOCUMENTS->R.id.chkCatDocuments;SmartSortCategory.ARCHIVES->R.id.chkCatArchives;SmartSortCategory.APPS->R.id.chkCatApps;SmartSortCategory.EBOOKS->R.id.chkCatEbooks}; findViewById<com.google.android.material.checkbox.MaterialCheckBox>(id)?.isChecked == true }.toSet(), enabledSizeTiers = SizeTier.entries.filter { t -> val id = when(t){SizeTier.TINY->R.id.chkSizeTiny;SizeTier.SMALL->R.id.chkSizeSmall;SizeTier.MEDIUM->R.id.chkSizeMedium;SizeTier.LARGE->R.id.chkSizeLarge;SizeTier.HUGE->R.id.chkSizeHuge}; findViewById<com.google.android.material.checkbox.MaterialCheckBox>(id)?.isChecked == true }.toSet(), enabledDatePeriods = DatePeriod.entries.filter { p -> val id = when(p){DatePeriod.TODAY->R.id.chkDateToday;DatePeriod.THIS_WEEK->R.id.chkDateWeek;DatePeriod.THIS_MONTH->R.id.chkDateMonth;DatePeriod.THIS_YEAR->R.id.chkDateYear;DatePeriod.OLDER->R.id.chkDateOlder}; findViewById<com.google.android.material.checkbox.MaterialCheckBox>(id)?.isChecked == true }.toSet(), includeOther = findViewById<Chip>(R.id.chipIncludeOther)?.isChecked == true, duplicateStrategy = dup, existingFolderStrategy = existing, shareInfo = share, customCategoryPaths = customCategoryPaths.toMap(), customCategoryShareIds = customCategoryShareIds.toMap())
}
private fun onSaveButtonClicked() {
    if (savedConfigId != null) {
        MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setTitle(R.string.smart_sort_edit_config)
            .setIcon(R.drawable.ic_edit)
            .setPositiveButton(R.string.smart_sort_save_changes) { _, _ -> showSaveDescriptionDialog(true) }
            .setNeutralButton(R.string.smart_sort_delete_saved) { _, _ ->
                val dialogView = LayoutInflater.from(this).inflate(
                    if (isTvDevice) R.layout.dialog_smart_sort_delete_config_confirm_tv else R.layout.dialog_smart_sort_delete_config_confirm,
                    null
                )
                val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
                    .setView(dialogView)
                    .create()
                dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
                dialogView.findViewById<View>(R.id.btnDeleteConfirm).setOnClickListener {
                    savedConfigId?.let { SmartSortSavedConfigRepository.delete(it) }
                    savedConfigId = null
                    updateSaveIcon()
                    updateSaveButtonState()
                    dialog.dismiss()
                }
                dialogView.findViewById<View>(R.id.btnCancel).setOnClickListener {
                    dialog.dismiss()
                }
                dialog.show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    } else {
        showSaveDescriptionDialog(false)
    }
}
private fun showSaveDescriptionDialog(isEdit: Boolean) {
    val dialogView = LayoutInflater.from(this).inflate(
        if (isTvDevice) R.layout.dialog_smart_sort_save_description_tv else R.layout.dialog_smart_sort_save_description,
        null
    )
    val txtInput = dialogView.findViewById<TextInputEditText>(R.id.txtConfigDescription)
    val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
        .setView(dialogView)
        .create()
    dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

    dialogView.findViewById<View>(R.id.btnSaveConfirm).setOnClickListener {
        val d = txtInput.text?.toString()?.trim() ?: ""
        if (d.isNotEmpty()) {
            saveCurrentConfig(d)
            dialog.dismiss()
        } else {
            dialogView.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.txtConfigDescriptionLayout)?.error = getString(R.string.smart_sort_enter_description)
        }
    }
    dialogView.findViewById<View>(R.id.btnCancel).setOnClickListener {
        dialog.dismiss()
    }
    dialog.show()
}
private fun saveCurrentConfig(description: String) {
    val cfg = buildConfig(); val json = serializeConfigToJson(cfg)
    val newId = savedConfigId ?: java.util.UUID.randomUUID().toString()
    SmartSortSavedConfigRepository.save(SmartSortSavedConfig(id = newId, folderPath = folderPath, description = description, configJson = json.toString()))
    savedConfigId = newId; updateSaveIcon()
}
private fun serializeConfigToJson(config: SmartSortConfig): org.json.JSONObject = org.json.JSONObject().apply {
    put("sourcePath", config.sourcePath); put("sortConfigType", config.sortConfigType.name); put("mode", config.mode.name); put("recursive", config.recursive); put("flattenSubfolders", config.flattenSubfolders)
    put("prefix", config.prefix); put("enabledCategories", org.json.JSONArray(config.enabledCategories.map { it.name })); put("enabledSizeTiers", org.json.JSONArray(config.enabledSizeTiers.map { it.name })); put("enabledDatePeriods", org.json.JSONArray(config.enabledDatePeriods.map { it.name }))
    put("includeOther", config.includeOther); put("duplicateStrategy", config.duplicateStrategy.name); put("existingFolderStrategy", config.existingFolderStrategy.name)
    if (config.shareInfo != null) put("shareId", config.shareInfo!!.id)
    if (shareId != null) put("sourceShareId", shareId)
    put("customCategoryPaths", org.json.JSONObject(config.customCategoryPaths)); put("customCategoryShareIds", org.json.JSONObject(config.customCategoryShareIds))
    put("customRules", org.json.JSONArray(config.customRules.map { r -> org.json.JSONObject().apply { put("id", r.id); put("description", r.description); put("extensions", org.json.JSONArray(r.extensions.toList())); put("enabled", r.enabled); if (r.customFolderPath != null) put("customFolderPath", r.customFolderPath); if (r.customFolderShareId != null) put("customFolderShareId", r.customFolderShareId) } }))
}
private fun loadSavedConfig(saved: SmartSortSavedConfig) {
    try {
        savedConfigId = saved.id
        val j = org.json.JSONObject(saved.configJson)
        val sid = if (j.has("sourceShareId") && !j.isNull("sourceShareId")) j.getString("sourceShareId") else null
        if (sid != null) shareId = sid

        val recursive = j.optBoolean("recursive", false)
        findViewById<ChipGroup>(R.id.cgScope)?.check(if (recursive) R.id.chipRecursive else R.id.chipRootOnly)
        val flatten = j.optBoolean("flattenSubfolders", true)
        if (recursive) {
            findViewById<LinearLayout>(R.id.layoutRecursiveOptions)?.visibility = View.VISIBLE
            findViewById<ChipGroup>(R.id.cgSubfolderMode)?.check(if (flatten) R.id.chipFlatten else R.id.chipPreserve)
        }
        when (j.optString("duplicateStrategy", "RENAME")) {
            "SKIP" -> findViewById<ChipGroup>(R.id.cgDuplicateStrategy)?.check(R.id.chipDupSkip)
            "RENAME" -> findViewById<ChipGroup>(R.id.cgDuplicateStrategy)?.check(R.id.chipDupRename)
            "OVERWRITE" -> findViewById<ChipGroup>(R.id.cgDuplicateStrategy)?.check(R.id.chipDupOverwrite)
        }
        when (j.optString("existingFolderStrategy", "MERGE")) {
            "MERGE" -> findViewById<ChipGroup>(R.id.cgExistingFolderStrategy)?.check(R.id.chipExistingMerge)
            "SKIP" -> findViewById<ChipGroup>(R.id.cgExistingFolderStrategy)?.check(R.id.chipExistingSkip)
            "RENAME" -> findViewById<ChipGroup>(R.id.cgExistingFolderStrategy)?.check(R.id.chipExistingRename)
        }

        val isCustom = j.optString("sortConfigType", "STANDARD") == "CUSTOM"
        findViewById<ChipGroup>(R.id.cgSortType)?.check(if (isCustom) R.id.chipSortCustom else R.id.chipSortStandard)
        if (!isCustom) {
            val m = SmartSortMode.valueOf(j.optString("mode", SmartSortMode.TYPE.name))
            findViewById<ChipGroup>(R.id.cgSortMode)?.check(when(m){SmartSortMode.TYPE->R.id.chipModeType;SmartSortMode.SIZE->R.id.chipModeSize;SmartSortMode.DATE->R.id.chipModeDate;SmartSortMode.CUSTOM->R.id.chipModeType})
            j.optJSONArray("enabledCategories")?.let { a -> val s = mutableSetOf<String>(); for (i in 0 until a.length()) s.add(a.getString(i)); SmartSortCategory.entries.forEach { c -> val id = when(c){SmartSortCategory.PHOTOS->R.id.chkCatPhotos;SmartSortCategory.VIDEOS->R.id.chkCatVideos;SmartSortCategory.AUDIO->R.id.chkCatAudio;SmartSortCategory.DOCUMENTS->R.id.chkCatDocuments;SmartSortCategory.ARCHIVES->R.id.chkCatArchives;SmartSortCategory.APPS->R.id.chkCatApps;SmartSortCategory.EBOOKS->R.id.chkCatEbooks}; findViewById<com.google.android.material.checkbox.MaterialCheckBox>(id)?.isChecked = c.name in s } }
            j.optJSONArray("enabledSizeTiers")?.let { a -> val s = mutableSetOf<String>(); for (i in 0 until a.length()) s.add(a.getString(i)); SizeTier.entries.forEach { t -> val id = when(t){SizeTier.TINY->R.id.chkSizeTiny;SizeTier.SMALL->R.id.chkSizeSmall;SizeTier.MEDIUM->R.id.chkSizeMedium;SizeTier.LARGE->R.id.chkSizeLarge;SizeTier.HUGE->R.id.chkSizeHuge}; findViewById<com.google.android.material.checkbox.MaterialCheckBox>(id)?.isChecked = t.name in s } }
            j.optJSONArray("enabledDatePeriods")?.let { a -> val s = mutableSetOf<String>(); for (i in 0 until a.length()) s.add(a.getString(i)); DatePeriod.entries.forEach { p -> val id = when(p){DatePeriod.TODAY->R.id.chkDateToday;DatePeriod.THIS_WEEK->R.id.chkDateWeek;DatePeriod.THIS_MONTH->R.id.chkDateMonth;DatePeriod.THIS_YEAR->R.id.chkDateYear;DatePeriod.OLDER->R.id.chkDateOlder}; findViewById<com.google.android.material.checkbox.MaterialCheckBox>(id)?.isChecked = p.name in s } }
            findViewById<Chip>(R.id.chipIncludeOther)?.isChecked = j.optBoolean("includeOther", false)
        }
        findViewById<TextInputEditText>(R.id.txtPrefix)?.setText(j.optString("prefix", "UFM"))
        j.optJSONObject("customCategoryPaths")?.let { o -> customCategoryPaths.clear(); for (k in o.keys()) customCategoryPaths[k] = o.getString(k) }
        j.optJSONObject("customCategoryShareIds")?.let { o -> customCategoryShareIds.clear(); for (k in o.keys()) customCategoryShareIds[k] = o.getString(k) }
        j.optJSONArray("customRules")?.let { a ->
            customRules.clear()
            for (i in 0 until a.length()) {
                val r = a.getJSONObject(i)
                val exts = mutableSetOf<String>()
                r.optJSONArray("extensions")?.let { e -> for (j in 0 until e.length()) exts.add(e.getString(j)) }
                val customFolderPath = if (r.has("customFolderPath") && !r.isNull("customFolderPath")) r.getString("customFolderPath") else null
                val customFolderShareId = if (r.has("customFolderShareId") && !r.isNull("customFolderShareId")) r.getString("customFolderShareId") else null
                val rule = SmartSortCustomRule(
                    id = r.optString("id", java.util.UUID.randomUUID().toString()),
                    description = r.optString("description", ""),
                    extensions = exts,
                    enabled = r.optBoolean("enabled", true),
                    customFolderPath = customFolderPath,
                    customFolderShareId = customFolderShareId
                )
                if (rule.customFolderPath != null) customCategoryPaths[rule.id] = rule.customFolderPath!!
                if (rule.customFolderShareId != null) customCategoryShareIds[rule.id] = rule.customFolderShareId!!
                customRules.add(rule)
            }
            renderCustomRules(findViewById(R.id.layoutCustomRules) ?: findViewById(R.id.layoutCustomRules), findViewById(R.id.txtCustomRulesEmpty))
        }
    } catch (_: Exception) {}
}
private fun updateSaveButtonState() {
    val btn = findViewById<ImageView>(R.id.btnSaveConfig) ?: return
    val en = validateConfig() == null
    btn.alpha = if (en || savedConfigId != null) 1.0f else 0.4f; btn.isEnabled = en || savedConfigId != null
}
private fun updateSaveIcon() {
    val btn = findViewById<ImageView>(R.id.btnSaveConfig) ?: return
    if (savedConfigId != null) { btn.setImageResource(R.drawable.ic_edit); btn.alpha = 1.0f; btn.isEnabled = true }
    else { btn.setImageResource(R.drawable.ic_save); btn.alpha = 0.4f; btn.isEnabled = false }
}
    private fun showValidationErrorDialog(errorMsg: String) {
        val dialogView = LayoutInflater.from(this).inflate(
            if (isTvDevice) R.layout.dialog_smart_sort_validation_error_tv else R.layout.dialog_smart_sort_validation_error,
            null
        )
        dialogView.findViewById<TextView>(R.id.txtErrorMessage).text = errorMsg
        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialogView.findViewById<View>(R.id.btnDone).setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun setupTvFocus() {
    val yb = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_button_focused_yellow))
    val wt = getColor(R.color.tv_text_primary); val yt = getColor(R.color.tv_button_focused_yellow_text)
    listOf(R.id.chipSortStandard, R.id.chipSortCustom, R.id.chipRootOnly, R.id.chipRecursive, R.id.chipFlatten, R.id.chipPreserve, R.id.chipModeType, R.id.chipModeSize, R.id.chipModeDate, R.id.chipIncludeOther, R.id.chipDupSkip, R.id.chipDupRename, R.id.chipDupOverwrite, R.id.chipExistingMerge, R.id.chipExistingSkip, R.id.chipExistingRename).forEach { id -> val chip = findViewById<Chip>(id) ?: return@forEach; chip.setOnFocusChangeListener { _, hf -> if (hf) { chip.chipBackgroundColor = yb; chip.setTextColor(yt) } else { chip.chipBackgroundColor = null; chip.setTextColor(wt) } } }
    (categoryButtonKeys.values.toList() + listOf(R.id.btnAddRule, R.id.btnPreview, R.id.btnStartSort)).forEach { id -> val btn = findViewById<MaterialButton>(id) ?: return@forEach; val dbg = btn.backgroundTintList; btn.setOnFocusChangeListener { _, hf -> if (hf) { btn.backgroundTintList = yb; btn.setTextColor(yt) } else { btn.backgroundTintList = dbg; btn.setTextColor(wt) } } }
    findViewById<ImageView>(R.id.btnSaveConfig)?.setOnFocusChangeListener { v, hf -> v.alpha = if (hf) 1.0f else 0.6f }
    listOf(R.id.btnPreview, R.id.btnStartSort).forEach { id -> val btn = findViewById<MaterialButton>(id) ?: return@forEach; val dbg = btn.backgroundTintList; btn.setOnFocusChangeListener { _, hf -> if (hf) { btn.backgroundTintList = yb; btn.setTextColor(yt) } else { btn.backgroundTintList = dbg; btn.setTextColor(wt) } } }
    fixCategoryFocus()
}
}
