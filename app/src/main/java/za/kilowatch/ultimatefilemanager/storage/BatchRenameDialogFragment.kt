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
import androidx.fragment.app.DialogFragment
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
import za.kilowatch.ultimatefilemanager.util.DeviceUtils

/**
 * Bottom sheet dialog for batch renaming multiple files/folders.
 *
 * On Mobile: displayed as a full-screen bottom sheet (STATE_EXPANDED).
 * On TV: displayed as a centered modal dialog.
 *
 * Created via [newInstance] with a list of [BatchRenameItem].
 */
class BatchRenameDialogFragment : DialogFragment() {

    private lateinit var viewModel: BatchRenameViewModel
    private var isTv = false
    private var suppressTextWatcher = false

    // UI references
    private lateinit var edtPattern: TextInputEditText
    private lateinit var edtReplaceText: TextInputEditText
    private lateinit var edtReplaceWith: TextInputEditText
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
        private const val ARG_ITEMS_CACHE_KEY = "items_cache_key"

        fun newInstance(items: List<BatchRenameItem>): BatchRenameDialogFragment {
            val fragment = BatchRenameDialogFragment()
            val cacheKey = BatchRenameItemsCache.put(items)
            fragment.arguments = Bundle().apply {
                putString(ARG_ITEMS_CACHE_KEY, cacheKey)
            }
            return fragment
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isTv = DeviceUtils.isTvDevice(requireContext())
        setStyle(STYLE_NORMAL, R.style.UFM_Dialog)

        val cacheKey = arguments?.getString(ARG_ITEMS_CACHE_KEY)
        val items: List<BatchRenameItem> = cacheKey?.let { BatchRenameItemsCache.peek(it) } ?: emptyList()

        if (items.isEmpty()) {
            dismiss()
            return
        }

        viewModel = ViewModelProvider(this, BatchRenameViewModel.Factory(items))
            .get(BatchRenameViewModel::class.java)
    }

    override fun onDestroy() {
        if (!requireActivity().isChangingConfigurations) {
            arguments?.getString(ARG_ITEMS_CACHE_KEY)?.let { BatchRenameItemsCache.remove(it) }
        }
        super.onDestroy()
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
        setupReplaceInputs()
        setupTokenChips(view)
        setupToggleButtons()
        setupPaddingInputs()
        setupActionButtons()
        observeViewModel()
        setupBackHandling()

        if (!isTv) {
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
            // Full-window dialog on mobile
            dialog.window?.apply {
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
                setDimAmount(0f)
            }
        }
    }

    // ── View binding ─────────────────────────────────────────────────────────

