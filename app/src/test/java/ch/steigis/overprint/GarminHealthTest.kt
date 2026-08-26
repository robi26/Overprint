package ch.steigis.overprint

import ch.steigis.overprint.domain.model.DailyHealth
import ch.steigis.overprint.data.remote.garmin.healthDateChunks
import ch.steigis.overprint.data.remote.garmin.healthHistoryRange
import ch.steigis.overprint.data.remote.garmin.healthRecentRange
import ch.steigis.overprint.data.remote.garmin.healthSeriesToDownload
import ch.steigis.overprint.data.remote.garmin.mergeDailyHealth
import ch.steigis.overprint.data.remote.garmin.parseBodyBatteryReports
import ch.steigis.overprint.data.remote.garmin.parseDailySummary
import ch.steigis.overprint.data.remote.garmin.parseFloorsSeries
import ch.steigis.overprint.data.remote.garmin.parseFloorsStats
import ch.steigis.overprint.data.remote.garmin.parseHeartRateSeries
import ch.steigis.overprint.data.remote.garmin.parseRespirationSeries
import ch.steigis.overprint.data.remote.garmin.parseSleepStages
import ch.steigis.overprint.data.remote.garmin.parseSleepStats
import ch.steigis.overprint.data.remote.garmin.parseSpo2Series
import ch.steigis.overprint.data.remote.garmin.parseStepsChart
import ch.steigis.overprint.data.remote.garmin.parseStepsStats
import ch.steigis.overprint.data.remote.garmin.parseStressSeries
import ch.steigis.overprint.data.remote.garmin.parseWellnessStats
import ch.steigis.overprint.domain.model.HealthSeries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class GarminHealthTest {
    @Test
    fun splitsRangesIntoInclusiveChunks() {
        val chunks = healthDateChunks(
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 2, 10),
            maxDays = 28,
        )
        assertEquals(2, chunks.size)
        assertEquals(LocalDate.of(2026, 1, 1) to LocalDate.of(2026, 1, 28), chunks[0])
        assertEquals(LocalDate.of(2026, 1, 29) to LocalDate.of(2026, 2, 10), chunks[1])
    }

    @Test
    fun recentSyncIsTwoWeeksAndHistoryIsAManualChunkBeforeThat() {
        val today = LocalDate.of(2026, 8, 24)
        assertEquals(LocalDate.of(2026, 8, 11) to today, healthRecentRange(today))
        assertEquals(
            LocalDate.of(2026, 5, 13) to LocalDate.of(2026, 8, 10),
            healthHistoryRange(oldestStored = "2026-08-11", today = today),
        )
        val beforeSync = healthHistoryRange(oldestStored = null, today = today)
        assertEquals(LocalDate.of(2026, 5, 13), beforeSync.first)
        assertEquals(LocalDate.of(2026, 8, 10), beforeSync.second)
    }

    @Test
    fun downloadsOnlyMissingSeriesUnlessRefreshingToday() {
        val stored = setOf(HealthSeries.HEART_RATE, HealthSeries.STEPS)
        assertEquals(
            setOf(
                HealthSeries.STRESS,
                HealthSeries.BODY_BATTERY,
                HealthSeries.SLEEP,
                HealthSeries.SPO2,
                HealthSeries.RESPIRATION,
                HealthSeries.FLOORS,
            ),
            healthSeriesToDownload(stored, refreshAll = false),
        )
        assertEquals(HealthSeries.entries.toSet(), healthSeriesToDownload(stored, refreshAll = true))
        assertTrue(healthSeriesToDownload(HealthSeries.entries.toSet(), refreshAll = false).isEmpty())
    }

    @Test
    fun parsesDailySummaryScalarsAndCurveRollups() {
        val json = """
            {
              "calendarDate": "2026-08-20",
              "totalSteps": 10421,
              "dailyStepGoal": 10000,
              "totalDistanceMeters": 8123.4,
              "totalKilocalories": 2410,
              "activeKilocalories": 620,
              "bmrKilocalories": 1790,
              "restingHeartRate": 48,
              "minHeartRate": 42,
              "maxHeartRate": 168,
              "sleepingSeconds": 26100,
              "moderateIntensityMinutes": 22,
              "vigorousIntensityMinutes": 18,
              "averageStressLevel": 28,
              "maxStressLevel": 81,
              "bodyBatteryChargedValue": 64,
              "bodyBatteryDrainedValue": 41,
              "bodyBatteryHighestValue": 88,
              "bodyBatteryLowestValue": 22,
              "bodyBatteryMostRecentValue": 55,
              "floorsAscended": 12.0,
              "floorsDescended": 11.0,
              "userFloorsAscendedGoal": 10,
              "averageSpo2Value": 96,
              "lowestSpo2Value": 91,
              "avgWakingRespirationValue": 14.2,
              "highestRespirationValue": 18.0,
              "lowestRespirationValue": 10.0
            }
        """.trimIndent()
        val day = parseDailySummary(json)!!
        assertEquals("2026-08-20", day.date)
        assertEquals(10421.0, day.steps!!, 0.0)
        assertEquals(10000.0, day.stepGoal!!, 0.0)
        assertEquals(8123.4, day.distanceMeters!!, 0.01)
        assertEquals(2410.0, day.caloriesTotal!!, 0.0)
        assertEquals(620.0, day.caloriesActive!!, 0.0)
        assertEquals(1790.0, day.caloriesBmr!!, 0.0)
        assertEquals(48.0, day.restingHr!!, 0.0)
        assertEquals(42.0, day.minHr!!, 0.0)
        assertEquals(168.0, day.maxHr!!, 0.0)
        assertEquals(26100.0, day.sleepSeconds!!, 0.0)
        assertEquals(22.0, day.intensityModerate!!, 0.0)
        assertEquals(18.0, day.intensityVigorous!!, 0.0)
        assertEquals(28.0, day.stressAvg!!, 0.0)
        assertEquals(81.0, day.stressMax!!, 0.0)
        assertEquals(64.0, day.bodyBatteryCharged!!, 0.0)
        assertEquals(41.0, day.bodyBatteryDrained!!, 0.0)
        assertEquals(88.0, day.bodyBatteryHigh!!, 0.0)
        assertEquals(22.0, day.bodyBatteryLow!!, 0.0)
        assertEquals(55.0, day.bodyBatteryLatest!!, 0.0)
        assertEquals(12.0, day.floorsUp!!, 0.0)
        assertEquals(10.0, day.floorsGoal!!, 0.0)
        assertEquals(96.0, day.spo2Avg!!, 0.0)
        assertEquals(14.2, day.respirationAvg!!, 0.01)
    }

    @Test
    fun parsesStepsSleepAndWellnessRanges() {
        val steps = parseStepsStats(
            """[{"calendarDate":"2026-08-19","totalSteps":8000,"stepGoal":9000,"totalDistance":6400}]""",
        )
        assertEquals(8000.0, steps.single().steps!!, 0.0)
        assertEquals(6400.0, steps.single().distanceMeters!!, 0.0)

        val floors = parseFloorsStats(
            """
            [{"calendarDate":"2026-08-19","values":{
              "wellnessFloorsAscended":8,
              "wellnessFloorsDescended":7,
              "wellnessUserFloorsAscendedGoal":10
            }}]
            """.trimIndent(),
        )
        assertEquals(8.0, floors.single().floorsUp!!, 0.0)
        assertEquals(10.0, floors.single().floorsGoal!!, 0.0)

        val sleep = parseSleepStats(
            """
            {"individualStats":[{
              "calendarDate":"2026-08-19",
              "values":{
                "sleepTimeSeconds":27000,
                "deepSleepSeconds":5100,
                "lightSleepSeconds":15000,
                "remSleepSeconds":5400,
                "awakeSleepSeconds":1500,
                "averageSleepScore":{"value":82}
              }
            }]}
            """.trimIndent(),
        )
        assertEquals(27000.0, sleep.single().sleepSeconds!!, 0.0)
        assertEquals(5100.0, sleep.single().sleepDeepSeconds!!, 0.0)
        assertEquals(82.0, sleep.single().sleepScore!!, 0.0)

        val wellness = parseWellnessStats(
            """
            {"allMetrics":{"metricsMap":{
              "WELLNESS_RESTING_HEART_RATE":[{"calendarDate":"2026-08-19","value":49}],
              "WELLNESS_ACTIVE_CALORIES":[{"calendarDate":"2026-08-19","value":500}],
              "WELLNESS_BMR_CALORIES":[{"calendarDate":"2026-08-19","value":1700}]
            }}}
            """.trimIndent(),
        )
        val day = wellness.single()
        assertEquals(49.0, day.restingHr!!, 0.0)
        assertEquals(500.0, day.caloriesActive!!, 0.0)
        assertEquals(1700.0, day.caloriesBmr!!, 0.0)
        assertEquals(2200.0, day.caloriesTotal!!, 0.0)
    }

    @Test
    fun mergeFillsGapsAndKeepsExistingUnlessOverwrite() {
        val stored = DailyHealth(date = "2026-08-19", steps = 1000.0, restingHr = 50.0)
        val incoming = DailyHealth(date = "2026-08-19", steps = 9000.0, sleepSeconds = 25000.0, restingHr = 47.0)
        val filled = mergeDailyHealth(stored, incoming, overwrite = false)
        assertEquals(1000.0, filled.steps!!, 0.0)
        assertEquals(50.0, filled.restingHr!!, 0.0)
        assertEquals(25000.0, filled.sleepSeconds!!, 0.0)

        val replaced = mergeDailyHealth(stored, incoming, overwrite = true)
        assertEquals(9000.0, replaced.steps!!, 0.0)
        assertEquals(47.0, replaced.restingHr!!, 0.0)
        assertEquals(25000.0, replaced.sleepSeconds!!, 0.0)
    }

    @Test
    fun ignoresEmptySummary() {
        assertNull(parseDailySummary("{}"))
        assertTrue(parseStepsStats("[]").isEmpty())
    }

    @Test
    fun parsesHeartRatePairsAndDropsMissingReadings() {
        val samples = parseHeartRateSeries(
            """
            {
              "calendarDate": "2026-08-20",
              "heartRateValues": [[1724112000000, 62], [1724112060000, -1], [1724112120000, 64]]
            }
            """.trimIndent(),
            "2026-08-20",
        )
        assertEquals(listOf(62.0, 64.0), samples.map { it.value })
        assertEquals(1724112000000L, samples.first().timestampMillis)
        assertTrue(samples.all { it.metric == HealthSeries.HEART_RATE })
        assertEquals("2026-08-20", samples.first().date)
    }

    @Test
    fun parsesHeartRateOffsetMapFromStartGmt() {
        val samples = parseHeartRateSeries(
            """
            {
              "calendarDate": "2026-08-20",
              "startTimestampGMT": "2026-08-20T00:00:00.0",
              "timeOffsetHeartRateSamples": {"0": 58, "60": 59}
            }
            """.trimIndent(),
            "2026-08-20",
        )
        assertEquals(2, samples.size)
        assertEquals(58.0, samples[0].value, 0.0)
        assertEquals(59.0, samples[1].value, 0.0)
        assertEquals(60_000L, samples[1].timestampMillis - samples[0].timestampMillis)
    }

    @Test
    fun parsesStepsChartBuckets() {
        val samples = parseStepsChart(
            """
            [
              {"startGMT":"2026-08-20T00:00:00.0","endGMT":"2026-08-20T00:15:00.0","steps":40},
              {"startGMT":"2026-08-20T00:15:00.0","endGMT":"2026-08-20T00:30:00.0","steps":12}
            ]
            """.trimIndent(),
            "2026-08-20",
        )
        assertEquals(listOf(40.0, 12.0), samples.map { it.value })
        assertTrue(samples.all { it.metric == HealthSeries.STEPS })
        assertEquals(15 * 60 * 1000L, samples[1].timestampMillis - samples[0].timestampMillis)
    }

    @Test
    fun parsesStressAndEmbeddedBodyBattery() {
        val samples = parseStressSeries(
            """
            {
              "calendarDate": "2026-08-20",
              "stressValuesArray": [[1724112000000, 22], [1724112060000, -1], [1724112120000, 31]],
              "bodyBatteryValuesArray": [[1724112000000, 1, 71], [1724112120000, 1, 68]]
            }
            """.trimIndent(),
            "2026-08-20",
        )
        val stress = samples.filter { it.metric == HealthSeries.STRESS }
        val battery = samples.filter { it.metric == HealthSeries.BODY_BATTERY }
        assertEquals(listOf(22.0, 31.0), stress.map { it.value })
        assertEquals(listOf(71.0, 68.0), battery.map { it.value })
    }

    @Test
    fun parsesBodyBatteryDailyReports() {
        val samples = parseBodyBatteryReports(
            """
            [{
              "date": "2026-08-20",
              "bodyBatteryValuesArray": [[1724112000000, 0, 80], [1724115600000, 0, 55]]
            }]
            """.trimIndent(),
        )
        assertEquals("2026-08-20", samples.first().date)
        assertEquals(listOf(80.0, 55.0), samples.map { it.value })
        assertTrue(samples.all { it.metric == HealthSeries.BODY_BATTERY })
    }

    @Test
    fun parsesSleepStageWindows() {
        val samples = parseSleepStages(
            """
            {
              "dailySleepDTO": {
                "calendarDate": "2026-08-20",
                "sleepLevels": [
                  {"startGMT":"2026-08-19T22:00:00.0","endGMT":"2026-08-19T22:20:00.0","activityLevel":1.0},
                  {"startGMT":"2026-08-19T22:20:00.0","endGMT":"2026-08-19T23:00:00.0","activityLevel":2.0}
                ]
              }
            }
            """.trimIndent(),
            "2026-08-20",
        )
        assertEquals(listOf(1.0, 2.0), samples.map { it.value })
        assertTrue(samples.all { it.metric == HealthSeries.SLEEP })
        assertEquals(20 * 60 * 1000L, samples[1].timestampMillis - samples[0].timestampMillis)
    }

    @Test
    fun parsesSpo2HourlyAndRespirationAndFloors() {
        val spo2 = parseSpo2Series(
            """{"calendarDate":"2026-08-20","spO2HourlyAverages":[[1724112000000,96],[1724115600000,95]]}""",
            "2026-08-20",
        )
        assertEquals(listOf(96.0, 95.0), spo2.map { it.value })
        assertTrue(spo2.all { it.metric == HealthSeries.SPO2 })

        val breaths = parseRespirationSeries(
            """{"calendarDate":"2026-08-20","respirationValuesArray":[[1724112000000,14.5],[1724112060000,-1]]}""",
            "2026-08-20",
        )
        assertEquals(listOf(14.5), breaths.map { it.value })
        assertTrue(breaths.all { it.metric == HealthSeries.RESPIRATION })

        val floors = parseFloorsSeries(
            """{"calendarDate":"2026-08-20","floorValuesArray":[[1724112000000,0],[1724112900000,2]]}""",
            "2026-08-20",
        )
        assertEquals(listOf(0.0, 2.0), floors.map { it.value })
        assertTrue(floors.all { it.metric == HealthSeries.FLOORS })

        val garminFloors = parseFloorsSeries(
            """
            {"calendarDate":"2026-08-20","floorValuesArray":[
              ["2026-08-19T22:00:00.0","2026-08-19T22:15:00.0",0,0],
              ["2026-08-19T22:15:00.0","2026-08-19T22:30:00.0",2,1]
            ]}
            """.trimIndent(),
            "2026-08-20",
        )
        assertEquals(listOf(0.0, 2.0), garminFloors.map { it.value })
    }

    @Test
    fun emptySeriesBodiesYieldNoSamples() {
        assertTrue(parseHeartRateSeries("{}", "2026-08-20").isEmpty())
        assertTrue(parseStepsChart("[]", "2026-08-20").isEmpty())
        assertTrue(parseStressSeries("{}", "2026-08-20").isEmpty())
        assertTrue(parseBodyBatteryReports("[]").isEmpty())
        assertTrue(parseSleepStages("{}", "2026-08-20").isEmpty())
    }
}
