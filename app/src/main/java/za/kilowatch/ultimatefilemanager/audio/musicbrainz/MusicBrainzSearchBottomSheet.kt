package za.kilowatch.ultimatefilemanager.audio.musicbrainz

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.audio.musicbrainz.model.MbRecordingItem
import za.kilowatch.ultimatefilemanager.audio.musicbrainz.model.MbReleaseDetails
import za.kilowatch.ultimatefilemanager.audio.musicbrainz.model.MbReleaseSummary

/**
 * UFMStandard Glass Bottom Sheet for searching and identifying recordings and album releases on MusicBrainz.
 */
class MusicBrainzSearchBottomSheet : BottomSheetDialogFragment() {

    companion object {
        const val TAG = "MusicBrainzSearchBottomSheet"
        private const val ARG_TITLE = "arg_title"
        private const val ARG_ARTIST = "arg_artist"
        private const val ARG_ALBUM = "arg_album"
        private const val ARG_IS_BATCH = "arg_is_batch"

        fun newInstance(
            title: String,
            artist: String,
            album: String,
            isBatch: Boolean = false
        ): MusicBrainzSearchBottomSheet {
            return MusicBrainzSearchBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_TITLE, title)
                    putString(ARG_ARTIST, artist)
                    putString(ARG_ALBUM, album)
                    putBoolean(ARG_IS_BATCH, isBatch)
                }
            }
        }
    }

    private var onReleaseDetailsResolved: ((details: MbReleaseDetails, artwork: Pair<ByteArray, String>?) -> Unit)? = null
    private var onRecordingResolved: ((recording: MbRecordingItem, releaseDetails: MbReleaseDetails?, artwork: Pair<ByteArray, String>?) -> Unit)? = null

    fun setOnReleaseDetailsResolved(listener: (details: MbReleaseDetails, artwork: Pair<ByteArray, String>?) -> Unit) {
        this.onReleaseDetailsResolved = listener
    }

    fun setOnRecordingResolved(listener: (recording: MbRecordingItem, releaseDetails: MbReleaseDetails?, artwork: Pair<ByteArray, String>?) -> Unit) {
        this.onRecordingResolved = listener
    }

    private lateinit var edtSearchQuery: TextInputEditText
    private lateinit var btnSearch: MaterialButton
    private lateinit var toggleSearchMode: MaterialButtonToggleGroup
    private lateinit var layoutLoading: LinearLayout
    private lateinit var layoutEmpty: LinearLayout
    private lateinit var recyclerCandidates: RecyclerView
    private lateinit var adapter: MusicBrainzCandidateAdapter

    private var isReleasesMode = false

    override fun getTheme(): Int = R.style.TransparentBottomSheetDialog

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.bottom_sheet_musicbrainz_search, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<ImageView>(R.id.btnClose).setOnClickListener {
            dismiss()
        }

        edtSearchQuery = view.findViewById(R.id.edtSearchQuery)
        btnSearch = view.findViewById(R.id.btnSearch)
        toggleSearchMode = view.findViewById(R.id.toggleSearchMode)
        layoutLoading = view.findViewById(R.id.layoutLoading)
        layoutEmpty = view.findViewById(R.id.layoutEmpty)
        recyclerCandidates = view.findViewById(R.id.recyclerCandidates)

        val initialTitle = arguments?.getString(ARG_TITLE) ?: ""
        val initialArtist = arguments?.getString(ARG_ARTIST) ?: ""
        val initialAlbum = arguments?.getString(ARG_ALBUM) ?: ""
        val isBatch = arguments?.getBoolean(ARG_IS_BATCH, false) ?: false

        isReleasesMode = isBatch || (initialTitle.isBlank() && initialAlbum.isNotBlank())
        if (isReleasesMode) {
            toggleSearchMode.check(R.id.btnModeReleases)
        } else {
            toggleSearchMode.check(R.id.btnModeRecordings)
        }

        val defaultQuery = if (isReleasesMode) {
            if (initialArtist.isNotBlank() && initialAlbum.isNotBlank()) "$initialArtist $initialAlbum" else initialAlbum.ifBlank { initialArtist }
        } else {
            if (initialArtist.isNotBlank() && initialTitle.isNotBlank()) "$initialArtist $initialTitle" else initialTitle.ifBlank { initialArtist }
        }

        edtSearchQuery.setText(defaultQuery)

        adapter = MusicBrainzCandidateAdapter { candidate ->
            resolveCandidate(candidate)
        }
        recyclerCandidates.adapter = adapter

        toggleSearchMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                isReleasesMode = (checkedId == R.id.btnModeReleases)
                val currentText = edtSearchQuery.text?.toString()?.trim() ?: ""
                if (currentText.isNotBlank()) {
                    performSearch(currentText)
                }
            }
        }

        btnSearch.setOnClickListener {
            val query = edtSearchQuery.text?.toString()?.trim() ?: ""
            if (query.isNotBlank()) {
                performSearch(query)
            }
        }

        edtSearchQuery.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                btnSearch.performClick()
                true
            } else {
                false
            }
        }

        if (defaultQuery.isNotBlank()) {
            performSearch(defaultQuery)
        } else {
            layoutEmpty.visibility = View.VISIBLE
        }
    }

    private fun performSearch(query: String) {
        layoutLoading.visibility = View.VISIBLE
        layoutEmpty.visibility = View.GONE
        recyclerCandidates.visibility = View.GONE

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val candidates = if (isReleasesMode) {
                val releases = MusicBrainzClient.searchReleases(album = query, artist = "", limit = 15)
                releases.map { MbCandidate.Release(it) }
            } else {
                val recordings = MusicBrainzClient.searchRecordings(title = query, artist = "", limit = 15)
                recordings.map { MbCandidate.Recording(it) }
            }

            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                layoutLoading.visibility = View.GONE
                if (candidates.isEmpty()) {
                    layoutEmpty.visibility = View.VISIBLE
                    recyclerCandidates.visibility = View.GONE
                } else {
                    layoutEmpty.visibility = View.GONE
                    recyclerCandidates.visibility = View.VISIBLE
                    adapter.submitList(candidates)
                }
            }
        }
    }

    private fun resolveCandidate(candidate: MbCandidate) {
        layoutLoading.visibility = View.VISIBLE
        recyclerCandidates.visibility = View.GONE

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            when (candidate) {
                is MbCandidate.Release -> {
                    val mbid = candidate.item.id
                    val details = MusicBrainzClient.lookupRelease(mbid)
                    val coverUrl = CoverArtArchiveClient.getFrontCoverUrl(mbid)
                    val artwork = if (coverUrl != null) CoverArtArchiveClient.downloadArtwork(coverUrl) else null

                    withContext(Dispatchers.Main) {
                        if (!isAdded) return@withContext
                        layoutLoading.visibility = View.GONE
                        if (details != null) {
                            onReleaseDetailsResolved?.invoke(details, artwork)
                            dismiss()
                        } else {
                            recyclerCandidates.visibility = View.VISIBLE
                            Toast.makeText(requireContext(), "Failed to fetch release details", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                is MbCandidate.Recording -> {
                    val rec = candidate.item
                    val releaseMbid = rec.releases.firstOrNull()?.id
                    val details = if (!releaseMbid.isNullOrBlank()) {
                        MusicBrainzClient.lookupRelease(releaseMbid)
                    } else null

                    val coverUrl = if (!releaseMbid.isNullOrBlank()) {
                        CoverArtArchiveClient.getFrontCoverUrl(releaseMbid)
                    } else null
                    val artwork = if (coverUrl != null) CoverArtArchiveClient.downloadArtwork(coverUrl) else null

                    withContext(Dispatchers.Main) {
                        if (!isAdded) return@withContext
                        layoutLoading.visibility = View.GONE
                        onRecordingResolved?.invoke(rec, details, artwork)
                        dismiss()
                    }
                }
            }
        }
    }
}
