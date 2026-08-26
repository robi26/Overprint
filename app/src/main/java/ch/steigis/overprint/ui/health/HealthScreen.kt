package ch.steigis.overprint.ui.health

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ch.steigis.overprint.data.remote.garmin.GarminSyncProgress
import ch.steigis.overprint.domain.format.Formatters
import ch.steigis.overprint.domain.model.DailyHealth
import ch.steigis.overprint.domain.stats.TrendPoint
import ch.steigis.overprint.ui.components.ChartCard
import ch.steigis.overprint.ui.components.LineChart
import ch.steigis.overprint.ui.settings.GarminSyncStatus
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToInt

private enum class HealthTab(val label: String) {
    DAY("Day"),
    STATS("Stats"),
}

private enum class HealthRange(val label: String) {
    WEEK("1 week"),
    MONTH("1 month"),
    YEAR("1 year"),
    ALL("All"),
    ;

    fun startOn(end: LocalDate): LocalDate? = when (this) {
        WEEK -> end.minusDays(6)
        MONTH -> end.minusMonths(1).plusDays(1)
        YEAR -> end.minusYears(1).plusDays(1)
        ALL -> null
    }
}

private enum class HealthMetric(val label: String) {
    STEPS("Steps"),
    DISTANCE("Distance"),
    CALORIES("Calories"),
    SLEEP("Sleep"),
    SLEEP_SCORE("Sleep score"),
    RHR("Resting HR"),
    STRESS("Stress"),
    BODY_BATTERY("Body battery"),
    INTENSITY("Intensity"),
    FLOORS("Floors"),
    SPO2("SpO2"),
    RESPIRATION("Breathing"),
}

@Composable
fun HealthScreen(
    days: List<DailyHealth>,
    garminSync: GarminSyncProgress,
    fmt: Formatters,
    hasGarminCredentials: Boolean,
    onLoadOlder: () -> Unit,
) {
    var tab by remember { mutableStateOf(HealthTab.DAY) }
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Garmin refresh stores the last 14 days. Older months and years load here, 90 days at a time.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onLoadOlder,
                enabled = hasGarminCredentials && !garminSync.running,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (garminSync.running) "Loading health…" else "Load older 90 days")
            }
            if (!hasGarminCredentials) {
                Text(
                    "Add your Garmin email and password in Settings first.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            GarminSyncStatus(garminSync)
        }
        TabRow(selectedTabIndex = HealthTab.entries.indexOf(tab), divider = {}) {
            HealthTab.entries.forEach { item ->
                Tab(
                    selected = tab == item,
                    onClick = { tab = item },
                    text = { Text(item.label) },
                )
            }
        }
        when (tab) {
            HealthTab.DAY -> HealthDayTab(days, fmt)
            HealthTab.STATS -> HealthStatsTab(days, fmt)
        }
    }
}

@Composable
private fun HealthDayTab(days: List<DailyHealth>, fmt: Formatters) {
    var selectedDate by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(days) {
        selectedDate = when {
            days.isEmpty() -> null
            days.any { it.date == selectedDate } -> selectedDate
            else -> days.first().date
        }
    }
    val index = days.indexOfFirst { it.date == selectedDate }.takeIf { it >= 0 } ?: 0
    val day = days.getOrNull(index)
    val canGoOlder = index in 0 until days.lastIndex
    val canGoNewer = index > 0

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (day == null) {
            Text(
                "No daily health yet. Refresh activities to pull the last two weeks, or load older days above.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { selectedDate = days[index + 1].date },
                    enabled = canGoOlder,
                ) {
                    Icon(Icons.Outlined.ChevronLeft, contentDescription = "Previous day")
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        formatHealthDate(day.date),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        "${index + 1} of ${days.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = { selectedDate = days[index - 1].date },
                    enabled = canGoNewer,
                ) {
                    Icon(Icons.Outlined.ChevronRight, contentDescription = "Next day")
                }
            }
            DayHealthCard(day, fmt)
        }
    }
}

