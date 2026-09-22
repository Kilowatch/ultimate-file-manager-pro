package za.kilowatch.ultimatefilemanager.viewer

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.media.FFmpegMediaHelper
import za.kilowatch.ultimatefilemanager.settings.ColorblindPalette
import za.kilowatch.ultimatefilemanager.util.DeviceUtils

/**
 * Universal Subtitle Track Selection Modal for Mobile and Android TV.
 *
 * Allows users to choose which embedded subtitle track/language to extract,
 * or extract all tracks at once into separate language-tagged files.
 */
class SubtitleTrackBottomSheet : BottomSheetDialogFragment() {

    private var fileName: String? = null
    private var tracks: ArrayList<FFmpegMediaHelper.MediaStreamInfo> = ArrayList()
    private var isTv: Boolean = false

    var onTrackSelected: ((streamIndex: Int, langTag: String, extractAll: Boolean) -> Unit)? = null

    data class TrackOption(
        val streamIndex: Int,
        val langTag: String,
        val title: String,
        val subtitle: String,
        val badge: String,
        val isExtractAll: Boolean = false
    )

    companion object {
        const val TAG = "SubtitleTrackBottomSheet"
        private const val ARG_FILE_NAME = "arg_file_name"
        private const val ARG_TRACKS = "arg_tracks"

        fun newInstance(
            fileName: String,
            tracks: List<FFmpegMediaHelper.MediaStreamInfo>
        ): SubtitleTrackBottomSheet {
            return SubtitleTrackBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_FILE_NAME, fileName)
                    putSerializable(ARG_TRACKS, ArrayList(tracks))
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fileName = arguments?.getString(ARG_FILE_NAME)
        @Suppress("DEPRECATION", "UNCHECKED_CAST")
        tracks = (arguments?.getSerializable(ARG_TRACKS) as? ArrayList<FFmpegMediaHelper.MediaStreamInfo>) ?: ArrayList()
        isTv = DeviceUtils.isTvDevice(requireContext())
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        isTv = DeviceUtils.isTvDevice(context)
        if (isTv) {
            val tvDialog = Dialog(context).apply {
                window?.apply {
                    setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                    setLayout(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }
            }
            return tvDialog
        }

        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            if (bottomSheet != null) {
                val behavior = BottomSheetBehavior.from(bottomSheet)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
            }
        }
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val layoutRes = if (isTv) R.layout.dialog_subtitle_tracks_tv else R.layout.dialog_subtitle_tracks
        return inflater.inflate(layoutRes, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val txtFileName = view.findViewById<TextView>(R.id.txtFileName)
        txtFileName.text = fileName ?: ""

        val rvTracks = view.findViewById<RecyclerView>(R.id.rvTracks)
        rvTracks.layoutManager = LinearLayoutManager(context)

        val options = mutableListOf<TrackOption>()

        // Add "Extract All" if more than 1 track
        if (tracks.size > 1) {
            options.add(
                TrackOption(
                    streamIndex = -1,
                    langTag = "all",
                    title = getString(R.string.extract_all_subtitles),
                    subtitle = getString(R.string.extract_all_subtitles_desc, tracks.size),
                    badge = "ALL",
                    isExtractAll = true
                )
            )
        }

        // Add each individual track
        for (track in tracks) {
            val displayLang = FFmpegMediaHelper.getLanguageDisplayName(track.lang)
            val titleText = when {
                displayLang.isNotBlank() && track.title.isNotBlank() -> "$displayLang (${track.title})"
                displayLang.isNotBlank() -> "$displayLang [${track.lang}]"
                track.title.isNotBlank() -> track.title
                track.lang.isNotBlank() -> track.lang.uppercase()
                else -> "Subtitle Track #${track.index}"
            }
            val subtitleText = "Track ${track.index} • ${track.codec.uppercase()}"
            val badgeText = if (track.codec.isNotBlank()) track.codec.uppercase() else "SUB"

            options.add(
                TrackOption(
                    streamIndex = track.index,
                    langTag = track.lang,
                    title = titleText,
                    subtitle = subtitleText,
                    badge = badgeText,
                    isExtractAll = false
                )
            )
        }

        rvTracks.adapter = SubtitleTrackAdapter(options, isTv) { opt ->
            onTrackSelected?.invoke(opt.streamIndex, opt.langTag, opt.isExtractAll)
            dismiss()
        }

        view.findViewById<MaterialButton?>(R.id.btnCancel)?.setOnClickListener {
            dismiss()
        }
    }

    private class SubtitleTrackAdapter(
        private val items: List<TrackOption>,
        private val isTv: Boolean,
        private val onItemClick: (TrackOption) -> Unit
    ) : RecyclerView.Adapter<SubtitleTrackAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val imgIcon: ImageView = view.findViewById(R.id.imgIcon)
            val txtTitle: TextView = view.findViewById(R.id.txtTitle)
            val txtDescription: TextView = view.findViewById(R.id.txtDescription)
            val txtBadge: TextView = view.findViewById(R.id.txtBadge)
            val itemContainer: View = view.findViewById(R.id.itemContainer)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(
                R.layout.item_subtitle_track,
                parent,
                false
            )
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            val context = holder.itemView.context

            if (item.isExtractAll) {
                holder.imgIcon.setImageResource(R.drawable.ic_select_all)
            } else {
                holder.imgIcon.setImageResource(R.drawable.ic_subtitles)
            }

            holder.txtTitle.text = item.title
            holder.txtDescription.text = item.subtitle
            holder.txtBadge.text = item.badge

            holder.itemContainer.setOnClickListener {
                onItemClick(item)
            }

            if (isTv) {
                holder.itemContainer.setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus) {
                        holder.itemContainer.setBackgroundColor(ColorblindPalette.focusFill(context))
                        holder.txtTitle.setTextColor(ColorblindPalette.focusFillText(context))
                        holder.txtDescription.setTextColor(ColorblindPalette.focusFillText(context))
                        holder.txtBadge.setTextColor(ColorblindPalette.focusFillText(context))
                    } else {
                        holder.itemContainer.setBackgroundColor(Color.TRANSPARENT)
                        holder.txtTitle.setTextColor(ContextCompat.getColor(context, R.color.tv_text_primary))
                        holder.txtDescription.setTextColor(ContextCompat.getColor(context, R.color.tv_text_secondary))
                        holder.txtBadge.setTextColor(ContextCompat.getColor(context, R.color.tv_text_primary))
                    }
                }
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
