package za.kilowatch.ultimatefilemanager.server

import android.content.Context
import org.apache.ftpserver.ftplet.FileSystemFactory
import org.apache.ftpserver.ftplet.FileSystemView
import org.apache.ftpserver.ftplet.User
import org.apache.ftpserver.usermanager.impl.WritePermission

/**
 * Implementation of [FileSystemFactory] for the File Server VFS.
 */
class UfmFileSystemFactory(private val context: Context) : FileSystemFactory {
    override fun createFileSystemView(user: User): FileSystemView {
        val rootUri = user.homeDirectory
        // Derive readOnly from the user's authority list: absence of WritePermission = read-only.
        val readOnly = user.getAuthorities(WritePermission::class.java).isEmpty()
        return UfmFileSystemView(context, user, rootUri, readOnly)
    }
}