@Composable
private fun HealthStatsTab(days: List<DailyHealth>, fmt: Formatters) {
    var range by remember { mutableStateOf(HealthRange.MONTH) }
    var metric by remember { mutableStateOf(HealthMetric.STEPS) }
    val window = remember(days, range) { days.inRange(range) }
    val points = remember(window, metric, fmt.metric, range) {
        healthTrend(window, metric, fmt, range)
    }
    val latest = points.lastOrNull()?.value
    val average = points.takeIf { it.isNotEmpty() }?.map { it.value }?.average()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HealthRange.entries.forEach { item ->
                FilterChip(
                    selected = range == item,
                    onClick = { range = item },
                    label = { Text(item.label) },
                )
            }
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HealthMetric.entries.forEach { item ->
                FilterChip(
                    selected = metric == item,
                    onClick = { metric = item },
                    label = { Text(item.label) },
                )
            }
        }
        ChartCard(
            title = metric.label,
            headline = latest?.let { metric.format(it, fmt) },
            subtitle = when {
                points.isEmpty() -> "No ${metric.label.lowercase()} in this range. Load older days if needed."
                else -> buildString {
                    val unit = when (range) {
                        HealthRange.YEAR -> if (points.size == 1) "week" else "weeks"
                        HealthRange.ALL -> if (points.size == 1) "month" else "months"
                        else -> if (points.size == 1) "day" else "days"
                    }
                    append("${points.size} $unit")
                    average?.let { append(" · avg ${metric.format(it, fmt)}") }
                }
            },
        ) {
            LineChart(points, yFormatter = { metric.tick(it, fmt) })
        }
    }
}

@Composable
private fun DayHealthCard(day: DailyHealth, fmt: Formatters) {
    val rows = day.metricRows(fmt)
    ChartCard(
        title = "Daily health",
        headline = day.headline(fmt),
        subtitle = day.subtitle(fmt),
    ) {
        rows.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { (label, value) ->
                    Column(Modifier.weight(1f).padding(top = 6.dp)) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    }
                }
                if (pair.size == 1) {
                    Column(Modifier.weight(1f)) {}
                }
            }
        }
    }
}

private fun List<DailyHealth>.inRange(range: HealthRange): List<DailyHealth> {
    val end = mapNotNull { runCatching { LocalDate.parse(it.date) }.getOrNull() }.maxOrNull()
        ?: return this
    val start = range.startOn(end) ?: return this
    return filter { day ->
        val date = runCatching { LocalDate.parse(day.date) }.getOrNull() ?: return@filter false
        !date.isBefore(start) && !date.isAfter(end)
    }
}

private fun healthTrend(
    days: List<DailyHealth>,
    metric: HealthMetric,
    fmt: Formatters,
    range: HealthRange,
): List<TrendPoint> {
    val daily = days.asReversed().mapNotNull { day ->
        val value = metric.value(day, fmt) ?: return@mapNotNull null
        val date = runCatching { LocalDate.parse(day.date) }.getOrNull() ?: return@mapNotNull null
        TrendPoint(
            millis = date.toEpochDay() * 86_400_000L,
            value = value,
            label = date.format(range.labelPattern),
        )
    }
    return when (range) {
        HealthRange.YEAR -> groupByWeek(daily)
        HealthRange.ALL -> groupByMonth(daily)
        else -> daily
    }
}

private fun groupByWeek(points: List<TrendPoint>): List<TrendPoint> {
    if (points.isEmpty()) return emptyList()
    val weekFmt = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
    return points.groupBy { point ->
        LocalDate.ofEpochDay(point.millis / 86_400_000L)
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    }.toSortedMap().map { (weekStart, week) ->
        TrendPoint(
            millis = weekStart.toEpochDay() * 86_400_000L,
            value = week.map { it.value }.average(),
            label = weekStart.format(weekFmt),
        )
    }
}

private fun groupByMonth(points: List<TrendPoint>): List<TrendPoint> {
    if (points.isEmpty()) return emptyList()
    val monthFmt = DateTimeFormatter.ofPattern("MMM yy", Locale.getDefault())
    return points.groupBy { point ->
        LocalDate.ofEpochDay(point.millis / 86_400_000L).withDayOfMonth(1)
    }.toSortedMap().map { (monthStart, month) ->
        TrendPoint(
            millis = monthStart.toEpochDay() * 86_400_000L,
            value = month.map { it.value }.average(),
            label = monthStart.format(monthFmt),
        )
    }
}

private val HealthRange.labelPattern: DateTimeFormatter
    get() = when (this) {
        HealthRange.WEEK -> DateTimeFormatter.ofPattern("EEE", Locale.getDefault())
        HealthRange.MONTH -> DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
        HealthRange.YEAR -> DateTimeFormatter.ofPattern("MMM", Locale.getDefault())
        HealthRange.ALL -> DateTimeFormatter.ofPattern("MMM yy", Locale.getDefault())
    }

