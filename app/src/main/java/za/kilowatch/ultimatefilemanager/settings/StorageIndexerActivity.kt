package za.kilowatch.ultimatefilemanager.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.storage.StorageManager
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.indexing.IndexingManager
import za.kilowatch.ultimatefilemanager.indexing.IndexingRepository
import za.kilowatch.ultimatefilemanager.network.NetworkShareRepository
import za.kilowatch.ultimatefilemanager.network.PairingManager
import za.kilowatch.ultimatefilemanager.storage.FileBrowserActivity
import za.kilowatch.ultimatefilemanager.storage.StorageItem
import za.kilowatch.ultimatefilemanager.util.DeviceUtils

/**
 * Storage Indexer Activity — hub for managing indexing across all storage devices.
 * Shows a list of Internal, SD, and USB storages.
 */
class StorageIndexerActivity : AppCompatActivity() {

    private var isTv = false
    private lateinit var adapter: StorageIndexerAdapter
    private lateinit var progressBar: ProgressBar
    private lateinit var txtSubtitle: TextView

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        isTv = DeviceUtils.isTvDevice(this)
        if (isTv) {
            setContentView(R.layout.activity_storage_indexer_tv)
        } else {
            setContentView(R.layout.activity_storage_indexer)
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

        txtSubtitle = findViewById(R.id.txtSubtitle)
        progressBar = findViewById(R.id.progressBar)
        val recyclerView = findViewById<RecyclerView>(R.id.recyclerView)

        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = StorageIndexerAdapter(
            isTv = isTv,
            onStorageClick = { storage -> onStorageClick(storage) }
        )
        recyclerView.adapter = adapter

        loadStorages()
    }

    override fun onResume() {
        super.onResume()
        loadStorages()
    }

