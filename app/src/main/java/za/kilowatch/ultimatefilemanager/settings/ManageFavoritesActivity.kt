package za.kilowatch.ultimatefilemanager.settings

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.util.DeviceUtils

/**
 * Activity to manage user favorites.
 * Displays a list of favorites and allows deletion.
 */
class ManageFavoritesActivity : AppCompatActivity() {

    private var isTv = false
    private lateinit var recyclerFavorites: RecyclerView
    private lateinit var layoutEmpty: LinearLayout
    private lateinit var adapter: ManageFavoritesAdapter

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        isTv = DeviceUtils.isTvDevice(this)
        if (isTv) {
            setContentView(R.layout.activity_manage_favorites_tv)
        } else {
            setContentView(R.layout.activity_manage_favorites)
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val tvPad = if (isTv) (27 * resources.displayMetrics.density).toInt() else 0
            v.setPadding(
                systemBars.left + tvPad, systemBars.top + tvPad,
                systemBars.right + tvPad, systemBars.bottom + tvPad
            )
            insets
        }

        // Back button
        val btnBack = findViewById<ImageView?>(R.id.btnBack)
        if (isTv) {
            val whiteCsl = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_text_primary))
            val blackCsl = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_button_focused_yellow_text))
            btnBack?.imageTintList = whiteCsl
            btnBack?.setOnFocusChangeListener { _, hasFocus ->
                btnBack.imageTintList = if (hasFocus) blackCsl else whiteCsl
            }
        }
        btnBack?.setOnClickListener { finish() }

        recyclerFavorites = findViewById(R.id.recyclerFavorites)
        layoutEmpty = findViewById(R.id.layoutEmpty)

        recyclerFavorites.layoutManager = LinearLayoutManager(this)
        adapter = ManageFavoritesAdapter(isTv) { favorite ->
            if (isTv) {
                showDeleteFavoriteDialogTv(favorite)
            } else {
                deleteFavorite(favorite)
            }
        }
        recyclerFavorites.adapter = adapter
    }

    private fun showDeleteFavoriteDialogTv(favorite: FavoritesManager.FavoriteItem) {
        androidx.appcompat.app.AlertDialog.Builder(this, R.style.UFM_Dialog)
            .setTitle(R.string.network_delete_confirm_title)
            .setMessage(getString(R.string.manage_favorites_removed, favorite.label).replace(getString(R.string.manage_favorites_removed, ""), "").let { getString(R.string.remove_favoritelabel_from_favorites) })
            .setPositiveButton(R.string.network_delete_confirm_yes) { _, _ ->
                deleteFavorite(favorite)
            }
            .setNegativeButton(R.string.delete_cancel, null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        loadFavorites()
    }

    private fun loadFavorites() {
        val list = FavoritesManager.getFavorites(this)
        if (list.isEmpty()) {
            recyclerFavorites.visibility = View.GONE
            layoutEmpty.visibility = View.VISIBLE
        } else {
            recyclerFavorites.visibility = View.VISIBLE
            layoutEmpty.visibility = View.GONE
            adapter.submitList(list)
        }
    }

    private fun deleteFavorite(favorite: FavoritesManager.FavoriteItem) {
        FavoritesManager.removeFavorite(this, favorite.path)
        loadFavorites()
        showPremiumSnackbar(getString(R.string.manage_favorites_removed, favorite.label))
    }

    private fun showPremiumSnackbar(message: String) {
        val rootView = findViewById<View>(R.id.main)
        if (isTv) {
            // Very simplified basic TV toast logic here or use snackbar
            Snackbar.make(rootView, message, Snackbar.LENGTH_SHORT)
                .setBackgroundTint(getColor(R.color.tv_glass_white_10))
                .setTextColor(getColor(R.color.tv_text_primary))
                .show()
        } else {
            Snackbar.make(rootView, message, Snackbar.LENGTH_SHORT)
                .setBackgroundTint(getColor(R.color.ufm_surface_variant))
                .setTextColor(getColor(R.color.ufm_text_primary))
                .setActionTextColor(getColor(R.color.ufm_primary))
                .show()
        }
    }
}
