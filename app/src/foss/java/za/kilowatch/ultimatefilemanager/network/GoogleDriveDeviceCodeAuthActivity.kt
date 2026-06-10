package za.kilowatch.ultimatefilemanager.network

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import za.kilowatch.ultimatefilemanager.settings.ThemeHelper

/**
 * FOSS build stub for GoogleDriveDeviceCodeAuthActivity (TV).
 * Google Drive is not available in the FOSS build.
 * This activity immediately finishes — it is never reachable via the FOSS UI.
 */
class GoogleDriveDeviceCodeAuthActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        finish()
    }
}
