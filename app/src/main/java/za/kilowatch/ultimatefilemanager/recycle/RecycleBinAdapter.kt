package za.kilowatch.ultimatefilemanager.recycle

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil3.asImage
import coil3.load
import coil3.request.crossfade
import coil3.request.allowHardware
import coil3.size.Scale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.settings.IconCustomizationManager
import za.kilowatch.ultimatefilemanager.settings.IconTapEditModePreferenceManager
import za.kilowatch.ultimatefilemanager.util.FileTypeIconProvider
import java.io.File
import java.text.SimpleDateFormat

import java.util.Date
import java.util.Locale

class RecycleBinAdapter(
    private val isTv: Boolean,
    private val onItemClick: (RecycleBinEntity) -> Unit,
    private val onItemLongClick: (RecycleBinEntity) -> Unit,
    private val onSelectionChanged: () -> Unit
) : ListAdapter<RecycleBinEntity, RecycleBinAdapter.ViewHolder>(DiffCallback()) {

    private val selectedIds = mutableSetOf<Long>()
    private var selectionMode = false

    fun isSelectionMode() = selectionMode
    fun getSelectedIds(): Set<Long> = selectedIds.toSet()
    fun getSelectedCount() = selectedIds.size

    fun enterSelectionMode(id: Long) {
        selectionMode = true
        selectedIds.add(id)
        notifyDataSetChanged()
        onSelectionChanged()
    }

    fun exitSelectionMode() {
        selectionMode = false
        selectedIds.clear()
        notifyDataSetChanged()
        onSelectionChanged()
    }

    fun toggleSelection(id: Long) {
        if (selectedIds.contains(id)) {
            selectedIds.remove(id)
            if (selectedIds.isEmpty()) {
                selectionMode = false
            }
        } else {
            selectedIds.add(id)
            selectionMode = true
        }
        notifyDataSetChanged()
        onSelectionChanged()
    }

    fun selectAll() {
        for (i in 0 until itemCount) {
            getItem(i)?.let { selectedIds.add(it.id) }
        }
        selectionMode = selectedIds.isNotEmpty()
        notifyDataSetChanged()
        onSelectionChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val layout = if (isTv) R.layout.item_recycle_bin_tv else R.layout.item_recycle_bin
        val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val imgFileIcon: ImageView = view.findViewById(R.id.imgFileIcon)
        private val txtFileName: TextView = view.findViewById(R.id.txtFileName)
        private val txtFileInfo: TextView = view.findViewById(R.id.txtFileInfo)
        private val txtDateDeleted: TextView = view.findViewById(R.id.txtDateDeleted)
        private val checkSelect: CheckBox? = view.findViewById(R.id.checkSelect)
        private var apkJob: kotlinx.coroutines.Job? = null

        fun bind(entity: RecycleBinEntity) {
            txtFileName.text = entity.fileName

            val isLocal = entity.trashPath.startsWith("/")
            val sourceTag = if (isLocal) entity.storageLabel else "${entity.storageType} · ${entity.storageLabel}"
            txtFileInfo.text = if (entity.isDirectory) {
                sourceTag
            } else if (entity.fileSize > 0) {
                android.text.format.Formatter.formatFileSize(itemView.context, entity.fileSize) + " · $sourceTag"
            } else {
                sourceTag
            }

            val now = System.currentTimeMillis()
            val diff = now - entity.dateDeleted
            val timeAgo = when {
                diff < 60_000 -> "Just now"
                diff < 3_600_000 -> "${diff / 60_000}m ago"
                diff < 86_400_000 -> "${diff / 3_600_000}h ago"
                diff < 604_800_000 -> "${diff / 86_400_000}d ago"
                else -> {
                    val sdf = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
                    sdf.format(Date(entity.dateDeleted))
                }
            }

            val autoDays = za.kilowatch.ultimatefilemanager.recycle.RecycleBinSettingsManager.getAutoDeleteDays(itemView.context)
            if (autoDays > 0) {
                val expiresMs = entity.dateDeleted + (autoDays * 86400000L)
                val remainingMs = expiresMs - now
                val remainingDays = (remainingMs / 86400000L).toInt()
                val expiryText = when {
                    remainingDays > 1 -> itemView.context.getString(R.string.recycle_bin_expires_in, remainingDays)
                    remainingDays == 1 -> itemView.context.getString(R.string.recycle_bin_expires_in, 1)
                    remainingDays == 0 -> itemView.context.getString(R.string.recycle_bin_expires_today)
                    else -> itemView.context.getString(R.string.recycle_bin_expired)
                }
                txtDateDeleted.text = "$timeAgo · $expiryText"
            } else {
                txtDateDeleted.text = timeAgo
            }

            val ext = entity.extension.lowercase()
            val iconRes = FileTypeIconProvider.iconForExtension(itemView.context, ext)

            if (entity.isDirectory) {
                imgFileIcon.scaleType = ImageView.ScaleType.CENTER_INSIDE
                imgFileIcon.setImageResource(IconCustomizationManager.getEffectiveIconRes(itemView.context, "folder_default", R.drawable.ic_folder))
                apkJob?.cancel()
                apkJob = GlobalScope.launch(Dispatchers.IO) {
                    try {
                        if (isLocal) {
                            val dir = File(entity.trashPath)
                            if (dir.isDirectory) {
                                val files = dir.listFiles() ?: emptyArray()
                                val fileCount = files.count { it.isFile }
                                val folderCount = files.count { it.isDirectory }
                                val totalSize = files.sumOf { if (it.isFile) it.length() else 0L }
                                val info = buildInfoString(fileCount, folderCount, totalSize, itemView.context)
                                if (info.isNotEmpty()) {
                                    withContext(Dispatchers.Main) { txtFileInfo.text = "$info · $sourceTag" }
                                }
                            }
                        } else {
                            // Network directory: try to list remote files
                            val share = resolveShareFromRepo(entity.storageId, entity.storageType)
                            if (share != null) {
                                val netFiles = when (share.type) {
                                    za.kilowatch.ultimatefilemanager.network.ShareType.SMB -> za.kilowatch.ultimatefilemanager.network.SmbShareClient.listFiles(share, entity.trashPath)
                                    za.kilowatch.ultimatefilemanager.network.ShareType.FTP -> za.kilowatch.ultimatefilemanager.network.FtpShareClient.listFiles(share, entity.trashPath)
                                    za.kilowatch.ultimatefilemanager.network.ShareType.SFTP, za.kilowatch.ultimatefilemanager.network.ShareType.SCP -> za.kilowatch.ultimatefilemanager.network.SshShareClient.listFiles(share, entity.trashPath)
                                    za.kilowatch.ultimatefilemanager.network.ShareType.NFS -> za.kilowatch.ultimatefilemanager.network.NfsShareClient.listFiles(share, entity.trashPath)
                                    za.kilowatch.ultimatefilemanager.network.ShareType.TV -> za.kilowatch.ultimatefilemanager.network.TvShareClient.listFiles(share, entity.trashPath)
                                    za.kilowatch.ultimatefilemanager.network.ShareType.ONEDRIVE -> za.kilowatch.ultimatefilemanager.network.OnedriveShareClient.listFiles(share, entity.trashPath)
                                    za.kilowatch.ultimatefilemanager.network.ShareType.GOOGLE_DRIVE -> za.kilowatch.ultimatefilemanager.network.GoogleDriveShareClient.listFiles(share, entity.trashPath)
                                    za.kilowatch.ultimatefilemanager.network.ShareType.DROPBOX -> za.kilowatch.ultimatefilemanager.network.DropboxShareClient.listFiles(share, entity.trashPath)
                                    za.kilowatch.ultimatefilemanager.network.ShareType.AWS_S3, za.kilowatch.ultimatefilemanager.network.ShareType.IDRIVE_E2 -> za.kilowatch.ultimatefilemanager.network.S3ShareClient.listFiles(share, entity.trashPath)
                                    za.kilowatch.ultimatefilemanager.network.ShareType.WEBDAV -> za.kilowatch.ultimatefilemanager.network.WebDavShareClient.listFiles(share, entity.trashPath)
                                    za.kilowatch.ultimatefilemanager.network.ShareType.WEBDAV -> za.kilowatch.ultimatefilemanager.network.WebDavShareClient.listFiles(share, entity.trashPath)
                                    za.kilowatch.ultimatefilemanager.network.ShareType.DLNA -> za.kilowatch.ultimatefilemanager.network.DlnaShareClient.listFiles(share, entity.trashPath)
                                }
                                val fileCount = netFiles.count { !it.isDirectory }
                                val folderCount = netFiles.count { it.isDirectory }
                                val totalSize = netFiles.sumOf { it.size }
                                val info = buildInfoString(fileCount, folderCount, totalSize, itemView.context)
                                if (info.isNotEmpty()) {
                                    withContext(Dispatchers.Main) { txtFileInfo.text = "$info · $sourceTag" }
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
            } else if (isLocal) {
                val file = File(entity.trashPath)
                if (file.exists()) {
                    val placeholderImage = androidx.core.content.ContextCompat.getDrawable(
                        itemView.context, R.drawable.ic_photo_video
                    )?.asImage()

                    val isImage = ext in setOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "svg", "heic", "heif")
                    val isApk = ext in setOf("apk", "xapk", "apks")
                    val isVideo = ext in setOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "3gp", "m4v")

                    when {
                        isImage -> {
                            imgFileIcon.imageTintList = null
                            imgFileIcon.scaleType = ImageView.ScaleType.CENTER_CROP
                            imgFileIcon.load(file) {
                                crossfade(200)
                                allowHardware(false)
                                scale(Scale.FILL)
                                placeholder(placeholderImage)
                                error(placeholderImage)
                            }
                        }
                        isApk -> {
                            imgFileIcon.imageTintList = null
                            imgFileIcon.scaleType = ImageView.ScaleType.CENTER_INSIDE
                            imgFileIcon.tag = file.absolutePath
                            imgFileIcon.setImageResource(FileTypeIconProvider.iconForFile(itemView.context, file))
                            apkJob?.cancel()
                            apkJob = GlobalScope.launch(Dispatchers.IO) {
                                val drawable = resolveApkIcon(file)
                                withContext(Dispatchers.Main) {
                                    if (imgFileIcon.tag == file.absolutePath && drawable != null) {
                                        imgFileIcon.scaleType = ImageView.ScaleType.CENTER_INSIDE
                                        imgFileIcon.load(drawable) {
                                            crossfade(150)
                                            allowHardware(false)
                                        }
                                    }
                                }
                            }
                        }
                        isVideo -> {
                            imgFileIcon.imageTintList = null
                            imgFileIcon.scaleType = ImageView.ScaleType.CENTER_CROP
                            imgFileIcon.setImageResource(iconRes)
                        }
                        else -> {
                            imgFileIcon.scaleType = ImageView.ScaleType.CENTER_INSIDE
                            imgFileIcon.setImageResource(iconRes)
                        }
                    }
                } else {
                    imgFileIcon.scaleType = ImageView.ScaleType.CENTER_INSIDE
                    imgFileIcon.setImageResource(iconRes)
                }
            } else {
                val isMedia = ext in setOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "svg", "heic", "heif") || ext in setOf("apk", "xapk", "apks")
                if (isMedia) {
                    imgFileIcon.imageTintList = null
                    imgFileIcon.scaleType = ImageView.ScaleType.CENTER_CROP
                    imgFileIcon.setImageResource(R.drawable.ic_photo_video)
                    val tag = entity.id.toString()
                    imgFileIcon.tag = tag
                    apkJob?.cancel()
                    apkJob = GlobalScope.launch(Dispatchers.IO) {
                        try {
                            val share = resolveShareFromRepo(entity.storageId, entity.storageType)
                            if (share != null) {
                                val netFile = za.kilowatch.ultimatefilemanager.network.NetworkFile(
                                    name = entity.fileName,
                                    path = entity.trashPath,
                                    isDirectory = entity.isDirectory,
                                    size = entity.fileSize
                                )
                                val cacheManager = za.kilowatch.ultimatefilemanager.settings.NetworkThumbnailCacheManager(itemView.context)
                                val localPath = cacheManager.getThumbnail(share, netFile)
                                if (localPath != null) {
                                    withContext(Dispatchers.Main) {
                                        if (imgFileIcon.tag == tag) {
                                            val ph = androidx.core.content.ContextCompat.getDrawable(
                                                itemView.context, R.drawable.ic_photo_video
                                            )?.asImage()
                                            imgFileIcon.load(File(localPath)) {
                                                crossfade(200)
                                                allowHardware(false)
                                                scale(Scale.FILL)
                                                placeholder(ph)
                                                error(ph)
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (_: Exception) {}
                    }
                } else {
                    imgFileIcon.scaleType = ImageView.ScaleType.CENTER_INSIDE
                    imgFileIcon.setImageResource(iconRes)
                }
            }

            if (selectionMode) {
                checkSelect?.visibility = View.VISIBLE
                checkSelect?.isChecked = selectedIds.contains(entity.id)
            } else {
                checkSelect?.visibility = View.GONE
            }

            // Icon tap to enter edit/selection mode (Mobile only)
            val targetIconView = itemView.findViewById<View>(R.id.iconContainer) ?: imgFileIcon
            if (!isTv && IconTapEditModePreferenceManager.isEnabled(itemView.context)) {

                targetIconView.setOnClickListener {
                    if (!selectionMode) {
                        enterSelectionMode(entity.id)
                    } else {
                        toggleSelection(entity.id)
                    }
                }
            } else {
                targetIconView.setOnClickListener(null)
                targetIconView.isClickable = false
            }

            itemView.setOnClickListener {

                if (selectionMode) {
                    toggleSelection(entity.id)
                } else {
                    onItemClick(entity)
                }
            }

            itemView.setOnLongClickListener {
                if (!selectionMode) {
                    enterSelectionMode(entity.id)
                }
                true
            }
        }

        private suspend fun openNetworkStream(share: za.kilowatch.ultimatefilemanager.network.NetworkShare, path: String): java.io.InputStream? {
            return try {
                when (share.type) {
                    za.kilowatch.ultimatefilemanager.network.ShareType.SMB ->
                        za.kilowatch.ultimatefilemanager.network.SmbShareClient.openInputStream(share, path)
                    za.kilowatch.ultimatefilemanager.network.ShareType.FTP ->
                        za.kilowatch.ultimatefilemanager.network.FtpShareClient.openInputStream(share, path)
                    za.kilowatch.ultimatefilemanager.network.ShareType.SFTP, za.kilowatch.ultimatefilemanager.network.ShareType.SCP ->
                        za.kilowatch.ultimatefilemanager.network.SshShareClient.openInputStream(share, path)
                    za.kilowatch.ultimatefilemanager.network.ShareType.NFS ->
                        za.kilowatch.ultimatefilemanager.network.NfsShareClient.openInputStream(share, path)
                    za.kilowatch.ultimatefilemanager.network.ShareType.TV ->
                        za.kilowatch.ultimatefilemanager.network.TvShareClient.openInputStream(share, path)
                    za.kilowatch.ultimatefilemanager.network.ShareType.ONEDRIVE ->
                        za.kilowatch.ultimatefilemanager.network.OnedriveShareClient.openInputStream(share, path).first
                    za.kilowatch.ultimatefilemanager.network.ShareType.GOOGLE_DRIVE ->
                        za.kilowatch.ultimatefilemanager.network.GoogleDriveShareClient.openInputStream(share, path).first
                    za.kilowatch.ultimatefilemanager.network.ShareType.DROPBOX ->
                        za.kilowatch.ultimatefilemanager.network.DropboxShareClient.openInputStream(share, path).first
                    za.kilowatch.ultimatefilemanager.network.ShareType.AWS_S3, za.kilowatch.ultimatefilemanager.network.ShareType.IDRIVE_E2 ->
                        za.kilowatch.ultimatefilemanager.network.S3ShareClient.openInputStream(share, path).first
                    za.kilowatch.ultimatefilemanager.network.ShareType.WEBDAV ->
                        za.kilowatch.ultimatefilemanager.network.WebDavShareClient.openInputStream(share, path).first
                    za.kilowatch.ultimatefilemanager.network.ShareType.WEBDAV ->
                        za.kilowatch.ultimatefilemanager.network.WebDavShareClient.openInputStream(share, path).first
                    za.kilowatch.ultimatefilemanager.network.ShareType.DLNA ->
                        za.kilowatch.ultimatefilemanager.network.DlnaShareClient.openInputStream(share, path)
                }
            } catch (_: Exception) { null }
        }

        private suspend fun resolveShareFromRepo(storageId: String, storageType: String): za.kilowatch.ultimatefilemanager.network.NetworkShare? {
            return try {
                val ctx = itemView.context
                val fromRepo = za.kilowatch.ultimatefilemanager.network.NetworkShareRepository.getInstance(ctx).getById(storageId)
                if (fromRepo != null) return fromRepo
                val fromOnline = za.kilowatch.ultimatefilemanager.network.OnlineStorageRepository.getInstance(ctx).getById(storageId)
                if (fromOnline != null) {
                    val providerType = when (fromOnline.provider) {
                        za.kilowatch.ultimatefilemanager.network.OnlineStorageProvider.ONEDRIVE -> za.kilowatch.ultimatefilemanager.network.ShareType.ONEDRIVE
                        za.kilowatch.ultimatefilemanager.network.OnlineStorageProvider.GOOGLE_DRIVE -> za.kilowatch.ultimatefilemanager.network.ShareType.GOOGLE_DRIVE
                        za.kilowatch.ultimatefilemanager.network.OnlineStorageProvider.DROPBOX -> za.kilowatch.ultimatefilemanager.network.ShareType.DROPBOX
                        za.kilowatch.ultimatefilemanager.network.OnlineStorageProvider.AWS_S3 -> za.kilowatch.ultimatefilemanager.network.ShareType.AWS_S3
                        za.kilowatch.ultimatefilemanager.network.OnlineStorageProvider.IDRIVE_E2 -> za.kilowatch.ultimatefilemanager.network.ShareType.IDRIVE_E2
                        za.kilowatch.ultimatefilemanager.network.OnlineStorageProvider.WEBDAV -> za.kilowatch.ultimatefilemanager.network.ShareType.WEBDAV
                        za.kilowatch.ultimatefilemanager.network.OnlineStorageProvider.RCLONE -> za.kilowatch.ultimatefilemanager.network.ShareType.WEBDAV
                    }
                    return za.kilowatch.ultimatefilemanager.network.NetworkShare(
                        id = fromOnline.id,
                        name = fromOnline.displayName,
                        type = providerType,
                        host = if (fromOnline.isWebDavProvider) (fromOnline.webDavUrl ?: fromOnline.email) else (fromOnline.s3Endpoint ?: fromOnline.email),
                        port = 0,
                        username = if (fromOnline.isWebDavProvider) (fromOnline.webDavUsername ?: fromOnline.email) else (fromOnline.s3AccessKey ?: fromOnline.email),
                        password = if (fromOnline.isWebDavProvider) (fromOnline.webDavPassword ?: "") else (fromOnline.s3SecretKey ?: ""),
                        domain = fromOnline.s3Bucket ?: "",
                        remotePath = fromOnline.s3Region ?: "/",
                        readOnly = false
                    )
                }
                val device = za.kilowatch.ultimatefilemanager.network.PairingManager.getInstance(ctx).getPairedDevice(storageId)
                if (device != null) return za.kilowatch.ultimatefilemanager.network.NetworkShare(id = device.deviceId, name = device.name, type = za.kilowatch.ultimatefilemanager.network.ShareType.TV, host = device.lastIp, port = device.lastPort, readOnly = false)
                null
            } catch (e: Exception) { null }
        }

        private suspend fun resolveApkIcon(file: File): android.graphics.drawable.Drawable? =
            withContext(Dispatchers.IO) {
                val ext = file.extension.lowercase()
                val pm = itemView.context.packageManager

                if (ext == "apk") {
                    try {
                        val pi = pm.getPackageArchiveInfo(file.absolutePath, 0)
                        if (pi != null) {
                            pi.applicationInfo?.sourceDir = file.absolutePath
                            pi.applicationInfo?.publicSourceDir = file.absolutePath
                            pi.applicationInfo?.loadIcon(pm)
                        } else null
                    } catch (_: Exception) { null }
                } else {
                    val iconBitmap: android.graphics.Bitmap? = try {
                        java.util.zip.ZipFile(file).use { zip ->
                            val entry = zip.getEntry("icon.png")
                            if (entry != null) {
                                android.graphics.BitmapFactory.decodeStream(zip.getInputStream(entry))
                            } else null
                        }
                    } catch (_: Exception) { null }

                    if (iconBitmap != null) {
                        android.graphics.drawable.BitmapDrawable(itemView.context.resources, iconBitmap)
                    } else {
                        var tempApk: File? = null
                        try {
                            tempApk = File(
                                itemView.context.cacheDir,
                                "xapk_base_${System.currentTimeMillis()}.apk"
                            )
                            java.util.zip.ZipFile(file).use { zip ->
                                val entry = zip.getEntry("base.apk")
                                if (entry != null) {
                                    zip.getInputStream(entry).use { input ->
                                        tempApk.outputStream().use { output -> input.copyTo(output) }
                                    }
                                }
                            }
                            if (tempApk.exists() && tempApk.length() > 0L) {
                                val pi = pm.getPackageArchiveInfo(tempApk.absolutePath, 0)
                                if (pi != null) {
                                    pi.applicationInfo?.sourceDir = tempApk.absolutePath
                                    pi.applicationInfo?.publicSourceDir = tempApk.absolutePath
                                    pi.applicationInfo?.loadIcon(pm)
                                } else null
                            } else null
                        } catch (_: Exception) { null } finally {
                            tempApk?.delete()
                        }
                    }
                }
            }
    }

    private fun buildInfoString(fileCount: Int, folderCount: Int, totalSize: Long, ctx: android.content.Context): String {
        return buildString {
            if (fileCount > 0) append("$fileCount file(s)")
            if (folderCount > 0) append("${if (fileCount > 0) ", " else ""}$folderCount folder(s)")
            if (totalSize > 0) append(" · ${android.text.format.Formatter.formatFileSize(ctx, totalSize)}")
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<RecycleBinEntity>() {
        override fun areItemsTheSame(old: RecycleBinEntity, new: RecycleBinEntity) = old.id == new.id
        override fun areContentsTheSame(old: RecycleBinEntity, new: RecycleBinEntity) = old == new
    }
}
