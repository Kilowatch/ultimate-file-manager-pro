package za.kilowatch.ultimatefilemanager.settings

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.storage.FileTagsManager
import za.kilowatch.ultimatefilemanager.util.DeviceUtils

/**
 * Mobile-only settings activity for managing file hashtags and multi-tagging preferences.
 */
class FileTagsSettingsActivity : AppCompatActivity() {

    private lateinit var switchMultiTagging: SwitchMaterial
    private lateinit var recyclerTags: RecyclerView
    private lateinit var txtHeaderCreatedTags: View
    private lateinit var layoutEmpty: View
    private lateinit var btnClearAll: View
    private lateinit var adapter: TagsAdapter

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)

        // Exclude TV devices - mobile only
        if (DeviceUtils.isTvDevice(this)) {
            finish()
            return
        }

        enableEdgeToEdge()
        setContentView(R.layout.activity_file_tags_settings)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        findViewById<ImageView>(R.id.btnBack).setOnClickListener {
            finish()
        }

        btnClearAll = findViewById(R.id.btnClearAll)
        btnClearAll.setOnClickListener {
            showClearAllConfirmDialog()
        }

        switchMultiTagging = findViewById(R.id.switchMultiTagging)
        txtHeaderCreatedTags = findViewById(R.id.txtHeaderCreatedTags)
        recyclerTags = findViewById(R.id.recyclerTags)
        layoutEmpty = findViewById(R.id.layoutEmpty)

        setupPrefs()
        setupRecycler()
    }

    private fun setupPrefs() {
        val prefs = getSharedPreferences("ufm_prefs", MODE_PRIVATE)
        switchMultiTagging.isChecked = prefs.getBoolean("pref_multi_file_tagging", false)
        switchMultiTagging.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("pref_multi_file_tagging", isChecked).apply()
        }
    }

    private fun setupRecycler() {
        recyclerTags.layoutManager = LinearLayoutManager(this)
        adapter = TagsAdapter { tag ->
            showDeleteConfirmDialog(tag)
        }
        recyclerTags.adapter = adapter
        loadTags()
    }

    private fun loadTags() {
        val tags = FileTagsManager.getAllCreatedTags(this).sorted()
        val hasTags = tags.isNotEmpty()

        btnClearAll.visibility = if (hasTags) View.VISIBLE else View.GONE
        txtHeaderCreatedTags.visibility = if (hasTags) View.VISIBLE else View.GONE

        if (hasTags) {
            recyclerTags.visibility = View.VISIBLE
            layoutEmpty.visibility = View.GONE
            adapter.submitList(tags)
        } else {
            recyclerTags.visibility = View.GONE
            layoutEmpty.visibility = View.VISIBLE
        }
    }

    private fun showDeleteConfirmDialog(tag: String) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_file_tag_delete_confirm, null)

        val txtMessage = dialogView.findViewById<TextView>(R.id.txtMessage)
        val btnDeleteConfirm = dialogView.findViewById<View>(R.id.btnDeleteConfirm)
        val btnCancel = dialogView.findViewById<View>(R.id.btnCancel)

        txtMessage.text = getString(R.string.settings_file_tags_delete_confirm_message, tag)

        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        btnDeleteConfirm.setOnClickListener {
            dialog.dismiss()
            FileTagsManager.deleteGlobalTag(this, tag)
            Toast.makeText(this, getString(R.string.settings_file_tags_deleted_toast, tag), Toast.LENGTH_SHORT).show()
            loadTags()
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showClearAllConfirmDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_file_tags_clear_all_confirm, null)

        val btnClearConfirm = dialogView.findViewById<View>(R.id.btnClearConfirm)
        val btnCancel = dialogView.findViewById<View>(R.id.btnCancel)

        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        btnClearConfirm.setOnClickListener {
            dialog.dismiss()
            FileTagsManager.clearAllTags(this)
            Toast.makeText(this, R.string.settings_file_tags_cleared_all_toast, Toast.LENGTH_SHORT).show()
            loadTags()
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private class TagsAdapter(private val onDeleteClick: (String) -> Unit) :
        RecyclerView.Adapter<TagsAdapter.ViewHolder>() {

        private val items = mutableListOf<String>()

        fun submitList(newList: List<String>) {
            items.clear()
            items.addAll(newList)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_settings_tag, parent, false)
            return ViewHolder(v)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val tag = items[position]
            holder.txtTagName.text = "#$tag"
            
            val deleteAction = { onDeleteClick(tag) }
            holder.btnDeleteTag.setOnClickListener { deleteAction() }
            holder.btnDeleteContainer?.setOnClickListener { deleteAction() }
        }

        override fun getItemCount(): Int = items.size

        class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val txtTagName: TextView = v.findViewById(R.id.txtTagName)
            val btnDeleteTag: ImageView = v.findViewById(R.id.btnDeleteTag)
            val btnDeleteContainer: View? = v.findViewById(R.id.btnDeleteContainer)
        }
    }
}
