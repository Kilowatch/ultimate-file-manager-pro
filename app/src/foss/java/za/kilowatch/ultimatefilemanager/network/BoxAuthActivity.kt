package za.kilowatch.ultimatefilemanager.network

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import za.kilowatch.ultimatefilemanager.settings.ThemeHelper

/**
 * FOSS build stub for BoxAuthActivity (mobile OAuth).
 * Box is not available in the FOSS build.
 * This activity immediately finishes — it is never reachable via the FOSS UI.
 * The companion constants are defined here so that RCloneProviderActivity can
 * reference them at compile time (they are only reached when BOX_ENABLED=true).
 */
class BoxAuthActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        finish()
    }

    companion object {
        const val EXTRA_TOKEN_JSON = "token_json"
        const val EXTRA_EMAIL = "email"
    }
}
