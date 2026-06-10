package za.kilowatch.ultimatefilemanager.server

import android.content.Context
import android.util.Log
import kotlinx.coroutines.runBlocking
import za.kilowatch.ultimatefilemanager.network.*
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Core bridge that maps unified URIs to backend operations.
 * Supports: file://, smb://, ftp://, sftp://, nfs://, tv://, gdrive://, onedrive://
 *
 * Security notes:
 * - M-1: [normalizePath] prevents `..` from escaping the virtual root.
 *   [UfmSftpFileSystemProvider.assertWithinRoot] provides an additional canonical-
 *   path check for file:// roots to catch symlink-based traversal.
 * - L-2: Log messages redact the URI path — only the scheme and a fixed label
 *   are emitted so no file names or share credentials appear in logcat.
 * - L-4: [delete] returns a clear indication when the target is absent.
 */
object UfmFileSystemBridge {

    // ── Short-lived directory listing cache ───────────────────────────────────
    private val metaCache = ConcurrentHashMap<String, Pair<NetworkFile, Long>>()
    private const val CACHE_TTL_MS = 5_000L

    private fun cacheFile(uri: String, file: NetworkFile) {
        metaCache[uri] = file to System.currentTimeMillis()
    }

    private fun cachedFile(uri: String): NetworkFile? {
        val entry = metaCache[uri] ?: return null
        if (System.currentTimeMillis() - entry.second > CACHE_TTL_MS) {
            metaCache.remove(uri)
            return null
        }
        return entry.first
    }

    private fun invalidate(uri: String) {
        metaCache.remove(uri)
        val prefix = uri.substringBeforeLast('/') + '/'
        metaCache.keys.filter { it.startsWith(prefix) }.forEach { metaCache.remove(it) }
    }

    // ── Short-lived paired-device cache ───────────────────────────────────────
    // Avoids hitting SharedPreferences + EncryptedSharedPreferences on every
    // tv:// file operation. Same TTL as the file metadata cache.
    @Volatile private var deviceCacheList: List<za.kilowatch.ultimatefilemanager.network.PairedDevice> = emptyList()
    @Volatile private var deviceCacheTimeMs: Long = 0L

    private fun getDeviceById(
        context: Context,
        id: String
    ): za.kilowatch.ultimatefilemanager.network.PairedDevice? {
        val now = System.currentTimeMillis()
        if (now - deviceCacheTimeMs > CACHE_TTL_MS) {
            deviceCacheList = PairingManager.getInstance(context).getAllPairedDevices()
            deviceCacheTimeMs = now
        }
        return deviceCacheList.find { it.deviceId == id }
    }

    // ── L-2: Redacted logging helper ──────────────────────────────────────────
    // Only logs the URI scheme so no paths or credentials appear in logcat.
    private fun schemeOf(uri: String) = uri.substringBefore("://")

    private const val TAG = "UfmBridge"

    fun exists(context: Context, uri: String): Boolean {
        return try {
            getFileMetadata(context, uri) != null
        } catch (e: Exception) {
            false
        }
    }

    fun isDirectory(context: Context, uri: String): Boolean {
        return getFileMetadata(context, uri)?.isDirectory ?: false
    }

