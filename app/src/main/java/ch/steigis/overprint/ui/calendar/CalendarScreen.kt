package ch.steigis.overprint.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ch.steigis.overprint.domain.format.Formatters
import ch.steigis.overprint.domain.model.Activity
import ch.steigis.overprint.domain.model.DailyHealth
import ch.steigis.overprint.ui.activities.ActivityRow
import ch.steigis.overprint.ui.theme.toComposeColor
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar
import kotlin.math.roundToInt

@Composable
fun CalendarScreen(
    activities: List<Activity>,
    dailyHealth: List<DailyHealth>,
    year: Int,
    month: Int,
    selectedDay: Int?,
    fmt: Formatters,
    healthLoading: Boolean,
    healthLoadingDate: String?,
    syncRunning: Boolean,
    onMonthChange: (Int, Int) -> Unit,
    onSelectDay: (Int) -> Unit,
    onOpen: (Activity) -> Unit,
    onOpenHealth: (String) -> Unit,
    onEnsureHealth: (String?) -> Unit,
) {
    val byDay = remember(activities, year, month) {
        activities.filter {
            val c = Calendar.getInstance().apply { timeInMillis = it.startTimeMillis }
            c.get(Calendar.YEAR) == year && c.get(Calendar.MONTH) == month
        }.groupBy {
            Calendar.getInstance().apply { timeInMillis = it.startTimeMillis }.get(Calendar.DAY_OF_MONTH)
        }
    }
    val cal = Calendar.getInstance().apply {
        set(Calendar.YEAR, year)
        set(Calendar.MONTH, month)
        set(Calendar.DAY_OF_MONTH, 1)
    }
    val firstDow = ((cal.get(Calendar.DAY_OF_WEEK) + 5) % 7)
    val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    val cells = List(firstDow) { 0 } + (1..daysInMonth).toList()
    val selected = byDay[selectedDay].orEmpty()
    val selectedIso = selectedDay?.let { LocalDate.of(year, month + 1, it).toString() }
    val title = fmt.monthYear(cal.timeInMillis)
    LaunchedEffect(selectedIso, syncRunning) { onEnsureHealth(selectedIso) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                val c = Calendar.getInstance().apply { set(year, month, 1); add(Calendar.MONTH, -1) }
                onMonthChange(c.get(Calendar.YEAR), c.get(Calendar.MONTH))
            }) { Icon(Icons.Outlined.ChevronLeft, contentDescription = "Previous month") }
            Text(title, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            IconButton(onClick = {
                val c = Calendar.getInstance().apply { set(year, month, 1); add(Calendar.MONTH, 1) }
                onMonthChange(c.get(Calendar.YEAR), c.get(Calendar.MONTH))
            }) { Icon(Icons.Outlined.ChevronRight, contentDescription = "Next month") }
        }
        Row(Modifier.fillMaxWidth()) {
            listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun").forEach {
                Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall)
            }
        }
        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { day ->
                    Box(
                        Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .padding(2.dp)
                            .clickable(enabled = day > 0) { onSelectDay(day) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (day > 0) {
                            val acts = byDay[day].orEmpty()
                            val selectedBg = if (day == selectedDay) MaterialTheme.colorScheme.primary.copy(0.25f) else Color.Transparent
                            Column(
                                Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)).background(selectedBg),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Text("$day", fontWeight = if (acts.isNotEmpty()) FontWeight.Bold else FontWeight.Normal)
                                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                    acts.take(3).forEach { a ->
                                        Box(Modifier.size(6.dp).clip(CircleShape).background(a.type.colorArgb.toComposeColor()))
                                    }
                                }
                            }
                        }
                    }
                }
                repeat(7 - week.size) { Box(Modifier.weight(1f)) }
            }
        }
        Text(
            if (selected.isEmpty()) "Tap a day with a coloured dot" else "${selected.size} activities",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (selectedIso != null) {
                item(key = "health-$selectedIso") {
                    HealthDayRow(
                        dateIso = selectedIso,
                        day = dailyHealth.firstOrNull { it.date == selectedIso },
                        loading = healthLoading && healthLoadingDate == selectedIso,
                        fmt = fmt,
                        onClick = { onOpenHealth(selectedIso) },
                    )
                }
            }
            items(selected, key = { it.id }) { ActivityRow(it, fmt, onClick = { onOpen(it) }) }
        }
    }
}

private val HealthColor = Color(0xFFD94B7A)
private val HealthLight = Color(0xFFF8D6E0)

@Composable
private fun HealthDayRow(
    dateIso: String,
    day: DailyHealth?,
    loading: Boolean,
    fmt: Formatters,
    onClick: () -> Unit,
) {
    val dateMillis = remember(dateIso) {
        runCatching {
            LocalDate.parse(dateIso).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrNull()
    }
    val metrics = listOfNotNull(
        day?.steps?.roundToInt()?.let { "$it steps" },
        day?.sleepSeconds?.let { fmt.duration(it) },
        (day?.caloriesTotal ?: day?.caloriesActive)?.let { fmt.calories(it) }
            ?: day?.restingHr?.let { "RHR ${it.roundToInt()}" },
    )
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(HealthLight.copy(alpha = 0.35f))
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(HealthColor),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Favorite,
                contentDescription = "Health",
                tint = Color.White,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Health", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
            Text(
                dateMillis?.let { fmt.weekdayDate(it) } ?: dateIso,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                when {
                    metrics.isNotEmpty() -> metrics.joinToString("   ")
                    loading -> "Loading daily health…"
                    else -> "No health data for this day"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (metrics.isEmpty()) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    Color.Unspecified
                },
            )
        }
    }
}
