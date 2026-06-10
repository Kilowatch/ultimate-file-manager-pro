package za.kilowatch.ultimatefilemanager.network

import za.kilowatch.ultimatefilemanager.R
import java.util.UUID

enum class OnlineStorageProvider { ONEDRIVE, GOOGLE_DRIVE, DROPBOX, AWS_S3, IDRIVE_E2, WEBDAV }

data class OnlineStorage(
    val id: String = UUID.randomUUID().toString(),
    val provider: OnlineStorageProvider = OnlineStorageProvider.ONEDRIVE,
    /** For OAuth providers: the signed-in email address.
     *  For S3 providers: user-supplied display label (e.g. "My Bucket").
     *  For WebDAV providers: user-supplied display label (e.g. "My Nextcloud"). */
    val email: String = "",
    val displayName: String = "",
    val refreshToken: String? = null,

    // --- S3-family fields (AWS S3, IDrive e2, Backblaze B2, etc.) ---
    /** Full endpoint URL, e.g. "https://s3.amazonaws.com" or "https://s3.us-west-004.backblazeb2.com" */
    val s3Endpoint: String? = null,
    /** Bucket name */
    val s3Bucket: String? = null,
    /** AWS region or equivalent, e.g. "us-east-1" */
    val s3Region: String? = null,
    /** S3 Access Key ID — stored in plaintext (not a secret by itself) */
    val s3AccessKey: String? = null,
    /** S3 Secret Access Key — encrypted at rest via VaultCrypto */
    val s3SecretKey: String? = null,

    // --- WebDAV fields ---
    /** Full WebDAV base URL, e.g. "https://cloud.example.com/remote.php/dav/files/user/" */
    val webDavUrl: String? = null,
    /** WebDAV username — stored in plaintext */
    val webDavUsername: String? = null,
    /** WebDAV password — encrypted at rest via VaultCrypto */
    val webDavPassword: String? = null,
    val isCredentialsStripped: Boolean = false
) {
    val docIdPrefix: String get() = "os:$id/"

    /** True for providers that use S3-compatible credential-based auth */
    val isS3Provider: Boolean get() = provider == OnlineStorageProvider.AWS_S3
                                   || provider == OnlineStorageProvider.IDRIVE_E2

    /** True for WebDAV providers */
    val isWebDavProvider: Boolean get() = provider == OnlineStorageProvider.WEBDAV

    fun getDisplayName(context: android.content.Context): String {
        return when (provider) {
            OnlineStorageProvider.ONEDRIVE     -> context.getString(R.string.add_online_storage_onedrive)
            OnlineStorageProvider.GOOGLE_DRIVE -> context.getString(R.string.add_online_storage_gdrive)
            OnlineStorageProvider.DROPBOX      -> context.getString(R.string.add_online_storage_dropbox)
            OnlineStorageProvider.AWS_S3       -> context.getString(R.string.add_online_storage_aws_s3)
            OnlineStorageProvider.IDRIVE_E2    -> context.getString(R.string.add_online_storage_idrive_e2)
            OnlineStorageProvider.WEBDAV       -> context.getString(R.string.add_online_storage_webdav)
        }
    }
}

fun OnlineStorageProvider.getFriendlyName(context: android.content.Context): String {
    return when (this) {
        OnlineStorageProvider.ONEDRIVE     -> context.getString(R.string.add_online_storage_onedrive)
        OnlineStorageProvider.GOOGLE_DRIVE -> context.getString(R.string.add_online_storage_gdrive)
        OnlineStorageProvider.DROPBOX      -> context.getString(R.string.add_online_storage_dropbox)
        OnlineStorageProvider.AWS_S3       -> context.getString(R.string.add_online_storage_aws_s3)
        OnlineStorageProvider.IDRIVE_E2    -> context.getString(R.string.add_online_storage_idrive_e2)
        OnlineStorageProvider.WEBDAV       -> context.getString(R.string.add_online_storage_webdav)
    }
}

