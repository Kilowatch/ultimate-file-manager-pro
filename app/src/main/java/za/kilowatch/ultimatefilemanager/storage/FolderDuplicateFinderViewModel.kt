package za.kilowatch.ultimatefilemanager.storage

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for [FolderDuplicateFinderActivity].
 * Manages state flow for folder duplicate analysis surviving configuration changes.
 */
class FolderDuplicateFinderViewModel(app: Application) : AndroidViewModel(app) {

    private val engine = StorageAnalyzerEngine(za.kilowatch.ultimatefilemanager.settings.LocaleHelper.applyTo(app))

    private val _duplicateGroups = MutableStateFlow<List<DuplicateGroup>?>(null)
    val duplicateGroups: StateFlow<List<DuplicateGroup>?> = _duplicateGroups

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _isScanRunning = MutableStateFlow(false)
    val isScanRunning: StateFlow<Boolean> = _isScanRunning

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    fun scanFolder(storageId: String, folderPath: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _isScanRunning.value = true
            _error.value = null
            try {
                val dups = withContext(Dispatchers.IO) {
                    try {
                        val (_, storageType, _) = za.kilowatch.ultimatefilemanager.indexing.IndexingRepository.resolveStorageForPath(folderPath)
                        za.kilowatch.ultimatefilemanager.UfmApplication.indexingRepository.reindexFolder(folderPath, storageId, storageType, recursive = true)
                    } catch (e: Exception) {
                        za.kilowatch.ultimatefilemanager.util.GoRoLog.w("FolderDuplicateFinderViewModel", "Pre-scan reindex failed: ${e.message}")
                    }
                    engine.getDuplicateGroupsReportForFolder(storageId, folderPath)
                }
                _duplicateGroups.value = dups
            } catch (e: Exception) {
                _error.value = e.message
                _duplicateGroups.value = emptyList()
            } finally {
                _isScanRunning.value = false
                _isLoading.value = false
            }
        }
    }
}
