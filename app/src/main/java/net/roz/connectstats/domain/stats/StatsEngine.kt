package net.roz.connectstats.domain.stats

import net.roz.connectstats.domain.model.Activity
import net.roz.connectstats.domain.model.ActivityType
import net.roz.connectstats.domain.model.Lap
import net.roz.connectstats.domain.model.TrackPoint
import net.roz.connectstats.domain.model.ZoneBucket
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

data class PeriodSummary(
    val label: String,
    val startMillis: Long,
    val endMillis: Long,
    val count: Int,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val elevationGain: Double,
    val calories: Double,
    val avgHeartRate: Double?,
    val byType: Map<ActivityType, TypeTotals>,
)

data class TypeTotals(
    val count: Int,
    val distanceMeters: Double,
    val durationSeconds: Double,
)

data class HistogramBin(val start: Double, val end: Double, val count: Int, val label: String)

data class ScatterPoint(val x: Double, val y: Double, val activityId: String, val type: ActivityType)

data class RollingBest(
    val distanceMeters: Double,
    val label: String,
    val durationSeconds: Double,
    val startElapsed: Double,
    val avgHeartRate: Double?,
    val avgPower: Double?,
)

data class TrendPoint(val millis: Long, val value: Double, val label: String)

object StatsEngine {

    fun periodSummaries(activities: List<Activity>, now: Long = System.currentTimeMillis()): List<PeriodSummary> {
        val cal = Calendar.getInstance(TimeZone.getDefault())
        cal.timeInMillis = now
        val weekStart = startOfWeek(cal.clone() as Calendar)
        val monthStart = startOfMonth(cal.clone() as Calendar)
        val yearStart = startOfYear(cal.clone() as Calendar)
        return listOf(
            summarize("This week", weekStart, now, activities),
            summarize("This month", monthStart, now, activities),
            summarize("Year to date", yearStart, now, activities),
            summarize("All time", 0L, now, activities),
        )
    }

    fun weeklyHistory(activities: List<Activity>, weeks: Int = 16, now: Long = System.currentTimeMillis()): List<TrendPoint> {
        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        val thisWeek = startOfWeek(cal)
        return (weeks - 1 downTo 0).map { back ->
            val start = thisWeek - back * 7L * 24 * 3600_000
            val end = start + 7L * 24 * 3600_000
            val slice = activities.filter { it.startTimeMillis in start until end }
            TrendPoint(start, slice.sumOf { it.distanceMeters } / 1000.0, "W${weeks - back}")
        }
    }

    fun monthlyHistory(activities: List<Activity>, months: Int = 12, now: Long = System.currentTimeMillis()): List<TrendPoint> {
        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        cal.set(Calendar.DAY_OF_MONTH, 1)
        startOfDay(cal)
        return (months - 1 downTo 0).map { back ->
            val c = cal.clone() as Calendar
            c.add(Calendar.MONTH, -back)
            val start = c.timeInMillis
            val n = c.clone() as Calendar
            n.add(Calendar.MONTH, 1)
            val slice = activities.filter { it.startTimeMillis in start until n.timeInMillis }
            TrendPoint(start, slice.sumOf { it.distanceMeters } / 1000.0, monthLabel(c))
        }
    }

    fun histogram(values: List<Double>, bins: Int = 12, unitLabel: String = ""): List<HistogramBin> {
        if (values.isEmpty()) return emptyList()
        val minV = values.min()
        val maxV = values.max()
        if (abs(maxV - minV) < 1e-6) {
            return listOf(HistogramBin(minV, maxV, values.size, formatBin(minV, maxV, unitLabel)))
        }
        val width = (maxV - minV) / bins
        return (0 until bins).map { i ->
            val start = minV + i * width
            val end = start + width
            val count = values.count { v ->
                if (i == bins - 1) v >= start && v <= end else v >= start && v < end
            }
            HistogramBin(start, end, count, formatBin(start, end, unitLabel))
        }
    }

