package za.kilowatch.ultimatefilemanager.util

/**
 * Natural alpha-numeric comparison for filenames and similar labels.
 *
 * Orders numeric substrings by their numeric value rather than character-by-character
 * sequence, so that "file2.jpg" sorts before "file10.jpg" (where a plain lexicographic
 * compare would order them the other way because '1' < '2').
 *
 * Behaviour:
 *  - Each name is split into alternating text and digit runs (`file10.jpg` → `["file", "10", ".jpg"]`).
 *  - Text runs compare case-insensitively ([String.CASE_INSENSITIVE_ORDER]), digit runs by value.
 *  - Digit runs are compared by length-then-lexicographic (overflow-safe) with a leading-zero
 *    tie-break: fewer leading zeros sorts first (`file2` < `file02` < `file002`).
 *  - When two names are equal under the natural comparison but not byte-identical, a final
 *    case-sensitive [String.compareTo] tie-break guarantees a stable total order.
 *
 * Deterministic and locale-independent (no [java.text.Collator]), so the same input always
 * yields the same order on every device and locale. Pure Kotlin with no Android imports, so it
 * is safe to use in JVM unit tests.
 */
object NaturalSort {

    /** Natural-order [Comparator] over [String]. */
    val order: Comparator<String> = Comparator { a, b -> naturalCompare(a, b) }

    /** Builds a natural-order [Comparator] for [T] using [selector] to extract the name. */
    fun <T> byName(selector: (T) -> String): Comparator<T> =
        compareBy(order, selector)

    /**
     * Splits a name at every digit/non-digit boundary. Consecutive digits (or consecutive
     * non-digits) stay together, so the result alternates between text and digit runs.
     */
    private val SPLIT = Regex("(?<=\\d)(?=\\D)|(?<=\\D)(?=\\d)")

    /**
     * Compares [a] and [b] in natural order.
     *
     * Nulls are treated as empty strings and sort first.
     */
    fun naturalCompare(a: String?, b: String?): Int {
        if (a == null || b == null) {
            return when {
                a == null && b == null -> 0
                a == null -> -1
                else -> 1
            }
        }
        if (a == b) return 0

        val aChunks = a.split(SPLIT)
        val bChunks = b.split(SPLIT)
        val shared = minOf(aChunks.size, bChunks.size)

        for (i in 0 until shared) {
            val ac = aChunks[i]
            val bc = bChunks[i]
            val aIsDigit = ac.isNotEmpty() && ac[0] in '0'..'9'
            val bIsDigit = bc.isNotEmpty() && bc[0] in '0'..'9'

            val cmp = if (aIsDigit && bIsDigit) {
                compareDigitChunks(ac, bc)
            } else {
                // Text runs — or a text/digit mismatch — fall back to case-insensitive string compare.
                String.CASE_INSENSITIVE_ORDER.compare(ac, bc)
            }
            if (cmp != 0) return cmp
        }

        // All shared runs equal — the name with fewer runs sorts first.
        val lengthCmp = aChunks.size - bChunks.size
        if (lengthCmp != 0) return lengthCmp

        // Deterministic total order for names that are equal except in case.
        return a.compareTo(b)
    }

    /**
     * Compares two digit runs by numeric value, ignoring leading zeros, without ever parsing a
     * fixed-width integer (so arbitrarily long numbers cannot overflow).
     *
     * When the numeric values are equal, the run with fewer leading zeros sorts first.
     */
    private fun compareDigitChunks(a: String, b: String): Int {
        val aStripped = a.trimStart('0')
        val bStripped = b.trimStart('0')
        if (aStripped.length != bStripped.length) return aStripped.length - bStripped.length
        val cmp = aStripped.compareTo(bStripped)
        if (cmp != 0) return cmp
        return a.length - b.length
    }
}
