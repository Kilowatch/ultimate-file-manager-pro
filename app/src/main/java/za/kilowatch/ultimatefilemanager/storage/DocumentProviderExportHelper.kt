package za.kilowatch.ultimatefilemanager.storage

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import za.kilowatch.ultimatefilemanager.util.GoRoLog
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Universal UFMStandard helper for selecting export destination providers
 * and handling single-file, folder, and multi-file zip exports to external providers.
 */
object DocumentProviderExportHelper {

    private const val TAG = "DocProviderExport"

    /**
     * Shows a canonical UFMStandard dialog allowing the user to select which mounted Document Provider
     * to export files/folders into, or navigate to Other Storage.
     */
    fun showSelectMountedProviderDialog(
        activity: AppCompatActivity,
        files: List<File>,
        mountedProviders: List<MountedDocumentProvider>,
        onProviderSelected: (MountedDocumentProvider) -> Unit
    ) {
        if (mountedProviders.isEmpty()) {
            Toast.makeText(activity, activity.getString(R.string.no_mounted_doc_providers_toast), Toast.LENGTH_SHORT).show()
            activity.startActivity(Intent(activity, OtherStorageActivity::class.java))
            return
        }

        if (mountedProviders.size == 1) {
            onProviderSelected(mountedProviders.first())
            return
        }

        val isTv = DeviceUtils.isTvDevice(activity)
        val layoutRes = if (isTv) R.layout.dialog_select_destination_provider_tv else R.layout.dialog_select_destination_provider
        val dialogView = LayoutInflater.from(activity).inflate(layoutRes, null)

        val dialog = MaterialAlertDialogBuilder(activity, R.style.UFM_Dialog)
            .setView(dialogView)
            .create()

        val rv = dialogView.findViewById<RecyclerView>(R.id.rvMountedProviders)
        val cardOtherStorage = dialogView.findViewById<MaterialCardView>(R.id.cardOtherStorage)
        val btnCancel = dialogView.findViewById<View>(R.id.btnCancel)

        val installedProviders = try {
            DocumentProviderManager.getInstalledProviders(activity)
        } catch (_: Exception) {
            emptyList()
        }

        rv.layoutManager = LinearLayoutManager(activity)
        rv.adapter = ProviderChoiceAdapter(activity, mountedProviders, installedProviders, isTv) { provider ->
            dialog.dismiss()
            onProviderSelected(provider)
        }

        cardOtherStorage.setOnClickListener {
            dialog.dismiss()
            activity.startActivity(Intent(activity, OtherStorageActivity::class.java))
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        if (isTv) {
            setupCardTvFocus(cardOtherStorage, ContextCompat.getColor(activity, R.color.tv_glass_border))
            (btnCancel as? MaterialButton)?.let { btn ->
                val glassCsl = ColorStateList.valueOf(0x26FFFFFF)
                val yellowCsl = ColorStateList.valueOf(ContextCompat.getColor(activity, R.color.tv_accent))
                btn.backgroundTintList = glassCsl
                btn.setTextColor(Color.WHITE)
                btn.setOnFocusChangeListener { _, hasFocus ->
                    btn.backgroundTintList = if (hasFocus) yellowCsl else glassCsl
                    btn.setTextColor(if (hasFocus) Color.BLACK else Color.WHITE)
                }
            }
        }

        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    }

    /**
     * Determines whether files require compression (multiple items or any directory)
     * and shows a canonical UFMStandard export choice dialog, or calls [onExportSingleFile] directly.
     */
    fun handleExportToProvider(
        activity: AppCompatActivity,
        files: List<File>,
        provider: MountedDocumentProvider,
        onExportSingleFile: (File, MountedDocumentProvider) -> Unit,
        onExportZip: (List<File>, MountedDocumentProvider) -> Unit,
        onExportShareSheet: (List<File>, MountedDocumentProvider) -> Unit
    ) {
        val hasDirectory = files.any { it.isDirectory }
        val isSingleRegularFile = files.size == 1 && !hasDirectory

        if (isSingleRegularFile) {
            onExportSingleFile(files.first(), provider)
            return
        }

        val isTv = DeviceUtils.isTvDevice(activity)
        val layoutRes = if (isTv) R.layout.dialog_export_multi_choice_tv else R.layout.dialog_export_multi_choice
        val dialogView = LayoutInflater.from(activity).inflate(layoutRes, null)

        val dialog = MaterialAlertDialogBuilder(activity, R.style.UFM_Dialog)
            .setView(dialogView)
            .create()

        val txtTitle = dialogView.findViewById<TextView>(R.id.txtExportChoiceTitle)
        val txtSubtitle = dialogView.findViewById<TextView>(R.id.txtExportChoiceSubtitle)
        val cardZip = dialogView.findViewById<MaterialCardView>(R.id.cardExportZip)
        val cardShareSheet = dialogView.findViewById<MaterialCardView>(R.id.cardExportShareSheet)
        val btnCancel = dialogView.findViewById<View>(R.id.btnCancel)

        if (files.size == 1 && files.first().isDirectory) {
            txtTitle.text = activity.getString(R.string.share_export_folder_title)
            txtSubtitle.text = activity.getString(R.string.share_export_folder_msg, files.first().name, provider.displayName)
        } else {
            txtTitle.text = activity.getString(R.string.share_export_multi_zip_title)
            txtSubtitle.text = activity.getString(R.string.share_export_multi_zip_msg, files.size, provider.displayName)
        }

        cardZip.setOnClickListener {
            dialog.dismiss()
            onExportZip(files, provider)
        }

        cardShareSheet.setOnClickListener {
            dialog.dismiss()
            onExportShareSheet(files, provider)
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        if (isTv) {
            setupCardTvFocus(cardZip, ContextCompat.getColor(activity, R.color.tv_accent))
            setupCardTvFocus(cardShareSheet, ContextCompat.getColor(activity, R.color.tv_glass_border))
            (btnCancel as? MaterialButton)?.let { btn ->
                val glassCsl = ColorStateList.valueOf(0x26FFFFFF)
                val yellowCsl = ColorStateList.valueOf(ContextCompat.getColor(activity, R.color.tv_accent))
                btn.backgroundTintList = glassCsl
                btn.setTextColor(Color.WHITE)
                btn.setOnFocusChangeListener { _, hasFocus ->
                    btn.backgroundTintList = if (hasFocus) yellowCsl else glassCsl
                    btn.setTextColor(if (hasFocus) Color.BLACK else Color.WHITE)
                }
            }
        }

        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    }

    /**
     * Recursively compresses files/folders into a single zip archive in cacheDir and returns the zip file.
     */
    fun createZipForExport(
        context: Context,
        files: List<File>,
        onComplete: (File) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val baseName = if (files.size == 1) files.first().nameWithoutExtension.ifEmpty { files.first().name } else "Archive"
        val zipName = if (files.size == 1) "$baseName.zip" else "${baseName}_${System.currentTimeMillis() % 10000}.zip"
        val tempZip = File(context.cacheDir, zipName)

        val scope = (context as? AppCompatActivity)?.lifecycleScope ?: GlobalScope
        scope.launch(Dispatchers.IO) {
            try {
                ZipOutputStream(tempZip.outputStream().buffered()).use { zos ->
                    val buffer = ByteArray(64 * 1024)
                    for (file in files) {
                        if (file.isDirectory) {
                            val rootPath = file.parentFile?.absolutePath ?: ""
                            file.walkTopDown().forEach { f ->
                                if (f.isFile) {
                                    val relPath = f.absolutePath.removePrefix(rootPath).removePrefix(File.separator)
                                    val entry = ZipEntry(relPath.replace(File.separatorChar, '/'))
                                    zos.putNextEntry(entry)
                                    f.inputStream().buffered().use { fis ->
                                        var len: Int
                                        while (fis.read(buffer).also { len = it } != -1) {
                                            zos.write(buffer, 0, len)
                                        }
                                    }
                                    zos.closeEntry()
                                }
                            }
                        } else if (file.isFile) {
                            val entry = ZipEntry(file.name)
                            zos.putNextEntry(entry)
                            file.inputStream().buffered().use { fis ->
                                var len: Int
                                while (fis.read(buffer).also { len = it } != -1) {
                                    zos.write(buffer, 0, len)
                                }
                            }
                            zos.closeEntry()
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    onComplete(tempZip)
                }
            } catch (e: Exception) {
                GoRoLog.e(TAG, "Failed to zip files for export: ${e.message}")
                withContext(Dispatchers.Main) {
                    onError(e)
                }
            }
        }
    }

    private fun setupCardTvFocus(card: MaterialCardView, defaultStrokeColor: Int) {
        val yellow = ContextCompat.getColor(card.context, R.color.tv_accent)
        val defaultBg = ContextCompat.getColor(card.context, R.color.tv_glass_white_10)

        card.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                card.setCardBackgroundColor(ColorStateList.valueOf(yellow))
                card.strokeColor = yellow
            } else {
                card.setCardBackgroundColor(ColorStateList.valueOf(defaultBg))
                card.strokeColor = defaultStrokeColor
            }
        }
    }

    private class ProviderChoiceAdapter(
        private val context: Context,
        private val items: List<MountedDocumentProvider>,
        private val installed: List<DocumentProviderInfo>,
        private val isTv: Boolean,
        private val onItemClick: (MountedDocumentProvider) -> Unit
    ) : RecyclerView.Adapter<ProviderChoiceAdapter.ViewHolder>() {

        class ViewHolder(val card: MaterialCardView) : RecyclerView.ViewHolder(card) {
            val imgIcon: ImageView = card.findViewById(R.id.imgProviderIcon)
            val txtName: TextView = card.findViewById(R.id.txtProviderName)
            val txtPackage: TextView = card.findViewById(R.id.txtProviderPackage)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val layoutRes = if (isTv) R.layout.item_export_destination_provider_tv else R.layout.item_export_destination_provider
            val view = LayoutInflater.from(parent.context).inflate(layoutRes, parent, false) as MaterialCardView
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.txtName.text = item.displayName
            holder.txtPackage.text = context.getString(R.string.document_provider_tile_subtitle)

            // Resolve full-color native icon
            val matchingInfo = installed.find { it.authority == item.authority }
            val iconDrawable: Drawable? = matchingInfo?.icon ?: try {
                if (item.packageName.isNotEmpty()) {
                    context.packageManager.getApplicationIcon(item.packageName)
                } else null
            } catch (_: Exception) { null }

            if (iconDrawable != null) {
                holder.imgIcon.setImageDrawable(iconDrawable)
                holder.imgIcon.imageTintList = null
            } else {
                holder.imgIcon.setImageResource(R.drawable.ic_cloud)
                val tint = if (isTv) ContextCompat.getColor(context, R.color.tv_accent)
                           else ContextCompat.getColor(context, R.color.mobile_icon_tint)
                holder.imgIcon.imageTintList = ColorStateList.valueOf(tint)
            }

            if (isTv) {
                setupCardTvFocus(holder.card, ContextCompat.getColor(context, R.color.tv_glass_border))
            }

            holder.card.setOnClickListener {
                onItemClick(item)
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
