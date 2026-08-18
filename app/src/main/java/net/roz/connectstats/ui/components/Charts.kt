package net.roz.connectstats.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.roz.connectstats.domain.model.ChartMetric
import net.roz.connectstats.domain.model.MapMetric
import net.roz.connectstats.domain.model.TrackPoint
import net.roz.connectstats.domain.model.chartValue
import net.roz.connectstats.domain.model.formatChartValue
import net.roz.connectstats.domain.model.shortTitle
import net.roz.connectstats.domain.model.unit
import net.roz.connectstats.domain.stats.HistogramBin
import net.roz.connectstats.domain.stats.ScatterPoint
import net.roz.connectstats.domain.stats.TrendPoint
import net.roz.connectstats.domain.stats.chartSeries
import net.roz.connectstats.domain.stats.windowedSeries
import net.roz.connectstats.ui.heatmap.BaseMapStyle
import net.roz.connectstats.ui.heatmap.MapCamera
import net.roz.connectstats.ui.heatmap.OsmTileLayer
import net.roz.connectstats.ui.heatmap.fitCamera
import net.roz.connectstats.ui.heatmap.geoToScreen
import net.roz.connectstats.ui.heatmap.pan
import net.roz.connectstats.ui.heatmap.zoomBy
import net.roz.connectstats.ui.theme.toComposeColor
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

fun metricColor(t: Float): Color {
    val clamped = t.coerceIn(0f, 1f)
    val r = if (clamped < 0.5f) clamped * 2f else 1f
    val g = if (clamped < 0.5f) 0.85f else 1f - (clamped - 0.5f) * 1.6f
    val b = if (clamped < 0.5f) 1f - clamped * 1.4f else 0.15f
    return Color(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f))
}

fun TrackPoint.valueOf(metric: MapMetric): Double? = when (metric) {
    MapMetric.HEART_RATE -> heartRate
    MapMetric.SPEED -> speedMps
    MapMetric.POWER -> power
    MapMetric.CADENCE -> cadence
    MapMetric.ELEVATION -> altitudeMeters
    MapMetric.GRADE -> gradePercent
}

fun TrackPoint.valueOf(metric: ChartMetric): Double? = chartValue(metric)

@Composable
fun ChartCard(
    title: String,
    modifier: Modifier = Modifier,
    headline: String? = null,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(colors.surface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            title.uppercase(Locale.getDefault()),
            style = MaterialTheme.typography.labelMedium,
            color = colors.onSurfaceVariant,
            letterSpacing = 1.1.sp,
            fontWeight = FontWeight.SemiBold,
        )
        if (!headline.isNullOrBlank()) {
            Text(
                headline,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = colors.onSurface,
            )
        }
        if (!subtitle.isNullOrBlank()) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }
        content()
    }
}

