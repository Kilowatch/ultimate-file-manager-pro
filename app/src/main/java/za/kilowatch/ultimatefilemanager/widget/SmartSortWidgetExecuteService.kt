package za.kilowatch.ultimatefilemanager.widget

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.smartsort.*

class SmartSortWidgetExecuteService : Service() {

    private val engine = SmartSortEngine()
    private var isCancelled = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val configId = intent?.getStringExtra(EXTRA_CONFIG_ID) ?: return START_NOT_STICKY

        scope.launch {
            val saved = SmartSortSavedConfigRepository.getById(configId)
            if (saved == null) {
                stopSelf()
                return@launch
            }

            Toast.makeText(this@SmartSortWidgetExecuteService, getString(R.string.smart_sort_widget_triggered, saved.description), Toast.LENGTH_SHORT).show()

            val config = parseConfig(saved.configJson)
            if (config == null) {
                stopSelf()
                return@launch
            }

            val notification = createNotification(getString(R.string.smart_sort_widget_sorting), 0)
            startForeground(NOTIFICATION_ID, notification)

            val result = withContext(Dispatchers.IO) {
                engine.execute(saved.folderPath, config) { fileName, current, total ->
                    val notif = createNotification(
                        getString(R.string.smart_sort_progress_moving, fileName, current, total),
                        (current * 100) / total
                    )
                    val manager = getSystemService(NotificationManager::class.java)
                    manager.notify(NOTIFICATION_ID, notif)
                }
            }

            val summary = buildString {
                appendLine(getString(R.string.smart_sort_result_moved, result.movedCount))
                appendLine(getString(R.string.smart_sort_result_skipped, result.skippedCount))
                if (result.failedCount > 0) {
                    appendLine(getString(R.string.smart_sort_result_failed, result.failedCount))
                }
            }

            val finalNotification = createNotification(summary, 100)
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, finalNotification)

            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
        }

        return START_REDELIVER_INTENT
    }

    private fun parseConfig(jsonStr: String): SmartSortConfig? {
        return try {
            val j = JSONObject(jsonStr)
            SmartSortConfig(
                sourcePath = j.optString("sourcePath", ""),
                sortConfigType = SortConfigType.valueOf(j.optString("sortConfigType", SortConfigType.STANDARD.name)),
                mode = SmartSortMode.valueOf(j.optString("mode", SmartSortMode.TYPE.name)),
                recursive = j.optBoolean("recursive", false),
                flattenSubfolders = j.optBoolean("flattenSubfolders", true),
                prefix = j.optString("prefix", "UFM"),
                includeOther = j.optBoolean("includeOther", false),
                duplicateStrategy = SmartSortConfig.DuplicateStrategy.valueOf(j.optString("duplicateStrategy", "RENAME")),
                existingFolderStrategy = SmartSortConfig.ExistingFolderStrategy.valueOf(j.optString("existingFolderStrategy", "MERGE")),
                shareInfo = j.optString("shareId", "")
                    .takeIf { it.isNotEmpty() }
                    ?.let { SmartSortShareHolder.resolve(it) }
                    ?: j.optString("sourceShareId", "")
                        .takeIf { it.isNotEmpty() }
                        ?.let { SmartSortShareHolder.resolve(it) },
                customRules = parseRules(j.optJSONArray("customRules")),
                customCategoryPaths = parseStringMap(j.optJSONObject("customCategoryPaths")),
                customCategoryShareIds = parseStringMap(j.optJSONObject("customCategoryShareIds"))
            )
        } catch (_: Exception) { null }
    }

    private fun parseRules(arr: org.json.JSONArray?): List<SmartSortCustomRule> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { i ->
            val r = arr.getJSONObject(i)
            val exts = mutableSetOf<String>()
            r.optJSONArray("extensions")?.let { e -> for (j in 0 until e.length()) exts.add(e.getString(j)) }
            SmartSortCustomRule(
                id = r.optString("id", java.util.UUID.randomUUID().toString()),
                description = r.optString("description", ""),
                extensions = exts,
                enabled = r.optBoolean("enabled", true)
            )
        }
    }

    private fun parseStringMap(obj: org.json.JSONObject?): Map<String, String> {
        if (obj == null) return emptyMap()
        val map = mutableMapOf<String, String>()
        for (key in obj.keys()) map[key] = obj.getString(key)
        return map
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.smart_sort_widget_sorting),
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(text: String, progress: Int): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sort)
            .setContentTitle(getString(R.string.smart_sort_title))
            .setContentText(text)
            .setProgress(100, progress, false)
            .setOngoing(progress < 100)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    companion object {
        const val EXTRA_CONFIG_ID = "extra_config_id"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "smart_sort_widget"
    }
}
