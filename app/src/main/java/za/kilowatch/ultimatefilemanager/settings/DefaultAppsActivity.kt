package za.kilowatch.ultimatefilemanager.settings

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import za.kilowatch.ultimatefilemanager.util.FileTypeIconProvider
import za.kilowatch.ultimatefilemanager.viewer.DefaultOpenManager

/**
 * Settings screen to view and manage default file opening preferences.
 * Supports both Mobile (frosted glass) and Android TV (yellow focus) themes.
 */
class DefaultAppsActivity : AppCompatActivity() {

    private var isTv = false
    private lateinit var adapter: DefaultAppsAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var layoutEmpty: View
    private lateinit var cardInfo: View
    private lateinit var btnClearAll: View

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

        btnClearAll = findViewById(R.id.btnClearAll)
        btnClearAll.setOnClickListener { showClearAllConfirmDialog() }

        // TV Focus for header buttons
        if (isTv) {
            setupHeaderButtonFocus(findViewById(R.id.btnBack))
            setupHeaderButtonFocus(btnClearAll)
        }

        cardInfo = findViewById(R.id.cardInfo)
        layoutEmpty = findViewById(R.id.layoutEmpty)
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

        val isEmpty = defaults.isEmpty()
        layoutEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
        cardInfo.visibility = if (isEmpty) View.GONE else View.VISIBLE
        recycler.visibility = if (isEmpty) View.GONE else View.VISIBLE
        btnClearAll.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    private fun showClearAllConfirmDialog() {
        val dialogView = LayoutInflater.from(this).inflate(
            if (isTv) R.layout.dialog_default_apps_clear_all_confirm_tv
            else R.layout.dialog_default_apps_clear_all_confirm,
            null
        )

        val btnClearConfirm = dialogView.findViewById<View>(R.id.btnClearConfirm)
        val btnCancel = dialogView.findViewById<View>(R.id.btnCancel)

        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        btnClearConfirm.setOnClickListener {
            dialog.dismiss()
            DefaultOpenManager.clearAllDefaults(this)
            refreshList()
            Toast.makeText(this, R.string.default_apps_cleared_all_toast, Toast.LENGTH_SHORT).show()
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()

        if (isTv) {
            btnCancel.requestFocus()
        }
    }

    private fun showRemoveItemConfirmDialog(entry: DefaultOpenManager.DefaultEntry) {
        val dialogView = LayoutInflater.from(this).inflate(
            if (isTv) R.layout.dialog_default_app_remove_confirm_tv
            else R.layout.dialog_default_app_remove_confirm,
            null
        )

        val txtTitle = dialogView.findViewById<TextView>(R.id.txtTitle)
        val txtMessage = dialogView.findViewById<TextView>(R.id.txtMessage)
        val btnResetConfirm = dialogView.findViewById<View>(R.id.btnResetConfirm)
        val btnCancel = dialogView.findViewById<View>(R.id.btnCancel)

        val extUpper = entry.extension.uppercase()
        val contextStr = if (entry.isNetwork) getString(R.string.default_apps_context_network) else getString(R.string.default_apps_context_local)

        txtTitle.text = getString(R.string.default_app_remove_confirm_title, extUpper)
        txtMessage.text = getString(R.string.default_app_remove_confirm_message, extUpper, contextStr)

        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        btnResetConfirm.setOnClickListener {
            dialog.dismiss()
            DefaultOpenManager.clearDefaultAction(this, entry.extension, entry.isNetwork)
            Toast.makeText(
                this,
                getString(R.string.default_apps_removed_toast, extUpper),
                Toast.LENGTH_SHORT
            ).show()
            refreshList()
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()

        if (isTv) {
            btnCancel.requestFocus()
        }
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
            val txtContextBadge: TextView? = v.findViewById(R.id.txtContextBadge)
            val txtDetails: TextView = v.findViewById(R.id.txtDetails)
            val btnRemove: View = v.findViewById(R.id.btnRemove)
            val btnRemoveContainer: View? = v.findViewById(R.id.btnRemoveContainer)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(layoutInflater.inflate(R.layout.item_default_app, parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val entry = items[position]
            holder.txtExtension.text = ".${entry.extension.uppercase()}"

            val iconRes = FileTypeIconProvider.iconForExtension(entry.extension)
            holder.imgIcon.setImageResource(iconRes)

            val isNet = entry.isNetwork
            val contextStr = if (isNet) getString(R.string.default_apps_context_network) else getString(R.string.default_apps_context_local)
            holder.txtContextBadge?.text = contextStr

            val actionStr = when (entry.action) {
                DefaultOpenManager.Action.INTERNAL -> getString(R.string.default_apps_action_internal)
                DefaultOpenManager.Action.EXTERNAL -> {
                    val base = getString(R.string.default_apps_action_external)
                    val pkg = DefaultOpenManager.getPreferredPackage(this@DefaultAppsActivity, entry.extension, entry.isNetwork)
                    if (pkg != null) {
                        val appLabel = try {
                            packageManager.getApplicationLabel(
                                packageManager.getApplicationInfo(pkg, 0)
                            ).toString()
                        } catch (_: android.content.pm.PackageManager.NameNotFoundException) { null }
                        if (appLabel != null) "$base ($appLabel)" else base
                    } else base
                }
                DefaultOpenManager.Action.PLAYER -> getString(R.string.default_apps_action_player)
                DefaultOpenManager.Action.SLIDESHOW -> getString(R.string.default_apps_action_slideshow)
                else -> ""
            }
            holder.txtDetails.text = actionStr

            val removeAction = {
                showRemoveItemConfirmDialog(entry)
            }

            holder.btnRemove.setOnClickListener { removeAction() }
            holder.btnRemoveContainer?.setOnClickListener { removeAction() }

            if (isTv) {
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
        }

        private fun setupTvFocus(holder: VH) {
            val yellowFill = getColor(R.color.tv_button_focused_yellow)
            val blackText = getColor(R.color.tv_button_focused_yellow_text)
            val glassColor = getColor(R.color.tv_glass_white_10)
            val primaryText = getColor(R.color.tv_text_primary)
            val secondText = getColor(R.color.tv_text_secondary)

            // Initial state
            holder.card.setCardBackgroundColor(glassColor)
            holder.txtExtension.setTextColor(primaryText)
            holder.txtDetails.setTextColor(secondText)

            holder.card.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    holder.card.setCardBackgroundColor(yellowFill)
                    holder.txtExtension.setTextColor(blackText)
                    holder.txtDetails.setTextColor(Color.parseColor("#333333"))
                    holder.txtContextBadge?.setTextColor(blackText)
                } else {
                    holder.card.setCardBackgroundColor(glassColor)
                    holder.txtExtension.setTextColor(primaryText)
                    holder.txtDetails.setTextColor(secondText)
                    holder.txtContextBadge?.setTextColor(primaryText)
                }
            }
        }
    }
}
