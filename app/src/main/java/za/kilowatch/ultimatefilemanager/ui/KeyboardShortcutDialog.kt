package za.kilowatch.ultimatefilemanager.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.settings.KeyboardPreferenceManager
import za.kilowatch.ultimatefilemanager.util.DeviceUtils

object KeyboardShortcutDialog {

    fun show(activity: Activity) {
        if (DeviceUtils.isTvDevice(activity)) return
        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_keyboard_shortcuts, null)

        val recycler = dialogView.findViewById<RecyclerView>(R.id.recyclerShortcuts)
        val btnClose = dialogView.findViewById<View>(R.id.btnCloseDialog)

        recycler.layoutManager = LinearLayoutManager(activity)
        recycler.adapter = ShortcutsAdapter(activity, KeyboardPreferenceManager.ALL_BINDINGS)

        val dialog = MaterialAlertDialogBuilder(activity, R.style.UFM_Dialog)
            .setView(dialogView)
            .create()

        btnClose.setOnClickListener { dialog.dismiss() }

        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    }

    private class ShortcutsAdapter(
        private val activity: Activity,
        private val bindings: List<KeyboardPreferenceManager.KeyBinding>
    ) : RecyclerView.Adapter<ShortcutsAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val txtTitle: TextView = view.findViewById(R.id.txtActionTitle)
            val txtDesc: TextView = view.findViewById(R.id.txtActionDesc)
            val txtKeyBadge: TextView = view.findViewById(R.id.txtKeyBadge)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_keyboard_shortcut_row, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = bindings[position]
            holder.txtTitle.setText(item.titleResId)
            holder.txtDesc.setText(item.descResId)
            holder.txtKeyBadge.text = KeyboardPreferenceManager.getCustomBindingDisplay(activity, item.actionId)
        }

        override fun getItemCount(): Int = bindings.size
    }
}
