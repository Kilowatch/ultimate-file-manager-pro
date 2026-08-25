package za.kilowatch.ultimatefilemanager.storage

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.button.MaterialButton
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.util.DeviceUtils

/**
 * Bottom sheet dialog for sorting and filtering files.
 * Supports sort by name/size/date/type with ascending/descending order,
 * and filter by file category.
 */
class SortFilterSheet : BottomSheetDialogFragment() {

    enum class SortMode { NAME, SIZE, DATE, TYPE }
    enum class SortOrder { ASC, DESC }
    enum class FilterType { ALL, IMAGES, VIDEOS, AUDIO, DOCUMENTS, ARCHIVES, APKS, OTHER }
    enum class DateFilter {
        ANY, TODAY, YESTERDAY, THIS_WEEK, PREVIOUS_WEEK, THIS_MONTH, PREVIOUS_MONTH, THIS_YEAR, PREVIOUS_YEAR
    }
    enum class SizeFilter {
        ANY, EMPTY, TINY, SMALL, MEDIUM, BIG, LARGE, HUGE
    }
    enum class Scope { GLOBAL, FOLDER }

    var currentSortMode = SortMode.NAME
    var currentSortOrder = SortOrder.ASC
    var currentFilterType = FilterType.ALL
    var currentDateFilter = DateFilter.ANY
    var currentSizeFilter = SizeFilter.ANY
    var currentShowHidden = false
    var currentGroupByDate = false
    var activeTags: Set<String> = emptySet()

    var currentFolderKey: String? = null
    var currentFolderDisplayPath: String = ""
    var currentScope: Scope = Scope.GLOBAL
    var currentIsRecursive = false
    var currentViewMode: ViewModeManager.ViewMode? = null
    var isSearchMode: Boolean = false

    var onApply: ((
        mode: SortMode,
        order: SortOrder,
        filter: FilterType,
        dateFilter: DateFilter,
        sizeFilter: SizeFilter,
        showHidden: Boolean,
        groupByDate: Boolean,
        tags: Set<String>,
        scope: Scope,
        viewMode: ViewModeManager.ViewMode,
        isRecursive: Boolean
    ) -> Unit)? = null

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
        val isTv = DeviceUtils.isTvDevice(requireContext())
        val layoutRes = if (isTv) R.layout.sheet_sort_filter_tv else R.layout.sheet_sort_filter
        val view = inflater.inflate(layoutRes, container, false)

        val txtTitle = view.findViewById<TextView>(R.id.txtSortTitle)
        txtTitle.text = getString(R.string.sort_title)

        // ── Scope selector ───────────────────────────────────────────────────
        val layoutScopeRow = view.findViewById<android.widget.LinearLayout?>(R.id.layoutScopeRow)
        val cgScope = view.findViewById<com.google.android.material.chip.ChipGroup?>(R.id.cgScope)
        val chipScopeGlobal = view.findViewById<com.google.android.material.chip.Chip?>(R.id.chipScopeGlobal)
        val chipScopeFolder = view.findViewById<com.google.android.material.chip.Chip?>(R.id.chipScopeFolder)

        if (currentFolderKey != null && !isSearchMode && layoutScopeRow != null) {
            layoutScopeRow.visibility = android.view.View.VISIBLE
            when (currentScope) {
                Scope.FOLDER -> chipScopeFolder?.isChecked = true
                Scope.GLOBAL -> chipScopeGlobal?.isChecked = true
            }
        } else {
            layoutScopeRow?.visibility = android.view.View.GONE
        }

