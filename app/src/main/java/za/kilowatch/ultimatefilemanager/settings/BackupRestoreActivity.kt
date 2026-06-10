package za.kilowatch.ultimatefilemanager.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.storage.FileBrowserActivity
import za.kilowatch.ultimatefilemanager.storage.StorageBrowserActivity
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import java.io.File

class BackupRestoreActivity : AppCompatActivity() {

    private var isTv = false

    private val configPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val path = result.data?.getStringExtra(FileBrowserActivity.RESULT_SELECTED_PATH)
            if (path != null) {
                val intent = Intent(this, ImportDetailsActivity::class.java).apply {
                    putExtra(ImportDetailsActivity.EXTRA_BACKUP_PATH, path)
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

    private fun setupViews() {
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

        val cardExport = findViewById<MaterialCardView>(R.id.cardExport)
        val cardImport = findViewById<MaterialCardView>(R.id.cardImport)

        cardExport.setOnClickListener {
            val intent = Intent(this, ExportDetailsActivity::class.java)
            startActivity(intent)
        }

        cardImport.setOnClickListener {
            launchFilePicker()
        }

        if (isTv) {
            setupTvCardFocus(cardExport)
            setupTvCardFocus(cardImport)
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
        val yellowFill  = getColor(R.color.tv_button_focused_yellow)
        val blackText   = getColor(R.color.tv_button_focused_yellow_text)
        val glassColor  = getColor(R.color.tv_glass_white_10)
        val primaryText = getColor(R.color.tv_text_primary)
        val secondText  = getColor(R.color.tv_text_secondary)

        card.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                card.setCardBackgroundColor(yellowFill)
                setChildTextColors(card, blackText)
            } else {
                card.setCardBackgroundColor(glassColor)
                setChildTextColorsTwo(card, primaryText, secondText)
            }
        }
    }

    private fun setChildTextColors(view: View, color: Int) {
        if (view is TextView) {
            view.setTextColor(color)
            return
        }
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                setChildTextColors(view.getChildAt(i), color)
            }
        }
    }

    private fun setChildTextColorsTwo(view: View, primary: Int, secondary: Int) {
        if (view is TextView) {
            view.setTextColor(if (view.textSize > resources.displayMetrics.density * 16) primary else secondary)
            return
        }
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                setChildTextColorsTwo(view.getChildAt(i), primary, secondary)
            }
        }
    }
}
