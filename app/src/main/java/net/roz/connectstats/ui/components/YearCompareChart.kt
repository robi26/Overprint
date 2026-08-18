package net.roz.connectstats.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.times
import net.roz.connectstats.domain.stats.YearDistanceSeries

private val yearPalette = listOf(
    Color(0xFF2E6BFF),
    Color(0xFFE24B4B),
    Color(0xFF1AA6C4),
    Color(0xFFC8A26A),
    Color(0xFF5B8C5A),
    Color(0xFFF169EF),
    Color(0xFFA6BB82),
    Color(0xFF7C8CFF),
)

private val monthTicks = listOf(
    1 to "Jan",
    60 to "Mar",
    121 to "May",
    182 to "Jul",
    244 to "Sep",
    305 to "Nov",
    365 to "Dec",
)

@Composable
fun YearCompareChart(
    series: List<YearDistanceSeries>,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val grid = colors.outline.copy(alpha = 0.45f)
    val labelColor = colors.onSurfaceVariant
    val currentColor = colors.onSurface
    val bestFill = colors.onSurface.copy(alpha = 0.12f)
    val maxV = remember(series) { niceMax(series.maxOfOrNull { it.totalKm } ?: 1.0) }
    val yTicks = remember(maxV) { (0..4).map { maxV * it / 4.0 } }
    val xMax = 365f
    val lineColors = remember(series) {
        series.mapIndexed { i, s ->
            when {
                s.isCurrent -> currentColor
                s.isBest -> Color(0xFF5C5C5C)
                else -> yearPalette[i % yearPalette.size]
            }
        }
    }

    Column(modifier.fillMaxWidth()) {
        if (series.isEmpty()) {
            ChartEmpty()
            return@Column
        }
        Row(Modifier.fillMaxWidth().height(200.dp)) {
            AxisLabels(yTicks.reversed().map { formatTick(it) })
            Canvas(Modifier.weight(1f).fillMaxHeight()) {
                val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)
                yTicks.forEach { v ->
                    val y = size.height * (1f - (v / maxV).toFloat())
                    drawLine(grid, Offset(0f, y), Offset(size.width, y), 1.dp.toPx(), pathEffect = dash)
                }
                fun xPos(day: Int) = (day / xMax).coerceIn(0f, 1f) * size.width
                fun yPos(km: Double) = (size.height * (1f - (km / maxV).toFloat())).coerceIn(0f, size.height)

                series.forEach { s ->
                    if (!s.isBest || s.points.isEmpty()) return@forEach
                    val fill = Path()
                    s.points.forEachIndexed { i, (day, km) ->
                        val px = xPos(day)
                        val py = yPos(km)
                        if (i == 0) {
                            fill.moveTo(px, size.height)
                            fill.lineTo(px, py)
                        } else {
                            fill.lineTo(px, py)
                        }
                    }
                    fill.lineTo(xPos(s.points.last().first), size.height)
                    fill.close()
                    drawPath(fill, bestFill)
                }
                series.sortedBy { it.isCurrent }.forEach { s ->
                    val index = series.indexOf(s)
                    if (s.points.size < 2) return@forEach
                    val path = Path()
                    s.points.forEachIndexed { i, (day, km) ->
                        val px = xPos(day)
                        val py = yPos(km)
                        if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                    }
                    val stroke = if (s.isCurrent) 2.dp.toPx() else 1.5.dp.toPx()
                    drawPath(
                        path,
                        lineColors[index],
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().padding(start = 40.dp, top = 6.dp).height(16.dp)) {
            monthTicks.forEach { (day, label) ->
                val x = maxWidth * (day / xMax).coerceIn(0f, 1f)
                Text(
                    label,
                    modifier = Modifier.padding(start = (x - 12.dp).coerceAtLeast(0.dp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = labelColor,
                )
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 40.dp, top = 8.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            series.forEachIndexed { index, s ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Canvas(Modifier.width(18.dp).height(4.dp)) {
                        drawLine(
                            lineColors[index],
                            Offset(0f, size.height / 2f),
                            Offset(size.width, size.height / 2f),
                            if (s.isCurrent) 2.5.dp.toPx() else 2.dp.toPx(),
                            cap = StrokeCap.Round,
                        )
                    }
                    Text(
                        buildString {
                            append(s.year)
                            when {
                                s.isBest && s.isCurrent -> append(" (last, best)")
                                s.isBest -> append(" (best)")
                                s.isCurrent -> append(" (last)")
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = labelColor,
                    )
                }
            }
        }
    }
}