    fun scatter(
        activities: List<Activity>,
        x: (Activity) -> Double?,
        y: (Activity) -> Double?,
    ): List<ScatterPoint> = activities.mapNotNull { a ->
        val xv = x(a) ?: return@mapNotNull null
        val yv = y(a) ?: return@mapNotNull null
        ScatterPoint(xv, yv, a.id, a.type)
    }

    fun timeInHrZones(track: List<TrackPoint>, maxHr: Double = 190.0): List<ZoneBucket> {
        val bounds = listOf(0.0, 0.60, 0.70, 0.80, 0.90, 1.05)
        val labels = listOf("Z1 Recovery", "Z2 Endurance", "Z3 Tempo", "Z4 Threshold", "Z5 VO2")
        val seconds = DoubleArray(5)
        track.zipWithNext().forEach { (a, b) ->
            val hr = a.heartRate ?: return@forEach
            val dt = (b.elapsedSeconds - a.elapsedSeconds).coerceIn(0.0, 30.0)
            val idx = bounds.indexOfLast { hr >= it * maxHr }.coerceIn(0, 4)
            seconds[idx] += dt
        }
        val total = seconds.sum().coerceAtLeast(1.0)
        return seconds.mapIndexed { i, s ->
            ZoneBucket(i + 1, labels[i], s, (s / total).toFloat())
        }
    }

    fun timeInPowerZones(track: List<TrackPoint>, ftp: Double = 250.0): List<ZoneBucket> {
        val bounds = listOf(0.0, 0.55, 0.75, 0.90, 1.05, 1.20, 1.50)
        val labels = listOf("Z1 Active", "Z2 Endurance", "Z3 Tempo", "Z4 Threshold", "Z5 VO2", "Z6 Anaerobic")
        val seconds = DoubleArray(6)
        track.zipWithNext().forEach { (a, b) ->
            val p = a.power ?: return@forEach
            val dt = (b.elapsedSeconds - a.elapsedSeconds).coerceIn(0.0, 30.0)
            val idx = bounds.indexOfLast { p >= it * ftp }.coerceIn(0, 5)
            seconds[idx] += dt
        }
        val total = seconds.sum().coerceAtLeast(1.0)
        return seconds.mapIndexed { i, s ->
            ZoneBucket(i + 1, labels[i], s, (s / total).toFloat())
        }
    }

    fun bestRolling(track: List<TrackPoint>, distancesMeters: List<Double> = defaultDistances): List<RollingBest> {
        if (track.size < 3) return emptyList()
        val dist = track.map { it.distanceMeters ?: 0.0 }
        val elapsed = track.map { it.elapsedSeconds }
        return distancesMeters.mapNotNull { target ->
            if ((dist.lastOrNull() ?: 0.0) < target * 0.95) return@mapNotNull null
            var best = Double.POSITIVE_INFINITY
            var bestStart = 0.0
            var i = 0
            for (j in track.indices) {
                while (i < j && dist[j] - dist[i] >= target) {
                    val dt = elapsed[j] - elapsed[i]
                    if (dt > 0 && dt < best) {
                        best = dt
                        bestStart = elapsed[i]
                    }
                    i++
                }
            }
            if (!best.isFinite()) return@mapNotNull null
            val window = track.filter { it.elapsedSeconds in bestStart..(bestStart + best) }
            RollingBest(
                distanceMeters = target,
                label = rollingLabel(target),
                durationSeconds = best,
                startElapsed = bestStart,
                avgHeartRate = window.mapNotNull { it.heartRate }.averageOrNull(),
                avgPower = window.mapNotNull { it.power }.averageOrNull(),
            )
        }
    }

    fun computedLaps(track: List<TrackPoint>, splitMeters: Double): List<Lap> {
        if (track.isEmpty() || splitMeters <= 0) return emptyList()
        val laps = mutableListOf<Lap>()
        var startIdx = 0
        var lap = 1
        val activityId = track.first().activityId
        for (i in 1 until track.size) {
            val d0 = track[startIdx].distanceMeters ?: 0.0
            val d1 = track[i].distanceMeters ?: 0.0
            if (d1 - d0 >= splitMeters || i == track.lastIndex) {
                val slice = track.subList(startIdx, i + 1)
                laps += lapFrom(activityId, lap, slice, "${lap}×${(splitMeters / 1000.0).let { if (it >= 1) "${it.toInt()} km" else "${splitMeters.roundToInt()} m" }}")
                lap++
                startIdx = i
            }
        }
        return laps
    }

