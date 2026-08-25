package za.kilowatch.ultimatefilemanager.notepad

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import za.kilowatch.ultimatefilemanager.settings.ThemeHelper
import za.kilowatch.ultimatefilemanager.storage.FileBrowserActivity
import za.kilowatch.ultimatefilemanager.storage.StorageBrowserActivity
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import za.kilowatch.ultimatefilemanager.util.EditorInsets
import za.kilowatch.ultimatefilemanager.util.PdfConverter
import java.io.File

class NotepadActivity : AppCompatActivity() {

    private lateinit var editText: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var txtTitle: TextView
    private lateinit var txtWordCount: TextView
    private lateinit var txtCharCount: TextView
    private lateinit var btnSave: ImageView
    private lateinit var btnNewDoc: ImageView
    private lateinit var btnClear: View

    private var textSize = 14f
    private lateinit var scaleDetector: ScaleGestureDetector
    private var isModified = false
    private var currentFile: File? = null
    private var notesDir: File? = null
    private var autoSaveJob: Job? = null
    private var fileName = "Notepad.txt"
    private var isTv = false
    private var imeWasVisible = false
    private var selectedFolderPath: String? = null
    private var pendingFormat: String? = null
    private var pendingFileName: String? = null

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val path = result.data?.getStringExtra(FileBrowserActivity.RESULT_SELECTED_LOCAL_PATH)
            if (path != null) {
                selectedFolderPath = path
                showSaveAsDialog(pendingFormat ?: "txt", pendingFileName ?: fileName)
            }
        }
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        isTv = DeviceUtils.isTvDevice(this)
        setContentView(
            if (isTv) R.layout.activity_notepad_tv
            else R.layout.activity_notepad
        )

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val imeVisible = EditorInsets.apply(
                v, insets,
                resources.getDimensionPixelSize(R.dimen.editor_keyboard_gap)
            )
            // When the soft keyboard first appears, scroll the cursor into view
            // so the active line never sits behind the keyboard.
            if (imeVisible && !imeWasVisible) {
                scrollToCursor()
            }
            imeWasVisible = imeVisible
            insets
        }

        editText = findViewById(R.id.editText)
        progressBar = findViewById(R.id.progressBar)
        txtTitle = findViewById(R.id.txtTitle)
        txtWordCount = findViewById(R.id.txtWordCount)
        txtCharCount = findViewById(R.id.txtCharCount)
        btnSave = findViewById(R.id.btnSave)
        btnNewDoc = findViewById(R.id.btnNewDoc)
        btnClear = findViewById(R.id.btnClear)

        findViewById<View>(R.id.btnBack).setOnClickListener { onBackPressed() }
        btnSave.setOnClickListener { onSavePressed() }
        btnNewDoc.setOnClickListener { onNewDocumentPressed() }
        btnClear.setOnClickListener { showClearConfirmDialog() }

        val border = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setStroke(1, za.kilowatch.ultimatefilemanager.util.ThemeColors.withAlpha(za.kilowatch.ultimatefilemanager.util.ThemeColors.primary(this@NotepadActivity), 0x33))
            setColor(0x0DFFFFFF.toInt())
            cornerRadius = 4f
        }
        editText.background = border

        notesDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            "UFM/Notes"
        )
        selectedFolderPath = notesDir?.absolutePath

        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                textSize = (textSize * detector.scaleFactor).coerceIn(10f, 36f)
                editText.textSize = textSize
                return true
            }
        })

        editText.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            if (scaleDetector.isInProgress) {
                editText.isEnabled = false
                editText.postDelayed({ editText.isEnabled = true }, 100)
                true
            } else false
        }

        editText.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                isModified = true
                updateWordCount()
                scheduleAutoSave()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        loadOrCreateNote()

        if (isTv) {
            editText.isFocusable = true
            editText.requestFocus()
        }
    }

    /**
     * Brings the cursor into view when the soft keyboard appears, so the line
     * being edited stays visible above the keyboard.
     */
    private fun scrollToCursor() {
        if (!::editText.isInitialized) return
        editText.post {
            editText.bringPointIntoView(editText.selectionStart.coerceAtLeast(0))
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onBackPressed() {
        if (isModified) {
            if (isTv) {
                AlertDialog.Builder(this)
                    .setTitle(R.string.unsaved_changes_title)
                    .setMessage(R.string.unsaved_changes_message)
                    .setPositiveButton(R.string.save) { _, _ -> onSavePressed() }
                    .setNegativeButton(R.string.btn_discard) { _, _ -> finish() }
                    .setNeutralButton(android.R.string.cancel, null)
                    .show()
            } else {
                val dialogView = layoutInflater.inflate(R.layout.dialog_notepad_unsaved, null)
                val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
                    .setView(dialogView)
                    .setCancelable(true)
                    .create()

                dialogView.findViewById<View>(R.id.btnSaveUnsaved).setOnClickListener {
                    dialog.dismiss()
                    onSavePressed()
                }
                dialogView.findViewById<View>(R.id.btnDiscardUnsaved).setOnClickListener {
                    dialog.dismiss()
                    finish()
                }
                dialogView.findViewById<View>(R.id.btnCancelUnsaved).setOnClickListener {
                    dialog.dismiss()
                }

                dialog.show()
                dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            }
        } else {
            super.onBackPressed()
        }
    }

    override fun onPause() {
        super.onPause()
        // Reset the IME-open transition flag so a keyboard shown after returning
        // to the foreground still triggers the cursor scroll.
        imeWasVisible = false
        if (isModified && currentFile != null) {
            autoSave()
        }
    }

    private fun loadOrCreateNote() {
        lifecycleScope.launch(Dispatchers.IO) {
            var attempt = 0
            val maxAttempts = 3
            var lastException: Exception? = null
            var success = false

            while (attempt < maxAttempts) {
                try {
                    notesDir?.let {
                        if (!it.exists()) {
                            it.mkdirs()
                        }
                    }
                    val file = File(notesDir, "Notepad.txt")
                    if (!file.exists()) {
                        file.writeText("", Charsets.UTF_8)
                    }
                    currentFile = file
                    fileName = "Notepad.txt"
                    val content = file.readText(Charsets.UTF_8)
                    withContext(Dispatchers.Main) {
                        editText.setText(content)
                        editText.setSelection(editText.text.length)
                        editText.requestFocus()
                        txtTitle.text = fileName
                        isModified = false
                        updateWordCount()
                        progressBar.visibility = View.GONE
                    }
                    success = true
                    break
                } catch (e: Exception) {
                    lastException = e
                    attempt++
                    if (attempt < maxAttempts) {
                        delay(500)
                    }
                }
            }

            if (!success) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    Toast.makeText(this@NotepadActivity,
                        getString(R.string.note_save_error, lastException?.message), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun onSavePressed() {
        val text = editText.text.toString()
        if (text.isBlank()) {
            Toast.makeText(this, R.string.note_empty_warning, Toast.LENGTH_SHORT).show()
            return
        }
        if (currentFile?.name == "Notepad.txt") {
            showFormatPicker(text)
        } else {
            saveInPlace(text)
        }
    }

    private fun showFormatPicker(text: String) {
        if (isTv) {
            val formats = arrayOf(
                getString(R.string.export_txt),
                getString(R.string.export_csv),
                getString(R.string.export_pdf)
            )
            AlertDialog.Builder(this)
                .setTitle(R.string.notepad_select_export_format)
                .setItems(formats) { _, which ->
                    val ext = arrayOf("txt", "csv", "pdf")[which]
                    pendingFormat = ext
                    pendingFileName = "Notepad.$ext"
                    selectedFolderPath = notesDir?.absolutePath
                    showSaveAsDialog(ext, pendingFileName!!)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else {
            val dialogView = layoutInflater.inflate(R.layout.dialog_notepad_format, null)
            val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
                .setView(dialogView)
                .setCancelable(true)
                .create()

            dialogView.findViewById<View>(R.id.btnFormatTxt).setOnClickListener {
                dialog.dismiss()
                pendingFormat = "txt"
                pendingFileName = "Notepad.txt"
                selectedFolderPath = notesDir?.absolutePath
                showSaveAsDialog("txt", pendingFileName!!)
            }

            dialogView.findViewById<View>(R.id.btnFormatCsv).setOnClickListener {
                dialog.dismiss()
                pendingFormat = "csv"
                pendingFileName = "Notepad.csv"
                selectedFolderPath = notesDir?.absolutePath
                showSaveAsDialog("csv", pendingFileName!!)
            }

            dialogView.findViewById<View>(R.id.btnFormatPdf).setOnClickListener {
                dialog.dismiss()
                pendingFormat = "pdf"
                pendingFileName = "Notepad.pdf"
                selectedFolderPath = notesDir?.absolutePath
                showSaveAsDialog("pdf", pendingFileName!!)
            }

            dialogView.findViewById<View>(R.id.btnCancelFormat).setOnClickListener {
                dialog.dismiss()
            }

            dialog.show()
            dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        }
    }

    private fun showSaveAsDialog(ext: String, name: String) {
        val layoutRes = if (isTv) R.layout.dialog_notepad_save_tv else R.layout.dialog_notepad_save
        val dialogView = layoutInflater.inflate(layoutRes, null)
        val edtFilename = if (isTv) {
            dialogView.findViewById<android.widget.EditText>(R.id.edtFilename)
        } else {
            dialogView.findViewById<TextInputEditText>(R.id.edtFilename)
        }
        val txtPath = dialogView.findViewById<TextView>(R.id.txtSavePath)
        val btnSelectFolder = dialogView.findViewById<View>(R.id.btnSelectFolder)
        val btnConfirm = dialogView.findViewById<View>(R.id.btnConfirmSave)
        val btnCancel = dialogView.findViewById<View>(R.id.btnCancelSave)

        val baseName = name.substringBeforeLast(".")
        edtFilename?.setText(baseName)
        txtPath?.text = selectedFolderPath ?: notesDir?.absolutePath ?: ""

        val dialog = if (isTv) {
            AlertDialog.Builder(this)
                .setView(dialogView)
                .create()
        } else {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
                .setView(dialogView)
                .setCancelable(true)
                .create()
        }

        btnSelectFolder?.setOnClickListener {
            pendingFormat = ext
            pendingFileName = "${edtFilename?.text}.$ext"
            dialog.dismiss()
            val intent = Intent(this, StorageBrowserActivity::class.java).apply {
                putExtra(FileBrowserActivity.EXTRA_NOTEPAD_FOLDER_PICKER, true)
            }
            folderPickerLauncher.launch(intent)
        }

        btnConfirm?.setOnClickListener {
            val nameInput = edtFilename?.text?.toString()?.trim() ?: ""
            if (nameInput.isEmpty()) {
                Toast.makeText(this, R.string.filename_empty_error, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val finalName = "$nameInput.$ext"
            val folder = selectedFolderPath ?: notesDir?.absolutePath ?: cacheDir.absolutePath
            dialog.dismiss()
            doSave(editText.text.toString(), File(folder, finalName), ext, isNewSave = true)
        }

        btnCancel?.setOnClickListener { dialog.dismiss() }

        dialog.show()
        if (!isTv) {
            dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        }
        za.kilowatch.ultimatefilemanager.util.DialogInputHelper.setupDialogInput(dialog, edtFilename) {
            btnConfirm?.performClick()
        }
    }

    private fun onNewDocumentPressed() {
        if (isModified) {
            if (isTv) {
                AlertDialog.Builder(this)
                    .setTitle(R.string.new_document_warning_title)
                    .setMessage(R.string.new_document_warning_message)
                    .setPositiveButton(R.string.btn_discard) { _, _ -> resetToNewDocument() }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            } else {
                val dialogView = layoutInflater.inflate(R.layout.dialog_notepad_unsaved, null)
                val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
                    .setView(dialogView)
                    .setCancelable(true)
                    .create()

                dialogView.findViewById<TextView>(R.id.txtTitle).text = getString(R.string.new_document_warning_title)
                dialogView.findViewById<TextView>(R.id.txtMessage).text = getString(R.string.new_document_warning_message)
                dialogView.findViewById<View>(R.id.btnSaveUnsaved).visibility = View.GONE
                dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDiscardUnsaved).text = getString(R.string.btn_discard)

                dialogView.findViewById<View>(R.id.btnDiscardUnsaved).setOnClickListener {
                    dialog.dismiss()
                    resetToNewDocument()
                }
                dialogView.findViewById<View>(R.id.btnCancelUnsaved).setOnClickListener {
                    dialog.dismiss()
                }

                dialog.show()
                dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            }
        } else {
            resetToNewDocument()
        }
    }

    private fun resetToNewDocument() {
        editText.text.clear()
        fileName = "Notepad.txt"
        txtTitle.text = fileName
        currentFile = File(notesDir, "Notepad.txt")
        selectedFolderPath = notesDir?.absolutePath
        isModified = false
        updateWordCount()
        editText.requestFocus()
    }

    private fun updateWordCount() {
        val text = editText.text.toString()
        val wordCount = if (text.isBlank()) 0 else text.trim().split(Regex("\\s+")).size
        txtWordCount.text = getString(R.string.words_count, wordCount)
        txtCharCount.text = getString(R.string.characters_count, text.length)
    }

    private fun scheduleAutoSave() {
        autoSaveJob?.cancel()
        autoSaveJob = lifecycleScope.launch(Dispatchers.IO) {
            delay(2000)
            autoSave()
        }
    }

    private fun autoSave() {
        val file = currentFile ?: return
        val content = editText.text.toString()
        try {
            if (file.extension.lowercase() != "pdf") {
                file.writeText(content, Charsets.UTF_8)
                isModified = false
            }
        } catch (_: Exception) {}
    }

    private fun showClearConfirmDialog() {
        if (isTv) {
            AlertDialog.Builder(this)
                .setTitle(R.string.clear_text_confirm_title)
                .setMessage(R.string.clear_text_confirm_message)
                .setPositiveButton(R.string.clear_text) { _, _ ->
                    editText.text.clear()
                    isModified = true
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else {
            val dialogView = layoutInflater.inflate(R.layout.dialog_notepad_clear, null)
            val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
                .setView(dialogView)
                .setCancelable(true)
                .create()

            dialogView.findViewById<View>(R.id.btnClearConfirm).setOnClickListener {
                dialog.dismiss()
                editText.text.clear()
                isModified = true
            }

            dialogView.findViewById<View>(R.id.btnCancelClear).setOnClickListener {
                dialog.dismiss()
            }

            dialog.show()
            dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        }
    }

    private fun saveInPlace(text: String) {
        val file = currentFile ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val ext = file.extension.lowercase()
                val isSaf = file is za.kilowatch.ultimatefilemanager.storage.SafFile ||
                            za.kilowatch.ultimatefilemanager.storage.SafTreeManager.isSafPath(file.absolutePath) ||
                            za.kilowatch.ultimatefilemanager.storage.SafTreeManager.hasTreePermissionForPath(this@NotepadActivity, file.absolutePath)
                if (ext == "pdf") {
                    val tempTxt = File(cacheDir, "pdf_temp_${System.currentTimeMillis()}.txt")
                    tempTxt.writeText(text, Charsets.UTF_8)
                    val converter = PdfConverter(this@NotepadActivity)
                    val ok = converter.convertTextToPdf(tempTxt.absolutePath, file.absolutePath, null)
                    tempTxt.delete()
                    if (!ok) throw java.io.IOException("Failed to convert note to PDF")
                } else {
                    val outStream = if (isSaf) {
                        za.kilowatch.ultimatefilemanager.storage.SafTreeManager.openOutputStream(this@NotepadActivity, file.absolutePath)
                    } else {
                        java.io.FileOutputStream(file)
                    } ?: throw java.io.IOException("Cannot open output stream for ${file.absolutePath}")
                    outStream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                }
                withContext(Dispatchers.Main) {
                    isModified = false
                    Toast.makeText(this@NotepadActivity, R.string.file_saved_inplace, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@NotepadActivity,
                        getString(R.string.note_save_error, e.message), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun doSave(content: String, targetFile: File, extension: String, isNewSave: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val isSaf = targetFile is za.kilowatch.ultimatefilemanager.storage.SafFile ||
                            za.kilowatch.ultimatefilemanager.storage.SafTreeManager.isSafPath(targetFile.absolutePath) ||
                            za.kilowatch.ultimatefilemanager.storage.SafTreeManager.hasTreePermissionForPath(this@NotepadActivity, targetFile.absolutePath)
                val targetName = "${targetFile.nameWithoutExtension}.$extension"
                val outPath = if (isSaf) {
                    za.kilowatch.ultimatefilemanager.storage.SafTreeManager.getSafChildPath(targetFile.parentFile?.absolutePath ?: "", targetName)
                } else {
                    File(targetFile.parentFile, targetName).absolutePath
                }
                val resultFile = if (isSaf) za.kilowatch.ultimatefilemanager.storage.SafFile(outPath) else File(outPath)

                when (extension) {
                    "pdf" -> {
                        val tempTxt = File(cacheDir, "pdf_temp_${System.currentTimeMillis()}.txt")
                        tempTxt.writeText(content, Charsets.UTF_8)
                        val converter = PdfConverter(this@NotepadActivity)
                        val ok = converter.convertTextToPdf(tempTxt.absolutePath, outPath, null)
                        tempTxt.delete()
                        if (!ok) throw java.io.IOException("Failed to convert note to PDF")
                        withContext(Dispatchers.Main) {
                            currentFile = resultFile
                            fileName = resultFile.name
                            txtTitle.text = resultFile.name
                        }
                    }
                    else -> {
                        val outStream = if (isSaf) {
                            za.kilowatch.ultimatefilemanager.storage.SafTreeManager.openOutputStream(this@NotepadActivity, outPath)
                        } else {
                            resultFile.parentFile?.mkdirs()
                            java.io.FileOutputStream(resultFile)
                        } ?: throw java.io.IOException("Cannot open output stream for $outPath")
                        outStream.use { it.write(content.toByteArray(Charsets.UTF_8)) }
                        withContext(Dispatchers.Main) {
                            currentFile = resultFile
                            fileName = resultFile.name
                            txtTitle.text = resultFile.name
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    isModified = false
                    Toast.makeText(this@NotepadActivity,
                        getString(R.string.note_saved, targetFile.name), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@NotepadActivity,
                        getString(R.string.note_save_error, e.message), Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
