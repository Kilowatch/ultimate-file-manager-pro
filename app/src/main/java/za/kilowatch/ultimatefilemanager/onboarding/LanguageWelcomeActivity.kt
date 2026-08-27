package za.kilowatch.ultimatefilemanager.onboarding

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import coil3.asImage
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import android.content.res.Resources
import androidx.core.os.ConfigurationCompat
import java.util.Locale
import za.kilowatch.ultimatefilemanager.BuildConfig
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import za.kilowatch.ultimatefilemanager.settings.ThemeHelper
import za.kilowatch.ultimatefilemanager.util.DeviceUtils

class LanguageWelcomeActivity : AppCompatActivity() {

    private var isTv = false
    private var selectedLocale = LocaleHelper.LOCALE_EN // default
    
    private lateinit var btnSetDefault: View
    private lateinit var cardLanguageSelector: MaterialCardView
    private lateinit var txtSelectedLanguage: TextView
    private lateinit var imgSelectedFlag: ImageView
    
    private val languages = listOf(
        LanguageItem(LocaleHelper.LOCALE_EN, "English", "English", "gb.svg"),
        LanguageItem(LocaleHelper.LOCALE_DE, "German", "Deutsch", "de.svg"),
        LanguageItem(LocaleHelper.LOCALE_ES, "Spanish", "Español", "es.svg"),
        LanguageItem(LocaleHelper.LOCALE_FR, "French", "Français", "fr.svg"),
        LanguageItem(LocaleHelper.LOCALE_IT, "Italian", "Italiano", "it.svg"),
        LanguageItem(LocaleHelper.LOCALE_PT, "Portuguese", "Português", "br.svg"),
        LanguageItem(LocaleHelper.LOCALE_RU, "Russian", "Русский", "ru.svg"),
        LanguageItem(LocaleHelper.LOCALE_TR, "Turkish", "Türkçe", "tr.svg"),
        LanguageItem(LocaleHelper.LOCALE_ID, "Indonesian", "Bahasa Indonesia", "id.svg"),
        LanguageItem(LocaleHelper.LOCALE_AR, "Arabic", "العربية", "sa.svg"),
        LanguageItem(LocaleHelper.LOCALE_HI, "Hindi", "हिन्दी", "in.svg"),
        LanguageItem(LocaleHelper.LOCALE_JA, "Japanese", "日本語", "jp.svg"),
        LanguageItem(LocaleHelper.LOCALE_KO, "Korean", "한국어", "kr.svg"),
        LanguageItem(LocaleHelper.LOCALE_UK, "Ukrainian", "Українська", "ua.svg")
    )

    override fun attachBaseContext(newBase: Context) {
        // We wrap with LocaleHelper during onboarding so they see the language change immediately
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        
        // If welcome screen already finished, follow the current flow
        if (isWelcomeFinished()) {
            goToNextScreen()
            return
        }

        enableEdgeToEdge()
        isTv = DeviceUtils.isTvDevice(this)

        val layoutRes = if (isTv) R.layout.activity_language_welcome_tv else R.layout.activity_language_welcome
        setContentView(layoutRes)
        
        val mainView = findViewById<View>(R.id.main)
        ViewCompat.setOnApplyWindowInsetsListener(mainView) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        btnSetDefault = findViewById(R.id.btnSetDefault)
        cardLanguageSelector = findViewById(R.id.cardLanguageSelector)
        txtSelectedLanguage = findViewById(R.id.txtSelectedLanguage)
        imgSelectedFlag = findViewById(R.id.imgSelectedFlag)
        
        val saved = LocaleHelper.getSavedLocale(this)
        selectedLocale = if (saved == LocaleHelper.LOCALE_DEFAULT) {
            val detected = detectDeviceLanguage()
            LocaleHelper.save(this, detected)
            detected
        } else {
            val matched = languages.find { it.code.equals(saved, ignoreCase = true) }
            matched?.code ?: detectDeviceLanguage()
        }

        updateSelectedLanguageUI(selectedLocale)
        
        cardLanguageSelector.setOnClickListener {
            showLanguageSelectorDialog()
        }
        
        val txtFossLegalNotice = findViewById<TextView>(R.id.txtFossLegalNotice)
        if (BuildConfig.IS_FOSS) {
            txtFossLegalNotice?.visibility = View.VISIBLE
        } else {
            txtFossLegalNotice?.visibility = View.GONE
        }

        btnSetDefault.setOnClickListener {
            LocaleHelper.save(this, selectedLocale)
            setWelcomeFinished()
            if (BuildConfig.IS_FOSS) {
                val acceptancePrefs = getSharedPreferences("acceptance_prefs", Context.MODE_PRIVATE)
                val currentTime = System.currentTimeMillis()
                acceptancePrefs.edit()
                    .putLong("terms_accepted_time", currentTime)
                    .putLong("privacy_accepted_time", currentTime)
                    .apply()
            }
            goToNextScreen()
        }
        
        if (isTv) {
            cardLanguageSelector.requestFocus()
            setupTvFocus()
        }
    }

