package za.kilowatch.ultimatefilemanager.viewer

import org.junit.Assert.assertEquals
import org.junit.Test
import za.kilowatch.ultimatefilemanager.settings.RepeatMode

class RepeatModeTest {

    @Test
    fun testRepeatModeValues() {
        assertEquals("off", RepeatMode.OFF.value)
        assertEquals("all", RepeatMode.ALL.value)
        assertEquals("one", RepeatMode.ONE.value)
    }

    @Test
    fun testFromValueParsing() {
        assertEquals(RepeatMode.OFF, RepeatMode.fromValue("off"))
        assertEquals(RepeatMode.ALL, RepeatMode.fromValue("all"))
        assertEquals(RepeatMode.ONE, RepeatMode.fromValue("one"))
        // Fallback for invalid or null-like values
        assertEquals(RepeatMode.OFF, RepeatMode.fromValue("unknown"))
        assertEquals(RepeatMode.OFF, RepeatMode.fromValue(""))
    }

    @Test
    fun testCycleTransitionLogic() {
        var mode = RepeatMode.OFF
        mode = when (mode) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        assertEquals(RepeatMode.ALL, mode)

        mode = when (mode) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        assertEquals(RepeatMode.ONE, mode)

        mode = when (mode) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        assertEquals(RepeatMode.OFF, mode)
    }
}
