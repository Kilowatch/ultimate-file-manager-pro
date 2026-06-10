package za.kilowatch.ultimatefilemanager.network

/**
 * Common file/directory entry returned by SMB and FTP clients.
 */
data class NetworkFile(
    val name: String,
    val path: String,       // full remote path, e.g. "/share/Movies/film.mkv"
    val isDirectory: Boolean,
    val size: Long = 0L,
    val lastModified: Long = 0L,
    val freeSpace: Long = -1L,   // -1 = unknown; ≥0 = drive entry with storage info
    val iconRes: Int = 0,        // optional custom icon resource; 0 = use default
    val isToggle: Boolean = false, // true = render with a Switch instead of detail text
    val isToggled: Boolean = false // current state of the Switch
)
