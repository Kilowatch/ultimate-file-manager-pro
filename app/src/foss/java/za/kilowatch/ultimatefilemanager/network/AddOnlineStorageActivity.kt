package za.kilowatch.ultimatefilemanager.network

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import za.kilowatch.ultimatefilemanager.settings.ThemeHelper
import za.kilowatch.ultimatefilemanager.util.DeviceUtils

/**
 * FOSS build override for AddOnlineStorageActivity.
 *
 * Only AWS S3, iDrive E2, and WebDAV are available in the FOSS build.
 * Google Drive, Dropbox, and OneDrive are completely absent from this screen —
 * their chips are hidden and their auth flows are never invoked.
 *
 * This is a source-set override: Gradle uses this file instead of the main/
 * version when building any foss variant.
 */
class AddOnlineStorageActivity : AppCompatActivity() {

    private lateinit var repo: OnlineStorageRepository
    private var isTv = false
    private var isConnected = false

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

        // ── FOSS: hide all proprietary cloud provider chips ───────────────────
        // Google Drive, Dropbox, and OneDrive require OAuth credentials that are
        // not available in the FOSS build. Hide them entirely — not greyed out,
        // not clickable, simply not there.
        hideProprietaryProviders()

        // ── Back button ───────────────────────────────────────────────────────
        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }

        // ── Toggle groups — only group 2 (WebDAV/Dropbox) and group 3 (S3) matter ──
        // Group 1 (Google Drive / OneDrive) is fully hidden.
        val toggleGroup2 = findViewById<MaterialButtonToggleGroup>(R.id.toggleGroupProvider2)
        val toggleGroup3 = findViewById<MaterialButtonToggleGroup>(R.id.toggleGroupProvider3)
        val btnAuthenticate = findViewById<MaterialButton>(R.id.btnAuthenticate)

        toggleGroup2.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                toggleGroup3.clearChecked()
                when (checkedId) {
                    R.id.chipDropbox -> { /* chip hidden — should never fire */ }
                    R.id.chipWebDav  -> btnAuthenticate.setText(R.string.add_online_storage_auth_webdav)
                }
            }
        }

        toggleGroup3.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                toggleGroup2.clearChecked()
                when (checkedId) {
                    R.id.chipAwsS3    -> btnAuthenticate.setText(R.string.add_online_storage_auth_aws_s3)
                    R.id.chipIDriveE2 -> btnAuthenticate.setText(R.string.add_online_storage_auth_idrive_e2)
                }
            }
        }

        // ── FOSS: check and focus WebDAV by default ───────────────────────────
        toggleGroup2.check(R.id.chipWebDav)
        val chipWebDav = findViewById<MaterialButton>(R.id.chipWebDav)
        chipWebDav.post {
            chipWebDav.requestFocus()
        }

        // ── TV: adjust D-pad focus routes ─────────────────────────────────────
        if (isTv) {
            findViewById<View?>(R.id.btnBack)?.apply {
                nextFocusRightId = R.id.chipWebDav
                nextFocusDownId = R.id.chipWebDav
            }
            chipWebDav?.apply {
                nextFocusUpId = R.id.btnBack
            }
            findViewById<View?>(R.id.chipAwsS3)?.apply {
                nextFocusUpId = R.id.chipWebDav
            }
            findViewById<View?>(R.id.chipIDriveE2)?.apply {
                nextFocusUpId = R.id.chipWebDav
            }
        }

        btnAuthenticate.setOnClickListener {
            val id2 = toggleGroup2.checkedButtonId
            val id3 = toggleGroup3.checkedButtonId
            val checkedId = when {
                id2 != View.NO_ID -> id2
                id3 != View.NO_ID -> id3
                else              -> View.NO_ID
            }
            when (checkedId) {
                R.id.chipWebDav   -> startWebDavSetupFlow()
                R.id.chipAwsS3    -> startS3AuthFlow(OnlineStorageProvider.AWS_S3)
                R.id.chipIDriveE2 -> startS3AuthFlow(OnlineStorageProvider.IDRIVE_E2)
            }
        }
    }

    /** Hides all chips and UI elements that require proprietary OAuth credentials. */
    private fun hideProprietaryProviders() {
        // Hide the entire first toggle group row (Google Drive + OneDrive)
        findViewById<View?>(R.id.toggleGroupProvider1)?.visibility = View.GONE
        // Hide any OneDrive note text
        findViewById<View?>(R.id.tvOneDriveNote)?.visibility = View.GONE
        // Hide Dropbox chip from second row (WebDAV stays visible)
        findViewById<View?>(R.id.chipDropbox)?.visibility = View.GONE
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
}
