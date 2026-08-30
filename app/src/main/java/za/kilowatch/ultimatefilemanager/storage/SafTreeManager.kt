package za.kilowatch.ultimatefilemanager.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import za.kilowatch.ultimatefilemanager.util.GoRoLog
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

object SafTreeManager {

    private const val PREFS_NAME = "ufm_saf_tree_prefs"

    // High-speed in-memory caches to eliminate IPC & traversal overhead
    private val docIdCache = ConcurrentHashMap<String, String>()
    private val docUriCache = ConcurrentHashMap<String, Uri>()
    private val treeUriCache = ConcurrentHashMap<String, Uri>()

    private val PROJECTION_FILES = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED
    )

    fun isSafPath(path: String): Boolean {
        return normalizePath(path).startsWith("saf://")
    }

    fun clearCache() {
        docIdCache.clear()
        docUriCache.clear()
        treeUriCache.clear()
    }

    fun invalidatePath(path: String) {
        val norm = normalizePath(path)
        docIdCache.remove(norm)
        docUriCache.remove(norm)
        treeUriCache.remove(norm)
        docIdCache.keys.filter { it.startsWith("$norm/") }.forEach { docIdCache.remove(it) }
        docUriCache.keys.filter { it.startsWith("$norm/") }.forEach { docUriCache.remove(it) }
        treeUriCache.keys.filter { it.startsWith("$norm/") }.forEach { treeUriCache.remove(it) }
    }

    fun saveTreePermission(context: Context, path: String, uri: Uri) {
        val norm = normalizePath(path)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(norm, uri.toString()).apply()
        invalidatePath(norm)
    }

    fun removeTreePermission(context: Context, path: String) {
        val norm = normalizePath(path)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(norm).apply()
        invalidatePath(norm)
    }

    fun hasAnyPersistedPermission(context: Context): Boolean {
        return try {
            context.contentResolver.persistedUriPermissions.any { it.isReadPermission }
        } catch (_: Exception) {
            false
        }
    }

    fun getGrantedPathsForStorage(context: Context, storageMountPath: String): List<String> {
        val cleanRoot = normalizePath(storageMountPath).trimEnd('/')
        val list = mutableListOf<String>()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        for ((path, uriStr) in prefs.all) {
            if (uriStr is String) {
                val norm = normalizePath(path)
                if (norm == cleanRoot || norm.startsWith("$cleanRoot/")) {
                    list.add(norm)
                }
            }
        }
        val locations = SafLocationRepository.getLocationsForStorage(context, storageMountPath)
        for (loc in locations) {
            val p = loc.getDisplayPath().trimEnd('/')
            if (p.isNotEmpty()) {
                list.add(p)
            }
        }
        return list.distinct()
    }

    fun removeTreePermissionAndLocation(context: Context, pathOrSafUri: String): Boolean {
        val norm = normalizePath(pathOrSafUri)
        val locations = SafLocationRepository.getLocations(context)
        val matchedLoc = locations.firstOrNull {
            it.treeUriString == norm || it.getDisplayPath() == norm || "saf://${it.id}" == norm || norm.endsWith("/${it.id}")
        }
        if (matchedLoc != null) {
            SafLocationRepository.removeLocation(context, matchedLoc.id)
        }
        val uriToRelease = getTreeUriForPath(context, norm)
        if (uriToRelease != null) {
            try {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.releasePersistableUriPermission(uriToRelease, flags)
            } catch (_: Exception) {}
        }
        removeTreePermission(context, norm)
        return true
    }

    fun hasTreePermissionForPath(context: Context, path: String): Boolean {
        val norm = normalizePath(path)
        if (norm.startsWith("saf://")) {
            val locationId = norm.removePrefix("saf://").substringBefore('/')
            val location = SafLocationRepository.getLocationById(context, locationId)
            return location != null
        }

        val entry = findMatchingTreeEntry(context, path) ?: return false
        val uri = Uri.parse(entry.second)
        val persisted = context.contentResolver.persistedUriPermissions
        return persisted.any { it.uri == uri && it.isReadPermission }
    }

    fun getTreeUriForPath(context: Context, path: String): Uri? {
        val norm = normalizePath(path)
        if (norm.startsWith("saf://")) {
            val locationId = norm.removePrefix("saf://").substringBefore('/')
            val location = SafLocationRepository.getLocationById(context, locationId) ?: return null
            return Uri.parse(location.treeUriString)
        }

        val entry = findMatchingTreeEntry(context, path) ?: return null
        return Uri.parse(entry.second)
    }

    /**
     * Resolves the root tree URI and target document ID for [path] using direct formulas
     * or single cursor queries, caching all discovered nodes.
     */
    fun resolveTreeAndDocId(context: Context, path: String): Pair<Uri, String>? {
        val norm = normalizePath(path)

        val cachedTree = treeUriCache[norm]
        val cachedDocId = docIdCache[norm]
        if (cachedTree != null && cachedDocId != null) {
            return Pair(cachedTree, cachedDocId)
        }

        if (norm.startsWith("saf://")) {
            val locationId = norm.removePrefix("saf://").substringBefore('/')
            val location = SafLocationRepository.getLocationById(context, locationId) ?: return null
            val treeUri = Uri.parse(location.treeUriString)
            val rootDocId = try {
                DocumentsContract.getTreeDocumentId(treeUri)
            } catch (_: Exception) {
                null
            } ?: try {
                DocumentsContract.getDocumentId(treeUri)
            } catch (_: Exception) {
                ""
            }

            val prefix = "saf://$locationId"
            treeUriCache[prefix] = treeUri
            docIdCache[prefix] = rootDocId
            docUriCache[prefix] = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId)

            if (norm == prefix) {
                return Pair(treeUri, rootDocId)
            }

            val relativeSubpath = norm.removePrefix("$prefix/").trim('/')
            if (relativeSubpath.isEmpty()) {
                return Pair(treeUri, rootDocId)
            }

            // Direct mapping for external storage documents (Internal storage, SD cards)
            if (treeUri.authority == "com.android.externalstorage.documents") {
                val targetDocId = if (rootDocId.endsWith(":")) {
                    "$rootDocId$relativeSubpath"
                } else if (rootDocId.contains(":")) {
                    "${rootDocId.substringBefore(':')}:$relativeSubpath"
                } else {
                    "primary:$relativeSubpath"
                }
                val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, targetDocId)
                treeUriCache[norm] = treeUri
                docIdCache[norm] = targetDocId
                docUriCache[norm] = docUri
                return Pair(treeUri, targetDocId)
            }

            // For other providers (Termux, Nextcloud, Downloads): walk segments using cursor queries
            val segments = relativeSubpath.split("/").filter { it.isNotEmpty() }
            var currentPath = prefix
            var currentDocId = rootDocId

            for (segment in segments) {
                val nextPath = "$currentPath/$segment"
                val existingDocId = docIdCache[nextPath]
                if (existingDocId != null) {
                    currentPath = nextPath
                    currentDocId = existingDocId
                    continue
                }

                var foundDocId: String? = null
                try {
                    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, currentDocId)
                    context.contentResolver.query(
                        childrenUri,
                        arrayOf(
                            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME
                        ),
                        null, null, null
                    )?.use { cursor ->
                        val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                        val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                        while (cursor.moveToNext()) {
                            val childId = cursor.getString(idCol)
                            val name = cursor.getString(nameCol)
                            if (name != null) {
                                val p = "$currentPath/$name"
                                treeUriCache[p] = treeUri
                                docIdCache[p] = childId
                                docUriCache[p] = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                                if (name == segment) {
                                    foundDocId = childId
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    GoRoLog.e("SafStorage", "Error querying child documents for $currentPath: ${e.message}", e)
                }

                if (foundDocId == null) {
                    return null
                }
                currentPath = nextPath
                currentDocId = foundDocId
            }

            return Pair(treeUri, currentDocId)
        }

        // Standard registered tree path
        val match = findMatchingTreeEntry(context, path) ?: return null
        val (registeredPath, uriString) = match
        val treeUri = Uri.parse(uriString)
        val rootDocId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (_: Exception) {
            ""
        }

        treeUriCache[registeredPath] = treeUri
        docIdCache[registeredPath] = rootDocId
        docUriCache[registeredPath] = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId)

        if (norm == registeredPath) {
            return Pair(treeUri, rootDocId)
        }

        val relativeSubpath = norm.removePrefix("$registeredPath/").trim('/')
        if (relativeSubpath.isEmpty()) {
            return Pair(treeUri, rootDocId)
        }

        if (treeUri.authority == "com.android.externalstorage.documents") {
            val targetDocId = if (rootDocId.endsWith(":")) {
                "$rootDocId$relativeSubpath"
            } else if (rootDocId.contains(":")) {
                "${rootDocId.substringBefore(':')}:$relativeSubpath"
            } else {
                "primary:$relativeSubpath"
            }
            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, targetDocId)
            treeUriCache[norm] = treeUri
            docIdCache[norm] = targetDocId
            docUriCache[norm] = docUri
            return Pair(treeUri, targetDocId)
        }

        // Other providers: walk segment by segment with cursor queries
        val segments = relativeSubpath.split("/").filter { it.isNotEmpty() }
        var currentPath = registeredPath
        var currentDocId = rootDocId

        for (segment in segments) {
            val nextPath = "$currentPath/$segment"
            val existingDocId = docIdCache[nextPath]
            if (existingDocId != null) {
                currentPath = nextPath
                currentDocId = existingDocId
                continue
            }

            var foundDocId: String? = null
            try {
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, currentDocId)
                context.contentResolver.query(
                    childrenUri,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME
                    ),
                    null, null, null
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    while (cursor.moveToNext()) {
                        val childId = cursor.getString(idCol)
                        val name = cursor.getString(nameCol)
                        if (name != null) {
                            val p = "$currentPath/$name"
                            treeUriCache[p] = treeUri
                            docIdCache[p] = childId
                            docUriCache[p] = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                            if (name == segment) {
                                foundDocId = childId
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                GoRoLog.e("SafStorage", "Error querying child documents for $currentPath: ${e.message}", e)
            }

            if (foundDocId == null) {
                return null
            }
            currentPath = nextPath
            currentDocId = foundDocId
        }

        return Pair(treeUri, currentDocId)
    }

    fun getDocumentUriForPath(context: Context, path: String): Uri? {
        val norm = normalizePath(path)
        val cached = docUriCache[norm]
        if (cached != null) return cached

        val (treeUri, docId) = resolveTreeAndDocId(context, norm) ?: return null
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        docUriCache[norm] = docUri
        return docUri
    }

    fun openInputStream(context: Context, path: String): InputStream? {
        val docUri = getDocumentUriForPath(context, path) ?: return null
        return try {
            context.contentResolver.openInputStream(docUri)
        } catch (e: Exception) {
            GoRoLog.e("SafStorage", "openInputStream failed for $path ($docUri): ${e.message}", e)
            null
        }
    }

    fun openOutputStream(context: Context, path: String, mode: String = "wt"): OutputStream? {
        val norm = normalizePath(path)
        val docUri = getDocumentUriForPath(context, norm)
        if (docUri != null) {
            try {
                val stream = context.contentResolver.openOutputStream(docUri, mode)
                if (stream != null) return stream
            } catch (e: Exception) {
                GoRoLog.d("SafStorage", "Direct openOutputStream failed for $path ($docUri): ${e.message}, attempting createFile")
            }
        }

        // File doesn't exist yet in the provider — create it in the parent folder
        val parent = norm.substringBeforeLast('/', "")
        val name = norm.substringAfterLast('/')
        if (parent.isNotEmpty() && name.isNotEmpty()) {
            val createdDoc = createFile(context, parent, name)
            if (createdDoc != null) {
                return try {
                    context.contentResolver.openOutputStream(createdDoc.uri, mode)
                } catch (e: Exception) {
                    GoRoLog.e("SafStorage", "openOutputStream failed for newly created $path (${createdDoc.uri}): ${e.message}", e)
                    null
                }
            }
        }
        return null
    }

    private fun findMatchingTreeEntry(context: Context, path: String): Pair<String, String>? {
        val norm = normalizePath(path)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val all = prefs.all
        var bestMatch: Pair<String, String>? = null
        for ((registeredPath, uriString) in all) {
            if (uriString is String) {
                val regNorm = normalizePath(registeredPath)
                if (norm == regNorm || norm.startsWith("$regNorm/")) {
                    if (bestMatch == null || regNorm.length > bestMatch.first.length) {
                        bestMatch = Pair(regNorm, uriString)
                    }
                }
            }
        }
        return bestMatch
    }

    fun createDocumentTreeIntent(path: String): Intent {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        intent.addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        )

        val docUri = getInitialUriForPath(path)
        if (docUri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, docUri)
        }
        return intent
    }

    private fun getInitialUriForPath(path: String): Uri? {
        val norm = normalizePath(path)
        val primarySub = when {
            norm.startsWith("/storage/emulated/0/") -> norm.removePrefix("/storage/emulated/0/")
            norm.startsWith("/sdcard/") -> norm.removePrefix("/sdcard/")
            norm == "/storage/emulated/0" || norm == "/sdcard" -> ""
            else -> null
        }

        if (primarySub != null) {
            val docId = if (primarySub.isEmpty()) "primary:" else "primary:$primarySub"
            return DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", docId)
        }

        val sdMatch = Regex("^/storage/([A-Fa-f0-9]{4}-[A-Fa-f0-9]{4})(?:/(.*))?").find(norm)
        if (sdMatch != null) {
            val uuid = sdMatch.groupValues[1]
            val sub = sdMatch.groupValues.getOrNull(2) ?: ""
            val docId = if (sub.isEmpty()) "$uuid:" else "$uuid:$sub"
            return DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", docId)
        }

        return null
    }

    /**
     * Lists files in a SAF directory with maximum performance using a single direct
     * ContentResolver query across all children, caching all document IDs and URIs.
     */
    fun listFiles(context: Context, path: String): List<File> {
        val norm = normalizePath(path)
        val (treeUri, parentDocId) = resolveTreeAndDocId(context, norm) ?: return emptyList()

        val results = mutableListOf<File>()
        try {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
            context.contentResolver.query(childrenUri, PROJECTION_FILES, null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                val modCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

                while (cursor.moveToNext()) {
                    val childDocId = cursor.getString(idCol) ?: continue
                    val name = cursor.getString(nameCol) ?: continue
                    val mime = cursor.getString(mimeCol) ?: ""
                    val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                    val size = if (sizeCol != -1 && !cursor.isNull(sizeCol)) cursor.getLong(sizeCol) else 0L
                    val lastMod = if (modCol != -1 && !cursor.isNull(modCol)) cursor.getLong(modCol) else 0L
                    val childDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childDocId)

                    val childPath = SafFile.combineSafPath(norm, name)
                    treeUriCache[childPath] = treeUri
                    docIdCache[childPath] = childDocId
                    docUriCache[childPath] = childDocUri

                    results.add(
                        SafFile(
                            parentPath = norm,
                            docName = name,
                            isDir = isDir,
                            docLength = size,
                            docLastModified = lastMod,
                            documentUri = childDocUri
                        )
                    )
                }
            }
        } catch (e: Exception) {
            GoRoLog.e("SafStorage", "Error listing SAF files for $norm: ${e.message}", e)
        }

        return results
    }

    /**
     * Fast child count query without full object instantiation or multi-query overhead.
     */
    fun getChildCount(context: Context, path: String): Int {
        val norm = normalizePath(path)
        val (treeUri, docId) = resolveTreeAndDocId(context, norm) ?: return 0
        return try {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            context.contentResolver.query(
                childrenUri,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                null, null, null
            )?.use { cursor ->
                cursor.count
            } ?: 0
        } catch (_: Exception) {
            0
        }
    }

    fun exists(context: Context, path: String): Boolean {
        val norm = normalizePath(path)
        val docUri = getDocumentUriForPath(context, norm) ?: return false
        return try {
            context.contentResolver.query(
                docUri,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                null, null, null
            )?.use { cursor ->
                cursor.moveToFirst()
            } == true
        } catch (_: Exception) {
            false
        }
    }

    fun delete(context: Context, path: String): Boolean {
        val norm = normalizePath(path)
        return try {
            val docUri = getDocumentUriForPath(context, norm) ?: return false
            var success = false
            try {
                success = DocumentsContract.deleteDocument(context.contentResolver, docUri)
            } catch (e: Exception) {
                GoRoLog.w("SafStorage", "deleteDocument failed for $norm: ${e.message}")
            }

            if (!success && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val parentPath = norm.substringBeforeLast('/', "")
                if (parentPath.isNotEmpty()) {
                    val parentDocUri = getDocumentUriForPath(context, parentPath)
                    if (parentDocUri != null) {
                        try {
                            success = DocumentsContract.removeDocument(context.contentResolver, docUri, parentDocUri)
                        } catch (e: Exception) {
                            GoRoLog.w("SafStorage", "removeDocument failed for $norm: ${e.message}")
                        }
                    }
                }
            }

            if (!success) {
                try {
                    success = DocumentFile.fromSingleUri(context, docUri)?.delete() == true
                } catch (_: Exception) {}
            }

            if (success) {
                invalidatePath(norm)
            }
            success
        } catch (e: Exception) {
            GoRoLog.e("SafStorage", "Failed to delete $norm: ${e.message}", e)
            false
        }
    }

    fun deleteRecursively(context: Context, path: String): Boolean {
        val norm = normalizePath(path)
        return try {
            if (delete(context, norm)) {
                return true
            }

            // If direct delete fails (e.g. non-empty folder on strict providers), recursively delete children
            val children = listFiles(context, norm)
            for (child in children) {
                if (child.isDirectory) {
                    deleteRecursively(context, child.absolutePath)
                } else {
                    delete(context, child.absolutePath)
                }
            }
            delete(context, norm)
        } catch (e: Exception) {
            GoRoLog.e("SafStorage", "Failed to deleteRecursively $norm: ${e.message}", e)
            false
        }
    }


    fun getFileSize(context: Context, path: String): Long {
        val norm = normalizePath(path)
        val docUri = getDocumentUriForPath(context, norm) ?: return -1L
        return try {
            context.contentResolver.query(
                docUri,
                arrayOf(DocumentsContract.Document.COLUMN_SIZE),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                    if (sizeIndex != -1 && !cursor.isNull(sizeIndex)) {
                        cursor.getLong(sizeIndex)
                    } else -1L
                } else -1L
            } ?: -1L
        } catch (_: Exception) {
            -1L
        }
    }

    fun isDirectory(context: Context, path: String): Boolean {
        val norm = normalizePath(path)
        val docUri = getDocumentUriForPath(context, norm) ?: return false
        return try {
            context.contentResolver.query(
                docUri,
                arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    if (mimeIndex != -1 && !cursor.isNull(mimeIndex)) {
                        cursor.getString(mimeIndex) == DocumentsContract.Document.MIME_TYPE_DIR
                    } else false
                } else false
            } ?: false
        } catch (_: Exception) {
            false
        }
    }

    fun createFolder(context: Context, path: String): Boolean {
        val norm = normalizePath(path)
        val parent = norm.substringBeforeLast('/', "")
        val name = norm.substringAfterLast('/')
        if (parent.isEmpty() || name.isEmpty()) return false
        return mkdir(context, parent, name)
    }

    fun mkdir(context: Context, parentPath: String, name: String): Boolean {
        val norm = normalizePath(parentPath)
        val parentDocUri = getDocumentUriForPath(context, norm) ?: return false
        return try {
            val newDocUri = DocumentsContract.createDocument(
                context.contentResolver,
                parentDocUri,
                DocumentsContract.Document.MIME_TYPE_DIR,
                name
            )
            if (newDocUri != null) {
                val newPath = SafFile.combineSafPath(norm, name)
                docUriCache[newPath] = newDocUri
                treeUriCache[newPath] = treeUriCache[norm] ?: Uri.parse(newDocUri.toString().substringBefore("/document/"))
                true
            } else false
        } catch (e: Exception) {
            GoRoLog.e("SafStorage", "Failed to mkdir $name in $norm: ${e.message}", e)
            false
        }
    }

    fun createFile(context: Context, parentPath: String, name: String, mimeType: String = "*/*"): DocumentFile? {
        val norm = normalizePath(parentPath)
        val parentDocUri = getDocumentUriForPath(context, norm) ?: return null
        val effectiveMime = if (mimeType == "*/*" || mimeType.isEmpty()) {
            val ext = name.substringAfterLast('.', "").lowercase()
            android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        } else mimeType

        return try {
            val newDocUri = DocumentsContract.createDocument(
                context.contentResolver,
                parentDocUri,
                effectiveMime,
                name
            )
            if (newDocUri != null) {
                val newPath = SafFile.combineSafPath(norm, name)
                docUriCache[newPath] = newDocUri
                treeUriCache[newPath] = treeUriCache[norm] ?: Uri.parse(newDocUri.toString().substringBefore("/document/"))
                DocumentFile.fromSingleUri(context, newDocUri)
            } else null
        } catch (e: Exception) {
            GoRoLog.e("SafStorage", "Failed to create file $name in $norm: ${e.message}", e)
            null
        }
    }

    fun rename(context: Context, path: String, newName: String): Boolean {
        val norm = normalizePath(path)
        return try {
            val docUri = getDocumentUriForPath(context, norm) ?: return false
            var renamedUri: Uri? = null
            try {
                renamedUri = DocumentsContract.renameDocument(context.contentResolver, docUri, newName)
            } catch (e: Exception) {
                GoRoLog.w("SafStorage", "DocumentsContract.renameDocument failed on $norm: ${e.message}, attempting fallback")
            }

            if (renamedUri != null) {
                invalidatePath(norm)
                val parent = norm.substringBeforeLast('/', "")
                val newPath = if (parent.isNotEmpty()) "$parent/$newName" else newName
                docUriCache[newPath] = renamedUri
                true
            } else {
                // Fallback: Copy to new file/folder and delete old document
                val parent = norm.substringBeforeLast('/', "")
                val isDir = isDirectory(context, norm)
                if (isDir) {
                    val newDir = mkdir(context, parent, newName)
                    if (newDir) {
                        val newPath = if (parent.isNotEmpty()) "$parent/$newName" else newName
                        val children = listFiles(context, norm)
                        var allCopied = true
                        for (child in children) {
                            val childName = child.name
                            if (!rename(context, child.absolutePath, childName)) {
                                allCopied = false
                            }
                        }
                        deleteRecursively(context, norm)
                        invalidatePath(norm)
                        true
                    } else false
                } else {
                    val newDocUri = createFile(context, parent, newName)
                    if (newDocUri != null) {
                        val copied = try {
                            val inStream = openInputStream(context, norm)
                            val outStream = openOutputStream(context, if (parent.isNotEmpty()) "$parent/$newName" else newName)
                            if (inStream != null && outStream != null) {
                                inStream.use { inp ->
                                    outStream.use { out ->
                                        inp.copyTo(out)
                                    }
                                }
                                true
                            } else false
                        } catch (_: Exception) { false }

                        if (copied) {
                            try {
                                DocumentsContract.deleteDocument(context.contentResolver, docUri)
                            } catch (_: Exception) {}
                            invalidatePath(norm)
                            val newPath = if (parent.isNotEmpty()) "$parent/$newName" else newName
                            docUriCache[newPath] = newDocUri.uri
                            true
                        } else {
                            try { DocumentsContract.deleteDocument(context.contentResolver, newDocUri.uri) } catch (_: Exception) {}
                            false
                        }
                    } else false
                }
            }

        } catch (e: Exception) {
            GoRoLog.e("SafStorage", "Failed to rename $norm to $newName: ${e.message}", e)
            false
        }
    }


    fun searchSaf(context: Context, rootPath: String, query: String, maxResults: Int = 500): List<File> {
        val result = mutableListOf<File>()
        val lowerQuery = query.lowercase()
        fun searchRecursively(currentPath: String) {
            if (result.size >= maxResults) return
            val children = listFiles(context, currentPath)
            for (child in children) {
                if (result.size >= maxResults) return
                if (child.name.lowercase().contains(lowerQuery)) {
                    result.add(child)
                }
                if (child.isDirectory) {
                    searchRecursively(child.absolutePath)
                }
            }
        }
        searchRecursively(rootPath)
        return result
    }

    fun walkSafTopDown(context: Context, rootPath: String, maxDepth: Int = 30): List<File> {
        val result = mutableListOf<File>()
        fun walk(currentPath: String, depth: Int) {
            if (depth > maxDepth) return
            val children = listFiles(context, currentPath)
            for (child in children) {
                result.add(child)
                if (child.isDirectory) {
                    walk(child.absolutePath, depth + 1)
                }
            }
        }
        walk(rootPath, 0)
        return result
    }

    fun normalizePath(path: String): String {
        return SafFile.cleanSafPath(path)
    }

    fun getSafChildPath(parentPath: String, childName: String): String {
        return SafFile.combineSafPath(parentPath, childName)
    }
}
