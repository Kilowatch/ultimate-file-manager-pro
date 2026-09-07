package za.kilowatch.ultimatefilemanager.tabs

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.RecyclerView
import za.kilowatch.ultimatefilemanager.R
import kotlin.math.abs

class TabAdapter(
    private val tabs: MutableList<TabModel>,
    private var activeTabId: String?,
    private val onTabSelected: (tab: TabModel, position: Int) -> Unit,
    private val onTabClose: (tab: TabModel, position: Int) -> Unit,
    private val onTabDuplicate: (tab: TabModel, position: Int) -> Unit,
    private val onTabRenamed: (tab: TabModel, newName: String, position: Int) -> Unit,
    private val onAddTabClicked: () -> Unit,
    private val onStartDrag: (holder: RecyclerView.ViewHolder) -> Unit = {}
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val VIEW_TYPE_TAB = 0
        private const val VIEW_TYPE_ADD = 1
    }

    // Persisted tap timestamp & tab ID to survive ViewHolder re-binding on tap
    private var lastTappedTabId: String? = null
    private var lastTapTimestamp: Long = 0L

    override fun getItemCount(): Int = tabs.size + 1

    override fun getItemViewType(position: Int): Int {
        return if (position == tabs.size) VIEW_TYPE_ADD else VIEW_TYPE_TAB
    }

    fun setActiveTabId(id: String, recyclerView: RecyclerView? = null) {
        if (activeTabId == id) return
        val oldPos = tabs.indexOfFirst { it.id == activeTabId }
        val newPos = tabs.indexOfFirst { it.id == id }
        activeTabId = id
        val notifyChange = {
            if (oldPos >= 0 && oldPos < tabs.size) notifyItemChanged(oldPos)
            if (newPos >= 0 && newPos < tabs.size) notifyItemChanged(newPos)
        }
        if (recyclerView?.isComputingLayout == true) {
            recyclerView.post(notifyChange)
        } else {
            notifyChange()
        }
    }

    fun startEditingTab(pos: Int) {
        if (pos in tabs.indices) {
            tabs.forEachIndexed { idx, t ->
                if (t.isEditing && idx != pos) {
                    t.isEditing = false
                    notifyItemChanged(idx)
                }
            }
            tabs[pos].isEditing = true
            notifyItemChanged(pos)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_ADD) {
            val view = inflater.inflate(R.layout.item_tab_add, parent, false)
            AddTabViewHolder(view)
        } else {
            val view = inflater.inflate(R.layout.item_tab_pill, parent, false)
            TabViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is AddTabViewHolder) {
            holder.itemView.setOnClickListener { onAddTabClicked() }
        } else if (holder is TabViewHolder) {
            val tab = tabs[position]
            val isActive = tab.id == activeTabId
            holder.bind(tab, isActive, position)
        }
    }

    inner class AddTabViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)

    inner class TabViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val container: LinearLayout = itemView.findViewById(R.id.tabPillContainer)
        private val imgIcon: ImageView = itemView.findViewById(R.id.imgTabIcon)
        private val txtTitle: TextView = itemView.findViewById(R.id.txtTabTitle)
        private val edtTitle: EditText = itemView.findViewById(R.id.edtTabTitle)

        private var startX = 0f
        private var startY = 0f
        private var isDraggingVertical = false
        private var isDraggingHorizontal = false
        private var isItemDragging = false

        fun bind(tab: TabModel, isActive: Boolean, position: Int) {
            val context = itemView.context
            val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

            // Reset translation & scale in case view was recycled
            itemView.translationY = 0f
            itemView.scaleX = 1f
            itemView.scaleY = 1f
            itemView.alpha = 1f
            isDraggingVertical = false
            isDraggingHorizontal = false
            isItemDragging = false

            // Background & Styling: Google Chrome style
            if (isActive) {
                container.setBackgroundResource(R.drawable.bg_tab_pill_active)
                txtTitle.setTextColor(Color.WHITE)
                imgIcon.setColorFilter(Color.parseColor("#00E5FF"))
            } else {
                container.setBackgroundResource(R.drawable.bg_tab_pill_inactive)
                txtTitle.setTextColor(Color.parseColor("#94A3B8"))
                imgIcon.setColorFilter(Color.parseColor("#64748B"))
            }

            imgIcon.setImageResource(tab.getIconRes())

            // Editing State
            if (tab.isEditing) {
                txtTitle.visibility = View.GONE
                edtTitle.visibility = View.VISIBLE
                edtTitle.isFocusable = true
                edtTitle.isFocusableInTouchMode = true
                edtTitle.setText(tab.title)
                edtTitle.selectAll()

                container.setOnTouchListener(null)
                container.setOnClickListener(null)
                container.setOnLongClickListener(null)

                // Request focus & soft keyboard asynchronously with postDelayed
                edtTitle.postDelayed({
                    if (tab.isEditing && edtTitle.isAttachedToWindow) {
                        edtTitle.requestFocus()
                        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                        imm?.showSoftInput(edtTitle, InputMethodManager.SHOW_FORCED)
                        (context as? Activity)?.window?.let { window ->
                            WindowCompat.getInsetsController(window, edtTitle).show(WindowInsetsCompat.Type.ime())
                        }
                    }
                }, 150)

                val commitEdit = {
                    if (tab.isEditing) {
                        val newName = edtTitle.text.toString().trim()
                        val finalName = if (newName.isNotEmpty()) newName else tab.title
                        tab.title = finalName
                        tab.isCustomName = true
                        tab.isEditing = false

                        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                        imm?.hideSoftInputFromWindow(edtTitle.windowToken, 0)
                        (context as? Activity)?.window?.let { window ->
                            WindowCompat.getInsetsController(window, edtTitle).hide(WindowInsetsCompat.Type.ime())
                        }

                        val pos = bindingAdapterPosition
                        if (pos != RecyclerView.NO_POSITION) {
                            onTabRenamed(tab, finalName, pos)
                            itemView.post {
                                if (pos in tabs.indices) {
                                    notifyItemChanged(pos)
                                }
                            }
                        }
                    }
                }

                edtTitle.setOnEditorActionListener { _, actionId, event ->
                    if (actionId == EditorInfo.IME_ACTION_DONE ||
                        actionId == EditorInfo.IME_ACTION_GO ||
                        actionId == EditorInfo.IME_ACTION_NEXT ||
                        actionId == EditorInfo.IME_ACTION_UNSPECIFIED ||
                        (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER)
                    ) {
                        commitEdit()
                        true
                    } else false
                }

                edtTitle.setOnKeyListener { _, keyCode, event ->
                    if (keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP) {
                        commitEdit()
                        true
                    } else false
                }

                edtTitle.setOnFocusChangeListener { _, hasFocus ->
                    if (!hasFocus && tab.isEditing) {
                        commitEdit()
                    }
                }
            } else {
                edtTitle.setOnFocusChangeListener(null)
                edtTitle.setOnEditorActionListener(null)
                edtTitle.setOnKeyListener(null)
                txtTitle.visibility = View.VISIBLE
                edtTitle.visibility = View.GONE
                txtTitle.text = tab.title

                val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
                    override fun onDown(e: MotionEvent): Boolean = true

                    override fun onLongPress(e: MotionEvent) {
                        val pos = bindingAdapterPosition
                        if (pos != RecyclerView.NO_POSITION && !tab.isEditing) {
                            isItemDragging = true
                            isDraggingVertical = false
                            container.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            onStartDrag(this@TabViewHolder)
                        }
                    }
                })

                container.setOnTouchListener { _, event ->
                    if (tab.isEditing) return@setOnTouchListener false

                    gestureDetector.onTouchEvent(event)

                    if (isItemDragging) {
                        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                            isItemDragging = false
                        }
                        return@setOnTouchListener false
                    }

                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            startX = event.rawX
                            startY = event.rawY
                            isDraggingVertical = false
                            isDraggingHorizontal = false
                            isItemDragging = false
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = event.rawX - startX
                            val dy = event.rawY - startY

                            if (!isDraggingVertical && !isDraggingHorizontal) {
                                if (abs(dy) > touchSlop && abs(dy) > abs(dx) * 1.1f) {
                                    isDraggingVertical = true
                                    itemView.parent.requestDisallowInterceptTouchEvent(true)
                                } else if (abs(dx) > touchSlop) {
                                    isDraggingHorizontal = true
                                    itemView.parent.requestDisallowInterceptTouchEvent(false)
                                }
                            }

                            if (isDraggingVertical) {
                                itemView.translationY = dy
                                if (dy < 0) {
                                    // Swiping UP: progressive fade to close
                                    val progress = (abs(dy) / (itemView.height * 2.2f)).coerceIn(0f, 0.85f)
                                    itemView.alpha = 1f - progress
                                    itemView.scaleX = 1f
                                    itemView.scaleY = 1f
                                } else {
                                    // Swiping DOWN: slight scale to duplicate
                                    itemView.alpha = 1f
                                    val scale = 1f + (dy / 800f).coerceIn(0f, 0.08f)
                                    itemView.scaleX = scale
                                    itemView.scaleY = scale
                                }
                                true
                            } else {
                                false
                            }
                        }
                        MotionEvent.ACTION_UP -> {
                            if (isDraggingVertical) {
                                itemView.parent.requestDisallowInterceptTouchEvent(false)
                                val density = context.resources.displayMetrics.density
                                val closeThreshold = -36f * density
                                val dupThreshold = 36f * density

                                if (itemView.translationY < closeThreshold) {
                                    // Swiped Up: animate upwards and close
                                    itemView.animate()
                                        .translationY(-160f * density)
                                        .alpha(0f)
                                        .setDuration(180)
                                        .withEndAction {
                                            itemView.translationY = 0f
                                            itemView.alpha = 1f
                                            itemView.scaleX = 1f
                                            itemView.scaleY = 1f
                                            val pos = bindingAdapterPosition
                                            if (pos != RecyclerView.NO_POSITION) {
                                                onTabClose(tab, pos)
                                            }
                                        }.start()
                                } else if (itemView.translationY > dupThreshold) {
                                    // Swiped Down: animate bounce back and duplicate
                                    itemView.animate()
                                        .translationY(0f)
                                        .scaleX(1f)
                                        .scaleY(1f)
                                        .alpha(1f)
                                        .setDuration(200)
                                        .withEndAction {
                                            val pos = bindingAdapterPosition
                                            if (pos != RecyclerView.NO_POSITION) {
                                                onTabDuplicate(tab, pos)
                                            }
                                        }.start()
                                } else {
                                    // Reset back
                                    itemView.animate()
                                        .translationY(0f)
                                        .scaleX(1f)
                                        .scaleY(1f)
                                        .alpha(1f)
                                        .setDuration(150)
                                        .start()
                                }
                                true
                            } else {
                                // Tap gesture: Check for double-tap vs single-tap
                                val pos = bindingAdapterPosition
                                if (pos != RecyclerView.NO_POSITION && !tab.isEditing) {
                                    val now = android.os.SystemClock.uptimeMillis()
                                    val timeout = ViewConfiguration.getDoubleTapTimeout().toLong().coerceAtLeast(350L)

                                    if (lastTappedTabId == tab.id && (now - lastTapTimestamp) <= timeout) {
                                        // DOUBLE TAP CONFIRMED!
                                        lastTappedTabId = null
                                        lastTapTimestamp = 0L
                                        startEditingTab(pos)
                                    } else {
                                        // FIRST TAP: record timestamp and select tab
                                        lastTappedTabId = tab.id
                                        lastTapTimestamp = now
                                        if (tab.id != activeTabId) {
                                            onTabSelected(tab, pos)
                                        }
                                    }
                                }
                                true
                            }
                        }
                        MotionEvent.ACTION_CANCEL -> {
                            if (isDraggingVertical) {
                                itemView.parent.requestDisallowInterceptTouchEvent(false)
                                itemView.animate()
                                    .translationY(0f)
                                    .scaleX(1f)
                                    .scaleY(1f)
                                    .alpha(1f)
                                    .setDuration(150)
                                    .start()
                            }
                            false
                        }
                        else -> false
                    }
                }
            }
        }
    }
}
