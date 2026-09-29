package ch.steigis.overprint.ui.detail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import ch.steigis.overprint.domain.format.Formatters
import ch.steigis.overprint.domain.model.ChartMetric
import ch.steigis.overprint.domain.model.TrackPoint
import ch.steigis.overprint.domain.model.chartValue
import ch.steigis.overprint.domain.model.unit
import ch.steigis.overprint.domain.stats.chartSeries
import ch.steigis.overprint.ui.components.ChartCard
import ch.steigis.overprint.ui.components.DotChart
import ch.steigis.overprint.ui.components.axisTicks
import ch.steigis.overprint.ui.components.chartLineColor
import ch.steigis.overprint.ui.components.formatTick
import java.util.Locale
import kotlin.math.abs

/** Metrics drawn as per-sample dot charts, in Garmin Connect's order. */
internal val RunningDynamicsMetrics = listOf(
    ChartMetric.CADENCE,
    ChartMetric.STEP_LENGTH,
    ChartMetric.VERTICAL_RATIO,
    ChartMetric.VERTICAL_OSC,
    ChartMetric.GROUND_CONTACT,
    ChartMetric.GCT_BALANCE,
)

private val ZoneRed = Color(0xFFE53935)
private val ZoneOrange = Color(0xFFFF9F2E)
private val ZoneGreen = Color(0xFF4CC870)
private val ZoneBlue = Color(0xFF3FA2F7)
private val ZonePurple = Color(0xFFD63AC9)

private class DotSpec(
    val title: String,
    val unit: String,
    val value: (TrackPoint) -> Double?,
    val format: (Double) -> String,
    /** Garmin's running dynamics colour gauge; null draws every dot in one colour. */
    val zone: ((Double) -> Color)?,
)

private class DotData(
    val points: List<Pair<Double, Double>>,
    val xRange: ClosedFloatingPointRange<Double>,
)

private fun whole(v: Double) = String.format(Locale.US, "%.0f", v)
private fun oneDecimal(v: Double) = String.format(Locale.US, "%.1f", v)

private fun dotSpec(metric: ChartMetric, metricUnits: Boolean): DotSpec? = when (metric) {
    // FIT stores running cadence per foot; Garmin shows steps per minute.
    ChartMetric.CADENCE -> DotSpec("Cadence", "spm", { p -> p.cadence?.takeIf { it > 0 }?.times(2) }, ::whole, ::cadenceZone)
    ChartMetric.STEP_LENGTH -> DotSpec(
        "Stride length",
        metric.unit(metricUnits),
        { p -> p.chartValue(metric, metricUnits)?.takeIf { it > 0 } },
        { String.format(Locale.US, "%.2f", it) },
        null,
    )
    ChartMetric.VERTICAL_RATIO -> DotSpec("Vertical ratio", "%", { p -> p.verticalRatio?.takeIf { it > 0 } }, ::oneDecimal, ::verticalRatioZone)
    ChartMetric.VERTICAL_OSC -> DotSpec("Vertical oscillation", "mm", { p -> p.verticalOscillationMm?.takeIf { it > 0 } }, ::whole, ::verticalOscillationZone)
    ChartMetric.GROUND_CONTACT -> DotSpec("Ground contact time", "ms", { p -> p.stanceTimeMs?.takeIf { it > 0 } }, ::whole, ::groundContactZone)
    ChartMetric.GCT_BALANCE -> DotSpec("Ground contact time balance", "% L", { p -> p.stanceTimeBalancePercent }, ::oneDecimal, ::balanceZone)
    else -> null
}

// Zone limits follow Garmin's running dynamics colour gauges.
private fun cadenceZone(spm: Double) = when {
    spm < 153 -> ZoneRed
    spm < 164 -> ZoneOrange
    spm < 174 -> ZoneGreen
    spm < 184 -> ZoneBlue
    else -> ZonePurple
}

private fun groundContactZone(ms: Double) = when {
    ms < 218 -> ZonePurple
    ms < 249 -> ZoneBlue
    ms < 278 -> ZoneGreen
    ms < 309 -> ZoneOrange
    else -> ZoneRed
}

