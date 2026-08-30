// ── PDF Generation Logic (PDFBox) ──────────────────────────────
package za.kilowatch.ultimatefilemanager.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import java.io.File

/**
 * Service to handle PDF conversion using PDFBox-Android.
 *
 * - Decodes image with inSampleSize so the longest side never exceeds MAX_DIM,
 *   preventing the "Canvas: trying to draw too large bitmap" crash on large photos.
 * - Reads EXIF orientation and rotates the bitmap accordingly so the PDF is
 *   always upright regardless of how the camera stored the file.
 * - Encodes with JPEGFactory at 85% quality — much faster than LosslessFactory.
 */
class PdfConverter(private val context: Context) {

    companion object {
        /** Maximum pixel dimension (width or height) for the decoded bitmap. */
        private const val MAX_DIM = 2000
    }

    init {
        PDFBoxResourceLoader.init(context)
    }

    private fun openInput(path: String): java.io.InputStream? {
        return if (za.kilowatch.ultimatefilemanager.storage.SafTreeManager.isSaf(context, path)) {
            za.kilowatch.ultimatefilemanager.storage.SafTreeManager.openInputStream(context, path)
        } else {
            try { File(path).inputStream() } catch (_: Exception) { null }
        }
    }

    private fun openOutput(path: String): java.io.OutputStream? {
        return if (za.kilowatch.ultimatefilemanager.storage.SafTreeManager.isSaf(context, path)) {
            za.kilowatch.ultimatefilemanager.storage.SafTreeManager.openOutputStream(context, path)
        } else {
            try { File(path).outputStream() } catch (_: Exception) { null }
        }
    }

    fun convertImageToPdf(imageFile: File, outputFile: File, password: String?): Boolean {
        return convertImageToPdf(imageFile.absolutePath, outputFile.absolutePath, password)
    }

    fun convertImageToPdf(sourcePath: String, outputPath: String, password: String?): Boolean {
        return try {
            // ── Step 1: measure raw dimensions without allocating the full bitmap ──
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            openInput(sourcePath)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, boundsOpts)
            }
            val rawWidth  = boundsOpts.outWidth
            val rawHeight = boundsOpts.outHeight
            if (rawWidth <= 0 || rawHeight <= 0) return false

            // ── Step 2: calculate inSampleSize to cap longest side at MAX_DIM ──────
            var inSampleSize = 1
            while (rawWidth / inSampleSize > MAX_DIM || rawHeight / inSampleSize > MAX_DIM) {
                inSampleSize *= 2
            }

