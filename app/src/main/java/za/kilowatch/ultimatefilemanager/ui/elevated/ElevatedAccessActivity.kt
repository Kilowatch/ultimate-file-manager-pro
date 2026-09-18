package za.kilowatch.ultimatefilemanager.ui.elevated

import android.animation.ObjectAnimator
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.ImageViewCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.databinding.ItemElevatedManagerBinding
import za.kilowatch.ultimatefilemanager.databinding.ItemElevatedManagerTvBinding
import za.kilowatch.ultimatefilemanager.settings.ColorblindPalette
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import za.kilowatch.ultimatefilemanager.settings.ThemeHelper
import android.util.Log
import za.kilowatch.ultimatefilemanager.storage.RootShellWrapper
import za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper
import za.kilowatch.ultimatefilemanager.update.ElevatedAppDownloadManager
import za.kilowatch.ultimatefilemanager.util.DeviceUtils

/**
 * The screen-level views from `activity_elevated_access[_tv].xml`.
 *
 * Resolved by id rather than by binding because **the two layouts have different binding
 * classes**. The 22 ids are the contract between the two files — identical on both form factors,
 * which is what lets one Activity serve them — so holding them by id is the honest expression of
 * that contract rather than a workaround.
 *
 * `toolbar` and `scrollView` are structural and deliberately absent: nothing reads them, and a field
 * nobody assigns is how `txtServiceModeVal` came to be dead in the layout this screen replaces.
 *
 * `heroCard` is a plain [View] for the same reason [ElevatedAccessViewRefs.card] is: it is a
 * `MaterialCardView` on mobile and a `LinearLayout` on TV. The hero *stroke* is a Material-card
 * affordance with no TV counterpart, so it is applied through a cast at the call site rather than
 * forced onto a common supertype that does not have it.
 */
private class ElevatedAccessScreenRefs(
    val root: View,
    val backButton: View,
    val recheckButton: MaterialButton,
    val enableButton: MaterialButton,
    val heroCard: View,
    val statusIconBg: View,
    val statusIcon: ImageView,
    val heroAppName: TextView?,
    val statusTitle: TextView,
    val statusBadge: TextView,
    val statusDescription: TextView,
    val stopDaemonButton: MaterialButton?,
    val protectedCheck: ImageView,
    val speedCheck: ImageView,
    val securityCheck: ImageView,
    val managersContainer: ViewGroup,
    val managersSection: View,
    val progressDetection: View,
    val serviceInfoCard: View,
    val serviceProviderValue: TextView,
    val servicePermissionValue: TextView,
    val setupGuideCard: View
) {
    companion object {
        fun from(root: View) = ElevatedAccessScreenRefs(
            root = root,
            backButton = root.findViewById(R.id.btnBack),
            recheckButton = root.findViewById(R.id.btnRecheckStatus),
            enableButton = root.findViewById(R.id.btnShizukuEnable),
            heroCard = root.findViewById(R.id.cardStatusHero),
            statusIconBg = root.findViewById(R.id.layoutStatusIconBg),
            statusIcon = root.findViewById(R.id.imgStatusIcon),
            heroAppName = root.findViewById(R.id.txtHeroAppName),
            statusTitle = root.findViewById(R.id.txtShizukuStatus),
            statusBadge = root.findViewById(R.id.txtStatusBadge),
            statusDescription = root.findViewById(R.id.txtShizukuDescription),
            stopDaemonButton = root.findViewById(R.id.btnStopDaemon),
            protectedCheck = root.findViewById(R.id.icProtectedCheck),
            speedCheck = root.findViewById(R.id.icSpeedCheck),
            securityCheck = root.findViewById(R.id.icSecurityCheck),
            managersContainer = root.findViewById(R.id.layoutManagersContainer),
            managersSection = root.findViewById(R.id.layoutManagersSection),
            progressDetection = root.findViewById(R.id.progressDetection),
            serviceInfoCard = root.findViewById(R.id.cardServiceInfo),
            serviceProviderValue = root.findViewById(R.id.txtServiceProviderVal),
            servicePermissionValue = root.findViewById(R.id.txtServicePermissionVal),
            setupGuideCard = root.findViewById(R.id.cardSetupGuide)
        )
    }
}

/**
 * Elevated Access — the single Activity that replaces both `ShizukuActivity` and
 * `ShizukuTvActivity`.
 *
 * One class, two layouts, selected by [DeviceUtils.isTvDevice]. The layouts are structurally very
 * different (the TV one is a `ConstraintLayout` over a gradient, the mobile one a card stack) but
 * expose the same ids, so the branch happens exactly once, here, and nothing below it knows which
 * form factor it is on.
 *
 * State is produced by [ElevatedAccessResolver] — this class queries nothing itself. It renders,
 * and it performs the actions the cards ask for.
 */
