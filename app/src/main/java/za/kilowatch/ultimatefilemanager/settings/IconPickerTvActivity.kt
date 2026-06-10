package za.kilowatch.ultimatefilemanager.settings

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.storage.StorageBrowserActivity
import za.kilowatch.ultimatefilemanager.storage.FileBrowserActivity
import java.io.File

class IconPickerTvActivity : AppCompatActivity() {

    private lateinit var iconId: String
    private lateinit var iconLabel: String
    private var defaultRes: Int = 0
    private var customIconPath: String? = null
    private lateinit var builtinAlternatives: IntArray

    private var pendingBuiltinRes: Int = 0
    private var pendingCustomPath: String? = null

    private lateinit var previewIcon: ImageView

    private val browseIconLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val selectedPath = result.data?.getStringExtra(FileBrowserActivity.RESULT_SELECTED_PATH)
            if (selectedPath != null) {
                val sourceFile = File(selectedPath)
                if (sourceFile.exists() && sourceFile.length() > 1 * 1024 * 1024) {
                    Toast.makeText(this, R.string.tile_icon_file_too_large, Toast.LENGTH_SHORT).show()
                } else {
                    val privatePath = IconCustomizationManager.copyToPrivateStorage(this, iconId, selectedPath)
                    if (privatePath != null) {
                        pendingCustomPath = privatePath
                        pendingBuiltinRes = 0
                        val bm = BitmapFactory.decodeFile(privatePath)
                        if (bm != null) {
                            previewIcon.setImageBitmap(bm)
                        } else {
                            previewIcon.setImageResource(defaultRes)
                        }
                    } else {
                        Toast.makeText(this, R.string.tile_icon_invalid_file, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_icon_picker_tv)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val tvPad = (27 * resources.displayMetrics.density).toInt()
            v.setPadding(
                systemBars.left + tvPad, systemBars.top + tvPad,
                systemBars.right + tvPad, systemBars.bottom + tvPad
            )
            insets
        }

        iconId = intent.getStringExtra(EXTRA_ICON_ID) ?: ""
        iconLabel = intent.getStringExtra(EXTRA_LABEL) ?: ""
        defaultRes = intent.getIntExtra(EXTRA_CURRENT_RES, 0)
        customIconPath = intent.getStringExtra(EXTRA_CUSTOM_PATH)
        builtinAlternatives = intent.getIntArrayExtra(EXTRA_BUILTIN_ALTERNATIVES) ?: IntArray(0)

        pendingBuiltinRes = defaultRes
        pendingCustomPath = customIconPath

        findViewById<TextView>(R.id.txtIconName).text = iconLabel

        previewIcon = findViewById(R.id.imgCurrentIcon)
        updatePreview()

        val btnBack = findViewById<ImageView>(R.id.btnBack)
        btnBack.setOnClickListener { finish() }
        setupTvIconFocus(btnBack)

        val btnDone = findViewById<MaterialButton>(R.id.btnDone)
        setupTvButtonFocus(btnDone)
        btnDone.setOnClickListener {
            if (pendingBuiltinRes == 0 && pendingCustomPath == null) {
                IconCustomizationManager.clearOverride(this, iconId)
            } else if (pendingCustomPath != null) {
                IconCustomizationManager.setCustomPath(this, iconId, pendingCustomPath)
            } else if (pendingBuiltinRes != 0) {
                IconCustomizationManager.setBuiltinRes(this, iconId, pendingBuiltinRes)
            }
            setResult(RESULT_OK)
            finish()
        }

        val btnBrowseIcon = findViewById<MaterialButton>(R.id.btnBrowseIcon)
        setupTvButtonFocus(btnBrowseIcon)
        btnBrowseIcon.setOnClickListener {
            val intent = Intent(this, StorageBrowserActivity::class.java).apply {
                putExtra(StorageBrowserActivity.EXTRA_TILE_ICON_PICKER, true)
            }
            browseIconLauncher.launch(intent)
        }

        val btnResetIcon = findViewById<MaterialButton>(R.id.btnResetIcon)
        setupTvButtonFocus(btnResetIcon)
        btnResetIcon.setOnClickListener {
            pendingBuiltinRes = 0
            pendingCustomPath = null
            previewIcon.setImageResource(defaultRes)
        }

        val recycler = findViewById<RecyclerView>(R.id.recyclerBuiltinIcons)
        if (builtinAlternatives.isNotEmpty()) {
            recycler.layoutManager = GridLayoutManager(this, 6)
            recycler.adapter = BuiltinIconAdapter(builtinAlternatives) { selectedRes ->
                pendingBuiltinRes = selectedRes
                pendingCustomPath = null
                previewIcon.setImageResource(selectedRes)
            }
        }
    }

    private fun updatePreview() {
        if (!pendingCustomPath.isNullOrEmpty()) {
            val bm = BitmapFactory.decodeFile(pendingCustomPath)
            if (bm != null) {
                previewIcon.setImageBitmap(bm)
                return
            }
        }
        previewIcon.setImageResource(defaultRes)
    }

    private fun setupTvIconFocus(view: View) {
        val whiteCsl = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_text_primary))
        val yellowCsl = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_button_focused_yellow_text))
        if (view is ImageView) {
            view.imageTintList = whiteCsl
            view.setOnFocusChangeListener { _, hasFocus ->
                view.imageTintList = if (hasFocus) yellowCsl else whiteCsl
                if (hasFocus) {
                    view.setBackgroundResource(R.drawable.bg_icon_circle_focused)
                } else {
                    view.setBackgroundResource(R.drawable.bg_icon_circle_accent)
                }
            }
        }
    }

    private fun setupTvButtonFocus(btn: MaterialButton) {
        val yellowFill = getColor(R.color.tv_button_focused_yellow)
        val blackText = getColor(R.color.tv_button_focused_yellow_text)
        val defaultText = getColor(R.color.tv_text_primary)
        val defaultBg = getColor(R.color.tv_glass_white_10)

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

    private inner class BuiltinIconAdapter(
        private val icons: IntArray,
        private val onIconSelected: (Int) -> Unit
    ) : RecyclerView.Adapter<BuiltinIconAdapter.VH>() {

        inner class VH(val icon: ImageView) : RecyclerView.ViewHolder(icon)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val ctx = parent.context
            val density = ctx.resources.displayMetrics.density
            val size = (density * 80).toInt()
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

            holder.icon.isFocusable = true
            holder.icon.isFocusableInTouchMode = false

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

            holder.icon.setOnClickListener { onIconSelected(iconRes) }
        }

        override fun getItemCount(): Int = icons.size
    }

    companion object {
        const val EXTRA_ICON_ID = "extra_icon_id"
        const val EXTRA_LABEL = "extra_label"
        const val EXTRA_CURRENT_RES = "extra_current_res"
        const val EXTRA_CUSTOM_PATH = "extra_custom_path"
        const val EXTRA_BUILTIN_ALTERNATIVES = "extra_builtin_alternatives"
    }
}
