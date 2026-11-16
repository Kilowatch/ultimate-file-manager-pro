package za.kilowatch.ultimatefilemanager.download

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import za.kilowatch.ultimatefilemanager.R

/**
 * RecyclerView adapter for active and completed transfers in Download Manager.
 * Supports stable IDs and granular payload updates to guarantee Android TV D-pad focus retention.
 */
class DownloadTransferAdapter(
    private val isTv: Boolean,
    private val onPauseResumeClicked: (DownloadTransferManager.TransferItem) -> Unit,
    private val onCancelClicked: (DownloadTransferManager.TransferItem) -> Unit,
    private val onItemClicked: ((DownloadTransferManager.TransferItem) -> Unit)? = null
) : RecyclerView.Adapter<DownloadTransferAdapter.TransferViewHolder>() {

    companion object {
        const val PAYLOAD_PROGRESS = "payload_progress"
    }

    private val items = mutableListOf<DownloadTransferManager.TransferItem>()

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long {
        return fnv1a64(items[position].id)
    }

    private fun fnv1a64(str: String): Long {
        var hash = -0x340d631b7bdddcdbL // 0xcbf29ce484222325L (FNV-1a 64-bit offset basis)
        for (c in str) {
            hash = hash xor c.code.toLong()
            hash *= 0x100000001b3L // FNV-1a 64-bit prime
        }
        return hash
    }

    fun submitList(newItems: List<DownloadTransferManager.TransferItem>) {
        if (items.isEmpty() && newItems.isNotEmpty()) {
            items.addAll(newItems)
            notifyDataSetChanged()
            return
        }
        if (newItems.isEmpty()) {
            val count = items.size
            items.clear()
            notifyItemRangeRemoved(0, count)
            return
        }

        val oldIds = items.map { it.id }
        val newIds = newItems.map { it.id }

        if (oldIds != newIds) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
            return
        }

        // Exact same item list: update in place with PAYLOAD_PROGRESS to prevent D-pad focus loss!
        for (i in newItems.indices) {
            items[i] = newItems[i]
            notifyItemChanged(i, PAYLOAD_PROGRESS)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TransferViewHolder {
        val layoutRes = if (isTv) R.layout.item_download_transfer_tv else R.layout.item_download_transfer
        val view = LayoutInflater.from(parent.context).inflate(layoutRes, parent, false)
        return TransferViewHolder(view)
    }

    override fun onBindViewHolder(holder: TransferViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_PROGRESS)) {
            holder.bindProgressOnly(items[position])
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    override fun onBindViewHolder(holder: TransferViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class TransferViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val imgIcon: ImageView? = itemView.findViewById(R.id.imgTransferIcon)
        private val txtTitle: TextView? = itemView.findViewById(R.id.txtTransferTitle)
        private val txtSizeSpeed: TextView? = itemView.findViewById(R.id.txtTransferSizeSpeed)
        private val txtEtaPeers: TextView? = itemView.findViewById(R.id.txtTransferEtaPeers)
        private val txtPercent: TextView? = itemView.findViewById(R.id.txtTransferPercent)
        private val progressBar: ProgressBar? = itemView.findViewById(R.id.progressTransfer)
        private val btnPauseResume: ImageView? = itemView.findViewById(R.id.btnTransferPauseResume)
        private val btnCancel: ImageView? = itemView.findViewById(R.id.btnTransferCancel)

        fun bind(item: DownloadTransferManager.TransferItem) {
            txtTitle?.text = item.title

            val iconRes = when (item.type) {
                DownloadTransferManager.TransferType.DIRECT_HTTP -> R.drawable.ic_link
                DownloadTransferManager.TransferType.TORRENT -> R.drawable.ic_file_torrent
                DownloadTransferManager.TransferType.MAGNET -> R.drawable.ic_magnet
            }
            imgIcon?.setImageResource(iconRes)

            if (isTv) {
                btnPauseResume?.isFocusable = false
                btnCancel?.isFocusable = false
            }

            bindProgressOnly(item)

            itemView.setOnClickListener {
                if (onItemClicked != null) {
                    onItemClicked.invoke(item)
                } else if (item.state != DownloadTransferManager.TransferState.COMPLETED) {
                    onPauseResumeClicked(item)
                }
            }
            btnPauseResume?.setOnClickListener { onPauseResumeClicked(item) }
            btnCancel?.setOnClickListener { onCancelClicked(item) }
        }

        fun bindProgressOnly(item: DownloadTransferManager.TransferItem) {
            progressBar?.progress = item.progressPercent
            txtPercent?.text = "${item.progressPercent}%"

            val speedText = formatSpeed(item.speedBytesPerSec)
            val downloadedText = formatSize(item.downloadedBytes)
            val totalText = if (item.totalBytes > 0) formatSize(item.totalBytes) else ""

            // Row 1: xx / xx Downloaded • Speed
            txtSizeSpeed?.text = when (item.state) {
                DownloadTransferManager.TransferState.DOWNLOADING -> {
                    if (totalText.isNotEmpty()) {
                        "$downloadedText / $totalText • $speedText"
                    } else {
                        "$downloadedText • $speedText"
                    }
                }
                DownloadTransferManager.TransferState.PAUSED -> {
                    if (totalText.isNotEmpty()) "Paused • $downloadedText / $totalText" else "Paused"
                }
                DownloadTransferManager.TransferState.QUEUED_WIFI -> {
                    if (totalText.isNotEmpty()) "Waiting for Wi-Fi • $downloadedText / $totalText" else "Waiting for Wi-Fi"
                }
                DownloadTransferManager.TransferState.COMPLETED -> {
                    if (totalText.isNotEmpty()) "Completed • $totalText" else "Completed"
                }
                DownloadTransferManager.TransferState.ERROR -> {
                    "Error: ${item.errorMessage ?: "Failed"}"
                }
            }

            // Row 2: ETA • Peers & Seeds
            txtEtaPeers?.text = when (item.state) {
                DownloadTransferManager.TransferState.DOWNLOADING -> {
                    if (item.type == DownloadTransferManager.TransferType.TORRENT ||
                        item.type == DownloadTransferManager.TransferType.MAGNET) {
                        if (item.speedBytesPerSec == 0L && item.connectedPeers == 0) {
                            if (item.totalPeers > 0) {
                                "Connecting... (${item.totalPeers} in swarm)"
                            } else {
                                "Connecting to tracker & peers..."
                            }
                        } else {
                            val etaPart = if (item.etaSeconds > 0) "ETA: ${formatEta(item.etaSeconds)}" else "ETA: Calculating..."
                            val swarmPart = if (item.totalPeers > 0) " (${item.totalPeers} in swarm)" else ""
                            "$etaPart • Peers: ${item.connectedPeers}$swarmPart • Seeds: ${item.connectedSeeds}"
                        }
                    } else {
                        if (item.etaSeconds > 0) {
                            "ETA: ${formatEta(item.etaSeconds)} • Direct HTTP"
                        } else {
                            "Direct HTTP"
                        }
                    }
                }
                DownloadTransferManager.TransferState.PAUSED -> "Transfer paused"
                DownloadTransferManager.TransferState.QUEUED_WIFI -> "Will resume automatically when Wi-Fi is connected"
                DownloadTransferManager.TransferState.COMPLETED -> "Download finished"
                DownloadTransferManager.TransferState.ERROR -> "Transfer interrupted"
            }

            if (item.state == DownloadTransferManager.TransferState.PAUSED ||
                item.state == DownloadTransferManager.TransferState.QUEUED_WIFI) {
                btnPauseResume?.setImageResource(R.drawable.ic_play)
            } else {
                btnPauseResume?.setImageResource(R.drawable.ic_pause)
            }

            btnPauseResume?.visibility = if (item.state == DownloadTransferManager.TransferState.COMPLETED) View.GONE else View.VISIBLE
        }

        private fun formatSize(bytes: Long): String {
            return when {
                bytes >= 1024L * 1024L * 1024L -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
                bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
                bytes >= 1024L -> String.format("%.0f KB", bytes / 1024.0)
                else -> "$bytes B"
            }
        }

        private fun formatSpeed(bytesPerSec: Long): String {
            return when {
                bytesPerSec >= 1024 * 1024 -> String.format("%.1f MB/s", bytesPerSec / (1024.0 * 1024.0))
                bytesPerSec >= 1024 -> String.format("%.0f KB/s", bytesPerSec / 1024.0)
                else -> "$bytesPerSec B/s"
            }
        }

        private fun formatEta(seconds: Long): String {
            val mins = seconds / 60
            val secs = seconds % 60
            return if (mins > 0) "${mins}m ${secs}s" else "${secs}s"
        }
    }
}
