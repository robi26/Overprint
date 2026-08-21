package ch.steigis.overprint.ui.detail

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ch.steigis.overprint.domain.format.Formatters
import ch.steigis.overprint.domain.model.ActivityDetail
import ch.steigis.overprint.domain.model.ActivityType
import ch.steigis.overprint.domain.model.ChartMetric
import ch.steigis.overprint.domain.model.Lap
import ch.steigis.overprint.domain.model.MapMetric
import ch.steigis.overprint.domain.model.ZoneBucket
import ch.steigis.overprint.domain.model.chartValue
import ch.steigis.overprint.domain.model.isCore
import ch.steigis.overprint.domain.model.shortTitle
import ch.steigis.overprint.domain.model.unit
import ch.steigis.overprint.domain.stats.RollingBest
import ch.steigis.overprint.domain.stats.StatsEngine
import ch.steigis.overprint.ui.components.ChartCard
import ch.steigis.overprint.ui.components.GradientTrackMap
import ch.steigis.overprint.ui.components.TrackChart
import ch.steigis.overprint.ui.components.valueOf

private enum class DetailTab(val label: String) {
    OVERVIEW("Overview"),
    DETAILS("Details"),
    LAPS("Laps"),
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
    var mapMetric by remember(detail.activity.id) {
        mutableStateOf(
            MapMetric.entries.firstOrNull { m ->
                detail.track.any { it.latitude != null && it.valueOf(m) != null }
            } ?: MapMetric.HEART_RATE,
        )
    }
    var chartMetrics by remember(detail.activity.id) {
        val initial = ChartMetric.entries.firstOrNull { m ->
            m.isCore && detail.track.count { it.chartValue(m) != null } >= 2
        } ?: ChartMetric.HEART_RATE
        mutableStateOf<Set<ChartMetric>>(linkedSetOf(initial))
    }
    val hrZones = remember(detail.track, maxHr) { StatsEngine.timeInHrZones(detail.track, maxHr) }
    val pwZones = remember(detail.track, ftp) { StatsEngine.timeInPowerZones(detail.track, ftp) }
    val rolling = remember(detail.track) { StatsEngine.bestRolling(detail.track) }
    val tabs = remember(detail.laps) {
        buildList {
            add(DetailTab.OVERVIEW)
            add(DetailTab.DETAILS)
            if (detail.laps.isNotEmpty()) add(DetailTab.LAPS)
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
        } else if (selectedTab == DetailTab.DETAILS) {
            DetailsList(detail, fmt)
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
                        hrZones = hrZones,
                        powerZones = pwZones,
                    )
                    DetailTab.DETAILS, DetailTab.LAPS -> Unit
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
    hrZones: List<ZoneBucket>,
    powerZones: List<ZoneBucket>,
) {
    val act = detail.activity
    HeroStatCircles(act, fmt)

    if (detail.track.any { it.latitude != null }) {
        val mapMetrics = MapMetric.entries.filter { metric ->
            detail.track.any { it.latitude != null && it.valueOf(metric) != null }
        }
        ChartCard(title = "Map") {
            if (mapMetrics.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    mapMetrics.forEach { m ->
                        FilterChip(
                            selected = mapMetric == m,
                            onClick = { onMapMetric(m) },
                            label = { Text(m.shortTitle()) },
                        )
                    }
                }
            }
            GradientTrackMap(detail.track, mapMetric)
        }
    }

    ZoneTimeCards(hrZones, powerZones, fmt)

    if (detail.track.isNotEmpty()) {
        val availableCharts = ChartMetric.entries.filter { m ->
            m.isCore && detail.track.count { it.chartValue(m) != null } >= 2
        }
        ChartCard(title = "Graphs") {
            if (availableCharts.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    availableCharts.forEach { m ->
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
            }
            TrackChart(detail.track, chartMetrics, fmt.metric)
        }
    }

    ExtraMetricDiagrams(detail, fmt)
    RollingBestCard(rolling, act.type, fmt)
}

@Composable
private fun RollingBestCard(
    rolling: List<RollingBest>,
    type: ActivityType,
    fmt: Formatters,
) {
    if (rolling.isEmpty()) return
    val showHr = rolling.any { it.avgHeartRate != null }
    val showPower = rolling.any { it.avgPower != null }
    val paceLabel = if (type.usesPace) "Pace" else "Speed"
    ChartCard(title = "Best rolling") {
        Column {
            RollingBestRow(
                cells = buildList {
                    add("Dist" to 1.15f)
                    add("Time" to 1f)
                    add(paceLabel to 1.25f)
                    if (showHr) add("HR" to 0.9f)
                    if (showPower) add("Power" to 1f)
                },
                header = true,
            )
            rolling.forEachIndexed { index, best ->
                if (index > 0) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                    )
                }
                val paceOrSpeed = fmt.speedOrPace(type, null, best.durationSeconds, best.distanceMeters)
                RollingBestRow(
                    cells = buildList {
                        add(best.label to 1.15f)
                        add(fmt.duration(best.durationSeconds) to 1f)
                        add(paceOrSpeed to 1.25f)
                        if (showHr) add(fmt.heartRate(best.avgHeartRate).removeSuffix(" bpm") to 0.9f)
                        if (showPower) add(fmt.power(best.avgPower).removeSuffix(" W") to 1f)
                    },
                    header = false,
                    emphasize = 1,
                )
            }
        }
    }
}

@Composable
private fun RollingBestRow(
    cells: List<Pair<String, Float>>,
    header: Boolean,
    emphasize: Int = -1,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = if (header) 0.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        cells.forEachIndexed { index, (text, weight) ->
            val isTime = !header && index == emphasize
            Text(
                text,
                modifier = Modifier.weight(weight),
                style = if (isTime) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                fontWeight = when {
                    header -> FontWeight.SemiBold
                    index == 0 || isTime -> FontWeight.SemiBold
                    else -> FontWeight.Normal
                },
                color = if (header || (index > 0 && !isTime)) muted else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
