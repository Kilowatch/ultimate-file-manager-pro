package za.kilowatch.ultimatefilemanager.network

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import za.kilowatch.ultimatefilemanager.server.DlnaSecurityFilter
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * ShareClient implementation for browsing and streaming from DLNA / UPnP
 * media servers.
 *
 * All network calls are blocking (synchronous OkHttp).  Callers should
 * invoke methods off the main thread.
 *
 * Connections use a shared [OkHttpClient] with 10-second connect and 30-second
 * read timeouts.  The underlying SOAP interactions are delegated to
 * [DlnaSoapClient]; raw media-streaming HTTP requests are made directly.
 *
 * Write operations ([openOutputStream], [mkdir], [deleteFile], [deleteDir],
 * [rename]) throw [UnsupportedOperationException] because DLNA is a read-only
 * protocol.
 */
object DlnaShareClient {

    private const val TAG = "DlnaShareClient"

    // -----------------------------------------------------------------
    // HTTP Client
    // -----------------------------------------------------------------

    private val httpClient: OkHttpClient by lazy {
        BypassCleartextOkHttpClient.applyBypass(
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
        ).build()
    }

    // -----------------------------------------------------------------
    // Read operations
    // -----------------------------------------------------------------

    /**
     * Lists the children of a DLNA container.
     *
     * @param share The [NetworkShare] describing the DLNA server (host, port).
     * @param path  The DLNA object ID of the container to browse (e.g. "0"
     *              for root, "videos" for the videos container).
     * @return A list of [NetworkFile] entries whose [NetworkFile.path] field
     *         contains the DLNA object ID of each child.
     */
    fun listFiles(share: NetworkShare, path: String): List<NetworkFile> {
        val objectId = path.ifBlank { "0" }  // DLNA root container is "0", never blank
        val serviceUrl = buildContentDirectoryUrl(share)
        Log.d(TAG, "listFiles: share=${share.name} host=${share.host}:${share.effectivePort} objectId=$objectId url=$serviceUrl")
        val result = DlnaSoapClient.browse(serviceUrl, objectId, "BrowseDirectChildren", 0, 200)
        Log.d(TAG, "listFiles: got ${result.size} items (containers=${result.count { it.isDirectory }}, files=${result.count { !it.isDirectory }})")
        return result
    }

