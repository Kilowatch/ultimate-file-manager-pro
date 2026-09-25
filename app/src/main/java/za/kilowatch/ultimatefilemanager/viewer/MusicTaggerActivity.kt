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
    private lateinit var btnChangeCover: MaterialButton
    private lateinit var btnRemoveCover: MaterialButton
    private lateinit var btnExtractCover: MaterialButton

    // Quick Tools
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
        btnChangeCover = findViewById(R.id.btnChangeCover)
        btnRemoveCover = findViewById(R.id.btnRemoveCover)
        btnExtractCover = findViewById(R.id.btnExtractCover)

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
                val autoNumber = switchAutoNumber.isChecked
                val commonData = tagCache[files[selectedIndex].absolutePath] ?: AudioTagData()

                val (successCount, errors) = AudioTagManager.batchWriteCommonTags(
                    context = this@MusicTaggerActivity,
                    files = files,
                    commonData = commonData,
                    applyAlbum = commonData.album.isNotBlank(),
                    applyArtist = commonData.artist.isNotBlank(),
                    applyAlbumArtist = commonData.albumArtist.isNotBlank(),
                    applyYear = commonData.year.isNotBlank(),
                    applyGenre = commonData.genre.isNotBlank(),
                    autoNumber = autoNumber,
                    updateArtwork = isArtworkModified,
                    removeArtwork = isArtworkRemoved
                ) { current, total ->
                    lifecycleScope.launch(Dispatchers.Main) {
                        progressDialog.setMessage("Updating track $current of $total...")
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