        // View mode section and chips
        val layoutViewModeSection = view.findViewById<android.widget.LinearLayout?>(R.id.layoutViewModeSection)
        val cgViewMode = view.findViewById<ChipGroup?>(R.id.cgViewMode)
        val chipViewListSmall = view.findViewById<Chip?>(R.id.chipViewListSmall)
        val chipViewListMedium = view.findViewById<Chip?>(R.id.chipViewListMedium)
        val chipViewListLarge = view.findViewById<Chip?>(R.id.chipViewListLarge)
        val chipViewListXLarge = view.findViewById<Chip?>(R.id.chipViewListXLarge)
        val chipViewGridSmall = view.findViewById<Chip?>(R.id.chipViewGridSmall)
        val chipViewGridMedium = view.findViewById<Chip?>(R.id.chipViewGridMedium)
        val chipViewGridLarge = view.findViewById<Chip?>(R.id.chipViewGridLarge)

        val initialViewMode = currentViewMode ?: ViewModeManager.load(requireContext())
        when (initialViewMode) {
            ViewModeManager.ViewMode.LIST_SMALL -> chipViewListSmall?.isChecked = true
            ViewModeManager.ViewMode.LIST_MEDIUM -> chipViewListMedium?.isChecked = true
            ViewModeManager.ViewMode.LIST_LARGE -> chipViewListLarge?.isChecked = true
            ViewModeManager.ViewMode.LIST_XLARGE -> chipViewListXLarge?.isChecked = true
            ViewModeManager.ViewMode.GRID_SMALL -> chipViewGridSmall?.isChecked = true
            ViewModeManager.ViewMode.GRID_MEDIUM -> chipViewGridMedium?.isChecked = true
            ViewModeManager.ViewMode.GRID_LARGE -> chipViewGridLarge?.isChecked = true
        }

        // Folder mode (Root vs. Recursive) chips
        val layoutFolderModeSection = view.findViewById<android.widget.LinearLayout?>(R.id.layoutFolderModeSection)
        val cgFolderMode = view.findViewById<ChipGroup?>(R.id.cgFolderMode)
        val chipFolderModeRoot = view.findViewById<Chip?>(R.id.chipFolderModeRoot)
        val chipFolderModeRecursive = view.findViewById<Chip?>(R.id.chipFolderModeRecursive)

        if (currentIsRecursive) {
            chipFolderModeRecursive?.isChecked = true
        } else {
            chipFolderModeRoot?.isChecked = true
        }

        fun updateFolderOptionsVisibility(scope: Scope) {
            val show = !isSearchMode && scope == Scope.FOLDER
            layoutViewModeSection?.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE
            layoutFolderModeSection?.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE
        }

        if (currentFolderKey != null && !isSearchMode) {
            updateFolderOptionsVisibility(currentScope)
            cgScope?.setOnCheckedStateChangeListener { _, checkedIds ->
                val scope = if (checkedIds.contains(R.id.chipScopeFolder)) Scope.FOLDER else Scope.GLOBAL
                updateFolderOptionsVisibility(scope)
            }
        } else {
            updateFolderOptionsVisibility(Scope.GLOBAL)
        }

        // Sort mode chips
        val cgSort = view.findViewById<ChipGroup>(R.id.cgSortMode)
        val chipName = view.findViewById<Chip>(R.id.chipSortName)
        val chipSize = view.findViewById<Chip>(R.id.chipSortSize)
        val chipDate = view.findViewById<Chip>(R.id.chipSortDate)
        val chipType = view.findViewById<Chip>(R.id.chipSortType)

        when (currentSortMode) {
            SortMode.NAME -> chipName.isChecked = true
            SortMode.SIZE -> chipSize.isChecked = true
            SortMode.DATE -> chipDate.isChecked = true
            SortMode.TYPE -> chipType.isChecked = true
        }

        // Sort order chips
        val cgOrder = view.findViewById<ChipGroup>(R.id.cgSortOrder)
        val chipAsc = view.findViewById<Chip>(R.id.chipAscending)
        val chipDesc = view.findViewById<Chip>(R.id.chipDescending)

