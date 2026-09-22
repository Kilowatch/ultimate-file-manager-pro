package za.kilowatch.ultimatefilemanager.viewer

import android.app.Dialog
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
 * Universal Audio Track Selection Modal for Mobile and Android TV.
 *
 * Allows users to choose which embedded audio track to extract,
 * or extract all tracks at once into separate language-tagged lossless audio files.
 */
class AudioTrackBottomSheet : BottomSheetDialogFragment() {

    private var fileName: String? = null
    private var tracks: ArrayList<FFmpegMediaHelper.MediaStreamInfo> = ArrayList()
    private var isTv: Boolean = false

    var onTrackSelected: ((streamIndex: Int, langTag: String, extractAll: Boolean, universalM4a: Boolean) -> Unit)? = null

    data class TrackOption(
        val streamIndex: Int,
        val langTag: String,
        val title: String,
        val subtitle: String,
        val badge: String,
        val isExtractAll: Boolean = false
    )

    companion object {
        const val TAG = "AudioTrackBottomSheet"
        private const val ARG_FILE_NAME = "arg_file_name"
        private const val ARG_TRACKS = "arg_tracks"

        fun newInstance(
            fileName: String,
            tracks: List<FFmpegMediaHelper.MediaStreamInfo>
        ): AudioTrackBottomSheet {
            return AudioTrackBottomSheet().apply {
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
        val layoutRes = if (isTv) R.layout.dialog_audio_tracks_tv else R.layout.dialog_audio_tracks
        return inflater.inflate(layoutRes, container, false)
    }

    private fun buildOptions(universalM4a: Boolean): List<TrackOption> {
        val options = mutableListOf<TrackOption>()

        // Add "Extract All" if more than 1 track
        if (tracks.size > 1) {
            options.add(
                TrackOption(
                    streamIndex = -1,
                    langTag = "all",
                    title = getString(R.string.extract_all_audio),
                    subtitle = getString(R.string.extract_all_audio_desc, tracks.size),
                    badge = if (universalM4a) "ALL M4A" else "ALL",
                    isExtractAll = true
                )
            )
        }

        // Add each individual track
        for (track in tracks) {
            val displayLang = FFmpegMediaHelper.getLanguageDisplayName(track.lang)
            val channelLayout = FFmpegMediaHelper.getChannelLayoutDisplayName(track.channels)

            val titleText = when {
                displayLang.isNotBlank() && channelLayout.isNotBlank() && track.title.isNotBlank() ->
                    "$displayLang • $channelLayout (${track.title})"
                displayLang.isNotBlank() && channelLayout.isNotBlank() ->
                    "$displayLang • $channelLayout"
                displayLang.isNotBlank() && track.title.isNotBlank() ->
                    "$displayLang (${track.title})"
                displayLang.isNotBlank() -> "$displayLang [${track.lang}]"
                track.title.isNotBlank() -> track.title
                channelLayout.isNotBlank() -> channelLayout
                track.lang.isNotBlank() -> track.lang.uppercase()
                else -> "Audio Track #${track.index}"
            }

            val details = mutableListOf<String>()
            details.add("Track ${track.index}")
            if (track.sampleRate > 0) {
                details.add("${track.sampleRate} Hz")
            }
            if (track.bitrate > 0) {
                details.add("${track.bitrate / 1000} kbps")
            }
            val subtitleText = details.joinToString(" • ")

            val badgeText = if (universalM4a) {
                "M4A (AAC)"
            } else {
                if (track.codec.isNotBlank()) track.codec.uppercase() else "AUDIO"
            }

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
        return options
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val txtFileName = view.findViewById<TextView>(R.id.txtFileName)
        txtFileName.text = fileName ?: ""

        val rvTracks = view.findViewById<RecyclerView>(R.id.rvTracks)
        rvTracks.layoutManager = LinearLayoutManager(context)

        var isUniversalM4a = true
        val toggleFormat = view.findViewById<com.google.android.material.button.MaterialButtonToggleGroup?>(R.id.toggleFormat)
        val txtFormatDesc = view.findViewById<TextView?>(R.id.txtFormatDesc)

        fun updateAdapter() {
            rvTracks.adapter = AudioTrackAdapter(buildOptions(isUniversalM4a), isTv) { opt ->
                onTrackSelected?.invoke(opt.streamIndex, opt.langTag, opt.isExtractAll, isUniversalM4a)
                dismiss()
            }
        }

        toggleFormat?.check(R.id.btnFormatUniversal)
        toggleFormat?.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                isUniversalM4a = (checkedId == R.id.btnFormatUniversal)
                txtFormatDesc?.setText(
                    if (isUniversalM4a) R.string.audio_format_universal_desc
                    else R.string.audio_format_original_desc
                )
                updateAdapter()
            }
        }

        updateAdapter()

        view.findViewById<MaterialButton?>(R.id.btnCancel)?.setOnClickListener {
            dismiss()
        }
    }

    private class AudioTrackAdapter(
        private val items: List<TrackOption>,
        private val isTv: Boolean,
        private val onItemClick: (TrackOption) -> Unit
    ) : RecyclerView.Adapter<AudioTrackAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val imgIcon: ImageView = view.findViewById(R.id.imgIcon)
            val txtTitle: TextView = view.findViewById(R.id.txtTitle)
            val txtDescription: TextView = view.findViewById(R.id.txtDescription)
            val txtBadge: TextView = view.findViewById(R.id.txtBadge)
            val itemContainer: View = view.findViewById(R.id.itemContainer)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(
                R.layout.item_audio_track,
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
                holder.imgIcon.setImageResource(R.drawable.ic_audio)
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
