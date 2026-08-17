package net.roz.connectstats.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("connectstats")

data class AppSettings(
    val metric: Boolean = true,
    val demoLoaded: Boolean = false,
    val garminEnabled: Boolean = false,
    val garminUsername: String = "",
    val garminPassword: String = "",
    val maxHeartRate: Double = 190.0,
    val ftpWatts: Double = 250.0,
    val themeMode: String = THEME_SYSTEM,
) {
    companion object {
        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"
    }
}

class SettingsStore(private val context: Context) {
    val settings: Flow<AppSettings> = context.dataStore.data.map { it.toSettings() }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { prefs ->
            val next = transform(prefs.toSettings())
            prefs[Keys.METRIC] = next.metric
            prefs[Keys.DEMO] = next.demoLoaded
            prefs[Keys.GARMIN] = next.garminEnabled
            prefs[Keys.GARMIN_USER] = next.garminUsername
            prefs[Keys.GARMIN_PASSWORD] = next.garminPassword
            prefs.remove(stringPreferencesKey("garmin_cookies"))
            prefs.remove(stringPreferencesKey("garmin_token"))
            prefs.remove(booleanPreferencesKey("strava"))
            prefs.remove(stringPreferencesKey("strava_id"))
            prefs.remove(stringPreferencesKey("strava_secret"))
            prefs.remove(stringPreferencesKey("strava_access"))
            prefs.remove(stringPreferencesKey("strava_refresh"))
            prefs.remove(stringPreferencesKey("strava_exp"))
            prefs[Keys.MAX_HR] = next.maxHeartRate
            prefs[Keys.FTP] = next.ftpWatts
            prefs[Keys.THEME] = next.themeMode
        }
    }

    private fun Preferences.toSettings() = AppSettings(
        metric = this[Keys.METRIC] ?: true,
        demoLoaded = this[Keys.DEMO] ?: false,
        garminEnabled = this[Keys.GARMIN] ?: false,
        garminUsername = this[Keys.GARMIN_USER] ?: "",
        garminPassword = this[Keys.GARMIN_PASSWORD] ?: "",
        maxHeartRate = this[Keys.MAX_HR] ?: 190.0,
        ftpWatts = this[Keys.FTP] ?: 250.0,
        themeMode = this[Keys.THEME] ?: AppSettings.THEME_SYSTEM,
    )

    private object Keys {
        val METRIC = booleanPreferencesKey("metric")
        val DEMO = booleanPreferencesKey("demo")
        val GARMIN = booleanPreferencesKey("garmin")
        val GARMIN_USER = stringPreferencesKey("garmin_user")
        val GARMIN_PASSWORD = stringPreferencesKey("garmin_password")
        val MAX_HR = doublePreferencesKey("max_hr")
        val FTP = doublePreferencesKey("ftp")
        val THEME = stringPreferencesKey("theme_mode")
    }
}
