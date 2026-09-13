package za.kilowatch.ultimatefilemanager.viewer

import java.io.File

/**
 * Static delegate that allows the text viewer to trigger an upload back
 * to a network share after saving a file locally.
 *
 * The caller ([NetworkBrowserActivity] / [NetworkBrowserFragment]) sets
 * [onFileSaved] before launching [TextViewerActivity] so that when the
 * user saves, the content is also written to the remote location.
 */
object NetworkSaveBridge {

    /**
     * Called by [TextViewerActivity] or [MusicTaggerActivity] after a local file save completes.
     * Implementations should upload [savedFile] to the appropriate
     * network share / online storage provider.
     */
    var onFileSaved: ((savedFile: File) -> Unit)? = null

    /**
     * Called by [MusicTaggerActivity] after a file is renamed locally.
     * Implementations should rename the file in the remote location.
     */
    var onFileRenamed: ((oldFile: File, newFile: File) -> Unit)? = null
}

