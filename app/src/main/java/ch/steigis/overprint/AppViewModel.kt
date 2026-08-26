package ch.steigis.overprint

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ch.steigis.overprint.data.prefs.AppSettings
import ch.steigis.overprint.data.prefs.SettingsStore
import ch.steigis.overprint.data.remote.garmin.GarminSyncProgress
import ch.steigis.overprint.data.repo.ActivityRepository
import ch.steigis.overprint.domain.format.Formatters
import ch.steigis.overprint.domain.model.Activity
import ch.steigis.overprint.domain.model.ActivityDetail
import ch.steigis.overprint.domain.model.ActivityType
import ch.steigis.overprint.domain.model.DailyHealth
import ch.steigis.overprint.domain.model.GpsTrack
import java.util.Calendar

data class UiState(
    val activities: List<Activity> = emptyList(),
    val filtered: List<Activity> = emptyList(),
    val query: String = "",
    val typeFilter: ActivityType? = null,
    val yearFilter: Int? = null,
    val settings: AppSettings = AppSettings(),
    val fmt: Formatters = Formatters(true),
    val refreshing: Boolean = false,
    val garminSync: GarminSyncProgress = GarminSyncProgress(),
    val status: String? = null,
    val selected: ActivityDetail? = null,
    val calYear: Int = Calendar.getInstance().get(Calendar.YEAR),
    val calMonth: Int = Calendar.getInstance().get(Calendar.MONTH),
    val calDay: Int? = Calendar.getInstance().get(Calendar.DAY_OF_MONTH),
    val gpsTracks: List<GpsTrack> = emptyList(),
    val gpsTracksLoading: Boolean = false,
    val deletedActivities: List<Activity> = emptyList(),
    val dailyHealth: List<DailyHealth> = emptyList(),
)

