package net.roz.connectstats

import net.roz.connectstats.domain.format.Formatters
import net.roz.connectstats.domain.model.Activity
import net.roz.connectstats.domain.model.ActivityType
import net.roz.connectstats.domain.model.ChartMetric
import net.roz.connectstats.domain.model.plausibleAvgHr
import net.roz.connectstats.domain.model.plausiblePaceSecPerKm
import net.roz.connectstats.domain.model.DataSource
import net.roz.connectstats.domain.model.TrackPoint
import net.roz.connectstats.domain.model.chartValue
import net.roz.connectstats.domain.model.unit
import net.roz.connectstats.domain.stats.StatsEngine
import net.roz.connectstats.domain.stats.chartSeries
import net.roz.connectstats.domain.stats.sanitizeActivity
import net.roz.connectstats.domain.stats.sanitizeFitUnits
import net.roz.connectstats.domain.stats.windowedSeries
import net.roz.connectstats.domain.stats.withNormalizedElapsed
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

    @Test
    fun chartSeriesUsesTimestampsWhenElapsedIsStuckAtZero() {
        val track = (0..4).map { i ->
            TrackPoint(
                activityId = "t",
                timestampMillis = 1_700_000_000_000L + i * 5_000L,
                elapsedSeconds = 0.0,
                latitude = 47.0,
                longitude = 8.0,
                altitudeMeters = 400.0 + i,
                distanceMeters = i * 15.0,
                speedMps = 3.0,
                heartRate = 140.0 + i,
                cadence = 170.0,
                power = null,
                gradePercent = 0.0,
                temperatureC = 16.0,
            )
        }
        val series = chartSeries(track) { it.heartRate }
        assertEquals(5, series.size)
        assertEquals(0.0, series.first().first, 0.01)
        assertEquals(20.0, series.last().first, 0.01)
        assertEquals(144.0, series.last().second, 0.01)
        val fixed = withNormalizedElapsed(track)
        assertEquals(20.0, fixed.last().elapsedSeconds, 0.01)
    }

    @Test
    fun chartSeriesIsEmptyWhenMetricMissing() {
        val track = (0..3).map { i ->
            TrackPoint(
                activityId = "t",
                timestampMillis = i * 1000L,
                elapsedSeconds = i.toDouble(),
                latitude = null,
                longitude = null,
                altitudeMeters = null,
                distanceMeters = null,
                speedMps = null,
                heartRate = null,
                cadence = null,
                power = null,
                gradePercent = null,
                temperatureC = null,
            )
        }
        assertTrue(chartSeries(track) { it.heartRate }.isEmpty())
    }

    @Test
    fun chartValueUsesKilometresPerHourAndMetres() {
        val point = TrackPoint(
            activityId = "t",
            timestampMillis = 0L,
            elapsedSeconds = 0.0,
            latitude = 47.0,
            longitude = 8.0,
            altitudeMeters = 412.0,
            distanceMeters = 0.0,
            speedMps = 5.0,
            heartRate = 150.0,
            cadence = 90.0,
            power = 200.0,
            gradePercent = 3.5,
            temperatureC = 16.0,
        )
        assertEquals(18.0, point.chartValue(ChartMetric.SPEED)!!, 0.01)
        assertEquals(412.0, point.chartValue(ChartMetric.ELEVATION)!!, 0.01)
        assertEquals("km/h", ChartMetric.SPEED.unit())
        assertEquals("m", ChartMetric.ELEVATION.unit())
        assertEquals("bpm", ChartMetric.HEART_RATE.unit())
    }

    @Test
    fun windowedSeriesKeepsEdgePoints() {
        val series = (0..10).map { it.toDouble() to it.toDouble() }
        val slice = windowedSeries(series, 0.4f, 0.6f)
        assertTrue(slice.first().first <= 4.0)
        assertTrue(slice.last().first >= 6.0)
        assertTrue(slice.size >= 3)
    }

    @Test
    fun sanitizeFitUnitsDecodesEnhancedSpeedAndAltitude() {
        val track = (0..9).map { i ->
            samplePoint(
                speed = 3_000.0 + i * 1_200.0,
                altitude = 4_200.0 + i * 20.0,
            )
        }
        val fixed = sanitizeFitUnits(track)
        assertEquals(3.0, fixed.first().speedMps!!, 0.01)
        assertEquals(15.6, fixed.last().speedMps!!, 0.01)
        assertEquals(340.0, fixed.first().altitudeMeters!!, 0.1)
        assertEquals(376.0, fixed.last().altitudeMeters!!, 0.1)
        assertEquals(10.8, fixed.first().chartValue(ChartMetric.SPEED)!!, 0.1)
    }

    @Test
    fun sanitizeFitUnitsLeavesAlreadyDecodedValues() {
        val track = (0..9).map { i ->
            samplePoint(speed = 3.0 + i * 0.2, altitude = 410.0 + i)
        }
        val fixed = sanitizeFitUnits(track)
        assertEquals(3.0, fixed.first().speedMps!!, 0.01)
        assertEquals(410.0, fixed.first().altitudeMeters!!, 0.1)
    }

    @Test
    fun sanitizeFitUnitsLeavesMountainAltitudeWhenSpeedIsInMetres() {
        val track = (0..9).map { i ->
            samplePoint(speed = 1.4, altitude = 2_400.0 + i * 80.0)
        }
        val fixed = sanitizeFitUnits(track)
        assertEquals(2400.0, fixed.first().altitudeMeters!!, 0.1)
        assertEquals(1.4, fixed.first().speedMps!!, 0.01)
    }

    @Test
    fun sanitizeActivityDecodesMillisecondDurationAndMmSpeed() {
        val raw = sample("ride", 0L, 18_300.0).copy(
            durationSeconds = 1_999_379.0,
            movingSeconds = 1_999_379.0,
            avgSpeedMps = 9_155.0,
            maxSpeedMps = 15_000.0,
            avgPower = 168.0,
        )
        val fixed = sanitizeActivity(raw)
        assertEquals(1999.379, fixed.durationSeconds, 0.01)
        assertEquals(9.155, fixed.avgSpeedMps!!, 0.01)
        assertEquals(15.0, fixed.maxSpeedMps!!, 0.01)
        assertEquals(336.0, fixed.workKj!!, 2.0)
    }

    @Test
    fun plausiblePaceDropsTinyDistanceAndInsaneDuration() {
        val tiny = sample("tiny", 0L, 80.0)
        val insane = sample("insane", 0L, 5_000.0).copy(durationSeconds = 100_000.0)
        val normal = sample("ok", 0L, 5_000.0)
        val fastRide = sample("ride", 0L, 20_000.0).copy(
            type = ActivityType.CYCLING,
            durationSeconds = 1_800.0,
        )
        assertEquals(null, tiny.plausiblePaceSecPerKm())
        assertEquals(null, insane.plausiblePaceSecPerKm())
        assertEquals(360.0, normal.plausiblePaceSecPerKm()!!, 0.1)
        assertEquals(90.0, fastRide.plausiblePaceSecPerKm()!!, 0.1)
    }

    @Test
    fun scatterSkipsImplausibleHeartRateAndPace() {
        val ok = sample("ok", 0L, 5_000.0)
        val badHr = ok.copy(id = "hr", externalId = "hr", avgHeartRate = 8.0)
        val badPace = ok.copy(id = "pace", externalId = "pace", durationSeconds = 100_000.0)
        val points = StatsEngine.scatter(
            listOf(ok, badHr, badPace),
            x = { it.plausibleAvgHr() },
            y = { it.plausiblePaceSecPerKm() },
        )
        assertEquals(1, points.size)
        assertEquals("ok", points.single().activityId)
    }

    @Test
    fun sanitizeActivityLeavesNormalRide() {
        val raw = sample("ride", 0L, 18_300.0).copy(
            durationSeconds = 2000.0,
            avgSpeedMps = 9.15,
        )
        val fixed = sanitizeActivity(raw)
        assertEquals(2000.0, fixed.durationSeconds, 0.01)
        assertEquals(9.15, fixed.avgSpeedMps!!, 0.01)
    }

    private fun samplePoint(speed: Double, altitude: Double) = TrackPoint(
        activityId = "t",
        timestampMillis = 0L,
        elapsedSeconds = 0.0,
        latitude = 47.0,
        longitude = 8.0,
        altitudeMeters = altitude,
        distanceMeters = 0.0,
        speedMps = speed,
        heartRate = 150.0,
        cadence = 90.0,
        power = null,
        gradePercent = 0.0,
        temperatureC = 16.0,
    )

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
