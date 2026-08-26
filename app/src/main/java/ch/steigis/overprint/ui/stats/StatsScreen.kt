package ch.steigis.overprint.ui.stats

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ch.steigis.overprint.domain.format.Formatters
import ch.steigis.overprint.domain.model.Activity
import ch.steigis.overprint.domain.model.ActivityType
import ch.steigis.overprint.domain.model.plausibleAvgHr
import ch.steigis.overprint.domain.model.plausibleDistanceKm
import ch.steigis.overprint.domain.model.plausibleDurationMinutes
import ch.steigis.overprint.domain.model.plausiblePaceSecPerKm
import ch.steigis.overprint.domain.model.plausiblePower
import ch.steigis.overprint.domain.stats.StatsEngine
import ch.steigis.overprint.domain.stats.TrendPoint
import ch.steigis.overprint.ui.common.SportAndYearFilters
import ch.steigis.overprint.ui.common.filterBySportAndYear
import ch.steigis.overprint.ui.components.BarChart
import ch.steigis.overprint.ui.components.ChartCard
import ch.steigis.overprint.ui.components.HistogramChart
import ch.steigis.overprint.ui.components.ScatterChart
import ch.steigis.overprint.ui.components.YearCompareChart
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

private enum class HistField(val label: String, val unit: String) {
    DISTANCE("Distance", "km"),
    DURATION("Duration", "min"),
    HR("HR", "bpm"),
    PACE("Pace", "s/km"),
    POWER("Power", "W"),
}

@Composable
fun StatsScreen(activities: List<Activity>, fmt: Formatters) {
    var type by remember { mutableStateOf<ActivityType?>(null) }
    var year by remember { mutableStateOf<Int?>(null) }
    var hist by remember { mutableStateOf(HistField.DISTANCE) }
    val byType = remember(activities, type) {
        if (type == null) activities else activities.filter { it.type == type }
    }
    val filtered = remember(activities, type, year) { filterBySportAndYear(activities, type, year) }
    val referenceNow = remember(year) { referenceTimeForYear(year) }
    val periods = remember(filtered, referenceNow) { StatsEngine.periodSummaries(filtered, referenceNow) }
    val weekly = remember(filtered, fmt.metric, referenceNow) {
        StatsEngine.weeklyHistory(filtered, now = referenceNow).map { it.copy(value = fmt.kmToChartUnit(it.value)) }
    }
    val monthly = remember(filtered, fmt.metric, referenceNow) {
        StatsEngine.monthlyHistory(filtered, now = referenceNow).map { it.copy(value = fmt.kmToChartUnit(it.value)) }
    }
    val histValues = remember(filtered, hist, fmt.metric) {
        filtered.mapNotNull {
            when (hist) {
                HistField.DISTANCE -> it.plausibleDistanceKm()?.let(fmt::kmToChartUnit)
                HistField.DURATION -> it.plausibleDurationMinutes()
                HistField.HR -> it.plausibleAvgHr()
                HistField.PACE -> it.plausiblePaceSecPerKm()
                HistField.POWER -> it.plausiblePower()
            }
        }
    }
    val histUnit = if (hist == HistField.DISTANCE) fmt.distanceUnit() else hist.unit
    val bins = remember(histValues, histUnit) {
        StatsEngine.histogram(histValues, unitLabel = histUnit)
    }
    val scatter = remember(filtered) {
        StatsEngine.scatter(filtered, x = { it.plausibleAvgHr() }, y = { it.plausiblePaceSecPerKm() })
    }
    val yearly = remember(byType, fmt.metric) {
        StatsEngine.yearlyCumulativeDistance(byType).map { s ->
            s.copy(
                points = s.points.map { it.first to fmt.kmToChartUnit(it.second) },
                totalKm = fmt.kmToChartUnit(s.totalKm),
            )
        }
    }
    val unit = fmt.distanceUnit()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SportAndYearFilters(
            activities = byType,
            type = type,
            year = year,
            onType = { type = it },
            onYear = { year = it },
        )
        periods.forEach { p ->
            ChartCard(
                title = p.label,
                headline = fmt.distance(p.distanceMeters),
                subtitle = buildString {
                    append("${p.count} activities · ${fmt.duration(p.durationSeconds)} · ${fmt.elevation(p.elevationGain)}")
                    if (p.byType.isNotEmpty()) {
                        append("\n")
                        append(p.byType.entries.joinToString("  ") { "${it.key.displayName} ${it.value.count}" })
                    }
                },
            )
        }
        ChartCard(
            title = "Weekly distance",
            headline = "${formatLatest(weekly)} $unit",
            subtitle = trendSubtitle(weekly, "week"),
        ) {
            BarChart(weekly, yFormatter = { String.format(Locale.US, "%.0f", it) })
        }
        ChartCard(
            title = "Monthly distance",
            headline = "${formatLatest(monthly)} $unit",
            subtitle = trendSubtitle(monthly, "month"),
        ) {
            BarChart(monthly, yFormatter = { String.format(Locale.US, "%.0f", it) })
        }
        ChartCard(
            title = "Distance (${unit})",
            headline = yearly.firstOrNull { it.isCurrent }?.let { String.format(Locale.US, "%.1f", it.totalKm) + " $unit" }
                ?: yearly.firstOrNull { it.isBest }?.let { String.format(Locale.US, "%.1f", it.totalKm) + " $unit" },
            subtitle = "Cumulative distance by day of year. Thick line is this year; shaded is the best year.",
        ) {
            YearCompareChart(yearly)
        }
        ChartCard(
            title = "Histogram",
            subtitle = "${hist.label} (${histUnit})",
        ) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HistField.entries.forEach { f ->
                    val chipUnit = if (f == HistField.DISTANCE) fmt.distanceUnit() else f.unit
                    FilterChip(
                        selected = hist == f,
                        onClick = { hist = f },
                        label = { Text("${f.label} ($chipUnit)") },
                    )
                }
            }
            HistogramChart(bins, xUnit = histUnit)
        }
        ChartCard(
            title = "Heart rate vs pace",
            subtitle = "Each point is an activity with a plausible heart rate and pace.",
        ) {
            ScatterChart(scatter)
        }
    }
}

private fun referenceTimeForYear(year: Int?): Long {
    if (year == null) return System.currentTimeMillis()
    val cal = Calendar.getInstance()
    if (year >= cal.get(Calendar.YEAR)) return System.currentTimeMillis()
    cal.set(Calendar.YEAR, year)
    cal.set(Calendar.MONTH, Calendar.DECEMBER)
    cal.set(Calendar.DAY_OF_MONTH, 31)
    cal.set(Calendar.HOUR_OF_DAY, 23)
    cal.set(Calendar.MINUTE, 59)
    cal.set(Calendar.SECOND, 59)
    cal.set(Calendar.MILLISECOND, 999)
    return cal.timeInMillis
}

private fun formatLatest(points: List<TrendPoint>): String {
    val last = points.lastOrNull()?.value ?: 0.0
    return String.format(Locale.US, "%.1f", last)
}

private fun trendSubtitle(points: List<TrendPoint>, period: String): String {
    val last = points.lastOrNull()?.value ?: return "No data yet"
    val prev = points.getOrNull(points.lastIndex - 1)?.value ?: return "Latest $period"
    if (prev < 0.05) return "Latest $period"
    val pct = ((last - prev) / prev * 100.0).roundToInt()
    val arrow = if (pct >= 0) "▲" else "▼"
    return "$arrow ${kotlin.math.abs(pct)}% vs previous $period"
}
