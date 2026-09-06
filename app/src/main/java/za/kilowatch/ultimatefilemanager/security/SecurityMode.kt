package za.kilowatch.ultimatefilemanager.security

import androidx.annotation.StringRes
import za.kilowatch.ultimatefilemanager.R

/**
 * Mutually exclusive authentication modes for UFM App Lock on Mobile.
 */
enum class SecurityMode(val key: String, @StringRes val labelRes: Int) {
    NONE("none", R.string.settings_security_method_none),
    PIN("pin", R.string.settings_security_method_pin),
    PASSWORD("password", R.string.settings_security_method_password),
    BIOMETRIC("biometric", R.string.settings_security_method_biometric);

    companion object {
        fun fromKey(key: String?): SecurityMode {
            return entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: NONE
        }
    }
}
