package za.kilowatch.ultimatefilemanager.storage

import android.os.Parcel
import android.os.Parcelable
import za.kilowatch.ultimatefilemanager.network.NetworkFile
import za.kilowatch.ultimatefilemanager.network.NetworkShare
import za.kilowatch.ultimatefilemanager.network.ShareType
import java.io.File

/**
 * Unified file wrapper for batch rename.
 *
 * Bridges [java.io.File] (local storage) and [NetworkFile] (network shares)
 * so the batch rename engine can process all items through a single code path.
 */
data class BatchRenameItem(
    /** Display name: bare name without extension for files, full name for folders. */
    val name: String,
    /** File extension including leading dot (e.g. ".jpg"), or "" for folders and dotfiles. */
    val extension: String,
    val isDirectory: Boolean,
    val isLocal: Boolean,
    /** Non-null when [isLocal] is true. */
    val localFile: File?,
    /** Non-null when [isLocal] is false. */
    val networkFile: NetworkFile?,
    /** Non-null when [isLocal] is false — carries the connection/auth context. */
    val networkShare: NetworkShare?
) : Parcelable {

    /** Full original display name including extension (e.g. "photo.jpg" or "MyFolder"). */
    val fullName: String get() = name + extension

    /** Modification timestamp in milliseconds (0L if unavailable). */
    val lastModified: Long get() = if (isLocal) (localFile?.lastModified() ?: 0L) else (networkFile?.lastModified ?: 0L)

    companion object {
        /**
         * Create a [BatchRenameItem] from a local file.
         *
         * Extension logic:
         * - Folders → extension = ""
         * - Files whose name starts with "." → extension = "" (dotfiles have no extension)
         * - Files with no "." in name → extension = ""
         * - Otherwise → extension = substring from last "." to end
         */
        fun fromLocalFile(file: File): BatchRenameItem {
            return if (file.isDirectory) {
                BatchRenameItem(
                    name = file.name,
                    extension = "",
                    isDirectory = true,
                    isLocal = true,
                    localFile = file,
                    networkFile = null,
                    networkShare = null
                )
            } else {
                val (baseName, ext) = splitNameAndExtension(file.name)
                BatchRenameItem(
                    name = baseName,
                    extension = ext,
                    isDirectory = false,
                    isLocal = true,
                    localFile = file,
                    networkFile = null,
                    networkShare = null
                )
            }
        }

        /**
         * Create a [BatchRenameItem] from a network file.
         */
        fun fromNetworkFile(file: NetworkFile, share: NetworkShare): BatchRenameItem {
            return if (file.isDirectory) {
                BatchRenameItem(
                    name = file.name,
                    extension = "",
                    isDirectory = true,
                    isLocal = false,
                    localFile = null,
                    networkFile = file,
                    networkShare = share
                )
            } else {
                val (baseName, ext) = splitNameAndExtension(file.name)
                BatchRenameItem(
                    name = baseName,
                    extension = ext,
                    isDirectory = false,
                    isLocal = false,
                    localFile = null,
                    networkFile = file,
                    networkShare = share
                )
            }
        }

        /**
         * Splits a filename into (baseName, extension).
         *
         * Rules:
         * - Dotfiles (name starts with ".") → (name, "")
         * - Files with no "." → (name, "")
         * - Otherwise → substring before last ".", substring from last "." to end
         *
         * Examples:
         * - "photo.jpg"   → ("photo", ".jpg")
         * - ".gitignore"  → (".gitignore", "")
         * - "README"      → ("README", "")
         * - "archive.tar.gz" → ("archive.tar", ".gz")
         */
        fun splitNameAndExtension(fileName: String): Pair<String, String> {
            if (fileName.startsWith(".")) return Pair(fileName, "")
            val lastDot = fileName.lastIndexOf('.')
            if (lastDot <= 0) return Pair(fileName, "")
            return Pair(fileName.substring(0, lastDot), fileName.substring(lastDot))
        }

        @JvmField
        val CREATOR: Parcelable.Creator<BatchRenameItem> =
            object : Parcelable.Creator<BatchRenameItem> {
                override fun createFromParcel(parcel: Parcel): BatchRenameItem {
                    val name = parcel.readString() ?: ""
                    val extension = parcel.readString() ?: ""
                    val isDirectory = parcel.readByte() != 0.toByte()
                    val isLocal = parcel.readByte() != 0.toByte()

                    val localFile: File? = if (isLocal) {
                        val path = parcel.readString()
                        if (path != null) File(path) else null
                    } else {
                        parcel.readString() // skip
                        null
                    }

                    val networkFile: NetworkFile? = if (!isLocal) {
                        val nfName = parcel.readString() ?: ""
                        val nfPath = parcel.readString() ?: ""
                        val nfIsDir = parcel.readByte() != 0.toByte()
                        val nfSize = parcel.readLong()
                        val nfLastMod = parcel.readLong()
                        val nfFreeSpace = parcel.readLong()
                        val nfIconRes = parcel.readInt()
                        val nfIsToggle = parcel.readByte() != 0.toByte()
                        val nfIsToggled = parcel.readByte() != 0.toByte()
                        NetworkFile(
                            name = nfName,
                            path = nfPath,
                            isDirectory = nfIsDir,
                            size = nfSize,
                            lastModified = nfLastMod,
                            freeSpace = nfFreeSpace,
                            iconRes = nfIconRes,
                            isToggle = nfIsToggle,
                            isToggled = nfIsToggled
                        )
                    } else {
                        // skip network fields
                        parcel.readString(); parcel.readString()
                        parcel.readByte(); parcel.readLong(); parcel.readLong()
                        parcel.readLong(); parcel.readInt(); parcel.readByte()
                        parcel.readByte()
                        null
                    }

                    val networkShare: NetworkShare? = if (!isLocal) {
                        val nsId = parcel.readString() ?: ""
                        val nsName = parcel.readString() ?: ""
                        val nsType = ShareType.valueOf(parcel.readString() ?: "SMB")
                        val nsHost = parcel.readString() ?: ""
                        val nsPort = parcel.readInt()
                        val nsUsername = parcel.readString() ?: ""
                        val nsPassword = parcel.readString() ?: ""
                        val nsDomain = parcel.readString() ?: ""
                        val nsRemotePath = parcel.readString() ?: ""
                        val nsReadOnly = parcel.readByte() != 0.toByte()
                        val nsPrivateKeyPath = parcel.readString()
                        val nsUseKeychain = parcel.readByte() != 0.toByte()
                        val nsSmbProtocol = parcel.readString() ?: "AUTO"
                        val nsCredsStripped = parcel.readByte() != 0.toByte()
                        NetworkShare(
                            id = nsId,
                            name = nsName,
                            type = nsType,
                            host = nsHost,
                            port = nsPort,
                            username = nsUsername,
                            password = nsPassword,
                            domain = nsDomain,
                            remotePath = nsRemotePath,
                            readOnly = nsReadOnly,
                            privateKeyPath = nsPrivateKeyPath,
                            useKeychain = nsUseKeychain,
                            smbProtocol = nsSmbProtocol,
                            isCredentialsStripped = nsCredsStripped
                        )
                    } else {
                        // skip share fields
                        parcel.readString(); parcel.readString(); parcel.readString()
                        parcel.readString(); parcel.readInt(); parcel.readString()
                        parcel.readString(); parcel.readString(); parcel.readString()
                        parcel.readByte(); parcel.readString(); parcel.readByte()
                        parcel.readString(); parcel.readByte()
                        null
                    }

                    return BatchRenameItem(
                        name = name,
                        extension = extension,
                        isDirectory = isDirectory,
                        isLocal = isLocal,
                        localFile = localFile,
                        networkFile = networkFile,
                        networkShare = networkShare
                    )
                }

                override fun newArray(size: Int): Array<BatchRenameItem?> = arrayOfNulls(size)
            }
    }

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(name)
        parcel.writeString(extension)
        parcel.writeByte(if (isDirectory) 1 else 0)
        parcel.writeByte(if (isLocal) 1 else 0)

        if (isLocal) {
            parcel.writeString(localFile?.absolutePath ?: "")
        } else {
            parcel.writeString("")
        }

        if (!isLocal && networkFile != null) {
            parcel.writeString(networkFile.name)
            parcel.writeString(networkFile.path)
            parcel.writeByte(if (networkFile.isDirectory) 1 else 0)
            parcel.writeLong(networkFile.size)
            parcel.writeLong(networkFile.lastModified)
            parcel.writeLong(networkFile.freeSpace)
            parcel.writeInt(networkFile.iconRes)
            parcel.writeByte(if (networkFile.isToggle) 1 else 0)
            parcel.writeByte(if (networkFile.isToggled) 1 else 0)
        } else {
            parcel.writeString(""); parcel.writeString("")
            parcel.writeByte(0); parcel.writeLong(0); parcel.writeLong(0)
            parcel.writeLong(0); parcel.writeInt(0); parcel.writeByte(0)
            parcel.writeByte(0)
        }

        if (!isLocal && networkShare != null) {
            parcel.writeString(networkShare.id)
            parcel.writeString(networkShare.name)
            parcel.writeString(networkShare.type.name)
            parcel.writeString(networkShare.host)
            parcel.writeInt(networkShare.port)
            parcel.writeString(networkShare.username)
            parcel.writeString(networkShare.password)
            parcel.writeString(networkShare.domain)
            parcel.writeString(networkShare.remotePath)
            parcel.writeByte(if (networkShare.readOnly) 1 else 0)
            parcel.writeString(networkShare.privateKeyPath)
            parcel.writeByte(if (networkShare.useKeychain) 1 else 0)
            parcel.writeString(networkShare.smbProtocol)
            parcel.writeByte(if (networkShare.isCredentialsStripped) 1 else 0)
        } else {
            parcel.writeString(""); parcel.writeString(""); parcel.writeString("")
            parcel.writeString(""); parcel.writeInt(0); parcel.writeString("")
            parcel.writeString(""); parcel.writeString(""); parcel.writeString("")
            parcel.writeByte(0); parcel.writeString(null); parcel.writeByte(0)
            parcel.writeString(""); parcel.writeByte(0)
        }
    }

    override fun describeContents(): Int = 0
}
