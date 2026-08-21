package ch.steigis.overprint.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ch.steigis.overprint.domain.format.Formatters
import ch.steigis.overprint.domain.model.ZoneBucket
import ch.steigis.overprint.ui.components.ChartCard

private val HrZoneColors = listOf(
    Color(0xFF8B9BB4),
    Color(0xFF3583F3),
    Color(0xFF5CE6B8),
    Color(0xFFF5A524),
    Color(0xFFE24B4B),
)

private val PowerZoneColors = listOf(
    Color(0xFF8B9BB4),
    Color(0xFF3583F3),
    Color(0xFF5CE6B8),
    Color(0xFFF5A524),
    Color(0xFFE67E22),
    Color(0xFFE24B4B),
)

@Composable
fun ZoneTimeCards(
    hrZones: List<ZoneBucket>,
    powerZones: List<ZoneBucket>,
    fmt: Formatters,
) {
    if (hrZones.any { it.seconds > 0 }) {
        ChartCard(title = "Heart rate zones") {
            ZoneBars(hrZones, fmt, HrZoneColors)
        }
    }
    if (powerZones.any { it.seconds > 0 }) {
        ChartCard(title = "Power zones") {
            ZoneBars(powerZones, fmt, PowerZoneColors)
        }
    }
}

@Composable
private fun ZoneBars(
    zones: List<ZoneBucket>,
    fmt: Formatters,
    colors: List<Color>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        zones.forEach { zone ->
            val color = colors.getOrElse(zone.zone - 1) { colors.last() }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(zone.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        fmt.duration(zone.seconds),
                        fontWeight = FontWeight.Medium,
                        style = MaterialTheme.typography.bodyMedium,
                        color = color,
                    )
                }
                LinearProgressIndicator(
                    progress = { zone.fraction },
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                    color = color,
                    trackColor = color.copy(alpha = 0.18f),
                )
            }
        }
    }
}