    private fun loadStorages() {
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val storageItems = withContext(Dispatchers.IO) {
                val items = mutableListOf<StorageItem>()
                val repo = IndexingRepository.getInstance(this@StorageIndexerActivity)
                
                // 1. Local Storage
                val sm = getSystemService(Context.STORAGE_SERVICE) as StorageManager
                sm.storageVolumes.forEach { volume ->
                    val path = if (volume.isPrimary) "/storage/emulated/0" else "/storage/${volume.uuid}"
                    if (path != "/storage/null") {
                        val storageId = IndexingRepository.resolveStorageForPath(path).first
                        val isIndexed = repo.isStorageFullyIndexed(storageId)
                        val count = if (isIndexed) repo.getFileCount(storageId) else 0L

                        items.add(StorageItem(
                            id = storageId,
                            label = volume.getDescription(this@StorageIndexerActivity),
                            iconRes = StorageItem.iconForType(volume.isRemovable, volume.getDescription(this@StorageIndexerActivity)),
                            totalBytes = 0,
                            usedBytes = 0,
                            mountPath = path,
                            isIndexed = isIndexed,
                            indexedFileCount = count
                        ))
                    }
                }
                items
            }

            adapter.submitList(storageItems)
            progressBar.visibility = View.GONE
            txtSubtitle.text = getString(R.string.storage_indexer_subtitle)
        }
    }

    private fun onStorageClick(storage: StorageItem) {
        val repo = IndexingRepository.getInstance(this)
        val storageId = storage.id
        
        if (repo.isStorageFullyIndexed(storageId)) {
            // Already indexed -> Open Detail Screen
            val intent = Intent(this, StorageIndexDetailActivity::class.java).apply {
                putExtra(StorageIndexDetailActivity.EXTRA_STORAGE_ID, storageId)
                putExtra(StorageIndexDetailActivity.EXTRA_STORAGE_LABEL, storage.label)
                putExtra(StorageIndexDetailActivity.EXTRA_STORAGE_PATH, storage.mountPath)
            }
            startActivity(intent)
        } else {
            // Not indexed -> Show Premium Indexing Popup if "Not Now" is set, otherwise use standard flow
            if (repo.hasUserDeclinedIndexing(storageId)) {
                showPremiumIndexingPopup(storage)
            } else {
                // Should we just open the browser or show the offer?
                // The requirements say: "when a user presses on a storage that does not have a full index, 
                // it should give the user a premium popup asking if they want to enable indexing 
                // if the 'Not Now' flag is true"
                // If its FALSE, we just show the standard offer or follow the browse flow?
                // Actually the requirement seems specifically about the "Not Now" case for the indexer.
                showPremiumIndexingPopup(storage)
            }
        }
    }

    private fun showPremiumIndexingPopup(storage: StorageItem) {
        val dialogTheme = R.style.UFM_Dialog
        val dialogView = layoutInflater.inflate(
            if (isTv) R.layout.dialog_storage_indexer_premium_tv else R.layout.dialog_storage_indexer_premium,
            null
        )

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this, dialogTheme)
            .setView(dialogView)
            .create()

        dialogView.findViewById<TextView>(R.id.txtTitle).text = getString(R.string.storage_indexer_premium_title)
        dialogView.findViewById<TextView>(R.id.txtMessage).text = getString(R.string.storage_indexer_premium_message)

        val btnContinue = dialogView.findViewById<View>(R.id.btnContinue)
        val btnExit = dialogView.findViewById<View>(R.id.btnExit)

        btnContinue.setOnClickListener {
            dialog.dismiss()
            // Follow the same process as when the user have indexed the 1st time
            // We need to trigger showIndexingProgressDialog from StorageBrowserActivity?
            // Better to have a shared helper or just implement it here.
            startIndexing(storage)
        }

        btnExit.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun startIndexing(storage: StorageItem) {
        val repo = IndexingRepository.getInstance(this)
        // Clear "Not Now" flag as user chose to Continue
        val prefs = getSharedPreferences("ufm_index_prefs", Context.MODE_PRIVATE)
        prefs.edit().remove("declinedIndexing_${storage.id}").apply()
        
        val storageType = when {
            storage.isNetworkRoot -> "NETWORK"
            else -> "LOCAL"
        }
        showIndexingProgressDialog(storage, storage.id, storageType)
    }

    private fun showIndexingProgressDialog(item: StorageItem, storageId: String, storageType: String) {
        val dialogView = layoutInflater.inflate(
            if (isTv) R.layout.dialog_indexing_progress_tv else R.layout.dialog_indexing_progress,
            null
        )

        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val txtProgressStats = dialogView.findViewById<TextView>(R.id.txtProgressStats)
        val btnRunBackground = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnRunBackground)
        val btnCancel = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCancel)

        // Remove circular progress initialization

        val indexingManager = IndexingManager.getInstance(this)

        btnCancel.setOnClickListener {
            indexingManager.stopIndexing()
            dialog.dismiss()
            loadStorages() // Refresh list
        }

        btnRunBackground.setOnClickListener {
            dialog.dismiss()
            navigateToFileBrowser(item, storageId, storageType)
        }

        dialog.show()
        if (isTv) btnCancel.requestFocus()

        indexingManager.startBackgroundIndexing(
            storageId = storageId,
            storagePath = item.mountPath,
            storageType = storageType,
            onProgress = { current, total ->
                runOnUiThread {
                    if (dialog.isShowing) {
                        txtProgressStats.text = "Indexed $current Files"
                    }
                }
            },
            onComplete = {
                runOnUiThread {
                    if (dialog.isShowing) {
                        dialog.dismiss()
                        loadStorages() // Refresh list to show lightning bolt
                        navigateToFileBrowser(item, storageId, storageType)
                    }
                }
            },
            onError = { e ->
                runOnUiThread {
                    if (dialog.isShowing) {
                        txtProgressStats.text = "Error: ${e.message}"
                        val errColor = if (isTv) R.color.tv_error_red else R.color.status_error
                        txtProgressStats.setTextColor(getColor(errColor))
                        btnRunBackground.setText(R.string.continue_anyway)
                    }
                }
            }
        )
    }

    private fun navigateToFileBrowser(item: StorageItem, storageId: String, storageType: String) {
        val intent = Intent(this, FileBrowserActivity::class.java).apply {
            putExtra(FileBrowserActivity.EXTRA_MOUNT_PATH, item.mountPath)
            putExtra(FileBrowserActivity.EXTRA_STORAGE_LABEL, item.label)
            putExtra(FileBrowserActivity.EXTRA_STORAGE_ID, storageId)
            putExtra(FileBrowserActivity.EXTRA_STORAGE_TYPE, storageType)
        }
        startActivity(intent)
    }
}
