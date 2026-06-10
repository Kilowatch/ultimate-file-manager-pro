package za.kilowatch.ultimatefilemanager.network

import android.content.Intent
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder

import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import za.kilowatch.ultimatefilemanager.util.GoRoLog

class OnlineStorageManagerActivity : AppCompatActivity() {

    private lateinit var repo: OnlineStorageRepository
    private lateinit var adapter: StorageAdapter
    private var isTv = false

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        za.kilowatch.ultimatefilemanager.settings.ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        GoRoLog.d("GoRoAuth", "OnlineStorageManagerActivity: onCreate. SavedState: ${savedInstanceState != null}")
        enableEdgeToEdge()

        isTv = DeviceUtils.isTvDevice(this)
        setContentView(
            if (isTv) R.layout.activity_online_storage_manager_tv
            else       R.layout.activity_online_storage_manager
        )

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(sb.left, sb.top, sb.right, sb.bottom)
            insets
        }

        repo = OnlineStorageRepository.getInstance(this)

        val rv = findViewById<RecyclerView>(R.id.rvStorages)
        rv.layoutManager = LinearLayoutManager(this)
        adapter = StorageAdapter(
            isTv     = isTv,
            onDelete = { storage -> confirmDelete(storage) }
        )
        rv.adapter = adapter

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<ImageView>(R.id.btnAddStorage).setOnClickListener { showProviderSelection() }
    }

    override fun onResume() {
        super.onResume()
        GoRoLog.d("GoRoAuth", "[${hashCode()}] OnlineStorageManagerActivity: onResume")
        refresh()
    }

    override fun onPause() {
        super.onPause()
        GoRoLog.d("GoRoAuth", "[${hashCode()}] OnlineStorageManagerActivity: onPause")
    }

    override fun onStop() {
        super.onStop()
        GoRoLog.d("GoRoAuth", "[${hashCode()}] OnlineStorageManagerActivity: onStop")
    }

    override fun onDestroy() {
        super.onDestroy()
        GoRoLog.d("GoRoAuth", "[${hashCode()}] OnlineStorageManagerActivity: onDestroy")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        GoRoLog.d("GoRoAuth", "[${hashCode()}] OnlineStorageManagerActivity: onNewIntent: $intent")
        intent.extras?.let { extras ->
            GoRoLog.d("GoRoAuth", "[${hashCode()}] OnlineStorageManagerActivity: onNewIntent extras: ${extras.keySet().joinToString { "$it=${extras.get(it)}" }}")
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        GoRoLog.d("GoRoAuth", "[${hashCode()}] OnlineStorageManagerActivity: onSaveInstanceState")
    }

    private fun showProviderSelection() {
        startActivity(Intent(this, AddOnlineStorageActivity::class.java))
    }

    private fun refresh() {
        val storages = repo.getAll()
        adapter.setItems(storages)
        val empty = findViewById<View>(R.id.emptyState)
        empty.visibility = if (storages.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun confirmDelete(storage: OnlineStorage) {
        MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setTitle(getString(R.string.network_delete_confirm_title))
            .setMessage("Are you sure you want to remove the connection to ${storage.email}?")
            .setPositiveButton(getString(R.string.network_delete_confirm_yes)) { _, _ ->
                repo.delete(storage.id)
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private inner class StorageAdapter(
        private val isTv: Boolean,
        private val onDelete: (OnlineStorage) -> Unit
    ) : RecyclerView.Adapter<StorageAdapter.VH>() {

        private val items = mutableListOf<OnlineStorage>()

        fun setItems(list: List<OnlineStorage>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val layout = if (isTv) R.layout.item_online_storage_tv else R.layout.item_online_storage
            val v = LayoutInflater.from(parent.context)
                .inflate(layout, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            private val txtEmail = view.findViewById<TextView>(R.id.txtEmail)
            private val txtProvider = view.findViewById<TextView>(R.id.txtProvider)
            private val layoutWarning = view.findViewById<View?>(R.id.layoutWarning)
            private val del  = view.findViewById<ImageView?>(R.id.btnDelete)

            fun bind(storage: OnlineStorage) {
                txtEmail.text = storage.email
                txtProvider.text = storage.provider.getFriendlyName(itemView.context)

                if (storage.isCredentialsStripped) {
                    layoutWarning?.visibility = View.VISIBLE
                } else {
                    layoutWarning?.visibility = View.GONE
                }

                if (isTv) {
                    itemView.setOnClickListener { showTvActionDialog(storage) }
                } else {
                    del?.setOnClickListener  { onDelete(storage) }
                    itemView.setOnClickListener { 
                        if (storage.isCredentialsStripped) {
                            Toast.makeText(
                                itemView.context,
                                R.string.backup_toast_please_fill_credentials,
                                Toast.LENGTH_SHORT
                            ).show()
                            launchSetup(storage)
                        } else {
                            val intent = Intent(itemView.context, NetworkBrowserActivity::class.java).apply {
                                putExtra(NetworkBrowserActivity.EXTRA_SHARE_ID, storage.id)
                                putExtra(NetworkBrowserActivity.EXTRA_STORAGE_LABEL, "${storage.displayName} - ${storage.email}")
                                putExtra("isOnlineStorage", true)
                            }
                            itemView.context.startActivity(intent)
                        }
                    }
                }
            }

            private fun launchSetup(storage: OnlineStorage) {
                val intent = if (storage.isS3Provider) {
                    Intent(itemView.context, S3SetupActivity::class.java).apply {
                        putExtra(S3SetupActivity.EXTRA_STORAGE_ID, storage.id)
                    }
                } else if (storage.isWebDavProvider) {
                    Intent(itemView.context, WebDavSetupActivity::class.java).apply {
                        putExtra(WebDavSetupActivity.EXTRA_STORAGE_ID, storage.id)
                    }
                } else {
                    null
                }
                if (intent != null) {
                    itemView.context.startActivity(intent)
                }
            }

            private fun showTvActionDialog(storage: OnlineStorage) {
                MaterialAlertDialogBuilder(itemView.context, R.style.UFM_Dialog)
                    .setTitle(storage.email)
                    .setItems(
                        arrayOf(
                            itemView.context.getString(R.string.network_action_browse),
                            itemView.context.getString(R.string.network_action_delete)
                        )
                    ) { _, which ->
                        when (which) {
                            0 -> {
                                if (storage.isCredentialsStripped) {
                                    Toast.makeText(
                                        itemView.context,
                                        R.string.backup_toast_please_fill_credentials,
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    launchSetup(storage)
                                } else {
                                    val intent = Intent(itemView.context, NetworkBrowserActivity::class.java).apply {
                                        putExtra(NetworkBrowserActivity.EXTRA_SHARE_ID, storage.id)
                                        putExtra(NetworkBrowserActivity.EXTRA_STORAGE_LABEL, "${storage.displayName} - ${storage.email}")
                                        putExtra("isOnlineStorage", true)
                                    }
                                    itemView.context.startActivity(intent)
                                }
                            }
                            1 -> onDelete(storage)
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }
}
