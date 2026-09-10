package za.kilowatch.ultimatefilemanager.settings

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.TypedValue
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import za.kilowatch.ultimatefilemanager.R

/**
 * Resolves the Colorblind Accessibility Mode palette for the current theme.
 *
 * This is the single substitution target for the Kotlin half of the feature.
 * Roughly 120 call sites across adapters, dialogs, activities and view builders
 * currently pass a hardcoded `R.color.*` (or a raw `0xFF…` literal) to
 * `setTextColor` / `setBackgroundColor` / `setColorFilter`. Those reads happen
 * *after* inflation, so unlike XML `@color/` references they are not intercepted
 * by the theme overlay — every one of them must be redirected through here, or it
 * keeps painting the old colour while the mode is on.
 *
 * ## Why resolutions degrade instead of throwing
 *
 * Every accessor resolves a theme attribute and falls back to the *legacy colour
 * resource* if the attribute is missing. That is not defensive padding — it is
 * load-bearing for two real situations:
 *
 *  - **Non-Activity contexts.** `ContextThemeWrapper`s built with a non-app theme,
 *    and some dialog contexts, do not carry the UFM attribute set. The fallback
 *    keeps them rendering today's colours rather than throwing on a live screen.
 *  - **Ordering.** The overlay is applied inside `ThemeHelper.applyTheme()`,
 *    which runs before `super.onCreate()`. Anything that resolves a colour before
 *    that point gets the base default — which, by design, *is* today's colour.
 *
 * ## The context you pass matters
 *
 * Pass a **theme-carrying context** — the Activity, or a `ContextThemeWrapper`
 * over it. Passing the Application context resolves against the manifest's
 * default theme, so the overlay is silently absent and every accessor returns the
 * fallback. That failure mode looks exactly like "the feature does nothing",
 * which is why it is called out here rather than left to be rediscovered.
 *
 * @see ColorblindPrefs for the stored type/strength these attributes select.
 */
object ColorblindPalette {

    /** Which status glyph to resolve. See [statusIcon]. */
    enum class StatusKind { SUCCESS, WARNING, ERROR }

    // ── Focus (FR-05, FR-06, FR-07) ─────────────────────────────────────────

    /** Solid fill painted behind a focused element. */
    @ColorInt
    fun focusFill(context: Context): Int =
        color(context, R.attr.ufmFocusFill, R.color.tv_button_focused_yellow)

    /** Text and icon colour drawn ON [focusFill]. */
    @ColorInt
    fun focusFillText(context: Context): Int =
        color(context, R.attr.ufmFocusFillText, R.color.tv_button_focused_yellow_text)

    /** Outer focus bloom. */
    @ColorInt
    fun focusGlow(context: Context): Int =
        color(context, R.attr.ufmFocusGlow, R.color.tv_button_focused_yellow_glow)

    /** Focus outline colour. */
    @ColorInt
    fun focusStrokeColor(context: Context): Int =
        color(context, R.attr.ufmFocusStrokeColor, R.color.tv_focus_border_strong)

    /**
     * Focus outline weight, in **pixels**, for programmatically-focused cards.
     *
     * > **This is the only channel that delivers strength-scaled outline weight.**
     * > No existing drawable's `<stroke android:width>` is converted, because
     * > measured across the focus seam the widths are 1 / 1.5 / 2 / 3dp and
     * > `selector_tv_card.xml` uses 2dp/2dp/3dp as a deliberate graduated glow —
     * > a single attribute default cannot preserve those, and flattening them
     * > would erase the glow. Declarative selectors therefore get the marker and
     * > the recolour but not weight scaling; this path covers the ~45 cards that
     * > paint focus in code and have no stroke at all today.
     *
     * Resolved in Kotlin rather than through `android:width="?attr/…"`, a form no
     * shipping code in this project uses.
     */
    fun focusStrokeWidth(context: Context): Float =
        dimension(context, R.attr.ufmFocusStrokeWidth, FALLBACK_STROKE_DP)

    /** Card / colour-swatch focus accent — the "blue" family unified per FR-07. */
    @ColorInt
    fun focusAccent(context: Context): Int =
        color(context, R.attr.ufmFocusAccent, R.color.tv_accent)

    /**
     * The translucent accent surface behind a card that focuses into the **blue**
     * family rather than into [focusFill] — `@color/tv_surface_focused`, a
     * 20 %-alpha accent tint.
     *
     * Not a fill, so it is not [focusFill] under another name: `ImportDetailsAdapter`
     * paints it behind a category card on focus and pairs it with
     * [focusStrokeColor], which is the same treatment `selector_tv_card` gives
     * declaratively. The default is `tv_surface_focused` in both base themes, so
     * focusing such a card is appearance-neutral while the mode is Off.
     */
    @ColorInt
    fun focusAccentSurface(context: Context): Int =
        color(context, R.attr.ufmFocusAccentSurface, R.color.tv_surface_focused)

