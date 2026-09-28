package za.kilowatch.ultimatefilemanager.audio.covers

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.audio.covers.model.CoverSearchResult

/**
 * UFMStandard Glass Bottom Sheet for searching and selecting high-resolution cover artwork.
 * Searches Apple Music (up to 3000x3000px), Deezer (1000x1000px), and Cover Art Archive concurrently.
 */
class OnlineCoverPickerBottomSheet : BottomSheetDialogFragment() {

    companion object {
        const val TAG = "OnlineCoverPickerBottomSheet"
        private const val ARG_ALBUM = "arg_album"
        private const val ARG_ARTIST = "arg_artist"
        private const val ARG_MBID = "arg_mbid"

        fun newInstance(
            album: String,
            artist: String,
            mbid: String? = null
        ): OnlineCoverPickerBottomSheet {
            return OnlineCoverPickerBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_ALBUM, album)
                    putString(ARG_ARTIST, artist)
                    putString(ARG_MBID, mbid)
                }
            }
        }
    }

    private var onCoverSelectedListener: ((bytes: ByteArray, mimeType: String, result: CoverSearchResult) -> Unit)? = null

    fun setOnCoverSelectedListener(listener: (bytes: ByteArray, mimeType: String, result: CoverSearchResult) -> Unit) {
        this.onCoverSelectedListener = listener
    }

    private lateinit var edtSearchQuery: TextInputEditText
    private lateinit var btnSearch: MaterialButton
    private lateinit var layoutLoading: LinearLayout
    private lateinit var layoutEmpty: LinearLayout
    private lateinit var recyclerCovers: RecyclerView
    private lateinit var adapter: OnlineCoverPickerAdapter

    override fun getTheme(): Int = R.style.TransparentBottomSheetDialog

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.bottom_sheet_online_cover_picker, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<ImageView>(R.id.btnClose).setOnClickListener {
            dismiss()
        }

        edtSearchQuery = view.findViewById(R.id.edtSearchQuery)
        btnSearch = view.findViewById(R.id.btnSearch)
        layoutLoading = view.findViewById(R.id.layoutLoading)
        layoutEmpty = view.findViewById(R.id.layoutEmpty)
        recyclerCovers = view.findViewById(R.id.recyclerCovers)

        adapter = OnlineCoverPickerAdapter { candidate ->
            downloadAndApply(candidate)
        }
        recyclerCovers.adapter = adapter

        val initialAlbum = arguments?.getString(ARG_ALBUM) ?: ""
        val initialArtist = arguments?.getString(ARG_ARTIST) ?: ""
        val initialMbid = arguments?.getString(ARG_MBID)

        val defaultQuery = if (initialArtist.isNotBlank() && initialAlbum.isNotBlank()) {
            "$initialArtist $initialAlbum"
        } else if (initialAlbum.isNotBlank()) {
            initialAlbum
        } else {
            initialArtist
        }

        edtSearchQuery.setText(defaultQuery)

        btnSearch.setOnClickListener {
            val query = edtSearchQuery.text?.toString()?.trim() ?: ""
            if (query.isNotBlank()) {
                performSearch(query, initialMbid)
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
            performSearch(defaultQuery, initialMbid)
        } else {
            layoutEmpty.visibility = View.VISIBLE
        }
    }

    private fun performSearch(query: String, mbid: String?) {
        layoutLoading.visibility = View.VISIBLE
        layoutEmpty.visibility = View.GONE
        recyclerCovers.visibility = View.GONE

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val results = CoverSearchAggregator.searchArtwork(album = query, artist = "", mbid = mbid)
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                layoutLoading.visibility = View.GONE
                if (results.isEmpty()) {
                    layoutEmpty.visibility = View.VISIBLE
                    recyclerCovers.visibility = View.GONE
                } else {
                    layoutEmpty.visibility = View.GONE
                    recyclerCovers.visibility = View.VISIBLE
                    adapter.submitList(results)
                }
            }
        }
    }

    private fun downloadAndApply(candidate: CoverSearchResult) {
        layoutLoading.visibility = View.VISIBLE
        recyclerCovers.visibility = View.GONE
        layoutEmpty.visibility = View.GONE

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val downloaded = CoverSearchAggregator.downloadImageBytes(candidate.fullImageUrl)
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                layoutLoading.visibility = View.GONE
                if (downloaded != null) {
                    onCoverSelectedListener?.invoke(downloaded.first, downloaded.second, candidate)
                    dismiss()
                } else {
                    recyclerCovers.visibility = View.VISIBLE
                    Toast.makeText(requireContext(), R.string.music_tag_cover_download_error, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
