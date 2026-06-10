package za.kilowatch.ultimatefilemanager.smartsort

import za.kilowatch.ultimatefilemanager.R

enum class SmartSortCategory(
    val folderName: String,
    val displayNameResId: Int,
    val extensions: Set<String>
) {
    PHOTOS(
        folderName = "UFM Photos",
        displayNameResId = R.string.smart_sort_category_photos,
        extensions = setOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "svg", "heic", "heif")
    ),
    VIDEOS(
        folderName = "UFM Videos",
        displayNameResId = R.string.smart_sort_category_videos,
        extensions = setOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "3gp", "m4v")
    ),
    AUDIO(
        folderName = "UFM Audio",
        displayNameResId = R.string.smart_sort_category_audio,
        extensions = setOf("mp3", "wav", "aac", "flac", "ogg", "wma", "m4a", "opus")
    ),
    DOCUMENTS(
        folderName = "UFM Documents",
        displayNameResId = R.string.smart_sort_category_documents,
        extensions = setOf(
            "pdf", "doc", "docx", "docm", "dot", "dotx",
            "xls", "xlsx", "xlsm", "xlt", "xltx",
            "ppt", "pptx", "pptm", "pps", "ppsx",
            "txt", "csv", "rtf", "odt"
        )
    ),
    ARCHIVES(
        folderName = "UFM Archives",
        displayNameResId = R.string.smart_sort_category_archives,
        extensions = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "iso", "tgz")
    ),
    APPS(
        folderName = "UFM Apps",
        displayNameResId = R.string.smart_sort_category_apps,
        extensions = setOf("apk", "xapk", "apks")
    ),
    EBOOKS(
        folderName = "UFM eBooks",
        displayNameResId = R.string.smart_sort_category_ebooks,
        extensions = setOf("epub", "mobi", "azw3", "cbr", "cbz", "djvu")
    );

    companion object {
        fun categorize(extension: String): SmartSortCategory? {
            val lower = extension.lowercase()
            for (cat in entries) {
                if (lower in cat.extensions) return cat
            }
            return null
        }
    }
}

enum class SizeTier(
    val folderName: String,
    val displayNameResId: Int,
    val minBytes: Long,
    val maxBytes: Long
) {
    TINY(
        folderName = "UFM Tiny",
        displayNameResId = R.string.smart_sort_size_tiny,
        minBytes = 0L,
        maxBytes = 1024L * 1024L
    ),
    SMALL(
        folderName = "UFM Small",
        displayNameResId = R.string.smart_sort_size_small,
        minBytes = 1024L * 1024L,
        maxBytes = 10L * 1024L * 1024L
    ),
    MEDIUM(
        folderName = "UFM Medium",
        displayNameResId = R.string.smart_sort_size_medium,
        minBytes = 10L * 1024L * 1024L,
        maxBytes = 100L * 1024L * 1024L
    ),
    LARGE(
        folderName = "UFM Large",
        displayNameResId = R.string.smart_sort_size_large,
        minBytes = 100L * 1024L * 1024L,
        maxBytes = 1024L * 1024L * 1024L
    ),
    HUGE(
        folderName = "UFM Huge",
        displayNameResId = R.string.smart_sort_size_huge,
        minBytes = 1024L * 1024L * 1024L,
        maxBytes = Long.MAX_VALUE
    );

    companion object {
        fun forSize(bytes: Long): SizeTier? {
            for (tier in entries) {
                if (bytes >= tier.minBytes && bytes < tier.maxBytes) return tier
            }
            return null
        }
    }
}

enum class DatePeriod(
    val folderName: String,
    val displayNameResId: Int
) {
    TODAY(
        folderName = "UFM Today",
        displayNameResId = R.string.smart_sort_date_today
    ),
    THIS_WEEK(
        folderName = "UFM This Week",
        displayNameResId = R.string.smart_sort_date_this_week
    ),
    THIS_MONTH(
        folderName = "UFM This Month",
        displayNameResId = R.string.smart_sort_date_this_month
    ),
    THIS_YEAR(
        folderName = "UFM This Year",
        displayNameResId = R.string.smart_sort_date_this_year
    ),
    OLDER(
        folderName = "UFM Older",
        displayNameResId = R.string.smart_sort_date_older
    );

    companion object {
        fun forMillis(millis: Long): DatePeriod {
            val now = System.currentTimeMillis()
            val cal = java.util.Calendar.getInstance()

            cal.timeInMillis = now
            val todayStart = cal.apply {
                set(java.util.Calendar.HOUR_OF_DAY, 0)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }.timeInMillis

            cal.timeInMillis = now
            cal.set(java.util.Calendar.DAY_OF_WEEK, cal.getFirstDayOfWeek())
            val weekStart = cal.apply {
                set(java.util.Calendar.HOUR_OF_DAY, 0)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }.timeInMillis

            cal.timeInMillis = now
            val monthStart = cal.apply {
                set(java.util.Calendar.DAY_OF_MONTH, 1)
                set(java.util.Calendar.HOUR_OF_DAY, 0)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }.timeInMillis

            cal.timeInMillis = now
            val yearStart = cal.apply {
                set(java.util.Calendar.DAY_OF_YEAR, 1)
                set(java.util.Calendar.HOUR_OF_DAY, 0)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }.timeInMillis

            return when {
                millis >= todayStart -> TODAY
                millis >= weekStart -> THIS_WEEK
                millis >= monthStart -> THIS_MONTH
                millis >= yearStart -> THIS_YEAR
                else -> OLDER
            }
        }
    }
}

enum class SmartSortMode(val displayNameResId: Int) {
    TYPE(R.string.smart_sort_mode_type),
    SIZE(R.string.smart_sort_mode_size),
    DATE(R.string.smart_sort_mode_date),
    CUSTOM(R.string.smart_sort_mode_custom)
}
