package net.roz.connectstats.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.roz.connectstats.domain.format.Formatters
import net.roz.connectstats.domain.model.ActivityDetail
import net.roz.connectstats.domain.model.ActivityType
import net.roz.connectstats.domain.model.ChartMetric
import net.roz.connectstats.domain.model.Lap
import net.roz.connectstats.domain.model.MapMetric
import net.roz.connectstats.domain.model.ZoneBucket
import net.roz.connectstats.domain.model.chartValue
import net.roz.connectstats.domain.model.shortTitle
import net.roz.connectstats.domain.model.unit
import net.roz.connectstats.domain.stats.RollingBest
import net.roz.connectstats.domain.stats.StatsEngine
import net.roz.connectstats.ui.components.ChartCard
import net.roz.connectstats.ui.components.GradientTrackMap
import net.roz.connectstats.ui.components.TrackChart

private enum class DetailTab(val label: String) {
    OVERVIEW("Overview"),
    LAPS("Laps"),
    HR_ZONES("HR zones"),
    POWER_ZONES("Power zones"),
}

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
    var chartMetrics by remember(detail.activity.id) {
        val initial = ChartMetric.entries.firstOrNull { m ->
            detail.track.count { it.chartValue(m) != null } >= 2
        } ?: ChartMetric.HEART_RATE
        mutableStateOf<Set<ChartMetric>>(linkedSetOf(initial))
    }
    val hrZones = remember(detail.track, maxHr) { StatsEngine.timeInHrZones(detail.track, maxHr) }
    val pwZones = remember(detail.track, ftp) { StatsEngine.timeInPowerZones(detail.track, ftp) }
    val rolling = remember(detail.track) { StatsEngine.bestRolling(detail.track) }
    val tabs = remember(detail.laps, hrZones, pwZones) {
        buildList {
            add(DetailTab.OVERVIEW)
            if (detail.laps.isNotEmpty()) add(DetailTab.LAPS)
            if (hrZones.any { it.seconds > 0 }) add(DetailTab.HR_ZONES)
            if (pwZones.any { it.seconds > 0 }) add(DetailTab.POWER_ZONES)
        }
    }
    var tab by remember(detail.activity.id) { mutableStateOf(DetailTab.OVERVIEW) }
    val selectedTab = if (tab in tabs) tab else DetailTab.OVERVIEW

    Column(Modifier.fillMaxSize()) {
        val subtitle = listOfNotNull(fmt.dateTime(act.startTimeMillis), act.location, act.deviceName).joinToString(" · ")
        if (subtitle.isNotBlank()) {
            Text(
                subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
            )
        }
        ScrollableTabRow(
            selectedTabIndex = tabs.indexOf(selectedTab).coerceAtLeast(0),
            edgePadding = 16.dp,
            divider = {},
        ) {
            tabs.forEach { item ->
                Tab(
                    selected = selectedTab == item,
                    onClick = { tab = item },
                    text = { Text(item.label) },
                )
            }
        }
        if (selectedTab == DetailTab.LAPS) {
            LapsTab(detail.laps, detail.activity.type, fmt)
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                when (selectedTab) {
                    DetailTab.OVERVIEW -> OverviewTab(
                        detail = detail,
                        fmt = fmt,
                        mapMetric = mapMetric,
                        onMapMetric = { mapMetric = it },
                        chartMetrics = chartMetrics,
                        onChartMetrics = { chartMetrics = it },
                        rolling = rolling,
                    )
                    DetailTab.LAPS -> Unit
                    DetailTab.HR_ZONES -> ZonesTab(hrZones, fmt)
                    DetailTab.POWER_ZONES -> ZonesTab(pwZones, fmt)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun OverviewTab(
    detail: ActivityDetail,
    fmt: Formatters,
    mapMetric: MapMetric,
    onMapMetric: (MapMetric) -> Unit,
    chartMetrics: Set<ChartMetric>,
    onChartMetrics: (Set<ChartMetric>) -> Unit,
    rolling: List<RollingBest>,
) {
    val act = detail.activity
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
                        onClick = { onMapMetric(m) },
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
                    val selected = m in chartMetrics
                    FilterChip(
                        selected = selected,
                        onClick = {
                            onChartMetrics(
                                if (selected) {
                                    if (chartMetrics.size > 1) chartMetrics - m else chartMetrics
                                } else chartMetrics + m,
                            )
                        },
                        label = { Text("${m.shortTitle()} ${m.unit(fmt.metric)}") },
                    )
                }
            }
            TrackChart(detail.track, chartMetrics, fmt.metric)
        }
    }

    if (rolling.isNotEmpty()) {
        Text("Best rolling", style = MaterialTheme.typography.titleMedium)
        rolling.forEach { b ->
            Text("${b.label}  ${fmt.duration(b.durationSeconds)}  ${fmt.heartRate(b.avgHeartRate)}  ${fmt.power(b.avgPower)}")
        }
    }
}

@Composable
private fun LapsTab(laps: List<Lap>, type: ActivityType, fmt: Formatters) {
    if (laps.isEmpty()) {
        Text("No laps", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
        return
    }
    val headerColor = MaterialTheme.colorScheme.onSurfaceVariant
    val even = MaterialTheme.colorScheme.surface
    val odd = MaterialTheme.colorScheme.surfaceVariant
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            LapRow(
                cells = listOf(
                    "#" to 0.55f,
                    "Dist" to 1.2f,
                    "Time" to 1.1f,
                    if (type.usesPace) "Pace" to 1.2f else "Speed" to 1.2f,
                    "HR" to 1f,
                    "Pwr" to 0.9f,
                ),
                bold = true,
                color = headerColor,
                background = MaterialTheme.colorScheme.background,
            )
            HorizontalDivider()
        }
        itemsIndexed(laps, key = { _, lap -> "${lap.activityId}-${lap.index}" }) { index, lap ->
            val paceOrSpeed = fmt.speedOrPace(type, lap.avgSpeedMps, lap.durationSeconds, lap.distanceMeters)
            LapRow(
                cells = listOf(
                    "${lap.index}" to 0.55f,
                    fmt.distance(lap.distanceMeters) to 1.2f,
                    fmt.duration(lap.durationSeconds) to 1.1f,
                    paceOrSpeed to 1.2f,
                    fmt.heartRate(lap.avgHeartRate).removeSuffix(" bpm") to 1f,
                    fmt.power(lap.avgPower).removeSuffix(" W") to 0.9f,
                ),
                bold = false,
                color = MaterialTheme.colorScheme.onSurface,
                background = if (index % 2 == 0) even else odd,
            )
        }
    }
}

@Composable
private fun LapRow(
    cells: List<Pair<String, Float>>,
    bold: Boolean,
    color: Color,
    background: Color,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(background)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        cells.forEach { (text, weight) ->
            Text(
                text,
                modifier = Modifier.weight(weight),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ZonesTab(zones: List<ZoneBucket>, fmt: Formatters) {
    if (zones.none { it.seconds > 0 }) {
        Text("No zone data", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    zones.forEach { z ->
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${z.label}  ${fmt.duration(z.seconds)}")
            LinearProgressIndicator(
                progress = { z.fraction },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
            )
        }
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
