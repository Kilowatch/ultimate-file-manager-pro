package za.kilowatch.ultimatefilemanager.network

import android.content.Context
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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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
                // Also remove from the encrypted RClone config so orphaned entries don't accumulate
                if (storage.provider == OnlineStorageProvider.RCLONE) {
                    try {
                        RCloneConfig.removeProvider(this@OnlineStorageManagerActivity, storage.id)
                    } catch (_: Exception) {
                        // Non-fatal — encrypted config removal is best-effort
                    }
                }
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Decrypts RClone config, initialises rclone, creates the remote, and
     * launches [NetworkBrowserActivity] for the given [storage] entry.
     */
    /** Tracks whether rclone has been initialised for browsing. */
    private var rcloneInitialized = false

    private fun launchRCloneBrowse(ctx: Context, storage: OnlineStorage) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val configFile = RCloneConfig.decryptToTempFile(ctx)
                if (configFile == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(ctx, "RClone credentials not found. Please re-add the share.", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }

                // Always finalize and re-initialize rclone to clear its internal Fs cache.
                // The gomobile library caches filesystem instances by remote name, so
                // just calling config/delete + config/create would leave the old cached
                // instance pointing to the previous provider's backend.
                if (rcloneInitialized) {
                    try { gomobile.Gomobile.rcloneFinalize() } catch (_: Exception) {}
                    rcloneInitialized = false
                }
                gomobile.Gomobile.rcloneInitialize()
                gomobile.Gomobile.rcloneRPC("config/setpath", """{"path": "${configFile.absolutePath}"}""")
                rcloneInitialized = true

                // Read the encrypted config to get provider parameters
                val encryptedFile = File(ctx.filesDir, "rclone_encrypted.json")
                if (!encryptedFile.exists()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(ctx, "RClone config not found.", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }
                val encryptedBlob = encryptedFile.readText()
                val decryptedJson = org.json.JSONObject(
                    za.kilowatch.ultimatefilemanager.storage.VaultCrypto.decryptString(encryptedBlob)
                )
                // Look up the provider config by its stored key (provider ID).
                // Fall back to the first entry for backward compatibility.
                val providerId = decryptedJson.keys().asSequence().firstOrNull { it == storage.id }
                    ?: decryptedJson.keys().asSequence().firstOrNull()
                    ?: "filen"
                val providerJson = decryptedJson.getJSONObject(providerId)
                // Use storage.id as the unique remote name — matches the key
                // used in the encrypted config file, so the temp .conf and the
                // in-memory RC remote are always in sync.
                val providerType = providerJson.optString("type", providerId)
                // premiumizeme's Config callback always returns OAuth state,
                // which deadlocks on Android.  Bypass it by relying on the config
                // file that decryptToTempFile already wrote — rclone reads lazily.
                if (providerType != "premiumizeme") {
                    val createParams = org.json.JSONObject().apply {
                        put("name", storage.id)
                        put("type", providerType)
                        put("parameters", providerJson)
                    }
                    val createResult = gomobile.Gomobile.rcloneRPC("config/create", createParams.toString())
                    if (createResult.status != 200L) {
                        GoRoLog.e("RClone", "Failed to create remote: ${createResult.output}")
                        withContext(Dispatchers.Main) {
                            Toast.makeText(ctx, "Failed to initialize: ${createResult.output}", Toast.LENGTH_LONG).show()
                        }
                        return@launch
                    }
                } else {
                    GoRoLog.i("RClone", "Skipping config/create for premiumizeme — using file-based config")
                }

                // Navigate to the browser
                withContext(Dispatchers.Main) {
                    val intent = Intent(ctx, NetworkBrowserActivity::class.java).apply {
                        putExtra(NetworkBrowserActivity.EXTRA_SHARE_ID, storage.id)
                        putExtra(NetworkBrowserActivity.EXTRA_STORAGE_LABEL, "${storage.displayName} - ${storage.email}")
                        putExtra("isOnlineStorage", true)
                    }
                    ctx.startActivity(intent)
                }
            } catch (e: Exception) {
                GoRoLog.e("RClone", "Failed to launch RClone browse", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(ctx, "RClone error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
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
                        if (storage.provider == OnlineStorageProvider.RCLONE) {
                            launchRCloneBrowse(itemView.context, storage)
                            return@setOnClickListener
                        }
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
                                } else if (storage.provider == OnlineStorageProvider.RCLONE) {
                                    launchRCloneBrowse(itemView.context, storage)
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
