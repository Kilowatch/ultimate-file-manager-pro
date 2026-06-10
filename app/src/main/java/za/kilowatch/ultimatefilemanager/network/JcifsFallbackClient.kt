package za.kilowatch.ultimatefilemanager.network

import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import jcifs.smb.SmbRandomAccessFile
import za.kilowatch.ultimatefilemanager.util.GoRoLog
import java.util.Properties

/**
 * UFM bypass specifically written to fix the issue where PPSSPP fails to boot
 * sparse game ROM sizes over SMB because smbj respects the 0-byte header.
 * jcifs-ng is more robust with legacy hardware handling sparse formats.
 */
object JcifsFallbackClient {

    /**
     * jcifs-ng context configured to disable SMB1 fallback.
     * Uses a dedicated [BaseContext] rather than [jcifs.context.SingletonContext]
     * so we control the protocol negotiation policy.
     */
    private val smbContext: CIFSContext by lazy {
        val props = Properties().apply {
            setProperty("jcifs.smb.client.minVersion", "SMB202")
            setProperty("jcifs.smb.client.ipcSigningEnforced", "false")
        }
        BaseContext(PropertyConfiguration(props))
    }

    fun openRandomAccessFile(share: NetworkShare, remotePath: String, isWrite: Boolean = false): JcifsRandomAccess {
        val auth = NtlmPasswordAuthenticator(share.domain ?: "", share.username, share.password)
        val context = smbContext.withCredentials(auth)

        val parts = share.remotePath.trim('/').split("/", limit = 2)
        val shareName = if (parts.isNotEmpty()) parts[0] else ""
        val basePath = if (parts.size > 1) parts[1] else ""
        val innerPath = "$basePath/${remotePath.trimStart('/')}".trim('/')

        val url = "smb://${share.host}/$shareName/$innerPath"
        GoRoLog.d("JcifsFallbackClient hooking direct stream to: $url")

        val smbFile = SmbFile(url, context)
        
        if (isWrite && !smbFile.exists()) {
            try { 
                val lastSlash = url.lastIndexOf('/')
                if (lastSlash > 0) {
                    val parentUrl = url.substring(0, lastSlash + 1)
                    val parentDir = SmbFile(parentUrl, context)
                    if (!parentDir.exists()) {
                        parentDir.mkdirs()
                    }
                }
                smbFile.createNewFile() 
            } catch (e: Exception) { GoRoLog.e("JCIFS failed to create new file", e) }
        }
        
        val raf = SmbRandomAccessFile(smbFile, if (isWrite) "rw" else "r")
        var size = try { smbFile.length() } catch (e: Exception) { 2147483647L }
        
        if (size == 0L) {
            GoRoLog.w("Mocking proxy stream size to 2GB for 0-byte file: $remotePath")
            size = 2147483647L
        }

        return JcifsRandomAccess(raf, size)
    }

    class JcifsRandomAccess(
        private val raf: SmbRandomAccessFile,
        override var size: Long
    ) : IRandomAccessFile {
        override fun read(offset: Long, buffer: ByteArray, length: Int): Int {
            if (offset >= size && size != 2147483647L) return -1
            try {
                raf.seek(offset)
                var totalRead = 0
                var toRead = length
                while (toRead > 0) {
                    val bytesRead = raf.read(buffer, totalRead, toRead)
                    if (bytesRead <= 0) break
                    totalRead += bytesRead
                    toRead -= bytesRead
                }
                return if (totalRead == 0) -1 else totalRead
            } catch (e: Exception) {
                GoRoLog.e("JCIFS read fault at offset $offset", e)
                return -1
            }
        }

        override fun write(offset: Long, buffer: ByteArray, length: Int): Int {
            raf.seek(offset)
            raf.write(buffer, 0, length)
            val endOffset = offset + length
            if (endOffset > size) size = endOffset
            return length
        }

        override fun close() {
            try { raf.close() } catch (ignored: Exception) {}
        }
    }
}
