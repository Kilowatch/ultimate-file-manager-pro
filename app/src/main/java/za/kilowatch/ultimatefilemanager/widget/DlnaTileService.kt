package za.kilowatch.ultimatefilemanager.widget

import android.annotation.SuppressLint
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.server.DlnaServerPrefs
import za.kilowatch.ultimatefilemanager.server.FileServerService

/**
 * Quick Settings tile to toggle the DLNA Media Server on/off.
 *
 * Mirrors the [FtpTileService] pattern for consistency.
 * Shows STATE_ACTIVE when the DLNA server is running and STATE_INACTIVE when stopped.
 * If no shared folders are configured, the tile refuses to activate and shows
 * the inactive state.
 */
@RequiresApi(Build.VERSION_CODES.N)
class DlnaTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState(DlnaServerPrefs.isDlnaServerEnabled(this))
    }

    override fun onClick() {
        super.onClick()
        val enabled = DlnaServerPrefs.isDlnaServerEnabled(this)

        if (enabled) {
            FileServerService.stopDlna(this)
            updateTileState(false)
        } else {
            val folders = DlnaServerPrefs.getSharedFolders(this)
            if (folders.isEmpty()) {
                // Can't enable without at least one shared folder
                updateTileState(false)
                return
            }
            FileServerService.startDlna(this)
            updateTileState(true)
        }
    }

    @SuppressLint("NewApi")
    private fun updateTileState(active: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.settings_dlna_tile_title)
        tile.subtitle = if (active) {
            getString(R.string.widget_ftp_sftp_running)
        } else {
            getString(R.string.widget_ftp_sftp_stopped)
        }
        tile.updateTile()
    }
}
