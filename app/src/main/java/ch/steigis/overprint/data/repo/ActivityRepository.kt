package ch.steigis.overprint.data.repo

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import ch.steigis.overprint.data.local.ActivityEntity
import ch.steigis.overprint.data.local.AppDatabase
import ch.steigis.overprint.data.local.LapEntity
import ch.steigis.overprint.data.local.TrackPointEntity
import ch.steigis.overprint.data.local.toEntity
import ch.steigis.overprint.data.local.toModel
import ch.steigis.overprint.data.parse.ActivityFileParser
import ch.steigis.overprint.data.prefs.AppSettings
import ch.steigis.overprint.data.prefs.SettingsStore
import ch.steigis.overprint.data.remote.garmin.GarminApiException
import ch.steigis.overprint.data.remote.garmin.GarminClient
import ch.steigis.overprint.data.remote.garmin.GarminSyncProgress
import ch.steigis.overprint.data.remote.garmin.garminReachedKnownHistory
import ch.steigis.overprint.data.remote.garmin.HEALTH_RELOAD_LIMIT_HOURS
import ch.steigis.overprint.data.remote.garmin.HEALTH_RELOAD_READY_MINUTES
import ch.steigis.overprint.data.remote.garmin.healthChartsPresent
import ch.steigis.overprint.data.remote.garmin.healthHistoryRange
import ch.steigis.overprint.data.remote.garmin.healthRecentRange
import ch.steigis.overprint.data.remote.garmin.healthReloadEligible
import ch.steigis.overprint.data.remote.garmin.healthReloadMessage
import ch.steigis.overprint.data.remote.garmin.healthSeriesToDownload
import ch.steigis.overprint.data.remote.garmin.mergeDailyHealth
import ch.steigis.overprint.domain.model.Activity
import ch.steigis.overprint.domain.model.ActivityDetail
import ch.steigis.overprint.domain.model.ActivityType
import ch.steigis.overprint.domain.model.DailyHealth
import ch.steigis.overprint.domain.model.HealthChartReload
import ch.steigis.overprint.domain.model.HealthReloadState
import ch.steigis.overprint.domain.model.DataSource
import ch.steigis.overprint.domain.model.HealthSample
import ch.steigis.overprint.domain.model.HealthSeries
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
import kotlin.coroutines.cancellation.CancellationException
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

    val dailyHealth: Flow<List<DailyHealth>> = db.health().observeAll().map { list ->
        list.map { it.toModel() }
    }

    suspend fun healthSamples(date: String): List<HealthSample> =
        db.healthSamples().forDate(date).map { it.toModel() }

    val healthReloads: Flow<List<HealthChartReload>> = db.healthReloads().observeAll().map { list ->
        list.map { it.toModel() }
    }

    /**
     * Download the day's missing detail curves. [force] asks for every metric again, which is
     * what a finished chart reload needs, and records whether Garmin actually had curves —
     * an older day usually has none until its charts are reloaded.
     */
    suspend fun syncHealthSeriesForDate(date: String, force: Boolean = false): Int {
        val prefs = settings.settings.first()
        if (!prefs.hasGarminCredentials) return 0
        val stored = db.healthSamples().metricsForDate(date)
            .mapNotNull { runCatching { HealthSeries.valueOf(it) }.getOrNull() }
            .toSet()
        val wanted = healthSeriesToDownload(stored, refreshAll = force)
        if (wanted.isEmpty()) return 0
        val day = java.time.LocalDate.parse(date)
        val client = GarminClient()
        authenticate(client, prefs) {}
        val series = client.pullHealthSeries(day, day) { if (it == date) wanted else emptySet() }
        // A metric Garmin answers with nothing keeps whatever is already stored: a reload that
        // comes back short must not delete curves the day already had.
        storeHealthSeries(series, overwriteDates = if (force) setOf(date) else emptySet())
        noteHealthChartOutcome(date, stored + series.map { it.metric })
        return series.size
    }

    /**
     * Garmin's "Reload Chart": queue the restore of one offloaded day. The curves arrive a few
     * minutes later, so the caller has to come back and pull them with [syncHealthSeriesForDate].
     */
    suspend fun requestHealthChartReload(date: String): HealthChartReload {
        val prefs = settings.settings.first()
        if (!prefs.hasGarminCredentials) {
            error("Enter your Garmin Connect email and password in Settings")
        }
        val client = GarminClient()
        authenticate(client, prefs) {}
        val state = client.requestHealthChartReload(date)
        val now = System.currentTimeMillis()
        val row = HealthChartReload(
            date = date,
            state = state,
            requestedAtMillis = now,
            checkedAtMillis = now,
            message = healthReloadMessage(state),
        )
        db.healthReloads().upsert(row.toEntity())
        return row
    }

    /**
     * Remember why a day has no curves: offloaded by Garmin, waiting on a queued reload,
     * or stored for good. A queued reload keeps its clock until Garmin has had its minutes.
     */
    private suspend fun noteHealthChartOutcome(date: String, present: Set<HealthSeries>) {
        val day = runCatching { java.time.LocalDate.parse(date) }.getOrNull() ?: return
        val now = System.currentTimeMillis()
        val existing = db.healthReloads().byDate(date)?.toModel()
        if (healthChartsPresent(present)) {
            db.healthReloads().upsert(
                HealthChartReload(
                    date = date,
                    state = HealthReloadState.LOADED,
                    requestedAtMillis = existing?.requestedAtMillis ?: 0L,
                    checkedAtMillis = now,
                ).toEntity(),
            )
            return
        }
        if (!healthReloadEligible(day)) return
        if (existing != null && healthReloadStillWaiting(existing, now)) {
            db.healthReloads().upsert(existing.copy(checkedAtMillis = now).toEntity())
            return
        }
        // A reload whose window has passed without curves means Garmin had nothing to send.
        val state = if (existing?.state == HealthReloadState.REQUESTED) {
            HealthReloadState.UNAVAILABLE
        } else {
            HealthReloadState.OFFLOADED
        }
        db.healthReloads().upsert(
            HealthChartReload(
                date = date,
                state = state,
                requestedAtMillis = existing?.requestedAtMillis ?: 0L,
                checkedAtMillis = now,
                message = healthReloadMessage(state),
            ).toEntity(),
        )
    }

    /** A queued reload, or a spent quota, keeps its state until its own clock runs out. */
    private fun healthReloadStillWaiting(row: HealthChartReload, nowMillis: Long): Boolean = when (row.state) {
        HealthReloadState.REQUESTED ->
            nowMillis - row.requestedAtMillis < HEALTH_RELOAD_READY_MINUTES * 60_000L
        HealthReloadState.LIMIT_REACHED ->
            nowMillis - row.requestedAtMillis < HEALTH_RELOAD_LIMIT_HOURS * 3_600_000L
        else -> false
    }

    /**
     * After a bulk pull, record which of the days that have totals are also missing their
     * curves, so the Health screen can offer the reload without another round trip.
     */
    private suspend fun noteHealthChartRange(start: java.time.LocalDate, end: java.time.LocalDate) {
        val byDate = db.healthSamples().presentMetrics(start.toString(), end.toString())
            .groupBy({ it.date }, { runCatching { HealthSeries.valueOf(it.metric) }.getOrNull() })
            .mapValues { (_, names) -> names.filterNotNull().toSet() }
        db.health().datesInRange(start.toString(), end.toString()).forEach { iso ->
            noteHealthChartOutcome(iso, byDate[iso].orEmpty())
        }
    }

    suspend fun syncDailyHealthForDate(date: String): DailyHealth? {
        val prefs = settings.settings.first()
        if (!prefs.hasGarminCredentials) return null
        val day = java.time.LocalDate.parse(date)
        val client = GarminClient()
        authenticate(client, prefs) {}
        val days = client.pullHealth(day, day, includeDailySummaries = true)
        val now = System.currentTimeMillis()
        days.forEach { incoming ->
            val existing = db.health().byDate(incoming.date)?.toModel()
            val merged = if (existing == null) incoming else mergeDailyHealth(existing, incoming, overwrite = true)
            db.health().upsert(merged.copy(updatedAtMillis = now).toEntity())
        }
        return db.health().byDate(date)?.toModel()
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
        runCatching {
            progress(GarminSyncProgress(running = true, message = "Updating recent health…"))
            val (start, end) = healthRecentRange()
            pullAndStoreHealth(client, start, end, refreshToday = true) { message ->
                progress(GarminSyncProgress(running = true, message = message, warnings = warnings.toList()))
            }
        }.onFailure { err ->
            warnings += "Health: ${err.message ?: err.javaClass.simpleName}"
        }
        progress(
            GarminSyncProgress(
                running = false,
                message = if (warnings.any { it.startsWith("Health:") }) "$summaryText (${warnings.last()})" else summaryText,
                current = imported,
                total = imported,
                warnings = warnings,
            ),
        )
    }

    /** Older months/years. Call from Health, not from the automatic activity sync. */
    suspend fun syncHealthHistory(progress: (GarminSyncProgress) -> Unit = {}) {
        val prefs = settings.settings.first()
        if (!prefs.hasGarminCredentials) {
            error("Enter your Garmin Connect email and password in Settings")
        }
        val client = GarminClient()
        authenticate(client, prefs, progress)
        val (start, end) = healthHistoryRange(db.health().oldestDate(), prefs.healthHistoryOldest)
        if (end.isBefore(start)) {
            progress(GarminSyncProgress(running = false, message = "No older health window to load"))
            return
        }
        progress(GarminSyncProgress(running = true, message = "Loading health $start – $end"))
        val stored = pullAndStoreHealth(client, start, end, refreshToday = false) { message ->
            progress(GarminSyncProgress(running = true, message = message))
        }
        // Move the walk back even when Garmin had nothing for the window, otherwise the next
        // press would ask for the same empty range again and never reach further history.
        settings.update { it.copy(healthHistoryOldest = start.toString()) }
        progress(
            GarminSyncProgress(
                running = false,
                message = if (stored == 0) {
                    "No Garmin health for $start – $end. Load again to go further back."
                } else {
                    "Stored $stored health ${if (stored == 1) "day" else "days"} ($start – $end)"
                },
                current = stored,
                total = stored,
            ),
        )
    }

    private suspend fun pullAndStoreHealth(
        client: GarminClient,
        start: java.time.LocalDate,
        end: java.time.LocalDate,
        refreshToday: Boolean,
        onProgress: (String) -> Unit,
    ): Int {
        val todayIso = java.time.LocalDate.now().toString()
        val knownDates = db.health().datesInRange(start.toString(), end.toString()).toSet()
        val summaryDates = buildSet {
            var cursor = start
            while (!cursor.isAfter(end)) {
                val iso = cursor.toString()
                if (iso == todayIso && refreshToday) add(iso)
                else if (iso !in knownDates) add(iso)
                cursor = cursor.plusDays(1)
            }
        }
        val days = client.pullHealth(
            start,
            end,
            includeDailySummaries = summaryDates.isNotEmpty(),
            summaryDates = summaryDates,
            onProgress = onProgress,
        )
        val now = System.currentTimeMillis()
        days.forEach { incoming ->
            val existing = db.health().byDate(incoming.date)?.toModel()
            val overwrite = refreshToday && incoming.date == todayIso
            val merged = if (existing == null) incoming else mergeDailyHealth(existing, incoming, overwrite)
            db.health().upsert(merged.copy(updatedAtMillis = now).toEntity())
        }
        val storedMetrics = db.healthSamples().presentMetrics(start.toString(), end.toString())
            .groupBy({ it.date }, { it.metric })
            .mapValues { (_, names) ->
                names.mapNotNull { runCatching { HealthSeries.valueOf(it) }.getOrNull() }.toSet()
            }
        runCatching {
            val series = client.pullHealthSeries(
                start,
                end,
                metricsForDate = { date ->
                    healthSeriesToDownload(
                        stored = storedMetrics[date].orEmpty(),
                        refreshAll = refreshToday && date == todayIso,
                    )
                },
                onProgress = onProgress,
            )
            storeHealthSeries(
                series,
                overwriteDates = if (refreshToday) setOf(todayIso) else emptySet(),
            )
            noteHealthChartRange(start, end)
        }.onFailure { err ->
            if (err is CancellationException) throw err
            if (err is GarminApiException && (err.isAuthFailure || err.httpCode == 429)) throw err
        }
        return days.size
    }

    private suspend fun storeHealthSeries(samples: List<HealthSample>, overwriteDates: Set<String>) {
        samples.groupBy { it.date to it.metric }.forEach { (key, points) ->
            val (date, metric) = key
            if (date !in overwriteDates && db.healthSamples().countFor(date, metric.name) > 0) return@forEach
            db.healthSamples().replaceMetric(date, metric.name, points.map { it.toEntity() })
        }
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
