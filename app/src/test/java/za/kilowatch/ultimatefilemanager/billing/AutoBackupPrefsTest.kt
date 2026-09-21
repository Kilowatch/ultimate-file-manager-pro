package za.kilowatch.ultimatefilemanager.billing

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AutoBackupPrefsTest {

    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        prefs = context.getSharedPreferences("auto_backup_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        AutoBackupPrefs.resetForTesting()
        AutoBackupPrefs.init(context)
    }

    @Test
    fun testFirstBootFlagsCachingAndApply() {
        AutoBackupPrefs.init(context)
        assertFalse(AutoBackupPrefs.isBackupFilesPresentOnFirstBoot(context))

        AutoBackupPrefs.setBackupFilesPresentOnFirstBoot(context, true)
        assertTrue(AutoBackupPrefs.isBackupFilesPresentOnFirstBoot(context))
        assertTrue(prefs.getBoolean("backup_files_present_on_first_boot", false))

        AutoBackupPrefs.setBackupFilesPresentOnFirstBoot(context, false)
        assertFalse(AutoBackupPrefs.isBackupFilesPresentOnFirstBoot(context))
        assertFalse(prefs.getBoolean("backup_files_present_on_first_boot", true))
    }

    @Test
    fun testRestorePromptShownCachingAndApply() {
        AutoBackupPrefs.init(context)
        assertFalse(AutoBackupPrefs.isRestorePromptShown(context))

        AutoBackupPrefs.setRestorePromptShown(context)
        assertTrue(AutoBackupPrefs.isRestorePromptShown(context))
        assertTrue(prefs.getBoolean("auto_restore_prompt_shown", false))
    }

    @Test
    fun testInitPreWarm() {
        prefs.edit()
            .putBoolean("backup_files_present_on_first_boot", true)
            .putBoolean("auto_restore_prompt_shown", true)
            .commit()

        AutoBackupPrefs.init(context)
        assertTrue(AutoBackupPrefs.isBackupFilesPresentOnFirstBoot(context))
        assertTrue(AutoBackupPrefs.isRestorePromptShown(context))
    }
}
