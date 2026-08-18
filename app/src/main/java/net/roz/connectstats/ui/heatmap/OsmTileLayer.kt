package net.roz.connectstats.ui.heatmap

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow

internal enum class BaseMapStyle { STREETS, DARK, NONE }

internal const val OSM_USER_AGENT = "Overprint/1.0 (Android; OSM tiles)"

private data class VisibleTile(
    val key: String,
    val url: String,
    val left: Float,
    val top: Float,
    val drawPx: Float,
)

@Composable
internal fun OsmTileLayer(
    camera: MapCamera,
    viewport: IntSize,
    style: BaseMapStyle,
    dimForDarkTheme: Boolean,
    modifier: Modifier = Modifier,
) {
    if (style == BaseMapStyle.NONE || viewport.width < 8 || viewport.height < 8) return
    val tiles = remember(camera, viewport.width, viewport.height, style) {
        visibleTiles(camera, viewport, style)
    }
    val context = LocalContext.current
    val density = LocalDensity.current
    val filter = when {
        style == BaseMapStyle.DARK -> null
        dimForDarkTheme -> ColorFilter.colorMatrix(ColorMatrix().apply { setToScale(0.72f, 0.74f, 0.82f, 1f) })
        else -> null
    }
    Box(modifier.fillMaxSize()) {
        tiles.forEach { tile ->
            val sizeDp = with(density) { tile.drawPx.toDp() }
            key(tile.key) {
                AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(tile.url)
                    .addHeader("User-Agent", OSM_USER_AGENT)
                    .crossfade(false)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                colorFilter = filter,
                modifier = Modifier
                    .requiredSize(sizeDp)
                    .graphicsLayer {
                        translationX = tile.left
                        translationY = tile.top
                    },
                )
            }
        }
    }
}

private fun visibleTiles(camera: MapCamera, viewport: IntSize, style: BaseMapStyle): List<VisibleTile> {
    val z = camera.zoom.coerceIn(MIN_MAP_ZOOM, MAX_MAP_ZOOM)
    val zInt = z.toInt().coerceIn(2, 18)
    val draw = (MAP_TILE_SIZE * 2.0.pow(z - zInt)).toFloat()
    val cx = lonToX(camera.centerLon, z)
    val cy = latToY(camera.centerLat, z)
    val leftWorld = cx - viewport.width / 2.0
    val topWorld = cy - viewport.height / 2.0
    val n = 1 shl zInt
    val minTx = floor(leftWorld / draw).toInt()
    val maxTx = ceil((leftWorld + viewport.width) / draw).toInt()
    val minTy = floor(topWorld / draw).toInt().coerceAtLeast(0)
    val maxTy = ceil((topWorld + viewport.height) / draw).toInt().coerceAtMost(n - 1)
    val out = ArrayList<VisibleTile>((maxTx - minTx + 1).coerceAtLeast(1) * (maxTy - minTy + 1).coerceAtLeast(1))
    var count = 0
    for (ty in minTy..maxTy) {
        for (tx in minTx..maxTx) {
            if (count >= 48) return out
            val wrappedX = ((tx % n) + n) % n
            val url = tileUrl(style, zInt, wrappedX, ty)
            out += VisibleTile(
                key = "$zInt/$wrappedX/$ty/${style.name}",
                url = url,
                left = (tx * draw - leftWorld).toFloat(),
                top = (ty * draw - topWorld).toFloat(),
                drawPx = draw,
            )
            count++
        }
    }
    return out
}

private fun tileUrl(style: BaseMapStyle, z: Int, x: Int, y: Int): String = when (style) {
    BaseMapStyle.STREETS -> "https://tile.openstreetmap.org/$z/$x/$y.png"
    BaseMapStyle.DARK -> "https://basemaps.cartocdn.com/dark_all/$z/$x/$y.png"
    BaseMapStyle.NONE -> ""
}
