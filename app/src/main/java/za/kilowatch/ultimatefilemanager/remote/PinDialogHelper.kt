package za.kilowatch.ultimatefilemanager.remote

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import za.kilowatch.ultimatefilemanager.R

/**
 * Shows a premium 4-digit PIN entry dialog.
 *
 * Uses a single hidden EditText to capture all 4 digits so the IME
 * connection is never torn down (no keyboard reset). Four TextViews act
 * as the visual digit boxes, updated as the user types.
 *
 * Calls [onPinSet] with the full PIN once confirmed.
 */
object PinDialogHelper {

    fun showPinDialog(
        context: Context,
        title: String? = null,
        subtitle: String? = null,
        confirmText: String? = null,
        showChangePin: Boolean = false,
        onCancel: (() -> Unit)? = null,
        onChangePin: (() -> Unit)? = null,
        showRecoveryCode: Boolean = false,
        onRecoveryCode: (() -> Unit)? = null,
        onPinSet: (String) -> Unit
    ) {
        val resolvedTitle = title ?: context.getString(R.string.set_access_pin)
        val resolvedSubtitle = subtitle ?: context.getString(R.string.enter_a_4digit_pin_to_secure_remote_access)
        val resolvedConfirmText = confirmText ?: context.getString(R.string.start_server)

        val dialogView = LayoutInflater.from(context)
            .inflate(R.layout.dialog_pin_entry, null)

        // Visual display boxes (TextViews — no EditText focus games)
        val box1 = dialogView.findViewById<TextView>(R.id.pin1)
        val box2 = dialogView.findViewById<TextView>(R.id.pin2)
        val box3 = dialogView.findViewById<TextView>(R.id.pin3)
        val box4 = dialogView.findViewById<TextView>(R.id.pin4)
        // Single real input field — stays focused the whole time
        val pinInput = dialogView.findViewById<EditText>(R.id.pinHiddenInput)

        val txtError    = dialogView.findViewById<TextView>(R.id.txtPinError)
        val txtTitle    = dialogView.findViewById<TextView>(R.id.txtPinTitle)
        val txtSubtitle = dialogView.findViewById<TextView>(R.id.txtPinSubtitle)
        val txtChangePin  = dialogView.findViewById<TextView>(R.id.txtChangePin)
        val txtRecovery   = dialogView.findViewById<TextView>(R.id.txtRecoveryCode)

        txtTitle.text = resolvedTitle
        txtSubtitle.text = resolvedSubtitle

        txtChangePin.visibility = if (showChangePin && onChangePin != null) View.VISIBLE else View.GONE
        txtRecovery.visibility  = if (showRecoveryCode && onRecoveryCode != null) View.VISIBLE else View.GONE

        val boxes = listOf(box1, box2, box3, box4)

        /** Refresh the 4 visual boxes and highlight the active one. */
        fun syncBoxes(digits: String) {
            boxes.forEachIndexed { i, tv ->
                tv.text = if (i < digits.length) "●" else ""
                // Highlight the box that is about to receive the next digit
                val isActive = i == digits.length.coerceAtMost(3)
                tv.isSelected = isActive
            }
        }

        syncBoxes("") // initial state: box1 highlighted

        pinInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                syncBoxes(s?.toString() ?: "")
                // Clear error as user types
                if ((s?.length ?: 0) > 0) {
                    txtError.visibility = View.GONE
                }
            }
        })

        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

        val builder = MaterialAlertDialogBuilder(context)
            .setView(dialogView)
            .setPositiveButton(resolvedConfirmText, null)
            .setCancelable(onCancel != null)
        // Only add a Cancel button when there is something to cancel to
        if (onCancel != null) {
            builder.setNegativeButton(R.string.cancel) { _, _ ->
                onCancel.invoke()
            }
        }
        val dialog = builder.create()

        /** Shared confirm logic — used by on-screen button and keyboard Done key. */
        fun confirmPin() {
            val pin = pinInput.text.toString()
            if (pin.length != 4 || !pin.all { c -> c.isDigit() }) {
                txtError.setText(R.string.please_enter_all_4_digits)
                txtError.visibility = View.VISIBLE
                dialogView.animate().translationX(10f).setDuration(50)
                    .withEndAction {
                        dialogView.animate().translationX(-10f).setDuration(50)
                            .withEndAction {
                                dialogView.animate().translationX(0f).setDuration(50).start()
                            }.start()
                    }.start()
            } else {
                txtError.visibility = View.GONE
                dialog.dismiss()
                // Defer until after the dismiss animation so the next dialog can open
                Handler(Looper.getMainLooper()).post { onPinSet(pin) }
            }
        }

        // Keyboard Done key on the last-digit scenario → confirm
        pinInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                confirmPin()
                true
            } else {
                false
            }
        }

        // Tapping any visual box focuses the hidden input (opens keyboard if closed)
        boxes.forEach { tv ->
            tv.setOnClickListener {
                pinInput.requestFocus()
                imm.showSoftInput(pinInput, InputMethodManager.SHOW_IMPLICIT)
            }
        }

        dialog.setOnShowListener {
            dialog.window?.setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN or
                android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
            )

            // Focus the hidden input and open the keyboard
            pinInput.requestFocus()
            pinInput.post {
                imm.showSoftInput(pinInput, InputMethodManager.SHOW_IMPLICIT)
            }

            if (showChangePin && onChangePin != null) {
                txtChangePin.setOnClickListener {
                    dialog.dismiss()
                    onChangePin.invoke()
                }
            }

            if (showRecoveryCode && onRecoveryCode != null) {
                txtRecovery.setOnClickListener {
                    dialog.dismiss()
                    onRecoveryCode.invoke()
                }
            }

            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                confirmPin()
            }
        }

        dialog.setOnCancelListener {
            onCancel?.invoke()
        }

        dialog.show()
    }
}
