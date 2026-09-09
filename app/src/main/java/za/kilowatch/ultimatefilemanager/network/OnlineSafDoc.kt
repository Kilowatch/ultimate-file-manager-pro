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
}
