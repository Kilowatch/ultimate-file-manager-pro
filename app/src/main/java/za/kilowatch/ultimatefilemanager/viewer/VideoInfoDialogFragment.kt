package za.kilowatch.ultimatefilemanager.viewer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.util.DeviceUtils

/**
 * Inspector dialog displaying technical video, audio, subtitle, and container specifications.
 * Rendered as an adaptive BottomSheet on mobile devices and a centered modal dialog on Android TV.
 */
class VideoInfoDialogFragment : DialogFragment() {

    companion object {
        const val TAG = "VideoInfoDialogFragment"
        private const val ARG_DETAILS = "arg_details"

        fun newInstance(details: VideoTechnicalDetails): VideoInfoDialogFragment {
            return VideoInfoDialogFragment().apply {
                arguments = Bundle().apply {
                    putSerializable(ARG_DETAILS, details)
                }
            }
        }
    }

    private var details: VideoTechnicalDetails? = null
    private var isTv = false
    var onDismissCallback: (() -> Unit)? = null

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        onDismissCallback?.invoke()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        details = arguments?.getSerializable(ARG_DETAILS) as? VideoTechnicalDetails
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): android.app.Dialog {
        isTv = DeviceUtils.isTvDevice(requireContext())
        return if (isTv) {
            super.onCreateDialog(savedInstanceState)
        } else {
            val dialog = BottomSheetDialog(requireContext(), theme)
            dialog.setOnShowListener {
                val sheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                if (sheet != null) {
                    val behavior = BottomSheetBehavior.from(sheet)
                    behavior.state = BottomSheetBehavior.STATE_EXPANDED
                    behavior.skipCollapsed = true
                }
            }
            dialog
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val layoutRes = if (isTv) R.layout.dialog_video_info_tv else R.layout.dialog_video_info
        return inflater.inflate(layoutRes, container, false)
    }

    override fun onStart() {
        super.onStart()
        val dialog = dialog ?: return
        val window = dialog.window ?: return

        if (isTv) {
            val screenWidth = requireContext().resources.displayMetrics.widthPixels
            window.setLayout(
                (screenWidth * 0.70f).toInt().coerceAtLeast(640),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            window.setGravity(Gravity.CENTER)
            window.setBackgroundDrawableResource(android.R.color.transparent)
        } else {
            window.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            window.setGravity(Gravity.BOTTOM)
            window.setBackgroundDrawableResource(android.R.color.transparent)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val data = details ?: run {
            dismissAllowingStateLoss()
            return
        }

        bindViews(view, data)
    }

    private fun bindViews(root: View, data: VideoTechnicalDetails) {
        val context = requireContext()
        val density = resources.displayMetrics.density

        // Header
        val imgHeaderIcon = root.findViewById<ImageView>(R.id.imgHeaderIcon)
        imgHeaderIcon.setImageResource(if (data.hasVideo) R.drawable.ic_file_video else R.drawable.ic_audio)

        val txtMediaTitle = root.findViewById<TextView>(R.id.txtMediaTitle)
        txtMediaTitle.text = data.fileName

        val txtMediaSubtitle = root.findViewById<TextView>(R.id.txtMediaSubtitle)
        val metaSummary = buildString {
            append(data.containerFormat)
            if (data.fileSizeFormatted != "N/A") append(" · ").append(data.fileSizeFormatted.substringBefore(" ("))
            if (data.durationFormatted != "00:00") append(" · ").append(data.durationFormatted)
        }
        txtMediaSubtitle.text = metaSummary

        // General / File Details
        root.findViewById<TextView>(R.id.txtContainer).text = data.containerFormat
        root.findViewById<TextView>(R.id.txtFileSize).text = data.fileSizeFormatted
        root.findViewById<TextView>(R.id.txtDuration).text = data.durationFormatted

        val rowOverallBitrate = root.findViewById<View>(R.id.rowOverallBitrate)
        val txtOverallBitrate = root.findViewById<TextView>(R.id.txtOverallBitrate)
        if (!data.overallBitrateFormatted.isNullOrEmpty()) {
            rowOverallBitrate.visibility = View.VISIBLE
            txtOverallBitrate.text = data.overallBitrateFormatted
        } else {
            rowOverallBitrate.visibility = View.GONE
        }

        root.findViewById<TextView>(R.id.txtLocation).text = data.filePath.ifEmpty { "N/A" }

        // Video Stream
        val layoutVideoSection = root.findViewById<View>(R.id.layoutVideoSection)
        if (data.hasVideo) {
            layoutVideoSection.visibility = View.VISIBLE
            root.findViewById<TextView>(R.id.txtVideoCodec).text = data.videoCodec ?: getString(R.string.video_info_not_available)

            val txtResolution = root.findViewById<TextView>(R.id.txtResolution)
            val resText = buildString {
                append(data.resolution ?: "N/A")
                if (!data.resolutionLabel.isNullOrEmpty()) {
                    append(" (").append(data.resolutionLabel).append(")")
                }
            }
            txtResolution.text = resText

            val rowFrameRate = root.findViewById<View>(R.id.rowFrameRate)
            val txtFrameRate = root.findViewById<TextView>(R.id.txtFrameRate)
            if (!data.frameRate.isNullOrEmpty()) {
                rowFrameRate.visibility = View.VISIBLE
                txtFrameRate.text = data.frameRate
            } else {
                rowFrameRate.visibility = View.GONE
            }

            val rowVideoBitrate = root.findViewById<View>(R.id.rowVideoBitrate)
            val txtVideoBitrate = root.findViewById<TextView>(R.id.txtVideoBitrate)
            if (!data.videoBitrate.isNullOrEmpty()) {
                rowVideoBitrate.visibility = View.VISIBLE
                txtVideoBitrate.text = data.videoBitrate
            } else {
                rowVideoBitrate.visibility = View.GONE
            }

            val rowHdr = root.findViewById<View>(R.id.rowHdr)
            val txtHdr = root.findViewById<TextView>(R.id.txtHdr)
            if (!data.hdrFormat.isNullOrEmpty()) {
                rowHdr.visibility = View.VISIBLE
                txtHdr.text = data.hdrFormat
            } else {
                rowHdr.visibility = View.GONE
            }

            val rowColorSpace = root.findViewById<View>(R.id.rowColorSpace)
            val txtColorSpace = root.findViewById<TextView>(R.id.txtColorSpace)
            val colorSpaceText = listOfNotNull(data.colorSpace, data.colorRange).joinToString(" · ")
            if (colorSpaceText.isNotEmpty()) {
                rowColorSpace.visibility = View.VISIBLE
                txtColorSpace.text = colorSpaceText
            } else {
                rowColorSpace.visibility = View.GONE
            }

            val rowBitDepth = root.findViewById<View>(R.id.rowBitDepth)
            val txtBitDepth = root.findViewById<TextView>(R.id.txtBitDepth)
            val depthText = listOfNotNull(data.bitDepth, data.pixelFormat).joinToString(" · ")
            if (depthText.isNotEmpty()) {
                rowBitDepth.visibility = View.VISIBLE
                txtBitDepth.text = depthText
            } else {
                rowBitDepth.visibility = View.GONE
            }
        } else {
            layoutVideoSection.visibility = View.GONE
        }

        // Audio Tracks
        val lblAudioSection = root.findViewById<TextView>(R.id.lblAudioSection)
        lblAudioSection.text = getString(R.string.video_info_section_audio) + if (data.audioTracks.isNotEmpty()) " (${data.audioTracks.size})" else ""

        val layoutAudioTracks = root.findViewById<LinearLayout>(R.id.layoutAudioTracksContainer)
        layoutAudioTracks.removeAllViews()

        val txtNoAudio = root.findViewById<TextView>(R.id.txtNoAudio)
        if (data.audioTracks.isEmpty()) {
            txtNoAudio.visibility = View.VISIBLE
        } else {
            txtNoAudio.visibility = View.GONE
            data.audioTracks.forEach { track ->
                layoutAudioTracks.addView(createAudioTrackCard(context, track, density, isTv))
            }
        }

        // Subtitle Tracks
        val lblSubtitlesSection = root.findViewById<TextView>(R.id.lblSubtitlesSection)
        lblSubtitlesSection.text = getString(R.string.video_info_section_subtitles) + if (data.subtitleTracks.isNotEmpty()) " (${data.subtitleTracks.size})" else ""

        val layoutSubtitleTracks = root.findViewById<LinearLayout>(R.id.layoutSubtitleTracksContainer)
        layoutSubtitleTracks.removeAllViews()

        val txtNoSubtitles = root.findViewById<TextView>(R.id.txtNoSubtitles)
        if (data.subtitleTracks.isEmpty()) {
            txtNoSubtitles.visibility = View.VISIBLE
        } else {
            txtNoSubtitles.visibility = View.GONE
            data.subtitleTracks.forEach { track ->
                layoutSubtitleTracks.addView(createSubtitleTrackCard(context, track, density, isTv))
            }
        }

        // Actions
        val btnCopy = root.findViewById<View>(R.id.btnCopySpecs)
        btnCopy.setOnClickListener {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = ClipData.newPlainText("Media Technical Info", data.toFormattedSummary())
            clipboard?.setPrimaryClip(clip)
            Toast.makeText(context, R.string.video_info_copied, Toast.LENGTH_SHORT).show()
        }

        val btnClose = root.findViewById<View>(R.id.btnClose)
        btnClose.setOnClickListener { dismiss() }

        if (isTv) {
            btnClose.requestFocus()
        }
    }

    private fun createAudioTrackCard(
        context: Context, track: AudioTrackTechnicalDetails, density: Float, isTv: Boolean
    ): View {
        val primaryTextColor = if (isTv) ContextCompat.getColor(context, R.color.tv_text_primary) else Color.WHITE
        val secondaryTextColor = if (isTv) ContextCompat.getColor(context, R.color.tv_text_secondary) else Color.parseColor("#CBD5E1")
        val cardBg = if (isTv) R.drawable.bg_tv_glass_card else R.drawable.bg_player_glass_card

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(cardBg)
            val p = (10 * density).toInt()
            setPadding(p, p, p, p)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (8 * density).toInt()
            }
            layoutParams = lp
        }

        // Top Row: Title + [ACTIVE] badge
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val titleView = TextView(context).apply {
            text = "${track.index}. ${track.title}"
            setTextColor(primaryTextColor)
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerRow.addView(titleView)

        if (track.isSelected) {
            val badge = TextView(context).apply {
                text = context.getString(R.string.video_info_active)
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                setBackgroundResource(R.drawable.bg_chip_primary)
                setPadding((6 * density).toInt(), (2 * density).toInt(), (6 * density).toInt(), (2 * density).toInt())
            }
            headerRow.addView(badge)
        }
        card.addView(headerRow)

        // Attribute Row
        val attributes = listOfNotNull(
            track.codec,
            track.channels,
            track.sampleRate,
            track.bitrate,
            track.language.takeIf { it != track.title }
        ).joinToString("  •  ")

        if (attributes.isNotEmpty()) {
            val attrView = TextView(context).apply {
                text = attributes
                setTextColor(secondaryTextColor)
                textSize = 12f
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (4 * density).toInt()
                }
                layoutParams = lp
            }
            card.addView(attrView)
        }

        return card
    }

    private fun createSubtitleTrackCard(
        context: Context, track: SubtitleTrackTechnicalDetails, density: Float, isTv: Boolean
    ): View {
        val primaryTextColor = if (isTv) ContextCompat.getColor(context, R.color.tv_text_primary) else Color.WHITE
        val secondaryTextColor = if (isTv) ContextCompat.getColor(context, R.color.tv_text_secondary) else Color.parseColor("#CBD5E1")
        val cardBg = if (isTv) R.drawable.bg_tv_glass_card else R.drawable.bg_player_glass_card

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(cardBg)
            val p = (10 * density).toInt()
            setPadding(p, p, p, p)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (8 * density).toInt()
            }
            layoutParams = lp
        }

        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val titleView = TextView(context).apply {
            text = "${track.index}. ${track.title}"
            setTextColor(primaryTextColor)
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerRow.addView(titleView)

        if (track.isSelected) {
            val badge = TextView(context).apply {
                text = context.getString(R.string.video_info_active)
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                setBackgroundResource(R.drawable.bg_chip_primary)
                setPadding((6 * density).toInt(), (2 * density).toInt(), (6 * density).toInt(), (2 * density).toInt())
            }
            headerRow.addView(badge)
        }
        card.addView(headerRow)

        val attributes = listOfNotNull(
            track.format,
            track.source,
            track.language.takeIf { it != track.title }
        ).joinToString("  •  ")

        if (attributes.isNotEmpty()) {
            val attrView = TextView(context).apply {
                text = attributes
                setTextColor(secondaryTextColor)
                textSize = 12f
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (4 * density).toInt()
                }
                layoutParams = lp
            }
            card.addView(attrView)
        }

        return card
    }
}
