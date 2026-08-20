package za.kilowatch.ultimatefilemanager.settings

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.util.DeviceUtils

/**
 * Activity to view and manage user favorites.
 * Supports both Mobile (frosted glass) and Android TV (yellow focus) themes.
 */
class ManageFavoritesActivity : AppCompatActivity() {

    private var isTv = false
    private lateinit var recyclerFavorites: RecyclerView
    private lateinit var layoutEmpty: View
    private lateinit var cardInfo: View
    private lateinit var btnClearAll: View
    private lateinit var adapter: ManageFavoritesAdapter

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        isTv = DeviceUtils.isTvDevice(this)
        setContentView(if (isTv) R.layout.activity_manage_favorites_tv else R.layout.activity_manage_favorites)

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
        btnBack?.setOnClickListener { finish() }

        btnClearAll = findViewById(R.id.btnClearAll)
        btnClearAll.setOnClickListener { showClearAllConfirmDialog() }

        if (isTv) {
            setupHeaderButtonFocus(btnBack)
            setupHeaderButtonFocus(btnClearAll as? ImageView)
        }

        cardInfo = findViewById(R.id.cardInfo)
        recyclerFavorites = findViewById(R.id.recyclerFavorites)
        layoutEmpty = findViewById(R.id.layoutEmpty)

        recyclerFavorites.layoutManager = LinearLayoutManager(this)
        adapter = ManageFavoritesAdapter(isTv) { favorite ->
            showDeleteFavoriteDialog(favorite)
        }
        recyclerFavorites.adapter = adapter

        loadFavorites()
    }

    private fun setupHeaderButtonFocus(btn: ImageView?) {
        if (btn == null) return
        val whiteCsl = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_text_primary))
        val blackCsl = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_button_focused_yellow_text))
        btn.imageTintList = whiteCsl
        btn.setOnFocusChangeListener { _, hasFocus ->
            btn.imageTintList = if (hasFocus) blackCsl else whiteCsl
        }
    }

    override fun onResume() {
        super.onResume()
        loadFavorites()
    }

    private fun loadFavorites() {
        val list = FavoritesManager.getFavorites(this)
        val isEmpty = list.isEmpty()

        if (isEmpty) {
            recyclerFavorites.visibility = View.GONE
            cardInfo.visibility = View.GONE
            btnClearAll.visibility = View.GONE
            layoutEmpty.visibility = View.VISIBLE
        } else {
            recyclerFavorites.visibility = View.VISIBLE
            cardInfo.visibility = View.VISIBLE
            btnClearAll.visibility = View.VISIBLE
            layoutEmpty.visibility = View.GONE
            adapter.submitList(list)
        }
    }

    private fun showDeleteFavoriteDialog(favorite: FavoritesManager.FavoriteItem) {
        val dialogView = LayoutInflater.from(this).inflate(
            if (isTv) R.layout.dialog_favorite_remove_confirm_tv
            else R.layout.dialog_favorite_remove_confirm,
            null
        )

        val txtMessage = dialogView.findViewById<TextView>(R.id.txtMessage)
        val btnRemoveConfirm = dialogView.findViewById<View>(R.id.btnRemoveConfirm)
        val btnCancel = dialogView.findViewById<View>(R.id.btnCancel)

        txtMessage.text = getString(R.string.manage_favorites_remove_confirm_message, favorite.label)

        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        btnRemoveConfirm.setOnClickListener {
            dialog.dismiss()
            deleteFavorite(favorite)
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()

        if (isTv) {
            btnCancel.requestFocus()
        }
    }

    private fun showClearAllConfirmDialog() {
        val dialogView = LayoutInflater.from(this).inflate(
            if (isTv) R.layout.dialog_favorites_clear_all_confirm_tv
            else R.layout.dialog_favorites_clear_all_confirm,
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
            FavoritesManager.clearAllFavorites(this)
            loadFavorites()
            Toast.makeText(this, R.string.manage_favorites_cleared_all_toast, Toast.LENGTH_SHORT).show()
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()

        if (isTv) {
            btnCancel.requestFocus()
        }
    }

    private fun deleteFavorite(favorite: FavoritesManager.FavoriteItem) {
        FavoritesManager.removeFavorite(this, favorite.path)
        loadFavorites()
        Toast.makeText(this, getString(R.string.manage_favorites_removed, favorite.label), Toast.LENGTH_SHORT).show()
    }
}
