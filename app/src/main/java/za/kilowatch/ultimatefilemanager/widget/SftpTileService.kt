package za.kilowatch.ultimatefilemanager.widget

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.server.FileServerService

@RequiresApi(Build.VERSION_CODES.N)
class SftpTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val isEnabled = FileServerService.isSftpEnabled(this)
        if (isEnabled) {
            FileServerService.stopSftp(this)
        } else {
            FileServerService.startSftp(this)
        }
        FileServerService.setSftpEnabled(this, !isEnabled)
        FileServerService.refreshNotification(this)
        updateTileState()
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val isEnabled = FileServerService.isSftpEnabled(this)

        if (isEnabled) {
            tile.state = Tile.STATE_ACTIVE
            tile.label = getString(R.string.widget_ftp_sftp_sftp_label)
            tile.subtitle = getString(R.string.widget_ftp_sftp_running)
        } else {
            tile.state = Tile.STATE_INACTIVE
            tile.label = getString(R.string.widget_ftp_sftp_sftp_label)
            tile.subtitle = getString(R.string.widget_ftp_sftp_stopped)
        }
        tile.updateTile()
    }
}