    fun enrichTrack(track: List<TrackPoint>): List<TrackPoint> {
        if (track.size < 2) return track
        var lastDist = track.first().distanceMeters ?: 0.0
        var lastAlt = track.first().altitudeMeters
        return track.mapIndexed { index, p ->
            val prev = track.getOrNull(index - 1)
            val dist = p.distanceMeters ?: run {
                if (prev?.latitude != null && prev.longitude != null && p.latitude != null && p.longitude != null) {
                    lastDist + haversine(prev.latitude, prev.longitude, p.latitude, p.longitude)
                } else lastDist
            }
            lastDist = dist
            val speed = p.speedMps ?: prev?.let { pr ->
                val dt = (p.elapsedSeconds - pr.elapsedSeconds).coerceAtLeast(0.5)
                val dd = dist - (pr.distanceMeters ?: lastDist)
                (dd / dt).coerceAtLeast(0.0)
            }
            val grade = p.gradePercent ?: run {
                val alt = p.altitudeMeters
                val previousAlt = lastAlt
                if (prev != null && alt != null && previousAlt != null) {
                    val dd = dist - (prev.distanceMeters ?: dist)
                    if (dd > 2) ((alt - previousAlt) / dd) * 100.0 else null
                } else null
            }
            lastAlt = p.altitudeMeters ?: lastAlt
            p.copy(distanceMeters = dist, speedMps = speed, gradePercent = grade)
        }
    }

    fun summaryFromTrack(
        id: String,
        name: String,
        type: ActivityType,
        source: net.roz.connectstats.domain.model.DataSource,
        track: List<TrackPoint>,
        laps: List<Lap> = emptyList(),
    ): Activity {
        val enriched = enrichTrack(track)
        val start = enriched.firstOrNull()?.timestampMillis ?: System.currentTimeMillis()
        val distance = enriched.lastOrNull()?.distanceMeters ?: 0.0
        val duration = enriched.lastOrNull()?.elapsedSeconds ?: 0.0
        val elev = elevationGain(enriched)
        val hrs = enriched.mapNotNull { it.heartRate }
        val speeds = enriched.mapNotNull { it.speedMps }.filter { it > 0.3 }
        val cads = enriched.mapNotNull { it.cadence }
        val pwrs = enriched.mapNotNull { it.power }
        val startPt = enriched.firstOrNull { it.latitude != null }
        return Activity(
            id = id,
            externalId = id,
            source = source,
            name = name,
            type = type,
            startTimeMillis = start,
            location = null,
            distanceMeters = distance,
            durationSeconds = duration,
            movingSeconds = duration,
            elevationGainMeters = elev,
            calories = estimateCalories(type, duration, hrs.averageOrNull()),
            avgHeartRate = hrs.averageOrNull(),
            maxHeartRate = hrs.maxOrNull(),
            avgSpeedMps = if (duration > 0) distance / duration else speeds.averageOrNull(),
            maxSpeedMps = speeds.maxOrNull(),
            avgCadence = cads.averageOrNull(),
            avgPower = pwrs.averageOrNull(),
            maxPower = pwrs.maxOrNull(),
            avgGrade = enriched.mapNotNull { it.gradePercent }.averageOrNull(),
            startLatitude = startPt?.latitude,
            startLongitude = startPt?.longitude,
            deviceName = null,
            hasTrack = enriched.isNotEmpty(),
        )
    }

    fun elevationGain(track: List<TrackPoint>): Double {
        var gain = 0.0
        var last: Double? = null
        track.forEach { p ->
            val alt = p.altitudeMeters ?: return@forEach
            last?.let { if (alt > it) gain += alt - it }
            last = alt
        }
        return gain
    }

