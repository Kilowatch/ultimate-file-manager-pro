package za.kilowatch.ultimatefilemanager.network

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.BuildConfig
import za.kilowatch.ultimatefilemanager.UfmApplication
import za.kilowatch.ultimatefilemanager.util.GoRoLog
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Dropbox REST API v2 client.
 *
 * Token refresh happens transparently: the stored refreshToken is exchanged for a short-lived
 * accessToken (POST https://api.dropboxapi.com/oauth2/token).
 *
 * Dropbox uses absolute paths (e.g., "/Documents/file.txt") instead of IDs. Root is "".
 */
object DropboxShareClient {
    private val gson = Gson()

    private data class TokenCache(val token: String, val expiryMs: Long)
    private val accessTokenCache = java.util.concurrent.ConcurrentHashMap<String, TokenCache>()

    // -------------------------------------------------------------------------
    // Token helpers
    // -------------------------------------------------------------------------

    private suspend fun getAccessToken(email: String): String {
        val cached = accessTokenCache[email]
        if (cached != null && System.currentTimeMillis() < cached.expiryMs - 60_000) {
            return cached.token
        }

        val repo = OnlineStorageRepository.getInstance(UfmApplication.instance)
        val storage = repo.getAll().find {
            it.email.equals(email, ignoreCase = true) && it.provider == OnlineStorageProvider.DROPBOX
        } ?: throw Exception("Dropbox account not found for $email")

        val refreshToken = storage.refreshToken
            ?: throw Exception("No refresh token stored for Dropbox account $email")

        return refreshAccessToken(email, refreshToken, storage)
    }

    fun getAccessTokenSync(email: String): String {
        val cached = accessTokenCache[email]
        if (cached != null && System.currentTimeMillis() < cached.expiryMs - 60_000) {
            return cached.token
        }

        val repo = OnlineStorageRepository.getInstance(UfmApplication.instance)
        val storage = repo.getAll().find {
            it.email.equals(email, ignoreCase = true) && it.provider == OnlineStorageProvider.DROPBOX
        } ?: throw Exception("Dropbox account not found for $email")

        val refreshToken = storage.refreshToken
            ?: throw Exception("No refresh token stored for Dropbox account $email")

        return runBlocking { refreshAccessToken(email, refreshToken, storage) }
    }

    private suspend fun refreshAccessToken(
        email: String,
        refreshToken: String,
        storage: OnlineStorage
    ): String = withContext(Dispatchers.IO) {
        val formFields = mapOf(
            "client_id" to BuildConfig.DROPBOX_APP_KEY,
            "client_secret" to BuildConfig.DROPBOX_APP_SECRET,
            "grant_type" to "refresh_token",
            "refresh_token" to refreshToken
        )

        val response = UfmHttpClient.postFormSync(
            "https://api.dropboxapi.com/oauth2/token",
            headers = emptyMap(),
            formFields = formFields
        )
        val body = response.bodyString
        val json = try { gson.fromJson(body, JsonObject::class.java) } catch (e: Exception) { JsonObject() }
        if (response.isSuccessful) {
            val newAccessToken = json.get("access_token")?.asString
                ?: throw IOException("No access_token in Dropbox refresh response")
            val expiresInSecs = json.get("expires_in")?.asLong ?: 14400L // Dropbox default is 4 hours
            accessTokenCache[email] = TokenCache(newAccessToken, System.currentTimeMillis() + (expiresInSecs * 1000L))

            // Dropbox doesn't usually rotate short-lived refresh tokens, but check just in case
            val newRefreshToken = json.get("refresh_token")?.asString
            if (newRefreshToken != null && newRefreshToken != refreshToken) {
                val repo = OnlineStorageRepository.getInstance(UfmApplication.instance)
                repo.save(storage.copy(refreshToken = newRefreshToken))
            }
            return@withContext newAccessToken
        } else {
            val error = json?.get("error")?.asString ?: "HTTP ${response.statusCode}"
            if (error == "invalid_grant") {
                throw IOException("Dropbox token invalid. Please delete and link the account again.")
            }
            throw IOException("Dropbox token refresh failed: $error")
        }
    }

    // -------------------------------------------------------------------------
    // Path Formatting
    // -------------------------------------------------------------------------

    private fun normalizePath(path: String?): String {
        if (path == null || path.isEmpty() || path == "/") return ""
        if (!path.startsWith("/")) return "/$path"
        return path
    }

    // -------------------------------------------------------------------------
    // UFM API Implementation
    // -------------------------------------------------------------------------

    suspend fun listFiles(share: NetworkShare, remotePath: String): List<NetworkFile> = withContext(Dispatchers.IO) {
        val token = getAccessToken(share.host)
        val path = normalizePath(remotePath)
        val files = mutableListOf<NetworkFile>()

        var hasMore = true
        var cursor: String? = null

        while (hasMore) {
            val endpoint = if (cursor == null) "https://api.dropboxapi.com/2/files/list_folder" else "https://api.dropboxapi.com/2/files/list_folder/continue"
            val jsonReq = JsonObject()
            if (cursor == null) {
                jsonReq.addProperty("path", path)
                jsonReq.addProperty("recursive", false)
                jsonReq.addProperty("include_media_info", false)
                jsonReq.addProperty("include_deleted", false)
            } else {
                jsonReq.addProperty("cursor", cursor)
            }

            val response = UfmHttpClient.postStringSync(
                endpoint,
                headers = mapOf(
                    "Authorization" to "Bearer $token",
                    "Content-Type" to "application/json; charset=utf-8"
                ),
                bodyString = gson.toJson(jsonReq)
            )

            if (!response.isSuccessful) {
                val body = response.bodyString
                GoRoLog.e("DropboxShareClient", "listFiles failed: $body")
                throw IOException("Dropbox listFiles failed (${response.statusCode})")
            }
            val body = response.bodyString
            val result = gson.fromJson(body, JsonObject::class.java)

            val entries = result.getAsJsonArray("entries")
            for (i in 0 until entries.size()) {
                val fileObj = entries.get(i).asJsonObject
                val tag = fileObj.get(".tag").asString
                val isDir = tag == "folder"
                val name = fileObj.get("name").asString
                val sizeStr = if (fileObj.has("size")) fileObj.get("size").asString else "0"
                val size = sizeStr.toLongOrNull() ?: 0L

                // Dropbox timestamp format: 2015-05-12T15:50:38Z
                var modifiedTime = 0L
                if (fileObj.has("server_modified")) {
                    try {
                        val df = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                        df.timeZone = TimeZone.getTimeZone("UTC")
                        modifiedTime = df.parse(fileObj.get("server_modified").asString)?.time ?: 0L
                    } catch (e: Exception) {}
                }

                files.add(
                    NetworkFile(
                        name = name,
                        path = fileObj.get("path_display")?.asString ?: "$path/$name",
                        isDirectory = isDir,
                        size = size,
                        lastModified = modifiedTime
                    )
                )
            }

            hasMore = result.get("has_more")?.asBoolean ?: false
            if (hasMore) {
                cursor = result.get("cursor")?.asString
            }
        }
        files
    }

    suspend fun mkdir(share: NetworkShare, remotePath: String) = withContext(Dispatchers.IO) {
        val token = getAccessToken(share.host)
        val path = normalizePath(remotePath)

        val jsonReq = JsonObject()
        jsonReq.addProperty("path", path)
        jsonReq.addProperty("autorename", false)

        val response = UfmHttpClient.postStringSync(
            "https://api.dropboxapi.com/2/files/create_folder_v2",
            headers = mapOf(
                "Authorization" to "Bearer $token",
                "Content-Type" to "application/json; charset=utf-8"
            ),
            bodyString = gson.toJson(jsonReq)
        )

        if (!response.isSuccessful) {
            val error = response.bodyString
            throw IOException("Dropbox mkdir failed: $error")
        }
    }

    suspend fun deleteFile(share: NetworkShare, remotePath: String) = withContext(Dispatchers.IO) {
        val token = getAccessToken(share.host)
        val path = normalizePath(remotePath)

        val jsonReq = JsonObject()
        jsonReq.addProperty("path", path)

        val response = UfmHttpClient.postStringSync(
            "https://api.dropboxapi.com/2/files/delete_v2",
            headers = mapOf(
                "Authorization" to "Bearer $token",
                "Content-Type" to "application/json; charset=utf-8"
            ),
            bodyString = gson.toJson(jsonReq)
        )

        if (!response.isSuccessful) {
            // If already deleted, Dropbox might return path_lookup/not_found
            val error = response.bodyString
            if (!error.contains("path_lookup/not_found")) {
                throw IOException("Dropbox delete failed: $error")
            }
        }
    }

    suspend fun rename(share: NetworkShare, fromPath: String, toPath: String) = withContext(Dispatchers.IO) {
        val token = getAccessToken(share.host)

        val jsonReq = JsonObject()
        jsonReq.addProperty("from_path", normalizePath(fromPath))
        jsonReq.addProperty("to_path", normalizePath(toPath))
        jsonReq.addProperty("autorename", false)

        val response = UfmHttpClient.postStringSync(
            "https://api.dropboxapi.com/2/files/move_v2",
            headers = mapOf(
                "Authorization" to "Bearer $token",
                "Content-Type" to "application/json; charset=utf-8"
            ),
            bodyString = gson.toJson(jsonReq)
        )

        if (!response.isSuccessful) {
            val error = response.bodyString
            throw IOException("Dropbox rename failed: $error")
        }
    }

    suspend fun openInputStream(share: NetworkShare, remotePath: String): Pair<InputStream, Long> = withContext(Dispatchers.IO) {
        val token = getAccessToken(share.host)
        val path = normalizePath(remotePath)

        val arg = JsonObject()
        arg.addProperty("path", path)

        val response = UfmHttpClient.openStream(
            "https://content.dropboxapi.com/2/files/download",
            headers = mapOf(
                "Authorization" to "Bearer $token",
                "Dropbox-API-Arg" to gson.toJson(arg)
            ),
            method = "POST",
            body = ByteArray(0)
        )

        if (!response.isSuccessful) {
            response.close()
            throw IOException("Download failed: ${response.statusCode}")
        }
        val length = response.contentLength
        Pair(response.inputStream, length)
    }

    suspend fun openInputStreamForStreaming(share: NetworkShare, remotePath: String, rangeHeader: String?): UfmHttpClient.StreamResponse = withContext(Dispatchers.IO) {
        openInputStreamForStreamingSync(share, remotePath, rangeHeader)
    }

    fun openInputStreamForStreamingSync(share: NetworkShare, remotePath: String, rangeHeader: String?): UfmHttpClient.StreamResponse {
        val token = getAccessTokenSync(share.host)
        val path = normalizePath(remotePath)

        val arg = JsonObject()
        arg.addProperty("path", path)

        val headers = mutableMapOf(
            "Authorization" to "Bearer $token",
            "Dropbox-API-Arg" to gson.toJson(arg)
        )

        if (rangeHeader != null) {
            headers["Range"] = rangeHeader
        }

        val response = UfmHttpClient.openStream(
            "https://content.dropboxapi.com/2/files/download",
            headers = headers,
            method = "POST",
            body = ByteArray(0)
        )
        if (!response.isSuccessful) {
            response.close()
            throw IOException("Download stream failed: ${response.statusCode}")
        }
        return response
    }

    fun openRandomAccessFile(share: NetworkShare, remotePath: String): IRandomAccessFile {
        val fileSize = getFileSizeSync(share, remotePath)
        return object : IRandomAccessFile {
            override val size: Long = fileSize
            override fun read(offset: Long, buffer: ByteArray, length: Int): Int {
                val rangeHeader = "bytes=$offset-${offset + length - 1}"
                val response = openInputStreamForStreamingSync(share, remotePath, rangeHeader)
                return response.use { resp ->
                    if (!resp.isSuccessful) {
                        throw IOException("RAF read failed: ${resp.statusCode}")
                    }
                    resp.inputStream.use { input ->
                        var totalRead = 0
                        while (totalRead < length) {
                            val read = input.read(buffer, totalRead, length - totalRead)
                            if (read == -1) break
                            totalRead += read
                        }
                        if (totalRead == 0) -1 else totalRead
                    }
                }
            }
            override fun write(offset: Long, buffer: ByteArray, length: Int): Int {
                throw IOException("Random writes not supported on Dropbox")
            }
            override fun close() {}
        }
    }

    fun getStreamingUrlAndTokenSync(share: NetworkShare, remotePath: String): Pair<String, String> {
        val token = getAccessTokenSync(share.host)
        val path = normalizePath(remotePath)
        val arg = JsonObject()
        arg.addProperty("path", path)
        val url = "https://content.dropboxapi.com/2/files/download?arg=" + URLEncoder.encode(gson.toJson(arg), "UTF-8")
        return Pair(url, token)
    }

    fun getFileSizeSync(share: NetworkShare, remotePath: String): Long {
        val token = getAccessTokenSync(share.host)
        val path = normalizePath(remotePath)

        val jsonReq = JsonObject()
        jsonReq.addProperty("path", path)

        val response = UfmHttpClient.postStringSync(
            "https://api.dropboxapi.com/2/files/get_metadata",
            headers = mapOf(
                "Authorization" to "Bearer $token",
                "Content-Type" to "application/json; charset=utf-8"
            ),
            bodyString = gson.toJson(jsonReq)
        )

        if (!response.isSuccessful) return 0L
        val body = response.bodyString
        val json = try { gson.fromJson(body, JsonObject::class.java) } catch (e: Exception) { null }
        val sizeStr = if (json?.has("size") == true) json.get("size").asString else "0"
        return sizeStr.toLongOrNull() ?: 0L
    }

    suspend fun uploadStream(
        share: NetworkShare,
        remotePath: String,
        inputStream: InputStream,
        totalSize: Long,
        onProgress: (Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        val token = getAccessToken(share.host)
        val path = normalizePath(remotePath)
        val CHUNK_SIZE = 8L * 1024 * 1024 // 8MB chunks

        if (totalSize <= CHUNK_SIZE) {
            // Single API call for small files (<= 150MB is allowed, using 8MB boundary)
            val arg = JsonObject()
            arg.addProperty("path", path)
            arg.addProperty("mode", "overwrite")
            arg.addProperty("autorename", true)

            val bytes = inputStream.readBytes()
            val response = UfmHttpClient.postSync(
                "https://content.dropboxapi.com/2/files/upload",
                headers = mapOf(
                    "Authorization" to "Bearer $token",
                    "Dropbox-API-Arg" to gson.toJson(arg),
                    "Content-Type" to "application/octet-stream"
                ),
                bodyBytes = bytes
            )

            if (!response.isSuccessful) {
                throw IOException("Upload failed: ${response.statusCode} ${response.bodyString}")
            }
            onProgress(totalSize)
        } else {
            // Chunked upload session
            var sessionId = ""
            var uploadedSoFar = 0L
            val buffer = ByteArray(CHUNK_SIZE.toInt())

            inputStream.use { input ->
                // Step 1: Start session
                var read = input.read(buffer)
                if (read <= 0) return@use

                val startResp = UfmHttpClient.postSync(
                    "https://content.dropboxapi.com/2/files/upload_session/start",
                    headers = mapOf(
                        "Authorization" to "Bearer $token",
                        "Dropbox-API-Arg" to "{\"close\": false}",
                        "Content-Type" to "application/octet-stream"
                    ),
                    bodyBytes = buffer.copyOfRange(0, read)
                )

                if (!startResp.isSuccessful) throw IOException("Start upload failed: ${startResp.statusCode}")
                val startJson = gson.fromJson(startResp.bodyString, JsonObject::class.java)
                sessionId = startJson.get("session_id").asString

                uploadedSoFar += read
                onProgress(uploadedSoFar)

                // Step 2: Append
                var isLastChunk = false
                while (!isLastChunk) {
                    read = input.read(buffer)
                    if (read <= 0) {
                        isLastChunk = true
                        break
                    }

                    val arg = JsonObject()
                    val cursor = JsonObject()
                    cursor.addProperty("session_id", sessionId)
                    cursor.addProperty("offset", uploadedSoFar)
                    arg.add("cursor", cursor)
                    arg.addProperty("close", false)

                    val appendResp = UfmHttpClient.postSync(
                        "https://content.dropboxapi.com/2/files/upload_session/append_v2",
                        headers = mapOf(
                            "Authorization" to "Bearer $token",
                            "Dropbox-API-Arg" to gson.toJson(arg),
                            "Content-Type" to "application/octet-stream"
                        ),
                        bodyBytes = buffer.copyOfRange(0, read)
                    )

                    if (!appendResp.isSuccessful) throw IOException("Append failed: ${appendResp.statusCode}")
                    uploadedSoFar += read
                    onProgress(uploadedSoFar)
                }

                // Step 3: Finish
                val cursor = JsonObject()
                cursor.addProperty("session_id", sessionId)
                cursor.addProperty("offset", uploadedSoFar)

                val commit = JsonObject()
                commit.addProperty("path", path)
                commit.addProperty("mode", "overwrite")

                val finishArg = JsonObject()
                finishArg.add("cursor", cursor)
                finishArg.add("commit", commit)

                val finishResp = UfmHttpClient.postSync(
                    "https://content.dropboxapi.com/2/files/upload_session/finish",
                    headers = mapOf(
                        "Authorization" to "Bearer $token",
                        "Dropbox-API-Arg" to gson.toJson(finishArg),
                        "Content-Type" to "application/octet-stream"
                    ),
                    bodyBytes = ByteArray(0)
                )

                if (!finishResp.isSuccessful) {
                    throw IOException("Finish upload failed: ${finishResp.statusCode} ${finishResp.bodyString}")
                }
            }
        }
    }

    suspend fun openOutputStream(share: NetworkShare, remotePath: String): OutputStream = withContext(Dispatchers.IO) {
        val tempFile = File(UfmApplication.instance.cacheDir, "dropbox_upload_${System.currentTimeMillis()}.tmp")
        object : java.io.OutputStream() {
            private val fileOut = FileOutputStream(tempFile)
            override fun write(b: Int) = fileOut.write(b)
            override fun write(b: ByteArray, off: Int, len: Int) = fileOut.write(b, off, len)
            override fun flush() = fileOut.flush()
            override fun close() {
                fileOut.close()
                runBlocking(Dispatchers.IO) {
                    try {
                        val size = tempFile.length()
                        FileInputStream(tempFile).use { fis ->
                            uploadStream(share, remotePath, fis, size) {}
                        }
                    } finally {
                        tempFile.delete()
                    }
                }
            }
        }
    }
}
