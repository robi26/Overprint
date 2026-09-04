package ch.steigis.overprint.data.prefs

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(
    name = "connectstats",
    produceMigrations = { listOf(EncryptLegacyGarminPassword) },
)

data class AppSettings(
    val metric: Boolean = true,
    val demoLoaded: Boolean = false,
    val garminEnabled: Boolean = false,
    val garminUsername: String = "",
    val garminPassword: String = "",
    val garminToken: String = "",
    val maxHeartRate: Double = 190.0,
    val ftpWatts: Double = 250.0,
    val themeMode: String = THEME_SYSTEM,
    /**
     * Oldest day the Health history walk has already asked Garmin for. Kept apart from the
     * oldest stored day so a window Garmin answers with nothing still moves the walk back.
     */
    val healthHistoryOldest: String = "",
) {
    val hasGarminCredentials: Boolean
        get() = garminUsername.isNotBlank() && garminPassword.isNotBlank()

    /**
     * The generated data-class toString would print the password and token verbatim into
     * any log line or crash report that touches settings or UI state, so redact them here.
     */
    override fun toString(): String = "AppSettings(metric=$metric, demoLoaded=$demoLoaded, " +
        "garminEnabled=$garminEnabled, garminUsername=${mask(garminUsername)}, " +
        "garminPassword=${mask(garminPassword)}, garminToken=${mask(garminToken)}, " +
        "maxHeartRate=$maxHeartRate, ftpWatts=$ftpWatts, themeMode=$themeMode, " +
        "healthHistoryOldest=$healthHistoryOldest)"

    private fun mask(value: String) = if (value.isBlank()) "<unset>" else "<set>"

    companion object {
        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"
    }
}

class SettingsStore(private val context: Context) {
    /** Decryption is a Keystore round trip per emission, so keep it off the collector's thread. */
    val settings: Flow<AppSettings> = context.dataStore.data
        .map { it.toSettings() }
        .flowOn(Dispatchers.IO)

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { prefs ->
            val current = prefs.toSettings()
            val next = transform(current)
            prefs[Keys.METRIC] = next.metric
            prefs[Keys.DEMO] = next.demoLoaded
            prefs[Keys.GARMIN] = next.garminEnabled
            prefs[Keys.GARMIN_USER] = next.garminUsername
            // Touch a secret only when its plaintext actually changed. Re-encrypting on every
            // unrelated write (a theme change, an FTP edit) would let a single transient
            // Keystore failure erase the stored credentials - and toSettings() reports a failed
            // decrypt as blank, so that blank would be written straight back over the ciphertext.
            if (next.garminPassword != current.garminPassword) {
                prefs.putSecret(Keys.GARMIN_PASSWORD, next.garminPassword)
            }
            if (next.garminToken != current.garminToken) {
                prefs.putSecret(Keys.GARMIN_TOKEN, next.garminToken)
            }
            prefs.remove(stringPreferencesKey("garmin_cookies"))
            // Plaintext secrets written by earlier builds, in case the migration was skipped.
            prefs.remove(stringPreferencesKey("garmin_password"))
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
            prefs[Keys.HEALTH_HISTORY_OLDEST] = next.healthHistoryOldest
        }
    }

    /**
     * Wipes the Garmin email, password and access token from disk. The secret keys are removed
     * outright rather than routed through [update]: a ciphertext that can no longer be decrypted
     * reads as blank, so the changed-only rule there would leave it sitting on disk forever.
     */
    suspend fun clearGarminCredentials() {
        context.dataStore.edit { prefs ->
            prefs[Keys.GARMIN] = false
            prefs[Keys.GARMIN_USER] = ""
            prefs.remove(Keys.GARMIN_PASSWORD)
            prefs.remove(Keys.GARMIN_TOKEN)
        }
    }

    private fun Preferences.toSettings() = AppSettings(
        metric = this[Keys.METRIC] ?: true,
        demoLoaded = this[Keys.DEMO] ?: false,
        garminEnabled = this[Keys.GARMIN] ?: false,
        garminUsername = this[Keys.GARMIN_USER] ?: "",
        garminPassword = SecretCipher.decrypt(this[Keys.GARMIN_PASSWORD] ?: ""),
        garminToken = SecretCipher.decrypt(this[Keys.GARMIN_TOKEN] ?: ""),
        maxHeartRate = this[Keys.MAX_HR] ?: 190.0,
        ftpWatts = this[Keys.FTP] ?: 250.0,
        themeMode = this[Keys.THEME] ?: AppSettings.THEME_SYSTEM,
        healthHistoryOldest = this[Keys.HEALTH_HISTORY_OLDEST] ?: "",
    )

    internal object Keys {
        val METRIC = booleanPreferencesKey("metric")
        val DEMO = booleanPreferencesKey("demo")
        val GARMIN = booleanPreferencesKey("garmin")
        val GARMIN_USER = stringPreferencesKey("garmin_user")
        val GARMIN_PASSWORD = stringPreferencesKey("garmin_password_enc")
        val GARMIN_TOKEN = stringPreferencesKey("garmin_token_enc")
        val MAX_HR = doublePreferencesKey("max_hr")
        val FTP = doublePreferencesKey("ftp")
        val THEME = stringPreferencesKey("theme_mode")
        val HEALTH_HISTORY_OLDEST = stringPreferencesKey("health_history_oldest")
    }
}

/**
 * Secrets only ever reach disk through here: encrypted, or not at all. A blank value, or a
 * Keystore that refuses to encrypt, removes the key instead of falling back to plaintext.
 */
private fun MutablePreferences.putSecret(key: Preferences.Key<String>, value: String) {
    val sealed = SecretCipher.encrypt(value)
    if (sealed == null) remove(key) else set(key, sealed)
}

/**
 * Earlier builds stored the Garmin password as plaintext under "garmin_password". Move it
 * into the encrypted key on first read and delete the plaintext copy.
 */
private object EncryptLegacyGarminPassword : DataMigration<Preferences> {
    private val LEGACY_PASSWORD = stringPreferencesKey("garmin_password")

    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        currentData.contains(LEGACY_PASSWORD)

    override suspend fun migrate(currentData: Preferences): Preferences {
        val legacy = currentData[LEGACY_PASSWORD].orEmpty()
        val migrated = currentData.toMutablePreferences()
        migrated.remove(LEGACY_PASSWORD)
        if (legacy.isNotEmpty() && !migrated.contains(SettingsStore.Keys.GARMIN_PASSWORD)) {
            migrated.putSecret(SettingsStore.Keys.GARMIN_PASSWORD, legacy)
        }
        return migrated
    }

    override suspend fun cleanUp() = Unit
}
