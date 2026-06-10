package za.kilowatch.ultimatefilemanager.storage

import za.kilowatch.ultimatefilemanager.util.safeDirectoryPath

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.storage.StorageManager
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import java.io.File
import za.kilowatch.ultimatefilemanager.util.FileTypeIconProvider
import za.kilowatch.ultimatefilemanager.settings.FontSizeHelper
import za.kilowatch.ultimatefilemanager.settings.IconCustomizationManager
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper

/**
 * Simple folder picker used by the Vault to select a folder path.
 * Styled to match the dark TV/app theme.
 */
class VaultFolderPickerActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var recyclerFolders: RecyclerView
    private lateinit var layoutEmpty: LinearLayout
    private lateinit var txtPath: TextView
    private lateinit var btnSelectCurrent: MaterialButton
    private lateinit var adapter: FolderAdapter

    private var currentDir: File? = null

    companion object {
        const val EXTRA_SELECTED_PATH = "selected_path"
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (DeviceUtils.isTvDevice(this)) {
            setContentView(R.layout.activity_vault_folder_picker_tv)
        } else {
            setContentView(R.layout.activity_vault_folder_picker)
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        toolbar = findViewById(R.id.toolbar)
        recyclerFolders = findViewById(R.id.recyclerFolders)
        layoutEmpty = findViewById(R.id.layoutEmpty)
        txtPath = findViewById(R.id.txtPath)
        btnSelectCurrent = findViewById(R.id.btnSelectCurrent)

        // Custom back button in the new header layout
        val btnBack = findViewById<ImageView>(R.id.btnPickerBack)
        val isTv = DeviceUtils.isTvDevice(this)
        
        if (isTv) {
            // TV: apply focus-based tint changes
            val whiteCsl = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_text_primary))
            val blackCsl = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_button_focused_yellow_text))
            btnBack?.imageTintList = whiteCsl
            btnBack?.setOnFocusChangeListener { _, hasFocus ->
                btnBack.imageTintList = if (hasFocus) blackCsl else whiteCsl
            }
        }
        // Mobile: tint is set via app:tint in XML, no override needed
        
        btnBack?.setOnClickListener {
            if (currentDir?.parentFile != null) {
                openDirectory(currentDir!!.parentFile!!)
            } else {
                finish()
            }
        }

        // Select This Folder button — TV focus: yellow bg + black text
        if (isTv) {
            val yellowCsl = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_button_focused_yellow))
            val glassCsl = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_glass_white_15))
            val accentCsl = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_accent))
            val blackCsl = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_button_focused_yellow_text))
            btnSelectCurrent.setOnFocusChangeListener { _, hasFocus ->
                btnSelectCurrent.setTextColor(if (hasFocus) getColor(R.color.tv_button_focused_yellow_text) else getColor(R.color.tv_text_primary))
                btnSelectCurrent.backgroundTintList = if (hasFocus) yellowCsl else glassCsl
                btnSelectCurrent.iconTint = if (hasFocus) blackCsl else accentCsl
            }
        }

        btnSelectCurrent.setOnClickListener {
            val dir = currentDir ?: return@setOnClickListener
            val result = Intent().apply {
                putExtra(EXTRA_SELECTED_PATH, dir.absolutePath)
            }
            setResult(RESULT_OK, result)
            finish()
        }

        adapter = FolderAdapter { file ->
            if (file.isDirectory) {
                openDirectory(file)
            }
        }

        recyclerFolders.layoutManager = LinearLayoutManager(this)
        recyclerFolders.adapter = adapter

        val roots = getStorageRoots()
        if (roots.size == 1) {
            openDirectory(roots.first())
        } else {
            showRoots(roots)
        }
    }

    private fun showRoots(roots: List<File>) {
        txtPath.text = getString(R.string.select_drive)
        currentDir = null
        btnSelectCurrent.isEnabled = false
        adapter.submitList(roots)
        updateEmptyState(roots.isEmpty())
    }

    private fun openDirectory(dir: File) {
        currentDir = dir
        txtPath.text = dir.absolutePath
        btnSelectCurrent.isEnabled = true

        val children = try {
            dir.listFiles()
                ?.filter { it.canRead() }
                ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        } catch (_: Exception) {
            emptyList()
        } ?: emptyList()

        adapter.submitList(children)
        updateEmptyState(children.isEmpty())
    }

    private fun updateEmptyState(isEmpty: Boolean) {
        layoutEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
        recyclerFolders.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    private fun getStorageRoots(): List<File> {
        val roots = mutableListOf<File>()
        val sm = getSystemService(Context.STORAGE_SERVICE) as StorageManager
        for (vol in sm.storageVolumes) {
            try {
                val dirPath = vol.safeDirectoryPath
                val dir = if (dirPath != null) File(dirPath) else null
                if (dir != null && dir.exists() && dir.canRead()) {
                    roots.add(dir)
                }
            } catch (_: Exception) { }
        }
        if (roots.isEmpty()) {
            roots.add(File("/storage/emulated/0"))
        }
        return roots
    }

    private inner class FolderAdapter(
        private val onClick: (File) -> Unit
    ) : RecyclerView.Adapter<FolderAdapter.VH>() {

        private val items = mutableListOf<File>()
        private val isTv = DeviceUtils.isTvDevice(this@VaultFolderPickerActivity)

        fun submitList(newItems: List<File>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val txtName: TextView = itemView.findViewById(R.id.txtFolderName)
            val txtPath: TextView = itemView.findViewById(R.id.txtFolderPath)
            val imgIcon: android.widget.ImageView = itemView.findViewById(R.id.imgFolderIcon)
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
            val layoutRes = if (isTv) R.layout.item_vault_folder_tv else R.layout.item_vault_folder
            val view = android.view.LayoutInflater.from(parent.context)
                .inflate(layoutRes, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val file = items[position]
            val isDir = file.isDirectory

            holder.txtName.text = file.name.ifBlank { file.absolutePath }
            holder.txtPath.text = if (isDir) file.absolutePath else file.extension.uppercase().ifBlank { "FILE" }

            val ctx = holder.itemView.context

            // Set the icon resource
            holder.imgIcon.setImageResource(
                if (isDir) IconCustomizationManager.getEffectiveIconRes(ctx, "folder_default", R.drawable.ic_folder)
                else FileTypeIconProvider.iconForFile(ctx, file)
            )

            // TV: apply custom tinting and focus states
            // Mobile: tint is set via app:tint in XML, no override needed
            if (isTv) {
                val accentColor = ctx.getColor(R.color.tv_accent)
                val hintColor = ctx.getColor(R.color.tv_text_hint)
                holder.imgIcon.imageTintList = android.content.res.ColorStateList.valueOf(
                    if (isDir) accentColor else hintColor
                )

                // TV focus: yellow background + black text on focused folder rows
                if (isDir) {
                    val yellow = ctx.getColor(R.color.tv_button_focused_yellow)
                    val black = ctx.getColor(R.color.tv_button_focused_yellow_text)
                    val white = ctx.getColor(R.color.tv_text_primary)
                    val hint = ctx.getColor(R.color.tv_text_hint)
                    val glassBg = ctx.getColor(R.color.tv_glass_white_10)

                    holder.itemView.setOnFocusChangeListener { _, hasFocus ->
                        holder.itemView.setBackgroundColor(if (hasFocus) yellow else glassBg)
                        holder.txtName.setTextColor(if (hasFocus) black else white)
                        holder.txtPath.setTextColor(if (hasFocus) black else hint)
                        holder.imgIcon.imageTintList = android.content.res.ColorStateList.valueOf(
                            if (hasFocus) black else accentColor
                        )
                    }
                }
            }

            // Files are non-clickable and dimmed; folders navigate on click
            holder.itemView.alpha = if (isDir) 1.0f else 0.55f
            holder.itemView.isClickable = isDir
            holder.itemView.isFocusable = isDir

            if (isDir) {
                holder.itemView.setOnClickListener { onClick(file) }
            } else {
                holder.itemView.setOnClickListener(null)
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
