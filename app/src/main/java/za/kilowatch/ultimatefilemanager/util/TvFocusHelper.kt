package za.kilowatch.ultimatefilemanager.util

import android.content.Context
import android.view.View
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.settings.ColorblindPalette
import za.kilowatch.ultimatefilemanager.settings.ColorblindPrefs

/**
 * The single implementation of the TV card focus treatment.
 *
 * ## Why this file exists
 *
 * Nineteen near-identical `setupTvCardFocus` copies were spread across
 * activities and adapters, each resolving the same focus colours by hand:
 * `tv_button_focused_yellow` in all 19, `tv_button_focused_yellow_text` in 18,
 * `tv_accent` in 14. Those reads happen **after** inflation, so no theme overlay
 * can reach them — every copy had to be redirected through [ColorblindPalette]
 * or it would keep painting the old colours while the mode is on. Nineteen
 * copies means nineteen chances to miss one, and a missed one is invisible until
 * someone turns the mode on and focuses that particular card.
 *
 * ## What is deliberately *not* centralised
 *
 * Each site keeps its own focus listener and its own extra behaviour —
 * `FontSizeActivity` re-tints a radio button and a letter badge from the
 * selected-state, `StorageRenameActivity` branches on `isOnline`, the adapter
 * copies re-tint a delete button. Folding those into a parameterised monster
 * would make the common case harder to read than the duplication it replaced.
 * What is shared here is the part that was genuinely identical: **which colours**
 * a focus treatment uses. The FR-06 marker is deliberately *not* one of those
 * values: it is not a colour to paint but a write to a slot that may already have
 * an owner, so it goes through [applyMarker] rather than through a resolved field
 * a caller could assign from.
 *
 * ## Resting appearance is intentionally left alone
 *
 * `tv_glass_white_10`, `tv_text_primary` and `tv_text_secondary` are also
 * resolved by those 19 bodies, and they are **not** routed through this helper.
 * They are the card's *unfocused* appearance — nothing to do with focus or
 * status — and repainting them would change the app's look with the mode **off**.
 * Only the focus cues move.
 *
 * @see ColorblindPalette for the underlying attribute resolution and its
 *      requirement that callers pass a **theme-carrying** context.
 */
object TvFocusHelper {

    /**
     * The focus colours a TV card needs, resolved once.
     *
     * Resolve this outside the focus-change listener, not inside it: every
     * accessor is a `theme.resolveAttribute` call, and the listener fires on
     * every D-pad move.
     */
    class FocusColors internal constructor(
        /** Solid fill painted behind a focused card. */
        val fill: Int,
        /** Text and icon colour drawn ON [fill]. */
        val onFill: Int,
        /** The card/swatch focus accent — the "blue" family unified per FR-07. */
        val accent: Int,
        /** Outline colour for programmatically-drawn focus rings. */
        val stroke: Int,
        /** Focus outline weight in px — the only channel delivering FR-02 scaling. */
        val strokeWidthPx: Float,
    )

    /** Resolve the focus treatment for [context]. Pass an Activity, not the app context. */
    fun colors(context: Context): FocusColors = FocusColors(
        fill = ColorblindPalette.focusFill(context),
        onFill = ColorblindPalette.focusFillText(context),
        accent = ColorblindPalette.focusAccent(context),
        stroke = ColorblindPalette.focusStrokeColor(context),
        strokeWidthPx = ColorblindPalette.focusStrokeWidth(context),
    )

    /**
     * Apply the FR-06 marker to [view] for a focus change, or leave the slot alone.
     *
     * ## The slot may already have an owner, and it is not always this helper
     *
     * A card whose layout declares `android:foreground="@drawable/selector_tv_card"`
     * already owns that slot. That selector is the app's existing TV focus
     * treatment — three graduated glow rings, the `?attr/ufmFocusAccent` border —
     * and its focused state *already* carries the FR-06 marker (T012). Assigning a
     * marker over it replaces the whole selector, and because blur assigns `null`
     * rather than restoring, the card loses that treatment permanently the first
     * time it is focused. **That happens with the mode Off**, so it is an FR-19
     * break: a visible regression for every TV user who never enables the feature.
     *
     * That is why the assignment is this helper's job and no longer a
     * `Drawable?` for callers to assign themselves. The rule used to live only in
     * this comment, and two call sites broke it. `View.foreground` is a **single
     * slot**, so "write it only if nobody else has" is the only correct reading of
     * it, and it is now enforced rather than described.
     *
     * ## How ownership is decided
     *
     * On the first event the slot still holds whatever XML put there, so a non-null
     * `foreground` means the layout owns it and a null one means this helper does.
     * The answer is cached on the view, because by the second event the evidence is
     * gone: this helper's own `null` on blur is indistinguishable from a layout
     * that never set a foreground at all.
     *
     * ## Why blur assigns `null` rather than a transparent drawable
     *
     * Only for a slot this helper owns: a lingering foreground would sit above the
     * card's content and swallow touches on the rows behind it.
     *
     * The mode gate really is unnecessary here — but again only for a card this
     * helper owns: with the mode Off the drawable paints nothing, so an unfocused
     * card and a mode-off card both end up visually unchanged. That reasoning does
     * not extend to a slot that already held something, which is the point above.
     */
    fun applyMarker(view: View, hasFocus: Boolean) {
        var owned = view.getTag(R.id.tag_foreground_owned)
        if (owned == null) {
            owned = view.foreground == null
            view.setTag(R.id.tag_foreground_owned, owned)
        }
        if (owned != true) return
        view.foreground = if (hasFocus) ColorblindPalette.focusMarker(view.context) else null
    }

