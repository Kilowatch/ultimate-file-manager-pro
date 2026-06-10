package za.kilowatch.ultimatefilemanager.settings

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import java.io.File

class IconPickerSheet : BottomSheetDialogFragment() {

    private var iconId: String = ""
    private var iconLabel: String = ""
    private var defaultRes: Int = 0
    private var customIconPath: String? = null
    private var builtinAlternatives: IntArray = IntArray(0)
    private var isTv: Boolean = false

    // Temp state — accumulates changes until Done is pressed
    private var pendingBuiltinRes: Int = 0
    private var pendingCustomPath: String? = null

    private var onIconPicked: ((iconId: String, customPath: String?, builtinRes: Int) -> Unit)? = null
    private var onBrowseClicked: (() -> Unit)? = null

    fun setOnIconPickedCallback(cb: (iconId: String, customPath: String?, builtinRes: Int) -> Unit) {
        onIconPicked = cb
    }

    fun setOnBrowseClickedListener(listener: () -> Unit) {
        onBrowseClicked = listener
    }

    /**
     * Called by the host activity when a browse file selection returns.
     * Updates the preview bitmap and stores the path until Done is pressed.
     */
    fun onBrowseResult(filePath: String?) {
        pendingCustomPath = filePath
        pendingBuiltinRes = 0
        val view = view ?: return
        val previewIcon = view.findViewById<ImageView>(R.id.imgCurrentIcon) ?: return
        if (filePath != null) {
            val file = File(filePath)
            if (file.exists()) {
                val bm = BitmapFactory.decodeFile(filePath)
                if (bm != null) {
                    previewIcon.setImageBitmap(bm)
                    return
                }
            }
        }
        // Fallback to current builtin or default
        val res = if (pendingBuiltinRes != 0) pendingBuiltinRes else defaultRes
        previewIcon.setImageResource(res)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isTv = DeviceUtils.isTvDevice(requireContext())
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val layoutRes = if (isTv) R.layout.dialog_icon_picker_tv else R.layout.dialog_icon_picker
        return inflater.inflate(layoutRes, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        iconId = arguments?.getString(ARG_ICON_ID) ?: ""
        iconLabel = arguments?.getString(ARG_ICON_LABEL) ?: ""
        defaultRes = arguments?.getInt(ARG_CURRENT_RES, 0) ?: 0
        customIconPath = arguments?.getString(ARG_CUSTOM_PATH)
        builtinAlternatives = arguments?.getIntArray(ARG_BUILTIN_ALTERNATIVES) ?: IntArray(0)

        // Initialize pending state from existing override
        pendingBuiltinRes = defaultRes
        pendingCustomPath = customIconPath

        view.findViewById<TextView>(R.id.txtIconName)?.text = iconLabel

        // Show current icon preview (existing custom > default)
        val previewIcon = view.findViewById<ImageView>(R.id.imgCurrentIcon)
        if (!customIconPath.isNullOrEmpty()) {
            val bm = IconCustomizationManager.getCustomBitmap(requireContext(), iconId)
            if (bm != null) previewIcon?.setImageBitmap(bm) else previewIcon?.setImageResource(defaultRes)
        } else {
            previewIcon?.setImageResource(defaultRes)
        }

        // Built-in icons grid & collapsible section
        val builtinSection = view.findViewById<View>(R.id.layoutBuiltinSection)
        val builtinToggle = view.findViewById<View>(R.id.layoutBuiltinToggle)
        val expandArrow = view.findViewById<ImageView>(R.id.imgBuiltinExpandArrow)
        val recycler = view.findViewById<RecyclerView>(R.id.recyclerBuiltinIcons)

        if (builtinAlternatives.isNotEmpty()) {
            builtinToggle?.visibility = View.VISIBLE
            builtinSection?.visibility = View.GONE
            
            builtinToggle?.setOnClickListener {
                val isVisible = builtinSection?.visibility == View.VISIBLE
                builtinSection?.visibility = if (isVisible) View.GONE else View.VISIBLE
                expandArrow?.animate()?.rotation(if (isVisible) 0f else 180f)?.setDuration(200)?.start()
            }

            if (recycler != null) {
                val spanCount = if (isTv) 6 else 4
                recycler.layoutManager = GridLayoutManager(context, spanCount)
                recycler.adapter = BuiltinIconAdapter(builtinAlternatives) { selectedRes ->
                    pendingBuiltinRes = selectedRes
                    pendingCustomPath = null
                    previewIcon?.setImageResource(selectedRes)
                }
            }
        } else {
            builtinToggle?.visibility = View.GONE
            builtinSection?.visibility = View.GONE
        }

        // Browse button — delegates to host
        view.findViewById<View>(R.id.btnBrowseIcon)?.setOnClickListener {
            onBrowseClicked?.invoke()
        }

        // Reset button — clears pending selection (does NOT dismiss)
        view.findViewById<View>(R.id.btnResetIcon)?.setOnClickListener {
            pendingBuiltinRes = 0
            pendingCustomPath = null
            previewIcon?.setImageResource(defaultRes)
            builtinSection?.visibility = View.GONE
            expandArrow?.rotation = 0f
        }

        // Done button — confirms current selection, fires callback, dismisses
        view.findViewById<View>(R.id.btnDone)?.setOnClickListener {
            onIconPicked?.invoke(iconId, pendingCustomPath, pendingBuiltinRes)
            dismiss()
        }

        if (isTv) {
            val resetBtn = view.findViewById<MaterialButton>(R.id.btnResetIcon)
            val browseBtn = view.findViewById<MaterialButton>(R.id.btnBrowseIcon)
            val doneBtn = view.findViewById<MaterialButton>(R.id.btnDone)
            
            resetBtn?.let { setupTvButtonFocus(it) }
            browseBtn?.let { setupTvButtonFocus(it) }
            doneBtn?.let { setupTvButtonFocus(it) }
        }
    }

    // ── Built-in icon adapter ────────────────────────────────────────

    private inner class BuiltinIconAdapter(
        private val icons: IntArray,
        private val onIconSelected: (Int) -> Unit
    ) : RecyclerView.Adapter<BuiltinIconAdapter.VH>() {

        inner class VH(val icon: ImageView) : RecyclerView.ViewHolder(icon)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val ctx = parent.context
            val density = ctx.resources.displayMetrics.density
            val size = (density * (if (isTv) 80 else 56)).toInt()
            val pad = (density * 4).toInt()
            val iv = ImageView(ctx)
            iv.layoutParams = ViewGroup.LayoutParams(size, size)
            iv.setPadding(pad, pad, pad, pad)
            iv.scaleType = ImageView.ScaleType.FIT_CENTER
            return VH(iv)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val iconRes = icons[position]
            val ctx = holder.itemView.context
            val density = ctx.resources.displayMetrics.density

            holder.icon.setImageResource(iconRes)
            holder.icon.setPadding(12, 12, 12, 12)
            
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x0FFFFFFF.toInt())
                setStroke(1, 0x33FFFFFF.toInt())
            }
            holder.icon.background = bg