private fun verticalOscillationZone(mm: Double) = when {
    mm < 64 -> ZonePurple
    mm < 82 -> ZoneBlue
    mm < 98 -> ZoneGreen
    mm < 116 -> ZoneOrange
    else -> ZoneRed
}

private fun verticalRatioZone(percent: Double) = when {
    percent < 6.1 -> ZonePurple
    percent < 7.5 -> ZoneBlue
    percent < 8.7 -> ZoneGreen
    percent < 10.2 -> ZoneOrange
    else -> ZoneRed
}

private fun balanceZone(leftPercent: Double) = abs(leftPercent - 50.0).let { off ->
    when {
        off < 0.8 -> ZoneGreen
        off <= 2.2 -> ZoneOrange
        else -> ZoneRed
    }
}

private fun dotData(track: List<TrackPoint>, value: (TrackPoint) -> Double?): DotData? {
    // Shared x axis over the whole activity, so gaps show where a metric has no samples.
    val xs = chartSeries(track) { 0.0 }
    if (xs.size != track.size) return null
    val points = track.indices.mapNotNull { i ->
        value(track[i])?.takeIf { it.isFinite() }?.let { xs[i].first to it }
    }
    if (points.size < 2) return null
    return DotData(points, xs.minOf { it.first }..xs.maxOf { it.first })
}

internal fun hasDotData(track: List<TrackPoint>, metric: ChartMetric, metricUnits: Boolean): Boolean {
    val spec = dotSpec(metric, metricUnits) ?: return false
    return track.count { spec.value(it) != null } >= 2
}

/** Axis from the 0.5–99.5 percentile so a few sensor spikes don't flatten the rest. */
private fun dotTicks(values: List<Double>): List<Double> {
    val sorted = values.sorted()
    val trim = if (sorted.size >= 200) sorted.size / 200 else 0
    return axisTicks(sorted[trim], sorted[sorted.lastIndex - trim], 4)
}

/** Symmetric around 50/50, left on top like Garmin Connect. */
private fun balanceTicks(values: List<Double>): List<Double> {
    val off = values.maxOf { abs(it - 50.0) }
    val half = listOf(5.0, 10.0, 15.0, 20.0).firstOrNull { it >= off } ?: 20.0
    return listOf(50.0 - half, 50.0, 50.0 + half)
}

private fun balanceTickLabel(v: Double): String = when {
    abs(v - 50.0) < 0.01 -> "50/50"
    v > 50.0 -> "${whole(v)}% L"
    else -> "${whole(100.0 - v)}% R"
}

@Composable
internal fun RunningDynamicsCard(
    metric: ChartMetric,
    track: List<TrackPoint>,
    running: Boolean,
    fmt: Formatters,
) {
    val spec = dotSpec(metric, fmt.metric) ?: return
    val data = remember(track, metric, fmt.metric) { dotData(track, spec.value) } ?: return
    val values = data.points.map { it.second }
    val average = values.average()
    val balance = metric == ChartMetric.GCT_BALANCE
    val headline = when {
        balance -> "${oneDecimal(average)}% L · ${oneDecimal(100.0 - average)}% R"
        else -> "${spec.format(average)} ${spec.unit}"
    }
    val subtitle = if (metric == ChartMetric.CADENCE) {
        "Average · max ${spec.format(values.max())} ${spec.unit}"
    } else {
        "Average"
    }
    val zone = spec.zone?.takeIf { running }
    val single = chartLineColor(metric)
    ChartCard(title = spec.title, headline = headline, subtitle = subtitle) {
        DotChart(
            points = data.points,
            xRange = data.xRange,
            ticks = remember(data) { if (balance) balanceTicks(values) else dotTicks(values) },
            dotColor = zone ?: { _: Double -> single },
            tickLabel = if (balance) ::balanceTickLabel else ::formatTick,
            valueLabel = { v ->
                if (balance) "${oneDecimal(v)}% L · ${oneDecimal(100.0 - v)}% R" else "${spec.format(v)} ${spec.unit}"
            },
            average = average,
        )
    }
}
