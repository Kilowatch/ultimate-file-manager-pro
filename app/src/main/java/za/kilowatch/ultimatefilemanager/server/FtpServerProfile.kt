package za.kilowatch.ultimatefilemanager.server

import java.util.UUID

/**
 * Represents the type of default location a server profile points to.
 */
enum class LocationType {
    LOCAL, SAF, SMB, FTP, SFTP, TV, GOOGLE_DRIVE, ONEDRIVE
}

/**
 * Represents a user profile for the hosted FTP/SFTP server.
 *
 * Passwords are encrypted at rest using AES-256 via [VaultCrypto].
 * The [defaultLocationUri] uses a scheme-based format:
 *   - file:///path          (local)
 *   - smb://shareId/path   (SMB)
 *   - ftp://shareId/path   (FTP)
 *   - sftp://shareId/path  (SFTP)
 *   - tv://deviceId/path   (Paired TV)
 *   - gdrive://id/path     (Google Drive)
 *   - onedrive://id/path   (OneDrive)
 */
data class FtpServerProfile(
    val id: String = UUID.randomUUID().toString(),
    val username: String,
    val encryptedPassword: String = "",      // AES-256 encrypted via VaultCrypto
    val defaultLocationUri: String,          // Unified URI — see format above
    val defaultLocationLabel: String,        // Human-readable label
    val locationType: LocationType = LocationType.LOCAL,
    val locationMetaId: String? = null,      // ID reference for network shares / online storages
    val readOnly: Boolean = false,           // Per-profile read-only vs read-write
    val authorizedKeys: String = "",         // SSH public keys, one per line, like ~/.ssh/authorized_keys
    val isCredentialsStripped: Boolean = false
)
