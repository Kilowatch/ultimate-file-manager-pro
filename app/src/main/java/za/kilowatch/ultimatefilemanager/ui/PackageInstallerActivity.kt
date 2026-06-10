package za.kilowatch.ultimatefilemanager.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import za.kilowatch.ultimatefilemanager.util.PackageInstallerHelper
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Transparent-ish activity that receives ACTION_VIEW intents for APK/XAPK files.
 * Provides a bridge between external apps (or internal clicks) and [PackageInstallerHelper].
 */
class PackageInstallerActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "PackageInstallerActivity"
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Simple "Preparing Installation" UI
        setContentView(R.layout.activity_package_installer_bridge)
        
        val uri = intent.data ?: run {
            finish()
            return
        }

        lifecycleScope.launch {
            try {
                installFromUri(uri)
            } catch (e: SecurityException) {
                // Already handled by opening settings in PackageInstallerHelper
                Log.w(TAG, "SecurityException: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Installation failed", e)
                withContext(Dispatchers.Main) {
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(this@PackageInstallerActivity)
                        .setTitle(R.string.installation_failed)
                        .setMessage(e.message ?: getString(R.string.unknown_error))
                        .setPositiveButton(R.string.btn_ok) { _, _ -> finish() }
                        .setOnCancelListener { finish() }
                        .show()
                }
                return@launch
            }
            finish()
        }
    }

    private suspend fun installFromUri(uri: Uri) = withContext(Dispatchers.IO) {
        val file = if (uri.scheme == "file") {
            File(uri.path!!)
        } else {
            // content:// URI - copy to temp cache file
            copyToTempFile(uri)
        }

        if (!file.exists()) {
            throw IllegalArgumentException("${getString(R.string.error_file_not_found)}: ${file.absolutePath}")
        }

        val ext = file.extension.lowercase()
        if (ext == "apk") {
            PackageInstallerHelper.installApk(this@PackageInstallerActivity, file)
        } else if (ext == "xapk" || ext == "apks") {
            PackageInstallerHelper.installXapk(this@PackageInstallerActivity, file)
        } else {
            throw IllegalArgumentException("${getString(R.string.error_not_apk_xapk)}: .$ext")
        }
    }

    private fun copyToTempFile(uri: Uri): File {
        val inputStream = contentResolver.openInputStream(uri) ?: throw Exception(getString(R.string.error_failed_open_uri))
        val tempFile = File(cacheDir, "install_temp_${UUID.randomUUID()}.${getFileExtension(uri)}")
        inputStream.use { input ->
            tempFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        return tempFile
    }

    private fun getFileExtension(uri: Uri): String {
        val path = uri.path ?: return "apk"
        return path.substringAfterLast('.', "apk")
    }
}
