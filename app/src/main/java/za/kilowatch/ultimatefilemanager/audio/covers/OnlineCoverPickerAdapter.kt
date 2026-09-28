package za.kilowatch.ultimatefilemanager.audio.covers

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.audio.covers.model.CoverSearchResult

/**
 * Adapter displaying high-resolution album cover search candidates in a 2-column grid.
 * Uses an in-memory LruCache and UfmHttpClient to download and render cover thumbnails directly,
 * bypassing external image loader network dependency limitations.
 */
class OnlineCoverPickerAdapter(
    private val items: MutableList<CoverSearchResult> = mutableListOf(),
    private val onCoverClick: (CoverSearchResult) -> Unit
) : RecyclerView.Adapter<OnlineCoverPickerAdapter.CoverViewHolder>() {

    companion object {
        // Shared in-memory thumbnail cache across queries (max 100 cover thumbnails)
        private val thumbnailCache = LruCache<String, Bitmap>(100)
    }

    private val adapterScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    fun submitList(newItems: List<CoverSearchResult>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CoverViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_online_cover_card, parent, false)
        return CoverViewHolder(view)
    }

    override fun onBindViewHolder(holder: CoverViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        adapterScope.coroutineContext[Job]?.cancelChildren()
    }

    inner class CoverViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val imgCoverThumb: ImageView = itemView.findViewById(R.id.imgCoverThumb)
        private val txtResolutionBadge: TextView = itemView.findViewById(R.id.txtResolutionBadge)
        private val txtSourceBadge: TextView = itemView.findViewById(R.id.txtSourceBadge)
        private val txtAlbumTitle: TextView = itemView.findViewById(R.id.txtAlbumTitle)
        private val txtArtistName: TextView = itemView.findViewById(R.id.txtArtistName)

        private var loadJob: Job? = null

        fun bind(item: CoverSearchResult) {
            loadJob?.cancel()

            val thumbUrl = item.thumbUrl
            imgCoverThumb.tag = thumbUrl

            val cached = thumbnailCache.get(thumbUrl)
            if (cached != null) {
                imgCoverThumb.alpha = 1f
                imgCoverThumb.setImageBitmap(cached)
            } else {
                imgCoverThumb.alpha = 0.45f
                imgCoverThumb.setImageResource(R.drawable.ic_file_image)

                loadJob = adapterScope.launch {
                    val bitmap = withContext(Dispatchers.IO) {
                        try {
                            val artwork = CoverSearchAggregator.downloadImageBytes(thumbUrl)
                            val bytes = artwork?.first
                            if (bytes != null && bytes.isNotEmpty()) {
                                decodeSampledBitmap(bytes, 320, 320)
                            } else {
                                null
                            }
                        } catch (_: Throwable) {
                            null
                        }
                    }

                    if (imgCoverThumb.tag == thumbUrl) {
                        if (bitmap != null) {
                            thumbnailCache.put(thumbUrl, bitmap)
                            imgCoverThumb.setImageBitmap(bitmap)
                            imgCoverThumb.animate().alpha(1f).setDuration(150).start()
                        } else {
                            imgCoverThumb.alpha = 1f
                            imgCoverThumb.setImageResource(R.drawable.ic_file_audio)
                        }
                    }
                }
            }

            txtResolutionBadge.text = item.resolutionLabel()
            txtSourceBadge.text = item.source.displayName
            txtAlbumTitle.text = item.albumTitle.ifBlank { "Unknown Album" }
            txtArtistName.text = item.artistName.ifBlank { "Unknown Artist" }

            itemView.setOnClickListener {
                onCoverClick(item)
            }
        }

        private fun decodeSampledBitmap(bytes: ByteArray, reqWidth: Int, reqHeight: Int): Bitmap? {
            return try {
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)

                var inSampleSize = 1
                if (options.outHeight > reqHeight || options.outWidth > reqWidth) {
                    val halfHeight = options.outHeight / 2
                    val halfWidth = options.outWidth / 2
                    while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                        inSampleSize *= 2
                    }
                }

                val decodeOptions = BitmapFactory.Options().apply {
                    this.inSampleSize = inSampleSize
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
            } catch (_: Throwable) {
                null
            }
        }
    }
}
