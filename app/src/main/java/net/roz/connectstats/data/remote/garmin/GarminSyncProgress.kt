package net.roz.connectstats.data.remote.garmin

data class GarminSyncProgress(
    val running: Boolean = false,
    val message: String = "",
    val current: Int = 0,
    val total: Int = 0,
    val error: String? = null,
    val warnings: List<String> = emptyList(),
) {
    val fraction: Float?
        get() = if (total > 0) (current.toFloat() / total).coerceIn(0f, 1f) else null
}
