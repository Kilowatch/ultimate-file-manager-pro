package za.kilowatch.ultimatefilemanager.storage

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.settings.FloatingBarManageActivity
import za.kilowatch.ultimatefilemanager.settings.SettingsActivity
import za.kilowatch.ultimatefilemanager.settings.ThemeHelper
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import java.util.WeakHashMap
import kotlin.math.abs

/**
 * Attaches and controls the global Edge Swipe Menu overlay across mobile activities.
 */
class EdgeMenuOverlayController(private val activity: Activity) {

    companion object {
        private val controllers = WeakHashMap<Activity, EdgeMenuOverlayController>()

        fun onActivityResumed(activity: Activity) {
            if (DeviceUtils.isTvDevice(activity)) return
            if (activity.isFinishing) return

            // Exclude welcome/security gate activities
            val name = activity.javaClass.simpleName
            if (name.contains("SecurityUnlock") || name.contains("Welcome") || name.contains("Splash")) {
                return
            }

            if (QuickAccessManager.getMode(activity) == QuickAccessManager.MODE_EDGE_MENU) {
                val controller = controllers.getOrPut(activity) { EdgeMenuOverlayController(activity) }
                controller.attach()
            } else {
                controllers.remove(activity)?.detach()
            }
        }

        fun onActivityPaused(activity: Activity) {
            controllers[activity]?.closeMenu(animate = false)
        }

        fun onActivityDestroyed(activity: Activity) {
            controllers.remove(activity)?.detach()
        }
    }

    private var rootView: EdgeMenuRootLayout? = null
    private var cardDrawer: MaterialCardView? = null
    private var viewScrim: View? = null
    private var viewHandle: View? = null
    private var viewHandlePill: View? = null
    private var rvItems: RecyclerView? = null
    private var layoutEmpty: View? = null
    private var adapter: EdgeMenuAdapter? = null

