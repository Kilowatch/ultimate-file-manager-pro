package za.kilowatch.ultimatefilemanager.network

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
import java.util.concurrent.TimeUnit

/**
 * Dropbox REST API v2 client.
 *
 * Token refresh happens transparently: the stored refreshToken is exchanged for a short-lived
 * accessToken (POST https://api.dropboxapi.com/oauth2/token).
 *
 * Dropbox uses absolute paths (e.g., "/Documents/file.txt") instead of IDs. Root is "".
 */
object DropboxShareClient {
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .build()
    }
    private val gson = Gson()

    private data class TokenCache(val token: String, val expiryMs: Long)
    private val accessTokenCache = java.util.concurrent.ConcurrentHashMap<String, TokenCache>()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

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
        val bodyBuilder = okhttp3.FormBody.Builder()
            .add("client_id",     BuildConfig.DROPBOX_APP_KEY)
            .add("client_secret", BuildConfig.DROPBOX_APP_SECRET)
            .add("grant_type",    "refresh_token")
            .add("refresh_token", refreshToken)

        val request = Request.Builder()
            .url("https://api.dropboxapi.com/oauth2/token")
            .post(bodyBuilder.build())
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: ""
            val json = gson.fromJson(body, JsonObject::class.java)
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
                val error = json.get("error")?.asString ?: response.message
                if (error == "invalid_grant") {
                    throw IOException("Dropbox token invalid. Please delete and link the account again.")
                }
                throw IOException("Dropbox token refresh failed: $error")
            }
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

            val request = Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer $token")
                .post(gson.toJson(jsonReq).toRequestBody(jsonMediaType))
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    GoRoLog.e("DropboxShareClient", "listFiles failed: $body")
                    throw IOException("Dropbox listFiles failed (${response.code})")
                }
                val body = response.body?.string() ?: ""
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
        }
        files
    }

    suspend fun mkdir(share: NetworkShare, remotePath: String) = withContext(Dispatchers.IO) {
        val token = getAccessToken(share.host)
        val path = normalizePath(remotePath)
        
        val jsonReq = JsonObject()
        jsonReq.addProperty("path", path)
        jsonReq.addProperty("autorename", false)

        val request = Request.Builder()
            .url("https://api.dropboxapi.com/2/files/create_folder_v2")
            .header("Authorization", "Bearer $token")
            .post(gson.toJson(jsonReq).toRequestBody(jsonMediaType))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val error = response.body?.string() ?: ""
                throw IOException("Dropbox mkdir failed: $error")
            }
        }
    }

    suspend fun deleteFile(share: NetworkShare, remotePath: String) = withContext(Dispatchers.IO) {
        val token = getAccessToken(share.host)
        val path = normalizePath(remotePath)
        
        val jsonReq = JsonObject()
        jsonReq.addProperty("path", path)

        val request = Request.Builder()
            .url("https://api.dropboxapi.com/2/files/delete_v2")
            .header("Authorization", "Bearer $token")
            .post(gson.toJson(jsonReq).toRequestBody(jsonMediaType))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                // If already deleted, Dropbox might return path_lookup/not_found
                val error = response.body?.string() ?: ""
                if (!error.contains("path_lookup/not_found")) {
                    throw IOException("Dropbox delete failed: $error")
                }
            }
        }
    }

    suspend fun rename(share: NetworkShare, fromPath: String, toPath: String) = withContext(Dispatchers.IO) {
        val token = getAccessToken(share.host)
        
        val jsonReq = JsonObject()
        jsonReq.addProperty("from_path", normalizePath(fromPath))
        jsonReq.addProperty("to_path", normalizePath(toPath))
        jsonReq.addProperty("autorename", false)

        val request = Request.Builder()
            .url("https://api.dropboxapi.com/2/files/move_v2")
            .header("Authorization", "Bearer $token")
            .post(gson.toJson(jsonReq).toRequestBody(jsonMediaType))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val error = response.body?.string() ?: ""
                throw IOException("Dropbox rename failed: $error")
            }
        }
    }

    suspend fun openInputStream(share: NetworkShare, remotePath: String): Pair<InputStream, Long> = withContext(Dispatchers.IO) {
        val token = getAccessToken(share.host)
        val path = normalizePath(remotePath)

        val arg = JsonObject()
        arg.addProperty("path", path)

        val request = Request.Builder()
            .url("https://content.dropboxapi.com/2/files/download")
            .header("Authorization", "Bearer $token")
            .header("Dropbox-API-Arg", gson.toJson(arg))
            .post(okhttp3.RequestBody.create(null, ByteArray(0)))
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            response.close()
            throw IOException("Download failed: ${response.code}")
        }
        val length = response.header("Content-Length")?.toLongOrNull() ?: -1L
        Pair(response.body!!.byteStream(), length)
    }

    suspend fun openInputStreamForStreaming(share: NetworkShare, remotePath: String, rangeHeader: String?): okhttp3.Response = withContext(Dispatchers.IO) {
        openInputStreamForStreamingSync(share, remotePath, rangeHeader)
    }

    fun openInputStreamForStreamingSync(share: NetworkShare, remotePath: String, rangeHeader: String?): okhttp3.Response {
        val token = getAccessTokenSync(share.host)
        val path = normalizePath(remotePath)
        
        val arg = JsonObject()
        arg.addProperty("path", path)

        val requestBuilder = Request.Builder()
            .url("https://content.dropboxapi.com/2/files/download")
            .header("Authorization", "Bearer $token")
            .header("Dropbox-API-Arg", gson.toJson(arg))
            .post(okhttp3.RequestBody.create(null, ByteArray(0)))

        if (rangeHeader != null) {
            requestBuilder.header("Range", rangeHeader)
        }

        return client.newCall(requestBuilder.build()).execute()
    }

    fun openRandomAccessFile(share: NetworkShare, remotePath: String): IRandomAccessFile {
        val fileSize = getFileSizeSync(share, remotePath)
        return object : IRandomAccessFile {
            override val size: Long = fileSize
            override fun read(offset: Long, buffer: ByteArray, length: Int): Int {
                val rangeHeader = "bytes=$offset-${offset + length - 1}"
                val response = openInputStreamForStreamingSync(share, remotePath, rangeHeader)
                if (!response.isSuccessful) {
                    response.close()
                    throw IOException("RAF read failed: ${response.code}")
                }
                return response.body!!.byteStream().use { input ->
                    var totalRead = 0
                    while (totalRead < length) {
                        val read = input.read(buffer, totalRead, length - totalRead)
                        if (read == -1) break
                        totalRead += read
                    }
                    if (totalRead == 0) -1 else totalRead
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
        
        val request = Request.Builder()
            .url("https://api.dropboxapi.com/2/files/get_metadata")
            .header("Authorization", "Bearer $token")
            .post(gson.toJson(jsonReq).toRequestBody(jsonMediaType))
            .build()
            
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return 0L
            val body = response.body?.string() ?: ""
            val json = gson.fromJson(body, JsonObject::class.java)
            val sizeStr = if (json.has("size")) json.get("size").asString else "0"
            return sizeStr.toLongOrNull() ?: 0L
        }
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

            // Convert InputStream to byte array - caution, only for small files!
            val bytes = inputStream.readBytes()
            val requestBody = okhttp3.RequestBody.create("application/octet-stream".toMediaType(), bytes)

            val request = Request.Builder()
                .url("https://content.dropboxapi.com/2/files/upload")
                .header("Authorization", "Bearer $token")
                .header("Dropbox-API-Arg", gson.toJson(arg))
                .header("Content-Type", "application/octet-stream")
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Upload failed: ${response.code} ${response.body?.string()}")
                }
                onProgress(totalSize)
            }
        } else {
            // Chunked upload session
            var sessionId = ""
            var uploadedSoFar = 0L
            val buffer = ByteArray(CHUNK_SIZE.toInt())

            inputStream.use { input ->
                // Step 1: Start session
                var read = input.read(buffer)
                if (read <= 0) return@use

                val startBody = okhttp3.RequestBody.create("application/octet-stream".toMediaType(), buffer, 0, read)
                val startReq = Request.Builder()
                    .url("https://content.dropboxapi.com/2/files/upload_session/start")
                    .header("Authorization", "Bearer $token")
                    .header("Dropbox-API-Arg", "{\"close\": false}")
                    .header("Content-Type", "application/octet-stream")
                    .post(startBody)
                    .build()

                client.newCall(startReq).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("Start upload failed: ${response.code}")
                    val json = gson.fromJson(response.body?.string(), JsonObject::class.java)
                    sessionId = json.get("session_id").asString
                }

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

                    // Check if this is the very last chunk by peaking the stream?
                    // Actually, we can just append, and close true at the end.
                    val arg = JsonObject()
                    val cursor = JsonObject()
                    cursor.addProperty("session_id", sessionId)
                    cursor.addProperty("offset", uploadedSoFar)
                    arg.add("cursor", cursor)
                    arg.addProperty("close", false)

                    val appendBody = okhttp3.RequestBody.create("application/octet-stream".toMediaType(), buffer, 0, read)
                    val appendReq = Request.Builder()
                        .url("https://content.dropboxapi.com/2/files/upload_session/append_v2")
                        .header("Authorization", "Bearer $token")
                        .header("Dropbox-API-Arg", gson.toJson(arg))
                        .header("Content-Type", "application/octet-stream")
                        .post(appendBody)
                        .build()

                    client.newCall(appendReq).execute().use { response ->
                        if (!response.isSuccessful) throw IOException("Append failed: ${response.code}")
                    }
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

                val emptyBody = okhttp3.RequestBody.create("application/octet-stream".toMediaType(), ByteArray(0))
                val finishReq = Request.Builder()
                    .url("https://content.dropboxapi.com/2/files/upload_session/finish")
                    .header("Authorization", "Bearer $token")
                    .header("Dropbox-API-Arg", gson.toJson(finishArg))
                    .header("Content-Type", "application/octet-stream")
                    .post(emptyBody)
                    .build()

                client.newCall(finishReq).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("Finish upload failed: ${response.code} ${response.body?.string()}")
                    }
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
