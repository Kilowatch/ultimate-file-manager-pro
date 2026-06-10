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
        setContentView(R.layout.activity_sync_manager)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        repo = SyncProfileRepository.getInstance(this)
        
        layoutEmptyState = findViewById(R.id.layoutEmptyState)
        recycler = findViewById(R.id.recyclerSyncProfiles)

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { navigateBack() }

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                navigateBack()
            }
        })

        findViewById<ExtendedFloatingActionButton>(R.id.fabAdd).setOnClickListener {
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
        adapter = SyncProfileAdapter(
            onToggle = { profile, isEnabled ->
                val updated = profile.copy(enabled = isEnabled)
                repo.save(updated)
                SyncScheduler.scheduleSync(this, updated)
                loadProfiles()
                showSnackbar(if (isEnabled) getString(R.string.sync_enabled) else getString(R.string.sync_disabled))
            },
            onEdit = { profile ->
                val intent = Intent(this, SyncEditActivity::class.java).apply {
                    putExtra(SyncEditActivity.EXTRA_PROFILE_ID, profile.id)
                }
                startActivity(intent)
            },
            onDelete = { profile ->
                repo.delete(profile.id)
                SyncScheduler.cancelSync(this, profile.id)
                loadProfiles()
                showSnackbar(getString(R.string.profile_deleted))
            },
            onSyncNow = { profile ->
                Toast.makeText(this, R.string.sync_triggered_locally_background_job, Toast.LENGTH_SHORT).show()
                // Force run once by scheduling it immediately
                val workRequest = androidx.work.OneTimeWorkRequestBuilder<SyncWorker>()
                    .setInputData(androidx.work.workDataOf("PROFILE_ID" to profile.id))
                    .setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .build()
                androidx.work.WorkManager.getInstance(this).enqueue(workRequest)
            }
        )
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
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
