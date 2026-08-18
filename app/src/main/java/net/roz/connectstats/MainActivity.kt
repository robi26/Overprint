package net.roz.connectstats

import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.SideEffect
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import net.roz.connectstats.ui.activities.ActivitiesScreen
import net.roz.connectstats.ui.calendar.CalendarScreen
import net.roz.connectstats.ui.detail.ActivityDetailScreen
import net.roz.connectstats.ui.heatmap.HeatmapScreen
import net.roz.connectstats.ui.more.MoreScreen
import net.roz.connectstats.ui.settings.SettingsScreen
import net.roz.connectstats.ui.stats.StatsScreen
import net.roz.connectstats.ui.theme.OverprintTheme
import net.roz.connectstats.ui.theme.DarkWindowArgb
import net.roz.connectstats.ui.theme.LightWindowArgb
import net.roz.connectstats.ui.theme.resolvedDarkTheme

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels { AppViewModel.factory() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(DarkWindowArgb))
        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            val dark = state.settings.resolvedDarkTheme(isSystemInDarkTheme())
            SideEffect {
                window.setBackgroundDrawable(ColorDrawable(if (dark) DarkWindowArgb else LightWindowArgb))
            }
            OverprintTheme(darkTheme = dark) {
                OverprintNav(viewModel) { bytes, name -> viewModel.importFile(bytes, name) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OverprintNav(
    viewModel: AppViewModel,
    onImportBytes: (ByteArray, String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route ?: "activities"
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "activity.fit"
        context.contentResolver.openInputStream(uri)?.use { stream ->
            onImportBytes(stream.readBytes(), name)
        }
    }

    val titles = mapOf(
        "activities" to "Activities",
        "calendar" to "Calendar",
        "stats" to "Statistics",
        "more" to "More",
        "heatmap" to "Heatmap",
        "settings" to "Settings",
        "detail" to (state.selected?.activity?.name ?: "Activity"),
    )
    val moreRoutes = setOf("more", "heatmap", "settings")
    val showBack = route == "detail" || route == "heatmap" || route == "settings"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(titles[route] ?: "Overprint") },
                navigationIcon = {
                    if (showBack) {
                        IconButton(onClick = {
                            if (route == "detail") viewModel.closeDetail()
                            nav.popBackStack()
                        }) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (route != "detail") {
                NavigationBar {
                    NavigationBarItem(
                        selected = route == "activities",
                        onClick = { nav.navigate("activities") { launchSingleTop = true } },
                        icon = { Icon(Icons.AutoMirrored.Outlined.List, contentDescription = null) },
                        label = { Text("Activities") },
                    )
                    NavigationBarItem(
                        selected = route == "calendar",
                        onClick = { nav.navigate("calendar") { launchSingleTop = true } },
                        icon = { Icon(Icons.Outlined.CalendarMonth, contentDescription = null) },
                        label = { Text("Calendar") },
                    )
                    NavigationBarItem(
                        selected = route == "stats",
                        onClick = { nav.navigate("stats") { launchSingleTop = true } },
                        icon = { Icon(Icons.Outlined.Insights, contentDescription = null) },
                        label = { Text("Stats") },
                    )
                    NavigationBarItem(
                        selected = route in moreRoutes,
                        onClick = { nav.navigate("more") { launchSingleTop = true } },
                        icon = { Icon(Icons.Outlined.MoreHoriz, contentDescription = null) },
                        label = { Text("More") },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = "activities", modifier = Modifier.padding(padding)) {
            composable("activities") {
                ActivitiesScreen(
                    activities = state.filtered,
                    query = state.query,
                    typeFilter = state.typeFilter,
                    garminSync = state.garminSync,
                    fmt = state.fmt,
                    onQuery = viewModel::setQuery,
                    onType = viewModel::setType,
                    onRefresh = viewModel::refresh,
                    onOpen = {
                        viewModel.open(it)
                        nav.navigate("detail")
                    },
                )
            }
            composable("calendar") {
                CalendarScreen(
                    activities = state.activities,
                    year = state.calYear,
                    month = state.calMonth,
                    selectedDay = state.calDay,
                    fmt = state.fmt,
                    onMonthChange = viewModel::setMonth,
                    onSelectDay = viewModel::setDay,
                    onOpen = {
                        viewModel.open(it)
                        nav.navigate("detail")
                    },
                )
            }
            composable("stats") {
                StatsScreen(state.activities, state.fmt)
            }
            composable("more") {
                MoreScreen(
                    onHeatmap = { nav.navigate("heatmap") },
                    onSettings = { nav.navigate("settings") },
                )
            }
            composable("heatmap") {
                HeatmapScreen(
                    activities = state.activities,
                    tracks = state.gpsTracks,
                    loading = state.gpsTracksLoading,
                    fmt = state.fmt,
                    onLoadTracks = viewModel::loadGpsTracks,
                    onOpen = {
                        viewModel.open(it)
                        nav.navigate("detail")
                    },
                )
            }
            composable("settings") {
                SettingsScreen(
                    settings = state.settings,
                    status = state.status,
                    garminSync = state.garminSync,
                    onMetric = viewModel::setMetric,
                    onThemeMode = viewModel::setThemeMode,
                    onGarminUsername = viewModel::setGarminUsername,
                    onGarminPassword = viewModel::setGarminPassword,
                    onImport = { picker.launch(arrayOf("*/*")) },
                    onDemo = viewModel::loadDemo,
                    onSyncGarmin = viewModel::syncGarmin,
                    onClearGarmin = viewModel::clearGarminCredentials,
                    onMaxHr = viewModel::setMaxHr,
                    onFtp = viewModel::setFtp,
                )
            }
            composable("detail") {
                ActivityDetailScreen(state.selected, state.fmt, state.settings.maxHeartRate, state.settings.ftpWatts)
            }
        }
    }
}
