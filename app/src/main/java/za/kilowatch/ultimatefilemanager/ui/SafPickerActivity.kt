package za.kilowatch.ultimatefilemanager.ui

import za.kilowatch.ultimatefilemanager.util.safeDirectoryPath

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.network.NetworkShare
import za.kilowatch.ultimatefilemanager.network.NetworkShareRepository
import za.kilowatch.ultimatefilemanager.network.ShareType
import za.kilowatch.ultimatefilemanager.storage.StorageItem
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import za.kilowatch.ultimatefilemanager.util.FileTypeIconProvider
import java.io.File

import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.network.DlnaShareClient
import za.kilowatch.ultimatefilemanager.network.FtpShareClient
import za.kilowatch.ultimatefilemanager.network.NfsShareClient
import za.kilowatch.ultimatefilemanager.network.SmbShareClient
import za.kilowatch.ultimatefilemanager.network.TvShareClient

import za.kilowatch.ultimatefilemanager.util.GoRoLog
import za.kilowatch.ultimatefilemanager.settings.FontSizeHelper
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import android.webkit.MimeTypeMap
import coil.dispose
import coil.load
import coil.size.Scale
import za.kilowatch.ultimatefilemanager.settings.ThumbnailPreferenceManager
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job

/**
 * A custom document picker for UFM.
 * Used on devices that lack a system-level document browser (common on Android TV).
 * Handles ACTION_OPEN_DOCUMENT, ACTION_OPEN_DOCUMENT_TREE, ACTION_GET_CONTENT,
 * ACTION_PICK, and ACTION_CREATE_DOCUMENT.
 *
 * In CREATE_DOCUMENT mode the bottom bar shows a filename EditText (pre-filled from
 * EXTRA_TITLE) and a "Save Here" button.  When the user taps the button the activity
 * creates the file via the SAF DocumentsProvider and returns a writable URI.
 */
class SafPickerActivity : AppCompatActivity() {

    private lateinit var recyclerItems: RecyclerView
    private lateinit var txtTitle: TextView
    private lateinit var txtSubtitle: TextView
    private lateinit var layoutBottomAction: View
    private lateinit var btnSelect: View
    private lateinit var layoutEmpty: View
    private lateinit var progressBar: android.widget.ProgressBar
    private lateinit var layoutFilename: TextInputLayout
    private lateinit var editFilename: TextInputEditText

    private var isTv = false
    private var action: String? = null
    private var isTreeAction = false
    private var isCreateAction = false
    private var currentPath: String? = null // null means roots view
    private var currentShare: NetworkShare? = null
    private var requestedMimeTypes: List<String>? = null
    private var allowMultiple = false
    private val selectedFiles = mutableSetOf<String>()

    private val adapter = PickerAdapter()

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        isTv = DeviceUtils.isTvDevice(this)
        setContentView(if (isTv) R.layout.activity_saf_picker_tv else R.layout.activity_saf_picker)

        action = intent.action
        isTreeAction  = action == Intent.ACTION_OPEN_DOCUMENT_TREE
        isCreateAction = action == Intent.ACTION_CREATE_DOCUMENT