    /**
     * Auto-detect the device's system language.
     * If the language is in our supported list, returns its code.
     * Otherwise defaults to English ("en").
     */
    private fun detectDeviceLanguage(): String {
        val systemLocale = try {
            ConfigurationCompat.getLocales(Resources.getSystem().configuration)[0]
                ?: Locale.getDefault()
        } catch (_: Exception) {
            Locale.getDefault()
        }

        var langCode = systemLocale?.language?.lowercase(Locale.ROOT) ?: LocaleHelper.LOCALE_EN
        // Handle Java legacy ISO mapping for Indonesian ("in" -> "id")
        if (langCode == "in") {
            langCode = LocaleHelper.LOCALE_ID
        }

        val isSupported = languages.any { it.code.equals(langCode, ignoreCase = true) }
        return if (isSupported) langCode else LocaleHelper.LOCALE_EN
    }

    private fun isWelcomeFinished(): Boolean {
        val prefs = getSharedPreferences("ufm_prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean("welcome_languages_finished", false)
    }

    private fun setWelcomeFinished() {
        getSharedPreferences("ufm_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("welcome_languages_finished", true)
            .apply()
    }

    private fun updateSelectedLanguageUI(localeCode: String) {
        val item = languages.find { it.code == localeCode } ?: languages.first()
        txtSelectedLanguage.text = if (item.name == item.nativeSubtitle) item.name else "${item.name} (${item.nativeSubtitle})"
        
        // Load flag SVG from assets using Coil 3 with placeholder/error
        val placeholderImage = ContextCompat.getDrawable(this, R.drawable.ic_photo_video)?.asImage()
        imgSelectedFlag.load("file:///android_asset/remote/flags/${item.flagAsset}") {
            placeholder(placeholderImage)
            error(placeholderImage)
        }
    }

    private fun showLanguageSelectorDialog() {
        val dialog = if (isTv) {
            android.app.Dialog(this, R.style.UFM_Dialog).apply {
                window?.setBackgroundDrawableResource(android.R.color.transparent)
            }
        } else {
            com.google.android.material.bottomsheet.BottomSheetDialog(this, R.style.UFM_Dialog).apply {
                window?.setBackgroundDrawableResource(android.R.color.transparent)
            }
        }
        
        val layoutRes = if (isTv) R.layout.dialog_language_selection_tv else R.layout.dialog_language_selection
        dialog.setContentView(layoutRes)
        
        val rvDialogLanguages = dialog.findViewById<RecyclerView>(R.id.rvDialogLanguages)
        rvDialogLanguages?.layoutManager = LinearLayoutManager(this)
        
        val adapter = DialogLanguageAdapter(languages, selectedLocale, isTv) { newLocale ->
            dialog.dismiss()
            if (newLocale != selectedLocale) {
                LocaleHelper.save(this@LanguageWelcomeActivity, newLocale)
                recreate()
            }
        }
        rvDialogLanguages?.adapter = adapter
        
        dialog.show()

        if (isTv) {
            val widthPx = (520 * resources.displayMetrics.density).toInt()
            val screenWidth = resources.displayMetrics.widthPixels
            val finalWidth = minOf(widthPx, (screenWidth * 0.85).toInt())
            dialog.window?.setLayout(finalWidth, android.view.WindowManager.LayoutParams.WRAP_CONTENT)

            val selectedIndex = languages.indexOfFirst { it.code.equals(selectedLocale, ignoreCase = true) }.coerceAtLeast(0)
            rvDialogLanguages?.scrollToPosition(selectedIndex)
            rvDialogLanguages?.post {
                rvDialogLanguages.findViewHolderForAdapterPosition(selectedIndex)?.itemView?.findViewById<View>(R.id.cardLanguageItem)?.requestFocus()
                    ?: rvDialogLanguages.getChildAt(0)?.findViewById<View>(R.id.cardLanguageItem)?.requestFocus()
            }
        }
    }

    private fun setupTvFocus() {
        val yellowBg = ColorStateList.valueOf(getColor(R.color.tv_button_focused_yellow))
        val glassBg  = ColorStateList.valueOf(getColor(R.color.tv_glass_white_10))
        val yellowText = getColor(R.color.tv_button_focused_yellow_text)
        val whiteText  = getColor(R.color.tv_text_primary)
        val imgChevron = findViewById<ImageView>(R.id.imgChevron)
        val flagContainer = findViewById<FrameLayout>(R.id.flagContainer)
        val chevronContainer = findViewById<FrameLayout>(R.id.chevronContainer)
        
        val btn = btnSetDefault as? com.google.android.material.button.MaterialButton
        btn?.setOnFocusChangeListener { _, hasFocus ->
            btn.backgroundTintList = if (hasFocus) yellowBg else glassBg
            btn.setTextColor(if (hasFocus) yellowText else whiteText)
            btn.iconTint = ColorStateList.valueOf(if (hasFocus) yellowText else whiteText)
        }
        
        cardLanguageSelector.setOnFocusChangeListener { _, hasFocus ->
            cardLanguageSelector.setCardBackgroundColor(if (hasFocus) getColor(R.color.tv_button_focused_yellow) else getColor(R.color.tv_glass_white_10))
            txtSelectedLanguage.setTextColor(if (hasFocus) yellowText else whiteText)
            imgChevron?.imageTintList = ColorStateList.valueOf(if (hasFocus) yellowText else whiteText)
            if (hasFocus) {
                flagContainer?.setBackgroundColor(Color.parseColor("#1A000000"))
                chevronContainer?.setBackgroundColor(Color.parseColor("#1A000000"))
            } else {
                flagContainer?.setBackgroundResource(R.drawable.bg_tv_card_glass)
                chevronContainer?.setBackgroundResource(R.drawable.bg_tv_card_glass)
            }
        }
    }

    private fun goToNextScreen() {
        val targetClass = if (BuildConfig.IS_FOSS) {
            WelcomeActivity::class.java
        } else {
            PolicyWelcomeActivity::class.java
        }
        startActivity(Intent(this, targetClass))
        finish()
    }

    data class LanguageItem(
        val code: String,
        val name: String,
        val nativeSubtitle: String,
        val flagAsset: String
    )

    inner class DialogLanguageAdapter(
        private val list: List<LanguageItem>,
        private val currentLocale: String,
        private val isTv: Boolean,
        private val onSelected: (String) -> Unit
    ) : RecyclerView.Adapter<DialogLanguageAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_language_flag_minimal, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = list[position]
            holder.txtName.text = item.name
            holder.txtSubtitle.text = item.nativeSubtitle
            
            // Load flag SVG from assets using Coil 3 with placeholder/error
            val ph = ContextCompat.getDrawable(holder.itemView.context, R.drawable.ic_photo_video)?.asImage()
            holder.imgFlag.load("file:///android_asset/remote/flags/${item.flagAsset}") {
                placeholder(ph)
                error(ph)
            }

            val context = holder.itemView.context
            val isSelected = item.code == currentLocale
            val density = context.resources.displayMetrics.density

            if (!isTv) {
                if (isSelected) {
                    val primaryColor = context.getColor(R.color.ufm_primary)
                    holder.card.setCardBackgroundColor(Color.parseColor("#262196F3"))
                    holder.card.strokeColor = primaryColor
                    holder.card.strokeWidth = (1.5f * density).toInt()
                    holder.txtName.setTextColor(primaryColor)
                    holder.txtSubtitle.setTextColor(Color.parseColor("#CC2196F3"))
                    holder.checkContainer.visibility = View.VISIBLE
                    holder.imgCheck.imageTintList = ColorStateList.valueOf(primaryColor)
                } else {
                    holder.card.setCardBackgroundColor(context.getColor(R.color.mobile_glass_card))
                    holder.card.strokeColor = context.getColor(R.color.mobile_glass_stroke)
                    holder.card.strokeWidth = (1f * density).toInt()
                    holder.txtName.setTextColor(context.getColor(R.color.mobile_text_primary))
                    holder.txtSubtitle.setTextColor(context.getColor(R.color.mobile_text_secondary))
                    holder.checkContainer.visibility = View.GONE
                }
            } else {
                val yellowColor = context.getColor(R.color.tv_button_focused_yellow)
                val blackText = context.getColor(R.color.tv_button_focused_yellow_text)
                val whiteText = context.getColor(R.color.tv_text_primary)
                val secText = context.getColor(R.color.tv_text_secondary)
                val glassBg = context.getColor(R.color.tv_glass_white_10)
                val borderInactive = context.getColor(R.color.tv_glass_border)

                holder.card.isFocusable = true
                holder.card.isClickable = true

                fun applyTvState(hasFocus: Boolean) {
                    if (hasFocus) {
                        holder.card.setCardBackgroundColor(yellowColor)
                        holder.card.strokeColor = yellowColor
                        holder.card.strokeWidth = (2f * density).toInt()
                        holder.txtName.setTextColor(blackText)
                        holder.txtSubtitle.setTextColor(Color.parseColor("#88000000"))
                        holder.checkContainer.visibility = if (isSelected) View.VISIBLE else View.GONE
                        holder.imgCheck.imageTintList = ColorStateList.valueOf(blackText)
                        holder.flagContainer.setBackgroundColor(Color.parseColor("#1A000000"))
                        holder.checkContainer.setBackgroundColor(Color.parseColor("#1A000000"))
                    } else {
                        holder.flagContainer.setBackgroundResource(R.drawable.bg_tv_card_glass)
                        holder.checkContainer.setBackgroundResource(R.drawable.bg_tv_card_glass)
                        if (isSelected) {
                            holder.card.setCardBackgroundColor(glassBg)
                            holder.card.strokeColor = yellowColor
                            holder.card.strokeWidth = (2f * density).toInt()
                            holder.txtName.setTextColor(yellowColor)
                            holder.txtSubtitle.setTextColor(Color.parseColor("#CCFFCC00"))
                            holder.checkContainer.visibility = View.VISIBLE
                            holder.imgCheck.imageTintList = ColorStateList.valueOf(yellowColor)
                        } else {
                            holder.card.setCardBackgroundColor(glassBg)
                            holder.card.strokeColor = borderInactive
                            holder.card.strokeWidth = (1f * density).toInt()
                            holder.txtName.setTextColor(whiteText)
                            holder.txtSubtitle.setTextColor(secText)
                            holder.checkContainer.visibility = View.GONE
                        }
                    }
                }

                applyTvState(holder.card.hasFocus())
                holder.card.setOnFocusChangeListener { _, hasFocus ->
                    applyTvState(hasFocus)
                }
            }

            holder.card.setOnClickListener {
                onSelected(item.code)
            }
        }

        override fun getItemCount(): Int = list.size

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val card: MaterialCardView = view.findViewById(R.id.cardLanguageItem)
            val flagContainer: FrameLayout = view.findViewById(R.id.flagContainer)
            val imgFlag: ImageView = view.findViewById(R.id.imgFlag)
            val txtName: TextView = view.findViewById(R.id.txtLanguageName)
            val txtSubtitle: TextView = view.findViewById(R.id.txtLanguageSubtitle)
            val checkContainer: FrameLayout = view.findViewById(R.id.checkContainer)
            val imgCheck: ImageView = view.findViewById(R.id.imgCheck)
        }
    }
}
