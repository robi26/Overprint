package ch.steigis.overprint.data.remote.garmin

import ch.steigis.overprint.domain.model.DailyHealth
import ch.steigis.overprint.domain.model.HealthSample
import ch.steigis.overprint.domain.model.HealthSeries
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val json = Json { ignoreUnknownKeys = true }

internal const val HEALTH_RECENT_DAYS = 14
internal const val HEALTH_HISTORY_DAYS = 90

internal fun healthSeriesToDownload(
    stored: Set<HealthSeries>,
    refreshAll: Boolean,
): Set<HealthSeries> {
    if (refreshAll) return HealthSeries.entries.toSet()
    return HealthSeries.entries.filterNot { it in stored }.toSet()
}

internal fun healthRecentRange(today: LocalDate = LocalDate.now()): Pair<LocalDate, LocalDate> =
    today.minusDays((HEALTH_RECENT_DAYS - 1).toLong()) to today

/** Next older 90-day window. Automatic sync never uses this; Health screen does. */
internal fun healthHistoryRange(oldestStored: String?, today: LocalDate = LocalDate.now()): Pair<LocalDate, LocalDate> {
    val end = oldestStored?.let { LocalDate.parse(it).minusDays(1) }
        ?: today.minusDays(HEALTH_RECENT_DAYS.toLong())
    val start = end.minusDays((HEALTH_HISTORY_DAYS - 1).toLong())
    return start to end
}

internal fun healthDateChunks(
    start: LocalDate,
    end: LocalDate,
    maxDays: Int = 28,
): List<Pair<LocalDate, LocalDate>> {
    if (start.isAfter(end)) return emptyList()
    val chunks = mutableListOf<Pair<LocalDate, LocalDate>>()
    var cursor = start
    while (!cursor.isAfter(end)) {
        val chunkEnd = minOf(cursor.plusDays((maxDays - 1).toLong()), end)
        chunks += cursor to chunkEnd
        cursor = chunkEnd.plusDays(1)
    }
    return chunks
}

internal fun mergeDailyHealth(base: DailyHealth, extra: DailyHealth, overwrite: Boolean): DailyHealth {
    require(base.date == extra.date) { "Cannot merge ${base.date} with ${extra.date}" }
    fun Double?.pick(incoming: Double?): Double? = when {
        overwrite && incoming != null -> incoming
        this != null -> this
        else -> incoming
    }
    return DailyHealth(
        date = base.date,
        steps = base.steps.pick(extra.steps),
        stepGoal = base.stepGoal.pick(extra.stepGoal),
        distanceMeters = base.distanceMeters.pick(extra.distanceMeters),
        caloriesTotal = base.caloriesTotal.pick(extra.caloriesTotal),
        caloriesActive = base.caloriesActive.pick(extra.caloriesActive),
        caloriesBmr = base.caloriesBmr.pick(extra.caloriesBmr),
        restingHr = base.restingHr.pick(extra.restingHr),
        minHr = base.minHr.pick(extra.minHr),
        maxHr = base.maxHr.pick(extra.maxHr),
        sleepSeconds = base.sleepSeconds.pick(extra.sleepSeconds),
        sleepScore = base.sleepScore.pick(extra.sleepScore),
        sleepDeepSeconds = base.sleepDeepSeconds.pick(extra.sleepDeepSeconds),
        sleepLightSeconds = base.sleepLightSeconds.pick(extra.sleepLightSeconds),
        sleepRemSeconds = base.sleepRemSeconds.pick(extra.sleepRemSeconds),
        sleepAwakeSeconds = base.sleepAwakeSeconds.pick(extra.sleepAwakeSeconds),
        intensityModerate = base.intensityModerate.pick(extra.intensityModerate),
        intensityVigorous = base.intensityVigorous.pick(extra.intensityVigorous),
        stressAvg = base.stressAvg.pick(extra.stressAvg),
        stressMax = base.stressMax.pick(extra.stressMax),
        bodyBatteryCharged = base.bodyBatteryCharged.pick(extra.bodyBatteryCharged),
        bodyBatteryDrained = base.bodyBatteryDrained.pick(extra.bodyBatteryDrained),
        bodyBatteryHigh = base.bodyBatteryHigh.pick(extra.bodyBatteryHigh),
        bodyBatteryLow = base.bodyBatteryLow.pick(extra.bodyBatteryLow),
        bodyBatteryLatest = base.bodyBatteryLatest.pick(extra.bodyBatteryLatest),
        floorsUp = base.floorsUp.pick(extra.floorsUp),
        floorsDown = base.floorsDown.pick(extra.floorsDown),
        floorsGoal = base.floorsGoal.pick(extra.floorsGoal),
        spo2Avg = base.spo2Avg.pick(extra.spo2Avg),
        spo2Min = base.spo2Min.pick(extra.spo2Min),
        respirationAvg = base.respirationAvg.pick(extra.respirationAvg),
        respirationMin = base.respirationMin.pick(extra.respirationMin),
        respirationMax = base.respirationMax.pick(extra.respirationMax),
        updatedAtMillis = maxOf(base.updatedAtMillis, extra.updatedAtMillis),
    )
}

