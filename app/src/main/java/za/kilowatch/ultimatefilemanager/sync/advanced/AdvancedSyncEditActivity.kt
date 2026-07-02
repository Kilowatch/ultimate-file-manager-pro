package za.kilowatch.ultimatefilemanager.sync.advanced

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.RadioGroup
import android.widget.TimePicker
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.network.NetworkBrowserActivity
import za.kilowatch.ultimatefilemanager.network.NetworkShareRepository
import za.kilowatch.ultimatefilemanager.network.OnlineStorageRepository
import za.kilowatch.ultimatefilemanager.storage.FileBrowserActivity
import za.kilowatch.ultimatefilemanager.storage.StorageBrowserActivity
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper

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

    private lateinit var editName: TextInputEditText
    private lateinit var txtSourceLabel: android.widget.TextView
    private lateinit var txtDestLabel: android.widget.TextView
    private lateinit var txtLocalPath: android.widget.TextView
    private lateinit var txtRemotePath: android.widget.TextView
    private lateinit var chipDirectionUpload: android.view.View
    private lateinit var chipDirectionDownload: android.view.View
    private lateinit var chipDirectionTwoway: android.view.View
    private lateinit var txtDirectionDesc: android.widget.TextView
    private lateinit var chipInterval: android.view.View
    private lateinit var chipScheduled: android.view.View
    private lateinit var chipManual: android.view.View
    private lateinit var layoutIntervalSection: View
    private lateinit var layoutScheduledSection: View
    private lateinit var layoutDayOfWeek: View
    private lateinit var layoutDayOfMonth: View
    private lateinit var layoutConflictResolution: View
    private lateinit var chipConflictSkip: android.view.View
    private lateinit var chipConflictNewest: android.view.View
    private lateinit var chipConflictKeepLocal: android.view.View
    private lateinit var chipConflictKeepRemote: android.view.View
    private var layoutSyncDeletions: View? = null
    private lateinit var dropdownInterval: AutoCompleteTextView
    private lateinit var dropdownPeriod: AutoCompleteTextView
    private lateinit var dropdownDayOfWeek: AutoCompleteTextView
    private lateinit var dropdownDayOfMonth: AutoCompleteTextView
    private lateinit var timePicker: TimePicker
    private lateinit var switchDownloadSubfolders: MaterialSwitch
    private var layoutDownloadSubfolders: View? = null
    private lateinit var switchInstantSync: MaterialSwitch
    private lateinit var switchWifiOnly: MaterialSwitch
    private lateinit var switchMoveFiles: MaterialSwitch
    private var txtMoveFilesSummary: android.widget.TextView? = null
    private lateinit var switchSyncDeletions: MaterialSwitch
    private var layoutFilters: View? = null
    private lateinit var toggleExtensionMode: com.google.android.material.button.MaterialButtonToggleGroup
    private var layoutFilterExtensions: View? = null
    private var txtFilterExtensionsLabel: android.widget.TextView? = null
    private lateinit var editFilterExtensions: TextInputEditText
    private lateinit var editExcludePatterns: TextInputEditText
    private lateinit var editIncludePatterns: TextInputEditText
    private lateinit var editMinSize: TextInputEditText
    private lateinit var editMaxSize: TextInputEditText
    private lateinit var toggleMinSizeUnit: com.google.android.material.button.MaterialButtonToggleGroup
    private lateinit var toggleMaxSizeUnit: com.google.android.material.button.MaterialButtonToggleGroup
    private lateinit var editMinAge: TextInputEditText
    private lateinit var editMaxAge: TextInputEditText
    private lateinit var switchNotifications: MaterialSwitch
    private lateinit var btnDelete: MaterialButton

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

        val isTv = za.kilowatch.ultimatefilemanager.util.DeviceUtils.isTvDevice(this)
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
        layoutSyncDeletions = findViewById<View>(R.id.layoutSyncDeletions)
        dropdownInterval = findViewById(R.id.dropdownInterval)
        dropdownPeriod = findViewById(R.id.dropdownPeriod)
        dropdownDayOfWeek = findViewById(R.id.dropdownDayOfWeek)
        dropdownDayOfMonth = findViewById(R.id.dropdownDayOfMonth)
        timePicker = findViewById(R.id.timePicker)
        switchDownloadSubfolders = findViewById(R.id.switchDownloadSubfolders)
        layoutDownloadSubfolders = findViewById<View>(R.id.layoutDownloadSubfolders)
        switchInstantSync = findViewById(R.id.switchInstantSync)
        switchWifiOnly = findViewById(R.id.switchWifiOnly)
        switchMoveFiles = findViewById(R.id.switchMoveFiles)
        txtMoveFilesSummary = findViewById<android.widget.TextView>(R.id.txtMoveFilesSummary)
        switchSyncDeletions = findViewById(R.id.switchSyncDeletions)
        layoutFilters = findViewById<View>(R.id.layoutFilters)
        toggleExtensionMode = findViewById(R.id.toggleExtensionMode)
        txtFilterExtensionsLabel = findViewById<android.widget.TextView>(R.id.txtFilterExtensionsLabel)
        layoutFilterExtensions = findViewById<View>(R.id.layoutFilterExtensions)
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

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        // ── Local folder picker ────────────────────────────────────────────────
        findViewById<MaterialButton>(R.id.btnSelectLocal).setOnClickListener {
            val intent = Intent(this, StorageBrowserActivity::class.java).apply {
                putExtra(StorageBrowserActivity.EXTRA_ADVANCED_SYNC_FOLDER_PICKER, true)
            }
            sourceFolderLauncher.launch(intent)
        }

        // ── Remote destination folder picker ───────────────────────────────────
        findViewById<MaterialButton>(R.id.btnBrowseRemote).setOnClickListener {
            val netShares = NetworkShareRepository.getInstance(this).getAll()
            val onlineStorages = OnlineStorageRepository.getInstance(this).getAll()

            // Build combined list: "Local folder" first, then network shares, then online storages
            val labels = mutableListOf<String>()
            val itemTypes = mutableListOf<String>() // "local" | "net" | "online"
            val itemIds = mutableListOf<String>()   // share/online id, empty for local

            labels.add(getString(R.string.advanced_sync_local_folder))
            itemTypes.add("local")
            itemIds.add("")

            netShares.forEach { share ->
                labels.add(share.name.ifEmpty { share.host })
                itemTypes.add("net")
                itemIds.add(share.id)
            }
            onlineStorages.forEach { storage ->
                labels.add(storage.displayName)
                itemTypes.add("online")
                itemIds.add(storage.id)
            }

            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.select_destination)
                .setItems(labels.toTypedArray()) { _, which ->
                    val type = itemTypes[which]
                    val id = itemIds[which]
                    android.util.Log.d("AdvSyncDest", "Selected dest: type=$type, id=$id")
                    when (type) {
                        "local" -> {
                            // Launch local folder picker in destination mode
                            val intent = Intent(this, StorageBrowserActivity::class.java).apply {
                                putExtra(FileBrowserActivity.EXTRA_ADVANCED_SYNC_DEST_PICKER, true)
                            }
                            destLocalFolderLauncher.launch(intent)
                        }
                        "net" -> {
                            selectedNetworkShareId = id
                            launchNetworkBrowserForDest(id, isOnline = false)
                        }
                        "online" -> {
                            selectedNetworkShareId = id
                            launchNetworkBrowserForDest(id, isOnline = true)
                        }
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        // ── Direction chip selection ────────────────────────────────────────────
        chipDirectionUpload.setOnClickListener { selectDirection("upload") }
        chipDirectionDownload.setOnClickListener { selectDirection("download") }
        chipDirectionTwoway.setOnClickListener { selectDirection("twoway") }

        // Extension mode toggle — show/hide the extensions field
        toggleExtensionMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val visible = if (checkedId == R.id.chipFilterAll) View.GONE else View.VISIBLE
            txtFilterExtensionsLabel?.visibility = visible
            layoutFilterExtensions?.visibility = visible
        }

        // Move files and Sync deletions are mutually exclusive
        switchMoveFiles.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                switchSyncDeletions.isChecked = false
                switchSyncDeletions.isEnabled = false
            } else {
                switchSyncDeletions.isEnabled = true
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
        val periodAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, periods)
        dropdownPeriod.setAdapter(periodAdapter)
        dropdownPeriod.setText(periods[0], false)
        dropdownPeriod.setOnItemClickListener { _, _, position, _ ->
            selectedPeriod = periodValues[position]
            layoutDayOfWeek.visibility = if (selectedPeriod == "weekly") View.VISIBLE else View.GONE
            layoutDayOfMonth.visibility = if (selectedPeriod == "monthly") View.VISIBLE else View.GONE
        }

        val dowAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, daysOfWeek)
        dropdownDayOfWeek.setAdapter(dowAdapter)
        dropdownDayOfWeek.setText(daysOfWeek[0], false)
        dropdownDayOfWeek.setOnItemClickListener { _, _, position, _ ->
            selectedDayOfWeek = daysOfWeekValues[position]
        }

        val domAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, daysOfMonth)
        dropdownDayOfMonth.setAdapter(domAdapter)
        dropdownDayOfMonth.setText(daysOfMonth[0], false)
        dropdownDayOfMonth.setOnItemClickListener { _, _, position, _ ->
            selectedDayOfMonth = daysOfMonthValues[position]
        }

        // ── Conflict chips ─────────────────────────────────────────────────────
        val conflictChips = listOf(chipConflictSkip, chipConflictNewest, chipConflictKeepLocal, chipConflictKeepRemote)
        conflictChips.forEach { chip ->
            chip.setOnClickListener {
                selectedConflictStrategy = when (chip.id) {
                    R.id.chipConflictNewest -> "newest"
                    R.id.chipConflictKeepLocal -> "keep_local"
                    R.id.chipConflictKeepRemote -> "keep_remote"
                    else -> "skip"
                }
                conflictChips.forEach { c ->
                    if (c is com.google.android.material.button.MaterialButton) {
                        c.isCheckable = true
                        c.isChecked = c.id == chip.id
                    }
                }
            }
        }

        // ── Save / Delete ──────────────────────────────────────────────────────
        findViewById<MaterialButton>(R.id.btnSave).setOnClickListener { saveProfile() }
        btnDelete.setOnClickListener { deleteProfile() }

        setupIntervalDropdown()

        profileId = intent.getStringExtra(EXTRA_PROFILE_ID)
        if (profileId != null) {
            loadProfile(profileId!!)
        }
    }

    private fun selectSchedule(type: String) {
        listOf(chipInterval, chipScheduled, chipManual).forEach { c ->
            if (c is com.google.android.material.button.MaterialButton) {
                c.isCheckable = true
                c.isChecked = c.id == when (type) {
                    "scheduled" -> R.id.chipScheduled
                    "manual" -> R.id.chipManual
                    else -> R.id.chipInterval
                }
            }
        }
        layoutIntervalSection.visibility = if (type == "interval") View.VISIBLE else View.GONE
        layoutScheduledSection.visibility = if (type == "scheduled") View.VISIBLE else View.GONE
    }

    private fun selectDirection(dir: String) {
        selectedDirection = dir
        updateDirectionDescription()
        // Update visual selection state for chips
        val selectedId = when (dir) {
            "download" -> R.id.chipDirectionDownload
            "twoway" -> R.id.chipDirectionTwoway
            else -> R.id.chipDirectionUpload
        }
        listOf(chipDirectionUpload, chipDirectionDownload, chipDirectionTwoway).forEach { chip ->
            val isSel = chip.id == selectedId
            if (chip is com.google.android.material.button.MaterialButton) {
                chip.isCheckable = true
                chip.isChecked = isSel
            } else {
                chip.alpha = if (isSel) 1.0f else 0.5f
            }
        }
        updateDirectionSections()
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
        // Update destination button label
        val btnBrowse = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnBrowseRemote)
        btnBrowse?.text = getString(if (isLocal) R.string.advanced_sync_dest_select else R.string.browse_remote)
    }

    private fun updateDirectionSections() {
        layoutConflictResolution.visibility =
            if (selectedDirection == "twoway") View.VISIBLE else View.GONE
        layoutSyncDeletions?.visibility =
            if (selectedDirection == "twoway") View.GONE else View.VISIBLE
        layoutDownloadSubfolders?.visibility =
            if (selectedDirection == "download") View.VISIBLE else View.GONE
        layoutFilters?.visibility =
            if (selectedDirection != "twoway") View.VISIBLE else View.GONE

        // Swap Source/Destination labels for Download direction
        if (selectedDirection == "download") {
            txtSourceLabel.setText(R.string.destination)
            txtDestLabel.setText(R.string.source_phone)
        } else {
            txtSourceLabel.setText(R.string.source_phone)
            txtDestLabel.setText(R.string.destination)
        }

        // Update sync deletions summary and move files text based on direction
        val summaryView = findViewById<android.widget.TextView>(R.id.txtSyncDeletionsSummary)
        summaryView?.setText(
            if (selectedDirection == "download") R.string.sync_deletions_summary_download
            else R.string.sync_deletions_summary_upload
        )
        if (selectedDirection == "download") {
            switchMoveFiles.setText(R.string.move_files_download)
            txtMoveFilesSummary?.setText(R.string.move_files_download_summary)
        } else {
            switchMoveFiles.setText(R.string.move_files_upload)
            txtMoveFilesSummary?.setText(R.string.move_files_upload_summary)
        }
    }

    private fun launchNetworkBrowserForDest(shareId: String, isOnline: Boolean) {
        android.util.Log.d("AdvSyncDest", "launchNetworkBrowserForDest: shareId=$shareId, isOnline=$isOnline")
        val intent = Intent(this, NetworkBrowserActivity::class.java).apply {
            putExtra(NetworkBrowserActivity.EXTRA_SHARE_ID, shareId)
            putExtra("isOnlineStorage", isOnline)
            putExtra(NetworkBrowserActivity.EXTRA_SYNC_FOLDER_PICKER, true)
            putExtra(NetworkBrowserActivity.EXTRA_STORAGE_LABEL, getString(R.string.select_sync_folder))
        }
        destFolderLauncher.launch(intent)
    }

    private fun setupIntervalDropdown() {
        val intervals = listOf(
            getString(R.string.q5_minutes), getString(R.string.q10_minutes),
            getString(R.string.q15_minutes), getString(R.string.q30_minutes),
            getString(R.string.q1_hour), getString(R.string.q6_hours),
            getString(R.string.q12_hours), getString(R.string.q24_hours)
        )
        val intervalValues = listOf(5, 10, 15, 30, 60, 360, 720, 1440)
        val intervalAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, intervals)
        dropdownInterval.setAdapter(intervalAdapter)
        dropdownInterval.setText(intervals[2], false)
        dropdownInterval.setOnItemClickListener { _, _, position, _ ->
            selectedIntervalMinutes = intervalValues[position]
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
        if (profile.direction == "twoway") {
            layoutConflictResolution.visibility = View.VISIBLE
        }

        // Conflict strategy
        selectedConflictStrategy = profile.conflictStrategy
        val conflictChips = listOf(chipConflictSkip, chipConflictNewest, chipConflictKeepLocal, chipConflictKeepRemote)
        val selectedChipId = when (profile.conflictStrategy) {
            "newest" -> R.id.chipConflictNewest
            "keep_local" -> R.id.chipConflictKeepLocal
            "keep_remote" -> R.id.chipConflictKeepRemote
            else -> R.id.chipConflictSkip
        }
        conflictChips.forEach { c ->
            if (c is com.google.android.material.button.MaterialButton) {
                c.isCheckable = true
                c.isChecked = c.id == selectedChipId
            }
        }

        // Schedule
        when (profile.scheduleType) {
            "scheduled" -> {
                selectSchedule("scheduled")
                layoutIntervalSection.visibility = View.GONE
                layoutScheduledSection.visibility = View.VISIBLE

                val pIndex = periodValues.indexOf(profile.scheduledPeriod).coerceAtLeast(0)
                selectedPeriod = profile.scheduledPeriod
                dropdownPeriod.setText(periods[pIndex], false)
                layoutDayOfWeek.visibility = if (selectedPeriod == "weekly") View.VISIBLE else View.GONE
                layoutDayOfMonth.visibility = if (selectedPeriod == "monthly") View.VISIBLE else View.GONE

                val dowIndex = daysOfWeekValues.indexOf(profile.scheduledDayOfWeek).coerceAtLeast(0)
                selectedDayOfWeek = profile.scheduledDayOfWeek
                dropdownDayOfWeek.setText(daysOfWeek[dowIndex], false)

                val domIndex = daysOfMonthValues.indexOf(profile.scheduledDayOfMonth).coerceAtLeast(0)
                selectedDayOfMonth = profile.scheduledDayOfMonth
                dropdownDayOfMonth.setText(daysOfMonth[domIndex], false)

                timePicker.hour = profile.scheduledHour
                timePicker.minute = profile.scheduledMinute
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
                dropdownInterval.setText(intervals[iIndex], false)
            }
        }

        // Toggles
        switchDownloadSubfolders.isChecked = profile.downloadSubfolders
        switchInstantSync.isChecked = profile.instantSyncEnabled
        switchWifiOnly.isChecked = profile.wifiOnly
        switchMoveFiles.isChecked = profile.moveFiles
        editFilterExtensions.setText(profile.extensionFilters)
        editExcludePatterns.setText(profile.excludePatterns)
        editIncludePatterns.setText(profile.includePatterns)
        editMinSize.setText(if (profile.minSizeBytes > 0) {
            ((if (profile.minSizeIsGB) profile.minSizeBytes / (1024*1024*1024) else profile.minSizeBytes / (1024*1024)).toString())
        } else "")
        editMaxSize.setText(if (profile.maxSizeBytes > 0) {
            ((if (profile.maxSizeIsGB) profile.maxSizeBytes / (1024*1024*1024) else profile.maxSizeBytes / (1024*1024)).toString())
        } else "")
        toggleMinSizeUnit.check(if (profile.minSizeIsGB) R.id.chipSizeMinGB else R.id.chipSizeMinMB)
        toggleMaxSizeUnit.check(if (profile.maxSizeIsGB) R.id.chipSizeMaxGB else R.id.chipSizeMaxMB)
        editMinAge.setText(if (profile.minAgeMinutes > 0) (profile.minAgeMinutes / (24*60)).toString() else "")
        editMaxAge.setText(if (profile.maxAgeMinutes > 0) (profile.maxAgeMinutes / (24*60)).toString() else "")
        val extChip = when (profile.extensionMode) {
            "only" -> R.id.chipFilterOnly
            "skip" -> R.id.chipFilterSkip
            else -> R.id.chipFilterAll
        }
        toggleExtensionMode.check(extChip)
        val extVisible = if (profile.extensionMode == "all") View.GONE else View.VISIBLE
        txtFilterExtensionsLabel?.visibility = extVisible
        layoutFilterExtensions?.visibility = extVisible
        switchSyncDeletions.isChecked = profile.syncDeletions
        if (profile.moveFiles) {
            switchSyncDeletions.isEnabled = false
        }
        switchNotifications.isChecked = profile.notificationsEnabled

        btnDelete.visibility = View.VISIBLE
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
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.important_warning)
                    .setMessage(R.string.advanced_sync_same_path_warning)
                    .setIcon(R.drawable.ic_warning)
                    .setPositiveButton(R.string.btn_continue) { _, _ ->
                        proceedSaveProfile(name)
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
                return
            }
        }

        proceedSaveProfile(name)
    }

    private fun proceedSaveProfile(name: String) {
        val scheduleType = when {
            (chipScheduled as? com.google.android.material.button.MaterialButton)?.isChecked == true -> "scheduled"
            (chipManual as? com.google.android.material.button.MaterialButton)?.isChecked == true -> "manual"
            else -> "interval"
        }

        // Show destructive operation warning for Upload/Download with sync deletions enabled
        if (switchSyncDeletions.isChecked && selectedDirection != "twoway") {
            val warningMessage = if (selectedDirection == "upload") {
                R.string.sync_deletions_warning_upload
            } else {
                R.string.sync_deletions_warning_download
            }
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.sync_deletions_warning_title)
                .setMessage(warningMessage)
                .setIcon(R.drawable.ic_warning)
                .setPositiveButton(R.string.btn_continue) { _, _ ->
                    doSaveProfile(name, scheduleType)
                }
                .setNegativeButton(R.string.cancel, null)
                .setNeutralButton(R.string.switch_to_twoway) { _, _ ->
                    // Switch to Two-way and save
                    selectDirection("twoway")
                    doSaveProfile(name, scheduleType)
                }
                .show()
            return
        }

        doSaveProfile(name, scheduleType)
    }

    private fun doSaveProfile(name: String, scheduleType: String) {
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
            scheduleType = scheduleType,
            intervalMinutes = selectedIntervalMinutes,
            scheduledHour = timePicker.hour,
            scheduledMinute = timePicker.minute,
            scheduledPeriod = selectedPeriod,
            scheduledDayOfWeek = selectedDayOfWeek,
            scheduledDayOfMonth = selectedDayOfMonth,
            instantSyncEnabled = switchInstantSync.isChecked,
            syncDeletions = switchSyncDeletions.isChecked,
            moveFiles = switchMoveFiles.isChecked,
            extensionMode = when (toggleExtensionMode.checkedButtonId) {
                R.id.chipFilterOnly -> "only"
                R.id.chipFilterSkip -> "skip"
                else -> "all"
            },
            extensionFilters = editFilterExtensions.text.toString().trim(),
            excludePatterns = editExcludePatterns.text.toString().trim(),
            includePatterns = editIncludePatterns.text.toString().trim(),
            minSizeBytes = (editMinSize.text.toString().toDoubleOrNull() ?: 0.0).let {
                (it * if (toggleMinSizeUnit.checkedButtonId == R.id.chipSizeMinGB) 1024*1024*1024 else 1024*1024).toLong()
            },
            maxSizeBytes = (editMaxSize.text.toString().toDoubleOrNull() ?: 0.0).let {
                (it * if (toggleMaxSizeUnit.checkedButtonId == R.id.chipSizeMaxGB) 1024*1024*1024 else 1024*1024).toLong()
            },
            minSizeIsGB = toggleMinSizeUnit.checkedButtonId == R.id.chipSizeMinGB,
            maxSizeIsGB = toggleMaxSizeUnit.checkedButtonId == R.id.chipSizeMaxGB,
            minAgeMinutes = (editMinAge.text.toString().toLongOrNull() ?: 0L) * 24 * 60,
            maxAgeMinutes = (editMaxAge.text.toString().toLongOrNull() ?: 0L) * 24 * 60,
            downloadSubfolders = switchDownloadSubfolders.isChecked,
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
}
