package za.kilowatch.ultimatefilemanager.util

import android.view.View
import androidx.core.view.WindowInsetsCompat

/**
 * Shared IME-inset handling for editor screens.
 *
 * Applies the soft-keyboard height (plus a small breathing gap) as bottom
 * padding on the root view so the editor never sits behind the keyboard, and
 * reports whether the keyboard is currently visible so callers can react to
 * the open/close transition (e.g. scrolling the cursor into view).
 */
object EditorInsets {

    /**
     * Applies system-bar + IME bottom padding to [root] and returns whether the
     * soft keyboard is currently visible.
     *
     * @param root   The root view whose bottom padding is adjusted.
     * @param insets The [WindowInsetsCompat] delivered by the inset listener.
     * @param gapPx  Extra breathing room (px) between the text and the keyboard.
     */
    fun apply(root: View, insets: WindowInsetsCompat, gapPx: Int): Boolean {
        val sb = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
        val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
        root.setPadding(
            sb.left, sb.top, sb.right,
            sb.bottom + ime.bottom + (if (imeVisible) gapPx else 0)
        )
        return imeVisible
    }
}
