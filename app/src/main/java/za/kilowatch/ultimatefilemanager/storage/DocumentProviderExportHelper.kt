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
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.archive.ArchiveManager
import za.kilowatch.ultimatefilemanager.archive.ArchiveOperationType
import za.kilowatch.ultimatefilemanager.archive.ArchiveProgressDialog
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import za.kilowatch.ultimatefilemanager.util.GoRoLog
import java.io.File

/**
 * Universal UFMStandard helper for selecting export destination providers
 * and handling single-file, folder, and multi-file zip exports to external providers
 * with canonical ArchiveProgressDialog telemetry.
 */
object DocumentProviderExportHelper {

    private const val TAG = "DocProviderExport"

    /**
     * Cleans up a specific temporary export file or staging directory if it resides in [context.cacheDir].
     * Safe to call on any file: if the file is outside cacheDir (e.g. user storage), it is untouched.
     */
    fun cleanupTempFile(context: Context, file: File?) {
        if (file == null) return
        try {
            val cachePath = context.cacheDir.canonicalPath
            val filePath = file.canonicalPath
            if (filePath.startsWith(cachePath)) {
                val parent = file.parentFile
                if (parent != null && parent.name.startsWith("upload_other_")) {
                    parent.deleteRecursively()
                } else if (file.isDirectory) {
                    file.deleteRecursively()
                } else {
                    file.delete()
                }
            }
        } catch (_: Exception) {}
    }

    /**
     * Cleans up temporary staging files or directories if they reside in [context.cacheDir].
     */
    fun cleanupTempFiles(context: Context, files: List<File>?) {
        if (files.isNullOrEmpty()) return
        try {
            val cachePath = context.cacheDir.canonicalPath
            for (f in files) {
                val fPath = f.canonicalPath
                if (fPath.startsWith(cachePath)) {
                    val parent = f.parentFile
                    if (parent != null && parent.name.startsWith("upload_other_")) {
                        parent.deleteRecursively()
                        break
                    } else if (f.isDirectory) {
                        f.deleteRecursively()
                    } else {
                        f.delete()
                    }
                }
            }
        } catch (_: Exception) {}
    }

