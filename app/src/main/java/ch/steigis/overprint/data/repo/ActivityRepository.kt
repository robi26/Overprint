package ch.steigis.overprint.data.repo

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import ch.steigis.overprint.data.local.ActivityEntity
import ch.steigis.overprint.data.local.AppDatabase
import ch.steigis.overprint.data.local.LapEntity
import ch.steigis.overprint.data.local.TrackPointEntity
import ch.steigis.overprint.data.parse.ActivityFileParser
import ch.steigis.overprint.data.prefs.AppSettings
import ch.steigis.overprint.data.prefs.SettingsStore
import ch.steigis.overprint.data.remote.garmin.GarminClient
import ch.steigis.overprint.data.remote.garmin.GarminSyncProgress
import ch.steigis.overprint.data.remote.garmin.garminReachedKnownHistory
import ch.steigis.overprint.domain.model.Activity
import ch.steigis.overprint.domain.model.ActivityDetail
import ch.steigis.overprint.domain.model.ActivityType
import ch.steigis.overprint.domain.model.DataSource
import ch.steigis.overprint.domain.model.GeoPoint
import ch.steigis.overprint.domain.model.GpsTrack
import ch.steigis.overprint.domain.model.Lap
import ch.steigis.overprint.domain.model.TrackPoint
import ch.steigis.overprint.domain.stats.sanitizeActivity
import ch.steigis.overprint.domain.stats.sanitizeFitUnits
import ch.steigis.overprint.domain.stats.sanitizeLap
import ch.steigis.overprint.domain.stats.withDerivedTrackStats
import ch.steigis.overprint.domain.stats.withListExtras
import ch.steigis.overprint.domain.stats.withNormalizedElapsed
import kotlin.math.min