    /**
     * Apply the standard treatment to [card] for a focus change.
     *
     * Covers the fill, the marker, and the FR-06 outline. Callers that also re-tint
     * child text, icons or radio buttons should keep doing so themselves — and
     * should call [restingTextColors] / [onFillTextColor] so those tints follow the
     * palette too.
     *
     * ## Why the outline is mode-gated and the fill is not
     *
     * The fill swap is the app's **existing** focus treatment: `focusFill`'s base
     * default is byte-identical to the `tv_button_focused_yellow` these nineteen
     * sites resolved by hand, so applying it unconditionally changes nothing while
     * the mode is Off.
     *
     * The outline is **new** — there is no outline on these cards today (Q2 chose
     * outline + marker, with no scale). Applying it unconditionally would add a
     * stroke to every focused card in the app with the mode Off, which is the one
     * thing FR-19 forbids. So it is applied only while
     * [ColorblindPrefs.isEnabled], and the card's own resting stroke is restored
     * otherwise.
     *
     * ## Why the resting stroke is captured rather than assumed
     *
     * Cards do not share one resting stroke: item cards declare 1dp
     * `tv_glass_border`, settings group cards 2dp. Restoring a hardcoded value
     * would silently repaint whichever cards disagreed, so the first focus
     * captures what the XML declared and every blur restores *that*. The capture
     * happens before the first overwrite, which is the only ordering that is
     * correct.
     */
    fun applyCardFocus(card: MaterialCardView, colors: FocusColors, hasFocus: Boolean) {
        card.setCardBackgroundColor(if (hasFocus) colors.fill else cardRestingFill(card))
        // Routed through applyMarker rather than assigned here: this function is
        // also reached for cards that declare their own `foreground`, and it has no
        // way to know which without asking. See applyMarker.
        applyMarker(card, hasFocus)

        if (!ColorblindPrefs.isEnabled(card.context)) {
            // Mode went off under a card that was focused while it was on. Put the
            // XML stroke back rather than leaving a palette stroke stranded.
            restoreStroke(card)
            return
        }
        if (hasFocus) {
            if (card.getTag(R.id.tag_resting_stroke_color) == null) {
                card.setTag(R.id.tag_resting_stroke_color, card.strokeColor)
                card.setTag(R.id.tag_resting_stroke_width, card.strokeWidth)
            }
            card.strokeColor = colors.stroke
            card.strokeWidth = colors.strokeWidthPx.toInt()
        } else {
            restoreStroke(card)
        }
    }

    /** Put back the stroke the card's XML declared, if this helper ever replaced it. */
    private fun restoreStroke(card: MaterialCardView) {
        val color = card.getTag(R.id.tag_resting_stroke_color) as? Int ?: return
        val width = card.getTag(R.id.tag_resting_stroke_width) as? Int ?: return
        card.strokeColor = color
        card.strokeWidth = width
    }

    /** Text/icon colour to use while [hasFocus]. */
    fun onFillTextColor(colors: FocusColors): Int = colors.onFill

    /**
     * Record the colour a card should return to when it loses focus.
     *
     * Call this when the card must not fall back to the shared glass token — the
     * same treatment a highlighted or already-selected card needs.
     *
     * **Do not use `View.tag` for this.** It reads as the obvious place and it is
     * the wrong one: callers legitimately store an *option key* in `tag`, and an
     * `Int` option key is a perfectly valid `Int` colour, so a card tagged `2`
     * would blur to `0x00000002`. A dedicated id keeps identity and appearance in
     * separate slots.
     */
    fun setRestingFill(card: View, color: Int) {
        card.setTag(R.id.tag_resting_fill, color)
    }

    /**
     * The card's unfocused fill, read back from [setRestingFill] if a caller
     * recorded one; otherwise the shared glass token, so the card never ends up
     * transparent on blur.
     */
    private fun cardRestingFill(card: View): Int {
        val tag = card.getTag(R.id.tag_resting_fill)
        return if (tag is Int && tag != 0) tag
        else ContextCompat.getColor(card.context, R.color.tv_glass_white_10)
    }

    /**
     * Resting text colours, for callers restoring a blurred card.
     *
     * Deliberately **not** palette-routed — these are the app's normal text
     * colours, unchanged by the mode.
     */
    fun restingTextColors(context: Context): Pair<Int, Int> =
        ContextCompat.getColor(context, R.color.tv_text_primary) to
            ContextCompat.getColor(context, R.color.tv_text_secondary)
}
