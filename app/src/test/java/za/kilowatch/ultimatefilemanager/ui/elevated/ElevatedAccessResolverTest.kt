package za.kilowatch.ultimatefilemanager.ui.elevated

import android.graphics.drawable.Drawable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [ElevatedAccessResolver]'s decision table.
 *
 * Every case here is a rule the screen is allowed to assert to the user, so the ones that matter
 * most are the ones asserting the resolver *declines* to speak: a manager on a channel UFM is not
 * bound to must come back [ServiceState.UNKNOWN] rather than a plausible-looking guess.
 *
 * Pure JVM — [ElevatedAccessProbe] is the only seam, and the fake below never returns an icon, so
 * nothing here touches a real Android object.
 */
class ElevatedAccessResolverTest {

    private class FakeProbe(
        private val installedPackages: Set<String> = emptySet(),
        private val bound: Boolean = false,
        private val authorized: Boolean = false,
        private val channel: ActiveChannel = ActiveChannel.SHIZUKU
    ) : ElevatedAccessProbe {
        var launcherIconQueries = 0
            private set

        override fun isInstalled(packageName: String) = packageName in installedPackages

        override fun launcherIcon(packageName: String): Drawable? {
            launcherIconQueries++
            return null
        }

        override fun isServiceBound() = bound

        override fun isAuthorized() = authorized

        override fun activeChannel() = channel
    }

    private val porter = ElevatedManager.PORTER.packageName
    private val shizuku = ElevatedManager.SHIZUKU.packageName
    private val shevery = ElevatedManager.SHEVERY.packageName

    private fun ElevatedAccessUiState.stateOf(manager: ElevatedManager): ServiceState =
        managers.first { it.manager == manager }.serviceState

    private fun ElevatedAccessUiState.entry(manager: ElevatedManager): ManagerState =
        managers.first { it.manager == manager }

    // ── Ordering and presence ────────────────────────────────────────────────

    @Test
    fun `porter is declared first and is the recommended manager`() {
        // Display order is declaration order, so this pins the "Porter on top" requirement.
        assertEquals(ElevatedManager.PORTER, ElevatedManager.entries.first())
        assertTrue(ElevatedManager.PORTER.isRecommended)
    }

    @Test
    fun `shevery is last and only porter is recommended`() {
        assertEquals(ElevatedManager.SHEVERY, ElevatedManager.entries.last())
        assertFalse(ElevatedManager.SHIZUKU.isRecommended)
        assertFalse(ElevatedManager.SHEVERY.isRecommended)
    }

    @Test
    fun `all three cards are present in order when nothing is installed`() {
        val state = ElevatedAccessResolver(FakeProbe()).resolve()

        assertEquals(ElevatedManager.entries.toList(), state.managers.map { it.manager })
        assertFalse(state.loading)
        assertTrue(state.managers.all { it.serviceState == ServiceState.NOT_INSTALLED })
        assertTrue(state.managers.none { it.installed })
    }

    @Test
    fun `loading state lists all three managers in order before detection`() {
        val state = ElevatedAccessUiState.loading()

        assertTrue(state.loading)
        assertEquals(ElevatedManager.entries.toList(), state.managers.map { it.manager })
        assertFalse(state.waitingForPorter)
    }

    @Test
    fun `launcher icon is only read for installed managers`() {
        val probe = FakeProbe(installedPackages = setOf(porter))
        ElevatedAccessResolver(probe).resolve()

        // Reading an icon for an uninstalled package throws NameNotFoundException in production;
        // the count asserts the resolver never asks.
        assertEquals(1, probe.launcherIconQueries)
    }

    // ── Porter on its own channel ────────────────────────────────────────────

    @Test
    fun `installed porter with no service reports not running and holds the selection`() {
        val state = ElevatedAccessResolver(
            FakeProbe(
                installedPackages = setOf(porter, shizuku),
                bound = false,
                channel = ActiveChannel.PORTER
            )
        ).resolve()

        assertEquals(ServiceState.NOT_RUNNING, state.stateOf(ElevatedManager.PORTER))
        assertTrue(state.entry(ElevatedManager.PORTER).isActiveBackend)
        // An installed Porter pins the SDK to Porter's channel, so Shizuku genuinely cannot take
        // over. Explaining that is the whole reason this flag exists.
        assertTrue(state.waitingForPorter)
    }

    @Test
    fun `porter service running but unauthorized offers authorization`() {
        val state = ElevatedAccessResolver(
            FakeProbe(
                installedPackages = setOf(porter),
                bound = true,
                authorized = false,
                channel = ActiveChannel.PORTER
            )
        ).resolve()

        assertEquals(ServiceState.RUNNING_UNAUTHORIZED, state.stateOf(ElevatedManager.PORTER))
        assertFalse(state.waitingForPorter)
    }

