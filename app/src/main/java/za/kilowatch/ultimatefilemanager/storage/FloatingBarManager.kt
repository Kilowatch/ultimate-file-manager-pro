package za.kilowatch.ultimatefilemanager.storage

import android.content.Context

/**
 * Backward-compatible facade forwarding to [QuickAccessManager].
 */
object FloatingBarManager {

    fun isEnabled(context: Context): Boolean = QuickAccessManager.isEnabled(context)

    fun setEnabled(context: Context, enabled: Boolean) = QuickAccessManager.setEnabled(context, enabled)

    fun getItemIds(context: Context): List<String> = QuickAccessManager.getItemIds(context)

    fun setItemIds(context: Context, ids: List<String>) = QuickAccessManager.setItemIds(context, ids)

    fun isItemDocked(context: Context, tileId: String): Boolean = QuickAccessManager.isItemDocked(context, tileId)

    fun addItem(context: Context, tileId: String) = QuickAccessManager.addItem(context, tileId)

    fun removeItem(context: Context, tileId: String) = QuickAccessManager.removeItem(context, tileId)
}
