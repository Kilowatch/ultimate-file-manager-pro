package za.kilowatch.ultimatefilemanager.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.storage.FileBrowserActivity
import za.kilowatch.ultimatefilemanager.storage.StorageBrowserActivity
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import za.kilowatch.ultimatefilemanager.util.ThemeColors

class BackupRestoreActivity : AppCompatActivity() {

    private var isTv = false
    private var handledFontChange = false
    private var handledLocaleChange = false

    private val configPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val selectedPath = result.data?.getStringExtra(FileBrowserActivity.RESULT_SELECTED_PATH)
            if (!selectedPath.isNullOrEmpty()) {
                val intent = Intent(this, ImportDetailsActivity::class.java).apply {
                    putExtra(ImportDetailsActivity.EXTRA_BACKUP_PATH, selectedPath)
                }
                startActivity(intent)
            }
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        isTv = DeviceUtils.isTvDevice(this)

        if (savedInstanceState != null) {
            handledFontChange = savedInstanceState.getBoolean("font_handled", false)
            handledLocaleChange = savedInstanceState.getBoolean("locale_handled", false)
        }

        if (isTv) {
            setContentView(R.layout.activity_backup_restore_tv)
        } else {
            setContentView(R.layout.activity_backup_restore)
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
    }

    override fun onResume() {
        super.onResume()
        if (LocaleHelper.restartPending && !handledLocaleChange) {
            handledLocaleChange = true
            recreate()
            return
        }
        if (FontSizeHelper.restartPending && !handledFontChange) {
            handledFontChange = true
            recreate()
            return
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("font_handled", handledFontChange)
        outState.putBoolean("locale_handled", handledLocaleChange)
    }

    private fun setupViews() {
        val btnBack = findViewById<ImageView?>(R.id.btnBack)
        if (isTv) {
            val whiteCsl = ColorStateList.valueOf(getColor(R.color.tv_text_primary))
            val yellowCsl = ColorStateList.valueOf(getColor(R.color.tv_button_focused_yellow_text))
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

        val txtActionsHeader = findViewById<TextView?>(R.id.txtActionsHeader)
        txtActionsHeader?.setTextColor(ThemeColors.primary(this))

        val exportView = findViewById<View?>(R.id.rowExport) ?: findViewById<View?>(R.id.cardExport)
        val importView = findViewById<View?>(R.id.rowImport) ?: findViewById<View?>(R.id.cardImport)

        exportView?.setOnClickListener {
            val intent = Intent(this, ExportDetailsActivity::class.java)
            startActivity(intent)
        }

        importView?.setOnClickListener {
            showImportGuideDialog()
        }

        if (isTv) {
            (exportView as? MaterialCardView)?.let { setupTvCardFocus(it) }
            (importView as? MaterialCardView)?.let { setupTvCardFocus(it) }
        }
    }

    private fun showImportGuideDialog() {
        val dialogView = LayoutInflater.from(this).inflate(
            if (isTv) R.layout.dialog_backup_import_guide_tv
            else R.layout.dialog_backup_import_guide,
            null
        )

        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val btnBrowse = dialogView.findViewById<View>(R.id.btnBrowse)
        val btnCancel = dialogView.findViewById<View>(R.id.btnCancel)

        if (!isTv) {
            val primary = ThemeColors.primary(this)
            val onPrimary = ThemeColors.onPrimary(this)
            (btnBrowse as? MaterialButton)?.backgroundTintList = ColorStateList.valueOf(primary)
            (btnBrowse as? MaterialButton)?.setTextColor(onPrimary)
            (btnBrowse as? MaterialButton)?.iconTint = ColorStateList.valueOf(onPrimary)
        }

        btnBrowse.setOnClickListener {
            dialog.dismiss()
            launchFilePicker()
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()

        if (isTv) {
            btnBrowse.requestFocus()
        }
    }

    private fun launchFilePicker() {
        val intent = Intent(this, StorageBrowserActivity::class.java).apply {
            putExtra(FileBrowserActivity.EXTRA_PICKER_MODE, true)
            putExtra(FileBrowserActivity.EXTRA_PICKER_EXTENSIONS, "ufmconfig")
        }
        configPickerLauncher.launch(intent)
    }

    private fun setupTvCardFocus(card: MaterialCardView) {
        val defStroke = getColor(R.color.tv_glass_border)
        val focusedStroke = getColor(R.color.tv_focus_border_strong)

        card.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                card.strokeColor = focusedStroke
                card.strokeWidth = (2 * resources.displayMetrics.density).toInt()
                card.cardElevation = 8f
            } else {
                card.strokeColor = defStroke
                card.strokeWidth = (1 * resources.displayMetrics.density).toInt()
                card.cardElevation = 2f
            }
        }
    }
}
