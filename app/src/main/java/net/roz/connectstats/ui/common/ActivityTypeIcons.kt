package net.roz.connectstats.ui.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DirectionsBike
import androidx.compose.material.icons.outlined.DirectionsRun
import androidx.compose.material.icons.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.DownhillSkiing
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Hiking
import androidx.compose.material.icons.outlined.Pool
import androidx.compose.material.icons.outlined.Sports
import androidx.compose.material.icons.outlined.SportsScore
import androidx.compose.ui.graphics.vector.ImageVector
import net.roz.connectstats.domain.model.ActivityType

fun ActivityType.icon(): ImageVector = when (this) {
    ActivityType.RUNNING -> Icons.Outlined.DirectionsRun
    ActivityType.CYCLING -> Icons.Outlined.DirectionsBike
    ActivityType.SWIMMING -> Icons.Outlined.Pool
    ActivityType.HIKING -> Icons.Outlined.Hiking
    ActivityType.WALKING -> Icons.Outlined.DirectionsWalk
    ActivityType.STRENGTH -> Icons.Outlined.FitnessCenter
    ActivityType.SKIING -> Icons.Outlined.DownhillSkiing
    ActivityType.MULTISPORT -> Icons.Outlined.Sports
    ActivityType.OTHER -> Icons.Outlined.SportsScore
}
