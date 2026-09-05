package za.kilowatch.ultimatefilemanager.checksum

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Generates standard GNU coreutils-compatible checksum manifest text.
 */
object ChecksumManifestWriter {

    data class FileHashRecord(
        val filename: String,
        val hash: String
    )

    /**
     * Builds GNU coreutils manifest format: `<hash>  <filename>\n`
     */
    fun createManifestContent(records: List<FileHashRecord>): String {
        val sb = StringBuilder()
        for (r in records) {
            sb.append(r.hash.lowercase(Locale.ROOT))
            sb.append("  ") // Standard GNU two-space separator
            sb.append(r.filename)
            sb.append("\n")
        }
        return sb.toString()
    }

    /**
     * Suggests a standard default filename, e.g. `checksums_sha256_20260905_194500.sha256`
     */
    fun generateDefaultManifestFilename(algorithm: HashAlgorithm): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())
        val algoTag = algorithm.name.lowercase(Locale.ROOT)
        return "checksums_${algoTag}_$timestamp.${algorithm.fileExtension}"
    }
}