internal fun parseDailySummary(body: String): DailyHealth? {
    val root = body.jsonObjectOrNull() ?: return null
    val date = root.date() ?: return null
    return DailyHealth(
        date = date,
        steps = root.num("totalSteps", "steps"),
        stepGoal = root.num("dailyStepGoal", "stepGoal"),
        distanceMeters = root.num("totalDistanceMeters", "totalDistance", "distanceMeters"),
        caloriesTotal = root.num("totalKilocalories", "totalCalories"),
        caloriesActive = root.num("activeKilocalories", "activeCalories"),
        caloriesBmr = root.num("bmrKilocalories", "bmrCalories", "restingKilocalories"),
        restingHr = root.num("restingHeartRate"),
        minHr = root.num("minHeartRate"),
        maxHr = root.num("maxHeartRate"),
        sleepSeconds = root.num("sleepingSeconds", "sleepDurationInSeconds", "sleepTimeSeconds"),
        intensityModerate = root.num("moderateIntensityMinutes"),
        intensityVigorous = root.num("vigorousIntensityMinutes"),
        stressAvg = root.num("averageStressLevel", "avgStressLevel"),
        stressMax = root.num("maxStressLevel"),
        bodyBatteryCharged = root.num("bodyBatteryChargedValue"),
        bodyBatteryDrained = root.num("bodyBatteryDrainedValue"),
        bodyBatteryHigh = root.num("bodyBatteryHighestValue"),
        bodyBatteryLow = root.num("bodyBatteryLowestValue"),
        bodyBatteryLatest = root.num("bodyBatteryMostRecentValue", "bodyBatteryAtWakeTime"),
        floorsUp = root.num("floorsAscended", "floorsClimbed"),
        floorsDown = root.num("floorsDescended"),
        floorsGoal = root.num(
            "userFloorsAscendedGoal",
            "floorsAscendedGoal",
            "dailyFloorGoal",
            "wellnessUserFloorsAscendedGoal",
        ),
        spo2Avg = root.num("averageSpo2Value", "averageSpo2"),
        spo2Min = root.num("lowestSpo2Value", "lowestSpo2"),
        respirationAvg = root.num("avgWakingRespirationValue", "averageRespirationValue"),
        respirationMin = root.num("lowestRespirationValue"),
        respirationMax = root.num("highestRespirationValue"),
    ).takeUnless { it.isEmpty() }
}

internal fun parseStepsStats(body: String): List<DailyHealth> =
    body.jsonElements().mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val date = o.date() ?: return@mapNotNull null
        DailyHealth(
            date = date,
            steps = o.num("totalSteps", "steps"),
            stepGoal = o.num("stepGoal", "dailyStepGoal", "goal"),
            distanceMeters = o.num("totalDistance", "totalDistanceMeters", "distance"),
        ).takeUnless { it.isEmpty() }
    }

internal fun parseFloorsStats(body: String): List<DailyHealth> =
    body.jsonElements("individualStats").mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val values = o.obj("values") ?: o
        val date = o.date() ?: values.date() ?: return@mapNotNull null
        DailyHealth(
            date = date,
            floorsUp = values.num("wellnessFloorsAscended", "floorsAscended", "floorsUp"),
            floorsDown = values.num("wellnessFloorsDescended", "floorsDescended"),
            floorsGoal = values.num(
                "wellnessUserFloorsAscendedGoal",
                "userFloorsAscendedGoal",
                "floorsAscendedGoal",
            ),
        ).takeUnless { it.isEmpty() }
    }