class ElevatedAccessActivity : AppCompatActivity() {

    private lateinit var refs: ElevatedAccessScreenRefs
    private var isTv = false

    /**
     * One renderer per manager card, keyed by manager. Built once in [buildManagerCards] and
     * rebound on every refresh — the cards are not rebuilt, because rebuilding them on resume would
     * throw away focus, which on TV is the difference between a usable screen and a lost cursor.
     */
    private val cardRenderers = LinkedHashMap<ElevatedManager, ElevatedAccessRenderer>()

    /**
     * True once a resolved state has been drawn at least once. Gates the detection state in
     * [refresh] so the progress bar appears on first load only, not on every resume.
     */
    private var hasRenderedResolvedState = false

    /** The manager whose card was tapped while a start attempt is running, or null. */
    private var startingManager: ElevatedManager? = null
    private var startingDialog: AlertDialog? = null

    private var statusAnimator: ObjectAnimator? = null

    /**
     * Neutral hero colour, captured from the layout so both variants keep their own.
     *
     * **The layouts' `txtShizukuStatus` colour is load-bearing, not decoration.** [heroAccentFor]
     * returns this value for [ServiceState.UNKNOWN], so whatever the layouts declare there becomes
     * the colour of "UFM cannot tell". Both must therefore declare a *neutral* token
     * (`mobile_card_text_primary` / `tv_text_primary`). The TV layout previously declared
     * `?attr/ufmShizukuError`, which would have rendered "unknown" in the error colour — a claim the
     * screen is not entitled to make. It also fixes the first frame: the layouts ship
     * `@string/shizuku_not_installed` as placeholder text, and no state colour is known at
     * inflation time.
     */
    private var heroNeutralColor: Int = 0
    private var placeholderTint: ColorStateList? = null

    /** The manager the hero panel currently describes. Kept so its button knows its target. */
    private var heroState: ManagerState? = null

    /**
     * System reads, behind the same seam the resolver's unit tests use. A `status` screen must not
     * mutate, and [ElevatedAccessProbe] is what enforces that — see its own documentation.
     */
    private val probe: ElevatedAccessProbe by lazy { SystemElevatedAccessProbe(applicationContext) }
    private val resolver: ElevatedAccessResolver by lazy { ElevatedAccessResolver(probe) }

    private val permissionRequestCode = 1001

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { code, _ ->
        if (code == permissionRequestCode) refresh()
    }

    /**
     * Wraps the base context for locale.
     *
     * Note for anyone following `CLAUDE.md`'s Activity convention verbatim: it names
     * `LocaleManagerWrapper.setLocale(...)`, a class that **does not exist anywhere in this
     * project**. The real API is [LocaleHelper.wrap], which is what every other Activity here
     * calls.
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)

        // Before setContentView, per the convention. `enableEdgeToEdge()` *is* `EdgeToEdge.enable(this)`
        // — the androidx-activity extension delegates to it — and is what the other 110 Activities
        // in this project call.
        enableEdgeToEdge()

        isTv = DeviceUtils.isTvDevice(this)
        val layoutRes =
            if (isTv) R.layout.activity_elevated_access_tv else R.layout.activity_elevated_access
        val root = LayoutInflater.from(this).inflate(layoutRes, null)
        setContentView(root)

        refs = ElevatedAccessScreenRefs.from(root)
        heroNeutralColor = refs.statusTitle.currentTextColor
        placeholderTint = refs.statusIcon.imageTintList

        refs.backButton.setOnClickListener { finish() }
        refs.recheckButton.setOnClickListener {
            refresh()
            Toast.makeText(this, R.string.shizuku_recheck_status, Toast.LENGTH_SHORT).show()
        }
        refs.enableButton.setOnClickListener {
            heroState?.let { hero ->
                if (hero.installed) onManagerAction(hero.manager, hero.serviceState)
            }
        }
        refs.stopDaemonButton?.setOnClickListener {
            heroState?.let { hero -> confirmStopDaemon(hero) }
        }

        ViewCompat.setOnApplyWindowInsetsListener(refs.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        buildManagerCards()

        try {
            Shizuku.addRequestPermissionResultListener(permissionListener)
        } catch (e: Throwable) {
            e.printStackTrace()
        }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        // FR-11: a user who installs a manager and comes back sees its real icon and state without
        // restarting UFM. Nothing needs invalidating by hand — the resolver re-reads the system.
        refresh()
    }

    override fun onDestroy() {
        super.onDestroy()
        statusAnimator?.cancel()
        statusAnimator = null
        startingDialog?.dismiss()
        startingDialog = null
        try {
            Shizuku.removeRequestPermissionResultListener(permissionListener)
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    // ── Card construction ───────────────────────────────────────────────────

    /**
     * One card per [ElevatedManager], in declaration order.
     *
     * The container is filled from the enum, never from XML, so the order a user sees cannot drift
     * between the two layouts and Porter is first on both (FR-02/FR-04/FR-05). A fourth manager
     * would need no layout change at all.
     */
    private fun buildManagerCards() {
        val container = refs.managersContainer
        container.removeAllViews()
        cardRenderers.clear()

        val inflater = LayoutInflater.from(this)
        ElevatedManager.entries.forEach { manager ->
            val viewRefs = if (isTv) {
                ElevatedAccessViewRefs.from(
                    ItemElevatedManagerTvBinding.inflate(inflater, container, false)
                )
            } else {
                ElevatedAccessViewRefs.from(
                    ItemElevatedManagerBinding.inflate(inflater, container, false)
                )
            }
            container.addView(viewRefs.card)
            cardRenderers[manager] = ElevatedAccessRenderer(
                activity = this,
                refs = viewRefs,
                onAction = ::onManagerAction,
                onDownload = ::onManagerDownload,
                onOpen = ::onManagerOpen,
                onToggleAccess = ::onToggleAppAccess
            )
        }
    }

