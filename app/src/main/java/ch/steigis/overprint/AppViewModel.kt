package ch.steigis.overprint

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ch.steigis.overprint.data.prefs.AppSettings
import ch.steigis.overprint.data.prefs.SettingsStore
import ch.steigis.overprint.data.remote.garmin.GarminSyncProgress
import ch.steigis.overprint.data.remote.garmin.HEALTH_RELOAD_POLL_DELAYS_MILLIS
import ch.steigis.overprint.data.repo.ActivityRepository
import ch.steigis.overprint.domain.format.Formatters
import ch.steigis.overprint.domain.model.Activity
import ch.steigis.overprint.domain.model.ActivityDetail
import ch.steigis.overprint.domain.model.ActivityType
import ch.steigis.overprint.domain.model.DailyHealth
import ch.steigis.overprint.domain.model.HealthChartReload
import ch.steigis.overprint.domain.model.HealthReloadState
import ch.steigis.overprint.domain.model.HealthSample
import ch.steigis.overprint.domain.model.HealthSeries
import ch.steigis.overprint.domain.model.GpsTrack
import java.time.LocalDate
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
    val healthSamples: List<HealthSample> = emptyList(),
    val healthSamplesDate: String? = null,
    val healthSeriesLoading: Boolean = false,
    val healthSummaryLoading: Boolean = false,
    val healthSummaryDate: String? = null,
    val healthDate: String? = null,
    val healthReloads: Map<String, HealthChartReload> = emptyMap(),
    val healthReloadPending: String? = null,
    val healthReloadStatus: String? = null,
)

