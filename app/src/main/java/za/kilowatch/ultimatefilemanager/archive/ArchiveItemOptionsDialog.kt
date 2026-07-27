package za.kilowatch.ultimatefilemanager.archive

import android.app.Dialog
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.util.DeviceUtils

/**
 * Options dialog shown when an item inside a ZIP or 7Z archive is selected.
 * Provides actions: Extract (Copy Out), Move Out of Archive, and Delete from Archive.
 */
class ArchiveItemOptionsDialog : DialogFragment() {

    private var itemName: String = ""
    private var onCopyOutListener: (() -> Unit)? = null
    private var onMoveOutListener: (() -> Unit)? = null
    private var onDeleteListener: (() -> Unit)? = null

    fun setItemName(name: String) { itemName = name }
    fun setOnCopyOut(listener: () -> Unit) { onCopyOutListener = listener }
    fun setOnMoveOut(listener: () -> Unit) { onMoveOutListener = listener }
    fun setOnDelete(listener: () -> Unit) { onDeleteListener = listener }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val isTv = DeviceUtils.isTvDevice(requireContext())
        return if (isTv) {
            super.onCreateDialog(savedInstanceState)
        } else {
            com.google.android.material.bottomsheet.BottomSheetDialog(requireContext(), theme)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val isTv = DeviceUtils.isTvDevice(requireContext())
        val layoutRes = if (isTv) R.layout.dialog_archive_item_options_tv
                        else      R.layout.dialog_archive_item_options
        return inflater.inflate(layoutRes, container, false)
    }

    override fun onStart() {
        super.onStart()
        val isTv = DeviceUtils.isTvDevice(requireContext())
        val window = dialog?.window ?: return
        if (isTv) {
            val screenWidth = requireContext().resources.displayMetrics.widthPixels
            window.setLayout(
                (screenWidth * 0.70f).toInt(),
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
            window.setGravity(android.view.Gravity.CENTER)
            window.setBackgroundDrawableResource(android.R.color.transparent)
        } else {
            window.setLayout(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
            window.setGravity(android.view.Gravity.BOTTOM)
            window.setBackgroundDrawableResource(android.R.color.transparent)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<TextView>(R.id.txtItemName).text = itemName

        val btnExtractTo = view.findViewById<View>(R.id.btnExtractTo)
        val btnMoveOut = view.findViewById<View>(R.id.btnMoveOut)
        val btnDeleteFromArchive = view.findViewById<View>(R.id.btnDeleteFromArchive)

        btnExtractTo.setOnClickListener {
            dismiss()
            onCopyOutListener?.invoke()
        }

        btnMoveOut.setOnClickListener {
            dismiss()
            onMoveOutListener?.invoke()
        }

        btnDeleteFromArchive.setOnClickListener {
            dismiss()
            onDeleteListener?.invoke()
        }

        val context = requireContext()
        if (DeviceUtils.isTvDevice(context)) {
            val black = context.getColor(R.color.tv_button_focused_yellow_text)
            val yellowCsl = ColorStateList.valueOf(context.getColor(R.color.tv_button_focused_yellow))
            val blackCsl = ColorStateList.valueOf(black)

            setupTvFocusRow(btnExtractTo, view.findViewById(R.id.txtExtractText), view.findViewById(R.id.imgExtractIcon), context.getColor(R.color.tv_text_primary), yellowCsl, blackCsl)
            setupTvFocusRow(btnMoveOut, view.findViewById(R.id.txtMoveText), view.findViewById(R.id.imgMoveIcon), context.getColor(R.color.tv_accent), yellowCsl, blackCsl)
            setupTvFocusRow(btnDeleteFromArchive, view.findViewById(R.id.txtDeleteText), view.findViewById(R.id.imgDeleteIcon), context.getColor(R.color.ufm_error), yellowCsl, blackCsl)

            btnMoveOut.requestFocus()
        }
    }

    private fun setupTvFocusRow(
        row: View,
        textView: TextView,
        imageView: ImageView,
        defaultTextColor: Int,
        yellowCsl: ColorStateList,
        blackCsl: ColorStateList
    ) {
        val defaultTextCsl = ColorStateList.valueOf(defaultTextColor)
        row.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                row.backgroundTintList = yellowCsl
                textView.setTextColor(blackCsl.defaultColor)
                imageView.imageTintList = blackCsl
            } else {
                row.backgroundTintList = null
                textView.setTextColor(defaultTextColor)
                imageView.imageTintList = defaultTextCsl
            }
        }
    }

    companion object {
        const val TAG = "ArchiveItemOptionsDialog"
    }
}