    // ── Rendering ───────────────────────────────────────────────────────────

    private fun refresh() {
        // NFR-07 / E-18: enter the loading state before the first read, so the screen is never
        // briefly a settled-looking picture of a system nobody has looked at yet.
        //
        // First pass only. Later refreshes — every onResume, including the very common "tap Open
        // Porter, authorize, come back" round trip (FR-11) — leave the already-resolved screen up
        // and swap it when the new state lands. That picture is complete rather than partial, so
        // E-18 is not in play, and re-showing the bar each time would blank the cards' buttons and
        // hide the description for a read that takes milliseconds.
        if (!hasRenderedResolvedState) renderDetecting()

        lifecycleScope.launch {
            // Binding and launcher-icon reads are IPC and disk; keep them off the main thread.
            val state = withContext(Dispatchers.IO) { resolver.resolve() }
            render(state)
        }
    }

    private fun render(state: ElevatedAccessUiState) {
        ElevatedManager.entries.forEach { manager ->
            val managerState = state.managers.firstOrNull { it.manager == manager } ?: return@forEach
            cardRenderers[manager]?.bind(managerState, state.waitingForPorter)
        }

        val hero = heroManagerFor(state)
        heroState = hero
        renderHero(hero)
        renderServiceInfo(hero)

        // This is the only path that draws a resolved state, so it is what releases the gate in
        // refresh(). Set last, after the screen is actually consistent.
        hasRenderedResolvedState = true
    }

    /**
     * NFR-07 / E-18 — the state shown from [refresh] until the resolve lands.
     *
     * The manager cards are deliberately **left unbound** rather than rendered from
     * [ElevatedAccessUiState.loading]: every conditional view in both item layouts ships
     * `android:visibility="gone"` and the text fields start empty, so unbind cards read as pending.
     * Binding them from the loading state would render three "Not installed" lines, which is a claim
     * about managers that have not been looked for yet — the exact outcome E-18 forbids. The hero is
     * reset by hand for the same reason: [heroManagerFor] would otherwise fall through to its
     * "first entry" branch and report Porter as not installed.
     *
     * The description is hidden rather than set, because the layouts ship
     * `shizuku_status_not_installed_desc` as its placeholder text — leaving it up would contradict
     * the "checking…" title directly above it. [renderHero] restores its visibility.
     */
    private fun renderDetecting() {
        statusAnimator?.cancel()
        statusAnimator = null
        refs.statusIcon.alpha = 1f
        heroState = null

        refs.progressDetection.visibility = View.VISIBLE

        refs.statusIcon.setImageResource(R.drawable.ic_shield_alert)
        ImageViewCompat.setImageTintList(refs.statusIcon, ColorStateList.valueOf(heroNeutralColor))
        refs.statusIconBg.setBackgroundResource(R.drawable.bg_badge_unavailable)

        refs.heroAppName?.visibility = View.GONE
        refs.statusTitle.setText(R.string.elevated_detecting)
        refs.statusDescription.visibility = View.GONE
        refs.statusBadge.visibility = View.GONE
        refs.enableButton.visibility = View.GONE
        refs.stopDaemonButton?.visibility = View.GONE

        // Neutral, like every other statement this screen makes about a state it has not observed.
        (refs.heroCard as? MaterialCardView)?.strokeColor =
            ColorUtils.setAlphaComponent(heroNeutralColor, HERO_STROKE_ALPHA)

        refs.protectedCheck.visibility = View.GONE
        refs.speedCheck.visibility = View.GONE
        refs.securityCheck.visibility = View.GONE
        refs.serviceInfoCard.visibility = View.GONE
        refs.setupGuideCard.visibility = View.GONE
    }

