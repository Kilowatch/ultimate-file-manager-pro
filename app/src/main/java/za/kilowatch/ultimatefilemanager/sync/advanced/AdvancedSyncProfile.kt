package za.kilowatch.ultimatefilemanager.sync.advanced

import java.util.UUID

/**
 * Represents an advanced sync profile with direction, conflict resolution,
 * instant sync, and network constraint support.
 *
 * Fully independent from [za.kilowatch.ultimatefilemanager.sync.SyncProfile].
 * Profiles are persisted to `advanced_sync_profiles.json`.
 *
 * Direction modes:
 * - "upload" — local → remote
 * - "download" — remote → local
 * - "twoway" — bidirectional with conflict resolution
 *
 * Conflict strategies (for twoway only):
 * - "skip" — leave both sides untouched
 * - "newest" — newer timestamp wins
 * - "keep_local" — local version overwrites remote
 * - "keep_remote" — remote version overwrites local
 *
 * Schedule types:
 * - "interval" — every N minutes
 * - "scheduled" — specific time daily/weekly/monthly
 * - "manual" — no schedule, triggers only (instant sync or WiFi)
 */
data class AdvancedSyncProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val localUri: String,
    val localDisplayPath: String,
    val networkShareId: String,
    val remotePath: String,
    // --- Direction & conflict ---
    val direction: String = "upload",               // "upload" | "download" | "twoway"
    val conflictStrategy: String = "skip",           // "skip" | "newest" | "keep_local" | "keep_remote"
    // --- Scheduling ---
    val scheduleType: String = "interval",           // "interval" | "scheduled" | "manual"
    val intervalMinutes: Int = 60,
    val scheduledHour: Int = 2,
    val scheduledMinute: Int = 0,
    val scheduledPeriod: String = "daily",           // "daily" | "weekly" | "monthly"
    val scheduledDayOfWeek: Int = 2,                 // 1=Mon … 7=Sun
    val scheduledDayOfMonth: Int = 1,                // 1–28
    // --- Triggers & constraints ---
    val instantSyncEnabled: Boolean = false,
    val syncDeletions: Boolean = false,              // true = delete destination files that no longer exist on source
    val moveFiles: Boolean = false,                  // true = delete source files after successful transfer (cut instead of copy)
    val extensionMode: String = "all",               // "all" | "only" | "skip"
    val extensionFilters: String = "",               // comma-separated extensions for "only" or "skip" mode
    val excludePatterns: String = "",                // comma-separated name patterns to exclude
    val minSizeBytes: Long = 0L,                     // minimum file size in bytes (0 = no limit)
    val maxSizeBytes: Long = 0L,                     // maximum file size in bytes (0 = no limit)
    val minSizeIsGB: Boolean = false,                // true = min size value is in GB, false = MB
    val maxSizeIsGB: Boolean = false,                // true = max size value is in GB, false = MB
    val minAgeMinutes: Long = 0L,                    // minimum file age in minutes (0 = no limit)
    val maxAgeMinutes: Long = 0L,                    // maximum file age in minutes (0 = no limit)
    val downloadSubfolders: Boolean = false,         // true = recursively download files from subfolders (download direction only)
    val wifiOnly: Boolean = false,                   // true = only sync on unmetered (WiFi)
    // --- Status ---
    val enabled: Boolean = true,
    val notificationsEnabled: Boolean = true,
    var lastSyncTime: Long = 0L,
    var lastSyncFileCount: Int = 0,
    var syncedFileHashes: String = "",               // SHA-256 hashes of synced file names (no plain-text names stored)
    var conflictLogJson: String = ""                 // JSON array of conflict entries
)