@Composable
fun GradientTrackMap(
    track: List<TrackPoint>,
    metric: MapMetric,
    modifier: Modifier = Modifier.height(240.dp),
) {
    val pts = remember(track) { track.filter { it.latitude != null && it.longitude != null } }
    val values = pts.map { it.valueOf(metric) }
    val minV = values.filterNotNull().minOrNull() ?: 0.0
    val maxV = values.filterNotNull().maxOrNull() ?: 1.0
    val colors = MaterialTheme.colorScheme
    val dark = colors.background.luminance() < 0.5f
    val baseStyle = if (dark) BaseMapStyle.DARK else BaseMapStyle.STREETS
    val trackKey = pts.firstOrNull()?.activityId ?: pts.size
    var camera by remember(trackKey) {
        val lats = pts.mapNotNull { it.latitude }
        val lons = pts.mapNotNull { it.longitude }
        mutableStateOf(
            MapCamera(
                lats.takeIf { it.isNotEmpty() }?.average() ?: 47.3769,
                lons.takeIf { it.isNotEmpty() }?.average() ?: 8.5417,
                15.0,
            ),
        )
    }
    var mapSize by remember { mutableStateOf(IntSize.Zero) }
    val cameraState = rememberUpdatedState(camera)

    LaunchedEffect(trackKey, pts.size, mapSize.width, mapSize.height) {
        if (pts.size < 2 || mapSize.width < 8 || mapSize.height < 8) return@LaunchedEffect
        camera = fitCamera(
            minLat = pts.minOf { it.latitude!! },
            maxLat = pts.maxOf { it.latitude!! },
            minLon = pts.minOf { it.longitude!! },
            maxLon = pts.maxOf { it.longitude!! },
            width = mapSize.width.toFloat(),
            height = mapSize.height.toFloat(),
        )
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surfaceVariant)
            .onSizeChanged { mapSize = it }
            .pointerInput(pts) {
                detectTransformGestures { centroid, panChange, zoom, _ ->
                    val w = size.width.toFloat().coerceAtLeast(1f)
                    val h = size.height.toFloat().coerceAtLeast(1f)
                    var next = cameraState.value.zoomBy(zoom, centroid.x, centroid.y, w, h)
                    next = next.pan(panChange.x, panChange.y)
                    camera = next
                }
            },
    ) {
        OsmTileLayer(
            camera = camera,
            viewport = mapSize,
            style = baseStyle,
            dimForDarkTheme = false,
        )
        Canvas(Modifier.fillMaxSize()) {
            if (pts.size < 2) return@Canvas
            fun pt(p: TrackPoint) = camera.geoToScreen(p.latitude!!, p.longitude!!, size.width, size.height)
            for (i in 1 until pts.size) {
                val a = pts[i - 1]
                val b = pts[i]
                val v = b.valueOf(metric) ?: a.valueOf(metric)
                val t = if (v == null || maxV == minV) 0.5f else ((v - minV) / (maxV - minV)).toFloat()
                drawLine(
                    color = metricColor(t),
                    start = pt(a),
                    end = pt(b),
                    strokeWidth = 7f,
                    cap = StrokeCap.Round,
                )
            }
            drawCircle(Color(0xFF5CE6B8), 10f, pt(pts.first()))
            drawCircle(Color(0xFFE24B4B), 10f, pt(pts.last()))
        }
        Text(
            "Map colour = ${metric.name.lowercase().replace('_', ' ')}",
            style = MaterialTheme.typography.labelSmall,
            color = if (dark) Color.White.copy(alpha = 0.85f) else colors.onSurface,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(12.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(colors.surface.copy(alpha = 0.82f))
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
fun TrackChart(
    track: List<TrackPoint>,
    metrics: Set<ChartMetric>,
    metricUnits: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val ordered = remember(track, metrics, metricUnits) {
        metrics.mapNotNull { m ->
            val series = chartSeries(track) { it.chartValue(m, metricUnits) }
            if (series.size < 2) null else m to series
        }
    }
    if (ordered.isEmpty()) {
        ChartEmpty()
        return
    }
    val trackKey = track.firstOrNull()?.activityId ?: track.size
    var viewStart by remember(trackKey) { mutableStateOf(0f) }
    var viewEnd by remember(trackKey) { mutableStateOf(1f) }
    var selectedX by remember(trackKey) { mutableStateOf<Double?>(null) }
    val startState = rememberUpdatedState(viewStart)
    val endState = rememberUpdatedState(viewEnd)
    val visible = remember(ordered, viewStart, viewEnd) {
        ordered.map { (metric, series) -> metric to windowedSeries(series, viewStart, viewEnd) }
            .filter { it.second.size >= 2 }
    }
    if (visible.isEmpty()) {
        ChartEmpty()
        return
    }
    val primary = visible.first()
    val secondary = visible.getOrNull(1)
    val minX = visible.minOf { it.second.minOf { p -> p.first } }
    val maxX = visible.maxOf { it.second.maxOf { p -> p.first } }
    val xSpan = (maxX - minX).coerceAtLeast(1e-3)
    val colors = MaterialTheme.colorScheme
    val grid = colors.outline.copy(alpha = 0.45f)
    val cursor = colors.onSurface
    val primaryTicks = niceAxisTicks(primary.second, primary.first)
    val primaryRange = primaryTicks.first() to primaryTicks.last()
    val secondaryTicks = secondary?.let { niceAxisTicks(it.second, it.first) }.orEmpty()
    val secondaryRange = secondaryTicks.takeIf { it.size >= 2 }?.let { it.first() to it.last() }

    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            AxisUnit(primary.first.unit(metricUnits), chartLineColor(primary.first))
            if (secondary != null) {
                AxisUnit(secondary.first.unit(metricUnits), chartLineColor(secondary.first))
            }
        }
        Row(Modifier.fillMaxWidth().height(200.dp)) {
            AxisLabels(
                primaryTicks.reversed().map { formatChartTick(primary.first, it) },
                color = chartLineColor(primary.first),
            )
            Canvas(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clipToBounds()
                    .pointerInput(trackKey) {
                        detectTransformGestures { centroid, pan, zoom, _ ->
                            val w = size.width.toFloat().coerceAtLeast(1f)
                            val start = startState.value
                            val end = endState.value
                            val span = (end - start).coerceIn(0.05f, 1f)
                            val zooming = abs(zoom - 1f) > 0.001f
                            var newSpan = if (zooming) (span / zoom).coerceIn(0.05f, 1f) else span
                            val focus = (centroid.x / w).coerceIn(0f, 1f)
                            val focusX = start + span * focus
                            var nextStart = focusX - newSpan * focus
                            var nextEnd = nextStart + newSpan
                            if (newSpan < 0.999f) {
                                val panFrac = -pan.x / w * newSpan
                                nextStart += panFrac
                                nextEnd += panFrac
                            }
                            if (nextStart < 0f) {
                                nextEnd -= nextStart
                                nextStart = 0f
                            }
                            if (nextEnd > 1f) {
                                nextStart -= nextEnd - 1f
                                nextEnd = 1f
                            }
                            viewStart = nextStart.coerceIn(0f, 0.95f)
                            viewEnd = nextEnd.coerceIn(viewStart + 0.05f, 1f)
                        }
                    }
                    .pointerInput(visible, minX, xSpan) {
                        detectTapGestures(
                            onDoubleTap = {
                                viewStart = 0f
                                viewEnd = 1f
                                selectedX = null
                            },
                            onTap = { offset ->
                                val x = minX + (offset.x / size.width).coerceIn(0f, 1f) * xSpan
                                selectedX = x
                            },
                        )
                    },
            ) {
                if (visible.isEmpty()) return@Canvas
                primaryTicks.forEach { v ->
                    val y = yPos(v, primaryRange.first, primaryRange.second, size.height)
                    drawLine(grid, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                }
                visible.forEachIndexed { index, (metric, series) ->
                    val range = if (index == 0) primaryRange
                    else if (index == 1 && secondaryRange != null) secondaryRange
                    else valueRange(series)
                    val line = chartLineColor(metric)
                    val path = Path()
                    val fill = Path()
                    series.forEachIndexed { i, (x, y) ->
                        val px = ((x - minX) / xSpan * size.width).toFloat()
                        val py = yPos(y, range.first, range.second, size.height)
                        if (i == 0) {
                            path.moveTo(px, py)
                            fill.moveTo(px, size.height)
                            fill.lineTo(px, py)
                        } else {
                            path.lineTo(px, py)
                            fill.lineTo(px, py)
                        }
                    }
                    if (index == 0) {
                        fill.lineTo(size.width, size.height)
                        fill.close()
                        drawPath(fill, Brush.verticalGradient(listOf(line.copy(alpha = 0.22f), Color.Transparent)))
                    }
                    drawPath(path, line, style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round))
                }
                selectedX?.let { x ->
                    val px = ((x - minX) / xSpan * size.width).toFloat()
                    drawLine(cursor.copy(alpha = 0.35f), Offset(px, 0f), Offset(px, size.height), 1.5.dp.toPx())
                    visible.forEachIndexed { index, (metric, series) ->
                        val pt = nearest(series, x) ?: return@forEachIndexed
                        val range = if (index == 0) primaryRange
                        else if (index == 1 && secondaryRange != null) secondaryRange
                        else valueRange(series)
                        val py = yPos(pt.second, range.first, range.second, size.height)
                        drawCircle(cursor, 5.dp.toPx(), Offset(px, py))
                        drawCircle(chartLineColor(metric), 3.dp.toPx(), Offset(px, py))
                    }
                }
            }
            if (secondary != null && secondaryRange != null) {
                AxisLabels(
                    secondaryTicks.reversed().map { formatChartTick(secondary.first, it) },
                    color = chartLineColor(secondary.first),
                    alignEnd = false,
                )
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 46.dp, top = 8.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            visible.forEach { (metric, series) ->
                val color = chartLineColor(metric)
                val value = selectedX?.let { nearest(series, it)?.second }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(color))
                    Text(
                        buildString {
                            append(metric.shortTitle())
                            if (value != null) {
                                append(' ')
                                append(formatChartValue(metric, value))
                            }
                            append(' ')
                            append(metric.unit(metricUnits))
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
        }
        Text(
            if (viewEnd - viewStart < 0.999f) "Pinch to zoom · double-tap to reset" else "Pinch to zoom · tap a line for values",
            Modifier.padding(start = 46.dp, top = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant.copy(alpha = 0.8f),
        )
    }
}

@Composable
private fun AxisUnit(unit: String, color: Color) {
    Text(
        unit,
        modifier = Modifier.width(46.dp).padding(bottom = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        color = color,
        maxLines = 1,
        textAlign = TextAlign.Center,
    )
}

private fun chartLineColor(metric: ChartMetric): Color = when (metric) {
    ChartMetric.HEART_RATE -> Color(0xFFE24B4B)
    ChartMetric.PACE -> Color(0xFF7C8CFF)
    ChartMetric.SPEED -> Color(0xFF3583F3)
    ChartMetric.POWER -> Color(0xFFF5A524)
    ChartMetric.CADENCE -> Color(0xFF1AA6C4)
    ChartMetric.ELEVATION -> Color(0xFFC8A26A)
    ChartMetric.GRADE -> Color(0xFF8B9BB4)
}

private fun niceAxisTicks(series: List<Pair<Double, Double>>, metric: ChartMetric): List<Double> {
    val minV = series.minOf { it.second }
    val maxV = series.maxOf { it.second }
    val clampZero = metric == ChartMetric.SPEED || metric == ChartMetric.POWER ||
        metric == ChartMetric.HEART_RATE || metric == ChartMetric.CADENCE
    return axisTicks(minV, maxV, 4, clampZero)
}

private fun valueRange(series: List<Pair<Double, Double>>): Pair<Double, Double> {
    val minV = series.minOf { it.second }
    val maxV = series.maxOf { it.second }
    val ticks = axisTicks(minV, maxV, 4)
    return ticks.first() to ticks.last()
}

private fun yPos(value: Double, minV: Double, maxV: Double, height: Float): Float {
    val span = (maxV - minV).coerceAtLeast(1e-6)
    return (height - ((value - minV) / span * height)).toFloat()
}

private fun nearest(series: List<Pair<Double, Double>>, x: Double): Pair<Double, Double>? =
    series.minByOrNull { abs(it.first - x) }

private fun formatChartTick(metric: ChartMetric, value: Double): String =
    if (metric == ChartMetric.PACE) formatChartValue(metric, value) else formatTick(value)

@Composable
fun BarChart(
    points: List<TrendPoint>,
    modifier: Modifier = Modifier,
    barColor: Color? = null,
    yFormatter: (Double) -> String = { String.format(Locale.US, "%.0f", it) },
) {
    val colors = MaterialTheme.colorScheme
    val fill = barColor ?: colors.primary
    val grid = colors.outline.copy(alpha = 0.45f)
    val labelColor = colors.onSurfaceVariant
    val maxV = remember(points) { niceMax(points.maxOfOrNull { it.value } ?: 1.0) }
    val ticks = remember(maxV) { (0..4).map { maxV * it / 4.0 } }
    val xStep = max(1, (points.size - 1) / 5)

    Column(modifier.fillMaxWidth()) {
        if (points.isEmpty()) {
            ChartEmpty()
            return@Column
        }
        Row(Modifier.fillMaxWidth().height(180.dp)) {
            AxisLabels(ticks.reversed().map(yFormatter))
            Canvas(Modifier.weight(1f).fillMaxHeight()) {
                ticks.forEach { v ->
                    val y = size.height * (1f - (v / maxV).toFloat())
                    drawLine(grid, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                }
                val slot = size.width / points.size
                val gap = (slot * 0.34f).coerceIn(6f, 14f)
                val barW = (slot - gap).coerceAtLeast(4f)
                val radius = 8.dp.toPx()
                points.forEachIndexed { i, p ->
                    val h = (p.value / maxV * size.height).toFloat()
                    if (h <= 0f) return@forEachIndexed
                    val left = i * slot + (slot - barW) / 2f
                    drawTopRoundedBar(fill, left, size.height - h.coerceAtLeast(2f), barW, h.coerceAtLeast(2f), radius)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 40.dp, top = 8.dp)) {
            points.forEachIndexed { i, p ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    if (i % xStep == 0 || i == points.lastIndex) {
                        Text(
                            p.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = labelColor,
                            maxLines = 1,
                            overflow = TextOverflow.Clip,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun HistogramChart(bins: List<HistogramBin>, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val grid = colors.outline.copy(alpha = 0.45f)
    val labelColor = colors.onSurfaceVariant
    val maxC = bins.maxOfOrNull { it.count }?.coerceAtLeast(1) ?: 1
    val maxV = niceMax(maxC.toDouble())
    val ticks = remember(maxV) { (0..4).map { maxV * it / 4.0 } }
    val xStep = max(1, (bins.size - 1) / 4)

    Column(modifier.fillMaxWidth()) {
        if (bins.isEmpty()) {
            ChartEmpty()
            return@Column
        }
        Row(Modifier.fillMaxWidth().height(160.dp)) {
            AxisLabels(ticks.reversed().map { String.format(Locale.US, "%.0f", it) })
            Canvas(Modifier.weight(1f).fillMaxHeight()) {
                ticks.forEach { v ->
                    val y = size.height * (1f - (v / maxV).toFloat())
                    drawLine(grid, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                }
                val slot = size.width / bins.size
                val gap = (slot * 0.28f).coerceIn(4f, 10f)
                val barW = (slot - gap).coerceAtLeast(3f)
                val radius = 6.dp.toPx()
                bins.forEachIndexed { i, b ->
                    val h = (b.count / maxV * size.height).toFloat()
                    if (h <= 0f) return@forEachIndexed
                    val left = i * slot + (slot - barW) / 2f
                    drawTopRoundedBar(
                        metricColor(i / max(1f, (bins.size - 1).toFloat())),
                        left,
                        size.height - h.coerceAtLeast(2f),
                        barW,
                        h.coerceAtLeast(2f),
                        radius,
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 40.dp, top = 8.dp)) {
            bins.forEachIndexed { i, b ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    if (i % xStep == 0 || i == bins.lastIndex) {
                        Text(
                            b.label.substringBefore(' '),
                            style = MaterialTheme.typography.labelSmall,
                            color = labelColor,
                            maxLines = 1,
                            overflow = TextOverflow.Clip,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ScatterChart(points: List<ScatterPoint>, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val grid = colors.outline.copy(alpha = 0.45f)
    val minX = points.minOfOrNull { it.x } ?: 0.0
    val maxX = points.maxOfOrNull { it.x } ?: 1.0
    val minY = points.minOfOrNull { it.y } ?: 0.0
    val maxY = points.maxOfOrNull { it.y } ?: 1.0
    val yTicks = remember(minY, maxY) { axisTicks(minY, maxY, 4) }
    val ySpan = (maxY - minY).coerceAtLeast(1e-6)
    val xSpan = (maxX - minX).coerceAtLeast(1e-6)

    Column(modifier.fillMaxWidth()) {
        if (points.size < 2) {
            ChartEmpty("Need more activities")
            return@Column
        }
        Row(Modifier.fillMaxWidth().height(180.dp)) {
            AxisLabels(yTicks.reversed().map { formatTick(it) })
            Canvas(Modifier.weight(1f).fillMaxHeight()) {
                yTicks.forEach { v ->
                    val y = size.height * (1f - ((v - minY) / ySpan).toFloat())
                    drawLine(grid, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                }
                points.forEach { p ->
                    val px = (((p.x - minX) / xSpan) * (size.width - 16f) + 8f).toFloat()
                    val py = (size.height - 8f - (p.y - minY) / ySpan * (size.height - 16f)).toFloat()
                    drawCircle(p.type.colorArgb.toComposeColor(), 6.dp.toPx(), Offset(px, py))
                }
            }
        }
    }
}

@Composable
private fun AxisLabels(
    labels: List<String>,
    color: Color = Color.Unspecified,
    alignEnd: Boolean = true,
) {
    val labelColor = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else color
    Column(
        Modifier.width(46.dp).fillMaxHeight().padding(horizontal = 4.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start,
    ) {
        labels.forEach {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = labelColor,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ChartEmpty(message: String = "No data") {
    Box(
        Modifier.fillMaxWidth().height(120.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}

private fun DrawScope.drawTopRoundedBar(
    color: Color,
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    radius: Float,
) {
    val r = radius.coerceAtMost(width / 2f).coerceAtMost(height)
    val path = Path().apply {
        moveTo(left, top + height)
        lineTo(left, top + r)
        quadraticTo(left, top, left + r, top)
        lineTo(left + width - r, top)
        quadraticTo(left + width, top, left + width, top + r)
        lineTo(left + width, top + height)
        close()
    }
    drawPath(path, color)
}

private fun niceMax(raw: Double): Double {
    if (raw <= 0.0) return 1.0
    val mag = 10.0.pow(floor(log10(raw)))
    val n = raw / mag
    val nice = when {
        n <= 1.0 -> 1.0
        n <= 2.0 -> 2.0
        n <= 5.0 -> 5.0
        else -> 10.0
    }
    return nice * mag
}

private fun axisTicks(minV: Double, maxV: Double, count: Int, clampZero: Boolean = false): List<Double> {
    val lo = if (clampZero) minV.coerceAtLeast(0.0) else minV
    val hi = maxV.coerceAtLeast(lo + 1e-6)
    if (hi <= lo) return listOf(lo)
    val span = niceNum(hi - lo, round = false)
    val step = niceNum(span / count, round = true).coerceAtLeast(1e-6)
    var start = floor(lo / step) * step
    if (clampZero) start = start.coerceAtLeast(0.0)
    val end = ceil(hi / step) * step
    val ticks = ArrayList<Double>(count + 2)
    var v = start
    var i = 0
    while (v <= end + step * 0.5 && i < 12) {
        ticks += v
        v += step
        i++
    }
    if (ticks.size < 2) ticks += start + step
    return ticks
}

private fun niceNum(range: Double, round: Boolean): Double {
    if (range <= 0.0) return 1.0
    val exp = floor(log10(range))
    val frac = range / 10.0.pow(exp)
    val nice = if (round) {
        when {
            frac < 1.5 -> 1.0
            frac < 3.0 -> 2.0
            frac < 7.0 -> 5.0
            else -> 10.0
        }
    } else {
        when {
            frac <= 1.0 -> 1.0
            frac <= 2.0 -> 2.0
            frac <= 5.0 -> 5.0
            else -> 10.0
        }
    }
    return nice * 10.0.pow(exp)
}

private fun formatTick(value: Double): String {
    val abs = kotlin.math.abs(value)
    return when {
        abs >= 100 -> String.format(Locale.US, "%.0f", value)
        abs >= 10 -> String.format(Locale.US, "%.0f", value)
        else -> String.format(Locale.US, "%.1f", value)
    }
}
