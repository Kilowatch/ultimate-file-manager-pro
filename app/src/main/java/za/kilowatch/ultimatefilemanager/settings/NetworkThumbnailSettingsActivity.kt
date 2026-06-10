package za.kilowatch.ultimatefilemanager.settings

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.RadioButton
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import za.kilowatch.ultimatefilemanager.util.GoRoLog
import java.io.File
import java.util.Locale

class NetworkThumbnailSettingsActivity : AppCompatActivity() {

    private var isTv = false
    private lateinit var cacheManager: NetworkThumbnailCacheManager

    // Persisted through recreate() to prevent looping when restartPending is still true.
    private var handledFontChange = false
    private var handledLocaleChange = false
    
    /** Receives the selected folder from [za.kilowatch.ultimatefilemanager.storage.StorageBrowserActivity]. */
    private lateinit var folderPickerLauncher: androidx.activity.result.ActivityResultLauncher<Intent>

    private lateinit var switchEnable: SwitchMaterial
    private lateinit var txtCacheFolder: TextView
    private lateinit var txtCacheLimit: TextView
    private lateinit var txtStats: TextView

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        handledFontChange = savedInstanceState?.getBoolean("font_handled", false) ?: false
        handledLocaleChange = savedInstanceState?.getBoolean("locale_handled", false) ?: false
        enableEdgeToEdge()

        cacheManager = NetworkThumbnailCacheManager(this)
        isTv = DeviceUtils.isTvDevice(this)

        setContentView(if (isTv) R.layout.activity_network_thumbnails_tv else R.layout.activity_network_thumbnails)

        findViewById<View>(R.id.main)?.let { mainView ->
            ViewCompat.setOnApplyWindowInsetsListener(mainView) { v, insets ->
                val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                val tvPad = if (isTv) (27 * resources.displayMetrics.density).toInt() else 0
                v.setPadding(
                    systemBars.left + tvPad, systemBars.top + tvPad,
                    systemBars.right + tvPad, systemBars.bottom + tvPad
                )
                insets
            }
        }
        
