package ch.steigis.overprint.ui.health

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ch.steigis.overprint.data.remote.garmin.GarminSyncProgress
import ch.steigis.overprint.domain.format.Formatters
import ch.steigis.overprint.domain.model.DailyHealth
import ch.steigis.overprint.domain.model.HealthSample
import ch.steigis.overprint.domain.stats.TrendPoint
import ch.steigis.overprint.ui.components.BarChart
import ch.steigis.overprint.ui.components.ChartCard
import ch.steigis.overprint.ui.components.ChartLine
import ch.steigis.overprint.ui.components.LineChart
import ch.steigis.overprint.ui.settings.GarminSyncStatus
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
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

private enum class HealthChartStyle { BARS, LINE }

private enum class HealthMetric(val label: String) {
    STEPS("Steps"),
    DISTANCE("Distance"),
    CALORIES("Calories"),
    SLEEP("Sleep"),
    SLEEP_SCORE("Sleep score"),
    HEART_RATE("Heart rate"),
    STRESS("Stress"),
    BODY_BATTERY("Body battery"),
    INTENSITY("Intensity minutes"),
    FLOORS("Floors"),
    SPO2("SpO2"),
    RESPIRATION("Breathing"),
    ;

    val style: HealthChartStyle
        get() = when (this) {
            STEPS, DISTANCE, CALORIES, SLEEP, INTENSITY, FLOORS -> HealthChartStyle.BARS
            SLEEP_SCORE, HEART_RATE, STRESS, BODY_BATTERY, SPO2, RESPIRATION -> HealthChartStyle.LINE
        }

    val color: Color
        get() = when (this) {
            STEPS -> Color(0xFF3583F3)
            DISTANCE -> Color(0xFF5B8C5A)
            CALORIES -> Color(0xFFE67E22)
            SLEEP -> Color(0xFF6C5CE7)
            SLEEP_SCORE -> Color(0xFF9B7EDE)
            HEART_RATE -> Color(0xFFE24B4B)
            STRESS -> Color(0xFFF5A524)
            BODY_BATTERY -> Color(0xFF2BB673)
            INTENSITY -> Color(0xFFF169EF)
            FLOORS -> Color(0xFFC8A26A)
            SPO2 -> Color(0xFF2AA5C9)
            RESPIRATION -> Color(0xFF7C8CFF)
        }

    val yMin: Double?
        get() = when (this) {
            STRESS, BODY_BATTERY, SLEEP_SCORE -> 0.0
            SPO2 -> 80.0
            else -> null
        }

    val yMax: Double?
        get() = when (this) {
            STRESS, BODY_BATTERY, SLEEP_SCORE, SPO2 -> 100.0
            else -> null
        }
}

private val HealthStatsOrder = listOf(
    HealthMetric.HEART_RATE,
    HealthMetric.STEPS,
    HealthMetric.FLOORS,
    HealthMetric.BODY_BATTERY,
    HealthMetric.SLEEP,
    HealthMetric.STRESS,
    HealthMetric.INTENSITY,
    HealthMetric.DISTANCE,
    HealthMetric.CALORIES,
    HealthMetric.SLEEP_SCORE,
    HealthMetric.SPO2,
    HealthMetric.RESPIRATION,
)

@Composable
fun HealthScreen(
    days: List<DailyHealth>,
    samples: List<HealthSample>,
    samplesDate: String?,
    seriesLoading: Boolean,
    summaryLoading: Boolean,
    healthDate: String?,
    garminSync: GarminSyncProgress,
    fmt: Formatters,
    onLoadSamples: (String?) -> Unit,
    onHealthDate: (String?) -> Unit,
) {
    var tab by remember { mutableStateOf(HealthTab.DAY) }
    Column(Modifier.fillMaxSize()) {
        GarminSyncStatus(garminSync, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
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
            HealthTab.DAY -> HealthDayTab(
                days, samples, samplesDate, seriesLoading, summaryLoading, garminSync.running, healthDate, fmt,
                onLoadSamples, onHealthDate,
            )
            HealthTab.STATS -> HealthStatsTab(days, fmt)
        }
    }
}