    fun listFiles(context: Context, uri: String): List<NetworkFile> {
        val (scheme, id, path) = parseUri(uri)
        val files = when (scheme) {
            "file" -> {
                val dir = File(path)
                dir.listFiles()?.map { f ->
                    NetworkFile(
                        name = f.name,
                        path = f.absolutePath,
                        isDirectory = f.isDirectory,
                        size = f.length(),
                        lastModified = f.lastModified()
                    )
                } ?: emptyList()
            }
            "smb" -> {
                val share = NetworkShareRepository.getInstance(context).getById(id) ?: return emptyList()
                SmbShareClient.listFiles(share, path)
            }
            "ftp" -> {
                val share = NetworkShareRepository.getInstance(context).getById(id) ?: return emptyList()
                FtpShareClient.listFiles(share, path)
            }
            "sftp" -> {
                val share = NetworkShareRepository.getInstance(context).getById(id) ?: return emptyList()
                SshShareClient.listFiles(share, path)
            }
            "nfs" -> {
                val share = NetworkShareRepository.getInstance(context).getById(id) ?: return emptyList()
                NfsShareClient.listFiles(share, path)
            }
            "tv" -> {
                val device = getDeviceById(context, id) ?: return emptyList()
                val share = deviceToShare(device)
                TvShareClient.listFiles(share, path)
            }
            "dropbox" -> {
                val storage = OnlineStorageRepository.getInstance(context).getById(id) ?: return emptyList()
                val share = storageToShare(storage)
                runBlocking { DropboxShareClient.listFiles(share, path) }

            }
            "gdrive" -> {
                val storage = OnlineStorageRepository.getInstance(context).getById(id) ?: return emptyList()
                val share = storageToShare(storage)
                runBlocking { GoogleDriveShareClient.listFiles(share, path) }
            }
            "onedrive" -> {
                val storage = OnlineStorageRepository.getInstance(context).getById(id) ?: return emptyList()
                val share = storageToShare(storage)
                runBlocking { OnedriveShareClient.listFiles(share, path) }
            }
            "s3" -> {
                val storage = OnlineStorageRepository.getInstance(context).getById(id) ?: return emptyList()
                val share = storageToShare(storage)
                runBlocking { S3ShareClient.listFiles(share, path) }
            }
            else -> emptyList()
        }
        Log.d(TAG, "listFiles [${schemeOf(uri)}] -> ${files.size} items")

        val cleanPath = path.trimEnd('/')
        files.forEach { file ->
            val childPath = if (cleanPath.isEmpty() || cleanPath == "/") "/" + file.name
                            else "$cleanPath/${file.name}"
            cacheFile(buildUri(scheme, id, childPath), file)
        }
        return files
    }

    fun getFileMetadata(context: Context, uri: String): NetworkFile? {
        cachedFile(uri)?.let { return it }

        val (scheme, id, path) = parseUri(uri)
        val result = when (scheme) {
            "file" -> {
                val f = File(path)
                if (!f.exists()) return null
                NetworkFile(f.name, f.absolutePath, f.isDirectory, f.length(), f.lastModified())
            }
            "smb" -> {
                val share = NetworkShareRepository.getInstance(context).getById(id) ?: return null
                val parentPath = getParentPath(path)
                val name = getFileName(path)
                if (path.isEmpty() || path == "/") return NetworkFile("root", "/", true, 0, 0)
                SmbShareClient.listFiles(share, parentPath).find { it.name == name }
            }
            "ftp", "sftp", "nfs", "tv", "gdrive", "onedrive", "dropbox", "s3", "webdav" -> {
                val parentPath = getParentPath(path)
                val name = getFileName(path)
                if (path.isEmpty() || path == "/") return NetworkFile("root", "/", true, 0, 0)
                listFiles(context, buildUri(scheme, id, parentPath)).find { it.name == name }
            }
            else -> null
        }
        if (result != null) cacheFile(uri, result)
        return result
    }

    fun openInputStream(context: Context, uri: String, startOffset: Long = 0): InputStream {
        val (scheme, id, path) = parseUri(uri)
        return when (scheme) {
            "file" -> {
                val fis = File(path).inputStream()
                if (startOffset > 0) fis.skip(startOffset)
                fis
            }
            "smb" -> {
                val share = NetworkShareRepository.getInstance(context).getById(id)!!
                runBlocking {
                    val stream = SmbShareClient.openInputStream(share, path)
                    if (startOffset > 0) stream.skip(startOffset)
                    stream
                }
            }
            "ftp" -> {
                val share = NetworkShareRepository.getInstance(context).getById(id)!!
                runBlocking { FtpShareClient.openInputStream(share, path, startOffset) }
            }
            "sftp" -> {
                val share = NetworkShareRepository.getInstance(context).getById(id)!!
                runBlocking { SshShareClient.openInputStream(share, path) }
            }
            "nfs" -> {
                val share = NetworkShareRepository.getInstance(context).getById(id)!!
                NfsShareClient.openInputStream(share, path)
            }
            "tv" -> {
                val device = getDeviceById(context, id)!!
                runBlocking {
                    val stream = TvShareClient.openInputStream(deviceToShare(device), path)
                    if (startOffset > 0) stream.skip(startOffset)
                    stream
                }
            }
            "dropbox" -> {
                val storage = OnlineStorageRepository.getInstance(context).getById(id)!!
                runBlocking {
                    val stream = DropboxShareClient.openInputStream(storageToShare(storage), path).first
                    if (startOffset > 0) stream.skip(startOffset)
                    stream
                }

            }
            "gdrive" -> {
                val storage = OnlineStorageRepository.getInstance(context).getById(id)!!
                runBlocking {
                    val stream = GoogleDriveShareClient.openInputStream(storageToShare(storage), path).first
                    if (startOffset > 0) stream.skip(startOffset)
                    stream
                }
            }
            "onedrive" -> {
                val storage = OnlineStorageRepository.getInstance(context).getById(id)!!
                runBlocking {
                    val stream = OnedriveShareClient.openInputStream(storageToShare(storage), path).first
                    if (startOffset > 0) stream.skip(startOffset)
                    stream
                }
            }
            "s3" -> {
                val storage = OnlineStorageRepository.getInstance(context).getById(id)!!
                runBlocking {
                    val stream = S3ShareClient.openInputStream(storageToShare(storage), path).first
                    if (startOffset > 0) stream.skip(startOffset)
                    stream
                }
            }
            else -> throw IOException("Unsupported scheme: $scheme")
        }
    }

