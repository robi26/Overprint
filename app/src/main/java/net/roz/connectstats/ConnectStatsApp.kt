package net.roz.connectstats

import android.app.Application
import androidx.room.Room
import net.roz.connectstats.data.local.AppDatabase
import net.roz.connectstats.data.prefs.SettingsStore
import net.roz.connectstats.data.repo.ActivityRepository

class ConnectStatsApp : Application() {
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
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
        settings = SettingsStore(this)
        repository = ActivityRepository(database, settings)
    }

    companion object {
        lateinit var instance: ConnectStatsApp
            private set
    }
}
