package net.roz.connectstats.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.roz.connectstats.domain.format.Formatters
import net.roz.connectstats.domain.model.ActivityDetail
import net.roz.connectstats.domain.model.ChartMetric
import net.roz.connectstats.domain.model.MapMetric
import net.roz.connectstats.domain.stats.StatsEngine
import net.roz.connectstats.ui.components.ChartCard
import net.roz.connectstats.ui.components.GradientTrackMap
import net.roz.connectstats.ui.components.TrackChart

@Composable
fun ActivityDetailScreen(
    detail: ActivityDetail?,
    fmt: Formatters,
    maxHr: Double,
    ftp: Double,
) {
    if (detail == null) {
        Text("Loading…", Modifier.padding(24.dp))
        return
    }
    val act = detail.activity
    var mapMetric by remember { mutableStateOf(MapMetric.HEART_RATE) }
    var chartMetric by remember { mutableStateOf(ChartMetric.HEART_RATE) }
    val hrZones = remember(detail.track, maxHr) { StatsEngine.timeInHrZones(detail.track, maxHr) }
    val pwZones = remember(detail.track, ftp) { StatsEngine.timeInPowerZones(detail.track, ftp) }
    val rolling = remember(detail.track) { StatsEngine.bestRolling(detail.track) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(act.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            listOfNotNull(fmt.dateTime(act.startTimeMillis), act.location, act.deviceName).joinToString(" · "),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        StatGrid(
            listOf(
                "Distance" to fmt.distance(act.distanceMeters),
                "Time" to fmt.duration(act.durationSeconds),
                if (act.type.usesPace) "Pace" to fmt.pace(act.paceSecPerKm) else "Speed" to fmt.speed(act.avgSpeedMps),
                "HR" to fmt.heartRate(act.avgHeartRate),
                "Elev+" to fmt.elevation(act.elevationGainMeters),
                "Calories" to fmt.calories(act.calories),
                "Cadence" to fmt.cadence(act.avgCadence),
                "Power" to fmt.power(act.avgPower),
                "Stride" to (act.strideLengthMeters?.let { String.format("%.2f m", it) } ?: "—"),
                "Work" to (act.workKj?.let { String.format("%.0f kJ", it) } ?: "—"),
                "Max HR" to fmt.heartRate(act.maxHeartRate),
                "Max Pwr" to fmt.power(act.maxPower),
            ),
        )

        if (detail.track.any { it.latitude != null }) {
            ChartCard(title = "Map") {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MapMetric.entries.forEach { m ->
                        FilterChip(
                            selected = mapMetric == m,
                            onClick = { mapMetric = m },
                            label = { Text(m.name.lowercase().replace('_', ' ')) },
                        )
                    }
                }
                GradientTrackMap(detail.track, mapMetric)
            }
        }

        if (detail.track.isNotEmpty()) {
            ChartCard(title = "Graphs") {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChartMetric.entries.forEach { m ->
                        FilterChip(
                            selected = chartMetric == m,
                            onClick = { chartMetric = m },
                            label = { Text(m.name.lowercase().replace('_', ' ')) },
                        )
                    }
                }
                TrackChart(detail.track, chartMetric)
            }
        }

        if (detail.laps.isNotEmpty()) {
            Text("Laps", style = MaterialTheme.typography.titleMedium)
            detail.laps.forEach { lap ->
                Text(
                    "${lap.label}  ${fmt.distance(lap.distanceMeters)}  ${fmt.duration(lap.durationSeconds)}  ${fmt.heartRate(lap.avgHeartRate)}  ${fmt.power(lap.avgPower)}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(10.dp),
                )
            }
        }

        if (hrZones.any { it.seconds > 0 }) {
            Text("Time in HR zones", style = MaterialTheme.typography.titleMedium)
            hrZones.forEach { z ->
                Column {
                    Text("${z.label}  ${fmt.duration(z.seconds)}")
                    LinearProgressIndicator(progress = { z.fraction }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)))
                }
            }
        }
        if (pwZones.any { it.seconds > 0 }) {
            Text("Time in power zones", style = MaterialTheme.typography.titleMedium)
            pwZones.forEach { z ->
                Column {
                    Text("${z.label}  ${fmt.duration(z.seconds)}")
                    LinearProgressIndicator(progress = { z.fraction }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)))
                }
            }
        }
        if (rolling.isNotEmpty()) {
            Text("Best rolling", style = MaterialTheme.typography.titleMedium)
            rolling.forEach { b ->
                Text("${b.label}  ${fmt.duration(b.durationSeconds)}  ${fmt.heartRate(b.avgHeartRate)}  ${fmt.power(b.avgPower)}")
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun StatGrid(items: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (k, v) ->
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(10.dp),
                    ) {
                        Text(k, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(v, fontWeight = FontWeight.SemiBold)
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