        fun updateOrderChipLabels(mode: SortMode) {
            when (mode) {
                SortMode.NAME -> {
                    chipAsc.text = getString(R.string.sort_order_a_to_z)
                    chipDesc.text = getString(R.string.sort_order_z_to_a)
                }
                SortMode.SIZE -> {
                    chipAsc.text = getString(R.string.sort_order_small_to_large)
                    chipDesc.text = getString(R.string.sort_order_large_to_small)
                }
                SortMode.DATE -> {
                    chipAsc.text = getString(R.string.sort_order_oldest_first)
                    chipDesc.text = getString(R.string.sort_order_newest_first)
                }
                SortMode.TYPE -> {
                    chipAsc.text = getString(R.string.sort_order_a_to_z)
                    chipDesc.text = getString(R.string.sort_order_z_to_a)
                }
            }
        }

        updateOrderChipLabels(currentSortMode)

        cgSort.setOnCheckedStateChangeListener { _, checkedIds ->
            val mode = when {
                checkedIds.contains(R.id.chipSortName) -> SortMode.NAME
                checkedIds.contains(R.id.chipSortSize) -> SortMode.SIZE
                checkedIds.contains(R.id.chipSortDate) -> SortMode.DATE
                checkedIds.contains(R.id.chipSortType) -> SortMode.TYPE
                else -> SortMode.NAME
            }
            updateOrderChipLabels(mode)
        }

        when (currentSortOrder) {
            SortOrder.ASC -> chipAsc.isChecked = true
            SortOrder.DESC -> chipDesc.isChecked = true
        }

        // Filter chips
        val chipGroup = view.findViewById<ChipGroup>(R.id.chipGroupFilter)
        val chipAll = view.findViewById<Chip>(R.id.chipAll)
        val chipImages = view.findViewById<Chip>(R.id.chipImages)
        val chipVideos = view.findViewById<Chip>(R.id.chipVideos)
        val chipAudio = view.findViewById<Chip>(R.id.chipAudio)
        val chipDocs = view.findViewById<Chip>(R.id.chipDocuments)
        val chipArchives = view.findViewById<Chip?>(R.id.chipArchives)
        val chipApks = view.findViewById<Chip>(R.id.chipApks)

        when (currentFilterType) {
            FilterType.ALL -> chipAll.isChecked = true
            FilterType.IMAGES -> chipImages.isChecked = true
            FilterType.VIDEOS -> chipVideos.isChecked = true
            FilterType.AUDIO -> chipAudio.isChecked = true
            FilterType.DOCUMENTS -> chipDocs.isChecked = true
            FilterType.ARCHIVES -> chipArchives?.isChecked = true
            FilterType.APKS -> chipApks.isChecked = true
            FilterType.OTHER -> { /* No specific chip for Other */ }
        }

        // Date filter chips
        val cgDate = view.findViewById<ChipGroup?>(R.id.cgDateFilter)
        val chipDateAny = view.findViewById<Chip?>(R.id.chipDateAny)
        val chipDateToday = view.findViewById<Chip?>(R.id.chipDateToday)
        val chipDateYesterday = view.findViewById<Chip?>(R.id.chipDateYesterday)
        val chipDateThisWeek = view.findViewById<Chip?>(R.id.chipDateThisWeek)
        val chipDatePrevWeek = view.findViewById<Chip?>(R.id.chipDatePrevWeek)
        val chipDateThisMonth = view.findViewById<Chip?>(R.id.chipDateThisMonth)
        val chipDatePrevMonth = view.findViewById<Chip?>(R.id.chipDatePrevMonth)
        val chipDateThisYear = view.findViewById<Chip?>(R.id.chipDateThisYear)
        val chipDatePrevYear = view.findViewById<Chip?>(R.id.chipDatePrevYear)

