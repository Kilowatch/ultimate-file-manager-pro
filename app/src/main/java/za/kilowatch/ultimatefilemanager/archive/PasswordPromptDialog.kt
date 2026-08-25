package za.kilowatch.ultimatefilemanager.archive

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import com.google.android.material.textfield.TextInputLayout
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.util.DeviceUtils

/**
 * Dialog to prompt for archive password.
 */
class PasswordPromptDialog : DialogFragment() {

    private var onConfirm: ((String) -> Unit)? = null
    private var onCancel: (() -> Unit)? = null

    fun setOnConfirm(listener: (String) -> Unit) {
        this.onConfirm = listener
    }

    fun setOnCancel(listener: () -> Unit) {
        this.onCancel = listener
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val isTv = DeviceUtils.isTvDevice(requireContext())
        val layoutRes = if (isTv) R.layout.dialog_password_prompt_tv else R.layout.dialog_password_prompt
        return inflater.inflate(layoutRes, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val edtPassword = view.findViewById<EditText>(R.id.edtPassword)
        val btnUnlock = view.findViewById<View>(R.id.btnUnlock)
        val btnCancel = view.findViewById<View>(R.id.btnCancel)
        val tilPassword = view.findViewById<TextInputLayout>(R.id.tilPassword)

        btnCancel.setOnClickListener {
            onCancel?.invoke()
            dismiss()
        }

        btnUnlock.setOnClickListener {
            val password = edtPassword.text.toString()
            if (password.isEmpty()) {
                if (tilPassword != null) tilPassword.error = getString(R.string.compress_password_hint)
                else Toast.makeText(context, R.string.compress_password_hint, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            onConfirm?.invoke(password)
            dismiss()
        }

        za.kilowatch.ultimatefilemanager.util.DialogInputHelper.setupDialogFragmentInput(this, edtPassword) {
            btnUnlock.performClick()
        }
    }

    companion object {
        const val TAG = "PasswordPromptDialog"
    }
}
