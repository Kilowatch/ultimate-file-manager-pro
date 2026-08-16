package za.kilowatch.ultimatefilemanager.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.File

object SafTreeManager {

    private const val PREFS_NAME = "ufm_saf_tree_prefs"

    fun saveTreePermission(context: Context, path: String, uri: Uri) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(normalizePath(path), uri.toString()).apply()
    }

    fun removeTreePermission(context: Context, path: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(normalizePath(path)).apply()
    }

    fun hasTreePermissionForPath(context: Context, path: String): Boolean {
        val entry = findMatchingTreeEntry(context, path) ?: return false
        val uri = Uri.parse(entry.second)
        val persisted = context.contentResolver.persistedUriPermissions
        return persisted.any { it.uri == uri && it.isReadPermission }
    }

    fun getTreeUriForPath(context: Context, path: String): Uri? {
        val entry = findMatchingTreeEntry(context, path) ?: return null
        return Uri.parse(entry.second)
    }

    private fun findMatchingTreeEntry(context: Context, path: String): Pair<String, String>? {
        val norm = normalizePath(path)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val all = prefs.all
        // Find the longest matching registered path prefix
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

    fun listFiles(context: Context, path: String): List<File> {
        val targetDoc = findDocumentFileForPath(context, path) ?: return emptyList()
        if (!targetDoc.isDirectory) return emptyList()

        val rawFiles = targetDoc.listFiles()
        return rawFiles.mapNotNull { doc ->
            val name = doc.name ?: return@mapNotNull null
            SafFile(
                parentPath = path,
                docName = name,
                isDir = doc.isDirectory,
                docLength = doc.length(),
                docLastModified = doc.lastModified(),
                documentUri = doc.uri
            )
        }
    }

    private fun findDocumentFileForPath(context: Context, path: String): DocumentFile? {
        val match = findMatchingTreeEntry(context, path) ?: return null
        val (registeredPath, uriString) = match
        val treeUri = Uri.parse(uriString)
        val rootDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return null

        val norm = normalizePath(path)
        if (norm == registeredPath) return rootDoc

        val relativeSubpath = norm.removePrefix("$registeredPath/").split("/").filter { it.isNotEmpty() }
        var current: DocumentFile = rootDoc
        for (segment in relativeSubpath) {
            val child = current.findFile(segment) ?: return null
            current = child
        }
        return current
    }

    fun exists(context: Context, path: String): Boolean {
        return findDocumentFileForPath(context, path)?.exists() == true
    }

    fun delete(context: Context, path: String): Boolean {
        return findDocumentFileForPath(context, path)?.delete() == true
    }

    fun mkdir(context: Context, parentPath: String, name: String): Boolean {
        val parentDoc = findDocumentFileForPath(context, parentPath) ?: return false
        return parentDoc.createDirectory(name) != null
    }

    private fun normalizePath(path: String): String {
        return path.trimEnd('/')
    }
}