        when (currentDateFilter) {
            DateFilter.ANY -> chipDateAny?.isChecked = true
            DateFilter.TODAY -> chipDateToday?.isChecked = true
            DateFilter.YESTERDAY -> chipDateYesterday?.isChecked = true
            DateFilter.THIS_WEEK -> chipDateThisWeek?.isChecked = true
            DateFilter.PREVIOUS_WEEK -> chipDatePrevWeek?.isChecked = true
            DateFilter.THIS_MONTH -> chipDateThisMonth?.isChecked = true
            DateFilter.PREVIOUS_MONTH -> chipDatePrevMonth?.isChecked = true
            DateFilter.THIS_YEAR -> chipDateThisYear?.isChecked = true
            DateFilter.PREVIOUS_YEAR -> chipDatePrevYear?.isChecked = true
        }

        // Size filter chips
        val cgSize = view.findViewById<ChipGroup?>(R.id.cgSizeFilter)
        val chipSizeAny = view.findViewById<Chip?>(R.id.chipSizeAny)
        val chipSizeEmpty = view.findViewById<Chip?>(R.id.chipSizeEmpty)
        val chipSizeTiny = view.findViewById<Chip?>(R.id.chipSizeTiny)
        val chipSizeSmall = view.findViewById<Chip?>(R.id.chipSizeSmall)
        val chipSizeMedium = view.findViewById<Chip?>(R.id.chipSizeMedium)
        val chipSizeBig = view.findViewById<Chip?>(R.id.chipSizeBig)
        val chipSizeLarge = view.findViewById<Chip?>(R.id.chipSizeLarge)
        val chipSizeHuge = view.findViewById<Chip?>(R.id.chipSizeHuge)

        when (currentSizeFilter) {
            SizeFilter.ANY -> chipSizeAny?.isChecked = true
            SizeFilter.EMPTY -> chipSizeEmpty?.isChecked = true
            SizeFilter.TINY -> chipSizeTiny?.isChecked = true
            SizeFilter.SMALL -> chipSizeSmall?.isChecked = true
            SizeFilter.MEDIUM -> chipSizeMedium?.isChecked = true
            SizeFilter.BIG -> chipSizeBig?.isChecked = true
            SizeFilter.LARGE -> chipSizeLarge?.isChecked = true
            SizeFilter.HUGE -> chipSizeHuge?.isChecked = true
        }

        // Show hidden files chips
        val cgHidden = view.findViewById<ChipGroup>(R.id.cgHiddenFiles)
        val chipHiddenEnabled = view.findViewById<Chip>(R.id.chipHiddenEnabled)
        val chipHiddenDisabled = view.findViewById<Chip>(R.id.chipHiddenDisabled)

        if (currentShowHidden) {
            chipHiddenEnabled.isChecked = true
        } else {
            chipHiddenDisabled.isChecked = true
        }

        // Group by Date chips
        val cgGroupByDate = view.findViewById<ChipGroup>(R.id.cgGroupByDate)
        val chipGroupDateEnabled = view.findViewById<Chip>(R.id.chipGroupDateEnabled)
        val chipGroupDateDisabled = view.findViewById<Chip>(R.id.chipGroupDateDisabled)

        if (currentGroupByDate) {
            chipGroupDateEnabled.isChecked = true
        } else {
            chipGroupDateDisabled.isChecked = true
        }

        // Populate Tags filter section if tags exist (Mobile Only)
        val layoutTags = view.findViewById<android.widget.LinearLayout>(R.id.layoutTagsFilter)
        val cgTags = view.findViewById<ChipGroup>(R.id.chipGroupTags)
        val allTags = FileTagsManager.getAllCreatedTags(requireContext()).sorted()

        if (allTags.isNotEmpty() && !DeviceUtils.isTvDevice(requireContext())) {
            layoutTags?.visibility = View.VISIBLE
            cgTags?.removeAllViews()
            for (tag in allTags) {
                val chip = LayoutInflater.from(requireContext())
                    .inflate(R.layout.item_tag_chip, cgTags, false) as Chip
                chip.text = "#$tag"
                chip.isChecked = activeTags.contains(tag)
                chip.isCheckedIconVisible = true
                cgTags?.addView(chip)
            }
        } else {
            layoutTags?.visibility = View.GONE
        }

