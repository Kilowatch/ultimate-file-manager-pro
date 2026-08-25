package za.kilowatch.ultimatefilemanager.util

import android.app.Dialog
import android.content.Context
import android.view.KeyEvent
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.DialogFragment

/**
 * Utility helper to standardize text input dialog behaviors:
 * 1. Automatically requests focus on the input field.
 * 2. Automatically displays the software keyboard on mobile devices (while preserving D-pad focus on TV).
 * 3. Handles IME Action Done / Go / Send and hardware Enter key to complete editing and trigger the primary action.
 */
object DialogInputHelper {

    /**
     * Prepares a standard [Dialog] with automatic focus, keyboard opening, and Enter key completion.
     */
    fun setupDialogInput(
        dialog: Dialog,
        editText: EditText?,
        onDone: (() -> Unit)? = null
    ) {
        if (editText == null) return

        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)

        if (onDone != null) {
            setupDoneAction(editText, onDone)
        }

        autoFocusAndShowKeyboard(editText, dialog)
    }

    /**
     * Prepares a [DialogFragment] or [com.google.android.material.bottomsheet.BottomSheetDialogFragment]
     * with automatic focus, keyboard opening, and Enter key completion.
     */
    fun setupDialogFragmentInput(
        fragment: DialogFragment,
        editText: EditText?,
        onDone: (() -> Unit)? = null
    ) {
        if (editText == null) return

        fragment.dialog?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)

        if (onDone != null) {
            setupDoneAction(editText, onDone)
        }

        autoFocusAndShowKeyboard(editText, fragment.dialog)
    }

    /**
     * Wires an [EditText.OnEditorActionListener] that triggers [onDone] when the user presses
     * the IME Done / Go / Send key or hardware Enter.
     */
    fun setupDoneAction(editText: EditText?, onDone: () -> Unit) {
        editText?.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_DONE ||
                actionId == EditorInfo.IME_ACTION_GO ||
                actionId == EditorInfo.IME_ACTION_SEND ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                onDone()
                true
            } else {
                false
            }
        }
    }

    /**
     * Requests focus on [editText] and requests the software keyboard to show on mobile devices.
     */
    fun autoFocusAndShowKeyboard(editText: EditText?, dialog: Dialog? = null) {
        if (editText == null) return
        editText.post {
            editText.requestFocus()
            val context = editText.context
            if (!DeviceUtils.isTvDevice(context)) {
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)

                dialog?.window?.let { window ->
                    WindowCompat.getInsetsController(window, editText)
                        .show(WindowInsetsCompat.Type.ime())
                }
            }
        }
    }
}
