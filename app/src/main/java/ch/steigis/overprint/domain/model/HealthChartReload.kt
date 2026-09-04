package ch.steigis.overprint.domain.model

/**
 * Garmin Connect offloads the daily detail charts of older days: the summary totals stay,
 * but heart rate, stress, body battery, sleep stages, SpO2, respiration and the steps /
 * floors curves come back empty until the day is reloaded. Garmin's own app calls this
 * "Reload Chart" — the reload is queued server side, takes a few minutes, and the number
 * of reloads per 24 hours is capped. This row remembers where one day stands.
 */
data class HealthChartReload(
    val date: String,
    val state: HealthReloadState,
    val requestedAtMillis: Long = 0L,
    val checkedAtMillis: Long = 0L,
    val message: String? = null,
)

enum class HealthReloadState {
    /** Garmin returned no detail curves for the day; a reload has not been asked for yet. */
    OFFLOADED,

    /** Reload requested. Garmin says the charts are ready "within minutes". */
    REQUESTED,

    /** Detail curves arrived and are stored locally, so they no longer depend on Garmin. */
    LOADED,

    /** The 24-hour reload quota is used up. */
    LIMIT_REACHED,

    /** Garmin declined the reload, or has nothing recorded for that day. */
    UNAVAILABLE,
}