        // Apply button
        view.findViewById<View>(R.id.btnApplySort).setOnClickListener {
            val sortMode = when (cgSort.checkedChipId) {
                R.id.chipSortName -> SortMode.NAME
                R.id.chipSortSize -> SortMode.SIZE
                R.id.chipSortDate -> SortMode.DATE
                R.id.chipSortType -> SortMode.TYPE
                else -> SortMode.NAME
            }
            val sortOrder = when (cgOrder.checkedChipId) {
                R.id.chipAscending -> SortOrder.ASC
                R.id.chipDescending -> SortOrder.DESC
                else -> SortOrder.ASC
            }
            val filterType = when (chipGroup.checkedChipId) {
                R.id.chipImages -> FilterType.IMAGES
                R.id.chipVideos -> FilterType.VIDEOS
                R.id.chipAudio -> FilterType.AUDIO
                R.id.chipDocuments -> FilterType.DOCUMENTS
                R.id.chipArchives -> FilterType.ARCHIVES
                R.id.chipApks -> FilterType.APKS
                else -> FilterType.ALL
            }
            val dateFilter = when (cgDate?.checkedChipId) {
                R.id.chipDateToday -> DateFilter.TODAY
                R.id.chipDateYesterday -> DateFilter.YESTERDAY
                R.id.chipDateThisWeek -> DateFilter.THIS_WEEK
                R.id.chipDatePrevWeek -> DateFilter.PREVIOUS_WEEK
                R.id.chipDateThisMonth -> DateFilter.THIS_MONTH
                R.id.chipDatePrevMonth -> DateFilter.PREVIOUS_MONTH
                R.id.chipDateThisYear -> DateFilter.THIS_YEAR
                R.id.chipDatePrevYear -> DateFilter.PREVIOUS_YEAR
                else -> DateFilter.ANY
            }
            val sizeFilter = when (cgSize?.checkedChipId) {
                R.id.chipSizeEmpty -> SizeFilter.EMPTY
                R.id.chipSizeTiny -> SizeFilter.TINY
                R.id.chipSizeSmall -> SizeFilter.SMALL
                R.id.chipSizeMedium -> SizeFilter.MEDIUM
                R.id.chipSizeBig -> SizeFilter.BIG
                R.id.chipSizeLarge -> SizeFilter.LARGE
                R.id.chipSizeHuge -> SizeFilter.HUGE
                else -> SizeFilter.ANY
            }
            val showHidden = when (cgHidden.checkedChipId) {
                R.id.chipHiddenEnabled -> true
                else -> false
            }
            val groupByDate = when (cgGroupByDate.checkedChipId) {
                R.id.chipGroupDateEnabled -> true
                else -> false
            }
            val selectedTags = mutableSetOf<String>()
            if (layoutTags?.visibility == View.VISIBLE) {
                val tagsGroup = cgTags
                if (tagsGroup != null) {
                    for (i in 0 until tagsGroup.childCount) {
                        val chip = tagsGroup.getChildAt(i) as? Chip
                        if (chip != null && chip.isChecked) {
                            val cleanTag = chip.text.toString().removePrefix("#")
                            selectedTags.add(cleanTag)
                        }
                    }
                }
            }
            val scope = if (isSearchMode) {
                Scope.GLOBAL
            } else {
                when (cgScope?.checkedChipId) {
                    R.id.chipScopeFolder -> Scope.FOLDER
                    else -> Scope.GLOBAL
                }
            }
            val isRecursiveSelected = when (cgFolderMode?.checkedChipId) {
                R.id.chipFolderModeRecursive -> true
                else -> false
            }
            val selectedViewMode = when (cgViewMode?.checkedChipId) {
                R.id.chipViewListSmall -> ViewModeManager.ViewMode.LIST_SMALL
                R.id.chipViewListMedium -> ViewModeManager.ViewMode.LIST_MEDIUM
                R.id.chipViewListLarge -> ViewModeManager.ViewMode.LIST_LARGE
                R.id.chipViewListXLarge -> ViewModeManager.ViewMode.LIST_XLARGE
                R.id.chipViewGridSmall -> ViewModeManager.ViewMode.GRID_SMALL
                R.id.chipViewGridMedium -> ViewModeManager.ViewMode.GRID_MEDIUM
                R.id.chipViewGridLarge -> ViewModeManager.ViewMode.GRID_LARGE
                else -> ViewModeManager.load(requireContext())
            }
            onApply?.invoke(
                sortMode, sortOrder, filterType, dateFilter, sizeFilter,
                showHidden, groupByDate, selectedTags, scope, selectedViewMode, isRecursiveSelected
            )
            dismiss()
        }
        
