package za.kilowatch.ultimatefilemanager.support

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SupportEmailPrefsTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("support_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun initialStateIsDisabledAndEmpty() {
        assertFalse(SupportEmailPrefs.isEnabled(context))
        assertEquals("", SupportEmailPrefs.getEmail(context))
        assertFalse(SupportEmailPrefs.isEmailRemembered(context))
    }

    @Test
    fun saveEmailEnablesAndStores() {
        SupportEmailPrefs.saveEmail(context, "user@example.com")

        assertTrue(SupportEmailPrefs.isEnabled(context))
        assertEquals("user@example.com", SupportEmailPrefs.getEmail(context))
        assertTrue(SupportEmailPrefs.isEmailRemembered(context))
    }

    @Test
    fun enabledWithoutEmailIsNotRemembered() {
        SupportEmailPrefs.setEnabled(context, true)

        assertTrue(SupportEmailPrefs.isEnabled(context))
        assertEquals("", SupportEmailPrefs.getEmail(context))
        assertFalse(SupportEmailPrefs.isEmailRemembered(context))
    }

    @Test
    fun setEnabledTogglesPreferenceWithoutTouchingEmail() {
        SupportEmailPrefs.saveEmail(context, "user@example.com")
        SupportEmailPrefs.setEnabled(context, false)

        assertFalse(SupportEmailPrefs.isEnabled(context))
        assertEquals("user@example.com", SupportEmailPrefs.getEmail(context))
        assertFalse(SupportEmailPrefs.isEmailRemembered(context))
    }

    @Test
    fun purgeDisablesAndClearsEmail() {
        SupportEmailPrefs.saveEmail(context, "user@example.com")
        SupportEmailPrefs.purge(context)

        assertFalse(SupportEmailPrefs.isEnabled(context))
        assertEquals("", SupportEmailPrefs.getEmail(context))
        assertFalse(SupportEmailPrefs.isEmailRemembered(context))
    }

    @Test
    fun saveEmailOverwritesPreviousValue() {
        SupportEmailPrefs.saveEmail(context, "old@example.com")
        SupportEmailPrefs.saveEmail(context, "new@example.com")

        assertEquals("new@example.com", SupportEmailPrefs.getEmail(context))
    }
}
