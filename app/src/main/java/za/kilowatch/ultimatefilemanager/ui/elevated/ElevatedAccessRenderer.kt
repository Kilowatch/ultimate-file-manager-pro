package za.kilowatch.ultimatefilemanager.ui.elevated

import android.app.Activity
import android.content.res.ColorStateList
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.widget.ImageViewCompat
import com.google.android.material.button.MaterialButton
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.databinding.ItemElevatedManagerBinding
import za.kilowatch.ultimatefilemanager.databinding.ItemElevatedManagerTvBinding
import za.kilowatch.ultimatefilemanager.settings.ColorblindPalette
import za.kilowatch.ultimatefilemanager.settings.ElevatedAccessPreferenceManager

/**
 * The view references one manager card exposes, resolved from either layout.
 *
 * The two item layouts carry **identical IDs but not identical types**: `cardManager` is a
 * `MaterialCardView` on mobile and a `LinearLayout` on TV, because the TV card is a plain
 * `bg_glass_card` panel rather than a Material card. Holding that one view in a typed field would
 * force the renderer to pick a form factor, so the card root is deliberately **absent** here — the
 * adapter that inflates the card keeps its own typed reference if it needs it.
 *
 * The nine views below are type-identical across both variants, which is what makes a single
 * renderer possible.
 */
class ElevatedAccessViewRefs(
    val card: View,
    val icon: ImageView,
    val title: TextView,
    val recommendedBadge: TextView,
    val description: TextView,
    val state: TextView,
    val porterWaiting: TextView,
    val actionButton: MaterialButton,
    val downloadButton: MaterialButton,
    val openButton: MaterialButton,
    val appAccessLayout: View,
    val appAccessSwitch: com.google.android.material.materialswitch.MaterialSwitch
) {
    companion object {
        fun from(binding: ItemElevatedManagerBinding) = ElevatedAccessViewRefs(
            card = binding.cardManager,
            icon = binding.imgManagerIcon,
            title = binding.txtManagerTitle,
            recommendedBadge = binding.txtRecommendedBadge,
            description = binding.txtManagerDesc,
            state = binding.txtManagerState,
            porterWaiting = binding.txtPorterWaiting,
            actionButton = binding.btnManagerAction,
            downloadButton = binding.btnManagerDownload,
            openButton = binding.btnManagerOpen,
            appAccessLayout = binding.layoutAppAccess,
            appAccessSwitch = binding.switchAppAccess
        )

        fun from(binding: ItemElevatedManagerTvBinding) = ElevatedAccessViewRefs(
            card = binding.cardManager,
            icon = binding.imgManagerIcon,
            title = binding.txtManagerTitle,
            recommendedBadge = binding.txtRecommendedBadge,
            description = binding.txtManagerDesc,
            state = binding.txtManagerState,
            porterWaiting = binding.txtPorterWaiting,
            actionButton = binding.btnManagerAction,
            downloadButton = binding.btnManagerDownload,
            openButton = binding.btnManagerOpen,
            appAccessLayout = binding.layoutAppAccess,
            appAccessSwitch = binding.switchAppAccess
        )
    }
}

/**
 * Binds one [ManagerState] onto one manager card. Holds no system state and performs no queries —
 * everything it renders has already been resolved by [ElevatedAccessResolver], which is what keeps
 * this class honest about the difference between "not running" and "we cannot tell" (see
 * [ServiceState.UNKNOWN]).
 *
 * One instance serves mobile and TV: the layouts differ, the IDs do not.
 */