    fun openOutputStream(context: Context, uri: String): OutputStream {
        invalidate(uri)
        val (scheme, id, path) = parseUri(uri)
        return when (scheme) {
            "file" -> File(path).outputStream()
            "smb" -> {
                val share = NetworkShareRepository.getInstance(context).getById(id)!!
                runBlocking { SmbShareClient.openOutputStream(share, path) }
            }
            "ftp" -> {
                val share = NetworkShareRepository.getInstance(context).getById(id)!!
                runBlocking { FtpShareClient.openOutputStream(share, path) }
            }
            "sftp" -> {
                val share = NetworkShareRepository.getInstance(context).getById(id)!!
                runBlocking { SshShareClient.openOutputStream(share, path) }
            }
            "nfs" -> {
                val share = NetworkShareRepository.getInstance(context).getById(id)!!
                NfsShareClient.openOutputStream(share, path)
            }
            "tv" -> {
                val device = getDeviceById(context, id)!!
                val tempFile = File(context.cacheDir, "tv_upload_${System.currentTimeMillis()}")
                object : java.io.FileOutputStream(tempFile) {
                    override fun close() {
                        super.close()
                        runBlocking {
                            java.io.FileInputStream(tempFile).use { fis ->
                                TvShareClient.uploadStream(deviceToShare(device), path, fis, tempFile.length())
                            }
                        }
                        tempFile.delete()
                    }
                }
            }
            "dropbox" -> {
                val storage = OnlineStorageRepository.getInstance(context).getById(id)!!
                runBlocking { DropboxShareClient.openOutputStream(storageToShare(storage), path) }

            }
            "gdrive" -> {
                val storage = OnlineStorageRepository.getInstance(context).getById(id)!!
                runBlocking { GoogleDriveShareClient.openOutputStream(storageToShare(storage), path) }
            }
            "onedrive" -> {
                val storage = OnlineStorageRepository.getInstance(context).getById(id)!!
                runBlocking { OnedriveShareClient.openOutputStream(storageToShare(storage), path) }
            }
            "s3" -> {
                val storage = OnlineStorageRepository.getInstance(context).getById(id)!!
                runBlocking { S3ShareClient.openOutputStream(storageToShare(storage), path) }
            }
            else -> throw IOException("Unsupported scheme: $scheme")
        }
    }

    fun mkdir(context: Context, uri: String) {
        invalidate(uri)
        val (scheme, id, path) = parseUri(uri)
        when (scheme) {
            "file" -> File(path).mkdirs()
            "smb" -> {
                val share = NetworkShareRepository.getInstance(context).getById(id)!!
                SmbShareClient.mkdir(share, path)
            }
            "ftp" -> {
                val share = NetworkShareRepository.getInstance(context).getById(id)!!
                FtpShareClient.mkdir(share, path)
            }
            "sftp" -> {
                val share = NetworkShareRepository.getInstance(context).getById(id)!!
                SshShareClient.mkdir(share, path)
            }
            "nfs" -> {
                val share = NetworkShareRepository.getInstance(context).getById(id)!!
                NfsShareClient.mkdir(share, path)
            }
            "tv" -> {
                val device = getDeviceById(context, id)!!
                TvShareClient.mkdir(deviceToShare(device), path)
            }
            "dropbox" -> {
                val storage = OnlineStorageRepository.getInstance(context).getById(id)!!
                runBlocking { DropboxShareClient.mkdir(storageToShare(storage), path) }

            }
            "gdrive" -> {
                val storage = OnlineStorageRepository.getInstance(context).getById(id)!!
                runBlocking { GoogleDriveShareClient.mkdir(storageToShare(storage), path) }
            }
            "onedrive" -> {
                val storage = OnlineStorageRepository.getInstance(context).getById(id)!!
                runBlocking { OnedriveShareClient.mkdir(storageToShare(storage), path) }
            }
            "s3" -> {
                val storage = OnlineStorageRepository.getInstance(context).getById(id)!!
                runBlocking { S3ShareClient.mkdir(storageToShare(storage), path) }
            }
        }
    }

