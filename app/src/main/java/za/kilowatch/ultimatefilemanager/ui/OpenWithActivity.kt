package za.kilowatch.ultimatefilemanager.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import za.kilowatch.ultimatefilemanager.settings.ThemeHelper
import za.kilowatch.ultimatefilemanager.storage.FileBrowserActivity
import za.kilowatch.ultimatefilemanager.storage.StorageBrowserActivity
import za.kilowatch.ultimatefilemanager.util.MimeTypeHelper
import za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID

/**
 * Universal dispatcher activity that receives ACTION_VIEW and ACTION_EDIT intents
 * from all external applications, enabling UFM to appear in the system "Open with" chooser
 * for documents, media, code/text, archives, directories, and arbitrary files.
 */
class OpenWithActivity : AppCompatActivity() {

    private lateinit var progressBar: ProgressBar
    private lateinit var txtStatus: TextView
    private lateinit var txtFileName: TextView

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContentView(R.layout.activity_open_with_bridge)

        val mainView = findViewById<View>(R.id.main)
        if (mainView != null) {
            ViewCompat.setOnApplyWindowInsetsListener(mainView) { v, insets ->
                val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
                insets
            }
        }

        progressBar = findViewById(R.id.progressBar)
        txtStatus = findViewById(R.id.txtStatus)
        txtFileName = findViewById(R.id.txtFileName)

