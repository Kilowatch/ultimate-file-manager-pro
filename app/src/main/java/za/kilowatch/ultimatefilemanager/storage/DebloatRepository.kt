package za.kilowatch.ultimatefilemanager.storage

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class DebloatRepository(private val context: Context) {

    private val executor = Executors.newSingleThreadExecutor()
    private val cacheFile = File(context.cacheDir, "debloat_list.json")
    
    // Default UAD list URL (Next Generation - actively maintained)
    private val defaultUrl = "https://raw.githubusercontent.com/Universal-Debloater-Alliance/universal-android-debloater-next-generation/main/resources/assets/uad_lists.json"

    interface DebloatCallback {
        fun onSuccess(data: Map<String, DebloatApp>)
        fun onError(e: Exception)
    }

    /**
     * Gets the debloat list, either from cache or by fetching it.
     */
    fun getDebloatList(forceUpdate: Boolean = false, callback: DebloatCallback) {
        executor.execute {
            try {
                if (!forceUpdate && cacheFile.exists()) {
                    val json = cacheFile.readText()
                    val data = DebloatParser.parseUadJson(context, json)
                    callback.onSuccess(data)
                } else {
                    updateFromRemote(defaultUrl, callback)
                }
            } catch (e: Exception) {
                callback.onError(e)
            }
        }
    }

    private fun updateFromRemote(urlStr: String, callback: DebloatCallback) {
        try {
            val url = URL(urlStr)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val json = connection.inputStream.bufferedReader().use { it.readText() }
                cacheFile.writeText(json)
                val data = DebloatParser.parseUadJson(context, json)
                callback.onSuccess(data)
            } else {
                callback.onError(Exception("HTTP error: ${connection.responseCode}"))
            }
        } catch (e: Exception) {
            callback.onError(e)
        }
    }
}