    /**
     * Deletes the resource at [uri].
     *
     * L-4: Returns `false` when the target was already absent (previously a silent no-op).
     * Callers that need to differentiate between "deleted" and "already gone" should check the return value.
     */
    fun delete(context: Context, uri: String): Boolean {
        val (scheme, id, path) = parseUri(uri)
        val metadata = getFileMetadata(context, uri) ?: run {
            Log.d(TAG, "delete: target not found (already absent) [${schemeOf(uri)}]")
            return false
        }

        when (scheme) {
            "file" -> {
                val file = File(path)
                if (file.isDirectory) recursiveDelete(file)
                else file.delete()

                val filepart = File(path + ".filepart")
                if (filepart.exists()) {
                    filepart.delete()
                    invalidate(uri + ".filepart")
                }
            }
            "smb" -> {
                val share = NetworkShareRepository.getInstance(context).getById(id)!!
                runBlocking {
                    if (metadata.isDirectory) SmbShareClient.deleteDir(share, path)
                    else SmbShareClient.deleteFile(share, path)
                }
            }
            "ftp" -> {
                val share = NetworkShareRepository.getInstance(context).getById(id)!!
                if (metadata.isDirectory) FtpShareClient.deleteDir(share, path)
                else FtpShareClient.deleteFile(share, path)
            }
            "sftp" -> {
                val share = NetworkShareRepository.getInstance(context).getById(id)!!
                SshShareClient.delete(share, path, metadata.isDirectory)
            }
            "nfs" -> {
                val share = NetworkShareRepository.getInstance(context).getById(id)!!
                if (metadata.isDirectory) NfsShareClient.deleteDir(share, path)
                else NfsShareClient.deleteFile(share, path)
            }
            "tv" -> {
                val device = getDeviceById(context, id)!!
                if (metadata.isDirectory) TvShareClient.deleteDir(deviceToShare(device), path)
                else TvShareClient.deleteFile(deviceToShare(device), path)
            }
            "dropbox" -> {
                val storage = OnlineStorageRepository.getInstance(context).getById(id)!!
                runBlocking { DropboxShareClient.deleteFile(storageToShare(storage), path) }

            }
            "gdrive" -> {
                val storage = OnlineStorageRepository.getInstance(context).getById(id)!!
                runBlocking { GoogleDriveShareClient.deleteFile(storageToShare(storage), path) }
            }
            "onedrive" -> {
                val storage = OnlineStorageRepository.getInstance(context).getById(id)!!
                runBlocking { OnedriveShareClient.deleteFile(storageToShare(storage), path) }
            }
            "s3", "idrive-e2" -> {
                val storage = OnlineStorageRepository.getInstance(context).getById(id)!!
                runBlocking {
                    val share = storageToShare(storage)
                    if (metadata.isDirectory) S3ShareClient.deleteDir(share, path)
                    else S3ShareClient.deleteFile(share, path)
                }
            }
        }
        invalidate(uri)
        return true
    }

    private fun recursiveDelete(file: File) {
        if (file.isDirectory) {
            file.listFiles()?.forEach { recursiveDelete(it) }
        }
        file.delete()
    }

