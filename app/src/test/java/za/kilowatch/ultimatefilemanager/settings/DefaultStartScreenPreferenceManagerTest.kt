package za.kilowatch.ultimatefilemanager.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DefaultStartScreenPreferenceManagerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("default_start_screen_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun testDefaultStartScreenOnMobileIsLastOpened() {
        // Robolectric by default runs as a mobile/phone environment
        val defaultId = DefaultStartScreenPreferenceManager.getStartScreenId(context)
        assertEquals(DefaultStartScreenPreferenceManager.ID_LAST_OPENED, defaultId)
    }

    @Test
    fun testSetAndGetStartScreenId() {
        DefaultStartScreenPreferenceManager.setStartScreenId(
            context,
            DefaultStartScreenPreferenceManager.ID_TWIN_WINDOW
        )
        assertEquals(
            DefaultStartScreenPreferenceManager.ID_TWIN_WINDOW,
            DefaultStartScreenPreferenceManager.getStartScreenId(context)
        )

        DefaultStartScreenPreferenceManager.setStartScreenId(
            context,
            DefaultStartScreenPreferenceManager.ID_STORAGE_BROWSER
        )
        assertEquals(
            DefaultStartScreenPreferenceManager.ID_STORAGE_BROWSER,
            DefaultStartScreenPreferenceManager.getStartScreenId(context)
        )

        DefaultStartScreenPreferenceManager.setStartScreenId(
            context,
            DefaultStartScreenPreferenceManager.ID_LAST_OPENED
        )
        assertEquals(
            DefaultStartScreenPreferenceManager.ID_LAST_OPENED,
            DefaultStartScreenPreferenceManager.getStartScreenId(context)
        )
    }

    @Test
    fun testLegacyKeyMigration() {
        val prefs = context.getSharedPreferences("default_start_screen_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("default_start_screen", DefaultStartScreenPreferenceManager.ID_FILE_SERVER).commit()

        val id = DefaultStartScreenPreferenceManager.getStartScreenId(context)
        assertEquals(DefaultStartScreenPreferenceManager.ID_FILE_SERVER, id)

        // Verify legacy key is removed and new key is set
        assertEquals(false, prefs.contains("default_start_screen"))
        assertEquals(
            DefaultStartScreenPreferenceManager.ID_FILE_SERVER,
            prefs.getString("default_start_screen_id", null)
        )
    }
}
