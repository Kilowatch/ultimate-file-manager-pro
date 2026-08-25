package za.kilowatch.ultimatefilemanager.settings

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import za.kilowatch.ultimatefilemanager.util.ThemeColors

/**
 * Settings activity to configure which icons appear on the selection action bar / toolbar.
 * Follows the Language and Grouped Glass Card design standard.
 */
class ToolbarIconsActivity : AppCompatActivity() {

    private var isTv = false
    private lateinit var contentLayout: LinearLayout

    private data class ToolbarItem(
        val iconResId: Int,
        val nameResId: Int,
        val descResId: Int,
        val prefKey: String,
        val customIconKey: String? = null,
        val mobileOnly: Boolean = false
    )

    private data class ToolbarSection(
        val titleResId: Int,
        val items: List<ToolbarItem>
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
            setContentView(R.layout.activity_toolbar_icons_tv)
        } else {
            setContentView(R.layout.activity_toolbar_icons)
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
        val btnResetDefaults = findViewById<View?>(R.id.btnResetDefaults)
        contentLayout = findViewById(R.id.contentLayout)

        if (isTv) {
            val whiteCsl = ColorStateList.valueOf(getColor(R.color.tv_text_primary))
            val blackCsl = ColorStateList.valueOf(getColor(R.color.tv_button_focused_yellow_text))

            btnBack?.imageTintList = whiteCsl
            btnBack?.setOnFocusChangeListener { _, hasFocus ->
                btnBack.imageTintList = if (hasFocus) blackCsl else whiteCsl
            }

            if (btnResetDefaults is ImageView) {
                btnResetDefaults.imageTintList = whiteCsl
                btnResetDefaults.setOnFocusChangeListener { _, hasFocus ->
                    btnResetDefaults.imageTintList = if (hasFocus) blackCsl else whiteCsl
                }
            }
        }

        btnBack?.setOnClickListener { finish() }
        btnResetDefaults?.setOnClickListener { showResetConfirmDialog() }

        buildLayout()
    }