    fun rename(context: Context, uri: String, newName: String) {
        val (scheme, id, path) = parseUri(uri)
        val parent = getParentPath(path)
        val newPath = if (parent.isEmpty() || parent == "/") newName else "$parent/$newName"

        when (scheme) {
            "file" -> File(path).renameTo(File(newPath))
            "smb" -> {
                val share = NetworkShareRepository.getInstance(context).getById(id)!!
                SmbShareClient.rename(share, path, newPath)
            }
            "ftp" -> {
                val share = NetworkShareRepository.getInstance(context).getById(id)!!
                FtpShareClient.rename(share, path, newPath)
            }
            "sftp" -> {
                val share = NetworkShareRepository.getInstance(context).getById(id)!!
                SshShareClient.rename(share, path, newPath)
            }
            "nfs" -> {
                val share = NetworkShareRepository.getInstance(context).getById(id)!!
                NfsShareClient.rename(share, path, newPath)
            }
            "tv" -> {
                val device = getDeviceById(context, id)!!
                TvShareClient.rename(deviceToShare(device), path, newPath)
            }
            "dropbox" -> {
                val storage = OnlineStorageRepository.getInstance(context).getById(id)!!
                runBlocking { DropboxShareClient.rename(storageToShare(storage), path, newPath) }

            }
            "gdrive" -> {
                val storage = OnlineStorageRepository.getInstance(context).getById(id)!!
                runBlocking { GoogleDriveShareClient.rename(storageToShare(storage), path, newPath) }
            }
            "onedrive" -> {
                val storage = OnlineStorageRepository.getInstance(context).getById(id)!!
                runBlocking { OnedriveShareClient.rename(storageToShare(storage), path, newPath) }
            }
            "s3", "idrive-e2" -> {
                val storage = OnlineStorageRepository.getInstance(context).getById(id)!!
                runBlocking { S3ShareClient.rename(storageToShare(storage), path, newPath) }
            }
        }
        invalidate(uri)
        invalidate(buildUri(scheme, id, newPath))
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun parseUri(uri: String): Triple<String, String, String> {
        val scheme = uri.substringBefore("://")
        val rest = uri.substringAfter("://")
        val id = rest.substringBefore("/")
        val rawPath = "/" + rest.substringAfter("/").trimStart('/')
        val normalized = normalizePath(rawPath)
        return Triple(scheme, id, normalized)
    }

    /**
     * Canonicalizes a virtual path:
     * - Collapses multiple slashes
     * - Resolves '.' and '..' components — '..' above root is clamped (M-1)
     * - Ensures result starts with '/' and has no trailing slash (unless root)
     */
    private fun normalizePath(path: String): String {
        val stack = mutableListOf<String>()
        val components = path.split("/").filter { it.isNotEmpty() && it != "." }
        for (comp in components) {
            if (comp == "..") {
                if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                // If stack is empty, '..' is clamped at root — no escape possible.
            } else {
                stack.add(comp)
            }
        }
        return "/" + stack.joinToString("/")
    }

    private fun buildUri(scheme: String, id: String, path: String): String {
        return "$scheme://$id/${path.trimStart('/')}"
    }

    private fun getParentPath(path: String): String {
        val clean = path.trim('/')
        if (clean.isEmpty()) return "/"
        val lastSlash = clean.lastIndexOf('/')
        return if (lastSlash < 0) "/" else "/" + clean.substring(0, lastSlash)
    }

    private fun getFileName(path: String): String {
        return path.trim('/').substringAfterLast('/')
    }

    private fun deviceToShare(device: PairedDevice): NetworkShare {
        return NetworkShare(
            host = device.lastIp,
            port = device.lastPort,
            type = ShareType.TV,
            name = device.name
        )
    }

    private fun storageToShare(storage: OnlineStorage): NetworkShare {
        return NetworkShare(
            id         = storage.id,
            name       = storage.displayName.ifEmpty { storage.email },
            host       = when (storage.provider) {
                OnlineStorageProvider.GOOGLE_DRIVE -> storage.email
                OnlineStorageProvider.ONEDRIVE     -> storage.email
                OnlineStorageProvider.DROPBOX      -> storage.email
                OnlineStorageProvider.AWS_S3,
                OnlineStorageProvider.IDRIVE_E2    -> storage.s3Endpoint ?: ""
                OnlineStorageProvider.WEBDAV        -> storage.webDavUrl ?: ""
            },
            domain     = storage.s3Bucket ?: "",
            remotePath = storage.s3Region ?: "",
            username   = when {
                storage.isWebDavProvider -> storage.webDavUsername ?: ""
                else                     -> storage.s3AccessKey ?: ""
            },
            password   = when {
                storage.isWebDavProvider -> storage.webDavPassword ?: ""
                else                     -> storage.s3SecretKey ?: ""
            },
            type = when (storage.provider) {
                OnlineStorageProvider.GOOGLE_DRIVE -> ShareType.GOOGLE_DRIVE
                OnlineStorageProvider.ONEDRIVE     -> ShareType.ONEDRIVE
                OnlineStorageProvider.DROPBOX      -> ShareType.DROPBOX
                OnlineStorageProvider.AWS_S3       -> ShareType.AWS_S3
                OnlineStorageProvider.IDRIVE_E2    -> ShareType.IDRIVE_E2
                OnlineStorageProvider.WEBDAV       -> ShareType.WEBDAV
            }
        )
    }
}
