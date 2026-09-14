package za.kilowatch.ultimatefilemanager.settings

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Outline
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.storage.ViewModeManager
import za.kilowatch.ultimatefilemanager.storage.ViewModeManager.ViewMode
import za.kilowatch.ultimatefilemanager.ui.NumberStepperView
import za.kilowatch.ultimatefilemanager.util.FileTypeIconProvider
import za.kilowatch.ultimatefilemanager.util.ThemeColors

/**
 * Mobile configuration screen for customizing individual List or Grid density presets.
 * Displays an interactive live preview card using the real row layout that updates in real-time.
 */
class ViewStyleDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_VIEW_MODE = "extra_view_mode"
    }

    private lateinit var targetMode: ViewMode
    private var isGridPreset: Boolean = false

    private lateinit var savedListStyle: ListViewStyle
    private lateinit var currentListStyle: ListViewStyle

    private lateinit var savedGridStyle: GridViewStyle
    private lateinit var currentGridStyle: GridViewStyle

    // Live preview references
    private lateinit var containerListPreview: FrameLayout
    private lateinit var containerGridPreview: LinearLayout
    private lateinit var txtPreviewDimensions: TextView

    // Steppers (List)
    private lateinit var layoutListSteppers: View
    private lateinit var stepperThumbSize: NumberStepperView
    private lateinit var stepperPadH: NumberStepperView
    private lateinit var stepperPadV: NumberStepperView
    private lateinit var stepperSpacing: NumberStepperView
    private lateinit var stepperTextPri: NumberStepperView
    private lateinit var stepperTextSec: NumberStepperView
    private lateinit var stepperCornerRadius: NumberStepperView

    // Steppers (Grid)
    private lateinit var layoutGridSteppers: View
    private lateinit var stepperGridColWidth: NumberStepperView
    private lateinit var stepperGridMargin: NumberStepperView
    private lateinit var stepperGridCorner: NumberStepperView
    private lateinit var stepperGridTextPri: NumberStepperView
    private lateinit var stepperGridIconPadding: NumberStepperView

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_view_style_detail)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val modeName = intent.getStringExtra(EXTRA_VIEW_MODE) ?: ViewMode.LIST_MEDIUM.name
        targetMode = try {
            ViewMode.valueOf(modeName)
        } catch (_: Exception) {
            ViewMode.LIST_MEDIUM
        }
        isGridPreset = ViewModeManager.isGrid(targetMode)

        initViews()
        setupToolbar()
        setupPreview()
        setupSteppers()
        setupActions()
    }

    private fun initViews() {
        containerListPreview = findViewById(R.id.containerListPreview)
        containerGridPreview = findViewById(R.id.containerGridPreview)
        txtPreviewDimensions = findViewById(R.id.txtPreviewDimensions)

        layoutListSteppers = findViewById(R.id.layoutListSteppers)
        stepperThumbSize = findViewById(R.id.stepperThumbSize)
        stepperPadH = findViewById(R.id.stepperPadH)
        stepperPadV = findViewById(R.id.stepperPadV)
        stepperSpacing = findViewById(R.id.stepperSpacing)
        stepperTextPri = findViewById(R.id.stepperTextPri)
        stepperTextSec = findViewById(R.id.stepperTextSec)
        stepperCornerRadius = findViewById(R.id.stepperCornerRadius)

        layoutGridSteppers = findViewById(R.id.layoutGridSteppers)
        stepperGridColWidth = findViewById(R.id.stepperGridColWidth)
        stepperGridMargin = findViewById(R.id.stepperGridMargin)
        stepperGridCorner = findViewById(R.id.stepperGridCorner)
        stepperGridTextPri = findViewById(R.id.stepperGridTextPri)
        stepperGridIconPadding = findViewById(R.id.stepperGridIconPadding)
    }

    private fun setupToolbar() {
        val presetName = when (targetMode) {
            ViewMode.LIST_SMALL -> "List: Small"
            ViewMode.LIST_MEDIUM -> "List: Medium"
            ViewMode.LIST_LARGE -> "List: Large"
            ViewMode.LIST_XLARGE -> "List: Extra Large"
            ViewMode.GRID_SMALL -> "Grid: Small"
            ViewMode.GRID_MEDIUM -> "Grid: Medium"
            ViewMode.GRID_LARGE -> "Grid: Large"
        }

        findViewById<TextView>(R.id.txtToolbarTitle).text = getString(R.string.view_customize_title, presetName)
        findViewById<View>(R.id.btnBack).setOnClickListener { handleBack() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBack()
            }
        })

        findViewById<MaterialButton>(R.id.btnSave).setOnClickListener {
            saveChanges()
        }
    }

    private fun setupPreview() {
        if (isGridPreset) {
            containerListPreview.visibility = View.GONE
            containerGridPreview.visibility = View.VISIBLE
            layoutListSteppers.visibility = View.GONE
            layoutGridSteppers.visibility = View.VISIBLE
        } else {
            containerListPreview.visibility = View.VISIBLE
            containerGridPreview.visibility = View.GONE
            layoutListSteppers.visibility = View.VISIBLE
            layoutGridSteppers.visibility = View.GONE

            // Bind mock data into list layout using the generic thumbnail image
            val listView = containerListPreview.findViewById<View>(R.id.previewItemFile)
            listView.findViewById<TextView>(R.id.txtFileName)?.text = getString(R.string.live_preview_sample_filename)
            listView.findViewById<TextView>(R.id.txtFileInfo)?.text = getString(R.string.live_preview_sample_info)
            listView.findViewById<TextView>(R.id.txtFileSize)?.text = getString(R.string.live_preview_sample_size)
            val iconContainer = listView.findViewById<View>(R.id.iconContainer)
            iconContainer?.setBackgroundResource(0)
            val img = listView.findViewById<ImageView>(R.id.imgFileIcon)
            img?.setImageResource(R.drawable.img_preview_sample)
            img?.imageTintList = null
            img?.setPadding(0, 0, 0, 0)
            img?.scaleType = ImageView.ScaleType.CENTER_CROP
        }
    }

    private fun setupSteppers() {
        if (isGridPreset) {
            savedGridStyle = ViewStyleManager.getGridStyle(this, targetMode)
            currentGridStyle = savedGridStyle.copy()

            stepperGridColWidth.configure(
                title = getString(R.string.stepper_grid_col_width),
                subtitle = getString(R.string.stepper_grid_col_width_desc),
                min = 70, max = 250, step = 5, unit = "dp",
                initialValue = currentGridStyle.targetWidthDp
            ) { value ->
                currentGridStyle = currentGridStyle.copy(targetWidthDp = value)
                updateGridLivePreview()
            }

            stepperGridMargin.configure(
                title = getString(R.string.stepper_card_margin),
                subtitle = getString(R.string.stepper_card_margin_desc),
                min = 2, max = 16, step = 1, unit = "dp",
                initialValue = currentGridStyle.cardMarginDp
            ) { value ->
                currentGridStyle = currentGridStyle.copy(cardMarginDp = value)
                updateGridLivePreview()
            }

            stepperGridCorner.configure(
                title = getString(R.string.stepper_card_corner_radius),
                subtitle = getString(R.string.stepper_card_corner_radius_desc),
                min = 0, max = 28, step = 1, unit = "dp",
                initialValue = currentGridStyle.cardCornerRadiusDp
            ) { value ->
                currentGridStyle = currentGridStyle.copy(cardCornerRadiusDp = value)
                updateGridLivePreview()
            }

            stepperGridTextPri.configure(
                title = getString(R.string.stepper_grid_caption_size),
                subtitle = getString(R.string.stepper_grid_caption_size_desc),
                min = 10, max = 18, step = 1, unit = "sp",
                initialValue = currentGridStyle.primaryTextSizeSp
            ) { value ->
                currentGridStyle = currentGridStyle.copy(primaryTextSizeSp = value)
                updateGridLivePreview()
            }

            stepperGridIconPadding.configure(
                title = getString(R.string.stepper_grid_icon_padding),
                subtitle = getString(R.string.stepper_grid_icon_padding_desc),
                min = 0, max = 16, step = 1, unit = "dp",
                initialValue = currentGridStyle.iconPaddingDp
            ) { value ->
                currentGridStyle = currentGridStyle.copy(iconPaddingDp = value)
                updateGridLivePreview()
            }

            updateGridLivePreview()
        } else {
            savedListStyle = ViewStyleManager.getListStyle(this, targetMode)
            currentListStyle = savedListStyle.copy()

            stepperThumbSize.configure(
                title = getString(R.string.stepper_thumbnail_size),
                subtitle = getString(R.string.stepper_thumbnail_size_desc),
                min = 24, max = 96, step = 4, unit = "dp",
                initialValue = currentListStyle.thumbnailSizeDp
            ) { value ->
                currentListStyle = currentListStyle.copy(thumbnailSizeDp = value)
                updateListLivePreview()
            }

            stepperPadH.configure(
                title = getString(R.string.stepper_horizontal_padding),
                subtitle = getString(R.string.stepper_horizontal_padding_desc),
                min = 4, max = 32, step = 2, unit = "dp",
                initialValue = currentListStyle.itemPaddingHorizontalDp
            ) { value ->
                currentListStyle = currentListStyle.copy(itemPaddingHorizontalDp = value)
                updateListLivePreview()
            }

            stepperPadV.configure(
                title = getString(R.string.stepper_vertical_padding),
                subtitle = getString(R.string.stepper_vertical_padding_desc),
                min = 2, max = 24, step = 1, unit = "dp",
                initialValue = currentListStyle.itemPaddingVerticalDp
            ) { value ->
                currentListStyle = currentListStyle.copy(itemPaddingVerticalDp = value)
                updateListLivePreview()
            }

            stepperSpacing.configure(
                title = getString(R.string.stepper_row_spacing),
                subtitle = getString(R.string.stepper_row_spacing_desc),
                min = 0, max = 16, step = 1, unit = "dp",
                initialValue = currentListStyle.itemSpacingDp
            ) { value ->
                currentListStyle = currentListStyle.copy(itemSpacingDp = value)
                updateListLivePreview()
            }

            stepperTextPri.configure(
                title = getString(R.string.stepper_text_primary),
                subtitle = getString(R.string.stepper_text_primary_desc),
                min = 11, max = 22, step = 1, unit = "sp",
                initialValue = currentListStyle.primaryTextSizeSp
            ) { value ->
                currentListStyle = currentListStyle.copy(primaryTextSizeSp = value)
                updateListLivePreview()
            }

            stepperTextSec.configure(
                title = getString(R.string.stepper_text_secondary),
                subtitle = getString(R.string.stepper_text_secondary_desc),
                min = 9, max = 18, step = 1, unit = "sp",
                initialValue = currentListStyle.secondaryTextSizeSp
            ) { value ->
                currentListStyle = currentListStyle.copy(secondaryTextSizeSp = value)
                updateListLivePreview()
            }

            stepperCornerRadius.configure(
                title = getString(R.string.stepper_icon_corner_radius),
                subtitle = getString(R.string.stepper_icon_corner_radius_desc),
                min = 0, max = 32, step = 1, unit = "dp",
                initialValue = currentListStyle.iconCornerRadiusDp
            ) { value ->
                currentListStyle = currentListStyle.copy(iconCornerRadiusDp = value)
                updateListLivePreview()
            }

            updateListLivePreview()
        }
    }

    private fun setupActions() {
        findViewById<MaterialButton>(R.id.btnResetDefault).setOnClickListener {
            val presetName = targetMode.name.replace("LIST_", "List ").replace("GRID_", "Grid ")
            MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
                .setTitle(R.string.view_reset_confirm_title)
                .setMessage(getString(R.string.view_reset_confirm_msg, presetName))
                .setPositiveButton(R.string.view_reset_to_default) { dialog, _ ->
                    dialog.dismiss()
                    resetToDefaults()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun updateListLivePreview() {
        val density = resources.displayMetrics.density
        val preview = containerListPreview.findViewById<View>(R.id.previewItemFile) ?: return
        val layoutRow = preview.findViewById<View>(R.id.layoutFileRow)
        val iconContainer = preview.findViewById<View>(R.id.iconContainer)
        val imgIcon = preview.findViewById<ImageView>(R.id.imgFileIcon)
        val txtName = preview.findViewById<TextView>(R.id.txtFileName)
        val txtInfo = preview.findViewById<TextView>(R.id.txtFileInfo)
        val txtSize = preview.findViewById<TextView>(R.id.txtFileSize)

        // Clear the circular accent background from item_file.xml so thumbnail shows true shape
        iconContainer?.setBackgroundResource(0)

        // Thumbnail dimensions & corner radius
        val sizePx = (currentListStyle.thumbnailSizeDp * density + 0.5f).toInt()
        val radiusPx = currentListStyle.iconCornerRadiusDp * density

        iconContainer?.let { container ->
            val lp = container.layoutParams
            lp.width = sizePx
            lp.height = sizePx
            container.layoutParams = lp

            if (radiusPx <= 0.5f) {
                container.clipToOutline = false
                container.outlineProvider = null
            } else {
                container.clipToOutline = true
                container.outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, radiusPx)
                    }
                }
                container.invalidateOutline()
            }
        }

        // Clip the thumbnail image itself with the dynamic corner radius
        imgIcon?.let { img ->
            img.setPadding(0, 0, 0, 0)
            img.imageTintList = null
            img.scaleType = ImageView.ScaleType.CENTER_CROP

            if (radiusPx <= 0.5f) {
                img.clipToOutline = false
                img.outlineProvider = null
            } else {
                img.clipToOutline = true
                img.outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, radiusPx)
                    }
                }
                img.invalidateOutline()
            }
        }

        // Row padding
        val padHPx = (currentListStyle.itemPaddingHorizontalDp * density + 0.5f).toInt()
        val padVPx = (currentListStyle.itemPaddingVerticalDp * density + 0.5f).toInt()
        layoutRow?.setPadding(padHPx, padVPx, padHPx, padVPx)

        // Text sizes
        txtName?.textSize = currentListStyle.primaryTextSizeSp.toFloat()
        txtInfo?.textSize = currentListStyle.secondaryTextSizeSp.toFloat()
        txtSize?.textSize = currentListStyle.secondaryTextSizeSp.toFloat()

        // Row Spacing (gap preview via bottom margin)
        val spacingPx = (currentListStyle.itemSpacingDp * density + 0.5f).toInt()
        val cardLp = containerListPreview.layoutParams as? ViewGroup.MarginLayoutParams
        if (cardLp != null) {
            cardLp.bottomMargin = spacingPx
            containerListPreview.layoutParams = cardLp
        }

        txtPreviewDimensions.text = "${currentListStyle.thumbnailSizeDp} dp icon · ${currentListStyle.primaryTextSizeSp} sp text"
    }

    private data class GridPreviewSample(
        val name: String,
        val iconRes: Int,
        val isImage: Boolean
    )

    private fun updateGridLivePreview() {
        val density = resources.displayMetrics.density
        val widthDp = resources.configuration.screenWidthDp
        val targetDp = currentGridStyle.targetWidthDp.toFloat()
        val spanCount = if (widthDp > 0 && targetDp > 0) {
            (widthDp / targetDp).let { kotlin.math.round(it).toInt() }.coerceIn(1, 8)
        } else {
            3
        }

        txtPreviewDimensions.text = "$spanCount columns · ${currentGridStyle.targetWidthDp} dp target · ${currentGridStyle.cardMarginDp} dp margin"

        val samples = listOf(
            GridPreviewSample("Photo.jpg", R.drawable.img_preview_sample, isImage = true),
            GridPreviewSample("Docs", R.drawable.ic_folder, isImage = false),
            GridPreviewSample("Audio.mp3", FileTypeIconProvider.iconForExtension(this, "mp3"), isImage = false),
            GridPreviewSample("Video.mp4", FileTypeIconProvider.iconForExtension(this, "mp4"), isImage = false),
            GridPreviewSample("Archive.zip", FileTypeIconProvider.iconForExtension(this, "zip"), isImage = false),
            GridPreviewSample("Notes.txt", FileTypeIconProvider.iconForExtension(this, "txt"), isImage = false),
            GridPreviewSample("App.apk", FileTypeIconProvider.iconForExtension(this, "apk"), isImage = false),
            GridPreviewSample("Data.csv", FileTypeIconProvider.iconForExtension(this, "csv"), isImage = false)
        )

        // Ensure container has exactly spanCount children
        while (containerGridPreview.childCount < spanCount) {
            val view = layoutInflater.inflate(R.layout.item_file_grid, containerGridPreview, false)
            containerGridPreview.addView(view)
        }
        while (containerGridPreview.childCount > spanCount) {
            containerGridPreview.removeViewAt(containerGridPreview.childCount - 1)
        }

        val marginPx = (currentGridStyle.cardMarginDp * density + 0.5f).toInt()
        val radiusPx = currentGridStyle.cardCornerRadiusDp * density
        val iconPadPx = (currentGridStyle.iconPaddingDp * density + 0.5f).toInt()
        val mobileTint = DefaultIconColorManager.getMobileIconTint(this)

        for (i in 0 until spanCount) {
            val card = containerGridPreview.getChildAt(i) as? MaterialCardView ?: continue
            val sample = samples[i % samples.size]

            if (spanCount == 1) {
                val singleWidthPx = (minOf(currentGridStyle.targetWidthDp, 220) * density + 0.5f).toInt()
                val lp = LinearLayout.LayoutParams(singleWidthPx, singleWidthPx).apply {
                    setMargins(marginPx, marginPx, marginPx, marginPx)
                }
                card.layoutParams = lp
            } else {
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f).apply {
                    setMargins(marginPx, marginPx, marginPx, marginPx)
                }
                card.layoutParams = lp
            }

            if (radiusPx <= 0.5f) {
                card.radius = 0f
                card.clipToOutline = false
                card.outlineProvider = null
            } else {
                card.radius = radiusPx
                card.clipToOutline = true
                card.outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, radiusPx)
                    }
                }
                card.invalidateOutline()
            }

            val iconContainer = card.findViewById<View>(R.id.iconContainer)
            iconContainer?.setBackgroundResource(0)

            val txtName = card.findViewById<TextView>(R.id.txtFileName)
            txtName?.text = sample.name
            txtName?.textSize = currentGridStyle.primaryTextSizeSp.toFloat()

            val imgIcon = card.findViewById<ImageView>(R.id.imgFileIcon)
            imgIcon?.clipToOutline = false
            imgIcon?.outlineProvider = null

            if (sample.isImage) {
                imgIcon?.setImageResource(sample.iconRes)
                imgIcon?.imageTintList = null
                imgIcon?.scaleType = ImageView.ScaleType.CENTER_CROP
                imgIcon?.setPadding(0, 0, 0, 0)
            } else {
                imgIcon?.setImageResource(sample.iconRes)
                imgIcon?.imageTintList = ColorStateList.valueOf(mobileTint)
                imgIcon?.scaleType = ImageView.ScaleType.FIT_CENTER
                imgIcon?.setPadding(iconPadPx, iconPadPx, iconPadPx, iconPadPx)
            }
        }
    }

    private fun resetToDefaults() {
        if (isGridPreset) {
            currentGridStyle = GridViewStyle.getDefault(targetMode)
            stepperGridColWidth.setValue(currentGridStyle.targetWidthDp)
            stepperGridMargin.setValue(currentGridStyle.cardMarginDp)
            stepperGridCorner.setValue(currentGridStyle.cardCornerRadiusDp)
            stepperGridTextPri.setValue(currentGridStyle.primaryTextSizeSp)
            stepperGridIconPadding.setValue(currentGridStyle.iconPaddingDp)
            updateGridLivePreview()
        } else {
            currentListStyle = ListViewStyle.getDefault(targetMode)
            stepperThumbSize.setValue(currentListStyle.thumbnailSizeDp)
            stepperPadH.setValue(currentListStyle.itemPaddingHorizontalDp)
            stepperPadV.setValue(currentListStyle.itemPaddingVerticalDp)
            stepperSpacing.setValue(currentListStyle.itemSpacingDp)
            stepperTextPri.setValue(currentListStyle.primaryTextSizeSp)
            stepperTextSec.setValue(currentListStyle.secondaryTextSizeSp)
            stepperCornerRadius.setValue(currentListStyle.iconCornerRadiusDp)
            updateListLivePreview()
        }
    }

    private fun saveChanges() {
        if (isGridPreset) {
            ViewStyleManager.saveGridStyle(this, targetMode, currentGridStyle)
            savedGridStyle = currentGridStyle.copy()
        } else {
            ViewStyleManager.saveListStyle(this, targetMode, currentListStyle)
            savedListStyle = currentListStyle.copy()
        }

        val modeLabel = when (targetMode) {
            ViewMode.LIST_SMALL -> "List Small"
            ViewMode.LIST_MEDIUM -> "List Medium"
            ViewMode.LIST_LARGE -> "List Large"
            ViewMode.LIST_XLARGE -> "List Extra Large"
            ViewMode.GRID_SMALL -> "Grid Small"
            ViewMode.GRID_MEDIUM -> "Grid Medium"
            ViewMode.GRID_LARGE -> "Grid Large"
        }
        Toast.makeText(this, getString(R.string.view_saved_toast, modeLabel), Toast.LENGTH_SHORT).show()
        setResult(RESULT_OK)
        finish()
    }

    private fun hasUnsavedChanges(): Boolean {
        return if (isGridPreset) {
            currentGridStyle != savedGridStyle
        } else {
            currentListStyle != savedListStyle
        }
    }

    private fun handleBack() {
        if (hasUnsavedChanges()) {
            MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
                .setTitle(R.string.view_discard_confirm_title)
                .setMessage(R.string.view_discard_confirm_msg)
                .setPositiveButton(R.string.remote_btn_yes_delete) { dialog, _ ->
                    dialog.dismiss()
                    finish()
                }
                .setNegativeButton(R.string.remote_btn_no_cancel, null)
                .show()
        } else {
            finish()
        }
    }
}
