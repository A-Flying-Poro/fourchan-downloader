data class Thread(
    val id: String,
    val board: String,
    val title: String,
    val images: List<ImageLink> = listOf()
)
