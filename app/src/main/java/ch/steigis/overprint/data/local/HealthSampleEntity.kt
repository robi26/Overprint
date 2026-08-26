package ch.steigis.overprint.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import ch.steigis.overprint.domain.model.HealthSample
import ch.steigis.overprint.domain.model.HealthSeries

@Entity(
    tableName = "health_samples",
    indices = [Index(value = ["date", "metric"])],
)
data class HealthSampleEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val date: String,
    val metric: String,
    val timestampMillis: Long,
    val value: Double,
)

@Dao
interface HealthSampleDao {
    @Query("SELECT * FROM health_samples WHERE date = :date ORDER BY timestampMillis ASC")
    suspend fun forDate(date: String): List<HealthSampleEntity>

    @Query("SELECT COUNT(*) FROM health_samples WHERE date = :date AND metric = :metric")
    suspend fun countFor(date: String, metric: String): Int

    @Query("SELECT DISTINCT metric FROM health_samples WHERE date = :date")
    suspend fun metricsForDate(date: String): List<String>

    @Query("SELECT DISTINCT date AS date, metric AS metric FROM health_samples WHERE date BETWEEN :start AND :end")
    suspend fun presentMetrics(start: String, end: String): List<HealthMetricKey>

    @Query("DELETE FROM health_samples WHERE date = :date AND metric = :metric")
    suspend fun deleteFor(date: String, metric: String)

    @Insert
    suspend fun insertAll(samples: List<HealthSampleEntity>)

    @Transaction
    suspend fun replaceMetric(date: String, metric: String, samples: List<HealthSampleEntity>) {
        deleteFor(date, metric)
        if (samples.isNotEmpty()) insertAll(samples)
    }
}

internal fun HealthSampleEntity.toModel() = HealthSample(
    date = date,
    metric = HealthSeries.valueOf(metric),
    timestampMillis = timestampMillis,
    value = value,
)

internal fun HealthSample.toEntity() = HealthSampleEntity(
    date = date,
    metric = metric.name,
    timestampMillis = timestampMillis,
    value = value,
)

data class HealthMetricKey(
    val date: String,
    val metric: String,
)
