package net.roz.connectstats.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import net.roz.connectstats.domain.model.ChartMetric
import net.roz.connectstats.domain.model.MapMetric
import net.roz.connectstats.domain.model.TrackPoint
import net.roz.connectstats.domain.stats.HistogramBin
import net.roz.connectstats.domain.stats.ScatterPoint
import net.roz.connectstats.domain.stats.TrendPoint
import net.roz.connectstats.ui.theme.toComposeColor
import kotlin.math.max

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
fun GradientTrackMap(
    track: List<TrackPoint>,
    metric: MapMetric,
    modifier: Modifier = Modifier.height(240.dp),
) {
    val pts = track.filter { it.latitude != null && it.longitude != null }
    val values = pts.map { it.valueOf(metric) }
    val minV = values.filterNotNull().minOrNull() ?: 0.0
    val maxV = values.filterNotNull().maxOrNull() ?: 1.0
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF0E223F), RoundedCornerShape(16.dp)),
    ) {
        Canvas(Modifier.fillMaxSize().padding(16.dp)) {
            if (pts.size < 2) return@Canvas
            val lats = pts.map { it.latitude!! }
            val lons = pts.map { it.longitude!! }
            val minLat = lats.min(); val maxLat = lats.max()
            val minLon = lons.min(); val maxLon = lons.max()
            val latSpan = (maxLat - minLat).coerceAtLeast(1e-5)
            val lonSpan = (maxLon - minLon).coerceAtLeast(1e-5)
            val pad = 8f
            fun x(lon: Double) = pad + ((lon - minLon) / lonSpan * (size.width - 2 * pad)).toFloat()
            fun y(lat: Double) = pad + ((maxLat - lat) / latSpan * (size.height - 2 * pad)).toFloat()
            for (i in 1 until pts.size) {
                val a = pts[i - 1]; val b = pts[i]
                val v = b.valueOf(metric) ?: a.valueOf(metric)
                val t = if (v == null || maxV == minV) 0.5f else ((v - minV) / (maxV - minV)).toFloat()
                drawLine(
                    color = metricColor(t),
                    start = Offset(x(a.longitude!!), y(a.latitude!!)),
                    end = Offset(x(b.longitude!!), y(b.latitude!!)),
                    strokeWidth = 7f,
                    cap = StrokeCap.Round,
                )
            }
            val start = pts.first()
            val end = pts.last()
            drawCircle(Color(0xFF5CE6B8), 10f, Offset(x(start.longitude!!), y(start.latitude!!)))
            drawCircle(Color(0xFFE24B4B), 10f, Offset(x(end.longitude!!), y(end.latitude!!)))
        }
        Text(
            "Map colour = ${metric.name.lowercase().replace('_', ' ')}",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
        )
    }
}

@Composable
fun TrackChart(
    track: List<TrackPoint>,
    metric: ChartMetric,
    modifier: Modifier = Modifier.height(180.dp),
) {
    var selected by remember { mutableStateOf<Int?>(null) }
    val series = track.mapNotNull { p -> p.valueOf(metric)?.let { p.elapsedSeconds to it } }
    val minV = series.minOfOrNull { it.second } ?: 0.0
    val maxV = series.maxOfOrNull { it.second } ?: 1.0
    val minX = series.minOfOrNull { it.first } ?: 0.0
    val maxX = series.maxOfOrNull { it.first } ?: 1.0
    Box(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(180.dp)
                .padding(12.dp)
                .pointerInput(series) {
                    detectTapGestures { offset ->
                        if (series.isEmpty()) return@detectTapGestures
                        val t = (offset.x / size.width).coerceIn(0f, 1f)
                        selected = (t * (series.lastIndex)).toInt()
                    }
                },
        ) {
            if (series.size < 2) return@Canvas
            val path = Path()
            val fill = Path()
            series.forEachIndexed { i, (x, y) ->
                val px = ((x - minX) / (maxX - minX).coerceAtLeast(1e-3) * size.width).toFloat()
                val py = (size.height - ((y - minV) / (maxV - minV).coerceAtLeast(1e-6) * size.height)).toFloat()
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
            drawPath(fill, Brush.verticalGradient(listOf(Color(0x553583F3), Color.Transparent)))
            drawPath(path, Color(0xFF3583F3), style = Stroke(width = 5f, cap = StrokeCap.Round))
            selected?.let { idx ->
                val (x, y) = series.getOrNull(idx) ?: return@let
                val px = ((x - minX) / (maxX - minX).coerceAtLeast(1e-3) * size.width).toFloat()
                val py = (size.height - ((y - minV) / (maxV - minV).coerceAtLeast(1e-6) * size.height)).toFloat()
                drawLine(Color.White.copy(0.4f), Offset(px, 0f), Offset(px, size.height), 2f)
                drawCircle(Color.White, 8f, Offset(px, py))
            }
        }
        val label = selected?.let { series.getOrNull(it) }?.let { (_, v) ->
            "${metric.name.lowercase().replace('_', ' ')}  ${"%.1f".format(v)}"
        } ?: metric.name.lowercase().replace('_', ' ')
        Text(label, Modifier.padding(12.dp), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun BarChart(points: List<TrendPoint>, modifier: Modifier = Modifier.height(160.dp), barColor: Color = Color(0xCC3583F3)) {
    Canvas(modifier.fillMaxWidth()) {
        if (points.isEmpty()) return@Canvas
        val maxV = points.maxOf { it.value }.coerceAtLeast(0.1)
        val slot = size.width / points.size
        points.forEachIndexed { i, p ->
            val h = (p.value / maxV * (size.height - 20f)).toFloat()
            drawRoundRect(
                color = barColor,
                topLeft = Offset(i * slot + 4f, size.height - h),
                size = Size(slot - 8f, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f, 8f),
            )
        }
    }
}

@Composable
fun HistogramChart(bins: List<HistogramBin>, modifier: Modifier = Modifier.height(140.dp)) {
    val maxC = bins.maxOfOrNull { it.count }?.coerceAtLeast(1) ?: 1
    Canvas(modifier.fillMaxWidth()) {
        if (bins.isEmpty()) return@Canvas
        val slot = size.width / bins.size
        bins.forEachIndexed { i, b ->
            val h = (b.count.toFloat() / maxC) * (size.height - 8f)
            drawRoundRect(
                color = metricColor(i / max(1f, (bins.size - 1).toFloat())),
                topLeft = Offset(i * slot + 3f, size.height - h),
                size = Size(slot - 6f, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f, 6f),
            )
        }
    }
}

@Composable
fun ScatterChart(points: List<ScatterPoint>, modifier: Modifier = Modifier.height(180.dp)) {
    Canvas(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))) {
        if (points.size < 2) return@Canvas
        val minX = points.minOf { it.x }; val maxX = points.maxOf { it.x }
        val minY = points.minOf { it.y }; val maxY = points.maxOf { it.y }
        points.forEach { p ->
            val px = ((p.x - minX) / (maxX - minX).coerceAtLeast(1e-6) * (size.width - 24) + 12).toFloat()
            val py = (size.height - 12 - (p.y - minY) / (maxY - minY).coerceAtLeast(1e-6) * (size.height - 24)).toFloat()
            drawCircle(p.type.colorArgb.toComposeColor(), 7f, Offset(px, py))
        }
    }
}
