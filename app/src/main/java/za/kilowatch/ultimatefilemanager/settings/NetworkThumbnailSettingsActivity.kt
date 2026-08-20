package za.kilowatch.ultimatefilemanager.settings

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import java.io.File
import java.util.Locale

/**
 * Network Thumbnail Settings activity.
 * Configures persistent caching, cache folder location, cache size limits, and cache clearing.
 * Follows the Language & Grouped Glass Card design standard.
 */
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
            val whiteCsl = ColorStateList.valueOf(getColor(R.color.tv_text_primary))
            val blackCsl = ColorStateList.valueOf(getColor(R.color.tv_button_focused_yellow_text))
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

        val cardEnable = findViewById<View>(R.id.cardEnable)
        cardEnable.setOnClickListener {
            val newValue = !switchEnable.isChecked
            NetworkThumbnailPreferenceManager.setEnabled(this, newValue)
            switchEnable.isChecked = newValue
        }

        val cardCacheFolder = findViewById<View>(R.id.cardCacheFolder)
        cardCacheFolder.setOnClickListener {
            showStoragePickerGuideDialog()
        }

        val cardCacheLimit = findViewById<View>(R.id.cardCacheLimit)
        cardCacheLimit.setOnClickListener {
            showLimitDialog()
        }

        val btnClearCache = findViewById<View>(R.id.btnClearCache)
        btnClearCache.setOnClickListener {
            showClearConfirmDialog()
        }

        if (isTv) {
            if (cardEnable is MaterialCardView) setupTvCardFocus(cardEnable)
            if (cardCacheFolder is MaterialCardView) setupTvCardFocus(cardCacheFolder)
            if (cardCacheLimit is MaterialCardView) setupTvCardFocus(cardCacheLimit)
            if (btnClearCache is MaterialCardView) setupTvCardFocus(btnClearCache)
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
            return "Internal Storage (Default)"
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

    private fun showStoragePickerGuideDialog() {
        val layoutRes = if (isTv) R.layout.dialog_network_cache_folder_guide_tv else R.layout.dialog_network_cache_folder_guide
        val view = LayoutInflater.from(this).inflate(layoutRes, null)

        val currentPath = NetworkThumbnailPreferenceManager.getCachePath(this)
        val txtCurrentPath = view.findViewById<TextView>(R.id.txtCurrentPath)
        txtCurrentPath.text = currentPath.ifEmpty { getString(R.string.nt_folder_not_selected) }

        val btnBrowse = view.findViewById<View>(R.id.btnBrowse)
        val btnResetDefault = view.findViewById<View>(R.id.btnResetDefault)
        val btnCancel = view.findViewById<View>(R.id.btnCancel)

        val defaultPath = File(externalCacheDir, "network_thumbnails").absolutePath
        val isCustom = currentPath.isNotEmpty() && currentPath != defaultPath
        btnResetDefault.visibility = if (isCustom) View.VISIBLE else View.GONE

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnBrowse.setOnClickListener {
            dialog.dismiss()
            showStoragePicker()
        }

        btnResetDefault.setOnClickListener {
            NetworkThumbnailPreferenceManager.setCachePath(this, "")
            updateUI()
            dialog.dismiss()
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showStoragePicker() {
        folderPickerLauncher.launch(
            Intent(this, za.kilowatch.ultimatefilemanager.storage.StorageBrowserActivity::class.java).apply {
                putExtra(za.kilowatch.ultimatefilemanager.storage.StorageBrowserActivity.EXTRA_NETWORK_CACHE_PICKER, true)
            }
        )
    }

    private fun showLimitDialog() {
        val layoutRes = if (isTv) R.layout.dialog_network_cache_limit_chooser_tv else R.layout.dialog_network_cache_limit_chooser
        val view = LayoutInflater.from(this).inflate(layoutRes, null)

        val currentMb = NetworkThumbnailPreferenceManager.getCacheLimitMb(this)

        val opt500Mb = view.findViewById<View>(R.id.opt500Mb)
        val opt1Gb = view.findViewById<View>(R.id.opt1Gb)
        val opt2Gb = view.findViewById<View>(R.id.opt2Gb)
        val opt5Gb = view.findViewById<View>(R.id.opt5Gb)
        val optCustom = view.findViewById<View>(R.id.optCustom)

        val rb500Mb = view.findViewById<android.widget.RadioButton>(R.id.rb500Mb)
        val rb1Gb = view.findViewById<android.widget.RadioButton>(R.id.rb1Gb)
        val rb2Gb = view.findViewById<android.widget.RadioButton>(R.id.rb2Gb)
        val rb5Gb = view.findViewById<android.widget.RadioButton>(R.id.rb5Gb)
        val rbCustom = view.findViewById<android.widget.RadioButton>(R.id.rbCustom)

        val isCustom = (currentMb !in listOf(500, 1024, 2048, 5120))

        rb500Mb.isChecked = (currentMb == 500)
        rb1Gb.isChecked = (currentMb == 1024)
        rb2Gb.isChecked = (currentMb == 2048)
        rb5Gb.isChecked = (currentMb == 5120)
        rbCustom.isChecked = isCustom

        val txtCustomSubtitle = view.findViewById<TextView?>(R.id.txtCustomSubtitle)
        if (isCustom && txtCustomSubtitle != null) {
            val formatted = if (currentMb >= 1024) {
                val gb = currentMb.toDouble() / 1024.0
                String.format(Locale.getDefault(), "%.1f GB", gb)
            } else {
                "$currentMb MB"
            }
            txtCustomSubtitle.text = "$formatted custom limit"
        }

        val btnCancel = view.findViewById<View>(R.id.btnCancel)

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        fun selectPreset(valueMb: Int) {
            NetworkThumbnailPreferenceManager.setCacheLimitMb(this, valueMb)
            updateUI()
            dialog.dismiss()
        }

        opt500Mb.setOnClickListener { selectPreset(500) }
        opt1Gb.setOnClickListener { selectPreset(1024) }
        opt2Gb.setOnClickListener { selectPreset(2048) }
        opt5Gb.setOnClickListener { selectPreset(5120) }

        optCustom.setOnClickListener {
            dialog.dismiss()
            if (isTv) {
                startActivity(Intent(this, NetworkThumbnailCustomLimitActivity::class.java))
            } else {
                val frag = CacheLimitDialogFragment()
                frag.show(supportFragmentManager, CacheLimitDialogFragment.TAG)
            }
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        if (isTv) {
            val yellowFill = getColor(R.color.tv_button_focused_yellow)
            val blackText = getColor(R.color.tv_button_focused_yellow_text)
            val glassColor = getColor(R.color.tv_glass_white_10)
            val primaryText = getColor(R.color.tv_text_primary)
            val secondaryText = getColor(R.color.tv_text_secondary)
            val activeColor = getColor(R.color.tv_accent)

            val tvCards = listOf(
                Pair(opt500Mb as? MaterialCardView, rb500Mb),
                Pair(opt1Gb as? MaterialCardView, rb1Gb),
                Pair(opt2Gb as? MaterialCardView, rb2Gb),
                Pair(opt5Gb as? MaterialCardView, rb5Gb),
                Pair(optCustom as? MaterialCardView, rbCustom)
            )

            for ((card, rb) in tvCards) {
                if (card == null) continue
                card.setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus) {
                        card.setCardBackgroundColor(yellowFill)
                        setChildTextColors(card, blackText)
                        rb.buttonTintList = ColorStateList.valueOf(blackText)
                    } else {
                        card.setCardBackgroundColor(glassColor)
                        setChildTextColors(card, primaryText)
                        rb.buttonTintList = ColorStateList.valueOf(if (rb.isChecked) activeColor else secondaryText)
                    }
                }
            }
        }

        dialog.show()
    }

    private fun showClearConfirmDialog() {
        val layoutRes = if (isTv) R.layout.dialog_network_cache_clear_confirm_tv else R.layout.dialog_network_cache_clear_confirm
        val view = LayoutInflater.from(this).inflate(layoutRes, null)

        val btnClearConfirm = view.findViewById<View>(R.id.btnClearConfirm)
        val btnCancel = view.findViewById<View>(R.id.btnCancel)

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnClearConfirm.setOnClickListener {
            dialog.dismiss()
            lifecycleScope.launch(Dispatchers.IO) {
                cacheManager.clearAllCache()
                withContext(Dispatchers.Main) {
                    updateUI()
                }
            }
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun setupTvCardFocus(card: MaterialCardView) {
        val yellowFill = getColor(R.color.tv_button_focused_yellow)
        val blackText = getColor(R.color.tv_button_focused_yellow_text)
        val glassColor = getColor(R.color.tv_glass_white_10)
        val primaryText = getColor(R.color.tv_text_primary)
        val secondaryText = getColor(R.color.tv_text_secondary)
        val errorRed = getColor(R.color.tv_error_red)
        val isClearCard = (card.id == R.id.btnClearCache)

        card.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                card.setCardBackgroundColor(yellowFill)
                setChildTextColors(card, blackText)
            } else {
                card.setCardBackgroundColor(glassColor)
                setChildTextColorsTwo(card, primaryText, secondaryText, errorRed, isClearCard)
            }
        }
    }

    private fun setChildTextColors(view: View, color: Int) {
        if (view is TextView) {
            view.setTextColor(color)
        } else if (view is ImageView && view !is SwitchMaterial) {
            view.imageTintList = ColorStateList.valueOf(color)
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                setChildTextColors(view.getChildAt(i), color)
            }
        }
    }

    private fun setChildTextColorsTwo(view: View, primary: Int, secondary: Int, error: Int, isErrorCard: Boolean) {
        if (view is TextView) {
            if (isErrorCard && view.id == R.id.txtClearTitle) {
                view.setTextColor(error)
            } else if (view.id == R.id.txtClearDesc || view.id == R.id.txtEnableDesc || view.id == R.id.txtCacheFolder || view.id == R.id.txtCacheLimit || view.id == R.id.txtStats) {
                view.setTextColor(secondary)
            } else {
                view.setTextColor(primary)
            }
        } else if (view is ImageView && view !is SwitchMaterial) {
            if (isErrorCard) {
                view.imageTintList = ColorStateList.valueOf(error)
            } else if (view.id == R.id.imgFolderChevron || view.id == R.id.imgLimitChevron) {
                view.imageTintList = ColorStateList.valueOf(secondary)
            } else {
                view.imageTintList = ColorStateList.valueOf(getColor(R.color.white))
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                setChildTextColorsTwo(view.getChildAt(i), primary, secondary, error, isErrorCard)
            }
        }
    }
}
