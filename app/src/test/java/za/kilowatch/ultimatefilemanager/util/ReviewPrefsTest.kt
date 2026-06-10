package za.kilowatch.ultimatefilemanager.util

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReviewPrefsTest {

    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        prefs = context.getSharedPreferences("review_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    @Test
    fun testInitialState() {
        ReviewPrefs.init(context)
        // Should not show popup immediately after install (waits 7 days)
        assertFalse(ReviewPrefs.shouldShowPopup(context))
    }

    @Test
    fun testShouldShowAfterSevenDays() {
        ReviewPrefs.init(context)
        val sevenDaysAgo = System.currentTimeMillis() - (8 * 24 * 60 * 60 * 1000L)
        prefs.edit().putLong("install_date", sevenDaysAgo).commit()
        
        assertTrue(ReviewPrefs.shouldShowPopup(context))
    }

    @Test
    fun testNeverAsk() {
        ReviewPrefs.init(context)
        val sevenDaysAgo = System.currentTimeMillis() - (8 * 24 * 60 * 60 * 1000L)
        prefs.edit().putLong("install_date", sevenDaysAgo).commit()
        
        ReviewPrefs.onNoThanks(context)
        assertFalse(ReviewPrefs.shouldShowPopup(context))
    }

    @Test
    fun testRateUsTapped() {
        ReviewPrefs.init(context)
        val sevenDaysAgo = System.currentTimeMillis() - (8 * 24 * 60 * 60 * 1000L)
        prefs.edit().putLong("install_date", sevenDaysAgo).commit()
        
        ReviewPrefs.onRateUsTapped(context)
        assertFalse(ReviewPrefs.shouldShowPopup(context))
    }

    @Test
    fun testSnooze() {
        ReviewPrefs.init(context)
        val sevenDaysAgo = System.currentTimeMillis() - (8 * 24 * 60 * 60 * 1000L)
        prefs.edit().putLong("install_date", sevenDaysAgo).commit()
        
        ReviewPrefs.onMaybeLater(context)
        assertFalse(ReviewPrefs.shouldShowPopup(context))
        
        // After 15 days, it should show again
        val fifteenDaysLater = System.currentTimeMillis() + (15 * 24 * 60 * 60 * 1000L)
        // We can't easily Mock System.currentTimeMillis() without PowerMock/MockK, 
        // but we can manually adjust the PREF for test purposes if we knew the key.
        // For simple logic check, we verify it's snoozed now.
    }
}
