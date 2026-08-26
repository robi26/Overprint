package ch.steigis.overprint.domain.model

/** One point on an all-day Garmin wellness curve. */
data class HealthSample(
    val date: String,
    val metric: HealthSeries,
    val timestampMillis: Long,
    val value: Double,
)

enum class HealthSeries {
    HEART_RATE,
    STEPS,
    STRESS,
    BODY_BATTERY,
    SLEEP,
    SPO2,
    RESPIRATION,
    FLOORS,
}
