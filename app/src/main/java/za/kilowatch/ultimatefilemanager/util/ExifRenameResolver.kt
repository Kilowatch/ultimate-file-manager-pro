package za.kilowatch.ultimatefilemanager.util

import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Preview model for a photo batch renaming operation.
 */
data class ExifRenamePreviewItem(
    val originalFile: File,
    val originalName: String,
    val newName: String,
    val targetFile: File,
    val hasConflict: Boolean = false,
    val errorReason: String? = null
)

/**
 * Resolver for EXIF-based file renaming patterns and batch rename execution.
 */
object ExifRenameResolver {

    const val PRESET_DATE_ORIGINAL = "{YYYY}-{MM}-{DD}_{hh}-{mm}-{ss}_{ORIGINAL}"
    const val PRESET_DATE_ONLY = "{YYYY}{MM}{DD}_{hh}{mm}{ss}"
    const val PRESET_CAMERA_DATE = "{MODEL}_{YYYY}{MM}{DD}_{#}"

    private val EXIF_DATE_FORMAT = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
    private val ILLEGAL_CHARS_REGEX = Regex("""[\\/:*?"<>|]""")

    /**
     * Resolve a single file's new target name based on the specified pattern.
     */
    fun resolveFilename(file: File, pattern: String, sequenceNumber: Int): String {
        var date: Date? = null
        var make: String? = null
        var model: String? = null

        try {
            val exif = ExifInterface(file.absolutePath)
            val dateStr = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: exif.getAttribute(ExifInterface.TAG_DATETIME)

            if (!dateStr.isNullOrBlank()) {
                date = EXIF_DATE_FORMAT.parse(dateStr)
            }
            make = exif.getAttribute(ExifInterface.TAG_MAKE)?.trim()
            model = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim()
        } catch (_: Exception) {}

        // Fallback to file timestamp if EXIF date is unavailable
        val effectiveDate = date ?: Date(file.lastModified())
        val cal = Calendar.getInstance().apply { time = effectiveDate }

        val yyyy = cal.get(Calendar.YEAR).toString()
        val yy = (cal.get(Calendar.YEAR) % 100).toString().padStart(2, '0')
        val mm = (cal.get(Calendar.MONTH) + 1).toString().padStart(2, '0')
        val dd = cal.get(Calendar.DAY_OF_MONTH).toString().padStart(2, '0')
        val hh = cal.get(Calendar.HOUR_OF_DAY).toString().padStart(2, '0')
        val min = cal.get(Calendar.MINUTE).toString().padStart(2, '0')
        val ss = cal.get(Calendar.SECOND).toString().padStart(2, '0')

        val cleanMake = sanitizeToken(make ?: "Camera")
        val cleanModel = sanitizeToken(model ?: cleanMake)
        val originalBase = file.nameWithoutExtension
        val ext = file.extension

        var result = pattern
            .replace("{YYYY}", yyyy)
            .replace("{YY}", yy)
            .replace("{MM}", mm)
            .replace("{DD}", dd)
            .replace("{hh}", hh)
            .replace("{mm}", min)
            .replace("{ss}", ss)
            .replace("{CAMERA}", cleanMake)
            .replace("{MAKE}", cleanMake)
            .replace("{MODEL}", cleanModel)
            .replace("{ORIGINAL}", originalBase)
            .replace("{NAME}", originalBase)

        // Replace sequence numbers: {#}, {##}, {###}, etc.
        result = Regex("""\{(#+)\}""").replace(result) { match ->
            val padWidth = match.groupValues[1].length
            sequenceNumber.toString().padStart(padWidth, '0')
        }
        result = Regex("""#+""").replace(result) { match ->
            val padWidth = match.value.length
            sequenceNumber.toString().padStart(padWidth, '0')
        }

        // Clean any invalid filename characters
        result = ILLEGAL_CHARS_REGEX.replace(result, "_").trim()

        if (result.isEmpty()) {
            result = originalBase
        }

        return if (ext.isNotEmpty()) "$result.$ext" else result
    }

    /**
     * Generate preview items for all files, resolving name collisions.
     */
    fun generatePreview(files: List<File>, pattern: String, startSeq: Int = 1): List<ExifRenamePreviewItem> {
        val usedNames = mutableSetOf<String>()
        val resultList = mutableListOf<ExifRenamePreviewItem>()

        files.forEachIndexed { index, file ->
            val seq = startSeq + index
            var baseTargetName = resolveFilename(file, pattern, seq)
            val parent = file.parentFile ?: File("/")

            // If name collision within this batch or with existing other files
            var targetName = baseTargetName
            var collisionCounter = 1
            val ext = file.extension
            val nameWithoutExt = if (ext.isNotEmpty()) baseTargetName.removeSuffix(".$ext") else baseTargetName

            while (usedNames.contains(targetName.lowercase(Locale.ROOT)) ||
                (File(parent, targetName).exists() && File(parent, targetName).absolutePath != file.absolutePath)
            ) {
                targetName = if (ext.isNotEmpty()) {
                    "${nameWithoutExt}_$collisionCounter.$ext"
                } else {
                    "${nameWithoutExt}_$collisionCounter"
                }
                collisionCounter++
            }

            usedNames.add(targetName.lowercase(Locale.ROOT))
            val targetFile = File(parent, targetName)

            resultList.add(
                ExifRenamePreviewItem(
                    originalFile = file,
                    originalName = file.name,
                    newName = targetName,
                    targetFile = targetFile,
                    hasConflict = collisionCounter > 1
                )
            )
        }

        return resultList
    }

    /**
     * Execute batch renaming of preview items with progress callback.
     */
    fun executeBatchRename(
        items: List<ExifRenamePreviewItem>,
        onProgress: (Int, Int) -> Unit
    ): Pair<Int, List<String>> {
        var successCount = 0
        val errors = mutableListOf<String>()

        items.forEachIndexed { index, item ->
            onProgress(index + 1, items.size)
            if (item.originalFile.absolutePath == item.targetFile.absolutePath) {
                // Name didn't change
                successCount++
                return@forEachIndexed
            }

            try {
                if (item.originalFile.renameTo(item.targetFile)) {
                    successCount++
                } else {
                    errors.add("${item.originalName} → ${item.newName}")
                }
            } catch (e: Exception) {
                errors.add("${item.originalName}: ${e.message}")
            }
        }

        return Pair(successCount, errors)
    }

    private fun sanitizeToken(token: String): String {
        return token
            .replace(ILLEGAL_CHARS_REGEX, "_")
            .replace(" ", "_")
            .trim('_')
    }
}