    private fun getSections(): List<ToolbarSection> {
        return listOf(
            ToolbarSection(
                R.string.toolbar_section_file_operations,
                listOf(
                    ToolbarItem(R.drawable.ic_create_new, R.string.cd_create_new, R.string.toolbar_desc_create_new, ToolbarIconsPreferenceManager.KEY_CREATE_NEW, "toolbar_create_new"),
                    ToolbarItem(R.drawable.ic_copy, R.string.action_copy, R.string.toolbar_desc_copy, ToolbarIconsPreferenceManager.KEY_COPY, "toolbar_copy"),
                    ToolbarItem(R.drawable.ic_move, R.string.action_move, R.string.toolbar_desc_move, ToolbarIconsPreferenceManager.KEY_MOVE, "toolbar_move"),
                    ToolbarItem(R.drawable.ic_rename, R.string.action_rename, R.string.toolbar_desc_rename, ToolbarIconsPreferenceManager.KEY_RENAME, "toolbar_rename"),
                    ToolbarItem(R.drawable.ic_share, R.string.action_share, R.string.toolbar_desc_share, ToolbarIconsPreferenceManager.KEY_SHARE, "toolbar_share"),
                    ToolbarItem(R.drawable.ic_delete, R.string.action_delete, R.string.toolbar_desc_delete, ToolbarIconsPreferenceManager.KEY_DELETE, "toolbar_delete")
                )
            ),
            ToolbarSection(
                R.string.toolbar_section_security,
                listOf(
                    ToolbarItem(R.drawable.ic_copy_encrypt, R.string.action_copy_encrypt, R.string.toolbar_desc_copy_encrypt, ToolbarIconsPreferenceManager.KEY_COPY_ENCRYPT, "toolbar_copy_encrypt"),
                    ToolbarItem(R.drawable.ic_move_encrypt, R.string.action_move_encrypt, R.string.toolbar_desc_move_encrypt, ToolbarIconsPreferenceManager.KEY_MOVE_ENCRYPT, "toolbar_move_encrypt"),
                    ToolbarItem(R.drawable.ic_shield_protected, R.string.protect, R.string.toolbar_desc_protect, ToolbarIconsPreferenceManager.KEY_PROTECT, "toolbar_protect"),
                    ToolbarItem(R.drawable.ic_shield_unprotected, R.string.unprotect, R.string.toolbar_desc_unprotect, ToolbarIconsPreferenceManager.KEY_UNPROTECT, "toolbar_unprotect"),
                    ToolbarItem(R.drawable.ic_eye_off, R.string.hide, R.string.toolbar_desc_hide, ToolbarIconsPreferenceManager.KEY_HIDE, "toolbar_hide"),
                    ToolbarItem(R.drawable.ic_eye, R.string.unhide, R.string.toolbar_desc_unhide, ToolbarIconsPreferenceManager.KEY_UNHIDE, "toolbar_unhide")
                )
            ),
            ToolbarSection(
                R.string.toolbar_section_selection_navigation,
                listOf(
                    ToolbarItem(R.drawable.ic_check, R.string.action_select_all, R.string.toolbar_desc_select_all, ToolbarIconsPreferenceManager.KEY_SELECT_ALL, "toolbar_select_all"),
                    ToolbarItem(R.drawable.ic_invert_selection, R.string.action_invert_selection, R.string.toolbar_desc_invert_selection, ToolbarIconsPreferenceManager.KEY_INVERT_SELECTION, "toolbar_invert_selection", mobileOnly = true),
                    ToolbarItem(R.drawable.ic_star, R.string.action_favorite, R.string.toolbar_desc_favorite, ToolbarIconsPreferenceManager.KEY_FAVORITE, "toolbar_favorite"),
                    ToolbarItem(R.drawable.ic_paperclip, R.string.pin, R.string.toolbar_desc_pin, ToolbarIconsPreferenceManager.KEY_PIN, "toolbar_pin"),
                    ToolbarItem(R.drawable.ic_paperclip_off, R.string.unpin, R.string.toolbar_desc_unpin, ToolbarIconsPreferenceManager.KEY_UNPIN, "toolbar_unpin")
                )
            ),
            ToolbarSection(
                R.string.toolbar_section_tools_media,
                listOf(
                    ToolbarItem(R.drawable.ic_compress, R.string.action_compress, R.string.toolbar_desc_compress, ToolbarIconsPreferenceManager.KEY_COMPRESS, "toolbar_compress"),
                    ToolbarItem(R.drawable.ic_extract, R.string.action_extract_here, R.string.toolbar_desc_extract, ToolbarIconsPreferenceManager.KEY_EXTRACT),
                    ToolbarItem(R.drawable.ic_compress_image, R.string.action_compress_image, R.string.toolbar_desc_compress_image, ToolbarIconsPreferenceManager.KEY_IMAGE_COMPRESS, "toolbar_image_compress"),
                    ToolbarItem(R.drawable.ic_photo_video, R.string.action_retrigger_thumbnails, R.string.toolbar_desc_retrigger_thumbnails, ToolbarIconsPreferenceManager.KEY_RETRIGGER_THUMBNAILS, "toolbar_retrigger_thumbnails"),
                    ToolbarItem(R.drawable.ic_gif, R.string.action_create_gif, R.string.toolbar_desc_create_gif, ToolbarIconsPreferenceManager.KEY_CREATE_GIF, "toolbar_create_gif"),
                    ToolbarItem(R.drawable.ic_exif_cleaner, R.string.action_exif_cleaner_renamer, R.string.toolbar_desc_exif_tools, ToolbarIconsPreferenceManager.KEY_EXIF_TOOLS, "toolbar_exif_cleaner", mobileOnly = true),
                    ToolbarItem(R.drawable.ic_wallpaper_home, R.string.action_set_home_wallpaper, R.string.toolbar_desc_set_home_wallpaper, ToolbarIconsPreferenceManager.KEY_SET_HOME_WALLPAPER, "toolbar_set_home_wallpaper"),
                    ToolbarItem(R.drawable.ic_wallpaper_lock, R.string.action_set_lock_wallpaper, R.string.toolbar_desc_set_lock_wallpaper, ToolbarIconsPreferenceManager.KEY_SET_LOCK_WALLPAPER, "toolbar_set_lock_wallpaper"),
                    ToolbarItem(R.drawable.ic_duplicate_finder, R.string.action_duplicate_finder, R.string.toolbar_desc_duplicate_finder, ToolbarIconsPreferenceManager.KEY_DUPLICATE_FINDER, "toolbar_duplicate_finder"),
                    ToolbarItem(R.drawable.ic_folder_large_files, R.string.action_large_files_finder, R.string.toolbar_desc_large_files_finder, ToolbarIconsPreferenceManager.KEY_LARGE_FILES_FINDER, "toolbar_large_files_finder")
                )
            )
        )
    }

