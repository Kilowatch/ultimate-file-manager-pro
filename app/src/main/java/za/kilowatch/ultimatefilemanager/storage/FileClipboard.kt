package za.kilowatch.ultimatefilemanager.storage

import java.io.File
import za.kilowatch.ultimatefilemanager.network.NetworkFile

/**
 * Singleton clipboard for file copy/move/extract operations.
 * Supports multiple clipboard slots with independent operations and mixed local + remote items.
 */
object FileClipboard {
    const val MAX_SLOTS = 10

    enum class Operation { COPY, MOVE, EXTRACT }

    sealed class ClipItem {
        abstract val name: String
        abstract val isDirectory: Boolean
        abstract val operation: Operation

        data class Local(
            val file: File,
            override val operation: Operation
        ) : ClipItem() {
            override val name: String get() = file.name
            override val isDirectory: Boolean get() = file.isDirectory
        }

        data class Remote(
            val file: NetworkFile,
            override val operation: Operation,
            val sourceShareId: String,
            val sourceRemotePath: String = ""
        ) : ClipItem() {
            override val name: String get() = file.name
            override val isDirectory: Boolean get() = file.isDirectory
        }
    }

    data class Entry(val file: File, val operation: Operation)

    data class Slot(
        val id: Long,
        var label: String,
        val items: MutableList<ClipItem>
    ) {
        val localFiles: List<File> get() = items.filterIsInstance<ClipItem.Local>().map { it.file }
        val remoteEntries: List<ClipItem.Remote> get() = items.filterIsInstance<ClipItem.Remote>()
        val totalCount: Int get() = items.size
        val hasLocal: Boolean get() = items.any { it is ClipItem.Local }
        val hasRemote: Boolean get() = items.any { it is ClipItem.Remote }
        val isExtract: Boolean get() = items.any { it.operation == Operation.EXTRACT }
    }

    private val _slots: MutableList<Slot> = mutableListOf()
    val slots: List<Slot> get() = _slots.toList()

    /** Listener notified whenever items or slots in FileClipboard change. */
    fun interface ClipboardChangeListener {
        fun onClipboardChanged()
    }

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<ClipboardChangeListener>()

    fun addListener(listener: ClipboardChangeListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: ClipboardChangeListener) {
        listeners.remove(listener)
    }

    /** Fast in-memory cache of local file paths -> operation */
    private val localClipMap = java.util.concurrent.ConcurrentHashMap<String, Operation>()
    /** Fast in-memory cache of "$shareId:$remotePath" -> operation */
    private val remoteClipMap = java.util.concurrent.ConcurrentHashMap<String, Operation>()

    private fun rebuildIndexAndNotify() {
        localClipMap.clear()
        remoteClipMap.clear()
        for (slot in _slots) {
            for (item in slot.items) {
                when (item) {
                    is ClipItem.Local -> localClipMap[item.file.absolutePath] = item.operation
                    is ClipItem.Remote -> remoteClipMap["${item.sourceShareId}:${item.file.path}"] = item.operation
                }
            }
        }
        val mainLooper = android.os.Looper.getMainLooper()
        if (android.os.Looper.myLooper() == mainLooper) {
            listeners.forEach { it.onClipboardChanged() }
        } else {
            android.os.Handler(mainLooper).post {
                listeners.forEach { it.onClipboardChanged() }
            }
        }
    }

    /** Returns the staged operation (COPY / MOVE / EXTRACT) if [file] is in clipboard, or null. */
    fun getLocalOperation(file: File): Operation? = localClipMap[file.absolutePath]

    /** Returns the staged operation if network file is in clipboard, or null. */
    fun getRemoteOperation(shareId: String, path: String): Operation? = remoteClipMap["$shareId:$path"]