    private fun summarize(label: String, start: Long, end: Long, activities: List<Activity>): PeriodSummary {
        val slice = activities.filter { it.startTimeMillis in start..end }
        val byType = slice.groupBy { it.type }.mapValues { (_, list) ->
            TypeTotals(list.size, list.sumOf { it.distanceMeters }, list.sumOf { it.durationSeconds })
        }
        val hrs = slice.mapNotNull { it.avgHeartRate }
        return PeriodSummary(
            label = label,
            startMillis = start,
            endMillis = end,
            count = slice.size,
            distanceMeters = slice.sumOf { it.distanceMeters },
            durationSeconds = slice.sumOf { it.durationSeconds },
            elevationGain = slice.sumOf { it.elevationGainMeters ?: 0.0 },
            calories = slice.sumOf { it.calories ?: 0.0 },
            avgHeartRate = hrs.averageOrNull(),
            byType = byType,
        )
    }

    private fun lapFrom(activityId: String, index: Int, slice: List<TrackPoint>, label: String): Lap {
        val dist = (slice.last().distanceMeters ?: 0.0) - (slice.first().distanceMeters ?: 0.0)
        val dur = slice.last().elapsedSeconds - slice.first().elapsedSeconds
        return Lap(
            activityId = activityId,
            index = index,
            startTimeMillis = slice.first().timestampMillis,
            durationSeconds = dur.coerceAtLeast(0.0),
            distanceMeters = dist.coerceAtLeast(0.0),
            avgHeartRate = slice.mapNotNull { it.heartRate }.averageOrNull(),
            maxHeartRate = slice.mapNotNull { it.heartRate }.maxOrNull(),
            avgSpeedMps = if (dur > 0) dist / dur else null,
            avgCadence = slice.mapNotNull { it.cadence }.averageOrNull(),
            avgPower = slice.mapNotNull { it.power }.averageOrNull(),
            elevationGainMeters = elevationGain(slice),
            label = label,
        )
    }

    private fun startOfDay(cal: Calendar): Long {
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun startOfWeek(cal: Calendar): Long {
        cal.firstDayOfWeek = Calendar.MONDAY
        cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        return startOfDay(cal)
    }

    private fun startOfMonth(cal: Calendar): Long {
        cal.set(Calendar.DAY_OF_MONTH, 1)
        return startOfDay(cal)
    }

    private fun startOfYear(cal: Calendar): Long {
        cal.set(Calendar.MONTH, Calendar.JANUARY)
        cal.set(Calendar.DAY_OF_MONTH, 1)
        return startOfDay(cal)
    }

    private fun monthLabel(cal: Calendar): String {
        val names = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        return names[cal.get(Calendar.MONTH)]
    }

    private fun formatBin(a: Double, b: Double, unit: String): String {
        val u = if (unit.isBlank()) "" else " $unit"
        return "${a.roundToInt()}–${b.roundToInt()}$u"
    }

    private fun rollingLabel(meters: Double): String = when {
        meters >= 1000 -> "${(meters / 1000).roundToInt()} km"
        else -> "${meters.roundToInt()} m"
    }

    private fun estimateCalories(type: ActivityType, durationSec: Double, hr: Double?): Double {
        val met = when (type) {
            ActivityType.RUNNING -> 9.8
            ActivityType.CYCLING -> 7.5
            ActivityType.SWIMMING -> 8.0
            ActivityType.HIKING -> 6.0
            ActivityType.WALKING -> 3.5
            ActivityType.STRENGTH -> 5.0
            ActivityType.SKIING -> 7.0
            else -> 5.0
        }
        val kg = 75.0
        val hours = durationSec / 3600.0
        val base = met * kg * hours
        return if (hr != null) base * (0.85 + (hr / 190.0) * 0.3) else base
    }

    fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = Math.toRadians(lat2 - lat1)
        val dl = Math.toRadians(lon2 - lon1)
        val a = kotlin.math.sin(dp / 2) * kotlin.math.sin(dp / 2) +
            kotlin.math.cos(p1) * kotlin.math.cos(p2) *
            kotlin.math.sin(dl / 2) * kotlin.math.sin(dl / 2)
        return 2 * r * kotlin.math.asin(min(1.0, kotlin.math.sqrt(a)))
    }

