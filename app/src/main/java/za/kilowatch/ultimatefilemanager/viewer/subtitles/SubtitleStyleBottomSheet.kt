package za.kilowatch.ultimatefilemanager.viewer.subtitles

import android.content.Context
import android.content.Intent
import android.graphics.Color
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
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.media3.ui.CaptionStyleCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.settings.PlayerPreferencesManager

/**
 * Mobile touch-optimized subtitle styling customizer adhering strictly to UFMStandard glass guidelines.
 *
 * Provides real-time preview, system/custom style toggle, granular custom controls
 * (text size, palette swatches, background opacity, edge effects, and bottom margin),
 * and intent fallback to Android system caption settings.
 */
class SubtitleStyleBottomSheet : BottomSheetDialogFragment() {

    companion object {
        const val TAG = "SubtitleStyleBottomSheet"

        fun newInstance(onDismiss: (() -> Unit)? = null): SubtitleStyleBottomSheet {
            return SubtitleStyleBottomSheet().apply {
                this.onDismissCallback = onDismiss
            }
        }

        val COLOR_PALETTE = intArrayOf(
            0xFFFBBF24.toInt(), // Yellow (Default)
            0xFFFFFFFF.toInt(), // White
            0xFF00E5FF.toInt(), // Cyan
            0xFF76FF03.toInt(), // Light Green
            0xFFFF4081.toInt(), // Magenta
            0xFFFF9100.toInt()  // Orange
        )

        val BG_OPACITY_OPTIONS = listOf(
            Pair(0x00000000, R.string.subtitle_style_bg_transparent),
            Pair(0x40000000, R.string.subtitle_style_bg_25),
            Pair(0x80000000.toInt(), R.string.subtitle_style_bg_50),
            Pair(0xBF000000.toInt(), R.string.subtitle_style_bg_75),
            Pair(0xFF000000.toInt(), R.string.subtitle_style_bg_100)
        )

        val EDGE_TYPE_OPTIONS = listOf(
            Pair(CaptionStyleCompat.EDGE_TYPE_OUTLINE, R.string.subtitle_style_edge_outline),
            Pair(CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW, R.string.subtitle_style_edge_drop_shadow),
            Pair(CaptionStyleCompat.EDGE_TYPE_RAISED, R.string.subtitle_style_edge_raised),
            Pair(CaptionStyleCompat.EDGE_TYPE_DEPRESSED, R.string.subtitle_style_edge_depressed),
            Pair(CaptionStyleCompat.EDGE_TYPE_NONE, R.string.subtitle_style_edge_none)
        )
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
    private lateinit var seekTextSize: SeekBar
    private lateinit var layoutColorSwatches: LinearLayout
    private lateinit var layoutBgChips: LinearLayout
    private lateinit var layoutEdgeChips: LinearLayout
    private lateinit var txtBottomMarginValue: TextView
    private lateinit var seekBottomMargin: SeekBar
    private lateinit var switchApplyEmbedded: SwitchCompat

    override fun onCreateDialog(savedInstanceState: Bundle?): android.app.Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.setOnShowListener {
            val sheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            if (sheet != null) {
                sheet.setBackgroundResource(android.R.color.transparent)
                val behavior = BottomSheetBehavior.from(sheet)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
            }
        }
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_subtitle_style, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val context = requireContext()
        currentConfig = SubtitleStyleConfig.fromPreferences(context, isTv = false)

        previewView = view.findViewById(R.id.subtitlePreviewView)
        btnModeSystem = view.findViewById(R.id.btnModeSystem)
        btnModeCustom = view.findViewById(R.id.btnModeCustom)
        layoutSystemSection = view.findViewById(R.id.layoutSystemSection)
        layoutCustomSection = view.findViewById(R.id.layoutCustomSection)
        txtSystemUnavailableHint = view.findViewById(R.id.txtSystemUnavailableHint)
        txtTextSizeValue = view.findViewById(R.id.txtTextSizeValue)
        seekTextSize = view.findViewById(R.id.seekTextSize)
        layoutColorSwatches = view.findViewById(R.id.layoutColorSwatches)
        layoutBgChips = view.findViewById(R.id.layoutBgChips)
        layoutEdgeChips = view.findViewById(R.id.layoutEdgeChips)
        txtBottomMarginValue = view.findViewById(R.id.txtBottomMarginValue)
        seekBottomMargin = view.findViewById(R.id.seekBottomMargin)
        switchApplyEmbedded = view.findViewById(R.id.switchApplyEmbedded)

        val btnOpenSystemSettings = view.findViewById<Button>(R.id.btnOpenSystemSettings)
        val btnDone = view.findViewById<Button>(R.id.btnDone)

        // Check if CaptioningManager is present
        val cm = context.getSystemService(Context.CAPTIONING_SERVICE) as? CaptioningManager
        if (cm == null) {
            txtSystemUnavailableHint.visibility = View.VISIBLE
        }

        // Mode switch buttons
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

        // Open system caption settings
        btnOpenSystemSettings.setOnClickListener {
            openSystemCaptionSettings(context)
        }

        // Text Size SeekBar (50% to 200%)
        val currentProgress = ((currentConfig.textSizeMultiplier - 0.5f) * 100).toInt().coerceIn(0, 150)
        seekTextSize.progress = currentProgress
        updateTextSizeLabel(currentConfig.textSizeMultiplier)
        seekTextSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val multiplier = 0.5f + (progress / 100f)
                    currentConfig = currentConfig.copy(textSizeMultiplier = multiplier)
                    updateTextSizeLabel(multiplier)
                    updatePreview()
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // Setup Color Swatches
        setupColorSwatches(context)

        // Setup Background Opacity Chips
        setupBgChips(context)

        // Setup Edge Type Chips
        setupEdgeChips(context)

        // Bottom Margin SeekBar (0 to 64dp)
        seekBottomMargin.progress = currentConfig.bottomPaddingDp.coerceIn(0, 64)
        txtBottomMarginValue.text = "${currentConfig.bottomPaddingDp} dp"
        seekBottomMargin.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    currentConfig = currentConfig.copy(bottomPaddingDp = progress)
                    txtBottomMarginValue.text = "$progress dp"
                    updatePreview()
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // Embedded Styles Switch
        switchApplyEmbedded.isChecked = currentConfig.applyEmbeddedStyles
        switchApplyEmbedded.setOnCheckedChangeListener { _, isChecked ->
            currentConfig = currentConfig.copy(applyEmbeddedStyles = isChecked)
            updatePreview()
        }

        // Done button
        btnDone.setOnClickListener {
            dismiss()
        }

        updateModeUI()
        updatePreview()
    }

