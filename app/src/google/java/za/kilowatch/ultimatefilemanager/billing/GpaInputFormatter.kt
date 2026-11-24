package za.kilowatch.ultimatefilemanager.billing

import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.widget.EditText

/**
 * Text formatting utilities for Google Play Order IDs (GPA numbers)
 * and Anonymous Loyalty IDs.
 *
 * Provides:
 * 1. Fixed "GPA." prefix handling and auto-hyphenation (XXXX-XXXX-XXXX-XXXXX) for 17 digits.
 * 2. Case-insensitive auto-hyphenation (XXX-XXX-XXX-XXX) for Loyalty IDs.
 * 3. Robust paste sanitization and backspace handling.
 */
object GpaInputFormatter {

    /**
     * Applies auto-hyphen formatting to a GPA number input field.
     * Expects 17 digits formatted as: 4 digits - 4 digits - 4 digits - 5 digits.
     * Example: 3398-3263-8701-36162
     */
    fun applyGpaFormatter(editText: EditText) {
        editText.inputType = android.text.InputType.TYPE_CLASS_PHONE
        val digitsFilter = android.text.method.DigitsKeyListener.getInstance("0123456789-")
        editText.keyListener = digitsFilter
        // Limit raw length to 17 digits + 3 hyphens = 20 chars, and restrict to numeric digits + hyphens
        editText.filters = arrayOf(digitsFilter, InputFilter.LengthFilter(20))

        editText.addTextChangedListener(object : TextWatcher {
            private var isFormatting = false
            private var deletingHyphen = false
            private var hyphenPos = -1

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                if (isFormatting || s == null) return
                // Check if user is deleting a hyphen
                if (count == 1 && after == 0) {
                    val charDeleted = s.getOrNull(start)
                    if (charDeleted == '-') {
                        deletingHyphen = true
                        hyphenPos = start
                    } else {
                        deletingHyphen = false
                    }
                } else {
                    deletingHyphen = false
                }
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                if (isFormatting || s == null) return
                isFormatting = true

                var raw = s.toString()

                // If user pasted a full GPA string with "GPA." or "gpa.", strip it
                if (raw.startsWith("GPA.", ignoreCase = true)) {
                    raw = raw.substring(4)
                }

                // If a hyphen was deleted, remove the preceding digit too so user doesn't get stuck
                if (deletingHyphen && hyphenPos > 0) {
                    val sb = StringBuilder(raw)
                    if (hyphenPos - 1 < sb.length) {
                        sb.deleteCharAt(hyphenPos - 1)
                        raw = sb.toString()
                    }
                    deletingHyphen = false
                }

                // Extract only digits (max 17 digits)
                val digits = raw.filter { it.isDigit() }.take(17)

                // Build formatted string: XXXX-XXXX-XXXX-XXXXX
                val formatted = StringBuilder()
                for (i in digits.indices) {
                    if (i == 4 || i == 8 || i == 12) {
                        formatted.append('-')
                    }
                    formatted.append(digits[i])
                }

                val formattedStr = formatted.toString()
                if (formattedStr != s.toString()) {
                    s.replace(0, s.length, formattedStr)
                    editText.setSelection(s.length)
                }

                isFormatting = false
            }
        })
    }

    /**
     * Applies auto-hyphen and uppercase formatting to a Loyalty ID input field.
     * Expects 12 alphanumeric characters formatted as: XXX-XXX-XXX-XXX.
     */
    fun applyLoyaltyIdFormatter(editText: EditText) {
        // 12 chars + 3 hyphens = 15 chars
        editText.filters = arrayOf(InputFilter.LengthFilter(15))

        editText.addTextChangedListener(object : TextWatcher {
            private var isFormatting = false
            private var deletingHyphen = false
            private var hyphenPos = -1

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                if (isFormatting || s == null) return
                if (count == 1 && after == 0) {
                    if (s.getOrNull(start) == '-') {
                        deletingHyphen = true
                        hyphenPos = start
                    } else {
                        deletingHyphen = false
                    }
                } else {
                    deletingHyphen = false
                }
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                if (isFormatting || s == null) return
                isFormatting = true

                var raw = s.toString()
                if (deletingHyphen && hyphenPos > 0) {
                    val sb = StringBuilder(raw)
                    if (hyphenPos - 1 < sb.length) {
                        sb.deleteCharAt(hyphenPos - 1)
                        raw = sb.toString()
                    }
                    deletingHyphen = false
                }

                // Extract only alphanumeric chars, uppercase (max 12)
                val chars = raw.filter { it.isLetterOrDigit() }.uppercase().take(12)

                // Build: XXX-XXX-XXX-XXX
                val formatted = StringBuilder()
                for (i in chars.indices) {
                    if (i > 0 && i % 3 == 0) {
                        formatted.append('-')
                    }
                    formatted.append(chars[i])
                }

                val formattedStr = formatted.toString()
                if (formattedStr != s.toString()) {
                    s.replace(0, s.length, formattedStr)
                    editText.setSelection(s.length)
                }

                isFormatting = false
            }
        })
    }

    /**
     * Given user input from a GPA field, returns the normalized full GPA string: "GPA.XXXX-XXXX-XXXX-XXXXX".
     */
    fun normalizeGpa(input: String): String {
        var clean = input.trim()
        if (clean.startsWith("GPA.", ignoreCase = true)) {
            clean = clean.substring(4).trim()
        }
        val digits = clean.filter { it.isDigit() }.take(17)
        if (digits.length != 17) {
            // Return as-is with GPA. prefix for validation error reporting
            return if (input.startsWith("GPA.", ignoreCase = true)) input.uppercase() else "GPA.$clean".uppercase()
        }
        val p1 = digits.substring(0, 4)
        val p2 = digits.substring(4, 8)
        val p3 = digits.substring(8, 12)
        val p4 = digits.substring(12, 17)
        return "GPA.$p1-$p2-$p3-$p4"
    }

    /**
     * Normalizes a Loyalty ID: trims, uppercases, ensures XXX-XXX-XXX-XXX format.
     */
    fun normalizeLoyaltyId(input: String): String {
        val chars = input.filter { it.isLetterOrDigit() }.uppercase().take(12)
        if (chars.length != 12) return input.trim().uppercase()
        val p1 = chars.substring(0, 3)
        val p2 = chars.substring(3, 6)
        val p3 = chars.substring(6, 9)
        val p4 = chars.substring(9, 12)
        return "$p1-$p2-$p3-$p4"
    }

    fun sanitizeGpa(input: String): String = normalizeGpa(input)

    fun sanitizeLoyaltyId(input: String): String = normalizeLoyaltyId(input)

    fun isValidGpa(gpa: String): Boolean {
        val normalized = normalizeGpa(gpa)
        val regex = Regex("^GPA\\.\\d{4}-\\d{4}-\\d{4}-\\d{5}$", RegexOption.IGNORE_CASE)
        return regex.matches(normalized)
    }

    fun isValidLoyaltyId(id: String): Boolean {
        val normalized = normalizeLoyaltyId(id)
        val regex = Regex("^[A-Z0-9]{3}-[A-Z0-9]{3}-[A-Z0-9]{3}-[A-Z0-9]{3}$")
        return regex.matches(normalized)
    }

    fun attachGpaPrefixWatcher(editText: EditText) = applyGpaFormatter(editText)

    fun attachLoyaltyIdWatcher(editText: EditText) = applyLoyaltyIdFormatter(editText)
}
