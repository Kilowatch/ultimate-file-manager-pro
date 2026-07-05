package za.kilowatch.ultimatefilemanager.settings

import android.content.Context
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

class FileTagsSettingsActivity : AppCompatActivity() {

    private lateinit var switchMultiTagging: SwitchMaterial
    private lateinit var recyclerTags: RecyclerView
    private lateinit var txtNoTags: TextView
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

        switchMultiTagging = findViewById(R.id.switchMultiTagging)
        recyclerTags = findViewById(R.id.recyclerTags)
        txtNoTags = findViewById(R.id.txtNoTags)

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
        adapter.submitList(tags)
        if (tags.isEmpty()) {
            txtNoTags.visibility = View.VISIBLE
            recyclerTags.visibility = View.GONE
        } else {
            txtNoTags.visibility = View.GONE
            recyclerTags.visibility = View.VISIBLE
        }
    }

    private fun showDeleteConfirmDialog(tag: String) {
        val dialogTheme = com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog

        MaterialAlertDialogBuilder(this, dialogTheme)
            .setTitle("Delete Tag")
            .setMessage("Are you sure you want to delete the tag #$tag? It will be removed from all tagged files.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                FileTagsManager.deleteGlobalTag(this, tag)
                Toast.makeText(this, "Tag #$tag deleted", Toast.LENGTH_SHORT).show()
                loadTags()
            }
            .show()
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
            holder.btnDeleteTag.setOnClickListener {
                onDeleteClick(tag)
            }
        }

        override fun getItemCount(): Int = items.size

        class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val txtTagName: TextView = v.findViewById(R.id.txtTagName)
            val btnDeleteTag: ImageView = v.findViewById(R.id.btnDeleteTag)
        }
    }
}
