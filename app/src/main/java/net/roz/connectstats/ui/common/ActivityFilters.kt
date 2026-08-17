package net.roz.connectstats.ui.common

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.roz.connectstats.domain.model.Activity
import net.roz.connectstats.domain.model.ActivityType
import java.util.Calendar

fun activityYear(millis: Long): Int {
    val cal = Calendar.getInstance()
    cal.timeInMillis = millis
    return cal.get(Calendar.YEAR)
}

fun filterBySportAndYear(
    activities: List<Activity>,
    type: ActivityType?,
    year: Int?,
): List<Activity> {
    val byType = if (type == null) activities else activities.filter { it.type == type }
    return if (year == null) byType else byType.filter { activityYear(it.startTimeMillis) == year }
}

@Composable
fun SportAndYearFilters(
    activities: List<Activity>,
    type: ActivityType?,
    year: Int?,
    onType: (ActivityType?) -> Unit,
    onYear: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val years = remember(activities) {
        activities.map { activityYear(it.startTimeMillis) }.toSet().sortedDescending()
    }
    LaunchedEffect(years, year) {
        if (year != null && year !in years) onYear(null)
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = type == null, onClick = { onType(null) }, label = { Text("All") })
            ActivityType.entries.forEach { t ->
                FilterChip(selected = type == t, onClick = { onType(t) }, label = { Text(t.displayName) })
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = year == null, onClick = { onYear(null) }, label = { Text("All years") })
            years.forEach { y ->
                FilterChip(selected = year == y, onClick = { onYear(y) }, label = { Text(y.toString()) })
            }
        }
    }
}
