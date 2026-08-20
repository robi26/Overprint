package net.roz.connectstats.ui.activities

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.roz.connectstats.domain.format.Formatters
import net.roz.connectstats.domain.model.Activity
import net.roz.connectstats.domain.model.ActivityType
import net.roz.connectstats.ui.common.SportAndYearFilters
import net.roz.connectstats.ui.common.icon
import net.roz.connectstats.ui.settings.GarminSyncStatus
import net.roz.connectstats.ui.theme.toComposeColor
import net.roz.connectstats.data.remote.garmin.GarminSyncProgress

@Composable
fun ActivitiesScreen(
    allActivities: List<Activity>,
    activities: List<Activity>,
    query: String,
    typeFilter: ActivityType?,
    yearFilter: Int?,
    garminSync: GarminSyncProgress,
    fmt: Formatters,
    onQuery: (String) -> Unit,
    onType: (ActivityType?) -> Unit,
    onYear: (Int?) -> Unit,
    onOpen: (Activity) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text("Search activities") },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
        )
        SportAndYearFilters(
            activities = allActivities,
            type = typeFilter,
            year = yearFilter,
            onType = onType,
            onYear = onYear,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        GarminSyncStatus(garminSync, Modifier.padding(horizontal = 16.dp))
        if (activities.isEmpty()) {
            Box(Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
                Text("No activities yet.\nOpen Settings to import FIT files or connect Garmin.")
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(activities, key = { it.id }) { act ->
                    ActivityRow(act, fmt, onClick = { onOpen(act) })
                }
            }
        }
    }
}

@Composable
fun ActivityRow(activity: Activity, fmt: Formatters, onClick: () -> Unit) {
    val bg = activity.type.lightArgb.toComposeColor().copy(alpha = 0.35f)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(activity.type.colorArgb.toComposeColor()),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                activity.type.icon(),
                contentDescription = activity.type.displayName,
                tint = Color.White,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(activity.name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
            Text(
                listOfNotNull(fmt.weekdayDate(activity.startTimeMillis), activity.location).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                listOf(
                    fmt.distance(activity.distanceMeters),
                    fmt.duration(activity.durationSeconds),
                    fmt.speedOrPace(activity.type, activity.avgSpeedMps, activity.durationSeconds, activity.distanceMeters),
                ).joinToString("   "),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