        handleIncomingIntent()
    }

    private fun handleIncomingIntent() {
        val incomingIntent = intent ?: run {
            finish()
            return
        }

        val uri = extractUri(incomingIntent) ?: run {
            Toast.makeText(this, R.string.error_cannot_open_file, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val isEdit = incomingIntent.action == Intent.ACTION_EDIT
        val mimeType = incomingIntent.type ?: contentResolver.getType(uri) ?: "*/*"

        // 1. Check for Directory / Folder
        if (isDirectoryIntent(uri, mimeType)) {
            openDirectory(uri)
            return
        }

        // 2. Resolve metadata (display name, size)
        val fileMetadata = resolveFileMetadata(uri, mimeType)
        val fileName = fileMetadata.first
        val fileExtension = File(fileName).extension.lowercase()

        // 3. Check for Package Installation (APK / XAPK / APKS)
        if (isPackageFile(fileExtension, mimeType)) {
            val installIntent = Intent(this, PackageInstallerActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = uri
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(installIntent)
            finish()
            return
        }

        // 4. Try resolving to a direct local file
        val directLocalFile = tryResolveLocalFile(uri)
        if (directLocalFile != null && directLocalFile.exists()) {
            dispatchLocalFile(directLocalFile, uri, isEdit)
            return
        }

        // 5. External stream (ContentProvider / cloud attachment) -> Cache and dispatch
        txtFileName.text = fileName
        txtFileName.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val cachedFile = cacheStream(uri, fileName)
                withContext(Dispatchers.Main) {
                    dispatchLocalFile(cachedFile, uri, isEdit)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    AlertDialog.Builder(this@OpenWithActivity)
                        .setTitle(R.string.error_cannot_open_file)
                        .setMessage(e.message ?: getString(R.string.unknown_error))
                        .setPositiveButton(R.string.btn_ok) { _, _ -> finish() }
                        .setOnCancelListener { finish() }
                        .show()
                }
            }
        }
    }

    private fun extractUri(intent: Intent): Uri? {
        intent.data?.let { return it }
        intent.clipData?.let { clip ->
            if (clip.itemCount > 0) {
                clip.getItemAt(0).uri?.let { return it }
            }
        }
        @Suppress("DEPRECATION")
        val streamUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        if (streamUri != null) return streamUri

        val pathExtra = intent.getStringExtra(FileViewerRouter.EXTRA_FILE_PATH)
        if (!pathExtra.isNullOrEmpty()) {
            return Uri.fromFile(File(pathExtra))
        }

        return null
    }

    private fun isDirectoryIntent(uri: Uri, mimeType: String): Boolean {
        if (mimeType in setOf(
                "vnd.android.document/directory",
                "resource/folder",
                "inode/directory",
                "x-directory/normal"
            )
        ) {
            return true
        }
        if (uri.scheme == "file") {
            val path = uri.path
            if (path != null && File(path).isDirectory) {
                return true
            }
        }
        return false
    }

    private fun openDirectory(uri: Uri) {
        val targetPath = if (uri.scheme == "file") {
            uri.path ?: Environment.getExternalStorageDirectory().absolutePath
        } else {
            tryResolveLocalPath(uri) ?: Environment.getExternalStorageDirectory().absolutePath
        }

        val internalPath = Environment.getExternalStorageDirectory().absolutePath
        val intent = Intent(this, FileBrowserActivity::class.java).apply {
            putExtra(FileBrowserActivity.EXTRA_MOUNT_PATH, internalPath)
            putExtra(FileBrowserActivity.EXTRA_INITIAL_PATH, targetPath)
        }
        startActivity(intent)
        finish()
    }

    private fun isPackageFile(extension: String, mimeType: String): Boolean {
        return extension in setOf("apk", "xapk", "apks") ||
                mimeType == "application/vnd.android.package-archive"
    }

    private fun resolveFileMetadata(uri: Uri, mimeType: String): Pair<String, Long> {
        var fileName = ""
        var fileSize = 0L

        if (uri.scheme == "file") {
            val file = File(uri.path ?: "")
            fileName = file.name
            fileSize = file.length()
        } else if (uri.scheme == "content") {
            try {
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIdx >= 0) {
                            fileName = cursor.getString(nameIdx) ?: ""
                        }
                        val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (sizeIdx >= 0) {
                            fileSize = cursor.getLong(sizeIdx)
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        if (fileName.isBlank()) {
            val lastSegment = uri.lastPathSegment ?: ""
            fileName = if (lastSegment.contains("/")) lastSegment.substringAfterLast("/") else lastSegment
        }

        if (fileName.isBlank()) {
            fileName = "file_${System.currentTimeMillis()}"
            val ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
            if (!ext.isNullOrBlank()) {
                fileName += ".$ext"
            }
        }

        return Pair(fileName, fileSize)
    }

    private fun tryResolveLocalFile(uri: Uri): File? {
        if (uri.scheme == "file") {
            val path = uri.path ?: return null
            val file = File(path)
            if (file.exists()) return file
        }

        val resolvedPath = tryResolveLocalPath(uri) ?: return null
        val file = File(resolvedPath)
        return if (file.exists()) file else null
    }

    private fun tryResolveLocalPath(uri: Uri): String? {
        val uriStr = uri.toString()
        // Common ContentProvider document id formats (e.g. primary:Download/abc.pdf)
        val docId = uri.lastPathSegment ?: ""
        if (docId.startsWith("primary:")) {
            val relPath = docId.removePrefix("primary:")
            return File(Environment.getExternalStorageDirectory(), relPath).absolutePath
        }

        if (uriStr.contains("/storage/emulated/0/")) {
            val sub = uriStr.substring(uriStr.indexOf("/storage/emulated/0/"))
            return sub.substringBefore("?")
        }

        // Query MediaStore _data column if accessible
        try {
            val proj = arrayOf("_data")
            contentResolver.query(uri, proj, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex("_data")
                    if (idx >= 0) {
                        val path = cursor.getString(idx)
                        if (!path.isNullOrBlank()) return path
                    }
                }
            }
        } catch (_: Exception) {}

        return null
    }

    private suspend fun cacheStream(uri: Uri, fileName: String): File = withContext(Dispatchers.IO) {
        val cacheFolder = File(cacheDir, "external_open").apply { mkdirs() }
        cleanupOldCacheFiles(cacheFolder)

        val cleanName = fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val destFile = File(cacheFolder, "${UUID.randomUUID().toString().take(8)}_$cleanName")

        val inputStream: InputStream = contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException(getString(R.string.error_failed_open_uri))

        inputStream.use { input ->
            FileOutputStream(destFile).use { output ->
                input.copyTo(output)
            }
        }
        destFile
    }

    private fun cleanupOldCacheFiles(folder: File) {
        runCatching {
            val now = System.currentTimeMillis()
            val maxAge = 24 * 60 * 60 * 1000L // 24 hours
            folder.listFiles()?.forEach { f ->
                if (now - f.lastModified() > maxAge) {
                    f.delete()
                }
            }
        }
    }

    private fun dispatchLocalFile(file: File, originalUri: Uri, isEdit: Boolean) {
        val ext = file.extension.lowercase()
        if (FileViewerRouter.canOpenInternally(ext) || FileViewerRouter.isDotConfigFile(file.name)) {
            FileViewerRouter.openInBuiltInViewer(
                context = this,
                file = file,
                contentUri = originalUri,
                startInEditMode = isEdit,
                isExternal = true
            )
            finish()
            return
        }

        // File format is not supported by built-in viewers:
        // If file exists on device storage, open FileBrowserActivity focused on it
        val internalPath = Environment.getExternalStorageDirectory().absolutePath
        val parentPath = file.parentFile?.absolutePath ?: internalPath

        if (file.exists() && !file.absolutePath.startsWith(cacheDir.absolutePath)) {
            val intent = Intent(this, FileBrowserActivity::class.java).apply {
                putExtra(FileBrowserActivity.EXTRA_MOUNT_PATH, internalPath)
                putExtra(FileBrowserActivity.EXTRA_INITIAL_PATH, parentPath)
                putExtra(FileBrowserActivity.EXTRA_FOCUS_PATH, file.absolutePath)
            }
            startActivity(intent)
            finish()
            return
        }

        // For cached non-standard files, offer option to view as text or open UFM
        AlertDialog.Builder(this)
            .setTitle(file.name)
            .setMessage(R.string.open_in_ufm)
            .setPositiveButton(R.string.view_as_text) { _, _ ->
                FileViewerRouter.openInBuiltInViewer(
                    context = this,
                    file = file,
                    contentUri = originalUri,
                    startInEditMode = isEdit,
                    isExternal = true
                )
                finish()
            }
            .setNeutralButton(R.string.browse_storage) { _, _ ->
                val intent = Intent(this, StorageBrowserActivity::class.java)
                startActivity(intent)
                finish()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }
}
