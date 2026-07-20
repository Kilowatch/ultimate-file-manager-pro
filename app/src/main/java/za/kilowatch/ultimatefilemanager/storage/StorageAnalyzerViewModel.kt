package za.kilowatch.ultimatefilemanager.storage

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * ViewModel for [StorageAnalyzerActivity].
 *
 * Manages a single [AnalyzerReport] state-flow. Survives configuration changes
 * so the analysis result is not lost on screen rotation.
 */
class StorageAnalyzerViewModel(app: Application) : AndroidViewModel(app) {

    private val engine = StorageAnalyzerEngine(za.kilowatch.ultimatefilemanager.settings.LocaleHelper.applyTo(app))

    private val _report    = MutableStateFlow<AnalyzerReport?>(null)
    val report: StateFlow<AnalyzerReport?> = _report

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    /**
     * True while the Phase-2 full-hash duplicate verification pass is running.
     * Observed by [DuplicatesTabFragment] to show a "Verifying content…" progress bar.
     */
    private val _isDuplicateScanRunning = MutableStateFlow(false)
    val isDuplicateScanRunning: StateFlow<Boolean> = _isDuplicateScanRunning

    /**
     * Kick off analysis for [storageId] / [storagePath].
     * If [isIndexed] is false, falls back to the filesystem-walk overview.
     */
    fun analyze(storageId: String, storagePath: File, isIndexed: Boolean) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value     = null
            try {
                if (isIndexed) {
                    // Stage 1: Initial report + stats (instant)
                    var currentReport = withContext(Dispatchers.IO) {
                        engine.createEmptyReport(storageId, storagePath)
                    }
                    _report.value = currentReport

                    // Stage 2: Categories (very fast)
                    val cats = withContext(Dispatchers.IO) {
                        engine.getCategories(storageId, currentReport.usedBytes)
                    }
                    currentReport = currentReport.copy(categoryBreakdown = cats)
                    _report.value = currentReport

                    // Stage 3: Overview accessories (Folders + Apps)
                    val folders = withContext(Dispatchers.IO) { engine.getTopFolders(storageId) }
                    val apps    = withContext(Dispatchers.IO) { engine.getAppUsage(storageId) }
                    currentReport = currentReport.copy(topFolders = folders, appUsage = apps)
                    _report.value = currentReport

                    // Stage 4: Specialized lists (Large Files + Junk + Old)
                    val large = withContext(Dispatchers.IO) { engine.getLargeFiles(storageId) }
                    val junk  = withContext(Dispatchers.IO) { engine.getJunk(storageId) }
                    val old   = withContext(Dispatchers.IO) { engine.getOldFiles(storageId) }
                    currentReport = currentReport.copy(largeFiles = large, junkReport = junk, oldFiles = old)
                    _report.value = currentReport

                    // Stage 5: Duplicates — two-phase (potentially heavy); signal the Duplicates tab
                    _isDuplicateScanRunning.value = true
                    val dups = withContext(Dispatchers.IO) { engine.getDuplicateGroupsReport(storageId) }
                    _isDuplicateScanRunning.value = false
                    val recs = engine.getRecommendations(dups, large, old, junk)
                    currentReport = currentReport.copy(duplicateGroups = dups, recommendations = recs)
                    _report.value = currentReport

                } else {
                    // Non-indexed: keep existing one-shot filesystem walk
                    val result = withContext(Dispatchers.IO) {
                        engine.runForNonIndexed(storagePath)
                    }
                    _report.value = result
                }
            } catch (e: Exception) {
                _isDuplicateScanRunning.value = false
                _error.value = e.message
            } finally {
                _isLoading.value = false
            }
        }
    }

    /** Clear the current report (e.g. when the user switches drives). */
    fun clearReport() {
        _report.value = null
        _isDuplicateScanRunning.value = false
    }
}

