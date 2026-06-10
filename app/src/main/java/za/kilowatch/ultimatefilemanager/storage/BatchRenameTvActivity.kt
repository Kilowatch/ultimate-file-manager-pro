package za.kilowatch.ultimatefilemanager.storage

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper

/**
 * Fullscreen Activity for batch renaming multiple files/folders on Android TV.
 *
 * Implements a split-pane layout: controls/inputs on the left, scrollable real-time
 * preview on the right. Optimized for D-pad navigation.
 */
class BatchRenameTvActivity : AppCompatActivity() {

    private lateinit var viewModel: BatchRenameViewModel
    private var suppressTextWatcher = false

    // UI references
    private lateinit var edtPattern: TextInputEditText
    private lateinit var txtPatternError: android.widget.TextView
    private lateinit var btnToggleOriginal: MaterialButton
    private lateinit var btnTogglePadding: MaterialButton
    private lateinit var layoutPaddingControls: LinearLayout
    private lateinit var edtPaddingLength: TextInputEditText
    private lateinit var edtPaddingStart: TextInputEditText
    private lateinit var recyclerPreview: androidx.recyclerview.widget.RecyclerView
    private lateinit var btnCancel: MaterialButton
    private lateinit var btnRename: MaterialButton
    private lateinit var btnBack: android.widget.ImageView
    private lateinit var previewAdapter: BatchRenamePreviewAdapter

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_batch_rename_tv)

        @Suppress("DEPRECATION")
        val items: List<BatchRenameItem> = intent.getParcelableArrayListExtra<BatchRenameItem>("items") ?: emptyList()

        if (items.isEmpty()) {
            finish()
            return
        }

        viewModel = ViewModelProvider(this, BatchRenameViewModel.Factory(items))
            .get(BatchRenameViewModel::class.java)

        bindViews()
        setupRecyclerView()
        setupPatternEditText()
        setupToggleButtons()
        setupPaddingInputs()
        setupActionButtons()
        observeViewModel()
        setupBackHandling()
    }

    // ── View binding ─────────────────────────────────────────────────────────

    private fun bindViews() {
        edtPattern = findViewById(R.id.edtPattern)
        txtPatternError = findViewById(R.id.txtPatternError)
        btnToggleOriginal = findViewById(R.id.btnToggleOriginal)
        btnTogglePadding = findViewById(R.id.btnTogglePadding)
        layoutPaddingControls = findViewById(R.id.layoutPaddingControls)
        edtPaddingLength = findViewById(R.id.edtPaddingLength)
        edtPaddingStart = findViewById(R.id.edtPaddingStart)
        recyclerPreview = findViewById(R.id.recyclerPreview)
        btnCancel = findViewById(R.id.btnCancel)
        btnRename = findViewById(R.id.btnRename)
        btnBack = findViewById(R.id.btnBack)
    }

    // ── RecyclerView ─────────────────────────────────────────────────────────

    private fun setupRecyclerView() {
        previewAdapter = BatchRenamePreviewAdapter(isTv = true)
        recyclerPreview.layoutManager = LinearLayoutManager(this)
        recyclerPreview.adapter = previewAdapter
    }

    // ── Pattern EditText + token handling ────────────────────────────────────

    private fun setupPatternEditText() {
        edtPattern.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                if (suppressTextWatcher) return
                val text = s?.toString() ?: ""

                // Check for partial token remnants and clean them
                val cleaned = cleanPartialTokens(text)
                if (cleaned != text) {
                    suppressTextWatcher = true
                    s?.replace(0, s.length, cleaned)
                    suppressTextWatcher = false
                    // After programmatic edit, sync with ViewModel
                    viewModel.updatePattern(s?.toString() ?: "")
                    viewModel.syncButtonStates(s?.toString() ?: "")
                    applyTokenSpans()
                    return
                }

                viewModel.updatePattern(text)
                viewModel.syncButtonStates(text)
                applyTokenSpans()
            }
        })
    }

    /**
     * Remove any fragments that look like partially-deleted tokens.
     */
    private fun cleanPartialTokens(text: String): String {
        val tokens = listOf("{Original}", "{Padding}")
        val tokenPrefixes: Set<String> = tokens.flatMap { token ->
            (1 until token.length).map { token.substring(0, it) }
        }.toSet()

        var result = text
        var changed = false
        var braceIdx = result.indexOf('{')
        while (braceIdx >= 0) {
            val remainder = result.substring(braceIdx)

            val completeToken = tokens.firstOrNull { remainder.startsWith(it) }
            if (completeToken != null) {
                braceIdx = result.indexOf('{', braceIdx + completeToken.length)
                continue
            }

            val matchedPrefix = tokenPrefixes.firstOrNull { remainder.startsWith(it) }
            if (matchedPrefix != null) {
                val endIdx = braceIdx + matchedPrefix.length
                var cleanup = endIdx
                while (cleanup < result.length && result[cleanup] != ' ' && result[cleanup] != '{') {
                    cleanup++
                }
                result = result.substring(0, braceIdx) + result.substring(cleanup)
                changed = true
                braceIdx = result.indexOf('{', braceIdx)
            } else {
                result = result.substring(0, braceIdx) + result.substring(braceIdx + 1)
                changed = true
                braceIdx = result.indexOf('{', braceIdx)
            }
        }
        return if (changed) result.replace(Regex(" +"), " ").trim() else text
    }

    /**
     * Apply pill/chip styling to tokens in the EditText.
     */
    private fun applyTokenSpans() {
        val text = edtPattern.text ?: return
        val spannable = SpannableString(text.toString())
        val tokenColor = ContextCompat.getColor(this, R.color.ufm_primary)
        val tokenTextColor = ContextCompat.getColor(this, R.color.tv_bg_gradient_end)

        val tokens = listOf("{Original}", "{Padding}")
        for (token in tokens) {
            var idx = spannable.indexOf(token)
            while (idx >= 0) {
                spannable.setSpan(
                    BackgroundColorSpan(tokenColor),
                    idx, idx + token.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                spannable.setSpan(
                    ForegroundColorSpan(tokenTextColor),
                    idx, idx + token.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                spannable.setSpan(
                    android.text.style.StyleSpan(Typeface.BOLD),
                    idx, idx + token.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                idx = spannable.indexOf(token, idx + token.length)
            }
        }

        val currentText = text.toString()
        if (spannable.toString() == currentText) {
            suppressTextWatcher = true
            edtPattern.setText(spannable)
            edtPattern.setSelection(text.length)
            suppressTextWatcher = false
        }
    }

    // ── Toggle buttons ───────────────────────────────────────────────────────

    private fun setupToggleButtons() {
        btnToggleOriginal.setOnClickListener {
            val currentState = viewModel.state.value
            val token = "{Original}"
            val newState = !currentState.useOriginal

            suppressTextWatcher = true
            if (newState) {
                // Insert token at cursor position
                val cursor = edtPattern.selectionStart
                val text = edtPattern.text?.toString() ?: ""
                val needsSpace = text.isNotEmpty() && !text.endsWith(" ") && cursor == text.length
                val insertion = (if (needsSpace) " " else "") + token
                val newText = if (cursor >= text.length) {
                    text + insertion
                } else {
                    text.substring(0, cursor) + insertion + text.substring(cursor)
                }
                edtPattern.setText(newText)
                edtPattern.setSelection(cursor + insertion.length)
            } else {
                // Remove token
                val text = edtPattern.text?.toString() ?: ""
                val cleaned = text.replace(token, "").replace("  ", " ").trim()
                edtPattern.setText(cleaned)
                edtPattern.setSelection(cleaned.length)
            }
            suppressTextWatcher = false

            applyTokenSpans()
            viewModel.toggleOriginal()
            viewModel.setPatternText(edtPattern.text?.toString() ?: "")
        }

        btnTogglePadding.setOnClickListener {
            val currentState = viewModel.state.value
            val token = "{Padding}"
            val newState = !currentState.usePadding

            suppressTextWatcher = true
            if (newState) {
                // Insert token at cursor position
                val cursor = edtPattern.selectionStart
                val text = edtPattern.text?.toString() ?: ""
                val needsSpace = text.isNotEmpty() && !text.endsWith(" ") && cursor == text.length
                val insertion = (if (needsSpace) " " else "") + token
                val newText = if (cursor >= text.length) {
                    text + insertion
                } else {
                    text.substring(0, cursor) + insertion + text.substring(cursor)
                }
                edtPattern.setText(newText)
                edtPattern.setSelection(cursor + insertion.length)
            } else {
                // Remove token
                val text = edtPattern.text?.toString() ?: ""
                val cleaned = text.replace(token, "").replace("  ", " ").trim()
                edtPattern.setText(cleaned)
                edtPattern.setSelection(cleaned.length)
            }
            suppressTextWatcher = false

            applyTokenSpans()
            viewModel.togglePadding()
            viewModel.setPatternText(edtPattern.text?.toString() ?: "")
        }
    }

    // ── Padding inputs ───────────────────────────────────────────────────────

    private fun setupPaddingInputs() {
        // Padding length: cap at 9, min 1
        edtPaddingLength.filters = arrayOf(
            InputFilter { source, _, _, dest, dstart, dend ->
                val proposed = StringBuilder(dest).apply {
                    replace(dstart, dend, source.toString())
                }.toString()
                if (proposed.isEmpty()) {
                    null
                } else {
                    val value = proposed.toIntOrNull()
                    if (value != null && value in 1..9) null else ""
                }
            },
            InputFilter.LengthFilter(1)
        )

        edtPaddingLength.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString() ?: ""
                val value = text.toIntOrNull()
                viewModel.setPaddingLength(value ?: 1)
            }
        })

        edtPaddingLength.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val text = edtPaddingLength.text?.toString() ?: ""
                if (text.isEmpty() || text.toIntOrNull() == null) {
                    edtPaddingLength.setText("1")
                    viewModel.setPaddingLength(1)
                }
            }
        }

        // Padding start: non-negative integer
        edtPaddingStart.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString() ?: ""
                val value = text.toIntOrNull()
                viewModel.setPaddingStart(value ?: 0)
            }
        })

        edtPaddingStart.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val text = edtPaddingStart.text?.toString() ?: ""
                if (text.isEmpty() || text.toIntOrNull() == null) {
                    edtPaddingStart.setText("0")
                    viewModel.setPaddingStart(0)
                }
            }
        }

        edtPaddingStart.inputType = EditorInfo.TYPE_CLASS_NUMBER
    }

    // ── Action buttons ───────────────────────────────────────────────────────

    private fun setupActionButtons() {
        btnBack.setOnClickListener {
            handleBackPress()
        }

        btnCancel.setOnClickListener {
            handleBackPress()
        }

        btnRename.setOnClickListener {
            val state = viewModel.state.value
            if (!state.isRenameEnabled) return@setOnClickListener

            val folderCount = state.items.count { it.isDirectory }
            val fileCount = state.items.size - folderCount

            MaterialAlertDialogBuilder(
                this,
                com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog
            )
                .setTitle(getString(R.string.batch_rename_confirm_title))
                .setMessage(getString(R.string.batch_rename_confirm_body, folderCount, fileCount))
                .setNegativeButton(getString(R.string.batch_rename_confirm_cancel), null)
                .setPositiveButton(getString(R.string.batch_rename_confirm_accept)) { _, _ ->
                    executeRename(state)
                }
                .show()
                .also { dialog ->
                    dialog.window?.setBackgroundDrawableResource(R.drawable.bg_dialog_surface)
                }
        }
    }

    private fun executeRename(state: BatchRenameState) {
        val resolvedNames = state.previewItems.map { it.resultingName }

        lifecycleScope.launch {
            val result = BatchRenameExecutor.execute(
                items = state.items,
                resolvedNames = resolvedNames
            )

            withContext(Dispatchers.Main) {
                val message = if (result.failureCount == 0) {
                    getString(R.string.batch_rename_result_success, result.successCount)
                } else {
                    getString(R.string.batch_rename_result_partial,
                        result.successCount, result.failureCount)
                }

                Toast.makeText(this@BatchRenameTvActivity, message, Toast.LENGTH_LONG).show()
                setResult(Activity.RESULT_OK)
                finish()
            }
        }
    }

    // ── ViewModel observation ────────────────────────────────────────────────

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.state.collectLatest { state ->
                btnToggleOriginal.isChecked = state.useOriginal
                btnTogglePadding.isChecked = state.usePadding

                layoutPaddingControls.visibility = if (state.usePadding) View.VISIBLE else View.GONE

                if (state.patternError != null) {
                    txtPatternError.text = getString(
                        resources.getIdentifier(state.patternError, "string", packageName)
                    )
                    txtPatternError.visibility = View.VISIBLE
                } else {
                    txtPatternError.visibility = View.GONE
                }

                btnRename.isEnabled = state.isRenameEnabled
                btnRename.alpha = if (state.isRenameEnabled) 1.0f else 0.5f

                previewAdapter.submitList(state.previewItems)
            }
        }
    }

    // ── Back handling ────────────────────────────────────────────────────────

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(this) {
            handleBackPress()
        }
    }

    private fun handleBackPress() {
        val patternText = edtPattern.text?.toString()?.trim() ?: ""
        if (patternText.isNotEmpty()) {
            MaterialAlertDialogBuilder(
                this,
                com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog
            )
                .setTitle(getString(R.string.batch_rename_back_discard_title))
                .setMessage(getString(R.string.batch_rename_back_discard_body))
                .setNegativeButton(getString(R.string.batch_rename_back_discard_cancel), null)
                .setPositiveButton(getString(R.string.batch_rename_back_discard_confirm)) { _, _ ->
                    finish()
                }
                .show()
                .also { dialog ->
                    dialog.window?.setBackgroundDrawableResource(R.drawable.bg_dialog_surface)
                }
        } else {
            finish()
        }
    }
}