    private fun updateModeUI() {
        val isSystem = currentConfig.mode == PlayerPreferencesManager.SUBTITLE_MODE_SYSTEM
        if (isSystem) {
            btnModeSystem.setBackgroundResource(R.drawable.bg_chip_selected)
            btnModeSystem.setTextColor(Color.WHITE)
            btnModeCustom.setBackgroundResource(R.drawable.bg_chip_unselected)
            btnModeCustom.setTextColor(ContextCompat.getColor(requireContext(), R.color.mobile_text_secondary))
            layoutSystemSection.visibility = View.VISIBLE
            layoutCustomSection.visibility = View.GONE
        } else {
            btnModeSystem.setBackgroundResource(R.drawable.bg_chip_unselected)
            btnModeSystem.setTextColor(ContextCompat.getColor(requireContext(), R.color.mobile_text_secondary))
            btnModeCustom.setBackgroundResource(R.drawable.bg_chip_selected)
            btnModeCustom.setTextColor(Color.WHITE)
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
        val sizePx = (36 * dp).toInt()
        val marginPx = (8 * dp).toInt()

        for (color in COLOR_PALETTE) {
            val isSelected = color == currentConfig.textColor

            val swatchFrame = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).apply {
                    marginEnd = marginPx
                }
                isClickable = true
                isFocusable = true
            }

            val circleDrawable = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
                if (isSelected) {
                    setStroke((3 * dp).toInt(), ContextCompat.getColor(context, R.color.ufm_primary))
                } else {
                    setStroke((1.5f * dp).toInt(), Color.parseColor("#4B5563"))
                }
            }
            swatchFrame.background = circleDrawable

            if (isSelected) {
                val check = ImageView(context).apply {
                    layoutParams = FrameLayout.LayoutParams((18 * dp).toInt(), (18 * dp).toInt()).apply {
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
        val marginPx = (6 * dp).toInt()

        for (option in BG_OPACITY_OPTIONS) {
            val colorVal = option.first
            val labelRes = option.second
            val isSelected = colorVal == currentConfig.backgroundColor

            val chip = TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, (34 * dp).toInt(), 1f).apply {
                    marginEnd = marginPx
                }
                gravity = Gravity.CENTER
                text = context.getString(labelRes)
                textSize = 12f
                setTextColor(if (isSelected) Color.WHITE else ContextCompat.getColor(context, R.color.mobile_text_secondary))
                setBackgroundResource(if (isSelected) R.drawable.bg_chip_selected else R.drawable.bg_chip_unselected)
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
        val marginPx = (6 * dp).toInt()

        for (option in EDGE_TYPE_OPTIONS) {
            val edgeVal = option.first
            val labelRes = option.second
            val isSelected = edgeVal == currentConfig.edgeType

            val chip = TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, (34 * dp).toInt()
                ).apply {
                    marginEnd = marginPx
                }
                setPadding((12 * dp).toInt(), 0, (12 * dp).toInt(), 0)
                gravity = Gravity.CENTER
                text = context.getString(labelRes)
                textSize = 12f
                setTextColor(if (isSelected) Color.WHITE else ContextCompat.getColor(context, R.color.mobile_text_secondary))
                setBackgroundResource(if (isSelected) R.drawable.bg_chip_selected else R.drawable.bg_chip_unselected)
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