class ActivityRepository(
    private val db: AppDatabase,
    private val settings: SettingsStore,
) {
    val activities: Flow<List<Activity>> = db.activities().observeActivities().map { list ->
        list.map { it.toModel() }
    }

    val deletedActivities: Flow<List<Activity>> = db.activities().observeDeleted().map { list ->
        list.map { it.toModel() }
    }

    suspend fun get(id: String): ActivityDetail? {
        val entity = db.activities().byId(id) ?: return null
        val track = withNormalizedElapsed(sanitizeFitUnits(db.tracks().forActivity(id).map { it.toModel() }))
        val laps = db.laps().forActivity(id).map { it.toModel() }
        return ActivityDetail(entity.toModel().withDerivedTrackStats(track), track, laps)
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

    suspend fun removeDemoActivities() {
        db.activities().idsBySource(DataSource.DEMO.name).forEach { delete(it) }
        settings.update { it.copy(demoLoaded = false) }
    }

    suspend fun syncGarmin(progress: (GarminSyncProgress) -> Unit = {}) {
        val prefs = settings.settings.first()
        if (!prefs.hasGarminCredentials) {
            error("Enter your Garmin Connect email and password in Settings")
        }
        val client = GarminClient()
        authenticate(client, prefs, progress)
        val skipFetch = (
            db.activities().deletedIds() + db.activities().idsWithTrack()
            ).toHashSet()
        val warnings = mutableListOf<String>()
        var imported = 0
        var listed = 0
        var start = 0
        val pageSize = 20
        progress(GarminSyncProgress(running = true, message = "Looking for new Garmin activities…"))
        while (true) {
            progress(
                GarminSyncProgress(
                    running = true,
                    message = "Looking for new Garmin activities…",
                    current = imported,
                    warnings = warnings.toList(),
                ),
            )
            val page = client.listActivities(start, pageSize)
            if (page.isEmpty()) break
            listed += page.size
            page.filter { it.id in skipFetch }.forEach { summary ->
                mergeListExtras(summary)
            }
            if (garminReachedKnownHistory(page.map { it.id }, skipFetch)) break
            val toFetch = page.filter { it.id !in skipFetch }
            val batchTotal = imported + toFetch.size
            toFetch.forEach { summary ->
                progress(
                    GarminSyncProgress(
                        running = true,
                        message = "Downloading ${summary.name}",
                        current = imported,
                        total = batchTotal,
                        warnings = warnings.toList(),
                    ),
                )
                val detail = runCatching { client.downloadFit(summary.externalId) }
                    .getOrElse { err ->
                        warnings += "${summary.name}: ${err.message ?: err.javaClass.simpleName}"
                        ActivityDetail(summary.copy(hasTrack = false), emptyList(), emptyList())
                    }
                save(
                    detail.copy(
                        activity = detail.activity.withListExtras(summary).copy(
                            name = summary.name,
                            location = summary.location ?: detail.activity.location,
                        ),
                    ),
                )
                skipFetch += summary.id
                imported++
            }
            if (page.size < pageSize) break
            start += pageSize
            if (start > 2000) break
        }
        if (listed == 0) {
            error("Garmin returned 0 activities (${client.lastListDiagnostic}).")
        }
        val summaryText = when {
            imported == 0 -> "No new Garmin activities"
            else -> buildString {
                append("Imported $imported new Garmin ${if (imported == 1) "activity" else "activities"}")
                if (warnings.isNotEmpty()) append(" (${warnings.size} without FIT track)")
            }
        }
        progress(
            GarminSyncProgress(
                running = false,
                message = summaryText,
                current = imported,
                total = imported,
                warnings = warnings,
            ),
        )
    }

    /**
     * Prefer the stored DI session so the password is sent to Garmin only when there is
     * no usable access or refresh token left. A rejected session is dropped before SSO.
     */
    private suspend fun authenticate(
        client: GarminClient,
        prefs: AppSettings,
        progress: (GarminSyncProgress) -> Unit,
    ) {
        if (prefs.garminToken.isNotBlank()) {
            progress(GarminSyncProgress(running = true, message = "Resuming Garmin session…"))
            client.resumeSession(prefs.garminToken)
            if (client.probeSession()) return
            progress(GarminSyncProgress(running = true, message = "Refreshing Garmin session…"))
            if (client.refreshSession()) {
                settings.update { it.copy(garminToken = client.sessionToken) }
                if (client.probeSession()) return
            }
            client.resumeSession("")
            settings.update { it.copy(garminToken = "") }
        }
        progress(GarminSyncProgress(running = true, message = "Signing in to Garmin…"))
        client.login(prefs.garminUsername, prefs.garminPassword)
        settings.update { it.copy(garminEnabled = true, garminToken = client.sessionToken) }
    }

    private suspend fun mergeListExtras(summary: Activity) {
        val existing = db.activities().byId(summary.id) ?: return
        if (existing.deleted) return
        db.activities().upsert(existing.toModel().withListExtras(summary).toEntity())
    }

    suspend fun save(detail: ActivityDetail) {
        val existing = db.activities().byId(detail.activity.id)
        if (existing?.deleted == true) return
        db.replaceDetail(
            activity = detail.activity.copy(deleted = false).toEntity(),
            track = detail.track.map { it.toEntity() },
            laps = detail.laps.map { it.toEntity() },
        )
    }

    suspend fun rename(id: String, name: String) {
        val current = db.activities().byId(id) ?: return
        db.activities().upsert(current.copy(name = name))
    }

    suspend fun markDeleted(id: String) {
        db.activities().markDeleted(id)
    }

    suspend fun restore(id: String) {
        db.activities().restore(id)
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

private fun ActivityEntity.toModel() = sanitizeActivity(
    Activity(
        id, externalId, DataSource.valueOf(source), name, ActivityType.fromKey(type),
        startTimeMillis, location, distanceMeters, durationSeconds, movingSeconds,
        elevationGainMeters, calories, avgHeartRate, maxHeartRate, avgSpeedMps, maxSpeedMps,
        avgCadence, avgPower, maxPower, avgGrade, startLatitude, startLongitude, deviceName, hasTrack,
        minHeartRate, maxCadence, elevationLossMeters, normalizedPower, trainingStressScore,
        intensityFactor, avgTemperatureC, avgVerticalOscillationMm, avgStanceTimeMs, avgVerticalRatio,
        avgStepLengthMm, avgRespirationRate, aerobicTrainingEffect, anaerobicTrainingEffect, notes, deleted,
    ),
)

private fun Activity.toEntity() = ActivityEntity(
    id, externalId, source.name, name, type.key, startTimeMillis, location, distanceMeters,
    durationSeconds, movingSeconds, elevationGainMeters, calories, avgHeartRate, maxHeartRate,
    avgSpeedMps, maxSpeedMps, avgCadence, avgPower, maxPower, avgGrade, startLatitude, startLongitude,
    deviceName, hasTrack, minHeartRate, maxCadence, elevationLossMeters, normalizedPower,
    trainingStressScore, intensityFactor, avgTemperatureC, avgVerticalOscillationMm, avgStanceTimeMs,
    avgVerticalRatio, avgStepLengthMm, avgRespirationRate, aerobicTrainingEffect,
    anaerobicTrainingEffect, notes, deleted,
)

private fun TrackPointEntity.toModel() = TrackPoint(
    activityId, timestampMillis, elapsedSeconds, latitude, longitude, altitudeMeters,
    distanceMeters, speedMps, heartRate, cadence, power, gradePercent, temperatureC,
    verticalOscillationMm, stanceTimeMs, verticalRatio, stepLengthMm, leftRightBalancePercent,
    respirationRate,
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
    verticalOscillationMm = verticalOscillationMm,
    stanceTimeMs = stanceTimeMs,
    verticalRatio = verticalRatio,
    stepLengthMm = stepLengthMm,
    leftRightBalancePercent = leftRightBalancePercent,
    respirationRate = respirationRate,
)

private fun LapEntity.toModel() = sanitizeLap(
    Lap(
        activityId, index, startTimeMillis, durationSeconds, distanceMeters, avgHeartRate,
        maxHeartRate, avgSpeedMps, avgCadence, avgPower, elevationGainMeters, label,
    ),
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
