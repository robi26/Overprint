package ch.steigis.overprint.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ch.steigis.overprint.domain.format.Formatters
import ch.steigis.overprint.domain.model.ActivityDetail
import java.util.Locale

private data class StatRow(val label: String, val value: String)
private data class StatGroup(val title: String, val rows: List<StatRow>)

@Composable
fun DetailsList(
    detail: ActivityDetail,
    fmt: Formatters,
) {
    val groups = remember(detail, fmt.metric) { detailGroups(detail, fmt) }
    if (groups.isEmpty()) {
        Text("No extra stats", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
        return
    }
    val headerBg = MaterialTheme.colorScheme.background
    val headerColor = MaterialTheme.colorScheme.onSurfaceVariant
    val even = MaterialTheme.colorScheme.surface
    val odd = MaterialTheme.colorScheme.surfaceVariant
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        groups.forEach { group ->
            statGroup(group, headerBg, headerColor, even, odd)
        }
    }
}

private fun LazyListScope.statGroup(
    group: StatGroup,
    headerBg: Color,
    headerColor: Color,
    even: Color,
    odd: Color,
) {
    item(key = "h-${group.title}") {
        Text(
            group.title.uppercase(Locale.US),
            modifier = Modifier
                .fillMaxWidth()
                .background(headerBg)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            style = MaterialTheme.typography.labelSmall,
            color = headerColor,
            fontWeight = FontWeight.SemiBold,
        )
    }
    itemsIndexed(group.rows, key = { _, row -> "${group.title}-${row.label}" }) { index, row ->
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (index % 2 == 0) even else odd)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(row.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(row.value, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
        }
    }
    item(key = "d-${group.title}") { HorizontalDivider() }
}

private fun detailGroups(detail: ActivityDetail, fmt: Formatters): List<StatGroup> {
    val act = detail.activity
    val rightPct = detail.track.mapNotNull { it.leftRightBalancePercent }.takeIf { it.isNotEmpty() }?.average()
    return listOfNotNull(
        group(
            "Timing",
            "Total time" to fmt.duration(act.durationSeconds),
            "Moving time" to fmt.duration(act.movingSeconds),
        ),
        group(
            "Distance & speed",
            "Distance" to fmt.distance(act.distanceMeters),
            if (act.type.usesPace) "Avg pace" to fmt.pace(act.paceSecPerKm) else "Avg speed" to fmt.speed(act.avgSpeedMps),
            "Max speed" to fmt.speed(act.maxSpeedMps),
            "Avg grade" to fmt.grade(act.avgGrade),
        ),
        group(
            "Heart rate",
            "Avg heart rate" to fmt.heartRate(act.avgHeartRate),
            "Max heart rate" to fmt.heartRate(act.maxHeartRate),
            "Min heart rate" to fmt.heartRate(act.minHeartRate),
        ),
        group(
            "Power",
            "Avg power" to fmt.power(act.avgPower),
            "Max power" to fmt.power(act.maxPower),
            "Normalized power" to fmt.power(act.normalizedPower),
            "Work" to act.workKj?.let { String.format(Locale.US, "%.0f kJ", it) },
        ),
        group(
            "Cadence",
            "Avg cadence" to fmt.cadence(act.avgCadence).takeIf { it != "—" }?.let { "$it rpm" },
            "Max cadence" to fmt.cadence(act.maxCadence).takeIf { it != "—" }?.let { "$it rpm" },
            "Stride" to fmt.stepLength(act.avgStepLengthMm ?: act.strideLengthMeters?.times(1000.0)),
        ),
        group(
            "Elevation",
            "Elevation gain" to fmt.elevation(act.elevationGainMeters),
            "Elevation loss" to fmt.elevation(act.elevationLossMeters),
        ),
        group(
            "Energy",
            "Calories" to fmt.calories(act.calories),
        ),
        group(
            "Temperature",
            "Avg temperature" to fmt.temperature(act.avgTemperatureC),
        ),
        group(
            "Running dynamics",
            "Vertical oscillation" to fmt.millimeters(act.avgVerticalOscillationMm),
            "Ground contact time" to fmt.milliseconds(act.avgStanceTimeMs),
            "Vertical ratio" to fmt.percent(act.avgVerticalRatio),
            "L/R balance" to rightPct?.let { String.format(Locale.US, "%.0f%% / %.0f%%", 100.0 - it, it) },
        ),
        group(
            "Respiration",
            "Avg respiration" to fmt.respiration(act.avgRespirationRate),
        ),
        group(
            "Training",
            "Aerobic training effect" to fmt.trainingEffect(act.aerobicTrainingEffect),
            "Anaerobic training effect" to fmt.trainingEffect(act.anaerobicTrainingEffect),
            "Intensity factor (IF)" to fmt.intensityFactor(act.intensityFactor),
            "Training stress score (TSS)" to fmt.tss(act.trainingStressScore),
        ),
    )
}

private fun group(title: String, vararg rows: Pair<String, String?>): StatGroup? {
    val present = rows.mapNotNull { (label, value) ->
        value?.takeIf { it.isNotBlank() && it != "—" }?.let { StatRow(label, it) }
    }
    return present.takeIf { it.isNotEmpty() }?.let { StatGroup(title, it) }
}