    /**
     * Checks if any staged clipboard item originated from [path] (or [shareId] if remote).
     * Used by tab pills and breadcrumbs to display indicator badges.
     */
    fun hasItemsFromPath(path: String, shareId: String? = null): Operation? {
        if (path.isEmpty()) return null
        if (shareId != null) {
            val prefix = if (path.endsWith("/")) path else "$path/"
            for (slot in _slots) {
                for (item in slot.items) {
                    if (item is ClipItem.Remote && item.sourceShareId == shareId) {
                        val itemPath = item.file.path
                        if (itemPath == path || itemPath.startsWith(prefix) || item.sourceRemotePath == path) {
                            return item.operation
                        }
                    }
                }
            }
        } else {
            val cleanPath = if (path.endsWith(File.separator)) path else path + File.separator
            for (slot in _slots) {
                for (item in slot.items) {
                    if (item is ClipItem.Local) {
                        val itemPath = item.file.absolutePath
                        if (itemPath == path || itemPath.startsWith(cleanPath) || item.file.parent == path) {
                            return item.operation
                        }
                    }
                }
            }
        }
        return null
    }

    /** Temporary directory for staged extraction files awaiting folder selection. */
    var activeTempExtractDir: File? = null
        private set

    // Backwards-compatibility aliases
    val entries: List<Entry> get() = _slots.flatMap { slot ->
        slot.items.filterIsInstance<ClipItem.Local>().map { Entry(it.file, it.operation) }
    }
    val files: List<File> get() = _slots.flatMap { it.localFiles }
    val operation: Operation get() = _slots.firstOrNull()?.items?.firstOrNull()?.operation ?: Operation.COPY

    /** Returns true if slot limit reached (10 slots) */
    val isFull: Boolean get() = _slots.size >= MAX_SLOTS

    /** Returns the most recently active slot */
    fun getRecentSlot(): Slot? = _slots.lastOrNull()

    /**
     * Push a new slot for local files. Returns false if already at MAX_SLOTS.
     */
    fun pushLocalSlot(selectedFiles: List<File>, op: Operation, sourceDir: String = ""): Boolean {
        if (isFull) return false
        if (op != Operation.EXTRACT) {
            clearTempDir()
        }
        val folderName = if (sourceDir.isNotEmpty()) {
            File(sourceDir).name.ifEmpty { sourceDir }
        } else {
            selectedFiles.firstOrNull()?.parentFile?.name ?: "Files"
        }
        val opLabel = when (op) {
            Operation.COPY -> "COPY"
            Operation.MOVE -> "CUT"
            Operation.EXTRACT -> "EXTRACT"
        }
        val label = "$folderName · $opLabel"
        val items = selectedFiles.map<File, ClipItem> { ClipItem.Local(it, op) }.toMutableList()
        val slot = Slot(System.currentTimeMillis(), label, items)
        _slots.add(slot)
        rebuildIndexAndNotify()
        return true
    }

    /**
     * Push a new slot for network files. Returns false if already at MAX_SLOTS.
     */
    fun pushRemoteSlot(
        items: List<NetworkFile>,
        op: Operation,
        shareId: String,
        sourceRemotePath: String = "",
        customLabel: String = ""
    ): Boolean {
        if (isFull) return false
        val folderName = when {
            customLabel.isNotEmpty() -> customLabel
            sourceRemotePath.isNotEmpty() -> sourceRemotePath.trimEnd('/').substringAfterLast('/').ifEmpty { "Network" }
            else -> items.firstOrNull()?.name ?: "Network"
        }
        val opLabel = when (op) {
            Operation.COPY -> "COPY"
            Operation.MOVE -> "CUT"
            Operation.EXTRACT -> "EXTRACT"
        }
        val label = "$folderName · $opLabel"
        val clipItems = items.map<NetworkFile, ClipItem> { ClipItem.Remote(it, op, shareId, sourceRemotePath) }.toMutableList()
        val slot = Slot(System.currentTimeMillis(), label, clipItems)
        _slots.add(slot)
        rebuildIndexAndNotify()
        return true
    }

    /**
     * Appends local files to an existing slot, replacing if path already exists.
     */
    fun addLocalToSlot(slotId: Long, selectedFiles: List<File>, op: Operation) {
        val slot = _slots.find { it.id == slotId } ?: run {
            pushLocalSlot(selectedFiles, op)
            return
        }
        val newPaths = selectedFiles.map { it.absolutePath }.toSet()
        slot.items.removeAll { it is ClipItem.Local && it.file.absolutePath in newPaths }
        selectedFiles.forEach { slot.items.add(ClipItem.Local(it, op)) }
        if (slot.hasLocal && slot.hasRemote) {
            slot.label = "Combined (${slot.totalCount} items)"
        }
        rebuildIndexAndNotify()
    }