    /**
     * Which manager the hero describes.
     *
     * Order of preference: whoever is actually connected, then whoever the SDK is routed to, then
     * any installed manager, then the first entry. The last two exist so the panel is never blank —
     * a hero that says nothing is worse than one that names the manager whose absence is the
     * problem.
     */
    private fun heroManagerFor(state: ElevatedAccessUiState): ManagerState =
        state.managers.firstOrNull { it.serviceState == ServiceState.CONNECTED }
            ?: state.managers.firstOrNull {
                it.isActiveBackend && it.serviceState != ServiceState.NOT_INSTALLED
            }
            ?: state.managers.firstOrNull { it.installed }
            ?: state.managers.first()

    private fun renderHero(hero: ManagerState) {
        statusAnimator?.cancel()
        statusAnimator = null
        refs.statusIcon.alpha = 1f

        // Leave the detection state behind: the resolve has landed, so the progress bar goes and the
        // description line comes back (renderDetecting hides it — see its note).
        refs.progressDetection.visibility = View.GONE
        refs.statusDescription.visibility = View.VISIBLE

        val accent = heroAccentFor(hero.serviceState)
        val titleRes: Int
        val badgeRes: Int?
        val descriptionRes: Int
        val showEnable: Boolean
        val enableLabelRes: Int
        val enableIconRes: Int

        when (hero.serviceState) {
            ServiceState.CONNECTED -> {
                titleRes = R.string.shizuku_enabled_and_active
                badgeRes = R.string.shizuku_badge_connected
                descriptionRes = R.string.shizuku_status_active_desc
                showEnable = false
                enableLabelRes = R.string.shizuku_btn_enable
                enableIconRes = R.drawable.ic_shield_check
            }

            ServiceState.RUNNING_UNAUTHORIZED -> {
                titleRes = R.string.shizuku_not_authorized
                badgeRes = R.string.shizuku_badge_auth_needed
                descriptionRes = R.string.shizuku_status_unauthorized_desc
                showEnable = true
                enableLabelRes = R.string.shizuku_btn_authorize
                enableIconRes = R.drawable.ic_check_circle
            }

            ServiceState.NOT_RUNNING -> {
                titleRes = R.string.shizuku_service_not_running
                badgeRes = R.string.shizuku_badge_stopped
                descriptionRes = R.string.shizuku_status_stopped_desc
                showEnable = true
                enableLabelRes = R.string.shizuku_btn_start_service
                enableIconRes = R.drawable.ic_lightning
            }

            // The manager is missing, or it is installed on a channel UFM cannot observe. Neither
            // is a failure of anything the user can see, so the panel states it and offers no
            // action beyond the per-card download/open the cards already carry.
            ServiceState.NOT_INSTALLED,
            ServiceState.UNKNOWN -> {
                titleRes = if (hero.serviceState == ServiceState.UNKNOWN) {
                    R.string.elevated_state_unknown
                } else {
                    R.string.shizuku_not_installed
                }
                badgeRes = if (hero.serviceState == ServiceState.UNKNOWN) {
                    null
                } else {
                    R.string.shizuku_badge_unavailable
                }
                descriptionRes = R.string.shizuku_status_not_installed_desc
                showEnable = false
                enableLabelRes = R.string.shizuku_btn_enable
                enableIconRes = R.drawable.ic_shield_check
            }
        }

        // Top App Icon: matches the bare ImageView in the manager cards below
        val launcherIcon = hero.launcherIcon
        if (launcherIcon != null) {
            refs.statusIcon.setImageDrawable(launcherIcon)
            ImageViewCompat.setImageTintList(refs.statusIcon, null)
            refs.statusIconBg.background = null
        } else {
            refs.statusIcon.setImageResource(hero.manager.brandIconRes)
            ImageViewCompat.setImageTintList(refs.statusIcon, placeholderTint ?: ColorStateList.valueOf(accent))
            refs.statusIconBg.background = null
        }
        refs.statusIcon.contentDescription = getString(hero.manager.titleRes)

        // Top App Name: prominent app identity
        refs.heroAppName?.setText(hero.manager.titleRes)
        refs.heroAppName?.visibility = View.VISIBLE

        refs.statusTitle.setText(titleRes)
        refs.statusDescription.setText(descriptionRes)

        if (badgeRes != null) {
            refs.statusBadge.setText(badgeRes)
            refs.statusBadge.setBackgroundResource(heroBadgeBackgroundFor(hero.serviceState))
            refs.statusBadge.setTextColor(accent)
            refs.statusBadge.visibility = View.VISIBLE
        } else {
            refs.statusBadge.visibility = View.GONE
        }

        // The stroke is a MaterialCardView affordance. The TV hero is a plain LinearLayout panel
        // that carries its own background, so there is nothing to stroke there — this is the one
        // place the two form factors genuinely differ in what they *can* render.
        (refs.heroCard as? MaterialCardView)?.strokeColor =
            ColorUtils.setAlphaComponent(accent, HERO_STROKE_ALPHA)

        refs.enableButton.visibility = if (showEnable) View.VISIBLE else View.GONE
        if (showEnable) {
            refs.enableButton.setText(enableLabelRes)
            refs.enableButton.setIconResource(enableIconRes)
        }

        // Stop Daemon Button: visible when the daemon is running or connected
        val canStop = hero.serviceState == ServiceState.CONNECTED || hero.serviceState == ServiceState.RUNNING_UNAUTHORIZED
        refs.stopDaemonButton?.visibility = if (canStop) View.VISIBLE else View.GONE

        val active = hero.serviceState == ServiceState.CONNECTED
        val checkVisibility = if (active) View.VISIBLE else View.GONE
        refs.protectedCheck.visibility = checkVisibility
        refs.speedCheck.visibility = checkVisibility
        refs.securityCheck.visibility = checkVisibility

        // The guide is for getting set up; once it works it is noise.
        refs.setupGuideCard.visibility = if (active) View.GONE else View.VISIBLE

        if (active) {
            statusAnimator = ObjectAnimator.ofFloat(refs.statusIcon, "alpha", 1f, 0.4f, 1f).apply {
                duration = 1500
                repeatCount = ObjectAnimator.INFINITE
                start()
            }
        }
    }

