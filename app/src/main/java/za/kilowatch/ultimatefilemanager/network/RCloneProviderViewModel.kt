package za.kilowatch.ultimatefilemanager.network

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ViewModel for [RCloneProviderActivity].
 *
 * Holds the currently selected provider ID and survives configuration changes
 * (e.g. device rotation) so the selection is preserved when the Activity
 * is recreated.
 */
class RCloneProviderViewModel : ViewModel() {

    private val _selectedProviderId = MutableStateFlow<String?>(null)
    val selectedProviderId: StateFlow<String?> = _selectedProviderId.asStateFlow()

    /**
     * Updates the selected provider ID. Called when the user taps a provider
     * in either the mobile RecyclerView or the TV chip layout.
     */
    fun selectProvider(id: String?) {
        _selectedProviderId.value = id
    }
}
