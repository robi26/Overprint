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

enum class MapMetric {
    HEART_RATE, SPEED, POWER, CADENCE, ELEVATION, GRADE,
    TEMPERATURE, RESPIRATION, STEP_LENGTH, VERTICAL_OSC, GROUND_CONTACT, VERTICAL_RATIO, BALANCE,
}

fun MapMetric.shortTitle(): String = when (this) {
    MapMetric.HEART_RATE -> "HR"
    MapMetric.SPEED -> "Speed"
    MapMetric.POWER -> "Power"
    MapMetric.CADENCE -> "Cadence"
    MapMetric.ELEVATION -> "Elev"
    MapMetric.GRADE -> "Grade"
    MapMetric.TEMPERATURE -> "Temp"
    MapMetric.RESPIRATION -> "Resp"
    MapMetric.STEP_LENGTH -> "Step"
    MapMetric.VERTICAL_OSC -> "VO"
    MapMetric.GROUND_CONTACT -> "GCT"
    MapMetric.VERTICAL_RATIO -> "VR"
    MapMetric.BALANCE -> "L/R"
}

enum class ChartMetric {
    HEART_RATE, PACE, SPEED, POWER, CADENCE, ELEVATION, GRADE,
    TEMPERATURE, RESPIRATION, STEP_LENGTH, VERTICAL_OSC, GROUND_CONTACT, VERTICAL_RATIO, BALANCE,
}

val ChartMetric.isCore: Boolean
    get() = this == ChartMetric.HEART_RATE || this == ChartMetric.PACE || this == ChartMetric.SPEED ||
        this == ChartMetric.POWER || this == ChartMetric.CADENCE || this == ChartMetric.ELEVATION ||
        this == ChartMetric.GRADE

fun ChartMetric.title(): String = when (this) {
    ChartMetric.HEART_RATE -> "Heart rate"
    ChartMetric.PACE -> "Pace"
    ChartMetric.SPEED -> "Speed"
    ChartMetric.POWER -> "Power"
    ChartMetric.CADENCE -> "Cadence"
    ChartMetric.ELEVATION -> "Elevation"
    ChartMetric.GRADE -> "Grade"
    ChartMetric.TEMPERATURE -> "Temperature"
    ChartMetric.RESPIRATION -> "Respiration"
    ChartMetric.STEP_LENGTH -> "Step length"
    ChartMetric.VERTICAL_OSC -> "Vertical osc."
    ChartMetric.GROUND_CONTACT -> "Ground contact"
    ChartMetric.VERTICAL_RATIO -> "Vertical ratio"
    ChartMetric.BALANCE -> "L/R balance"
}

fun ChartMetric.shortTitle(): String = when (this) {
    ChartMetric.HEART_RATE -> "HR"
    ChartMetric.PACE -> "Pace"
    ChartMetric.SPEED -> "Speed"
    ChartMetric.POWER -> "Power"
    ChartMetric.CADENCE -> "Cadence"
    ChartMetric.ELEVATION -> "Elev"
    ChartMetric.GRADE -> "Grade"
    ChartMetric.TEMPERATURE -> "Temp"
    ChartMetric.RESPIRATION -> "Resp"
    ChartMetric.STEP_LENGTH -> "Step"
    ChartMetric.VERTICAL_OSC -> "VO"
    ChartMetric.GROUND_CONTACT -> "GCT"
    ChartMetric.VERTICAL_RATIO -> "VR"
    ChartMetric.BALANCE -> "L/R"
}

fun ChartMetric.unit(metric: Boolean = true): String = when (this) {
    ChartMetric.HEART_RATE -> "bpm"
    ChartMetric.PACE -> if (metric) "/km" else "/mi"
    ChartMetric.SPEED -> if (metric) "km/h" else "mph"
    ChartMetric.POWER -> "W"
    ChartMetric.CADENCE -> "rpm"
    ChartMetric.ELEVATION -> if (metric) "m" else "ft"
    ChartMetric.GRADE, ChartMetric.VERTICAL_RATIO -> "%"
    ChartMetric.TEMPERATURE -> if (metric) "°C" else "°F"
    ChartMetric.RESPIRATION -> "br/min"
    ChartMetric.STEP_LENGTH -> if (metric) "m" else "ft"
    ChartMetric.VERTICAL_OSC -> "mm"
    ChartMetric.GROUND_CONTACT -> "ms"
    ChartMetric.BALANCE -> "% R"
}

fun TrackPoint.chartValue(metric: ChartMetric, metricUnits: Boolean = true): Double? = when (metric) {
    ChartMetric.HEART_RATE -> heartRate
    ChartMetric.PACE -> speedMps?.let { raw ->
        val mps = if (raw > 80.0) raw / 1000.0 else raw
        mps.takeIf { it > 0.3 }?.let { speed ->
            val secPerKm = 1000.0 / speed
            if (metricUnits) secPerKm else secPerKm * 1.609344
        }
    }
    ChartMetric.SPEED -> speedMps?.takeIf { it >= 0 }?.let { raw ->
        val mps = if (raw > 80.0) raw / 1000.0 else raw
        mps.takeIf { it in 0.0..55.0 }?.times(if (metricUnits) 3.6 else 2.236936)
    }
    ChartMetric.POWER -> power
    ChartMetric.CADENCE -> cadence
    ChartMetric.ELEVATION -> altitudeMeters?.let { if (metricUnits) it else it * 3.28084 }
    ChartMetric.GRADE -> gradePercent
    ChartMetric.TEMPERATURE -> temperatureC?.let { if (metricUnits) it else it * 9.0 / 5.0 + 32.0 }
    ChartMetric.RESPIRATION -> respirationRate
    ChartMetric.STEP_LENGTH -> stepLengthMm?.div(1000.0)?.let { if (metricUnits) it else it * 3.28084 }
    ChartMetric.VERTICAL_OSC -> verticalOscillationMm
    ChartMetric.GROUND_CONTACT -> stanceTimeMs
    ChartMetric.VERTICAL_RATIO -> verticalRatio
    ChartMetric.BALANCE -> leftRightBalancePercent
}

fun formatChartValue(metric: ChartMetric, value: Double): String = when (metric) {
    ChartMetric.PACE -> {
        val total = kotlin.math.round(value).toInt().coerceAtLeast(0)
        String.format(java.util.Locale.US, "%d:%02d", total / 60, total % 60)
    }
    ChartMetric.HEART_RATE, ChartMetric.CADENCE, ChartMetric.POWER, ChartMetric.ELEVATION,
    ChartMetric.RESPIRATION, ChartMetric.VERTICAL_OSC, ChartMetric.GROUND_CONTACT ->
        String.format(java.util.Locale.US, "%.0f", value)
    ChartMetric.SPEED, ChartMetric.GRADE, ChartMetric.TEMPERATURE, ChartMetric.VERTICAL_RATIO,
    ChartMetric.BALANCE ->
        String.format(java.util.Locale.US, "%.1f", value)
    ChartMetric.STEP_LENGTH ->
        String.format(java.util.Locale.US, "%.2f", value)
}