    private var isOpen = false
    private var isAnimating = false
    private var isLeftEdge = true
    private var drawerWidthPx = 0

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (isOpen) {
                closeMenu(animate = true)
            }
        }
    }

    fun attach() {
        if (DeviceUtils.isTvDevice(activity)) return
        if (QuickAccessManager.getMode(activity) != QuickAccessManager.MODE_EDGE_MENU) {
            detach()
            return
        }

        val decorView = activity.window?.decorView as? ViewGroup ?: return
        installWindowCallback()

        val existing = decorView.findViewById<EdgeMenuRootLayout?>(R.id.rootEdgeMenu)
        if (existing != null) {
            rootView = existing
            cardDrawer = existing.findViewById(R.id.cardEdgeDrawer)
            viewScrim = existing.findViewById(R.id.viewEdgeScrim)
            viewHandle = existing.findViewById(R.id.viewEdgeHandle)
            viewHandlePill = existing.findViewById(R.id.viewEdgeHandlePill)
            rvItems = existing.findViewById(R.id.rvEdgeMenuItems)
            layoutEmpty = existing.findViewById(R.id.layoutEmptyEdgeMenu)
            refreshConfiguration()
            return
        }

        val inflater = LayoutInflater.from(activity)
        val overlay = inflater.inflate(R.layout.layout_edge_menu_drawer, decorView, false) as EdgeMenuRootLayout
        decorView.addView(overlay, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        rootView = overlay
        cardDrawer = overlay.findViewById(R.id.cardEdgeDrawer)
        viewScrim = overlay.findViewById(R.id.viewEdgeScrim)
        viewHandle = overlay.findViewById(R.id.viewEdgeHandle)
        viewHandlePill = overlay.findViewById(R.id.viewEdgeHandlePill)
        rvItems = overlay.findViewById(R.id.rvEdgeMenuItems)
        layoutEmpty = overlay.findViewById(R.id.layoutEmptyEdgeMenu)

        // Apply system window insets so header is below status bar and footer is above nav bar
        val inner = overlay.findViewById<View>(R.id.layoutDrawerInner)
        ViewCompat.setOnApplyWindowInsetsListener(overlay) { _, insets ->
            val sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            inner?.setPadding(0, sysBars.top, 0, sysBars.bottom)
            insets
        }
        applyWindowInsetsToDrawer()
        ViewCompat.requestApplyInsets(overlay)

        // RecyclerView setup
        rvItems?.layoutManager = LinearLayoutManager(activity)
        adapter = EdgeMenuAdapter { item ->
            closeMenu(animate = true)
            StorageTileActionHandler.launchTile(activity, item)
        }
        rvItems?.adapter = adapter

        // Button clicks
        overlay.findViewById<View?>(R.id.btnCloseEdgeMenu)?.setOnClickListener {
            closeMenu(animate = true)
        }
        viewScrim?.setOnClickListener {
            closeMenu(animate = true)
        }
        overlay.findViewById<View?>(R.id.btnManageTiles)?.setOnClickListener {
            closeMenu(animate = false)
            activity.startActivity(Intent(activity, FloatingBarManageActivity::class.java))
        }
        overlay.findViewById<View?>(R.id.btnEdgeSettings)?.setOnClickListener {
            closeMenu(animate = false)
            activity.startActivity(Intent(activity, SettingsActivity::class.java))
        }

        // Handle Back button
        if (activity is ComponentActivity) {
            activity.onBackPressedDispatcher.addCallback(activity, backCallback)
        }

        refreshConfiguration()
    }

    fun detach() {
        uninstallWindowCallback()
        val decorView = activity.window?.decorView as? ViewGroup
        rootView?.let { decorView?.removeView(it) }
        rootView = null
        cardDrawer = null
        viewScrim = null
        viewHandle = null
        viewHandlePill = null
        rvItems = null
        layoutEmpty = null
        adapter = null
        backCallback.remove()
        isOpen = false
    }

    fun refreshConfiguration() {
        val root = rootView ?: return
        val card = cardDrawer ?: return
        isLeftEdge = QuickAccessManager.getEdgePosition(activity) == QuickAccessManager.EDGE_LEFT
        root.isLeftEdge = isLeftEdge
        root.isMenuOpen = isOpen

        val density = activity.resources.displayMetrics.density
        drawerWidthPx = (300 * density).toInt()
        root.edgeThresholdPx = 28f * density

        // 1. Layout Gravity & Corner Radii
        val cardParams = card.layoutParams as? FrameLayout.LayoutParams
        cardParams?.gravity = if (isLeftEdge) Gravity.START else Gravity.END
        cardParams?.width = drawerWidthPx
        card.layoutParams = cardParams

        // Round only the inner corners facing the screen
        if (isLeftEdge) {
            card.shapeAppearanceModel = card.shapeAppearanceModel.toBuilder()
                .setTopLeftCornerSize(0f)
                .setBottomLeftCornerSize(0f)
                .setTopRightCornerSize(20f * density)
                .setBottomRightCornerSize(20f * density)
                .build()
        } else {
            card.shapeAppearanceModel = card.shapeAppearanceModel.toBuilder()
                .setTopRightCornerSize(0f)
                .setBottomRightCornerSize(0f)
                .setTopLeftCornerSize(20f * density)
                .setBottomLeftCornerSize(20f * density)
                .build()
        }

        // 2. Touch Handle Alignment & Exclusion Rects
        val handleParams = viewHandle?.layoutParams as? FrameLayout.LayoutParams
        handleParams?.gravity = if (isLeftEdge) Gravity.START or Gravity.CENTER_VERTICAL else Gravity.END or Gravity.CENTER_VERTICAL
        viewHandle?.layoutParams = handleParams

        val pillParams = viewHandlePill?.layoutParams as? FrameLayout.LayoutParams
        pillParams?.gravity = if (isLeftEdge) Gravity.START or Gravity.CENTER_VERTICAL else Gravity.END or Gravity.CENTER_VERTICAL
        viewHandlePill?.layoutParams = pillParams

        val handleEnabled = QuickAccessManager.isEdgeHandleEnabled(activity)
        viewHandle?.visibility = if (handleEnabled && !isOpen) View.VISIBLE else View.GONE
        viewHandle?.setOnClickListener {
            openMenu()
        }

        // Android 10+ (API 29+) System Gesture Exclusion Rects to prevent Back gesture clash
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && handleEnabled) {
            viewHandle?.post {
                viewHandle?.let { handle ->
                    if (handle.width > 0 && handle.height > 0) {
                        val rect = Rect(0, 0, handle.width, handle.height)
                        handle.systemGestureExclusionRects = listOf(rect)
                    }
                }
            }
        }

        // 3. Solid Opaque Background & Stroke Color
        val bgColor = resolveOpaqueBackgroundColor()
        val strokeColor = resolveOpaqueStrokeColor()
        card.setCardBackgroundColor(bgColor)
        card.strokeColor = strokeColor

        // 4. Populate Docked Tiles
        val dockedIds = QuickAccessManager.getItemIds(activity)
        val allTiles = StorageBrowserActivity.buildAllKnownTiles(activity)
        val tileMap = allTiles.associateBy { it.id }
        val dockedItems = dockedIds.mapNotNull { tileMap[it] }

        adapter?.updateTileDecorations(
            colors = TileColorManager.loadTileColors(activity),
            icons = TileIconManager.getAllTileIcons(activity),
            res = TileIconManager.getAllTileIconRes(activity)
        )
        adapter?.submitItems(dockedItems)

        if (dockedItems.isEmpty()) {
            rvItems?.visibility = View.GONE
            layoutEmpty?.visibility = View.VISIBLE
        } else {
            rvItems?.visibility = View.VISIBLE
            layoutEmpty?.visibility = View.GONE
        }
    }

    fun openMenu() {
        if (isOpen || isAnimating) return
        val card = cardDrawer ?: return
        val scrim = viewScrim ?: return

        applyWindowInsetsToDrawer()

        isAnimating = true
        isOpen = true
        rootView?.isMenuOpen = true
        backCallback.isEnabled = true
        viewHandle?.visibility = View.GONE

        // Start off-screen
        card.translationX = if (isLeftEdge) -drawerWidthPx.toFloat() else drawerWidthPx.toFloat()
        card.visibility = View.VISIBLE

        scrim.alpha = 0f
        scrim.visibility = View.VISIBLE

        scrim.animate()
            .alpha(1f)
            .setDuration(260)
            .start()

        card.animate()
            .translationX(0f)
            .setDuration(280)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.5f))
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    isAnimating = false
                }
            })
            .start()
    }

    fun closeMenu(animate: Boolean = true) {
        if (!isOpen || isAnimating) return
        val card = cardDrawer ?: return
        val scrim = viewScrim ?: return

        if (!animate) {
            card.visibility = View.GONE
            scrim.visibility = View.GONE
            scrim.alpha = 0f
            isOpen = false
            rootView?.isMenuOpen = false
            backCallback.isEnabled = false
            if (QuickAccessManager.isEdgeHandleEnabled(activity)) {
                viewHandle?.visibility = View.VISIBLE
            }
            return
        }

        isAnimating = true
        val targetX = if (isLeftEdge) -drawerWidthPx.toFloat() else drawerWidthPx.toFloat()

        scrim.animate()
            .alpha(0f)
            .setDuration(220)
            .start()

        card.animate()
            .translationX(targetX)
            .setDuration(240)
            .setInterpolator(android.view.animation.AccelerateInterpolator(1.5f))
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    card.visibility = View.GONE
                    scrim.visibility = View.GONE
                    isAnimating = false
                    isOpen = false
                    rootView?.isMenuOpen = false
                    backCallback.isEnabled = false
                    if (QuickAccessManager.isEdgeHandleEnabled(activity)) {
                        viewHandle?.visibility = View.VISIBLE
                    }
                }
            })
            .start()
    }

    private fun getScreenBackgroundColor(): Int {
        val theme = ThemeHelper.getSavedTheme(activity)
        return when (theme) {
            ThemeHelper.THEME_AMOLED -> Color.BLACK
            ThemeHelper.THEME_LIGHT -> ContextCompat.getColor(activity, R.color.tv_bg_gradient_end)
            else -> ContextCompat.getColor(activity, R.color.tv_bg_gradient_end)
        }
    }

    private fun resolveOpaqueBackgroundColor(): Int {
        val colors = TileColorManager.loadTileColors(activity)
        val dockedIds = QuickAccessManager.getItemIds(activity)
        val screenBg = getScreenBackgroundColor()

        for (id in dockedIds) {
            val bg = colors[id]?.tileBgColor
            if (bg != null && bg != Color.TRANSPARENT) {
                return if (Color.alpha(bg) == 255) bg else ColorUtils.compositeColors(bg, screenBg)
            }
        }
        for (config in colors.values) {
            if (config.tileBgColor != Color.TRANSPARENT) {
                val bg = config.tileBgColor
                return if (Color.alpha(bg) == 255) bg else ColorUtils.compositeColors(bg, screenBg)
            }
        }
        val glassCardColor = ContextCompat.getColor(activity, R.color.mobile_glass_card)
        return ColorUtils.compositeColors(glassCardColor, screenBg)
    }

    private fun resolveOpaqueStrokeColor(): Int {
        val colors = TileColorManager.loadTileColors(activity)
        val dockedIds = QuickAccessManager.getItemIds(activity)
        val screenBg = getScreenBackgroundColor()

        for (id in dockedIds) {
            val ring = colors[id]?.ringColor
            if (ring != null && ring != Color.TRANSPARENT) {
                return if (Color.alpha(ring) == 255) ring else ColorUtils.compositeColors(ring, screenBg)
            }
        }
        for (config in colors.values) {
            if (config.ringColor != Color.TRANSPARENT) {
                val ring = config.ringColor
                return if (Color.alpha(ring) == 255) ring else ColorUtils.compositeColors(ring, screenBg)
            }
        }
        val glassStrokeColor = ContextCompat.getColor(activity, R.color.mobile_glass_stroke)
        return ColorUtils.compositeColors(glassStrokeColor, screenBg)
    }

    private fun installWindowCallback() {
        val window = activity.window ?: return
        if (window.callback !is EdgeMenuWindowCallback) {
            val original = window.callback
            val wrapped = EdgeMenuWindowCallback(
                originalCallback = original,
                isMenuOpenProvider = { isOpen },
                isLeftEdgeProvider = { isLeftEdge },
                edgeThresholdPxProvider = { 32f * activity.resources.displayMetrics.density },
                displayWidthPxProvider = { activity.resources.displayMetrics.widthPixels },
                onSwipeTriggered = { openMenu() }
            ).apply {
                initTouchSlop(activity)
            }
            window.callback = wrapped
        }
    }

    private fun uninstallWindowCallback() {
        val window = activity.window ?: return
        if (window.callback is EdgeMenuWindowCallback) {
            window.callback = (window.callback as EdgeMenuWindowCallback).originalCallback
        }
    }

    private fun applyWindowInsetsToDrawer() {
        val inner = rootView?.findViewById<View>(R.id.layoutDrawerInner) ?: return
        val decorView = activity.window?.decorView ?: return
        val rootInsets = ViewCompat.getRootWindowInsets(decorView)
        if (rootInsets != null) {
            val sysBars = rootInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            inner.setPadding(0, sysBars.top, 0, sysBars.bottom)
        }
    }
}

