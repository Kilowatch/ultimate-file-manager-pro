package za.kilowatch.ultimatefilemanager.network

import java.io.InputStream
import java.io.OutputStream

/**
 * Amazon Appstore stub for OnedriveShareClient.
 *
 * OneDrive is hidden from the UI in Amazon builds. This stub matches the exact API
 * surface of the real client so all `ShareType.ONEDRIVE -> OnedriveShareClient.*` branches
 * in main source-set files continue to compile without modification.
 *
 * At runtime, these branches are unreachable because users can never add a OneDrive
 * account (the UI chip is hidden). Every method throws UnsupportedOperationException
 * as a safety net in case of unexpected invocation.
 */
object OnedriveShareClient {

    private fun unsupported(): Nothing =
        throw UnsupportedOperationException("OneDrive is not available on the Amazon Appstore build.")

    // ── Stub methods matching the real OnedriveShareClient API ────────────────

    fun getAccessTokenSync(email: String): String = unsupported()

    suspend fun listFiles(share: NetworkShare, remotePath: String): List<NetworkFile> = unsupported()

    suspend fun mkdir(share: NetworkShare, remotePath: String) { unsupported() }

    suspend fun deleteFile(share: NetworkShare, remotePath: String) { unsupported() }

    suspend fun rename(share: NetworkShare, fromPath: String, toPath: String) { unsupported() }

    suspend fun openInputStream(share: NetworkShare, remotePath: String): Pair<InputStream, Long> = unsupported()

    suspend fun openInputStreamForStreaming(
        share: NetworkShare,
        remotePath: String,
        rangeHeader: String?
    ): okhttp3.Response = unsupported()

    fun openInputStreamForStreamingSync(
        share: NetworkShare,
        remotePath: String,
        rangeHeader: String?
    ): okhttp3.Response = unsupported()

    fun openRandomAccessFile(share: NetworkShare, remotePath: String): IRandomAccessFile = unsupported()

    fun getStreamingUrlAndTokenSync(share: NetworkShare, remotePath: String): Pair<String, String> = unsupported()

    fun getFileSizeSync(share: NetworkShare, remotePath: String): Long = unsupported()

    suspend fun uploadStream(
        share: NetworkShare,
        remotePath: String,
        inputStream: InputStream,
        totalSize: Long,
        onProgress: ((Long, Long) -> Unit)? = null
    ) { unsupported() }

    suspend fun openOutputStream(share: NetworkShare, remotePath: String): OutputStream = unsupported()
}
