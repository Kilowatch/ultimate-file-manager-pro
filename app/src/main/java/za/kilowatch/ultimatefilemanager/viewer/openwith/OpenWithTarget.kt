package za.kilowatch.ultimatefilemanager.viewer.openwith

import android.graphics.drawable.Drawable

/**
 * Represents an application target capable of handling an ACTION_VIEW intent.
 */
data class OpenWithTarget(
    val id: String, // "${packageName}/${activityName}"
    val label: String,
    val packageName: String,
    val activityName: String,
    val isSystemApp: Boolean = false,
    val isPreferred: Boolean = false,
    val disambiguation: String? = null,
    val badge: Drawable? = null,
    val isSystemChooserFallback: Boolean = false
) {
    companion object {
        const val ID_SYSTEM_CHOOSER = "ufm.system.chooser"

        fun createSystemChooserFallback(label: String): OpenWithTarget {
            return OpenWithTarget(
                id = ID_SYSTEM_CHOOSER,
                label = label,
                packageName = "",
                activityName = "",
                isSystemChooserFallback = true
            )
        }
    }
}

/**
 * UI State emitted by OpenWithResolver for observation by OpenWithBottomSheet / Dialog.
 */
sealed class OpenWithUiState {
    data object Loading : OpenWithUiState()
    data class Success(val targets: List<OpenWithTarget>) : OpenWithUiState()
    data object Empty : OpenWithUiState()
    data class Error(val message: String) : OpenWithUiState()
}
