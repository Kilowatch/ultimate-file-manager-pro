package za.kilowatch.ultimatefilemanager.sync

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.TextView
import android.widget.TimePicker
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.network.NetworkShare
import za.kilowatch.ultimatefilemanager.network.NetworkShareManagerActivity
import za.kilowatch.ultimatefilemanager.network.NetworkShareRepository
import za.kilowatch.ultimatefilemanager.storage.FileBrowserActivity
import za.kilowatch.ultimatefilemanager.storage.StorageBrowserActivity
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper

class SyncEditActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PROFILE_ID = "profile_id"
        /** Extra to pass a pre-selected share ID when launching from the share dropdown */
        const val EXTRA_SHARE_ID = "sync_share_id"
    }

    private var profileId: String? = null
    private var localUri: String = ""
    private var localDisplayPath: String = ""
    private var selectedNetworkShareId: String? = null
    private var selectedIntervalMinutes: Int = 60
    private var selectedRemotePath: String = ""
    private var selectedScheduleType: String = "interval"

    private lateinit var editName: TextInputEditText
    private lateinit var txtLocalPath: TextView
    private lateinit var txtRemotePath: TextView
    private lateinit var dropdownShare: AutoCompleteTextView
    private lateinit var dropdownInterval: AutoCompleteTextView
    private lateinit var dropdownPeriod: AutoCompleteTextView
    private lateinit var dropdownDayOfWeek: AutoCompleteTextView
    private lateinit var dropdownDayOfMonth: AutoCompleteTextView
    private lateinit var chipInterval: MaterialButton
    private lateinit var chipScheduled: MaterialButton
    private lateinit var layoutIntervalSection: View
    private lateinit var layoutScheduledSection: View
    private lateinit var layoutDayOfWeek: View
    private lateinit var layoutDayOfMonth: View
    private lateinit var timePicker: TimePicker
    private lateinit var switchNotifications: MaterialSwitch
    private lateinit var btnDelete: MaterialButton

    private lateinit var repo: SyncProfileRepository
    private lateinit var netRepo: NetworkShareRepository
    private var networkShares = listOf<NetworkShare>()

    // Period / DOW / DOM values
    private val periods by lazy { listOf(getString(R.string.daily), getString(R.string.weekly), getString(R.string.monthly)) }
    private val periodValues = listOf("daily", "weekly", "monthly")
    private val daysOfWeek by lazy { listOf(getString(R.string.monday),getString(R.string.tuesday),getString(R.string.wednesday),getString(R.string.thursday),getString(R.string.friday),getString(R.string.saturday),getString(R.string.sunday)) }
    private val daysOfWeekValues = listOf(1,2,3,4,5,6,7)
    private val daysOfMonth = (1..28).map { it.toString() }
    private val daysOfMonthValues = (1..28).toList()
    private var selectedPeriod = "daily"
    private var selectedDayOfWeek = 1
    private var selectedDayOfMonth = 1

    /** Launches StorageBrowserActivity in sync folder picker mode for picking the local source folder */
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

    /** Launches NetworkBrowserActivity in sync folder picker mode */
    private val remoteFolderLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val path = result.data?.getStringExtra(
                za.kilowatch.ultimatefilemanager.network.NetworkBrowserActivity.RESULT_SELECTED_SYNC_PATH
            ) ?: return@registerForActivityResult
            selectedRemotePath = path
            txtRemotePath.text = if (path.isEmpty()) "/" else "/$path"
        }
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        za.kilowatch.ultimatefilemanager.settings.ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_sync_edit)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        repo = SyncProfileRepository.getInstance(this)
        netRepo = NetworkShareRepository.getInstance(this)

        editName = findViewById(R.id.editName)
        txtLocalPath = findViewById(R.id.txtLocalPath)
        txtRemotePath = findViewById(R.id.txtRemotePath)
        dropdownShare = findViewById(R.id.dropdownShare)
        dropdownInterval = findViewById(R.id.dropdownInterval)
        dropdownPeriod = findViewById(R.id.dropdownPeriod)
        dropdownDayOfWeek = findViewById(R.id.dropdownDayOfWeek)
        dropdownDayOfMonth = findViewById(R.id.dropdownDayOfMonth)
        chipInterval = findViewById(R.id.chipInterval)
        chipScheduled = findViewById(R.id.chipScheduled)
        layoutIntervalSection = findViewById(R.id.layoutIntervalSection)
        layoutScheduledSection = findViewById(R.id.layoutScheduledSection)
        layoutDayOfWeek = findViewById(R.id.layoutDayOfWeek)
        layoutDayOfMonth = findViewById(R.id.layoutDayOfMonth)
        timePicker = findViewById(R.id.timePicker)
        switchNotifications = findViewById(R.id.switchNotifications)
        btnDelete = findViewById(R.id.btnDelete)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        // ── Local folder picker (uses UFM storage browser) ──────────────────
        findViewById<MaterialButton>(R.id.btnSelectLocal).setOnClickListener {
            val intent = Intent(this, StorageBrowserActivity::class.java).apply {
                putExtra(StorageBrowserActivity.EXTRA_SYNC_FOLDER_PICKER, true)
            }
            sourceFolderLauncher.launch(intent)
        }

        // ── Remote folder browser ────────────────────────────────────────────
        findViewById<MaterialButton>(R.id.btnBrowseRemote).setOnClickListener {
            val shareId = selectedNetworkShareId
            if (shareId == null) {
                Toast.makeText(this, R.string.please_select_a_network_share, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val intent = Intent(this, za.kilowatch.ultimatefilemanager.network.NetworkBrowserActivity::class.java).apply {
                putExtra(za.kilowatch.ultimatefilemanager.network.NetworkBrowserActivity.EXTRA_SHARE_ID, shareId)
                putExtra(za.kilowatch.ultimatefilemanager.network.NetworkBrowserActivity.EXTRA_SYNC_FOLDER_PICKER, true)
                putExtra(za.kilowatch.ultimatefilemanager.network.NetworkBrowserActivity.EXTRA_STORAGE_LABEL, getString(R.string.select_sync_folder))
            }
            remoteFolderLauncher.launch(intent)
        }

        // ── Schedule chips ───────────────────────────────────────────────────
        chipInterval.setOnClickListener { selectSchedule("interval") }
        chipScheduled.setOnClickListener { selectSchedule("scheduled") }
        selectSchedule("interval")

        // ── Period dropdown ──────────────────────────────────────────────────
        val periodAdapter = ArrayAdapter(this, R.layout.item_dropdown_popup, android.R.id.text1, periods)
        dropdownPeriod.setAdapter(periodAdapter)
        dropdownPeriod.setText(periods[0], false)
        dropdownPeriod.setOnItemClickListener { _, _, position, _ ->
            selectedPeriod = periodValues[position]
            layoutDayOfWeek.visibility = if (selectedPeriod == "weekly") View.VISIBLE else View.GONE
            layoutDayOfMonth.visibility = if (selectedPeriod == "monthly") View.VISIBLE else View.GONE
        }

        // ── Day of week dropdown ─────────────────────────────────────────────
        val dowAdapter = ArrayAdapter(this, R.layout.item_dropdown_popup, android.R.id.text1, daysOfWeek)
        dropdownDayOfWeek.setAdapter(dowAdapter)
        dropdownDayOfWeek.setText(daysOfWeek[0], false)
        dropdownDayOfWeek.setOnItemClickListener { _, _, position, _ ->
            selectedDayOfWeek = daysOfWeekValues[position]
        }

        // ── Day of month dropdown ────────────────────────────────────────────
        val domAdapter = ArrayAdapter(this, R.layout.item_dropdown_popup, android.R.id.text1, daysOfMonth)
        dropdownDayOfMonth.setAdapter(domAdapter)
        dropdownDayOfMonth.setText(daysOfMonth[0], false)
        dropdownDayOfMonth.setOnItemClickListener { _, _, position, _ ->
            selectedDayOfMonth = daysOfMonthValues[position]
        }

        // ── Save / Delete buttons ────────────────────────────────────────────
        findViewById<MaterialButton>(R.id.btnSave).setOnClickListener { saveProfile() }
        btnDelete.setOnClickListener { showDeleteConfirmDialog() }

        setupShareDropdown()
        setupIntervalDropdown()

        profileId = intent.getStringExtra(EXTRA_PROFILE_ID)
        if (profileId != null) {
            loadProfile(profileId!!)
        } else {
            // Check if there are any network shares; if not, show the setup dialog
            val shares = netRepo.getAll()
            if (shares.isEmpty()) {
                showNoShareDialog()
            }
        }
    }

    private fun selectSchedule(type: String) {
        selectedScheduleType = type
        val isInterval = type == "interval"
        chipInterval.isCheckable = true
        chipInterval.isChecked = isInterval
        chipScheduled.isCheckable = true
        chipScheduled.isChecked = !isInterval

        layoutIntervalSection.visibility = if (isInterval) View.VISIBLE else View.GONE
        layoutScheduledSection.visibility = if (isInterval) View.GONE else View.VISIBLE
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Setup helpers
    // ─────────────────────────────────────────────────────────────────────

    private fun setupShareDropdown() {
        networkShares = netRepo.getAll()
        val shareNames = networkShares.map { it.name.ifEmpty { it.host } }
        val shareAdapter = ArrayAdapter(this, R.layout.item_dropdown_popup, android.R.id.text1, shareNames)
        dropdownShare.setAdapter(shareAdapter)
        dropdownShare.setOnItemClickListener { _, _, position, _ ->
            selectedNetworkShareId = networkShares[position].id
            // Reset remote path when a different share is chosen
            selectedRemotePath = ""
            txtRemotePath.setText(R.string.not_selected)
        }
    }

    private fun setupIntervalDropdown() {
        val intervals = listOf(
            getString(R.string.q15_minutes), getString(R.string.q30_minutes),
            getString(R.string.q1_hour), getString(R.string.q6_hours),
            getString(R.string.q12_hours), getString(R.string.q24_hours)
        )
        val intervalValues = listOf(15, 30, 60, 360, 720, 1440)
        val intervalAdapter = ArrayAdapter(this, R.layout.item_dropdown_popup, android.R.id.text1, intervals)
        dropdownInterval.setAdapter(intervalAdapter)
        dropdownInterval.setText(intervals[2], false) // Default: 1 hr
        dropdownInterval.setOnItemClickListener { _, _, position, _ ->
            selectedIntervalMinutes = intervalValues[position]
        }
        selectedIntervalMinutes = 60
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Dialog: no SMB shares configured
    // ─────────────────────────────────────────────────────────────────────

    private fun showNoShareDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_sync_no_share, null)
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogView.findViewById<View>(R.id.btnSetup).setOnClickListener {
            dialog.dismiss()
            startActivity(Intent(this, NetworkShareManagerActivity::class.java))
            finish()
        }

        dialogView.findViewById<View>(R.id.btnCancel).setOnClickListener {
            dialog.dismiss()
            finish()
        }

        dialog.show()
    }

    private fun showDeleteConfirmDialog() {
        val currentName = editName.text.toString().trim().ifEmpty { getString(R.string.sync_now) }
        val dialogView = layoutInflater.inflate(R.layout.dialog_sync_profile_delete_confirm, null)
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

    // ─────────────────────────────────────────────────────────────────────
    //  Load / Save / Delete
    // ─────────────────────────────────────────────────────────────────────

    private fun loadProfile(id: String) {
        val profile = repo.getById(id) ?: return

        editName.setText(profile.name)
        localUri = profile.localUri
        localDisplayPath = profile.localDisplayPath
        txtLocalPath.text = localDisplayPath

        selectedNetworkShareId = profile.networkShareId
        val shareIndex = networkShares.indexOfFirst { it.id == profile.networkShareId }
        if (shareIndex >= 0) {
            dropdownShare.setText(networkShares[shareIndex].let { it.name.ifEmpty { it.host } }, false)
        }

        selectedRemotePath = profile.remotePath
        txtRemotePath.text = if (profile.remotePath.isEmpty()) "/" else "/${profile.remotePath}"

        // Schedule type
        if (profile.scheduleType == "scheduled") {
            selectSchedule("scheduled")

            // Period
            val pIndex = periodValues.indexOf(profile.scheduledPeriod).coerceAtLeast(0)
            selectedPeriod = profile.scheduledPeriod
            dropdownPeriod.setText(periods[pIndex], false)
            layoutDayOfWeek.visibility = if (selectedPeriod == "weekly") View.VISIBLE else View.GONE
            layoutDayOfMonth.visibility = if (selectedPeriod == "monthly") View.VISIBLE else View.GONE

            // Day of week
            val dowIndex = daysOfWeekValues.indexOf(profile.scheduledDayOfWeek).coerceAtLeast(0)
            selectedDayOfWeek = profile.scheduledDayOfWeek
            dropdownDayOfWeek.setText(daysOfWeek[dowIndex], false)

            // Day of month
            val domIndex = daysOfMonthValues.indexOf(profile.scheduledDayOfMonth).coerceAtLeast(0)
            selectedDayOfMonth = profile.scheduledDayOfMonth
            dropdownDayOfMonth.setText(daysOfMonth[domIndex], false)

            // Time
            timePicker.hour = profile.scheduledHour
            timePicker.minute = profile.scheduledMinute
        } else {
            selectSchedule("interval")
            val intervalValues = listOf(15, 30, 60, 360, 720, 1440)
            val intervals = listOf(
                getString(R.string.q15_minutes), getString(R.string.q30_minutes),
                getString(R.string.q1_hour), getString(R.string.q6_hours),
                getString(R.string.q12_hours), getString(R.string.q24_hours)
            )
            val iIndex = intervalValues.indexOf(profile.intervalMinutes).coerceAtLeast(0)
            selectedIntervalMinutes = profile.intervalMinutes
            dropdownInterval.setText(intervals[iIndex], false)
        }

        switchNotifications.isChecked = profile.notificationsEnabled
        btnDelete.visibility = View.VISIBLE
    }

    private fun saveProfile() {
        val name = editName.text.toString().trim()

        if (name.isEmpty() || localUri.isEmpty() || selectedNetworkShareId == null) {
            Toast.makeText(this, R.string.please_fill_all_required_fields, Toast.LENGTH_SHORT).show()
            return
        }

        val isScheduled = selectedScheduleType == "scheduled"

        val profile = SyncProfile(
            id = profileId ?: java.util.UUID.randomUUID().toString(),
            name = name,
            localUri = localUri,
            localDisplayPath = localDisplayPath,
            networkShareId = selectedNetworkShareId!!,
            remotePath = selectedRemotePath,
            scheduleType = if (isScheduled) "scheduled" else "interval",
            intervalMinutes = selectedIntervalMinutes,
            scheduledHour = timePicker.hour,
            scheduledMinute = timePicker.minute,
            scheduledPeriod = selectedPeriod,
            scheduledDayOfWeek = selectedDayOfWeek,
            scheduledDayOfMonth = selectedDayOfMonth,
            enabled = true,
            notificationsEnabled = switchNotifications.isChecked,
            lastSyncTime = repo.getById(profileId ?: "")?.lastSyncTime ?: 0L,
            lastSyncFileCount = repo.getById(profileId ?: "")?.lastSyncFileCount ?: 0
        )

        repo.save(profile)
        SyncScheduler.scheduleSync(this, profile)
        finish()
    }

    private fun deleteProfile() {
        profileId?.let {
            repo.delete(it)
            SyncScheduler.cancelSync(this, it)
        }
        finish()
    }
}
