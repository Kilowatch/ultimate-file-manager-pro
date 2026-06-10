package za.kilowatch.ultimatefilemanager.storage

data class VaultEntry(
    val id: String,
    val displayName: String,
    val originalRoot: String,
    val files: List<String>
)
