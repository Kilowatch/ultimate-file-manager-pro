package za.kilowatch.ultimatefilemanager.smartsort

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.R

class SmartSortSheet : BottomSheetDialogFragment() {

    private var folderPath: String = ""
    private var shareId: String? = null
    private val engine = SmartSortEngine()
    private var currentMode = SmartSortMode.TYPE
    private val customCategoryPaths = mutableMapOf<String, String>()
    private val customCategoryShareIds = mutableMapOf<String, String>()
    private var sheetRootView: View? = null
    private var isCustomMode = false
    private val sheetCustomRules = mutableListOf<SmartSortCustomRule>()

    private val categoryFolderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val key = result.data?.getStringExtra(SmartSortActivity.RESULT_SELECTED_CATEGORY_KEY) ?: return@registerForActivityResult
            val path = result.data?.getStringExtra(SmartSortActivity.RESULT_SELECTED_CATEGORY_PATH) ?: return@registerForActivityResult
            val sId = result.data?.getStringExtra(SmartSortActivity.RESULT_SELECTED_SHARE_ID)
            customCategoryPaths[key] = path
            if (sId != null) customCategoryShareIds[key] = sId
            val v = sheetRootView ?: return@registerForActivityResult
            updateSheetCategoryLabel(v, key, path)
            renderSheetCustomRules(v)
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): android.app.Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            if (bottomSheet != null) {
                val behavior = BottomSheetBehavior.from(bottomSheet)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
            }
        }
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.sheet_smart_sort, container, false)
        sheetRootView = view
        folderPath = arguments?.getString(ARG_FOLDER_PATH) ?: ""
        shareId = arguments?.getString(ARG_SHARE_ID)
        initViews(view)
        return view
    }

    private fun initViews(view: View) {
        val txtFolder = view.findViewById<TextView>(R.id.txtSelectedFolder)
        txtFolder.text = folderPath

        val cgScope = view.findViewById<ChipGroup>(R.id.cgScope)
        val chipRecursive = view.findViewById<Chip>(R.id.chipRecursive)
        val layoutRecursiveOptions = view.findViewById<LinearLayout>(R.id.layoutRecursiveOptions)

        chipRecursive.setOnCheckedChangeListener { _, isChecked ->
            layoutRecursiveOptions.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        val cgSortMode = view.findViewById<ChipGroup>(R.id.cgSortMode)
        val layoutTypeCategories = view.findViewById<LinearLayout>(R.id.layoutTypeCategories)
        val layoutSizeCategories = view.findViewById<LinearLayout>(R.id.layoutSizeCategories)
        val layoutDateCategories = view.findViewById<LinearLayout>(R.id.layoutDateCategories)

        cgSortMode.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isNotEmpty()) {
                val id = checkedIds[0]
                    layoutTypeCategories.visibility = View.GONE
                    layoutSizeCategories.visibility = View.GONE
                    layoutDateCategories.visibility = View.GONE
                when (id) {
                    R.id.chipModeType -> {
                        currentMode = SmartSortMode.TYPE
                        layoutTypeCategories.visibility = View.VISIBLE
                    }
                    R.id.chipModeSize -> {
                        currentMode = SmartSortMode.SIZE
                        layoutSizeCategories.visibility = View.VISIBLE
                    }
                    R.id.chipModeDate -> {
                        currentMode = SmartSortMode.DATE
                        layoutDateCategories.visibility = View.VISIBLE
                    }
                }
            }
        }

        val cgSortType = view.findViewById<ChipGroup>(R.id.cgSortType)
        val layoutStandardOptions = view.findViewById<LinearLayout>(R.id.layoutStandardOptions)
        val layoutCustomOptions = view.findViewById<LinearLayout>(R.id.layoutCustomOptions)
        val btnAddRule = view.findViewById<MaterialButton>(R.id.btnAddRule)
        val txtCustomRulesEmpty = view.findViewById<TextView>(R.id.txtCustomRulesEmpty)

        cgSortType?.setOnCheckedStateChangeListener { _, checkedIds ->
            isCustomMode = checkedIds.isNotEmpty() && checkedIds[0] == R.id.chipSortCustom
            layoutStandardOptions?.visibility = if (isCustomMode) View.GONE else View.VISIBLE
            layoutCustomOptions?.visibility = if (isCustomMode) View.VISIBLE else View.GONE
            if (isCustomMode) renderSheetCustomRules(view)
        }

        btnAddRule?.setOnClickListener { showSheetAddRuleDialog(view) }

        wireSheetFolderButtons(view)
        val txtPrefix = view.findViewById<TextInputEditText>(R.id.txtPrefix)
        val chipIncludeOther = view.findViewById<Chip>(R.id.chipIncludeOther)

        val cgDuplicate = view.findViewById<ChipGroup>(R.id.cgDuplicateStrategy)
        val cgExisting = view.findViewById<ChipGroup>(R.id.cgExistingFolderStrategy)

        val btnPreview = view.findViewById<MaterialButton>(R.id.btnPreview)
        val btnStart = view.findViewById<MaterialButton>(R.id.btnStartSort)

        val layoutPreview = view.findViewById<LinearLayout>(R.id.layoutPreviewResults)
        val txtPreview = view.findViewById<TextView>(R.id.txtPreviewContent)
        val layoutProgress = view.findViewById<LinearLayout>(R.id.layoutProgress)
        val txtProgress = view.findViewById<TextView>(R.id.txtProgressLabel)
        val progressBar = view.findViewById<ProgressBar>(R.id.progressBar)
        val layoutResults = view.findViewById<LinearLayout>(R.id.layoutResults)
        val txtResult = view.findViewById<TextView>(R.id.txtResultSummary)
        val btnUndo = view.findViewById<MaterialButton>(R.id.btnUndoSort)
        val btnHistory = view.findViewById<MaterialButton>(R.id.btnHistory)
        var lastResult: SmartSortResult? = null

        btnPreview.setOnClickListener {
            val config = buildConfig(view)
            layoutPreview.visibility = View.GONE
            lifecycleScope.launch {
                val preview = withContext(Dispatchers.IO) {
                    engine.preview(folderPath, config)
                }
                val sb = StringBuilder()
                preview.categoryCounts.forEach { (cat, count) ->
                    sb.appendLine("$cat: $count files")
                }
                sb.appendLine()
                sb.appendLine("Total: ${preview.totalFiles} files")
                if (preview.conflicts.isNotEmpty()) {
                    sb.appendLine("Conflicts: ${preview.conflicts.size}")
                }
                txtPreview.text = sb.toString()
                layoutPreview.visibility = View.VISIBLE
            }
        }

        btnStart.setOnClickListener {
            if (isCustomMode && (sheetCustomRules.isEmpty() || sheetCustomRules.none { it.enabled })) {
                com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext(), R.style.UFM_Dialog)
                    .setMessage(R.string.smart_sort_no_rules).setPositiveButton(R.string.got_it_1, null).show()
                return@setOnClickListener
            }
            val config = buildConfig(view)
            layoutProgress.visibility = View.VISIBLE
            layoutResults.visibility = View.GONE
            layoutPreview.visibility = View.GONE
            btnPreview.isEnabled = false
            btnStart.isEnabled = false

            lifecycleScope.launch {
                val result = engine.execute(folderPath, config) { fileName, current, total ->
                    requireActivity().runOnUiThread {
                        txtProgress.text = "Moving: $fileName ($current/$total)"
                        progressBar.progress = (current * 100) / total
                    }
                }
                layoutProgress.visibility = View.GONE
                layoutResults.visibility = View.VISIBLE
                btnPreview.isEnabled = true
                btnStart.isEnabled = true

                lastResult = result

                if (result.movedCount > 0) {
                    SmartSortHistoryManager.addEntry(
                        folderPath = folderPath,
                        movedCount = result.movedCount,
                        skippedCount = result.skippedCount,
                        failedCount = result.failedCount
                    )
                }

                val summary = buildString {
                    appendLine("Moved: ${result.movedCount}")
                    appendLine("Skipped: ${result.skippedCount}")
                    if (result.failedCount > 0) {
                        appendLine("Failed: ${result.failedCount}")
                    }
                }
                txtResult.text = summary
            }
        }

        btnUndo.setOnClickListener {
            val res = lastResult ?: return@setOnClickListener
            val manifest = res.manifest ?: return@setOnClickListener
            btnUndo.isEnabled = false
            layoutResults.visibility = View.GONE
            layoutProgress.visibility = View.VISIBLE
            txtProgress.text = getString(R.string.smart_sort_undoing)
            progressBar.isIndeterminate = true

            lifecycleScope.launch {
                val config = buildConfig(view)
                engine.undo(folderPath, manifest, config)
                SmartSortHistoryManager.removeEntryForPath(folderPath)
                progressBar.isIndeterminate = false
                layoutProgress.visibility = View.GONE
                txtResult.text = getString(R.string.smart_sort_undone)
                layoutResults.visibility = View.VISIBLE
            }
        }

        btnHistory.setOnClickListener {
            SmartSortHistorySheet.newInstance(folderPath = folderPath).show(requireActivity().supportFragmentManager, SmartSortHistorySheet.TAG)
        }
    }

    private fun buildConfig(view: View): SmartSortConfig {
        val cgScope = view.findViewById<ChipGroup>(R.id.cgScope)
        val isRecursive = cgScope.checkedChipId == R.id.chipRecursive

        val cgSubfolder = view.findViewById<ChipGroup>(R.id.cgSubfolderMode)
        val flatten = cgSubfolder.checkedChipId == R.id.chipFlatten

        val cgDuplicate = view.findViewById<ChipGroup>(R.id.cgDuplicateStrategy)
        val dupStrategy = when (cgDuplicate.checkedChipId) {
            R.id.chipDupSkip -> SmartSortConfig.DuplicateStrategy.SKIP
            R.id.chipDupRename -> SmartSortConfig.DuplicateStrategy.RENAME
            R.id.chipDupOverwrite -> SmartSortConfig.DuplicateStrategy.OVERWRITE
            else -> SmartSortConfig.DuplicateStrategy.SKIP
        }

        val cgExisting = view.findViewById<ChipGroup>(R.id.cgExistingFolderStrategy)
        val existingStrategy = when (cgExisting.checkedChipId) {
            R.id.chipExistingMerge -> SmartSortConfig.ExistingFolderStrategy.MERGE
            R.id.chipExistingSkip -> SmartSortConfig.ExistingFolderStrategy.SKIP
            R.id.chipExistingRename -> SmartSortConfig.ExistingFolderStrategy.RENAME
            else -> SmartSortConfig.ExistingFolderStrategy.MERGE
        }

        val share = shareId?.let { SmartSortShareHolder.resolve(it) }

        if (isCustomMode) {
            return SmartSortConfig(
                sourcePath = folderPath,
                sortConfigType = SortConfigType.CUSTOM,
                mode = SmartSortMode.CUSTOM,
                recursive = isRecursive,
                flattenSubfolders = flatten,
                duplicateStrategy = dupStrategy,
                existingFolderStrategy = existingStrategy,
                shareInfo = share,
                customRules = sheetCustomRules.filter { it.enabled }.map { it.copy(extensions = it.extensions.toMutableSet()) },
                customCategoryPaths = customCategoryPaths.toMap(),
                customCategoryShareIds = customCategoryShareIds.toMap()
            )
        }

        val enabledCats = SmartSortCategory.entries.filter { cat ->
            val chipId = when (cat) {
                SmartSortCategory.PHOTOS -> R.id.chipCatPhotos
                SmartSortCategory.VIDEOS -> R.id.chipCatVideos
                SmartSortCategory.AUDIO -> R.id.chipCatAudio
                SmartSortCategory.DOCUMENTS -> R.id.chipCatDocuments
                SmartSortCategory.ARCHIVES -> R.id.chipCatArchives
                SmartSortCategory.APPS -> R.id.chipCatApps
                SmartSortCategory.EBOOKS -> R.id.chipCatEbooks
                else -> null
            }
            chipId != null && view.findViewById<Chip>(chipId)?.isChecked == true
        }.toSet()

        val enabledSizes = SizeTier.entries.filter { tier ->
            val chipId = when (tier) {
                SizeTier.TINY -> R.id.chipSizeTiny
                SizeTier.SMALL -> R.id.chipSizeSmall
                SizeTier.MEDIUM -> R.id.chipSizeMedium
                SizeTier.LARGE -> R.id.chipSizeLarge
                SizeTier.HUGE -> R.id.chipSizeHuge
            }
            view.findViewById<Chip>(chipId)?.isChecked == true
        }.toSet()

        val enabledDates = DatePeriod.entries.filter { period ->
            val chipId = when (period) {
                DatePeriod.TODAY -> R.id.chipDateToday
                DatePeriod.THIS_WEEK -> R.id.chipDateWeek
                DatePeriod.THIS_MONTH -> R.id.chipDateMonth
                DatePeriod.THIS_YEAR -> R.id.chipDateYear
                DatePeriod.OLDER -> R.id.chipDateOlder
            }
            view.findViewById<Chip>(chipId)?.isChecked == true
        }.toSet()

        val txtPrefix = view.findViewById<TextInputEditText>(R.id.txtPrefix)
        val prefix = txtPrefix.text?.toString()?.trim() ?: "UFM"

        val chipIncludeOther = view.findViewById<Chip>(R.id.chipIncludeOther)
        val includeOther = chipIncludeOther.isChecked

        val cgSortMode = view.findViewById<ChipGroup>(R.id.cgSortMode)
        val mode = when (cgSortMode.checkedChipId) {
            R.id.chipModeSize -> SmartSortMode.SIZE
            R.id.chipModeDate -> SmartSortMode.DATE
            else -> SmartSortMode.TYPE
        }

        return SmartSortConfig(
            sourcePath = folderPath,
            sortConfigType = SortConfigType.STANDARD,
            mode = mode,
            recursive = isRecursive,
            flattenSubfolders = flatten,
            prefix = prefix,
            enabledCategories = enabledCats,
            enabledSizeTiers = enabledSizes,
            enabledDatePeriods = enabledDates,
            includeOther = includeOther,
            duplicateStrategy = dupStrategy,
            existingFolderStrategy = existingStrategy,
            shareInfo = share,
            customCategoryPaths = customCategoryPaths.toMap(),
            customCategoryShareIds = customCategoryShareIds.toMap()
        )
    }

    private val sheetCategoryButtonKeys = mapOf(
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

    private val sheetResetButtonIds = mapOf(
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

    private val sheetFolderLabelIds = mapOf(
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

    private val sheetCategoryDisplayNames = mapOf(
        "PHOTOS" to R.string.smart_sort_category_photos,
        "VIDEOS" to R.string.smart_sort_category_videos,
        "AUDIO" to R.string.smart_sort_category_audio,
        "DOCUMENTS" to R.string.smart_sort_category_documents,
        "ARCHIVES" to R.string.smart_sort_category_archives,
        "APPS" to R.string.smart_sort_category_apps,
        "EBOOKS" to R.string.smart_sort_category_ebooks,
        "TINY" to R.string.smart_sort_size_tiny,
        "SMALL" to R.string.smart_sort_size_small,
        "MEDIUM" to R.string.smart_sort_size_medium,
        "LARGE" to R.string.smart_sort_size_large,
        "HUGE" to R.string.smart_sort_size_huge,
        "TODAY" to R.string.smart_sort_date_today,
        "THIS_WEEK" to R.string.smart_sort_date_this_week,
        "THIS_MONTH" to R.string.smart_sort_date_this_month,
        "THIS_YEAR" to R.string.smart_sort_date_this_year,
        "OLDER" to R.string.smart_sort_date_older,
        "OTHER" to R.string.smart_sort_category_other
    )

    private fun wireSheetFolderButtons(view: View) {
        sheetCategoryButtonKeys.forEach { (key, btnId) ->
            view.findViewById<View>(btnId)?.setOnClickListener {
                val intent = Intent(requireContext(), SmartSortActivity::class.java).apply {
                    putExtra(SmartSortActivity.EXTRA_SMART_SORT_CATEGORY_PICKER, true)
                    putExtra(SmartSortActivity.EXTRA_SMART_SORT_CATEGORY_KEY, key)
                }
                categoryFolderPickerLauncher.launch(intent)
            }
        }
        sheetResetButtonIds.forEach { (key, btnId) ->
            view.findViewById<View>(btnId)?.setOnClickListener {
                customCategoryPaths.remove(key)
                customCategoryShareIds.remove(key)
                revertSheetCategoryLabel(view, key)
            }
        }
    }

    private fun updateSheetCategoryLabel(view: View, key: String, path: String) {
        val labelId = sheetFolderLabelIds[key] ?: return
        val label = view.findViewById<TextView>(labelId) ?: return
        label.text = getString(R.string.smart_sort_folder_label, path)
        sheetResetButtonIds[key]?.let { view.findViewById<View>(it)?.visibility = View.VISIBLE }
    }

    private fun revertSheetCategoryLabel(view: View, key: String) {
        val prefix = view.findViewById<TextInputEditText>(R.id.txtPrefix)?.text?.toString()?.trim() ?: "UFM"
        val displayNameRes = sheetCategoryDisplayNames[key] ?: return
        val labelId = sheetFolderLabelIds[key] ?: return
        val label = view.findViewById<TextView>(labelId) ?: return
        val folderText = if (prefix.isBlank()) "" else "$prefix "
        label.text = getString(R.string.smart_sort_folder_label, "$folderText${getString(displayNameRes)}")
        sheetResetButtonIds[key]?.let { view.findViewById<View>(it)?.visibility = View.GONE }
    }

    private fun renderSheetCustomRules(view: View) {
        val container = view.findViewById<LinearLayout>(R.id.layoutCustomRules) ?: return
        val emptyText = view.findViewById<TextView>(R.id.txtCustomRulesEmpty)
        container.removeAllViews()
        if (sheetCustomRules.isEmpty()) {
            emptyText?.visibility = View.VISIBLE
            return
        }
        emptyText?.visibility = View.GONE
        val prefix = view.findViewById<TextInputEditText>(R.id.txtPrefix)?.text?.toString()?.trim() ?: "UFM"
        for ((index, rule) in sheetCustomRules.withIndex()) {
            val card = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 16) }
                setPadding(16, 12, 16, 12)
            }
            val chkEnabled = com.google.android.material.checkbox.MaterialCheckBox(requireContext()).apply {
                text = getString(R.string.smart_sort_rule_description_label, rule.description)
                isChecked = rule.enabled
                setOnCheckedChangeListener { _, isChecked ->
                    sheetCustomRules[index] = rule.copy(enabled = isChecked)
                }
            }
            card.addView(chkEnabled)

            val extText = if (rule.extensions.isEmpty()) getString(R.string.smart_sort_no_extensions_added)
            else getString(R.string.smart_sort_extensions, rule.extensions.joinToString(", "))
            val txtExtensions = android.widget.TextView(requireContext()).apply {
                text = extText
                textSize = 12f
                setPadding(48, 0, 0, 0)
            }
            card.addView(txtExtensions)

            val folderName = customCategoryPaths[rule.id] ?: "$prefix ${rule.description}"
            val txtFolder = android.widget.TextView(requireContext()).apply {
                text = getString(R.string.smart_sort_folder_label, folderName)
                textSize = 12f
                setPadding(48, 4, 0, 0)
            }
            card.addView(txtFolder)

            val btnExtRow = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL; setPadding(48, 4, 0, 0) }
            val btnAddExt = com.google.android.material.button.MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(8, 0, 8, 0) }; text = getString(R.string.smart_sort_add_extension); textSize = 12f; minimumHeight = 0; setPadding(8, 2, 8, 2); setOnClickListener { showSheetAddExtensionDialog(view, index) } }
            btnExtRow.addView(btnAddExt)
            if (rule.extensions.isNotEmpty()) {
                val btnRemoveExt = com.google.android.material.button.MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(8, 0, 8, 0) }; text = getString(R.string.smart_sort_remove_extension); textSize = 12f; minimumHeight = 0; setPadding(8, 2, 8, 2); setOnClickListener { showSheetRemoveExtensionsDialog(view, index) } }
                btnExtRow.addView(btnRemoveExt)
            }
            card.addView(btnExtRow)

            val btnFolderRow = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL; setPadding(48, 4, 0, 0) }
            val btnSelectFolder = com.google.android.material.button.MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(8, 0, 8, 0) }; text = getString(R.string.smart_sort_select_folder); textSize = 12f; minimumHeight = 0; setPadding(8, 2, 8, 2); setOnClickListener { pickSheetCustomRuleFolder(index) } }
            btnFolderRow.addView(btnSelectFolder)
            if (customCategoryPaths.containsKey(rule.id) || rule.customFolderPath != null) {
                btnFolderRow.addView(com.google.android.material.button.MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(8, 0, 8, 0) }; text = getString(R.string.smart_sort_set_default); textSize = 12f; minimumHeight = 0; setPadding(8, 2, 8, 2); setOnClickListener { customCategoryPaths.remove(rule.id); customCategoryShareIds.remove(rule.id); renderSheetCustomRules(view) } })
            }
            card.addView(btnFolderRow)

            val btnActionRow = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL; setPadding(48, 4, 0, 0) }
            val btnEdit = com.google.android.material.button.MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(8, 0, 8, 0) }; text = getString(R.string.smart_sort_edit_rule); textSize = 12f; minimumHeight = 0; setPadding(8, 2, 8, 2); setOnClickListener { showSheetEditRuleDialog(view, index) } }
            btnActionRow.addView(btnEdit)
            val btnDelete = com.google.android.material.button.MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(8, 0, 8, 0) }; text = getString(R.string.smart_sort_delete_rule); textSize = 12f; minimumHeight = 0; setPadding(8, 2, 8, 2); setOnClickListener { com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext(), R.style.UFM_Dialog).setTitle(R.string.smart_sort_delete_rule_confirm).setIcon(R.drawable.ic_delete).setPositiveButton(R.string.smart_sort_delete_rule) { _, _ -> sheetCustomRules.removeAt(index); customCategoryPaths.remove(rule.id); customCategoryShareIds.remove(rule.id); renderSheetCustomRules(view) }.setNegativeButton(R.string.cancel, null).show() } }
            btnActionRow.addView(btnDelete)
            card.addView(btnActionRow)
            container.addView(card)
        }
    }

    private fun showSheetAddRuleDialog(view: View) {
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.smart_sort_rule_description)
            setPadding(24, 16, 24, 16)
        }
        val dialogContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL; setPadding(24, 8, 24, 8)
            addView(input)
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext(), R.style.UFM_Dialog)
            .setTitle(R.string.smart_sort_add_rule).setView(dialogContainer)
            .setPositiveButton(R.string.smart_sort_add_rule) { d, _ ->
                val desc = input.text?.toString()?.trim() ?: ""
                if (desc.isEmpty()) {
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext(), R.style.UFM_Dialog)
                        .setMessage(R.string.smart_sort_description_empty).setPositiveButton(R.string.got_it_1, null).show()
                    return@setPositiveButton
                }
                sheetCustomRules.add(SmartSortCustomRule(description = desc))
                renderSheetCustomRules(view)
                d.dismiss()
            }.setNegativeButton(R.string.cancel, null).show()
    }

    private fun showSheetEditRuleDialog(view: View, index: Int) {
        val rule = sheetCustomRules[index]
        val input = EditText(requireContext()).apply {
            setText(rule.description)
            hint = getString(R.string.smart_sort_rule_description)
            setPadding(24, 16, 24, 16)
        }
        val dialogContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL; setPadding(24, 8, 24, 8)
            addView(input)
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext(), R.style.UFM_Dialog)
            .setTitle(R.string.smart_sort_edit_rule).setView(dialogContainer)
            .setPositiveButton(R.string.smart_sort_edit_rule) { d, _ ->
                val desc = input.text?.toString()?.trim() ?: ""
                if (desc.isEmpty()) {
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext(), R.style.UFM_Dialog)
                        .setMessage(R.string.smart_sort_description_empty).setPositiveButton(R.string.got_it_1, null).show()
                    return@setPositiveButton
                }
                sheetCustomRules[index] = rule.copy(description = desc)
                renderSheetCustomRules(view)
                d.dismiss()
            }.setNegativeButton(R.string.cancel, null).show()
    }

    private fun showSheetAddExtensionDialog(view: View, ruleIndex: Int) {
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.smart_sort_extension_empty)
            setPadding(24, 16, 24, 16)
        }
        val errorText = android.widget.TextView(requireContext()).apply {
            textSize = 12f; visibility = View.GONE
        }
        val dialogContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL; setPadding(24, 8, 24, 8)
            addView(input); addView(errorText)
        }
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext(), R.style.UFM_Dialog)
            .setTitle(R.string.smart_sort_add_extension).setView(dialogContainer)
            .setPositiveButton(R.string.smart_sort_add_extension) { d, _ ->
                val ext = input.text?.toString()?.trim()?.lowercase() ?: ""
                if (ext.isEmpty()) {
                    errorText.text = getString(R.string.smart_sort_extension_empty)
                    errorText.visibility = View.VISIBLE; return@setPositiveButton
                }
                for ((otherIdx, other) in sheetCustomRules.withIndex()) {
                    if (otherIdx != ruleIndex && ext in other.extensions) {
                        d.dismiss()
                        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext(), R.style.UFM_Dialog).setTitle(getString(R.string.smart_sort_extension_already_used, ext, other.description)).setPositiveButton(R.string.got_it_1, null).show()
                        return@setPositiveButton
                    }
                }
                sheetCustomRules[ruleIndex].extensions.add(ext)
                d.dismiss(); renderSheetCustomRules(view)
            }.setNegativeButton(R.string.cancel, null).create()
        dialog.show()
    }

    private fun showSheetRemoveExtensionsDialog(view: View, ruleIndex: Int) {
        val rule = sheetCustomRules[ruleIndex]
        val dialogView = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 16, 24, 8) }
        for (ext in rule.extensions.toList()) {
            val card = com.google.android.material.card.MaterialCardView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 16) }
                radius = 16f
                strokeWidth = 0
                setCardBackgroundColor(android.graphics.Color.TRANSPARENT); setBackgroundResource(R.drawable.bg_glass_card_dark)
            }
            val row = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(32, 24, 32, 24) }
            row.addView(TextView(requireContext()).apply { text = ext; textSize = 20f; setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.mobile_text_primary)); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
            val trash = ImageView(requireContext()).apply { layoutParams = LinearLayout.LayoutParams(56, 56); setImageResource(R.drawable.ic_delete); imageTintList = android.content.res.ColorStateList.valueOf(androidx.core.content.ContextCompat.getColor(context, R.color.ufm_error)); setOnClickListener { rule.extensions.remove(ext); dialogView.removeView(card) } }
            row.addView(trash); card.addView(row); dialogView.addView(card)
        }
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext(), R.style.UFM_Dialog).setTitle(R.string.smart_sort_remove_extensions_title).setView(dialogView).setPositiveButton(R.string.done) { _, _ -> renderSheetCustomRules(view) }.create()
        dialog.show()
    }

    private fun pickSheetCustomRuleFolder(ruleIndex: Int) {
        val rule = sheetCustomRules[ruleIndex]
        val intent = Intent(requireContext(), SmartSortActivity::class.java).apply {
            putExtra(SmartSortActivity.EXTRA_SMART_SORT_CATEGORY_PICKER, true)
            putExtra(SmartSortActivity.EXTRA_SMART_SORT_CATEGORY_KEY, rule.id)
        }
        categoryFolderPickerLauncher.launch(intent)
    }

    companion object {
        const val TAG = "SmartSortSheet"
        private const val ARG_FOLDER_PATH = "folder_path"
        private const val ARG_SHARE_ID = "share_id"

        fun newInstance(folderPath: String, shareId: String? = null): SmartSortSheet {
            return SmartSortSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_FOLDER_PATH, folderPath)
                    putString(ARG_SHARE_ID, shareId)
                }
            }
        }
    }
}
