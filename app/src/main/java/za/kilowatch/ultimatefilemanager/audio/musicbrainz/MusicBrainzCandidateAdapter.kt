package za.kilowatch.ultimatefilemanager.audio.musicbrainz

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.audio.musicbrainz.model.MbRecordingItem
import za.kilowatch.ultimatefilemanager.audio.musicbrainz.model.MbReleaseSummary

sealed class MbCandidate {
    data class Recording(val item: MbRecordingItem) : MbCandidate()
    data class Release(val item: MbReleaseSummary) : MbCandidate()
}

class MusicBrainzCandidateAdapter(
    private val candidates: MutableList<MbCandidate> = mutableListOf(),
    private val onCandidateSelected: (MbCandidate) -> Unit
) : RecyclerView.Adapter<MusicBrainzCandidateAdapter.CandidateViewHolder>() {

    fun submitList(newCandidates: List<MbCandidate>) {
        candidates.clear()
        candidates.addAll(newCandidates)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CandidateViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_musicbrainz_candidate, parent, false)
        return CandidateViewHolder(view)
    }

    override fun onBindViewHolder(holder: CandidateViewHolder, position: Int) {
        holder.bind(candidates[position])
    }

    override fun getItemCount(): Int = candidates.size

    inner class CandidateViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val imgType: ImageView = itemView.findViewById(R.id.imgCandidateType)
        private val txtTitle: TextView = itemView.findViewById(R.id.txtCandidateTitle)
        private val txtArtist: TextView = itemView.findViewById(R.id.txtCandidateArtist)
        private val txtDetails: TextView = itemView.findViewById(R.id.txtCandidateDetails)

        fun bind(candidate: MbCandidate) {
            when (candidate) {
                is MbCandidate.Recording -> {
                    val rec = candidate.item
                    imgType.setImageResource(R.drawable.ic_file_audio)
                    txtTitle.text = rec.title
                    txtArtist.text = rec.displayArtist().ifBlank { "Unknown Artist" }

                    val rel = rec.releases.firstOrNull()
                    val albumPart = if (rel != null) rel.title else ""
                    val yearPart = rel?.releaseYear() ?: ""
                    val countryPart = rel?.country ?: ""

                    val detailParts = listOfNotNull(
                        albumPart.takeIf { it.isNotBlank() },
                        yearPart.takeIf { it.isNotBlank() },
                        countryPart.takeIf { it.isNotBlank() }
                    )
                    txtDetails.text = detailParts.joinToString(" · ").ifBlank { "Recording" }
                }
                is MbCandidate.Release -> {
                    val rel = candidate.item
                    imgType.setImageResource(R.drawable.ic_tune)
                    txtTitle.text = rel.title
                    txtArtist.text = rel.displayArtist().ifBlank { "Unknown Artist" }

                    val yearPart = rel.releaseYear()
                    val countryPart = rel.country ?: ""
                    val tracksPart = if (rel.trackCount > 0) "${rel.trackCount} tracks" else ""

                    val detailParts = listOfNotNull(
                        yearPart.takeIf { it.isNotBlank() },
                        countryPart.takeIf { it.isNotBlank() },
                        tracksPart.takeIf { it.isNotBlank() },
                        rel.status?.takeIf { it.isNotBlank() }
                    )
                    txtDetails.text = detailParts.joinToString(" · ").ifBlank { "Album Release" }
                }
            }

            itemView.setOnClickListener {
                onCandidateSelected(candidate)
            }
        }
    }
}
