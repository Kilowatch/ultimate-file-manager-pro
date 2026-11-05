package za.kilowatch.ultimatefilemanager.viewer.subtitles

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.CaptioningManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import androidx.media3.ui.CaptionStyleCompat
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.settings.PlayerPreferencesManager

/**
 * Android TV 10-foot modal glass dialog for subtitle styling customization.
 * Adheres strictly to UFMStandard TV dialog guidelines with D-pad navigation and yellow focus rings.
 */
class SubtitleStyleTvDialog : DialogFragment() {

    companion object {
        const val TAG = "SubtitleStyleTvDialog"

        fun newInstance(onDismiss: (() -> Unit)? = null): SubtitleStyleTvDialog {
            return SubtitleStyleTvDialog().apply {
                this.onDismissCallback = onDismiss
            }
        }
    }

    var onDismissCallback: (() -> Unit)? = null
    private lateinit var currentConfig: SubtitleStyleConfig

    private lateinit var previewView: SubtitlePreviewView
    private lateinit var btnModeSystem: TextView
    private lateinit var btnModeCustom: TextView
    private lateinit var layoutSystemSection: View
    private lateinit var layoutCustomSection: View
    private lateinit var txtSystemUnavailableHint: TextView
    private lateinit var txtTextSizeValue: TextView
    private lateinit var btnTextSizeDec: Button
    private lateinit var btnTextSizeInc: Button
    private lateinit var layoutColorSwatches: LinearLayout
    private lateinit var layoutBgChips: LinearLayout
    private lateinit var layoutEdgeChips: LinearLayout
    private lateinit var txtBottomMarginValue: TextView
    private lateinit var btnBottomMarginDec: Button
    private lateinit var btnBottomMarginInc: Button
    private lateinit var switchApplyEmbedded: SwitchCompat

