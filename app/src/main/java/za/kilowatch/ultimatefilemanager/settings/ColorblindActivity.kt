package za.kilowatch.ultimatefilemanager.settings

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import com.google.android.material.card.MaterialCardView
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.UfmApplication
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import za.kilowatch.ultimatefilemanager.util.ThemeColors
import za.kilowatch.ultimatefilemanager.util.TvFocusHelper

/**
 * Colorblind Accessibility Mode settings screen (FR-03, FR-04, FR-15, FR-16).
 *
 * Two independent choices — **type** (which palette) and **strength** (how hard
 * it pushes contrast) — plus a preview card that shows what the current pair
 * actually produces.
 *
 * ## Why the preview is built from the resolved palette, not from the resources
 *
 * The preview has to show the colours the app will *actually* use, on the platform
 * the user is holding, in the appearance they are in. Reading
 * `R.color.ufm_cb_*` directly would ignore the day/night split; reading
 * `?attr/ufmFocusFill` would show the applied type but could not show the
 * *unapplied* ones. Both are needed: the preview reads [ColorblindPalette] for
 * "what I have now", and [TYPE_FILL_RES] for "what that other option would give
 * me" — the swatch on each option row.
 *
 * ## Why changing a setting recreates every Activity
 *
 * `@color/` references resolve during inflation and cannot be swapped afterwards,
 * so the whole feature is expressed as a theme overlay. A theme overlay is applied
 * to an Activity's theme, and an Activity's theme is read at `setContentView` time.
 * There is therefore no way to change the palette of an already-inflated view
 * tree; the only correct response to a change is to rebuild every visible screen.
 * [UfmApplication.recreateAllActivities] does that, and — because `apply()` writes
 * the in-memory preference map synchronously before returning — each recreated
 * Activity reads the new value on its first pass. See [ColorblindPrefs] for why
 * that ordering is load-bearing and not incidental.
 *
 * ## Focus survives the recreate
 *
 * Recreating on every tap is a visible flash, and losing the user's place inside
 * one makes the screen feel broken: a D-pad user changing strength three times
 * would be thrown back to the first card each time. Both the scroll offset and the
 * identity of the focused option are saved and restored, so the row the user is
 * standing on is the row they are standing on afterwards (Q13).
 */
class ColorblindActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_SAVED_SCROLL_Y = "extra_saved_scroll_y"
        private const val EXTRA_FOCUSED_KEY = "extra_focused_key"

        /**
         * Strength rows are keyed at 1000+, type rows at 0-3, so the two groups can
         * share one focus-restore lookup without an ambiguity. The keys are stored
         * in `View.tag`, which is why they must not collide — a raw 0-6 range for
         * both groups would make "Off" and "Low" the same restore target.
         */
        private const val KEY_STRENGTH_BASE = 1000

        private const val GROUP_TYPE = 0
        private const val GROUP_STRENGTH = 1
    }

    private var isTv = false
    private lateinit var contentLayout: LinearLayout

    /** Every option row/card, for selection and enablement updates. */
    private val optionViews = mutableListOf<OptionView>()

    /** The preview's status glyphs, repainted on every change. */
    private val statusGlyphs = mutableListOf<Pair<ColorblindPalette.StatusKind, ImageView>>()

    private var previewTiles: List<PreviewTile> = emptyList()

    private class OptionView(
        val key: Int,
        val group: Int,
        val item: View,
        val radio: RadioButton,
        val swatch: View,
        val title: TextView,
        val subtitle: TextView,
    )

    private class PreviewTile(
        val card: MaterialCardView,
        val label: TextView,
    )

    /**
     * Focus-fill resource per `[type][strength]`, for the option swatches.
     *
     * An explicit table rather than `getIdentifier("ufm_cb_focus_fill_" + …)`.
     * Reflective lookup cannot be checked at compile time, silently returns 0 for a
     * typo, and R8 cannot see through it — and these names are *generated*, so a
     * template change could rename them without anything here noticing. Row 0 is
     * the Off type, which has no palette colour of its own: it falls back to
     * [ColorblindPalette.focusFill], i.e. exactly what the app uses today.
     */
    private val typeFillRes: Array<IntArray> = arrayOf(
        intArrayOf(0, 0, 0),
        intArrayOf(
            R.color.ufm_cb_focus_fill_redgreen_low,
            R.color.ufm_cb_focus_fill_redgreen_medium,
            R.color.ufm_cb_focus_fill_redgreen_high,
        ),
        intArrayOf(
            R.color.ufm_cb_focus_fill_blueyellow_low,
            R.color.ufm_cb_focus_fill_blueyellow_medium,
            R.color.ufm_cb_focus_fill_blueyellow_high,
        ),
        intArrayOf(
            R.color.ufm_cb_focus_fill_highcontrast_low,
            R.color.ufm_cb_focus_fill_highcontrast_medium,
            R.color.ufm_cb_focus_fill_highcontrast_high,
        ),
    )

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        isTv = DeviceUtils.isTvDevice(this)
        setContentView(if (isTv) R.layout.activity_colorblind_tv else R.layout.activity_colorblind)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val tvPad = if (isTv) (27 * resources.displayMetrics.density).toInt() else 0
            v.setPadding(
                systemBars.left + tvPad, systemBars.top + tvPad,
                systemBars.right + tvPad, systemBars.bottom + tvPad
            )
            insets
        }

        contentLayout = findViewById(R.id.contentLayout)

        findViewById<ImageView?>(R.id.btnBack)?.let { back ->
            if (isTv) {
                val white = ColorStateList.valueOf(getColor(R.color.tv_text_primary))
                val black = ColorStateList.valueOf(ColorblindPalette.focusFillText(this))
                back.imageTintList = white
                back.setOnFocusChangeListener { _, hasFocus ->
                    back.imageTintList = if (hasFocus) black else white
                }
            }
            back.setOnClickListener { finish() }
        }

        buildLayout()
        updateSelection()

        // Restore the user's place across the recreate that applying a setting
        // triggers. Read from the saved state first, then the intent, then nothing.
        val savedScrollY = savedInstanceState?.getInt(EXTRA_SAVED_SCROLL_Y, 0)
            ?: intent.getIntExtra(EXTRA_SAVED_SCROLL_Y, 0)
        val savedKey = savedInstanceState?.getInt(EXTRA_FOCUSED_KEY, Int.MIN_VALUE)
            ?: intent.getIntExtra(EXTRA_FOCUSED_KEY, Int.MIN_VALUE)

        val scrollView = findViewById<NestedScrollView?>(R.id.scrollView)
        if (savedScrollY > 0) {
            scrollView?.post { scrollView.scrollTo(0, savedScrollY) }
        }

        // Focus has to be requested after layout, or the view is not yet focusable
        // and the request is dropped silently. On TV there is no touch to recover
        // with, so a dropped request leaves the user with no selection at all.
        if (isTv) {
            contentLayout.post {
                val target = if (savedKey != Int.MIN_VALUE) {
                    findViewWithKey(savedKey)
                } else {
                    null
                } ?: findViewWithKey(typeKey(ColorblindPrefs.getType(this)))
                target?.requestFocus()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        findViewById<NestedScrollView?>(R.id.scrollView)?.let {
            outState.putInt(EXTRA_SAVED_SCROLL_Y, it.scrollY)
        }
        val focused = optionViews.firstOrNull { it.item.hasFocus() }
            ?: optionViews.firstOrNull { (it.item as? MaterialCardView)?.isChecked == true }
        outState.putInt(EXTRA_FOCUSED_KEY, focused?.key ?: Int.MIN_VALUE)
    }

    // ── Layout ──────────────────────────────────────────────────────────────

    private fun buildLayout() {
        val inflater = LayoutInflater.from(this)

        addGroupHeader(R.string.colorblind_group_type)
        val typeCard = addGlassCard()
        for (type in 0 until ColorblindPrefs.TYPE_COUNT) {
            addOptionRow(
                inflater, typeCard, GROUP_TYPE, typeKey(type), type,
                typeTitle(type), typeDescription(type),
            )
        }

        addGroupHeader(R.string.colorblind_group_strength)
        addGroupHint(R.string.colorblind_group_strength_hint)
        val strengthCard = addGlassCard()
        for (strength in 0 until ColorblindPrefs.STRENGTH_COUNT) {
            addOptionRow(
                inflater, strengthCard, GROUP_STRENGTH, strengthKey(strength), strength,
                strengthTitle(strength), strengthDescription(strength),
            )
        }

        addGroupHeader(R.string.colorblind_preview_title)
        addGroupHint(R.string.colorblind_preview_hint)
        addPreviewCard()
    }

    /**
     * Inflate and bind one option row, in whichever form this form factor uses.
     *
     * Mobile and TV differ in more than the layout id: the TV card needs a focus
     * listener, a resting-fill tag and a stroke, and the mobile row gets none of
     * that because there is no D-pad focus on a touch screen. Splitting here keeps
     * the shared part — text, swatch, radio, tag, click — in one place.
     */
    private fun addOptionRow(
        inflater: LayoutInflater,
        parent: LinearLayout,
        group: Int,
        key: Int,
        value: Int,
        titleRes: Int,
        descriptionRes: Int,
    ) {
        if (isTv) {
            val card = inflater.inflate(
                R.layout.item_colorblind_option_card_tv, parent, false
            ) as MaterialCardView
            val radio = card.findViewById<RadioButton>(R.id.rbSelect)
            val swatch = card.findViewById<View>(R.id.viewSwatch)
            val title = card.findViewById<TextView>(R.id.txtLabel)
            val subtitle = card.findViewById<TextView>(R.id.txtSubtitle)
            bindRow(key, group, card, radio, swatch, title, subtitle, titleRes, descriptionRes)

            // Blur reads this, not `card.tag`: the tag holds the option key, and an
            // Int option key is a valid Int colour.
            TvFocusHelper.setRestingFill(card, getColor(R.color.tv_glass_white_10))
            setupTvCardFocus(card, radio, title, subtitle)

            parent.addView(card)
        } else {
            val row = inflater.inflate(R.layout.item_colorblind_option_row, parent, false)
            val radio = row.findViewById<RadioButton>(R.id.rbSelect)
            val swatch = row.findViewById<View>(R.id.viewSwatch)
            val title = row.findViewById<TextView>(R.id.txtTitle)
            val subtitle = row.findViewById<TextView>(R.id.txtSubtitle)
            bindRow(key, group, row, radio, swatch, title, subtitle, titleRes, descriptionRes)
            parent.addView(row)
        }
    }

    private fun bindRow(
        key: Int,
        group: Int,
        item: View,
        radio: RadioButton,
        swatch: View,
        title: TextView,
        subtitle: TextView,
        titleRes: Int,
        descriptionRes: Int,
    ) {
        title.setText(titleRes)
        subtitle.setText(descriptionRes)
        item.tag = key
        item.setOnClickListener { onOptionChosen(group, key) }
        optionViews.add(OptionView(key, group, item, radio, swatch, title, subtitle))
    }

    private fun setupTvCardFocus(
        card: MaterialCardView,
        radio: RadioButton,
        title: TextView,
        subtitle: TextView,
    ) {
        // Resolved once, outside the listener: every accessor is a
        // theme.resolveAttribute call and this fires on every D-pad move.
        val colors = TvFocusHelper.colors(this)

        card.setOnFocusChangeListener { _, hasFocus ->
            TvFocusHelper.applyCardFocus(card, colors, hasFocus)

            // The shared helper covers fill, marker and outline. Text and the radio
            // button are this screen's own extra behaviour, so they stay here.
            if (hasFocus) {
                title.setTextColor(colors.onFill)
                subtitle.setTextColor(colors.onFill)
                radio.buttonTintList = ColorStateList.valueOf(colors.onFill)
            } else {
                title.setTextColor(getColor(R.color.tv_text_primary))
                subtitle.setTextColor(getColor(R.color.tv_text_secondary))
                radio.buttonTintList = ColorStateList.valueOf(selectedAccent())
            }
        }
    }

    /** Accent for a selected-but-unfocused radio: the palette's selection colour. */
    private fun selectedAccent(): Int =
        if (ColorblindPrefs.isEnabled(this)) ColorblindPalette.selectionStroke(this)
        else getColor(R.color.tv_accent)

    private fun addGroupHeader(titleRes: Int): TextView =
        TextView(this).apply {
            setText(titleRes)
            setTextColor(if (isTv) getColor(R.color.tv_accent) else ThemeColors.primary(this@ColorblindActivity))
            textSize = if (isTv) 14f else 13f
            typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
            isAllCaps = true
            letterSpacing = 0.05f
            val d = resources.displayMetrics.density
            setPadding(
                (if (isTv) 12 * d else 4 * d).toInt(),
                (14 * d).toInt(),
                (4 * d).toInt(),
                (if (isTv) 10 * d else 8 * d).toInt()
            )
            contentLayout.addView(this)
        }

    private fun addGroupHint(textRes: Int): TextView =
        TextView(this).apply {
            setText(textRes)
            setTextColor(getColor(if (isTv) R.color.tv_text_secondary else R.color.mobile_text_secondary))
            textSize = if (isTv) 13f else 12f
            val d = resources.displayMetrics.density
            setPadding((if (isTv) 12 * d else 4 * d).toInt(), 0, (12 * d).toInt(), (8 * d).toInt())
            contentLayout.addView(this)
        }

    private fun addGlassCard(): LinearLayout {
        val d = resources.displayMetrics.density
        val holder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (12 * d).toInt() }
            radius = if (isTv) 20 * d else 16 * d
            strokeWidth = (if (isTv) 2 * d else 1 * d).toInt()
            strokeColor = getColor(if (isTv) R.color.tv_glass_border else R.color.mobile_glass_stroke)
            setCardBackgroundColor(getColor(if (isTv) R.color.tv_glass_white_10 else R.color.mobile_glass_card))
            cardElevation = 0f
        }
        val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(inner)
        holder.addView(card)
        contentLayout.addView(holder)
        return inner
    }

    // ── Preview (FR-15) ─────────────────────────────────────────────────────

    private fun addPreviewCard() {
        val container = addGlassCard()
        val d = resources.displayMetrics.density

        val tiles = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        previewTiles = listOf(
            addPreviewTile(tiles, R.string.colorblind_preview_unfocused, d),
            addPreviewTile(tiles, R.string.colorblind_preview_focused, d),
            addPreviewTile(tiles, R.string.colorblind_preview_selected, d),
        )
        container.addView(tiles)

        val statuses = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (14 * d).toInt() }
        }
        container.addView(statuses)
        for ((kind, labelRes) in listOf(
            ColorblindPalette.StatusKind.SUCCESS to R.string.colorblind_preview_success,
            ColorblindPalette.StatusKind.WARNING to R.string.colorblind_preview_warning,
            ColorblindPalette.StatusKind.ERROR to R.string.colorblind_preview_error,
        )) {
            statuses.addView(buildStatusSample(kind, labelRes, d))
        }
    }

    private fun addPreviewTile(parent: LinearLayout, labelRes: Int, d: Float): PreviewTile {
        val tile = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = (6 * d).toInt()
            }
            radius = 12 * d
            cardElevation = 0f
        }
        val label = TextView(this).apply {
            setText(labelRes)
            textSize = 11f
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding((6 * d).toInt(), (14 * d).toInt(), (6 * d).toInt(), (14 * d).toInt())
        }
        tile.addView(label)
        parent.addView(tile)
        return PreviewTile(tile, label)
    }

    /**
     * One status sample: the colour, plus the shape that carries the same meaning
     * without it.
     *
     * The glyph is shown **only while the mode is on**, because that is exactly what
     * the app does — `ufmStatus*Icon` defaults to a transparent no-op so that sites
     * already drawing their own status icon are not blanked out (FR-19). A preview
     * that always drew a glyph would be advertising behaviour the app does not have
     * with the mode off.
     */
    private fun buildStatusSample(
        kind: ColorblindPalette.StatusKind,
        labelRes: Int,
        d: Float,
    ): LinearLayout {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val glyph = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams((26 * d).toInt(), (26 * d).toInt(), Gravity.CENTER)
            contentDescription = getString(
                when (kind) {
                    ColorblindPalette.StatusKind.SUCCESS -> R.string.colorblind_status_success_desc
                    ColorblindPalette.StatusKind.WARNING -> R.string.colorblind_status_warning_desc
                    ColorblindPalette.StatusKind.ERROR -> R.string.colorblind_status_error_desc
                }
            )
        }
        val circle = View(this).apply {
            layoutParams = FrameLayout.LayoutParams((26 * d).toInt(), (26 * d).toInt(), Gravity.CENTER)
        }
        val stack = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            addView(circle)
            addView(glyph)
        }
        column.addView(stack)
        column.addView(TextView(this).apply {
            setText(labelRes)
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(getColor(if (isTv) R.color.tv_text_secondary else R.color.mobile_text_secondary))
            setPadding(0, (4 * d).toInt(), 0, 0)
        })
        // The circle and the glyph are repainted together in updateSelection, from
        // the same palette read, so they cannot disagree.
        statusGlyphs.add(kind to glyph)
        glyph.tag = circle
        return column
    }

    // ── State ───────────────────────────────────────────────────────────────

    private fun onOptionChosen(group: Int, key: Int) {
        val value = if (group == GROUP_TYPE) key else key - KEY_STRENGTH_BASE
        val previous = if (group == GROUP_TYPE) ColorblindPrefs.getType(this)
        else ColorblindPrefs.getStrength(this)
        if (value == previous) return

        if (group == GROUP_TYPE) ColorblindPrefs.setType(this, value)
        else ColorblindPrefs.setStrength(this, value)

        // The icon colour cache is keyed on the theme, and the overlay this change
        // applies is a theme change. Without this, icons already resolved under the
        // old overlay keep their old tint for the life of the process.
        DefaultIconColorManager.invalidateCache()

        // Persist the new value into the intent as well as the saved state: the
        // recreate is posted to the main looper by recreateAllActivities, and the
        // intent extras are the one channel that is guaranteed to be readable on
        // the other side of it without depending on save/restore ordering.
        val scrollView = findViewById<NestedScrollView?>(R.id.scrollView)
        intent.putExtra(EXTRA_SAVED_SCROLL_Y, scrollView?.scrollY ?: 0)
        intent.putExtra(EXTRA_FOCUSED_KEY, key)

        // `instance.recreateAllActivities()`, not a static — it is an ordinary
        // member. Same call ThemeActivity makes after toggling Material You, which
        // is the closest analogue: both changes are a theme swap that only a
        // full re-inflation can apply.
        UfmApplication.instance.recreateAllActivities()
    }

    /**
     * Repaint every selection-dependent view from the saved preferences.
     *
     * Called once on create. It is *not* called on every tap — a tap persists the
     * value and recreates, and this runs again on the other side of the recreate
     * with the new value already stored. Repainting first and recreating after
     * would show the change twice, with a flash between.
     */
    private fun updateSelection() {
        val type = ColorblindPrefs.getType(this)
        val strength = ColorblindPrefs.getStrength(this)

        for (option in optionViews) {
            val isSelected = when (option.group) {
                GROUP_TYPE -> option.key == type
                else -> option.key - KEY_STRENGTH_BASE == strength
            }
            option.radio.isChecked = isSelected
            if (!isTv) {
                option.radio.buttonTintList = ColorStateList.valueOf(
                    if (isSelected) ThemeColors.primary(this)
                    else getColor(R.color.mobile_text_secondary)
                )
            } else if (!option.item.hasFocus()) {
                option.radio.buttonTintList = ColorStateList.valueOf(
                    if (isSelected) selectedAccent()
                    else getColor(R.color.tv_text_secondary)
                )
            }
            option.swatch.background = swatchFor(option.group, option.key, type, strength)

            // Strength has nothing to act on while the type is Off: it scales the
            // focus fill, and there is no fill to scale. Leaving the rows live would
            // offer three controls that provably do nothing, which is worse than
            // saying so. The stored strength is retained either way (FR-03), so
            // picking a type restores the user's last choice.
            val enabled = option.group != GROUP_STRENGTH || type != ColorblindPrefs.TYPE_OFF
            option.item.isEnabled = enabled
            option.item.isClickable = enabled
            option.item.isFocusable = enabled
            option.item.alpha = if (enabled) 1f else 0.45f
        }

        paintPreview(type, strength)
    }

    /**
     * The swatch behind one option row: the focus fill that option would produce.
     *
     * Type rows show their own palette at the strength in force; strength rows show
     * the selected type's fill at that tier, so the three tiers are directly
     * comparable against each other. The Off row and the strength rows have no
     * palette fill to show, so they fall back to the app's own focus colour — which
     * is the honest answer, and the rows are disabled anyway.
     *
     * ## The row decides, not the current type
     *
     * The two are not the same question, and conflating them crashed the screen.
     * Row 0 of [typeFillRes] is zeroed because the Off type has no palette of its
     * own, so `getColor(typeFillRes[0][…])` is `getColor(0)` — a
     * `Resources$NotFoundException`, not a wrong colour. Testing `type` instead of
     * the row left that call reachable exactly when the type was **not** Off, i.e.
     * on every relaunch after the user's first pick: `onCreate` → [updateSelection]
     * walks all eight rows, reaches the Off row, and dies. The screen was
     * unusable past its first tap.
     *
     * So the table is only consulted for a row that actually has an entry in it,
     * and the app default is read from `R.color.tv_button_focused_yellow` — the
     * resource [ColorblindPalette.focusFill] itself falls back to. That is the
     * value a row with no palette means in *both* states, whereas `focusFill`
     * would resolve through the overlay once a type is active and paint an "Off"
     * swatch in the colour the user is trying to switch away from.
     */
    private fun swatchFor(group: Int, key: Int, type: Int, strength: Int): Drawable {
        val isTypeRow = group == GROUP_TYPE
        val hasPaletteEntry = !isTypeRow || key != ColorblindPrefs.TYPE_OFF
        val fill: Int = if (type == ColorblindPrefs.TYPE_OFF || !hasPaletteEntry) {
            getColor(R.color.tv_button_focused_yellow)
        } else if (isTypeRow) {
            getColor(typeFillRes[key][strength])
        } else {
            getColor(typeFillRes[type][key - KEY_STRENGTH_BASE])
        }
        val d = resources.displayMetrics.density
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fill)
            // A hairline of the palette's stroke keeps a light fill visible against
            // the frosted container without altering the fill it is showing.
            setStroke((1 * d).toInt(), ColorblindPalette.focusStrokeColor(this@ColorblindActivity))
        }
    }

    private fun paintPreview(type: Int, strength: Int) {
        val focused = ColorblindPalette.focusFill(this)
        val onFill = ColorblindPalette.focusFillText(this)
        val selected = ColorblindPalette.selectionFill(this)
        val selectedStroke = ColorblindPalette.selectionStroke(this)
        val glass = getColor(if (isTv) R.color.tv_glass_white_10 else R.color.mobile_glass_card)
        val glassStroke = getColor(if (isTv) R.color.tv_glass_border else R.color.mobile_glass_stroke)
        val primaryText = getColor(if (isTv) R.color.tv_text_primary else R.color.mobile_card_text_primary)
        val d = resources.displayMetrics.density

        previewTiles.getOrNull(0)?.let {
            it.card.setCardBackgroundColor(glass)
            it.card.strokeWidth = (1 * d).toInt()
            it.card.strokeColor = glassStroke
            TvFocusHelper.applyMarker(it.card, hasFocus = false)
            it.label.setTextColor(primaryText)
        }
        previewTiles.getOrNull(1)?.let {
            it.card.setCardBackgroundColor(focused)
            it.card.strokeWidth = ColorblindPalette.focusStrokeWidth(this).toInt()
            it.card.strokeColor = ColorblindPalette.focusStrokeColor(this)
            // The FR-06 marker, shown where the user can see what it is before
            // meeting it on a TV card. Draws nothing while the mode is off.
            TvFocusHelper.applyMarker(it.card, hasFocus = true)
            it.label.setTextColor(onFill)
        }
        previewTiles.getOrNull(2)?.let {
            it.card.setCardBackgroundColor(selected)
            it.card.strokeWidth = (2 * d).toInt()
            it.card.strokeColor = selectedStroke
            TvFocusHelper.applyMarker(it.card, hasFocus = false)
            it.label.setTextColor(primaryText)
        }

        val enabled = type != ColorblindPrefs.TYPE_OFF
        for ((kind, glyph) in statusGlyphs) {
            val color = when (kind) {
                ColorblindPalette.StatusKind.SUCCESS -> ColorblindPalette.statusSuccess(this)
                ColorblindPalette.StatusKind.WARNING -> ColorblindPalette.statusWarning(this)
                ColorblindPalette.StatusKind.ERROR -> ColorblindPalette.statusError(this)
            }
            (glyph.tag as? View)?.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
            if (enabled) {
                glyph.setImageDrawable(ColorblindPalette.statusIcon(this, kind))
                glyph.visibility = View.VISIBLE
            } else {
                // Off: no glyph, because the app draws none either. Showing one here
                // would promise a shape cue the app does not provide in this state.
                glyph.setImageDrawable(null)
                glyph.visibility = View.GONE
            }
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun findViewWithKey(key: Int): View? =
        findViewById<ViewGroup?>(R.id.main)?.findViewWithTag(key)

    private fun typeKey(type: Int) = type
    private fun strengthKey(strength: Int) = KEY_STRENGTH_BASE + strength

    private fun typeTitle(type: Int) = when (type) {
        ColorblindPrefs.TYPE_RED_GREEN -> R.string.colorblind_type_red_green
        ColorblindPrefs.TYPE_BLUE_YELLOW -> R.string.colorblind_type_blue_yellow
        ColorblindPrefs.TYPE_HIGH_CONTRAST -> R.string.colorblind_type_high_contrast
        else -> R.string.colorblind_type_off
    }

    private fun typeDescription(type: Int) = when (type) {
        ColorblindPrefs.TYPE_RED_GREEN -> R.string.colorblind_type_red_green_desc
        ColorblindPrefs.TYPE_BLUE_YELLOW -> R.string.colorblind_type_blue_yellow_desc
        ColorblindPrefs.TYPE_HIGH_CONTRAST -> R.string.colorblind_type_high_contrast_desc
        else -> R.string.colorblind_type_off_desc
    }

    private fun strengthTitle(strength: Int) = when (strength) {
        ColorblindPrefs.STRENGTH_LOW -> R.string.colorblind_strength_low
        ColorblindPrefs.STRENGTH_HIGH -> R.string.colorblind_strength_high
        else -> R.string.colorblind_strength_medium
    }

    private fun strengthDescription(strength: Int) = when (strength) {
        ColorblindPrefs.STRENGTH_LOW -> R.string.colorblind_strength_low_desc
        ColorblindPrefs.STRENGTH_HIGH -> R.string.colorblind_strength_high_desc
        else -> R.string.colorblind_strength_medium_desc
    }
}
