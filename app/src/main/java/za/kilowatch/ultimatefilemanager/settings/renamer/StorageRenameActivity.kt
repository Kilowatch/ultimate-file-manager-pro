package za.kilowatch.ultimatefilemanager.settings.renamer

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import za.kilowatch.ultimatefilemanager.settings.ThemeHelper
import za.kilowatch.ultimatefilemanager.storage.StorageBrowserActivity
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import za.kilowatch.ultimatefilemanager.util.ThemeColors

/**
 * Settings activity to manage custom labels for storage drives.
 * Follows the Language and Grouped Glass Card design standard.
 */
class StorageRenameActivity : AppCompatActivity() {

    private var isTv = false

    private lateinit var contentLayout: LinearLayout
    private lateinit var layoutEmpty: View
    private lateinit var cardInfo: View
    private lateinit var btnClearAll: View

    private data class RenameItem(
        val deviceId: String,
        val customName: String?,
        val originalName: String,
        val sizeBytes: Long,
        val isOnline: Boolean,
        val iconRes: Int
    )

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        isTv = DeviceUtils.isTvDevice(this)
        if (isTv) {
            setContentView(R.layout.activity_storage_rename_tv)
        } else {
            setContentView(R.layout.activity_storage_rename)
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

        val btnBack = findViewById<ImageView?>(R.id.btnBack)
        btnClearAll = findViewById(R.id.btnClearAll)
        cardInfo = findViewById(R.id.cardInfo)
        contentLayout = findViewById(R.id.contentLayout)
        layoutEmpty = findViewById(R.id.layoutEmpty)

        if (isTv) {
            val whiteCsl = ColorStateList.valueOf(getColor(R.color.tv_text_primary))
            val blackCsl = ColorStateList.valueOf(getColor(R.color.tv_button_focused_yellow_text))

            btnBack?.imageTintList = whiteCsl
            btnBack?.setOnFocusChangeListener { _, hasFocus ->
                btnBack.imageTintList = if (hasFocus) blackCsl else whiteCsl
            }

            val clearIv = btnClearAll as? ImageView
            clearIv?.imageTintList = ColorStateList.valueOf(getColor(R.color.status_error))
            clearIv?.setOnFocusChangeListener { _, hasFocus ->
                clearIv.imageTintList = if (hasFocus) blackCsl else ColorStateList.valueOf(getColor(R.color.status_error))
            }
        }

        btnBack?.setOnClickListener { finish() }
        btnClearAll.setOnClickListener { showClearAllDialog() }

        loadData()
    }

    override fun onResume() {
        super.onResume()
        loadData()
    }

    private fun loadData() {
        lifecycleScope.launch(Dispatchers.IO) {
            val dbRenames = StorageRenameManager.getInstance(this@StorageRenameActivity).getAllRenameMapSync()

            // Connected physical drives omitting logical tiles
            val connectedStorages = StorageBrowserActivity.getConnectedStorages(this@StorageRenameActivity)
                .filter {
                    !it.isAppsTile && !it.isRemoteTile && !it.isSettingsTile && !it.isLegalTile &&
                    !it.isRateUsTile && !it.isTipJarTile && !it.isAnalyzerTile && !it.isSearchTile &&
                    !it.isVaultTile && !it.isNetworkTile && !it.isSyncTile && !it.isExtractsTile &&
                    !it.isPairedDevicesTile && !it.isTwinWindowTile && !it.isTerminalTile &&
                    !it.isShizukuTile && !it.isFavoriteTile && !it.isOnlineStoragesTile &&
                    !it.isFileServerTile && !it.isNetworkRoot && !it.isOnlineStorage
                }

            val liveHashedIds = connectedStorages.map { StorageRenameManager.hashDeviceId(it.id) }.toSet()
            val connectedItems = mutableListOf<RenameItem>()
            val offlineItems = mutableListOf<RenameItem>()

            // 1. Connected drives
            for (liveDrive in connectedStorages) {
                val hashedId = StorageRenameManager.hashDeviceId(liveDrive.id)
                val renameEntity = dbRenames[hashedId]
                connectedItems.add(
                    RenameItem(
                        deviceId = hashedId,
                        customName = renameEntity?.customName,
                        originalName = renameEntity?.originalName ?: liveDrive.label,
                        sizeBytes = liveDrive.totalBytes,
                        isOnline = true,
                        iconRes = liveDrive.iconRes
                    )
                )
            }

            // 2. Offline / Disconnected renamed drives
            for ((id, entity) in dbRenames) {
                if (id !in liveHashedIds) {
                    val lowerOriginal = entity.originalName.lowercase()
                    val fallbackIcon = when {
                        lowerOriginal.contains("usb") -> R.drawable.ic_storage_usb
                        lowerOriginal.contains("sd") -> R.drawable.ic_storage_sdcard
                        else -> R.drawable.ic_storage_internal
                    }
                    offlineItems.add(
                        RenameItem(
                            deviceId = id,
                            customName = entity.customName,
                            originalName = entity.originalName,
                            sizeBytes = entity.totalBytes,
                            isOnline = false,
                            iconRes = fallbackIcon
                        )
                    )
                }
            }

            val hasCustomRenames = dbRenames.isNotEmpty()
            val totalCount = connectedItems.size + offlineItems.size

            withContext(Dispatchers.Main) {
                if (totalCount == 0) {
                    cardInfo.visibility = View.GONE
                    btnClearAll.visibility = View.GONE
                    layoutEmpty.visibility = View.VISIBLE
                    // Clear dynamic views from contentLayout keeping only cardInfo
                    removeDynamicSections()
                } else {
                    cardInfo.visibility = View.VISIBLE
                    btnClearAll.visibility = if (hasCustomRenames) View.VISIBLE else View.GONE
                    layoutEmpty.visibility = View.GONE

                    if (isTv) {
                        renderTvSections(connectedItems, offlineItems)
                    } else {
                        renderMobileSections(connectedItems, offlineItems)
                    }
                }
            }
        }
    }

