package za.kilowatch.ultimatefilemanager.storage

import java.util.Calendar
import java.util.Locale

/**
 * Pure-function pattern resolver for batch rename.
 *
 * Supports extended tokens (#/##/###, $F, $N, $E, $Y/$M/$D),
 * custom extension overrides ($E toggle), date overrides, case transforms ($U/$L option flags), and string text replacement.
 */
object BatchRenamePatternResolver {

    const val TOKEN_ORIGINAL = "{Original}"
    const val TOKEN_PADDING = "{Padding}"

    private val CASE_UPPER_REGEX = Regex("""\$(upper|U)\b""", RegexOption.IGNORE_CASE)
    private val CASE_LOWER_REGEX = Regex("""\$(lower|L)\b""", RegexOption.IGNORE_CASE)

    /**
     * Resolve a single item's resulting name from pattern and settings.
     */
    fun resolve(
        pattern: String,
        item: BatchRenameItem,
        counter: Int,
        useOriginal: Boolean = false,
        usePadding: Boolean = false,
        paddingLength: Int = 3,
        paddingStart: Int = 1,
        replaceText: String = "",
        replaceWith: String = "",
        useCustomExtension: Boolean = false,
        customExtension: String = "",
        useYear: Boolean = false,
        useMonth: Boolean = false,
        useDay: Boolean = false,
        customYear: String = "",
        customMonth: String = "",
        customDay: String = "",
        hasUpper: Boolean = false,
        hasLower: Boolean = false
    ): String {
        var result = pattern

        // 1. Detect and strip case tokens ($U / $upper / $L / $lower) if present in pattern text
        val toUpper = hasUpper || CASE_UPPER_REGEX.containsMatchIn(result)
        val toLower = hasLower || CASE_LOWER_REGEX.containsMatchIn(result)
        result = result.replace(CASE_UPPER_REGEX, "").replace(CASE_LOWER_REGEX, "")

        val hadTokens = hasAnyTokens(pattern) || useOriginal || usePadding || useYear || useMonth || useDay || toUpper || toLower

        // If pattern is empty (or was just case tokens), default base name to item.name when actions exist
        if (result.isEmpty()) {
            if (replaceText.isNotEmpty() || toUpper || toLower || useOriginal) {
                result = item.name
            }
        }

        // 2. Sequential numbers token `#+`
        result = Regex("#+").replace(result) { match ->
            val padWidth = match.value.length
            (paddingStart + counter - 1).toString().padStart(padWidth, '0')
        }

        // 3. Substitute legacy `{Padding}` token (without parentheses)
        if (result.contains(TOKEN_PADDING)) {
            val padValue = (paddingStart + counter - 1).toString().padStart(paddingLength, '0')
            result = result.replace(TOKEN_PADDING, padValue)
        } else if (usePadding && !hasSequenceNumberToken(pattern)) {
            val padValue = (paddingStart + counter - 1).toString().padStart(paddingLength, '0')
            result = if (result.isEmpty()) padValue else "$result $padValue"
        }

        // 4. Substitute Full Name tokens ($F, $fullname, {fullname}, %F) with base name during pattern construction
        val baseName = item.name
        result = result
            .replace("\$fullname", baseName)
            .replace("{fullname}", baseName)
            .replace("\$F", baseName)
            .replace("%F", baseName)

        // 5. Substitute Name tokens ($N, $name, {name}, %N, {Original})
        result = result
            .replace(TOKEN_ORIGINAL, baseName)
            .replace("\$name", baseName)
            .replace("{name}", baseName)
            .replace("\$N", baseName)
            .replace("%N", baseName)

        if (useOriginal && !hasNameToken(pattern)) {
            result = if (result.isEmpty()) baseName else "$baseName $result"
        }

        // 6. Substitute Extension tokens ($E, $ext, {ext}, %E)
        val extNoDot = if (useCustomExtension && customExtension.isNotBlank()) {
            customExtension.removePrefix(".")
        } else {
            item.extension.removePrefix(".")
        }
        result = result
            .replace("\$ext", extNoDot)
            .replace("{ext}", extNoDot)
            .replace("\$E", extNoDot)
            .replace("%E", extNoDot)

        // 7. Substitute Date tokens ($Y, $M, $D) and Date option cards
        val calendar = Calendar.getInstance().apply {
            timeInMillis = if (item.lastModified > 0L) item.lastModified else System.currentTimeMillis()
        }
        val fileYear = String.format(Locale.US, "%04d", calendar.get(Calendar.YEAR))
        val fileMonth = String.format(Locale.US, "%02d", calendar.get(Calendar.MONTH) + 1)
        val fileDay = String.format(Locale.US, "%02d", calendar.get(Calendar.DAY_OF_MONTH))

        val yearStr = if (useYear && customYear.isNotBlank()) customYear else fileYear
        val monthStr = if (useMonth && customMonth.isNotBlank()) customMonth else fileMonth
        val dayStr = if (useDay && customDay.isNotBlank()) customDay else fileDay

        if (result.contains("\$Y") || result.contains("\$M") || result.contains("\$D")) {
            result = result
                .replace("\$Y", yearStr)
                .replace("\$M", monthStr)
                .replace("\$D", dayStr)
        } else if (useYear || useMonth || useDay) {
            val dateParts = mutableListOf<String>()
            if (useYear) dateParts.add(yearStr)
            if (useMonth) dateParts.add(monthStr)
            if (useDay) dateParts.add(dayStr)
            val appendedDate = dateParts.joinToString("")
            result = if (result.isEmpty()) appendedDate else "$result$appendedDate"
        }

        // 8. If no tokens were present and no options active, append parenthetical index
        if (!hadTokens && result.isNotEmpty() && replaceText.isEmpty()) {
            result = "$result ($counter)"
        }

        // 9. Case transformation
        if (toUpper) {
            result = result.uppercase(Locale.getDefault())
        } else if (toLower) {
            result = result.lowercase(Locale.getDefault())
        }

        // 10. Perform Replace Text -> With (case-insensitive)
        if (replaceText.isNotEmpty()) {
            result = result.replace(replaceText, replaceWith, ignoreCase = true)
        }

        return result.trim()
    }