            holder.icon.isFocusable = isTv
            holder.icon.isFocusableInTouchMode = false

            if (isTv) {
                val yellowFill = ctx.getColor(R.color.tv_button_focused_yellow)
                val blackText = ctx.getColor(R.color.tv_button_focused_yellow_text)
                
                holder.icon.setOnFocusChangeListener { _, hasFocus ->
                    val newBg = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        if (hasFocus) {
                            setColor(yellowFill)
                            setStroke((3 * density).toInt(), yellowFill)
                        } else {
                            setColor(0x0FFFFFFF.toInt())
                            setStroke(1, 0x33FFFFFF.toInt())
                        }
                    }
                    holder.icon.background = newBg
                    holder.icon.imageTintList = if (hasFocus) android.content.res.ColorStateList.valueOf(blackText) else null
                }
            }

            holder.icon.setOnClickListener { onIconSelected(iconRes) }
        }

        override fun getItemCount(): Int = icons.size
    }

    private fun setupTvButtonFocus(btn: MaterialButton) {
        val context = requireContext()
        val yellowFill = context.getColor(R.color.tv_button_focused_yellow)
        val blackText = context.getColor(R.color.tv_button_focused_yellow_text)
        val defaultText = context.getColor(R.color.tv_text_primary)
        val defaultBg = context.getColor(R.color.tv_glass_white_10)

        btn.setTextColor(defaultText)

        btn.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                btn.backgroundTintList = android.content.res.ColorStateList.valueOf(yellowFill)
                btn.setTextColor(blackText)
                btn.iconTint = android.content.res.ColorStateList.valueOf(blackText)
            } else {
                btn.backgroundTintList = android.content.res.ColorStateList.valueOf(defaultBg)
                btn.setTextColor(defaultText)
                btn.iconTint = android.content.res.ColorStateList.valueOf(defaultText)
            }
        }
    }

    companion object {
        private const val ARG_ICON_ID = "icon_id"
        private const val ARG_ICON_LABEL = "icon_label"
        private const val ARG_CURRENT_RES = "current_res"
        private const val ARG_CUSTOM_PATH = "custom_path"
        private const val ARG_BUILTIN_ALTERNATIVES = "builtin_alternatives"

        fun newInstance(
            iconId: String,
            label: String,
            currentRes: Int,
            customPath: String?,
            builtinAlts: IntArray
        ): IconPickerSheet {
            return IconPickerSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_ICON_ID, iconId)
                    putString(ARG_ICON_LABEL, label)
                    putInt(ARG_CURRENT_RES, currentRes)
                    putString(ARG_CUSTOM_PATH, customPath)
                    putIntArray(ARG_BUILTIN_ALTERNATIVES, builtinAlts)
                }
            }
        }
    }
}
