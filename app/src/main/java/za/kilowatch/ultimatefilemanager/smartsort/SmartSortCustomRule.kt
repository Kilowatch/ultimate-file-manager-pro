package za.kilowatch.ultimatefilemanager.smartsort

data class SmartSortCustomRule(
    val id: String = java.util.UUID.randomUUID().toString(),
    val description: String = "",
    val extensions: MutableSet<String> = mutableSetOf(),
    val enabled: Boolean = true,
    val customFolderPath: String? = null,
    val customFolderShareId: String? = null
)
