package net.roz.connectstats

import net.roz.connectstats.domain.format.Formatters
import net.roz.connectstats.domain.model.Activity
import net.roz.connectstats.domain.model.ActivityType
import net.roz.connectstats.domain.model.DataSource
import net.roz.connectstats.domain.model.TrackPoint
import net.roz.connectstats.domain.stats.StatsEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StatsEngineTest {
    @Test
    fun bestRollingFindsFastestKilometre() {
        var dist = 0.0
        val track = (0..200).map { i ->
            val speed = if (i in 40..80) 4.0 else 3.0
            dist += speed
            TrackPoint(
                activityId = "t",
                timestampMillis = i * 1000L,
                elapsedSeconds = i.toDouble(),
                latitude = 47.0,
                longitude = 8.0,
                altitudeMeters = 400.0,
                distanceMeters = dist,
                speedMps = speed,
                heartRate = 150.0,
                cadence = 170.0,
                power = 200.0,
                gradePercent = 0.0,
                temperatureC = 16.0,
            )
        }
        val best = StatsEngine.bestRolling(track, listOf(1000.0))
        assertTrue(best.isNotEmpty())
        assertTrue(best.first().durationSeconds < 340.0)
    }

    @Test
    fun periodSummaryCountsThisWeek() {
        val now = System.currentTimeMillis()
        val acts = listOf(
            sample("a", now - 3600_000, 5000.0),
            sample("b", now - 40L * 24 * 3600_000, 10000.0),
        )
        val week = StatsEngine.periodSummaries(acts, now).first { it.label == "This week" }
        assertEquals(1, week.count)
        assertEquals(5000.0, week.distanceMeters, 0.1)
    }

    @Test
    fun formattersPaceAndDistance() {
        val fmt = Formatters(metric = true)
        assertTrue(fmt.distance(12500.0).contains("12.50"))
        assertEquals("5:00", fmt.duration(300.0))
        assertTrue(fmt.pace(300.0).startsWith("5:00"))
    }

    private fun sample(id: String, start: Long, distance: Double) = Activity(
        id = id,
        externalId = id,
        source = DataSource.DEMO,
        name = "Run",
        type = ActivityType.RUNNING,
        startTimeMillis = start,
        location = null,
        distanceMeters = distance,
        durationSeconds = 1800.0,
        movingSeconds = 1800.0,
        elevationGainMeters = 40.0,
        calories = 400.0,
        avgHeartRate = 150.0,
        maxHeartRate = 170.0,
        avgSpeedMps = 3.0,
        maxSpeedMps = 4.0,
        avgCadence = 170.0,
        avgPower = null,
        maxPower = null,
        avgGrade = null,
        startLatitude = null,
        startLongitude = null,
        deviceName = null,
        hasTrack = false,
    )
}
