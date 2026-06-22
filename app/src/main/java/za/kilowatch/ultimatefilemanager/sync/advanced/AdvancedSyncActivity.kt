package za.kilowatch.ultimatefilemanager.sync.advanced

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.snackbar.Snackbar
import org.json.JSONArray
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper

class AdvancedSyncActivity : AppCompatActivity() {

    private lateinit var repo: AdvancedSyncProfileRepository
    private lateinit var recycler: RecyclerView
    private lateinit var layoutEmpty: android.view.View
    private lateinit var fabAddProfile: ExtendedFloatingActionButton
    private var adapter: AdvancedSyncProfileAdapter? = null

    private val editLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* profiles reload in onResume */ }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or denied — proceed either way */ }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        za.kilowatch.ultimatefilemanager.settings.ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val isTv = za.kilowatch.ultimatefilemanager.util.DeviceUtils.isTvDevice(this)
        setContentView(
            if (isTv) R.layout.activity_advanced_sync_tv
            else R.layout.activity_advanced_sync
        )

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        repo = AdvancedSyncProfileRepository.getInstance(this)

        recycler = findViewById(R.id.recyclerSyncProfiles)
        layoutEmpty = findViewById(R.id.layoutEmpty)
        fabAddProfile = findViewById(R.id.fabAddProfile)

        findViewById<android.view.View>(R.id.btnBack).setOnClickListener {
            navigateBack()
        }

        recycler.layoutManager = LinearLayoutManager(this)

        fabAddProfile.setOnClickListener {
            val intent = Intent(this, AdvancedSyncEditActivity::class.java)
            editLauncher.launch(intent)
        }

        // Request notification permission on Android 13+
        requestNotificationPermission()

        // Re-register instant sync watchers on app start
        InstantSyncWatcher.rewatchAll(this)

        // Show premium snackbar
        showPremiumSnackbar()
    }

    override fun onResume() {
        super.onResume()
        loadProfiles()
    }

    private fun loadProfiles() {
        val profiles = repo.getAll()
        val isEmpty = profiles.isEmpty()

        recycler.visibility = if (isEmpty) android.view.View.GONE else android.view.View.VISIBLE
        layoutEmpty.visibility = if (isEmpty) android.view.View.VISIBLE else android.view.View.GONE

        if (!isEmpty) {
            adapter = AdvancedSyncProfileAdapter(
                onToggle = { profile, enabled ->
                    val updated = profile.copy(enabled = enabled)
                    repo.save(updated)
                    AdvancedSyncScheduler.scheduleSync(this, updated)
                    if (updated.instantSyncEnabled && enabled) {
                        InstantSyncWatcher.startWatching(this, updated)
                    } else {
                        InstantSyncWatcher.stopWatching(updated.id)
                    }
                    loadProfiles()
                    Toast.makeText(this, if (enabled) R.string.sync_enabled else R.string.sync_disabled, Toast.LENGTH_SHORT).show()
                },
                onEdit = { profile ->
                    val intent = Intent(this, AdvancedSyncEditActivity::class.java).apply {
                        putExtra(AdvancedSyncEditActivity.EXTRA_PROFILE_ID, profile.id)
                    }
                    editLauncher.launch(intent)
                },
                onDelete = { profile ->
                    MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.action_delete)
                        .setMessage(getString(R.string.delete_profile_confirm, profile.name))
                        .setPositiveButton(R.string.action_delete) { _, _ ->
                            repo.delete(profile.id)
                            AdvancedSyncScheduler.cancelSync(this, profile.id)
                            InstantSyncWatcher.stopWatching(profile.id)
                            loadProfiles()
                            Toast.makeText(this, R.string.profile_deleted, Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                },
                onSyncNow = { profile ->
                    val inputData = workDataOf("PROFILE_ID" to profile.id)
                    val workRequest = OneTimeWorkRequestBuilder<AdvancedSyncWorker>()
                        .setInputData(inputData)
                        .build()
                    WorkManager.getInstance(this).enqueue(workRequest)
                    Toast.makeText(this, R.string.sync_now, Toast.LENGTH_SHORT).show()
                },
                onViewConflictLog = { profile ->
                    showConflictLog(profile)
                }
            )
            recycler.adapter = adapter
            adapter?.submitList(profiles)
        }
    }

    private fun showConflictLog(profile: AdvancedSyncProfile) {
        val logText = if (profile.conflictLogJson.isBlank()) {
            getString(R.string.conflict_log_empty)
        } else {
            try {
                val arr = JSONArray(profile.conflictLogJson)
                if (arr.length() == 0) {
                    getString(R.string.conflict_log_empty)
                } else {
                    val sb = StringBuilder()
                    for (i in 0 until arr.length()) {
                        val entry = arr.getJSONObject(i)
                        sb.append("• ")
                            .append(entry.optString("file", "?"))
                            .append(" → ")
                            .append(entry.optString("resolution", "?"))
                            .append("\n")
                    }
                    sb.toString()
                }
            } catch (e: Exception) {
                profile.conflictLogJson
            }
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.conflict_log_title) + " — " + profile.name)
            .setMessage(logText)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun navigateBack() {
        if (isTaskRoot) {
            startActivity(Intent(this, za.kilowatch.ultimatefilemanager.storage.StorageBrowserActivity::class.java))
        }
        finish()
    }

    private fun showPremiumSnackbar() {
        val rootView = findViewById<android.view.View>(R.id.main)
        Snackbar.make(
            rootView, getString(R.string.opening_advanced_sync),
            Snackbar.LENGTH_SHORT
        )
            .setBackgroundTint(getColor(R.color.ufm_surface_variant))
            .setTextColor(getColor(R.color.ufm_text_primary))
            .setActionTextColor(getColor(R.color.ufm_primary))
            .show()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