    private data class QuickActionDef(
        val actionId: String,
        val nameResId: Int,
        val iconResId: Int,
        val customIconKey: String? = null
    )

    private fun getQuickActionDef(actionId: String): QuickActionDef {
        val pm = ToolbarIconsPreferenceManager
        return when (actionId) {
            pm.ACTION_DELETE -> QuickActionDef(pm.ACTION_DELETE, R.string.action_delete, R.drawable.ic_delete, "toolbar_delete")
            pm.ACTION_COMPRESS -> QuickActionDef(pm.ACTION_COMPRESS, R.string.action_compress, R.drawable.ic_compress, "toolbar_compress")
            pm.ACTION_MOVE -> QuickActionDef(pm.ACTION_MOVE, R.string.action_move, R.drawable.ic_move, "toolbar_move")
            pm.ACTION_COPY -> QuickActionDef(pm.ACTION_COPY, R.string.action_copy, R.drawable.ic_copy, "toolbar_copy")
            pm.ACTION_RENAME -> QuickActionDef(pm.ACTION_RENAME, R.string.action_rename, R.drawable.ic_edit, "toolbar_rename")
            pm.ACTION_SHARE -> QuickActionDef(pm.ACTION_SHARE, R.string.action_share, R.drawable.ic_share, "toolbar_share")
            pm.ACTION_PROTECT_UNPROTECT -> QuickActionDef(pm.ACTION_PROTECT_UNPROTECT, R.string.quick_bar_action_protect_unprotect, R.drawable.ic_shield_protected, "toolbar_protect")
            pm.ACTION_HIDE_UNHIDE -> QuickActionDef(pm.ACTION_HIDE_UNHIDE, R.string.quick_bar_action_hide_unhide, R.drawable.ic_eye_off, "toolbar_hide")
            pm.ACTION_PIN_UNPIN -> QuickActionDef(pm.ACTION_PIN_UNPIN, R.string.quick_bar_action_pin_unpin, R.drawable.ic_paperclip, "toolbar_pin")
            pm.ACTION_FAVORITE -> QuickActionDef(pm.ACTION_FAVORITE, R.string.action_favorite, R.drawable.ic_star, "toolbar_favorite")
            pm.ACTION_SELECT_ALL -> QuickActionDef(pm.ACTION_SELECT_ALL, R.string.action_select_all, R.drawable.ic_select_all, "toolbar_select_all")
            pm.ACTION_INVERT_SELECTION -> QuickActionDef(pm.ACTION_INVERT_SELECTION, R.string.action_invert_selection, R.drawable.ic_invert_selection, "toolbar_invert_selection")
            pm.ACTION_EXTRACT -> QuickActionDef(pm.ACTION_EXTRACT, R.string.action_extract_here, R.drawable.ic_extract)
            pm.ACTION_IMAGE_COMPRESS -> QuickActionDef(pm.ACTION_IMAGE_COMPRESS, R.string.action_compress_image, R.drawable.ic_compress_image, "toolbar_image_compress")
            pm.ACTION_CREATE_GIF -> QuickActionDef(pm.ACTION_CREATE_GIF, R.string.action_create_gif, R.drawable.ic_gif, "toolbar_create_gif")
            pm.ACTION_EXIF_TOOLS -> QuickActionDef(pm.ACTION_EXIF_TOOLS, R.string.action_exif_cleaner_renamer, R.drawable.ic_exif_cleaner, "toolbar_exif_cleaner")
            pm.ACTION_SET_HOME_WALLPAPER -> QuickActionDef(pm.ACTION_SET_HOME_WALLPAPER, R.string.action_set_home_wallpaper, R.drawable.ic_wallpaper_home, "toolbar_set_home_wallpaper")
            pm.ACTION_SET_LOCK_WALLPAPER -> QuickActionDef(pm.ACTION_SET_LOCK_WALLPAPER, R.string.action_set_lock_wallpaper, R.drawable.ic_wallpaper_lock, "toolbar_set_lock_wallpaper")
            pm.ACTION_DUPLICATE_FINDER -> QuickActionDef(pm.ACTION_DUPLICATE_FINDER, R.string.action_duplicate_finder, R.drawable.ic_duplicate_finder, "toolbar_duplicate_finder")
            pm.ACTION_LARGE_FILES_FINDER -> QuickActionDef(pm.ACTION_LARGE_FILES_FINDER, R.string.action_large_files_finder, R.drawable.ic_folder_large_files, "toolbar_large_files_finder")
            pm.ACTION_CREATE_NEW -> QuickActionDef(pm.ACTION_CREATE_NEW, R.string.cd_create_new, R.drawable.ic_create_new, "toolbar_create_new")
            pm.ACTION_RETRIGGER_THUMBNAILS -> QuickActionDef(pm.ACTION_RETRIGGER_THUMBNAILS, R.string.action_retrigger_thumbnails, R.drawable.ic_photo_video, "toolbar_retrigger_thumbnails")
            pm.ACTION_COPY_ENCRYPT -> QuickActionDef(pm.ACTION_COPY_ENCRYPT, R.string.action_copy_encrypt, R.drawable.ic_copy_encrypt, "toolbar_copy_encrypt")
            pm.ACTION_MOVE_ENCRYPT -> QuickActionDef(pm.ACTION_MOVE_ENCRYPT, R.string.action_move_encrypt, R.drawable.ic_move_encrypt, "toolbar_move_encrypt")
            pm.ACTION_MORE -> QuickActionDef(pm.ACTION_MORE, R.string.quick_bar_action_more, R.drawable.ic_arrow_forward)
            else -> QuickActionDef(actionId, R.string.quick_bar_action_more, R.drawable.ic_more)
        }
    }