    private fun heroBadgeBackgroundFor(state: ServiceState): Int = when (state) {
        ServiceState.CONNECTED -> R.drawable.bg_badge_connected
        ServiceState.RUNNING_UNAUTHORIZED -> R.drawable.bg_status_badge_accent
        ServiceState.NOT_RUNNING -> R.drawable.bg_badge_inactive
        ServiceState.NOT_INSTALLED,
        ServiceState.UNKNOWN -> R.drawable.bg_badge_unavailable
    }

    /**
     * Only shown while something is actually connected — it names the backend that supplied the
     * live binder, which is meaningless when there is none.
     *
     * The value is the manager's own name rather than a "<name> Service" literal. The row's label is
     * already "Service Provider", so the name alone reads correctly, and a composed literal would
     * have to be assembled from an untranslatable English suffix.
     */
    private fun renderServiceInfo(hero: ManagerState) {
        if (hero.serviceState != ServiceState.CONNECTED) {
            refs.serviceInfoCard.visibility = View.GONE
            return
        }
        refs.serviceInfoCard.visibility = View.VISIBLE
        refs.serviceProviderValue.setText(hero.manager.titleRes)
        refs.servicePermissionValue.setText(R.string.shizuku_permission_granted_api)
        refs.servicePermissionValue.setTextColor(ColorblindPalette.shizukuOk(this))
    }

    private fun heroAccentFor(state: ServiceState): Int = when (state) {
        ServiceState.CONNECTED -> ColorblindPalette.shizukuOk(this)
        ServiceState.RUNNING_UNAUTHORIZED -> ColorblindPalette.focusAccent(this)
        ServiceState.NOT_RUNNING -> ColorblindPalette.statusWarning(this)
        ServiceState.NOT_INSTALLED -> ColorblindPalette.shizukuError(this)
        // Neutral on purpose: UFM cannot observe this manager, so colouring the statement would
        // assert something it does not know. Same rule as ElevatedAccessRenderer.bindStateLine.
        ServiceState.UNKNOWN -> heroNeutralColor
    }

    // ── Actions ─────────────────────────────────────────────────────────────

    private fun onManagerAction(manager: ElevatedManager, state: ServiceState) {
        when (state) {
            // FR-15: confirm, then trigger that manager's permission request.
            ServiceState.RUNNING_UNAUTHORIZED -> showAuthDialog()

            // FR-16/17/18: the start chain.
            ServiceState.NOT_RUNNING -> startManagerService(manager)

            // The renderer offers no action in these states, so there is nothing to do.
            ServiceState.CONNECTED,
            ServiceState.UNKNOWN,
            ServiceState.NOT_INSTALLED -> Unit
        }
    }

    private fun onManagerDownload(manager: ElevatedManager) {
        ElevatedAppDownloadManager.startDownloadFlow(this, manager.toDownloadableApp())
    }

    private fun onManagerOpen(manager: ElevatedManager) {
        openManagerApp(manager)
    }