    /**
     * Scans [context.cacheDir] and prunes any stale temporary export archives (*.zip)
     * or network export staging folders (upload_other_*) older than [maxAgeMs].
     */
    fun pruneStaleExportTempFiles(context: Context, maxAgeMs: Long = 10 * 60 * 1000L) {
        try {
            val cacheDir = context.cacheDir
            val now = System.currentTimeMillis()
            cacheDir.listFiles()?.forEach { file ->
                try {
                    if (file.name.startsWith("upload_other_") && (now - file.lastModified() > maxAgeMs)) {
                        file.deleteRecursively()
                    } else if (file.name.endsWith(".zip") && (now - file.lastModified() > maxAgeMs)) {
                        file.delete()
                    }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

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

        var providerSelected = false
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
            providerSelected = true
            dialog.dismiss()
            onProviderSelected(provider)
        }

        cardOtherStorage.setOnClickListener {
            providerSelected = true
            dialog.dismiss()
            cleanupTempFiles(activity, files)
            activity.startActivity(Intent(activity, OtherStorageActivity::class.java))
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialog.setOnDismissListener {
            if (!providerSelected) {
                cleanupTempFiles(activity, files)
            }
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
        val txtZipDesc = dialogView.findViewById<TextView>(R.id.txtExportZipSubtitle)
        val txtShareTitle = dialogView.findViewById<TextView>(R.id.txtExportShareSheetTitle)
        val txtShareDesc = dialogView.findViewById<TextView>(R.id.txtExportShareSheetSubtitle)
        val imgShareIcon = dialogView.findViewById<ImageView>(R.id.imgExportShareSheetIcon)
        val btnCancel = dialogView.findViewById<View>(R.id.btnCancel)

        if (files.size == 1 && files.first().isDirectory) {
            txtTitle.text = activity.getString(R.string.share_export_folder_title)
            txtSubtitle.text = activity.getString(R.string.share_export_folder_msg, files.first().name, provider.displayName)
        } else {
            txtTitle.text = activity.getString(R.string.share_export_multi_zip_title)
            txtSubtitle.text = activity.getString(R.string.share_export_multi_zip_msg, files.size, provider.displayName)
        }

        txtShareTitle?.text = activity.getString(R.string.share_export_via_app, provider.displayName)
        if (files.any { it.isDirectory }) {
            txtZipDesc?.text = activity.getString(R.string.share_export_as_zip_folder_desc, provider.displayName)
            txtShareDesc?.text = activity.getString(R.string.share_export_via_app_folder_desc, provider.displayName)
        } else {
            txtZipDesc?.text = activity.getString(R.string.share_export_as_zip_desc, provider.displayName)
            txtShareDesc?.text = activity.getString(R.string.share_export_via_app_desc, provider.displayName)
        }

        // Resolve app icon for Option 2 (Upload via Provider App)
        val iconDrawable = try {
            if (provider.packageName.isNotEmpty()) {
                activity.packageManager.getApplicationIcon(provider.packageName)
            } else null
        } catch (_: Exception) { null }

        if (iconDrawable != null && imgShareIcon != null) {
            imgShareIcon.setImageDrawable(iconDrawable)
            imgShareIcon.imageTintList = null
        }

        var optionSelected = false

        cardZip.setOnClickListener {
            optionSelected = true
            dialog.dismiss()
            onExportZip(files, provider)
        }

        cardShareSheet.setOnClickListener {
            if (files.any { it.isDirectory }) {
                val title = activity.getString(R.string.folder_upload_notice_title)
                val msg = if (files.size == 1 && files.first().isDirectory) {
                    activity.getString(R.string.folder_upload_notice_single_msg, files.first().name, provider.displayName)
                } else {
                    activity.getString(R.string.folder_upload_notice_multi_msg, provider.displayName)
                }
                za.kilowatch.ultimatefilemanager.ui.UfmDialogHelper.showConfirmation(
                    context = activity,
                    title = title,
                    message = msg,
                    iconRes = R.drawable.ic_warning,
                    positiveText = activity.getString(R.string.btn_upload_files_only),
                    negativeText = activity.getString(R.string.cancel),
                    onPositive = {
                        optionSelected = true
                        dialog.dismiss()
                        onExportShareSheet(files, provider)
                    }
                )
            } else {
                optionSelected = true
                dialog.dismiss()
                onExportShareSheet(files, provider)
            }
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialog.setOnDismissListener {
            if (!optionSelected) {
                cleanupTempFiles(activity, files)
            }
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
     * Compresses files/folders into a single zip archive in cacheDir using [ArchiveManager.compress]
     * while displaying canonical [ArchiveProgressDialog] telemetry.
     */
    fun createZipForExport(
        activity: AppCompatActivity,
        files: List<File>,
        provider: MountedDocumentProvider,
        onComplete: (File) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val baseName = if (files.size == 1) files.first().nameWithoutExtension.ifEmpty { files.first().name } else "Archive"
        val zipName = if (files.size == 1) "$baseName.zip" else "${baseName}_${System.currentTimeMillis() % 10000}.zip"
        val tempZip = File(activity.cacheDir, zipName)

        val progressTitle = activity.getString(R.string.archive_progress_zipping_other_storage, provider.displayName)
        val (calcFiles, _) = ArchiveManager.calculateTotalFilesAndBytes(files)
        val progressDialog = ArchiveProgressDialog(activity, initialTitle = progressTitle, initialArchiveName = zipName)
        progressDialog.show(
            operation = ArchiveOperationType.COMPRESS,
            archiveName = zipName,
            totalFiles = calcFiles
        )

        var compressJob: kotlinx.coroutines.Job? = null
        progressDialog.setOnCancelListener {
            compressJob?.cancel()
            tempZip.delete()
            cleanupTempFiles(activity, files)
        }

        compressJob = activity.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val result = ArchiveManager.compress(
                    context = activity,
                    sourceFiles = files,
                    destFile = tempZip,
                    password = null,
                    format = ArchiveManager.Format.ZIP,
                    onArchiveProgress = { progress ->
                        activity.runOnUiThread {
                            progressDialog.update(progress)
                        }
                    }
                )
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    if (result.isSuccess) {
                        cleanupTempFiles(activity, files)
                        onComplete(tempZip)
                    } else {
                        tempZip.delete()
                        cleanupTempFiles(activity, files)
                        val ex = result.exceptionOrNull() ?: Exception("Compression failed")
                        onError(if (ex is Exception) ex else Exception(ex.message))
                    }
                }
            } catch (e: CancellationException) {
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    tempZip.delete()
                    cleanupTempFiles(activity, files)
                    Toast.makeText(activity, R.string.archive_cancelled, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    tempZip.delete()
                    cleanupTempFiles(activity, files)
                    GoRoLog.e(TAG, "Failed to zip files for export: ${e.message}")
                    onError(e)
                }
            }
        }
    }

    /**
     * Directly launches the provider application's upload/share activity for the given files,
     * bypassing the generic Android system "Share With" sheet when the target package supports it.
     */
    fun exportFilesViaProviderApp(
        activity: AppCompatActivity,
        files: List<File>,
        provider: MountedDocumentProvider
    ) {
        try {
            val regularFiles = files.flatMap { file ->
                if (file.isDirectory) file.walkTopDown().filter { it.isFile }.toList()
                else listOf(file)
            }

            if (regularFiles.isEmpty()) {
                Toast.makeText(activity, "No files found to upload", Toast.LENGTH_SHORT).show()
                return
            }

            val uris = ArrayList<android.net.Uri>()
            for (f in regularFiles) {
                uris.add(FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", f))
            }

            val intent = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                if (uris.size == 1) {
                    putExtra(Intent.EXTRA_STREAM, uris.first())
                } else {
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (provider.packageName.isNotEmpty()) {
                    setPackage(provider.packageName)
                }
            }

            val resolveActivities = if (provider.packageName.isNotEmpty()) {
                activity.packageManager.queryIntentActivities(intent, 0)
            } else emptyList()

            if (resolveActivities.isNotEmpty()) {
                activity.startActivity(intent)
            } else {
                intent.setPackage(null)
                activity.startActivity(Intent.createChooser(intent, activity.getString(R.string.share_export_to_cloud_provider)))
            }
        } catch (e: Exception) {
            Toast.makeText(activity, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
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
