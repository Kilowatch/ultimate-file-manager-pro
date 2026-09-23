package za.kilowatch.ultimatefilemanager.sync

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.snackbar.Snackbar
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.settings.FontSizeHelper
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper

class SyncManagerActivity : AppCompatActivity() {

    private lateinit var adapter: SyncProfileAdapter
    private lateinit var repo: SyncProfileRepository
    private lateinit var layoutEmptyState: View
    private lateinit var recycler: RecyclerView

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Handle results if needed
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
            if (isTv) R.layout.activity_sync_manager_tv
            else R.layout.activity_sync_manager
        )

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        repo = SyncProfileRepository.getInstance(this)
        
        layoutEmptyState = findViewById(R.id.layoutEmptyState)
        recycler = findViewById(R.id.recyclerSyncProfiles)

        findViewById<View>(R.id.btnBack).setOnClickListener { navigateBack() }
        findViewById<View>(R.id.btnAddProfile)?.setOnClickListener {
            startActivity(Intent(this, SyncEditActivity::class.java))
        }

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                navigateBack()
            }
        })

        findViewById<ExtendedFloatingActionButton>(R.id.fabAdd)?.setOnClickListener {
            startActivity(Intent(this, SyncEditActivity::class.java))
        }

        setupRecyclerView()
        checkPermissions()
    }

    override fun onResume() {
        super.onResume()
        loadProfiles()
    }

    private fun setupRecyclerView() {
        val isTv = za.kilowatch.ultimatefilemanager.util.DeviceUtils.isTvDevice(this)
        adapter = SyncProfileAdapter(
            onToggle = { profile, isEnabled ->
                val updated = profile.copy(enabled = isEnabled)
                repo.save(updated)
                SyncScheduler.scheduleSync(this, updated)
                loadProfiles()
                showSnackbar(if (isEnabled) getString(R.string.sync_enabled) else getString(R.string.sync_disabled))
            },
            onEdit = { profile ->
                if (isTv) {
                    showTvActionDialog(profile)
                } else {
                    val intent = Intent(this, SyncEditActivity::class.java).apply {
                        putExtra(SyncEditActivity.EXTRA_PROFILE_ID, profile.id)
                    }
                    startActivity(intent)
                }
            },
            onDelete = { profile ->
                showDeleteConfirmDialog(profile)
            },
            onSyncNow = { profile ->
                triggerSyncNow(profile)
            }
        )
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
    }

    private fun triggerSyncNow(profile: SyncProfile) {
        Toast.makeText(this, R.string.sync_triggered_locally_background_job, Toast.LENGTH_SHORT).show()
        val workRequest = androidx.work.OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(androidx.work.workDataOf("PROFILE_ID" to profile.id))
            .setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        androidx.work.WorkManager.getInstance(this).enqueue(workRequest)
    }

    private fun showTvActionDialog(profile: SyncProfile) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_sync_profile_actions_tv, null)
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogView.findViewById<TextView>(R.id.txtTitle).text = profile.name
        dialogView.findViewById<TextView>(R.id.txtSubtitle).text = profile.localDisplayPath

        val btnToggle = dialogView.findViewById<android.widget.Button>(R.id.btnToggle)
        btnToggle.text = if (profile.enabled) getString(R.string.sync_action_disable) else getString(R.string.sync_action_enable)
        btnToggle.setOnClickListener {
            dialog.dismiss()
            val updated = profile.copy(enabled = !profile.enabled)
            repo.save(updated)
            SyncScheduler.scheduleSync(this, updated)
            loadProfiles()
            showSnackbar(if (updated.enabled) getString(R.string.sync_enabled) else getString(R.string.sync_disabled))
        }

        dialogView.findViewById<android.widget.Button>(R.id.btnEdit).setOnClickListener {
            dialog.dismiss()
            val intent = Intent(this, SyncEditActivity::class.java).apply {
                putExtra(SyncEditActivity.EXTRA_PROFILE_ID, profile.id)
            }
            startActivity(intent)
        }

        dialogView.findViewById<android.widget.Button>(R.id.btnSyncNow).setOnClickListener {
            dialog.dismiss()
            triggerSyncNow(profile)
        }

        dialogView.findViewById<android.widget.Button>(R.id.btnDelete).setOnClickListener {
            dialog.dismiss()
            showDeleteConfirmDialog(profile)
        }

        dialogView.findViewById<android.widget.Button>(R.id.btnCancel).setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showDeleteConfirmDialog(profile: SyncProfile) {
        val isTv = za.kilowatch.ultimatefilemanager.util.DeviceUtils.isTvDevice(this)
        val layoutRes = if (isTv) R.layout.dialog_sync_profile_delete_confirm_tv else R.layout.dialog_sync_profile_delete_confirm
        val dialogView = layoutInflater.inflate(layoutRes, null)
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogView.findViewById<TextView>(R.id.txtTitle).text =
            getString(R.string.delete_confirm_single, profile.name)
        dialogView.findViewById<TextView>(R.id.txtMessage).text =
            getString(R.string.sync_delete_profile_confirm_msg, profile.name)

        dialogView.findViewById<View>(R.id.btnDelete).setOnClickListener {
            dialog.dismiss()
            repo.delete(profile.id)
            SyncScheduler.cancelSync(this, profile.id)
            loadProfiles()
            showSnackbar(getString(R.string.profile_deleted))
        }

        dialogView.findViewById<View>(R.id.btnCancel).setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun loadProfiles() {
        val profiles = repo.getAll()
        adapter.submitList(profiles)
        layoutEmptyState.visibility = if (profiles.isEmpty()) View.VISIBLE else View.GONE
        recycler.visibility = if (profiles.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun checkPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (permissions.isNotEmpty()) {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }
    
    private fun showSnackbar(message: String) {
        Snackbar.make(findViewById(R.id.main), message, Snackbar.LENGTH_SHORT)
            .setBackgroundTint(getColor(R.color.ufm_surface_variant))
            .setTextColor(getColor(R.color.ufm_text_primary))
            .show()
    }

    private fun navigateBack() {
        if (isTaskRoot) {
            val intent = Intent(this, za.kilowatch.ultimatefilemanager.storage.StorageBrowserActivity::class.java)
            startActivity(intent)
        }
        finish()
    }
}
