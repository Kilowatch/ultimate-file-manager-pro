package za.kilowatch.ultimatefilemanager.smartsort

data class SmartSortSavedConfig(
    val id: String = java.util.UUID.randomUUID().toString(),
    val folderPath: String,
    val description: String = "",
    val savedAt: Long = System.currentTimeMillis(),
    val configJson: String
)