    /**
     * Opens an [InputStream] for the media resource identified by [path].
     *
     * The [path] parameter is the DLNA object ID.  The method first fetches
     * item metadata via a BrowseMetadata SOAP call to populate the media-URL
     * cache; if the URL is still unavailable it falls back to constructing
     * the URL from the share's host and port.
     *
     * @param share      The [NetworkShare] describing the DLNA server.
     * @param path       The DLNA object ID of the item to stream.
     * @param startOffset  Optional byte offset for ranged requests (HTTP Range header).
     * @return An [InputStream] from which the raw media data can be read.
     * @throws IOException If the network request fails or the URL is blocked
     *                     by the security filter.
     */
    fun openInputStream(
        share: NetworkShare,
        path: String,
        startOffset: Long? = null
    ): InputStream {
        val serviceUrl = buildContentDirectoryUrl(share)

        // Browse metadata to populate the media-URL cache
        DlnaSoapClient.browse(serviceUrl, path, "BrowseMetadata", 0, 1)

        // Retrieve media URL from cache, or construct it as a fallback
        val mediaUrl = DlnaSoapClient.getUrl(path)
            ?: "http://${share.host}:${share.effectivePort}/media/$path"

        // Validate the URL through the security filter
        if (!DlnaSecurityFilter.validateUrl(mediaUrl)) {
            throw IOException("DLNA media URL blocked by security filter: $mediaUrl")
        }

        // Build the HTTP request
        val requestBuilder = Request.Builder()
            .url(mediaUrl)
            .get()

        // Add Range header if a start offset was provided
        if (startOffset != null) {
            requestBuilder.header("Range", "bytes=$startOffset-")
        }

        val request = requestBuilder.build()

        return try {
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                val message = "HTTP ${response.code}: ${response.message}"
                response.close()
                throw IOException("Failed to stream DLNA media at $mediaUrl: $message")
            }
            val body = response.body
                ?: run {
                    response.close()
                    throw IOException("Null response body from $mediaUrl")
                }
            body.byteStream()
        } catch (e: IOException) {
            throw IOException("DLNA stream error for $mediaUrl", e)
        }
    }

    /**
     * Opens a seekable random-access handle for the media resource identified
     * by [path].
     *
     * Fetches the item metadata to determine the total file size and locates
     * the streaming URL.
     *
     * @param share The [NetworkShare] describing the DLNA server.
     * @param path  The DLNA object ID of the item.
     * @return An [IRandomAccessFile] supporting positional reads.
     */
    fun openRandomAccessFile(
        share: NetworkShare,
        path: String
    ): IRandomAccessFile {
        val serviceUrl = buildContentDirectoryUrl(share)

        // Browse metadata to populate the media-URL cache and get the file size
        val metadata = DlnaSoapClient.browse(serviceUrl, path, "BrowseMetadata", 0, 1)
        val fileSize = metadata.firstOrNull()?.size ?: 0L

        // Retrieve media URL from cache
        val mediaUrl = DlnaSoapClient.getUrl(path)
            ?: "http://${share.host}:${share.effectivePort}/media/$path"

        return DlnaRandomAccessFile(mediaUrl, fileSize)
    }

    /**
     * Returns the size of the media resource identified by [path], or null
     * if the size could not be determined.
     *
     * @param share The [NetworkShare] describing the DLNA server.
     * @param path  The DLNA object ID of the item.
     */
    fun getFileSize(share: NetworkShare, path: String): Long? {
        val serviceUrl = buildContentDirectoryUrl(share)
        val metadata = DlnaSoapClient.browse(serviceUrl, path, "BrowseMetadata", 0, 1)
        val size = metadata.firstOrNull()?.size ?: 0L
        return if (size > 0L) size else null
    }

    // -----------------------------------------------------------------
    // Connection test
    // -----------------------------------------------------------------

    /**
     * Tests whether the DLNA server at [share] is reachable and responds
     * correctly.
     *
     * Sends a GetProtocolInfo SOAP request to the ConnectionManager service.
     *
     * @return `null` on success, or an error message string on failure.
     */
    fun testConnection(share: NetworkShare): String? {
        val serviceUrl = buildConnectionManagerUrl(share)
        return try {
            val success = DlnaSoapClient.getProtocolInfo(serviceUrl)
            if (success) {
                null
            } else {
                "Connection failed"
            }
        } catch (e: IOException) {
            Log.w(TAG, "testConnection failed for ${share.host}:${share.effectivePort}", e)
            e.message ?: "Connection failed"
        }
    }

    // -----------------------------------------------------------------
    // Write operations (unsupported — DLNA is read-only)
    // -----------------------------------------------------------------

    /**
     * @throws UnsupportedOperationException DLNA is read-only.
     */
    fun openOutputStream(share: NetworkShare, path: String): OutputStream {
        throw UnsupportedOperationException("DLNA is read-only")
    }

    /**
     * @throws UnsupportedOperationException DLNA is read-only.
     */
    fun mkdir(share: NetworkShare, path: String) {
        throw UnsupportedOperationException("DLNA is read-only")
    }

    /**
     * @throws UnsupportedOperationException DLNA is read-only.
     */
    fun deleteFile(share: NetworkShare, path: String) {
        throw UnsupportedOperationException("DLNA is read-only")
    }

    /**
     * @throws UnsupportedOperationException DLNA is read-only.
     */
    fun deleteDir(share: NetworkShare, path: String) {
        throw UnsupportedOperationException("DLNA is read-only")
    }

    /**
     * @throws UnsupportedOperationException DLNA is read-only.
     */
    fun rename(share: NetworkShare, fromPath: String, toPath: String) {
        throw UnsupportedOperationException("DLNA is read-only")
    }

    // -----------------------------------------------------------------
    // URL helpers
    // -----------------------------------------------------------------

    /**
     * Builds the ContentDirectory SOAP service URL for the given share.
     *
     * Example: `http://192.168.1.100:8200/cds/control`
     */
    private fun buildContentDirectoryUrl(share: NetworkShare): String {
        if (share.dlnaContentDirectoryUrl.isNotBlank()) {
            return share.dlnaContentDirectoryUrl
        }
        return "http://${share.host}:${share.effectivePort}/cds/control"
    }

    /**
     * Builds the ConnectionManager SOAP service URL for the given share.
     *
     * Example: `http://192.168.1.100:8200/cms/control`
     */
    private fun buildConnectionManagerUrl(share: NetworkShare): String {
        if (share.dlnaConnectionManagerUrl.isNotBlank()) {
            return share.dlnaConnectionManagerUrl
        }
        return "http://${share.host}:${share.effectivePort}/cms/control"
    }
}
