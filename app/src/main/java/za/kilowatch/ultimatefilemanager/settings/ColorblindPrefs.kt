package za.kilowatch.ultimatefilemanager.settings

import android.content.Context

/**
 * Reads/writes the Colorblind Accessibility Mode preferences.
 *
 * The preferences live in the existing [ufm_prefs] file (same file as the theme
 * mode in [ThemeHelper] and the Material You flag in [MaterialYouPrefs]) so they
 * are automatically covered by the settings backup system under the
 * already-registered "ufm_prefs" entry — no extra backup registration is
 * required. This satisfies FR-18 with no `SettingsBackupManager` change.
 *
 * Two independent values are stored, per FR-01 and FR-02:
 *
 *  - **Type** — which palette to apply. Default [TYPE_OFF]: the app keeps its
 *    original appearance unless the user explicitly opts in.
 *  - **Strength** — how far the palette pushes contrast separation. Retained
 *    even while the type is Off, so switching back to a type restores the
 *    user's last chosen strength rather than silently resetting it (FR-03).
 *
 * > **Writes always use `apply()`, never `commit()`.** Changing either value
 * > triggers `UfmApplication.recreateAllActivities()`, which is fire-and-forget
 * > `handler.post`. That is only safe because `apply()` updates the in-memory
 * > map synchronously before returning — so the recreated activities read the
 * > new value on their first pass. A `commit()` on a background thread would
 * > let the recreate read the stale value and trigger a second, unrequested
 * > recreate, which the user sees as a double-flash. `FontSizeHelper`'s
 * > `@Volatile`-cache-plus-`commit()` model is deliberately **not** copied for
 * > this reason.
 *
 * @see ColorblindPalette for the resolved colours these values select.
 */
object ColorblindPrefs {

    private const val PREFS = "ufm_prefs"
    private const val KEY_TYPE = "colorblind_type"
    private const val KEY_STRENGTH = "colorblind_strength"

    // ── Type (FR-01) ────────────────────────────────────────────────────────
    // Three types, not four: protanopia and deuteranopia are both red-green and
    // share one palette, which is why the list is Red-green / Blue-yellow /
    // General High Contrast rather than naming each condition.

    /** Mode off — the app renders exactly as it does today. Also the default. */
    const val TYPE_OFF = 0

    /** Red-green deficiency (protanopia, deuteranopia). */
    const val TYPE_RED_GREEN = 1

    /** Blue-yellow deficiency (tritanopia). */
    const val TYPE_BLUE_YELLOW = 2

    /** General high contrast — no specific deficiency assumed. */
    const val TYPE_HIGH_CONTRAST = 3

    /** Number of selectable types *including* [TYPE_OFF]; for bounds and UI. */
    const val TYPE_COUNT = 4

    // ── Strength (FR-02) ────────────────────────────────────────────────────
    // Each tier is a contrast floor rather than a fixed look: Low ≥ 3:1,
    // Medium ≥ 4.5:1, High ≥ 7:1 against the immediate background, measured in
    // the appearance actually in use. A single colour cannot satisfy both the
    // near-white day background and the dark night glass, so the tier→colour
    // mapping lives in the day/night palette pair, not here.

    /** Low — 3:1 contrast floor. */
    const val STRENGTH_LOW = 0

    /** Medium — 4.5:1 contrast floor. The default. */
    const val STRENGTH_MEDIUM = 1

    /** High — 7:1 contrast floor. */
    const val STRENGTH_HIGH = 2

    /** Number of selectable strengths; for bounds and UI. */
    const val STRENGTH_COUNT = 3

    // ── Type accessors ──────────────────────────────────────────────────────

    /**
     * The selected type, coerced into `0..TYPE_COUNT-1`. Defaults to
     * [TYPE_OFF] when unset.
     *
     * The `coerceIn` guards against a stored value written by a future version
     * that offered more types than this one knows about: rather than crashing
     * on an out-of-range palette lookup or rendering an arbitrary style, an
     * unknown value degrades to [TYPE_OFF].
     */
    fun getType(context: Context): Int {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_TYPE, TYPE_OFF)
        return stored.coerceIn(0, TYPE_COUNT - 1)
    }

    /** Persist the selected type. Out-of-range values are coerced, not rejected. */
    fun setType(context: Context, type: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_TYPE, type.coerceIn(0, TYPE_COUNT - 1))
            .apply()
    }

    // ── Strength accessors ──────────────────────────────────────────────────

    /**
     * The selected strength, coerced into `0..STRENGTH_COUNT-1`. Defaults to
     * [STRENGTH_MEDIUM] when unset — a mid-point that is already a meaningful
     * improvement, so a user who picks a type without touching strength gets
     * the benefit of the mode immediately.
     */
    fun getStrength(context: Context): Int {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_STRENGTH, STRENGTH_MEDIUM)
        return stored.coerceIn(0, STRENGTH_COUNT - 1)
    }

    /** Persist the selected strength. Out-of-range values are coerced, not rejected. */
    fun setStrength(context: Context, strength: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_STRENGTH, strength.coerceIn(0, STRENGTH_COUNT - 1))
            .apply()
    }

    // ── Convenience ─────────────────────────────────────────────────────────

    /**
     * Whether the mode is active at all. This is the check [ThemeHelper] uses to
     * decide whether to apply an overlay, and the one every consumer should use
     * rather than comparing against [TYPE_OFF] itself.
     */
    fun isEnabled(context: Context): Boolean = getType(context) != TYPE_OFF
}
