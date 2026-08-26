package ch.steigis.overprint

import ch.steigis.overprint.domain.model.DailyHealth
import ch.steigis.overprint.data.remote.garmin.healthDateChunks
import ch.steigis.overprint.data.remote.garmin.healthHistoryRange
import ch.steigis.overprint.data.remote.garmin.healthRecentRange
import ch.steigis.overprint.data.remote.garmin.mergeDailyHealth
import ch.steigis.overprint.data.remote.garmin.parseDailySummary
import ch.steigis.overprint.data.remote.garmin.parseSleepStats
import ch.steigis.overprint.data.remote.garmin.parseStepsStats
import ch.steigis.overprint.data.remote.garmin.parseWellnessStats
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
}
