package za.kilowatch.ultimatefilemanager.settings

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.storage.FloatingBarManager
import za.kilowatch.ultimatefilemanager.storage.StorageBrowserActivity
import za.kilowatch.ultimatefilemanager.storage.StorageItem
import za.kilowatch.ultimatefilemanager.storage.TileColorManager
import za.kilowatch.ultimatefilemanager.storage.TileIconManager
import za.kilowatch.ultimatefilemanager.storage.TileOrderManager
import java.io.File
import java.util.Collections

/**
 * Mobile management screen for the Floating Bottom Bar (Dock).
 *
 * Allows users to:
 * 1. Toggle master enable/disable state
 * 2. Reorder docked items via drag-and-drop
 * 3. Remove items from the floating bar (returns them to main menu)
 * 4. Add available main menu tiles to the floating bar (hides them from main menu)
 */
class FloatingBarManageActivity : AppCompatActivity() {

    private lateinit var switchFloatingBar: SwitchMaterial
    private lateinit var recyclerDocked: RecyclerView
    private lateinit var recyclerAvailable: RecyclerView
    private lateinit var cardEmptyDocked: MaterialCardView
    private lateinit var cardEmptyAvailable: MaterialCardView
    private lateinit var txtDockedCount: TextView
    private lateinit var txtAvailableCount: TextView

    private val dockedItems = mutableListOf<StorageItem>()
    private val availableItems = mutableListOf<StorageItem>()

    private lateinit var dockedAdapter: DockedItemsAdapter
    private lateinit var availableAdapter: AvailableItemsAdapter

