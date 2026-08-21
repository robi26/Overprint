package ch.steigis.overprint.ui.detail

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import ch.steigis.overprint.domain.format.Formatters
import ch.steigis.overprint.domain.model.Activity

@Composable
fun HeroStatCircles(activity: Activity, fmt: Formatters) {
    val time = fmt.duration(activity.durationSeconds) to "Time"
    val distance = fmt.heroDistance(activity.distanceMeters)
    val third = if (activity.type.usesPace) fmt.heroPace(activity.paceSecPerKm) else fmt.heroSpeed(activity.avgSpeedMps)
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(196.dp)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        val side = 128.dp
        val center = 148.dp
        val overlap = 26.dp
        val xOffset = min((maxWidth - side) / 2, (center + side) / 2 - overlap)
        HeroCircle(
            value = time.first,
            label = time.second,
            ring = Color(0xFF7C8CFF),
            size = side,
            textAlign = Alignment.CenterStart,
            modifier = Modifier.offset(x = -xOffset),
        )
        HeroCircle(
            value = third.first,
            label = third.second,
            ring = Color(0xFF5CE6B8),
            size = side,
            textAlign = Alignment.CenterEnd,
            modifier = Modifier.offset(x = xOffset),
        )
        HeroCircle(
            value = distance.first,
            label = distance.second,
            ring = Color(0xFFF5A524),
            size = center,
            textAlign = Alignment.Center,
        )
    }
}

@Composable
private fun HeroCircle(
    value: String,
    label: String,
    ring: Color,
    size: Dp,
    textAlign: Alignment,
    modifier: Modifier = Modifier,
) {
    val fill = MaterialTheme.colorScheme.surface
    val onSurface = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val textPadding = when (textAlign) {
        Alignment.CenterStart -> Modifier.padding(start = 16.dp, end = 40.dp)
        Alignment.CenterEnd -> Modifier.padding(start = 40.dp, end = 16.dp)
        else -> Modifier.padding(horizontal = 16.dp)
    }
    val align = when (textAlign) {
        Alignment.CenterStart -> TextAlign.Start
        Alignment.CenterEnd -> TextAlign.End
        else -> TextAlign.Center
    }
    Box(
        modifier
            .size(size)
            .shadow(8.dp, CircleShape, clip = false),
        contentAlignment = textAlign,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 5.dp.toPx()
            drawCircle(fill)
            drawArc(
                color = ring,
                startAngle = -215f,
                sweepAngle = 250f,
                useCenter = false,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        Column(textPadding, horizontalAlignment = when (textAlign) {
            Alignment.CenterStart -> Alignment.Start
            Alignment.CenterEnd -> Alignment.End
            else -> Alignment.CenterHorizontally
        }) {
            Text(
                value,
                color = onSurface,
                style = if (textAlign == Alignment.Center) {
                    MaterialTheme.typography.headlineSmall
                } else {
                    MaterialTheme.typography.titleLarge
                },
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                textAlign = align,
            )
            Text(
                label,
                color = muted,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 2,
                overflow = TextOverflow.Clip,
                textAlign = align,
            )
        }
    }
}
