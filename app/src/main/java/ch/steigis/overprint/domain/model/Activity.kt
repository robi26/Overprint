package ch.steigis.overprint.domain.model

data class Activity(
    val id: String,
    val externalId: String,
    val source: DataSource,
    val name: String,
    val type: ActivityType,
    val startTimeMillis: Long,
    val location: String?,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val movingSeconds: Double,
    val elevationGainMeters: Double?,
    val calories: Double?,
    val avgHeartRate: Double?,
    val maxHeartRate: Double?,
    val avgSpeedMps: Double?,
    val maxSpeedMps: Double?,
    val avgCadence: Double?,
    val avgPower: Double?,
    val maxPower: Double?,
    val avgGrade: Double?,
    val startLatitude: Double?,
    val startLongitude: Double?,
    val deviceName: String?,
    val hasTrack: Boolean,
    val minHeartRate: Double? = null,
    val maxCadence: Double? = null,
    val elevationLossMeters: Double? = null,
    val normalizedPower: Double? = null,
    val trainingStressScore: Double? = null,
    val intensityFactor: Double? = null,
    val avgTemperatureC: Double? = null,
    val avgVerticalOscillationMm: Double? = null,
    val avgStanceTimeMs: Double? = null,
    val avgVerticalRatio: Double? = null,
    val avgStepLengthMm: Double? = null,
    val avgRespirationRate: Double? = null,
    val aerobicTrainingEffect: Double? = null,
    val anaerobicTrainingEffect: Double? = null,
    val notes: String? = null,
    val deleted: Boolean = false,
) {
    val endTimeMillis: Long get() = startTimeMillis + (durationSeconds * 1000).toLong()
    val paceSecPerKm: Double? get() =
        if (distanceMeters > 1) durationSeconds / (distanceMeters / 1000.0) else null
    val strideLengthMeters: Double? get() {
        val cadence = avgCadence ?: return null
        val speed = avgSpeedMps ?: return null
        val stepsPerSec = cadence / 60.0
        if (stepsPerSec <= 0) return null
        return if (type == ActivityType.RUNNING || type == ActivityType.WALKING) {
            speed / stepsPerSec
        } else null
    }
    val workKj: Double? get() {
        val power = avgPower
        return if (power == null) calories?.let { it * 4.184 }
        else power * durationSeconds / 1000.0
    }
}

/** Average HR that can come from a real activity (not a sensor glitch or missing value). */
fun Activity.plausibleAvgHr(): Double? = avgHeartRate?.takeIf { it in 50.0..220.0 }

/**
 * Pace in seconds per kilometre, dropping GPS/FIT outliers that flatten scatter plots.
 * Allows cycling (~60 km/h) through slow hiking (~2 km/h); requires a real distance and duration.
 */
fun Activity.plausiblePaceSecPerKm(): Double? {
    if (distanceMeters < 400.0) return null
    if (durationSeconds !in 60.0..(12.0 * 3600.0)) return null
    return paceSecPerKm?.takeIf { it in 60.0..1_800.0 }
}

fun Activity.plausiblePower(): Double? = avgPower?.takeIf { it in 20.0..2_000.0 }

fun Activity.plausibleDistanceKm(): Double? =
    distanceMeters.takeIf { it in 50.0..500_000.0 }?.div(1000.0)

fun Activity.plausibleDurationMinutes(): Double? =
    durationSeconds.takeIf { it in 60.0..(16.0 * 3600.0) }?.div(60.0)


data class TrackPoint(
    val activityId: String,
    val timestampMillis: Long,
    val elapsedSeconds: Double,
    val latitude: Double?,
    val longitude: Double?,
    val altitudeMeters: Double?,
    val distanceMeters: Double?,
    val speedMps: Double?,
    val heartRate: Double?,
    val cadence: Double?,
    val power: Double?,
    val gradePercent: Double?,
    val temperatureC: Double?,
    val verticalOscillationMm: Double? = null,
    val stanceTimeMs: Double? = null,
    val verticalRatio: Double? = null,
    val stepLengthMm: Double? = null,
    val leftRightBalancePercent: Double? = null,
    val respirationRate: Double? = null,
    val stanceTimeBalancePercent: Double? = null,
)

data class Lap(
    val activityId: String,
    val index: Int,
    val startTimeMillis: Long,
    val durationSeconds: Double,
    val distanceMeters: Double,
    val avgHeartRate: Double?,
    val maxHeartRate: Double?,
    val avgSpeedMps: Double?,
    val avgCadence: Double?,
    val avgPower: Double?,
    val elevationGainMeters: Double?,
    val label: String,
)

/** What a [Split] covers; [group] puts kinds that share a timeline together. */
enum class SplitKind(val key: String, val label: String, val group: SplitGroup) {
    RUN("run", "Run", SplitGroup.RUN_WALK),
    WALK("walk", "Walk", SplitGroup.RUN_WALK),
    IDLE("idle", "Idle", SplitGroup.RUN_WALK),
    CLIMB("climb", "Climb", SplitGroup.CLIMBS),
    SEATED("seated", "Seated", SplitGroup.RIDER_POSITION),
    STANDING("standing", "Standing", SplitGroup.RIDER_POSITION),
    ;

    companion object {
        fun fromKey(key: String): SplitKind? = entries.firstOrNull { it.key == key }
    }
}

enum class SplitGroup(val title: String) {
    RUN_WALK("Run / walk"),
    CLIMBS("Climbs"),
    RIDER_POSITION("Seated / standing"),
}

/** A stretch of an activity the device classified, e.g. a walk break or a climb. */
data class Split(
    val activityId: String,
    val kind: SplitKind,
    val startTimeMillis: Long,
    val durationSeconds: Double,
    val movingSeconds: Double? = null,
    val distanceMeters: Double? = null,
    val ascentMeters: Double? = null,
    val descentMeters: Double? = null,
    val avgHeartRate: Double? = null,
    val maxHeartRate: Double? = null,
    val avgSpeedMps: Double? = null,
    val avgCadence: Double? = null,
    val avgPower: Double? = null,
    val avgGradePercent: Double? = null,
)

enum class DeviceConnection(val key: String, val label: String) {
    RECORDER("recorder", "Recording device"),
    ANT("ant", "ANT+"),
    BLUETOOTH("bluetooth", "Bluetooth"),
    OTHER("other", "Connected"),
    BUILT_IN("built_in", "Built-in"),
    ;

    companion object {
        fun fromKey(key: String): DeviceConnection = entries.firstOrNull { it.key == key } ?: OTHER
    }
}

/** The watch that recorded an activity, or a sensor that fed it data. */
data class ActivityDevice(
    val activityId: String,
    val name: String,
    val connection: DeviceConnection,
    val manufacturer: String? = null,
    /** What the device measures, e.g. "Heart rate monitor" or "Footpod". */
    val kind: String? = null,
    val serialNumber: Long? = null,
    val softwareVersion: String? = null,
    val batteryStatus: String? = null,
    val batteryPercent: Int? = null,
    val batteryVoltage: Double? = null,
)

data class ActivityDetail(
    val activity: Activity,
    val track: List<TrackPoint>,
    val laps: List<Lap>,
    val splits: List<Split> = emptyList(),
    val devices: List<ActivityDevice> = emptyList(),
)

data class ZoneBucket(
    val zone: Int,
    val label: String,
    val seconds: Double,
    val fraction: Float,
)
