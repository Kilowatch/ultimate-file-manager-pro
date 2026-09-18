package za.kilowatch.ultimatefilemanager.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GridTextPositionPreferenceManagerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("grid_text_position_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun testDefaultPositionIsBelow() {
        assertEquals(GridTextPositionPreferenceManager.Position.BELOW, GridTextPositionPreferenceManager.getPosition(context))
        assertTrue(GridTextPositionPreferenceManager.isBelow(context))
    }

    @Test
    fun testSetPositionOverlay() {
        GridTextPositionPreferenceManager.setPosition(context, GridTextPositionPreferenceManager.Position.OVERLAY)
        assertEquals(GridTextPositionPreferenceManager.Position.OVERLAY, GridTextPositionPreferenceManager.getPosition(context))
        assertFalse(GridTextPositionPreferenceManager.isBelow(context))
    }

    @Test
    fun testSetPositionBackToBelow() {
        GridTextPositionPreferenceManager.setPosition(context, GridTextPositionPreferenceManager.Position.OVERLAY)
        assertFalse(GridTextPositionPreferenceManager.isBelow(context))

        GridTextPositionPreferenceManager.setPosition(context, GridTextPositionPreferenceManager.Position.BELOW)
        assertEquals(GridTextPositionPreferenceManager.Position.BELOW, GridTextPositionPreferenceManager.getPosition(context))
        assertTrue(GridTextPositionPreferenceManager.isBelow(context))
    }

    @Test
    fun testFromIdFallback() {
        assertEquals(GridTextPositionPreferenceManager.Position.BELOW, GridTextPositionPreferenceManager.Position.fromId(null))
        assertEquals(GridTextPositionPreferenceManager.Position.BELOW, GridTextPositionPreferenceManager.Position.fromId("unknown"))
        assertEquals(GridTextPositionPreferenceManager.Position.OVERLAY, GridTextPositionPreferenceManager.Position.fromId("overlay"))
        assertEquals(GridTextPositionPreferenceManager.Position.BELOW, GridTextPositionPreferenceManager.Position.fromId("below"))
    }
}
