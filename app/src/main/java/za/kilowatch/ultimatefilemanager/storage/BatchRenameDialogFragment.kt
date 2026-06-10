package za.kilowatch.ultimatefilemanager.storage

import android.app.Dialog
import android.content.DialogInterface
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.addCallback
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.util.DeviceUtils

/**
 * Bottom sheet dialog for batch renaming multiple files/folders.
 *
 * On Mobile: displayed as a full-screen bottom sheet (STATE_EXPANDED).
 * On TV: displayed as a centered modal dialog.
 *
 * Created via [newInstance] with a list of [BatchRenameItem].
 */
class BatchRenameDialogFragment : BottomSheetDialogFragment() {

    private lateinit var viewModel: BatchRenameViewModel
    private var isTv = false
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
    private lateinit var previewAdapter: BatchRenamePreviewAdapter

    companion object {
        const val TAG = "BatchRenameDialog"

        fun newInstance(items: List<BatchRenameItem>): BatchRenameDialogFragment {
            val fragment = BatchRenameDialogFragment()
            fragment.arguments = Bundle().apply {
                putParcelableArray("items", items.toTypedArray())
            }
            return fragment
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isTv = DeviceUtils.isTvDevice(requireContext())
        if (isTv) {
            setStyle(STYLE_NORMAL, R.style.UFM_Dialog)
        } else {
            setStyle(STYLE_NORMAL, R.style.TransparentBottomSheetDialog)
        }

        @Suppress("DEPRECATION")
        val items: List<BatchRenameItem> = arguments?.getParcelableArray("items")
            ?.filterIsInstance<BatchRenameItem>() ?: emptyList()

        if (items.isEmpty()) {
            dismiss()
            return
        }

        viewModel = ViewModelProvider(this, BatchRenameViewModel.Factory(items))
            .get(BatchRenameViewModel::class.java)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        if (DeviceUtils.isTvDevice(requireContext())) {
            val centeredDialog = Dialog(requireContext(), theme)
            centeredDialog.window?.apply {
                setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
                setGravity(Gravity.CENTER)
            }
            return centeredDialog
        }
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val layoutRes = if (isTv) R.layout.dialog_batch_rename_tv
                        else R.layout.dialog_batch_rename
        return inflater.inflate(layoutRes, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        bindViews(view)
        setupRecyclerView()
        setupPatternEditText()
        setupToggleButtons()
        setupPaddingInputs()
        setupActionButtons()
        observeViewModel()
        setupBackHandling()

        if (!isTv) {
            // Auto-open keyboard on mobile
            edtPattern.requestFocus()
            dialog?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }
    }

    override fun onStart() {
        super.onStart()
        val dialog = dialog ?: return

        if (isTv) {
            // Centered modal on TV: 80% width
            dialog.window?.let { window ->
                val metrics = resources.displayMetrics
                window.setLayout((metrics.widthPixels * 0.8).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
                window.setGravity(Gravity.CENTER)
            }
        } else {
            // Full-screen bottom sheet on mobile
            (dialog as? BottomSheetDialog)?.behavior?.apply {
                state = BottomSheetBehavior.STATE_EXPANDED
                skipCollapsed = true
            }
        }
    }

    // ── View binding ─────────────────────────────────────────────────────────

    private fun bindViews(view: View) {
        edtPattern = view.findViewById(R.id.edtPattern)
        txtPatternError = view.findViewById(R.id.txtPatternError)
        btnToggleOriginal = view.findViewById(R.id.btnToggleOriginal)
        btnTogglePadding = view.findViewById(R.id.btnTogglePadding)
        layoutPaddingControls = view.findViewById(R.id.layoutPaddingControls)
        edtPaddingLength = view.findViewById(R.id.edtPaddingLength)
        edtPaddingStart = view.findViewById(R.id.edtPaddingStart)
        recyclerPreview = view.findViewById(R.id.recyclerPreview)
        btnCancel = view.findViewById(R.id.btnCancel)
        btnRename = view.findViewById(R.id.btnRename)
    }

    // ── RecyclerView ─────────────────────────────────────────────────────────

    private fun setupRecyclerView() {
        previewAdapter = BatchRenamePreviewAdapter(isTv)
        recyclerPreview.layoutManager = LinearLayoutManager(requireContext())
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
     *
     * When the user backspaces into a token (e.g. "{Original" after deleting "}"),
     * the entire corrupted token fragment is removed atomically — not just the brace.
     * A stray "{" that matches no token prefix is removed individually.
     */
    /**
     * Returns [text] with any mangled token fragments removed. If the text is
     * unchanged (no fragments found), it is returned as-is — spaces and all.
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
        // Only clean up whitespace when we actually removed something
        return if (changed) result.replace(Regex(" +"), " ").trim() else text
    }

    /**
     * Apply pill/chip styling to tokens in the EditText.
     */
    private fun applyTokenSpans() {
        val text = edtPattern.text ?: return
        val spannable = SpannableString(text.toString())
        val tokenColor = ContextCompat.getColor(requireContext(), R.color.ufm_primary)
        val tokenTextColor = ContextCompat.getColor(
            requireContext(),
            if (isTv) R.color.tv_bg_gradient_end else android.R.color.white
        )

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
        // Padding length: hard cap at 9, minimum 1
        edtPaddingLength.filters = arrayOf(
            InputFilter { source, _, _, dest, dstart, dend ->
                val proposed = StringBuilder(dest).apply {
                    replace(dstart, dend, source.toString())
                }.toString()
                if (proposed.isEmpty()) {
                    null // allow empty during typing
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

        // Number-only input for start
        edtPaddingStart.inputType = EditorInfo.TYPE_CLASS_NUMBER
    }

    // ── Action buttons ───────────────────────────────────────────────────────

    private fun setupActionButtons() {
        btnCancel.setOnClickListener {
            handleBackPress()
        }

        btnRename.setOnClickListener {
            val state = viewModel.state.value
            if (!state.isRenameEnabled) return@setOnClickListener

            // Count folders and files
            val folderCount = state.items.count { it.isDirectory }
            val fileCount = state.items.size - folderCount

            MaterialAlertDialogBuilder(
                requireContext(),
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

    private var onCompleteListener: OnBatchRenameCompleteListener? = null

    fun setOnCompleteListener(listener: OnBatchRenameCompleteListener): BatchRenameDialogFragment {
        onCompleteListener = listener
        return this
    }

    private fun executeRename(state: BatchRenameState) {
        val resolvedNames = state.previewItems.map { it.resultingName }
        val context = requireContext()  // capture before dismiss

        lifecycleScope.launch {
            val result = BatchRenameExecutor.execute(
                items = state.items,
                resolvedNames = resolvedNames
            )

            withContext(Dispatchers.Main) {
                val listener = onCompleteListener
                dismiss()

                val message = if (result.failureCount == 0) {
                    getString(R.string.batch_rename_result_success, result.successCount)
                } else {
                    getString(R.string.batch_rename_result_partial,
                        result.successCount, result.failureCount)
                }

                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                listener?.onBatchRenameComplete(result.successCount, result.failureCount)
            }
        }
    }

    // ── ViewModel observation ────────────────────────────────────────────────

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.state.collectLatest { state ->
                // Update toggle button visual states
                updateButtonState(btnToggleOriginal, state.useOriginal)
                updateButtonState(btnTogglePadding, state.usePadding)

                // Toggle padding controls visibility
                layoutPaddingControls.visibility = if (state.usePadding) View.VISIBLE else View.GONE

                // Update error state
                if (state.patternError != null) {
                    txtPatternError.text = getString(
                        resources.getIdentifier(state.patternError, "string", requireContext().packageName)
                    )
                    txtPatternError.visibility = View.VISIBLE
                } else {
                    txtPatternError.visibility = View.GONE
                }

                // Update rename button
                btnRename.isEnabled = state.isRenameEnabled
                btnRename.alpha = if (state.isRenameEnabled) 1.0f else 0.5f

                // Update preview list
                previewAdapter.submitList(state.previewItems)
            }
        }
    }

    private fun updateButtonState(button: MaterialButton, isSelected: Boolean) {
        if (isTv) {
            button.isChecked = isSelected
        } else {
            if (isSelected) {
                button.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.ufm_primary))
                button.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.white))
            } else {
                button.setBackgroundColor(ContextCompat.getColor(requireContext(), android.R.color.transparent))
                button.setTextColor(ContextCompat.getColor(requireContext(), R.color.mobile_text_secondary))
            }
        }
    }

    // ── Back handling ────────────────────────────────────────────────────────

    private fun setupBackHandling() {
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
            handleBackPress()
        }
    }

    private fun handleBackPress() {
        val patternText = edtPattern.text?.toString()?.trim() ?: ""
        if (patternText.isNotEmpty()) {
            MaterialAlertDialogBuilder(
                requireContext(),
                com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog
            )
                .setTitle(getString(R.string.batch_rename_back_discard_title))
                .setMessage(getString(R.string.batch_rename_back_discard_body))
                .setNegativeButton(getString(R.string.batch_rename_back_discard_cancel), null)
                .setPositiveButton(getString(R.string.batch_rename_back_discard_confirm)) { _, _ ->
                    dismiss()
                }
                .show()
                .also { dialog ->
                    dialog.window?.setBackgroundDrawableResource(R.drawable.bg_dialog_surface)
                }
        } else {
            dismiss()
        }
    }

    override fun onCancel(dialog: DialogInterface) {
        val patternText = edtPattern.text?.toString()?.trim() ?: ""
        if (patternText.isNotEmpty()) {
            handleBackPress()
        } else {
            super.onCancel(dialog)
        }
    }

    // ── Callback interface ───────────────────────────────────────────────────

    fun interface OnBatchRenameCompleteListener {
        fun onBatchRenameComplete(successCount: Int, failureCount: Int)
    }
}