    private var allTileColors: Map<String, za.kilowatch.ultimatefilemanager.storage.TileColorConfig> = emptyMap()
    private var allTileIcons: Map<String, String> = emptyMap()
    private var allTileIconRes: Map<String, Int> = emptyMap()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_floating_bar_manage)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        initViews()
        loadData()
    }

    private fun initViews() {
        findViewById<View>(R.id.btnBack).setOnClickListener {
            finish()
        }

        switchFloatingBar = findViewById(R.id.switchFloatingBar)
        recyclerDocked = findViewById(R.id.recyclerDocked)
        recyclerAvailable = findViewById(R.id.recyclerAvailable)
        cardEmptyDocked = findViewById(R.id.cardEmptyDocked)
        cardEmptyAvailable = findViewById(R.id.cardEmptyAvailable)
        txtDockedCount = findViewById(R.id.txtDockedCount)
        txtAvailableCount = findViewById(R.id.txtAvailableCount)

        switchFloatingBar.isChecked = FloatingBarManager.isEnabled(this)
        switchFloatingBar.setOnCheckedChangeListener { _, isChecked ->
            FloatingBarManager.setEnabled(this, isChecked)
        }

        findViewById<View>(R.id.cardMasterToggle).setOnClickListener {
            switchFloatingBar.isChecked = !switchFloatingBar.isChecked
        }

        // Setup Docked Recycler
        dockedAdapter = DockedItemsAdapter()
        recyclerDocked.layoutManager = LinearLayoutManager(this)
        recyclerDocked.adapter = dockedAdapter

        // ItemTouchHelper for drag & drop reordering
        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val fromPos = viewHolder.bindingAdapterPosition
                val toPos = target.bindingAdapterPosition
                if (fromPos == RecyclerView.NO_POSITION || toPos == RecyclerView.NO_POSITION) return false

                Collections.swap(dockedItems, fromPos, toPos)
                dockedAdapter.notifyItemMoved(fromPos, toPos)
                val min = minOf(fromPos, toPos)
                val count = kotlin.math.abs(fromPos - toPos) + 1
                dockedAdapter.notifyItemRangeChanged(min, count)

                FloatingBarManager.setItemIds(this@FloatingBarManageActivity, dockedItems.map { it.id })
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
        })
        touchHelper.attachToRecyclerView(recyclerDocked)
        dockedAdapter.itemTouchHelper = touchHelper

        // Setup Available Recycler
        availableAdapter = AvailableItemsAdapter()
        recyclerAvailable.layoutManager = LinearLayoutManager(this)
        recyclerAvailable.adapter = availableAdapter
    }

    private fun loadData() {
        allTileColors = TileColorManager.loadTileColors(this)
        allTileIcons = TileIconManager.getAllTileIcons(this)
        allTileIconRes = TileIconManager.getAllTileIconRes(this)

        val allKnown = StorageBrowserActivity.buildAllKnownTiles(this)
        val hiddenIds = TileOrderManager.loadHidden(this)
        val dockedIds = FloatingBarManager.getItemIds(this)

        val byId = allKnown.associateBy { it.id }

        dockedItems.clear()
        for (id in dockedIds) {
            val item = byId[id]
            if (item != null) {
                dockedItems.add(item)
            }
        }

        availableItems.clear()
        val dockedSet = dockedIds.toSet()
        for (item in allKnown) {
            // Must not be in docked bar and must not be hidden
            if (!dockedSet.contains(item.id) && !hiddenIds.contains(item.id)) {
                availableItems.add(item)
            }
        }

        dockedAdapter.notifyDataSetChanged()
        availableAdapter.notifyDataSetChanged()
        updateCountsAndEmptyStates()
    }

    private fun updateCountsAndEmptyStates() {
        txtDockedCount.text = dockedItems.size.toString()
        txtAvailableCount.text = availableItems.size.toString()

        cardEmptyDocked.visibility = if (dockedItems.isEmpty()) View.VISIBLE else View.GONE
        cardEmptyAvailable.visibility = if (availableItems.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun dockItem(item: StorageItem) {
        val availIndex = availableItems.indexOfFirst { it.id == item.id }
        if (availIndex >= 0) {
            availableItems.removeAt(availIndex)
            availableAdapter.notifyItemRemoved(availIndex)

            dockedItems.add(item)
            val insertIndex = dockedItems.size - 1
            dockedAdapter.notifyItemInserted(insertIndex)

            FloatingBarManager.addItem(this, item.id)
            updateCountsAndEmptyStates()
        }
    }

    private fun undockItem(item: StorageItem) {
        val dockIndex = dockedItems.indexOfFirst { it.id == item.id }
        if (dockIndex >= 0) {
            dockedItems.removeAt(dockIndex)
            dockedAdapter.notifyItemRemoved(dockIndex)
            dockedAdapter.notifyItemRangeChanged(dockIndex, dockedItems.size - dockIndex)

            availableItems.add(0, item)
            availableAdapter.notifyItemInserted(0)

            FloatingBarManager.removeItem(this, item.id)
            updateCountsAndEmptyStates()
        }
    }

    private fun bindTileIcon(
        tileId: String,
        defaultIconRes: Int,
        imgView: ImageView,
        container: FrameLayout? = null
    ) {
        val customPath = allTileIcons[tileId]
        val customRes = allTileIconRes[tileId]

        if (!customPath.isNullOrEmpty() && File(customPath).exists()) {
            try {
                val bmp = BitmapFactory.decodeFile(customPath)
                if (bmp != null) {
                    imgView.setImageBitmap(bmp)
                    imgView.colorFilter = null
                    return
                }
            } catch (_: Exception) {}
        }

        val iconRes = if (customRes != null && customRes != 0) customRes else defaultIconRes
        imgView.setImageResource(iconRes)

        val config = allTileColors[tileId]
        if (config != null && config.iconColor != 0) {
            imgView.colorFilter = PorterDuffColorFilter(config.iconColor, PorterDuff.Mode.SRC_IN)
        } else {
            imgView.setColorFilter(
                ContextCompat.getColor(this, R.color.mobile_icon_tint),
                PorterDuff.Mode.SRC_IN
            )
        }
    }

    // ── Adapters ────────────────────────────────────────────────────────────

    inner class DockedItemsAdapter : RecyclerView.Adapter<DockedItemsAdapter.ViewHolder>() {
        var itemTouchHelper: ItemTouchHelper? = null

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val txtPosition: TextView = view.findViewById(R.id.txtTilePosition)
            val imgIcon: ImageView = view.findViewById(R.id.imgTileIcon)
            val containerIcon: FrameLayout = view.findViewById(R.id.containerTileIcon)
            val txtTitle: TextView = view.findViewById(R.id.txtTileTitle)
            val txtSubtitle: TextView = view.findViewById(R.id.txtTileSubtitle)
            val btnRemove: View = view.findViewById(R.id.btnRemoveTile)
            val btnDrag: View = view.findViewById(R.id.btnDragHandle)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_floating_dock_reorder, parent, false)
            return ViewHolder(v)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = dockedItems[position]
            holder.txtPosition.text = (position + 1).toString()
            holder.txtTitle.text = item.label

            if (!item.subtitle.isNullOrEmpty()) {
                holder.txtSubtitle.text = item.subtitle
                holder.txtSubtitle.visibility = View.VISIBLE
            } else {
                holder.txtSubtitle.visibility = View.GONE
            }

            bindTileIcon(item.id, item.iconRes, holder.imgIcon, holder.containerIcon)

            holder.btnRemove.setOnClickListener {
                undockItem(item)
            }

            holder.btnDrag.setOnTouchListener { _, event ->
                if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                    itemTouchHelper?.startDrag(holder)
                }
                false
            }
        }

        override fun getItemCount(): Int = dockedItems.size
    }

    inner class AvailableItemsAdapter : RecyclerView.Adapter<AvailableItemsAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val imgIcon: ImageView = view.findViewById(R.id.imgTileIcon)
            val containerIcon: FrameLayout = view.findViewById(R.id.containerTileIcon)
            val txtTitle: TextView = view.findViewById(R.id.txtTileTitle)
            val txtSubtitle: TextView = view.findViewById(R.id.txtTileSubtitle)
            val btnAdd: MaterialButton = view.findViewById(R.id.btnAddTile)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_floating_dock_available, parent, false)
            return ViewHolder(v)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = availableItems[position]
            holder.txtTitle.text = item.label

            if (!item.subtitle.isNullOrEmpty()) {
                holder.txtSubtitle.text = item.subtitle
                holder.txtSubtitle.visibility = View.VISIBLE
            } else {
                holder.txtSubtitle.visibility = View.GONE
            }

            bindTileIcon(item.id, item.iconRes, holder.imgIcon, holder.containerIcon)

            holder.btnAdd.setOnClickListener {
                dockItem(item)
            }
            holder.itemView.setOnClickListener {
                dockItem(item)
            }
        }

        override fun getItemCount(): Int = availableItems.size
    }
}
