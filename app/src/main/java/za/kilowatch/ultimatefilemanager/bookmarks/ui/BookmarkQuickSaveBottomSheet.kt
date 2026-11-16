package za.kilowatch.ultimatefilemanager.bookmarks.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.bookmarks.db.BookmarkContentType
import za.kilowatch.ultimatefilemanager.bookmarks.db.BookmarkEntity
import za.kilowatch.ultimatefilemanager.bookmarks.db.BookmarkGroupEntity
import za.kilowatch.ultimatefilemanager.bookmarks.db.BookmarkRepository
import za.kilowatch.ultimatefilemanager.bookmarks.util.BookmarkFileCloner
import za.kilowatch.ultimatefilemanager.bookmarks.util.BookmarkMetadataScraper
import java.util.UUID

class BookmarkQuickSaveBottomSheet : BottomSheetDialogFragment() {

    private var sourcePackage: String? = null
    private var sourceAppName: String = "App"
    private var sharedUrl: String? = null
    private var sharedText: String? = null
    private var sharedUris: List<Uri> = emptyList()

    private var onFinishedListener: (() -> Unit)? = null

    private lateinit var imgSourceApp: ImageView
    private lateinit var txtSourceApp: TextView
    private lateinit var layoutDuplicateNotice: LinearLayout
    private lateinit var editTitle: TextInputEditText
    private lateinit var spinnerGroup: Spinner
    private lateinit var editNotes: TextInputEditText
    private lateinit var btnSaveBookmark: MaterialButton
    private lateinit var btnCancel: MaterialButton

    private var availableGroups: List<BookmarkGroupEntity> = emptyList()
    private var selectedGroup: BookmarkGroupEntity? = null