class AppViewModel(
    private val repo: ActivityRepository,
    private val settingsStore: SettingsStore,
    private val syncWakeLock: SyncWakeLock = SyncWakeLock(OverprintApp.instance),
) : ViewModel() {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state
    private var gpsLoadJob: Job? = null

    init {
        viewModelScope.launch {
            runCatching { repo.removeDemoActivities() }
        }
        viewModelScope.launch {
            repo.activities.collect { acts ->
                _state.update { it.copy(activities = acts).withFilter() }
            }
        }
        viewModelScope.launch {
            repo.deletedActivities.collect { acts ->
                _state.update { it.copy(deletedActivities = acts) }
            }
        }
        viewModelScope.launch {
            settingsStore.settings.collect { s ->
                _state.update { it.copy(settings = s, fmt = Formatters(s.metric)) }
            }
        }
        viewModelScope.launch {
            repo.dailyHealth.collect { days ->
                _state.update { it.copy(dailyHealth = days) }
            }
        }
    }

    fun setQuery(value: String) {
        _state.update { it.copy(query = value).withFilter() }
    }

    fun setType(type: ActivityType?) {
        _state.update { it.copy(typeFilter = type).withFilter() }
    }

    fun setYear(year: Int?) {
        _state.update { it.copy(yearFilter = year).withFilter() }
    }

    fun setMonth(year: Int, month: Int) {
        _state.update { it.copy(calYear = year, calMonth = month) }
    }

    fun setDay(day: Int) {
        _state.update { it.copy(calDay = day) }
    }

    fun open(activity: Activity) {
        viewModelScope.launch {
            _state.update { it.copy(selected = repo.get(activity.id)) }
        }
    }

    fun closeDetail() {
        _state.update { it.copy(selected = null) }
    }

    fun markDeleted(id: String) {
        viewModelScope.launch {
            repo.markDeleted(id)
            _state.update { it.copy(selected = null, status = "Activity removed") }
        }
    }

    fun restoreDeleted(id: String) {
        viewModelScope.launch { repo.restore(id) }
    }

    fun loadGpsTracks(activityIds: List<String>) {
        gpsLoadJob?.cancel()
        gpsLoadJob = viewModelScope.launch {
            if (activityIds.isEmpty()) {
                _state.update { it.copy(gpsTracks = emptyList(), gpsTracksLoading = false) }
                return@launch
            }
            _state.update { it.copy(gpsTracksLoading = true) }
            try {
                val tracks = withContext(Dispatchers.IO) { repo.gpsTracks(activityIds) }
                _state.update { it.copy(gpsTracks = tracks, gpsTracksLoading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(gpsTracks = emptyList(), gpsTracksLoading = false) }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch { runGarminSync() }
    }

    fun syncGarmin() {
        viewModelScope.launch { runGarminSync() }
    }

    fun loadHealthHistory() {
        viewModelScope.launch { runHealthHistorySync() }
    }

    private suspend fun runGarminSync() {
        if (_state.value.garminSync.running) return
        val settings = _state.value.settings
        if (!settings.hasGarminCredentials) {
            _state.update {
                it.copy(
                    garminSync = GarminSyncProgress(
                        error = "Enter your Garmin email and password in Settings.",
                    ),
                    status = "Enter your Garmin email and password in Settings.",
                )
            }
            return
        }
        _state.update {
            it.copy(
                refreshing = true,
                status = null,
                garminSync = GarminSyncProgress(running = true, message = "Starting Garmin sync…"),
            )
        }
        syncWakeLock.acquire()
        try {
            runCatching {
                repo.syncGarmin { update ->
                    _state.update { it.copy(garminSync = update, status = update.message) }
                }
            }.onFailure { err ->
                val message = err.message ?: "Garmin sync failed"
                _state.update {
                    it.copy(
                        garminSync = it.garminSync.copy(running = false, error = message),
                        status = message,
                    )
                }
            }
        } finally {
            syncWakeLock.release()
            _state.update { it.copy(refreshing = false, garminSync = it.garminSync.copy(running = false)) }
        }
    }

    private suspend fun runHealthHistorySync() {
        if (_state.value.garminSync.running) return
        val settings = _state.value.settings
        if (!settings.hasGarminCredentials) {
            _state.update {
                it.copy(
                    garminSync = GarminSyncProgress(
                        error = "Enter your Garmin email and password in Settings.",
                    ),
                    status = "Enter your Garmin email and password in Settings.",
                )
            }
            return
        }
        _state.update {
            it.copy(
                status = null,
                garminSync = GarminSyncProgress(running = true, message = "Loading older health…"),
            )
        }
        syncWakeLock.acquire()
        try {
            runCatching {
                repo.syncHealthHistory { update ->
                    _state.update { it.copy(garminSync = update, status = update.message) }
                }
            }.onFailure { err ->
                val message = err.message ?: "Health history failed"
                _state.update {
                    it.copy(
                        garminSync = it.garminSync.copy(running = false, error = message),
                        status = message,
                    )
                }
            }
        } finally {
            syncWakeLock.release()
            _state.update { it.copy(garminSync = it.garminSync.copy(running = false)) }
        }
    }

    fun importFile(bytes: ByteArray, name: String) {
        viewModelScope.launch {
            runCatching {
                val act = repo.importFile(bytes, name)
                _state.update { it.copy(status = "Imported ${act.name}") }
            }.onFailure { err -> _state.update { it.copy(status = "Import failed: ${err.message}") } }
        }
    }

    fun setMetric(metric: Boolean) {
        viewModelScope.launch { settingsStore.update { it.copy(metric = metric) } }
    }

    fun setThemeMode(mode: String) {
        viewModelScope.launch { settingsStore.update { it.copy(themeMode = mode) } }
    }

    fun setGarminUsername(value: String) {
        val trimmed = value.trim()
        viewModelScope.launch {
            settingsStore.update { current ->
                if (current.garminUsername == trimmed) current
                else current.copy(
                    garminUsername = trimmed,
                    garminEnabled = trimmed.isNotBlank() && current.garminPassword.isNotBlank(),
                    garminToken = "",
                )
            }
        }
    }

    fun setGarminPassword(value: String) {
        viewModelScope.launch {
            settingsStore.update { current ->
                if (current.garminPassword == value) current
                else current.copy(
                    garminPassword = value,
                    garminEnabled = current.garminUsername.isNotBlank() && value.isNotBlank(),
                    garminToken = "",
                )
            }
        }
    }

    fun saveGarminAndSync(username: String, password: String) {
        viewModelScope.launch {
            val trimmed = username.trim()
            settingsStore.update { current ->
                current.copy(
                    garminUsername = trimmed,
                    garminPassword = password,
                    garminEnabled = trimmed.isNotBlank() && password.isNotBlank(),
                    garminToken = if (trimmed != current.garminUsername || password != current.garminPassword) {
                        ""
                    } else current.garminToken,
                )
            }
            runGarminSync()
        }
    }

    fun clearGarminCredentials() {
        viewModelScope.launch {
            settingsStore.clearGarminCredentials()
            _state.update {
                it.copy(garminSync = GarminSyncProgress(), status = "Garmin credentials cleared")
            }
        }
    }

    fun setMaxHr(raw: String) {
        val v = raw.toDoubleOrNull() ?: return
        viewModelScope.launch { settingsStore.update { it.copy(maxHeartRate = v) } }
    }

    fun setFtp(raw: String) {
        val v = raw.toDoubleOrNull() ?: return
        viewModelScope.launch { settingsStore.update { it.copy(ftpWatts = v) } }
    }

    private fun UiState.withFilter(): UiState {
        val filtered = activities.filter { a ->
            (typeFilter == null || a.type == typeFilter) &&
                (yearFilter == null || activityYear(a.startTimeMillis) == yearFilter) &&
                (query.isBlank() ||
                    a.name.contains(query, true) ||
                    a.location.orEmpty().contains(query, true) ||
                    a.type.displayName.contains(query, true))
        }
        return copy(filtered = filtered)
    }

    override fun onCleared() {
        syncWakeLock.release()
        super.onCleared()
    }

    companion object {
        fun factory(): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val app = OverprintApp.instance
                return AppViewModel(app.repository, app.settings) as T
            }
        }

        private fun activityYear(millis: Long): Int {
            val cal = Calendar.getInstance()
            cal.timeInMillis = millis
            return cal.get(Calendar.YEAR)
        }
    }
}
