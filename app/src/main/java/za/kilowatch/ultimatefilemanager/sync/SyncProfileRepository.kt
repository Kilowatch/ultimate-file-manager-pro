package za.kilowatch.ultimatefilemanager.sync

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persists configured sync profiles to filesDir/sync_profiles.json.
 * Singleton — get via [SyncProfileRepository.getInstance].
 */
class SyncProfileRepository private constructor(private val context: Context) {

    private val file: File get() = File(context.filesDir, "sync_profiles.json")

    private val profiles = mutableListOf<SyncProfile>()

    init { load() }

    companion object {
        @Volatile private var instance: SyncProfileRepository? = null
        fun getInstance(ctx: Context): SyncProfileRepository {
            return instance ?: synchronized(this) {
                instance ?: SyncProfileRepository(ctx.applicationContext).also { instance = it }
            }
        }
    }

    fun getAll(): List<SyncProfile> = profiles.toList()

    fun getById(id: String): SyncProfile? = profiles.find { it.id == id }

    fun save(profile: SyncProfile) {
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
                    SyncProfile(
                        id = o.getString("id"),
                        name = o.getString("name"),
                        localUri = o.getString("localUri"),
                        localDisplayPath = o.getString("localDisplayPath"),
                        networkShareId = o.getString("networkShareId"),
                        remotePath = o.getString("remotePath"),
                        scheduleType = o.optString("scheduleType", "interval"),
                        intervalMinutes = o.optInt("intervalMinutes", 60),
                        scheduledHour = o.optInt("scheduledHour", 2),
                        scheduledMinute = o.optInt("scheduledMinute", 0),
                        scheduledPeriod = o.optString("scheduledPeriod", "daily"),
                        scheduledDayOfWeek = o.optInt("scheduledDayOfWeek", 2),
                        scheduledDayOfMonth = o.optInt("scheduledDayOfMonth", 1),
                        enabled = o.optBoolean("enabled", true),
                        notificationsEnabled = o.optBoolean("notificationsEnabled", true),
                        lastSyncTime = o.optLong("lastSyncTime", 0L),
                        lastSyncFileCount = o.optInt("lastSyncFileCount", 0)
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
                put("scheduleType", p.scheduleType)
                put("intervalMinutes", p.intervalMinutes)
                put("scheduledHour", p.scheduledHour)
                put("scheduledMinute", p.scheduledMinute)
                put("scheduledPeriod", p.scheduledPeriod)
                put("scheduledDayOfWeek", p.scheduledDayOfWeek)
                put("scheduledDayOfMonth", p.scheduledDayOfMonth)
                put("enabled", p.enabled)
                put("notificationsEnabled", p.notificationsEnabled)
                put("lastSyncTime", p.lastSyncTime)
                put("lastSyncFileCount", p.lastSyncFileCount)
            })
        }
        file.writeText(arr.toString(2))
    }
}
