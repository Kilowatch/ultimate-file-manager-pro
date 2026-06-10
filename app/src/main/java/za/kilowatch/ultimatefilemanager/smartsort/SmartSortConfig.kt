package za.kilowatch.ultimatefilemanager.smartsort

import za.kilowatch.ultimatefilemanager.network.NetworkShare

enum class SortConfigType { STANDARD, CUSTOM }

data class SmartSortConfig(
    val sourcePath: String = "",
    val sortConfigType: SortConfigType = SortConfigType.STANDARD,
    val mode: SmartSortMode = SmartSortMode.TYPE,
    val recursive: Boolean = false,
    val flattenSubfolders: Boolean = true,
    val maxDepth: Int = Int.MAX_VALUE,
    val prefix: String = "UFM",
    val enabledCategories: Set<SmartSortCategory> = SmartSortCategory.entries.toSet(),
    val enabledSizeTiers: Set<SizeTier> = emptySet(),
    val enabledDatePeriods: Set<DatePeriod> = emptySet(),
    val includeOther: Boolean = false,
    val duplicateStrategy: DuplicateStrategy = DuplicateStrategy.RENAME,
    val existingFolderStrategy: ExistingFolderStrategy = ExistingFolderStrategy.MERGE,
    val shareInfo: NetworkShare? = null,
    val customCategoryPaths: Map<String, String> = emptyMap(),
    val customCategoryShareIds: Map<String, String> = emptyMap(),
    val customRules: List<SmartSortCustomRule> = emptyList()
) {
    val isNetwork: Boolean get() = shareInfo != null
    enum class DuplicateStrategy {
        SKIP,
        RENAME,
        OVERWRITE,
        ASK
    }

    enum class ExistingFolderStrategy {
        MERGE,
        SKIP,
        RENAME,
        ASK
    }

    fun resolveFolderName(baseName: String): String {
        return if (prefix.isBlank()) baseName else "$prefix $baseName".trim()
    }

    fun isCategoryEnabled(category: SmartSortCategory): Boolean {
        return mode == SmartSortMode.TYPE && category in enabledCategories
    }

    fun isSizeTierEnabled(tier: SizeTier): Boolean {
        return mode == SmartSortMode.SIZE && tier in enabledSizeTiers
    }

    fun isDatePeriodEnabled(period: DatePeriod): Boolean {
        return mode == SmartSortMode.DATE && period in enabledDatePeriods
    }
}
