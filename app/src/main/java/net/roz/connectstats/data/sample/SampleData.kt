package net.roz.connectstats.data.sample

import net.roz.connectstats.domain.model.Activity
import net.roz.connectstats.domain.model.ActivityDetail
import net.roz.connectstats.domain.model.ActivityType
import net.roz.connectstats.domain.model.DataSource
import net.roz.connectstats.domain.model.Lap
import net.roz.connectstats.domain.model.TrackPoint
import net.roz.connectstats.domain.stats.StatsEngine
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

object SampleData {

    fun catalog(now: Long = System.currentTimeMillis()): List<ActivityDetail> {
        val rnd = Random(42)
        val details = mutableListOf<ActivityDetail>()
        var day = 0
        val plans = listOf(
            Triple(ActivityType.RUNNING, 10_000.0, "Morning run"),
            Triple(ActivityType.CYCLING, 42_000.0, "After-work ride"),
            Triple(ActivityType.RUNNING, 5_000.0, "Intervals"),
            Triple(ActivityType.SWIMMING, 2_000.0, "Pool swim"),
            Triple(ActivityType.HIKING, 12_000.0, "Weekend hike"),
            Triple(ActivityType.CYCLING, 80_000.0, "Long ride"),
            Triple(ActivityType.RUNNING, 21_097.0, "Half marathon"),
            Triple(ActivityType.WALKING, 6_000.0, "Recovery walk"),
            Triple(ActivityType.STRENGTH, 0.0, "Gym session"),
            Triple(ActivityType.RUNNING, 8_000.0, "Tempo run"),
        )
        repeat(28) { i ->
            val (type, dist, name) = plans[i % plans.size]
            day += if (i % 3 == 0) 1 else 2
            val start = now - day * 24L * 3600_000 - (7 + i % 5) * 3600_000L
            details += buildActivity(
                id = "demo-$i",
                name = name,
                type = type,
                start = start,
                targetDistance = if (type == ActivityType.STRENGTH) 0.0 else dist * (0.85 + rnd.nextDouble() * 0.3),
                rnd = Random(100 + i),
            )
        }
        return details
    }

    private fun buildActivity(
        id: String,
        name: String,
        type: ActivityType,
        start: Long,
        targetDistance: Double,
        rnd: Random,
    ): ActivityDetail {
        if (type == ActivityType.STRENGTH) {
            val duration = 2400.0 + rnd.nextDouble() * 900
            val activity = Activity(
                id = id,
                externalId = id,
                source = DataSource.DEMO,
                name = name,
                type = type,
                startTimeMillis = start,
                location = "Home gym",
                distanceMeters = 0.0,
                durationSeconds = duration,
                movingSeconds = duration,
                elevationGainMeters = 0.0,
                calories = 280 + rnd.nextDouble() * 80,
                avgHeartRate = 118 + rnd.nextDouble() * 15,
                maxHeartRate = 155.0,
                avgSpeedMps = null,
                maxSpeedMps = null,
                avgCadence = null,
                avgPower = null,
                maxPower = null,
                avgGrade = null,
                startLatitude = null,
                startLongitude = null,
                deviceName = "Demo",
                hasTrack = false,
            )
            return ActivityDetail(activity, emptyList(), emptyList())
        }

        val baseLat = 47.3769 + (rnd.nextDouble() - 0.5) * 0.04
        val baseLon = 8.5417 + (rnd.nextDouble() - 0.5) * 0.04
        val speed = when (type) {
            ActivityType.RUNNING -> 3.1 + rnd.nextDouble() * 0.5
            ActivityType.CYCLING -> 8.0 + rnd.nextDouble() * 1.5
            ActivityType.SWIMMING -> 1.15
            ActivityType.HIKING -> 1.4
            ActivityType.WALKING -> 1.5
            else -> 2.5
        }
        val duration = if (targetDistance > 0) targetDistance / speed else 3600.0
        val step = 5.0
        val n = (duration / step).toInt().coerceIn(40, 2500)
        val hrBase = when (type) {
            ActivityType.RUNNING -> 148.0
            ActivityType.CYCLING -> 138.0
            ActivityType.SWIMMING -> 142.0
            else -> 120.0
        }
        val points = ArrayList<TrackPoint>(n)
        var dist = 0.0
        var lat = baseLat
        var lon = baseLon
        var alt = 410.0 + rnd.nextDouble() * 40
        for (i in 0 until n) {
            val t = i * step
            val heading = (i / 40.0)
            val dlat = (speed * step / 111_320.0) * cos(heading)
            val dlon = (speed * step / (111_320.0 * cos(Math.toRadians(lat)))) * sin(heading * 0.7)
            if (type != ActivityType.SWIMMING) {
                lat += dlat
                lon += dlon
            }
            dist += speed * step * (0.96 + rnd.nextDouble() * 0.08)
            alt += sin(i / 18.0) * 1.6
            val hr = hrBase + 18 * sin(i / 30.0) + rnd.nextDouble() * 6
            val cad = when (type) {
                ActivityType.RUNNING, ActivityType.WALKING -> 164 + 8 * sin(i / 20.0)
                ActivityType.CYCLING -> 82 + 10 * sin(i / 25.0)
                else -> null
            }
            val pwr = if (type == ActivityType.CYCLING || type == ActivityType.RUNNING)
                180 + 70 * sin(i / 22.0) + rnd.nextDouble() * 20 else null
            points += TrackPoint(
                activityId = id,
                timestampMillis = start + (t * 1000).toLong(),
                elapsedSeconds = t,
                latitude = if (type == ActivityType.SWIMMING) null else lat,
                longitude = if (type == ActivityType.SWIMMING) null else lon,
                altitudeMeters = if (type == ActivityType.SWIMMING) null else alt,
                distanceMeters = dist,
                speedMps = speed * (0.9 + 0.2 * sin(i / 14.0)),
                heartRate = hr,
                cadence = cad,
                power = pwr,
                gradePercent = sin(i / 18.0) * 4,
                temperatureC = 16.0,
            )
        }
        val track = StatsEngine.enrichTrack(points)
        val location = when (type) {
            ActivityType.SWIMMING -> "Sportbad"
            ActivityType.HIKING -> "Uetliberg"
            else -> "Zürich"
        }
        val activity = StatsEngine.summaryFromTrack(id, name, type, DataSource.DEMO, track).copy(
            location = location,
            deviceName = "Demo Forerunner",
        )
        val split = when (type) {
            ActivityType.SWIMMING -> 100.0
            ActivityType.CYCLING -> 5000.0
            else -> 1000.0
        }
        val laps = StatsEngine.computedLaps(track, split)
        return ActivityDetail(activity, track, laps)
    }
}