    private fun List<Double>.averageOrNull(): Double? = if (isEmpty()) null else average()

    val defaultDistances = listOf(400.0, 1000.0, 1609.34, 5000.0, 10000.0, 21097.5)
}

fun withNormalizedElapsed(track: List<TrackPoint>): List<TrackPoint> {
    if (track.size < 2) return track
    val elapsedSpan = track.maxOf { it.elapsedSeconds } - track.minOf { it.elapsedSeconds }
    if (elapsedSpan >= 1e-3) return track
    val t0 = track.minOf { it.timestampMillis }
    if (track.maxOf { it.timestampMillis } - t0 < 1000L) return track
    return track.map { it.copy(elapsedSeconds = (it.timestampMillis - t0) / 1000.0) }
}

fun sanitizeFitUnits(track: List<TrackPoint>): List<TrackPoint> {
    if (track.isEmpty()) return track
    val speeds = track.mapNotNull { it.speedMps?.takeIf { s -> s.isFinite() } }
    val speedIsMmPerSec = speeds.size >= 2 && speeds.count { it > 80.0 } * 2 >= speeds.size
    val alts = track.mapNotNull { it.altitudeMeters?.takeIf { a -> a.isFinite() } }
    val altMedian = alts.sorted().getOrNull(alts.size / 2)
    val altSpan = if (alts.size >= 2) alts.max() - alts.min() else 0.0
    val altitudeIsFitRaw = altMedian != null && altMedian > 2400.0 &&
        (speedIsMmPerSec || altSpan < 600.0 && altMedian < 12_000.0)
    if (!speedIsMmPerSec && !altitudeIsFitRaw) return track
    return track.map { p ->
        val speed = p.speedMps?.let { s ->
            val metres = if (speedIsMmPerSec) s / 1000.0 else s
            metres.takeIf { it >= 0.0 && it <= 55.0 }
        }
        val alt = p.altitudeMeters?.let { a ->
            if (altitudeIsFitRaw) a / 5.0 - 500.0 else a
        }
        p.copy(speedMps = speed, altitudeMeters = alt)
    }
}

fun chartSeries(track: List<TrackPoint>, valueOf: (TrackPoint) -> Double?): List<Pair<Double, Double>> {
    val valued = track.mapNotNull { p -> valueOf(p)?.let { p to it } }
    if (valued.size < 2) return emptyList()
    val t0 = valued.minOf { it.first.timestampMillis }
    val t1 = valued.maxOf { it.first.timestampMillis }
    if (t1 - t0 >= 1000L) {
        return valued.map { ((it.first.timestampMillis - t0) / 1000.0) to it.second }
    }
    val e0 = valued.minOf { it.first.elapsedSeconds }
    val e1 = valued.maxOf { it.first.elapsedSeconds }
    if (e1 - e0 >= 1e-3) {
        return valued.map { it.first.elapsedSeconds to it.second }
    }
    return valued.mapIndexed { i, p -> i.toDouble() to p.second }
}

fun windowedSeries(
    series: List<Pair<Double, Double>>,
    startFrac: Float,
    endFrac: Float,
): List<Pair<Double, Double>> {
    if (series.size < 2) return series
    val minX = series.minOf { it.first }
    val maxX = series.maxOf { it.first }
    val span = (maxX - minX).coerceAtLeast(1e-3)
    val x0 = minX + span * startFrac.toDouble()
    val x1 = minX + span * endFrac.toDouble()
    var first = series.indexOfFirst { it.first >= x0 }
    if (first < 0) first = series.lastIndex
    if (first > 0) first -= 1
    var last = series.indexOfLast { it.first <= x1 }
    if (last < 0) last = 0
    if (last < series.lastIndex) last += 1
    if (last < first) return series
    return series.subList(first, last + 1)
}
