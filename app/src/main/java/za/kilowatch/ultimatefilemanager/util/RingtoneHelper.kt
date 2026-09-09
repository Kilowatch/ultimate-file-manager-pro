package za.kilowatch.ultimatefilemanager.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.webkit.MimeTypeMap
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import za.kilowatch.ultimatefilemanager.R
import java.io.File
import java.io.FileInputStream

object RingtoneHelper {

    private const val TAG = "RingtoneHelper"

    /**
     * Checks if the app has permission to modify system settings (required to set default ringtones).
     */
    fun canWriteSettings(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.System.canWrite(context)
        } else {
            true
        }
    }

    /**
     * Opens system settings screen to allow user to grant WRITE_SETTINGS permission.
     */
    fun requestWriteSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                try {
                    val fallback = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(fallback)
                } catch (e2: Exception) {
                    Log.e(TAG, "Failed to launch ACTION_MANAGE_WRITE_SETTINGS", e2)
                }
            }
        }
    }

    /**
     * Shows a confirmation dialog informing the user before applying a system sound.
     */
    fun showConfirmDialog(
        context: Context,
        fileName: String,
        type: Int,
        onConfirmed: () -> Unit
    ) {
        val iconRes = when (type) {
            RingtoneManager.TYPE_RINGTONE -> R.drawable.ic_ringtone
            RingtoneManager.TYPE_NOTIFICATION -> R.drawable.ic_notification_sound
            RingtoneManager.TYPE_ALARM -> R.drawable.ic_alarm_sound
            else -> R.drawable.ic_ringtone
        }
        val messageRes = when (type) {
            RingtoneManager.TYPE_RINGTONE -> R.string.ringtone_confirm_ringtone_message
            RingtoneManager.TYPE_NOTIFICATION -> R.string.ringtone_confirm_notification_message
            RingtoneManager.TYPE_ALARM -> R.string.ringtone_confirm_alarm_message
            else -> R.string.ringtone_confirm_ringtone_message
        }
        val btnTextRes = when (type) {
            RingtoneManager.TYPE_RINGTONE -> R.string.action_set_ringtone
            RingtoneManager.TYPE_NOTIFICATION -> R.string.action_set_notification
            RingtoneManager.TYPE_ALARM -> R.string.action_set_alarm
            else -> R.string.action_set_ringtone
        }
        val isTv = DeviceUtils.isTvDevice(context)

        val layoutRes = if (isTv) R.layout.dialog_support_message_tv else R.layout.dialog_support_message
        val dialogView = android.view.LayoutInflater.from(context).inflate(layoutRes, null)
        val imgIcon = dialogView.findViewById<android.widget.ImageView>(R.id.imgDialogIcon)
        val txtTitle = dialogView.findViewById<android.widget.TextView>(R.id.txtDialogTitle)
        val txtMessage = dialogView.findViewById<android.widget.TextView>(R.id.txtDialogMessage)
        val btnPositive = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDialogPositive)
        val btnNegative = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDialogNegative)

        imgIcon?.setImageResource(iconRes)
        txtTitle?.setText(R.string.ringtone_confirm_title)
        txtMessage?.text = context.getString(messageRes, fileName)
        btnPositive?.setText(btnTextRes)
        btnNegative?.visibility = android.view.View.VISIBLE
        btnNegative?.setText(android.R.string.cancel)

        val dialog = MaterialAlertDialogBuilder(context, R.style.UFM_Dialog)
            .setView(dialogView)
            .create()

        btnPositive?.setOnClickListener {
            dialog.dismiss()
            onConfirmed()
        }

        btnNegative?.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
    }

    /**
     * Sets the specified audio file as a system sound (Ringtone, Notification, or Alarm).
     * Inserts/copies the file into MediaStore with appropriate flags so it also appears
     * in system sound pickers (and 3rd-party apps like WhatsApp and Telegram).
     *
     * @param context Context
     * @param file Local or SAF audio File
     * @param type RingtoneManager.TYPE_RINGTONE, TYPE_NOTIFICATION, or TYPE_ALARM
     * @return true if sound was successfully applied, false on failure
     */
    fun setAsSystemSound(context: Context, file: File, type: Int): Boolean {
        if (!canWriteSettings(context)) return false

        val isSaf = file is za.kilowatch.ultimatefilemanager.storage.SafFile ||
                    za.kilowatch.ultimatefilemanager.storage.SafTreeManager.isSafPath(file.absolutePath) ||
                    za.kilowatch.ultimatefilemanager.storage.SafTreeManager.hasTreePermissionForPath(context, file.absolutePath)
        val exists = if (isSaf) za.kilowatch.ultimatefilemanager.storage.SafTreeManager.exists(context, file.absolutePath) else (file.exists() && file.isFile)
        if (!exists) return false

        val resolver = context.contentResolver
        val ext = file.extension.lowercase()
        val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: when (ext) {
                "mp3" -> "audio/mpeg"
                "ogg", "opus" -> "audio/ogg"
                "wav" -> "audio/wav"
                "m4a", "aac" -> "audio/mp4"
                "flac" -> "audio/flac"
                else -> "audio/*"
            }

        val targetDirName = when (type) {
            RingtoneManager.TYPE_RINGTONE -> Environment.DIRECTORY_RINGTONES
            RingtoneManager.TYPE_NOTIFICATION -> Environment.DIRECTORY_NOTIFICATIONS
            RingtoneManager.TYPE_ALARM -> Environment.DIRECTORY_ALARMS
            else -> Environment.DIRECTORY_RINGTONES
        }

        return try {
            val finalUri: Uri?
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                    put(MediaStore.MediaColumns.TITLE, file.nameWithoutExtension)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, targetDirName)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                    when (type) {
                        RingtoneManager.TYPE_RINGTONE -> put(MediaStore.Audio.AudioColumns.IS_RINGTONE, true)
                        RingtoneManager.TYPE_NOTIFICATION -> put(MediaStore.Audio.AudioColumns.IS_NOTIFICATION, true)
                        RingtoneManager.TYPE_ALARM -> put(MediaStore.Audio.AudioColumns.IS_ALARM, true)
                    }
                }
                val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: return false
                val inputStream = if (isSaf) {
                    za.kilowatch.ultimatefilemanager.storage.SafTreeManager.openInputStream(context, file.absolutePath)
                } else {
                    FileInputStream(file)
                } ?: return false

                inputStream.use { input ->
                    resolver.openOutputStream(uri)?.use { output ->
                        input.copyTo(output)
                    } ?: return false
                }

                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                finalUri = uri
            } else {
                // Pre-Q: copy to external storage public directory and insert into MediaStore
                @Suppress("DEPRECATION")
                val publicDir = Environment.getExternalStoragePublicDirectory(targetDirName)
                if (!publicDir.exists()) publicDir.mkdirs()
                val destFile = File(publicDir, file.name)

                val inputStream = if (isSaf) {
                    za.kilowatch.ultimatefilemanager.storage.SafTreeManager.openInputStream(context, file.absolutePath)
                } else {
                    FileInputStream(file)
                } ?: return false

                inputStream.use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                @Suppress("DEPRECATION")
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DATA, destFile.absolutePath)
                    put(MediaStore.MediaColumns.TITLE, destFile.nameWithoutExtension)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    when (type) {
                        RingtoneManager.TYPE_RINGTONE -> put(MediaStore.Audio.AudioColumns.IS_RINGTONE, true)
                        RingtoneManager.TYPE_NOTIFICATION -> put(MediaStore.Audio.AudioColumns.IS_NOTIFICATION, true)
                        RingtoneManager.TYPE_ALARM -> put(MediaStore.Audio.AudioColumns.IS_ALARM, true)
                    }
                }
                finalUri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                    ?: Uri.fromFile(destFile)
                MediaScannerNotifier.scanFile(context, destFile)
            }

            if (finalUri != null) {
                RingtoneManager.setActualDefaultRingtoneUri(context, type, finalUri)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting system sound", e)
            false
        }
    }
}
