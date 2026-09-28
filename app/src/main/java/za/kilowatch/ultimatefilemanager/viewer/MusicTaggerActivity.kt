package za.kilowatch.ultimatefilemanager.viewer

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.audio.AudioTagData
import za.kilowatch.ultimatefilemanager.audio.AudioTagManager
import za.kilowatch.ultimatefilemanager.audio.FilenameTagParser
import za.kilowatch.ultimatefilemanager.audio.covers.OnlineCoverPickerBottomSheet
import za.kilowatch.ultimatefilemanager.audio.musicbrainz.MusicBrainzSearchBottomSheet
import za.kilowatch.ultimatefilemanager.audio.musicbrainz.TagDiffItem
import za.kilowatch.ultimatefilemanager.audio.musicbrainz.TagFieldDiffBottomSheet
import za.kilowatch.ultimatefilemanager.audio.musicbrainz.model.MbRecordingItem
import za.kilowatch.ultimatefilemanager.audio.musicbrainz.model.MbReleaseDetails
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import za.kilowatch.ultimatefilemanager.settings.ThemeHelper
import za.kilowatch.ultimatefilemanager.storage.FileBrowserActivity
import za.kilowatch.ultimatefilemanager.storage.StorageBrowserActivity
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Mobile-only Activity for inspecting, editing, and batch-updating audio tags, lyrics, and album art.
 */
class MusicTaggerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FILE_PATHS = "extra_file_paths"
    }

    private val files = mutableListOf<File>()
    private val tagCache = mutableMapOf<String, AudioTagData>()
    private var selectedIndex = 0

    // Artwork state
    private var currentArtworkBytes: ByteArray? = null
    private var currentArtworkMime: String? = null
    private var isArtworkModified = false
    private var isArtworkRemoved = false

    // UI References
    private lateinit var txtTitle: TextView
    private lateinit var txtTrackCount: TextView
    private lateinit var btnSaveHeader: ImageView
    private lateinit var layoutTracksCarousel: LinearLayout
    private lateinit var recyclerTracks: RecyclerView
    private lateinit var thumbAdapter: MusicTaggerThumbAdapter

    // Cover Art
    private lateinit var imgCoverArt: ImageView
    private lateinit var btnSearchOnlineCover: MaterialButton
    private lateinit var btnChangeCover: MaterialButton
    private lateinit var btnRemoveCover: MaterialButton
    private lateinit var btnExtractCover: MaterialButton

    // Quick Tools
    private lateinit var btnSearchMusicBrainz: MaterialButton
    private lateinit var btnAutoFillFilename: MaterialButton
    private lateinit var btnRenameFromTags: MaterialButton

    // Batch Options
    private lateinit var cardBatchOptions: MaterialCardView
    private lateinit var switchAutoNumber: MaterialSwitch

    // Core Tags
    private lateinit var edtTitle: TextInputEditText
    private lateinit var edtArtist: TextInputEditText
    private lateinit var edtAlbum: TextInputEditText
    private lateinit var edtAlbumArtist: TextInputEditText
    private lateinit var edtYear: TextInputEditText
    private lateinit var edtGenre: TextInputEditText
    private lateinit var edtTrackNumber: TextInputEditText
    private lateinit var edtTrackTotal: TextInputEditText
    private lateinit var edtDiscNumber: TextInputEditText
    private lateinit var edtDiscTotal: TextInputEditText

    // Advanced & Lyrics
    private lateinit var headerAdvancedToggle: LinearLayout
    private lateinit var iconAdvancedExpand: ImageView
    private lateinit var layoutAdvancedContent: LinearLayout
    private lateinit var edtComposer: TextInputEditText
    private lateinit var edtComment: TextInputEditText
    private lateinit var edtLyrics: TextInputEditText
    private var isAdvancedExpanded = false

    // Technical Info
    private lateinit var cardTechnicalInfo: MaterialCardView
    private lateinit var txtTechFormat: TextView
    private lateinit var txtTechBitrate: TextView
    private lateinit var txtTechSampleRate: TextView
    private lateinit var txtTechDuration: TextView
    private lateinit var txtTechPath: TextView

    // Bottom Action
    private lateinit var btnApplyAction: MaterialButton

    // Storage Browser Image Picker Launcher
    private val storageImagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val selectedPath = result.data?.getStringExtra(FileBrowserActivity.RESULT_SELECTED_PATH)
                ?: result.data?.getStringExtra(FileBrowserActivity.RESULT_SELECTED_LOCAL_PATH)
            if (selectedPath != null) {
                handleSelectedCoverFile(File(selectedPath))
            } else {
                result.data?.data?.let { handleSelectedCoverImage(it) }
            }
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)

        // Mobile only check
        if (DeviceUtils.isTvDevice(this)) {
            finish()
            return
        }

        enableEdgeToEdge()
        setContentView(R.layout.activity_music_tagger)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val rawPaths = intent.getStringArrayListExtra(EXTRA_FILE_PATHS) ?: arrayListOf()
        for (path in rawPaths) {
            val f = File(path)
            if (f.exists() && f.isFile) {
                files.add(f)
            }
        }

        if (files.isEmpty()) {
            Toast.makeText(this, "No audio files selected", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        initViews()
        loadInitialData()
    }

    private fun initViews() {
        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }
        txtTitle = findViewById(R.id.txtTitle)
        txtTrackCount = findViewById(R.id.txtTrackCount)
        btnSaveHeader = findViewById(R.id.btnSaveHeader)

        layoutTracksCarousel = findViewById(R.id.layoutTracksCarousel)
        recyclerTracks = findViewById(R.id.recyclerTracks)

        imgCoverArt = findViewById(R.id.imgCoverArt)
        btnSearchOnlineCover = findViewById(R.id.btnSearchOnlineCover)
        btnChangeCover = findViewById(R.id.btnChangeCover)
        btnRemoveCover = findViewById(R.id.btnRemoveCover)
        btnExtractCover = findViewById(R.id.btnExtractCover)

        btnSearchMusicBrainz = findViewById(R.id.btnSearchMusicBrainz)
        btnAutoFillFilename = findViewById(R.id.btnAutoFillFilename)
        btnRenameFromTags = findViewById(R.id.btnRenameFromTags)

        cardBatchOptions = findViewById(R.id.cardBatchOptions)
        switchAutoNumber = findViewById(R.id.switchAutoNumber)

        edtTitle = findViewById(R.id.edtTitle)
        edtArtist = findViewById(R.id.edtArtist)
        edtAlbum = findViewById(R.id.edtAlbum)
        edtAlbumArtist = findViewById(R.id.edtAlbumArtist)
        edtYear = findViewById(R.id.edtYear)
        edtGenre = findViewById(R.id.edtGenre)
        edtTrackNumber = findViewById(R.id.edtTrackNumber)
        edtTrackTotal = findViewById(R.id.edtTrackTotal)
        edtDiscNumber = findViewById(R.id.edtDiscNumber)
        edtDiscTotal = findViewById(R.id.edtDiscTotal)

        headerAdvancedToggle = findViewById(R.id.headerAdvancedToggle)
        iconAdvancedExpand = findViewById(R.id.iconAdvancedExpand)
        layoutAdvancedContent = findViewById(R.id.layoutAdvancedContent)
        edtComposer = findViewById(R.id.edtComposer)
        edtComment = findViewById(R.id.edtComment)
        edtLyrics = findViewById(R.id.edtLyrics)

        cardTechnicalInfo = findViewById(R.id.cardTechnicalInfo)
        txtTechFormat = findViewById(R.id.txtTechFormat)
        txtTechBitrate = findViewById(R.id.txtTechBitrate)
        txtTechSampleRate = findViewById(R.id.txtTechSampleRate)
        txtTechDuration = findViewById(R.id.txtTechDuration)
        txtTechPath = findViewById(R.id.txtTechPath)

        btnApplyAction = findViewById(R.id.btnApplyAction)

        // Listeners
        btnSearchOnlineCover.setOnClickListener {
            openOnlineCoverPicker()
        }

        btnSearchMusicBrainz.setOnClickListener {
            openMusicBrainzSearch()
        }

        btnChangeCover.setOnClickListener {
            val intent = Intent(this, StorageBrowserActivity::class.java).apply {
                putExtra(FileBrowserActivity.EXTRA_PICKER_MODE, true)
                putExtra(FileBrowserActivity.EXTRA_PICKER_EXTENSIONS, "jpg,jpeg,png,webp")
            }
            storageImagePickerLauncher.launch(intent)
        }

        btnRemoveCover.setOnClickListener {
            currentArtworkBytes = null
            currentArtworkMime = null
            isArtworkModified = true
            isArtworkRemoved = true
            imgCoverArt.setImageResource(R.drawable.ic_file_audio)
            btnRemoveCover.visibility = View.GONE
            btnExtractCover.visibility = View.GONE
        }

        btnExtractCover.setOnClickListener {
            extractCurrentCover()
        }

        btnAutoFillFilename.setOnClickListener {
            autoFillFromFilename()
        }

        btnRenameFromTags.setOnClickListener {
            confirmRenameFromTags()
        }

        headerAdvancedToggle.setOnClickListener {
            isAdvancedExpanded = !isAdvancedExpanded
            layoutAdvancedContent.visibility = if (isAdvancedExpanded) View.VISIBLE else View.GONE
            iconAdvancedExpand.animate().rotation(if (isAdvancedExpanded) 180f else 0f).setDuration(200).start()
        }

        btnSaveHeader.setOnClickListener { saveTags() }
        btnApplyAction.setOnClickListener { saveTags() }
    }

    private fun loadInitialData() {
        val count = files.size
        val isBatch = count > 1

        if (isBatch) {
            txtTitle.text = getString(R.string.music_tagger_title)
            txtTrackCount.text = getString(R.string.music_tag_tracks_selected, count)
            layoutTracksCarousel.visibility = View.VISIBLE
            cardBatchOptions.visibility = View.VISIBLE
            btnApplyAction.text = getString(R.string.music_tag_btn_apply_batch, count)
            cardTechnicalInfo.visibility = View.GONE

            thumbAdapter = MusicTaggerThumbAdapter(files, tagCache) { index, file ->
                saveFormIntoCache(files[selectedIndex])
                selectedIndex = index
                loadTrackIntoForm(file)
            }
            recyclerTracks.adapter = thumbAdapter
        } else {
            val file = files.first()
            txtTitle.text = file.name
            txtTrackCount.text = getString(R.string.music_tag_single_track, file.extension.uppercase())
            layoutTracksCarousel.visibility = View.GONE
            cardBatchOptions.visibility = View.GONE
            btnApplyAction.text = getString(R.string.music_tag_btn_save)
            cardTechnicalInfo.visibility = View.VISIBLE
        }

        // Load all tags asynchronously
        lifecycleScope.launch(Dispatchers.IO) {
            for (f in files) {
                val tags = AudioTagManager.readTags(this@MusicTaggerActivity, f) ?: AudioTagData()
                tagCache[f.absolutePath] = tags
            }
            withContext(Dispatchers.Main) {
                if (isDestroyed || isFinishing) return@withContext
                if (isBatch) {
                    thumbAdapter.notifyDataSetChanged()
                }
                loadTrackIntoForm(files[selectedIndex])
            }
        }
    }

    private fun loadTrackIntoForm(file: File) {
        val tags = tagCache[file.absolutePath] ?: AudioTagData()

        edtTitle.setText(tags.title)
        edtArtist.setText(tags.artist)
        edtAlbum.setText(tags.album)
        edtAlbumArtist.setText(tags.albumArtist)
        edtYear.setText(tags.year)
        edtGenre.setText(tags.genre)
        edtTrackNumber.setText(tags.trackNumber)
        edtTrackTotal.setText(tags.trackTotal)
        edtDiscNumber.setText(tags.discNumber)
        edtDiscTotal.setText(tags.discTotal)

        edtComposer.setText(tags.composer)
        edtComment.setText(tags.comment)
        edtLyrics.setText(tags.lyrics)

        // Artwork
        currentArtworkBytes = tags.artworkBytes
        currentArtworkMime = tags.artworkMime
        isArtworkModified = false
        isArtworkRemoved = false

        if (currentArtworkBytes != null && currentArtworkBytes!!.isNotEmpty()) {
            val bmp = BitmapFactory.decodeByteArray(currentArtworkBytes, 0, currentArtworkBytes!!.size)
            if (bmp != null) {
                imgCoverArt.setImageBitmap(bmp)
                btnRemoveCover.visibility = View.VISIBLE
                btnExtractCover.visibility = View.VISIBLE
            } else {
                imgCoverArt.setImageResource(R.drawable.ic_file_audio)
                btnRemoveCover.visibility = View.GONE
                btnExtractCover.visibility = View.GONE
            }
        } else {
            imgCoverArt.setImageResource(R.drawable.ic_file_audio)
            btnRemoveCover.visibility = View.GONE
            btnExtractCover.visibility = View.GONE
        }

        // Technical details
        if (files.size == 1) {
            txtTechFormat.text = tags.format.ifBlank { file.extension.uppercase() }
            txtTechBitrate.text = tags.bitrate.ifBlank { "--" }
            txtTechSampleRate.text = if (tags.channels.isNotBlank()) "${tags.sampleRate} · ${tags.channels}" else tags.sampleRate.ifBlank { "--" }
            txtTechDuration.text = tags.formattedDuration()
            txtTechPath.text = file.absolutePath
        }
    }

    private fun saveFormIntoCache(file: File) {
        val existing = tagCache[file.absolutePath] ?: AudioTagData()
        tagCache[file.absolutePath] = existing.copy(
            title = edtTitle.text?.toString()?.trim() ?: "",
            artist = edtArtist.text?.toString()?.trim() ?: "",
            album = edtAlbum.text?.toString()?.trim() ?: "",
            albumArtist = edtAlbumArtist.text?.toString()?.trim() ?: "",
            year = edtYear.text?.toString()?.trim() ?: "",
            genre = edtGenre.text?.toString()?.trim() ?: "",
            trackNumber = edtTrackNumber.text?.toString()?.trim() ?: "",
            trackTotal = edtTrackTotal.text?.toString()?.trim() ?: "",
            discNumber = edtDiscNumber.text?.toString()?.trim() ?: "",
            discTotal = edtDiscTotal.text?.toString()?.trim() ?: "",
            composer = edtComposer.text?.toString()?.trim() ?: "",
            comment = edtComment.text?.toString()?.trim() ?: "",
            lyrics = edtLyrics.text?.toString()?.trim() ?: "",
            artworkBytes = if (isArtworkRemoved) null else if (isArtworkModified) currentArtworkBytes else existing.artworkBytes,
            artworkMime = if (isArtworkRemoved) null else if (isArtworkModified) currentArtworkMime else existing.artworkMime
        )
    }

    private fun handleSelectedCoverFile(file: File) {
        try {
            val isSaf = za.kilowatch.ultimatefilemanager.storage.SafTreeManager.isSafPath(file.absolutePath) ||
                        za.kilowatch.ultimatefilemanager.storage.SafTreeManager.hasTreePermissionForPath(this, file.absolutePath)
            val inStream = (if (isSaf) {
                za.kilowatch.ultimatefilemanager.storage.SafTreeManager.openInputStream(this, file.absolutePath)
            } else if (file.exists() && file.isFile) {
                file.inputStream()
            } else null) ?: return

            val rawBytes = inStream.use { it.readBytes() }
            val bmp = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size)
            if (bmp != null) {
                // Compress to max 1200x1200 JPEG to avoid giant audio files
                val scaled = if (bmp.width > 1200 || bmp.height > 1200) {
                    val ratio = bmp.width.toFloat() / bmp.height.toFloat()
                    val targetW = if (ratio >= 1f) 1200 else (1200 * ratio).toInt()
                    val targetH = if (ratio <= 1f) 1200 else (1200 / ratio).toInt()
                    Bitmap.createScaledBitmap(bmp, targetW, targetH, true)
                } else bmp

                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 90, out)
                currentArtworkBytes = out.toByteArray()
                currentArtworkMime = "image/jpeg"
                isArtworkModified = true
                isArtworkRemoved = false

                imgCoverArt.setImageBitmap(scaled)
                btnRemoveCover.visibility = View.VISIBLE
                btnExtractCover.visibility = View.VISIBLE
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to load image: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleSelectedCoverImage(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { stream ->
                val rawBytes = stream.readBytes()
                val bmp = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size)
                if (bmp != null) {
                    // Compress to max 1200x1200 JPEG to avoid giant audio files
                    val scaled = if (bmp.width > 1200 || bmp.height > 1200) {
                        val ratio = bmp.width.toFloat() / bmp.height.toFloat()
                        val targetW = if (ratio >= 1f) 1200 else (1200 * ratio).toInt()
                        val targetH = if (ratio <= 1f) 1200 else (1200 / ratio).toInt()
                        Bitmap.createScaledBitmap(bmp, targetW, targetH, true)
                    } else bmp

                    val out = ByteArrayOutputStream()
                    scaled.compress(Bitmap.CompressFormat.JPEG, 90, out)
                    currentArtworkBytes = out.toByteArray()
                    currentArtworkMime = "image/jpeg"
                    isArtworkModified = true
                    isArtworkRemoved = false

                    imgCoverArt.setImageBitmap(scaled)
                    btnRemoveCover.visibility = View.VISIBLE
                    btnExtractCover.visibility = View.VISIBLE
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to load image: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun extractCurrentCover() {
        val bytes = currentArtworkBytes
        if (bytes == null || bytes.isEmpty()) {
            Toast.makeText(this, getString(R.string.music_tag_art_extract_failed), Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val currentFile = files[selectedIndex]
            val parent = currentFile.parentFile ?: cacheDir
            var targetName = "cover.jpg"
            var counter = 1
            while (File(parent, targetName).exists()) {
                targetName = "cover_$counter.jpg"
                counter++
            }
            val target = File(parent, targetName)

            try {
                val isSaf = za.kilowatch.ultimatefilemanager.storage.SafTreeManager.isSafPath(parent.absolutePath) ||
                            za.kilowatch.ultimatefilemanager.storage.SafTreeManager.hasTreePermissionForPath(this@MusicTaggerActivity, parent.absolutePath)
                if (isSaf) {
                    za.kilowatch.ultimatefilemanager.storage.SafTreeManager.createFile(this@MusicTaggerActivity, parent.absolutePath, targetName, "image/jpeg")
                    za.kilowatch.ultimatefilemanager.storage.SafTreeManager.openOutputStream(this@MusicTaggerActivity, target.absolutePath)?.use {
                        it.write(bytes)
                    }
                } else {
                    target.outputStream().use { it.write(bytes) }
                }
                android.media.MediaScannerConnection.scanFile(this@MusicTaggerActivity, arrayOf(target.absolutePath), null, null)
                withContext(Dispatchers.Main) {
                    val msg = getString(R.string.music_tag_art_extracted, target.name)
                    Snackbar.make(findViewById(R.id.main), msg, Snackbar.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MusicTaggerActivity, getString(R.string.music_tag_art_extract_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun autoFillFromFilename() {
        val file = files[selectedIndex]
        val parsed = FilenameTagParser.parseFilename(file.name)

        var changed = false
        if (!parsed.title.isNullOrBlank()) {
            edtTitle.setText(parsed.title)
            changed = true
        }
        if (!parsed.artist.isNullOrBlank()) {
            edtArtist.setText(parsed.artist)
            changed = true
        }
        if (!parsed.trackNumber.isNullOrBlank()) {
            edtTrackNumber.setText(parsed.trackNumber)
            changed = true
        }

        val msg = if (changed) getString(R.string.music_tag_autofill_detected) else getString(R.string.music_tag_autofill_no_match)
        Snackbar.make(findViewById(R.id.main), msg, Snackbar.LENGTH_SHORT).show()
    }

    private fun confirmRenameFromTags() {
        val currentFile = files[selectedIndex]
        val currentData = AudioTagData(
            title = edtTitle.text?.toString()?.trim() ?: "",
            artist = edtArtist.text?.toString()?.trim() ?: "",
            album = edtAlbum.text?.toString()?.trim() ?: "",
            trackNumber = edtTrackNumber.text?.toString()?.trim() ?: "",
            year = edtYear.text?.toString()?.trim() ?: ""
        )

        val suggestedName = FilenameTagParser.formatFilename(
            currentData,
            FilenameTagParser.PresetPattern.TRACK_ARTIST_TITLE.pattern,
            currentFile.extension
        )

        za.kilowatch.ultimatefilemanager.ui.UfmDialogHelper.showConfirmation(
            context = this,
            title = getString(R.string.music_tag_rename_confirm_title),
            message = getString(R.string.music_tag_rename_confirm_msg, suggestedName),
            iconRes = R.drawable.ic_music_tag,
            positiveText = getString(R.string.action_rename),
            onPositive = {
                lifecycleScope.launch(Dispatchers.IO) {
                    val renamed = AudioTagManager.renameFileFromTags(
                        this@MusicTaggerActivity,
                        currentFile,
                        FilenameTagParser.PresetPattern.TRACK_ARTIST_TITLE.pattern,
                        currentData
                    )
                    withContext(Dispatchers.Main) {
                        if (renamed != null) {
                            val oldFile = currentFile
                            runCatching { za.kilowatch.ultimatefilemanager.viewer.NetworkSaveBridge.onFileRenamed?.invoke(oldFile, renamed) }
                            files[selectedIndex] = renamed
                            txtTitle.text = renamed.name
                            txtTechPath.text = renamed.absolutePath
                            if (files.size > 1) {
                                thumbAdapter.notifyItemChanged(selectedIndex)
                            }
                            Snackbar.make(findViewById(R.id.main), "Renamed to ${renamed.name}", Snackbar.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this@MusicTaggerActivity, "Failed to rename file", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        )
    }

    private fun openOnlineCoverPicker() {
        val album = edtAlbum.text?.toString()?.trim() ?: ""
        val artist = edtArtist.text?.toString()?.trim() ?: ""
        val sheet = OnlineCoverPickerBottomSheet.newInstance(album = album, artist = artist)
        sheet.setOnCoverSelectedListener { bytes, mimeType, _ ->
            currentArtworkBytes = bytes
            currentArtworkMime = mimeType
            isArtworkModified = true
            isArtworkRemoved = false
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bmp != null) {
                imgCoverArt.setImageBitmap(bmp)
                btnRemoveCover.visibility = View.VISIBLE
                btnExtractCover.visibility = View.VISIBLE
            }
            Snackbar.make(findViewById(R.id.main), R.string.music_tag_cover_applied, Snackbar.LENGTH_SHORT).show()
        }
        sheet.show(supportFragmentManager, OnlineCoverPickerBottomSheet.TAG)
    }

    private fun openMusicBrainzSearch() {
        val isBatch = files.size > 1
        val title = if (isBatch) "" else (edtTitle.text?.toString()?.trim() ?: "")
        val artist = edtArtist.text?.toString()?.trim() ?: ""
        val album = edtAlbum.text?.toString()?.trim() ?: ""

        val sheet = MusicBrainzSearchBottomSheet.newInstance(
            title = title,
            artist = artist,
            album = album,
            isBatch = isBatch
        )

        sheet.setOnRecordingResolved { recording, releaseDetails, artwork ->
            showSingleTrackDiff(recording, releaseDetails, artwork)
        }

        sheet.setOnReleaseDetailsResolved { details, artwork ->
            if (isBatch) {
                applyBatchRelease(details, artwork)
            } else {
                showReleaseDiff(details, artwork)
            }
        }

        sheet.show(supportFragmentManager, MusicBrainzSearchBottomSheet.TAG)
    }

    private fun showSingleTrackDiff(
        recording: MbRecordingItem,
        releaseDetails: MbReleaseDetails?,
        artwork: Pair<ByteArray, String>?
    ) {
        val rel = recording.releases.firstOrNull()
        val mediaSummary = rel?.media?.firstOrNull()

        // Match track from releaseDetails if available
        val matchedTrack = releaseDetails?.media?.flatMap { it.tracks }?.firstOrNull {
            it.recording?.id == recording.id || (it.title.isNotBlank() && it.title.equals(recording.title, ignoreCase = true))
        } ?: releaseDetails?.media?.firstOrNull()?.tracks?.firstOrNull()

        val matchedMedia = releaseDetails?.media?.firstOrNull { m ->
            m.tracks.any { it.id == matchedTrack?.id }
        }

        val newTitle = recording.title
        val newArtist = recording.displayArtist()
        val newAlbum = releaseDetails?.title ?: rel?.title ?: ""
        val newAlbumArtist = releaseDetails?.displayArtist() ?: rel?.displayArtist() ?: newArtist
        val newYear = releaseDetails?.releaseYear() ?: rel?.releaseYear() ?: ""
        val newGenre = releaseDetails?.primaryGenre() ?: ""
        val newTrackNumber = matchedTrack?.number?.ifBlank { matchedTrack.position.takeIf { it > 0 }?.toString() } ?: ""
        val newTrackTotal = matchedMedia?.trackCount?.takeIf { it > 0 }?.toString() ?: if ((mediaSummary?.trackCount ?: 0) > 0) mediaSummary!!.trackCount.toString() else (rel?.trackCount?.takeIf { it > 0 }?.toString() ?: "")
        val newDiscNumber = matchedMedia?.position?.takeIf { it > 0 }?.toString() ?: if ((mediaSummary?.position ?: 0) > 0) mediaSummary!!.position.toString() else "1"
        val newDiscTotal = if ((releaseDetails?.media?.size ?: 0) > 0) releaseDetails!!.media.size.toString() else "1"

        val diffItems = mutableListOf<TagDiffItem>()
        if (newTitle.isNotBlank()) diffItems.add(TagDiffItem("title", getString(R.string.music_tag_hint_title), edtTitle.text?.toString() ?: "", newTitle))
        if (newArtist.isNotBlank()) diffItems.add(TagDiffItem("artist", getString(R.string.music_tag_hint_artist), edtArtist.text?.toString() ?: "", newArtist))
        if (newAlbum.isNotBlank()) diffItems.add(TagDiffItem("album", getString(R.string.music_tag_hint_album), edtAlbum.text?.toString() ?: "", newAlbum))
        if (newAlbumArtist.isNotBlank()) diffItems.add(TagDiffItem("albumArtist", getString(R.string.music_tag_hint_album_artist), edtAlbumArtist.text?.toString() ?: "", newAlbumArtist))
        if (newYear.isNotBlank()) diffItems.add(TagDiffItem("year", getString(R.string.music_tag_hint_year), edtYear.text?.toString() ?: "", newYear))
        if (newGenre.isNotBlank()) diffItems.add(TagDiffItem("genre", getString(R.string.music_tag_hint_genre), edtGenre.text?.toString() ?: "", newGenre))
        if (newTrackNumber.isNotBlank()) diffItems.add(TagDiffItem("trackNumber", getString(R.string.music_tag_hint_track), edtTrackNumber.text?.toString() ?: "", newTrackNumber))
        if (newTrackTotal.isNotBlank()) diffItems.add(TagDiffItem("trackTotal", getString(R.string.music_tag_hint_total_tracks), edtTrackTotal.text?.toString() ?: "", newTrackTotal))
        if (newDiscNumber.isNotBlank()) diffItems.add(TagDiffItem("discNumber", getString(R.string.music_tag_hint_disc), edtDiscNumber.text?.toString() ?: "", newDiscNumber))
        if (newDiscTotal.isNotBlank()) diffItems.add(TagDiffItem("discTotal", getString(R.string.music_tag_hint_total_discs), edtDiscTotal.text?.toString() ?: "", newDiscTotal))

        if (artwork != null && artwork.first.isNotEmpty()) {
            diffItems.add(TagDiffItem("artwork", getString(R.string.music_tag_cover_art), if (currentArtworkBytes != null) "Current artwork" else "None", "Online cover", artworkBytes = artwork.first))
        }

        val diffSheet = TagFieldDiffBottomSheet.newInstance()
        diffSheet.setDiffData(diffItems) { selected ->
            for (item in selected) {
                when (item.key) {
                    "title" -> edtTitle.setText(item.newValue)
                    "artist" -> edtArtist.setText(item.newValue)
                    "album" -> edtAlbum.setText(item.newValue)
                    "albumArtist" -> edtAlbumArtist.setText(item.newValue)
                    "year" -> edtYear.setText(item.newValue)
                    "genre" -> edtGenre.setText(item.newValue)
                    "trackNumber" -> edtTrackNumber.setText(item.newValue)
                    "trackTotal" -> edtTrackTotal.setText(item.newValue)
                    "discNumber" -> edtDiscNumber.setText(item.newValue)
                    "discTotal" -> edtDiscTotal.setText(item.newValue)
                    "artwork" -> {
                        currentArtworkBytes = artwork!!.first
                        currentArtworkMime = artwork.second
                        isArtworkModified = true
                        isArtworkRemoved = false
                        val bmp = BitmapFactory.decodeByteArray(currentArtworkBytes, 0, currentArtworkBytes!!.size)
                        if (bmp != null) {
                            imgCoverArt.setImageBitmap(bmp)
                            btnRemoveCover.visibility = View.VISIBLE
                            btnExtractCover.visibility = View.VISIBLE
                        }
                    }
                }
            }
            Snackbar.make(findViewById(R.id.main), R.string.music_tag_autofill_detected, Snackbar.LENGTH_SHORT).show()
        }
        diffSheet.show(supportFragmentManager, TagFieldDiffBottomSheet.TAG)
    }

    private fun showReleaseDiff(releaseDetails: MbReleaseDetails, artwork: Pair<ByteArray, String>?) {
        val newAlbum = releaseDetails.title
        val newArtist = releaseDetails.displayArtist()
        val newAlbumArtist = releaseDetails.displayArtist()
        val newYear = releaseDetails.releaseYear()
        val newGenre = releaseDetails.primaryGenre()
        val totalDiscs = if (releaseDetails.media.isNotEmpty()) releaseDetails.media.size.toString() else "1"
        val totalTracks = releaseDetails.media.firstOrNull()?.trackCount?.toString() ?: ""

        val diffItems = mutableListOf<TagDiffItem>()
        if (newArtist.isNotBlank()) diffItems.add(TagDiffItem("artist", getString(R.string.music_tag_hint_artist), edtArtist.text?.toString() ?: "", newArtist))
        if (newAlbum.isNotBlank()) diffItems.add(TagDiffItem("album", getString(R.string.music_tag_hint_album), edtAlbum.text?.toString() ?: "", newAlbum))
        if (newAlbumArtist.isNotBlank()) diffItems.add(TagDiffItem("albumArtist", getString(R.string.music_tag_hint_album_artist), edtAlbumArtist.text?.toString() ?: "", newAlbumArtist))
        if (newYear.isNotBlank()) diffItems.add(TagDiffItem("year", getString(R.string.music_tag_hint_year), edtYear.text?.toString() ?: "", newYear))
        if (newGenre.isNotBlank()) diffItems.add(TagDiffItem("genre", getString(R.string.music_tag_hint_genre), edtGenre.text?.toString() ?: "", newGenre))
        if (totalTracks.isNotBlank()) diffItems.add(TagDiffItem("trackTotal", getString(R.string.music_tag_hint_total_tracks), edtTrackTotal.text?.toString() ?: "", totalTracks))
        if (totalDiscs.isNotBlank()) diffItems.add(TagDiffItem("discTotal", getString(R.string.music_tag_hint_total_discs), edtDiscTotal.text?.toString() ?: "", totalDiscs))

        if (artwork != null && artwork.first.isNotEmpty()) {
            diffItems.add(TagDiffItem("artwork", getString(R.string.music_tag_cover_art), if (currentArtworkBytes != null) "Current artwork" else "None", "Online cover", artworkBytes = artwork.first))
        }

        val diffSheet = TagFieldDiffBottomSheet.newInstance()
        diffSheet.setDiffData(diffItems) { selected ->
            for (item in selected) {
                when (item.key) {
                    "artist" -> edtArtist.setText(item.newValue)
                    "album" -> edtAlbum.setText(item.newValue)
                    "albumArtist" -> edtAlbumArtist.setText(item.newValue)
                    "year" -> edtYear.setText(item.newValue)
                    "genre" -> edtGenre.setText(item.newValue)
                    "trackTotal" -> edtTrackTotal.setText(item.newValue)
                    "discTotal" -> edtDiscTotal.setText(item.newValue)
                    "artwork" -> {
                        currentArtworkBytes = artwork!!.first
                        currentArtworkMime = artwork.second
                        isArtworkModified = true
                        isArtworkRemoved = false
                        val bmp = BitmapFactory.decodeByteArray(currentArtworkBytes, 0, currentArtworkBytes!!.size)
                        if (bmp != null) {
                            imgCoverArt.setImageBitmap(bmp)
                            btnRemoveCover.visibility = View.VISIBLE
                            btnExtractCover.visibility = View.VISIBLE
                        }
                    }
                }
            }
            Snackbar.make(findViewById(R.id.main), R.string.music_tag_autofill_detected, Snackbar.LENGTH_SHORT).show()
        }
        diffSheet.show(supportFragmentManager, TagFieldDiffBottomSheet.TAG)
    }

    private fun applyBatchRelease(releaseDetails: MbReleaseDetails, artwork: Pair<ByteArray, String>?) {
        val albumTitle = releaseDetails.title
        val artistName = releaseDetails.displayArtist()
        val year = releaseDetails.releaseYear()
        val genre = releaseDetails.primaryGenre()
        val totalDiscs = if (releaseDetails.media.isNotEmpty()) releaseDetails.media.size.toString() else "1"

        // Flatten all tracks from all media discs
        val allMbTracks = releaseDetails.media.flatMap { media ->
            media.tracks.map { track ->
                Triple(media.position.toString(), track.number.ifBlank { track.position.toString() }, track.title)
            }
        }

        if (artwork != null && artwork.first.isNotEmpty()) {
            currentArtworkBytes = artwork.first
            currentArtworkMime = artwork.second
            isArtworkModified = true
            isArtworkRemoved = false
            val bmp = BitmapFactory.decodeByteArray(currentArtworkBytes, 0, currentArtworkBytes!!.size)
            if (bmp != null) {
                imgCoverArt.setImageBitmap(bmp)
                btnRemoveCover.visibility = View.VISIBLE
                btnExtractCover.visibility = View.VISIBLE
            }
        }

        var matchedCount = 0
        for ((index, file) in files.withIndex()) {
            val cached = tagCache[file.absolutePath] ?: AudioTagData()
            if (albumTitle.isNotBlank()) cached.album = albumTitle
            if (artistName.isNotBlank()) {
                if (cached.artist.isBlank()) cached.artist = artistName
                cached.albumArtist = artistName
            }
            if (year.isNotBlank()) cached.year = year
            if (genre.isNotBlank()) cached.genre = genre
            cached.discTotal = totalDiscs
            if (artwork != null) {
                cached.artworkBytes = artwork.first
                cached.artworkMime = artwork.second
            }

            val mbTrack = if (index < allMbTracks.size) allMbTracks[index] else null
            if (mbTrack != null) {
                cached.discNumber = mbTrack.first
                cached.trackNumber = mbTrack.second
                cached.trackTotal = releaseDetails.media.firstOrNull()?.trackCount?.toString() ?: allMbTracks.size.toString()
                cached.title = mbTrack.third
                matchedCount++
            }

            tagCache[file.absolutePath] = cached
        }

        loadTrackIntoForm(files[selectedIndex])
        thumbAdapter.notifyDataSetChanged()

        Snackbar.make(
            findViewById(R.id.main),
            "Matched $matchedCount tracks to \"$albumTitle\"",
            Snackbar.LENGTH_LONG
        ).show()
    }

    private fun saveTags() {
        saveFormIntoCache(files[selectedIndex])

        val progressDialog = za.kilowatch.ultimatefilemanager.media.MediaOperationProgressDialog(
            this,
            getString(R.string.saving),
            if (files.size > 1) "Updating tracks..." else "Saving tags...",
            R.drawable.ic_music_tag
        )
        progressDialog.show()

        lifecycleScope.launch(Dispatchers.IO) {
            if (files.size == 1) {
                val file = files.first()
                val data = tagCache[file.absolutePath] ?: AudioTagData()
                val success = AudioTagManager.writeTags(
                    context = this@MusicTaggerActivity,
                    targetFile = file,
                    data = data,
                    updateArtwork = isArtworkModified,
                    removeArtwork = isArtworkRemoved
                )

                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    if (success) {
                        za.kilowatch.ultimatefilemanager.audio.AudioCoverHelper.clearCacheForPath(file.absolutePath)
                        runCatching { za.kilowatch.ultimatefilemanager.viewer.NetworkSaveBridge.onFileSaved?.invoke(file) }
                        Toast.makeText(this@MusicTaggerActivity, getString(R.string.music_tag_save_success), Toast.LENGTH_SHORT).show()
                        finish()
                    } else {
                        Toast.makeText(this@MusicTaggerActivity, getString(R.string.music_tag_save_error, "Permission or format error"), Toast.LENGTH_LONG).show()
                    }
                }
            } else {
                // Batch Mode
                var successCount = 0
                val errors = mutableListOf<String>()
                val autoNumber = switchAutoNumber.isChecked

                for ((index, file) in files.withIndex()) {
                    val fileData = tagCache[file.absolutePath] ?: AudioTagData()
                    if (autoNumber) {
                        fileData.trackNumber = (index + 1).toString()
                        fileData.trackTotal = files.size.toString()
                    }
                    if (isArtworkModified && currentArtworkBytes != null) {
                        fileData.artworkBytes = currentArtworkBytes
                        fileData.artworkMime = currentArtworkMime
                    }

                    val ok = AudioTagManager.writeTags(
                        context = this@MusicTaggerActivity,
                        targetFile = file,
                        data = fileData,
                        updateArtwork = isArtworkModified,
                        removeArtwork = isArtworkRemoved
                    )
                    if (ok) {
                        successCount++
                    } else {
                        errors.add("${file.name}: Write failed")
                    }
                    withContext(Dispatchers.Main) {
                        progressDialog.setMessage("Updating track ${index + 1} of ${files.size}...")
                    }
                }

                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    if (successCount > 0) {
                        for (f in files) {
                            za.kilowatch.ultimatefilemanager.audio.AudioCoverHelper.clearCacheForPath(f.absolutePath)
                            runCatching { za.kilowatch.ultimatefilemanager.viewer.NetworkSaveBridge.onFileSaved?.invoke(f) }
                        }
                        Toast.makeText(this@MusicTaggerActivity, getString(R.string.music_tag_batch_success, successCount), Toast.LENGTH_SHORT).show()
                        finish()
                    } else {
                        val errMsg = errors.firstOrNull() ?: "Unknown error"
                        Toast.makeText(this@MusicTaggerActivity, getString(R.string.music_tag_save_error, errMsg), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }
}