    /**
     * The FR-06 positional marker: a full-row-height leading-edge bar.
     *
     * > **Pass a theme-carrying context.** The drawable resolves
     * > `?attr/ufmFocusMarkerColor` internally, so a context without the applied
     * > overlay yields the base default — transparent, i.e. no visible marker.
     *
     * When the mode is Off, `ufmFocusMarkerColor` is `@android:color/transparent`
     * and the bar draws nothing, so this returns a valid-but-invisible drawable
     * rather than null. Callers that want to skip the work entirely should check
     * [ColorblindPrefs.isEnabled] first; callers that simply assign it to
     * `View.foreground` need no special case.
     */
    fun focusMarker(context: Context): Drawable? =
        ContextCompat.getDrawable(context, R.drawable.ufm_focus_marker_bar)

    // ── Selection (FR-08) ───────────────────────────────────────────────────

    /** Multi-select rows, active tab, active filter/sort chip. */
    @ColorInt
    fun selectionFill(context: Context): Int =
        color(context, R.attr.ufmSelectionFill, R.color.ufm_selection_highlight)

    /** Selected card/row border. */
    @ColorInt
    fun selectionStroke(context: Context): Int =
        color(context, R.attr.ufmSelectionStroke, R.color.ufm_selection_highlight)

    // ── Status and risk (FR-10) ─────────────────────────────────────────────

    @ColorInt
    fun statusSuccess(context: Context): Int =
        color(context, R.attr.ufmStatusSuccess, R.color.ufm_granted)

    @ColorInt
    fun statusWarning(context: Context): Int =
        color(context, R.attr.ufmStatusWarning, R.color.ufm_pending)

    @ColorInt
    fun statusError(context: Context): Int =
        color(context, R.attr.ufmStatusError, R.color.ufm_error)

    @ColorInt
    fun riskSafe(context: Context): Int =
        color(context, R.attr.ufmRiskSafe, R.color.ufm_risk_safe)

    @ColorInt
    fun riskModerate(context: Context): Int =
        color(context, R.attr.ufmRiskModerate, R.color.ufm_risk_moderate)

    @ColorInt
    fun riskManual(context: Context): Int =
        color(context, R.attr.ufmRiskManual, R.color.ufm_risk_manual)

    /**
     * The TV red — `tv_error_red` / `tv_error`, both `#FF5252`.
     *
     * Separate from [statusError] because they are **different colours**:
     * `ufm_error` is `#FFC62828` with no night override, while the TV red is a
     * lighter `#FF5252` on dark TV surfaces. Routing the TV sites through
     * [statusError] would repaint them with the mode **off** — see the T014 note
     * in `attrs.xml`.
     */
    @ColorInt
    fun tvErrorRed(context: Context): Int =
        color(context, R.attr.ufmTvErrorRed, R.color.tv_error_red)

    /**
     * Permission-denied red. Distinct from [statusError] because `ufm_denied`
     * carries a night override (`#FFEF5350`) that `ufm_error` does not.
     */
    @ColorInt
    fun denied(context: Context): Int =
        color(context, R.attr.ufmDenied, R.color.ufm_denied)

    /**
     * Shizuku's "service is enabled and active" green — the counterpart of
     * [shizukuError], which already had an attribute while this one did not.
     *
     * Found by measuring chroma rather than by reading names: `shizuku_status_ok`
     * contains no hue word, so the gap report never listed it, and it was being
     * set directly on a TextView. With the mode on, that status text stayed green
     * — the exact symptom reported for this feature. See `check_unlevelled_hues.py`.
     */
    @ColorInt
    fun shizukuOk(context: Context): Int =
        color(context, R.attr.ufmShizukuOk, R.color.shizuku_status_ok)

    /**
     * Shizuku's error/not-running red. Its attribute predates this change; what
     * was missing was any caller — every read went straight to `R.color`.
     */
    @ColorInt
    fun shizukuError(context: Context): Int =
        color(context, R.attr.ufmShizukuError, R.color.shizuku_status_error)

    /**
     * The positive tier of the progress scale — the success icon's tint and the
     * default fill of `CircularProgressView`. Completes a family whose other two
     * tiers already had levers.
     */
    @ColorInt
    fun progressFill(context: Context): Int =
        color(context, R.attr.ufmProgressFill, R.color.ufm_progress_fill)

    /**
     * The stop/abort affordance red (`#FFB71C1C`).
     *
     * Deliberately not [denied]: that is `#FFC62828`, and swapping one red for
     * another would change the mode-off appearance at every stop button.
     */
    @ColorInt
    fun stopButton(context: Context): Int =
        color(context, R.attr.ufmStopButton, R.color.mobile_stop_btn)

