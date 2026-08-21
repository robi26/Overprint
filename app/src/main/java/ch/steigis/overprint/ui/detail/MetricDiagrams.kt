package ch.steigis.overprint.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ch.steigis.overprint.domain.format.Formatters
import ch.steigis.overprint.domain.model.Activity
import ch.steigis.overprint.domain.model.ActivityDetail
import ch.steigis.overprint.domain.model.ChartMetric
import ch.steigis.overprint.domain.model.TrackPoint
import ch.steigis.overprint.domain.model.chartValue
import ch.steigis.overprint.domain.model.isCore
import ch.steigis.overprint.domain.model.title
import ch.steigis.overprint.ui.components.ChartCard
import ch.steigis.overprint.ui.components.TrackChart

@Composable
fun ExtraMetricDiagrams(detail: ActivityDetail, fmt: Formatters) {
    val act = detail.activity
    val extraSeries = ChartMetric.entries.filter { !it.isCore && detail.track.count { p -> p.chartValue(it) != null } >= 2 }
    val rightPct = act.avgRightBalancePercent(detail.track)
    val showElev = (act.elevationGainMeters ?: 0.0) > 0.5 || (act.elevationLossMeters ?: 0.0) > 0.5
    val showTe = act.aerobicTrainingEffect != null || act.anaerobicTrainingEffect != null
    val showIf = act.intensityFactor != null || act.trainingStressScore != null
    if (extraSeries.isEmpty() && rightPct == null && !showElev && !showTe && !showIf) return

    if (showElev) {
        val gain = act.elevationGainMeters ?: 0.0
        val loss = act.elevationLossMeters ?: 0.0
        ChartCard(title = "Elevation") {
            SplitMeter(
                leftLabel = "Gain",
                rightLabel = "Loss",
                leftValue = fmt.elevation(gain),
                rightValue = fmt.elevation(loss),
                leftFraction = (gain / (gain + loss).coerceAtLeast(0.1)).toFloat(),
                leftColor = Color(0xFF5B8C5A),
                rightColor = Color(0xFFE67E22),
            )
        }
    }
    if (showTe) {
        ChartCard(title = "Training effect") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                act.aerobicTrainingEffect?.let { ScaleMeter("Aerobic", it, 5.0, fmt.trainingEffect(it), teColor(it)) }
                act.anaerobicTrainingEffect?.let { ScaleMeter("Anaerobic", it, 5.0, fmt.trainingEffect(it), teColor(it)) }
            }
        }
    }
    if (showIf) {
        ChartCard(title = "Load") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                act.intensityFactor?.let {
                    ScaleMeter("Intensity factor (IF)", it, 1.2, fmt.intensityFactor(it), Color(0xFF3583F3))
                }
                act.trainingStressScore?.let {
                    ScaleMeter("Training stress score (TSS)", it, 150.0, fmt.tss(it), Color(0xFFF5A524))
                }
            }
        }
    }
    if (rightPct != null) {
        ChartCard(title = "L/R balance") {
            SplitMeter(
                leftLabel = "Left",
                rightLabel = "Right",
                leftValue = String.format(java.util.Locale.US, "%.0f%%", 100.0 - rightPct),
                rightValue = String.format(java.util.Locale.US, "%.0f%%", rightPct),
                leftFraction = ((100.0 - rightPct) / 100.0).toFloat().coerceIn(0.05f, 0.95f),
                leftColor = Color(0xFF3583F3),
                rightColor = Color(0xFFE24B4B),
            )
        }
    }
    extraSeries.forEach { metric ->
        ChartCard(title = metric.title()) {
            TrackChart(detail.track, setOf(metric), fmt.metric)
        }
    }
}

private fun Activity.avgRightBalancePercent(track: List<TrackPoint>): Double? =
    track.mapNotNull { it.leftRightBalancePercent }.takeIf { it.isNotEmpty() }?.average()

private fun teColor(value: Double): Color = when {
    value < 2.0 -> Color(0xFF5B8C5A)
    value < 3.5 -> Color(0xFF3583F3)
    value < 4.5 -> Color(0xFFF5A524)
    else -> Color(0xFFE24B4B)
}

@Composable
private fun ScaleMeter(
    label: String,
    value: Double,
    max: Double,
    formatted: String,
    color: Color,
) {
    val fraction = (value / max).toFloat().coerceIn(0f, 1f)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatted, fontWeight = FontWeight.SemiBold)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .background(color),
            )
        }
    }
}

@Composable
private fun SplitMeter(
    leftLabel: String,
    rightLabel: String,
    leftValue: String,
    rightValue: String,
    leftFraction: Float,
    leftColor: Color,
    rightColor: Color,
) {
    val left = leftFraction.coerceIn(0.08f, 0.92f)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("$leftLabel  $leftValue", style = MaterialTheme.typography.labelMedium)
            Text("$rightLabel  $rightValue", style = MaterialTheme.typography.labelMedium)
        }
        Row(
            Modifier
                .fillMaxWidth()
                .height(12.dp)
                .clip(RoundedCornerShape(6.dp)),
        ) {
            Box(Modifier.weight(left).fillMaxHeight().background(leftColor))
            Box(Modifier.weight(1f - left).fillMaxHeight().background(rightColor))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(leftLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(rightLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
