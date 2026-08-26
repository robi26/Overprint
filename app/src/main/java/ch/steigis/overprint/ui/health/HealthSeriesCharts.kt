package ch.steigis.overprint.ui.health

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import ch.steigis.overprint.domain.format.Formatters
import ch.steigis.overprint.domain.model.DailyHealth
import ch.steigis.overprint.domain.model.HealthSample
import ch.steigis.overprint.domain.model.HealthSeries
import ch.steigis.overprint.domain.stats.TrendPoint
import ch.steigis.overprint.ui.components.ChartCard
import ch.steigis.overprint.ui.components.TimedChartStyle
import ch.steigis.overprint.ui.components.TimedSeriesChart
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt

private val SleepStageColors = listOf(
    Color(0xFF24357A),
    Color(0xFF5B8CFF),
    Color(0xFF9B7EDE),
    Color(0xFFF5C56E),
)
private val SleepStageNames = listOf("Deep", "Light", "REM", "Awake")

@Composable
internal fun DaySeriesCharts(
    day: DailyHealth,
    samples: List<HealthSample>,
    fmt: Formatters,
) {
    val byMetric = remember(samples) { samples.groupBy { it.metric } }
    DaySeriesOrder.forEach { series ->
        val points = byMetric[series].orEmpty().ifEmpty {
            if (series == HealthSeries.FLOORS && day.floorsUp != null) {
                val start = runCatching {
                    LocalDate.parse(day.date).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                }.getOrNull()
                if (start == null) emptyList()
                else listOf(
                    HealthSample(day.date, HealthSeries.FLOORS, start, 0.0),
                    HealthSample(day.date, HealthSeries.FLOORS, start + 12 * 3_600_000L, day.floorsUp),
                )
            } else {
                emptyList()
            }
        }
        if (points.isEmpty()) return@forEach
        val trend = remember(points, series) {
            val ordered = points.sortedBy { it.timestampMillis }
            if (series == HealthSeries.STEPS || series == HealthSeries.FLOORS) {
                var sum = 0.0
                ordered.map {
                    sum += it.value
                    TrendPoint(it.timestampMillis, sum, "")
                }
            } else {
                ordered.map { TrendPoint(it.timestampMillis, it.value, "") }
            }
        }
        val goal = when (series) {
            HealthSeries.STEPS -> day.stepGoal
            HealthSeries.FLOORS -> day.floorsGoal
            else -> null
        }
        ChartCard(
            title = series.title,
            headline = series.headline(day, points, fmt),
            subtitle = series.subtitle(points),
        ) {
            TimedSeriesChart(
                points = trend,
                style = series.style,
                lineColor = series.color,
                yMin = series.yMin,
                yMax = series.yMax,
                fromZero = series.fromZero,
                yLabels = if (series == HealthSeries.SLEEP) SleepStageNames else null,
                stageColors = if (series == HealthSeries.SLEEP) SleepStageColors else emptyList(),
                referenceY = when (series) {
                    HealthSeries.HEART_RATE -> day.restingHr
                    HealthSeries.STEPS, HealthSeries.FLOORS -> goal
                    else -> null
                },
                referenceColor = Color(0xFF24357A),
                yFormatter = series.yTick,
            )
            if (series == HealthSeries.HEART_RATE && day.restingHr != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Dashed line is resting HR (${fmt.heartRate(day.restingHr)})",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if ((series == HealthSeries.STEPS || series == HealthSeries.FLOORS) && goal != null && goal > 0) {
                Text(
                    "Dashed line is the daily goal",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (series == HealthSeries.SLEEP) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SleepStageNames.forEachIndexed { i, name ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(SleepStageColors[i]),
                            )
                            Text(
                                name,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

private val DaySeriesOrder = listOf(
    HealthSeries.HEART_RATE,
    HealthSeries.STEPS,
    HealthSeries.FLOORS,
    HealthSeries.BODY_BATTERY,
    HealthSeries.SLEEP,
    HealthSeries.STRESS,
    HealthSeries.SPO2,
    HealthSeries.RESPIRATION,
)

private val HealthSeries.title: String
    get() = when (this) {
        HealthSeries.HEART_RATE -> "Heart rate"
        HealthSeries.STEPS -> "Steps"
        HealthSeries.STRESS -> "Stress"
        HealthSeries.BODY_BATTERY -> "Body battery"
        HealthSeries.SLEEP -> "Sleep stages"
        HealthSeries.SPO2 -> "SpO2"
        HealthSeries.RESPIRATION -> "Breathing"
        HealthSeries.FLOORS -> "Floors"
    }

private val HealthSeries.style: TimedChartStyle
    get() = when (this) {
        HealthSeries.STEPS, HealthSeries.FLOORS -> TimedChartStyle.BARS
        HealthSeries.SLEEP -> TimedChartStyle.STAGES
        HealthSeries.SPO2 -> TimedChartStyle.LINE
        HealthSeries.HEART_RATE, HealthSeries.STRESS, HealthSeries.BODY_BATTERY, HealthSeries.RESPIRATION ->
            TimedChartStyle.AREA
    }

private val HealthSeries.color: Color
    get() = when (this) {
        HealthSeries.HEART_RATE -> Color(0xFFE24B4B)
        HealthSeries.STEPS -> Color(0xFF3583F3)
        HealthSeries.STRESS -> Color(0xFFF5A524)
        HealthSeries.BODY_BATTERY -> Color(0xFF2BB673)
        HealthSeries.SLEEP -> Color(0xFF6C5CE7)
        HealthSeries.SPO2 -> Color(0xFF2AA5C9)
        HealthSeries.RESPIRATION -> Color(0xFF9B7EDE)
        HealthSeries.FLOORS -> Color(0xFFE67E22)
    }

private val HealthSeries.fromZero: Boolean
    get() = this == HealthSeries.STEPS || this == HealthSeries.FLOORS

private val HealthSeries.yMin: Double?
    get() = when (this) {
        HealthSeries.STRESS, HealthSeries.BODY_BATTERY -> 0.0
        HealthSeries.SPO2 -> 80.0
        else -> null
    }

private val HealthSeries.yMax: Double?
    get() = when (this) {
        HealthSeries.STRESS, HealthSeries.BODY_BATTERY, HealthSeries.SPO2 -> 100.0
        else -> null
    }

private val HealthSeries.yTick: (Double) -> String
    get() = { value ->
        when (this) {
            HealthSeries.RESPIRATION -> String.format(Locale.US, "%.0f", value)
            else -> String.format(Locale.US, "%.0f", value)
        }
    }

private fun HealthSeries.headline(
    day: DailyHealth,
    samples: List<HealthSample>,
    fmt: Formatters,
): String? {
    val avg = samples.map { it.value }.average()
    val min = samples.minOf { it.value }
    val max = samples.maxOf { it.value }
    return when (this) {
        HealthSeries.HEART_RATE ->
            listOfNotNull(
                fmt.heartRate(avg),
                "${min.roundToInt()}–${max.roundToInt()}",
                day.restingHr?.let { "rest ${it.roundToInt()}" },
            ).joinToString(" · ")
        HealthSeries.STEPS -> {
            val count = (day.steps ?: samples.sumOf { it.value }).roundToInt()
            val goal = day.stepGoal?.roundToInt()?.takeIf { it > 0 }
            if (goal != null) "$count / $goal steps" else "$count steps"
        }
        HealthSeries.STRESS -> "${avg.roundToInt()} avg"
        HealthSeries.BODY_BATTERY -> "${samples.last().value.roundToInt()}% · ${min.roundToInt()}–${max.roundToInt()}"
        HealthSeries.SLEEP -> day.sleepSeconds?.let { fmt.duration(it) } ?: "${samples.size} stages"
        HealthSeries.SPO2 -> "${avg.roundToInt()}%"
        HealthSeries.RESPIRATION -> fmt.respiration(avg)
        HealthSeries.FLOORS -> {
            val count = (day.floorsUp ?: samples.sumOf { it.value }).roundToInt()
            val goal = day.floorsGoal?.roundToInt()?.takeIf { it > 0 }
            if (goal != null) "$count / $goal floors" else "$count floors"
        }
    }
}

private fun HealthSeries.subtitle(samples: List<HealthSample>): String? =
    if (samples.size < 2) null else "${samples.size} samples"
