package za.kilowatch.ultimatefilemanager.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlayerPreferencesManagerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Reset prefs
        context.getSharedPreferences("ufm_player_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun testGesturesEnabledDefault() {
        assertTrue("Gestures should be enabled by default", PlayerPreferencesManager.isGesturesEnabled(context))
    }

    @Test
    fun testSetGesturesEnabled() {
        PlayerPreferencesManager.setGesturesEnabled(context, false)
        assertFalse(PlayerPreferencesManager.isGesturesEnabled(context))

        PlayerPreferencesManager.setGesturesEnabled(context, true)
        assertTrue(PlayerPreferencesManager.isGesturesEnabled(context))
    }

    @Test
    fun testSkipLengthDefaults() {
        assertEquals(10, PlayerPreferencesManager.getSkipLengthSeconds(context))
        assertEquals(10_000L, PlayerPreferencesManager.getSkipLengthMs(context))
        assertTrue(PlayerPreferencesManager.isSkipEnabled(context))
    }

    @Test
    fun testButtonToastsEnabledDefault() {
        assertFalse("Button toasts should be disabled by default", PlayerPreferencesManager.isButtonToastsEnabled(context))
    }

    @Test
    fun testSetButtonToastsEnabled() {
        PlayerPreferencesManager.setButtonToastsEnabled(context, true)
        assertTrue(PlayerPreferencesManager.isButtonToastsEnabled(context))

        PlayerPreferencesManager.setButtonToastsEnabled(context, false)
        assertFalse(PlayerPreferencesManager.isButtonToastsEnabled(context))
    }
}