class AppViewModel(
    private val repo: ActivityRepository,
    private val settingsStore: SettingsStore,
    private val syncWakeLock: SyncWakeLock = SyncWakeLock(OverprintApp.instance),
) : ViewModel() {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state
    private var gpsLoadJob: Job? = null
    private var healthSampleJob: Job? = null
    private var healthSummaryJob: Job? = null
    private var healthReloadJob: Job? = null
    private val seriesFetchedDates = mutableSetOf<String>()
    private val summaryFetchedDates = mutableSetOf<String>()
    private val healthSummaryMutex = Mutex()

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
        viewModelScope.launch {
            repo.healthReloads.collect { reloads ->
                _state.update { it.copy(healthReloads = reloads.associateBy { row -> row.date }) }
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

    fun setHealthDate(date: String?) {
        _state.update {
            if (it.healthDate == date) it else it.copy(healthDate = date, healthReloadStatus = null)
        }
    }

    fun ensureDailyHealth(date: String?) {
        if (date.isNullOrBlank()) {
            _state.update { it.copy(healthSummaryLoading = false, healthSummaryDate = null) }
            return
        }
        if (_state.value.dailyHealth.any { it.date == date }) {
            _state.update { it.copy(healthSummaryLoading = false, healthSummaryDate = date) }
            return
        }
        if (healthSummaryJob?.isActive == true && _state.value.healthSummaryDate == date) return
        healthSummaryJob?.cancel()
        healthSummaryJob = viewModelScope.launch { fetchDailyHealthIfMissing(date) }
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

    fun loadHealthSamples(date: String?) {
        healthSampleJob?.cancel()
        if (date.isNullOrBlank()) {
            _state.update {
                it.copy(healthSamples = emptyList(), healthSamplesDate = null, healthSeriesLoading = false)
            }
            return
        }
        healthSampleJob = viewModelScope.launch {
            fetchDailyHealthIfMissing(date)
            val samples = withContext(Dispatchers.IO) { repo.healthSamples(date) }
            _state.update { it.copy(healthSamples = samples, healthSamplesDate = date, healthSeriesLoading = false) }
            val missingSeries = HealthSeries.entries.any { metric -> samples.none { it.metric == metric } }
            val canFetch = missingSeries &&
                date !in seriesFetchedDates &&
                _state.value.settings.hasGarminCredentials &&
                !_state.value.garminSync.running
            if (!canFetch) return@launch
            seriesFetchedDates += date
            _state.update { it.copy(healthSeriesLoading = true) }
            try {
                withContext(Dispatchers.IO) { repo.syncHealthSeriesForDate(date) }
                val fetched = withContext(Dispatchers.IO) { repo.healthSamples(date) }
                _state.update { st ->
                    if (st.healthSamplesDate == date) {
                        st.copy(healthSamples = fetched, healthSeriesLoading = false)
                    } else {
                        st.copy(healthSeriesLoading = false)
                    }
                }
            } catch (e: CancellationException) {
                seriesFetchedDates.remove(date)
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(healthSeriesLoading = false) }
            }
        }
    }

    /**
     * Garmin's "Reload Chart" for one offloaded day: queue the reload, then keep looking for
     * the curves, because Garmin restores them in the background over the next few minutes.
     */
    fun reloadHealthCharts(date: String) {
        if (date.isBlank() || _state.value.healthReloadPending != null) return
        // Claim the slot before suspending, so a double tap cannot spend two reloads.
        _state.update {
            it.copy(healthReloadPending = date, healthReloadStatus = "Asking Garmin to reload $date…")
        }
        healthReloadJob = viewModelScope.launch {
            try {
                val row = withContext(Dispatchers.IO) { repo.requestHealthChartReload(date) }
                _state.update { it.copy(healthReloadStatus = row.message) }
                if (row.state == HealthReloadState.REQUESTED) awaitReloadedCharts(date)
            } catch (e: CancellationException) {
                throw e
            } catch (err: Exception) {
                val message = err.message ?: "Garmin chart reload failed"
                _state.update { it.copy(healthReloadStatus = message) }
            } finally {
                _state.update { it.copy(healthReloadPending = null) }
            }
        }
    }

    /** Look for the curves now, without spending another reload on Garmin's daily quota. */
    fun checkHealthCharts(date: String) {
        if (date.isBlank() || _state.value.healthReloadPending != null) return
        _state.update { it.copy(healthReloadPending = date, healthReloadStatus = null) }
        healthReloadJob = viewModelScope.launch {
            try {
                if (fetchReloadedCharts(date) == 0) {
                    _state.update { it.copy(healthReloadStatus = "Garmin has not sent this day's charts yet.") }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (err: Exception) {
                _state.update { it.copy(healthReloadStatus = err.message ?: "Could not load the charts") }
            } finally {
                _state.update { it.copy(healthReloadPending = null) }
            }
        }
    }

    private suspend fun awaitReloadedCharts(date: String) {
        HEALTH_RELOAD_POLL_DELAYS_MILLIS.forEach { wait ->
            delay(wait)
            if (fetchReloadedCharts(date) > 0) return
        }
        _state.update {
            it.copy(healthReloadStatus = "Garmin is still working on this day. Check again in a few minutes.")
        }
    }

    /** Re-asks for every metric: a reload replaces the day, so partial stored curves are stale. */
    private suspend fun fetchReloadedCharts(date: String): Int {
        seriesFetchedDates.remove(date)
        val count = withContext(Dispatchers.IO) { repo.syncHealthSeriesForDate(date, force = true) }
        val samples = withContext(Dispatchers.IO) { repo.healthSamples(date) }
        _state.update { st ->
            val next = if (st.healthSamplesDate == date) st.copy(healthSamples = samples) else st
            if (count > 0) next.copy(healthReloadStatus = "Garmin sent this day's charts.") else next
        }
        if (count > 0) seriesFetchedDates += date
        return count
    }

    private suspend fun fetchDailyHealthIfMissing(date: String) {
        healthSummaryMutex.withLock {
            if (_state.value.dailyHealth.any { it.date == date }) {
                _state.update { it.copy(healthSummaryLoading = false, healthSummaryDate = date) }
                return
            }
            val parsed = runCatching { LocalDate.parse(date) }.getOrNull()
            val cannotDownload = parsed == null ||
                parsed.isAfter(LocalDate.now()) ||
                !_state.value.settings.hasGarminCredentials
            if (cannotDownload || date in summaryFetchedDates) {
                _state.update { it.copy(healthSummaryLoading = false, healthSummaryDate = date) }
                return
            }
            if (_state.value.garminSync.running) {
                _state.update { it.copy(healthSummaryLoading = true, healthSummaryDate = date) }
                return
            }
            _state.update { it.copy(healthSummaryLoading = true, healthSummaryDate = date) }
            try {
                val stored = withContext(Dispatchers.IO) { repo.syncDailyHealthForDate(date) }
                summaryFetchedDates += date
                if (stored == null) {
                    _state.update { st ->
                        if (st.healthSummaryDate == date) st.copy(healthSummaryLoading = false) else st
                    }
                } else {
                    _state.update { st ->
                        val days = if (st.dailyHealth.any { it.date == stored.date }) st.dailyHealth
                        else (st.dailyHealth + stored).sortedByDescending { it.date }
                        if (st.healthSummaryDate == date) {
                            st.copy(dailyHealth = days, healthSummaryLoading = false)
                        } else {
                            st.copy(dailyHealth = days)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { st ->
                    if (st.healthSummaryDate == date) st.copy(healthSummaryLoading = false) else st
                }
            }
        }
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
