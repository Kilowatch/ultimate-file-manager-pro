package za.kilowatch.ultimatefilemanager.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RootPreferenceManagerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("root_preferences", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun testRootDisabledByDefault() {
        assertFalse("Root access should be disabled by default", RootPreferenceManager.isRootEnabled(context))
    }

    @Test
    fun testSetRootEnabled() {
        RootPreferenceManager.setRootEnabled(context, true)
        assertTrue("Root access should be enabled after setRootEnabled(true)", RootPreferenceManager.isRootEnabled(context))

        RootPreferenceManager.setRootEnabled(context, false)
        assertFalse("Root access should be disabled after setRootEnabled(false)", RootPreferenceManager.isRootEnabled(context))
    }

    @Test
    fun testCloseShellSafeWhenClosed() {
        // Calling closeShell should not throw even if no active shell exists
        RootPreferenceManager.closeShell()
    }
}