    private fun removeDynamicSections() {
        val count = contentLayout.childCount
        if (count > 1) {
            contentLayout.removeViews(1, count - 1)
        }
    }

    private fun renderMobileSections(connected: List<RenameItem>, offline: List<RenameItem>) {
        removeDynamicSections()
        val inflater = LayoutInflater.from(this)

        // Section 1: Connected Drives
        if (connected.isNotEmpty()) {
            contentLayout.addView(createSectionHeader(R.string.storage_rename_section_connected))
            val glassCard = createGlassCard()
            val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

            connected.forEachIndexed { index, item ->
                val row = inflater.inflate(R.layout.item_storage_rename_row, container, false)
                bindMobileRow(row, item)
                container.addView(row)

                if (index < connected.size - 1) {
                    container.addView(createDivider())
                }
            }
            glassCard.addView(container)
            contentLayout.addView(glassCard)
        }

        // Section 2: Offline / Disconnected Drives
        if (offline.isNotEmpty()) {
            contentLayout.addView(createSectionHeader(R.string.storage_rename_section_offline))
            val glassCard = createGlassCard()
            val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

            offline.forEachIndexed { index, item ->
                val row = inflater.inflate(R.layout.item_storage_rename_row, container, false)
                bindMobileRow(row, item)
                container.addView(row)

                if (index < offline.size - 1) {
                    container.addView(createDivider())
                }
            }
            glassCard.addView(container)
            contentLayout.addView(glassCard)
        }
    }

    private fun bindMobileRow(row: View, item: RenameItem) {
        val imgIcon = row.findViewById<ImageView>(R.id.imgIcon)
        val txtTitle = row.findViewById<TextView>(R.id.txtTitle)
        val txtSubtitle = row.findViewById<TextView>(R.id.txtSubtitle)
        val txtStatus = row.findViewById<TextView>(R.id.txtStatus)
        val btnEditAction = row.findViewById<FrameLayout>(R.id.btnEditAction)
        val imgAction = row.findViewById<ImageView>(R.id.imgAction)

        imgIcon.setImageResource(item.iconRes)

        val formattedSize = if (item.sizeBytes > 0) Formatter.formatFileSize(this, item.sizeBytes) else ""

        if (item.customName != null) {
            txtTitle.text = item.customName
            txtSubtitle.text = if (formattedSize.isNotEmpty()) {
                getString(R.string.storage_rename_original_details, item.originalName, formattedSize)
            } else {
                item.originalName
            }
        } else {
            txtTitle.text = item.originalName
            txtSubtitle.text = formattedSize
        }

        if (item.isOnline) {
            txtStatus.setText(R.string.status_online)
            txtStatus.backgroundTintList = ColorStateList.valueOf(getColor(R.color.status_success))
            imgAction.setImageResource(R.drawable.ic_edit)
            imgAction.imageTintList = ColorStateList.valueOf(getColor(R.color.mobile_icon_tint))
            imgIcon.alpha = 1.0f
            txtTitle.alpha = 1.0f
        } else {
            txtStatus.setText(R.string.status_offline)
            txtStatus.backgroundTintList = ColorStateList.valueOf(getColor(R.color.status_error))
            imgAction.setImageResource(R.drawable.ic_undo)
            imgAction.imageTintList = ColorStateList.valueOf(getColor(R.color.status_error))
            imgIcon.alpha = 0.5f
            txtTitle.alpha = 0.7f
        }

        val clickAction = { showRenameDialog(item) }
        row.setOnClickListener { clickAction() }
        btnEditAction.setOnClickListener { clickAction() }
    }

