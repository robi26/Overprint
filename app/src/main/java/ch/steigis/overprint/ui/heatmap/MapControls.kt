package ch.steigis.overprint.ui.heatmap

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun MapLayerMenu(
    baseStyle: BaseMapStyle,
    onBaseStyle: (BaseMapStyle) -> Unit,
    modifier: Modifier = Modifier,
    showHeat: Boolean? = null,
    onHeat: ((Boolean) -> Unit)? = null,
    showTracks: Boolean? = null,
    onTracks: ((Boolean) -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    val overlaysEnabled = showHeat != null && onHeat != null && showTracks != null && onTracks != null
    Box(modifier) {
        FilledTonalIconButton(onClick = { expanded = true }) {
            Icon(Icons.Outlined.Layers, contentDescription = "Map layers")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Text(
                "Basemap",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
            DropdownMenuItem(
                text = { Text("Streets") },
                onClick = {
                    onBaseStyle(BaseMapStyle.STREETS)
                    expanded = false
                },
                leadingIcon = {
                    RadioButton(selected = baseStyle == BaseMapStyle.STREETS, onClick = null)
                },
            )
            DropdownMenuItem(
                text = { Text("Dark streets") },
                onClick = {
                    onBaseStyle(BaseMapStyle.DARK)
                    expanded = false
                },
                leadingIcon = {
                    RadioButton(selected = baseStyle == BaseMapStyle.DARK, onClick = null)
                },
            )
            DropdownMenuItem(
                text = { Text("Topographic") },
                onClick = {
                    onBaseStyle(BaseMapStyle.TOPO)
                    expanded = false
                },
                leadingIcon = {
                    RadioButton(selected = baseStyle == BaseMapStyle.TOPO, onClick = null)
                },
            )
            DropdownMenuItem(
                text = { Text("No map") },
                onClick = {
                    onBaseStyle(BaseMapStyle.NONE)
                    expanded = false
                },
                leadingIcon = {
                    RadioButton(selected = baseStyle == BaseMapStyle.NONE, onClick = null)
                },
            )
            if (overlaysEnabled && showHeat != null && onHeat != null && showTracks != null && onTracks != null) {
                HorizontalDivider()
                Text(
                    "Overlays",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
                DropdownMenuItem(
                    text = { Text("Heat") },
                    onClick = { onHeat(!showHeat) },
                    trailingIcon = { Checkbox(checked = showHeat, onCheckedChange = null) },
                )
                DropdownMenuItem(
                    text = { Text("Tracks") },
                    onClick = { onTracks(!showTracks) },
                    trailingIcon = { Checkbox(checked = showTracks, onCheckedChange = null) },
                )
            }
        }
    }
}

@Composable
internal fun MapZoomButtons(
    zoom: Double,
    onZoom: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalIconButton(onClick = {
            onZoom((zoom + 1).coerceIn(MIN_MAP_ZOOM, MAX_MAP_ZOOM))
        }) {
            Icon(Icons.Outlined.Add, contentDescription = "Zoom in")
        }
        FilledTonalIconButton(onClick = {
            onZoom((zoom - 1).coerceIn(MIN_MAP_ZOOM, MAX_MAP_ZOOM))
        }) {
            Icon(Icons.Outlined.Remove, contentDescription = "Zoom out")
        }
    }
}
