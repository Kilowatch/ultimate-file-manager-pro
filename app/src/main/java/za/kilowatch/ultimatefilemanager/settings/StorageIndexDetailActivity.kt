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
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.indexing.IndexingRepository
import za.kilowatch.ultimatefilemanager.util.DeviceUtils

/**
 * Detail activity for a specific storage's indexing status.
 * Allows viewing indexing state and deleting indexed records.
 * Follows the Language and Grouped Glass Card design standard.
 */
class StorageIndexDetailActivity : AppCompatActivity() {

    private var isTv = false
    private lateinit var storageId: String
    private lateinit var storageLabel: String
    private lateinit var storagePath: String

    // Persisted through recreate() to prevent looping when restartPending is still true.
    private var handledFontChange = false
    private var handledLocaleChange = false

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
        handledFontChange = savedInstanceState?.getBoolean("font_handled", false) ?: false
        handledLocaleChange = savedInstanceState?.getBoolean("locale_handled", false) ?: false

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

        val btnBack = findViewById<View>(R.id.btnBack)
        if (isTv && btnBack is ImageView) {
            val whiteCsl = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_text_primary))
            val blackCsl = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_button_focused_yellow_text))
            btnBack.imageTintList = whiteCsl
            btnBack.setOnFocusChangeListener { _, hasFocus ->
                btnBack.imageTintList = if (hasFocus) blackCsl else whiteCsl
            }
        }
        btnBack.setOnClickListener { finish() }

        val cardDelete = findViewById<View>(R.id.cardDeleteRecords)
        cardDelete.setOnClickListener { confirmDeleteRecords() }

        if (isTv && cardDelete is MaterialCardView) {
            setupTvFocus(cardDelete)
        }
    }

    override fun onResume() {
        super.onResume()
        if (LocaleHelper.restartPending && !handledLocaleChange) {
            handledLocaleChange = true
            recreate()
            return
        }
        if (FontSizeHelper.restartPending && !handledFontChange) {
            handledFontChange = true
            recreate()
            return
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("font_handled", handledFontChange)
        outState.putBoolean("locale_handled", handledLocaleChange)
    }

    private fun confirmDeleteRecords() {
        val dialogView = layoutInflater.inflate(
            if (isTv) R.layout.dialog_storage_index_delete_confirm_tv else R.layout.dialog_storage_index_delete_confirm,
            null
        )

        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val txtMessage = dialogView.findViewById<TextView>(R.id.txtMessage)
        txtMessage?.text = getString(R.string.storage_indexer_delete_confirm_message)

        val btnDeleteConfirm = dialogView.findViewById<View>(R.id.btnDeleteConfirm)
        val btnCancel = dialogView.findViewById<View>(R.id.btnCancel)

        btnDeleteConfirm.setOnClickListener {
            dialog.dismiss()
            val repo = IndexingRepository.getInstance(this)
            repo.clearIndexForStorage(storageId)
            if (storagePath.isNotEmpty()) {
                lifecycleScope.launch(Dispatchers.IO) {
                    repo.deleteTreeFromIndex(storagePath)
                }
            }
            finish()
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
        if (isTv) btnCancel.requestFocus()
    }

    private fun setupTvFocus(card: MaterialCardView) {
        val yellow = getColor(R.color.tv_button_focused_yellow)
        val black = getColor(R.color.tv_button_focused_yellow_text)
        val white = getColor(R.color.tv_text_primary)
        val errorRed = getColor(R.color.status_error)

        val txtDeleteTitle = card.findViewById<TextView>(R.id.txtDeleteTitle)
        val imgDeleteIcon = card.findViewById<ImageView>(R.id.imgDeleteIcon)

        card.setOnFocusChangeListener { _, hasFocus ->
            card.backgroundTintList = android.content.res.ColorStateList.valueOf(if (hasFocus) yellow else 0x1AFFFFFF)
            if (txtDeleteTitle != null) {
                txtDeleteTitle.setTextColor(if (hasFocus) black else errorRed)
            }
            if (imgDeleteIcon != null) {
                imgDeleteIcon.imageTintList = android.content.res.ColorStateList.valueOf(if (hasFocus) black else errorRed)
            }
        }
    }
}
