package za.kilowatch.ultimatefilemanager.ui.elevated

import android.graphics.drawable.Drawable

/**
 * What UFM can say about one manager's daemon and its own authorization (FR-12).
 */
enum class ServiceState {
    /** Not installed — the card offers a download instead of an action. */
    NOT_INSTALLED,

    /** Installed, daemon not running. The card names *this* manager (FR-13). */
    NOT_RUNNING,

    /** Daemon running, UFM not authorized. The card offers authorize (FR-15). */
    RUNNING_UNAUTHORIZED,

    /** Running and authorized. No start/authorize action is offered (FR-19). */
    CONNECTED,

    /**
     * Installed, but UFM cannot observe this manager's state.
     *
     * The SDK routes to exactly one channel per process, chosen from *install* state (see
     * [SystemElevatedAccessProbe.activeChannel]), so a manager sitting on a channel UFM is not
     * bound to has no observable daemon or authorization state.
     *
     * This is FR-25's open question (plan R-2). The honest answer is to say nothing rather than
     * display a state that was guessed: the whole point of this screen is that the user acts on
     * what it says, and a wrong "service not running" would send them chasing a daemon that is
     * already up. FR-27 explicitly permits reporting uncertainty here.
     */
    UNKNOWN
}

/**
 * One manager card's worth of state.
 */
data class ManagerState(
    val manager: ElevatedManager,
    val installed: Boolean,
    /**
     * The installed app's own launcher icon, or null.
     *
     * Null both when not installed and when the icon could not be read — the renderer falls back to
     * [ElevatedManager.brandIconRes] in either case, so the slot is never empty (FR-10).
     */
    val launcherIcon: Drawable?,
    val serviceState: ServiceState,
    /** True for the single manager the service adapter is bound to (FR-23/FR-24). */
    val isActiveBackend: Boolean
)

/**
 * The whole screen's state. Immutable; produced by [ElevatedAccessResolver], consumed by the
 * renderer. Nothing here queries the system and nothing here touches a view.
 */
data class ElevatedAccessUiState(
    /** Detection still running — the screen must not look settled (NFR-07 / E-18). */
    val loading: Boolean,
    /** Always all three, always in [ElevatedManager] declaration order (FR-01/FR-05). */
    val managers: List<ManagerState>,
    /**
     * FR-26 / E-17: the selection is holding out for an installed-but-stopped Porter while another
     * manager is installed and running. Rendered as inline text on the **Porter card**, not as a
     * dialog or screen-level banner.
     */
    val waitingForPorter: Boolean
) {
    companion object {
        /**
         * The pre-detection state: all three cards present in order, nothing known yet.
         *
         * Built from the enum rather than hardcoded so the loading screen cannot disagree with the
         * resolved one about which managers exist or what order they go in.
         */
        fun loading(): ElevatedAccessUiState = ElevatedAccessUiState(
            loading = true,
            managers = ElevatedManager.entries.map { manager ->
                ManagerState(
                    manager = manager,
                    installed = false,
                    launcherIcon = null,
                    serviceState = ServiceState.NOT_INSTALLED,
                    isActiveBackend = false
                )
            },
            waitingForPorter = false
        )
    }
}
