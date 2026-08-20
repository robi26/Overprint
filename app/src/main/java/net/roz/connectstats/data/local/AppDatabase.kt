package net.roz.connectstats.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "activities")
data class ActivityEntity(
    @PrimaryKey val id: String,
    val externalId: String,
    val source: String,
    val name: String,
    val type: String,
    val startTimeMillis: Long,
    val location: String?,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val movingSeconds: Double,
    val elevationGainMeters: Double?,
    val calories: Double?,
    val avgHeartRate: Double?,
    val maxHeartRate: Double?,
    val avgSpeedMps: Double?,
    val maxSpeedMps: Double?,
    val avgCadence: Double?,
    val avgPower: Double?,
    val maxPower: Double?,
    val avgGrade: Double?,
    val startLatitude: Double?,
    val startLongitude: Double?,
    val deviceName: String?,
    val hasTrack: Boolean,
    val notes: String?,
    val deleted: Boolean = false,
)

@Entity(
    tableName = "track_points",
    indices = [Index("activityId")],
)
data class TrackPointEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val activityId: String,
    val timestampMillis: Long,
    val elapsedSeconds: Double,
    val latitude: Double?,
    val longitude: Double?,
    val altitudeMeters: Double?,
    val distanceMeters: Double?,
    val speedMps: Double?,
    val heartRate: Double?,
    val cadence: Double?,
    val power: Double?,
    val gradePercent: Double?,
    val temperatureC: Double?,
)

@Entity(
    tableName = "laps",
    indices = [Index("activityId")],
)
data class LapEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val activityId: String,
    val index: Int,
    val startTimeMillis: Long,
    val durationSeconds: Double,
    val distanceMeters: Double,
    val avgHeartRate: Double?,
    val maxHeartRate: Double?,
    val avgSpeedMps: Double?,
    val avgCadence: Double?,
    val avgPower: Double?,
    val elevationGainMeters: Double?,
    val label: String,
)

@Dao
interface ActivityDao {
    @Query("SELECT * FROM activities WHERE deleted = 0 ORDER BY startTimeMillis DESC")
    fun observeActivities(): Flow<List<ActivityEntity>>

    @Query("SELECT * FROM activities WHERE deleted = 1 ORDER BY startTimeMillis DESC")
    fun observeDeleted(): Flow<List<ActivityEntity>>

    @Query("SELECT * FROM activities WHERE deleted = 0 ORDER BY startTimeMillis DESC")
    suspend fun all(): List<ActivityEntity>

    @Query("SELECT * FROM activities WHERE id = :id")
    suspend fun byId(id: String): ActivityEntity?

    @Query("SELECT * FROM activities WHERE deleted = 0 AND (name LIKE '%' || :q || '%' OR location LIKE '%' || :q || '%' OR type LIKE '%' || :q || '%') ORDER BY startTimeMillis DESC")
    suspend fun search(q: String): List<ActivityEntity>

    @Query("SELECT id FROM activities WHERE deleted = 1")
    suspend fun deletedIds(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ActivityEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<ActivityEntity>)

    @Query("UPDATE activities SET deleted = 1 WHERE id = :id")
    suspend fun markDeleted(id: String): Int

    @Query("UPDATE activities SET deleted = 0 WHERE id = :id")
    suspend fun restore(id: String): Int

    @Query("DELETE FROM activities WHERE id = :id")
    suspend fun delete(id: String): Int

    @Query("SELECT id FROM activities WHERE source = :source")
    suspend fun idsBySource(source: String): List<String>

    @Query("SELECT id FROM activities WHERE hasTrack = 1")
    suspend fun idsWithTrack(): List<String>

    @Query("DELETE FROM activities")
    suspend fun clear(): Int

    @Query("SELECT COUNT(*) FROM activities")
    suspend fun count(): Int
}

data class GpsSample(
    val activityId: String,
    val latitude: Double?,
    val longitude: Double?,
)

@Dao
interface TrackDao {
    @Query("SELECT * FROM track_points WHERE activityId = :id ORDER BY elapsedSeconds ASC")
    suspend fun forActivity(id: String): List<TrackPointEntity>

    @Query(
        """
        SELECT activityId, latitude, longitude FROM track_points
        WHERE activityId IN (:activityIds)
          AND latitude IS NOT NULL AND longitude IS NOT NULL
          AND (elapsedSeconds < 1.0 OR CAST(elapsedSeconds AS INTEGER) % :stride = 0)
        ORDER BY activityId ASC, elapsedSeconds ASC
        """,
    )
    suspend fun gpsSamplesFor(activityIds: List<String>, stride: Int): List<GpsSample>

    @Insert
    suspend fun insertAll(points: List<TrackPointEntity>)

    @Query("DELETE FROM track_points WHERE activityId = :id")
    suspend fun deleteFor(id: String): Int

    @Query("DELETE FROM track_points")
    suspend fun clear(): Int
}

@Dao
interface LapDao {
    @Query("SELECT * FROM laps WHERE activityId = :id ORDER BY `index` ASC")
    suspend fun forActivity(id: String): List<LapEntity>

    @Insert
    suspend fun insertAll(laps: List<LapEntity>)

    @Query("DELETE FROM laps WHERE activityId = :id")
    suspend fun deleteFor(id: String): Int

    @Query("DELETE FROM laps")
    suspend fun clear(): Int
}

@Database(
    entities = [ActivityEntity::class, TrackPointEntity::class, LapEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun activities(): ActivityDao
    abstract fun tracks(): TrackDao
    abstract fun laps(): LapDao

    @Transaction
    suspend fun replaceDetail(activity: ActivityEntity, track: List<TrackPointEntity>, laps: List<LapEntity>) {
        activities().upsert(activity)
        tracks().deleteFor(activity.id)
        laps().deleteFor(activity.id)
        if (track.isNotEmpty()) tracks().insertAll(track)
        if (laps.isNotEmpty()) laps().insertAll(laps)
    }
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE activities ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
    }
}
