package za.kilowatch.ultimatefilemanager.storage

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import za.kilowatch.ultimatefilemanager.R

class TileColorImportBottomSheet : BottomSheetDialogFragment() {

    private var onApply: ((TileColorConfig) -> Unit)? = null
    private var currentValidConfig: TileColorConfig? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.bottom_sheet_tile_color_import, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val etImportCode = view.findViewById<EditText>(R.id.etImportCode)
        val tvImportError = view.findViewById<TextView>(R.id.tvImportError)
        val importPreviewPanel = view.findViewById<View>(R.id.importPreviewPanel)
        val btnImportApply = view.findViewById<View>(R.id.btnImportApply)

        etImportCode.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                val code = s?.toString()?.trim() ?: ""

                if (code.isEmpty()) {
                    tvImportError.visibility = View.GONE
                    importPreviewPanel.visibility = View.GONE
                    btnImportApply.isEnabled = false
                    currentValidConfig = null
                    return
                }

                val config = TileColorCodec.decode(code)
                if (config == null) {
                    tvImportError.visibility = View.VISIBLE
                    tvImportError.text = getString(R.string.tile_color_import_invalid)
                    importPreviewPanel.visibility = View.GONE
                    btnImportApply.isEnabled = false
                    currentValidConfig = null
                } else {
                    tvImportError.visibility = View.GONE
                    currentValidConfig = config
                    updatePreviewPanel(view, config)
                    importPreviewPanel.visibility = View.VISIBLE
                    btnImportApply.isEnabled = true
                }
            }
        })

        btnImportApply.setOnClickListener {
            currentValidConfig?.let { config ->
                onApply?.invoke(config)
                dismiss()
            }
        }
    }

    private fun updatePreviewPanel(view: View, config: TileColorConfig) {
        setPreviewRow(view, R.id.dotImportIcon, R.id.hexImportIcon, config.iconColor)
        setPreviewRow(view, R.id.dotImportTileBg, R.id.hexImportTileBg, config.tileBgColor)
        setPreviewRow(view, R.id.dotImportRing, R.id.hexImportRing, config.ringColor)
        setPreviewRow(view, R.id.dotImportIconBg, R.id.hexImportIconBg, config.iconBgColor)
        setPreviewRow(view, R.id.dotImportLabel, R.id.hexImportLabel, config.labelColor)
    }

    private fun setPreviewRow(view: View, dotId: Int, hexId: Int, color: Int) {
        val dot = view.findViewById<View>(dotId)
        val hexText = view.findViewById<TextView>(hexId)

        if (color == Color.TRANSPARENT) {
            val d = GradientDrawable()
            d.shape = GradientDrawable.OVAL
            d.setColor(Color.TRANSPARENT)
            d.setStroke(2, view.context.getColor(R.color.mobile_glass_stroke))
            dot.background = d
            hexText.text = getString(R.string.tile_color_export_none)
        } else {
            val d = GradientDrawable()
            d.shape = GradientDrawable.OVAL
            d.setColor(color)
            dot.background = d
            hexText.text = String.format("#%08X", color)
        }
    }

    fun setOnApplyListener(listener: (TileColorConfig) -> Unit): TileColorImportBottomSheet {
        onApply = listener
        return this
    }

    companion object {
        const val TAG = "TileColorImportBottomSheet"
    }
}