        // Setup TV focus states
        if (DeviceUtils.isTvDevice(requireContext())) {
            setupTvFocus(view)
        }

        return view
    }

    private fun setupTvFocus(view: View) {
        val yellowBg = android.content.res.ColorStateList.valueOf(requireContext().getColor(R.color.tv_button_focused_yellow))
        val yellowText = requireContext().getColor(R.color.tv_button_focused_yellow_text)
        val whiteText = requireContext().getColor(R.color.tv_text_primary)
        val accentText = requireContext().getColor(R.color.tv_accent)

        fun applyFocusListener(v: View, defaultBgTint: android.content.res.ColorStateList? = null, defaultTextColor: Int? = null) {
            v.setOnFocusChangeListener { _, hasFocus ->
                if (v is RadioButton) {
                    if (hasFocus) {
                        v.backgroundTintList = yellowBg
                        v.setTextColor(yellowText)
                    } else {
                        v.backgroundTintList = defaultBgTint ?: android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
                        v.setTextColor(defaultTextColor ?: whiteText)
                    }
                } else if (v is Chip) {
                    if (hasFocus) {
                        v.chipBackgroundColor = yellowBg
                        v.setTextColor(yellowText)
                    } else {
                        v.chipBackgroundColor = null // fall back to style
                        v.setTextColor(whiteText)
                    }
                } else if (v is MaterialButton) {
                    if (hasFocus) {
                        v.backgroundTintList = yellowBg
                        v.setTextColor(yellowText)
                    } else {
                        v.backgroundTintList = defaultBgTint ?: android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
                        v.setTextColor(defaultTextColor ?: accentText)
                    }
                }
            }
        }

        val folderModeChips = if (currentFolderKey != null && !isSearchMode) listOf(R.id.chipFolderModeRoot, R.id.chipFolderModeRecursive) else emptyList()
        val scopeChips = if (currentFolderKey != null && !isSearchMode) listOf(R.id.chipScopeGlobal, R.id.chipScopeFolder) else emptyList()
        val chips = listOf(
            R.id.chipSortName, R.id.chipSortSize, R.id.chipSortDate, R.id.chipSortType,
            R.id.chipAscending, R.id.chipDescending,
            R.id.chipAll, R.id.chipImages, R.id.chipVideos, R.id.chipAudio, R.id.chipDocuments, R.id.chipArchives, R.id.chipApks,
            R.id.chipDateAny, R.id.chipDateToday, R.id.chipDateYesterday, R.id.chipDateThisWeek, R.id.chipDatePrevWeek,
            R.id.chipDateThisMonth, R.id.chipDatePrevMonth, R.id.chipDateThisYear, R.id.chipDatePrevYear,
            R.id.chipSizeAny, R.id.chipSizeEmpty, R.id.chipSizeTiny, R.id.chipSizeSmall, R.id.chipSizeMedium,
            R.id.chipSizeBig, R.id.chipSizeLarge, R.id.chipSizeHuge,
            R.id.chipHiddenEnabled, R.id.chipHiddenDisabled,
            R.id.chipGroupDateEnabled, R.id.chipGroupDateDisabled,
            R.id.chipViewListSmall, R.id.chipViewListMedium, R.id.chipViewListLarge, R.id.chipViewListXLarge,
            R.id.chipViewGridSmall, R.id.chipViewGridMedium, R.id.chipViewGridLarge
        ) + scopeChips + folderModeChips
        for (id in chips) {
            val chip = view.findViewById<Chip?>(id) ?: continue
            applyFocusListener(chip, defaultTextColor = whiteText)
        }

        val btnApply = view.findViewById<MaterialButton>(R.id.btnApplySort)
        val defaultBtnBg = btnApply.backgroundTintList
        applyFocusListener(btnApply, defaultBgTint = defaultBtnBg, defaultTextColor = whiteText)
    }

    companion object {
        const val TAG = "SortFilterSheet"

        val IMAGE_EXTENSIONS = za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.IMAGE_EXTENSIONS
        val VIDEO_EXTENSIONS = za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter.VIDEO_EXTENSIONS
        val AUDIO_EXTENSIONS = setOf("mp3", "wav", "aac", "flac", "ogg", "wma", "m4a", "opus")
        val DOCUMENT_EXTENSIONS = setOf(
            "pdf", "doc", "docx", "docm", "dot", "dotx", "dotm",
            "xls", "xlsx", "xlsm", "xlt", "xltx", "xltm", "xlsb",
            "ppt", "pptx", "pptm", "pps", "ppsx", "pot", "potx", "potm",
            "txt", "csv", "rtf", "odt", "dat",
            "vsd", "vsdx", "pub", "accdb", "mdb"
        )
        val APK_EXTENSIONS = setOf("apk", "xapk", "apks")
        val TEXT_EXTENSIONS = setOf(
            "txt", "log", "ini", "cfg", "conf", "json", "xml", "yaml", "yml", "md",
            "sh", "bat", "py", "js", "html", "css", "java", "kt", "c", "cpp", "h",
            "sql", "gradle", "properties", "csv", "rtf", "m3u", "m3u8"
        )
        val ARCHIVE_EXTENSIONS = setOf(
            "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "iso", "tgz", "tbz2", "zst", "cab", "lzma", "lzh", "arj"
        )

        fun matchesExtension(ext: String, filter: FilterType): Boolean {
            if (filter == FilterType.ALL) return true
            val lower = ext.lowercase().trimStart('.')
            return when (filter) {
                FilterType.IMAGES -> lower in IMAGE_EXTENSIONS
                FilterType.VIDEOS -> lower in VIDEO_EXTENSIONS
                FilterType.AUDIO -> lower in AUDIO_EXTENSIONS
                FilterType.DOCUMENTS -> lower in DOCUMENT_EXTENSIONS
                FilterType.ARCHIVES -> lower in ARCHIVE_EXTENSIONS
                FilterType.APKS -> lower in APK_EXTENSIONS
                FilterType.OTHER -> {
                    lower !in IMAGE_EXTENSIONS && lower !in VIDEO_EXTENSIONS &&
                    lower !in AUDIO_EXTENSIONS && lower !in DOCUMENT_EXTENSIONS &&
                    lower !in ARCHIVE_EXTENSIONS && lower !in APK_EXTENSIONS
                }
                else -> true
            }
        }

        fun matchesDate(lastModified: Long, dateFilter: DateFilter): Boolean {
            if (dateFilter == DateFilter.ANY) return true
            if (lastModified <= 0L) return false

            val cal = java.util.Calendar.getInstance()
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
            cal.set(java.util.Calendar.MINUTE, 0)
            cal.set(java.util.Calendar.SECOND, 0)
            cal.set(java.util.Calendar.MILLISECOND, 0)
            val startOfToday = cal.timeInMillis

            return when (dateFilter) {
                DateFilter.ANY -> true
                DateFilter.TODAY -> lastModified >= startOfToday
                DateFilter.YESTERDAY -> {
                    cal.add(java.util.Calendar.DAY_OF_YEAR, -1)
                    val startOfYesterday = cal.timeInMillis
                    lastModified in startOfYesterday until startOfToday
                }
                DateFilter.THIS_WEEK -> {
                    cal.set(java.util.Calendar.DAY_OF_WEEK, cal.firstDayOfWeek)
                    val startOfThisWeek = cal.timeInMillis
                    lastModified >= startOfThisWeek
                }
                DateFilter.PREVIOUS_WEEK -> {
                    cal.set(java.util.Calendar.DAY_OF_WEEK, cal.firstDayOfWeek)
                    val startOfThisWeek = cal.timeInMillis
                    cal.add(java.util.Calendar.WEEK_OF_YEAR, -1)
                    val startOfPrevWeek = cal.timeInMillis
                    lastModified in startOfPrevWeek until startOfThisWeek
                }
                DateFilter.THIS_MONTH -> {
                    cal.set(java.util.Calendar.DAY_OF_MONTH, 1)
                    val startOfThisMonth = cal.timeInMillis
                    lastModified >= startOfThisMonth
                }
                DateFilter.PREVIOUS_MONTH -> {
                    cal.set(java.util.Calendar.DAY_OF_MONTH, 1)
                    val startOfThisMonth = cal.timeInMillis
                    cal.add(java.util.Calendar.MONTH, -1)
                    val startOfPrevMonth = cal.timeInMillis
                    lastModified in startOfPrevMonth until startOfThisMonth
                }
                DateFilter.THIS_YEAR -> {
                    cal.set(java.util.Calendar.DAY_OF_YEAR, 1)
                    val startOfThisYear = cal.timeInMillis
                    lastModified >= startOfThisYear
                }
                DateFilter.PREVIOUS_YEAR -> {
                    cal.set(java.util.Calendar.DAY_OF_YEAR, 1)
                    val startOfThisYear = cal.timeInMillis
                    cal.add(java.util.Calendar.YEAR, -1)
                    val startOfPrevYear = cal.timeInMillis
                    lastModified in startOfPrevYear until startOfThisYear
                }
            }
        }

        fun matchesSize(size: Long, isDirectory: Boolean, sizeFilter: SizeFilter): Boolean {
            if (sizeFilter == SizeFilter.ANY) return true
            if (isDirectory) return true // Folders pass size filter
            val kb = 1024L
            val mb = 1024L * 1024L
            val gb = 1024L * 1024L * 1024L
            return when (sizeFilter) {
                SizeFilter.ANY -> true
                SizeFilter.EMPTY -> size == 0L
                SizeFilter.TINY -> size in 1..(10 * kb)
                SizeFilter.SMALL -> size in (10 * kb + 1)..(1 * mb)
                SizeFilter.MEDIUM -> size in (1 * mb + 1)..(10 * mb)
                SizeFilter.BIG -> size in (10 * mb + 1)..(100 * mb)
                SizeFilter.LARGE -> size in (100 * mb + 1)..(1 * gb)
                SizeFilter.HUGE -> size > 1 * gb
            }
        }

        fun matchesFilter(
            file: java.io.File,
            filter: FilterType = FilterType.ALL,
            dateFilter: DateFilter = DateFilter.ANY,
            sizeFilter: SizeFilter = SizeFilter.ANY
        ): Boolean {
            if (!matchesExtension(file.extension, filter)) return false
            if (!matchesDate(file.lastModified(), dateFilter)) return false
            if (!matchesSize(if (file.isDirectory) 0L else file.length(), file.isDirectory, sizeFilter)) return false
            return true
        }
    }
}