    /**
     * Appends remote files to an existing slot, replacing if path already exists.
     */
    fun addRemoteToSlot(
        slotId: Long,
        selectedFiles: List<NetworkFile>,
        op: Operation,
        shareId: String,
        sourceRemotePath: String = ""
    ) {
        val slot = _slots.find { it.id == slotId } ?: run {
            pushRemoteSlot(selectedFiles, op, shareId, sourceRemotePath)
            return
        }
        val newPaths = selectedFiles.map { it.path }.toSet()
        slot.items.removeAll { it is ClipItem.Remote && it.file.path in newPaths }
        selectedFiles.forEach { slot.items.add(ClipItem.Remote(it, op, shareId, sourceRemotePath)) }
        if (slot.hasLocal && slot.hasRemote) {
            slot.label = "Combined (${slot.totalCount} items)"
        }
        rebuildIndexAndNotify()
    }

    /**
     * Removes a specific slot by id.
     */
    fun removeSlot(slotId: Long) {
        _slots.removeAll { it.id == slotId }
        if (_slots.isEmpty()) {
            clearTempDir()
        }
        rebuildIndexAndNotify()
    }

    /**
     * Removes a specific item from a slot.
     */
    fun removeItem(slotId: Long, item: ClipItem) {
        val slot = _slots.find { it.id == slotId } ?: return
        slot.items.remove(item)
        if (slot.items.isEmpty()) {
            removeSlot(slotId)
        } else {
            rebuildIndexAndNotify()
        }
    }

    /** Legacy single-slot set - clears and pushes 1 local slot */
    fun set(selectedFiles: List<File>, op: Operation) {
        clear()
        pushLocalSlot(selectedFiles, op)
    }

    /** Legacy add - adds to most recent slot or pushes new */
    fun add(selectedFiles: List<File>, op: Operation) {
        val recent = getRecentSlot()
        if (recent != null) {
            addLocalToSlot(recent.id, selectedFiles, op)
        } else {
            pushLocalSlot(selectedFiles, op)
        }
    }

    /** Sets clipboard for archive extraction */
    fun setExtract(extractedFiles: List<File>, tempDir: File) {
        clear()
        activeTempExtractDir = tempDir
        val items = extractedFiles.map<File, ClipItem> { ClipItem.Local(it, Operation.EXTRACT) }.toMutableList()
        val slot = Slot(System.currentTimeMillis(), "Extract (${extractedFiles.size} items)", items)
        _slots.add(slot)
        rebuildIndexAndNotify()
    }

    fun clearTempDir() {
        activeTempExtractDir?.let { dir ->
            try {
                if (dir.exists()) dir.deleteRecursively()
            } catch (_: Exception) {}
        }
        activeTempExtractDir = null
    }

    fun remove(file: File) {
        for (slot in _slots.toList()) {
            slot.items.removeAll { it is ClipItem.Local && it.file.absolutePath == file.absolutePath }
            if (slot.items.isEmpty()) {
                _slots.remove(slot)
            }
        }
        if (_slots.isEmpty()) {
            clearTempDir()
        }
        rebuildIndexAndNotify()
    }

    fun removeNetwork(file: NetworkFile) {
        for (slot in _slots.toList()) {
            slot.items.removeAll { it is ClipItem.Remote && it.file.path == file.path }
            if (slot.items.isEmpty()) {
                _slots.remove(slot)
            }
        }
        if (_slots.isEmpty()) {
            clearTempDir()
        }
        rebuildIndexAndNotify()
    }

    fun hasItems(): Boolean = _slots.any { it.items.isNotEmpty() }

    fun totalItemCount(): Int = _slots.sumOf { it.items.size }

    fun clear() {
        _slots.clear()
        clearTempDir()
        rebuildIndexAndNotify()
    }
}
