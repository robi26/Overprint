package net.roz.connectstats.ui.heatmap

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import net.roz.connectstats.domain.format.Formatters
import net.roz.connectstats.domain.model.Activity
import net.roz.connectstats.domain.model.ActivityType
import net.roz.connectstats.domain.model.GeoPoint
import net.roz.connectstats.domain.model.GpsTrack
import net.roz.connectstats.ui.activities.ActivityRow
import net.roz.connectstats.ui.common.SportAndYearFilters
import net.roz.connectstats.ui.common.filterBySportAndYear
import net.roz.connectstats.ui.components.metricColor
import net.roz.connectstats.ui.theme.toComposeColor
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max

private data class MapCamera(
    val centerLat: Double,
    val centerLon: Double,
    val spanLat: Double,
)

@Composable
fun HeatmapScreen(
    activities: List<Activity>,
    tracks: List<GpsTrack>,
    loading: Boolean,
    fmt: Formatters,
    onLoadTracks: (List<String>) -> Unit,
    onOpen: (Activity) -> Unit,
) {
    var type by remember { mutableStateOf<ActivityType?>(null) }
    var year by remember { mutableStateOf<Int?>(null) }
    var camera by remember { mutableStateOf(MapCamera(47.0, 8.0, 0.4)) }
    var mapSize by remember { mutableStateOf(IntSize.Zero) }

    val byFilter = remember(activities, type, year) { filterBySportAndYear(activities, type, year) }
    val candidateIds = remember(byFilter) { byFilter.filter { it.hasTrack }.map { it.id } }
    LaunchedEffect(candidateIds) { onLoadTracks(candidateIds) }

    val trackById = remember(tracks) { tracks.associateBy { it.activityId } }
    val mapped = remember(byFilter, trackById) { byFilter.filter { it.id in trackById } }
    val mappedTracks = remember(mapped, trackById) { mapped.mapNotNull { trackById[it.id] } }
    val chipSource = remember(activities, type) {
        val base = activities.filter { it.hasTrack }
        if (type == null) base else base.filter { it.type == type }
    }

    LaunchedEffect(type, year, mappedTracks.size, mapSize.width, mapSize.height) {
        if (mappedTracks.isEmpty() || mapSize.width <= 0 || mapSize.height <= 0) return@LaunchedEffect
        camera = fitCamera(mappedTracks, mapSize.width.toFloat() / mapSize.height.toFloat())
    }

    val aspect = if (mapSize.height == 0) 1f else mapSize.width.toFloat() / mapSize.height.toFloat()
    val bounds = remember(camera, aspect) { camera.bounds(aspect) }
    val visibleActivities = remember(mapped, trackById, bounds, mapSize) {
        if (mapSize.width <= 0) mapped
        else mapped.filter { act ->
            trackById[act.id]?.intersects(bounds.south, bounds.north, bounds.west, bounds.east) == true
        }
    }
    val visibleTracks = remember(visibleActivities, trackById) {
        visibleActivities.mapNotNull { trackById[it.id] }
    }

    Column(Modifier.fillMaxSize()) {
        SportAndYearFilters(
            activities = chipSource,
            type = type,
            year = year,
            onType = { type = it },
            onYear = { year = it },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1.15f)
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surface),
        ) {
            when {
                loading && candidateIds.isNotEmpty() && mappedTracks.isEmpty() -> {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
                mappedTracks.isEmpty() -> {
                    Text(
                        "No GPS tracks for this filter.\nImport FIT files or load demo data.",
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> {
                    HeatmapCanvas(
                        tracks = visibleTracks,
                        activities = visibleActivities,
                        camera = camera,
                        onCamera = { camera = it },
                        modifier = Modifier
                            .fillMaxSize()
                            .onSizeChanged { mapSize = it },
                    )
                    Column(
                        Modifier.align(Alignment.BottomEnd).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilledTonalIconButton(onClick = { camera = camera.zoom(0.7f) }) {
                            Icon(Icons.Outlined.Add, contentDescription = "Zoom in")
                        }
                        FilledTonalIconButton(onClick = { camera = camera.zoom(1.4f) }) {
                            Icon(Icons.Outlined.Remove, contentDescription = "Zoom out")
                        }
                    }
                }
            }
        }
        Text(
            "${visibleActivities.size} tracks in view",
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (mappedTracks.isEmpty()) {
                item {
                    Text(
                        "No GPS tracks for this filter.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (visibleActivities.isEmpty()) {
                item {
                    Text(
                        "Zoom out or pan to see tracks.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(visibleActivities, key = { it.id }) { act ->
                    ActivityRow(act, fmt, onClick = { onOpen(act) })
                }
            }
        }
    }
}

@Composable
private fun HeatmapCanvas(
    tracks: List<GpsTrack>,
    activities: List<Activity>,
    camera: MapCamera,
    onCamera: (MapCamera) -> Unit,
    modifier: Modifier = Modifier,
) {
    val typeById = remember(activities) { activities.associate { it.id to it.type } }
    val cameraState = rememberUpdatedState(camera)
    val onCameraState = rememberUpdatedState(onCamera)
    Canvas(
        modifier.pointerInput(Unit) {
            detectTransformGestures { centroid, pan, zoom, _ ->
                val cam = cameraState.value
                val w = size.width.toFloat()
                val h = size.height.toFloat().coerceAtLeast(1f)
                val aspect = w / h
                val focus = cam.screenToGeo(centroid.x, centroid.y, w, h, aspect)
                var next = cam.zoom(1f / zoom)
                val newFocus = next.screenToGeo(centroid.x, centroid.y, w, h, aspect)
                next = next.copy(
                    centerLat = next.centerLat + (focus.lat - newFocus.lat),
                    centerLon = next.centerLon + (focus.lon - newFocus.lon),
                )
                val b = next.bounds(aspect)
                val dLon = -pan.x / w * (b.east - b.west)
                val dLat = pan.y / h * (b.north - b.south)
                onCameraState.value(next.copy(centerLat = next.centerLat + dLat, centerLon = next.centerLon + dLon))
            }
        },
    ) {
        if (size.width < 8f || size.height < 8f) return@Canvas
        val aspect = size.width / size.height
        val b = camera.bounds(aspect)
        val latSpan = (b.north - b.south).coerceAtLeast(1e-8)
        val lonSpan = (b.east - b.west).coerceAtLeast(1e-8)
        fun x(lon: Double) = ((lon - b.west) / lonSpan * size.width).toFloat()
        fun y(lat: Double) = ((b.north - lat) / latSpan * size.height).toFloat()

        val cols = 72
        val rows = max(48, (72 * size.height / size.width).toInt().coerceIn(40, 110))
        val grid = IntArray(cols * rows)
        tracks.forEach { track ->
            val pts = track.points
            for (i in 1 until pts.size) {
                val x0 = (x(pts[i - 1].lon) / size.width * cols)
                val y0 = (y(pts[i - 1].lat) / size.height * rows)
                val x1 = (x(pts[i].lon) / size.width * cols)
                val y1 = (y(pts[i].lat) / size.height * rows)
                stampSegment(grid, x0, y0, x1, y1, cols, rows)
            }
        }
        val maxCount = grid.max().coerceAtLeast(1)
        val cellW = size.width / cols
        val cellH = size.height / rows
        val logMax = ln(maxCount + 1f)
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val count = grid[r * cols + c]
                if (count <= 0) continue
                val t = (ln(count + 1f) / logMax).coerceIn(0.15f, 1f)
                drawRect(
                    color = metricColor(t).copy(alpha = 0.28f + 0.55f * t),
                    topLeft = Offset(c * cellW, r * cellH),
                    size = Size(cellW + 0.5f, cellH + 0.5f),
                )
            }
        }
        tracks.forEach { track ->
            val color = (typeById[track.activityId]?.colorArgb?.toComposeColor() ?: Color(0xFF3583F3))
                .copy(alpha = 0.55f)
            val pts = track.points
            for (i in 1 until pts.size) {
                drawLine(
                    color = color,
                    start = Offset(x(pts[i - 1].lon), y(pts[i - 1].lat)),
                    end = Offset(x(pts[i].lon), y(pts[i].lat)),
                    strokeWidth = 3.5f,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

private data class GeoBounds(
    val south: Double,
    val north: Double,
    val west: Double,
    val east: Double,
)

private fun MapCamera.bounds(aspect: Float): GeoBounds {
    val cosLat = cos(Math.toRadians(centerLat)).coerceAtLeast(0.2)
    val spanLon = spanLat * aspect / cosLat
    return GeoBounds(
        south = centerLat - spanLat / 2,
        north = centerLat + spanLat / 2,
        west = centerLon - spanLon / 2,
        east = centerLon + spanLon / 2,
    )
}

private fun MapCamera.screenToGeo(px: Float, py: Float, width: Float, height: Float, aspect: Float): GeoPoint {
    val b = bounds(aspect)
    val lon = b.west + (px / width) * (b.east - b.west)
    val lat = b.north - (py / height) * (b.north - b.south)
    return GeoPoint(lat, lon)
}

private fun MapCamera.zoom(factor: Float): MapCamera =
    copy(spanLat = (spanLat * factor).coerceIn(0.002, 40.0))

private fun fitCamera(tracks: List<GpsTrack>, aspect: Float): MapCamera {
    val minLat = tracks.minOf { it.minLat }
    val maxLat = tracks.maxOf { it.maxLat }
    val minLon = tracks.minOf { it.minLon }
    val maxLon = tracks.maxOf { it.maxLon }
    val centerLat = (minLat + maxLat) / 2
    val centerLon = (minLon + maxLon) / 2
    val latSpan = (maxLat - minLat).coerceAtLeast(0.008)
    val lonSpan = (maxLon - minLon).coerceAtLeast(0.008)
    val cosLat = cos(Math.toRadians(centerLat)).coerceAtLeast(0.2)
    val spanForLon = lonSpan * 1.2 * cosLat / aspect.coerceAtLeast(0.4f)
    return MapCamera(centerLat, centerLon, max(latSpan * 1.2, spanForLon))
}

private fun stampSegment(
    grid: IntArray,
    x0: Float,
    y0: Float,
    x1: Float,
    y1: Float,
    cols: Int,
    rows: Int,
) {
    val steps = max(1, hypot((x1 - x0).toDouble(), (y1 - y0).toDouble()).toInt())
    for (s in 0..steps) {
        val t = s / steps.toFloat()
        val c = (x0 + (x1 - x0) * t).toInt()
        val r = (y0 + (y1 - y0) * t).toInt()
        if (c in 0 until cols && r in 0 until rows) grid[r * cols + c]++
    }
}
