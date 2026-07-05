package za.kilowatch.ultimatefilemanager.settings

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import za.kilowatch.ultimatefilemanager.R

object ProtectedFilesManager {
    private const val PREFS_NAME = "ufm_protected_files_prefs"

    fun isProtected(context: Context, path: String, shareId: String? = null): Boolean {
        val key = getStorageKey(path, shareId)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(key, false)
    }

    fun setProtected(context: Context, path: String, shareId: String? = null, protected: Boolean) {
        val key = getStorageKey(path, shareId)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (protected) {
            prefs.edit().putBoolean(key, true).apply()
        } else {
            prefs.edit().remove(key).apply()
        }
    }

    fun isOrContainsProtected(context: Context, path: String, shareId: String? = null): Boolean {
        if (isProtected(context, path, shareId)) return true
        val prefix = getStorageKey(path, shareId) + "/"
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.all.keys.any { it.startsWith(prefix) }
    }

    private fun getStorageKey(path: String, shareId: String?): String {
        return if (!shareId.isNullOrEmpty()) {
            "network:$shareId:$path"
        } else {
            path
        }
    }

    /**
     * Shows a premium style warning dialog stating that the item is protected and cannot be deleted.
     */
    fun showProtectedDeleteDialog(context: Context, isTv: Boolean, onDismiss: (() -> Unit)? = null) {
        val bgColor = context.getColor(R.color.tv_bg_gradient_end)
        val white = context.getColor(R.color.tv_text_primary)
        val black = context.getColor(R.color.tv_button_focused_yellow_text)
        val yellow = context.getColor(R.color.tv_button_focused_yellow)
        val yellowCsl = ColorStateList.valueOf(yellow)

        val dialog = MaterialAlertDialogBuilder(context,
            com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
            .setTitle(context.getString(R.string.protected_delete_title))
            .setMessage(context.getString(R.string.protected_delete_message))
            .setIcon(R.drawable.ic_shield_protected)
            .setPositiveButton(context.getString(R.string.protected_delete_ok)) { d, _ ->
                d.dismiss()
                onDismiss?.invoke()
            }
            .create()

        dialog.show()

        // Dark window styling to make it look premium
        dialog.window?.setBackgroundDrawable(ColorDrawable(bgColor))
        
        val titleView = dialog.findViewById<android.widget.TextView>(
            com.google.android.material.R.id.alertTitle
        ) ?: dialog.findViewById(context.resources.getIdentifier("alertTitle", "id", "android"))
        titleView?.setTextColor(white)
        
        dialog.findViewById<android.widget.TextView>(android.R.id.message)?.setTextColor(white)

        // Positive button styling (Yellow confirm button with black text)
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.apply {
            backgroundTintList = yellowCsl
            setTextColor(black)
            if (isTv) {
                setOnFocusChangeListener { _, hasFocus ->
                    backgroundTintList = yellowCsl
                    setTextColor(black)
                }
            }
        }
    }
}
