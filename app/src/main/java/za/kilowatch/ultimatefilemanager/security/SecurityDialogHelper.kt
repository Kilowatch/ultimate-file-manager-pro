package za.kilowatch.ultimatefilemanager.security

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.remote.PinDialogHelper

/**
 * Helper object providing UFMStandard glass dialog flows for App Security:
 * - Method Picker (disabling biometrics if hardware or enrollment is missing)
 * - PIN & Password setup with irreversible warning
 * - One-time 16-character recovery key display with 60s clipboard auto-clear
 * - Old-credential verification before modification
 * - Active-credential verification before disabling
 * - Recovery key entry to reset forgotten credentials
 */
object SecurityDialogHelper {

    private var clipboardClearHandler: Handler? = null
    private var clipboardClearRunnable: Runnable? = null

    // ── Method Picker ────────────────────────────────────────────────────────

    fun showMethodPicker(
        context: Context,
        onModeSelected: (SecurityMode) -> Unit,
        onCancel: () -> Unit
    ) {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_security_method_picker, null)
        val dialog = MaterialAlertDialogBuilder(context, R.style.UFM_Dialog)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        val cardPin = dialogView.findViewById<MaterialCardView>(R.id.cardMethodPin)
        val cardPassword = dialogView.findViewById<MaterialCardView>(R.id.cardMethodPassword)
        val cardBiometric = dialogView.findViewById<MaterialCardView>(R.id.cardMethodBiometric)
        val txtBiometricSubtitle = dialogView.findViewById<TextView>(R.id.txtBiometricSubtitle)
        val imgBiometricChevron = dialogView.findViewById<ImageView>(R.id.imgBiometricChevron)
        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnPickerCancel)

        val secManager = AppSecurityManager.getInstance(context)
        val bioStatus = secManager.checkBiometricStatus(context)

        cardPin.setOnClickListener {
            dialog.dismiss()
            onModeSelected(SecurityMode.PIN)
        }

        cardPassword.setOnClickListener {
            dialog.dismiss()
            onModeSelected(SecurityMode.PASSWORD)
        }

        when (bioStatus) {
            AppSecurityManager.BiometricStatus.AVAILABLE -> {
                cardBiometric.alpha = 1.0f
                cardBiometric.isEnabled = true
                cardBiometric.setOnClickListener {
                    dialog.dismiss()
                    onModeSelected(SecurityMode.BIOMETRIC)
                }
            }
            AppSecurityManager.BiometricStatus.NONE_ENROLLED -> {
                cardBiometric.alpha = 0.45f
                cardBiometric.isEnabled = false
                imgBiometricChevron.visibility = View.GONE
                txtBiometricSubtitle.setText(R.string.settings_security_biometric_none_enrolled)
            }
            AppSecurityManager.BiometricStatus.UNAVAILABLE -> {
                cardBiometric.alpha = 0.45f
                cardBiometric.isEnabled = false
                imgBiometricChevron.visibility = View.GONE
                txtBiometricSubtitle.setText(R.string.settings_security_biometric_unavailable)
            }
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
            onCancel()
        }

        dialog.setOnCancelListener { onCancel() }
        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    }

    // ── PIN Setup Flow ───────────────────────────────────────────────────────

    fun showSetPinFlow(
        context: Context,
        scope: CoroutineScope,
        onConfirmed: () -> Unit,
        onCancel: () -> Unit
    ) {
        PinDialogHelper.showPinDialog(
            context = context,
            title = context.getString(R.string.set_access_pin),
            subtitle = context.getString(R.string.settings_security_pin_setup_subtitle),
            confirmText = context.getString(R.string.set_access_pin),
            onCancel = onCancel
        ) { enteredPin ->
            // Step 2: Confirm PIN
            PinDialogHelper.showPinDialog(
                context = context,
                title = context.getString(R.string.vault_confirm_pin_title),
                subtitle = context.getString(R.string.vault_confirm_pin_subtitle),
                confirmText = context.getString(R.string.vault_confirm_pin_title),
                onCancel = onCancel
            ) { confirmedPin ->
                if (enteredPin != confirmedPin) {
                    Toast.makeText(context, R.string.vault_pin_mismatch, Toast.LENGTH_SHORT).show()
                    showSetPinFlow(context, scope, onConfirmed, onCancel)
                    return@showPinDialog
                }

                // Step 3: Irreversible Warning
                showWarningDialog(context, onProceed = {
                    val secManager = AppSecurityManager.getInstance(context)
                    val recoveryKey = secManager.generateRecoveryKey()

                    // Step 4: Display Recovery Key with 60s auto-clear
                    showRecoveryDisplayDialog(context, recoveryKey) {
                        scope.launch {
                            secManager.savePin(enteredPin, recoveryKey)
                            withContext(Dispatchers.Main) {
                                onConfirmed()
                            }
                        }
                    }
                }, onCancel = onCancel)
            }
        }
    }

    // ── Password Setup Flow ──────────────────────────────────────────────────

    fun showSetPasswordFlow(
        context: Context,
        scope: CoroutineScope,
        onConfirmed: () -> Unit,
        onCancel: () -> Unit
    ) {
        showPasswordDialog(
            context = context,
            title = context.getString(R.string.settings_security_password_create_title),
            subtitle = context.getString(R.string.settings_security_password_create_subtitle),
            confirmText = context.getString(R.string.confirm),
            onCancel = onCancel
        ) { password ->
            if (password.length < 4) {
                Toast.makeText(context, R.string.theme_password_too_short, Toast.LENGTH_SHORT).show()
                showSetPasswordFlow(context, scope, onConfirmed, onCancel)
                return@showPasswordDialog
            }

            // Step 2: Confirm Password
            showPasswordDialog(
                context = context,
                title = context.getString(R.string.settings_security_password_confirm_title),
                subtitle = context.getString(R.string.settings_security_password_confirm_subtitle),
                confirmText = context.getString(R.string.confirm),
                onCancel = onCancel
            ) { confirmedPassword ->
                if (password != confirmedPassword) {
                    Toast.makeText(context, R.string.theme_password_mismatch, Toast.LENGTH_SHORT).show()
                    showSetPasswordFlow(context, scope, onConfirmed, onCancel)
                    return@showPasswordDialog
                }

                // Step 3: Irreversible Warning
                showWarningDialog(context, onProceed = {
                    val secManager = AppSecurityManager.getInstance(context)
                    val recoveryKey = secManager.generateRecoveryKey()

                    // Step 4: Display Recovery Key with 60s auto-clear
                    showRecoveryDisplayDialog(context, recoveryKey) {
                        scope.launch {
                            secManager.savePassword(password, recoveryKey)
                            withContext(Dispatchers.Main) {
                                onConfirmed()
                            }
                        }
                    }
                }, onCancel = onCancel)
            }
        }
    }

    // ── Modify PIN Flow (Verifies Old PIN First) ─────────────────────────────

    fun showModifyPinFlow(
        context: Context,
        scope: CoroutineScope,
        onConfirmed: () -> Unit,
        onCancel: () -> Unit
    ) {
        PinDialogHelper.showPinDialog(
            context = context,
            title = context.getString(R.string.vault_verify_pin_title),
            subtitle = context.getString(R.string.settings_security_verify_old_pin),
            confirmText = context.getString(R.string.confirm),
            onCancel = onCancel
        ) { oldPin ->
            scope.launch {
                val secManager = AppSecurityManager.getInstance(context)
                if (!secManager.verifyStoredCredential(oldPin)) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, R.string.vault_pin_invalid, Toast.LENGTH_SHORT).show()
                        showModifyPinFlow(context, scope, onConfirmed, onCancel)
                    }
                    return@launch
                }
                withContext(Dispatchers.Main) {
                    showSetPinFlow(context, scope, onConfirmed, onCancel)
                }
            }
        }
    }

    // ── Modify Password Flow (Verifies Old Password First) ───────────────────

    fun showModifyPasswordFlow(
        context: Context,
        scope: CoroutineScope,
        onConfirmed: () -> Unit,
        onCancel: () -> Unit
    ) {
        showPasswordDialog(
            context = context,
            title = context.getString(R.string.settings_security_verify_old_password_title),
            subtitle = context.getString(R.string.settings_security_verify_old_password_subtitle),
            confirmText = context.getString(R.string.confirm),
            onCancel = onCancel
        ) { oldPassword ->
            scope.launch {
                val secManager = AppSecurityManager.getInstance(context)
                if (!secManager.verifyStoredCredential(oldPassword)) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, R.string.security_unlock_invalid_password_short, Toast.LENGTH_SHORT).show()
                        showModifyPasswordFlow(context, scope, onConfirmed, onCancel)
                    }
                    return@launch
                }
                withContext(Dispatchers.Main) {
                    showSetPasswordFlow(context, scope, onConfirmed, onCancel)
                }
            }
        }
    }

    // ── Disable Security Confirmation Flow ──────────────────────────────────

    fun showDisableConfirmDialog(
        activity: AppCompatActivity,
        scope: CoroutineScope,
        onConfirmed: () -> Unit,
        onCancel: () -> Unit
    ) {
        val secManager = AppSecurityManager.getInstance(activity)
        val mode = secManager.getSecurityMode()

        when (mode) {
            SecurityMode.PIN -> {
                PinDialogHelper.showPinDialog(
                    context = activity,
                    title = activity.getString(R.string.settings_security_disable_pin_title),
                    subtitle = activity.getString(R.string.settings_security_disable_pin_subtitle),
                    confirmText = activity.getString(R.string.confirm),
                    onCancel = onCancel
                ) { enteredPin ->
                    scope.launch {
                        if (!secManager.verifyStoredCredential(enteredPin)) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(activity, R.string.vault_pin_invalid, Toast.LENGTH_SHORT).show()
                                onCancel()
                            }
                            return@launch
                        }
                        secManager.clearAllCredentials()
                        withContext(Dispatchers.Main) {
                            onConfirmed()
                        }
                    }
                }
            }
            SecurityMode.PASSWORD -> {
                showPasswordDialog(
                    context = activity,
                    title = activity.getString(R.string.settings_security_disable_password_title),
                    subtitle = activity.getString(R.string.settings_security_disable_password_subtitle),
                    confirmText = activity.getString(R.string.confirm),
                    onCancel = onCancel
                ) { enteredPassword ->
                    scope.launch {
                        if (!secManager.verifyStoredCredential(enteredPassword)) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(activity, R.string.security_unlock_invalid_password_short, Toast.LENGTH_SHORT).show()
                                onCancel()
                            }
                            return@launch
                        }
                        secManager.clearAllCredentials()
                        withContext(Dispatchers.Main) {
                            onConfirmed()
                        }
                    }
                }
            }
            SecurityMode.BIOMETRIC -> {
                val executor = ContextCompat.getMainExecutor(activity)
                val promptInfo = BiometricPrompt.PromptInfo.Builder()
                    .setTitle(activity.getString(R.string.settings_security_disable_biometric_title))
                    .setSubtitle(activity.getString(R.string.settings_security_disable_biometric_subtitle))
                    .apply {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
                        } else {
                            @Suppress("DEPRECATION")
                            setDeviceCredentialAllowed(true)
                        }
                    }
                    .build()

                BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        scope.launch {
                            secManager.clearAllCredentials()
                            withContext(Dispatchers.Main) {
                                onConfirmed()
                            }
                        }
                    }
                    override fun onAuthenticationError(code: Int, errString: CharSequence) {
                        onCancel()
                    }
                }).authenticate(promptInfo)
            }
            SecurityMode.NONE -> {
                scope.launch {
                    secManager.clearAllCredentials()
                    withContext(Dispatchers.Main) {
                        onConfirmed()
                    }
                }
            }
        }
    }

    // ── Recovery Key Entry Dialog (Forgot PIN/Password) ─────────────────────

    fun showRecoveryEntryDialog(
        activity: AppCompatActivity,
        scope: CoroutineScope,
        onKeyVerified: () -> Unit,
        onCancel: () -> Unit
    ) {
        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_security_recovery_entry, null)
        val dialog = MaterialAlertDialogBuilder(activity, R.style.UFM_Dialog)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        val etKey = dialogView.findViewById<TextInputEditText>(R.id.etRecoveryKey)
        val txtError = dialogView.findViewById<TextView>(R.id.txtRecoveryKeyError)
        val btnConfirm = dialogView.findViewById<MaterialButton>(R.id.btnConfirmRecoveryKey)
        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnCancelRecoveryKey)

        btnConfirm.setOnClickListener {
            val entered = etKey.text?.toString()?.replace("-", "")?.trim() ?: ""
            if (entered.length != 16) {
                txtError.setText(R.string.code_must_be_exactly_16)
                txtError.visibility = View.VISIBLE
                return@setOnClickListener
            }

            scope.launch {
                val secManager = AppSecurityManager.getInstance(activity)
                val isValid = secManager.verifyRecoveryKey(entered)
                withContext(Dispatchers.Main) {
                    if (isValid) {
                        dialog.dismiss()
                        onKeyVerified()
                    } else {
                        txtError.setText(R.string.vault_recovery_invalid)
                        txtError.visibility = View.VISIBLE
                    }
                }
            }
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
            onCancel()
        }

        dialog.setOnCancelListener { onCancel() }
        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    }

    // ── Shared Warning & Recovery Dialogs ────────────────────────────────────

    private fun showWarningDialog(
        context: Context,
        onProceed: () -> Unit,
        onCancel: () -> Unit
    ) {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_security_warning, null)
        val dialog = MaterialAlertDialogBuilder(context, R.style.UFM_Dialog)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        val btnConfirm = dialogView.findViewById<MaterialButton>(R.id.btnWarningConfirm)
        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnWarningCancel)

        btnConfirm.setOnClickListener {
            dialog.dismiss()
            onProceed()
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
            onCancel()
        }

        dialog.setOnCancelListener { onCancel() }
        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    }

    private fun showRecoveryDisplayDialog(
        context: Context,
        recoveryKey: String,
        onDone: () -> Unit
    ) {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_security_recovery_display, null)
        val dialog = MaterialAlertDialogBuilder(context, R.style.UFM_Dialog)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        val txtKey = dialogView.findViewById<TextView>(R.id.txtSecurityRecoveryKey)
        val btnCopy = dialogView.findViewById<MaterialButton>(R.id.btnCopyRecoveryKey)
        val btnDone = dialogView.findViewById<MaterialButton>(R.id.btnRecoveryDone)

        // Format 16 chars with hyphens: XXXX-XXXX-XXXX-XXXX
        val formattedKey = if (recoveryKey.length == 16) {
            recoveryKey.chunked(4).joinToString("-")
        } else {
            recoveryKey
        }
        txtKey.text = formattedKey

        btnCopy.setOnClickListener {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.recovery_code), recoveryKey))

            // Cancel any pending timer
            clipboardClearRunnable?.let { clipboardClearHandler?.removeCallbacks(it) }
            if (clipboardClearHandler == null) {
                clipboardClearHandler = Handler(Looper.getMainLooper())
            }

            // Schedule auto-clear in 60s
            val runnable = Runnable {
                cm?.setPrimaryClip(ClipData.newPlainText("", ""))
            }
            clipboardClearRunnable = runnable
            clipboardClearHandler?.postDelayed(runnable, 60_000L)

            Toast.makeText(context, R.string.security_recovery_code_copied_autoclear, Toast.LENGTH_LONG).show()
        }

        btnDone.setOnClickListener {
            dialog.dismiss()
            onDone()
        }

        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    }

    private fun showPasswordDialog(
        context: Context,
        title: String,
        subtitle: String,
        confirmText: String,
        onCancel: () -> Unit,
        onConfirmed: (String) -> Unit
    ) {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_security_password, null)
        val dialog = MaterialAlertDialogBuilder(context, R.style.UFM_Dialog)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        val txtTitle = dialogView.findViewById<TextView>(R.id.txtPasswordTitle)
        val txtSubtitle = dialogView.findViewById<TextView>(R.id.txtPasswordSubtitle)
        val etPassword = dialogView.findViewById<TextInputEditText>(R.id.etPassword)
        val btnConfirm = dialogView.findViewById<MaterialButton>(R.id.btnPasswordConfirm)
        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnPasswordCancel)

        txtTitle.text = title
        txtSubtitle.text = subtitle
        btnConfirm.text = confirmText

        btnConfirm.setOnClickListener {
            val pw = etPassword.text?.toString() ?: ""
            if (pw.isEmpty()) {
                etPassword.error = context.getString(R.string.field_cannot_be_empty)
                return@setOnClickListener
            }
            dialog.dismiss()
            onConfirmed(pw)
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
            onCancel()
        }

        dialog.setOnCancelListener { onCancel() }
        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    }
}
