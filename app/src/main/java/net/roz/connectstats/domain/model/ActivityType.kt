package net.roz.connectstats.domain.model

enum class ActivityType(
    val key: String,
    val displayName: String,
    val colorArgb: Long,
    val lightArgb: Long,
) {
    RUNNING("running", "Run", 0xFF2E6BFF, 0xFFDCEEFF),
    CYCLING("cycling", "Bike", 0xFFE24B4B, 0xFFFFDADA),
    SWIMMING("swimming", "Swim", 0xFF1AA6C4, 0xFF80E6FF),
    HIKING("hiking", "Hike", 0xFFC8A26A, 0xFFE8C89E),
    WALKING("walking", "Walk", 0xFF5B8C5A, 0xFFD6EBD5),
    STRENGTH("strength_training", "Strength", 0xFFF169EF, 0xFFCAA4E8),
    SKIING("skiing", "Ski", 0xFFBDC3C7, 0xFFE8ECF0),
    MULTISPORT("multi_sport", "Multisport", 0xFFA6BB82, 0xFFE4EED0),
    OTHER("other", "Other", 0xFF3583F3, 0xFFE7EDF5);

    val usesPace: Boolean get() = this == RUNNING || this == WALKING || this == HIKING || this == SWIMMING
    val usesCadence: Boolean get() = this == RUNNING || this == CYCLING || this == WALKING

    companion object {
        fun fromKey(raw: String?): ActivityType {
            val key = raw?.lowercase()?.replace(" ", "_").orEmpty()
            return entries.firstOrNull {
                key.contains(it.key) || it.key.contains(key)
            } ?: when {
                key.contains("run") -> RUNNING
                key.contains("bike") || key.contains("cycl") || key.contains("ride") -> CYCLING
                key.contains("swim") -> SWIMMING
                key.contains("hike") || key.contains("trail") -> HIKING
                key.contains("walk") -> WALKING
                key.contains("strength") || key.contains("weight") || key.contains("fitness") -> STRENGTH
                key.contains("ski") -> SKIING
                key.contains("tri") || key.contains("multi") -> MULTISPORT
                else -> OTHER
            }
        }
    }
}

enum class DataSource { GARMIN, STRAVA, FILE, DEMO }

enum class MapMetric { HEART_RATE, SPEED, POWER, CADENCE, ELEVATION, GRADE }

enum class ChartMetric { HEART_RATE, PACE, SPEED, POWER, CADENCE, ELEVATION, GRADE }
