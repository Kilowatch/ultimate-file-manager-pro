package za.kilowatch.ultimatefilemanager.smartsort

import za.kilowatch.ultimatefilemanager.network.NetworkShare
import za.kilowatch.ultimatefilemanager.network.NetworkShareRepository
import za.kilowatch.ultimatefilemanager.network.OnlineStorageRepository
import za.kilowatch.ultimatefilemanager.network.OnlineStorageProvider
import za.kilowatch.ultimatefilemanager.network.toNetworkShare
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
            return online.toNetworkShare()
        }
        return null
    }
}
