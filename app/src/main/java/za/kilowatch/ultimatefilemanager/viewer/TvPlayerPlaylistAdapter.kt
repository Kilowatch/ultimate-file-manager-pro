package za.kilowatch.ultimatefilemanager.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.text.format.Formatter
import android.util.LruCache
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.media.FFmpegThumbnailHelper
import za.kilowatch.ultimatefilemanager.settings.NetworkThumbnailCacheManager
import za.kilowatch.ultimatefilemanager.settings.VideoThumbnailTimePreferenceManager
import za.kilowatch.ultimatefilemanager.storage.FileAdapter
import za.kilowatch.ultimatefilemanager.storage.SafTreeManager
import za.kilowatch.ultimatefilemanager.network.NetworkFile
import za.kilowatch.ultimatefilemanager.network.NetworkShareRepository
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * TV-optimized playlist adapter for the slide-out directory drawer.
 * Displays 16:9 thumbnails, filenames, durations, and active playing indicator.
 * Uses the proven multi-stage video thumbnail extraction pipeline from FileAdapter.
 */
class TvPlayerPlaylistAdapter(
    private var items: List<QueueItem>,
    private var currentIndex: Int,
    private val onItemClick: (Int) -> Unit
) : RecyclerView.Adapter<TvPlayerPlaylistAdapter.ViewHolder>() {

    private val adapterScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    companion object {
        private val thumbnailCache = LruCache<String, Bitmap>(64)
        private val durationCache = ConcurrentHashMap<String, String>()

        fun formatDuration(ms: Long): String {
            if (ms <= 0L) return ""
            val totalSeconds = ms / 1000
            val seconds = totalSeconds % 60
            val minutes = (totalSeconds / 60) % 60
            val hours = totalSeconds / 3600
            return if (hours > 0) {
                String.format("%d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format("%d:%02d", minutes, seconds)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_tv_player_playlist, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val isCurrent = position == currentIndex
        val ctx = holder.itemView.context

        // Title
        val name = item.title ?: item.path.substringAfterLast('/')
        holder.txtTitle.text = name

        // Subtitle: "#X · FileSize"
        val sizeStr = if (item.fileSize > 0) {
            " · " + Formatter.formatFileSize(ctx, item.fileSize)
        } else {
            val f = File(item.path)
            if (f.exists()) " · " + Formatter.formatFileSize(ctx, f.length()) else ""
        }
        holder.txtSubtitle.text = "#${position + 1}$sizeStr"

        // Active playing highlights
        holder.itemView.isSelected = isCurrent
        holder.playingBadge.visibility = if (isCurrent) View.VISIBLE else View.GONE

        // Duration
        bindDuration(holder, item)

        // Thumbnail (FileAdapter multi-stage pipeline)
        bindThumbnail(holder, item)

        // Click handler
        holder.itemView.setOnClickListener {
            onItemClick(position)
        }
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        holder.thumbnailJob?.cancel()
        holder.durationJob?.cancel()
    }

    override fun getItemCount(): Int = items.size

    fun updateItems(newItems: List<QueueItem>, newIndex: Int) {
        items = newItems
        currentIndex = newIndex
        notifyDataSetChanged()
    }

    fun setCurrentIndex(newIndex: Int) {
        if (currentIndex == newIndex) return
        val oldIndex = currentIndex
        currentIndex = newIndex
        if (oldIndex in items.indices) notifyItemChanged(oldIndex)
        if (newIndex in items.indices) notifyItemChanged(newIndex)
    }

    fun getCurrentIndex(): Int = currentIndex

    private fun bindDuration(holder: ViewHolder, item: QueueItem) {
        val cached = durationCache[item.path]
        if (cached != null) {
            holder.txtDuration.text = cached
            holder.txtDuration.visibility = View.VISIBLE
            return
        }

        if (item.duration > 0L) {
            val formatted = formatDuration(item.duration)
            durationCache[item.path] = formatted
            holder.txtDuration.text = formatted
            holder.txtDuration.visibility = View.VISIBLE
            return
        }

        holder.txtDuration.visibility = View.GONE
        holder.durationJob?.cancel()

        val ctx = holder.itemView.context
        val path = item.path
        val localFile = File(path)
        val isSaf = SafTreeManager.isSafPath(path) || path.startsWith("content://") || SafTreeManager.isSaf(ctx, path)

        if (localFile.exists() || isSaf) {
            holder.durationJob = adapterScope.launch(Dispatchers.IO) {
                var retriever: MediaMetadataRetriever? = null
                try {
                    retriever = MediaMetadataRetriever()
                    if (localFile.exists()) {
                        retriever.setDataSource(path)
                    } else {
                        val uri = if (path.startsWith("content://")) Uri.parse(path)
                        else SafTreeManager.getDocumentUriForPath(ctx, path)
                        if (uri != null) {
                            retriever.setDataSource(ctx, uri)
                        } else return@launch
                    }
                    val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    val durMs = durStr?.toLongOrNull() ?: 0L
                    if (durMs > 0L) {
                        val formatted = formatDuration(durMs)
                        durationCache[path] = formatted
                        withContext(Dispatchers.Main) {
                            if (holder.bindingAdapterPosition != RecyclerView.NO_POSITION &&
                                items.getOrNull(holder.bindingAdapterPosition)?.path == path
                            ) {
                                holder.txtDuration.text = formatted
                                holder.txtDuration.visibility = View.VISIBLE
                            }
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    try { retriever?.release() } catch (_: Exception) {}
                }
            }
        }
    }

    /**
     * Implements the exact robust multi-tier video thumbnail generation pipeline used in FileAdapter:
     * 1. Check in-memory caches (TvPlayerPlaylistAdapter + FileAdapter.videoCache).
     * 2. If network share item: query NetworkThumbnailCacheManager.
     * 3. If SAF / Content URI:
     *    - Android Q+: ContentResolver.loadThumbnail(512x512)
     *    - Fallback: MediaMetadataRetriever with configured percentage frame & scaled down.
     * 4. If local filesystem file:
     *    - Native FFmpeg frame extraction at user-configured time percent (512x512).
     *    - Fallback: MediaMetadataRetriever frame extraction at user-configured time percent, scaled.
     *    - Fallback (Android Q+): ThumbnailUtils.createVideoThumbnail(File, Size, null).
     *    - Fallback (pre-Q): ThumbnailUtils.createVideoThumbnail(path, MediaStore.Video.Thumbnails.MINI_KIND).
     *    - Fallback: MJPEG frame extraction if applicable.
     * 5. Fallback for audio items: embedded album art picture.
     * 6. Cache into both TvPlayerPlaylistAdapter and FileAdapter caches.
     */
    private fun bindThumbnail(holder: ViewHolder, item: QueueItem) {
        val path = item.path
        holder.imgThumbnail.tag = path
        holder.thumbnailJob?.cancel()

        // 1. Check in-memory caches
        val cached = thumbnailCache.get(path) ?: FileAdapter.getVideoThumbnail(path)
        if (cached != null) {
            holder.imgThumbnail.setImageBitmap(cached)
            holder.imgFallbackIcon.visibility = View.GONE
            return
        }

        // Default placeholder
        holder.imgThumbnail.setImageBitmap(null)
        holder.imgFallbackIcon.visibility = View.VISIBLE

        val ctx = holder.itemView.context
        val isNetwork = item.shareId != null || item.remotePath != null
        val pct = VideoThumbnailTimePreferenceManager.getPercent(ctx)
        val tag = "TvPlaylistThumb"

        holder.thumbnailJob = adapterScope.launch(Dispatchers.IO) {
            var bitmap: Bitmap? = null
            val localFile = File(path)
            val isLocalFile = localFile.exists()

            za.kilowatch.ultimatefilemanager.util.GoRoLog.d(tag,
                "bindThumbnail START path=$path isLocal=$isLocalFile isNetwork=$isNetwork")

            // ── Priority 1: Local filesystem file ──────────────────────
            if (isLocalFile) {
                // 1a. Native FFmpeg extraction
                ensureActive()
                bitmap = FFmpegThumbnailHelper.extractVideoFrame(path, pct, 512, 512)
                za.kilowatch.ultimatefilemanager.util.GoRoLog.d(tag,
                    "  FFmpeg result=${bitmap != null} path=$path")

                // 1b. MediaMetadataRetriever at configured percentage
                if (bitmap == null) {
                    ensureActive()
                    bitmap = try {
                        val retriever = MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(path)
                            val durationMs = retriever.extractMetadata(
                                MediaMetadataRetriever.METADATA_KEY_DURATION
                            )?.toLongOrNull() ?: 0L
                            val durationUs = durationMs * 1000L
                            val timeUs = if (durationUs > 0) durationUs * pct / 100L else 0L
                            val raw = retriever.getFrameAtTime(
                                timeUs,
                                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                            )
                            if (durationMs > 0L) {
                                val formatted = formatDuration(durationMs)
                                durationCache[path] = formatted
                                withContext(Dispatchers.Main) {
                                    if (holder.imgThumbnail.tag == path) {
                                        holder.txtDuration.text = formatted
                                        holder.txtDuration.visibility = View.VISIBLE
                                    }
                                }
                            }
                            if (raw != null) {
                                val maxPx = 512
                                val w = raw.width; val h = raw.height
                                if (w <= maxPx && h <= maxPx) raw else {
                                    val scale = maxPx.toFloat() / maxOf(w, h)
                                    Bitmap.createScaledBitmap(
                                        raw,
                                        (w * scale).toInt().coerceAtLeast(1),
                                        (h * scale).toInt().coerceAtLeast(1),
                                        true
                                    )
                                }
                            } else null
                        } finally {
                            try { retriever.release() } catch (_: Throwable) {}
                        }
                    } catch (ce: kotlinx.coroutines.CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        za.kilowatch.ultimatefilemanager.util.GoRoLog.w(tag,
                            "  MMR failed: ${t.message} path=$path")
                        null
                    }
                    za.kilowatch.ultimatefilemanager.util.GoRoLog.d(tag,
                        "  MMR result=${bitmap != null} path=$path")
                }

                // 1c. ThumbnailUtils on Android Q+
                if (bitmap == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ensureActive()
                    bitmap = try {
                        ThumbnailUtils.createVideoThumbnail(localFile, Size(512, 512), null)
                    } catch (ce: kotlinx.coroutines.CancellationException) {
                        throw ce
                    } catch (_: Throwable) { null }
                    za.kilowatch.ultimatefilemanager.util.GoRoLog.d(tag,
                        "  ThumbnailUtils(Q+) result=${bitmap != null} path=$path")
                }

                // 1d. ThumbnailUtils for pre-Android Q
                if (bitmap == null && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    ensureActive()
                    bitmap = try {
                        @Suppress("DEPRECATION")
                        ThumbnailUtils.createVideoThumbnail(
                            path,
                            MediaStore.Video.Thumbnails.MINI_KIND
                        )
                    } catch (ce: kotlinx.coroutines.CancellationException) {
                        throw ce
                    } catch (_: Throwable) { null }
                }

                // 1e. MJPEG video fallback
                if (bitmap == null && path.substringAfterLast('.', "").lowercase() in listOf("mjpeg", "mjpg", "mjp")) {
                    bitmap = try {
                        val exif = android.media.ExifInterface(path)
                        exif.thumbnailBitmap ?: exif.thumbnailBytes?.let { bytes ->
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        }
                    } catch (ce: kotlinx.coroutines.CancellationException) {
                        throw ce
                    } catch (_: Throwable) { null }
                }
            }

            // ── Priority 2: Network file cache & generation ────────────
            if (bitmap == null && isNetwork) {
                ensureActive()
                val shareId = item.shareId ?: ""
                val networkPath = item.path
                if (shareId.isNotEmpty()) {
                    try {
                        val cacheMgr = NetworkThumbnailCacheManager(ctx)
                        var cachedPath = cacheMgr.getCachedThumbnailPath(shareId, networkPath)
                        if (cachedPath == null && !networkPath.startsWith("/")) {
                            cachedPath = cacheMgr.getCachedThumbnailPath(shareId, "/$networkPath")
                        }
                        if (cachedPath == null && networkPath.startsWith("/")) {
                            cachedPath = cacheMgr.getCachedThumbnailPath(shareId, networkPath.trimStart('/'))
                        }

                        if (cachedPath != null && File(cachedPath).exists()) {
                            bitmap = BitmapFactory.decodeFile(cachedPath)
                            za.kilowatch.ultimatefilemanager.util.GoRoLog.d(tag,
                                "  Network cached hit: $cachedPath for $networkPath")
                        } else {
                            val repo = NetworkShareRepository.getInstance(ctx)
                            val share = repo.getAll().firstOrNull { it.id == shareId }
                            if (share != null) {
                                val netFile = NetworkFile(
                                    name = networkPath.substringAfterLast('/'),
                                    path = networkPath,
                                    isDirectory = false,
                                    size = item.fileSize,
                                    lastModified = 0L
                                )
                                val genPath = cacheMgr.getThumbnail(share, netFile)
                                if (genPath != null && File(genPath).exists()) {
                                    bitmap = BitmapFactory.decodeFile(genPath)
                                    za.kilowatch.ultimatefilemanager.util.GoRoLog.d(tag,
                                        "  Network generated thumb: $genPath for $networkPath")
                                }
                            }
                        }
                    } catch (ce: kotlinx.coroutines.CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        za.kilowatch.ultimatefilemanager.util.GoRoLog.w(tag,
                            "  Network thumbnail failed: ${t.message} path=$path")
                    }
                }
            }

            // ── Priority 3: SAF / Content URI ──────────────────────────
            if (bitmap == null && !isLocalFile) {
                val isSafPath = SafTreeManager.isSafPath(path) || path.startsWith("content://")
                if (isSafPath) {
                    ensureActive()
                    try {
                        val uri = if (path.startsWith("content://")) {
                            Uri.parse(path)
                        } else {
                            SafTreeManager.getDocumentUriForPath(ctx, path)
                        }
                        if (uri != null) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                try {
                                    bitmap = ctx.contentResolver.loadThumbnail(uri, Size(512, 512), null)
                                } catch (ce: kotlinx.coroutines.CancellationException) {
                                    throw ce
                                } catch (_: Throwable) {}
                            }

                            if (bitmap == null) {
                                ensureActive()
                                val retriever = MediaMetadataRetriever()
                                try {
                                    retriever.setDataSource(ctx, uri)
                                    val durationMs = retriever.extractMetadata(
                                        MediaMetadataRetriever.METADATA_KEY_DURATION
                                    )?.toLongOrNull() ?: 0L
                                    val durationUs = durationMs * 1000L
                                    val timeUs = if (durationUs > 0) durationUs * pct / 100L else 0L
                                    val raw = retriever.getFrameAtTime(
                                        timeUs,
                                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                                    )
                                    if (raw != null) {
                                        val maxPx = 512
                                        val w = raw.width; val h = raw.height
                                        bitmap = if (w <= maxPx && h <= maxPx) raw else {
                                            val scale = maxPx.toFloat() / maxOf(w, h)
                                            Bitmap.createScaledBitmap(
                                                raw,
                                                (w * scale).toInt().coerceAtLeast(1),
                                                (h * scale).toInt().coerceAtLeast(1),
                                                true
                                            )
                                        }
                                    }
                                    if (durationMs > 0L) {
                                        val formatted = formatDuration(durationMs)
                                        durationCache[path] = formatted
                                        withContext(Dispatchers.Main) {
                                            if (holder.imgThumbnail.tag == path) {
                                                holder.txtDuration.text = formatted
                                                holder.txtDuration.visibility = View.VISIBLE
                                            }
                                        }
                                    }
                                } finally {
                                    try { retriever.release() } catch (_: Throwable) {}
                                }
                            }
                        }
                    } catch (ce: kotlinx.coroutines.CancellationException) {
                        throw ce
                    } catch (_: Throwable) {}
                }
            }

            // ── Priority 4: Audio album art fallback ───────────────────
            if (bitmap == null && !item.isVideo && isLocalFile) {
                ensureActive()
                bitmap = try {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(path)
                        val art = retriever.embeddedPicture
                        if (art != null) {
                            BitmapFactory.decodeByteArray(art, 0, art.size)
                        } else null
                    } finally {
                        try { retriever.release() } catch (_: Throwable) {}
                    }
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    throw ce
                } catch (_: Throwable) { null }
            }

            za.kilowatch.ultimatefilemanager.util.GoRoLog.d(tag,
                "bindThumbnail END bitmap=${bitmap != null} isActive=$isActive path=$path")

            // Cache and present on main thread
            if (bitmap != null && isActive) {
                thumbnailCache.put(path, bitmap)
                FileAdapter.putVideoThumbnail(path, bitmap)
                withContext(Dispatchers.Main) {
                    if (holder.imgThumbnail.tag == path) {
                        holder.imgThumbnail.setImageBitmap(bitmap)
                        holder.imgFallbackIcon.visibility = View.GONE
                    }
                }
            }
        }
    }

    fun release() {
        adapterScope.cancel()
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val imgThumbnail: ImageView = itemView.findViewById(R.id.imgThumbnail)
        val imgFallbackIcon: ImageView = itemView.findViewById(R.id.imgFallbackIcon)
        val playingBadge: LinearLayout = itemView.findViewById(R.id.playingBadge)
        val txtDuration: TextView = itemView.findViewById(R.id.txtDuration)
        val txtTitle: TextView = itemView.findViewById(R.id.txtTitle)
        val txtSubtitle: TextView = itemView.findViewById(R.id.txtSubtitle)
        var thumbnailJob: Job? = null
        var durationJob: Job? = null
    }
}
