package za.kilowatch.ultimatefilemanager.storage

import android.content.Intent
import android.os.Bundle
import android.util.Log
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import za.kilowatch.ultimatefilemanager.BuildConfig
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

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
        private const val TAG = "VaultBrowser"
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

    override fun onDestroy() {
        // SEC-§8.12: Ensure decrypted mirror files never linger in private storage
        try {
            if (::mirrorDir.isInitialized && mirrorDir.exists()) {
                mirrorDir.deleteRecursively()
            }
        } catch (_: Exception) {}
        scope.cancel()
        super.onDestroy()
    }

    private fun decryptToMirrorAndOpen() {
        val progressView = layoutInflater.inflate(R.layout.dialog_vault_progress, null)
        val txtProgress = progressView.findViewById<TextView>(R.id.txtVaultProgress)
        val progressBar = progressView.findViewById<ProgressBar>(R.id.progressVault)

        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(progressView)
            .setCancelable(false)
            .create()
        dialog.show()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))

        scope.launch {
            val success = withContext(Dispatchers.IO) {
                try {
                    mirrorDir.mkdirs()
                    val total = entry.files.size.coerceAtLeast(1)
                    val completed = AtomicInteger(0)
                    var lastProgressUpdate = 0L

                    // Utilize multi-core parallel worker pool
                    val numWorkers = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
                    val dispatcher = Dispatchers.IO.limitedParallelism(numWorkers)

                    coroutineScope {
                        entry.files.map { relative ->
                            async(dispatcher) {
                                val encryptedFile = File(entryDir, "$relative.enc")
                                val outputFile = File(mirrorDir, relative)

                                // Smart incremental sync: only decrypt if missing, empty, or outdated
                                val needsDecryption = !outputFile.exists() ||
                                        outputFile.length() == 0L ||
                                        outputFile.lastModified() < encryptedFile.lastModified()

                                if (needsDecryption && encryptedFile.exists()) {
                                    outputFile.parentFile?.mkdirs()
                                    VaultCrypto.decryptFile(encryptedFile, outputFile)
                                    outputFile.setLastModified(encryptedFile.lastModified())
                                }

                                val current = completed.incrementAndGet()
                                val now = System.currentTimeMillis()
                                if (now - lastProgressUpdate > 50 || current == total) {
                                    lastProgressUpdate = now
                                    val percent = ((current.toFloat() / total.toFloat()) * 100).toInt()
                                    runOnUiThread {
                                        txtProgress.text = getString(R.string.vault_opening, current, total)
                                        progressBar.progress = percent
                                    }
                                }
                            }
                        }.awaitAll()
                    }
                    true
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) Log.e(TAG, "Decryption failed", e)
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

        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(progressView)
            .setCancelable(false)
            .create()
        dialog.show()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))

        scope.launch {
            val success = withContext(Dispatchers.IO) {
                try {
                    // Filter out system files and hidden files
                    val mirrorFiles = if (mirrorDir.exists()) {
                        mirrorDir.walkTopDown()
                            .filter { it.isFile }
                            .filter { !isSystemFile(it) }
                            .filter { !isHiddenFile(it) }
                            .toList()
                    } else emptyList()

                    val relativeList = java.util.Collections.synchronizedList(mutableListOf<String>())
                    val currentMirrorRelatives = java.util.Collections.synchronizedSet(HashSet<String>())
                    val total = mirrorFiles.size.coerceAtLeast(1)
                    val completed = AtomicInteger(0)
                    var lastProgressUpdate = 0L

                    val numWorkers = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
                    val dispatcher = Dispatchers.IO.limitedParallelism(numWorkers)

                    coroutineScope {
                        mirrorFiles.map { file ->
                            val relative = file.relativeTo(mirrorDir).path
                            currentMirrorRelatives.add(relative)
                            relativeList.add(relative)
                            async(dispatcher) {
                                val encryptedFile = File(entryDir, "$relative.enc")
                                // Smart incremental check: only re-encrypt if changed or missing
                                val needsReEncryption = !encryptedFile.exists() ||
                                        file.lastModified() > encryptedFile.lastModified() ||
                                        file.length() == 0L

                                if (needsReEncryption) {
                                    val tempEncrypted = File(entryDir, "$relative.enc.tmp")
                                    tempEncrypted.parentFile?.mkdirs()
                                    VaultCrypto.encryptFile(file, tempEncrypted)
                                    if (tempEncrypted.exists()) {
                                        encryptedFile.delete()
                                        tempEncrypted.renameTo(encryptedFile)
                                    }
                                }

                                val current = completed.incrementAndGet()
                                val now = System.currentTimeMillis()
                                if (now - lastProgressUpdate > 50 || current == total) {
                                    lastProgressUpdate = now
                                    val percent = ((current.toFloat() / total.toFloat()) * 100).toInt()
                                    runOnUiThread {
                                        txtProgress.text = getString(R.string.vault_reencrypting, current, total)
                                        progressBar.progress = percent
                                    }
                                }
                            }
                        }.awaitAll()
                    }

                    // Delete encrypted files whose source in mirror was removed
                    entry.files.forEach { oldRelative ->
                        if (!currentMirrorRelatives.contains(oldRelative)) {
                            File(entryDir, "$oldRelative.enc").delete()
                        }
                    }

                    // Clean empty directories in entryDir
                    entryDir.walkBottomUp().forEach { dir ->
                        if (dir.isDirectory && dir != entryDir && dir.listFiles()?.isEmpty() == true) {
                            dir.delete()
                        }
                    }

                    // Crash-safe atomic metadata write with bulk payload + legacy fallback
                    val sortedRelatives = relativeList.toList()
                    val metadata = JSONObject().apply {
                        put("id", entry.id)
                        put("displayName", encryptField(entry.displayName))
                        put("originalRoot", encryptField(entry.originalRoot))
                        put("filesPayload", VaultCrypto.encryptStrings(sortedRelatives))
                        put("files", JSONArray(sortedRelatives.map { encryptField(it) }))
                    }
                    val tempMeta = File(entryDir, "$META_FILE.tmp")
                    tempMeta.writeText(metadata.toString())
                    val finalMeta = File(entryDir, META_FILE)
                    finalMeta.delete()
                    tempMeta.renameTo(finalMeta)

                    // SEC-§8.12: Cleanly wipe mirror directory so decrypted content never persists at rest
                    mirrorDir.deleteRecursively()
                    true
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) Log.e(TAG, "Re-encryption failed", e)
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
            if (BuildConfig.DEBUG) Log.w(TAG, "encryptField failed", e)
            plain
        }
    }

    /** Decrypt a field from metadata.json (enc: prefix → decrypt, otherwise pass through). */
    private fun decryptField(encrypted: String): String {
        if (!encrypted.startsWith("enc:")) return encrypted
        return try {
            VaultCrypto.decryptString(encrypted.removePrefix("enc:"))
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "decryptField failed — using fallback", e)
            encrypted
        }
    }

    private fun readEntry(dir: File): VaultEntry? {
        val metadataFile = File(dir, META_FILE)
        val fileToRead = if (metadataFile.exists()) metadataFile else File(dir, "$META_FILE.bak")
        if (!fileToRead.exists()) return null
        return try {
            val json = JSONObject(fileToRead.readText())
            val id = json.getString("id")
            val displayName = decryptField(json.getString("displayName"))
            val originalRoot = decryptField(json.optString("originalRoot", ""))

            val files: List<String> = if (json.has("filesPayload")) {
                val payload = json.getString("filesPayload")
                VaultCrypto.decryptStrings(payload)
            } else if (json.has("files")) {
                val filesJson = json.getJSONArray("files")
                val list = ArrayList<String>(filesJson.length())
                for (i in 0 until filesJson.length()) {
                    list.add(decryptField(filesJson.getString(i)))
                }
                list
            } else {
                emptyList()
            }

            VaultEntry(
                id = id,
                displayName = displayName,
                originalRoot = originalRoot,
                files = files
            )
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "readEntry failed", e)
            null
        }
    }
}

