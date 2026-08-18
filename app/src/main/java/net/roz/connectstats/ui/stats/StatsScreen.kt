package net.roz.connectstats.ui.stats

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.roz.connectstats.domain.format.Formatters
import net.roz.connectstats.domain.model.Activity
import net.roz.connectstats.domain.model.ActivityType
import net.roz.connectstats.domain.model.plausibleAvgHr
import net.roz.connectstats.domain.model.plausibleDistanceKm
import net.roz.connectstats.domain.model.plausibleDurationMinutes
import net.roz.connectstats.domain.model.plausiblePaceSecPerKm
import net.roz.connectstats.domain.model.plausiblePower
import net.roz.connectstats.domain.stats.StatsEngine
import net.roz.connectstats.domain.stats.TrendPoint
import net.roz.connectstats.ui.common.SportAndYearFilters
import net.roz.connectstats.ui.common.filterBySportAndYear
import net.roz.connectstats.ui.components.BarChart
import net.roz.connectstats.ui.components.ChartCard
import net.roz.connectstats.ui.components.HistogramChart
import net.roz.connectstats.ui.components.ScatterChart
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

private enum class HistField { DISTANCE, DURATION, HR, PACE, POWER }

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
    val histValues = remember(filtered, hist) {
        filtered.mapNotNull {
            when (hist) {
                HistField.DISTANCE -> it.plausibleDistanceKm()
                HistField.DURATION -> it.plausibleDurationMinutes()
                HistField.HR -> it.plausibleAvgHr()
                HistField.PACE -> it.plausiblePaceSecPerKm()
                HistField.POWER -> it.plausiblePower()
            }
        }
    }
    val bins = remember(histValues, hist) {
        val unit = when (hist) {
            HistField.DISTANCE -> "km"
            HistField.DURATION -> "min"
            HistField.HR -> "bpm"
            HistField.PACE -> "s"
            HistField.POWER -> "W"
        }
        StatsEngine.histogram(histValues, unitLabel = unit)
    }
    val scatter = remember(filtered) {
        StatsEngine.scatter(filtered, x = { it.plausibleAvgHr() }, y = { it.plausiblePaceSecPerKm() })
    }
    val unit = fmt.distanceUnit()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Statistics", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
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
        ChartCard(title = "Histogram") {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HistField.entries.forEach { f ->
                    FilterChip(
                        selected = hist == f,
                        onClick = { hist = f },
                        label = { Text(f.name.lowercase()) },
                    )
                }
            }
            HistogramChart(bins)
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