    private fun openManagerApp(manager: ElevatedManager) {
        val launchIntent = packageManager.getLaunchIntentForPackage(manager.packageName)
        if (launchIntent != null) {
            startActivity(launchIntent)
        } else {
            Toast.makeText(this, R.string.shizuku_manager_open_error, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * FR-15. The dialog is the confirmation step; the request itself goes through
     * `ShizukuShellWrapper`, which re-attaches once if the client has detached rather than throwing.
     */
    private fun showAuthDialog() {
        val dialogView = layoutInflater.inflate(
            if (isTv) R.layout.dialog_shizuku_auth_tv else R.layout.dialog_shizuku_auth,
            null
        )
        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialogView.findViewById<View>(R.id.btnAuthorize).setOnClickListener {
            val requested = ShizukuShellWrapper.requestPermissionSafely(permissionRequestCode, this)
            if (!requested) {
                Toast.makeText(this, R.string.shizuku_start_failed, Toast.LENGTH_SHORT).show()
            }
            dialog.dismiss()
        }
        dialogView.findViewById<View>(R.id.btnCancel).setOnClickListener { dialog.dismiss() }

        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    }

    /**
     * FR-16 — the start chain, in the specified order, with a bounded wait after each attempt.
     *
     * The order is not arbitrary. A start broadcast asks the manager to bring its daemon up; if that
     * is ignored, the binder-request broadcast asks it to hand over a binder it may already hold; and
     * if neither produces a binder, the only remaining move is to put the manager's own UI in front
     * of the user, because from outside the app there is nothing else left to try.
     *
     * Porter's start broadcast is deliberately **absent** from step one. It is gated by an auth token
     * in Porter's private preferences that no other app can read (spec CR-02), and its receiver class
     * name is not part of the published SDK — sending a guessed component name would be a no-op that
     * reads like a real attempt. Porter therefore enters the chain at step two, where its documented
     * broadcast action does exist.
     *
     * The whole chain is bounded: [BINDER_WAIT_STEPS] × [BINDER_WAIT_INTERVAL_MS] per attempt, on the
     * main dispatcher but suspending, so the UI never blocks and the screen never hangs (E-01).
     * Nothing here reports success — [refresh] re-reads the system afterwards, so the screen can only
     * show "connected" if a live binder actually appeared (FR-18).
     */
    private fun startManagerService(manager: ElevatedManager) {
        if (startingManager != null) return
        startingManager = manager
        showStartingDialog()

        lifecycleScope.launch {
            sendStartBroadcast(manager)
            var started = awaitBinder()

            if (!started) {
                sendBinderRequestBroadcast(manager)
                started = awaitBinder()
            }

            startingDialog?.dismiss()
            startingDialog = null
            startingManager = null

            if (!started) {
                // Step three: hand the user the one thing that can still change the outcome.
                Toast.makeText(
                    this@ElevatedAccessActivity,
                    R.string.shizuku_start_failed,
                    Toast.LENGTH_LONG
                ).show()
                openManagerApp(manager)
            }

            refresh()
        }
    }

    private fun showStartingDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_shizuku_starting, null)
        startingDialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .setCancelable(false)
            .create()
            .also { dialog ->
                dialog.show()
                dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            }
    }

    /**
     * Step one of FR-16 — ask the manager to start its daemon.
     *
     * Null for Porter, and null means "skip", not "try a guess": see [startManagerService].
     */
    private fun sendStartBroadcast(manager: ElevatedManager) {
        // Action to the receiver that handles it. Every one of these receivers is `exported`, which
        // is why an explicit component is enough and no permission is needed to address it.
        val (action, receiverClass) = when (manager) {
            ElevatedManager.PORTER -> return
            ElevatedManager.SHIZUKU ->
                "moe.shizuku.privileged.api.START" to
                    "${manager.packageName}.receiver.ManualStartReceiver"

            ElevatedManager.SHEVERY ->
                "com.hamondev.shevery.START" to
                    "${manager.packageName}.receiver.ManualStartReceiver"
        }

        try {
            sendBroadcast(
                Intent(action).setComponent(ComponentName(manager.packageName, receiverClass))
            )
        } catch (e: Exception) {
            // A manager that has no such receiver simply does not answer. That is the case the
            // bounded wait is for, not an error worth surfacing.
            e.printStackTrace()
        }
    }

    /**
     * Step two of FR-16 — ask the manager to hand over a binder it may already hold.
     *
     * Both actions are the ones the managers' own SDKs broadcast to request a binder from a process
     * that cannot reach their provider directly: `ShizukuProvider.requestBinderForNonProviderProcess`
     * sends `moe.shizuku.api.action.BINDER_RECEIVED` to the Shizuku package, and Porter's
     * `PorterApiProvider` sends the same shape of intent under `eu.darken.porter.sdk.action.*`.
     *
     * The literals are intentional. They are constants published by libraries that are not a
     * compile-time dependency of this class, and writing them out keeps that visible at the point of
     * use rather than hiding it in an import that would not exist.
     */
    private fun sendBinderRequestBroadcast(manager: ElevatedManager) {
        val action = when (manager) {
            ElevatedManager.PORTER -> "eu.darken.porter.sdk.action.BINDER_RECEIVED"
            ElevatedManager.SHIZUKU,
            ElevatedManager.SHEVERY -> "moe.shizuku.api.action.BINDER_RECEIVED"
        }
        try {
            sendBroadcast(Intent(action).setPackage(manager.packageName))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** FR-17 — a bounded wait for a binder, suspending rather than blocking. */
    private suspend fun awaitBinder(): Boolean {
        repeat(BINDER_WAIT_STEPS) {
            delay(BINDER_WAIT_INTERVAL_MS)
            if (probe.isServiceBound()) return true
        }
        return false
    }

    private fun ElevatedManager.toDownloadableApp(): ElevatedAppDownloadManager.ElevatedApp =
        when (this) {
            ElevatedManager.PORTER -> ElevatedAppDownloadManager.ElevatedApp.PORTER
            ElevatedManager.SHIZUKU -> ElevatedAppDownloadManager.ElevatedApp.SHIZUKU
            ElevatedManager.SHEVERY -> ElevatedAppDownloadManager.ElevatedApp.SHEVERY
        }

    // ── Daemon Stopping ─────────────────────────────────────────────────────

    private fun confirmStopDaemon(hero: ManagerState) {
        val managerTitle = getString(hero.manager.titleRes)
        val msg = getString(R.string.shizuku_dialog_stop_daemon_desc, managerTitle)

        MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setTitle(R.string.shizuku_dialog_stop_daemon_title)
            .setMessage(msg)
            .setPositiveButton(R.string.shizuku_btn_stop_daemon) { _, _ ->
                stopDaemon(hero)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun stopDaemon(hero: ManagerState) {
        lifecycleScope.launch {
            val managerTitle = getString(hero.manager.titleRes)

            withContext(Dispatchers.IO) {
                val killScript = "daemons=\"\"; " +
                    "for p in /proc/[0-9]*; do " +
                    "[ -d \"\$p\" ] || continue; " +
                    "cmd=\$(cat \"\$p/cmdline\" 2>/dev/null | tr '\\0' ' '); " +
                    "comm=\$(cat \"\$p/comm\" 2>/dev/null); " +
                    "case \"\$cmd \$comm\" in " +
                    "*porter_server*|*eu.darken.porter.server*|*shizuku_server*|*moe.shizuku.server*|*com.hamondev.shevery*) " +
                    "daemons=\"\$daemons \${p##*/}\";; " +
                    "esac; done; " +
                    "if [ -n \"\$daemons\" ]; then nohup sh -c \"sleep 1; kill -9 \$daemons\" >/dev/null 2>&1 & fi; " +
                    "killall -9 porter_server shizuku_server 2>/dev/null; " +
                    "kill -9 \$(pidof porter_server 2>/dev/null) 2>/dev/null; " +
                    "kill -9 \$(pidof shizuku_server 2>/dev/null) 2>/dev/null; " +
                    "am force-stop eu.darken.porter moe.shizuku.privileged.api com.hamondev.shevery 2>/dev/null"

                // 1. Terminate daemon processes via elevated Shizuku shell
                try {
                    ShizukuShellWrapper.runCommand(killScript)
                } catch (e: Throwable) {
                    Log.w("ElevatedAccess", "Shizuku shell kill error: ${e.message}")
                }

                // 2. Terminate daemon processes via Root if root shell is available
                try {
                    if (RootShellWrapper.isAuthorized(this@ElevatedAccessActivity)) {
                        RootShellWrapper.runCommand(killScript)
                    }
                } catch (e: Throwable) {
                    Log.w("ElevatedAccess", "Root shell kill error: ${e.message}")
                }

                // 3. Request exit on Shizuku API
                try {
                    Shizuku.exit()
                } catch (e: Throwable) {
                    Log.w("ElevatedAccess", "Shizuku.exit() error: ${e.message}")
                }

                delay(1500)
            }

            // 4. Verify whether daemon was stopped
            val stillRunning = withContext(Dispatchers.IO) { probe.isServiceBound() }
            if (!stillRunning) {
                try {
                    Shizuku.onBinderReceived(null, packageName)
                } catch (e: Throwable) {
                    Log.w("ElevatedAccess", "Binder detach error: ${e.message}")
                }
                Toast.makeText(
                    this@ElevatedAccessActivity,
                    getString(R.string.shizuku_daemon_stopped, managerTitle),
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(
                    this@ElevatedAccessActivity,
                    getString(R.string.shizuku_daemon_stop_failed, managerTitle),
                    Toast.LENGTH_LONG
                ).show()
                openManagerApp(hero.manager)
            }
            refresh()
        }
    }

    // ── Per-Application Allow App Access Toggle ───────────────────────────────

    private fun onToggleAppAccess(manager: ElevatedManager, enable: Boolean) {
        lifecycleScope.launch {
            val managerTitle = getString(manager.titleRes)
            if (enable) {
                withContext(Dispatchers.IO) {
                    try {
                        ShizukuShellWrapper.runCommand("pm grant $packageName ${manager.permission}")
                        if (RootShellWrapper.isAuthorized(this@ElevatedAccessActivity)) {
                            RootShellWrapper.runCommand("pm grant $packageName ${manager.permission}")
                        }
                    } catch (e: Throwable) {
                        Log.w("ElevatedAccess", "pm grant error: ${e.message}")
                    }

                    try {
                        val grantJsonScript = "sed -i '/za\\.kilowatch\\.ultimatefilemanager/s/\"flags\":[0-9]*/\"flags\":2/' /data/user_de/0/com.android.shell/porter.json 2>/dev/null; " +
                            "sed -i '/za\\.kilowatch\\.ultimatefilemanager/s/\"flags\":[0-9]*/\"flags\":2/' /data/user_de/0/com.android.shell/shizuku.json 2>/dev/null"
                        ShizukuShellWrapper.runCommand(grantJsonScript)
                        if (RootShellWrapper.isAuthorized(this@ElevatedAccessActivity)) {
                            RootShellWrapper.runCommand(grantJsonScript)
                        }
                    } catch (e: Throwable) {
                        Log.w("ElevatedAccess", "config json update error: ${e.message}")
                    }
                }

                val isAuth = withContext(Dispatchers.IO) { probe.isAuthorized() }
                if (!isAuth) {
                    val requested = ShizukuShellWrapper.requestPermissionSafely(permissionRequestCode, this@ElevatedAccessActivity)
                    if (!requested) {
                        launchManagerAuthorization(manager)
                    }
                } else {
                    Toast.makeText(
                        this@ElevatedAccessActivity,
                        getString(R.string.shizuku_app_access_granted_toast, managerTitle),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } else {
                withContext(Dispatchers.IO) {
                    try {
                        ShizukuShellWrapper.runCommand("pm revoke $packageName ${manager.permission}")
                        if (RootShellWrapper.isAuthorized(this@ElevatedAccessActivity)) {
                            RootShellWrapper.runCommand("pm revoke $packageName ${manager.permission}")
                        }
                    } catch (e: Throwable) {
                        Log.w("ElevatedAccess", "pm revoke error: ${e.message}")
                    }

                    try {
                        val revokeJsonScript = "sed -i '/za\\.kilowatch\\.ultimatefilemanager/s/\"flags\":[0-9]*/\"flags\":4/' /data/user_de/0/com.android.shell/porter.json 2>/dev/null; " +
                            "sed -i '/za\\.kilowatch\\.ultimatefilemanager/s/\"flags\":[0-9]*/\"flags\":4/' /data/user_de/0/com.android.shell/shizuku.json 2>/dev/null"
                        ShizukuShellWrapper.runCommand(revokeJsonScript)
                        if (RootShellWrapper.isAuthorized(this@ElevatedAccessActivity)) {
                            RootShellWrapper.runCommand(revokeJsonScript)
                        }
                    } catch (e: Throwable) {
                        Log.w("ElevatedAccess", "config json update error: ${e.message}")
                    }

                    try {
                        Shizuku.onBinderReceived(null, packageName)
                    } catch (e: Throwable) {
                        Log.w("ElevatedAccess", "Binder detach error: ${e.message}")
                    }
                }

                launchManagerAuthorization(manager)

                Toast.makeText(
                    this@ElevatedAccessActivity,
                    getString(R.string.shizuku_app_access_revoked, managerTitle),
                    Toast.LENGTH_SHORT
                ).show()
            }
            refresh()
        }
    }

    private fun launchManagerAuthorization(manager: ElevatedManager) {
        when (manager) {
            ElevatedManager.SHIZUKU -> {
                try {
                    val intent = Intent().setComponent(
                        ComponentName("moe.shizuku.privileged.api", "moe.shizuku.manager.authorization.AuthorizationActivity")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                } catch (_: Throwable) {
                    openManagerApp(manager)
                }
            }
            ElevatedManager.PORTER -> {
                try {
                    val (code, _) = ShizukuShellWrapper.runCommand("am start -n eu.darken.porter/.management.ApplicationManagementActivity")
                    if (code != 0) {
                        openManagerApp(manager)
                    }
                } catch (_: Throwable) {
                    openManagerApp(manager)
                }
            }
            ElevatedManager.SHEVERY -> {
                openManagerApp(manager)
            }
        }
    }

    private companion object {
        /** How many times [awaitBinder] re-checks before giving up on one attempt. */
        const val BINDER_WAIT_STEPS = 10

        /** Gap between those checks. 10 × 200 ms ≈ 2 s per attempt, ~4 s for the whole chain. */
        const val BINDER_WAIT_INTERVAL_MS = 200L

        /** 25 % — the alpha the hero card's stroke carried before this screen had a palette. */
        const val HERO_STROKE_ALPHA = 0x40
    }
}
