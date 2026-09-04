package ch.steigis.overprint.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import ch.steigis.overprint.domain.model.HealthChartReload
import ch.steigis.overprint.domain.model.HealthReloadState
import kotlinx.coroutines.flow.Flow

/** Where one day stands in Garmin's "Reload Chart" flow. See [HealthChartReload]. */
@Entity(tableName = "health_reload")
data class HealthReloadEntity(
    @PrimaryKey val date: String,
    val state: String,
    val requestedAtMillis: Long,
    val checkedAtMillis: Long,
    val message: String?,
)

@Dao
interface HealthReloadDao {
    @Query("SELECT * FROM health_reload ORDER BY date DESC")
    fun observeAll(): Flow<List<HealthReloadEntity>>

    @Query("SELECT * FROM health_reload WHERE date = :date")
    suspend fun byDate(date: String): HealthReloadEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: HealthReloadEntity)

    @Query("DELETE FROM health_reload WHERE date = :date")
    suspend fun deleteFor(date: String)
}

internal fun HealthReloadEntity.toModel() = HealthChartReload(
    date = date,
    state = runCatching { HealthReloadState.valueOf(state) }.getOrDefault(HealthReloadState.OFFLOADED),
    requestedAtMillis = requestedAtMillis,
    checkedAtMillis = checkedAtMillis,
    message = message,
)

internal fun HealthChartReload.toEntity() = HealthReloadEntity(
    date = date,
    state = state.name,
    requestedAtMillis = requestedAtMillis,
    checkedAtMillis = checkedAtMillis,
    message = message,
)