    /** Translucent status-card fills — the green/amber surfaces FR-10 must reach. */
    @ColorInt
    fun statusGlassGreen(context: Context): Int =
        color(context, R.attr.ufmStatusGlassGreen, R.color.tv_glass_green)

    @ColorInt
    fun statusGlassAmber(context: Context): Int =
        color(context, R.attr.ufmStatusGlassAmber, R.color.tv_glass_amber)

    // The seven below are variants of the same two status ROLES (success and
    // warning) that the app renders in colours differing from the shared
    // attributes by day, by night, or both — measured, tabulated in attrs.xml.
    // Each is its own lever purely so the off state stays byte-identical; the
    // overlays collapse every success onto one hue and every warning onto one.

    /** `policy_green` — the policy screens' success green. */
    @ColorInt
    fun policyGreen(context: Context): Int =
        color(context, R.attr.ufmPolicyGreen, R.color.policy_green)

    /** `ufm_success` — a second success green, distinct in both day and night. */
    @ColorInt
    fun success(context: Context): Int =
        color(context, R.attr.ufmSuccess, R.color.ufm_success)

    /** `vpn_warning_amber` — amber warning; differs from [statusWarning] by day. */
    @ColorInt
    fun vpnWarningAmber(context: Context): Int =
        color(context, R.attr.ufmVpnWarningAmber, R.color.vpn_warning_amber)

    /** `ufm_progress_warning` — amber progress warning, with a night override. */
    @ColorInt
    fun progressWarning(context: Context): Int =
        color(context, R.attr.ufmProgressWarning, R.color.ufm_progress_warning)

    /** `policy_green_bg` — the mobile success pill fill behind [policyGreen]. */
    @ColorInt
    fun policyGreenSurface(context: Context): Int =
        color(context, R.attr.ufmPolicyGreenSurface, R.color.policy_green_bg)

    /** `policy_red_bg` — the mobile danger pill fill paired with [statusError]. */
    @ColorInt
    fun policyRedSurface(context: Context): Int =
        color(context, R.attr.ufmPolicyRedSurface, R.color.policy_red_bg)

    /** `tv_glass_red` — the TV danger alert fill, twin of [statusGlassGreen]. */
    @ColorInt
    fun statusGlassRed(context: Context): Int =
        color(context, R.attr.ufmStatusGlassRed, R.color.tv_glass_red)

    /**
     * The distinguishing glyph paired with a status colour — the redundancy that
     * makes FR-10 work for a user who cannot perceive the remapped colours at all.
     *
     * > **Returns a transparent no-op when the mode is Off**, because the three
     * > `ufmStatus*Icon` attributes default to `@drawable/ufm_status_icon_none`.
     * > Call sites should therefore prefer [statusIconOr], which applies this
     * > only while [ColorblindPrefs.isEnabled] is true and otherwise returns the
     * > site's own drawable — applying this unconditionally would blank out
     * > status icons that exist today and break FR-19.
     *
     * This asymmetry is deliberate and cannot be designed away: sites that already
     * render a status icon and sites that render only a colour are mixed together
     * across the ~40 status call sites, so a single default cannot be both "keep
     * your icon" and "you have no icon to keep".
     */
    fun statusIcon(context: Context, kind: StatusKind): Drawable? =
        ContextCompat.getDrawable(context, statusIconRes(context, kind))

    /** Resource id behind [statusIcon]; for callers that need the id, not a drawable. */
    @DrawableRes
    fun statusIconRes(context: Context, kind: StatusKind): Int {
        val attr = when (kind) {
            StatusKind.SUCCESS -> R.attr.ufmStatusSuccessIcon
            StatusKind.WARNING -> R.attr.ufmStatusWarningIcon
            StatusKind.ERROR   -> R.attr.ufmStatusErrorIcon
        }
        return reference(context, attr, R.drawable.ufm_status_icon_none)
    }

    /**
     * The drawable a status site should show, given the one it shows today.
     *
     * Returns [fallbackRes] untouched while the mode is Off, and the mode's
     * distinguishing glyph while it is On. **Use this rather than
     * [statusIconRes] at call sites.**
     *
     * The `isEnabled` test lives *here*, not at the call site, and that is the
     * point: [statusIconRes] resolves to `ufm_status_icon_none` (a transparent
     * no-op) when no overlay is applied, so a site that swapped its drawable
     * unconditionally would blank out a status icon that exists today — a
     * silent FR-19 break, in the literal sense, on the sites least likely to be
     * re-checked. Encapsulating the branch makes that mistake unrepresentable
     * instead of merely documented: there is no argument combination that
     * returns the no-op while the mode is off.
     *
     * Callers still pass their *own* current drawable rather than this method
     * knowing it, because the sites disagree about what "success" looks like —
     * a checksum match is [R.drawable.ic_check], an analyzer verdict is
     * something else — and the Off state must reproduce each site exactly.
     */
    @DrawableRes
    fun statusIconOr(context: Context, kind: StatusKind, @DrawableRes fallbackRes: Int): Int =
        if (ColorblindPrefs.isEnabled(context)) statusIconRes(context, kind) else fallbackRes