    private fun mapPrefKeyToQuickAction(prefKey: String): String {
        val pm = ToolbarIconsPreferenceManager
        return when (prefKey) {
            pm.KEY_CREATE_NEW -> pm.ACTION_CREATE_NEW
            pm.KEY_COPY -> pm.ACTION_COPY
            pm.KEY_MOVE -> pm.ACTION_MOVE
            pm.KEY_RENAME -> pm.ACTION_RENAME
            pm.KEY_SHARE -> pm.ACTION_SHARE
            pm.KEY_DELETE -> pm.ACTION_DELETE
            pm.KEY_COPY_ENCRYPT -> pm.ACTION_COPY_ENCRYPT
            pm.KEY_MOVE_ENCRYPT -> pm.ACTION_MOVE_ENCRYPT
            pm.KEY_PROTECT, pm.KEY_UNPROTECT -> pm.ACTION_PROTECT_UNPROTECT
            pm.KEY_HIDE, pm.KEY_UNHIDE -> pm.ACTION_HIDE_UNHIDE
            pm.KEY_PIN, pm.KEY_UNPIN -> pm.ACTION_PIN_UNPIN
            pm.KEY_SELECT_ALL -> pm.ACTION_SELECT_ALL
            pm.KEY_INVERT_SELECTION -> pm.ACTION_INVERT_SELECTION
            pm.KEY_FAVORITE -> pm.ACTION_FAVORITE
            pm.KEY_COMPRESS -> pm.ACTION_COMPRESS
            pm.KEY_EXTRACT -> pm.ACTION_EXTRACT
            pm.KEY_IMAGE_COMPRESS -> pm.ACTION_IMAGE_COMPRESS
            pm.KEY_RETRIGGER_THUMBNAILS -> pm.ACTION_RETRIGGER_THUMBNAILS
            pm.KEY_CREATE_GIF -> pm.ACTION_CREATE_GIF
            pm.KEY_EXIF_TOOLS -> pm.ACTION_EXIF_TOOLS
            pm.KEY_SET_HOME_WALLPAPER -> pm.ACTION_SET_HOME_WALLPAPER
            pm.KEY_SET_LOCK_WALLPAPER -> pm.ACTION_SET_LOCK_WALLPAPER
            pm.KEY_DUPLICATE_FINDER -> pm.ACTION_DUPLICATE_FINDER
            pm.KEY_LARGE_FILES_FINDER -> pm.ACTION_LARGE_FILES_FINDER
            else -> prefKey
        }
    }