    @Test
    fun `porter connected stops waiting`() {
        val state = ElevatedAccessResolver(
            FakeProbe(
                installedPackages = setOf(porter),
                bound = true,
                authorized = true,
                channel = ActiveChannel.PORTER
            )
        ).resolve()

        assertEquals(ServiceState.CONNECTED, state.stateOf(ElevatedManager.PORTER))
        assertFalse(state.waitingForPorter)
    }

    @Test
    fun `porter downloaded but not yet installed does not hold the selection`() {
        // Waiting is a claim about an installed-but-stopped Porter. Absent the install, the SDK is
        // on the Shizuku channel and nothing is being held up.
        val state = ElevatedAccessResolver(
            FakeProbe(
                installedPackages = setOf(shizuku),
                bound = true,
                authorized = true,
                channel = ActiveChannel.SHIZUKU
            )
        ).resolve()

        assertEquals(ServiceState.NOT_INSTALLED, state.stateOf(ElevatedManager.PORTER))
        assertFalse(state.waitingForPorter)
    }

    // ── The shared Shizuku / Shevery channel ─────────────────────────────────

    @Test
    fun `porter installed keeps shizuku and shevery unobservable`() {
        val state = ElevatedAccessResolver(
            FakeProbe(
                installedPackages = setOf(porter, shizuku, shevery),
                bound = true,
                authorized = true,
                channel = ActiveChannel.PORTER
            )
        ).resolve()

        // The SDK is reading Porter's channel, so it has no view of either manager's daemon.
        assertEquals(ServiceState.UNKNOWN, state.stateOf(ElevatedManager.SHIZUKU))
        assertEquals(ServiceState.UNKNOWN, state.stateOf(ElevatedManager.SHEVERY))
        assertFalse(state.entry(ElevatedManager.SHIZUKU).isActiveBackend)
        assertFalse(state.entry(ElevatedManager.SHEVERY).isActiveBackend)
    }

    @Test
    fun `with porter absent the installed shizuku manager reports the channel state`() {
        val state = ElevatedAccessResolver(
            FakeProbe(
                installedPackages = setOf(shizuku),
                bound = true,
                authorized = true,
                channel = ActiveChannel.SHIZUKU
            )
        ).resolve()

        assertEquals(ServiceState.CONNECTED, state.stateOf(ElevatedManager.SHIZUKU))
        assertTrue(state.entry(ElevatedManager.SHIZUKU).isActiveBackend)
        // Not installed, so it is reported as such rather than as an unobservable state.
        assertEquals(ServiceState.NOT_INSTALLED, state.stateOf(ElevatedManager.SHEVERY))
        assertFalse(state.waitingForPorter)
    }

    @Test
    fun `shizuku and shevery installed together both report the shared channel state`() {
        val state = ElevatedAccessResolver(
            FakeProbe(
                installedPackages = setOf(shizuku, shevery),
                bound = true,
                authorized = true,
                channel = ActiveChannel.SHIZUKU
            )
        ).resolve()

        // Documents a deliberate decision, not an oversight: the two are indistinguishable from the
        // client side, so both cards report the channel they share. Each card's action opens the app
        // that card names, so the user is never sent to the other manager.
        assertEquals(ServiceState.CONNECTED, state.stateOf(ElevatedManager.SHIZUKU))
        assertEquals(ServiceState.CONNECTED, state.stateOf(ElevatedManager.SHEVERY))
    }

    @Test
    fun `unbound shared channel reports not running for both`() {
        val state = ElevatedAccessResolver(
            FakeProbe(
                installedPackages = setOf(shizuku, shevery),
                bound = false,
                channel = ActiveChannel.SHIZUKU
            )
        ).resolve()

        assertEquals(ServiceState.NOT_RUNNING, state.stateOf(ElevatedManager.SHIZUKU))
        assertEquals(ServiceState.NOT_RUNNING, state.stateOf(ElevatedManager.SHEVERY))
    }

    // ── The authorization guard ──────────────────────────────────────────────

    @Test
    fun `authorization is never claimed while the service is unbound`() {
        // checkSelfPermission() throws IllegalStateException when unattached, so the resolver must
        // not consult it. A probe that reports granted while unbound must still yield NOT_RUNNING.
        val state = ElevatedAccessResolver(
            FakeProbe(
                installedPackages = setOf(porter),
                bound = false,
                authorized = true,
                channel = ActiveChannel.PORTER
            )
        ).resolve()

        assertEquals(ServiceState.NOT_RUNNING, state.stateOf(ElevatedManager.PORTER))
    }

    @Test
    fun `an uninstalled manager is never marked as the active backend`() {
        // The active channel names Porter, but Porter is absent — so no card may claim it is live.
        val state = ElevatedAccessResolver(
            FakeProbe(installedPackages = emptySet(), channel = ActiveChannel.PORTER)
        ).resolve()

        assertTrue(state.managers.none { it.isActiveBackend })
        assertEquals(ServiceState.NOT_INSTALLED, state.stateOf(ElevatedManager.PORTER))
    }
}
