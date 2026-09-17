package za.kilowatch.ultimatefilemanager.ui.elevated

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Turns system facts into the screen's state. Pure decision logic over [ElevatedAccessProbe] — no
 * Android calls, no views, so every branch below is unit-testable (see `ElevatedAccessResolverTest`).
 *
 * ## Why a manager's state is a property of its *channel*
 *
 * Three managers, two channels. Porter publishes to a channel of its own; Shizuku and Shevery both
 * speak the original Shizuku protocol over one shared channel, and nothing at the client end can say
 * which of the two supplied the binder. So a manager that is installed *and* on the channel the SDK
 * is bound to gets the channel's real observed state, and a manager on any other channel gets
 * [ServiceState.UNKNOWN].
 *
 * The consequence worth stating plainly: **when the Shizuku channel is active and both Shizuku and
 * Shevery are installed, both cards show the channel's state.** That is not a guess about either
 * app — the reading is genuinely true of the channel they share — and each card's button opens the
 * app that card names, so the user is never sent to a different manager than the one they tapped.
 *
 * [FR-21]/[FR-24] ("which manager is active") is therefore answered at install granularity, not
 * daemon granularity, because that is the granularity the SDK actually reports: `PorterClient`
 * picks its backend by resolving who *defines* `eu.darken.porter.permission.API_V23`, which is
 * fixed at install time. It cannot be upgraded to a per-daemon answer from the client side.
 */
class ElevatedAccessResolver(private val probe: ElevatedAccessProbe) {

    /**
     * Resolves state. Callable from the main thread, but [forContext] call sites should prefer
     * [resolveOffMainThread] — the probe does binder IPC and reads launcher icons.
     */
    fun resolve(): ElevatedAccessUiState {
        val installed = ElevatedManager.entries.associateWith { probe.isInstalled(it.packageName) }
        val bound = probe.isServiceBound()
        // Guarded by `bound`: checkSelfPermission() throws IllegalStateException when unattached.
        val authorized = bound && probe.isAuthorized()
        val channel = probe.activeChannel()

        // Channels are disjoint, and each manager belongs to exactly one.
        val onActiveChannel: Set<ElevatedManager> = when (channel) {
            ActiveChannel.PORTER -> setOf(ElevatedManager.PORTER)
            ActiveChannel.SHIZUKU -> ElevatedManager.entries
                .filterTo(mutableSetOf()) { it != ElevatedManager.PORTER }
        }

        val managers = ElevatedManager.entries.map { manager ->
            val isInstalled = installed.getValue(manager)
            // An uninstalled manager has no channel to sit on, so this is false for it.
            val isActive = isInstalled && manager in onActiveChannel

            val state = when {
                !isInstalled -> ServiceState.NOT_INSTALLED

                // Installed, but on a channel we are not bound to — we cannot see its state.
                !isActive -> ServiceState.UNKNOWN

                // If the app supplies Shizuku's protocol it can be started by the user
                // independently of UFM either way, so the actionable branch is what we show.
                !bound -> ServiceState.NOT_RUNNING

                !authorized -> ServiceState.RUNNING_UNAUTHORIZED

                else -> ServiceState.CONNECTED
            }

            ManagerState(
                manager = manager,
                installed = isInstalled,
                launcherIcon = if (isInstalled) probe.launcherIcon(manager.packageName) else null,
                serviceState = state,
                isActiveBackend = isActive
            )
        }

        // FR-26 / E-17: Porter is installed and the SDK is routed to Porter's channel, yet nothing
        // is bound. Because Porter's presence alone pins the SDK to its channel, an installed but
        // stopped Porter genuinely blocks Shizuku and Shevery from taking over — which is exactly
        // the situation the card has to explain, and why both remedies are worth offering.
        val waitingForPorter = installed.getValue(ElevatedManager.PORTER) &&
            channel == ActiveChannel.PORTER &&
            !bound

        return ElevatedAccessUiState(
            loading = false,
            managers = managers,
            waitingForPorter = waitingForPorter
        )
    }

    companion object {
        fun forContext(context: Context): ElevatedAccessResolver =
            ElevatedAccessResolver(SystemElevatedAccessProbe(context.applicationContext))

        /**
         * [resolve] on [Dispatchers.IO].
         *
         * Kept separate from [resolve] so the logic stays synchronously testable while call sites
         * still keep binder IPC and icon loading off the main thread.
         */
        suspend fun resolveOffMainThread(context: Context): ElevatedAccessUiState =
            withContext(Dispatchers.IO) { forContext(context).resolve() }
    }
}
