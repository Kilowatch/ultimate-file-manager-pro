package za.kilowatch.ultimatefilemanager.storage

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.UfmApplication
import za.kilowatch.ultimatefilemanager.indexing.FileIndex
import za.kilowatch.ultimatefilemanager.indexing.IndexingRepository
import za.kilowatch.ultimatefilemanager.util.GoRoLog

/**
 * ViewModel for [FolderLargeFilesFinderActivity].
 * Manages state flow for folder large files analysis surviving configuration changes.
 */
class FolderLargeFilesFinderViewModel(app: Application) : AndroidViewModel(app) {

    private val engine = StorageAnalyzerEngine(za.kilowatch.ultimatefilemanager.settings.LocaleHelper.applyTo(app))

    private val _largeFiles = MutableStateFlow<List<FileIndex>?>(null)
    val largeFiles: StateFlow<List<FileIndex>?> = _largeFiles

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
                val files = withContext(Dispatchers.IO) {
                    try {
                        val (_, storageType, _) = IndexingRepository.resolveStorageForPath(folderPath)
                        UfmApplication.indexingRepository.reindexFolder(folderPath, storageId, storageType, recursive = true)
                    } catch (e: Exception) {
                        GoRoLog.w("FolderLargeFilesViewModel", "Pre-scan reindex failed: ${e.message}")
                    }
                    engine.getLargestFilesReportForFolder(storageId, folderPath)
                }
                _largeFiles.value = files
            } catch (e: Exception) {
                _error.value = e.message
                _largeFiles.value = emptyList()
            } finally {
                _isScanRunning.value = false
                _isLoading.value = false
            }
        }
    }
}
