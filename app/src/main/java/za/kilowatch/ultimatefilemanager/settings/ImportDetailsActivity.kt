package za.kilowatch.ultimatefilemanager.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import java.io.File

class ImportDetailsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_BACKUP_PATH = "extra_backup_path"
    }

    private var isTv = false
    private var backupPath: String? = null
    private var parsedDetails: BackupDetails? = null

    private lateinit var progressBar: ProgressBar
    private lateinit var tvError: TextView
    private lateinit var layoutContent: View
    private var rvImportPreview: RecyclerView? = null        // mobile only
    private var tvImportContainer: LinearLayout? = null      // TV only
    private lateinit var btnImportConfirm: MaterialButton

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        isTv = DeviceUtils.isTvDevice(this)

        if (isTv) {
            setContentView(R.layout.activity_import_details_tv)
        } else {
            setContentView(R.layout.activity_import_details)
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val tvPad = if (isTv) (27 * resources.displayMetrics.density).toInt() else 0
            v.setPadding(
                systemBars.left + tvPad, systemBars.top + tvPad,
                systemBars.right + tvPad, systemBars.bottom + tvPad
            )
            insets
        }

        backupPath = intent.getStringExtra(EXTRA_BACKUP_PATH)
        if (backupPath.isNullOrEmpty()) {
            finish()
            return
        }

        setupViews()
        loadBackupFile()
    }

    private fun setupViews() {
        progressBar = findViewById(R.id.progressBar)
        tvError = findViewById(R.id.tvError)
        layoutContent = findViewById(R.id.layoutContent)
        btnImportConfirm = findViewById(R.id.btnImportConfirm)

        if (isTv) {
            // TV: items are inflated into a LinearLayout inside NestedScrollView (no RecyclerView)
            tvImportContainer = findViewById(R.id.importPreviewContainer)
        } else {
            rvImportPreview = findViewById(R.id.rvImportPreview)
            rvImportPreview!!.layoutManager = LinearLayoutManager(this)
        }

        val btnBack = findViewById<ImageView?>(R.id.btnBack)
        if (isTv) {
            val whiteCsl = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_text_primary))
            val yellowCsl = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_button_focused_yellow_text))
            btnBack?.imageTintList = whiteCsl
            btnBack?.setOnFocusChangeListener { _, hasFocus ->
                btnBack.imageTintList = if (hasFocus) yellowCsl else whiteCsl
                if (hasFocus) {
                    btnBack.setBackgroundResource(R.drawable.bg_icon_circle_focused)
                } else {
                    btnBack.setBackgroundResource(R.drawable.bg_icon_circle_accent)
                }
            }
        }
        btnBack?.setOnClickListener { finish() }

        btnImportConfirm.setOnClickListener {
            val details = parsedDetails ?: return@setOnClickListener
            performRestore(details)
        }

        if (isTv) {
            setupTvButtonFocus(btnImportConfirm)
        }
    }

    private fun loadBackupFile() {
        progressBar.visibility = View.VISIBLE
        tvError.visibility = View.GONE
        layoutContent.visibility = View.GONE

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val file = File(backupPath!!)
                if (!file.exists()) {
                    showError(getString(R.string.backup_import_error))
                    return@launch
                }

                val bytes = file.readBytes()
                val plainText = try {
                    SettingsBackupManager.decryptBackup(bytes)
                } catch (e: javax.crypto.AEADBadTagException) {
                    showError(getString(R.string.backup_import_decrypt_error))
                    return@launch
                } catch (e: Exception) {
                    showError(getString(R.string.backup_import_decrypt_error))
                    return@launch
                }

                val details = try {
                    SettingsBackupManager.parseBackupContent(this@ImportDetailsActivity, plainText)
                } catch (e: Exception) {
                    showError(getString(R.string.backup_import_invalid_error))
                    return@launch
                }

                val isEmpty = details.sharedPrefs.isEmpty() &&
                        details.shares.isEmpty() &&
                        details.storages.isEmpty() &&
                        details.ftpProfiles.isEmpty() &&
                        details.renames.isEmpty() &&
                        details.smartSortConfigs.isEmpty()

                if (isEmpty) {
                    showError(getString(R.string.backup_import_details_empty))
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    parsedDetails = details
                    if (isTv) {
                        populateTvPreview(details)
                    } else {
                        val adapter = ImportDetailsAdapter(details)
                        rvImportPreview!!.adapter = adapter
                    }
                    progressBar.visibility = View.GONE
                    layoutContent.visibility = View.VISIBLE
                    btnImportConfirm.requestFocus()
                }
            } catch (e: Exception) {
                showError(getString(R.string.backup_import_error))
            }
        }
    }

    private suspend fun showError(message: String) {
        withContext(Dispatchers.Main) {
            progressBar.visibility = View.GONE
            tvError.text = message
            tvError.visibility = View.VISIBLE
        }
    }

    private fun performRestore(details: BackupDetails) {
        progressBar.visibility = View.VISIBLE
        layoutContent.visibility = View.GONE

        lifecycleScope.launch(Dispatchers.IO) {
            val success = SettingsBackupManager.performRestore(this@ImportDetailsActivity, details)
            withContext(Dispatchers.Main) {
                progressBar.visibility = View.GONE
                if (success) {
                    Toast.makeText(this@ImportDetailsActivity, R.string.backup_import_success, Toast.LENGTH_SHORT).show()
                    
                    // Self-restart application to reload preferences and db singletons cleanly
                    val pm = packageManager
                    val launchIntent = pm.getLaunchIntentForPackage(packageName)
                    val mainIntent = Intent.makeRestartActivityTask(launchIntent?.component)
                    startActivity(mainIntent)
                    Runtime.getRuntime().exit(0)
                } else {
                    layoutContent.visibility = View.VISIBLE
                    Toast.makeText(this@ImportDetailsActivity, R.string.backup_import_error, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * TV only: inflates each category group card directly into the NestedScrollView's
     * LinearLayout container so no individual item can receive D-pad focus.
     * Scrolling is handled by the focusable FrameLayout wrapper (same as the T&C screen).
     */
    private fun populateTvPreview(details: BackupDetails) {
        val container = tvImportContainer ?: return
        container.removeAllViews()
        val adapter = ImportDetailsAdapter(details)
        for (position in 0 until adapter.itemCount) {
            val vh = adapter.onCreateViewHolder(container, adapter.getItemViewType(position))
            adapter.onBindViewHolder(vh, position)
            // Remove any focus from the inflated card — this screen is read-only
            vh.itemView.isFocusable = false
            vh.itemView.isClickable = false
            (vh.itemView as? android.view.ViewGroup)?.descendantFocusability =
                android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
            container.addView(vh.itemView)
        }
    }

    private fun setupTvButtonFocus(btn: MaterialButton) {
        val yellowFill = getColor(R.color.tv_button_focused_yellow)
        val blackText  = getColor(R.color.tv_button_focused_yellow_text)
        val defaultBg  = getColor(R.color.btn_save_bg_tint)
        val defaultText = getColor(android.R.color.white)

        btn.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                btn.backgroundTintList = android.content.res.ColorStateList.valueOf(yellowFill)
                btn.setTextColor(blackText)
            } else {
                btn.backgroundTintList = android.content.res.ColorStateList.valueOf(defaultBg)
                btn.setTextColor(defaultText)
            }
        }
    }
}