internal fun parseSleepStats(body: String): List<DailyHealth> =
    body.jsonElements("individualStats", "dailySleepList").mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val values = o.obj("values") ?: o
        val date = o.date() ?: values.date() ?: return@mapNotNull null
        val score = values.obj("averageSleepScore", "sleepScoreOverall")?.num("value")
            ?: values.num("averageSleepScore", "sleepScore", "overallSleepScore")
        DailyHealth(
            date = date,
            sleepSeconds = values.num("sleepTimeSeconds", "sleepingSeconds", "durationInSeconds"),
            sleepScore = score,
            sleepDeepSeconds = values.num("deepSleepSeconds"),
            sleepLightSeconds = values.num("lightSleepSeconds"),
            sleepRemSeconds = values.num("remSleepSeconds"),
            sleepAwakeSeconds = values.num("awakeSleepSeconds", "awakeDurationInSeconds"),
        ).takeUnless { it.isEmpty() }
    }

internal fun parseWellnessStats(body: String): List<DailyHealth> {
    val root = body.jsonObjectOrNull() ?: return emptyList()
    val map = root.obj("allMetrics")?.obj("metricsMap")
        ?: root.obj("metricsMap")
        ?: return emptyList()
    val byDate = linkedMapOf<String, DailyHealth>()
    fun put(date: String, patch: DailyHealth) {
        val current = byDate[date] ?: DailyHealth(date)
        byDate[date] = mergeDailyHealth(current, patch, overwrite = false)
    }
    map.series("WELLNESS_RESTING_HEART_RATE").forEach { (date, value) ->
        put(date, DailyHealth(date, restingHr = value))
    }
    map.series("WELLNESS_TOTAL_STEP_GOAL").forEach { (date, value) ->
        put(date, DailyHealth(date, stepGoal = value))
    }
    map.series("WELLNESS_FLOORS_ASCENDED").forEach { (date, value) ->
        put(date, DailyHealth(date, floorsUp = value))
    }
    map.series("WELLNESS_USER_FLOORS_ASCENDED_GOAL").forEach { (date, value) ->
        put(date, DailyHealth(date, floorsGoal = value))
    }
    map.series("WELLNESS_ACTIVE_CALORIES").forEach { (date, value) ->
        put(date, DailyHealth(date, caloriesActive = value))
    }
    map.series("WELLNESS_BMR_CALORIES").forEach { (date, value) ->
        put(date, DailyHealth(date, caloriesBmr = value))
    }
    return byDate.values.map { day ->
        val total = listOfNotNull(day.caloriesActive, day.caloriesBmr).takeIf { it.isNotEmpty() }?.sum()
        if (day.caloriesTotal == null && total != null) day.copy(caloriesTotal = total) else day
    }
}

internal fun parseDisplayName(body: String): String? {
    val root = body.jsonObjectOrNull() ?: return null
    return root.str("displayName") ?: root.obj("userInfo")?.str("displayName")
}

internal fun parseHeartRateSeries(body: String, date: String): List<HealthSample> {
    val root = body.jsonObjectOrNull() ?: return emptyList()
    val day = root.date() ?: date
    val start = root.gmtMillis("startTimestampGMT", "startTimestampLocal")
    val pairs = root.pairs("heartRateValues")
        .ifEmpty { root.offsetMap("timeOffsetHeartRateSamples", start) }
    return pairs.toSamples(day, HealthSeries.HEART_RATE, dropNegative = true)
}

internal fun parseStepsChart(body: String, date: String): List<HealthSample> =
    body.jsonElements().mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val ts = o.gmtMillis("startGMT", "startTimestampGMT") ?: return@mapNotNull null
        val steps = o.num("steps") ?: return@mapNotNull null
        HealthSample(date, HealthSeries.STEPS, ts, steps)
    }

internal fun parseStressSeries(body: String, date: String): List<HealthSample> {
    val root = body.jsonObjectOrNull() ?: return emptyList()
    val day = root.date() ?: date
    return root.pairs("stressValuesArray").toSamples(day, HealthSeries.STRESS, dropNegative = true) +
        root.triples("bodyBatteryValuesArray").toSamples(day, HealthSeries.BODY_BATTERY, dropNegative = true)
}