    private fun buildLayout() {
        val count = contentLayout.childCount
        if (count > 1) {
            contentLayout.removeViews(1, count - 1)
        }

        if (isTv) {
            buildTvLayout()
        } else {
            buildMobileLayout()
        }
    }

    private fun buildMobileLayout() {
        val inflater = LayoutInflater.from(this)
        val pm = ToolbarIconsPreferenceManager

        // ── 1. Master Floating Quick Bar Toggle Card ─────────────────────────
        val masterCard = createGlassCard()
        val masterRow = inflater.inflate(R.layout.item_toolbar_icon_row, masterCard, false)
        val imgMasterIcon = masterRow.findViewById<ImageView>(R.id.imgIcon)
        val txtMasterName = masterRow.findViewById<TextView>(R.id.txtName)
        val txtMasterSubtitle = masterRow.findViewById<TextView>(R.id.txtSubtitle)
        val switchMaster = masterRow.findViewById<SwitchMaterial>(R.id.switchToggle)
        val btnMasterQuickToggle = masterRow.findViewById<View>(R.id.btnQuickBarToggle)

        btnMasterQuickToggle.visibility = View.GONE
        imgMasterIcon.setImageResource(R.drawable.ic_long_press)
        txtMasterName.setText(R.string.quick_bar_title)
        txtMasterSubtitle.setText(R.string.quick_bar_subtitle)

        val isQuickBarOn = pm.isQuickBarEnabled(this)
        switchMaster.isChecked = isQuickBarOn

        masterCard.addView(masterRow)
        contentLayout.addView(masterCard)

        // ── 2. Active Quick Bar Slots & Reorder Card ────────────────────────
        val activeSlotsCard = createGlassCard()
        val activeSlotsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val density = resources.displayMetrics.density
            setPadding((14 * density).toInt(), (14 * density).toInt(), (14 * density).toInt(), (14 * density).toInt())
        }

        val txtSlotsTitle = TextView(this).apply {
            setText(R.string.quick_bar_active_slots_title)
            setTextColor(getColor(R.color.mobile_card_text_primary))
            textSize = 15.5f
            typeface = Typeface.create("sans-serif-bold", Typeface.NORMAL)
        }
        val txtSlotsSubtitle = TextView(this).apply {
            setText(R.string.quick_bar_active_slots_subtitle)
            setTextColor(getColor(R.color.mobile_text_secondary))
            textSize = 12f
            val density = resources.displayMetrics.density
            setPadding(0, (2 * density).toInt(), 0, (10 * density).toInt())
        }

