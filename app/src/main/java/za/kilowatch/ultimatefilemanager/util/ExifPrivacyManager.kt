package za.kilowatch.ultimatefilemanager.util

import android.content.Context
import androidx.exifinterface.media.ExifInterface
import za.kilowatch.ultimatefilemanager.R
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Data holder representing parsed EXIF details for a photo.
 */
data class ExifMetadataDetails(
    val file: File,
    val hasGps: Boolean,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitude: Double? = null,
    val cameraMake: String? = null,
    val cameraModel: String? = null,
    val lensModel: String? = null,
    val focalLength: String? = null,
    val aperture: String? = null,
    val shutterSpeed: String? = null,
    val iso: String? = null,
    val flash: String? = null,
    val whiteBalance: String? = null,
    val dateTaken: String? = null,
    val dateDigitized: String? = null,
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
    val software: String? = null,
    val serialNumber: String? = null,
    val artist: String? = null,
    val copyright: String? = null,
    val userComment: String? = null,
    val orientation: Int = ExifInterface.ORIENTATION_NORMAL
) {
    val formattedCoordinates: String?
        get() = if (latitude != null && longitude != null) {
            String.format(Locale.US, "%.5f, %.5f", latitude, longitude)
        } else null

    val formattedDimensions: String
        get() = if (imageWidth > 0 && imageHeight > 0) {
            val mp = (imageWidth * imageHeight) / 1_000_000.0
            "${imageWidth} × ${imageHeight} (${String.format(Locale.US, "%.1f", mp)} MP)"
        } else {
            "—"
        }
}

/**
 * Configuration options for selective EXIF stripping.
 */
data class ExifPrivacyOptions(
    val stripGps: Boolean = true,
    val stripDevice: Boolean = true,
    val stripAuthor: Boolean = true,
    val stripDates: Boolean = false,
    val stripCameraSettings: Boolean = false
)

/**
 * Manager class for reading, inspecting, and scrubbing EXIF metadata.
 */
object ExifPrivacyManager {

    private val GPS_ATTRIBUTES = arrayOf(
        ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP,
        ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_PROCESSING_METHOD,
        ExifInterface.TAG_GPS_AREA_INFORMATION,
        ExifInterface.TAG_GPS_DEST_BEARING,
        ExifInterface.TAG_GPS_DEST_BEARING_REF,
        ExifInterface.TAG_GPS_DEST_DISTANCE,
        ExifInterface.TAG_GPS_DEST_DISTANCE_REF,
        ExifInterface.TAG_GPS_SPEED,
        ExifInterface.TAG_GPS_SPEED_REF,
        ExifInterface.TAG_GPS_TRACK,
        ExifInterface.TAG_GPS_TRACK_REF,
        ExifInterface.TAG_GPS_IMG_DIRECTION,
        ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
        ExifInterface.TAG_GPS_MAP_DATUM,
        ExifInterface.TAG_GPS_VERSION_ID
    )

    private val DEVICE_ATTRIBUTES = arrayOf(
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_SOFTWARE,
        ExifInterface.TAG_BODY_SERIAL_NUMBER,
        ExifInterface.TAG_CAMERA_OWNER_NAME,
        ExifInterface.TAG_LENS_MAKE,
        ExifInterface.TAG_LENS_MODEL,
        ExifInterface.TAG_LENS_SERIAL_NUMBER,
        ExifInterface.TAG_LENS_SPECIFICATION,
        ExifInterface.TAG_DEVICE_SETTING_DESCRIPTION
    )

    private val AUTHOR_ATTRIBUTES = arrayOf(
        ExifInterface.TAG_ARTIST,
        ExifInterface.TAG_COPYRIGHT,
        ExifInterface.TAG_USER_COMMENT,
        ExifInterface.TAG_IMAGE_DESCRIPTION,
        "XPTitle",
        "XPComment",
        "XPAuthor",
        "XPKeywords",
        "XPSubject"
    )

    private val DATE_ATTRIBUTES = arrayOf(
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME,
        ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
        ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
        ExifInterface.TAG_SUBSEC_TIME,
        ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
        ExifInterface.TAG_SUBSEC_TIME_DIGITIZED
    )

    private val CAMERA_SETTINGS_ATTRIBUTES = arrayOf(
        ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
        ExifInterface.TAG_FLASH,
        ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_METERING_MODE,
        ExifInterface.TAG_EXPOSURE_PROGRAM,
        ExifInterface.TAG_EXPOSURE_MODE,
        ExifInterface.TAG_SCENE_CAPTURE_TYPE,
        ExifInterface.TAG_LIGHT_SOURCE
    )

