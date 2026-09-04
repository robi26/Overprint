package ch.steigis.overprint

import android.app.Application
import androidx.room.Room
import ch.steigis.overprint.data.local.AppDatabase
import ch.steigis.overprint.data.local.MIGRATION_1_2
import ch.steigis.overprint.data.local.MIGRATION_2_3
import ch.steigis.overprint.data.local.MIGRATION_3_4
import ch.steigis.overprint.data.local.MIGRATION_4_5
import ch.steigis.overprint.data.local.MIGRATION_5_6
import ch.steigis.overprint.data.local.MIGRATION_6_7
import ch.steigis.overprint.data.prefs.SettingsStore
import ch.steigis.overprint.data.repo.ActivityRepository

class OverprintApp : Application() {
    lateinit var database: AppDatabase
        private set
    lateinit var settings: SettingsStore
        private set
    lateinit var repository: ActivityRepository
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        database = Room.databaseBuilder(this, AppDatabase::class.java, "connectstats.db")
            .addMigrations(
                MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
                MIGRATION_6_7,
            )
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
        settings = SettingsStore(this)
        repository = ActivityRepository(database, settings)
    }

    companion object {
        lateinit var instance: OverprintApp
            private set
    }
}