        val rvReorder = androidx.recyclerview.widget.RecyclerView(this).apply {
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this@ToolbarIconsActivity)
            isNestedScrollingEnabled = false
        }

        val currentQuickItems = pm.getQuickBarItems(this).toMutableList()
        val quickDefs = currentQuickItems.map { getQuickActionDef(it) }.toMutableList()

        var reorderAdapter: QuickBarReorderAdapter? = null
        val itemTouchHelper = androidx.recyclerview.widget.ItemTouchHelper(object : androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(
            androidx.recyclerview.widget.ItemTouchHelper.UP or androidx.recyclerview.widget.ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                recyclerView: androidx.recyclerview.widget.RecyclerView,
                viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder,
                target: androidx.recyclerview.widget.RecyclerView.ViewHolder
            ): Boolean {
                val fromPos = viewHolder.bindingAdapterPosition
                val toPos = target.bindingAdapterPosition
                if (fromPos == androidx.recyclerview.widget.RecyclerView.NO_POSITION || toPos == androidx.recyclerview.widget.RecyclerView.NO_POSITION) return false

                java.util.Collections.swap(quickDefs, fromPos, toPos)
                reorderAdapter?.notifyItemMoved(fromPos, toPos)
                val min = minOf(fromPos, toPos)
                val count = kotlin.math.abs(fromPos - toPos) + 1
                reorderAdapter?.notifyItemRangeChanged(min, count)

                pm.setQuickBarItems(this@ToolbarIconsActivity, quickDefs.map { it.actionId })
                refreshCategoryQuickBarChips()
                return true
            }

            override fun onSwiped(viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder, direction: Int) {}
        })
        itemTouchHelper.attachToRecyclerView(rvReorder)

        reorderAdapter = QuickBarReorderAdapter(
            quickDefs,
            onRemove = { pos ->
                if (pos in quickDefs.indices) {
                    quickDefs.removeAt(pos)
                    reorderAdapter?.notifyItemRemoved(pos)
                    reorderAdapter?.notifyItemRangeChanged(pos, quickDefs.size - pos)
                    pm.setQuickBarItems(this@ToolbarIconsActivity, quickDefs.map { it.actionId })
                    refreshCategoryQuickBarChips()
                }
            },
            onStartDrag = { holder ->
                itemTouchHelper.startDrag(holder)
            }
        )
        rvReorder.adapter = reorderAdapter

        activeSlotsContainer.addView(txtSlotsTitle)
        activeSlotsContainer.addView(txtSlotsSubtitle)
        activeSlotsContainer.addView(rvReorder)
        activeSlotsCard.addView(activeSlotsContainer)

        contentLayout.addView(activeSlotsCard)
        activeSlotsCard.visibility = if (isQuickBarOn) View.VISIBLE else View.GONE

        switchMaster.setOnCheckedChangeListener { _, isChecked ->
            pm.setQuickBarEnabled(this, isChecked)
            activeSlotsCard.visibility = if (isChecked) View.VISIBLE else View.GONE
        }
        masterRow.setOnClickListener { switchMaster.isChecked = !switchMaster.isChecked }

        // ── 3. Categorized Action Sections ───────────────────────────────────
        val sections = getSections()

        for (section in sections) {
            val visibleItems = section.items.filter { !it.mobileOnly || !isTv }
            if (visibleItems.isEmpty()) continue

            contentLayout.addView(createSectionHeader(section.titleResId))
            val glassCard = createGlassCard()
            val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

            visibleItems.forEachIndexed { index, item ->
                val row = inflater.inflate(R.layout.item_toolbar_icon_row, container, false)
                val imgIcon = row.findViewById<ImageView>(R.id.imgIcon)
                val txtName = row.findViewById<TextView>(R.id.txtName)
                val txtSubtitle = row.findViewById<TextView>(R.id.txtSubtitle)
                val switchToggle = row.findViewById<SwitchMaterial>(R.id.switchToggle)
                val btnQuickBarToggle = row.findViewById<View>(R.id.btnQuickBarToggle)
                val txtQuickBarStatus = row.findViewById<TextView>(R.id.txtQuickBarStatus)

                imgIcon.setImageResource(item.iconResId)
                if (item.customIconKey != null) {
                    IconCustomizationManager.applyToView(this, imgIcon, item.customIconKey, item.iconResId)
                }

                txtName.setText(item.nameResId)
                txtSubtitle.setText(item.descResId)

                val isEnabled = pm.isIconEnabled(this, item.prefKey)
                switchToggle.isChecked = isEnabled

                val targetActionId = mapPrefKeyToQuickAction(item.prefKey)

                switchToggle.setOnCheckedChangeListener { _, isChecked ->
                    pm.setIconEnabled(this, item.prefKey, isChecked)
                    if (!isChecked) {
                        val shouldRemove = when (targetActionId) {
                            pm.ACTION_PROTECT_UNPROTECT -> !pm.isIconEnabled(this, pm.KEY_PROTECT) && !pm.isIconEnabled(this, pm.KEY_UNPROTECT)
                            pm.ACTION_HIDE_UNHIDE -> !pm.isIconEnabled(this, pm.KEY_HIDE) && !pm.isIconEnabled(this, pm.KEY_UNHIDE)
                            pm.ACTION_PIN_UNPIN -> !pm.isIconEnabled(this, pm.KEY_PIN) && !pm.isIconEnabled(this, pm.KEY_UNPIN)
                            else -> true
                        }
                        if (shouldRemove) {
                            val activeItems = pm.getQuickBarItems(this).toMutableList()
                            val idx = activeItems.indexOf(targetActionId)
                            if (idx >= 0) {
                                activeItems.removeAt(idx)
                                pm.setQuickBarItems(this, activeItems)
                                quickDefs.clear()
                                quickDefs.addAll(activeItems.map { getQuickActionDef(it) })
                                reorderAdapter?.notifyDataSetChanged()
                                refreshCategoryQuickBarChips()
                            }
                        }
                    }
                }

                val updateChipStatus = {
                    val activeItems = pm.getQuickBarItems(this)
                    val idx = activeItems.indexOf(targetActionId)
                    if (idx >= 0) {
                        txtQuickBarStatus.text = getString(R.string.quick_bar_in_quick_bar, idx + 1)
                        btnQuickBarToggle.background = getDrawable(R.drawable.bg_chip_selected)
                        txtQuickBarStatus.setTextColor(getColor(R.color.black))
                    } else {
                        txtQuickBarStatus.setText(R.string.quick_bar_add_to_quick_bar)
                        btnQuickBarToggle.background = getDrawable(R.drawable.bg_btn_icon_frosted)
                        txtQuickBarStatus.setTextColor(getColor(R.color.mobile_text_secondary))
                    }
                }
                updateChipStatus()
                row.setTag(R.id.btnQuickBarToggle, updateChipStatus)

                btnQuickBarToggle.setOnClickListener {
                    val activeItems = pm.getQuickBarItems(this).toMutableList()
                    val idx = activeItems.indexOf(targetActionId)
                    if (idx >= 0) {
                        activeItems.removeAt(idx)
                        pm.setQuickBarItems(this, activeItems)
                        quickDefs.clear()
                        quickDefs.addAll(activeItems.map { getQuickActionDef(it) })
                        reorderAdapter?.notifyDataSetChanged()
                        refreshCategoryQuickBarChips()
                    } else {
                        if (activeItems.size >= ToolbarIconsPreferenceManager.MAX_QUICK_BAR_ITEMS) {
                            Toast.makeText(this, R.string.quick_bar_max_limit_reached, Toast.LENGTH_SHORT).show()
                        } else {
                            if (!switchToggle.isChecked) {
                                switchToggle.isChecked = true
                                pm.setIconEnabled(this, item.prefKey, true)
                            }
                            activeItems.add(targetActionId)
                            pm.setQuickBarItems(this, activeItems)
                            quickDefs.clear()
                            quickDefs.addAll(activeItems.map { getQuickActionDef(it) })
                            reorderAdapter?.notifyDataSetChanged()
                            refreshCategoryQuickBarChips()
                        }
                    }
                }

                row.setOnClickListener { switchToggle.isChecked = !switchToggle.isChecked }

                container.addView(row)

                if (index < visibleItems.size - 1) {
                    container.addView(createDivider())
                }
            }

            glassCard.addView(container)
            contentLayout.addView(glassCard)
        }
    }

    private fun refreshCategoryQuickBarChips() {
        for (i in 0 until contentLayout.childCount) {
            val child = contentLayout.getChildAt(i)
            if (child is MaterialCardView) {
                val container = child.getChildAt(0)
                if (container is LinearLayout) {
                    for (j in 0 until container.childCount) {
                        val row = container.getChildAt(j)
                        val updater = row.getTag(R.id.btnQuickBarToggle) as? (() -> Unit)
                        updater?.invoke()
                    }
                }
            }
        }
    }

    private class QuickBarReorderAdapter(
        private val items: List<QuickActionDef>,
        private val onRemove: (position: Int) -> Unit,
        private val onStartDrag: (holder: androidx.recyclerview.widget.RecyclerView.ViewHolder) -> Unit
    ) : androidx.recyclerview.widget.RecyclerView.Adapter<QuickBarReorderAdapter.SlotViewHolder>() {

        inner class SlotViewHolder(view: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {
            val txtSlotPosition: TextView = view.findViewById(R.id.txtSlotPosition)
            val imgSlotIcon: ImageView = view.findViewById(R.id.imgSlotIcon)
            val txtSlotName: TextView = view.findViewById(R.id.txtSlotName)
            val btnRemoveSlot: View = view.findViewById(R.id.btnRemoveSlot)
            val btnDragHandle: View = view.findViewById(R.id.btnDragHandle)
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): SlotViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_quick_bar_reorder_slot, parent, false)
            return SlotViewHolder(view)
        }

        override fun onBindViewHolder(holder: SlotViewHolder, position: Int) {
            val item = items[position]
            val context = holder.itemView.context

            holder.txtSlotPosition.text = (position + 1).toString()
            holder.imgSlotIcon.setImageResource(item.iconResId)
            if (item.customIconKey != null) {
                IconCustomizationManager.applyToView(context, holder.imgSlotIcon, item.customIconKey, item.iconResId)
            }
            holder.txtSlotName.setText(item.nameResId)

            holder.btnRemoveSlot.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != androidx.recyclerview.widget.RecyclerView.NO_POSITION) {
                    onRemove(pos)
                }
            }

            holder.btnDragHandle.setOnTouchListener { _, event ->
                if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                    onStartDrag(holder)
                }
                false
            }
        }

        override fun getItemCount(): Int = items.size
    }


    private fun buildTvLayout() {
        val inflater = LayoutInflater.from(this)
        val sections = getSections()

        for (section in sections) {
            val visibleItems = section.items.filter { !it.mobileOnly }
            if (visibleItems.isEmpty()) continue

            contentLayout.addView(createSectionHeader(section.titleResId))

            for (item in visibleItems) {
                val card = inflater.inflate(R.layout.item_toolbar_icon_card_tv, contentLayout, false) as MaterialCardView
                val imgIcon = card.findViewById<ImageView>(R.id.imgIcon)
                val txtLabel = card.findViewById<TextView>(R.id.txtLabel)
                val txtSubtitle = card.findViewById<TextView>(R.id.txtSubtitle)
                val switchToggle = card.findViewById<SwitchMaterial>(R.id.switchToggle)

                imgIcon.setImageResource(item.iconResId)
                if (item.customIconKey != null) {
                    IconCustomizationManager.applyToView(this, imgIcon, item.customIconKey, item.iconResId)
                }

                txtLabel.setText(item.nameResId)
                txtSubtitle.setText(item.descResId)

                val isEnabled = ToolbarIconsPreferenceManager.isIconEnabled(this, item.prefKey)
                switchToggle.isChecked = isEnabled

                switchToggle.setOnCheckedChangeListener { _, isChecked ->
                    ToolbarIconsPreferenceManager.setIconEnabled(this, item.prefKey, isChecked)
                }
                card.setOnClickListener { switchToggle.isChecked = !switchToggle.isChecked }
                setupTvCardFocus(card)
                contentLayout.addView(card)
            }
        }
    }

    private fun setupTvCardFocus(card: MaterialCardView) {
        val yellowFill = getColor(R.color.tv_button_focused_yellow)
        val blackText = getColor(R.color.tv_button_focused_yellow_text)
        val glassColor = getColor(R.color.tv_glass_white_10)
        val primaryText = getColor(R.color.tv_text_primary)
        val secondText = getColor(R.color.tv_text_secondary)

        val txtLabel = card.findViewById<TextView>(R.id.txtLabel)
        val txtSubtitle = card.findViewById<TextView>(R.id.txtSubtitle)
        val imgIcon = card.findViewById<ImageView>(R.id.imgIcon)

        card.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                card.setCardBackgroundColor(yellowFill)
                txtLabel?.setTextColor(blackText)
                txtSubtitle?.setTextColor(blackText)
                imgIcon?.imageTintList = ColorStateList.valueOf(blackText)
            } else {
                card.setCardBackgroundColor(glassColor)
                txtLabel?.setTextColor(primaryText)
                txtSubtitle?.setTextColor(secondText)
                imgIcon?.imageTintList = ColorStateList.valueOf(getColor(R.color.tv_accent))
            }
        }
    }

    private fun createSectionHeader(titleRes: Int): TextView {
        return TextView(this).apply {
            setText(titleRes)
            setTextColor(ThemeColors.primary(this@ToolbarIconsActivity))
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

    private fun showResetConfirmDialog() {
        val layoutRes = if (isTv) {
            R.layout.dialog_toolbar_icons_reset_confirm_tv
        } else {
            R.layout.dialog_toolbar_icons_reset_confirm
        }

        val dialogView = LayoutInflater.from(this).inflate(layoutRes, null)
        val btnResetConfirm = dialogView.findViewById<View>(R.id.btnResetConfirm)
        val btnCancel = dialogView.findViewById<View>(R.id.btnCancel)

        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        btnResetConfirm.setOnClickListener {
            dialog.dismiss()
            ToolbarIconsPreferenceManager.resetToDefaults(this)
            Toast.makeText(this, R.string.settings_toolbar_icons_reset_toast, Toast.LENGTH_SHORT).show()
            buildLayout()
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
