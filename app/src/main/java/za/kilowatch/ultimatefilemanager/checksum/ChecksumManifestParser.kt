package za.kilowatch.ultimatefilemanager.checksum

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.Locale

/**
 * Robust parser for checksum manifest files.
 * Supports GNU coreutils standard/binary formats, BSD style, and SFV.
 */
object ChecksumManifestParser {

    data class ManifestEntry(
        val filename: String,
        val expectedHash: String,
        val algorithm: HashAlgorithm
    )

    private val BSD_REGEX = Regex("^(SHA256|SHA512|SHA1|MD5|CRC32)\\s*\\((.+)\\)\\s*=\\s*([0-9a-fA-F]+)$", RegexOption.IGNORE_CASE)

    fun parse(inputStream: InputStream, fallbackAlgorithm: HashAlgorithm? = null): List<ManifestEntry> {
        val entries = mutableListOf<ManifestEntry>()
        val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))

        reader.useLines { lines ->
            for (rawLine in lines) {
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) {
                    continue
                }

                // 1. BSD format: ALGO (file) = hash
                val bsdMatch = BSD_REGEX.find(line)
                if (bsdMatch != null) {
                    val algoName = bsdMatch.groupValues[1]
                    val filename = bsdMatch.groupValues[2].trim()
                    val hash = bsdMatch.groupValues[3].trim().lowercase(Locale.ROOT)
                    val algo = HashAlgorithm.fromStandardName(algoName) ?: HashAlgorithm.detectAlgorithmFromHex(hash) ?: fallbackAlgorithm
                    if (algo != null) {
                        entries.add(ManifestEntry(filename, hash, algo))
                        continue
                    }
                }

                // 2. GNU coreutils format: <hash>  <filename> or <hash> *<filename>
                // Format has 2 spaces or 1 space + asterisk
                val gnuSplit = if (line.contains("  ")) {
                    val idx = line.indexOf("  ")
                    Pair(line.substring(0, idx).trim(), line.substring(idx + 2).trim())
                } else if (line.contains(" *")) {
                    val idx = line.indexOf(" *")
                    Pair(line.substring(0, idx).trim(), line.substring(idx + 2).trim())
                } else {
                    // Try splitting on first whitespace
                    val firstSpace = line.indexOf(' ')
                    if (firstSpace > 0) {
                        Pair(line.substring(0, firstSpace).trim(), line.substring(firstSpace + 1).trim())
                    } else {
                        null
                    }
                }

                if (gnuSplit != null) {
                    val first = gnuSplit.first
                    val second = gnuSplit.second

                    val detectedAlgo = HashAlgorithm.detectAlgorithmFromHex(first)
                    if (detectedAlgo != null) {
                        val cleanFilename = second.removePrefix("*").trim()
                        entries.add(ManifestEntry(cleanFilename, first.lowercase(Locale.ROOT), detectedAlgo))
                        continue
                    }

                    // 3. SFV style: <filename> <crc32> (last token is 8-hex CRC32)
                    val lastSpace = line.lastIndexOf(' ')
                    if (lastSpace > 0) {
                        val potentialHash = line.substring(lastSpace + 1).trim()
                        val potentialFile = line.substring(0, lastSpace).trim()
                        if (potentialHash.length == 8 && HashAlgorithm.detectAlgorithmFromHex(potentialHash) == HashAlgorithm.CRC32) {
                            entries.add(ManifestEntry(potentialFile, potentialHash.lowercase(Locale.ROOT), HashAlgorithm.CRC32))
                            continue
                        }
                    }
                }
            }
        }

        return entries
    }
}