    /**
     * Read complete EXIF metadata details from a photo file.
     */
    fun readFullDetails(file: File): ExifMetadataDetails {
        return try {
            val exif = ExifInterface(file.absolutePath)

            val latLong = exif.latLong
            val hasGps = latLong != null
            val lat = latLong?.get(0)
            val lng = latLong?.get(1)
            val alt = exif.getAltitude(0.0).takeIf { hasGps && it != 0.0 }

            val make = exif.getAttribute(ExifInterface.TAG_MAKE)?.trim()
            val model = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim()
            val lens = exif.getAttribute(ExifInterface.TAG_LENS_MODEL)?.trim()
            
            val focal = exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0).takeIf { it > 0 }?.let { "${it}mm" }
            val fNum = exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0).takeIf { it > 0 }?.let { "f/$it" }
            val expTime = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0).takeIf { it > 0 }?.let { formatShutter(it) }
            val iso = exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)

            val flash = formatFlash(exif.getAttributeInt(ExifInterface.TAG_FLASH, -1))
            val wb = formatWhiteBalance(exif.getAttributeInt(ExifInterface.TAG_WHITE_BALANCE, -1))

            val dateTaken = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
            val dateDigitized = exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED)

            var width = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
            var height = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
            val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)

            if (width == 0 || height == 0) {
                val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(file.absolutePath, opts)
                width = opts.outWidth
                height = opts.outHeight
            }

            val software = exif.getAttribute(ExifInterface.TAG_SOFTWARE)?.trim()
            val serial = exif.getAttribute(ExifInterface.TAG_BODY_SERIAL_NUMBER)?.trim()
            val artist = exif.getAttribute(ExifInterface.TAG_ARTIST)?.trim()
            val copyright = exif.getAttribute(ExifInterface.TAG_COPYRIGHT)?.trim()
            val comment = exif.getAttribute(ExifInterface.TAG_USER_COMMENT)?.trim()

            ExifMetadataDetails(
                file = file,
                hasGps = hasGps,
                latitude = lat,
                longitude = lng,
                altitude = alt,
                cameraMake = make,
                cameraModel = model,
                lensModel = lens,
                focalLength = focal,
                aperture = fNum,
                shutterSpeed = expTime,
                iso = iso,
                flash = flash,
                whiteBalance = wb,
                dateTaken = dateTaken,
                dateDigitized = dateDigitized,
                imageWidth = width,
                imageHeight = height,
                software = software,
                serialNumber = serial,
                artist = artist,
                copyright = copyright,
                userComment = comment,
                orientation = orientation
            )
        } catch (e: Exception) {
            ExifMetadataDetails(file = file, hasGps = false)
        }
    }

    /**
     * Remove GPS geotags only from a single file in-place.
     */
    fun removeGpsOnly(file: File): Boolean {
        return try {
            val exif = ExifInterface(file.absolutePath)
            for (attr in GPS_ATTRIBUTES) {
                exif.setAttribute(attr, null)
            }
            exif.saveAttributes()
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Strip metadata from source file according to privacy options.
     * Can write in-place if destination equals source, or create a cleaned copy.
     */
    fun stripMetadata(sourceFile: File, destinationFile: File, options: ExifPrivacyOptions): Boolean {
        return try {
            val target = if (sourceFile.absolutePath == destinationFile.absolutePath) {
                sourceFile
            } else {
                sourceFile.copyTo(destinationFile, overwrite = true)
            }

            val exif = ExifInterface(target.absolutePath)
            val currentOrientation = exif.getAttribute(ExifInterface.TAG_ORIENTATION)

            if (options.stripGps) {
                for (attr in GPS_ATTRIBUTES) exif.setAttribute(attr, null)
            }
            if (options.stripDevice) {
                for (attr in DEVICE_ATTRIBUTES) exif.setAttribute(attr, null)
            }
            if (options.stripAuthor) {
                for (attr in AUTHOR_ATTRIBUTES) exif.setAttribute(attr, null)
            }
            if (options.stripDates) {
                for (attr in DATE_ATTRIBUTES) exif.setAttribute(attr, null)
            }
            if (options.stripCameraSettings) {
                for (attr in CAMERA_SETTINGS_ATTRIBUTES) exif.setAttribute(attr, null)
            }

            // Always preserve display orientation tag so the photo does not rotate
            if (currentOrientation != null) {
                exif.setAttribute(ExifInterface.TAG_ORIENTATION, currentOrientation)
            }

            exif.saveAttributes()
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Batch clean a list of files with progress reporting.
     */
    fun batchStripMetadata(
        files: List<File>,
        options: ExifPrivacyOptions,
        outputDir: File?,
        onProgress: (Int, Int) -> Unit
    ): Pair<Int, List<String>> {
        var successCount = 0
        val errors = mutableListOf<String>()

        files.forEachIndexed { index, file ->
            onProgress(index + 1, files.size)
            try {
                val destFile = if (outputDir == null) {
                    file // In-place overwrite
                } else {
                    File(outputDir, file.name)
                }

                if (stripMetadata(file, destFile, options)) {
                    successCount++
                } else {
                    errors.add(file.name)
                }
            } catch (e: Exception) {
                errors.add("${file.name}: ${e.message}")
            }
        }

        return Pair(successCount, errors)
    }

    private fun formatShutter(seconds: Double): String {
        return if (seconds >= 1.0) {
            String.format(Locale.US, "%.1fs", seconds)
        } else if (seconds > 0) {
            val fraction = (1.0 / seconds).toInt()
            "1/${fraction}s"
        } else {
            "—"
        }
    }

    private fun formatFlash(flashCode: Int): String? {
        if (flashCode < 0) return null
        return if ((flashCode and 1) != 0) "Fired" else "Off"
    }

    private fun formatWhiteBalance(wbCode: Int): String? {
        return when (wbCode) {
            ExifInterface.WHITE_BALANCE_AUTO.toInt() -> "Auto"
            ExifInterface.WHITE_BALANCE_MANUAL.toInt() -> "Manual"
            else -> null
        }
    }
}
