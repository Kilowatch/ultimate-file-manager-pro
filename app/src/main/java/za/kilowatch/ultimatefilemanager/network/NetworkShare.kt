package za.kilowatch.ultimatefilemanager.network

import java.util.UUID

enum class ShareType { SMB, FTP, TV, SFTP, SCP, NFS, ONEDRIVE, GOOGLE_DRIVE, DROPBOX, AWS_S3, IDRIVE_E2, WEBDAV, DLNA }

/**
 * Represents a saved network share (SMB, FTP, SFTP, or SCP).
 * Stored as JSON in filesDir/network_shares.json.
 */
data class NetworkShare(
    val id: String          = UUID.randomUUID().toString(),
    val name: String        = "",          // user-facing label
    val type: ShareType     = ShareType.SMB,
    val host: String        = "",          // hostname or IP
    val port: Int           = 0,           // 0 = use protocol default
    val username: String    = "",          // blank = anonymous / guest
    val password: String    = "",          // For SFTP/SCP, this can be the password or encrypted key passphrase
    val domain: String      = "WORKGROUP", // SMB only
    val remotePath: String  = "",          // root path inside the share, e.g. "/share"
    val readOnly: Boolean   = true,
    val privateKeyPath: String? = null,    // SFTP/SCP only
    val useKeychain: Boolean = false,      // SFTP/SCP: if true, password field is the encrypted passphrase
    val smbProtocol: String = "AUTO",      // "AUTO", "SMB2", "SMB3"
    val dlnaUdn: String = "",              // Unique Device Name from UPnP description
    val dlnaContentDirectoryUrl: String = "",  // Full SOAP URL for ContentDirectory service
    val dlnaConnectionManagerUrl: String = "", // Full SOAP URL for ConnectionManager service
    val isCredentialsStripped: Boolean = false,
    val isServerMode: Boolean = false,
    val hostKeyFingerprint: String? = null   // SHA-256 hex; null = TOFU on next connect
) {
    val effectivePort: Int get() = when {
        port > 0  -> port
        type == ShareType.SMB -> 445
        type == ShareType.FTP -> 21
        type == ShareType.NFS -> 2049
        type == ShareType.DLNA -> 8200
        else -> 22 // SFTP and SCP default to 22
    }

    /** Document ID prefix used in UfmDocumentsProvider: "net:<id>/" */
    val docIdPrefix: String get() = "net:$id/"
}
