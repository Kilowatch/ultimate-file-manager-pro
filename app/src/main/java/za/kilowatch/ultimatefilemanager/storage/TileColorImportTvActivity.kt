package za.kilowatch.ultimatefilemanager.storage

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper

class TileColorImportTvActivity : AppCompatActivity() {

    private var currentValidConfig: TileColorConfig? = null

    // Use a launcher so that we return properly after the copy flow finishes
    private val copyToLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                setResult(Activity.RESULT_OK)
                finish()
            }
        }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_tile_color_import_tv)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.etImportCode)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, v.paddingTop + systemBars.top, v.paddingRight, v.paddingBottom + systemBars.bottom)
            insets
        }

        val etImportCode = findViewById<EditText>(R.id.etImportCode)
        val tvImportError = findViewById<TextView>(R.id.tvImportError)
        val importPreviewPanel = findViewById<View>(R.id.importPreviewPanel)
        val btnImportApply = findViewById<View>(R.id.btnImportApply)
        val btnImportCancel = findViewById<View>(R.id.btnImportCancel)

        val updatePreviewRow = { dotId: Int, hexId: Int, color: Int ->
            val dot = findViewById<View>(dotId)
            val hexText = findViewById<TextView>(hexId)

            if (color == Color.TRANSPARENT) {
                val d = GradientDrawable()
                d.shape = GradientDrawable.OVAL
                d.setColor(Color.TRANSPARENT)
                d.setStroke(2, getColor(R.color.tv_glass_border))
                dot.background = d
                hexText.text = getString(R.string.tile_color_export_none)
            } else {
                val d = GradientDrawable()
                d.shape = GradientDrawable.OVAL
                d.setColor(color)
                dot.background = d
                hexText.text = String.format("#%08X", color)
            }
        }

        etImportCode.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                val code = s?.toString()?.trim() ?: ""

                if (code.isEmpty()) {
                    tvImportError.visibility = View.GONE
                    importPreviewPanel.visibility = View.GONE
                    btnImportApply.isEnabled = false
                    currentValidConfig = null
                    return
                }

                val config = TileColorCodec.decode(code)
                if (config == null) {
                    tvImportError.visibility = View.VISIBLE
                    tvImportError.text = getString(R.string.tile_color_import_invalid)
                    importPreviewPanel.visibility = View.GONE
                    btnImportApply.isEnabled = false
                    currentValidConfig = null
                } else {
                    tvImportError.visibility = View.GONE
                    currentValidConfig = config
                    
                    updatePreviewRow(R.id.dotImportIcon, R.id.hexImportIcon, config.iconColor)
                    updatePreviewRow(R.id.dotImportTileBg, R.id.hexImportTileBg, config.tileBgColor)
                    updatePreviewRow(R.id.dotImportRing, R.id.hexImportRing, config.ringColor)
                    updatePreviewRow(R.id.dotImportIconBg, R.id.hexImportIconBg, config.iconBgColor)
                    updatePreviewRow(R.id.dotImportLabel, R.id.hexImportLabel, config.labelColor)
                    
                    importPreviewPanel.visibility = View.VISIBLE
                    btnImportApply.isEnabled = true
                }
            }
        })

        btnImportApply.setOnClickListener {
            currentValidConfig?.let { config ->
                // The main StorageBrowserActivity will have set up TvTileDataHolder.tiles.
                // We just need to update the sourceConfig and launch the copy flow.
                TvTileDataHolder.sourceConfig = config
                TvTileDataHolder.sourceTileId = "imported"
                TvTileDataHolder.isListView = MainMenuViewModeManager.loadViewMode(this) == MainMenuViewModeManager.ViewMode.LIST
                
                val copyIntent = Intent(this, TileCopyTvActivity::class.java)
                copyToLauncher.launch(copyIntent)
            }
        }

        btnImportCancel.setOnClickListener {
            finish()
        }
    }
}
