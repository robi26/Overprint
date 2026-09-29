package ch.steigis.overprint.ui.detail

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ch.steigis.overprint.domain.format.Formatters
import ch.steigis.overprint.domain.model.Activity
import ch.steigis.overprint.domain.model.ActivityType
import ch.steigis.overprint.domain.model.Split
import ch.steigis.overprint.domain.model.SplitGroup
import ch.steigis.overprint.domain.model.SplitKind
import ch.steigis.overprint.ui.components.ChartCard
import java.util.Locale
import kotlin.math.roundToInt

private fun SplitKind.color(): Color = when (this) {
    SplitKind.RUN -> Color(0xFF3FA2F7)
    SplitKind.WALK -> Color(0xFFFF9F2E)
    SplitKind.IDLE -> Color(0xFF8B9BB4)
    SplitKind.CLIMB -> Color(0xFFE53935)
    SplitKind.SEATED -> Color(0xFF3583F3)
    SplitKind.STANDING -> Color(0xFFF5A524)
}

/** FIT stores foot-sport cadence per foot; show steps per minute like Garmin. */
private fun Activity.cadenceLabel(split: Split): String {
    val raw = split.avgCadence ?: return "—"
    val steps = type == ActivityType.RUNNING || type == ActivityType.WALKING || type == ActivityType.HIKING
    return (if (steps && split.kind.group != SplitGroup.RIDER_POSITION) raw * 2 else raw).roundToInt().toString()
}

@Composable
internal fun SplitsTab(activity: Activity, splits: List<Split>, fmt: Formatters) {
    val groups = remember(splits) { splits.groupBy { it.kind.group } }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SplitGroup.entries.forEach { group ->
            val groupSplits = groups[group] ?: return@forEach
            item(key = group.name) { SplitGroupCard(group, groupSplits, activity, fmt) }
        }
    }
}

