package za.kilowatch.ultimatefilemanager.storage

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import za.kilowatch.ultimatefilemanager.R
import android.util.Log
import za.kilowatch.ultimatefilemanager.BuildConfig
import za.kilowatch.ultimatefilemanager.storage.VaultCrypto
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import java.io.File
import za.kilowatch.ultimatefilemanager.settings.FontSizeHelper
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper

class VaultBrowserActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val vaultBrowserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        reEncryptAndFinish()
    }

    companion object {
        const val EXTRA_ENTRY_ID = "vault_entry_id"
        private const val META_FILE = "metadata.json"
    }

    private lateinit var entryDir: File
    private lateinit var mirrorDir: File
    private lateinit var entry: VaultEntry

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // SEC-§8.12: Vault browser exposes decrypted files — prevent screenshots
        // and recent-apps thumbnails from leaking vault contents.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (DeviceUtils.isTvDevice(this)) {
            setContentView(R.layout.activity_vault_browser_tv)
        } else {
            setContentView(R.layout.activity_vault_browser)
        }

        val entryId = intent.getStringExtra(EXTRA_ENTRY_ID) ?: run {
            finish()
            return
        }
        entryDir = File(filesDir, "vault/$entryId")
        mirrorDir = File(filesDir, "vault_mirror/$entryId")

        val loaded = readEntry(entryDir)
        if (loaded == null) {
            Toast.makeText(this, getString(R.string.vault_decryption_failed), Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        entry = loaded

        decryptToMirrorAndOpen()
    }

    private fun decryptToMirrorAndOpen() {
        val progressView = layoutInflater.inflate(R.layout.dialog_vault_progress, null)
        val txtProgress = progressView.findViewById<TextView>(R.id.txtVaultProgress)
        val progressBar = progressView.findViewById<ProgressBar>(R.id.progressVault)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.vault_open)
            .setView(progressView)
            .setCancelable(false)
            .create()
        dialog.show()

        scope.launch {
            val success = withContext(Dispatchers.IO) {
                try {
                    mirrorDir.deleteRecursively()
                    mirrorDir.mkdirs()
                    val total = entry.files.size.coerceAtLeast(1)
                    entry.files.forEachIndexed { index, relative ->
                        val encryptedFile = File(entryDir, "$relative.enc")
                        val outputFile = File(mirrorDir, relative)
                        VaultCrypto.decryptFile(encryptedFile, outputFile)
                        val current = index + 1
                        val percent = ((current.toFloat() / total.toFloat()) * 100).toInt()
                        runOnUiThread {
                            txtProgress.text = getString(R.string.vault_opening, current, total)
                            progressBar.progress = percent
                        }
                    }
                    true
                } catch (_: Exception) {
                    false
                }
            }

            dialog.dismiss()
            if (!success) {
                Toast.makeText(this@VaultBrowserActivity, getString(R.string.vault_decryption_failed), Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }

            val intent = Intent(this@VaultBrowserActivity, FileBrowserActivity::class.java).apply {
                putExtra(FileBrowserActivity.EXTRA_MOUNT_PATH, mirrorDir.absolutePath)
                putExtra(FileBrowserActivity.EXTRA_STORAGE_LABEL, entry.displayName)
            }
            vaultBrowserLauncher.launch(intent)
        }
    }

    private fun reEncryptAndFinish() {
        val progressView = layoutInflater.inflate(R.layout.dialog_vault_progress, null)
        val txtProgress = progressView.findViewById<TextView>(R.id.txtVaultProgress)
        val progressBar = progressView.findViewById<ProgressBar>(R.id.progressVault)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.vault_encrypt)
            .setView(progressView)
            .setCancelable(false)
            .create()
        dialog.show()

        scope.launch {
            val success = withContext(Dispatchers.IO) {
                try {
                    // Don't delete entryDir first - we need to preserve existing encrypted files
                    // in case re-encryption fails. Instead, we'll update files incrementally.
                    
                    // Filter out system files and hidden files
                    val files = mirrorDir.walkTopDown()
                        .filter { it.isFile }
                        .filter { !isSystemFile(it) }
                        .filter { !isHiddenFile(it) }
                        .toList()
                    val relativeList = mutableListOf<String>()
                    val total = files.size.coerceAtLeast(1)

                    files.forEachIndexed { index, file ->
                        val relative = file.relativeTo(mirrorDir).path
                        val encryptedFile = File(entryDir, "$relative.enc")
                        // Ensure parent directory exists
                        encryptedFile.parentFile?.mkdirs()
                        VaultCrypto.encryptFile(file, encryptedFile)
                        relativeList.add(relative)
                        val current = index + 1
                        val percent = ((current.toFloat() / total.toFloat()) * 100).toInt()
                        runOnUiThread {
                            txtProgress.text = getString(R.string.vault_reencrypting, current, total)
                            progressBar.progress = percent
                        }
                    }

                    val metadata = JSONObject().apply {
                        put("id", entry.id)
                        put("displayName", encryptField(entry.displayName))
                        put("originalRoot", encryptField(entry.originalRoot))
                        put("files", JSONArray(relativeList.map { encryptField(it) }))
                    }
                    File(entryDir, META_FILE).writeText(metadata.toString())

                    mirrorDir.deleteRecursively()
                    true
                } catch (_: Exception) {
                    false
                }
            }

            dialog.dismiss()
            if (!success) {
                Toast.makeText(this@VaultBrowserActivity, getString(R.string.vault_encryption_failed), Toast.LENGTH_SHORT).show()
            }
            finish()
        }
    }

    /**
     * Checks if a file is a system file that should not be encrypted.
     * Note: This is used when re-encrypting from mirror, so we need to be careful
     * not to filter out files in our own app's internal storage.
     */
    private fun isSystemFile(file: File): Boolean {
        val path = file.absolutePath.lowercase()
        
        // Don't filter out files in our own app's internal storage
        // (mirrorDir is in filesDir which is in /data/data/<package>/)
        if (path.startsWith(filesDir.absolutePath.lowercase())) {
            return false
        }
        
        val systemPaths = listOf(
            "/system/", "/proc/", "/sys/", "/dev/", "/data/system/",
            "/data/dalvik-cache/", "/data/app/"
        )
        
        if (systemPaths.any { path.startsWith(it) }) return true
        
        val systemFilePatterns = listOf(
            ".nomedia", "thumbs.db", "desktop.ini", ".ds_store",
            ".trash-", ".spotlight-", ".fseventsd", ".temporaryitems",
            ".localized", ".com.apple.timemachine.donotpresent"
        )
        
        return systemFilePatterns.any { file.name.lowercase() == it.lowercase() }
    }

    /**
     * Checks if a file is hidden (starts with dot) or in a hidden directory.
     */
    private fun isHiddenFile(file: File): Boolean {
        if (file.name.startsWith(".")) return true
        
        var parent = file.parentFile
        while (parent != null) {
            if (parent.name.startsWith(".")) return true
            parent = parent.parentFile
        }
        
        return false
    }

    /** Encrypt a field for storage in metadata.json. */
    private fun encryptField(plain: String): String {
        return try {
            "enc:" + VaultCrypto.encryptString(plain)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w("VaultBrowser", "encryptField failed", e)
            plain
        }
    }

    /** Decrypt a field from metadata.json (enc: prefix → decrypt, otherwise pass through). */
    private fun decryptField(encrypted: String): String {
        if (!encrypted.startsWith("enc:")) return encrypted
        return try {
            VaultCrypto.decryptString(encrypted.removePrefix("enc:"))
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w("VaultBrowser", "decryptField failed — using fallback", e)
            encrypted
        }
    }

    private fun readEntry(dir: File): VaultEntry? {
        return try {
            val metadataFile = File(dir, META_FILE)
            if (!metadataFile.exists()) return null
            val json = JSONObject(metadataFile.readText())
            val filesJson = json.getJSONArray("files")
            val files = mutableListOf<String>()
            for (i in 0 until filesJson.length()) {
                files.add(decryptField(filesJson.getString(i)))
            }
            VaultEntry(
                id = json.getString("id"),
                displayName = decryptField(json.getString("displayName")),
                originalRoot = decryptField(json.getString("originalRoot")),
                files = files
            )
        } catch (_: Exception) {
            null
        }
    }
}
