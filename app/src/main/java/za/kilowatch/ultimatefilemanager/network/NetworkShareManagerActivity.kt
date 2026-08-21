package za.kilowatch.ultimatefilemanager.network

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import za.kilowatch.ultimatefilemanager.settings.FontSizeHelper
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper

class NetworkShareManagerActivity : AppCompatActivity() {

    private lateinit var repo: NetworkShareRepository
    private lateinit var adapter: ShareAdapter

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        za.kilowatch.ultimatefilemanager.settings.ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val isTv = DeviceUtils.isTvDevice(this)
        setContentView(
            if (isTv) R.layout.activity_network_share_manager_tv
            else       R.layout.activity_network_share_manager
        )

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(sb.left, sb.top, sb.right, sb.bottom)
            insets
        }

        repo = NetworkShareRepository.getInstance(this)

        val rv = findViewById<RecyclerView>(R.id.rvShares)
        rv.layoutManager = LinearLayoutManager(this)
        adapter = ShareAdapter(
            isTv     = isTv,
            onEdit   = { share -> openEditor(share.id) },
            onDelete = { share -> confirmDelete(share) }
        )
        rv.adapter = adapter

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<ImageView>(R.id.btnAddShare).setOnClickListener { openEditor(null) }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val shares = repo.getAll()
        adapter.setItems(shares)
        val empty = findViewById<View>(R.id.emptyState)
        empty.visibility = if (shares.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun openEditor(shareId: String?) {
        val intent = Intent(this, NetworkShareEditActivity::class.java)
        if (shareId != null) intent.putExtra(NetworkShareEditActivity.EXTRA_SHARE_ID, shareId)
        startActivity(intent)
    }

    private fun confirmDelete(share: NetworkShare) {
        val isTv = DeviceUtils.isTvDevice(this)
        val dialogView = LayoutInflater.from(this).inflate(
            if (isTv) R.layout.dialog_network_share_delete_confirm_tv
            else R.layout.dialog_network_share_delete_confirm,
            null
        )

        val txtMessage = dialogView.findViewById<TextView>(R.id.txtDeleteMessage)
        txtMessage?.text = getString(R.string.network_delete_confirm_body, share.name)

        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogView.findViewById<View>(R.id.btnCancel)?.setOnClickListener {
            dialog.dismiss()
        }

        dialogView.findViewById<View>(R.id.btnDeleteConfirm)?.setOnClickListener {
            repo.delete(share.id)
            refresh()
            dialog.dismiss()
        }

        dialog.show()
    }

    // ── Adapter ───────────────────────────────────────────────────────────────

    private inner class ShareAdapter(
        private val isTv: Boolean,
        private val onEdit: (NetworkShare) -> Unit,
        private val onDelete: (NetworkShare) -> Unit
    ) : RecyclerView.Adapter<ShareAdapter.VH>() {

        private val items = mutableListOf<NetworkShare>()

        fun setItems(list: List<NetworkShare>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val layout = if (isTv) R.layout.item_network_share_tv else R.layout.item_network_share
            val v = LayoutInflater.from(parent.context)
                .inflate(layout, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            private val name = view.findViewById<TextView>(R.id.txtShareName)
            private val host = view.findViewById<TextView>(R.id.txtShareHost)
            private val layoutWarning = view.findViewById<View?>(R.id.layoutWarning)
            // Mobile layout has inline buttons; TV layout does not.
            private val edit = view.findViewById<View?>(R.id.btnEdit)
            private val del  = view.findViewById<View?>(R.id.btnDelete)

            fun bind(share: NetworkShare) {
                name.text = share.name
                host.text = "${share.type.name} • ${share.host}"

                if (share.isCredentialsStripped) {
                    layoutWarning?.visibility = View.VISIBLE
                } else {
                    layoutWarning?.visibility = View.GONE
                }

                if (isTv) {
                    // On TV: pressing OK on the row opens a focused action dialog
                    itemView.setOnClickListener { showTvActionDialog(share) }
                } else {
                    // On mobile: inline icon buttons handle edit / delete
                    edit?.setOnClickListener { onEdit(share) }
                    del?.setOnClickListener  { onDelete(share) }
                    itemView.setOnClickListener {
                        if (share.isCredentialsStripped) {
                            android.widget.Toast.makeText(
                                itemView.context,
                                R.string.backup_toast_please_fill_credentials,
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                        onEdit(share)
                    }
                }
            }

            private fun showTvActionDialog(share: NetworkShare) {
                val dialogView = LayoutInflater.from(itemView.context).inflate(
                    R.layout.dialog_network_share_actions_tv,
                    null
                )

                dialogView.findViewById<TextView>(R.id.txtShareTitle)?.text = share.name
                dialogView.findViewById<TextView>(R.id.txtShareSubtitle)?.text = "${share.type.name} • ${share.host}"

                val dialog = MaterialAlertDialogBuilder(itemView.context, R.style.UFM_Dialog)
                    .setView(dialogView)
                    .setCancelable(true)
                    .create()

                dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

                dialogView.findViewById<View>(R.id.btnCancel)?.setOnClickListener {
                    dialog.dismiss()
                }

                dialogView.findViewById<View>(R.id.btnActionBrowse)?.setOnClickListener {
                    dialog.dismiss()
                    if (share.isCredentialsStripped) {
                        android.widget.Toast.makeText(
                            itemView.context,
                            R.string.backup_toast_please_fill_credentials,
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                        onEdit(share)
                    } else {
                        val intent = Intent(itemView.context, NetworkBrowserActivity::class.java).apply {
                            putExtra(NetworkBrowserActivity.EXTRA_SHARE_ID, share.id)
                            putExtra(NetworkBrowserActivity.EXTRA_STORAGE_LABEL, share.name)
                        }
                        itemView.context.startActivity(intent)
                    }
                }

                dialogView.findViewById<View>(R.id.btnActionEdit)?.setOnClickListener {
                    dialog.dismiss()
                    onEdit(share)
                }

                dialogView.findViewById<View>(R.id.btnActionDelete)?.setOnClickListener {
                    dialog.dismiss()
                    onDelete(share)
                }

                dialog.show()
            }
        }
    }
}