    // ── Storage analyzer (FR-11) ────────────────────────────────────────────
    // Remapped as a SET, not individually: the requirement is that categories
    // stay separable from EACH OTHER, which a per-colour fix cannot guarantee.

    @ColorInt
    fun analyzerImages(context: Context): Int =
        color(context, R.attr.ufmAnalyzerImages, R.color.ufm_analyzer_images)

    @ColorInt
    fun analyzerVideos(context: Context): Int =
        color(context, R.attr.ufmAnalyzerVideos, R.color.ufm_analyzer_videos)

    @ColorInt
    fun analyzerAudio(context: Context): Int =
        color(context, R.attr.ufmAnalyzerAudio, R.color.ufm_analyzer_audio)

    @ColorInt
    fun analyzerDocuments(context: Context): Int =
        color(context, R.attr.ufmAnalyzerDocuments, R.color.ufm_analyzer_documents)

    @ColorInt
    fun analyzerApks(context: Context): Int =
        color(context, R.attr.ufmAnalyzerApks, R.color.ufm_analyzer_apks)

    @ColorInt
    fun analyzerOther(context: Context): Int =
        color(context, R.attr.ufmAnalyzerOther, R.color.ufm_analyzer_other)

    /**
     * The **empty** track behind the usage bar's segments.
     *
     * Part of the analyzer set even though it is not a category: a segment's edge
     * is only readable if the surface it is drawn on is separable from it, so the
     * track has to be chosen against all six segments at once. It was a hardcoded
     * `0xFFE0E0E0` in `StorageBarView` — which is byte-identical to this
     * attribute's default, so the mode-off bar is unchanged.
     *
     * The default is poor in both appearances (1.06:1 against the day page;
     * within 1.26:1 of the brightest night segment), so the overlays move it to a
     * luminance between the page and the nearest segment.
     */
    @ColorInt
    fun analyzerTrack(context: Context): Int =
        color(context, R.attr.ufmAnalyzerTrack, R.color.ufm_analyzer_track)

    // ── Resolution ──────────────────────────────────────────────────────────

    private const val FALLBACK_STROKE_DP = 3f

    /**
     * Resolve a colour attribute to an ARGB int, falling back to [fallback].
     *
     * `resolveRefs = true` follows the chain `?attr/x` → `@color/y` to the final
     * literal, so the common case lands in the `TYPE_*_COLOR_INT` range and is
     * returned directly. A `TYPE_REFERENCE` result means the chain stopped at an
     * unresolved reference — a `ColorStateList` or an indirect resource — which is
     * read through `ContextCompat.getColor` so state lists still resolve.
     */
    @ColorInt
    private fun color(context: Context, @AttrRes attr: Int, @ColorRes fallback: Int): Int {
        val tv = TypedValue()
        if (context.theme.resolveAttribute(attr, tv, true)) {
            if (tv.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT) {
                return tv.data
            }
            if (tv.resourceId != 0) {
                return ContextCompat.getColor(context, tv.resourceId)
            }
        }
        return ContextCompat.getColor(context, fallback)
    }

    /** Resolve a dimension attribute to pixels, falling back to [fallbackDp] dp. */
    private fun dimension(context: Context, @AttrRes attr: Int, fallbackDp: Float): Float {
        val tv = TypedValue()
        if (context.theme.resolveAttribute(attr, tv, true) && tv.type == TypedValue.TYPE_DIMENSION) {
            return tv.getDimension(context.resources.displayMetrics)
        }
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, fallbackDp, context.resources.displayMetrics
        )
    }

    /**
     * Resolve a reference attribute (one carrying a *drawable*) to a resource id.
     *
     * Unlike [color] this deliberately does **not** use `resolveRefs = true`: the
     * caller wants the drawable's id so it can be loaded lazily, and forcing full
     * resolution would inflate it here on every call.
     */
    @DrawableRes
    private fun reference(context: Context, @AttrRes attr: Int, @DrawableRes fallback: Int): Int {
        val tv = TypedValue()
        if (context.theme.resolveAttribute(attr, tv, false) && tv.resourceId != 0) {
            return tv.resourceId
        }
        return fallback
    }
}