class ElevatedAccessRenderer(
    private val activity: Activity,
    private val refs: ElevatedAccessViewRefs,
    private val onAction: (ElevatedManager, ServiceState) -> Unit,
    private val onDownload: (ElevatedManager) -> Unit,
    private val onOpen: (ElevatedManager) -> Unit,
    private val onToggleAccess: (ElevatedManager, Boolean) -> Unit
) {

    /**
     * The `app:tint` each layout applies to the brand vector, captured before anything overwrites it.
     *
     * Brand vectors in this project are filled `#FF000000` and are only visible because the layout
     * tints them (mobile `?attr/colorPrimary`, TV `@color/tv_accent`). Reading the tint from XML
     * rather than hardcoding a colour is what lets the placeholder look right on both form factors.
     */
    private val placeholderTint: ColorStateList? = refs.icon.imageTintList

    /** Whatever the layout says a normal state line looks like, so neutral states can restore it. */
    private val neutralStateColor: Int = refs.state.currentTextColor

    fun bind(state: ManagerState, waitingForPorter: Boolean) {
        val manager = state.manager

        bindIcon(state)
        bindIdentity(manager)
        bindStateLine(state)
        bindWaitingNotice(manager, waitingForPorter)
        bindAppAccess(state)
        bindButtons(state)
    }

    private fun bindAppAccess(state: ManagerState) {
        val manager = state.manager
        if (!state.installed) {
            refs.appAccessLayout.visibility = View.GONE
            refs.appAccessSwitch.setOnCheckedChangeListener(null)
            return
        }

        refs.appAccessLayout.visibility = View.VISIBLE

        val isPrefEnabled = ElevatedAccessPreferenceManager.isManagerEnabled(activity, manager)
        val hasPermission = when (state.serviceState) {
            ServiceState.CONNECTED -> true
            ServiceState.RUNNING_UNAUTHORIZED -> false
            else -> activity.checkSelfPermission(manager.permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        val isAllowed = isPrefEnabled && hasPermission
        refs.appAccessSwitch.setOnCheckedChangeListener(null)
        refs.appAccessSwitch.isChecked = isAllowed

        refs.appAccessSwitch.setOnCheckedChangeListener { _, isChecked ->
            onToggleAccess(manager, isChecked)
        }

        refs.appAccessLayout.setOnClickListener {
            refs.appAccessSwitch.toggle()
        }
    }

    /**
     * The icon rule, and the reason this is a bare `ImageView` and not a framed one.
     *
     * Installed → the app's real launcher icon, **untinted**, because a tinted launcher icon is a
     * recoloured approximation of the app it claims to represent. The tint is cleared rather than
     * left at the layout default so the icon's own colours survive.
     * Not installed → the brand vector, tinted with the layout's placeholder tint so it is visible.
     */
    private fun bindIcon(state: ManagerState) {
        val launcherIcon = state.launcherIcon
        if (launcherIcon != null) {
            refs.icon.setImageDrawable(launcherIcon)
            ImageViewCompat.setImageTintList(refs.icon, null)
        } else {
            refs.icon.setImageResource(state.manager.brandIconRes)
            ImageViewCompat.setImageTintList(refs.icon, placeholderTint)
        }
        refs.icon.contentDescription = activity.getString(state.manager.titleRes)
    }

    private fun bindIdentity(manager: ElevatedManager) {
        refs.title.setText(manager.titleRes)
        refs.description.setText(manager.descriptionRes)
        // Porter's pill is the only thing that distinguishes it visually as the recommended
        // choice, so it is driven by the enum rather than by the card's position (FR-03).
        refs.recommendedBadge.visibility = if (manager.isRecommended) View.VISIBLE else View.GONE
    }

    /**
     * One line per card. [ServiceState.UNKNOWN] is rendered in the neutral colour on purpose: UFM
     * genuinely cannot observe a manager that is not on the active channel, and colouring that
     * statement green or red would assert something the app does not know.
     */
    private fun bindStateLine(state: ManagerState) {
        val (textRes, color) = when (state.serviceState) {
            ServiceState.NOT_INSTALLED ->
                R.string.elevated_state_not_installed to neutralStateColor

            ServiceState.UNKNOWN ->
                R.string.elevated_state_unknown to neutralStateColor

            ServiceState.NOT_RUNNING ->
                R.string.elevated_state_not_running to ColorblindPalette.shizukuError(activity)

            ServiceState.RUNNING_UNAUTHORIZED ->
                R.string.elevated_state_unauthorized to ColorblindPalette.statusWarning(activity)

            ServiceState.CONNECTED ->
                R.string.elevated_state_connected to ColorblindPalette.shizukuOk(activity)
        }
        refs.state.setText(textRes)
        refs.state.setTextColor(color)
    }

    /**
     * FR-26 / E-17. Shown only on Porter's card, and only when Porter is installed, is the active
     * channel, and nothing is bound — the one situation where an installed-but-stopped Porter
     * genuinely blocks the other two.
     */
    private fun bindWaitingNotice(manager: ElevatedManager, waitingForPorter: Boolean) {
        val show = waitingForPorter && manager == ElevatedManager.PORTER
        refs.porterWaiting.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun bindButtons(state: ManagerState) {
        val manager = state.manager

        when (state.serviceState) {
            // Installed and reachable, but not usable — the user's requested affordance:
            // "if the service is running, then there should be a button to allow elevated access".
            ServiceState.NOT_RUNNING -> {
                refs.actionButton.setText(R.string.shizuku_btn_start_service)
                refs.actionButton.visibility = View.VISIBLE
                refs.actionButton.setOnClickListener { onAction(manager, state.serviceState) }
            }

            ServiceState.RUNNING_UNAUTHORIZED -> {
                refs.actionButton.setText(R.string.shizuku_btn_authorize)
                refs.actionButton.visibility = View.VISIBLE
                refs.actionButton.setOnClickListener { onAction(manager, state.serviceState) }
            }

            // Connected: nothing to enable. Unavailable: no action could change it.
            ServiceState.CONNECTED,
            ServiceState.UNKNOWN,
            ServiceState.NOT_INSTALLED -> {
                refs.actionButton.visibility = View.GONE
                refs.actionButton.setOnClickListener(null)
            }
        }

        if (state.serviceState == ServiceState.NOT_INSTALLED) {
            refs.downloadButton.setText(manager.downloadLabelRes)
            refs.downloadButton.visibility = View.VISIBLE
            refs.downloadButton.setOnClickListener { onDownload(manager) }
        } else {
            refs.downloadButton.visibility = View.GONE
            refs.downloadButton.setOnClickListener(null)
        }

        // Opening the app is offered whenever it exists, including while it is connected —
        // the user may want its own UI to start or stop the service.
        if (state.installed) {
            refs.openButton.text =
                activity.getString(R.string.shizuku_open_manager, activity.getString(manager.titleRes))
            refs.openButton.visibility = View.VISIBLE
            refs.openButton.setOnClickListener { onOpen(manager) }
        } else {
            refs.openButton.visibility = View.GONE
            refs.openButton.setOnClickListener(null)
        }
    }
}
