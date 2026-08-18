package net.roz.connectstats.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import net.roz.connectstats.data.prefs.AppSettings
import net.roz.connectstats.data.remote.garmin.GarminSyncProgress

@Composable
fun SettingsScreen(
    settings: AppSettings,
    status: String?,
    garminSync: GarminSyncProgress,
    onMetric: (Boolean) -> Unit,
    onThemeMode: (String) -> Unit,
    onGarminUsername: (String) -> Unit,
    onGarminPassword: (String) -> Unit,
    onImport: () -> Unit,
    onDemo: () -> Unit,
    onSyncGarmin: () -> Unit,
    onMaxHr: (String) -> Unit,
    onFtp: (String) -> Unit,
) {
    val canSync = settings.garminUsername.isNotBlank() &&
        settings.garminPassword.isNotBlank() &&
        !garminSync.running
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Metric units", fontWeight = FontWeight.SemiBold)
                Text("km, pace /km, metres")
            }
            Switch(checked = settings.metric, onCheckedChange = onMetric)
        }

        Text("Appearance", style = MaterialTheme.typography.titleMedium)
        Text("Charts and screens follow this choice. System matches your phone setting.")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = settings.themeMode == AppSettings.THEME_SYSTEM,
                onClick = { onThemeMode(AppSettings.THEME_SYSTEM) },
                label = { Text("System") },
            )
            FilterChip(
                selected = settings.themeMode == AppSettings.THEME_LIGHT,
                onClick = { onThemeMode(AppSettings.THEME_LIGHT) },
                label = { Text("Light") },
            )
            FilterChip(
                selected = settings.themeMode == AppSettings.THEME_DARK,
                onClick = { onThemeMode(AppSettings.THEME_DARK) },
                label = { Text("Dark") },
            )
        }

        Text("Garmin Connect", style = MaterialTheme.typography.titleMedium)
        Text("Sign in with your Garmin email and password. Overprint downloads your activities from Garmin Connect.")
        OutlinedTextField(
            value = settings.garminUsername,
            onValueChange = onGarminUsername,
            label = { Text("Garmin email") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        )
        OutlinedTextField(
            value = settings.garminPassword,
            onValueChange = onGarminPassword,
            label = { Text("Garmin password") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        Button(
            onClick = onSyncGarmin,
            enabled = canSync,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (garminSync.running) "Syncing from Garmin…" else "Refresh from Garmin")
        }
        GarminSyncStatus(garminSync)
        Button(onClick = onImport, modifier = Modifier.fillMaxWidth()) { Text("Import FIT / GPX / TCX") }
        OutlinedButton(onClick = onDemo, modifier = Modifier.fillMaxWidth()) { Text("Load demo activities") }

        Text("Training zones", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            settings.maxHeartRate.toInt().toString(),
            onMaxHr,
            label = { Text("Max heart rate") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            settings.ftpWatts.toInt().toString(),
            onFtp,
            label = { Text("FTP (watts)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        if (!status.isNullOrBlank() && status != garminSync.message && status != garminSync.error) {
            Text(status, color = MaterialTheme.colorScheme.primary)
        }

        Text("About", style = MaterialTheme.typography.titleMedium)
        Text("Overprint shows activities you import or download from Garmin Connect. It does not record workouts. Not affiliated with Garmin.")
    }
}

@Composable
fun GarminSyncStatus(progress: GarminSyncProgress, modifier: Modifier = Modifier) {
    val hasContent = progress.running || progress.message.isNotBlank() ||
        !progress.error.isNullOrBlank() || progress.warnings.isNotEmpty()
    if (!hasContent) return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (progress.running) {
            val fraction = progress.fraction
            if (fraction != null) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "${progress.current} / ${progress.total}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
        if (progress.error != null) {
            Text(progress.error, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
        } else if (progress.message.isNotBlank()) {
            Text(
                progress.message,
                color = if (progress.running) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
            )
        }
        if (progress.warnings.isNotEmpty()) {
            Text(
                "${progress.warnings.size} FIT file(s) could not be downloaded:",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
            )
            progress.warnings.take(8).forEach { warning ->
                Text("• $warning", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (progress.warnings.size > 8) {
                Text(
                    "…and ${progress.warnings.size - 8} more",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