@Composable
private fun HealthDayTab(
    days: List<DailyHealth>,
    samples: List<HealthSample>,
    samplesDate: String?,
    seriesLoading: Boolean,
    summaryLoading: Boolean,
    syncRunning: Boolean,
    healthDate: String?,
    fmt: Formatters,
    onLoadSamples: (String?) -> Unit,
    onHealthDate: (String?) -> Unit,
) {
    val selectedDate = healthDate
    LaunchedEffect(days, selectedDate) {
        if (selectedDate == null && days.isNotEmpty()) onHealthDate(days.first().date)
    }
    val day = days.find { it.date == selectedDate }
    LaunchedEffect(selectedDate, syncRunning) { onLoadSamples(selectedDate) }
    val older = selectedDate?.let { date -> days.filter { it.date < date }.maxByOrNull { it.date } }
    val newer = selectedDate?.let { date -> days.filter { it.date > date }.minByOrNull { it.date } }
    val daySamples = if (samplesDate == selectedDate) samples else emptyList()
    var showPicker by remember { mutableStateOf(false) }
    val availableDates = remember(days) { days.map { it.date }.toSet() }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (selectedDate == null) {
            Text(
                "No daily health yet. Refresh activities to pull the last two weeks, or load older days above.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { older?.date?.let(onHealthDate) },
                    enabled = older != null,
                ) {
                    Icon(Icons.Outlined.ChevronLeft, contentDescription = "Previous day")
                }
                Column(
                    Modifier
                        .weight(1f)
                        .clickable(enabled = availableDates.isNotEmpty()) { showPicker = true },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            formatHealthDate(selectedDate),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                        )
                        Icon(
                            Icons.Outlined.CalendarMonth,
                            contentDescription = "Pick date",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        if (days.isEmpty()) "No stored days yet" else {
                            val pos = days.indexOfFirst { it.date == selectedDate }
                            if (pos >= 0) "${pos + 1} of ${days.size}" else "Not in stored days"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = { newer?.date?.let(onHealthDate) },
                    enabled = newer != null,
                ) {
                    Icon(Icons.Outlined.ChevronRight, contentDescription = "Next day")
                }
            }
            if (showPicker && availableDates.isNotEmpty()) {
                HealthDatePickerDialog(
                    selected = if (selectedDate in availableDates) selectedDate else availableDates.first(),
                    available = availableDates,
                    onPick = { iso ->
                        onHealthDate(iso)
                        showPicker = false
                    },
                    onDismiss = { showPicker = false },
                )
            }
            if (day != null) {
                DayHealthCard(day, fmt)
            } else {
                Text(
                    if (summaryLoading) "Loading daily totals…"
                    else "No daily totals for this day.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (daySamples.isEmpty() && seriesLoading && samplesDate == selectedDate) {
                Text(
                    "Loading graphs for this day…",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (day != null) {
                DaySeriesCharts(day, daySamples, fmt)
            } else if (daySamples.isNotEmpty()) {
                DaySeriesCharts(DailyHealth(date = selectedDate), daySamples, fmt)
            }
        }
    }
}

@Composable
private fun HealthStatsTab(days: List<DailyHealth>, fmt: Formatters) {
    var range by remember { mutableStateOf(HealthRange.MONTH) }
    val window = remember(days, range) { days.inRange(range) }
    val trends = remember(window, fmt.metric, range) {
        HealthStatsOrder.associateWith { metric -> healthTrend(window, metric, fmt, range) }
    }
    val restHr = remember(window, range) { healthTrend(window, range) { it.restingHr } }
    val minHr = remember(window, range) { healthTrend(window, range) { it.minHr } }
    val maxHr = remember(window, range) { healthTrend(window, range) { it.maxHr } }
    val hasHeartRate = restHr.isNotEmpty() || minHr.isNotEmpty() || maxHr.isNotEmpty()
    val unit = when (range) {
        HealthRange.YEAR -> "week"
        HealthRange.ALL -> "month"
        else -> "day"
    }

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
        if (HealthStatsOrder.filter { it != HealthMetric.HEART_RATE }.all { trends[it].isNullOrEmpty() } && !hasHeartRate) {
            Text(
                "No health in this range. Load older days if needed.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HealthStatsOrder.forEach { metric ->
            if (metric == HealthMetric.HEART_RATE) {
                if (!hasHeartRate) return@forEach
                val hrLine = maxHr.ifEmpty { minHr }.ifEmpty { restHr }
                val restLine = restHr.takeIf { hrLine !== restHr && it.isNotEmpty() }.orEmpty()
                ChartCard(
                    title = "Heart rate",
                    subtitle = buildString {
                        val n = maxOf(hrLine.size, restHr.size)
                        append("$n ${if (n == 1) unit else unit + "s"}")
                        restHr.takeIf { it.isNotEmpty() }?.let {
                            append(" · avg rest ${fmt.heartRate(it.map { p -> p.value }.average())}")
                        }
                    },
                ) {
                    LineChart(
                        points = hrLine,
                        lineColor = Color(0xFFE24B4B),
                        extra = if (restLine.isNotEmpty()) {
                            listOf(ChartLine(restLine, Color(0xFF24357A), dashed = true))
                        } else {
                            emptyList()
                        },
                        band = if (minHr.isNotEmpty() && maxHr.isNotEmpty()) minHr to maxHr else null,
                        yFormatter = { String.format(Locale.US, "%.0f", it) },
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "HR range",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFE24B4B),
                        )
                        if (restLine.isNotEmpty()) {
                            Text(
                                "Dashed: resting",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF24357A),
                            )
                        }
                    }
                }
                return@forEach
            }
            val points = trends[metric].orEmpty()
            if (points.isEmpty()) return@forEach
            val average = points.map { it.value }.average()
            val goal = when (metric) {
                HealthMetric.STEPS -> window.latestPositive { it.stepGoal } ?: days.latestPositive { it.stepGoal }
                HealthMetric.FLOORS -> window.latestPositive { it.floorsGoal } ?: days.latestPositive { it.floorsGoal }
                else -> null
            }
            ChartCard(
                title = metric.label,
                subtitle = buildString {
                    append("${points.size} ${if (points.size == 1) unit else unit + "s"} · avg ${metric.format(average, fmt)}")
                    if (goal != null) append(" · goal ${metric.format(goal, fmt)}")
                },
            ) {
                when (metric.style) {
                    HealthChartStyle.BARS -> BarChart(
                        points,
                        barColor = metric.color,
                        referenceY = goal,
                        yFormatter = { metric.tick(it, fmt) },
                    )
                    HealthChartStyle.LINE -> LineChart(
                        points,
                        lineColor = metric.color,
                        yMin = metric.yMin,
                        yMax = metric.yMax,
                        yFormatter = { metric.tick(it, fmt) },
                    )
                }
                if (goal != null) {
                    Text(
                        "Dashed line is the daily goal",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
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

private fun List<DailyHealth>.latestPositive(pick: (DailyHealth) -> Double?): Double? =
    sortedBy { it.date }.mapNotNull(pick).lastOrNull()?.takeIf { it > 0 }

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
): List<TrendPoint> = healthTrend(days, range) { metric.value(it, fmt) }

private fun healthTrend(
    days: List<DailyHealth>,
    range: HealthRange,
    valueOf: (DailyHealth) -> Double?,
): List<TrendPoint> {
    val daily = days.asReversed().mapNotNull { day ->
        val value = valueOf(day) ?: return@mapNotNull null
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
    HealthMetric.HEART_RATE -> day.restingHr
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
    HealthMetric.HEART_RATE -> fmt.heartRate(value)
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
    floorsUp?.let {
        val goal = floorsGoal?.roundToInt()?.takeIf { g -> g > 0 }
        add("Floors up" to if (goal != null) "${it.roundToInt()} / $goal" else it.roundToInt().toString())
    }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HealthDatePickerDialog(
    selected: String,
    available: Set<String>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val years = remember(available) {
        available.mapNotNull { runCatching { LocalDate.parse(it).year }.getOrNull() }
    }
    val yearRange = if (years.isEmpty()) 2000..2100 else years.min()..years.max()
    val selectable = remember(available) {
        object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                utcMillisToIso(utcTimeMillis) in available

            override fun isSelectableYear(year: Int): Boolean = year in yearRange
        }
    }
    val state = rememberDatePickerState(
        initialSelectedDateMillis = isoToUtcMillis(selected),
        yearRange = yearRange,
        selectableDates = selectable,
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val iso = state.selectedDateMillis?.let(::utcMillisToIso)
                    if (iso != null && iso in available) onPick(iso) else onDismiss()
                },
            ) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    ) {
        DatePicker(state = state)
    }
}

private fun isoToUtcMillis(iso: String): Long =
    LocalDate.parse(iso).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun utcMillisToIso(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString()

