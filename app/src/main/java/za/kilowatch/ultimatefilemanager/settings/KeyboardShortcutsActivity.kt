package za.kilowatch.ultimatefilemanager.settings

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.util.DeviceUtils

/**
 * Settings activity to configure hardware / Bluetooth keyboard shortcuts,
 * master enable/disable toggle, Vim navigation mode, and individual key allocations.
 * Available only on Mobile devices.
 */
class KeyboardShortcutsActivity : AppCompatActivity() {

    private lateinit var switchMasterEnable: SwitchMaterial
    private lateinit var switchVimMode: SwitchMaterial
    private lateinit var switchDualPane: SwitchMaterial
    private lateinit var cardModeOptions: MaterialCardView
    private lateinit var layoutKeybindsContainer: LinearLayout

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)

        if (DeviceUtils.isTvDevice(this)) {
            finish()
            return
        }

        enableEdgeToEdge()
        setContentView(R.layout.activity_keyboard_shortcuts)

        val rootView = findViewById<View>(R.id.main)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(
                systemBars.left, systemBars.top,
                systemBars.right, systemBars.bottom
            )
            insets
        }

        val btnBack = findViewById<View?>(R.id.btnBack)
        val btnResetDefaults = findViewById<View?>(R.id.btnResetDefaults)
        switchMasterEnable = findViewById(R.id.switchMasterEnable)
        switchVimMode = findViewById(R.id.switchVimMode)
        switchDualPane = findViewById(R.id.switchDualPane)
        cardModeOptions = findViewById(R.id.cardModeOptions)
        layoutKeybindsContainer = findViewById(R.id.layoutKeybindsContainer)

        btnBack?.setOnClickListener { finish() }
        btnResetDefaults?.setOnClickListener { showResetConfirmDialog() }

        initSwitches()
        rebuildKeybindList()
    }

    private fun initSwitches() {
        val isMaster = KeyboardPreferenceManager.isMasterEnabled(this)
        switchMasterEnable.isChecked = isMaster

        val isVim = KeyboardPreferenceManager.isVimModeEnabled(this)
        switchVimMode.isChecked = isVim

        val isDualPane = KeyboardPreferenceManager.isDualPaneSwitchEnabled(this)
        switchDualPane.isChecked = isDualPane

        updateControlsState(isMaster)

        switchMasterEnable.setOnCheckedChangeListener { _, isChecked ->
            KeyboardPreferenceManager.setMasterEnabled(this, isChecked)
            updateControlsState(isChecked)
        }

        switchVimMode.setOnCheckedChangeListener { _, isChecked ->
            KeyboardPreferenceManager.setVimModeEnabled(this, isChecked)
        }

        switchDualPane.setOnCheckedChangeListener { _, isChecked ->
            KeyboardPreferenceManager.setDualPaneSwitchEnabled(this, isChecked)
        }

        // Entire row clicks toggle switches (Mobile and TV)
        findViewById<View?>(R.id.cardMasterEnable)?.setOnClickListener {
            switchMasterEnable.toggle()
        }
        findViewById<View?>(R.id.rowVimMode)?.setOnClickListener {
            if (switchMasterEnable.isChecked) {
                switchVimMode.toggle()
            }
        }
        findViewById<View?>(R.id.rowDualPane)?.setOnClickListener {
            if (switchMasterEnable.isChecked) {
                switchDualPane.toggle()
            }
        }
    }

    private fun updateControlsState(masterEnabled: Boolean) {
        val alphaVal = if (masterEnabled) 1.0f else 0.45f
        cardModeOptions.alpha = alphaVal
        cardModeOptions.isEnabled = masterEnabled
        switchVimMode.isEnabled = masterEnabled
        switchDualPane.isEnabled = masterEnabled
        layoutKeybindsContainer.alpha = alphaVal
        for (i in 0 until layoutKeybindsContainer.childCount) {
            layoutKeybindsContainer.getChildAt(i).isEnabled = masterEnabled
        }
    }

    private fun rebuildKeybindList() {
        layoutKeybindsContainer.removeAllViews()

        val grouped = KeyboardPreferenceManager.ALL_BINDINGS.groupBy { it.categoryResId }

        for ((categoryResId, bindings) in grouped) {
            // Category Section Title
            val sectionTitle = TextView(this).apply {
                setText(categoryResId)
                textSize = 12f
                typeface = android.graphics.Typeface.create("sans-serif-bold", android.graphics.Typeface.BOLD)
                letterSpacing = 0.08f
                isAllCaps = true
                val typedValue = android.util.TypedValue()
                theme.resolveAttribute(androidx.appcompat.R.attr.colorPrimary, typedValue, true)
                setTextColor(typedValue.data)

                val dp8 = (8 * resources.displayMetrics.density).toInt()
                val dp16 = (16 * resources.displayMetrics.density).toInt()
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(dp8, dp16, dp8, dp8)
                }
            }
            layoutKeybindsContainer.addView(sectionTitle)

            // Category Card
            val card = MaterialCardView(this).apply {
                radius = (16 * resources.displayMetrics.density)
                cardElevation = 0f
                strokeWidth = (1 * resources.displayMetrics.density).toInt()
                setStrokeColor(android.content.res.ColorStateList.valueOf(getColor(R.color.mobile_glass_stroke)))
                setCardBackgroundColor(getColor(R.color.mobile_glass_card))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (16 * resources.displayMetrics.density).toInt()
                }
            }

            val cardInner = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 0, 0, 0)
            }

            for (i in bindings.indices) {
                val binding = bindings[i]
                val rowView = LayoutInflater.from(this).inflate(R.layout.item_keyboard_shortcut_row, cardInner, false)
                val txtTitle = rowView.findViewById<TextView>(R.id.txtActionTitle)
                val txtDesc = rowView.findViewById<TextView>(R.id.txtActionDesc)
                val txtKeyBadge = rowView.findViewById<TextView>(R.id.txtKeyBadge)

                txtTitle.setText(binding.titleResId)
                txtDesc.setText(binding.descResId)
                txtKeyBadge.text = KeyboardPreferenceManager.getCustomBindingDisplay(this, binding.actionId)

                txtTitle.setTextColor(getColor(R.color.mobile_card_text_primary))
                txtDesc.setTextColor(getColor(R.color.mobile_text_secondary))

                rowView.setOnClickListener {
                    if (KeyboardPreferenceManager.isMasterEnabled(this)) {
                        showRebindDialog(binding)
                    }
                }

                cardInner.addView(rowView)

                if (i < bindings.size - 1) {
                    val divider = View(this).apply {
                        val dp16 = (16 * resources.displayMetrics.density).toInt()
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            (1 * resources.displayMetrics.density).toInt()
                        ).apply {
                            marginStart = dp16
                            marginEnd = dp16
                        }
                        setBackgroundColor(getColor(R.color.mobile_glass_stroke))
                    }
                    cardInner.addView(divider)
                }
            }

            card.addView(cardInner)
            layoutKeybindsContainer.addView(card)
        }
    }

    private fun showRebindDialog(binding: KeyboardPreferenceManager.KeyBinding) {
        val currentDisplay = KeyboardPreferenceManager.getCustomBindingDisplay(this, binding.actionId)
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_keyboard_rebind, null)

        val txtActionName = dialogView.findViewById<TextView>(R.id.txtActionName)
        val txtCurrentKey = dialogView.findViewById<TextView>(R.id.txtCurrentKey)
        val btnResetDefault = dialogView.findViewById<View>(R.id.btnResetDefault)
        val btnCancel = dialogView.findViewById<View>(R.id.btnCancel)

        txtActionName.setText(binding.titleResId)
        txtCurrentKey.text = getString(R.string.keyboard_rebind_current, currentDisplay)

        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        dialog.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode != KeyEvent.KEYCODE_BACK && keyCode != KeyEvent.KEYCODE_ESCAPE) {
                val keyChar = event.unicodeChar.toChar()
                val displayKey = if (keyChar.isLetterOrDigit() || keyChar in listOf('/', '.', '?', ',', ';')) {
                    keyChar.toString().uppercase()
                } else {
                    KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")
                }
                KeyboardPreferenceManager.setCustomBindingDisplay(this, binding.actionId, displayKey)
                Toast.makeText(this, getString(R.string.keyboard_rebind_success, displayKey), Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                rebuildKeybindList()
                return@setOnKeyListener true
            }
            false
        }

        btnResetDefault.setOnClickListener {
            KeyboardPreferenceManager.setCustomBindingDisplay(this, binding.actionId, binding.defaultDisplayKey)
            dialog.dismiss()
            rebuildKeybindList()
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showResetConfirmDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_keyboard_shortcuts_reset_confirm, null)
        val btnResetConfirm = dialogView.findViewById<View>(R.id.btnResetConfirm)
        val btnCancel = dialogView.findViewById<View>(R.id.btnCancel)

        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        btnResetConfirm.setOnClickListener {
            dialog.dismiss()
            KeyboardPreferenceManager.resetToDefaults(this)
            initSwitches()
            rebuildKeybindList()
            Toast.makeText(this, R.string.keyboard_shortcuts_reset_defaults, Toast.LENGTH_SHORT).show()
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }
}
