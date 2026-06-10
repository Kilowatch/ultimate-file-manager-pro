package za.kilowatch.ultimatefilemanager.network

data class DlnaServerInfo(
    val udn: String,                  // Unique Device Name — UUID from device description
    val friendlyName: String,         // e.g. "UFM Media Server", "Synology NAS"
    val ip: String,                   // IPv4 address string like "192.168.1.100"
    val port: Int,                    // HTTP port (default 8200)
    val contentDirectoryUrl: String,  // Full SOAP endpoint URL for ContentDirectory service
    val connectionManagerUrl: String, // Full SOAP endpoint URL for ConnectionManager service
    val lastSeen: Long = System.currentTimeMillis()  // Timestamp of last SSDP message
)
