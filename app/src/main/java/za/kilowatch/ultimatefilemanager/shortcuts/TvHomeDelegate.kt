package za.kilowatch.ultimatefilemanager.shortcuts

import android.app.Activity
import android.content.Context
import za.kilowatch.ultimatefilemanager.settings.FavoritesManager

/**
 * Interface for TV home-screen preview channel management.
 * Implemented in `src/tv` using `androidx.tvprovider` to keep the mobile compilation classpath clean.
 */
interface TvHomeDelegate {
    fun addProgram(context: Context, item: FavoritesManager.FavoriteItem, activity: Activity? = null)
    fun removeProgram(context: Context, item: FavoritesManager.FavoriteItem)
    fun reconcileTv(context: Context, favorites: List<FavoritesManager.FavoriteItem>): List<FavoritesManager.FavoriteItem>
}