    private fun renderTvSections(connected: List<RenameItem>, offline: List<RenameItem>) {
        removeDynamicSections()
        val inflater = LayoutInflater.from(this)

        if (connected.isNotEmpty()) {
            contentLayout.addView(createSectionHeader(R.string.storage_rename_section_connected))
            for (item in connected) {
                val card = inflater.inflate(R.layout.item_storage_rename_card_tv, contentLayout, false) as MaterialCardView
                bindTvCard(card, item)
                contentLayout.addView(card)
            }
        }

        if (offline.isNotEmpty()) {
            contentLayout.addView(createSectionHeader(R.string.storage_rename_section_offline))
            for (item in offline) {
                val card = inflater.inflate(R.layout.item_storage_rename_card_tv, contentLayout, false) as MaterialCardView
                bindTvCard(card, item)
                contentLayout.addView(card)
            }
        }
    }

    private fun bindTvCard(card: MaterialCardView, item: RenameItem) {
        val imgIcon = card.findViewById<ImageView>(R.id.imgIcon)
        val txtLabel = card.findViewById<TextView>(R.id.txtLabel)
        val txtSubtitle = card.findViewById<TextView>(R.id.txtSubtitle)
        val txtStatus = card.findViewById<TextView>(R.id.txtStatus)

        imgIcon.setImageResource(item.iconRes)

        val formattedSize = if (item.sizeBytes > 0) Formatter.formatFileSize(this, item.sizeBytes) else ""

        if (item.customName != null) {
            txtLabel.text = item.customName
            txtSubtitle.text = if (formattedSize.isNotEmpty()) {
                getString(R.string.storage_rename_original_details, item.originalName, formattedSize)
            } else {
                item.originalName
            }
        } else {
            txtLabel.text = item.originalName
            txtSubtitle.text = formattedSize
        }

        val badgeTint = if (item.isOnline) getColor(R.color.status_success) else getColor(R.color.status_error)
        txtStatus.setText(if (item.isOnline) R.string.status_online else R.string.status_offline)
        txtStatus.backgroundTintList = ColorStateList.valueOf(badgeTint)

        card.setOnClickListener { showRenameDialog(item) }
        setupTvCardFocus(card, item.isOnline)
    }