internal fun parseBodyBatteryReports(body: String): List<HealthSample> =
    body.jsonElements().flatMap { el ->
        val o = el as? JsonObject ?: return@flatMap emptyList()
        val day = o.date() ?: return@flatMap emptyList()
        o.triples("bodyBatteryValuesArray").toSamples(day, HealthSeries.BODY_BATTERY, dropNegative = true)
    }

internal fun parseSleepStages(body: String, date: String): List<HealthSample> {
    val root = body.jsonObjectOrNull() ?: return emptyList()
    val dto = root.obj("dailySleepDTO") ?: root
    val day = dto.date() ?: date
    val levels = dto["sleepLevels"] as? JsonArray
        ?: root["sleepLevels"] as? JsonArray
        ?: return emptyList()
    return levels.mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val ts = o.gmtMillis("startGMT", "startTimeGMT", "startTimestampGMT") ?: return@mapNotNull null
        val stage = o.num("activityLevel", "level") ?: return@mapNotNull null
        HealthSample(day, HealthSeries.SLEEP, ts, stage)
    }
}

internal fun parseSpo2Series(body: String, date: String): List<HealthSample> {
    val root = body.jsonObjectOrNull() ?: return emptyList()
    val day = root.date() ?: date
    val start = root.gmtMillis("startTimeGMT", "sleepStartTimestampGMT", "startTimestampGMT")
    val pairs = root.pairs("spO2HourlyAverages", "spo2HourlyAverages")
        .ifEmpty { root.offsetMap("timeOffsetSleepSpo2", start) }
        .ifEmpty { root.offsetMap("timeOffsetSleepSpo2Values", start) }
    return pairs.toSamples(day, HealthSeries.SPO2, dropNegative = true)
}

internal fun parseRespirationSeries(body: String, date: String): List<HealthSample> {
    val root = body.jsonObjectOrNull() ?: return emptyList()
    val day = root.date() ?: date
    return root.pairs("respirationValuesArray").toSamples(day, HealthSeries.RESPIRATION, dropNegative = true)
}

internal fun parseFloorsSeries(body: String, date: String): List<HealthSample> {
    val root = body.jsonObjectOrNull() ?: return emptyList()
    val day = root.date() ?: date
    val start = root.gmtMillis("startTimestampGMT")
    val rows = (root["floorValuesArray"] as? JsonArray) ?: (root["floorsValuesArray"] as? JsonArray)
    val pairs = if (rows != null) {
        rows.mapNotNull { el ->
            val row = el as? JsonArray ?: return@mapNotNull null
            val ts = row.timeMillis(0) ?: return@mapNotNull null
            val ascended = row.number(2) ?: row.number(1) ?: return@mapNotNull null
            ts to ascended
        }
    } else {
        root.offsetMap("values", start)
    }
    return pairs.toSamples(day, HealthSeries.FLOORS, dropNegative = false)
}

private fun List<Pair<Long, Double>>.toSamples(
    date: String,
    metric: HealthSeries,
    dropNegative: Boolean,
): List<HealthSample> = mapNotNull { (ts, value) ->
    if (!value.isFinite()) return@mapNotNull null
    if (dropNegative && value < 0) return@mapNotNull null
    HealthSample(date, metric, ts, value)
}

private fun JsonObject.pairs(vararg keys: String): List<Pair<Long, Double>> {
    val rows = keys.firstNotNullOfOrNull { this[it] as? JsonArray } ?: return emptyList()
    return rows.mapNotNull { el ->
        val row = el as? JsonArray ?: return@mapNotNull null
        val ts = row.epochMillis(0) ?: return@mapNotNull null
        val value = row.number(1) ?: return@mapNotNull null
        ts to value
    }
}

private fun JsonObject.triples(key: String): List<Pair<Long, Double>> {
    val rows = this[key] as? JsonArray ?: return emptyList()
    return rows.mapNotNull { el ->
        val row = el as? JsonArray ?: return@mapNotNull null
        val ts = row.epochMillis(0) ?: return@mapNotNull null
        val value = row.number(2) ?: row.number(1) ?: return@mapNotNull null
        ts to value
    }
}

