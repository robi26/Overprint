package net.roz.connectstats.ui.heatmap

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sinh
import kotlin.math.tan

internal const val MAP_TILE_SIZE = 256.0
internal const val MIN_MAP_ZOOM = 2.0
internal const val MAX_MAP_ZOOM = 18.0

internal data class MapCamera(
    val centerLat: Double,
    val centerLon: Double,
    val zoom: Double,
)

internal data class GeoBounds(
    val south: Double,
    val north: Double,
    val west: Double,
    val east: Double,
)

internal fun lonToX(lon: Double, zoom: Double): Double {
    val scale = MAP_TILE_SIZE * 2.0.pow(zoom)
    return (lon + 180.0) / 360.0 * scale
}

internal fun latToY(lat: Double, zoom: Double): Double {
    val latC = lat.coerceIn(-85.05112878, 85.05112878)
    val latRad = Math.toRadians(latC)
    val scale = MAP_TILE_SIZE * 2.0.pow(zoom)
    return (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * scale
}

internal fun xToLon(x: Double, zoom: Double): Double {
    val scale = MAP_TILE_SIZE * 2.0.pow(zoom)
    return x / scale * 360.0 - 180.0
}

internal fun yToLat(y: Double, zoom: Double): Double {
    val scale = MAP_TILE_SIZE * 2.0.pow(zoom)
    val n = PI - 2.0 * PI * y / scale
    return Math.toDegrees(atan(sinh(n)))
}

internal fun MapCamera.geoBounds(widthPx: Float, heightPx: Float): GeoBounds {
    val cx = lonToX(centerLon, zoom)
    val cy = latToY(centerLat, zoom)
    return GeoBounds(
        south = yToLat(cy + heightPx / 2.0, zoom),
        north = yToLat(cy - heightPx / 2.0, zoom),
        west = xToLon(cx - widthPx / 2.0, zoom),
        east = xToLon(cx + widthPx / 2.0, zoom),
    )
}

internal fun MapCamera.screenToGeo(px: Float, py: Float, width: Float, height: Float): Pair<Double, Double> {
    val cx = lonToX(centerLon, zoom)
    val cy = latToY(centerLat, zoom)
    val lon = xToLon(cx - width / 2.0 + px, zoom)
    val lat = yToLat(cy - height / 2.0 + py, zoom)
    return lat to lon
}

internal fun MapCamera.geoToScreen(lat: Double, lon: Double, width: Float, height: Float): androidx.compose.ui.geometry.Offset {
    val cx = lonToX(centerLon, zoom)
    val cy = latToY(centerLat, zoom)
    val x = (lonToX(lon, zoom) - cx + width / 2.0).toFloat()
    val y = (latToY(lat, zoom) - cy + height / 2.0).toFloat()
    return androidx.compose.ui.geometry.Offset(x, y)
}

internal fun MapCamera.pan(dxPx: Float, dyPx: Float): MapCamera {
    val cx = lonToX(centerLon, zoom) - dxPx
    val cy = latToY(centerLat, zoom) - dyPx
    return copy(centerLat = yToLat(cy, zoom), centerLon = xToLon(cx, zoom))
}

internal fun MapCamera.zoomBy(factor: Float, focusPx: Float, focusPy: Float, width: Float, height: Float): MapCamera {
    val (focusLat, focusLon) = screenToGeo(focusPx, focusPy, width, height)
    val nextZoom = (zoom + kotlin.math.log2(factor.toDouble())).coerceIn(MIN_MAP_ZOOM, MAX_MAP_ZOOM)
    val next = copy(zoom = nextZoom)
    val (newLat, newLon) = next.screenToGeo(focusPx, focusPy, width, height)
    return next.copy(
        centerLat = next.centerLat + (focusLat - newLat),
        centerLon = next.centerLon + (focusLon - newLon),
    )
}

internal fun fitCamera(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double, width: Float, height: Float): MapCamera {
    val centerLat = (minLat + maxLat) / 2
    val centerLon = (minLon + maxLon) / 2
    val dx0 = kotlin.math.abs(lonToX(maxLon, 0.0) - lonToX(minLon, 0.0)).coerceAtLeast(8.0)
    val dy0 = kotlin.math.abs(latToY(minLat, 0.0) - latToY(maxLat, 0.0)).coerceAtLeast(8.0)
    val zx = ln((width * 0.82) / dx0) / ln(2.0)
    val zy = ln((height * 0.82) / dy0) / ln(2.0)
    val zoom = minOf(zx, zy).coerceIn(MIN_MAP_ZOOM, MAX_MAP_ZOOM - 0.5)
    return MapCamera(centerLat, centerLon, zoom)
}
