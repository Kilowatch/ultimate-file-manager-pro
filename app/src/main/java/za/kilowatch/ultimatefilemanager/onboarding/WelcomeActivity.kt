package za.kilowatch.ultimatefilemanager.onboarding

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.storage.StorageBrowserActivity
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import za.kilowatch.ultimatefilemanager.settings.FontSizeHelper
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper

/**
 * Welcome/Onboarding screen that guides the user through granting
 * the required permissions for the file manager to function.
 *
 * Detects whether the device is a phone or Android TV and inflates
 * the appropriate layout. Shows permission cards with policy-aligned
 * descriptions and handles runtime permission requests.
 */
class WelcomeActivity : AppCompatActivity() {

    private lateinit var recyclerPermissions: RecyclerView
    private lateinit var btnContinue: MaterialButton
    private lateinit var permissionAdapter: PermissionAdapter
    private lateinit var permissionItems: List<PermissionItem>

    private var currentPermissionIndex = -1
    private var isTv = false

    // Launcher for standard runtime permissions
    private val permissionLauncher: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            handlePermissionResults(results)
        }

    // Launcher for special access settings (MANAGE_EXTERNAL_STORAGE, REQUEST_INSTALL_PACKAGES)
    private val settingsLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            handleSettingsResult()
        }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        za.kilowatch.ultimatefilemanager.settings.ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Check if onboarding already completed AND permissions still valid
        if (isOnboardingComplete() && areRequiredPermissionsGranted()) {
            navigateToDefaultStartScreen()
            return
        }

        // Detect device type and set layout
        isTv = DeviceUtils.isTvDevice(this)
        if (isTv) {
            setContentView(R.layout.activity_welcome_tv)
        } else {
            setContentView(R.layout.activity_welcome)
        }

        // Handle edge-to-edge insets
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        setupViews()
        setupPermissions()
    }

    override fun onResume() {
        super.onResume()
        // Re-check all permission statuses when returning from settings
        refreshPermissionStatuses()
    }

    private fun setupViews() {
        recyclerPermissions = findViewById(R.id.recyclerPermissions)
        btnContinue = findViewById(R.id.btnContinue)

        permissionAdapter = PermissionAdapter(isTv) { item, position ->
            currentPermissionIndex = position
            requestPermissionForItem(item)
        }

        recyclerPermissions.layoutManager = LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false)
        recyclerPermissions.adapter = permissionAdapter

        btnContinue.setOnClickListener {
            markOnboardingComplete()
            navigateToDefaultStartScreen()
        }

        // TV: swap Continue button bg+text on focus
        if (isTv) {
            val yellowText = getColor(R.color.tv_button_focused_yellow_text)
            val whiteText  = getColor(R.color.tv_text_primary)
            val yellowCsl  = android.content.res.ColorStateList.valueOf(
                getColor(R.color.tv_button_focused_yellow)
            )
            val glassCsl   = android.content.res.ColorStateList.valueOf(0x26FFFFFF.toInt())

            // Force glass-white as default (Material3 may not honour the XML CSL)
            btnContinue.backgroundTintList = glassCsl

            btnContinue.setOnFocusChangeListener { _, hasFocus ->
                btnContinue.backgroundTintList = if (hasFocus) yellowCsl else glassCsl
                btnContinue.setTextColor(if (hasFocus) yellowText else whiteText)
            }
        }
    }

    private fun setupPermissions() {
        permissionItems = PermissionItemFactory.createPermissionItems(this)
        // Check initial statuses
        permissionItems.forEach { item ->
            item.status = checkPermissionStatus(item)
        }
        permissionAdapter.submitList(permissionItems)
        updateContinueButton()
    }

    /**
     * Requests the permission(s) for a given [PermissionItem].
     * All Files Access and Install Apps require a Settings intent.
     */
    private fun requestPermissionForItem(item: PermissionItem) {
        when {
            // MANAGE_EXTERNAL_STORAGE — open Settings
            item.id == "all_files" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                try {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    settingsLauncher.launch(intent)
                } catch (_: Exception) {
                    // Specific settings page doesn't exist — open app details
                    openAppDetailsSettings()
                }
            }
            // QUERY_ALL_PACKAGES — manifest-only, open app details as fallback
            item.id == "query_apps" -> {
                openAppDetailsSettings()
            }
            // REQUEST_INSTALL_PACKAGES — open Settings
            item.id == "install_apps" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    try {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:$packageName")
                        )
                        settingsLauncher.launch(intent)
                    } catch (_: Exception) {
                        openAppDetailsSettings()
                    }
                }
            }
            // Standard runtime permissions
            else -> {
                permissionLauncher.launch(item.permissions.toTypedArray())
            }
        }
    }

    /**
     * Fallback: opens the generic app info/details page in system settings.
     */
    private fun openAppDetailsSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:$packageName")
        )
        settingsLauncher.launch(intent)
    }

    /**
     * Handles runtime permission results.
     */
    private fun handlePermissionResults(results: Map<String, Boolean>) {
        if (currentPermissionIndex < 0) return
        val item = permissionItems[currentPermissionIndex]

        val allGranted = results.values.all { it }
        item.status = if (allGranted) PermissionStatus.GRANTED else PermissionStatus.DENIED
        permissionAdapter.updateItem(currentPermissionIndex, item)
        updateContinueButton()
    }

    /**
     * Handles result from Settings intent (MANAGE_EXTERNAL_STORAGE or install sources).
     */
    private fun handleSettingsResult() {
        if (currentPermissionIndex < 0) return
        val item = permissionItems[currentPermissionIndex]
        item.status = checkPermissionStatus(item)
        permissionAdapter.updateItem(currentPermissionIndex, item)
        updateContinueButton()
    }

    /**
     * Refreshes all permission statuses (called in onResume).
     */
    private fun refreshPermissionStatuses() {
        if (!::permissionItems.isInitialized) return
        permissionItems.forEachIndexed { index, item ->
            val newStatus = checkPermissionStatus(item)
            if (item.status != newStatus) {
                item.status = newStatus
                permissionAdapter.updateItem(index, item)
            }
        }
        updateContinueButton()
    }

    /**
     * Checks whether a permission item is currently granted.
     */
    private fun checkPermissionStatus(item: PermissionItem): PermissionStatus {
        return when {
            item.id == "all_files" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                if (Environment.isExternalStorageManager()) PermissionStatus.GRANTED
                else PermissionStatus.NOT_REQUESTED
            }
            item.id == "query_apps" -> {
                // QUERY_ALL_PACKAGES is a manifest-level permission — auto-granted when declared.
                // Verify by trying to list packages; if it returns results, we have access.
                val packages = packageManager.getInstalledPackages(0)
                if (packages.size > 1) PermissionStatus.GRANTED
                else PermissionStatus.NOT_REQUESTED
            }
            item.id == "install_apps" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && packageManager.canRequestPackageInstalls()) {
                    PermissionStatus.GRANTED
                } else {
                    PermissionStatus.NOT_REQUESTED
                }
            }
            else -> {
                val allGranted = item.permissions.all { perm ->
                    ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
                }
                if (allGranted) PermissionStatus.GRANTED else PermissionStatus.NOT_REQUESTED
            }
        }
    }

    /**
     * Enables the Continue button only when all required (non-optional) permissions are granted.
     */
    private fun updateContinueButton() {
        val requiredGranted = permissionItems
            .filter { !it.isOptional }
            .all { it.status == PermissionStatus.GRANTED }
        btnContinue.isEnabled = requiredGranted
        btnContinue.alpha = if (requiredGranted) 1f else 0.4f
    }

    private fun isOnboardingComplete(): Boolean {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_ONBOARDING_COMPLETE, false)
    }

    /**
     * Checks if all required (non-optional) permissions are currently granted.
     * Called at startup to detect if any permission was revoked since last launch.
     */
    private fun areRequiredPermissionsGranted(): Boolean {
        val items = PermissionItemFactory.createPermissionItems(this)
        return items.filter { !it.isOptional }.all { item ->
            checkPermissionStatus(item) == PermissionStatus.GRANTED
        }
    }

    private fun markOnboardingComplete() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETE, true).apply()
    }

    private fun navigateToDefaultStartScreen() {
        val startScreenId = za.kilowatch.ultimatefilemanager.settings.DefaultStartScreenPreferenceManager.getStartScreenId(this)
        var intent: Intent? = null

        when {
            startScreenId == za.kilowatch.ultimatefilemanager.settings.DefaultStartScreenPreferenceManager.ID_TWIN_WINDOW -> {
                intent = Intent(this, za.kilowatch.ultimatefilemanager.storage.TwinWindowActivity::class.java)
            }
            startScreenId == za.kilowatch.ultimatefilemanager.settings.DefaultStartScreenPreferenceManager.ID_FILE_SERVER -> {
                intent = Intent(this, za.kilowatch.ultimatefilemanager.server.ServerHostActivity::class.java)
            }
            startScreenId.startsWith(za.kilowatch.ultimatefilemanager.settings.DefaultStartScreenPreferenceManager.PREFIX_STORAGE) -> {
                val storageId = startScreenId.removePrefix(za.kilowatch.ultimatefilemanager.settings.DefaultStartScreenPreferenceManager.PREFIX_STORAGE)
                val connectedStorages = za.kilowatch.ultimatefilemanager.storage.StorageBrowserActivity.getConnectedStorages(this, localOnly = false)
                val targetStorage = connectedStorages.find { it.id == storageId }
                
                if (targetStorage != null) {
                    if (targetStorage.isNetworkRoot) {
                        intent = Intent(this, za.kilowatch.ultimatefilemanager.network.NetworkBrowserActivity::class.java).apply {
                            putExtra(za.kilowatch.ultimatefilemanager.network.NetworkBrowserActivity.EXTRA_SHARE_ID, targetStorage.id)
                        }
                    } else {
                        intent = Intent(this, za.kilowatch.ultimatefilemanager.storage.FileBrowserActivity::class.java).apply {
                            putExtra(za.kilowatch.ultimatefilemanager.storage.FileBrowserActivity.EXTRA_MOUNT_PATH, targetStorage.mountPath)
                            putExtra(za.kilowatch.ultimatefilemanager.storage.FileBrowserActivity.EXTRA_STORAGE_LABEL, targetStorage.label)
                            putExtra(za.kilowatch.ultimatefilemanager.storage.FileBrowserActivity.EXTRA_STORAGE_ID, targetStorage.id)
                            putExtra(za.kilowatch.ultimatefilemanager.storage.FileBrowserActivity.EXTRA_STORAGE_TYPE, if (targetStorage.isRemovable) "external" else "internal")
                        }
                    }
                }
            }
        }

        // Fallback to StorageBrowserActivity if the ID is STORAGE_BROWSER or if the target drive was disconnected/not found
        if (intent == null) {
            intent = Intent(this, za.kilowatch.ultimatefilemanager.storage.StorageBrowserActivity::class.java)
        }

        // Ensure that if we bypass StorageBrowser, the target activity is the task root
        // so it can synthesize the back stack correctly.
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    companion object {
        private const val PREFS_NAME = "acceptance_prefs"
        private const val KEY_ONBOARDING_COMPLETE = "onboarding_complete"
    }
}