        // Extract requested MIME types from intent (supports both type field and EXTRA_MIME_TYPES)
        requestedMimeTypes = intent.getStringArrayListExtra(Intent.EXTRA_MIME_TYPES)?.takeIf { it.isNotEmpty() }
            ?: intent.type?.let { listOf(it) }
        allowMultiple = intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)

        GoRoLog.i("SafPickerActivity onCreate: action=$action, isTree=$isTreeAction, isCreate=$isCreateAction, allowMultiple=$allowMultiple")
        GoRoLog.i("GoRoSAF", "SafPickerActivity opened action=$action isTree=$isTreeAction isCreate=$isCreateAction")
        intent.extras?.let { extras ->
            GoRoLog.d("SafPickerActivity extras: ${extras.keySet().joinToString { "$it=${extras.get(it)}" }}")
            GoRoLog.d("GoRoSAF", "SafPickerActivity extras: ${extras.keySet().joinToString { "$it=${extras.get(it)}" }}")
        }

        setupViews()
        loadRoots()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentPath != null) {
                    if (currentShare != null && currentPath == "") {
                        loadRoots()
                    } else if (currentShare != null) {
                        val lastSlash = currentPath!!.lastIndexOf('/')
                        val parent = if (lastSlash > 0) currentPath!!.substring(0, lastSlash) else ""
                        loadDirectory(parent, currentShare)
                    } else {
                        val file = File(currentPath!!)
                        val parent = file.parentFile
                        if (parent != null && parent.exists() && canAccess(parent)) {
                            loadDirectory(parent.absolutePath)
                        } else {
                            loadRoots()
                        }
                    }
                } else {
                    finish()
                }
            }
        })
    }

    private fun setupViews() {
        recyclerItems = findViewById(R.id.recyclerItems)
        txtTitle = findViewById(R.id.txtTitle)
        txtSubtitle = findViewById(R.id.txtSubtitle)
        layoutBottomAction = findViewById(R.id.layoutBottomAction)
        btnSelect = findViewById(R.id.btnSelect)
        layoutEmpty = findViewById(R.id.layoutEmpty)
        progressBar = findViewById(R.id.progressBar)
        layoutFilename = findViewById(R.id.layoutFilename)
        editFilename = findViewById(R.id.editFilename)

        findViewById<View>(R.id.btnBack).setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        findViewById<View>(R.id.btnNewFolder).visibility = View.GONE // Keep it simple for now

        recyclerItems.layoutManager = LinearLayoutManager(this)
        recyclerItems.adapter = adapter

        // Pre-fill filename from the intent's suggested title
        if (isCreateAction) {
            val suggestedName = intent.getStringExtra(Intent.EXTRA_TITLE) ?: ""
            editFilename.setText(suggestedName)
            layoutFilename.visibility = View.VISIBLE
            (btnSelect as? com.google.android.material.button.MaterialButton)?.setText(R.string.save_here)
        }

        btnSelect.setOnClickListener {
            val path = currentPath ?: return@setOnClickListener
            if (isCreateAction) {
                // Create mode: combine folder + filename and return a writable URI
                val filename = editFilename.text?.toString()?.trim() ?: ""
                if (filename.isEmpty()) {
                    layoutFilename.error = getString(R.string.enter_a_file_name)
                    return@setOnClickListener
                }
                layoutFilename.error = null
                val fullFolderDocId = if (currentShare != null) {
                    currentShare!!.docIdPrefix + path
                } else {
                    path
                }
                createAndReturn(fullFolderDocId, filename)
            } else if (allowMultiple && selectedFiles.isNotEmpty()) {
                // Multi-select: return all selected files
                returnMultipleResults()
            } else {
                // Tree / folder-select mode
                val fullPath = if (currentShare != null) {
                    currentShare!!.docIdPrefix + path
                } else {
                    path
                }
                returnResult(fullPath, isTree = true)
            }
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    private fun canAccess(file: File): Boolean {
        return file.canRead() || file.listFiles() != null
    }

    private fun loadRoots() {
        currentPath = null
        currentShare = null
        txtTitle.text = if (isCreateAction) getString(R.string.save_to) else "Select Storage"
        txtSubtitle.setText(R.string.choose_a_volume_to_browse)
        layoutBottomAction.visibility = View.GONE
        progressBar.visibility = View.GONE

        val items = mutableListOf<PickerItem>()
        
        // Local volumes
        val storageManager = getSystemService(Context.STORAGE_SERVICE) as StorageManager
        for (volume in storageManager.storageVolumes) {
            val path = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                volume.safeDirectoryPath
            } else {
                try {
                    volume.javaClass.getMethod("getPath").invoke(volume) as? String
                } catch (_: Exception) { null }
            } ?: continue
            
            val label = when {
                volume.isPrimary -> getString(R.string.storage_internal)
                volume.isRemovable -> {
                    val desc = volume.getDescription(this).lowercase()
                    if (desc.contains("usb")) getString(R.string.storage_usb)
                    else getString(R.string.storage_sd_card)
                }
                else -> volume.getDescription(this)
            }
            
            items.add(PickerItem(
                label = label,
                iconRes = StorageItem.iconForType(volume.isRemovable, volume.getDescription(this) ?: ""),
                path = path,
                isRoot = true
            ))
        }

        // Network shares (create mode supports writing to SMB/FTP/TV via pipe/buffer)
        val repo = NetworkShareRepository.getInstance(this)
        for (share in repo.getAll()) {
            items.add(PickerItem(
                label = share.name,
                iconRes = R.drawable.ic_network,
                path = "", // Start at share root
                isRoot = true,
                share = share
            ))
        }

        adapter.submitList(items)
        layoutEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun loadDirectory(path: String, share: NetworkShare? = null) {
        currentPath = path
        currentShare = share
        
        if (share != null) {
            txtTitle.text = share.name
            txtSubtitle.text = if (path.isEmpty()) "/" else path
            browseNetwork(share, path)
        } else {
            val file = File(path)
            txtTitle.text = file.name.ifEmpty { "Root" }
            txtSubtitle.text = path
            browseLocal(file)
        }
        
        // Show the bottom action bar for tree-select, create, or multi-select
        val showBar = isTreeAction || isCreateAction || allowMultiple
        layoutBottomAction.visibility = if (showBar) View.VISIBLE else View.GONE
        if (allowMultiple && !isCreateAction) {
            (btnSelect as? com.google.android.material.button.MaterialButton)?.let { btn ->
                btn.text = if (selectedFiles.isEmpty()) getString(R.string.select) else "Select (${selectedFiles.size})"
            }
        }
    }

    private fun browseLocal(file: File) {
        val items = mutableListOf<PickerItem>()
        val files = file.listFiles()?.toList() ?: emptyList()
        
        files.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            .forEach { f ->
                val showFile = !isTreeAction || f.isDirectory
                val matchesMime = f.isDirectory || mimeMatchesAny(f.name)
                if (showFile && matchesMime) {
                    items.add(PickerItem(
                        label = f.name,
                        iconRes = if (f.isDirectory) R.drawable.ic_folder else FileTypeIconProvider.iconForFile(f),
                        path = f.absolutePath,
                        isDir = f.isDirectory
                    ))
                }
            }

        adapter.submitList(items)
        layoutEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        progressBar.visibility = View.GONE
    }

    private fun browseNetwork(share: NetworkShare, path: String) {
        progressBar.visibility = View.VISIBLE
        recyclerItems.visibility = View.GONE
        layoutEmpty.visibility = View.GONE

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val files = when (share.type) {
                    ShareType.SMB -> SmbShareClient.listFiles(share, path)
                    ShareType.FTP -> FtpShareClient.listFiles(share, path)
                    ShareType.TV  -> TvShareClient.listFiles(share, path)
                    ShareType.SFTP, ShareType.SCP -> za.kilowatch.ultimatefilemanager.network.SshShareClient.listFiles(share, path)
                    ShareType.ONEDRIVE -> za.kilowatch.ultimatefilemanager.network.OnedriveShareClient.listFiles(share, path)
                    ShareType.GOOGLE_DRIVE -> za.kilowatch.ultimatefilemanager.network.GoogleDriveShareClient.listFiles(share, path)
                    ShareType.DROPBOX -> za.kilowatch.ultimatefilemanager.network.DropboxShareClient.listFiles(share, path)
                    ShareType.AWS_S3, ShareType.IDRIVE_E2 -> za.kilowatch.ultimatefilemanager.network.S3ShareClient.listFiles(share, path)
                    ShareType.WEBDAV -> za.kilowatch.ultimatefilemanager.network.WebDavShareClient.listFiles(share, path)
                    ShareType.NFS -> NfsShareClient.listFiles(share, path)
                    ShareType.DLNA -> DlnaShareClient.listFiles(share, path)
                }
                
                val items = files.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                    .filter { (!isTreeAction || it.isDirectory) && (it.isDirectory || mimeMatchesAny(it.name)) }
                    .map { f ->
                        PickerItem(
                            label = f.name,
                            iconRes = if (f.isDirectory) R.drawable.ic_folder else FileTypeIconProvider.iconForExtension(f.name.substringAfterLast('.', "")),
                            path = f.path,
                            isDir = f.isDirectory
                        )
                    }

                withContext(Dispatchers.Main) {
                    adapter.submitList(items)
                    progressBar.visibility = View.GONE
                    recyclerItems.visibility = View.VISIBLE
                    layoutEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    layoutEmpty.visibility = View.VISIBLE
                }
            }
        }
    }

    /**
     * Called in CREATE_DOCUMENT mode.
     * Delegates the actual file creation to the DocumentsProvider via a ContentResolver
     * call so the resulting URI is a proper SAF URI the caller can hold permissions on.
     */
    private fun createAndReturn(parentDocId: String, filename: String) {
        try {
            val authority = "${packageName}.documents"
            // Convert to SAF-style doc ID if this is a local path
            val safParentDocId = if (currentShare != null) parentDocId else toSafDocId(parentDocId)
            val parentUri = DocumentsContract.buildDocumentUri(authority, safParentDocId)
            val mimeType = intent.type ?: "*/*"

            GoRoLog.i("createAndReturn: parentDocId=$parentDocId filename=$filename mime=$mimeType")

            GoRoLog.i("GoRoSAF", "createAndReturn parentDocId=$parentDocId filename=$filename mime=$mimeType")

            // Delegate creation to UfmDocumentsProvider.createDocument()
            val newUri: Uri? = DocumentsContract.createDocument(
                contentResolver, parentUri, mimeType, filename
            )

            if (newUri == null) {
                GoRoLog.e("createDocument returned null URI")
                setResult(Activity.RESULT_CANCELED)
                finish()
                return
            }

            GoRoLog.d("Created document URI: $newUri")

            // Grant URI permission to the calling app so takePersistableUriPermission() works
            val callingPkg = callingPackage
            if (callingPkg != null) {
                val grantFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                grantUriPermission(callingPkg, newUri, grantFlags)
                GoRoLog.d("Granted URI permission to $callingPkg for $newUri")
                GoRoLog.i("GoRoSAF", "Granted URI permission to $callingPkg for $newUri flags=$grantFlags")
            }

            val resultIntent = Intent().apply {
                data = newUri
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                )
            }
            setResult(Activity.RESULT_OK, resultIntent)
            finish()
        } catch (e: Exception) {
            GoRoLog.e("CRASH in createAndReturn", e)
            setResult(Activity.RESULT_CANCELED)
            finish()
        }
    }

    private fun returnResult(fullDocId: String, isTree: Boolean) {
        try {
            val authority = "${packageName}.documents"
            
            // Normalize: ensure no \ in the docId
            val normalizedDocId = fullDocId.replace('\\', '/')

            // Convert absolute local paths to SAF-style volumeId:relativePath
            // so the tree URI is /tree/primary:path instead of /tree//storage/...
            val safDocId = if (currentShare != null) {
                normalizedDocId   // network doc IDs stay as-is
            } else {
                toSafDocId(normalizedDocId)
            }
            GoRoLog.i("returnResult: docId=$safDocId (original=$normalizedDocId), isTree=$isTree")
            GoRoLog.i("GoRoSAF", "returnResult: safDocId=$safDocId original=$normalizedDocId isTree=$isTree")

            val uri = if (isTree) {
                val treeUri = DocumentsContract.buildTreeDocumentUri(authority, safDocId)
                GoRoLog.d("Returning Tree URI: $treeUri")
                GoRoLog.i("GoRoSAF", "Returning Tree URI: $treeUri")
                treeUri
            } else {
                val docUri = DocumentsContract.buildDocumentUri(authority, safDocId)
                GoRoLog.d("Returning Document URI: $docUri")
                GoRoLog.i("GoRoSAF", "Returning Document URI: $docUri")
                docUri
            }

            // Grant URI permission to the calling app so takePersistableUriPermission() works.
            // FLAG_GRANT_PREFIX_URI_PERMISSION is critical for tree URIs: it tells Android
            // that child document URIs under this tree are also covered by the grant.
            val callingPkg = callingPackage
            if (callingPkg != null) {
                val grantFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                grantUriPermission(callingPkg, uri, grantFlags)
                GoRoLog.d("Granted URI permission to $callingPkg for $uri")
                GoRoLog.i("GoRoSAF", "Granted URI permission to $callingPkg for $uri flags=$grantFlags")
            }

            val resultIntent = Intent().apply {
                data = uri
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }
            setResult(Activity.RESULT_OK, resultIntent)
            GoRoLog.i("SafPickerActivity finishing with RESULT_OK")
            finish()
        } catch (e: Exception) {
            GoRoLog.e("CRASH in returnResult", e)
            // Still finish to avoid being stuck
            finish()
        }
    }

    /**
     * Converts an absolute filesystem path to a SAF-compatible document ID
     * using the `volumeId:relativePath` format.
     */
    private fun toSafDocId(absolutePath: String): String {
        val sm = getSystemService(Context.STORAGE_SERVICE) as StorageManager
        for (volume in sm.storageVolumes) {
            val volPath = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                volume.safeDirectoryPath
            } else {
                try { volume.javaClass.getMethod("getPath").invoke(volume) as? String } catch (_: Exception) { null }
            } ?: continue
            val prefix = if (volPath.endsWith("/")) volPath else "$volPath/"
            if (absolutePath == volPath || absolutePath.startsWith(prefix)) {
                val volumeId = if (volume.isPrimary) "primary" else (volume.uuid ?: continue)
                val relativePath = if (absolutePath == volPath) "" else absolutePath.removePrefix(prefix)
                return "loc:$volumeId/$relativePath"
            }
        }
        return absolutePath
    }

    inner class PickerAdapter : RecyclerView.Adapter<PickerAdapter.ViewHolder>() {
        private val items = mutableListOf<PickerItem>()

        fun submitList(newItems: List<PickerItem>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
            recyclerItems.scrollToPosition(0)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val layout = if (isTv) R.layout.item_file_tv else R.layout.item_file
            val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount() = items.size

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val icon: ImageView = view.findViewById(R.id.imgFileIcon)
            private val title: TextView = view.findViewById(R.id.txtFileName)
            private val subtitle: TextView = view.findViewById(R.id.txtFileInfo)

            /** Cancels any in-flight Coil request when this ViewHolder is rebound. */
            private var coilDisposable: coil.request.Disposable? = null
            /** Cancels any in-flight video-frame extraction coroutine. */
            private var videoJob: Job? = null

            fun bind(item: PickerItem) {
                // Cancel stale async loads from a previous bind
                coilDisposable?.dispose()
                coilDisposable = null
                videoJob?.cancel()
                videoJob = null

                title.text = item.label
                subtitle.text = if (item.isRoot) getString(R.string.storage_volume) else if (item.isDir) "Folder" else "File"

                itemView.findViewById<View>(R.id.txtFileSize)?.visibility = View.GONE

                // Show selection state in multi-select mode
                val isSelected = allowMultiple && !item.isRoot && !item.isDir && selectedFiles.contains(item.path)
                val alpha = if (isSelected) 1.0f else 0.6f
                title.alpha = alpha

                // ── Thumbnail vs. icon ────────────────────────────────────────
                val showThumbnails = ThumbnailPreferenceManager.isEnabled(itemView.context)
                val file = if (!item.isRoot && !item.isDir && item.path.isNotEmpty()) File(item.path) else null
                val ext = file?.extension?.lowercase() ?: ""
                val isImage = ext in listOf("jpg", "jpeg", "png", "bmp", "webp", "gif", "heic", "heif", "avif")
                val isVideo = ext in listOf("mp4", "mkv", "avi", "mov", "3gp", "webm")
                val canShowThumb = showThumbnails && file != null && (isImage || isVideo)

                icon.imageTintList = null
                icon.alpha = alpha

                if (canShowThumb) {
                    icon.scaleType = ImageView.ScaleType.CENTER_CROP
                    icon.clipToOutline = true
                    icon.outlineProvider = object : android.view.ViewOutlineProvider() {
                        override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                            val r = 8f * view.context.resources.displayMetrics.density
                            outline.setRoundRect(0, 0, view.width, view.height, r)
                        }
                    }

                    if (isImage) {
                        coilDisposable = icon.load(file!!) {
                            crossfade(200)
                            allowHardware(false)
                            scale(Scale.FILL)
                            placeholder(androidx.core.content.ContextCompat.getDrawable(itemView.context, item.iconRes)?.asImage())
                            error(androidx.core.content.ContextCompat.getDrawable(itemView.context, item.iconRes)?.asImage())
                        }
                    } else {
                        // Video: extract frame on a background coroutine
                        icon.setImageResource(item.iconRes)
                        @OptIn(DelicateCoroutinesApi::class)
                        videoJob = GlobalScope.launch(Dispatchers.IO) {
                            val bitmap: android.graphics.Bitmap? = try {
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                                    android.media.ThumbnailUtils.createVideoThumbnail(
                                        file!!, android.util.Size(256, 256), null
                                    )
                                } else {
                                    @Suppress("DEPRECATION")
                                    android.media.ThumbnailUtils.createVideoThumbnail(
                                        file!!.absolutePath,
                                        android.provider.MediaStore.Video.Thumbnails.MINI_KIND
                                    )
                                }
                            } catch (_: Throwable) { null }

                            withContext(Dispatchers.Main) {
                                if (bitmap != null) {
                                    coilDisposable = icon.load(bitmap) {
                                        crossfade(150)
                                        allowHardware(false)
                                        scale(Scale.FILL)
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // Generic icon
                    icon.scaleType = ImageView.ScaleType.FIT_CENTER
                    icon.clipToOutline = false
                    icon.setImageResource(item.iconRes)
                    // Restore accent tint for TV
                    if (isTv) {
                        val accent = itemView.context.getColor(R.color.tv_accent)
                        icon.imageTintList = android.content.res.ColorStateList.valueOf(accent)
                    }
                }
                itemView.setOnClickListener {
                    if (item.isRoot) {
                        loadDirectory(item.path, item.share)
                    } else if (item.isDir) {
                        loadDirectory(item.path, currentShare)
                    } else if (allowMultiple) {
                        // Multi-select: toggle selection
                        toggleSelection(item.path)
                    } else if (!isTreeAction && !isCreateAction) {
                        // Open mode: tapping a file returns it immediately
                        val fullPath = if (currentShare != null) {
                            currentShare!!.docIdPrefix + item.path
                        } else {
                            item.path
                        }
                        returnResult(fullPath, isTree = false)
                    }
                    // In create mode tapping a file does nothing (user must type the name)
                }

                if (isTv) {
                    val ctx = itemView.context
                    val black = ctx.getColor(R.color.tv_button_focused_yellow_text)
                    val white = ctx.getColor(R.color.tv_text_primary)
                    val secondary = ctx.getColor(R.color.tv_text_secondary)
                    val blackCsl = android.content.res.ColorStateList.valueOf(black)
                    val accent = android.content.res.ColorStateList.valueOf(ctx.getColor(R.color.tv_accent))
                    
                    itemView.setOnFocusChangeListener { _, hasFocus ->
                        if (hasFocus) {
                            title.setTextColor(black)
                            subtitle.setTextColor(black)
                            icon.imageTintList = blackCsl
                        } else {
                            title.setTextColor(white)
                            subtitle.setTextColor(secondary)
                            icon.imageTintList = accent
                        }
                    }
                }
            }
        }
    }

    private fun toggleSelection(path: String) {
        if (selectedFiles.contains(path)) {
            selectedFiles.remove(path)
        } else {
            selectedFiles.add(path)
        }
        adapter.notifyDataSetChanged()
        // Update the button text
        if (allowMultiple) {
            (btnSelect as? com.google.android.material.button.MaterialButton)?.let { btn ->
                btn.text = if (selectedFiles.isEmpty()) getString(R.string.select) else "Select (${selectedFiles.size})"
            }
        }
    }

    private fun returnMultipleResults() {
        try {
            val uris = selectedFiles.mapNotNull { path ->
                val fullPath = if (currentShare != null) {
                    currentShare!!.docIdPrefix + path
                } else {
                    path
                }
                val normalized = fullPath.replace('\\', '/')
                val safDocId = if (currentShare != null) normalized else toSafDocId(normalized)
                val docUri = DocumentsContract.buildDocumentUri("${packageName}.documents", safDocId)

                val callingPkg = callingPackage
                if (callingPkg != null) {
                    grantUriPermission(
                        callingPkg, docUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                }
                docUri
            }

            if (uris.isEmpty()) {
                setResult(Activity.RESULT_CANCELED)
                finish()
                return
            }

            val resultIntent = Intent().apply {
                if (uris.size == 1) {
                    data = uris[0]
                } else {
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                }
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                )
            }
            setResult(Activity.RESULT_OK, resultIntent)
            finish()
        } catch (e: Exception) {
            GoRoLog.e("CRASH in returnMultipleResults", e)
            setResult(Activity.RESULT_CANCELED)
            finish()
        }
    }

    private fun mimeMatchesAny(fileName: String): Boolean {
        val mimeList = requestedMimeTypes
        if (mimeList.isNullOrEmpty()) return true
        if (mimeList.any { it == "*/*" || it == "*" }) return true
        val fileMime = getMimeType(fileName) ?: return false
        return mimeList.any { pattern -> mimeMatches(pattern, fileMime) }
    }

    private fun mimeMatches(pattern: String, mime: String): Boolean {
        return when {
            pattern == "*/*" || pattern == "*" -> true
            pattern.endsWith("/*") -> mime.substringBeforeLast('/') == pattern.substringBeforeLast('/')
            else -> mime.equals(pattern, ignoreCase = true)
        }
    }

    private fun getMimeType(fileName: String): String? {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
    }

    data class PickerItem(
        val label: String,
        val iconRes: Int,
        val path: String,
        val isRoot: Boolean = false,
        val isDir: Boolean = false,
        val share: NetworkShare? = null
    )
}
