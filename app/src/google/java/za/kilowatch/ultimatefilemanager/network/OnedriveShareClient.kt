package za.kilowatch.ultimatefilemanager.network

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.microsoft.identity.client.IMultipleAccountPublicClientApplication
import com.microsoft.identity.client.PublicClientApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.UfmApplication
import java.io.InputStream
import java.io.OutputStream
import java.io.IOException
import okio.source
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import za.kilowatch.ultimatefilemanager.util.GoRoLog
import java.util.concurrent.TimeUnit

/**
 * OneDrive file operations via Microsoft Graph API.
 * Google Play source set: full MSAL + Graph implementation.
 * Amazon source set: see src/amazon/ for the no-op stub.
 */
object OnedriveShareClient {
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .build()
    }
    private val gson = Gson()
    private var msalApp: IMultipleAccountPublicClientApplication? = null

    private suspend fun getMsalApp(): IMultipleAccountPublicClientApplication = suspendCoroutine { cont ->
        MsalProvider.getApp(UfmApplication.instance) { application, exception ->
            if (application != null) {
                msalApp = application
                cont.resume(application)
            } else {
                GoRoLog.e("GoRoAuth", "OnedriveShareClient: MSAL Init Error: ${exception?.message}")
                cont.resumeWithException(exception ?: Exception("Unknown MSAL error"))
            }
        }
    }

    private suspend fun getAccessToken(email: String): String {
        GoRoLog.d("GoRoAuth", "OnedriveShareClient: getAccessToken for $email")
        val repo = OnlineStorageRepository.getInstance(UfmApplication.instance)
        val storage = repo.getAll().find { it.email.equals(email, ignoreCase = true) && it.provider == OnlineStorageProvider.ONEDRIVE }
            ?: throw Exception("OneDrive account not found for $email")

        if (storage.refreshToken != null) {
            try {
                return refreshAccessToken(email, storage.refreshToken!!)
            } catch (e: Exception) {
                GoRoLog.e("GoRoAuth", "Failed to refresh token via REST for $email", e)
            }
        }
        return getAccessTokenViaMsal(email)
    }

    fun getAccessTokenSync(email: String): String {
        val repo = OnlineStorageRepository.getInstance(UfmApplication.instance)
        val storage = repo.getAll().find { it.email.equals(email, ignoreCase = true) && it.provider == OnlineStorageProvider.ONEDRIVE }
            ?: throw Exception("OneDrive account not found for $email")

        if (storage.refreshToken != null) {
            try {
                return runBlocking { refreshAccessToken(email, storage.refreshToken!!) }
            } catch (e: Exception) {
                GoRoLog.e("GoRoAuth", "Failed to refresh token via REST for $email", e)
            }
        }
        return runBlocking { getAccessTokenViaMsal(email) }
    }

    private suspend fun refreshAccessToken(email: String, refreshToken: String): String = withContext(Dispatchers.IO) {
        val clientId = "1c135efb-c510-42a7-a7a6-32d29ab38d19"
        val formBody = okhttp3.FormBody.Builder()
            .add("client_id", clientId)
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .build()

        val request = Request.Builder()
            .url("https://login.microsoftonline.com/common/oauth2/v2.0/token")
            .post(formBody)
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: ""
            val json = gson.fromJson(body, JsonObject::class.java)
            
            if (response.isSuccessful) {
                val newAccessToken = json.get("access_token").asString
                val newRefreshToken = json.get("refresh_token")?.asString
                
                if (newRefreshToken != null && newRefreshToken != refreshToken) {
                    // Update stored refresh token
                    val repo = OnlineStorageRepository.getInstance(UfmApplication.instance)
                    val storage = repo.getAll().find { it.email.equals(email, ignoreCase = true) }
                    if (storage != null) {
                        repo.save(storage.copy(refreshToken = newRefreshToken))
                        GoRoLog.d("GoRoAuth", "OnedriveShareClient: Updated refresh token for $email")
                    }
                }
                return@withContext newAccessToken
            } else {
                val error = json.get("error")?.asString ?: response.message
                val errorDesc = json.get("error_description")?.asString ?: ""
                throw IOException("Refresh token failed: $error ($errorDesc)")
            }
        }
    }

    private suspend fun getAccessTokenViaMsal(email: String): String {
        val app = getMsalApp()
        val accounts = withContext(Dispatchers.IO) { app.accounts }
        val account = accounts.find { it.username.equals(email, ignoreCase = true) }
            ?: throw Exception("OneDrive account not found for $email")

        GoRoLog.d("GoRoAuth", "OnedriveShareClient: Calling app.acquireTokenSilentAsync for $email")
        return suspendCoroutine { cont ->
            app.acquireTokenSilentAsync(
                arrayOf("Files.ReadWrite", "User.Read"),
                account,
                // Bug 3 fix: must use 'consumers' (not 'common') for PersonalMicrosoftAccount
                // audience as declared in auth_config_onedrive.json. Using 'common' causes
                // MsalUiRequiredException on every silent refresh for personal accounts.
                "https://login.microsoftonline.com/consumers",
                object : com.microsoft.identity.client.SilentAuthenticationCallback {
                    override fun onSuccess(authenticationResult: com.microsoft.identity.client.IAuthenticationResult) {
                        val grantedScopes = authenticationResult.scope.joinToString(", ")
                        GoRoLog.d("GoRoAuth", "acquireTokenSilentAsync success for $email. Granted scopes: $grantedScopes")
                        cont.resume(authenticationResult.accessToken)
                    }

                    override fun onError(exception: com.microsoft.identity.client.exception.MsalException) {
                        GoRoLog.e("GoRoAuth", "acquireTokenSilentAsync error for $email: ${exception.message}", exception)
                        cont.resumeWithException(exception)
                    }
                }
            )
        }
    }

    private fun encodePath(path: String): String {
        return path.split("/").joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
    }

    private fun buildItemUrl(remotePath: String, suffix: String = ""): String {
        val base = "https://graph.microsoft.com/v1.0/me/drive/root"
        val cleanPath = remotePath.trim('/')
        val suffixPath = if (suffix.isNotEmpty()) "/$suffix" else ""
        return if (cleanPath.isEmpty()) {
            "$base$suffixPath"
        } else {
            "$base:/${encodePath(cleanPath)}:$suffixPath"
        }
    }

    suspend fun listFiles(share: NetworkShare, remotePath: String): List<NetworkFile> {
        val token = getAccessToken(share.host)
        val url = buildItemUrl(remotePath, "children")
        
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .get()
            .build()
            
        val response = withContext(Dispatchers.IO) { client.newCall(request).execute() }
        val body = response.body?.string() ?: "{}"
        
        if (!response.isSuccessful) {
            val err = "OneDrive listFiles failed: ${response.code} ${response.message}\nBody: $body"
            GoRoLog.e("GoRoAuth", err)
            throw IOException(err)
        }
        
        val json = gson.fromJson(body, JsonObject::class.java)
        val values = json.getAsJsonArray("value")
        if (values == null) {
            GoRoLog.w("GoRoAuth", "OnedriveShareClient: 'value' array missing in response for $url. Body: $body")
            return emptyList()
        }
        
        GoRoLog.d("GoRoAuth", "OnedriveShareClient: Found ${values.size()} items for $remotePath")
        if (values.size() == 0) {
            GoRoLog.d("GoRoAuth", "OnedriveShareClient: Empty root? Body: $body")
        }
        
        val result = mutableListOf<NetworkFile>()
        val format = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        
        for (item in values) {
            val obj = item.asJsonObject
            val name = obj.get("name").asString
            val isDirectory = obj.has("folder")
            val size = obj.get("size")?.asLong ?: 0L
            val dateStr = obj.get("lastModifiedDateTime")?.asString
            val time = dateStr?.let { runCatching { format.parse(it)?.time }.getOrNull() } ?: 0L
            
            val cleanRemote = remotePath.trim('/')
            val path = if (cleanRemote.isEmpty()) name else "$cleanRemote/$name"
            
            result.add(NetworkFile(
                name = name,
                path = path,
                isDirectory = isDirectory,
                size = size,
                lastModified = time
            ))
        }
        return result
    }

    suspend fun mkdir(share: NetworkShare, remotePath: String) {
        val token = getAccessToken(share.host)
        val parts = remotePath.trim('/').split("/")
        val folderName = parts.last()
        val parentPath = parts.dropLast(1).joinToString("/")
        
        val url = buildItemUrl(parentPath, "children")
        
        val jsonMap = mapOf(
            "name" to folderName,
            "folder" to mapOf<String, Any>(),
            "@microsoft.graph.conflictBehavior" to "rename"
        )
        val body = gson.toJson(jsonMap).toRequestBody("application/json".toMediaType())
        
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .post(body)
            .build()
            
        val response = withContext(Dispatchers.IO) { client.newCall(request).execute() }
        if (!response.isSuccessful) throw IOException("OneDrive mkdir failed: ${response.code} ${response.message}")
    }

    suspend fun deleteFile(share: NetworkShare, remotePath: String) {
        val token = getAccessToken(share.host)
        val url = buildItemUrl(remotePath)
        
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .delete()
            .build()
            
        val response = withContext(Dispatchers.IO) { client.newCall(request).execute() }
        if (!response.isSuccessful && response.code != 404) {
            throw IOException("OneDrive delete failed: ${response.code} ${response.message}")
        }
    }

    suspend fun rename(share: NetworkShare, fromPath: String, toPath: String) {
        val token = getAccessToken(share.host)
        val url = buildItemUrl(fromPath)
        
        val fromParts = fromPath.trim('/').split("/")
        val fromParentPath = fromParts.dropLast(1).joinToString("/")
        
        val toParts = toPath.trim('/').split("/")
        val toName = toParts.last()
        val toParentPath = toParts.dropLast(1).joinToString("/")
        
        val jsonMap = mutableMapOf<String, Any>("name" to toName)
        
        if (fromParentPath != toParentPath) {
            // Moving to a different folder
            val targetFolderUrl = buildItemUrl(toParentPath)
            val requestF = Request.Builder()
                .url(targetFolderUrl)
                .header("Authorization", "Bearer $token")
                .get()
                .build()
                
            val responseF = withContext(Dispatchers.IO) { client.newCall(requestF).execute() }
            val bodyF = responseF.body?.string() ?: ""
            if (!responseF.isSuccessful) throw IOException("OneDrive move failed (could not resolve target folder): ${responseF.code}\n$bodyF")
            
            val jsonF = gson.fromJson(bodyF, JsonObject::class.java)
            val targetId = jsonF.get("id").asString
            jsonMap["parentReference"] = mapOf("id" to targetId)
        }
        
        val body = gson.toJson(jsonMap).toRequestBody("application/json".toMediaType())
        
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .patch(body)
            .build()
            
        val response = withContext(Dispatchers.IO) { client.newCall(request).execute() }
        if (!response.isSuccessful) throw IOException("OneDrive rename/move failed: ${response.code} ${response.message}")
    }

    suspend fun openInputStream(share: NetworkShare, remotePath: String): Pair<InputStream, Long> {
        GoRoLog.d("GoRoAuth", "OnedriveShareClient: openInputStream for $remotePath")
        val token = getAccessToken(share.host)
        val url = buildItemUrl(remotePath, "content")
        
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .get()
            .build()
            
        val response = withContext(Dispatchers.IO) { client.newCall(request).execute() }
        if (!response.isSuccessful) {
            GoRoLog.e("GoRoAuth", "OnedriveShareClient: Download failed with code ${response.code}")
            response.close()
            throw IOException("OneDrive download failed: ${response.code} ${response.message}")
        }
        GoRoLog.d("GoRoAuth", "OnedriveShareClient: Download success, returning stream.")
        val body = response.body ?: throw IOException("Empty body from OneDrive")
        return Pair(body.byteStream(), body.contentLength())
    }

    suspend fun openInputStreamForStreaming(share: NetworkShare, remotePath: String, rangeHeader: String?): okhttp3.Response {
        return withContext(Dispatchers.IO) { openInputStreamForStreamingSync(share, remotePath, rangeHeader) }
    }

    fun openInputStreamForStreamingSync(share: NetworkShare, remotePath: String, rangeHeader: String?): okhttp3.Response {
        GoRoLog.d("GoRoAuth", "OnedriveShareClient: openInputStreamForStreamingSync for $remotePath")
        val token = getAccessTokenSync(share.host)
        val url = buildItemUrl(remotePath, "content")
        
        val requestBuilder = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            
        if (rangeHeader != null) {
            requestBuilder.header("Range", rangeHeader)
        }
            
        val response = client.newCall(requestBuilder.get().build()).execute()
        if (!response.isSuccessful) {
            GoRoLog.e("GoRoAuth", "OnedriveShareClient: Stream download failed with code ${response.code}")
            response.close()
            throw IOException("OneDrive stream failed: ${response.code} ${response.message}")
        }
        GoRoLog.d("GoRoAuth", "OnedriveShareClient: Stream success, returning response.")
        return response
    }

    fun openRandomAccessFile(share: NetworkShare, remotePath: String): IRandomAccessFile {
        val fileSize = getFileSizeSync(share, remotePath)
        return object : IRandomAccessFile {
            override var size = fileSize

            override fun read(offset: Long, buffer: ByteArray, length: Int): Int {
                if (offset >= size) return -1
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
                    GoRoLog.e("OnedriveClient", "Random Access read failed at $offset", e)
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
        return try {
            val token = getAccessTokenSync(share.host)
            // Fetch item metadata to get the direct download URL (@microsoft.graph.downloadUrl)
            // This URL is pre-authenticated and direct to content servers, avoiding redirect/auth header issues in ExoPlayer.
            val url = buildItemUrl(remotePath, "")
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val json = gson.fromJson(body, JsonObject::class.java)
                    val downloadUrl = json.get("@microsoft.graph.downloadUrl")?.asString
                    if (downloadUrl != null) {
                        return Pair(downloadUrl, "") // Direct URL needs no extra Bearer token
                    }
                }
            }
            // Fallback to the redirecting /content endpoint if downloadUrl is missing
            Pair(buildItemUrl(remotePath, "content"), token)
        } catch (e: Exception) {
            GoRoLog.e("OnedriveShareClient", "Failed to get downloadUrl, falling back", e)
            Pair(buildItemUrl(remotePath, "content"), getAccessTokenSync(share.host))
        }
    }

    fun getFileSizeSync(share: NetworkShare, remotePath: String): Long {
        val token = getAccessTokenSync(share.host)
        val url = buildItemUrl(remotePath, "")
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

    suspend fun uploadStream(share: NetworkShare, remotePath: String, inputStream: InputStream, totalSize: Long, onProgress: ((Long, Long) -> Unit)? = null) {
        // For files < 4MB, use simple upload.
        // For files >= 4MB, use upload session (standard Graph API chunking rule).
        val token = getAccessToken(share.host)
        
        if (totalSize < 4_000_000) {
            // Simple upload
            val url = buildItemUrl(remotePath, "content")
            val requestBody = object : okhttp3.RequestBody() {
                override fun contentType() = "application/octet-stream".toMediaType()
                override fun writeTo(sink: okio.BufferedSink) {
                    inputStream.source().use { source ->
                        sink.writeAll(source)
                    }
                }
            }
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .put(requestBody)
                .build()
                
            val response = withContext(Dispatchers.IO) { client.newCall(request).execute() }
            if (!response.isSuccessful) throw IOException("OneDrive simple upload failed: ${response.code} ${response.message}")
            onProgress?.invoke(totalSize, totalSize)
            return
        }

        // Upload session for large files
        val createSessionUrl = buildItemUrl(remotePath, "createUploadSession")
        val createSessionMap = mapOf("item" to mapOf("@microsoft.graph.conflictBehavior" to "replace"))
        val sessionBody = gson.toJson(createSessionMap).toRequestBody("application/json".toMediaType())
        
        val sessionRequest = Request.Builder()
            .url(createSessionUrl)
            .header("Authorization", "Bearer $token")
            .post(sessionBody)
            .build()
            
        val sessionResponse = withContext(Dispatchers.IO) { client.newCall(sessionRequest).execute() }
        if (!sessionResponse.isSuccessful) throw IOException("Could not create OneDrive upload session: ${sessionResponse.code}")
        
        val sessionJson = gson.fromJson(sessionResponse.body?.string(), JsonObject::class.java)
        val uploadUrl = sessionJson.get("uploadUrl").asString

        // Upload in 320KB chunks (Graph API requirement: multiple of 320KB)
        val chunkSize = 320 * 1024 * 10  // 3.2MB chunks
        val buffer = ByteArray(chunkSize)
        var offset = 0L

        while (true) {
            var bytesRead = 0
            while (bytesRead < chunkSize) {
                val read = withContext(Dispatchers.IO) { inputStream.read(buffer, bytesRead, chunkSize - bytesRead) }
                if (read == -1) break
                bytesRead += read
            }
            
            if (bytesRead == 0) break // EOF reached exactly at boundary
            
            val chunkBody = okhttp3.RequestBody.create("application/octet-stream".toMediaType(), buffer, 0, bytesRead)
            val rangeEnd = offset + bytesRead - 1
            
            val uploadRequest = Request.Builder()
                .url(uploadUrl)
                .header("Content-Length", bytesRead.toString())
                .header("Content-Range", "bytes $offset-$rangeEnd/$totalSize")
                .put(chunkBody)
                .build()
                
            val uploadResult = withContext(Dispatchers.IO) { client.newCall(uploadRequest).execute() }
            if (!uploadResult.isSuccessful) {
                if (uploadResult.code !in 200..299 && uploadResult.code != 308) { // 308 means continue
                    throw IOException("OneDrive chunk upload failed: ${uploadResult.code} ${uploadResult.message}")
                }
            }
            offset += bytesRead
            onProgress?.invoke(offset, totalSize)
            if (offset >= totalSize || bytesRead < chunkSize) break
        }
    }

    suspend fun openOutputStream(share: NetworkShare, remotePath: String): OutputStream {
        // OneDrive Graph API doesn't support raw streaming OutputStreams directly like FTP/SMB.
        // Files are uploaded via standard request bodies or upload sessions.
        // To preserve the NetworkBrowserActivity generic copy/move logic which writes to OutputStream,
        // we write to a temporary file locally and upload it on close().
        return object : OutputStream() {
            private val tempFile = java.io.File(UfmApplication.instance.cacheDir, "onedrive_upload_${System.currentTimeMillis()}")
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
