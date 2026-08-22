package za.kilowatch.ultimatefilemanager.network

import android.content.Context
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.text.HtmlCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import za.kilowatch.ultimatefilemanager.settings.ThemeHelper

class TvSetupGuideActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_tv_setup_guide)

        val coordinatorRoot = findViewById<android.view.View>(R.id.coordinatorRoot)
        val bottomBar = findViewById<android.view.View>(R.id.bottomBar)
        
        ViewCompat.setOnApplyWindowInsetsListener(coordinatorRoot) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            coordinatorRoot.setPadding(bars.left, bars.top, bars.right, 0)
            bottomBar.setPadding(
                16.dpToPx(),
                16.dpToPx(),
                16.dpToPx(),
                bars.bottom + 16.dpToPx()
            )
            insets
        }

        val btnBack = findViewById<ImageView>(R.id.btnBack)
        btnBack.setOnClickListener {
            finish()
        }

        val txtMessage = findViewById<TextView>(R.id.txtMessage)
        val btnContinue = findViewById<MaterialButton>(R.id.btnContinue)
        val btnCancel = findViewById<MaterialButton>(R.id.btnCancel)

        // Parse HTML tags
        txtMessage.text = HtmlCompat.fromHtml(
            getString(R.string.ufm_first_tv_setup_guide),
            HtmlCompat.FROM_HTML_MODE_LEGACY
        )

        btnContinue.setOnClickListener {
            setResult(RESULT_OK)
            finish()
        }

        btnCancel.setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }
    }

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()
}
