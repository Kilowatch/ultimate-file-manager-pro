package za.kilowatch.ultimatefilemanager.network

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import za.kilowatch.ultimatefilemanager.settings.ThemeHelper
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import za.kilowatch.ultimatefilemanager.util.GoRoLog

/**
 * Credential-entry screen for S3-compatible storage (AWS S3, IDrive e2, Backblaze B2, etc.).
 *
 * No OAuth — the user supplies endpoint, region, bucket, access key ID, and secret.
 * On "Connect", we attempt a list-objects call to validate the credentials, then
 * save the [OnlineStorage] to [OnlineStorageRepository].
 *
 * Launched from [AddOnlineStorageActivity] with EXTRA_PROVIDER = "AWS_S3" or "IDRIVE_E2".
 */
class S3SetupActivity : AppCompatActivity() {

    private lateinit var repo: OnlineStorageRepository
    private var provider: OnlineStorageProvider = OnlineStorageProvider.AWS_S3
    private var isTv = false
    private var editingStorageId: String? = null

    companion object {
        const val EXTRA_PROVIDER = "extra_s3_provider"
        const val EXTRA_STORAGE_ID = "extra_storage_id"

        private const val TAG = "S3Setup"

        // Default endpoint and region per provider
        private val DEFAULTS = mapOf(
            OnlineStorageProvider.AWS_S3 to Pair("https://s3.amazonaws.com", "us-east-1"),
            // IDrive e2 requires the user to look up their cluster URL in the portal
            OnlineStorageProvider.IDRIVE_E2 to Pair("", "us-east-1")
        )
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
            if (isTv) R.layout.activity_s3_setup_tv
            else R.layout.activity_s3_setup
        )

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val combinedInsets = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            v.setPadding(combinedInsets.left, combinedInsets.top, combinedInsets.right, combinedInsets.bottom)
            insets
        }

        repo = OnlineStorageRepository.getInstance(this)
        editingStorageId = intent.getStringExtra(EXTRA_STORAGE_ID)
        val existing = editingStorageId?.let { repo.getById(it) }

        provider = if (existing != null) {
            existing.provider
        } else {
            try {
                OnlineStorageProvider.valueOf(
                    intent.getStringExtra(EXTRA_PROVIDER) ?: OnlineStorageProvider.AWS_S3.name
                )
            } catch (_: Exception) {
                OnlineStorageProvider.AWS_S3
            }
        }

        setupViews()
    }

    private fun setupViews() {
        val btnBack      = findViewById<ImageView>(R.id.btnBack)
        val tvTitle      = findViewById<TextView>(R.id.tvSetupTitle)
        val tvSubtitle   = findViewById<TextView>(R.id.tvSetupSubtitle)
        val edtLabel     = findViewById<TextInputEditText>(R.id.edtS3Label)
        val edtEndpoint  = findViewById<TextInputEditText>(R.id.edtS3Endpoint)
        val edtRegion    = findViewById<TextInputEditText>(R.id.edtS3Region)
        val edtBucket    = findViewById<TextInputEditText>(R.id.edtS3Bucket)
        val edtAccessKey = findViewById<TextInputEditText>(R.id.edtS3AccessKey)
        val edtSecretKey = findViewById<TextInputEditText>(R.id.edtS3SecretKey)
        val btnConnect   = findViewById<MaterialButton>(R.id.btnConnect)
        val progressBar  = findViewById<ProgressBar>(R.id.progressBar)
        val tvStatus     = findViewById<TextView>(R.id.tvStatus)

        btnBack.setOnClickListener { finish() }

        // Provider-specific labels
        val providerLabel = when (provider) {
            OnlineStorageProvider.AWS_S3   -> getString(R.string.add_online_storage_aws_s3)
            OnlineStorageProvider.IDRIVE_E2 -> getString(R.string.add_online_storage_idrive_e2)
            else -> "S3"
        }
        tvTitle.text    = getString(R.string.s3_setup_title, providerLabel)
        tvSubtitle.text = getString(R.string.s3_setup_subtitle)

        val existing = editingStorageId?.let { repo.getById(it) }
        if (existing != null) {
            edtLabel.setText(existing.email)
            edtEndpoint.setText(existing.s3Endpoint ?: "")
            edtRegion.setText(existing.s3Region ?: "")
            edtBucket.setText(existing.s3Bucket ?: "")
            edtAccessKey.setText(existing.s3AccessKey ?: "")
            edtSecretKey.setText("")
        } else {
            // Pre-fill endpoint / region defaults
            val (defaultEndpoint, defaultRegion) = DEFAULTS[provider] ?: Pair("", "us-east-1")
            if (defaultEndpoint.isNotEmpty()) edtEndpoint.setText(defaultEndpoint)
            if (defaultRegion.isNotEmpty())   edtRegion.setText(defaultRegion)
        }

        // IDrive e2: show endpoint hint
        if (provider == OnlineStorageProvider.IDRIVE_E2) {
            edtEndpoint.hint = getString(R.string.s3_setup_endpoint_hint_idrive)
        }

        btnConnect.setOnClickListener {
            val label     = edtLabel.text?.toString()?.trim() ?: ""
            val endpoint  = edtEndpoint.text?.toString()?.trim() ?: ""
            val region    = edtRegion.text?.toString()?.trim() ?: ""
            val bucket    = edtBucket.text?.toString()?.trim() ?: ""
            val accessKey = edtAccessKey.text?.toString()?.trim() ?: ""
            val secretKey = edtSecretKey.text?.toString()?.trim() ?: ""

            if (label.isEmpty() || endpoint.isEmpty() || region.isEmpty() ||
                bucket.isEmpty() || accessKey.isEmpty() || secretKey.isEmpty()) {
                tvStatus.text  = getString(R.string.s3_setup_error_empty_fields)
                tvStatus.visibility = View.VISIBLE
                return@setOnClickListener
            }

            if (!endpoint.startsWith("http://") && !endpoint.startsWith("https://")) {
                tvStatus.text  = getString(R.string.s3_setup_error_bad_endpoint)
                tvStatus.visibility = View.VISIBLE
                return@setOnClickListener
            }

            // Build a temporary NetworkShare to test
            val testShare = NetworkShare(
                id         = "test_${System.currentTimeMillis()}",
                name       = label,
                type       = if (provider == OnlineStorageProvider.IDRIVE_E2) ShareType.IDRIVE_E2 else ShareType.AWS_S3,
                host       = endpoint.trimEnd('/'),
                domain     = bucket,
                remotePath = region,
                username   = accessKey,
                password   = secretKey,
                readOnly   = false
            )

            progressBar.visibility = View.VISIBLE
            btnConnect.isEnabled   = false
            tvStatus.visibility    = View.GONE
            tvStatus.text          = ""

            CoroutineScope(Dispatchers.Main).launch {
                try {
                    withContext(Dispatchers.IO) {
                        S3ShareClient.listFiles(testShare, "")
                    }

                    // Success — persist
                    val storage = OnlineStorage(
                        id          = editingStorageId ?: java.util.UUID.randomUUID().toString(),
                        provider    = provider,
                        email       = label,
                        displayName = "$providerLabel: $bucket",
                        s3Endpoint  = endpoint.trimEnd('/'),
                        s3Bucket    = bucket,
                        s3Region    = region,
                        s3AccessKey = accessKey,
                        s3SecretKey = secretKey,
                        isCredentialsStripped = false
                    )
                    repo.save(storage)
                    GoRoLog.d(TAG, "S3 storage connected: $label ($provider)")

                    progressBar.visibility = View.GONE
                    tvStatus.text          = getString(R.string.s3_setup_success)
                    tvStatus.visibility    = View.VISIBLE

                    // Return to the manager after a brief pause
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        finish()
                    }, 1000)

                } catch (e: Exception) {
                    GoRoLog.e(TAG, "S3 connection test failed", e)
                    progressBar.visibility = View.GONE
                    btnConnect.isEnabled   = true
                    tvStatus.text          = getString(R.string.s3_setup_error_connection, e.message ?: "Unknown error")
                    tvStatus.visibility    = View.VISIBLE
                }
            }
        }
    }
}
