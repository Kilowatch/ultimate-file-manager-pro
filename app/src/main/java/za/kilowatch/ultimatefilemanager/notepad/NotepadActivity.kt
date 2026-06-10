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
import androidx.core.view.WindowInsetsCompat
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
            val sb = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(sb.left, sb.top, sb.right, sb.bottom)
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
            setStroke(1, android.graphics.Color.parseColor("#330284C7"))
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

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onBackPressed() {
        if (isModified) {
            AlertDialog.Builder(this)
                .setTitle(R.string.unsaved_changes_title)
                .setMessage(R.string.unsaved_changes_message)
                .setPositiveButton(R.string.save) { _, _ -> onSavePressed() }
                .setNegativeButton(R.string.btn_discard) { _, _ -> finish() }
                .setNeutralButton(android.R.string.cancel, null)
                .show()
        } else {
            super.onBackPressed()
        }
    }

    override fun onPause() {
        super.onPause()
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
        txtPath?.text = getString(R.string.notepad_saved_path, selectedFolderPath ?: notesDir?.absolutePath ?: "")

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

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
    }

    private fun onNewDocumentPressed() {
        if (isModified) {
            AlertDialog.Builder(this)
                .setTitle(R.string.new_document_warning_title)
                .setMessage(R.string.new_document_warning_message)
                .setPositiveButton(R.string.btn_discard) { _, _ -> resetToNewDocument() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
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
        AlertDialog.Builder(this)
            .setTitle(R.string.clear_text_confirm_title)
            .setMessage(R.string.clear_text_confirm_message)
            .setPositiveButton(R.string.clear_text) { _, _ ->
                editText.text.clear()
                isModified = true
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun saveInPlace(text: String) {
        val file = currentFile ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val ext = file.extension.lowercase()
                if (ext == "pdf") {
                    val tempTxt = File(cacheDir, "pdf_temp_${System.currentTimeMillis()}.txt")
                    tempTxt.writeText(text, Charsets.UTF_8)
                    val converter = PdfConverter(this@NotepadActivity)
                    converter.convertTextToPdf(tempTxt, file, null)
                    tempTxt.delete()
                } else {
                    file.writeText(text, Charsets.UTF_8)
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
                when (extension) {
                    "pdf" -> {
                        val pdfFile = File(targetFile.parentFile, "${targetFile.nameWithoutExtension}.pdf")
                        val tempTxt = File(cacheDir, "pdf_temp_${System.currentTimeMillis()}.txt")
                        tempTxt.writeText(content, Charsets.UTF_8)
                        val converter = PdfConverter(this@NotepadActivity)
                        converter.convertTextToPdf(tempTxt, pdfFile, null)
                        tempTxt.delete()
                        withContext(Dispatchers.Main) {
                            currentFile = pdfFile
                            fileName = pdfFile.name
                            txtTitle.text = pdfFile.name
                        }
                    }
                    else -> {
                        val extFile = File(targetFile.parentFile, "${targetFile.nameWithoutExtension}.$extension")
                        extFile.writeText(content, Charsets.UTF_8)
                        withContext(Dispatchers.Main) {
                            currentFile = extFile
                            fileName = extFile.name
                            txtTitle.text = extFile.name
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