            // ── Step 3: decode downsampled bitmap ────────────────────────────────
            val ext = sourcePath.substringAfterLast('.', "").lowercase()
            val raw: Bitmap = if (ext == "jxl") {
                // BitmapFactory cannot decode JXL — use JxlCoder.decodeSampled()
                try {
                    val bytes = openInput(sourcePath)?.use { it.readBytes() } ?: return false
                    com.awxkee.jxlcoder.JxlCoder.decodeSampled(bytes, MAX_DIM, MAX_DIM)
                } catch (_: Exception) { return false }
            } else {
                val decodeOpts = BitmapFactory.Options().apply { this.inSampleSize = inSampleSize }
                openInput(sourcePath)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, decodeOpts)
                } ?: return false
            }

            // ── Step 4: apply EXIF orientation so the PDF is always upright ──────
            val rotation = readExifRotation(sourcePath)
            val bitmap = if (rotation != 0) {
                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
                    .also { raw.recycle() }
            } else {
                raw
            }

            // ── Step 5: encode as JPEG inside the PDF document ───────────────────
            val document   = PDDocument()
            val image      = JPEGFactory.createFromImage(document, bitmap, 0.85f)
            bitmap.recycle()

            // ── Step 6: create page sized to the (possibly rotated) image ────────
            val pageWidth  = image.width.toFloat()
            val pageHeight = image.height.toFloat()
            val page       = PDPage(PDRectangle(pageWidth, pageHeight))
            document.addPage(page)

            // ── Step 7: draw, encrypt (optional), save ───────────────────────────
            val contentStream = PDPageContentStream(document, page)
            contentStream.drawImage(image, 0f, 0f, pageWidth, pageHeight)
            contentStream.close()

            if (!password.isNullOrEmpty()) {
                val ap  = AccessPermission()
                val spp = StandardProtectionPolicy(password, password, ap)
                spp.setEncryptionKeyLength(256)
                document.protect(spp)
            }

            val out = openOutput(outputPath) ?: return false
            out.use { stream ->
                document.save(stream)
            }
            document.close()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Returns the clockwise rotation in degrees encoded in the image's EXIF data,
     * or 0 if the orientation is normal / unreadable.
     * Uses [android.media.ExifInterface] (built-in, no extra dependency, minSdk 26+).
     */
    private fun readExifRotation(sourcePath: String): Int {
        return try {
            val exif = if (za.kilowatch.ultimatefilemanager.storage.SafTreeManager.isSaf(context, sourcePath)) {
                openInput(sourcePath)?.use { ExifInterface(it) } ?: return 0
            } else {
                ExifInterface(sourcePath)
            }
            when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90  -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else                                 -> 0
            }
        } catch (e: Exception) {
            0
        }
    }

    fun convertTextToPdf(textFile: File, outputFile: File, password: String?): Boolean {
        return convertTextToPdf(textFile.absolutePath, outputFile.absolutePath, password)
    }

    fun convertTextToPdf(sourcePath: String, outputPath: String, password: String?): Boolean {
        return try {
            PDFBoxResourceLoader.init(context)
            val content = openInput(sourcePath)?.use { stream ->
                try {
                    stream.bufferedReader(Charsets.UTF_8).readText()
                } catch (_: Exception) {
                    openInput(sourcePath)?.use { s2 -> s2.bufferedReader(Charsets.ISO_8859_1).readText() } ?: ""
                }
            } ?: return false

            val document = PDDocument()
            val font     = PDType1Font.HELVETICA
            val fontSize = 10f
            val leading  = fontSize * 1.4f

            // A4 page in points
            val pageWidth  = PDRectangle.A4.width
            val pageHeight = PDRectangle.A4.height
            val marginX    = 40f
            val marginTop  = 40f
            val marginBot  = 40f
            val usableWidth = pageWidth - 2 * marginX

            // Approximate chars per line (Helvetica 10pt â‰ˆ 5.6 pts/char average)
            val charsPerLine = (usableWidth / (fontSize * 0.56f)).toInt().coerceAtLeast(40)

            // Word-wrap all lines
            val wrappedLines = mutableListOf<String>()
            for (rawLine in content.lines()) {
                if (rawLine.isEmpty()) { wrappedLines.add(""); continue }
                var remaining = rawLine
                while (remaining.length > charsPerLine) {
                    val breakAt = remaining.lastIndexOf(' ', charsPerLine).let {
                        if (it <= 0) charsPerLine else it
                    }
                    wrappedLines.add(remaining.substring(0, breakAt))
                    remaining = remaining.substring(breakAt).trimStart()
                }
                wrappedLines.add(remaining)
            }

            // Render lines across pages
            val linesPerPage = ((pageHeight - marginTop - marginBot) / leading).toInt()
            var lineIndex = 0

            while (lineIndex < wrappedLines.size) {
                val page = PDPage(PDRectangle.A4)
                document.addPage(page)
                val cs = PDPageContentStream(document, page)
                cs.beginText()
                cs.setFont(font, fontSize)
                cs.setLeading(leading)
                cs.newLineAtOffset(marginX, pageHeight - marginTop - fontSize)

                var linesOnPage = 0
                while (lineIndex < wrappedLines.size && linesOnPage < linesPerPage) {
                    var line = wrappedLines[lineIndex]
                        .replace("\\", "\\\\")
                        .replace("(", "\\(")
                        .replace(")", "\\)")
                    // Replace characters outside WinAnsiEncoding (emoji, etc.) with a space
                    line = line.map { if (it.code in 32..126 || it.code in 160..255) it else ' ' }.joinToString("")
                    cs.showText(line)
                    cs.newLine()
                    lineIndex++
                    linesOnPage++
                }

                cs.endText()
                cs.close()
            }

            // Handle empty file edge case
            if (document.numberOfPages == 0) document.addPage(PDPage(PDRectangle.A4))

            if (!password.isNullOrEmpty()) {
                val ap  = AccessPermission()
                val spp = StandardProtectionPolicy(password, password, ap)
                spp.setEncryptionKeyLength(256)
                document.protect(spp)
            }

            val out = openOutput(outputPath) ?: return false
            out.use { stream ->
                document.save(stream)
            }
            document.close()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