    override fun getTheme(): Int = R.style.UFM_Dialog

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.bottom_sheet_bookmark_quick_save, container, false)
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        onFinishedListener?.invoke()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        imgSourceApp = view.findViewById(R.id.imgSourceApp)
        txtSourceApp = view.findViewById(R.id.txtSourceApp)
        layoutDuplicateNotice = view.findViewById(R.id.layoutDuplicateNotice)
        editTitle = view.findViewById(R.id.editTitle)
        spinnerGroup = view.findViewById(R.id.spinnerGroup)
        editNotes = view.findViewById(R.id.editNotes)
        btnSaveBookmark = view.findViewById(R.id.btnSaveBookmark)
        btnCancel = view.findViewById(R.id.btnCancel)

        txtSourceApp.text = getString(R.string.bookmark_shared_from, sourceAppName)
        loadSourceAppIcon()

        // Set default title
        val defaultTitle = sharedUrl ?: sharedText ?: sharedUris.firstOrNull()?.lastPathSegment ?: ""
        editTitle.setText(defaultTitle)

        btnCancel.setOnClickListener {
            dismiss()
            onFinishedListener?.invoke()
        }

        btnSaveBookmark.setOnClickListener {
            performSave()
        }

        loadGroupsAndAutoSelect()
    }

    private fun loadSourceAppIcon() {
        val pkg = sourcePackage ?: return
        try {
            val pm = requireContext().packageManager
            val icon = pm.getApplicationIcon(pkg)
            imgSourceApp.setImageDrawable(icon)
        } catch (_: Exception) {
            imgSourceApp.setImageResource(R.drawable.ic_bookmark_hub)
        }
    }

    private fun loadGroupsAndAutoSelect() {
        val repo = BookmarkRepository.getInstance(requireContext())
        viewLifecycleOwner.lifecycleScope.launch {
            val autoGroup = repo.getOrCreateGroupForPackage(sourcePackage, sourceAppName)
            val allGroups = repo.getAllGroupsSync()
            availableGroups = allGroups

            val groupNames = allGroups.map { it.name }.toMutableList()
            groupNames.add(getString(R.string.bookmark_new_group))

            val adapter = ArrayAdapter(requireContext(), R.layout.item_dropdown_popup, groupNames)
            adapter.setDropDownViewResource(R.layout.item_dropdown_popup)
            spinnerGroup.adapter = adapter

            val initialIndex = allGroups.indexOfFirst { it.id == autoGroup.id }.coerceAtLeast(0)
            spinnerGroup.setSelection(initialIndex)
            selectedGroup = allGroups.getOrNull(initialIndex)

            spinnerGroup.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (position < allGroups.size) {
                        selectedGroup = allGroups[position]
                    } else {
                        // "+ Create New Group" tapped
                        showCreateNewGroupDialog()
                    }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
    }

    private fun showCreateNewGroupDialog() {
        val ctx = requireContext()
        val input = TextInputEditText(ctx).apply {
            hint = getString(R.string.bookmark_dialog_group_name_hint)
            setTextColor(Color.WHITE)
            textSize = 15f
        }
        val layout = TextInputLayout(ctx, null, com.google.android.material.R.attr.textInputStyle).apply {
            setPadding(40, 20, 40, 0)
            addView(input)
        }

        MaterialAlertDialogBuilder(ctx, R.style.UFM_Dialog)
            .setTitle(R.string.bookmark_dialog_new_group_title)
            .setView(layout)
            .setPositiveButton(R.string.bookmark_dialog_btn_create) { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isNotEmpty()) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        val newGroup = BookmarkGroupEntity(
                            id = "group_${UUID.randomUUID()}",
                            name = name,
                            iconResName = "ic_folder",
                            colorHex = "#3B82F6",
                            isAutoGroup = false,
                            sortOrder = 50
                        )
                        BookmarkRepository.getInstance(ctx).insertGroup(newGroup)
                        loadGroupsAndAutoSelect()
                    }
                }
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                spinnerGroup.setSelection(0)
            }
            .show()
    }

    private fun performSave() {
        val context = context ?: return
        val group = selectedGroup ?: return
        val title = editTitle.text?.toString()?.trim().orEmpty().ifBlank {
            sharedUrl ?: sharedText ?: "Bookmark"
        }
        val notes = editNotes.text?.toString()?.trim()?.takeIf { it.isNotBlank() }

        btnSaveBookmark.isEnabled = false

        viewLifecycleOwner.lifecycleScope.launch {
            val repo = BookmarkRepository.getInstance(context)

            // 1. Files / Media stream shares
            if (sharedUris.isNotEmpty()) {
                var firstSaved: BookmarkEntity? = null
                for (uri in sharedUris) {
                    val cloned = BookmarkFileCloner.cloneStream(context, uri)
                    if (cloned != null) {
                        val item = BookmarkEntity(
                            id = UUID.randomUUID().toString(),
                            title = if (sharedUris.size == 1) title else cloned.fileName,
                            filePath = cloned.filePath,
                            thumbnailPath = cloned.thumbnailPath,
                            contentType = cloned.contentType,
                            sourcePackage = sourcePackage.orEmpty(),
                            sourceAppName = sourceAppName,
                            groupId = group.id,
                            groupName = group.name,
                            notes = notes,
                            fileSizeBytes = cloned.fileSize
                        )
                        val (saved, isDuplicate) = repo.saveBookmark(item)
                        firstSaved = saved
                    }
                }
                Toast.makeText(context, getString(R.string.bookmark_saved_success, group.name), Toast.LENGTH_SHORT).show()
                dismiss()
                onFinishedListener?.invoke()
                return@launch
            }

            // 2. Web Links
            val url = sharedUrl
            if (!url.isNullOrBlank()) {
                val item = BookmarkEntity(
                    id = UUID.randomUUID().toString(),
                    title = title,
                    url = url,
                    contentType = BookmarkContentType.LINK,
                    sourcePackage = sourcePackage.orEmpty(),
                    sourceAppName = sourceAppName,
                    groupId = group.id,
                    groupName = group.name,
                    notes = notes
                )
                val (saved, isDuplicate) = repo.saveBookmark(item)
                Toast.makeText(context, getString(R.string.bookmark_saved_success, group.name), Toast.LENGTH_SHORT).show()

                // Trigger background metadata scraping for rich preview & reader text
                launch(Dispatchers.IO) {
                    try {
                        val meta = BookmarkMetadataScraper.scrape(context.applicationContext, url)
                        val enriched = saved.copy(
                            title = if (saved.title == url && !meta.title.isNullOrBlank()) meta.title else saved.title,
                            thumbnailPath = meta.thumbnailPath ?: saved.thumbnailPath,
                            faviconPath = meta.faviconPath ?: saved.faviconPath,
                            cachedArticleText = meta.articleText
                        )
                        repo.updateBookmark(enriched)
                    } catch (_: Exception) {}
                }

                dismiss()
                onFinishedListener?.invoke()
                return@launch
            }

            // 3. Plain Text / Notes
            val text = sharedText
            if (!text.isNullOrBlank()) {
                val item = BookmarkEntity(
                    id = UUID.randomUUID().toString(),
                    title = title,
                    notes = text,
                    contentType = BookmarkContentType.TEXT_NOTE,
                    sourcePackage = sourcePackage.orEmpty(),
                    sourceAppName = sourceAppName,
                    groupId = group.id,
                    groupName = group.name
                )
                repo.saveBookmark(item)
                Toast.makeText(context, getString(R.string.bookmark_saved_success, group.name), Toast.LENGTH_SHORT).show()
                dismiss()
                onFinishedListener?.invoke()
            }
        }
    }

    companion object {
        fun newInstance(
            sourcePackage: String?,
            sourceAppName: String,
            sharedUrl: String?,
            sharedText: String?,
            sharedUris: List<Uri>,
            onFinished: () -> Unit
        ): BookmarkQuickSaveBottomSheet {
            return BookmarkQuickSaveBottomSheet().apply {
                this.sourcePackage = sourcePackage
                this.sourceAppName = sourceAppName
                this.sharedUrl = sharedUrl
                this.sharedText = sharedText
                this.sharedUris = sharedUris
                this.onFinishedListener = onFinished
            }
        }
    }
}
