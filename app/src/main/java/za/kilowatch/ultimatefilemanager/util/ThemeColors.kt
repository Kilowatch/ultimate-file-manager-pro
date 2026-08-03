package za.kilowatch.ultimatefilemanager.util

import android.content.Context
import android.graphics.Color
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import com.google.android.material.R
import com.google.android.material.color.MaterialColors

/**
 * Resolves Material 3 theme-token colours from an Activity context at runtime.
 *
 * Intended for Kotlin call sites that previously hardcoded hex literals
 * (`Color.parseColor(...)`, `0xFF......`, `Color.WHITE`, ...). Every call must
 * be made from a **themed, inflated context** (an Activity after [android.app.Activity.setContentView],
 * or a view's context), so that the active theme — including the Material You
 * DynamicColors overlay and the AMOLED overlay — is applied. Static / pre-inflation
 * contexts will resolve the theme's fallback values instead.
 *
 * `withAlpha` reuses the same alpha math as the M3 translucent-glass tones.
 */
object ThemeColors {

    // ── Core accents ─────────────────────────────────────────────────────────
    @ColorInt fun primary(context: Context): Int = attr(context, androidx.appcompat.R.attr.colorPrimary)
    @ColorInt fun onPrimary(context: Context): Int = attr(context, com.google.android.material.R.attr.colorOnPrimary)
    @ColorInt fun primaryContainer(context: Context): Int = attr(context, com.google.android.material.R.attr.colorPrimaryContainer)
    @ColorInt fun onPrimaryContainer(context: Context): Int = attr(context, com.google.android.material.R.attr.colorOnPrimaryContainer)
    @ColorInt fun secondary(context: Context): Int = attr(context, com.google.android.material.R.attr.colorSecondary)
    @ColorInt fun onSecondary(context: Context): Int = attr(context, com.google.android.material.R.attr.colorOnSecondary)
    @ColorInt fun secondaryContainer(context: Context): Int = attr(context, com.google.android.material.R.attr.colorSecondaryContainer)
    @ColorInt fun tertiary(context: Context): Int = attr(context, com.google.android.material.R.attr.colorTertiary)
    @ColorInt fun error(context: Context): Int = attr(context, androidx.appcompat.R.attr.colorError)
    @ColorInt fun onError(context: Context): Int = attr(context, com.google.android.material.R.attr.colorOnError)

    // ── Surfaces ─────────────────────────────────────────────────────────────
    @ColorInt fun surface(context: Context): Int = attr(context, com.google.android.material.R.attr.colorSurface)
    @ColorInt fun onSurface(context: Context): Int = attr(context, com.google.android.material.R.attr.colorOnSurface)
    @ColorInt fun surfaceVariant(context: Context): Int = attr(context, com.google.android.material.R.attr.colorSurfaceVariant)
    @ColorInt fun onSurfaceVariant(context: Context): Int = attr(context, com.google.android.material.R.attr.colorOnSurfaceVariant)
    @ColorInt fun surfaceContainerLowest(context: Context): Int = attr(context, com.google.android.material.R.attr.colorSurfaceContainerLowest)
    @ColorInt fun surfaceContainerLow(context: Context): Int = attr(context, com.google.android.material.R.attr.colorSurfaceContainerLow)
    @ColorInt fun surfaceContainer(context: Context): Int = attr(context, com.google.android.material.R.attr.colorSurfaceContainer)
    @ColorInt fun surfaceContainerHigh(context: Context): Int = attr(context, com.google.android.material.R.attr.colorSurfaceContainerHigh)
    @ColorInt fun surfaceContainerHighest(context: Context): Int = attr(context, com.google.android.material.R.attr.colorSurfaceContainerHighest)
    @ColorInt fun surfaceDim(context: Context): Int = attr(context, com.google.android.material.R.attr.colorSurfaceDim)

    // ── Outline / inverse / scrim ────────────────────────────────────────────
    @ColorInt fun outline(context: Context): Int = attr(context, com.google.android.material.R.attr.colorOutline)
    @ColorInt fun outlineVariant(context: Context): Int = attr(context, com.google.android.material.R.attr.colorOutlineVariant)

    // Note: colorInverseSurface / colorInverseOnSurface / colorInversePrimary /
    // colorScrim are NOT exposed as theme attributes by the material Views
    // library, so they are intentionally omitted here (and from themes.xml).

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Resolve [attrResId] against the given context's current theme, falling back
     * to [MaterialColors] defaults so a pre-theme context still yields a colour.
     */
    @ColorInt
    private fun attr(context: Context, @AttrRes attrResId: Int): Int =
        MaterialColors.getColor(context, attrResId, Color.MAGENTA)

    /**
     * Return [color] with its alpha replaced by [alpha] (0..255). Uses the same
     * ARGB math as the M3 translucent-glass tones so token colours can be faded
     * without fixed-alpha duplicates.
     */
    @ColorInt
    fun withAlpha(@ColorInt color: Int, alpha: Int): Int =
        (alpha shl 24) or (color and 0x00FFFFFF)
}
