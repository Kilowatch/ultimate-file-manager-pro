package za.kilowatch.ultimatefilemanager.settings

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.ImageView
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

class ExportDetailsActivity : AppCompatActivity() {

    private var isTv = false
    private lateinit var progressBar: ProgressBar
    private lateinit var tvError: TextView
    private lateinit var layoutContent: View
    private lateinit var rvExportPreview: RecyclerView
    private lateinit var btnExportConfirm: MaterialButton

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        isTv = DeviceUtils.isTvDevice(this)

        if (isTv) {
            setContentView(R.layout.activity_export_details_tv)
        } else {
            setContentView(R.layout.activity_export_details)
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

        setupViews()
        loadExportableItems()
    }

    private fun setupViews() {
        progressBar = findViewById(R.id.progressBar)
        tvError = findViewById(R.id.tvError)
        layoutContent = findViewById(R.id.layoutContent)
        rvExportPreview = findViewById(R.id.rvExportPreview)
        btnExportConfirm = findViewById(R.id.btnExportConfirm)

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

        rvExportPreview.layoutManager = LinearLayoutManager(this)

        if (isTv) {
            setupTvButtonFocus(btnExportConfirm)
        }
    }

    private fun loadExportableItems() {
        progressBar.visibility = View.VISIBLE
        tvError.visibility = View.GONE
        layoutContent.visibility = View.GONE

        lifecycleScope.launch(Dispatchers.Main) {
            try {
                val items = withContext(Dispatchers.IO) {
                    SettingsBackupManager.getAvailableBackupItems(this@ExportDetailsActivity)
                }

                if (items.isEmpty()) {
                    progressBar.visibility = View.GONE
                    tvError.text = getString(R.string.backup_no_exportable_items)
                    tvError.visibility = View.VISIBLE
                    return@launch
                }

                val adapter = ExportSelectionAdapter(items)
                rvExportPreview.adapter = adapter
                progressBar.visibility = View.GONE
                layoutContent.visibility = View.VISIBLE
                btnExportConfirm.requestFocus()

                btnExportConfirm.setOnClickListener {
                    performExport(items)
                }
            } catch (e: Exception) {
                progressBar.visibility = View.GONE
                tvError.text = getString(R.string.backup_export_error)
                tvError.visibility = View.VISIBLE
            }
        }
    }

    private fun performExport(items: List<BackupItem>) {
        progressBar.visibility = View.VISIBLE
        layoutContent.visibility = View.GONE

        lifecycleScope.launch(Dispatchers.Main) {
            val targetFile = SettingsBackupManager.getBackupFile()
            val success = withContext(Dispatchers.IO) {
                SettingsBackupManager.performExport(this@ExportDetailsActivity, items, targetFile)
            }
            progressBar.visibility = View.GONE
            if (success) {
                val userPath = getString(R.string.backup_path_internal_storage, targetFile.name)
                val message = getString(R.string.backup_notification_file_saved, userPath)
                Toast.makeText(this@ExportDetailsActivity, message, Toast.LENGTH_LONG).show()
                finish()
            } else {
                layoutContent.visibility = View.VISIBLE
                Toast.makeText(this@ExportDetailsActivity, R.string.backup_export_error, Toast.LENGTH_LONG).show()
            }
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