/**
 * Window.Callback wrapper that intercepts inward edge swipe gestures at the window level
 * without interfering with normal taps, clicks, or scrolling in the underlying activity.
 */
internal class EdgeMenuWindowCallback(
    val originalCallback: Window.Callback,
    private val isMenuOpenProvider: () -> Boolean,
    private val isLeftEdgeProvider: () -> Boolean,
    private val edgeThresholdPxProvider: () -> Float,
    private val displayWidthPxProvider: () -> Int,
    private val onSwipeTriggered: () -> Unit
) : Window.Callback by originalCallback {

    private var startX = 0f
    private var startY = 0f
    private var trackingSwipe = false
    private var consumingActiveSwipe = false
    private var touchSlop = 24f

    fun initTouchSlop(context: Context) {
        touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat().coerceAtLeast(16f)
    }

    override fun dispatchTouchEvent(event: MotionEvent?): Boolean {
        if (event == null) return originalCallback.dispatchTouchEvent(event)

        if (isMenuOpenProvider()) {
            trackingSwipe = false
            consumingActiveSwipe = false
            return originalCallback.dispatchTouchEvent(event)
        }

        val isLeft = isLeftEdgeProvider()
        val threshold = edgeThresholdPxProvider()
        val screenWidth = displayWidthPxProvider()

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val x = event.rawX
                val atEdge = if (isLeft) x <= threshold else x >= (screenWidth - threshold)
                if (atEdge) {
                    startX = event.rawX
                    startY = event.rawY
                    trackingSwipe = true
                } else {
                    trackingSwipe = false
                }
                consumingActiveSwipe = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (consumingActiveSwipe) {
                    return true
                }
                if (trackingSwipe) {
                    val dx = event.rawX - startX
                    val dy = event.rawY - startY
                    val meetsDirection = if (isLeft) dx > touchSlop else dx < -touchSlop
                    if (meetsDirection && abs(dx) > abs(dy) * 1.2f) {
                        trackingSwipe = false
                        consumingActiveSwipe = true

                        // Cancel underlying touch targets so views don't stay pressed
                        val cancelEvent = MotionEvent.obtain(event).apply {
                            action = MotionEvent.ACTION_CANCEL
                        }
                        originalCallback.dispatchTouchEvent(cancelEvent)
                        cancelEvent.recycle()

                        onSwipeTriggered()
                        return true
                    } else if (abs(dy) > touchSlop && abs(dy) > abs(dx)) {
                        // User is scrolling vertically, abort edge swipe tracking
                        trackingSwipe = false
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (consumingActiveSwipe) {
                    consumingActiveSwipe = false
                    trackingSwipe = false
                    return true
                }
                trackingSwipe = false
                consumingActiveSwipe = false
            }
        }

        return originalCallback.dispatchTouchEvent(event)
    }
}