private fun HealthMetric.value(day: DailyHealth, fmt: Formatters): Double? = when (this) {
    HealthMetric.STEPS -> day.steps
    HealthMetric.DISTANCE -> day.distanceMeters?.let { fmt.kmToChartUnit(it / 1000.0) }
    HealthMetric.CALORIES -> day.caloriesTotal ?: day.caloriesActive
    HealthMetric.SLEEP -> day.sleepSeconds?.div(3600.0)
    HealthMetric.SLEEP_SCORE -> day.sleepScore
    HealthMetric.RHR -> day.restingHr
    HealthMetric.STRESS -> day.stressAvg
    HealthMetric.BODY_BATTERY -> day.bodyBatteryLatest
    HealthMetric.INTENSITY -> listOfNotNull(day.intensityModerate, day.intensityVigorous)
        .takeIf { it.isNotEmpty() }?.sum()
    HealthMetric.FLOORS -> day.floorsUp
    HealthMetric.SPO2 -> day.spo2Avg
    HealthMetric.RESPIRATION -> day.respirationAvg
}

private fun HealthMetric.format(value: Double, fmt: Formatters): String = when (this) {
    HealthMetric.STEPS, HealthMetric.FLOORS, HealthMetric.STRESS,
    HealthMetric.BODY_BATTERY, HealthMetric.SLEEP_SCORE, HealthMetric.INTENSITY,
    -> value.roundToInt().toString()
    HealthMetric.DISTANCE -> "${String.format(Locale.US, "%.1f", value)} ${fmt.distanceUnit()}"
    HealthMetric.CALORIES -> fmt.calories(value)
    HealthMetric.SLEEP -> fmt.duration(value * 3600.0)
    HealthMetric.RHR -> fmt.heartRate(value)
    HealthMetric.SPO2 -> "${value.roundToInt()}%"
    HealthMetric.RESPIRATION -> fmt.respiration(value)
}

private fun HealthMetric.tick(value: Double, fmt: Formatters): String = when (this) {
    HealthMetric.SLEEP, HealthMetric.DISTANCE -> String.format(Locale.US, "%.1f", value)
    else -> String.format(Locale.US, "%.0f", value)
}

private fun DailyHealth.headline(fmt: Formatters): String? {
    val stepText = steps?.roundToInt()?.let { count ->
        val goal = stepGoal?.roundToInt()
        if (goal != null && goal > 0) "$count / $goal steps" else "$count steps"
    }
    return stepText ?: sleepSeconds?.let { "Sleep ${fmt.duration(it)}" }
}

private fun DailyHealth.subtitle(fmt: Formatters): String? {
    val parts = buildList {
        sleepSeconds?.let { add("Sleep ${fmt.duration(it)}") }
        sleepScore?.let { add("Score ${it.roundToInt()}") }
        restingHr?.let { add("RHR ${it.roundToInt()}") }
        bodyBatteryLatest?.let { add("BB ${it.roundToInt()}") }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

private fun DailyHealth.metricRows(fmt: Formatters): List<Pair<String, String>> = buildList {
    distanceMeters?.let { add("Distance" to fmt.distance(it)) }
    caloriesTotal?.let { add("Calories" to fmt.calories(it)) }
    caloriesActive?.let { add("Active" to fmt.calories(it)) }
    minHr?.let { add("Min HR" to fmt.heartRate(it)) }
    maxHr?.let { add("Max HR" to fmt.heartRate(it)) }
    intensityMinutes()?.let { add("Intensity" to "$it min") }
    stressAvg?.let { add("Stress avg" to it.roundToInt().toString()) }
    stressMax?.let { add("Stress max" to it.roundToInt().toString()) }
    bodyBatteryHigh?.let { add("BB high" to it.roundToInt().toString()) }
    bodyBatteryLow?.let { add("BB low" to it.roundToInt().toString()) }
    floorsUp?.let { add("Floors up" to it.roundToInt().toString()) }
    spo2Avg?.let { add("SpO2" to "${it.roundToInt()}%") }
    respirationAvg?.let { add("Breathing" to fmt.respiration(it)) }
}

private fun DailyHealth.intensityMinutes(): Int? {
    val total = listOfNotNull(intensityModerate, intensityVigorous).sum()
    return total.takeIf { it > 0 }?.roundToInt()
}

private val healthDateFmt: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault())

private fun formatHealthDate(iso: String): String =
    runCatching { LocalDate.parse(iso).format(healthDateFmt) }.getOrDefault(iso)