        // Register folder picker result launcher
        folderPickerLauncher = registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK) {
                val data = result.data ?: return@registerForActivityResult
                val path = data.getStringExtra(za.kilowatch.ultimatefilemanager.storage.StorageBrowserActivity.RESULT_SELECTED_LOCAL_PATH)
                if (path != null) {
                    val file = File(path)
                    if (!file.exists()) file.mkdirs()
                    
                    NetworkThumbnailPreferenceManager.setCachePath(this, path)
                    updateUI()
                }
            }
        }

        setupViews()
        updateUI()
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
        // Refresh UI when returning from child activities (e.g., TV custom limit Activity)
        updateUI()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("font_handled", handledFontChange)
        outState.putBoolean("locale_handled", handledLocaleChange)
    }

    private fun setupViews() {
        val btnBack = findViewById<View>(R.id.btnBack)
        btnBack.setOnClickListener { finish() }

        if (isTv && btnBack is ImageView) {
            val whiteCsl = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_text_primary))
            val blackCsl = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_button_focused_yellow_text))
            btnBack.imageTintList = whiteCsl
            btnBack.setOnFocusChangeListener { _, hasFocus ->
                btnBack.imageTintList = if (hasFocus) blackCsl else whiteCsl
            }
        }

        switchEnable = findViewById(R.id.switchEnable)
        // Persist changes when user toggles the Switch directly
        switchEnable.setOnCheckedChangeListener { _, isChecked ->
            NetworkThumbnailPreferenceManager.setEnabled(this, isChecked)
        }
        txtCacheFolder = findViewById(R.id.txtCacheFolder)
        txtCacheLimit = findViewById(R.id.txtCacheLimit)
        txtStats = findViewById(R.id.txtStats)

        val cardEnable = findViewById<MaterialCardView>(R.id.cardEnable)
        cardEnable.setOnClickListener {
            val newValue = !switchEnable.isChecked
            NetworkThumbnailPreferenceManager.setEnabled(this, newValue)
            switchEnable.isChecked = newValue
        }

        val cardCacheFolder = findViewById<MaterialCardView>(R.id.cardCacheFolder)
        cardCacheFolder.setOnClickListener {
            showStoragePicker()
        }

        val cardCacheLimit = findViewById<MaterialCardView>(R.id.cardCacheLimit)
        cardCacheLimit.setOnClickListener {
            showLimitDialog()
        }

        val btnClearCache = findViewById<MaterialCardView>(R.id.btnClearCache)
        btnClearCache.setOnClickListener {
            showClearConfirmDialog()
        }
    }

    fun updateUI() {
        switchEnable.isChecked = NetworkThumbnailPreferenceManager.isEnabled(this)
        
        val currentPath = NetworkThumbnailPreferenceManager.getCachePath(this)
        txtCacheFolder.text = getFriendlyPathName(currentPath)

        val limitMb = NetworkThumbnailPreferenceManager.getCacheLimitMb(this)
        txtCacheLimit.text = when (limitMb) {
            500 -> getString(R.string.nt_limit_500mb)
            1024 -> getString(R.string.nt_limit_1gb)
            2048 -> getString(R.string.nt_limit_2gb)
            5120 -> getString(R.string.nt_limit_5gb)
            else -> {
                if (limitMb >= 1024) {
                    val gb = limitMb.toDouble() / 1024.0
                    String.format(Locale.getDefault(), "%.1f GB", gb)
                } else {
                    "$limitMb MB"
                }
            }
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val currentSizeBytes = cacheManager.getCurrentCacheSize()
            val currentSizeMb = currentSizeBytes / (1024 * 1024)
            withContext(Dispatchers.Main) {
                txtStats.text = getString(R.string.nt_stats_size, "$currentSizeMb MB", limitMb)
            }
        }
    }

    private fun getFriendlyPathName(path: String): String {
        if (path.isEmpty()) return getString(R.string.nt_folder_not_selected)
        
        val internalCache = externalCacheDir?.absolutePath ?: ""
        if (path.startsWith(internalCache)) {
            return "Internal Storage"
        }
        
        val externalDirs = ContextCompat.getExternalFilesDirs(this, null)
        for (i in 1 until externalDirs.size) {
            val dir = externalDirs[i]
            if (dir != null && path.startsWith(dir.absolutePath.substringBefore("/Android"))) {
                return "SD Card $i"
            }
        }
        
        // If it's a subfolder, show the last part of the path or the full path
        val file = File(path)
        return if (file.exists()) {
            file.name.ifEmpty { path }
        } else {
            "Custom Location"
        }
    }

    private fun showStoragePicker() {
        folderPickerLauncher.launch(
            Intent(this, za.kilowatch.ultimatefilemanager.storage.StorageBrowserActivity::class.java).apply {
                putExtra(za.kilowatch.ultimatefilemanager.storage.StorageBrowserActivity.EXTRA_NETWORK_CACHE_PICKER, true)
            }
        )
    }

    private fun showLimitDialog() {
        val options = arrayOf(
            getString(R.string.nt_limit_500mb),
            getString(R.string.nt_limit_1gb),
            getString(R.string.nt_limit_2gb),
            getString(R.string.nt_limit_5gb)
        )
        val values = arrayOf(500, 1024, 2048, 5120)

        // Add a Custom... option at the end
        val optionsWithCustom = options + arrayOf(getString(R.string.nt_limit_custom))
        val valuesList = values.toMutableList()

        AlertDialog.Builder(this)
            .setTitle(R.string.nt_cache_limit_title)
            .setItems(optionsWithCustom) { _, which ->
                if (which < values.size) {
                    NetworkThumbnailPreferenceManager.setCacheLimitMb(this, values[which])
                    updateUI()
                } else {
                    // On TV open full-screen Activity; on Mobile show dialog
                    if (isTv) {
                        startActivity(android.content.Intent(this, NetworkThumbnailCustomLimitActivity::class.java))
                    } else {
                        val frag = CacheLimitDialogFragment()
                        frag.show(supportFragmentManager, CacheLimitDialogFragment.TAG)
                    }
                }
            }
            .show()
    }

    private fun showCustomLimitDialog() {
        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.dialog_cache_limit_custom, null)

        val editValue = view.findViewById<android.widget.EditText>(R.id.editCacheValue)
        val chip500 = view.findViewById<com.google.android.material.chip.Chip>(R.id.chipPreset500)
        val chip1gb = view.findViewById<com.google.android.material.chip.Chip>(R.id.chipPreset1gb)
        val chip2gb = view.findViewById<com.google.android.material.chip.Chip>(R.id.chipPreset2gb)
        val chip5gb = view.findViewById<com.google.android.material.chip.Chip>(R.id.chipPreset5gb)
        val chipMb = view.findViewById<com.google.android.material.chip.Chip>(R.id.chipMb)
        val chipGb = view.findViewById<com.google.android.material.chip.Chip>(R.id.chipGb)
        val btnSave = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSave)
        val btnCancel = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCancel)

        val currentMb = NetworkThumbnailPreferenceManager.getCacheLimitMb(this)
        if (currentMb >= 1024 && currentMb % 1024 == 0) {
            val gbVal = currentMb.toDouble() / 1024.0
            editValue.setText(String.format(java.util.Locale.getDefault(), "%.1f", gbVal))
            chipGb.isChecked = true
        } else if (currentMb >= 1024) {
            val gbVal = currentMb.toDouble() / 1024.0
            editValue.setText(String.format(java.util.Locale.getDefault(), "%.1f", gbVal))
            chipGb.isChecked = true
        } else {
            editValue.setText(currentMb.toString())
            chipMb.isChecked = true
        }

        fun setPresetValue(mb: Int) {
            if (mb >= 1024) {
                val gb = mb.toDouble() / 1024.0
                editValue.setText(String.format(java.util.Locale.getDefault(), "%.1f", gb))
                chipGb.isChecked = true
            } else {
                editValue.setText(mb.toString())
                chipMb.isChecked = true
            }
        }

        chip500.setOnClickListener { setPresetValue(500) }
        chip1gb.setOnClickListener { setPresetValue(1024) }
        chip2gb.setOnClickListener { setPresetValue(2048) }
        chip5gb.setOnClickListener { setPresetValue(5120) }

        chipMb.setOnClickListener { /* unit switched to MB */ }
        chipGb.setOnClickListener { /* unit switched to GB */ }

        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle(R.string.nt_cache_limit_title)
            .setView(view)
            .create()

        btnSave.setOnClickListener {
            val raw = editValue.text.toString().trim()
            if (raw.isEmpty()) {
                editValue.error = getString(R.string.nt_custom_value_hint)
                return@setOnClickListener
            }
            val isGb = chipGb.isChecked
            val valueDouble = try {
                raw.toDouble()
            } catch (e: Exception) {
                editValue.error = getString(R.string.nt_custom_value_hint)
                return@setOnClickListener
            }
            val resultMb = if (isGb) (valueDouble * 1024.0).toInt() else valueDouble.toInt()
            NetworkThumbnailPreferenceManager.setCacheLimitMb(this, resultMb)
            updateUI()
            dialog.dismiss()
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showClearConfirmDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.nt_clear_cache_confirm_title)
            .setMessage(R.string.nt_clear_cache_confirm_msg)
            .setPositiveButton(R.string.nt_clear_cache_title) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    cacheManager.clearAllCache()
                    withContext(Dispatchers.Main) {
                        updateUI()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun setupTvCardFocus(card: MaterialCardView) {
        val yellowFill  = getColor(R.color.tv_button_focused_yellow)
        val blackText   = getColor(R.color.tv_button_focused_yellow_text)
        val glassColor  = android.graphics.Color.TRANSPARENT
        val primaryText = getColor(R.color.tv_text_primary)
        val secondText  = getColor(R.color.tv_text_secondary)
        val errorRed    = getColor(R.color.tv_error_red)

        card.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                card.setCardBackgroundColor(yellowFill)
                setChildTextColors(card, blackText)
            } else {
                card.setCardBackgroundColor(glassColor)
                setChildTextColorsTwo(card, primaryText, secondText, errorRed, card.id == R.id.btnClearCache)
            }
        }
    }

    private fun setChildTextColors(view: View, color: Int) {
        if (view is TextView) { view.setTextColor(color) }
        else if (view is ImageView && view !is SwitchMaterial) {
            view.imageTintList = android.content.res.ColorStateList.valueOf(color)
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) setChildTextColors(view.getChildAt(i), color)
        }
    }

    private fun setChildTextColorsTwo(view: View, primary: Int, secondary: Int, error: Int, isErrorCard: Boolean) {
        if (view is TextView) {
            if (isErrorCard && view.text == getString(R.string.nt_clear_cache_title)) {
                view.setTextColor(error)
            } else {
                view.setTextColor(if (view.textSize > resources.displayMetrics.density * 18) primary else secondary)
            }
        } else if (view is ImageView && view !is SwitchMaterial) {
            view.imageTintList = android.content.res.ColorStateList.valueOf(if (isErrorCard && view.id != R.id.switchEnable) error else primary)
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) setChildTextColorsTwo(view.getChildAt(i), primary, secondary, error, isErrorCard)
        }
    }
}
