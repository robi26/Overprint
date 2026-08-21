package ch.steigis.overprint.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import ch.steigis.overprint.data.prefs.AppSettings
import ch.steigis.overprint.data.remote.garmin.GarminSyncProgress
import ch.steigis.overprint.domain.model.Activity

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
    onSyncGarmin: (String, String) -> Unit,
    onClearGarmin: () -> Unit,
    onMaxHr: (String) -> Unit,
    onFtp: (String) -> Unit,
    deletedActivities: List<Activity> = emptyList(),
    onRestore: (String) -> Unit = {},
) {
    var username by remember { mutableStateOf(settings.garminUsername) }
    var password by remember { mutableStateOf(settings.garminPassword) }
    var emailFocused by remember { mutableStateOf(false) }
    var passwordFocused by remember { mutableStateOf(false) }
    val passwordFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val latestUser = rememberUpdatedState(username)
    val latestPass = rememberUpdatedState(password)
    LaunchedEffect(settings.garminUsername) {
        if (!emailFocused) username = settings.garminUsername
    }
    LaunchedEffect(settings.garminPassword) {
        if (!passwordFocused) password = settings.garminPassword
    }
    DisposableEffect(Unit) {
        onDispose {
            onGarminUsername(latestUser.value)
            onGarminPassword(latestPass.value)
        }
    }
    val canSync = username.isNotBlank() && password.isNotBlank() && !garminSync.running
    val hasStoredGarmin = settings.garminUsername.isNotBlank() ||
        settings.garminPassword.isNotBlank() ||
        settings.garminToken.isNotBlank() ||
        username.isNotBlank() ||
        password.isNotBlank()
    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
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
            value = username,
            onValueChange = { username = it },
            label = { Text("Garmin email") },
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentType = ContentType.EmailAddress + ContentType.Username }
                .onFocusChanged { focus ->
                    emailFocused = focus.isFocused
                    if (!focus.isFocused) onGarminUsername(username)
                },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next,
            ),
            keyboardActions = KeyboardActions(onNext = { passwordFocus.requestFocus() }),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Garmin password") },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(passwordFocus)
                .semantics { contentType = ContentType.Password }
                .onFocusChanged { focus ->
                    passwordFocused = focus.isFocused
                    if (!focus.isFocused) onGarminPassword(password)
                },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    onGarminPassword(password)
                    focusManager.clearFocus()
                },
            ),
        )
        Button(
            onClick = { onSyncGarmin(username, password) },
            enabled = canSync,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (garminSync.running) "Syncing from Garmin…" else "Refresh from Garmin")
        }
        GarminSyncStatus(garminSync)
        if (hasStoredGarmin) {
            OutlinedButton(
                onClick = {
                    username = ""
                    password = ""
                    onClearGarmin()
                },
                enabled = !garminSync.running,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Forget Garmin credentials")
            }
        }
        Button(onClick = onImport, modifier = Modifier.fillMaxWidth()) { Text("Import FIT / GPX / TCX") }

        if (deletedActivities.isNotEmpty()) {
            Text("Deleted activities", style = MaterialTheme.typography.titleMedium)
            Text("Hidden from lists, stats, and Garmin refresh. Restore to show them again.")
            deletedActivities.forEach { act ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(act.name, fontWeight = FontWeight.SemiBold)
                        Text(
                            act.type.displayName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { onRestore(act.id) }) { Text("Restore") }
                }
            }
        }

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
