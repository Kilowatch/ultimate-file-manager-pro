package za.kilowatch.ultimatefilemanager.onboarding

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import com.google.android.material.textfield.MaterialAutoCompleteTextView
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
        LanguageItem(LocaleHelper.LOCALE_EN, "English", "gb.svg"),
        LanguageItem(LocaleHelper.LOCALE_DE, "German (Deutsch)", "de.svg"),
        LanguageItem(LocaleHelper.LOCALE_ES, "Spanish (Español)", "es.svg"),
        LanguageItem(LocaleHelper.LOCALE_FR, "French (Français)", "fr.svg"),
        LanguageItem(LocaleHelper.LOCALE_PT, "Portuguese (Português)", "br.svg"),
        LanguageItem(LocaleHelper.LOCALE_RU, "Russian (Русский)", "ru.svg"),
        LanguageItem(LocaleHelper.LOCALE_TR, "Turkish (Türkçe)", "tr.svg"),
        LanguageItem(LocaleHelper.LOCALE_ID, "Indonesian (Bahasa Indonesia)", "id.svg"),
        LanguageItem(LocaleHelper.LOCALE_AR, "Arabic (العربية)", "sa.svg"),
        LanguageItem(LocaleHelper.LOCALE_HI, "Hindi (हिन्दी)", "in.svg"),
        LanguageItem(LocaleHelper.LOCALE_JA, "Japanese (日本語)", "jp.svg"),
        LanguageItem(LocaleHelper.LOCALE_KO, "Korean (한국어)", "kr.svg")
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
        
        selectedLocale = LocaleHelper.getSavedLocale(this)
        if (selectedLocale == LocaleHelper.LOCALE_DEFAULT) {
            selectedLocale = LocaleHelper.LOCALE_EN
        }

        updateSelectedLanguageUI(selectedLocale)
        
        cardLanguageSelector.setOnClickListener {
            showLanguageSelectorDialog()
        }
        
        btnSetDefault.setOnClickListener {
            setWelcomeFinished()
            goToNextScreen()
        }
        
        if (isTv) {
            cardLanguageSelector.requestFocus()
            setupTvFocus()
        }
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
        txtSelectedLanguage.text = item.name
        
        // Load flag SVG from assets using Coil 3 with placeholder/error
        val placeholderImage = ContextCompat.getDrawable(this, R.drawable.ic_photo_video)?.asImage()
        imgSelectedFlag.load("file:///android_asset/remote/flags/${item.flagAsset}") {
            placeholder(placeholderImage)
            error(placeholderImage)
        }
    }

    private fun showLanguageSelectorDialog() {
        val dialog = if (isTv) {
            android.app.Dialog(this).apply {
                window?.setBackgroundDrawableResource(android.R.color.transparent)
            }
        } else {
            com.google.android.material.bottomsheet.BottomSheetDialog(this)
        }
        
        dialog.setContentView(R.layout.dialog_language_selection)
        
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
    }

    private fun setupTvFocus() {
        val yellowBg = ColorStateList.valueOf(getColor(R.color.tv_button_focused_yellow))
        val glassBg  = ColorStateList.valueOf(getColor(R.color.tv_glass_white_10))
        val yellowText = getColor(R.color.tv_button_focused_yellow_text)
        val whiteText  = getColor(R.color.tv_text_primary)
        
        val btn = btnSetDefault as? com.google.android.material.button.MaterialButton
        btn?.setOnFocusChangeListener { _, hasFocus ->
            btn.backgroundTintList = if (hasFocus) yellowBg else glassBg
            btn.setTextColor(if (hasFocus) yellowText else whiteText)
        }
        
        cardLanguageSelector.setOnFocusChangeListener { _, hasFocus ->
            cardLanguageSelector.setCardBackgroundColor(if (hasFocus) getColor(R.color.tv_button_focused_yellow) else Color.parseColor("#22FFFFFF"))
            txtSelectedLanguage.setTextColor(if (hasFocus) yellowText else whiteText)
        }
    }

    private fun goToNextScreen() {
        startActivity(Intent(this, PolicyWelcomeActivity::class.java))
        finish()
    }

    data class LanguageItem(val code: String, val name: String, val flagAsset: String)

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
            
            // Load flag SVG from assets using Coil 3 with placeholder/error
            val ph = ContextCompat.getDrawable(holder.itemView.context, R.drawable.ic_photo_video)?.asImage()
            holder.imgFlag.load("file:///android_asset/remote/flags/${item.flagAsset}") {
                placeholder(ph)
                error(ph)
            }

            val context = holder.itemView.context
            val ufmPrimary = context.getColor(R.color.ufm_primary) // usually a blue/green
            val whiteColor = Color.parseColor("#FFFFFF")
            val selectedBg = Color.parseColor("#1AFFFFFF")
            
            // Initial State based on selection
            if (item.code == currentLocale) {
                holder.txtName.setTextColor(if (isTv) context.getColor(R.color.tv_button_focused_yellow) else ufmPrimary)
                holder.itemView.setBackgroundColor(selectedBg)
            } else {
                holder.txtName.setTextColor(whiteColor)
                holder.itemView.setBackgroundResource(R.drawable.selector_dropdown_item)
            }

            holder.itemView.setOnClickListener {
                onSelected(item.code)
            }
            
            if (isTv) {
                val yellowColor = context.getColor(R.color.tv_button_focused_yellow)
                val blackText  = context.getColor(R.color.tv_button_focused_yellow_text)
                holder.itemView.isFocusable = true
                holder.itemView.setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus) {
                        holder.itemView.setBackgroundColor(yellowColor)
                        holder.txtName.setTextColor(blackText)
                    } else {
                        if (item.code == currentLocale) {
                            holder.itemView.setBackgroundColor(selectedBg)
                            holder.txtName.setTextColor(yellowColor)
                        } else {
                            holder.itemView.setBackgroundResource(R.drawable.selector_dropdown_item)
                            holder.txtName.setTextColor(whiteColor)
                        }
                    }
                }
            }
        }

        override fun getItemCount(): Int = list.size

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val imgFlag: ImageView = view.findViewById(R.id.imgFlag)
            val txtName: TextView = view.findViewById(R.id.txtLanguageName)
        }
    }
}
