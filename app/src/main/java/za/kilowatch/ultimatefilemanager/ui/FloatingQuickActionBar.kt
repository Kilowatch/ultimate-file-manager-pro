package za.kilowatch.ultimatefilemanager.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.google.android.material.card.MaterialCardView
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.settings.IconCustomizationManager
import za.kilowatch.ultimatefilemanager.settings.ToolbarIconsPreferenceManager

/**
 * Floating pill-shaped bottom action bar for mobile selection mode.
 * Dynamically renders user-configured quick action buttons (max 6),
 * handles paired dynamic toggles (Protect/Unprotect, Hide/Unhide, Pin/Unpin),
 * and provides smooth enter/exit slide animations.
 */
class FloatingQuickActionBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    data class SelectionState(
        val selectedCount: Int = 0,
        val isAllSelected: Boolean = false,
        val hasHidden: Boolean = false,
        val hasVisible: Boolean = false,
        val hasProtected: Boolean = false,
        val hasUnprotected: Boolean = false,
        val hasPinned: Boolean = false,
        val hasUnpinned: Boolean = false,
        val hasArchiveSelected: Boolean = false,
        val allImagesSelected: Boolean = false
    )

    private val cardPill: MaterialCardView
    private val actionsContainer: LinearLayout
    private var actionClickListener: ((actionId: String) -> Unit)? = null
    private var isAnimating = false

    init {
        val view = LayoutInflater.from(context).inflate(R.layout.layout_floating_quick_bar, this, true)
        cardPill = view.findViewById(R.id.cardQuickBarPill)
        actionsContainer = view.findViewById(R.id.layoutQuickActionsContainer)
        visibility = View.GONE
    }

    fun setOnActionClickListener(listener: (actionId: String) -> Unit) {
        this.actionClickListener = listener
    }

    /**
     * Rebuilds and binds the quick bar action items given current selection state.
     */
    fun bindSelection(state: SelectionState) {
        val pm = ToolbarIconsPreferenceManager
        val actionIds = pm.getQuickBarItems(context).filter { actionId ->
            when (actionId) {
                pm.ACTION_COPY -> pm.isIconEnabled(context, pm.KEY_COPY)
                pm.ACTION_MOVE -> pm.isIconEnabled(context, pm.KEY_MOVE)
                pm.ACTION_DELETE -> pm.isIconEnabled(context, pm.KEY_DELETE)
                pm.ACTION_RENAME -> pm.isIconEnabled(context, pm.KEY_RENAME)
                pm.ACTION_SHARE -> pm.isIconEnabled(context, pm.KEY_SHARE)
                pm.ACTION_COMPRESS -> pm.isIconEnabled(context, pm.KEY_COMPRESS)
                pm.ACTION_EXTRACT -> pm.isIconEnabled(context, pm.KEY_EXTRACT)
                pm.ACTION_FAVORITE -> pm.isIconEnabled(context, pm.KEY_FAVORITE)
                pm.ACTION_PROTECT_UNPROTECT -> pm.isIconEnabled(context, pm.KEY_PROTECT) || pm.isIconEnabled(context, pm.KEY_UNPROTECT)
                pm.ACTION_HIDE_UNHIDE -> pm.isIconEnabled(context, pm.KEY_HIDE) || pm.isIconEnabled(context, pm.KEY_UNHIDE)
                pm.ACTION_PIN_UNPIN -> pm.isIconEnabled(context, pm.KEY_PIN) || pm.isIconEnabled(context, pm.KEY_UNPIN)
                pm.ACTION_COPY_ENCRYPT -> pm.isIconEnabled(context, pm.KEY_COPY_ENCRYPT)
                pm.ACTION_MOVE_ENCRYPT -> pm.isIconEnabled(context, pm.KEY_MOVE_ENCRYPT)
                pm.ACTION_IMAGE_COMPRESS -> pm.isIconEnabled(context, pm.KEY_IMAGE_COMPRESS)
                pm.ACTION_CREATE_GIF -> pm.isIconEnabled(context, pm.KEY_CREATE_GIF)
                pm.ACTION_EXIF_TOOLS -> pm.isIconEnabled(context, pm.KEY_EXIF_TOOLS)
                pm.ACTION_SET_HOME_WALLPAPER -> pm.isIconEnabled(context, pm.KEY_SET_HOME_WALLPAPER)
                pm.ACTION_SET_LOCK_WALLPAPER -> pm.isIconEnabled(context, pm.KEY_SET_LOCK_WALLPAPER)
                pm.ACTION_SELECT_ALL -> pm.isIconEnabled(context, pm.KEY_SELECT_ALL)
                pm.ACTION_INVERT_SELECTION -> pm.isIconEnabled(context, pm.KEY_INVERT_SELECTION)
                pm.ACTION_CHECKSUM -> pm.isIconEnabled(context, pm.KEY_CHECKSUM)
                pm.ACTION_MORE -> true
                else -> true
            }
        }
        val inflater = LayoutInflater.from(context)

        actionsContainer.removeAllViews()

        for (actionId in actionIds) {
            val buttonView = inflater.inflate(R.layout.item_quick_action_button, actionsContainer, false)
            val imgIcon = buttonView.findViewById<ImageView>(R.id.imgQuickActionIcon)
            val txtLabel = buttonView.findViewById<TextView>(R.id.txtQuickActionLabel)

            var resolvedIconRes: Int
            var resolvedNameRes: Int
            var customIconKey: String? = null
            var effectiveActionId = actionId

            when (actionId) {
                pm.ACTION_DELETE -> {
                    resolvedIconRes = R.drawable.ic_delete
                    resolvedNameRes = R.string.action_delete
                    customIconKey = "toolbar_delete"
                }
                pm.ACTION_COMPRESS -> {
                    resolvedIconRes = R.drawable.ic_compress
                    resolvedNameRes = R.string.action_compress
                    customIconKey = "toolbar_compress"
                }
                pm.ACTION_MOVE -> {
                    resolvedIconRes = R.drawable.ic_move
                    resolvedNameRes = R.string.action_move
                    customIconKey = "toolbar_move"
                }
                pm.ACTION_COPY -> {
                    resolvedIconRes = R.drawable.ic_copy
                    resolvedNameRes = R.string.action_copy
                    customIconKey = "toolbar_copy"
                }
                pm.ACTION_RENAME -> {
                    resolvedIconRes = R.drawable.ic_edit
                    resolvedNameRes = R.string.action_rename
                    customIconKey = "toolbar_rename"
                }
                pm.ACTION_SHARE -> {
                    resolvedIconRes = R.drawable.ic_share
                    resolvedNameRes = R.string.action_share
                    customIconKey = "toolbar_share"
                }
                pm.ACTION_PROTECT_UNPROTECT -> {
                    if (state.hasProtected && !state.hasUnprotected) {
                        resolvedIconRes = R.drawable.ic_shield_unprotected
                        resolvedNameRes = R.string.unprotect
                        customIconKey = "toolbar_unprotect"
                        effectiveActionId = "unprotect"
                    } else {
                        resolvedIconRes = R.drawable.ic_shield_protected
                        resolvedNameRes = R.string.protect
                        customIconKey = "toolbar_protect"
                        effectiveActionId = "protect"
                    }
                }
                pm.ACTION_HIDE_UNHIDE -> {
                    if (state.hasHidden && !state.hasVisible) {
                        resolvedIconRes = R.drawable.ic_eye
                        resolvedNameRes = R.string.unhide
                        customIconKey = "toolbar_unhide"
                        effectiveActionId = "unhide"
                    } else {
                        resolvedIconRes = R.drawable.ic_eye_off
                        resolvedNameRes = R.string.hide
                        customIconKey = "toolbar_hide"
                        effectiveActionId = "hide"
                    }
                }
                pm.ACTION_PIN_UNPIN -> {
                    if (state.hasPinned && !state.hasUnpinned) {
                        resolvedIconRes = R.drawable.ic_paperclip_off
                        resolvedNameRes = R.string.unpin
                        customIconKey = "toolbar_unpin"
                        effectiveActionId = "unpin"
                    } else {
                        resolvedIconRes = R.drawable.ic_paperclip
                        resolvedNameRes = R.string.pin
                        customIconKey = "toolbar_pin"
                        effectiveActionId = "pin"
                    }
                }
                pm.ACTION_FAVORITE -> {
                    resolvedIconRes = R.drawable.ic_star
                    resolvedNameRes = R.string.action_favorite
                    customIconKey = "toolbar_favorite"
                }
                pm.ACTION_SELECT_ALL -> {
                    resolvedIconRes = if (state.isAllSelected) R.drawable.ic_deselect_all else R.drawable.ic_select_all
                    resolvedNameRes = if (state.isAllSelected) R.string.action_deselect_all else R.string.action_select_all
                    customIconKey = "toolbar_select_all"
                }
                pm.ACTION_INVERT_SELECTION -> {
                    resolvedIconRes = R.drawable.ic_invert_selection
                    resolvedNameRes = R.string.action_invert_selection
                    customIconKey = "toolbar_invert_selection"
                }
                pm.ACTION_EXTRACT -> {
                    resolvedIconRes = R.drawable.ic_extract
                    resolvedNameRes = R.string.action_extract_here
                }
                pm.ACTION_IMAGE_COMPRESS -> {
                    resolvedIconRes = R.drawable.ic_compress_image
                    resolvedNameRes = R.string.action_compress_image
                    customIconKey = "toolbar_image_compress"
                }
                pm.ACTION_CREATE_GIF -> {
                    resolvedIconRes = R.drawable.ic_gif
                    resolvedNameRes = R.string.action_create_gif
                    customIconKey = "toolbar_create_gif"
                }
                pm.ACTION_EXIF_TOOLS -> {
                    resolvedIconRes = R.drawable.ic_exif_cleaner
                    resolvedNameRes = R.string.action_exif_cleaner_renamer
                    customIconKey = "toolbar_exif_cleaner"
                }
                pm.ACTION_SET_HOME_WALLPAPER -> {
                    resolvedIconRes = R.drawable.ic_wallpaper_home
                    resolvedNameRes = R.string.action_set_home_wallpaper
                    customIconKey = "toolbar_set_home_wallpaper"
                }
                pm.ACTION_SET_LOCK_WALLPAPER -> {
                    resolvedIconRes = R.drawable.ic_wallpaper_lock
                    resolvedNameRes = R.string.action_set_lock_wallpaper
                    customIconKey = "toolbar_set_lock_wallpaper"
                }
                pm.ACTION_DUPLICATE_FINDER -> {
                    resolvedIconRes = R.drawable.ic_duplicate_finder
                    resolvedNameRes = R.string.action_duplicate_finder
                    customIconKey = "toolbar_duplicate_finder"
                }
                pm.ACTION_LARGE_FILES_FINDER -> {
                    resolvedIconRes = R.drawable.ic_folder_large_files
                    resolvedNameRes = R.string.action_large_files_finder
                    customIconKey = "toolbar_large_files_finder"
                }
                pm.ACTION_CREATE_NEW -> {
                    resolvedIconRes = R.drawable.ic_create_new
                    resolvedNameRes = R.string.cd_create_new
                    customIconKey = "toolbar_create_new"
                }
                pm.ACTION_RETRIGGER_THUMBNAILS -> {
                    resolvedIconRes = R.drawable.ic_photo_video
                    resolvedNameRes = R.string.action_retrigger_thumbnails
                    customIconKey = "toolbar_retrigger_thumbnails"
                }
                pm.ACTION_COPY_ENCRYPT -> {
                    resolvedIconRes = R.drawable.ic_copy_encrypt
                    resolvedNameRes = R.string.action_copy_encrypt
                    customIconKey = "toolbar_copy_encrypt"
                }
                pm.ACTION_MOVE_ENCRYPT -> {
                    resolvedIconRes = R.drawable.ic_move_encrypt
                    resolvedNameRes = R.string.action_move_encrypt
                    customIconKey = "toolbar_move_encrypt"
                }
                pm.ACTION_CHECKSUM -> {
                    resolvedIconRes = R.drawable.ic_checksum
                    resolvedNameRes = R.string.action_checksum
                    customIconKey = "toolbar_checksum"
                }
                pm.ACTION_MORE -> {
                    resolvedIconRes = R.drawable.ic_arrow_forward
                    resolvedNameRes = R.string.quick_bar_action_more
                }
                else -> {
                    resolvedIconRes = R.drawable.ic_more
                    resolvedNameRes = R.string.quick_bar_action_more
                }
            }

            imgIcon.setImageResource(resolvedIconRes)
            if (customIconKey != null) {
                IconCustomizationManager.applyToView(context, imgIcon, customIconKey, resolvedIconRes)
            }
            txtLabel.setText(resolvedNameRes)

            buttonView.setOnClickListener {
                actionClickListener?.invoke(effectiveActionId)
            }

            actionsContainer.addView(buttonView)
        }
    }

    /**
     * Shows the quick bar with a smooth slide-up and fade-in animation.
     */
    fun showAnimated(onShown: (() -> Unit)? = null) {
        if (visibility == View.VISIBLE && alpha > 0.9f) {
            onShown?.invoke()
            return
        }
        animate().cancel()
        isAnimating = true
        alpha = 0f
        translationY = (36 * resources.displayMetrics.density)
        visibility = View.VISIBLE
        animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(220)
            .setInterpolator(FastOutSlowInInterpolator())
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    isAnimating = false
                    onShown?.invoke()
                }
            })
            .start()
    }

    /**
     * Hides the quick bar with a smooth slide-down and fade-out animation.
     */
    fun hideAnimated(onHidden: (() -> Unit)? = null) {
        if (visibility != View.VISIBLE) {
            onHidden?.invoke()
            return
        }
        animate().cancel()
        isAnimating = true
        animate()
            .alpha(0f)
            .translationY((36 * resources.displayMetrics.density))
            .setDuration(180)
            .setInterpolator(DecelerateInterpolator())
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    visibility = View.GONE
                    isAnimating = false
                    onHidden?.invoke()
                }
            })
            .start()
    }
}