    private fun bindViews(view: View) {
        edtPattern = view.findViewById(R.id.edtPattern)
        edtReplaceText = view.findViewById(R.id.edtReplaceText)
        edtReplaceWith = view.findViewById(R.id.edtReplaceWith)
        txtPatternError = view.findViewById(R.id.txtPatternError)
        btnToggleOriginal = view.findViewById(R.id.btnToggleOriginal)
        btnTogglePadding = view.findViewById(R.id.btnTogglePadding)
        layoutPaddingControls = view.findViewById(R.id.layoutPaddingControls)
        edtPaddingLength = view.findViewById(R.id.edtPaddingLength)
        edtPaddingStart = view.findViewById(R.id.edtPaddingStart)
        recyclerPreview = view.findViewById(R.id.recyclerPreview)
        btnCancel = view.findViewById(R.id.btnCancel)
        btnRename = view.findViewById(R.id.btnRename)

        val btnHelp = view.findViewById<View>(R.id.btnHelp)
        btnHelp?.setOnClickListener {
            showHelpDialog()
        }

        view.findViewById<TextInputEditText>(R.id.edtExtension)?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                viewModel.setCustomExtension(s?.toString() ?: "")
            }
        })

        view.findViewById<TextInputEditText>(R.id.edtYear)?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                viewModel.setCustomYear(s?.toString() ?: "")
            }
        })

        view.findViewById<TextInputEditText>(R.id.edtMonth)?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                viewModel.setCustomMonth(s?.toString() ?: "")
            }
        })

        view.findViewById<TextInputEditText>(R.id.edtDay)?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                viewModel.setCustomDay(s?.toString() ?: "")
            }
        })
    }

    private fun showHelpDialog() {
        MaterialAlertDialogBuilder(
            requireContext(),
            com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog
        )
            .setTitle(getString(R.string.batch_rename_help_title))
            .setMessage(getString(R.string.batch_rename_help_message))
            .setPositiveButton(android.R.string.ok, null)
            .show()
            .also { dialog ->
                dialog.window?.setBackgroundDrawableResource(R.drawable.bg_dialog_surface)
            }
    }

    private fun setupReplaceInputs() {
        edtReplaceText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                viewModel.updateReplaceText(s?.toString() ?: "")
            }
        })

        edtReplaceWith.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                viewModel.updateReplaceWith(s?.toString() ?: "")
            }
        })
    }

    private fun setupTokenChips(view: View) {
        val chipsMap = mapOf(
            R.id.chipNumber to "#",
            R.id.chipPaddedNumber to "###",
            R.id.chipName to "\$N",
            R.id.chipFullName to "\$F",
            R.id.chipYear to "\$Y",
            R.id.chipMonth to "\$M",
            R.id.chipDay to "\$D"
        )
        for ((chipId, token) in chipsMap) {
            val chip = view.findViewById<com.google.android.material.chip.Chip>(chipId) ?: continue
            chip.setOnClickListener {
                if (chip.isChecked) {
                    insertToken(token)
                } else {
                    removeToken(token)
                }
            }
        }

        view.findViewById<com.google.android.material.chip.Chip>(R.id.chipUpper)?.setOnClickListener {
            viewModel.toggleUpperOption()
        }
        view.findViewById<com.google.android.material.chip.Chip>(R.id.chipLower)?.setOnClickListener {
            viewModel.toggleLowerOption()
        }
        view.findViewById<com.google.android.material.chip.Chip>(R.id.chipExt)?.setOnClickListener {
            val currentState = viewModel.state.value.useCustomExtension
            val nextState = !currentState
            viewModel.toggleCustomExtension()
            if (nextState) {
                if (!edtPattern.text.toString().contains("\$N")) {
                    insertToken("\$N")
                }
                removeToken("\$F")
            }
        }
    }

    private fun removeToken(token: String) {
        suppressTextWatcher = true
        val text = edtPattern.text?.toString() ?: ""
        val cleaned = text.replace(token, "").replace("  ", " ").trim()
        edtPattern.setText(cleaned)
        edtPattern.setSelection(cleaned.length)
        suppressTextWatcher = false

        applyTokenSpans()
        viewModel.setPatternText(cleaned)
    }

    private fun insertToken(token: String) {
        val cursor = edtPattern.selectionStart.coerceAtLeast(0)
        val text = edtPattern.text?.toString() ?: ""
        val needsSpace = text.isNotEmpty() && !text.endsWith(" ") && cursor == text.length
        val insertion = (if (needsSpace) " " else "") + token
        val newText = if (cursor >= text.length) {
            text + insertion
        } else {
            text.substring(0, cursor) + insertion + text.substring(cursor)
        }
        edtPattern.setText(newText)
        edtPattern.setSelection((cursor + insertion.length).coerceAtMost(newText.length))
        viewModel.updatePattern(newText)
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
            val token = "\$F"
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
            val collisionCount = state.previewItems.count { it.conflict == PreviewConflict.COLLISION }

            val confirmMessage = if (collisionCount > 0) {
                getString(R.string.batch_rename_confirm_body, folderCount, fileCount) +
                    "\n\n" + getString(R.string.batch_rename_warning_collisions, collisionCount)
            } else {
                getString(R.string.batch_rename_confirm_body, folderCount, fileCount)
            }

            MaterialAlertDialogBuilder(
                requireContext(),
                com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog
            )
                .setTitle(getString(R.string.batch_rename_confirm_title))
                .setMessage(confirmMessage)
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
                context = context,
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

                // Toggle padding, extension & date card controls visibility
                layoutPaddingControls.visibility = if (state.usePadding) View.VISIBLE else View.GONE
                view?.findViewById<View>(R.id.cardExtensionControls)?.visibility =
                    if (state.useCustomExtension) View.VISIBLE else View.GONE

                val hasDateSelection = state.hasYearToken || state.hasMonthToken || state.hasDayToken
                view?.findViewById<View>(R.id.cardDateControls)?.visibility =
                    if (hasDateSelection) View.VISIBLE else View.GONE

                val tilYear = view?.findViewById<View>(R.id.tilYear)
                val tilMonth = view?.findViewById<View>(R.id.tilMonth)
                val tilDay = view?.findViewById<View>(R.id.tilDay)

                tilYear?.visibility = if (state.hasYearToken) View.VISIBLE else View.GONE
                tilMonth?.visibility = if (state.hasMonthToken) View.VISIBLE else View.GONE
                tilDay?.visibility = if (state.hasDayToken) View.VISIBLE else View.GONE

                val visibleDateFields = listOfNotNull(
                    if (state.hasYearToken) tilYear else null,
                    if (state.hasMonthToken) tilMonth else null,
                    if (state.hasDayToken) tilDay else null
                )
                val gapPx = (6 * resources.displayMetrics.density).toInt()
                visibleDateFields.forEachIndexed { index, field ->
                    val params = field.layoutParams as? LinearLayout.LayoutParams ?: return@forEachIndexed
                    params.leftMargin = 0
                    params.rightMargin = if (index < visibleDateFields.size - 1) gapPx else 0
                    field.layoutParams = params
                }

                // Disable $F when $E is active
                val isExtActive = state.useCustomExtension
                val chipFullName = view?.findViewById<com.google.android.material.chip.Chip>(R.id.chipFullName)
                chipFullName?.isEnabled = !isExtActive
                chipFullName?.alpha = if (isExtActive) 0.5f else 1.0f

                btnToggleOriginal.isEnabled = !isExtActive
                btnToggleOriginal.alpha = if (isExtActive) 0.5f else 1.0f

                // Update token chip checked states
                view?.findViewById<com.google.android.material.chip.Chip>(R.id.chipNumber)?.isChecked = state.hasNumberToken
                view?.findViewById<com.google.android.material.chip.Chip>(R.id.chipPaddedNumber)?.isChecked = state.hasPaddedNumberToken
                view?.findViewById<com.google.android.material.chip.Chip>(R.id.chipName)?.isChecked = state.hasNameToken
                view?.findViewById<com.google.android.material.chip.Chip>(R.id.chipFullName)?.isChecked = state.hasFullNameToken
                view?.findViewById<com.google.android.material.chip.Chip>(R.id.chipYear)?.isChecked = state.hasYearToken
                view?.findViewById<com.google.android.material.chip.Chip>(R.id.chipMonth)?.isChecked = state.hasMonthToken
                view?.findViewById<com.google.android.material.chip.Chip>(R.id.chipDay)?.isChecked = state.hasDayToken
                view?.findViewById<com.google.android.material.chip.Chip>(R.id.chipUpper)?.isChecked = state.hasUpperToken
                view?.findViewById<com.google.android.material.chip.Chip>(R.id.chipLower)?.isChecked = state.hasLowerToken
                view?.findViewById<com.google.android.material.chip.Chip>(R.id.chipExt)?.isChecked = state.useCustomExtension

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