    /**
     * Checks if pattern explicitly requests bare name without extension ($N / $name / {name} / %N / {Original}).
     */
    fun hasBareNameToken(pattern: String): Boolean {
        val hasN = pattern.contains("\$N") ||
                pattern.contains("\$name") ||
                pattern.contains("{name}") ||
                pattern.contains("%N") ||
                pattern.contains(TOKEN_ORIGINAL)
        val hasF = pattern.contains("\$F") ||
                pattern.contains("\$fullname") ||
                pattern.contains("{fullname}") ||
                pattern.contains("%F")
        return hasN && !hasF
    }

    /**
     * Returns the full resulting filename with extension at the very end.
     */
    fun appendExtension(
        resolvedName: String,
        item: BatchRenameItem,
        pattern: String,
        useCustomExtension: Boolean = false,
        customExtension: String = "",
        hasUpper: Boolean = false,
        hasLower: Boolean = false
    ): String {
        if (resolvedName.isEmpty()) return ""
        if (item.isDirectory) return resolvedName

        val toUpper = hasUpper || CASE_UPPER_REGEX.containsMatchIn(pattern)
        val toLower = hasLower || CASE_LOWER_REGEX.containsMatchIn(pattern)

        // 1. If custom extension toggle is active and non-blank, ALWAYS apply custom extension
        if (useCustomExtension && customExtension.isNotBlank()) {
            val formattedCustomExt = if (customExtension.startsWith(".")) customExtension else ".$customExtension"
            val finalExt = when {
                toUpper -> formattedCustomExt.uppercase(Locale.getDefault())
                toLower -> formattedCustomExt.lowercase(Locale.getDefault())
                else -> formattedCustomExt
            }
            return resolvedName + finalExt
        }

        // 2. If pattern explicitly specifies $N (bare name without extension) and no $F token, omit extension
        if (hasBareNameToken(pattern)) {
            return resolvedName
        }

        // 3. For all other cases ($F, #, dates, custom text, etc.), append original extension
        val formattedExt = when {
            toUpper -> item.extension.uppercase(Locale.getDefault())
            toLower -> item.extension.lowercase(Locale.getDefault())
            else -> item.extension
        }
        return resolvedName + formattedExt
    }

    private fun hasAnyTokens(pattern: String): Boolean {
        return pattern.contains("#") ||
                pattern.contains("\$") ||
                pattern.contains("%") ||
                pattern.contains("{") ||
                pattern.contains(TOKEN_ORIGINAL) ||
                pattern.contains(TOKEN_PADDING)
    }

    private fun hasSequenceNumberToken(pattern: String): Boolean {
        return pattern.contains("#") || pattern.contains(TOKEN_PADDING)
    }

    private fun hasNameToken(pattern: String): Boolean {
        return pattern.contains(TOKEN_ORIGINAL) ||
                pattern.contains("\$N") ||
                pattern.contains("\$name") ||
                pattern.contains("{name}") ||
                pattern.contains("%N") ||
                pattern.contains("\$F") ||
                pattern.contains("\$fullname") ||
                pattern.contains("{fullname}") ||
                pattern.contains("%F")
    }
}
