package net.roz.connectstats.data.repo

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import net.roz.connectstats.data.local.ActivityEntity
import net.roz.connectstats.data.local.AppDatabase
import net.roz.connectstats.data.local.LapEntity
import net.roz.connectstats.data.local.TrackPointEntity
import net.roz.connectstats.data.parse.ActivityFileParser
import net.roz.connectstats.data.prefs.SettingsStore
import net.roz.connectstats.data.remote.garmin.GarminClient
import net.roz.connectstats.data.remote.garmin.GarminSyncProgress
import net.roz.connectstats.data.sample.SampleData
import net.roz.connectstats.domain.model.Activity
import net.roz.connectstats.domain.model.ActivityDetail
import net.roz.connectstats.domain.model.ActivityType
import net.roz.connectstats.domain.model.DataSource
import net.roz.connectstats.domain.model.GeoPoint
import net.roz.connectstats.domain.model.GpsTrack
import net.roz.connectstats.domain.model.Lap
import net.roz.connectstats.domain.model.TrackPoint
import kotlin.math.min

class ActivityRepository(
    private val db: AppDatabase,
    private val settings: SettingsStore,
) {
    val activities: Flow<List<Activity>> = db.activities().observeActivities().map { list ->
        list.map { it.toModel() }
    }

    suspend fun get(id: String): ActivityDetail? {
        val entity = db.activities().byId(id) ?: return null
        val track = db.tracks().forActivity(id).map { it.toModel() }
        val laps = db.laps().forActivity(id).map { it.toModel() }
        return ActivityDetail(entity.toModel(), track, laps)
    }

    suspend fun gpsTracks(
        activityIds: Collection<String>,
        maxPointsPerTrack: Int = 160,
    ): List<GpsTrack> {
        if (activityIds.isEmpty()) return emptyList()
        val grouped = LinkedHashMap<String, MutableList<GeoPoint>>()
        for (chunk in activityIds.distinct().chunked(80)) {
            val samples = db.tracks().gpsSamplesFor(chunk, stride = 12)
            for (sample in samples) {
                val lat = sample.latitude ?: continue
                val lon = sample.longitude ?: continue
                grouped.getOrPut(sample.activityId) { mutableListOf() }
                    .add(GeoPoint(lat, lon))
            }
        }
        return grouped.mapNotNull { (id, pts) ->
            val slim = downsample(pts, maxPointsPerTrack)
            if (slim.size < 2) return@mapNotNull null
            GpsTrack(
                activityId = id,
                points = slim,
                minLat = slim.minOf { it.lat },
                maxLat = slim.maxOf { it.lat },
                minLon = slim.minOf { it.lon },
                maxLon = slim.maxOf { it.lon },
            )
        }
    }

    suspend fun search(query: String): List<Activity> =
        if (query.isBlank()) db.activities().all().map { it.toModel() }
        else db.activities().search(query).map { it.toModel() }

    suspend fun importFile(bytes: ByteArray, fileName: String): Activity {
        val detail = ActivityFileParser.parse(bytes, fileName)
        save(detail)
        return detail.activity
    }

    suspend fun loadDemoIfEmpty() {
        if (db.activities().count() > 0) return
        SampleData.catalog().forEach { save(it) }
        settings.update { it.copy(demoLoaded = true) }
    }

    suspend fun reloadDemo() {
        db.tracks().clear()
        db.laps().clear()
        db.activities().clear()
        SampleData.catalog().forEach { save(it) }
        settings.update { it.copy(demoLoaded = true) }
    }

    suspend fun syncGarmin(progress: (GarminSyncProgress) -> Unit = {}) {
        val prefs = settings.settings.first()
        if (prefs.garminUsername.isBlank() || prefs.garminPassword.isBlank()) {
            error("Enter your Garmin Connect email and password in Settings")
        }
        val client = GarminClient()
        progress(GarminSyncProgress(running = true, message = "Signing in to Garmin…"))
        client.login(prefs.garminUsername, prefs.garminPassword)
        settings.update { it.copy(garminEnabled = true) }
        val summaries = mutableListOf<Activity>()
        var start = 0
        val pageSize = 20
        progress(GarminSyncProgress(running = true, message = "Listing Garmin activities…"))
        while (true) {
            progress(
                GarminSyncProgress(
                    running = true,
                    message = "Listing Garmin activities…",
                    current = summaries.size,
                ),
            )
            val page = client.listActivities(start, pageSize)
            if (page.isEmpty()) break
            summaries += page
            if (page.size < pageSize) break
            start += pageSize
            if (start > 2000) break
        }
        if (summaries.isEmpty()) {
            error("Garmin returned 0 activities (${client.lastListDiagnostic}).")
        }
        val warnings = mutableListOf<String>()
        summaries.forEachIndexed { index, summary ->
            progress(
                GarminSyncProgress(
                    running = true,
                    message = "Downloading ${summary.name}",
                    current = index,
                    total = summaries.size,
                    warnings = warnings.toList(),
                ),
            )
            val detail = runCatching { client.downloadFit(summary.externalId) }
                .getOrElse { err ->
                    warnings += "${summary.name}: ${err.message ?: err.javaClass.simpleName}"
                    ActivityDetail(summary.copy(hasTrack = false), emptyList(), emptyList())
                }
            save(detail.copy(activity = detail.activity.copy(name = summary.name, location = summary.location)))
        }
        val failed = warnings.size
        val summaryText = buildString {
            append("Imported ${summaries.size} Garmin activities")
            if (failed > 0) append(" ($failed without FIT track)")
        }
        progress(
            GarminSyncProgress(
                running = false,
                message = summaryText,
                current = summaries.size,
                total = summaries.size,
                warnings = warnings,
            ),
        )
    }

    suspend fun save(detail: ActivityDetail) {
        db.replaceDetail(
            activity = detail.activity.toEntity(),
            track = detail.track.map { it.toEntity() },
            laps = detail.laps.map { it.toEntity() },
        )
    }

    suspend fun rename(id: String, name: String) {
        val current = db.activities().byId(id) ?: return
        db.activities().upsert(current.copy(name = name))
    }

    suspend fun delete(id: String) {
        db.tracks().deleteFor(id)
        db.laps().deleteFor(id)
        db.activities().delete(id)
    }
}

