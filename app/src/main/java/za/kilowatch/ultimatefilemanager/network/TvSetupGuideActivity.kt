package za.kilowatch.ultimatefilemanager.network

import android.content.Context
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.text.HtmlCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper

class TvSetupGuideActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Edge-to-edge
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_tv_setup_guide)

        val coordinatorRoot = findViewById<android.view.View>(R.id.coordinatorRoot)
        val bottomBar = findViewById<android.view.View>(R.id.bottomBar)
        
        ViewCompat.setOnApplyWindowInsetsListener(coordinatorRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, 0)
            
            // Apply bottom padding to the bottom bar
            bottomBar.setPadding(
                bottomBar.paddingLeft, 
                bottomBar.paddingTop, 
                bottomBar.paddingRight, 
                bars.bottom + 16 // 16dp was the original padding
            )
            insets
        }

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener {
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
}
