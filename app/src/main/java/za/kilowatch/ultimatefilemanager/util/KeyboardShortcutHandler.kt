package za.kilowatch.ultimatefilemanager.util

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import za.kilowatch.ultimatefilemanager.settings.KeyboardPreferenceManager

/**
 * Dispatches hardware / Bluetooth keyboard events to file browser actions.
 * Respects master enable/disable setting, Vim mode toggle, and safe text input guards.
 */
class KeyboardShortcutHandler(
    private val activity: Activity,
    private val listener: KeyboardActionListener
) {

    interface KeyboardActionListener {
        fun onMoveDown() {}
        fun onMoveUp() {}
        fun onParentDir() {}
        fun onOpen() {}
        fun onJumpTop() {}
        fun onJumpBottom() {}
        fun onGoToPath() {}
        fun onToggleSelect() {}
        fun onSelectAll() {}
        fun onCopy() {}
        fun onCut() {}
        fun onPaste() {}
        fun onDelete() {}
        fun onRename() {}
        fun onNewFolder() {}
        fun onSearch() {}
        fun onToggleHidden() {}
        fun onRefresh() {}
        fun onSwitchPane() {}
        fun onFocusPane(paneIndex: Int) {}
        fun onCheatsheet() {}
        fun onEscape() {}
    }

    private val handler = Handler(Looper.getMainLooper())
    private var pendingGKey = false
    private val gTimeoutRunnable = Runnable {
        if (pendingGKey) {
            pendingGKey = false
            listener.onGoToPath()
        }
    }

    /**
     * Process key event from Activity or Fragment.
     * @return true if the shortcut was consumed, false to allow default handling.
     */
    fun handleKeyEvent(event: KeyEvent): Boolean {
        if (!KeyboardPreferenceManager.isMasterEnabled(activity)) return false
        if (event.action != KeyEvent.ACTION_DOWN) return false

        val currentFocus = activity.currentFocus
        val isInputFocused = currentFocus is EditText

        val keyCode = event.keyCode
        val isCtrl = event.isCtrlPressed
        val isShift = event.isShiftPressed
        val isAlt = event.isAltPressed
        val isVim = KeyboardPreferenceManager.isVimModeEnabled(activity)
        val isDualPaneEnabled = KeyboardPreferenceManager.isDualPaneSwitchEnabled(activity)

        // Escape always works to clear focus / dismiss
        if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
            pendingGKey = false
            handler.removeCallbacks(gTimeoutRunnable)
            listener.onEscape()
            return true
        }

        // If user is actively typing in an input field, do NOT intercept alphanumeric keys
        if (isInputFocused) {
            return false
        }

        // Handle double-tap 'g' sequence
        if (pendingGKey) {
            handler.removeCallbacks(gTimeoutRunnable)
            pendingGKey = false
            if (keyCode == KeyEvent.KEYCODE_G && !isShift && !isCtrl && !isAlt) {
                listener.onJumpTop()
                return true
            } else {
                // First 'g' was intended as Go To dialog, but a different key was pressed
                listener.onGoToPath()
                // Process the current key event next
            }
        }

        // Navigation
        when {
            // Move Down: 'j' (Vim) or Down Arrow
            (isVim && keyCode == KeyEvent.KEYCODE_J && !isCtrl && !isAlt && !isShift) ||
            keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> {
                listener.onMoveDown()
                return true
            }

            // Move Up: 'k' (Vim) or Up Arrow
            (isVim && keyCode == KeyEvent.KEYCODE_K && !isCtrl && !isAlt && !isShift) ||
            keyCode == KeyEvent.KEYCODE_DPAD_UP -> {
                listener.onMoveUp()
                return true
            }

            // Parent Directory: 'h' (Vim) or Backspace or Alt+Up or Left Arrow
            (isVim && keyCode == KeyEvent.KEYCODE_H && !isCtrl && !isAlt && !isShift) ||
            keyCode == KeyEvent.KEYCODE_DEL ||
            (isAlt && keyCode == KeyEvent.KEYCODE_DPAD_UP) ||
            keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> {
                listener.onParentDir()
                return true
            }

            // Open / Enter: 'l' (Vim) or Enter or Right Arrow or D-pad Center
            (isVim && keyCode == KeyEvent.KEYCODE_L && !isCtrl && !isAlt && !isShift) ||
            keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
            keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
            keyCode == KeyEvent.KEYCODE_DPAD_CENTER -> {
                listener.onOpen()
                return true
            }

            // Jump to Top: Home
            keyCode == KeyEvent.KEYCODE_MOVE_HOME -> {
                listener.onJumpTop()
                return true
            }

            // Jump to Bottom: 'G' (Shift+g in Vim) or End
            (isVim && keyCode == KeyEvent.KEYCODE_G && isShift && !isCtrl && !isAlt) ||
            keyCode == KeyEvent.KEYCODE_MOVE_END -> {
                listener.onJumpBottom()
                return true
            }

            // Go To / Jump sequence start: 'g' (without Shift/Ctrl/Alt)
            isVim && keyCode == KeyEvent.KEYCODE_G && !isShift && !isCtrl && !isAlt -> {
                pendingGKey = true
                handler.postDelayed(gTimeoutRunnable, 400L)
                return true
            }

            // Go To Path: Ctrl+G
            isCtrl && keyCode == KeyEvent.KEYCODE_G -> {
                listener.onGoToPath()
                return true
            }

            // Toggle Selection: Space or 'v' (Vim visual)
            keyCode == KeyEvent.KEYCODE_SPACE ||
            (isVim && keyCode == KeyEvent.KEYCODE_V && !isCtrl && !isAlt && !isShift) -> {
                listener.onToggleSelect()
                return true
            }

            // Select All: 'a' or Ctrl+A
            (isCtrl && keyCode == KeyEvent.KEYCODE_A) ||
            (isVim && keyCode == KeyEvent.KEYCODE_A && !isCtrl && !isAlt && !isShift) -> {
                listener.onSelectAll()
                return true
            }

            // Copy: 'y' (Yank in Vim) or Ctrl+C
            (isCtrl && keyCode == KeyEvent.KEYCODE_C) ||
            (isVim && keyCode == KeyEvent.KEYCODE_Y && !isCtrl && !isAlt && !isShift) -> {
                listener.onCopy()
                return true
            }

            // Cut: 'x' or Ctrl+X
            (isCtrl && keyCode == KeyEvent.KEYCODE_X) ||
            (isVim && keyCode == KeyEvent.KEYCODE_X && !isCtrl && !isAlt && !isShift) -> {
                listener.onCut()
                return true
            }

            // Paste: 'p' (Put in Vim) or Ctrl+V
            (isCtrl && keyCode == KeyEvent.KEYCODE_V) ||
            (isVim && keyCode == KeyEvent.KEYCODE_P && !isCtrl && !isAlt && !isShift) -> {
                listener.onPaste()
                return true
            }

            // Delete: 'd' or Delete key
            keyCode == KeyEvent.KEYCODE_FORWARD_DEL ||
            (isVim && keyCode == KeyEvent.KEYCODE_D && !isCtrl && !isAlt && !isShift) -> {
                listener.onDelete()
                return true
            }

            // Rename: 'r' or F2
            keyCode == KeyEvent.KEYCODE_F2 ||
            (isVim && keyCode == KeyEvent.KEYCODE_R && !isCtrl && !isAlt && !isShift) -> {
                listener.onRename()
                return true
            }

            // New Folder: 'n' or Ctrl+Shift+N
            (isCtrl && isShift && keyCode == KeyEvent.KEYCODE_N) ||
            (isVim && keyCode == KeyEvent.KEYCODE_N && !isCtrl && !isAlt && !isShift) -> {
                listener.onNewFolder()
                return true
            }

            // Search / Find: '/' or Ctrl+F
            (isCtrl && keyCode == KeyEvent.KEYCODE_F) ||
            keyCode == KeyEvent.KEYCODE_SLASH -> {
                listener.onSearch()
                return true
            }

            // Toggle Hidden: '.' or Ctrl+H
            (isCtrl && keyCode == KeyEvent.KEYCODE_H) ||
            keyCode == KeyEvent.KEYCODE_PERIOD -> {
                listener.onToggleHidden()
                return true
            }

            // Refresh: F5 or Ctrl+R
            keyCode == KeyEvent.KEYCODE_F5 ||
            (isCtrl && keyCode == KeyEvent.KEYCODE_R) -> {
                listener.onRefresh()
                return true
            }

            // Dual Pane Fast Switch: Tab or Ctrl+W
            isDualPaneEnabled && (keyCode == KeyEvent.KEYCODE_TAB || (isCtrl && keyCode == KeyEvent.KEYCODE_W)) -> {
                listener.onSwitchPane()
                return true
            }

            // Focus Pane 1: '1'
            isDualPaneEnabled && keyCode == KeyEvent.KEYCODE_1 && !isCtrl && !isAlt && !isShift -> {
                listener.onFocusPane(1)
                return true
            }

            // Focus Pane 2: '2'
            isDualPaneEnabled && keyCode == KeyEvent.KEYCODE_2 && !isCtrl && !isAlt && !isShift -> {
                listener.onFocusPane(2)
                return true
            }

            // Cheatsheet Help: '?' (Shift+Slash) or F1 or Ctrl+/
            keyCode == KeyEvent.KEYCODE_F1 ||
            (isShift && keyCode == KeyEvent.KEYCODE_SLASH) ||
            (isCtrl && keyCode == KeyEvent.KEYCODE_SLASH) -> {
                listener.onCheatsheet()
                return true
            }
        }

        return false
    }
}
