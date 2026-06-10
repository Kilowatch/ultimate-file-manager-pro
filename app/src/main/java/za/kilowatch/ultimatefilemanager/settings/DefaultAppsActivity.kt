package za.kilowatch.ultimatefilemanager.settings

import android.content.Context
import android.content.DialogInterface
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import za.kilowatch.ultimatefilemanager.viewer.DefaultOpenManager

/**
 * Settings screen to view and manage default file opening preferences.
 * Supports both Mobile (frosted glass) and Android TV (yellow focus) themes.
 */
class DefaultAppsActivity : AppCompatActivity() {

    private var isTv = false
    private lateinit var adapter: DefaultAppsAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var txtEmpty: TextView

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        isTv = DeviceUtils.isTvDevice(this)
        setContentView(if (isTv) R.layout.activity_default_apps_tv else R.layout.activity_default_apps)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val tvPad = if (isTv) (27 * resources.displayMetrics.density).toInt() else 0
            v.setPadding(
                systemBars.left + tvPad, systemBars.top + tvPad,
                systemBars.right + tvPad, systemBars.bottom + tvPad
            )
            insets
        }

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        
        val btnClearAll = findViewById<View>(R.id.btnClearAll)
        btnClearAll.setOnClickListener { confirmClearAll() }
        
        // TV Focus for header buttons
        if (isTv) {
            setupHeaderButtonFocus(findViewById(R.id.btnBack))
            setupHeaderButtonFocus(btnClearAll)
        }

        txtEmpty = findViewById(R.id.txtEmpty)
        recycler = findViewById(R.id.recyclerDefaults)
        recycler.layoutManager = LinearLayoutManager(this)
        
        adapter = DefaultAppsAdapter()
        recycler.adapter = adapter

        refreshList()
    }

    private fun refreshList() {
        val defaults = DefaultOpenManager.getAllDefaults(this)
        adapter.items = defaults
        adapter.notifyDataSetChanged()
        txtEmpty.visibility = if (defaults.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun confirmClearAll() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.default_apps_clear_all)
            .setMessage("Are you sure you want to clear all default application settings?")
            .setPositiveButton(R.string.action_clear) { dialog: DialogInterface, which: Int ->
                DefaultOpenManager.clearAllDefaults(this)
                refreshList()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun setupHeaderButtonFocus(view: View) {
        val whiteCsl = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_text_primary))
        val blackCsl = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_button_focused_yellow_text))
        val btn = view as? ImageView ?: return
        
        btn.setOnFocusChangeListener { _, hasFocus ->
            btn.imageTintList = if (hasFocus) blackCsl else whiteCsl
        }
    }

    inner class DefaultAppsAdapter : RecyclerView.Adapter<DefaultAppsAdapter.VH>() {
        var items: List<DefaultOpenManager.DefaultEntry> = emptyList()

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val card: MaterialCardView = v.findViewById(R.id.cardDefault)
            val imgIcon: ImageView = v.findViewById(R.id.imgIcon)
            val txtExtension: TextView = v.findViewById(R.id.txtExtension)
            val txtDetails: TextView = v.findViewById(R.id.txtDetails)
            val btnRemove: ImageView = v.findViewById(R.id.btnRemove)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(layoutInflater.inflate(R.layout.item_default_app, parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val entry = items[position]
            holder.txtExtension.text = ".${entry.extension.uppercase()}"
            
            val contextStr = if (entry.isNetwork) getString(R.string.default_apps_context_network) else getString(R.string.default_apps_context_local)
            val actionStr = when (entry.action) {
                DefaultOpenManager.Action.INTERNAL -> getString(R.string.default_apps_action_internal)
                DefaultOpenManager.Action.EXTERNAL -> {
                    val base = getString(R.string.default_apps_action_external)
                    val pkg  = DefaultOpenManager.getPreferredPackage(this@DefaultAppsActivity, entry.extension, entry.isNetwork)
                    if (pkg != null) {
                        val appLabel = try {
                            packageManager.getApplicationLabel(
                                packageManager.getApplicationInfo(pkg, 0)
                            ).toString()
                        } catch (_: android.content.pm.PackageManager.NameNotFoundException) { null }
                        if (appLabel != null) "$base ($appLabel)" else base
                    } else base
                }
                DefaultOpenManager.Action.PLAYER   -> getString(R.string.default_apps_action_player)
                DefaultOpenManager.Action.SLIDESHOW -> getString(R.string.default_apps_action_slideshow)
                else -> ""
            }
            holder.txtDetails.text = "$contextStr • $actionStr"

            val removeAction = {
                DefaultOpenManager.clearDefaultAction(this@DefaultAppsActivity, entry.extension, entry.isNetwork)
                refreshList()
            }

            holder.btnRemove.setOnClickListener { removeAction() }

            if (isTv) {
                // On TV, D-pad OK fires the focused card's click — wire it to delete too
                holder.card.setOnClickListener { removeAction() }
                setupTvFocus(holder)
            } else {
                setupMobileStyle(holder)
            }
        }

        private fun setupMobileStyle(holder: VH) {
            holder.card.setCardBackgroundColor(getColor(R.color.mobile_glass_card))
            holder.txtExtension.setTextColor(getColor(R.color.mobile_text_primary))
            holder.txtDetails.setTextColor(getColor(R.color.mobile_text_secondary))
            holder.imgIcon.imageTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.mobile_icon_tint))
            holder.btnRemove.imageTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.ufm_denied))
        }

        private fun setupTvFocus(holder: VH) {
            val yellowFill  = getColor(R.color.tv_button_focused_yellow)
            val blackText   = getColor(R.color.tv_button_focused_yellow_text)
            val glassColor  = getColor(R.color.tv_glass_white_10)
            val primaryText = getColor(R.color.tv_text_primary)
            val secondText  = getColor(R.color.tv_text_secondary)

            // Initial state (blurred)
            holder.card.setCardBackgroundColor(glassColor)
            holder.txtExtension.setTextColor(primaryText)
            holder.txtDetails.setTextColor(secondText)
            holder.imgIcon.imageTintList = android.content.res.ColorStateList.valueOf(primaryText)
            holder.btnRemove.imageTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.ufm_denied))

            holder.card.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    holder.card.setCardBackgroundColor(yellowFill)
                    holder.txtExtension.setTextColor(blackText)
                    holder.txtDetails.setTextColor(Color.parseColor("#333333"))
                    holder.imgIcon.imageTintList = android.content.res.ColorStateList.valueOf(blackText)
                    holder.btnRemove.imageTintList = android.content.res.ColorStateList.valueOf(blackText)
                } else {
                    holder.card.setCardBackgroundColor(glassColor)
                    holder.txtExtension.setTextColor(primaryText)
                    holder.txtDetails.setTextColor(secondText)
                    holder.imgIcon.imageTintList = android.content.res.ColorStateList.valueOf(primaryText)
                    holder.btnRemove.imageTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.ufm_denied))
                }
            }
        }
    }
}
