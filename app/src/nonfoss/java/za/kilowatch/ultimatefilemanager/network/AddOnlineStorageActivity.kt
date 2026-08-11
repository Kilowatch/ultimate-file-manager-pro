package za.kilowatch.ultimatefilemanager.network

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import za.kilowatch.ultimatefilemanager.BuildConfig
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import za.kilowatch.ultimatefilemanager.settings.ThemeHelper
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import za.kilowatch.ultimatefilemanager.util.GoRoLog
import java.util.concurrent.atomic.AtomicInteger

class AddOnlineStorageActivity : AppCompatActivity() {

    private lateinit var repo: OnlineStorageRepository
    private var isAuthInProgress = false
    private var isTv = false
    private var lastAuthStartTime = 0L
    private var isResumedState = false
    // Bug 4 fix: guard against re-arming auth after a successful connection
    private var isConnected = false

    companion object {
        const val ONEDRIVE_SCOPES = "Files.ReadWrite User.Read"
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        isTv = DeviceUtils.isTvDevice(this)
        setContentView(
            if (isTv) R.layout.activity_add_online_storage_tv
            else R.layout.activity_add_online_storage
        )

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(sb.left, sb.top, sb.right, sb.bottom)
            insets
        }

        repo = OnlineStorageRepository.getInstance(this)

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }

        val toggleGroup1 = findViewById<MaterialButtonToggleGroup>(R.id.toggleGroupProvider1)
        val toggleGroup2 = findViewById<MaterialButtonToggleGroup>(R.id.toggleGroupProvider2)
        val toggleGroup3 = findViewById<MaterialButtonToggleGroup>(R.id.toggleGroupProvider3)
        val toggleGroup4 = findViewById<MaterialButtonToggleGroup>(R.id.toggleGroupProvider4)
        val btnAuthenticate = findViewById<MaterialButton>(R.id.btnAuthenticate)
        val tvOneDriveNote = findViewById<TextView>(R.id.tvOneDriveNote)

        // Initial state logic
        if (toggleGroup1.checkedButtonId == R.id.chipOneDrive) {
            tvOneDriveNote.visibility = View.VISIBLE
        } else {
            tvOneDriveNote.visibility = View.GONE
        }

        toggleGroup1.addOnButtonCheckedListener { group, checkedId, isChecked ->
            if (isChecked) {
                toggleGroup2.clearChecked()
                toggleGroup3.clearChecked()
                toggleGroup4.clearChecked()
                if (checkedId == R.id.chipOneDrive) {
                    btnAuthenticate.setText(R.string.add_online_storage_auth_onedrive)
                    tvOneDriveNote.visibility = View.VISIBLE
                } else if (checkedId == R.id.chipGoogleDrive) {
                    btnAuthenticate.setText(R.string.add_online_storage_auth_gdrive)
                    tvOneDriveNote.visibility = View.GONE
                }
            }
        }

        toggleGroup2.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                toggleGroup1.clearChecked()
                toggleGroup3.clearChecked()
                toggleGroup4.clearChecked()
                tvOneDriveNote.visibility = View.GONE
                when (checkedId) {
                    R.id.chipDropbox -> btnAuthenticate.setText(R.string.add_online_storage_auth_dropbox)
                    R.id.chipWebDav  -> btnAuthenticate.setText(R.string.add_online_storage_auth_webdav)
                }
            }
        }

        toggleGroup3.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                toggleGroup1.clearChecked()
                toggleGroup2.clearChecked()
                toggleGroup4.clearChecked()
                tvOneDriveNote.visibility = View.GONE
                if (checkedId == R.id.chipAwsS3) {
                    btnAuthenticate.setText(R.string.add_online_storage_auth_aws_s3)
                } else if (checkedId == R.id.chipIDriveE2) {
                    btnAuthenticate.setText(R.string.add_online_storage_auth_idrive_e2)
                }
            }
        }

        // RClone row — clear other rows when selected
        toggleGroup4.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                toggleGroup1.clearChecked()
                toggleGroup2.clearChecked()
                toggleGroup3.clearChecked()
                tvOneDriveNote.visibility = View.GONE
                if (checkedId == R.id.chipRclone) {
                    btnAuthenticate.setText(R.string.add_online_storage_auth_rclone)
                }
            }
        }

        btnAuthenticate.setOnClickListener {
            val id1 = toggleGroup1.checkedButtonId
            val id2 = toggleGroup2.checkedButtonId
            val id3 = toggleGroup3.checkedButtonId
            val id4 = toggleGroup4.checkedButtonId
            val checkedId = when {
                id1 != View.NO_ID -> id1
                id2 != View.NO_ID -> id2
                id3 != View.NO_ID -> id3
                id4 != View.NO_ID -> id4
                else -> View.NO_ID
            }

            when (checkedId) {
                R.id.chipOneDrive    -> startOneDriveAuthFlow()
                R.id.chipGoogleDrive -> showGoogleDriveScopeDialog()
                R.id.chipDropbox     -> startDropboxAuthFlow()
                R.id.chipWebDav      -> startWebDavSetupFlow()
                R.id.chipAwsS3       -> startS3AuthFlow(OnlineStorageProvider.AWS_S3)
                R.id.chipIDriveE2    -> startS3AuthFlow(OnlineStorageProvider.IDRIVE_E2)
                R.id.chipRclone      -> startRCloneFlow()
            }
        }

        // Hide OneDrive on Amazon builds — MSAL (OneDrive's auth library) transitively
        // pulls in Google Play Services which Amazon's policy scanner rejects.
        if (!BuildConfig.ONEDRIVE_ENABLED) {
            findViewById<View>(R.id.chipOneDrive)?.visibility = View.GONE
            tvOneDriveNote.visibility = View.GONE
            // If OneDrive was the pre-selected chip, clear the selection so the
            // toggle group starts with nothing checked (user must choose explicitly).
            if (toggleGroup1.checkedButtonId == R.id.chipOneDrive) {
                toggleGroup1.clearChecked()
                btnAuthenticate.setText(R.string.add_online_storage_auth_onedrive) // will be overridden on first tap
            }
        }

        // TV Focus Routing Reinforcement
        if (isTv) {
            findViewById<View?>(R.id.chipAwsS3)?.nextFocusDownId = R.id.chipRclone
            findViewById<View?>(R.id.chipIDriveE2)?.nextFocusDownId = R.id.chipRclone
            findViewById<View?>(R.id.chipRclone)?.apply {
                nextFocusUpId = R.id.chipIDriveE2
                nextFocusDownId = R.id.btnAuthenticate
            }
            findViewById<View?>(R.id.btnAuthenticate)?.nextFocusUpId = R.id.chipRclone
        }
    }

    override fun onResume() {
        super.onResume()
        isResumedState = true
        GoRoLog.d("GoRoAuth", "[${hashCode()}] AddOnlineStorageActivity: onResume. isAuthInProgress=$isAuthInProgress")
    }

    override fun onPause() {
        super.onPause()
        isResumedState = false
        // Log exactly what is on top of us when we pause
        val topActivity = try {
            val am = getSystemService(android.app.ActivityManager::class.java)
            am.appTasks?.firstOrNull()?.taskInfo?.topActivity?.className ?: "unknown"
        } catch (e: Exception) { "error: ${e.message}" }
        GoRoLog.d("GoRoAuth", "[${hashCode()}] AddOnlineStorageActivity: onPause. topActivity=$topActivity")
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        GoRoLog.d("GoRoAuth", "[${hashCode()}] onActivityResult: req=$requestCode res=$resultCode")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        GoRoLog.d("GoRoAuth", "[${hashCode()}] AddOnlineStorageActivity: onNewIntent: $intent")
    }

    private fun startOneDriveAuthFlow() {
        if (isConnected) return
        startActivity(Intent(this, OnedriveDeviceCodeAuthActivity::class.java))
        finish()
    }

    private fun startGoogleDriveAuthFlow() {
        if (isConnected) return
        if (isTv) {
            startActivity(Intent(this, GoogleDriveDeviceCodeAuthActivity::class.java))
            finish()
            return
        }
        startActivity(Intent(this, GoogleDriveAuthActivity::class.java))
        finish()
    }

    private fun startDropboxAuthFlow() {
        if (isConnected) return
        if (isTv) {
            startActivity(Intent(this, DropboxManualCodeAuthActivity::class.java))
            finish()
            return
        }
        startActivity(Intent(this, DropboxAuthActivity::class.java))
        finish()
    }

    private fun startS3AuthFlow(provider: OnlineStorageProvider) {
        if (isConnected) return
        val intent = Intent(this, S3SetupActivity::class.java).apply {
            putExtra(S3SetupActivity.EXTRA_PROVIDER, provider.name)
        }
        startActivity(intent)
        finish()
    }

    private fun startWebDavSetupFlow() {
        if (isConnected) return
        startActivity(Intent(this, WebDavSetupActivity::class.java))
        finish()
    }

    private fun startRCloneFlow() {
        if (isConnected) return
        startActivity(Intent(this, RCloneProviderActivity::class.java))
        finish()
    }

    private fun showGoogleDriveScopeDialog() {
        val dialogView = layoutInflater.inflate(
            if (isTv) R.layout.dialog_gdrive_scope_tv
            else R.layout.dialog_gdrive_scope, null
        )

        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        val messageView = dialogView.findViewById<TextView>(R.id.txtGdriveScopeMessage)
        if (!isTv) {
            messageView.movementMethod = LinkMovementMethod.getInstance()
        }

        dialogView.findViewById<View>(R.id.btnGdriveScopeOk).setOnClickListener {
            dialog.dismiss()
            startGoogleDriveAuthFlow()
        }

        dialogView.findViewById<View>(R.id.btnGdriveScopeCancel).setOnClickListener {
            dialog.dismiss()
        }

        if (isTv) {
            val btnLearnMore = dialogView.findViewById<View>(R.id.btnGdriveScopeLearnMore)
            btnLearnMore.setOnClickListener {
                val url = "https://developers.google.com/drive/api/guides/api-specific-auth#drive.file"
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }

            val focusColor = getColor(R.color.tv_button_focused_yellow)
            val defaultColor = getColor(R.color.tv_button_bg_tint)
            val focusTextColor = getColor(R.color.tv_button_focused_yellow_text)
            val defaultTextColor = getColor(R.color.tv_text_primary)

            listOf(
                dialogView.findViewById<View>(R.id.btnGdriveScopeLearnMore),
                dialogView.findViewById<View>(R.id.btnGdriveScopeCancel),
                dialogView.findViewById<View>(R.id.btnGdriveScopeOk)
            ).filter { it is MaterialButton }.forEach { btn ->
                btn.setOnFocusChangeListener { v, hasFocus ->
                    if (hasFocus) {
                        (v as MaterialButton).setBackgroundColor(focusColor)
                        v.setTextColor(focusTextColor)
                    } else {
                        (v as MaterialButton).setBackgroundColor(defaultColor)
                        v.setTextColor(defaultTextColor)
                    }
                }
            }

        }

        dialog.show()

        if (isTv) {
            val btnOk = dialogView.findViewById<View>(R.id.btnGdriveScopeOk)
            btnOk?.post { btnOk.requestFocus() }
        }
    }
}
