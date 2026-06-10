package za.kilowatch.ultimatefilemanager.server

import android.content.Context
import org.apache.sshd.common.file.FileSystemFactory
import org.apache.sshd.common.session.SessionContext
import java.io.IOException
import java.nio.file.FileSystem
import java.nio.file.Path
import java.net.URI

/**
 * Implementation of [FileSystemFactory] for the SFTP VFS.
 *
 * Security notes:
 * - Anonymous access is NOT supported. Sessions whose username cannot be
 *   resolved to a profile are rejected (M-2: no fallback to filesDir).
 * - Anonymous username is explicitly blocked even if somehow passed.
 */
class UfmSftpFileSystemFactory(private val context: Context) : FileSystemFactory {

    override fun createFileSystem(session: SessionContext): FileSystem {
        val username = session.username
            ?: throw IOException("SFTP session has no username — rejecting")

        // Explicit anonymous block.
        if (username.equals("anonymous", ignoreCase = true)) {
            throw IOException("Anonymous login is not permitted")
        }

        val profileRepo = FtpServerProfileRepository.getInstance(context)
        val profile = profileRepo.getByUsername(username)
            ?: throw IOException("No profile found for username: $username")

        // M-2: Never fall back to filesDir — always use the profile's configured location.
        val rootUri = profile.defaultLocationUri
        if (rootUri.isBlank()) {
            throw IOException("Profile '$username' has no default location configured")
        }

        val readOnly = profile.readOnly

        val provider = UfmSftpFileSystemProvider(context, readOnly, rootUri)
        return provider.newFileSystem(URI("ufm:///"), mutableMapOf("rootUri" to rootUri))
    }

    override fun getUserHomeDir(session: SessionContext): Path {
        val fs = createFileSystem(session) as UfmSftpFileSystem
        return fs.getPath("/")
    }
}