    override fun onStart() {
        super.onStart()
        dialog?.window?.let { window ->
            val dp = resources.displayMetrics.density
            val widthPx = (680 * dp).toInt().coerceAtMost(resources.displayMetrics.widthPixels - (32 * dp).toInt())
            window.setLayout(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT)
            window.setGravity(Gravity.CENTER)
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_subtitle_style_tv, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val context = requireContext()
        currentConfig = SubtitleStyleConfig.fromPreferences(context, isTv = true)

        previewView = view.findViewById(R.id.subtitlePreviewView)
        btnModeSystem = view.findViewById(R.id.btnModeSystem)
        btnModeCustom = view.findViewById(R.id.btnModeCustom)
        layoutSystemSection = view.findViewById(R.id.layoutSystemSection)
        layoutCustomSection = view.findViewById(R.id.layoutCustomSection)
        txtSystemUnavailableHint = view.findViewById(R.id.txtSystemUnavailableHint)
        txtTextSizeValue = view.findViewById(R.id.txtTextSizeValue)
        btnTextSizeDec = view.findViewById(R.id.btnTextSizeDec)
        btnTextSizeInc = view.findViewById(R.id.btnTextSizeInc)
        layoutColorSwatches = view.findViewById(R.id.layoutColorSwatches)
        layoutBgChips = view.findViewById(R.id.layoutBgChips)
        layoutEdgeChips = view.findViewById(R.id.layoutEdgeChips)
        txtBottomMarginValue = view.findViewById(R.id.txtBottomMarginValue)
        btnBottomMarginDec = view.findViewById(R.id.btnBottomMarginDec)
        btnBottomMarginInc = view.findViewById(R.id.btnBottomMarginInc)
        switchApplyEmbedded = view.findViewById(R.id.switchApplyEmbedded)

        val btnOpenSystemSettings = view.findViewById<Button>(R.id.btnOpenSystemSettings)
        val btnDone = view.findViewById<Button>(R.id.btnDone)

        // Check if CaptioningManager is present
        val cm = context.getSystemService(Context.CAPTIONING_SERVICE) as? CaptioningManager
        if (cm == null) {
            txtSystemUnavailableHint.visibility = View.VISIBLE
        }

        // Mode buttons
        btnModeSystem.setOnClickListener {
            currentConfig = currentConfig.copy(mode = PlayerPreferencesManager.SUBTITLE_MODE_SYSTEM)
            updateModeUI()
            updatePreview()
        }

        btnModeCustom.setOnClickListener {
            currentConfig = currentConfig.copy(mode = PlayerPreferencesManager.SUBTITLE_MODE_CUSTOM)
            updateModeUI()
            updatePreview()
        }

        btnOpenSystemSettings.setOnClickListener {
            openSystemCaptionSettings(context)
        }

        // Text Size Steppers
        updateTextSizeLabel(currentConfig.textSizeMultiplier)
        btnTextSizeDec.setOnClickListener {
            val newScale = (currentConfig.textSizeMultiplier - 0.1f).coerceIn(0.5f, 2.0f)
            currentConfig = currentConfig.copy(textSizeMultiplier = newScale)
            updateTextSizeLabel(newScale)
            updatePreview()
        }

        btnTextSizeInc.setOnClickListener {
            val newScale = (currentConfig.textSizeMultiplier + 0.1f).coerceIn(0.5f, 2.0f)
            currentConfig = currentConfig.copy(textSizeMultiplier = newScale)
            updateTextSizeLabel(newScale)
            updatePreview()
        }

        // Color swatches
        setupColorSwatches(context)

        // Background Opacity chips
        setupBgChips(context)

        // Edge Type chips
        setupEdgeChips(context)

        // Bottom Margin Steppers
        txtBottomMarginValue.text = "${currentConfig.bottomPaddingDp} dp"
        btnBottomMarginDec.setOnClickListener {
            val newMargin = (currentConfig.bottomPaddingDp - 4).coerceIn(0, 64)
            currentConfig = currentConfig.copy(bottomPaddingDp = newMargin)
            txtBottomMarginValue.text = "$newMargin dp"
            updatePreview()
        }

        btnBottomMarginInc.setOnClickListener {
            val newMargin = (currentConfig.bottomPaddingDp + 4).coerceIn(0, 64)
            currentConfig = currentConfig.copy(bottomPaddingDp = newMargin)
            txtBottomMarginValue.text = "$newMargin dp"
            updatePreview()
        }

        // Embedded Styles Switch
        switchApplyEmbedded.isChecked = currentConfig.applyEmbeddedStyles
        switchApplyEmbedded.setOnCheckedChangeListener { _, isChecked ->
            currentConfig = currentConfig.copy(applyEmbeddedStyles = isChecked)
            updatePreview()
        }

        btnDone.setOnClickListener {
            dismiss()
        }

        updateModeUI()
        updatePreview()

        // Request initial focus on the active mode button
        if (currentConfig.mode == PlayerPreferencesManager.SUBTITLE_MODE_SYSTEM) {
            btnModeSystem.requestFocus()
        } else {
            btnModeCustom.requestFocus()
        }
    }

    private fun updateModeUI() {
        val isSystem = currentConfig.mode == PlayerPreferencesManager.SUBTITLE_MODE_SYSTEM
        if (isSystem) {
            layoutSystemSection.visibility = View.VISIBLE
            layoutCustomSection.visibility = View.GONE
        } else {
            layoutSystemSection.visibility = View.GONE
            layoutCustomSection.visibility = View.VISIBLE
        }
    }

    private fun updateTextSizeLabel(multiplier: Float) {
        val pct = (multiplier * 100).toInt()
        txtTextSizeValue.text = "$pct%"
    }

    private fun updatePreview() {
        if (::previewView.isInitialized) {
            previewView.updateStyle(currentConfig)
        }
    }

    private fun setupColorSwatches(context: Context) {
        layoutColorSwatches.removeAllViews()
        val dp = resources.displayMetrics.density
        val sizePx = (44 * dp).toInt()
        val marginPx = (10 * dp).toInt()

        for (color in SubtitleStyleBottomSheet.COLOR_PALETTE) {
            val isSelected = color == currentConfig.textColor

            val swatchFrame = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).apply {
                    marginEnd = marginPx
                }
                isClickable = true
                isFocusable = true
                background = ContextCompat.getDrawable(context, R.drawable.selector_tv_color_swatch)
            }

            val circle = View(context).apply {
                val circleDp = (34 * dp).toInt()
                layoutParams = FrameLayout.LayoutParams(circleDp, circleDp).apply {
                    gravity = Gravity.CENTER
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                    if (isSelected) {
                        setStroke((3 * dp).toInt(), ContextCompat.getColor(context, R.color.tv_accent))
                    } else {
                        setStroke((1.5f * dp).toInt(), Color.parseColor("#4B5563"))
                    }
                }
            }
            swatchFrame.addView(circle)

            if (isSelected) {
                val check = ImageView(context).apply {
                    layoutParams = FrameLayout.LayoutParams((20 * dp).toInt(), (20 * dp).toInt()).apply {
                        gravity = Gravity.CENTER
                    }
                    setImageResource(R.drawable.ic_check)
                    setColorFilter(if (color == 0xFFFFFFFF.toInt() || color == 0xFFFBBF24.toInt()) Color.BLACK else Color.WHITE)
                }
                swatchFrame.addView(check)
            }

            swatchFrame.setOnClickListener {
                currentConfig = currentConfig.copy(textColor = color)
                setupColorSwatches(context)
                updatePreview()
            }

            layoutColorSwatches.addView(swatchFrame)
        }
    }

    private fun setupBgChips(context: Context) {
        layoutBgChips.removeAllViews()
        val dp = resources.displayMetrics.density
        val marginPx = (8 * dp).toInt()

        for (option in SubtitleStyleBottomSheet.BG_OPACITY_OPTIONS) {
            val colorVal = option.first
            val labelRes = option.second
            val isSelected = colorVal == currentConfig.backgroundColor

            val chip = TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, (40 * dp).toInt(), 1f).apply {
                    marginEnd = marginPx
                }
                gravity = Gravity.CENTER
                text = context.getString(labelRes)
                textSize = 14f
                setTextColor(if (isSelected) ContextCompat.getColor(context, R.color.tv_accent) else ContextCompat.getColor(context, R.color.tv_text_primary))
                setBackgroundResource(R.drawable.selector_tv_button_yellow)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    currentConfig = currentConfig.copy(backgroundColor = colorVal)
                    setupBgChips(context)
                    updatePreview()
                }
            }
            layoutBgChips.addView(chip)
        }
    }

    private fun setupEdgeChips(context: Context) {
        layoutEdgeChips.removeAllViews()
        val dp = resources.displayMetrics.density
        val marginPx = (8 * dp).toInt()

        for (option in SubtitleStyleBottomSheet.EDGE_TYPE_OPTIONS) {
            val edgeVal = option.first
            val labelRes = option.second
            val isSelected = edgeVal == currentConfig.edgeType

            val chip = TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, (40 * dp).toInt()
                ).apply {
                    marginEnd = marginPx
                }
                setPadding((16 * dp).toInt(), 0, (16 * dp).toInt(), 0)
                gravity = Gravity.CENTER
                text = context.getString(labelRes)
                textSize = 14f
                setTextColor(if (isSelected) ContextCompat.getColor(context, R.color.tv_accent) else ContextCompat.getColor(context, R.color.tv_text_primary))
                setBackgroundResource(R.drawable.selector_tv_button_yellow)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    currentConfig = currentConfig.copy(edgeType = edgeVal)
                    setupEdgeChips(context)
                    updatePreview()
                }
            }
            layoutEdgeChips.addView(chip)
        }
    }

    private fun openSystemCaptionSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_CAPTIONING_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            try {
                val fallbackIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                fallbackIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(fallbackIntent)
            } catch (ex: Exception) {
                Toast.makeText(context, R.string.subtitle_style_system_unavailable, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        context?.let { ctx ->
            currentConfig.saveToPreferences(ctx)
        }
        onDismissCallback?.invoke()
    }
}
