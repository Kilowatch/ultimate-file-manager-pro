package za.kilowatch.ultimatefilemanager.flatview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import za.kilowatch.ultimatefilemanager.indexing.FileIndex
import za.kilowatch.ultimatefilemanager.indexing.FileIndexDao

data class FlatSubfolder(
    val folderPath: String,
    val subfolderDisplayName: String, // e.g. "Hello" or "Hello/World" (or "" for root category direct files)
    val files: List<FileIndex>
)

data class FlatRootCategory(
    val categoryPath: String,
    val categoryName: String, // e.g. "A Google Review" or "(Root)"
    val subfolders: List<FlatSubfolder>,
    val isRoot: Boolean
) {
    val allFiles: List<FileIndex> = subfolders.flatMap { it.files }
    val totalFiles: Int get() = allFiles.size
    val subfolderCount: Int get() = subfolders.count { it.subfolderDisplayName.isNotEmpty() }
}

data class FlatViewState(
    val isLoading: Boolean = true,
    val items: List<FlatViewItem> = emptyList(),
    val totalFiles: Int = 0,
    val totalFolders: Int = 0,
    val selectedPaths: Set<String> = emptySet(),
    val isEditMode: Boolean = false,
    val areAllSelected: Boolean = false,
    val filterQuery: String = "",
    val errorMessage: String? = null
)

