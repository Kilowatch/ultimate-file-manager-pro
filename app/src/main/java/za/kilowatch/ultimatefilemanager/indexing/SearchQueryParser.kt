package za.kilowatch.ultimatefilemanager.indexing

import java.util.Locale

/**
 * Parses a free-text search query into a structured [ParsedQuery].
 *
 * Supported filter tokens (can be mixed with plain text):
 *
 * | Token          | Example            | Effect                         |
 * |----------------|--------------------|--------------------------------|
 * | `size>N`       | `size>5MB`         | minSize filter                 |
 * | `size<N`       | `size<100KB`       | maxSize filter                 |
 * | `ext:X`        | `ext:pdf`          | extension = "pdf"              |
 * | `*.X`          | `*.pdf`            | extension = "pdf"              |
 * | `type:X`       | `type:video`       | mimeType prefix filter         |
 * | `date:Nd`      | `date:7d`          | lastModified >= now - N days   |
 * | `date:Ndays`   | `date:30days`      | same                           |
 * | `folder:NAME`  | `folder:downloads` | folderPath LIKE %/NAME%        |
 * | anything else  | `invoice`          | FTS/LIKE filename term         |
 *
 * **Filter tokens only apply to indexed storages.**
 */
object SearchQueryParser {

    private val SIZE_REGEX = Regex("""size([<>])(\d+\.?\d*)(mb|kb|gb|b)?""", RegexOption.IGNORE_CASE)
    private val EXT_REGEX  = Regex("""ext:(\w+)""", RegexOption.IGNORE_CASE)
    private val GLOB_EXT   = Regex("""\*\.(\w+)""")
    private val TYPE_REGEX = Regex("""type:(\w+)""", RegexOption.IGNORE_CASE)
    private val DATE_REGEX = Regex("""date:(\d+)d(?:ays)?""", RegexOption.IGNORE_CASE)
    private val FOLDER_REGEX = Regex("""folder:(\S+)""", RegexOption.IGNORE_CASE)

    /** Parse [raw] into a [ParsedQuery]. Never throws. */
    fun parse(raw: String): ParsedQuery {
        val input = raw.trim()
        if (input.isEmpty()) return ParsedQuery(ftsTerm = "")

        var remainder = input
        var minSize: Long? = null
        var maxSize: Long? = null
        var sinceDate: Long? = null
        var folderPrefix: String? = null

        // --- size filter ---
        SIZE_REGEX.find(remainder)?.let { m ->
            val op   = m.groupValues[1]
            val num  = m.groupValues[2].toDoubleOrNull() ?: 0.0
            val unit = m.groupValues[3].lowercase(Locale.ROOT)
            val bytes = (num * when (unit) {
                "kb" -> 1_024L
                "mb" -> 1_048_576L
                "gb" -> 1_073_741_824L
                else -> 1L
            }).toLong()
            if (op == ">") minSize = bytes else maxSize = bytes
            remainder = remainder.replace(m.value, " ")
        }

        // --- ext:pdf or *.pdf (collect ALL occurrences) ---
        val extensions = mutableListOf<String>()
        var extRemainder = remainder
        var extMatch = (EXT_REGEX.find(extRemainder) ?: GLOB_EXT.find(extRemainder))
        while (extMatch != null) {
            extensions.add(extMatch.groupValues[1].lowercase(Locale.ROOT))
            extRemainder = extRemainder.replace(extMatch.value, " ")
            extMatch = (EXT_REGEX.find(extRemainder) ?: GLOB_EXT.find(extRemainder))
        }
        remainder = extRemainder

        // --- type filter (collect ALL occurrences) ---
        val mimePrefixes = mutableListOf<String>()
        var typeRemainder = remainder
        var typeMatch = TYPE_REGEX.find(typeRemainder)
        while (typeMatch != null) {
            val prefix = when (typeMatch.groupValues[1].lowercase(Locale.ROOT)) {
                "image"    -> "image/%"
                "video"    -> "video/%"
                "audio"    -> "audio/%"
                "doc", "document", "text" -> "text/%"
                "apk"      -> "application/vnd.android.package-archive"
                "archive"  -> "application/zip"
                else       -> "${typeMatch.groupValues[1].lowercase(Locale.ROOT)}/%"
            }
            mimePrefixes.add(prefix)
            typeRemainder = typeRemainder.replace(typeMatch.value, " ")
            typeMatch = TYPE_REGEX.find(typeRemainder)
        }
        remainder = typeRemainder

        // --- date filter ---
        DATE_REGEX.find(remainder)?.let { m ->
            val days = m.groupValues[1].toLongOrNull() ?: 7L
            sinceDate = System.currentTimeMillis() - (days * 86_400_000L)
            remainder = remainder.replace(m.value, " ")
        }

        // --- folder filter ---
        FOLDER_REGEX.find(remainder)?.let { m ->
            folderPrefix = "%/${m.groupValues[1].lowercase(Locale.ROOT)}%"
            remainder = remainder.replace(m.value, " ")
        }

        return ParsedQuery(
            ftsTerm      = remainder.trim(),
            extensions   = extensions,
            mimePrefixes = mimePrefixes,
            minSize      = minSize,
            maxSize      = maxSize,
            sinceDate    = sinceDate,
            folderPrefix = folderPrefix
        )
    }

    fun ParsedQuery.hasFilters(): Boolean =
        extensions.isNotEmpty() || mimePrefixes.isNotEmpty() ||
        minSize != null || maxSize != null ||
        sinceDate != null || folderPrefix != null
}

/**
 * Structured representation of a parsed search query.
 *
 * @param ftsTerm     Plain-text remainder for FTS/LIKE filename search (may be empty).
 * @param extensions  Extension filters, e.g. ["pdf", "jpg"]. OR'd together.
 * @param mimePrefixes MIME type LIKE patterns, e.g. ["video/%", "audio/%"]. OR'd together.
 * @param minSize     Minimum file size in bytes.
 * @param maxSize     Maximum file size in bytes.
 * @param sinceDate   Minimum lastModified timestamp (ms since epoch).
 * @param folderPrefix Folder path LIKE pattern, e.g. "%/downloads%".
 */
data class ParsedQuery(
    val ftsTerm: String,
    val extensions: List<String> = emptyList(),
    val mimePrefixes: List<String> = emptyList(),
    val minSize: Long? = null,
    val maxSize: Long? = null,
    val sinceDate: Long? = null,
    val folderPrefix: String? = null
)
