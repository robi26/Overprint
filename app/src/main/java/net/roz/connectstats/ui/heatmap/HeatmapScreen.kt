package net.roz.connectstats.ui.heatmap

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import net.roz.connectstats.domain.format.Formatters
import net.roz.connectstats.domain.model.Activity
import net.roz.connectstats.domain.model.ActivityType
import net.roz.connectstats.domain.model.GpsTrack
import net.roz.connectstats.ui.activities.ActivityRow
import net.roz.connectstats.ui.common.SportAndYearFilters
import net.roz.connectstats.ui.common.filterBySportAndYear
import net.roz.connectstats.ui.components.metricColor
import net.roz.connectstats.ui.theme.toComposeColor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max

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
    var camera by remember { mutableStateOf(MapCamera(47.3769, 8.5417, 12.0)) }
    var mapSize by remember { mutableStateOf(IntSize.Zero) }
    var baseStyle by remember { mutableStateOf(BaseMapStyle.STREETS) }
    var showHeat by remember { mutableStateOf(true) }
    var showTracks by remember { mutableStateOf(true) }

    val byFilter = remember(activities, type, year) { filterBySportAndYear(activities, type, year) }
    val idsKey = remember(byFilter) { byFilter.map { it.id }.joinToString() }
    LaunchedEffect(idsKey) { onLoadTracks(byFilter.map { it.id }) }

    val trackById = remember(tracks) { tracks.associateBy { it.activityId } }
    val mapped = remember(byFilter, trackById) { byFilter.filter { it.id in trackById } }
    val mappedTracks = remember(mapped, trackById) { mapped.mapNotNull { trackById[it.id] } }
    val chipSource = remember(activities, type) {
        val base = if (type == null) activities else activities.filter { it.type == type }
        base.ifEmpty { activities }
    }

    LaunchedEffect(type, year, mappedTracks.size, mapSize.width, mapSize.height) {
        if (mappedTracks.isEmpty() || mapSize.width <= 0 || mapSize.height <= 0) return@LaunchedEffect
        camera = fitCamera(
            minLat = mappedTracks.minOf { it.minLat },
            maxLat = mappedTracks.maxOf { it.maxLat },
            minLon = mappedTracks.minOf { it.minLon },
            maxLon = mappedTracks.maxOf { it.maxLon },
            width = mapSize.width.toFloat(),
            height = mapSize.height.toFloat(),
        )
    }

    val bounds = remember(camera, mapSize) {
        if (mapSize.width <= 0) null
        else camera.geoBounds(mapSize.width.toFloat(), mapSize.height.toFloat())
    }
    val visibleActivities = remember(mapped, trackById, bounds, mapSize) {
        val b = bounds ?: return@remember mapped
        mapped.filter { act ->
            trackById[act.id]?.intersects(b.south, b.north, b.west, b.east) == true
        }
    }
    val visibleTracks = remember(visibleActivities, trackById) {
        visibleActivities.mapNotNull { trackById[it.id] }
    }
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f

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
                .background(MaterialTheme.colorScheme.surface)
                .onSizeChanged { mapSize = it },
        ) {
            MapViewport(
                camera = camera,
                onCamera = { camera = it },
                tracks = visibleTracks,
                activities = visibleActivities,
                baseStyle = baseStyle,
                showHeat = showHeat,
                showTracks = showTracks,
                dimTiles = darkTheme,
                modifier = Modifier.fillMaxSize(),
            )
            if (loading && mappedTracks.isEmpty()) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            } else if (mappedTracks.isEmpty()) {
                Text(
                    "No GPS tracks for this filter.",
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            LayerMenuButton(
                baseStyle = baseStyle,
                onBaseStyle = { baseStyle = it },
                showHeat = showHeat,
                onHeat = { showHeat = it },
                showTracks = showTracks,
                onTracks = { showTracks = it },
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            )
            Column(
                Modifier.align(Alignment.BottomEnd).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalIconButton(onClick = {
                    camera = camera.copy(zoom = (camera.zoom + 1).coerceIn(MIN_MAP_ZOOM, MAX_MAP_ZOOM))
                }) {
                    Icon(Icons.Outlined.Add, contentDescription = "Zoom in")
                }
                FilledTonalIconButton(onClick = {
                    camera = camera.copy(zoom = (camera.zoom - 1).coerceIn(MIN_MAP_ZOOM, MAX_MAP_ZOOM))
                }) {
                    Icon(Icons.Outlined.Remove, contentDescription = "Zoom out")
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
                    Text("No GPS tracks for this filter.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else if (visibleActivities.isEmpty()) {
                item {
                    Text("Zoom out or pan to see tracks.", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
private fun LayerMenuButton(
    baseStyle: BaseMapStyle,
    onBaseStyle: (BaseMapStyle) -> Unit,
    showHeat: Boolean,
    onHeat: (Boolean) -> Unit,
    showTracks: Boolean,
    onTracks: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        FilledTonalIconButton(onClick = { expanded = true }) {
            Icon(Icons.Outlined.Layers, contentDescription = "Map layers")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Text(
                "Basemap",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
            DropdownMenuItem(
                text = { Text("Streets") },
                onClick = {
                    onBaseStyle(BaseMapStyle.STREETS)
                    expanded = false
                },
                leadingIcon = {
                    RadioButton(selected = baseStyle == BaseMapStyle.STREETS, onClick = null)
                },
            )
            DropdownMenuItem(
                text = { Text("Dark streets") },
                onClick = {
                    onBaseStyle(BaseMapStyle.DARK)
                    expanded = false
                },
                leadingIcon = {
                    RadioButton(selected = baseStyle == BaseMapStyle.DARK, onClick = null)
                },
            )
            DropdownMenuItem(
                text = { Text("No map") },
                onClick = {
                    onBaseStyle(BaseMapStyle.NONE)
                    expanded = false
                },
                leadingIcon = {
                    RadioButton(selected = baseStyle == BaseMapStyle.NONE, onClick = null)
                },
            )
            HorizontalDivider()
            Text(
                "Overlays",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
            DropdownMenuItem(
                text = { Text("Heat") },
                onClick = { onHeat(!showHeat) },
                trailingIcon = { Checkbox(checked = showHeat, onCheckedChange = null) },
            )
            DropdownMenuItem(
                text = { Text("Tracks") },
                onClick = { onTracks(!showTracks) },
                trailingIcon = { Checkbox(checked = showTracks, onCheckedChange = null) },
            )
        }
    }
}

@Composable
private fun MapViewport(
    camera: MapCamera,
    onCamera: (MapCamera) -> Unit,
    tracks: List<GpsTrack>,
    activities: List<Activity>,
    baseStyle: BaseMapStyle,
    showHeat: Boolean,
    showTracks: Boolean,
    dimTiles: Boolean,
    modifier: Modifier = Modifier,
) {
    val cameraState = rememberUpdatedState(camera)
    val onCameraState = rememberUpdatedState(onCamera)
    val typeById = remember(activities) { activities.associate { it.id to it.type } }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    Box(
        modifier
            .onSizeChanged { viewport = it }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val cam = cameraState.value
                    val w = size.width.toFloat().coerceAtLeast(1f)
                    val h = size.height.toFloat().coerceAtLeast(1f)
                    var next = cam.zoomBy(zoom, centroid.x, centroid.y, w, h)
                    next = next.pan(pan.x, pan.y)
                    onCameraState.value(next)
                }
            },
    ) {
        OsmTileLayer(
            camera = camera,
            viewport = viewport,
            style = baseStyle,
            dimForDarkTheme = dimTiles,
        )
        TrackOverlay(
            tracks = tracks,
            typeById = typeById,
            camera = camera,
            showHeat = showHeat,
            showTracks = showTracks,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun TrackOverlay(
    tracks: List<GpsTrack>,
    typeById: Map<String, ActivityType>,
    camera: MapCamera,
    showHeat: Boolean,
    showTracks: Boolean,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        if (size.width < 8f || size.height < 8f) return@Canvas
        fun pt(lat: Double, lon: Double) = camera.geoToScreen(lat, lon, size.width, size.height)
        if (showHeat && tracks.isNotEmpty()) {
            val cols = 72
            val rows = max(48, (72 * size.height / size.width).toInt().coerceIn(40, 110))
            val grid = IntArray(cols * rows)
            tracks.forEach { track ->
                val pts = track.points
                for (i in 1 until pts.size) {
                    val a = pt(pts[i - 1].lat, pts[i - 1].lon)
                    val b = pt(pts[i].lat, pts[i].lon)
                    stampSegment(grid, a.x / size.width * cols, a.y / size.height * rows, b.x / size.width * cols, b.y / size.height * rows, cols, rows)
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
                        color = metricColor(t).copy(alpha = 0.22f + 0.5f * t),
                        topLeft = Offset(c * cellW, r * cellH),
                        size = Size(cellW + 0.5f, cellH + 0.5f),
                    )
                }
            }
        }
        if (showTracks) {
            tracks.forEach { track ->
                val color = (typeById[track.activityId]?.colorArgb?.toComposeColor() ?: Color(0xFF3583F3))
                    .copy(alpha = 0.85f)
                val pts = track.points
                for (i in 1 until pts.size) {
                    val a = pt(pts[i - 1].lat, pts[i - 1].lon)
                    val b = pt(pts[i].lat, pts[i].lon)
                    drawLine(color, a, b, strokeWidth = 3.5f, cap = StrokeCap.Round)
                }
            }
        }
    }
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