@Composable
private fun SplitGroupCard(group: SplitGroup, splits: List<Split>, activity: Activity, fmt: Formatters) {
    ChartCard(title = group.title) {
        SplitTimeline(splits, activity)
        if (group == SplitGroup.CLIMBS) {
            ClimbSummary(splits, fmt)
        } else {
            val total = splits.sumOf { it.durationSeconds }.coerceAtLeast(1.0)
            splits.groupBy { it.kind }.toSortedMap().forEach { (kind, ofKind) ->
                KindSummary(kind, ofKind, total, activity, fmt)
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
        SplitTable(group, splits, activity, fmt)
    }
}

/** Where each split sits within the whole activity. */
@Composable
private fun SplitTimeline(splits: List<Split>, activity: Activity) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val start = minOf(activity.startTimeMillis, splits.minOf { it.startTimeMillis })
    val end = maxOf(
        activity.endTimeMillis,
        splits.maxOf { it.startTimeMillis + (it.durationSeconds * 1000).toLong() },
    )
    val span = (end - start).coerceAtLeast(1L).toFloat()
    Canvas(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .height(14.dp)
            .clip(RoundedCornerShape(7.dp)),
    ) {
        drawRect(track)
        splits.forEach { s ->
            val left = (s.startTimeMillis - start) / span * size.width
            val width = (s.durationSeconds * 1000 / span * size.width).toFloat().coerceAtLeast(1f)
            drawRect(s.kind.color(), Offset(left, 0f), Size(width, size.height))
        }
    }
}

@Composable
private fun KindSummary(kind: SplitKind, splits: List<Split>, groupSeconds: Double, activity: Activity, fmt: Formatters) {
    val seconds = splits.sumOf { it.durationSeconds }
    val moving = splits.sumOf { it.movingSeconds ?: it.durationSeconds }
    val distance = splits.mapNotNull { it.distanceMeters }.takeIf { it.isNotEmpty() }?.sum()
    val share = (seconds / groupSeconds * 100).roundToInt()
    val details = listOfNotNull(
        "${splits.size}×",
        distance?.let { fmt.distance(it) },
        distance?.takeIf { it > 1 && kind != SplitKind.IDLE }?.let { fmt.speedOrPace(activity.type, it / moving, moving, it) },
        timeWeighted(splits) { it.avgHeartRate }?.let { fmt.heartRate(it) },
        timeWeighted(splits) { it.avgPower }?.takeIf { kind.group == SplitGroup.RIDER_POSITION }?.let { fmt.power(it) },
    )
    SummaryRow(kind.color(), "${kind.label} · $share%", details.joinToString(" · "), fmt.duration(seconds))
}

@Composable
private fun ClimbSummary(climbs: List<Split>, fmt: Formatters) {
    val seconds = climbs.sumOf { it.durationSeconds }
    val distance = climbs.mapNotNull { it.distanceMeters }.takeIf { it.isNotEmpty() }?.sum()
    val ascent = climbs.mapNotNull { it.ascentMeters }.takeIf { it.isNotEmpty() }?.sum()
    val details = listOfNotNull(
        distance?.let { fmt.distance(it) },
        ascent?.let { "+${fmt.elevation(it)}" },
        timeWeighted(climbs) { it.avgHeartRate }?.let { fmt.heartRate(it) },
    )
    val count = if (climbs.size == 1) "1 climb" else "${climbs.size} climbs"
    SummaryRow(SplitKind.CLIMB.color(), count, details.joinToString(" · "), fmt.duration(seconds))
}

@Composable
private fun SummaryRow(color: Color, title: String, details: String, duration: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (details.isNotBlank()) {
                Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(duration, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SplitTable(group: SplitGroup, splits: List<Split>, activity: Activity, fmt: Formatters) {
    val showPower = splits.any { it.avgPower != null }
    val zebra = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    Column {
        SplitRow(tableHeader(group, activity, showPower), bold = true, color = MaterialTheme.colorScheme.onSurfaceVariant, background = Color.Transparent)
        splits.forEachIndexed { i, s ->
            SplitRow(
                tableRow(group, i, s, activity, fmt, showPower),
                bold = false,
                color = MaterialTheme.colorScheme.onSurface,
                background = if (i % 2 == 0) Color.Transparent else zebra,
            )
        }
    }
}

private fun tableHeader(group: SplitGroup, activity: Activity, showPower: Boolean): List<Pair<String, Float>> = when (group) {
    SplitGroup.CLIMBS -> buildList {
        add("#" to 0.4f)
        add("Time" to 1f)
        add("Dist" to 1.1f)
        add("Ascent" to 1f)
        add("Grade" to 0.9f)
        add("HR" to 0.7f)
        if (showPower) add("Pwr" to 0.7f)
    }
    SplitGroup.RUN_WALK -> listOf(
        "#" to 0.4f, "Type" to 0.8f, "Time" to 1f, "Dist" to 1.1f,
        (if (activity.type.usesPace) "Pace" else "Speed") to 1.2f, "HR" to 0.7f, "Cad" to 0.7f,
    )
    SplitGroup.RIDER_POSITION -> listOf("#" to 0.4f, "Type" to 1.2f, "Time" to 1f, "Pwr" to 0.9f, "Cad" to 0.8f, "HR" to 0.8f)
}

private fun tableRow(
    group: SplitGroup,
    i: Int,
    s: Split,
    activity: Activity,
    fmt: Formatters,
    showPower: Boolean,
): List<Pair<String, Float>> = when (group) {
    SplitGroup.CLIMBS -> buildList {
        add("${i + 1}" to 0.4f)
        add(fmt.duration(s.durationSeconds) to 1f)
        add((s.distanceMeters?.let { fmt.distance(it) } ?: "—") to 1.1f)
        add((s.ascentMeters?.let { "+${fmt.elevation(it)}" } ?: "—") to 1f)
        add((s.avgGradePercent?.let { String.format(Locale.US, "%.1f%%", it) } ?: "—") to 0.9f)
        add(fmt.heartRate(s.avgHeartRate).removeSuffix(" bpm") to 0.7f)
        if (showPower) add(fmt.power(s.avgPower).removeSuffix(" W") to 0.7f)
    }
    SplitGroup.RUN_WALK -> listOf(
        "${i + 1}" to 0.4f,
        s.kind.label to 0.8f,
        fmt.duration(s.durationSeconds) to 1f,
        (s.distanceMeters?.let { fmt.distance(it) } ?: "—") to 1.1f,
        fmt.speedOrPace(activity.type, s.avgSpeedMps, s.movingSeconds ?: s.durationSeconds, s.distanceMeters ?: 0.0) to 1.2f,
        fmt.heartRate(s.avgHeartRate).removeSuffix(" bpm") to 0.7f,
        activity.cadenceLabel(s) to 0.7f,
    )
    SplitGroup.RIDER_POSITION -> listOf(
        "${i + 1}" to 0.4f,
        s.kind.label to 1.2f,
        fmt.duration(s.durationSeconds) to 1f,
        fmt.power(s.avgPower).removeSuffix(" W") to 0.9f,
        activity.cadenceLabel(s) to 0.8f,
        fmt.heartRate(s.avgHeartRate).removeSuffix(" bpm") to 0.8f,
    )
}

@Composable
private fun SplitRow(cells: List<Pair<String, Float>>, bold: Boolean, color: Color, background: Color) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .padding(horizontal = 4.dp, vertical = 8.dp),
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

private fun timeWeighted(splits: List<Split>, value: (Split) -> Double?): Double? {
    var weight = 0.0
    var sum = 0.0
    splits.forEach { s ->
        val v = value(s) ?: return@forEach
        sum += v * s.durationSeconds
        weight += s.durationSeconds
    }
    return if (weight > 0) sum / weight else null
}
