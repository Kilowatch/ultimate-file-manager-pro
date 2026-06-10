package za.kilowatch.ultimatefilemanager.network

import java.io.InputStream
import java.io.OutputStream

/**
 * FOSS build stub for DropboxShareClient.
 *
 * Dropbox is not available in the FOSS build — it requires proprietary
 * Dropbox app credentials. This stub keeps shared-code branches compiling.
 * These branches are unreachable at runtime because the Dropbox option is absent
 * from the AddOnlineStorageActivity FOSS override.
 */
object DropboxShareClient {

    private fun unsupported(): Nothing =
        throw UnsupportedOperationException("Dropbox is not available in the FOSS build.")

    suspend fun listFiles(share: NetworkShare, remotePath: String): List<NetworkFile> = unsupported()
    suspend fun mkdir(share: NetworkShare, remotePath: String) { unsupported() }
    suspend fun deleteFile(share: NetworkShare, remotePath: String) { unsupported() }
    suspend fun rename(share: NetworkShare, fromPath: String, toPath: String) { unsupported() }
    suspend fun openInputStream(share: NetworkShare, remotePath: String): Pair<InputStream, Long> = unsupported()
    suspend fun openInputStreamForStreaming(
        share: NetworkShare, remotePath: String, rangeHeader: String?
    ): okhttp3.Response = unsupported()
    fun openInputStreamForStreamingSync(
        share: NetworkShare, remotePath: String, rangeHeader: String?
    ): okhttp3.Response = unsupported()
    fun openRandomAccessFile(share: NetworkShare, remotePath: String): IRandomAccessFile = unsupported()
    fun getStreamingUrlAndTokenSync(share: NetworkShare, remotePath: String): Pair<String, String> = unsupported()
    fun getFileSizeSync(share: NetworkShare, remotePath: String): Long = unsupported()
    suspend fun uploadStream(
        share: NetworkShare, remotePath: String, inputStream: InputStream,
        totalSize: Long, onProgress: (Long) -> Unit
    ) { unsupported() }
    suspend fun openOutputStream(share: NetworkShare, remotePath: String): OutputStream = unsupported()
}
