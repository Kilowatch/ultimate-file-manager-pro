package za.kilowatch.ultimatefilemanager.sync.advanced

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persists advanced sync profiles to filesDir/advanced_sync_profiles.json.
 * Singleton — get via [AdvancedSyncProfileRepository.getInstance].
 *
 * Fully independent from [za.kilowatch.ultimatefilemanager.sync.SyncProfileRepository].
 */
class AdvancedSyncProfileRepository private constructor(private val context: Context) {

    private val file: File get() = File(context.filesDir, "advanced_sync_profiles.json")

    private val profiles = mutableListOf<AdvancedSyncProfile>()

    init { load() }

    companion object {
        @Volatile private var instance: AdvancedSyncProfileRepository? = null
        fun getInstance(ctx: Context): AdvancedSyncProfileRepository {
            return instance ?: synchronized(this) {
                instance ?: AdvancedSyncProfileRepository(ctx.applicationContext).also { instance = it }
            }
        }
    }

    fun getAll(): List<AdvancedSyncProfile> = profiles.toList()

    fun getById(id: String): AdvancedSyncProfile? = profiles.find { it.id == id }

    fun save(profile: AdvancedSyncProfile) {
        val idx = profiles.indexOfFirst { it.id == profile.id }
        if (idx >= 0) profiles[idx] = profile else profiles.add(profile)
        persist()
    }

    fun delete(id: String) {
        profiles.removeAll { it.id == id }
        persist()
    }

    private fun load() {
        profiles.clear()
        if (!file.exists()) return
        runCatching {
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                profiles.add(
                    AdvancedSyncProfile(
                        id = o.getString("id"),
                        name = o.getString("name"),
                        localUri = o.getString("localUri"),
                        localDisplayPath = o.getString("localDisplayPath"),
                        networkShareId = o.getString("networkShareId"),
                        remotePath = o.getString("remotePath"),
                        destLocalUri = o.optString("destLocalUri", ""),
                        destLocalDisplayPath = o.optString("destLocalDisplayPath", ""),
                        direction = o.optString("direction", "upload"),
                        conflictStrategy = o.optString("conflictStrategy", "skip"),
                        scheduleType = o.optString("scheduleType", "interval"),
                        intervalMinutes = o.optInt("intervalMinutes", 60),
                        scheduledHour = o.optInt("scheduledHour", 2),
                        scheduledMinute = o.optInt("scheduledMinute", 0),
                        scheduledPeriod = o.optString("scheduledPeriod", "daily"),
                        scheduledDayOfWeek = o.optInt("scheduledDayOfWeek", 2),
                        scheduledDayOfMonth = o.optInt("scheduledDayOfMonth", 1),
                        instantSyncEnabled = o.optBoolean("instantSyncEnabled", false),
                        syncDeletions = o.optBoolean("syncDeletions", false),
                        moveFiles = o.optBoolean("moveFiles", false),
                        extensionMode = o.optString("extensionMode", "all"),
                        extensionFilters = o.optString("extensionFilters", ""),
                        excludePatterns = o.optString("excludePatterns", ""),
                        includePatterns = o.optString("includePatterns", ""),
                        minSizeBytes = o.optLong("minSizeBytes", 0L),
                        maxSizeBytes = o.optLong("maxSizeBytes", 0L),
                        minSizeIsGB = o.optBoolean("minSizeIsGB", false),
                        maxSizeIsGB = o.optBoolean("maxSizeIsGB", false),
                        minAgeMinutes = o.optLong("minAgeMinutes", 0L),
                        maxAgeMinutes = o.optLong("maxAgeMinutes", 0L),
                        downloadSubfolders = o.optBoolean("downloadSubfolders", false),
                        wifiOnly = o.optBoolean("wifiOnly", false),
                        enabled = o.optBoolean("enabled", true),
                        notificationsEnabled = o.optBoolean("notificationsEnabled", true),
                        lastSyncTime = o.optLong("lastSyncTime", 0L),
                        lastSyncFileCount = o.optInt("lastSyncFileCount", 0),
                        syncedFileHashes = o.optString("syncedFileHashes", ""),
                        conflictLogJson = o.optString("conflictLogJson", "")
                    )
                )
            }
        }
    }

    private fun persist() {
        val arr = JSONArray()
        profiles.forEach { p ->
            arr.put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("localUri", p.localUri)
                put("localDisplayPath", p.localDisplayPath)
                put("networkShareId", p.networkShareId)
                put("remotePath", p.remotePath)
                put("destLocalUri", p.destLocalUri)
                put("destLocalDisplayPath", p.destLocalDisplayPath)
                put("direction", p.direction)
                put("conflictStrategy", p.conflictStrategy)
                put("scheduleType", p.scheduleType)
                put("intervalMinutes", p.intervalMinutes)
                put("scheduledHour", p.scheduledHour)
                put("scheduledMinute", p.scheduledMinute)
                put("scheduledPeriod", p.scheduledPeriod)
                put("scheduledDayOfWeek", p.scheduledDayOfWeek)
                put("scheduledDayOfMonth", p.scheduledDayOfMonth)
                put("instantSyncEnabled", p.instantSyncEnabled)
                put("syncDeletions", p.syncDeletions)
                put("moveFiles", p.moveFiles)
                put("extensionMode", p.extensionMode)
                put("extensionFilters", p.extensionFilters)
                put("excludePatterns", p.excludePatterns)
                put("includePatterns", p.includePatterns)
                put("minSizeBytes", p.minSizeBytes)
                put("maxSizeBytes", p.maxSizeBytes)
                put("minSizeIsGB", p.minSizeIsGB)
                put("maxSizeIsGB", p.maxSizeIsGB)
                put("minAgeMinutes", p.minAgeMinutes)
                put("maxAgeMinutes", p.maxAgeMinutes)
                put("downloadSubfolders", p.downloadSubfolders)
                put("wifiOnly", p.wifiOnly)
                put("enabled", p.enabled)
                put("notificationsEnabled", p.notificationsEnabled)
                put("lastSyncTime", p.lastSyncTime)
                put("lastSyncFileCount", p.lastSyncFileCount)
                put("syncedFileHashes", p.syncedFileHashes)
                put("conflictLogJson", p.conflictLogJson)
            })
        }
        file.writeText(arr.toString(2))
    }
}
