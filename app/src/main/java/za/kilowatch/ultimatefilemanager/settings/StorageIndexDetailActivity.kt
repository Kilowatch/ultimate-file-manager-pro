package za.kilowatch.ultimatefilemanager.settings

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.indexing.IndexingRepository
import za.kilowatch.ultimatefilemanager.util.DeviceUtils

/**
 * Detail activity for a specific storage's indexing status.
 */
class StorageIndexDetailActivity : AppCompatActivity() {

    private var isTv = false
    private lateinit var storageId: String
    private lateinit var storageLabel: String
    private lateinit var storagePath: String
    

    companion object {
        const val EXTRA_STORAGE_ID = "extra_storage_id"
        const val EXTRA_STORAGE_LABEL = "extra_storage_label"
        const val EXTRA_STORAGE_PATH = "extra_storage_path"
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        storageId = intent.getStringExtra(EXTRA_STORAGE_ID) ?: ""
        storageLabel = intent.getStringExtra(EXTRA_STORAGE_LABEL) ?: ""
        storagePath = intent.getStringExtra(EXTRA_STORAGE_PATH) ?: ""

        isTv = DeviceUtils.isTvDevice(this)
        if (isTv) {
            setContentView(R.layout.activity_storage_index_detail_tv)
        } else {
            setContentView(R.layout.activity_storage_index_detail)
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val tvPad = if (isTv) (27 * resources.displayMetrics.density).toInt() else 0
            v.setPadding(
                systemBars.left + tvPad, systemBars.top + tvPad,
                systemBars.right + tvPad, systemBars.bottom + tvPad
            )
            insets
        }

        findViewById<TextView>(R.id.txtTitle).text = storageLabel
        
        val btnBack = findViewById<ImageView>(R.id.btnBack)
        if (isTv) {
            val whiteCsl = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_text_primary))
            val blackCsl = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_button_focused_yellow_text))
            btnBack.imageTintList = whiteCsl
            btnBack.setOnFocusChangeListener { _, hasFocus ->
                btnBack.imageTintList = if (hasFocus) blackCsl else whiteCsl
            }
        }
        btnBack.setOnClickListener { finish() }

        val cardDelete = findViewById<MaterialCardView>(R.id.cardDeleteRecords)
        cardDelete.setOnClickListener { confirmDeleteRecords() }

        if (isTv) {
            setupTvFocus(cardDelete)
        }
    }

    private fun confirmDeleteRecords() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setTitle(R.string.storage_indexer_delete_confirm_title)
            .setMessage(R.string.storage_indexer_delete_confirm_message)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                IndexingRepository.getInstance(this).clearIndexForStorage(storageId)
                finish() // Go back after clearing
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun setupTvFocus(card: MaterialCardView) {
        val yellow = getColor(R.color.tv_button_focused_yellow)
        val black = getColor(R.color.tv_button_focused_yellow_text)
        val white = getColor(R.color.tv_text_primary)
        
        card.setOnFocusChangeListener { _, hasFocus ->
            card.backgroundTintList = android.content.res.ColorStateList.valueOf(if (hasFocus) yellow else 0x1AFFFFFF)
            // Need a way to set child text colors like in SettingsActivity
            setChildTextColors(card, if (hasFocus) black else white)
        }
    }

    private fun setChildTextColors(view: android.view.View, color: Int) {
        if (view is android.widget.TextView) { view.setTextColor(color); return }
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) setChildTextColors(view.getChildAt(i), color)
        }
    }
}