    private fun setupTvCardFocus(card: MaterialCardView, isOnline: Boolean) {
        val yellowFill = getColor(R.color.tv_button_focused_yellow)
        val blackText = getColor(R.color.tv_button_focused_yellow_text)
        val glassColor = getColor(R.color.tv_glass_white_10)
        val primaryText = getColor(R.color.tv_text_primary)
        val secondText = getColor(R.color.tv_text_secondary)
        val badgeTint = if (isOnline) getColor(R.color.status_success) else getColor(R.color.status_error)

        val txtLabel = card.findViewById<TextView>(R.id.txtLabel)
        val txtSubtitle = card.findViewById<TextView>(R.id.txtSubtitle)
        val imgIcon = card.findViewById<ImageView>(R.id.imgIcon)
        val txtStatus = card.findViewById<TextView>(R.id.txtStatus)

        card.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                card.setCardBackgroundColor(yellowFill)
                txtLabel?.setTextColor(blackText)
                txtSubtitle?.setTextColor(blackText)
                imgIcon?.imageTintList = ColorStateList.valueOf(blackText)
                txtStatus?.setTextColor(yellowFill)
                txtStatus?.backgroundTintList = ColorStateList.valueOf(blackText)
            } else {
                card.setCardBackgroundColor(glassColor)
                txtLabel?.setTextColor(primaryText)
                txtSubtitle?.setTextColor(secondText)
                imgIcon?.imageTintList = ColorStateList.valueOf(getColor(R.color.tv_accent))
                txtStatus?.setTextColor(Color.WHITE)
                txtStatus?.backgroundTintList = ColorStateList.valueOf(badgeTint)
            }
        }
    }

    private fun createSectionHeader(titleRes: Int): TextView {
        return TextView(this).apply {
            setText(titleRes)
            setTextColor(ThemeColors.primary(this@StorageRenameActivity))
            textSize = 13f
            typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
            isAllCaps = true
            letterSpacing = 0.05f
            val density = resources.displayMetrics.density
            setPadding(
                (4 * density).toInt(),
                (14 * density).toInt(),
                (4 * density).toInt(),
                (8 * density).toInt()
            )
        }
    }

    private fun createGlassCard(): MaterialCardView {
        val density = resources.displayMetrics.density
        return MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (12 * density).toInt()
            }
            radius = 16 * density
            strokeWidth = (1 * density).toInt()
            strokeColor = getColor(R.color.mobile_glass_stroke)
            setCardBackgroundColor(getColor(R.color.mobile_glass_card))
            cardElevation = 0f
        }
    }

    private fun createDivider(): View {
        val density = resources.displayMetrics.density
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (1 * density).toInt()
            ).apply {
                marginStart = (14 * density).toInt()
                marginEnd = (14 * density).toInt()
            }
            setBackgroundColor(getColor(R.color.mobile_glass_stroke))
        }
    }

    private fun showRenameDialog(item: RenameItem) {
        val dialogView = LayoutInflater.from(this).inflate(
            if (isTv) R.layout.dialog_storage_rename_tv
            else R.layout.dialog_storage_rename,
            null
        )

        val txtSubtitle = dialogView.findViewById<TextView>(R.id.txtSubtitle)
        val edtDriveName = dialogView.findViewById<EditText>(R.id.edtDriveName)
        val btnSaveRename = dialogView.findViewById<View>(R.id.btnSaveRename)
        val btnResetRename = dialogView.findViewById<View>(R.id.btnResetRename)
        val btnCancel = dialogView.findViewById<View>(R.id.btnCancel)

        txtSubtitle.text = getString(R.string.storage_rename_dialog_subtitle, item.originalName)
        val currentName = item.customName ?: item.originalName
        edtDriveName.setText(currentName)
        edtDriveName.setSelection(currentName.length)

        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        if (item.customName != null) {
            btnResetRename.visibility = View.VISIBLE
            btnResetRename.setOnClickListener {
                dialog.dismiss()
                lifecycleScope.launch(Dispatchers.IO) {
                    StorageRenameManager.getInstance(this@StorageRenameActivity).deleteRenameByHashedId(item.deviceId)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@StorageRenameActivity, R.string.storage_rename_reset_toast, Toast.LENGTH_SHORT).show()
                        loadData()
                    }
                }
            }
        } else {
            btnResetRename.visibility = View.GONE
        }

        btnSaveRename.setOnClickListener {
            val newName = edtDriveName.text.toString().trim()
            if (newName.isNotEmpty()) {
                dialog.dismiss()
                lifecycleScope.launch(Dispatchers.IO) {
                    StorageRenameManager.getInstance(this@StorageRenameActivity).saveRenameByHashedId(
                        hashedId = item.deviceId,
                        customName = newName,
                        originalName = item.originalName,
                        totalBytes = item.sizeBytes
                    )
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@StorageRenameActivity,
                            getString(R.string.storage_rename_saved_toast, newName),
                            Toast.LENGTH_SHORT
                        ).show()
                        loadData()
                    }
                }
            }
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()

        if (isTv) {
            edtDriveName.requestFocus()
        }
    }

    private fun showClearAllDialog() {
        val dialogView = LayoutInflater.from(this).inflate(
            if (isTv) R.layout.dialog_storage_rename_clear_all_confirm_tv
            else R.layout.dialog_storage_rename_clear_all_confirm,
            null
        )

        val btnClearConfirm = dialogView.findViewById<View>(R.id.btnClearConfirm)
        val btnCancel = dialogView.findViewById<View>(R.id.btnCancel)

        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        btnClearConfirm.setOnClickListener {
            dialog.dismiss()
            lifecycleScope.launch(Dispatchers.IO) {
                StorageRenameManager.getInstance(this@StorageRenameActivity).clearAll()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@StorageRenameActivity, R.string.storage_rename_cleared_all_toast, Toast.LENGTH_SHORT).show()
                    loadData()
                }
            }
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()

        if (isTv) {
            btnCancel.requestFocus()
        }
    }
}
