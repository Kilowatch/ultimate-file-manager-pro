package za.kilowatch.ultimatefilemanager.sync.advanced

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.TimePicker
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.network.NetworkBrowserActivity
import za.kilowatch.ultimatefilemanager.network.NetworkShareRepository
import za.kilowatch.ultimatefilemanager.network.OnlineStorageRepository
import za.kilowatch.ultimatefilemanager.storage.FileBrowserActivity
import za.kilowatch.ultimatefilemanager.storage.FileTagsManager
import za.kilowatch.ultimatefilemanager.storage.StorageBrowserActivity
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import za.kilowatch.ultimatefilemanager.util.DeviceUtils

class AdvancedSyncEditActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PROFILE_ID = "advanced_profile_id"
        const val EXTRA_SHARE_ID = "advanced_share_id"
    }

    private var profileId: String? = null
    private var localUri: String = ""
    private var localDisplayPath: String = ""
    private var selectedNetworkShareId: String? = null
    private var selectedRemotePath: String = ""
    private var destLocalUri: String = ""
    private var destLocalDisplayPath: String = ""
    private var selectedIntervalMinutes: Int = 60
    private var selectedScheduleType: String = "interval"

    private lateinit var editName: TextInputEditText
    private lateinit var txtSourceLabel: TextView
    private lateinit var txtDestLabel: TextView
    private lateinit var txtLocalPath: TextView
    private lateinit var txtRemotePath: TextView
    private lateinit var chipDirectionUpload: View
    private lateinit var chipDirectionDownload: View
    private lateinit var chipDirectionTwoway: View
    private lateinit var txtDirectionDesc: TextView
    private lateinit var chipInterval: View
    private lateinit var chipScheduled: View
    private lateinit var chipManual: View
    private lateinit var layoutIntervalSection: View
    private lateinit var layoutScheduledSection: View
    private var layoutDayOfWeek: View? = null
    private var layoutDayOfMonth: View? = null
    private lateinit var layoutConflictResolution: View
    private lateinit var chipConflictSkip: View
    private lateinit var chipConflictNewest: View
    private lateinit var chipConflictKeepLocal: View
    private lateinit var chipConflictKeepRemote: View
    private var layoutSyncDeletions: View? = null
    private var dropdownInterval: AutoCompleteTextView? = null
    private var dropdownPeriod: AutoCompleteTextView? = null
    private var dropdownDayOfWeek: AutoCompleteTextView? = null
    private var dropdownDayOfMonth: AutoCompleteTextView? = null
    private var timePicker: TimePicker? = null
    private var switchDownloadSubfolders: MaterialSwitch? = null
    private var layoutDownloadSubfolders: View? = null
    private lateinit var switchInstantSync: MaterialSwitch
    private lateinit var switchWifiOnly: MaterialSwitch
    private var switchMoveFiles: MaterialSwitch? = null
    private var txtMoveFilesSummary: TextView? = null
    private var switchSyncDeletions: MaterialSwitch? = null
    private var txtHeaderFiltering: View? = null
    private var cardFiltering: View? = null
    private var layoutFilters: View? = null
    private var chipFilterAll: View? = null
    private var chipFilterOnly: View? = null
    private var chipFilterSkip: View? = null
    private var layoutFilterExtensions: View? = null
    private var txtFilterExtensionsLabel: TextView? = null
    private var editFilterExtensions: TextInputEditText? = null
    private var editExcludePatterns: TextInputEditText? = null
    private var editIncludePatterns: TextInputEditText? = null
    private var editMinSize: TextInputEditText? = null
    private var editMaxSize: TextInputEditText? = null
    private var toggleMinSizeUnit: MaterialButtonToggleGroup? = null
    private var toggleMaxSizeUnit: MaterialButtonToggleGroup? = null
    private var editMinAge: TextInputEditText? = null
    private var editMaxAge: TextInputEditText? = null
    private lateinit var switchNotifications: MaterialSwitch
    private lateinit var btnDelete: View

    private var layoutTagsFilterSection: View? = null
    private var layoutIncludeTags: View? = null
    private var layoutExcludeTags: View? = null
    private var chipGroupIncludeTags: ChipGroup? = null
    private var chipGroupExcludeTags: ChipGroup? = null

    private lateinit var repo: AdvancedSyncProfileRepository

    private val periods by lazy { listOf(getString(R.string.daily), getString(R.string.weekly), getString(R.string.monthly)) }
    private val periodValues = listOf("daily", "weekly", "monthly")
    private val daysOfWeek by lazy { listOf(getString(R.string.monday),getString(R.string.tuesday),getString(R.string.wednesday),getString(R.string.thursday),getString(R.string.friday),getString(R.string.saturday),getString(R.string.sunday)) }
    private val daysOfWeekValues = listOf(1,2,3,4,5,6,7)
    private val daysOfMonth = (1..28).map { it.toString() }
    private val daysOfMonthValues = (1..28).toList()
    private var selectedPeriod = "daily"
    private var selectedDayOfWeek = 1
    private var selectedDayOfMonth = 1

    private var selectedDirection = "upload"
    private var selectedConflictStrategy = "skip"
    private var selectedExtensionMode = "all"

    private val sourceFolderLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val path = result.data?.getStringExtra(FileBrowserActivity.RESULT_SELECTED_LOCAL_PATH)
                ?: return@registerForActivityResult
            localUri = path
            localDisplayPath = java.io.File(path).name.ifEmpty { path }
            txtLocalPath.text = path
        }
    }

    /** Launches StorageBrowserActivity in dest picker mode, then NetworkBrowserActivity */
    private val destFolderLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val path = result.data?.getStringExtra(NetworkBrowserActivity.RESULT_SELECTED_SYNC_PATH)
                ?: return@registerForActivityResult
            val shareId = result.data?.getStringExtra(NetworkBrowserActivity.RESULT_SELECTED_SHARE_ID)
            if (shareId != null) {
                selectedNetworkShareId = shareId
            }
            selectedRemotePath = path
            txtRemotePath.text = if (path.isEmpty()) "/" else "/$path"
            updateDirectionDescription()
        }
    }

    /** Launches StorageBrowserActivity for local destination picker */
    private val destLocalFolderLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val path = result.data?.getStringExtra(FileBrowserActivity.RESULT_SELECTED_LOCAL_PATH)
                ?: return@registerForActivityResult
            destLocalUri = path
            destLocalDisplayPath = java.io.File(path).name.ifEmpty { path }
            txtRemotePath.text = path
            updateDirectionDescription()
        }
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        za.kilowatch.ultimatefilemanager.settings.ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val isTv = DeviceUtils.isTvDevice(this)
        setContentView(
            if (isTv) R.layout.activity_advanced_sync_edit_tv
            else R.layout.activity_advanced_sync_edit
        )

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        repo = AdvancedSyncProfileRepository.getInstance(this)

        editName = findViewById(R.id.editName)
        txtSourceLabel = findViewById(R.id.txtSourceLabel)
        txtDestLabel = findViewById(R.id.txtDestLabel)
        txtLocalPath = findViewById(R.id.txtLocalPath)
        txtRemotePath = findViewById(R.id.txtRemotePath)
        chipDirectionUpload = findViewById(R.id.chipDirectionUpload)
        chipDirectionDownload = findViewById(R.id.chipDirectionDownload)
        chipDirectionTwoway = findViewById(R.id.chipDirectionTwoway)
        txtDirectionDesc = findViewById(R.id.txtDirectionDesc)
        chipInterval = findViewById(R.id.chipInterval)
        chipScheduled = findViewById(R.id.chipScheduled)
        chipManual = findViewById(R.id.chipManual)
        layoutIntervalSection = findViewById(R.id.layoutIntervalSection)
        layoutScheduledSection = findViewById(R.id.layoutScheduledSection)
        layoutDayOfWeek = findViewById(R.id.layoutDayOfWeek)
        layoutDayOfMonth = findViewById(R.id.layoutDayOfMonth)
        layoutConflictResolution = findViewById(R.id.layoutConflictResolution)
        chipConflictSkip = findViewById(R.id.chipConflictSkip)
        chipConflictNewest = findViewById(R.id.chipConflictNewest)
        chipConflictKeepLocal = findViewById(R.id.chipConflictKeepLocal)
        chipConflictKeepRemote = findViewById(R.id.chipConflictKeepRemote)
        layoutSyncDeletions = findViewById(R.id.layoutSyncDeletions)
        dropdownInterval = findViewById(R.id.dropdownInterval)
        dropdownPeriod = findViewById(R.id.dropdownPeriod)
        dropdownDayOfWeek = findViewById(R.id.dropdownDayOfWeek)
        dropdownDayOfMonth = findViewById(R.id.dropdownDayOfMonth)
        timePicker = findViewById(R.id.timePicker)
        switchDownloadSubfolders = findViewById(R.id.switchDownloadSubfolders)
        layoutDownloadSubfolders = findViewById(R.id.layoutDownloadSubfolders)
        switchInstantSync = findViewById(R.id.switchInstantSync)
        switchWifiOnly = findViewById(R.id.switchWifiOnly)
        switchMoveFiles = findViewById(R.id.switchMoveFiles)
        txtMoveFilesSummary = findViewById(R.id.txtMoveFilesSummary)
        switchSyncDeletions = findViewById(R.id.switchSyncDeletions)
        txtHeaderFiltering = findViewById(R.id.txtHeaderFiltering)
        cardFiltering = findViewById(R.id.cardFiltering)
        layoutFilters = findViewById(R.id.layoutFilters)
        chipFilterAll = findViewById(R.id.chipFilterAll)
        chipFilterOnly = findViewById(R.id.chipFilterOnly)
        chipFilterSkip = findViewById(R.id.chipFilterSkip)
        txtFilterExtensionsLabel = findViewById(R.id.txtFilterExtensionsLabel)
        layoutFilterExtensions = findViewById(R.id.layoutFilterExtensions)
        editFilterExtensions = findViewById(R.id.editFilterExtensions)
        editExcludePatterns = findViewById(R.id.editExcludePatterns)
        editIncludePatterns = findViewById(R.id.editIncludePatterns)
        editMinSize = findViewById(R.id.editMinSize)
        editMaxSize = findViewById(R.id.editMaxSize)
        toggleMinSizeUnit = findViewById(R.id.toggleMinSizeUnit)
        toggleMaxSizeUnit = findViewById(R.id.toggleMaxSizeUnit)
        editMinAge = findViewById(R.id.editMinAge)
        editMaxAge = findViewById(R.id.editMaxAge)
        switchNotifications = findViewById(R.id.switchNotifications)
        btnDelete = findViewById(R.id.btnDelete)
        layoutTagsFilterSection = findViewById(R.id.layoutTagsFilterSection)
        layoutIncludeTags = findViewById(R.id.layoutIncludeTags)
        layoutExcludeTags = findViewById(R.id.layoutExcludeTags)
        chipGroupIncludeTags = findViewById(R.id.chipGroupIncludeTags)
        chipGroupExcludeTags = findViewById(R.id.chipGroupExcludeTags)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        // ── Local folder picker ────────────────────────────────────────────────
        findViewById<View>(R.id.btnSelectLocal).setOnClickListener {
            val intent = Intent(this, StorageBrowserActivity::class.java).apply {
                putExtra(StorageBrowserActivity.EXTRA_ADVANCED_SYNC_FOLDER_PICKER, true)
            }
            sourceFolderLauncher.launch(intent)
        }

        // ── Remote destination folder picker ───────────────────────────────────
        findViewById<View>(R.id.btnBrowseRemote).setOnClickListener {
            showDestinationChooserDialog()
        }

        // ── Direction chip selection ────────────────────────────────────────────
        chipDirectionUpload.setOnClickListener { selectDirection("upload") }
        chipDirectionDownload.setOnClickListener { selectDirection("download") }
        chipDirectionTwoway.setOnClickListener { selectDirection("twoway") }

        // Filter mode chips (All / Only / Skip)
        chipFilterAll?.setOnClickListener { selectFilterMode("all") }
        chipFilterOnly?.setOnClickListener { selectFilterMode("only") }
        chipFilterSkip?.setOnClickListener { selectFilterMode("skip") }
        selectFilterMode("all")

        // Move files and Sync deletions are mutually exclusive
        switchMoveFiles?.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                switchSyncDeletions?.isChecked = false
                switchSyncDeletions?.isEnabled = false
            } else {
                switchSyncDeletions?.isEnabled = true
            }
        }

        // Apply initial state
        selectDirection("upload")

        // ── Schedule type chips ────────────────────────────────────────────────
        chipInterval.setOnClickListener { selectSchedule("interval") }
        chipScheduled.setOnClickListener { selectSchedule("scheduled") }
        chipManual.setOnClickListener { selectSchedule("manual") }
        selectSchedule("interval")

        // ── Period dropdown ────────────────────────────────────────────────────
        dropdownPeriod?.let { dp ->
            val periodAdapter = ArrayAdapter(this, R.layout.item_dropdown_popup, android.R.id.text1, periods)
            dp.setAdapter(periodAdapter)
            dp.setText(periods[0], false)
            dp.setOnItemClickListener { _, _, position, _ ->
                selectedPeriod = periodValues[position]
                layoutDayOfWeek?.visibility = if (selectedPeriod == "weekly") View.VISIBLE else View.GONE
                layoutDayOfMonth?.visibility = if (selectedPeriod == "monthly") View.VISIBLE else View.GONE
            }
        }

        dropdownDayOfWeek?.let { ddow ->
            val dowAdapter = ArrayAdapter(this, R.layout.item_dropdown_popup, android.R.id.text1, daysOfWeek)
            ddow.setAdapter(dowAdapter)
            ddow.setText(daysOfWeek[0], false)
            ddow.setOnItemClickListener { _, _, position, _ ->
                selectedDayOfWeek = daysOfWeekValues[position]
            }
        }

        dropdownDayOfMonth?.let { ddom ->
            val domAdapter = ArrayAdapter(this, R.layout.item_dropdown_popup, android.R.id.text1, daysOfMonth)
            ddom.setAdapter(domAdapter)
            ddom.setText(daysOfMonth[0], false)
            ddom.setOnItemClickListener { _, _, position, _ ->
                selectedDayOfMonth = daysOfMonthValues[position]
            }
        }

        // ── Conflict chips ─────────────────────────────────────────────────────
        chipConflictSkip.setOnClickListener { selectConflictStrategy("skip") }
        chipConflictNewest.setOnClickListener { selectConflictStrategy("newest") }
        chipConflictKeepLocal.setOnClickListener { selectConflictStrategy("keep_local") }
        chipConflictKeepRemote.setOnClickListener { selectConflictStrategy("keep_remote") }
        selectConflictStrategy("skip")

        // ── Save / Delete ──────────────────────────────────────────────────────
        findViewById<View>(R.id.btnSave).setOnClickListener { saveProfile() }
        btnDelete.setOnClickListener { showDeleteConfirmDialog() }

        setupIntervalDropdown()

        profileId = intent.getStringExtra(EXTRA_PROFILE_ID)
        val shareExtra = intent.getStringExtra(EXTRA_SHARE_ID)
        if (shareExtra != null && profileId == null) {
            selectedNetworkShareId = shareExtra
            val share = NetworkShareRepository.getInstance(this).getById(shareExtra)
            if (share != null) {
                editName.setText(getString(R.string.backup_sync_between_mobile_and) + " " + (share.name.ifEmpty { share.host }))
            }
        }

        if (profileId != null) {
            loadProfile(profileId!!)
        } else {
            setupTagsFilterSection(null)
        }
    }

    private fun selectSchedule(type: String) {
        selectedScheduleType = type
        val isInterval = type == "interval"
        val isScheduled = type == "scheduled"
        val isManual = type == "manual"

        listOf(chipInterval, chipScheduled, chipManual).forEach { c ->
            if (c is MaterialButton) {
                c.isCheckable = true
                c.isChecked = (c == chipInterval && isInterval) || (c == chipScheduled && isScheduled) || (c == chipManual && isManual)
            } else if (c is Button) {
                val isSelected = (c == chipInterval && isInterval) || (c == chipScheduled && isScheduled) || (c == chipManual && isManual)
                c.setBackgroundResource(if (isSelected) R.drawable.selector_tv_button_yellow else R.drawable.selector_tv_button)
            }
        }

        layoutIntervalSection.visibility = if (isInterval) View.VISIBLE else View.GONE
        layoutScheduledSection.visibility = if (isScheduled) View.VISIBLE else View.GONE
    }

    private fun selectDirection(dir: String) {
        selectedDirection = dir
        val isUpload = dir == "upload"
        val isDownload = dir == "download"
        val isTwoway = dir == "twoway"

        listOf(chipDirectionUpload, chipDirectionDownload, chipDirectionTwoway).forEach { c ->
            if (c is MaterialButton) {
                c.isCheckable = true
                c.isChecked = (c == chipDirectionUpload && isUpload) || (c == chipDirectionDownload && isDownload) || (c == chipDirectionTwoway && isTwoway)
            } else if (c is Button) {
                val isSelected = (c == chipDirectionUpload && isUpload) || (c == chipDirectionDownload && isDownload) || (c == chipDirectionTwoway && isTwoway)
                c.setBackgroundResource(if (isSelected) R.drawable.selector_tv_button_yellow else R.drawable.selector_tv_button)
            }
        }

        updateDirectionDescription()
        updateDirectionSections()
    }

    private fun selectConflictStrategy(strategy: String) {
        selectedConflictStrategy = strategy
        val conflictChips = listOf(chipConflictSkip, chipConflictNewest, chipConflictKeepLocal, chipConflictKeepRemote)
        conflictChips.forEach { c ->
            val isSelected = when (strategy) {
                "newest" -> c == chipConflictNewest
                "keep_local" -> c == chipConflictKeepLocal
                "keep_remote" -> c == chipConflictKeepRemote
                else -> c == chipConflictSkip
            }
            if (c is MaterialButton) {
                c.isCheckable = true
                c.isChecked = isSelected
            } else if (c is Button) {
                c.setBackgroundResource(if (isSelected) R.drawable.selector_tv_button_yellow else R.drawable.selector_tv_button)
            }
        }
    }

    private fun selectFilterMode(mode: String) {
        selectedExtensionMode = mode
        val filterChips = listOfNotNull(chipFilterAll, chipFilterOnly, chipFilterSkip)
        filterChips.forEach { c ->
            val isSelected = when (mode) {
                "only" -> c == chipFilterOnly
                "skip" -> c == chipFilterSkip
                else -> c == chipFilterAll
            }
            if (c is MaterialButton) {
                c.isCheckable = true
                c.isChecked = isSelected
            } else if (c is Button) {
                c.setBackgroundResource(if (isSelected) R.drawable.selector_tv_button_yellow else R.drawable.selector_tv_button)
            }
        }
        val visible = if (mode == "all") View.GONE else View.VISIBLE
        txtFilterExtensionsLabel?.visibility = visible
        layoutFilterExtensions?.visibility = visible
        updateTagsVisibility()
    }

    private fun updateDirectionDescription() {
        val isLocal = destLocalUri.isNotEmpty()
        txtDirectionDesc.setText(when {
            isLocal && selectedDirection == "download" -> R.string.advanced_sync_local_download_desc
            isLocal && selectedDirection == "twoway" -> R.string.advanced_sync_local_twoway_desc
            isLocal -> R.string.advanced_sync_local_upload_desc
            selectedDirection == "download" -> R.string.sync_direction_download_desc
            selectedDirection == "twoway" -> R.string.sync_direction_twoway_desc
            else -> R.string.sync_direction_upload_desc
        })
        val btnBrowse = findViewById<View>(R.id.btnBrowseRemote)
        if (btnBrowse is MaterialButton) {
            btnBrowse.text = getString(if (isLocal) R.string.advanced_sync_dest_select else R.string.browse_remote)
        } else if (btnBrowse is Button) {
            btnBrowse.text = getString(if (isLocal) R.string.advanced_sync_dest_select else R.string.browse_remote)
        }
    }

    private fun updateDirectionSections() {
        layoutConflictResolution.visibility =
            if (selectedDirection == "twoway") View.VISIBLE else View.GONE
        layoutSyncDeletions?.visibility =
            if (selectedDirection == "twoway") View.GONE else View.VISIBLE
        layoutDownloadSubfolders?.visibility =
            if (selectedDirection == "download") View.VISIBLE else View.GONE

        val filterVisibility = if (selectedDirection != "twoway") View.VISIBLE else View.GONE
        txtHeaderFiltering?.visibility = filterVisibility
        cardFiltering?.visibility = filterVisibility
        layoutFilters?.visibility = filterVisibility

        // Swap Source/Destination labels for Download direction
        if (selectedDirection == "download") {
            txtSourceLabel.setText(R.string.destination)
            txtDestLabel.setText(R.string.source_phone)
        } else {
            txtSourceLabel.setText(R.string.source_phone)
            txtDestLabel.setText(R.string.destination)
        }

        val summaryView = findViewById<TextView>(R.id.txtSyncDeletionsSummary)
        summaryView?.setText(
            if (selectedDirection == "download") R.string.sync_deletions_summary_download
            else R.string.sync_deletions_summary_upload
        )
        if (selectedDirection == "download") {
            switchMoveFiles?.setText(R.string.move_files_download)
            txtMoveFilesSummary?.setText(R.string.move_files_download_summary)
        } else {
            switchMoveFiles?.setText(R.string.move_files_upload)
            txtMoveFilesSummary?.setText(R.string.move_files_upload_summary)
        }
    }

    private fun launchNetworkBrowserForDest(shareId: String, isOnline: Boolean) {
        val intent = Intent(this, NetworkBrowserActivity::class.java).apply {
            putExtra(NetworkBrowserActivity.EXTRA_SHARE_ID, shareId)
            putExtra("isOnlineStorage", isOnline)
            putExtra(NetworkBrowserActivity.EXTRA_SYNC_FOLDER_PICKER, true)
            putExtra(NetworkBrowserActivity.EXTRA_STORAGE_LABEL, getString(R.string.select_sync_folder))
        }
        destFolderLauncher.launch(intent)
    }

    private fun setupIntervalDropdown() {
        dropdownInterval?.let { di ->
            val intervals = listOf(
                getString(R.string.q5_minutes), getString(R.string.q10_minutes),
                getString(R.string.q15_minutes), getString(R.string.q30_minutes),
                getString(R.string.q1_hour), getString(R.string.q6_hours),
                getString(R.string.q12_hours), getString(R.string.q24_hours)
            )
            val intervalValues = listOf(5, 10, 15, 30, 60, 360, 720, 1440)
            val intervalAdapter = ArrayAdapter(this, R.layout.item_dropdown_popup, android.R.id.text1, intervals)
            di.setAdapter(intervalAdapter)
            di.setText(intervals[2], false)
            di.setOnItemClickListener { _, _, position, _ ->
                selectedIntervalMinutes = intervalValues[position]
            }
        }
        selectedIntervalMinutes = 60
    }

    private fun loadProfile(id: String) {
        val profile = repo.getById(id) ?: return

        editName.setText(profile.name)
        localUri = profile.localUri
        localDisplayPath = profile.localDisplayPath
        txtLocalPath.text = localDisplayPath

        selectedNetworkShareId = profile.networkShareId
        selectedRemotePath = profile.remotePath
        destLocalUri = profile.destLocalUri
        destLocalDisplayPath = profile.destLocalDisplayPath
        if (profile.destLocalUri.isNotEmpty()) {
            txtRemotePath.text = profile.destLocalDisplayPath.ifEmpty { profile.destLocalUri }
        } else {
            txtRemotePath.text = if (profile.remotePath.isEmpty()) "/" else "/${profile.remotePath}"
        }

        // Direction
        selectDirection(profile.direction)

        // Conflict strategy
        selectConflictStrategy(profile.conflictStrategy)

        // Schedule
        when (profile.scheduleType) {
            "scheduled" -> {
                selectSchedule("scheduled")
                val pIndex = periodValues.indexOf(profile.scheduledPeriod).coerceAtLeast(0)
                selectedPeriod = profile.scheduledPeriod
                dropdownPeriod?.setText(periods[pIndex], false)
                layoutDayOfWeek?.visibility = if (selectedPeriod == "weekly") View.VISIBLE else View.GONE
                layoutDayOfMonth?.visibility = if (selectedPeriod == "monthly") View.VISIBLE else View.GONE

                val dowIndex = daysOfWeekValues.indexOf(profile.scheduledDayOfWeek).coerceAtLeast(0)
                selectedDayOfWeek = profile.scheduledDayOfWeek
                dropdownDayOfWeek?.setText(daysOfWeek[dowIndex], false)

                val domIndex = daysOfMonthValues.indexOf(profile.scheduledDayOfMonth).coerceAtLeast(0)
                selectedDayOfMonth = profile.scheduledDayOfMonth
                dropdownDayOfMonth?.setText(daysOfMonth[domIndex], false)

                timePicker?.hour = profile.scheduledHour
                timePicker?.minute = profile.scheduledMinute
            }
            "manual" -> {
                selectSchedule("manual")
            }
            else -> {
                selectSchedule("interval")
                val intervalValues = listOf(5, 10, 15, 30, 60, 360, 720, 1440)
                val intervals = listOf(
                    getString(R.string.q5_minutes), getString(R.string.q10_minutes),
                    getString(R.string.q15_minutes), getString(R.string.q30_minutes),
                    getString(R.string.q1_hour), getString(R.string.q6_hours),
                    getString(R.string.q12_hours), getString(R.string.q24_hours)
                )
                val iIndex = intervalValues.indexOf(profile.intervalMinutes).coerceAtLeast(0)
                selectedIntervalMinutes = profile.intervalMinutes
                dropdownInterval?.setText(intervals[iIndex], false)
            }
        }

        // Toggles
        switchDownloadSubfolders?.isChecked = profile.downloadSubfolders
        switchInstantSync.isChecked = profile.instantSyncEnabled
        switchWifiOnly.isChecked = profile.wifiOnly
        switchMoveFiles?.isChecked = profile.moveFiles
        editFilterExtensions?.setText(profile.extensionFilters)
        editExcludePatterns?.setText(profile.excludePatterns)
        editIncludePatterns?.setText(profile.includePatterns)
        editMinSize?.setText(if (profile.minSizeBytes > 0) {
            ((if (profile.minSizeIsGB) profile.minSizeBytes / (1024*1024*1024) else profile.minSizeBytes / (1024*1024)).toString())
        } else "")
        editMaxSize?.setText(if (profile.maxSizeBytes > 0) {
            ((if (profile.maxSizeIsGB) profile.maxSizeBytes / (1024*1024*1024) else profile.maxSizeBytes / (1024*1024)).toString())
        } else "")
        toggleMinSizeUnit?.check(if (profile.minSizeIsGB) R.id.chipSizeMinGB else R.id.chipSizeMinMB)
        toggleMaxSizeUnit?.check(if (profile.maxSizeIsGB) R.id.chipSizeMaxGB else R.id.chipSizeMaxMB)
        editMinAge?.setText(if (profile.minAgeMinutes > 0) (profile.minAgeMinutes / (24*60)).toString() else "")
        editMaxAge?.setText(if (profile.maxAgeMinutes > 0) (profile.maxAgeMinutes / (24*60)).toString() else "")

        selectFilterMode(profile.extensionMode)

        val extVisible = if (profile.extensionMode == "all") View.GONE else View.VISIBLE
        txtFilterExtensionsLabel?.visibility = extVisible
        layoutFilterExtensions?.visibility = extVisible
        switchSyncDeletions?.isChecked = profile.syncDeletions
        if (profile.moveFiles) {
            switchSyncDeletions?.isEnabled = false
        }
        switchNotifications.isChecked = profile.notificationsEnabled

        btnDelete.visibility = View.VISIBLE
        setupTagsFilterSection(profile)
    }

    private fun saveProfile() {
        val name = editName.text.toString().trim()

        // Validate: must have name, source path, and either a network share or local destination
        val hasNetworkDest = selectedNetworkShareId != null && selectedNetworkShareId!!.isNotEmpty()
        val hasLocalDest = destLocalUri.isNotEmpty()
        if (name.isEmpty() || localUri.isEmpty() || (!hasNetworkDest && !hasLocalDest)) {
            Toast.makeText(this, R.string.please_fill_all_required_fields, Toast.LENGTH_SHORT).show()
            return
        }

        // Warn if source and local destination are the same or nested
        if (hasLocalDest) {
            val srcCanonical = java.io.File(localUri).canonicalPath
            val destCanonical = java.io.File(destLocalUri).canonicalPath
            if (srcCanonical == destCanonical ||
                destCanonical.startsWith(srcCanonical + java.io.File.separator) ||
                srcCanonical.startsWith(destCanonical + java.io.File.separator)) {
                showWarningDialog(
                    title = getString(R.string.important_warning),
                    message = getString(R.string.advanced_sync_same_path_warning),
                    onContinue = { proceedSaveProfile(name) }
                )
                return
            }
        }

        proceedSaveProfile(name)
    }

    private fun proceedSaveProfile(name: String) {
        val scheduleType = selectedScheduleType

        // Show destructive operation warning for Upload/Download with sync deletions enabled
        if (switchSyncDeletions?.isChecked == true && selectedDirection != "twoway") {
            val warningMessage = if (selectedDirection == "upload") {
                getString(R.string.sync_deletions_warning_upload)
            } else {
                getString(R.string.sync_deletions_warning_download)
            }
            showWarningDialog(
                title = getString(R.string.sync_deletions_warning_title),
                message = warningMessage,
                showSwitchToTwoWay = true,
                onContinue = { doSaveProfile(name, scheduleType) },
                onSwitchToTwoWay = {
                    selectDirection("twoway")
                    doSaveProfile(name, scheduleType)
                }
            )
            return
        }

        doSaveProfile(name, scheduleType)
    }

    private fun showWarningDialog(
        title: String,
        message: String,
        showSwitchToTwoWay: Boolean = false,
        onContinue: () -> Unit,
        onSwitchToTwoWay: (() -> Unit)? = null
    ) {
        val isTv = DeviceUtils.isTvDevice(this)
        val layoutRes = if (isTv) R.layout.dialog_sync_warning_tv else R.layout.dialog_sync_warning
        val dialogView = layoutInflater.inflate(layoutRes, null)
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogView.findViewById<TextView>(R.id.txtTitle).text = title
        dialogView.findViewById<TextView>(R.id.txtMessage).text = message

        dialogView.findViewById<View>(R.id.btnContinue).setOnClickListener {
            dialog.dismiss()
            onContinue()
        }

        val btnNeutral = dialogView.findViewById<View>(R.id.btnNeutral)
        if (showSwitchToTwoWay && onSwitchToTwoWay != null) {
            btnNeutral.visibility = View.VISIBLE
            btnNeutral.setOnClickListener {
                dialog.dismiss()
                onSwitchToTwoWay()
            }
        } else {
            btnNeutral.visibility = View.GONE
        }

        dialogView.findViewById<View>(R.id.btnCancel).setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showDeleteConfirmDialog() {
        val currentName = editName.text.toString().trim().ifEmpty { getString(R.string.sync_now) }
        val isTv = DeviceUtils.isTvDevice(this)
        val layoutRes = if (isTv) R.layout.dialog_sync_profile_delete_confirm_tv else R.layout.dialog_sync_profile_delete_confirm
        val dialogView = layoutInflater.inflate(layoutRes, null)
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogView.findViewById<TextView>(R.id.txtTitle).text =
            getString(R.string.delete_confirm_single, currentName)
        dialogView.findViewById<TextView>(R.id.txtMessage).text =
            getString(R.string.sync_delete_profile_confirm_msg, currentName)

        dialogView.findViewById<View>(R.id.btnDelete).setOnClickListener {
            dialog.dismiss()
            deleteProfile()
        }

        dialogView.findViewById<View>(R.id.btnCancel).setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun doSaveProfile(name: String, scheduleType: String) {
        val incTagsList = mutableListOf<String>()
        val cgInc = chipGroupIncludeTags
        if (cgInc != null && layoutIncludeTags?.visibility == View.VISIBLE) {
            for (i in 0 until cgInc.childCount) {
                val chip = cgInc.getChildAt(i) as? Chip
                if (chip != null && chip.isChecked) {
                    incTagsList.add(chip.text.toString().removePrefix("#"))
                }
            }
        }
        val incTags = incTagsList.joinToString(",")

        val excTagsList = mutableListOf<String>()
        val cgExc = chipGroupExcludeTags
        if (cgExc != null && layoutExcludeTags?.visibility == View.VISIBLE) {
            for (i in 0 until cgExc.childCount) {
                val chip = cgExc.getChildAt(i) as? Chip
                if (chip != null && chip.isChecked) {
                    excTagsList.add(chip.text.toString().removePrefix("#"))
                }
            }
        }
        val excTags = excTagsList.joinToString(",")

        val profile = AdvancedSyncProfile(
            id = profileId ?: java.util.UUID.randomUUID().toString(),
            name = name,
            localUri = localUri,
            localDisplayPath = localDisplayPath,
            networkShareId = selectedNetworkShareId ?: "",
            remotePath = selectedRemotePath,
            destLocalUri = destLocalUri,
            destLocalDisplayPath = destLocalDisplayPath,
            direction = selectedDirection,
            conflictStrategy = selectedConflictStrategy,
            syncDeletions = switchSyncDeletions?.isChecked ?: false,
            moveFiles = switchMoveFiles?.isChecked ?: false,
            scheduleType = scheduleType,
            intervalMinutes = selectedIntervalMinutes,
            scheduledHour = timePicker?.hour ?: 0,
            scheduledMinute = timePicker?.minute ?: 0,
            scheduledPeriod = selectedPeriod,
            scheduledDayOfWeek = selectedDayOfWeek,
            scheduledDayOfMonth = selectedDayOfMonth,
            instantSyncEnabled = switchInstantSync.isChecked,
            extensionMode = selectedExtensionMode,
            extensionFilters = editFilterExtensions?.text?.toString()?.trim() ?: "",
            excludePatterns = editExcludePatterns?.text?.toString()?.trim() ?: "",
            includePatterns = editIncludePatterns?.text?.toString()?.trim() ?: "",
            includeTags = incTags,
            excludeTags = excTags,
            minSizeBytes = (editMinSize?.text?.toString()?.toDoubleOrNull() ?: 0.0).let {
                (it * if (toggleMinSizeUnit?.checkedButtonId == R.id.chipSizeMinGB) 1024*1024*1024 else 1024*1024).toLong()
            },
            maxSizeBytes = (editMaxSize?.text?.toString()?.toDoubleOrNull() ?: 0.0).let {
                (it * if (toggleMaxSizeUnit?.checkedButtonId == R.id.chipSizeMaxGB) 1024*1024*1024 else 1024*1024).toLong()
            },
            minSizeIsGB = toggleMinSizeUnit?.checkedButtonId == R.id.chipSizeMinGB,
            maxSizeIsGB = toggleMaxSizeUnit?.checkedButtonId == R.id.chipSizeMaxGB,
            minAgeMinutes = (editMinAge?.text?.toString()?.toLongOrNull() ?: 0L) * 24 * 60,
            maxAgeMinutes = (editMaxAge?.text?.toString()?.toLongOrNull() ?: 0L) * 24 * 60,
            downloadSubfolders = switchDownloadSubfolders?.isChecked ?: false,
            wifiOnly = switchWifiOnly.isChecked,
            enabled = true,
            notificationsEnabled = switchNotifications.isChecked,
            lastSyncTime = repo.getById(profileId ?: "")?.lastSyncTime ?: 0L,
            lastSyncFileCount = repo.getById(profileId ?: "")?.lastSyncFileCount ?: 0,
            syncedFileHashes = repo.getById(profileId ?: "")?.syncedFileHashes ?: "",
            conflictLogJson = repo.getById(profileId ?: "")?.conflictLogJson ?: ""
        )

        repo.save(profile)
        AdvancedSyncScheduler.scheduleSync(this, profile)

        // Manage instant sync watcher
        if (profile.instantSyncEnabled && profile.enabled) {
            InstantSyncWatcher.startWatching(this, profile)
        } else {
            InstantSyncWatcher.stopWatching(profile.id)
        }

        finish()
    }

    private fun deleteProfile() {
        profileId?.let { id ->
            repo.delete(id)
            AdvancedSyncScheduler.cancelSync(this, id)
            InstantSyncWatcher.stopWatching(id)
        }
        finish()
    }

    private fun setupTagsFilterSection(profile: AdvancedSyncProfile?) {
        val allTags = FileTagsManager.getAllCreatedTags(this).sorted()
        val isMobile = !DeviceUtils.isTvDevice(this)
        val section = layoutTagsFilterSection ?: return

        if (isMobile && allTags.isNotEmpty()) {
            section.visibility = View.VISIBLE

            val cgInc = chipGroupIncludeTags ?: return
            val cgExc = chipGroupExcludeTags ?: return

            cgInc.removeAllViews()
            cgExc.removeAllViews()

            val activeInc = profile?.includeTags?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
            val activeExc = profile?.excludeTags?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()

            for (tag in allTags) {
                val chipInc = LayoutInflater.from(this)
                    .inflate(R.layout.item_tag_chip, cgInc, false) as Chip
                chipInc.text = "#$tag"
                chipInc.isCheckable = true
                chipInc.isChecked = activeInc.contains(tag)
                chipInc.isCheckedIconVisible = true
                cgInc.addView(chipInc)

                val chipExc = LayoutInflater.from(this)
                    .inflate(R.layout.item_tag_chip, cgExc, false) as Chip
                chipExc.text = "#$tag"
                chipExc.isCheckable = true
                chipExc.isChecked = activeExc.contains(tag)
                chipExc.isCheckedIconVisible = true
                cgExc.addView(chipExc)
            }

            updateTagsVisibility()
        } else {
            section.visibility = View.GONE
        }
    }

    private fun updateTagsVisibility() {
        val allTags = FileTagsManager.getAllCreatedTags(this).sorted()
        val isMobile = !DeviceUtils.isTvDevice(this)
        val section = layoutTagsFilterSection ?: return

        if (isMobile && allTags.isNotEmpty()) {
            section.visibility = View.VISIBLE
            val layInc = layoutIncludeTags
            val layExc = layoutExcludeTags

            when (selectedExtensionMode) {
                "only" -> {
                    layInc?.visibility = View.VISIBLE
                    layExc?.visibility = View.GONE
                }
                "skip" -> {
                    layInc?.visibility = View.GONE
                    layExc?.visibility = View.VISIBLE
                }
                else -> {
                    layInc?.visibility = View.VISIBLE
                    layExc?.visibility = View.VISIBLE
                }
            }
        } else {
            section.visibility = View.GONE
        }
    }

    private fun showDestinationChooserDialog() {
        val netShares = NetworkShareRepository.getInstance(this).getAll()
        val onlineStorages = OnlineStorageRepository.getInstance(this).getAll()

        data class DestOption(val title: String, val subtitle: String, val iconRes: Int, val type: String, val id: String)

        val options = mutableListOf<DestOption>()
        options.add(
            DestOption(
                title = getString(R.string.advanced_sync_local_folder),
                subtitle = getString(R.string.source_phone),
                iconRes = R.drawable.ic_storage_internal,
                type = "local",
                id = ""
            )
        )
        netShares.forEach { share ->
            options.add(
                DestOption(
                    title = share.name.ifEmpty { share.host },
                    subtitle = "${share.type.name} " + getString(R.string.destination_network_share),
                    iconRes = R.drawable.ic_network,
                    type = "net",
                    id = share.id
                )
            )
        }
        onlineStorages.forEach { storage ->
            options.add(
                DestOption(
                    title = storage.displayName.ifEmpty { storage.email },
                    subtitle = "${storage.provider.name} " + getString(R.string.destination_network_share),
                    iconRes = R.drawable.ic_cloud,
                    type = "online",
                    id = storage.id
                )
            )
        }

        val isTv = DeviceUtils.isTvDevice(this)
        val layoutRes = if (isTv) R.layout.dialog_sync_select_destination_tv else R.layout.dialog_sync_select_destination
        val dialogView = layoutInflater.inflate(layoutRes, null)
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val recycler = dialogView.findViewById<RecyclerView>(R.id.recyclerDestinations)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val itemView = layoutInflater.inflate(R.layout.item_sync_destination, parent, false)
                return object : RecyclerView.ViewHolder(itemView) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val opt = options[position]
                val v = holder.itemView
                v.findViewById<ImageView>(R.id.imgDestIcon).setImageResource(opt.iconRes)
                v.findViewById<TextView>(R.id.txtDestTitle).text = opt.title
                v.findViewById<TextView>(R.id.txtDestSubtitle).text = opt.subtitle
                v.setOnClickListener {
                    dialog.dismiss()
                    when (opt.type) {
                        "local" -> {
                            val intent = Intent(this@AdvancedSyncEditActivity, StorageBrowserActivity::class.java).apply {
                                putExtra(FileBrowserActivity.EXTRA_ADVANCED_SYNC_DEST_PICKER, true)
                            }
                            destLocalFolderLauncher.launch(intent)
                        }
                        "net" -> {
                            selectedNetworkShareId = opt.id
                            launchNetworkBrowserForDest(opt.id, isOnline = false)
                        }
                        "online" -> {
                            selectedNetworkShareId = opt.id
                            launchNetworkBrowserForDest(opt.id, isOnline = true)
                        }
                    }
                }
            }

            override fun getItemCount(): Int = options.size
        }

        dialogView.findViewById<android.view.View>(R.id.btnCancel).setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }
}
