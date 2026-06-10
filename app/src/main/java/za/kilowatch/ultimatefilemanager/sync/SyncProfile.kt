package za.kilowatch.ultimatefilemanager.sync

import java.util.UUID

/**
 * Represents a sync configuration from a local phone folder to a network share.
 *
 * scheduleType is either "interval" (every N minutes) or "scheduled" (specific time each day/week/month).
 */
data class SyncProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,                           // User-facing label, e.g. getString(R.string.dcim_backup)
    val localUri: String,                       // SAF tree URI string
    val localDisplayPath: String,               // Human-readable path, e.g. "Internal/DCIM"
    val networkShareId: String,                 // References NetworkShare.id
    val remotePath: String,                     // Path on the remote share, e.g. "Backups/Phone"
    // --- Interval scheduling ---
    val scheduleType: String = "interval",      // "interval" | "scheduled"
    val intervalMinutes: Int = 60,              // Used when scheduleType == "interval"
    // --- Scheduled (time-based) ---
    val scheduledHour: Int = 2,                 // 0–23
    val scheduledMinute: Int = 0,               // 0–59
    val scheduledPeriod: String = "daily",      // "daily" | "weekly" | "monthly"
    val scheduledDayOfWeek: Int = 2,            // 1=Mon … 7=Sun (weekly only)
    val scheduledDayOfMonth: Int = 1,           // 1–28 (monthly only)
    // --- Status ---
    val enabled: Boolean = true,
    val notificationsEnabled: Boolean = true,
    var lastSyncTime: Long = 0L,
    var lastSyncFileCount: Int = 0
)
