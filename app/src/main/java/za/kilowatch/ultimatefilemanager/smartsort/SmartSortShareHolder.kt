package za.kilowatch.ultimatefilemanager.smartsort

import za.kilowatch.ultimatefilemanager.network.NetworkShare
import za.kilowatch.ultimatefilemanager.network.NetworkShareRepository
import za.kilowatch.ultimatefilemanager.network.OnlineStorageRepository
import za.kilowatch.ultimatefilemanager.network.OnlineStorageProvider
import za.kilowatch.ultimatefilemanager.network.ShareType
import za.kilowatch.ultimatefilemanager.UfmApplication

object SmartSortShareHolder {
    private var currentShare: NetworkShare? = null

    fun set(share: NetworkShare) {
        currentShare = share
    }

    fun get(): NetworkShare? = currentShare

    fun clear() {
        currentShare = null
    }

    fun resolve(shareId: String): NetworkShare? {
        currentShare?.let { if (it.id == shareId) return it }

        val app = UfmApplication.instance
        val netShare = NetworkShareRepository.getInstance(app).getById(shareId)
        if (netShare != null) return netShare

        val online = OnlineStorageRepository.getInstance(app).getById(shareId)
        if (online != null) {
            return NetworkShare(
                id = online.id,
                name = online.displayName,
                type = when (online.provider) {
                    OnlineStorageProvider.ONEDRIVE     -> ShareType.ONEDRIVE
                    OnlineStorageProvider.GOOGLE_DRIVE -> ShareType.GOOGLE_DRIVE
                    OnlineStorageProvider.DROPBOX      -> ShareType.DROPBOX
                    OnlineStorageProvider.AWS_S3       -> ShareType.AWS_S3
                    OnlineStorageProvider.IDRIVE_E2    -> ShareType.IDRIVE_E2
                    OnlineStorageProvider.WEBDAV       -> ShareType.WEBDAV
                },
                host = online.email,
                port = 0,
                remotePath = when {
                    online.isWebDavProvider -> online.webDavUrl ?: ""
                    else                   -> online.s3Endpoint ?: ""
                }
            )
        }
        return null
    }
}
