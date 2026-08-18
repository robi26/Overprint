package net.roz.connectstats.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.foundation.layout.width
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
import net.roz.connectstats.domain.stats.HistogramBin
import net.roz.connectstats.domain.stats.ScatterPoint
import net.roz.connectstats.domain.stats.TrendPoint
import net.roz.connectstats.ui.heatmap.BaseMapStyle
import net.roz.connectstats.ui.heatmap.MapCamera
import net.roz.connectstats.ui.heatmap.OsmTileLayer
import net.roz.connectstats.ui.heatmap.fitCamera
import net.roz.connectstats.ui.heatmap.geoToScreen
import net.roz.connectstats.ui.heatmap.pan
import net.roz.connectstats.ui.heatmap.zoomBy
import net.roz.connectstats.ui.theme.toComposeColor
import java.util.Locale
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

fun TrackPoint.valueOf(metric: ChartMetric): Double? = when (metric) {
    ChartMetric.HEART_RATE -> heartRate
    ChartMetric.PACE -> speedMps?.takeIf { it > 0.3 }?.let { 1000.0 / it }
    ChartMetric.SPEED -> speedMps
    ChartMetric.POWER -> power
    ChartMetric.CADENCE -> cadence
    ChartMetric.ELEVATION -> altitudeMeters
    ChartMetric.GRADE -> gradePercent
}

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
    metric: ChartMetric,
    modifier: Modifier = Modifier,
) {
    var selected by remember { mutableStateOf<Int?>(null) }
    val series = track.mapNotNull { p -> p.valueOf(metric)?.let { p.elapsedSeconds to it } }
    val minV = series.minOfOrNull { it.second } ?: 0.0
    val maxV = series.maxOfOrNull { it.second } ?: 1.0
    val minX = series.minOfOrNull { it.first } ?: 0.0
    val maxX = series.maxOfOrNull { it.first } ?: 1.0
    val colors = MaterialTheme.colorScheme
    val line = colors.primary
    val grid = colors.outline.copy(alpha = 0.45f)
    val cursor = colors.onSurface
    val fillTop = line.copy(alpha = 0.35f)
    val ticks = remember(minV, maxV) { axisTicks(minV, maxV, 4) }
    val span = (maxV - minV).coerceAtLeast(1e-6)

    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(180.dp)) {
            AxisLabels(ticks.reversed().map { formatTick(it) })
            Canvas(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .pointerInput(series) {
                        detectTapGestures { offset ->
                            if (series.isEmpty()) return@detectTapGestures
                            val t = (offset.x / size.width).coerceIn(0f, 1f)
                            selected = (t * (series.lastIndex)).toInt()
                        }
                    },
            ) {
                if (series.size < 2) return@Canvas
                ticks.forEach { v ->
                    val y = size.height * (1f - ((v - minV) / span).toFloat())
                    drawLine(grid, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                }
                val path = Path()
                val fill = Path()
                series.forEachIndexed { i, (x, y) ->
                    val px = ((x - minX) / (maxX - minX).coerceAtLeast(1e-3) * size.width).toFloat()
                    val py = (size.height - ((y - minV) / span * size.height)).toFloat()
                    if (i == 0) {
                        path.moveTo(px, py)
                        fill.moveTo(px, size.height)
                        fill.lineTo(px, py)
                    } else {
                        path.lineTo(px, py)
                        fill.lineTo(px, py)
                    }
                }
                fill.lineTo(size.width, size.height)
                fill.close()
                drawPath(fill, Brush.verticalGradient(listOf(fillTop, Color.Transparent)))
                drawPath(path, line, style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round))
                selected?.let { idx ->
                    val (x, y) = series.getOrNull(idx) ?: return@let
                    val px = ((x - minX) / (maxX - minX).coerceAtLeast(1e-3) * size.width).toFloat()
                    val py = (size.height - ((y - minV) / span * size.height)).toFloat()
                    drawLine(cursor.copy(alpha = 0.35f), Offset(px, 0f), Offset(px, size.height), 2.dp.toPx())
                    drawCircle(cursor, 7.dp.toPx(), Offset(px, py))
                    drawCircle(line, 4.dp.toPx(), Offset(px, py))
                }
            }
        }
        val label = selected?.let { series.getOrNull(it) }?.let { (_, v) ->
            "${metric.name.lowercase().replace('_', ' ')}  ${"%.1f".format(v)}"
        } ?: metric.name.lowercase().replace('_', ' ')
        Text(
            label,
            Modifier.padding(start = 40.dp, top = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            color = colors.onSurfaceVariant,
        )
    }
}

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
private fun AxisLabels(labels: List<String>) {
    Column(
        Modifier.width(40.dp).fillMaxHeight().padding(end = 6.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.End,
    ) {
        labels.forEach {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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

private fun axisTicks(minV: Double, maxV: Double, count: Int): List<Double> {
    if (maxV <= minV) return listOf(minV)
    return (0..count).map { minV + (maxV - minV) * it / count }
}

private fun formatTick(value: Double): String {
    val abs = kotlin.math.abs(value)
    return when {
        abs >= 100 -> String.format(Locale.US, "%.0f", value)
        abs >= 10 -> String.format(Locale.US, "%.0f", value)
        else -> String.format(Locale.US, "%.1f", value)
    }
}
