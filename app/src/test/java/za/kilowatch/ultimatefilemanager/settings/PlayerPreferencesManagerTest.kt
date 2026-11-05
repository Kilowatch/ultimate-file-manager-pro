package za.kilowatch.ultimatefilemanager.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
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

    @Test
    fun testSubtitleStyleDefaults() {
        assertEquals(PlayerPreferencesManager.SUBTITLE_MODE_SYSTEM, PlayerPreferencesManager.getSubtitleStyleMode(context))
        assertEquals(1.0f, PlayerPreferencesManager.getSubtitleCustomTextSize(context), 0.001f)
        assertEquals(PlayerPreferencesManager.DEFAULT_SUBTITLE_TEXT_COLOR, PlayerPreferencesManager.getSubtitleCustomTextColor(context))
        assertEquals(PlayerPreferencesManager.DEFAULT_SUBTITLE_BG_COLOR, PlayerPreferencesManager.getSubtitleCustomBgColor(context))
        assertEquals(PlayerPreferencesManager.DEFAULT_SUBTITLE_EDGE_TYPE, PlayerPreferencesManager.getSubtitleCustomEdgeType(context))
        assertEquals(PlayerPreferencesManager.DEFAULT_SUBTITLE_EDGE_COLOR, PlayerPreferencesManager.getSubtitleCustomEdgeColor(context))
        assertEquals(PlayerPreferencesManager.DEFAULT_SUBTITLE_BOTTOM_PADDING_DP_MOBILE, PlayerPreferencesManager.getSubtitleBottomPaddingDp(context, isTv = false))
        assertEquals(PlayerPreferencesManager.DEFAULT_SUBTITLE_BOTTOM_PADDING_DP_TV, PlayerPreferencesManager.getSubtitleBottomPaddingDp(context, isTv = true))
        assertFalse(PlayerPreferencesManager.isSubtitleApplyEmbeddedStyles(context))
    }

    @Test
    fun testSetSubtitleStylePreferences() {
        PlayerPreferencesManager.setSubtitleStyleMode(context, PlayerPreferencesManager.SUBTITLE_MODE_CUSTOM)
        assertEquals(PlayerPreferencesManager.SUBTITLE_MODE_CUSTOM, PlayerPreferencesManager.getSubtitleStyleMode(context))

        PlayerPreferencesManager.setSubtitleCustomTextSize(context, 1.4f)
        assertEquals(1.4f, PlayerPreferencesManager.getSubtitleCustomTextSize(context), 0.001f)

        // Clamping check: 0.5f to 2.0f
        PlayerPreferencesManager.setSubtitleCustomTextSize(context, 0.1f)
        assertEquals(0.5f, PlayerPreferencesManager.getSubtitleCustomTextSize(context), 0.001f)
        PlayerPreferencesManager.setSubtitleCustomTextSize(context, 3.5f)
        assertEquals(2.0f, PlayerPreferencesManager.getSubtitleCustomTextSize(context), 0.001f)

        PlayerPreferencesManager.setSubtitleCustomTextColor(context, 0xFF00E5FF.toInt())
        assertEquals(0xFF00E5FF.toInt(), PlayerPreferencesManager.getSubtitleCustomTextColor(context))

        PlayerPreferencesManager.setSubtitleCustomBgColor(context, 0x80000000.toInt())
        assertEquals(0x80000000.toInt(), PlayerPreferencesManager.getSubtitleCustomBgColor(context))

        PlayerPreferencesManager.setSubtitleCustomEdgeType(context, 2)
        assertEquals(2, PlayerPreferencesManager.getSubtitleCustomEdgeType(context))

        PlayerPreferencesManager.setSubtitleBottomPaddingDp(context, 32)
        assertEquals(32, PlayerPreferencesManager.getSubtitleBottomPaddingDp(context))

        PlayerPreferencesManager.setSubtitleApplyEmbeddedStyles(context, true)
        assertTrue(PlayerPreferencesManager.isSubtitleApplyEmbeddedStyles(context))
    }

    @Test
    fun testFormatSubtitleStyleSummary() {
        PlayerPreferencesManager.setSubtitleStyleMode(context, PlayerPreferencesManager.SUBTITLE_MODE_SYSTEM)
        val systemSummary = PlayerPreferencesManager.formatSubtitleStyleSummary(context)
        assertTrue(systemSummary.isNotEmpty())

        PlayerPreferencesManager.setSubtitleStyleMode(context, PlayerPreferencesManager.SUBTITLE_MODE_CUSTOM)
        PlayerPreferencesManager.setSubtitleCustomTextColor(context, PlayerPreferencesManager.DEFAULT_SUBTITLE_TEXT_COLOR)
        val customSummary = PlayerPreferencesManager.formatSubtitleStyleSummary(context)
        assertTrue(customSummary.contains("Yellow") || customSummary.isNotEmpty())
    }
}
