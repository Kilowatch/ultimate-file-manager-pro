package za.kilowatch.ultimatefilemanager.network

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import za.kilowatch.ultimatefilemanager.UfmApplication
import za.kilowatch.ultimatefilemanager.util.GoRoLog
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Google Drive REST API v3 client.
 *
 * Google Drive is ID-based, not path-based. Paths (e.g. "Photos/Vacation") are resolved
 * by walking the folder hierarchy from root. An in-memory path→ID cache reduces repeated
 * API calls for entries already visited in the same session.
 *
 * Token refresh happens transparently: the stored refreshToken is exchanged for a short-lived
 * accessToken (POST https://oauth2.googleapis.com/token). If a new refreshToken is returned
 * it is persisted to [OnlineStorageRepository].
 *
 * The [NetworkShare.host] field stores the user's email, which is the lookup key in the repo.
 */
object GoogleDriveShareClient {
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .build()
    }
    private val gson = Gson()

    /** In-memory cache: email → (path → fileId). Reset on repository changes. */
    private val pathIdCache = mutableMapOf<String, MutableMap<String, String>>()

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

        GoRoLog.d("GDriveAuth", "GoogleDriveShareClient: getAccessToken for $email")
        val repo = OnlineStorageRepository.getInstance(UfmApplication.instance)
        val storage = repo.getAll().find {
            it.email.equals(email, ignoreCase = true) && it.provider == OnlineStorageProvider.GOOGLE_DRIVE
        } ?: throw Exception("Google Drive account not found for $email")

        val refreshToken = storage.refreshToken
            ?: throw Exception("No refresh token stored for Google Drive account $email")

        return refreshAccessToken(email, refreshToken, storage)
    }

    fun getAccessTokenSync(email: String): String {
        val cached = accessTokenCache[email]
        if (cached != null && System.currentTimeMillis() < cached.expiryMs - 60_000) {
            return cached.token
        }

        val repo = OnlineStorageRepository.getInstance(UfmApplication.instance)
        val storage = repo.getAll().find {
            it.email.equals(email, ignoreCase = true) && it.provider == OnlineStorageProvider.GOOGLE_DRIVE
        } ?: throw Exception("Google Drive account not found for $email")

        val refreshToken = storage.refreshToken
            ?: throw Exception("No refresh token stored for Google Drive account $email")

        // refreshAccessToken is mostly synchronous anyway, we can run it in runBlocking if needed,
        // but let's just make it call the sync part directly.
        return runBlocking { refreshAccessToken(email, refreshToken, storage) }
    }

    private suspend fun refreshAccessToken(
        email: String,
        refreshToken: String,
        storage: OnlineStorage
    ): String = withContext(Dispatchers.IO) {
        val isTv = za.kilowatch.ultimatefilemanager.util.DeviceUtils.isTvDevice(UfmApplication.instance)
        val clientId = if (isTv)
            za.kilowatch.ultimatefilemanager.BuildConfig.GOOGLE_DRIVE_TV_CLIENT_ID
        else
            za.kilowatch.ultimatefilemanager.BuildConfig.GOOGLE_DRIVE_MOBILE_CLIENT_ID

        val bodyBuilder = okhttp3.FormBody.Builder()
            .add("client_id",     clientId)
            .add("grant_type",    "refresh_token")
            .add("refresh_token", refreshToken)
        
        if (isTv) {
            bodyBuilder.add("client_secret", za.kilowatch.ultimatefilemanager.BuildConfig.GOOGLE_DRIVE_TV_CLIENT_SECRET)
        }

        val formBody = bodyBuilder.build()

        val request = Request.Builder()
            .url("https://oauth2.googleapis.com/token")
            .post(formBody)
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: ""
            val json = gson.fromJson(body, JsonObject::class.java)
            if (response.isSuccessful) {
                val newAccessToken = json.get("access_token")?.asString
                    ?: throw IOException("No access_token in Google refresh response")
                val expiresInSecs = json.get("expires_in")?.asLong ?: 3600L
                accessTokenCache[email] = TokenCache(newAccessToken, System.currentTimeMillis() + (expiresInSecs * 1000L))

                val newRefreshToken = json.get("refresh_token")?.asString
                if (newRefreshToken != null && newRefreshToken != refreshToken) {
                    val repo = OnlineStorageRepository.getInstance(UfmApplication.instance)
                    repo.save(storage.copy(refreshToken = newRefreshToken))
                    GoRoLog.d("GDriveAuth", "GoogleDriveShareClient: Updated refresh token for $email")
                }
                return@withContext newAccessToken
            } else {
                val error = json.get("error")?.asString ?: response.message
                val desc  = json.get("error_description")?.asString ?: ""
                
                if (error == "invalid_grant") {
                    throw IOException("Google Drive token is invalid or expired. To fix: Please go to 'Online Storages', delete this Google Drive account, and add it again.")
                }
                
                throw IOException("Google Drive token refresh failed: $error ($desc)")
            }
        }
    }

    fun readClientId(): String =
        if (za.kilowatch.ultimatefilemanager.util.DeviceUtils.isTvDevice(UfmApplication.instance))
            za.kilowatch.ultimatefilemanager.BuildConfig.GOOGLE_DRIVE_TV_CLIENT_ID
        else
            za.kilowatch.ultimatefilemanager.BuildConfig.GOOGLE_DRIVE_MOBILE_CLIENT_ID

    // -------------------------------------------------------------------------
    // Path → ID resolution
    // -------------------------------------------------------------------------

    private suspend fun resolvePathToId(token: String, email: String, path: String): String {
        return withContext(Dispatchers.IO) { resolvePathToIdSync(token, email, path) }
    }

    private fun resolvePathToIdSync(token: String, email: String, path: String): String {
        val cleanPath = path.trim('/')
        if (cleanPath.isEmpty()) return "root"

        val cache = pathIdCache.getOrPut(email) { mutableMapOf() }
        if (cache.containsKey(cleanPath)) return cache[cleanPath]!!

        val parts = cleanPath.split('/')
        var currentId = "root"
        val builder = StringBuilder()

        for (part in parts) {
            if (builder.isNotEmpty()) builder.append('/')
            builder.append(part)
            val subPath = builder.toString()
            
            if (cache.containsKey(subPath)) {
                currentId = cache[subPath]!!
            } else {
                val foundId = findFileIdInFolderSync(token, currentId, part)
                    ?: throw IOException("Path not found: $part in $currentId")
                cache[subPath] = foundId
                currentId = foundId
            }
        }
        return currentId
    }

    private fun findFileIdInFolderSync(token: String, parentId: String, name: String): String? {
        val query = "'$parentId' in parents and name = '${name.replace("'", "\\'")}' and trashed = false"
        val url = "https://www.googleapis.com/drive/v3/files?q=${URLEncoder.encode(query, "UTF-8")}&fields=files(id)"
        
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: ""
            val json = gson.fromJson(body, JsonObject::class.java)
            val files = json.getAsJsonArray("files")
            if (files != null && files.size() > 0) {
                return files[0].asJsonObject.get("id").asString
            }
        }
        return null
    }


    /** Resolve the parent folder of a path, returning (parentId, leafName). */
    private suspend fun resolveParent(token: String, email: String, path: String): Pair<String, String> {
        val clean = path.trim('/')
        val lastSlash = clean.lastIndexOf('/')
        return if (lastSlash < 0) {
            Pair("root", clean)
        } else {
            val parentPath = clean.substring(0, lastSlash)
            val leaf = clean.substring(lastSlash + 1)
            Pair(resolvePathToId(token, email, parentPath), leaf)
        }
    }

    private fun invalidateCacheBelow(email: String, path: String) {
        pathIdCache[email]?.keys?.removeAll { it.startsWith(path.trim('/')) }
    }

    // -------------------------------------------------------------------------
    // Public API – mirrors OnedriveShareClient
    // -------------------------------------------------------------------------

    suspend fun listFiles(share: NetworkShare, remotePath: String): List<NetworkFile> {
        val token = getAccessToken(share.host)
        val folderId = resolvePathToId(token, share.host, remotePath)

        val q = URLEncoder.encode("'$folderId' in parents and trashed=false", "UTF-8")
        val fields = URLEncoder.encode("files(id,name,mimeType,size,modifiedTime)", "UTF-8")
        val url = "https://www.googleapis.com/drive/v3/files?q=$q&fields=$fields&pageSize=1000"

        val response = withContext(Dispatchers.IO) {
            client.newCall(
                Request.Builder().url(url).header("Authorization", "Bearer $token").get().build()
            ).execute()
        }
        val body = response.body?.string() ?: "{}"
        if (!response.isSuccessful) throw IOException("GDrive listFiles failed: ${response.code} $body")

        val json = gson.fromJson(body, JsonObject::class.java)
        val files = json.getAsJsonArray("files") ?: return emptyList()

        val cache = pathIdCache.getOrPut(share.host) { mutableMapOf() }
        val cleanRemote = remotePath.trim('/')
        val format = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }

        val result = mutableListOf<NetworkFile>()
        for (item in files) {
            val obj = item.asJsonObject
            val name = obj.get("name").asString
            val id   = obj.get("id").asString
            val isDir = obj.get("mimeType")?.asString == "application/vnd.google-apps.folder"
            val size = obj.get("size")?.asLong ?: 0L
            val dateStr = obj.get("modifiedTime")?.asString
            val time = dateStr?.let { runCatching { format.parse(it)?.time }.getOrNull() } ?: 0L

            val childPath = if (cleanRemote.isEmpty()) name else "$cleanRemote/$name"
            cache[childPath] = id

            result.add(NetworkFile(name = name, path = childPath, isDirectory = isDir, size = size, lastModified = time))
        }
        GoRoLog.d("GDriveClient", "listFiles($remotePath): ${result.size} items")
        return result
    }

    suspend fun mkdir(share: NetworkShare, remotePath: String) {
        val token = getAccessToken(share.host)
        val (parentId, folderName) = resolveParent(token, share.host, remotePath)

        // Check if a folder with this name already exists in the parent
        // to prevent creating duplicate-named folders on Google Drive
        val existingId = withContext(Dispatchers.IO) {
            runCatching { findFileIdInFolderSync(token, parentId, folderName) }.getOrNull()
        }
        if (existingId != null) {
            pathIdCache.getOrPut(share.host) { mutableMapOf() }[remotePath.trim('/')] = existingId
            GoRoLog.d("GDriveClient", "mkdir($remotePath) → reuse existing $existingId")
            return
        }

        val meta = gson.toJson(mapOf(
            "name"     to folderName,
            "mimeType" to "application/vnd.google-apps.folder",
            "parents"  to listOf(parentId)
        )).toRequestBody("application/json".toMediaType())

        val response = withContext(Dispatchers.IO) {
            client.newCall(
                Request.Builder()
                    .url("https://www.googleapis.com/drive/v3/files?fields=id")
                    .header("Authorization", "Bearer $token")
                    .post(meta)
                    .build()
            ).execute()
        }
        if (!response.isSuccessful) throw IOException("GDrive mkdir failed: ${response.code} ${response.message}")
        val id = gson.fromJson(response.body?.string(), JsonObject::class.java).get("id").asString
        pathIdCache.getOrPut(share.host) { mutableMapOf() }[remotePath.trim('/')] = id
        GoRoLog.d("GDriveClient", "mkdir($remotePath) → $id")
    }

    suspend fun deleteFile(share: NetworkShare, remotePath: String) {
        val token = getAccessToken(share.host)
        val fileId = resolvePathToId(token, share.host, remotePath)

        val response = withContext(Dispatchers.IO) {
            client.newCall(
                Request.Builder()
                    .url("https://www.googleapis.com/drive/v3/files/$fileId")
                    .header("Authorization", "Bearer $token")
                    .delete()
                    .build()
            ).execute()
        }
        if (!response.isSuccessful && response.code != 404) {
            throw IOException("GDrive delete failed: ${response.code} ${response.message}")
        }
        invalidateCacheBelow(share.host, remotePath)
        GoRoLog.d("GDriveClient", "deleteFile($remotePath)")
    }

    suspend fun rename(share: NetworkShare, fromPath: String, toPath: String) {
        val token = getAccessToken(share.host)
        val fileId = resolvePathToId(token, share.host, fromPath)
        
        val fromParts = fromPath.trim('/').split("/")
        val fromParentPath = fromParts.dropLast(1).joinToString("/")
        
        val toParts = toPath.trim('/').split("/")
        val toName = toParts.last()
        val toParentPath = toParts.dropLast(1).joinToString("/")

        val body = gson.toJson(mapOf("name" to toName)).toRequestBody("application/json".toMediaType())
        
        val urlBuilder = StringBuilder("https://www.googleapis.com/drive/v3/files/$fileId?fields=id,name")
        
        if (fromParentPath != toParentPath) {
            val oldParentId = resolvePathToId(token, share.host, fromParentPath)
            val newParentId = resolvePathToId(token, share.host, toParentPath)
            urlBuilder.append("&addParents=").append(newParentId)
            urlBuilder.append("&removeParents=").append(oldParentId)
        }

        val response = withContext(Dispatchers.IO) {
            client.newCall(
                Request.Builder()
                    .url(urlBuilder.toString())
                    .header("Authorization", "Bearer $token")
                    .patch(body)
                    .build()
            ).execute()
        }
        if (!response.isSuccessful) throw IOException("GDrive rename/move failed: ${response.code} ${response.message}")
        
        invalidateCacheBelow(share.host, fromPath)
        invalidateCacheBelow(share.host, toPath)
        GoRoLog.d("GDriveClient", "rename/move($fromPath → $toPath)")
    }

    suspend fun openInputStream(share: NetworkShare, remotePath: String): Pair<InputStream, Long> {
        GoRoLog.d("GDriveClient", "openInputStream($remotePath)")
        val token = getAccessToken(share.host)
        val fileId = resolvePathToId(token, share.host, remotePath)

        val response = withContext(Dispatchers.IO) {
            client.newCall(
                Request.Builder()
                    .url("https://www.googleapis.com/drive/v3/files/$fileId?alt=media")
                    .header("Authorization", "Bearer $token")
                    .get()
                    .build()
            ).execute()
        }
        if (!response.isSuccessful) {
            response.close()
            throw IOException("GDrive download failed: ${response.code} ${response.message}")
        }
        val responseBody = response.body ?: throw IOException("Empty body from Google Drive")
        return Pair(responseBody.byteStream(), responseBody.contentLength())
    }

    suspend fun openInputStreamForStreaming(share: NetworkShare, remotePath: String, rangeHeader: String?): okhttp3.Response {
        return withContext(Dispatchers.IO) { openInputStreamForStreamingSync(share, remotePath, rangeHeader) }
    }

    fun openInputStreamForStreamingSync(share: NetworkShare, remotePath: String, rangeHeader: String?): okhttp3.Response {
        GoRoLog.d("GDriveClient", "openInputStreamForStreamingSync($remotePath, range=$rangeHeader)")
        val token = getAccessTokenSync(share.host)
        val fileId = resolvePathToIdSync(token, share.host, remotePath)

        val requestBuilder = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files/$fileId?alt=media")
            .header("Authorization", "Bearer $token")
        
        if (rangeHeader != null) {
            requestBuilder.header("Range", rangeHeader)
        }

        val response = client.newCall(requestBuilder.get().build()).execute()
        if (!response.isSuccessful) {
            response.close()
            throw IOException("GDrive stream failed: ${response.code} ${response.message}")
        }
        return response
    }

    fun openRandomAccessFile(share: NetworkShare, remotePath: String): IRandomAccessFile {
        val fileSize = getFileSizeSync(share, remotePath)
        return object : IRandomAccessFile {
            override var size = fileSize

            override fun read(offset: Long, buffer: ByteArray, length: Int): Int {
                if (offset >= size) return -1
                // Ensure we don't request past EOF
                val requestLength = minOf(length.toLong(), size - offset).toInt()
                val rangeEnd = offset + requestLength - 1
                val rangeHeader = "bytes=$offset-$rangeEnd"
                
                var bytesRead = -1
                try {
                    val response = openInputStreamForStreamingSync(share, remotePath, rangeHeader)
                    val stream = response.body?.byteStream()
                    if (stream != null) {
                        bytesRead = stream.read(buffer, 0, requestLength)
                    }
                    runCatching { stream?.close() }
                    runCatching { response.close() }
                } catch (e: Exception) {
                    GoRoLog.e("GDriveClient", "Random Access read failed at $offset", e)
                }
                return bytesRead
            }

            override fun write(offset: Long, buffer: ByteArray, length: Int): Int {
                return 0 // Unsupported
            }

            override fun close() {}
        }
    }

    fun getStreamingUrlAndTokenSync(share: NetworkShare, remotePath: String): Pair<String, String> {
        val token = getAccessTokenSync(share.host)
        val fileId = resolvePathToIdSync(token, share.host, remotePath)
        return Pair("https://www.googleapis.com/drive/v3/files/$fileId?alt=media", token)
    }

    fun getFileSizeSync(share: NetworkShare, remotePath: String): Long {
        val token = getAccessTokenSync(share.host)
        val fileId = resolvePathToIdSync(token, share.host, remotePath)
        val url = "https://www.googleapis.com/drive/v3/files/$fileId?fields=size"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return -1
            val body = response.body?.string() ?: ""
            val json = gson.fromJson(body, JsonObject::class.java)
            return json.get("size")?.asLong ?: -1
        }
    }

    suspend fun uploadStream(
        share: NetworkShare,
        remotePath: String,
        inputStream: InputStream,
        totalSize: Long,
        onProgress: ((Long, Long) -> Unit)? = null
    ) {
        val token = getAccessToken(share.host)
        val (parentId, fileName) = resolveParent(token, share.host, remotePath)

        // Check for existing file (needed for update vs create)
        val existingId = runCatching { resolvePathToId(token, share.host, remotePath) }.getOrNull()

        if (totalSize < 5_000_000L) {
            // Multipart upload for small files
            val boundary = "gdrive_boundary_${System.currentTimeMillis()}"
            val metaJson = if (existingId == null) {
                gson.toJson(mapOf("name" to fileName, "parents" to listOf(parentId)))
            } else {
                gson.toJson(mapOf("name" to fileName))
            }
            val metaPart = "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$metaJson\r\n"
            val dataPart = "--$boundary\r\nContent-Type: application/octet-stream\r\n\r\n"
            val endPart = "\r\n--$boundary--"

            val url = if (existingId == null) {
                "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id"
            } else {
                "https://www.googleapis.com/upload/drive/v3/files/$existingId?uploadType=multipart&fields=id"
            }

            val bodyBytes = metaPart.toByteArray() + dataPart.toByteArray() +
                    inputStream.readBytes() + endPart.toByteArray()
            val requestBody = bodyBytes.toRequestBody("multipart/related; boundary=$boundary".toMediaType())
            val method = if (existingId == null) "POST" else "PATCH"
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .method(method, requestBody)
                .build()

            val response = withContext(Dispatchers.IO) { client.newCall(request).execute() }
            if (!response.isSuccessful) throw IOException("GDrive multipart upload failed: ${response.code} ${response.message}")

            val id = gson.fromJson(response.body?.string(), JsonObject::class.java).get("id")?.asString
            if (id != null) pathIdCache.getOrPut(share.host) { mutableMapOf() }[remotePath.trim('/')] = id
            onProgress?.invoke(totalSize, totalSize)
            return
        }

        // Resumable upload for large files (≥ 5 MB)
        val metaJson = if (existingId == null) {
            gson.toJson(mapOf("name" to fileName, "parents" to listOf(parentId)))
        } else {
            gson.toJson(mapOf("name" to fileName))
        }
        val initUrl = if (existingId == null) {
            "https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&fields=id"
        } else {
            "https://www.googleapis.com/upload/drive/v3/files/$existingId?uploadType=resumable&fields=id"
        }
        val initMethod = if (existingId == null) "POST" else "PATCH"
        val initRequest = Request.Builder()
            .url(initUrl)
            .header("Authorization", "Bearer $token")
            .header("X-Upload-Content-Type", "application/octet-stream")
            .header("X-Upload-Content-Length", totalSize.toString())
            .method(initMethod, metaJson.toRequestBody("application/json".toMediaType()))
            .build()

        val initResponse = withContext(Dispatchers.IO) { client.newCall(initRequest).execute() }
        if (!initResponse.isSuccessful) throw IOException("GDrive resumable init failed: ${initResponse.code}")
        val uploadUrl = initResponse.header("Location")
            ?: throw IOException("No upload URL in Google Drive resumable init response")

        // Upload in 8 MB chunks (must be multiple of 256 KB per Google docs)
        val chunkSize = 8 * 1024 * 1024
        val buffer = ByteArray(chunkSize)
        var offset = 0L

        while (true) {
            var bytesRead = 0
            while (bytesRead < chunkSize) {
                val read = withContext(Dispatchers.IO) { inputStream.read(buffer, bytesRead, chunkSize - bytesRead) }
                if (read == -1) break
                bytesRead += read
            }
            if (bytesRead == 0) break

            val rangeEnd = offset + bytesRead - 1
            val chunkBody = okhttp3.RequestBody.create("application/octet-stream".toMediaType(), buffer, 0, bytesRead)
            val uploadRequest = Request.Builder()
                .url(uploadUrl)
                .header("Content-Length", bytesRead.toString())
                .header("Content-Range", "bytes $offset-$rangeEnd/$totalSize")
                .put(chunkBody)
                .build()

            val uploadResult = withContext(Dispatchers.IO) { client.newCall(uploadRequest).execute() }
            // 308 = Resume Incomplete (continue), 200/201 = done
            if (!uploadResult.isSuccessful && uploadResult.code != 308) {
                throw IOException("GDrive chunk upload failed: ${uploadResult.code} ${uploadResult.message}")
            }
            offset += bytesRead
            onProgress?.invoke(offset, totalSize)
            if (offset >= totalSize || bytesRead < chunkSize) break
        }
        invalidateCacheBelow(share.host, remotePath) // refresh in case existing overwritten
    }

    suspend fun openOutputStream(share: NetworkShare, remotePath: String): OutputStream {
        return object : OutputStream() {
            private val tempFile = java.io.File(
                UfmApplication.instance.cacheDir,
                "gdrive_upload_${System.currentTimeMillis()}"
            )
            private val fileOut = java.io.FileOutputStream(tempFile)

            override fun write(b: Int) = fileOut.write(b)
            override fun write(b: ByteArray, off: Int, len: Int) = fileOut.write(b, off, len)
            override fun flush() = fileOut.flush()

            override fun close() {
                try {
                    fileOut.close()
                    kotlinx.coroutines.runBlocking {
                        java.io.FileInputStream(tempFile).use { fis ->
                            uploadStream(share, remotePath, fis, tempFile.length())
                        }
                    }
                } finally {
                    tempFile.delete()
                }
            }
        }
    }
}