private fun JsonObject.offsetMap(key: String, startMillis: Long?): List<Pair<Long, Double>> {
    if (startMillis == null) return emptyList()
    val map = this[key] as? JsonObject ?: return emptyList()
    return map.entries.mapNotNull { (offset, el) ->
        val seconds = offset.toLongOrNull() ?: return@mapNotNull null
        val value = (el as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
        startMillis + seconds * 1000L to value
    }.sortedBy { it.first }
}

private fun JsonArray.epochMillis(index: Int): Long? {
    val n = number(index) ?: return null
    val ts = n.toLong()
    return if (ts in 1_000_000_000L until 1_000_000_000_000L) ts * 1000L else ts
}

private fun JsonArray.timeMillis(index: Int): Long? =
    epochMillis(index) ?: (getOrNull(index) as? JsonPrimitive)?.contentOrNull?.let(::parseGmtMillis)

private fun JsonArray.number(index: Int): Double? {
    val el = getOrNull(index) as? JsonPrimitive ?: return null
    return el.doubleOrNull ?: el.longOrNull?.toDouble() ?: el.contentOrNull?.toDoubleOrNull()
}

private fun JsonObject.gmtMillis(vararg keys: String): Long? =
    keys.firstNotNullOfOrNull { key -> str(key)?.let { parseGmtMillis(it) } }

internal fun parseGmtMillis(raw: String): Long? {
    raw.trim().toLongOrNull()?.let { ts ->
        return if (ts in 1_000_000_000L until 1_000_000_000_000L) ts * 1000L else ts
    }
    val trimmed = raw.trim().removeSuffix("Z")
    val local = runCatching {
        LocalDateTime.parse(trimmed, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
    }.getOrNull() ?: runCatching {
        LocalDateTime.parse(trimmed.replace(Regex("\\.\\d+$"), ""), DateTimeFormatter.ISO_LOCAL_DATE_TIME)
    }.getOrNull() ?: return null
    return local.toInstant(ZoneOffset.UTC).toEpochMilli()
}

private fun DailyHealth.isEmpty(): Boolean = listOf(
    steps, stepGoal, distanceMeters, caloriesTotal, caloriesActive, caloriesBmr,
    restingHr, minHr, maxHr, sleepSeconds, sleepScore, sleepDeepSeconds, sleepLightSeconds,
    sleepRemSeconds, sleepAwakeSeconds, intensityModerate, intensityVigorous, stressAvg, stressMax,
    bodyBatteryCharged, bodyBatteryDrained, bodyBatteryHigh, bodyBatteryLow, bodyBatteryLatest,
    floorsUp, floorsDown, floorsGoal, spo2Avg, spo2Min, respirationAvg, respirationMin, respirationMax,
).all { it == null }

private fun String.jsonObjectOrNull(): JsonObject? =
    runCatching { json.parseToJsonElement(this) }.getOrNull() as? JsonObject

private fun String.jsonElements(vararg keys: String): List<JsonElement> {
    val root = runCatching { json.parseToJsonElement(this) }.getOrNull() ?: return emptyList()
    return when (root) {
        is JsonArray -> root
        is JsonObject -> {
            keys.firstNotNullOfOrNull { key -> (root[key] as? JsonArray) }
                ?: (root["values"] as? JsonArray)
                ?: emptyList()
        }
        else -> emptyList()
    }
}

private fun JsonObject.obj(vararg keys: String): JsonObject? =
    keys.firstNotNullOfOrNull { (this[it] as? JsonObject) }

private fun JsonObject.str(key: String): String? =
    runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()?.takeIf { it.isNotBlank() && it != "null" }

private fun JsonObject.date(): String? =
    str("calendarDate") ?: str("date") ?: str("day")

private fun JsonObject.num(vararg keys: String): Double? {
    keys.forEach { key ->
        val el = this[key] ?: return@forEach
        when (el) {
            is JsonPrimitive -> {
                el.doubleOrNull?.takeIf { it.isFinite() }?.let { return it }
                el.contentOrNull?.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { return it }
            }
            is JsonObject -> el.num("value", "avg", "average")?.let { return it }
            else -> Unit
        }
    }
    return null
}

private fun JsonObject.series(key: String): List<Pair<String, Double>> {
    val rows = this[key] as? JsonArray ?: return emptyList()
    return rows.mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val date = o.date() ?: return@mapNotNull null
        val value = o.num("value") ?: return@mapNotNull null
        date to value
    }
}
