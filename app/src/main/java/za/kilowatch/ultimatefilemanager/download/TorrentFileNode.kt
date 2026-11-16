package za.kilowatch.ultimatefilemanager.download

import org.libtorrent4j.Priority
import org.libtorrent4j.TorrentInfo
import java.util.concurrent.atomic.AtomicInteger

/**
 * Hierarchical tree node representing a file or directory within a torrent payload.
 * Supports recursive selection, child cascading, indeterminate parent state checks,
 * byte accumulation, and generation of [Priority] arrays for libtorrent.
 */
data class TorrentFileNode(
    val id: Int,
    val path: String,
    val name: String,
    var sizeBytes: Long,
    var isSelected: Boolean = true,
    val isDirectory: Boolean,
    val fileIndex: Int = -1,
    val children: MutableList<TorrentFileNode> = mutableListOf(),
    var isExpanded: Boolean = true
) {

    /**
     * Recursively sets selection for this node and all descendant nodes.
     */
    fun setSelectionRecursive(selected: Boolean) {
        isSelected = selected
        for (child in children) {
            child.setSelectionRecursive(selected)
        }
    }

    /**
     * Returns true if all leaf files under this node (or this leaf file itself) are selected.
     */
    fun isAllSelected(): Boolean {
        if (!isDirectory) return isSelected
        if (children.isEmpty()) return isSelected
        return children.all { it.isAllSelected() }
    }

    /**
     * Returns true if no leaf files under this node (or this leaf file itself) are selected.
     */
    fun isNoneSelected(): Boolean {
        if (!isDirectory) return !isSelected
        if (children.isEmpty()) return !isSelected
        return children.all { it.isNoneSelected() }
    }

    /**
     * Returns true if some, but not all, leaf files under this node are selected.
     */
    fun isPartiallySelected(): Boolean {
        return !isAllSelected() && !isNoneSelected()
    }

    /**
     * Calculates the sum of bytes for all selected leaf files under this node.
     */
    fun getSelectedBytes(): Long {
        if (!isDirectory) {
            return if (isSelected) sizeBytes else 0L
        }
        return children.sumOf { it.getSelectedBytes() }
    }

    /**
     * Returns the total count of leaf files under this node.
     */
    fun getLeafFileCount(): Int {
        if (!isDirectory) return 1
        return children.sumOf { it.getLeafFileCount() }
    }

    /**
     * Returns the count of selected leaf files under this node.
     */
    fun getSelectedLeafFileCount(): Int {
        if (!isDirectory) return if (isSelected) 1 else 0
        return children.sumOf { it.getSelectedLeafFileCount() }
    }

    /**
     * Collects all selected leaf file indices ([fileIndex] >= 0).
     */
    fun getSelectedFileIndices(destination: MutableSet<Int> = mutableSetOf()): Set<Int> {
        if (!isDirectory && fileIndex >= 0) {
            if (isSelected) {
                destination.add(fileIndex)
            }
        } else {
            for (child in children) {
                child.getSelectedFileIndices(destination)
            }
        }
        return destination
    }

    /**
     * Collects all leaf file indices ([fileIndex] >= 0) regardless of selection.
     */
    fun getAllFileIndices(destination: MutableSet<Int> = mutableSetOf()): Set<Int> {
        if (!isDirectory && fileIndex >= 0) {
            destination.add(fileIndex)
        } else {
            for (child in children) {
                child.getAllFileIndices(destination)
            }
        }
        return destination
    }

    /**
     * Builds an array of [Priority] elements of length [totalFiles] matching libtorrent's
     * file priority requirement. Selected files are assigned [Priority.DEFAULT], and
     * unselected files are assigned [Priority.IGNORE].
     */
    fun buildPriorityArray(totalFiles: Int): Array<Priority> {
        val selectedIndices = getSelectedFileIndices()
        return Array(totalFiles) { idx ->
            if (idx in selectedIndices) Priority.DEFAULT else Priority.IGNORE
        }
    }

    companion object {
        private val idGenerator = AtomicInteger(1)

        /**
         * Parses a [TorrentInfo] instance into a hierarchical root [TorrentFileNode].
         */
        fun fromTorrentInfo(torrentInfo: TorrentInfo): TorrentFileNode {
            val root = TorrentFileNode(
                id = idGenerator.getAndIncrement(),
                path = "",
                name = torrentInfo.name().ifBlank { "Torrent Contents" },
                sizeBytes = 0L,
                isSelected = true,
                isDirectory = true,
                fileIndex = -1
            )

            val numFiles = torrentInfo.numFiles()
            val fileStorage = torrentInfo.files()

            for (i in 0 until numFiles) {
                val rawPath = fileStorage.filePath(i)
                val normalizedPath = rawPath.replace('\\', '/').trimStart('/')
                val size = fileStorage.fileSize(i)

                val segments = normalizedPath.split('/')
                var currentNode = root

                var accumulatedPath = ""
                for (segIndex in 0 until segments.size - 1) {
                    val seg = segments[segIndex]
                    accumulatedPath = if (accumulatedPath.isEmpty()) seg else "$accumulatedPath/$seg"

                    var dirChild = currentNode.children.find { it.isDirectory && it.name == seg }
                    if (dirChild == null) {
                        dirChild = TorrentFileNode(
                            id = idGenerator.getAndIncrement(),
                            path = accumulatedPath,
                            name = seg,
                            sizeBytes = 0L,
                            isSelected = true,
                            isDirectory = true,
                            fileIndex = -1
                        )
                        currentNode.children.add(dirChild)
                    }
                    currentNode = dirChild
                }

                val leafName = segments.last()
                val leafNode = TorrentFileNode(
                    id = idGenerator.getAndIncrement(),
                    path = normalizedPath,
                    name = leafName,
                    sizeBytes = size,
                    isSelected = true,
                    isDirectory = false,
                    fileIndex = i
                )
                currentNode.children.add(leafNode)
            }

            // Post-process to calculate aggregated directory sizes
            fun computeDirectorySizes(node: TorrentFileNode): Long {
                if (!node.isDirectory) return node.sizeBytes
                var total = 0L
                for (child in node.children) {
                    total += computeDirectorySizes(child)
                }
                node.sizeBytes = total
                return total
            }
            computeDirectorySizes(root)

            // Sort children: directories first, then files alphabetically
            fun sortChildren(node: TorrentFileNode) {
                if (node.isDirectory) {
                    node.children.sortWith(compareBy<TorrentFileNode> { !it.isDirectory }.thenBy { it.name.lowercase() })
                    for (child in node.children) {
                        sortChildren(child)
                    }
                }
            }
            sortChildren(root)

            return root
        }
    }
}
