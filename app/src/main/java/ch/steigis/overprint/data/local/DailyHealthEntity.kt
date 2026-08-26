package ch.steigis.overprint.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import ch.steigis.overprint.domain.model.DailyHealth
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "daily_health")
data class DailyHealthEntity(
    @PrimaryKey val date: String,
    val steps: Double?,
    val stepGoal: Double?,
    val distanceMeters: Double?,
    val caloriesTotal: Double?,
    val caloriesActive: Double?,
    val caloriesBmr: Double?,
    val restingHr: Double?,
    val minHr: Double?,
    val maxHr: Double?,
    val sleepSeconds: Double?,
    val sleepScore: Double?,
    val sleepDeepSeconds: Double?,
    val sleepLightSeconds: Double?,
    val sleepRemSeconds: Double?,
    val sleepAwakeSeconds: Double?,
    val intensityModerate: Double?,
    val intensityVigorous: Double?,
    val stressAvg: Double?,
    val stressMax: Double?,
    val bodyBatteryCharged: Double?,
    val bodyBatteryDrained: Double?,
    val bodyBatteryHigh: Double?,
    val bodyBatteryLow: Double?,
    val bodyBatteryLatest: Double?,
    val floorsUp: Double?,
    val floorsDown: Double?,
    val spo2Avg: Double?,
    val spo2Min: Double?,
    val respirationAvg: Double?,
    val respirationMin: Double?,
    val respirationMax: Double?,
    val updatedAtMillis: Long,
)

@Dao
interface DailyHealthDao {
    @Query("SELECT * FROM daily_health ORDER BY date DESC")
    fun observeAll(): Flow<List<DailyHealthEntity>>

    @Query("SELECT * FROM daily_health WHERE date = :date")
    suspend fun byDate(date: String): DailyHealthEntity?

    @Query("SELECT MIN(date) FROM daily_health")
    suspend fun oldestDate(): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DailyHealthEntity)
}

internal fun DailyHealthEntity.toModel() = DailyHealth(
    date, steps, stepGoal, distanceMeters, caloriesTotal, caloriesActive, caloriesBmr,
    restingHr, minHr, maxHr, sleepSeconds, sleepScore, sleepDeepSeconds, sleepLightSeconds,
    sleepRemSeconds, sleepAwakeSeconds, intensityModerate, intensityVigorous, stressAvg, stressMax,
    bodyBatteryCharged, bodyBatteryDrained, bodyBatteryHigh, bodyBatteryLow, bodyBatteryLatest,
    floorsUp, floorsDown, spo2Avg, spo2Min, respirationAvg, respirationMin, respirationMax,
    updatedAtMillis,
)

internal fun DailyHealth.toEntity() = DailyHealthEntity(
    date, steps, stepGoal, distanceMeters, caloriesTotal, caloriesActive, caloriesBmr,
    restingHr, minHr, maxHr, sleepSeconds, sleepScore, sleepDeepSeconds, sleepLightSeconds,
    sleepRemSeconds, sleepAwakeSeconds, intensityModerate, intensityVigorous, stressAvg, stressMax,
    bodyBatteryCharged, bodyBatteryDrained, bodyBatteryHigh, bodyBatteryLow, bodyBatteryLatest,
    floorsUp, floorsDown, spo2Avg, spo2Min, respirationAvg, respirationMin, respirationMax,
    updatedAtMillis,
)