private fun downsample(points: List<GeoPoint>, maxPoints: Int): List<GeoPoint> {
    if (points.size <= maxPoints) return points
    val last = points.lastIndex
    val step = last.toFloat() / (maxPoints - 1)
    return List(maxPoints) { i -> points[min(last, (i * step).toInt())] }
}

private fun ActivityEntity.toModel() = Activity(
    id, externalId, DataSource.valueOf(source), name, ActivityType.fromKey(type),
    startTimeMillis, location, distanceMeters, durationSeconds, movingSeconds,
    elevationGainMeters, calories, avgHeartRate, maxHeartRate, avgSpeedMps, maxSpeedMps,
    avgCadence, avgPower, maxPower, avgGrade, startLatitude, startLongitude, deviceName, hasTrack, notes,
)

private fun Activity.toEntity() = ActivityEntity(
    id, externalId, source.name, name, type.key, startTimeMillis, location, distanceMeters,
    durationSeconds, movingSeconds, elevationGainMeters, calories, avgHeartRate, maxHeartRate,
    avgSpeedMps, maxSpeedMps, avgCadence, avgPower, maxPower, avgGrade, startLatitude, startLongitude,
    deviceName, hasTrack, notes,
)

private fun TrackPointEntity.toModel() = TrackPoint(
    activityId, timestampMillis, elapsedSeconds, latitude, longitude, altitudeMeters,
    distanceMeters, speedMps, heartRate, cadence, power, gradePercent, temperatureC,
)

private fun TrackPoint.toEntity() = TrackPointEntity(
    activityId = activityId,
    timestampMillis = timestampMillis,
    elapsedSeconds = elapsedSeconds,
    latitude = latitude,
    longitude = longitude,
    altitudeMeters = altitudeMeters,
    distanceMeters = distanceMeters,
    speedMps = speedMps,
    heartRate = heartRate,
    cadence = cadence,
    power = power,
    gradePercent = gradePercent,
    temperatureC = temperatureC,
)

private fun LapEntity.toModel() = Lap(
    activityId, index, startTimeMillis, durationSeconds, distanceMeters, avgHeartRate,
    maxHeartRate, avgSpeedMps, avgCadence, avgPower, elevationGainMeters, label,
)

private fun Lap.toEntity() = LapEntity(
    activityId = activityId,
    index = index,
    startTimeMillis = startTimeMillis,
    durationSeconds = durationSeconds,
    distanceMeters = distanceMeters,
    avgHeartRate = avgHeartRate,
    maxHeartRate = maxHeartRate,
    avgSpeedMps = avgSpeedMps,
    avgCadence = avgCadence,
    avgPower = avgPower,
    elevationGainMeters = elevationGainMeters,
    label = label,
)
