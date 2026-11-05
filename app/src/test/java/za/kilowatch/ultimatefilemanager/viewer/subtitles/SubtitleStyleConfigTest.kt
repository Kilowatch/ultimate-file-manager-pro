package za.kilowatch.ultimatefilemanager.viewer.subtitles

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import za.kilowatch.ultimatefilemanager.settings.PlayerPreferencesManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SubtitleStyleConfigTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("ufm_player_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun testDefaultConfigMatchesPreferences() {
        val config = SubtitleStyleConfig.fromPreferences(context)
        assertEquals(PlayerPreferencesManager.SUBTITLE_MODE_SYSTEM, config.mode)
        assertEquals(PlayerPreferencesManager.DEFAULT_SUBTITLE_TEXT_COLOR, config.textColor)
        assertEquals(1.0f, config.textSizeMultiplier, 0.001f)
        assertFalse(config.applyEmbeddedStyles)
    }

    @Test
    fun testSaveAndRestoreConfig() {
        val custom = SubtitleStyleConfig(
            mode = PlayerPreferencesManager.SUBTITLE_MODE_CUSTOM,
            textSizeMultiplier = 1.3f,
            textColor = 0xFF00E5FF.toInt(),
            backgroundColor = 0x40000000,
            edgeType = 2,
            edgeColor = 0xFF000000.toInt(),
            bottomPaddingDp = 24,
            applyEmbeddedStyles = true
        )
        custom.saveToPreferences(context)

        val loaded = SubtitleStyleConfig.fromPreferences(context)
        assertEquals(PlayerPreferencesManager.SUBTITLE_MODE_CUSTOM, loaded.mode)
        assertEquals(1.3f, loaded.textSizeMultiplier, 0.001f)
        assertEquals(0xFF00E5FF.toInt(), loaded.textColor)
        assertEquals(0x40000000, loaded.backgroundColor)
        assertEquals(2, loaded.edgeType)
        assertEquals(24, loaded.bottomPaddingDp)
        assertTrue(loaded.applyEmbeddedStyles)
    }

    @Test
    fun testResolveSystemStyleFallback() {
        val (style, scale) = SubtitleStyleApplier.resolveSystemStyle(context)
        assertNotNull(style)
        assertTrue(scale in 0.5f..2.0f)
    }
}
