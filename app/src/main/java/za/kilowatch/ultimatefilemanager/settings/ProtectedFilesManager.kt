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
        val layoutRes = if (isTv) R.layout.dialog_support_message_tv else R.layout.dialog_support_message
        val dialogView = android.view.LayoutInflater.from(context).inflate(layoutRes, null)
        val imgIcon = dialogView.findViewById<android.widget.ImageView>(R.id.imgDialogIcon)
        val txtTitle = dialogView.findViewById<android.widget.TextView>(R.id.txtDialogTitle)
        val txtMessage = dialogView.findViewById<android.widget.TextView>(R.id.txtDialogMessage)
        val btnPositive = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDialogPositive)

        imgIcon?.setImageResource(R.drawable.ic_shield_protected)
        txtTitle?.text = context.getString(R.string.protected_delete_title)
        txtMessage?.text = context.getString(R.string.protected_delete_message)
        btnPositive?.text = context.getString(R.string.protected_delete_ok)

        val dialog = MaterialAlertDialogBuilder(context, R.style.UFM_Dialog)
            .setView(dialogView)
            .create()

        btnPositive?.setOnClickListener {
            dialog.dismiss()
            onDismiss?.invoke()
        }

        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
    }
}
