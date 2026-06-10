package za.kilowatch.ultimatefilemanager

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import za.kilowatch.ultimatefilemanager.onboarding.WelcomeActivity
import za.kilowatch.ultimatefilemanager.storage.StorageBrowserActivity
import za.kilowatch.ultimatefilemanager.storage.TwinWindowActivity
import za.kilowatch.ultimatefilemanager.settings.FontSizeHelper
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper

/**
 * Main Activity — serves as a routing entry point.
 * Checks if onboarding is complete:
 *   - No → redirects to WelcomeActivity
 *   - Yes → redirects to StorageBrowserActivity
 */
class MainActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        za.kilowatch.ultimatefilemanager.settings.ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val prefs = getSharedPreferences("acceptance_prefs", Context.MODE_PRIVATE)
        val onboardingComplete = prefs.getBoolean("onboarding_complete", false)

        val destination = if (onboardingComplete) {
            // Check if Twin Window is set as default startup
            val isDefaultTwinWindow = za.kilowatch.ultimatefilemanager.settings.TwinWindowPreferenceManager.isDefaultStartup(this)
            if (isDefaultTwinWindow) {
                Intent(this, TwinWindowActivity::class.java).also {
                    // Set default local storage for the first pane
                    it.putExtra(TwinWindowActivity.EXTRA_TOP_LOCAL_PATH, 
                        android.os.Environment.getExternalStorageDirectory().absolutePath)
                    it.putExtra(TwinWindowActivity.EXTRA_TOP_LOCAL_LABEL, 
                        getString(R.string.storage_internal))
                }
            } else {
                Intent(this, StorageBrowserActivity::class.java)
            }
        } else {
            Intent(this, WelcomeActivity::class.java)
        }

        startActivity(destination)
        finish()
    }
}