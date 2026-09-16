package za.kilowatch.ultimatefilemanager.network

/**
 * Resolves SAF paths that UFM opened through its own DocumentsProvider on an
 * RClone online-storage root (document id `os:<storageId>/<remote>`) back to the
 * backing [OnlineStorage] and the remote path inside it.
 *
 * Shared by the internal player (UFMPlaybackService), the external-open path
 * (FileViewerRouter), and the SAF provider gate so the `os:`-document parsing
 * lives in exactly one place.
 */
object OnlineSafDoc {

    /** Returns the backing RClone [OnlineStorage] and remote path, or null when [path]
     *  is not a SAF path rooted at an RClone online storage. */
    fun resolveRClone(context: android.content.Context, path: String): Pair<OnlineStorage, String>? {
        return try {
            if (!za.kilowatch.ultimatefilemanager.storage.SafTreeManager.isSaf(context, path)) return null
            val uri = za.kilowatch.ultimatefilemanager.storage.SafTreeManager.getDocumentUriForPath(context, path) ?: return null
            val docId = try {
                android.provider.DocumentsContract.getDocumentId(uri)
            } catch (_: Exception) {
                return null
            } ?: return null
            if (!docId.startsWith("os:")) return null
            val rest = docId.removePrefix("os:")
            val storageId = rest.substringBefore('/')
            if (storageId.isEmpty()) return null
            val online = OnlineStorageRepository.getInstance(context).getById(storageId) ?: return null
            if (online.provider != OnlineStorageProvider.RCLONE) return null
            val remote = rest.removePrefix(storageId).trimStart('/')
            Pair(online, remote)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Resolves SAF paths or URIs that UFM opened through its own DocumentsProvider on any
     * network share or online storage root (`net:<shareId>/<remote>` or `os:<storageId>/<remote>`)
     * back to the backing [NetworkShare] and remote path.
     */
    fun resolveSafShare(context: android.content.Context, path: String): Pair<NetworkShare, String>? {
        return try {
            val docId = if (path.startsWith("net:") || path.startsWith("os:") || path.startsWith("net://") || path.startsWith("os://")) {
                path
            } else {
                val uri = when {
                    path.startsWith("content://") -> android.net.Uri.parse(path)
                    za.kilowatch.ultimatefilemanager.storage.SafTreeManager.isSaf(context, path) ->
                        za.kilowatch.ultimatefilemanager.storage.SafTreeManager.getDocumentUriForPath(context, path)
                    else -> null
                } ?: return null

                try {
                    android.provider.DocumentsContract.getDocumentId(uri)
                } catch (_: Exception) {
                    return null
                } ?: return null
            }

            val (isOnline, withoutScheme) = when {
                docId.startsWith("os://") -> true to docId.removePrefix("os://")
                docId.startsWith("os:") -> true to docId.removePrefix("os:")
                docId.startsWith("net://") -> false to docId.removePrefix("net://")
                docId.startsWith("net:") -> false to docId.removePrefix("net:")
                else -> return null
            }
            val slashIdx = withoutScheme.indexOf('/')
            val shareId = if (slashIdx < 0) withoutScheme else withoutScheme.substring(0, slashIdx)
            if (shareId.isEmpty()) return null
            val remote = if (slashIdx < 0) "" else withoutScheme.substring(slashIdx + 1).trimStart('/')

            val share: NetworkShare? = if (isOnline) {
                val online = OnlineStorageRepository.getInstance(context).getById(shareId) ?: return null
                online.toNetworkShare()
            } else {
                val netShare = NetworkShareRepository.getInstance(context).getById(shareId)
                if (netShare != null) {
                    netShare
                } else {
                    val online = OnlineStorageRepository.getInstance(context).getById(shareId) ?: return null
                    online.toNetworkShare()
                }
            }
            if (share != null) Pair(share, remote) else null
        } catch (_: Exception) {
            null
        }
    }
}

