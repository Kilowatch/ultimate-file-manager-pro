package za.kilowatch.ultimatefilemanager.shortcuts

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.settings.ColorblindPalette
import za.kilowatch.ultimatefilemanager.settings.FavoritesManager
import za.kilowatch.ultimatefilemanager.settings.IconCustomizationManager
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import za.kilowatch.ultimatefilemanager.settings.ThemeHelper
import za.kilowatch.ultimatefilemanager.storage.StorageBrowserActivity
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import za.kilowatch.ultimatefilemanager.util.FileTypeIconProvider
import za.kilowatch.ultimatefilemanager.util.ThemeColors

/**
 * Activity handling [Intent.ACTION_CREATE_SHORTCUT] requested by custom launchers
 * (such as ATV Launcher Pro, Nova Launcher, etc.) via their "Add Shortcut" interface.
 *
 * Displays a list of user favorites and mounted storage drives (e.g. SSD, USB, Internal Storage),
 * allowing the user to select any drive or folder to pin to their home screen.
 */
class CreateShortcutActivity : AppCompatActivity() {

    private var isTv: Boolean = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)

        isTv = DeviceUtils.isTvDevice(this)
        setContentView(if (isTv) R.layout.activity_create_shortcut_tv else R.layout.activity_create_shortcut)

        val recyclerTargets = findViewById<RecyclerView>(R.id.recyclerTargets)
        val txtEmpty = findViewById<TextView>(R.id.txtEmpty)
        val btnCancel = findViewById<MaterialButton>(R.id.btnCancel)

        btnCancel.setOnClickListener {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }

        val targets = loadShortcutTargets()

        if (targets.isEmpty()) {
            recyclerTargets.visibility = View.GONE
            txtEmpty.visibility = View.VISIBLE
        } else {
            recyclerTargets.visibility = View.VISIBLE
            txtEmpty.visibility = View.GONE
            recyclerTargets.layoutManager = LinearLayoutManager(this)
            recyclerTargets.adapter = ShortcutTargetAdapter(targets) { selectedItem ->
                returnShortcutResult(selectedItem)
            }
        }
    }

    /**
     * Aggregates all user favorites and mounted physical storages (SSD, USB drives, internal).
     */
    private fun loadShortcutTargets(): List<FavoritesManager.FavoriteItem> {
        val favorites = FavoritesManager.getFavorites(this)
        val existingPaths = favorites.map { it.path.trimEnd('/') }.toSet()

        val merged = mutableListOf<FavoritesManager.FavoriteItem>()
        merged.addAll(favorites)

        // Add any connected physical drives (e.g. SSD, USB) that aren't already explicitly favorited
        try {
            val connectedDrives = StorageBrowserActivity.getConnectedStorages(this, localOnly = true)
            for (drive in connectedDrives) {
                if (drive.isShizukuTile || drive.mountPath.isEmpty()) continue
                val cleanPath = drive.mountPath.trimEnd('/')
                if (!existingPaths.contains(cleanPath)) {
                    merged.add(
                        FavoritesManager.FavoriteItem(
                            id = "drive_${drive.id}",
                            path = drive.mountPath,
                            label = drive.label,
                            isFolder = true,
                            isNetwork = false,
                            isStorageTileFavorite = true
                        )
                    )
                }
            }
        } catch (_: Exception) {}

        return merged
    }

    /**
     * Packages the chosen favorite/drive into standard shortcut result intents for the launcher.
     */
    private fun returnShortcutResult(item: FavoritesManager.FavoriteItem) {
        val launchIntent = FavoriteHomeScreenManager.createLaunchIntent(this, item)
        val iconBitmap = FavoriteIconComposer.composeIconBitmap(this, item)

        val shortcutInfo = ShortcutInfoCompat.Builder(this, item.id)
            .setShortLabel(item.label.take(12))
            .setLongLabel(item.label)
            .setIcon(IconCompat.createWithBitmap(iconBitmap))
            .setIntent(launchIntent)
            .build()

        val resultIntent = ShortcutManagerCompat.createShortcutResultIntent(this, shortcutInfo) ?: Intent()

        // Explicit extras for legacy and custom TV launchers like ATV Launcher Pro
        resultIntent.putExtra(Intent.EXTRA_SHORTCUT_INTENT, launchIntent)
        resultIntent.putExtra(Intent.EXTRA_SHORTCUT_NAME, item.label)
        resultIntent.putExtra(Intent.EXTRA_SHORTCUT_ICON, iconBitmap)

        // Grant poster URI permission to calling launcher package if available
        callingPackage?.let { pkg ->
            FavoritePosterRenderer.getPosterUri(this, item)?.let { uri ->
                try {
                    grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) {}
            }
        }

        setResult(Activity.RESULT_OK, resultIntent)
        finish()
    }

    inner class ShortcutTargetAdapter(
        private val items: List<FavoritesManager.FavoriteItem>,
        private val onItemClick: (FavoritesManager.FavoriteItem) -> Unit
    ) : RecyclerView.Adapter<ShortcutTargetAdapter.TargetViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TargetViewHolder {
            val layoutRes = if (isTv) R.layout.item_create_shortcut_tv else R.layout.item_create_shortcut
            val view = LayoutInflater.from(parent.context).inflate(layoutRes, parent, false)
            return TargetViewHolder(view)
        }

        override fun onBindViewHolder(holder: TargetViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class TargetViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val card: MaterialCardView = itemView.findViewById(R.id.cardShortcutItem)
            private val txtLabel: TextView = itemView.findViewById(R.id.txtItemLabel)
            private val txtPath: TextView = itemView.findViewById(R.id.txtItemPath)
            private val imgIcon: ImageView = itemView.findViewById(R.id.imgItemIcon)

            fun bind(item: FavoritesManager.FavoriteItem) {
                val ctx = itemView.context
                txtLabel.text = item.label
                txtPath.text = item.path

                val iconTint = if (isTv) ctx.getColor(R.color.tv_accent) else ThemeColors.primary(ctx)
                if (item.isFolder || item.isStorageTileFavorite) {
                    val isCustom = IconCustomizationManager.applyFolderIcon(ctx, imgIcon, isNetwork = item.isNetwork, defaultTint = iconTint)
                    if (isCustom) imgIcon.clearColorFilter() else imgIcon.setColorFilter(iconTint)
                } else {
                    val ext = item.path.substringAfterLast('.', "")
                    val isCustom = IconCustomizationManager.applyFileExtensionIcon(ctx, imgIcon, ext, defaultTint = iconTint)
                    if (isCustom) imgIcon.clearColorFilter() else imgIcon.setColorFilter(iconTint)
                }

                card.setOnClickListener { onItemClick(item) }

                if (isTv) {
                    setupTvFocus(card)
                }
            }

            private fun setupTvFocus(cardView: MaterialCardView) {
                val yellowFill = ColorblindPalette.focusFill(cardView.context)
                val blackText = ColorblindPalette.focusFillText(cardView.context)
                val glassBg = cardView.context.getColor(R.color.tv_glass_white_10)
                val primaryText = cardView.context.getColor(R.color.tv_text_primary)
                val secondaryText = cardView.context.getColor(R.color.tv_text_secondary)

                cardView.setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus) {
                        cardView.setCardBackgroundColor(yellowFill)
                        txtLabel.setTextColor(blackText)
                        txtPath.setTextColor(blackText)
                    } else {
                        cardView.setCardBackgroundColor(glassBg)
                        txtLabel.setTextColor(primaryText)
                        txtPath.setTextColor(secondaryText)
                    }
                }
            }
        }
    }
}