class FlatViewViewModel(
    private val storageId: String,
    private val rootPath: String,
    private val fileIndexDao: FileIndexDao
) : ViewModel() {

    private val _state = MutableStateFlow(FlatViewState())
    val state: StateFlow<FlatViewState> = _state.asStateFlow()

    private var allCategories: List<FlatRootCategory> = emptyList()
    private var fileMap: Map<String, FileIndex> = emptyMap()
    private val expandedCategories = mutableSetOf<String>()
    private val expandedSubfolders = mutableSetOf<String>()
    private val selectedFilePaths = mutableSetOf<String>()

    fun loadData(excludeSystem: Boolean, defaultExpanded: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val files = fileIndexDao.getFlatViewFiles(storageId, excludeSystem)
                val normalizedRoot = rootPath.trimEnd('/')

                // Group files into Root Categories and nested Subfolders
                val categoryMap = mutableMapOf<String, MutableMap<String, MutableList<FileIndex>>>()
                val categoryIsRootMap = mutableMapOf<String, Boolean>()
                val categoryNameMap = mutableMapOf<String, String>()

                for (file in files) {
                    val folder = file.folderPath.trimEnd('/')
                    val relPath = if (folder.startsWith(normalizedRoot)) {
                        folder.removePrefix(normalizedRoot).trimStart('/')
                    } else {
                        folder.trimStart('/')
                    }

                    val catPath: String
                    val catName: String
                    val isRoot: Boolean
                    val subDisplayName: String

                    if (relPath.isEmpty()) {
                        catPath = normalizedRoot
                        catName = "(Root)"
                        isRoot = true
                        subDisplayName = ""
                    } else {
                        val segments = relPath.split('/')
                        val topFolder = segments[0]
                        catPath = "$normalizedRoot/$topFolder"
                        catName = topFolder
                        isRoot = false
                        subDisplayName = if (segments.size > 1) {
                            segments.drop(1).joinToString("/")
                        } else {
                            ""
                        }
                    }

                    categoryIsRootMap[catPath] = isRoot
                    categoryNameMap[catPath] = catName

                    val subfolderMap = categoryMap.getOrPut(catPath) { mutableMapOf() }
                    val fileList = subfolderMap.getOrPut(folder) { mutableListOf() }
                    fileList.add(file)
                }

                val categories = categoryMap.map { (catPath, subMap) ->
                    val isRoot = categoryIsRootMap[catPath] ?: false
                    val catName = categoryNameMap[catPath] ?: catPath
                    val subfolders = subMap.map { (folderPath, folderFiles) ->
                        val subDisplayName = if (folderPath == catPath) {
                            ""
                        } else {
                            val rel = folderPath.removePrefix(catPath).trimStart('/')
                            rel
                        }
                        FlatSubfolder(
                            folderPath = folderPath,
                            subfolderDisplayName = subDisplayName,
                            files = folderFiles
                        )
                    }.sortedWith(compareBy({ it.subfolderDisplayName.isNotEmpty() }, { it.subfolderDisplayName.lowercase() }))

                    FlatRootCategory(
                        categoryPath = catPath,
                        categoryName = catName,
                        subfolders = subfolders,
                        isRoot = isRoot
                    )
                }.sortedWith(compareBy({ !it.isRoot }, { it.categoryName.lowercase() }))

                val fileIndexMap = LinkedHashMap<String, FileIndex>(files.size)
                for (file in files) {
                    fileIndexMap[file.path] = file
                }
                fileMap = fileIndexMap
                allCategories = categories
                expandedCategories.clear()
                expandedSubfolders.clear()

                if (defaultExpanded) {
                    categories.forEach { cat ->
                        expandedCategories.add(cat.categoryPath)
                        cat.subfolders.forEach { sub ->
                            expandedSubfolders.add(sub.folderPath)
                        }
                    }
                }

                val totalFileCount = files.size
                val totalFolderCount = categories.sumOf { it.subfolders.size }

                val flattened = buildFlattenedList(allCategories, _state.value.filterQuery)
                val allSelected = totalFileCount > 0 && selectedFilePaths.size == totalFileCount

                _state.update {
                    it.copy(
                        isLoading = false,
                        items = flattened,
                        totalFiles = totalFileCount,
                        totalFolders = totalFolderCount,
                        areAllSelected = allSelected
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = e.message ?: "Failed to load files"
                    )
                }
            }
        }
    }

    private fun buildFlattenedList(categories: List<FlatRootCategory>, query: String): List<FlatViewItem> {
        val result = ArrayList<FlatViewItem>()
        val trimmedQuery = query.trim().lowercase()

        for (category in categories) {
            val matchingCategoryFiles = if (trimmedQuery.isEmpty()) {
                category.allFiles
            } else {
                category.allFiles.filter {
                    it.filename.lowercase().contains(trimmedQuery) ||
                            category.categoryName.lowercase().contains(trimmedQuery)
                }
            }

            // Skip category if filtering and no files match
            if (trimmedQuery.isNotEmpty() && matchingCategoryFiles.isEmpty()) {
                continue
            }

            val isCatExpanded = (trimmedQuery.isNotEmpty() && matchingCategoryFiles.isNotEmpty()) ||
                    expandedCategories.contains(category.categoryPath)

            val allCatFilesSelected = matchingCategoryFiles.isNotEmpty() &&
                    matchingCategoryFiles.all { selectedFilePaths.contains(it.path) }

            result.add(
                FlatViewItem.RootCategoryHeader(
                    categoryPath = category.categoryPath,
                    categoryName = category.categoryName,
                    totalFiles = matchingCategoryFiles.size,
                    totalSubfolders = category.subfolderCount,
                    isExpanded = isCatExpanded,
                    isSelected = allCatFilesSelected,
                    isRoot = category.isRoot
                )
            )

            if (isCatExpanded) {
                for (subfolder in category.subfolders) {
                    val matchingSubFiles = if (trimmedQuery.isEmpty()) {
                        subfolder.files
                    } else {
                        subfolder.files.filter {
                            it.filename.lowercase().contains(trimmedQuery) ||
                                    subfolder.subfolderDisplayName.lowercase().contains(trimmedQuery) ||
                                    category.categoryName.lowercase().contains(trimmedQuery)
                        }
                    }

                    if (trimmedQuery.isNotEmpty() && matchingSubFiles.isEmpty()) {
                        continue
                    }

                    if (subfolder.subfolderDisplayName.isEmpty()) {
                        // Direct files in root category (no intermediate subfolder card)
                        for (file in matchingSubFiles) {
                            result.add(
                                FlatViewItem.FileEntry(
                                    fileIndex = file,
                                    folderPath = subfolder.folderPath,
                                    categoryPath = category.categoryPath,
                                    isSelected = selectedFilePaths.contains(file.path)
                                )
                            )
                        }
                    } else {
                        // Subfolder with clean relative name (e.g. "Hello")
                        val isSubExpanded = (trimmedQuery.isNotEmpty() && matchingSubFiles.isNotEmpty()) ||
                                expandedSubfolders.contains(subfolder.folderPath)
                        val allSubFilesSelected = matchingSubFiles.isNotEmpty() &&
                                matchingSubFiles.all { selectedFilePaths.contains(it.path) }

                        result.add(
                            FlatViewItem.SubfolderHeader(
                                folderPath = subfolder.folderPath,
                                categoryPath = category.categoryPath,
                                subfolderDisplayName = subfolder.subfolderDisplayName,
                                fileCount = matchingSubFiles.size,
                                isExpanded = isSubExpanded,
                                isSelected = allSubFilesSelected
                            )
                        )

                        if (isSubExpanded) {
                            for (file in matchingSubFiles) {
                                result.add(
                                    FlatViewItem.FileEntry(
                                        fileIndex = file,
                                        folderPath = subfolder.folderPath,
                                        categoryPath = category.categoryPath,
                                        isSelected = selectedFilePaths.contains(file.path)
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
        return result
    }

    fun toggleCategoryExpansion(categoryPath: String) {
        if (expandedCategories.contains(categoryPath)) {
            expandedCategories.remove(categoryPath)
        } else {
            expandedCategories.add(categoryPath)
        }
        val items = buildFlattenedList(allCategories, _state.value.filterQuery)
        _state.update { it.copy(items = items) }
    }

    fun toggleSubfolderExpansion(folderPath: String) {
        if (expandedSubfolders.contains(folderPath)) {
            expandedSubfolders.remove(folderPath)
        } else {
            expandedSubfolders.add(folderPath)
        }
        val items = buildFlattenedList(allCategories, _state.value.filterQuery)
        _state.update { it.copy(items = items) }
    }

    fun expandAll() {
        allCategories.forEach { cat ->
            expandedCategories.add(cat.categoryPath)
            cat.subfolders.forEach { sub ->
                expandedSubfolders.add(sub.folderPath)
            }
        }
        val items = buildFlattenedList(allCategories, _state.value.filterQuery)
        _state.update { it.copy(items = items) }
    }

    fun collapseAll() {
        expandedCategories.clear()
        expandedSubfolders.clear()
        val items = buildFlattenedList(allCategories, _state.value.filterQuery)
        _state.update { it.copy(items = items) }
    }

    fun setFilterQuery(query: String) {
        _state.update { it.copy(filterQuery = query) }
        val items = buildFlattenedList(allCategories, query)
        _state.update { it.copy(items = items) }
    }

    fun toggleFileSelection(path: String) {
        if (selectedFilePaths.contains(path)) {
            selectedFilePaths.remove(path)
        } else {
            selectedFilePaths.add(path)
        }
        syncSelectionState()
    }

    fun toggleCategorySelection(categoryPath: String, select: Boolean) {
        val cat = allCategories.find { it.categoryPath == categoryPath } ?: return
        if (select) {
            cat.allFiles.forEach { selectedFilePaths.add(it.path) }
        } else {
            cat.allFiles.forEach { selectedFilePaths.remove(it.path) }
        }
        syncSelectionState()
    }

    fun toggleSubfolderSelection(folderPath: String, select: Boolean) {
        val sub = allCategories.flatMap { it.subfolders }.find { it.folderPath == folderPath } ?: return
        if (select) {
            sub.files.forEach { selectedFilePaths.add(it.path) }
        } else {
            sub.files.forEach { selectedFilePaths.remove(it.path) }
        }
        syncSelectionState()
    }

    fun selectAll() {
        if (fileMap.isNotEmpty()) {
            selectedFilePaths.addAll(fileMap.keys)
        } else {
            allCategories.forEach { cat ->
                cat.allFiles.forEach { selectedFilePaths.add(it.path) }
            }
        }
        syncSelectionState()
    }

    fun invertSelection() {
        val allPaths = fileMap.keys
        val newSelected = mutableSetOf<String>()
        if (allPaths.isNotEmpty()) {
            for (p in allPaths) {
                if (!selectedFilePaths.contains(p)) {
                    newSelected.add(p)
                }
            }
        } else {
            val allFiles = allCategories.flatMap { it.allFiles }
            for (f in allFiles) {
                if (!selectedFilePaths.contains(f.path)) {
                    newSelected.add(f.path)
                }
            }
        }
        selectedFilePaths.clear()
        selectedFilePaths.addAll(newSelected)
        syncSelectionState()
    }

    fun deselectAll() {
        selectedFilePaths.clear()
        syncSelectionState()
    }

    fun clearSelection() {
        deselectAll()
    }

    fun setEditMode(enabled: Boolean) {
        if (!enabled) {
            selectedFilePaths.clear()
        }
        val items = buildFlattenedList(allCategories, _state.value.filterQuery)
        _state.update {
            it.copy(
                isEditMode = enabled,
                selectedPaths = selectedFilePaths.toSet(),
                areAllSelected = false,
                items = items
            )
        }
    }

    private fun syncSelectionState() {
        val count = selectedFilePaths.size
        // Auto-exit edit mode when everything is deselected!
        val isEdit = count > 0
        val totalFiles = _state.value.totalFiles
        val allSelected = totalFiles > 0 && count == totalFiles

        val items = buildFlattenedList(allCategories, _state.value.filterQuery)
        _state.update {
            it.copy(
                selectedPaths = selectedFilePaths.toSet(),
                isEditMode = isEdit,
                areAllSelected = allSelected,
                items = items
            )
        }
    }

    fun getSelectedFiles(): List<FileIndex> {
        val set = selectedFilePaths
        if (set.isEmpty()) return emptyList()
        val map = fileMap
        if (map.isNotEmpty()) {
            return set.mapNotNull { map[it] }
        }
        return allCategories.flatMap { it.allFiles }.filter { set.contains(it.path) }
    }

    class Factory(
        private val storageId: String,
        private val rootPath: String,
        private val fileIndexDao: FileIndexDao
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return FlatViewViewModel(storageId, rootPath, fileIndexDao) as T
        }
    }
}
