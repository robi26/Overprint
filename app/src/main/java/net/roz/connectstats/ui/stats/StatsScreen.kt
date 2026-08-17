package net.roz.connectstats.ui.stats

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
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
import net.roz.connectstats.domain.stats.StatsEngine
import net.roz.connectstats.ui.components.BarChart
import net.roz.connectstats.ui.components.HistogramChart
import net.roz.connectstats.ui.components.ScatterChart

private enum class HistField { DISTANCE, DURATION, HR, PACE, POWER }

@Composable
fun StatsScreen(activities: List<Activity>, fmt: Formatters) {
    var type by remember { mutableStateOf<ActivityType?>(null) }
    var hist by remember { mutableStateOf(HistField.DISTANCE) }
    val filtered = remember(activities, type) {
        if (type == null) activities else activities.filter { it.type == type }
    }
    val periods = remember(filtered) { StatsEngine.periodSummaries(filtered) }
    val weekly = remember(filtered) { StatsEngine.weeklyHistory(filtered) }
    val monthly = remember(filtered) { StatsEngine.monthlyHistory(filtered) }
    val histValues = remember(filtered, hist) {
        filtered.mapNotNull {
            when (hist) {
                HistField.DISTANCE -> it.distanceMeters / 1000.0
                HistField.DURATION -> it.durationSeconds / 60.0
                HistField.HR -> it.avgHeartRate
                HistField.PACE -> it.paceSecPerKm
                HistField.POWER -> it.avgPower
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
        StatsEngine.scatter(filtered, x = { it.avgHeartRate }, y = { it.paceSecPerKm })
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Statistics", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = { type = null }, label = { Text("All") })
            ActivityType.entries.forEach { t ->
                AssistChip(onClick = { type = t }, label = { Text(t.displayName) })
            }
        }
        periods.forEach { p ->
            Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                Text(p.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("${p.count} activities · ${fmt.distance(p.distanceMeters)} · ${fmt.duration(p.durationSeconds)} · ${fmt.elevation(p.elevationGain)}")
                if (p.byType.isNotEmpty()) {
                    Text(
                        p.byType.entries.joinToString("  ") { "${it.key.displayName} ${it.value.count}" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Text("Weekly distance", style = MaterialTheme.typography.titleMedium)
        BarChart(weekly, Modifier.fillMaxWidth().height(160.dp))
        Text("Monthly distance", style = MaterialTheme.typography.titleMedium)
        BarChart(monthly, Modifier.fillMaxWidth().height(160.dp))
        Text("Histogram", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HistField.entries.forEach { f ->
                AssistChip(onClick = { hist = f }, label = { Text(f.name.lowercase()) })
            }
        }
        HistogramChart(bins, Modifier.fillMaxWidth().height(140.dp))
        Text("Scatter: heart rate vs pace", style = MaterialTheme.typography.titleMedium)
        ScatterChart(scatter, Modifier.fillMaxWidth().height(180.dp))
        Text("Tap a field in the iOS app to open full history — here the histogram and scatter cover the same idea for the selected sport.")
    }
}
