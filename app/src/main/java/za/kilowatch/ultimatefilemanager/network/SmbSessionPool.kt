package za.kilowatch.ultimatefilemanager.network

/**
 * Singleton SMB session manager.
 *
 * Multiplexed connection pooling, keepalive Echo, and idle eviction are managed
 * natively in the Go SMB client runtime. [closeAll] closes all active Go connections
 * and file handles when the app goes to background or network connectivity changes.
 */
object SmbSessionPool {

    fun closeAll() {
        runCatching {
            smbclient.Smbclient.smbCloseAll()
        }
    }
}
